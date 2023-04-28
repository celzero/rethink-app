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

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.service.FirewallManager.GlobalVariable.appInfos
import com.celzero.bravedns.service.FirewallManager.GlobalVariable.appInfosLiveData
import com.celzero.bravedns.service.FirewallManager.GlobalVariable.foregroundUids
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.OrbotHelper
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.Multimap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object FirewallManager : KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()
    private val lock = ReentrantReadWriteLock()

    const val NOTIF_CHANNEL_ID_FIREWALL_ALERTS = "Firewall_Alerts"

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
                    0 -> {
                        NONE
                    }
                    1 -> {
                        NONE
                    }
                    2 -> {
                        NONE
                    }
                    3 -> {
                        NONE
                    }
                    4 -> {
                        ISOLATE
                    }
                    5 -> {
                        BYPASS_DNS_FIREWALL
                    }
                    6 -> {
                        BYPASS_UNIVERSAL
                    }
                    7 -> {
                        EXCLUDE
                    }
                    else -> {
                        NONE
                    }
                }
            }
        }

        fun bypassUniversal(): Boolean {
            return this == BYPASS_UNIVERSAL
        }

        fun bypassDnsFirewall(): Boolean {
            return this == BYPASS_DNS_FIREWALL
        }

        fun isolate(): Boolean {
            return this == ISOLATE
        }

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
                    0 -> {
                        ALLOW
                    }
                    1 -> {
                        BOTH
                    }
                    2 -> {
                        UNMETERED
                    }
                    3 -> {
                        METERED
                    }
                    4 -> {
                        ALLOW
                    }
                    5 -> {
                        ALLOW
                    }
                    else -> {
                        ALLOW
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
        INSTALLED(R.string.category_name_installed)
    }

    object GlobalVariable {

        var appInfos: Multimap<Int, AppInfo> = HashMultimap.create()

        // TODO: protect access to the foregroundUids (read/write)
        @Volatile var foregroundUids: HashSet<Int> = HashSet()

        var appInfosLiveData: MutableLiveData<Collection<AppInfo>> = MutableLiveData()
    }

    init {
        io { loadAppFirewallRules() }
    }

    data class AppInfoTuple(val uid: Int, var packageName: String)

    @Volatile private var isFirewallRulesLoaded: Boolean = false

    fun isUidFirewalled(uid: Int): Boolean {
        return connectionStatus(uid) != ConnectionStatus.ALLOW
    }

    fun isUidSystemApp(uid: Int): Boolean {
        lock.read {
            return appInfos.get(uid).any { it.isSystemApp }
        }
    }

    fun getTotalApps(): Int {
        lock.read {
            return appInfos.values().count()
        }
    }

    fun getPackageNames(): Set<AppInfoTuple> {
        lock.read {
            return appInfos.values().map { AppInfoTuple(it.uid, it.packageName) }.toHashSet()
        }
    }

    fun deletePackages(packagesToDelete: Set<AppInfoTuple>) {
        lock.write {
            packagesToDelete.forEach { tuple ->
                appInfos
                    .get(tuple.uid)
                    .filter { tuple.packageName == it.packageName }
                    .forEach { ai -> appInfos.remove(tuple.uid, ai) }
            }
        }
        io {
            // Delete the uninstalled apps from database
            appInfoRepository.deleteByPackageName(packagesToDelete.map { it.packageName })
        }
    }

    fun deletePackage(packageName: String) {
        lock.write {
            appInfos
                .values()
                .filter { it.packageName == packageName }
                .forEach { appInfos.remove(it.uid, it) }
        }
        io {
            // Delete the uninstalled apps from database
            appInfoRepository.deleteByPackageName(listOf(packageName))
        }
    }

    fun getNonFirewalledAppsPackageNames(): List<AppInfo> {
        lock.read {
            return appInfos.values().filter { it.connectionStatus == ConnectionStatus.ALLOW.id }
        }
    }

    // TODO: Use the package-manager API instead
    fun isOrbotInstalled(): Boolean {
        lock.read {
            return appInfos.values().any { it.packageName == OrbotHelper.ORBOT_PACKAGE_NAME }
        }
    }

    fun hasUid(uid: Int): Boolean {
        lock.read {
            return appInfos.containsKey(uid)
        }
    }

    fun appStatus(uid: Int): FirewallStatus {
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

    fun connectionStatus(uid: Int): ConnectionStatus {
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

    fun getExcludedApps(): MutableSet<String> {
        lock.read {
            return appInfos
                .values()
                .filter { it.firewallStatus == FirewallStatus.EXCLUDE.id }
                .map { it.packageName }
                .toMutableSet()
        }
    }

    // any app is bypassed both dns and firewall
    fun isAnyAppBypassesDns(): Boolean {
        lock.read {
            return appInfos.values().any { it.firewallStatus == FirewallStatus.BYPASS_DNS_FIREWALL.id }
        }
    }

    fun getPackageNameByAppName(appName: String): String? {
        lock.read {
            return appInfos.values().firstOrNull { it.appName == appName }?.packageName
        }
    }

    fun getAppNamesByUid(uid: Int): List<String> {
        lock.read {
            return appInfos.get(uid).map { it.appName }
        }
    }

    fun getPackageNamesByUid(uid: Int): List<String> {
        lock.read {
            return appInfos.get(uid).map { it.packageName }
        }
    }

    fun getAllAppNames(): List<String> {
        return getAppInfos().map { it.appName }.sortedBy { it.lowercase() }
    }

    fun getAppNameByUid(uid: Int): String? {
        lock.read {
            return appInfos.get(uid).firstOrNull()?.appName
        }
    }

    fun getAppInfoByPackage(packageName: String?): AppInfo? {
        if (packageName.isNullOrBlank()) return null
        lock.read {
            return appInfos.values().firstOrNull { it.packageName == packageName }
        }
    }

    fun getAppInfoByUid(uid: Int): AppInfo? {
        lock.read {
            return appInfos.get(uid).firstOrNull()
        }
    }

    fun getPackageNameByUid(uid: Int): String? {
        lock.read {
            return appInfos.get(uid).firstOrNull()?.packageName
        }
    }

    fun getCategoriesForSystemApps(): List<String> {
        return getAppInfos().filter { it.isSystemApp }.map { it.appCategory }.distinct().sorted()
    }

    fun getCategoriesForInstalledApps(): List<String> {
        return getAppInfos().filter { !it.isSystemApp }.map { it.appCategory }.distinct().sorted()
    }

    fun getAllCategories(): List<String> {
        return getAppInfos().map { it.appCategory }.distinct().sorted()
    }

    suspend fun loadAppFirewallRules() {
        if (isFirewallRulesLoaded) return

        withContext(Dispatchers.IO) { reloadAppList() }
    }

    private fun invalidateFirewallStatus(
        uid: Int,
        firewallStatus: FirewallStatus,
        connectionStatus: ConnectionStatus
    ) {
        lock.write {
            appInfos.get(uid).forEach {
                it.firewallStatus = firewallStatus.id
                it.connectionStatus = connectionStatus.id
            }
        }
        informObservers()
    }

    suspend fun persistAppInfo(appInfo: AppInfo) {
        appInfoRepository.insert(appInfo)

        lock.write { appInfos.put(appInfo.uid, appInfo) }
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
            apps.forEach { appInfos.put(it.uid, it) }
            isFirewallRulesLoaded = true
        }
        informObservers()
    }

    fun untrackForegroundApps() {
        Log.i(
            LOG_TAG_FIREWALL,
            "launcher in the foreground, clear foreground uids: $foregroundUids"
        )
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
        if (DEBUG)
            Log.d(
                LOG_TAG_FIREWALL,
                "is app $uid foreground? ${locked && isForeground}, isLocked? $locked, is available in foreground list? $isForeground"
            )
        return locked && isForeground
    }

    fun updateFirewalledApps(uid: Int, connectionStatus: ConnectionStatus) {
        io {
            invalidateFirewallStatus(uid, FirewallStatus.NONE, connectionStatus)
            appInfoRepository.updateFirewallStatusByUid(
                uid,
                FirewallStatus.NONE.id,
                connectionStatus.id
            )
        }
    }

    fun updateFirewallStatus(
        uid: Int,
        firewallStatus: FirewallStatus,
        connectionStatus: ConnectionStatus
    ) {
        Log.i(
            LOG_TAG_FIREWALL,
            "Apply firewall rule for uid: ${uid}, ${firewallStatus.name}, ${connectionStatus.name}"
        )
        io {
            invalidateFirewallStatus(uid, firewallStatus, connectionStatus)
            appInfoRepository.updateFirewallStatusByUid(uid, firewallStatus.id, connectionStatus.id)
        }
    }

    private fun getAppInfos(): Collection<AppInfo> {
        lock.read {
            return ImmutableList.copyOf(appInfos.values())
        }
    }

    private fun informObservers() {
        val v = getAppInfos()
        v.let { appInfosLiveData.postValue(v) }
    }

    // labels for spinner / toggle ui
    fun getLabel(context: Context): Array<String> {
        return context.resources.getStringArray(R.array.firewall_rules)
    }

    fun getLabelForStatus(firewallStatus: FirewallStatus, connectionStatus: ConnectionStatus): Int {
        return when (firewallStatus) {
            FirewallStatus.NONE -> {
                when (connectionStatus) {
                    ConnectionStatus.BOTH -> R.string.block
                    ConnectionStatus.METERED -> R.string.block
                    ConnectionStatus.UNMETERED -> R.string.block
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

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
