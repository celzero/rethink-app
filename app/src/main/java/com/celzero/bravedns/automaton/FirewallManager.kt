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
import com.google.common.collect.Multimaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.HashSet


/*TODO : Initial check is for firewall app completely
           Later modification required for Data, WiFi, ScreenOn/Off, Background
           Lot of redundant code - streamline the code.
           */

object FirewallManager : KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val rwl = ReentrantReadWriteLock()
    private val rl: ReentrantReadWriteLock.ReadLock = rwl.readLock()
    private val wl: ReentrantReadWriteLock.WriteLock = rwl.writeLock()

    private fun upgradeToWriteLock() {
        rl.unlock()
        wl.lock()
    }

    private fun downgradeToReadLock() {
        wl.unlock()
        rl.lock()
    }

    enum class FIREWALL_STATUS {
        WHITELISTED, EXCLUDED, BLOCKED, ALLOWED, NONE
    }

    object GlobalVariable {

        var appInfos: Multimap<Int, AppInfo> = Multimaps.synchronizedMultimap(HashMultimap.create())

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
        try {
            rl.lock()
            return appInfos.get(uid).any { !it.isInternetAllowed }
        } finally {
            rl.unlock()
        }
    }

    fun isUidWhitelisted(uid: Int): Boolean {
        try {
            rl.lock()
            return appInfos.get(uid).any { it.whiteListUniv1 }
        } finally {
            rl.unlock()
        }
    }

    fun getTotalApps(): Int {
        try {
            rl.lock()
            return appInfos.values().size
        } finally {
            rl.unlock()
        }
    }

    fun getPackageNames(): Set<AppInfoTuple> {
        try {
            rl.lock()
            return appInfos.values().map { AppInfoTuple(it.uid, it.packageInfo) }.toHashSet()
        } finally {
            rl.unlock()
        }
    }

    fun getAppInfos(): Collection<AppInfo> {
        try {
            rl.lock()
            return appInfos.values()
        } finally {
            rl.unlock()
        }
    }

    fun deletePackagesFromCache(packageNames: Set<AppInfoTuple>) {
        try {
            rl.lock()
            packageNames.forEach { tuple ->
                appInfos.get(
                    tuple.uid).filter { tuple.packageName == it.packageInfo }.forEach { ai ->
                    upgradeToWriteLock()
                    appInfos.remove(tuple.uid, ai)
                    downgradeToReadLock()
                }
            }
        } finally {
            rl.unlock()
        }
        // Delete the uninstalled apps from database
        appInfoRepository.deleteByPackageName(packageNames.map { it.packageName })

    }

    fun getNonFirewalledAppsPackageNames(): List<AppInfo> {
        try {
            rl.lock()
            return appInfos.values().filter { it.isInternetAllowed }
        } finally {
            rl.unlock()
        }
    }

    // TODO: Use the package-manager API instead
    fun isOrbotInstalled(): Boolean {
        try {
            rl.lock()
            return appInfos.values().any { it.packageInfo == OrbotHelper.ORBOT_PACKAGE_NAME }
        } finally {
            rl.unlock()
        }
    }

    fun hasUid(uid: Int): Boolean {
        try {
            rl.lock()
            return appInfos.containsKey(uid)
        } finally {
            rl.unlock()
        }
    }

    fun isUidExcluded(uid: Int): Boolean {
        try {
            rl.lock()
            return appInfos.get(uid).any { it.isExcluded }
        } finally {
            rl.unlock()
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
        try {
            rl.lock()
            return appInfos.values().filter { it.isExcluded }.map { it.packageInfo }
        } finally {
            rl.unlock()
        }
    }

    fun getPackageNameByAppName(appName: String): String? {
        try {
            rl.lock()
            return appInfos.values().firstOrNull { it.appName == appName }?.packageInfo
        } finally {
            rl.unlock()
        }
    }

    fun getAppNamesByUid(uid: Int): List<String> {
        try {
            rl.lock()
            return appInfos.get(uid).map { it.appName }
        } finally {
            rl.unlock()
        }
    }

    fun getPackageNamesByUid(uid: Int): List<String> {
        try {
            rl.lock()
            return appInfos.get(uid).filter { !it.isSystemApp }.map { it.packageInfo }
        } finally {
            rl.unlock()
        }
    }

    fun getAllAppNames(): List<String> {
        try {
            rl.lock()
            return appInfos.values().map { it.appName }.sorted()
        } finally {
            rl.unlock()
        }
    }

    fun getAppNameByUid(uid: Int): String? {
        try {
            rl.lock()
            return appInfos.get(uid).firstOrNull()?.appName
        } finally {
            rl.unlock()
        }
    }

    fun getAppInfoByPackage(packageName: String?): AppInfo? {
        if (packageName.isNullOrBlank()) return null

        try {
            rl.lock()
            return appInfos.values().firstOrNull { it.packageInfo == packageName }
        } finally {
            rl.unlock()
        }
    }

    fun getAppInfoByUid(uid: Int): AppInfo? {
        try {
            rl.lock()
            return appInfos.get(uid).firstOrNull()
        } finally {
            rl.unlock()
        }
    }

    private fun getAppInfosByUid(uid: Int): Collection<AppInfo> {
        try {
            rl.lock()
            return appInfos.get(uid)
        } finally {
            rl.unlock()
        }
    }

    fun getPackageNameByUid(uid: Int): String? {
        try {
            rl.lock()
            return appInfos.get(uid).firstOrNull()?.packageInfo
        } finally {
            rl.unlock()
        }
    }

    fun getCategoryListByAppName(appName: String): List<String> {
        try {
            rl.lock()
            return appInfos.values().filter {
                it.appName.contains(appName)
            }.map { it.appCategory }.distinct().sorted()
        } finally {
            rl.unlock()
        }
    }

    private fun getBlockedCountForCategory(categoryName: String): Int {
        try {
            rl.lock()
            return appInfos.values().filter {
                it.appCategory == categoryName && !it.isInternetAllowed
            }.size
        } finally {
            rl.unlock()
        }
    }

    private fun getWhitelistCountForCategory(categoryName: String): Int {
        try {
            rl.lock()
            return appInfos.values().filter {
                it.appCategory == categoryName && it.whiteListUniv1
            }.size
        } finally {
            rl.unlock()
        }
    }

    private fun getExcludedCountForCategory(categoryName: String): Int {
        try {
            rl.lock()
            return appInfos.values().filter {
                it.appCategory == categoryName && it.isExcluded
            }.size
        } finally {
            rl.unlock()
        }
    }

    fun getWhitelistAppData(): List<AppInfo> {
        try {
            rl.lock()
            return appInfos.values().filter { it.whiteListUniv1 }
        } finally {
            rl.unlock()
        }
    }

    suspend fun loadAppFirewallRules() {
        if (isFirewallRulesLoaded) return

        withContext(Dispatchers.IO) {
            reloadAppList()
        }
    }

    private fun updateAppsInternetPermission(uid: Int, isAllowed: Boolean) {
        try {
            wl.lock()
            appInfos.get(uid).forEach {
                it.isInternetAllowed = isAllowed
            }
            downgradeToReadLock()
            appInfosLiveData.postValue(appInfos.values())
        } finally {
            rl.unlock()
        }
    }

    private fun updateAppsExcludedPermission(uid: Int, isExcluded: Boolean) {

        try {
            wl.lock()
            appInfos.get(uid).forEach {
                it.isExcluded = isExcluded
                if (isExcluded) {
                    it.isInternetAllowed = true
                }
            }
            downgradeToReadLock()
            appInfosLiveData.postValue(appInfos.values())
        } finally {
            rl.unlock()
        }
    }

    private fun updateAppsWhitelist(uid: Int, isWhitelisted: Boolean) {
        try {
            wl.lock()
            appInfos.get(uid).forEach {
                it.whiteListUniv1 = isWhitelisted
                if (isWhitelisted) {
                    it.isInternetAllowed = true
                }
            }
            downgradeToReadLock()
            appInfosLiveData.postValue(appInfos.values())
        } finally {
            rl.unlock()
        }
    }

    private fun updateCategoryAppsInternetPermission(categoryName: String, isAllowed: Boolean) {
        try {
            wl.lock()
            appInfos.values().filter { it.appCategory == categoryName }.forEach {
                if (!it.whiteListUniv1) {
                    it.isInternetAllowed = !isAllowed
                }
            }
            downgradeToReadLock()
            appInfosLiveData.postValue(appInfos.values())
        } finally {
            rl.unlock()
        }
    }

    fun persistAppInfo(appInfo: AppInfo) {
        appInfoRepository.insert(appInfo)

        try {
            wl.lock()
            appInfos.put(appInfo.uid, appInfo)
            downgradeToReadLock()
            appInfosLiveData.postValue(appInfos.values())
        } finally {
            rl.unlock()
        }
    }

    private fun reloadAppList() {
        val apps = appInfoRepository.getAppInfo()
        if (apps.isEmpty()) {
            isFirewallRulesLoaded = false
            return
        }

        try {
            wl.lock()
            appInfos.clear()
            apps.forEach {
                appInfos.put(it.uid, it)
            }
            downgradeToReadLock()
            isFirewallRulesLoaded = true
            appInfosLiveData.postValue(appInfos.values())
        } finally {
            rl.unlock()
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

            try {
                wl.lock()
                appInfos.clear()
                // If the app is excluded, then remove all the other rules.
                appInfos.values().forEach {
                    if (filterCategories.isNotEmpty() && !filterCategories.contains(
                            it.appCategory)) return@forEach

                    it.isExcluded = checked
                    if (checked) {
                        it.whiteListUniv1 = false
                        it.isInternetAllowed = true
                    }
                }
                downgradeToReadLock()
                appInfosLiveData.postValue(appInfos.values())
            } finally {
                rl.unlock()
            }
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

            try {
                wl.lock()
                appInfos.clear()
                // If the app is whitelisted, then remove from exclude and allow internet to the app.
                appInfos.values().forEach {
                    if (filterCategories.isNotEmpty() && !filterCategories.contains(
                            it.appCategory)) return@forEach

                    it.whiteListUniv1 = checked
                    if (checked) {
                        it.isExcluded = false
                        it.isInternetAllowed = true
                    }
                }
                downgradeToReadLock()
                appInfosLiveData.postValue(appInfos.values())
            } finally {
                rl.unlock()
            }
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
            val appInfo = getAppInfosByUid(uid)
            appInfo.forEach {
                categoryInfoRepository.updateNumberOfBlocked(it.appCategory, !isBlocked)
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "Category block executed with blocked as ${!isBlocked}")
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
