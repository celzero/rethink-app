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
import android.net.Network
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.DNSTransport
import com.celzero.firestack.backend.NetStat
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.RouterStats
import com.celzero.firestack.intra.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException

object VpnController : KoinComponent {

    private var braveVpnService: BraveVPNService? = null
    private var connectionState: BraveVPNService.State? = null
    private var lastConnectedServerName: String? = null
    private val persistentState by inject<PersistentState>()
    private var states: Channel<BraveVPNService.State?>? = null
    private var protocol: Pair<Boolean, Boolean> = Pair(false, false)
    private const val URL4 = "IPv4"
    private const val URL6 = "IPv6"

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
            // externalScope?.coroutineContext?.get(Job)?.cancel("VPNController - onVpnDestroyed")
            externalScope?.cancel("VPNController - onVpnDestroyed")
        } catch (_: IllegalStateException) {} catch (
            _: CancellationException) {} catch (_: Exception) {}
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

    fun onServerNameUpdated(name: String?) {
        lastConnectedServerName = name
    }

    private fun updateState(state: BraveVPNService.State?) {
        connectionState = state
        connectionStatus.postValue(state)
    }

    fun start(context: Context, reboot: Boolean = false) {
        // if the tunnel has the go-adapter then there's nothing to do
        if (hasTunnel()) {
            Logger.w(LOG_TAG_VPN, "braveVPNService is already on, resending vpn enabled state")
            return
        }
        // below check is to avoid multiple calls to start the vpn when always-on is enabled
        // case: after a device reboot, vpn?.isAlwaysOnEnabled() may return false even though
        // always-on is actually enabled; this causes the VPN to start twice and fails doing so.
        // one approach is to store the always-on state in persistent state and check it here.
        // another is to check whether the vpn is already running.
        // todo: see whether changing the persistent state is really necessary.
        if (braveVpnService != null && reboot) {
            Logger.i(LOG_TAG_VPN, "vpn service already running, no need to start")
            return
        }
        try {
            // else: resend/send the start-command to the vpn service which handles both false-start
            // and actual-start scenarios just fine; ref: isNewVpn bool in vpnService.onStartCommand
            val startServiceIntent = Intent(context, BraveVPNService::class.java)

            // ContextCompat will take care of calling the proper service based on the API version.
            // before Android O, context.startService(intent) should be invoked.
            // on or after Android O, context.startForegroundService(intent) should be invoked.
            ContextCompat.startForegroundService(context, startServiceIntent)

            onConnectionStateChanged(connectionState)
            Logger.i(LOG_TAG_VPN, "VPNController; Start(sync) executed")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "VPNController; Start(sync) failed, ${e.message}")
        }
    }

    fun stop(reason: String, context: Context) {
        Logger.i(LOG_TAG_VPN, "VPN Controller stop with context: $context")
        connectionState = null
        onConnectionStateChanged(null)
        braveVpnService?.signalStopService(reason, userInitiated = true)
    }

    fun state(): VpnState {
        val requested: Boolean = persistentState.getVpnEnabled()
        val on = isOn()
        return VpnState(requested, on, connectionState, lastConnectedServerName)
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

    suspend fun getProxyStatusById(id: String): Pair<Long?, String> {
        return braveVpnService?.getProxyStatusById(id) ?: Pair(null, "vpn service not available")
    }

    suspend fun getProxyStats(id: String): RouterStats? {
        return braveVpnService?.getProxyStats(id)
    }

    suspend fun getWireGuardStats(id: String): WireguardManager.WgStats? {
        return braveVpnService?.getWireGuardStats(id)
    }

    suspend fun getSupportedIpVersion(id: String): Pair<Boolean, Boolean> {
        return braveVpnService?.getSupportedIpVersion(id) ?: Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(id: String, pair: Pair<Boolean, Boolean>): Boolean {
        return braveVpnService?.isSplitTunnelProxy(id, pair) ?: false
    }

    suspend fun p50(id: String): Long {
        return braveVpnService?.p50(id) ?: -1L
    }

    fun getRegionLiveData(): LiveData<String> {
        return braveVpnService?.getRegionLiveData() ?: MutableLiveData()
    }

    fun protocols(): String {
        val ipv4 = protocol.first
        val ipv6 = protocol.second
        return if (ipv4 && ipv6) {
            "$URL4, $URL6"
        } else if (ipv6) {
            URL6
        } else if (ipv4) {
            URL4
        } else {
            // if both are false, then return based on the stallOnNoNetwork value
            if (!persistentState.stallOnNoNetwork) {
                "$URL4, $URL6"
            } else {
                "-"
            }
        }
    }

    fun updateProtocol(proto: Pair<Boolean, Boolean>) {
        if (!proto.first && !proto.second) {
            val failOpen = !persistentState.stallOnNoNetwork
            Logger.i(LOG_TAG_VPN, "both v4 and v6 false, setting $failOpen")
            protocol = Pair(failOpen, failOpen)
            return
        }
        protocol = proto
    }

    fun mtu(): Int {
        return braveVpnService?.tunMtu() ?: 0
    }

    fun underlyingSsid(): String? {
        return braveVpnService?.underlyingNetworks?.activeSsid ?: braveVpnService?.underlyingNetworks?.ipv4Net?.firstOrNull { !it.ssid.isNullOrEmpty() }?.ssid ?: braveVpnService?.underlyingNetworks?.ipv6Net?.firstOrNull { !it.ssid.isNullOrEmpty() }?.ssid ?: ""
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

    suspend fun removeWireGuardProxy(id: Int) {
        braveVpnService?.removeWireGuardProxy(id)
    }

    suspend fun addWireGuardProxy(id: String, force: Boolean = false) {
        braveVpnService?.addWireGuardProxy(id, force)
    }

    suspend fun refreshOrPauseOrResumeOrReAddProxies() {
        braveVpnService?.refreshOrPauseOrResumeOrReAddProxies()
    }

    fun closeConnectionsIfNeeded(uid: Int = INVALID_UID, reason: String) {
        braveVpnService?.closeConnectionsIfNeeded(uid, reason)
    }

    fun closeConnectionsByUidDomain(uid: Int, ipAddress: String?, reason: String) {
        braveVpnService?.closeConnectionsByUidDomain(uid, ipAddress, reason)
    }

    suspend fun getDnsStatus(id: String): Long? {
        return braveVpnService?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return braveVpnService?.getRDNS(type)
    }

    fun protectSocket(socket: Socket) {
        braveVpnService?.protectSocket(socket)
    }

    suspend fun probeIpOrUrl(ip: String, useAuto: Boolean): ConnectionMonitor.ProbeResult? {
        return braveVpnService?.probeIpOrUrl(ip, useAuto)
    }

    suspend fun notifyConnectionMonitor(enforcePolicyChange: Boolean = false) {
        braveVpnService?.notifyConnectionMonitor(enforcePolicyChange)
    }

    suspend fun getSystemDns(): String {
        return braveVpnService?.getSystemDns() ?: ""
    }

    fun getNetStat(): NetStat? {
        return braveVpnService?.getNetStat()
    }

    fun writeConsoleLog(log: ConsoleLog) {
        braveVpnService?.writeConsoleLog(log)
    }

    suspend fun isProxyReachable(proxyId: String, ippcsv: String): Boolean {
        return braveVpnService?.isProxyReachable(proxyId, ippcsv) ?: false
    }

    suspend fun registerAndFetchWinConfig(prevBytes: ByteArray?): ByteArray? {
        return braveVpnService?.registerAndFetchWinIfNeeded(prevBytes)
    }

    suspend fun createWgHop(origin: String, hop: String): Pair<Boolean, String> {
        return (braveVpnService?.createWgHop(origin, hop) ?: Pair(false, "vpn service not available"))
    }

    suspend fun testRpnProxy(proxyId: String): Boolean {
        return braveVpnService?.testRpnProxy(proxyId) == true
    }

    suspend fun testHop(src: String, hop: String): Pair<Boolean, String?> {
        return braveVpnService?.testHop(src, hop) ?: Pair(false, "vpn service not available")
    }

    suspend fun hopStatus(src: String, hop: String): Pair<Long?, String> {
        return braveVpnService?.hopStatus(src, hop) ?: Pair(null, "vpn service not available")
    }

    suspend fun removeHop(src: String): Pair<Boolean, String> {
        return braveVpnService?.removeHop(src) ?: Pair(false, "vpn service not available")
    }

    /*suspend fun getRpnProps(type: RpnProxyManager.RpnType): Pair<RpnProxyManager.RpnProps?, String?> {
        return braveVpnService?.getRpnProps(type) ?: Pair(null, null)
    }
*/
    suspend fun vpnStats(): String? {
        return braveVpnService?.vpnStats()
    }

    fun performConnectivityCheck(controller: Controller, id: String, addrPort: String): Boolean {
        return braveVpnService?.performConnectivityCheck(controller, id, addrPort) ?: false
    }

    fun performAutoConnectivityCheck(controller: Controller, id: String, mode: String): Boolean {
        return braveVpnService?.performAutoConnectivityCheck(controller, id, mode) ?: false
    }

    fun bindToNwForConnectivityChecks(nw: Network, pfd: Long): Boolean {
        return braveVpnService?.bindToNwForConnectivityChecks(nw, pfd) ?: false
    }

    fun protectFdForConnectivityChecks(fd: Long) {
        this.braveVpnService?.protectFdForConnectivityChecks(fd)
    }

    suspend fun getPlusResolvers(): List<String> {
        return braveVpnService?.getPlusResolvers() ?: emptyList()
    }

    suspend fun getPlusTransportById(id: String): DNSTransport? {
        return braveVpnService?.getPlusTransportById(id)
    }

    fun isUnderlyingVpnNetworkEmpty(): Boolean {
        return braveVpnService?.isUnderlyingVpnNetworkEmpty() ?: false
    }

    fun screenUnlock() {
        braveVpnService?.screenUnlock()
    }

    suspend fun performFlightRecording() {
        braveVpnService?.performFlightRecording()
    }
}
