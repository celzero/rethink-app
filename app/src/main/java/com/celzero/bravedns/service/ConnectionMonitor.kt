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

    private val networkRequest: NetworkRequest =
        NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()

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

        // change in linked property
        const val MSG_LINK_PROPERTY = 3
    }

    // data class that holds the below information for handlers to process the link properties
    // changes
    data class NetworkLinkProperties(
        val network: Network,
        val linkProperties: LinkProperties,
        val isAuto: Boolean
    )

    // data class that holds the below information for handlers to process the network changes
    data class HandlerObj(val isForceUpdate: Boolean, val isAuto: Boolean)

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
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onAvailable")
        handleNetworkChange()
    }

    override fun onLost(network: Network) {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onLost")
        handleNetworkChange()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged")
        handleNetworkChange()
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged")
        handleNetworkChange(isForceUpdate = true)
        handlePropertyChange(network, linkProperties)
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
        val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
        handleNetworkChange(isForceUpdate = true)
    }

    private fun handleNetworkChange(isForceUpdate: Boolean = false) {
        val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
        val message =
            constructNetworkMessage(
                if (persistentState.useMultipleNetworks) MSG_ADD_ALL_NETWORKS
                else MSG_ADD_ACTIVE_NETWORK,
                isForceUpdate,
                isAuto
            )
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        serviceHandler?.sendMessageDelayed(message, TimeUnit.SECONDS.toMillis(3))
    }

    private fun handlePropertyChange(network: Network, linkedProperties: LinkProperties) {
        // negate useMultipleNetworks so that the message obj has use active network
        val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
        val message =
            constructLinkedPropertyMessage(MSG_LINK_PROPERTY, network, linkedProperties, isAuto)
        serviceHandler?.removeMessages(MSG_LINK_PROPERTY, null)
        serviceHandler?.sendMessage(message)
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
        val handlerObj = HandlerObj(isForceUpdate, isAuto)
        val message = Message.obtain()
        message.what = what
        message.obj = handlerObj
        return message
    }

    // constructs the message object for Network handler.
    // adds the linked properties to the message
    // dns server changes are part of linked properties
    private fun constructLinkedPropertyMessage(
        what: Int,
        network: Network,
        linkedProperties: LinkProperties,
        useActive: Boolean
    ): Message {
        val networkLinkProperties = NetworkLinkProperties(network, linkedProperties, useActive)
        val message = Message.obtain()
        message.what = what
        message.obj = networkLinkProperties
        return message
    }

    data class UnderlyingNetworks(
        val allNet: Set<Network>?,
        val ipv4Net: Set<NetworkIcmpProperty>,
        val ipv6Net: Set<NetworkIcmpProperty>,
        val useActive: Boolean,
        var isActiveNetworkMetered: Boolean,
        var lastUpdated: Long
    )

    data class NetworkIcmpProperty(val network: Network, val isReachable: Boolean)

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(
        context: Context,
        looper: Looper,
        val networkListener: NetworkListener
    ) : Handler(looper) {
        // ref - https://developer.android.com/reference/kotlin/java/util/LinkedHashSet
        // The network list is maintained in a linked-hash-set to preserve insertion and iteration
        // order. This is required because {@link android.net.VpnService#setUnderlyingNetworks}
        // defines network priority depending on the iteration order, that is, the network
        // in the 0th index is preferred over the one at 1st index, and so on.
        var currentNetworks: LinkedHashSet<Network> = linkedSetOf()

        var trackedIpv4Networks: MutableSet<NetworkIcmpProperty> = mutableSetOf()
        var trackedIpv6Networks: MutableSet<NetworkIcmpProperty> = mutableSetOf()

        var connectivityManager: ConnectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        override fun handleMessage(msg: Message) {
            // isForceUpdate - true if onUserPreferenceChanged is changes, the messages should be
            // processed forcefully regardless of the current and new networks.
            val handlerObj: HandlerObj
            when (msg.what) {
                MSG_ADD_ACTIVE_NETWORK -> {
                    handlerObj = msg.obj as HandlerObj
                    processActiveNetwork(handlerObj)
                }
                MSG_ADD_ALL_NETWORKS -> {
                    handlerObj = msg.obj as HandlerObj
                    processAllNetworks(handlerObj)
                }
                MSG_LINK_PROPERTY -> {
                    val netProps = msg.obj as NetworkLinkProperties
                    processLinkPropertyChange(netProps)
                }
            }
        }

        /**
         * tracks the changes in active network. Set the underlying network if the current active
         * network is different from already assigned one unless the force update is required.
         */
        private fun processActiveNetwork(handlerObj: HandlerObj) {
            val newActiveNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(newActiveNetwork)
            // set active network's connection status
            val isActiveNetworkMetered = isConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Log.i(
                LOG_TAG_CONNECTION,
                "Connected network: ${
                                when {
                                    networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                                    networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                                    networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                                    else -> "Unknown"
                                }
                            }, Is new network? $isNewNetwork, is force update? ${handlerObj.isForceUpdate}, is auto? ${handlerObj.isAuto}"
            )

            if (isNewNetwork || handlerObj.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(handlerObj.isAuto, currentNetworks)
                informListener(requireAllNetworks = false, isActiveNetworkMetered)
            }
        }

        /** Adds all the available network to the underlying network. */
        private fun processAllNetworks(handlerObj: HandlerObj) {
            val newActiveNetwork = connectivityManager.activeNetwork
            // set active network's connection status
            val isActiveNetworkMetered = isConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork, true)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Log.i(
                LOG_TAG_CONNECTION,
                "process message MESSAGE_AVAILABLE_NETWORK, ${currentNetworks},${newNetworks}. isNewNetwork: $isNewNetwork, force update is ${handlerObj.isForceUpdate}, is auto? ${handlerObj.isAuto}"
            )

            if (isNewNetwork || handlerObj.isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(handlerObj.isAuto, currentNetworks)

                informListener(requireAllNetworks = true, isActiveNetworkMetered)
            }
        }

        private fun informListener(
            requireAllNetworks: Boolean = false,
            isActiveNetworkMetered: Boolean
        ) {
            Log.i(
                LOG_TAG_CONNECTION,
                "inform listener on network change: ${currentNetworks.size}, is all network: $requireAllNetworks"
            )
            if (currentNetworks.isNotEmpty()) {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        currentNetworks,
                        trackedIpv4Networks,
                        trackedIpv6Networks,
                        !requireAllNetworks, // use multiple network is negated for active network
                        isActiveNetworkMetered,
                        SystemClock.elapsedRealtime()
                    )
                networkListener.onNetworkConnected(underlyingNetworks)
            } else {
                networkListener.onNetworkDisconnected()
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

        private fun processLinkPropertyChange(networkLinkProperties: NetworkLinkProperties) {
            val linkProperties = networkLinkProperties.linkProperties
            val network = networkLinkProperties.network
            val isAuto = networkLinkProperties.isAuto

            // do not add network if there is no internet/is VPN
            if (hasInternet(network) == false || isVPN(network) == true) {
                return
            }

            linkProperties.linkAddresses.forEach {
                // fixme: remove RT_SCOPE_UNIVERSE check once ICMP handling is added; see: #553
                // if auto mode is enabled, do not check for RT_SCOPE_UNIVERSE as we are checking
                // reachability of the network
                if (!isAuto && it.scope != RT_SCOPE_UNIVERSE) return@forEach

                val address = IPAddressString(it.address.hostAddress?.toString())

                if (address.isIPv6) {
                    trackedIpv6Networks.add(
                        NetworkIcmpProperty(
                            network,
                            // if auto mode is enabled, check for ipv6 reachability
                            if (isAuto) checkIpv6Reachability(network) else true
                        )
                    )
                } else {
                    trackedIpv4Networks.add(
                        NetworkIcmpProperty(
                            network,
                            // if auto mode is enabled, check for ipv4 reachability
                            if (isAuto) checkIpv4Reachability(network) else true
                        )
                    )
                }
            }
        }

        private fun repopulateTrackedNetworks(
            isAuto: Boolean,
            networkIcmpProperties: LinkedHashSet<Network>
        ) {
            val ipv6: MutableSet<NetworkIcmpProperty> = mutableSetOf()
            val ipv4: MutableSet<NetworkIcmpProperty> = mutableSetOf()

            networkIcmpProperties.forEach { property ->
                val linkProperties =
                    connectivityManager.getLinkProperties(property) ?: return@forEach

                linkProperties.linkAddresses.forEach inner@{ prop ->
                    // fixme: remove RT_SCOPE_UNIVERSE check once ICMP handling is added; see: #553
                    // if auto mode is enabled, do not check for RT_SCOPE_UNIVERSE as we are
                    // checking reachability of the network
                    if (!isAuto && prop.scope != RT_SCOPE_UNIVERSE) return@inner

                    val address = IPAddressString(prop.address.hostAddress?.toString())

                    if (address.isIPv6) {
                        ipv6.add(
                            NetworkIcmpProperty(
                                property,
                                // if auto mode is enabled, check for ipv4 reachability
                                if (isAuto) checkIpv6Reachability(property) else true
                            )
                        )
                    } else {
                        ipv4.add(
                            NetworkIcmpProperty(
                                property,
                                // if auto mode is enabled, check for ipv4 reachability
                                if (isAuto) checkIpv4Reachability(property) else true
                            )
                        )
                    }
                }
            }

            trackedIpv6Networks = ipv6
            trackedIpv4Networks = ipv4
            Log.d(
                LOG_TAG_CONNECTION,
                "repopulate tracked network for IPv6: $trackedIpv6Networks, Ipv4: $trackedIpv4Networks"
            )
        }

        private fun hasDifference(
            currentNetworks: LinkedHashSet<Network>,
            newNetworks: LinkedHashSet<Network>
        ): Boolean {
            return Sets.symmetricDifference(currentNetworks, newNetworks).isNotEmpty()
        }

        /**
         * Create network set(available networks) based on the user preference. The first element of
         * the list must be active network. requireAllNetwork - if true adds all networks to the set
         * else adds active network alone to the set.
         */
        private fun createNetworksSet(
            activeNetwork: Network?,
            requireAllNetworks: Boolean = false
        ): LinkedHashSet<Network> {
            val newNetworks: LinkedHashSet<Network> = linkedSetOf()
            activeNetwork?.let {
                if (hasInternet(it) == true && isVPN(it) == false) {
                    newNetworks.add(it)
                } else {
                    // no-op
                }
            }

            if (!requireAllNetworks) {
                return newNetworks
            }

            val tempAllNetwork = connectivityManager.allNetworks

            tempAllNetwork.forEach {
                if (it.networkHandle == activeNetwork?.networkHandle) {
                    return@forEach
                }

                if (hasInternet(it) == true && isVPN(it) == false) {
                    newNetworks.add(it)
                } else {
                    // no-op
                }
            }

            return newNetworks
        }

        private fun checkIpv4Reachability(network: Network): Boolean = runBlocking {
            coroutineScope {
                // execute isReachable on series of IP addresses and return true if any of them is
                // reachable
                val ipAddresses =
                    listOf(
                        "216.239.32.27", // google org
                        "104.16.132.229", // cloudflare
                        "44.235.246.155" // mozilla
                    )
                // fixme: remove RT_SCOPE_UNIVERSE check once ICMP handling is added; see: #553
                // select the first reachable IP address and return true if any of them is reachable
                // else return false
                select<Boolean> {
                        async { isReachable(network, ipAddresses[0]) }.onAwait { it }
                        async { isReachable(network, ipAddresses[1]) }.onAwait { it }
                        async { isReachable(network, ipAddresses[2]) }.onAwait { it }
                    }
                    .also { coroutineContext.cancelChildren() }
            }
        }

        private fun checkIpv6Reachability(network: Network): Boolean = runBlocking {
            coroutineScope {
                // execute isReachable on series of IP addresses and return true if any of them is
                // reachable
                val ipAddresses =
                    listOf(
                        "2001:4860:4802:32::1b", // google org
                        "2606:4700::6810:84e5", // cloudflare
                        "2606:4700:3033::ac43:a21b" // rethinkdns
                    )
                // fixme: remove RT_SCOPE_UNIVERSE check once ICMP handling is added; see: #553
                // select the first reachable IP address and return true if any of them is reachable
                // else return false
                select<Boolean> {
                        async { isReachable(network, ipAddresses[0]) }.onAwait { it }
                        async { isReachable(network, ipAddresses[1]) }.onAwait { it }
                        async { isReachable(network, ipAddresses[2]) }.onAwait { it }
                    }
                    .also { coroutineContext.cancelChildren() }
            }
        }

        private fun isReachable(network: Network, host: String): Boolean {
            try {
                // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
                TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())
                // https://developer.android.com/reference/java/net/InetAddress#isReachable(int)
                // the getByName() call on a network will bind the socket to the network
                // in which case the isReachable() call will use the network to check reachability
                // we are using isReachable(timeout) instead of isReachable(NI, ttl, timeout)
                // because there is no way to get the network interface for a network
                val isReachable = network.getByName(host).isReachable(1000)

                if (DEBUG)
                    Log.d(
                        LOG_TAG_CONNECTION,
                        "isReachable for $host, network: ${network.networkHandle}: $isReachable"
                    )
                return isReachable
            } catch (e: Exception) {
                Log.e(LOG_TAG_CONNECTION, "caught during isReachable: ${e.message}")
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
