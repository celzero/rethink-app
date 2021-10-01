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
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.isAtleastO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object VpnController : KoinComponent {

    private var braveVpnService: BraveVPNService? = null
    private var connectionState: BraveVPNService.State? = null
    private val persistentState by inject<PersistentState>()
    private var states: Channel<BraveVPNService.State?>? = null
    private var controllerScope: CoroutineScope? = null

    private var vpnStartElapsedTime: Long = SystemClock.elapsedRealtime()

    val mutex: Mutex = Mutex()

    var connectionStatus: MutableLiveData<BraveVPNService.State> = MutableLiveData()

    @Throws(CloneNotSupportedException::class)
    fun clone(): Any? {
        throw CloneNotSupportedException()
    }

    // TODO: make clients listen on create, start, stop, destory from vpn-service
    fun onVpnCreated(b: BraveVPNService) {
        braveVpnService = b
        controllerScope = CoroutineScope(Dispatchers.IO)
        states = Channel(Channel.CONFLATED) // drop unconsumed states

        // store app start time, used in HomeScreenBottomSheet
        vpnStartElapsedTime = SystemClock.elapsedRealtime()

        controllerScope!!.launch {
            states!!.consumeEach { state ->
                // transition from paused connection state only on NEW/NULL
                when (state) {
                    null -> {
                        updateState(null)
                    }
                    BraveVPNService.State.NEW -> {
                        updateState(state)
                    }
                    else -> {
                        // do not update if in paused-state unless state is new / null
                        if (!isAppPaused()) {
                            updateState(state)
                        }
                    }
                }
            }
        }
    }

    fun onVpnDestroyed() {
        braveVpnService = null
        controllerScope?.cancel("stop")
        states?.cancel()
        vpnStartElapsedTime = SystemClock.elapsedRealtime()
    }

    fun uptimeMs(): Long {
        val t = SystemClock.elapsedRealtime() - vpnStartElapsedTime

        return if (isOn()) {
            t
        } else {
            -1L * t
        }
    }

    fun onConnectionStateChanged(state: BraveVPNService.State?) {
        controllerScope?.launch {
            states?.send(state)
        }
    }

    private fun updateState(state: BraveVPNService.State?) {
        connectionState = state
        connectionStatus.postValue(state)
    }

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

    fun stop(context: Context) {
        Log.i(LOG_TAG_VPN, "VPN Controller stop with context: $context")
        connectionState = null
        braveVpnService?.signalStopService(true)
        braveVpnService = null
        onConnectionStateChanged(connectionState)
    }

    fun state(): VpnState {
        val requested: Boolean = persistentState.getVpnEnabled()
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

    fun isAppPaused(): Boolean {
        return connectionState == BraveVPNService.State.PAUSED
    }

    fun isVpnLockdown(): Boolean {
        return Utilities.isVpnLockdownEnabled(braveVpnService)
    }

    fun isAlwaysOn(context: Context): Boolean {
        return Utilities.isAlwaysOnEnabled(context, braveVpnService)
    }

    fun pauseApp() {
        braveVpnService?.let {
            onConnectionStateChanged(BraveVPNService.State.PAUSED)
            it.pauseApp()
        }
    }

    fun resumeApp() {
        braveVpnService?.let {
            onConnectionStateChanged(BraveVPNService.State.NEW)
            it.resumeApp()
        }
    }

    fun getPauseCountDownObserver(): MutableLiveData<Long>? {
        return braveVpnService?.getPauseCountDownObserver()
    }

    fun increasePauseDuration(durationMs: Long) {
        braveVpnService?.increasePauseDuration(durationMs)
    }

    fun decreasePauseDuration(durationMs: Long) {
        braveVpnService?.decreasePauseDuration(durationMs)
    }

}
