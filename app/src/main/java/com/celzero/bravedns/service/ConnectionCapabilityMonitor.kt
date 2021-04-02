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
import android.net.*
import android.util.Log
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.google.common.collect.Sets
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ConnectionCapabilityMonitor(context: Context, networkListener: NetworkListener) : ConnectivityManager.NetworkCallback(), KoinComponent {

    private val networkRequest: NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private var networkListener: NetworkListener? = null
    private val activeNetworks: MutableSet<Network> = mutableSetOf()
    private val prevNetworks: MutableSet<Network> = mutableSetOf()
    var connectivityManager: ConnectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val persistentState by inject<PersistentState>()

    interface NetworkListener {
        fun onNetworkConnected()
        fun onNetworkDisconnected()
        fun onNetworkChange(networkSet: MutableSet<Network>)
        fun checkLockDown()
    }

    init {
        connectivityManager.registerNetworkCallback(networkRequest, this)
        this.networkListener = networkListener
    }

    fun removeCallBack() {
        connectivityManager.unregisterNetworkCallback(this)
    }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        //if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks onAvailable 1: ${network.networkHandle}, ${connectivityManager.activeNetwork?.networkHandle}, ${connectivityManager.getNetworkInfo(network)?.typeName}")
        if (activeNetworks.isEmpty()) {
            networkListener?.onNetworkConnected()
            prevNetworks.clear()
        }
        if (activeNetworks.none { activeNetwork -> activeNetwork.networkHandle == network.networkHandle }) activeNetworks.add(network)
        // Part of issue fix, internet connection showing metered even if its not the case.
        // (apps lose connectivity during switch over Mobile Data from WiFi)
        // Earlier implementation, the restart of VPN service happened whenever there is
        // network change.
        // With the new implementation, frequency of restarting the VPN is reduced which caused the
        // issue, the below fix will add the network received from the onAvailable() to setUnderlyingNetworks
        // if its not already part of it.
        // The active network will be added as first in the array
        setAvailableNetworks()
        networkChangeCheck()
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        //if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks onLost 1: ${network.networkHandle}, ${connectivityManager.activeNetwork?.networkHandle}, ${connectivityManager.getNetworkInfo(network)?.typeName}")
        activeNetworks.removeAll { activeNetwork -> activeNetwork.networkHandle == network.networkHandle }
        val isActiveNetworkAvailable = connectivityManager.activeNetwork != null
        if (!isActiveNetworkAvailable) {
            networkListener?.onNetworkDisconnected()
            if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks onLost: null val")
            persistentState.setTestNetworkVal("Empty")
        } else {
            // Part of issue fix, internet connection showing metered even if its not the case.
            // (apps lose connectivity during switch over Mobile Data from WiFi)
            // Earlier implementation, the restart of VPN service happened whenever there is
            // network change.
            // With the new implementation, frequency of restarting the VPN is reduced which caused the
            // issue, the below fix will add the network received from the onLost() to setUnderlyingNetworks
            // if its not already part of it.
            // The active network will be added as first in the array
            setAvailableNetworks()
            networkChangeCheck()
        }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, networkCapabilities)
        /* As of now, the capability change is used to check for the lockdown mode("Block connections
           without VPN"). If the lockdown mode is modified, then the VPN service will be restarted*/
        if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks onCapabilitiesChanged received")
        //if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks printCapability ${network.networkHandle}, ${connectivityManager.activeNetwork?.networkHandle}, ${connectivityManager.getNetworkInfo(network)?.typeName}")
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            printCapability(networkCapabilities)
        }*/
        setAvailableNetworks()
        networkChangeCheck()
        networkListener?.checkLockDown()
    }

    fun getNetworkList(): MutableSet<Network> {
        return activeNetworks
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        super.onLinkPropertiesChanged(network, linkProperties)
        // As of now, the capability change is used to check for the lockdown mode("Block connections
        // without VPN"). If the lockdown mode is modified, then the VPN service will be restarted
        if(DEBUG)Log.d(LOG_TAG, "ActiveNetworks onCapabilitiesChanged received")
        setAvailableNetworks()
        networkChangeCheck()
        networkListener?.checkLockDown()
    }

    private fun setAvailableNetworks() {
        activeNetworks.clear()
        connectivityManager.activeNetwork?.let {
            activeNetworks.add(it)
        }
        val tempAllNetwork = connectivityManager.allNetworks
        tempAllNetwork.forEach {
            val hasInternet = connectivityManager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isVPN = connectivityManager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (hasInternet == true && isVPN == false) {
                activeNetworks.add(it)
                //if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks **adding** onAvailable: ${it.networkHandle}, ${connectivityManager.getNetworkInfo(it)?.typeName}")
            } else {
                //if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks **Not added** onAvailable: ${it.networkHandle}, ${connectivityManager.getNetworkInfo(it)?.typeName}")
            }
        }
    }

    private fun networkChangeCheck() {
        val setDiff = Sets.symmetricDifference(prevNetworks, activeNetworks)
        if(DEBUG) Log.d(LOG_TAG, "ActiveNetworks setDiff size - ${setDiff.size}, ${prevNetworks.size}, ${activeNetworks.size}")
        var isActiveNetworkChanged = false
        if(prevNetworks.size > 0 && activeNetworks.size> 0){
            isActiveNetworkChanged = (prevNetworks.elementAt(0).networkHandle != activeNetworks.elementAt(0).networkHandle)
        }
        if (setDiff.size != 0 || isActiveNetworkChanged) {
            if (DEBUG) Log.d(LOG_TAG, "ActiveNetworks - setting setUnderlyingNetworks - ${activeNetworks.size}")
            if(activeNetworks.size>0)
                connectivityManager.getNetworkInfo(activeNetworks.elementAt(0))?.typeName?.let { persistentState.setTestNetworkVal(it) }
            networkListener?.onNetworkChange(activeNetworks)
            prevNetworks.clear()
            prevNetworks.addAll(activeNetworks)
        }
    }

    /*@RequiresApi(Build.VERSION_CODES.Q)
    private fun printCapability(networkCapabilities: NetworkCapabilities) {
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_INTERNET " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_DUN " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_NOT_RESTRICTED " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_NOT_VPN " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_CAPTIVE_PORTAL " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_CBS " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CBS))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_EIMS " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_FOREGROUND " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_FOTA " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_IA " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_IMS " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_MCX " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_MCX))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_MMS " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_NOT_CONGESTED " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_NOT_METERED " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_NOT_ROAMING " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_NOT_SUSPENDED " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_RCS " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCS))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_SUPL " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_TEMPORARILY_NOT_METERED " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
        }
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_TRUSTED " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_VALIDATED " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_WIFI_P2P " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P))
        Log.d(LOG_TAG, "ActiveNetworks NET_CAPABILITY_XCAP " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP))
        Log.d(LOG_TAG, "ActiveNetworks TRANSPORT_VPN " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        Log.d(LOG_TAG, "ActiveNetworks TRANSPORT_ETHERNET " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        Log.d(LOG_TAG, "ActiveNetworks TRANSPORT_WIFI " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        Log.d(LOG_TAG, "ActiveNetworks TRANSPORT_BLUETOOTH " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
        Log.d(LOG_TAG, "ActiveNetworks TRANSPORT_CELLULAR " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        Log.d(LOG_TAG, "ActiveNetworks TRANSPORT_LOWPAN " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN))
        Log.d(LOG_TAG, "ActiveNetworks TRANSPORT_WIFI_AWARE " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE))
    }*/

}