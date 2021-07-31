/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.automaton

import android.app.KeyguardManager
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.appList
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.appListLiveData
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.foregroundUids
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.OrbotHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap

/*TODO : Initial check is for firewall app completely
           Later modification required for Data, WiFi, ScreenOn/Off, Background
           Lot of redundant code - streamline the code.
           */

object FirewallManager : KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()

    enum class FIREWALL_STATUS {
        WHITELISTED, EXCLUDED, BLOCKED, ALLOWED, NONE
    }

    object GlobalVariable {
        var appList: MutableMap<String, AppInfo> = ConcurrentHashMap()

        // TODO: protect access to the foregroundUids (read/write)
        @Volatile var foregroundUids: HashSet<Int> = HashSet()

        var appListLiveData: MutableLiveData<List<AppInfo>> = MutableLiveData()
    }

    @Volatile private var isFirewallRulesLoaded: Boolean = false

    fun isFirewallRulesLoaded(): Boolean {
        return isFirewallRulesLoaded
    }

    fun isUidFirewalled(uid: Int): Boolean {
        return !appList.values.filter { it.uid == uid }[0].isInternetAllowed
    }

    fun isUidWhitelisted(uid: Int): Boolean {
        if (!appList.isNullOrEmpty()) {
            val appDetail = appList.values.filter { it.uid == uid }
            if (appDetail.isNotEmpty()) {
                return appDetail[0].whiteListUniv1
            }
        }
        return false
    }

    fun getTotalApps(): Int {
        return appList.size
    }

    fun isOrbotInstalled(): Boolean {
        return appList.contains(OrbotHelper.ORBOT_PACKAGE_NAME)
    }

    fun isUidRegistered(uid: Int): Boolean {
        return appList.values.any { it.uid == uid }
    }

    fun isUidExcluded(uid: Int): Boolean {
        if (!appList.isNullOrEmpty()) {
            val appDetail = appList.values.filter { it.uid == uid }
            if (appDetail.isNotEmpty()) {
                return appDetail[0].isExcluded
            }
        }
        return false
    }

    fun canFirewall(uid: Int): FIREWALL_STATUS {
        val appInfo = getAppInfoByUid(uid) ?: return FIREWALL_STATUS.NONE

        if (appInfo.whiteListUniv1) {
            return FIREWALL_STATUS.WHITELISTED
        }
        if (appInfo.isExcluded) {
            return FIREWALL_STATUS.EXCLUDED
        }
        if (!appInfo.isInternetAllowed) {
            return FIREWALL_STATUS.BLOCKED
        }

        return FIREWALL_STATUS.ALLOWED
    }

    fun getApplistObserver(): MutableLiveData<List<AppInfo>> {
        return appListLiveData
    }

    fun getExcludedApps(): List<String> {
        val list = appList.values.filter { it.isExcluded }
        val excludedList: MutableList<String> = ArrayList()
        list.forEach {
            excludedList.add(it.packageInfo)
        }
        return excludedList
    }

    fun getPackageNameByAppName(appName: String): String {
        val appNames = appList.values.filter { it.appName == appName }
        if (!appNames.isNullOrEmpty()) return appNames[0].packageInfo

        return appInfoRepository.getPackageNameForAppName(appName)
    }

    fun getAppNamesByUid(uid: Int): List<String> {
        return appList.values.filter { it.uid == uid }.map(AppInfo::appName)
    }

    fun getPackageNamesByUid(uid: Int): List<String> {
        return appList.values.filter { it.uid == uid && !it.isSystemApp }.map(AppInfo::packageInfo)
    }

    fun getAllAppNames(): List<String> {
        return appList.values.map(AppInfo::appName)
    }

    fun getAppNameByUid(uid: Int): String? {
        return appList.values.firstOrNull { it.uid == uid }?.appName
    }

    fun getAppInfoByPackage(packageName: String?): AppInfo? {
        return appList[packageName]
    }

    fun getAppInfoByUid(uid: Int): AppInfo? {
        return appList.values.firstOrNull { it.uid == uid }
    }

    private fun getAppInfosByUid(uid: Int): List<AppInfo> {
        return appList.values.filter { it.uid == uid }
    }

    fun getPackageNameByUid(uid: Int): String? {
        return appList.values.firstOrNull { it.uid == uid }?.packageInfo
    }

    fun getCategoryListByAppName(appName: String): List<String> {
        return appList.values.filter {
            it.appName.contains(appName)
        }.map { it.appCategory }.distinct().sorted()
    }

    suspend fun loadAppFirewallRules() {
        withContext(Dispatchers.IO) {
            reloadAppList()
            isFirewallRulesLoaded = true
            appListLiveData.postValue(appList.values.toList())
        }
    }

    private fun updateAppsInternetPermission(uid: Int, isAllowed: Boolean) {
        appList.values.filter { it.uid == uid }.forEach {
            it.isInternetAllowed = isAllowed
        }
        appListLiveData.postValue(appList.values.toList())
    }

    private fun updateAppsExcludedPermission(uid: Int, isExcluded: Boolean) {
        appList.values.filter { it.uid == uid }.forEach {
            it.isExcluded = isExcluded
            if (isExcluded) {
                it.isInternetAllowed = true
            }
        }

        appListLiveData.postValue(appList.values.toList())
    }

    private fun updateAppsWhitelist(uid: Int, isWhitelisted: Boolean) {
        appList.values.filter { it.uid == uid }.forEach {
            it.whiteListUniv1 = isWhitelisted
            if (isWhitelisted) {
                it.isInternetAllowed = true
            }
        }

        appListLiveData.postValue(appList.values.toList())
    }

    private fun updateCategoryAppsInternetPermission(categoryName: String, isAllowed: Boolean) {
        appList.values.filter { it.appCategory == categoryName }.forEach {
            it.isInternetAllowed = !isAllowed
        }

        appListLiveData.postValue(appList.values.toList())
    }

    fun updateGlobalAppInfoEntry(packageName: String, appInfo: AppInfo?) {
        if (appInfo != null) {
            appList[packageName] = appInfo
            appListLiveData.postValue(appList.values.toList())
        }
    }

    fun reloadAppList() {
        val appInfos = appInfoRepository.getAppInfo()
        appList.clear()
        appInfos.forEach {
            appList[it.packageInfo] = it
        }

        appListLiveData.postValue(appList.values.toList())
    }

    fun untrackForegroundApps() {
        Log.i(LOG_TAG_FIREWALL,
              "launcher in the foreground, clear foreground uids: $foregroundUids")
        foregroundUids.clear()
    }

    fun trackForegroundApp(packageName: String?) {
        val appInfo = appList[packageName]

        if (appInfo == null) {
            Log.i(LOG_TAG_FIREWALL, "No such app $packageName to update 'dis/allow' firewall rule")
            return
        }

        val isAppUid = AndroidUidConfig.isUidAppRange(appInfo.uid)
        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "app in foreground: ${appInfo.packageInfo}, isAppUid? $isAppUid")

        // Only track packages within app uid range.
        if (!isAppUid) return

        foregroundUids.add(appInfo.uid)
    }

    fun isAppForeground(uid: Int, keyguardManager: KeyguardManager?): Boolean {
        // isKeyguardLocked check for allow apps in foreground.
        // When the user engages the app and locks the screen, the app is
        // considered to be in background and the connections for those apps
        // should be blocked.
        val locked = keyguardManager?.isKeyguardLocked == false
        val isForeground = foregroundUids.contains(uid)
        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "is app $uid foreground? ${locked && isForeground}, isLocked? $locked, is available in foreground list? $isForeground")
        return locked && isForeground
    }


    fun updateExcludedAppsByCategories(filterCategories: List<String>, checked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (filterCategories.isNullOrEmpty()) {
                appInfoRepository.updateExcludedForAllApp(checked)
                categoryInfoRepository.updateExcludedCountForAllApp(checked)
                if (checked) {
                    categoryInfoRepository.updateWhitelistCountForAll(!checked)
                }
            } else {
                filterCategories.forEach {
                    appInfoRepository.updateExcludedForCategories(it, checked)
                    categoryInfoRepository.updateExcludedCountForCategory(it, checked)
                    if (checked) {
                        categoryInfoRepository.updateWhitelistForCategory(it, !checked)
                    }
                }
            }
            // All the apps / some app categories selected and excluded so reload the
            // app list from database.
            reloadAppList()
        }
    }

    fun updateWhitelistedAppsByCategories(filterCategories: List<String>, checked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (filterCategories.isNullOrEmpty()) {
                appInfoRepository.updateWhitelistForAllApp(checked)
                appInfoRepository.getAppCategoryList().forEach {
                    val countBlocked = appInfoRepository.getBlockedCountForCategory(it)
                    categoryInfoRepository.updateBlockedCount(it, countBlocked)
                }
                categoryInfoRepository.updateWhitelistCountForAll(checked)
            } else {
                filterCategories.forEach {
                    val update = appInfoRepository.updateWhitelistForCategories(it, checked)
                    categoryInfoRepository.updateWhitelistForCategory(it, checked)
                    val countBlocked = appInfoRepository.getBlockedCountForCategory(it)
                    categoryInfoRepository.updateBlockedCount(it, countBlocked)
                    if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Update whitelist count: $update")
                }
            }
            // All the apps / some app categories selected and whitelisted so reload the
            // app list from database.
            reloadAppList()
        }
    }

    fun updateExcludedApps(appInfo: AppInfo, status: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            updateAppsExcludedPermission(appInfo.uid, status)
            appInfoRepository.updateExcludedList(appInfo.uid, status)
            val count = appInfoRepository.getBlockedCountForCategory(appInfo.appCategory)
            val excludedCount = appInfoRepository.getExcludedAppCountForCategory(
                appInfo.appCategory)
            val whitelistCount = appInfoRepository.getBlockedCountForCategory(appInfo.appCategory)
            categoryInfoRepository.updateBlockedCount(appInfo.appCategory, count)
            categoryInfoRepository.updateExcludedCount(appInfo.appCategory, excludedCount)
            categoryInfoRepository.updateWhitelistCount(appInfo.appCategory, whitelistCount)
        }
    }

    fun updateWhitelistedApps(appInfo: AppInfo, isWhitelisted: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            updateAppsWhitelist(appInfo.uid, isWhitelisted)
            if (isWhitelisted) {
                appInfoRepository.updateInternetForUID(appInfo.uid, isWhitelisted)
            }
            appInfoRepository.updateWhitelist(appInfo.uid, isWhitelisted)
            val countBlocked = appInfoRepository.getBlockedCountForCategory(appInfo.appCategory)
            val countWhitelisted = appInfoRepository.getWhitelistCountForCategory(
                appInfo.appCategory)
            categoryInfoRepository.updateBlockedCount(appInfo.appCategory, countBlocked)
            categoryInfoRepository.updateWhitelistCount(appInfo.appCategory, countWhitelisted)
        }
    }

    fun updateFirewalledApps(uid: Int, isBlocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            updateAppsInternetPermission(uid, isBlocked)
            val appInfo = getAppInfosByUid(uid).distinctBy { it.appCategory }
            appInfo.forEach {
                categoryInfoRepository.updateNumberOfBlocked(it.appCategory, !isBlocked)
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "Category block executed with blocked as $isBlocked")
            }
            appInfoRepository.updateInternetForUID(uid, isBlocked)
        }
    }

    fun updateFirewalledAppsByCategory(categoryInfo: CategoryInfo, isInternetBlocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            // flip appInfoRepository's isInternetBlocked.
            // AppInfo's(Database) column name is isInternet but for CategoryInfo(database) the
            // column name is appInfoRepository.
            val count = appInfoRepository.setInternetAllowedForCategory(categoryInfo.categoryName,
                                                                        !isInternetBlocked)
            if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Apps updated : $count, $isInternetBlocked")
            // Update the category's internet blocked based on the app's count which is returned
            // from the app info database.
            categoryInfoRepository.updateCategoryDetails(categoryInfo.categoryName, count,
                                                         isInternetBlocked)
            updateCategoryAppsInternetPermission(categoryInfo.categoryName, isInternetBlocked)
        }
    }


}
