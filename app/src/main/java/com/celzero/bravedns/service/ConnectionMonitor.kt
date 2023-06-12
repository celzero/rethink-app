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
import android.system.OsConstants.RT_SCOPE_UNIVERSE
import android.util.Log
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_CONNECTION
import com.google.common.collect.Sets
import inet.ipaddr.IPAddressString
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ConnectionMonitor(context: Context, networkListener: NetworkListener) :
    ConnectivityManager.NetworkCallback(), KoinComponent {

    private val androidValidatedNetworks = false

    private val networkRequest: NetworkRequest =
        if (androidValidatedNetworks)
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        else
        // add cellular, wifi, bluetooth, ethernet, vpn, wifi aware, low pan
        NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                // api27: .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                // api26: .addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN)
                .build()

    // An Android handler thread internally operates on a looper.
    // Defined as @Volatile so that the looper in the Handler thread so that
    // the value loaded from memory instead of Thread's local cache.
    // ref -
    // https://alvinalexander.com/java/jwarehouse/android/core/java/android/app/IntentService.java.shtml
    @Volatile private var handlerThread: HandlerThread
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
    }

    // data class that holds the below information for handlers to process the network changes
    data class OpPrefs(val isForceUpdate: Boolean, val isAuto: Boolean)

    init {
        try {
            connectivityManager.registerNetworkCallback(networkRequest, this)
        } catch (e: Exception) {
            Log.w(LOG_TAG_CONNECTION, "Exception while registering network callback", e)
        }
        this.handlerThread = HandlerThread(NetworkRequestHandler::class.simpleName)
        this.handlerThread.start()
        this.serviceHandler = NetworkRequestHandler(context, handlerThread.looper, networkListener)
    }

    interface NetworkListener {
        fun onNetworkDisconnected()
        fun onNetworkConnected(networks: UnderlyingNetworks)
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
        handleNetworkChange()
    }

    override fun onLost(network: Network) {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onLost, ${network.networkHandle}, $network")
        handleNetworkChange()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if (DEBUG)
            Log.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged, ${network.networkHandle}, $network")
        handleNetworkChange()
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if (DEBUG)
            Log.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged: ${network.networkHandle}, $network")
        handleNetworkChange(isForceUpdate = true)
    }

    /**
     * Handles user preference changes, ie, when the user elects to see either multiple underlying
     * networks, or just one (the active network).
     */
    fun onUserPreferenceChanged() {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onUserPreferenceChanged")
        handleNetworkChange(isForceUpdate = true)
    }

    /**
     * Force updates the VPN's underlying network based on the preference. Will be initiated when
     * the VPN start is completed.
     */
    fun onVpnStart() {
        Log.i(LOG_TAG_CONNECTION, "new vpn is created force update the network")
        handleNetworkChange(isForceUpdate = true)
    }

    private fun handleNetworkChange(isForceUpdate: Boolean = false) {
        val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
        val msg =
            constructNetworkMessage(
                if (persistentState.useMultipleNetworks) MSG_ADD_ALL_NETWORKS
                else MSG_ADD_ACTIVE_NETWORK,
                isForceUpdate,
                isAuto
            )
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        // process after a delay to avoid processing multiple network changes in short bursts
        serviceHandler?.sendMessageDelayed(msg, TimeUnit.SECONDS.toMillis(3))
    }

    /**
     * Constructs the message object for Network handler. Add the active network to the message
     * object in case of setUnderlying network has only active networks.
     */
    private fun constructNetworkMessage(
        what: Int,
        isForceUpdate: Boolean,
        isAuto: Boolean
    ): Message {
        val opPrefs = OpPrefs(isForceUpdate, isAuto)
        val message = Message.obtain()
        message.what = what
        message.obj = opPrefs
        return message
    }

    data class NetworkProperties(val network: Network, val capabilities: NetworkCapabilities)

    data class UnderlyingNetworks(
        val allNet: List<NetworkProperties>?,
        val ipv4Net: List<NetworkProperties>,
        val ipv6Net: List<NetworkProperties>,
        val useActive: Boolean,
        var isActiveNetworkMetered: Boolean,
        var lastUpdated: Long
    )

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(
        ctx: Context,
        looper: Looper,
        val listener: NetworkListener
    ) : Handler(looper) {
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
            val isActiveNetworkMetered = isConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Log.i(
                LOG_TAG_CONNECTION,
                "Connected network: ${newActiveNetwork?.networkHandle} ${
                                when {
                                    newActiveNetworkCap?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                                    newActiveNetworkCap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                                    newActiveNetworkCap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                                    else -> "Unknown"
                                }
                            }, new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, auto? ${opPrefs.isAuto}"
            )

            if (isNewNetwork || opPrefs.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(opPrefs.isAuto, currentNetworks)
                informListener(true, isActiveNetworkMetered)
            }
        }

        /** Adds all the available network to the underlying network. */
        private fun processAllNetworks(opPrefs: OpPrefs) {
            val newActiveNetwork = connectivityManager.activeNetwork
            // set active network's connection status
            val isActiveNetworkMetered = isConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork, true)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Log.i(
                LOG_TAG_CONNECTION,
                "process message MESSAGE_AVAILABLE_NETWORK, ${currentNetworks}, ${newNetworks}; new? $isNewNetwork, force? ${opPrefs.isForceUpdate}, auto? ${opPrefs.isAuto}"
            )

            if (isNewNetwork || opPrefs.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(opPrefs.isAuto, currentNetworks)
                informListener(false, isActiveNetworkMetered)
            }
        }

        private fun informListener(
            useActiveNetwork: Boolean = false,
            isActiveNetworkMetered: Boolean
        ) {
            val sz = currentNetworks.size
            Log.i(LOG_TAG_CONNECTION, "inform network change: ${sz}, all? $useActiveNetwork")
            if (sz > 0) {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        currentNetworks.map { it }, // map to produce shallow copy
                        trackedIpv4Networks.map { it },
                        trackedIpv6Networks.map { it },
                        useActiveNetwork,
                        isActiveNetworkMetered,
                        SystemClock.elapsedRealtime()
                    )
                listener.onNetworkConnected(underlyingNetworks)
            } else {
                listener.onNetworkDisconnected()
            }
        }

        private fun isConnectionMetered(): Boolean {
            return connectivityManager.isActiveNetworkMetered

            // below code will return if the connection is of type CELLULAR or not
            /*
            return connectivityManager.getNetworkCapabilities(
                connectivityManager.activeNetwork)?.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR) == true */
        }

        private fun repopulateTrackedNetworks(
            isAuto: Boolean,
            networks: LinkedHashSet<NetworkProperties>
        ) {
            val ipv6: LinkedHashSet<NetworkProperties> = linkedSetOf()
            val ipv4: LinkedHashSet<NetworkProperties> = linkedSetOf()

            val activeNetwork = connectivityManager.activeNetwork // null in vpn lockdown mode
            val activeNetworkProp: NetworkProperties? =
                activeNetwork?.let {
                    val activeLp = connectivityManager.getNetworkCapabilities(activeNetwork)
                    if (activeLp != null) {
                        NetworkProperties(it, activeLp)
                    } else {
                        null
                    }
                }
            // check if active network (without binding to any interface) is reachable
            if (isAuto) {
                val active6 = isIPv6Reachable(null)
                if (active6 && activeNetworkProp != null) ipv6.add(activeNetworkProp)
                val active4 = isIPv4Reachable(null)
                if (active4 && activeNetworkProp != null) ipv4.add(activeNetworkProp)
                Log.i(LOG_TAG_CONNECTION, "active-network: $activeNetwork; 4? $ 6? $active6")
            }
            networks.forEach { prop ->
                val network = prop.network
                // skip active network (in auto mode) as we have already checked it
                if (isAuto && network == activeNetwork) return@forEach

                val linkProperties =
                    connectivityManager.getLinkProperties(network) ?: return@forEach

                linkProperties.linkAddresses.forEach inner@{ addr ->
                    // skip if the address scope is not RT_SCOPE_UNIVERSE, as there are some
                    // addresses which are not reachable outside the network but we will end up
                    // adding those address for ipv4/ipv6 mode, reachability check will fail in
                    // auto mode.
                    if (!isAuto && addr.scope != RT_SCOPE_UNIVERSE) {
                        Log.i(
                            LOG_TAG_CONNECTION,
                            "skipping address: ${addr.address.hostAddress} with scope: ${addr.scope}")
                        return@inner
                    }

                    val address = IPAddressString(addr.address.hostAddress?.toString())

                    if (address.isIPv6) {
                        var y = !isAuto
                        if (!isAuto) {
                            ipv6.add(prop)
                        } else if (isIPv6Reachable(network)) {
                            // in auto mode, do network validation ourselves
                            ipv6.add(prop)
                            y = true
                        }
                        Log.i(
                            LOG_TAG_CONNECTION,
                            "adding ipv6 network: $network; works? $y for $address"
                        )
                    } else {
                        var y = !isAuto
                        if (!isAuto) {
                            ipv4.add(prop)
                        } else if (isIPv4Reachable(network)) {
                            // in auto mode, do network validation ourselves
                            ipv4.add(prop)
                            y = true
                        }
                        Log.i(
                            LOG_TAG_CONNECTION,
                            "adding ipv4 network: $network; works? $y for $address"
                        )
                    }
                }
            }

            trackedIpv6Networks = ipv6
            trackedIpv4Networks = ipv4
            Log.d(
                LOG_TAG_CONNECTION,
                "repopulate v6: $trackedIpv6Networks, v4: $trackedIpv4Networks"
            )
        }

        private fun hasDifference(
            currentNetworks: LinkedHashSet<NetworkProperties>,
            newNetworks: LinkedHashSet<NetworkProperties>
        ): Boolean {
            val cn = currentNetworks.map { it.network }.toSet()
            val nn = newNetworks.map { it.network }.toSet()
            return Sets.symmetricDifference(cn, nn).isNotEmpty()
        }

        /**
         * Create network set(available networks) based on the user preference. The first element of
         * the list must be active network. requireAllNetwork - if true adds all networks to the set
         * else adds active network alone to the set.
         */
        private fun createNetworksSet(
            activeNetwork: Network?,
            requireAllNetworks: Boolean = false
        ): LinkedHashSet<NetworkProperties> {
            val newNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()
            activeNetwork?.let {
                val activeLp = connectivityManager.getNetworkCapabilities(activeNetwork)
                val activeProp =
                    if (activeLp != null) {
                        NetworkProperties(it, activeLp)
                    } else {
                        null
                    }
                if (hasInternet(it) == true && isVPN(it) == false) {
                    if (activeProp != null) {
                        newNetworks.add(activeProp)
                    }
                } else {
                    // no-op
                }
            }

            if (!requireAllNetworks) {
                return newNetworks
            }

            connectivityManager.allNetworks.forEach {
                val lp = connectivityManager.getNetworkCapabilities(it)
                val prop =
                    if (lp != null) {
                        NetworkProperties(it, lp)
                    } else {
                        null
                    }
                if (it.networkHandle == activeNetwork?.networkHandle) {
                    return@forEach
                }

                if (hasInternet(it) == true && isVPN(it) == false) {
                    if (prop != null) {
                        newNetworks.add(prop)
                    }
                } else {
                    // no-op
                }
            }

            return newNetworks
        }

        private fun isIPv4Reachable(nw: Network?): Boolean = runBlocking {
            coroutineScope {
                // select the first reachable IP / domain and return true if any of them is
                // reachable
                select<Boolean> {
                        ip4probes.forEach { ip -> async { isReachable(nw, ip) }.onAwait { it } }
                    }
                    .also { coroutineContext.cancelChildren() }
            }
        }

        private fun isIPv6Reachable(nw: Network?): Boolean = runBlocking {
            coroutineScope {
                select<Boolean> {
                        ip6probes.forEach { ip -> async { isReachable(nw, ip) }.onAwait { it } }
                    }
                    .also { coroutineContext.cancelChildren() }
            }
        }

        private fun isReachable(nw: Network?, host: String): Boolean {
            try {
                val onesec = 1000 // ms
                // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
                TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())
                // https://developer.android.com/reference/java/net/InetAddress#isReachable(int)
                // Network.getByName() binds the socket to that network; InetAddress.getByName.
                // isReachable(network-interface, ttl, timeout) cannot be used here since
                // "network-interface" is a java-construct while "Network" is an android-construct
                // InetAddress.getByName() will bind the socket to the default active network.
                val yes =
                    nw?.getByName(host)?.isReachable(onesec)
                        ?: InetAddress.getByName(host).isReachable(onesec)

                if (DEBUG)
                    Log.d(
                        LOG_TAG_CONNECTION,
                        "$host isReachable on network: ${nw?.networkHandle}: $yes"
                    )
                return yes
            } catch (e: Exception) {
                Log.w(LOG_TAG_CONNECTION, "err isReachable: ${e.message}")
            }
            return false
        }

        private fun hasInternet(network: Network): Boolean? {
            // TODO: consider checking for NET_CAPABILITY_NOT_SUSPENDED, NET_CAPABILITY_VALIDATED?
            return connectivityManager
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
