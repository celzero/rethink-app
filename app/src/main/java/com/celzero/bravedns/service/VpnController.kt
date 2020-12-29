
/*
Copyright 2018 Jigsaw Operations LLC

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
package com.celzero.bravedns.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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
            Log.i(LOG_TAG,"braveVPNService is not null")
            return
        }
        VpnControllerHelper.persistentState.vpnEnabled = true
        stateChanged(context)
        val startServiceIntent = Intent(context, BraveVPNService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startServiceIntent)
        } else {
            context.startService(startServiceIntent)
        }
        Log.i(LOG_TAG,"VPNController - Start(Synchronized) executed - $context")
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
        Log.i(LOG_TAG,"VPN Controller stop - ${context!!}")
        VpnControllerHelper.persistentState.vpnEnabled = false
        connectionState = null //BraveVPNService.State.STOP
        if (braveVpnService != null) {
            braveVpnService!!.signalStopService(true)
        }
        braveVpnService = null
        stateChanged(context)
    }

    //@Synchronized
    fun getState(context: Context?): VpnState? {
        val requested: Boolean = VpnControllerHelper.persistentState.vpnEnabled
        val on = braveVpnService != null && braveVpnService!!.isOn()
        /*if(connectionState == null){
            connectionState = BraveVPNService.State.NEW
        }*/
        return VpnState(requested, on, connectionState)
    }

    /*fun test(){
        braveVpnService!!.test()
    }*/
}

internal object VpnControllerHelper:KoinComponent {
    val persistentState by inject<PersistentState>()
    val queryTracker by inject<QueryTracker>()
}