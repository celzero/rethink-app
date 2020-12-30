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
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import dnsx.BraveDNS
import dnsx.Dnsx
import settings.Settings

class AppMode internal constructor(
    private val context: Context,
    private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
    private val doHEndpointRepository: DoHEndpointRepository,
    private val dnsCryptEndpointRepository: DNSCryptEndpointRepository,
    private val dnsCryptRelayEndpointRepository: DNSCryptRelayEndpointRepository,
    private val proxyEndpointRepository: ProxyEndpointRepository,
    private val persistentState:PersistentState
) {
    private var appDNSMode: Long = -1L
    private var appFirewallMode: Long = -1L
    private var appProxyMode: Long = -1L
    private val proxyTypeInternal = "Internal"
    private val proxyTypeExternal = "External"
    private var dnsType: Int = -1
    private var socks5ProxyEndpoint: ProxyEndpoint ?= null
    private var braveDNS : BraveDNS ?= null

    fun getDNSMode(): Long {
        if (appDNSMode == -1L) {
            val dnsType = persistentState.dnsType
            if (dnsType == 1) {
                if (persistentState.allowDNSTraffic) {
                    appDNSMode = Settings.DNSModePort
                } else {
                    appDNSMode = Settings.DNSModeIP
                }
            } else if (dnsType == 2) {
                appDNSMode = Settings.DNSModeCryptPort
            } else if (dnsType == 3) {
                return getProxyModeSettings()
            }
        }
        return appDNSMode
    }

    fun getFirewallMode(): Long {
        if (appFirewallMode == -1L) {
            return persistentState.firewallMode.toLong()
        } else {
            return appFirewallMode
        }
    }

    fun setFirewallMode(fMode: Long) {
        appFirewallMode = fMode
        persistentState.firewallMode = fMode.toInt()
    }

    fun getProxyMode(): Long {
        if (appProxyMode == -1L) {
            return persistentState.proxyMode
        }
        return appProxyMode
    }

    fun setProxyMode(proxyMode: Long) {
        appProxyMode = proxyMode
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
        appDNSMode = if (dnsProxy.proxyType == proxyTypeInternal) {
            Settings.DNSModeProxyPort
        } else if (dnsProxy.proxyType == proxyTypeExternal) {
            Settings.DNSModeProxyIP
        } else {
            Settings.DNSModeProxyPort
        }
        return appDNSMode
    }

    fun getDOHDetails(): DoHEndpoint {
        val dohEndpoint = doHEndpointRepository.getConnectedDoH()
        if (dohEndpoint != null) {
            if (dohEndpoint.dohURL.isEmpty()) {
                if (HomeScreenActivity.GlobalVariable.DEBUG) {
                    Log.d(LOG_TAG, "getDOHDetails -appMode- DoH endpoint is null")
                }
            }else{
                if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "getDOHDetails -appMode- DoH endpoint - ${dohEndpoint.dohURL}")
            }
        }
        return dohEndpoint
    }

    fun getDNSCryptServers(): String {
        val cryptList = dnsCryptEndpointRepository.getConnectedDNSCrypt()
        return constructServerString(cryptList)
    }

    fun getDNSCryptServerCount() : Int{
        val count = dnsCryptEndpointRepository.getConnectedCount()
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
            Log.i(LOG_TAG, "Crypt Server - $servers")
            servers
        }
    }

    fun getDNSCryptRelays(): String {
        val relay = dnsCryptRelayEndpointRepository.getConnectedRelays()
        return constructRelayString(relay)
    }

    private fun constructRelayString(relay: List<DNSCryptRelayEndpoint>): String {
        var relayString = ""
        val i = 1
        return if (relay.isEmpty()) {
            relayString
        } else {
            relay.forEach {
                relayString += "${it.dnsCryptRelayURL},"
            }
            relayString = relayString.dropLast(1)
            Log.i(LOG_TAG, "Crypt Server - $relayString")
            relayString
        }
    }

    fun getDNSProxyServerDetails(): DNSProxyEndpoint {
        return dnsProxyEndpointRepository.getConnectedProxy()
    }

    fun setBraveDNSMode( braveDNS : BraveDNS?){
        this.braveDNS = braveDNS
    }

    fun getBraveDNS(): BraveDNS?{
        if(braveDNS == null && persistentState.localBlocklistEnabled
            && persistentState.blockListFilesDownloaded && !persistentState.getLocalBlockListStamp().isNullOrEmpty()){
            val path: String = context.filesDir.canonicalPath
            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "Local brave dns set call from AppMode")
            braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE, path + Constants.FILE_RD_FILE, path + Constants.FILE_BASIC_CONFIG, path + Constants.FILE_TAG_NAME)
            HomeScreenActivity.GlobalVariable.appMode?.setBraveDNSMode(braveDNS)
        }
        return braveDNS
    }

}
