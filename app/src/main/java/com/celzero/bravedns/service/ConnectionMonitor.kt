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
import android.system.OsConstants.RT_SCOPE_UNIVERSE
import android.util.Log
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.Logger.Companion.LOG_TAG_CONNECTION
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isNetworkSame
import com.google.common.collect.Sets
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ConnectionMonitor(context: Context, networkListener: NetworkListener) :
    ConnectivityManager.NetworkCallback(), KoinComponent {

    private val androidValidatedNetworks = false

    private val networkSet: MutableSet<Network> = mutableSetOf()

    private val networkRequest: NetworkRequest =
        if (androidValidatedNetworks)
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .apply { if (isAtleastS()) setIncludeOtherUidNetworks(true) }
                .build()
        else
        // add cellular, wifi, bluetooth, ethernet, vpn, wifi aware, low pan
        NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .apply { if (isAtleastS()) setIncludeOtherUidNetworks(true) }
                // api27: .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                // api26: .addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN)
                .build()

    // An Android handler thread internally operates on a looper
    // ref:
    // alvinalexander.com/java/jwarehouse/android/core/java/android/app/IntentService.java.shtml
    private var handlerThread: HandlerThread
    private var serviceHandler: NetworkRequestHandler? = null
    private val persistentState by inject<PersistentState>()

    private var connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

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
        val testReachability: Boolean,
        val dualStack: Boolean
    )

    init {
        try {
            connectivityManager.registerNetworkCallback(networkRequest, this)
        } catch (e: Exception) {
            Log.w(LOG_TAG_CONNECTION, "Exception while registering network callback", e)
            networkListener.onNetworkRegistrationFailed()
        }
        this.handlerThread = HandlerThread(NetworkRequestHandler::class.simpleName)
        this.handlerThread.start()
        this.serviceHandler = NetworkRequestHandler(context, handlerThread.looper, networkListener)
    }

    interface NetworkListener {
        fun onNetworkDisconnected(networks: UnderlyingNetworks)

        fun onNetworkConnected(networks: UnderlyingNetworks)

        fun onNetworkRegistrationFailed()
    }

    fun onVpnStop() {
        connectivityManager.unregisterNetworkCallback(this)
        destroy()
    }

    private fun destroy() {
        this.serviceHandler?.removeCallbacksAndMessages(null)
        this.handlerThread.quitSafely()
        this.serviceHandler = null
    }

    override fun onAvailable(network: Network) {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onAvailable: ${network.networkHandle}, $network")
        networkSet.add(network)
        val cap = connectivityManager.getNetworkCapabilities(network)
        if (DEBUG)
            Log.d(
                LOG_TAG_CONNECTION,
                "onAvailable: ${network.networkHandle}, $network, ${networkSet.size}, ${networkType(cap)}"
            )
        handleNetworkChange()
    }

    override fun onLost(network: Network) {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onLost, ${network.networkHandle}, $network")
        networkSet.remove(network)
        val cap = connectivityManager.getNetworkCapabilities(network)
        if (DEBUG)
            Log.d(
                LOG_TAG_CONNECTION,
                "onLost: ${network.networkHandle}, $network, ${networkSet.size}, ${networkType(cap)}"
            )
        handleNetworkChange()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if (DEBUG)
            Log.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged, ${network.networkHandle}, $network")
        handleNetworkChange(isForceUpdate = false, TimeUnit.SECONDS.toMillis(3))
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if (DEBUG)
            Log.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged: ${network.networkHandle}, $network")
        handleNetworkChange(isForceUpdate = true, TimeUnit.SECONDS.toMillis(3))
    }

    /**
     * Handles user preference changes, ie, when the user elects to see either multiple underlying
     * networks, or just one (the active network).
     */
    fun onUserPreferenceChangedLocked() {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onUserPreferenceChanged")
        handleNetworkChange(isForceUpdate = true)
    }

    /**
     * Force updates the VPN's underlying network based on the preference. Will be initiated when
     * the VPN start is completed.
     */
    fun onVpnStartLocked() {
        Log.i(LOG_TAG_CONNECTION, "new vpn is created force update the network")
        handleNetworkChange(isForceUpdate = true)
    }

    private fun handleNetworkChange(
        isForceUpdate: Boolean = false,
        delay: Long = TimeUnit.SECONDS.toMillis(1)
    ) {
        val isDualStack = InternetProtocol.isAuto(persistentState.internetProtocolType)
        val testReachability = isDualStack && !androidValidatedNetworks
        val msg =
            constructNetworkMessage(
                if (persistentState.useMultipleNetworks) MSG_ADD_ALL_NETWORKS
                else MSG_ADD_ACTIVE_NETWORK,
                isForceUpdate,
                testReachability,
                isDualStack
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
        testReachability: Boolean,
        dualStack: Boolean
    ): Message {
        val opPrefs = OpPrefs(what, networkSet, isForceUpdate, testReachability, dualStack)
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
        var isActiveNetworkMetered: Boolean,
        var lastUpdated: Long
    )

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(
        ctx: Context,
        looper: Looper,
        val listener: NetworkListener
    ) : Handler(looper) {
        // number of times the reachability check is performed due to failures
        private var reachabilityCount = 0L
        private val maxReachabilityCount = 10L

        companion object {
            private const val MAX_INTERFACE_MTU = 1500 // same as BraveVpnService#VPN_INTERFACE_MTU
            private const val MIN_INTERFACE_MTU = 1280
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

        var connectivityManager: ConnectivityManager =
            ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

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

            Log.i(
                LOG_TAG_CONNECTION,
                "Connected network: ${newActiveNetwork?.networkHandle} ${networkType(newActiveNetworkCap)
                            }, new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, auto? ${opPrefs.testReachability}"
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

            Log.i(
                LOG_TAG_CONNECTION,
                "process message MESSAGE_AVAILABLE_NETWORK, ${currentNetworks}, ${newNetworks}; new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, auto? ${opPrefs.testReachability}"
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
            val sz = trackedIpv4Networks.size + trackedIpv6Networks.size
            Log.i(
                LOG_TAG_CONNECTION,
                "inform network change: ${sz}, all? $useActiveNetwork, metered? $isActiveNetworkMetered"
            )
            if (sz > 0) {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        trackedIpv4Networks.map { it }, // map to produce shallow copy
                        trackedIpv6Networks.map { it },
                        useActiveNetwork,
                        determineMtu(useActiveNetwork),
                        isActiveNetworkMetered,
                        SystemClock.elapsedRealtime()
                    )
                if (DEBUG) {
                    trackedIpv4Networks.forEach {
                        Log.d(LOG_TAG_CONNECTION, "inform4: ${it.network}, ${it.networkType}, $sz")
                    }
                    trackedIpv6Networks.forEach {
                        Log.d(LOG_TAG_CONNECTION, "inform6: ${it.network}, ${it.networkType}, $sz")
                    }
                }
                listener.onNetworkConnected(underlyingNetworks)
            } else {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        emptyList(),
                        emptyList(),
                        useActiveNetwork,
                        MAX_INTERFACE_MTU,
                        isActiveNetworkMetered = false,
                        SystemClock.elapsedRealtime()
                    )
                listener.onNetworkDisconnected(underlyingNetworks)
            }
        }

        private fun determineMtu(useActiveNetwork: Boolean): Int {
            if (!isAtleastQ()) {
                return MAX_INTERFACE_MTU
            }

            var mtu =
                if (useActiveNetwork) {
                    val activeNetwork = connectivityManager.activeNetwork
                    // consider first network in underlying network as active network, in case
                    // if active network is null
                    val lp4 =
                        trackedIpv4Networks.firstOrNull()?.linkProperties
                            ?: connectivityManager.getLinkProperties(activeNetwork)
                    val lp6 =
                        trackedIpv6Networks.firstOrNull()?.linkProperties
                            ?: connectivityManager.getLinkProperties(activeNetwork)
                    minNonZeroMtu(lp4?.mtu, lp6?.mtu)
                } else {
                    var prev4Lp: LinkProperties? = null
                    var prev6Lp: LinkProperties? = null
                    var minMtu4: Int = MAX_INTERFACE_MTU
                    var minMtu6: Int = MAX_INTERFACE_MTU
                    // parse through all the networks and get the minimum mtu
                    trackedIpv4Networks.forEach {
                        if (DEBUG)
                            Log.d(LOG_TAG_CONNECTION, "mtu for ipv4: ${it.linkProperties?.mtu}")
                        val c = it.linkProperties
                        minMtu4 = minNonZeroMtu(c?.mtu, prev4Lp?.mtu) ?: minMtu4
                        prev4Lp = c
                    }
                    trackedIpv6Networks.forEach {
                        if (DEBUG)
                            Log.d(LOG_TAG_CONNECTION, "mtu for ipv6: ${it.linkProperties?.mtu}")
                        val c = it.linkProperties
                        minMtu6 = minNonZeroMtu(c?.mtu, prev6Lp?.mtu) ?: minMtu6
                        prev6Lp = c
                    }
                    if (DEBUG)
                        Log.d(LOG_TAG_CONNECTION, "min mtu for v4 nws: $minMtu4, v6 nws: $minMtu6")
                    min(minMtu4, minMtu6)
                }

            // mtu can be 0 or null, set it to default value
            // LinkProperties#getMtu() returns 0 if the value is not set
            // minNonZeroMtu can returns null
            if (mtu == null || mtu == 0) {
                mtu = MAX_INTERFACE_MTU
                Log.i(LOG_TAG_CONNECTION, "mtu is 0, setting to default $MAX_INTERFACE_MTU")
            }

            // min value is 1280, set it to 1280 if it is less than 1280
            if (mtu < MIN_INTERFACE_MTU) {
                mtu = MIN_INTERFACE_MTU
                Log.i(LOG_TAG_CONNECTION, "mtu is less than 1280, setting to 1280")
            }

            Log.i(LOG_TAG_CONNECTION, "current mtu: $mtu")
            return mtu
        }

        private fun minNonZeroMtu(m1: Int?, m2: Int?): Int? {
            return if (m1 != null && m1 > 0 && m2 != null && m2 > 0) {
                // mtu can be null when lp is null
                // mtu can be 0 when the value is not set, see:LinkProperties#getMtu()
                min(m1, m2)
            } else if (m1 != null && m1 > 0) {
                m1
            } else if (m2 != null && m2 > 0) {
                m2
            } else {
                null
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
            val dualStack: Boolean = opPrefs.dualStack

            val activeNetwork = connectivityManager.activeNetwork // null in vpn lockdown mode

            trackedIpv4Networks.clear()
            trackedIpv6Networks.clear()

            networks.forEach outer@{ prop ->
                val network: Network = prop.network

                val lp = connectivityManager.getLinkProperties(network)
                if (lp == null) {
                    Log.i(LOG_TAG_CONNECTION, "skipping: $network; no link properties")
                    return@outer
                }

                val isActive = isNetworkSame(network, activeNetwork)
                if (isActive) {
                    if (DEBUG) Log.d(LOG_TAG_CONNECTION, "processing active network: $network")
                }

                var has4 = false
                var has6 = false

                if (testReachability) {
                    // for active network, ICMP echo is additionally used with TCP and UDP checks
                    // but ICMP echo will always return reachable when app is in rinr mode
                    // so till we have checks for rinr mode, we should not use ICMP reachability
                    val canUseIcmp = false // for now, need to check for rinr mode
                    val useIcmp = isActive && canUseIcmp
                    has4 = probeConnectivity(ip4probes, network, useIcmp)
                    has6 = probeConnectivity(ip6probes, network, useIcmp)
                    if (has4) trackedIpv4Networks.add(prop)
                    if (has6) trackedIpv6Networks.add(prop)
                    Log.i(LOG_TAG_CONNECTION, "nw: has4? $has4, has6? $has6, $prop")
                    if (has4 || has6) return@outer
                    // else: fall-through to check reachability with network capabilities
                }

                lp.linkAddresses.forEach inner@{ addr ->
                    if (has4 && has6) return@outer
                    // skip if the address scope is not RT_SCOPE_UNIVERSE, as there are some
                    // addresses which are not reachable outside the network but we will end up
                    // adding those address for ipv4/ipv6 mode, reachability check will fail in
                    // dual stack mode.
                    if (!dualStack && addr.scope != RT_SCOPE_UNIVERSE) {
                        Log.i(
                            LOG_TAG_CONNECTION,
                            "skipping: ${addr.address.hostAddress} with scope: ${addr.scope}"
                        )
                        return@inner
                    }

                    val address = IPAddressString(addr.address.hostAddress?.toString())

                    if (hasInternet(network) == true) {
                        if (address.isIPv6) {
                            has6 = true
                            trackedIpv6Networks.add(prop)
                            Log.i(
                                LOG_TAG_CONNECTION,
                                "adding ipv6(${trackedIpv6Networks.size}): $prop; for $address"
                            )
                        } else if (address.isIPv4) {
                            has4 = true
                            // see #createNetworksSet for why we are using hasInternet
                            trackedIpv4Networks.add(prop)
                            Log.i(
                                LOG_TAG_CONNECTION,
                                "adding ipv4(${trackedIpv4Networks.size}): $prop; for $address"
                            )
                        } else {
                            Log.i(LOG_TAG_CONNECTION, "unknown lnaddr: $network; $address")
                        }
                    }
                }
            }

            redoReachabilityIfNeeded(trackedIpv4Networks, trackedIpv6Networks, opPrefs)

            Log.d(
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
                Log.i(LOG_TAG_CONNECTION, "redo reachability, try: $reachabilityCount")
                if (reachabilityCount > maxReachabilityCount) return
                val message = Message.obtain()
                // assume opPrefs is immutable
                message.what = opPrefs.msgType
                message.obj = opPrefs
                val delay = TimeUnit.SECONDS.toMillis(10 * reachabilityCount)
                this.sendMessageDelayed(message, delay)
            } else {
                Log.d(LOG_TAG_CONNECTION, "reset reachability count, prev: $reachabilityCount")
                // reset the reachability count
                reachabilityCount = 0
            }
        }

        private fun hasDifference(
            currentNetworks: LinkedHashSet<NetworkProperties>,
            newNetworks: LinkedHashSet<NetworkProperties>
        ): Boolean {
            val cn = currentNetworks.map { it.network }.toSet()
            val nn = newNetworks.map { it.network }.toSet()
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
            val nonMeteredNetworks = networks.filter { isConnectionNotMetered(it.capabilities) }
            nonMeteredNetworks.forEach {
                if (!newNetworks.contains(it)) {
                    newNetworks.add(it)
                }
            }
            // add remaining networks, ie, metered networks
            networks.forEach {
                if (!newNetworks.contains(it)) {
                    newNetworks.add(it)
                }
            }
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
                if (/*hasInternet(it) == true &&*/ isVPN(it) == false) {
                    if (activeProp != null) {
                        newNetworks.add(activeProp)
                    }
                } else {
                    // no-op
                }
            }
            val networks =
                if (networkSet.isEmpty()) {
                    if (DEBUG) Log.d(LOG_TAG_CONNECTION, "networkSet is empty")
                    connectivityManager.allNetworks
                } else {
                    if (DEBUG)
                        Log.d(
                            LOG_TAG_CONNECTION,
                            "networkSet is not empty, size: ${networkSet.size}"
                        )
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
                if (/*hasInternet(it) == true &&*/ isVPN(it) == false) {
                    if (prop != null) {
                        newNetworks.add(prop)
                    }
                } else {
                    // no-op
                }
            }

            return newNetworks
        }

        private fun probeConnectivity(
            probes: Collection<String>,
            nw: Network?,
            isActive: Boolean = false
        ): Boolean = runBlocking {
            coroutineScope {
                // select the first reachable IP / domain and return true if any of them is
                // reachable
                select<Boolean> {
                        probes.forEach { ip ->
                            async {
                                    var ok = false
                                    if (isActive) {
                                        ok = isReachable(ip)
                                    }
                                    if (!ok) {
                                        ok = isReachableTcpUdp(nw, ip)
                                    }
                                    ok
                                }
                                .onAwait { it }
                        }
                    }
                    .also { coroutineContext.cancelChildren() }
            }
        }

        private fun isReachableTcpUdp(nw: Network?, host: String): Boolean {
            try {
                // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
                TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())

                val yes = tcp80(nw, host) || udp53(nw, host) || tcp53(nw, host)

                if (DEBUG) Log.d(LOG_TAG_CONNECTION, "$host isReachable on network($nw): $yes")
                return yes
            } catch (e: Exception) {
                Log.w(LOG_TAG_CONNECTION, "err isReachable: ${e.message}")
            }
            return false
        }

        private fun isReachable(host: String): Boolean {
            // The 'isReachable()' function sends an ICMP echo request to the target host. In the
            // event of the 'Rethink within Rethink' option being used, ICMP checks will fail.
            // Ideally, there is no need to perform ICMP checks. However, 'Rethink within
            // Rethink' is not supplied to this handler, these checks will be carried out but will
            // result in failure.
            try {
                val onesec = 1000 // ms
                // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
                TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())
                // https://developer.android.com/reference/java/net/InetAddress#isReachable(int)
                // network.getByName() binds the socket to that network; InetAddress.getByName.
                // isReachable(network-interface, ttl, timeout) cannot be used here since
                // "network-interface" is a java-construct while "Network" is an android-construct
                // InetAddress.getByName() will bind the socket to the default active network.
                val yes = InetAddress.getByName(host).isReachable(onesec)

                if (DEBUG) Log.d(LOG_TAG_CONNECTION, "$host isReachable on network: $yes")
                return yes
            } catch (e: Exception) {
                Log.w(LOG_TAG_CONNECTION, "err isReachable: ${e.message}")
            }
            return false
        }

        // https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-benchmark-release/java/net/Inet6AddressImpl.java#217
        private fun tcp80(nw: Network?, host: String): Boolean {
            val onesec = 1000 // ms
            val port80 = 80 // port
            var socket: Socket? = null
            try {
                // port 7 is echo port, blocked by most firewalls. use port 80 instead
                val s = InetSocketAddress(host, port80)
                socket = Socket()
                nw?.bindSocket(socket)
                socket.connect(s, onesec)
                val c = socket.isConnected
                val b = socket.isBound
                if (DEBUG)
                    Log.d(LOG_TAG_CONNECTION, "tcpEcho80: $host, ${nw?.networkHandle}: $c, $b")

                return true
            } catch (e: IOException) {
                Log.w(LOG_TAG_CONNECTION, "err tcpEcho80: ${e.message}, ${e.cause}")
                val cause: Throwable = e.cause ?: return false

                return (cause is ErrnoException && cause.errno == ECONNREFUSED)
            } catch (e: IllegalArgumentException) {
                Log.w(LOG_TAG_CONNECTION, "err tcpEcho80: ${e.message}, ${e.cause}")
                return false
            } catch (e: SecurityException) {
                Log.w(LOG_TAG_CONNECTION, "err tcpEcho80: ${e.message}, ${e.cause}")
                return false
            } finally {
                clos(socket)
            }
        }

        private fun tcp53(nw: Network?, host: String): Boolean {
            val port53 = 53 // port
            var socket: Socket? = null

            try {
                socket = Socket()
                val s = InetSocketAddress(host, port53)
                nw?.bindSocket(socket)
                socket.connect(s)
                val c = socket.isConnected
                val b = socket.isBound
                if (DEBUG)
                    Log.d(LOG_TAG_CONNECTION, "tcpEcho53: $host, ${nw?.networkHandle}: $c, $b")
                return true
            } catch (e: IOException) {
                Log.w(LOG_TAG_CONNECTION, "err tcpEcho53: ${e.message}, ${e.cause}")
                val cause: Throwable = e.cause ?: return false

                return (cause is ErrnoException && cause.errno == ECONNREFUSED)
            } catch (e: IllegalArgumentException) {
                Log.w(LOG_TAG_CONNECTION, "err tcpEcho53: ${e.message}, ${e.cause}")
            } catch (e: SecurityException) {
                Log.w(LOG_TAG_CONNECTION, "err tcpEcho53: ${e.message}, ${e.cause}")
            } finally {
                clos(socket)
            }
            return false
        }

        private fun udp53(nw: Network?, host: String): Boolean {
            val port53 = 53 // port
            var socket: DatagramSocket? = null

            try {
                socket = DatagramSocket()
                val s = InetSocketAddress(host, port53)
                nw?.bindSocket(socket)
                socket.connect(s)
                val c = socket.isConnected
                val b = socket.isBound
                if (DEBUG) Log.d(LOG_TAG_CONNECTION, "udpEcho: $host, ${nw?.networkHandle}: $c, $b")
                return true
            } catch (e: IOException) {
                Log.w(LOG_TAG_CONNECTION, "err udpEcho: ${e.message}, ${e.cause}")
                val cause: Throwable = e.cause ?: return false

                return (cause is ErrnoException && cause.errno == ECONNREFUSED)
            } catch (e: IllegalArgumentException) {
                Log.w(LOG_TAG_CONNECTION, "err udpEcho: ${e.message}, ${e.cause}")
            } catch (e: SecurityException) {
                Log.w(LOG_TAG_CONNECTION, "err udpEcho: ${e.message}, ${e.cause}")
            } finally {
                clos(socket)
            }
            return false
        }

        private fun clos(socket: Closeable?) {
            try {
                socket?.close()
            } catch (ignored: IOException) {}
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
