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
import com.celzero.bravedns.service.VpnController
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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.HashSet
import kotlin.concurrent.read
import kotlin.concurrent.write


/*TODO : Initial check is for firewall app completely
           Later modification required for Data, WiFi, ScreenOn/Off, Background
           Lot of redundant code - streamline the code.
           */

object FirewallManager : KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val lock = ReentrantReadWriteLock()

    enum class AppStatus {
        ALLOWED, BLOCKED, WHITELISTED, EXCLUDED, NONE
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
        lock.read {
            return appInfos.get(uid).any { !it.isInternetAllowed }
        }
    }

    fun isUidWhitelisted(uid: Int): Boolean {
        lock.read {
            return appInfos.get(uid).any { it.whiteListUniv1 }
        }
    }

    fun getTotalApps(): Int {
        lock.read {
            return appInfos.values().size
        }
    }

    fun getPackageNames(): Set<AppInfoTuple> {
        lock.read {
            return appInfos.values().map { AppInfoTuple(it.uid, it.packageInfo) }.toHashSet()
        }
    }

    fun getAppInfos(): Collection<AppInfo> {
        lock.read {
            return appInfos.values()
        }
    }

    fun deletePackagesFromCache(packageNames: Set<AppInfoTuple>) {
        lock.write {
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
        lock.read {
            return appInfos.values().filter { it.isInternetAllowed }
        }
    }

    // TODO: Use the package-manager API instead
    fun isOrbotInstalled(): Boolean {
        lock.read {
            return appInfos.values().any { it.packageInfo == OrbotHelper.ORBOT_PACKAGE_NAME }
        }
    }

    fun hasUid(uid: Int): Boolean {
        lock.read {
            return appInfos.containsKey(uid)
        }
    }

    fun isUidExcluded(uid: Int): Boolean {
        lock.read {
            return appInfos.get(uid).any { it.isExcluded }
        }
    }

    fun canFirewall(uid: Int): AppStatus {
        val appInfo = getAppInfoByUid(uid) ?: return AppStatus.NONE

        if (appInfo.whiteListUniv1) {
            return AppStatus.WHITELISTED
        }
        if (appInfo.isExcluded) {
            return AppStatus.EXCLUDED
        }
        if (!appInfo.isInternetAllowed) {
            return AppStatus.BLOCKED
        }

        return AppStatus.ALLOWED
    }

    fun getApplistObserver(): MutableLiveData<Collection<AppInfo>> {
        return appInfosLiveData
    }

    fun getExcludedApps(): List<String> {
        lock.read {
            return appInfos.values().filter { it.isExcluded }.map { it.packageInfo }
        }
    }

    fun getPackageNameByAppName(appName: String): String? {
        lock.read {
            return appInfos.values().firstOrNull { it.appName == appName }?.packageInfo
        }
    }

    fun getAppNamesByUid(uid: Int): List<String> {
        lock.read {
            return appInfos.get(uid).map { it.appName }
        }
    }

    fun getPackageNamesByUid(uid: Int): List<String> {
        lock.read {
            return appInfos.get(uid).filter { !it.isSystemApp }.map { it.packageInfo }
        }
    }

    fun getAllAppNames(): List<String> {
        lock.read {
            return appInfos.values().map { it.appName }.sortedBy { it.lowercase() }
        }
    }

    fun getAppNameByUid(uid: Int): String? {
        lock.read {
            return appInfos.get(uid).firstOrNull()?.appName
        }
    }

    fun getAppInfoByPackage(packageName: String?): AppInfo? {
        if (packageName.isNullOrBlank()) return null

        lock.read {
            return appInfos.values().firstOrNull { it.packageInfo == packageName }
        }
    }

    fun getAppInfoByUid(uid: Int): AppInfo? {
        lock.read {
            return appInfos.get(uid).firstOrNull()
        }
    }

    private fun getAppInfosByUid(uid: Int): Collection<AppInfo> {
        lock.read {
            return appInfos.get(uid)
        }
    }

    fun getPackageNameByUid(uid: Int): String? {
        lock.read {
            return appInfos.get(uid).firstOrNull()?.packageInfo
        }
    }

    fun getCategoryListByAppName(appName: String): List<String> {
        lock.read {
            return appInfos.values().filter {
                it.appName.contains(appName)
            }.map { it.appCategory }.distinct().sorted()
        }
    }

    private fun getBlockedCountForCategory(categoryName: String): Int {
        lock.read {
            return appInfos.values().filter {
                it.appCategory == categoryName && !it.isInternetAllowed
            }.size
        }
    }

    private fun getWhitelistCountForCategory(categoryName: String): Int {
        lock.read {
            return appInfos.values().filter {
                it.appCategory == categoryName && it.whiteListUniv1
            }.size
        }
    }

    private fun getExcludedCountForCategory(categoryName: String): Int {
        lock.read {
            return appInfos.values().filter {
                it.appCategory == categoryName && it.isExcluded
            }.size
        }
    }

    fun getWhitelistAppData(): List<AppInfo> {
        lock.read {
            return appInfos.values().filter { it.whiteListUniv1 }
        }
    }

    suspend fun loadAppFirewallRules() {
        if (isFirewallRulesLoaded) return

        withContext(Dispatchers.IO) {
            reloadAppList()
        }
    }

    private fun invalidateBlockedApps(uid: Int, isAllowed: Boolean) {
        lock.read {
            appInfos.get(uid).forEach {
                it.isInternetAllowed = isAllowed
            }
        }
        postToObserver()
    }

    private fun invalidateExcludedApps(uid: Int, isExcluded: Boolean) {
        lock.write {
            appInfos.get(uid).forEach {
                it.isExcluded = isExcluded
                if (isExcluded) {
                    it.isInternetAllowed = true
                }
            }
        }
        postToObserver()
    }

    private fun invalidateWhitelistedApps(uid: Int, isWhitelisted: Boolean) {
        lock.write {
            appInfos.get(uid).forEach {
                it.whiteListUniv1 = isWhitelisted
                if (isWhitelisted) {
                    it.isInternetAllowed = true
                }
            }
        }
        postToObserver()
    }

    fun persistAppInfo(appInfo: AppInfo) {
        appInfoRepository.insert(appInfo)

        lock.write {
            appInfos.put(appInfo.uid, appInfo)
        }
        postToObserver()
    }

    private suspend fun reloadAppList() {
        val apps = appInfoRepository.getAppInfo()
        if (apps.isEmpty()) {
            Log.w(LOG_TAG_FIREWALL, "no apps found in db, no app-based rules to load")
            isFirewallRulesLoaded = true
            return
        }

        lock.write {
            appInfos.clear()
            apps.forEach {
                appInfos.put(it.uid, it)
            }
            isFirewallRulesLoaded = true
        }
        postToObserver()
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

    fun updateFirewalledAppsByCategories(categoryInfo: CategoryInfo, isInternetBlocked: Boolean) {
        io {
            // Adding the category name to a set
            // as of now, the invalidateCachedAppStatuses accepts category names as set
            // This is because, the ui behaviour for whitelist/exclude/block are different.
            val set: MutableSet<String> = HashSet()
            set.add(categoryInfo.categoryName)
            invalidateCachedAppStatuses(set, AppStatus.BLOCKED, isInternetBlocked)

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

    fun updateExcludedAppsByCategories(filterCategories: Set<String>, checked: Boolean) {
        io {
            if (filterCategories.isNullOrEmpty()) {
                appInfoRepository.updateExcludedForAllApps(checked)
                categoryInfoRepository.updateExcludedCountForAllApp(checked)
                if (checked) {
                    categoryInfoRepository.updateWhitelistCountForAllCategories(!checked)
                }
            } else {
                filterCategories.forEach { category ->
                    val update = appInfoRepository.updateExcludedForCategories(category, checked)
                    categoryInfoRepository.updateExcludedCountForCategory(category, checked)
                    if (checked) {
                        categoryInfoRepository.updateWhitelistForCategory(category, !checked)
                    }
                    Log.i(LOG_TAG_FIREWALL, "Excluded apps count: $update")
                }
            }

            invalidateCachedAppStatuses(filterCategories, AppStatus.EXCLUDED, checked)
        }
    }

    fun updateWhitelistedAppsByCategories(filterCategories: Set<String>, checked: Boolean) {
        io {
            if (filterCategories.isNullOrEmpty()) {
                appInfoRepository.updateWhitelistForAllApp(checked)
                appInfoRepository.getAppCategoryList().forEach {
                    val countBlocked = appInfoRepository.getBlockedCountForCategory(it)
                    categoryInfoRepository.updateBlockedCount(it, countBlocked)
                }
                categoryInfoRepository.updateWhitelistCountForAllCategories(checked)
            } else {
                filterCategories.forEach { category ->
                    val update = appInfoRepository.updateWhitelistForCategories(category, checked)
                    categoryInfoRepository.updateWhitelistForCategory(category, checked)
                    val countBlocked = appInfoRepository.getBlockedCountForCategory(category)
                    categoryInfoRepository.updateBlockedCount(category, countBlocked)
                    Log.i(LOG_TAG_FIREWALL, "Whitelisted apps count: $update")
                }
            }

            invalidateCachedAppStatuses(filterCategories, AppStatus.WHITELISTED, checked)
        }
    }

    private fun invalidateCachedAppStatuses(filterCategories: Set<String>, state: AppStatus,
                                            checked: Boolean) {
        lock.write {
            appInfos.values().forEach {
                if (!filterCategories.contains(it.appCategory)) return@forEach

                // modified the appInfos based on the state.
                when (state) {
                    AppStatus.BLOCKED -> it.isInternetAllowed = !checked
                    AppStatus.WHITELISTED -> it.whiteListUniv1 = checked
                    AppStatus.EXCLUDED -> it.isExcluded = checked
                    else -> { /* no-op */
                    }
                }

                // below changes need to be carried out only when app is added to whitelisted/excluded
                if (!checked || state == AppStatus.BLOCKED) return@forEach

                when (state) {
                    // when whitelisted, remove from the excluded state
                    AppStatus.WHITELISTED -> it.isExcluded = false
                    // when excluded, remove from whitelist
                    AppStatus.EXCLUDED -> it.whiteListUniv1 = false
                    else -> { /* no-op */
                    }
                }
                // allow internet when the app is added to whitelist/excluded
                it.isInternetAllowed = true

            }
        }
        postToObserver()
    }

    fun updateExcludedApps(appInfo: AppInfo, status: Boolean) {
        io {
            invalidateExcludedApps(appInfo.uid, status)
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
        io {
            invalidateWhitelistedApps(appInfo.uid, isWhitelisted)
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
        io {
            invalidateBlockedApps(uid, isBlocked)
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

    private fun postToObserver() {
        lock.read {
            appInfosLiveData.postValue(appInfos.values())
        }
    }

    private fun io(f: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }
}
