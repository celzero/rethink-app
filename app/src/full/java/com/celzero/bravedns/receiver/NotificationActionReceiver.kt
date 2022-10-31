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
package com.celzero.bravedns.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.OrbotHelper.Companion.NOTIF_CHANNEL_ID_PROXY_ALERTS
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()

    override fun onReceive(context: Context, intent: Intent) {
        // TODO - Move the NOTIFICATION_ACTIONs value to enum
        val action: String? = intent.getStringExtra(Constants.NOTIFICATION_ACTION)
        Log.i(LOG_TAG_VPN, "Received notification action: $action")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (action) {
            OrbotHelper.ORBOT_NOTIFICATION_ACTION_TEXT -> {
                orbotHelper.openOrbotApp()
                manager.cancel(NOTIF_CHANNEL_ID_PROXY_ALERTS, OrbotHelper.ORBOT_SERVICE_ID)
            }
            Constants.NOTIF_ACTION_PAUSE_VPN -> {
                pauseApp(context)
            }
            Constants.NOTIF_ACTION_RESUME_VPN -> {
                resumeApp()
            }
            Constants.NOTIF_ACTION_STOP_VPN -> {
                stopVpn(context)
            }
            Constants.NOTIF_ACTION_DNS_VPN -> {
                dnsMode()
            }
            Constants.NOTIF_ACTION_DNS_FIREWALL_VPN -> {
                dnsFirewallMode()
            }
            Constants.NOTIF_ACTION_RULES_FAILURE -> {
                reloadRules()
            }
            Constants.NOTIF_ACTION_NEW_APP_ALLOW -> {
                val uid = intent.getIntExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, Int.MIN_VALUE)
                if (uid < 0) return

                manager.cancel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, uid)

                modifyAppFirewallSettings(context, uid, FirewallManager.FirewallStatus.ALLOW)
            }
            Constants.NOTIF_ACTION_NEW_APP_DENY -> {
                val uid = intent.getIntExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, Int.MIN_VALUE)
                if (uid < 0) return

                manager.cancel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, uid)

                modifyAppFirewallSettings(context, uid, FirewallManager.FirewallStatus.BLOCK)
            }
        }
    }

    private fun reloadRules() {
        io {
            FirewallManager.loadAppFirewallRules()
        }
    }

    private fun stopVpn(context: Context) {
        VpnController.stop(context)
    }

    private fun pauseApp(context: Context) {
        if (!VpnController.hasTunnel()) {
            Utilities.showToastUiCentered(context,
                                          context.getString(R.string.hsf_pause_vpn_failure),
                                          Toast.LENGTH_SHORT)
            return
        }

        if (VpnController.isVpnLockdown()) {
            Utilities.showToastUiCentered(context,
                                          context.getString(R.string.hsf_pause_lockdown_failure),
                                          Toast.LENGTH_SHORT)
            return
        }

        VpnController.pauseApp()
    }

    private fun resumeApp() {
        VpnController.resumeApp()
    }

    private fun dnsMode() {
        io {
            appConfig.changeBraveMode(AppConfig.BraveMode.DNS.mode)
        }
    }

    private fun dnsFirewallMode() {
        io {
            appConfig.changeBraveMode(AppConfig.BraveMode.DNS_FIREWALL.mode)
        }
    }

    private fun modifyAppFirewallSettings(context: Context, uid: Int,
                                          firewallStatus: FirewallManager.FirewallStatus) {
        val text = if (firewallStatus == FirewallManager.FirewallStatus.BLOCK) {
            context.getString(R.string.new_app_notification_action_toast_deny)
        } else {
            context.getString(R.string.new_app_notification_action_toast_allow)
        }

        Utilities.showToastUiCentered(context, text, Toast.LENGTH_SHORT)
        FirewallManager.updateFirewalledApps(uid, firewallStatus)
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }
}
