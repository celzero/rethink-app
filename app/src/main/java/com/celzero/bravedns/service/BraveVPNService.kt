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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.CountDownTimer
import android.os.SystemClock.elapsedRealtime
import android.service.quicksettings.TileService.requestListeningState
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.automaton.FirewallRules.UID_EVERYBODY
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.ProxyEndpointRepository
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.ui.FirewallActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.DEFAULT_PAUSE_TIMER
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_PROXY
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.getThemeAccent
import com.celzero.bravedns.util.Utilities.Companion.isMissingOrInvalidUid
import com.celzero.bravedns.util.Utilities.Companion.isVpnLockdownEnabled
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import kotlinx.coroutines.*
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import protect.Blocker
import protect.Protector
import settings.Settings
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random


class BraveVPNService : VpnService(), ConnectionMonitor.NetworkListener, Protector, Blocker,
                        OnSharedPreferenceChangeListener {

    @GuardedBy("vpnController") private var connectionMonitor: ConnectionMonitor? = null
    @GuardedBy("vpnController") private var vpnAdapter: GoVpnAdapter? = null

    companion object {
        private const val DNS_REQUEST_PORT = 53

        const val SERVICE_ID = 1 // Only has to be unique within this app.

        private const val MAIN_CHANNEL_ID = "vpn"
        private const val WARNING_CHANNEL_ID = "warning"

        // notification request codes
        private const val NOTIF_ACTION_MODE_RESUME = 98
        private const val NOTIF_ACTION_MODE_PAUSE = 99
        private const val NOTIF_ACTION_MODE_STOP = 100
        private const val NOTIF_ACTION_MODE_DNS_ONLY = 101
        private const val NOTIF_ACTION_MODE_DNS_FIREWALL = 102

        private const val NOTIF_LOAD_RULES_FAIL = "warning"
        private const val NOTIF_ID_LOAD_RULES_FAIL = 103

        private const val NOTIF_ACCESSIBILITY_FAILURE = "Accessibility"
        private const val NOTIF_ID_ACCESSIBILITY_FAILURE = 104
    }

    private var isLockDownPrevious: Boolean = false

    private val vpnScope = MainScope()

    private var notificationCountForFirewallRules: Int = 0
    var totalAccessibilityFailureNotifications: Int = 0

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appMode by inject<AppMode>()
    private val ipTracker by inject<IPTracker>()
    private val dnsLogTracker by inject<DNSLogTracker>()
    private val persistentState by inject<PersistentState>()
    private val dnsLatencyTracker by inject<QueryTracker>()

    @Volatile private var isAccessibilityServiceFunctional: Boolean = false
    @Volatile var accessibilityHearbeatTimestamp: Long = 0L

    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager
    private lateinit var accessibilityManager: AccessibilityManager
    private var keyguardManager: KeyguardManager? = null

    var pauseTimer: CountDownTimer? = null
    var pauseRemainingTime: Long? = DEFAULT_PAUSE_TIMER
    var updateTimerLiveData: MutableLiveData<Long> = MutableLiveData()

    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    enum class State {
        NEW, WORKING, FAILING
    }

    override fun block(protocol: Int, uid: Long, sourceAddress: String,
                       destAddress: String): Boolean {
        val isBlocked: Boolean
        val first = sourceAddress.split(":")
        val second = destAddress.split(":")
        isBlocked = checkConnectionBlocked(uid.toInt(), protocol, first[0],
                                           first[first.size - 1].toInt(), second[0],
                                           second[second.size - 1].toInt())

        // creating reusable IO() method for coroutine call causing compiler error.
        // so using the coroutine everywhere.
        CoroutineScope(Dispatchers.IO).launch {
            handleVpnLockdownState()
        }
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
    private fun checkConnectionBlocked(_uid: Int, protocol: Int, sourceIp: String, sourcePort: Int,
                                       destIp: String, destPort: Int): Boolean {

        loadFirewallRulesIfNeeded()

        val ipDetails: IPDetails?
        var isBlocked: Boolean
        var uid = _uid
        try {
            if ((destIp == GoVpnAdapter.FAKE_DNS_IP && destPort == DNS_REQUEST_PORT)) {
                return false
            }

            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                uid = connTracer.getUidQ(protocol, sourceIp, sourcePort, destIp, destPort)
            } else {
                // no-op
            }

            ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort,
                                  System.currentTimeMillis(), false, "", protocol)

            if (FirewallManager.isUidWhitelisted(uid)) {
                ipDetails.isBlocked = false
                ipDetails.blockedByRule = FirewallRuleset.RULE7.ruleName
                connTrack(ipDetails)
                Log.i(LOG_TAG_VPN, "whitelisted app, uid: $uid")
                return false
            }

            if (persistentState.blockUnknownConnections) {
                if (isMissingOrInvalidUid(uid)) {
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = FirewallRuleset.RULE5.ruleName
                    connTrack(ipDetails)
                    delayBeforeResponse()
                    return true
                }
            }

            if (isUdpBlocked(protocol)) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = FirewallRuleset.RULE6.ruleName
                connTrack(ipDetails)
                delayBeforeResponse()
                if (DEBUG) Log.d(LOG_TAG_VPN, "blockUDPTraffic: $uid, $destPort")
                return true
            }

            //Check whether the screen lock is enabled and act based on it
            if (isDeviceLocked() && Utilities.isUnknownUid(uid)) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = FirewallRuleset.RULE3.ruleName
                connTrack(ipDetails)
                if (DEBUG) Log.d(LOG_TAG_VPN,
                                 "Block connection with delay for screen lock: $uid, $destPort")
                delayBeforeResponse()
                return true
            }

            //Check whether the background is enabled and act based on it
            if (blockBackgroundData() && Utilities.isUnknownUid(uid)) {
                // FIXME: 04-12-2020 Removed the app range check for testing.
                var isBGBlock = true

                // Introducing exponential calculation for the wait time.
                // initially the thread sleep was done with constant time. for eg., thread
                // will sleep for 2 secs and will check if the app is in foreground or not.
                // To avoid the constant time wait introduced the exponential logic below.
                val bgBlockWaitMs = TimeUnit.SECONDS.toMillis(20)
                var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
                var attempt = 0

                while (remainingWaitMs > 0) {
                    if (FirewallManager.isAppForeground(uid, keyguardManager)) {
                        isBGBlock = false
                        break
                    }
                    remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
                    attempt += 1
                }

                // When the app is not in foreground.
                if (isBGBlock) {
                    if (DEBUG) Log.d(LOG_TAG_VPN,
                                     "Background blocked uid: $uid, send response after sleep of ${bgBlockWaitMs + remainingWaitMs}")
                    Thread.sleep((bgBlockWaitMs + remainingWaitMs))
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = FirewallRuleset.RULE4.ruleName
                    connTrack(ipDetails)
                    Log.i(LOG_TAG_VPN, "Background blocked $uid after sleep of: 30 secs")
                    return isBGBlock
                } else {
                    if (DEBUG) Log.d(LOG_TAG_VPN,
                                     "Background allowed for $uid, $destIp, Remaining time: ${10_000 - remainingWaitMs} ")
                }
            }

            // Check whether any rules are set block the IP/App.
            var blockedBy = ""
            isBlocked = isUidBlocked(uid)
            if (Utilities.isUnknownUid(uid) && isBlocked) {
                blockedBy = FirewallRuleset.RULE1.ruleName
                if (persistentState.killAppOnFirewall) {
                    killFirewalledApplication(uid)
                }
            } // fall-through

            if (!isBlocked) {
                val connectionRules = ConnectionRules(destIp, destPort,
                                                      Protocol.getProtocolName(protocol).name)
                isBlocked = FirewallRules.hasRule(UID_EVERYBODY, connectionRules)
                if (isBlocked) blockedBy = FirewallRuleset.RULE2.ruleName
            } // fall-through
            ipDetails.isBlocked = isBlocked
            ipDetails.blockedByRule = blockedBy
            connTrack(ipDetails)

        } catch (iex: Exception) {
            //Returning the blocked as true for all the block call from GO with exceptions.
            //So the apps which are hit in exceptions will have the response sent as blocked(true)
            //after a delayed amount of time.
            /*
             * TODO- Check for the possibility of adding the exceptions on alerts(either in-memory
             * or as persistent alert)
             */
            isBlocked = true
            Log.e(LOG_TAG_VPN, iex.message, iex)
        }
        if (isBlocked) {
            delayBeforeResponse()
        }
        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "Response for the block: $destIp, $uid, $isBlocked, $sourceIp, $sourcePort, $destIp, $destPort")
        return isBlocked
    }

    private fun isUdpBlocked(protocol: Int): Boolean {
        return protocol == Protocol.UDP.protocolType && persistentState.udpBlockedSettings
    }

    private fun loadFirewallRulesIfNeeded() {
        if (FirewallManager.isFirewallRulesLoaded()) return

        Log.i(LOG_TAG_VPN, "loading rules... waiting")
        waitForFirewallRules()
        if (!FirewallManager.isFirewallRulesLoaded()) {
            notifyLoadFailure()
        }
    }

    // Modified the logic of "Block connections when screen is locked".
    // Earlier the screen lock is detected with receiver received for Action user_present/screen_off.
    // Now the code only checks whether the KeyguardManager#isKeyguardLocked() is true/false.
    // if isKeyguardLocked() is true, the connections will be blocked.
    private fun isDeviceLocked(): Boolean {
        if (!persistentState.screenState) return false

        if (keyguardManager == null) {
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        return (keyguardManager?.isKeyguardLocked == true)
    }

    private fun blockBackgroundData(): Boolean {
        if (!persistentState.backgroundEnabled) return false
        if (!accessibilityServiceFunctional()) {
            if (DEBUG) Log.d(LOG_TAG_VPN,
                             "App not in use is enabled but accessibility permission got disabled")
            handleAccessibilityFailure()
            return false
        }
        return true
    }

    private fun handleAccessibilityFailure() {
        // Disable app not in use behaviour when the accessibility failure is detected.
        persistentState.backgroundEnabled = false
        showAccessibilityStoppedNotification()
    }

    private fun showAccessibilityStoppedNotification() {
        if (isAccessibilityNotificationNotNeeded()) return

        totalAccessibilityFailureNotifications += 1

        if (DEBUG) Log.d(LOG_TAG_VPN, "App not in use failure, show notification")

        val intent = Intent(this, FirewallActivity::class.java)
        intent.putExtra(NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME,
                        NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE)

        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
                                                      PendingIntent.FLAG_UPDATE_CURRENT)
        var builder: NotificationCompat.Builder
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            val name: CharSequence = NOTIF_ACCESSIBILITY_FAILURE
            val description = this.resources.getString(R.string.accessibility_notification_content)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_ACCESSIBILITY_FAILURE, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(this, NOTIF_ACCESSIBILITY_FAILURE)
        } else {
            builder = NotificationCompat.Builder(this, NOTIF_ACCESSIBILITY_FAILURE)
        }

        val contentTitle: String = this.resources.getString(
            R.string.accessibility_notification_title)
        val contentText: String = this.resources.getString(
            R.string.accessibility_notification_content)

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            pendingIntent).setContentText(contentText)

        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(this, getThemeAccent(this))

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // Cancel the notification after clicking.
        builder.setAutoCancel(true)

        notificationManager.notify(NOTIF_ID_ACCESSIBILITY_FAILURE, builder.build())
    }

    private fun isAccessibilityNotificationNotNeeded(): Boolean {
        // The check is to make sure the notification is not shown to the user
        // more than twice.
        if (totalAccessibilityFailureNotifications >= 2) {
            return true
        }
        return false
    }

    private fun accessibilityServiceFunctional(): Boolean {
        val now = elapsedRealtime()
        // Added the INIT_TIME_MS check, encountered a bug during phone restart
        // isAccessibilityServiceRunning default value(false) is passed instead of
        // checking it from accessibility service for the first time.
        if (accessibilityHearbeatTimestamp == INIT_TIME_MS || Math.abs(
                now - accessibilityHearbeatTimestamp) > Constants.ACCESSIBILITY_SERVICE_HEARTBEAT_THRESHOLD_MS) {
            accessibilityHearbeatTimestamp = now

            isAccessibilityServiceFunctional = Utilities.isAccessibilityServiceEnabled(this,
                                                                                       BackgroundAccessibilityService::class.java) && Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                this, BackgroundAccessibilityService::class.java)
        }
        return isAccessibilityServiceFunctional
    }

    private fun notifyLoadFailure() {
        if (isLoadFailureNotificationNotNeeded()) return

        notificationCountForFirewallRules += 1

        val mainActivityIntent = PendingIntent.getActivity(this, 0, Intent(this,
                                                                           HomeScreenActivity::class.java),
                                                           PendingIntent.FLAG_UPDATE_CURRENT)
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            val name: CharSequence = NOTIF_LOAD_RULES_FAIL
            val description = resources.getString(R.string.rules_load_failure_heading)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_LOAD_RULES_FAIL, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
        }
        var builder: NotificationCompat.Builder = NotificationCompat.Builder(this,
                                                                             NOTIF_LOAD_RULES_FAIL)

        val contentTitle = resources.getString(R.string.rules_load_failure_heading)
        val contentText = resources.getString(R.string.rules_load_failure_desc)
        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            mainActivityIntent).setContentText(contentText)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(this, getThemeAccent(this))
        val openIntent = makeVpnIntent(this, NOTIF_ID_LOAD_RULES_FAIL,
                                       Constants.NOTIF_ACTION_RULES_FAILURE)
        val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0,
                                                                                      resources.getString(
                                                                                          R.string.rules_load_failure_reload),
                                                                                      openIntent)
        builder.addAction(notificationAction)

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        builder.build()
        notificationManager.notify(NOTIF_ID_LOAD_RULES_FAIL, builder.build())

    }

    private fun isLoadFailureNotificationNotNeeded(): Boolean {
        // The check is to make sure the notification is not shown to the user
        // more than twice. Earlier notificationManager#activeNotifications was used
        // which will return false when the user clears the notification from status bar.
        if (notificationCountForFirewallRules >= 2) {
            return true
        }
        return false
    }

    private fun waitForFirewallRules() {
        // Adding the exponential calculation of wait time until we load the firewall
        // rules. The other check of the block() method will be executed once we load
        // the firewall rules
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
        var attempt = 0
        while (remainingWaitMs > 0) {
            Log.i(LOG_TAG_VPN,
                  "isLoadFirewallRulesCompleted? ${FirewallManager.isFirewallRulesLoaded()}")
            if (FirewallManager.isFirewallRulesLoaded()) {
                break
            }
            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt += 1
        }
    }

    private fun delayBeforeResponse() {
        Thread.sleep(Constants.DELAY_FIREWALL_RESPONSE_MS)
    }

    // ref: https://stackoverflow.com/a/363692
    val baseWaitMs = TimeUnit.MILLISECONDS.toMillis(50)

    private fun exponentialBackoff(remainingWaitMs: Long, attempt: Int): Long {
        var tempRemainingWaitMs = remainingWaitMs
        val exponent = exp(attempt)
        val randomValue = rand.nextLong(exponent - baseWaitMs + 1) + baseWaitMs
        val waitTimeMs = min(randomValue, remainingWaitMs)
        tempRemainingWaitMs -= waitTimeMs
        Thread.sleep(waitTimeMs)
        return tempRemainingWaitMs
    }

    private fun exp(pow: Int): Long {
        return if (pow == 0) {
            baseWaitMs
        } else {
            (1 shl pow) * baseWaitMs
        }
    }

    private fun killFirewalledApplication(uid: Int) {
        if (DEBUG) Log.i(LOG_TAG_VPN,
                         "Firewalled application trying to connect - Kill app is enabled - uid - $uid")
        CoroutineScope(Dispatchers.IO).launch {
            val appUIDList = FirewallManager.getPackageNamesByUid(uid)
            appUIDList.forEach {
                if (DEBUG) Log.d(LOG_TAG_VPN, "app $uid / $it killed")
                Utilities.killBg(activityManager, it)
            }
        }
    }

    /**
     * Records the network transaction in local database
     * The logs will be shown in network monitor screen
     */
    private fun connTrack(ipDetails: IPDetails?) {
        ipTracker.recordTransaction(ipDetails)
    }

    /**
     * Checks whether the connection is added in firewall rules
     */
    private fun isUidBlocked(uid: Int): Boolean {
        return try {
            FirewallManager.isUidFirewalled(uid)
        } catch (e: Exception) {
            false
        }
    }

    override fun getResolvers(): String {
        // TODO: remove stub, unused
        throw UnsupportedOperationException("stub method")
    }

    fun isOn(): Boolean {
        return vpnAdapter != null
    }

    fun newBuilder(): Builder {
        var builder = Builder()

        if (!isVpnLockdownEnabled(this) && persistentState.allowByPass) {
            Log.i(LOG_TAG_VPN, "getAllowByPass - true")
            builder = builder.allowBypass()
        }

        // underlying networks is set to null, which prompts Android to set it to whatever is the
        // current active network. Later, ConnectionMonitor#onVpnStarted, depending on user
        // chosen preferences, sets appropriate underlying network/s.
        builder.setUnderlyingNetworks(null)

        // Fix - Cloud Backups were failing thinking that the VPN connection is metered.
        // The below code will fix that.
        if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            if (!isVpnLockdownEnabled(this) && (appMode.isHttpProxyTypeEnabled())) {
                getHttpProxyInfo()?.let { builder.setHttpProxy(it) }
            }
        }

        try {
            if (!isVpnLockdownEnabled(
                    this) && appMode.getAppState() == AppMode.AppState.PAUSE.state) {
                val allApps = FirewallManager.getNonFirewalledAppsPackageNames()
                Log.i(LOG_TAG_VPN,
                      "app is in pause state, exclude all the non firewalled apps, size: ${allApps.size}")
                allApps.forEach {
                    builder.addDisallowedApplication(it.packageInfo)
                }
                builder = builder.addDisallowedApplication(this.packageName)
                return builder
            }

            val excludedApps = FirewallManager.getExcludedApps()
            if (appMode.getFirewallMode() == Settings.BlockModeSink) {
                if (excludedApps.isEmpty()) {
                    builder.addAllowedApplication("")
                } else {
                    for (packageName in excludedApps) {
                        builder = builder.addAllowedApplication(packageName)
                    }
                }
            } else {
                /* The check for the apps to exclude from VPN or not.
                   In case of lockdown mode, the excluded apps wont able to connected. so
                   not including the apps in the excluded list if the lockdown mode is
                   enabled. */
                if (!isVpnLockdownEnabled(this)) {
                    excludedApps.forEach {
                        builder = builder.addDisallowedApplication(it)
                        Log.i(LOG_TAG_VPN, "Excluded package - $it")
                    }
                }
                builder = builder.addDisallowedApplication(this.packageName)
            }
            if (appMode.isCustomSocks5()) {
                // For Socks5 if there is a app selected, add that app in excluded list
                val socks5ProxyEndpoint = appMode.getConnectedSocks5Proxy()
                val appName = socks5ProxyEndpoint?.proxyAppName
                Log.i(LOG_TAG_VPN,
                      "Proxy mode - Socks5 is selected - $socks5ProxyEndpoint, with app name - $appName")
                if (appName?.equals(getString(R.string.settings_app_list_default_app)) == false && isExcludePossible(appName, getString(R.string.socks5_proxy_toast_parameter))) {
                    // Asserting the appName as there is null check above.
                    builder = builder.addDisallowedApplication(appName)
                }
            }

            if (appMode.isOrbotProxy() && isExcludePossible(getString(R.string.orbot), getString(
                    R.string.orbot_toast_parameter))) {
                builder = builder.addDisallowedApplication(OrbotHelper.ORBOT_PACKAGE_NAME)
            }

            if (appMode.isDnsProxyActive()) {
                // For DNS proxy mode, if any app is set then exclude the application from the list
                val dnsProxyEndpoint = appMode.getConnectedProxyDetails()
                val appName = dnsProxyEndpoint.proxyAppName
                Log.i(LOG_TAG_VPN, "DNS Proxy mode is set with the app name as $appName")
                if (appName?.equals(getString(R.string.settings_app_list_default_app)) == false && isExcludePossible(appName, getString(R.string.dns_proxy_toast_parameter))) {
                    // Asserting the appName as there is null check above.
                    builder = builder.addDisallowedApplication(appName)
                }
            }

        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(LOG_TAG_VPN, "cannot exclude the dns proxy app", e)
        }
        return builder
    }

    private fun isExcludePossible(appName: String?, message: String): Boolean {
        if (isVpnLockdownEnabled(this)) {
            Log.i(LOG_TAG_VPN, "Vpn in lockdown mode")
            CoroutineScope(Dispatchers.Main).launch {
                VpnController.getBraveVpnService()?.let {
                    showToastUiCentered(it, getString(R.string.dns_proxy_connection_failure_lockdown,
                                                      appName, message), Toast.LENGTH_SHORT)
                }
            }
            return false
        }
        return (appName?.equals(getString(R.string.settings_app_list_default_app)) == false)
    }


    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        VpnController.setBraveVpnService(this)
    }

    private fun getHttpProxyInfo(): ProxyInfo? {
        val proxyInfo: ProxyInfo?
        val host = persistentState.httpProxyHostAddress
        val port = persistentState.httpProxyPort
        Log.i(LOG_TAG_VPN, "Http proxy enabled - builder updated with $host, $port")
        if (host.isNotEmpty() && port != INVALID_PORT) {
            proxyInfo = ProxyInfo.buildDirectProxy(host, port)
            return proxyInfo
        }
        return null
    }

    private fun updateNotificationBuilder(context: Context): NotificationCompat.Builder {
        val mainActivityIntent = PendingIntent.getActivity(context, 0, Intent(context,
                                                                              HomeScreenActivity::class.java),
                                                           PendingIntent.FLAG_UPDATE_CURRENT)
        var builder: NotificationCompat.Builder
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            val name: CharSequence = context.resources.getString(R.string.app_name)
            val description = context.resources.getString(R.string.notification_content)
            // LOW is the lowest importance that is allowed with startForeground in Android O
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        } else {
            builder = NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        }

        var contentTitle: String = when (appMode.getBraveMode()) {
            Constants.APP_MODE_DNS -> context.resources.getString(
                R.string.dns_mode_notification_title)
            Constants.APP_MODE_FIREWALL -> context.resources.getString(
                R.string.firewall_mode_notification_title)
            Constants.APP_MODE_DNS_FIREWALL -> context.resources.getString(
                R.string.hybrid_mode_notification_title)
            else -> context.resources.getString(R.string.notification_title)
        }

        if (appMode.getAppState() == AppMode.AppState.PAUSE.state) {
            contentTitle = context.resources.getString(
                                            R.string.pause_mode_notification_title)
        }

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            mainActivityIntent)

        // New action button options in the notification
        // 1. PAUSE/RESUME, STOP action button.
        // 2. RethinkDNS modes (DNS & DNS+Firewall mode)
        // 3. No action button.
        if (DEBUG) Log.d(LOG_TAG_VPN, "Notification - ${persistentState.notificationAction}")
        builder.color = ContextCompat.getColor(this, getThemeAccent(this))
        when (persistentState.notificationAction) {
            Constants.NOTIFICATION_ACTION_STOP -> {
                // Add the action based on AppState (PAUSE/ACTIVE)
                if (appMode.getAppState() == AppMode.AppState.PAUSE.state) {
                    val openIntent1 = makeVpnIntent(context, NOTIF_ACTION_MODE_RESUME,
                                                    Constants.NOTIF_ACTION_RESUME_VPN)
                    val notificationAction1 = NotificationCompat.Action(0,
                                                                        context.resources.getString(
                                                                            R.string.notification_action_resume_vpn),
                                                                        openIntent1)
                    builder.addAction(notificationAction1)
                } else {
                    val openIntent1 = makeVpnIntent(context, NOTIF_ACTION_MODE_PAUSE,
                                                    Constants.NOTIF_ACTION_PAUSE_VPN)
                    val notificationAction1 = NotificationCompat.Action(0,
                                                                        context.resources.getString(
                                                                            R.string.notification_action_pause_vpn),
                                                                        openIntent1)
                    builder.addAction(notificationAction1)
                }

                val openIntent2 = makeVpnIntent(context, NOTIF_ACTION_MODE_STOP,
                                                Constants.NOTIF_ACTION_STOP_VPN)
                val notificationAction2 = NotificationCompat.Action(0, context.resources.getString(
                    R.string.notification_action_stop_vpn), openIntent2)
                builder.addAction(notificationAction2)
            }
            Constants.NOTIFICATION_ACTION_DNS_FIREWALL -> {
                val openIntent1 = makeVpnIntent(context, NOTIF_ACTION_MODE_DNS_ONLY,
                                                Constants.NOTIF_ACTION_DNS_VPN)
                val openIntent2 = makeVpnIntent(context, NOTIF_ACTION_MODE_DNS_FIREWALL,
                                                Constants.NOTIF_ACTION_DNS_FIREWALL_VPN)
                val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0,
                                                                                              context.resources.getString(
                                                                                                  R.string.notification_action_dns_mode),
                                                                                              openIntent1)
                val notificationAction2: NotificationCompat.Action = NotificationCompat.Action(0,
                                                                                               context.resources.getString(
                                                                                                   R.string.notification_action_dns_firewall_mode),
                                                                                               openIntent2)
                builder.addAction(notificationAction)
                builder.addAction(notificationAction2)
            }
            Constants.NOTIFICATION_ACTION_NONE -> {
                Log.i(LOG_TAG_VPN, "No notification action")
            }
        }

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        builder.build()
        return builder
    }

    private fun makeVpnIntent(context: Context, notificationID: Int,
                              intentExtra: String): PendingIntent? {
        val intentAction = Intent(context, NotificationActionReceiver::class.java)
        intentAction.putExtra(Constants.NOTIFICATION_ACTION, intentExtra)
        return PendingIntent.getBroadcast(context, notificationID, intentAction,
                                          PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        activityManager = this.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        accessibilityManager = this.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        keyguardManager = this.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        vpnScope.launch {
            FirewallManager.loadAppFirewallRules()
        }

        // Initialize the value whenever the vpn is started.
        accessibilityHearbeatTimestamp = INIT_TIME_MS

        if (appMode.isOrbotProxyEnabled()) {
            get<OrbotHelper>().startOrbot(appMode.getProxyType())
        }
        if (persistentState.backgroundEnabled) {
            registerAccessibilityServiceState()
        }
        synchronized(VpnController) {
            VpnController.onConnectionStateChanged(State.NEW)

            if (DEBUG) Log.d(LOG_TAG_VPN,
                             "Registering the shared pref changes with the vpn service")
            persistentState.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

            // In case if the service already running(connectionMonitor will not be null)
            // and onStartCommand is called then no need to process the below initializations
            // instead call spawnServerUpdate()
            // spawnServerUpdate() - Will overwrite the tunnel values with new values.
            if (connectionMonitor != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    spawnServerUpdate(appMode.makeTunnelDataClass())
                }
                return Service.START_REDELIVER_INTENT
            }

            // Register for ConnectivityManager
            // FIXME #200 - Move it to Koin
            connectionMonitor = ConnectionMonitor(this, this)
            CoroutineScope(Dispatchers.IO).launch {
                restartVpn(appMode.makeTunnelDataClass())
            }
            // Mark this as a foreground service.  This is normally done to ensure that the service
            // survives under memory pressure.  Since this is a VPN service, it is presumably protected
            // anyway, but the foreground service mechanism allows us to set a persistent notification,
            // which helps users understand what's going on, and return to the app if they want.
            val builder = updateNotificationBuilder(this)
            Log.i(LOG_TAG_VPN, "onStart command - start as foreground service. ")
            startForeground(SERVICE_ID, builder.build())
            persistentState.setVpnEnabled(true)
            updateQuickSettingsTile()
            connectionMonitor?.onVpnStarted()
        }
        return Service.START_REDELIVER_INTENT
    }


    private fun registerAccessibilityServiceState() {
        accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { b ->
            if (!b) {
                persistentState.backgroundEnabled = false
                handleAccessibilityFailure()
            }
        }
    }

    private fun unregisterAccessibilityServiceState() {
        accessibilityListener?.let {
            accessibilityManager.removeAccessibilityStateChangeListener(it)
        }
    }

    private fun spawnServerUpdate(tunnelMode: AppMode.TunnelMode) {
        synchronized(VpnController) {
            // Connection monitor can be null if onDestroy() of service
            // is called, in that case no need to call updateServerConnection()
            if (connectionMonitor != null) {
                Thread({ updateServerConnection(tunnelMode) },
                       "updateServerConnection-onStartCommand").start()
            } else {
                Log.w(LOG_TAG_VPN, "cannot spawn sever update, no connection monitor")
            }
        }
    }

    @WorkerThread
    private fun updateServerConnection(tunnelMode: AppMode.TunnelMode) {
        vpnAdapter?.updateDohUrl(tunnelMode)
        handleAdapterChange(tunnelMode)
    }

    fun recordTransaction(transaction: Transaction) {
        transaction.responseCalendar = Calendar.getInstance()
        // All the transactions are recorded in the DNS logs.
        // Quantile estimation correction - Not adding the transactions with server IP
        // as null in the quantile estimator.
        if (!transaction.serverIp.isNullOrEmpty()) {
            dnsLatencyTracker.recordTransaction(transaction)
        }

        dnsLogTracker.recordTransaction(transaction)

        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "Record Transaction: status as ${transaction.status} with blocklist ${transaction.blocklist}")
        // Update the connection state.  If the transaction succeeded, then the connection is working.
        // If the transaction failed, then the connection is not working.
        // If the transaction was canceled, then we don't have any new information about the status
        // of the connection, so we don't send an update.
        // commented the code for reporting good or bad network.
        // Connection state will be unknown if the transaction is blocked locally in that case,
        // transaction status will be set as complete. So introduced check while
        // setting the connection state.
        if (transaction.status === Transaction.Status.COMPLETE) {
            if (isLocallyResolved(transaction)) return
            VpnController.onConnectionStateChanged(State.WORKING)
        } else if (transaction.status !== Transaction.Status.CANCELED) {
            VpnController.onConnectionStateChanged(State.FAILING)
        }
    }

    private fun isLocallyResolved(transaction: Transaction): Boolean {
        return transaction.serverIp.isNullOrEmpty()
    }

    private fun setCryptMode(tunnelMode: AppMode.TunnelMode) {
        vpnAdapter?.setDnscryptMode(tunnelMode)
    }

    // FIXME #305 - All the code related to the tunnel should go through the handler.
    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        /* TODO Check on the Persistent State variable
           Check on updating the values for Package change and for mode change.
           As of now handled manually */
        Log.i(LOG_TAG_VPN,
              "SharedPref value for key: $key with notification action: ${persistentState.notificationAction}, proxy: ${appMode.getProxyMode()}")
        when (key) {
            PersistentState.BRAVE_MODE -> {
                if (vpnAdapter == null) {
                    Log.i(LOG_TAG_VPN,
                          "vpnAdapter is null nothing to do on firewall/dns mode changes")
                    return
                }
                CoroutineScope(Dispatchers.IO).launch {
                    restartVpn(appMode.makeTunnelDataClass())
                }
                val builder = updateNotificationBuilder(this)
                notificationManager.notify(SERVICE_ID, builder.build())
            }
            PersistentState.EXCLUDE_FROM_VPN -> {
                CoroutineScope(Dispatchers.IO).launch {
                    restartVpn(appMode.makeTunnelDataClass())
                }
            }
            PersistentState.LOCAL_BLOCK_LIST -> {
                CoroutineScope(Dispatchers.IO).launch {
                    spawnServerUpdate(appMode.makeTunnelDataClass())
                }
            }
            PersistentState.BACKGROUND_MODE -> {
                if (persistentState.backgroundEnabled) {
                    registerAccessibilityServiceState()
                } else {
                    unregisterAccessibilityServiceState()
                }
            }
            PersistentState.LOCAL_BLOCK_LIST_STAMP -> {
                CoroutineScope(Dispatchers.IO).launch {
                    spawnServerUpdate(appMode.makeTunnelDataClass())
                }
            }
            PersistentState.DNS_CHANGE -> {
                /*
                 * Handles the DNS type changes.
                 * DNS Proxy - Requires restart of the VPN.
                 * DNSCrypt - Set the tunnel with DNSCrypt mode once the live servers size is not 0.
                 * DOH - Overwrites the tunnel values with new values.
                 */
                CoroutineScope(Dispatchers.IO).launch {
                    when (appMode.getDnsType()) {
                        PREF_DNS_MODE_PROXY -> {
                            restartVpn(appMode.makeTunnelDataClass())
                        }
                        PREF_DNS_MODE_DNSCRYPT -> {
                            setCryptMode(appMode.makeTunnelDataClass())
                        }
                        else -> {
                            spawnServerUpdate(appMode.makeTunnelDataClass())
                        }
                    }
                }
            }
            PersistentState.DNS_RELAYS -> {
                CoroutineScope(Dispatchers.IO).launch {
                    setCryptMode(appMode.makeTunnelDataClass())
                }
            }
            PersistentState.ALLOW_BYPASS -> {
                CoroutineScope(Dispatchers.IO).launch {
                    restartVpn(appMode.makeTunnelDataClass())
                }
            }
            PersistentState.PROXY_TYPE -> {
                CoroutineScope(Dispatchers.IO).launch {
                    restartVpn(appMode.makeTunnelDataClass())
                }
            }
            PersistentState.NETWORK -> {
                connectionMonitor?.onUserPreferenceChanged()
            }
            PersistentState.NOTIFICATION_ACTION -> {
                val builder = updateNotificationBuilder(this)
                notificationManager.notify(SERVICE_ID, builder.build())
            }
            PersistentState.APP_STATE -> {
                if (appMode.getAppState() == AppMode.AppState.PAUSE.state) {
                    startCountDownTimer(pauseRemainingTime)
                } else {
                    stopCountTimer()
                }
                CoroutineScope(Dispatchers.IO).launch {
                    restartVpn(appMode.makeTunnelDataClass())
                }
                val builder = updateNotificationBuilder(this)
                notificationManager.notify(SERVICE_ID, builder.build())
            }
        }
    }

    fun signalStopService(userInitiated: Boolean) {
        persistentState.setVpnEnabled(false)
        if (!userInitiated) {
            val vibrationPattern = longArrayOf(1000) // Vibrate for one second.
            // Show revocation warning
            val builder: NotificationCompat.Builder
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                val name: CharSequence = getString(R.string.warning_channel_name)
                val description = getString(R.string.warning_channel_description)
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(WARNING_CHANNEL_ID, name, importance)
                channel.description = description
                channel.enableVibration(true)
                channel.vibrationPattern = vibrationPattern
                notificationManager.createNotificationChannel(channel)
                builder = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            } else {
                builder = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                builder.setVibrate(vibrationPattern)
            }
            val mainActivityIntent = PendingIntent.getActivity(this, 0, Intent(this,
                                                                               HomeScreenActivity::class.java),
                                                               PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(
                resources.getText(R.string.warning_title)).setContentText(
                resources.getText(R.string.notification_content)).setContentIntent(
                mainActivityIntent)
                // Open the main UI if possible.
                .setAutoCancel(true)
            notificationManager.notify(0, builder.build())
        }
        stopVpnAdapter()
        stopSelf()

        Log.i(LOG_TAG_VPN, "Stop Foreground")
        updateQuickSettingsTile()
    }

    private fun updateQuickSettingsTile() {
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            requestListeningState(this, ComponentName(this, BraveTileService::class.java))
        }
    }

    private fun stopVpnAdapter() {
        synchronized(VpnController) {
            if (vpnAdapter != null) {
                vpnAdapter?.close()
                vpnAdapter = null
                Log.i(LOG_TAG_VPN, "Stop vpn adapter/controller")
            }
        }
    }

    // TODO: Now restartvpn invocation is happening from Coroutines, check
    // if new thread creation process is necessary.
    private fun restartVpn(tunnelMode: AppMode.TunnelMode) {
        synchronized(VpnController) {
            Thread({
                       // Attempt seamless hand off as described in the docs for VpnService.Builder.establish().
                       val oldAdapter: GoVpnAdapter? = vpnAdapter
                       vpnAdapter = makeVpnAdapter()
                       oldAdapter?.close()
                       Log.i(LOG_TAG_VPN, "restartVpn? ${vpnAdapter != null}")
                       if (vpnAdapter != null) {
                           vpnAdapter?.start(tunnelMode)
                           handleAdapterChange(tunnelMode)
                       } else {
                           signalStopService(false)
                       }
                   }, "restartvpn-onCommand").start()
        }
    }

    private fun handleAdapterChange(tunnelMode: AppMode.TunnelMode) {
        // Case: Set state to working in case of Firewall mode
        if (tunnelMode.dnsMode == Settings.DNSModeNone && tunnelMode.firewallMode != Settings.BlockModeNone) {
            return VpnController.onConnectionStateChanged(State.WORKING)
        }

        // Case: Update to failing when the tunnel creation failed.
        if (!hasTunnel()) {
            return VpnController.onConnectionStateChanged(State.FAILING)
        }
    }

    fun hasTunnel(): Boolean {
        return vpnAdapter?.hasTunnel() == true
    }

    private fun makeVpnAdapter(): GoVpnAdapter? {
        return GoVpnAdapter.establish(this, get(), get())
    }

    // TODO: #294 - Figure out a way to show users that the device is offline instead of status as failing.
    override fun onNetworkDisconnected() {
        Log.i(LOG_TAG_VPN,
              "#onNetworkDisconnected: Underlying networks set to null, controller-state set to failing")
        setUnderlyingNetworks(null)
        VpnController.onConnectionStateChanged(null)
    }

    override fun onNetworkConnected(networks: LinkedHashSet<Network>?) {
        Log.i(LOG_TAG_VPN, "connecting to networks: $networks")
        setUnderlyingNetworks(networks?.toTypedArray())
    }

    private suspend fun handleVpnLockdownState() {
        if (syncLockdownState()) {
            if (DEBUG) Log.d(LOG_TAG_VPN, "Lockdown mode has switched, restarting vpn")
            restartVpn(appMode.makeTunnelDataClass())
        }
    }

    private fun syncLockdownState(): Boolean {
        if (VERSION.SDK_INT < VERSION_CODES.Q) return false

        val ret = isLockdownEnabled != isLockDownPrevious
        isLockDownPrevious = this.isLockdownEnabled
        return ret
    }


    override fun onDestroy() {
        try {
            unregisterAccessibilityServiceState()
            get<OrbotHelper>().unregisterReceiver()
            persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_VPN, "Unregister receiver error: ${e.message}")
        }

        persistentState.setVpnEnabled(false)

        synchronized(VpnController) {
            Log.w(LOG_TAG_VPN, "Destroying DNS VPN service")
            connectionMonitor?.removeCallBack()
            VpnController.setBraveVpnService(null)
            stopForeground(true)
            if (vpnAdapter != null) {
                signalStopService(false)
            }
        }
    }

    fun startCountDownTimer(timeInMills: Long?) {
        pauseRemainingTime = timeInMills

        if (pauseRemainingTime == null) {
            appMode.setAppState(AppMode.AppState.ACTIVE)
            return
        }

        pauseTimer?.cancel()
        pauseTimer = object : CountDownTimer(pauseRemainingTime!!, 1000) { // asserting the time as there is null check above
            override fun onTick(millisUntilFinished: Long) {
                pauseRemainingTime = millisUntilFinished
                updateTimerLiveData.postValue(millisUntilFinished)
            }

            override fun onFinish() {
                Log.d(LOG_TAG_VPN, "Timer count down timer onFinish.")
                appMode.setAppState(AppMode.AppState.ACTIVE)
                updateTimerLiveData.postValue(0)
            }
        }.start()
    }

    private fun stopCountTimer() {
        pauseTimer?.cancel()
        pauseRemainingTime = DEFAULT_PAUSE_TIMER
        updateTimerLiveData.postValue(0)
    }

}
