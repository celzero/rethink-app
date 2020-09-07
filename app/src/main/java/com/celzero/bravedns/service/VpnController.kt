package com.celzero.bravedns.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class VpnController {

    companion object{
        private var dnsVpnServiceState: VpnController? = null
        private var braveVpnService : BraveVPNService ? = null
        private var connectionState: BraveVPNService.State? = null
        private var tracker: QueryTracker? = null
        private var ipTracker : IPTracker ?= null

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
    fun onConnectionStateChanged(context: Context, state: BraveVPNService.State? ) {
        if (braveVpnService == null) {
            // User clicked disable while the connection state was changing.
            return
        }
        connectionState = state
        //TODO Changes done to remove the check of context
        stateChanged(context)
        /*if (context != null) {

        }*/
    }


    private fun stateChanged(context: Context) {
        val broadcast = Intent(InternalNames.DNS_STATUS.name)
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast)
    }


    @Synchronized
    fun start(context: Context) {
        //TODO : Code modified to remove the check of null reference - MODIFIED check??
        if (braveVpnService != null) {
            Log.d("BraveDNS","braveVPNService is not null")
            return
        }
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
        Log.e("BraveDNS", "VPN controller stop called")
        PersistentState.setVpnEnabled(context!!, false)
        connectionState = null
        if (braveVpnService != null) {
            braveVpnService!!.signalStopService(true)
        }
        braveVpnService = null
        stateChanged(context)
    }

    // FIXME: Should this be synchronized? Causes ANRs.
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

    @Synchronized
    fun getIPTracker(context : Context?): IPTracker? {
        if(ipTracker == null){
            ipTracker = IPTracker(context)
        }
        return ipTracker
    }
}