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
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_MODE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import dnsx.BraveDNS
import dnsx.Dnsx
import settings.Settings
import java.io.File

class AppMode internal constructor(private val context: Context,
                                   private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
                                   private val doHEndpointRepository: DoHEndpointRepository,
                                   private val dnsCryptEndpointRepository: DNSCryptEndpointRepository,
                                   private val dnsCryptRelayEndpointRepository: DNSCryptRelayEndpointRepository,
                                   private val proxyEndpointRepository: ProxyEndpointRepository,
                                   private val persistentState: PersistentState) {
    private var appDNSMode: Long = PREF_DNS_INVALID
    private val proxyTypeInternal = "Internal"
    private val proxyTypeExternal = "External"
    private var dnsType: Int = -1
    private var socks5ProxyEndpoint: ProxyEndpoint? = null
    private var braveDNS: BraveDNS? = null

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
                return getProxyModeSettings()
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
        if (dnsType == -1) {
            return persistentState.dnsType
        }
        return dnsType
    }

    fun setDNSMode(mode: Long) {
        appDNSMode = mode
    }

    private fun getProxyModeSettings(): Long {
        val dnsProxy = dnsProxyEndpointRepository.getConnectedProxy()
        appDNSMode = when (dnsProxy.proxyType) {
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
        return appDNSMode
    }

    fun getDOHDetails(): DoHEndpoint? {
        return doHEndpointRepository.getConnectedDoH()
    }

    fun getDNSCryptServers(): String {
        val cryptList = dnsCryptEndpointRepository.getConnectedDNSCrypt()
        return constructServerString(cryptList)
    }

    fun getDNSCryptServerCount(): Int {
        return dnsCryptEndpointRepository.getConnectedCount()
    }

    fun getDNSCryptServerToRemove(): String {
        val cryptList = dnsCryptEndpointRepository.getConnectedDNSCrypt()
        return constructStringForRemoval(cryptList)
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

    private fun constructStringForRemoval(cryptList: List<DNSCryptEndpoint>): String {
        var removeServerString = ""
        cryptList.forEach {
            removeServerString += "${it.id},"
        }
        removeServerString = removeServerString.dropLast(1)
        return removeServerString
    }

    private fun constructServerString(cryptList: List<DNSCryptEndpoint>): String {
        var servers = ""

        cryptList.forEach {
            servers += "${it.id}#${it.dnsCryptURL},"
        }
        servers = servers.dropLast(1)
        Log.i(LOG_TAG_APP_MODE, "Crypt Server - $servers")
        return servers
    }

    fun getDNSCryptRelays(): String {
        val relay = dnsCryptRelayEndpointRepository.getConnectedRelays()
        return constructRelayString(relay)
    }

    private fun constructRelayString(relay: List<DNSCryptRelayEndpoint>): String {
        var relayString = ""
        relay.forEach {
            relayString += "${it.dnsCryptRelayURL},"
        }
        relayString = relayString.dropLast(1)
        Log.i(LOG_TAG_APP_MODE, "Crypt Server - $relayString")
        return relayString
    }

    fun getDNSProxyServerDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun setBraveDNS(braveDNS: BraveDNS?) {
        this.braveDNS = braveDNS
    }

    fun getBraveDNS(): BraveDNS? {
        if (braveDNS != null) {
            return braveDNS
        }

        val path: String = context.filesDir.canonicalPath + File.separator + persistentState.blocklistDownloadTime
        if (DEBUG) Log.d(LOG_TAG_APP_MODE,
                         "Local brave dns set call from AppMode path newBraveDNSLocal :$path")
        try {
            braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE,
                                             path + Constants.FILE_RD_FILE,
                                             path + Constants.FILE_BASIC_CONFIG,
                                             path + Constants.FILE_TAG_NAME)
        } catch (e: Exception) {
            Log.e(LOG_TAG_APP_MODE, "Local brave dns set exception :${e.message}", e)
        }

        return braveDNS
    }

    fun onNewDnsConnected(dnsType: Int, dnsMode: Long) {
        if (PREF_DNS_MODE_DOH == dnsType) {
            persistentState.dnsType = dnsType
            setDNSMode(dnsMode)
            val endpoint = getDOHDetails()
            if (endpoint != null) {
                persistentState.setConnectedDNS(endpoint.dohName)
            }
        } else if (PREF_DNS_MODE_DNSCRYPT == dnsType) {
            persistentState.dnsType = dnsType
            setDNSMode(dnsMode)
            val connectedDNS = getDNSCryptServerCount()
            val text = context.getString(R.string.configure_dns_crypt, connectedDNS.toString())
            persistentState.setConnectedDNS(text)
        } else if (PREF_DNS_MODE_PROXY == dnsType) {
            persistentState.dnsType = dnsType
            setDNSMode(dnsMode)
            val endpoint = getDNSProxyServerDetails()
            persistentState.setConnectedDNS(endpoint.proxyName)
        }
    }

    fun changeBraveMode(braveMode: Int) {
        persistentState.setBraveMode(braveMode)
        when (braveMode) {
            APP_MODE_DNS -> {
                setFirewallMode(Settings.BlockModeNone)
            }
            APP_MODE_FIREWALL -> {
                setDNSMode(Settings.DNSModeNone)
                setFirewallMode(getFilteredFirewall())
            }
            APP_MODE_DNS_FIREWALL -> {
                setFirewallMode(getFilteredFirewall())
            }
        }
    }

    fun syncBraveMode() {
        when (persistentState.getBraveMode()) {
            APP_MODE_DNS -> {
                setFirewallMode(Settings.BlockModeNone)
            }
            APP_MODE_FIREWALL -> {
                setDNSMode(Settings.DNSModeNone)
                setFirewallMode(getFilteredFirewall())
            }
            APP_MODE_DNS_FIREWALL -> {
                setFirewallMode(getFilteredFirewall())
            }
        }
    }

    private fun getFilteredFirewall(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Settings.BlockModeFilterProc
        } else {
            Settings.BlockModeFilter
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

        if (isProxyTypeSOCKS5(proxyType)) {
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

    private fun isProxyTypeSOCKS5(proxyType: ProxyType): Boolean {
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

}
