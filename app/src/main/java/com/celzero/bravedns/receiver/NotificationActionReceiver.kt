/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.receiver

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.OrbotHelper.Companion.NOTIF_CHANNEL_ID_PROXY_ALERTS
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val rdb by inject<RefreshDatabase>()
    private val persistentState by inject<PersistentState>()

    private val appScope by inject<CoroutineScope>()

    override fun onReceive(context: Context, intent: Intent) {
        // TODO - Move the NOTIFICATION_ACTIONs value to enum
        val action: String? = intent.getStringExtra(Constants.NOTIFICATION_ACTION)
        Logger.i(LOG_TAG_VPN, "received notification action: $action")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (action) {
            OrbotHelper.ORBOT_NOTIFICATION_ACTION_TEXT -> handleOrbot(manager)
            Constants.NOTIF_ACTION_PAUSE_VPN -> pauseApp(context)
            Constants.NOTIF_ACTION_RESUME_VPN -> resumeApp()
            Constants.NOTIF_ACTION_STOP_VPN -> stopVpn(context)
            Constants.NOTIF_ACTION_DNS_VPN -> dnsMode(context)
            Constants.NOTIF_ACTION_DNS_FIREWALL_VPN -> dnsFirewallMode()
            Constants.NOTIF_ACTION_RULES_FAILURE -> refreshDatabase()
            Constants.NOTIF_ACTION_NEW_APP_ALLOW -> handleNewAppFirewallAction(
                context, intent, manager, FirewallManager.ConnectionStatus.ALLOW, "allow"
            )
            Constants.NOTIF_ACTION_NEW_APP_DENY -> handleNewAppFirewallAction(
                context, intent, manager, FirewallManager.ConnectionStatus.BOTH, "deny"
            )
            Constants.NOTIF_ACTION_DB_CORRUPTED_CLEAR -> clearCorruptedDatabase(manager)
            Constants.NOTIF_ACTION_DB_CORRUPTED_DISMISS -> dismissCorruptedDatabase(manager)
            Constants.NOTIF_ACTION_RETHINK_BLOCK_DISMISS -> dismissRethinkBlock(manager)
        }
    }

    /**
     * Runs [work] as background work for this broadcast via goAsync(): the
     * receiver stays "alive" (and the broadcast is not yet considered finished)
     * until PendingResult.finish() is called, extending the broadcast window to
     * roughly ten seconds.
     */
    private fun io(work: suspend () -> Unit) {
        val pendingResult: BroadcastReceiver.PendingResult = goAsync()
        appScope.launch {
            try {
                work()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleOrbot(manager: NotificationManager) {
        orbotHelper.openOrbotApp()
        manager.cancel(NOTIF_CHANNEL_ID_PROXY_ALERTS, OrbotHelper.ORBOT_SERVICE_ID)
    }

    private fun pauseApp(context: Context) {
        if (!VpnController.hasTunnel()) {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.hsf_pause_vpn_failure),
                Toast.LENGTH_SHORT
            )
            return
        }

        VpnController.pauseApp()
    }

    private fun resumeApp() {
        VpnController.resumeApp()
    }

    private fun stopVpn(context: Context) {
        VpnController.stop("notif", context)
    }

    private fun dismissCorruptedDatabase(manager: NotificationManager) {
        manager.cancel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, RefreshDatabase.NOTIF_ID_DB_CORRUPTION)
    }

    private fun dismissRethinkBlock(manager: NotificationManager) {
        manager.cancel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, Constants.NOTIF_ID_RETHINK_BLOCK)
        persistentState.showRethinkBlockNotification = false
    }

    private fun dnsMode(context: Context) {
        if (appConfig.isProxyEnabled()) {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.settings_lock_down_proxy_desc),
                Toast.LENGTH_SHORT
            )
            return
        }
        io { appConfig.changeBraveMode(AppConfig.BraveMode.DNS.mode) }
    }

    private fun dnsFirewallMode() {
        if (appConfig.getBraveMode().isDnsFirewallMode()) return

        io { appConfig.changeBraveMode(AppConfig.BraveMode.DNS_FIREWALL.mode) }
    }

    private fun refreshDatabase() {
        io { rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE) }
    }

    private fun clearCorruptedDatabase(manager: NotificationManager) {
        manager.cancel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, RefreshDatabase.NOTIF_ID_DB_CORRUPTION)
        io { rdb.clearCoreTablesAndRebuild() }
    }

    private fun handleNewAppFirewallAction(
        context: Context,
        intent: Intent,
        manager: NotificationManager,
        connectionStatus: FirewallManager.ConnectionStatus,
        actionLabel: String
    ) {
        val uid = intent.getIntExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, Int.MIN_VALUE)
        if (uid < 0) {
            Logger.i(LOG_TAG_VPN, "Invalid uid: $uid, on new app $actionLabel, ignoring")
            return
        }

        manager.cancel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, uid)

        val text =
            if (connectionStatus == FirewallManager.ConnectionStatus.BOTH) {
                context.getString(R.string.new_app_notification_action_toast_deny)
            } else {
                context.getString(R.string.new_app_notification_action_toast_allow)
            }
        Utilities.showToastUiCentered(context, text, Toast.LENGTH_SHORT)

        io { FirewallManager.updateFirewalledApps(uid, connectionStatus) }
    }
}
