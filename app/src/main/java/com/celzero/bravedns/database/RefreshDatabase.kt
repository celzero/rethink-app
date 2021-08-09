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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.NO_PACKAGE
import com.celzero.bravedns.util.Constants.Companion.REFRESH_APP_DURATION
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_DB
import com.celzero.bravedns.util.PlayStoreCategory
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.isAtleastO
import com.google.common.collect.Sets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit
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
    }

    private val refreshMutex = ReentrantReadWriteLock()

    @GuardedBy("refreshMutex") @Volatile private var isRefreshInProgress: Boolean = false

    /**
     * Need to rewrite the logic for adding the apps in the database and removing it during uninstall.
     */
    fun refreshAppInfoDatabase(isForceRefresh: Boolean) {
        if (DEBUG) Log.d(LOG_TAG_APP_DB, "Initiated refresh application info")

        refreshMutex.read {
            if (isRefreshInProgress) return
        }

        if (!isRefreshCheckRequired(isForceRefresh) && FirewallManager.getTotalApps() != 0) return

        // Synchronization is deprecated in kotlin, so using Lock.withLock with ReentrantLock.
        // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/synchronized.html
        // ref: https://chris-ribetti.medium.com/synchronized-to-reentrantlock-6f045519577e
        refreshMutex.write {
            if (isRefreshInProgress) {
                return
            }

            isRefreshInProgress = true
        }

        CoroutineScope(Dispatchers.IO).launch {

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

            if (DEBUG) Log.d(LOG_TAG_APP_DB,
                             "Refresh Database, packagesToDelete -> $packagesToDelete")
            if (DEBUG) Log.d(LOG_TAG_APP_DB, "Refresh Database, packagesToAdd -> $packagesToAdd")

            // Add the missing packages to the database
            addMissingPackages(packagesToAdd)

            // update app refresh time to current time in persistent state
            persistentState.lastAppRefreshTime = System.currentTimeMillis()

            isRefreshInProgress = false
        }
    }

    // TODO: Ideally this should be in FirewallManager
    private fun addMissingPackages(apps: HashSet<FirewallManager.AppInfoTuple>) {
        if (apps.size <= 0) return

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

            entry.appCategory = determineAppCategory(appInfo)

            FirewallManager.persistAppInfo(entry)

        }
        updateCategoryRepo()
    }

    // Refresh database is called from Homescreenactivity's onResume().
    // Now the refresh will be called if the last updated time is greater than
    // REFRESH_APP_DURATION(3hrs) / if appList is empty.
    // This will avoid too frequent refresh calls.
    private fun isRefreshCheckRequired(forceRefresh: Boolean): Boolean {
        if (forceRefresh) return true

        val timeDifference = System.currentTimeMillis() - persistentState.lastAppRefreshTime
        val hours = TimeUnit.MILLISECONDS.toHours(timeDifference)

        return hours > REFRESH_APP_DURATION
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
    fun registerNonApp(uid: Int, appName: String) {
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

    fun updateCategoryRepo() {
        if (DEBUG) Log.d(LOG_TAG_APP_DB, "RefreshDatabase - Call for updateCategoryDB")
        CoroutineScope(Dispatchers.IO).launch {
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
