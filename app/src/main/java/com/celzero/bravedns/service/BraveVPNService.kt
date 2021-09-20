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
import com.celzero.bravedns.automaton.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.automaton.FirewallRules.UID_EVERYBODY
import com.celzero.bravedns.data.AppConfig
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
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.getThemeAccent
import com.celzero.bravedns.util.Utilities.Companion.isAtleastO
import com.celzero.bravedns.util.Utilities.Companion.isAtleastQ
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
import java.util.concurrent.atomic.AtomicBoolean
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

        // maximum time delay before sending block connection response
        val DELAY_FIREWALL_RESPONSE_MS: Long = TimeUnit.SECONDS.toMillis(30)

        // notification request codes
        private const val NOTIF_ACTION_MODE_RESUME = 98
        private const val NOTIF_ACTION_MODE_PAUSE = 99
        private const val NOTIF_ACTION_MODE_STOP = 100
        private const val NOTIF_ACTION_MODE_DNS_ONLY = 101
        private const val NOTIF_ACTION_MODE_DNS_FIREWALL = 102

        private const val NOTIF_ID_LOAD_RULES_FAIL = 103

        private const val NOTIF_ID_ACCESSIBILITY_FAILURE = 104

        // IPv4 VPN constants
        private const val IPV4_TEMPLATE: String = "10.111.222.%d"

        private const val IPV4_PREFIX_LENGTH: Int = 24

        // This value must match the hardcoded MTU in outline-go-tun2socks.
        // TODO: Make outline-go-tun2socks's MTU configurable.
        private const val VPN_INTERFACE_MTU: Int = 1500

        private val fakeDnsIp: String = LanIp.DNS.make(IPV4_TEMPLATE)
        private val fakeDns = "$fakeDnsIp:${KnownPorts.DNS_PORT}"
    }

    private var isLockDownPrevious: Boolean = false

    private val vpnScope = MainScope()

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val ipTracker by inject<IPTracker>()
    private val dnsLogTracker by inject<DNSLogTracker>()
    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()

    @Volatile private var isAccessibilityServiceFunctional: Boolean = false
    @Volatile var accessibilityHearbeatTimestamp: Long = INIT_TIME_MS
    var settingUpOrbot: AtomicBoolean = AtomicBoolean(false)

    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager
    private lateinit var accessibilityManager: AccessibilityManager
    private var keyguardManager: KeyguardManager? = null

    private lateinit var appInfoObserver: Observer<Collection<AppInfo>>
    private lateinit var orbotStartStatusObserver: Observer<Boolean>

    private var excludedApps: MutableSet<String> = mutableSetOf()

    private var userInitiatedStop: Boolean = false

    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    enum class State {
        NEW, WORKING, FAILING, PAUSED
    }

    override fun block(protocol: Int, _uid: Long, sourceAddress: String,
                       destAddress: String): Boolean {

        handleVpnLockdownStateAsync()

        val first = sourceAddress.split(":")
        val second = destAddress.split(":")

        val srcIp = first[0]
        val srcPort = first[first.size - 1].toInt()
        val dstIp = second[0]
        val dstPort = second[second.size - 1].toInt()

        val uid = if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            connTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort)
        } else {
            _uid.toInt() // uid must have been retrieved from procfs by the caller
        }

        // skip the block-ceremony for dns conns
        if (isDns(dstPort) && isVpnDns(dstIp)) return false

        // FIXME: replace currentTimeMillis with elapsed-time
        val connInfo = IPDetails(uid, srcIp, srcPort, dstIp, dstPort,
                                 System.currentTimeMillis(), /*blocked?*/ false, /*rule*/ "",
                                 protocol)

        val rule = firewall(connInfo)
        val blocked = FirewallRuleset.ground(rule)

        connInfo.blockedByRule = rule.id
        connInfo.isBlocked = blocked

        if (DEBUG) Log.i(LOG_TAG_VPN, "firewall-rule $rule on conn $connInfo")

        connTrack(connInfo)

        if (FirewallRuleset.stall(rule)) {
            stallResponse()
        }

        return blocked
    }

    /**
     * Checks if incoming connection is blocked by any user-set firewall rule
     */
    private fun firewall(connInfo: IPDetails): FirewallRuleset {
        try {

            val uid = connInfo.uid
            val appStatus = FirewallManager.appStatus(uid)

            if (settingUpOrbot.get()) {
                if (OrbotHelper.ORBOT_PACKAGE_NAME == FirewallManager.getPackageNameByUid(uid)) {
                    return FirewallRuleset.RULE9B
                } else {
                    // fall-through
                }
            }

            if (allowOrbot(uid)) {
                return FirewallRuleset.RULE9B
            }

            // if the app is new (ie unknown), refresh the db
            if (FirewallManager.AppStatus.UNKNOWN == appStatus) {
                io("dbRefresh") {
                    refreshDatabase.handleNewlyConnectedApp(uid)
                }
                if (newAppBlocked(uid)) {
                    return FirewallRuleset.RULE1B
                }
            }

            if (FirewallManager.AppStatus.WHITELISTED == appStatus) {
                return if (dnsProxied(connInfo.destPort)) {
                    FirewallRuleset.RULE9
                } else {
                    FirewallRuleset.RULE8
                }
            }

            if (ipBlocked(connInfo.destIP, connInfo.destPort, connInfo.protocol)) {
                return FirewallRuleset.RULE2
            }

            if (unknownAppBlocked(uid)) {
                return FirewallRuleset.RULE5
            }

            if (FirewallManager.AppStatus.BLOCKED == appStatus) {
                if (persistentState.killAppOnFirewall) {
                    killFirewalledApplication(uid)
                }
                return FirewallRuleset.RULE1
            }

            if (deviceLocked()) {
                return FirewallRuleset.RULE3
            }

            if (udpBlocked(uid, connInfo.protocol, connInfo.destPort)) {
                return FirewallRuleset.RULE6
            }

            if (blockBackgroundData(uid)) {
                return FirewallRuleset.RULE4
            }

            // if all packets on port 53 needs to be trapped
            if (dnsProxied(connInfo.destPort)) {
                return FirewallRuleset.RULE9
            }

            // whether the destination ip was resolved by the dns set by the user
            if (dnsBypassed(connInfo.destIP)) {
                return FirewallRuleset.RULE7
            }

        } catch (iex: Exception) {
            // TODO: show alerts to user on such exceptions, in a separate ui?
            Log.e(LOG_TAG_VPN, "err blocking conn, block anyway", iex)
            return FirewallRuleset.RULE1C
        }

        return FirewallRuleset.RULE0
    }

    private fun allowOrbot(uid: Int): Boolean {
        return settingUpOrbot.get() && OrbotHelper.ORBOT_PACKAGE_NAME == FirewallManager.getPackageNameByUid(uid)
    }

    private fun dnsProxied(port: Int): Boolean {
        return (appConfig.getBraveMode().isDnsFirewallMode() && persistentState.preventDnsLeaks && isDns(
            port))
    }

    private fun dnsBypassed(destIp: String): Boolean {
        return if (!persistentState.disallowDnsBypass || !appConfig.getBraveMode().isDnsFirewallMode()) {
            false
        } else {
            unresolvedIp(destIp)
        }
    }

    private fun waitAndCheckIfNewAppAllowed(uid: Int): Boolean {
        val allowed = testWithBackoff {
            FirewallManager.hasUid(uid) && !FirewallManager.isUidFirewalled(uid)
        }
        return !allowed
    }

    private fun newAppBlocked(uid: Int): Boolean {
        return if (!persistentState.blockNewlyInstalledApp) {
            false
        } else {
            waitAndCheckIfNewAppAllowed(uid)
        }
    }

    private fun ipBlocked(destIp: String, destPort: Int, protocol: Int): Boolean {
        val connectionRules = ConnectionRules(destIp, destPort,
                                              Protocol.getProtocolName(protocol).name)
        return FirewallRules.hasRule(UID_EVERYBODY, connectionRules)
    }

    private fun unknownAppBlocked(uid: Int): Boolean {
        return if (!persistentState.blockUnknownConnections) {
            false
        } else {
            isMissingOrInvalidUid(uid)
        }
    }

    private fun testWithBackoff(stallSec: Long = 20, waitSec: Long = 10,
                                test: () -> Boolean): Boolean {
        val minWaitMs = TimeUnit.SECONDS.toMillis(stallSec)
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(waitSec)
        var attempt = 0
        while (remainingWaitMs > 0) {
            if (test()) return true

            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt += 1
        }

        Thread.sleep(minWaitMs + remainingWaitMs)

        return false
    }

    private fun unresolvedIp(ip: String): Boolean {
        val resolvedIp = testWithBackoff {
            val now = System.currentTimeMillis()
            val ttl = dnsLogTracker.ipDomainLookup.getIfPresent(ip)?.ttl ?: Long.MIN_VALUE
            ttl >= now
        }

        return !resolvedIp
    }

    private fun udpBlocked(uid: Int, protocol: Int, port: Int): Boolean {
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
    private fun deviceLocked(): Boolean {
        if (!persistentState.blockWhenDeviceLocked) return false

        if (keyguardManager == null) {
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        return (keyguardManager?.isKeyguardLocked == true)
    }

    private fun blockBackgroundData(uid: Int): Boolean {
        if (!persistentState.blockAppWhenBackground) return false
        if (!accessibilityServiceFunctional()) {
            Log.w(LOG_TAG_VPN, "accessibility service not functional, disable bg-block")
            handleAccessibilityFailure()
            return false
        }

        val allowed = testWithBackoff {
            FirewallManager.isAppForeground(uid, keyguardManager)
        }

        return !allowed
    }

    private fun handleAccessibilityFailure() {
        // Disable app not in use behaviour when the accessibility failure is detected.
        persistentState.blockAppWhenBackground = false
        showAccessibilityStoppedNotification()
    }

    private fun showAccessibilityStoppedNotification() {
        Log.i(LOG_TAG_VPN, "app not in use failure, show notification")

        val intent = Intent(this, NotificationHandlerDialog::class.java)
        intent.putExtra(NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME,
                        NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE)

        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
                                                      PendingIntent.FLAG_UPDATE_CURRENT)
        var builder: NotificationCompat.Builder
        if (isAtleastO()) {
            val name: CharSequence = getString(R.string.notif_channel_firewall_alerts)
            val description = this.resources.getString(R.string.notif_channel_desc_firewall_alerts)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
        } else {
            builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
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

        notificationManager.notify(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, NOTIF_ID_ACCESSIBILITY_FAILURE,
                                   builder.build())
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
        if (isAtleastO()) {
            val name: CharSequence = getString(R.string.notif_channel_firewall_alerts)
            val description = resources.getString(R.string.notif_channel_desc_firewall_alerts)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS,
                                              name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
        }
        var builder: NotificationCompat.Builder = NotificationCompat.Builder(this,
                                                                             FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS)

        val contentTitle = resources.getString(R.string.rules_load_failure_heading)
        val contentText = resources.getString(R.string.rules_load_failure_desc)
        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            mainActivityIntent).setContentText(contentText)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(this, getThemeAccent(this))
        val openIntent = makeVpnIntent(NOTIF_ID_LOAD_RULES_FAIL,
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
        notificationManager.notify(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, NOTIF_ID_LOAD_RULES_FAIL,
                                   builder.build())
    }

    private fun stallResponse() {
        Thread.sleep(DELAY_FIREWALL_RESPONSE_MS)
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
    private fun connTrack(info: IPDetails?) {
        ipTracker.recordTransaction(info)
    }

    override fun getResolvers(): String {
        // TODO: remove stub, unused
        throw UnsupportedOperationException("stub method")
    }

    private suspend fun newBuilder(): Builder {
        var builder = Builder()

        if (!VpnController.isVpnLockdown() && persistentState.allowBypass) {
            Log.i(LOG_TAG_VPN, "allow apps to bypass vpn on-demand")
            builder = builder.allowBypass()
        }

        // underlying networks is set to null, which prompts Android to set it to whatever is the
        // current active network. Later, ConnectionMonitor#onVpnStarted, depending on user
        // chosen preferences, sets appropriate underlying network/s.
        builder.setUnderlyingNetworks(null)

        // Fix - Cloud Backups were failing thinking that the VPN connection is metered.
        // The below code will fix that.
        if (isAtleastQ()) {
            builder.setMetered(false)
        }

        // re-hydrate exclude-apps incase it has changed in the interim
        excludedApps = FirewallManager.getExcludedApps()

        if (isAtleastQ()) {
            if (appConfig.canEnableProxy() && appConfig.isCustomHttpProxyEnabled()) {
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

            if (appConfig.getFirewallMode().isFirewallSinkMode()) {
                for (packageName in excludedApps) {
                    builder = builder.addAllowedApplication(packageName)
                }
            } else {
                // ignore excluded-apps settings when vpn is lockdown because
                // those apps would lose all internet connectivity, otherwise
                if (!VpnController.isVpnLockdown()) {
                    excludedApps.forEach {
                        builder = builder.addDisallowedApplication(it)
                        Log.i(LOG_TAG_VPN, "Excluded package - $it")
                    }
                } else {
                    Log.w(LOG_TAG_VPN, "vpn is lockdown, ignoring exclude-apps list")
                }
                builder = builder.addDisallowedApplication(this.packageName)
            }
            if (appConfig.isCustomSocks5Enabled()) {
                // For Socks5 if there is a app selected, add that app in excluded list
                val socks5ProxyEndpoint = appConfig.getConnectedSocks5Proxy()
                val appName = socks5ProxyEndpoint?.proxyAppName
                Log.i(LOG_TAG_VPN,
                      "Proxy mode - Socks5 is selected - $socks5ProxyEndpoint, with app name - $appName")
                if (appName?.equals(getString(
                        R.string.settings_app_list_default_app)) == false && isExcludePossible(
                        appName, getString(R.string.socks5_proxy_toast_parameter))) {
                    builder = builder.addDisallowedApplication(appName)
                }
            }

            if (appConfig.isOrbotProxyEnabled() && isExcludePossible(
                    getString(R.string.orbot), getString(R.string.orbot_toast_parameter))) {
                builder = builder.addDisallowedApplication(OrbotHelper.ORBOT_PACKAGE_NAME)
            }

            if (appConfig.isDnsProxyActive()) {
                // For DNS proxy mode, if any app is set then exclude the application from the list
                val dnsProxyEndpoint = appConfig.getConnectedProxyDetails()
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
        if (!VpnController.isVpnLockdown()) return (appName?.equals(
            getString(R.string.settings_app_list_default_app)) == false)

        ui {
            showToastUiCentered(this,
                                getString(R.string.dns_proxy_connection_failure_lockdown, appName,
                                          message), Toast.LENGTH_SHORT)
        }
        return false
    }


    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        VpnController.onVpnCreated(this)

        notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        activityManager = this.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        accessibilityManager = this.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        keyguardManager = this.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (persistentState.blockAppWhenBackground) {
            registerAccessibilityServiceState()
        }

        observeChanges()

    }

    private fun observeChanges() {
        appInfoObserver = makeAppInfoObserver()
        FirewallManager.getApplistObserver().observeForever(appInfoObserver)
        persistentState.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        orbotStartStatusObserver = makeOrbotStartStatusObserver()
        persistentState.orbotConnectionStatus.observeForever(orbotStartStatusObserver)
        Log.i(LOG_TAG_VPN, "observe pref and app list changes")
    }

    private fun makeAppInfoObserver(): Observer<Collection<AppInfo>> {
        return Observer<Collection<AppInfo>> { t ->
            val latestExcludedApps = t.filter(AppInfo::isExcluded).map(AppInfo::packageInfo).toSet()
            if (Sets.symmetricDifference(excludedApps,
                                         latestExcludedApps).size == 0) return@Observer

            Log.i(LOG_TAG_VPN, "excluded-apps list changed, restart vpn")

            io("excludeApps") {
                restartVpn(appConfig.newTunnelOptions(this, GoIntraListener, fakeDns))
            }
        }
    }

    private fun makeOrbotStartStatusObserver(): Observer<Boolean> {
        return Observer<Boolean> {
            settingUpOrbot.set(it)
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

    private fun updateNotificationBuilder(): NotificationCompat.Builder {
        val mainActivityIntent = PendingIntent.getActivity(this, 0, Intent(this,
                                                                           HomeScreenActivity::class.java),
                                                           PendingIntent.FLAG_UPDATE_CURRENT)
        var builder: NotificationCompat.Builder
        if (isAtleastO()) {
            val name: CharSequence = resources.getString(R.string.notif_channel_vpn_notification)
            // LOW is the lowest importance that is allowed with startForeground in Android O
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance)
            channel.description = resources.getString(R.string.notif_channel_desc_vpn_notification)
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(this, MAIN_CHANNEL_ID)
        } else {
            builder = NotificationCompat.Builder(this, MAIN_CHANNEL_ID)
        }

        var contentTitle: String = when (appConfig.getBraveMode()) {
            AppConfig.BraveMode.DNS -> resources.getString(R.string.dns_mode_notification_title)
            AppConfig.BraveMode.FIREWALL -> resources.getString(
                R.string.firewall_mode_notification_title)
            AppConfig.BraveMode.DNS_FIREWALL -> resources.getString(
                R.string.hybrid_mode_notification_title)
        }

        if (isAppPaused()) {
            contentTitle = resources.getString(R.string.pause_mode_notification_title)
        }

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            mainActivityIntent)

        // New action button options in the notification
        // 1. Pause / Resume, Stop action button.
        // 2. RethinkDNS modes (dns & dns+firewall mode)
        // 3. No action button.
        if (DEBUG) Log.d(LOG_TAG_VPN, "notification action type:  ${persistentState.notificationActionType}")
        builder.color = ContextCompat.getColor(this, getThemeAccent(this))
        when (NotificationActionType.getNotificationActionType(
            persistentState.notificationActionType)) {
            NotificationActionType.PAUSE_STOP -> {
                // Add the action based on AppState (PAUSE/ACTIVE)
                val openIntent1 = makeVpnIntent(NOTIF_ACTION_MODE_STOP,
                                                Constants.NOTIF_ACTION_STOP_VPN)
                val notificationAction1 = NotificationCompat.Action(0, resources.getString(
                    R.string.notification_action_stop_vpn), openIntent1)
                builder.addAction(notificationAction1)

                if (isAppPaused()) {
                    val openIntent2 = makeVpnIntent(NOTIF_ACTION_MODE_RESUME,
                                                    Constants.NOTIF_ACTION_RESUME_VPN)
                    val notificationAction2 = NotificationCompat.Action(0, resources.getString(
                        R.string.notification_action_resume_vpn), openIntent2)
                    builder.addAction(notificationAction2)
                } else {
                    val openIntent2 = makeVpnIntent(NOTIF_ACTION_MODE_PAUSE,
                                                    Constants.NOTIF_ACTION_PAUSE_VPN)
                    val notificationAction2 = NotificationCompat.Action(0, resources.getString(
                        R.string.notification_action_pause_vpn), openIntent2)
                    builder.addAction(notificationAction2)
                }
            }
            NotificationActionType.DNS_FIREWALL -> {
                val openIntent1 = makeVpnIntent(NOTIF_ACTION_MODE_DNS_ONLY,
                                                Constants.NOTIF_ACTION_DNS_VPN)
                val openIntent2 = makeVpnIntent(NOTIF_ACTION_MODE_DNS_FIREWALL,
                                                Constants.NOTIF_ACTION_DNS_FIREWALL_VPN)
                val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0,
                                                                                              resources.getString(
                                                                                                  R.string.notification_action_dns_mode),
                                                                                              openIntent1)
                val notificationAction2: NotificationCompat.Action = NotificationCompat.Action(0,
                                                                                               resources.getString(
                                                                                                   R.string.notification_action_dns_firewall_mode),
                                                                                               openIntent2)
                builder.addAction(notificationAction)
                builder.addAction(notificationAction2)
            }
            NotificationActionType.NONE -> {
                Log.i(LOG_TAG_VPN, "No notification action")
            }
        }

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        builder.build()
        return builder
    }

    private fun makeVpnIntent(notificationID: Int, intentExtra: String): PendingIntent? {
        val intentAction = Intent(this, NotificationActionReceiver::class.java)
        intentAction.putExtra(Constants.NOTIFICATION_ACTION, intentExtra)
        return PendingIntent.getBroadcast(this, notificationID, intentAction,
                                          PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnController.onConnectionStateChanged(State.NEW)

        ui {
            var isNewVpn = true

            // Initialize the value whenever the vpn is started.
            accessibilityHearbeatTimestamp = INIT_TIME_MS

            startOrbotAsyncIfNeeded()

            VpnController.mutex.withLock {
                // if service is up (aka connectionMonitor not null)
                // then simply update the existing tunnel
                if (connectionMonitor == null) {
                    connectionMonitor = ConnectionMonitor(this, this)
                } else {
                    isNewVpn = false
                }
            }

            val opts = appConfig.newTunnelOptions(this, GoIntraListener, fakeDns)

            Log.i(LOG_TAG_VPN, "start-foreground with opts $opts (for new-vpn? $isNewVpn)")

            if (!isNewVpn) {
                io("tunUpdate") {
                    updateTun(opts)
                }
            } else {
                io("startVpn") {
                    FirewallManager.loadAppFirewallRules()

                    if (FirewallManager.getTotalApps() <= 0) {
                        notifyEmptyFirewallRules()
                    }

                    restartVpn(opts)
                }

                startForeground(SERVICE_ID, updateNotificationBuilder().build())

                persistentState.setVpnEnabled(true)

                updateQuickSettingsTile()
                connectionMonitor?.onVpnStart()
            }
        }
        return Service.START_REDELIVER_INTENT
    }

    private fun startOrbotAsyncIfNeeded() {
        if (!appConfig.isOrbotProxyEnabled()) return

        io("startOrbot") {
            orbotHelper.startOrbot(appConfig.getProxyType())
        }
    }

    private fun unobserveAppInfos() {
        FirewallManager.getApplistObserver().removeObserver(appInfoObserver)
    }

    private fun unobserveOrbotStartStatus() {
        persistentState.orbotConnectionStatus.removeObserver(orbotStartStatusObserver)
    }

    private fun registerAccessibilityServiceState() {
        accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { b ->
            if (!b) {
                handleAccessibilityFailure()
            }
        }
    }

    private fun unregisterAccessibilityServiceState() {
        accessibilityListener?.let {
            accessibilityManager.removeAccessibilityStateChangeListener(it)
        }
    }

    private suspend fun updateTun(tunnelOptions: AppConfig.TunnelOptions) {
        VpnController.mutex.withLock {
            // Connection monitor can be null if onDestroy() of service
            // is called, in that case no need to call updateServerConnection()
            if (connectionMonitor == null) {
                Log.w(LOG_TAG_VPN, "skip update-tun, connection-monitor missing")
                return@withLock
            }
            vpnAdapter?.updateTun(tunnelOptions)
            handleVpnAdapterChange()
        }
    }

    private suspend fun updateDnscrypt(tunnelOptions: AppConfig.TunnelOptions) {
        VpnController.mutex.withLock {
            if (connectionMonitor == null) return@withLock

            vpnAdapter?.setDnscryptMode(tunnelOptions)
        }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        /* TODO Check on the Persistent State variable
           Check on updating the values for Package change and for mode change.
           As of now handled manually */
        val opts = appConfig.newTunnelOptions(this, GoIntraListener, fakeDns)
        Log.i(LOG_TAG_VPN, "onPrefChange key: $key, opts: $opts")
        when (key) {
            PersistentState.BRAVE_MODE -> {
                io("braveModeChange") {
                    restartVpn(opts)
                }
                notificationManager.notify(SERVICE_ID, updateNotificationBuilder().build())
            }
            PersistentState.LOCAL_BLOCK_LIST -> {
                io("localBlocklist") {
                    updateTun(opts)
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
                spawnLocalBlocklistStampUpdate()
            }
            PersistentState.REMOTE_BLOCK_LIST_STAMP -> { // update tunnel on remote blocklist stamp change.
                io("remoteBlocklist") {
                    updateTun(opts)
                }
            }
            PersistentState.DNS_CHANGE -> {
                /*
                 * Handles the DNS type changes.
                 * DNS Proxy - Requires restart of the VPN.
                 * DNSCrypt - Set the tunnel with DNSCrypt mode once the live servers size is not 0.
                 * DOH - Overwrites the tunnel values with new values.
                 */
                io("dnsChange") {
                    when (appConfig.getDnsType()) {
                        AppConfig.DnsType.DOH -> {
                            updateTun(opts)
                        }
                        AppConfig.DnsType.DNSCRYPT -> {
                            updateDnscrypt(opts)
                        }
                        AppConfig.DnsType.DNS_PROXY -> {
                            updateDnsProxy(opts)
                        }
                    }
                }
            }
            PersistentState.DNS_RELAYS -> {
                io("updateDnscrypt") {
                    updateDnscrypt(opts)
                }
            }
            PersistentState.ALLOW_BYPASS -> {
                io("allowBypass") {
                    restartVpn(opts)
                }
            }
            PersistentState.PROXY_TYPE -> {
                io("proxy") {
                    restartVpn(opts)
                }
            }
            PersistentState.NETWORK -> {
                connectionMonitor?.onUserPreferenceChanged()
            }
            PersistentState.NOTIFICATION_ACTION -> {
                notificationManager.notify(SERVICE_ID, updateNotificationBuilder().build())
            }
        }
    }

    private suspend fun updateDnsProxy(opts: AppConfig.TunnelOptions) {
        val dnsProxyEndpoint = appConfig.getConnectedProxyDetails()
        if (dnsProxyEndpoint.isInternal(this)) {
            restartVpn(opts)
        } else {
            updateTun(opts)
        }
    }

    private fun spawnLocalBlocklistStampUpdate() {
        io("dnsStampUpdate") {
            VpnController.mutex.withLock {
                if (connectionMonitor == null) return@withLock

                vpnAdapter?.setBraveDnsStamp()
            }
        }
    }

    fun signalStopService(userInitiated: Boolean) {
        userInitiatedStop = userInitiated
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
        io("stopVpn") {
            VpnController.mutex.withLock {
                vpnAdapter?.close()
                vpnAdapter = null
                Log.i(LOG_TAG_VPN, "Stop vpn adapter/controller")
            }
        }
    }

    private suspend fun restartVpn(tunnelOptions: AppConfig.TunnelOptions) {
        VpnController.mutex.withLock {
            // connectionMonitor = null indicates onStartCommand has not yet been called
            if (connectionMonitor == null) return@withLock

            // attempt seamless hand-off as described in VpnService.Builder.establish() docs
            val oldAdapter: GoVpnAdapter? = vpnAdapter
            vpnAdapter = makeVpnAdapter()
            oldAdapter?.close()
            Log.i(LOG_TAG_VPN, "restartVpn? ${vpnAdapter != null}")
            vpnAdapter?.start(tunnelOptions)
            handleVpnAdapterChange()
        }
    }

    private fun handleVpnAdapterChange() {
        // edge-case: mark connection-state as 'failing' when underlying tunnel does not exist
        // TODO: like Intra, call VpnController#stop instead? (see VpnController#onStartComplete)
        if (!hasTunnel()) {
            signalStopService(userInitiated = false)
            return
        }

        // Case: Set state to working in case of Firewall mode / dns proxy mode
        if (appConfig.getBraveMode().isFirewallMode() || appConfig.isDnsProxyActive()) {
            return VpnController.onConnectionStateChanged(State.WORKING)
        }
    }

    fun hasTunnel(): Boolean {
        return vpnAdapter?.hasTunnel() == true
    }

    fun isOn(): Boolean {
        return vpnAdapter != null
    }

    private suspend fun makeVpnAdapter(): GoVpnAdapter? {
        val tunFd = establishVpn()
        return establish(this, vpnScope, tunFd)
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

    private fun handleVpnLockdownStateAsync() {
        if (!syncLockdownState()) return
        io("lockdownSync") {
            Log.i(LOG_TAG_VPN, "vpn lockdown mode change, restarting")
            restartVpn(appConfig.newTunnelOptions(this, GoIntraListener, fakeDns))
        }
    }

    private fun syncLockdownState(): Boolean {
        if (!isAtleastQ()) return false

        val ret = isLockdownEnabled != isLockDownPrevious
        isLockDownPrevious = this.isLockdownEnabled
        return ret
    }

    override fun onDestroy() {
        if (!userInitiatedStop) {
            val vibrationPattern = longArrayOf(1000) // Vibrate for one second.
            // Show revocation warning
            val builder: NotificationCompat.Builder
            if (isAtleastO()) {
                val name: CharSequence = getString(R.string.notif_channel_vpn_failure)
                val description = getString(R.string.notif_channel_desc_vpn_failure)
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

        try {
            unregisterAccessibilityServiceState()
            orbotHelper.unregisterReceiver()
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_VPN, "Unregister receiver error: ${e.message}")
        }

        persistentState.setVpnEnabled(false)
        updateQuickSettingsTile()
        stopPauseTimer()

        unobserveOrbotStartStatus()
        unobserveAppInfos()
        persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        connectionMonitor?.onVpnStop()
        VpnController.onVpnDestroyed()
        vpnScope.cancel("VpnService onDestroy")

        Log.w(LOG_TAG_VPN, "Destroying VPN service")
        stopForeground(true)
    }

    private fun startPauseTimer() {
        PauseTimer.start(PauseTimer.DEFAULT_PAUSE_TIME_MS)
    }

    private fun stopPauseTimer() {
        PauseTimer.stop()
    }

    fun increasePauseDuration(durationMs: Long) {
        PauseTimer.addDuration(durationMs)
    }

    fun decreasePauseDuration(durationMs: Long) {
        PauseTimer.subtractDuration(durationMs)
    }

    fun getPauseCountDownObserver(): MutableLiveData<Long>? {
        return PauseTimer.getPauseCountDownObserver()
    }

    private fun isAppPaused(): Boolean {
        return VpnController.isAppPaused()
    }

    fun pauseApp() {
        startPauseTimer()
        handleVpnServiceOnAppStateChange()
    }

    fun resumeApp() {
        stopPauseTimer()
        handleVpnServiceOnAppStateChange()
    }

    private fun handleVpnServiceOnAppStateChange() {
        io("appStateChange") {
            restartVpn(appConfig.newTunnelOptions(this, GoIntraListener, fakeDns))
        }
        notificationManager.notify(SERVICE_ID, updateNotificationBuilder().build())
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

            when (appConfig.getBraveMode()) {
                AppConfig.BraveMode.DNS -> {
                    builder.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
                    builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
                }
                AppConfig.BraveMode.FIREWALL -> {
                    builder.addRoute(Constants.UNSPECIFIED_IP, Constants.UNSPECIFIED_PORT)
                }
                AppConfig.BraveMode.DNS_FIREWALL -> {
                    builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
                    builder.addRoute(Constants.UNSPECIFIED_IP, Constants.UNSPECIFIED_PORT)
                }
            }
            return builder.establish()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            return null
        }
    }

    private fun io(s: String, f: suspend () -> Unit) {
        vpnScope.launch(CoroutineName(s)) {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private fun ui(f: suspend () -> Unit) {
        vpnScope.launch {
            withContext(Dispatchers.Main) {
                f()
            }
        }
    }

}
