package com.celzero.bravedns.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class VpnController {

    private var dnsVpnServiceState: VpnController? = null

    private var braveVpnService : BraveVPNService ?= null
    private var connectionState: BraveVPNService.State? = null
    //private val tracker: QueryTracker? = null

    @Throws(CloneNotSupportedException::class)
    fun clone(): Any? {
        throw CloneNotSupportedException()
    }

    @Synchronized
    fun getInstance(): VpnController? {
        if (dnsVpnServiceState == null) {
            dnsVpnServiceState = VpnController()
        }
        return dnsVpnServiceState
    }



    fun setBraveVpnService(braveVpnService: BraveVPNService?) {
        if (braveVpnService != null) {
            this.braveVpnService = braveVpnService
        }
    }


    fun getBraveVpnService(): BraveVPNService? {
            return this.braveVpnService
    }


    @Synchronized
    fun onConnectionStateChanged(
        context: Context?,
        state: BraveVPNService.State?
    ) {
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
        Log.e("BraveDNS","Controller onStart called")
        /*if (braveVpnService != null) {
            return
        }*/
        val persistantState = PersistantState()
        persistantState.setVpnEnabled(context, true)
        stateChanged(context)
        val startServiceIntent = Intent(context, BraveVPNService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.e("BraveDNS","startForegroundService")
            context.startForegroundService(startServiceIntent)
        } else {
            Log.e("BraveDNS","startService")
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
        val persistantState = PersistantState()
        persistantState.setVpnEnabled(context!!, false)
        connectionState = null
        if (braveVpnService != null) {
            braveVpnService!!.signalStopService(true)
        }
        braveVpnService?.let {braveVPNService ->null   }
        stateChanged(context!!)
    }

    @Synchronized
    fun getState(context: Context?): VpnState? {
        val persistantState = PersistantState()
        val requested: Boolean = persistantState.getVpnEnabled(context!!)
        val on = braveVpnService != null && braveVpnService!!.isOn()
        return VpnState(requested, on, connectionState)
    }



}