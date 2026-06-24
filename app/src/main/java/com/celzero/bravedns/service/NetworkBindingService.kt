/*
 * Copyright 2024 RethinkDNS and its authors
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
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.KnownPorts
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket

/**
 * Handles network binding and socket protection for VPN traffic.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class NetworkBindingService(
    private val vpnService: VpnService,
    private val persistentState: PersistentState,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "NetworkBinding"
        
        // Route IPv4 in IPv6 only networks?
        private const val ROUTE4IN6 = true
    }

    /**
     * Data class representing underlying network information
     */
    data class UnderlyingNetworks(
        val ipv4Net: List<NetworkProperties> = emptyList(),
        val ipv6Net: List<NetworkProperties> = emptyList(),
        val dnsServers: Map<java.net.InetAddress, Network> = emptyMap(),
        val useActive: Boolean = true,
        val vpnLockdown: Boolean = false,
        val minMtu: Int = Int.MAX_VALUE,
        var lastUpdated: Long = 0L,
        var isActiveNetworkMetered: Boolean = false,
        var isActiveNetworkCellular: Boolean = false
    )

    data class NetworkProperties(
        val network: Network,
        val capabilities: android.net.NetworkCapabilities
    )

    // Current underlying networks - should be updated by ConnectionMonitor
    var underlyingNetworks: UnderlyingNetworks? = null

    /**
     * Binds a socket to an IPv4 network.
     * Called from Go code via Bridge interface.
     */
    suspend fun bind4(who: String, addrPort: String, fid: Long): Boolean {
        return withContext(ioDispatcher) {
            var v4Net = underlyingNetworks?.ipv4Net
            val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
            
            // Fall back to IPv6 net if IPv4 is empty and auto mode
            if (ROUTE4IN6 && isAuto && v4Net.isNullOrEmpty()) {
                v4Net = underlyingNetworks?.ipv6Net
            }
            
            bindAny(who, addrPort, fid, v4Net ?: emptyList())
        }
    }

    /**
     * Binds a socket to an IPv6 network.
     * Called from Go code via Bridge interface.
     */
    suspend fun bind6(who: String, addrPort: String, fid: Long): Boolean {
        return withContext(ioDispatcher) {
            bindAny(who, addrPort, fid, underlyingNetworks?.ipv6Net ?: emptyList())
        }
    }

    /**
     * Protects a file descriptor from going through the VPN.
     * Called from Go code via Bridge interface.
     */
    suspend fun protectSocket(who: String?, fd: Long) {
        withContext(ioDispatcher) {
            if (who == null) {
                Logger.w(LOG_TAG_VPN, "$TAG protect: who is null, fd: $fd")
                return@withContext
            }

            val rinr = persistentState.routeRethinkInRethink
            logd("protect: $who, fd: $fd, rinr? $rinr")
            
            if (rinr && shouldSkipForRethinkInRethink(who)) {
                return@withContext
            }
            
            vpnService.protect(fd.toInt())
        }
    }

    /**
     * Protects a socket from going through the VPN.
     */
    fun protectSocket(socket: Socket) {
        vpnService.protect(socket)
        Logger.v(LOG_TAG_VPN, "$TAG socket protected")
    }

    /**
     * Binds a file descriptor to a specific network for connectivity checks.
     */
    fun bindToNetworkForConnectivityChecks(network: Network, fid: Long): Boolean {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = ParcelFileDescriptor.adoptFd(fid.toInt())
            return bindToNetwork(network, pfd, fid)
        } catch (e: Exception) {
            Logger.i(LOG_TAG_VPN, "$TAG err bindToNetworkForConnectivityChecks, ${e.message}")
        } finally {
            pfd?.detachFd()
        }
        return false
    }

    /**
     * Protects a file descriptor for connectivity checks.
     */
    fun protectFdForConnectivityChecks(fd: Long) {
        vpnService.protect(fd.toInt())
        Logger.v(LOG_TAG_VPN, "$TAG fd($fd) protected for connectivity checks")
    }

    private suspend fun bindAny(
        who: String,
        addrPort: String,
        fid: Long,
        networks: List<NetworkProperties>
    ): Boolean {
        val rinr = persistentState.routeRethinkInRethink
        val currentNetworks = underlyingNetworks

        logd("bind: who: $who, addr: $addrPort, fd: $fid, rinr? $rinr")
        
        if (rinr && shouldSkipForRethinkInRethink(who)) {
            return true
        }

        // Protect the socket first
        vpnService.protect(fid.toInt())

        if (networks.isEmpty()) {
            Logger.w(LOG_TAG_VPN, "$TAG no network to bind, who: $who, fd: $fid, addr: $addrPort")
            return false
        }

        var pfd: ParcelFileDescriptor? = null
        try {
            val dest = IpRulesManager.splitHostPort(addrPort)
            val destIp = IPAddressString(dest.first).address
            val destPort = dest.second.toIntOrNull()
            val destAddr = destIp.toInetAddress()

            // Skip binding for zero addresses, loopback, or WireGuard
            if (destIp.isZero && !who.startsWith(ProxyManager.ID_WG_BASE)) {
                logd("bind: zero addr, who: $who, addr: $addrPort")
                return true
            }
            if (destIp.isZero || destIp.isLoopback) {
                logd("bind: invalid destIp: $destIp, who: $who, addr: $addrPort")
                return true
            }

            pfd = ParcelFileDescriptor.adoptFd(fid.toInt())

            // Check if destination is DNS port, bind to appropriate network
            if (KnownPorts.isDns(destPort)) {
                currentNetworks?.dnsServers?.get(destAddr)?.let { net ->
                    if (bindToNetwork(net, pfd, fid)) {
                        logd("bind: dns, who: $who, addr: $addrPort, fd: $fid, ok: true")
                        return true
                    }
                }
            }

            // Use active network if configured
            if (currentNetworks?.useActive == true) {
                logd("bind: use active network is true, who: $who, addr: $addrPort, fd: $fid")
                return true
            }

            // Try binding to available networks
            for (networkProp in networks) {
                if (bindToNetwork(networkProp.network, pfd, fid)) {
                    logd("bind: nw, who: $who, addr: $addrPort, fd: $fid, ok: true")
                    return true
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err bind: who: $who, addr: $addrPort, fd: $fid, ${e.message}", e)
        } finally {
            pfd?.detachFd()
        }

        Logger.e(LOG_TAG_VPN, "$TAG bind failed: who: $who, addr: $addrPort, fd: $fid")
        return false
    }

    private fun bindToNetwork(network: Network, pfd: ParcelFileDescriptor, fid: Long): Boolean {
        return try {
            network.bindSocket(pfd.fileDescriptor)
            true
        } catch (e: IOException) {
            val netId = netid(network.networkHandle)
            Logger.e(LOG_TAG_VPN, "$TAG err bindToNetwork(netId: $netId, fid: $fid, ${e.message}")
            false
        }
    }

    private fun shouldSkipForRethinkInRethink(who: String): Boolean {
        val rethinkUid = try {
            vpnService.packageManager.getApplicationInfo(vpnService.packageName, 0).uid
        } catch (e: Exception) {
            return false
        }

        // Simplified check - assume rethink is not bypassed from proxy
        val isRethinkBypassedFromProxy = false
        
        if (!isRethinkBypassedFromProxy) {
            if (!ProxyManager.isAnyUserSetProxy(who) && who != com.celzero.firestack.backend.Backend.Exit) {
                Logger.vv(LOG_TAG_VPN, "$TAG rinr, bypassed rethink, who: $who")
                return true
            }
        } else if (who != com.celzero.firestack.backend.Backend.Exit) {
            Logger.vv(LOG_TAG_VPN, "$TAG rinr, within rethink, who: $who")
            return true
        }
        return false
    }

    /**
     * Extracts network ID from network handle.
     */
    fun netid(nwHandle: Long): Long {
        // ref: cs.android.com/android/platform/superproject/main/+/main:packages/modules/Connectivity/framework/src/android/net/Network.java
        return nwHandle shr 32
    }

    private fun logd(msg: String) {
        Logger.d(LOG_TAG_VPN, "$TAG $msg")
    }
}
