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
import android.os.ParcelFileDescriptor
import android.os.SystemClock.elapsedRealtime
import android.service.quicksettings.TileService.requestListeningState
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.automaton.FirewallRules.UID_EVERYBODY
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.net.go.GoIntraListener
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.go.GoVpnAdapter.Companion.establish
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.NotificationHandlerDialog
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.DEFAULT_PAUSE_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_PROXY
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.getThemeAccent
import com.celzero.bravedns.util.Utilities.Companion.isMissingOrInvalidUid
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.google.common.collect.Sets
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import protect.Blocker
import protect.Protector
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random


class BraveVPNService : VpnService(), ConnectionMonitor.NetworkListener, Protector, Blocker,
                        OnSharedPreferenceChangeListener {

    @GuardedBy("vpnController") private var connectionMonitor: ConnectionMonitor? = null
    @GuardedBy("vpnController") private var vpnAdapter: GoVpnAdapter? = null

    companion object {
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

        // IPv4 VPN constants
        private const val IPV4_TEMPLATE: String = "10.111.222.%d"

        private const val IPV4_PREFIX_LENGTH: Int = 24

        // This value must match the hardcoded MTU in outline-go-tun2socks.
        // TODO: Make outline-go-tun2socks's MTU configurable.
        private const val VPN_INTERFACE_MTU: Int = 1500

        private val fakeDnsIp: String = LanIp.DNS.make(IPV4_TEMPLATE)
        private val fakeDns = "$fakeDnsIp:${KnownPorts.DNS_DEFAULT_PORT}"
    }

    private var isLockDownPrevious: Boolean = false

    private val vpnScope = MainScope()

    private lateinit var context: Context

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appMode by inject<AppMode>()
    private val orbotHelper by inject<OrbotHelper>()
    private val ipTracker by inject<IPTracker>()
    private val dnsLogTracker by inject<DNSLogTracker>()
    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()

    @Volatile private var isAccessibilityServiceFunctional: Boolean = false
    @Volatile var accessibilityHearbeatTimestamp: Long = INIT_TIME_MS

    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager
    private lateinit var accessibilityManager: AccessibilityManager
    private var keyguardManager: KeyguardManager? = null

    private var appInfoObserver: Observer<Collection<AppInfo>>? = null

    private var excludedApps: MutableSet<String> = HashSet()

    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    enum class State {
        NEW, WORKING, FAILING, PAUSED
    }

    override fun block(protocol: Int, uid: Long, sourceAddress: String,
                       destAddress: String): Boolean {
        val isBlocked: Boolean
        val first = sourceAddress.split(":")
        val second = destAddress.split(":")
        isBlocked = checkConnectionBlocked(uid.toInt(), protocol, first[0],
                                           first[first.size - 1].toInt(), second[0],
                                           second[second.size - 1].toInt())

        io("handleVpnLockdownState") {
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

        var ipDetails: IPDetails? = null
        var isBlocked = false
        var uid = _uid

        try {

            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                uid = connTracer.getUidQ(protocol, sourceIp, sourcePort, destIp, destPort)
            } else {
                // no-op
            }

            var appStatus = FirewallManager.appStatus(uid)

            ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort,
                                  System.currentTimeMillis(), false, "", protocol)



            if (isDns(destPort) && isVpnDns(destIp)) return false

            // Check if the app is available in cache, else refresh  apps database & cache
            if (FirewallManager.AppStatus.UNKNOWN.equals(appStatus)) {
                refreshDatabase.refreshAppInfoDatabase()

                // check whether to block newly installed app
                if (persistentState.blockNewlyInstalledApp && isConnectionAllowedForNewlyInstalledApp(
                        uid)) {
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = FirewallRuleset.RULE1.id
                    connTrack(ipDetails)
                    delayBeforeResponse()
                    return true
                }
            }

            if (FirewallManager.AppStatus.WHITELISTED.equals(appStatus)) {
                if(isDnsProxied(ipDetails, destPort)) return false

                ipDetails.isBlocked = false
                ipDetails.blockedByRule = FirewallRuleset.RULE8.id
                connTrack(ipDetails)
                Log.i(LOG_TAG_VPN, "whitelisted app, uid: $uid")
                return false
            }

            // Check whether IP rules are set
            if (isIpBlocked(destIp, destPort, protocol)) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = FirewallRuleset.RULE2.id
                connTrack(ipDetails)
                delayBeforeResponse()
                return true
            } // fall-through

            if (persistentState.blockUnknownConnections) {
                if (isMissingOrInvalidUid(uid)) {
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = FirewallRuleset.RULE5.id
                    connTrack(ipDetails)
                    delayBeforeResponse()
                    return true
                }
            }

            // Check whether any rules are set block the App.
            if (FirewallManager.AppStatus.BLOCKED.equals(appStatus)) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = FirewallRuleset.RULE1.id
                connTrack(ipDetails)
                if (persistentState.killAppOnFirewall) {
                    killFirewalledApplication(uid)
                }
                delayBeforeResponse()
                return true
            }

            //Check whether the screen lock is enabled and act based on it
            if (isDeviceLocked()) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = FirewallRuleset.RULE3.id
                connTrack(ipDetails)
                if (DEBUG) Log.d(LOG_TAG_VPN,
                                 "Block connection with delay for screen lock: $uid, $destPort")
                delayBeforeResponse()
                return true
            }

            if (isUdpBlocked(uid, protocol, destPort)) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = FirewallRuleset.RULE6.id
                connTrack(ipDetails)
                delayBeforeResponse()
                if (DEBUG) Log.d(LOG_TAG_VPN, "blockUDPTraffic: $uid, $destPort")
                return true
            }

            // Check whether the background is enabled and act based on it
            if (blockBackgroundData()) {
                // FIXME: 04-12-2020 Removed the app range check for testing.
                var isBgBlock = true

                // check if an app happens to come to the foreground and so is unblocked
                // by exponentially backing-off over a period of remainingTimeMs
                val bgBlockWaitMs = TimeUnit.SECONDS.toMillis(20)
                var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
                var attempt = 0

                while (remainingWaitMs > 0) {
                    if (FirewallManager.isAppForeground(uid, keyguardManager)) {
                        isBgBlock = false
                        break
                    }
                    remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
                    attempt += 1
                }

                // When the app is not in foreground.
                if (isBgBlock) {
                    if (DEBUG) Log.d(LOG_TAG_VPN,
                                     "Background blocked uid: $uid, send response after sleep of ${bgBlockWaitMs + remainingWaitMs}")
                    Thread.sleep((bgBlockWaitMs + remainingWaitMs))
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = FirewallRuleset.RULE4.id
                    connTrack(ipDetails)
                    Log.i(LOG_TAG_VPN, "Background blocked $uid after sleep of: 30 secs")
                    return isBgBlock
                } else {
                    if (DEBUG) Log.d(LOG_TAG_VPN,
                                     "Background allowed for $uid, $destIp, Remaining time: ${10_000 - remainingWaitMs} ")
                }
            }

            // check if all packets on port 53 needs to be trapped
            if (isDnsProxied(ipDetails, destPort)) {
                return false
            }

            // Check whether the destination ip was resolved by the dns set by the user
            if (persistentState.disallowDnsBypass) {
                if (isUnresolvedIp(destIp)) {
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = FirewallRuleset.RULE7.id
                    connTrack(ipDetails)
                    return true
                }
            }

        } catch (iex: Exception) {
            //Returning the blocked as true for all the block call from GO with exceptions.
            //So the apps which are hit in exceptions will have the response sent as blocked(true)
            //after a delayed amount of time.
            /*
             * TODO: Check for the possibility of adding the exceptions on alerts(either in-memory
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
        ipDetails?.isBlocked = false
        connTrack(ipDetails)

        return isBlocked
    }

    private fun isDnsProxied(ipDetails: IPDetails, port: Int): Boolean {
        if (persistentState.preventDnsLeaks && isDns(port)) {
            ipDetails.isBlocked = false
            ipDetails.blockedByRule = FirewallRuleset.RULE9.id
            connTrack(ipDetails)
            return true
        }

        return false
    }

    private fun isConnectionAllowedForNewlyInstalledApp(uid: Int): Boolean {
        val bgBlockWaitMs = TimeUnit.SECONDS.toMillis(20)
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
        var attempt = 0

        while (remainingWaitMs > 0) {
            if (FirewallManager.hasUid(uid) && !FirewallManager.isUidFirewalled(uid)) {
                return false
            }

            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt += 1
        }

        Thread.sleep((bgBlockWaitMs + remainingWaitMs))

        return true
    }

    private fun isIpBlocked(destIp: String, destPort: Int, protocol: Int): Boolean {
        val connectionRules = ConnectionRules(destIp, destPort,
                                              Protocol.getProtocolName(protocol).name)
        return FirewallRules.hasRule(UID_EVERYBODY, connectionRules)
    }

    private fun isUnresolvedIp(ip: String): Boolean {
        val bgBlockWaitMs = TimeUnit.SECONDS.toMillis(20)
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
        var attempt = 0
        val now = System.currentTimeMillis()
        while (remainingWaitMs > 0) {
            val ipLife = dnsLogTracker.ipDomainLookup.getIfPresent(ip)

            ipLife?.let {
                Log.i(LOG_TAG_VPN, "ipLife: $ipLife, destIp: $ip, now: $now, attempt: $attempt")
                if (it.ttl >= now) return false
                // else wait
            }

            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt += 1
        }

        Thread.sleep((bgBlockWaitMs + remainingWaitMs))

        return true
    }

    private fun isUdpBlocked(uid: Int, protocol: Int, port: Int): Boolean {
        val hasUserBlockedUdp = persistentState.udpBlockedSettings
        if (!hasUserBlockedUdp) return false

        val isUdp = protocol == Protocol.UDP.protocolType
        if (!isUdp) return false

        val isNtpFromSystemApp = KnownPorts.isNtp(port) && FirewallManager.isUidSystemApp(uid)
        if (isNtpFromSystemApp) return false

        return true
    }

    private fun isVpnDns(ip: String): Boolean {
        return ip == fakeDnsIp
    }

    private fun isDns(port: Int): Boolean {
        return KnownPorts.isDns(port)
    }

    // Modified the logic of "Block connections when screen is locked".
    // Earlier the screen lock is detected with receiver received for Action user_present/screen_off.
    // Now the code only checks whether the KeyguardManager#isKeyguardLocked() is true/false.
    // if isKeyguardLocked() is true, the connections will be blocked.
    private fun isDeviceLocked(): Boolean {
        if (!persistentState.blockWhenDeviceLocked) return false

        if (keyguardManager == null) {
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        return (keyguardManager?.isKeyguardLocked == true)
    }

    private fun blockBackgroundData(): Boolean {
        if (!persistentState.blockAppWhenBackground) return false
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
        persistentState.blockAppWhenBackground = false
        showAccessibilityStoppedNotification()
    }

    private fun showAccessibilityStoppedNotification() {
        if (DEBUG) Log.d(LOG_TAG_VPN, "App not in use failure, show notification")

        val intent = Intent(this, NotificationHandlerDialog::class.java)
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

    private fun notifyEmptyFirewallRules() {
        val intent = Intent(this, HomeScreenActivity::class.java)
        val mainActivityIntent = PendingIntent.getActivity(this, 0, intent,
                                                           PendingIntent.FLAG_UPDATE_CURRENT)
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            val name: CharSequence = NOTIF_LOAD_RULES_FAIL
            val description = resources.getString(R.string.rules_load_failure_heading)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_LOAD_RULES_FAIL, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
        }
        var builder: NotificationCompat.Builder =
                NotificationCompat.Builder(this,NOTIF_LOAD_RULES_FAIL)

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

    private fun delayBeforeResponse() {
        Thread.sleep(Constants.DELAY_FIREWALL_RESPONSE_MS)
    }

    // ref: https://stackoverflow.com/a/363692
    private val baseWaitMs = TimeUnit.MILLISECONDS.toMillis(50)

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
        io("killBgApps") {
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

    override fun getResolvers(): String {
        // TODO: remove stub, unused
        throw UnsupportedOperationException("stub method")
    }

    fun isOn(): Boolean {
        return vpnAdapter != null
    }

    private suspend fun newBuilder(): Builder {
        var builder = Builder()

        if (!VpnController.isVpnLockdown() && persistentState.allowBypass) {
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
            if (!VpnController.isVpnLockdown() && appMode.isHttpProxyTypeEnabled()) {
                getHttpProxyInfo()?.let { builder.setHttpProxy(it) }
            }
        }

        try {
            if (!VpnController.isVpnLockdown() && isAppPaused()) {
                val nonFirewalledApps = FirewallManager.getNonFirewalledAppsPackageNames()
                Log.i(LOG_TAG_VPN,
                      "app is in pause state, exclude all the non firewalled apps, size: ${nonFirewalledApps.size}")
                nonFirewalledApps.forEach {
                    builder.addDisallowedApplication(it.packageInfo)
                }
                builder = builder.addDisallowedApplication(this.packageName)
                return builder
            }

            val excludedApps = excludedApps.toList()
            if (appMode.getFirewallMode().isFirewallSinkMode()) {
                for (packageName in excludedApps) {
                    builder = builder.addAllowedApplication(packageName)
                }
            } else {
                /* The check for the apps to exclude from VPN or not.
                   In case of lockdown mode, the excluded apps wont able to connected. so
                   not including the apps in the excluded list if the lockdown mode is
                   enabled. */
                if (!VpnController.isVpnLockdown()) {
                    excludedApps.forEach {
                        builder = builder.addDisallowedApplication(it)
                        Log.i(LOG_TAG_VPN, "Excluded package - $it")
                    }
                } else {
                    Log.w(LOG_TAG_VPN,
                          "Vpn in lockdown mode, not excluding the apps added in exclude list")
                }
                builder = builder.addDisallowedApplication(this.packageName)
            }
            if (appMode.getProxyMode().isCustomSocks5Proxy()) {
                // For Socks5 if there is a app selected, add that app in excluded list
                val socks5ProxyEndpoint = appMode.getConnectedSocks5Proxy()
                val appName = socks5ProxyEndpoint?.proxyAppName
                Log.i(LOG_TAG_VPN,
                      "Proxy mode - Socks5 is selected - $socks5ProxyEndpoint, with app name - $appName")
                if (appName?.equals(getString(
                        R.string.settings_app_list_default_app)) == false && isExcludePossible(
                        appName, getString(R.string.socks5_proxy_toast_parameter))) {
                    builder = builder.addDisallowedApplication(appName)
                }
            }

            if (appMode.getProxyMode().isOrbotProxy() && isExcludePossible(
                    getString(R.string.orbot), getString(R.string.orbot_toast_parameter))) {
                builder = builder.addDisallowedApplication(OrbotHelper.ORBOT_PACKAGE_NAME)
            }

            if (appMode.isDnsProxyActive()) {
                // For DNS proxy mode, if any app is set then exclude the application from the list
                val dnsProxyEndpoint = appMode.getConnectedProxyDetails()
                val appName = dnsProxyEndpoint.proxyAppName
                Log.i(LOG_TAG_VPN, "DNS Proxy mode is set with the app name as $appName")
                if (appName?.equals(getString(
                        R.string.settings_app_list_default_app)) == false && isExcludePossible(
                        appName, getString(R.string.dns_proxy_toast_parameter))) {
                    builder = builder.addDisallowedApplication(appName)
                }
            }

        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(LOG_TAG_VPN, "cannot exclude the dns proxy app", e)
        }
        return builder
    }

    private fun isExcludePossible(appName: String?, message: String): Boolean {
        if (VpnController.isVpnLockdown()) {
            Log.i(LOG_TAG_VPN, "Vpn in lockdown mode")
            CoroutineScope(Dispatchers.Main).launch {
                VpnController.getBraveVpnService()?.let {
                    showToastUiCentered(it,
                                        getString(R.string.dns_proxy_connection_failure_lockdown,
                                                  appName, message), Toast.LENGTH_SHORT)
                }
            }
            return false
        }
        return (appName?.equals(getString(R.string.settings_app_list_default_app)) == false)
    }


    override fun onCreate() {
        context = this
        connTracer = ConnectionTracer(context)
        VpnController.setBraveVpnService(this)

        notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        activityManager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        accessibilityManager = context.getSystemService(
            ACCESSIBILITY_SERVICE) as AccessibilityManager
        keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (persistentState.blockAppWhenBackground) {
            registerAccessibilityServiceState()
        }

        initAppInfoObserver()
    }

    private fun initAppInfoObserver() {
        if (appInfoObserver != null) return

        appInfoObserver = Observer<Collection<AppInfo>> { t ->
            val latestExcludedApps = t.filter { a -> a.isExcluded }.map { i -> i.packageInfo }
            if (Sets.symmetricDifference(excludedApps, latestExcludedApps.toSet()).size != 0) {
                Log.i(LOG_TAG_VPN, "changes in excluded apps list: restart vpn")
                with(excludedApps) {
                    clear()
                    addAll(latestExcludedApps.toSet())
                }
                io("exclude-apps") {
                    val service = context as BraveVPNService
                    restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                }
            }
        }
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
            AppMode.BraveMode.DNS -> context.resources.getString(
                R.string.dns_mode_notification_title)
            AppMode.BraveMode.FIREWALL -> context.resources.getString(
                R.string.firewall_mode_notification_title)
            AppMode.BraveMode.DNS_FIREWALL -> context.resources.getString(
                R.string.hybrid_mode_notification_title)
            else -> context.resources.getString(R.string.notification_title)
        }

        if (isAppPaused()) {
            contentTitle = context.resources.getString(R.string.pause_mode_notification_title)
        }

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            mainActivityIntent)

        // New action button options in the notification
        // 1. Pause / Resume, Stop action button.
        // 2. RethinkDNS modes (dns & dns+firewall mode)
        // 3. No action button.
        if (DEBUG) Log.d(LOG_TAG_VPN, "Notification - ${persistentState.notificationActionType}")
        builder.color = ContextCompat.getColor(this, getThemeAccent(this))
        when (persistentState.notificationActionType) {
            Constants.NOTIFICATION_ACTION_STOP -> {
                // Add the action based on AppState (PAUSE/ACTIVE)
                val openIntent1 = makeVpnIntent(context, NOTIF_ACTION_MODE_STOP,
                                                Constants.NOTIF_ACTION_STOP_VPN)
                val notificationAction1 = NotificationCompat.Action(0, context.resources.getString(
                    R.string.notification_action_stop_vpn), openIntent1)
                builder.addAction(notificationAction1)

                if (isAppPaused()) {
                    val openIntent2 = makeVpnIntent(context, NOTIF_ACTION_MODE_RESUME,
                                                    Constants.NOTIF_ACTION_RESUME_VPN)
                    val notificationAction2 = NotificationCompat.Action(0,
                                                                        context.resources.getString(
                                                                            R.string.notification_action_resume_vpn),
                                                                        openIntent2)
                    builder.addAction(notificationAction2)
                } else {
                    val openIntent2 = makeVpnIntent(context, NOTIF_ACTION_MODE_PAUSE,
                                                    Constants.NOTIF_ACTION_PAUSE_VPN)
                    val notificationAction2 = NotificationCompat.Action(0,
                                                                        context.resources.getString(
                                                                            R.string.notification_action_pause_vpn),
                                                                        openIntent2)
                    builder.addAction(notificationAction2)
                }
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
        VpnController.onConnectionStateChanged(State.NEW)

        vpnScope.launch {

            FirewallManager.loadAppFirewallRules()

            if (FirewallManager.getTotalApps() <= 0) {
                notifyEmptyFirewallRules()
            }
            // Initialize the value whenever the vpn is started.
            accessibilityHearbeatTimestamp = INIT_TIME_MS

            startOrbotAsyncIfNeeded()

            if (DEBUG) Log.d(LOG_TAG_VPN,
                             "Registering the shared pref changes with the vpn service")

            VpnController.mutex.withLock {
                // In case if the service already running(connectionMonitor will not be null)
                // and onStartCommand is called then no need to process the below initializations
                // instead call spawnServerUpdate()
                // spawnServerUpdate() - Will overwrite the tunnel values with new values.
                if (connectionMonitor != null) {
                    io("updateServerConnection_onStartCommand") {
                        val service = context as BraveVPNService
                        spawnDohUpdate(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                    }
                    return@launch
                }

                // Register for ConnectivityManager
                // FIXME #200 - Move it to Koin
                // TODO: find a better way to instantiate ConnectionMonitor
                connectionMonitor = ConnectionMonitor(context, context as BraveVPNService)
            }

            observeAppInfos()
            excludedApps = FirewallManager.getExcludedApps().toMutableSet()

            val service = context as BraveVPNService
            io("onStart_restartVpn") {
                restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
            }
            persistentState.sharedPreferences.registerOnSharedPreferenceChangeListener(service)

            // Mark this as a foreground service.  This is normally done to ensure that the service
            // survives under memory pressure.  Since this is a VPN service, it is presumably protected
            // anyway, but the foreground service mechanism allows us to set a persistent notification,
            // which helps users understand what's going on, and return to the app if they want.
            val builder = updateNotificationBuilder(context)
            Log.i(LOG_TAG_VPN, "onStart command: start as foreground service. ")
            startForeground(SERVICE_ID, builder.build())

            persistentState.setVpnEnabled(true)

            updateQuickSettingsTile()
            connectionMonitor?.onVpnStarted()
        }
        return Service.START_REDELIVER_INTENT
    }

    private fun startOrbotAsyncIfNeeded() {
        io("orbot_enabled") {
            if (appMode.isOrbotProxyEnabled()) {
                orbotHelper.startOrbot(appMode.getProxyType())
            }
        }
    }

    private fun observeAppInfos() {
        appInfoObserver?.let { it -> FirewallManager.getApplistObserver().observeForever(it) }
    }

    private fun unobserveAppInfos() {
        appInfoObserver?.let { it -> FirewallManager.getApplistObserver().removeObserver(it) }
    }

    private fun registerAccessibilityServiceState() {
        accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { b ->
            if (!b) {
                persistentState.blockAppWhenBackground = false
                handleAccessibilityFailure()
            }
        }
    }

    private fun unregisterAccessibilityServiceState() {
        accessibilityListener?.let {
            accessibilityManager.removeAccessibilityStateChangeListener(it)
        }
    }

    private suspend fun spawnDohUpdate(tunnelOptions: AppMode.TunnelOptions) {
        VpnController.mutex.withLock {
            // Connection monitor can be null if onDestroy() of service
            // is called, in that case no need to call updateServerConnection()
            if (connectionMonitor != null) {
                vpnAdapter?.updateDohUrl(tunnelOptions)
                handleVpnAdapterChange(tunnelOptions) // ***
            } else {
                Log.w(LOG_TAG_VPN, "cannot spawn sever update, no connection monitor")
            }
        }
    }

    private suspend fun spawnDnscryptUpdate(tunnelOptions: AppMode.TunnelOptions) {
        VpnController.mutex.withLock {
            vpnAdapter?.setDnscryptMode(tunnelOptions)
        }
    }

    // FIXME #305 - All the code related to the tunnel should go through the handler.
    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        /* TODO Check on the Persistent State variable
           Check on updating the values for Package change and for mode change.
           As of now handled manually */
        Log.i(LOG_TAG_VPN,
              "SharedPref value for key: $key with notification action: ${persistentState.notificationActionType}, proxy: ${appMode.getProxyMode()}")
        when (key) {
            PersistentState.BRAVE_MODE -> {
                io("restartVpn_braveMode") {
                    val service = context as BraveVPNService
                    restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                }
                val builder = updateNotificationBuilder(this)
                notificationManager.notify(SERVICE_ID, builder.build())
            }
            PersistentState.LOCAL_BLOCK_LIST -> {
                io("updateServerConnection_blocklist") {
                    val service = context as BraveVPNService
                    spawnDohUpdate(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                }
            }
            PersistentState.BACKGROUND_MODE -> {
                if (persistentState.blockAppWhenBackground) {
                    registerAccessibilityServiceState()
                } else {
                    unregisterAccessibilityServiceState()
                }
            }
            PersistentState.LOCAL_BLOCK_LIST_STAMP -> { // update tunnel on local blocklist stamp change
                io("update_braveDnsStamp") {
                    vpnAdapter?.setBraveDnsStamp()
                }
            }
            PersistentState.REMOTE_BLOCK_LIST_STAMP -> { // update tunnel on remote blocklist stamp change.
                io("updateServerConnection_blocklist") {
                    val service = context as BraveVPNService
                    spawnDohUpdate(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                }
            }
            PersistentState.DNS_CHANGE -> {
                /*
                 * Handles the DNS type changes.
                 * DNS Proxy - Requires restart of the VPN.
                 * DNSCrypt - Set the tunnel with DNSCrypt mode once the live servers size is not 0.
                 * DOH - Overwrites the tunnel values with new values.
                 */
                io("updateServerConnection_dnschange") {
                    when (appMode.getDnsType()) {
                        PREF_DNS_MODE_PROXY -> {
                            val service = context as BraveVPNService
                            restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                        }
                        PREF_DNS_MODE_DNSCRYPT -> {
                            val service = context as BraveVPNService
                            spawnDnscryptUpdate(
                                appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                        }
                        PREF_DNS_MODE_DOH -> {
                            val service = context as BraveVPNService
                            spawnDohUpdate(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                        }
                        else -> {
                            // no-op
                        }
                    }
                }
            }
            PersistentState.DNS_RELAYS -> {
                io("updateTunnel_dnscrypt") {
                    val service = context as BraveVPNService
                    spawnDnscryptUpdate(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                }
            }
            PersistentState.ALLOW_BYPASS -> {
                io("restartVpn_allowBypass") {
                    val service = context as BraveVPNService
                    restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                }
            }
            PersistentState.PROXY_TYPE -> {
                io("restartVpn_proxy") {
                    val service = context as BraveVPNService
                    restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
                }
            }
            PersistentState.NETWORK -> {
                connectionMonitor?.onUserPreferenceChanged()
            }
            PersistentState.NOTIFICATION_ACTION -> {
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
    }

    private fun updateQuickSettingsTile() {
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            requestListeningState(this, ComponentName(this, BraveTileService::class.java))
        }
    }

    private fun stopVpnAdapter() {
        io("stop_vpnadapter") {
            VpnController.mutex.withLock {
                if (vpnAdapter != null) {
                    vpnAdapter?.close()
                    vpnAdapter = null
                    Log.i(LOG_TAG_VPN, "Stop vpn adapter/controller")
                }
            }
        }
    }

    private suspend fun restartVpn(tunnelOptions: AppMode.TunnelOptions) {
        VpnController.mutex.withLock {
            // Attempt seamless hand off as described in the docs for VpnService.Builder.establish().
            val oldAdapter: GoVpnAdapter? = vpnAdapter
            vpnAdapter = makeVpnAdapter()
            oldAdapter?.close()
            Log.i(LOG_TAG_VPN, "restartVpn? ${vpnAdapter != null}")
            if (isOn()) {
                vpnAdapter?.start(tunnelOptions)
                handleVpnAdapterChange(tunnelOptions)
            } else {
                Log.w(LOG_TAG_VPN, "failed to restart vpn")
            }
        }
    }

    private fun handleVpnAdapterChange(tunnelOptions: AppMode.TunnelOptions) {
        // Case: Set state to working in case of Firewall mode
        if (AppMode.isFirewallActive(tunnelOptions)) {
            return VpnController.onConnectionStateChanged(State.WORKING)
        }

        // edge-case: mark connection-state as 'failing' when underlying tunnel does not exist
        // TODO: like Intra, call VpnController#stop instead? (see VpnController#onStartComplete)
        if (!hasTunnel()) {
            return VpnController.onConnectionStateChanged(State.FAILING)
        }
    }

    fun hasTunnel(): Boolean {
        return vpnAdapter?.hasTunnel() == true
    }

    private suspend fun makeVpnAdapter(): GoVpnAdapter? {
        val tunFd = establishVpn()
        return establish(tunFd)
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
            val service = context as BraveVPNService
            restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
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
            vpnScope.cancel("VpnService onDestroy")
            unobserveAppInfos()
            unregisterAccessibilityServiceState()
            orbotHelper.unregisterReceiver()
            persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_VPN, "Unregister receiver error: ${e.message}")
        }

        persistentState.setVpnEnabled(false)
        updateQuickSettingsTile()
        stopPauseTimer()

        Log.w(LOG_TAG_VPN, "Destroying DNS VPN service")
        connectionMonitor?.removeCallBack()
        VpnController.setBraveVpnService(null)
        stopForeground(true)
        if (isOn()) {
            signalStopService(false)
        }
    }

    private fun startPauseTimer() {
        PauseTimer.start(DEFAULT_PAUSE_TIME_MS)
    }

    private fun stopPauseTimer() {
        PauseTimer.stop()
    }

    fun incrementPauseTimer(timeInMills: Long) {
        PauseTimer.increment(timeInMills)
    }

    fun decrementPauseTimer(timeInMills: Long) {
        PauseTimer.decrement(timeInMills)
    }

    fun getPauseCountDownObserver(): MutableLiveData<Long>? {
        return PauseTimer.getPauseCountDownObserver()
    }

    private fun isAppPaused(): Boolean {
        return VpnController.isAppPaused()
    }

    fun pauseApp() {
        VpnController.onConnectionStateChanged(State.PAUSED)
        startPauseTimer()
        handleVpnServiceOnAppStateChange()
    }

    fun resumeApp() {
        VpnController.onConnectionStateChanged(State.WORKING)
        stopPauseTimer()
        handleVpnServiceOnAppStateChange()
    }

    private fun handleVpnServiceOnAppStateChange() {
        io("restartVpn_appState") {
            val service = context as BraveVPNService
            restartVpn(appMode.newTunnelMode(service, GoIntraListener, fakeDns))
        }
        val builder = updateNotificationBuilder(this)
        notificationManager.notify(SERVICE_ID, builder.build())
    }

    // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
// assign the following values to the final byte of an address within a subnet.
// Value of the final byte, to be substituted into the template.
    private enum class LanIp(private val value: Int) {
        GATEWAY(1), ROUTER(2), DNS(3);

        fun make(template: String): String {
            return String.format(Locale.ROOT, template, value)
        }
    }

    private suspend fun establishVpn(): ParcelFileDescriptor? {
        try {
            val builder: VpnService.Builder = newBuilder().setSession("RethinkDNS").setMtu(
                VPN_INTERFACE_MTU).addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
            if (appMode.getBraveMode().isDnsMode()) {
                builder.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
            } else if (appMode.getBraveMode().isFirewallMode()) {
                builder.addRoute(Constants.UNSPECIFIED_IP, Constants.UNSPECIFIED_PORT)
            } else {
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
                builder.addRoute(Constants.UNSPECIFIED_IP, Constants.UNSPECIFIED_PORT)
            }
            return builder.establish()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            return null
        }
    }

    private fun io(s: String, f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch(CoroutineName(s)) {
            f()
        }
    }

}
