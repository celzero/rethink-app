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

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_RD
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TD
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.InternetProtocol.Companion.getInternetProtocol
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.KnownPorts.Companion.DNS_PORT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities
import dnsx.BraveDNS
import dnsx.Dnsx
import inet.ipaddr.IPAddressString
import intra.Listener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import protect.Blocker
import settings.Settings
import java.net.InetAddress

class AppConfig internal constructor(private val context: Context,
                                     private val rethinkDnsEndpointRepository: RethinkDnsEndpointRepository,
                                     private val dnsProxyEndpointRepository: DnsProxyEndpointRepository,
                                     private val doHEndpointRepository: DoHEndpointRepository,
                                     private val dnsCryptEndpointRepository: DnsCryptEndpointRepository,
                                     private val dnsCryptRelayEndpointRepository: DnsCryptRelayEndpointRepository,
                                     private val proxyEndpointRepository: ProxyEndpointRepository,
                                     private val persistentState: PersistentState) {
    private var appTunDnsMode: TunDnsMode = TunDnsMode.NONE
    private var systemDns: SystemDns = SystemDns("", DNS_PORT)
    private var braveModeObserver: MutableLiveData<Int> = MutableLiveData()
    private var braveDns: BraveDNS? = null

    companion object {
        private var connectedDns: MutableLiveData<String> = MutableLiveData()
        var dnscryptRelaysToRemove: String = ""

        private const val PROXY_MODE_ORBOT = 10L
        private const val ORBOT_DNS = "Orbot"
    }

    init {
        connectedDns.postValue(persistentState.connectedDnsName)
        setDnsMode()
        createBraveDnsObjectIfNeeded()
    }

    private fun createBraveDnsObjectIfNeeded() {
        if (!persistentState.blocklistEnabled) return

        try {
            // FIXME: canonical path may go missing but is unhandled
            val path: String = Utilities.localBlocklistDownloadBasePath(context,
                                                                        LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                                        persistentState.localBlocklistTimestamp)
            braveDns = Dnsx.newBraveDNSLocal(path + ONDEVICE_BLOCKLIST_FILE_TD,
                                             path + ONDEVICE_BLOCKLIST_FILE_RD,
                                             path + ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG,
                                             path + ONDEVICE_BLOCKLIST_FILE_TAG)
        } catch (e: Exception) {
            // Set local blocklist enabled to false and reset the timestamp
            // if there is a failure creating bravedns
            persistentState.blocklistEnabled = false
            Log.e(LOG_TAG_VPN, "Local brave dns set exception :${e.message}", e)
            // Set local blocklist enabled to false and reset the timestamp
            // or corrupted. Reset local blocklist timestamp to make sure
            // user is prompted to download blocklists again on the next try
            persistentState.localBlocklistTimestamp = INIT_TIME_MS
        }
    }

    fun getBraveDnsObj(): BraveDNS? {
        if (braveDns == null) {
            createBraveDnsObjectIfNeeded()
        }

        return braveDns
    }

    data class TunnelOptions(val tunDnsMode: TunDnsMode, val tunFirewallMode: TunFirewallMode,
                             val tunProxyMode: TunProxyMode, val ptMode: ProtoTranslationMode,
                             val blocker: Blocker, val listener: Listener, val fakeDns: String,
                             val preferredEngine: InternetProtocol)

    enum class BraveMode(val mode: Int) {
        DNS(0), FIREWALL(1), DNS_FIREWALL(2);

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

    enum class TunFirewallMode(val mode: Long) {
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
        DOH(1), DNSCRYPT(2), DNS_PROXY(3), RETHINK_REMOTE(4), NETWORK_DNS(5);

        fun isDnsProxy(): Boolean {
            return this == DNS_PROXY
        }

        fun isNetworkDns(): Boolean {
            return this == NETWORK_DNS
        }
    }

    enum class TunDnsMode(val mode: Long) {
        NONE(Settings.DNSModeNone),
        DOH_IP(Settings.DNSModeIP),
        DOH_PORT(Settings.DNSModePort),
        DNSCRYPT_IP(Settings.DNSModeCryptIP),
        DNSCRYPT_PORT(Settings.DNSModeCryptPort),
        DNSPROXY_IP(Settings.DNSModeProxyIP),
        DNSPROXY_PORT(Settings.DNSModeProxyPort);

        fun isDoh(): Boolean {
            return mode == DOH_IP.mode || mode == DOH_PORT.mode
        }

        fun isDnscrypt(): Boolean {
            return mode == DNSCRYPT_IP.mode || mode == DNSCRYPT_PORT.mode
        }

        fun isDnsProxy(): Boolean {
            return mode == DNSPROXY_IP.mode || mode == DNSPROXY_PORT.mode
        }

        fun isRethinkRemote(): Boolean {
            return mode == DOH_IP.mode || mode == DOH_PORT.mode
        }

        fun isSystemDns(): Boolean {
            return mode == DNSPROXY_IP.mode || mode == DNSPROXY_PORT.mode
        }

        fun isNone(): Boolean {
            return mode == NONE.mode
        }

        fun trapIP(): Boolean {
            return mode == DOH_IP.mode || mode == DNSCRYPT_IP.mode || mode == DNSPROXY_IP.mode
        }

        fun trapPort(): Boolean {
            return mode == DOH_PORT.mode || mode == DNSCRYPT_PORT.mode || mode == DNSPROXY_PORT.mode
        }
    }

    enum class TunProxyMode(val mode: Long) {
        NONE(Settings.ProxyModeNone),
        HTTPS(Settings.ProxyModeHTTPS),
        SOCKS5(Settings.ProxyModeSOCKS5),
        ORBOT(PROXY_MODE_ORBOT);

        fun isTunProxyOrbot(): Boolean {
            return mode == ORBOT.mode
        }

        fun isTunProxySocks5(): Boolean {
            return mode == SOCKS5.mode
        }

    }

    // Provider - Custom - SOCKS5, Http proxy setup.
    // ORBOT - One touch Orbot integration.
    enum class ProxyProvider {
        NONE, CUSTOM, ORBOT;

        fun isProxyProviderCustom(): Boolean {
            return CUSTOM.name == name
        }

        fun isProxyProviderNone(): Boolean {
            return NONE.name == name
        }

        fun isProxyProviderOrbot(): Boolean {
            return ORBOT.name == name
        }

        companion object {
            fun getProxyProvider(name: String): ProxyProvider {
                return when (name) {
                    CUSTOM.name -> CUSTOM
                    ORBOT.name -> ORBOT
                    else -> NONE
                }
            }
        }
    }

    // Supported Proxy types
    enum class ProxyType {
        NONE, HTTP, SOCKS5, HTTP_SOCKS5;

        fun isProxyTypeHttp(): Boolean {
            return HTTP.name == name
        }

        fun isProxyTypeSocks5(): Boolean {
            return SOCKS5.name == name
        }

        fun isProxyTypeHttpSocks5(): Boolean {
            return HTTP_SOCKS5.name == name
        }

        fun isProxyTypeNone(): Boolean {
            return NONE.name == name
        }

        companion object {
            fun of(name: String): ProxyType {
                return when (name) {
                    HTTP.name -> HTTP
                    SOCKS5.name -> SOCKS5
                    HTTP_SOCKS5.name -> HTTP_SOCKS5
                    else -> NONE
                }
            }
        }
    }

    enum class ProtoTranslationMode(val id: Long) {
        PTMODEAUTO(Settings.PtModeAuto),
        PTMODEFORCE64(Settings.PtModeForce64),
        PTMODEMAYBE46(Settings.PtModeMaybe46);
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


    data class SystemDns(var ipAddress: String, var port: Int)

    fun getFirewallMode(): TunFirewallMode {
        return determineFirewallMode()
    }

    private fun setFirewallMode() {
        determineFirewallMode()
    }

    fun getDnsType(): DnsType {
        return when (persistentState.dnsType) {
            DnsType.DOH.type -> DnsType.DOH
            DnsType.DNSCRYPT.type -> DnsType.DNSCRYPT
            DnsType.DNS_PROXY.type -> DnsType.DNS_PROXY
            DnsType.RETHINK_REMOTE.type -> DnsType.RETHINK_REMOTE
            DnsType.NETWORK_DNS.type -> DnsType.NETWORK_DNS
            else -> {
                Log.wtf(LOG_TAG_VPN, "Invalid dns type mode: ${persistentState.dnsType}")
                DnsType.DOH
            }
        }
    }

    private fun getDnsMode(): TunDnsMode {
        return appTunDnsMode
    }

    private fun setDnsMode() {
        // Case: app mode - firewall, DNS mode should be none.
        when (persistentState.braveMode) {
            BraveMode.FIREWALL.mode -> appTunDnsMode = TunDnsMode.NONE
            BraveMode.DNS.mode -> appTunDnsMode = determineTunDnsMode()
            BraveMode.DNS_FIREWALL.mode -> appTunDnsMode = determineTunDnsMode()
            else -> Log.wtf(LOG_TAG_VPN, "Invalid brave mode: ${persistentState.braveMode}")
        }
    }

    private fun determineTunDnsMode(): TunDnsMode {
        // app mode - DNS & DNS+Firewall mode
        return when (persistentState.dnsType) {
            DnsType.DOH.type -> {
                if (persistentState.preventDnsLeaks) {
                    TunDnsMode.DOH_PORT
                } else {
                    TunDnsMode.DOH_IP
                }
            }
            DnsType.DNSCRYPT.type -> {
                if (persistentState.preventDnsLeaks) {
                    TunDnsMode.DNSCRYPT_PORT
                } else {
                    TunDnsMode.DNSCRYPT_IP
                }
            }
            DnsType.DNS_PROXY.type -> {
                if (persistentState.preventDnsLeaks) {
                    TunDnsMode.DNSPROXY_PORT
                } else {
                    TunDnsMode.DNSPROXY_IP
                }
            }
            DnsType.RETHINK_REMOTE.type -> {
                if (persistentState.preventDnsLeaks) {
                    TunDnsMode.DOH_PORT
                } else {
                    TunDnsMode.DOH_IP
                }
            }
            DnsType.NETWORK_DNS.type -> {
                if (persistentState.preventDnsLeaks) {
                    TunDnsMode.DNSPROXY_PORT
                } else {
                    TunDnsMode.DNSPROXY_IP
                }
            }
            else -> {
                TunDnsMode.NONE
            }
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

    private suspend fun getDNSCryptServerCount(): Int {
        return dnsCryptEndpointRepository.getConnectedCount()
    }

    suspend fun getSocks5ProxyDetails(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedProxy()
    }

    suspend fun getOrbotProxyDetails(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedOrbotProxy()
    }

    private suspend fun getDNSProxyServerDetails(): DnsProxyEndpoint? {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun getDnscryptCountObserver(): LiveData<Int> {
        return dnsCryptEndpointRepository.getConnectedCountLiveData()
    }

    private suspend fun onDnsChange(dt: DnsType) {
        if (!isValidDnsType(dt)) return

        persistentState.dnsType = dt.type
        setDnsMode()
        when (dt) {
            DnsType.DOH -> {
                val endpoint = getDOHDetails() ?: return

                connectedDns.postValue(endpoint.dohName)
                persistentState.connectedDnsName = endpoint.dohName
            }
            DnsType.DNSCRYPT -> {
                val count = getDNSCryptServerCount()
                val relayCount = dnsCryptRelayEndpointRepository.getConnectedRelays()
                val text = context.getString(R.string.configure_dns_crypt, count.toString())
                connectedDns.postValue(text)
                persistentState.connectedDnsName = text
                persistentState.setDnsCryptRelayCount(relayCount.count())
            }
            DnsType.DNS_PROXY -> {
                val endpoint = getDNSProxyServerDetails() ?: return

                connectedDns.postValue(endpoint.proxyName)
                persistentState.connectedDnsName = endpoint.proxyName
            }
            DnsType.RETHINK_REMOTE -> {
                val endpoint = getRemoteRethinkEndpoint() ?: return

                connectedDns.postValue(endpoint.name)
                persistentState.setRemoteBlocklistCount(endpoint.blocklistCount)
                persistentState.connectedDnsName = endpoint.name
            }
            DnsType.NETWORK_DNS -> {
                connectedDns.postValue(context.getString(R.string.network_dns))
                persistentState.connectedDnsName = context.getString(R.string.network_dns)
            }
        }
    }

    private fun isValidDnsType(dt: DnsType): Boolean {
        return (dt == DnsType.DOH || dt == DnsType.DNSCRYPT || dt == DnsType.DNS_PROXY || dt == DnsType.RETHINK_REMOTE || dt == DnsType.NETWORK_DNS)
    }

    suspend fun switchRethinkDnsToMax() {
        rethinkDnsEndpointRepository.switchToMax()
        if (isRethinkDnsConnected()) {
            persistentState.connectedDnsName = ""
            onDnsChange(DnsType.RETHINK_REMOTE)
        }
    }

    suspend fun switchRethinkDnsToSky() {
        rethinkDnsEndpointRepository.switchToSky()
        if (isRethinkDnsConnected()) {
            persistentState.connectedDnsName = ""
            onDnsChange(DnsType.RETHINK_REMOTE)
        }
    }

    fun getConnectedDns(): String {
        return persistentState.connectedDnsName
    }

    suspend fun changeBraveMode(braveMode: Int) {
        persistentState.braveMode = braveMode
        braveModeObserver.postValue(braveMode)
        setDnsMode()
        setFirewallMode()
    }

    fun getBraveMode(): BraveMode {
        return when (persistentState.braveMode) {
            BraveMode.DNS.mode -> BraveMode.DNS
            BraveMode.FIREWALL.mode -> BraveMode.FIREWALL
            BraveMode.DNS_FIREWALL.mode -> BraveMode.DNS_FIREWALL
            else -> BraveMode.DNS_FIREWALL
        }
    }

    private fun determineFirewallMode(): TunFirewallMode {
        // app mode - DNS, set the firewall mode as NONE.
        if (persistentState.braveMode == BraveMode.DNS.mode) {
            return TunFirewallMode.NONE
        }

        // app mode - Firewall & DNS+Firewall
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            TunFirewallMode.FILTER_ANDROID8_BELOW
        } else {
            TunFirewallMode.FILTER_ANDROID9_ABOVE
        }
    }

    fun newTunnelOptions(blocker: Blocker, listener: Listener, fakeDns: String,
                         preferredEngine: InternetProtocol,
                         ptMode: ProtoTranslationMode): TunnelOptions {
        return TunnelOptions(getDnsMode(), getFirewallMode(), getTunProxyMode(), ptMode, blocker,
                             listener, fakeDns, preferredEngine)
    }

    // -- DNS Manager --
    suspend fun getConnectedProxyDetails(): DnsProxyEndpoint? {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    suspend fun getDnscryptServers(): String {
        return dnsCryptEndpointRepository.getServersToAdd()
    }

    suspend fun getDnscryptRelayServers(): String {
        return dnsCryptRelayEndpointRepository.getServersToAdd()
    }

    suspend fun getDnscryptServersToRemove(): String {
        return dnsCryptEndpointRepository.getServersToRemove()
    }

    suspend fun getDohCount(): Int {
        return doHEndpointRepository.getCount()
    }

    suspend fun getRethinkCount(): Int {
        return rethinkDnsEndpointRepository.getCount()
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

    suspend fun updateDnscryptLiveServers(servers: String?) {
        // if the prev connection was not dnscrypt, then remove the connection status from database
        if (getDnsType() != DnsType.DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.updateConnectionStatus(servers)
        onDnsChange(DnsType.DNSCRYPT)
    }

    suspend fun handleDoHChanges(doHEndpoint: DoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != DnsType.DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.update(doHEndpoint)
        onDnsChange(DnsType.DOH)
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

    suspend fun handleOrbotDnsChange(dnsProxyEndpoint: DnsProxyEndpoint) {
        // if the prev connection was not dns proxy, then remove the connection status from database
        if (getDnsType() != DnsType.DNS_PROXY) {
            removeConnectionStatus()
        }

        dnsProxyEndpointRepository.update(dnsProxyEndpoint)
        onDnsChange(DnsType.DNS_PROXY)
    }

    suspend fun isOrbotDns(): Boolean {
        if (!getDnsType().isDnsProxy()) return false

        return dnsProxyEndpointRepository.getConnectedProxy()?.proxyName == ORBOT_DNS
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

    suspend fun canRemoveDnscrypt(dnsCryptEndpoint: DnsCryptEndpoint): Boolean {
        val list = dnsCryptEndpointRepository.getConnectedDNSCrypt()
        if (list.count() == 1 && list[0].dnsCryptURL == dnsCryptEndpoint.dnsCryptURL) {
            return false
        }
        return true
    }

    suspend fun handleDnsrelayChanges(endpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.update(endpoint)
        onDnsChange(DnsType.DNSCRYPT)
    }

    suspend fun setDefaultConnection() {
        if (getDnsType() != DnsType.RETHINK_REMOTE) {
            removeConnectionStatus()
        }

        rethinkDnsEndpointRepository.updateConnectionDefault()
        onDnsChange(DnsType.RETHINK_REMOTE)
    }

    suspend fun getRemoteRethinkEndpoint(): RethinkDnsEndpoint? {
        return rethinkDnsEndpointRepository.getConnectedEndpoint()
    }

    fun getRemoteBlocklistCount(): Int {
        return persistentState.getRemoteBlocklistCount()
    }

    suspend fun getRethinkPlusEndpoint(): RethinkDnsEndpoint {
        return rethinkDnsEndpointRepository.getRethinkPlusEndpoint()
    }

    suspend fun enableRethinkDnsPlus() {
        if (getDnsType() != DnsType.RETHINK_REMOTE) {
            removeConnectionStatus()
        }

        rethinkDnsEndpointRepository.setRethinkPlus()
        onDnsChange(DnsType.RETHINK_REMOTE)
    }

    fun isRethinkDnsConnectedv053x(): Boolean {
        return persistentState.connectedDnsName == context.getString(R.string.rethink_plus)
    }

    suspend fun updateRethinkPlusCountv053x(count: Int) {
        rethinkDnsEndpointRepository.updatePlusBlocklistCount(count)
    }

    fun isRethinkDnsConnected(): Boolean {
        return getDnsType() == DnsType.RETHINK_REMOTE
    }

    suspend fun setSystemDns(ip: String, port: Int) {
        if (getDnsType() != DnsType.NETWORK_DNS) {
            removeConnectionStatus()
        }

        systemDns.ipAddress = ip
        systemDns.port = port
        onDnsChange(DnsType.NETWORK_DNS)
    }

    suspend fun setSystemDns(dnsServers: List<InetAddress>) {
        var dnsIp: String? = null

        when (getInternetProtocol()) {
            InternetProtocol.IPv4 -> {
                run loop@{
                    dnsServers.forEach {
                        if (IPAddressString(it.hostAddress).isIPv4) {
                            dnsIp = it.hostAddress
                            return@loop
                        }
                    }
                }
            }
            InternetProtocol.IPv6 -> {
                run loop@{
                    dnsServers.forEach {
                        if (IPAddressString(it.hostAddress).isIPv6) {
                            dnsIp = it.hostAddress
                            return@loop
                        }
                    }
                }
            }
            InternetProtocol.IPv46 -> {
                dnsIp = dnsServers[0].hostAddress
            }
        }

        if (dnsIp.isNullOrEmpty()) {
            dnsIp = dnsServers[0].hostAddress
        }

        dnsIp?.let { setSystemDns(it, KnownPorts.DNS_PORT) }
    }

    fun getSystemDns(): SystemDns {
        return systemDns
    }

    fun isSystemDns(): Boolean {
        return getDnsType().isNetworkDns()
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
            DnsType.NETWORK_DNS -> {
                // set the system dns object with empty ip address
                setSystemDns("", DNS_PORT)
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

    suspend fun deleteRethinkEndpoint(name: String, url: String, uid: Int) {
        rethinkDnsEndpointRepository.deleteRethinkEndpoint(name, url, uid)
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

    fun canEnableDnsBypassFirewallSetting(): Boolean {
        return getBraveMode().isDnsFirewallMode()
    }

    // -- Proxy Manager --

    fun addProxy(proxyType: ProxyType, provider: ProxyProvider) {
        // When there is a request of add with proxy type or provider as none
        // then remove all the proxies.
        if (proxyType == ProxyType.NONE || provider == ProxyProvider.NONE) {
            removeAllProxies()
            return
        }

        if (provider == ProxyProvider.ORBOT) {
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
                Log.w(LOG_TAG_VPN,
                      "invalid remove proxy call, type: ${removeType.name}, provider: ${removeProvider.name}")
            }
        } else {
            removeAllProxies()
        }
    }

    fun getProxyType(): String {
        return persistentState.proxyType
    }

    private fun setProxy(type: ProxyType, provider: ProxyProvider) {
        persistentState.proxyProvider = provider.name
        persistentState.proxyType = type.name
    }

    // Returns the proxymode to set in Tunnel.
    // Settings.ProxyModeNone
    // Settings.ProxyModeSOCKS5
    // Settings.ProxyModeHTTPS
    private fun getTunProxyMode(): TunProxyMode {
        val type = persistentState.proxyType
        val provider = persistentState.proxyProvider
        if (DEBUG) Log.d(LOG_TAG_VPN, "selected proxy type: $type, with provider as $provider")

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

    fun isProxyEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        if (proxyProvider.isProxyProviderNone()) return false

        val proxyType = ProxyType.of(persistentState.proxyType)
        return !proxyType.isProxyTypeNone()
    }

    fun canEnableProxy(): Boolean {
        return !getBraveMode().isDnsMode() && !VpnController.isVpnLockdown()
    }

    fun canEnableSocks5Proxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return canEnableProxy() && (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderCustom())
    }

    fun canEnableHttpProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return canEnableProxy() && (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderCustom())
    }

    fun canEnableOrbotProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return canEnableProxy() && (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderOrbot())
    }

    suspend fun getConnectedSocks5Proxy(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedProxy()
    }

    suspend fun insertCustomHttpProxy(host: String, port: Int) {
        persistentState.httpProxyHostAddress = host
        persistentState.httpProxyPort = port
    }

    suspend fun insertCustomSocks5Proxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.clearAllData()
        proxyEndpointRepository.insert(proxyEndpoint)
        addProxy(ProxyType.SOCKS5, ProxyProvider.CUSTOM)
    }

    suspend fun insertOrbotProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.clearOrbotData()
        proxyEndpointRepository.insert(proxyEndpoint)
    }

    val connectedProxy: LiveData<ProxyEndpoint> = liveData {
        withContext(Dispatchers.IO) {
            proxyEndpointRepository.getConnectedProxy()?.let { emit(it) }
        }
    }
}
