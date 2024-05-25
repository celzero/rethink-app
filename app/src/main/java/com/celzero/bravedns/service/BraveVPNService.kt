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

import Logger
import Logger.LOG_TAG_VPN
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.app.KeyguardManager
import android.app.MissingForegroundServiceTypeException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock.elapsedRealtime
import android.system.OsConstants.AF_INET
import android.system.OsConstants.AF_INET6
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import backend.Backend
import backend.RDNS
import backend.Stats
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
import com.celzero.bravedns.util.Constants.Companion.PRIMARY_USER
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.IPUtil
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isAtleastU
import com.celzero.bravedns.util.Utilities.isMissingOrInvalidUid
import com.celzero.bravedns.util.Utilities.isNetworkSame
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.common.collect.Sets
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Bridge
import intra.SocketSummary
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import rnet.ServerSummary
import rnet.Tab

class BraveVPNService :
    VpnService(), ConnectionMonitor.NetworkListener, Bridge, OnSharedPreferenceChangeListener {

    private var connectionMonitor: ConnectionMonitor = ConnectionMonitor(this)
    private var vpnAdapter: GoVpnAdapter? = null
    private var isVpnStarted = false // always accessed on the main thread

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

        const val VPN_INTERFACE_MTU: Int = 1500
        const val MIN_MTU: Int = 1280

        // TODO: add routes as normal but do not send fd to netstack
        // repopulateTrackedNetworks also fails open see isAnyNwValidated
        const val FAIL_OPEN_ON_NO_NETWORK = true
    }

    // handshake expiry time for proxy connections
    private val wgHandshakeTimeout = TimeUnit.MINUTES.toMillis(3L)
    private val checkpointInterval = TimeUnit.MINUTES.toMillis(1L)

    private var isLockDownPrevious: Boolean = false
    private val vpnScope = MainScope()

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val persistentState by inject<PersistentState>()
    private val rdb by inject<RefreshDatabase>()
    private val netLogTracker by inject<NetLogTracker>()

    @Volatile
    private var isAccessibilityServiceFunctional: Boolean = false

    @Volatile
    var accessibilityHearbeatTimestamp: Long = INIT_TIME_MS
    private var settingUpOrbot: AtomicBoolean = AtomicBoolean(false)

    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager
    private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var connectivityManager: ConnectivityManager
    private var keyguardManager: KeyguardManager? = null

    private lateinit var appInfoObserver: Observer<Collection<AppInfo>>
    private lateinit var orbotStartStatusObserver: Observer<Boolean>
    private lateinit var dnscryptRelayObserver: Observer<PersistentState.DnsCryptRelayDetails>

    private var rethinkUid: Int = INVALID_UID

    // used to store the conn-ids that are allowed and active, to show in network logs
    // as active connections. removed when the connection is closed (onSummary)
    private var trackedCids = Collections.newSetFromMap(ConcurrentHashMap<CidKey, Boolean>())

    // store proxyids and their handshake times, refresh proxy when handshake time is expired
    private val wgHandShakeCheckpoints: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    // data class to store the connection summary
    data class CidKey(val cid: String, val uid: Int)

    private var excludedApps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // post underlying networks as live data
    @Volatile
    var underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null

    @Volatile
    var overlayNetworks: OverlayNetworks = OverlayNetworks()

    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    data class OverlayNetworks(
        val has4: Boolean = false,
        val has6: Boolean = false,
        val failOpen: Boolean = true,
        val mtu: Int = Int.MAX_VALUE
    )

    data class Networks(
        val underlyingNws: ConnectionMonitor.UnderlyingNetworks?,
        val overlayNws: OverlayNetworks
    )

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
        Logger.d(LOG_TAG_VPN, msg)
    }

    override fun bind4(who: String, addrPort: String, fid: Long) {
        return bindAny(who, addrPort, fid, underlyingNetworks?.ipv4Net ?: emptyList())
    }

    override fun bind6(who: String, addrPort: String, fid: Long) {
        return bindAny(who, addrPort, fid, underlyingNetworks?.ipv6Net ?: emptyList())
    }

    private fun bindAny(
        who: String,
        addrPort: String,
        fid: Long,
        nws: List<ConnectionMonitor.NetworkProperties>
    ) {
        val rinr = persistentState.routeRethinkInRethink
        val curnet = underlyingNetworks

        logd("bind: $who, $fid, rinr? $rinr")
        if (rinr && who != Backend.Exit) {
            // do not proceed if rethink within rethink is enabled and proxyId(who) is not exit
            return
        }

        this.protect(fid.toInt())

        var pfd: ParcelFileDescriptor? = null
        try {

            pfd = ParcelFileDescriptor.adoptFd(fid.toInt())

            // split the addrPort to get the IP address and convert it to InetAddress
            val dest = IpRulesManager.splitHostPort(addrPort)
            val destIp = IPAddressString(dest.first).address
            val destPort = dest.second.toIntOrNull()
            val destAddr = destIp.toInetAddress()

            // in case of zero, bind only for wg connections, wireguard tries to bind to
            // network with zero addresses
            if (
                (destIp.isZero && who.startsWith(ProxyManager.ID_WG_BASE)) ||
                destIp.isAnyLocal ||
                destIp.isLoopback
            ) {
                logd("bind: invalid destIp: $destIp, who: $who, $addrPort")
                return
            }

            // check if the destination port is DNS port, if so bind to the network where the dns
            // belongs to, else bind to the available network
            val net = if (KnownPorts.isDns(destPort)) curnet?.dnsServers?.get(destAddr) else null
            if (net != null) {
                bindToNw(net, pfd)
                logd("bind: dns: $who, $addrPort, $fid, ${net.networkHandle}")
                return
            }

            // who is not used, but kept for future use
            // binding to the underlying network is not working.
            // no need to bind if use active network is true
            if (curnet?.useActive == true) {
                logd("bind: use active network is true")
                return
            }

            nws.forEach {
                if (bindToNw(it.network, pfd)) {
                    logd("bind: $who, $addrPort, $fid, ${it.network.networkHandle}")
                    return
                }
            }
        } finally {
            pfd?.detachFd()
        }
        logd("bind: no network to bind, ${curnet?.dnsServers?.keys}, who: $who, $addrPort")
    }

    private fun bindToNw(net: Network, pfd: ParcelFileDescriptor): Boolean {
        return try {
            net.bindSocket(pfd.fileDescriptor)
            true
        } catch (e: IOException) {
            Logger.e(LOG_TAG_VPN, "err bindToNw, ${e.message}, $e")
            false
        }
    }

    override fun protect(who: String?, fd: Long) {
        val rinr = persistentState.routeRethinkInRethink
        logd("protect: $who, $fd, rinr? $rinr")
        if (who != Backend.Exit && rinr) {
            // when in rinr mode, only protect "Exit" as others must be looped back into the tunnel
            return
        }
        this.protect(fd.toInt())
    }

    private suspend fun getUid(
        _uid: Long,
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ): Int {
        return if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            ioAsync("getUidQ") { connTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort) }
                .await()
        } else {
            _uid.toInt() // uid must have been retrieved from procfs by the caller
        }
    }

    /** Checks if incoming connection is blocked by any user-set firewall rule */
    private suspend fun firewall(
        connInfo: ConnTrackerMetaData,
        anyRealIpBlocked: Boolean = false,
        isSplApp: Boolean
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
                logd("firewall: unknown app blocked, $uid")
                return FirewallRuleset.RULE5
            }

            // if the app is new (ie unknown), refresh the db
            if (appStatus.isUntracked()) {
                io("addNewApp") { rdb.addNewApp(uid) }
                if (newAppBlocked(uid)) {
                    logd("firewall: new app blocked, $uid")
                    return FirewallRuleset.RULE1B
                }
            }

            // check for app rules (unmetered, metered connections)
            val appRuleset = appBlocked(connInfo, connectionStatus)
            if (appRuleset != null) {
                logd("firewall: app blocked, $uid")
                return appRuleset
            }

            if (VpnController.isVpnLockdown() && isAppPaused()) {
                logd("firewall: lockdown, app paused, $uid")
                // allow when firewall is paused: as a placeholder RULE8(bypass app) is used
                return FirewallRuleset.RULE8
            }

            val perAppDomainTentativeRule = getDomainRule(connInfo.query, uid)
            val perAppIpTentativeRule = uidIpStatus(uid, connInfo.destIP, connInfo.destPort)

            when (perAppDomainTentativeRule) {
                DomainRulesManager.Status.BLOCK -> {
                    logd("firewall: domain blocked, $uid")
                    return FirewallRuleset.RULE2E
                }

                DomainRulesManager.Status.TRUST -> {
                    if (!perAppIpTentativeRule.isBlocked()) {
                        logd("firewall: domain trusted, $uid")
                        return FirewallRuleset.RULE2F
                    } else {
                        // fall-through, check ip rules
                    }
                }

                DomainRulesManager.Status.NONE -> {
                    // fall-through
                }
            }

            // IP rules
            when (perAppIpTentativeRule) {
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    logd("firewall: ip blocked, $uid")
                    return FirewallRuleset.RULE2
                }

                IpRulesManager.IpRuleStatus.TRUST -> {
                    logd("firewall: ip trusted, $uid")
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
                logd("firewall: bypass dns firewall, $uid")
                return FirewallRuleset.RULE1H
            }

            // isolate mode
            if (appStatus.isolate()) {
                logd("firewall: isolate mode, $uid")
                return FirewallRuleset.RULE1G
            }

            val globalDomainRule = getDomainRule(connInfo.query, UID_EVERYBODY)
            val globalIpRule = globalIpRule(connInfo.destIP, connInfo.destPort)

            // should firewall rules by-pass universal firewall rules (previously whitelist)
            if (appStatus.bypassUniversal()) {
                // bypass universal should block the domains that are blocked by dns (local/remote)
                // unless the domain is trusted by the user
                if (anyRealIpBlocked && globalDomainRule != DomainRulesManager.Status.TRUST) {
                    logd("firewall: bypass universal, dns blocked, $uid, ${connInfo.query}")
                    return FirewallRuleset.RULE2G
                }

                return if (dnsProxied(connInfo.destPort)) {
                    logd("firewall: bypass universal, dns proxied, $uid")
                    FirewallRuleset.RULE9
                } else {
                    logd("firewall: bypass universal, $uid")
                    FirewallRuleset.RULE8
                }
            }

            // check for global domain allow/block domains
            when (globalDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    if (!globalIpRule.isBlocked()) {
                        logd("firewall: global domain trusted, $uid, ${connInfo.query}")
                        return FirewallRuleset.RULE2I
                    } else {
                        // fall-through, check ip rules
                    }
                }

                DomainRulesManager.Status.BLOCK -> {
                    logd("firewall: global domain blocked, $uid, ${connInfo.query}")
                    return FirewallRuleset.RULE2H
                }

                else -> {
                    // fall through
                }
            }

            // should ip rules by-pass or block universal firewall rules
            when (globalIpRule) {
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    logd("firewall: global ip blocked, $uid, ${connInfo.destIP}")
                    return FirewallRuleset.RULE2D
                }

                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    logd("firewall: global ip bypass universal, $uid, ${connInfo.destIP}")
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
                logd("firewall: dns blocked, $uid, ${connInfo.query}")
                return FirewallRuleset.RULE2G
            } else {
                // no-op; pass-through
            }

            // apps which are used to forward dns proxy, socks5 or https proxy are handled as spl
            // no need to handle universal rules for these apps
            if (isSplApp) {
                logd("firewall: special app, $uid, ${connInfo.query}")
                // placeholder rule (RULE0) for special app rules
                return FirewallRuleset.RULE0
            }

            val isMetered = isConnectionMetered(connInfo.destIP)
            // block all metered connections (Universal firewall setting)
            if (persistentState.getBlockMeteredConnections() && isMetered) {
                logd("firewall: metered blocked, $uid")
                return FirewallRuleset.RULE1F
            }

            // block apps when universal lockdown is enabled
            if (universalLockdown()) {
                logd("firewall: universal lockdown, $uid")
                return FirewallRuleset.RULE11
            }

            if (httpBlocked(connInfo.destPort)) {
                logd("firewall: http blocked, $uid")
                return FirewallRuleset.RULE10
            }

            if (deviceLocked()) {
                logd("firewall: device locked, $uid")
                return FirewallRuleset.RULE3
            }

            if (udpBlocked(uid, connInfo.protocol, connInfo.destPort)) {
                logd("firewall: udp blocked, $uid")
                return FirewallRuleset.RULE6
            }

            if (blockBackgroundData(uid)) {
                logd("firewall: background data blocked, $uid")
                return FirewallRuleset.RULE4
            }

            // if all packets on port 53 needs to be trapped
            if (dnsProxied(connInfo.destPort)) {
                logd("firewall: dns proxied, $uid")
                return FirewallRuleset.RULE9
            }

            // if connInfo.query is empty, then it is not resolved by user set dns
            if (dnsBypassed(connInfo.query)) {
                logd("firewall: dns bypassed, $uid")
                return FirewallRuleset.RULE7
            }
        } catch (iex: Exception) {
            // TODO: show alerts to user on such exceptions, in a separate ui?
            Logger.e(LOG_TAG_VPN, "err blocking conn, block anyway", iex)
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

    private fun uidIpStatus(uid: Int, destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        return ipStatus(uid, destIp, destPort)
    }

    private fun is4in6FilterRequired(): Boolean {
        return persistentState.filterIpv4inIpv6
    }

    private fun globalIpRule(destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        return ipStatus(UID_EVERYBODY, destIp, destPort)
    }

    private fun ipStatus(uid: Int, destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        if (destIp.isEmpty() || isUnspecifiedIp(destIp)) {
            return IpRulesManager.IpRuleStatus.NONE
        }
        // is ip:port or ip:* blocked / trusted?
        val statusIpPort = IpRulesManager.hasRule(uid, destIp, destPort)
        if (statusIpPort != IpRulesManager.IpRuleStatus.NONE) {
            return statusIpPort // trusted or blocked or bypassed-universal
        }
        // is ipv4 addr as ipv6 blocked / trusted?
        if (is4in6FilterRequired()) {
            val addr = IPAddressString(destIp)
            val ip4in6 = IPUtil.ip4in6(addr.address) ?: return IpRulesManager.IpRuleStatus.NONE
            val ip4str = ip4in6.toNormalizedString()
            val statusIpPort4in6 = IpRulesManager.hasRule(uid, ip4str, destPort)
            if (statusIpPort4in6 != IpRulesManager.IpRuleStatus.NONE) {
                return statusIpPort // trusted or blocked or bypassed-universal
            }
        }
        return statusIpPort
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

    private fun isPrivateDns(port: Int): Boolean {
        return KnownPorts.isDoT(port)
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
        val curnet = underlyingNetworks
        // assume active network until underlying networks are set by ConnectionMonitor
        // do not use persistentState.useMultipleNetworks
        val useActive = curnet == null || curnet.useActive
        if (!useActive || VpnController.isVpnLockdown()) {
            return isIfaceMetered(dst)
        }
        return isActiveIfaceMetered()
    }

    private fun isIfaceMetered(dst: String): Boolean {
        val dest = IPAddressString(dst)
        if (dest.isEmpty) {
            Logger.e(LOG_TAG_VPN, "invalid destination IP: $dst")
            return isActiveIfaceMetered()
        }

        // TODO: check for all networks instead of just the first one
        val curnet = underlyingNetworks
        val cap =
            if (dest.isZero || dest.isIPv6) { // wildcard addrs(::80, ::443, etc.) are bound to ipv6
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv6Net?.isEmpty() == true) {
                    return isActiveIfaceMetered()
                }
                curnet?.ipv6Net?.firstOrNull()?.capabilities
            } else {
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv4Net?.isEmpty() == true) {
                    return isActiveIfaceMetered()
                }
                curnet?.ipv4Net?.firstOrNull()?.capabilities
            }

        // if there are no network to be bound given a destination IP, fallback to active network
        if (cap == null) {
            Logger.e(LOG_TAG_VPN, "no network to be bound for $dst, use active network")
            return isActiveIfaceMetered()
        }
        return !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isActiveIfaceMetered(): Boolean {
        val curnet = underlyingNetworks ?: return false // assume unmetered
        val now = elapsedRealtime()
        val ts = curnet.lastUpdated
        if (abs(now - ts) > Constants.ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            curnet.lastUpdated = now
            curnet.isActiveNetworkMetered = connectivityManager.isActiveNetworkMetered
        }
        return curnet.isActiveNetworkMetered
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
            Logger.w(LOG_TAG_VPN, "accessibility service not functional, disable bg-block")
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
        Logger.i(LOG_TAG_VPN, "app not in use failure, show notification")

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

    private fun canAllowBypass(): Boolean {
        return !VpnController.isVpnLockdown() &&
                persistentState.allowBypass &&
                !appConfig.isProxyEnabled()
    }

    private suspend fun newBuilder(): Builder {
        var builder = Builder()

        if (canAllowBypass()) {
            Logger.i(LOG_TAG_VPN, "allow apps to bypass vpn on-demand")
            builder = builder.allowBypass()
            // TODO: should allowFamily be set?
            // family must be either AF_INET (for IPv4) or AF_INET6 (for IPv6)
        }

        val curnet = underlyingNetworks
        if (curnet == null || curnet.useActive) {
            // underlying networks is set to null, which prompts Android to set it to whatever is
            // the current active network. Later, ConnectionMonitor#onVpnStarted, depending on user
            // chosen preferences, sets appropriate underlying network/s.
            builder.setUnderlyingNetworks(null)
        } else {
            // add ipv4 and ipv6 networks to the tunnel
            val ipv4 = curnet.ipv4Net.map { it.network }
            val ipv6 = curnet.ipv6Net.map { it.network }
            val allNetworks = ipv4.plus(ipv6)
            if (allNetworks.isNotEmpty()) {
                builder.setUnderlyingNetworks(allNetworks.toTypedArray())
            } else {
                if (FAIL_OPEN_ON_NO_NETWORK) {
                    builder.setUnderlyingNetworks(null)
                } else {
                    builder.setUnderlyingNetworks(emptyArray())
                }
            }
        }

        // Fix - Cloud Backups were failing thinking that the VPN connection is metered.
        // The below code will fix that.
        if (isAtleastQ()) {
            builder.setMetered(false)
        }

        // route rethink traffic in rethink based on the user selection
        if (!persistentState.routeRethinkInRethink) {
            Logger.i(LOG_TAG_VPN, "builder: exclude rethink app from builder")
            addDisallowedApplication(builder, this.packageName)
        } else {
            Logger.i(LOG_TAG_VPN, "builder: route rethink traffic in rethink")
            // no-op
        }

        if (isAppPaused()) { // exclude all non-firewalled apps and be done
            if (VpnController.isVpnLockdown()) {
                Logger.i(LOG_TAG_VPN, "paused but vpn is lockdown; cannot exclude apps")
                return builder
            }
            val nonFirewalledApps = FirewallManager.getNonFirewalledAppsPackageNames()
            val packages = nonFirewalledApps.map { it.packageName }
            Logger.i(LOG_TAG_VPN, "paused, exclude non-firewalled apps, size: ${packages.count()}")
            addDisallowedApplications(builder, packages)
            return builder
        }

        // re-hydrate exclude-apps incase it has changed in the interim
        excludedApps = FirewallManager.getExcludedApps()
        if (appConfig.determineFirewallMode().isFirewallSinkMode()) {
            addAllowedApplication(builder, excludedApps)
        } else {
            // ignore excluded-apps settings when vpn is lockdown because
            // those apps would lose all internet connectivity, otherwise
            if (!VpnController.isVpnLockdown()) {
                Logger.i(LOG_TAG_VPN, "builder, vpn is not lockdown, exclude-apps $excludedApps")
                addDisallowedApplications(builder, excludedApps)
            } else {
                Logger.w(LOG_TAG_VPN, "builder, vpn is lockdown, ignoring exclude-apps list")
            }
        }

        if (appConfig.isCustomSocks5Enabled()) {
            // For Socks5 if there is a app selected, add that app in excluded list
            val socks5ProxyEndpoint = appConfig.getConnectedSocks5Proxy()
            val appName =
                socks5ProxyEndpoint?.proxyAppName
                    ?: getString(R.string.settings_app_list_default_app)
            if (isExcludePossible(appName)) {
                Logger.i(LOG_TAG_VPN, "exclude app for socks5, pkg: $appName")
                addDisallowedApplication(builder, appName)
            } else {
                Logger.i(LOG_TAG_VPN, "socks5(exclude): app not set or exclude not possible")
            }
        }

        if (appConfig.isOrbotProxyEnabled() && isExcludePossible(getString(R.string.orbot))) {
            Logger.i(LOG_TAG_VPN, "exclude orbot app")
            addDisallowedApplication(builder, OrbotHelper.ORBOT_PACKAGE_NAME)
        }

        if (appConfig.isCustomHttpProxyEnabled()) {
            // For HTTP proxy if there is a app selected, add that app in excluded list
            val httpProxyEndpoint = appConfig.getConnectedHttpProxy()
            val appName =
                httpProxyEndpoint?.proxyAppName ?: getString(R.string.settings_app_list_default_app)
            if (isExcludePossible(appName)) {
                Logger.i(LOG_TAG_VPN, "exclude app for http proxy, pkg: $appName")
                addDisallowedApplication(builder, appName)
            } else {
                Logger.i(LOG_TAG_VPN, "http proxy(exclude): app not set or exclude not possible")
            }
        }

        if (appConfig.isDnsProxyActive()) {
            // For DNS proxy mode, if any app is set then exclude the application from the list
            val dnsProxyEndpoint = appConfig.getSelectedDnsProxyDetails()
            val appName =
                dnsProxyEndpoint?.proxyAppName ?: getString(R.string.settings_app_list_default_app)
            if (isExcludePossible(appName)) {
                Logger.i(LOG_TAG_VPN, "exclude app for dns proxy, pkg: $appName")
                addDisallowedApplication(builder, appName)
            } else {
                Logger.i(LOG_TAG_VPN, "dns proxy(exclude): app not set or exclude not possible")
            }
        }

        return builder
    }

    private fun isExcludePossible(appName: String?): Boolean {
        // user settings to exclude apps in proxy mode
        if (!persistentState.excludeAppsInProxy) {
            Logger.i(LOG_TAG_VPN, "exclude apps in proxy is disabled")
            return false
        }

        if (VpnController.isVpnLockdown()) {
            Logger.i(LOG_TAG_VPN, "vpn is lockdown, exclude apps not possible")
            return false
        }

        return appName?.equals(getString(R.string.settings_app_list_default_app)) == false
    }

    private fun addDisallowedApplication(builder: Builder, pkg: String) {
        try {
            Logger.d(LOG_TAG_VPN, "exclude app: $pkg")
            builder.addDisallowedApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_VPN, "skip adding disallowed app ($pkg)", e)
        }
    }

    private fun addDisallowedApplications(builder: Builder, packages: Collection<String>) {
        packages.forEach { addDisallowedApplication(builder, it) }
    }

    private fun addAllowedApplication(builder: Builder, packages: Set<String>) {
        packages.forEach {
            try {
                builder.addAllowedApplication(it)
            } catch (e: PackageManager.NameNotFoundException) {
                Logger.w(LOG_TAG_VPN, "skip adding allowed app ($it)", e)
            }
        }
    }

    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        VpnController.onVpnCreated(this)

        io("loggers") { netLogTracker.restart(vpnScope) }

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
        dnscryptRelayObserver = makeDnscryptRelayObserver()
        persistentState.dnsCryptRelays.observeForever(dnscryptRelayObserver)
        Logger.i(LOG_TAG_VPN, "observe pref, dnscrypt relay, app list changes")
    }

    private fun makeDnscryptRelayObserver(): Observer<PersistentState.DnsCryptRelayDetails> {
        return Observer<PersistentState.DnsCryptRelayDetails> { t ->
            io("dnscryptRelay") {
                if (t.added) {
                    vpnAdapter?.addDnscryptRelay(t.relay)
                } else {
                    vpnAdapter?.removeDnscryptRelay(t.relay)
                }
            }
        }
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

                Logger.i(LOG_TAG_VPN, "excluded-apps list changed, restart vpn")

                io("excludeApps") { restartVpnWithNewAppConfig(reason = "excludeApps") }
            } catch (e: Exception) { // NoSuchElementException, ConcurrentModification
                Logger.e(
                    LOG_TAG_VPN,
                    "error retrieving value from appInfos observer ${e.message}",
                    e
                )
            }
        }
    }

    private fun makeOrbotStartStatusObserver(): Observer<Boolean> {
        return Observer<Boolean> { settingUpOrbot.set(it) }
    }

    private fun updateNotificationBuilder(): Notification {
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
        val isProxyEnabled = appConfig.isProxyEnabled()

        var contentTitle: String =
            when (appConfig.getBraveMode()) {
                AppConfig.BraveMode.DNS -> resources.getString(R.string.dns_mode_notification_title)
                AppConfig.BraveMode.FIREWALL ->
                    resources.getString(R.string.firewall_mode_notification_title)

                AppConfig.BraveMode.DNS_FIREWALL ->
                    if (isProxyEnabled) {
                        resources.getString(R.string.hybrid_mode_with_proxy_notification_title)
                    } else {
                        resources.getString(R.string.hybrid_mode_notification_title)
                    }
            }

        if (isAppPaused()) {
            contentTitle = resources.getString(R.string.pause_mode_notification_title)
        }

        builder.setSmallIcon(R.drawable.ic_notification_icon).setContentIntent(pendingIntent)
        builder.color = ContextCompat.getColor(this, getAccentColor(persistentState.theme))

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
                Logger.i(LOG_TAG_VPN, "No notification action")
            }
        }

        // from docs, Starting in Android 13 (API level 33), users can dismiss the notification
        // associated with a foreground service by default. To do so, users perform a swipe gesture
        // on the notification. On previous versions of Android, the notification can't be dismissed
        // unless the foreground service is either stopped or removed from the foreground.
        // make it ongoing to prevent that. https://github.com/celzero/rethink-app/issues/1136
        if (persistentState.persistentNotification) {
            builder.setOngoing(true)
        } else {
            builder.setOngoing(false)
        }

        // Secret notifications are not shown on the lock screen.  No need for this app to show
        // there. Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification = builder.build()

        if (persistentState.persistentNotification) {
            notification.flags = Notification.FLAG_ONGOING_EVENT
        } else {
            notification.flags = Notification.FLAG_NO_CLEAR
        }
        return notification
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

    private fun isPrimaryUser(): Boolean {
        return FirewallManager.userId(rethinkUid) == PRIMARY_USER
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rethinkUid = getRethinkUid()
        val pid = Process.myPid()
        Logger.i(
            LOG_TAG_VPN,
            "onStartCommand, us: $rethinkUid / pid: $pid, primary? ${isPrimaryUser()}"
        )

        VpnController.onConnectionStateChanged(State.NEW)

        ui {
            // Initialize the value whenever the vpn is started.
            accessibilityHearbeatTimestamp = INIT_TIME_MS

            // startForeground should always be called within 5 secs of onStartCommand invocation
            // https://developer.android.com/guide/components/fg-service-types
            // to log the exception type, wrap the call in different methods based on the API level
            // TODO: can remove multiple startForegroundService calls if we decide to remove
            // multiple catch blocks for API 31 and above
            if (isAtleastU()) {
                var ok = startForegroundService(FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                if (!ok) {
                    Logger.i(LOG_TAG_VPN, "start service failed, retrying with connected device")
                    ok = startForegroundService(FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                }
                if (!ok) {
                    Logger.i(LOG_TAG_VPN, "start service failed, stopping service")
                    signalStopService(userInitiated = false) // notify and stop
                    return@ui
                }
            } else {
                val ok = startForegroundService()
                if (!ok) {
                    Logger.i(LOG_TAG_VPN, "start service failed ( > U ), stopping service")
                    signalStopService(userInitiated = false) // notify and stop
                    return@ui
                }
            }

            startOrbotAsyncIfNeeded()

            // this should always be set before ConnectionMonitor is init-d
            // see restartVpn and updateTun which expect this to be the case
            persistentState.setVpnEnabled(true)

            var isNewVpn = false

            if (!isVpnStarted) {
                Logger.i(LOG_TAG_VPN, "new vpn")
                isVpnStarted = true
                isNewVpn = true
                connectionMonitor.onVpnStart(this)
            }

            if (isVpnStarted) {
                underlyingNetworks = null
            }

            val mtu = mtu()
            val opts =
                appConfig.newTunnelOptions(
                    this,
                    getFakeDns(),
                    appConfig.getProtocolTranslationMode(),
                    mtu
                )

            Logger.i(LOG_TAG_VPN, "start-foreground with opts $opts (for new-vpn? $isNewVpn)")
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
                    rdb.refresh(RefreshDatabase.ACTION_REFRESH_AUTO) {
                        restartVpn(opts, Networks(null, overlayNetworks), why = "startVpn")
                        // call this *after* a new vpn is created #512
                        uiCtx("observers") { observeChanges() }
                    }
                }
            }
        }
        return Service.START_STICKY
    }

    @RequiresApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startForegroundService(serviceType: Int): Boolean {
        Logger.vv(LOG_TAG_VPN, "startForegroundService, api: ${VERSION.SDK_INT}")
        try {
            ServiceCompat.startForeground(
                this,
                SERVICE_ID,
                updateNotificationBuilder(),
                serviceType
            )
            return true
        } catch (e: ForegroundServiceStartNotAllowedException) { // API 31 and above
            Logger.e(LOG_TAG_VPN, "startForeground failed, start not allowed exception", e)
        } catch (e: InvalidForegroundServiceTypeException) { // API 34 and above
            Logger.e(LOG_TAG_VPN, "startForeground failed, invalid service type exception", e)
        } catch (e: MissingForegroundServiceTypeException) { // API 34 and above
            Logger.e(LOG_TAG_VPN, "startForeground failed, missing service type exception", e)
        } catch (e: SecurityException) { // API 34 and above
            Logger.e(LOG_TAG_VPN, "startForeground failed, security exception", e)
        } catch (e: IllegalArgumentException) { // API 34 and above
            Logger.e(LOG_TAG_VPN, "startForeground failed, illegal argument", e)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "startForeground failed", e)
        }
        return false
    }

    private fun startForegroundService(): Boolean {
        Logger.vv(LOG_TAG_VPN, "startForegroundService, api: ${VERSION.SDK_INT}")
        if (isAtleastS()) {
            try {
                startForeground(SERVICE_ID, updateNotificationBuilder())
                return true
            } catch (e: ForegroundServiceStartNotAllowedException) { // API 31 and above
                Logger.e(LOG_TAG_VPN, "startForeground failed, start not allowed exception", e)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "startForeground failed", e)
            }
        } else {
            try {
                startForeground(SERVICE_ID, updateNotificationBuilder())
                return true
            } catch (e: Exception) { // no exception expected for API < 31
                Logger.e(LOG_TAG_VPN, "startForeground failed", e)
            }
        }
        return false
    }

    private fun mtu(): Int {
        val overlayMtu = overlayNetworks.mtu
        val underlyingMtu = underlyingNetworks?.minMtu ?: VPN_INTERFACE_MTU
        val minMtu = min(overlayMtu, underlyingMtu)
        Logger.i(LOG_TAG_VPN, "mtu; proxy: $overlayMtu, underlying: $underlyingMtu, min: $minMtu")
        // min mtu should be at least MIN_MTU (1280)
        if (minMtu < MIN_MTU) {
            Logger.w(LOG_TAG_VPN, "mtu less than $MIN_MTU, using $MIN_MTU")
            return MIN_MTU
        }
        return minMtu
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

    private fun unobserveDnsRelay() {
        if (this::dnscryptRelayObserver.isInitialized) {
            persistentState.dnsCryptRelays.removeObserver(dnscryptRelayObserver)
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
        Logger.i(LOG_TAG_VPN, "update-tun with new pre-set tunnel options")
        if (!persistentState.getVpnEnabled()) {
            // when persistent-state "thinks" vpn is disabled, stop the service, especially when
            // we could be here via onStartCommand -> updateTun -> handleVpnAdapterChange while
            // conn-monitor and go-vpn-adapter exist, but persistent-state tracking vpn goes out
            // of sync
            Logger.e(LOG_TAG_VPN, "stop-vpn(updateTun), tracking vpn is out of sync")
            io("outOfSync") { signalStopService(userInitiated = false) }
            return
        }

        // should not be called in normal circumstances, but just in case...
        val ok = vpnAdapter?.updateTun(tunnelOptions)
        // TODO: like Intra, call VpnController#stop instead? see
        // VpnController#onStartComplete
        if (ok == false) {
            Logger.w(LOG_TAG_VPN, "Cannot handle vpn adapter changes, no tunnel")
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
                io("braveModeChange") {
                    // change in brave mode, requires restart of the vpn (to set routes in vpn),
                    // tunMode (to set the tun mode), and dnsAlg (to update the dns alg) in go
                    restartVpnWithNewAppConfig(reason = "braveMode")
                    setTunMode()
                    updateDnsAlg()
                }
                notificationManager.notify(SERVICE_ID, updateNotificationBuilder())
            }

            PersistentState.LOCAL_BLOCK_LIST -> {
                io("localBlocklistEnable") { setRDNS() }
            }

            PersistentState.LOCAL_BLOCK_LIST_UPDATE -> {
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
                            restartVpnWithNewAppConfig(reason = "dnsProxy")
                            addTransport()
                        }

                        AppConfig.DnsType.RETHINK_REMOTE -> {
                            addTransport()
                        }

                        AppConfig.DnsType.SYSTEM_DNS -> {
                            setNetworkAndDefaultDnsIfNeeded()
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

            PersistentState.PREVENT_DNS_LEAKS -> {
                io("preventDnsLeaks") { setTunMode() }
            }

            PersistentState.ALLOW_BYPASS -> {
                io("allowBypass") { restartVpnWithNewAppConfig(reason = "allowBypass") }
            }

            PersistentState.PROXY_TYPE -> {
                io("proxy") {
                    handleProxyChange()
                    // if any proxy is set, then disable builder.allowByPass as false
                    disableAllowBypassIfNeeded()
                }
                notificationManager.notify(SERVICE_ID, updateNotificationBuilder())
            }

            PersistentState.NETWORK -> {
                Logger.i(LOG_TAG_VPN, "network change, ${persistentState.useMultipleNetworks}")
                io("useAllNetworks") { notifyConnectionMonitor() }
            }

            PersistentState.NOTIFICATION_ACTION -> {
                notificationManager.notify(SERVICE_ID, updateNotificationBuilder())
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
                io("routeLanTraffic") { restartVpnWithNewAppConfig(reason = "routeLanTraffic") }
            }

            PersistentState.RETHINK_IN_RETHINK -> {
                // restart vpn to allow/disallow rethink traffic in rethink
                io("routeRethinkInRethink") {
                    restartVpnWithNewAppConfig(reason = "routeRethinkInRethink")
                }
            }

            PersistentState.CONNECTIVITY_CHECKS -> {
                Logger.i(
                    LOG_TAG_VPN,
                    "connectivity checks changed, ${persistentState.connectivityChecks}"
                )
                io("connectivityChecks") { notifyConnectionMonitor() }
            }

            PersistentState.NOTIFICATION_PERMISSION -> {
                if (persistentState.shouldRequestNotificationPermission) {
                    Logger.i(LOG_TAG_VPN, "notification permission allowed, show notification")
                    notificationManager.notify(SERVICE_ID, updateNotificationBuilder())
                } else {
                    // no-op
                }
            }

            PersistentState.EXCLUDE_APPS_IN_PROXY -> {
                // restart vpn to exclude apps if either proxy or dns proxy is enabled
                if (appConfig.isProxyEnabled() || appConfig.isDnsProxyActive()) {
                    io("excludeAppsInProxy") {
                        restartVpnWithNewAppConfig(reason = "excludeAppsInProxy")
                    }
                } else {
                    // no-op, no need to restart vpn as no proxy/dns proxy is enabled
                }
            }
        }
    }

    private suspend fun disableAllowBypassIfNeeded() {
        if (appConfig.isProxyEnabled() && persistentState.allowBypass) {
            Logger.i(LOG_TAG_VPN, "disabling allowBypass, as proxy is set.")
            // inform user about the change in allow bypass setting by showing a toast
            ui {
                val message =
                    getString(
                        R.string.toast_allow_bypass_disabled,
                        getString(R.string.settings_allow_bypass_heading)
                    )
                showToastUiCentered(this, message, Toast.LENGTH_LONG)
            }
            persistentState.allowBypass = false
        }
    }

    private suspend fun setRDNS() {
        logd("set brave dns mode, local/remote")
        vpnAdapter?.setRDNS()
    }

    fun closeConnectionsIfNeeded(uid: Int) { // can be invalid uid, in which case, no-op
        if (uid == INVALID_UID) return

        val uid0 = FirewallManager.appId(uid, isPrimaryUser())

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
        Logger.i(LOG_TAG_VPN, "handle ip proto changes")
        if (InternetProtocol.isAuto(persistentState.internetProtocolType)) {
            // initiates connectivity checks if Auto mode and calls onNetworkConnected
            // or onNetworkDisconnected. onNetworkConnected may call restartVpn and setRoute on
            // route changes as informed by the connection monitor
            notifyConnectionMonitor()
        }
        restartVpnWithNewAppConfig(reason = "handleIPProtoChanges")
        setRoute()
    }

    private suspend fun handleProxyChange() {
        val tunProxyMode = appConfig.getTunProxyMode()
        val proxy = AppConfig.ProxyProvider.getProxyProvider(appConfig.getProxyProvider())
        Logger.i(LOG_TAG_VPN, "handle proxy change, proxy: $proxy, mode: $tunProxyMode")
        when (proxy) {
            AppConfig.ProxyProvider.NONE -> {
                // no-op
            }

            AppConfig.ProxyProvider.TCP -> {
                vpnAdapter?.setTcpProxy()
            }

            AppConfig.ProxyProvider.WIREGUARD -> {
                // no need to set proxy for wireguard, as WireguardManager handles it
            }

            AppConfig.ProxyProvider.ORBOT -> {
                // update orbot config, its treated as SOCKS5 or HTTP proxy internally
                // orbot proxy requires app to be excluded from vpn, so restart vpn
                restartVpnWithNewAppConfig(reason = "orbotProxy")
                vpnAdapter?.setCustomProxy(tunProxyMode)
            }

            AppConfig.ProxyProvider.CUSTOM -> {
                // custom either means socks5 or http proxy
                // socks5 proxy requires app to be excluded from vpn, so restart vpn
                restartVpnWithNewAppConfig(reason = "customProxy")
                vpnAdapter?.setCustomProxy(tunProxyMode)
            }
        }
    }

    private fun spawnLocalBlocklistStampUpdate() {
        if (isPlayStoreFlavour()) return

        io("dnsStampUpdate") { vpnAdapter?.setRDNSStamp() }
    }

    private suspend fun notifyConnectionMonitor() {
        connectionMonitor.onUserPreferenceChanged()
    }

    private suspend fun updateDnsAlg() {
        vpnAdapter?.setDnsAlg()
    }

    fun signalStopService(userInitiated: Boolean = true) {
        if (!userInitiated) notifyUserOnVpnFailure()
        stopVpnAdapter()
        stopSelf()
        Logger.i(LOG_TAG_VPN, "stopped vpn adapter and vpn service")
    }

    private fun stopVpnAdapter() {
        io("stopVpn") {
            if (vpnAdapter == null) {
                Logger.i(LOG_TAG_VPN, "vpn adapter already stopped")
                return@io
            }
            vpnAdapter?.closeTun()
            vpnAdapter = null
            Logger.i(LOG_TAG_VPN, "stop vpn adapter")
        }
    }

    private suspend fun restartVpnWithNewAppConfig(
        underlyingNws: ConnectionMonitor.UnderlyingNetworks? = underlyingNetworks,
        overlayNws: OverlayNetworks = overlayNetworks,
        reason: String
    ) {
        logd("restart vpn with new app config")
        val nws = Networks(underlyingNws, overlayNws)
        restartVpn(
            appConfig.newTunnelOptions(
                this,
                getFakeDns(),
                appConfig.getProtocolTranslationMode(),
                mtu()
            ),
            nws,
            reason
        )
    }

    private suspend fun setPcapMode() {
        val pcapPath = appConfig.getPcapFilePath()
        Logger.i(LOG_TAG_VPN, "pcap mode enabled, path: $pcapPath")
        vpnAdapter?.setPcapMode(pcapPath)
    }

    private suspend fun setTunMode() {
        val tunnelOptions =
            appConfig.newTunnelOptions(
                this,
                getFakeDns(),
                appConfig.getProtocolTranslationMode(),
                mtu()
            )
        Logger.i(
            LOG_TAG_VPN,
            "set tun mode with dns: ${tunnelOptions.tunDnsMode}, firewall: ${tunnelOptions.tunFirewallMode}, proxy: ${tunnelOptions.tunProxyMode}, pt: ${tunnelOptions.ptMode}"
        )
        vpnAdapter?.setTunMode(tunnelOptions)
    }

    private suspend fun restartVpn(opts: AppConfig.TunnelOptions, networks: Networks, why: String) {
        if (!persistentState.getVpnEnabled()) {
            // when persistent-state "thinks" vpn is disabled, stop the service, especially when
            // we could be here via onStartCommand -> isNewVpn -> restartVpn while both,
            // vpn-service & conn-monitor exists & vpn-enabled state goes out of sync
            logAndToastIfNeeded(
                "$why, stop-vpn(restartVpn), tracking vpn is out of sync",
                Log.ERROR
            )
            io("outOfSyncRestart") { signalStopService(userInitiated = false) }
            return
        }

        // attempt seamless hand-off as described in VpnService.Builder.establish() docs
        val tunFd = establishVpn(networks)
        if (tunFd == null) {
            logAndToastIfNeeded("$why, cannot restart-vpn, no tun-fd", Log.ERROR)
            io("noTunRestart") { signalStopService(userInitiated = false) }
            return
        }

        val ok = makeOrUpdateVpnAdapter(tunFd, opts, vpnProtos) // vpnProtos set in establishVpn()
        if (!ok) {
            logAndToastIfNeeded("$why, cannot restart-vpn, no vpn-adapter", Log.ERROR)
            io("noTunnelRestart") { signalStopService(userInitiated = false) }
            return
        } else {
            logAndToastIfNeeded("$why, vpn restarted", Log.INFO)
        }

        notifyConnectionStateChangeIfNeeded()
        informVpnControllerForProtoChange(vpnProtos)
    }

    private suspend fun logAndToastIfNeeded(msg: String, logLevel: Int = Log.WARN) {
        when (logLevel) {
            Log.WARN -> Logger.w(LOG_TAG_VPN, msg)
            Log.ERROR -> Logger.e(LOG_TAG_VPN, msg)
            Log.INFO -> Logger.i(LOG_TAG_VPN, msg)
            else -> Logger.d(LOG_TAG_VPN, msg)
        }
        uiCtx("toast") { if (DEBUG) showToastUiCentered(this, msg, Toast.LENGTH_LONG) }
    }

    private fun notifyConnectionStateChangeIfNeeded() {
        // Case: Set state to working in case of Firewall mode
        if (appConfig.getBraveMode().isFirewallMode()) {
            VpnController.onConnectionStateChanged(State.WORKING)
        }
    }

    private fun informVpnControllerForProtoChange(protos: Pair<Boolean, Boolean>) {
        // update the controller, which will update the UI (home screen btm sheet)
        VpnController.updateProtocol(protos)
    }

    fun hasTunnel(): Boolean {
        return vpnAdapter?.hasTunnel() == true
    }

    suspend fun refreshResolvers() {
        Logger.i(LOG_TAG_VPN, "refresh resolvers")
        vpnAdapter?.refreshResolvers()
    }

    private suspend fun makeOrUpdateVpnAdapter(
        tunFd: ParcelFileDescriptor,
        opts: AppConfig.TunnelOptions,
        p: Pair<Boolean, Boolean>
    ): Boolean {
        val ok = true
        val noTun = false // should eventually call signalStopService(userInitiated=false)
        val protos = InternetProtocol.byProtos(p.first, p.second).value()
        try {
            if (vpnAdapter != null) {
                Logger.i(LOG_TAG_VPN, "vpn-adapter exists, use it")
                // in case, if vpn-adapter exists, update the existing vpn-adapter
                if (vpnAdapter?.updateLinkAndRoutes(tunFd, opts, protos) == false) {
                    Logger.e(LOG_TAG_VPN, "err update vpn-adapter")
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
            Logger.e(LOG_TAG_VPN, "err new vpn-adapter: ${e.message}", e)
            return noTun
        } finally {
            try { // close the tunFd as GoVpnAdapter has its own copy
                tunFd.close()
            } catch (ignored: IOException) {
                Logger.e(LOG_TAG_VPN, "err closing tunFd: ${ignored.message}", ignored)
            }
        }
    }

    // TODO: #294 - Figure out a way to show users that the device is offline instead of status as
    // failing.
    override fun onNetworkDisconnected(networks: ConnectionMonitor.UnderlyingNetworks) {
        underlyingNetworks = networks
        Logger.i(LOG_TAG_VPN, "onNetworkDisconnected: state: z, $networks")
        if (FAIL_OPEN_ON_NO_NETWORK) {
            setUnderlyingNetworks(null)
        } else {
            setUnderlyingNetworks(emptyArray())
            // setting empty array is not enough, do not add routes to the tunnel
            io("nwDisconnect") { restartVpnWithNewAppConfig(networks, reason = "nwDisconnect") }
        }
        setNetworkAndDefaultDnsIfNeeded()
        VpnController.onConnectionStateChanged(null)
    }

    override fun onNetworkRegistrationFailed() {
        Logger.i(LOG_TAG_VPN, "recd nw registration failed, stop vpn service with notification")
        signalStopService(userInitiated = false)
    }

    override fun onNetworkConnected(networks: ConnectionMonitor.UnderlyingNetworks) {
        val curnet = underlyingNetworks
        val out = interestingNetworkChanges(curnet, networks)
        val isRoutesChanged = hasRouteChangedInAutoMode(out)
        val isBoundNetworksChanged = out.netChanged
        val isMtuChanged = out.mtuChanged
        underlyingNetworks = networks
        Logger.i(LOG_TAG_VPN, "onNetworkConnected: changes: $out for new: $networks")

        // always reset the system dns server ip of the active network with the tunnel
        setNetworkAndDefaultDnsIfNeeded()

        if (networks.useActive) {
            setUnderlyingNetworks(null)
        } else if (networks.ipv4Net.isEmpty() && networks.ipv6Net.isEmpty()) {
            Logger.w(LOG_TAG_VPN, "network changed but empty ipv4/ipv6 networks w connectivity")
            if (FAIL_OPEN_ON_NO_NETWORK) {
                setUnderlyingNetworks(null)
            } else {
                setUnderlyingNetworks(emptyArray())
            }
        } else {
            // add ipv4/ipv6 networks to the tunnel
            val allNetworks =
                networks.ipv4Net.map { it.network } + networks.ipv6Net.map { it.network }
            setUnderlyingNetworks(allNetworks.toTypedArray())
        }
        // restart vpn if the routes or when mtu changes
        if (isMtuChanged || isRoutesChanged) {
            logd(
                "mtu? $isMtuChanged(o:${curnet?.minMtu}, n:${networks.minMtu}); routes? $isRoutesChanged, restart vpn"
            )
            io("nwConnect") {
                restartVpnWithNewAppConfig(
                    networks,
                    reason =
                    "mtu? $isMtuChanged(o:${curnet?.minMtu}, n:${networks.minMtu}); routes? $isRoutesChanged"
                )
            }
        }

        // no need to close the existing connections if the bound networks are changed
        // observations on close connections:
        // instagram video delays when the network changes, reconnects (5-10s), feeds take longer
        // play store downloads completely broke when the network changes
        // observations on not closing connections:
        // instagram video delays when the network changes, reconnects (5-10s or more), feeds normal
        // play store downloads continue when the network changes, resumes after reconnect (5-10s)
        // so, not closing connections is better for user experience
        /* if (isBoundNetworksChanged) {
            logd("bound networks changed, close connections")
            io("boundNetworksChanged") { vpnAdapter?.closeAllConnections() }
        } */

        // Workaround for WireGuard connection issues after network change
        // WireGuard may fail to connect to the server when the network changes.
        // refresh will do a configuration refresh in tunnel to ensure a successful
        // reconnection after detecting a network change event
        if (isBoundNetworksChanged && appConfig.isWireGuardEnabled()) {
            refreshProxies()
        }
    }

    private fun hasRouteChangedInAutoMode(out: NetworkChanges): Boolean {
        // no need to check for routes if the app is not set in auto mode
        if (!appConfig.getInternetProtocol().isIPv46()) {
            return false
        }
        return out.routesChanged
    }

    private fun setRoute() {
        logd("set route")
        // go / netstack is always routing dual-stack, regardless
        // io("setRoute") { vpnAdapter?.setRoute(createNewTunnelOptsObj()) }
    }

    data class NetworkChanges(
        val routesChanged: Boolean = true,
        val netChanged: Boolean = true,
        val mtuChanged: Boolean = true
    )

    private fun interestingNetworkChanges(
        old: ConnectionMonitor.UnderlyingNetworks? = underlyingNetworks,
        _new: ConnectionMonitor.UnderlyingNetworks? = null,
        aux: OverlayNetworks = overlayNetworks
    ): NetworkChanges {
        var new = _new
        // when old and new are null, no changes
        if (old == null && new == null) {
            return NetworkChanges(false, false, false)
        }
        // no old routes to compare with, return true
        if (old == null) return NetworkChanges()
        if (new == null) {
            // new is null, but old is not, then check for changes in aux networks
            new = old
        }

        val underlyingMtuChanged = old.minMtu != new.minMtu
        val overlayMtuChanged = overlayNetworks.mtu != aux.mtu
        Logger.d(
            LOG_TAG_VPN,
            "old: ${old.minMtu}, new: ${new.minMtu}, oldaux: ${overlayNetworks.mtu}  newaux: ${aux.mtu}"
        )
        // check if mtu has changed for both underlying and overlay networks
        val mtuChanged = underlyingMtuChanged || overlayMtuChanged

        // val auxHas4 = aux.has4 || aux.failOpen
        // val auxHas6 = aux.has6 || aux.failOpen
        val n = Networks(new, aux)
        val (tunHas4, tunHas6) = vpnProtos // current tunnel routes v4/v6?
        val (tunWants4, tunWants6) = determineRoutes(n)

        val ok4 = tunHas4 == tunWants4 // old & new agree on activ capable of routing ipv4 or not
        val ok6 = tunHas6 == tunWants6 // old & new agree on activ capable of routing ipv6 or not
        val routesChanged = !ok4 || !ok6

        if (new.useActive) {
            connectivityManager.activeNetwork?.let { activ ->
                // val tunWants4 = activHas4 && auxHas4
                // val tunWants6 = activHas6 && auxHas6
                val activHas4 = isNetworkSame(new.ipv4Net.firstOrNull()?.network, activ)
                val activHas6 = isNetworkSame(new.ipv6Net.firstOrNull()?.network, activ)
                val oldActivHas4 = isNetworkSame(old.ipv4Net.firstOrNull()?.network, activ)
                val oldActivHas6 = isNetworkSame(old.ipv6Net.firstOrNull()?.network, activ)
                val okActiv4 =
                    oldActivHas4 ==
                            activHas4 // routing for ipv4 is same in old and new FIRST network
                val okActiv6 =
                    oldActivHas6 ==
                            activHas6 // routing for ipv6 is same in old and new FIRST network
                val netChanged = !okActiv4 || !okActiv6
                // for active networks, changes in routes includes all possible network changes;
                return NetworkChanges(routesChanged, netChanged, mtuChanged)
            } // active network null, fallthrough to check for netChanged
        }
        // check if ipv6 or ipv4 routes are different in old and new networks
        // val oldHas6 = old.ipv6Net.isNotEmpty() || tunHas6
        // val oldHas4 = old.ipv4Net.isNotEmpty() || tunHas4
        // val newHas6 = new.ipv6Net.isNotEmpty()
        // val newHas4 = new.ipv4Net.isNotEmpty()
        // val tunWants4 = newHas4 && auxHas4
        // val tunWants6 = newHas6 && auxHas6
        // check if the first networks are different to urge rebinds where necessary (ex: WireGuard)
        val oldFirst6 = old.ipv6Net.firstOrNull()?.network
        val newFirst6 = new.ipv6Net.firstOrNull()?.network
        val oldFirst4 = old.ipv4Net.firstOrNull()?.network
        val newFirst4 = new.ipv4Net.firstOrNull()?.network
        val netChanged =
            !isNetworkSame(oldFirst6, newFirst6) || !isNetworkSame(oldFirst4, newFirst4)

        return NetworkChanges(routesChanged, netChanged, mtuChanged)
    }

    private fun setNetworkAndDefaultDnsIfNeeded() {
        val currNet = underlyingNetworks
        val useActive = currNet == null || currNet.useActive
        val dnsServers =
            if (
                persistentState.routeRethinkInRethink || // rinr case is same as multiple networks
                !useActive || // use dns from all networks
                VpnController.isVpnLockdown() // active nw is null when lockdown
            ) {
                val dl: MutableList<InetAddress> = mutableListOf()
                dl.addAll(currNet?.dnsServers?.keys?.toList() ?: emptyList())
                Logger.i(LOG_TAG_VPN, "dns servers ipv4,ipv6: $dl")
                dl
            } else {
                // get dns servers from the first network or active network
                val active = connectivityManager.activeNetwork
                val lp = connectivityManager.getLinkProperties(active)
                val dnsServers = lp?.dnsServers

                if (dnsServers.isNullOrEmpty()) {
                    // first network is considered to be active network
                    val ipv4 = currNet?.ipv4Net?.firstOrNull()
                    val ipv6 = currNet?.ipv6Net?.firstOrNull()
                    val dns4 = ipv4?.linkProperties?.dnsServers
                    val dns6 = ipv6?.linkProperties?.dnsServers
                    // if active network is not found in the list of networks, then use dns from
                    // first network
                    val dl = mutableListOf<InetAddress>()
                    // add all the dns servers from the first network, depending on the current
                    // route, netstack will make use of the dns servers
                    dns4?.let { dl.addAll(it) }
                    dns6?.let { dl.addAll(it) }
                    Logger.i(LOG_TAG_VPN, "dns servers for network: $dl")
                    dl
                } else {
                    Logger.i(LOG_TAG_VPN, "dns servers for network: $dnsServers")
                    dnsServers
                }
            }

        if (dnsServers.isNullOrEmpty()) {
            // TODO: send an alert/notification instead?
            Logger.w(LOG_TAG_VPN, "No system dns servers found")
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
        io("setSystemAndDefaultDns") {
            Logger.i(LOG_TAG_VPN, "Setting dns servers: $dnsServers")
            val dns = determineSystemDns(dnsServers)
            // set system dns whenever there is a change in network
            vpnAdapter?.setSystemDns(dns)
            // set default dns server for the tunnel if none is set
            if (isDefaultDnsNone()) {
                val dnsCsv = dns.joinToString(",")
                vpnAdapter?.addDefaultTransport(dnsCsv)
            }
        }
    }

    private fun determineSystemDns(dnsServers: List<InetAddress>?): List<String> {
        val list = dnsServers?.map { it.hostAddress ?: "" }?.filter { it != "" }
        if (list.isNullOrEmpty()) {
            // no dns servers found, return empty list
            return emptyList()
        }
        Logger.d(LOG_TAG_VPN, "System dns: $list")
        return list
    }

    private fun isDefaultDnsNone(): Boolean {
        // if none is set then the url will either be empty or will not be one of the default dns
        return persistentState.defaultDnsUrl.isEmpty() ||
                !Constants.DEFAULT_DNS_LIST.any { it.url == persistentState.defaultDnsUrl }
    }

    private fun handleVpnLockdownStateAsync() {
        if (!syncLockdownState()) return
        Logger.i(LOG_TAG_VPN, "vpn lockdown mode change, restarting")
        io("lockdownSync") { restartVpnWithNewAppConfig(reason = "lockdownSync") }
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
                // fixme: should the string need to be changed based on failure type?
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
            Logger.w(LOG_TAG_VPN, "Unregister receiver error: ${e.message}")
        }

        persistentState.setVpnEnabled(false)
        stopPauseTimer()
        // reset the underlying networks
        underlyingNetworks = null
        isVpnStarted = false // reset the vpn state

        unobserveOrbotStartStatus()
        unobserveAppInfos()
        unobserveDnsRelay()
        persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        // onVpnStart is also called from the main thread (ui)
        connectionMonitor.onVpnStop()
        VpnController.onVpnDestroyed()
        try {
            vpnScope.cancel("vpnDestroy")
        } catch (ignored: IllegalStateException) {
        }

        Logger.w(LOG_TAG_VPN, "Destroying VPN service")

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
        io("pauseOrResumed") { restartVpnWithNewAppConfig(reason = "pauseOrResumed") }
        ui { notificationManager.notify(SERVICE_ID, updateNotificationBuilder()) }
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

    // var to update the controller with the protocol set for the vpn
    private var vpnProtos: Pair<Boolean, Boolean> = Pair(false, false)

    private fun determineRoutes(n: Networks): Pair<Boolean, Boolean> {
        var has6 = route6(n)
        var has4 = route4(n)

        if (!has4 && !has6 && !n.overlayNws.failOpen) {
            // When overlay networks has v6 routes but active network has v4 routes
            // both has4 and has6 will be false and fail-open may open up BOTH routes
            // What's desirable is for the active network route to take precedence, that is,
            // to only add v4 route in case of a mismatch. Failing open will falsely make
            // apps think the underlying active network is dual-stack when it is not causing
            // all sorts of delays (due to happy eyeballs).
            val n2 = Networks(n.underlyingNws, /*fail-open overlay*/ OverlayNetworks())
            has4 = route4(n2)
            has6 = route6(n2)
        }
        if (!has4 && !has6) {
            // no route available for both v4 and v6, add all routes
            // connectivity manager is expected to retry when no route is available
            // see ConnectionMonitor#repopulateTrackedNetworks
            Logger.i(LOG_TAG_VPN, "No routes, fail-open? $FAIL_OPEN_ON_NO_NETWORK")
            has4 = FAIL_OPEN_ON_NO_NETWORK
            has6 = FAIL_OPEN_ON_NO_NETWORK
        } else {
            Logger.i(LOG_TAG_VPN, "Building vpn for v4? $has4, v6? $has6")
        }

        return Pair(has4, has6)
    }

    private suspend fun establishVpn(networks: Networks): ParcelFileDescriptor? {
        try {
            val mtu = mtu() // get mtu from the underlyingnetworks
            var builder: VpnService.Builder = newBuilder().setSession("Rethink").setMtu(mtu)

            val (has4, has6) = determineRoutes(networks)

            vpnProtos = Pair(has4, has6)

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
            } else {
                // when not routing all traffic (firewall inactive) allow v4/v6 families
                // to be routed based on the underlying network (bypassing the tunnel)
                Logger.i(LOG_TAG_VPN, "dns-only mode, allowFamily: v4: $has4, v6: $has6")
                if (has4) {
                    builder.allowFamily(AF_INET)
                }
                if (has6) {
                    builder.allowFamily(AF_INET6)
                }
            }
            return builder.establish()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, e.message ?: "err establishVpn", e)
            return null
        }
    }

    private fun route6(nws: Networks): Boolean {
        return when (appConfig.getInternetProtocol()) {
            InternetProtocol.IPv4 -> {
                false
            }

            InternetProtocol.IPv6 -> {
                true
            }

            InternetProtocol.IPv46 -> {
                // null overlayNetwork means no active wireguard network, default to true so
                // that the route is added based on the underlying network
                val overlayIpv6 = nws.overlayNws.has6 || nws.overlayNws.failOpen

                val underlay = nws.underlyingNws
                // when no underlying-networks are unknown, or if use-multiple-networks is enabled,
                // simply check whether there are ANY v6 networks available; otherwise, if the vpn
                // must only use the active-network (always the first network in allNet), then check
                // if active-network has v6 connectivity (that is, it must be present in ipv6Net).
                // check if isReachable is true, if not, don't need to add route for v6 (return
                // false)
                logd("r6: underlyingNetworks: ${underlay?.useActive}, ${underlay?.ipv6Net?.size}")
                if (underlay?.useActive != true) {
                    val underlayIpv6 = (underlay?.ipv6Net?.size ?: 0) > 0
                    return if (!underlayIpv6) {
                        Logger.i(LOG_TAG_VPN, "r6: No IPv6 networks available")
                        false
                    } else {
                        Logger.i(LOG_TAG_VPN, "r6: IPv6 available, overlay: $overlayIpv6")
                        // underlay network is available, check if overlay network is available
                        overlayIpv6
                    }
                } else {
                    val activeNetwork = connectivityManager.activeNetwork

                    if (activeNetwork == null) {
                        Logger.i(LOG_TAG_VPN, "r6: missing active network, use the first network")
                        return underlay.ipv6Net.isNotEmpty() && overlayIpv6
                    }
                    underlay.ipv6Net.forEach {
                        val underlayIpv6 = isNetworkSame(it.network, activeNetwork)
                        if (underlayIpv6) {
                            Logger.i(LOG_TAG_VPN, "r6: Active network ok: ov: $overlayIpv6")
                            // underlay network is available, check if overlay network is available
                            return overlayIpv6
                        }
                    }
                    Logger.i(LOG_TAG_VPN, "r6: active network not available")
                    return false
                }
            }
        }
    }

    private fun onOverlayNetworkChanged(nw: OverlayNetworks) {
        // compare the overlay network pair with the overlayNetworkIpStates to determine if the
        // overlay network is changed, if so, restart the vpn
        val interestingNet = interestingNetworkChanges(aux = nw)
        val isRoutesChanged = interestingNet.routesChanged
        val isMtuChanged = interestingNet.mtuChanged
        Logger.i(
            LOG_TAG_VPN,
            "overlay: routes changed? $isRoutesChanged, mtu changed? $isMtuChanged"
        )
        overlayNetworks = nw
        if (isRoutesChanged || isMtuChanged) {
            Logger.i(LOG_TAG_VPN, "overlay changed $overlayNetworks, restart vpn")
            // There may be cases where both overlay and underlay networks have the same routes.
            // In such scenarios, no restart is required. However, here the routeChange is
            // considered
            // only for overlay network changes. Therefore, the VPN needs to be restarted
            // to recalculate the decision of adding routes.
            io("overlayNwChanged") {
                restartVpnWithNewAppConfig(
                    overlayNws = overlayNetworks,
                    reason = "overlayNwChanged"
                )
            }
        } else {
            Logger.i(LOG_TAG_VPN, "overlay routes or mtu not changed, no restart needed")
        }
    }

    private fun route4(nws: Networks): Boolean {
        return when (appConfig.getInternetProtocol()) {
            InternetProtocol.IPv4 -> {
                true
            }

            InternetProtocol.IPv6 -> {
                false
            }

            InternetProtocol.IPv46 -> {
                // null overlayNetwork means no active wireguard network, default to true so
                // that the route is added based on the underlying network
                val overlayIpv4 = nws.overlayNws.has4 || nws.overlayNws.failOpen

                val underlay = nws.underlyingNws
                // when no underlying-networks are unknown, or if use-multiple-networks is enabled,
                // simply check whether there are ANY v4 networks available; otherwise, if the vpn
                // must only use the active-network (always the first network in allNet), then check
                // if active-network has v4 connectivity (that is, it must be present in ipv4Net).
                // check if isReachable is true, if not, don't need to add route for v4 (return
                // false)
                logd("r4: useActive? ${underlay?.useActive}, sz: ${underlay?.ipv4Net?.size}")
                if (underlay?.useActive != true) {
                    val underlayIpv4 = (underlay?.ipv4Net?.size ?: 0) > 0
                    if (!underlayIpv4) {
                        Logger.i(LOG_TAG_VPN, "r4: No IPv4 networks available")
                        return false
                    } else {
                        Logger.i(LOG_TAG_VPN, "r4: IPv4 networks available")
                        // underlay network is available, check if overlay network is available
                        return overlayIpv4
                    }
                } else {
                    val activeNetwork = connectivityManager.activeNetwork
                    if (activeNetwork == null) {
                        Logger.i(LOG_TAG_VPN, "r4: missing active network, use the first network")
                        return underlay.ipv4Net.isNotEmpty() && overlayIpv4
                    }

                    underlay.ipv4Net.forEach {
                        val underlayIpv4 = isNetworkSame(it.network, activeNetwork)
                        if (underlayIpv4) {
                            Logger.i(LOG_TAG_VPN, "r4: reachable, ov: $overlayIpv4")
                            // underlay network is available, check if overlay network is available
                            return overlayIpv4
                        }
                    }
                    return false
                }
            }
        }
    }

    private fun addRoute6(b: Builder): Builder {
        if (persistentState.privateIps) {
            Logger.i(LOG_TAG_VPN, "addRoute6: privateIps is true, adding routes")
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
            Logger.i(LOG_TAG_VPN, "addRoute6: privateIps is false, adding default route")
            b.addRoute(Constants.UNSPECIFIED_IP_IPV6, Constants.UNSPECIFIED_PORT)
        }

        return b
    }

    private fun addRoute4(b: Builder): Builder {
        if (persistentState.privateIps) {
            Logger.i(LOG_TAG_VPN, "addRoute4: privateIps is true, adding routes")
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
                            Logger.e(LOG_TAG_VPN, "exception while adding route: ${ex.message}", ex)
                        }
                    }
                    start = IPUtil.plus1(exclude.end)
                }
            } catch (ex: SocketException) {
                Logger.e(LOG_TAG_VPN, "addRoute4: ${ex.message}", ex)
            } catch (ex: UnknownHostException) {
                Logger.e(LOG_TAG_VPN, "addRoute4: ${ex.message}", ex)
            }
            b.addRoute(LanIp.GATEWAY.make(IPV4_TEMPLATE), 32)
            b.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
            b.addRoute(LanIp.ROUTER.make(IPV4_TEMPLATE), 32)
        } else {
            Logger.i(LOG_TAG_VPN, "addRoute4: privateIps is false, adding default route")
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

    private suspend fun uiCtx(s: String, f: suspend () -> Unit) {
        withContext(CoroutineName(s) + Dispatchers.Main) { f() }
    }

    private suspend fun <T> ioAsync(s: String, f: suspend () -> T): Deferred<T> {
        return vpnScope.async(CoroutineName(s) + Dispatchers.IO) { f() }
    }

    override fun onQuery(fqdn: String?, qtype: Long): backend.DNSOpts = runBlocking {
        // queryType: see ResourceRecordTypes.kt
        logd("onQuery: rcvd query: $fqdn, qtype: $qtype")
        if (fqdn == null) {
            Logger.e(LOG_TAG_VPN, "onQuery: fqdn is null")
            // return block all, as it is not expected to reach here
            return@runBlocking makeNsOpts(Backend.BlockAll)
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

        val res = makeNsOpts(Backend.Preferred) // should not reach here
        Logger.e(
            LOG_TAG_VPN,
            "onQuery: unknown mode ${appConfig.getBraveMode()}, $fqdn, returning $res"
        )
        return@runBlocking res
    }

    // function to decide which transport id to return on Dns only mode
    private suspend fun getTransportIdForDnsMode(fqdn: String): backend.DNSOpts {
        val tid = determineDnsTransportId()

        // check for global domain rules
        when (DomainRulesManager.getDomainRule(fqdn, UID_EVERYBODY)) {
            DomainRulesManager.Status.TRUST -> return makeNsOpts(Backend.BlockFree, true)
            DomainRulesManager.Status.BLOCK -> return makeNsOpts(Backend.BlockAll, false)
            else -> {} // no-op, fall-through;
        }

        return makeNsOpts(tid)
    }

    // function to decide which transport id to return on DnsFirewall mode
    private suspend fun getTransportIdForDnsFirewallMode(fqdn: String): backend.DNSOpts {
        val tid = determineDnsTransportId()
        val forceBypassLocalBlocklists = isAppPaused() && VpnController.isVpnLockdown()

        return if (forceBypassLocalBlocklists) {
            // if the app is paused and vpn is in lockdown mode, then bypass the local blocklists
            makeNsOpts(tid, true)
        } else if (FirewallManager.isAnyAppBypassesDns()) {
            // if any app is bypassed (dns + firewall) set isBlockFree as true, so that the
            // domain is resolved amd the decision is made by in flow()
            makeNsOpts(transportIdsAlg(tid), true)
        } else if (DomainRulesManager.isDomainTrusted(fqdn)) {
            // set isBlockFree as true so that the decision is made by in flow() function
            makeNsOpts(transportIdsAlg(tid), true)
        } else if (
            DomainRulesManager.status(fqdn, UID_EVERYBODY) == DomainRulesManager.Status.BLOCK
        ) {
            // if the domain is blocked by global rule then set as block all (overriding the tid)
            // app-wise trust is already checked above
            makeNsOpts(Backend.BlockAll)
        } else {
            // no global rule, no app-wise trust, return the tid as it is
            makeNsOpts(tid)
        }
    }

    private fun determineDnsTransportId(): String {
        val oneWgId = WireguardManager.getOneWireGuardProxyId()
        return if (oneWgId != null) {
            ProxyManager.ID_WG_BASE + oneWgId
        } else if (appConfig.isSystemDns() || (isAppPaused() && VpnController.isVpnLockdown())) {
            // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
            // as if the apps are excluded from vpn
            Backend.System
        } else {
            Backend.Preferred
        }
    }

    private suspend fun makeNsOpts(
        tid: String,
        bypassLocalBlocklists: Boolean = false
    ): backend.DNSOpts {
        val opts = backend.DNSOpts()
        opts.ipcsv = "" // as of now, no suggested ips
        opts.tidcsv = appendDnsCacheIfNeeded(tid)
        opts.pid = proxyIdForOnQuery()
        opts.noblock = bypassLocalBlocklists
        return opts
    }

    private fun transportIdsAlg(preferredId: String): String {
        // case when userPreferredId is Alg, then return BlockFree + tid
        // tid can be System / ProxyId / Preferred
        return if (isRethinkDnsEnabled()) {
            val sb = StringBuilder()
            val tr1 = appendDnsCacheIfNeeded(Backend.BlockFree)
            val tr2 = appendDnsCacheIfNeeded(preferredId) // ideally, it should be Preferred
            sb.append(tr1).append(",").append(tr2)
            sb.toString()
        } else {
            appendDnsCacheIfNeeded(preferredId)
        }
    }

    private fun isRethinkDnsEnabled(): Boolean {
        return appConfig.isRethinkDnsConnected() && !WireguardManager.oneWireGuardEnabled()
    }

    private fun appendDnsCacheIfNeeded(id: String): String {
        return if (canUseDnsCacheOnTransportId(id) && !id.startsWith(Backend.CT)) {
            Backend.CT + id
        } else {
            id
        }
    }

    private fun canUseDnsCacheOnTransportId(userPreferredId: String): Boolean {
        // if userPreferredId is Dnsx.BlockAll, Alg then don't need to append CT
        return persistentState.enableDnsCache && userPreferredId != Backend.BlockAll
    }

    private suspend fun proxyIdForOnQuery(): String {
        // proxies are used only in dns-firewall mode
        if (!appConfig.getBraveMode().isDnsFirewallMode()) {
            return Backend.Base
        }

        // user setting to disable proxy dns
        if (!persistentState.proxyDns) {
            return Backend.Base
        }

        if (appConfig.isDnsProxyActive()) {
            val endpoint = appConfig.getSelectedDnsProxyDetails()
            if (!endpoint?.proxyAppName.isNullOrEmpty()) {
                return Backend.Base
            }
        }

        return if (appConfig.isCustomSocks5Enabled()) {
            ProxyManager.ID_S5_BASE
        } else if (appConfig.isCustomHttpProxyEnabled()) {
            ProxyManager.ID_HTTP_BASE
        } else if (appConfig.isWireGuardEnabled()) {
            // need to check if the enabled wireguard is one-wireguard
            // only for one-wireguard, the dns queries are proxied
            if (WireguardManager.oneWireGuardEnabled()) {
                val id = WireguardManager.getOneWireGuardProxyId() ?: return Backend.Base
                ProxyManager.ID_WG_BASE + id
            } else if (WireguardManager.catchAllEnabled()) {
                // if the enabled wireguard is catchall-wireguard, then return wireguard id
                val id = WireguardManager.getCatchAllWireGuardProxyId() ?: return Backend.Base
                ProxyManager.ID_WG_BASE + id
            } else {
                // if the enabled wireguard is not one-wireguard, then return base
                Backend.Base
            }
        } else if (WireguardManager.catchAllEnabled()) { // check even if wireguard is not enabled
            // if the enabled wireguard is catchall-wireguard, then return wireguard id
            val id = WireguardManager.getCatchAllWireGuardProxyId() ?: return Backend.Base
            // in this case, no need to check if the proxy is available
            ProxyManager.ID_WG_BASE + id
        } else {
            Backend.Base
        }
    }

    override fun onResponse(summary: backend.DNSSummary?) {
        if (summary == null) {
            Logger.i(LOG_TAG_VPN, "received null summary for dns")
            return
        }

        logd("onResponse: $summary")
        netLogTracker.processDnsLog(summary)
    }

    override fun onProxiesStopped() {
        // clear the proxy handshake times
        logd("onProxiesStopped; clear the handshake times")
        wgHandShakeCheckpoints.clear()
    }

    override fun onProxyAdded(id: String) {
        if (!id.contains(ProxyManager.ID_WG_BASE)) {
            // only wireguard proxies are considered for overlay network
            return
        }
        wgHandShakeCheckpoints[id] = elapsedRealtime()
        // new proxy added, refresh overlay network pair
        val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
        logd("onProxyAdded for proxy $id: $nw")
        onOverlayNetworkChanged(nw ?: OverlayNetworks())
    }

    override fun onProxyRemoved(id: String) {
        if (!id.contains(ProxyManager.ID_WG_BASE)) {
            // only wireguard proxies are considered for overlay network
            return
        }
        wgHandShakeCheckpoints.remove(id)
        // proxy removed, refresh overlay network pair
        val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
        logd("onProxyRemoved for proxy $id: $nw")
        onOverlayNetworkChanged(nw ?: OverlayNetworks())
    }

    override fun onDNSAdded(id: String) {
        // no-op
        Logger.v(LOG_TAG_VPN, "onDNSAdded: $id")
    }

    override fun onDNSRemoved(id: String) {
        // no-op
        Logger.v(LOG_TAG_VPN, "onDNSRemoved: $id")
    }

    override fun onDNSStopped() {
        // no-op
        Logger.v(LOG_TAG_VPN, "onDNSStopped")
    }

    override fun onComplete(p0: ServerSummary) {
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
            Logger.i(LOG_TAG_VPN, "received null summary for socket")
            return
        }

        // set the flag as null, will calculate the flag based on the target
        val connectionSummary =
            ConnectionSummary(
                s.uid,
                s.pid,
                s.id,
                s.rx,
                s.tx,
                s.duration,
                s.rtt,
                s.msg,
                s.target,
                null
            )
        logd("onSocketClosed: $s")

        if (s.uid.isNullOrEmpty()) {
            Logger.e(LOG_TAG_VPN, "onSocketClosed: missing uid, summary: $s")
            return
        }

        try {
            if (s.uid == Backend.UidSelf) {
                // update rethink summary
                val key = CidKey(connectionSummary.connId, rethinkUid)
                trackedCids.remove(key)
                netLogTracker.updateRethinkSummary(connectionSummary)
            } else {
                // other apps summary
                // convert the uid to app id
                val uid = FirewallManager.appId(s.uid.toInt(), isPrimaryUser())
                val key = CidKey(connectionSummary.connId, uid)
                trackedCids.remove(key)
                netLogTracker.updateIpSummary(connectionSummary)
            }
        } catch (e: NumberFormatException) {
            Logger.e(LOG_TAG_VPN, "onSocketClosed: ${e.message}", e)
        }
    }

    override fun flow(
        protocol: Int,
        _uid: Long,
        dup: Boolean,
        src: String,
        dest: String,
        realIps: String,
        d: String,
        possibleDomains: String,
        blocklists: String
    ): intra.Mark = runBlocking {
        // runBlocking blocks the current thread until all coroutines within it are complete
        // an call a suspending function from a non-suspending context and obtain the result.
        logd("flow: $_uid, $dup, $src, $dest, $realIps, $d, $blocklists")
        handleVpnLockdownStateAsync()

        val first = HostName(src)
        val second = HostName(dest)

        val srcIp = if (first.asAddress() == null) "" else first.asAddress().toString()
        val srcPort = first.port ?: 0
        val dstIp = if (second.asAddress() == null) "" else second.asAddress().toString()
        val dstPort = second.port ?: 0

        val ips = realIps.split(",")
        val fip = ips.firstOrNull()?.trim()
        // use realIps; as of now, netstack uses the first ip
        // TODO: apply firewall rules on all real ips
        val realDestIp =
            if (fip.isNullOrEmpty()) {
                dstIp
            } else {
                fip
            }
        var uid = getUid(_uid, protocol, srcIp, srcPort, dstIp, dstPort)
        uid = FirewallManager.appId(uid, isPrimaryUser())
        val userId = FirewallManager.userId(uid)

        // generates a random 8-byte value, converts it to hexadecimal, and then
        // provides the hexadecimal value as a string for connId
        val connId = Utilities.getRandomString(8)

        // TODO: handle multiple domains, for now, use the first domain
        val domains = d.split(",")

        // if `d` is blocked, then at least one of the real ips is unspecified
        val anyRealIpBlocked = !ips.none { isUnspecifiedIp(it.trim()) }
        val connType =
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
                connType
            )

        val trapVpnDns = isDns(dstPort) && isVpnDns(dstIp)
        val trapVpnPrivateDns = isVpnDns(dstIp) && isPrivateDns(dstPort)
        // app is considered as spl when it is selected to forward dns proxy, socks5 or http proxy
        val isSplApp = isSpecialApp(uid)

        // always block, since the vpn tunnel doesn't serve dns-over-tls
        if (trapVpnPrivateDns) {
            logd("flow: dns-over-tls, returning Ipn.Block, $uid")
            cm.isBlocked = true
            cm.blockedByRule = FirewallRuleset.RULE1C.id
            return@runBlocking persistAndConstructFlowResponse(cm, Backend.Block, connId, uid)
        }

        val isRethink = uid == rethinkUid
        if (isRethink) {
            // case when uid is rethink, return Ipn.Base
            logd(
                "flow: Ipn.Exit for rethink, $uid, $dup, $packageName, $srcIp, $srcPort, $realDestIp, $dstPort, $possibleDomains"
            )
            if (cm.query.isNullOrEmpty()) {
                // possible domains only used for logging purposes, it may be available if
                // the domains are empty. So, use the possibleDomains only if domains is empty
                // no need to show the possible domains other than rethink
                cm.query = possibleDomains.split(",").firstOrNull() ?: ""
            }

            // TODO: should handle the LanIp.GATEWAY, LanIp.ROUTER addresses as well
            // now only handling the LanIp.DNS address, handle it once go implementation is ready

            // if trapVpnDns is true, then Ipn.Exit won't be able to route the request via the
            // underlying network as the IP only exists within the VPN tunnel. So, use Ipn.Base
            // and expect the network engine to re-route as appropriate.
            val proxy =
                if (trapVpnDns) {
                    Backend.Base
                } else {
                    Backend.Exit
                }
            // add to trackedCids, so that the connection can be removed from the list when the
            // connection is closed (onSocketClosed), use: ui to show the active connections
            val key = CidKey(cm.connId, uid)
            trackedCids.add(key)

            // TODO: set dup as true for now (v055f), need to handle dup properly in future
            val duplicate = dup || true
            // if the connection is Rethink's uid and if the dup is false, then the connections
            // are rethink's own connections, so add it in network log as well
            if (!duplicate) {
                // no need to consider return value as the function is called only for logging
                persistAndConstructFlowResponse(cm, proxy, connId, uid)
            }
            // make the cm obj to null so that the db write will not happen
            val c = if (duplicate) cm else null
            return@runBlocking persistAndConstructFlowResponse(c, proxy, connId, uid, isRethink)
        }

        if (trapVpnDns) {
            logd("flow: dns-request, returning ${Backend.Base}, $uid, $connId")
            return@runBlocking persistAndConstructFlowResponse(null, Backend.Base, connId, uid)
        }

        processFirewallRequest(cm, anyRealIpBlocked, blocklists, isSplApp)

        if (cm.isBlocked) {
            // return Ipn.Block, no need to check for other rules
            logd("flow: received rule: block, returning Ipn.Block, $connId, $uid")
            return@runBlocking persistAndConstructFlowResponse(cm, Backend.Block, connId, uid)
        }

        // add to trackedCids, so that the connection can be removed from the list when the
        // connection is closed (onSocketClosed), use: ui to show the active connections
        val key = CidKey(cm.connId, uid)
        trackedCids.add(key)

        return@runBlocking determineProxyDetails(cm, isSplApp)
    }

    private suspend fun isSpecialApp(uid: Int): Boolean {
        if (!appConfig.getBraveMode().isDnsFirewallMode()) {
            return false
        }
        // check if the app is selected to forward dns proxy, orbot, socks5, http proxy
        if (
            !appConfig.isCustomSocks5Enabled() &&
            !appConfig.isCustomHttpProxyEnabled() &&
            !appConfig.isDnsProxyActive() &&
            !appConfig.isOrbotProxyEnabled()
        ) {
            return false
        }

        if (appConfig.isOrbotProxyEnabled()) {
            val endpoint = appConfig.getConnectedOrbotProxy()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            if (endpoint?.proxyAppName == packageName) {
                logd("flow: orbot enabled for $packageName, handling as spl app")
                return true
            }
        }

        if (appConfig.isCustomSocks5Enabled()) {
            val endpoint = appConfig.getSocks5ProxyDetails()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via socks5 proxy
            if (endpoint.proxyAppName == packageName) {
                logd("flow: socks5 enabled for $packageName, handling as spl app")
                return true
            }
        }

        if (appConfig.isCustomHttpProxyEnabled()) {
            val endpoint = appConfig.getHttpProxyDetails()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via http proxy
            if (endpoint.proxyAppName == packageName) {
                logd("flow: http proxy enabled for $packageName, handling as spl app")
                return true
            }
        }

        if (appConfig.isDnsProxyActive()) {
            val endpoint = appConfig.getSelectedDnsProxyDetails() ?: return false
            val packageName = FirewallManager.getPackageNameByUid(uid) ?: return false
            // do not block the app if the app is set to forward the traffic via dns proxy
            if (endpoint.proxyAppName == packageName) {
                logd("flow: dns proxy enabled for $packageName, handling as spl app")
                return true
            }
        }

        return false
    }

    private suspend fun determineProxyDetails(
        connTracker: ConnTrackerMetaData,
        isSplApp: Boolean
    ): intra.Mark {
        val baseOrExit =
            if (isSplApp) {
                Backend.Exit
            } else {
                Backend.Base
            }
        val connId = connTracker.connId
        val uid = connTracker.uid

        if (FirewallManager.isAppExcludedFromProxy(uid)) {
            logd("flow: app is excluded from proxy, returning Ipn.Base, $connId, $uid")
            return persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
        }

        // check for one-wireguard, if enabled, return wireguard proxy for all connections
        val oneWgId = WireguardManager.getOneWireGuardProxyId()
        if (oneWgId != null && oneWgId != WireguardManager.INVALID_CONF_ID) {
            val proxyId = "${ProxyManager.ID_WG_BASE}${oneWgId}"
            // regardless of whether this proxyId exists in go, use it to avoid leaks
            val canRoute = vpnAdapter?.canRouteIp(proxyId, connTracker.destIP, true)
            return if (canRoute == true) {
                handleProxyHandshake(proxyId)
                logd("flow: one-wg is enabled, returning $proxyId, $connId, $uid")
                persistAndConstructFlowResponse(connTracker, proxyId, connId, uid)
            } else {
                // in some configurations the allowed ips will not be 0.0.0.0/0, so the connection
                // will be dropped, in those cases, return base (connection will be forwarded to
                // base proxy)
                logd("flow: one-wg is enabled, but no route; ret:Ipn.Base, $connId, $uid")
                persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
            }
        }

        val wgConfig = WireguardManager.getConfigIdForApp(uid) // also accounts for catch-all
        if (wgConfig != null && wgConfig.id != WireguardManager.INVALID_CONF_ID) {
            val proxyId = "${ProxyManager.ID_WG_BASE}${wgConfig.id}"
            // even if inactive, route connections to wg if lockdown/catch-all is enabled to
            // avoid leaks
            if (wgConfig.isActive || wgConfig.isLockdown || wgConfig.isCatchAll) {
                // if lockdown is enabled, canRoute checks peer configuration and if it returns
                // "false", then the connection will be sent to base and not dropped
                // if lockdown is disabled, then canRoute returns default (true) which
                // will have the effect of blocking all connections
                // ie, if lockdown is enabled, split-tunneling happens as expected but if
                // lockdown is disabled, it has the effect of blocking all connections
                val canRoute = vpnAdapter?.canRouteIp(proxyId, connTracker.destIP, true)
                logd("flow: wg is active/lockdown/catch-all; $proxyId, $connId, $uid; canRoute? $canRoute")
                return if (canRoute == true) {
                    handleProxyHandshake(proxyId)
                    persistAndConstructFlowResponse(connTracker, proxyId, connId, uid)
                } else {
                    persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
                }
            } else {
                // fall-through, no lockdown/catch-all/active wg found, so proceed with other checks
            }
        }

        // carry out this check after wireguard, because wireguard has catchAll and lockdown
        // if no proxy or dns proxy is enabled, return baseOrExit
        if (!appConfig.isProxyEnabled() && !appConfig.isDnsProxyActive()) {
            logd("flow: no proxy/dnsproxy enabled, returning Ipn.Base, $connId, $uid")
            return persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
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

        if (appConfig.isOrbotProxyEnabled()) {
            val endpoint = appConfig.getConnectedOrbotProxy()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            if (endpoint?.proxyAppName == packageName) {
                logd("flow: orbot exit for $packageName, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }

            val activeId = ProxyManager.getProxyIdForApp(uid)
            if (!activeId.contains(ProxyManager.ID_ORBOT_BASE)) {
                Logger.e(LOG_TAG_VPN, "flow: orbot proxy is enabled but app is not included")
                // pass-through
            } else {
                logd("flow: orbot proxy for $uid, $connId")
                return persistAndConstructFlowResponse(
                    connTracker,
                    ProxyManager.ID_ORBOT_BASE,
                    connId,
                    uid
                )
            }
        }

        // chose socks5 proxy over http proxy
        if (appConfig.isCustomSocks5Enabled()) {
            val endpoint = appConfig.getSocks5ProxyDetails()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            logd("flow: socks5 proxy is enabled, $packageName, ${endpoint.proxyAppName}")
            // do not block the app if the app is set to forward the traffic via socks5 proxy
            if (endpoint.proxyAppName == packageName) {
                logd("flow: socks5 exit for $packageName, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }

            logd("flow: socks5 proxy for $connId, $uid")
            return persistAndConstructFlowResponse(
                connTracker,
                ProxyManager.ID_S5_BASE,
                connId,
                uid
            )
        }

        if (appConfig.isCustomHttpProxyEnabled()) {
            val endpoint = appConfig.getHttpProxyDetails()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via http proxy
            if (endpoint.proxyAppName == packageName) {
                logd("flow: http exit for $packageName, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }

            logd("flow: http proxy for $connId, $uid")
            return persistAndConstructFlowResponse(
                connTracker,
                ProxyManager.ID_HTTP_BASE,
                connId,
                uid
            )
        }

        if (appConfig.isDnsProxyActive()) {
            val endpoint = appConfig.getSelectedDnsProxyDetails()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via dns proxy
            if (endpoint?.proxyAppName == packageName) {
                logd("flow: dns proxy enabled for $packageName, return exit, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }
        }

        logd("flow: no proxies, $baseOrExit, $connId, $uid")
        return persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
    }

    private fun handleProxyHandshake(id: String) {
        if (!id.startsWith(ProxyManager.ID_WG_BASE)) {
            // only wireguard proxies are considered for handshakes
            return
        }

        val latestCheckpoint = wgHandShakeCheckpoints[id]
        if (latestCheckpoint == null) {
            Logger.w(LOG_TAG_VPN, "flow: latest checkpoint is null for $id")
            return
        }

        val stats = vpnAdapter?.getProxyStats(id)
        if (stats == null) {
            Logger.w(LOG_TAG_VPN, "flow: stats is null for $id")
            return
        }

        val realtime = elapsedRealtime()
        val cpInterval = realtime - latestCheckpoint
        val cpIntervalSecs = TimeUnit.MILLISECONDS.toSeconds(cpInterval)
        if (cpInterval < this.checkpointInterval) {
            logd("flow: skip refresh for $id, within interval: $cpIntervalSecs")
            return
        }
        val lastHandShake = stats.lastOK
        if (lastHandShake <= 0) {
            Logger.w(LOG_TAG_VPN, "flow: skip refresh, handshake never done for $id")
            return
        }
        logd("flow: handshake check for $id, $lastHandShake, interval: $cpIntervalSecs")
        wgHandShakeCheckpoints[id] = realtime
        val currTimeMs = System.currentTimeMillis()
        val durationMs = currTimeMs - lastHandShake
        val durationSecs = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        // if the last handshake is older than the timeout, refresh the proxy
        val mustRefresh = durationMs > wgHandshakeTimeout
        Logger.i(LOG_TAG_VPN, "flow: refresh $id after $durationSecs: $mustRefresh")
        if (mustRefresh) {
            io("proxyHandshake") { vpnAdapter?.refreshProxy(id) }
        }
    }

    fun hasCid(connId: String, uid: Int): Boolean {
        // get app id from uid
        val uid0 = FirewallManager.appId(uid, isPrimaryUser())
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

    fun getDnsStatus(id: String): Long? {
        return vpnAdapter?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return vpnAdapter?.getRDNS(type)
    }

    private fun persistAndConstructFlowResponse(
        cm: ConnTrackerMetaData?,
        proxyId: String,
        connId: String,
        uid: Int,
        isRethink: Boolean = false
    ): intra.Mark {
        // persist ConnTrackerMetaData
        if (cm != null) {
            cm.proxyDetails = proxyId
            // assign the proxy details to cm after the decision is made
            if (ProxyManager.isIpnProxy(proxyId) && !cm.isBlocked) {
                cm.blockedByRule = FirewallRuleset.RULE12.id
            }
            if (isRethink) {
                netLogTracker.writeRethinkLog(cm)
            } else {
                netLogTracker.writeIpLog(cm)
            }
            logd("flow: connTracker: $cm")
        }

        val mark = intra.Mark()
        mark.pid = proxyId
        mark.cid = connId
        // if rethink, then set uid as rethink, so that go process can handle it accordingly
        if (isRethink) {
            mark.uid = Backend.UidSelf
        } else {
            mark.uid = uid.toString()
        }
        if (cm == null) {
            Logger.i(
                LOG_TAG_VPN,
                "flow: returning mark: $mark for connId: $connId, uid: $uid, cm: null"
            )
        } else {
            Logger.i(
                LOG_TAG_VPN,
                "flow: returning mark: $mark for src(${cm.sourceIP}: ${cm.sourcePort}), dest(${cm.destIP}:${cm.destPort})"
            )
        }
        return mark
    }

    private suspend fun processFirewallRequest(
        metadata: ConnTrackerMetaData,
        anyRealIpBlocked: Boolean = false,
        blocklists: String = "",
        isSplApp: Boolean
    ) {
        val rule = firewall(metadata, anyRealIpBlocked, isSplApp)

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
            Logger.w(LOG_TAG_VPN, "error while fetching proxy status: vpnAdapter is null")
            null
        }
    }

    fun getProxyStats(id: String): Stats? {
        return if (vpnAdapter != null) {
            vpnAdapter?.getProxyStats(id)
        } else {
            Logger.w(LOG_TAG_VPN, "error while fetching proxy stats: vpnAdapter is null")
            null
        }
    }

    fun getSupportedIpVersion(id: String): Pair<Boolean, Boolean>? {
        return if (vpnAdapter != null) {
            vpnAdapter?.getSupportedIpVersion(id)
        } else {
            Logger.w(LOG_TAG_VPN, "error while fetching protocol status: vpnAdapter is null")
            null
        }
    }

    fun isSplitTunnelProxy(id: String, pair: Pair<Boolean, Boolean>): Boolean {
        return vpnAdapter?.isSplitTunnelProxy(id, pair) ?: false
    }

    fun syncP50Latency(id: String) {
        io("syncP50Latency") { vpnAdapter?.syncP50Latency(id) }
    }
}
