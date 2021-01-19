package com.celzero.bravedns.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import kotlinx.coroutines.InternalCoroutinesApi
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@KoinApiExtension
class ConnectionCapabilityMonitor(context: Context) : ConnectivityManager.NetworkCallback(), KoinComponent {

    private val networkRequest: NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val appMode by inject<AppMode>()

    init {
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(networkRequest, this)
    }

    fun removeCallBack(context: Context){
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(this)
    }


    @InternalCoroutinesApi
    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, networkCapabilities)
        Log.d(LOG_TAG, "Value for onCapabilitiesChanged received")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val vpnService = VpnController.getInstance()?.getBraveVpnService()
            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "Value for isLockDownEnabled - ${vpnService?.isLockdownEnabled}, ${vpnService?.isLockDownPrevious}")
            if(vpnService?.isLockdownEnabled != vpnService?.isLockDownPrevious){
                vpnService?.isLockDownPrevious = vpnService?.isLockdownEnabled!!
                vpnService.restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
            }
        }
    }
}