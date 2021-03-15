/*
Copyright 2020 RethinkDNS and its authors

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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import settings.Settings
import java.net.InetAddress
import java.util.*

@KoinApiExtension
class ConnectionCapabilityMonitor(context: Context, networkListener: NetworkListener) : ConnectivityManager.NetworkCallback(), KoinComponent {

    private val networkRequest: NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val appMode by inject<AppMode>()
    private var networkListener : NetworkListener? = null
    private val activeNetworks: MutableList<Network> = mutableListOf()
    var connectivityManager : ConnectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    interface NetworkListener {
        fun onNetworkConnected()
        fun onNetworkDisconnected()
    }

    init {
        connectivityManager.registerNetworkCallback(networkRequest, this)
        this.networkListener = networkListener
    }

    fun removeCallBack(){
        connectivityManager.unregisterNetworkCallback(this)
    }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        if (activeNetworks.none { activeNetwork -> activeNetwork.networkHandle == network.networkHandle }) activeNetworks.add(network)
        val isNetworkConnected = activeNetworks.isNotEmpty()
        if(isNetworkConnected) {
            networkListener?.onNetworkConnected()
        }
    }


    override fun onLost(network: Network) {
        super.onLost(network)
        activeNetworks.removeAll { activeNetwork -> activeNetwork.networkHandle == network.networkHandle }
        val isNetworkConnected = activeNetworks.isNotEmpty()
        if(!isNetworkConnected) {
            networkListener?.onNetworkDisconnected()
        }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, networkCapabilities)
        /* As of now, the capability change is used to check for the lockdown mode("Block connections
           without VPN"). If the lockdown mode is modified, then the VPN service will be restarted*/
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val vpnService = VpnController.getInstance()?.getBraveVpnService()
            if (DEBUG) Log.d(LOG_TAG, "ConnectionCapabilityMonitor Value for isLockDownEnabled - ${vpnService?.isLockdownEnabled}, ${vpnService?.isLockDownPrevious}")
            if (vpnService?.isLockdownEnabled != vpnService?.isLockDownPrevious) {
                vpnService?.isLockDownPrevious = vpnService?.isLockdownEnabled!!
                //Introducing the lockdown mode and Orbot - proxy mode for the Orbot one touch
                //configuration. When the lockdown mode is enabled, the exclusion of Orbot will
                // be avoided which will result in no internet connectivity.
                //This is temp change, the changes are need to be moved out of capabilities once the
                //appMode variable is removed.
                if(vpnService.isLockdownEnabled && appMode.getProxyMode() == Constants.ORBOT_SOCKS) {
                    if(DEBUG) Log.d(LOG_TAG,"isLockDownEnabled - True, ORBOT is socks5 - restart with proxy mode none")
                    vpnService.restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), Settings.ProxyModeNone)
                }else{
                    if(DEBUG) Log.d(LOG_TAG,"isLockDownEnabled - False, ORBOT is ${appMode.getProxyMode()} - restart with set proxy mode")
                    vpnService.restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
                }
            }
        }
    }

    fun getSystemResolvers(): List<InetAddress>? {
        // This list of network types is in roughly descending priority order, so that the first
        // entries in the returned list are most likely to be the appropriate resolvers.
        val networkTypes = intArrayOf(ConnectivityManager.TYPE_ETHERNET, ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIMAX)
        val resolvers: MutableList<InetAddress> = ArrayList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.i("NetworkManager", "This function should never be called in L+.")
            return resolvers
        }
        try {
            // LinkProperties ConnectivityManager.getLinkProperties(int type) (removed in Lollipop)
            val getLinkProperties = ConnectivityManager::class.java.getMethod("getLinkProperties", Int::class.javaPrimitiveType)
            // LinkProperties, which existed before Lollipop but had a different API and was not exposed.
            val linkPropertiesClass = Class.forName("android.net.LinkProperties")
            // Collection<InetAddress> LinkProperties.getDnses() (replaced by getDnsServers in Lollipop).
            val getDnses = linkPropertiesClass.getMethod("getDnses")
            for (networkType in networkTypes) {
                val linkProperties = getLinkProperties.invoke(connectivityManager, networkType) ?: // No network of this type.
                continue
                val addresses = getDnses.invoke(linkProperties) as Collection<*>
                for (address in addresses) {
                    resolvers.add(address as InetAddress)
                }
            }
        } catch (e: java.lang.Exception) {
            Log.e("NetworkManager", "Error unregistering network receiver: " + e.message, e)

        }
        return resolvers
    }


}