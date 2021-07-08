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
import android.util.Log
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_INVALID
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_PROXY
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_MODE
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

    fun getProxyMode(): Long {
        return persistentState.proxyMode
    }

    fun setProxyMode(proxyMode: Long) {
        persistentState.proxyMode = proxyMode
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

    fun getSocks5ProxyDetails(): ProxyEndpoint {
        if (socks5ProxyEndpoint == null) {
            socks5ProxyEndpoint = proxyEndpointRepository.getConnectedProxy()
        }
        return socks5ProxyEndpoint!!
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
        var i = 1
        return if (cryptList.isEmpty()) {
            servers
        } else {
            cryptList.forEach {
                servers += "${it.id}#${it.dnsCryptURL},"
                i++
            }
            servers = servers.dropLast(1)
            Log.i(LOG_TAG_APP_MODE, "Crypt Server - $servers")
            servers
        }
    }

    fun getDNSCryptRelays(): String {
        val relay = dnsCryptRelayEndpointRepository.getConnectedRelays()
        return constructRelayString(relay)
    }

    private fun constructRelayString(relay: List<DNSCryptRelayEndpoint>): String {
        var relayString = ""
        return if (relay.isEmpty()) {
            relayString
        } else {
            relay.forEach {
                relayString += "${it.dnsCryptRelayURL},"
            }
            relayString = relayString.dropLast(1)
            Log.i(LOG_TAG_APP_MODE, "Crypt Server - $relayString")
            relayString
        }
    }

    fun getDNSProxyServerDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun setBraveDNSMode(braveDNS: BraveDNS?) {
        this.braveDNS = braveDNS
    }

    fun getBraveDNS(): BraveDNS? {
        if (braveDNS != null) {
            return braveDNS
        }

        val path: String = context.filesDir.canonicalPath + File.separator + persistentState.localBlocklistDownloadTime
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
}
