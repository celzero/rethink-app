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
import Logger.LOG_BATCH_LOGGER
import Logger.LOG_GO_LOGGER
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
import android.app.UiModeManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.content.res.Configuration
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import backend.Backend
import backend.DNSOpts
import backend.RDNS
import backend.RouterStats
import backend.ServerSummary
import backend.Tab
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.iab.SubscriptionCheckWorker
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.CoFactory
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
import com.celzero.bravedns.util.Constants.Companion.PRIMARY_USER
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Daemons
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
import intra.Mark
import intra.PreMark
import intra.SocketSummary
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
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

class BraveVPNService : VpnService(), ConnectionMonitor.NetworkListener, Bridge, OnSharedPreferenceChangeListener {

    private val vpnScope = MainScope()

    private var connectionMonitor: ConnectionMonitor = ConnectionMonitor(this)

    // multiple coroutines call both signalStopService and makeOrUpdateVpnAdapter and so
    // set and unset this variable on the serializer thread
    @Volatile
    private var vpnAdapter: GoVpnAdapter? = null

    // used mostly for service to adapter creation and updates
    private var serializer: CoroutineDispatcher = Daemons.make("vpnser")

    private var flowDispatcher = Daemons.ioDispatcher("flow", Mark(),  vpnScope)
    private var inflowDispatcher = Daemons.ioDispatcher("inflow", Mark(), vpnScope)
    private var preflowDispatcher = Daemons.ioDispatcher("preflow", PreMark(), vpnScope)
    private var dnsQueryDispatcher = Daemons.ioDispatcher("onQuery", DNSOpts(), vpnScope)

    private var builderStats: String = ""

    companion object {
        private const val TAG = "VpnService;"
        const val SERVICE_ID = 1 // Only has to be unique within this app.
        const val MEMORY_NOTIFICATION_ID = 29001
        const val NW_ENGINE_NOTIFICATION_ID = 29002

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
        // changing the below ip should require a changes in ConnectionTracer, RethinkLogAdapter
        private const val IPV4_TEMPLATE: String = "10.111.222.%d"
        private const val IPV4_PREFIX_LENGTH: Int = 24

        // IPv6 vpn constants
        // Randomly generated unique local IPv6 unicast subnet prefix, as defined by RFC 4193
        // changing the below ip should require a changes in ConnectionTracer, RethinkLogAdapter
        private const val IPV6_TEMPLATE: String = "fd66:f83a:c650::%d"
        private const val IPV6_PREFIX_LENGTH: Int = 120

        const val VPN_INTERFACE_MTU: Int = 1280
        // TODO: should be different for IPv4 and IPv6, but for now it is same
        // IPv4: 576, IPv6: 1280
        const val MIN_MTU: Int = 1280

        // TODO: add routes as normal but do not send fd to netstack
        // repopulateTrackedNetworks also fails open see isAnyNwValidated
        const val FAIL_OPEN_ON_NO_NETWORK = false

        // route v4 in v6 only networks?
        const val ROUTE4IN6 = true

        // subscription check interval in milliseconds 4 hours
        private const val PLUS_CHECK_INTERVAL = 4 * 60 *  60 * 1000L // changed to 4 min for testing
    }

    private var lastSubscriptionCheckTime: Long = 0

    private var isLockDownPrevious = AtomicBoolean(false)

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

    // used to store the conn-ids that need to be closed when device is locked,
    // this is used to close the connections when the device is locked
    // list will exclude bypassed apps, domains and ip rules
    private var trackedCidsToClose = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // data class to store the connection summary
    data class CidKey(val cid: String, val uid: Int)

    private var excludedApps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // post underlying networks as live data
    @Volatile
    var underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null

    @Volatile
    var overlayNetworks: OverlayNetworks = OverlayNetworks()

    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null

    // live-data to store the region received from onResponse
    val regionLiveData: MutableLiveData<String> = MutableLiveData()

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
        Logger.d(LOG_TAG_VPN, "$TAG $msg")
    }

    override fun bind4(who: String, addrPort: String, fid: Long) {
        var v4Net = underlyingNetworks?.ipv4Net
        val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
        if (ROUTE4IN6 && isAuto && v4Net.isNullOrEmpty()) {
            v4Net = underlyingNetworks?.ipv6Net
        }

        return bindAny(who, addrPort, fid, v4Net ?: emptyList())
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

        if (nws.isEmpty()) {
            Logger.w(LOG_TAG_VPN, "no network to bind, who: $who, $addrPort")
            return
        }

        var pfd: ParcelFileDescriptor? = null
        try {
            // split the addrPort to get the IP address and convert it to InetAddress
            val dest = IpRulesManager.splitHostPort(addrPort)
            val destIp = IPAddressString(dest.first).address
            val destPort = dest.second.toIntOrNull()
            val destAddr = destIp.toInetAddress()

            // in case of zero, bind only for wg connections, wireguard tries to bind to
            // network with zero addresses
            if (
                (destIp.isZero && who.startsWith(ID_WG_BASE)) ||
                destIp.isZero ||
                destIp.isLoopback
            ) {
                logd("bind: invalid destIp: $destIp, who: $who, $addrPort")
                return
            }

            pfd = ParcelFileDescriptor.adoptFd(fid.toInt())

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
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "err bind: $who, $addrPort, $fid, ${e.message}, $e")
        } finally {
            pfd?.detachFd()
        }
        Logger.w(LOG_TAG_VPN, "bind failed: $who, $addrPort, $fid")
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

    suspend fun probeIp(ip: String): ConnectionMonitor.ProbeResult? {
        return connectionMonitor.probeIp(ip)
    }

    fun protectSocket(socket: Socket) {
        this.protect(socket)
        Logger.v(LOG_TAG_VPN, "socket protected")
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
        recdUid: Int,
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        caller: ConnectionTracer.CallerSrc
    ): Int {
        if (recdUid != INVALID_UID) {
            return recdUid
        }
        // caller: true - called from flow, false - called from inflow
        return if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            ioAsync("getUidQ") { connTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort, caller) }
                .await()
        } else {
            recdUid // uid must have been retrieved from procfs by the caller
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
            if (appStatus.isUntracked() && uid != INVALID_UID) {
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
                return FirewallRuleset.RULE16
            }

            when (getDomainRule(connInfo.query, uid)) {
                DomainRulesManager.Status.BLOCK -> {
                    logd("firewall: domain blocked, $uid")
                    return FirewallRuleset.RULE2E
                }

                DomainRulesManager.Status.TRUST -> {
                    logd("firewall: domain trusted, $uid")
                    return FirewallRuleset.RULE2F
                }

                DomainRulesManager.Status.NONE -> {
                    // fall-through
                }
            }

            // IP rules
            when (uidIpStatus(uid, connInfo.destIP, connInfo.destPort)) {
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
                    logd("firewall: global domain trusted, $uid, ${connInfo.query}")
                    return FirewallRuleset.RULE2I
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
            when (globalIpRule(connInfo.destIP, connInfo.destPort)) {
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
            // no need to handle universal firewall rules for these apps
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
                closeTrackedConnsOnDeviceLock()
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
            Logger.crash(LOG_TAG_VPN, "unexpected err in firewall(), block anyway", iex)
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
            val addr = try {
                IPAddressString(destIp).address
            } catch (e: Exception) {
                return IpRulesManager.IpRuleStatus.NONE
            }

            val ip4in6 = IPUtil.ip4in6(addr) ?: return IpRulesManager.IpRuleStatus.NONE
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
            keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
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
        val treatMobileAsMetered = persistentState.treatOnlyMobileNetworkAsMetered
        if (!useActive || VpnController.isVpnLockdown()) {
            return if (treatMobileAsMetered) {
                // TODO: should this check be a combination of cellular & metered?
                isIfaceCellular(dst)
            } else {
                isIfaceMetered(dst)
            }
        }
        return if (treatMobileAsMetered) {
            isActiveIfaceCellular()
        } else {
            isActiveIfaceMetered()
        }
    }

    private fun isIfaceCellular(dst: String): Boolean {
        val dest = IPAddressString(dst)
        if (dest.isEmpty) {
            Logger.e(LOG_TAG_VPN, "invalid destination IP: $dst")
            return isActiveIfaceCellular()
        }

        val curnet = underlyingNetworks
        val cap =
            if (dest.isZero || dest.isIPv6) { // wildcard addrs(::80, ::443, etc.) are bound to ipv6
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv6Net?.isEmpty() == true) {
                    return isActiveIfaceCellular()
                }
                curnet?.ipv6Net?.firstOrNull()?.capabilities
            } else {
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv4Net?.isEmpty() == true) {
                    return isActiveIfaceCellular()
                }
                curnet?.ipv4Net?.firstOrNull()?.capabilities
            }
        // if there are no network to be bound given a destination IP, fallback to active network
        if (cap == null) {
            Logger.e(LOG_TAG_VPN, "no network to be bound for $dst, use active network")
            return isActiveIfaceCellular()
        }
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
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

    private fun isActiveIfaceCellular(): Boolean {
        val curnet = underlyingNetworks ?: return false // assume unmetered
        val now = elapsedRealtime()
        val ts = curnet.lastUpdated
        if (abs(now - ts) > Constants.ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            curnet.lastUpdated = now
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork)
            curnet.isActiveNetworkCellular =
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
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

        val intent = Intent(this, NotificationHandlerActivity::class.java)
        intent.putExtra(
            NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME,
            NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
        )

        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, AppLockActivity::class.java),
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
            abs(now - accessibilityHearbeatTimestamp) >
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

        builder.setUnderlyingNetworks(getUnderlays())

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

        io("nlt") {
            Log.d(LOG_BATCH_LOGGER, "vpn: restart $vpnScope")
            netLogTracker.restart(vpnScope)
        }

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
        return Observer { t ->
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
        return Observer { t ->
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
        return Observer { settingUpOrbot.set(it) }
    }

    private fun isAppLockEnabled(): Boolean {
        if (isAppRunningOnTv()) return false

        // TODO: should we check for last unlock time here?
        MiscSettingsActivity.BioMetricType.fromValue(persistentState.biometricAuthType).let {
            return it.enabled()
        }
    }

    private fun updateNotificationBuilder(): Notification {
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, AppLockActivity::class.java),
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
        var isProxyEnabled = appConfig.isProxyEnabled()
        if (appConfig.isWgEnabled() && WireguardManager.getActiveConfigs().isEmpty()) {
            // when wireguard is enabled but no configs are present, then do not show in notif
            // this config is added part of Auto
            isProxyEnabled = false
        }

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
        // do not show notification action when app lock is enabled
        val notifActionType =
            if (isAppLockEnabled()) {
                NotificationActionType.NONE
            } else {
                NotificationActionType.getNotificationActionType(
                    persistentState.notificationActionType
                )
            }
        logd(
            "notification action type: ${persistentState.notificationActionType}, $notifActionType"
        )

        when (notifActionType) {
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
                builder.setContentTitle(contentTitle)
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

    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (ignored: Exception) {
            false
        }
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
                    Logger.w(LOG_TAG_VPN, "start service failed, retrying with connected device")
                    ok = startForegroundService(FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                }
                if (!ok) {
                    Logger.w(LOG_TAG_VPN, "start service failed, stopping service")
                    signalStopService("startFg1", userInitiated = false) // notify and stop
                    return@ui
                }
            } else {
                val ok = startForegroundService()
                if (!ok) {
                    Logger.w(LOG_TAG_VPN, "start service failed ( > U ), stopping service")
                    signalStopService("startFg2", userInitiated = false) // notify and stop
                    return@ui
                }
            }

            startOrbotAsyncIfNeeded()

            // this should always be set before ConnectionMonitor is init-d
            // see restartVpn and updateTun which expect this to be the case
            persistentState.setVpnEnabled(true)

            val isNewVpn = connectionMonitor.onVpnStart(this)

            if (isNewVpn) {
                // clear the underlying networks, so that the new vpn can be created with the
                // current active network.
                underlyingNetworks = null
                Logger.i(LOG_TAG_VPN, "new vpn")
            }

            val mtu = mtu()
            val opts =
                appConfig.newTunnelOptions(
                    this,
                    getFakeDns(),
                    appConfig.getProtocolTranslationMode(),
                    mtu
                )

            Logger.i(LOG_TAG_VPN, "start-fg with opts $opts (for new-vpn? $isNewVpn)")
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
                        restartVpn(this, opts, Networks(null, overlayNetworks), why = "startVpn")
                        // call this *after* a new vpn is created #512
                        uiCtx("observers") { observeChanges() }
                    }
                }
            }
        }
        return Service.START_STICKY
    }

    private suspend fun checkForPlusSubscription() {
        // start the subscription check only if the user has enabled the feature
        // and the subscription check is not already in progress
        // invoke the work manager to check the subscription status
        if (RpnProxyManager.isRpnActive()) {
            io("rethinkPlusSubs") {
                handleRpnProxies()
                Logger.i(LOG_TAG_VPN, "checkForPlusSubscription: start work manager")
                val workManager = WorkManager.getInstance(this)
                val workRequest = OneTimeWorkRequestBuilder<SubscriptionCheckWorker>().build()
                workManager.enqueueUniqueWork(
                    SubscriptionCheckWorker.WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            }
        } else {
            Logger.i(LOG_TAG_VPN, "checkForPlusSubscription: feature disabled")
        }
    }

    private suspend fun handleRpnProxies() {
        if (RpnProxyManager.isRpnActive()) {
            if (vpnAdapter == null) {
                Logger.i(LOG_TAG_VPN, "handleRpnProxies: adapter null, no-op")
                return
            }
            vpnAdapter?.addMultipleDoHAsPlus()
            val wt = vpnAdapter?.testRpnProxy(RpnProxyManager.RpnType.WARP) == true
            val st = vpnAdapter?.testRpnProxy(RpnProxyManager.RpnType.SE) == true
            val at = vpnAdapter?.testRpnProxy(RpnProxyManager.RpnType.AMZ) == true
            val pt = vpnAdapter?.testRpnProxy(RpnProxyManager.RpnType.PROTON) == true
            val x64 = vpnAdapter?.testRpnProxy(RpnProxyManager.RpnType.EXIT_64) == true
            Logger.i(LOG_TAG_VPN, "$TAG tests: w: $wt, se: $st, amz: $at, proton: $pt, x64: $x64")
            if (wt) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: warp not enabled, register")
                val existingBytes = RpnProxyManager.getWarpExistingData()
                val bytes = registerAndFetchWarpConfigIfNeeded(existingBytes)
                RpnProxyManager.updateWarpConfig(bytes)
            }
            if (st) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: se not enabled, register")
                vpnAdapter?.registerSurfEasyIfNeeded()
            }
            if (at) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: amz not enabled, register")
                val existingBytes = RpnProxyManager.getAmzExistingData()
                val bytes = registerAndFetchAmzConfigIfNeeded(existingBytes)
                RpnProxyManager.updateAmzConfig(bytes)
            }
            if (pt) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: proton not enabled, register")
                val existingBytes = RpnProxyManager.getProtonExistingData()
                val bytes = registerAndFetchProtonIfNeeded(existingBytes)
                RpnProxyManager.updateProtonConfig(bytes)
            }

            // TODO: get the list of other countries other than default, add all of them
            // make sure it doesn't exceed the max number of allowed configs (5)
            // if the user has selected a country, then add that country to the list
            val countries = RpnProxyManager.getSelectedCCs()
            if (countries.isNotEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: selected countries: $countries")
                // TODO: add the selected countries to the tunnel, new API needed
            }
            vpnAdapter?.setRpnAutoMode(RpnProxyManager.rpnMode())
        } else { // either in pause mode or plus disabled
            Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: plus disabled")
            // unregister the rpn proxies
            vpnAdapter?.unregisterWarp()
            vpnAdapter?.unregisterSE()
            vpnAdapter?.unregisterAmnezia()
            vpnAdapter?.unregisterProton()
        }
    }

    private suspend fun setRpnAutoMode() {
        val res = vpnAdapter?.setRpnAutoMode(RpnProxyManager.rpnMode())
        logd("set rpn auto to: ${RpnProxyManager.rpnMode().isHideIp()}, set? $res")
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
        if (minMtu <= MIN_MTU) {
            Logger.w(LOG_TAG_VPN, "mtu less than or equal to $MIN_MTU, using $MIN_MTU")
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
            io("outOfSync") { signalStopService("outOfSync", userInitiated = false) }
            return
        }

        // should not be called in normal circumstances, but just in case...
        val ok = vpnAdapter?.updateTun(tunnelOptions) ?: false
        // TODO: like Intra, call VpnController#stop instead? see
        // VpnController#onStartComplete
        if (!ok) {
            Logger.w(LOG_TAG_VPN, "Cannot handle vpn adapter changes, no tunnel")
            io("noTunnel") { signalStopService("noTunnel", userInitiated = false) }
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

            PersistentState.BIOMETRIC_AUTH -> {
                // update the notification builder to show the action buttons based on the biometric
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
                    if (persistentState.defaultDnsUrl.isNotEmpty()) {
                        vpnAdapter?.addDefaultTransport(persistentState.defaultDnsUrl)
                    } else {
                        setNetworkAndDefaultDnsIfNeeded()
                    }
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
                    vpnAdapter?.notifyLoopback()
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

            PersistentState.ANTI_CENSORSHIP_TYPE -> {
                io("antiCensorship") {
                    setDialStrategy()
                }
            }

            PersistentState.RETRY_STRATEGY -> {
                io("retryStrategy") {
                    setDialStrategy()
                }
            }
            PersistentState.ENDPOINT_INDEPENDENCE -> {
                io("endpointIndependence") {
                    setTransparency()
                }
            }
            PersistentState.TCP_KEEP_ALIVE -> {
                io("tcpKeepAlive") {
                    setDialStrategy()
                }
            }
            PersistentState.USE_SYSTEM_DNS_FOR_UNDELEGATED_DOMAINS -> {
                io("useSystemDnsForUndelegatedDomains") {
                    undelegatedDomains()
                }
            }
            PersistentState.SLOWDOWN_MODE -> {
                io("slowdownMode") {
                    setSlowdownMode()
                }
            }
            PersistentState.NETWORK_ENGINE_EXPERIMENTAL -> {
                io("networkEngineExperimental") {
                    setExperimentalSettings(persistentState.nwEngExperimentalFeatures)
                }
            }
            PersistentState.USE_RPN -> {
                io("rpnUpdated") {
                    handleRpnProxies()
                }
            }
            PersistentState.RPN_MODE -> {
                io("rpnMode") {
                    setRpnAutoMode()
                }
            }
            PersistentState.DIAL_TIMEOUT_SEC -> {
                io("tunTimeout") {
                    setDialStrategy()
                }
            }
        }
    }

    private suspend fun setExperimentalSettings(experimental: Boolean) {
        Logger.i(LOG_TAG_VPN, "set experimental settings: $experimental")
        vpnAdapter?.setExperimentalSettings(experimental)
    }

    private suspend fun undelegatedDomains() {
        Logger.i(LOG_TAG_VPN, "use system dns for undelegated domains: ${persistentState.useSystemDnsForUndelegatedDomains}")
        vpnAdapter?.undelegatedDomains(persistentState.useSystemDnsForUndelegatedDomains)
    }

    private suspend fun setDialStrategy() {
        Logger.d(
            LOG_TAG_VPN,
            "set dial strategy: ${persistentState.dialStrategy}, retry: ${persistentState.retryStrategy}, tcpKeepAlive: ${persistentState.tcpKeepAlive}, timeout: ${persistentState.dialTimeoutSec}"
        )
        vpnAdapter?.setDialStrategy()
    }

    private suspend fun setSlowdownMode() {
        Logger.d(LOG_TAG_VPN, "set slowdown mode: ${persistentState.slowdownMode}")
        vpnAdapter?.setSlowdownMode()
    }

    private suspend fun setTransparency() {
        Logger.d(LOG_TAG_VPN, "set endpoint independence: ${persistentState.endpointIndependence}")
        vpnAdapter?.setTransparency(persistentState.endpointIndependence)
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

        if (uid == UID_EVERYBODY) {
            // when the uid is everybody, close all the connections
            io("closeConn") { vpnAdapter?.closeConnections(emptyList()) }
            return
        }

        // close conns can now be called with a list of uids / connIds
        val uid0 = listOf(FirewallManager.appId(uid, isPrimaryUser()).toString())
        io("closeConn") { vpnAdapter?.closeConnections(uid0) }
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
                //vpnAdapter?.setTcpProxy()
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

    // invoked on pref / probe-ip changes, so that the connection monitor can
    // re-initiate the connectivity checks
    suspend fun notifyConnectionMonitor() {
        connectionMonitor.onUserPreferenceChanged()
    }

    private suspend fun updateDnsAlg() {
        vpnAdapter?.setDnsAlg()
    }

    fun signalStopService(reason: String, userInitiated: Boolean = true) {
        if (!userInitiated) notifyUserOnVpnFailure()
        io(reason) { stopVpnAdapter() }
        stopSelf()
        Logger.i(LOG_TAG_VPN, "stopped vpn adapter & service: $reason, $userInitiated")
    }

    private suspend fun stopVpnAdapter() =
        withContext(CoroutineName("stopVpn") + serializer) {
            if (vpnAdapter == null) {
                Logger.i(LOG_TAG_VPN, "vpn adapter already stopped")
                return@withContext
            }

            vpnAdapter?.closeTun()
            vpnAdapter = null
            Logger.i(LOG_TAG_VPN, "stop vpn adapter")
        }

    private suspend fun restartVpnWithNewAppConfig(
        underlyingNws: ConnectionMonitor.UnderlyingNetworks? = underlyingNetworks,
        overlayNws: OverlayNetworks = overlayNetworks,
        reason: String
    ) {
        logd("restart vpn with new app config")
        val nws = Networks(underlyingNws, overlayNws)
        restartVpn(
            this,
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

    private suspend fun restartVpn(
        ctx: Context,
        opts: AppConfig.TunnelOptions,
        networks: Networks,
        why: String
    ) =
        withContext(CoroutineName(why) + serializer) {
            if (!persistentState.getVpnEnabled()) {
                // when persistent-state "thinks" vpn is disabled, stop the service, especially when
                // we could be here via onStartCommand -> isNewVpn -> restartVpn while both,
                // vpn-service & conn-monitor exists & vpn-enabled state goes out of sync
                logAndToastIfNeeded(
                    "$why, stop-vpn(restartVpn), tracking vpn is out of sync",
                    Log.ERROR
                )
                io("outOfSyncRestart") {
                    signalStopService("outOfSyncRestart", userInitiated = false)
                }
                return@withContext
            }
            Logger.i(
                LOG_TAG_VPN,
                "---------------------------RESTART-INIT----------------------------"
            )
            // In vpn lockdown mode, unlink the adapter to close the previous file descriptor (fd)
            // and use a new fd after creation. This should only be done in lockdown mode,
            // as leaks are not possible.
            // doing so also fixes 'endpoint closed' errors which are frequent in lockdown mode
            if (VpnController.isVpnLockdown()) {
               vpnAdapter?.unlink()
            }
            // attempt seamless hand-off as described in VpnService.Builder.establish() docs
            val tunFd = establishVpn(networks)
            if (tunFd == null) {
                logAndToastIfNeeded("$why, cannot restart-vpn, no tun-fd", Log.ERROR)
                io("noTunRestart1") { signalStopService("noTunRestart1", userInitiated = false) }
                return@withContext
            }

            val ok =
                makeOrUpdateVpnAdapter(
                    ctx,
                    tunFd,
                    opts,
                    builderRoutes
                ) // builderRoutes set in establishVpn()
            if (!ok) {
                logAndToastIfNeeded("$why, cannot restart-vpn, no vpn-adapter", Log.ERROR)
                io("noTunnelRestart2") { signalStopService("noTunRestart2", userInitiated = false) }
                return@withContext
            } else {
                logAndToastIfNeeded("$why, vpn restarted", Log.INFO)
            }
            Logger.i(
                LOG_TAG_VPN,
                "---------------------------RESTART-OK----------------------------"
            )

            notifyConnectionStateChangeIfNeeded()
            informVpnControllerForProtoChange(builderRoutes)
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
        ctx: Context,
        tunFd: ParcelFileDescriptor,
        opts: AppConfig.TunnelOptions,
        p: Pair<Boolean, Boolean>
    ): Boolean =
        withContext(CoroutineName("makeVpn") + serializer) {
            val ok = true
            val noTun = false // should eventually call signalStopService(userInitiated=false)
            val protos = InternetProtocol.byProtos(p.first, p.second).value()
            try {
                if (vpnAdapter == null) {
                    // create a new vpn adapter
                    vpnAdapter = GoVpnAdapter(ctx, vpnScope, tunFd, opts) // may throw
                    GoVpnAdapter.setLogLevel(persistentState.goLoggerLevel.toInt())
                    vpnAdapter?.initResolverProxiesPcap(opts)
                    checkForPlusSubscription()
                    return@withContext ok
                } else {
                    Logger.i(LOG_TAG_VPN, "vpn-adapter exists, use it")
                    // in case, if vpn-adapter exists, update the existing vpn-adapter
                    if (vpnAdapter?.updateLinkAndRoutes(tunFd, opts, protos) == false) {
                        Logger.e(LOG_TAG_VPN, "err update vpn-adapter")
                        return@withContext noTun
                    }
                    return@withContext ok
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "err new vpn-adapter: ${e.message}", e)
                return@withContext noTun
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
        Logger.i(LOG_TAG_VPN, "onNetworkDisconnected: state: z, $networks, updatedTs: ${networks.lastUpdated}")
        // TODO: if getUnderlays() is empty array, set empty routes on tun using vpn builder
        setUnderlyingNetworks(getUnderlays())
        // always restart, because global var builderRoutes is set to only in builder, also
        // need to set routes as well based on the protos
        // TODO: do we need to restart if the network is not changed?
        io("nwDisconnect") {
            restartVpnWithNewAppConfig(networks, reason = "nwDisconnect")
        }
        setNetworkAndDefaultDnsIfNeeded(emptySet(), true)
        VpnController.onConnectionStateChanged(null)
    }

    override fun onNetworkRegistrationFailed() {
        Logger.i(LOG_TAG_VPN, "recd nw reg failed, stop vpn service with notification")
        signalStopService("nwRegFail", userInitiated = false)
    }

    private fun getUnderlays(): Array<Network>? {
        val networks = underlyingNetworks

        if (networks == null) {
            Logger.i(
                LOG_TAG_VPN,
                "getUnderlays: networks is null; fail-open? $FAIL_OPEN_ON_NO_NETWORK"
            )
            return if (FAIL_OPEN_ON_NO_NETWORK) { // failing open on no nw
                null // use whichever network is active, whenever it becomes active
            } else {
                emptyArray() // deny all traffic; fail closed
            }
        }

        // underlying networks is set to null, which prompts Android to set it to whatever is
        // the current active network. Later, ConnectionMonitor#onVpnStarted, depending on user
        // chosen preferences, sets appropriate underlying network/s.

        // add ipv4/ipv6 networks to the tunnel
        val allNetworks =
            networks.ipv4Net.map { it.network } + networks.ipv6Net.map { it.network }
        val hasUnderlyingNetwork = !allNetworks.isEmpty()
        val underlays = if (hasUnderlyingNetwork) {
            if (networks.useActive) {
                null // null denotes active network
            } else {
                allNetworks.toTypedArray() // never empty
            }
        } else {
            if (FAIL_OPEN_ON_NO_NETWORK) { // failing open on no nw
                null // use whichever network is active, whenever it becomes active
            } else {
                emptyArray() // deny all traffic; fail closed
            }
        }

        Logger.i(
            LOG_TAG_VPN,
            "getUnderlays: use active? ${networks.useActive}; fail-open? $FAIL_OPEN_ON_NO_NETWORK; networks: ${allNetworks.size}; null-underlay? ${underlays == null}"
        )
        return underlays
    }

    override fun onNetworkConnected(networks: ConnectionMonitor.UnderlyingNetworks) {
        val curnet = underlyingNetworks
        val out = interestingNetworkChanges(curnet, networks)
        val isRoutesChanged = hasRouteChangedInAutoMode(out)
        val isBoundNetworksChanged = out.netChanged
        val isMtuChanged = out.mtuChanged
        val prevDns = underlyingNetworks?.dnsServers?.keys ?: emptySet()
        underlyingNetworks = networks

        // always reset the system dns server ip of the active network with the tunnel
        setNetworkAndDefaultDnsIfNeeded(prevDns, (isRoutesChanged || isBoundNetworksChanged))

        setUnderlyingNetworks(getUnderlays())

        logd(
            "mtu? $isMtuChanged(o:${curnet?.minMtu}, n:${networks.minMtu}), tun: ${tunMtu()}; routes? $isRoutesChanged, bound-nws? $isBoundNetworksChanged, updatedTs: ${networks.lastUpdated}"
        )

        // restart vpn if the routes or when mtu changes
        if (isMtuChanged || isRoutesChanged) {
            Logger.i(LOG_TAG_VPN, "$TAG; mtu/routes changed,  restart vpn")
            io("nwConnect") {
                restartVpnWithNewAppConfig(
                    networks,
                    reason =
                    "mtu? $isMtuChanged(o:${curnet?.minMtu}, n:${networks.minMtu}); routes? $isRoutesChanged"
                )
                // not needed as the refresh is done in go, TODO: remove below code later
                // only after set links and routes, wg can be refreshed
                // if (isRoutesChanged) {
                    // Logger.v(LOG_TAG_VPN, "refresh wg after network change")
                    // refreshProxies()
                // }
            }
        }

        if (!isRoutesChanged && isBoundNetworksChanged) {
            // Workaround for WireGuard connection issues after network change
            // WireGuard may fail to connect to the server when the network changes.
            Logger.i(LOG_TAG_VPN, "$TAG routes/bound-nws changed, refresh wg")
            refreshProxies() // takes care of adding the proxies if missing in tunnel
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
    }

    fun tunMtu(): Int {
        return vpnAdapter?.tunMtu() ?: 0
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
            logd("tun: old and new nws are null")
            return NetworkChanges(routesChanged = false, netChanged = false, mtuChanged = false)
        }
        // no old routes to compare with, return true
        if (old == null) {
            logd("tun: old nw is null, new nw: $new")
            return NetworkChanges()
        }
        if (new == null) {
            // new is null, but old is not, then check for changes in aux networks
            logd("tun: new nw is null, using old nw: $old")
            new = old
        }

        val tunMtu = tunMtu()
        logd(
            "tun: ${tunMtu}; old: ${old.minMtu}, new: ${new.minMtu}; oldaux: ${overlayNetworks.mtu}, newaux: ${aux.mtu}"
        )

        // mark mtu changed if any tunMtu differs from min mtu of new underlying & overlay network
        val mtuChanged = tunMtu != min(new.minMtu, aux.mtu)

        // val auxHas4 = aux.has4 || aux.failOpen
        // val auxHas6 = aux.has6 || aux.failOpen

        val (builderHas4, builderHas6) = builderRoutes // current tunnel routes v4/v6?

        // when the nws are null from the connection monitor, then consider the builder routes
        // as the new routes
        val vpnHas4 = new.vpnRoutes?.first ?: builderHas4
        val vpnHas6 = new.vpnRoutes?.second ?: builderHas6

        val n = Networks(new, aux)
        val (tunWants4, tunWants6) = determineRoutes(n)

        // old & new agree on activ capable of routing ipv4 or not
        val ok4 = builderHas4 == tunWants4 && builderHas4 == vpnHas4
        // old & new agree on activ capable of routing ipv6 or not
        val ok6 = builderHas6 == tunWants6 && builderHas6 == vpnHas6

        val routesChanged = !ok4 || !ok6

        logd("tun: has4: $builderHas4, wants4: $tunWants4, vpnHas4: $vpnHas4, has6: $builderHas6, wants6: $tunWants6, vpnHas6: $vpnHas6, routesChanged? $routesChanged, ")

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
                logd("tun: oldActiv4: $oldActivHas4, newActiv4: $activHas4, oldActiv6: $oldActivHas6, newActiv6: $activHas6, netChanged? $netChanged")
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
        logd("tun: oldFirst4: $oldFirst4, newFirst4: $newFirst4, oldFirst6: $oldFirst6, newFirst6: $newFirst6, netChanged? $netChanged")
        return NetworkChanges(routesChanged, netChanged, mtuChanged)
    }

    private fun setNetworkAndDefaultDnsIfNeeded(prevDns: Set<InetAddress>? = null, forceUpdate: Boolean = false) {
        val currNet = underlyingNetworks
        // get dns servers from the first network or active network
        val active = connectivityManager.activeNetwork
        val dnsServers: MutableSet<InetAddress> = if (connectivityManager
                        .getNetworkCapabilities(active)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            Logger.i(LOG_TAG_VPN, "active network is vpn, so no need get dns servers")
            mutableSetOf()
        } else {
            val lp = connectivityManager.getLinkProperties(active)
            // here dnsServers are validated with underlyingNetworks, so there may be a case
            // where v6 address is added when v6 network is not available
            // so, dnsServers will have both v4 and v6 addresses
            lp?.dnsServers?.toMutableSet() ?: mutableSetOf()
        }

        if (dnsServers.isEmpty()) {
            // first network is considered to be active network
            val ipv4 = currNet?.ipv4Net?.firstOrNull()
            val ipv6 = currNet?.ipv6Net?.firstOrNull()
            val dns4 = ipv4?.linkProperties?.dnsServers
            val dns6 = ipv6?.linkProperties?.dnsServers
            // if active network is not found in the list of networks, then use dns from
            // first network
            val dl = mutableSetOf<InetAddress>()
            // add all the dns servers from the first network, depending on the current
            // route, netstack will make use of the dns servers
            dns4?.let { dl.addAll(it) }
            dns6?.let { dl.addAll(it) }
            Logger.i(LOG_TAG_VPN, "dns servers for network: $dl")
            dnsServers.addAll(dl)
        } else {
            Logger.i(LOG_TAG_VPN, "dns servers for network: $dnsServers")
        }

        if (dnsServers.isEmpty()) {
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
            Logger.d(LOG_TAG_VPN, "dns: $dnsServers, existing: $prevDns, force: $forceUpdate")
            if (areDnsEqual(prevDns, dnsServers) && !forceUpdate) {
                Logger.i(LOG_TAG_VPN, "system dns servers are same, no need to set")
                return@io
            }
            Logger.i(LOG_TAG_VPN, "system dns servers changed, set: $dnsServers")
            // set system dns whenever there is a change in network
            val dns = dnsServers.map { it.hostAddress }
            vpnAdapter?.setSystemDns(dns)
            // set default dns server for the tunnel if none is set
            if (isDefaultDnsNone()) {
                val dnsCsv = dns.joinToString(",")
                vpnAdapter?.addDefaultTransport(dnsCsv)
            }
            // special case, check if preferred is available, if not add again
            val isPrefOk = vpnAdapter?.getDnsStatus(Backend.Preferred)
            if (isPrefOk == null) {
                Logger.i(LOG_TAG_VPN, "preferred dns is not set, set it again")
                vpnAdapter?.addTransport()
            } else {
                Logger.i(LOG_TAG_VPN, "preferred dns is already set, no need to set again")
            }
        }
    }

    private fun areDnsEqual(prevDns: Set<InetAddress>?, dnsServers: Set<InetAddress>): Boolean {
        if (prevDns == null) {
            return false
        }

        if (prevDns.size != dnsServers.size) {
            return false
        }

        // ref: kotlinlang.org/docs/equality.html#structural-equality
        return dnsServers == prevDns
    }

    private fun isDefaultDnsNone(): Boolean {
        // if none is set then the url will either be empty or will not be one of the default dns
        return persistentState.defaultDnsUrl.isEmpty() ||
                !Constants.DEFAULT_DNS_LIST.any { it.url == persistentState.defaultDnsUrl }
    }

    private fun handleVpnLockdownStateAsync() {
        if (!syncLockdownState()) return

        Logger.i(LOG_TAG_VPN, "vpn lockdown mode change, restarting")
        io("lockdownSync") {
            restartVpnWithNewAppConfig(reason = "lockdownEnabled? ${isLockDownPrevious.get()}")
            vpnAdapter?.notifyLoopback()
        }
    }

    private fun syncLockdownState(): Boolean {
        if (!isAtleastQ()) return false

        // cannot set the lockdown status while the vpn is being created, it will return false
        // until the vpn is created. so the sync will be done after the vpn is created
        // when the first flow call is made.
        val prev = isLockDownPrevious.get()
        if (isLockdownEnabled == prev) return false

        return isLockDownPrevious.compareAndSet(prev, isLockdownEnabled)
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
                    Intent(this, AppLockActivity::class.java),
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
        } catch (
            ignored: CancellationException
        ) {
        } catch (ignored: Exception) {
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
        Logger.i(LOG_TAG_VPN, "App paused")
    }

    fun resumeApp() {
        stopPauseTimer()
        handleVpnServiceOnAppStateChange()
        Logger.i(LOG_TAG_VPN, "App resumed")
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
            return try {
                HostName(IPAddressString(format).address, port).toString()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "err make lan ip: ${e.message}", e)
                ""
            }
        }
    }

    // var to update the controller with the protocol set for the vpn
    private var builderRoutes: Pair<Boolean, Boolean> = Pair(false, false)

    private fun determineRoutes(n: Networks): Pair<Boolean, Boolean> {
        var has6 = route6(n)
        var has4 = route4(n)

        val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
        // in auto mode, assume v4 route is available if only v6 route is available, which is true
        // for scenarios like 464Xlat and other 4to6 translation mechanisms
        if (ROUTE4IN6 && isAuto && (has6 && !has4)) {
            Logger.w(LOG_TAG_VPN, "Adding v4 route in v6-only network")
            has4 = true
        }

        if (!has4 && !has6 && !n.overlayNws.failOpen) {
            // When overlay networks has v6 routes but active network has v4 routes
            // both has4 and has6 will be false and fail-open may open up BOTH routes
            // What's desirable is for the active network route to take precedence, that is,
            // to only add v4 route in case of a mismatch. Failing open will falsely make
            // apps think the underlying active network is dual-stack when it is not causing
            // all sorts of delays (due to happy eyeballs).
            // fixme: this code doesn't seem to be doing anything, because route4 and route6
            // will always be false
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
            val s = StringBuilder()

            val mtu = mtu() // get mtu from the underlyingnetworks
            var builder: Builder = newBuilder().setSession("Rethink").setMtu(mtu)

            val (has4, has6) = determineRoutes(networks)

            // TODO: do we need to still exclude the routes in case of noRoutes?
            val noRoutes = !has4 && !has6

            builderRoutes = Pair(has4, has6)
            val dnsMode = appConfig.getBraveMode().isDnsActive()
            val firewallMode = appConfig.getBraveMode().isFirewallActive()

            // setup the gateway addr
            if (has4 || noRoutes) {
                builder = addIfAddress4(builder)
            }
            if (has6 || noRoutes) {
                builder = addIfAddress6(builder)
            }

            if (dnsMode) {
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
            if (firewallMode) {
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
                if (has4 && !noRoutes) {
                    builder.allowFamily(AF_INET)
                }
                if (has6 && !noRoutes) {
                    builder.allowFamily(AF_INET6)
                }
            }

            // nw engine expects the fd to be non-blocking
            builder.setBlocking(false)

            Logger.i(
                LOG_TAG_VPN,
                "$TAG; establish vpn, mtu: $mtu, has4: $has4, has6: $has6, noRoutes: $noRoutes, dnsMode? $dnsMode, firewallMode? $firewallMode"
            )

            s.append("mtu: $mtu\n   has4: $has4\n   has6: $has6\n   noRoutes: $noRoutes\n   dnsMode? $dnsMode\n   firewallMode? $firewallMode\n")
            builderStats = s.toString()

            return builder.establish()
        } catch (e: Exception) {
            Logger.crash(LOG_TAG_VPN, e.message ?: "err establishVpn", e)
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
        // TODO: as of now, vpn lockdown mode is not handled, check if this is required
        if (persistentState.privateIps) {
            Logger.i(LOG_TAG_VPN, "addRoute6: privateIps is true, adding routes")
            // exclude LAN traffic, add only unicast routes
            // add only unicast routes
            // range 0000:0000:0000:0000:0000:0000:0000:0000-
            // 0000:0000:0000:0000:ffff:ffff:ffff:ffff
            // fixme: see if the ranges overlap with the default route
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
        // TODO: as of now, vpn lockdown mode is not handled, check if this is required
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

    private fun addIfAddress4(b: Builder): Builder {
        b.addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
        return b
    }

    private fun addIfAddress6(b: Builder): Builder {
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

    private fun <T> go2kt(co: CoFactory<T>, f: suspend() -> T): T = runBlocking {
        // runBlocking blocks the current thread until all coroutines within it are complete
        // an call a suspending function from a non-suspending context and obtain the result.
        return@runBlocking co.tryDispatch(f)
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

    override fun onQuery(uidStr: String, fqdn: String?, qtype: Long): DNSOpts = go2kt(dnsQueryDispatcher) {
        // TODO: if uid is received, then make sure Rethink uid always returns Default as transport
        var uid: Int = INVALID_UID
        try {
            uid = if (uidStr == Backend.UidSelf || uidStr == rethinkUid.toString()) {
                rethinkUid
            } else if (uidStr == Backend.UidSystem) {
                AndroidUidConfig.SYSTEM.uid // 1000
            } else {
                uidStr.toInt()
            }
        } catch (ignored: NumberFormatException) {
            Logger.w(LOG_TAG_VPN, "onQuery: invalid uid: $uidStr, using default $uid")
        }

        // queryType: see ResourceRecordTypes.kt
        logd("onQuery: rcvd uid: $uid query: $fqdn, qtype: $qtype")
        if (fqdn == null) {
            Logger.e(LOG_TAG_VPN, "onQuery: fqdn is null")
            // return block all, as it is not expected to reach here
            val res = makeNsOpts(uid,  Pair(Backend.BlockAll, ""), fqdn ?: "")
            return@go2kt res
        }

        val appMode = appConfig.getBraveMode()
        if (appMode.isDnsMode()) {
            // always use INVALID_UID for Dns only mode
            val res = getTransportIdForDnsMode(INVALID_UID, fqdn)
            logd("onQuery (Dns):$fqdn, dnsx: $res")
            return@go2kt res
        }

        if (appMode.isDnsFirewallMode()) {
            val res = getTransportIdForDnsFirewallMode(uid, fqdn)
            logd("onQuery (Dns+Firewall):$fqdn, dnsx: $res")
            return@go2kt res
        }

        val res = makeNsOpts(uid, Pair(appendDnsCacheIfNeeded(Backend.Preferred), ""), fqdn) // should not reach here
        Logger.e(LOG_TAG_VPN, "onQuery: unknown mode ${appMode}, $fqdn, returning $res")
        return@go2kt res
    }

    // function to decide which transport id to return on Dns only mode
    private suspend fun getTransportIdForDnsMode(uid: Int, fqdn: String): DNSOpts {
        // useFixedTransport is false in Dns only mode
        val tid = determineDnsTransportIdForDnsMode()

        if (uid == rethinkUid) {
            // no need to check for domain rules for rethink uid, can be added in the future
            return makeNsOpts(uid, tid, fqdn)
        }

        // check for global domain rules
        when (DomainRulesManager.getDomainRule(fqdn, UID_EVERYBODY)) {
            DomainRulesManager.Status.TRUST -> return makeNsOpts(uid, Pair(Backend.BlockFree, ""), fqdn, true)
            DomainRulesManager.Status.BLOCK -> return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false)
            else -> {} // no-op, fall-through;
        }

        return makeNsOpts(uid, tid, fqdn)
    }

    // function to decide which transport id to return on DnsFirewall mode
    private suspend fun getTransportIdForDnsFirewallMode(uid: Int, fqdn: String): DNSOpts {
        val splitDns = persistentState.splitDns && WireguardManager.isAdvancedWgActive()
        val tid = determineDnsTransportIdForDFMode(uid, fqdn, splitDns)
        val forceBypassLocalBlocklists = isAppPaused() && VpnController.isVpnLockdown()

        if (uid == rethinkUid) {
            // no need to check for domain rules for rethink uid, can be added in the future
            // tid should be always set to default, see #determineDnsTransportIdForDFMode
            Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: rethink's uid use $tid for $fqdn")
            return makeNsOpts(uid, tid, fqdn, true)
        }
        if (forceBypassLocalBlocklists) {
            // if the app is paused and vpn is in lockdown mode, then bypass the local blocklists
            // no rules should be applied and user-set dns should be ignored, use system dns
            // tid will be System, check #determineDnsTransportIdForDFMode
            Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: bypassing local blocklists for $fqdn, $uid")
            return makeNsOpts(uid, tid, fqdn, true)
        }
        if (persistentState.bypassBlockInDns) {
            // if bypassBlockInDns is enabled, bypass local blocklist as true, so that the domain
            // is resolved and the decision is made by in flow()
            Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: bypass block in onquery $fqdn, $uid")
            return makeNsOpts(uid, transportIdsAlg(tid, splitDns), fqdn, true)
        }

        // either the android version is less than Q or the OEM preferred not to set uid
        // in the DNS.uid (AID_DNS) field, so set the uid as INVALID_UID from flow()
        if (uid == INVALID_UID) {
            return if (FirewallManager.isAnyAppBypassesDns()) {
                // if any app is bypassed (dns + firewall), then set bypass local blocklist as true
                // so that the domain is resolved and the decision is made by in flow()
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: no uid, some app is bypassed $fqdn")
                makeNsOpts(uid, transportIdsAlg(tid, splitDns), fqdn, true)
            } else if (DomainRulesManager.isDomainTrusted(fqdn)) {
                // set isBlockFree as true so that the decision is made by in flow() function
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: no uid, univ domain trusted $fqdn")
                makeNsOpts(uid, transportIdsAlg(tid, splitDns), fqdn, true)
            } else if (getDomainRule(fqdn, UID_EVERYBODY) == DomainRulesManager.Status.BLOCK) {
                // if the domain is blocked by global rule then set as block all (overriding the tid)
                // app-wise trust is already checked above
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: no uid, univ domain blocked $fqdn")
                makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
            } else {
                // no global rule, no app-wise trust, return the tid as it is
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: no uid, no global rule, use $tid for $fqdn")
                makeNsOpts(uid, tid, fqdn)
            }
        } else {
            val connectionStatus = FirewallManager.connectionStatus(uid)
            if (connectionStatus.blocked()) {
                // if the app is blocked by both wifi and mobile data, then the block the request
                // block wifi/metered decision can be made by in flow() function
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery, $uid is blocked, $fqdn")
                return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
            }

            val appStatus = FirewallManager.appStatus(uid)
            if (appStatus.bypassDnsFirewall()) {
                // in case of bypass dns, bypass the local blocklists and set only block-free
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery, $uid bypasses dns+firewall, $fqdn")
                return makeNsOpts(uid, Pair(Backend.BlockFree, ""), fqdn, true)
            }

            val appDomainRule =  getDomainRule(fqdn, uid)
            when (appDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG; onQuery, $uid, domain trusted: $fqdn")
                    return makeNsOpts(uid, Pair(Backend.BlockFree, ""), fqdn, true)
                }
                DomainRulesManager.Status.BLOCK -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG; onQuery, $uid, domain blocked: $fqdn")
                    return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
                }
                DomainRulesManager.Status.NONE -> {
                    // no-op, fall through, check for global rules
                }
            }

            val globalDomainRule = getDomainRule(fqdn, UID_EVERYBODY)
            when (globalDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG; onQuery, $uid, univ domain trusted: $fqdn")
                    return makeNsOpts(uid, Pair(Backend.BlockFree, ""), fqdn, true)
                }
                DomainRulesManager.Status.BLOCK -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG; onQuery, $uid, univ domain blocked: $fqdn")
                    return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
                }
                DomainRulesManager.Status.NONE -> {
                    // no-op, fall through, return the tid as it is
                }
            }

            Logger.vv(LOG_TAG_VPN, "$TAG; onQuery, $uid, no rules, use $tid for $fqdn")
            return makeNsOpts(uid, tid, fqdn)
        }

        /*return if (forceBypassLocalBlocklists) {
            // if the app is paused and vpn is in lockdown mode, then bypass the local blocklists
            makeNsOpts(uid, tid, fqdn, true)
        } else if (FirewallManager.isAnyAppBypassesDns() || (persistentState.bypassBlockInDns && !persistentState.splitDns)) {
            // if any app is bypassed (dns + firewall) or bypassBlockInDns is enabled, then
            // set bypass local blocklist as true, so that the domain is resolved and the decision
            // is made by in flow()
            makeNsOpts(uid, transportIdsAlg(tid, splitDns), fqdn, true)
        } else if (DomainRulesManager.isDomainTrusted(fqdn)) {
            // set isBlockFree as true so that the decision is made by in flow() function
            makeNsOpts(uid, transportIdsAlg(tid, splitDns), fqdn,true)
        } else if (
            DomainRulesManager.status(fqdn, UID_EVERYBODY) == DomainRulesManager.Status.BLOCK
        ) {
            // if the domain is blocked by global rule then set as block all (overriding the tid)
            // app-wise trust is already checked above
            makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
        } else {
            // no global rule, no app-wise trust, return the tid as it is
            makeNsOpts(uid, tid, fqdn)
        }*/
    }

    private suspend fun determineDnsTransportIdForDnsMode(): Pair<String, String> {
        val tid = if (appConfig.isSystemDns() || (isAppPaused() && VpnController.isVpnLockdown())) {
            // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
            // as if the apps are excluded from vpn
            Backend.System
        } else {
            Backend.Preferred
        }
        return Pair(appendDnsCacheIfNeeded(tid), "")
    }

    private suspend fun determineDnsTransportIdForDFMode(uid: Int, domain: String, splitDns: Boolean): Pair<String, String> {
        val defaultTid = if (appConfig.isSystemDns() || (isAppPaused() && VpnController.isVpnLockdown())) {
            // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
            // as if the apps are excluded from vpn
            Backend.System
        } else {
            Backend.Preferred
        }

        if (uid == rethinkUid) {
            // set default transport to rethink's uid, as no rules or proxy will be applied
            val id = if (RpnProxyManager.isRpnActive()) {
                Backend.Plus
            } else {
                Backend.Default
            }
            Logger.d(LOG_TAG_VPN, "(onQuery)rethink's uid using $id")
            return Pair(id, "")
        }

        if (uid == INVALID_UID) {
            val oneWgId = WireguardManager.getOneWireGuardProxyId()
            val tid = if (oneWgId != null) {
                ID_WG_BASE + oneWgId
            } else {
                appendDnsCacheIfNeeded(defaultTid)
            }
            return if (splitDns) {
                // in case of split dns, append Fixed to the tid when there is no uid
                Pair(tid, Backend.Fixed)
            } else {
                Pair(tid, "")
            }
        } else {
            if (!splitDns) {
                Logger.d(LOG_TAG_VPN, "(onQuery)no split dns, using $defaultTid")
                return Pair(appendDnsCacheIfNeeded(defaultTid), "")
            }
            if (FirewallManager.isAppExcludedFromProxy(uid)) {
                return Pair(appendDnsCacheIfNeeded(defaultTid), "")
            }
            // take only the active nw into account as we do not know the dns server ip
            // which the domain is going to be resolved
            val activeNwMetered = isActiveIfaceCellular()
            // only when there is an uid, we need to calculate wireguard ids
            val ids = WireguardManager.getAllPossibleConfigIdsForApp(uid, ip="", port = 0, domain, activeNwMetered)
            return if (ids.isNotEmpty()) {
                Logger.d(LOG_TAG_VPN, "(onQuery)wg ids($ids) found for uid: $uid")
                Pair(ids.joinToString(","), "")
            } else {
                Logger.d(LOG_TAG_VPN, "(onQuery)no wg ids found for uid: $uid, using $defaultTid")
                Pair(appendDnsCacheIfNeeded(defaultTid), "")
            }
        }
    }

    private suspend fun makeNsOpts(
        uid: Int,
        tid: Pair<String, String>,
        domain: String,
        bypassLocalBlocklists: Boolean = false
    ): DNSOpts {
        val opts = DNSOpts()
        opts.ipcsv = "" // as of now, no suggested ips
        opts.tidcsv = tid.first
        opts.tidseccsv = tid.second

        if (uid == rethinkUid) {
            // for rethink no need to set the proxyId, always set base
            opts.pidcsv = Backend.Base
        } else {
            // only when there is no wireguard as tid, we need to calculate proxyIds
            // to avoid undesired hop behavior
            if (tid.first.contains(ID_WG_BASE) || tid.second.contains(ID_WG_BASE)) {
                opts.pidcsv = Backend.Base
            } else {
                opts.pidcsv = proxyIdForOnQuery(uid, domain)
            }
        }
        opts.noblock = bypassLocalBlocklists
        Logger.vv(LOG_TAG_VPN, "onQuery: uid: $uid, domain: $domain, tid: ${opts.tidcsv}, sec: ${opts.tidseccsv}, pid: ${opts.pidcsv}, noblock: ${opts.noblock}")
        return opts
    }

    private fun transportIdsAlg(preferredId: Pair<String, String>, splitDns: Boolean): Pair<String, String> {
        if (splitDns) {
            // case when splitDns is true, then tid will already be appended with Fixed
            // so no need to append BlockFree again
            return preferredId // ex: CT+Preferred,Fixed
        }
        // case when userPreferredId is Alg, then return BlockFree + tid
        // tid can be System / ProxyId / Preferred
        return if (isRethinkDnsEnabled()) {
            val tr1 = appendDnsCacheIfNeeded(Backend.BlockFree)
            val tr2 = preferredId.first // ideally, it should be Preferred
            val p = Pair(tr1, tr2)
            p
        } else {
            preferredId
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

    private suspend fun proxyIdForOnQuery(uid: Int, domain: String): String {
        // in case of rinr mode, use only base even if auto is enabled
        // use auto only in non-rinr mode and if plus is subscribed
        val defaultProxy = when {
            persistentState.routeRethinkInRethink -> Backend.Base
            else -> Backend.Base
        }

        // proxies are used only in dns-firewall mode
        if (!appConfig.getBraveMode().isDnsFirewallMode()) {
            Logger.d(LOG_TAG_VPN, "(onQuery-pid)not in dns-firewall mode")
            return defaultProxy
        }

        // user setting to disable proxy dns
        if (!persistentState.proxyDns) {
            Logger.d(LOG_TAG_VPN, "(onQuery-pid)proxyDns is disabled, return $defaultProxy")
            return defaultProxy
        }

        if (FirewallManager.isAppExcludedFromProxy(uid)) {
            logd("(onQuery-pid) app excluded from proxy, return $defaultProxy")
            return defaultProxy
        }

        if (appConfig.isDnsProxyActive()) {
            val endpoint = appConfig.getSelectedDnsProxyDetails()
            val app = endpoint?.proxyAppName
            if (!app.isNullOrEmpty()) {
                Logger.d(LOG_TAG_VPN, "(onQuery-pid)proxy app: $app, return $defaultProxy")
                return defaultProxy
            }
        }

        return if (appConfig.isCustomSocks5Enabled()) {
            Logger.d(
                LOG_TAG_VPN,
                "(onQuery-pid)customSocks5 enabled, return ${ProxyManager.ID_S5_BASE},${defaultProxy}"
            )
            "${ProxyManager.ID_S5_BASE},${defaultProxy}"
        } else if (appConfig.isCustomHttpProxyEnabled()) {
            Logger.d(
                LOG_TAG_VPN,
                "(onQuery-pid)customHttp enabled, return ${ProxyManager.ID_HTTP_BASE},${defaultProxy}"
            )
            "${ProxyManager.ID_HTTP_BASE},${defaultProxy}"
        } else if (WireguardManager.oneWireGuardEnabled()) {
            val id = WireguardManager.getOneWireGuardProxyId()
            if (id == null) {
                Logger.e(
                    LOG_TAG_VPN,
                    "(onQuery-pid)No one-wg id found but one-wg enabled, return $defaultProxy"
                )
                defaultProxy // this should not happen
            } else {
                // include defaultProxy as well in case if the canRoute fails for one-wireguard
                val oid = ID_WG_BASE + id
                "$oid,$defaultProxy"
            }
        } else {
            // take only the active nw into account as we do not know the dns server ip
            // which the domain is going to be resolved
            val activeNwMetered = isActiveIfaceCellular()
            // if the enabled wireguard is catchall-wireguard, then return wireguard id
            val ids = WireguardManager.getAllPossibleConfigIdsForApp(
                uid,
                ip = "",
                port = 0,
                domain,
                activeNwMetered,
                defaultProxy
            )
            if (ids.isNotEmpty()) {
                Logger.d(LOG_TAG_VPN, "(onQuery-pid)wg ids($ids) found for uid: $uid")
                ids.joinToString(",")
            } else {
                if (RpnProxyManager.isRpnActive()) {
                    Logger.d(LOG_TAG_VPN, "(onQuery-pid)No proxy, add auto for uid: $uid")
                    getRpnIds().ifEmpty { Backend.Base }
                } else {
                    Logger.d(LOG_TAG_VPN, "(onQuery-pid)No proxy, return $defaultProxy, uid: $uid")
                    defaultProxy
                }
            }
        }
    }

    private fun getRpnIds(): String {
        // not needed as caller is already checking for rpn active
        if (!RpnProxyManager.isRpnActive()) return ""

        val mode = RpnProxyManager.rpnMode()
        val ids = mode.value
        Logger.vv(LOG_TAG_VPN, "getRpnIds; state:${RpnProxyManager.rpnState().name}, mode: ${mode.name}, ids: $ids")
        return ids
    }

    override fun onResponse(summary: backend.DNSSummary?) {
        if (summary == null) {
            Logger.i(LOG_TAG_VPN, "received null summary for dns")
            return
        }
        logd("onResponse: $summary")
        if (!DEBUG) {
            if (summary.id.contains(Backend.Fixed)) {
                return
            }
        }
        if (!Logger.LoggerType.fromId(persistentState.goLoggerLevel.toInt())
                .isLessThan(Logger.LoggerType.INFO)
        ) { // skip cached response logging for info and above
            if (summary.cached) {
                logd("onResponse: cached response, not logging")
                return
            }
        }
        netLogTracker.processDnsLog(summary, rethinkUid)
        setRegionLiveDataIfRequired(summary)
    }

    private fun setRegionLiveDataIfRequired(summary: backend.DNSSummary) {
        if (summary.region == null) {
            return
        }

        val region = summary.region
        val regionLiveData = regionLiveData
        if (regionLiveData.value != region) {
            regionLiveData.postValue(region)
        }
    }

    fun getRegionLiveData(): LiveData<String> {
        return regionLiveData
    }

    override fun onProxiesStopped() {
        // clear the proxy handshake times
        logd("onProxiesStopped; clear the handshake times")
    }

    override fun onProxyAdded(id: String) {
        if (!id.contains(ID_WG_BASE)) {
            // only wireguard proxies are considered for overlay network
            logd("onProxyAdded: no-op as it is not wireguard proxy, added $id")
            return
        }
        // new proxy added, refresh overlay network pair
        io("onProxyAdded") {
            val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
            logd("onProxyAdded for proxy $id: $nw")
            onOverlayNetworkChanged(nw ?: OverlayNetworks())
        }
    }

    override fun onProxyRemoved(id: String) {
        if (!id.contains(ID_WG_BASE)) {
            // only wireguard proxies are considered for overlay network
            logd("onProxyRemoved: proxy removed $id, not wireguard")
            return
        }
        // proxy removed, refresh overlay network pair
        io("onProxyRemoved") {
            val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
            logd("onProxyRemoved for proxy $id: $nw")
            onOverlayNetworkChanged(nw ?: OverlayNetworks())
        }
    }

    override fun onProxyStopped(id: String?) {
        Logger.v(LOG_TAG_VPN, "onProxyStopped: $id")
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

    override fun onSvcComplete(p0: ServerSummary) {
        // no-op
    }

    override fun svcRoute(
        sid: String,
        pid: String,
        network: String,
        sipport: String,
        dipport: String
    ): Tab {
        // no-op
        return Tab()
    }

    // should never run in seperate go-routine as msg is a bytearray in go and will be
    // released immediately
    override fun log(level: Int, msg: String) {
        if (msg.isEmpty()) return

        val l = Logger.LoggerType.fromId(level)
        if (l.stacktrace()) {
            // disable crash logging for now
            if (false) Logger.crash(LOG_GO_LOGGER, msg) // write to in-mem db
            EnhancedBugReport.writeLogsToFile(this, msg)
        } else if (l.user()) {
            showNwEngineNotification(msg)
        } else {
            Logger.goLog(msg, l)
        }
    }

    private fun showNwEngineNotification(msg: String) {
        if (msg.isEmpty()) {
            return
        }

        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, AppLockActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )
        val builder =
            NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(msg)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
        builder.color = ContextCompat.getColor(this, getAccentColor(persistentState.theme))
        notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        Logger.w(LOG_TAG_VPN, "nw eng notification: $msg")
    }

    override fun onSocketClosed(s: SocketSummary?) {
        if (s == null) {
            Logger.i(LOG_TAG_VPN, "received null summary for socket")
            return
        }

        // TODO: convert the duration obj to long, this is work around
        val durationSec = (s.duration / 1000).toInt()

        // set the flag as null, will calculate the flag based on the target
        val connectionSummary =
            ConnectionSummary(
                s.uid,
                s.pid,
                s.rpid,
                s.id,
                s.rx,
                s.tx,
                durationSec,
                s.rtt, // updated in synack var
                s.msg,
                s.target,
                null
            )
        logd("onSocketClosed: $s")

        if (s.uid.isNullOrEmpty()) {
            Logger.e(LOG_TAG_VPN, "onSocketClosed: missing uid, summary: $s")
            return
        }

        if (connectionSummary.connId.isEmpty()) {
            // in case of empty connId, insert a new entry
            val cid = "debug-" + Utilities.getRandomString(8)
            val uid = FirewallManager.appId(s.uid.toInt(), isPrimaryUser())
            val cm = createConnTrackerMetaData(
                    uid,
                    uid,
                    s.target,
                    0,
                    s.target,
                    0,
                    0,
                    proxyDetails = "", // set later
                    "",
                    s.target,
                    cid,
                    ConnectionTracker.ConnType.UNMETERED
                )
            netLogTracker.writeIpLog(cm)
            return
        }

        try {
            if (s.uid == Backend.UidSelf || s.uid == rethinkUid.toString()) {
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

    override fun preflow(
        protocol: Int,
        uid: Int,
        src: String,
        dst: String,
        domains: String
    ): PreMark = go2kt(preflowDispatcher) {
        val first = HostName(src)
        val second = HostName(dst)

        val srcIp = if (first.asAddress() == null) "" else first.asAddress().toString()
        val srcPort = first.port ?: 0
        val dstIp = if (second.asAddress() == null) "" else second.asAddress().toString()
        val dstPort = second.port ?: 0

        val newUid = if (uid == INVALID_UID) { // fetch uid only if it is invalid
            getUid(
                uid,
                protocol,
                srcIp,
                srcPort,
                dstIp,
                dstPort,
                ConnectionTracer.CallerSrc.PREFLOW
            )
        } else {
            uid
        }
        Logger.d(
            LOG_TAG_VPN,
            "preflow: $newUid, $srcIp, $srcPort, $dstIp, $dstPort, $domains"
        )

        val p = PreMark()
        p.uid = newUid.toString()
        Logger.i(LOG_TAG_VPN, "preflow: returning ${p.uid} for src: $srcIp:$srcPort, dst: $dstIp:$dstPort, $domains")
        return@go2kt p
    }

    override fun flow(
        protocol: Int,
        _uid: Int,
        src: String,
        dest: String,
        realIps: String,
        d: String,
        possibleDomains: String,
        blocklists: String
    ): Mark = go2kt(flowDispatcher) {
        logd("flow: $_uid, $src, $dest, $realIps, $d, $blocklists")
        handleVpnLockdownStateAsync()

        // in case of double loopback, all traffic will be part of rinr instead of just rethink's
        // own traffic. flip the doubleLoopback flag to true if we need that behavior
        val doubleLoopback = false

        val first = HostName(src)
        val second = HostName(dest)

        val srcIp = if (first.asAddress() == null) "" else first.asAddress().toString()
        val srcPort = first.port ?: 0
        val dstIp = if (second.asAddress() == null) "" else second.asAddress().toString()
        val dstPort = second.port ?: 0

        val ips = realIps.split(",")
        // take the first non-unspecified ip as the real destination ip
        val fip = ips.firstOrNull { !isUnspecifiedIp(it.trim()) }?.trim()
        // use realIps; as of now, netstack uses the first ip
        // TODO: apply firewall rules on all real ips
        val realDestIp =
            if (fip.isNullOrEmpty()) {
                dstIp
            } else {
                fip
            }
        var uid = getUid(
            _uid,
            protocol,
            srcIp,
            srcPort,
            dstIp,
            dstPort,
            ConnectionTracer.CallerSrc.FLOW
        )
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

        // always block, since the vpn tunnel doesn't serve dns-over-tls
        if (trapVpnPrivateDns) {
            logd("flow: dns-over-tls, returning Ipn.Block, $uid")
            cm.isBlocked = true
            cm.blockedByRule = FirewallRuleset.RULE14.id
            return@go2kt persistAndConstructFlowResponse(cm, Backend.Block, connId, uid)
        }

        // app is considered as spl when it is selected to forward dns proxy, socks5 or http proxy
        val isSplApp = isSpecialApp(uid)

        val isRethink = uid == rethinkUid
        if (isRethink) {
            // case when uid is rethink, return Ipn.Base
            logd(
                "flow: Ipn.Exit for rethink, $uid, $packageName, $srcIp, $srcPort, $realDestIp, $dstPort, $possibleDomains"
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
            // and expect Android's netd via the network engine to re-route as appropriate.
            val proxy =
                if (trapVpnDns) {
                    // on Android Q and below, the uid for local dns(to VPN's dns servers)
                    // is always DNS.uid (AID_DNS), ie., the true src for the dns request
                    // is not known. override uid with INVALID_UID to force a preflow() call
                    // on Android P and above, the uid for private dns is also DNS.uid (AID_DNS)
                    // which we should not override and let it out as it is
                    if (uid == AndroidUidConfig.DNS.uid) {
                        uid = INVALID_UID
                    }
                    Backend.Base
                    // do not add the trackedCids for dns entries as there will not be any
                    // onSocketClosed event for dns entries
                } else {
                    // add to trackedCids, so that the connection can be removed from the list when the
                    // connection is closed (onSocketClosed), use: ui to show the active connections
                    val key = CidKey(cm.connId, uid)
                    trackedCids.add(key)
                    Backend.Exit
                }

            return@go2kt persistAndConstructFlowResponse(cm, proxy, connId, uid, isRethink)
        }

        if (trapVpnDns) {
            // see the comment above for the reasoning #trapVpnDns
            if (uid == AndroidUidConfig.DNS.uid) {
                uid = INVALID_UID
            }
            // android R+, uid will be there for dns request as well
            logd("flow: dns-request, returning ${Backend.Base}, $uid, $connId")
            return@go2kt persistAndConstructFlowResponse(null, Backend.Base, connId, uid)
        }

        processFirewallRequest(cm, anyRealIpBlocked, blocklists, isSplApp)

        if (cm.isBlocked) {
            // return Ipn.Block, no need to check for other rules
            logd("flow: received rule: block, returning Ipn.Block, $connId, $uid")
            return@go2kt persistAndConstructFlowResponse(cm, Backend.Block, connId, uid)
        }

        // add to trackedCids, so that the connection can be removed from the list when the
        // connection is closed (onSocketClosed), use: ui to show the active connections
        val key = CidKey(cm.connId, uid)
        trackedCids.add(key)

        return@go2kt determineProxyDetails(cm, doubleLoopback)
    }

    override fun inflow(protocol: Int, recvdUid: Int, src: String?, dst: String?): Mark =
        go2kt(inflowDispatcher) {
            val first = HostName(src)
            val second = HostName(dst)

            val srcIp = if (first.asAddress() == null) "" else first.asAddress().toString()
            val srcPort = first.port ?: 0
            val dstIp = if (second.asAddress() == null) "" else second.asAddress().toString()
            val dstPort = second.port ?: 0

            var uid = getUid(
                recvdUid,
                protocol,
                srcIp,
                srcPort,
                dstIp,
                dstPort,
                ConnectionTracer.CallerSrc.INFLOW
            )
            uid = FirewallManager.appId(recvdUid, isPrimaryUser())
            val userId = FirewallManager.userId(uid)

            logd("inflow: $uid($recvdUid), $srcIp, $srcPort, $dstIp, $dstPort")

            val connId = Utilities.getRandomString(8)

            val connType =
                if (isConnectionMetered(dstIp)) {
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
                    dstIp,
                    dstPort,
                    protocol,
                    proxyDetails = "",
                    "",
                    "",
                    connId,
                    connType
                )

            processFirewallRequest(cm, false, "")

            if (cm.isBlocked) {
                // return Ipn.Block, no need to check for other rules
                logd("inflow: received rule: block, returning Ipn.Block, $connId, $uid")
                return@go2kt persistAndConstructFlowResponse(cm, Backend.Block, connId, uid)
            }

            // add to trackedCids, so that the connection can be removed from the list when the
            // connection is closed (onSocketClosed), use: ui to show the active connections
            val key = CidKey(cm.connId, uid)
            trackedCids.add(key)

            logd("inflow: determine proxy and other dtls for $connId, $uid")

            // the proxy id (other than block) will be ignored by the go code, so use
            // Backend.Ingress as a placeholder
            return@go2kt persistAndConstructFlowResponse(cm, Backend.Ingress, connId, uid)
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
        doubleLoopback: Boolean
    ): Mark {
        val baseOrExit =
            if (doubleLoopback) {
                Backend.Base
            } else if (connTracker.blockedByRule == FirewallRuleset.RULE9.id) {
                // special case: proxied dns traffic should not Backed.Exit as is. Only traffic
                // marked with Backend.Base will be handled (proxied) by vpnAdapter's dns-transport
                Backend.Base
            } else {
                Backend.Exit
            }
        val connId = connTracker.connId
        val uid = connTracker.uid

        if (FirewallManager.isAppExcludedFromProxy(uid)) {
            logd("flow/inflow: app is excluded from proxy, returning Ipn.Base, $connId, $uid")
            connTracker.blockedByRule = FirewallRuleset.RULE15.id
            return persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
        }
        // used to check wg proxy
        val isConnCellular = isIfaceCellular(connTracker.destIP)

        // add baseOrExit in the end of the list if needed (not true for lockdown)
        val wgs = WireguardManager.getAllPossibleConfigIdsForApp(uid, connTracker.destIP, connTracker.destPort, connTracker.query ?: "", isConnCellular, baseOrExit)

        if (wgs.isNotEmpty()) {
            // if canRoute fails for all configs, then the connection will be sent to
            // baseOrExit if available, else it will be blocked
            val ids = wgs.joinToString(",")
            logd("flow/inflow: wg is active, returning $ids, $connId, $uid")
            return persistAndConstructFlowResponse(connTracker, ids, connId, uid)
        } else {
            Logger.vv(LOG_TAG_VPN, "flow/inflow: no wireguard proxy, $baseOrExit, $connId, $uid")
        }

        // carry out this check after wireguard, because wireguard has catchAll and lockdown
        // if no proxy or dns proxy is enabled, return baseOrExit
        if (!appConfig.isProxyEnabled() && !appConfig.isDnsProxyActive()) {
            logd("flow/inflow: no proxy/dnsproxy enabled, returning Ipn.Base, $connId, $uid")
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
                logd("flow/inflow: orbot exit for $packageName, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }

            val activeId = ProxyManager.getProxyIdForApp(uid)
            if (!activeId.contains(ProxyManager.ID_ORBOT_BASE)) {
                Logger.e(LOG_TAG_VPN, "flow/inflow: orbot proxy is enabled but app is not included")
                // pass-through
            } else {
                logd("flow/inflow: orbot proxy for $uid, $connId")
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
            logd("flow/inflow: socks5 proxy is enabled, $packageName, ${endpoint.proxyAppName}")
            // do not block the app if the app is set to forward the traffic via socks5 proxy
            if (endpoint.proxyAppName == packageName) {
                logd("flow/inflow: socks5 exit for $packageName, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }

            logd("flow/inflow: socks5 proxy for $connId, $uid")
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
                logd("flow/inflow: http exit for $packageName, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }

            logd("flow/inflow: http proxy for $connId, $uid")
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
                logd("flow/inflow: dns proxy enabled for $packageName, return exit, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, Backend.Exit, connId, uid)
            }
        }

        logd("flow/inflow: no proxies, $baseOrExit, $connId, $uid")
        return persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
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

    suspend fun addWireGuardProxy(id: String) {
        logd("add wg from tunnel: $id")
        vpnAdapter?.addWgProxy(id)
    }

    fun refreshProxies() {
        logd("refresh wg config")
        io("refreshWg") { vpnAdapter?.refreshProxies() }
    }

    suspend fun getDnsStatus(id: String): Long? {
        return vpnAdapter?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return vpnAdapter?.getRDNS(type)
    }

    fun goBuildVersion(full: Boolean): String {
        return vpnAdapter?.goBuildVersion(full) ?: ""
    }

    private fun persistAndConstructFlowResponse(
        cm: ConnTrackerMetaData?,
        proxyId: String,
        connId: String,
        uid: Int,
        isRethink: Boolean = false
    ): Mark {
        // persist ConnTrackerMetaData
        if (cm != null) {
            // if proxyId has multiple ids, then use the first id as the proxyId
            cm.proxyDetails = proxyId.split(",").first()
            // assign the proxy details to cm after the decision is made
            if (ProxyManager.isIpnProxy(proxyId) && !cm.isBlocked) {
                cm.blockedByRule = FirewallRuleset.RULE12.id
            }
            if (isRethink) {
                netLogTracker.writeRethinkLog(cm)
            } else {
                netLogTracker.writeIpLog(cm)
            }
            logd("flow/inflow: connTracker: $cm")
        }

        val mark = Mark()
        // fixme: adding for testing, remove it later
        val iid = if(proxyId == Backend.Exit && RpnProxyManager.isRpnActive() && !isRethink) {
            val rpnId = getRpnIds()
            logd("flow/inflow: returning $rpnId for connId: $connId, uid: $uid")
            io("checkPlusSub") {
                initiatePlusSubscriptionCheckIfRequired()
            }
            rpnId
        } else {
            proxyId
        }
        mark.pidcsv = iid
        mark.cid = connId
        // no need to handle rethink as
        mark.uid = uid.toString()
        if (cm == null) {
            Logger.i(
                LOG_TAG_VPN,
                "flow/inflow: returning mark: $mark for connId: $connId, uid: $uid, cm: null"
            )
        } else {
            Logger.i(
                LOG_TAG_VPN,
                "flow/inflow: returning mark: $mark for src(${cm.sourceIP}: ${cm.sourcePort}), dest(${cm.destIP}:${cm.destPort})"
            )
        }
        return mark
    }

    private suspend fun initiatePlusSubscriptionCheckIfRequired() {
        // consider enableWarp as the flag to check the plus subscription
        if (!RpnProxyManager.isRpnActive()) {
            Logger.i(LOG_TAG_VPN, "initiatePlusSubscriptionCheckIfRequired: plus not enabled")
            return
        }
        // initiate the check once in 4 hours, store last check time in local variable
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSubscriptionCheckTime < PLUS_CHECK_INTERVAL) {
            Logger.v(LOG_TAG_VPN, "initiatePlusSubscriptionCheckIfRequired: check not required")
            return
        }
        // initiate the check
        checkForPlusSubscription()
        lastSubscriptionCheckTime = currentTime
    }

    private suspend fun processFirewallRequest(
        metadata: ConnTrackerMetaData,
        anyRealIpBlocked: Boolean = false,
        blocklists: String = "",
        isSplApp: Boolean = false
    ) {
        val rule = firewall(metadata, anyRealIpBlocked, isSplApp)

        metadata.blockedByRule = rule.id
        metadata.blocklists = blocklists

        val blocked = FirewallRuleset.ground(rule)
        metadata.isBlocked = blocked

        addCidToTrackedCidsToCloseIfNeeded(metadata.connId, rule)

        logd("firewall-rule $rule on conn $metadata")
        return
    }

    private fun addCidToTrackedCidsToCloseIfNeeded(cid: String, rule: FirewallRuleset) {
        // no need to track the blocked connections, as they will be closed
        if (FirewallRuleset.ground(rule)) {
            return
        }
        // skip the connections if the rules is part of any bypass rules
        // like, app bypass, dns bypass, domain trust, ip trust
        if (FirewallRuleset.isBypassRule(rule)) {
            return
        }

        Logger.v(LOG_TAG_VPN, "firewall-rule $rule, adding to trackedCids to close, $cid")
        trackedCidsToClose.add(cid)
    }

    // this method is called when the device is locked, so no need to check for device lock here
    private fun closeTrackedConnsOnDeviceLock() {
        io("devLockCloseConns") {
            // do not call closeConnections with empty list, as it will close all connections
            if (trackedCidsToClose.isNotEmpty()) {
                vpnAdapter?.closeConnections(trackedCidsToClose.toList())
                trackedCidsToClose.clear()
            }
        }
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

    suspend fun getProxyStatusById(id: String): Pair<Long?, String> {
        return vpnAdapter?.getProxyStatusById(id) ?: Pair(null, "adapter is null")
    }

    suspend fun getProxyStats(id: String): RouterStats? {
        return vpnAdapter?.getProxyStats(id)
    }

    suspend fun getSupportedIpVersion(id: String): Pair<Boolean, Boolean>? {
        return vpnAdapter?.getSupportedIpVersion(id) ?: return Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(id: String, pair: Pair<Boolean, Boolean>): Boolean {
        return vpnAdapter?.isSplitTunnelProxy(id, pair) ?: false
    }

    fun syncP50Latency(id: String) {
        io("syncP50Latency") { vpnAdapter?.syncP50Latency(id) }
    }

    override fun onTrimMemory(level: Int) {
        // override onLowMemory is deprecated, so use onTrimMemory
        // ref: developer.android.com/reference/android/net/VpnService
        super.onTrimMemory(level)
        Logger.i(LOG_TAG_VPN, "onTrimMemory: $level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // TODO: call go to clear the cache
            // show notification to user, that the app is consuming more memory
            showMemoryNotification()
        }
        io("onLowMem") { vpnAdapter?.onLowMemory() }
    }

    private fun showMemoryNotification() {
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, AppLockActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )

        val builder =
            NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                .setContentTitle(getString(R.string.memory_notification_text))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
        builder.color = ContextCompat.getColor(this, getAccentColor(persistentState.theme))
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(MEMORY_NOTIFICATION_ID, builder.build())
    }

    override fun onRevoke() {
        Logger.i(LOG_TAG_VPN, "onRevoke, stop vpn adapter")
        signalStopService("revoked", false)
    }

    suspend fun getSystemDns(): String {
        return vpnAdapter?.getSystemDns() ?: ""
    }

    fun getNetStat(): backend.NetStat? {
        return vpnAdapter?.getNetStat()
    }

    fun writeConsoleLog(log: ConsoleLog) {
        netLogTracker.writeConsoleLog(log)
    }

    suspend fun isProxyReachable(proxyId: String, csv: String): Boolean { // can be ippcsv or hostpcsv
        return vpnAdapter?.isProxyReachable(proxyId, csv) == true
    }

    suspend fun testRpnProxy(type: RpnProxyManager.RpnType): Boolean {
        return vpnAdapter?.testRpnProxy(type) == true
    }

    suspend fun testHop(src: String, via: String): Pair<Boolean, String> {
        return vpnAdapter?.testHop(src, via) ?: Pair(false, "vpn not active")
    }

    suspend fun hopStatus(src: String, via: String): Pair<Long?, String> {
        return vpnAdapter?.hopStatus(src, via) ?: Pair(null, "vpn not active")
    }

    suspend fun removeHop(src: String): Pair<Boolean, String> {
        return vpnAdapter?.removeHop(src) ?: Pair(false, "vpn not active")
    }

    suspend fun getRpnProps(type: RpnProxyManager.RpnType): Pair<RpnProxyManager.RpnProps?, String?> {
        return vpnAdapter?.getRpnProps(type) ?: Pair(null, null)
    }

    suspend fun registerSEToTunnel(): Boolean {
        return vpnAdapter?.registerSurfEasyIfNeeded() == true
    }

    suspend fun registerAndFetchWarpConfigIfNeeded(prevBytes: ByteArray?): ByteArray? {
        return vpnAdapter?.registerAndFetchWarpConfigIfNeeded(prevBytes)
    }

    suspend fun registerAndFetchAmzConfigIfNeeded(prevBytes: ByteArray?): ByteArray? {
        return vpnAdapter?.registerAndFetchAmzConfigIfNeeded(prevBytes)
    }

    suspend fun registerAndFetchProtonIfNeeded(prevBytes: ByteArray?): ByteArray? {
        return vpnAdapter?.registerAndFetchProtonIfNeeded(prevBytes)
    }

    suspend fun createWgHop(origin: String, via: String): Pair<Boolean, String> {
        return (vpnAdapter?.createHop(origin, via)) ?: Pair(false, "adapter is null")
    }

    suspend fun via(proxyId: String): String {
        return vpnAdapter?.via(proxyId) ?: ""
    }

    suspend fun updateRpnProxy(type: RpnProxyManager.RpnType): ByteArray? {
        return vpnAdapter?.updateRpnProxy(type)
    }

    suspend fun vpnStats(): String {
        // create a string with the stats, add stats of firewall, dns, proxy, builder
        // other key stats
        val stats = StringBuilder()
        stats.append("VPN Stats:\n")
        stats.append("Builder:\n${builderStats()}\n")
        stats.append("General:\n${generalStats()}\n")
        stats.append("Firewall:\n${firewallStats()}\n")
        //stats.append("DNS: ${dnsStats()}\n")
        stats.append("Proxy:\n${proxyStats()}\n")
        stats.append("RPN:\n${rpnStats()}\n")
        stats.append("IpRules:\n${ipRulesStats()}\n")
        stats.append("DomainRules:\n${domainRulesStats()}\n")

        return stats.toString()
    }

    private fun firewallStats(): String {
        return FirewallManager.stats()
    }

    private fun dnsStats(): String {
        // TODO: add dns stats
        return ""
    }

    private fun rpnStats(): String {
        return RpnProxyManager.stats()
    }

    private fun generalStats(): String {
        return appConfig.stats()
    }

    private fun proxyStats(): String {
        return ProxyManager.stats()
    }

    private suspend fun ipRulesStats(): String {
        return IpRulesManager.stats()
    }

    private suspend fun domainRulesStats(): String {
        return DomainRulesManager.stats()
    }

    private fun builderStats(): String {
        val n = Networks(underlyingNetworks, overlayNetworks)
        val (route4, route6) = determineRoutes(n)

        val sb = StringBuilder()
        sb.append("  $builderStats\n")
        sb.append("   builderRoutes: ${builderRoutes}\n")
        sb.append("   Underlying\n")
        sb.append("      4: ${underlyingNetworks?.ipv4Net?.size}\n")
        sb.append("      6: ${underlyingNetworks?.ipv6Net?.size}\n")
        sb.append("      vpnRoutes: ${underlyingNetworks?.vpnRoutes}\n")
        sb.append("      useActive: ${underlyingNetworks?.useActive}\n")
        sb.append("      mtu: ${underlyingNetworks?.minMtu}\n")
        sb.append("   Overlay\n")
        sb.append("      4: ${overlayNetworks.has4}\n")
        sb.append("      6: ${overlayNetworks.has6}\n")
        sb.append("      mtu:${overlayNetworks.mtu}\n")
        sb.append("      determine4: $route4\n")
        sb.append("      determine66: $route6\n")

        return sb.toString()
    }

    /*override fun onUnbind(intent: Intent?): Boolean {
        Logger.w(LOG_TAG_VPN, "onUnbind, stop vpn adapter")
        // onUnbind is called when the vpn is disconnected by signalStopService or if
        // some other vpn service is started by the user, so stop the vpn adapter in onUnbind which
        // will close tunFd which is a prerequisite for onDestroy()
        stopVpnAdapter()
        return super.onUnbind(intent)
    }*/
}
