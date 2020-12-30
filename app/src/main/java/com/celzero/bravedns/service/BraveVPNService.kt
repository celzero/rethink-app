/*
 * Copyright 2019 Jigsaw Operations LLC
 * Copyright 2020 RethinkDNS and its authors
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

import android.app.*
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.service.quicksettings.TileService.requestListeningState
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.*
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.BraveAutoStartReceiver
import com.celzero.bravedns.receiver.BraveScreenStateReceiver
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment.Companion.UNIVERSAL_RULES_UID
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.backgroundAllowedUID
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.BACKGROUND_DELAY_CHECK_REMAINING
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Constants.Companion.MISSING_UID
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import protect.Blocker
import protect.Protector
import settings.Settings
import java.util.*
import org.koin.android.ext.android.get
import kotlin.collections.HashMap


class BraveVPNService : VpnService(), NetworkManager.NetworkListener, Protector, Blocker,
    OnSharedPreferenceChangeListener {

    @GuardedBy("vpnController") private var networkManager: NetworkManager? = null


    @GuardedBy("vpnController") private var vpnAdapter: GoVpnAdapter? = null

    private val DNS_REQUEST_PORT = 53
    private val PRIVATE_DNS_PORT = 853

    companion object {

        var firewallRules = FirewallRules.getInstance()
        private const val FILE_LOG_TAG = "RethinkVPNService"
        const val SERVICE_ID = 1 // Only has to be unique within this app.

        var braveScreenStateReceiver = BraveScreenStateReceiver()
        var braveAutoStartReceiver = BraveAutoStartReceiver()
        private const val MAIN_CHANNEL_ID = "vpn"
        private const val WARNING_CHANNEL_ID = "warning"
        val vpnController: VpnController? = VpnController.getInstance()

        var isScreenLocked: Boolean = false
        var isBackgroundEnabled: Boolean = false
        var blockUnknownConnection: Boolean = false

        var appWhiteList: MutableMap<Int, Boolean> = HashMap()
        var privateDNSOverride: Boolean = false
        var blockUDPTraffic: Boolean = false

    }

    private var networkConnected = false

    private lateinit var connTracer: ConnectionTracer

    private val appInfoRepository by inject<AppInfoRepository>()
    private val socks5ProxyEndpointRepository by inject<ProxyEndpointRepository>()
    private val dnsProxyEndpointRepository by inject<DNSProxyEndpointRepository>()
    private val appMode by inject<AppMode>()
    private val ipTracker by inject<IPTracker>()
    private val dnsLogTracker by inject<DNSLogTracker>()
    private val persistentState by inject<PersistentState>()
    private val tracker by inject<QueryTracker>()

    enum class State {
        NEW, WORKING, FAILING
    }

    enum class BlockedRuleNames(val ruleName: String) {
        RULE1("Rule #1"), RULE2("Rule #2"), RULE3("Rule #3"), RULE4("Rule #4"),
         RULE5("Rule #5"), RULE6("Rule #6"), RULE7("Whitelist")
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
     * RULE1 - Firewall App
     * RULE2 - IP blocked from connecting to Internet
     * RULE3 - Universal firewall - Block connections when screen is off
     * RULE4 - Universal firewall - Block connections when apps are in background
     * RULE5 - Universal firewall - Block when source app is unknown
     * RULE6 - Universal firewall - Block all UDP connections
     */
    private fun checkConnectionBlocked(_uid: Int, protocol: Int, sourceIp: String, sourcePort: Int, destIp: String, destPort: Int): Boolean {
        val ipDetails: IPDetails?
        var isBlocked = false
        var uid = _uid
        try {
            if ((destIp == GoVpnAdapter.FAKE_DNS_IP && destPort == DNS_REQUEST_PORT)) {
                return false
            }

            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                uid = connTracer.getUidQ(protocol, sourceIp, sourcePort, destIp, destPort)
            }

            ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), false, "", protocol)

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
            }

            if(DEBUG) Log.i(LOG_TAG,
                    "Thread: ${Thread.currentThread().id}, name: ${Thread.currentThread().name},
                    uid: $uid, ip: $sourceIp, $destIp, port: $sourcePort, $destPort, $protocol")
            */


            if (appWhiteList.containsKey(uid)) {
                if ((destIp != GoVpnAdapter.FAKE_DNS_IP && destPort != DNS_REQUEST_PORT)) {
                    ipDetails.isBlocked = false
                    ipDetails.blockedByRule = BlockedRuleNames.RULE7.ruleName
                    sendConnTracking(ipDetails)
                }
                Log.i(LOG_TAG, "$FILE_LOG_TAG appWhiteList: $uid, $destIp")
                return false
            }

            if (persistentState.blockUnknownConnections && destIp != GoVpnAdapter.FAKE_DNS_IP) {
                if (uid == -1 || uid == MISSING_UID)  {
                    if ((destIp != GoVpnAdapter.FAKE_DNS_IP && destPort != DNS_REQUEST_PORT)) {
                        ipDetails.isBlocked = true
                        ipDetails.blockedByRule = BlockedRuleNames.RULE5.ruleName
                        sendConnTracking(ipDetails)
                    }
                    delayBeforeResponse()
                    return true
                }
            }

            if (protocol == 17 && blockUDPTraffic  && destIp != GoVpnAdapter.FAKE_DNS_IP && destPort != DNS_REQUEST_PORT) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = BlockedRuleNames.RULE6.ruleName
                sendConnTracking(ipDetails)
                delayBeforeResponse()
                if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG blockUDPTraffic: $uid, $destPort")
                return true
            }

            //Check whether the screen lock is enabled and act based on it
            if(DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG : isScreenLockEnabled: $isScreenLocked")
            if (isScreenLocked) {
                //Don't include DNS and private DNS request in the list
                // FIXME: 04-12-2020 Removed the app range check for testing.
                //if ((uid != 0 || uid != -1) && FileSystemUID.isUIDAppRange(uid)) {
                if ((uid != -1 && uid != MISSING_UID)) {
                    if ((destIp != GoVpnAdapter.FAKE_DNS_IP && destPort != DNS_REQUEST_PORT)) {
                        ipDetails.isBlocked = true
                        ipDetails.blockedByRule = BlockedRuleNames.RULE3.ruleName
                        sendConnTracking(ipDetails)
                    }
                    if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isScreenLocked: $uid, $destPort")
                    delayBeforeResponse()
                    return true
                }

            } else if (isBackgroundEnabled) { //Check whether the background is enabled and act based on it
                // FIXME: 04-12-2020 Removed the app range check for testing.
                //if ((uid != 0 || uid != -1) && FileSystemUID.isUIDAppRange(uid)) {
                if ((uid != -1 && uid != MISSING_UID)) {
                    var isBGBlock = true
                    if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid, $destIp, before sleep: $uid, $destIp, $isBGBlock, ${System.currentTimeMillis()}")
                    for (i in 3 downTo 1) {
                        if (backgroundAllowedUID.containsKey(uid)) {
                            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background not blocked $uid, $destIp, AccessibilityEvent: app in foreground: $uid")
                            isBGBlock = false
                            break
                        } else {
                            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid, $destIp, AccessibilityEvent: UID: $uid is not available in the foreground, trying again")
                        }
                        Thread.sleep(Constants.BACKGROUND_DELAY_CHECK)
                    }
                    if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid, $destIp, after sleep: $uid, $destIp, $isBGBlock, ${System.currentTimeMillis()}")
                    if (isBGBlock) {
                        Thread.sleep(BACKGROUND_DELAY_CHECK_REMAINING)
                        if (!backgroundAllowedUID.containsKey(uid)) {
                            if ((destIp != GoVpnAdapter.FAKE_DNS_IP && destPort != DNS_REQUEST_PORT)) {
                                ipDetails.isBlocked = true
                                ipDetails.blockedByRule = BlockedRuleNames.RULE4.ruleName
                                sendConnTracking(ipDetails)
                            }
                            Log.i(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid, $destIp, after sleep of:  ${System.currentTimeMillis()}")
                            return isBGBlock
                        }
                    }
                }
            }

            //Check whether any rules are set block the IP/App.
            var blockedBy = ""
            if (uid != -1 && uid != MISSING_UID) {
                isBlocked = isUidBlocked(uid)
                if (isBlocked) {
                    blockedBy = BlockedRuleNames.RULE1.ruleName
                    if (persistentState.killAppOnFirewall) {
                        killFirewalledApplication(uid)
                    }
                }
            } else {
                isBlocked = false
            }

            if (!isBlocked) {
                val connectionRules = ConnectionRules(destIp, destPort, Protocol.getProtocolName(protocol).name)
                isBlocked = firewallRules.checkRules(UNIVERSAL_RULES_UID, connectionRules)
                if (isBlocked) blockedBy = BlockedRuleNames.RULE2.ruleName
            }
            if ((destPort != DNS_REQUEST_PORT && destIp != GoVpnAdapter.FAKE_DNS_IP)) {
                ipDetails.isBlocked = isBlocked
                ipDetails.blockedByRule = blockedBy
                sendConnTracking(ipDetails)
            }
        } catch (iex: Exception) {
            Log.e(LOG_TAG, iex.message, iex)
        }
        if (isBlocked) {
            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Response for the block: $destIp %Before% $uid, $isBlocked,$sourceIp, $sourcePort, $destIp, $destPort ")
            delayBeforeResponse()
        }
        if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Response for the block: $destIp  %After% $uid, $isBlocked,$sourceIp, $sourcePort, $destIp, $destPort")
        return isBlocked
    }

    private fun delayBeforeResponse() {
        Thread.sleep(Constants.DELAY_FOR_BLOCK_RESPONSE)
    }


    private fun killFirewalledApplication(uid: Int) {
        Log.i(LOG_TAG, "$FILE_LOG_TAG Firewalled application trying to connect - Kill app is enabled - uid - $uid")
        val activityManager: ActivityManager = this.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        GlobalScope.launch(Dispatchers.IO) {
            val appUIDList = appInfoRepository.getAppListForUID(uid)
            appUIDList.forEach {
                if (!it.isSystemApp) {
                    activityManager.killBackgroundProcesses(it.packageInfo)
                    Log.i(LOG_TAG, "$FILE_LOG_TAG Application killed - $uid, ${it.packageInfo}")
                } else {
                    Log.i(LOG_TAG, "$FILE_LOG_TAG System application is refrained from kill - $uid, ${it.packageInfo}")
                }
            }
        }
    }

    /**
     * Records the network transaction in local database
     * The logs will be shown in network monitor screen
     */
    private fun sendConnTracking(ipDetails: IPDetails?) {
        if(persistentState.logsEnabled) {
            ipTracker.recordTransaction(ipDetails)
        }
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

    fun newBuilder(): Builder {
        var builder = Builder()
        var alwaysOn : String ?= null
        var lockDown = -1

        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isLockDownEnabled - ${this.isLockdownEnabled}, ${this.isAlwaysOn}")
                alwaysOn = android.provider.Settings.Secure.getString(this.contentResolver, "always_on_vpn_app")
                lockDown = android.provider.Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0)
                if (DEBUG) Log.d(LOG_TAG, "isLockDownEnabled - $lockDown , $alwaysOn")
                if (alwaysOn.isNullOrEmpty() && lockDown == 0) {
                    if (persistentState.allowByPass) {
                        Log.d(LOG_TAG, "$FILE_LOG_TAG getAllowByPass - true")
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
                if (persistentState.httpProxyEnabled) {
                    val host = persistentState.httpProxyHostAddress
                    val port = persistentState.httpProxyPort
                    val proxyInfo: ProxyInfo = ProxyInfo.buildDirectProxy(host!!, port)
                    builder.setHttpProxy(proxyInfo)
                    Log.i(LOG_TAG, "$FILE_LOG_TAG Http proxy enabled - builder updated with $host, $port")
                }
            }

            try {
                // Workaround for any app incompatibility bugs.
                //TODO : As of now the wifi  packages are considered for blocking the entire traffic
                if (appMode?.getFirewallMode() == Settings.BlockModeSink) {
                    if (persistentState.excludedPackagesWifi.isEmpty()) {
                        builder.addAllowedApplication("")
                    } else {
                        for (packageName in persistentState.excludedPackagesWifi) {
                            builder = builder.addAllowedApplication(packageName!!)
                        }
                    }
                } else {
                    /* The check for the apps to exclude from VPN or not.
                       In case of lockdown mode, the excluded apps wont able to connected. so
                       not including the apps in the excluded list if the lockdown mode is
                       enabled. */
                    val excludedApps = persistentState.excludedAppsFromVPN
                    if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                        if (alwaysOn.isNullOrEmpty()  && lockDown == 0) {
                            excludedApps?.forEach {
                                builder = builder.addDisallowedApplication(it)
                                Log.i(LOG_TAG, "$FILE_LOG_TAG Excluded package - $it")
                            }
                        }else{
                            Log.i(LOG_TAG, "$FILE_LOG_TAG lockdown mode enabled, Ignoring apps to exclude")
                        }
                    }else{
                        excludedApps?.forEach {
                            builder = builder.addDisallowedApplication(it)
                            Log.i(LOG_TAG, "$FILE_LOG_TAG Excluded package - $it")
                        }
                    }
                    //builder = builder.addDisallowedApplication("com.android.vending")
                    builder = builder.addDisallowedApplication(this.packageName)
                }
                Log.i(LOG_TAG, "$FILE_LOG_TAG Proxy mode set to Socks5 ${appMode?.getProxyMode()}")
                if (appMode?.getProxyMode() == Settings.ProxyModeSOCKS5) {
                    //For Socks5 if there is a app selected, add that app in excluded list

                    val socks5ProxyEndpoint = socks5ProxyEndpointRepository.getConnectedProxy()
                    if (socks5ProxyEndpoint != null) {
                        if (!socks5ProxyEndpoint.proxyAppName.isNullOrEmpty() && socks5ProxyEndpoint.proxyAppName != "Nobody") {
                            Log.i(LOG_TAG, "$FILE_LOG_TAG Proxy mode set to Socks5 with the app - ${socks5ProxyEndpoint.proxyAppName!!}- added to excluded list")
                            builder = builder.addDisallowedApplication(socks5ProxyEndpoint.proxyAppName!!)
                        }
                    } else {
                        Log.i(LOG_TAG, "$FILE_LOG_TAG Proxy mode not set to Socks5 with the app - null - added to excluded list")
                    }
                }

                if (appMode?.getDNSType() == 3) {

                    //For DNS proxy mode, if any app is set then exclude the application from the list
                    val dnsProxyEndpoint = dnsProxyEndpointRepository.getConnectedProxy()
                    if (!dnsProxyEndpoint.proxyAppName.isNullOrEmpty() && dnsProxyEndpoint.proxyAppName != "Nobody") {
                        Log.i(LOG_TAG, "$FILE_LOG_TAG DNS Proxy mode is set with the app - ${dnsProxyEndpoint.proxyAppName!!}- added to excluded list")
                        builder = builder.addDisallowedApplication(dnsProxyEndpoint.proxyAppName!!)
                    } else {
                        Log.i(LOG_TAG, "$FILE_LOG_TAG DNS Proxy mode is set with the app - ${dnsProxyEndpoint.proxyAppName!!}- added to excluded list")
                    }
                    //mDb.close()
                }

            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(LOG_TAG, "$FILE_LOG_TAG Failed to exclude an app", e)
            }
        }
        return builder
    }


    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        vpnController!!.setBraveVpnService(this)
        setPrivateDNSOverrideFromPref(this)
    }

    fun setPrivateDNSOverrideFromPref(context: Context) {
        privateDNSOverride = persistentState.allowPrivateDNS
    }

    private fun registerReceiversForScreenState() {
        if(DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Registering for the intent filter for ACTION_SCREEN_OFF,ON and USER_PRESENT")
        val actionFilter = IntentFilter()
        actionFilter.addAction(Intent.ACTION_SCREEN_OFF)
        actionFilter.addAction(Intent.ACTION_USER_PRESENT)
        actionFilter.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(braveScreenStateReceiver, actionFilter)
    }

    private fun registerReceiverForBootComplete() {
        val autoStartFilter = IntentFilter()
        autoStartFilter.addAction(Intent.ACTION_BOOT_COMPLETED)
        registerReceiver(braveAutoStartReceiver, autoStartFilter)
    }


    private fun updateBuilder(context: Context): Notification.Builder {
        val mainActivityIntent = PendingIntent.getActivity(context, 0, Intent(context, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
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

        val contentTitle: String = if (braveMode == 0) context.resources.getString(R.string.dns_mode_notification_title)
        else if (braveMode == 1) context.resources.getString(R.string.firewall_mode_notification_title)
        else if (braveMode == 2) context.resources.getString(R.string.hybrid_mode_notification_title)
        else context.resources.getString(R.string.notification_title)

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(mainActivityIntent)

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(Notification.VISIBILITY_SECRET)
        builder.build()
        return builder
    }

    @InternalCoroutinesApi
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        appInfoRepository.getUIDForUnivWhiteList().observeForever({
            appWhiteList = it.associateBy({ it }, { true }).toMutableMap()
        })
        blockUDPTraffic = persistentState.udpBlockedSettings
        privateDNSOverride = persistentState.allowPrivateDNS
        isScreenLocked = persistentState.getScreenLockData()
        isBackgroundEnabled = persistentState.backgroundEnabled && Utilities.isAccessibilityServiceEnabledEnhanced(this, BackgroundAccessibilityService::class.java)
        privateDNSOverride = persistentState.allowPrivateDNS

        registerAccessibilityServiceState()
        registerReceiversForScreenState()
        registerReceiverForBootComplete()
        if (vpnController != null) {
            synchronized(vpnController) {

                if(DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Registering the shared pref changes with the vpn service")
                persistentState.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

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

                val applicationContext = this.applicationContext
                val connectivityManager = applicationContext!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.activeNetworkInfo?.takeIf { it.isConnected }?.also {
                    onNetworkConnected(it)
                }
                val builder = updateBuilder(this)
                Log.i(LOG_TAG, "$FILE_LOG_TAG onStart command - start as foreground service. ")
                startForeground(SERVICE_ID, builder.notification)
                updateQuickSettingsTile()
                persistentState.vpnEnabled = true
            }
        } else {
            Log.i(LOG_TAG, "$FILE_LOG_TAG onStart command not initiated - vpnController is null ")
        }
        return Service.START_REDELIVER_INTENT
    }

    private fun registerAccessibilityServiceState() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.addAccessibilityStateChangeListener { b ->
            val isServiceEnabled = Utilities.isAccessibilityServiceEnabledEnhanced(
                this,
                BackgroundAccessibilityService::class.java
            )
            if (!b || !isServiceEnabled) {
                isBackgroundEnabled = false
                persistentState.setIsBackgroundEnabled(false)
            }
        }
    }

    @InternalCoroutinesApi private fun spawnServerUpdate() {
        if (vpnController != null) {
            synchronized(vpnController) {
                if (networkManager != null) {
                    Thread({ updateServerConnection() }, "updateServerConnection-onStartCommand").start()
                }
            }
        }
    }

    @WorkerThread private fun updateServerConnection() {
        vpnAdapter?.updateDohUrl(appMode?.getDNSMode(), appMode?.getFirewallMode(), appMode?.getProxyMode())
    }

    fun recordTransaction(transaction: Transaction) {
        //transaction.responseTime = SystemClock.elapsedRealtime()
        transaction.responseCalendar = Calendar.getInstance()
        // All the transactions are recorded in the DNS logs.
        tracker.recordTransaction(transaction)
        if(persistentState.logsEnabled) {
            dnsLogTracker.recordTransaction(transaction)
        }

        if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Record Transaction: status- ${transaction.status}")
        // Update the connection state.  If the transaction succeeded, then the connection is working.
        // If the transaction failed, then the connection is not working.
        // If the transaction was canceled, then we don't have any new information about the status
        // of the connection, so we don't send an update.
        if (transaction.status === Transaction.Status.COMPLETE) {
            vpnController!!.onConnectionStateChanged(this, State.WORKING)
        } else if (transaction.status !== Transaction.Status.CANCELED) {
            vpnController!!.onConnectionStateChanged(this, State.FAILING)
        }
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
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val builder = updateBuilder(this)
            notificationManager.notify(SERVICE_ID, builder.build())
        }


        if (PersistentState.DNS_TYPE == key) {
            Log.d(LOG_TAG, "$FILE_LOG_TAG DNSType- ${appMode?.getDNSType()}")

            /**
             * The code has been modified to restart the VPN service when there is a
             * change in the DNS Type. Earlier the restart of the VPN is performed
             * when the mode is changed to DNS Proxy, now the code is modified in
             * such a way that, the restart is performed when there mode is Crypt/Proxy
             */

            if (appMode?.getDNSType() == 3) {
                restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
            } else {
                if (appMode?.getDNSType() == 2) {
                    setCryptMode()
                }
                spawnServerUpdate()
            }
        }

        if (PersistentState.PROXY_MODE == key) {
            Log.d(LOG_TAG, "$FILE_LOG_TAG PROXY_MODE- ${appMode?.getProxyMode()}")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if (PersistentState.CONNECTION_CHANGE == key) {
            Log.d(LOG_TAG, "$FILE_LOG_TAG CONNECTION_CHANGE- ${appMode?.getDNSMode()}")
            spawnServerUpdate()
        }

        if (PersistentState.DNS_PROXY_ID == key) {
            Log.d(LOG_TAG, "$FILE_LOG_TAG DNS PROXY CHANGE- ${appMode?.getDNSMode()!!}")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if (PersistentState.EXCLUDE_FROM_VPN == key) {
            Log.d(LOG_TAG, "$FILE_LOG_TAG EXCLUDE_FROM_VPN - restartVpn- ${appMode?.getDNSMode()}")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if (PersistentState.IS_SCREEN_OFF == key) {
            isScreenLocked = persistentState.getScreenLockData()
            Log.i(LOG_TAG, "$FILE_LOG_TAG preference for screen off mode is modified - $isScreenLocked")
        }

        if (PersistentState.BACKGROUND_MODE == key) {
            isBackgroundEnabled = persistentState.backgroundEnabled && Utilities.isAccessibilityServiceEnabledEnhanced(this, BackgroundAccessibilityService::class.java)
            Log.i(LOG_TAG, "$FILE_LOG_TAG preference for background mode is modified - $isBackgroundEnabled")
            if (isBackgroundEnabled) {
                restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
            }
        }
        if (PersistentState.BLOCK_UNKNOWN_CONNECTIONS == key) {
            Log.i(LOG_TAG, "$FILE_LOG_TAG preference for block unknown connections is modified")
            blockUnknownConnection = persistentState.blockUnknownConnections
        }

        if (PersistentState.LOCAL_BLOCK_LIST == key) {
            Log.i(LOG_TAG, "$FILE_LOG_TAG preference for local block list is changed - restart vpn")
            spawnServerUpdate()
        }

        if (PersistentState.LOCAL_BLOCK_LIST_STAMP == key) {
            Log.i(LOG_TAG, "$FILE_LOG_TAG configuration stamp for local block list is changed- restart vpn")
            spawnServerUpdate()
        }

        if (PersistentState.ALLOW_BYPASS == key) {
            Log.i(LOG_TAG, "$FILE_LOG_TAG preference for allow by pass is changed - restart vpn")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if (PersistentState.PRIVATE_DNS == key) {
            privateDNSOverride = persistentState.allowPrivateDNS
        }

        if (PersistentState.HTTP_PROXY_ENABLED == key) {
            Log.i(LOG_TAG, "$FILE_LOG_TAG preference for http proxy is changed - restart vpn")
            restartVpn(appMode?.getDNSMode()!!, appMode?.getFirewallMode()!!, appMode?.getProxyMode()!!)
        }

        if (PersistentState.BLOCK_UDP_OTHER_THAN_DNS == key) {
            blockUDPTraffic = persistentState.udpBlockedSettings
        }

    }

    fun signalStopService(userInitiated: Boolean) {
        if (!userInitiated) {
            val vibrationPattern = longArrayOf(1000) // Vibrate for one second.
            // Show revocation warning
            var builder: Notification.Builder
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            val mainActivityIntent = PendingIntent.getActivity(this, 0, Intent(this, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setSmallIcon(R.drawable.dns_icon)
                .setContentTitle(resources.getText(R.string.warning_title))
                .setContentText(resources.getText(R.string.notification_content))
                .setContentIntent(mainActivityIntent)
                // Open the main UI if possible.
                .setAutoCancel(true)
            if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_ERROR)
            }
            notificationManager.notify(0, builder.notification)
        }
        stopVpnAdapter()
        stopSelf()

        Log.d(LOG_TAG, "$FILE_LOG_TAG Stop Foreground")
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
                    Log.e(LOG_TAG, "$FILE_LOG_TAG Stop Called - stopVpnAdapter closed all states")
                }
            }
        } else {
            Log.e(LOG_TAG, "$FILE_LOG_TAG Stop Called with VPN Controller as null")
        }
    }

    @InternalCoroutinesApi
    private fun restartVpn(dnsModeL: Long, firewallModeL: Long, proxyMode: Long) {
        isBackgroundEnabled = persistentState.backgroundEnabled &&
            Utilities.isAccessibilityServiceEnabledEnhanced(this, BackgroundAccessibilityService::class.java)
        if (vpnController != null) {
            synchronized(vpnController) {
                Thread({
                    //updateServerConnection()
                    // Attempt seamless handoff as described in the docs for VpnService.Builder.establish().
                    val oldAdapter: GoVpnAdapter? = vpnAdapter
                    vpnAdapter = makeVpnAdapter()
                    oldAdapter?.close()
                    if (vpnAdapter != null) {
                        vpnAdapter!!.start(dnsModeL, firewallModeL, proxyMode)
                    } else {
                        Log.i(LOG_TAG, String.format("$FILE_LOG_TAG Restart failed"))
                    }
                }, "restartvpn-onCommand").start()
            }
        } else {
            Log.i("VpnService", String.format("$FILE_LOG_TAG vpnController is null"))
        }
    }

    private fun makeVpnAdapter(): GoVpnAdapter? {
        return GoVpnAdapter.establish(this, appMode, dnsProxyEndpointRepository, get(), get(), persistentState)
    }

    override fun onNetworkConnected(networkInfo: NetworkInfo?) {
        setNetworkConnected(true)

        // This code is used to start the VPN for the first time, but startVpn is idempotent, so we can
        // call it every time. startVpn performs network activity so it has to run on a separate thread.
        Thread({ //TODO Work on the order of the function call.
            updateServerConnection()
            startVpn()
        }, "startVpn-onNetworkConnected").start()
    }

    override fun onNetworkDisconnected() {
        setNetworkConnected(false)
        vpnController!!.onConnectionStateChanged(this, null)
    }

    private fun setNetworkConnected(connected: Boolean) {
        networkConnected = connected
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            // Indicate that traffic will be sent over the current active network.
            // See e.g. https://issuetracker.google.com/issues/68657525
            val activeNetwork = getSystemService(ConnectivityManager::class.java).activeNetwork
            setUnderlyingNetworks(if (connected) arrayOf(activeNetwork) else null)
        }
    }

    @WorkerThread private fun startVpn() {
        isBackgroundEnabled = persistentState.backgroundEnabled
            && Utilities.isAccessibilityServiceEnabledEnhanced(this, BackgroundAccessibilityService::class.java)
        kotlin.synchronized(vpnController!!) {
            startVpnAdapter()
            vpnController.onStartComplete(this, vpnAdapter != null)
            if (vpnAdapter == null) {
                Log.d(LOG_TAG, "$FILE_LOG_TAG Failed to startVpn VPN adapter")
                stopSelf()
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
                    Log.w(LOG_TAG, "$FILE_LOG_TAG Failed to start VPN adapter!")
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(braveScreenStateReceiver)
        } catch (e: java.lang.Exception) {
            Log.w(LOG_TAG, "$FILE_LOG_TAG Unregister receiver error: ${e.message}", e)
        }

        kotlin.synchronized(vpnController!!) {
            Log.w(LOG_TAG, "$FILE_LOG_TAG Destroying DNS VPN service")
            persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
            if (networkManager != null) {
                networkManager!!.destroy()
            }
            vpnController.setBraveVpnService(null)
            stopForeground(true)
            if (vpnAdapter != null) {
                signalStopService(false)
            }
        }
        try {
            unregisterReceiver(braveAutoStartReceiver)
        } catch (e: java.lang.Exception) {
            Log.w(LOG_TAG, "$FILE_LOG_TAG Unregister receiver error: ${e.message}", e)
        }
    }

    override fun onRevoke() {
        stopSelf()
        persistentState.vpnEnabled = false
    }

}
