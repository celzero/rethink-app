/*
 * Copyright 2024 RethinkDNS and its authors
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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock.elapsedRealtime
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.util.Constants.Companion.ACTIVE_NETWORK_CHECK_THRESHOLD_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.IPUtil
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities.isMissingOrInvalidUid
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.pow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object TunFirewallManager : KoinComponent {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val rdb by inject<RefreshDatabase>()

    private var rethinkUid: Int = Process.myUid()
    private val settingUpOrbot: AtomicBoolean = AtomicBoolean(false)

    data class FirewallParameters(
        val scope: CoroutineScope,
        val connInfo: ConnTrackerMetaData,
        val domains: String?,
        val anyRealIpBlocked: Boolean = false,
        val isSplApp: Boolean = false,
        val rinr: Boolean = false,
        val isAlg: Boolean = false,
        val forUpstreamAnswer: Boolean = false,
        val isDeviceLocked: Boolean = false,
        val onDeviceLocked: (() -> Unit)? = null,
        val underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null,
        val isLockdown: Boolean = false,
        val isAppPaused: Boolean = false,
        val accessibilityServiceFunctional: Boolean = false,
        val onAccessibilityFailure: (() -> Unit)? = null,
        val keyguardManager: KeyguardManager? = null,
        val connectivityManager: ConnectivityManager
    )

    fun setSettingUpOrbot(value: Boolean) {
        settingUpOrbot.set(value)
    }

    fun setRethinkUidForTest(uid: Int) {
        rethinkUid = uid
    }

    private fun logd(msg: String) {
        Logger.d(LOG_TAG_VPN, "TunFirewallManager; $msg")
    }

    internal suspend fun firewall(params: FirewallParameters): FirewallRuleset {
        val connId = params.connInfo.connId
        val skipUnknownAppRule = params.forUpstreamAnswer && !persistentState.splitDns
        val res = try {
            if (params.connInfo.uid == rethinkUid && !params.rinr) {
                logd("firewall($connId): rethink uid, $rethinkUid, not processing firewall rules")
                return FirewallRuleset.RULE0
            }

            logd("firewall($connId): ${params.connInfo}")
            val uid = params.connInfo.uid
            val appStatus = FirewallManager.appStatus(uid)
            val connectionStatus = FirewallManager.connectionStatus(uid)
            val isTempAllowed = FirewallManager.isTempAllowed(uid)

            if (allowOrbot(uid)) {
                return FirewallRuleset.RULE9B
            }

            if (unknownAppBlocked(uid) && !skipUnknownAppRule) {
                logd("firewall($connId): unknown app blocked, $uid")
                return FirewallRuleset.RULE5
            }

            // if the app is new (ie not tracked by FirewallManager), refresh the db
            if (!FirewallManager.hasUid(uid) && uid != INVALID_UID) {
                io(params.scope, "addNewApp") { rdb.addNewApp(uid) }
                if (newAppBlocked(uid)) {
                    logd("firewall($connId): new app blocked, $uid")
                    return FirewallRuleset.RULE1B
                }
            }

            if (isTempAllowed) {
                logd("firewall($connId): temp allowed, $uid")
                return FirewallRuleset.RULE19
            }

            // check for app rules (unmetered, metered connections)
            val appRuleset =
                appBlocked(
                    params.connInfo,
                    connectionStatus,
                    params.underlyingNetworks,
                    params.isLockdown,
                    params.connectivityManager
                )
            if (appRuleset != null) {
                logd("firewall($connId): app blocked, $uid")
                return appRuleset
            }

            if (params.isLockdown && params.isAppPaused) {
                logd("firewall($connId): lockdown, app paused, $uid")
                return FirewallRuleset.RULE16
            }

            // getDomainRule() will check rules for all domains (comma separated)
            // and returns as a pair of domain rule and matched domain
            val dms = if (params.isAlg) params.domains else params.domains?.split(",")?.firstOrNull()
            val dp = DomainRulesManager.getAggregatedDomainRule(dms, uid)
            when (dp.first) {
                DomainRulesManager.Status.BLOCK -> {
                    logd("firewall($connId): domain blocked, $uid")
                    // assign the query only if we have a rule as blocked
                    if (!dp.second.isNullOrEmpty()) {
                        params.connInfo.query = dp.second
                    }
                    return FirewallRuleset.RULE2E
                }

                DomainRulesManager.Status.TRUST -> {
                    logd("firewall($connId): domain trusted, $uid")
                    // assign the query only if we have a rule as trust
                    if (!dp.second.isNullOrEmpty()) {
                        params.connInfo.query = dp.second
                    }
                    return FirewallRuleset.RULE2F
                }

                DomainRulesManager.Status.NONE -> {
                    // fall-through
                }
            }

            // IP rules
            var hasTrustedIp = false
            var hasBlockedIp = false
            val ips = if (params.isAlg) params.connInfo.destIP else params.connInfo.destIP.split(",").first()
            ips.split(",").forEach { ip ->
                when (uidIpStatus(uid, ip, params.connInfo.destPort)) {
                    IpRulesManager.IpRuleStatus.BLOCK -> {
                        logd("firewall($connId): ip blocked ($ip), $uid")
                        hasBlockedIp = true
                    }

                    IpRulesManager.IpRuleStatus.TRUST -> {
                        logd("firewall($connId): ip trusted ($ip), $uid")
                        hasTrustedIp = true
                    }

                    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                        // no-op; pass-through
                        // By-pass universal should be validated after app-firewall rules
                    }

                    IpRulesManager.IpRuleStatus.NONE -> {
                        // no-op; pass-through
                    }
                }
            }

            when {
                hasTrustedIp -> return FirewallRuleset.RULE2B
                hasBlockedIp -> return FirewallRuleset.RULE2
            }

            // by-pass dns firewall, go-through app specific ip and domain rules before applying
            if (appStatus.bypassDnsFirewall()) {
                logd("firewall($connId): bypass dns firewall, $uid")
                return FirewallRuleset.RULE1H
            }

            // isolate mode
            if (appStatus.isolate()) {
                logd("firewall($connId): isolate mode, $uid")
                return FirewallRuleset.RULE1G
            }

            // returns a pair of domain rule and matched domain
            val globalDomainPair = DomainRulesManager.getAggregatedDomainRule(dms, UID_EVERYBODY)
            val globalDomainRule = globalDomainPair.first
            if (!globalDomainPair.second.isNullOrEmpty()) {
                params.connInfo.query = globalDomainPair.second
            }

            // should firewall rules by-pass universal firewall rules (previously whitelist)
            if (appStatus.bypassUniversal()) {
                // bypass universal should block the domains that are blocked by dns (local/remote)
                // unless the domain is trusted by the user
                if (params.anyRealIpBlocked && globalDomainRule != DomainRulesManager.Status.TRUST) {
                    logd("firewall($connId): bypass universal, dns blocked, $uid, ${params.connInfo.query}")
                    return FirewallRuleset.RULE2G
                }

                if (dnsProxied(params.connInfo.destPort) && !params.forUpstreamAnswer) {
                    logd("firewall($connId): bypass universal, dns proxied, $uid")
                    return FirewallRuleset.RULE9
                } else {
                    logd("firewall($connId): bypass universal, $uid")
                    return FirewallRuleset.RULE8
                }
            }

            // check for global domain allow/block domains
            when (globalDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    logd("firewall($connId): global domain trusted, $uid, ${params.connInfo.query}")
                    return FirewallRuleset.RULE2I
                }

                DomainRulesManager.Status.BLOCK -> {
                    logd("firewall($connId): global domain blocked, $uid, ${params.connInfo.query}")
                    return FirewallRuleset.RULE2H
                }

                else -> {
                    // fall through
                }
            }

            hasTrustedIp = false
            hasBlockedIp = false
            ips.split(",").forEach { ip ->
                // should ip rules by-pass or block universal firewall rules
                when (globalIpRule(ip, params.connInfo.destPort)) {
                    IpRulesManager.IpRuleStatus.BLOCK -> {
                        logd("firewall($connId): global ip blocked, $uid, $ip")
                        hasBlockedIp = true
                    }

                    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                        logd("firewall($connId): global ip bypass universal, $uid, $ip")
                        hasTrustedIp = true
                    }

                    IpRulesManager.IpRuleStatus.TRUST -> {
                        // no-op; pass-through
                    }

                    IpRulesManager.IpRuleStatus.NONE -> {
                        // no-op; pass-through
                    }
                }
            }

            when {
                hasTrustedIp -> return FirewallRuleset.RULE2C
                hasBlockedIp -> return FirewallRuleset.RULE2D
            }

            // if any of the real ip is blocked then allow only if it is trusted,
            // otherwise no need to check further
            if (params.anyRealIpBlocked) {
                logd("firewall($connId): dns blocked, $uid, ${params.connInfo.query}")
                return FirewallRuleset.RULE2G
            } else {
                // no-op; pass-through
            }

            // apps which are used to forward dns proxy, socks5 or https proxy are handled as spl
            // no need to handle universal firewall rules for these apps
            if (params.isSplApp) {
                logd("firewall($connId): special app, $uid, ${params.connInfo.query}")
                // placeholder rule (RULE0) for special app rules
                return FirewallRuleset.RULE0
            }

            val isMetered =
                isConnectionMetered(
                    params.connInfo.destIP,
                    params.underlyingNetworks,
                    params.isLockdown,
                    params.connectivityManager
                )
            // block all metered connections (Universal firewall setting)
            if (persistentState.getBlockMeteredConnections() && isMetered) {
                logd("firewall($connId): metered blocked, $uid")
                return FirewallRuleset.RULE1F
            }

            // block apps when universal lockdown is enabled
            if (universalLockdown()) {
                logd("firewall($connId): universal lockdown, $uid")
                return FirewallRuleset.RULE11
            }

            // no need to check for http for dns queries
            if (!params.forUpstreamAnswer && httpBlocked(params.connInfo.destPort)) {
                logd("firewall($connId): http blocked, $uid")
                return FirewallRuleset.RULE10
            }

            if (params.isDeviceLocked) {
                params.onDeviceLocked?.invoke()
                logd("firewall($connId): device locked, $uid")
                return FirewallRuleset.RULE3
            }

            // no need to check for udp block for dns queries
            if (!params.forUpstreamAnswer && udpBlocked(uid, params.connInfo.protocol, params.connInfo.destPort)) {
                logd("firewall($connId): udp blocked, $uid")
                return FirewallRuleset.RULE6
            }

            if (blockBackgroundData(uid, params.accessibilityServiceFunctional, params.onAccessibilityFailure, params.keyguardManager)) {
                logd("firewall($connId): background data blocked, $uid")
                return FirewallRuleset.RULE4
            }

            // if all packets on port 53 needs to be trapped, no need to check for dns queries
            if (!params.forUpstreamAnswer && dnsProxied(params.connInfo.destPort)) {
                logd("firewall($connId): dns proxied, $uid")
                return FirewallRuleset.RULE9
            }

            // if connInfo.query is empty, then it is not resolved by user set dns
            // not true in case of dns queries, skip this check
            if (!params.forUpstreamAnswer && dnsBypassed(params.connInfo.query)) {
                logd("firewall($connId): dns bypassed, $uid")
                return FirewallRuleset.RULE7
            }
            logd("no firewall rule($connId), uid=${params.connInfo.uid}")
            FirewallRuleset.RULE0
        } catch (iex: Exception) {
            Logger.crash(LOG_TAG_VPN, "unexpected err in firewall()($connId), block anyway", iex)
            FirewallRuleset.RULE1C
        }
        Logger.vv(LOG_TAG_VPN, "firewall: connId: $connId, rule: $res")
        return res
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

    private fun isDns(port: Int): Boolean {
        return port == KnownPorts.DNS_PORT
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
            logd("firewall: ip-rule for $destIp:$destPort, ${statusIpPort.name}")
            return statusIpPort // trusted or blocked or bypassed-universal
        }
        // is ipv4 addr as ipv6 blocked / trusted?
        if (is4in6FilterRequired()) {
            val addr = try {
                IPAddressString(destIp).address
            } catch (_: Exception) {
                return IpRulesManager.IpRuleStatus.NONE
            }

            val ip4in6 = IPUtil.ip4in6(addr) ?: return IpRulesManager.IpRuleStatus.NONE
            val ip4str = ip4in6.toNormalizedString()
            val statusIpPort4in6 = IpRulesManager.hasRule(uid, ip4str, destPort)
            if (statusIpPort4in6 != IpRulesManager.IpRuleStatus.NONE) {
                logd("firewall: ip-rule for $destIp:$destPort, 4in6  ${statusIpPort4in6.name}")
                return statusIpPort4in6 // trusted or blocked or bypassed-universal
            }
        }
        logd("firewall: ip-rule for $destIp:$destPort, ${statusIpPort.name}")
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
        durationSec: Long = 10,
        test: suspend () -> Boolean
    ): Boolean {
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(durationSec)
        var attempt = 0
        while (remainingWaitMs > 0) {
            if (test()) return true

            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt += 1
        }

        return false
    }

    private fun exponentialBackoff(remainingWaitMs: Long, attempt: Int): Long {
        var backoffMs = TimeUnit.SECONDS.toMillis(2.0.pow(attempt.toDouble()).toLong())
        val maxBackoffMs = TimeUnit.SECONDS.toMillis(10)
        backoffMs = Math.min(backoffMs, maxBackoffMs)
        return remainingWaitMs - backoffMs
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

    private fun appBlocked(
        connInfo: ConnTrackerMetaData,
        connectionStatus: FirewallManager.ConnectionStatus,
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        isLockdown: Boolean,
        connectivityManager: ConnectivityManager
    ): FirewallRuleset? {
        if (connectionStatus.blocked()) {
            return FirewallRuleset.RULE1
        }

        val isMetered =
            isConnectionMetered(connInfo.destIP, underlyingNetworks, isLockdown, connectivityManager)
        if (connectionStatus.wifi() && !isMetered) {
            return FirewallRuleset.RULE1D
        }

        if (connectionStatus.mobileData() && isMetered) {
            return FirewallRuleset.RULE1E
        }

        return null
    }

    private fun isConnectionMetered(
        dst: String,
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        isLockdown: Boolean,
        connectivityManager: ConnectivityManager
    ): Boolean {
        val curnet = underlyingNetworks
        // assume active network until underlying networks are set by ConnectionMonitor
        // do not use persistentState.useMultipleNetworks
        val useActive = curnet == null || curnet.useActive
        val treatMobileAsMetered = persistentState.treatOnlyMobileNetworkAsMetered
        return if (!useActive || isLockdown) {
            if (treatMobileAsMetered) {
                isIfaceCellular(dst, underlyingNetworks, connectivityManager)
            } else {
                isIfaceMetered(dst, underlyingNetworks, connectivityManager)
            }
        } else {
            if (treatMobileAsMetered) {
                curnet?.isActiveNetworkCellular == true
            } else {
                curnet?.isActiveNetworkMetered == true
            }
        }
    }

    private fun isIfaceCellular(
        dst: String,
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        connectivityManager: ConnectivityManager
    ): Boolean {
        if (dst.isEmpty()) {
            val isActiveCellular = isActiveIfaceCellular(underlyingNetworks, connectivityManager)
            Logger.vv(LOG_TAG_VPN, "empty destination ip, active cellular? $isActiveCellular")
            return isActiveCellular
        }
        val dest = IPAddressString(dst)
        if (dest.isEmpty) {
            Logger.e(LOG_TAG_VPN, "invalid destination IP: $dst")
            return isActiveIfaceCellular(underlyingNetworks, connectivityManager)
        }

        val curnet = underlyingNetworks
        val cap =
            if (dest.isZero || dest.isIPv6) { // wildcard addrs(::80, ::443, etc.) are bound to ipv6
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv6Net?.isEmpty() == true) {
                    return isActiveIfaceCellular(underlyingNetworks, connectivityManager)
                }
                curnet?.ipv6Net?.firstOrNull()?.capabilities
            } else {
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv4Net?.isEmpty() == true) {
                    return isActiveIfaceCellular(underlyingNetworks, connectivityManager)
                }
                curnet?.ipv4Net?.firstOrNull()?.capabilities
            }
        // if there are no network to be bound given a destination IP, fallback to active network
        if (cap == null) {
            Logger.e(LOG_TAG_VPN, "no network to be bound for $dst, use active network")
            return isActiveIfaceCellular(underlyingNetworks, connectivityManager)
        }
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun isIfaceMetered(
        dst: String,
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        connectivityManager: ConnectivityManager
    ): Boolean {
        val dest = IPAddressString(dst)
        if (dest.isEmpty) {
            Logger.e(LOG_TAG_VPN, "invalid destination IP: $dst")
            return isActiveIfaceMetered(underlyingNetworks, connectivityManager)
        }

        // TODO: check for all networks instead of just the first one
        val curnet = underlyingNetworks
        val cap =
            if (dest.isZero || dest.isIPv6) { // wildcard addrs(::80, ::443, etc.) are bound to ipv6
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv6Net?.isEmpty() == true) {
                    return isActiveIfaceMetered(underlyingNetworks, connectivityManager)
                }
                curnet?.ipv6Net?.firstOrNull()?.capabilities
            } else {
                // if there are no network to be bound, fallback to active network
                if (curnet?.ipv4Net?.isEmpty() == true) {
                    return isActiveIfaceMetered(underlyingNetworks, connectivityManager)
                }
                curnet?.ipv4Net?.firstOrNull()?.capabilities
            }

        // if there are no network to be bound given a destination IP, fallback to active network
        if (cap == null) {
            Logger.e(LOG_TAG_VPN, "no network to be bound for $dst, use active network")
            return isActiveIfaceMetered(underlyingNetworks, connectivityManager)
        }
        return !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isActiveIfaceMetered(
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        connectivityManager: ConnectivityManager
    ): Boolean {
        val curnet = underlyingNetworks ?: return false // assume unmetered
        val now = elapsedRealtime()
        val ts = curnet.lastUpdated
        if (abs(now - ts) > ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            curnet.lastUpdated = now
            curnet.isActiveNetworkMetered = connectivityManager.isActiveNetworkMetered
        }
        return curnet.isActiveNetworkMetered
    }

    private fun isActiveIfaceCellular(
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        connectivityManager: ConnectivityManager
    ): Boolean {
        val curnet = underlyingNetworks ?: return false // assume unmetered
        val now = elapsedRealtime()
        val ts = curnet.lastUpdated
        if (abs(now - ts) > ACTIVE_NETWORK_CHECK_THRESHOLD_MS) {
            curnet.lastUpdated = now
            val activeNetwork = connectivityManager.activeNetwork
            val cap = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            val isCellular = cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            curnet.isActiveNetworkCellular = isCellular
        }
        return curnet.isActiveNetworkCellular
    }

    private fun blockBackgroundData(
        uid: Int,
        accessibilityServiceFunctional: Boolean,
        onAccessibilityFailure: (() -> Unit)?,
        keyguardManager: KeyguardManager?
    ): Boolean {
        if (!persistentState.getBlockAppWhenBackground()) return false

        if (!accessibilityServiceFunctional) {
            Logger.w(LOG_TAG_VPN, "accessibility service not functional, disable bg-block")
            onAccessibilityFailure?.invoke()
            return false
        }

        if (FirewallManager.isAppForeground(uid, keyguardManager)) return false

        return true
    }

    private fun io(scope: CoroutineScope, s: String, f: suspend () -> Unit) =
        scope.launch(CoroutineName(s) + Dispatchers.IO) { f() }
}
