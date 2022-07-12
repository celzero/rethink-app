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
import android.net.ConnectivityManager
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
import com.celzero.bravedns.automaton.DomainRulesManager
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.RefreshDatabase
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
import dnsx.Summary
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Listener
import intra.TCPSocketSummary
import intra.UDPSocketSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import protect.Blocker
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class BraveVPNService : VpnService(), ConnectionMonitor.NetworkListener, Blocker, Listener,
                        OnSharedPreferenceChangeListener {

    @GuardedBy("vpnController") private var connectionMonitor: ConnectionMonitor? = null
    @GuardedBy("vpnController") private var vpnAdapter: GoVpnAdapter? = null
//    private var connectionType: ConnectionMonitor.ConnectionType = ConnectionMonitor.ConnectionType.NONE

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

        // IPv6 vpn constants
        // Randomly generated unique local IPv6 unicast subnet prefix, as defined by RFC 4193.
        private const val IPV6_TEMPLATE: String = "fd66:f83a:c650::%d"
        private const val IPV6_PREFIX_LENGTH: Int = 120

        // This value must match the hardcoded MTU in outline-go-tun2socks.
        // TODO: Make outline-go-tun2socks's MTU configurable.
        private const val VPN_INTERFACE_MTU: Int = 1500

    }

    private var isLockDownPrevious: Boolean = false

    private val vpnScope = MainScope()

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()
    private val netLogTracker by inject<NetLogTracker>()

    @Volatile private var isAccessibilityServiceFunctional: Boolean = false
    @Volatile var accessibilityHearbeatTimestamp: Long = INIT_TIME_MS
    @Volatile var activeNetworkHeartbeatTimestamp: Long = INIT_TIME_MS
    var settingUpOrbot: AtomicBoolean = AtomicBoolean(false)

    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager
    private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var connectivityManager: ConnectivityManager
    private var keyguardManager: KeyguardManager? = null

    private lateinit var appInfoObserver: Observer<Collection<AppInfo>>
    private lateinit var orbotStartStatusObserver: Observer<Boolean>

    private var excludedApps: MutableSet<String> = mutableSetOf()

    private var userInitiatedStop: Boolean = false

    private var underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null

    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    enum class State {
        NEW, WORKING, FAILING, PAUSED
    }

    override fun bind4(fid: Long) {
        // binding to the underlying network is not working.
        // no need to bind if use active network is true
        if (underlyingNetworks?.useActive == true) return

        // fixme: vpn lockdown scenario should behave similar to the behaviour of use all
        // network.

        var pfd: ParcelFileDescriptor? = null
        try {

            // allNet is always sorted, first network is always the active network
            underlyingNetworks?.allNet?.forEach { network ->
                if (underlyingNetworks?.ipv4Net?.contains(network) == true) {
                    pfd = ParcelFileDescriptor.adoptFd(fid.toInt())
                    if (pfd == null) return

                    network.bindSocket(pfd!!.fileDescriptor)
                    return
                } else {
                    // no-op is ok, fd is auto-bound to active network
                }
            }
        } catch (e: IOException) {
            Log.e(LOG_TAG_VPN,
                  "Exception while binding(bind4) the socket for fid: $fid, ${e.message}, $e")
        } finally {
            pfd?.detachFd()
        }
    }

    override fun bind6(fid: Long) {
        // binding to the underlying network is not working.
        // no need to bind if use active network is true
        if (underlyingNetworks?.useActive == true) return

        // fixme: vpn lockdown scenario should behave similar to the behaviour of use all
        // network enabled?


        var pfd: ParcelFileDescriptor? = null
        try {
            underlyingNetworks?.allNet?.forEach { network ->
                if (underlyingNetworks?.ipv6Net?.contains(network) == true) {
                    pfd = ParcelFileDescriptor.adoptFd(fid.toInt())
                    if (pfd == null) return

                    network.bindSocket(pfd!!.fileDescriptor)
                    return
                } else {
                    // no-op is ok, fd is auto-bound to active network
                }

            }
        } catch (e: IOException) {
            Log.e(LOG_TAG_VPN,
                  "Exception while binding(bind6) the socket for fid: $fid, ${e.message}, $e")
        } finally {
            pfd?.detachFd()
        }
    }

    override fun block(protocol: Int, _uid: Long, sourceAddress: String,
                       destAddress: String): Boolean {
        handleVpnLockdownStateAsync()

        // Ref: ipaddress doc: https://seancfoley.github.io/IPAddress/ipaddress.html#host-name-or-address-with-port-or-service-name
        val first = HostName(sourceAddress)
        val second = HostName(destAddress)

        val srcIp = if (first.address == null) "" else first.address.toString()
        val srcPort = first.port
        val dstIp = if (second.address == null) "" else second.address.toString()
        val dstPort = second.port

        val uid = if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            connTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort)
        } else {
            _uid.toInt() // uid must have been retrieved from procfs by the caller
        }

        // skip the block-ceremony for dns conns
        if (isDns(dstPort) && isVpnDns(dstIp)) {
            return false
        }

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
            val connectionStatus = FirewallManager.connectionStatus(uid)

            if (allowOrbot(uid)) {
                return FirewallRuleset.RULE9B
            }

            if (unknownAppBlocked(uid)) {
                return FirewallRuleset.RULE5
            }

            // if the app is new (ie unknown), refresh the db
            if (FirewallManager.FirewallStatus.UNTRACKED == appStatus) {
                io("dbRefresh") {
                    refreshDatabase.handleNewlyConnectedApp(uid)
                }
                if (newAppBlocked(uid)) {
                    return FirewallRuleset.RULE1B
                }
            }

            if (FirewallManager.FirewallStatus.WHITELIST == appStatus) {
                return if (dnsProxied(connInfo.destPort)) {
                    FirewallRuleset.RULE9
                } else {
                    FirewallRuleset.RULE8
                }
            }

            val appRuleset = appBlocked(appStatus, connectionStatus)
            if (appRuleset != null) {
                if (persistentState.killAppOnFirewall) {
                    killFirewalledApplication(uid)
                }
                return appRuleset
            }

            // ip status should be considered only if the app is not blocked.
            // earlier the ip rules had only block so checking ips before app is valid
            // case: when the ip is whitelisted and the app is blocked, the
            // connections are going through.
            when (ipStatus(uid, connInfo.destIP)) {
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    return FirewallRuleset.RULE2
                }
                IpRulesManager.IpRuleStatus.WHITELIST -> {
                    return FirewallRuleset.RULE2B
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    // no-op; pass-through
                }
            }

            if (httpBlocked(connInfo.destPort)) {
                return FirewallRuleset.RULE10
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

    private fun httpBlocked(port: Int): Boolean {
        // no need to check if the port is not HTTP port
        if (port != KnownPorts.HTTP_PORT) {
            return false
        }

        return persistentState.blockHttpConnections
    }

    private fun allowOrbot(uid: Int): Boolean {
        return settingUpOrbot.get() && OrbotHelper.ORBOT_PACKAGE_NAME == FirewallManager.getPackageNameByUid(
            uid)
    }

    private fun dnsProxied(port: Int): Boolean {
        return (appConfig.getBraveMode().isDnsFirewallMode() && persistentState.preventDnsLeaks && isDns(
            port))
    }

    private fun dnsBypassed(destIp: String): Boolean {
        return if (!persistentState.disallowDnsBypass) {
            false
        } else {
            unresolvedIp(destIp)
        }
    }

    private fun waitAndCheckIfUidBlocked(uid: Int): Boolean {
        val allowed = testWithBackoff {
            FirewallManager.hasUid(uid) && !FirewallManager.isUidFirewalled(uid)
        }
        return !allowed
    }

    private fun newAppBlocked(uid: Int): Boolean {
        return if (!persistentState.blockNewlyInstalledApp || isMissingOrInvalidUid(uid)) {
            false
        } else {
            waitAndCheckIfUidBlocked(uid)
        }
    }

    private fun ipStatus(uid: Int, destIp: String): IpRulesManager.IpRuleStatus {
        return IpRulesManager.hasRule(uid, destIp)
    }

    private fun unknownAppBlocked(uid: Int): Boolean {
        return if (!persistentState.blockUnknownConnections) {
            false
        } else {
            isMissingOrInvalidUid(uid)
        }
    }

    private fun testWithBackoff(stallSec: Long = 20, durationSec: Long = 10,
                                test: () -> Boolean): Boolean {
        val minWaitMs = TimeUnit.SECONDS.toMillis(stallSec)
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(durationSec)
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
            val ttl = FirewallManager.ipDomainLookup.getIfPresent(ip)?.ttl ?: Long.MIN_VALUE
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
        val fakeDnsIpv4: String = LanIp.DNS.make(IPV4_TEMPLATE)
        val fakeDnsIpv6: String = LanIp.DNS.make(IPV6_TEMPLATE)
        return when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                ip == fakeDnsIpv4
            }
            InternetProtocol.IPv6.id -> {
                ip == fakeDnsIpv6
            }
            InternetProtocol.IPv46.id -> {
                ip == fakeDnsIpv4 || ip == fakeDnsIpv6
            }
            else -> {
                ip == fakeDnsIpv4
            }
        }

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

    // Check if the app has firewall rules set
    // refer: FirewallManager.kt line-no#58
    private fun appBlocked(firewallStatus: FirewallManager.FirewallStatus,
                           connectionStatus: FirewallManager.ConnectionStatus): FirewallRuleset? {
        if (isAppBlocked(firewallStatus, connectionStatus)) {
            return FirewallRuleset.RULE1
        }

        // when use all network / vpn lockdown is on, metered / unmetered rules won't be considered
        if (persistentState.useMultipleNetworks || VpnController.isVpnLockdown()) {
            return null
        }

        if (isWifiBlockedForUid(firewallStatus, connectionStatus) && !isConnectionMetered()) {
            return FirewallRuleset.RULE1D
        }

        if (isMobileDataBlockedForUid(firewallStatus, connectionStatus) && isConnectionMetered()) {
            return FirewallRuleset.RULE1E
        }

        return null
    }

    private fun isConnectionMetered(): Boolean {
        return activeNetworkMeteredCheck()
    }

    private fun activeNetworkMeteredCheck(): Boolean {
        val now = elapsedRealtime()
        // Added the INIT_TIME_MS check, encountered a bug during phone restart
        // isAccessibilityServiceRunning default value(false) is passed instead of
        // checking it from accessibility service for the first time.
        if (activeNetworkHeartbeatTimestamp == INIT_TIME_MS || Math.abs(
                now - activeNetworkHeartbeatTimestamp) > Constants.ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            Log.i(LOG_TAG_VPN,
                  "Active network check, activeNetworkHeartbeatTimestamp: $activeNetworkHeartbeatTimestamp, is connection metered?: ${connectivityManager.isActiveNetworkMetered}," + "active network? ${connectivityManager.activeNetwork}, name: ${
                      connectivityManager.getLinkProperties(
                          connectivityManager.activeNetwork)?.interfaceName
                  }")
            activeNetworkHeartbeatTimestamp = now

            underlyingNetworks?.isActiveNetworkMetered = connectivityManager.isActiveNetworkMetered
        }
        return underlyingNetworks?.isActiveNetworkMetered == true
    }

    private fun isAppBlocked(firewallStatus: FirewallManager.FirewallStatus,
                             connectionStatus: FirewallManager.ConnectionStatus): Boolean {
        return firewallStatus.blocked() && connectionStatus.both()
    }

    private fun isMobileDataBlockedForUid(firewallStatus: FirewallManager.FirewallStatus,
                                          connectionStatus: FirewallManager.ConnectionStatus): Boolean {
        return firewallStatus.blocked() && connectionStatus.mobileData()
    }

    private fun isWifiBlockedForUid(firewallStatus: FirewallManager.FirewallStatus,
                                    connectionStatus: FirewallManager.ConnectionStatus): Boolean {
        return firewallStatus.blocked() && connectionStatus.wifi()
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


        val pendingIntent = Utilities.getActivityPendingIntent(this, Intent(this,
                                                                            HomeScreenActivity::class.java),
                                                               PendingIntent.FLAG_UPDATE_CURRENT,
                                                               mutable = true)

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

        val pendingIntent = Utilities.getActivityPendingIntent(this, intent,
                                                               PendingIntent.FLAG_UPDATE_CURRENT,
                                                               mutable = true)
        if (isAtleastO()) {
            val name: CharSequence = getString(R.string.notif_channel_firewall_alerts)
            val description = resources.getString(R.string.notif_channel_desc_firewall_alerts)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
        }
        var builder: NotificationCompat.Builder = NotificationCompat.Builder(this,
                                                                             NOTIF_CHANNEL_ID_FIREWALL_ALERTS)

        val contentTitle = resources.getString(R.string.rules_load_failure_heading)
        val contentText = resources.getString(R.string.rules_load_failure_desc)
        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            pendingIntent).setContentText(contentText)
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
            val appUIDList = FirewallManager.getNonSystemAppsPackageNameByUid(uid)
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
        if (info == null) return

        netLogTracker.writeIpLog(info)
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
                      "app is in pause state, exclude all the non firewalled apps, size: ${nonFirewalledApps.count()}")
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

            if (appConfig.isOrbotProxyEnabled() && isExcludePossible(getString(R.string.orbot),
                                                                     getString(
                                                                         R.string.orbot_toast_parameter))) {
                builder = builder.addDisallowedApplication(OrbotHelper.ORBOT_PACKAGE_NAME)
            }

            if (appConfig.isDnsProxyActive()) {
                // For DNS proxy mode, if any app is set then exclude the application from the list
                val dnsProxyEndpoint = appConfig.getConnectedProxyDetails()
                val appName = dnsProxyEndpoint?.proxyAppName ?: getString(
                    R.string.settings_app_list_default_app)
                Log.i(LOG_TAG_VPN, "DNS Proxy mode is set with the app name as $appName")
                if (appName != getString(
                        R.string.settings_app_list_default_app) && isExcludePossible(appName,
                                                                                     getString(
                                                                                         R.string.dns_proxy_toast_parameter))) {
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
        netLogTracker.startLogger()

        notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        activityManager = this.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        accessibilityManager = this.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        keyguardManager = this.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        connectivityManager = this.getSystemService(
            Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
            val copy: MutableList<AppInfo>
            // adding synchronized block, found a case of concurrent modification
            // exception that happened once when trying to filter the received object (t).
            // creating a copy of the received value in a synchronized block.
            synchronized(t) {
                copy = mutableListOf<AppInfo>().apply { addAll(t) }
            }
            val latestExcludedApps = copy.filter { it.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id }.map(
                AppInfo::packageInfo).toSet()
            if (Sets.symmetricDifference(excludedApps,
                                         latestExcludedApps).isEmpty()) return@Observer

            Log.i(LOG_TAG_VPN, "excluded-apps list changed, restart vpn")

            io("excludeApps") {
                restartVpn(appConfig.newTunnelOptions(this, this, getFakeDns(),
                                                      appConfig.getInternetProtocol(),
                                                      appConfig.getProtocolTranslationMode()))
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
        val pendingIntent = Utilities.getActivityPendingIntent(this, Intent(this,
                                                                            HomeScreenActivity::class.java),
                                                               PendingIntent.FLAG_UPDATE_CURRENT,
                                                               mutable = true)
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
            pendingIntent)

        // New action button options in the notification
        // 1. Pause / Resume, Stop action button.
        // 2. RethinkDNS modes (dns & dns+firewall mode)
        // 3. No action button.
        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "notification action type:  ${persistentState.notificationActionType}")
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

    private fun makeVpnIntent(notificationID: Int, intentExtra: String): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java)
        intent.putExtra(Constants.NOTIFICATION_ACTION, intentExtra)
        return Utilities.getBroadcastPendingIntent(this, notificationID, intent,
                                                   PendingIntent.FLAG_UPDATE_CURRENT,
                                                   mutable = true)
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

            val opts = appConfig.newTunnelOptions(this, this, getFakeDns(),
                                                  appConfig.getInternetProtocol(),
                                                  appConfig.getProtocolTranslationMode())

            Log.i(LOG_TAG_VPN, "start-foreground with opts $opts (for new-vpn? $isNewVpn)")

            if (!isNewVpn) {
                io("tunUpdate") {
                    updateTun(opts)
                }
            } else {
                io("startVpn") {
                    FirewallManager.loadAppFirewallRules()
                    DomainRulesManager.load()

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

        // Reset the heart beat time for the accessibility check.
        // On accessibility failure the value will be stored for next 5 mins.
        // If user, re-enable the settings reset the timestamp so that vpn service
        // will check for the accessibility service availability.
        accessibilityHearbeatTimestamp = INIT_TIME_MS
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
        val opts = appConfig.newTunnelOptions(this, this, getFakeDns(),
                                              appConfig.getInternetProtocol(),
                                              appConfig.getProtocolTranslationMode())
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
                        AppConfig.DnsType.RETHINK_REMOTE -> {
                            updateTun(opts)
                        }
                        AppConfig.DnsType.NETWORK_DNS -> {
                            enableSystemDns()
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
            PersistentState.INTERNET_PROTOCOL -> {
                io("InternetProtocolChange") {
                    restartVpn(opts)
                }
            }
            PersistentState.PROTOCOL_TRANSLATION -> {
                io("ProtocolTranslation") {
                    updateTun(opts)
                }
            }
        }
    }

    private suspend fun enableSystemDns() {
        val linkProperties = connectivityManager.getLinkProperties(
            connectivityManager.activeNetwork)
        val dnsServers = linkProperties?.dnsServers

        if (dnsServers.isNullOrEmpty()) {
            ui {
                showToastUiCentered(this, getString(R.string.system_dns_connection_failure),
                                    Toast.LENGTH_SHORT)
            }
            appConfig.setDefaultConnection()
            return
        }

        appConfig.setSystemDns(dnsServers)
        updateTunnelOnSystemDnsChange()
    }

    private fun updateTunnelOnSystemDnsChange() {
        io("system_dns") {
            val opts = appConfig.newTunnelOptions(this, this, getFakeDns(),
                                                  appConfig.getInternetProtocol(),
                                                  appConfig.getProtocolTranslationMode())
            val previousTunnelProxyInfo = vpnAdapter?.getProxyTransport()
            if (previousTunnelProxyInfo == null) {
                updateTun(opts)
            } else {
                // no need to update if the proxy set in tunnel is same
                if (isProxyInfoSame(previousTunnelProxyInfo)) return@io

                updateTun(opts)
            }
        }
    }

    private fun isProxyInfoSame(prev: HostName): Boolean {
        return prev.host == appConfig.getSystemDns().ipAddress && prev.port == appConfig.getSystemDns().port
    }

    private suspend fun updateDnsProxy(opts: AppConfig.TunnelOptions) {
        val dnsProxyEndpoint = appConfig.getConnectedProxyDetails()
        if (dnsProxyEndpoint?.isInternal(this) == true) {
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
        // reset the heart beat time to current time for active network check
        activeNetworkHeartbeatTimestamp = elapsedRealtime()
        VpnController.onConnectionStateChanged(null)
    }

    override fun onNetworkConnected(networks: ConnectionMonitor.UnderlyingNetworks) {
        Log.i(LOG_TAG_VPN, "connecting to networks: $networks")

        if (networks.allNet.isNullOrEmpty() || networks.useActive) {
            setUnderlyingNetworks(null)
        } else {
            setUnderlyingNetworks(networks.allNet.toTypedArray())
        }

        underlyingNetworks = networks
        // set the heart beat time to current time for active network check
        activeNetworkHeartbeatTimestamp = elapsedRealtime()

        // set dns address is system dns
        if (appConfig.isSystemDns()) {
            val linkProperties = connectivityManager.getLinkProperties(networks.allNet?.first())
            val dnsServers = linkProperties?.dnsServers

            // on null dns servers, show toast, TODO: maybe notification?
            if (dnsServers.isNullOrEmpty()) {
                ui {
                    showToastUiCentered(this, getString(R.string.system_dns_connection_failure),
                                        Toast.LENGTH_SHORT)
                }
                // fixme: should we fallback to default?
                io("set_default_connection") {
                    appConfig.setDefaultConnection()
                }
                return
            }
            io("set_systen_dns") {
                appConfig.setSystemDns(dnsServers)
                updateTunnelOnSystemDnsChange()
            }
        }
    }

    /*override fun onLinkPropertiesChange(linkProperties: LinkProperties) {
        // get the dns endpoint object from database and update the ip
        io("insert_network_dns") {
            val endpoint = appConfig.getNetworkDnsEndpoint() ?: return@io
            if (linkProperties.dnsServers.isEmpty()) return@io

            // dns servers returns list of InetAddress, update the host address of the
            // first element as endpoint's proxyIp
            // fixme: as of now, database and go lib accepts only one ip as input.
            // need to do the changes in the dns proxy endpoint to accept multiple ip addresses
            endpoint.proxyIP = linkProperties.dnsServers[0].hostAddress
            Log.i(LOG_TAG_VPN, "change in network dns server: ${endpoint.proxyIP}")
            try {
                appConfig.updateNetworkDnsEndpoint(endpoint)
            } catch (e: Exception) {
                Log.e(LOG_TAG_VPN, "Error persisting default network proxy ip. ${e.message}")
            }
            // restart vpn only if network dns is connected
            if (appConfig.isNetworkDns()) {
                Log.i(LOG_TAG_VPN, "change in network dns, restarting")
                restartVpn(appConfig.newTunnelOptions(this, GoIntraListener, getFakeDns(),
                                                      getInternetProtocol(),
                                                      getProtocolTranslationMode()))
            }
        }
    }*/

    private fun handleVpnLockdownStateAsync() {
        if (!syncLockdownState()) return
        io("lockdownSync") {
            Log.i(LOG_TAG_VPN, "vpn lockdown mode change, restarting")
            restartVpn(appConfig.newTunnelOptions(this, this, getFakeDns(),
                                                  appConfig.getInternetProtocol(),
                                                  appConfig.getProtocolTranslationMode()))
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

            val pendingIntent = Utilities.getActivityPendingIntent(this, Intent(this,
                                                                                HomeScreenActivity::class.java),
                                                                   PendingIntent.FLAG_UPDATE_CURRENT,
                                                                   mutable = true)
            builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(
                resources.getText(R.string.warning_title)).setContentText(
                resources.getText(R.string.notification_content)).setContentIntent(pendingIntent)
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

        netLogTracker.stopLogger()
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

    fun getPauseCountDownObserver(): MutableLiveData<Long> {
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
            restartVpn(appConfig.newTunnelOptions(this, this, getFakeDns(),
                                                  appConfig.getInternetProtocol(),
                                                  appConfig.getProtocolTranslationMode()))
        }
        notificationManager.notify(SERVICE_ID, updateNotificationBuilder().build())
    }

    // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
// assign the following values to the final byte of an address within a subnet.
// Value of the final byte, to be substituted into the template.
    private enum class LanIp(private val value: Int) {
        GATEWAY(1), ROUTER(2), DNS(3);

        fun make(template: String): String {
            val format = String.format(Locale.ROOT, template, value)
            return HostName(format).toString()
        }

        // accepts ip template and port number, converts into address or host with port
        // introduced IPAddressString, as IPv6 is not well-formed after appending port number
        // with the formatted(String.format) ip
        fun make(template: String, port: Int): String {
            val format = String.format(Locale.ROOT, template, value)
            // Hostname() accepts IPAddress, port(Int) as parameters
            return HostName(IPAddressString(format).address, port).toString()
        }
    }

    private suspend fun establishVpn(): ParcelFileDescriptor? {
        try {
            var builder: VpnService.Builder = newBuilder().setSession("RethinkDNS").setMtu(
                VPN_INTERFACE_MTU)

            builder = addAddress(builder)

            when (appConfig.getBraveMode()) {
                AppConfig.BraveMode.DNS -> {
                    builder = addDnsRoute(builder)
                    builder = addDnsServer(builder)
                }
                AppConfig.BraveMode.FIREWALL -> {
                    builder = addRoute(builder)
                }
                AppConfig.BraveMode.DNS_FIREWALL -> {
                    builder = addRoute(builder)
                    builder = addDnsServer(builder)
                }
            }

            return builder.establish()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            return null
        }
    }

    private fun addRoute(builder: Builder): Builder {
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                builder.addRoute(Constants.UNSPECIFIED_IP_IPV4, Constants.UNSPECIFIED_PORT)
            }
            InternetProtocol.IPv6.id -> {
                builder.addRoute(Constants.UNSPECIFIED_IP_IPV6, Constants.UNSPECIFIED_PORT)
            }
            InternetProtocol.IPv46.id -> {
                builder.addRoute(Constants.UNSPECIFIED_IP_IPV4, Constants.UNSPECIFIED_PORT)
                builder.addRoute(Constants.UNSPECIFIED_IP_IPV6, Constants.UNSPECIFIED_PORT)
            }
        }
        return builder
    }

    private fun addAddress(builder: Builder): Builder {
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                builder.addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
            }
            InternetProtocol.IPv6.id -> {
                builder.addAddress(LanIp.GATEWAY.make(IPV6_TEMPLATE), IPV6_PREFIX_LENGTH)
            }
            InternetProtocol.IPv46.id -> {
                builder.addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
                builder.addAddress(LanIp.GATEWAY.make(IPV6_TEMPLATE), IPV6_PREFIX_LENGTH)
            }
        }
        return builder
    }

    private fun addDnsServer(builder: Builder): Builder {
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
            }
            InternetProtocol.IPv6.id -> {
                builder.addDnsServer(LanIp.DNS.make(IPV6_TEMPLATE))
            }
            InternetProtocol.IPv46.id -> {
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
                builder.addDnsServer(LanIp.DNS.make(IPV6_TEMPLATE))
            }
        }
        return builder
    }

    // builder.addRoute() when the app is in DNS only mode
    private fun addDnsRoute(builder: Builder): Builder {
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                builder.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
            }
            InternetProtocol.IPv6.id -> {
                builder.addRoute(LanIp.DNS.make(IPV6_TEMPLATE), 128)
            }
            InternetProtocol.IPv46.id -> {
                builder.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
                builder.addRoute(LanIp.DNS.make(IPV6_TEMPLATE), 128)
            }
        }
        return builder
    }

    private fun getFakeDns(): String {
        val ipv4 = LanIp.DNS.make(IPV4_TEMPLATE, KnownPorts.DNS_PORT)
        val ipv6 = LanIp.DNS.make(IPV6_TEMPLATE, KnownPorts.DNS_PORT)
        return when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                ipv4
            }
            InternetProtocol.IPv6.id -> {
                ipv6
            }
            InternetProtocol.IPv46.id -> {
                "$ipv4,$ipv6"
            }
            else -> {
                ipv4
            }
        }
    }

    private fun io(s: String, f: suspend () -> Unit) = vpnScope.launch(CoroutineName(s)) {
        withContext(Dispatchers.IO) {
            f()
        }
    }

    private fun ui(f: suspend () -> Unit) = vpnScope.launch {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    override fun onQuery(query: String?): String {
        // return empty response for now
        return ""
    }

    override fun onResponse(summary: Summary?) {
        if (summary == null) {
            Log.i(LoggerConstants.LOG_TAG_DNS_LOG, "received null summary")
            return
        }

        netLogTracker.processDnsLog(summary)
    }

    override fun onTCPSocketClosed(summary: TCPSocketSummary?) {
        // no-op
    }

    override fun onUDPSocketClosed(summary: UDPSocketSummary?) {
        // no-op
    }

}
