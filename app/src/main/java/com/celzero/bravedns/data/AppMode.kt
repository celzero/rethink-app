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
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_INVALID
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
    private var appDnsMode: Long = PREF_DNS_INVALID

    companion object {
        private var connectedDNS: MutableLiveData<String> = MutableLiveData()
        var cryptRelayToRemove: String = ""
    }

    enum class AppState(val state: Int) {
        PAUSE(10), ACTIVE(11)
    }

    data class TunnelMode(val dnsMode: Long, val firewallMode: Long, val proxyMode: Long)

    fun getFirewallMode(): Long {
        return persistentState.firewallMode.toLong()
    }

    fun setFirewallMode() {
        persistentState.firewallMode = determineFirewallMode().toInt()
    }

    fun getDnsType(): Int {
        return persistentState.dnsType
    }

    suspend fun getDnsMode(): Long {
        if (appDnsMode == PREF_DNS_INVALID) {
            setDnsMode()
        }

        return appDnsMode
    }

    fun setDnsMode() {
        appDnsMode = determineDnsMode()
    }

    private fun determineDnsMode(): Long {
        // Case: app mode - firewall, DNS mode should be none.
        if (persistentState.braveMode == APP_MODE_FIREWALL) {
            return Settings.DNSModeNone
        }

        // app mode - DNS & DNS+Firewall mode
        return when (persistentState.dnsType) {
            PREF_DNS_MODE_DOH -> {
                if (persistentState.allowDNSTraffic) {
                    Settings.DNSModePort
                } else {
                    Settings.DNSModeIP
                }
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                Settings.DNSModeCryptPort
            }
            PREF_DNS_MODE_PROXY -> {
                fetchProxyModeFromDb()
            }
            else -> {
                Settings.DNSModeNone
            }
        }
    }

    fun isFirewallActive(): Boolean {
        return persistentState.braveMode == APP_MODE_FIREWALL || persistentState.braveMode == APP_MODE_DNS_FIREWALL
    }

    fun isDnsActive(): Boolean {
        return persistentState.braveMode == APP_MODE_DNS || persistentState.braveMode == APP_MODE_DNS_FIREWALL
    }

    fun isDnsProxyActive(): Boolean {
        return PREF_DNS_MODE_PROXY == getDnsType()
    }

    fun isFirewallMode(): Boolean {
        return persistentState.braveMode == APP_MODE_FIREWALL
    }

    fun isDnsMode(): Boolean {
        return persistentState.braveMode == APP_MODE_DNS
    }

    fun isDnsFirewallMode(): Boolean {
        return persistentState.braveMode == APP_MODE_DNS_FIREWALL
    }

    private fun fetchProxyModeFromDb(): Long {
        val dnsProxy = dnsProxyEndpointRepository.getConnectedProxy()
        val proxyTypeInternal = "Internal"
        val proxyTypeExternal = "External"

        return when (dnsProxy.proxyType) {
            proxyTypeInternal -> {
                Settings.DNSModeProxyPort
            }
            proxyTypeExternal -> {
                Settings.DNSModeProxyIP
            }
            else -> {
                Settings.DNSModeProxyPort
            }
        }
    }

    fun getConnectedDnsObservable(): MutableLiveData<String> {
        connectedDNS.postValue(persistentState.connectedDnsName)
        return connectedDNS
    }

    fun getDOHDetails(): DoHEndpoint? {
        return doHEndpointRepository.getConnectedDoH()
    }

    fun getDNSCryptServerCount(): Int {
        return dnsCryptEndpointRepository.getConnectedCount()
    }

    fun getSocks5ProxyDetails(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedProxy()
    }

    fun getOrbotProxyDetails(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedOrbotProxy()
    }

    fun getDNSProxyServerDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun getDnscryptCountLiveDataObserver(): LiveData<Int> {
        return dnsCryptEndpointRepository.getConnectedCountLiveData()
    }

    // FIXME: Check if the below usage of variables can be reduced.
    private fun onNewDnsConnected(dt: Int) {
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
        setDnsMode()
        setFirewallMode()
    }

    fun getBraveMode(): Int {
        return persistentState.braveMode
    }

    private fun determineFirewallMode(): Long {
        // app mode - DNS, set the firewall mode as NONE.
        if (persistentState.braveMode == APP_MODE_DNS) {
            return Settings.BlockModeNone
        }

        // app mode - Firewall & DNS+Firewall
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Settings.BlockModeFilterProc
        } else {
            Settings.BlockModeFilter
        }
    }

    fun setAppState(appState: AppState) {
        persistentState.appState = appState.state
        persistentState.appStateLiveData.postValue(appState)
    }

    // Value stored in persistent state is of type Int (AppState.state)
    fun getAppState(): Int {
        return persistentState.appState
    }

    suspend fun makeTunnelDataClass(): TunnelMode {
        return TunnelMode(getDnsMode(), getFirewallMode(), getProxyMode())
    }

    // -- DNS Manager --

    fun getConnectedProxyDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun getDnscryptServers(): String {
        return dnsCryptEndpointRepository.getServersToAdd()
    }

    fun getDnscryptRelayServers(): String {
        return dnsCryptRelayEndpointRepository.getServersToAdd()
    }

    fun getDnscryptServersToRemove(): String {
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

    fun updateDnscryptLiveServers(servers: String) {
        // if the prev connection was not dnscrypt, then remove the connection status from database
        if (getDnsType() != PREF_DNS_MODE_DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.updateConnectionStatus(servers)
        onNewDnsConnected(PREF_DNS_MODE_DNSCRYPT)
    }

    suspend fun handleDoHChanges(doHEndpoint: DoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != PREF_DNS_MODE_DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.update(doHEndpoint)
        onNewDnsConnected(PREF_DNS_MODE_DOH)
    }

    suspend fun handleDnsProxyChanges(dnsProxyEndpoint: DNSProxyEndpoint) {
        // if the prev connection was not dns proxy, then remove the connection status from database
        if (getDnsType() != PREF_DNS_MODE_PROXY) {
            removeConnectionStatus()
        }

        dnsProxyEndpointRepository.update(dnsProxyEndpoint)
        onNewDnsConnected(PREF_DNS_MODE_PROXY)
    }

    suspend fun handleDnscryptChanges(dnsCryptEndpoint: DNSCryptEndpoint) {
        // if the prev connection was not dnscrypt, then remove the connection status from database
        if (getDnsType() != PREF_DNS_MODE_DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.update(dnsCryptEndpoint)
        onNewDnsConnected(PREF_DNS_MODE_DNSCRYPT)
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
        onNewDnsConnected(PREF_DNS_MODE_DNSCRYPT)
    }

    fun setDefaultConnection() {
        if (getDnsType() != PREF_DNS_MODE_DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.updateConnectionDefault()
        onNewDnsConnected(PREF_DNS_MODE_DOH)
    }

    suspend fun updateDnsRethinkPlusStamp(stamp: String) {
        removeConnectionStatus()
        doHEndpointRepository.updateConnectionURL(stamp)
        onNewDnsConnected(PREF_DNS_MODE_DOH)
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
    // TODO - Move the database operations related to proxy into AppMode.

    // Provider - Custom - SOCKS5, Http proxy setup.
    // ORBOT - One touch Orbot integration.
    enum class ProxyProvider {
        NONE, CUSTOM, ORBOT
    }

    // Supported Proxy types
    enum class ProxyType {
        NONE, HTTP, SOCKS5, HTTP_SOCKS5
    }

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

    private fun removeAllProxies() {
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
    fun getProxyMode(): Long {
        val type = getProxyType()
        val provider = getProxyProvider()
        if (DEBUG) Log.d(LOG_TAG_VPN, "selected proxy type: $type, with provider as $provider")

        if (ProxyProvider.ORBOT.name == provider) {
            return Constants.ORBOT_PROXY
        }

        when (type) {
            ProxyType.HTTP.name -> {
                return Settings.ProxyModeHTTPS
            }
            ProxyType.SOCKS5.name -> {
                return Settings.ProxyModeSOCKS5
            }
            ProxyType.HTTP_SOCKS5.name -> {
                return Settings.ProxyModeSOCKS5
            }
        }
        return Settings.ProxyModeNone
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

    private fun canEnableProxy(): Boolean {
        return !Utilities.isVpnLockdownEnabled(VpnController.getBraveVpnService())
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

    fun isOrbotProxy(): Boolean {
        return Constants.ORBOT_PROXY == getProxyMode()
    }

    fun isCustomSocks5(): Boolean {
        return Settings.ProxyModeSOCKS5 == getProxyMode()
    }

    fun getConnectedSocks5Proxy(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedProxy()
    }

    val connectedProxy: LiveData<ProxyEndpoint> = liveData {
        withContext(Dispatchers.IO) {
            proxyEndpointRepository.getConnectedProxy()?.let { emit(it) }
        }
    }

}
