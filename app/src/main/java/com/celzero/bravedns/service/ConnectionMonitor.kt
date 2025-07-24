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
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

        fun networkType(netCap: NetworkCapabilities?): String {
            val a =
                if (netCap?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    "VPN"
                } else if (
                    netCap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                ) {
                    "WiFi"
                } else if (
                    netCap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ==
                    true
                ) {
                    "Cellular"
                } else {
                    "Unknown"
                }
            return a
        }


        fun netId(nwHandle: Long?): Long {
            if (nwHandle == null) {
                return -1L
            }
            // ref: cs.android.com/android/platform/superproject/main/+/main:packages/modules/Connectivity/framework/src/android/net/Network.java;drc=0209c366627e98d6311629a0592c6e22be7d13e0;l=491
            return nwHandle shr (32)
        }
    }

    // data class that holds the below information for handlers to process the network changes
    // all values in OpPrefs should always remain immutable
    data class OpPrefs(
        val msgType: Int,
        val networkSet: Set<Network>,
        val isForceUpdate: Boolean,
        val testReachability: Boolean,
        val failOpenOnNoNetwork: Boolean
    )

    // capabilities used only to indicate whether the network is metered or not
    // TODO: send only the required capabilities to the handler instead of the whole
    data class ProbeResult(val ip: String, val ok: Boolean, val capabilities: NetworkCapabilities?)

    interface NetworkListener {
        fun onNetworkDisconnected(networks: UnderlyingNetworks)

        fun onNetworkConnected(networks: UnderlyingNetworks)

        fun onNetworkRegistrationFailed()
    }

    override fun onAvailable(network: Network) {
        val res = networkSet.add(network)
        if (!res) {
            networkSet.remove(network)
            networkSet.add(network) // re-add to ensure the latest network is used
        }
        val cap = connectivityManager.getNetworkCapabilities(network)
        Logger.d(
            LOG_TAG_CONNECTION,
            "onAvailable: ${network.networkHandle}, netid: ${netId(network.networkHandle)}, $network, ${networkSet.size}, ${networkType(cap)}"
        )
        handleNetworkChange()
    }

    override fun onLost(network: Network) {
        networkSet.remove(network)
        val cap = connectivityManager.getNetworkCapabilities(network)
        Logger.d(
            LOG_TAG_CONNECTION,
            "onLost: ${network.networkHandle}, netid: ${netId(network.networkHandle)}, $network, ${networkSet.size}, ${networkType(cap)}"
        )
        handleNetworkChange()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        Logger.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged, ${network.networkHandle}, netid: ${netId(network.networkHandle)}, $network")
        val res = networkSet.add(network) // ensure the network is added to the set
        if (!res) {
            networkSet.remove(network)
            networkSet.add(network) // re-add to ensure the latest network is used
        }
        handleNetworkChange(isForceUpdate = true, TimeUnit.SECONDS.toMillis(3))
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        Logger.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged: ${network.networkHandle}, netid: ${netId(network.networkHandle)}, $network")
        val res = networkSet.add(network) // ensure the network is added to the set
        if (!res) {
            networkSet.remove(network)
            networkSet.add(network) // re-add to ensure the latest network is used
        }
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

    suspend fun probeIpOrUrl(ipOrUrl: String): ProbeResult? {
        Logger.d(LOG_TAG_CONNECTION, "pingIpOrUrl: $ipOrUrl")
        return serviceHandler?.let {
            val res = it.probeIpOrUrl(ipOrUrl, networkSet)
            Logger.d(LOG_TAG_CONNECTION, "pingIpOrUrl: $ipOrUrl, $res, nws: ${networkSet.size}")
            res
        }
    }

    /**
     * Force updates the VPN's underlying network based on the preference. Will be initiated when
     * the VPN start is completed. Always called from the main thread
     */
    fun onVpnStart(context: Context): Boolean {
        val isNewVpn = serviceHandler == null

        if (this.serviceHandler != null) {
            Logger.w(LOG_TAG_CONNECTION, "connection monitor is already running")
            return isNewVpn
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
            return isNewVpn
        }

        val handlerThread = HandlerThread(NetworkRequestHandler::class.simpleName)
        handlerThread.start()
        // Filter out empty strings from probe IPs to avoid unnecessary probe attempts
        val ips = IpsAndUrlToProbe(
            persistentState.pingv4Ips.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            persistentState.pingv6Ips.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            persistentState.pingv4Url.trim(),
            persistentState.pingv6Url.trim()
        )
        this.serviceHandler =
            NetworkRequestHandler(connectivityManager, handlerThread.looper, networkListener, ips)
        handleNetworkChange(isForceUpdate = true)

        return isNewVpn
    }


    // Always called from the main thread
    fun onVpnStop() {
        try {
            this.serviceHandler?.removeCallbacksAndMessages(null)
            serviceHandler?.looper?.quitSafely()
            this.serviceHandler = null
            // check if connectivity manager is initialized as it is lazy initialized
            if (::connectivityManager.isInitialized) {
                connectivityManager.unregisterNetworkCallback(this)
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "ConnectionMonitor: err while unregistering", e)
        }
    }

    private fun handleNetworkChange(
        isForceUpdate: Boolean = true,
        delay: Long = TimeUnit.SECONDS.toMillis(1)
    ) {
        val dualStack =
            InternetProtocol.getInternetProtocol(persistentState.internetProtocolType).isIPv46()
        val testReachability = dualStack && persistentState.connectivityChecks
        val failOpenOnNoNetwork = persistentState.failOpenOnNoNetwork
        val msg =
            constructNetworkMessage(
                if (persistentState.useMultipleNetworks) MSG_ADD_ALL_NETWORKS
                else MSG_ADD_ACTIVE_NETWORK,
                isForceUpdate,
                testReachability,
                failOpenOnNoNetwork
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
        failOpenOnNoNetwork: Boolean
    ): Message {
        val opPrefs = OpPrefs(what, networkSet.toSet(), isForceUpdate, testReachability, failOpenOnNoNetwork)
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
        val vpnRoutes: Pair<Boolean, Boolean>?,
        val useActive: Boolean,
        val minMtu: Int,
        var isActiveNetworkMetered: Boolean, // may be updated by client listener
        var isActiveNetworkCellular: Boolean,
        var lastUpdated: Long, // may be updated by client listener
        val dnsServers: Map<InetAddress, Network>,
    )

    data class IpsAndUrlToProbe(
        val ip4probes: Collection<String>,
        val ip6probes: Collection<String>,
        val url4Probe: String,
        val url6Probe: String
    )

    /**
     * Handles network messages from the connectivity manager callback.
     *
     * This class is responsible for processing network change events, determining network
     * properties (like reachability, MTU, DNS servers), and informing a [NetworkListener]
     * about these changes. It manages a set of current underlying networks for the VPN,
     * prioritizing active and non-metered networks.
     *
     * It uses a [Handler] to process messages on a specific [Looper] to avoid blocking
     * the main thread.
     *
     * Key responsibilities include:
     * - Processing messages for adding active or all available networks.
     * - Determining VPN protocol support (IPv4/IPv6) based on routes.
     * - Informing the listener about network connections and disconnections, including
     *   details like MTU, metered status, cellular status, and DNS servers.
     * - Repopulating tracked IPv4 and IPv6 networks based on reachability and network capabilities.
     * - Retrying reachability checks if no usable networks are found initially.
     * - Rearranging networks to prioritize active and non-metered connections.
     * - Probing IP addresses for connectivity on specific networks.
     * - Performing TCP/UDP reachability checks as fallbacks.
     *
     * @property cm The system's [ConnectivityManager] instance.
     * @property listener The [NetworkListener] to be informed of network changes.
     * @property ipsAndUrl An [IpsAndUrlToProbe] object containing IP addresses for reachability checks.
     * @param looper The [Looper] on which this handler will process messages.
     */// Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(
        val cm: ConnectivityManager,
        looper: Looper,
        val listener: NetworkListener,
        ipsAndUrl: IpsAndUrlToProbe
    ) : Handler(looper) {

        // number of times the reachability check is performed due to failures
        private var reachabilityCount = 0L
        private val maxReachabilityCount = 10L

        companion object {
            private const val DEFAULT_MTU = 1280 // same as BraveVpnService#VPN_INTERFACE_MTU
            private const val MIN_MTU = 1280
            // variable to check whether to rely on the TCP/UDP reachability checks from
            // kotlin end instead of tunnel reachability checks, set false by default for now
            // TODO: set it to true when the reachability checks are required to be done from
            // kotlin end
            private const val USE_KOTLIN_REACHABILITY_CHECKS = false
        }

        val ip4probes = ipsAndUrl.ip4probes
        // probing with domain names is not viable because some domains will resolve to both
        // ipv4 and ipv6 addresses. So, we use ipv6 addresses for probing ipv6 connectivity.
        val ip6probes = ipsAndUrl.ip6probes
        val url4Probe = ipsAndUrl.url4Probe
        val url6Probe = ipsAndUrl.url6Probe

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
            val newActiveNetwork = cm.activeNetwork
            val newActiveNetworkCap = cm.getNetworkCapabilities(newActiveNetwork)
            // set active network's connection status
            val isActiveNetworkMetered = isActiveConnectionMetered()
            val isActiveNetworkCellular = isActiveConnectionCellular(newActiveNetwork)
            val newNetworks = createNetworksSet(newActiveNetwork, opPrefs.networkSet)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)
            val vpnRoutes = determineVpnProtos(opPrefs.networkSet)

            Logger.i(
                LOG_TAG_CONNECTION,
                "Connected network: ${newActiveNetwork?.networkHandle} ${
                    networkType(newActiveNetworkCap)
                }, netid: ${netId(newActiveNetwork?.networkHandle)}, new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, test? ${opPrefs.testReachability}," +
                 "cellular? $isActiveNetworkCellular, metered? $isActiveNetworkMetered"
            )

            if (isNewNetwork || opPrefs.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(opPrefs, currentNetworks)
                informListener(true, isActiveNetworkMetered, isActiveNetworkCellular, vpnRoutes)
            }
        }

        /** Adds all the available network to the underlying network. */
        private fun processAllNetworks(opPrefs: OpPrefs) {
            val newActiveNetwork = cm.activeNetwork
            // set active network's connection status
            val isActiveNetworkMetered = isActiveConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork, opPrefs.networkSet)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)
            val vpnRoutes = determineVpnProtos(opPrefs.networkSet)
            val isActiveNetworkCellular = isActiveConnectionCellular(newActiveNetwork)

            Logger.i(LOG_TAG_CONNECTION, "process message MESSAGE_AVAILABLE_NETWORK, currNws: $currentNetworks ; new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, test? ${opPrefs.testReachability}, cellular? $isActiveNetworkCellular, metered? $isActiveNetworkMetered")

            Logger.i(LOG_TAG_CONNECTION, "process message MESSAGE_AVAILABLE_NETWORK, newNws: $newNetworks \n ; new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, test? ${opPrefs.testReachability}, cellular? $isActiveNetworkCellular, metered? $isActiveNetworkMetered")

            if (isNewNetwork || opPrefs.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(opPrefs, currentNetworks)
                informListener(false, isActiveNetworkMetered, isActiveNetworkCellular, vpnRoutes)
            }
        }

        /**
         * Determines the IP protocols (IPv4 and/or IPv6) supported by the VPN network.
         *
         * This function checks the routes of the VPN network to determine if it has
         * default routes for IPv4 and IPv6. This depends on the routes configured
         * in the builder, when exclude private networks is set to true then routes
         * will not have default route
         *
         * @param nws A set of [Network] objects, which may include the VPN network.
         * @return A [Pair] where the first element indicates IPv4 support (true if supported)
         *         and the second element indicates IPv6 support (true if supported).
         *         Returns null if no VPN network is found in the provided set.
         */
        private fun determineVpnProtos(nws: Set<Network?>): Pair<Boolean, Boolean>? {
            var vpnNw = nws.firstOrNull { isVPN(it) == true }
            if (vpnNw == null) {
                // fallback to the active network if the vpn network is not found
                val allNws = cm.allNetworks
                vpnNw = allNws.firstOrNull { isVPN(it) == true }
            }
            if (vpnNw == null) {
                // vpn routes is just the suggestion to mitigate the discrepancy between
                // actual vpn routes and the ones handled by BraveVpnService, in that case
                // if the vpn routes are not available, set it to null and return let the
                // obj(builderRoutes) in BraveVpnService to handle the rest
                Logger.i(LOG_TAG_CONNECTION, "determineVpnProtos; no vpn networks found")
                return null
            }

            // fixme: using below code has issues when private networks are excluded, return null for now
            // come up with a better way to determine the protocols
            /*val lp = cm.getLinkProperties(vpnNw)
            var has4 = false
            var has6 = false

            lp?.routes?.forEach { route ->
                val dst = route.destination
                val addr = dst.address
                val prefix = dst.prefixLength

                when (addr) {
                    is Inet4Address -> {
                        val octet = addr.address.map { it.toInt() and 0xFF }

                        val isPrivate =
                            (octet[0] == 10) ||
                                    (octet[0] == 172 && octet[1] in 16..31) ||
                                    (octet[0] == 192 && octet[1] == 168)

                        val isLoopback = (octet[0] == 127)
                        val isLinkLocal = (octet[0] == 169 && octet[1] == 254)
                        val isMulticast = (octet[0] in 224..239)
                        val isSelf = (prefix == 32)

                        if (!isPrivate && !isLoopback && !isLinkLocal && !isMulticast && !isSelf) {
                            has4 = true
                            Logger.vv(
                                LOG_TAG_CONNECTION,
                                "determineVpnProtos2; adding IPv4 route: $route"
                            )
                        }
                    }

                    is Inet6Address -> {
                        val bytes = addr.address
                        val firstByte = bytes[0].toInt() and 0xFF
                        val secondByte = bytes[1].toInt() and 0xFF

                        val isULA = (firstByte and 0xFE) == 0xFC          // fd00::/8
                        val isLinkLocal =
                            (firstByte == 0xFE) && ((secondByte and 0xC0) == 0x80)  // fe80::/10
                        val isSelf = (prefix == 128)

                        if (!isULA && !isLinkLocal && !isSelf) {
                            has6 = true
                            Logger.vv(
                                LOG_TAG_CONNECTION,
                                "determineVpnProtos2; adding IPv6 route: $route"
                            )
                        }
                    }
                }

                Logger.vv(
                    LOG_TAG_CONNECTION,
                    "determineVpnProtos2; for $route, has4? $has4, has6? $has6"
                )

                if (has4 && has6) return@forEach
            }

            if (!has4 && !has6) {
                lp?.routes?.forEach rloop@{
                    // ref: androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/net/RouteInfo.java#328
                    val hasDefaultRoute4 =
                        (it.isDefaultRoute && it.destination.address is Inet4Address)
                    val hasDefaultRoute6 =
                        (it.isDefaultRoute && it.destination.address is Inet6Address)

                    has4 = hasDefaultRoute4
                    has4 = hasDefaultRoute6

                    Logger.v(
                        LOG_TAG_CONNECTION,
                        "determineVpnProtos; for $it, has4? $has4, has6? $has6, ${it.destination}, ${it.gateway}, ${it.isDefaultRoute}, ${it.`interface`}"
                    )
                    if (has4 && has6) return@rloop
                }
            }

            Logger.i(LOG_TAG_CONNECTION, "determineVpnProtos; has4? $has4, has6? $has6, $lp")
            */
            return null //Pair(has4, has6)
        }

        /**
         * Informs the listener about network changes.
         *
         * This function constructs an [UnderlyingNetworks] object based on the current state of
         * tracked IPv4 and IPv6 networks and then calls either `onNetworkConnected` or
         * `onNetworkDisconnected` on the `listener` depending on whether any networks are available.
         *
         * It also gathers DNS server information from the tracked networks and includes it in the
         * [UnderlyingNetworks] object.
         *
         * @param useActiveNetwork A boolean indicating whether the active network should be
         *                         prioritized or if all available networks should be considered.
         * @param isActiveNetworkMetered A boolean indicating if the currently active network is metered.
         * @param isActiveNetworkCellular A boolean indicating if the currently active network is cellular.
         * @param vpnRoutes A [Pair] indicating whether VPN routes for IPv4 (first element) and
         *                  IPv6 (second element) are available. Can be null if VPN routes are not
         *                  determined.
         */
        private fun informListener(
            useActiveNetwork: Boolean = false,
            isActiveNetworkMetered: Boolean,
            isActiveNetworkCellular: Boolean,
            vpnRoutes: Pair<Boolean, Boolean>?
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
                trackedIpv4Networks.forEach {
                    Logger.d(LOG_TAG_CONNECTION, "inform4: ${it.network}, ${it.networkType}, $sz")
                }
                trackedIpv6Networks.forEach {
                    Logger.d(LOG_TAG_CONNECTION, "inform6: ${it.network}, ${it.networkType}, $sz")
                }
                val underlyingNetworks =
                    UnderlyingNetworks(
                        trackedIpv4Networks.map { it }, // map to produce shallow copy
                        trackedIpv6Networks.map { it },
                        vpnRoutes,
                        useActiveNetwork,
                        determineMtu(useActiveNetwork),
                        isActiveNetworkMetered,
                        isActiveNetworkCellular,
                        SystemClock.elapsedRealtimeNanos(),
                        Collections.unmodifiableMap(dnsServers)
                    )
                listener.onNetworkConnected(underlyingNetworks)
            } else {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        emptyList(),
                        emptyList(),
                        vpnRoutes,
                        useActiveNetwork,
                        DEFAULT_MTU,
                        isActiveNetworkMetered = false,
                        isActiveNetworkCellular = false,
                        SystemClock.elapsedRealtimeNanos(),
                        LinkedHashMap()
                    )
                listener.onNetworkDisconnected(underlyingNetworks)
            }
        }

        /**
         * Retrieves a map of DNS servers and their associated networks from a set of
         * [NetworkProperties].
         *
         * This function iterates through the provided `nws` (set of [NetworkProperties]).
         * For each `NetworkProperties` object, it accesses its `linkProperties` and then
         * its `dnsServers`. Each DNS server found is added as a key to the returned
         * `LinkedHashMap`, with the corresponding `Network` object from the
         * `NetworkProperties` as its value.
         *
         * Using a `LinkedHashMap` ensures that the order of DNS servers is preserved
         * based on their insertion order, which might be relevant for prioritization.
         * Duplicate DNS server addresses will be overwritten by later occurrences if they
         * are associated with a different network, or simply ignored if the network is the same.
         *
         * @param nws A [LinkedHashSet] of [NetworkProperties] objects, each potentially
         *            containing DNS server information.
         * @return A [LinkedHashMap] where keys are [InetAddress] objects representing
         *         DNS servers, and values are the [Network] objects they are associated with.
         *         Returns an empty map if no DNS servers are found or if `nws` is empty.
         */
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

        /**
         * Determines the MTU (Maximum Transmission Unit) for the VPN interface.
         *
         * The MTU is determined based on the following logic:
         * 1. If the Android version is below Q (API level 29), it returns `MIN_MTU` for safety
         * 2. If `useActiveNetwork` is true:
         *    a. It attempts to get the MTU from the active network.
         *    b. If the active network is null or its MTU is invalid, it falls back to the
         *       MTU of the first tracked IPv4 and IPv6 networks.
         * 3. If `useActiveNetwork` is false:
         *    a. It iterates through all tracked IPv4 and IPv6 networks and determines the
         *       minimum non-zero MTU for each protocol.
         * 4. If both IPv4 and IPv6 MTUs are invalid (less than or equal to 0), it returns `MIN_MTU`.
         * 5. Otherwise, it returns the maximum of `MIN_MTU` and the minimum of the valid
         *    IPv4 and IPv6 MTUs. This ensures the MTU is never below `MIN_MTU`.
         *
         * @param useActiveNetwork A boolean indicating whether to consider only the active network
         *                         for MTU determination.
         * @return The calculated MTU value.
         */
        private fun determineMtu(useActiveNetwork: Boolean): Int {
            var minMtu4: Int = -1
            var minMtu6: Int = -1
            if (!isAtleastQ()) {
                // If not at least Q, return MIN_MTU for safety
                return MIN_MTU
            }
            if (useActiveNetwork) {
                cm.activeNetwork?.let {
                    val lp = cm.getLinkProperties(it)
                    minMtu4 = minNonZeroMtu(lp?.mtu, minMtu4)
                    minMtu6 = minNonZeroMtu(lp?.mtu, minMtu6)
                    Logger.v(LOG_TAG_CONNECTION, "active network mtu: ${lp?.mtu}, minMtu4: $minMtu4, minMtu6: $minMtu6")
                }
                    ?: run {
                        // consider first network in underlying network as active network,
                        //  in case active network is null
                        val lp4 = trackedIpv4Networks.firstOrNull()?.linkProperties
                        val lp6 = trackedIpv6Networks.firstOrNull()?.linkProperties
                        Logger.v(LOG_TAG_CONNECTION, "tracked network mtu: ${lp4?.mtu}, ${lp6?.mtu}")
                        minMtu4 = minNonZeroMtu(lp4?.mtu, minMtu4)
                        minMtu6 = minNonZeroMtu(lp6?.mtu, minMtu6)
                    }
            } else {
                // parse through all the networks and get the minimum mtu
                trackedIpv4Networks.forEach {
                    val c = it.linkProperties
                    minMtu4 = minNonZeroMtu(c?.mtu, minMtu4)
                    Logger.v(LOG_TAG_CONNECTION, "tracked network4 mtu: ${c?.mtu}, using $minMtu4")
                }
                trackedIpv6Networks.forEach {
                    val c = it.linkProperties
                    minMtu6 = minNonZeroMtu(c?.mtu, minMtu6)
                    Logger.v(LOG_TAG_CONNECTION, "tracked network6 mtu: ${c?.mtu}, using $minMtu6")
                }
            }
            // If both are -1, return MIN_MTU explicitly
            if (minMtu4 <= 0 && minMtu6 <= 0) {
                Logger.i(LOG_TAG_CONNECTION, "Both MTUs are invalid, using MIN_MTU: $MIN_MTU")
                return MIN_MTU
            }
            // set mtu to MIN_MTU (1280) if mtu4/mtu6 are less than MIN_MTU
            val mtu = max(min(minMtu4.takeIf { it > 0 } ?: Int.MAX_VALUE, minMtu6.takeIf { it > 0 } ?: Int.MAX_VALUE), MIN_MTU)
            Logger.i(LOG_TAG_CONNECTION, "mtu4: $minMtu4, mtu6: $minMtu6; final mtu: $mtu")
            return mtu
        }

        /**
         * Returns the minimum non-zero MTU (Maximum Transmission Unit) between two given values.
         *
         * This function is used to determine the smallest valid MTU when comparing two potential
         * MTU values. An MTU can be null if the LinkProperties object is null, or it can be 0 if
         * the value is not set (see LinkProperties#getMtu()). This function handles these cases
         * by preferring a non-null, positive MTU.
         *
         * @param m1 The first MTU value. Can be null or 0.
         * @param m2 The second MTU value.
         * @return The minimum of the two MTUs, considering only positive values. If `m1` is valid
         *         (not null and > 0) and `m2` is not positive, `m1` is returned. If `m1` is not
         *         valid, `m2` is returned. Otherwise, the minimum of `m1` and `m2` is returned.
         */
        private fun minNonZeroMtu(m1: Int?, m2: Int): Int {
            return if (m1 != null && m1 > 0) {
                // mtu can be null when lp is null
                // mtu can be 0 when the value is not set, see:LinkProperties#getMtu()
                if (m2 <= 0) m1 else min(m1, m2)
            } else {
                m2
            }
        }

        /**
         * Checks if the active network connection is metered.
         * A metered connection is one for which the user may be charged per unit of data consumed.
         *
         * @return True if the active network connection is metered, false otherwise.
         */
        private fun isActiveConnectionMetered(): Boolean {
            return cm.isActiveNetworkMetered
        }

        /**
         * Checks if the active connection is cellular.
         *
         * @param network The network to check.
         * @return True if the active connection is cellular, false otherwise.
         */
        private fun isActiveConnectionCellular(network: Network?): Boolean {
            if (network == null) {
                Logger.d(LOG_TAG_CONNECTION, "isActiveConnectionCellular: network is null")
                return false
            }

            val networkCapabilities = cm.getNetworkCapabilities(network)
            return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }

        /**
         * Repopulates the tracked IPv4 and IPv6 networks based on the provided set of networks
         * and operation preferences.
         *
         * This function iterates through the given `networks` and determines their suitability
         * for IPv4 and IPv6 connectivity. The process involves:
         *
         * 1. **Filtering by Link Properties:** Networks without valid link properties are skipped.
         * 2. **Identifying Network State:** It determines if a network is active, a captive portal,
         *    validated, and has internet access.
         * 3. **Reachability Testing (Optional):** If `opPrefs.testReachability` is true, it probes
         *    for IPv4 and IPv6 connectivity using [probeConnectivity]. Networks that pass these
         *    tests are added to `trackedIpv4Networks` and `trackedIpv6Networks` respectively.
         *    If both IPv4 and IPv6 are reachable on a network, it moves to the next network.
         * 4. **Default Route Check (Fallback):** If reachability testing is disabled or fails to
         *    establish both IPv4 and IPv6 connectivity, it checks if the network has default
         *    routes for IPv4 and/or IPv6. This check is performed if the network has internet
         *    capability and either:
         *    - It's a captive portal and active (or active network is null).
         *    - `opPrefs.failOpenOnNoNetwork` is true.
         *    - The network is validated.
         *    Networks with default IPv4 routes are added to `trackedIpv4Networks`, and those
         *    with default IPv6 routes are added to `trackedIpv6Networks`.
         * 5. **Retry Mechanism:** If no usable IPv4 or IPv6 networks are found after the initial
         *    pass, [redoReachabilityIfNeeded] is called to schedule a retry.
         * 6. **Rearrangement:** Finally, `trackedIpv4Networks` and `trackedIpv6Networks` are
         *    rearranged using [rearrangeNetworks] to prioritize active and non-metered networks.
         *
         * @param opPrefs The operation preferences, which include settings like whether to test
         */
        private fun repopulateTrackedNetworks(
            opPrefs: OpPrefs,
            nwProps: LinkedHashSet<NetworkProperties>
        ) {
            val testReachability: Boolean = opPrefs.testReachability

            val activeNetwork = cm.activeNetwork // null in vpn lockdown mode

            trackedIpv4Networks.clear()
            trackedIpv6Networks.clear()

            nwProps.forEach outer@{ prop ->
                val network: Network = prop.network

                val lp = cm.getLinkProperties(network)
                if (lp == null) {
                    Logger.i(LOG_TAG_CONNECTION, "skipping: ${network.networkHandle}, netid: ${netId(network.networkHandle)}; no link properties")
                    return@outer
                }

                val isActiveNull = activeNetwork == null
                val isActive = !isActiveNull && isNetworkSame(network, activeNetwork)
                val isCaptive = isCaptivePortal(network)
                val maybeCaptiveActive = isCaptive && (isActive || isActiveNull)
                val isValidated = isValidated(network)
                val hasInternet = hasInternet(network)
                Logger.d(LOG_TAG_CONNECTION, "processing: ${network.networkHandle}, netid: ${netId(network.networkHandle)}, active? $isActive, activeNull? $isActiveNull, internet? $hasInternet, captive? $isCaptive, validated? $isValidated")

                // TODO: case: CAPTIVE_PORTAL, should we not test reachability?
                if (testReachability) {
                    // for active network, ICMP echo is additionally used with TCP and UDP checks
                    // but ICMP echo will always return reachable when app is in rinr mode
                    // so till we have checks for rinr mode, we should not use ICMP reachability

                    val has4 = probeConnectivity(listOf(url4Probe), network)
                    val has6 = probeConnectivity(listOf(url6Probe), network)
                    if (has4) trackedIpv4Networks.add(prop)
                    if (has6) trackedIpv6Networks.add(prop)
                    Logger.i(LOG_TAG_CONNECTION, "url probe, nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}): has4? $has4, has6? $has6, $prop")
                    if (has4 && has6) return@outer
                    // else: fall-through to check reachability with ips or network capabilities
                }

                val nwHas4 = trackedIpv4Networks.any { it.network == network }
                val nwHas6 = trackedIpv6Networks.any { it.network == network }
                // if either of the trackedIpv4Networks or trackedIpv6Networks has the network,
                // no need to check reachability again with ip4probes or ip6probes
                if (testReachability && (!nwHas4 && !nwHas6)) {
                    // both the ipv4 and ipv6 networks are not reachable, so try to check
                    // for ip reachability
                    val has4 = probeConnectivity(ip4probes, network)
                    val has6 = probeConnectivity(ip6probes, network)
                    if (has4) trackedIpv4Networks.add(prop)
                    if (has6) trackedIpv6Networks.add(prop)
                    Logger.i(LOG_TAG_CONNECTION, "ip probe, nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}): has4? $has4, has6? $has6, $prop")
                    if (has4 && has6) return@outer
                }

                val nwHas4AfterProbe = trackedIpv4Networks.any { it.network == network }
                val nwHas6AfterProbe = trackedIpv6Networks.any { it.network == network }
                // if either of the trackedIpv4Networks or trackedIpv6Networks has the network,
                // no need to check for below conditions
                if (nwHas4AfterProbe || nwHas6AfterProbe) {
                    Logger.i(LOG_TAG_CONNECTION, "nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}) already has ipv4? $nwHas4AfterProbe, ipv6? $nwHas6AfterProbe, skipping further checks")
                    return@outer
                }

                // treat captive portal as having internet, if client code is not going to fail-open
                val isCaptivePortal = maybeCaptiveActive && hasInternet
                val relyOnValidation = !testReachability && isValidated && hasInternet

                // see #createNetworksSet for why we are using hasInternet
                // if no network has been validated, then fail open
                // expect captive portal to have internet bound routes
                if (isCaptivePortal || relyOnValidation) {
                    var hasDefaultRoute4 = false
                    var hasDefaultRoute6 = false

                    // TODO: handle transport types like bluetooth, ethernet which may not have
                    // default routes, but can still have internet access
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
                        "nwValidation, nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}) default4? $hasDefaultRoute4, default6? $hasDefaultRoute6 for $prop"
                    )
                } else {
                    Logger.i(LOG_TAG_CONNECTION, "skip: ${network.networkHandle}, netid: ${netId(network.networkHandle)}, cap: ${prop.capabilities}; no internet capability")
                }
            }

            // handle fail-open when no networks are found in both IPv4 and IPv6 sets
            val failOpen = opPrefs.failOpenOnNoNetwork
            if (trackedIpv4Networks.isEmpty() && trackedIpv6Networks.isEmpty() && failOpen) {
                Logger.i(LOG_TAG_CONNECTION, "no networks found, but fail-open is enabled")
                nwProps.forEach outer@{ prop ->
                    val network: Network = prop.network

                    val lp = cm.getLinkProperties(network)
                    if (lp == null) {
                        Logger.i(
                            LOG_TAG_CONNECTION,
                            "skip fail-open: ${network.networkHandle}, netid: ${netId(network.networkHandle)}; no link properties"
                        )
                        return@outer
                    }

                    val hasInternet = hasInternet(network)
                    if (!hasInternet) {
                        Logger.i(
                            LOG_TAG_CONNECTION,
                            "skip fail-open: ${network.networkHandle}, netid: ${netId(network.networkHandle)}; no internet capability"
                        )
                        return@outer
                    }
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
                        "fail-open nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}) default4? $hasDefaultRoute4, default6? $hasDefaultRoute6 for $prop"
                    )
                }
            }

            redoReachabilityIfNeeded(trackedIpv4Networks, trackedIpv6Networks, opPrefs)

            trackedIpv4Networks = rearrangeNetworks(trackedIpv4Networks)
            trackedIpv6Networks = rearrangeNetworks(trackedIpv6Networks)

            Logger.d(
                LOG_TAG_CONNECTION,
                "repopulate v6: $trackedIpv6Networks,\nv4: $trackedIpv4Networks"
            )
        }

        /**
         * Retries reachability checks if no IPv4 or IPv6 networks are found.
         * This function is called when the initial reachability check fails to find any usable
         * IPv4 or IPv6 networks. It increments a counter (`reachabilityCount`) and, if the
         * counter is within the `maxReachabilityCount`, schedules another reachability check
         * with a delay. The delay increases with each retry. If a usable network is found in
         * a subsequent check, the `reachabilityCount` is reset.
         *
         * @param ipv4 A set of IPv4 network properties. If empty, it indicates no usable IPv4
         *             networks were found.
         * @param ipv6 A set of IPv6 network properties. If empty, it indicates no usable IPv6
         *             networks were found.
         * @param opPrefs The operation preferences containing the message type and other settings
         *                for the reachability check. It's assumed to be immutable.
         */
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

        /**
         * Check if there is any difference between the current and new networks.
         * The difference is determined by comparing the size of the sets and the symmetric
         * difference of the network handles.
         * @param currentNetworks The set of current networks.
         * @param newNetworks The set of new networks.
         * @return True if there is a difference, false otherwise.
         */
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

        /**
         * Rearranges the given set of networks.
         * The active network is added first, followed by non-metered networks, and then metered networks.
         *
         * @param networks The set of networks to rearrange.
         * @return A new set of networks with the rearranged order.
         */
        private fun rearrangeNetworks(
            networks: LinkedHashSet<NetworkProperties>
        ): LinkedHashSet<NetworkProperties> {
            val newNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()
            // add active network first, then add non metered networks, then metered networks
            val activeNetwork = cm.activeNetwork
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

        /**
         * Rearranges the given set of networks based on their properties.
         * The active network is placed first, followed by non-metered networks,
         * and finally metered networks.
         *
         * @param nws The set of networks to rearrange.
         * @return A new set of networks with the rearranged order.
         */
        private fun rearrangeNetworks(nws: Set<Network>): Set<Network> {
            val newNetworks: LinkedHashSet<Network> = linkedSetOf()
            val activeNetwork = cm.activeNetwork
            val n = nws.firstOrNull { isNetworkSame(it, activeNetwork) }
            if (n != null) {
                newNetworks.add(n)
            }
            nws
                .filter { isConnectionNotMetered(cm.getNetworkCapabilities(it)) }
                .forEach { newNetworks.add(it) }
            nws
                .filter { !isConnectionNotMetered(cm.getNetworkCapabilities(it)) }
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
                val activeCap = cm.getNetworkCapabilities(activeNetwork)
                val activeLp = cm.getLinkProperties(activeNetwork)
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
                    cm.allNetworks
                } else {
                    Logger.d(LOG_TAG_CONNECTION, "networkSet size: ${networkSet.size}")
                    networkSet.toTypedArray()
                }

            networks.forEach {
                val cap = cm.getNetworkCapabilities(it)
                val lp = cm.getLinkProperties(it)
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

        /**
         * Probes connectivity to a list of IP addresses on a given network.
         *
         * This function iterates through the provided IP addresses and attempts to establish
         * connectivity using [VpnController.performConnectivityCheck].
         *
         * If `useActive` is true, it first attempts to probe using the active network (socket
         * will be protected but not bound). If that fails or `useActive` is false, it proceeds
         * to probe using the specified `nw` (socket will be bound to this network).
         *
         * If both attempts fail and `USE_KOTLIN_REACHABILITY_CHECKS` is true, it falls back to
         * [isReachableTcpUdp] for an additional check.
         *
         * The function returns true as soon as any probe is successful.
         *
         * @param probes A collection of IP addresses (as strings) to probe. Empty strings are skipped.
         * @param nw The [Network] to use for probing. If null and `useActive` is false,
         *           the probe controller will protect the socket to the default network.
         * @return True if connectivity is established to any of the probed IPs, false otherwise.
         */
        private fun probeConnectivity(
            probes: Collection<String>,
            nw: Network?
        ): Boolean = runBlocking {
            var ok = false
            val probeController = if (nw != null) {
                ProbeController(nw.networkHandle, nw)
            } else {
                // in case of use active network, the network is null, so the controller will
                // protect the socket to the default network instead of bind
                // assign the networkHandle as 0 and nw as null, so that the controller will not
                // bind the socket
                ProbeController(-1, null)
            }
            probes.forEach { ipOrUrl ->
                if (ipOrUrl.isEmpty()) return@forEach // skip empty IPs

                val id = "probe:${netId(nw?.networkHandle)}:$ipOrUrl"
                ok = VpnController.performConnectivityCheck(probeController, id, ipOrUrl)

                if (!ok && USE_KOTLIN_REACHABILITY_CHECKS) {
                    ok = isReachableTcpUdp(nw, ipOrUrl)
                }
                Logger.v(LOG_TAG_CONNECTION, "probeConnectivity, id($id): $ipOrUrl, ok? $ok, nw: ${nw?.networkHandle}, netid: ${netId(nw?.networkHandle)}")
                if (ok) {
                    return@forEach // break
                }
            }
            return@runBlocking ok
        }

        /**
         * Probes the given IP address for connectivity on the available networks.
         *
         * This function attempts to establish a connection to the specified IP address
         * using the provided set of networks. It prioritizes networks based on their
         * type (active, non-metered, metered).
         *
         * If initial probes fail and `USE_KOTLIN_REACHABILITY_CHECKS` is enabled,
         * it will fall back to ICMP or TCP/UDP reachability checks.
         *
         * @param ip The IP address to probe.
         * @param nws The set of available networks to use for probing.
         * @return A [ProbeResult] object containing the IP address, a boolean indicating
         *         if the probe was successful, and the [NetworkCapabilities] of the
         *         network on which the successful probe occurred (or null if unsuccessful).
         */
        suspend fun probeIpOrUrl(ip: String, nws: Set<Network>): ProbeResult {
            val newNws = rearrangeNetworks(nws)
            if (newNws.isEmpty()) {
                var ok = probeConnectivity(listOf(ip), nw = null)
                if (!ok && USE_KOTLIN_REACHABILITY_CHECKS) {
                    // if no networks are available, try to reach the ip using ICMP
                    // or TCP/UDP checks
                    ok = isReachable(ip)
                }
                val cap = cm.getNetworkCapabilities(cm.activeNetwork)
                Logger.i(LOG_TAG_CONNECTION, "probeIpOrUrl: $ip, ok? $ok, cap: $cap")
                return ProbeResult(ip, ok, cap)
            }
            newNws.forEach { nw ->
                val ok = probeConnectivity(listOf(ip), nw)
                val cap = cm.getNetworkCapabilities(nw)
                if (!ok && USE_KOTLIN_REACHABILITY_CHECKS) {
                    // if the probe fails, try to reach the ip using ICMP or TCP/UDP checks
                    val reachable = isReachableTcpUdp(nw, ip)
                    if (reachable) {
                        return ProbeResult(ip, true, cap)
                    }
                }
                if (ok) {
                    Logger.i(LOG_TAG_CONNECTION, "probeIpOrUrl: $ip, ok? $ok, netid: ${netId(nw.networkHandle)}, cap: $cap")
                    return ProbeResult(ip, true, cap)
                }
            }
            return ProbeResult(ip, false, null)
        }

        /**
         * Checks if the host is reachable via TCP/UDP on the given network.
         * It tries to connect to the host on port 80 (HTTP) and port 53 (DNS) via TCP.
         * If either of these connections is successful, the host is considered reachable.
         * The socket is bound to the given network before attempting to connect.
         * @param nw The network to use for the reachability check.
         * @param host The host to check for reachability.
         * @return True if the host is reachable, false otherwise.
         */
        private suspend fun isReachableTcpUdp(nw: Network?, host: String): Boolean {
            try {
                // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
                TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())

                val yes = tcp80(nw, host) || tcp53(nw, host)

                Logger.d(LOG_TAG_CONNECTION, "$host isReachable on network ${nw?.networkHandle}, netid: ${netId(nw?.networkHandle)},($nw): $yes")
                return yes
            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "err isReachable: ${e.message}")
            }
            return false
        }

        /**
         * Checks if the host is reachable using ICMP echo request.
         *
         * The 'isReachable()' function sends an ICMP echo request to the target host. In the
         * event of the 'Rethink within Rethink' option being used, ICMP checks will fail.
         * Ideally, there is no need to perform ICMP checks. However, if 'Rethink within
         * Rethink' is not supplied to this handler, these checks will be carried out but will
         * result in failure.
         *
         * @param host The hostname or IP address to check.
         * @return True if the host is reachable, false otherwise.
         */
        private suspend fun isReachable(host: String): Boolean {
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

        /**
         * Checks if the host is reachable via TCP on port 80.
         *
         * The check involves creating a socket, binding it to the provided network (if any),
         * protecting the socket using VpnController, and then attempting to connect to the
         * host on port 80 with a timeout.
         *
         * android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-benchmark-release/java/net/Inet6AddressImpl.java#217
         *
         * @param nw The network to use for the connection. If null, the default network is used.
         * @param host The hostname or IP address to connect to.
         * @return True if the connection is successful or if the connection is refused (ECONNREFUSED),
         *         false otherwise.
         */

        private suspend fun tcp80(nw: Network?, host: String): Boolean {
            val timeout = 500 // ms
            val port80 = 80 // port
            var socket: Socket? = null
            try {
                // port 7 is echo port, blocked by most firewalls. use port 80 instead
                val s = InetSocketAddress(host, port80)
                socket = Socket()
                nw?.bindSocket(socket)
                VpnController.protectSocket(socket)
                socket.connect(s, timeout)
                val c = socket.isConnected
                val b = socket.isBound
                Logger.d(LOG_TAG_CONNECTION, "tcpEcho80: $host, ${nw?.networkHandle}, netid: ${netId(nw?.networkHandle)}, $c, $b, $nw")

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

        /**
         * Check if the host is reachable on port 53 (DNS) using TCP.
         * Tries to establish a TCP connection to the host on port 53. If the connection is
         * successful, the host is considered reachable. If the connection is refused (ECONNREFUSED),
         * it's also considered reachable as it implies a service is listening but refused the
         * specific connection. Other IOExceptions or errors indicate unreachability.
         *
         * @param nw The network to use for the connection. If null, the default network is used.
         * @param host The hostname or IP address of the host to check.
         * @return True if the host is reachable on port 53, false otherwise.
         */
        private suspend fun tcp53(nw: Network?, host: String): Boolean {
            val timeout = 500 // ms
            val port53 = 53 // port
            var socket: Socket? = null

            try {
                socket = Socket()
                val s = InetSocketAddress(host, port53)
                nw?.bindSocket(socket)
                VpnController.protectSocket(socket)
                socket.connect(s, timeout)
                val c = socket.isConnected
                val b = socket.isBound
                Logger.d(LOG_TAG_CONNECTION, "tcpEcho53: $host, ${nw?.networkHandle}, netid: ${netId(nw?.networkHandle)}: $c, $b, $nw")
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

        /**
         * Closes the given [Closeable] and ignores any [IOException] that may occur.
         *
         * @param socket The [Closeable] to close.
         */
        private fun clos(socket: Closeable?) {
            try {
                socket?.use {
                    it.close()
                }
            } catch (ignored: IOException) {
            }
        }

        /**
         * Checks if the given network has internet capability.
         *
         * @param network The network to check.
         * @return True if the network has internet capability, false otherwise.
         */
        private fun hasInternet(network: Network?): Boolean {
            // TODO: consider checking for NET_CAPABILITY_NOT_SUSPENDED, NET_CAPABILITY_VALIDATED?
            if (network == null) return false

            return cm
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

        /**
         * Checks if the network is a captive portal.
         * A captive portal is a web page that the user of a public-access network is
         * obliged to view and interact with before access is granted.
         *
         * @param network The network to check.
         * @return True if the network is a captive portal, false otherwise.
         */
        private fun isCaptivePortal(network: Network): Boolean {
            return cm
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        }

        /**
         * Checks if the network is validated.
         * A validated network is a network that has been tested by the system to have functional
         * Internet connectivity.
         * @param network The network to check.
         * @return True if the network is validated, false otherwise.
         */
        private fun isValidated(network: Network): Boolean {
            return cm
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }

        /**
         * Checks if the given network is a VPN network.
         *
         * @param network The network to check.
         * @return True if the network is a VPN, false if it's not, or null if the network is null
         * or its capabilities cannot be determined.
         */
        private fun isVPN(network: Network?): Boolean? {
            if (network == null) return null
            return cm
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
