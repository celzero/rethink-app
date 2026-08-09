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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_BATCH_LOGGER
import com.celzero.bravedns.util.Logger.LOG_TAG_CONNECTION
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
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
import android.app.UiModeManager
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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.SubscriptionCheckWorker
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.receiver.UserPresentReceiver
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.RpnType
import com.celzero.bravedns.scheduler.RpnProxyUpdateWorker
import com.celzero.bravedns.scheduler.WgProxyPingController
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.ProxyManager.isAnyUserSetProxy
import com.celzero.bravedns.tunnel.TunDnsManager

import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import com.celzero.bravedns.ui.activity.TunnelSettingsActivity
import com.celzero.bravedns.ui.bottomsheet.BlockFreeDnsModeBottomSheet
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.BubbleHelper
import com.celzero.bravedns.util.CoFactory
import com.celzero.bravedns.util.ConnectivityCheckHelper
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
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isAtleastU
import com.celzero.bravedns.util.Utilities.isNetworkSame
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.Client
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.DNSTransport
import com.celzero.firestack.backend.NetStat
import com.celzero.firestack.backend.Proxy
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.RouterStats
import com.celzero.firestack.backend.RpnEntitlement
import com.celzero.firestack.backend.RpnServers
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.Controller
import com.celzero.firestack.intra.FlowSummary
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.google.common.collect.Sets
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class BraveVPNService : VpnService(), ConnectionMonitor.NetworkListener, NetworkLifecycleObserver.Listener, Bridge, OnSharedPreferenceChangeListener {

    private val vpnScope = MainScope()

    // used mostly for service to adapter creation and updates
    private val serializer: CoroutineDispatcher = Daemons.make("vpnser")

    private val connectionMonitor: ConnectionMonitor = ConnectionMonitor(this, this, serializer, vpnScope)

    // Lightweight observer for raw network lifecycle events (added, lost, capabilities
    // and link-property changes), tracked independently from ConnectionMonitor which
    // performs the heavier connectivity/VPN orchestration.
    private val networkLifecycleObserver: NetworkLifecycleObserver =
        NetworkLifecycleObserver(this, this)
    private val connTrackRepository by inject<ConnectionTrackerRepository>()
    private val eventLogger by inject<EventLogger>()

    private val userPresentReceiver: UserPresentReceiver = UserPresentReceiver()

    // multiple coroutines call both signalStopService and makeOrUpdateVpnAdapter and so
    // set and unset this variable on the serializer thread
    @Volatile
    private var vpnAdapter: GoVpnAdapter? = null
    private val dnsQueryDispatcher by lazy { Daemons.ioDispatcher("onquery", DNSOpts(), vpnScope) }
    private val flowDispatcher by lazy { Daemons.ioDispatcher("flow", Mark(),  vpnScope) }
    private val inflowDispatcher by lazy { Daemons.ioDispatcher("inflow", Mark(), vpnScope) }
    private val preflowDispatcher by lazy { Daemons.ioDispatcher("preflow", PreMark(), vpnScope) }
    private val bind4Dispatcher by lazy { Daemons.ioDispatcher("bind4", Unit, vpnScope) }
    private val bind6Dispatcher by lazy { Daemons.ioDispatcher("bind6", Unit, vpnScope) }
    private val protectDispatcher by lazy { Daemons.ioDispatcher("protect", Unit, vpnScope) }
    private val proxyAddedDispatcher by lazy { Daemons.ioDispatcher("pxycallback", Unit, vpnScope) }
    private val upstreamQueryDispatcher by lazy { Daemons.ioDispatcher("upstreamQ", DNSOpts(), vpnScope) }

    private val wgProxyPingController by lazy { WgProxyPingController(vpnScope) }

    // TODO: remove volatile
    @Volatile
    private var builderStats: String = ""
    @Volatile
    private var tunUnderlyingNetworks: String? = null
    @Volatile
    private var prevDns: MutableSet<InetAddress> = mutableSetOf()
    private val testFd: AtomicInteger = AtomicInteger(-1)

    companion object {
        private const val TAG = "VpnService;"
        const val SERVICE_ID = 1 // Only has to be unique within this app.
        const val MEMORY_NOTIFICATION_ID = 29001
        const val NW_ENGINE_NOTIFICATION_ID = 29002
        const val IP_MISMATCH_NOTIFICATION_ID = 29003

        private const val MAIN_CHANNEL_ID = "vpn"
        private const val WARNING_CHANNEL_ID = "warning"

        // notification request codes
        private const val NOTIF_ACTION_MODE_RESUME = 98
        private const val NOTIF_ACTION_MODE_PAUSE = 99
        internal const val NOTIF_ACTION_MODE_STOP = 100
        private const val NOTIF_ACTION_MODE_DNS_ONLY = 101
        private const val NOTIF_ACTION_MODE_DNS_FIREWALL = 102
        private const val NOTIF_ACTION_MODE_IP_MISMATCH = 103 // opens TunnelSettingsActivity on tap

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

        const val VPN_INTERFACE_MTU: Int = 1500
        // TODO: should be different for IPv4 and IPv6, but for now it is same
        // IPv4: 576, IPv6: 1280
        const val MIN_MTU: Int = 1280
        private const val MAX_MTU: Int = 10000

        // route v4 in v6 only networks?
        const val ROUTE4IN6 = true

        // subscription check interval in milliseconds 1 hour
        // TODO: increase it to 6 hours?
        private const val PLUS_CHECK_INTERVAL = 6 * 60 * 60 * 1000L

        private const val DATA_STALL_THRESHOLD_MS = 30 * 1000L // 30 seconds

        // vpnRoutes are only used for diagnostics, the current implementation will taken
        // into account the vpn routes are handled properly, case: do not route private ips
        private const val RECONCILE_WITH_VPN_ROUTES = false
    }

    private val isLockDownPrevious = AtomicBoolean(false)

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val persistentState by inject<PersistentState>()
    private val rdb by inject<RefreshDatabase>()
    private val netLogTracker by inject<NetLogTracker>()

    @Volatile
    private var isAccessibilityServiceFunctional: Boolean = false

    // Tracks whether the IP protocol-mismatch notification is currently on screen, so the
    // warning is posted exactly once per mismatch condition and removed once it clears.
    private val ipMismatchNotifShown = AtomicBoolean(false)

    @Volatile
    var accessibilityHearbeatTimestamp: Long = INIT_TIME_MS
    private val settingUpOrbot: AtomicBoolean = AtomicBoolean(false)

    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager
    private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var cm: ConnectivityManager
    private var keyguardManager: KeyguardManager? = null

    private lateinit var appInfoObserver: Observer<Collection<AppInfo>>
    private lateinit var orbotStartStatusObserver: Observer<Boolean>
    private lateinit var dnscryptRelayObserver: Observer<PersistentState.DnsCryptRelayDetails>
    private lateinit var blockedConnsObserver: Observer<Int>

    // used to handle app observer to exclude apps from the tunnel
    private val excludeAppsMutex = Mutex()

    private var excludedApps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // post underlying networks as live data
    @Volatile
    var underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null

    // Networks currently tracked from NetworkLifecycleObserver events (WiFi + Cellular),
    // maintained in priority order (active first, then non-metered, then metered) by
    // rearrangeObservedNetworks(). Mutated only on the serializer; @Volatile so the
    // reassignment in stop() is visible across threads.
    @Volatile
    private var observedNetworks: LinkedHashSet<ConnectionMonitor.NetworkProperties> = linkedSetOf()

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
        PAUSED
    }

    private fun logd(msg: String) {
        Logger.d(LOG_TAG_VPN, "$TAG $msg")
    }

    override fun bind4(who: String, addrPort: String, fid: Long) = go2kt(bind4Dispatcher) {
        var v4Net = underlyingNetworks?.ipv4Net
        val isAuto = InternetProtocol.isAuto(persistentState.internetProtocolType)
        if (ROUTE4IN6 && isAuto && v4Net.isNullOrEmpty()) {
            v4Net = underlyingNetworks?.ipv6Net
        }

        bindAny(who, addrPort, fid, v4Net ?: emptyList())
        Logger.vv(LOG_TAG_VPN, "bind4: who: $who, addrPort: $addrPort, fid: $fid")
    }

    override fun bind6(who: String, addrPort: String, fid: Long) = go2kt(bind6Dispatcher) {
        bindAny(who, addrPort, fid, underlyingNetworks?.ipv6Net ?: emptyList())
        Logger.vv(LOG_TAG_VPN, "bind6: who: $who, addrPort: $addrPort, fid: $fid")
    }

    private suspend fun bindAny(
        who: String,
        addrPort: String,
        fid: Long,
        nws: List<ConnectionMonitor.NetworkProperties>
    ) {
        val rinr = persistentState.routeRethinkInRethink
        val curnet = underlyingNetworks
        val isBase = who == Backend.Base
        val proxying = ProxyManager.isAnyUserSetProxy(who)
        val doNotProtect = isBase

        logd("bind: who: $who, addr: $addrPort, fd: $fid, rinr? $rinr, base? $isBase, proxying? $proxying")
        if (doNotProtect && rinr) {
            // do not proceed if rethink within rethink is enabled and proxyId(who) is base
            Logger.vv(LOG_TAG_VPN, "bind: rinr, within rethink, who: $who, fd: $fid, addr: $addrPort")
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
            // TODO: in case of wg, bind to the network depending on the call from go, wg ips
            // will always be zero.
            /*if (
                (destIp.isZero && who.startsWith(ID_WG_BASE)) ||
                destIp.isZero ||
                destIp.isLoopback
            ) {
                logd("bind: zero ip: $destIp, who: $who, addr: $addrPort")
                return
            }*/

            pfd = ParcelFileDescriptor.adoptFd(fid.toInt())

            // check if the destination port is DNS port, if so bind to the network where the dns
            // belongs to, else bind to the available network
            val net = if (KnownPorts.isDns(destPort)) curnet?.dnsServers?.get(destAddr) else null
            if (net != null) {
                val ok = bindToNw(net, pfd, fid)
                if (!ok) {
                    Logger.e(LOG_TAG_VPN, "bind failed, who: $who, addr: $addrPort, fd: $fid, handle: ${net.networkHandle}, netid:${netid(net.networkHandle)}")
                } else {
                    logd("bind: dns, who: $who, addr: $addrPort, fd: $fid, handle: ${net.networkHandle}, netid:${netid(net.networkHandle)}, ok: true")
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
                val ok = bindToNw(it.network, pfd, fid)
                Logger.vv(LOG_TAG_VPN, "bindAny: bindToNw handle: ${it.network.networkHandle}")
                if (ok) {
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
        val res = nwHandle shr (32)
        Logger.vv(LOG_TAG_VPN, "netid: nwHandle: $nwHandle, result: $res")
        return res
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
        val res = try {
            net.bindSocket(pfd.fileDescriptor)
            true
        } catch (e: IOException) {
            Logger.e(LOG_TAG_VPN, "err bindToNw(nw: ${net.networkHandle}, netid: ${netid(net.networkHandle)}, fid: $fid, ${e.message}, $e")
            false
        }
        Logger.vv(LOG_TAG_VPN, "bindToNw: nw: ${net.networkHandle}, fid: $fid, success: $res")
        return res
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
                    return ConnectionMonitor.ProbeResult("", true, nwprop.capabilities)
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

    override fun protect(who: String?, fd: Long) = go2kt(protectDispatcher) {
        if (who == null) {
            Logger.w(LOG_TAG_VPN, "protect: who is null, fd: $fd")
            return@go2kt
        }

        val rinr = persistentState.routeRethinkInRethink
        val isBase = who == Backend.Base
        val proxying = ProxyManager.isAnyUserSetProxy(who)
        val doNotProtect = isBase

        logd("bind: who: $who, addr: fd: $fd, rinr? $rinr, base? $isBase, proxying? $proxying")
        if (doNotProtect && rinr) {
            // do not proceed if rethink within rethink is enabled and proxyId(who) is base
            Logger.vv(LOG_TAG_VPN, "protect: rinr, within rethink, who: $who, fd: $fd")
            return@go2kt
        }
        this.protect(fd.toInt())
    }

    private suspend fun newBuilder(): Builder {
        val builder = Builder()
        val underlyingNws = getUnderlays()
        // prefer view of underlying networks over vpn service lockdown state for being consistent
        // with onNetworksChanged()
        val vpnLockdown = if (isAtleastQ()) {
            underlyingNetworks?.vpnLockdown ?: isLockdownEnabled
        } else {
            false
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

        // Temp-allow expiry scheduling is only relevant when VPN is active.
        FirewallManager.initTempAllowScheduler(this)

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

        ensureNotificationChannelExists()

        if (persistentState.getBlockAppWhenBackground()) {
            registerAccessibilityServiceState()
        }
        registerUserPresentReceiver()
        if (isAtleastQ()) {
            handleFirewallBubbleIfNeeded()
        }
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

    private suspend fun observeChanges() {
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
            io("appObsrver") {
                try {
                    var latestExcludedApps: Set<String>
                    excludeAppsMutex.withLock {
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
                        return@io

                    Logger.i(LOG_TAG_VPN, "excluded-apps list changed, restart vpn")

                    val reason =
                        "excludeApps: ${latestExcludedApps.size} apps, at: ${elapsedRealtime()}"
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

    private fun ensureNotificationChannelExists() {
        if (!isAtleastO()) return
        val name: CharSequence = resources.getString(R.string.notif_channel_vpn_notification)
        // LOW is the lowest importance that is allowed with startForeground in Android O
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance)
        channel.description = resources.getString(R.string.notif_channel_desc_vpn_notification)
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotificationBuilder(): Notification {
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                this,
                Intent(this, AppLockActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                mutable = false
            )
        // ensure the channel exists; the user may have deleted it via system settings
        ensureNotificationChannelExists()
        val builder = NotificationCompat.Builder(this, MAIN_CHANNEL_ID)
        val isProxyEnabled = appConfig.isProxyEnabled() || RpnProxyManager.isRpnActive()

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
        builder.setContentTitle(contentTitle)
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
        builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification = builder.build()

        if (persistentState.persistentNotification) {
            notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        } else {
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
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

    private fun isPrimaryUser(): Boolean {
        return FirewallManager.userId(Process.myUid()) == PRIMARY_USER
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

    private val vpnRestartTrigger: MutableStateFlow<String> = MutableStateFlow("startVpn")
    @OptIn(FlowPreview::class)
    private fun observeVpnRestartRequests() {
        vpnScope.launch {
            Logger.i(LOG_TAG_VPN, "start restart manager flow")
            vpnRestartTrigger
                .debounce(3000.milliseconds)
                .collect { reason ->
                    Logger.v(LOG_TAG_VPN, "RESTART; new restart request: $reason")
                    restartVpnWithNewAppConfig(reason)
                    io("eventLogger") {
                        eventLogger.logHigh(
                            EventType.VPN_RESTART,
                            "Vpn Restart",
                            EventSource.VPN,
                            userAction = false,
                            details = "reason: $reason"
                        )
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pid = Process.myPid()

        Logger.i(
            LOG_TAG_VPN,
            "onStartCommand, us: ${Process.myUid()} / pid: $pid, primary? ${isPrimaryUser()}"
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

            // periodic health-check that re-adds proxies missing from the tunnel
            GlobalProxyHandler.start(vpnScope)

            startOrbotAsyncIfNeeded()

            val isNewVpn = connectionMonitor.onVpnStart(this)

            // observe raw network lifecycle events independently of ConnectionMonitor
            val observerStarted = networkLifecycleObserver.start()
            if (!observerStarted) {
                Logger.w(LOG_TAG_VPN, "networkLifecycleObserver.start() failed; raw lifecycle events will not be tracked")
            }

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
                    calculatePtMode()
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
                    eventLogger.logLow(EventType.VPN_START, "restart existing vpn from onStartCommand",
                        EventSource.VPN, userAction = false, details = "opts: $opts")
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
                        eventLogger.logLow(EventType.VPN_START, "start new vpn from onStartCommand",
                            EventSource.VPN, userAction = false, details = "opts: $opts")
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun checkForPlusSubscription() {
        // initiate the billing client if it is not already initialized
        if (!InAppBillingHandler.isBillingClientSetup()) {
            InAppBillingHandler.initiate(this.applicationContext)
            Logger.i(LOG_TAG_VPN, "checkForPlusSubscription: billing client initiated")
        } else {
            Logger.i(LOG_TAG_VPN, "checkForPlusSubscription: billing client already setup")
        }

        // start the subscription check only if the user has enabled the feature
        // invoke the work manager to check the subscription status
        if (RpnProxyManager.isRpnEnabled()) {
            io("rethinkPlusSubs") {
                // Proxy setup is decoupled from the subscription check: a Go-backend
                // exception here (e.g. addNewWinServer/setRpnAutoMode) must not prevent
                // the SubscriptionCheckWorker from running. Proxy health is independently
                // reconciled by GlobalProxyHandler (every 2 min) and RpnProxyUpdateWorker
                // (every 45 min), so a failure here is non-fatal.
                try {
                    handleRpnProxies()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_VPN, "checkForPlusSubscription(rpn): handleRpnProxies failed (non-fatal), proxy self-heal deferred to GlobalProxyHandler: ${e.message}", e)
                }
                // KEEP: only enqueue if a SubscriptionCheckWorker with this unique name
                // is not already enqueued or running; avoids redundant work on repeated
                // VPN adapter creations (e.g. network-driven restarts).
                Logger.i(LOG_TAG_VPN, "checkForPlusSubscription(rpn): enqueue work manager (KEEP)")
                val workManager = WorkManager.getInstance(this)
                val workRequest = OneTimeWorkRequestBuilder<SubscriptionCheckWorker>().build()
                workManager.enqueueUniqueWork(
                    SubscriptionCheckWorker.WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
            }
            // TOOD: write listener to check the subscription status, if worker fails
            // reset the last check time
        } else {
            Logger.i(LOG_TAG_VPN, "checkForPlusSubscription(rpn): feature disabled")
        }
    }

    suspend fun handleRpnProxies() {
        Logger.v(LOG_TAG_VPN, "RpnMgr handleRpnProxies, rpnActive? ${RpnProxyManager.isRpnActive()}")
        Logger.v(LOG_TAG_VPN, "RpnMgr handleRpnProxies, mode: ${RpnProxyManager.rpnMode().name}, state: ${RpnProxyManager.rpnState().name}")
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
            val countries = RpnProxyManager.getEnabledConfigs()
            if (countries.isNotEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: selected countries(rpn): $countries")
                // TODO: add the selected countries to the tunnel, new API needed
                countries.forEach {
                    // duplicate adds are handled in vpnAdapter
                    val res = vpnAdapter?.addNewWinServer(it.key)
                    if (res?.first != true) {
                        Logger.w(LOG_TAG_VPN, "$TAG handleRpnProxies: addNewWinServer returned not-ok for ${it.key}: $res")
                    }
                }
            }
            vpnAdapter?.setRpnAutoMode()
        } else { // either in pause mode or plus disabled
            Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: plus disabled(rpn)")
            vpnAdapter?.unregisterWin()
            // Cancel the periodic update worker when RPN is no longer active.
            RpnProxyUpdateWorker.cancel(applicationContext)
        }
    }

    private suspend fun handleWinProxy() {
        // see if win is already registered and last connected is less than 60 mins
        val isWinRegistered = vpnAdapter?.isWinRegistered() == true
        Logger.d(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) registered? $isWinRegistered")
        if (!isWinRegistered) {
            val registered = RpnProxyManager.registerProxy(RpnType.WIN)
            if (registered) {
                RpnProxyUpdateWorker.schedule(applicationContext)
                Logger.i(
                    LOG_TAG_VPN,
                    "$TAG handleRpnProxies: RpnProxyUpdateWorker scheduled (fresh timer) after WIN registration"
                )
            } else {
                Logger.e(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) registration failed")
                // Track the WIN proxy so GlobalProxyHandler's periodic checker (every
                // 2 min) re-attempts registration. Without this, a failed registration
                // is never retried until the next full VPN restart, since the proxy is
                // only tracked inside GoVpnAdapter on a *successful* Go-side add.
                // track() is idempotent and resets the retry budget; a later successful
                // registration re-tracks with a fresh entry.
                GlobalProxyHandler.track(Backend.RpnWin)
            }
        } else {
            Logger.i(LOG_TAG_VPN, "$TAG handleRpnProxies: win(rpn) already registered, periodic update worker handles refresh")
        }
    }

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

    internal fun mtu(): Int {
        val overlayMtu = overlayNetworks.mtu
        val underlyingMtu = underlyingNetworks?.minMtu ?: VPN_INTERFACE_MTU
        val minMtu = min(overlayMtu, underlyingMtu)
        Logger.i(LOG_TAG_VPN, "mtu for overlay: $overlayMtu, underlying: $underlyingMtu, min: $minMtu")
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

            PersistentState.PROXY_TYPE -> {
                io("proxy") {
                    handleProxyChange()
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
                    setExperimentalWireGuardSettings(persistentState.nwEngExperimentalFeatures)
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
                    val newMtu = if (persistentState.useMaxMtu && !persistentState.routeRethinkInRethink) {
                        MAX_MTU
                    } else {
                        mtu()
                    }
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
            PersistentState.CUSTOM_LAN_MODE_IPS_CHANGED -> {
                if (persistentState.customModeOrIpChanged) {
                    io("customIpsChanged") {
                        val reason = "customIpsChanged: ${System.currentTimeMillis()}"
                        vpnRestartTrigger.value = reason
                    }
                } else {
                    // no-op
                }
            }

            PersistentState.FIREWALL_BUBBLE -> {
                if (isAtleastQ()) {
                    handleFirewallBubbleIfNeeded()
                }
            }

            PersistentState.ADV_SETTINGS_FORCE_PT_MODE_ID -> {
                io("forcePtMode") {
                    setTunMode()
                }
            }

            PersistentState.FLOOD_WIREGUARD -> {
                io("floodWg") {
                    vpnAdapter?.setFloodWgMode()
                }
            }

            PersistentState.SOCKET_BUFFER_SIZE_BYTES -> {
                io("socBuf") {
                    setDialStrategy()
                }
            }

            PersistentState.GO_MAX_MEMORY -> {
                io("lowMem") {
                    vpnAdapter?.onLowMemory()
                }
            }
        }
    }

    private suspend fun setExperimentalWireGuardSettings(experimental: Boolean) {
        Logger.i(LOG_TAG_VPN, "set experimental wg settings: $experimental")
        vpnAdapter?.setExperimentalWireGuardSettings(experimental)
    }

    private suspend fun undelegatedDomains() {
        Logger.i(LOG_TAG_VPN, "use system dns for undelegated domains: ${persistentState.useSystemDnsForUndelegatedDomains}")
        vpnAdapter?.undelegatedDomains(persistentState.useSystemDnsForUndelegatedDomains)
    }

    private suspend fun setDialStrategy() {
        Logger.d(
            LOG_TAG_VPN,
            "set dial strategy: ${persistentState.dialStrategy}, retry: ${persistentState.retryStrategy}, tcpKeepAlive: ${persistentState.tcpKeepAlive}, timeout: ${persistentState.dialTimeoutSec}, socket buf size: ${persistentState.socketBufferSizeBytes}"
        )
        vpnAdapter?.setDialStrategy()
        vpnAdapter?.setAutoMode()
    }

    private suspend fun setAutoDialsParallel() {
        Logger.d(LOG_TAG_VPN, "set auto dials parallel: ${persistentState.autoDialsParallel}")
        vpnAdapter?.setAutoDialsParallel()
    }

    private suspend fun setTransparency() {
        Logger.d(LOG_TAG_VPN, "set endpoint independence: ${persistentState.endpointIndependence}")
        vpnAdapter?.setTransparency(persistentState.endpointIndependence)
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
        setTunMode()
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
    fun notifyConnectionMonitor(enforcePolicyChange: Boolean = false) {
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
        io(reason) {
            stopVpnAdapter()
            eventLogger.logHigh(
                EventType.VPN_STOP, "vpn service destroyed",
                EventSource.SERVICE, userAction = userInitiated, details = "vpn destroyed"
            )
        }
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
                    calculatePtMode()
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

    /**
     * calculate ProtocolTranslationMode based on the actual underlying networks
     * and the user-selected internet protocol preference.
     *
     * when protocol translation is enabled:
     * - ipv4-only underlying network + ipv6 selected : PTMODEFORCE46
     * - ipv6-only underlying network + ipv4 selected : PTMODEFORCE64
     * - only one protocol (ipv4/ipv6) + auto / ipv4&ipv6 selected : PTMODEFORCE
     * - other cases : PTMODEAUTO
     *
     * In debug builds, any mode can be forced via advSettingForcePTModeId for testing.
     */
    private fun calculatePtMode(): AppConfig.ProtoTranslationMode {
        // override: allow forcing any PT mode for testing purposes
        if (DEBUG) {
            val forcedId = persistentState.advSettingForcePTModeId
            if (forcedId != -1) {
                val forced = AppConfig.ProtoTranslationMode.entries.firstOrNull { it.id == forcedId }
                if (forced != null) {
                    Logger.d(LOG_TAG_VPN, "calculatePtMode: debug override, chosen $forced")
                    return forced
                }
            }
        }

        if (!persistentState.protocolTranslationType) {
            return AppConfig.ProtoTranslationMode.PTMODEAUTO
        }

        val networks = underlyingNetworks
        val hasIpv4: Boolean
        val hasIpv6: Boolean

        if (networks == null) {
            // No network info yet; PTMODEAUTO is the safest until the first network event arrives
            Logger.d(LOG_TAG_VPN, "calculatePtMode: no underlying networks yet, chosen PTMODEAUTO")
            return AppConfig.ProtoTranslationMode.PTMODEAUTO
        }

        if (networks.useActive) {
            // setUnderlyingNetworks(null); use  active network.
            // iterate each protocol list and verify the entry matches cm.activeNetwork.
            val activeNetwork = cm.activeNetwork
            if (activeNetwork == null) {
                // cm.activeNetwork not yet available; treat the first known network as active.
                // Pick the first network from whichever list is non-empty and check what
                // protocols that single network supports across both lists.
                val firstNet = networks.ipv4Net.firstOrNull()?.network
                    ?: networks.ipv6Net.firstOrNull()?.network
                hasIpv4 = firstNet != null && networks.ipv4Net.any { isNetworkSame(it.network, firstNet) }
                hasIpv6 = firstNet != null && networks.ipv6Net.any { isNetworkSame(it.network, firstNet) }
                Logger.d(LOG_TAG_VPN, "calculatePtMode(useActive): no active nw, using first nw ${firstNet?.networkHandle}: v4=$hasIpv4, v6=$hasIpv6")
            } else {
                hasIpv4 = networks.ipv4Net.any { isNetworkSame(it.network, activeNetwork) }
                hasIpv6 = networks.ipv6Net.any { isNetworkSame(it.network, activeNetwork) }
                Logger.d(LOG_TAG_VPN, "calculatePtMode(useActive): activeNw=${activeNetwork.networkHandle}, v4=$hasIpv4, v6=$hasIpv6")
            }
        } else {
            // All underlying networks are in use; any network providing a protocol counts.
            hasIpv4 = networks.ipv4Net.isNotEmpty()
            hasIpv6 = networks.ipv6Net.isNotEmpty()
            Logger.d(LOG_TAG_VPN, "calculatePtMode(allNws): v4=$hasIpv4, v6=$hasIpv6")
        }

        val selectedProto = appConfig.getInternetProtocol()

        val mode = when {
            // ipv4-only network but ipv6 traffic
            hasIpv4 && !hasIpv6 && selectedProto.isIPv6() -> AppConfig.ProtoTranslationMode.PTMODEFORCE46
            // ipv6-only network but ipv4 traffic
            hasIpv6 && !hasIpv4 && selectedProto.isIPv4() -> AppConfig.ProtoTranslationMode.PTMODEFORCE64
            // auto / dual-stack mode but network has only one: force translation as needed
            (selectedProto.isIPv46() || selectedProto == InternetProtocol.ALWAYSv46) && (!hasIpv4 || !hasIpv6) ->
                AppConfig.ProtoTranslationMode.PTMODEFORCE
            else -> AppConfig.ProtoTranslationMode.PTMODEAUTO
        }

        Logger.i(
            LOG_TAG_VPN,
            "calculatePtMode: useActive=${networks.useActive}, hasIpv4=$hasIpv4, hasIpv6=$hasIpv6, selectedProto=$selectedProto, chosen: $mode"
        )
        return mode
    }

    private fun setTunMode() {
        val opts =
            appConfig.newTunnelOptions(
                this,
                getFakeDns(),
                calculatePtMode()
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
                val mtu = if (persistentState.useMaxMtu && !persistentState.routeRethinkInRethink) {
                    MAX_MTU
                } else {
                    mtu()
                }
                val nwMtu = mtu()
                // attempt seamless hand-off as described in VpnService.Builder.establish() docs
                // call unlink when the mode is relaxed.
                // In relaxed mode, if lockdown then tunnel will be restarted else only the
                // update will be called. In all other cases, unlink is invoked in
                // makeOrUpdateVpnAdapter(). only in this case, it must be called before
                // establishVpn().
                // added temporarily for testing because some OnePlus devices remain stuck in
                // the "connection was refused" state. If this issue occurs in production, we
                // can ask users to switch to relaxed mode to verify whether it resolves it.
                //
                // switching to relaxed mode results in the following:
                // 1. unlink() is called before establishVpn().
                // 2. tunFd is detached.
                // 3. dupTunFd is set to false in the tunnel.
                // 4. tunnel is restarted in lockdown mode or updated otherwise
                // update: only when the policy is set to relaxed the app is working as
                // expected, so always call unlink() before establishVpn() for all policies,
                // making that behaviour common for all policies
                withContext(CoroutineName(why) + serializer) {
                    val lockdown = underlyingNetworks?.vpnLockdown ?: isLockdown()
                    if (lockdown) {
                        vpnAdapter?.unlink()
                    }
                }
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
                        nwMtu,
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
        // Signal that the tunnel has been (re)established. This must fire in every brave
        // mode (DNS, Firewall, DNS+Firewall): this method is only ever called *after* a
        // successful adapter create/update (see restartVpn / updateTun), so by this point
        // VpnController.hasTunnel() is already true.
        VpnController.onConnectionStateChanged(State.WORKING)
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

    suspend fun refreshProxies() {
        Logger.i(LOG_TAG_VPN, "refresh proxies")
        vpnAdapter?.refreshProxies()
    }

    private suspend fun makeOrUpdateVpnAdapter(
        ctx: Context,
        tunFd: ParcelFileDescriptor,
        mtu: Int,
        nwMtu: Int,
        opts: AppConfig.TunnelOptions,
        p: Pair<Boolean, Boolean>
    ): Boolean =
        withContext(CoroutineName("makeVpn") + serializer) {
            val restartPolicy = VpnBuilderPolicy.fromOrdinalOrDefault(persistentState.vpnBuilderPolicy).vpnAdapterBehaviour
            val lockdown = underlyingNetworks?.vpnLockdown ?: isLockdown()
            val ok = true
            val noTun = false // should eventually call signalStopService(userInitiated=false)
            val protos = InternetProtocol.byProtos(p.first, p.second).value()
            // only for relaxed mode, the tunFd will be detached, earlier it was set to true
            // with a const FIRESTACK_MUST_DUP_TUNFD, see restartVpn() for more
            val firestackMustDupFd = restartPolicy != VpnBuilderPolicy.GoVpnAdapterBehaviour.PREFER_RESTART
            try {
                val fd = if (firestackMustDupFd) {
                    tunFd.fd.toLong()
                } else {
                    tunFd.detachFd().toLong()
                }
                vpnAdapter?.dupTunfd(firestackMustDupFd)

                if (vpnAdapter == null) {
                    // create a new vpn adapter
                    val ifaceAddresses = getAddresses()
                    Logger.i(LOG_TAG_VPN, "vpn-adapter doesn't exists, create one, fd: $fd, lockdown: $lockdown, protos: $protos, ifaddr: $ifaceAddresses, opts: $opts, mtu: $mtu, nwMtu: $nwMtu")
                    GoVpnAdapter.setLogLevel(persistentState.goLoggerLevel.toInt(), includeFileTrace = persistentState.includeFileTrace)
                    vpnAdapter = GoVpnAdapter(ctx, vpnScope, fd, ifaceAddresses, mtu, nwMtu, opts) // may throw
                    Logger.d(LOG_TAG_VPN, "vpn-adapter created with ifaddr: $ifaceAddresses, protos: $protos")
                    io("tunInit") { vpnAdapter?.initResolverProxiesPcap(opts) }
                    io("rpnCheck") { checkForPlusSubscription() }
                    return@withContext ok
                } else {
                    Logger.i(LOG_TAG_VPN, "vpn-adapter exists, fd: $fd, policy: ${restartPolicy.name}, lockdown: $lockdown, protos: $protos, mtu: $mtu, nwMtu: $nwMtu")
                    when (restartPolicy) {
                        VpnBuilderPolicy.GoVpnAdapterBehaviour.NEVER_RESTART -> {
                            // In vpn lockdown mode, unlink the adapter to close the previous file descriptor (fd)
                            // and use a new fd after creation. This should only be done in lockdown mode,
                            // as leaks are not possible.
                            // doing so also fixes 'endpoint closed' errors which are frequent in lockdown mode
                            // now unlink happens before creating new tunFd, see restartVpn()
                            /* if (lockdown) {
                                vpnAdapter?.unlink()
                            } */
                            // in case, if vpn-adapter exists, update the existing vpn-adapter
                            if (vpnAdapter?.updateLinkAndRoutes(fd, mtu, nwMtu, protos) == false) {
                                Logger.e(LOG_TAG_VPN, "err update vpn-adapter")
                                return@withContext noTun
                            }
                        }

                        VpnBuilderPolicy.GoVpnAdapterBehaviour.PREFER_RESTART -> {
                            // TODO: should we check for lockdown mode and decide to restart? or just restart always?
                            // if vpn-adapter exists, recreate vpn-adapter only on lockdown mode
                            if (lockdown) {
                                if (vpnAdapter?.restartTunnel(fd, mtu, nwMtu, protos) == false) {
                                    Logger.e(LOG_TAG_VPN, "err recreate vpn-adapter")
                                    return@withContext noTun
                                }
                            } else {
                                if (vpnAdapter?.updateLinkAndRoutes(fd, mtu, nwMtu, protos) == false) {
                                    Logger.e(LOG_TAG_VPN, "err update vpn-adapter")
                                    return@withContext noTun
                                }
                            }

                        }
                    }
                    setTunMode()
                    return@withContext ok
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "err new vpn-adapter: ${e.message}", e)
                return@withContext noTun
            } finally {
                try { // close the tunFd as GoVpnAdapter has its own copy
                    if (firestackMustDupFd) tunFd.close()
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
        withContext(Dispatchers.Main) {
            setUnderlyingNetworks(underlyingNws)
        }
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
        eventLogger.logMedium(EventType.SYSTEM_EVENT, "network disconnected, restart vpn",
            EventSource.SERVICE, userAction = false, details = "networks: ${networks.ipv4Net.size + networks.ipv6Net.size}")
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

            // Warn the user (once) when their selected IP protocol is unavailable on the
            // current underlying network(s); removes the notification when resolved.
            // Intentionally leaves onNetworkConnected/onNetworkDisconnected untouched.
            handleIpProtocolMismatch(networks)
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
        val lastRxTrafficTime = TunFlowManager.getLastRxTrafficTime()
        if (elapsed >= lastRxTrafficTime + DATA_STALL_THRESHOLD_MS) {
            Logger.w(LOG_TAG_VPN, "diags; no flow call for 30 seconds, restarting vpn, last: $lastRxTrafficTime")
            val reason = "diags ${elapsed/(10*1000L)}" // restart once in a given 10 sec interval
            vpnRestartTrigger.value = reason
        } else {
            Logger.d(LOG_TAG_VPN, "diags; flow call recd, no restart needed, last: $lastRxTrafficTime")
        }
    }

    // Lightweight, independent tracking of raw network lifecycle events delivered by
    // NetworkLifecycleObserver. Intentionally does NOT trigger any VPN orchestration
    // here (that is ConnectionMonitor's responsibility); this is a passive observer.
    override fun onNetworkEvent(
        network: Network,
        eventType: NetworkLifecycleObserver.EventType,
        ssid: String?
    ) {
        Logger.i(
            LOG_TAG_VPN,
            "nw lifecycle; ${eventType.name}, netId: ${ConnectionMonitor.netId(network.networkHandle)}, ssid: $ssid"
        )
        // Drive proxy refresh/pause/resume/re-add directly from the observer event.
        // Link-property changes (DNS, addresses) don't affect transport type or SSID --
        // the only two inputs the refresh logic consumes -- so skip them to avoid
        // redundant adapter work on every link-property callback.
        if (eventType == NetworkLifecycleObserver.EventType.LINK_PROPERTY_CHANGE) return
        io("nwLifecycle") {
            withContext(CoroutineName("nwLifecycle") + serializer) {
                refreshOrPauseOrResumeOrReAddProxiesFromObserver(network, eventType, ssid)
            }
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
            "getUnderlays: use active? ${networks.useActive}; fail-open? $failOpen; null on lockdown? $mustSetNullOnVpnLockdown; networks: ${underlays?.size}; null-underlay? ${underlays == null}"
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
        withContext(Dispatchers.Main) {
            setUnderlyingNetworks(underlyingNws)
        }
        tunUnderlyingNetworks = underlyingNws?.joinToString()
        var ipv4Ssid = ""
        var ipv6Ssid = ""
        networks.ipv4Net.forEach {
            ipv4Ssid = ipv4Ssid + it.network.networkHandle.toString() + "##" + (it.ssid.orEmpty())
        }
        networks.ipv6Net.forEach {
            ipv6Ssid = ipv6Ssid + it.network.networkHandle.toString() + "##" + (it.ssid.orEmpty())
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
                reason += "nwConnect;"
                vpnRestartTrigger.value = reason
                // not needed as the refresh is done in go, TODO: remove below code later
                // only after set links and routes, wg can be refreshed
                // if (isRoutesChanged) {
                // Logger.v(LOG_TAG_VPN, "refresh wg after network change")
                // refreshProxies()
                // }
            }
        } else {
            // update the network mtu even though no restart is needed
            // can set multiple times, no issues
            vpnAdapter?.setLinkMtu(mtu())
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
            eventLogger.logLow(EventType.PROXY_REFRESH, "refresh/pause/resume/readd proxies",
                EventSource.SERVICE, userAction = false, "nwChange, $isBoundNetworksChanged, ssidChanged: $isSsidChanged, ssid: ${networks.activeSsid}, refresh/pause/resume/readd proxies")
        }

        underlyingNetworks?.ipv4Net?.forEach {
            it.linkProperties?.linkAddresses?.forEach { ips ->
                Logger.i(
                    LOG_TAG_VPN,
                    "IPv4 link Address: ${ips.address.hostAddress}, prefix: ${ips.prefixLength}, flags: ${ips.flags}, scope: ${ips.scope}, all: $ips"
                )
            }
        }

        underlyingNetworks?.ipv6Net?.forEach {
            it.linkProperties?.linkAddresses?.forEach { ips ->
                Logger.i(
                    LOG_TAG_VPN,
                    "IPv6 link Address: ${ips.address.hostAddress}, prefix: ${ips.prefixLength}, flags: ${ips.flags}, scope: ${ips.scope}, all: $ips"
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
        val ssidChanged: Boolean = true,
        val reason: String = ""
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

        val useMaxMtu = persistentState.useMaxMtu && !persistentState.routeRethinkInRethink
        val tunMtu = tunMtu()
        logd(
            "tun: useMaxMtu? $useMaxMtu tunMtu:$tunMtu; old: ${old.minMtu}, new: ${new.minMtu}; oldaux: ${overlayNetworks.mtu}, newaux: ${aux.mtu}"
        )

        // mark mtu changed if any tunMtu differs from min mtu of new underlying & overlay network
        val mtuChanged = !useMaxMtu && tunMtu != min(new.minMtu, aux.mtu)
        val mtuChangedReason = "max{$useMaxMtu} tun{$tunMtu} net[${old.minMtu}->${new.minMtu}] aux[${overlayNetworks.mtu}->${aux.mtu}] chg = !useMaxMtu && tunMtu != min(new.minMtu, aux.mtu)"

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
        val routeChangeReason = "v4[b:$builderHas4,t:$tunWants4,v:$vpnHas4] v6[b:$builderHas6,t:$tunWants6,v:$vpnHas6], ok4? $ok4(b == t && b == v), ok6? $ok6(b == t && b == v), changed(!ok4 || !ok6)? {${!ok4 || !ok6}}"
        val routesChanged = !ok4 || !ok6

        logd("tun: has4: $builderHas4, wants4: $tunWants4, vpnHas4: $vpnHas4, has6: $builderHas6, wants6: $tunWants6, vpnHas6: $vpnHas6, routesChanged? $routesChanged")

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
                val netChangedReason = "v4[o:$oldActivHas4,n:$activHas4,ok:$okActiv4] v6[o:$oldActivHas6,n:$activHas6,ok:$okActiv6] chg(!ok4 || !ok6)? $netChanged"

                val ssidChanged = old.activeSsid != new.activeSsid
                val ssidChangedReason = "[o:${old.activeSsid},n:${new.activeSsid}] chg?{$ssidChanged}"
                var reason = ""
                if (routesChanged) { reason += routeChangeReason }
                if (netChanged) { reason += netChangedReason }
                if (mtuChanged) { reason += mtuChangedReason }
                if (ssidChanged) { reason += ssidChangedReason }
                logd("tun: oldActiv4: $oldActivHas4, newActiv4: $activHas4, oldActiv6: $oldActivHas6, newActiv6: $activHas6, netChanged? $netChanged")
                logd("tun: oldActiveSsid: ${old.activeSsid}, newActiveSsid: ${new.activeSsid}, ssidChanged? $ssidChanged")
                // for active networks, changes in routes includes all possible network changes;
                return NetworkChanges(routesChanged, netChanged, mtuChanged, ssidChanged, reason)
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
        val isOld4New4Same = isNetworkSame(oldFirst4, newFirst4)
        val isOld6New6Same = isNetworkSame(oldFirst6, newFirst6)
        val netChanged = !isOld6New6Same || !isOld4New4Same
        val netChangedReason = "v4[o:$oldFirst4,n:$newFirst4,s:$isOld4New4Same] v6[o:$oldFirst6,n:$newFirst6,s:$isOld6New6Same], changed(!v4s || !v6s)? {$netChanged}"

        val oldSsidFirst4 = old.ipv4Net.firstOrNull()?.ssid
        val newSsidFirst4 = new.ipv4Net.firstOrNull()?.ssid
        val oldSsidFirst6 = old.ipv6Net.firstOrNull()?.ssid
        val newSsidFirst6 = new.ipv6Net.firstOrNull()?.ssid
        val ssidChanged = oldSsidFirst4 != newSsidFirst4 || oldSsidFirst6 != newSsidFirst6
        val ssidChangedReason = "v4[o:$oldSsidFirst4,n:$newSsidFirst4] v6[o:$oldSsidFirst6,n:$newSsidFirst6] chg?{$ssidChanged}"

        logd("tun: oldFirst4: $oldFirst4, newFirst4: $newFirst4, oldFirst6: $oldFirst6, newFirst6: $newFirst6, netChanged? $netChanged")
        logd("tun: oldSsidFirst4: $oldSsidFirst4, newSsidFirst4: $newSsidFirst4, oldSsidFirst6: $oldSsidFirst6, newSsidFirst6: $newSsidFirst6, ssidChanged? $ssidChanged")
        var reason = ""
        if (routesChanged) { reason += routeChangeReason }
        if (netChanged) { reason += netChangedReason }
        if (mtuChanged) { reason += mtuChangedReason }
        if (ssidChanged) { reason += ssidChangedReason }

        return NetworkChanges(routesChanged, netChanged, mtuChanged, ssidChanged, reason)
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
                if (vpnAdapter == null) {
                    Logger.i(LOG_TAG_VPN, "setSystemAndDefaultDns: vpnAdapter is null, not setting system/default dns")
                    return@io
                }
                prevDns.addAll(dnsServers)
                val dns = dnsServers.map { it.hostAddress }
                val isSysDnsSet = vpnAdapter?.setSystemDns(dns)
                var defSet: Boolean? = false
                // set default dns server for the tunnel if none is set
                if (isDefaultDnsNone()) {
                    val dnsCsv = dns.joinToString(",")
                    defSet = vpnAdapter?.addDefaultTransport(dnsCsv)
                }
                Logger.i(LOG_TAG_VPN, "setSystemAndDefaultDns: sys-dns set? $isSysDnsSet, def-set? $defSet/none? ${isDefaultDnsNone()}")

                val id = if (appConfig.isSmartDnsEnabled()) Backend.Plus else Backend.Preferred
                val mainDnsStatus = vpnAdapter?.getDnsStatus(id)
                val mainDnsOK = mainDnsStatus != null && mainDnsStatus != Backend.DEnd
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

    /**
     * compares the user-selected protocols against the protocols actually present on the
     * current underlying network(s). mismatch can exist only for the (IPv4 / IPv6) modes.
     * when the chosen stack is absent from the network while the other one
     * is present (e.g. ipv4 selected on an ipv6-only network). The modes "Auto"
     * (IPv46) and "IPv4 & IPv6" (ALWAYSv46) adjust to whatever is reachable, so they
     * never count as a mismatch.
     */
    private fun isIpProtocolMismatch(networks: ConnectionMonitor.UnderlyingNetworks): Boolean {
        val hasV4 = networks.ipv4Net.isNotEmpty()
        val hasV6 = networks.ipv6Net.isNotEmpty()
        if (!hasV4 && !hasV6) return false
        return when (InternetProtocol.getInternetProtocol(persistentState.internetProtocolType)) {
            InternetProtocol.IPv4 -> !hasV4 && hasV6
            InternetProtocol.IPv6 -> !hasV6 && hasV4
            else -> false // IPv46 (Auto) & ALWAYSv46 (IPv4 & IPv6) adapt to available networks
        }
    }

    /**
     * Posts the protocol-mismatch warning once per mismatch and dismisses it the moment the
     * network recovers or the user switches modes. Uses [compareAndSet] so that the
     * back-to-back network-change callbacks never duplicate or flood the notification.
     */
    private fun handleIpProtocolMismatch(networks: ConnectionMonitor.UnderlyingNetworks) {
        if (isIpProtocolMismatch(networks)) {
            if (ipMismatchNotifShown.compareAndSet(false, true)) {
                Logger.i(
                    LOG_TAG_VPN,
                    "ip proto mismatch; selected: ${persistentState.internetProtocolType}, v4 nets: ${networks.ipv4Net.size}, v6 nets: ${networks.ipv6Net.size}"
                )
                showIpMismatchNotification()
            }
        } else if (ipMismatchNotifShown.compareAndSet(true, false)) {
            Logger.i(LOG_TAG_VPN, "ip proto mismatch resolved; removing notification")
            cancelIpMismatchNotification()
        }
    }

    private fun showIpMismatchNotification() {
        ui {
            // NOTIF_CHANNEL_ID_FIREWALL_ALERTS, make sure it exists on this path too.
            if (isAtleastO()) {
                val name: CharSequence = getString(R.string.notif_channel_vpn_failure)
                val channel = NotificationChannel(
                    NOTIF_CHANNEL_ID_FIREWALL_ALERTS, name, NotificationManager.IMPORTANCE_HIGH
                )
                channel.description = getString(R.string.notif_channel_desc_firewall_alerts)
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(1000)
                notificationManager.createNotificationChannel(channel)
            }

            // tapping the notification deep-links the user straight into TunnelSettingsActivity
            // where they can switch to "IPv4 & IPv6" or "Auto".
            val intent = Intent(this, TunnelSettingsActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                NOTIF_ACTION_MODE_IP_MISMATCH,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val content = getString(R.string.ip_mismatch_notification_content)
            val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(getString(R.string.ip_mismatch_notification_title))
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            builder.color = ContextCompat.getColor(this, getAccentColor(persistentState.theme))
            notificationManager.notify(IP_MISMATCH_NOTIFICATION_ID, builder.build())
        }
    }

    private fun cancelIpMismatchNotification() {
        ui {
            try {
                notificationManager.cancel(IP_MISMATCH_NOTIFICATION_ID)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_VPN, "err cancelling ip mismatch notification: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        // Dismiss the firewall bubble and tear down its observer.
        //
        // Lifecycle note: onDestroy() is called ONLY when the VPN is truly stopping
        // (via signalStopService -> stopSelf). Seamless restarts use establishVpn() for
        // hand-off and do NOT destroy the service, so the bubble stays alive across
        // restarts without any flicker. Therefore it is safe — and correct — to dismiss
        // the bubble here: this is the "VPN turned off" signal.
        //
        // The bubble is re-shown on the next VPN start via handleFirewallBubbleIfNeeded()
        // in onCreate(). When permissions are already granted, showBubble() posts the
        // backing notification with setSuppressNotification(true), so the bubble appears
        // silently (no shade entry, no heads-up).
        if (isAtleastQ()) {
            try {
                unobserveBubbleBlockedConns()
                BubbleHelper.dismissBubble(this)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_VPN, "Bubble cleanup on destroy error: ${e.message}")
            }
        }

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
        // reset the observer-tracked network set (observer is already stopped by now)
        observedNetworks = linkedSetOf()

        // TunFlowManager is a singleton that outlives the service; clear its
        // conn-tracking state so the next VPN session doesn't inherit stale
        // active connections / closable cids / rx-traffic timer from this one
        TunFlowManager.clear()

        unobserveOrbotStartStatus()
        unobserveAppInfos()
        unobserveDnsRelay()
        persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        // onVpnStart is also called from the main thread (ui)
        io("cmVpnStop") { connectionMonitor.onVpnStop() }
        networkLifecycleObserver.stop()
        GlobalProxyHandler.stop()
        wgProxyPingController.stopAll()
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

        // Use STOP_FOREGROUND_REMOVE so the persistent VPN-active notification clears
        // when the service ends. STOP_FOREGROUND_DETACH only strips the service's
        // foreground status while leaving the notification visible — which causes
        // a stale "RethinkDNS protected" notification to linger after the user has
        // stopped the VPN, until the user manually swipes it away or the service is
        // re-created. REMOVE is the right semantics for "we're done, clean up."
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

            val establish = withContext(Dispatchers.Main) {
                builder.establish()
            }
            return establish
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
                    if (!underlayIpv6) {
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
                    false
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
                val reason = "overlayNwChanged [reason: ${interestingNet.reason}], routes: $isRoutesChanged, mtu: $isMtuChanged, at: ${elapsedRealtime()}"
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
            if (persistentState.customLanIpMode) {
                // gateway
                val gatewayIp4 = persistentState.customLanGatewayIpv4
                val ipParts = gatewayIp4.split("/")
                val gwIp4 = ipParts[0]
                val prefixGwIp4 = ipParts[1].toIntOrNull() ?: 32
                b.addRoute(HostName(gwIp4).toString(), prefixGwIp4)
                // dns
                val customDns4 = persistentState.customLanDnsIpv4
                val dnsIpParts = customDns4.split("/")
                val dnsIp4 = dnsIpParts[0]
                val prefixDnsIp4 = dnsIpParts[1].toIntOrNull() ?: 32
                b.addRoute(HostName(dnsIp4).toString(), prefixDnsIp4)
                // router
                val router4 = persistentState.customLanRouterIpv4
                val routerIpParts = router4.split("/")
                val routerIp4 = routerIpParts[0]
                val prefixRouterIp4 = routerIpParts[1].toIntOrNull() ?: 32
                b.addRoute(HostName(routerIp4).toString(), prefixRouterIp4)
            } else {
                b.addRoute(LanIp.GATEWAY.make(IPV4_TEMPLATE), 32)
                b.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
                b.addRoute(LanIp.ROUTER.make(IPV4_TEMPLATE), 32)
            }
        } else {
            Logger.i(LOG_TAG_VPN, "addRoute4: privateIps is false, adding default route")
            // no need to exclude LAN traffic, add default route which is 0.0.0.0/0
            b.addRoute(Constants.UNSPECIFIED_IP_IPV4, Constants.UNSPECIFIED_PORT)
        }

        return b
    }

    private fun addIfAddress4(b: Builder): Builder {
        val isCustomGateway = persistentState.customLanIpMode
        if (isCustomGateway) {
            val customIp = persistentState.customLanGatewayIpv4
            Logger.i(LOG_TAG_VPN, "addIfAddress4: using custom gateway ip: $customIp")
            // split the address and prefix length "$ip/$prefix"
            val ip4Parts = customIp.split("/")
            val ip4 = HostName(ip4Parts[0]).toString()
            b.addAddress(ip4, ip4Parts[1].toIntOrNull() ?: IPV4_PREFIX_LENGTH)
        } else {
            b.addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
        }
        return b
    }

    private fun addIfAddress6(b: Builder): Builder {
        val isCustomGateway = persistentState.customLanIpMode
        if (isCustomGateway) {
            val customIp = persistentState.customLanGatewayIpv6
            Logger.i(LOG_TAG_VPN, "addIfAddress6: using custom gateway ip: $customIp")
            // split the address and prefix length "$ip/$prefix"
            val ipParts = customIp.split("/")
            val ip6 = HostName(ipParts[0]).toString()
            b.addAddress(ip6, ipParts[1].toIntOrNull() ?: IPV6_PREFIX_LENGTH)
        } else {
            b.addAddress(LanIp.GATEWAY.make(IPV6_TEMPLATE), IPV6_PREFIX_LENGTH)
        }
        return b
    }

    private fun addDnsServer4(b: Builder): Builder {
        val isCustomLanDns = persistentState.customLanIpMode
        if (isCustomLanDns) {
            val customDns = persistentState.customLanDnsIpv4
            Logger.i(LOG_TAG_VPN, "addDnsServer4: using custom dns ip: $customDns")
            val dnsIp = customDns.split("/")
            val dns = HostName(dnsIp[0]).toString()
            b.addDnsServer(dns)
        } else {
            b.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
        }
        return b
    }

    private fun addDnsServer6(b: Builder): Builder {
        val isCustomLanDns = persistentState.customLanIpMode
        if (isCustomLanDns) {
            val customDns = persistentState.customLanDnsIpv6
            Logger.i(LOG_TAG_VPN, "addDnsServer6: using custom dns ip: $customDns")
            val dnsIp = customDns.split("/")
            val dns = HostName(dnsIp[0]).toString()
            b.addDnsServer(dns)
        } else {
            b.addDnsServer(LanIp.DNS.make(IPV6_TEMPLATE))
        }
        return b
    }

    private fun addDnsRoute4(b: Builder): Builder {
        val isCustomLanDns = persistentState.customLanIpMode
        if (isCustomLanDns) {
            val customDns = persistentState.customLanDnsIpv4
            Logger.i(LOG_TAG_VPN, "addDnsRoute4: using custom dns ip: $customDns")
            val ipParts = customDns.split("/")
            val ip = HostName(ipParts[0]).toString()
            b.addRoute(ip, ipParts[1].toIntOrNull() ?: 32)
        } else {
            b.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
        }
        return b
    }

    // builder.addRoute() when the app is in DNS only mode
    private fun addDnsRoute6(b: Builder): Builder {
        val isCustomLanDns = persistentState.customLanIpMode
        if (isCustomLanDns) {
            val customDns = persistentState.customLanDnsIpv6
            Logger.i(LOG_TAG_VPN, "addDnsRoute6: using custom dns ip: $customDns")
            val ipParts = customDns.split("/")
            val ip = HostName(ipParts[0]).toString()
            b.addRoute(ip, ipParts[1].toIntOrNull() ?: 128)
        } else {
            b.addRoute(LanIp.DNS.make(IPV6_TEMPLATE), 128)
        }
        return b
    }

    private fun getFakeDns(): String {
        val isCustomLanDns = persistentState.customLanIpMode
        if (isCustomLanDns) {
            val customDnsV4 = persistentState.customLanDnsIpv4
            val customDnsV6 = persistentState.customLanDnsIpv6
            Logger.i(
                LOG_TAG_VPN,
                "getFakeDns: using custom dns ips: v4: $customDnsV4, v6: $customDnsV6"
            )
            val ipv4 = customDnsV4.split(",") .joinToString(",") {
                val ipParts = it.split("/")
                HostName(IPAddressString(ipParts[0]).address, KnownPorts.DNS_PORT).toString()
            }
            val ipv6 = customDnsV6.split(",") .joinToString(",") {
                val ipParts = it.split("/")
                HostName(IPAddressString(ipParts[0]).address, KnownPorts.DNS_PORT).toString()
            }
            return "$ipv4,$ipv6"
        } else {
            val ipv4 = LanIp.DNS.make(IPV4_TEMPLATE, KnownPorts.DNS_PORT)
            val ipv6 = LanIp.DNS.make(IPV6_TEMPLATE, KnownPorts.DNS_PORT)
            // now fakedns will be only set during first time vpn is started, so set both ipv4 and ipv6
            // addresses, so that if the network changes doesn't affect
            return "$ipv4,$ipv6"
        }
    }

    private fun getAddresses(): String {
        val isCustomLanIp = persistentState.customLanIpMode
        if (isCustomLanIp) {
            val customGatewayV4 = persistentState.customLanGatewayIpv4
            val customGatewayV6 = persistentState.customLanGatewayIpv6
            Logger.i(
                LOG_TAG_VPN,
                "getAddresses: using custom gateway ips: v4: $customGatewayV4, v6: $customGatewayV6"
            )
            val ip4Parts = customGatewayV4.split("/")
            val prefix4 = ip4Parts[1].toIntOrNull() ?: IPV4_PREFIX_LENGTH
            val ipv4 = IPAddressString("${ip4Parts[0]}/$prefix4").address.toNormalizedString()
            val ip6Parts = customGatewayV6.split("/")
            val prefix6 = ip6Parts[1].toIntOrNull() ?: IPV6_PREFIX_LENGTH
            val ipv6 = IPAddressString("${ip6Parts[0]}/$prefix6").address.toNormalizedString()
            return "$ipv4,$ipv6"
        } else {
            val ipv4 = IPAddressString("${LanIp.GATEWAY.make(IPV4_TEMPLATE)}/$IPV4_PREFIX_LENGTH")
            val ipv6 = IPAddressString("${LanIp.GATEWAY.make(IPV6_TEMPLATE)}/$IPV6_PREFIX_LENGTH")
            return "${ipv4.address.toNormalizedString()},${ipv6.address.toNormalizedString()}"
        }
    }

    private fun <T> go2kt(co: CoFactory<T>, f: suspend() -> T): T = runBlocking {
        // runBlocking blocks the current thread until all coroutines within it are complete
        // a call a suspending function from a non-suspending context and obtain the result.
        return@runBlocking co.tryDispatch(f)
    }

    private suspend fun <T> ioCtx(s: String, f: suspend () -> T): T =
        withContext(CoroutineName(s) + Dispatchers.IO) { f() }


    private fun io(s: String, f: suspend () -> Unit) =
        vpnScope.launch(CoroutineName(s) + Dispatchers.IO) { f() }

    private fun ui(f: suspend () -> Unit) = vpnScope.launch(Dispatchers.Main) { f() }

    private suspend fun uiCtx(s: String, f: suspend () -> Unit) =
        withContext(CoroutineName(s) + Dispatchers.Main) { f() }

    override fun onQuery(origin: String, uidGostr: String, fqdn: String, qtype: Long): DNSOpts {
        return go2kt(dnsQueryDispatcher) {
            val d = TunDnsManager.DnsParams(origin, uidGostr, fqdn, qtype, isLockdown(), getUnderlyingSsid(), isIfaceCellular(""))
            TunDnsManager.handleOnQuery(d)
        }
    }

    private fun getUnderlyingSsid(): String? {
        return underlyingNetworks?.activeSsid ?: underlyingNetworks?.ipv4Net?.firstOrNull { it.ssid != null }?.ssid ?: underlyingNetworks?.ipv6Net?.firstOrNull { it.ssid != null }?.ssid
    }

    override fun onResponse(summary: DNSSummary?) {
        TunDnsManager.handleOnResponse(summary) { region ->
            if (region != null && regionLiveData.value != region) {
                regionLiveData.postValue(region)
            }
        }
    }

    fun getRegionLiveData(): LiveData<String> {
        return regionLiveData
    }

    override fun onProxiesStopped() {
        // no need to remove the dnses as tunnel will be taking care of removing all
        logd("onProxiesStopped")
    }

    override fun onProxyAdded(pid: String?, handle: String): Unit = go2kt(proxyAddedDispatcher) {
        if (pid == null) {
            Logger.e(LOG_TAG_VPN, "onProxyAdded: received null id")
            return@go2kt
        }

        if (!pid.contains(ID_WG_BASE, true) && !pid.contains(Backend.RpnWin, true)) {
            // only wireguard / rpn proxies are considered for overlay network
            logd("onProxyAdded: no-op as it is not wg/rpn proxy, added $pid")
            return@go2kt
        }

        // new proxy added, refresh overlay network pair
        io("nwChgOverlay") {
            val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
            logd("onProxyAdded for proxy $pid: $nw")
            onOverlayNetworkChanged(nw ?: OverlayNetworks())
        }

        io("proxyAdded") {
            if (pid.contains(Backend.RpnWin, true) && RpnProxyManager.isRpnActive()) {
                logd("onProxyAdded: rpn proxy added $pid, handle post addition logics")
                vpnAdapter?.handleOnRpnAddedOrUpdated(pid)
            } else if (pid.contains(ID_WG_BASE, true)) {
                logd("onProxyAdded: wg proxy added $pid, handle post addition logics")
                vpnAdapter?.handleOnWgAdded(pid)
            }
            refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    override fun onProxyRemoved(pid: String?, handle: String) {
        if (pid == null) {
            Logger.e(LOG_TAG_VPN, "onProxyAdded: received null id")
            return
        }

        if (!pid.contains(ID_WG_BASE) && !pid.contains(Backend.RpnWin)) {
            // only wireguard proxies are considered for overlay network
            logd("onProxyRemoved: proxy removed $pid, not wg or rpn proxy, no-op for overlay network")
            return
        }
        // proxy removed, refresh overlay network pair
        io("rmvProxy") {
            val nw: OverlayNetworks? = vpnAdapter?.getActiveProxiesIpAndMtu()
            logd("onProxyRemoved for proxy $pid: $nw")
            onOverlayNetworkChanged(nw ?: OverlayNetworks())
        }
        io("rmvProxyDns") {
            val rmvd = vpnAdapter?.handleOnProxyRemoved(pid)
            Logger.i(LOG_TAG_VPN, "onProxyRemoved: handled proxy removed for $pid, success? $rmvd")
        }
    }

    override fun onProxyStopped(id: String?, handle: String) {
        // no-op
        Logger.v(LOG_TAG_VPN, "onProxyStopped: $id")
    }

    override fun onProxyUpdated(pid: String, handle: String) {
        Logger.v(LOG_TAG_VPN, "onProxyUpdated: $pid, handle: $handle")
        io("proxyUpd") {
            if (pid.contains(Backend.RpnWin, true) && RpnProxyManager.isRpnActive()) {
                logd("onProxyUpdated: rpn proxy added $pid, handle post addition logics")
                vpnAdapter?.handleOnRpnAddedOrUpdated(pid)
            } else if (pid.contains(ID_WG_BASE, true)) {
                logd("onProxyUpdated: wg proxy added $pid, handle post addition logics")
                vpnAdapter?.handleOnWgAdded(pid)
            }
            // pause/resume option is not handled here as firestack is taking care of maintaining
            // the state of the proxies
        }
    }

    override fun onDNSAdded(id: String?) {
        // no-op
        Logger.v(LOG_TAG_VPN, "onDNSAdded: $id")
    }

    override fun onDNSRemoved(id: String?) {
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

    override fun onUpstreamAnswer(
        id: String,
        smm: DNSSummary,
        rcvdDnsOpts: DNSOpts,
        ipcsv: String
    ): DNSOpts {
        return go2kt(upstreamQueryDispatcher) {
            val params = TunDnsManager.UpstreamAnswerParams(
                scope = vpnScope,
                id = id,
                smm = smm,
                rcvdDnsOpts = rcvdDnsOpts,
                ipcsv = ipcsv,
                isLockdown = isLockdown(),
                isDeviceLocked = keyguardManager?.isKeyguardLocked == true && persistentState.getBlockWhenDeviceLocked(),
                underlyingNetworks = underlyingNetworks,
                keyguardManager = keyguardManager,
                connectivityManager = cm,
                accessibilityServiceFunctional = accessibilityServiceFunctional(),
                isSpecialApp = { uid -> TunFlowManager.isSpecialApp(uid) },
                isConnectionMetered = { dst -> TunFlowManager.isConnectionMetered(buildFlowContext(), dst) },
                determineProxyDetails = { connTracker, rinr, forUpstreamAnswer ->
                    TunFlowManager.determineProxyDetails(buildFlowContext(), connTracker, rinr, forUpstreamAnswer)
                },
                onDeviceLocked = { TunFlowManager.closeTrackedConnsOnDeviceLock(buildFlowContext()) },
                onAccessibilityFailure = ::handleAccessibilityFailure
            )
            TunDnsManager.handleOnUpstreamAnswer(params)
        }
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

    // no need of go2kt here as it is called from go and just performs db operations
    // requires go2kt if there any calls to go functions
    override fun postflow(s: FlowSummary?) {
        TunFlowManager.handlePostflow(buildFlowContext(), s)
    }

    override fun preflow(protocol: Int, uid: Int, src: String?, dst: String?): PreMark = go2kt(preflowDispatcher) {
        TunFlowManager.handlePreflow(buildFlowContext(), protocol, uid, src, dst)
    }

    override fun flow(
        protocol: Int,
        _uid: Int,
        src: String?,
        dst: String?,
        realIps: String?,
        d: String?,
        probableDomains: String?,
        blocklists: String?,
        isAlg: Boolean
    ): Mark = go2kt(flowDispatcher) {
        TunFlowManager.handleFlow(
            buildFlowContext(),
            protocol, _uid, src, dst, realIps, d, probableDomains, blocklists, isAlg
        )
    }

    override fun inflow(protocol: Int, recvdUid: Int, src: String?, dst: String?): Mark =
        go2kt(inflowDispatcher) {
            TunFlowManager.handleInflow(buildFlowContext(), protocol, recvdUid, src, dst)
        }

    // no need of go2kt here as it is called from go and performs db operations with no return value
    // requires go2kt if there any calls to go functions
    override fun flowing(m: Mark?) {
        TunFlowManager.handleFlowing(m)
    }

    private fun isLockdown(): Boolean {
        return isLockDownPrevious.get()
    }

    private fun isIfaceCellular(dst: String): Boolean {
        if (dst.isEmpty()) {
            val isActiveCellular = isActiveIfaceCellular()
            Logger.vv(LOG_TAG_VPN, "empty destination ip, active cellular? $isActiveCellular")
            return isActiveCellular
        }
        val dest = IPAddressString(dst)
        if (dest.isEmpty) {
            Logger.e(LOG_TAG_VPN, "invalid destination IP: $dst")
            return isActiveIfaceCellular()
        }

        val curnet = underlyingNetworks
        val cap =
            if (dest.isZero || dest.isIPv6) {
                if (curnet?.ipv6Net?.isEmpty() == true) {
                    return isActiveIfaceCellular()
                }
                curnet?.ipv6Net?.firstOrNull()?.capabilities
            } else {
                if (curnet?.ipv4Net?.isEmpty() == true) {
                    return isActiveIfaceCellular()
                }
                curnet?.ipv4Net?.firstOrNull()?.capabilities
            }
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

        val curnet = underlyingNetworks
        val cap =
            if (dest.isZero || dest.isIPv6) {
                if (curnet?.ipv6Net?.isEmpty() == true) {
                    return isActiveIfaceMetered()
                }
                curnet?.ipv6Net?.firstOrNull()?.capabilities
            } else {
                if (curnet?.ipv4Net?.isEmpty() == true) {
                    return isActiveIfaceMetered()
                }
                curnet?.ipv4Net?.firstOrNull()?.capabilities
            }

        if (cap == null) {
            Logger.e(LOG_TAG_VPN, "no network to be bound for $dst, use active network")
            return isActiveIfaceMetered()
        }
        return !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isActiveIfaceMetered(): Boolean {
        val curnet = underlyingNetworks ?: return false
        val now = elapsedRealtime()
        val ts = curnet.lastUpdated
        if (abs(now - ts) > Constants.ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            curnet.lastUpdated = now
            curnet.isActiveNetworkMetered = cm.isActiveNetworkMetered
        }
        return curnet.isActiveNetworkMetered
    }

    private fun isActiveIfaceCellular(): Boolean {
        val curnet = underlyingNetworks ?: return false
        val now = elapsedRealtime()
        val ts = curnet.lastUpdated
        if (abs(now - ts) > Constants.ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            curnet.lastUpdated = now
            val activeNetwork = cm.activeNetwork
            val cap = cm.getNetworkCapabilities(activeNetwork) ?: return false
            val isCellular = cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            curnet.isActiveNetworkCellular = isCellular
        }
        return curnet.isActiveNetworkCellular
    }

    private fun buildFlowContext(): TunFlowManager.FlowContext {
        return TunFlowManager.FlowContext(
            connectivityManager = cm,
            underlyingNetworks = underlyingNetworks,
            keyguardManager = keyguardManager,
            vpnAdapter = vpnAdapter,
            connTracer = connTracer,
            prevDns = prevDns,
            isPrimaryUser = isPrimaryUser(),
            context = this,
            notificationManager = notificationManager,
            scope = vpnScope,
            isLockdownEnabled = { isLockdown() },
            isAppPaused = { isAppPaused() },
            accessibilityServiceFunctional = { accessibilityServiceFunctional() },
            onAccessibilityFailure = ::handleAccessibilityFailure,
            onVpnLockdownStateChanged = ::handleVpnLockdownStateAsync,
            handleWgOrRpnProxiesToPing = { proxyId -> handleWgOrRpnProxiesToPing(proxyId) },
            isIfaceCellular = { dst -> isIfaceCellular(dst) },
            isIfaceMetered = { dst -> isIfaceMetered(dst) },
            isActiveIfaceCellular = { isActiveIfaceCellular() },
            isActiveIfaceMetered = { isActiveIfaceMetered() },
        )
    }

    suspend fun hasCid(connId: String, uid: Int): Boolean {
        return TunFlowManager.hasCid(buildFlowContext(), connId, uid)
    }

    suspend fun removeWireGuardProxy(id: Int) {
        logd("remove wg from tunnel: $id")
        vpnAdapter?.removeWgProxy(id)
    }

    suspend fun addWireGuardProxy(id: String, force: Boolean = false) {
        logd("add wg from tunnel: $id")
        vpnAdapter?.addWgProxy(id, force)
    }

    suspend fun readdCustomProxy() {
        logd("readd custom proxy")
        vpnAdapter?.readdCustomProxy()
    }

    suspend fun readdSocks5Proxy() {
        logd("readd socks5 proxy")
        vpnAdapter?.readdSocks5Proxy()
    }

    suspend fun readdHttpProxy() {
        logd("readd http proxy")
        vpnAdapter?.readdHttpProxy()
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
            val activeSsid = getUnderlyingSsid().orEmpty()
            Logger.v(LOG_TAG_VPN, "refreshOrPauseOrResumeOrReAddProxies: canResumeMobileOnlyWg? $isActiveMobile, curr-ssid: $activeSsid")
            io("refreshWg") { vpnAdapter?.refreshOrPauseOrResumeOrReAddProxies(isActiveMobile, activeSsid) }
        }
    }

    /**
     * variant of [refreshOrPauseOrResumeOrReAddProxies] driven entirely by network
     * lifecycle events delivered by [NetworkLifecycleObserver].
     */
    private suspend fun refreshOrPauseOrResumeOrReAddProxiesFromObserver(
        network: Network,
        eventType: NetworkLifecycleObserver.EventType,
        ssid: String?
    ) {
        logd("refresh wg config from observer; event: ${eventType.name}")
        updateObservedNetworks(network, eventType, ssid)
        observedNetworks = rearrangeObservedNetworks(observedNetworks)
        // first element after rearrange is the active network if present, else the
        // first non-metered network, else the first metered network.
        val priority = observedNetworks.firstOrNull()
        val isActiveMobile = priority?.capabilities
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
        val activeSsid = priority?.ssid.orEmpty()
        Logger.v(
            LOG_TAG_VPN,
            "refreshOrPauseOrResumeOrReAddProxiesFromObserver: event: ${eventType.name}, observed: ${observedNetworks.size}, canResumeMobileOnlyWg? $isActiveMobile, curr-ssid: $activeSsid"
        )
        val wgConfigs: List<Config> = WireguardManager.getActiveConfigs()
        val rpnConfigs: Set<CountryConfig> = RpnProxyManager.getEnabledConfigs()
        if (wgConfigs.isEmpty() && rpnConfigs.isEmpty()) {
            Logger.i(LOG_TAG_VPN, "$TAG no active wg-configs found")
            return
        }
        // pause or resume the proxies based on the mobile/ssid conditions
        vpnAdapter?.pauseAndResumeProxies(wgConfigs, rpnConfigs, isActiveMobile, activeSsid)
    }

    /**
     * updates [observedNetworks] for a single observer event. NETWORK_LOST removes the
     * network; NETWORK_ADDED and CAPABILITY_CHANGE has its [ConnectionMonitor.NetworkProperties]
     * from the live capabilities looked up via the connectivity manager. The SSID resolved
     * by the observer (can be non-null only for WiFi). Capabilities are required by
     * [ConnectionMonitor.NetworkProperties].
     */
    private fun updateObservedNetworks(
        network: Network,
        eventType: NetworkLifecycleObserver.EventType,
        ssid: String?
    ) {
        when (eventType) {
            NetworkLifecycleObserver.EventType.NETWORK_LOST -> {
                observedNetworks.removeIf { isNetworkSame(it.network, network) }
            }

            NetworkLifecycleObserver.EventType.NETWORK_ADDED,
            NetworkLifecycleObserver.EventType.CAPABILITY_CHANGE -> {
                if (!::cm.isInitialized) return
                observedNetworks.removeIf { isNetworkSame(it.network, network) }
                val cap = cm.getNetworkCapabilities(network) ?: return
                val nwType = ConnectionMonitor.networkType(cap)
                // linkProperties are unused by rearrange/refresh; pass null to keep the
                // observer path lightweight (no extra cm.getLinkProperties call).
                observedNetworks.add(
                    ConnectionMonitor.NetworkProperties(network, cap, null, nwType, ssid)
                )
            }

            NetworkLifecycleObserver.EventType.LINK_PROPERTY_CHANGE -> {
                // no-op: handled by the early-return in onNetworkEvent; kept exhaustive.
            }
        }
    }

    /**
     * reorder the active network first (if present),
     * then non-metered networks, then metered networks. Mirrors
     * ConnectionMonitor.NetworkRequestHandler.rearrangeNetworks
     */
    private fun rearrangeObservedNetworks(
        networks: LinkedHashSet<ConnectionMonitor.NetworkProperties>
    ): LinkedHashSet<ConnectionMonitor.NetworkProperties> {
        if (networks.isEmpty()) return networks
        val newNetworks: LinkedHashSet<ConnectionMonitor.NetworkProperties> = linkedSetOf()
        val activeNetwork = if (::cm.isInitialized) cm.activeNetwork else null
        // add active network first
        networks.firstOrNull { isNetworkSame(it.network, activeNetwork) }?.let {
            newNetworks.add(it)
        }
        // then non-metered networks
        networks.filter { isConnectionNotMetered(it.capabilities) }.forEach { newNetworks.add(it) }
        // then remaining (metered) networks
        networks.filter { !isConnectionNotMetered(it.capabilities) }.forEach { newNetworks.add(it) }
        return newNetworks
    }

    private fun isConnectionNotMetered(capabilities: NetworkCapabilities?): Boolean {
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false
    }

    suspend fun getDnsStatus(id: String): Int? {
        return vpnAdapter?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return vpnAdapter?.getRDNS(type)
    }

    suspend fun getProxyStatusById(id: String): Pair<Int?, String> {
        return vpnAdapter?.getProxyStatusById(id) ?: Pair(null, "adapter is null")
    }

    suspend fun getProxyAddrById(id: String): String? {
        return vpnAdapter?.getProxyAddrById(id)
    }

    suspend fun getProxyStats(id: String): RouterStats? {
        return vpnAdapter?.getProxyStats(id)
    }

    suspend fun getWireGuardStats(id: String): WireguardManager.WgStats? {
        return vpnAdapter?.getWireGuardStats(id)
    }

    suspend fun getLocalProxyStatsById(id: String): ProxyManager.ProxyStats? {
        return vpnAdapter?.getLocalProxyStatsById(id)
    }

    suspend fun getRpnStats(id: String): RpnProxyManager.RpnStats? {
        return vpnAdapter?.getRpnStats(id)
    }

    suspend fun getDnsIps(id: String): String? {
        return vpnAdapter?.getDnsIps(id)
    }

    suspend fun getRpnAddlInfo(id: String): RpnProxyManager.ActiveRpnAddlInfo? {
        return vpnAdapter?.getRpnAddlInfo(id)
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
        if (level >= TRIM_MEMORY_BACKGROUND) {
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
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(MEMORY_NOTIFICATION_ID, builder.build())
    }

    override fun onRevoke() {
        // System invokes onRevoke when the user takes an explicit action that
        // disables this VPN: (a) toggles RethinkDNS off in Android Settings →
        // Network & internet → VPN, (b) selects a different VPN app, or
        // (c) switches Always-On VPN to a different app. In every one of those
        // cases the user IS deliberately stopping us — not a crash, not a
        // transient error. Treat as user-initiated so signalStopService can
        // clear "VPN should be running" persistent state; otherwise the
        // autostart receiver relaunches us on the next boot / unlock /
        // package-replace because activationRequested is still true.
        Logger.i(LOG_TAG_VPN, "onRevoke, treating as user-initiated stop")
        signalStopService("revoked", userInitiated = true)
    }

    suspend fun getSystemDns(): String {
        return vpnAdapter?.getSystemDns().orEmpty()
    }

    suspend fun getNetStat(): NetStat? {
        return vpnAdapter?.getNetStat()
    }

    fun writeConsoleLog(log: ConsoleLog) {
        netLogTracker.writeConsoleLog(log)
    }

    suspend fun isRpnReachable(csv: String): Boolean { // can be ippcsv or hostpcsv
        return vpnAdapter?.isRpnReachable(csv) == true
    }

    suspend fun testRpnProxy(): Boolean {
        return vpnAdapter?.testRpnProxy() == true
    }

    suspend fun testHop(src: String, hop: String): Pair<Boolean, String?> {
        return vpnAdapter?.testHop(src, hop) ?: Pair(false, "vpn not active")
    }

    suspend fun hopStatus(src: String, via: String): Pair<Int?, String> {
        return vpnAdapter?.hopStatus(src, via) ?: Pair(null, "vpn not active")
    }

    suspend fun removeHop(src: String): Pair<Boolean, String> {
        return vpnAdapter?.removeHop(src) ?: Pair(false, "vpn not active")
    }

    suspend fun getRpnProps(type: RpnType): Pair<RpnProxyManager.RpnProps?, String?> {
        return vpnAdapter?.getRpnProps(type) ?: Pair(null, null)
    }

    suspend fun getRpnLocations(type: RpnType): Pair<RpnServers?, String?> {
        return vpnAdapter?.getRpnLocations(type) ?: Pair(null, null)
    }

    suspend fun registerAndFetchWinIfNeeded(entitlementBytes: ByteArray?, stateBytes: ByteArray?, deviceId: String): ByteArray? {
        return vpnAdapter?.registerAndFetchWinIfNeeded(entitlementBytes, stateBytes, deviceId)
    }

    suspend fun getEntitlementDetails(prevBytes: ByteArray?, deviceId: String): RpnEntitlement? {
        return vpnAdapter?.getEntitlementDetails(prevBytes, deviceId)
    }

    suspend fun isWinRegistered(): Boolean {
        return vpnAdapter?.isWinRegistered() == true
    }

    suspend fun unregisterWin(): Boolean {
        return vpnAdapter?.unregisterWin() == true
    }

    suspend fun updateWin(): ByteArray? {
        return vpnAdapter?.updateWin()
    }

    suspend fun onRpnOptsChange() {
        vpnAdapter?.onRpnOptsChange()
    }

    suspend fun hasProxy(id: String): Boolean {
        return vpnAdapter?.hasProxy(id) ?: false
    }

    suspend fun hasRpnProxy(id: String): Boolean {
        return vpnAdapter?.hasRpnProxy(id) ?: false
    }

    suspend fun stopRpnProxy(): Boolean {
        return vpnAdapter?.stopRpnProxy() ?: false
    }

    suspend fun getWinExpiryTs(): Long? {
        return vpnAdapter?.getWinExpiryTs()
    }

    suspend fun createWgHop(origin: String, hop: String): Pair<Boolean, String> {
        return (vpnAdapter?.createHop(origin, hop)) ?: Pair(false, "adapter is null")
    }

    suspend fun vpnStats(): String {
        // create a string with the stats, add stats of firewall, dns, proxy, builder
        // other key stats
        val stats = StringBuilder()
        stats.append("VPN Stats:\n")
        stats.append("Builder:\n${builderStats()}\n")
        stats.append("General:\n${generalStats()}\n")
        stats.append("Firewall:\n${firewallStats()}\n")
        stats.append("IpRules:\n${ipRulesStats()}\n")
        stats.append("DomainRules:\n${domainRulesStats()}\n")
        stats.append("Proxy:\n${proxyStats()}\n")
        stats.append("WireGuard:\n${wireguardStats()}\n")
        stats.append("RPN Wg:\n${rpnStats()}\n")
        return stats.toString()
    }

    fun performConnectivityCheck(controller: Controller, id: String, addrPort: String): Boolean {
        return vpnAdapter?.performConnectivityCheck(controller, id, addrPort) ?: false
    }

    fun performAutoConnectivityCheck(controller: Controller, id: String, mode: String): Boolean {
        return vpnAdapter?.performAutoConnectivityCheck(controller, id, mode) ?: false
    }

    private suspend fun firewallStats(): String {
        return FirewallManager.stats()
    }

    private fun dnsStats(): String {
        return prevDns.joinToString()
    }


    private fun generalStats(): String {
        return appConfig.stats()
    }

    suspend fun getWinByKey(key: String): Proxy? {
        return vpnAdapter?.getWinByKey(key)
    }

    suspend fun getActiveEntitlement(): RpnEntitlement? {
        return vpnAdapter?.getActiveEntitlement()
    }

    suspend fun getWinIdentifier(): String? {
        return vpnAdapter?.getWinIdentifier()
    }

    suspend fun getWinProxyId(): String? {
        return vpnAdapter?.getWinProxyId()
    }

    suspend fun addNewWinServer(key: String): Pair<Boolean, String> {
        return vpnAdapter?.addNewWinServer(key) ?: Pair(false, "adapter is null")
    }

    suspend fun handleRpnHop(key: String, configChanged: Boolean): Pair<Boolean, String> {
        return vpnAdapter?.handleRpnHop(key, configChanged) ?: Pair(false, "adapter is null")
    }

    suspend fun removeWinServer(key: String): Pair<Boolean, String> {
        return vpnAdapter?.removeWinServer(key) ?: Pair(false, "adapter is null")
    }

    suspend fun refreshRpnProxy(id: String): Boolean {
        return vpnAdapter?.refreshRpnProxy(id) ?: false
    }

    suspend fun getRpnClientInfoById(id: String): Client? {
        return vpnAdapter?.getRpnClientInfoById(id)
    }

    suspend fun getWgClientInfoById(id: String): Client? {
        return vpnAdapter?.getWgClientInfoById(id)
    }

    suspend fun reconnectRpnProxy(id: String): Boolean {
        return vpnAdapter?.reconnectRpnProxy(id) ?: false
    }

    private suspend fun proxyStats(): String {
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

    private suspend fun rpnStats(): String {
        return RpnProxyManager.stats()
    }

    suspend fun crashTun(type: Long) {
        vpnAdapter?.crashTun(type)
    }

    fun screenUnlock() {
        io("screenUnlock") {
            // initiate wireguard ping for one wg, catch-all, hop proxies
            val proxies = WireguardManager.getActiveConfigs()
            Logger.i(LOG_TAG_VPN, "rpnProxiesToPing: initiate ping for one-wg/catchall/hop/rpn proxies")
            proxies.forEach { c ->
                val isOneWg = WireguardManager.getOneWireGuardProxyId() == c.getId()
                val isCatchAll = WireguardManager.getActiveCatchAllConfig().any { it.id == c.getId()}
                val isPartOfHop = WgHopManager.isWgEitherHopOrSrc(c.getId())
                if (isOneWg || isCatchAll || isPartOfHop) {
                    val id = ID_WG_BASE + c.getId()
                    if (persistentState.smartPersistentKeepalive) wgProxyPingController.startPing(id)
                    else initiateWgPing(id) // ping once
                }
            }
            if (RpnProxyManager.isRpnActive()) {
                val autoConfig = RpnProxyManager.getAutoServer()
                if (autoConfig?.catchAll == true) {
                    wgProxyPingController.startPing(Backend.RpnWin)
                } else {
                    initiateRpnPing(Backend.RpnWin) // ping once
                }
                // active win proxies will give the kids
                val activeProxyIds = vpnAdapter?.getActiveWinKidsProxies()
                activeProxyIds?.forEach {
                    val config = RpnProxyManager.getCountryConfigByKey(it.removePrefix(Backend.RpnWin))
                    if (config == null) {
                        Logger.e(LOG_TAG_VPN, "rpnProxiesToPing: kids returned from adapter has $it but missing from config")
                        return@forEach
                    }
                    if (config.catchAll) {
                        if (persistentState.smartPersistentKeepalive) wgProxyPingController.startPing(it)
                        else initiateRpnPing(it) // ping once
                    }
                }
            }
        }
    }

    // initiate ping for wg or rpn proxies if smart persistent keepalive is enabled,
    // this will initiate the ping if the proxy is not already running and is one of the following:
    suspend fun handleWgOrRpnProxiesToPing(proxyId: String) {
        if (!RpnProxyManager.isRpnActive() && !WireguardManager.isAnyWgActive()) {
            Logger.w(LOG_TAG_VPN, "rpnProxiesToPing: rpn/wg is not active, skip ping for $proxyId, selected proxy can be lockdown")
            return
        }

        val winId = if (RpnProxyManager.isRpnActive()) VpnController.getWinProxyId() else ""
        val active: Boolean = when {
            proxyId == winId -> {
                val config = RpnProxyManager.getAutoServer()
                if (config == null) {
                    Logger.w(LOG_TAG_VPN, "rpnProxiesToPing: auto config is null")
                    return
                }
                config.isEnabled
            }
            proxyId.startsWith(Backend.RpnWin) -> {
                val config = RpnProxyManager.getCountryConfigByKey(
                    proxyId.removePrefix(Backend.RpnWin)
                ) ?: run {
                    Logger.w(LOG_TAG_VPN, "rpnProxiesToPing: config is null for $proxyId")
                    return
                }
                config.isEnabled
            }
            proxyId.startsWith(ID_WG_BASE) -> {
                val config = try {
                    WireguardManager.getConfigFilesById(
                        proxyId.removePrefix(ID_WG_BASE).toInt()
                    )
                } catch (_: Exception) {
                    null
                } ?: run {
                    Logger.w(LOG_TAG_VPN, "rpnProxiesToPing: config is null for $proxyId")
                    return
                }
                config.isActive
            }
            else -> return
        }

        if (active) {
            Logger.d(LOG_TAG_VPN, "rpnProxiesToPing: start ping for proxy $proxyId")
            wgProxyPingController.startPing(proxyId)
        } else {
            Logger.vv(
                LOG_TAG_VPN,
                "rpnProxiesToPing: proxy $proxyId is not active, skip ping"
            )
        }
    }

    suspend fun memProfile(filepath: String) {
        vpnAdapter?.memProfile(filepath)
    }

    suspend fun cpuProfile(filepath: String) {
        vpnAdapter?.cpuProfile(filepath)
    }

    suspend fun initiateWgPing(proxyId: String) {
        vpnAdapter?.initiateWgPing(proxyId)
    }

    suspend fun initiateRpnPing(proxyId: String) {
        vpnAdapter?.initiateRpnPing(proxyId)
    }

    fun screenLock() {
        io("screenLock") {
            // no need to check for catch-all/hop/one-wg while stopping the ping, if pid is not
            // there stopPing is no-op
            val proxies = WireguardManager.getActiveConfigs()
            proxies.forEach { c ->
                val id = ID_WG_BASE + c.getId()
                wgProxyPingController.stopPing(id)
                Logger.d(LOG_TAG_VPN, "rpnProxiesToPing: stop ping for wg proxy $id during screen lock")
            }
            if (RpnProxyManager.isRpnActive()) {
                wgProxyPingController.stopPing(Backend.RpnWin)
                Logger.d(LOG_TAG_VPN, "rpnProxiesToPing: stop ping for auto win proxy during screen lock")
                val activeProxyIds = vpnAdapter?.getActiveWinKidsProxies()
                activeProxyIds?.forEach {
                    val config = RpnProxyManager.getCountryConfigByKey(it.removePrefix(Backend.RpnWin))
                    if (config == null) {
                        Logger.w(LOG_TAG_VPN, "rpnProxiesToPing: kids returned from adapter has $it but missing from config")
                        return@forEach
                    }
                    if (config.catchAll) {
                        wgProxyPingController.stopPing(it)
                        Logger.d(LOG_TAG_VPN, "rpnProxiesToPing: stop ping for catch-all kid proxy $it during screen lock")
                    }
                }
            }
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

    @RequiresApi(VERSION_CODES.Q)
    private fun handleFirewallBubbleIfNeeded() {
        if (!persistentState.firewallBubbleEnabled) {
            Logger.w(TAG, "Bubble disabled by user")
            unobserveBubbleBlockedConns()
            BubbleHelper.dismissBubble(this)
            return
        }

        // A notification (and therefore a bubble) cannot be shown without the
        // POST_NOTIFICATIONS permission on Android 13+. Treat it the same as disabled.
        if (!BubbleHelper.isNotificationPermissionGranted(this)) {
            Logger.w(TAG, "Notification permission not granted; not showing bubble")
            unobserveBubbleBlockedConns()
            BubbleHelper.dismissBubble(this)
            return
        }

        initializeBubble()
    }

    private fun unobserveBubbleBlockedConns() {
        if (this::blockedConnsObserver.isInitialized) {
            connTrackRepository.getBlockedConnectionsCountLiveData().removeObserver(blockedConnsObserver)
        }
        lastBlockedCount = -1
        Logger.i(TAG, "Bubble observer removed")
    }

    @RequiresApi(VERSION_CODES.Q)
    private fun initializeBubble() {
        try {
            // Request bubble. Bubbles are always backed by a notification, but we suppress the
            // shade entry via BubbleMetadata#setSuppressNotification(true).
            val eligible = BubbleHelper.showBubble(this, persistentState)
            Logger.i(TAG, "Bubble notification posted (eligible=$eligible)")

            // If not eligible, do not install observers / update loops.
            // Do not post any fallback notification (bubble-only UX).
            if (!eligible) {
                unobserveBubbleBlockedConns()
                return
            }

            blockedConnsObserver = makeFirewallBlockedConnsObserver()
            connTrackRepository.getBlockedConnectionsCountLiveData().observeForever(blockedConnsObserver)
        } catch (e: Exception) {
            Logger.e(TAG, "Bubble init failed: ${e.message}", e)
            stopSelf()
        }
    }
    private var lastBlockedCount = -1
    @RequiresApi(VERSION_CODES.Q)
    private fun makeFirewallBlockedConnsObserver(): Observer<Int> {
        return Observer { t ->
            // If user disabled bubble (or it was auto-disabled), stop observing.
            if (!persistentState.firewallBubbleEnabled) {
                try {
                    BubbleHelper.dismissBubble(this)
                    removeBubbleObserver()
                } catch (e: Exception) {
                    Logger.w(TAG, "err stopping bubble observer: ${e.message}")
                }
                return@Observer
            }

            if (t != lastBlockedCount) {
                // Pass persistentState so it can be disabled if bubbles are not allowed
                BubbleHelper.updateBubble(this, t, persistentState)
                lastBlockedCount = t
                Logger.d(TAG, "Bubble updated: $t blocked")

                // updateBubble can flip the toggle off (permission revoked). If that happened,
                // stop observing and clean up.
                if (!persistentState.firewallBubbleEnabled) {
                    BubbleHelper.dismissBubble(this)
                    removeBubbleObserver()
                }
            }
        }
    }

    @RequiresApi(VERSION_CODES.Q)
    private fun removeBubbleObserver() {
        if (this::blockedConnsObserver.isInitialized) {
            connTrackRepository.getBlockedConnectionsCountLiveData().removeObserver(blockedConnsObserver)
        }
        lastBlockedCount = -1
        Logger.i(TAG, "Bubble observer removed")
    }
}
