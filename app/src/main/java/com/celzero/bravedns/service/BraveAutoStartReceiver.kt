package com.celzero.bravedns.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

class BraveAutoStartReceiver  : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {

        if (intent!!.action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            Log.i("BraveVPN",String.format("ACTION_BOOT_COMPLETED from boot receiver"))
            var prepareVpnIntent: Intent? = null
            prepareVpnIntent = try {
                VpnService.prepare(context)
            } catch (e: NullPointerException) {
                Log.e("BraveVPN","Device does not support system-wide VPN mode.")
                return
            }
            if(prepareVpnIntent != null) {
                Log.i("BraveVPN",String.format("Starting DNS VPN service from boot receiver"))
                VpnController.getInstance()?.start(context!!)
            }else
                VpnController.getInstance()?.start(context!!)

        }
    }

}