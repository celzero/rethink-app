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
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.PersistentState
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
import com.celzero.bravedns.util.Utilities
import settings.Settings

class AppMode internal constructor(private val context: Context,
                                   private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
                                   private val doHEndpointRepository: DoHEndpointRepository,
                                   private val dnsCryptEndpointRepository: DNSCryptEndpointRepository,
                                   private val dnsCryptRelayEndpointRepository: DNSCryptRelayEndpointRepository,
                                   private val proxyEndpointRepository: ProxyEndpointRepository,
                                   private val persistentState: PersistentState) {
    private val INVALID_DNS_TYPE = -1
    private var appDNSMode: Long = PREF_DNS_INVALID
    private val proxyTypeInternal = "Internal"
    private val proxyTypeExternal = "External"
    private var dnsType: Int = INVALID_DNS_TYPE
    private var socks5ProxyEndpoint: ProxyEndpoint? = null

    companion object {
        private var connectedDNS: MutableLiveData<String> = MutableLiveData()
        var cryptRelayToRemove: String = ""
    }


    fun getDNSMode(): Long {
        if (appDNSMode != PREF_DNS_INVALID) return appDNSMode

        when (persistentState.dnsType) {
            PREF_DNS_MODE_DOH -> {
                appDNSMode = if (persistentState.allowDNSTraffic) {
                    Settings.DNSModePort
                } else {
                    Settings.DNSModeIP
                }
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                appDNSMode = Settings.DNSModeCryptPort
            }
            PREF_DNS_MODE_PROXY -> {
                appDNSMode = fetchProxyModeFromDb()
            }
        }
        return appDNSMode
    }

    fun getFirewallMode(): Long {
        return persistentState.firewallMode.toLong()
    }

    fun setFirewallMode(fMode: Long) {
        persistentState.firewallMode = fMode.toInt()
    }

    fun getDNSType(): Int {
        if (dnsType == INVALID_DNS_TYPE) {
            return persistentState.dnsType
        }
        return dnsType
    }

    fun setDNSMode(mode: Long) {
        appDNSMode = mode
    }

    fun isFirewallActive(): Boolean {
        return persistentState.braveMode == APP_MODE_FIREWALL || persistentState.braveMode == APP_MODE_DNS_FIREWALL
    }

    fun isDnsActive(): Boolean {
        return persistentState.braveMode == APP_MODE_DNS || persistentState.braveMode == APP_MODE_DNS_FIREWALL
    }

    fun isFirewallMode(): Boolean {
        return persistentState.braveMode == APP_MODE_FIREWALL
    }

    fun isDnsMode(): Boolean {
        return persistentState.braveMode == APP_MODE_DNS
    }

    fun isDnsProxyEnabled(): Boolean {
        return persistentState.dnsType == PREF_DNS_MODE_PROXY
    }

    private fun fetchProxyModeFromDb(): Long {
        val dnsProxy = dnsProxyEndpointRepository.getConnectedProxy()
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
        return connectedDNS
    }

    fun getDOHDetails(): DoHEndpoint? {
        return doHEndpointRepository.getConnectedDoH()
    }

    fun getDNSCryptServerCount(): Int {
        return dnsCryptEndpointRepository.getConnectedCount()
    }

    fun getSocks5ProxyDetails(): ProxyEndpoint? {
        if (socks5ProxyEndpoint == null) {
            socks5ProxyEndpoint = proxyEndpointRepository.getConnectedProxy()
        }
        return socks5ProxyEndpoint
    }

    fun getOrbotProxyDetails(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedOrbotProxy()
    }

    fun getDNSProxyServerDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    private fun onNewDnsConnected(dnsType: Int, dnsMode: Long) {
        if (PREF_DNS_MODE_DOH == dnsType) {
            persistentState.dnsType = dnsType
            setDNSMode(dnsMode)
            val endpoint = getDOHDetails()
            if (endpoint != null) {
                connectedDNS.postValue(endpoint.dohName)
                persistentState.setConnectedDns(endpoint.dohName)
            }
        } else if (PREF_DNS_MODE_DNSCRYPT == dnsType) {
            persistentState.dnsType = dnsType
            setDNSMode(dnsMode)
            val count = getDNSCryptServerCount()
            val relayCount = dnsCryptRelayEndpointRepository.getConnectedRelays()
            val text = context.getString(R.string.configure_dns_crypt, count.toString())
            connectedDNS.postValue(text)
            persistentState.setConnectedDns(text)
            persistentState.setDnsCryptRelayCount(relayCount.size)
        } else if (PREF_DNS_MODE_PROXY == dnsType) {
            persistentState.dnsType = dnsType
            setDNSMode(dnsMode)
            val endpoint = getDNSProxyServerDetails()
            connectedDNS.postValue(endpoint.proxyName)
            persistentState.setConnectedDns(endpoint.proxyName)
        }
    }

    // FIXME replace the below logic once the DNS data is streamlined.
    fun getConnectedDNS(): String {
        if (!connectedDNS.value.isNullOrEmpty()) {
            return connectedDNS.value!!
        }

        val dnsType = getDNSType()
        return if (PREF_DNS_MODE_DOH == dnsType) {
            val dohDetail: DoHEndpoint?
            try {
                dohDetail = getDOHDetails()
                dohDetail?.dohName!!
            } catch (e: Exception) { // FIXME - #320
                Log.e(LOG_TAG_VPN, "could not fetch connected DoH info from db: ${e.message}", e)
                persistentState.getConnectedDns()
            }
        } else if (PREF_DNS_MODE_DNSCRYPT == dnsType) {
            context.getString(R.string.configure_dns_crypt, getDNSCryptServerCount().toString())
        } else {
            val proxyDetails = getDNSProxyServerDetails()
            proxyDetails.proxyAppName!!
        }
    }

    fun changeBraveMode(braveMode: Int) {
        persistentState.braveMode = braveMode
        when (braveMode) {
            APP_MODE_DNS -> {
                setFirewallMode(Settings.BlockModeNone)
            }
            APP_MODE_FIREWALL -> {
                setDNSMode(Settings.DNSModeNone)
                setFirewallMode(determineFirewallMode())
            }
            APP_MODE_DNS_FIREWALL -> {
                setFirewallMode(determineFirewallMode())
            }
        }
    }

    fun getBraveMode(): Int {
        return persistentState.braveMode
    }

    fun syncBraveMode() {
        when (persistentState.braveMode) {
            APP_MODE_DNS -> {
                setFirewallMode(Settings.BlockModeNone)
            }
            APP_MODE_FIREWALL -> {
                setDNSMode(Settings.DNSModeNone)
                setFirewallMode(determineFirewallMode())
            }
            APP_MODE_DNS_FIREWALL -> {
                setFirewallMode(determineFirewallMode())
            }
        }
    }

    private fun determineFirewallMode(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Settings.BlockModeFilterProc
        } else {
            Settings.BlockModeFilter
        }
    }

    // -- DNS Manager --

    fun getConnectedProxyDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun getDnscryptServers(): String {
        return dnsCryptEndpointRepository.getServersToAdd()
    }

    fun getRelayServers(): String {
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

    suspend fun getRelayCount(): Int {
        return dnsCryptRelayEndpointRepository.getCount()
    }

    fun updateDnscryptLiveServers(servers: String) {
        // if the prev connection was not dnscrypt, then remove the connection status
        if (getDNSType() != PREF_DNS_MODE_DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.updateConnectionStatus(servers)
        onNewDnsConnected(PREF_DNS_MODE_DNSCRYPT, Settings.DNSModeCryptPort)
    }

    suspend fun handleDoHChanges(doHEndpoint: DoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status
        if (getDNSType() != PREF_DNS_MODE_DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.update(doHEndpoint)
        onNewDnsConnected(PREF_DNS_MODE_DOH, Settings.DNSModePort)
    }

    suspend fun handleDnsProxyChanges(dnsProxyEndpoint: DNSProxyEndpoint) {
        // if the prev connection was not dns proxy, then remove the connection status
        if (getDNSType() != PREF_DNS_MODE_PROXY) {
            removeConnectionStatus()
        }

        dnsProxyEndpointRepository.update(dnsProxyEndpoint)
        onNewDnsConnected(PREF_DNS_MODE_PROXY, Settings.DNSModeProxyIP)
    }

    suspend fun handleDnscryptChanges(dnsCryptEndpoint: DNSCryptEndpoint) {
        // if the prev connection was not dnscrypt, then remove the connection status
        if (getDNSType() != PREF_DNS_MODE_DNSCRYPT) {
            removeConnectionStatus()
        }

        dnsCryptEndpointRepository.update(dnsCryptEndpoint)
        onNewDnsConnected(PREF_DNS_MODE_DNSCRYPT, Settings.DNSModeCryptPort)
    }

    suspend fun isRemoveDnscryptAllowed(dnsCryptEndpoint: DNSCryptEndpoint): Boolean {
        val list = dnsCryptEndpointRepository.getConnectedDNSCrypt()
        if (list.size == 1 && list[0].dnsCryptURL == dnsCryptEndpoint.dnsCryptURL) {
            return false
        }
        return true
    }

    suspend fun handleDnsrelayChanges(endpoint: DNSCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.update(endpoint)
        onNewDnsConnected(PREF_DNS_MODE_DNSCRYPT, Settings.DNSModeCryptPort)
    }

    fun setDefaultConnection() {
        if (getDNSType() != PREF_DNS_MODE_DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.updateConnectionDefault()
        onNewDnsConnected(PREF_DNS_MODE_DOH, Settings.DNSModePort)
    }

    suspend fun updateRethinkPlusStamp(stamp: String) {
        removeConnectionStatus()
        doHEndpointRepository.updateConnectionURL(stamp)
        onNewDnsConnected(PREF_DNS_MODE_DOH, Settings.DNSModePort)
    }

    suspend fun isRelaySelectable(): Boolean {
        return dnsCryptEndpointRepository.getConnectedCount() >= 1
    }

    private fun removeConnectionStatus() {
        when (getDNSType()) {
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

    suspend fun insertRelayEndpoint(endpoint: DNSCryptRelayEndpoint) {
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

    suspend fun deleteDnsrelayEndpoint(id: Int) {
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

        // If the proxy  type is either http/socks5 then check if other proxy is enabled.
        // if yes, then make the proxy type as HTTP_SOCKS5. This case holds good for
        // custom proxy setup.
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

    private fun isSocks5ProxyEnabled(): Boolean {
        return ProxyType.SOCKS5.name == getProxyType()
    }

    private fun isProxyTypeSocks5(proxyType: ProxyType): Boolean {
        return ProxyType.SOCKS5 == proxyType
    }

    private fun isHttpSocks5ProxyEnabled(): Boolean {
        return ProxyType.HTTP_SOCKS5.name == getProxyType()
    }

    private fun removeAllProxies() {
        persistentState.proxyType = ProxyType.NONE.name
        persistentState.proxyProvider = ProxyProvider.NONE.name
    }

    fun removeProxy(proxyType: ProxyType, provider: ProxyProvider) {
        if (provider == ProxyProvider.NONE || provider == ProxyProvider.NONE) {
            removeAllProxies()
            return
        }

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
                    Utilities.showToastUiCentered(context, "Invalid Mode", Toast.LENGTH_SHORT)
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
    fun getProxyMode(): Long {
        val type = getProxyType()
        val provider = getProxyProvider()
        if (DEBUG) Log.d(LOG_TAG_VPN, "selected proxy type: $type, with provider as $provider")

        if (ProxyType.NONE.name == type || ProxyProvider.NONE.name == provider) {
            return Settings.ProxyModeNone
        }

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
        return ((getProxyType() == ProxyType.HTTP.name || getProxyType() == ProxyType.HTTP_SOCKS5.name) && (getProxyProvider() == ProxyProvider.CUSTOM.name))
    }

    fun isCustomSocks5Enabled(): Boolean {
        return ((getProxyType() == ProxyType.SOCKS5.name || getProxyType() == ProxyType.HTTP_SOCKS5.name) && (getProxyProvider() == ProxyProvider.CUSTOM.name))
    }

    fun isOrbotProxyEnabled(): Boolean {
        return getProxyProvider() == ProxyProvider.ORBOT.name
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

    fun isDnsProxy(): Boolean {
        return PREF_DNS_MODE_PROXY == getDNSType()
    }

}
