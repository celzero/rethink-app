/*
 * Copyright 2026 RethinkDNS and its authors
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

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import android.os.SystemClock.elapsedRealtime
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.IpInfoDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.ProxyManager.isNotLocalAndRpnProxy
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.intra.FlowSummary
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalCause
import com.google.common.cache.RemovalNotification
import inet.ipaddr.HostName
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetAddress
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TunFlowManager : KoinComponent {
    private const val TAG = "TunFlowManager"

    // Carries references from BraveVPNService that TunFlowManager doesn't own
    class FlowContext(
        val connectivityManager: ConnectivityManager,
        var underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        val keyguardManager: KeyguardManager?,
        val vpnAdapter: GoVpnAdapter?,
        val connTracer: ConnectionTracer,
        val prevDns: MutableSet<InetAddress>,
        val isPrimaryUser: Boolean,
        val context: Context,
        val notificationManager: NotificationManager,
        val scope: CoroutineScope,
        val isLockdownEnabled: () -> Boolean,
        val isAppPaused: () -> Boolean,
        val accessibilityServiceFunctional: () -> Boolean,
        val onAccessibilityFailure: () -> Unit,
        val onVpnLockdownStateChanged: () -> Unit,
        val handleWgOrRpnProxiesToPing: suspend (String) -> Unit,
        val isIfaceCellular: (String) -> Boolean,
        val isIfaceMetered: (String) -> Boolean,
        val isActiveIfaceCellular: () -> Boolean,
        val isActiveIfaceMetered: () -> Boolean,
    )

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

    // Koin-injected dependencies
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val netLogTracker by inject<NetLogTracker>()

    // Internal state previously in BraveVPNService
    private val rethinkUid: Int = Process.myUid()
    private var lastRethinkBlockReason: Int = -1
    private var lastRxTrafficTime: Long = elapsedRealtime()

    data class CidKey(val cid: String, val uid: Int)

    private val activeCidsMutex = Mutex()
    // used to store the conn-ids that are allowed and active, to show in network logs
    // as active connections. removed when the connection is closed (onSummary)
    private val activeCids = Collections.newSetFromMap(ConcurrentHashMap<CidKey, Boolean>())

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
    private val activeClosableCids = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val activeClosableCidsMutex = Mutex()

    private fun logd(msg: String) {
        Logger.d(LOG_TAG_VPN, "$TAG $msg")
    }

    private fun logv(msg: String) {
        Logger.v(LOG_TAG_VPN, "$TAG $msg")
    }

    // no need of go2kt here as it is called from go and just performs db operations
    // requires go2kt if there any calls to go functions
    fun handlePostflow(ctx: FlowContext, s: FlowSummary?) {
        if (s == null) {
            Logger.i(LOG_TAG_VPN, "received null summary for socket")
            return
        }

        if (s.id.isEmpty()) { // this should not happen, but just in case
            // in case of empty connId, insert a new entry
            val cid = "debug-" + Utilities.getRandomString(8)
            val uid = FirewallManager.appId(s.uid.toInt(), ctx.isPrimaryUser)
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

        val cm = getConnTrackerMetaData(s.id)
        if (cm != null) { // if the connection metadata is already tracked, insert with summary
            var proxyRule = ""
            if (s.pid.isNotEmpty() && isNotLocalAndRpnProxy(s.pid)) {
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
            // the conn-id was added to the active/closable sets in flow(); remove them now
            // before returning.
            removeTrackedCid(ctx.scope, cm.connId, cm.uid)
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
        if (s.rx > 0) {
            lastRxTrafficTime = elapsedRealtime()
        }

        try {
            val key: CidKey
            if (s.uid == rethinkUid.toString()) {
                // update rethink summary
                key = CidKey(connectionSummary.connId, rethinkUid)
                netLogTracker.updateRethinkSummary(connectionSummary)
            } else {
                // other apps summary
                // convert the uid to app id
                val uid = FirewallManager.appId(s.uid.toInt(), ctx.isPrimaryUser)
                key = CidKey(connectionSummary.connId, uid)
                netLogTracker.updateIpSummary(connectionSummary)
            }
            removeTrackedCid(ctx.scope, connectionSummary.connId, key.uid)
            io(ctx.scope, "dlIpInfo") {
                IpInfoDownloader.fetchIpInfoIfRequired(s.target)
            }
        } catch (e: NumberFormatException) {
            Logger.e(LOG_TAG_VPN, "onSocketClosed: ${e.message}", e)
        }
    }

    suspend fun handlePreflow(
        ctx: FlowContext,
        protocol: Int,
        uid: Int,
        src: String?,
        dst: String?
    ): PreMark {
        val srcIpPort = parseIpAndPort(src)
        val dstIpPort = parseIpAndPort(dst)
        Logger.d(LOG_TAG_VPN, "preflow: init, uid: $uid, rcvd: $src & $dst, parsed: $srcIpPort & $dstIpPort")
        val newUid = if (uid == INVALID_UID) { // fetch uid only if it is invalid
            val resolvedUid = getUid(
                ctx,
                uid,
                protocol,
                srcIpPort.first,
                srcIpPort.second,
                dstIpPort.first,
                dstIpPort.second
            )
            resolvedUid
        } else {
            uid
        }
        Logger.d(LOG_TAG_VPN, "preflow: $newUid, $srcIpPort, $dstIpPort")

        val p = PreMark()
        p.uid = newUid.toString()
        p.isUidSelf = newUid == rethinkUid
        Logger.i(LOG_TAG_VPN, "preflow: returning uid: ${p.uid} for src: $srcIpPort, dst: $dstIpPort, isRethink? ${p.isUidSelf}")
        return p
    }

    suspend fun handleFlow(
        ctx: FlowContext,
        protocol: Int,
        _uid: Int,
        src: String?,
        dst: String?,
        realIps: String?,
        d: String?,
        probableDomains: String?,
        blocklists: String?,
        isAlg: Boolean
    ): Mark {
        logd("flow: $_uid, $src, $dst, $realIps, $d, $probableDomains, $blocklists, $isAlg")
        ctx.onVpnLockdownStateChanged()

        val srcIpPort = parseIpAndPort(src)
        val dstIpPort = parseIpAndPort(dst)
        val srcIp = srcIpPort.first
        val srcPort = srcIpPort.second
        val dstIp = dstIpPort.first
        val dstPort = dstIpPort.second

        val rinr = persistentState.routeRethinkInRethink

        val ips = realIps?.split(",") ?: emptyList()
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
            ctx,
            _uid,
            protocol,
            srcIp,
            srcPort,
            dstIp,
            dstPort
        )
        // fixme: decide a behaviour on what uid to use when the firewall rules are
        // handled, either to let user to apply same firewall rules for same app in main user and
        // in private space or allow to create a separate set of rules for private space apps.
        // also see how the uid needs to be sent to the tunnel, because now dns uses per-app cache
        // which can trigger in bypass-dns rule
        uid = FirewallManager.appId(uid, ctx.isPrimaryUser)
        val userId = FirewallManager.userId(uid)

        // generates a random 6-byte value, converts it to hexadecimal, and then
        // provides the hexadecimal value as a string for connId
        val connId = Utilities.getRandomString(8)

        // TODO: handle multiple domains, for now, use the first domain
        var domains = d?.split(",") ?: emptyList()
        if (domains.isEmpty()) {
            // add probableDomains in case of empty domains, do this only when the log level is
            // debug or below, and when in play-version.
            val canAddProbableDomains = isPlayStoreFlavour() || Logger.LoggerLevel.fromId(persistentState.goLoggerLevel.toInt())
                ?.isLessThanOrEqualTo(Logger.LoggerLevel.DEBUG) == true
            if (canAddProbableDomains) {
                domains = probableDomains?.split(",") ?: emptyList()
            }
        }

        // if `d` is blocked, then at least one of the real ips is unspecified
        val anyRealIpBlocked = !ips.none { isUnspecifiedIp(it.trim()) }
        val connType =
            if (isConnectionMetered(ctx, realDestIp)) {
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
                blocklists.orEmpty(),
                domains.firstOrNull(),
                connId,
                connType
            )

        val trapVpnDns = isDns(dstPort) && isVpnDns(dstIp)
        val trapVpnPrivateDns = isVpnDns(dstIp) && isPrivateDns(dstPort)

        // always block, since the vpn tunnel doesn't serve dns-over-tls
        if (trapVpnPrivateDns) {
            logd("flow: trap vpn private dns ($dstIp), returning Ipn.Block, $uid, $domains")
            cm.isBlocked = true
            cm.blockedByRule = FirewallRuleset.RULE14.id
            return persistAndConstructFlowResponse(ctx, cm, Backend.Block, connId, uid)
        }

        // app is considered as spl when it is selected to forward dns proxy, socks5 or http proxy
        val isSplApp = isSpecialApp(uid)

        if (uid == rethinkUid && !rinr) {
            if (cm.query.isNullOrEmpty()) {
                // possible domains only used for logging purposes, it may be available if
                // the domains are empty. So, use the possibleDomains only if domains is empty
                // no need to show the possible domains other than rethink
                cm.query = probableDomains?.split(",")?.firstOrNull() ?: ""
            }

            // TODO: should handle the LanIp.GATEWAY, LanIp.ROUTER addresses as well
            // now only handling the LanIp.DNS address, handle it once go implementation is ready

            // if trapVpnDns is true, then Ipn.Exit won't be able to route the request via the
            // underlying network as the IP only exists within the VPN tunnel. So, use Ipn.Base
            // and expect Android's netd via the network engine to re-route as appropriate.
            val proxy =
                if (trapVpnDns) {
                    // on Android Q and below, the uid for local dns(to VPN's dns servers)
                    // is always DNS.uid (AID_DNS), i.e., the true src for the dns request
                    // is not known. override uid with INVALID_UID to force a preflow() call
                    // on Android P and above, the uid for private dns is also DNS.uid (AID_DNS)
                    // which we should not override and let it out as it is
                    // if (uid == AndroidUidConfig.DNS.uid) { uid = INVALID_UID }
                    // the above check is disabled in v055y
                    Backend.Base
                    // do not add the trackedCids for dns entries as there will not be any
                    // onSocketClosed event for dns entries
                } else {
                    // add to trackedCids, so that the connection can be removed from the list when the
                    // connection is closed (onSocketClosed), use: ui to show the active connections
                    val key = CidKey(cm.connId, uid)
                    activeCidsMutex.withLock {
                        activeCids.add(key)
                    }
                    if (persistentState.autoProxyEnabled) {
                        Backend.Auto
                    } else {
                        Backend.Exit
                    }
                }
            logd("flow: return $proxy for $uid, $srcIp, $srcPort, $realDestIp, $dstPort, $domains, $probableDomains")
            return persistAndConstructFlowResponse(ctx, cm, proxy, connId, uid)
        }

        if (trapVpnDns) {
            // see the comment above for the reasoning #trapVpnDns
            // if (uid == AndroidUidConfig.DNS.uid) { uid = INVALID_UID }
            // the above check is disabled in v055y
            // android R+, uid will be there for dns request as well
            logd("flow: dns-request, returning ${Backend.Base}, $uid, $connId, $domains")
            return persistAndConstructFlowResponse(ctx, null, Backend.Base, connId, uid)
        }
        processFirewallRequest(ctx, cm, d, anyRealIpBlocked, blocklists ?: "", isSplApp, rinr, isAlg)

        if (cm.isBlocked) {
            // return Ipn.Block, no need to check for other rules
            logd("flow: received rule: block, returning Ipn.Block, $connId, $uid, $domains")
            return persistAndConstructFlowResponse(ctx, cm, Backend.Block, connId, uid)
        }

        // add to trackedCids, so that the connection can be removed from the list when the
        // connection is closed (onSocketClosed), use: ui to show the active connections
        val key = CidKey(cm.connId, uid)
        activeCidsMutex.withLock {
            activeCids.add(key)
        }

        val rs = determineProxyDetails(ctx, cm, rinr)
        if (cm.uid == rethinkUid) {
            val blockedRule = FirewallRuleset.getFirewallRule(cm.blockedByRule)?.title
            if (cm.isBlocked && blockedRule != null) {
                handleRethinkBlockScenario(ctx, blockedRule)
            }
        }
        return rs
    }

    suspend fun handleInflow(
        ctx: FlowContext,
        protocol: Int,
        recvdUid: Int,
        src: String?,
        dst: String?
    ): Mark {
        val srcIpPort = parseIpAndPort(src)
        val dstIpPort = parseIpAndPort(dst)
        val srcIp = srcIpPort.first
        val srcPort = srcIpPort.second
        val dstIp = dstIpPort.first
        val dstPort = dstIpPort.second

        val rinr = persistentState.routeRethinkInRethink

        var uid = getUid(
            ctx,
            recvdUid,
            protocol,
            srcIp,
            srcPort,
            dstIp,
            dstPort
        )
        // fixme: see flow()
        uid = FirewallManager.appId(uid, ctx.isPrimaryUser)
        val userId = FirewallManager.userId(uid)

        logd("inflow: $uid($recvdUid), $srcIp, $srcPort, $dstIp, $dstPort")

        val connId = Utilities.getRandomString(8)

        val connType =
            if (isConnectionMetered(ctx, dstIp)) {
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

        processFirewallRequest(ctx, cm, "", false, "", rinr = rinr, isAlg = false)

        if (cm.isBlocked) {
            // return Ipn.Block, no need to check for other rules
            logd("inflow: received rule: block, returning Ipn.Block, $connId, $uid")
            return persistAndConstructFlowResponse(ctx, cm, Backend.Block, connId, uid)
        }

        // add to trackedCids, so that the connection can be removed from the list when the
        // connection is closed (onSocketClosed), use: ui to show the active connections
        val key = CidKey(cm.connId, uid)
        activeCidsMutex.withLock {
            activeCids.add(key)
        }

        logd("inflow: determine proxy and other dtls for $connId, $uid")

        // the proxy id (other than block) will be ignored by the go code, so use
        // Backend.Ingress as a placeholder
        return persistAndConstructFlowResponse(ctx, cm, Backend.Ingress, connId, uid)
    }

    // no need of go2kt here as it is called from go and performs db operations with no return value
    // requires go2kt if there any calls to go functions
    fun handleFlowing(m: Mark?) {
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

        cm.proxyDetails = mark.pidcsv
        cm.destIP = mark.ip
        val isNotLocalProxy = isNotLocalAndRpnProxy(mark.pidcsv)
        if (mark.pidcsv.isNotEmpty() && isNotLocalProxy) {
            cm.blockedByRule = FirewallRuleset.RULE12.id
        }

        if (cm.uid == rethinkUid) {
            netLogTracker.writeRethinkLog(cm)
        } else {
            netLogTracker.writeIpLog(cm)
        }

        logd("flow/postFlow, write conn in db: $mark")
    }

    suspend fun hasCid(ctx: FlowContext, connId: String, uid: Int): Boolean {
        // get app id from uid
        val uid0 = FirewallManager.appId(uid, ctx.isPrimaryUser)
        val key = CidKey(connId, uid0)
        activeCidsMutex.withLock {
            return activeCids.contains(key)
        }
    }

    fun getLastRxTrafficTime(): Long = lastRxTrafficTime

    /**
     * Resets all connection-tracking state owned by this singleton.
     *
     * TunFlowManager is a process-wide object, so its mutable state would
     * otherwise survive a VPN stop->start cycle: stale conn-ids would linger
     * in activeCids/activeClosableCids (showing phantom "active connections"
     * in the UI and skewing hasCid), and a stale lastRxTrafficTime would
     * immediately trip the maybeNetworkStall() 30s data-stall check on the
     * next session. Must be called from BraveVPNService teardown so every VPN
     * session begins from a clean slate.
     *
     * Safe to call from the main thread: the sets and cache are concurrent, and
     * by teardown time the Go adapter is already closed so no new flow callbacks
     * can repopulate them. invalidateAll() fires the removal listener with
     * RemovalCause.EXPLICIT, which handleExpiredConnMetaData ignores.
     */
    fun clear() {
        activeCids.clear()
        activeClosableCids.clear()
        trackedConnMetaData.invalidateAll()
        lastRxTrafficTime = elapsedRealtime()
        lastRethinkBlockReason = -1
        Logger.i(LOG_TAG_VPN, "$TAG cleared connection-tracking state")
    }

    // this method is called when the device is locked, so no need to check for device lock here
    fun closeTrackedConnsOnDeviceLock(ctx: FlowContext) {
        io(ctx.scope, "devLockCloseConns") {
            val cidsToClose: List<String> = activeClosableCidsMutex.withLock {
                if (activeClosableCids.isEmpty()) emptyList<String>()

                val snapshot = activeClosableCids.toList()
                activeClosableCids.clear()
                snapshot
            }
            if (cidsToClose.isNotEmpty()) {
                ctx.vpnAdapter?.closeConnections(cidsToClose, isUid = false, "dev-lock-close-conns")
            }
        }
    }

    private fun getConnTrackerMetaData(cid: String): ConnTrackerMetaData? {
        val cm = trackedConnMetaData.getIfPresent(cid)
        trackedConnMetaData.invalidate(cid)
        return cm
    }

    /**
     * Removes a conn-id from the "active connections" tracking sets once the
     * underlying socket is closed (handlePostflow / onSocketClosed). This must
     * run for every close path, including the multi-proxy branch where the
     * summary metadata was cached in trackedConnMetaData; otherwise the conn-id
     * leaks and shows up as a permanently "active" connection in the UI (and
     * keeps skewing hasCid / closeTrackedConnsOnDeviceLock).
     */
    private fun removeTrackedCid(scope: CoroutineScope, connId: String, uid: Int) {
        io(scope, "activeCids") {
            activeCidsMutex.withLock {
                activeCids.remove(CidKey(connId, uid))
            }
            activeClosableCidsMutex.withLock {
                activeClosableCids.remove(connId)
            }
        }
    }

    /**
     *  invoked when the cached connection metadata expires
     *  remove the connection metadata from trackedConnMetaData when the cached entry expires.
     */
    fun handleExpiredConnMetaData(notification: RemovalNotification<String, ConnTrackerMetaData>) {
        // handle only the expired connMetaData
        if (notification.cause != RemovalCause.EXPIRED) return

        val cm = notification.value
        if (cm == null) {
            Logger.e(LOG_TAG_VPN, "handleExpiredConnMetaData: received null connMetaData")
            return
        }

        cm.proxyDetails = ""
        cm.rpid = ""
        cm.downloadBytes = 0L
        cm.uploadBytes = 0L
        cm.duration = 0
        cm.synack = 0L
        cm.message = "no metadata"
        if (cm.uid == rethinkUid) {
            netLogTracker.writeRethinkLog(cm)
        } else {
            netLogTracker.writeIpLog(cm)
        }
        Logger.d(LOG_TAG_VPN, "expired connMetaData, close conns: $cm")
    }

    /**
     * Parses an endpoint string into a host (IP address or hostname) and port.
     *
     * Supports the following endpoint formats:
     * - Bracketed IPv6 with port: `[2001:db8::1]:443`
     * - Unbracketed IPv6 with port: `2001:db8::1:443`
     * - IPv4 with port: `10.0.0.1:53`
     * - Hostname with port: `example.com:443`
     * - Plain IPv4, IPv6, or hostname without a port.
     *
     * For unbracketed IPv6 addresses, the last segment is treated as the port only
     * if it is numeric and the preceding portion resembles an IPv6 address.
     *
     * Returns an empty host and `0` for null or blank input. If no valid port is
     * present, the returned port is `0`.
     */
    private fun parseIpAndPort(endpoint: String?): Pair<String, Int> {
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

    private suspend fun getUid(
        ctx: FlowContext,
        recdUid: Int,
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ): Int {
        val res = if (recdUid != INVALID_UID) {
            recdUid
        } else {
            if (isAtleastQ()) {
                ioCtx("getUidQ") { ctx.connTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort) }
            } else {
                recdUid // uid must have been retrieved from procfs by the caller
            }
        }
        Logger.vv(LOG_TAG_VPN, "getUid: recdUid: $recdUid, protocol: $protocol, srcIp: $srcIp, srcPort: $srcPort, dstIp: $dstIp, dstPort: $dstPort, result: $res")
        return res
    }

    private fun isDns(port: Int): Boolean {
        return KnownPorts.isDns(port)
    }

    private fun isPrivateDns(port: Int): Boolean {
        return KnownPorts.isDoT(port)
    }

    private fun isVpnDns(ip: String): Boolean {
        val isCustomLanIp = persistentState.customLanIpMode
        if (isCustomLanIp) {
            val customDnsIp4 = persistentState.customLanDnsIpv4
            val customDnsIp6 = persistentState.customLanDnsIpv6
            val ipv4Parts = customDnsIp4.split("/").firstOrNull() ?: ""
            val ipv6Parts = customDnsIp6.split("/").firstOrNull() ?: ""
            val ipv4 = HostName(ipv4Parts).toString()
            val ipv6 = HostName(ipv6Parts).toString()
            return when (persistentState.internetProtocolType) {
                InternetProtocol.IPv4.id -> {
                    ip == ipv4
                }

                InternetProtocol.IPv6.id -> {
                    false
                }

                InternetProtocol.IPv46.id -> {
                    ip == ipv4 || ip == ipv6
                }

                InternetProtocol.ALWAYSv46.id -> {
                    ip == ipv4 || ip == ipv6
                }

                else -> {
                    ip == ipv4
                }
            }
        } else {
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
    }

    private fun isSystemDnsIpFromExpectedUid(ctx: FlowContext, uid: Int, ip: String): Boolean {
        if (ip.isEmpty()) return false

        // in case of system-dns's ip, see if the source is from either ANDROID, DNS or Rethink.
        // if it is from any of those src then treat it is as special ip, for every other case we
        // don't have to treat as special
        val isProtectedUid = uid == AndroidUidConfig.ANDROID.uid || uid == AndroidUidConfig.DNS.uid || uid == rethinkUid
        return isProtectedUid && /* isSytemDnsIp */(ctx.prevDns.any { it.hostAddress == ip })
    }

    fun isConnectionMetered(ctx: FlowContext, dst: String): Boolean {
        val curnet = ctx.underlyingNetworks
        // assume active network until underlying networks are set by ConnectionMonitor
        // do not use persistentState.useMultipleNetworks
        val useActive = curnet == null || curnet.useActive
        val treatMobileAsMetered = persistentState.treatOnlyMobileNetworkAsMetered
        val res = if (!useActive || isLockdown(ctx)) {
            if (treatMobileAsMetered) {
                // TODO: should this check be a combination of cellular & metered?
                ctx.isIfaceCellular(dst)
            } else {
                ctx.isIfaceMetered(dst)
            }
        } else {
            if (treatMobileAsMetered) {
                ctx.isActiveIfaceCellular()
            } else {
                ctx.isActiveIfaceMetered()
            }
        }
        Logger.vv(LOG_TAG_VPN, "isConnectionMetered: dst: $dst, result: $res")
        return res
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
            System.currentTimeMillis(),   // timestamp
            false,                         // blocked?
            "",                        // rule
            proxyDetails,
            blocklists,
            protocol,
            query,
            connId,
            connType.value
        )
    }

    private suspend fun processFirewallRequest(
        ctx: FlowContext,
        metadata: ConnTrackerMetaData,
        domains: String?,
        anyRealIpBlocked: Boolean = false,
        blocklists: String = "",
        isSplApp: Boolean = false,
        rinr: Boolean,
        isAlg: Boolean
    ) {
        val rule = evaluateFirewall(ctx, metadata, domains, anyRealIpBlocked, isSplApp, rinr, isAlg)

        metadata.blockedByRule = rule.id
        metadata.blocklists = blocklists

        val blocked = FirewallRuleset.ground(rule)
        metadata.isBlocked = blocked

        addCidToTrackedCidsToCloseIfNeeded(metadata.connId, rule)

        logd("firewall-rule $rule on conn: ${metadata.connId}; $metadata")
        return
    }

    /** Checks if incoming connection is blocked by any user-set firewall rule */
    private suspend fun evaluateFirewall(
        ctx: FlowContext,
        connInfo: ConnTrackerMetaData,
        domains: String?,
        anyRealIpBlocked: Boolean = false,
        isSplApp: Boolean = false,
        rinr: Boolean = false,
        isAlg: Boolean = false,
        forUpstreamAnswer: Boolean = false
    ): FirewallRuleset {
        val params =
            TunFirewallManager.FirewallParameters(
                scope = ctx.scope,
                connInfo = connInfo,
                domains = domains,
                anyRealIpBlocked = anyRealIpBlocked,
                isSplApp = isSplApp,
                rinr = rinr,
                isAlg = isAlg,
                forUpstreamAnswer = forUpstreamAnswer,
                isDeviceLocked = deviceLocked(ctx),
                onDeviceLocked = { closeTrackedConnsOnDeviceLock(ctx) },
                underlyingNetworks = ctx.underlyingNetworks,
                isLockdown = isLockdown(ctx),
                isAppPaused = ctx.isAppPaused(),
                accessibilityServiceFunctional = ctx.accessibilityServiceFunctional(),
                onAccessibilityFailure = ctx.onAccessibilityFailure,
                keyguardManager = ctx.keyguardManager,
                connectivityManager = ctx.connectivityManager
            )
        return TunFirewallManager.firewall(params)
    }

    private suspend fun addCidToTrackedCidsToCloseIfNeeded(cid: String, rule: FirewallRuleset) {
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
        activeClosableCidsMutex.withLock {
            activeClosableCids.add(cid)
        }
    }

    private suspend fun persistAndConstructFlowResponse(
        ctx: FlowContext,
        cm: ConnTrackerMetaData?,
        proxyIds: String,
        connId: String,
        uid: Int,
        forUpstreamAnswer: Boolean = false
    ): Mark {

        if (forUpstreamAnswer) {
            val mark = Mark()
            mark.pidcsv = proxyIds
            mark.cid = connId
            mark.uid = uid.toString()
            logd("forUpstreamAnswer: returning mark for upstream answer: $mark for connId: $connId, uid: $uid, cm: $cm")
            return mark
        }

        // subscription/proxy health is reconciled on VPN start (see
        // BraveVPNService.makeOrUpdateVpnAdapter -> checkForPlusSubscription) and kept
        // healthy by GlobalProxyHandler; no need to re-check on every flow/inflow.

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

                if (uid == rethinkUid) {
                    netLogTracker.writeRethinkLog(cm)
                } else {
                    netLogTracker.writeIpLog(cm)
                }
            }
            logd("flow/inflow: connTracker: $cm, isRethink? ${cm.uid == rethinkUid}")
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
        io(ctx.scope, "handleSmartKeepAlive") {
            if (persistentState.smartPersistentKeepalive) {
                proxyIds.split(",").forEach {
                    if (it.startsWith(ID_WG_BASE) || it.startsWith(Backend.RpnWin)) {
                        Logger.vv(LOG_TAG_VPN, "rpnProxiesToPing: smart keepalive is enabled, curr used proxy: $it")
                        ctx.handleWgOrRpnProxiesToPing(it)
                    }
                }
            }
        }
        return mark
    }

    suspend fun determineProxyDetails(
        ctx: FlowContext,
        connTracker: ConnTrackerMetaData,
        rinr: Boolean,
        forUpstreamAnswer: Boolean = false
    ): Mark {
        // treat system dns ips as special (if the call was from flow()), as system dns will
        // not work when forwarded to a proxy so use only exit if the below condition is satisfied.
        // All the firewall checks can happen and the rules can be applied, but while selecting the
        // proxy the system dns ips should be treated specially based on the src uid.
        // see #isSystemDnsIpFromExpectedUid()
        // this condition doesn't applicable for upstreamAnswer()
        val treatAsSplIp = isSystemDnsIpFromExpectedUid(ctx, connTracker.uid, connTracker.destIP)
        if (treatAsSplIp && !forUpstreamAnswer) {
            logd("flow/upstream: sys-dns ip with uid: ${connTracker.uid}, return Exit")
            return persistAndConstructFlowResponse(ctx, connTracker, Backend.Exit, connTracker.connId, connTracker.uid, forUpstreamAnswer)
        }

        var baseOrAutoOrExit =
            if (connTracker.blockedByRule == FirewallRuleset.RULE9.id) {
                // special case: proxied dns traffic should not Backend.Exit as is. Only traffic
                // marked with Backend.Base will be handled (proxied) by vpnAdapter's dns-transport
                Backend.Base
            } else {
                if (persistentState.autoProxyEnabled) Backend.Auto else Backend.Exit
            }

        // override baseOrExit to Exit if rinr is true and the app is rethink because using
        // base will cause rethink's traffic to be rerouted to vpn again
        // treat it as special case
        if (connTracker.uid == rethinkUid) {
            baseOrAutoOrExit = if (persistentState.autoProxyEnabled) Backend.Auto else Backend.Exit
        }

        val connId = connTracker.connId
        val uid = connTracker.uid
        // add baseOrAutoOrExit in the end of the list if needed (not true for lockdown)
        val ssid = ctx.underlyingNetworks?.activeSsid ?: ctx.underlyingNetworks?.ipv4Net?.firstOrNull { it.ssid != null }?.ssid ?: ctx.underlyingNetworks?.ipv6Net?.firstOrNull { it.ssid != null }?.ssid ?: ""
        val usesMobileNw = ctx.isIfaceCellular(connTracker.destIP)

        val rpnIds = if (RpnProxyManager.isRpnActive()) {
            RpnProxyManager.getAllPossibleConfigIdsForApp(
                uid,
                connTracker.destIP,
                connTracker.destPort,
                connTracker.query.orEmpty(),
                usesMobileNw,
                ssid
            )
        } else {
            emptyList()
        }
        logd("flow/upstream: rpn-active? ${RpnProxyManager.isRpnActive()}, rpn-ids: $rpnIds for $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")

        if (connTracker.uid == rethinkUid && !rinr) {
            // baseOrAutoOrExit is expected to be Auto/Exit
            logd("flow/upstream: $baseOrAutoOrExit (must be exit or auto) for rethink, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
            return persistAndConstructFlowResponse(ctx, connTracker, baseOrAutoOrExit, connId, uid, forUpstreamAnswer)
        }

        val isGlobalLockdown = persistentState.wgGlobalLockdown
        // treat the forwarder apps as spl apps and use auto/exit based on the below setting
        // block those traffic when the global lockdown is enabled
        val splAppProxy = if (persistentState.autoProxyEnabled) Backend.Auto else if (isGlobalLockdown) Backend.Block else Backend.Exit
        if (FirewallManager.isAppExcludedFromProxy(uid)) {
            if (isGlobalLockdown) {
                logd("flow/upstream: app excluded from proxy, but lockdown is enabled, so block, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
                connTracker.isBlocked = true
                connTracker.blockedByRule = FirewallRuleset.RULE17.id
                return persistAndConstructFlowResponse(ctx, connTracker, Backend.Block, connId, uid, forUpstreamAnswer)
            }
            logd("flow/upstream: app is excluded from proxy, returning Ipn.Base, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
            if (connTracker.blockedByRule == FirewallRuleset.RULE0.id) {
                connTracker.blockedByRule = FirewallRuleset.RULE15.id
            }
            return persistAndConstructFlowResponse(ctx, connTracker, baseOrAutoOrExit, connId, uid, forUpstreamAnswer)
        }

        val defProxy = if (rpnIds.isNotEmpty() || isGlobalLockdown) "" else baseOrAutoOrExit
        val wgs = WireguardManager.getAllPossibleConfigIdsForApp(uid, connTracker.destIP, connTracker.destPort, connTracker.query.orEmpty(), usesMobileNw, ssid, defProxy)
        if (wgs.isNotEmpty() && wgs.first() != baseOrAutoOrExit) {
            // canRoute may fail for all configs.
            // if that happens:
            //   - traffic is sent to baseOrExit if available,
            //   - in lockdown mode, traffic is blocked if not active, apply rule#17
            // the above comment is not true now as the checks for canRoute is happening in
            // go, see if this is needed for the global proxy lockdown else remove the below check
            if (wgs.equals(Backend.Block)) {
                connTracker.isBlocked = true
                connTracker.blockedByRule = FirewallRuleset.RULE17.id
            }

            val ids = (rpnIds + wgs).joinToString(",")

            logd("flow/upstream: returning $ids for connId: $connId, uid: $uid, ${connTracker.query}, ${connTracker.destIP}")
            return persistAndConstructFlowResponse(ctx, connTracker, ids, connId, uid, forUpstreamAnswer)
        } else {
            Logger.vv(LOG_TAG_VPN, "flow/upstream: no wg proxy, fall-through $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
        }

        // carry out this check after wireguard, because wireguard has catchAll and lockdown.
        // if no proxy or dns proxy is enabled, return baseOrAutoOrExit
        Logger.vv(LOG_TAG_VPN, "flow/upstream proxy-enabled? ${appConfig.isProxyEnabled()}, dns-proxy active? ${appConfig.isDnsProxyActive()}, rpn empty? ${rpnIds.isEmpty()} for $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
        if (!appConfig.isProxyEnabled() && !appConfig.isDnsProxyActive() && rpnIds.isEmpty()) {
            if (isGlobalLockdown) {
                Logger.vv(LOG_TAG_VPN, "flow/upstream: global lockdown is active(no proxy/dnsproxy), block the traffic for $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
                connTracker.isBlocked = true
                connTracker.blockedByRule = FirewallRuleset.RULE17.id
                return persistAndConstructFlowResponse(ctx, connTracker, Backend.Block, connId, uid, forUpstreamAnswer)
            } else {
                logd("flow/upstream: no proxy/dnsproxy enabled, returning Ipn.Base, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
                return persistAndConstructFlowResponse(ctx, connTracker, baseOrAutoOrExit, connId, uid, forUpstreamAnswer)
            }
        }

        if (appConfig.isOrbotProxyEnabled()) {
            val endpoint = appConfig.getConnectedOrbotProxy()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            if (endpoint?.proxyAppName == packageName) {
                logd("flow/upstream: orbot $splAppProxy for $packageName, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
                if (splAppProxy == Backend.Block) {
                    connTracker.isBlocked = true
                    connTracker.blockedByRule = FirewallRuleset.RULE17.id
                }
                return persistAndConstructFlowResponse(ctx, connTracker, splAppProxy, connId, uid, forUpstreamAnswer)
            }

            val activeIds = ProxyManager.getProxyIdForApp(uid)
            Logger.vv(LOG_TAG_VPN, "flow/upstream: rcvd proxy details for $packageName $$connId, $uid, ${connTracker.query}, ${connTracker.destIP}, pids: $activeIds")
            if (!activeIds.contains(ProxyManager.ID_ORBOT_BASE)) {
                Logger.e(LOG_TAG_VPN, "flow/upstream: orbot proxy is enabled but app is not included $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
                // pass-through
            } else {
                val pids = if (rpnIds.isNotEmpty()) {
                    rpnIds.plus(ProxyManager.ID_ORBOT_BASE).joinToString(",")
                } else {
                    ProxyManager.ID_ORBOT_BASE
                }
                logd("flow/upstream: orbot proxy for $uid, $connId, ${connTracker.query}, ${connTracker.destIP}, returning $pids")
                return persistAndConstructFlowResponse(
                    ctx,
                    connTracker,
                    pids,
                    connId,
                    uid,
                    forUpstreamAnswer
                )
            }
        }

        // chose socks5 proxy over http proxy
        if (appConfig.isCustomSocks5Enabled()) {
            val endpoint = appConfig.getSocks5ProxyDetails()
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow/upstream: socks5 proxy enabled but endpoint is null $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
            }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow/upstream: socks5 proxy enabled but endpoint is null $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
            }
            logd("flow/upstream: socks5 proxy is enabled, src: $packageName, ${endpoint?.proxyAppName}, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
            // do not block the app if the app is set to forward the traffic via socks5 proxy
            if (endpoint?.proxyAppName == packageName) {
                if (splAppProxy == Backend.Block) {
                    connTracker.isBlocked = true
                    connTracker.blockedByRule = FirewallRuleset.RULE17.id
                }
                logd("flow/upstream: socks5 $splAppProxy for $packageName, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
                return persistAndConstructFlowResponse(ctx, connTracker, splAppProxy, connId, uid, forUpstreamAnswer)
            }

            val pids = if (rpnIds.isNotEmpty()) {
                rpnIds.plus(ProxyManager.ID_S5_BASE).joinToString(",")
            } else {
                ProxyManager.ID_S5_BASE
            }
            logd("flow/upstream: socks5 proxy for $connId, $uid, ${connTracker.query}, ${connTracker.destIP}, returning $pids")
            return persistAndConstructFlowResponse(
                ctx,
                connTracker,
                pids,
                connId,
                uid,
                forUpstreamAnswer
            )
        }

        if (appConfig.isCustomHttpProxyEnabled()) {
            val endpoint = appConfig.getHttpProxyDetails()
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow/upstream: http proxy enabled but endpoint is null for $connId, $uid, ${connTracker.query}, ${connTracker.destIP}")
            }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via http proxy
            if (endpoint?.proxyAppName == packageName) {
                if (splAppProxy == Backend.Block) {
                    connTracker.isBlocked = true
                    connTracker.blockedByRule = FirewallRuleset.RULE17.id
                }
                logd("flow/upstream: http $splAppProxy for $packageName, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}, global lockdown? $isGlobalLockdown, returning $splAppProxy")
                return persistAndConstructFlowResponse(ctx, connTracker, splAppProxy, connId, uid, forUpstreamAnswer)
            }
            val pids = if (rpnIds.isNotEmpty()) {
                rpnIds.plus(ProxyManager.ID_HTTP_BASE).joinToString(",")
            } else {
                ProxyManager.ID_HTTP_BASE
            }
            logd("flow/upstream: http proxy for $connId, $uid, ${connTracker.query}, ${connTracker.destIP}, returning $pids")
            return persistAndConstructFlowResponse(
                ctx,
                connTracker,
                pids,
                connId,
                uid,
                forUpstreamAnswer
            )
        }

        if (appConfig.isDnsProxyActive()) {
            val endpoint = appConfig.getSelectedDnsProxyDetails()
            val packageName = FirewallManager.getPackageNameByUid(uid)
            // do not block the app if the app is set to forward the traffic via dns proxy
            // unless global proxy is set
            if (endpoint?.proxyAppName == packageName) {
                if (splAppProxy == Backend.Block) {
                    connTracker.isBlocked = true
                    connTracker.blockedByRule = FirewallRuleset.RULE17.id
                }
                logd("flow/upstream: dns proxy enabled for $packageName, return $splAppProxy, $connId, $uid, ${connTracker.query}, ${connTracker.destIP}, global lockdown? $isGlobalLockdown, returning $splAppProxy")
                return persistAndConstructFlowResponse(ctx, connTracker, splAppProxy, connId, uid, forUpstreamAnswer)
            }
        }

        if (rpnIds.isNotEmpty()) {
            val be = if (isGlobalLockdown) {
                emptyList()
            } else {
                listOf(baseOrAutoOrExit)
            }
            val ids = (rpnIds + be).joinToString(",")
            logd("flow/upstream: returning $ids for connId: $connId, uid: $uid, ${connTracker.query}, ${connTracker.destIP}, global lockdown? $isGlobalLockdown, returning $ids")
            return persistAndConstructFlowResponse(ctx, connTracker, ids, connId, uid, forUpstreamAnswer)
        }
        if (isGlobalLockdown) {
            Logger.vv(LOG_TAG_VPN, "flow/upstream: global lockdown is active, block the traffic for $connId, $uid, ${connTracker.query}, ${connTracker.destIP}, returning ${Backend.Block}")
            connTracker.isBlocked = true
            connTracker.blockedByRule = FirewallRuleset.RULE17.id
            return persistAndConstructFlowResponse(ctx, connTracker, Backend.Block, connId, uid, forUpstreamAnswer)
        }
        logd("flow/upstream: no proxies, $baseOrAutoOrExit, $connId, $uid, , ${connTracker.query}, ${connTracker.destIP}, returning $baseOrAutoOrExit")
        return persistAndConstructFlowResponse(ctx, connTracker, baseOrAutoOrExit, connId, uid, forUpstreamAnswer)
    }

    suspend fun isSpecialApp(uid: Int): Boolean {
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
                logd("flow/inflow: orbot enabled for $packageName, handling as spl app")
                return true
            }
        }

        if (appConfig.isCustomSocks5Enabled()) {
            val endpoint = appConfig.getSocks5ProxyDetails()
            if (endpoint == null) {
                Logger.e(LOG_TAG_VPN, "flow: socks5 proxy enabled but endpoint is null")
                return false
            }
            val packageName = FirewallManager.getPackageNameByUid(uid)
            logd("flow/inflow: socks5 proxy is enabled, $packageName, ${endpoint?.proxyAppName}")
            // do not block the app if the app is set to forward the traffic via socks5 proxy
            if (endpoint?.proxyAppName == packageName) {
                logd("flow/inflow: socks5 enabled for $packageName, handling as spl app")
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
                logd("flow/inflow: dns proxy enabled for $packageName, handling as spl app")
                return true
            }
        }

        return false
    }

    // user_present/screen_off.
    // checking the KeyguardManager#isKeyguardLocked() is suffice, no need for receiver.
    // if isKeyguardLocked() is true, the connections will be blocked.
    private fun deviceLocked(ctx: FlowContext): Boolean {
        if (!persistentState.getBlockWhenDeviceLocked()) return false

        if (ctx.keyguardManager == null) {
            // keyguardManager is provided by BVS via FlowContext
        }
        return (ctx.keyguardManager?.isKeyguardLocked == true)
    }

    private fun isLockdown(ctx: FlowContext): Boolean {
        return ctx.isLockdownEnabled()
    }
    private fun handleRethinkBlockScenario(ctx: FlowContext, rule: Int) {
        if (!persistentState.showRethinkBlockNotification) return

        if (lastRethinkBlockReason == rule) return

        lastRethinkBlockReason = rule
        val reason = ctx.context.getString(R.string.rethink_block_notification_desc, ctx.context.getString(rule))
        ui(ctx.scope, "rBlockNotif") {
            val intent = Intent(ctx.context, NotificationActionReceiver::class.java).apply {
                putExtra(Constants.NOTIFICATION_ACTION, Constants.NOTIF_ACTION_RETHINK_BLOCK_DISMISS)
            }

            val pendingIntent =
                Utilities.getActivityPendingIntent(
                    ctx.context,
                    Intent(ctx.context, com.celzero.bravedns.ui.activity.AppLockActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    mutable = false
                )
            val actionIntent = Utilities.getBroadcastPendingIntent(
                ctx.context,
                Constants.NOTIF_ID_RETHINK_BLOCK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )

            val builder: NotificationCompat.Builder = if (isAtleastO()) {
                val name: CharSequence = ctx.context.getString(R.string.notif_channel_firewall_alerts)
                val description = ctx.context.resources.getString(R.string.notif_channel_desc_firewall_alerts)
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, name, importance)
                channel.description = description
                ctx.notificationManager.createNotificationChannel(channel)
                NotificationCompat.Builder(ctx.context, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
            } else {
                NotificationCompat.Builder(ctx.context, NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
            }

            builder.setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(ctx.context.getString(R.string.rethink_block_notification_title))
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setColor(ContextCompat.getColor(ctx.context, getAccentColor(persistentState.theme)))
                .addAction(0, ctx.context.getString(R.string.rethink_block_notification_action_dismiss), actionIntent)

            ctx.notificationManager.notify(NOTIF_CHANNEL_ID_FIREWALL_ALERTS, Constants.NOTIF_ID_RETHINK_BLOCK, builder.build())
        }
    }

    private enum class LanIp(private val value: Int) {
        GATEWAY(1),
        ROUTER(2),
        DNS(3);

        fun make(template: String): String {
            val format = String.format(Locale.ROOT, template, value)
            return HostName(format).toString()
        }
    }

    private fun io(scope: CoroutineScope, s: String, f: suspend () -> Unit) =
        scope.launch(CoroutineName(s) + Dispatchers.IO) { f() }

    private fun ui(scope: CoroutineScope, s: String, f: suspend () -> Unit) =
        scope.launch(CoroutineName(s) + Dispatchers.Main) { f() }

    private suspend fun <T> ioCtx(s: String, f: suspend () -> T): T =
        withContext(CoroutineName(s) + Dispatchers.IO) { f() }
}
