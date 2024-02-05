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

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.ParcelFileDescriptor
import android.os.SystemClock.elapsedRealtime
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.NotificationHandlerDialog
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.IPUtil
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isMissingOrInvalidUid
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.common.collect.Sets
import dnsx.Dnsx
import dnsx.RDNS
import dnsx.Summary
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Bridge
import intra.SocketSummary
import ipn.Ipn
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import protect.Protect
import rnet.ServerSummary
import rnet.Tab
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class BraveVPNService :
    VpnService(), ConnectionMonitor.NetworkListener, Bridge, OnSharedPreferenceChangeListener {

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

        private const val NOTIF_ID_ACCESSIBILITY_FAILURE = 104

        // IPv4 VPN constants
        private const val IPV4_TEMPLATE: String = "10.111.222.%d"
        private const val IPV4_PREFIX_LENGTH: Int = 24

        // IPv6 vpn constants
        // Randomly generated unique local IPv6 unicast subnet prefix, as defined by RFC 4193.
        private const val IPV6_TEMPLATE: String = "fd66:f83a:c650::%d"
        private const val IPV6_PREFIX_LENGTH: Int = 120

        // TODO: add the minimum of the underlying network interface MTU
        private const val VPN_INTERFACE_MTU: Int = 1500 // 65535
        private const val MAX_INTERFACE_MTU: Int = 65535
    }

    private var isLockDownPrevious: Boolean = false
    private val ctx: Context = this
    private val vpnScope = MainScope()

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val persistentState by inject<PersistentState>()
    private val rdb by inject<RefreshDatabase>()
    private val netLogTracker by inject<NetLogTracker>()

    @Volatile private var isAccessibilityServiceFunctional: Boolean = false
    @Volatile var accessibilityHearbeatTimestamp: Long = INIT_TIME_MS
    private var settingUpOrbot: AtomicBoolean = AtomicBoolean(false)

    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager
    private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var connectivityManager: ConnectivityManager
    private var keyguardManager: KeyguardManager? = null

    private lateinit var appInfoObserver: Observer<Collection<AppInfo>>
    private lateinit var orbotStartStatusObserver: Observer<Boolean>

    private var rethinkUid: Int = INVALID_UID

    // used to store the conn-ids that are allowed and active, to show in network logs
    // as active connections. removed when the connection is closed (onSummary)
    private var trackedCids = Collections.newSetFromMap(ConcurrentHashMap<CidKey, Boolean>())

    // data class to store the connection summary
    data class CidKey(val cid: String, val uid: Int)

    private var excludedApps: MutableSet<String> = mutableSetOf()

    var underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null

    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    enum class State {
        NEW,
        WORKING,
        FAILING,
        PAUSED,
        NO_INTERNET,
        DNS_SERVER_DOWN,
        DNS_ERROR,
        APP_ERROR
    }

    private fun logd(msg: String) {
        if (DEBUG) Log.d(LOG_TAG_VPN, msg)
    }

    override fun bind4(who: String, fid: Long) {
        val rinr = persistentState.routeRethinkInRethink
        logd("protect: $who, $fid, rinr? $rinr")
        if (who != Ipn.Exit && rinr) {
            // do not proceed if rethink within rethink is enabled and proxyId(who) is not exit
            return
        }

        this.protect(fid.toInt())
        // binding to the underlying network is not working.
        // no need to bind if use active network is true
        if (underlyingNetworks?.useActive == true) {
            logd("bind4: use active network is true")
            return
        }

        // fixme: vpn lockdown scenario should behave similar to the behaviour of use all
        // network.

        var pfd: ParcelFileDescriptor? = null
        // allNet, ipv4Net, ipv6Net is always sorted, first network is always the active network
        underlyingNetworks?.ipv4Net?.forEach {
            try {
                pfd = ParcelFileDescriptor.adoptFd(fid.toInt())

                it.network.bindSocket(pfd!!.fileDescriptor)
                logd("bind4: $who, ${it.network.networkHandle}, ${it.networkType}")
                return
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, "err bind4 for fid: $fid, ${e.message}, $e")
            } finally {
                pfd?.detachFd()
            }
        }
        logd("bind4: no network to bind, ${underlyingNetworks?.ipv4Net?.size}")
    }

    override fun bind6(who: String, fid: Long) {
        val rinr = persistentState.routeRethinkInRethink
        logd("protect: $who, $fid, rinr? $rinr")
        if (who != Ipn.Exit && rinr) {
            // do not proceed if rethink within rethink is enabled and proxyId(who) is not exit
            return
        }

        this.protect(fid.toInt())
        // who is not used, but kept for future use
        // binding to the underlying network is not working.
        // no need to bind if use active network is true
        if (underlyingNetworks?.useActive == true) {
            logd("bind6: use active network is true")
            return
        }

        // fixme: vpn lockdown scenario should behave similar to the behaviour of use all
        // network enabled?

        var pfd: ParcelFileDescriptor? = null
        underlyingNetworks?.ipv6Net?.forEach {
            try {
                pfd = ParcelFileDescriptor.adoptFd(fid.toInt())
                it.network.bindSocket(pfd!!.fileDescriptor)
                logd("bind6: $who, ${it.network.networkHandle}, ${it.networkType}")
                return
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, "err(bind6) fid: $fid, ${e.message}, $e")
            } finally {
                pfd?.detachFd()
            }
        }
        logd("bind6: no network to bind, ${underlyingNetworks?.ipv6Net?.size}")
    }

    override fun protect(who: String?, fd: Long) {
        val rinr = persistentState.routeRethinkInRethink
        logd("protect: $who, $fd, rinr? $rinr")
        if (who != Ipn.Exit && rinr) {
            // do not proceed if rethink within rethink is enabled and proxyId(who) is not exit
            return
        }
        this.protect(fd.toInt())
    }

    private fun getUid(
        _uid: Long,
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ): Int {
        return if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            connTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort)
        } else {
            _uid.toInt() // uid must have been retrieved from procfs by the caller
        }
    }

    /** Checks if incoming connection is blocked by any user-set firewall rule */
    private suspend fun firewall(
        connInfo: ConnTrackerMetaData,
        anyRealIpBlocked: Boolean = false
    ): FirewallRuleset {
        try {
            logd("firewall: $connInfo")
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
            if (appStatus.isUntracked()) {
                io("addNewApp") { rdb.addNewApp(uid) }
                if (newAppBlocked(uid)) {
                    return FirewallRuleset.RULE1B
                }
            }

            // check for app rules (unmetered, metered connections)
            val appRuleset = appBlocked(connInfo, connectionStatus)
            if (appRuleset != null) {
                return appRuleset
            }

            if (VpnController.isVpnLockdown() && isAppPaused()) {
                // allow when firewall is paused: as a placeholder RULE8(bypass app) is used
                return FirewallRuleset.RULE8
            }

            when (getDomainRule(connInfo.query, uid)) {
                DomainRulesManager.Status.BLOCK -> {
                    return FirewallRuleset.RULE2E
                }
                DomainRulesManager.Status.TRUST -> {
                    return FirewallRuleset.RULE2F
                }
                DomainRulesManager.Status.NONE -> {
                    // fall-through
                }
            }

            // IP rules
            when (ipStatus(uid, connInfo.destIP, connInfo.destPort)) {
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    return FirewallRuleset.RULE2
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    return FirewallRuleset.RULE2B
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    // no-op; pass-through
                    // By-pass universal should be validated after app-firewall rules
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    // no-op; pass-through
                }
            }

            // by-pass dns firewall, go-through app specific ip and domain rules before applying
            if (appStatus.bypassDnsFirewall()) {
                return FirewallRuleset.RULE1H
            }

            // isolate mode
            if (appStatus.isolate()) {
                return FirewallRuleset.RULE1G
            }

            val globalDomainRule = getDomainRule(connInfo.query, UID_EVERYBODY)

            // should firewall rules by-pass universal firewall rules (previously whitelist)
            if (appStatus.bypassUniversal()) {
                // bypass universal should block the domains that are blocked by dns (local/remote)
                // unless the domain is trusted by the user
                if (anyRealIpBlocked && globalDomainRule != DomainRulesManager.Status.TRUST) {
                    return FirewallRuleset.RULE2G
                }

                return if (dnsProxied(connInfo.destPort)) {
                    FirewallRuleset.RULE9
                } else {
                    FirewallRuleset.RULE8
                }
            }

            // check for global domain allow/block domains
            when (globalDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    return FirewallRuleset.RULE2I
                }
                DomainRulesManager.Status.BLOCK -> {
                    return FirewallRuleset.RULE2H
                }
                else -> {
                    // fall through
                }
            }

            // should ip rules by-pass or block universal firewall rules
            when (globalIpStatus(connInfo.destIP, connInfo.destPort)) {
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    return FirewallRuleset.RULE2D
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    return FirewallRuleset.RULE2C
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    // no-op; pass-through
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    // no-op; pass-through
                }
            }

            // if any of the real ip is blocked then allow only if it is trusted,
            // otherwise no need to check further
            if (anyRealIpBlocked) {
                return FirewallRuleset.RULE2G
            } else {
                // no-op; pass-through
            }

            val isMetered = isConnectionMetered(connInfo.destIP)
            // block all metered connections (Universal firewall setting)
            if (persistentState.getBlockMeteredConnections() && isMetered) {
                return FirewallRuleset.RULE1F
            }

            // block apps when universal lockdown is enabled
            if (universalLockdown()) {
                return FirewallRuleset.RULE11
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

            // if connInfo.query is empty, then it is not resolved by user set dns
            if (dnsBypassed(connInfo.query)) {
                return FirewallRuleset.RULE7
            }
        } catch (iex: Exception) {
            // TODO: show alerts to user on such exceptions, in a separate ui?
            Log.e(LOG_TAG_VPN, "err blocking conn, block anyway", iex)
            return FirewallRuleset.RULE1C
        }

        logd("no firewall rule, uid=${connInfo.uid}")
        return FirewallRuleset.RULE0
    }

    private fun getDomainRule(domain: String?, uid: Int): DomainRulesManager.Status {
        if (domain.isNullOrEmpty()) {
            return DomainRulesManager.Status.NONE
        }

        return DomainRulesManager.status(domain, uid)
    }

    private fun universalLockdown(): Boolean {
        return persistentState.getUniversalLockdown()
    }

    private fun httpBlocked(port: Int): Boolean {
        // no need to check if the port is not HTTP port
        if (port != KnownPorts.HTTP_PORT) {
            return false
        }

        return persistentState.getBlockHttpConnections()
    }

    private suspend fun allowOrbot(uid: Int): Boolean {
        return settingUpOrbot.get() &&
            OrbotHelper.ORBOT_PACKAGE_NAME == FirewallManager.getPackageNameByUid(uid)
    }

    private fun dnsProxied(port: Int): Boolean {
        return (appConfig.getBraveMode().isDnsFirewallMode() &&
            appConfig.preventDnsLeaks() &&
            isDns(port))
    }

    private fun dnsBypassed(query: String?): Boolean {
        return if (!persistentState.getDisallowDnsBypass()) {
            false
        } else {
            query.isNullOrEmpty()
        }
    }

    private suspend fun waitAndCheckIfUidBlocked(uid: Int): Boolean {
        val allowed = testWithBackoff {
            FirewallManager.hasUid(uid) && !FirewallManager.isUidFirewalled(uid)
        }
        return !allowed
    }

    private suspend fun newAppBlocked(uid: Int): Boolean {
        return if (!persistentState.getBlockNewlyInstalledApp() || isMissingOrInvalidUid(uid)) {
            false
        } else {
            waitAndCheckIfUidBlocked(uid)
        }
    }

    private fun ipStatus(uid: Int, destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        return IpRulesManager.hasRule(uid, destIp, destPort)
    }

    private fun globalIpStatus(destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        return IpRulesManager.hasRule(UID_EVERYBODY, destIp, destPort)
    }

    private fun unknownAppBlocked(uid: Int): Boolean {
        return if (!persistentState.getBlockUnknownConnections()) {
            false
        } else {
            isMissingOrInvalidUid(uid)
        }
    }

    private suspend fun testWithBackoff(
        stallSec: Long = 20,
        durationSec: Long = 10,
        test: suspend () -> Boolean
    ): Boolean {
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

    private suspend fun udpBlocked(uid: Int, protocol: Int, port: Int): Boolean {
        val hasUserBlockedUdp = persistentState.getUdpBlocked()
        if (!hasUserBlockedUdp) return false

        val isUdp = protocol == Protocol.UDP.protocolType
        if (!isUdp) return false

        // fall through dns requests, other rules might catch as appropriate
        // https://github.com/celzero/rethink-app/issues/492#issuecomment-1299090538
        if (isDns(port)) return false

        val isNtpFromSystemApp = KnownPorts.isNtp(port) && FirewallManager.isUidSystemApp(uid)
        return !isNtpFromSystemApp
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
    // Earlier the screen lock is detected with receiver received for Action
    // user_present/screen_off.
    // Now the code only checks whether the KeyguardManager#isKeyguardLocked() is true/false.
    // if isKeyguardLocked() is true, the connections will be blocked.
    private fun deviceLocked(): Boolean {
        if (!persistentState.getBlockWhenDeviceLocked()) return false

        if (keyguardManager == null) {
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        return (keyguardManager?.isKeyguardLocked == true)
    }

    // Check if the app has firewall rules set
    // refer: FirewallManager.kt line-no#58
    private fun appBlocked(
        connInfo: ConnTrackerMetaData,
        connectionStatus: FirewallManager.ConnectionStatus
    ): FirewallRuleset? {
        if (isAppBlocked(connectionStatus)) {
            return FirewallRuleset.RULE1
        }

        // when use all network / vpn lockdown is on, metered / unmetered rules won't be considered
        if (persistentState.useMultipleNetworks || VpnController.isVpnLockdown()) {
            return null
        }

        val isMetered = isConnectionMetered(connInfo.destIP)
        if (isWifiBlockedForUid(connectionStatus) && !isMetered) {
            return FirewallRuleset.RULE1D
        }

        if (isMobileDataBlockedForUid(connectionStatus) && isMetered) {
            return FirewallRuleset.RULE1E
        }

        return null
    }

    private fun isConnectionMetered(dst: String): Boolean {
        if (persistentState.useMultipleNetworks || VpnController.isVpnLockdown()) {
            return isIfaceMetered(dst)
        }
        return isActiveIfaceMetered()
    }

    private fun isIfaceMetered(dst: String): Boolean {
        val dest = IPAddressString(dst)
        // TODO: check for all networks instead of just the first one
        val cap =
            if (dest.isIPv6) {
                // if there are no network to be bound, fallback to active network
                if (underlyingNetworks?.ipv6Net?.isEmpty() == true) {
                    return isActiveIfaceMetered()
                }
                underlyingNetworks?.ipv6Net?.firstOrNull()?.capabilities
            } else {
                // if there are no network to be bound, fallback to active network
                if (underlyingNetworks?.ipv4Net?.isEmpty() == true) {
                    return isActiveIfaceMetered()
                }
                underlyingNetworks?.ipv4Net?.firstOrNull()?.capabilities
            }

        // if there are no network to be bound given a destination IP, fallback to active network
        if (cap == null) {
            Log.e(LOG_TAG_VPN, "no network to be bound for $dst, use active network")
            return isActiveIfaceMetered()
        }
        return !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isActiveIfaceMetered(): Boolean {
        val now = elapsedRealtime()
        val ts = underlyingNetworks?.lastUpdated
        // Added the INIT_TIME_MS check, encountered a bug during phone restart
        // isAccessibilityServiceRunning default value(false) is passed instead of
        // checking it from accessibility service for the first time.
        if (ts == null || Math.abs(now - ts) > Constants.ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            underlyingNetworks?.lastUpdated = now
            underlyingNetworks?.isActiveNetworkMetered = connectivityManager.isActiveNetworkMetered
        }
        return underlyingNetworks?.isActiveNetworkMetered == true
    }

    private fun isAppBlocked(connectionStatus: FirewallManager.ConnectionStatus): Boolean {
        return connectionStatus.blocked()
    }

    private fun isMobileDataBlockedForUid(
        connectionStatus: FirewallManager.ConnectionStatus
    ): Boolean {
        return connectionStatus.mobileData()
    }

    private fun isWifiBlockedForUid(connectionStatus: FirewallManager.ConnectionStatus): Boolean {
        return connectionStatus.wifi()
    }

    private suspend fun blockBackgroundData(uid: Int): Boolean {
        if (!persistentState.getBlockAppWhenBackground()) return false

        if (!accessibilityServiceFunctional()) {
            Log.w(LOG_TAG_VPN, "accessibility service not functional, disable bg-block")
            handleAccessibilityFailure()
            return false
        }

        val allowed = testWithBackoff { FirewallManager.isAppForeground(uid, keyguardManager) }

        return !allowed
    }

    private fun handleAccessibilityFailure() {
        // Disable app not in use behaviour when the accessibility failure is detected.
        persistentState.setBlockAppWhenBackground(false)
        showAccessibilityStoppedNotification()
    }

    private fun showAccessibilityStoppedNotification() {
        Log.i(LOG_TAG_VPN, "app not in use failure, show notification")

        val intent = Intent(this, NotificationHandlerDialog::class.java)
        intent.putExtra(
            NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME,
            NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
        )

        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, HomeScreenActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )

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

        val contentTitle: String = this.resources.getString(R.string.lbl_action_required)
        val contentText: String =
            this.resources.getString(R.string.accessibility_notification_content)

        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(pendingIntent)
            .setContentText(contentText)

        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(this, getAccentColor(persistentState.theme))

        // Secret notifications are not shown on the lock screen.  No need for this app to show
        // there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // Cancel the notification after clicking.
        builder.setAutoCancel(true)

        notificationManager.notify(
            NOTIF_CHANNEL_ID_FIREWALL_ALERTS,
            NOTIF_ID_ACCESSIBILITY_FAILURE,
            builder.build()
        )
    }

    private fun accessibilityServiceFunctional(): Boolean {
        val now = elapsedRealtime()
        // Added the INIT_TIME_MS check, encountered a bug during phone restart
        // isAccessibilityServiceRunning default value(false) is passed instead of
        // checking it from accessibility service for the first time.
        if (
            accessibilityHearbeatTimestamp == INIT_TIME_MS ||
                Math.abs(now - accessibilityHearbeatTimestamp) >
                    Constants.ACCESSIBILITY_SERVICE_HEARTBEAT_THRESHOLD_MS
        ) {
            accessibilityHearbeatTimestamp = now

            isAccessibilityServiceFunctional =
                Utilities.isAccessibilityServiceEnabled(
                    this,
                    BackgroundAccessibilityService::class.java
                ) &&
                    Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                        this,
                        BackgroundAccessibilityService::class.java
                    )
        }
        return isAccessibilityServiceFunctional
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

    /**
     * Records the network transaction in local database The logs will be shown in network monitor
     * screen
     */
    private fun connTrack(info: ConnTrackerMetaData?) {
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

        try {
            if (!VpnController.isVpnLockdown() && isAppPaused()) {
                val nonFirewalledApps = FirewallManager.getNonFirewalledAppsPackageNames()
                Log.i(
                    LOG_TAG_VPN,
                    "app is in pause state, exclude all the non firewalled apps, size: ${nonFirewalledApps.count()}"
                )
                nonFirewalledApps.forEach { builder.addDisallowedApplication(it.packageName) }
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
                        Log.i(LOG_TAG_VPN, "builder, exclude package: $it")
                    }
                } else {
                    Log.w(LOG_TAG_VPN, "builder, vpn is lockdown, ignoring exclude-apps list")
                }
            }

            // route rethink traffic in rethink based on the user selection
            if (!persistentState.routeRethinkInRethink) {
                Log.i(LOG_TAG_VPN, "builder, do not route rethink traffic in rethink")
                builder = builder.addDisallowedApplication(this.packageName)
            } else {
                Log.i(LOG_TAG_VPN, "builder, route rethink traffic in rethink")
                // no-op
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(LOG_TAG_VPN, "cannot exclude the dns proxy app", e)
        }
        return builder
    }

    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        VpnController.onVpnCreated(this)

        io("loggers") { netLogTracker.startLogger(vpnScope) }

        notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        activityManager = this.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        accessibilityManager = this.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        keyguardManager = this.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        connectivityManager =
            this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (persistentState.getBlockAppWhenBackground()) {
            registerAccessibilityServiceState()
        }
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
            try {
                var latestExcludedApps: Set<String>
                // adding synchronized block, found a case of concurrent modification
                // exception that happened once when trying to filter the received object (t).
                // creating a copy of the received value in a synchronized block.
                synchronized(t) {
                    val copy: List<AppInfo> = mutableListOf<AppInfo>().apply { addAll(t) }
                    latestExcludedApps =
                        copy
                            .filter {
                                it.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id
                            }
                            .map(AppInfo::packageName)
                            .toSet()
                }

                if (Sets.symmetricDifference(excludedApps, latestExcludedApps).isEmpty())
                    return@Observer

                Log.i(LOG_TAG_VPN, "excluded-apps list changed, restart vpn")

                io("excludeApps") { restartVpnWithExistingAppConfig() }
            } catch (e: Exception) { // NoSuchElementException, ConcurrentModification
                Log.e(LOG_TAG_VPN, "error retrieving value from appInfos observer ${e.message}", e)
            }
        }
    }

    private fun makeOrbotStartStatusObserver(): Observer<Boolean> {
        return Observer<Boolean> { settingUpOrbot.set(it) }
    }

    private fun updateNotificationBuilder(): NotificationCompat.Builder {
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, HomeScreenActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )
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

        var contentTitle: String =
            when (appConfig.getBraveMode()) {
                AppConfig.BraveMode.DNS -> resources.getString(R.string.dns_mode_notification_title)
                AppConfig.BraveMode.FIREWALL ->
                    resources.getString(R.string.firewall_mode_notification_title)
                AppConfig.BraveMode.DNS_FIREWALL ->
                    resources.getString(R.string.hybrid_mode_notification_title)
            }

        if (isAppPaused()) {
            contentTitle = resources.getString(R.string.pause_mode_notification_title)
        }

        builder.setSmallIcon(R.drawable.ic_notification_icon).setContentIntent(pendingIntent)
        builder.color = ContextCompat.getColor(this, getAccentColor(persistentState.theme))
        // from docs, Starting in Android 13 (API level 33), users can dismiss the notification
        // associated with a foreground service by default. To do so, users perform a swipe gesture
        // on the notification. On previous versions of Android, the notification can't be dismissed
        // unless the foreground service is either stopped or removed from the foreground.
        // make it ongoing to prevent that. https://github.com/celzero/rethink-app/issues/1136
        if (persistentState.persistentNotification) {
            builder.setOngoing(true)
        }

        // New action button options in the notification
        // 1. Pause / Resume, Stop action button.
        // 2. RethinkDNS modes (dns & dns+firewall mode)
        // 3. No action button.
        logd("notification action type:  ${persistentState.notificationActionType}")

        when (
            NotificationActionType.getNotificationActionType(persistentState.notificationActionType)
        ) {
            NotificationActionType.PAUSE_STOP -> {
                // Add the action based on AppState (PAUSE/ACTIVE)
                val openIntent1 =
                    makeVpnIntent(NOTIF_ACTION_MODE_STOP, Constants.NOTIF_ACTION_STOP_VPN)
                val notificationAction1 =
                    NotificationCompat.Action(
                        0,
                        resources.getString(R.string.notification_action_stop_vpn),
                        openIntent1
                    )
                builder.addAction(notificationAction1)
                // set content title for notifications which has actions
                builder.setContentTitle(contentTitle)

                if (isAppPaused()) {
                    val openIntent2 =
                        makeVpnIntent(NOTIF_ACTION_MODE_RESUME, Constants.NOTIF_ACTION_RESUME_VPN)
                    val notificationAction2 =
                        NotificationCompat.Action(
                            0,
                            resources.getString(R.string.notification_action_resume_vpn),
                            openIntent2
                        )
                    builder.addAction(notificationAction2)
                } else {
                    val openIntent2 =
                        makeVpnIntent(NOTIF_ACTION_MODE_PAUSE, Constants.NOTIF_ACTION_PAUSE_VPN)
                    val notificationAction2 =
                        NotificationCompat.Action(
                            0,
                            resources.getString(R.string.notification_action_pause_vpn),
                            openIntent2
                        )
                    builder.addAction(notificationAction2)
                }
            }
            NotificationActionType.DNS_FIREWALL -> {
                val openIntent1 =
                    makeVpnIntent(NOTIF_ACTION_MODE_DNS_ONLY, Constants.NOTIF_ACTION_DNS_VPN)
                val openIntent2 =
                    makeVpnIntent(
                        NOTIF_ACTION_MODE_DNS_FIREWALL,
                        Constants.NOTIF_ACTION_DNS_FIREWALL_VPN
                    )
                val notificationAction: NotificationCompat.Action =
                    NotificationCompat.Action(
                        0,
                        resources.getString(R.string.notification_action_dns_mode),
                        openIntent1
                    )
                val notificationAction2: NotificationCompat.Action =
                    NotificationCompat.Action(
                        0,
                        resources.getString(R.string.notification_action_dns_firewall_mode),
                        openIntent2
                    )
                builder.addAction(notificationAction)
                builder.addAction(notificationAction2)
                // set content title for notifications which has actions
                builder.setContentTitle(contentTitle)
            }
            NotificationActionType.NONE -> {
                Log.i(LOG_TAG_VPN, "No notification action")
            }
        }

        // Secret notifications are not shown on the lock screen.  No need for this app to show
        // there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        builder.build()
        return builder
    }

    // keep in sync with RefreshDatabase#makeVpnIntent
    private fun makeVpnIntent(notificationID: Int, intentExtra: String): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java)
        intent.putExtra(Constants.NOTIFICATION_ACTION, intentExtra)
        return Utilities.getBroadcastPendingIntent(
            this,
            notificationID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            mutable = false
        )
    }

    private fun getRethinkUid(): Int {
        return Utilities.getApplicationInfo(this, this.packageName)?.uid ?: INVALID_UID
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rethinkUid = getRethinkUid()

        VpnController.onConnectionStateChanged(State.NEW)

        ui {
            var isNewVpn = true

            // Initialize the value whenever the vpn is started.
            accessibilityHearbeatTimestamp = INIT_TIME_MS

            startOrbotAsyncIfNeeded()

            // startForeground should always be called within 5 secs of onStartCommand invocation
            // https://developer.android.com/guide/components/fg-service-types
            // java.lang.IllegalArgumentException: foregroundServiceType 0x00000400 is
            // not a subset of foregroundServiceType attribute 0x00000000 in service element of
            // manifest file (below code is commented out until complete analysis of
            // FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) (v055b)
            /*ServiceCompat.startForeground(
                this,
                SERVICE_ID,
                updateNotificationBuilder().build(),
                FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )*/
            startForeground(SERVICE_ID, updateNotificationBuilder().build())
            // this should always be set before ConnectionMonitor is init-d
            // see restartVpn and updateTun which expect this to be the case
            persistentState.setVpnEnabled(true)

            VpnController.mutex.withLock {
                // if service is up (aka connectionMonitor not null)
                // then simply update the existing tunnel
                if (connectionMonitor == null) {
                    underlyingNetworks = null
                    connectionMonitor = ConnectionMonitor(this, this)
                    connectionMonitor?.onVpnStartLocked()
                } else {
                    isNewVpn = false
                }
            }

            val mtu =
                if (persistentState.useMaxMtu) {
                    MAX_INTERFACE_MTU
                } else {
                    VPN_INTERFACE_MTU
                }
            val opts =
                appConfig.newTunnelOptions(
                    this,
                    getFakeDns(),
                    appConfig.getInternetProtocol(),
                    appConfig.getProtocolTranslationMode(),
                    mtu
                )

            Log.i(LOG_TAG_VPN, "start-foreground with opts $opts (for new-vpn? $isNewVpn)")
            if (!isNewVpn) {
                io("tunUpdate") {
                    // may call signalStopService(userInitiated=false) if go-vpn-adapter is missing
                    // which is the inverse of actually starting the vpn! But that's okay, since
                    // it indicates that something is out of whack (as in, connection monitor
                    // exists, vpn service exists, but the underlying adapter doesn't...
                    updateTun(opts)
                }
            } else {
                io("startVpn") {
                    // refresh should happen before restartVpn, otherwise the new vpn will not
                    // have app, ip, domain rules. See RefreshDatabase#refresh
                    rdb.refresh(RefreshDatabase.ACTION_REFRESH_AUTO)
                    restartVpn(opts)
                }
                // call this *after* a new vpn is created #512
                observeChanges()
            }
        }
        return Service.START_STICKY
    }

    private fun startOrbotAsyncIfNeeded() {
        if (!appConfig.isOrbotProxyEnabled()) return

        io("startOrbot") { orbotHelper.startOrbot(appConfig.getProxyType()) }
    }

    private fun unobserveAppInfos() {
        // fix for issue #648 (UninitializedPropertyAccessException)
        if (this::appInfoObserver.isInitialized) {
            FirewallManager.getApplistObserver().removeObserver(appInfoObserver)
        }
    }

    private fun unobserveOrbotStartStatus() {
        // fix for issue #648 (UninitializedPropertyAccessException)
        if (this::orbotStartStatusObserver.isInitialized) {
            persistentState.orbotConnectionStatus.removeObserver(orbotStartStatusObserver)
        }
    }

    private fun registerAccessibilityServiceState() {
        accessibilityListener =
            AccessibilityManager.AccessibilityStateChangeListener { b ->
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
        Log.i(LOG_TAG_VPN, "update-tun with new pre-set tunnel options")
        VpnController.mutex.withLock {
            // Connection monitor can be null if onDestroy() of service
            // is called, in that case no need to call updateTun()
            if (connectionMonitor == null) {
                Log.w(LOG_TAG_VPN, "skip update-tun, connection-monitor missing")
                return
            }
        }
        // FIXME: protect getVpnEnabledLocked with mutex everywhere?
        if (!persistentState.getVpnEnabledLocked()) {
            // when persistent-state "thinks" vpn is disabled, stop the service, especially when
            // we could be here via onStartCommand -> updateTun -> handleVpnAdapterChange while
            // conn-monitor and go-vpn-adapter exist, but persistent-state tracking vpn goes out
            // of sync
            Log.e(LOG_TAG_VPN, "stop-vpn(updateTun), tracking vpn is out of sync")
            io("outOfSync") { signalStopService(userInitiated = false) }
            return
        }

        // should not be called in normal circumstances, but just in case...
        val ok = vpnAdapter?.updateTun(tunnelOptions)
        // TODO: like Intra, call VpnController#stop instead? see
        // VpnController#onStartComplete
        if (ok == false) {
            Log.w(LOG_TAG_VPN, "Cannot handle vpn adapter changes, no tunnel")
            io("noTunnel") { signalStopService(userInitiated = false) }
            return
        }
        notifyConnectionStateChangeIfNeeded()
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        /* TODO Check on the Persistent State variable
        Check on updating the values for Package change and for mode change.
        As of now handled manually */
        logd("on pref change, key: $key")
        when (key) {
            PersistentState.BRAVE_MODE -> {
                io("modeChange") { setTunMode() }
                notificationManager.notify(SERVICE_ID, updateNotificationBuilder().build())
            }
            PersistentState.LOCAL_BLOCK_LIST -> {
                io("localBlocklistEnable") { setRDNS() }
            }
            PersistentState.LOCAL_BLOCK_LIST_UPDATE -> {
                // FIXME: update just that local bravedns obj, not the entire tunnel
                io("localBlocklistDownload") { setRDNS() }
            }
            PersistentState.BACKGROUND_MODE -> {
                if (persistentState.getBlockAppWhenBackground()) {
                    registerAccessibilityServiceState()
                } else {
                    unregisterAccessibilityServiceState()
                }
            }
            PersistentState.LOCAL_BLOCK_LIST_STAMP -> { // update on local blocklist stamp change
                spawnLocalBlocklistStampUpdate()
            }
            PersistentState.REMOTE_BLOCKLIST_UPDATE -> {
                io("remoteBlocklistUpdate") {
                    addTransport()
                    setRDNS()
                }
            }
            PersistentState.DNS_CHANGE -> {
                /*
                 * Handles the DNS type changes.
                 * DNS Proxy - Requires restart of the VPN.
                 * DNSCrypt - Set the tunnel with DNSCrypt mode once the live servers size is not 0.
                 * DOH - Overwrites the tunnel values with new values.
                 */
                // FIXME: update just that dns proxy, not the entire tunnel
                io("dnsChange") {
                    when (appConfig.getDnsType()) {
                        AppConfig.DnsType.DOH -> {
                            addTransport()
                        }
                        AppConfig.DnsType.DNSCRYPT -> {
                            addTransport()
                        }
                        AppConfig.DnsType.DNS_PROXY -> {
                            addTransport()
                        }
                        AppConfig.DnsType.RETHINK_REMOTE -> {
                            addTransport()
                        }
                        AppConfig.DnsType.SYSTEM_DNS -> {
                            setDnsServers()
                        }
                        AppConfig.DnsType.DOT -> {
                            addTransport()
                        }
                        AppConfig.DnsType.ODOH -> {
                            addTransport()
                        }
                    }
                }
            }
            PersistentState.DNS_RELAYS -> {
                // FIXME: add relay using vpnAdapter; no update needed
                io("updateDnscrypt") { addTransport() }
            }
            PersistentState.ALLOW_BYPASS -> {
                io("allowBypass") { restartVpn(createNewTunnelOptsObj()) }
            }
            PersistentState.PROXY_TYPE -> {
                io("proxy") { handleProxyChange() }
            }
            PersistentState.NETWORK -> {
                io("useAllNetworks") { notifyConnectionMonitor() }
            }
            PersistentState.NOTIFICATION_ACTION -> {
                notificationManager.notify(SERVICE_ID, updateNotificationBuilder().build())
            }
            PersistentState.INTERNET_PROTOCOL -> {
                io("chooseIpVersion") { handleIPProtoChanges() }
            }
            PersistentState.PROTOCOL_TRANSLATION -> {
                io("forceV4Egress") { setTunMode() }
            }
            PersistentState.DEFAULT_DNS_SERVER -> {
                io("defaultDnsServer") {
                    logd(
                        "default transport server changed, change: ${persistentState.defaultDnsUrl}"
                    )
                    vpnAdapter?.addDefaultTransport(persistentState.defaultDnsUrl)
                }
            }
            PersistentState.PCAP_MODE -> {
                io("pcap") { setPcapMode() }
            }
            PersistentState.DNS_ALG -> {
                io("dnsAlg") { updateDnsAlg() }
            }
            PersistentState.PRIVATE_IPS -> {
                // restart vpn to enable/disable route lan traffic
                io("routeLanTraffic") { restartVpn(createNewTunnelOptsObj()) }
            }
            PersistentState.RETHINK_IN_RETHINK -> {
                // restart vpn to allow/disallow rethink traffic in rethink
                io("routeRethinkInRethink") { restartVpn(createNewTunnelOptsObj()) }
            }
            PersistentState.USE_MAX_MTU -> {
                // restart vpn to enable/disable use max mtu
                io("useMaxMtu") { restartVpn(createNewTunnelOptsObj()) }
            }
        }
    }

    private suspend fun setRDNS() {
        logd("set brave dns mode, local/remote")
        vpnAdapter?.setRDNS()
    }

    fun closeConnectionsIfNeeded(uid: Int) { // can be invalid uid, in which case, no-op
        if (uid == INVALID_UID) return

        // get app id from uid
        val uid0 = FirewallManager.appId(uid)

        val ids: MutableList<String> = mutableListOf()
        // when there is a change in firewall rule for uid, close all the existing connections
        trackedCids.filter { it.uid == uid0 }.forEach { ids.add(it.cid) }

        if (ids.isEmpty()) return

        vpnAdapter?.closeConnections(ids)
    }

    private suspend fun addTransport() {
        // TODO: no need to call addTransport in case of relay changes, which we are doing now
        logd("handle transport change")
        vpnAdapter?.addTransport()
    }

    private suspend fun handleIPProtoChanges() {
        logd("handle ip proto changes")
        if (InternetProtocol.isAuto(persistentState.internetProtocolType)) {
            // initiates connectivity checks if Auto mode and calls onNetworkConnected
            // or onNetworkDisconnected. onNetworkConnected may call restartVpn and setRoute on
            // route changes as informed by the connection monitor
            notifyConnectionMonitor()
        }
        restartVpn(createNewTunnelOptsObj())
        setRoute()
    }

    private suspend fun handleProxyChange() {
        val tunProxyMode = appConfig.getTunProxyMode()
        val proxy = AppConfig.ProxyProvider.getProxyProvider(appConfig.getProxyProvider())
        logd("handle proxy change, proxy: ${proxy.name}")
        when (proxy) {
            AppConfig.ProxyProvider.NONE -> {
                // no-op
            }
            AppConfig.ProxyProvider.TCP -> {
                vpnAdapter?.setTcpProxy()
            }
            AppConfig.ProxyProvider.WIREGUARD -> {
                // update wireguard config
                vpnAdapter?.setWireguardTunnelModeIfNeeded(tunProxyMode)
            }
            AppConfig.ProxyProvider.ORBOT -> {
                // update orbot config, its treated as SOCKS5 or HTTP proxy internally
                vpnAdapter?.setCustomProxy(tunProxyMode)
            }
            AppConfig.ProxyProvider.CUSTOM -> {
                // custom either means socks5 or http proxy
                vpnAdapter?.setCustomProxy(tunProxyMode)
            }
        }
    }

    private fun createNewTunnelOptsObj(): AppConfig.TunnelOptions {
        val mtu =
            if (persistentState.useMaxMtu) {
                MAX_INTERFACE_MTU
            } else {
                VPN_INTERFACE_MTU
            }
        val opts =
            appConfig.newTunnelOptions(
                this,
                getFakeDns(),
                appConfig.getInternetProtocol(),
                appConfig.getProtocolTranslationMode(),
                mtu
            )
        Log.i(LOG_TAG_VPN, "created new tunnel options, opts: $opts")
        return opts
    }

    private fun spawnLocalBlocklistStampUpdate() {
        io("dnsStampUpdate") { vpnAdapter?.setRDNSStamp() }
    }

    private suspend fun notifyConnectionMonitor() {
        VpnController.mutex.withLock { connectionMonitor?.onUserPreferenceChangedLocked() }
    }

    private suspend fun updateDnsAlg() {
        vpnAdapter?.setDnsAlg()
    }

    fun signalStopService(userInitiated: Boolean = true) {
        if (!userInitiated) notifyUserOnVpnFailure()
        stopVpnAdapter()
        stopSelf()
        Log.i(LOG_TAG_VPN, "stopped vpn adapter and vpn service")
    }

    private fun stopVpnAdapter() {
        io("stopVpn") {
            if (vpnAdapter == null) {
                Log.i(LOG_TAG_VPN, "vpn adapter already stopped")
                return@io
            }
            vpnAdapter?.closeTun()
            vpnAdapter = null
            Log.i(LOG_TAG_VPN, "stop vpn adapter")
        }
    }

    private suspend fun restartVpnWithExistingAppConfig() {
        val mtu =
            if (persistentState.useMaxMtu) {
                MAX_INTERFACE_MTU
            } else {
                VPN_INTERFACE_MTU
            }
        restartVpn(
            appConfig.newTunnelOptions(
                this,
                getFakeDns(),
                appConfig.getInternetProtocol(),
                appConfig.getProtocolTranslationMode(),
                mtu
            )
        )
    }

    private suspend fun setPcapMode() {
        val pcapPath = appConfig.getPcapFilePath()
        if (pcapPath.isEmpty()) {
            Log.i(LOG_TAG_VPN, "pcap mode disabled")
            return
        }
        Log.i(LOG_TAG_VPN, "pcap mode enabled, path: $pcapPath")
        vpnAdapter?.setPcapMode(pcapPath)
    }

    private suspend fun setTunMode() {
        val mtu =
            if (persistentState.useMaxMtu) {
                MAX_INTERFACE_MTU
            } else {
                VPN_INTERFACE_MTU
            }
        val tunnelOptions =
            appConfig.newTunnelOptions(
                this,
                getFakeDns(),
                appConfig.getInternetProtocol(),
                appConfig.getProtocolTranslationMode(),
                mtu
            )
        Log.i(
            LOG_TAG_VPN,
            "set tun mode with dns: ${tunnelOptions.tunDnsMode}, firewall: ${tunnelOptions.tunFirewallMode}, proxy: ${tunnelOptions.tunProxyMode}, pt: ${tunnelOptions.ptMode}"
        )
        vpnAdapter?.setTunMode(tunnelOptions)
    }

    private suspend fun restartVpn(opts: AppConfig.TunnelOptions) {
        var tunFd: ParcelFileDescriptor? = null
        VpnController.mutex.withLock {
            // connectionMonitor = null indicates onStartCommand has not yet been called
            if (connectionMonitor == null) {
                Log.e(
                    LOG_TAG_VPN,
                    "cannot restart-vpn, conn monitor null! Was onStartCommand called?"
                )
                return
            }
        }
        // FIXME: protect getVpnEnabledLocked with mutex everywhere?
        if (!persistentState.getVpnEnabledLocked()) {
            // when persistent-state "thinks" vpn is disabled, stop the service, especially when
            // we could be here via onStartCommand -> isNewVpn -> restartVpn while both,
            // vpn-service & conn-monitor exists & vpn-enabled state goes out of sync
            Log.e(
                LOG_TAG_VPN,
                "stop-vpn(restartVpn), tracking vpn is out of sync",
                java.lang.RuntimeException()
            )
            io("outOfSyncRestart") { signalStopService(userInitiated = false) }
            return
        }

        // attempt seamless hand-off as described in VpnService.Builder.establish() docs
        tunFd = establishVpn()
        if (tunFd == null) {
            Log.e(LOG_TAG_VPN, "cannot restart-vpn, no tun-fd")
            io("noTunRestart") { signalStopService(userInitiated = false) }
            return
        }
        val ok = makeOrUpdateVpnAdapter(tunFd, opts)
        Log.i(LOG_TAG_VPN, "restartVpn? ${vpnAdapter != null}, done? $ok")
        if (!ok) {
            Log.w(LOG_TAG_VPN, "cannot handle vpn adapter changes, no tunnel")
            io("noTunnelRestart") { signalStopService(userInitiated = false) }
            return
        } // else: tunnel is up, fall through

        notifyConnectionStateChangeIfNeeded()
    }

    private fun notifyConnectionStateChangeIfNeeded() {
        // Case: Set state to working in case of Firewall mode
        if (appConfig.getBraveMode().isFirewallMode()) {
            VpnController.onConnectionStateChanged(State.WORKING)
        }
    }

    // protected by vpncontroller.mutex
    fun hasTunnel(): Boolean {
        // FIXME: protect vpnAdapter with mutex
        return vpnAdapter?.hasTunnel() == true
    }

    fun isOn(): Boolean {
        // FIXME: protect vpnAdapter with mutex
        return vpnAdapter != null
    }

    suspend fun refreshResolvers() {
        Log.i(LOG_TAG_VPN, "refresh resolvers")
        vpnAdapter?.refreshResolvers()
    }

    private suspend fun makeOrUpdateVpnAdapter(
        tunFd: ParcelFileDescriptor,
        opts: AppConfig.TunnelOptions
    ): Boolean {
        val ok = true
        val noTun = false // should eventually call signalStopService(userInitiated=false)

        try {
            if (vpnAdapter != null) {
                Log.i(LOG_TAG_VPN, "vpn-adapter exists, use it")
                // in case, if vpn-adapter exists, update the existing vpn-adapter
                if (vpnAdapter?.updateLink(tunFd, opts) == false) {
                    Log.e(LOG_TAG_VPN, "err update vpn-adapter")
                    return noTun
                }
                return ok
            } else {
                // create a new vpn adapter
                vpnAdapter = GoVpnAdapter(this, vpnScope, tunFd, opts) // may throw
                GoVpnAdapter.setLogLevel(persistentState.goLoggerLevel)
                vpnAdapter!!.initResolverProxiesPcap(opts)
                return ok
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err new vpn-adapter: ${e.message}", e)
            return noTun
        } finally {
            try { // close the tunFd as GoVpnAdapter has its own copy
                tunFd.close()
            } catch (ignored: IOException) {
                Log.e(LOG_TAG_VPN, "err closing tunFd: ${ignored.message}", ignored)
            }
        }
    }

    // TODO: #294 - Figure out a way to show users that the device is offline instead of status as
    // failing.
    override fun onNetworkDisconnected(newNetworks: ConnectionMonitor.UnderlyingNetworks) {
        underlyingNetworks = newNetworks
        Log.i(
            LOG_TAG_VPN,
            "onNetworkDisconnected: Underlying networks: null, state: z, $underlyingNetworks"
        )
        setUnderlyingNetworks(null)
        VpnController.onConnectionStateChanged(null)
    }

    override fun onNetworkConnected(newNetworks: ConnectionMonitor.UnderlyingNetworks) {
        val isRoutesChanged = isUnderlyingRoutesChanged(underlyingNetworks, newNetworks)
        underlyingNetworks = newNetworks
        Log.i(LOG_TAG_VPN, "connecting to networks, $underlyingNetworks, $isRoutesChanged")

        // always reset the system dns server ip of the active network with the tunnel
        setDnsServers()

        if (newNetworks.useActive) {
            setUnderlyingNetworks(null)
        } else if (newNetworks.ipv4Net.isEmpty() && newNetworks.ipv6Net.isEmpty()) {
            Log.w(LOG_TAG_VPN, "network changed but empty ipv4/ipv6 networks w connectivity")
            setUnderlyingNetworks(null)
        } else {
            // add ipv4/ipv6 networks to the tunnel
            val allNetworks =
                newNetworks.ipv4Net.map { it.network } + newNetworks.ipv6Net.map { it.network }
            setUnderlyingNetworks(allNetworks.toTypedArray())
        }
        // restart vpn if in ipv46 (Auto) mode
        if (appConfig.getInternetProtocol().isIPv46()) {
            io("restartVpnRouteChanges") { restartVpnWithExistingAppConfig() }
        }

        // Workaround for WireGuard connection issues after network change
        // WireGuard may fail to connect to the server when the network changes.
        // refresh will do a configuration refresh in tunnel to ensure a successful
        // reconnection after detecting a network change event
        if (appConfig.isWireGuardEnabled()) {
            refreshProxies()
        }
    }

    private fun setRoute() {
        logd("set route")
        // avoid calling setRoute as there is a unknown crash in go-vpn-adapter
        // to prevent that, do not set route on route changes, always set route
        // to v4, v6 routes regardless of the route changes
        // io("setRoute") { vpnAdapter?.setRoute(createNewTunnelOptsObj()) }
    }

    private fun isUnderlyingRoutesChanged(
        old: ConnectionMonitor.UnderlyingNetworks?,
        new: ConnectionMonitor.UnderlyingNetworks
    ): Boolean {
        // no old routes to compare with, return true
        if (old == null) return true

        val old6 = old.ipv6Net.isNotEmpty()
        val new6 = new.ipv6Net.isNotEmpty()
        val old4 = old.ipv4Net.isNotEmpty()
        val new4 = new.ipv4Net.isNotEmpty()
        // inform if ipv6 or ipv4 routes changed
        return old6 != new6 || old4 != new4
    }

    private fun setDnsServers() {
        val dnsServers =
            if (
                persistentState.routeRethinkInRethink || // ???
                    persistentState.useMultipleNetworks || // dns from all networks
                    VpnController.isVpnLockdown() // active nw is null when lockdown
            ) {
                val dl: MutableList<InetAddress> = mutableListOf()
                underlyingNetworks?.ipv4Net?.forEach {
                    val n = it.network
                    val lp = connectivityManager.getLinkProperties(n)
                    Log.i(LOG_TAG_VPN, "dns servers for network: $n, $lp")
                    lp?.dnsServers?.let { it1 -> dl.addAll(it1) }
                }
                underlyingNetworks?.ipv6Net?.forEach {
                    val n = it.network
                    val lp = connectivityManager.getLinkProperties(n)
                    Log.i(LOG_TAG_VPN, "dns servers for network: $n, $lp")
                    lp?.dnsServers?.let { it1 -> dl.addAll(it1) }
                }
                dl
            } else {
                // get dns servers from active network
                connectivityManager.getLinkProperties(connectivityManager.activeNetwork)?.dnsServers
            }

        if (dnsServers.isNullOrEmpty()) {
            // TODO: send an alert/notification instead?
            Log.w(LOG_TAG_VPN, "No system dns servers found")
            if (appConfig.isSystemDns()) {
                // on null dns servers, show toast
                ui {
                    showToastUiCentered(
                        this,
                        getString(R.string.system_dns_connection_failure),
                        Toast.LENGTH_LONG
                    )
                }
            } else {
                // no-op
            }
        }
        io("setSystemDns") {
            Log.i(LOG_TAG_VPN, "Setting dns servers: $dnsServers")
            appConfig.updateSystemDnsServers(dnsServers)
            // set system dns whenever there is a change in network
            vpnAdapter?.setSystemDns()
            // set default dns server for the tunnel if none is set
            if (isDefaultDnsNone()) {
                val dns = appConfig.getSystemDns()
                val d = HostName(IPAddressString(dns.ipAddress).address, dns.port).toString()
                vpnAdapter?.addDefaultTransport(d)
            }
        }
    }

    private fun isDefaultDnsNone(): Boolean {
        // if none is set then the url will either be empty or will not be one of the default dns
        return persistentState.defaultDnsUrl.isEmpty() ||
            !Constants.DEFAULT_DNS_LIST.any { it.url == persistentState.defaultDnsUrl }
    }

    private fun handleVpnLockdownStateAsync() {
        if (!syncLockdownState()) return
        io("lockdownSync") {
            Log.i(LOG_TAG_VPN, "vpn lockdown mode change, restarting")
            restartVpnWithExistingAppConfig()
        }
    }

    private fun syncLockdownState(): Boolean {
        if (!isAtleastQ()) return false

        val ret = isLockdownEnabled != isLockDownPrevious
        isLockDownPrevious = this.isLockdownEnabled
        return ret
    }

    private fun notifyUserOnVpnFailure() {
        ui {
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

            val pendingIntent =
                Utilities.getActivityPendingIntent(
                    this,
                    Intent(this, HomeScreenActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                    mutable = false
                )
            builder
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(resources.getText(R.string.warning_title))
                .setContentText(resources.getText(R.string.notification_content))
                .setContentIntent(pendingIntent)
                // Open the main UI if possible.
                .setAutoCancel(true)
            notificationManager.notify(0, builder.build())
        }
    }

    override fun onDestroy() {
        try {
            unregisterAccessibilityServiceState()
            orbotHelper.unregisterReceiver()
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_VPN, "Unregister receiver error: ${e.message}")
        }

        persistentState.setVpnEnabled(false)
        stopPauseTimer()

        unobserveOrbotStartStatus()
        unobserveAppInfos()
        persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        io("vpnDestroy") {
            VpnController.mutex.withLock {
                connectionMonitor?.onVpnStop()
                connectionMonitor = null
            }
        }
        VpnController.onVpnDestroyed()
        vpnScope.cancel("VpnService onDestroy")

        Log.w(LOG_TAG_VPN, "Destroying VPN service")

        // stop foreground service will take care of stopping the service for both
        // version >= 24 and < 24
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
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

    private fun handleVpnServiceOnAppStateChange() { // paused or resumed
        io("appStateChange") { restartVpnWithExistingAppConfig() }
        notificationManager.notify(SERVICE_ID, updateNotificationBuilder().build())
    }

    // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
    // assign the following values to the final byte of an address within a subnet.
    // Value of the final byte, to be substituted into the template.
    private enum class LanIp(private val value: Int) {
        GATEWAY(1),
        ROUTER(2),
        DNS(3);

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
            val mtu =
                if (persistentState.useMaxMtu) {
                    MAX_INTERFACE_MTU
                } else {
                    VPN_INTERFACE_MTU
                }
            var builder: VpnService.Builder = newBuilder().setSession("Rethink").setMtu(mtu)

            var has6 = route6()
            // always add ipv4 to the route, even though there is no ipv4 address
            // ICMPv6 is not handled in underlying tun2socks, so add ipv4 route even if the
            // selected protocol type is ipv6
            var has4 = route4()

            Log.i(LOG_TAG_VPN, "Building vpn for v4? $has4, v6? $has6")

            if (!has4 && !has6) {
                // no route available for both v4 and v6, add all routes
                // connectivity manager is expected to retry when no route is available
                // see ConnectionMonitor#repopulateTrackedNetworks
                Log.i(LOG_TAG_VPN, "No route available for v4 and v6, adding all routes")
                has4 = true
                has6 = true
            }

            // setup the gateway addr
            if (has4) {
                builder = addAddress4(builder)
            }
            if (has6) {
                builder = addAddress6(builder)
            }

            if (appConfig.getBraveMode().isDnsActive()) {
                // setup dns addrs and dns routes
                if (has4) {
                    builder = addDnsRoute4(builder)
                    builder = addDnsServer4(builder)
                }
                if (has6) {
                    builder = addDnsRoute6(builder)
                    builder = addDnsServer6(builder)
                }
            }
            if (appConfig.getBraveMode().isFirewallActive()) {
                // setup catch-all / default routes
                if (has4) {
                    builder = addRoute4(builder)
                }
                if (has6) {
                    builder = addRoute6(builder)
                }
            }
            return builder.establish()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            return null
        }
    }

    private fun route6(): Boolean {
        return when (appConfig.getInternetProtocol()) {
            InternetProtocol.IPv4 -> {
                false
            }
            InternetProtocol.IPv6 -> {
                true
            }
            InternetProtocol.IPv46 -> {
                // when no underlying-networks are unknown, or if use-multiple-networks is enabled,
                // simply check whether there are ANY v6 networks available; otherwise, if the vpn
                // must only use the active-network (always the first network in allNet), then check
                // if active-network has v6 connectivity (that is, it must be present in ipv6Net).
                // check if isReachable is true, if not, don't need to add route for v6 (return
                // false)
                logd(
                    "underlyingNetworks: ${underlyingNetworks?.useActive}, ${underlyingNetworks?.ipv6Net?.size}"
                )
                if (underlyingNetworks?.useActive != true) {
                    return if (underlyingNetworks?.ipv6Net?.size == 0) {
                        Log.i(LOG_TAG_VPN, "No IPv6 networks available")
                        false
                    } else {
                        Log.i(LOG_TAG_VPN, "IPv6 networks available")
                        true
                    }
                } else {
                    val activeNetwork = connectivityManager.activeNetwork ?: return false
                    underlyingNetworks?.ipv6Net?.forEach {
                        if (it.network == activeNetwork) {
                            Log.i(
                                LOG_TAG_VPN,
                                "Active network is reachable for IPv6: ${it.network.networkHandle}, ${activeNetwork.networkHandle}"
                            )
                            return true
                        }
                    }
                    return false
                }
            }
        }
    }

    private fun route4(): Boolean {
        return when (appConfig.getInternetProtocol()) {
            InternetProtocol.IPv4 -> {
                true
            }
            InternetProtocol.IPv6 -> {
                false
            }
            InternetProtocol.IPv46 -> {
                // when no underlying-networks are unknown, or if use-multiple-networks is enabled,
                // simply check whether there are ANY v4 networks available; otherwise, if the vpn
                // must only use the active-network (always the first network in allNet), then check
                // if active-network has v4 connectivity (that is, it must be present in ipv4Net).
                // check if isReachable is true, if not, don't need to add route for v4 (return
                // false)
                logd(
                    "underlyingNetworks: ${underlyingNetworks?.useActive}, ${underlyingNetworks?.ipv4Net?.size}"
                )
                if (underlyingNetworks?.useActive != true) {
                    if (underlyingNetworks?.ipv4Net?.size == 0) {
                        Log.i(LOG_TAG_VPN, "No IPv4 networks available")
                        return false
                    } else {
                        Log.i(LOG_TAG_VPN, "IPv4 networks available")
                        return true
                    }
                } else {
                    val activeNetwork = connectivityManager.activeNetwork ?: return false
                    underlyingNetworks?.ipv4Net?.forEach {
                        Log.i(LOG_TAG_VPN, "IPv4 network: ${it.network.networkHandle}")
                        if (it.network == activeNetwork) {
                            Log.i(LOG_TAG_VPN, "IPv4 network is reachable")
                            return true
                        }
                    }
                    return false
                }
            }
        }
    }

    private fun addRoute6(b: Builder): Builder {
        if (persistentState.privateIps) {
            Log.i(LOG_TAG_VPN, "addRoute6: privateIps is true, adding routes")
            // exclude LAN traffic, add only unicast routes
            // add only unicast routes
            // range 0000:0000:0000:0000:0000:0000:0000:0000-
            // 0000:0000:0000:0000:ffff:ffff:ffff:ffff
            b.addRoute("0000::", 64)
            b.addRoute("2000::", 3) // 2000:: - 3fff::
            b.addRoute("4000::", 3) // 4000:: - 5fff::
            b.addRoute("6000::", 3) // 6000:: - 7fff::
            b.addRoute("8000::", 3) // 8000:: - 9fff::
            b.addRoute("a000::", 3) // a000:: - bfff::
            b.addRoute("c000::", 3) // c000:: - dfff::
            b.addRoute("e000::", 4) // e000:: - efff::
            b.addRoute("f000::", 5) // f000:: - f7ff::
            b.addRoute("64:ff9b:1::", 48) // RFC8215/alg
            b.addRoute("64:ff9b::", 96) // RFC6052/dns64

            // b.addRoute("f800::", 6) // unicast routes
            // b.addRoute("fe00::", 9) // unicast routes
            // b.addRoute("ff00::", 8) // multicast routes
            // not considering 100::/64 and other reserved ranges
        } else {
            // no need to exclude LAN traffic, add default route which is ::/0
            Log.i(LOG_TAG_VPN, "addRoute6: privateIps is false, adding default route")
            b.addRoute(Constants.UNSPECIFIED_IP_IPV6, Constants.UNSPECIFIED_PORT)
        }

        return b
    }

    private fun addRoute4(b: Builder): Builder {
        if (persistentState.privateIps) {
            Log.i(LOG_TAG_VPN, "addRoute4: privateIps is true, adding routes")
            // https://developer.android.com/reference/android/net/VpnService.Builder.html#addRoute(java.lang.String,%20int)
            // Adds a route to the VPN's routing table. The VPN will forward all traffic to the
            // destination through the VPN interface. The destination is specified by address and
            // prefixLength.
            // ref: github.com/celzero/rethink-app/issues/26
            // github.com/M66B/NetGuard/blob/master/app/src/main/java/eu/faircode/netguard/ServiceSinkhole.java#L1276-L1353
            val ipsToExclude: MutableList<IPUtil.CIDR> = ArrayList()

            // loopback
            ipsToExclude.add(IPUtil.CIDR("127.0.0.0", 8))
            // lan: tools.ietf.org/html/rfc1918
            ipsToExclude.add(IPUtil.CIDR("10.0.0.0", 8))
            ipsToExclude.add(IPUtil.CIDR("172.16.0.0", 12))
            ipsToExclude.add(IPUtil.CIDR("192.168.0.0", 16))
            // link local
            ipsToExclude.add(IPUtil.CIDR("169.254.0.0", 16))
            // Broadcast
            ipsToExclude.add(IPUtil.CIDR("224.0.0.0", 3))

            ipsToExclude.sort()

            try {
                var start: InetAddress? = InetAddress.getByName(Constants.UNSPECIFIED_IP_IPV4)
                ipsToExclude.forEach { exclude ->
                    val include = IPUtil.toCIDR(start, IPUtil.minus1(exclude.start)!!)
                    include?.forEach {
                        try {
                            it.address?.let { it1 -> b.addRoute(it1, it.prefix) }
                        } catch (ex: Exception) {
                            Log.e(LOG_TAG_VPN, "exception while adding route: ${ex.message}", ex)
                        }
                    }
                    start = IPUtil.plus1(exclude.end)
                }
            } catch (ex: SocketException) {
                Log.e(LOG_TAG_VPN, "addRoute4: ${ex.message}", ex)
            } catch (ex: UnknownHostException) {
                Log.e(LOG_TAG_VPN, "addRoute4: ${ex.message}", ex)
            }
            b.addRoute("10.111.222.1", 32) // add route for the gateway
            b.addRoute("10.111.222.2", 32) // add route for the router
            b.addRoute("10.111.222.3", 32) // add route for the dns
        } else {
            Log.i(LOG_TAG_VPN, "addRoute4: privateIps is false, adding default route")
            // no need to exclude LAN traffic, add default route which is 0.0.0.0/0
            b.addRoute(Constants.UNSPECIFIED_IP_IPV4, Constants.UNSPECIFIED_PORT)
        }

        return b
    }

    private fun addAddress4(b: Builder): Builder {
        b.addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
        return b
    }

    private fun addAddress6(b: Builder): Builder {
        b.addAddress(LanIp.GATEWAY.make(IPV6_TEMPLATE), IPV6_PREFIX_LENGTH)
        return b
    }

    private fun addDnsServer4(b: Builder): Builder {
        b.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
        return b
    }

    private fun addDnsServer6(b: Builder): Builder {
        b.addDnsServer(LanIp.DNS.make(IPV6_TEMPLATE))
        return b
    }

    private fun addDnsRoute4(b: Builder): Builder {
        b.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
        return b
    }

    // builder.addRoute() when the app is in DNS only mode
    private fun addDnsRoute6(b: Builder): Builder {
        b.addRoute(LanIp.DNS.make(IPV6_TEMPLATE), 128)
        return b
    }

    private fun getFakeDns(): String {
        val ipv4 = LanIp.DNS.make(IPV4_TEMPLATE, KnownPorts.DNS_PORT)
        val ipv6 = LanIp.DNS.make(IPV6_TEMPLATE, KnownPorts.DNS_PORT)
        // now fakedns will be only set during first time vpn is started, so set both ipv4 and ipv6
        // addresses, so that if the network changes doesn't affect
        return "$ipv4,$ipv6"
        /*return if (route4() && route6()) {
            "$ipv4,$ipv6"
        } else if (route6()) {
            ipv6
        } else {
            ipv4 // default
        }*/
    }

    private fun io(s: String, f: suspend () -> Unit) =
        vpnScope.launch(CoroutineName(s) + Dispatchers.IO) { f() }

    private fun ui(f: suspend () -> Unit) = vpnScope.launch(Dispatchers.Main) { f() }

    override fun onQuery(fqdn: String?, qtype: Long): dnsx.NsOpts = runBlocking {
        // queryType: see ResourceRecordTypes.kt
        logd("onQuery: rcvd query: $fqdn, qtype: $qtype")
        if (fqdn == null) {
            Log.e(LOG_TAG_VPN, "onQuery: fqdn is null")
            return@runBlocking prepareDnsxNsOpts(Dnsx.Preferred)
        }

        if (appConfig.getBraveMode().isDnsMode()) {
            val res = getTransportIdForDnsMode(fqdn)
            logd("onQuery (Dns):$fqdn, dnsx: $res")
            return@runBlocking res
        }

        if (appConfig.getBraveMode().isDnsFirewallMode()) {
            val res = getTransportIdForDnsFirewallMode(fqdn)
            logd("onQuery (Dns+Firewall):$fqdn, dnsx: $res")
            return@runBlocking res
        }

        val res = prepareDnsxNsOpts(Dnsx.Preferred)
        Log.e(
            LOG_TAG_VPN,
            "onQuery: unknown mode ${appConfig.getBraveMode()}, $fqdn, returning $res"
        )
        return@runBlocking res
    }

    // function to decide which transport id to return on Dns only mode
    private fun getTransportIdForDnsMode(fqdn: String): dnsx.NsOpts {
        // system dns is treated as special in GoTun2Socks, so return as Dnsx.System if enabled
        if (appConfig.isSystemDns()) {
            return prepareDnsxNsOpts(Dnsx.System)
        }
        // check for global domain rules
        when (DomainRulesManager.getDomainRule(fqdn, UID_EVERYBODY)) {
            DomainRulesManager.Status.TRUST ->
                return prepareDnsxNsOpts(Dnsx.BlockFree) // Dnsx.BlockFree
            DomainRulesManager.Status.BLOCK ->
                return prepareDnsxNsOpts(Dnsx.BlockAll) // Dnsx.BlockAll
            else -> {} // no-op, fall-through;
        }

        return prepareDnsxNsOpts(Dnsx.Preferred)
    }

    // function to decide which transport id to return on DnsFirewall mode
    private suspend fun getTransportIdForDnsFirewallMode(fqdn: String): dnsx.NsOpts {
        return if (FirewallManager.isAnyAppBypassesDns()) {
            // if any app is bypassed (dns + firewall) and if local blocklist enabled or remote dns
            // is rethink then return Alg so that the decision is made by in flow() function
            prepareDnsxNsOpts(Dnsx.Alg)
        } else if (DomainRulesManager.isDomainTrusted(fqdn)) {
            // return Alg so that the decision is made by in flow() function
            prepareDnsxNsOpts(Dnsx.Alg)
        } else if (
            DomainRulesManager.status(fqdn, UID_EVERYBODY) == DomainRulesManager.Status.BLOCK
        ) {
            // if the domain is blocked by global rule then return block all
            // app-wise trust is already checked above
            prepareDnsxNsOpts(Dnsx.BlockAll)
        } else {
            // if the domain is not trusted and no app is bypassed then return preferred or
            // CT+preferred so that if the domain is blocked by upstream then no need to do
            // any further processing
            prepareDnsxNsOpts(Dnsx.Preferred)
        }
    }

    private fun prepareDnsxNsOpts(userPreferredId: String): dnsx.NsOpts {
        val tid = transportIdForOnQuery(userPreferredId)
        val pid = proxyIdForOnQuery()
        val opts = dnsx.NsOpts()
        opts.ipcsv = "" // as of now, no suggested ips
        opts.tidcsv = tid
        opts.pid = pid
        return opts
    }

    private fun transportIdForOnQuery(userPreferredId: String): String {
        val tid =
            if (WireguardManager.oneWireGuardEnabled()) {
                val wg = WireguardManager.getOneWireGuardProxyId() ?: return userPreferredId
                val fullId = ProxyManager.ID_WG_BASE + wg
                if (hasProxy(fullId)) {
                    fullId
                } else {
                    userPreferredId
                }
            } else if (appConfig.isSystemDns()) {
                // system dns is treated as special in GoTun2Socks, so return as Dnsx.System if
                // enabled
                Dnsx.System
            } else {
                userPreferredId
            }
        // case when tid and userPreferredId is Alg, then return BlockFree + Preferred
        // case when tid is Alg and userPreferredId is not Alg, then return BlockFree + tid
        // tid can be System / dnsProxyId
        return if (userPreferredId == Dnsx.Alg) {
            val sb = StringBuilder()
            if (tid == Dnsx.Alg) {
                // Dnsx.BlockFree + "," + Dnsx.Preferred
                val bf = appendDnsCacheIfNeeded(Dnsx.BlockFree)
                val pref = appendDnsCacheIfNeeded(Dnsx.Preferred)
                sb.append(bf).append(",").append(pref)
                sb.toString()
            } else {
                val bf = appendDnsCacheIfNeeded(Dnsx.BlockFree)
                val pref = appendDnsCacheIfNeeded(tid)
                sb.append(bf).append(",").append(pref)
                sb.toString()
            }
        } else {
            appendDnsCacheIfNeeded(tid)
        }
    }

    private fun appendDnsCacheIfNeeded(id: String): String {
        return if (canUseDnsCacheOnTransportId(id)) {
            Dnsx.CT + id
        } else {
            id
        }
    }

    private fun hasProxy(id: String): Boolean {
        // only add proxy id for one wireguard, no need for catchall wireguard
        // catchall wireguard need not have all the apps added to it
        return vpnAdapter?.getProxyById(id) != null
    }

    private fun canUseDnsCacheOnTransportId(userPreferredId: String): Boolean {
        // if userPreferredId is Dnsx.BlockAll, Alg then don't need to append CT
        return persistentState.enableDnsCache && userPreferredId != Dnsx.BlockAll
    }

    private fun proxyIdForOnQuery(): String {
        return if (appConfig.isCustomSocks5Enabled()) {
            val id = ProxyManager.ID_S5_BASE
            if (hasProxy(id)) {
                id
            } else {
                Ipn.Base
            }
        } else if (appConfig.isCustomHttpProxyEnabled()) {
            val id = ProxyManager.ID_HTTP_BASE
            if (hasProxy(id)) {
                id
            } else {
                Ipn.Base
            }
        } else if (appConfig.isWireGuardEnabled()) {
            // need to check if the enabled wireguard is one-wireguard
            // only for one-wireguard, the dns queries are proxied
            if (WireguardManager.oneWireGuardEnabled()) {
                val id = WireguardManager.getOneWireGuardProxyId() ?: return Ipn.Base
                val fullId = ProxyManager.ID_WG_BASE + id
                if (hasProxy(fullId)) {
                    fullId
                } else {
                    Ipn.Base
                }
            } else if (WireguardManager.catchAllEnabled()) {
                // if the enabled wireguard is catchall-wireguard, then return wireguard id
                val id = WireguardManager.getCatchAllWireGuardProxyId() ?: return Ipn.Base
                val fullId = ProxyManager.ID_WG_BASE + id
                if (hasProxy(fullId)) {
                    fullId
                } else {
                    Ipn.Base
                }
            } else {
                // if the enabled wireguard is not one-wireguard, then return base
                Ipn.Base
            }
        } else {
            Ipn.Base
        }
    }

    override fun onResponse(summary: Summary?) {
        if (summary == null) {
            Log.i(LOG_TAG_VPN, "received null summary for dns")
            return
        }

        logd("onResponse: $summary")
        netLogTracker.processDnsLog(summary)
    }

    override fun onComplete(p0: ServerSummary?) {
        // no-op
    }

    override fun route(
        sid: String?,
        pid: String?,
        network: String?,
        sipport: String?,
        dipport: String?
    ): Tab {
        return Tab()
    }

    override fun onSocketClosed(s: SocketSummary?) {
        if (s == null) {
            Log.i(LOG_TAG_VPN, "received null summary for socket")
            return
        }
        val connectionSummary =
            ConnectionSummary(s.uid, s.pid, s.id, s.rx, s.tx, s.duration, s.rtt, s.msg)
        logd("onSocketClosed: $s, $connectionSummary")

        if (s.uid.isNullOrEmpty()) {
            Log.e(LOG_TAG_VPN, "onSocketClosed: missing uid, summary: $s")
            return
        }

        try {
            if (s.uid == Protect.UidSelf) {
                // update rethink summary
                val key = CidKey(connectionSummary.connId, rethinkUid)
                trackedCids.remove(key)
                netLogTracker.updateRethinkSummary(connectionSummary)
            } else {
                // other apps summary
                // convert the uid to app id
                val uid = FirewallManager.appId(s.uid.toInt())
                val key = CidKey(connectionSummary.connId, uid)
                trackedCids.remove(key)
                netLogTracker.updateIpSummary(connectionSummary)
            }
        } catch (e: NumberFormatException) {
            Log.e(LOG_TAG_VPN, "onSocketClosed: ${e.message}", e)
        }
    }

    override fun flow(
        protocol: Int,
        _uid: Long,
        src: String,
        dest: String,
        realIps: String?,
        d: String?,
        possibleDomains: String?,
        blocklists: String
    ): intra.Mark = runBlocking {
        // runBlocking blocks the current thread until all coroutines within it are complete
        // runBlocking block to ensure that we can call a suspending function from a non-suspending
        // context and obtain the result.
        logd("flow: $_uid, $src, $dest, $realIps, $d, $blocklists")
        handleVpnLockdownStateAsync()

        val first = HostName(src)
        val second = HostName(dest)

        val srcIp = if (first.asAddress() == null) "" else first.asAddress().toString()
        val srcPort = first.port ?: 0
        val dstIp = if (second.asAddress() == null) "" else second.asAddress().toString()
        val dstPort = second.port ?: 0

        val ips = realIps?.split(",") ?: emptyList()
        // use realIps; as of now, netstack uses the first ip
        // TODO: apply firewall rules on all real ips
        val realDestIp =
            if (ips.isEmpty() || ips.first().trim().isEmpty()) {
                dstIp
            } else {
                ips.first().trim()
            }
        var uid = getUid(_uid, protocol, srcIp, srcPort, dstIp, dstPort)
        uid = FirewallManager.appId(uid)
        val userId = FirewallManager.userId(uid)

        // generates a random 8-byte value, converts it to hexadecimal, and then
        // provides the hexadecimal value as a string for connId
        val connId = Utilities.getRandomString(8)

        // TODO: handle multiple domains, for now, use the first domain
        val domains = d?.split(",") ?: emptyList()

        // if `d` is blocked, then at least one of the real ips is unspecified
        val anyRealIpBlocked = !ips.none { isUnspecifiedIp(it.trim()) }
        val connTyp =
            if (isConnectionMetered(realDestIp)) {
                ConnectionTracker.ConnType.METERED
            } else {
                ConnectionTracker.ConnType.UNMETERED
            }

        val cm =
            createConnTrackerMetaData(
                uid,
                userId,
                srcIp,
                srcPort,
                realDestIp,
                dstPort,
                protocol,
                proxyDetails = "", // set later
                blocklists,
                domains.firstOrNull(),
                connId,
                connTyp
            )

        val trapVpnDns = isDns(dstPort) && isVpnDns(dstIp)

        val isRethink = uid == rethinkUid
        if (isRethink) {
            // case when uid is rethink, return Ipn.Base
            logd(
                "flow: Ipn.Exit for rethink, $uid, $packageName, $srcIp, $srcPort, $realDestIp, $dstPort, $possibleDomains"
            )
            if (domains.isEmpty()) {
                // possible domains only used for logging purposes, it may be available if
                // the domains are empty. So, use the possibleDomains only if domains is empty
                // no need to show the possible domains other than rethink
                cm.query = possibleDomains?.split(",")?.firstOrNull() ?: ""
            }

            // if trapVpnDns is true, then Ipn.Exit won't be able to route the request via the
            // underlying network as the IP only exists within the VPN tunnel. So, use Ipn.Base
            // and expect the network engine to re-route as appropriate.
            val proxy =
                if (trapVpnDns) {
                    Ipn.Base
                } else {
                    Ipn.Exit
                }
            // add to trackedCids, so that the connection can be removed from the list when the
            // connection is closed (onSocketClosed), use: ui to show the active connections
            val key = CidKey(cm.connId, uid)
            trackedCids.add(key)
            return@runBlocking persistAndConstructFlowResponse(cm, proxy, connId, uid, isRethink)
        }

        if (trapVpnDns) {
            logd("flow: dns-request, returning Ipn.Base, $uid")
            return@runBlocking persistAndConstructFlowResponse(null, Ipn.Base, connId, uid)
        }

        processFirewallRequest(cm, anyRealIpBlocked, blocklists)

        if (cm.isBlocked) {
            // return Ipn.Block, no need to check for other rules
            logd("flow: received rule: block, returning Ipn.Block, $connId, $uid")
            return@runBlocking persistAndConstructFlowResponse(cm, Ipn.Block, connId, uid)
        }

        // add to trackedCids, so that the connection can be removed from the list when the
        // connection is closed (onSocketClosed), use: ui to show the active connections
        val key = CidKey(cm.connId, uid)
        trackedCids.add(key)

        return@runBlocking determineProxyDetails(cm)
    }

    private suspend fun determineProxyDetails(
        connTracker: ConnTrackerMetaData,
        connId: String = connTracker.connId,
        uid: Int = connTracker.uid
    ): intra.Mark {
        // check for one-wireguard, if enabled, return wireguard proxy for all connections
        if (WireguardManager.oneWireGuardEnabled()) {
            logd("flow: one-wireguard is enabled init, $connId, $uid")
            val id =
                WireguardManager.getOneWireGuardProxyId()
                    ?: return persistAndConstructFlowResponse(connTracker, Ipn.Base, connId, uid)
            val proxyId = "${ProxyManager.ID_WG_BASE}${id}"
            logd(
                "flow: one-wireguard is enabled, returning $proxyId, ${WireguardManager.canRouteIp(id, connTracker.destIP)}, ${hasProxy(proxyId)}"
            )
            if (WireguardManager.canRouteIp(id, connTracker.destIP) && hasProxy(proxyId)) {
                logd("flow: one-wireguard is enabled, returning $proxyId, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, proxyId, connId, uid)
            } else {
                // in some configurations the allowed ips will not be 0.0.0.0/0, so the connection
                // will be dropped, in those cases, check if the wireguard is in lockdown mode,
                // if not then return base (connection will be forwarded to base proxy), if yes,
                // mark the connection as no route available and block the connection
                val cf =
                    WireguardManager.getConfigFilesById(id)
                        ?: return persistAndConstructFlowResponse(
                            connTracker,
                            Ipn.Base,
                            connId,
                            uid
                        )

                return if (cf.isLockdown) {
                    logd(
                        "flow: one-wireguard is enabled, but no route/proxy available, returning Ipn.Block, $connId, $uid"
                    )
                    connTracker.blockedByRule = FirewallRuleset.RULE13.id
                    connTracker.isBlocked = true
                    persistAndConstructFlowResponse(connTracker, Ipn.Block, connId, uid)
                } else {
                    logd(
                        "flow: one-wireguard is enabled, but no route/proxy available, returning Ipn.Base, $connId, $uid"
                    )
                    persistAndConstructFlowResponse(connTracker, Ipn.Base, connId, uid)
                }
            }
        }

        // before checking for other proxy, check for wireguard, as it has lockdown scenario
        // do not check for enabled for lockdown scenario
        val cf = WireguardManager.getConfigIdForApp(uid)
        val proxyId = "${ProxyManager.ID_WG_BASE}${cf?.id}"
        // if no config is assigned / enabled for this app, pass-through
        // add ID_WG_BASE to the id to get the proxyId
        if (cf == null || cf.id == WireguardManager.INVALID_CONF_ID) {
            // wireguard is not enabled for this app, pass-through
            logd("flow: wireguard is not enabled for this app, pass-through, $connId, $uid")
        } else if (!cf.isActive) {
            // app is included in the config, but the config is not active check for lockdown
            if (cf.isLockdown) {
                logd(
                    "flow: wireguard is not enabled for this app, but lockdown is enabled, returning $proxyId, $connId, $uid"
                )
                return persistAndConstructFlowResponse(connTracker, proxyId, connId, uid)
            } else {
                logd(
                    "flow: wireguard is enabled but app is not included, proceed for other checks, $connId, $uid"
                )
                // pass-through, no wireguard config is enabled for this app
            }
        } else {
            logd(
                "flow: wireguard is enabled and app is included, returning $proxyId, $connId, $uid"
            )
            return if (hasProxy(proxyId)) {
                persistAndConstructFlowResponse(connTracker, proxyId, connId, uid)
            } else {
                // in some configurations the allowed ips will not be
                persistAndConstructFlowResponse(connTracker, Ipn.Base, connId, uid)
            }
        }

        // no need to check for other proxies if the protocol is not TCP or UDP
        if (
            connTracker.protocol != Protocol.TCP.protocolType &&
                connTracker.protocol != Protocol.UDP.protocolType
        ) {
            logd("flow: protocol is not TCP or UDP, returning Ipn.Base, $connId, $uid")
            return persistAndConstructFlowResponse(connTracker, Ipn.Base, connId, uid)
        }

        // carry out this check after wireguard, because wireguard has catchAll and lockdown
        // if no proxy is enabled, return Ipn.Base
        if (!appConfig.isProxyEnabled()) {
            logd("flow: no proxy enabled, returning Ipn.Base, $connId, $uid")
            return persistAndConstructFlowResponse(connTracker, Ipn.Base, connId, uid)
        }

        // comment out tcp proxy for v055 release
        /*if (appConfig.isTcpProxyEnabled()) {
            val activeId = ProxyManager.getProxyIdForApp(uid)
            if (!activeId.contains(ProxyManager.ID_TCP_BASE)) {
                Log.e(LOG_TAG_VPN, "flow: tcp proxy is enabled but app is not included")
                // pass-through
            } else {
                val ip = connTracker?.destIP ?: ""
                val isCloudflareIp = TcpProxyHelper.isCloudflareIp(ip)
                logd(
                        "flow: tcp proxy enabled, checking for cloudflare: $ip, $isCloudflareIp"
                    )
                if (isCloudflareIp) {
                    val proxyId = "${Ipn.WG}${SEC_WARP_ID}"
                    logd(
                            "flow: tcp proxy enabled, but destination is cloudflare, returning $proxyId, $connId, $uid"
                        )
                    return persistAndConstructFlowResponse(connTracker, proxyId, connId, uid)
                }
                   logd(
                        "flow: tcp proxy enabled, returning ${ProxyManager.ID_TCP_BASE}, $connId, $uid"
                    )
                return persistAndConstructFlowResponse(
                    connTracker,
                    ProxyManager.ID_TCP_BASE,
                    connId,
                    uid
                )
            }
        }*/

        if (appConfig.isOrbotProxyEnabled() && hasProxy(ProxyManager.ID_ORBOT_BASE)) {
            val endpoint = appConfig.getConnectedOrbotProxy()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            if (endpoint?.proxyAppName == packageName) {
                logd(
                    "flow: orbot proxy is enabled and app is $packageName, returning ${Ipn.Exit}, $connId, $uid"
                )
                connTracker.isBlocked = false
                connTracker.blockedByRule = FirewallRuleset.RULE0.id
                return persistAndConstructFlowResponse(connTracker, Ipn.Exit, connId, uid)
            }

            val activeId = ProxyManager.getProxyIdForApp(uid)
            if (!activeId.contains(ProxyManager.ID_ORBOT_BASE)) {
                Log.e(LOG_TAG_VPN, "flow: orbot proxy is enabled but app is not included")
                // pass-through
            } else {
                logd(
                    "flow: received rule: orbot, returning ${ProxyManager.ID_ORBOT_BASE}, $connId, $uid"
                )
                return persistAndConstructFlowResponse(
                    connTracker,
                    ProxyManager.ID_ORBOT_BASE,
                    connId,
                    uid
                )
            }
        }

        // chose socks5 proxy over http proxy
        if (appConfig.isCustomSocks5Enabled() && hasProxy(ProxyManager.ID_S5_BASE)) {
            val endpoint = runBlocking { appConfig.getSocks5ProxyDetails() }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            logd("flow: socks5 proxy is enabled, $packageName, ${endpoint.proxyAppName}")
            if (endpoint.proxyAppName == packageName) {
                logd(
                    "flow: socks5 proxy is enabled and app is $packageName, returning ${Ipn.Exit}, $connId, $uid"
                )
                connTracker.isBlocked = false
                connTracker.blockedByRule = FirewallRuleset.RULE0.id
                return persistAndConstructFlowResponse(connTracker, Ipn.Exit, connId, uid)
            }

            logd("flow: rule: socks5, use ${ProxyManager.ID_S5_BASE}, $connId, $uid")
            return persistAndConstructFlowResponse(
                connTracker,
                ProxyManager.ID_S5_BASE,
                connId,
                uid
            )
        }

        if (appConfig.isCustomHttpProxyEnabled() && hasProxy(ProxyManager.ID_HTTP_BASE)) {
            val endpoint = runBlocking { appConfig.getHttpProxyDetails() }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            if (endpoint.proxyAppName == packageName) {
                logd(
                    "flow: http proxy is enabled and app is $packageName, returning ${Ipn.Exit}, $connId, $uid"
                )
                connTracker.isBlocked = false
                connTracker.blockedByRule = FirewallRuleset.RULE0.id
                return persistAndConstructFlowResponse(connTracker, Ipn.Exit, connId, uid)
            }

            logd("flow: rule: http, use ${ProxyManager.ID_HTTP_BASE}, $connId, $uid")
            return persistAndConstructFlowResponse(
                connTracker,
                ProxyManager.ID_HTTP_BASE,
                connId,
                uid
            )
        }

        logd("flow: no proxy enabled, returning Ipn.Base, $connId, $uid")
        return persistAndConstructFlowResponse(connTracker, Ipn.Base, connId, uid)
    }

    fun hasCid(connId: String, uid: Int): Boolean {
        // get app id from uid
        val uid0 = FirewallManager.appId(uid)
        val key = CidKey(connId, uid0)
        return trackedCids.contains(key)
    }

    fun removeWireGuardProxy(id: Int) {
        logd("remove wg from tunnel: $id")
        io("removeWg") { vpnAdapter?.removeWgProxy(id) }
    }

    fun addWireGuardProxy(id: String) {
        logd("add wg from tunnel: $id")
        io("addWg") { vpnAdapter?.addWgProxy(id) }
    }

    fun refreshProxies() {
        logd("refresh wg config")
        io("refreshWg") { vpnAdapter?.refreshProxies() }
    }

    fun updateWireGuardConfig() {
        logd("update wg config")
        val tunProxyMode = appConfig.getTunProxyMode()
        io("updateWg") { vpnAdapter?.setWireguardTunnelModeIfNeeded(tunProxyMode) }
    }

    suspend fun getDnsStatus(id: String): Long? {
        return vpnAdapter?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return vpnAdapter?.getRDNS(type)
    }

    private fun persistAndConstructFlowResponse(
        connTracker: ConnTrackerMetaData?,
        proxyId: String,
        connId: String,
        uid: Int,
        isRethink: Boolean = false
    ): intra.Mark {
        // persist the connTracker
        if (connTracker != null) {
            connTracker.proxyDetails = proxyId
            // assign the proxy details to the connTracker after the decision is made
            if (ProxyManager.isIpnProxy(proxyId) && !connTracker.isBlocked) {
                connTracker.blockedByRule = FirewallRuleset.RULE12.id
            }
            if (isRethink) {
                netLogTracker.writeRethinkLog(connTracker)
            } else {
                netLogTracker.writeIpLog(connTracker)
            }
            logd("flow: connTracker: $connTracker")
        }

        val mark = intra.Mark()
        mark.pid = proxyId
        mark.cid = connId
        // if rethink, then set uid as rethink, so that go process can handle it accordingly
        if (isRethink) {
            mark.uid = Protect.UidSelf
        } else {
            mark.uid = uid.toString()
        }
        return mark
    }

    private suspend fun processFirewallRequest(
        metadata: ConnTrackerMetaData,
        anyRealIpBlocked: Boolean = false,
        blocklists: String = ""
    ) {
        val rule = firewall(metadata, anyRealIpBlocked)

        metadata.blockedByRule = rule.id
        metadata.blocklists = blocklists

        val blocked = FirewallRuleset.ground(rule)
        metadata.isBlocked = blocked

        logd("firewall-rule $rule on conn $metadata")
        return
    }

    private fun createConnTrackerMetaData(
        uid: Int,
        usrId: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        protocol: Int,
        proxyDetails: String = "",
        blocklists: String = "",
        query: String? = "",
        connId: String,
        connType: ConnectionTracker.ConnType
    ): ConnTrackerMetaData {

        // Ref: ipaddress doc:
        // https://seancfoley.github.io/IPAddress/ipaddress.html#host-name-or-address-with-port-or-service-name
        logd(
            "createConnInfoObj: uid: $uid, srcIp: $srcIp, srcPort: $srcPort, dstIp: $dstIp, dstPort: $dstPort, protocol: $protocol, query: $query, connId: $connId"
        )

        // FIXME: replace currentTimeMillis with elapsed-time
        return ConnTrackerMetaData(
            uid,
            usrId,
            srcIp,
            srcPort,
            dstIp,
            dstPort,
            System.currentTimeMillis(),
            false, /*blocked?*/
            "", /*rule*/
            proxyDetails,
            blocklists,
            protocol,
            query,
            connId,
            connType.value
        )
    }

    fun getProxyStatusById(id: String): Long? {
        return if (vpnAdapter != null) {
            val status = vpnAdapter?.getProxyStatusById(id)
            status
        } else {
            Log.w(LOG_TAG_VPN, "error while fetching proxy status: vpnAdapter is null")
            null
        }
    }

    fun syncP50Latency(id: String) {
        io("syncP50Latency") { vpnAdapter?.syncP50Latency(id) }
    }
}
