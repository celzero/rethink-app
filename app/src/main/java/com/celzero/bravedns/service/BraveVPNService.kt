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

//import com.celzero.bravedns.rpnproxy.RpnProxyManager.RpnMode
//import com.celzero.bravedns.rpnproxy.RpnProxyManager.rpnMode
import Logger
import Logger.LOG_BATCH_LOGGER
import Logger.LOG_GO_LOGGER
import Logger.LOG_TAG_CONNECTION
import Logger.LOG_TAG_VPN
import android.annotation.SuppressLint
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
import android.content.IntentFilter
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
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.IpInfoDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.receiver.UserPresentReceiver
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.ProxyManager.isNotLocalAndRpnProxy
import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.CoFactory
import com.celzero.bravedns.util.ConnectivityCheckHelper
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
import com.celzero.bravedns.util.Constants.Companion.PRIMARY_USER
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.CrashReporter
import com.celzero.bravedns.util.Daemons
import com.celzero.bravedns.util.IPUtil
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.MemoryUtils
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isAtleastU
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.isMissingOrInvalidUid
import com.celzero.bravedns.util.Utilities.isNetworkSame
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.DNSTransport
import com.celzero.firestack.backend.Gostr
import com.celzero.firestack.backend.NetStat
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.RouterStats
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.Controller
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.SocketSummary
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalCause
import com.google.common.cache.RemovalNotification
import com.google.common.collect.Sets
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.measureTime

class BraveVPNService : VpnService(), ConnectionMonitor.NetworkListener, Bridge, OnSharedPreferenceChangeListener {

    private val vpnScope = MainScope()

    // used mostly for service to adapter creation and updates
    private var serializer: CoroutineDispatcher = Daemons.make("vpnser")

    private var connectionMonitor: ConnectionMonitor = ConnectionMonitor(this, this, serializer, vpnScope)
    private val connTrackRepository by inject<ConnectionTrackerRepository>()

    private var userPresentReceiver: UserPresentReceiver = UserPresentReceiver()

    // multiple coroutines call both signalStopService and makeOrUpdateVpnAdapter and so
    // set and unset this variable on the serializer thread
    @Volatile
    private var vpnAdapter: GoVpnAdapter? = null

    private val flowDispatcher by lazy { Daemons.ioDispatcher("flow", Mark(),  vpnScope) }
    private val inflowDispatcher by lazy { Daemons.ioDispatcher("inflow", Mark(), vpnScope) }
    private val preflowDispatcher by lazy { Daemons.ioDispatcher("preflow", PreMark(), vpnScope) }
    private val dnsQueryDispatcher by lazy { Daemons.ioDispatcher("onQuery", DNSOpts(), vpnScope) }
    private val proxyAddedDispatcher by lazy { Daemons.ioDispatcher("proxyAdded", Unit, vpnScope) }

    // TODO: remove volatile
    @Volatile
    private var builderStats: String = ""
    @Volatile
    private var tunUnderlyingNetworks: String? = null
    @Volatile
    private var prevDns: MutableSet<InetAddress> = mutableSetOf()
    @Volatile
    private var lastRxTrafficTime: Long = elapsedRealtime() // tracks rx from onSocketClosed()
    private var testFd: AtomicInteger = AtomicInteger(-1)

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
        private const val MAX_MTU: Int = 10000

        // route v4 in v6 only networks?
        const val ROUTE4IN6 = true

        // subscription check interval in milliseconds 1 hour
        // TODO: increase it to 6 hours?
        private const val PLUS_CHECK_INTERVAL = 1 * 60 * 60 * 1000L

        // win last connected threshold in milliseconds
        private const val WIN_LAST_CONNECTED_THRESHOLD_MS = 60 * 60 * 1000L // 60 minutes

        private const val DATA_STALL_THRESHOLD_MS = 30 * 1000L // 30 seconds

        // vpnRoutes are only used for diagnostics, the current implementation will taken
        // into account the vpn routes are handled properly, case: do not route private ips
        private const val RECONCILE_WITH_VPN_ROUTES = false

        const val FIRESTACK_MUST_DUP_TUNFD = true
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
    private lateinit var cm: ConnectivityManager
    private var keyguardManager: KeyguardManager? = null

    private lateinit var appInfoObserver: Observer<Collection<AppInfo>>
    private lateinit var orbotStartStatusObserver: Observer<Boolean>
    private lateinit var dnscryptRelayObserver: Observer<PersistentState.DnsCryptRelayDetails>

    private var rethinkUid: Int = INVALID_UID

    // used to store the conn-ids that are allowed and active, to show in network logs
    // as active connections. removed when the connection is closed (onSummary)
    private var activeCids = Collections.newSetFromMap(ConcurrentHashMap<CidKey, Boolean>())

    // used to store the ConnTrackerMetaData that has multiple proxy ids associated with it
    // waiting for the connection to be established, the call from postFlow/socketClosed will
    // remove the entry from this map
    private val trackedConnMetaData: Cache<String, ConnTrackerMetaData> =
        CacheBuilder.newBuilder()
            .expireAfterWrite(300, TimeUnit.SECONDS) // entry removed 300s after creation/update
            .removalListener<String, ConnTrackerMetaData> { notification ->
                handleExpiredConnMetaData(notification)
            }
            .build()

    // used to store the conn-ids that need to be closed when device is locked,
    // this is used to close the connections when the device is locked
    // list will exclude bypassed apps, domains and ip rules
    private var activeClosableCids = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // data class to store the connection summary
    data class CidKey(val cid: String, val uid: Int)

    private var excludedApps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // post underlying networks as live data
    @Volatile
    var underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null

    @Volatile
    var overlayNetworks: OverlayNetworks = OverlayNetworks()

    // marks whether the uid is included in the dns requests. universal firewall rules are enforced
    // only when this flag is true, ensuring unknown app DNS requests are blocked and avoiding
    // issues when Android omits uid in dns requests
    private var isUidPresentInAnyDnsRequest: Boolean = false

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

        logd("bind: who: $who, addr: $addrPort, fd: $fid, rinr? $rinr")
        if (rinr && who != Backend.Exit) {
            // do not proceed if rethink within rethink is enabled and proxyId(who) is not exit
            return
        }

        this.protect(fid.toInt())

        if (nws.isEmpty()) {
            Logger.w(LOG_TAG_VPN, "no network to bind, who: $who, fd: $fid, addr: $addrPort")
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
                logd("bind: invalid destIp: $destIp, who: $who, addr: $addrPort")
                return
            }

            pfd = ParcelFileDescriptor.adoptFd(fid.toInt())

            // check if the destination port is DNS port, if so bind to the network where the dns
            // belongs to, else bind to the available network
            val net = if (KnownPorts.isDns(destPort)) curnet?.dnsServers?.get(destAddr) else null
            if (net != null) {
                val ok = bindToNw(net, pfd, fid)
                if (!ok) {
                    Logger.e(LOG_TAG_VPN, "bind failed, who: $who, addr: $addrPort, fd: $fid, handle: ${net.networkHandle}, netid:${netid(net.networkHandle)}")
                } else {
                    logd("bind: dns, who: $who, addr: $addrPort, fd: $fid, handle: ${net.networkHandle}, netid:${netid(net.networkHandle)}, ok: $ok")
                }
                return
            }

            // who is not used, but kept for future use
            // binding to the underlying network is not working.
            // no need to bind if use active network is true
            if (curnet?.useActive == true) {
                logd("bind: use active network is true, who: $who, addr: $addrPort, fd: $fid")
                return
            }

            nws.forEach {
                if (bindToNw(it.network, pfd, fid)) {
                    logd("bind: nw, who: $who, addr: $addrPort, fd: $fid, handle: ${it.network.networkHandle}, netid:${netid(it.network.networkHandle)}")
                    return
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "err bind: who: $who, addr: $addrPort, fd: $fid, ${e.message}, $e")
        } finally {
            pfd?.detachFd()
        }
        Logger.e(LOG_TAG_VPN, "bind failed: who: $who, addr: $addrPort, fd: $fid")
    }

    private fun netid(nwHandle: Long): Long {
        // ref: cs.android.com/android/platform/superproject/main/+/main:packages/modules/Connectivity/framework/src/android/net/Network.java;drc=0209c366627e98d6311629a0592c6e22be7d13e0;l=491
        return nwHandle shr (32)
    }

    fun bindToNwForConnectivityChecks(nw: Network, fid: Long): Boolean {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = ParcelFileDescriptor.adoptFd(fid.toInt())
            return bindToNw(nw, pfd, fid)
        } catch (e: Exception) {
            Logger.i(LOG_TAG_VPN, "err bindToNwForConnectivityChecks, ${e.message}")
        } finally {
            pfd?.detachFd()
        }
        return false
    }

    fun protectFdForConnectivityChecks(fd: Long) {
        this.protect(fd.toInt())
        Logger.v(LOG_TAG_CONNECTION, "fd($fd) protected for connectivity checks")
    }

    suspend fun getPlusResolvers(): List<String> {
        return vpnAdapter?.getPlusResolvers() ?: emptyList()
    }

    suspend fun getPlusTransportById(transportId: String): DNSTransport? {
        return vpnAdapter?.getPlusTransportById(transportId)
    }

    private fun bindToNw(net: Network, pfd: ParcelFileDescriptor, fid: Long): Boolean {
        return try {
            net.bindSocket(pfd.fileDescriptor)
            true
        } catch (e: IOException) {
            Logger.e(LOG_TAG_VPN, "err bindToNw(nw: ${net.networkHandle}, netid: ${netid(net.networkHandle)}, fid: $fid, ${e.message}, $e")
            false
        }
    }

    suspend fun probeIpOrUrl(ipOrUrl: String, useAuto: Boolean): ConnectionMonitor.ProbeResult? {
        val nws = constructNetworkProperties(underlyingNetworks)
        if (useAuto) {
            // in auto mode the ipOrUrl contain scheme:protocol
            val split = ipOrUrl.split(":")
            val scheme = split.firstOrNull() ?: ConnectionMonitor.SCHEME_HTTPS
            val protocol = split.getOrNull(1) ?: ConnectionMonitor.PROTOCOL_V4
            val defaultIps = persistentState.pingv4Ips.split(",").map { it.trim() }
            if (nws.isEmpty()) {
                val res = ConnectivityCheckHelper.probeConnectivityInAutoMode(scheme = scheme, protocol = protocol, ipOrUrl = defaultIps, useKotlinChecks = ConnectionMonitor.USE_KOTLIN_REACHABILITY_CHECKS)
                return ConnectionMonitor.ProbeResult("", res, null)
            }
            nws.forEach { nwprop ->
                val res = ConnectivityCheckHelper.probeConnectivityInAutoMode(nwprop.network, scheme,  protocol, defaultIps,  ConnectionMonitor.USE_KOTLIN_REACHABILITY_CHECKS)
                if (res) {
                    return ConnectionMonitor.ProbeResult("", res, nwprop.capabilities)
                }
            }
            return ConnectionMonitor.ProbeResult("", false, null)
        } else {
            val activeCap = cm.getNetworkCapabilities(cm.activeNetwork) // can be null
            val useKotlinConnectivityChecks = ConnectionMonitor.USE_KOTLIN_REACHABILITY_CHECKS
            return ConnectivityCheckHelper.probeIpOrUrl(
                ipOrUrl,
                nws,
                activeCap,
                useKotlinConnectivityChecks
            )
        }
    }

    private fun constructNetworkProperties(nws: ConnectionMonitor.UnderlyingNetworks?): Set<ConnectionMonitor.NetworkProperties> {
        if (nws == null) {
            Logger.w(LOG_TAG_VPN, "constructNetworkProperties: underlying networks is null")
            return emptySet()
        }
        val nwProps: MutableList<ConnectionMonitor.NetworkProperties> = mutableListOf()
        if (nws.ipv4Net.isNotEmpty()) {
            nwProps.addAll(nws.ipv4Net)
        }
        if (nws.ipv6Net.isNotEmpty()) {
            nwProps.addAll(nws.ipv6Net)
        }
        return nwProps.toSet()
    }

    fun protectSocket(socket: Socket) {
        this.protect(socket)
        Logger.v(LOG_TAG_VPN, "socket protected")
    }

    override fun protect(who: String?, fd: Long) {
        val rinr = persistentState.routeRethinkInRethink
        logd("protect: $who, fd: $fd, rinr? $rinr")
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
        dstPort: Int
    ): Int {
        if (recdUid != INVALID_UID) {
            return recdUid
        }
        return if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            ioAsync("getUidQ") { connTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort) }
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

            if (isLockdown() && isAppPaused()) {
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
                return statusIpPort4in6 // trusted or blocked or bypassed-universal
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

            InternetProtocol.ALWAYSv46.id -> {
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
        if (!useActive || isLockdown()) {
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
            curnet.isActiveNetworkMetered = cm.isActiveNetworkMetered
        }
        return curnet.isActiveNetworkMetered
    }

    private fun isActiveIfaceCellular(): Boolean {
        val curnet = underlyingNetworks ?: return false // assume unmetered
        val now = elapsedRealtime()
        val ts = curnet.lastUpdated
        if (abs(now - ts) > Constants.ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            curnet.lastUpdated = now
            val activeNetwork = cm.activeNetwork
            val cap = cm.getNetworkCapabilities(activeNetwork) ?: return false
            val isCellular = cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            curnet.isActiveNetworkCellular = isCellular
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
        if (keyguardManager == null) {
            keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        }
        // if keyguard is locked, then the app is in background
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
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
        return persistentState.allowBypass &&
                !appConfig.isProxyEnabled()
    }

    private suspend fun newBuilder(): Builder {
        var builder = Builder()
        val underlyingNws = getUnderlays()
        // prefer view of underlying networks over vpn service lockdown state for being consistent
        // with onNetworksChanged()
        val vpnLockdown = if (isAtleastQ()) {
            underlyingNetworks?.vpnLockdown ?: isLockdownEnabled
        } else {
            false
        }
        if (!vpnLockdown && !isPlayStoreFlavour() && canAllowBypass()) {
            Logger.i(LOG_TAG_VPN, "allow apps to bypass vpn on-demand")
            builder = builder.allowBypass()
            // TODO: should allowFamily be set?
            // family must be either AF_INET (for IPv4) or AF_INET6 (for IPv6)
        }
        builder.setUnderlyingNetworks(underlyingNws)
        tunUnderlyingNetworks = underlyingNws?.joinToString()
        logd("builder: set underlying networks: $tunUnderlyingNetworks")

        // now that we set metered based on user preference, earlier it was always set to false
        // as cloud backups were failing thinking that the VPN connection is metered
        if (isAtleastQ()) {
            builder.setMetered(persistentState.setVpnBuilderToMetered)
            logd("builder: set metered: ${persistentState.setVpnBuilderToMetered}")
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
            if (vpnLockdown) {
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
            if (!vpnLockdown) {
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
            if (!vpnLockdown && isExcludeProxyApp(appName)) {
                Logger.i(LOG_TAG_VPN, "exclude app for socks5, pkg: $appName")
                addDisallowedApplication(builder, appName)
            } else {
                Logger.i(LOG_TAG_VPN, "socks5(exclude): app not set or exclude not possible")
            }
        }

        if (!vpnLockdown && appConfig.isOrbotProxyEnabled() && isExcludeProxyApp(getString(R.string.orbot))) {
            Logger.i(LOG_TAG_VPN, "exclude orbot app")
            addDisallowedApplication(builder, OrbotHelper.ORBOT_PACKAGE_NAME)
        }

        if (appConfig.isCustomHttpProxyEnabled()) {
            // For HTTP proxy if there is a app selected, add that app in excluded list
            val httpProxyEndpoint = appConfig.getConnectedHttpProxy()
            val appName =
                httpProxyEndpoint?.proxyAppName ?: getString(R.string.settings_app_list_default_app)
            if (!vpnLockdown && isExcludeProxyApp(appName)) {
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
            if (!vpnLockdown && isExcludeProxyApp(appName)) {
                Logger.i(LOG_TAG_VPN, "exclude app for dns proxy, pkg: $appName")
                addDisallowedApplication(builder, appName)
            } else {
                Logger.i(LOG_TAG_VPN, "dns proxy(exclude): app not set or exclude not possible")
            }
        }

        return builder
    }

    private fun isExcludeProxyApp(appName: String?): Boolean {
        // user settings to exclude apps in proxy mode
        if (!persistentState.excludeAppsInProxy) {
            Logger.i(LOG_TAG_VPN, "exclude apps in proxy is disabled")
            return false
        }

        return appName?.equals(getString(R.string.settings_app_list_default_app)) == false
    }

    private fun addDisallowedApplication(builder: Builder, pkg: String) {
        try {
            Logger.d(LOG_TAG_VPN, "builder: exclude app: $pkg")
            builder.addDisallowedApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_VPN, "builder: skip adding disallowed app ($pkg)", e)
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
        keyguardManager = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        cm =
            this.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        if (persistentState.getBlockAppWhenBackground()) {
            registerAccessibilityServiceState()
        }
        registerUserPresentReceiver()
    }

    private fun registerUserPresentReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(userPresentReceiver, filter)
        Logger.i(LOG_TAG_VPN, "user present receiver registered")
    }

    private fun unregisterUserPresentReceiver() {
        try {
            unregisterReceiver(userPresentReceiver)
            Logger.i(LOG_TAG_VPN, "user present receiver unregistered")
        } catch (_: IllegalArgumentException) {
            Logger.w(LOG_TAG_VPN, "user present receiver not registered")
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

                val reason = "excludeApps: ${latestExcludedApps.size} apps, at: ${elapsedRealtime()}"
                vpnRestartTrigger.value = reason
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
        } catch (_: Exception) {
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

    private val vpnRestartTrigger: MutableStateFlow<String> = MutableStateFlow("startVpn")
    @OptIn(FlowPreview::class)
    private fun observeVpnRestartRequests() {
        vpnScope.launch {
            Logger.i(LOG_TAG_VPN, "start restart manager flow")
            // if the string is same, it will not restart the vpn, so adding ts whereever same
            // value requires a restart
            vpnRestartTrigger
                .debounce(3000)
                .collect { reason ->
                    Logger.v(LOG_TAG_VPN, "RESTART; new restart request: $reason")
                    restartVpnWithNewAppConfig(reason)
                }
        }
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
            // this should always be set before ConnectionMonitor is init-d
            // see restartVpn and updateTun which expect this to be the case
            persistentState.setVpnEnabled(true)

            startOrbotAsyncIfNeeded()

            val isNewVpn = connectionMonitor.onVpnStart(this)

            if (isNewVpn) {
                // clear the underlying networks, so that the new vpn can be created with the
                // current active network.
                underlyingNetworks = null
                Logger.i(LOG_TAG_VPN, "new vpn")
            }

            val opts =
                appConfig.newTunnelOptions(
                    this,
                    getFakeDns(),
                    appConfig.getProtocolTranslationMode()
                )

            val isVpnEnabled = persistentState.getVpnEnabled()
            Logger.i(LOG_TAG_VPN, "start-fg with opts $opts (for new-vpn? $isNewVpn), isEnabled? $isVpnEnabled")
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
                        restartVpn(this, opts, why = "startVpn")
                        // this should always happen after vpn enabled is set to true, as this will
                        // call restart-vpn and there is a check for vpn enabled state
                        observeVpnRestartRequests()
                        // call this *after* a new vpn is created #512
                        uiCtx("observers") { observeChanges() }
                    }
                }
            }
        }
        return Service.START_STICKY
    }

    /*private suspend fun checkForPlusSubscription() {
        // initiate the billing client if it is not already initialized
        if (!InAppBillingHandler.isBillingClientSetup()) {
            InAppBillingHandler.initiate(this.applicationContext)
            Logger.i(LOG_TAG_VPN, "checkForPlusSubscription: billing client initiated")
        } else {
            Logger.i(LOG_TAG_VPN, "checkForPlusSubscription: billing client already setup")
        }

        // start the subscription check only if the user has enabled the feature
        // and the subscription check is not already in progress
        // invoke the work manager to check the subscription status
        if (RpnProxyManager.isRpnEnabled()) {
            io("rethinkPlusSubs") {
                handleRpnProxies()
                Logger.i(LOG_TAG_VPN, "checkForPlusSubscription(rpn): start work manager")
                val workManager = WorkManager.getInstance(this)
                val workRequest = OneTimeWorkRequestBuilder<SubscriptionCheckWorker>().build()
                workManager.enqueueUniqueWork(
                    SubscriptionCheckWorker.WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            }
            // TOOD: write listener to check the subscription status, if worker fails
            // reset the last check time
        } else {
            Logger.i(LOG_TAG_VPN, "checkForPlusSubscription(rpn): feature disabled")
        }
    }

    private suspend fun handleRpnProxies() {
        if (RpnProxyManager.isRpnActive()) {
            if (vpnAdapter == null) {
                Logger.i(LOG_TAG_VPN, "handleRpnProxies(rpn): adapter null, no-op")
                return
            }
            // win proxy is handled separately
            handleWinProxy()
            // TODO: get the list of other countries other than default, add all of them
            // make sure it doesn't exceed the max number of allowed configs (5)
            // if the user has selected a country, then add that country to the list
            val countries = RpnProxyManager.getSelectedCCs()
            if (countries.isNotEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: selected countries(rpn): $countries")
                // TODO: add the selected countries to the tunnel, new API needed
            }
            vpnAdapter?.setRpnAutoMode()
        } else { // either in pause mode or plus disabled
            Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: plus disabled(rpn)")
            vpnAdapter?.unregisterWin()
        }
    }

    private suspend fun handleWinProxy() {
        val win = true
        // see if win is already registered and last connected is less than 60 mins
        val isWinRegistered = vpnAdapter?.isWinRegistered() == true
        Logger.d(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) test: $win, registered? $isWinRegistered")
        if (win && !isWinRegistered) {
            var existingBytes = RpnProxyManager.getWinExistingData() // fetch existing win state
            if (existingBytes == null) {
                Logger.i(LOG_TAG_PROXY, "$TAG handleRpnProxies: win(rpn) state is null, fetching entitlement")
                existingBytes = RpnProxyManager.getWinEntitlement()
            }
            if (existingBytes == null || existingBytes.isEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG handleRpnProxies: win(rpn) entitlement is null or empty, cannot register")
                return
            }
            val bytes = registerAndFetchWinIfNeeded(existingBytes)
            RpnProxyManager.updateWinConfigState(bytes)
            Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: exit64(rpn), registered? ${bytes != null}")
        } else if (isWinRegistered) {
            val lastConnectedTs = vpnAdapter?.getWinLastConnectedTs()
            if (lastConnectedTs != null && abs(elapsedRealtime() - lastConnectedTs) < WIN_LAST_CONNECTED_THRESHOLD_MS) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) already registered, no-op")
            } else {
                // update the proxy as last connected time is more than 60 mins
                try {
                    Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) registered, updating")
                    val bytes = vpnAdapter?.updateWin()
                    if (bytes != null && bytes.isEmpty()) {
                        Logger.w(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) no update needed")
                        return
                    }
                    // if the bytes are null, then it means the win is either failer to update or
                    // no update is needed
                    val updated = RpnProxyManager.updateWinConfigState(bytes)
                    if (!updated) {
                        Logger.w(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) update failed, no-op")
                        //lastSubscriptionCheckTime = 0 // reset the last subscription check time
                        return
                    }
                    // re-register the win proxy
                    vpnAdapter?.registerAndFetchWinIfNeeded(bytes)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) update failed", e)
                    // fixme: find a way to handle this case
                    // this is a work around for the case where the win is registered
                    // but the last connected time is not updated, so we reset the
                    // last subscription check time to 0, so that the next time for any
                    // network request, it will try to update the win
                    //lastSubscriptionCheckTime = 0
                }
            }
        }
    }

    private suspend fun setRpnAutoMode() {
        val res = vpnAdapter?.setRpnAutoMode()
        logd("set rpn mode to: ${rpnMode()}, set? $res")
        handleRpnProxies()
    }*/

    @SuppressLint("ForegroundServiceType")
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

    @SuppressLint("ForegroundServiceType")
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
        if (DEBUG && persistentState.useMaxMtu) {
            return MAX_MTU
        }
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
                    val reason = "braveMode: ${appConfig.getBraveMode()}}"
                    vpnRestartTrigger.value = reason
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
                            val reason = "dnsProxy: ${appConfig.getSelectedDnsProxyDetails()?.id}"
                            vpnRestartTrigger.value = reason
                            addTransport()
                        }

                        AppConfig.DnsType.RETHINK_REMOTE -> {
                            addTransport()
                        }

                        AppConfig.DnsType.SYSTEM_DNS -> {
                            setNetworkAndDefaultDnsIfNeeded(forceUpdate = true)
                        }

                        AppConfig.DnsType.SMART_DNS -> {
                            // no need to add multiple DoH as smart dns as it is expected to be
                            // added by the vpn adapter while starting, but add it if it is missing
                            if(getDnsStatus(Backend.Plus) == null) addTransport()
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
                val reason = "allowBypass: ${persistentState.allowBypass}"
                vpnRestartTrigger.value = reason
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
                    if (!isDefaultDnsNone()) {
                        vpnAdapter?.addDefaultTransport(persistentState.defaultDnsUrl)
                    } else {
                        setNetworkAndDefaultDnsIfNeeded(forceUpdate = true)
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
                val reason = "routeLanTraffic: ${persistentState.privateIps}"
                vpnRestartTrigger.value = reason
            }

            PersistentState.RETHINK_IN_RETHINK -> {
                // restart vpn to allow/disallow rethink traffic in rethink
                io("routeRethinkInRethink") {
                    val reason = "routeRethinkInRethink: ${persistentState.routeRethinkInRethink}"
                    vpnRestartTrigger.value = reason
                    vpnAdapter?.notifyLoopback()
                    setNetworkAndDefaultDnsIfNeeded(forceUpdate = true)
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
                    val reason = "excludeAppsInProxy: ${persistentState.excludeAppsInProxy}"
                    vpnRestartTrigger.value = reason
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
            PersistentState.NETWORK_ENGINE_EXPERIMENTAL -> {
                io("networkEngineExperimental") {
                    setExperimentalSettings(persistentState.nwEngExperimentalFeatures)
                }
            }
            PersistentState.USE_RPN -> {
                io("rpnUpdated") {
                    //handleRpnProxies()
                }
            }
            PersistentState.RPN_MODE -> {
                io("rpnMode") {
                    //setRpnAutoMode()
                }
            }
            PersistentState.DIAL_TIMEOUT_SEC -> {
                io("tunTimeout") {
                    setDialStrategy()
                }
            }
            PersistentState.AUTO_DIALS_PARALLEL -> {
                io("autoDialsParallel") {
                    setAutoDialsParallel()
                }
            }
            PersistentState.STALL_ON_NO_NETWORK -> {
                io("stallOnNoNetwork") {
                    notifyConnectionMonitor()
                }
                val reason = "stallOnNoNetwork: ${persistentState.stallOnNoNetwork}"
                vpnRestartTrigger.value = reason
            }
            PersistentState.TUN_NETWORK_POLICY -> {
                io("tunNetworkPolicy") {
                    // notify connection monitor to update the network policy
                    Logger.i(LOG_TAG_VPN, "tun network policy changed, notify connection monitor")
                    connectionMonitor.onPolicyChanged()
                }
            }
            PersistentState.USE_MAX_MTU -> {
                io("useMaxMtu") {
                    val newMtu = mtu()
                    Logger.i(LOG_TAG_VPN, "use max mtu changed, new mtu: $newMtu")
                    val reason = "useMaxMtu: ${persistentState.useMaxMtu}"
                    vpnRestartTrigger.value = reason
                }
            }
            PersistentState.SET_VPN_BUILDER_TO_METERED -> {
                io("setVpnBuilderToMetered") {
                    Logger.i(LOG_TAG_VPN, "set vpn builder to metered: ${persistentState.setVpnBuilderToMetered}")
                    val reason = "setVpnBuilderToMetered: ${persistentState.setVpnBuilderToMetered}"
                    vpnRestartTrigger.value = reason
                }
            }
            PersistentState.PANIC_RANDOM -> {
                io("panicRandom") {
                    if (DEBUG) {
                        vpnAdapter?.panicAtRandom(persistentState.panicRandom)
                    } else {
                        Logger.e(LOG_TAG_VPN, "panic random change ignored, not in debug mode")
                    }
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

    private suspend fun setAutoDialsParallel() {
        Logger.d(LOG_TAG_VPN, "set auto dials parallel: ${persistentState.autoDialsParallel}")
        vpnAdapter?.setAutoDialsParallel()
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

    fun closeConnectionsIfNeeded(uid: Int, reason: String) { // can be invalid uid, in which case, no-op
        if (uid == INVALID_UID) return

        if (uid == UID_EVERYBODY) {
            // when the uid is everybody, close all the connections
            io("closeConn") { vpnAdapter?.closeConnections(emptyList(), isUid = false, reason) }
            return
        }

        // close conns can now be called with a list of uids / connIds
        val uid0 = listOf(FirewallManager.appId(uid, isPrimaryUser()).toString())
        io("closeConn") { vpnAdapter?.closeConnections(uid0, isUid = true, reason) }
    }

    fun closeConnectionsByUidDomain(uid: Int, ipAddress: String?, reason: String) {
        // can be invalid uid, in which case, no-op
        // no need to close all connections in case of empty domain, as it is not valid
        if (uid == INVALID_UID || ipAddress.isNullOrEmpty()) return

        io("closeUidIp") {
            val to = System.currentTimeMillis() - VpnController.uptimeMs()
            // can be empty when the conns already closed before ui calls this
            val cids = connTrackRepository.getConnIdByUidIpAddress(uid, ipAddress, to)
            if (cids.isEmpty()) {
                Logger.w(LOG_TAG_VPN, "no connections found for uid: $uid, domain: $ipAddress")
                return@io
            }
            vpnAdapter?.closeConnections(cids, isUid = false, reason)
            Logger.i(LOG_TAG_VPN, "close connections by uid: $uid, domain: $ipAddress, cids: $cids, reason: $reason")
        }
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
            // or onNetworkDisconnected. onNetworkConnected may call restartVpn
            notifyConnectionMonitor()
        }
        val reason = "ipProto: ${persistentState.internetProtocolType}"
        vpnRestartTrigger.value = reason
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
                val reason = "orbotProxy: ${appConfig.isOrbotProxyEnabled()}"
                vpnRestartTrigger.value = reason
                vpnAdapter?.setCustomProxy(tunProxyMode)
            }

            AppConfig.ProxyProvider.CUSTOM -> {
                // custom either means socks5 or http proxy
                // socks5 proxy requires app to be excluded from vpn, so restart vpn
                val isSocks5 = tunProxyMode == AppConfig.TunProxyMode.SOCKS5
                val reason = if (isSocks5) {
                    "customProxy: ${appConfig.getSocks5ProxyDetails()}"
                } else {
                    "customProxy: ${appConfig.getHttpProxyDetails()}"
                }
                vpnRestartTrigger.value = reason
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
    suspend fun notifyConnectionMonitor(enforcePolicyChange: Boolean = false) {
        if (enforcePolicyChange) {
            connectionMonitor.onPolicyChanged()
        } else {
            connectionMonitor.onUserPreferenceChanged()
        }
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

    private suspend fun restartVpnWithNewAppConfig(reason: String) {
        val ctx = this
        val bridge = this
        withContext(serializer) {
            logd("restart vpn with new app config")
            restartVpn(
                ctx,
                appConfig.newTunnelOptions(
                    bridge,
                    getFakeDns(),
                    appConfig.getProtocolTranslationMode()
                ),
                reason
            )
        }
    }

    private suspend fun setPcapMode() {
        val pcapPath = appConfig.getPcapFilePath()
        Logger.i(LOG_TAG_VPN, "pcap mode enabled, path: $pcapPath")
        vpnAdapter?.setPcapMode(pcapPath)
    }

    private suspend fun setTunMode() {
        val opts =
            appConfig.newTunnelOptions(
                this,
                getFakeDns(),
                appConfig.getProtocolTranslationMode()
            )
        Logger.i(
            LOG_TAG_VPN,
            "set tun mode with dns: ${opts.tunDnsMode}, firewall: ${opts.tunFirewallMode}, proxy: ${opts.tunProxyMode}, pt: ${opts.ptMode}"
        )
        vpnAdapter?.setTunMode(opts)
    }


    private suspend fun restartVpn(
        ctx: Context,
        opts: AppConfig.TunnelOptions,
        why: String
    ) =
        withContext(CoroutineName(why) + serializer) {
            if (!persistentState.getVpnEnabled()) {
                // when persistent-state "thinks" vpn is disabled, stop the service, especially when
                // we could be here via onStartCommand -> isNewVpn -> restartVpn while both,
                // vpn-service & conn-monitor exist & vpn-enabled state goes out of sync
                io("outOfSyncRestart") {
                    logAndToastIfNeeded("$why, stop-vpn(restartVpn), tracking vpn is out of sync", Log.ERROR)
                    signalStopService("outOfSyncRestart", userInitiated = false)
                }
                return@withContext
            }
            try {
                Logger.i(
                    LOG_TAG_VPN,
                    "---------------------------RESTART-INIT----------------------------"
                )
                val nws = Networks(underlyingNetworks, overlayNetworks)
                val mtu = mtu()
                // attempt seamless hand-off as described in VpnService.Builder.establish() docs
                val tunFd = establishVpn(nws, mtu)
                if (tunFd == null) {
                    io("noTunRestart1") {
                        Logger.i(LOG_TAG_VPN, "-------------------------RESTART-ERR1----------------------")
                        logAndToastIfNeeded("$why, cannot restart-vpn, no tun-fd", Log.ERROR)
                        signalStopService("noTunRestart1", userInitiated = false)
                    }
                    return@withContext
                }

                testFd.set(tunFd.fd) // save the fd for testing purposes

                val ok =
                    makeOrUpdateVpnAdapter(
                        ctx,
                        tunFd,
                        mtu,
                        opts,
                        builderRoutes
                    ) // builderRoutes set in establishVpn()
                if (!ok) {
                    io("noTunnelRestart2") {
                        Logger.i(LOG_TAG_VPN, "----------------------RESTART-ERR2----------------------")
                        logAndToastIfNeeded("$why, cannot restart-vpn, no vpn-adapter", Log.ERROR)
                        signalStopService("noTunRestart2", userInitiated = false)
                    }
                    return@withContext
                } else {
                    io("restarted") { logAndToastIfNeeded("$why, vpn restarted", Log.INFO) }
                }
                Logger.i(
                    LOG_TAG_VPN,
                    "---------------------------RESTART-OK----------------------------"
                )

                notifyConnectionStateChangeIfNeeded()
                informVpnControllerForProtoChange(builderRoutes)
            } catch (e: Exception) {
                Logger.i(LOG_TAG_VPN, "----------------------RESTART-ERR0----------------------")
                Logger.e(LOG_TAG_VPN, "restart-vpn failed: ${e.message}", e)
                io("restartVpnError") {
                    logAndToastIfNeeded("$why, restart-vpn failed: ${e.message}", Log.ERROR)
                    signalStopService("restartVpnError", userInitiated = false)
                }
            }
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
        mtu: Int,
        opts: AppConfig.TunnelOptions,
        p: Pair<Boolean, Boolean>
    ): Boolean =
        withContext(CoroutineName("makeVpn") + serializer) {
            val restartPolicy = VpnBuilderPolicy.fromOrdinalOrDefault(persistentState.vpnBuilderPolicy).vpnAdapterBehaviour
            val lockdown = underlyingNetworks?.vpnLockdown ?: isLockdown()
            val ok = true
            val noTun = false // should eventually call signalStopService(userInitiated=false)
            val protos = InternetProtocol.byProtos(p.first, p.second).value()
            try {
                val fd = if (FIRESTACK_MUST_DUP_TUNFD) {
                    tunFd.fd.toLong()
                } else {
                    tunFd.detachFd().toLong()
                }

                if (vpnAdapter == null) {
                    // create a new vpn adapter
                    val ifaceAddresses = getAddresses()
                    Logger.i(LOG_TAG_VPN, "vpn-adapter doesn't exists, create one, fd: $fd, lockdown: $lockdown, protos: $protos, ifaddr: $ifaceAddresses, opts: $opts")
                    vpnAdapter = GoVpnAdapter(ctx, vpnScope, fd, ifaceAddresses, mtu, opts) // may throw
                    GoVpnAdapter.setLogLevel(persistentState.goLoggerLevel.toInt())
                    vpnAdapter?.initResolverProxiesPcap(opts)
                    //checkForPlusSubscription()
                    return@withContext ok
                } else {
                    Logger.i(LOG_TAG_VPN, "vpn-adapter exists, fd: $fd, policy: ${restartPolicy.name}, lockdown: $lockdown, protos: $protos")
                    when (restartPolicy) {
                        VpnBuilderPolicy.GoVpnAdapterBehaviour.NEVER_RESTART -> {
                            // In vpn lockdown mode, unlink the adapter to close the previous file descriptor (fd)
                            // and use a new fd after creation. This should only be done in lockdown mode,
                            // as leaks are not possible.
                            // doing so also fixes 'endpoint closed' errors which are frequent in lockdown mode
                            if (lockdown) {
                                vpnAdapter?.unlink()
                            }
                            // in case, if vpn-adapter exists, update the existing vpn-adapter
                            if (vpnAdapter?.updateLinkAndRoutes(fd, mtu, protos) == false) {
                                Logger.e(LOG_TAG_VPN, "err update vpn-adapter")
                                return@withContext noTun
                            }
                        }
                        VpnBuilderPolicy.GoVpnAdapterBehaviour.PREFER_RESTART -> {
                            // TODO: should we check for lockdown mode and decide to restart? or just restart always?
                            // if vpn-adapter exists, recreate vpn-adapter only on lockdown mode
                            if (lockdown) {
                                if (vpnAdapter?.restartTunnel(fd, mtu, protos) == false) {
                                    Logger.e(LOG_TAG_VPN, "err recreate vpn-adapter")
                                    return@withContext noTun
                                }
                            } else {
                                if (vpnAdapter?.updateLinkAndRoutes(fd, mtu, protos) == false) {
                                    Logger.e(LOG_TAG_VPN, "err update vpn-adapter")
                                    return@withContext noTun
                                }
                            }
                        }
                    }

                    return@withContext ok
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "err new vpn-adapter: ${e.message}", e)
                return@withContext noTun
            } finally {
                try { // close the tunFd as GoVpnAdapter has its own copy
                    if (FIRESTACK_MUST_DUP_TUNFD) tunFd.close()
                } catch (e: IOException) {
                    Logger.e(LOG_TAG_VPN, "err closing tunFd: ${e.message}", e)
                }
            }
        }

    // TODO: #294 - Figure out a way to show users that the device is offline instead of status as
    // failing.
    suspend fun onNetworkDisconnected(networks: ConnectionMonitor.UnderlyingNetworks, forceRestart: Boolean = false) {
        underlyingNetworks = networks
        underlyingNetworks?.vpnLockdown = isLockdown()

        val underlyingNws = getUnderlays()
        setUnderlyingNetworks(underlyingNws)
        tunUnderlyingNetworks = underlyingNws?.joinToString()

        Logger.i(LOG_TAG_VPN, "$TAG; nw disconnect, restart vpn? $forceRestart")

        // if there is no changes, then already a disconnection restart happened, no need to
        // restart again, this will avoid unnecessary restarts
        // some cases, onLost is called multiple times, so avoid restarting
        if (!forceRestart) {
            // if forceRestart is false, then we are not restarting the vpn, so no need to set
            // network and default dns, as it is already set to empty before
            return
        }

        val reason = "nwDisconnect"
        vpnRestartTrigger.value = reason
        // pause mobile-only wgs on no network
        pauseMobileOnlyWireGuardOnNoNw()
        pauseSsidEnabledWireGuardOnNoNw()
        setNetworkAndDefaultDnsIfNeeded(true)
        VpnController.onConnectionStateChanged(null)
    }

    override suspend fun onNetworkChange(networks: ConnectionMonitor.UnderlyingNetworks) {
        withContext(serializer) {
            val prev = underlyingNetworks
            // no need to handle nw change when last updated time is stale
            if ((prev?.lastUpdated ?: 0L) > networks.lastUpdated) {
                Logger.w(LOG_TAG_VPN, "onNetworkChange: ignoring stale network change, prev: ${prev?.lastUpdated}, new: ${networks.lastUpdated}")
                return@withContext
            }

            val prevSize = (prev?.ipv4Net?.size ?: 0) + (prev?.ipv6Net?.size ?: 0)
            val currSize = networks.ipv4Net.size + networks.ipv6Net.size
            // force restart if no networks before or after
            val forceRestart  = (prevSize == 0 && currSize > 0) || (prevSize > 0 && currSize == 0)
            if (currSize > 0) {
                onNetworkConnected(networks, forceRestart)
            } else {
                onNetworkDisconnected(networks, forceRestart)
            }
        }
    }

    override suspend fun onNetworkRegistrationFailed() = withContext(serializer) {
        Logger.i(LOG_TAG_VPN, "recd nw reg failed, stop vpn service with notification")
        signalStopService("nwRegFail", userInitiated = false)
    }

    override suspend fun maybeNetworkStall() {
        // these calls are not fool proof, just a mitigation mechanism
        // see if there is no flow call for 30 seconds and this is called, then restart the vpn
        val elapsed = elapsedRealtime()
        if (elapsed >= lastRxTrafficTime + DATA_STALL_THRESHOLD_MS) {
            Logger.w(LOG_TAG_VPN, "diags; no flow call for 30 seconds, restarting vpn, last: $lastRxTrafficTime")
            val reason = "diags ${elapsed/(10*1000L)}" // restart once in a given 10 sec interval
            vpnRestartTrigger.value = reason
        } else {
            Logger.d(LOG_TAG_VPN, "diags; flow call recd, no restart needed, last: $lastRxTrafficTime")
        }
    }

    private fun getUnderlays(): Array<Network>? {
        val networks = underlyingNetworks
        val failOpen = !persistentState.stallOnNoNetwork
        val setNullOnVpnLockdown = false
        // always check lockdown from isLockdown() as it is the original source of truth
        val currentlyLockdown = isLockdown()
        val mustSetNullOnVpnLockdown = currentlyLockdown && setNullOnVpnLockdown
        if (networks == null) {
            Logger.w(LOG_TAG_VPN, "getUnderlays: null nws; fail-open? $failOpen, must-set-null? $mustSetNullOnVpnLockdown, lockdown? $currentlyLockdown")
            return if (failOpen || mustSetNullOnVpnLockdown) { // failing open on no nw / lockdown
                null // use whichever network is active, whenever it becomes active
            } else {
                emptyArray() // deny all traffic; fail closed
            }
        }

        // underlying networks is set to null, which prompts Android to set it to whatever is
        // the current active network. Later, ConnectionMonitor#onVpnStarted, depending on user
        // chosen preferences, sets appropriate underlying network/s.

        // add ipv4/ipv6 networks to the tunnel
        val allNetworks = networks.ipv4Net.map { it.network } + networks.ipv6Net.map { it.network }
        // remove duplicates, as the same network can be both ipv4 and ipv6
        val distinctNetworks = allNetworks.distinctBy { it.networkHandle }
        val hasUnderlyingNetwork = distinctNetworks.isNotEmpty()
        val underlays = if (hasUnderlyingNetwork) {
            if (networks.useActive) {
                null // null denotes active network
            } else {
                distinctNetworks.toTypedArray() // use all networks
            }
        } else {
            // failing open on no nw / lockdown
            if (failOpen || mustSetNullOnVpnLockdown) {
                null // use whichever network is active, whenever it becomes active
            } else {
                emptyArray() // deny all traffic; fail closed
            }
        }

        Logger.i(
            LOG_TAG_VPN,
            "getUnderlays: use active? ${networks.useActive}; fail-open? $failOpen; lockdown? $mustSetNullOnVpnLockdown; networks: ${underlays?.size}; null-underlay? ${underlays == null}"
        )
        if (!hasUnderlyingNetwork) {
            Logger.w(LOG_TAG_VPN, "getUnderlays: no underlying networks found")
        } else {
            underlays?.forEach {
                Logger.i(
                    LOG_TAG_VPN,
                    "getUnderlays: network: ${it.networkHandle}, netId: ${netid(it.networkHandle)}"
                )
            }
        }
        return underlays
    }

    suspend fun onNetworkConnected(networks: ConnectionMonitor.UnderlyingNetworks, forceRestart: Boolean = false) {
        val curnet = underlyingNetworks
        val out = interestingNetworkChanges(curnet, networks)
        val isRoutesChanged = hasRouteChangedInAutoMode(out)
        val isBoundNetworksChanged = out.netChanged
        val isMtuChanged = out.mtuChanged
        val isSsidChanged = out.ssidChanged
        underlyingNetworks = networks
        underlyingNetworks?.vpnLockdown = isLockdown()

        // always reset the system dns server ip of the active network with the tunnel
        setNetworkAndDefaultDnsIfNeeded(isRoutesChanged || isBoundNetworksChanged)

        val underlyingNws = getUnderlays()
        setUnderlyingNetworks(underlyingNws)
        tunUnderlyingNetworks = underlyingNws?.joinToString()
        var ipv4Ssid = ""
        var ipv6Ssid = ""
        networks.ipv4Net.forEach {
            ipv4Ssid = ipv4Ssid + it.network.networkHandle.toString() + "##" + (it.ssid ?: "")
        }
        networks.ipv6Net.forEach {
            ipv6Ssid = ipv6Ssid + it.network.networkHandle.toString() + "##" + (it.ssid ?: "")
        }

        logd("getNetworkSSID - onNetworkConnected: active: ${networks.activeSsid}, v4: $ipv4Ssid, v6: $ipv6Ssid")
        logd(
            "underlays: ${underlyingNws?.joinToString()}, forceRestart? $forceRestart mtu? $isMtuChanged(o:${curnet?.minMtu}, n:${networks.minMtu}), tun: ${tunMtu()}; routes? $isRoutesChanged, bound-nws? $isBoundNetworksChanged, stall? ${persistentState.stallOnNoNetwork}, updatedTs: ${networks.lastUpdated}"
        )

        // restart vpn if the routes or when mtu changes
        if (isMtuChanged || isRoutesChanged || forceRestart) {
            Logger.i(LOG_TAG_VPN, "$TAG; mtu/routes/force-restart,  restart vpn")
            ioCtx("nwConnect") {
                var reason = "mtu: ${curnet?.minMtu}/${networks.minMtu}, "
                reason += "r: $isRoutesChanged, "
                reason += "nws: ${curnet?.ipv4Net?.size}/${curnet?.ipv6Net?.size} > new: ${networks.ipv4Net.size}/${networks.ipv6Net.size} ($isBoundNetworksChanged), "
                reason += "force: $forceRestart, lock: ${curnet?.vpnLockdown}/${networks.vpnLockdown}, "
                reason += "nwConnect, $reason"
                vpnRestartTrigger.value = reason
                // not needed as the refresh is done in go, TODO: remove below code later
                // only after set links and routes, wg can be refreshed
                // if (isRoutesChanged) {
                // Logger.v(LOG_TAG_VPN, "refresh wg after network change")
                // refreshProxies()
                // }
            }
        }

        // now the proxy need to be either paused/resumed/refreshed/readded
        // so no need to check for isRoutesChanged, even though the routes are same,
        // the bound networks have changed, so either of the above operations are needed
        // case: wireguard in mobile-only mode & ssid change in wifi for ssidEnabled wgs
        if (isBoundNetworksChanged || isSsidChanged) {
            // Workaround for WireGuard connection issues after network change
            // WireGuard may fail to connect to the server when the network changes.
            Logger.i(LOG_TAG_VPN, "$TAG ssid/bound-nws changed, refresh wg if needed")
            refreshOrPauseOrResumeOrReAddProxies() // takes care of adding the proxies if missing in tun
        }

        underlyingNetworks?.ipv4Net?.forEach {
            it.linkProperties?.linkAddresses?.forEach { ips ->
                Logger.i(
                    LOG_TAG_VPN,
                    "IPv4 link Address: ${ips.address.hostAddress}, prefix: ${ips.prefixLength}, flags: ${ips.flags}, scope: ${ips.scope}, all: ${ips}"
                )
            }
        }

        underlyingNetworks?.ipv6Net?.forEach {
            it.linkProperties?.linkAddresses?.forEach { ips ->
                Logger.i(
                    LOG_TAG_VPN,
                    "IPv6 link Address: ${ips.address.hostAddress}, prefix: ${ips.prefixLength}, flags: ${ips.flags}, scope: ${ips.scope}, all: ${ips}"
                )
            }
        }
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

    data class NetworkChanges(
        val routesChanged: Boolean = true,
        val netChanged: Boolean = true,
        val mtuChanged: Boolean = true,
        val ssidChanged: Boolean = true
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

        val useMaxMtu = persistentState.useMaxMtu
        val tunMtu = tunMtu()
        logd(
            "tun: useMaxMtu? $useMaxMtu tunMtu:${tunMtu}; old: ${old.minMtu}, new: ${new.minMtu}; oldaux: ${overlayNetworks.mtu}, newaux: ${aux.mtu}"
        )

        // mark mtu changed if any tunMtu differs from min mtu of new underlying & overlay network
        val mtuChanged = !useMaxMtu && tunMtu != min(new.minMtu, aux.mtu)

        // val auxHas4 = aux.has4 || aux.failOpen
        // val auxHas6 = aux.has6 || aux.failOpen

        val (builderHas4, builderHas6) = builderRoutes // current tunnel routes v4/v6?

        // when the nws are null from the connection monitor, then consider the builder routes
        // as the new routes

        var vpnHas4 = builderHas4
        var vpnHas6 = builderHas6
        if (RECONCILE_WITH_VPN_ROUTES) {
            vpnHas4 = new.vpnRoutes?.first ?: builderHas4
            vpnHas6 = new.vpnRoutes?.second ?: builderHas6
        }

        val n = Networks(new, aux)
        val (tunWants4, tunWants6) = determineRoutes(n)

        // old & new agree on activ capable of routing ipv4 or not
        val ok4 = builderHas4 == tunWants4 && builderHas4 == vpnHas4
        // old & new agree on activ capable of routing ipv6 or not
        val ok6 = builderHas6 == tunWants6 && builderHas6 == vpnHas6

        val routesChanged = !ok4 || !ok6

        logd("tun: has4: $builderHas4, wants4: $tunWants4, vpnHas4: $vpnHas4, has6: $builderHas6, wants6: $tunWants6, vpnHas6: $vpnHas6, routesChanged? $routesChanged, ")

        if (new.useActive) {
            cm.activeNetwork?.let { activ ->
                // val tunWants4 = activHas4 && auxHas4
                // val tunWants6 = activHas6 && auxHas6
                val activHas4 = isNetworkSame(new.ipv4Net.firstOrNull()?.network, activ)
                val activHas6 = isNetworkSame(new.ipv6Net.firstOrNull()?.network, activ)
                val oldActivHas4 = isNetworkSame(old.ipv4Net.firstOrNull()?.network, activ)
                val oldActivHas6 = isNetworkSame(old.ipv6Net.firstOrNull()?.network, activ)
                val okActiv4 = oldActivHas4 == activHas4 // routing for ipv4 is same in old and new FIRST network
                val okActiv6 = oldActivHas6 == activHas6 // routing for ipv6 is same in old and new FIRST network
                val netChanged = !okActiv4 || !okActiv6

                val ssidChanged = old.activeSsid != new.activeSsid
                logd("tun: oldActiv4: $oldActivHas4, newActiv4: $activHas4, oldActiv6: $oldActivHas6, newActiv6: $activHas6, netChanged? $netChanged")
                logd("tun: oldActiveSsid: ${old.activeSsid}, newActiveSsid: ${new.activeSsid}, ssidChanged? $ssidChanged")
                // for active networks, changes in routes includes all possible network changes;
                return NetworkChanges(routesChanged, netChanged, mtuChanged, ssidChanged)
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
        val netChanged = !isNetworkSame(oldFirst6, newFirst6) || !isNetworkSame(oldFirst4, newFirst4)

        val oldSsidFirst4 = old.ipv4Net.firstOrNull()?.ssid
        val newSsidFirst4 = new.ipv4Net.firstOrNull()?.ssid
        val oldSsidFirst6 = old.ipv6Net.firstOrNull()?.ssid
        val newSsidFirst6 = new.ipv6Net.firstOrNull()?.ssid
        val ssidChanged = oldSsidFirst4 != newSsidFirst4 || oldSsidFirst6 != newSsidFirst6

        logd("tun: oldFirst4: $oldFirst4, newFirst4: $newFirst4, oldFirst6: $oldFirst6, newFirst6: $newFirst6, netChanged? $netChanged")
        logd("tun: oldSsidFirst4: $oldSsidFirst4, newSsidFirst4: $newSsidFirst4, oldSsidFirst6: $oldSsidFirst6, newSsidFirst6: $newSsidFirst6, ssidChanged? $ssidChanged")
        return NetworkChanges(routesChanged, netChanged, mtuChanged, ssidChanged)
    }

    private suspend fun setNetworkAndDefaultDnsIfNeeded(forceUpdate: Boolean = false) {
        val ctx = this
        withContext(serializer) {
            val currNet = underlyingNetworks
            // get dns servers from the first network or active network
            val active = cm.activeNetwork
            val dnsServers: MutableSet<InetAddress> =
            if (cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                Logger.i(LOG_TAG_VPN, "active network is vpn, so no need get dns servers")
                mutableSetOf()
            } else {
                val lp = cm.getLinkProperties(active)
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
                            ctx,
                            getString(R.string.system_dns_connection_failure),
                            Toast.LENGTH_LONG
                        )
                    }
                } else {
                    // no-op
                }
            }
            io("setSystemAndDefaultDns") {
                // ref: kotlinlang.org/docs/equality.html#structural-equality
                val same = dnsServers == prevDns

                Logger.i(
                    LOG_TAG_VPN,
                    "dns: $dnsServers, existing: $prevDns, force: $forceUpdate, same? $same"
                )
                if (same && !forceUpdate) {
                    return@io
                }
                // set system dns whenever there is a change in network
                prevDns.clear()
                prevDns.addAll(dnsServers)
                val dns = dnsServers.map { it.hostAddress }
                vpnAdapter?.setSystemDns(dns)
                // set default dns server for the tunnel if none is set
                if (isDefaultDnsNone()) {
                    val dnsCsv = dns.joinToString(",")
                    vpnAdapter?.addDefaultTransport(dnsCsv)
                }

                val id = if (appConfig.isSmartDnsEnabled()) Backend.Plus else Backend.Preferred
                val mainDnsOK = vpnAdapter?.getDnsStatus(id) != null
                Logger.i(LOG_TAG_VPN, "preferred/plus set? ${mainDnsOK}, if not set it again")

                if (!mainDnsOK) {
                    vpnAdapter?.addTransport()
                }
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

        Logger.i(LOG_TAG_VPN, "vpn lockdown mode change, restarting")
        io("lockdownSync") {
            val reason = "lockdown: ${VpnController.isVpnLockdown()}"
            vpnRestartTrigger.value = reason
            vpnAdapter?.notifyLoopback()
        }
    }

    private fun syncLockdownState(): Boolean {
        if (!isAtleastQ()) return false

        val curr = isLockdownEnabled
        // cannot set the lockdown status while the vpn is being created, it will return false
        // until the vpn is created. so the sync will be done after the vpn is created
        // when the first flow call is made.
        val prev = isLockDownPrevious.get()

        if (curr == prev) {
            underlyingNetworks?.vpnLockdown = prev
            return false
        }

        val set = isLockDownPrevious.compareAndSet(prev, curr)
        if (set) {
            underlyingNetworks?.vpnLockdown = curr
        }
        return set
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
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
            unregisterUserPresentReceiver()
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
        io("cmVpnStop") { connectionMonitor.onVpnStop() }
        VpnController.onVpnDestroyed()
        // stop the inapp billing handler if it exists
        //InAppBillingHandler.endConnection()
        try {
            // this will also cancels the restarter state flow
            vpnScope.cancel("vpnDestroy")
        } catch (_: IllegalStateException) {
        } catch (_: CancellationException) {
        } catch (_: Exception) { }

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
        val reason = if (isAppPaused()) "pause" else "resume"
        vpnRestartTrigger.value = reason
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
            val failOpen = !persistentState.stallOnNoNetwork
            // no route available for both v4 and v6, add all routes
            // connectivity manager is expected to retry when no route is available
            // see ConnectionMonitor#repopulateTrackedNetworks
            Logger.i(LOG_TAG_VPN, "No routes, fail-open? $failOpen")
            has4 = failOpen
            has6 = failOpen
        } else {
            Logger.i(LOG_TAG_VPN, "Building vpn for v4? $has4, v6? $has6")
        }

        return Pair(has4, has6)
    }

    private suspend fun establishVpn(networks: Networks, mtu: Int): ParcelFileDescriptor? {
        try {
            val s = StringBuilder()
            val pendingIntent =
                Utilities.getActivityPendingIntent(
                    this,
                    Intent(this, AppLockActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    mutable = false
                )

            // val mtu = mtu() // get mtu from the underlyingnetworks
            var builder: Builder = newBuilder().setSession("Rethink").setMtu(mtu)

            // set the PendingIntent to an activity for users to configure the vpn connection.
            // if it is not set, the button to configure will not be shown in system-managed dialogs
            builder.setConfigureIntent(pendingIntent)

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
            // builder.setBlocking(false)

            Logger.i(
                LOG_TAG_VPN,
                "$TAG; establish vpn, mtu: $mtu, has4: $has4, has6: $has6, noRoutes: $noRoutes, dnsMode? $dnsMode, firewallMode? $firewallMode"
            )

            s.append("mtu: $mtu\n   has4: $has4\n   has6: $has6\n   noRoutes: $noRoutes\n   dnsMode? $dnsMode\n   firewallMode? $firewallMode")
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

            InternetProtocol.ALWAYSv46 -> {
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
                logd("r6: underlay: ${underlay?.useActive}, ${underlay?.ipv6Net?.size}")
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
                    val activeNetwork = cm.activeNetwork

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

    private suspend fun onOverlayNetworkChanged(nw: OverlayNetworks) =
        withContext(CoroutineName("ovch") + serializer) {
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
                val reason = "overlayNwChanged, routes: $isRoutesChanged, mtu: $isMtuChanged, at: ${elapsedRealtime()}"
                vpnRestartTrigger.value = reason
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

            InternetProtocol.ALWAYSv46 -> {
                true
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
                    val activeNetwork = cm.activeNetwork
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

    private fun getAddresses(): String {
        val ipv4 = IPAddressString("${LanIp.GATEWAY.make(IPV4_TEMPLATE)}/$IPV4_PREFIX_LENGTH")
        val ipv6 = IPAddressString("${LanIp.GATEWAY.make(IPV6_TEMPLATE)}/$IPV6_PREFIX_LENGTH")
        return "${ipv4.address.toNormalizedString()},${ipv6.address.toNormalizedString()}"
    }

    private fun <T> go2kt(co: CoFactory<T>, f: suspend() -> T): T = runBlocking {
        // runBlocking blocks the current thread until all coroutines within it are complete
        // an call a suspending function from a non-suspending context and obtain the result.
        return@runBlocking co.tryDispatch(f)
    }

    private suspend fun ioCtx(s: String, f: suspend () -> Unit) =
        withContext(CoroutineName(s) + Dispatchers.IO) { f() }


    private fun io(s: String, f: suspend () -> Unit) =
        vpnScope.launch(CoroutineName(s) + Dispatchers.IO) { f() }

    private fun ui(f: suspend () -> Unit) = vpnScope.launch(Dispatchers.Main) { f() }

    private suspend fun uiCtx(s: String, f: suspend () -> Unit) =
        withContext(CoroutineName(s) + Dispatchers.Main) { f() }

    private suspend fun <T> ioAsync(s: String, f: suspend () -> T): Deferred<T> {
        return vpnScope.async(CoroutineName(s) + Dispatchers.IO) { f() }
    }

    override fun onQuery(uidGostr: Gostr?, qdn: Gostr?, qtype: Long): DNSOpts = go2kt(dnsQueryDispatcher) {
        val fqdn: String? = qdn?.tos() ?: ""
        val uidStr = uidGostr?.tos() ?: ""
        var result: DNSOpts? = null
        val timeTaken = measureTime {
            // TODO: if uid is received, then make sure Rethink uid always returns Default as transport
            var uid: Int = INVALID_UID
            try {
                uid = when (uidStr) {
                    Backend.UidSelf, rethinkUid.toString() -> {
                        rethinkUid
                    }
                    Backend.UidSystem -> {
                        AndroidUidConfig.SYSTEM.uid // 1000
                    }
                    else -> {
                        uidStr.toInt()
                    }
                }
            } catch (_: NumberFormatException) {
                Logger.w(LOG_TAG_VPN, "onQuery: invalid uid: $uidStr, using default $uid")
            }

            // queryType: see ResourceRecordTypes.kt
            logd("onQuery: rcvd uid: $uid query: $fqdn, qtype: $qtype")
            if (fqdn == null) {
                Logger.e(
                    LOG_TAG_VPN,
                    "onQuery: fqdn is null, uid: $uid, returning ${Backend.BlockAll}"
                )
                // return block all, as it is not expected to reach here
                result = makeNsOpts(uid, Pair(Backend.BlockAll, ""), domain = "")
                return@measureTime
            }

            val appMode = appConfig.getBraveMode()
            if (appMode.isDnsMode()) {
                result = getTransportIdForDnsMode(uid, fqdn)
                logd("onQuery (Dns):$fqdn, dnsx: $result")
                return@measureTime
            }

            if (appMode.isDnsFirewallMode()) {
                result =  getTransportIdForDnsFirewallMode(uid, fqdn)
                logd("onQuery (Dns+Firewall):$fqdn, dnsx: $result")
                return@measureTime
            }

            // all other dns are added with id as Preferred, but SmartDns is added with Plus
            // so treat it as a special case, change this when the Preferred mode is removed
            val tid = if (appConfig.isSmartDnsEnabled()) {
                Backend.Plus
            } else {
                Backend.Preferred
            }
            result = makeNsOpts(uid, Pair(tid, ""), fqdn) // should not reach here
            Logger.e(LOG_TAG_VPN, "onQuery: unknown mode ${appMode}, $fqdn, returning $result")
            return@measureTime
        }
        Logger.vv(
            LOG_TAG_VPN,
            "$TAG onQuery: $fqdn, uid: $uidStr, qtype: $qtype, time taken: ${timeTaken.inWholeMilliseconds} ms"
        )
        // log the time taken for the query; result should not be null in any case
        return@go2kt result ?: makeNsOpts(INVALID_UID, Pair(Backend.BlockAll, ""), fqdn ?: "")
    }

    private fun getTransportIdToBypass(id: Pair<String, String>): Pair<String, String> {
        // determines whether fallback DNS should be used for trusted domains or IPs.
        // this must be called before using Backend.BlockFree. in rethink’s dns case, BlockFree
        // transport is added to tun so calling this method is not required.
        return if (persistentState.useFallbackDnsToBypass) { // setting to use fallback dns
            Pair(Backend.BlockFree, "")
        } else {
            id
        }
    }

    // function to decide which transport id to return on Dns only mode
    private suspend fun getTransportIdForDnsMode(uid: Int, fqdn: String): DNSOpts {
        // useFixedTransport is false in Dns only mode
        val tid = determineDnsTransportIdForDnsMode()

        if (uid == rethinkUid) {
            // no need to check for domain rules for rethink uid, can be added in the future
            return makeNsOpts(uid, tid, fqdn)
        }

        // consider app specific domain rules even in dns only mode, domain based firewall
        // is now supported in dns only mode
        // either the android version is less than Q or the OEM preferred not to set uid
        // in the DNS.uid (AID_DNS) field
        if (uid != INVALID_UID) {
            when (DomainRulesManager.getDomainRule(fqdn, uid)) {
                DomainRulesManager.Status.TRUST -> return makeNsOpts(uid, getTransportIdToBypass(tid), fqdn, true)
                DomainRulesManager.Status.BLOCK -> return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false)
                else -> {} // no-op, fall-through;
            }
        }

        // check for global domain rules
        when (DomainRulesManager.getDomainRule(fqdn, UID_EVERYBODY)) {
            DomainRulesManager.Status.TRUST -> return makeNsOpts(uid, getTransportIdToBypass(tid), fqdn, true)
            DomainRulesManager.Status.BLOCK -> return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false)
            else -> {} // no-op, fall-through;
        }

        return makeNsOpts(uid, tid, fqdn)
    }

    // function to decide which transport id to return on DnsFirewall mode
    private suspend fun getTransportIdForDnsFirewallMode(uid: Int, fqdn: String): DNSOpts {
        val splitDns = persistentState.splitDns && WireguardManager.isAdvancedWgActive()
        val tid = determineDnsTransportIdForDFMode(uid, fqdn, splitDns)
        val forceBypassLocalBlocklists = isAppPaused() && isLockdown()

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
        // requires alg to be enabled, as the go-tun adds the secondary ip (blocked by upstream).
        // so check if alg is enabled along with bypassBlockInDns
        if (persistentState.bypassBlockInDns && persistentState.enableDnsAlg) {
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
            } else if (isUidPresentInAnyDnsRequest && persistentState.getBlockUnknownConnections()) {
                // universal block for unknown uids if the setting is enabled
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: no uid, block unknown connections $fqdn")
                makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
            } else {
                // no global rule, no app-wise trust, return the tid as it is
                Logger.vv(LOG_TAG_VPN, "$TAG; onQuery: no uid, no global rule, use $tid for $fqdn")
                makeNsOpts(uid, tid, fqdn)
            }
        } else {
            if (!isUidPresentInAnyDnsRequest && uid != AndroidUidConfig.DNS.uid) {
                isUidPresentInAnyDnsRequest = true
                Logger.i(LOG_TAG_VPN, "$TAG; onQuery: uid present in dns request")
            }
            val connectionStatus = FirewallManager.connectionStatus(uid)
            if (connectionStatus.blocked()) {
                // if the app is blocked by both wifi and mobile data, then the block the request
                // block wifi/metered decision can be made by in flow() function
                Logger.vv(LOG_TAG_VPN, "$TAG onQuery, $uid is blocked, $fqdn")
                return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
            }

            val appStatus = FirewallManager.appStatus(uid)
            if (appStatus.bypassDnsFirewall()) {
                // in case of bypass dns, bypass the local blocklists and set only block-free
                Logger.vv(LOG_TAG_VPN, "$TAG onQuery, $uid bypasses dns+firewall, $fqdn")
                return makeNsOpts(uid, getTransportIdToBypass(tid), fqdn, true)
            }

            val appDomainRule =  getDomainRule(fqdn, uid)
            when (appDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG onQuery, $uid, domain trusted: $fqdn")
                    return makeNsOpts(uid, getTransportIdToBypass(tid), fqdn, true)
                }
                DomainRulesManager.Status.BLOCK -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG onQuery, $uid, domain blocked: $fqdn")
                    return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
                }
                DomainRulesManager.Status.NONE -> {
                    // no-op, fall through, check for global rules
                }
            }

            val globalDomainRule = getDomainRule(fqdn, UID_EVERYBODY)
            when (globalDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG onQuery, $uid, univ domain trusted: $fqdn")
                    return makeNsOpts(uid, getTransportIdToBypass(tid), fqdn, true)
                }
                DomainRulesManager.Status.BLOCK -> {
                    Logger.vv(LOG_TAG_VPN, "$TAG onQuery, $uid, univ domain blocked: $fqdn")
                    return makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn)
                }
                DomainRulesManager.Status.NONE -> {
                    // no-op, fall through, return the tid as it is
                }
            }

            Logger.vv(LOG_TAG_VPN, "$TAG onQuery, $uid, no rules, use $tid for $fqdn")
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
        val tid = if (appConfig.isSystemDns() || (isAppPaused() && isLockdown())) {
            // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
            // as if the apps are excluded from vpn
            Backend.System
        } else if (appConfig.isSmartDnsEnabled()) {
            // if smart dns is enabled, use plus transport id
            Backend.Plus
        } else {
            Backend.Preferred
        }
        return Pair(tid, "")
    }

    private suspend fun determineDnsTransportIdForDFMode(uid: Int, domain: String, splitDns: Boolean): Pair<String, String> {
        val defaultTid =
            if (appConfig.isSystemDns() || (isAppPaused() && isLockdown())) {
                // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
                // as if the apps are excluded from vpn
                Backend.System
            } else if (appConfig.isSmartDnsEnabled()) {
                // if smart dns is enabled, use plus transport id
                Backend.Plus
            } else {
                Backend.Preferred
            }

        if (uid == rethinkUid) {
            val id = Backend.Default
            /*if (RpnProxyManager.isRpnActive()) {
                // use Plus only for rethink when rpn is active
                appendDnsCacheIfNeeded(Backend.Plus)
            } else {
                // set default transport to rethink's uid, as no rules or proxy will be applied
                Backend.Default
            }*/
            Logger.d(LOG_TAG_VPN, "(onQuery)rethink's uid using $id")
            return Pair(id, "")
        }

        if (uid == INVALID_UID) {
            val oneWgId = WireguardManager.getOneWireGuardProxyId()
            val tid = if (oneWgId != null) {
                ID_WG_BASE + oneWgId
            } else {
                defaultTid
            }
            return if (splitDns) {
                // in case of split dns, append Fixed to the tid when there is no uid
                // this synthesizes A/AAAA from a single fixed IP
                Pair(tid, Backend.Fixed)
            } else {
                Pair(tid, "")
            }
        } else {
            if (!splitDns && !WireguardManager.oneWireGuardEnabled()) {
                Logger.d(LOG_TAG_VPN, "(onQuery)no split dns, using $defaultTid")
                return Pair(defaultTid, "")
            }
            if (FirewallManager.isAppExcludedFromProxy(uid)) {
                return Pair(defaultTid, "")
            }
            // only when there is an uid, we need to calculate wireguard ids
            // gives all the possible wgs for the app regardless of usesMobileNetwork
            val ssid = getUnderlyingSsid() ?: ""
            val ids = WireguardManager.getAllPossibleConfigIdsForApp(uid, ip = "", port = 0, domain, true, ssid, defaultTid)
            val wgOrDefaultTid = if (ids.isEmpty()) {
                Logger.d(LOG_TAG_VPN, "(onQuery-pid)no wg found, return $defaultTid")
                defaultTid
            } else {
                Logger.d(LOG_TAG_VPN, "(onQuery-pid)wg ids($ids) found for uid: $uid")
                ids.joinToString(",")
            }
            Logger.d(LOG_TAG_VPN, "(onQuery)wg ids($ids) found for uid: $uid")
            return Pair(wgOrDefaultTid, "")
        }
    }

    private fun getUnderlyingSsid(): String? {
        return underlyingNetworks?.activeSsid ?: underlyingNetworks?.ipv4Net?.firstOrNull { it.ssid != null }?.ssid ?: underlyingNetworks?.ipv6Net?.firstOrNull() { it.ssid != null }?.ssid
    }

    private suspend fun makeNsOpts(
        uid: Int,
        tid: Pair<String, String>,
        domain: String,
        bypassLocalBlocklists: Boolean = false
    ): DNSOpts {
        val opts = DNSOpts()
        opts.ipcsv = "" // as of now, no suggested ips
        // add CT for all primary and sec transport ids if dns cache is enabled regardless of
        // tids. Go will remove if its not applicable for that transport id.
        val tidCsv = tid.first.split(",").joinToString(",") { appendDnsCacheIfNeeded(it) }
        opts.tidcsv = tidCsv
        // tid.second can be empty
        val secCsv = if (tid.second.isNotEmpty()) tid.second.split(",").joinToString(",") { appendDnsCacheIfNeeded(it) } else ""
        opts.tidseccsv = secCsv

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
            val tr1 = Backend.BlockFree
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
        // NOTE: when the transport id is set to default/Auto then the proxyId are ignored
        // in tunnel. so no need to set the proxyId in that case, as of now we are not
        // handling the case when the transport id is set to default/Auto. the proxyId
        // calculation is done
        // TODO: check if the transport id is set to default/Auto, then return empty string/base

        // in case of rinr mode, use only base even if auto is enabled
        // use auto only in non-rinr mode and if plus is subscribed
        val defaultProxy = when {
            persistentState.routeRethinkInRethink -> Backend.Base
            // if rpn is active, then use auto
            //RpnProxyManager.isRpnActive() -> getRpnIds()
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
        } else {
            // if the enabled wireguard is catchall-wireguard, then return wireguard id
            val ssid = underlyingNetworks?.activeSsid ?: underlyingNetworks?.ipv4Net?.firstOrNull { it.ssid != null }?.ssid ?: underlyingNetworks?.ipv6Net?.firstOrNull() { it.ssid != null }?.ssid ?: ""
            val ids = WireguardManager.getAllPossibleConfigIdsForApp(
                uid,
                ip = "",
                port = 0,
                domain,
                true,
                ssid,
                defaultProxy
            )
            if (ids.isEmpty()) {
                Logger.d(LOG_TAG_VPN, "(onQuery-pid)no wg found, return $defaultProxy")
                defaultProxy
            } else {
                Logger.d(LOG_TAG_VPN, "(onQuery-pid)wg ids($ids) found for uid: $uid")
                ids.joinToString(",")
            }
        }
    }

   /* private fun getRpnIds(): String {
        // not needed as caller is already checking for rpn active
        if (!RpnProxyManager.isRpnActive()) return ""

        val mode = rpnMode()
        val ids = RpnMode.getPreferredId(mode.id)
        Logger.vv(LOG_TAG_VPN, "getRpnIds; state:${RpnProxyManager.rpnState().name}, mode: ${mode.name}, ids: $ids")
        return ids
    }*/

    override fun onResponse(summary: DNSSummary?) {
        if (summary == null) {
            Logger.i(LOG_TAG_VPN, "received null summary for dns")
            return
        }
        logd("onResponse: $summary")
        if (!DEBUG) {
            // not expected to have fixed id in the summary on production builds
            if (summary.id.contains(Backend.Fixed)) {
                return
            }
        }
        netLogTracker.processDnsLog(summary, rethinkUid)
        setRegionLiveDataIfRequired(summary)
    }

    private fun setRegionLiveDataIfRequired(summary: DNSSummary) {
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

    override fun onProxyAdded(id: Gostr?): Unit = go2kt(proxyAddedDispatcher) {
        val iid = id.tos()
        if (iid == null) {
            Logger.e(LOG_TAG_VPN, "onProxyAdded: received null id")
            return@go2kt
        }

        if (!iid.contains(ID_WG_BASE, true)) {
            // only wireguard proxies are considered for overlay network
            logd("onProxyAdded: no-op as it is not wireguard proxy, added $iid")
            return@go2kt
        }

        // new proxy added, refresh overlay network pair
        io("onProxyAdded") {
            val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
            logd("onProxyAdded for proxy $iid: $nw")
            onOverlayNetworkChanged(nw ?: OverlayNetworks())
        }
        val id = iid.substringAfter(ID_WG_BASE).toIntOrNull() ?: return@go2kt
        val files = WireguardManager.getConfigFilesById(id) ?: return@go2kt

        if (!files.useOnlyOnMetered || files.oneWireGuard) return@go2kt
        withContext(CoroutineName("onProxyAdded") + serializer) {
            val newNet = underlyingNetworks
            val v4first = newNet?.ipv4Net?.firstOrNull()
            val v6first = newNet?.ipv6Net?.firstOrNull()
            val v4Mobile = v4first?.capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
            val v6Mobile = v6first?.capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
            val isActiveMobile = v4Mobile || (v4first == null && v6Mobile)
            Logger.i(LOG_TAG_VPN, "onProxyAdded: wg proxy $iid is added, isActiveMobile: $isActiveMobile")
        }
    }

    override fun onProxyRemoved(id: Gostr?) {
        val iid = id.tos()
        if (iid == null) {
            Logger.e(LOG_TAG_VPN, "onProxyAdded: received null id")
            return
        }

        if (!iid.contains(ID_WG_BASE)) {
            // only wireguard proxies are considered for overlay network
            logd("onProxyRemoved: proxy removed $iid, not wireguard")
            return
        }
        // proxy removed, refresh overlay network pair
        io("onProxyRemoved") {
            val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
            logd("onProxyRemoved for proxy $iid: $nw")
            onOverlayNetworkChanged(nw ?: OverlayNetworks())
        }
    }

    override fun onProxyStopped(id: Gostr?) {
        // no-op
        Logger.v(LOG_TAG_VPN, "onProxyStopped: ${id.tos()}")
    }

    override fun onDNSAdded(id: Gostr?) {
        // no-op
        Logger.v(LOG_TAG_VPN, "onDNSAdded: ${id.tos()}")
    }

    override fun onDNSRemoved(id: Gostr?) {
        // no-op
        Logger.v(LOG_TAG_VPN, "onDNSRemoved: ${id.tos()}")
    }

    override fun onDNSStopped() {
        // no-op
        Logger.v(LOG_TAG_VPN, "onDNSStopped")
    }

    override fun onSvcComplete(p0: ServerSummary) {
        // no-op
    }

    override fun onUpstreamAnswer(smm: DNSSummary?, ipcsv: Gostr?): DNSOpts? {
        // no-op
        if (DEBUG) logd("onUpstreamAnswer: $smm, ipcsv: ${ipcsv.tos()}")
        return null
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
    override fun log(level: Int, m: Gostr?) {
        val msg = m?.tos() ?: return

        if (msg.isEmpty()) return

        val l = Logger.LoggerLevel.fromId(level)
        if (l.stacktrace()) {
            // disable crash logging for now
            if (false) Logger.crash(LOG_GO_LOGGER, msg) // write to in-mem db
            if (!isFdroidFlavour()) CrashReporter.recordGoCrash(msg)
            val token = if (isFdroidFlavour()) "fdroid" else persistentState.firebaseUserToken
            EnhancedBugReport.writeLogsToFile(this, token, msg)
        } else if (l.user()) {
            showNwEngineNotification(msg)
            // consider all the notifications from go as failure and stop the service
            signalStopService("goNotif", userInitiated = false)
        } else {
            Logger.goLog(msg, l)
        }
    }

    private fun showNwEngineNotification(msg: String) {
        if (msg.isEmpty()) {
            Logger.e(LOG_GO_LOGGER, "empty msg with log level set as user")
            return
        }

        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, AppLockActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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

    // no need of go2kt here as it is called from go and just performs db operations
    // requires go2kt if there any calls to go functions
    override fun onSocketClosed(s: SocketSummary?) {
        if (s == null) {
            Logger.i(LOG_TAG_VPN, "received null summary for socket")
            return
        }

        if (s.id.isEmpty()) { // this should not happen, but just in case
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
                    proxyDetails = s.pid,
                    "",
                    s.target,
                    cid,
                    ConnectionTracker.ConnType.UNMETERED,
                )
            netLogTracker.writeIpLog(cm)
            return
        }

        // TODO: convert the duration obj to long, this is work around
        val durationSec = (s.duration / 1000).toInt()
        val isNotLocalProxy = isNotLocalAndRpnProxy(s.pid)

        val cm = getConnTrackerMetaData(s.id)
        if (cm != null) { // if the connection metadata is already tracked, insert with summary
            var proxyRule = ""
            if (s.pid.isNotEmpty() && isNotLocalProxy) {
                proxyRule = FirewallRuleset.RULE12.id
            }

            val isRethink = cm.uid == rethinkUid
            cm.proxyDetails = s.pid
            cm.rpid = s.rpid
            cm.downloadBytes = s.rx
            cm.uploadBytes = s.tx
            cm.duration = durationSec
            cm.synack = s.rtt
            cm.message = s.msg
            cm.destIP = s.target
            cm.isBlocked = if (proxyRule.isEmpty()) true else cm.isBlocked
            cm.blockedByRule = proxyRule.ifEmpty { FirewallRuleset.RULE18.id }
            logd("onSocketClosed-flow/postflow: $s, pid: ${s.pid.isNullOrEmpty()}, cm: $cm")
            if (isRethink) {
                netLogTracker.writeRethinkLog(cm)
            } else {
                netLogTracker.writeIpLog(cm)
            }
            return // no need to proceed further as no need to update the summary
        }

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

        // note the last time when there is a connection update with download traffic
        // useful to detect data stalls
        if (s.rx  > 0) {
            lastRxTrafficTime = elapsedRealtime()
        }

        try {
            if (s.uid == Backend.UidSelf || s.uid == rethinkUid.toString()) {
                // update rethink summary
                val key = CidKey(connectionSummary.connId, rethinkUid)
                synchronized(activeCids) {
                    activeCids.remove(key)
                }
                netLogTracker.updateRethinkSummary(connectionSummary)
                synchronized(activeClosableCids) {
                    activeClosableCids.remove(connectionSummary.connId)
                }
            } else {
                // other apps summary
                // convert the uid to app id
                val uid = FirewallManager.appId(s.uid.toInt(), isPrimaryUser())
                val key = CidKey(connectionSummary.connId, uid)
                synchronized(activeCids) {
                    activeCids.remove(key)
                }
                netLogTracker.updateIpSummary(connectionSummary)
                synchronized(activeClosableCids) {
                    activeClosableCids.remove(connectionSummary.connId)
                }
            }
            io("dlIpInfo") {
                IpInfoDownloader.fetchIpInfoIfRequired(s.target)
            }
        } catch (e: NumberFormatException) {
            Logger.e(LOG_TAG_VPN, "onSocketClosed: ${e.message}", e)
        }
    }

    private fun getConnTrackerMetaData(cid: String): ConnTrackerMetaData? {
        val cm = trackedConnMetaData.getIfPresent(cid)
        trackedConnMetaData.invalidate(cid)
        return cm
    }

    fun parseIpAndPort(endpoint: String?): Pair<String, Int> {
        if (endpoint.isNullOrBlank()) return "" to 0

        val trimmed = endpoint.trim()

        // handle bracketed IPv6: [2001:db8::1]:443
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            val endBracket = trimmed.indexOf(']')
            val ipPart = trimmed.substring(1, endBracket)
            val portPart = trimmed.substring(endBracket + 1).removePrefix(":")
            val port = portPart.toIntOrNull() ?: 0
            return ipPart to port
        }

        // handle unbracketed IPv6 with port, e.g. 2001:db8::1:443
        // we assume the last colon separates port, but IPv6 can contain many colons.
        // so, only treat the last part as port if it's numeric.
        val lastColonIndex = trimmed.lastIndexOf(':')
        if (lastColonIndex > 0) {
            val potentialPort = trimmed.substring(lastColonIndex + 1)
            if (potentialPort.toIntOrNull() != null) {
                val ipPart = trimmed.substring(0, lastColonIndex)
                // avoid cutting an IPv6 without port (like 2001:db8::1)
                val colonCount = ipPart.count { it == ':' }
                val ip = if (colonCount >= 2) ipPart else trimmed
                val port = if (colonCount >= 2) potentialPort.toIntOrNull() ?: 0 else 0
                if (colonCount >= 2) return ip to port
            }
        }

        // handle IPv4 or hostname with port, e.g. 10.0.0.1:53
        val parts = trimmed.split(":")
        if (parts.size == 2 && parts[1].toIntOrNull() != null) {
            return parts[0] to (parts[1].toIntOrNull() ?: 0)
        }

        // use hostName parser for complex cases
        return try {
            val host = HostName(trimmed)
            val ip = host.asAddress()?.toString() ?: trimmed
            val port = host.port ?: 0
            ip to port
        } catch (_: Exception) {
            trimmed to 0
        }
    }


    override fun preflow(
        protocol: Int,
        uid: Int,
        src: Gostr?,
        dst: Gostr?
    ): PreMark = go2kt(preflowDispatcher) {
        val srcIpPort = parseIpAndPort(src.tos())
        val dstIpPort = parseIpAndPort(dst.tos())
        Logger.d(LOG_TAG_VPN, "preflow - init: $uid, rcvd: $src & $dst, parsed: $srcIpPort & $dstIpPort")
        val newUid = if (uid == INVALID_UID) { // fetch uid only if it is invalid
            getUid(
                uid,
                protocol,
                srcIpPort.first,
                srcIpPort.second,
                dstIpPort.first,
                dstIpPort.second
            )
        } else {
            uid
        }
        Logger.d(LOG_TAG_VPN, "preflow: $newUid, $srcIpPort, $dstIpPort")

        val p = PreMark()
        p.uid = newUid.toString()
        p.isUidSelf = newUid == rethinkUid
        Logger.i(LOG_TAG_VPN, "preflow: returning ${p.uid} for src: $srcIpPort, dst: $dstIpPort, isRethink? ${p.isUidSelf}")
        return@go2kt p
    }

    override fun flow(
        protocol: Int,
        _uid: Int,
        src: Gostr?,
        dst: Gostr?,
        realIps: Gostr?,
        d: Gostr?,
        possibleDomains: Gostr?,
        blocklists: Gostr?
    ): Mark = go2kt(flowDispatcher) {
        logd("flow: $_uid, $src, $dst, $realIps, $d, $blocklists")
        handleVpnLockdownStateAsync()

        // in case of double loopback, all traffic will be part of rinr instead of just rethink's
        // own traffic. flip the doubleLoopback flag to true if we need that behavior
        val doubleLoopback = false

        val srcIpPort = parseIpAndPort(src.tos())
        val dstIpPort = parseIpAndPort(dst.tos())
        val srcIp = srcIpPort.first
        val srcPort = srcIpPort.second
        val dstIp = dstIpPort.first
        val dstPort = dstIpPort.second

        val ips = realIps.tos()?.split(",") ?: emptyList()
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
            dstPort
        )
        uid = FirewallManager.appId(uid, isPrimaryUser())
        val userId = FirewallManager.userId(uid)

        // generates a random 8-byte value, converts it to hexadecimal, and then
        // provides the hexadecimal value as a string for connId
        val connId = Utilities.getRandomString(8)

        // TODO: handle multiple domains, for now, use the first domain
        val domains = d.tos()?.split(",") ?: emptyList()

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
                blocklists.tos() ?: "",
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
                cm.query = possibleDomains.tos()?.split(",")?.firstOrNull() ?: ""
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
                    synchronized(activeCids) {
                        activeCids.add(key)
                    }
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
        processFirewallRequest(cm, anyRealIpBlocked, blocklists.tos() ?: "", isSplApp)

        if (cm.isBlocked) {
            // return Ipn.Block, no need to check for other rules
            logd("flow: received rule: block, returning Ipn.Block, $connId, $uid")
            return@go2kt persistAndConstructFlowResponse(cm, Backend.Block, connId, uid)
        }

        // add to trackedCids, so that the connection can be removed from the list when the
        // connection is closed (onSocketClosed), use: ui to show the active connections
        val key = CidKey(cm.connId, uid)
        synchronized(activeCids) {
            activeCids.add(key)
        }

        return@go2kt determineProxyDetails(cm, doubleLoopback)
    }

    override fun inflow(protocol: Int, recvdUid: Int, src: Gostr?, dst: Gostr?): Mark =
        go2kt(inflowDispatcher) {
            val srcIpPort = parseIpAndPort(src.tos())
            val dstIpPort = parseIpAndPort(dst.tos())
            val srcIp = srcIpPort.first
            val srcPort = srcIpPort.second
            val dstIp = dstIpPort.first
            val dstPort = dstIpPort.second

            var uid = getUid(
                recvdUid,
                protocol,
                srcIp,
                srcPort,
                dstIp,
                dstPort
            )
            uid = FirewallManager.appId(uid, isPrimaryUser())
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
            synchronized(activeCids) {
                activeCids.add(key)
            }

            logd("inflow: determine proxy and other dtls for $connId, $uid")

            // the proxy id (other than block) will be ignored by the go code, so use
            // Backend.Ingress as a placeholder
            return@go2kt persistAndConstructFlowResponse(cm, Backend.Ingress, connId, uid)
        }

    // no need of go2kt here as it is called from go and performs db operations with no return value
    // requires go2kt if there any calls to go functions
    override fun postFlow(m: Mark?) {
        val mark = m
        if (mark == null) {
            Logger.e(LOG_TAG_VPN, "postFlow: received null mark")
            return
        }
        val cm = getConnTrackerMetaData(m.cid)
        if (cm == null) {
            Logger.w(LOG_TAG_VPN, "postFlow: no connection metadata found for mark: $mark")
            return
        }

        val isRethink = cm.uid == rethinkUid
        cm.proxyDetails = mark.pidcsv
        cm.destIP = mark.ip
        val isNotLocalProxy = isNotLocalAndRpnProxy(mark.pidcsv)
        if (mark.pidcsv.isNotEmpty() && isNotLocalProxy) {
            cm.blockedByRule = FirewallRuleset.RULE12.id
        }

        if (isRethink) {
            netLogTracker.writeRethinkLog(cm)
        } else {
            netLogTracker.writeIpLog(cm)
        }

        logd("flow/postFlow, write conn in db: $mark")
    }

    fun handleExpiredConnMetaData(notification: RemovalNotification<String, ConnTrackerMetaData>) {
        // handle only the expired connMetaData
        if (notification.cause != RemovalCause.EXPIRED) return

        // this is called when the connMetaData is expired from the cache
        // remove the connection metadata from the trackedConnMetaData
        val cm = notification.value
        if (cm == null) {
            Logger.e(LOG_TAG_VPN, "handleExpiredConnMetaData: received null connMetaData")
            return
        }

        val isRethink = cm.uid == rethinkUid
        cm.proxyDetails = ""
        cm.rpid = ""
        cm.downloadBytes = 0L
        cm.uploadBytes = 0L
        cm.duration = 0
        cm.synack = 0L
        cm.message = "no metadata"
        if (isRethink) {
            netLogTracker.writeRethinkLog(cm)
        } else {
            netLogTracker.writeIpLog(cm)
        }
        Logger.d(LOG_TAG_VPN, "expired connMetaData, close conns: $cm")
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
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow: socks5 proxy enabled but endpoint is null")
            }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            logd("flow/inflow: socks5 proxy is enabled, $packageName, ${endpoint?.proxyAppName}")
            // do not block the app if the app is set to forward the traffic via socks5 proxy
            if (endpoint?.proxyAppName == packageName) {
                logd("flow: socks5 enabled for $packageName, handling as spl app")
                return true
            }
        }

        if (appConfig.isCustomHttpProxyEnabled()) {
            val endpoint = appConfig.getHttpProxyDetails()
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow: http proxy enabled but endpoint is null")
            }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via http proxy
            if (endpoint?.proxyAppName == packageName) {
                logd("flow/inflow: http exit for $packageName, $uid")
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
            if (connTracker.blockedByRule == FirewallRuleset.RULE0.id) {
                connTracker.blockedByRule = FirewallRuleset.RULE15.id
            }
            return persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
        }
        // add baseOrExit in the end of the list if needed (not true for lockdown)
        val ssid = underlyingNetworks?.activeSsid ?: underlyingNetworks?.ipv4Net?.firstOrNull { it.ssid != null }?.ssid ?: underlyingNetworks?.ipv6Net?.firstOrNull() { it.ssid != null }?.ssid ?: ""
        val wgs = WireguardManager.getAllPossibleConfigIdsForApp(uid, connTracker.destIP, connTracker.destPort, connTracker.query ?: "", true, ssid, baseOrExit)
        if (wgs.isNotEmpty() && wgs.first() != baseOrExit) {
            // canRoute may fail for all configs.
            // if that happens:
            //   - traffic is sent to baseOrExit if available,
            //   - in lockdown mode, traffic is blocked if not active, apply rule#17
            if (wgs.contains(Backend.Block)) { // block should be the only entry
                connTracker.isBlocked = true
                connTracker.blockedByRule = FirewallRuleset.RULE17.id
            }
            val ids = wgs.joinToString(",")
            if (ids.isEmpty()) { // should not happen as wgs is not empty
                logd("flow/inflow: wg ids is empty, returning $baseOrExit, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, baseOrExit, connId, uid)
            } else {
                logd("flow/inflow: wg is active, returning $wgs, $connId, $uid")
                return persistAndConstructFlowResponse(connTracker, ids, connId, uid)
            }
        } else {
            Logger.vv(LOG_TAG_VPN, "flow/inflow: no wg proxy, fall-through")
        }

        // carry out this check after wireguard, because wireguard has catchAll and lockdown.
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
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow: socks5 proxy enabled but endpoint is null")
            }
            logd("flow/inflow: socks5 proxy is enabled, $packageName, ${endpoint?.proxyAppName}")
            // do not block the app if the app is set to forward the traffic via socks5 proxy
            if (endpoint?.proxyAppName == packageName) {
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
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow: http proxy enabled but endpoint is null")
            }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via http proxy
            if (endpoint?.proxyAppName == packageName) {
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
        synchronized(activeCids) {
            return activeCids.contains(key)
        }
    }

    suspend fun removeWireGuardProxy(id: Int) {
        logd("remove wg from tunnel: $id")
        vpnAdapter?.removeWgProxy(id)
    }

    suspend fun addWireGuardProxy(id: String, force: Boolean = false) {
        logd("add wg from tunnel: $id")
        vpnAdapter?.addWgProxy(id, force)
    }

    suspend fun pauseMobileOnlyWireGuardOnNoNw() {
        val activeWgs = WireguardManager.getActiveConfigs()
        activeWgs.forEach { config ->
            val map = WireguardManager.getConfigFilesById(config.getId())
            if (map == null || !map.useOnlyOnMetered) {
                // if the config is not using only on metered, then skip it
                logd("pause wg from tunnel: ${config.getId()} is not using only on metered")
                return@forEach
            }
            val id = ID_WG_BASE + config.getId()
            logd("pause wg from tunnel (mobile): $id")
            // pause the wireguard proxy, so that it won't be used for new connections
            vpnAdapter?.pauseWireguard(id)
        }
    }

    suspend fun pauseSsidEnabledWireGuardOnNoNw() {
        val activeWgs = WireguardManager.getActiveConfigs()
        activeWgs.forEach { config ->
            val map = WireguardManager.getConfigFilesById(config.getId())
            if (map == null || !map.ssidEnabled) {
                // if the config is not using ssid restriction, then skip it
                logd("pause wg from tunnel: ${config.getId()} is not using ssid restriction")
                return@forEach
            }
            val id = ID_WG_BASE + config.getId()
            logd("pause wg from tunnel (ssid): $id")
            // pause the wireguard proxy, so that it won't be used for new connections
            vpnAdapter?.pauseWireguard(id)
        }
    }

    suspend fun refreshOrPauseOrResumeOrReAddProxies() {
        withContext(CoroutineName("ref-pro") + serializer) {
            logd("refresh wg config")
            // perform the active network mobile check
            val newNet = underlyingNetworks
            val v4first = newNet?.ipv4Net?.firstOrNull()
            val v6first = newNet?.ipv6Net?.firstOrNull()
            val v4Mobile = v4first?.capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
            val v6Mobile = v6first?.capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
            val isActiveMobile = v4Mobile || (v4first == null && v6Mobile)
            val activeSsid = getUnderlyingSsid() ?: ""
            Logger.v(LOG_TAG_VPN, "refreshOrPauseOrResumeOrReAddProxies: canResumeMobileOnlyWg? $isActiveMobile, curr-ssid: $activeSsid")
            io("refreshWg") { vpnAdapter?.refreshOrPauseOrResumeOrReAddProxies(isActiveMobile, activeSsid) }
        }
    }

    suspend fun getDnsStatus(id: String): Long? {
        return vpnAdapter?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return vpnAdapter?.getRDNS(type)
    }

    private fun persistAndConstructFlowResponse(
        cm: ConnTrackerMetaData?,
        proxyIds: String,
        connId: String,
        uid: Int,
        isRethink: Boolean = false
    ): Mark {
        // override exit in case of rethink plus subscription
        // case: do not override the proxyId in case of rethink as rethink's traffic should
        // always use Exit proxy not the rpn proxy
        // val iid = proxyIds
        /*if (proxyId == Backend.Exit && RpnProxyManager.isRpnActive() && !isRethink) {
            val rpnId = getRpnIds()
            logd("flow/inflow: returning $rpnId for connId: $connId, uid: $uid")
            io("checkPlusSub") {
                initiatePlusSubscriptionCheckIfRequired()
            }
            rpnId
        } else {
            proxyId
        }*/


        if (cm != null) {
            // in case of multiple proxies we do not need to write the log as we are not sure
            // which proxy is used for the connection, so wait for the postflow/onSocketClosed
            // to write the log, until that maintain the connTrackerMetaData in a set
            val containsMultipleProxy = proxyIds
                .split(",")
                .map { it.trim() }.count { it.isNotEmpty() } > 1
            if (containsMultipleProxy && !cm.isBlocked) {
                trackedConnMetaData.put(cm.connId, cm)
                if (DEBUG) logd("flow/inflow/postflow: multiple proxies for connId: $connId, proxies: $proxyIds, uid: $uid, cache-size: ${trackedConnMetaData.size()}, cm: $cm")
            } else {
                cm.proxyDetails = proxyIds // contains only one proxy id

                // set proxied rule if the proxy is ipn
                if (proxyIds.isNotEmpty() && isNotLocalAndRpnProxy(proxyIds)) {
                    cm.blockedByRule = FirewallRuleset.RULE12.id
                }

                if (isRethink) {
                    netLogTracker.writeRethinkLog(cm)
                } else {
                    netLogTracker.writeIpLog(cm)
                }
            }
            logd("flow/inflow: connTracker: $cm")
        }

        val mark = Mark()

        mark.pidcsv = proxyIds
        mark.cid = connId
        // no need to handle rethink
        mark.uid = uid.toString()
        if (cm == null) {
            Logger.i(
                LOG_TAG_VPN,
                "flow/inflow: returning mark: $mark for connId: $connId, uid: $uid, cm: null"
            )
        } else {
            Logger.i(
                LOG_TAG_VPN,
                "flow/inflow: returning mark: $mark for src(${cm.sourceIP}: ${cm.sourcePort}), dest(${cm.destIP}:${cm.destPort}, ${cm.query})"
            )
        }
        return mark
    }

    /*private suspend fun initiatePlusSubscriptionCheckIfRequired() {
        // consider enableWarp as the flag to check the plus subscription
        if (!RpnProxyManager.isRpnEnabled()) {
            Logger.i(LOG_TAG_VPN, "initiatePlusSubscriptionCheckIfRequired(rpn): plus not enabled")
            return
        }
        // initiate the check once in 4 hours, store last check time in local variable
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSubscriptionCheckTime < PLUS_CHECK_INTERVAL) {
            Logger.v(LOG_TAG_VPN, "initiatePlusSubscriptionCheckIfRequired(rpn): check not required")
            return
        }
        // initiate the check
        lastSubscriptionCheckTime = currentTime
        checkForPlusSubscription()
    }*/

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
        synchronized(activeClosableCids) {
            activeClosableCids.add(cid)
        }
    }

    // this method is called when the device is locked, so no need to check for device lock here
    private fun closeTrackedConnsOnDeviceLock() {
        io("devLockCloseConns") {
            val cidsToClose: List<String> = synchronized(activeClosableCids) {
                if (activeClosableCids.isEmpty()) emptyList<String>()

                val snapshot = activeClosableCids.toList()
                activeClosableCids.clear()
                snapshot
            }
            if (cidsToClose.isNotEmpty()) {
                vpnAdapter?.closeConnections(cidsToClose, isUid = false, "dev-lock-close-conns")
            }
        }
    }

    private fun isLockdown(): Boolean {
        return isLockDownPrevious.get()
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

    suspend fun getWireGuardStats(id: String): WireguardManager.WgStats? {
        return vpnAdapter?.getWireGuardStats(id)
    }

    suspend fun getSupportedIpVersion(id: String): Pair<Boolean, Boolean>? {
        return vpnAdapter?.getSupportedIpVersion(id) ?: return Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(id: String, pair: Pair<Boolean, Boolean>): Boolean {
        return vpnAdapter?.isSplitTunnelProxy(id, pair) ?: false
    }

    suspend fun p50(id: String): Long {
        return vpnAdapter?.p50(id) ?: -1L
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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

    fun getNetStat(): NetStat? {
        return vpnAdapter?.getNetStat()
    }

    fun writeConsoleLog(log: ConsoleLog) {
        netLogTracker.writeConsoleLog(log)
    }

    suspend fun performFlightRecording() {
        vpnAdapter?.performFlightRecording()
    }

    suspend fun isProxyReachable(proxyId: String, csv: String): Boolean { // can be ippcsv or hostpcsv
        return vpnAdapter?.isProxyReachable(proxyId, csv) == true
    }

    suspend fun testRpnProxy(proxyId: String): Boolean {
        return vpnAdapter?.testRpnProxy(proxyId) == true
    }

    suspend fun testHop(src: String, hop: String): Pair<Boolean, String?> {
        return vpnAdapter?.testHop(src, hop) ?: Pair(false, "vpn not active")
    }

    suspend fun hopStatus(src: String, via: String): Pair<Long?, String> {
        return vpnAdapter?.hopStatus(src, via) ?: Pair(null, "vpn not active")
    }

    suspend fun removeHop(src: String): Pair<Boolean, String> {
        return vpnAdapter?.removeHop(src) ?: Pair(false, "vpn not active")
    }

    /*suspend fun getRpnProps(type: RpnProxyManager.RpnType): Pair<RpnProxyManager.RpnProps?, String?> {
        return vpnAdapter?.getRpnProps(type) ?: Pair(null, null)
    }*/

    suspend fun registerAndFetchWinIfNeeded(prevBytes: ByteArray?): ByteArray? {
        return vpnAdapter?.registerAndFetchWinIfNeeded(prevBytes)
    }

    suspend fun updateWin(): ByteArray? {
        return vpnAdapter?.updateWin()
    }

    suspend fun createWgHop(origin: String, hop: String): Pair<Boolean, String> {
        return (vpnAdapter?.createHop(origin, hop)) ?: Pair(false, "adapter is null")
    }
/*
    suspend fun updateRpnProxy(type: RpnProxyManager.RpnType): ByteArray? {
        return vpnAdapter?.updateRpnProxy(type)
    }*/

    suspend fun vpnStats(): String {
        // create a string with the stats, add stats of firewall, dns, proxy, builder
        // other key stats
        val stats = StringBuilder()
        stats.append("VPN Stats:\n")
        stats.append("Builder:\n${builderStats()}\n")
        stats.append("General:\n${generalStats()}\n")
        //stats.append("RPN:\n${rpnStats()}\n")
        stats.append("Firewall:\n${firewallStats()}\n")
        stats.append("IpRules:\n${ipRulesStats()}\n")
        stats.append("DomainRules:\n${domainRulesStats()}\n")
        stats.append("Proxy:\n${proxyStats()}\n")
        stats.append("WireGuard:\n${wireguardStats()}\n")
        stats.append("Memory: \n${MemoryUtils.getMemoryStats(this)}\n")
        return stats.toString()
    }

    fun performConnectivityCheck(controller: Controller, id: String, addrPort: String): Boolean {
        return vpnAdapter?.performConnectivityCheck(controller, id, addrPort) ?: false
    }

    fun performAutoConnectivityCheck(controller: Controller, id: String, mode: String): Boolean {
        return vpnAdapter?.performAutoConnectivityCheck(controller, id, mode) ?: false
    }

    private fun firewallStats(): String {
        return FirewallManager.stats()
    }

    private fun dnsStats(): String {
        return prevDns.joinToString()
    }

    /* private fun rpnStats(): String {
        return RpnProxyManager.stats()
    } */

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

    private suspend fun wireguardStats(): String {
        return WireguardManager.stats()
    }

    fun screenUnlock() {
        io("screenUnlock") {
            // initiate wireguard ping for one wg, catch-all, hop proxies
            val proxies = WireguardManager.getActiveConfigs()
            Logger.i(LOG_TAG_VPN, "unlock: initiate ping for one-wg/catchall/hop/rpn proxies")
            proxies.forEach { c ->
                val isOneWg = WireguardManager.getOneWireGuardProxyId() == c.getId()
                val isCatchAll = WireguardManager.getActiveCatchAllConfig().any { it.id == c.getId()}
                val isPartOfHop = WgHopManager.isWgEitherHopOrSrc(c.getId())
                if (isOneWg || isCatchAll || isPartOfHop) {
                    val id = ID_WG_BASE + c.getId()
                    vpnAdapter?.initiateWgPing(id)
                }
            }
            /*if (RpnProxyManager.isRpnActive()) {
                val id = Backend.Auto // ping auto proxy which internally pings all rpn proxies
                // only initiating ping is sufficient, no need to re-add the proxy (AUTO)
                vpnAdapter?.initiateWgPing(id)
                handleWinProxy()
            }*/
        }
    }

    private fun builderStats(): String {
        val n = Networks(underlyingNetworks, overlayNetworks)
        val (route4, route6) = determineRoutes(n)

        val ipv4NwHandles = n.underlyingNws?.ipv4Net?.map { netid(it.network.networkHandle) } ?: emptyList()
        val ipv6NwHandles = n.underlyingNws?.ipv6Net?.map { netid(it.network.networkHandle) } ?: emptyList()
        val linkAddresses4 = n.underlyingNws?.ipv4Net?.map { it.linkProperties?.linkAddresses?.filter { IPAddressString(it.address.hostAddress).isIPv4 } } ?: emptyList()
        val linkAddresses6 = n.underlyingNws?.ipv6Net?.map { it.linkProperties?.linkAddresses?.filter { IPAddressString(it.address.hostAddress).isIPv6 } } ?: emptyList()
        val link4Mtu = if (isAtleastQ()) n.underlyingNws?.ipv4Net?.map { it.linkProperties?.mtu ?: 0 } ?: listOf(-1) else listOf(-1)
        val link6Mtu = if (isAtleastQ()) n.underlyingNws?.ipv6Net?.map { it.linkProperties?.mtu ?: 0 } ?: listOf(-1) else listOf(-1)
        val ssid = getUnderlyingSsid() ?: "N/A"

        val linkAddr4String = if (linkAddresses4.isEmpty()) {
            "N/A"
        } else {
            linkAddresses4.joinToString(", ") { it?.joinToString(", ") { addr -> addr.address.hostAddress } ?: "N/A" }
        }
        val linkAddr6String = if (linkAddresses6.isEmpty()) {
            "N/A"
        } else {
            linkAddresses6.joinToString(", ") { it?.joinToString(", ") { addr -> addr.address.hostAddress } ?: "N/A" }
        }
        val vpnServiceLockdown = if (isAtleastQ()) {
            isLockdownEnabled
        } else {
            ">Q"
        }
        val sb = StringBuilder()
        sb.append("  $builderStats\n")
        sb.append("   builderRoutes: ${builderRoutes}\n")
        sb.append("   fd: ${testFd.get()}\n")
        sb.append("   dns: ${dnsStats()}\n")
        sb.append("   stall: ${persistentState.stallOnNoNetwork}\n")
        sb.append("   setUnderlyingNws: $tunUnderlyingNetworks\n")
        sb.append("   loopback: ${persistentState.routeRethinkInRethink}\n")
        sb.append("   lockdown: ${isLockdown()}/${underlyingNetworks?.vpnLockdown ?: "null"}/$vpnServiceLockdown\n")
        sb.append("   Restart mechanism: ${persistentState.vpnBuilderPolicy}\n")
        sb.append("   Underlay\n")
        sb.append("      4: ${n.underlyingNws?.ipv4Net?.size}\n")
        sb.append("      6: ${n.underlyingNws?.ipv6Net?.size}\n")
        sb.append("      vpnRoutes: ${n.underlyingNws?.vpnRoutes}\n")
        sb.append("      useActive: ${n.underlyingNws?.useActive}\n")
        sb.append("      mtu: ${n.underlyingNws?.minMtu}\n")
        sb.append("   Overlay\n")
        sb.append("      4: ${n.overlayNws.has4}\n")
        sb.append("      6: ${n.overlayNws.has6}\n")
        sb.append("      mtu:${n.overlayNws.mtu}\n")
        sb.append("      determine4: $route4\n")
        sb.append("      determine6: $route6\n")
        sb.append("   Net ID\n")
        sb.append("      4: $ipv4NwHandles\n")
        sb.append("      6: $ipv6NwHandles\n")
        sb.append("   Link Addresses\n")
        sb.append("      4: $linkAddr4String\n")
        sb.append("      6: $linkAddr6String\n")
        sb.append("   Link MTU\n")
        sb.append("      4: $link4Mtu\n")
        sb.append("      6: $link6Mtu\n")
        sb.append("   SSID: $ssid\n")
        return sb.toString()
    }

    fun isUnderlyingVpnNetworkEmpty(): Boolean {
        val tunUnderlyingNetworks = tunUnderlyingNetworks ?: return false
        // return the current underlying networks in the vpn adapter
        return tunUnderlyingNetworks.isEmpty()
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
