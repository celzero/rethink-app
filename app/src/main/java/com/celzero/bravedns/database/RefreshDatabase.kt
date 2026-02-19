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

import Logger
import Logger.LOG_TAG_APP_DB
import Logger.LOG_TAG_VPN
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
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository.Companion.NO_PACKAGE_PREFIX
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.FirewallManager.TOMBSTONE_EXPIRY_TIME_MS
import com.celzero.bravedns.service.FirewallManager.deletePackage
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.PlayStoreCategory
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getActivityPendingIntent
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isNonApp
import com.celzero.bravedns.wireguard.WgHopManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.jvm.java
import kotlin.random.Random

class RefreshDatabase
internal constructor(
    private var ctx: Context,
    private val connTrackerRepository: ConnectionTrackerRepository,
    private val dnsLogRepository: DnsLogRepository,
    private val rethinkLogRepository: RethinkLogRepository,
    private val persistentState: PersistentState,
    private val eventLogger: EventLogger
) {

    companion object {
        private const val NOTIF_BATCH_NEW_APPS_THRESHOLD = 5
        private val FULL_REFRESH_INTERVAL = TimeUnit.MINUTES.toMillis(1L)
        private const val NOTIF_ID_LOAD_RULES_FAIL = 103
        private const val NOBODY = Constants.INVALID_UID
        private const val ACTION_BASE = 0
        const val ACTION_REFRESH_RESTORE = ACTION_BASE + 1
        const val ACTION_REFRESH_AUTO = ACTION_BASE + 2
        const val ACTION_REFRESH_INTERACTIVE = ACTION_BASE + 3
        const val ACTION_REFRESH_FORCE = ACTION_BASE + 4
        const val ACTION_INSERT_NEW_APP = ACTION_BASE + 5
        const val PENDING_INTENT_REQUEST_CODE_ALLOW = 0x10000000
        const val PENDING_INTENT_REQUEST_CODE_DENY = 0x20000000
    }

    private val randomNotifId: Random = Random
    private val actions: Channel<Action> = Channel(Channel.RENDEZVOUS)

    data class Action(val action: Int, val uid: Int = NOBODY, val cb: suspend () -> Unit = {})

    private var latestRefreshTime: Long = 0L

    init {
        io("RefreshDatabase") {
            for (action in actions) {
                process(action)
            }
        }
    }

    /**
     * Need to rewrite the logic for adding the apps in the database and removing it during
     * uninstall.
     */
    suspend fun refresh(action: Int, cb: suspend () -> Unit = {}) {
        actions.send(Action(action, NOBODY, cb))
    }

    suspend fun addNewApp(uid: Int) {
        actions.send(Action(ACTION_INSERT_NEW_APP, uid))
    }

    suspend fun process(a: Action) {
        try {
            val action = a.action
            val uid = a.uid // may be -1
            Logger.d(LOG_TAG_APP_DB, "Initiated refresh application info $a")
            if (action == ACTION_INSERT_NEW_APP) {
                // ignore invalid uid (app source could not be determined)
                // ignore missing uid (protocol unknown or connectivity mgr missing)
                if (Utilities.isMissingOrInvalidUid(uid)) return
                maybeInsertApp(uid)
                return
            }
            val current = SystemClock.elapsedRealtime()
            // do not auto-refresh and the last refresh was within AUTO_REFRESH_INTERVAL
            if (
                latestRefreshTime > 0 &&
                    current - latestRefreshTime < FULL_REFRESH_INTERVAL &&
                    (action == ACTION_REFRESH_AUTO || action == ACTION_REFRESH_INTERACTIVE)
            ) {
                Logger.i(LOG_TAG_APP_DB, "no-op auto refresh")
                return
            }
            latestRefreshTime = current
            val pm = ctx.packageManager ?: return

            // make sure to maintain the order of the below calls
            val fm = FirewallManager.load()
            val ipm = IpRulesManager.load()
            val dm = DomainRulesManager.load()
            val pxm = ProxyManager.load()
            val wgm = WireguardManager.load(forceRefresh = false)
            val hm = WgHopManager.load(forceRefresh = false)
            // val tm = TcpProxyHelper.load() // no need to load tcp-proxy mapping now (055v)
            //val rm = RpnProxyManager.load()

            Logger.i(
                LOG_TAG_APP_DB,
                "reload: fm: $fm; ip: $ipm; dom: $dm; px: $pxm; wg: $wgm; hm: $hm"
            )

            val canTombstone = persistentState.tombstoneApps
            val trackedApps = FirewallManager.getAllApps()
            // installedPackages includes apps which are disabled by the user
            val installedPackages: List<PackageInfo> =
                if (isAtleastT()) {
                    pm.getInstalledPackages(
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    pm.getInstalledPackages(PackageManager.GET_META_DATA)
                }

            val installedApps: MutableSet<FirewallManager.AppInfoTuple> = mutableSetOf()
            installedPackages.forEach {
                val appInfo = it.applicationInfo
                if (appInfo != null) {
                    installedApps.add(FirewallManager.AppInfoTuple(appInfo.uid, it.packageName))
                }
            }
            if (trackedApps.size != installedApps.size) {
                logEvent(Severity.LOW, "app refresh", "installed apps: ${installedApps.size}, tracked apps: ${trackedApps.size}, restore? ${action == ACTION_REFRESH_RESTORE}")
            }
            Logger.i(
                LOG_TAG_APP_DB,
                "installed apps: ${installedApps.size}, tracked apps: ${trackedApps.size}"
            )
            val packagesToAdd =
                findPackagesToAdd(trackedApps, installedApps)
            val probableTombstonePkgs =
                findPackagesToTombstone(trackedApps, installedApps, action != ACTION_REFRESH_RESTORE && canTombstone)
            val packagesToDelete =
                findPackagesToDelete(trackedApps, installedApps, action == ACTION_REFRESH_RESTORE || !canTombstone)
            // remove packages from delete list which are part of the probable tombstone
            val packagesToTombstone = probableTombstonePkgs.filter {
                !packagesToDelete.map { x -> x.packageName }.contains(it.packageName)
            }.toSet()
            val packagesToUpdate =
                findPackagesToUpdate(trackedApps, installedApps, action == ACTION_REFRESH_RESTORE)

            printAll(packagesToAdd, "packagesToAdd")
            printAll(packagesToTombstone, "packagesToTombstone")
            printAll(packagesToDelete, "packagesToDelete")
            printAll(packagesToUpdate, "packagesToUpdate")

            logEvent(Severity.LOW, "app refresh details", "sizes: rmv: ${packagesToDelete.size}; add: ${packagesToAdd.size}; update: ${packagesToUpdate.size}, tombstone: ${packagesToTombstone.size}, action: $action, tombstoneEnabled? $canTombstone")
            Logger.i(
                LOG_TAG_APP_DB,
                "sizes: rmv: ${packagesToDelete.size}; add: ${packagesToAdd.size}; update: ${packagesToUpdate.size}, tombstone: ${packagesToTombstone.size}, action: $action, tombstoneEnabled? $canTombstone"
            )
            tombstonePackages(packagesToTombstone, action == ACTION_REFRESH_RESTORE || !canTombstone)
            deletePackages(packagesToDelete, action == ACTION_REFRESH_RESTORE)
            addMissingPackages(packagesToAdd)
            updateExistingPackagesIfNeeded(packagesToUpdate) // updated only for restore
            refreshNonApps(trackedApps, installedApps)
            // must be called after updateExistingPackagesIfNeeded
            // packages to add and delete are calculated based on proxy mapping
            refreshProxyMapping(trackedApps, packagesToAdd, packagesToUpdate, packagesToTombstone, packagesToDelete, action == ACTION_REFRESH_RESTORE)
            // must be called after updateExistingPackagesIfNeeded
            refreshIPRules(packagesToUpdate)
            // must be called after updateExistingPackagesIfNeeded
            refreshDomainRules(packagesToUpdate)
            restoreWireGuardProfilesIfNeeded(action == ACTION_REFRESH_RESTORE)
            Logger.i(LOG_TAG_APP_DB, "refresh done")
        } catch (e: RuntimeException) {
            Logger.crash(LOG_TAG_APP_DB, e.message ?: "refresh err", e)
            throw e
        } finally {
            notifyEmptyFirewallRulesIfNeeded()
            a.cb()
        }
    }

    private fun findPackagesToAdd(
        old: Set<FirewallManager.AppInfoTuple>,
        latest: Set<FirewallManager.AppInfoTuple>
    ): Set<FirewallManager.AppInfoTuple> {
        val oldpkgs = old.map { it.packageName }.toSet()
        return latest
            .filter { !oldpkgs.contains(it.packageName) }
            .toHashSet()
    }

    private suspend fun findPackagesToTombstone(
        old: Set<FirewallManager.AppInfoTuple>,
        latest: Set<FirewallManager.AppInfoTuple>,
        canTombstone: Boolean
    ): Set<FirewallManager.AppInfoTuple> {
        if (!canTombstone) return emptySet()
        // find packages that are in old but not in latest, and are not non-apps
        val latestPkgs = latest.map { it.packageName }.toSet()
        val tombstonePkgs = old.filter { !latestPkgs.contains(it.packageName) && !isNonApp(it.packageName) }
            .toSet() // find old package names that do not appear in latest
        return tombstonePkgs.filter {
            val appInfo = FirewallManager.getAppInfoByPackage(it.packageName)
            // cases where some apps tombstoned already but uid is positive
            (appInfo != null && appInfo.tombstoneTs == 0L) || it.uid > 0
        }.toSet() // filter out already tombstone packages
    }

    private suspend fun findPackagesToDelete(old: Set<FirewallManager.AppInfoTuple>, latest: Set<FirewallManager.AppInfoTuple>, restoreOrNoTombstone: Boolean): Set<FirewallManager.AppInfoTuple> {
        val latestPkgs = latest.map { it.packageName }.toSet()
        if (restoreOrNoTombstone) {
            return old.filter { !latestPkgs.contains(it.packageName) && !isNonApp(it.packageName) }.toSet()
        }

        // find packages which have elapsed the tombstone expiry time
        val currentTime = System.currentTimeMillis()
        val tombstonePkgs = old.filter { !latestPkgs.contains(it.packageName) && !isNonApp(it.packageName) }.toSet()
        return tombstonePkgs.filter {
            val appInfo = FirewallManager.getAppInfoByPackage(it.packageName)
            appInfo != null && appInfo.tombstoneTs > 0L &&
                currentTime - appInfo.tombstoneTs > TOMBSTONE_EXPIRY_TIME_MS
        }.toSet() // find old package names that do not appear in latest
    }

    private fun findPackagesToUpdate(
        old: Set<FirewallManager.AppInfoTuple>,
        latest: Set<FirewallManager.AppInfoTuple>,
        ignoreUid: Boolean
    ): Set<FirewallManager.AppInfoTuple> {
        return if (ignoreUid) {
            val latestPkgs = latest.map { it.packageName }.toSet()
            old.filter { latestPkgs.contains(it.packageName) }
                .toSet() // find old package names that appear in latest
        } else {
            // Sets.intersection(old, latest); need not update apps already tracked
            old.filter { x ->
                latest.any { y -> y.packageName == x.packageName && y.uid != x.uid }
            }.toSet() // find old package names that appear in latest with different uid
        }
    }

    private suspend fun tombstonePackages(
        packagesToTombstone: Set<FirewallManager.AppInfoTuple>,
        skipTombstone: Boolean
    ) {
        if (skipTombstone) {
            // if restore, then no need to tombstone the app, delete should take care of it
            Logger.i(LOG_TAG_APP_DB, "tombstonePackages: skip tombstone, no-op")
            return
        }
        // if not restore then mark the app as tombstone
        val currentTime = System.currentTimeMillis()
        packagesToTombstone.forEach {
            val appInfo = FirewallManager.getAppInfoByPackage(it.packageName)
            Logger.d(LOG_TAG_APP_DB, "tombstone app: $it, tombstone: ${appInfo?.tombstoneTs}, restore: $skipTombstone, current: $currentTime, diff: ${currentTime - (appInfo?.tombstoneTs ?: 0L)}")
            if (appInfo != null) {
                if (appInfo.tombstoneTs > 0L && appInfo.uid < 0) {
                    // should not be the case as we filter out the tombstone packages
                    Logger.w(LOG_TAG_APP_DB, "tombstone app: ${it.packageName}, uid: ${it.uid}, ts: ${appInfo.tombstoneTs} is already tombstoned, no-op")
                    return@forEach
                }
                // mark the app as tombstone, only appInfo will be updated with tombstone ts
                // all other rules will be updated with new uid (-1 * uid)
                IpRulesManager.tombstoneRulesByUid(it.uid)
                DomainRulesManager.tombstoneRulesByUid(it.uid)
                ProxyManager.tombstoneApp(it.uid)
                FirewallManager.tombstoneApp(it.uid, it.packageName, currentTime)
                Logger.i(
                    LOG_TAG_APP_DB,
                    "tombstone app: ${it.packageName}, uid: ${it.uid}, ts: ${appInfo.tombstoneTs}"
                )
            }
        }
    }

    private suspend fun deletePackages(packagesToDelete: Set<FirewallManager.AppInfoTuple>, restore: Boolean) {
        // decide whether the app need to be deleted or just need to be tombstoned
        // if tombstone then just update the tombstoneTs to current time and return
        // in case if the app is already tombstoned and the tombstoneTs is expired then delete it
        // in case of restore no need to look for tombstoneTs expiry, just delete the app
        val currentTime = System.currentTimeMillis()
        packagesToDelete.forEach {
            val appInfo = FirewallManager.getAppInfoByPackage(it.packageName)
            Logger.d(LOG_TAG_APP_DB, "delete app: $it, tombstone: ${appInfo?.tombstoneTs}, restore: $restore, current: $currentTime, diff: ${currentTime - (appInfo?.tombstoneTs ?: 0L)}")
            if (appInfo != null) {
                // delete the app from the database only if tombstone expiry time has elapsed
                // or if restore is true or tombstone is disabled
                val canDelete = !persistentState.tombstoneApps || restore || (appInfo.tombstoneTs > 0L && currentTime - appInfo.tombstoneTs > TOMBSTONE_EXPIRY_TIME_MS)
                if (canDelete) {
                    // remove all the rules related to the packages
                    IpRulesManager.deleteRulesByUid(it.uid)
                    DomainRulesManager.deleteRulesByUid(it.uid)
                    ProxyManager.deleteApp(it.uid, it.packageName)
                    deletePackage(it.uid, it.packageName)
                    Logger.i(
                        LOG_TAG_APP_DB,
                        "delete app: ${it.packageName}, uid: ${it.uid}, ts: ${appInfo.tombstoneTs}"
                    )
                } else {
                    // should not be the case as we filter out the tombstone packages
                    // no-op, package is already tombstone, will be deleted later
                    Logger.w(LOG_TAG_APP_DB, "delete app: ${it.packageName}, uid: ${it.uid}, ts: ${appInfo.tombstoneTs} is not expired, no-op")
                }
            }
        }
    }

    private suspend fun refreshNonApps(
        trackedApps: Set<FirewallManager.AppInfoTuple>,
        installedApps: Set<FirewallManager.AppInfoTuple>
    ) {
        // if a non-app appears installed-apps group, then upsert its db entry
        // and give it a proper identity as retrieved from the package-manager
        val nonApps = trackedApps.filter { isNonApp(it.packageName) }.map { it.uid }.toSet()
        installedApps.forEach { x ->
            if (nonApps.contains(x.uid)) {
                val prevPackageName =
                    trackedApps.filter { i -> i.uid == x.uid }.map { it.packageName }
                upsertNonApp(x, prevPackageName.firstOrNull())
            }
        }
    }

    private suspend fun upsertNonApp(
        appTuple: FirewallManager.AppInfoTuple,
        prevPackageName: String?
    ) {
        val appInfo = fetchApplicationInfo(appTuple.uid) ?: return
        // TODO: implement upsert logic handling all the edge cases
        deletePackage(appTuple.uid, prevPackageName)
        insertApp(appInfo)
    }

    private suspend fun addMissingPackages(apps: Set<FirewallManager.AppInfoTuple>) {
        if (apps.isEmpty()) return

        apps.forEach {
            // no need to avoid adding Rethink app to the database, so commenting the below line
            // if (it.packageName == context.applicationContext.packageName) return@forEach
            val ai = Utilities.getApplicationInfo(ctx, it.packageName) ?: return@forEach
            insertApp(ai)
        }
        maybeSendNewAppNotification(apps)
    }

    private suspend fun updateExistingPackagesIfNeeded(apps: Set<FirewallManager.AppInfoTuple>) {
        if (apps.isEmpty()) return

        apps.forEach { old ->
            // get the latest app info from package manager against existing package name
            val newInfo = Utilities.getApplicationInfo(ctx, old.packageName) ?: return@forEach
            updateApp(old.uid, newInfo.uid, old.packageName)
            // updating the ip/domain/proxy rules with the new uid are handled in caller methods
        }
    }

    private suspend fun refreshIPRules(apps: Set<FirewallManager.AppInfoTuple>) {
        if (apps.isEmpty()) return
        val oldUids = mutableListOf<Int>()
        val newUids = mutableListOf<Int>()
        apps.forEach { old ->
            // FirewallManager must have been updated by now, so we can get the latest app info
            // using the package-name (as uid have changed)
            val newInfo = FirewallManager.getAppInfoByPackage(old.packageName) ?: return@forEach
            if (newInfo.uid == old.uid) return@forEach
            oldUids.add(old.uid)
            newUids.add(newInfo.uid)
        }
        IpRulesManager.updateUids(oldUids, newUids)
    }

    private suspend fun refreshDomainRules(apps: Set<FirewallManager.AppInfoTuple>) {
        if (apps.isEmpty()) return
        val oldUids = mutableListOf<Int>()
        val newUids = mutableListOf<Int>()
        apps.forEach { old ->
            // FirewallManager must have been updated by now, so we can get the latest app info
            // using the package-name (as uid have changed)
            val newInfo = FirewallManager.getAppInfoByPackage(old.packageName) ?: return@forEach
            if (newInfo.uid == old.uid) return@forEach
            oldUids.add(old.uid)
            newUids.add(newInfo.uid)
        }
        DomainRulesManager.updateUids(oldUids, newUids)
    }

    private suspend fun maybeInsertApp(uid: Int) {
        val knownUid = FirewallManager.hasUid(uid) // this will skip tombstone apps
        if (knownUid) {
            Logger.i(LOG_TAG_APP_DB, "insertApp: $uid already tracked")
            return
        }
        val ai = maybeFetchAppInfo(uid)
        val pkg = ai?.packageName ?: ""
        val tombstone = FirewallManager.isTombstone(pkg)
        Logger.i(LOG_TAG_APP_DB, "insert app; uid: $uid, pkg: $pkg, tombstone? $tombstone")
        if (tombstone) {
            val oldUid = FirewallManager.getAppInfoByPackage(pkg)?.uid
            if (oldUid == null) {
                Logger.e(LOG_TAG_APP_DB, "insertApp: $uid is tombstone, but oldUid is null")
                return
            }
            if (ai == null) { // this should not happen as tombstone will not deal with non-apps
                // if the app is tombstone, but the app info is not available, maybe non-app
                // reset the tombstone timestamp
                val newUid = if (oldUid < INVALID_UID) { // negative for tombstoned apps
                    // if the oldUid is invalid, then use the current uid
                    uid
                } else {
                    oldUid
                }
                FirewallManager.updateUidAndResetTombstone(oldUid, newUid, pkg)
                logEvent(Severity.MEDIUM, "tombstone app", "reset tombstone for non-app $pkg, uid: $newUid")
                Logger.i(LOG_TAG_APP_DB, "insertApp: $uid is tombstoned, but app info is null (non-app)")
                return
            }
            // if the app is markes as tombstone, then reset the tombstone timestamp
            // and return, so that the app is not added again
            FirewallManager.updateUidAndResetTombstone(oldUid, ai.uid, pkg)
            IpRulesManager.updateUids(listOf(oldUid), listOf(ai.uid))
            DomainRulesManager.updateUids(listOf(oldUid), listOf(ai.uid))
            ProxyManager.updateApp(ai.uid, ai.packageName)
            Logger.i(LOG_TAG_APP_DB, "insertApp: $uid is tombstone ($oldUid), reset ts")
            return
        }
        if (ai != null) {
            // uid may be different from the one in ai, if the app is installed in a different user
            insertApp(ai)
            logEvent(Severity.LOW, "new app installed", "inserted app ${ai.packageName}, uid: ${ai.uid}")
        } else {
            insertUnknownApp(uid)
            logEvent(Severity.MEDIUM, "new unknown app installed", "inserted unknown app, uid: $uid")
        }
        showNewAppNotificationIfNeeded(FirewallManager.AppInfoTuple(uid, pkg))
    }

    private fun maybeFetchAppInfo(uid: Int): ApplicationInfo? {
        if (!AndroidUidConfig.isUidAppRange(uid)) return null

        return fetchApplicationInfo(uid)
    }

    private fun fetchApplicationInfo(uid: Int): ApplicationInfo? {
        val pkgs = Utilities.getPackageInfoForUid(ctx, uid)
        // return the first appinfo for a given uid (there could be multiple)
        pkgs?.forEach {
            return Utilities.getApplicationInfo(ctx, it) ?: return@forEach
        }
        return null
    }

    private suspend fun restoreWireGuardProfilesIfNeeded(restore: Boolean) {
        if (restore) {
            WireguardManager.restoreProcessRetrieveWireGuardConfigs()
        } else {
            Logger.d(LOG_TAG_APP_DB, "not restore, no-op for WireGuard profiles")
        }
    }

    private suspend fun refreshProxyMapping(
        trackedApps: Set<FirewallManager.AppInfoTuple>,
        packageToAdd: Set<FirewallManager.AppInfoTuple>,
        packagesToUpdate: Set<FirewallManager.AppInfoTuple>,
        packagesToTombstone: Set<FirewallManager.AppInfoTuple>,
        packagesToDelete: Set<FirewallManager.AppInfoTuple>,
        restore: Boolean = false
    ) {
        // trackedApps is empty, the installed apps are yet to be added to the database; and so,
        // there's no need to refresh these mappings as apps tracked by FirewallManager is empty
        if (trackedApps.isEmpty()) {
            Logger.i(LOG_TAG_APP_DB, "refreshProxyMapping: trackedApps is empty")
            return
        }

        ProxyManager.purgeDupsBeforeRefresh()
        val canTombstone = persistentState.tombstoneApps
        // remove the apps from proxy mapping which are not tracked by app info repository
        // this will just sync the proxy mapping with the app info repository
        val pxm = ProxyManager.trackedApps()
        val tombstoneApps = findPackagesToTombstone(pxm, trackedApps, !restore && canTombstone)
        // apps which are tombstone, but not yet deleted will be deleted now
        val del = findPackagesToDelete(pxm, trackedApps, restore || !canTombstone)
        val update = findPackagesToUpdate(pxm, trackedApps, restore)
        val add =
            findPackagesToAdd(pxm, trackedApps).map {
                FirewallManager.getAppInfoByPackage(it.packageName)
            }
        printAll(pxm, "px: tracked apps")
        printAll(packageToAdd, "px: add apps")
        printAll(update, "px: update apps")
        printAll(tombstoneApps, "px: tombstone apps")
        printAll(del, "px: delete apps")

        ProxyManager.deleteApps(del)
        ProxyManager.addApps(add)
        ProxyManager.updateApps(update)

        // proceed to actual add/update/delete based on the package manager's installed apps
        packageToAdd.forEach {
            val appInfo = FirewallManager.getAppInfoByPackage(it.packageName)
            if (appInfo != null) {
                ProxyManager.addApp(appInfo)
            }
        }

        packagesToUpdate.forEach {
            ProxyManager.updateApp(it.uid, it.packageName)
        }

        packagesToTombstone.forEach {
            ProxyManager.tombstoneApp(it.uid)
        }

        packagesToDelete.forEach {
            if (restore) {
                // restore the app in proxy mapping
                ProxyManager.deleteApp(it.uid, it.packageName)
            } else {
                ProxyManager.deleteAppIfNeeded(it.uid, it.packageName)
            }

        }

        Logger.i(
            LOG_TAG_APP_DB,
            "refreshing proxy mapping, size: ${pxm.size}, trackedApps: ${trackedApps.size}"
        )
    }

    private suspend fun insertUnknownApp(uid: Int) {
        val androidUidConfig = AndroidUidConfig.fromFileSystemUid(uid)
        val newAppInfo = AppInfo(null)

        newAppInfo.appName =
            if (androidUidConfig.uid == Constants.INVALID_UID) {
                newAppInfo.isSystemApp = false
                ctx.getString(R.string.network_log_app_name_unnamed, uid.toString())
            } else {
                newAppInfo.isSystemApp = true
                androidUidConfig.name
            }
        newAppInfo.packageName = "$NO_PACKAGE_PREFIX$uid"
        newAppInfo.appCategory = ctx.getString(FirewallManager.CategoryConstants.NON_APP.nameResId)
        newAppInfo.uid = uid

        if (persistentState.getBlockNewlyInstalledApp()) {
            newAppInfo.firewallStatus = FirewallManager.FirewallStatus.NONE.id
            newAppInfo.connectionStatus = FirewallManager.ConnectionStatus.BOTH.id
        }

        FirewallManager.persistAppInfo(newAppInfo)
        ProxyManager.addNewApp(newAppInfo)
    }

    private suspend fun updateApp(oldUid: Int, newUid: Int, pkg: String) {
        Logger.i(LOG_TAG_APP_DB, "update app; oldUid: $oldUid, newUid: $newUid, pkg: $pkg")
        if (oldUid == newUid) {
            return
        }
        FirewallManager.updateUidAndResetTombstone(oldUid, newUid, pkg)
    }

    private suspend fun insertApp(ai: ApplicationInfo) {
        val appName: String = try {
            ctx.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            // fallback if base.apk is not accessible
            ctx.getString(R.string.network_log_app_name_unnamed, ai.uid.toString())
        }
        val isSystemApp = isSystemApp(ai)
        val entry = AppInfo(null)

        entry.appName = appName

        entry.packageName = ai.packageName
        // uid may be different from the one in ai, if the app is installed in a different user
        // see: fetchApplicationInfo()
        entry.uid = ai.uid
        entry.isSystemApp = isSystemApp

        // do not firewall app by default, if blockNewlyInstalledApp is set to false
        if (persistentState.getBlockNewlyInstalledApp()) {
            entry.firewallStatus = FirewallManager.FirewallStatus.NONE.id
            entry.connectionStatus = FirewallManager.ConnectionStatus.BOTH.id
        } else {
            entry.firewallStatus = FirewallManager.FirewallStatus.NONE.id
            entry.connectionStatus = FirewallManager.ConnectionStatus.ALLOW.id
        }

        entry.appCategory = determineAppCategory(ai)

        Logger.i(LOG_TAG_APP_DB, "insert app: $ai")
        FirewallManager.persistAppInfo(entry)
        ProxyManager.addNewApp(entry)
    }

    private suspend fun maybeSendNewAppNotification(apps: Set<FirewallManager.AppInfoTuple>) {
        // need not notify if "block newly installed apps" is off: insertApp() & insertUnkownApp()
        if (!persistentState.getBlockNewlyInstalledApp()) return

        val appCount = apps.count()
        // Show bulk notification when the app size is greater than NEW_APP_BULK_CHECK_COUNT(5)
        if (appCount > NOTIF_BATCH_NEW_APPS_THRESHOLD) {
            showNewAppsBulkNotificationIfNeeded(appCount)
            return
        }

        // show notification for particular app (less than NEW_APP_BULK_CHECK_COUNT)
        apps.forEach { showNewAppNotificationIfNeeded(it) }
    }

    private fun showNewAppsBulkNotificationIfNeeded(appSize: Int) {
        // no need to notify if the Universal setting is off
        if (!persistentState.getBlockNewlyInstalledApp()) return

        val notificationManager =
            ctx.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager
        Logger.d(LOG_TAG_VPN, "Number of new apps: $appSize, show notification")

        val intent = Intent(ctx, NotificationHandlerActivity::class.java)
        intent.putExtra(
            Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME,
            Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE
        )

        val pendingIntent =
            getActivityPendingIntent(ctx, intent, PendingIntent.FLAG_ONE_SHOT, mutable = false)

        var builder: NotificationCompat.Builder
        if (isAtleastO()) {
            val name: CharSequence = ctx.getString(R.string.notif_channel_firewall_alerts)
            val description = ctx.resources.getString(R.string.notif_channel_desc_firewall_alerts)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
        } else {
            builder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
        }

        val contentTitle: String = ctx.resources.getString(R.string.new_app_bulk_notification_title)
        val contentText: String =
            ctx.resources.getString(R.string.new_app_bulk_notification_content, appSize.toString())

        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(pendingIntent)
            .setContentText(contentText)

        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(ctx, UIUtils.getAccentColor(persistentState.theme))

        // Secret notifications are not shown on the lock screen.  No need for this app to show
        // there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // Cancel the notification after clicking.
        builder.setAutoCancel(true)

        notificationManager.notify(
            NOTIF_CHANNEL_ID_FIREWALL_ALERTS,
            randomNotifId.nextInt(100),
            builder.build()
        )
    }

    private suspend fun showNewAppNotificationIfNeeded(app: FirewallManager.AppInfoTuple) {
        // no need to notify if the Universal setting is off
        if (!persistentState.getBlockNewlyInstalledApp()) return

        // no need to notify if the vpn is not on
        @Suppress("DEPRECATION")
        if (!VpnController.isOn()) return

        var pkgName = app.packageName
        if (pkgName.isEmpty()) {
            pkgName = FirewallManager.getPackageNameByUid(app.uid) ?: ""
        }

        val appInfo = Utilities.getApplicationInfo(ctx, pkgName)
        val appName =
            if (appInfo == null) {
                app.uid
            } else {
                try {
                    ctx.packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    // fallback if base.apk is not accessible
                    ctx.getString(R.string.network_log_app_name_unnamed, appInfo.uid.toString())
                }
            }

        val notificationManager =
            ctx.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager
        Logger.d(LOG_TAG_VPN, "New app installed: $appName, show notification")

        val intent = Intent(ctx, NotificationHandlerActivity::class.java)
        intent.putExtra(
            Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME,
            Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE
        )
        intent.putExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, app.uid)

        val pendingIntent =
            getActivityPendingIntent(
                ctx,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )

        val builder: NotificationCompat.Builder
        if (isAtleastO()) {
            val name: CharSequence = ctx.getString(R.string.notif_channel_firewall_alerts)
            val description = ctx.resources.getString(R.string.notif_channel_desc_firewall_alerts)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
        } else {
            builder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
        }

        val contentTitle: String = ctx.resources.getString(R.string.lbl_action_required)
        val contentText: String =
            ctx.resources.getString(R.string.new_app_notification_content, appName)

        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(pendingIntent)
            .setContentText(contentText)

        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(ctx, UIUtils.getAccentColor(persistentState.theme))

        val openIntent1 =
            makeNewAppVpnIntent(
                ctx,
                Constants.NOTIF_ACTION_NEW_APP_ALLOW,
                app.uid,
                PENDING_INTENT_REQUEST_CODE_ALLOW
            )

        val openIntent2 =
            makeNewAppVpnIntent(
                ctx,
                Constants.NOTIF_ACTION_NEW_APP_DENY,
                app.uid,
                PENDING_INTENT_REQUEST_CODE_DENY
            )
        val notificationAction: NotificationCompat.Action =
            NotificationCompat.Action(
                0,
                ctx.resources.getString(R.string.allow).uppercase(),
                openIntent1
            )
        val notificationAction2: NotificationCompat.Action =
            NotificationCompat.Action(
                0,
                ctx.resources.getString(R.string.new_app_notification_action_deny).uppercase(),
                openIntent2
            )
        builder.addAction(notificationAction)
        builder.addAction(notificationAction2)

        // API >= 21 only
        builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        builder.setAutoCancel(true)

        notificationManager.notify(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, app.uid, builder.build())
    }

    private suspend fun notifyEmptyFirewallRulesIfNeeded() {
        val trackedApps = FirewallManager.getAllApps()
        if (trackedApps.isNotEmpty()) {
            return
        }

        val intent = Intent(ctx, AppLockActivity::class.java)
        val nm = ctx.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent =
            getActivityPendingIntent(
                ctx,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                mutable = false
            )
        if (isAtleastO()) {
            val name: CharSequence = ctx.getString(R.string.notif_channel_firewall_alerts)
            val description = ctx.resources.getString(R.string.notif_channel_desc_firewall_alerts)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, name, importance)
            channel.description = description
            nm.createNotificationChannel(channel)
        }
        var builder: NotificationCompat.Builder =
            NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)

        val contentTitle = ctx.resources.getString(R.string.rules_load_failure_heading)
        val contentText = ctx.resources.getString(R.string.rules_load_failure_desc)
        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(pendingIntent)
            .setContentText(contentText)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(ctx, UIUtils.getAccentColor(persistentState.theme))
        val openIntent =
            makeVpnIntent(NOTIF_ID_LOAD_RULES_FAIL, Constants.NOTIF_ACTION_RULES_FAILURE)
        val notificationAction: NotificationCompat.Action =
            NotificationCompat.Action(
                0,
                ctx.resources.getString(R.string.rules_load_failure_reload),
                openIntent
            )
        builder.addAction(notificationAction)

        // Secret notifications are not shown on the lock screen.  No need for this app to show
        // there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        builder.build()
        nm.notify(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, NOTIF_ID_LOAD_RULES_FAIL, builder.build())
    }
    // keep in sync with BraveVPNServie#makeVpnIntent
    private fun makeVpnIntent(id: Int, extra: String): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java)
        intent.putExtra(Constants.NOTIFICATION_ACTION, extra)
        return Utilities.getBroadcastPendingIntent(
            ctx,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            mutable = false
        )
    }

    private fun makeNewAppVpnIntent(
        context: Context,
        intentExtra: String,
        uid: Int,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
        intent.putExtra(Constants.NOTIFICATION_ACTION, intentExtra)
        intent.putExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, uid)

        return Utilities.getBroadcastPendingIntent(
            context,
            (uid or requestCode),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            mutable = false
        )
    }

    private fun isSystemApp(ai: ApplicationInfo): Boolean {
        return (ai.flags and ApplicationInfo.FLAG_SYSTEM > 0)
    }

    private fun isSystemComponent(ai: ApplicationInfo): Boolean {
        return isSystemApp(ai) && !AndroidUidConfig.isUidAppRange(ai.uid)
    }

    private fun determineAppCategory(ai: ApplicationInfo): String {
        // fetchCategory returns  category from an unofficial PlayStore endpoint.
        // disabled for now until there's clarity on its legality
        // val category = fetchCategory(appInfo.packageInfo)
        val cat = fetchCategory()

        if (!PlayStoreCategory.OTHER.name.equals(cat, ignoreCase = true)) {
            return replaceUnderscore(cat)
        }

        if (isSystemComponent(ai)) {
            return ctx.getString(FirewallManager.CategoryConstants.SYSTEM_COMPONENT.nameResId)
        }

        if (isSystemApp(ai)) {
            return ctx.getString(FirewallManager.CategoryConstants.SYSTEM_APP.nameResId)
        }

        if (isAtleastO()) {
            return replaceUnderscore(appInfoCategory(ai))
        }

        return ctx.getString(FirewallManager.CategoryConstants.INSTALLED.nameResId)
    }

    suspend fun cleanupTombstone() {
        // delete all the tombstoned apps which are older than current time
        val tombstoneApps = FirewallManager.getTombstoneApps()
        tombstoneApps.forEach { appInfo ->
            // remove all the rules
            IpRulesManager.deleteRulesByUid(appInfo.uid)
            DomainRulesManager.deleteRulesByUid(appInfo.uid)
            ProxyManager.deleteAppByPkgName(appInfo.packageName)
            deletePackage(appInfo.uid, appInfo.packageName)
            Logger.i(
                LOG_TAG_APP_DB,
                "cleanupTombstone: deleted app: ${appInfo.packageName}, uid: ${appInfo.uid}, ts: ${appInfo.tombstoneTs}"
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun appInfoCategory(ai: ApplicationInfo): String {
        val cat = ApplicationInfo.getCategoryTitle(ctx, ai.category)
        return cat?.toString() ?: ctx.getString(FirewallManager.CategoryConstants.OTHER.nameResId)
    }

    private fun replaceUnderscore(s: String): String {
        return s.replace("_", " ")
    }

    suspend fun purgeConnectionLogs(date: Long) {
        // purge logs older than specified date
        dnsLogRepository.purgeDnsLogsByDate(date)
        connTrackerRepository.purgeLogsByDate(date)
        rethinkLogRepository.purgeLogsByDate(date)
    }

    private suspend fun logEvent(severity: Severity, msg: String, details: String) {
        eventLogger.log(EventType.APP_REFRESH, severity, msg, EventSource.MANAGER, false, details)
    }

    private fun printAll(c: Collection<FirewallManager.AppInfoTuple>, tag: String) {
        c.forEach { Logger.i(LOG_TAG_APP_DB, "$tag: ${it.uid}, ${it.packageName}") }
    }

    /** Below code to fetch the google play service-application category Not in use as of now. */
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

    /*
    private val CAT_SIZE = 9
    private val CATEGORY_STRING = "category/"
    private val CATEGORY_GAME_STRING = "GAME_" // All games start with this prefix

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
    }*/

    private fun io(s: String, f: suspend () -> Unit) =
        CoroutineScope(CoroutineName(s) + Dispatchers.IO).launch { f() }
}
