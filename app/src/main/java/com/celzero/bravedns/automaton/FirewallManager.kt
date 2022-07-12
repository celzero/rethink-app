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
package com.celzero.bravedns.automaton

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.appInfos
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.appInfosLiveData
import com.celzero.bravedns.automaton.FirewallManager.GlobalVariable.foregroundUids
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.OrbotHelper
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object FirewallManager : KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()
    private val lock = ReentrantReadWriteLock()

    const val NOTIF_CHANNEL_ID_FIREWALL_ALERTS = "Firewall_Alerts"

    data class DnsCacheRecord(val ttl: Long, val fqdn: String)

    private const val CACHE_BUILDER_MAX_SIZE = 20000L
    private val CACHE_BUILDER_WRITE_EXPIRE_HRS = TimeUnit.DAYS.toHours(3L)

    val ipDomainLookup: Cache<String, DnsCacheRecord> = CacheBuilder.newBuilder().maximumSize(
        CACHE_BUILDER_MAX_SIZE).expireAfterWrite(CACHE_BUILDER_WRITE_EXPIRE_HRS,
                                                 TimeUnit.HOURS).build()

    // Below are the firewall rule set
    // app-status | connection-status |  Rule
    // allowed    |    BOTH           |  allow
    // blocked    |    wifi           |  WiFi-data-block
    // blocked    |    mobile         |  mobile-data-block
    // blocked    |    both           |  block
    enum class FirewallStatus(val id: Int) {
        ALLOW(0), BLOCK(1), WHITELIST(2), EXCLUDE(3), UNTRACKED(4);

        companion object {

            // labels for spinner / toggle ui
            fun getLabel(context: Context): Array<String> {
                return context.resources.getStringArray(R.array.firewall_rules)
            }

            fun getStatus(id: Int): FirewallStatus {
                return when (id) {
                    ALLOW.id -> {
                        ALLOW
                    }
                    BLOCK.id -> {
                        BLOCK
                    }
                    WHITELIST.id -> {
                        WHITELIST
                    }
                    EXCLUDE.id -> {
                        EXCLUDE
                    }
                    else -> {
                        UNTRACKED
                    }
                }
            }

            fun getStatusByLabel(id: Int): FirewallStatus {
                return when (id) {
                    0 -> {
                        ALLOW
                    }
                    1 -> {
                        BLOCK
                    }
                    2 -> {
                        BLOCK
                    }
                    3 -> {
                        BLOCK
                    }
                    4 -> {
                        WHITELIST
                    }
                    5 -> {
                        EXCLUDE
                    }
                    else -> {
                        ALLOW
                    }
                }
            }
        }

        fun whitelisted(): Boolean {
            return this == WHITELIST
        }

        fun excluded(): Boolean {
            return this == EXCLUDE
        }

        fun allowed(): Boolean {
            return this == ALLOW
        }

        fun blocked(): Boolean {
            return this == BLOCK
        }

        fun isUntracked(): Boolean {
            return this == UNTRACKED
        }
    }

    enum class ConnectionStatus(val id: Int) {
        BOTH(0), WIFI(1), MOBILE_DATA(2);

        fun mobileData(): Boolean {
            return this == MOBILE_DATA
        }

        fun wifi(): Boolean {
            return this == WIFI
        }

        fun both(): Boolean {
            return this == BOTH
        }

        companion object {
            fun getStatusByLabel(id: Int): ConnectionStatus {
                return when (id) {
                    0 -> {
                        BOTH
                    }
                    1 -> {
                        BOTH
                    }
                    2 -> {
                        WIFI
                    }
                    3 -> {
                        MOBILE_DATA
                    }
                    4 -> {
                        BOTH
                    }
                    5 -> {
                        BOTH
                    }
                    else -> {
                        BOTH
                    }
                }
            }
        }

    }

    // Firewall app category constants
    enum class CategoryConstants(val nameResId: Int) {
        SYSTEM_COMPONENT(R.string.category_name_sys_components),
        SYSTEM_APP(R.string.category_name_sys_apps),
        OTHER(R.string.category_name_others),
        NON_APP(R.string.category_name_non_app_sys),
        INSTALLED(R.string.category_name_installed);

        companion object {
            fun isSystemComponent(context: Context, name: String): Boolean {
                return context.getString(SYSTEM_COMPONENT.nameResId) == name
            }

            fun isAnySystemCategory(context: Context, name: String): Boolean {
                return context.getString(SYSTEM_COMPONENT.nameResId) == name || context.getString(
                    SYSTEM_APP.nameResId) == name || context.getString(NON_APP.nameResId) == name
            }

            fun isNonApp(context: Context, name: String): Boolean {
                return context.getString(NON_APP.nameResId) == name
            }
        }
    }

    object GlobalVariable {

        var appInfos: Multimap<Int, AppInfo> = HashMultimap.create()

        // TODO: protect access to the foregroundUids (read/write)
        @Volatile var foregroundUids: HashSet<Int> = HashSet()

        var appInfosLiveData: MutableLiveData<Collection<AppInfo>> = MutableLiveData()
    }

    data class AppInfoTuple(val uid: Int, var packageName: String)

    @Volatile private var isFirewallRulesLoaded: Boolean = false

    fun isUidFirewalled(uid: Int): Boolean {
        return appStatus(uid) == FirewallStatus.BLOCK
    }

    fun isUidWhitelisted(uid: Int): Boolean {
        return appStatus(uid) == FirewallStatus.WHITELIST
    }

    fun isUidExcluded(uid: Int): Boolean {
        return appStatus(uid) == FirewallStatus.EXCLUDE
    }

    fun isUidSystemApp(uid: Int): Boolean {
        return getAppInfosByUidLocked(uid).any { it.isSystemApp }
    }

    fun getTotalApps(): Int {
        return getAppInfosLocked().count()
    }

    fun getPackageNames(): Set<AppInfoTuple> {
        return getAppInfosLocked().map { AppInfoTuple(it.uid, it.packageInfo) }.toHashSet()
    }

    fun deletePackagesFromCache(packagesToDelete: Set<AppInfoTuple>) {
        lock.write {
            packagesToDelete.forEach { tuple ->
                appInfos.get(
                    tuple.uid).filter { tuple.packageName == it.packageInfo }.forEach { ai ->
                    appInfos.remove(tuple.uid, ai)
                }
            }
        }
        io {
            // Delete the uninstalled apps from database
            appInfoRepository.deleteByPackageName(packagesToDelete.map { it.packageName })
        }
    }

    fun getNonFirewalledAppsPackageNames(): List<AppInfo> {
        return getAppInfosLocked().filter { it.firewallStatus == FirewallStatus.ALLOW.id }
    }

    // TODO: Use the package-manager API instead
    fun isOrbotInstalled(): Boolean {
        return getAppInfosLocked().any { it.packageInfo == OrbotHelper.ORBOT_PACKAGE_NAME }
    }

    fun hasUid(uid: Int): Boolean {
        lock.read {
            return appInfos.containsKey(uid)
        }
    }

    fun appStatus(uid: Int): FirewallStatus {
        val appInfo = getAppInfoByUid(uid) ?: return FirewallStatus.UNTRACKED

        return when (appInfo.firewallStatus) {
            FirewallStatus.WHITELIST.id -> FirewallStatus.WHITELIST
            FirewallStatus.BLOCK.id -> FirewallStatus.BLOCK
            FirewallStatus.ALLOW.id -> FirewallStatus.ALLOW
            FirewallStatus.EXCLUDE.id -> FirewallStatus.EXCLUDE
            FirewallStatus.UNTRACKED.id -> FirewallStatus.UNTRACKED
            else -> FirewallStatus.UNTRACKED
        }
    }

    fun connectionStatus(uid: Int): ConnectionStatus {
        val appInfo = getAppInfoByUid(uid) ?: return ConnectionStatus.BOTH

        return when (appInfo.metered) {
            ConnectionStatus.MOBILE_DATA.id -> ConnectionStatus.MOBILE_DATA
            ConnectionStatus.WIFI.id -> ConnectionStatus.WIFI
            ConnectionStatus.BOTH.id -> ConnectionStatus.BOTH
            else -> ConnectionStatus.BOTH
        }
    }

    fun getApplistObserver(): MutableLiveData<Collection<AppInfo>> {
        return appInfosLiveData
    }

    fun getExcludedApps(): MutableSet<String> {
        return getAppInfosLocked().filter { it.firewallStatus == FirewallStatus.EXCLUDE.id }.map { it.packageInfo }.toMutableSet()
    }

    fun getPackageNameByAppName(appName: String): String? {
        return getAppInfosLocked().firstOrNull { it.appName == appName }?.packageInfo
    }

    fun getAppNamesByUid(uid: Int): List<String> {
        return getAppInfosByUidLocked(uid).map { it.appName }
    }

    fun getNonSystemAppsPackageNameByUid(uid: Int): List<String> {
        return getAppInfosByUidLocked(uid).filter { !it.isSystemApp }.map { it.packageInfo }
    }

    fun getPackageNamesByUid(uid: Int): List<String> {
        return getAppInfosByUidLocked(uid).map { it.packageInfo }
    }

    fun getAllAppNames(): List<String> {
        return getAppInfosLocked().map { it.appName }.sortedBy { it.lowercase() }
    }

    fun getAppNameByUid(uid: Int): String? {
        return getAppInfosByUidLocked(uid).firstOrNull()?.appName
    }

    fun getAppInfoByPackage(packageName: String?): AppInfo? {
        if (packageName.isNullOrBlank()) return null

        return getAppInfosLocked().firstOrNull { it.packageInfo == packageName }
    }

    fun getAppInfoByUid(uid: Int): AppInfo? {
        return getAppInfosByUidLocked(uid).firstOrNull()
    }

    fun getPackageNameByUid(uid: Int): String? {
        return getAppInfosByUidLocked(uid).firstOrNull()?.packageInfo
    }

    fun getCategoriesForSystemApps(): List<String> {
        return getAppInfosLocked().filter {
            it.isSystemApp
        }.map { it.appCategory }.distinct().sorted()
    }

    fun getCategoriesForInstalledApps(): List<String> {
        return getAppInfosLocked().filter {
            !it.isSystemApp
        }.map { it.appCategory }.distinct().sorted()
    }

    fun getAllCategories(): List<String> {
        return getAppInfosLocked().map { it.appCategory }.distinct().sorted()
    }

    fun getCategoryListByAppName(appName: String): List<String> {
        return getAppInfosLocked().filter {
            it.appName.contains(appName)
        }.map { it.appCategory }.distinct().sorted()
    }

    suspend fun loadAppFirewallRules() {
        if (isFirewallRulesLoaded) return

        withContext(Dispatchers.IO) {
            reloadAppList()
        }
    }

    private fun invalidateFirewallStatus(uid: Int, firewallStatus: FirewallStatus,
                                         connectionStatus: ConnectionStatus) {
        lock.write {
            appInfos.get(uid).forEach {
                it.firewallStatus = firewallStatus.id
                it.metered = connectionStatus.id
            }
        }
        informObservers()
    }

    suspend fun persistAppInfo(appInfo: AppInfo) {
        appInfoRepository.insert(appInfo)

        lock.write {
            appInfos.put(appInfo.uid, appInfo)
        }
        informObservers()
    }

    suspend fun reloadAppList() {
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
        informObservers()
    }

    fun untrackForegroundApps() {
        Log.i(LOG_TAG_FIREWALL,
              "launcher in the foreground, clear foreground uids: $foregroundUids")
        foregroundUids.clear()
    }

    fun trackForegroundApp(uid: Int) {
        val appInfo = appInfos[uid]

        if (appInfo.isNullOrEmpty()) {
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

    fun updateExcludedApps(appInfo: AppInfo, status: Boolean) {
        io {
            val firewallStatus: FirewallStatus = if (status) {
                FirewallStatus.EXCLUDE
            } else {
                FirewallStatus.ALLOW
            }

            invalidateFirewallStatus(appInfo.uid, firewallStatus, ConnectionStatus.BOTH)
            appInfoRepository.updateFirewallStatusByUid(appInfo.uid, firewallStatus.id,
                                                        ConnectionStatus.BOTH.id)
        }
    }

    fun updateWhitelistedApps(appInfo: AppInfo, isWhitelisted: Boolean) {
        io {
            val firewallStatus: FirewallStatus = if (isWhitelisted) {
                FirewallStatus.WHITELIST
            } else {
                FirewallStatus.ALLOW
            }
            invalidateFirewallStatus(appInfo.uid, firewallStatus, ConnectionStatus.BOTH)
            appInfoRepository.updateFirewallStatusByUid(appInfo.uid, firewallStatus.id,
                                                        ConnectionStatus.BOTH.id)
        }
    }

    fun updateFirewalledApps(uid: Int, firewallStatus: FirewallStatus) {
        io {
            invalidateFirewallStatus(uid, firewallStatus, ConnectionStatus.BOTH)
            appInfoRepository.updateFirewallStatusByUid(uid, firewallStatus.id,
                                                        ConnectionStatus.BOTH.id)
        }
    }

    fun updateFirewallStatus(uid: Int, firewallStatus: FirewallStatus,
                             connectionStatus: ConnectionStatus) {
        Log.d(LOG_TAG_FIREWALL,
              "Apply firewall rule for uid: ${uid}, ${firewallStatus.name}, ${connectionStatus.name}")
        io {
            invalidateFirewallStatus(uid, firewallStatus, connectionStatus)
            appInfoRepository.updateFirewallStatusByUid(uid, firewallStatus.id, connectionStatus.id)
        }
    }

    private fun getAppInfosLocked(): MutableCollection<AppInfo> {
        lock.read {
            return appInfos.values()
        }
    }

    private fun getAppInfosByUidLocked(uid: Int): MutableCollection<AppInfo> {
        lock.read {
            return appInfos.get(uid)
        }
    }

    private fun informObservers() {
        val v = getAppInfosLocked()
        v.let {
            appInfosLiveData.postValue(v)
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }
}
