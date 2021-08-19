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
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.appInfos
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.appInfosLiveData
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.foregroundUids
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.OrbotHelper
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.HashSet
import kotlin.concurrent.withLock


/*TODO : Initial check is for firewall app completely
           Later modification required for Data, WiFi, ScreenOn/Off, Background
           Lot of redundant code - streamline the code.
           */

object FirewallManager : KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val lock = ReentrantLock()

    enum class FIREWALL_STATUS {
        WHITELISTED, EXCLUDED, BLOCKED, ALLOWED, NONE
    }

    object GlobalVariable {

        var appInfos: Multimap<Int, AppInfo> = HashMultimap.create()

        // TODO: protect access to the foregroundUids (read/write)
        @Volatile var foregroundUids: HashSet<Int> = HashSet()

        var appInfosLiveData: MutableLiveData<Collection<AppInfo>> = MutableLiveData()
    }

    data class AppInfoTuple(val uid: Int, val packageName: String)

    @Volatile private var isFirewallRulesLoaded: Boolean = false

    fun isFirewallRulesLoaded(): Boolean {
        return isFirewallRulesLoaded
    }

    fun isUidFirewalled(uid: Int): Boolean {
        lock.withLock {
            return appInfos.get(uid).any { !it.isInternetAllowed }
        }
    }

    fun isUidWhitelisted(uid: Int): Boolean {
        lock.withLock {
            return appInfos.get(uid).any { it.whiteListUniv1 }
        }
    }

    fun getTotalApps(): Int {
        lock.withLock {
            return appInfos.values().size
        }
    }

    fun getPackageNames(): Set<AppInfoTuple> {
        lock.withLock {
            return appInfos.values().map { AppInfoTuple(it.uid, it.packageInfo) }.toHashSet()
        }
    }

    fun getAppInfos(): Collection<AppInfo> {
        lock.withLock {
            return appInfos.values()
        }
    }

    fun deletePackagesFromCache(packageNames: Set<AppInfoTuple>) {
        lock.withLock {
            packageNames.forEach { tuple ->
                appInfos.get(
                    tuple.uid).filter { tuple.packageName == it.packageInfo }.forEach { ai ->
                    appInfos.remove(tuple.uid, ai)
                }
            }
            // Delete the uninstalled apps from database
            appInfoRepository.deleteByPackageName(packageNames.map { it.packageName })
        }
    }

    fun getNonFirewalledAppsPackageNames(): List<AppInfo> {
        lock.withLock {
            return appInfos.values().filter { it.isInternetAllowed }
        }
    }

    // TODO: Use the package-manager API instead
    fun isOrbotInstalled(): Boolean {
        lock.withLock {
            return appInfos.values().any { it.packageInfo == OrbotHelper.ORBOT_PACKAGE_NAME }
        }
    }

    fun hasUid(uid: Int): Boolean {
        lock.withLock {
            return appInfos.containsKey(uid)
        }
    }

    fun isUidExcluded(uid: Int): Boolean {
        lock.withLock {
            return appInfos.get(uid).any { it.isExcluded }
        }
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

    fun getApplistObserver(): MutableLiveData<Collection<AppInfo>> {
        return appInfosLiveData
    }

    fun getExcludedApps(): List<String> {
        lock.withLock {
            return appInfos.values().filter { it.isExcluded }.map { it.packageInfo }
        }
    }

    fun getPackageNameByAppName(appName: String): String? {
        lock.withLock {
            return appInfos.values().firstOrNull { it.appName == appName }?.packageInfo
        }
    }

    fun getAppNamesByUid(uid: Int): List<String> {
        lock.withLock {
            return appInfos.get(uid).map { it.appName }
        }
    }

    fun getPackageNamesByUid(uid: Int): List<String> {
        lock.withLock {
            return appInfos.get(uid).filter { !it.isSystemApp }.map { it.packageInfo }
        }
    }

    fun getAllAppNames(): List<String> {
        lock.withLock {
            return appInfos.values().map { it.appName }.sorted()
        }
    }

    fun getAppNameByUid(uid: Int): String? {
        lock.withLock {
            return appInfos.get(uid).firstOrNull()?.appName
        }
    }

    fun getAppInfoByPackage(packageName: String?): AppInfo? {
        if (packageName.isNullOrBlank()) return null

        lock.withLock {
            return appInfos.values().firstOrNull { it.packageInfo == packageName }
        }
    }

    fun getAppInfoByUid(uid: Int): AppInfo? {
        lock.withLock {
            return appInfos.get(uid).firstOrNull()
        }
    }

    private fun getAppInfosByUid(uid: Int): Collection<AppInfo> {
        lock.withLock {
            return appInfos.get(uid)
        }
    }

    fun getPackageNameByUid(uid: Int): String? {
        lock.withLock {
            return appInfos.get(uid).firstOrNull()?.packageInfo
        }
    }

    fun getCategoryListByAppName(appName: String): List<String> {
        lock.withLock {
            return appInfos.values().filter {
                it.appName.contains(appName)
            }.map { it.appCategory }.distinct().sorted()
        }
    }

    private fun getBlockedCountForCategory(categoryName: String): Int {
        lock.withLock {
            return appInfos.values().filter {
                it.appCategory == categoryName && !it.isInternetAllowed
            }.size
        }
    }

    private fun getWhitelistCountForCategory(categoryName: String): Int {
        lock.withLock {
            return appInfos.values().filter {
                it.appCategory == categoryName && it.whiteListUniv1
            }.size
        }
    }

    private fun getExcludedCountForCategory(categoryName: String): Int {
        lock.withLock {
            return appInfos.values().filter {
                it.appCategory == categoryName && it.isExcluded
            }.size
        }
    }

    fun getWhitelistAppData(): List<AppInfo> {
        lock.withLock {
            return appInfos.values().filter { it.whiteListUniv1 }
        }
    }

    suspend fun loadAppFirewallRules() {
        if (isFirewallRulesLoaded) return

        withContext(Dispatchers.IO) {
            reloadAppList()
        }
    }

    private fun updateAppsInternetPermission(uid: Int, isAllowed: Boolean) {
        lock.withLock {
            appInfos.get(uid).forEach {
                it.isInternetAllowed = isAllowed
            }
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    private fun updateAppsExcludedPermission(uid: Int, isExcluded: Boolean) {

        lock.withLock {
            appInfos.get(uid).forEach {
                it.isExcluded = isExcluded
                if (isExcluded) {
                    it.isInternetAllowed = true
                }
            }
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    private fun updateAppsWhitelist(uid: Int, isWhitelisted: Boolean) {
        lock.withLock {
            appInfos.get(uid).forEach {
                it.whiteListUniv1 = isWhitelisted
                if (isWhitelisted) {
                    it.isInternetAllowed = true
                }
            }
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    private fun updateCategoryAppsInternetPermission(categoryName: String, isAllowed: Boolean) {
        lock.withLock {
            appInfos.values().filter { it.appCategory == categoryName }.forEach {
                if (!it.whiteListUniv1) {
                    it.isInternetAllowed = !isAllowed
                }
            }
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    fun persistAppInfo(appInfo: AppInfo) {
        appInfoRepository.insert(appInfo)

        lock.withLock {
            appInfos.put(appInfo.uid, appInfo)
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    private fun reloadAppList() {
        val apps = appInfoRepository.getAppInfo()
        if (apps.isEmpty()) {
            isFirewallRulesLoaded = false
            return
        }

        lock.withLock {
            appInfos.clear()
            apps.forEach {
                appInfos.put(it.uid, it)
            }
            isFirewallRulesLoaded = true
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    fun untrackForegroundApps() {
        Log.i(LOG_TAG_FIREWALL,
              "launcher in the foreground, clear foreground uids: $foregroundUids")
        foregroundUids.clear()
    }

    fun trackForegroundApp(uid: Int) {
        val appInfo = appInfos[uid]

        if (appInfo == null) {
            Log.i(LOG_TAG_FIREWALL, "No such app $uid to update 'dis/allow' firewall rule")
            return
        }

        val isAppUid = AndroidUidConfig.isUidAppRange(uid)
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "app in foreground with uid? $isAppUid")

        // Only track packages within app uid range.
        if (!isAppUid) return

        foregroundUids.add(uid)
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


    fun updateExcludedAppsByCategories(filterCategories: Set<String>, checked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (filterCategories.isNullOrEmpty()) {
                appInfoRepository.updateExcludedForAllApp(checked)
                categoryInfoRepository.updateExcludedCountForAllApp(checked)
                if (checked) {
                    categoryInfoRepository.updateWhitelistCountForAll(!checked)
                }
            } else {
                filterCategories.forEach { category ->
                    appInfoRepository.updateExcludedForCategories(category, checked)
                    categoryInfoRepository.updateExcludedCountForCategory(category, checked)
                    if (checked) {
                        categoryInfoRepository.updateWhitelistForCategory(category, !checked)
                    }
                }
            }

            updateAppStateInCache(filterCategories, AppFirewallStates.EXCLUDED, checked)
        }
    }

    fun updateWhitelistedAppsByCategories(filterCategories: Set<String>, checked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (filterCategories.isNullOrEmpty()) {
                appInfoRepository.updateWhitelistForAllApp(checked)
                appInfoRepository.getAppCategoryList().forEach {
                    val countBlocked = appInfoRepository.getBlockedCountForCategory(it)
                    categoryInfoRepository.updateBlockedCount(it, countBlocked)
                }
                categoryInfoRepository.updateWhitelistCountForAll(checked)
            } else {
                filterCategories.forEach { category ->
                    val update = appInfoRepository.updateWhitelistForCategories(category, checked)
                    categoryInfoRepository.updateWhitelistForCategory(category, checked)
                    val countBlocked = appInfoRepository.getBlockedCountForCategory(category)
                    categoryInfoRepository.updateBlockedCount(category, countBlocked)
                    if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Update whitelist count: $update")
                }
            }

            updateAppStateInCache(filterCategories, AppFirewallStates.WHITELISTED, checked)
        }
    }

    enum class AppFirewallStates {
        ALLOWED, BLOCKED, WHITELISTED, EXCLUDED
    }

    // handles list of excluded/whitelisted apps
    private fun updateAppStateInCache(filterCategories: Set<String>, state: AppFirewallStates, checked: Boolean) {
        lock.withLock {
            appInfos.values().forEach {
                if (filterCategories.isNotEmpty() && !filterCategories.contains(
                        it.appCategory)) return@forEach

                // modified the appInfos based on the state.
                if (AppFirewallStates.WHITELISTED == state) it.whiteListUniv1 = checked
                else it.isExcluded = checked

                // below changes need to be carried out only when app is added to whitelisted/excluded
                if (!checked) return@forEach

                // when whitelisted, remove from the excluded state
                if (AppFirewallStates.WHITELISTED == state) it.isExcluded = false
                // when excluded, remove from whitelist
                else it.whiteListUniv1 = false
                // allow internet when the app is added either to whitelist/excluded
                it.isInternetAllowed = true
            }
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    fun updateExcludedApps(appInfo: AppInfo, status: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            updateAppsExcludedPermission(appInfo.uid, status)
            appInfoRepository.updateExcludedList(appInfo.uid, status)
            val count = getBlockedCountForCategory(appInfo.appCategory)
            val excludedCount = getExcludedCountForCategory(appInfo.appCategory)
            val whitelistCount = getWhitelistCountForCategory(appInfo.appCategory)
            categoryInfoRepository.updateBlockedCount(appInfo.appCategory, count)
            categoryInfoRepository.updateExcludedCount(appInfo.appCategory, excludedCount)
            categoryInfoRepository.updateWhitelistCount(appInfo.appCategory, whitelistCount)
        }
    }

    fun updateWhitelistedApps(appInfo: AppInfo, isWhitelisted: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            updateAppsWhitelist(appInfo.uid, isWhitelisted)
            val countBlocked = getBlockedCountForCategory(appInfo.appCategory)
            val countWhitelisted = getWhitelistCountForCategory(appInfo.appCategory)
            categoryInfoRepository.updateBlockedCount(appInfo.appCategory, countBlocked)
            categoryInfoRepository.updateWhitelistCount(appInfo.appCategory, countWhitelisted)
            appInfoRepository.updateWhitelist(appInfo.uid, isWhitelisted)
            if (isWhitelisted) {
                appInfoRepository.updateInternetForUID(appInfo.uid, isWhitelisted)
            }
        }
    }

    fun updateFirewalledApps(uid: Int, isBlocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            updateAppsInternetPermission(uid, isBlocked)
            val appInfo = getAppInfosByUid(uid).distinctBy { it.appCategory }
            appInfo.forEach {
                val count = getBlockedCountForCategory(it.appCategory)
                categoryInfoRepository.updateBlockedCount(it.appCategory, count)
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "Category (${it.appCategory}), isFirewalled? ${!isBlocked}")
            }
            appInfoRepository.updateInternetForUID(uid, isBlocked)
        }
    }

    fun updateFirewalledAppsByCategory(categoryInfo: CategoryInfo, isInternetBlocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            updateCategoryAppsInternetPermission(categoryInfo.categoryName, isInternetBlocked)
            val count = getBlockedCountForCategory(categoryInfo.categoryName)
            if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Apps updated : $count, $isInternetBlocked")
            // Update the category's internet blocked based on the app's count which is returned
            // from the app info database.
            categoryInfoRepository.updateCategoryDetails(categoryInfo.categoryName, count,
                                                         isInternetBlocked)
            // flip appInfoRepository's isInternetBlocked.
            // AppInfo's(Database) column name is isInternet but for CategoryInfo(database) the
            // column name is appInfoRepository.
            appInfoRepository.setInternetAllowedForCategory(categoryInfo.categoryName,
                                                            !isInternetBlocked)
        }
    }
}
