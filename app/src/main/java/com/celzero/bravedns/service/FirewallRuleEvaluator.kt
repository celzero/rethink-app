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

import Logger
import Logger.LOG_TAG_VPN
import android.app.KeyguardManager
import android.content.Context
import android.net.NetworkCapabilities
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.IPUtil
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isMissingOrInvalidUid
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Evaluates firewall rules for network connections.
 * Extracted from BraveVPNService for better separation of concerns and testability.
 */
class FirewallRuleEvaluator(
    private val context: Context,
    private val persistentState: PersistentState,
    private val appConfig: AppConfig,
    private val refreshDatabase: RefreshDatabase,
    private val networkInfoProvider: NetworkInfoProvider
) {
    companion object {
        private const val TAG = "FirewallEvaluator"
        private const val UID_EVERYBODY = Constants.UID_EVERYBODY
        private const val INVALID_UID = Constants.INVALID_UID
        
        // Backoff configuration
        private const val BASE_WAIT_MS = 50L
    }

    private val rand = Random
    
    private var keyguardManager: KeyguardManager? = null

    /**
     * Checks if incoming connection is blocked by any user-set firewall rule.
     * Returns the appropriate FirewallRuleset based on the evaluation.
     */
    suspend fun evaluateFirewallRules(
        connInfo: ConnTrackerMetaData,
        domains: String?,
        anyRealIpBlocked: Boolean = false,
        isSplApp: Boolean,
        rinr: Boolean,
        rethinkUid: Int
    ): FirewallRuleset {
        val connId = connInfo.connId
        
        try {
            // Skip firewall for Rethink app unless route-in-route is enabled
            if (connInfo.uid == rethinkUid && !rinr) {
                logd("firewall($connId): rethink uid, $rethinkUid, not processing firewall rules")
                return FirewallRuleset.RULE0
            }

            logd("firewall($connId): $connInfo")
            val uid = connInfo.uid
            val appStatus = FirewallManager.appStatus(uid)
            val connectionStatus = FirewallManager.connectionStatus(uid)
            val isTempAllowed = FirewallManager.isTempAllowed(uid)

            // Evaluate rules in priority order
            return evaluateRulesInOrder(
                connInfo, domains, anyRealIpBlocked, isSplApp, rinr,
                uid, appStatus, connectionStatus, isTempAllowed
            )
            
        } catch (ex: Exception) {
            Logger.crash(LOG_TAG_VPN, "unexpected err in firewall()($connId), block anyway", ex)
            return FirewallRuleset.RULE1C
        }
    }

    private suspend fun evaluateRulesInOrder(
        connInfo: ConnTrackerMetaData,
        domains: String?,
        anyRealIpBlocked: Boolean,
        isSplApp: Boolean,
        rinr: Boolean,
        uid: Int,
        appStatus: FirewallManager.FirewallStatus,
        connectionStatus: FirewallManager.ConnectionStatus,
        isTempAllowed: Boolean
    ): FirewallRuleset {
        val connId = connInfo.connId

        // Rule 1: Allow Orbot during setup
        if (allowOrbot(uid)) {
            return FirewallRuleset.RULE9B
        }

        // Rule 2: Block unknown apps if configured
        if (unknownAppBlocked(uid)) {
            logd("firewall($connId): unknown app blocked, $uid")
            return FirewallRuleset.RULE5
        }

        // Rule 3: Handle new/unknown apps
        if (appStatus.isUntracked() && uid != INVALID_UID) {
            withContext(Dispatchers.IO) { refreshDatabase.addNewApp(uid) }
            if (newAppBlocked(uid)) {
                logd("firewall($connId): new app blocked, $uid")
                return FirewallRuleset.RULE1B
            }
        }

        // Rule 4: Temporarily allowed apps
        if (isTempAllowed) {
            logd("firewall($connId): temp allowed, $uid")
            return FirewallRuleset.RULE19
        }

        // Rule 5: App-level rules (unmetered, metered connections)
        appBlocked(connInfo, connectionStatus)?.let { return it }

        // Rule 6: Lockdown + paused
        if (isLockdown() && isAppPaused()) {
            logd("firewall($connId): lockdown, app paused, $uid")
            return FirewallRuleset.RULE16
        }

        // Rule 7: Domain rules (app-specific)
        val domainPair = getDomainRule(domains, uid)
        if (!domainPair.second.isNullOrEmpty()) {
            connInfo.query = domainPair.second
        }
        when (domainPair.first) {
            DomainRulesManager.Status.BLOCK -> {
                logd("firewall($connId): domain blocked, $uid")
                return FirewallRuleset.RULE2E
            }
            DomainRulesManager.Status.TRUST -> {
                logd("firewall($connId): domain trusted, $uid")
                return FirewallRuleset.RULE2F
            }
            DomainRulesManager.Status.NONE -> { /* fall-through */ }
        }

        // Rule 8: IP rules (app-specific)
        when (uidIpStatus(uid, connInfo.destIP, connInfo.destPort)) {
            IpRulesManager.IpRuleStatus.BLOCK -> {
                logd("firewall($connId): ip blocked, $uid")
                return FirewallRuleset.RULE2
            }
            IpRulesManager.IpRuleStatus.TRUST -> {
                logd("firewall($connId): ip trusted, $uid")
                return FirewallRuleset.RULE2B
            }
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> { /* no-op, pass-through */ }
            IpRulesManager.IpRuleStatus.NONE -> { /* no-op, pass-through */ }
        }

        // Rule 9: Bypass DNS Firewall mode
        if (appStatus.bypassDnsFirewall()) {
            logd("firewall($connId): bypass dns firewall, $uid")
            return FirewallRuleset.RULE1H
        }

        // Rule 10: Isolate mode
        if (appStatus.isolate()) {
            logd("firewall($connId): isolate mode, $uid")
            return FirewallRuleset.RULE1G
        }

        // Rule 11: Global domain rules
        val globalDomainPair = getDomainRule(domains, UID_EVERYBODY)
        val globalDomainRule = globalDomainPair.first
        if (!globalDomainPair.second.isNullOrEmpty()) {
            connInfo.query = globalDomainPair.second
        }

        // Rule 12: Bypass Universal mode
        if (appStatus.bypassUniversal()) {
            if (anyRealIpBlocked && globalDomainRule != DomainRulesManager.Status.TRUST) {
                logd("firewall($connId): bypass universal, dns blocked, $uid, ${connInfo.query}")
                return FirewallRuleset.RULE2G
            }
            return if (dnsProxied(connInfo.destPort)) {
                logd("firewall($connId): bypass universal, dns proxied, $uid")
                FirewallRuleset.RULE9
            } else {
                logd("firewall($connId): bypass universal, $uid")
                FirewallRuleset.RULE8
            }
        }

        // Rule 13: Global domain rules
        when (globalDomainRule) {
            DomainRulesManager.Status.TRUST -> {
                logd("firewall($connId): global domain trusted, $uid, ${connInfo.query}")
                return FirewallRuleset.RULE2I
            }
            DomainRulesManager.Status.BLOCK -> {
                logd("firewall($connId): global domain blocked, $uid, ${connInfo.query}")
                return FirewallRuleset.RULE2H
            }
            DomainRulesManager.Status.NONE -> { /* fall-through */ }
        }

        // Rule 14: Global IP rules
        when (globalIpRule(connInfo.destIP, connInfo.destPort)) {
            IpRulesManager.IpRuleStatus.BLOCK -> {
                logd("firewall($connId): global ip blocked, $uid, ${connInfo.destIP}")
                return FirewallRuleset.RULE2D
            }
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                logd("firewall($connId): global ip bypass universal, $uid, ${connInfo.destIP}")
                return FirewallRuleset.RULE2C
            }
            IpRulesManager.IpRuleStatus.TRUST -> { /* no-op, pass-through */ }
            IpRulesManager.IpRuleStatus.NONE -> { /* no-op, pass-through */ }
        }

        // Rule 15: DNS blocked IPs
        if (anyRealIpBlocked) {
            logd("firewall($connId): dns blocked, $uid, ${connInfo.query}")
            return FirewallRuleset.RULE2G
        }

        // Rule 16: Special apps (DNS proxy, SOCKS5, HTTP proxy)
        if (isSplApp) {
            logd("firewall($connId): special app, $uid, ${connInfo.query}")
            return FirewallRuleset.RULE0
        }

        // Rule 17-24: Universal firewall rules
        return evaluateUniversalRules(connInfo, uid)
    }

    private suspend fun evaluateUniversalRules(
        connInfo: ConnTrackerMetaData,
        uid: Int
    ): FirewallRuleset {
        val connId = connInfo.connId
        val isMetered = isConnectionMetered(connInfo.destIP)

        // Block metered connections
        if (persistentState.getBlockMeteredConnections() && isMetered) {
            logd("firewall($connId): metered blocked, $uid")
            return FirewallRuleset.RULE1F
        }

        // Universal lockdown
        if (universalLockdown()) {
            logd("firewall($connId): universal lockdown, $uid")
            return FirewallRuleset.RULE11
        }

        // Block HTTP
        if (httpBlocked(connInfo.destPort)) {
            logd("firewall($connId): http blocked, $uid")
            return FirewallRuleset.RULE10
        }

        // Device locked
        if (deviceLocked()) {
            logd("firewall($connId): device locked, $uid")
            return FirewallRuleset.RULE3
        }

        // Block UDP (except DNS)
        if (udpBlocked(uid, connInfo.protocol, connInfo.destPort)) {
            logd("firewall($connId): udp blocked, $uid")
            return FirewallRuleset.RULE6
        }

        // Block background data
        if (blockBackgroundData(uid)) {
            logd("firewall($connId): background data blocked, $uid")
            return FirewallRuleset.RULE4
        }

        // DNS proxy mode
        if (dnsProxied(connInfo.destPort)) {
            logd("firewall($connId): dns proxied, $uid")
            return FirewallRuleset.RULE9
        }

        // DNS bypass detection
        if (dnsBypassed(connInfo.query)) {
            logd("firewall($connId): dns bypassed, $uid")
            return FirewallRuleset.RULE7
        }

        logd("no firewall rule($connId), uid=${connInfo.uid}")
        return FirewallRuleset.RULE0
    }

    // Helper methods

    private fun logd(msg: String) {
        Logger.d(LOG_TAG_VPN, "$TAG $msg")
    }

    private suspend fun allowOrbot(uid: Int): Boolean {
        return OrbotHelper.ORBOT_PACKAGE_NAME == FirewallManager.getPackageNameByUid(uid)
    }

    private fun unknownAppBlocked(uid: Int): Boolean {
        return persistentState.getBlockUnknownConnections() && isMissingOrInvalidUid(uid)
    }

    private suspend fun newAppBlocked(uid: Int): Boolean {
        if (!persistentState.getBlockNewlyInstalledApp() || isMissingOrInvalidUid(uid)) {
            return false
        }
        return !waitAndCheckIfUidAllowed(uid)
    }

    private suspend fun waitAndCheckIfUidAllowed(uid: Int): Boolean {
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
        var attempt = 0
        
        while (remainingWaitMs > 0) {
            if (FirewallManager.hasUid(uid) && !FirewallManager.isUidFirewalled(uid)) {
                return true
            }
            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt++
        }
        return false
    }

    private fun exponentialBackoff(remainingWaitMs: Long, attempt: Int): Long {
        val exponent = exp(attempt)
        val randomValue = rand.nextLong(exponent - BASE_WAIT_MS + 1) + BASE_WAIT_MS
        val waitTimeMs = min(randomValue, remainingWaitMs)
        Thread.sleep(waitTimeMs)
        return remainingWaitMs - waitTimeMs
    }

    private fun exp(pow: Int): Long {
        return if (pow == 0) BASE_WAIT_MS else (1 shl pow) * BASE_WAIT_MS
    }

    private fun appBlocked(
        connInfo: ConnTrackerMetaData,
        connectionStatus: FirewallManager.ConnectionStatus
    ): FirewallRuleset? {
        if (connectionStatus.blocked()) {
            return FirewallRuleset.RULE1
        }

        val isMetered = isConnectionMetered(connInfo.destIP)
        if (connectionStatus.wifi() && !isMetered) {
            return FirewallRuleset.RULE1D
        }
        if (connectionStatus.mobileData() && isMetered) {
            return FirewallRuleset.RULE1E
        }
        return null
    }

    private fun getDomainRule(domain: String?, uid: Int): Pair<DomainRulesManager.Status, String?> {
        if (domain.isNullOrEmpty()) {
            return Pair(DomainRulesManager.Status.NONE, "")
        }

        val domains = if (isAtleastR()) {
            val d = domain.lowercase(Locale.getDefault()).split(",").firstOrNull()
            if (d.isNullOrEmpty()) return Pair(DomainRulesManager.Status.NONE, "")
            listOf(d)
        } else {
            domain.lowercase(Locale.getDefault()).split(",")
        }

        if (domains.isEmpty()) {
            return Pair(DomainRulesManager.Status.NONE, "")
        }

        for (d in domains) {
            val status = DomainRulesManager.status(d, uid)
            if (status != DomainRulesManager.Status.NONE) {
                return Pair(status, d)
            }
        }
        return Pair(DomainRulesManager.Status.NONE, domains.firstOrNull())
    }

    private fun uidIpStatus(uid: Int, destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        return ipStatus(uid, destIp, destPort)
    }

    private fun globalIpRule(destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        return ipStatus(UID_EVERYBODY, destIp, destPort)
    }

    private fun ipStatus(uid: Int, destIp: String, destPort: Int): IpRulesManager.IpRuleStatus {
        if (destIp.isEmpty() || Utilities.isUnspecifiedIp(destIp)) {
            return IpRulesManager.IpRuleStatus.NONE
        }

        val statusIpPort = IpRulesManager.hasRule(uid, destIp, destPort)
        if (statusIpPort != IpRulesManager.IpRuleStatus.NONE) {
            return statusIpPort
        }

        // Check IPv4-in-IPv6 if configured
        if (persistentState.filterIpv4inIpv6) {
            val addr = try {
                IPAddressString(destIp).address
            } catch (_: Exception) {
                return IpRulesManager.IpRuleStatus.NONE
            }

            val ip4in6 = IPUtil.ip4in6(addr) ?: return IpRulesManager.IpRuleStatus.NONE
            val ip4str = ip4in6.toNormalizedString()
            return IpRulesManager.hasRule(uid, ip4str, destPort)
        }
        return statusIpPort
    }

    private fun universalLockdown(): Boolean = persistentState.getUniversalLockdown()

    private fun httpBlocked(port: Int): Boolean {
        return port == KnownPorts.HTTP_PORT && persistentState.getBlockHttpConnections()
    }

    private fun dnsProxied(port: Int): Boolean {
        return appConfig.getBraveMode().isDnsFirewallMode() &&
                appConfig.preventDnsLeaks() &&
                KnownPorts.isDns(port)
    }

    private fun dnsBypassed(query: String?): Boolean {
        return persistentState.getDisallowDnsBypass() && query.isNullOrEmpty()
    }

    private fun isLockdown(): Boolean = networkInfoProvider.isLockdown()

    private fun isAppPaused(): Boolean = networkInfoProvider.isAppPaused()

    private fun isConnectionMetered(dst: String): Boolean = networkInfoProvider.isConnectionMetered(dst)

    private fun udpBlocked(uid: Int, protocol: Int, port: Int): Boolean {
        if (!persistentState.getUdpBlocked()) return false
        if (protocol != Protocol.UDP.protocolType) return false
        if (KnownPorts.isDns(port)) return false // Allow DNS
        
        // For NTP, allow from system apps - simplified check
        if (KnownPorts.isNtp(port)) {
            // Assume non-system apps are blocked for NTP
            return false // Allow NTP for now
        }
        return true
    }

    private suspend fun blockBackgroundData(uid: Int): Boolean {
        if (!persistentState.getBlockAppWhenBackground()) return false
        
        if (keyguardManager == null) {
            keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        return !waitAndCheckIfAppForeground(uid)
    }

    private suspend fun waitAndCheckIfAppForeground(uid: Int): Boolean {
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
        var attempt = 0
        
        while (remainingWaitMs > 0) {
            if (FirewallManager.isAppForeground(uid, keyguardManager)) {
                return true
            }
            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt++
        }
        return false
    }

    private fun deviceLocked(): Boolean {
        if (!persistentState.getBlockWhenDeviceLocked()) return false
        
        if (keyguardManager == null) {
            keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        return keyguardManager?.isKeyguardLocked == true
    }
}

/**
 * Provides network information for firewall evaluation.
 * Implement this interface to supply network state to the firewall evaluator.
 */
interface NetworkInfoProvider {
    fun isLockdown(): Boolean
    fun isAppPaused(): Boolean
    fun isConnectionMetered(dstIp: String): Boolean
}
