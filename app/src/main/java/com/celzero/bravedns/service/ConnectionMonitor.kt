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
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.google.common.collect.Sets
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


class ConnectionMonitor(context: Context, val networkListener: NetworkListener) : ConnectivityManager.NetworkCallback(), KoinComponent {
    private val networkRequest: NetworkRequest = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()

    // An Android handler thread internally operates on a looper.
    // Defined as @Volatile so that the looper in the Handler thread so that
    // the value loaded from memory instead of Thread's local cache.
    // ref - https://alvinalexander.com/java/jwarehouse/android/core/java/android/app/IntentService.java.shtml
    @Volatile private var handlerThread: HandlerThread? = null
    private var serviceHandler: NetworkRequestHandler? = null
    private val persistentState by inject<PersistentState>()

    // ref - https://developer.android.com/reference/kotlin/java/util/LinkedHashSet
    // The network list is maintained in a linked-hash-set to preserve insertion and iteration
    // order. This is required because {@link android.net.VpnService#setUnderlyingNetworks}
    // defines network priority depending on the iteration order, that is, the network
    // in the 0th index is preferred over the one at 1st index, and so on.
    var currentNetworks: LinkedHashSet<Network> = linkedSetOf()
    var currentActiveNetwork: Network? = null

    var connectivityManager: ConnectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


    companion object {
        // add active network as underlying vpn network
        const val MSG_ADD_ACTIVE_NETWORK = 1
        // add all available networks as underlying vpn networks
        const val MSG_ADD_ALL_NETWORKS = 2
    }

    init {
        connectivityManager.registerNetworkCallback(networkRequest, this)
        this.handlerThread = HandlerThread("NetworkRequestHandler.HandlerThread")
        this.handlerThread?.start()
        this.serviceHandler = NetworkRequestHandler(handlerThread?.looper, this)
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
        this.handlerThread?.quitSafely()
        this.handlerThread = null
        this.serviceHandler = null
    }

    override fun onAvailable(network: Network) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onAvailable")
        handleNetworkChange(isForceUpdate = false)
    }

    override fun onLost(network: Network) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLost")
        handleNetworkChange(isForceUpdate = false)
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onCapabilitiesChanged")
        handleNetworkChange(isForceUpdate = false)
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLinkPropertiesChanged")
        handleNetworkChange(isForceUpdate = false)
    }

    /**
     * Called when the preference(isAddAllNetwork) is changed.
     * if the preference is true, add only active network
     * else, add all available network.
     */
    fun onUserPreferenceChanged(){
        if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onUserPreferenceChanged")
        handleNetworkChange(isForceUpdate = true)
    }

    /**
     * Returns the network list based on the user preference.
     * If isAddAllNetwork preference is selected then returns the list of available networks.
     * else return the current active network.
     */
    fun getNetworkList(): LinkedHashSet<Network> {
        val networks: LinkedHashSet<Network> = linkedSetOf()
        if (persistentState.isAddAllNetworks) {
            networks.addAll(currentNetworks)
        } else {
            currentActiveNetwork?.let { networks.add(it) }
        }
        return networks
    }

    private fun handleNetworkChange(isForceUpdate : Boolean) {
        val message = constructMessage(if (persistentState.isAddAllNetworks) MSG_ADD_ALL_NETWORKS else MSG_ADD_ACTIVE_NETWORK, isForceUpdate)
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
        message?.what = what
        message?.obj = isForceUpdate
        return message!!
    }

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(looper: Looper?, val connectionMonitor: ConnectionMonitor) : Handler(looper!!) {
        override fun handleMessage(msg: Message) {
            // isForceUpdate - true if onUserPreferenceChanged is changes, the messages should be
            // processed forcefully regardless of the current and new networks.
            val isForceUpdate : Boolean = msg.obj as Boolean
            when(msg.what) {
                MSG_ADD_ACTIVE_NETWORK -> processActiveNetwork(isForceUpdate)
                MSG_ADD_ALL_NETWORKS -> processAllNetworks(isForceUpdate)
            }
        }

        /**
         * Handles the active network.
         * Get active network from connectivity-manager
         * Set the vpn underlying network when the current active network is different from
         * already assigned active network or if there is difference in the available network set
         */
        private fun processActiveNetwork(isForceUpdate : Boolean){
            val newActiveNetwork: Network? = connectionMonitor.connectivityManager.activeNetwork
            val isNewNetwork = connectionMonitor.currentActiveNetwork?.networkHandle != newActiveNetwork?.networkHandle
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(newActiveNetwork)?.typeName.toString()}, $isNewNetwork")
            connectionMonitor.currentActiveNetwork = newActiveNetwork
            connectionMonitor.currentNetworks.clear()
            if(!isNewNetwork && !isForceUpdate){
                return
            }
            if (connectionMonitor.currentActiveNetwork != null) {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message onNetworkConnected- $newActiveNetwork")
                // Setting the setUnderLyingNetwork to null which by default will set the
                // active network.
                connectionMonitor.networkListener.onNetworkConnected(null)
            } else {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message onNetworkDisconnected")
                connectionMonitor.networkListener.onNetworkDisconnected()
            }
            Log.i(LOG_TAG, "ConnectionMonitor - currentActiveNetwork- ${connectionMonitor.connectivityManager.getNetworkInfo(newActiveNetwork)?.typeName.toString()}")
        }

        /**
         * Adds all the available network to the underlying network.
         */
        private fun processAllNetworks(isForceUpdate : Boolean) {
            val newActiveNetwork = connectionMonitor.connectivityManager.activeNetwork
            var isNewNetwork = connectionMonitor.currentActiveNetwork?.networkHandle != newActiveNetwork?.networkHandle
            val newNetworks = createNetworksSet(newActiveNetwork)
            isNewNetwork = isNewNetwork || hasDifference(connectionMonitor.currentNetworks, newNetworks)
            connectionMonitor.currentActiveNetwork  = newActiveNetwork
            connectionMonitor.currentNetworks = newNetworks
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message MESSAGE_AVAILABLE_NETWORK, ${connectionMonitor.currentNetworks.size},${newNetworks.size}")
            if (connectionMonitor.currentNetworks.size == 0) {
                connectionMonitor.networkListener.onNetworkDisconnected()
                return
            }
            if(!isNewNetwork && !isForceUpdate) return
            connectionMonitor.networkListener.onNetworkConnected(connectionMonitor.currentNetworks)
        }

        private fun hasDifference(currentNetworks : LinkedHashSet<Network>, newNetworks : LinkedHashSet<Network>): Boolean{
            return Sets.symmetricDifference(currentNetworks, newNetworks).size != 0
        }

        /**
         * The first element of the list will be the active network.
         */
        private fun createNetworksSet(activeNetwork : Network?) : LinkedHashSet<Network> {
            val newNetworks : LinkedHashSet<Network> = linkedSetOf()
            activeNetwork?.let {
                newNetworks.add(it)
            }
            val tempAllNetwork = connectionMonitor.connectivityManager.allNetworks
            tempAllNetwork.forEach {
                if(it.networkHandle == activeNetwork?.networkHandle){
                    return@forEach
                }
                if (hasInternet(it) == true && isVPN(it) == false) {
                    newNetworks.add(it)
                }
            }
            return newNetworks
        }

        private fun hasInternet(network : Network) : Boolean?{
            return connectionMonitor.connectivityManager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun isVPN(network : Network) : Boolean? {
            return connectionMonitor.connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

    }
}
