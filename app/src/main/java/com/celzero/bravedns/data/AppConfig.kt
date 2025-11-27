/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.data

import Logger
import Logger.LOG_TAG_VPN
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptEndpointRepository
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.database.DnsCryptRelayEndpointRepository
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DnsProxyEndpointRepository
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.DoTEndpointRepository
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ODoHEndpointRepository
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpointRepository
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.database.RethinkDnsEndpointRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.InternetProtocol.Companion.getInternetProtocol
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.settings.Settings

class AppConfig
internal constructor(
    private val context: Context,
    private val rethinkDnsEndpointRepository: RethinkDnsEndpointRepository,
    private val dnsProxyEndpointRepository: DnsProxyEndpointRepository,
    private val doHEndpointRepository: DoHEndpointRepository,
    private val dnsCryptEndpointRepository: DnsCryptEndpointRepository,
    private val dnsCryptRelayEndpointRepository: DnsCryptRelayEndpointRepository,
    private val doTEndpointRepository: DoTEndpointRepository,
    private val oDoHEndpointRepository: ODoHEndpointRepository,
    private val proxyEndpointRepository: ProxyEndpointRepository,
    private val persistentState: PersistentState,
    private val networkLogs: ConnectionTrackerRepository,
    private val dnsLogs: DnsLogRepository
) {
    private var braveModeObserver: MutableLiveData<Int> = MutableLiveData()
    private var pcapFilePath: String = ""
    private var customSocks5Endpoint: ProxyEndpoint? = null
    private var customHttpEndpoint: ProxyEndpoint? = null
    private var orbotEndpoint: ProxyEndpoint? = null

    companion object {
        private var connectedDns: MutableLiveData<String> = MutableLiveData()

        private const val ORBOT_DNS = "Orbot"

        const val FALLBACK_DNS_IF_NET_DNS_EMPTY = "9.9.9.9,2620:fe::fe"

        // used to add index to the transport ids added as part of Plus transport
        // for now only DOH, DoT are supported
        const val DOH_INDEX = '1'
        const val DOT_INDEX = '2'
        const val ODOH_INDEX = '3'
        const val DNS_CRYPT_INDEX = '4'
    }

    init {
        // now connectedDnsName has the dns name and url, extract the dns name and update
        // csv is <dns-name,url>, url maybe empty
        val dnsName = persistentState.connectedDnsName.split(",").firstOrNull() ?: ""
        connectedDns.postValue(dnsName)

        // initialize pcapFilePath from persistent state
        pcapFilePath = persistentState.pcapFilePath

        // validate pcap settings: if mode is EXTERNAL_FILE but path is empty/invalid, reset to NONE
        if (PcapMode.getPcapType(persistentState.pcapMode) == PcapMode.EXTERNAL_FILE) {
            if (pcapFilePath.isEmpty() || !java.io.File(pcapFilePath).exists()) {
                Logger.w(LOG_TAG_VPN, "Pcap file path invalid or missing, resetting pcap mode to NONE")
                persistentState.pcapMode = PcapMode.NONE.id
                persistentState.pcapFilePath = ""
                pcapFilePath = ""
            }
        }
    }

    data class TunnelOptions(
        val tunDnsMode: TunDnsMode,
        val tunFirewallMode: TunFirewallMode,
        val tunProxyMode: TunProxyMode,
        val ptMode: ProtoTranslationMode,
        val bridge: Bridge,
        val defaultDns: String,
        val fakeDns: String
    )

    enum class BraveMode(val mode: Int) {
        DNS(0),
        FIREWALL(1),
        DNS_FIREWALL(2);

        fun isFirewallActive(): Boolean {
            return isFirewallMode() || isDnsFirewallMode()
        }

        fun isDnsActive(): Boolean {
            return isDnsMode() || isDnsFirewallMode()
        }

        fun isFirewallMode(): Boolean {
            return mode == FIREWALL.mode
        }

        fun isDnsMode(): Boolean {
            return mode == DNS.mode
        }

        fun isDnsFirewallMode(): Boolean {
            return mode == DNS_FIREWALL.mode
        }

        companion object {
            fun getMode(id: Int): BraveMode {
                return when (id) {
                    DNS.mode -> DNS
                    FIREWALL.mode -> FIREWALL
                    DNS_FIREWALL.mode -> DNS_FIREWALL
                    else -> DNS_FIREWALL
                }
            }
        }
    }

    enum class TunFirewallMode(val mode: Int) {
        FILTER_ANDROID9_ABOVE(Settings.BlockModeFilter),
        SINK(Settings.BlockModeSink),
        FILTER_ANDROID8_BELOW(Settings.BlockModeFilterProc),
        NONE(Settings.BlockModeNone);

        fun isFirewallSinkMode(): Boolean {
            return mode == SINK.mode
        }
    }

    enum class DnsType(val type: Int) {
        // fixme: remove the type RETHINK_REMOTE and merge it with type DOH
        DOH(1),
        DNSCRYPT(2),
        DNS_PROXY(3),
        RETHINK_REMOTE(4),
        SYSTEM_DNS(5),
        DOT(6),
        ODOH(7),
        SMART_DNS(8); // // treat Plus as SMART

        fun isDnsProxy(): Boolean {
            return this == DNS_PROXY
        }

        fun isRethinkRemote(): Boolean {
            return this == RETHINK_REMOTE
        }

        fun isSystemDns(): Boolean {
            return this == SYSTEM_DNS
        }

        fun isSmartDns(): Boolean {
            return this == SMART_DNS
        }

        fun isValidDnsType(): Boolean {
            return this == DOH ||
                this == DNSCRYPT ||
                this == DNS_PROXY ||
                this == RETHINK_REMOTE ||
                this == SYSTEM_DNS ||
                this == DOT ||
                this == ODOH ||
                this == SMART_DNS
        }

        companion object {
            fun getDnsType(id: Int): DnsType {
                return when (id) {
                    DOH.type -> DOH
                    DNSCRYPT.type -> DNSCRYPT
                    DNS_PROXY.type -> DNS_PROXY
                    RETHINK_REMOTE.type -> RETHINK_REMOTE
                    SYSTEM_DNS.type -> SYSTEM_DNS
                    DOT.type -> DOT
                    ODOH.type -> ODOH
                    SMART_DNS.type -> SMART_DNS
                    else -> DOH
                }
            }
        }
    }

    enum class TunDnsMode(val mode: Long) {
        NONE(Settings.DNSModeNone.toLong()),
        DNS_IP(Settings.DNSModeIP.toLong()),
        DNS_PORT(Settings.DNSModePort.toLong())
    }

    // TODO: untangle the mess of proxy modes and providers
    enum class TunProxyMode {
        NONE,
        HTTPS,
        SOCKS5,
        ORBOT,
        TCP,
        WIREGUARD;

        fun isTunProxyOrbot(): Boolean {
            return this == ORBOT
        }

        fun isTunProxySocks5(): Boolean {
            return this == SOCKS5
        }

        fun isTunProxyHttps(): Boolean {
            return this == HTTPS
        }

        fun isTunProxyWireguard(): Boolean {
            return this == WIREGUARD
        }

        fun isTunProxyTcp(): Boolean {
            return this == TCP
        }
    }

    // Provider - Custom - SOCKS5, Http proxy setup.
    // ORBOT - One touch Orbot integration.
    enum class ProxyProvider {
        NONE,
        CUSTOM,
        ORBOT,
        TCP,
        WIREGUARD;

        fun isProxyProviderCustom(): Boolean {
            return CUSTOM.name == name
        }

        fun isProxyProviderNone(): Boolean {
            return NONE.name == name
        }

        fun isProxyProviderOrbot(): Boolean {
            return ORBOT.name == name
        }

        fun isProxyProviderWireguard(): Boolean {
            return WIREGUARD.name == name
        }

        fun isProxyProviderTcp(): Boolean {
            return TCP.name == name
        }

        companion object {
            fun getProxyProvider(name: String): ProxyProvider {
                return when (name) {
                    WIREGUARD.name -> WIREGUARD
                    CUSTOM.name -> CUSTOM
                    ORBOT.name -> ORBOT
                    TCP.name -> TCP
                    else -> NONE
                }
            }
        }
    }

    // Supported Proxy types
    enum class ProxyType {
        NONE,
        HTTP,
        SOCKS5,
        TCP,
        HTTP_SOCKS5,
        WIREGUARD;

        fun isProxyTypeHttp(): Boolean {
            return HTTP.name == name
        }

        fun isProxyTypeSocks5(): Boolean {
            return SOCKS5.name == name
        }

        fun isProxyTypeHttpSocks5(): Boolean {
            return HTTP_SOCKS5.name == name
        }

        fun isProxyTypeTcp(): Boolean {
            return TCP.name == name
        }

        fun isProxyTypeNone(): Boolean {
            return NONE.name == name
        }

        fun isProxyTypeWireguard(): Boolean {
            return WIREGUARD.name == name
        }

        fun isProxyTypeHasHttp(): Boolean {
            return isProxyTypeHttp() || isProxyTypeHttpSocks5()
        }

        fun isSocks5Enabled(): Boolean {
            return isProxyTypeSocks5() || isProxyTypeHttpSocks5()
        }

        fun isAnyProxyEnabled(): Boolean {
            return isProxyTypeHttp() ||
                isProxyTypeSocks5() ||
                isProxyTypeHttpSocks5() ||
                isProxyTypeTcp() ||
                isProxyTypeWireguard()
        }

        companion object {
            fun of(name: String): ProxyType {
                return when (name) {
                    HTTP.name -> HTTP
                    SOCKS5.name -> SOCKS5
                    HTTP_SOCKS5.name -> HTTP_SOCKS5
                    WIREGUARD.name -> WIREGUARD
                    TCP.name -> TCP
                    else -> NONE
                }
            }
        }
    }

    enum class ProtoTranslationMode(val id: Int) {
        PTMODEAUTO(Settings.PtModeAuto),
        PTMODEFORCE64(Settings.PtModeForce64),
        PTMODENO46(Settings.PtModeNo46)
    }

    fun getInternetProtocol(): InternetProtocol {
        return getInternetProtocol(persistentState.internetProtocolType)
    }

    fun getProtocolTranslationMode(): ProtoTranslationMode {
        if (persistentState.protocolTranslationType && getInternetProtocol().isIPv6()) {
            return ProtoTranslationMode.PTMODEFORCE64
        }

        return ProtoTranslationMode.PTMODEAUTO
    }

    fun setPcap(mode: Int, path: String = PcapMode.DISABLE_PCAP) {
        pcapFilePath =
            when (PcapMode.getPcapType(mode)) {
                PcapMode.NONE -> {
                    ""
                }
                PcapMode.LOGCAT -> {
                    "0"
                }
                PcapMode.EXTERNAL_FILE -> {
                    path
                }
            }
        persistentState.pcapMode = mode
        persistentState.pcapFilePath = pcapFilePath
    }

    fun getPcapFilePath(): String {
        return pcapFilePath
    }

    fun getDnsType(): DnsType {
        return when (persistentState.dnsType) {
            DnsType.DOH.type -> DnsType.DOH
            DnsType.DNSCRYPT.type -> DnsType.DNSCRYPT
            DnsType.DNS_PROXY.type -> DnsType.DNS_PROXY
            DnsType.RETHINK_REMOTE.type -> DnsType.RETHINK_REMOTE
            DnsType.SYSTEM_DNS.type -> DnsType.SYSTEM_DNS
            DnsType.DOT.type -> DnsType.DOT
            DnsType.ODOH.type -> DnsType.ODOH
            DnsType.SMART_DNS.type -> DnsType.SMART_DNS
            else -> {
                Logger.w(LOG_TAG_VPN, "Invalid dns type mode: ${persistentState.dnsType}")
                DnsType.DOH
            }
        }
    }

    private fun getDnsMode(): TunDnsMode {
        return when (persistentState.braveMode) {
            // Case: app mode - firewall, DNS mode should be none.
            BraveMode.FIREWALL.mode -> TunDnsMode.NONE
            BraveMode.DNS.mode,
            BraveMode.DNS_FIREWALL.mode -> determineTunDnsMode()
            else -> {
                Logger.crash(
                    LOG_TAG_VPN,
                    "invalid brave mode: ${persistentState.braveMode}",
                    Exception()
                )
                TunDnsMode.NONE
            }
        }
    }

    private fun determineTunDnsMode(): TunDnsMode {
        // app mode - DNS & DNS+Firewall mode
        return if (DnsType.getDnsType(persistentState.dnsType).isValidDnsType()) {
            if (persistentState.preventDnsLeaks) {
                TunDnsMode.DNS_PORT
            } else {
                TunDnsMode.DNS_IP
            }
        } else {
            TunDnsMode.NONE
        }
    }

    fun preventDnsLeaks(): Boolean {
        return persistentState.preventDnsLeaks
    }

    fun isDnsProxyActive(): Boolean {
        return DnsType.DNS_PROXY == getDnsType()
    }

    fun getConnectedDnsObservable(): MutableLiveData<String> {
        return connectedDns
    }

    fun getBraveModeObservable(): MutableLiveData<Int> {
        return braveModeObserver
    }

    suspend fun getDOHDetails(): DoHEndpoint? {
        return doHEndpointRepository.getConnectedDoH()
    }

    suspend fun getAllDefaultDoHEndpoints(): List<DoHEndpoint> {
        return doHEndpointRepository.getAllDefaultDoHEndpoints()
    }

    suspend fun getAllDefaultDoTEndpoints(): List<DoTEndpoint> {
        return doTEndpointRepository.getAllDefaultDoTEndpoints()
    }

    suspend fun getDOTDetails(): DoTEndpoint? {
        return doTEndpointRepository.getConnectedDoT()
    }

    suspend fun getODoHDetails(): ODoHEndpoint? {
        return oDoHEndpointRepository.getConnectedODoH()
    }

    suspend fun getSocks5ProxyDetails(): ProxyEndpoint? {
        if (customSocks5Endpoint == null) {
            customSocks5Endpoint = proxyEndpointRepository.getCustomSocks5Endpoint()
        }
        return customSocks5Endpoint
    }

    suspend fun getHttpProxyDetails(): ProxyEndpoint? {
        if (customHttpEndpoint == null) {
            customHttpEndpoint = proxyEndpointRepository.getHttpProxyDetails()
        }
        return customHttpEndpoint
    }

    suspend fun getConnectedOrbotProxy(): ProxyEndpoint? {
        if (orbotEndpoint == null) {
            orbotEndpoint = proxyEndpointRepository.getConnectedOrbotProxy()
        }
        return orbotEndpoint
    }

    suspend fun getOrbotSocks5Endpoint(): ProxyEndpoint? {
        return proxyEndpointRepository.getOrbotSocks5Endpoint()
    }

    suspend fun getOrbotHttpEndpoint(): ProxyEndpoint? {
        return proxyEndpointRepository.getOrbotHttpEndpoint()
    }

    fun isTcpProxyEnabled(): Boolean {
        return TcpProxyHelper.getActiveTcpProxy() != null
    }

    fun isWireGuardEnabled(): Boolean {
        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isProxyTypeWireguard()
    }

    private suspend fun getDNSProxyServerDetails(): DnsProxyEndpoint? {
        return dnsProxyEndpointRepository.getSelectedProxy()
    }

    private suspend fun onDnsChange(dt: DnsType) {
        if (!isValidDnsType(dt)) return

        persistentState.dnsType = dt.type
        when (dt) {
            DnsType.DOH -> {
                val endpoint = getDOHDetails() ?: return

                postConnectedDnsName(endpoint.dohName, endpoint.dohURL)
            }
            DnsType.DOT -> {
                val endpoint = getDOTDetails() ?: return

                postConnectedDnsName(endpoint.name, endpoint.url)
            }
            DnsType.ODOH -> {
                val endpoint = getODoHDetails() ?: return

                postConnectedDnsName(endpoint.name, endpoint.resolver)
            }
            DnsType.DNSCRYPT -> {
                val endpoint = getConnectedDnscryptServer() ?: return
                postConnectedDnsName(endpoint.dnsCryptName, endpoint.dnsCryptURL)
            }
            DnsType.DNS_PROXY -> {
                val endpoint = getDNSProxyServerDetails() ?: return

                val ip = endpoint.proxyIP?.split(",")?.firstOrNull() ?: ""
                val url = ip + ":" + endpoint.proxyPort
                postConnectedDnsName(endpoint.proxyName, url)
            }
            DnsType.RETHINK_REMOTE -> {
                val endpoint = getRemoteRethinkEndpoint() ?: return

                persistentState.setRemoteBlocklistCount(endpoint.blocklistCount)
                postConnectedDnsName(endpoint.name, endpoint.url)
            }
            DnsType.SYSTEM_DNS -> {
                postConnectedDnsName(context.getString(R.string.network_dns))
            }
            DnsType.SMART_DNS -> {
                postConnectedDnsName(context.getString(R.string.smart_dns))
            }
        }
    }

    private fun postConnectedDnsName(name: String, url: String = "") {
        connectedDns.postValue(name)
        if (url.isEmpty()) {
            persistentState.connectedDnsName = name
        } else {
            persistentState.connectedDnsName = "$name,$url"
        }
    }

    private fun isValidDnsType(dt: DnsType): Boolean {
        return (dt == DnsType.DOH ||
            dt == DnsType.DNSCRYPT ||
            dt == DnsType.DNS_PROXY ||
            dt == DnsType.RETHINK_REMOTE ||
            dt == DnsType.SYSTEM_DNS ||
            dt == DnsType.DOT ||
            dt == DnsType.ODOH ||
            dt == DnsType.SMART_DNS)
    }

    suspend fun switchRethinkDnsToMax() {
        rethinkDnsEndpointRepository.switchToMax()
        if (isRethinkDnsConnected()) {
            onDnsChange(DnsType.RETHINK_REMOTE)
        }
    }

    suspend fun switchRethinkDnsToSky() {
        rethinkDnsEndpointRepository.switchToSky()
        if (isRethinkDnsConnected()) {
            onDnsChange(DnsType.RETHINK_REMOTE)
        }
    }

    fun changeBraveMode(braveMode: Int) {
        persistentState.braveMode = braveMode
        braveModeObserver.postValue(braveMode)
    }

    fun getBraveMode(): BraveMode {
        return when (persistentState.braveMode) {
            BraveMode.DNS.mode -> BraveMode.DNS
            BraveMode.FIREWALL.mode -> BraveMode.FIREWALL
            BraveMode.DNS_FIREWALL.mode -> BraveMode.DNS_FIREWALL
            else -> BraveMode.DNS_FIREWALL
        }
    }

    fun determineFirewallMode(): TunFirewallMode {
        // app mode - DNS, set the firewall mode as NONE.
        if (persistentState.braveMode == BraveMode.DNS.mode) {
            return TunFirewallMode.NONE
        }

        // app mode - Firewall & DNS+Firewall
        return if (isAtleastQ()) {
            TunFirewallMode.FILTER_ANDROID9_ABOVE
        } else {
            TunFirewallMode.FILTER_ANDROID8_BELOW
        }
    }

    fun newTunnelOptions(
        bridge: Bridge,
        fakeDns: String,
        ptMode: ProtoTranslationMode
    ): TunnelOptions {
        return TunnelOptions(
            getDnsMode(),
            determineFirewallMode(),
            getTunProxyMode(),
            ptMode,
            bridge,
            getDefaultDns(),
            fakeDns
        )
    }

    // -- DNS Manager --
    suspend fun getSelectedDnsProxyDetails(): DnsProxyEndpoint? {
        return dnsProxyEndpointRepository.getSelectedProxy()
    }

    suspend fun getConnectedDnscryptServer(): DnsCryptEndpoint? {
        return dnsCryptEndpointRepository.getConnectedDNSCrypt()
    }

    suspend fun getDnscryptRelayServers(): String {
        return dnsCryptRelayEndpointRepository.getServersToAdd()
    }

    suspend fun getDohCount(): Int {
        return doHEndpointRepository.getCount()
    }

    suspend fun getDoTCount(): Int {
        return doTEndpointRepository.getCount()
    }

    suspend fun getODoHCount(): Int {
        return oDoHEndpointRepository.getCount()
    }

    suspend fun getDnsProxyCount(): Int {
        return dnsProxyEndpointRepository.getCount()
    }

    suspend fun getDnscryptCount(): Int {
        return dnsCryptEndpointRepository.getCount()
    }

    suspend fun getDnscryptRelayCount(): Int {
        return dnsCryptRelayEndpointRepository.getCount()
    }

    suspend fun handleDoHChanges(doHEndpoint: DoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != DnsType.DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.update(doHEndpoint)
        onDnsChange(DnsType.DOH)
    }

    suspend fun handleDoTChanges(doTEndpoint: DoTEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != DnsType.DOT) {
            removeConnectionStatus()
        }

        doTEndpointRepository.update(doTEndpoint)
        onDnsChange(DnsType.DOT)
    }

    suspend fun handleODoHChanges(oDoHEndpoint: ODoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != DnsType.ODOH) {
            removeConnectionStatus()
        }

        oDoHEndpointRepository.update(oDoHEndpoint)
        onDnsChange(DnsType.ODOH)
    }

    suspend fun handleRethinkChanges(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        // if previous connection was rethink, then remove the connection from database
        if (getDnsType() != DnsType.RETHINK_REMOTE) {
            removeConnectionStatus()
        }

        rethinkDnsEndpointRepository.update(rethinkDnsEndpoint)
        onDnsChange(DnsType.RETHINK_REMOTE)
    }

    suspend fun handleDnsProxyChanges(dnsProxyEndpoint: DnsProxyEndpoint) {
        // if the prev connection was not dns proxy, then remove the connection status from database
        if (getDnsType() != DnsType.DNS_PROXY) {
            removeConnectionStatus()
        }

        dnsProxyEndpointRepository.update(dnsProxyEndpoint)
        onDnsChange(DnsType.DNS_PROXY)
    }

    suspend fun isOrbotDns(): Boolean {
        if (!getDnsType().isDnsProxy()) return false

        return dnsProxyEndpointRepository.getSelectedProxy()?.proxyName == ORBOT_DNS
    }

    suspend fun handleDnscryptChanges(dnsCryptEndpoint: DnsCryptEndpoint) {
        // if the prev connection was not dnscrypt, then remove the connection status from database
        if (getDnsType() != DnsType.DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.update(dnsCryptEndpoint)
        onDnsChange(DnsType.DNSCRYPT)
    }

    suspend fun getOrbotDnsProxyEndpoint(): DnsProxyEndpoint? {
        return dnsProxyEndpointRepository.getOrbotDnsEndpoint()
    }

    suspend fun handleDnsrelayChanges(endpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.update(endpoint)
        persistentState.dnsCryptRelays.postValue(
            PersistentState.DnsCryptRelayDetails(endpoint, endpoint.isSelected)
        )
    }

    suspend fun removeDnscryptRelay(stamp: String) {
        dnsCryptRelayEndpointRepository.unselectRelay(stamp)
    }

    fun getDefaultDns(): String {
        return persistentState.defaultDnsUrl
    }

    suspend fun getRemoteRethinkEndpoint(): RethinkDnsEndpoint? {
        return rethinkDnsEndpointRepository.getConnectedEndpoint()
    }

    suspend fun getRethinkDefaultEndpoint(): RethinkDnsEndpoint? {
        return rethinkDnsEndpointRepository.getDefaultRethinkEndpoint()
    }

    suspend fun getBlockFreeRethinkEndpoint(): String {
        // decide which blockfree endpoint to use
        return if (getRemoteRethinkEndpoint()?.url?.contains(MAX_ENDPOINT) == true) {
            Constants.BLOCK_FREE_DNS_MAX
        } else {
            Constants.BLOCK_FREE_DNS_SKY
        }
    }

    suspend fun getRethinkPlusEndpoint(): RethinkDnsEndpoint? {
        return rethinkDnsEndpointRepository.getRethinkPlusEndpoint()
    }

    suspend fun enableRethinkDnsPlus() {
        if (getDnsType() != DnsType.RETHINK_REMOTE) {
            removeConnectionStatus()
        }

        rethinkDnsEndpointRepository.setRethinkPlus()
        onDnsChange(DnsType.RETHINK_REMOTE)
    }

    fun isRethinkDnsConnected(): Boolean {
        return getDnsType() == DnsType.RETHINK_REMOTE
    }

    suspend fun enableSystemDns() {
        if (getDnsType() != DnsType.SYSTEM_DNS) {
            removeConnectionStatus()
        }

        onDnsChange(DnsType.SYSTEM_DNS)
    }

    suspend fun enableSmartDns() {
        if (getDnsType() != DnsType.SMART_DNS) {
            removeConnectionStatus()
        }

        onDnsChange(DnsType.SMART_DNS)
    }

    fun isSystemDns(): Boolean {
        return getDnsType().isSystemDns()
    }

    fun isSmartDnsEnabled(): Boolean {
        return getDnsType().isSmartDns()
    }

    suspend fun isDnscryptRelaySelectable(): Boolean {
        return dnsCryptEndpointRepository.getConnectedCount() >= 1
    }

    suspend fun isAppWiseDnsEnabled(uid: Int): Boolean {
        return rethinkDnsEndpointRepository.isAppWiseDnsEnabled(uid)
    }

    private suspend fun removeConnectionStatus() {
        when (getDnsType()) {
            DnsType.DOH -> {
                doHEndpointRepository.removeConnectionStatus()
            }
            DnsType.DOT -> {
                doTEndpointRepository.removeConnectionStatus()
            }
            DnsType.ODOH -> {
                oDoHEndpointRepository.removeConnectionStatus()
            }
            DnsType.DNSCRYPT -> {
                dnsCryptEndpointRepository.removeConnectionStatus()
                dnsCryptRelayEndpointRepository.removeConnectionStatus()
            }
            DnsType.DNS_PROXY -> {
                dnsProxyEndpointRepository.removeConnectionStatus()
            }
            DnsType.RETHINK_REMOTE -> {
                rethinkDnsEndpointRepository.removeConnectionStatus()
            }
            DnsType.SYSTEM_DNS -> {
                // no-op, no need to remove connection status
            }
            DnsType.SMART_DNS -> {
                // no-op, no need to remove connection status
            }
        }
    }

    suspend fun removeAppWiseDns(uid: Int) {
        rethinkDnsEndpointRepository.removeAppWiseDns(uid)
    }

    suspend fun insertDnscryptRelayEndpoint(endpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDnscryptEndpoint(endpoint: DnsCryptEndpoint) {
        dnsCryptEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDohEndpoint(endpoint: DoHEndpoint) {
        doHEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDoTEndpoint(endpoint: DoTEndpoint) {
        doTEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertODoHEndpoint(endpoint: ODoHEndpoint) {
        oDoHEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertReplaceEndpoint(endpoint: RethinkDnsEndpoint) {
        rethinkDnsEndpointRepository.insertWithReplace(endpoint)
    }

    suspend fun updateRethinkEndpoint(name: String, url: String, count: Int) {
        rethinkDnsEndpointRepository.updateEndpoint(name, url, count)
    }

    suspend fun insertDnsproxyEndpoint(endpoint: DnsProxyEndpoint) {
        dnsProxyEndpointRepository.insertAsync(endpoint)
    }

    suspend fun deleteDohEndpoint(id: Int) {
        doHEndpointRepository.deleteDoHEndpoint(id)
    }

    suspend fun deleteDoTEndpoint(id: Int) {
        doTEndpointRepository.deleteDoTEndpoint(id)
    }

    suspend fun deleteODoHEndpoint(id: Int) {
        oDoHEndpointRepository.deleteODoHEndpoint(id)
    }

    suspend fun deleteDnsProxyEndpoint(id: Int) {
        dnsProxyEndpointRepository.deleteDnsProxyEndpoint(id)
    }

    suspend fun deleteDnscryptEndpoint(id: Int) {
        dnsCryptEndpointRepository.deleteDNSCryptEndpoint(id)
    }

    suspend fun deleteDnscryptRelayEndpoint(id: Int) {
        dnsCryptRelayEndpointRepository.deleteDnsCryptRelayEndpoint(id)
    }

    // -- Proxy Manager --

    fun addProxy(proxyType: ProxyType, provider: ProxyProvider) {
        // When there is a request of add with proxy type or provider as none
        // then remove all the proxies.
        if (proxyType == ProxyType.NONE || provider == ProxyProvider.NONE) {
            removeAllProxies()
            return
        }

        if (provider == ProxyProvider.WIREGUARD) {
            setProxy(proxyType, provider)
            return
        }

        if (provider == ProxyProvider.ORBOT) {
            setProxy(proxyType, provider)
            return
        }

        if (provider == ProxyProvider.TCP) {
            setProxy(proxyType, provider)
            return
        }

        // If add proxy request is custom proxy (either http/socks5), check if the other
        // proxy is already set. if yes, then make the proxy type as HTTP_SOCKS5.
        val currentProxyType = ProxyType.of(getProxyType())
        if (proxyType.isProxyTypeHttp()) {
            if (currentProxyType.isProxyTypeSocks5()) {
                setProxy(ProxyType.HTTP_SOCKS5, provider)
                return
            }
            setProxy(ProxyType.HTTP, provider)
            return
        }

        if (proxyType.isProxyTypeSocks5()) {
            if (currentProxyType.isProxyTypeHttp()) {
                setProxy(ProxyType.HTTP_SOCKS5, provider)
                return
            }
            setProxy(ProxyType.SOCKS5, provider)
            return
        }
    }

    fun removeAllProxies() {
        removeOrbot()
        persistentState.proxyProvider = ProxyProvider.NONE.name
        persistentState.proxyType = ProxyType.NONE.name
        persistentState.updateProxyStatus()
    }

    private fun removeOrbot() {
        OrbotHelper.selectedProxyType = ProxyType.NONE.name
    }

    fun removeProxy(removeType: ProxyType, removeProvider: ProxyProvider) {
        val currentProxyType = ProxyType.of(getProxyType())

        if (currentProxyType.isProxyTypeHttpSocks5()) {
            if (removeType.isProxyTypeHttp()) {
                setProxy(ProxyType.SOCKS5, removeProvider)
                return
            } else if (removeType.isProxyTypeSocks5()) {
                setProxy(ProxyType.HTTP, removeProvider)
                return
            } else {
                Logger.w(
                    LOG_TAG_VPN,
                    "invalid remove proxy call, type: ${removeType.name}, provider: ${removeProvider.name}"
                )
            }
        } else {
            removeAllProxies()
        }
    }

    fun getProxyType(): String {
        return persistentState.proxyType
    }

    fun getProxyProvider(): String {
        return persistentState.proxyProvider
    }

    private fun setProxy(type: ProxyType, provider: ProxyProvider) {
        persistentState.proxyProvider = provider.name
        persistentState.proxyType = type.name
        persistentState.updateProxyStatus()
    }

    // Returns the proxymode to set in Tunnel.
    // Settings.ProxyModeNone
    // Settings.ProxyModeSOCKS5
    // Settings.ProxyModeHTTPS
    fun getTunProxyMode(): TunProxyMode {
        val type = persistentState.proxyType
        val provider = persistentState.proxyProvider
        Logger.d(LOG_TAG_VPN, "selected proxy type: $type, with provider as $provider")

        if (ProxyProvider.WIREGUARD.name == provider) {
            return TunProxyMode.WIREGUARD
        }

        if (ProxyProvider.ORBOT.name == provider) {
            return TunProxyMode.ORBOT
        }

        when (type) {
            ProxyType.HTTP.name -> {
                return TunProxyMode.HTTPS
            }
            ProxyType.SOCKS5.name -> {
                return TunProxyMode.SOCKS5
            }
            ProxyType.HTTP_SOCKS5.name -> {
                // FIXME: tunnel does not support both http and socks5 at once.
                return TunProxyMode.SOCKS5
            }
        }
        return TunProxyMode.NONE
    }

    fun isCustomHttpProxyEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        // return false if the proxy provider is not custom
        if (!proxyProvider.isProxyProviderCustom()) return false

        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isProxyTypeHttp() || proxyType.isProxyTypeHttpSocks5()
    }

    fun isCustomSocks5Enabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        // return false if the proxy provider is not custom
        if (!proxyProvider.isProxyProviderCustom()) return false

        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isProxyTypeSocks5() || proxyType.isProxyTypeHttpSocks5()
    }

    fun isOrbotProxyEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return proxyProvider.isProxyProviderOrbot()
    }

    fun isWgEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return proxyProvider.isProxyProviderWireguard()
    }

    fun isProxyEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        if (proxyProvider.isProxyProviderNone()) return false

        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isAnyProxyEnabled()
    }

    fun canEnableProxy(): Boolean {
        return !getBraveMode().isDnsMode()
    }

    fun canEnableSocks5Proxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return !getBraveMode().isDnsMode() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderCustom())
    }

    fun canEnableWireguardProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return !getBraveMode().isDnsMode() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderWireguard())
    }

    fun canEnableHttpProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return !getBraveMode().isDnsMode() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderCustom())
    }

    fun canEnableTcpProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return !getBraveMode().isDnsMode() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderTcp())
    }

    fun canEnableOrbotProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return canEnableProxy() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderOrbot())
    }

    suspend fun getConnectedSocks5Proxy(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedSocks5Proxy()
    }

    suspend fun getConnectedHttpProxy(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedHttpProxy()
    }

    suspend fun updateCustomSocks5Proxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
        customSocks5Endpoint = proxyEndpoint
        addProxy(ProxyType.SOCKS5, ProxyProvider.CUSTOM)
    }

    suspend fun updateOrbotProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
        orbotEndpoint = proxyEndpoint
    }

    suspend fun updateCustomHttpProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
        customHttpEndpoint = proxyEndpoint
        addProxy(ProxyType.HTTP, ProxyProvider.CUSTOM)
    }

    suspend fun updateOrbotHttpProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
    }

    fun stats(): String {
        val sb = StringBuilder()
        sb.append("   Brave mode: ${getBraveMode()}\n")
        sb.append("   DNS type: ${getDnsType()}\n")
        sb.append("   Proxy type: ${ProxyType.of(getProxyType()).name}\n")
        sb.append("   Proxy provider: ${getProxyProvider()}\n")
        sb.append("   Pcap mode: ${getPcapFilePath()}\n")
        sb.append("   Connected DNS: ${persistentState.connectedDnsName}\n")
        sb.append("   Prevent DNS leaks: ${persistentState.preventDnsLeaks}\n")
        sb.append("   Internet protocol: ${getInternetProtocol()}\n")
        sb.append("   Protocol translation mode: ${getProtocolTranslationMode()}\n")

        return sb.toString()
    }

    suspend fun getLeastLoggedNetworkLogs(): Long {
        val a =
            if (getBraveMode().isDnsMode()) {
                dnsLogs.getLeastLoggedTime()
            } else {
                networkLogs.getLeastLoggedTime()
            }
        return a
    }

    val networkLogsCount: LiveData<Long> = networkLogs.logsCount()

    val dnsLogsCount: LiveData<Long> = dnsLogs.logsCount()

    val connectedProxy: LiveData<ProxyEndpoint?> =
        proxyEndpointRepository.getConnectedProxyLiveData()
}
