/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.database

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.FirewallActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.NO_PACKAGE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_DB
import com.celzero.bravedns.util.Utilities.Companion.isAtleastO
import com.google.common.collect.Sets
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class RefreshDatabase internal constructor(private var context: Context,
                                           private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
                                           private val categoryInfoRepository: CategoryInfoRepository,
                                           private val doHEndpointRepository: DoHEndpointRepository,
                                           private val connTrackerRepository: ConnectionTrackerRepository,
                                           private val dnsLogRepository: DNSLogRepository,
                                           private val dnsCryptEndpointRepository: DNSCryptEndpointRepository,
                                           private val dnsCryptRelayEndpointRepository: DNSCryptRelayEndpointRepository,
                                           private val persistentState: PersistentState) {


    companion object {
        private const val PROXY_EXTERNAL = "External"
        private const val APP_NAME_NO_APP = "Nobody"

        private const val NEW_APP_BULK_CHECK_COUNT = 5

        private const val NOTIF_NEW_APP_BULK = "NewAppBulkInstall"

        private const val NOTIF_NEW_APP = "NewAppInstall"
        const val NOTIF_ID_NEW_APP = 106
    }

    private val refreshMutex = ReentrantReadWriteLock()

    @GuardedBy("refreshMutex") @Volatile private var isRefreshInProgress: Boolean = false

    /**
     * Need to rewrite the logic for adding the apps in the database and removing it during uninstall.
     */
    fun refreshAppInfoDatabase() {
        if (DEBUG) Log.d(LOG_TAG_APP_DB, "Initiated refresh application info")

        refreshMutex.read {
            if (isRefreshInProgress) {
                return
            }
        }

        refreshMutex.write {
            if (isRefreshInProgress) {
                return
            }

            isRefreshInProgress = true
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirewallManager.loadAppFirewallRules()

                // Get app details from Global variable
                val trackedApps = FirewallManager.getPackageNames()

                val installedPackages: List<PackageInfo> = context.packageManager?.getInstalledPackages(
                    PackageManager.GET_META_DATA) as List<PackageInfo>

                val installedApps = installedPackages.map {
                    FirewallManager.AppInfoTuple(it.applicationInfo.uid, it.packageName)
                }.toHashSet()

                // packages which are all available in database but not in installed apps(package manager)
                val packagesToDelete = Sets.difference(trackedApps, installedApps).filter {
                    !it.packageName.contains(NO_PACKAGE)
                }.toHashSet()

                // packages which are all available in installed apps list but not in database
                val packagesToAdd = Sets.difference(installedApps, trackedApps).filter {
                    // Remove this package
                    it.packageName != context.packageName
                }.toHashSet()

                FirewallManager.deletePackagesFromCache(packagesToDelete)

                Log.i(LOG_TAG_APP_DB, "Refresh Database, packagesToDelete -> $packagesToDelete")
                Log.i(LOG_TAG_APP_DB, "Refresh Database, packagesToAdd -> $packagesToAdd")

                // Add the missing packages to the database
                addMissingPackages(packagesToAdd)

                updateCategoryRepo()
            } catch (e: RuntimeException) {
                Log.e(LOG_TAG_APP_DB, e.message, e)
                throw e
            } finally {
                withContext(NonCancellable) {
                    refreshMutex.write {
                        isRefreshInProgress = false
                    }
                }
            }
        }
    }

    // TODO: Ideally this should be in FirewallManager
    private suspend fun addMissingPackages(apps: HashSet<FirewallManager.AppInfoTuple>) {
        if (apps.size <= 0) return

        handleNewAppNotification(apps)

        // Contains the app list which are not part of rethink database but available
        // in package manager's installed list.
        apps.forEach {
            if (it.packageName == context.applicationContext.packageName) return@forEach

            val appInfo = Utilities.getApplicationInfo(context, it.packageName) ?: return@forEach

            val appName = context.packageManager.getApplicationLabel(appInfo).toString()
            if (DEBUG) Log.d(LOG_TAG_APP_DB, "Refresh Database, AppInfo -> $appName")

            val isSystemApp = isSystemApp(appInfo)
            val isSystemComponent = isSystemComponent(appInfo)
            val entry = AppInfo()

            entry.appName = appName

            entry.packageInfo = appInfo.packageName
            entry.uid = appInfo.uid

            entry.whiteListUniv1 = isSystemComponent
            entry.isSystemApp = isSystemApp

            // default value of apps internet permission is true, when the universal firewall
            // parameter (blockNewlyInstalledApp is true) firewall the app
            if (persistentState.blockNewlyInstalledApp) {
                entry.isInternetAllowed = false
            }

            entry.appCategory = determineAppCategory(appInfo)

            FirewallManager.persistAppInfo(entry)

        }
    }

    private fun handleNewAppNotification(apps: HashSet<FirewallManager.AppInfoTuple>) {
        // no need to notify if the Universal setting is off
        if (!persistentState.blockNewlyInstalledApp) return

        // if there is no apps in the cache, don't show the notification
        if (FirewallManager.getTotalApps() == 0) return

        // Show bulk notification when the app size is greater than NEW_APP_BULK_CHECK_COUNT(5)
        if (apps.size > NEW_APP_BULK_CHECK_COUNT) {
            showNewAppsBulkNotification(apps.size)
            return
        }

        // show notification for particular app (less than NEW_APP_BULK_CHECK_COUNT)
        apps.forEach {
            showNewAppNotification(it)
        }
    }

    private fun showNewAppsBulkNotification(appSize: Int) {
        val notificationManager = context.getSystemService(
            VpnService.NOTIFICATION_SERVICE) as NotificationManager
        if (DEBUG) Log.d(LoggerConstants.LOG_TAG_VPN,
                         "Number of new apps: $appSize, show notification")

        val intent = Intent(context, FirewallActivity::class.java)
        intent.putExtra(Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME,
                        Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE)

        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
                                                      PendingIntent.FLAG_ONE_SHOT)

        var builder: NotificationCompat.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = NOTIF_NEW_APP_BULK
            val description = context.resources.getString(
                R.string.new_app_bulk_notification_content, appSize.toString())
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_NEW_APP_BULK, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, NOTIF_NEW_APP_BULK)
        } else {
            builder = NotificationCompat.Builder(context, NOTIF_NEW_APP_BULK)
        }

        val contentTitle: String = context.resources.getString(
            R.string.new_app_bulk_notification_title)
        val contentText: String = context.resources.getString(
            R.string.new_app_bulk_notification_content, appSize.toString())

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            pendingIntent).setContentText(contentText)

        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(context, Utilities.getThemeAccent(context))

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // Cancel the notification after clicking.
        builder.setAutoCancel(true)

        val notificationId = Random()
        notificationManager.notify(notificationId.nextInt(100), builder.build())
    }

    private fun showNewAppNotification(app: FirewallManager.AppInfoTuple) {
        val appInfo = Utilities.getApplicationInfo(context, app.packageName) ?: return
        val appName = context.packageManager.getApplicationLabel(appInfo).toString()

        val notificationManager = context.getSystemService(
            VpnService.NOTIFICATION_SERVICE) as NotificationManager
        if (DEBUG) Log.d(LoggerConstants.LOG_TAG_VPN,
                         "New app installed: $appName, show notification")

        val intent = Intent(context, FirewallActivity::class.java)
        intent.putExtra(Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME,
                        Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE)

        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
                                                      PendingIntent.FLAG_UPDATE_CURRENT)
        var nbuilder: NotificationCompat.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = NOTIF_NEW_APP
            val description = context.resources.getString(R.string.new_app_notification_content,
                                                          appName)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_NEW_APP, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            nbuilder = NotificationCompat.Builder(context, NOTIF_NEW_APP)
        } else {
            nbuilder = NotificationCompat.Builder(context, NOTIF_NEW_APP)
        }

        val contentTitle: String = context.resources.getString(R.string.new_app_notification_title)
        val contentText: String = context.resources.getString(R.string.new_app_notification_content,
                                                              appName)

        nbuilder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            pendingIntent).setContentText(contentText)

        nbuilder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        nbuilder.color = ContextCompat.getColor(context, Utilities.getThemeAccent(context))

        val openIntent1 = makeNewAppVpnIntent(context, Constants.NOTIF_ACTION_NEW_APP_ALLOW,
                                              app.uid)

        val openIntent2 = makeNewAppVpnIntent(context, Constants.NOTIF_ACTION_NEW_APP_DENY, app.uid)
        val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0,
                                                                                      context.resources.getString(
                                                                                          R.string.new_app_notification_action_allow),
                                                                                      openIntent1)
        val notificationAction2: NotificationCompat.Action = NotificationCompat.Action(0,
                                                                                       context.resources.getString(
                                                                                           R.string.new_app_notification_action_deny),
                                                                                       openIntent2)
        nbuilder.addAction(notificationAction)
        nbuilder.addAction(notificationAction2)

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        nbuilder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // Cancel the notification after clicking.
        nbuilder.setAutoCancel(true)

        notificationManager.notify(NOTIF_ID_NEW_APP.toString(), app.uid, nbuilder.build())
    }

    private fun makeNewAppVpnIntent(context: Context, intentExtra: String,
                                    uid: Int): PendingIntent? {
        val intentAction = Intent(context, NotificationActionReceiver::class.java)
        intentAction.putExtra(Constants.NOTIFICATION_ACTION, intentExtra)
        intentAction.putExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, uid)
        return PendingIntent.getBroadcast(context, uid, intentAction,
                                          PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun isSystemApp(ai: ApplicationInfo): Boolean {
        return (ai.flags and ApplicationInfo.FLAG_SYSTEM > 0)
    }

    private fun isSystemComponent(ai: ApplicationInfo): Boolean {
        return isSystemApp(ai) && !AndroidUidConfig.isUidAppRange(ai.uid)
    }

    private fun determineAppCategory(ai: ApplicationInfo): String {
        // Removed the package name from the method fetchCategory().
        // As of now, the fetchCategory is returning Category as OTHERS.
        // Instead we can query play store for the category.
        // In that case, fetchCategory method should have packageInfo as
        // parameter.
        // val category = fetchCategory(appInfo.packageInfo)
        val cat = fetchCategory()

        if (!PlayStoreCategory.OTHER.name.equals(cat, ignoreCase = true)) {
            return replaceUnderscore(cat)
        }

        if (isSystemComponent(ai)) {
            return Constants.APP_CAT_SYSTEM_COMPONENTS
        }

        if (isSystemApp(ai)) {
            return Constants.APP_CAT_SYSTEM_APPS
        }

        if (isAtleastO()) {
            return replaceUnderscore(appInfoCategory(ai))
        }

        return Constants.INSTALLED_CAT_APPS

    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun appInfoCategory(ai: ApplicationInfo): String {
        val cat = ApplicationInfo.getCategoryTitle(context, ai.category)
        return cat?.toString() ?: Constants.APP_CAT_OTHER
    }

    private fun replaceUnderscore(s: String): String {
        return s.replace("_", " ")
    }

    // TODO: Ideally this should be in FirewallManager
    suspend fun registerNonApp(uid: Int, appName: String) {
        val appInfo = AppInfo()
        appInfo.appName = appName
        appInfo.packageInfo = "no_package_$uid"
        appInfo.appCategory = Constants.APP_NON_APP
        appInfo.isSystemApp = true
        appInfo.isDataEnabled = true
        appInfo.isWifiEnabled = true
        appInfo.isScreenOff = false
        appInfo.uid = uid
        appInfo.isInternetAllowed = true
        appInfo.isBackgroundEnabled = false
        appInfo.whiteListUniv1 = false
        appInfo.whiteListUniv2 = false
        appInfo.mobileDataUsed = 0
        appInfo.trackers = 0
        appInfo.wifiDataUsed = 0
        FirewallManager.persistAppInfo(appInfo)
        updateCategoryRepo()
    }

    fun insertDefaultDNSProxy() {
        CoroutineScope(Dispatchers.IO).launch {
            val proxyURL = context.resources.getStringArray(R.array.dns_proxy_names)
            val proxyIP = context.resources.getStringArray(R.array.dns_proxy_ips)
            val dnsProxyEndPoint1 = DNSProxyEndpoint(1, proxyURL[0], PROXY_EXTERNAL,
                                                     APP_NAME_NO_APP, proxyIP[0], 53, false, false,
                                                     0, 0)
            val dnsProxyEndPoint2 = DNSProxyEndpoint(2, proxyURL[1], PROXY_EXTERNAL,
                                                     APP_NAME_NO_APP, proxyIP[1], 53, false, false,
                                                     0, 0)
            val dnsProxyEndPoint3 = DNSProxyEndpoint(3, proxyURL[2], PROXY_EXTERNAL,
                                                     APP_NAME_NO_APP, proxyIP[2], 53, false, false,
                                                     0, 0)
            dnsProxyEndpointRepository.insertWithReplace(dnsProxyEndPoint1)
            dnsProxyEndpointRepository.insertWithReplace(dnsProxyEndPoint2)
            dnsProxyEndpointRepository.insertWithReplace(dnsProxyEndPoint3)
        }
    }


    fun deleteOlderDataFromNetworkLogs() {
        CoroutineScope(Dispatchers.IO).launch {
            /**
             * Removing the logs delete code based on the days. Instead added a count to keep
             * in the table.
             * Come up with some other configuration/logic to delete the user logs.(both
             * ConnectionTracker and DNSLogs.
             */
            dnsLogRepository.deleteConnectionTrackerCount()
            connTrackerRepository.deleteConnectionTrackerCount()
        }
    }

    private val CAT_SIZE = 9
    private val CATEGORY_STRING = "category/"
    private val CATEGORY_GAME_STRING = "GAME_" // All games start with this prefix

    suspend fun updateCategoryRepo() {
        if (DEBUG) Log.d(LOG_TAG_APP_DB, "RefreshDatabase - Call for updateCategoryDB")

        //Changes to remove the count queries.
        val categoryFromAppList = FirewallManager.getAppInfos()
        val applications = categoryFromAppList.distinctBy { a -> a.appCategory }

        applications.forEach {
            val categoryInfo = CategoryInfo()
            categoryInfo.categoryName = it.appCategory

            val excludedList = categoryFromAppList.filter { a -> a.isExcluded && a.appCategory == it.appCategory }
            val appsBlocked = categoryFromAppList.filter { a -> !a.isInternetAllowed && a.appCategory == it.appCategory }
            val whiteListedApps = categoryFromAppList.filter { a -> a.whiteListUniv1 && a.appCategory == it.appCategory }

            categoryInfo.numberOFApps = categoryFromAppList.filter { a -> a.appCategory == it.appCategory }.size
            categoryInfo.numOfAppsExcluded = excludedList.size
            categoryInfo.numOfAppWhitelisted = whiteListedApps.size
            categoryInfo.numOfAppsBlocked = appsBlocked.size
            categoryInfo.isInternetBlocked = (categoryInfo.numberOFApps == categoryInfo.numOfAppsBlocked)

            Log.i(LOG_TAG_APP_DB,
                  "categoryListFromAppList - ${categoryInfo.categoryName}, ${categoryInfo.numberOFApps}, ${categoryInfo.numOfAppsBlocked}, ${categoryInfo.isInternetBlocked}")
            categoryInfoRepository.insert(categoryInfo)
        }
    }

    /**
     * Below code to fetch the google play service-application category
     * Not in use as of now.
     */
    private fun fetchCategory(): String {
        return PlayStoreCategory.OTHER.name
        /*if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            return PlayStoreCategory.OTHER.name
        }else {
            try {
                val url =
                    "$APP_URL$packageName&hl=en" //https://play.google.com/store/apps/details?id=com.example.app&hl=en
                //Log.d(LOG_TAG,"Insert Category: $packageName")
                val categoryRaw = parseAndExtractCategory(url)
                //Log.d(LOG_TAG,"Insert Category2: $packageName")
                val storeCategory =
                    PlayStoreCategory.fromCategoryName(categoryRaw ?: PlayStoreCategory.OTHER.name)
                return storeCategory.name
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Exception while fetching the category:" + e.message, e)
                return PlayStoreCategory.OTHER.name
            }
        }*/

    }

    /*private fun parseAndExtractCategory(url: String): String? {
        return try {
            val text = Jsoup.connect(url).get()?.select("a[itemprop=genre]") ?: return null
            val href = text.attr("abs:href")

            if (href != null && href.length > 4 && href.contains(CATEGORY_STRING)) {
                getCategoryTypeByHref(href)
            } else {
                PlayStoreCategory.OTHER.name
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Parse Category" + e.message, e)
            //TODO handle error
            PlayStoreCategory.OTHER.name
        }
    }*/

    private fun getCategoryTypeByHref(href: String): String? {
        val appCategoryType = href.substring(href.indexOf(CATEGORY_STRING) + CAT_SIZE, href.length)
        return if (appCategoryType.contains(CATEGORY_GAME_STRING)) {
            PlayStoreCategory.GENERAL_GAMES_CATEGORY_NAME
        } else appCategoryType
    }

    fun insertDefaultDNSList() {
        val isAlreadyConnectionAvailable = doHEndpointRepository.getConnectedDoH()
        val urlName = context.resources.getStringArray(R.array.doh_endpoint_names)
        val urlValues = context.resources.getStringArray(R.array.doh_endpoint_urls)
        if (isAlreadyConnectionAvailable == null) {
            insertDefaultDOHList()
        } else {
            Log.w(LOG_TAG_APP_DB,
                  "Refresh Database, Already insertion done. Correct values for Cloudflare alone.")
            val doHEndpoint = DoHEndpoint(id = 3, urlName[2], urlValues[2],
                                          context.getString(R.string.dns_mode_2_explanation),
                                          isSelected = false, isCustom = false,
                                          modifiedDataTime = System.currentTimeMillis(),
                                          latency = 0)
            doHEndpointRepository.insertWithReplaceAsync(doHEndpoint)
        }

    }

    private fun insertDefaultDOHList() {
        val urlName = context.resources.getStringArray(R.array.doh_endpoint_names)
        val urlValues = context.resources.getStringArray(R.array.doh_endpoint_urls)
        val doHEndpoint1 = DoHEndpoint(id = 1, urlName[0], urlValues[0],
                                       context.getString(R.string.dns_mode_0_explanation),
                                       isSelected = false, isCustom = false,
                                       modifiedDataTime = System.currentTimeMillis(), latency = 0)
        val doHEndpoint2 = DoHEndpoint(id = 2, urlName[1], urlValues[1],
                                       context.getString(R.string.dns_mode_1_explanation),
                                       isSelected = false, isCustom = false,
                                       modifiedDataTime = System.currentTimeMillis(), latency = 0)
        val doHEndpoint3 = DoHEndpoint(id = 3, urlName[2], urlValues[2],
                                       context.getString(R.string.dns_mode_2_explanation),
                                       isSelected = false, isCustom = false,
                                       modifiedDataTime = System.currentTimeMillis(), latency = 0)
        val doHEndpoint4 = DoHEndpoint(id = 4, urlName[3], urlValues[3],
                                       context.getString(R.string.dns_mode_3_explanation), true,
                                       isCustom = false,
                                       modifiedDataTime = System.currentTimeMillis(), latency = 0)
        // Note: rethinkdns+ must always be at index 5; if not impl such as
        // AppMode#getDnsRethinkEndpoint will break
        val doHEndpoint5 = DoHEndpoint(id = 5, urlName[5], urlValues[5],
                                       context.getString(R.string.dns_mode_5_explanation),
                                       isSelected = false, isCustom = false,
                                       modifiedDataTime = System.currentTimeMillis(), latency = 0)

        doHEndpointRepository.insertWithReplaceAsync(doHEndpoint1)
        doHEndpointRepository.insertWithReplaceAsync(doHEndpoint2)
        doHEndpointRepository.insertWithReplaceAsync(doHEndpoint3)
        doHEndpointRepository.insertWithReplaceAsync(doHEndpoint4)
        doHEndpointRepository.insertWithReplaceAsync(doHEndpoint5)
    }


    fun insertDefaultDNSCryptList() {
        val urlName = context.resources.getStringArray(R.array.dns_crypt_endpoint_names)
        val urlValues = context.resources.getStringArray(R.array.dns_crypt_endpoint_urls)
        val urlDesc = context.resources.getStringArray(R.array.dns_crypt_endpoint_desc)

        val dnsCryptEndpoint1 = DNSCryptEndpoint(1, urlName[0], urlValues[0], urlDesc[0], false,
                                                 false, System.currentTimeMillis(), 0)
        val dnsCryptEndpoint2 = DNSCryptEndpoint(2, urlName[1], urlValues[1], urlDesc[1], false,
                                                 false, System.currentTimeMillis(), 0)
        val dnsCryptEndpoint3 = DNSCryptEndpoint(3, urlName[2], urlValues[2], urlDesc[2], false,
                                                 false, System.currentTimeMillis(), 0)
        val dnsCryptEndpoint4 = DNSCryptEndpoint(4, urlName[3], urlValues[3], urlDesc[3], false,
                                                 false, System.currentTimeMillis(), 0)
        val dnsCryptEndpoint5 = DNSCryptEndpoint(5, urlName[4], urlValues[4], urlDesc[4], false,
                                                 false, System.currentTimeMillis(), 0)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint1)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint2)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint3)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint4)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint5)

    }

    fun insertDefaultDNSCryptRelayList() {
        val urlName = context.resources.getStringArray(R.array.dns_crypt_relay_endpoint_names)
        val urlValues = context.resources.getStringArray(R.array.dns_crypt_relay_endpoint_urls)
        val urlDesc = context.resources.getStringArray(R.array.dns_crypt_relay_endpoint_desc)

        val dnsCryptRelayEndpoint1 = DNSCryptRelayEndpoint(1, urlName[0], urlValues[0], urlDesc[0],
                                                           false, false, System.currentTimeMillis(),
                                                           0)
        val dnsCryptRelayEndpoint2 = DNSCryptRelayEndpoint(2, urlName[1], urlValues[1], urlDesc[1],
                                                           false, false, System.currentTimeMillis(),
                                                           0)
        val dnsCryptRelayEndpoint3 = DNSCryptRelayEndpoint(3, urlName[2], urlValues[2], urlDesc[2],
                                                           false, false, System.currentTimeMillis(),
                                                           0)
        val dnsCryptRelayEndpoint4 = DNSCryptRelayEndpoint(4, urlName[3], urlValues[3], urlDesc[3],
                                                           false, false, System.currentTimeMillis(),
                                                           0)
        val dnsCryptRelayEndpoint5 = DNSCryptRelayEndpoint(5, urlName[4], urlValues[4], urlDesc[4],
                                                           false, false, System.currentTimeMillis(),
                                                           0)

        dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint1)
        dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint2)
        dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint3)
        dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint4)
        dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint5)
    }

}
