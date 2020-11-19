/*
Copyright 2019 Jigsaw Operations LLC

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

import android.app.*
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.SystemClock
import android.service.quicksettings.TileService.requestListeningState
import android.text.TextUtils
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.BraveAutoStartReceiver
import com.celzero.bravedns.receiver.BraveScreenStateReceiver
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment.Companion.UNIVERSAL_RULES_UID
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.backgroundAllowedUID
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.FileSystemUID
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.launch
import protect.Blocker
import protect.Protector
import settings.Settings
import java.util.*
import kotlin.collections.HashMap

class BraveVPNService :
    VpnService(), NetworkManager.NetworkListener, Protector, Blocker, OnSharedPreferenceChangeListener {

    @GuardedBy("vpnController")
    private var networkManager: NetworkManager? = null


    @GuardedBy("vpnController")
    private var vpnAdapter: GoVpnAdapter? = null

    private val DNS_REQUEST_PORT = 53
    private val PRIVATE_DNS_PORT = 853

    companion object {

        var firewallRules = FirewallRules.getInstance()
        private const val LOG_TAG = "RethinkVPNService"
        private const val DEBUG = true
        const val SERVICE_ID = 1 // Only has to be unique within this app.

        var braveScreenStateReceiver = BraveScreenStateReceiver()
        var braveAutoStartReceiver = BraveAutoStartReceiver()
        private const val MAIN_CHANNEL_ID = "vpn"
        private const val WARNING_CHANNEL_ID = "warning"
        val vpnController: VpnController? = VpnController.getInstance()

        var isScreenLockedEnabled: Boolean = false
        var isBackgroundEnabled: Boolean = false
        var blockUnknownConnection : Boolean = false

        var appWhiteList: MutableMap<Int, Boolean> = HashMap()
        var privateDNSOverride: Boolean = false
        var blockUDPTraffic : Boolean = false

        fun setPrivateDNSOverrideFromPref(context: Context) {
            privateDNSOverride = PersistentState.getAllowPrivateDNS(context)
        }

        const val ROOT_IP = "0.0.0.0"
    }

    //@GuardedBy("vpnController")
    //private var url : String ?= null
    private var networkConnected = false

    private lateinit var connTracer: ConnectionTracer

    enum class State {
        NEW, WORKING, FAILING
    }

    enum class BlockedRuleNames(val ruleName: String) {
        RULE1("Rule #1"), RULE2("Rule #2"), RULE3("Rule #3"), RULE4("Rule #4"), RULE5("Rule #5"), RULE6("Rule #6"),RULE7("Whitelist")
    }

    override fun block(protocol: Int, uid: Long, sourceAddress: String, destAddress: String): Boolean {
        var isBlocked = false
        val first = sourceAddress.split(":")
        val second = destAddress.split(":")

        isBlocked = checkConnectionBlocked(uid.toInt(), protocol, first[0], first[first.size - 1].toInt(), second[0], second[second.size - 1].toInt())
        return isBlocked
    }

    /**
     * Check if the connection is blocked by any of the rules
     * RULE1 - Universal firewall - Block connections when screen is off
     * RULE2 - Universal firewall - Block connections when apps are in background
     * RULE3 - Firewall App
     * RULE4 - Particular IP blocked with this App. //Not implemented completely.
     * RULE5 - IP blocked from connecting to Internet
     */
    private fun checkConnectionBlocked(_uid: Int, protocol: Int, sourceIp: String, sourcePort: Int, destIp: String, destPort: Int): Boolean {
        val ipDetails: IPDetails?
        var isBlocked = false
        var uid = _uid
        try {
            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                uid = connTracer.getUidQ(protocol, sourceIp, sourcePort, destIp, destPort)
            }

            //Check for the Private DNS override
            /*if (DEBUG) Log.d(LOG_TAG, "privateDNSOverride value: $privateDNSOverride, $uid, $destPort, $sourcePort")
            if (privateDNSOverride) {
                if (DEBUG) Log.d(LOG_TAG, "privateDNSOverride: $uid, $destPort")
                if (uid == FileSystemUID.ANDROID.uid && destPort == PRIVATE_DNS_PORT) {
                    if (DEBUG) Log.d(LOG_TAG, "privateDNSOverride: $uid, $destPort")
                    ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), true, BlockedRuleNames.RULE6.ruleName, protocol)
                    sendConnTracking(ipDetails)
                    return true
                }
            }*/
            //Cif(DEBUG) Log.i(LOG_TAG, "Thread: ${Thread.currentThread().id}, name: ${Thread.currentThread().name}, uid: $uid, ip: $sourceIp, $destIp, port: $sourcePort, $destPort, $protocol")

            if (appWhiteList.containsKey(uid)) {
                if ((destPort != DNS_REQUEST_PORT && destIp != GoVpnAdapter.FAKE_DNS_IP)) {
                    ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), false, BlockedRuleNames.RULE7.ruleName, protocol)
                    sendConnTracking(ipDetails)
                }
                if (DEBUG) Log.d(LOG_TAG, "appWhiteList: $uid, $destIp")
                return false
            }

            if (PersistentState.getBlockUnknownConnections(this)) {
                if (uid == -1) {
                    ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), true, BlockedRuleNames.RULE5.ruleName, protocol)
                    sendConnTracking(ipDetails)
                    return true
                }
            }

            if (protocol == 17 && blockUDPTraffic && destPort != DNS_REQUEST_PORT) {
                if (DEBUG) Log.d(LOG_TAG, "blockUDPTraffic: $uid, $destPort")
                ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), true, BlockedRuleNames.RULE6.ruleName, protocol)
                sendConnTracking(ipDetails)
                return true
            }



            //Check whether the screen lock is enabled and act based on it
            if (isScreenLockedEnabled) {
                if (DEBUG) Log.d(LOG_TAG, "isScreenLocked: $uid, $destPort")
                //Don't include DNS and private DNS request in the list
                if ((uid != 0 || uid != -1) && FileSystemUID.isUIDAppRange(uid)) {
                    if ((destPort != DNS_REQUEST_PORT && destIp != GoVpnAdapter.FAKE_DNS_IP)) {
                        ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), true, BlockedRuleNames.RULE3.ruleName, protocol)
                        sendConnTracking(ipDetails)
                    }
                    return true
                }
            } else if (isBackgroundEnabled) { //Check whether the background is enabled and act based on it
                if ((uid != 0 || uid != -1) && FileSystemUID.isUIDAppRange(uid)) {
                    //if (backgroundAllowedUID.isNotEmpty()) {
                        if (!backgroundAllowedUID.containsKey(uid)) {
                            if ((destPort != DNS_REQUEST_PORT && destIp != GoVpnAdapter.FAKE_DNS_IP)) {
                                ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), true, BlockedRuleNames.RULE4.ruleName, protocol)
                                sendConnTracking(ipDetails)
                            }
                            if (DEBUG) Log.d(LOG_TAG, "AccessibilityEvent: Background blocked: $uid")
                            return true
                        }
                }
            }
            //Check whether any rules are set block the IP/App.
            var blockedBy = ""
            if (uid != 0 || uid != -1) {
                isBlocked = isUidBlocked(uid)
                if (isBlocked) {
                    blockedBy = BlockedRuleNames.RULE1.ruleName
                    if(PersistentState.getKillAppOnFirewall(this)){
                        killFirewalledApplication(uid)
                    }
                }
            } else {
                isBlocked = false
            }

            if (!isBlocked) {
                val connectionRules = ConnectionRules(destIp, destPort, Protocol.getProtocolName(protocol).name)
                isBlocked = firewallRules.checkRules(UNIVERSAL_RULES_UID, connectionRules)
                if (isBlocked)
                    blockedBy = BlockedRuleNames.RULE2.ruleName
            }
            if ((destPort != DNS_REQUEST_PORT && destIp != GoVpnAdapter.FAKE_DNS_IP)) {
                ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), isBlocked, blockedBy, protocol)
                sendConnTracking(ipDetails)
            }
        } catch (iex: Exception) {
            Log.e(LOG_TAG, iex.message, iex)
        }
        return isBlocked
    }

    private fun killFirewalledApplication(uid: Int) {
        Log.i(LOG_TAG, "Firewalled application trying to connect - Kill app is enabled - uid - $uid")
        val activityManager: ActivityManager = this.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        val mDb = AppDatabase.invoke(this.applicationContext)
        val appInfoRepository = mDb.appInfoRepository()
        GlobalScope.launch(Dispatchers.IO) {
            val appUIDList = appInfoRepository.getAppListForUID(uid)
            appUIDList.forEach {
                if(!it.isSystemApp) {
                    activityManager.killBackgroundProcesses(it.packageInfo)
                    Log.i(LOG_TAG, "Application killed - $uid, ${it.packageInfo}")
                }else{
                    Log.i(LOG_TAG, "System application is refrained from kill - $uid, ${it.packageInfo}")
                }
            }
        }
    }

    /**
     * Records the network transaction in local database
     * The logs will be shown in network monitor screen
     */
    private fun sendConnTracking(ipDetails: IPDetails) {
        getIPTracker()!!.recordTransaction(this, ipDetails)
    }

    /**
     * Checks whether the connection is added in firewall rules.
     */
    private fun isUidBlocked(uid: Int): Boolean {
        return try {
            FirewallManager.checkInternetPermission(uid)
        } catch (e: Exception) {
            false
        }
    }

    override fun getResolvers(): String {
        val ips: MutableList<String?> = ArrayList()
        val ipsList = networkManager!!.getSystemResolvers()
        if (ipsList != null) {
            for (ip in ipsList) {
                val address = ip.hostAddress
                if (GoVpnAdapter.FAKE_DNS_IP != address) {
                    ips.add(address)
                }
            }
        }
        return TextUtils.join(",", ips)
    }

    fun isOn(): Boolean {
        return vpnAdapter != null
    }

    fun newBuilder(): Builder? {
        var builder = Builder()
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {

            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                if(DEBUG) Log.d(LOG_TAG, "isLockDownEnabled - ${this.isLockdownEnabled}, ${this.isAlwaysOn}")
                val alwaysOn = android.provider.Settings.Secure.getString(this.contentResolver, "always_on_vpn_app")
                val lockDown = android.provider.Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0)
                if(DEBUG) Log.d(LOG_TAG, "isLockDownEnabled - $lockDown , $alwaysOn")
                if (TextUtils.isEmpty(alwaysOn) && lockDown == 0) {
                    if (PersistentState.getAllowByPass(this)) {
                        Log.d(LOG_TAG, "getAllowByPass - true")
                        builder = builder.allowBypass()
                    }
                }
            }

            //Fix - Cloud Backups were failing thinking that the VPN connection is metered.
            //The below code will fix that.
            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                if (PersistentState.getHttpProxyEnabled(this)) {
                    val host = PersistentState.getHttpProxyHostAddress(this)
                    val port = PersistentState.getHttpProxyPort(this)
                    val proxyInfo: ProxyInfo = ProxyInfo.buildDirectProxy(host!!, port!!)
                    builder.setHttpProxy(proxyInfo)
                    Log.i(LOG_TAG,"Http proxy enabled - builder updated with $host, $port")
                }
            }

            try {
                // Workaround for any app incompatibility bugs.
                //TODO : As of now the wifi  packages are considered for blocking the entire traffic
                if (appMode?.getFirewallMode() == Settings.BlockModeSink) {
                    if (PersistentState.getExcludedPackagesWifi(this)!!.isEmpty()) {
                        builder.addAllowedApplication("")
                    } else {
                        for (packageName in PersistentState.getExcludedPackagesWifi(this)!!) {
                            builder = builder.addAllowedApplication(packageName!!)
                        }
                    }
                }else{
                    val excludedApps = PersistentState.getExcludedAppsFromVPN(this)
                    excludedApps?.forEach {
                        builder = builder.addDisallowedApplication(it)
                        Log.d(LOG_TAG, "Excluded package - $it")
                    }
                    //builder = builder.addDisallowedApplication("com.android.vending")
                    builder = builder.addDisallowedApplication(this.packageName)
                }
                Log.i(LOG_TAG, "Proxy mode set to Socks5 ${appMode?.getProxyMode()}")
                if(appMode?.getProxyMode() == Settings.ProxyModeSOCKS5){
                    //For Socks5 if there is a app selected, add that app in excluded list

                    val mDb = AppDatabase.invoke(this.applicationContext)
                    val socks5ProxyEndpointRepository = mDb.proxyEndpointRepository()
                    val socks5ProxyEndpoint = socks5ProxyEndpointRepository.getConnectedProxy()
                    if (socks5ProxyEndpoint != null) {
                        if(!socks5ProxyEndpoint.proxyAppName.isNullOrEmpty()  && socks5ProxyEndpoint.proxyAppName != "Nobody"){
                            Log.i(LOG_TAG, "Proxy mode set to Socks5 with the app - ${socks5ProxyEndpoint.proxyAppName!!}- added to excluded list")
                            builder = builder.addDisallowedApplication(socks5ProxyEndpoint.proxyAppName!!)
                        }
                    }else{
                        Log.i(LOG_TAG, "Proxy mode not set to Socks5 with the app - null - added to excluded list")
                    }
                }

                if(appMode?.getDNSType() == 3){

                    //For DNS proxy mode, if any app is set then exclude the application from the list
                    val mDb = AppDatabase.invoke(this.applicationContext)
                    val dnsProxyEndpointRepository = mDb.dnsProxyEndpointRepository()
                    val dnsProxyEndpoint = dnsProxyEndpointRepository.getConnectedProxy()
                    if(!dnsProxyEndpoint.proxyAppName.isNullOrEmpty() && dnsProxyEndpoint.proxyAppName != "Nobody"){
                        Log.i(LOG_TAG, "DNS Proxy mode is set with the app - ${dnsProxyEndpoint.proxyAppName!!}- added to excluded list")
                        builder = builder.addDisallowedApplication(dnsProxyEndpoint.proxyAppName!!)
                    }else{
                        Log.i(LOG_TAG, "DNS Proxy mode is set with the app - ${dnsProxyEndpoint.proxyAppName!!}- added to excluded list")
                    }
                    //mDb.close()
                }

            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(LOG_TAG, "Failed to exclude an app", e)
            }
        }
        return builder
    }

    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        vpnController!!.setBraveVpnService(this)
        setPrivateDNSOverrideFromPref(this)
    }

    private fun registerReceiversForScreenState() {
        val actionFilter = IntentFilter()
        actionFilter.addAction(Intent.ACTION_SCREEN_OFF)
        actionFilter.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(braveScreenStateReceiver, actionFilter)
        val autoStartFilter = IntentFilter()
        autoStartFilter.addAction(Intent.ACTION_BOOT_COMPLETED)
        registerReceiver(braveAutoStartReceiver, autoStartFilter)
    }


    private fun updateBuilder(context: Context): Notification.Builder {
        val mainActivityIntent = PendingIntent.getActivity(
            context, 0, Intent(context, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )
        var builder: Notification.Builder
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            val name: CharSequence = context.resources.getString(R.string.app_name_brave)
            val description = context.resources.getString(R.string.notification_content)
            // LOW is the lowest importance that is allowed with startForeground in Android O.
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance)
            channel.description = description
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            builder = Notification.Builder(context, MAIN_CHANNEL_ID)
        } else {
            builder = Notification.Builder(context)
            if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
                // Min-priority notifications don't show an icon in the notification bar, reducing clutter.
                // Only available in API >= 16.  Deprecated in API 26.
                builder = builder.setPriority(Notification.PRIORITY_MIN)
            }
        }

        var contentTitle: String = if (braveMode == 0)
            context.resources.getString(R.string.dns_mode_notification_title)
        else if (braveMode == 1)
            context.resources.getString(R.string.firewall_mode_notification_title)
        else if (braveMode == 2)
            context.resources.getString(R.string.hybrid_mode_notification_title)
        else
            context.resources.getString(R.string.notification_title)

        builder.setSmallIcon(R.drawable.dns_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(mainActivityIntent)

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(Notification.VISIBILITY_SECRET)
        builder.build()
        return builder
    }

    @InternalCoroutinesApi
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val mDb = AppDatabase.invoke(this.applicationContext)
        val appInfoRepository = mDb.appInfoRepository()
        appInfoRepository.getUIDForUnivWhiteList().observeForever(androidx.lifecycle.Observer {
            appWhiteList = it.associateBy({ it }, { true }).toMutableMap()
        })
        blockUDPTraffic = PersistentState.getUDPBlockedSettings(this)
        privateDNSOverride  = PersistentState.getAllowPrivateDNS(this)
        isScreenLockedEnabled = PersistentState.getScreenLockData(this)
        isBackgroundEnabled = PersistentState.getBackgroundEnabled(this) && Utilities.isAccessibilityServiceEnabled(this, BackgroundAccessibilityService::class.java)
        privateDNSOverride = PersistentState.getAllowPrivateDNS(this)
        registerReceiversForScreenState()
        if (vpnController != null) {
            synchronized(vpnController) {

                PersistentState.getUserPreferences(this)
                    .registerOnSharedPreferenceChangeListener(this)

                if (networkManager != null) {
                    spawnServerUpdate()
                    return Service.START_REDELIVER_INTENT
                }

                // If we're online, |networkManager| immediately calls this.onNetworkConnected(), which in turn
                // calls startVpn() to actually start.  If we're offline, the startup actions will be delayed
                // until we come online.

                // If we're online, |networkManager| immediately calls this.onNetworkConnected(), which in turn
                // calls startVpn() to actually start.  If we're offline, the startup actions will be delayed
                // until we come online.
                networkManager = NetworkManager(this, this)

                // Mark this as a foreground service.  This is normally done to ensure that the service
                // survives under memory pressure.  Since this is a VPN service, it is presumably protected
                // anyway, but the foreground service mechanism allows us to set a persistent notification,
                // which helps users understand what's going on, and return to the app if they want.

                // Mark this as a foreground service.  This is normally done to ensure that the service
                // survives under memory pressure.  Since this is a VPN service, it is presumably protected
                // anyway, but the foreground service mechanism allows us to set a persistent notification,
                // which helps users understand what's going on, and return to the app if they want.

                val builder = updateBuilder(this)
                Log.i(LOG_TAG,"onStart command - start as foreground service. ")
                startForeground(SERVICE_ID, builder.notification)
                updateQuickSettingsTile()
            }
        }else{
            Log.i(LOG_TAG,"onStart command not initiated - vpnController is null ")
        }
        return Service.START_REDELIVER_INTENT
    }

    @InternalCoroutinesApi
    private fun spawnServerUpdate() {
        if (vpnController != null) {
            synchronized(vpnController) {
                if (networkManager != null) {
                    Thread(
                        Runnable { updateServerConnection() }, "updateServerConnection-onStartCommand"
                    ).start()
                }
            }
        }
    }

    @WorkerThread
    private fun updateServerConnection() {
        if (appMode == null) {
            appMode = AppMode.getInstance(this)
        }
        vpnAdapter?.updateDohUrl(appMode?.getDNSMode(), appMode?.getFirewallMode(), appMode?.getProxyMode())
    }

    fun recordTransaction(transaction: Transaction) {
        transaction.responseTime = SystemClock.elapsedRealtime()
        transaction.responseCalendar = Calendar.getInstance()
        getTracker()!!.recordTransaction(this, transaction)
        val intent = Intent(InternalNames.RESULT.name)
        intent.putExtra(InternalNames.TRANSACTION.name, transaction)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        if (!networkConnected) {
            // No need to update the user-visible connection state while there is no network.
            return
        }

        // Update the connection state.  If the transaction succeeded, then the connection is working.
        // If the transaction failed, then the connection is not working.
        // If the transaction was canceled, then we don't have any new information about the status
        // of the connection, so we don't send an update.
        if (transaction.status === Transaction.Status.COMPLETE) {
            vpnController!!.onConnectionStateChanged(
                this,
                State.WORKING
            )
        } else if (transaction.status !== Transaction.Status.CANCELED) {
            vpnController!!.onConnectionStateChanged(
                this,
                State.FAILING
            )
        }
    }

    private fun getTracker(): QueryTracker? {
        return vpnController!!.getTracker(this)
    }

    private fun getIPTracker(): IPTracker? {
        return vpnController!!.getIPTracker(this)
    }

    private fun setCryptMode() {
        vpnAdapter!!.setCryptMode()
    }

    @InternalCoroutinesApi
    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        /*TODO Check on the Persistent State variable
            Check on updating the values for Package change and for mode change.
           As of now handled manually*/

        if ((PersistentState.BRAVE_MODE == key) && vpnAdapter != null) {
            ///firewallMode = PersistentState.getFirewallMode(context)
           /* if (braveMode == HomeScreenFragment.DNS_MODE) {

                vpnAdapter!!.setDNSTunnelMode(appMode?.getDNSMode())
            } else
                vpnAdapter!!.setFilterTunnelMode()*/
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val builder = updateBuilder(this)
            notificationManager.notify(SERVICE_ID, builder.build())
        }

        /*if (PersistentState.URL_KEY == key) {
            spawnServerUpdate()
        }*/

        if (PersistentState.DNS_TYPE == key) {
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            Log.d(LOG_TAG, "DNSType- ${appMode?.getDNSType()}")

            /**
             * The code has been modified to restart the VPN service when there is a
             * change in the DNS Type. Earlier the restart of the VPN is performed
             * when the mode is changed to DNS Proxy, now the code is modified in
             * such a way that, the restart is performed when there mode is Crypt/Proxy
             */

            if (appMode?.getDNSType() == 3) {
                restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
            }else {
                if(appMode?.getDNSType() == 2){
                    setCryptMode()
                }
                spawnServerUpdate()
            }
        }

        if(PersistentState.PROXY_MODE == key){
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            Log.d(LOG_TAG, "PROXY_MODE- ${appMode?.getProxyMode()}")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if (PersistentState.CONNECTION_CHANGE == key) {
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            Log.d(LOG_TAG, "CONNECTION_CHANGE- ${appMode?.getDNSMode()}")
            spawnServerUpdate()
        }

        if(PersistentState.DNS_PROXY_ID == key){
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            Log.d(LOG_TAG, "DNS PROXY CHANGE- ${appMode?.getDNSMode()!!}")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if(PersistentState.EXCLUDE_FROM_VPN == key){
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            Log.d(LOG_TAG, "EXCLUDE_FROM_VPN - restartVpn- ${appMode?.getDNSMode()}")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if (PersistentState.IS_SCREEN_OFF == key) {
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            isScreenLockedEnabled = PersistentState.getScreenLockData(this)
            Log.i(LOG_TAG, "preference for screen off mode is modified - $isScreenLockedEnabled")
        }

        if (PersistentState.BACKGROUND_MODE == key) {
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            isBackgroundEnabled = PersistentState.getBackgroundEnabled(this) && Utilities.isAccessibilityServiceEnabled(this, BackgroundAccessibilityService::class.java)
            Log.i(LOG_TAG, "preference for background mode is modified - $isBackgroundEnabled")
            if(isBackgroundEnabled) {
                restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
            }
        }
        if(PersistentState.BLOCK_UNKNOWN_CONNECTIONS == key){
            Log.i(LOG_TAG, "preference for block unknown connections is modified")
            blockUnknownConnection = PersistentState.getBlockUnknownConnections(this)
        }

        if (PersistentState.LOCAL_BLOCK_LIST == key) {
            Log.i(LOG_TAG, "preference for local block list is changed - restart vpn")
            spawnServerUpdate()
        }

        if(PersistentState.LOCAL_BLOCK_LIST_STAMP == key){
            Log.i(LOG_TAG, "configuration stamp for local block list is changed- restart vpn")
            spawnServerUpdate()
        }

        if(PersistentState.ALLOW_BYPASS == key){
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            Log.i(LOG_TAG, "preference for allow by pass is changed - restart vpn")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if(PersistentState.PRIVATE_DNS == key){
            privateDNSOverride = PersistentState.getAllowPrivateDNS(this)
        }

        if(PersistentState.HTTP_PROXY_ENABLED == key){
            if (appMode == null) {
                appMode = AppMode.getInstance(this)
            }
            Log.i(LOG_TAG, "preference for http proxy is changed - restart vpn")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if(PersistentState.BLOCK_UDP_OTHER_THAN_DNS == key){
            blockUDPTraffic = PersistentState.getUDPBlockedSettings(this)
        }

    }

    fun signalStopService(userInitiated: Boolean) {
        if (!userInitiated) {
            val vibrationPattern = longArrayOf(1000) // Vibrate for one second.
            // Show revocation warning
            var builder: Notification.Builder
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                val name: CharSequence = getString(R.string.warning_channel_name)
                val description = getString(R.string.warning_channel_description)
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(WARNING_CHANNEL_ID, name, importance)
                channel.description = description
                channel.enableVibration(true)
                channel.vibrationPattern = vibrationPattern
                notificationManager.createNotificationChannel(channel)
                builder = Notification.Builder(this, WARNING_CHANNEL_ID)
            } else {
                builder = Notification.Builder(this)
                builder.setVibrate(vibrationPattern)
                if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
                    // Only available in API >= 16.  Deprecated in API 26.
                    builder = builder.setPriority(Notification.PRIORITY_MAX)
                }
            }
            val mainActivityIntent = PendingIntent.getActivity(
                this, 0, Intent(this, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setSmallIcon(R.drawable.dns_icon)
                .setContentTitle(resources.getText(R.string.warning_title))
                .setContentText(resources.getText(R.string.notification_content))
                .setContentIntent(mainActivityIntent) // Open the main UI if possible.
                .setAutoCancel(true)
            if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_ERROR)
            }
            notificationManager.notify(0, builder.notification)
        }
        stopVpnAdapter()
        stopSelf()

        Log.d(LOG_TAG,"Stop Foreground")
        updateQuickSettingsTile()
    }

    private fun updateQuickSettingsTile() {
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            requestListeningState(this, ComponentName(this, BraveTileService::class.java))
        }
    }

    private fun stopVpnAdapter() {
        if (vpnController != null) {
            kotlin.synchronized(vpnController) {
                if (vpnAdapter != null) {
                    vpnAdapter!!.close()
                    vpnAdapter = null
                    vpnController.stop(this)
                    vpnController.getState(this)!!.activationRequested = false
                    vpnController.onConnectionStateChanged(this, null)
                    Log.e(LOG_TAG, "Stop Called - stopVpnAdapter closed all states")
                }
            }
        } else {
            Log.e(LOG_TAG, "Stop Called with VPN Controller as null")
        }
    }

    @InternalCoroutinesApi
    private fun restartVpn(dnsModeL: Long, firewallModeL: Long, proxyMode: Long) {
        if (vpnController != null) {
            synchronized(vpnController) {
                Thread(
                    Runnable {
                        //updateServerConnection()
                        // Attempt seamless handoff as described in the docs for VpnService.Builder.establish().
                        val oldAdapter: GoVpnAdapter? = vpnAdapter
                        vpnAdapter = makeVpnAdapter()
                        oldAdapter?.close()
                        if (vpnAdapter != null) {
                            vpnAdapter!!.start(dnsModeL, firewallModeL, proxyMode)
                        } else {
                            Log.i(LOG_TAG, String.format("Restart failed"))
                        }
                    }, "restartvpn-onCommand"
                ).start()
            }
        } else {
            Log.i("VpnService", String.format("vpnController is null"))
        }
    }

    private fun makeVpnAdapter(): GoVpnAdapter? {
        return GoVpnAdapter.establish(this)
    }

    override fun onNetworkConnected(networkInfo: NetworkInfo?) {
        setNetworkConnected(true)

        // This code is used to start the VPN for the first time, but startVpn is idempotent, so we can
        // call it every time. startVpn performs network activity so it has to run on a separate thread.
        Thread(
            object : Runnable {
                override fun run() {
                    //TODO Work on the order of the function call.
                    updateServerConnection()
                    startVpn()
                }
            }, "startVpn-onNetworkConnected"
        ).start()
    }

    override fun onNetworkDisconnected() {
        setNetworkConnected(false)
        vpnController!!.onConnectionStateChanged(this, null)
    }

    private fun setNetworkConnected(connected: Boolean) {
        networkConnected = connected
        if (Build.VERSION.SDK_INT >= VERSION_CODES.M) {
            // Indicate that traffic will be sent over the current active network.
            // See e.g. https://issuetracker.google.com/issues/68657525
            val activeNetwork =
                getSystemService(ConnectivityManager::class.java).activeNetwork
            setUnderlyingNetworks(if (connected) arrayOf(activeNetwork) else null)
        }
    }

    @WorkerThread
    private fun startVpn() {
        kotlin.synchronized(vpnController!!) {
            /*if (vpnAdapter != null) {
                return
            }*/
            startVpnAdapter()
            vpnController.onStartComplete(this, vpnAdapter != null)
            if (vpnAdapter == null) {
                Log.d(LOG_TAG, "Failed to startVpn VPN adapter")
                stopSelf()
                /*stopForeground(true)
                onDestroy()*/
            }
        }
    }

    private fun startVpnAdapter() {
        kotlin.synchronized(vpnController!!) {
            if (vpnAdapter == null) {
                vpnAdapter = makeVpnAdapter()
                if (vpnAdapter != null) {
                    vpnAdapter!!.start(appMode?.getDNSMode(), appMode?.getFirewallMode(), appMode?.getProxyMode())
                } else {
                    Log.w(LOG_TAG, "Failed to start VPN adapter!")
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(braveScreenStateReceiver)
            unregisterReceiver(braveAutoStartReceiver)
        }catch (e : java.lang.Exception){
            Log.e(LOG_TAG,"Unregister receiver error: ${e.message}",e)
        }
        kotlin.synchronized(vpnController!!) {
            Log.w(LOG_TAG, "Destroying DNS VPN service")
            PersistentState.getUserPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this)
            if (networkManager != null) {
                networkManager!!.destroy()
            }
            vpnController.setBraveVpnService(null)
            stopForeground(true)
            if (vpnAdapter != null) {
                signalStopService(false)
            }
        }
    }

    override fun onRevoke() {
        stopSelf()
        // Disable autostart if VPN permission is revoked.
        PersistentState.setVpnEnabled(this, false)

        /*stopForeground(true)
        onDestroy()*/
    }

}