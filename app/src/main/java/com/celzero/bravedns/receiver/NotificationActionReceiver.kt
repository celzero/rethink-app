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
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val persistentState by inject<PersistentState>()

    override fun onReceive(context: Context?, intent: Intent?) {
        val action: String? = intent?.getStringExtra(Constants.NOTIFICATION_ACTION)
        Log.i(Constants.LOG_TAG, "NotificationActionReceiver: onReceive - $action")
        val manager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when(action){
            OrbotHelper.ORBOT_NOTIFICATION_ACTION_TEXT -> {
                openOrbotApp(context)
                manager.cancel(OrbotHelper.ORBOT_SERVICE_ID)
            }
            Constants.STOP_VPN_NOTIFICATION_ACTION -> {
                stopVpn(context)
                manager.cancel(BraveVPNService.SERVICE_ID)
            }
            Constants.DNS_VPN_NOTIFICATION_ACTION -> {
                dnsMode()
                manager.cancel(BraveVPNService.SERVICE_ID)
            }
            Constants.DNS_FIREWALL_VPN_NOTIFICATION_ACTION -> {
                dnsFirewallMode()
                manager.cancel(BraveVPNService.SERVICE_ID)
            }
        }
    }

    private fun openOrbotApp(context: Context?) {
        val packageName = OrbotHelper.ORBOT_PACKAGE_NAME
        Log.i(Constants.LOG_TAG, "appInfoForPackage: $packageName")
        try {
            val launchIntent: Intent? = context?.packageManager?.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {//null pointer check in case package name was not found
                Log.d(Constants.LOG_TAG, "launchIntent: $packageName")
                context.startActivity(launchIntent)
            } else {
                val text = context?.getString(R.string.orbot_app_issue)
                if(text != null) {
                    Utilities.showToastInMidLayout(context, text, Toast.LENGTH_SHORT)
                }
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                context?.startActivity(intent)
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(Constants.LOG_TAG, "Exception while opening app info: ${e.message}", e)
        }
    }

    private fun stopVpn(context: Context?) {
        Log.i(Constants.LOG_TAG, "NotificationAction: Stopping the VPN")
        VpnController.getInstance().stop(context)
    }

    private fun dnsMode(){
        Log.i(Constants.LOG_TAG, "NotificationAction: Setting global state to DNS Mode")
        persistentState.setBraveMode(HomeScreenFragment.DNS_MODE)
    }

    private fun dnsFirewallMode(){
        Log.i(Constants.LOG_TAG, "NotificationActionReceiver: Setting global state to DNS + Firewall Mode")
        persistentState.setBraveMode(HomeScreenFragment.DNS_FIREWALL_MODE)
    }
}
