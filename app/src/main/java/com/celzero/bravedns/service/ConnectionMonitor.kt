/*
 * Copyright 2021 RethinkDNS and its authors
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
import Logger.LOG_TAG_CONNECTION
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants.ECONNREFUSED
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isNetworkSame
import com.google.common.collect.Sets
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class ConnectionMonitor(private val networkListener: NetworkListener) :
    ConnectivityManager.NetworkCallback(), KoinComponent {

    private val networkSet: MutableSet<Network> = ConcurrentHashMap.newKeySet()

    // add cellular, wifi, bluetooth, ethernet, vpn, wifi aware, low pan
    private val networkRequest: NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .apply { if (isAtleastS()) setIncludeOtherUidNetworks(true) }
            // api27: .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            // api26: .addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN)
            .build()

    private var serviceHandler: NetworkRequestHandler? = null
    private val persistentState by inject<PersistentState>()

    private lateinit var connectivityManager: ConnectivityManager

    companion object {
        // add active network as underlying vpn network
        const val MSG_ADD_ACTIVE_NETWORK = 1

        // add all available networks as underlying vpn networks
        const val MSG_ADD_ALL_NETWORKS = 2

        fun networkType(newActiveNetworkCap: NetworkCapabilities?): String {
            val a =
                if (newActiveNetworkCap?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    "VPN"
                } else if (
                    newActiveNetworkCap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                ) {
                    "WiFi"
                } else if (
                    newActiveNetworkCap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ==
                    true
                ) {
                    "Cellular"
                } else {
                    "Unknown"
                }
            return a
        }
    }

    // data class that holds the below information for handlers to process the network changes
    // all values in OpPrefs should always remain immutable
    data class OpPrefs(
        val msgType: Int,
        val networkSet: Set<Network>,
        val isForceUpdate: Boolean,
        val testReachability: Boolean
    )

    interface NetworkListener {
        fun onNetworkDisconnected(networks: UnderlyingNetworks)

        fun onNetworkConnected(networks: UnderlyingNetworks)

        fun onNetworkRegistrationFailed()
    }

    override fun onAvailable(network: Network) {
        networkSet.add(network)
        val cap = connectivityManager.getNetworkCapabilities(network)
        Logger.d(
            LOG_TAG_CONNECTION,
            "onAvailable: ${network.networkHandle}, $network, ${networkSet.size}, ${networkType(cap)}"
        )
        handleNetworkChange()
    }

    override fun onLost(network: Network) {
        networkSet.remove(network)
        val cap = connectivityManager.getNetworkCapabilities(network)
        Logger.d(
            LOG_TAG_CONNECTION,
            "onLost: ${network.networkHandle}, $network, ${networkSet.size}, ${networkType(cap)}"
        )
        handleNetworkChange()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        Logger.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged, ${network.networkHandle}, $network")
        handleNetworkChange(isForceUpdate = false, TimeUnit.SECONDS.toMillis(3))
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        Logger.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged: ${network.networkHandle}, $network")
        handleNetworkChange(isForceUpdate = true, TimeUnit.SECONDS.toMillis(3))
    }

    /**
     * Handles user preference changes, ie, when the user elects to see either multiple underlying
     * networks, or just one (the active network).
     */
    fun onUserPreferenceChanged() {
        Logger.d(LOG_TAG_CONNECTION, "onUserPreferenceChanged")
        handleNetworkChange(isForceUpdate = true)
    }

    /**
     * Force updates the VPN's underlying network based on the preference. Will be initiated when
     * the VPN start is completed.
     */
    fun onVpnStart(context: Context) {
        if (this.serviceHandler != null) {
            Logger.w(LOG_TAG_CONNECTION, "connection monitor is already running")
            return
        }

        Logger.i(LOG_TAG_CONNECTION, "new vpn is created force update the network")
        connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
        try {
            connectivityManager.registerNetworkCallback(networkRequest, this)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "Exception while registering network callback", e)
            networkListener.onNetworkRegistrationFailed()
            return
        }

        val handlerThread = HandlerThread(NetworkRequestHandler::class.simpleName)
        handlerThread.start()
        this.serviceHandler =
            NetworkRequestHandler(connectivityManager, handlerThread.looper, networkListener)
        handleNetworkChange(isForceUpdate = true)
    }

    fun onVpnStop() {
        try {
            this.serviceHandler?.removeCallbacksAndMessages(null)
            serviceHandler?.looper?.quitSafely()
            this.serviceHandler = null
            connectivityManager.unregisterNetworkCallback(this)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "ConnectionMonitor: err while unregistering", e)
        }
    }

    private fun handleNetworkChange(
        isForceUpdate: Boolean = false,
        delay: Long = TimeUnit.SECONDS.toMillis(1)
    ) {
        val dualStack =
            InternetProtocol.getInternetProtocol(persistentState.internetProtocolType).isIPv46()
        val testReachability = dualStack && persistentState.connectivityChecks
        val msg =
            constructNetworkMessage(
                if (persistentState.useMultipleNetworks) MSG_ADD_ALL_NETWORKS
                else MSG_ADD_ACTIVE_NETWORK,
                isForceUpdate,
                testReachability
            )
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        // process after a delay to avoid processing multiple network changes in short bursts
        serviceHandler?.sendMessageDelayed(msg, delay)
    }

    /**
     * Constructs the message object for Network handler. Add the active network to the message
     * object in case of setUnderlying network has only active networks.
     */
    private fun constructNetworkMessage(
        what: Int,
        isForceUpdate: Boolean,
        testReachability: Boolean
    ): Message {
        val opPrefs = OpPrefs(what, networkSet, isForceUpdate, testReachability)
        val message = Message.obtain()
        message.what = what
        message.obj = opPrefs
        return message
    }

    data class NetworkProperties(
        val network: Network,
        val capabilities: NetworkCapabilities,
        val linkProperties: LinkProperties?,
        val networkType: String
    )

    data class UnderlyingNetworks(
        val ipv4Net: List<NetworkProperties>,
        val ipv6Net: List<NetworkProperties>,
        val useActive: Boolean,
        val minMtu: Int,
        var isActiveNetworkMetered: Boolean, // may be updated by client listener
        var lastUpdated: Long, // may be updated by client listener
        val dnsServers: Map<InetAddress, Network>
    )

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(
        val connectivityManager: ConnectivityManager,
        looper: Looper,
        val listener: NetworkListener
    ) : Handler(looper) {

        // number of times the reachability check is performed due to failures
        private var reachabilityCount = 0L
        private val maxReachabilityCount = 10L

        companion object {
            private const val DEFAULT_MTU = 1500 // same as BraveVpnService#VPN_INTERFACE_MTU
            private const val MIN_MTU = 1280
        }

        private val ip4probes =
            listOf(
                "216.239.32.27", // google org
                "104.16.132.229", // cloudflare
                "31.13.79.53" // whatsapp.net
            )

        // probing with domain names is not viable because some domains will resolve to both
        // ipv4 and ipv6 addresses. So, we use ipv6 addresses for probing ipv6 connectivity.
        private val ip6probes =
            listOf(
                "2001:4860:4802:32::1b", // google org
                "2606:4700::6810:84e5", // cloudflare
                "2606:4700:3033::ac43:a21b" // rethinkdns
            )

        // ref - https://developer.android.com/reference/kotlin/java/util/LinkedHashSet
        // The network list is maintained in a linked-hash-set to preserve insertion and iteration
        // order. This is required because {@link android.net.VpnService#setUnderlyingNetworks}
        // defines network priority depending on the iteration order, that is, the network
        // in the 0th index is preferred over the one at 1st index, and so on.
        var currentNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()

        var trackedIpv4Networks: LinkedHashSet<NetworkProperties> = linkedSetOf()
        var trackedIpv6Networks: LinkedHashSet<NetworkProperties> = linkedSetOf()

        override fun handleMessage(msg: Message) {
            // isForceUpdate - true if onUserPreferenceChanged is changes, the messages should be
            // processed forcefully regardless of the current and new networks.
            when (msg.what) {
                MSG_ADD_ACTIVE_NETWORK -> {
                    val opPrefs = msg.obj as OpPrefs
                    processActiveNetwork(opPrefs)
                }

                MSG_ADD_ALL_NETWORKS -> {
                    val opPrefs = msg.obj as OpPrefs
                    processAllNetworks(opPrefs)
                }
            }
        }

        /**
         * tracks the changes in active network. Set the underlying network if the current active
         * network is different from already assigned one unless the force update is required.
         */
        private fun processActiveNetwork(opPrefs: OpPrefs) {
            val newActiveNetwork = connectivityManager.activeNetwork
            val newActiveNetworkCap = connectivityManager.getNetworkCapabilities(newActiveNetwork)
            // set active network's connection status
            val isActiveNetworkMetered = isActiveConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork, opPrefs.networkSet)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Logger.i(
                LOG_TAG_CONNECTION,
                "Connected network: ${newActiveNetwork?.networkHandle} ${
                    networkType(newActiveNetworkCap)
                }, new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, test? ${opPrefs.testReachability}"
            )

            if (isNewNetwork || opPrefs.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(opPrefs, currentNetworks)
                informListener(true, isActiveNetworkMetered)
            }
        }

        /** Adds all the available network to the underlying network. */
        private fun processAllNetworks(opPrefs: OpPrefs) {
            val newActiveNetwork = connectivityManager.activeNetwork
            // set active network's connection status
            val isActiveNetworkMetered = isActiveConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork, opPrefs.networkSet)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Logger.i(
                LOG_TAG_CONNECTION,
                "process message MESSAGE_AVAILABLE_NETWORK, ${currentNetworks}, ${newNetworks}; new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, test? ${opPrefs.testReachability}"
            )

            if (isNewNetwork || opPrefs.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(opPrefs, currentNetworks)
                informListener(false, isActiveNetworkMetered)
            }
        }

        private fun informListener(
            useActiveNetwork: Boolean = false,
            isActiveNetworkMetered: Boolean
        ) {
            // TODO: use currentNetworks instead of trackedIpv4Networks and trackedIpv6Networks
            // to determine whether to call onNetworkConnected or onNetworkDisconnected
            val sz = trackedIpv4Networks.size + trackedIpv6Networks.size
            Logger.i(
                LOG_TAG_CONNECTION,
                "inform network change: ${sz}, all? $useActiveNetwork, metered? $isActiveNetworkMetered"
            )
            // maintain a map of dns servers for ipv4 and ipv6 networks
            val dns4 = getDnsServers(trackedIpv4Networks)
            val dns6 = getDnsServers(trackedIpv6Networks)
            val dnsServers: LinkedHashMap<InetAddress, Network> = LinkedHashMap()
            dnsServers.putAll(dns4)
            dnsServers.putAll(dns6)

            if (sz > 0) {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        trackedIpv4Networks.map { it }, // map to produce shallow copy
                        trackedIpv6Networks.map { it },
                        useActiveNetwork,
                        determineMtu(useActiveNetwork),
                        isActiveNetworkMetered,
                        SystemClock.elapsedRealtime(),
                        Collections.unmodifiableMap(dnsServers)
                    )
                trackedIpv4Networks.forEach {
                    Logger.d(LOG_TAG_CONNECTION, "inform4: ${it.network}, ${it.networkType}, $sz")
                }
                trackedIpv6Networks.forEach {
                    Logger.d(LOG_TAG_CONNECTION, "inform6: ${it.network}, ${it.networkType}, $sz")
                }
                listener.onNetworkConnected(underlyingNetworks)
            } else {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        emptyList(),
                        emptyList(),
                        useActiveNetwork,
                        DEFAULT_MTU,
                        isActiveNetworkMetered = false,
                        SystemClock.elapsedRealtime(),
                        LinkedHashMap()
                    )
                listener.onNetworkDisconnected(underlyingNetworks)
            }
        }

        private fun getDnsServers(
            nws: LinkedHashSet<NetworkProperties>
        ): LinkedHashMap<InetAddress, Network> {
            // add dns servers into a set to avoid duplicates and add corresponding network to it
            val dnsServers = LinkedHashMap<InetAddress, Network>()
            nws.forEach {
                it.linkProperties?.dnsServers?.forEach v@{ dns ->
                    val address = dns ?: return@v
                    dnsServers[address] = it.network
                }
            }
            return dnsServers
        }

        private fun determineMtu(useActiveNetwork: Boolean): Int {
            var minMtu4: Int = DEFAULT_MTU
            var minMtu6: Int = DEFAULT_MTU
            if (!isAtleastQ()) {
                return minMtu4
            }
            if (useActiveNetwork) {
                connectivityManager.activeNetwork?.let {
                    val lp = connectivityManager.getLinkProperties(it)
                    minMtu4 = minNonZeroMtu(lp?.mtu, minMtu4)
                    minMtu6 = minMtu4 // assume same mtu for both ipv4 and ipv6
                }
                    ?: {
                        // consider first network in underlying network as active network,
                        //  in case active network is null
                        val lp4 = trackedIpv4Networks.firstOrNull()?.linkProperties
                        val lp6 = trackedIpv6Networks.firstOrNull()?.linkProperties
                        minMtu4 = minNonZeroMtu(lp4?.mtu, minMtu4)
                        minMtu6 = minNonZeroMtu(lp6?.mtu, minMtu6)
                    }
            } else {
                // parse through all the networks and get the minimum mtu
                trackedIpv4Networks.forEach {
                    val c = it.linkProperties
                    minMtu4 = minNonZeroMtu(c?.mtu, minMtu4)
                }
                trackedIpv6Networks.forEach {
                    val c = it.linkProperties
                    minMtu6 = minNonZeroMtu(c?.mtu, minMtu6)
                }
            }
            // set mtu to MIN_MTU (1280) if mtu4/mtu6 are less than MIN_MTU
            val mtu = max(min(minMtu4, minMtu6), MIN_MTU)
            Logger.i(LOG_TAG_CONNECTION, "mtu4: $minMtu4, mtu6: $minMtu6; final mtu: $mtu")
            return mtu
        }

        private fun minNonZeroMtu(m1: Int?, m2: Int): Int {
            return if (m1 != null && m1 > 0) {
                // mtu can be null when lp is null
                // mtu can be 0 when the value is not set, see:LinkProperties#getMtu()
                min(m1, m2)
            } else {
                m2
            }
        }

        private fun isActiveConnectionMetered(): Boolean {
            return connectivityManager.isActiveNetworkMetered
        }

        private fun repopulateTrackedNetworks(
            opPrefs: OpPrefs,
            networks: LinkedHashSet<NetworkProperties>
        ) {
            val testReachability: Boolean = opPrefs.testReachability

            val activeNetwork = connectivityManager.activeNetwork // null in vpn lockdown mode

            trackedIpv4Networks.clear()
            trackedIpv6Networks.clear()

            // BraveVPNService also fails open, see FAIL_OPEN_ON_NO_NETWORK
            val isAnyNwValidated = networks.any { isNwValidated(it.network) }

            networks.forEach outer@{ prop ->
                val network: Network = prop.network

                val lp = connectivityManager.getLinkProperties(network)
                if (lp == null) {
                    Logger.i(LOG_TAG_CONNECTION, "skipping: $network; no link properties")
                    return@outer
                }

                val isActive = isNetworkSame(network, activeNetwork)
                if (isActive) {
                    Logger.d(LOG_TAG_CONNECTION, "processing active network: $network")
                }

                if (testReachability) {
                    // for active network, ICMP echo is additionally used with TCP and UDP checks
                    // but ICMP echo will always return reachable when app is in rinr mode
                    // so till we have checks for rinr mode, we should not use ICMP reachability
                    val canUseIcmp = false // for now, need to check for rinr mode
                    val useIcmp = isActive && canUseIcmp
                    val has4 = probeConnectivity(ip4probes, network, useIcmp)
                    val has6 = probeConnectivity(ip6probes, network, useIcmp)
                    if (has4) trackedIpv4Networks.add(prop)
                    if (has6) trackedIpv6Networks.add(prop)
                    Logger.i(LOG_TAG_CONNECTION, "nw: has4? $has4, has6? $has6, $prop")
                    if (has4 || has6) return@outer
                    // else: fall-through to check reachability with network capabilities
                }

                // see #createNetworksSet for why we are using hasInternet
                // if no network has been validated, then fail open
                val failOpen = !isAnyNwValidated && BraveVPNService.FAIL_OPEN_ON_NO_NETWORK
                if (hasInternet(network) == true && (failOpen || isNwValidated(network))) {
                    var hasDefaultRoute4 = false
                    var hasDefaultRoute6 = false
                    lp.routes.forEach rloop@{
                        // ref:
                        // androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/net/RouteInfo.java#328
                        hasDefaultRoute4 =
                            hasDefaultRoute4 ||
                                    (it.isDefaultRoute && it.destination.address is Inet4Address)
                        hasDefaultRoute6 =
                            hasDefaultRoute6 ||
                                    (it.isDefaultRoute && it.destination.address is Inet6Address)

                        if (hasDefaultRoute4 && hasDefaultRoute6) return@rloop
                    }

                    if (hasDefaultRoute6) {
                        trackedIpv6Networks.add(prop)
                    }
                    if (hasDefaultRoute4) {
                        trackedIpv4Networks.add(prop)
                    }

                    Logger.i(
                        LOG_TAG_CONNECTION,
                        "nw: default4? $hasDefaultRoute4, default6? $hasDefaultRoute6 for $prop"
                    )
                } else {
                    Logger.i(LOG_TAG_CONNECTION, "skip: $network; no internet capability")
                }
            }

            redoReachabilityIfNeeded(trackedIpv4Networks, trackedIpv6Networks, opPrefs)

            Logger.d(
                LOG_TAG_CONNECTION,
                "repopulate v6: $trackedIpv6Networks,\nv4: $trackedIpv4Networks"
            )
            trackedIpv4Networks = rearrangeNetworks(trackedIpv4Networks)
            trackedIpv6Networks = rearrangeNetworks(trackedIpv6Networks)
        }

        private fun redoReachabilityIfNeeded(
            ipv4: LinkedHashSet<NetworkProperties>,
            ipv6: LinkedHashSet<NetworkProperties>,
            opPrefs: OpPrefs
        ) {
            if (ipv4.isEmpty() && ipv6.isEmpty()) {
                reachabilityCount++
                Logger.i(LOG_TAG_CONNECTION, "redo reachability, try: $reachabilityCount")
                if (reachabilityCount > maxReachabilityCount) return
                val message = Message.obtain()
                // assume opPrefs is immutable
                message.what = opPrefs.msgType
                message.obj = opPrefs
                val delay = TimeUnit.SECONDS.toMillis(10 * reachabilityCount)
                this.sendMessageDelayed(message, delay)
            } else {
                Logger.d(LOG_TAG_CONNECTION, "reset reachability count, prev: $reachabilityCount")
                // reset the reachability count
                reachabilityCount = 0
            }
        }

        private fun hasDifference(
            currentNetworks: LinkedHashSet<NetworkProperties>,
            newNetworks: LinkedHashSet<NetworkProperties>
        ): Boolean {
            if (currentNetworks.size != newNetworks.size) {
                return true
            }
            val cn = currentNetworks.map { it.network.networkHandle }.toHashSet()
            val nn = newNetworks.map { it.network.networkHandle }.toHashSet()
            return Sets.symmetricDifference(cn, nn).isNotEmpty()
        }

        private fun rearrangeNetworks(
            networks: LinkedHashSet<NetworkProperties>
        ): LinkedHashSet<NetworkProperties> {
            val newNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()
            // add active network first, then add non metered networks, then metered networks
            val activeNetwork = connectivityManager.activeNetwork
            val n = networks.firstOrNull { isNetworkSame(it.network, activeNetwork) }
            if (n != null) {
                newNetworks.add(n)
            }
            networks
                .filter { isConnectionNotMetered(it.capabilities) }
                .forEach { newNetworks.add(it) }
            // add remaining networks, ie, metered networks
            networks
                .filter { !isConnectionNotMetered(it.capabilities) }
                .forEach { newNetworks.add(it) }
            return newNetworks
        }

        private fun isConnectionNotMetered(capabilities: NetworkCapabilities?): Boolean {
            return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                ?: false
        }

        /**
         * Create network set(available networks) based on the user preference. The first element of
         * the list must be active network. requireAllNetwork - if true adds all networks to the set
         * else adds active network alone to the set.
         */
        private fun createNetworksSet(
            activeNetwork: Network?,
            networkSet: Set<Network>
        ): LinkedHashSet<NetworkProperties> {
            val newNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()
            activeNetwork?.let {
                val activeCap = connectivityManager.getNetworkCapabilities(activeNetwork)
                val activeLp = connectivityManager.getLinkProperties(activeNetwork)
                val nwType =
                    networkType(activeCap) + ", NotMetered?" + isConnectionNotMetered(activeCap)
                val activeProp =
                    if (activeCap != null) {
                        NetworkProperties(it, activeCap, activeLp, nwType)
                    } else {
                        null
                    }
                // test for internet capability iff opPrefs.testReachability is false
                if (/*hasInternet(it) == true &&*/ activeProp != null && isVPN(it) == false) {
                    newNetworks.add(activeProp)
                }
            }
            val networks =
                if (networkSet.isEmpty()) {
                    Logger.d(LOG_TAG_CONNECTION, "networkSet is empty")
                    connectivityManager.allNetworks
                } else {
                    Logger.d(LOG_TAG_CONNECTION, "networkSet size: ${networkSet.size}")
                    networkSet.toTypedArray()
                }

            networks.forEach {
                val cap = connectivityManager.getNetworkCapabilities(it)
                val lp = connectivityManager.getLinkProperties(it)
                val prop =
                    if (cap != null) {
                        val nwType =
                            networkType(cap) + ", NotMetered?" + isConnectionNotMetered(cap)
                        NetworkProperties(it, cap, lp, nwType)
                    } else {
                        null
                    }
                if (isNetworkSame(it, activeNetwork)) {
                    return@forEach
                }

                // test for internet capability iff opPrefs.testReachability is false
                if (/*hasInternet(it) == true &&*/ prop != null && isVPN(it) == false) {
                    newNetworks.add(prop)
                }
            }

            return newNetworks
        }

        private fun probeConnectivity(
            probes: Collection<String>,
            nw: Network?,
            isActive: Boolean = false
        ): Boolean = runBlocking {
            var ok = false
            probes.forEach { ip ->
                if (isActive) {
                    ok = isReachable(ip)
                }
                if (!ok) {
                    ok = isReachableTcpUdp(nw, ip)
                }
                if (ok) {
                    return@forEach // break
                }
            }
            return@runBlocking ok
        }

        private suspend fun isReachableTcpUdp(nw: Network?, host: String): Boolean {
            try {
                // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
                TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())

                val yes = tcp80(nw, host) || udp53(nw, host) || tcp53(nw, host)

                Logger.d(LOG_TAG_CONNECTION, "$host isReachable on network($nw): $yes")
                return yes
            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "err isReachable: ${e.message}")
            }
            return false
        }

        private suspend fun isReachable(host: String): Boolean {
            // The 'isReachable()' function sends an ICMP echo request to the target host. In the
            // event of the 'Rethink within Rethink' option being used, ICMP checks will fail.
            // Ideally, there is no need to perform ICMP checks. However, 'Rethink within
            // Rethink' is not supplied to this handler, these checks will be carried out but will
            // result in failure.
            try {
                val timeout = 500 // ms
                // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
                TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())
                // https://developer.android.com/reference/java/net/InetAddress#isReachable(int)
                // network.getByName() binds the socket to that network; InetAddress.getByName.
                // isReachable(network-interface, ttl, timeout) cannot be used here since
                // "network-interface" is a java-construct while "Network" is an android-construct
                // InetAddress.getByName() will bind the socket to the default active network.
                val yes = InetAddress.getByName(host).isReachable(timeout)

                Logger.d(LOG_TAG_CONNECTION, "$host isReachable on network: $yes")
                return yes
            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "err isReachable: ${e.message}")
            }
            return false
        }

        // https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-benchmark-release/java/net/Inet6AddressImpl.java#217
        private suspend fun tcp80(nw: Network?, host: String): Boolean {
            val timeout = 500 // ms
            val port80 = 80 // port
            var socket: Socket? = null
            try {
                // port 7 is echo port, blocked by most firewalls. use port 80 instead
                val s = InetSocketAddress(host, port80)
                socket = Socket()
                nw?.bindSocket(socket)
                socket.connect(s, timeout)
                val c = socket.isConnected
                val b = socket.isBound
                Logger.d(LOG_TAG_CONNECTION, "tcpEcho80: $host, ${nw?.networkHandle}: $c, $b")

                return true
            } catch (e: IOException) {
                Logger.w(LOG_TAG_CONNECTION, "err tcpEcho80: ${e.message}, ${e.cause}")
                val cause: Throwable = e.cause ?: return false

                return (cause is ErrnoException && cause.errno == ECONNREFUSED)
            } catch (e: IllegalArgumentException) {
                Logger.w(LOG_TAG_CONNECTION, "err tcpEcho80: ${e.message}, ${e.cause}")
                return false
            } catch (e: SecurityException) {
                Logger.w(LOG_TAG_CONNECTION, "err tcpEcho80: ${e.message}, ${e.cause}")
                return false
            } finally {
                clos(socket)
            }
        }

        private suspend fun tcp53(nw: Network?, host: String): Boolean {
            val timeout = 500 // ms
            val port53 = 53 // port
            var socket: Socket? = null

            try {
                socket = Socket()
                val s = InetSocketAddress(host, port53)
                nw?.bindSocket(socket)
                socket.connect(s, timeout)
                val c = socket.isConnected
                val b = socket.isBound
                Logger.d(LOG_TAG_CONNECTION, "tcpEcho53: $host, ${nw?.networkHandle}: $c, $b")
                return true
            } catch (e: IOException) {
                Logger.w(LOG_TAG_CONNECTION, "err tcpEcho53: ${e.message}, ${e.cause}")
                val cause: Throwable = e.cause ?: return false

                return (cause is ErrnoException && cause.errno == ECONNREFUSED)
            } catch (e: IllegalArgumentException) {
                Logger.w(LOG_TAG_CONNECTION, "err tcpEcho53: ${e.message}, ${e.cause}")
            } catch (e: SecurityException) {
                Logger.w(LOG_TAG_CONNECTION, "err tcpEcho53: ${e.message}, ${e.cause}")
            } finally {
                clos(socket)
            }
            return false
        }

        private suspend fun udp53(nw: Network?, host: String): Boolean {
            val timeout = 500 // ms
            val port53 = 53 // port
            var socket: DatagramSocket? = null

            try {
                socket = DatagramSocket()
                val s = InetSocketAddress(host, port53)
                nw?.bindSocket(socket)
                socket.soTimeout = timeout
                socket.connect(s)
                val c = socket.isConnected
                val b = socket.isBound
                Logger.d(LOG_TAG_CONNECTION, "udpEcho: $host, ${nw?.networkHandle}: $c, $b")
                return true
            } catch (e: IOException) {
                Logger.w(LOG_TAG_CONNECTION, "err udpEcho: ${e.message}, ${e.cause}")
                val cause: Throwable = e.cause ?: return false

                return (cause is ErrnoException && cause.errno == ECONNREFUSED)
            } catch (e: IllegalArgumentException) {
                Logger.w(LOG_TAG_CONNECTION, "err udpEcho: ${e.message}, ${e.cause}")
            } catch (e: SecurityException) {
                Logger.w(LOG_TAG_CONNECTION, "err udpEcho: ${e.message}, ${e.cause}")
            } finally {
                clos(socket)
            }
            return false
        }

        private fun clos(socket: Closeable?) {
            try {
                socket?.close()
            } catch (ignored: IOException) {
            }
        }

        private fun hasInternet(network: Network?): Boolean? {
            // TODO: consider checking for NET_CAPABILITY_NOT_SUSPENDED, NET_CAPABILITY_VALIDATED?
            if (network == null) return false

            return connectivityManager
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun isNwValidated(network: Network): Boolean {
            return connectivityManager
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }

        private fun isVPN(network: Network): Boolean? {
            return connectivityManager
                .getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        // convert long to int, if the value is out of range return 10000
        private fun Long.toIntOrDefault(): Int {
            return if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
                10000
            } else {
                this.toInt()
            }
        }
    }
}
