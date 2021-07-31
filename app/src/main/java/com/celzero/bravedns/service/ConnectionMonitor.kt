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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_CONNECTION
import com.google.common.collect.Sets
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


class ConnectionMonitor(context: Context, networkListener: NetworkListener) :
        ConnectivityManager.NetworkCallback(), KoinComponent {
    private val networkRequest: NetworkRequest = NetworkRequest.Builder().addCapability(
        NetworkCapabilities.NET_CAPABILITY_INTERNET).build()

    // An Android handler thread internally operates on a looper.
    // Defined as @Volatile so that the looper in the Handler thread so that
    // the value loaded from memory instead of Thread's local cache.
    // ref - https://alvinalexander.com/java/jwarehouse/android/core/java/android/app/IntentService.java.shtml
    @Volatile private var handlerThread: HandlerThread
    private var serviceHandler: NetworkRequestHandler? = null
    private val persistentState by inject<PersistentState>()

    var connectivityManager: ConnectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        // add active network as underlying vpn network
        const val MSG_ADD_ACTIVE_NETWORK = 1

        // add all available networks as underlying vpn networks
        const val MSG_ADD_ALL_NETWORKS = 2
    }

    init {
        connectivityManager.registerNetworkCallback(networkRequest, this)
        this.handlerThread = HandlerThread(NetworkRequestHandler::class.simpleName)
        this.handlerThread.start()
        this.serviceHandler = NetworkRequestHandler(context, handlerThread.looper, networkListener)
    }

    interface NetworkListener {
        fun onNetworkDisconnected()
        fun onNetworkConnected(networks: LinkedHashSet<Network>?)
    }

    fun removeCallBack() {
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
    }

    /**
     * Handles user preference changes, ie, when the user elects to see
     * either multiple underlying networks, or just one (the active network).
     */
    fun onUserPreferenceChanged() {
        if (DEBUG) Log.d(LOG_TAG_CONNECTION, "onUserPreferenceChanged")
        handleNetworkChange(isForceUpdate = true)
    }

    /**
     * Force updates the VPN's underlying network based on the preference.
     * Will be initiated when the VPN start is completed.
     */
    fun onVpnStarted() {
        Log.i(LOG_TAG_CONNECTION, "new vpn is created force update the network")
        handleNetworkChange(isForceUpdate = true)
    }

    private fun handleNetworkChange(isForceUpdate: Boolean = false) {
        val message = constructMessage(
            if (persistentState.isAddAllNetworks) MSG_ADD_ALL_NETWORKS else MSG_ADD_ACTIVE_NETWORK,
            isForceUpdate)
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        serviceHandler?.sendMessage(message)
    }

    /**
     * Constructs the message object for Network handler.
     * Add the active network to the message object
     * in case of setUnderlying network has only active networks.
     */
    private fun constructMessage(what: Int, isForceUpdate: Boolean): Message {
        val message = Message.obtain()
        message.what = what
        message.obj = isForceUpdate
        return message
    }

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(context: Context, looper: Looper,
                                        val networkListener: NetworkListener) : Handler(looper) {
        // ref - https://developer.android.com/reference/kotlin/java/util/LinkedHashSet
        // The network list is maintained in a linked-hash-set to preserve insertion and iteration
        // order. This is required because {@link android.net.VpnService#setUnderlyingNetworks}
        // defines network priority depending on the iteration order, that is, the network
        // in the 0th index is preferred over the one at 1st index, and so on.
        var currentNetworks: LinkedHashSet<Network> = linkedSetOf()
        var connectivityManager: ConnectivityManager = context.applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        override fun handleMessage(msg: Message) {
            // isForceUpdate - true if onUserPreferenceChanged is changes, the messages should be
            // processed forcefully regardless of the current and new networks.
            val isForceUpdate: Boolean = msg.obj as Boolean
            when (msg.what) {
                MSG_ADD_ACTIVE_NETWORK -> processActiveNetwork(isForceUpdate)
                MSG_ADD_ALL_NETWORKS -> processAllNetworks(isForceUpdate)
            }
        }

        /**
         * tracks the changes in active network.
         * Set the underlying network if the current active network is different from
         * already assigned one unless the force update is required.
         */
        private fun processActiveNetwork(isForceUpdate: Boolean) {
            val newActiveNetwork = connectivityManager.activeNetwork
            val newNetworks = createNetworksSet(newActiveNetwork)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            currentNetworks = newNetworks
            Log.i(LOG_TAG_CONNECTION, "Connected network- ${
                connectivityManager.getNetworkInfo(newActiveNetwork)?.typeName.toString()
            }, Is new network? $isNewNetwork, is force update? $isForceUpdate")

            if (!isNewNetwork && !isForceUpdate) return

            informListener()
        }

        /**
         * Adds all the available network to the underlying network.
         */
        private fun processAllNetworks(isForceUpdate: Boolean) {
            val newActiveNetwork = connectivityManager.activeNetwork
            val newNetworks = createNetworksSet(newActiveNetwork, true)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)

            Log.i(LOG_TAG_CONNECTION,
                  "process message MESSAGE_AVAILABLE_NETWORK, ${currentNetworks.size},${newNetworks.size}. isNewNetwork - $isNewNetwork, force update is $isForceUpdate")
            currentNetworks = newNetworks

            if (!isNewNetwork && !isForceUpdate) return

            informListener(requireAllNetworks = true)
        }

        private fun informListener(requireAllNetworks: Boolean = false) {
            Log.i(LOG_TAG_CONNECTION,
                  "inform listener on network change - ${currentNetworks.size}, is all network - $requireAllNetworks")
            if (currentNetworks.size > 0) {
                val networks = when (requireAllNetworks) {
                    true -> currentNetworks
                    false -> null
                }
                networkListener.onNetworkConnected(networks)
            } else {
                networkListener.onNetworkDisconnected()
            }
        }

        private fun hasDifference(currentNetworks: LinkedHashSet<Network>,
                                  newNetworks: LinkedHashSet<Network>): Boolean {
            return Sets.symmetricDifference(currentNetworks, newNetworks).size != 0
        }

        /**
         * Create network set(available networks) based on the user preference.
         * The first element of the list must be active network.
         * requireAllNetwork - if true adds all networks to the set else adds active network
         * alone to the set.
         */
        private fun createNetworksSet(activeNetwork: Network?,
                                      requireAllNetworks: Boolean = false): LinkedHashSet<Network> {
            val newNetworks: LinkedHashSet<Network> = linkedSetOf()
            activeNetwork?.let {
                if (hasInternet(it) == true && isVPN(it) == false) {
                    newNetworks.add(it)
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
                }
            }

            return newNetworks
        }

        private fun hasInternet(network: Network): Boolean? {
            return connectivityManager.getNetworkCapabilities(network)?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun isVPN(network: Network): Boolean? {
            return connectivityManager.getNetworkCapabilities(network)?.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}
