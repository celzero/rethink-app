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
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS_FIREWALL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val persistentState by inject<PersistentState>()

    override fun onReceive(context: Context, intent: Intent) {
        val action: String? = intent.getStringExtra(Constants.NOTIFICATION_ACTION)
        Log.i(LOG_TAG_VPN, "NotificationActionReceiver: onReceive - $action")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (action) {
            OrbotHelper.ORBOT_NOTIFICATION_ACTION_TEXT -> {
                openOrbotApp(context)
                manager.cancel(OrbotHelper.ORBOT_SERVICE_ID)
            }
            Constants.NOTIF_ACTION_STOP_VPN -> {
                stopVpn(context)
                manager.cancel(BraveVPNService.SERVICE_ID)
            }
            Constants.NOTIF_ACTION_DNS_VPN -> {
                dnsMode()
                manager.cancel(BraveVPNService.SERVICE_ID)
            }
            Constants.NOTIF_ACTION_DNS_FIREWALL_VPN -> {
                dnsFirewallMode()
                manager.cancel(BraveVPNService.SERVICE_ID)
            }
            Constants.NOTIF_ACTION_RULES_FAILURE -> {
                reloadRules()
                manager.cancel(BraveVPNService.SERVICE_ID)
            }
        }
    }

    private fun reloadRules() {
        runBlocking {
            withContext(Dispatchers.IO) {
                VpnController.getInstance().getBraveVpnService()?.loadAppFirewallRules()
            }
        }
    }

    private fun openOrbotApp(context: Context) {
        val packageName = OrbotHelper.ORBOT_PACKAGE_NAME
        Log.i(LOG_TAG_VPN, "appInfoForPackage: $packageName")
        try {
            val launchIntent: Intent? = context.packageManager?.getLaunchIntentForPackage(
                packageName)
            if (launchIntent != null) {//null pointer check in case package name was not found
                context.startActivity(launchIntent)
            } else {
                val text = context.getString(R.string.orbot_app_issue)
                Utilities.showToastUiCentered(context, text, Toast.LENGTH_SHORT)
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                context.startActivity(intent)
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG_VPN, "Exception while opening app info: ${e.message}", e)
        }
    }

    private fun stopVpn(context: Context?) {
        VpnController.getInstance().stop(context)
    }

    private fun dnsMode() {
        persistentState.setBraveMode(APP_MODE_DNS)
    }

    private fun dnsFirewallMode() {
        persistentState.setBraveMode(APP_MODE_DNS_FIREWALL)
    }
}
