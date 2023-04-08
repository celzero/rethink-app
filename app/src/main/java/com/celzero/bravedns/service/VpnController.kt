/*
 * Copyright 2018 Jigsaw Operations LLC
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
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
    val persistentState by inject<PersistentState>()
    private var states: Channel<BraveVPNService.State?>? = null
    var controllerScope: CoroutineScope? = null
        private set

    private var vpnStartElapsedTime: Long = SystemClock.elapsedRealtime()

    val mutex: Mutex = Mutex()

    var connectionStatus: MutableLiveData<BraveVPNService.State?> = MutableLiveData()

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
        controllerScope?.launch { states?.send(state) }
    }

    private fun updateState(state: BraveVPNService.State?) {
        connectionState = state
        connectionStatus.postValue(state)
    }

    // lock(VpnController.mutex) should not be held
    fun start(context: Context) {
        // if the tunnel has the go-adapter then there's nothing to do
        if (braveVpnService?.isOn() == true) {
            Log.w(LOG_TAG_VPN, "braveVPNService is already on, resending vpn enabled state")
            return
        }
        // else: resend/send the start-command to the vpn service which handles both false-start
        // and actual-start scenarios just fine; ref: isNewVpn bool in vpnService.onStartCommand

        val startServiceIntent = Intent(context, BraveVPNService::class.java)

        // ContextCompat will take care of calling the proper service based on the API version.
        // before Android O, context.startService(intent) should be invoked.
        // on or after Android O, context.startForegroundService(intent) should be invoked.
        ContextCompat.startForegroundService(context, startServiceIntent)

        onConnectionStateChanged(state().connectionState)
        Log.i(LOG_TAG_VPN, "VPNController - Start(Synchronized) executed - $context")
    }

    fun stop(context: Context) {
        Log.i(LOG_TAG_VPN, "VPN Controller stop with context: $context")
        connectionState = null
        braveVpnService?.signalStopService(true)
        braveVpnService = null
        onConnectionStateChanged(connectionState)
        persistentState.setVpnEnabled(false)
    }

    fun state(): VpnState {
        val requested: Boolean = persistentState.getVpnEnabled()
        val on = isOn()
        return VpnState(requested, on, connectionState)
    }

    fun isOn(): Boolean {
        return braveVpnService?.isOn() == true
    }

    fun refresh() {
        braveVpnService?.refresh()
    }

    fun hasTunnel(): Boolean {
        return braveVpnService?.hasTunnel() == true
    }

    fun hasStarted(): Boolean {
        return connectionState == BraveVPNService.State.WORKING ||
            connectionState == BraveVPNService.State.FAILING
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

    fun protocols(): String {
        var protoString = ""
        val ipv4Size = braveVpnService?.underlyingNetworks?.ipv4Net?.size ?: 0
        val ipv6Size = braveVpnService?.underlyingNetworks?.ipv6Net?.size ?: 0
        if (ipv4Size >= 1 && ipv6Size >= 1) {
            protoString = "IPv4, IPv6"
        } else if (ipv4Size >= 1) {
            protoString = "IPv4"
        } else if (ipv6Size >= 1) {
            protoString = "IPv6"
        } else {
            // no-op
        }
        return protoString
    }

    fun netType(): String {
        // using firewall_status_unknown from strings.xml as a place holder to show network
        // type as Unknown.
        var t = braveVpnService?.getString(R.string.firewall_status_unknown) ?: ""
        if (braveVpnService == null) {
            return t
        }

        t =
            if (braveVpnService?.underlyingNetworks?.isActiveNetworkMetered == true) {
                braveVpnService?.getString(R.string.ada_app_metered).toString()
            } else {
                // the network type is shown as unmetered even when rethink cannot determine
                // the underlying network / no underlying network
                braveVpnService?.getString(R.string.ada_app_unmetered).toString()
            }
        return t
    }
}
