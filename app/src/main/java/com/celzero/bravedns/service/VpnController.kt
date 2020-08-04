package com.celzero.bravedns.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class VpnController {

    companion object{
        private var dnsVpnServiceState: VpnController? = null
        private var braveVpnService : BraveVPNService ? = null
        private var connectionState: BraveVPNService.State? = null
        private var tracker: QueryTracker? = null

        @Synchronized
        fun getInstance(): VpnController? {
            if (dnsVpnServiceState == null) {
                dnsVpnServiceState = VpnController()
            }
            return dnsVpnServiceState
        }
    }

    @Throws(CloneNotSupportedException::class)
    fun clone(): Any? {
        throw CloneNotSupportedException()
    }

    fun setBraveVpnService(braveVpnService: BraveVPNService?) {
        if (braveVpnService != null) {
            VpnController.braveVpnService = braveVpnService
        }
    }

    fun getBraveVpnService(): BraveVPNService? {
            return braveVpnService
    }

    @Synchronized
    fun onConnectionStateChanged(context: Context?, state: BraveVPNService.State? ) {
        if (braveVpnService == null) {
            // User clicked disable while the connection state was changing.
            return
        }
        connectionState = state
        if (context != null) {
            stateChanged(context)
        }
    }


    private fun stateChanged(context: Context) {
        val broadcast = Intent(InternalNames.DNS_STATUS.name)
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast)
    }


    @Synchronized
    fun start(context: Context) {
        //TODO : Code modified to remove the check of null reference
        /*if (braveVpnService != null) {
            return
        }*/
        PersistentState.setVpnEnabled(context, true)
        stateChanged(context)
        val startServiceIntent = Intent(context, BraveVPNService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startServiceIntent)
        } else {
            context.startService(startServiceIntent)
        }
    }

    @Synchronized
    fun onStartComplete(context: Context?, succeeded: Boolean) {
        if (!succeeded) {
            // VPN setup only fails if VPN permission has been revoked.  If this happens, clear the
            // user intent state and reset to the default state.
            stop(context)
        } else {
            stateChanged(context!!)
        }
    }

    @Synchronized
    fun stop(context: Context?) {
        PersistentState.setVpnEnabled(context!!, false)
        connectionState = null
        if (braveVpnService != null) {
            braveVpnService!!.signalStopService(true)
        }
        braveVpnService = null
        stateChanged(context)
    }

    @Synchronized
    fun getState(context: Context?): VpnState? {
        val requested: Boolean = PersistentState.getVpnEnabled(context!!)
        val on = braveVpnService != null && braveVpnService!!.isOn()
        return VpnState(requested, on, connectionState)
    }

    @Synchronized
    fun getTracker(context: Context?): QueryTracker? {
        if (tracker == null) {
            tracker = QueryTracker(context)
        }
        return tracker
    }
}