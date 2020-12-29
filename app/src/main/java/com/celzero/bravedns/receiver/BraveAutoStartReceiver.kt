/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.text.TextUtils
import android.util.Log
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG

class BraveAutoStartReceiver  : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val alwaysOnPackage = android.provider.Settings.Secure.getString(context?.contentResolver, "always_on_vpn_app")
        var isAlwaysOnEnabled = false
        if (!TextUtils.isEmpty(alwaysOnPackage)) {
            if (context?.packageName == alwaysOnPackage) {
                isAlwaysOnEnabled = true
            }
        }
        if (intent!!.action.equals(Intent.ACTION_BOOT_COMPLETED) || intent.action.equals(Intent.ACTION_REBOOT)) {
            if (ReceiverHelper.persistentState.prefAutoStartBootUp && ReceiverHelper.persistentState.vpnEnabled && !isAlwaysOnEnabled) {
                val prepareVpnIntent: Intent? = try {
                    VpnService.prepare(context)
                } catch (e: NullPointerException) {
                    Log.w(LOG_TAG, "Device does not support system-wide VPN mode.")
                    return
                }
                if (prepareVpnIntent != null) {
                    val startIntent = Intent(context, HomeScreenActivity::class.java)
                    startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context!!.startActivity(startIntent)
                    return
                } else {
                    VpnController.getInstance()?.start(context!!)
                }
            }
        }
    }

}