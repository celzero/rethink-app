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
    @Volatile private var handlerThread: HandlerThread? = null
    private var serviceHandler: NetworkRequestHandler? = null
    private val persistentState by inject<PersistentState>()

    // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-linked-hash-set/
    // https://developer.android.com/reference/kotlin/java/util/LinkedHashSet
    // LinkedHashSet maintains the iteration ordering, which is the order in which elements were
    // inserted into the set (insertion-order)
    // CurrentNetworkList first element should be always active network. So need to maintain the
    // insertion order for this Set. (Using LinkedHashSet).
    var currentNetworkList: LinkedHashSet<Network> = linkedSetOf()
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
        fun onNetworkConnected(networkSet: LinkedHashSet<Network>?)
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
        handleNetworkChange()
    }

    override fun onLost(network: Network) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLost")
        handleNetworkChange()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onCapabilitiesChanged")
        handleNetworkChange()
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLinkPropertiesChanged")
        handleNetworkChange()
    }

    /**
     * Called when the preference(isAddAllNetwork) is changed.
     * if the preference is true, add only active network
     * else, add all available network.
     */
    fun onPreferenceChanged(){
        if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onPreferenceChanged")
        handleNetworkChange()
    }

    /**
     * Returns the network list based on the user preference.
     * If isAddAllNetwork preference is selected then returns the list of available networks.
     * else return the current active network.
     */
    fun getNetworkList(): MutableSet<Network> {
        val networkList : MutableSet<Network> = mutableSetOf()
        if(persistentState.isAddAllNetwork){
            networkList.addAll(currentNetworkList)
        }else{
            currentActiveNetwork?.let { networkList.add(it) }
        }
        return networkList
    }

    private fun handleNetworkChange() {
        val message = constructMessage(if (persistentState.isAddAllNetwork) MSG_ADD_ALL_NETWORKS else MSG_ADD_ACTIVE_NETWORK)
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        serviceHandler?.sendMessage(message)
    }

    /**
     * Constructs the message object for Network handler.
     * Add the active network to the message object
     * in case of setUnderlying network has only active networks.
     */
    private fun constructMessage(what: Int): Message {
        val message = Message.obtain()
        message?.what = what
        return message!!
    }

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(looper: Looper?, val connectionMonitor: ConnectionMonitor) : Handler(looper!!) {
        override fun handleMessage(msg: Message) {
            when(msg.what) {
                MSG_ADD_ACTIVE_NETWORK -> processActiveNetwork()
                MSG_ADD_ALL_NETWORKS -> processAllNetworks()
            }
        }

        /**
         * Handles the active network.
         * Get active network from connectivity-manager
         * Set the vpn underlying network when the current active network is different from
         * already assigned active network or if there is difference in the available network set
         */
        private fun processActiveNetwork(){
            val currentActiveNetwork: Network? = connectionMonitor.connectivityManager.activeNetwork
            val isNewNetwork = connectionMonitor.currentActiveNetwork?.networkHandle == currentActiveNetwork?.networkHandle
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(currentActiveNetwork)?.typeName.toString()}, $isNewNetwork")
            connectionMonitor.currentActiveNetwork = currentActiveNetwork
            connectionMonitor.currentNetworkList.clear()
            if(isNewNetwork){
                return
            }
            if (currentActiveNetwork != null) {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message onNetworkConnected- $currentActiveNetwork")
                // Setting the setUnderLyingNetwork to null which by default will set the
                // active network.
                connectionMonitor.networkListener.onNetworkConnected(null)
            } else {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message onNetworkDisconnected")
                connectionMonitor.networkListener.onNetworkDisconnected()
            }
            Log.i(LOG_TAG, "ConnectionMonitor - currentActiveNetwork- ${connectionMonitor.connectivityManager.getNetworkInfo(currentActiveNetwork)?.typeName.toString()}")
        }

        /**
         * Adds all the available network to the underlying network.
         */
        private fun processAllNetworks() {
            val newActiveNetwork = connectionMonitor.connectivityManager.activeNetwork
            var isNewNetwork = connectionMonitor.currentActiveNetwork?.networkHandle != newActiveNetwork?.networkHandle
            val newNetworks = createNetworksSet(newActiveNetwork)
            isNewNetwork = isNewNetwork || hasDifference(connectionMonitor.currentNetworkList, newNetworks)
            connectionMonitor.currentActiveNetwork  = newActiveNetwork
            connectionMonitor.currentNetworkList = newNetworks
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message MESSAGE_AVAILABLE_NETWORK, ${connectionMonitor.currentNetworkList.size},${newNetworks.size}")
            if (connectionMonitor.currentNetworkList.size > 0 && newNetworks.size > 0) {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - Current Network -" + "${connectionMonitor.connectivityManager.getNetworkInfo(connectionMonitor.currentNetworkList.elementAt(0))?.typeName.toString()}, "
                + "already added network- ${connectionMonitor.connectivityManager.getNetworkInfo(newNetworks.elementAt(0))?.typeName.toString()}")
                Log.i(LOG_TAG, "ConnectionMonitor - isNewNetwork - $isNewNetwork, currentNetworks - ${connectionMonitor.currentNetworkList}, newNetworks- $newNetworks")
            }
            if (connectionMonitor.currentNetworkList.size == 0) {
                connectionMonitor.networkListener.onNetworkDisconnected()
                return
            }
            if(!isNewNetwork) return
            connectionMonitor.networkListener.onNetworkConnected(connectionMonitor.currentNetworkList)
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
