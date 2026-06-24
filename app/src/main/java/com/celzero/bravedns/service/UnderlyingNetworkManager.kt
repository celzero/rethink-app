/*
 * Copyright 2024 RethinkDNS and its authors
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
import Logger.LOG_TAG_VPN
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastP
import com.celzero.bravedns.util.Utilities.isAtleastS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages underlying network monitoring and callbacks.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class UnderlyingNetworkManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "UnderlyingNetwork"
    }

    private val _activeNetwork = MutableStateFlow<Network?>(null)
    val activeNetwork: StateFlow<Network?> = _activeNetwork.asStateFlow()

    private val _hasIpv4 = MutableStateFlow(false)
    val hasIpv4: StateFlow<Boolean> = _hasIpv4.asStateFlow()

    private val _hasIpv6 = MutableStateFlow(false)
    val hasIpv6: StateFlow<Boolean> = _hasIpv6.asStateFlow()

    private val _isNetworkValid = MutableStateFlow(false)
    val isNetworkValid: StateFlow<Boolean> = _isNetworkValid.asStateFlow()

    private val _networkCapabilities = MutableStateFlow<NetworkCapabilities?>(null)
    val networkCapabilities: StateFlow<NetworkCapabilities?> = _networkCapabilities.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun initialize() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (isAtleastS()) {
            registerNetworkCallback()
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logger.d(LOG_TAG_VPN, "$TAG Network available: $network")
                _activeNetwork.value = network
                _isNetworkValid.value = true
                updateCapabilities(network)
            }

            override fun onLost(network: Network) {
                Logger.d(LOG_TAG_VPN, "$TAG Network lost: $network")
                if (_activeNetwork.value == network) {
                    _activeNetwork.value = null
                    _isNetworkValid.value = false
                    _hasIpv4.value = false
                    _hasIpv6.value = false
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                Logger.d(LOG_TAG_VPN, "$TAG Capabilities changed for $network")
                if (_activeNetwork.value == network) {
                    updateCapabilities(network)
                    _networkCapabilities.value = capabilities
                }
            }

            override fun onUnavailable() {
                Logger.d(LOG_TAG_VPN, "$TAG Network unavailable")
                _isNetworkValid.value = false
                _activeNetwork.value = null
            }
        }

        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun updateCapabilities(network: Network) {
        val cm = connectivityManager ?: return
        
        try {
            val caps = if (isAtleastO()) {
                cm.getNetworkCapabilities(network)
            } else {
                null
            }

            caps?.let { capabilities ->
                _networkCapabilities.value = capabilities

                // Check for IPv4/IPv6 based on link properties
                val linkProperties = if (isAtleastP()) {
                    cm.getLinkProperties(network)
                } else {
                    null
                }

                linkProperties?.let { lp ->
                    val has4 = lp.linkAddresses.any { 
                        it.address.hostAddress?.contains(":") == false 
                    }
                    val has6 = lp.linkAddresses.any { 
                        it.address.hostAddress?.contains(":") == true 
                    }
                    _hasIpv4.value = has4
                    _hasIpv6.value = has6
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG Error updating capabilities: ${e.message}")
        }
    }

    fun getCurrentNetwork(): Network? {
        return connectivityManager?.activeNetwork
    }

    fun isNetworkConnected(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isMetered(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isWifi(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isCellular(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun cleanup() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_VPN, "$TAG Error unregistering network callback: ${e.message}")
            }
        }
        networkCallback = null
    }
}
