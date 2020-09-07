package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity

class BraveAutoStartReceiver  : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {

        if (intent!!.action.equals(Intent.ACTION_BOOT_COMPLETED) || intent.action.equals(Intent.ACTION_REBOOT)) {
            if (PersistentState.getPrefAutoStartBootUp(context!!)) {
                var prepareVpnIntent: Intent? = null
                prepareVpnIntent = try {
                    VpnService.prepare(context)
                } catch (e: NullPointerException) {
                    Log.e("BraveVPN", "Device does not support system-wide VPN mode.")
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