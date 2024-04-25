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
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.FirewallManager.deletePackage
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.NotificationHandlerDialog
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.PlayStoreCategory
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getActivityPendingIntent
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isNonApp
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class RefreshDatabase
internal constructor(
    private var ctx: Context,
    private val connTrackerRepository: ConnectionTrackerRepository,
    private val dnsLogRepository: DnsLogRepository,
    private val persistentState: PersistentState
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

            val fm = FirewallManager.load()
            val ipm = IpRulesManager.load()
            val dm = DomainRulesManager.load()
            val pxm = ProxyManager.load()
            val wgm = WireguardManager.load()
            val tcpm = TcpProxyHelper.load()

            Logger.i(
                LOG_TAG_APP_DB,
                "reload: fm: ${fm}; ip: ${ipm}; dom: ${dm}; px: ${pxm}; wg: ${wgm}; t: ${tcpm}"
            )

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

            val installedApps =
                installedPackages
                    .map { FirewallManager.AppInfoTuple(it.applicationInfo.uid, it.packageName) }
                    .toSet()

            val packagesToAdd =
                findPackagesToAdd(trackedApps, installedApps, action == ACTION_REFRESH_RESTORE)
            val packagesToDelete =
                findPackagesToDelete(trackedApps, installedApps, action == ACTION_REFRESH_RESTORE)
            val packagesToUpdate =
                findPackagesToUpdate(trackedApps, installedApps, action == ACTION_REFRESH_RESTORE)

            printAll(packagesToAdd, "packagesToAdd")
            printAll(packagesToDelete, "packagesToDelete")
            printAll(packagesToUpdate, "packagesToUpdate")

            Logger.i(
                LOG_TAG_APP_DB,
                "sizes: rmv: ${packagesToDelete.size}; add: ${packagesToAdd.size}; update: ${packagesToUpdate.size}"
            )
            deletePackages(packagesToDelete)
            addMissingPackages(packagesToAdd)
            updateExistingPackagesIfNeeded(packagesToUpdate) // updated only for restore
            removeWireGuardProfilesIfNeeded(action == ACTION_REFRESH_RESTORE)
            refreshNonApps(trackedApps, installedApps)
            // for proxy mapping, restore is a special case, where we clear all proxy->app mappings
            // and so, packagesToUpdate, even if not empty, is ignored. proxy mappings cannot be
            // updated during restore.
            refreshProxyMapping(trackedApps, action == ACTION_REFRESH_RESTORE)
            // must be called after updateExistingPackagesIfNeeded
            refreshIPRules(packagesToUpdate)
            // must be called after updateExistingPackagesIfNeeded
            refreshDomainRules(packagesToUpdate)
        } catch (e: RuntimeException) {
            Logger.e(LOG_TAG_APP_DB, e.message ?: "refresh err", e)
            throw e
        } finally {
            notifyEmptyFirewallRulesIfNeeded()
            a.cb()
        }
    }

    private fun findPackagesToAdd(
        old: Set<FirewallManager.AppInfoTuple>,
        latest: Set<FirewallManager.AppInfoTuple>,
        ignoreUid: Boolean = false
    ): Set<FirewallManager.AppInfoTuple> {
        return if (ignoreUid) {
            val oldpkgs = old.map { it.packageName }.toSet()
            latest
                .filter { !oldpkgs.contains(it.packageName) }
                .toHashSet() // latest apps not found in old
        } else {
            latest.filter { !old.contains(it) }.toHashSet()
        }
    }

    private fun findPackagesToDelete(
        old: Set<FirewallManager.AppInfoTuple>,
        latest: Set<FirewallManager.AppInfoTuple>,
        ignoreUid: Boolean = false
    ): Set<FirewallManager.AppInfoTuple> {
        return if (ignoreUid) {
            val latestpkgs = latest.map { it.packageName }.toSet()
            old.filter { !latestpkgs.contains(it.packageName) && !isNonApp(it.packageName) }
                .toHashSet()
        } else {
            // extract old apps that are not latest
            old.filter { !latest.contains(it) && !isNonApp(it.packageName) }.toHashSet()
        }
    }

    private fun findPackagesToUpdate(
        old: Set<FirewallManager.AppInfoTuple>,
        latest: Set<FirewallManager.AppInfoTuple>,
        ignoreUid: Boolean = false
    ): Set<FirewallManager.AppInfoTuple> {
        return if (ignoreUid) {
            val latestpkgs = latest.map { it.packageName }.toSet()
            old.filter { latestpkgs.contains(it.packageName) }
                .toSet() // find old package names that appear in latest
        } else {
            // Sets.intersection(old, latest); need not update apps already tracked
            emptySet()
        }
    }

    private suspend fun deletePackages(packagesToDelete: Set<FirewallManager.AppInfoTuple>) {
        // remove all the rules related to the packages
        packagesToDelete.forEach {
            IpRulesManager.deleteRulesByUid(it.uid)
            DomainRulesManager.deleteRulesByUid(it.uid)
            ProxyManager.deleteAppMappingsByUid(it.uid)
        }
        FirewallManager.deletePackages(packagesToDelete)
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

    // TODO: Ideally this should be in FirewallManager
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
            val newinfo = Utilities.getApplicationInfo(ctx, old.packageName) ?: return@forEach
            updateApp(old.uid, newinfo.uid, old.packageName)
        }
    }

    private suspend fun refreshIPRules(apps: Set<FirewallManager.AppInfoTuple>) {
        if (apps.isEmpty()) return
        val oldUids = mutableListOf<Int>()
        val newUids = mutableListOf<Int>()
        apps.forEach { old ->
            // FirewallManager must have been udpated by now, so we can get the latest app info
            // using the package-name (as uid have changed)
            val newinfo = FirewallManager.getAppInfoByPackage(old.packageName) ?: return@forEach
            oldUids.add(old.uid)
            newUids.add(newinfo.uid)
        }
        IpRulesManager.updateUids(oldUids, newUids)
    }

    private suspend fun refreshDomainRules(apps: Set<FirewallManager.AppInfoTuple>) {
        if (apps.isEmpty()) return
        val oldUids = mutableListOf<Int>()
        val newUids = mutableListOf<Int>()
        apps.forEach { old ->
            // FirewallManager must have been udpated by now, so we can get the latest app info
            // using the package-name (as uid have changed)
            val newinfo = FirewallManager.getAppInfoByPackage(old.packageName) ?: return@forEach
            oldUids.add(old.uid)
            newUids.add(newinfo.uid)
        }
        DomainRulesManager.updateUids(oldUids, newUids)
    }

    private suspend fun maybeInsertApp(uid: Int) {
        val knownUid = FirewallManager.hasUid(uid)
        if (knownUid) {
            Logger.i(LOG_TAG_APP_DB, "insertApp: $uid already tracked")
            return
        }
        val ai = maybeFetchAppInfo(uid)
        val pkg = ai?.packageName ?: ""
        Logger.i(LOG_TAG_APP_DB, "insert app; uid: $uid, pkg: ${pkg}")
        if (ai != null) {
            // uid may be different from the one in ai, if the app is installed in a different user
            insertApp(ai)
        } else {
            insertUnknownApp(uid)
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

    private suspend fun removeWireGuardProfilesIfNeeded(rmv: Boolean) {
        // may already have been purged by RestoreAgent.startRestore() -> wireguardCleanup()
        if (rmv) {
            WireguardManager.restoreProcessDeleteWireGuardEntries()
        } else {
            Logger.d(LOG_TAG_APP_DB, "removeWireGuardProfilesIfNeeded: no-op")
        }
    }

    private suspend fun refreshProxyMapping(
        trackedApps: Set<FirewallManager.AppInfoTuple>,
        emptyAll: Boolean
    ) {
        // trackedApps is empty, the installed apps are yet to be added to the database; and so,
        // there's no need to refresh these mappings as apps tracked by FirewallManager is empty
        if (trackedApps.isEmpty()) {
            Logger.i(LOG_TAG_APP_DB, "refreshProxyMapping: trackedApps is empty")
            return
        }

        // remove all apps from proxy mapping and add the apps from tracked apps
        if (emptyAll) {
            ProxyManager.clear()
            trackedApps
                .map { FirewallManager.getAppInfoByPackage(it.packageName) }
                .forEach { ProxyManager.addNewApp(it) } // it may be null, esp for non-apps
            Logger.i(
                LOG_TAG_APP_DB,
                "empty proxy mapping, trackedApps: ${trackedApps.size}, proxy mapping: ${ProxyManager.trackedApps().size}"
            )
            return
        }

        ProxyManager.purgeDupsBeforeRefresh()

        // remove the apps from proxy mapping which are not tracked by app info repository
        val pxm = ProxyManager.trackedApps()
        val del = findPackagesToDelete(pxm, trackedApps)
        val add =
            findPackagesToAdd(pxm, trackedApps).map {
                FirewallManager.getAppInfoByPackage(it.packageName)
            }
        ProxyManager.deleteApps(del)
        ProxyManager.addApps(add)
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
        newAppInfo.packageName = "no_package_$uid"
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
        FirewallManager.updateUid(oldUid, newUid, pkg)
    }

    private suspend fun insertApp(ai: ApplicationInfo) {
        val appName = ctx.packageManager.getApplicationLabel(ai).toString()
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

        val intent = Intent(ctx, NotificationHandlerDialog::class.java)
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
                ctx.packageManager.getApplicationLabel(appInfo).toString()
            }

        val notificationManager =
            ctx.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager
        Logger.d(LOG_TAG_VPN, "New app installed: $appName, show notification")

        val intent = Intent(ctx, NotificationHandlerDialog::class.java)
        intent.putExtra(
            Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME,
            Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE
        )

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

        val intent = Intent(ctx, HomeScreenActivity::class.java)
        val nm = ctx.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                ctx,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
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
