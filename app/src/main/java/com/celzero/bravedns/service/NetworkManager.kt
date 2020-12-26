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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.util.Log
import java.net.InetAddress
import java.util.*


class NetworkManager(context: Context, networkListener: NetworkListener) {

    private var connectivityManager: ConnectivityManager? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private var applicationContext: Context? = null
    private var networkListener: NetworkListener? = null

    interface NetworkListener {
        fun onNetworkConnected(networkInfo: NetworkInfo?)
        fun onNetworkDisconnected()
    }

   /* fun NetworkManager(context: Context, networkListener: NetworkListener) {
        applicationContext = context.applicationContext
        connectivityManager = applicationContext!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        this.networkListener = networkListener
        val intentFilter = IntentFilter()
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                connectivityChanged(intent)
            }
        }
        applicationContext!!.registerReceiver(this.broadcastReceiver, intentFilter)

        // Fire onNetworkConnected listener immediately if we are online.
        val networkInfo: NetworkInfo = connectivityManager!!.getActiveNetworkInfo()
        if (networkInfo != null && networkInfo.isConnected) {
            networkListener.onNetworkConnected(networkInfo)
        }
    }*/


    init {
        applicationContext = context.applicationContext
        connectivityManager = applicationContext!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        this.networkListener = networkListener
        val intentFilter = IntentFilter()
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                connectivityChanged(intent)
            }
        }

        applicationContext!!.registerReceiver(this.broadcastReceiver, intentFilter)
        // Fire onNetworkConnected listener immediately if we are online.
        connectivityManager?.activeNetworkInfo?.takeIf { it.isConnected }?.also {
            networkListener.onNetworkConnected(it)
        }
    }


    fun destroy() {
        if (broadcastReceiver == null) {
            return
        }
        try {
            applicationContext!!.unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            Log.e("NetworkManager","Error unregistering network receiver: " + e.message, e)
        } finally {
            broadcastReceiver = null
        }
    }

    private fun connectivityChanged(intent: Intent) {
        val activeNetworkInfo = connectivityManager!!.activeNetworkInfo
        val intentNetworkInfo = 
            intent.getParcelableExtra<NetworkInfo>("networkInfo")
        if (networkListener == null) {
            return
        }
        if (isConnectedNetwork(activeNetworkInfo)
            && intentNetworkInfo != null && intentNetworkInfo.type == ConnectivityManager.TYPE_VPN
        ) {
            // VPN state changed, we have connectivity, ignore.
            return
        } else if (!isConnectedNetwork(activeNetworkInfo)) {
            // No active network, signal disconnect event.
            networkListener!!.onNetworkDisconnected()
        } else if (activeNetworkInfo!!.type != ConnectivityManager.TYPE_VPN) {
            // We have an active network, make sure it is not a VPN to signal a connected event.
            networkListener!!.onNetworkConnected(activeNetworkInfo)
        }
    }

    // Returns true if the supplied network is connected and available
    //TODO : Lookout for ConnectivityManager.NetworkCallback API instead of NetworkInfo
    private fun isConnectedNetwork(networkInfo: NetworkInfo?): Boolean {
        return if (networkInfo == null) {
            false
        } else networkInfo.isConnectedOrConnecting && networkInfo.isAvailable

    }




    fun getSystemResolvers(): List<InetAddress>? {
        // This list of network types is in roughly descending priority order, so that the first
        // entries in the returned list are most likely to be the appropriate resolvers.
        val networkTypes = intArrayOf(
            ConnectivityManager.TYPE_ETHERNET,
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_WIMAX
        )
        val resolvers: MutableList<InetAddress> =
            ArrayList()
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            Log.i("NetworkManager","This function should never be called in L+.")

            return resolvers
        }
        try {
            // LinkProperties ConnectivityManager.getLinkProperties(int type) (removed in Lollipop)
            val getLinkProperties = ConnectivityManager::class.java
                .getMethod("getLinkProperties", Int::class.javaPrimitiveType)
            // LinkProperties, which existed before Lollipop but had a different API and was not exposed.
            val linkPropertiesClass =
                Class.forName("android.net.LinkProperties")
            // Collection<InetAddress> LinkProperties.getDnses() (replaced by getDnsServers in Lollipop).
            val getDnses = linkPropertiesClass.getMethod("getDnses")
            for (networkType in networkTypes) {
                val linkProperties =
                    getLinkProperties.invoke(connectivityManager, networkType)
                        ?: // No network of this type.
                        continue
                val addresses =
                    getDnses.invoke(linkProperties) as Collection<*>
                for (address in addresses) {
                    resolvers.add(address as InetAddress)
                }
            }
        } catch (e: java.lang.Exception) {
            Log.e("NetworkManager","Error unregistering network receiver: " + e.message, e)

        }
        return resolvers
    }

}
