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

import Logger
import Logger.LOG_TAG_VPN
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import backend.RDNS
import backend.Stats
import com.celzero.bravedns.R
import com.celzero.bravedns.service.BraveVPNService.Companion.FAIL_OPEN_ON_NO_NETWORK
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object VpnController : KoinComponent {

    private var braveVpnService: BraveVPNService? = null
    private var connectionState: BraveVPNService.State? = null
    private val persistentState by inject<PersistentState>()
    private var states: Channel<BraveVPNService.State?>? = null
    private var protocol: Pair<Boolean, Boolean> = Pair(false, false)

    // usually same as vpnScope from BraveVPNService
    var externalScope: CoroutineScope? = null
        private set

    private var vpnStartElapsedTime: Long = SystemClock.elapsedRealtime()

    // FIXME: Publish VpnState through this live-data to relieve direct access
    // into VpnController's state(), isOn(), hasTunnel() etc.
    var connectionStatus: MutableLiveData<BraveVPNService.State?> = MutableLiveData()

    @Throws(CloneNotSupportedException::class)
    fun clone(): Any? {
        throw CloneNotSupportedException()
    }

    // TODO: make clients listen on create, start, stop, destroy from vpn-service
    fun onVpnCreated(b: BraveVPNService) {
        braveVpnService = b
        externalScope = CoroutineScope(Dispatchers.IO)
        states = Channel(Channel.CONFLATED) // drop unconsumed states

        // store app start time, used in HomeScreenBottomSheet
        vpnStartElapsedTime = SystemClock.elapsedRealtime()

        externalScope!!.launch {
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
        states?.cancel()
        vpnStartElapsedTime = SystemClock.elapsedRealtime()
        try {
            externalScope?.cancel("VPNController - onVpnDestroyed")
        } catch (ignored: IllegalStateException) {}
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
        externalScope?.launch { states?.send(state) }
    }

    private fun updateState(state: BraveVPNService.State?) {
        connectionState = state
        connectionStatus.postValue(state)
    }

    fun start(context: Context) {
        // if the tunnel has the go-adapter then there's nothing to do
        if (hasTunnel()) {
            Logger.w(LOG_TAG_VPN, "braveVPNService is already on, resending vpn enabled state")
            return
        }
        // else: resend/send the start-command to the vpn service which handles both false-start
        // and actual-start scenarios just fine; ref: isNewVpn bool in vpnService.onStartCommand

        val startServiceIntent = Intent(context, BraveVPNService::class.java)

        // ContextCompat will take care of calling the proper service based on the API version.
        // before Android O, context.startService(intent) should be invoked.
        // on or after Android O, context.startForegroundService(intent) should be invoked.
        ContextCompat.startForegroundService(context, startServiceIntent)

        onConnectionStateChanged(connectionState)
        Logger.i(LOG_TAG_VPN, "VPNController - Start(Synchronized) executed - $context")
    }

    fun stop(context: Context) {
        Logger.i(LOG_TAG_VPN, "VPN Controller stop with context: $context")
        connectionState = null
        onConnectionStateChanged(connectionState)
        braveVpnService?.signalStopService(userInitiated = true)
    }

    fun state(): VpnState {
        val requested: Boolean = persistentState.getVpnEnabled()
        val on = isOn()
        return VpnState(requested, on, connectionState)
    }

    @Deprecated(message = "use hasTunnel() instead", replaceWith = ReplaceWith("hasTunnel()"))
    fun isOn(): Boolean {
        return hasTunnel()
    }

    suspend fun refresh() {
        braveVpnService?.refreshResolvers()
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

    fun getProxyStatusById(id: String): Long? {
        return braveVpnService?.getProxyStatusById(id)
    }

    fun getProxyStats(id: String): Stats? {
        return braveVpnService?.getProxyStats(id) ?: null
    }

    fun getSupportedIpVersion(id: String): Pair<Boolean, Boolean> {
        return braveVpnService?.getSupportedIpVersion(id) ?: Pair(false, false)
    }

    fun isSplitTunnelProxy(id: String, pair: Pair<Boolean, Boolean>): Boolean {
        return braveVpnService?.isSplitTunnelProxy(id, pair) ?: false
    }

    suspend fun syncP50Latency(id: String) {
        braveVpnService?.syncP50Latency(id)
    }

    fun protocols(): String {
        val ipv4 = protocol.first
        val ipv6 = protocol.second
        Logger.d(LOG_TAG_VPN, "protocols - ipv4: $ipv4, ipv6: $ipv6")
        return if (ipv4 && ipv6) {
            "IPv4, IPv6"
        } else if (ipv6) {
            "IPv6"
        } else if (ipv4) {
            "IPv4"
        } else {
            // if both are false, then return based on the FAIL_OPEN_ON_NO_NETWORK value
            if (FAIL_OPEN_ON_NO_NETWORK) {
                "IPv4, IPv6"
            } else {
                ""
            }
        }
    }

    fun updateProtocol(proto: Pair<Boolean, Boolean>) {
        if (!proto.first && !proto.second) {
            Logger.i(LOG_TAG_VPN, "both v4 and v6 false, setting $FAIL_OPEN_ON_NO_NETWORK")
            protocol = Pair(FAIL_OPEN_ON_NO_NETWORK, FAIL_OPEN_ON_NO_NETWORK)
            return
        }
        protocol = proto
    }

    fun mtu(): Int {
        return braveVpnService?.underlyingNetworks?.minMtu ?: BraveVPNService.VPN_INTERFACE_MTU
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

    fun hasCid(cid: String, uid: Int): Boolean {
        return braveVpnService?.hasCid(cid, uid) ?: false
    }

    fun removeWireGuardProxy(id: Int) {
        braveVpnService?.removeWireGuardProxy(id)
    }

    fun addWireGuardProxy(id: String) {
        braveVpnService?.addWireGuardProxy(id)
    }

    fun refreshProxies() {
        braveVpnService?.refreshProxies()
    }

    fun closeConnectionsIfNeeded(uid: Int = INVALID_UID) {
        braveVpnService?.closeConnectionsIfNeeded(uid)
    }

    fun getDnsStatus(id: String): Long? {
        return braveVpnService?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return braveVpnService?.getRDNS(type)
    }
}
