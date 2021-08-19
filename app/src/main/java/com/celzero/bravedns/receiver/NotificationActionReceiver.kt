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
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS_FIREWALL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val appMode by inject<AppMode>()
    private val orbotHelper by inject<OrbotHelper>()

    override fun onReceive(context: Context, intent: Intent) {
        val action: String? = intent.getStringExtra(Constants.NOTIFICATION_ACTION)
        Log.i(LOG_TAG_VPN, "NotificationActionReceiver: onReceive - $action")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (action) {
            OrbotHelper.ORBOT_NOTIFICATION_ACTION_TEXT -> {
                orbotHelper.openOrbotApp()
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
        }
        manager.cancel(OrbotHelper.ORBOT_SERVICE_ID)
    }

    private fun reloadRules() {
        CoroutineScope(Dispatchers.IO).launch {
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

        if (Utilities.isVpnLockdownEnabled(VpnController.getBraveVpnService())) {
            Utilities.showToastUiCentered(context,
                                          context.getString(R.string.hsf_pause_lockdown_failure),
                                          Toast.LENGTH_SHORT)
            return
        }

        appMode.setAppState(AppMode.AppState.PAUSED)
    }

    private fun resumeApp() {
        appMode.setAppState(AppMode.AppState.ACTIVE)
    }

    private fun dnsMode() {
        CoroutineScope(Dispatchers.IO).launch {
            appMode.changeBraveMode(APP_MODE_DNS)
        }
    }

    private fun dnsFirewallMode() {
        CoroutineScope(Dispatchers.IO).launch {
            appMode.changeBraveMode(APP_MODE_DNS_FIREWALL)
        }
    }
}
