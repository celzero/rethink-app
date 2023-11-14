/*
 * Copyright 2023 RethinkDNS and its authors
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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

// ref:
// https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/DnsConfig.java#L166
class DnsConfig(ctx: Context?) {

    private var ctx: Context? = null

    init {
        this.ctx = ctx
    }

    fun getDnsConfigAsString(): String {
        val s: String = getDnsConfigFromLinkProperties()
        if (s.trim { it <= ',' }.isNotEmpty()) {
            Log.d("DnsConfig", "getDnsConfigAsString: $s")
            return s
        }
        Log.d("DnsConfig", "getDnsConfigAsString: empty")
        return ""
    }

    private fun getDnsConfigFromLinkProperties(): String {
        val cMgr = ctx!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cMgr.activeNetwork
        if (network != null) {
            val linkProp = cMgr.getLinkProperties(network)
            val dnsList = linkProp!!.dnsServers
            val sb = StringBuilder("")
            for (ip in dnsList) {
                sb.append(ip.hostAddress + ",")
            }
            if (sb.isNotEmpty()) {
                return sb.dropLastWhile { it == ',' }.toString()
            }
        }

        // If the active network is null, then try to get the DNS config from all networks.
        val networks = cMgr.allNetworks

        // getPreferabilityForNetwork returns an index into dnsConfigs from 0-3.
        val dnsConfigs = arrayOf("", "", "", "")
        for (network in networks) {
            val idx: Int = getPreferabilityForNetwork(cMgr, network)
            if (idx < 0 || idx > 3) {
                continue
            }
            val linkProp = cMgr.getLinkProperties(network)
            val dnsList = linkProp!!.dnsServers
            val sb = StringBuilder("")
            for (ip in dnsList) {
                sb.append(ip.hostAddress + ",")
            }
            /*val d = linkProp.domains
            if (d != null) {
                sb.append("\n")
                sb.append(d)
            }*/
            dnsConfigs[idx] = sb.dropLastWhile { it == ',' }.toString()
        }

        // return the lowest index DNS config which exists. If an Ethernet config
        // was found, return it. Otherwise if Wi-fi was found, return it. Etc.
        for (s in dnsConfigs) {
            if (s.trim { it <= ',' }.isNotEmpty()) {
                return s
            }
        }
        return ""
    }

    private fun getPreferabilityForNetwork(cMgr: ConnectivityManager, network: Network?): Int {
        val nc = cMgr.getNetworkCapabilities(network) ?: return -1
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // tun0 has both VPN and WIFI set, have to check VPN first and return.
            return -1
        }
        return if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            0
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            1
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            2
        } else {
            3
        }
    }
}
