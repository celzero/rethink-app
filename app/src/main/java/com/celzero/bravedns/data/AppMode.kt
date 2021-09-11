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
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_PROXY
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import settings.Settings

class AppMode internal constructor(private val context: Context,
                                   private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
                                   private val doHEndpointRepository: DoHEndpointRepository,
                                   private val dnsCryptEndpointRepository: DNSCryptEndpointRepository,
                                   private val dnsCryptRelayEndpointRepository: DNSCryptRelayEndpointRepository,
                                   private val proxyEndpointRepository: ProxyEndpointRepository,
                                   private val persistentState: PersistentState) {
    private var appDnsMode: DnsMode = DnsMode.NONE
    var braveModeObserver: MutableLiveData<Int> = MutableLiveData()

    companion object {
        private var connectedDNS: MutableLiveData<String> = MutableLiveData()
        var cryptRelayToRemove: String = ""

        fun isFirewallActive(mode: TunnelOptions): Boolean {
            return mode.dnsMode == DnsMode.NONE && mode.firewallMode != FirewallMode.NONE
        }
    }

    init {
        connectedDNS.postValue(persistentState.connectedDnsName)
    }

    data class TunnelOptions(val dnsMode: DnsMode, val firewallMode: FirewallMode,
                             val proxyMode: ProxyMode)

    enum class BraveMode(val mode: Int) {
        DNS(0), FIREWALL(1), DNS_FIREWALL(2);

        fun isFirewallActive(): Boolean {
            return mode == FIREWALL.mode || mode == DNS_FIREWALL.mode
        }

        fun isDnsActive(): Boolean {
            return mode == DNS.mode || mode == DNS_FIREWALL.mode
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

    }

    enum class FirewallMode(val mode: Long) {
        DEFAULT(Settings.BlockModeFilter),
        SINK(Settings.BlockModeSink),
        PROCFS(Settings.BlockModeFilterProc),
        NONE(Settings.BlockModeNone);

        fun isFirewallSinkMode(): Boolean {
            return mode == SINK.mode
        }
    }

    enum class DnsMode(val mode: Long) {
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

    enum class ProxyMode(val mode: Long) {
        NONE(Settings.ProxyModeNone),
        HTTPS(Settings.ProxyModeHTTPS),
        SOCKS5(Settings.ProxyModeSOCKS5),
        ORBOT(Constants.ORBOT_PROXY);

        fun isOrbotProxy(): Boolean {
            return mode == ORBOT.mode
        }

        fun isCustomSocks5Proxy(): Boolean {
            return mode == SOCKS5.mode
        }

        fun isCustomHttpsProxy(): Boolean {
            return mode == HTTPS.mode
        }
    }

    // Provider - Custom - SOCKS5, Http proxy setup.
    // ORBOT - One touch Orbot integration.
    enum class ProxyProvider {
        NONE, CUSTOM, ORBOT
    }

    // Supported Proxy types
    enum class ProxyType {
        NONE, HTTP, SOCKS5, HTTP_SOCKS5
    }

    fun getFirewallMode(): FirewallMode {
        return determineFirewallMode()
    }

    private fun setFirewallMode() {
        determineFirewallMode()
    }

    fun getDnsType(): Int {
        return persistentState.dnsType
    }

    private suspend fun getDnsMode(): DnsMode {
        setDnsMode()
        return appDnsMode
    }

    private suspend fun setDnsMode() {
        // Case: app mode - firewall, DNS mode should be none.
        when (persistentState.braveMode) {
            BraveMode.FIREWALL.mode -> appDnsMode = DnsMode.NONE
            BraveMode.DNS.mode -> appDnsMode = determineDnsMode()
            BraveMode.DNS_FIREWALL.mode -> appDnsMode = determineDnsMode()
            else -> Log.wtf(LOG_TAG_VPN, "Invalid brave mode: ${persistentState.braveMode}")
        }
    }

    private suspend fun determineDnsMode(): DnsMode {
        // app mode - DNS & DNS+Firewall mode
        return when (persistentState.dnsType) {
            PREF_DNS_MODE_DOH -> {
                if (persistentState.preventDnsLeaks) {
                    DnsMode.DOH_PORT
                } else {
                    DnsMode.DOH_IP
                }
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                if (persistentState.preventDnsLeaks) {
                    DnsMode.DNSCRYPT_PORT
                } else {
                    DnsMode.DNSCRYPT_IP
                }
            }
            PREF_DNS_MODE_PROXY -> {
                if (persistentState.preventDnsLeaks) {
                    DnsMode.DNSPROXY_PORT
                } else {
                    DnsMode.DNSPROXY_IP
                }
            }
            else -> {
                DnsMode.NONE
            }
        }
    }

    fun isDnsProxyActive(): Boolean {
        return PREF_DNS_MODE_PROXY == getDnsType()
    }

    fun getConnectedDnsObservable(): MutableLiveData<String> {
        return connectedDNS
    }

    fun getDOHDetails(): DoHEndpoint? {
        return doHEndpointRepository.getConnectedDoH()
    }

    private fun getDNSCryptServerCount(): Int {
        return dnsCryptEndpointRepository.getConnectedCount()
    }

    suspend fun getSocks5ProxyDetails(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedProxy()
    }

    suspend fun getOrbotProxyDetails(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedOrbotProxy()
    }

    private suspend fun getDNSProxyServerDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun getDnscryptCountObserver(): LiveData<Int> {
        return dnsCryptEndpointRepository.getConnectedCountLiveData()
    }

    private suspend fun onDnsChange(dt: Int) {
        if (!isValidDnsType(dt)) return

        persistentState.dnsType = dt
        setDnsMode()
        when (dt) {
            PREF_DNS_MODE_DOH -> {
                val endpoint = getDOHDetails()
                if (endpoint != null) {
                    connectedDNS.postValue(endpoint.dohName)
                    persistentState.connectedDnsName = endpoint.dohName
                }
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                val count = getDNSCryptServerCount()
                val relayCount = dnsCryptRelayEndpointRepository.getConnectedRelays()
                val text = context.getString(R.string.configure_dns_crypt, count.toString())
                connectedDNS.postValue(text)
                persistentState.connectedDnsName = text
                persistentState.setDnsCryptRelayCount(relayCount.size)
            }
            PREF_DNS_MODE_PROXY -> {
                val endpoint = getDNSProxyServerDetails()
                connectedDNS.postValue(endpoint.proxyName)
                persistentState.connectedDnsName = endpoint.proxyName
            }
            else -> {
                Log.wtf(LOG_TAG_VPN, "Invalid set request for dns type: $dt")
            }
        }
    }

    private fun isValidDnsType(dt: Int): Boolean {
        return (dt == PREF_DNS_MODE_DOH || dt == PREF_DNS_MODE_DNSCRYPT || dt == PREF_DNS_MODE_PROXY)
    }

    fun getConnectedDNS(): String {
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

    private fun determineFirewallMode(): FirewallMode {
        // app mode - DNS, set the firewall mode as NONE.
        if (persistentState.braveMode == BraveMode.DNS.mode) {
            return FirewallMode.NONE
        }

        // app mode - Firewall & DNS+Firewall
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            FirewallMode.PROCFS
        } else {
            FirewallMode.DEFAULT
        }
    }

    suspend fun newTunnelMode(): TunnelOptions {
        return TunnelOptions(getDnsMode(), getFirewallMode(), getProxyMode())
    }

    // -- DNS Manager --
    suspend fun getConnectedProxyDetails(): DNSProxyEndpoint {
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
        if (getDnsType() != PREF_DNS_MODE_DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.updateConnectionStatus(servers)
        onDnsChange(PREF_DNS_MODE_DNSCRYPT)
    }

    suspend fun handleDoHChanges(doHEndpoint: DoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != PREF_DNS_MODE_DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.update(doHEndpoint)
        onDnsChange(PREF_DNS_MODE_DOH)
    }

    suspend fun handleDnsProxyChanges(dnsProxyEndpoint: DNSProxyEndpoint) {
        // if the prev connection was not dns proxy, then remove the connection status from database
        if (getDnsType() != PREF_DNS_MODE_PROXY) {
            removeConnectionStatus()
        }

        dnsProxyEndpointRepository.update(dnsProxyEndpoint)
        onDnsChange(PREF_DNS_MODE_PROXY)
    }

    suspend fun handleDnscryptChanges(dnsCryptEndpoint: DNSCryptEndpoint) {
        // if the prev connection was not dnscrypt, then remove the connection status from database
        if (getDnsType() != PREF_DNS_MODE_DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.update(dnsCryptEndpoint)
        onDnsChange(PREF_DNS_MODE_DNSCRYPT)
    }

    suspend fun canRemoveDnscrypt(dnsCryptEndpoint: DNSCryptEndpoint): Boolean {
        val list = dnsCryptEndpointRepository.getConnectedDNSCrypt()
        if (list.size == 1 && list[0].dnsCryptURL == dnsCryptEndpoint.dnsCryptURL) {
            return false
        }
        return true
    }

    suspend fun handleDnsrelayChanges(endpoint: DNSCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.update(endpoint)
        onDnsChange(PREF_DNS_MODE_DNSCRYPT)
    }

    suspend fun setDefaultConnection() {
        if (getDnsType() != PREF_DNS_MODE_DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.updateConnectionDefault()
        onDnsChange(PREF_DNS_MODE_DOH)
    }

    suspend fun getDnsRethinkEndpoint(): DoHEndpoint {
        return doHEndpointRepository.getRethinkDnsEndpoint()
    }

    fun getRemoteBlocklistCount(): Int {
        return persistentState.getRemoteBlocklistCount()
    }

    fun isRethinkDnsPlusUrl(dohName: String): Boolean {
        return Constants.RETHINK_DNS_PLUS == dohName
    }

    suspend fun updateRethinkDnsPlusStamp(stamp: String, count: Int) {
        removeConnectionStatus()
        doHEndpointRepository.updateConnectionURL(stamp)
        persistentState.setRemoteBlocklistCount(count)
        onDnsChange(PREF_DNS_MODE_DOH)
    }

    suspend fun isDnscryptRelaySelectable(): Boolean {
        return dnsCryptEndpointRepository.getConnectedCount() >= 1
    }

    private fun removeConnectionStatus() {
        when (getDnsType()) {
            PREF_DNS_MODE_DOH -> {
                doHEndpointRepository.removeConnectionStatus()
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                dnsCryptEndpointRepository.removeConnectionStatus()
                dnsCryptRelayEndpointRepository.removeConnectionStatus()
            }
            PREF_DNS_MODE_PROXY -> {
                dnsProxyEndpointRepository.removeConnectionStatus()
            }
        }
    }

    suspend fun insertDnscryptRelayEndpoint(endpoint: DNSCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDnscryptEndpoint(endpoint: DNSCryptEndpoint) {
        dnsCryptEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDohEndpoint(endpoint: DoHEndpoint) {
        doHEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDnsproxyEndpoint(endpoint: DNSProxyEndpoint) {
        dnsProxyEndpointRepository.insertAsync(endpoint)
    }

    suspend fun deleteDohEndpoint(id: Int) {
        doHEndpointRepository.deleteDoHEndpoint(id)
    }

    suspend fun deleteDnsProxyEndpoint(id: Int) {
        dnsProxyEndpointRepository.deleteDNSProxyEndpoint(id)
    }

    suspend fun deleteDnscryptEndpoint(id: Int) {
        dnsCryptEndpointRepository.deleteDNSCryptEndpoint(id)
    }

    suspend fun deleteDnscryptRelayEndpoint(id: Int) {
        dnsCryptRelayEndpointRepository.deleteDNSCryptRelayEndpoint(id)
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
        if (isProxyTypeHttp(proxyType)) {
            if (isSocks5ProxyEnabled()) {
                setProxy(ProxyType.HTTP_SOCKS5, provider)
                return
            }
            setProxy(ProxyType.HTTP, provider)
            return
        }

        if (isProxyTypeSocks5(proxyType)) {
            if (isHttpProxyEnabled()) {
                setProxy(ProxyType.HTTP_SOCKS5, provider)
                return
            }
            setProxy(ProxyType.SOCKS5, provider)
            return
        }
    }

    private fun isHttpProxyEnabled(): Boolean {
        return ProxyType.HTTP.name == getProxyType()
    }

    private fun isProxyTypeHttp(proxyType: ProxyType): Boolean {
        return ProxyType.HTTP == proxyType
    }

    private fun isProxyTypeSocks5(proxyType: ProxyType): Boolean {
        return ProxyType.SOCKS5 == proxyType
    }

    fun isProxyEnabled(): Boolean {
        return ProxyType.NONE.name != getProxyType()
    }

    private fun isSocks5ProxyEnabled(): Boolean {
        return ProxyType.SOCKS5.name == getProxyType()
    }

    private fun isHttpSocks5ProxyEnabled(): Boolean {
        return ProxyType.HTTP_SOCKS5.name == getProxyType()
    }

    fun removeAllProxies() {
        removeOrbot()
        persistentState.proxyType = ProxyType.NONE.name
        persistentState.proxyProvider = ProxyProvider.NONE.name
    }

    private fun removeOrbot() {
        OrbotHelper.selectedProxyType = ProxyType.NONE.name
    }

    fun removeProxy(proxyType: ProxyType, provider: ProxyProvider) {
        when (proxyType) {
            ProxyType.HTTP -> {
                if (isHttpSocks5ProxyEnabled()) {
                    setProxy(ProxyType.SOCKS5, provider)
                    return
                }
            }
            ProxyType.SOCKS5 -> {
                if (isHttpSocks5ProxyEnabled()) {
                    setProxy(ProxyType.HTTP, provider)
                    return
                }
            }
            ProxyType.HTTP_SOCKS5 -> {
                if (ProxyProvider.CUSTOM == provider) {
                    removeAllProxies()
                    return
                }
            }
            else -> {
                removeAllProxies()
                return
            }
        }
        removeAllProxies()
    }

    fun getProxyType(): String {
        return persistentState.proxyType
    }

    private fun setProxy(type: ProxyType, provider: ProxyProvider) {
        persistentState.proxyProvider = provider.name
        persistentState.proxyType = type.name
    }

    private fun getProxyProvider(): String {
        return persistentState.proxyProvider
    }

    // Returns the proxymode to set in Tunnel.
    // Settings.ProxyModeNone
    // Settings.ProxyModeSOCKS5
    // Settings.ProxyModeHTTPS
    fun getProxyMode(): ProxyMode {
        val type = getProxyType()
        val provider = getProxyProvider()
        if (DEBUG) Log.d(LOG_TAG_VPN, "selected proxy type: $type, with provider as $provider")

        if (ProxyProvider.ORBOT.name == provider) {
            return ProxyMode.ORBOT
        }

        when (type) {
            ProxyType.HTTP.name -> {
                return ProxyMode.HTTPS
            }
            ProxyType.SOCKS5.name -> {
                return ProxyMode.SOCKS5
            }
            ProxyType.HTTP_SOCKS5.name -> {
                return ProxyMode.SOCKS5
            }
        }
        return ProxyMode.NONE
    }

    fun isCustomHttpProxyEnabled(): Boolean {
        return ((getProxyType() == ProxyType.HTTP.name || getProxyType() == ProxyType.HTTP_SOCKS5.name) && getProxyProvider() == ProxyProvider.CUSTOM.name)
    }

    fun isCustomSocks5Enabled(): Boolean {
        return ((getProxyType() == ProxyType.SOCKS5.name || getProxyType() == ProxyType.HTTP_SOCKS5.name) && getProxyProvider() == ProxyProvider.CUSTOM.name)
    }

    fun isOrbotProxyEnabled(): Boolean {
        return getProxyProvider() == ProxyProvider.ORBOT.name
    }

    fun canEnableProxy(): Boolean {
        return !getBraveMode().isDnsMode() && !Utilities.isVpnLockdownEnabled(
            VpnController.getBraveVpnService())
    }

    fun canEnableSocks5Proxy(): Boolean {
        return canEnableProxy() && !isOrbotProxyEnabled()
    }

    fun canEnableHttpProxy(): Boolean {
        return canEnableProxy() && !isOrbotProxyEnabled()
    }

    fun canEnableOrbotProxy(): Boolean {
        return canEnableProxy() && !isSocks5ProxyEnabled() && !isHttpProxyEnabled()
    }

    fun isHttpProxyTypeEnabled(): Boolean {
        return (getProxyType() == ProxyType.HTTP.name || getProxyType() == ProxyType.HTTP_SOCKS5.name)
    }

    fun getConnectedSocks5Proxy(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedProxy()
    }

    fun insertCustomHttpProxy(host: String, port: Int) {
        persistentState.httpProxyHostAddress = host
        persistentState.httpProxyPort = port
    }

    fun insertCustomSocks5Proxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.clearAllData()
        proxyEndpointRepository.insert(proxyEndpoint)
        addProxy(ProxyType.SOCKS5, ProxyProvider.CUSTOM)
    }

    fun insertOrbotProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.clearOrbotData()
        proxyEndpointRepository.insert(proxyEndpoint)
    }

    val connectedProxy: LiveData<ProxyEndpoint> = liveData {
        withContext(Dispatchers.IO) {
            proxyEndpointRepository.getConnectedProxy()?.let { emit(it) }
        }
    }
}
