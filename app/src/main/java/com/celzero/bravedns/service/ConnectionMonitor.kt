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

    @Volatile private var handlerThread: HandlerThread? = null
    private var serviceHandler: NetworkRequestHandler? = null
    private var isAllNetworkSupported: Boolean = false
    private val persistentState by inject<PersistentState>()

    // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-linked-hash-set/
    // https://developer.android.com/reference/kotlin/java/util/LinkedHashSet
    // LinkedHashSet maintains the iteration ordering, which is the order in which elements were
    // inserted into the set (insertion-order)
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
        isAllNetworkSupported = persistentState.isAddAllNetwork
    }

    interface NetworkListener {
        fun onNetworkDisconnected()
        fun onUpdateActiveNetwork(network: Network?)
        fun onNetworkConnected(networkSet: LinkedHashSet<Network>)
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
        if (!isAllNetworkSupported) {

            val message = constructMessage(MSG_ADD_ACTIVE_NETWORK)
            serviceHandler?.sendMessage(message)
        } else {

            val message = constructMessage(MSG_ADD_ALL_NETWORKS)
            serviceHandler?.sendMessage(message)
        }
    }

    override fun onLost(network: Network) {
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLost")
        if (!isAllNetworkSupported) {
            val message = constructMessage(MSG_ADD_ACTIVE_NETWORK)
            serviceHandler?.sendMessage(message)
        } else {
            val message = constructMessage(MSG_ADD_ALL_NETWORKS)
            serviceHandler?.sendMessage(message)
        }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onCapabilitiesChanged")
        if (!isAllNetworkSupported) {
            val message = constructMessage(serviceHandler?.obtainMessage(), MSG_ADD_ACTIVE_NETWORK)
            serviceHandler?.sendMessage(message)
        } else {
            val message = constructMessage(serviceHandler?.obtainMessage(), MSG_ADD_ALL_NETWORKS)
            serviceHandler?.sendMessage(message)
        }
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLinkPropertiesChanged")
        if (!isAllNetworkSupported) {
            val message = constructMessage(serviceHandler?.obtainMessage(), MSG_ADD_ACTIVE_NETWORK)
            serviceHandler?.sendMessage(message)
        } else {
            val message = constructMessage(serviceHandler?.obtainMessage(), MSG_ADD_ALL_NETWORKS)
            serviceHandler?.sendMessage(message)
        }
    }

    /**
     * Called when the preference(isAddAllNetwork) is changed.
     * if the preference is true, add only active network
     * else, add all available network.
     */
    fun onPreferenceChanged(){
        isAllNetworkSupported = persistentState.isAddAllNetwork
        serviceHandler?.removeMessages(MSG_ADD_ACTIVE_NETWORK, null)
        serviceHandler?.removeMessages(MSG_ADD_ALL_NETWORKS, null)
        if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onPreferenceChanged")
        if (!isAllNetworkSupported) {
            val message = constructMessage(serviceHandler?.obtainMessage(), MSG_ADD_ACTIVE_NETWORK)
            serviceHandler?.sendMessage(message)
        } else {
            val message = constructMessage(serviceHandler?.obtainMessage(), MSG_ADD_ALL_NETWORKS)
            serviceHandler?.sendMessage(message)
        }
    }

    fun getNetworkList(): MutableSet<Network> {
        return currentNetworkList
    }

    /**
     * Constructs the message object for Network handler.
     * Add the active network to the message object
     * in case of setUnderlying network has only active networks.
     */
    private fun constructMessage(what: Int): Message {
        val message = Message()
        message.what = what
        return message
    }

    private fun constructMessage(message: Message?, what: Int): Message {
        var messageValue = message
        if (messageValue == null) {
            messageValue = Message()
        }
        messageValue.what = what
        return messageValue
    }

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(looper: Looper?, val connectionMonitor: ConnectionMonitor) : Handler(looper!!) {
        override fun handleMessage(msg: Message) {
            processMessage(msg)
        }

        fun processMessage(msg: Message) {
            if (msg.what == MSG_ADD_ACTIVE_NETWORK) {
                processActiveNetwork()
            } else if (msg.what == MSG_ADD_ALL_NETWORKS) {
                processAllNetwork()
            }
        }

        /**
         * Handles the active network.
         * Get active network from connectivity-manager
         * Set the vpn underlying network when the current active network is different from
         * already assigned active network or if there is difference in the available network set
         */
        private fun processActiveNetwork(){
            val activeNetwork: Network? = connectionMonitor.connectivityManager.activeNetwork
            val isNewNetwork = connectionMonitor.currentActiveNetwork?.networkHandle != activeNetwork?.networkHandle
            connectionMonitor.currentActiveNetwork = activeNetwork
            if(!isNewNetwork){
                return
            }
            if (activeNetwork != null) {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message ${connectionMonitor.connectivityManager.getNetworkInfo(activeNetwork)?.typeName.toString()}")
                connectionMonitor.networkListener.onUpdateActiveNetwork(activeNetwork)
            } else {
                connectionMonitor.networkListener.onNetworkDisconnected()
            }
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(activeNetwork)?.typeName.toString()}")
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(connectionMonitor.currentActiveNetwork)?.typeName.toString()}")
        }



        /**
         * Adds all the available network to the underlying network.
         */
        private fun processAllNetwork() {
            val newActiveNetwork = connectionMonitor.connectivityManager.activeNetwork
            var isNewNetwork = connectionMonitor.currentActiveNetwork != newActiveNetwork
            connectionMonitor.currentActiveNetwork  = newActiveNetwork
            val addedNetworkList = createNetworksSet(newActiveNetwork)

            isNewNetwork = isNewNetwork || hasDifference(connectionMonitor.currentNetworkList, addedNetworkList)
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message MESSAGE_AVAILABLE_NETWORK")

            if(!isNewNetwork) return

            if (connectionMonitor.currentNetworkList.size > 0 && addedNetworkList.size > 0) {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - Current Network -" +
                 "${connectionMonitor.connectivityManager.getNetworkInfo(connectionMonitor.currentNetworkList.elementAt(0))?.typeName.toString()}, " +
                  "already added network- ${connectionMonitor.connectivityManager.getNetworkInfo(addedNetworkList.elementAt(0))?.typeName.toString()}")
            }
            connectionMonitor.networkListener.onNetworkConnected(addedNetworkList)
            connectionMonitor.currentNetworkList.clear()
            connectionMonitor.currentNetworkList.addAll(addedNetworkList)
        }

        private fun hasDifference(currentNetworks : LinkedHashSet<Network>, newNetworks : LinkedHashSet<Network>): Boolean{
            return Sets.symmetricDifference(currentNetworks, newNetworks).size != 0
        }

        /**
         * The first element of the list will be the active network.
         */
        private fun createNetworksSet(activeNetwork : Network?) : LinkedHashSet<Network> {
            val addedNetworkList : LinkedHashSet<Network> = linkedSetOf()
            activeNetwork?.let {
                addedNetworkList.add(it)
            }
            val tempAllNetwork = connectionMonitor.connectivityManager.allNetworks
            tempAllNetwork.forEach {
                if(it == activeNetwork) return@forEach
                val hasInternet = connectionMonitor.connectivityManager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isVPN = connectionMonitor.connectivityManager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (hasInternet == true && isVPN == false) {
                    addedNetworkList.add(it)
                }
            }
            return addedNetworkList
        }

    }
}
