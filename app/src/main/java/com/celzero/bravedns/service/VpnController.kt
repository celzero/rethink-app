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
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.isAtleastO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object VpnController {

    private var braveVpnService: BraveVPNService? = null
    private var connectionState: BraveVPNService.State? = null

    var connectionStatus: MutableLiveData<BraveVPNService.State> = MutableLiveData()

    @Throws(CloneNotSupportedException::class)
    fun clone(): Any? {
        throw CloneNotSupportedException()
    }

    fun setBraveVpnService(braveVpnService: BraveVPNService?) {
        VpnController.braveVpnService = braveVpnService
    }

    fun getBraveVpnService(): BraveVPNService? {
        return braveVpnService
    }

    @Synchronized
    fun onConnectionStateChanged(state: BraveVPNService.State?) {
        connectionState = if (braveVpnService == null) {
            // User clicked disable while the connection state was changing.
            null
        } else {
            state
        }
        connectionStatus.postValue(state)
    }

    @Synchronized
    fun start(context: Context) {
        //TODO : Code modified to remove the check of null reference - MODIFIED check??
        if (braveVpnService != null) {
            Log.i(LOG_TAG_VPN, "braveVPNService is not null")
            return
        }
        val startServiceIntent = Intent(context, BraveVPNService::class.java)
        if (isAtleastO()) {
            context.startForegroundService(startServiceIntent)
        } else {
            context.startService(startServiceIntent)
        }
        onConnectionStateChanged(state().connectionState)
        Log.i(LOG_TAG_VPN, "VPNController - Start(Synchronized) executed - $context")

    }

    fun onStartComplete(context: Context, succeeded: Boolean) {
        if (!succeeded) {
            // VPN setup only fails if VPN permission has been revoked.  If this happens, clear the
            // user intent state and reset to the default state.
            stop(context)
        } else {
            // no op
        }
        Log.i(LOG_TAG_VPN, "onStartComplete - VpnController")
    }

    @Synchronized
    fun stop(context: Context) {
        Log.i(LOG_TAG_VPN, "VPN Controller stop with context: $context")
        connectionState = null
        braveVpnService?.signalStopService(true)
        braveVpnService = null
        onConnectionStateChanged(connectionState)
    }

    fun state(): VpnState {
        val requested: Boolean = braveVpnService?.persistentState?.getVpnEnabled() == true
        val on = isOn()
        return VpnState(requested, on, connectionState)
    }

    fun isOn(): Boolean {
        return braveVpnService?.isOn() == true
    }

    fun hasTunnel(): Boolean {
        return braveVpnService?.hasTunnel() == true
    }

    fun hasStarted(): Boolean {
        return connectionState == BraveVPNService.State.WORKING || connectionState == BraveVPNService.State.FAILING
    }

}
