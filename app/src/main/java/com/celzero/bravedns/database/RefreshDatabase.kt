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
import com.celzero.bravedns.R
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.FileSystemUID
import com.celzero.bravedns.util.PlayStoreCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.util.*

class RefreshDatabase internal constructor(
    private var context: Context,
    private val appInfoRepository: AppInfoRepository,
    private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
    private val categoryInfoRepository: CategoryInfoRepository,
    private val doHEndpointRepository: DoHEndpointRepository,
    private val connTrackerRepository: ConnectionTrackerRepository,
    private val dnsLogRepository: DNSLogRepository,
    private val dnsCryptEndpointRepository: DNSCryptEndpointRepository,
    private val dnsCryptRelayEndpointRepository: DNSCryptRelayEndpointRepository,
    private val persistentState:PersistentState
) {

    /**
     * Need to rewrite the logic for adding the apps in the database and removing it during uninstall.
     */
    fun refreshAppInfoDatabase() {
        if(DEBUG) Log.d(LOG_TAG,"Refresh database is called")
        GlobalScope.launch(Dispatchers.IO) {
            val appListDB = appInfoRepository.getAppInfoAsync()
            if (appListDB.isNotEmpty()) {
                appListDB.forEach {
                    if(it.appName != "ANDROID" && it.packageInfo != Constants.NO_PACKAGE) {
                        try {
                            val packageName = context.packageManager.getPackageInfo(it.packageInfo, PackageManager.GET_META_DATA)
                            if (packageName.applicationInfo == null) {
                                appInfoRepository.delete(it)
                                updateCategoryInDB()
                            }
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "Application not available ${it.appName}" + e.message, e)
                            appInfoRepository.delete(it)
                            updateCategoryInDB()
                        }
                    }
                }
            }
            getAppInfo()
        }

    }

    private fun getAppInfo() {
        HomeScreenActivity.isLoadingComplete = false
        GlobalScope.launch(Dispatchers.IO) {
            val allPackages: List<PackageInfo> = context.packageManager?.getInstalledPackages(PackageManager.GET_META_DATA)!!
            val appDetailsFromDB = appInfoRepository.getAppInfoAsync()
            val nonAppsCount = appInfoRepository.getNonAppCount()
            val isRootAvailable = insertRootAndroid(appInfoRepository)
            if (appDetailsFromDB.isEmpty() || ((appDetailsFromDB.size-nonAppsCount) != (allPackages.size - 1))  || isRootAvailable) {
                allPackages.forEach {
                    if (it.applicationInfo.packageName != context.applicationContext.packageName) {
                        val applicationInfo: ApplicationInfo = it.applicationInfo
                        val appInfo = AppInfo()
                        appInfo.appName = context.packageManager.getApplicationLabel(applicationInfo).toString()
                        appInfo.packageInfo = applicationInfo.packageName
                        appInfo.uid = applicationInfo.uid
                        val dbAppInfo = appInfoRepository.getAppInfoForPackageName(appInfo.packageInfo)
                        if (dbAppInfo != null) {
                            HomeScreenActivity.GlobalVariable.appList[applicationInfo.packageName] = dbAppInfo
                            return@forEach
                        }else{
                            if(DEBUG) Log.d(LOG_TAG,"Refresh Database, AppInfo - new package found ${appInfo.appName} will be inserted")
                            appInfo.isDataEnabled = true
                            appInfo.isWifiEnabled = true
                            appInfo.isScreenOff = false
                            appInfo.isBackgroundEnabled = false
                            appInfo.whiteListUniv2 = false
                            appInfo.isExcluded = false
                            appInfo.whiteListUniv1 = ((it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) && !FileSystemUID.isUIDAppRange(
                                appInfo.uid
                            )
                            appInfo.mobileDataUsed = 0
                            appInfo.trackers = 0
                            appInfo.wifiDataUsed = 0
                        }

                        val category = fetchCategory(appInfo.packageInfo)
                        if (category.toLowerCase(Locale.ROOT) != PlayStoreCategory.OTHER.name.toLowerCase(Locale.ROOT)) {
                            appInfo.appCategory = category
                        } else if (((it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) && FileSystemUID.isUIDAppRange(
                                appInfo.uid
                            )
                        ) {
                            appInfo.appCategory = Constants.APP_CAT_SYSTEM_APPS
                            appInfo.isSystemApp = true
                        } else if (((it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)){
                            appInfo.appCategory = Constants.APP_CAT_SYSTEM_COMPONENTS
                            appInfo.isSystemApp = true
                        } else {
                            val temp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                ApplicationInfo.getCategoryTitle(context, applicationInfo.category)
                            } else {
                                Constants.INSTALLED_CAT_APPS
                            }
                            if (temp != null)
                                appInfo.appCategory = temp.toString()
                            else
                                appInfo.appCategory = Constants.APP_CAT_OTHER
                        }
                        if (appInfo.appCategory.contains("_"))
                            appInfo.appCategory = appInfo.appCategory.replace("_", " ").toLowerCase(Locale.ROOT)

                        //appInfo.uid = context.packageManager.getPackageUid(appInfo.packageInfo, PackageManager.GET_META_DATA)
                        appInfo.isInternetAllowed = persistentState.wifiAllowed(appInfo.packageInfo)


                        //TODO Handle this Global scope variable properly. Only half done.
                        HomeScreenActivity.GlobalVariable.appList[applicationInfo.packageName] = appInfo
                        appInfoRepository.insertAsync(appInfo, this)
                    }
                }
                updateCategoryInDB()
            } else {
                HomeScreenActivity.GlobalVariable.appList.clear()
                appDetailsFromDB.forEach {
                    HomeScreenActivity.GlobalVariable.appList[it.packageInfo] = it
                    if (!it.isInternetAllowed) {
                        HomeScreenActivity.GlobalVariable.blockedUID[it.uid] = false
                    }
                }
            }
            HomeScreenActivity.isLoadingComplete = true
            //mDb.close()
        }

    }

    private fun insertRootAndroid(appInfoRepository : AppInfoRepository) :Boolean {
        val isRootInfo = appInfoRepository.isRootUserAvailable()
        if(isRootInfo == null || isRootInfo.isBlank()){
            val appInfo = AppInfo()
            appInfo.appName = "ANDROID"
            appInfo.packageInfo = Constants.NO_PACKAGE
            appInfo.appCategory = Constants.APP_CAT_SYSTEM_COMPONENTS
            appInfo.isSystemApp = true
            appInfo.isDataEnabled = true
            appInfo.isWifiEnabled = true
            appInfo.isScreenOff = false
            appInfo.uid = 0
            appInfo.isInternetAllowed = true
            appInfo.isBackgroundEnabled = false
            appInfo.whiteListUniv1 = false
            appInfo.whiteListUniv2 = false
            appInfo.isExcluded = false
            appInfo.mobileDataUsed = 0
            appInfo.trackers = 0
            appInfo.wifiDataUsed = 0
            appInfoRepository.insertAsync(appInfo)
            if(DEBUG) Log.d(LOG_TAG,"Root insert success")
            updateBraveURLToRethink()
            insertDefaultDNSProxy()
            return true
        }else{
            if(DEBUG) Log.d(LOG_TAG,"Root already available")
            return false
        }
    }

    fun insertNonAppToAppInfo(uid : Int, appName : String){
        val appInfo = AppInfo()
        appInfo.appName = appName
        appInfo.packageInfo = "no_package"
        appInfo.appCategory = Constants.APP_NON_APP
        appInfo.isSystemApp = true
        appInfo.isDataEnabled = true
        appInfo.isWifiEnabled = true
        appInfo.isScreenOff = false
        appInfo.uid = uid
        appInfo.isInternetAllowed = persistentState.wifiAllowed(appInfo.packageInfo)
        appInfo.isBackgroundEnabled = false
        appInfo.whiteListUniv1 = false
        appInfo.whiteListUniv2 = false
        appInfo.mobileDataUsed = 0
        appInfo.trackers = 0
        appInfo.wifiDataUsed = 0
        appInfoRepository.insertAsync(appInfo)
        updateCategoryInDB()
    }

    private fun insertDefaultDNSProxy() {
        GlobalScope.launch(Dispatchers.IO) {
            val proxyURL  = context.resources.getStringArray(R.array.dns_proxy_names)
            val proxyIP  = context.resources.getStringArray(R.array.dns_proxy_ips)
            val dnsProxyEndPoint1 = DNSProxyEndpoint(1,proxyURL[0],"External","Nobody",proxyIP[0],53,false,false,0,0)
            val dnsProxyEndPoint2 = DNSProxyEndpoint(2,proxyURL[1],"External","Nobody",proxyIP[1],53,false,false,0,0)
            val dnsProxyEndPoint3 = DNSProxyEndpoint(3,proxyURL[2],"External","Nobody",proxyIP[2],53,false,false,0,0)
            dnsProxyEndpointRepository.insertWithReplace(dnsProxyEndPoint1)
            dnsProxyEndpointRepository.insertWithReplace(dnsProxyEndPoint2)
            dnsProxyEndpointRepository.insertWithReplace(dnsProxyEndPoint3)
        }
    }

    private fun updateBraveURLToRethink() {
        GlobalScope.launch(Dispatchers.IO) {
            doHEndpointRepository.removeConnectionStatus()
            val urlName = context.resources.getStringArray(R.array.doh_endpoint_names)
            val urlValues = context.resources.getStringArray(R.array.doh_endpoint_urls)

            val doHEndpoint1 = DoHEndpoint(1, urlName[0], urlValues[0], context.getString(R.string.dns_mode_0_explanation), false, false, System.currentTimeMillis(), 0)
            val doHEndpoint2 = DoHEndpoint(2, urlName[1], urlValues[1], context.getString(R.string.dns_mode_1_explanation), false, false, System.currentTimeMillis(), 0)
            val doHEndpoint3 = DoHEndpoint(3, urlName[2], urlValues[2], context.getString(R.string.dns_mode_2_explanation), false, false, System.currentTimeMillis(), 0)
            val doHEndpoint4 = DoHEndpoint(4, urlName[3], urlValues[3], context.getString(R.string.dns_mode_3_explanation), true, false, System.currentTimeMillis(), 0)
            val doHEndpoint5 = DoHEndpoint(5, urlName[5], urlValues[5], context.getString(R.string.dns_mode_5_explanation), false, false, System.currentTimeMillis(), 0)

            doHEndpointRepository.insertWithReplaceAsync(doHEndpoint1)
            doHEndpointRepository.insertWithReplaceAsync(doHEndpoint2)
            doHEndpointRepository.insertWithReplaceAsync(doHEndpoint3)
            doHEndpointRepository.insertWithReplaceAsync(doHEndpoint4)
            doHEndpointRepository.insertWithReplaceAsync(doHEndpoint5)
            //mDb.close()
        }
    }

    fun deleteOlderDataFromNetworkLogs() {
        GlobalScope.launch(Dispatchers.IO) {
            val DAY_IN_MS = 1000 * 60 * 60 * 24
            val date = System.currentTimeMillis() - (HomeScreenActivity.DAYS_TO_MAINTAIN_NETWORK_LOG * DAY_IN_MS)
            if (DEBUG) Log.d(LOG_TAG, "Time: ${System.currentTimeMillis()}, dateVal: $date")
            connTrackerRepository.deleteOlderData(date)
            dnsLogRepository.deleteOlderData(date)
            //mDb.close()
        }
    }

    private val APP_URL = "https://play.google.com/store/apps/details?id="
    private val CAT_SIZE = 9
    private val CATEGORY_STRING = "category/"
    private val CATEGORY_GAME_STRING = "GAME_" // All games start with this prefix
    private val DEFAULT_VALUE = "OTHERS"

    fun updateCategoryInDB() {
        if(DEBUG) Log.d(LOG_TAG,"RefreshDatabase - Call for updateCategoryDB")
        //val categoryDetailsFromDB = categoryInfoRepository.getAppCategoryList()
        categoryInfoRepository.deleteAllCategory()
        val categoryListFromAppList = appInfoRepository.getAppCategoryList()
        //if (categoryDetailsFromDB.isEmpty() || categoryDetailsFromDB.size != categoryListFromAppList.size) {
        categoryListFromAppList.forEach {
            val categoryInfo = CategoryInfo()
            categoryInfo.categoryName = it //.replace("_"," ").toLowerCase()
            categoryInfo.numberOFApps = appInfoRepository.getAppCountForCategory(it)
            categoryInfo.numOfAppsBlocked = appInfoRepository.getBlockedCountForCategory(it)
            categoryInfo.isInternetBlocked = (categoryInfo.numOfAppsBlocked == categoryInfo.numberOFApps)
            categoryInfo.numOfAppsExcluded = appInfoRepository.getExcludedAppCountForCategory(it)
            categoryInfo.numOfAppWhitelisted = appInfoRepository.getWhitelistCount(it)
            //categoryInfo.isInternetBlocked = false
            Log.i(LOG_TAG,"categoryListFromAppList - ${categoryInfo.categoryName}, ${categoryInfo.numberOFApps}, ${categoryInfo.numOfAppsBlocked}, ${categoryInfo.isInternetBlocked}")
            categoryInfoRepository.insertAsync(categoryInfo)
        }
        //}
       // mDb.close()
    }

    /**
     * Below code to fetch the google play service-application category
     * Not in use as of now.
     */
    private fun fetchCategory(packageName: String): String {
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

    private fun parseAndExtractCategory(url: String): String? {
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
    }

    private fun getCategoryTypeByHref(href: String): String? {
        val appCategoryType = href.substring(href.indexOf(CATEGORY_STRING) + CAT_SIZE, href.length)
        return if (appCategoryType.contains(CATEGORY_GAME_STRING)) {
            PlayStoreCategory.GENERAL_GAMES_CATEGORY_NAME
        } else appCategoryType
    }

    fun insertDefaultDNSList() {
        //https://basic.bravedns.com/1:wAIgAYAAAGA=
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val isAlreadyConnectionAvailable = doHEndpointRepository.getConnectedDoH()
                val urlName = context.resources.getStringArray(R.array.doh_endpoint_names)
                val urlValues = context.resources.getStringArray(R.array.doh_endpoint_urls)
                if(isAlreadyConnectionAvailable.dohName.isEmpty()){
                    doHEndpointRepository.removeConnectionStatus()

                    val doHEndpoint1 = DoHEndpoint(1, urlName[0], urlValues[0], context.getString(R.string.dns_mode_0_explanation), false, false, System.currentTimeMillis(), 0)
                    val doHEndpoint2 = DoHEndpoint(2, urlName[1], urlValues[1], context.getString(R.string.dns_mode_1_explanation), false, false, System.currentTimeMillis(), 0)
                    val doHEndpoint3 = DoHEndpoint(3, urlName[2], urlValues[2], context.getString(R.string.dns_mode_2_explanation), false, false, System.currentTimeMillis(), 0)
                    val doHEndpoint4 = DoHEndpoint(4, urlName[4], urlValues[3], context.getString(R.string.dns_mode_4_explanation), true, false, System.currentTimeMillis(), 0)

                    doHEndpointRepository.insertWithReplaceAsync(doHEndpoint1)
                    doHEndpointRepository.insertWithReplaceAsync(doHEndpoint2)
                    doHEndpointRepository.insertWithReplaceAsync(doHEndpoint3)
                    doHEndpointRepository.insertWithReplaceAsync(doHEndpoint4)
                }else{
                    Log.i(LOG_TAG, "Refresh Database, ALready insertion done. Correct values for Cloudflare alone.")
                    val doHEndpoint = DoHEndpoint(3, urlName[2], urlValues[2], context.getString(R.string.dns_mode_2_explanation), false, false, System.currentTimeMillis(), 0)
                    doHEndpointRepository.insertWithReplaceAsync(doHEndpoint)
                }
            }catch (e : Exception){
                Log.i(LOG_TAG, "Refresh Database, No connections available proceed insert")
            }

        }
    }


    fun insertDefaultDNSCryptList() {
        GlobalScope.launch(Dispatchers.IO) {
            val urlName = context.resources.getStringArray(R.array.dns_crypt_endpoint_names)
            val urlValues = context.resources.getStringArray(R.array.dns_crypt_endpoint_urls)
            val urlDesc = context.resources.getStringArray(R.array.dns_crypt_endpoint_desc)

            val dnsCryptEndpoint1 = DNSCryptEndpoint(1, urlName[0], urlValues[0], urlDesc[0], false, false, System.currentTimeMillis(), 0)
            val dnsCryptEndpoint2 = DNSCryptEndpoint(2, urlName[1], urlValues[1], urlDesc[1], false, false, System.currentTimeMillis(), 0)
            val dnsCryptEndpoint3 = DNSCryptEndpoint(3, urlName[2], urlValues[2], urlDesc[2], false, false, System.currentTimeMillis(), 0)
            val dnsCryptEndpoint4 = DNSCryptEndpoint(4, urlName[3], urlValues[3], urlDesc[3], false, false, System.currentTimeMillis(), 0)
            val dnsCryptEndpoint5 = DNSCryptEndpoint(5, urlName[4], urlValues[4], urlDesc[4], false, false, System.currentTimeMillis(), 0)
            dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint1)
            dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint2)
            dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint3)
            dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint4)
            dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint5)
            //mDb.close()
        }
    }

    fun insertDefaultDNSCryptRelayList() {
        GlobalScope.launch(Dispatchers.IO) {
            val urlName = context.resources.getStringArray(R.array.dns_crypt_relay_endpoint_names)
            val urlValues = context.resources.getStringArray(R.array.dns_crypt_relay_endpoint_urls)
            val urlDesc = context.resources.getStringArray(R.array.dns_crypt_relay_endpoint_desc)

            val dnsCryptRelayEndpoint1 = DNSCryptRelayEndpoint(1, urlName[0], urlValues[0], urlDesc[0], false, false, System.currentTimeMillis(), 0)
            val dnsCryptRelayEndpoint2 = DNSCryptRelayEndpoint(2, urlName[1], urlValues[1], urlDesc[1], false, false, System.currentTimeMillis(), 0)
            val dnsCryptRelayEndpoint3 = DNSCryptRelayEndpoint(3, urlName[2], urlValues[2], urlDesc[2], false, false, System.currentTimeMillis(), 0)
            val dnsCryptRelayEndpoint4 = DNSCryptRelayEndpoint(4, urlName[3], urlValues[3], urlDesc[3], false, false, System.currentTimeMillis(), 0)
            val dnsCryptRelayEndpoint5 = DNSCryptRelayEndpoint(5, urlName[4], urlValues[4], urlDesc[4], false, false, System.currentTimeMillis(), 0)

            dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint1)
            dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint2)
            dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint3)
            dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint4)
            dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint5)
            //mDb.close()
        }
    }

}
