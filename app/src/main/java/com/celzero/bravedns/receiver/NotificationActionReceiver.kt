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
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS_FIREWALL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.OrbotHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val appMode by inject<AppMode>()

    override fun onReceive(context: Context, intent: Intent) {
        val action: String? = intent.getStringExtra(Constants.NOTIFICATION_ACTION)
        Log.i(LOG_TAG_VPN, "NotificationActionReceiver: onReceive - $action")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (action) {
            OrbotHelper.ORBOT_NOTIFICATION_ACTION_TEXT -> {
                get<OrbotHelper>().openOrbotApp()
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

    private fun dnsMode() {
        appMode.changeBraveMode(APP_MODE_DNS)
    }

    private fun dnsFirewallMode() {
        appMode.changeBraveMode(APP_MODE_DNS_FIREWALL)
    }
}
