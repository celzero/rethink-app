/*
Copyright 2021 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
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

    @Volatile private var mHandlerThread: HandlerThread? = null
    private var mServiceHandler: NetworkRequestHandler? = null
    private var isAllNetworkSupported: Boolean = false
    private val persistentState by inject<PersistentState>()

    var currentNetworkList: LinkedHashSet<Network> = linkedSetOf()
    var currentActiveNetwork: Network? = null

    var connectivityManager: ConnectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


    companion object {
        const val MESSAGE_ACTIVE_NETWORK = 1
        const val MESSAGE_AVAILABLE_NETWORK = 2
    }

    init {
        connectivityManager.registerNetworkCallback(networkRequest, this)
        this.mHandlerThread = HandlerThread("NetworkRequestHandler.HandlerThread")
        this.mHandlerThread?.start()
        this.mServiceHandler = NetworkRequestHandler(mHandlerThread?.looper, this)
        isAllNetworkSupported = persistentState.isAddAllNetwork
    }

    interface NetworkListener {
        fun onNetworkDisconnected()
        fun onUpdateActiveNetwork(network: Network?)
        fun onNetworkChange(networkSet: LinkedHashSet<Network>)
    }

    fun removeCallBack() {
        connectivityManager.unregisterNetworkCallback(this)
        destroy()
    }

    private fun destroy() {
        this.mServiceHandler?.removeCallbacksAndMessages(null)
        this.mHandlerThread?.quitSafely()
        this.mHandlerThread = null
        this.mServiceHandler = null
    }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onAvailable")
        if (!isAllNetworkSupported) {
            // add active network alone to the underlying network
            val message = constructMessage(MESSAGE_ACTIVE_NETWORK)
            mServiceHandler?.sendMessage(message)
        } else {
            // Add all the available networks to the underlying networks
            val message = constructMessage(MESSAGE_AVAILABLE_NETWORK)
            mServiceHandler?.sendMessage(message)
        }
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLost")
        if (!isAllNetworkSupported) {
            // add active network alone to the underlying network
            val message = constructMessage(MESSAGE_ACTIVE_NETWORK)
            mServiceHandler?.sendMessage(message)
        } else {
            // Add all the available networks to the underlying networks
            val message = constructMessage(MESSAGE_AVAILABLE_NETWORK)
            mServiceHandler?.sendMessage(message)
        }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, networkCapabilities)
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onCapabilitiesChanged")
        if (!isAllNetworkSupported) {
            // add active network alone to the underlying network
            val message = constructMessage(mServiceHandler?.obtainMessage(), MESSAGE_ACTIVE_NETWORK)
            mServiceHandler?.sendMessage(message)
        } else {
            // Add all the available networks to the underlying networks
            val message = constructMessage(mServiceHandler?.obtainMessage(), MESSAGE_AVAILABLE_NETWORK)
            mServiceHandler?.sendMessage(message)
        }
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        super.onLinkPropertiesChanged(network, linkProperties)
        if(DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - onLinkPropertiesChanged")
        if (!isAllNetworkSupported) {
            // add active network alone to the underlying network
            val message = constructMessage(mServiceHandler?.obtainMessage(), MESSAGE_ACTIVE_NETWORK)
            mServiceHandler?.sendMessage(message)
        } else {
            // Add all the available networks to the underlying networks
            val message = constructMessage(mServiceHandler?.obtainMessage(), MESSAGE_AVAILABLE_NETWORK)
            mServiceHandler?.sendMessage(message)
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
        if (what == MESSAGE_ACTIVE_NETWORK) {
            message.obj = connectivityManager.activeNetwork
        }
        return message
    }

    private fun constructMessage(message: Message?, what: Int): Message {
        var messageValue = message
        if (messageValue == null) {
            messageValue = Message()
        }
        messageValue.what = what
        if (what == MESSAGE_ACTIVE_NETWORK) {
            messageValue.obj = connectivityManager.activeNetwork
        }
        return messageValue
    }

    // Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(looper: Looper?, val connectionMonitor: ConnectionMonitor) : Handler(looper!!) {
        var addedNetworkList: LinkedHashSet<Network> = linkedSetOf()
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            processMessage(msg)
        }

        fun processMessage(msg: Message) {
            if (msg.what == MESSAGE_ACTIVE_NETWORK) {
                processActiveNetwork(msg)
            } else if (msg.what == MESSAGE_AVAILABLE_NETWORK) {
                processAllAvailableNetwork()
            }
        }

        /**
         * Handles the active networks.
         * Message object will have the active network associated with it.
         * Checks if the currentActiveNetwork object is not equal to activeNetwork - VPN underlying
         * network will be set.
         */
        private fun processActiveNetwork(msg : Message){
            var activeNetwork: Network? = null
            if (msg.obj != null) {
                activeNetwork = msg.obj as Network
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message ${connectionMonitor.connectivityManager.getNetworkInfo(activeNetwork)?.typeName.toString()}")
                if (connectionMonitor.currentActiveNetwork?.networkHandle != activeNetwork.networkHandle) {
                    connectionMonitor.networkListener.onUpdateActiveNetwork(activeNetwork)
                    connectionMonitor.currentActiveNetwork = activeNetwork
                }
            } else {
                connectionMonitor.networkListener.onNetworkDisconnected()
                connectionMonitor.currentActiveNetwork = null
            }
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(activeNetwork)?.typeName.toString()}")
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(connectionMonitor.currentActiveNetwork)?.typeName.toString()}")
        }

        /**
         * Adds all the available network to the underlying network.
         */
        private fun processAllAvailableNetwork() {
            var isActiveNetworkChanged = false
            setAvailableNetworks()
            if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - process message MESSAGE_AVAILABLE_NETWORK")
            if (connectionMonitor.currentNetworkList.size > 0 && addedNetworkList.size > 0) {
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(connectionMonitor.currentNetworkList.elementAt(0))?.typeName.toString()}")
                if (DEBUG) Log.d(LOG_TAG, "ConnectionMonitor - ${connectionMonitor.connectivityManager.getNetworkInfo(addedNetworkList.elementAt(0))?.typeName.toString()}")
                isActiveNetworkChanged = (connectionMonitor.currentNetworkList.elementAt(0).networkHandle != addedNetworkList.elementAt(0).networkHandle)
            }
            val setDiff = Sets.symmetricDifference(connectionMonitor.currentNetworkList, addedNetworkList)
            if (setDiff.size != 0 || isActiveNetworkChanged) {
                connectionMonitor.networkListener.onNetworkChange(addedNetworkList)
                connectionMonitor.currentNetworkList.clear()
                connectionMonitor.currentNetworkList.addAll(addedNetworkList)
            }
        }

        /**
         * The first element of the list will be the active network.
         */
        private fun setAvailableNetworks() {
            addedNetworkList = linkedSetOf()
            connectionMonitor.connectivityManager.activeNetwork?.let {
                addedNetworkList.add(it)
            }
            val tempAllNetwork = connectionMonitor.connectivityManager.allNetworks
            tempAllNetwork.forEach {
                val hasInternet = connectionMonitor.connectivityManager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isVPN = connectionMonitor.connectivityManager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (hasInternet == true && isVPN == false) {
                    addedNetworkList.add(it)
                }
            }
        }

    }

}