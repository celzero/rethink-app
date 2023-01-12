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
import android.net.*
import android.os.*
import android.system.OsConstants.RT_SCOPE_UNIVERSE
import android.util.Log
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_CONNECTION
import com.google.common.collect.Sets
import inet.ipaddr.IPAddressString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

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

    data class NetworkProperties(val network: Network, val linkProperties: LinkProperties)

    init {
        connectivityManager.registerNetworkCallback(networkRequest, this)
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
        handleNetworkChange(isForceUpdate = true)
    }

    private fun handleNetworkChange(isForceUpdate: Boolean = false) {
        val message =
            constructNetworkMessage(
                if (persistentState.useMultipleNetworks) MSG_ADD_ALL_NETWORKS
                else MSG_ADD_ACTIVE_NETWORK,
                isForceUpdate
            )
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        serviceHandler?.sendMessageDelayed(message, TimeUnit.SECONDS.toMillis(3))
    }

    private fun handlePropertyChange(network: Network, linkedProperties: LinkProperties) {
        val message = constructLinkedPropertyMessage(MSG_LINK_PROPERTY, network, linkedProperties)
        serviceHandler?.removeMessages(MSG_LINK_PROPERTY, null)
        serviceHandler?.sendMessage(message)
    }

    /**
     * Constructs the message object for Network handler. Add the active network to the message
     * object in case of setUnderlying network has only active networks.
     */
    private fun constructNetworkMessage(what: Int, isForceUpdate: Boolean): Message {
        val message = Message.obtain()
        message.what = what
        message.obj = isForceUpdate
        return message
    }

    // constructs the message object for Network handler.
    // adds the linked properties to the message
    // dns server changes are part of linked properties
    private fun constructLinkedPropertyMessage(
        what: Int,
        network: Network,
        linkedProperties: LinkProperties
    ): Message {
        val networkProperties = NetworkProperties(network, linkedProperties)
        val message = Message.obtain()
        message.what = what
        message.obj = networkProperties
        return message
    }

    data class UnderlyingNetworks(
        val allNet: Set<Network>?,
        val ipv4Net: Set<Network>,
        val ipv6Net: Set<Network>,
        val useActive: Boolean,
        var isActiveNetworkMetered: Boolean,
        var lastUpdated: Long
    )

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

        var trackedIpv4Networks: MutableSet<Network> = mutableSetOf()
        var trackedIpv6Networks: MutableSet<Network> = mutableSetOf()

        var connectivityManager: ConnectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        override fun handleMessage(msg: Message) {
            // isForceUpdate - true if onUserPreferenceChanged is changes, the messages should be
            // processed forcefully regardless of the current and new networks.
            val isForceUpdate: Boolean
            when (msg.what) {
                MSG_ADD_ACTIVE_NETWORK -> {
                    isForceUpdate = msg.obj as Boolean
                    processActiveNetwork(isForceUpdate)
                }
                MSG_ADD_ALL_NETWORKS -> {
                    isForceUpdate = msg.obj as Boolean
                    processAllNetworks(isForceUpdate)
                }
                MSG_LINK_PROPERTY -> {
                    val netProps = msg.obj as NetworkProperties
                    processLinkPropertyChange(netProps)
                }
            }
        }

        /**
         * tracks the changes in active network. Set the underlying network if the current active
         * network is different from already assigned one unless the force update is required.
         */
        private fun processActiveNetwork(isForceUpdate: Boolean) {
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
                            }, Is new network? $isNewNetwork, is force update? $isForceUpdate"
            )

            if (isNewNetwork || isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(currentNetworks)
                informListener(requireAllNetworks = false, isActiveNetworkMetered)
            }
        }

        /** Adds all the available network to the underlying network. */
        private fun processAllNetworks(isForceUpdate: Boolean) {
            val newActiveNetwork = connectivityManager.activeNetwork
            // set active network's connection status
            val isActiveNetworkMetered = isConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork, true)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Log.i(
                LOG_TAG_CONNECTION,
                "process message MESSAGE_AVAILABLE_NETWORK, ${currentNetworks},${newNetworks}. isNewNetwork: $isNewNetwork, force update is $isForceUpdate"
            )

            if (isNewNetwork || isForceUpdate) {
                currentNetworks = newNetworks
                repopulateTrackedNetworks(currentNetworks)

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
                        !requireAllNetworks,
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

        private fun processLinkPropertyChange(networkProperties: NetworkProperties) {
            val linkProperties = networkProperties.linkProperties
            val network = networkProperties.network

            // do not add network if there is no internet/is VPN
            if (hasInternet(network) == false || isVPN(network) == true) {
                return
            }

            linkProperties.linkAddresses.forEach {
                // fixme: remove RT_SCOPE_UNIVERSE check once ICMP handling is added; see: #553
                if (it.scope != RT_SCOPE_UNIVERSE) return@forEach

                val address = IPAddressString(it.address.hostAddress?.toString())

                if (address.isIPv6) {
                    trackedIpv6Networks.add(network)
                } else {
                    trackedIpv4Networks.add(network)
                }
            }
        }

        private fun repopulateTrackedNetworks(networks: LinkedHashSet<Network>) {
            val ipv6: MutableSet<Network> = mutableSetOf()
            val ipv4: MutableSet<Network> = mutableSetOf()

            networks.forEach { network ->
                val linkProperties =
                    connectivityManager.getLinkProperties(network) ?: return@forEach

                linkProperties.linkAddresses.forEach inner@{ prop ->
                    // fixme: remove RT_SCOPE_UNIVERSE check once ICMP handling is added; see: #553
                    if (prop.scope != RT_SCOPE_UNIVERSE) return@inner

                    val address = IPAddressString(prop.address.hostAddress?.toString())

                    if (address.isIPv6) {
                        ipv6.add(network)
                    } else {
                        ipv4.add(network)
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
    }
}
