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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_FIREWALL
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.AppInfoRepository.Companion.NO_PACKAGE_PREFIX
import com.celzero.bravedns.service.FirewallManager.GlobalVariable.appInfos
import com.celzero.bravedns.service.FirewallManager.GlobalVariable.appInfosLiveData
import com.celzero.bravedns.service.FirewallManager.GlobalVariable.foregroundUids
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.RETHINK_PACKAGE
import com.celzero.bravedns.util.OrbotHelper
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.Multimap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object FirewallManager : KoinComponent {

    private val db by inject<AppInfoRepository>()
    private val persistentState by inject<PersistentState>()
    private val mutex = Mutex()

    const val NOTIF_CHANNEL_ID_FIREWALL_ALERTS = "Firewall_Alerts"

    // max time to keep the tombstone entry in the database
    const val TOMBSTONE_EXPIRY_TIME_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    // androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/os/UserHandle.java
    private const val PER_USER_RANGE = 100000

    // lo part is the uid within the user and hi part is the userId
    // androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/os/UserHandle.java#224
    fun appId(uid: Int, mainUserOnly: Boolean = false): Int {
        if (mainUserOnly) return uid % PER_USER_RANGE

        return uid
    }

    // hi part is the userId and lo part is the uid within the user
    // androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/os/UserHandle.java#183
    fun userId(uid: Int): Int {
        return uid / PER_USER_RANGE
    }

    // Below are the firewall rule set
    // app-status | connection-status |  Rule
    // none       |    ALLOW          |  allow
    // none       |    BOTH           |  block
    // none       |    wifi           |  WiFi-data-block
    // none       |    mobile         |  mobile-data-block
    enum class FirewallStatus(val id: Int) {
        BYPASS_UNIVERSAL(2),
        EXCLUDE(3),
        ISOLATE(4),
        NONE(5),
        UNTRACKED(6),
        BYPASS_DNS_FIREWALL(7);

        companion object {
            // UI label index constants for getStatusByLabel mapping
            // Positions 0-3: NONE status with different ConnectionStatus values
            private const val LABEL_INDEX_NONE_ALLOW = 0      // Allow all
            private const val LABEL_INDEX_NONE_BLOCK = 1      // Block all
            private const val LABEL_INDEX_NONE_WIFI_BLOCK = 2 // Block WiFi only
            private const val LABEL_INDEX_NONE_DATA_BLOCK = 3 // Block Mobile data only

            // Positions 4-7: Special firewall statuses
            private const val LABEL_INDEX_ISOLATE = 4
            private const val LABEL_INDEX_BYPASS_DNS_FIREWALL = 5
            private const val LABEL_INDEX_BYPASS_UNIVERSAL = 6
            private const val LABEL_INDEX_EXCLUDE = 7


            fun getStatus(id: Int): FirewallStatus {
                return when (id) {
                    BYPASS_UNIVERSAL.id -> {
                        BYPASS_UNIVERSAL
                    }
                    EXCLUDE.id -> {
                        EXCLUDE
                    }
                    ISOLATE.id -> {
                        ISOLATE
                    }
                    BYPASS_DNS_FIREWALL.id -> {
                        BYPASS_DNS_FIREWALL
                    }
                    else -> {
                        NONE
                    }
                }
            }

            fun getStatusByLabel(id: Int): FirewallStatus {
                return when (id) {
                    LABEL_INDEX_NONE_ALLOW -> NONE
                    LABEL_INDEX_NONE_BLOCK -> NONE
                    LABEL_INDEX_NONE_WIFI_BLOCK -> NONE
                    LABEL_INDEX_NONE_DATA_BLOCK -> NONE
                    LABEL_INDEX_ISOLATE -> ISOLATE
                    LABEL_INDEX_BYPASS_DNS_FIREWALL -> BYPASS_DNS_FIREWALL
                    LABEL_INDEX_BYPASS_UNIVERSAL -> BYPASS_UNIVERSAL
                    LABEL_INDEX_EXCLUDE -> EXCLUDE
                    else -> NONE
                }
            }
        }

        fun bypassUniversal(): Boolean {
            return this == BYPASS_UNIVERSAL
        }

        fun bypassDnsFirewall(): Boolean {
            return this == BYPASS_DNS_FIREWALL
        }

        fun isExclude(): Boolean {
            return this == EXCLUDE
        }

        fun isolate(): Boolean {
            return this == ISOLATE
        }

        // even invalid uids are considered as untracked
        fun isUntracked(): Boolean {
            return this == UNTRACKED
        }
    }

    enum class ConnectionStatus(val id: Int) {
        BOTH(0),
        UNMETERED(1),
        METERED(2),
        ALLOW(3);

        fun mobileData(): Boolean {
            return this == METERED
        }

        fun wifi(): Boolean {
            return this == UNMETERED
        }

        fun blocked(): Boolean {
            return this == BOTH
        }

        fun allow(): Boolean {
            return this == ALLOW
        }

        companion object {
            // UI label index constants for getStatusByLabel mapping
            private const val LABEL_INDEX_ALLOW = 0      // Allow all (position 0)
            private const val LABEL_INDEX_BOTH = 1       // Block all (position 1)
            private const val LABEL_INDEX_UNMETERED = 2  // Block WiFi only
            private const val LABEL_INDEX_METERED = 3    // Block Mobile data only
            private const val LABEL_INDEX_ALLOW_START = 4  // Allow for higher positions
            private const val LABEL_INDEX_ALLOW_END = 5    // Allow for higher positions

            fun getStatus(id: Int): ConnectionStatus {
                return when (id) {
                    BOTH.id -> {
                        BOTH
                    }
                    UNMETERED.id -> {
                        UNMETERED
                    }
                    METERED.id -> {
                        METERED
                    }
                    ALLOW.id -> {
                        ALLOW
                    }
                    else -> {
                        ALLOW
                    }
                }
            }

            fun getStatusByLabel(id: Int): ConnectionStatus {
                return when (id) {
                    LABEL_INDEX_ALLOW -> ALLOW
                    LABEL_INDEX_BOTH -> BOTH
                    LABEL_INDEX_UNMETERED -> UNMETERED
                    LABEL_INDEX_METERED -> METERED
                    LABEL_INDEX_ALLOW_START -> ALLOW
                    LABEL_INDEX_ALLOW_END -> ALLOW
                    else -> ALLOW
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
        INSTALLED(R.string.category_name_installed)
    }

    object GlobalVariable {

        var appInfos: Multimap<Int, AppInfo> = HashMultimap.create()

        // TODO: protect access to the foregroundUids (read/write)
        @Volatile var foregroundUids: HashSet<Int> = HashSet()

        var appInfosLiveData: MutableLiveData<Collection<AppInfo>> = MutableLiveData()
    }

    init {
        io { load() }
    }

    data class AppInfoTuple(val uid: Int, val packageName: String)

    suspend fun isUidFirewalled(uid: Int): Boolean {
        return connectionStatus(uid) != ConnectionStatus.ALLOW
    }

    suspend fun isUidSystemApp(uid: Int): Boolean {
        mutex.withLock {
            return appInfos.get(uid).any { it.isSystemApp }
        }
    }

    suspend fun getAllApps(): Set<AppInfoTuple> {
        mutex.withLock {
            // only return apps that are not tombstoned
            return appInfos.values().map { AppInfoTuple(it.uid, it.packageName) }.toSet()
        }
    }

    suspend fun tombstoneApp(uid: Int, packageName: String?, ts: Long = System.currentTimeMillis()) {
        val newUid = if (uid > 0) -1 * uid else uid // use negative uid to mark the app as tombstone
        mutex.withLock {
            val iter = appInfos.get(uid).iterator()
            while (iter.hasNext()) {
                val ai = iter.next()
                if (ai.packageName == packageName) {
                    iter.remove() // safe removal while iterating
                    ai.uid = newUid
                    ai.tombstoneTs = ts
                    appInfos.put(newUid, ai)
                    break
                }
            }
        }
        db.tombstoneApp(newUid, uid, packageName, ts)
        Logger.d(LOG_TAG_FIREWALL, "tombstone app: $packageName, uid: $uid, ts: $ts, newUid: $newUid")
        informObservers()
    }

    suspend fun deletePackage(uid: Int, packageName: String?) {
        mutex.withLock {
            appInfos
                .values()
                .filter { it.packageName == packageName }
                .forEach { appInfos.remove(it.uid, it) }
        }
        // Delete the uninstalled apps from database
        db.deletePackage(uid, packageName)
    }

    suspend fun getNonFirewalledAppsPackageNames(): List<AppInfo> {
        mutex.withLock {
            return appInfos.values().filter { it.connectionStatus == ConnectionStatus.ALLOW.id }
        }
    }

    // TODO: Use the package-manager API instead
    suspend fun isOrbotInstalled(): Boolean {
        mutex.withLock {
            return appInfos.values().any { it.packageName == OrbotHelper.ORBOT_PACKAGE_NAME && it.tombstoneTs == 0L }
        }
    }

    suspend fun hasUid(uid: Int): Boolean {
        mutex.withLock {
            val appInfo = appInfos.get(uid)
            return appInfo.isNotEmpty()
        }
    }

    suspend fun isTombstone(packageName: String): Boolean {
        mutex.withLock {
            return appInfos.values().any {
                it.packageName == packageName && it.tombstoneTs > 0L
            }
        }
    }

    suspend fun appStatus(uid: Int): FirewallStatus {
        val appInfo = getAppInfoByUid(uid) ?: return FirewallStatus.UNTRACKED

        return when (appInfo.firewallStatus) {
            FirewallStatus.BYPASS_UNIVERSAL.id -> FirewallStatus.BYPASS_UNIVERSAL
            FirewallStatus.EXCLUDE.id -> FirewallStatus.EXCLUDE
            FirewallStatus.NONE.id -> FirewallStatus.NONE
            FirewallStatus.ISOLATE.id -> FirewallStatus.ISOLATE
            FirewallStatus.BYPASS_DNS_FIREWALL.id -> FirewallStatus.BYPASS_DNS_FIREWALL
            else -> FirewallStatus.NONE
        }
    }

    suspend fun connectionStatus(uid: Int): ConnectionStatus {
        val appInfo = getAppInfoByUid(uid) ?: return ConnectionStatus.ALLOW
        return when (appInfo.connectionStatus) {
            ConnectionStatus.METERED.id -> ConnectionStatus.METERED
            ConnectionStatus.UNMETERED.id -> ConnectionStatus.UNMETERED
            ConnectionStatus.BOTH.id -> ConnectionStatus.BOTH
            ConnectionStatus.ALLOW.id -> ConnectionStatus.ALLOW
            else -> ConnectionStatus.ALLOW
        }
    }

    fun getApplistObserver(): MutableLiveData<Collection<AppInfo>> {
        return appInfosLiveData
    }

    suspend fun getExcludedApps(): MutableSet<String> {
        mutex.withLock {
            return appInfos
                .values()
                .filter { it.firewallStatus == FirewallStatus.EXCLUDE.id && it.tombstoneTs == 0L }
                .map { it.packageName }
                .toMutableSet()
        }
    }

    // any app is bypassed both dns and firewall
    suspend fun isAnyAppBypassesDns(): Boolean {
        mutex.withLock {
            return appInfos.values().any {
                it.firewallStatus == FirewallStatus.BYPASS_DNS_FIREWALL.id &&
                it.tombstoneTs == 0L
            }
        }
    }

    suspend fun getPackageNameByAppName(appName: String?): String? {
        mutex.withLock {
            return appInfos.values().firstOrNull { it.appName == appName }?.packageName
        }
    }

    suspend fun getAppNamesByUid(uid: Int): List<String> {
        mutex.withLock {
            return appInfos.get(uid).map { it.appName }
        }
    }

    suspend fun getPackageNamesByUid(uid: Int): List<String> {
        mutex.withLock {
            return appInfos.get(uid).map { it.packageName }
        }
    }

    suspend fun getAllAppNames(): List<String> {
        return getAppInfos().map { it.appName }.sortedBy { it.lowercase() }
    }

    suspend fun getAllAppNamesSortedByVpnPermission(context: Context): List<String> {
        val appInfos = getAppInfos()
        val packageManager = context.packageManager

        // separate apps with and without VPN permission
        val appsWithVpnPermission = mutableListOf<String>()
        val appsWithoutVpnPermission = mutableListOf<String>()

        appInfos.forEach { appInfo ->
            // skip the app itself
            if (appInfo.packageName == RETHINK_PACKAGE) {
                return@forEach
            }
            // skip apps which do not have internet permission
            if (!appInfo.hasInternetPermission(packageManager)) {
                return@forEach
            }
            // skip tombstoned apps
            if (appInfo.tombstoneTs > 0L) {
                return@forEach
            }
            val hasVpnPermission = try {
                val packageInfo = packageManager.getPackageInfo(appInfo.packageName, PackageManager.GET_SERVICES)
                packageInfo.isVpnRelatedApp(packageManager)
            } catch (_: Exception) {
                false
            }

            if (hasVpnPermission) {
                appsWithVpnPermission.add(appInfo.appName)
            } else {
                appsWithoutVpnPermission.add(appInfo.appName)
            }
        }

        // sort each group alphabetically and combine with the list of apps
        return appsWithVpnPermission.sortedBy { it.lowercase() } +
               appsWithoutVpnPermission.sortedBy { it.lowercase() }
    }

    private fun PackageInfo.isVpnRelatedApp(pm: PackageManager): Boolean {
        val vpnServiceStr = "android.net.VpnService"
        val hasVpnService = services?.any {
            it.permission == android.Manifest.permission.BIND_VPN_SERVICE
        } ?: false

        val hasVpnIntent = pm.queryIntentServices(
            Intent(vpnServiceStr).apply { `package` = packageName },
            PackageManager.MATCH_ALL
        ).isNotEmpty()

        return hasVpnService || hasVpnIntent
    }

    suspend fun getAppNameByUid(uid: Int): String? {
        mutex.withLock {
            return appInfos.get(uid).firstOrNull()?.appName
        }
    }

    suspend fun getAppInfoByPackage(packageName: String?): AppInfo? {
        if (packageName.isNullOrBlank()) return null
        mutex.withLock {
            return appInfos.values().firstOrNull { it.packageName == packageName }
        }
    }

    suspend fun getAppInfoByUid(uid: Int): AppInfo? {
        mutex.withLock {
            return appInfos.get(uid).firstOrNull()
        }
    }

    suspend fun getPackageNameByUid(uid: Int): String? {
        mutex.withLock {
            return appInfos.get(uid).firstOrNull()?.packageName
        }
    }

    suspend fun getCategoriesForSystemApps(): List<String> {
        return getAppInfos().filter { it.isSystemApp }.map { it.appCategory }.distinct().sorted()
    }

    suspend fun getCategoriesForInstalledApps(): List<String> {
        return getAppInfos().filter { !it.isSystemApp }.map { it.appCategory }.distinct().sorted()
    }

    suspend fun getAllCategories(): List<String> {
        return getAppInfos().map { it.appCategory }.distinct().sorted()
    }

    private suspend fun invalidateFirewallStatus(
        uid: Int,
        firewallStatus: FirewallStatus,
        connectionStatus: ConnectionStatus
    ) {
        mutex.withLock {
            appInfos.get(uid).forEach {
                it.firewallStatus = firewallStatus.id
                it.connectionStatus = connectionStatus.id
            }
        }
        informObservers()
        closeConnectionsIfNeeded(uid, firewallStatus, connectionStatus)
    }

    private suspend fun closeConnectionsIfNeeded(
        uid: Int,
        firewallStatus: FirewallStatus,
        connectionStatus: ConnectionStatus
    ) {
        if (firewallStatus == FirewallStatus.ISOLATE) {
            VpnController.closeConnectionsIfNeeded(uid, "isolate-manual-close")
        } else if (
            firewallStatus == FirewallStatus.NONE && connectionStatus != ConnectionStatus.ALLOW
        ) {
            VpnController.closeConnectionsIfNeeded(uid, "block-manual-close")
        } else {
            // no-op, no need to close existing connections, if the app is not isolated or blocked
        }
    }

    suspend fun updateUidAndResetTombstone(oldUid: Int, newUid: Int, pkg: String) {
        // while updating the package reset the tombstone timestamp
        var cacheok = false
        val appInfo = getAppInfoByUid(oldUid)
        Logger.i(LOG_TAG_FIREWALL, "updateUidAndResetTombstone: $oldUid -> $newUid; has? ${appInfo?.packageName} == $pkg")
        mutex.withLock {
            val iter = appInfos.get(oldUid).iterator()
            while (iter.hasNext()) {
                val ai = iter.next()
                if (ai.packageName == pkg) {
                    iter.remove() // safe removal while iterating
                    ai.uid = newUid
                    ai.tombstoneTs = 0
                    appInfos.put(newUid, ai)
                    cacheok = true
                    break
                }
            }
        }

        val dbok = db.updateUid(oldUid, newUid, pkg)
        Logger.d(LOG_TAG_FIREWALL, "update: $pkg; $oldUid -> $newUid; c? $cacheok; db? $dbok")
        informObservers()
    }

    suspend fun persistAppInfo(appInfo: AppInfo) {
        db.insert(appInfo)

        mutex.withLock { appInfos.put(appInfo.uid, appInfo) }
        informObservers()
    }

    suspend fun load(): Int {
        val apps = db.getAppInfo()
        if (apps.isEmpty()) {
            Logger.w(LOG_TAG_FIREWALL, "no apps found in db, no app-based rules to load")
            return 0
        }

        mutex.withLock {
            appInfos.clear()
            apps.forEach { appInfos.put(it.uid, it) }
        }
        informObservers()
        return apps.size
    }

    fun untrackForegroundApps() {
        Logger.i(
            LOG_TAG_FIREWALL,
            "launcher in the foreground, clear foreground uids: $foregroundUids"
        )
        foregroundUids.clear()
    }

    fun trackForegroundApp(uid: Int) {
        io {
            mutex.withLock {
                val appInfo = appInfos[uid]

                if (appInfo.isNullOrEmpty()) {
                    Logger.i(
                        LOG_TAG_FIREWALL,
                        "No such app $uid to update 'dis/allow' firewall rule"
                    )
                    return@io
                }
            }
            val isAppUid = AndroidUidConfig.isUidAppRange(uid)
            Logger.d(LOG_TAG_FIREWALL, "app in foreground; uid($uid)? $isAppUid")

            // Only track packages within app uid range.
            if (!isAppUid) return@io

            foregroundUids.add(uid)
        }
    }

    fun isAppForeground(uid: Int, keyguardManager: KeyguardManager?): Boolean {
        // isKeyguardLocked check for allow apps in foreground.
        // When the user engages the app and locks the screen, the app is
        // considered to be in background and the connections for those apps
        // should be blocked.
        val locked = keyguardManager?.isKeyguardLocked == true
        val inForegroundList = foregroundUids.contains(uid)
        Logger.d(
            LOG_TAG_FIREWALL,
            "is app $uid foreground? ${!locked && inForegroundList}, isLocked? $locked, in foreground list? $inForegroundList"
        )
        return !locked && inForegroundList
    }

    suspend fun updateFirewalledApps(uid: Int, connectionStatus: ConnectionStatus) {
        invalidateFirewallStatus(uid, FirewallStatus.NONE, connectionStatus)
        db.updateFirewallStatusByUid(uid, FirewallStatus.NONE.id, connectionStatus.id)
    }

    suspend fun updateFirewallStatus(
        uid: Int,
        firewallStatus: FirewallStatus,
        connectionStatus: ConnectionStatus
    ) {
        Logger.i(
            LOG_TAG_FIREWALL,
            "Apply firewall rule for uid: ${uid}, ${firewallStatus.name}, ${connectionStatus.name}"
        )
        if (isUnknownPackage(uid) && firewallStatus.isExclude()) {
            Logger.w(LOG_TAG_FIREWALL, "Cannot exclude unknown package: $uid")
            return
        }

        invalidateFirewallStatus(uid, firewallStatus, connectionStatus)
        db.updateFirewallStatusByUid(uid, firewallStatus.id, connectionStatus.id)
    }

    private suspend fun getAppInfos(): Collection<AppInfo> {
        mutex.withLock {
            if (appInfos.isEmpty) {
                return emptyList()
            }
            return ImmutableList.copyOf(appInfos.values())
        }
    }

    suspend fun isUnknownPackage(uid: Int): Boolean {
        return getAppInfoByUid(uid)?.packageName?.startsWith(NO_PACKAGE_PREFIX) ?: false
    }

    private suspend fun informObservers() {
        val v = getAppInfos()
        v.let { appInfosLiveData.postValue(v) }
    }

    // labels for spinner / toggle ui
    fun getLabel(context: Context): Array<String> {
        return context.resources.getStringArray(R.array.firewall_rules)
    }

    fun getLabelForStatus(firewallStatus: FirewallStatus, connectionStatus: ConnectionStatus, prevConnStatus: ConnectionStatus): Int {
        return when (firewallStatus) {
            FirewallStatus.NONE -> {
                when (connectionStatus) {
                    ConnectionStatus.BOTH -> R.string.block
                    ConnectionStatus.METERED ->
                        if (prevConnStatus == ConnectionStatus.UNMETERED || prevConnStatus == ConnectionStatus.ALLOW) {
                            R.string.block
                        } else {
                            R.string.allow
                        }
                    ConnectionStatus.UNMETERED ->
                        if (prevConnStatus == ConnectionStatus.METERED || prevConnStatus == ConnectionStatus.ALLOW) {
                            R.string.block
                        } else {
                            R.string.allow
                        }
                    ConnectionStatus.ALLOW -> R.string.allow
                }
            }
            FirewallStatus.BYPASS_UNIVERSAL -> {
                R.string.bypass_universal
            }
            FirewallStatus.EXCLUDE -> {
                R.string.exclude
            }
            FirewallStatus.ISOLATE -> {
                R.string.isolate
            }
            FirewallStatus.UNTRACKED -> {
                R.string.untracked
            }
            FirewallStatus.BYPASS_DNS_FIREWALL -> {
                R.string.bypass_dns_firewall
            }
        }
    }

    fun updateIsProxyExcluded(uid: Int, isProxyExcluded: Boolean) {
        io {
            mutex.withLock {
                appInfos.get(uid).forEach {
                    it.isProxyExcluded = isProxyExcluded
                }
            }
            db.updateProxyExcluded(uid, isProxyExcluded)
            informObservers()
        }
    }

    suspend fun getTombstoneApps(): List<AppInfo> {
        mutex.withLock {
            return appInfos.values().filter { it.tombstoneTs > 0L }
        }
    }

    suspend fun isAppExcludedFromProxy(uid: Int): Boolean {
        return getAppInfoByUid(uid)?.isProxyExcluded ?: false
    }

    fun stats(): String {
        // add count of apps in each firewall status
        val statusCount = HashMap<FirewallStatus, Int>()
        appInfos.values().forEach {
            val status = FirewallStatus.getStatus(it.firewallStatus)
            statusCount[status] = (statusCount[status] ?: 0) + 1
        }
        val sb = StringBuilder()
        sb.append("Apps\n")
        statusCount.forEach { (status, count) ->
            sb.append("   ${status.name}: $count\n")
        }
        sb.append("Universal firewall\n")
        sb.append("   block_http_connections: ${persistentState.getBlockHttpConnections()}\n")
        sb.append("   block_metered_connections: ${persistentState.getBlockMeteredConnections()}\n")
        sb.append("   universal_lockdown: ${persistentState.getUniversalLockdown()}\n")
        sb.append("   block_newly_installed_app: ${persistentState.getBlockNewlyInstalledApp()}\n")
        sb.append("   disallow_dns_bypass: ${persistentState.getDisallowDnsBypass()}\n")
        sb.append("   udp_blocked: ${persistentState.getUdpBlocked()}\n")
        sb.append("   block_unknown_connections: ${persistentState.getBlockUnknownConnections()}\n")
        sb.append("   block_app_when_background: ${persistentState.getBlockAppWhenBackground()}\n")
        sb.append("   block_when_device_locked: ${persistentState.getBlockWhenDeviceLocked()}\n")

        return sb.toString()
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
