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
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.tunnel.TunDnsManager
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.TransportIdDecoder
import com.celzero.bravedns.util.Utilities.isAtleastP
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.intra.Mark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// dry-run facade over TunDnsManager/TunFirewallManager for the connection simulator
// screen: given a domain, an optional destination ip and an app uid, it reports which
// dns transport and proxy would be selected, the local-blocklist behavior, and the
// firewall rule outcome, all against the current configuration; no state is mutated
object ConnectionSimulator : KoinComponent {

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val appCtx by inject<Context>()

    // never used for launches while dryRun = true (firewall() only launches io() for
    // the add-new-app db write, which dryRun skips); kept to satisfy FirewallParameters
    private val simScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class SimulationInput(
        val domain: String,
        val ip: String? = null,
        val uid: Int? = null, // null = unknown / no-uid app
        val qtype: Long = 1L // A
    )

    data class DecisionStep(val text: String, val rule: FirewallRuleset? = null)

    data class SimulationResult(
        val blocked: Boolean,
        val detailLines: List<String>,
        val configLines: List<String>,
        val steps: List<DecisionStep>,
        val firewallSkipped: Boolean,
        val dnsDecision: TunDnsManager.DnsDecision?
    )

    suspend fun simulate(input: SimulationInput): SimulationResult {
        val uid = input.uid ?: INVALID_UID
        val domain = sanitizeDomain(input.domain)
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val keyguardManager = appCtx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val isLockdown = VpnController.isVpnLockdown()
        val isPaused = VpnController.isAppPaused()
        val isCaptivePortal = isCaptivePortal(cm)
        val isCellular = isActiveNetworkCellular(cm)
        val ssid = VpnController.underlyingSsid()

        val steps = mutableListOf<DecisionStep>()
        val detailLines = mutableListOf<String>()

        steps.add(DecisionStep("App: ${describeApp(uid)}"))

        // stage 1: dns query decision (transport + noblock), identical to the live onQuery path
        val dnsParams = TunDnsManager.DnsParams(
            origin = "sim",
            uidStr = uid.toString(),
            fqdn = domain,
            qtype = input.qtype,
            isLockdown = isLockdown,
            ssid = ssid,
            isIfaceCellular = isCellular,
            isCaptivePortal = isCaptivePortal
        )
        val dnsDecision = TunDnsManager.decideQuery(dnsParams)
        val dnsTidLines = TransportIdDecoder.decodeTidCsv(dnsDecision.tid.first + dnsTidSuffix(dnsDecision))
        steps.add(DecisionStep("DNS decision: branch \"${dnsDecision.branch}\"${dnsDecision.noblock.mapBool(" (local blocklists bypassed)", " (local blocklists apply)")}"))
        detailLines.addAll(dnsTidLines.map { "DNS transport: $it" })

        var blocked = isDnsBlocked(dnsDecision)
        if (dnsDecision.qtypeBlocked) {
            steps.add(DecisionStep("DNS record type (qtype ${input.qtype}) is blocked by the allowed-record-types setting", FirewallRuleset.RULE0))
            blocked = true
        }

        // stage 2: firewall / upstream-answer-stage decision (only when an ip is provided);
        // runs the exact live path via decideUpstreamAnswer (port-rule skip, unknown-app
        // block, bypass-transport rewrite included), in dry-run mode
        var firewallSkipped = false
        var upstreamBranch = ""
        val providedIp = input.ip?.trim().orEmpty()
        if (providedIp.isEmpty()) {
            firewallSkipped = true
            steps.add(DecisionStep("No IP provided: firewall rules that need a destination IP (IP rules, metered, universal lockdown) were not evaluated"))
        } else {
            val keyguardManager2 = keyguardManager
            val cm2 = cm
            val smm = DNSSummary().apply {
                this.uid = uid.toString()
                fid = "sim"
                qName = domain
                qType = input.qtype
                blocklists = ""
            }
            val upstreamParams = TunDnsManager.UpstreamAnswerParams(
                scope = simScope,
                id = "sim",
                smm = smm,
                rcvdDnsOpts = TunDnsManager.decisionToDnsOpts(dnsDecision),
                ipcsv = providedIp,
                isLockdown = isLockdown,
                isDeviceLocked = keyguardManager2.isKeyguardLocked && persistentState.getBlockWhenDeviceLocked(),
                underlyingNetworks = null, // falls back to active-network metered state
                keyguardManager = keyguardManager2,
                connectivityManager = cm2,
                accessibilityServiceFunctional = false,
                isSpecialApp = { u -> TunFlowManager.isSpecialApp(u) },
                isConnectionMetered = { _ -> cm2.isActiveNetworkMetered },
                determineProxyDetails = { _, _, _ -> Mark() },
                onDeviceLocked = null,
                onAccessibilityFailure = null,
                isCaptivePortal = isCaptivePortal
            )
            val decision = TunDnsManager.decideUpstreamAnswer(upstreamParams, dryRun = true)
            upstreamBranch = decision.branch
            when (decision.action) {
                TunDnsManager.UpstreamAction.BLOCK -> {
                    val rule = decision.rule ?: FirewallRuleset.RULE0
                    steps.add(DecisionStep("Blocked at the DNS-answer stage: ${describeRule(rule)}", rule))
                    blocked = true
                }
                TunDnsManager.UpstreamAction.BYPASS -> {
                    val ruleTxt = decision.rule?.let { describeRule(it) } ?: "bypass"
                    steps.add(DecisionStep("Allowed via bypass rule at the DNS-answer stage: $ruleTxt (local blocklists bypassed on resolve)", decision.rule))
                }
                TunDnsManager.UpstreamAction.KEEP_PREVIOUS -> {
                    val ruleTxt = decision.rule?.let { describeRule(it) }
                    steps.add(
                        if (ruleTxt != null) {
                            DecisionStep("DNS-answer stage: no change, rule $ruleTxt did not alter the outcome", decision.rule)
                        } else {
                            DecisionStep("DNS-answer stage: no change (branch \"${decision.branch}\")")
                        }
                    )
                }
            }
        }

        detailLines.add(
            if (dnsDecision.noblock) {
                "Blocklists: local Rethink blocklists bypassed (noblock)"
            } else {
                "Blocklists: local Rethink blocklists apply"
            }
        )
        detailLines.add("DNS decision branch: ${dnsDecision.branch}")
        if (firewallSkipped) {
            detailLines.add("Firewall: skipped (no IP provided)")
        } else {
            detailLines.add("Firewall (DNS-answer stage) branch: $upstreamBranch")
        }

        return SimulationResult(
            blocked = blocked,
            detailLines = detailLines,
            configLines = configSnapshot(isLockdown, isPaused, isCaptivePortal, isCellular),
            steps = steps,
            firewallSkipped = firewallSkipped,
            dnsDecision = dnsDecision
        )
    }

    private fun dnsTidSuffix(d: TunDnsManager.DnsDecision): String {
        return if (d.tid.second.isNotBlank()) ",${d.tid.second}" else ""
    }

    private fun isDnsBlocked(d: TunDnsManager.DnsDecision): Boolean {
        return d.tid.first.split(",").any { token ->
            val transport = token.split(":").firstOrNull()?.removePrefix(Backend.CT)
            transport == Backend.BlockAll || transport == Backend.Block
        }
    }

    private fun Boolean.mapBool(ifTrue: String, ifFalse: String): String = if (this) ifTrue else ifFalse

    private suspend fun describeApp(uid: Int): String {
        if (uid == INVALID_UID) return "Unknown app (no uid)"
        return runCatching {
            val names = FirewallManager.getAppNamesByUid(uid)
            if (names.isEmpty()) "uid $uid" else "${names.joinToString(", ")} (uid $uid)"
        }.getOrDefault("uid $uid")
    }

    private fun describeRule(rule: FirewallRuleset): String {
        return "${appCtx.getString(rule.title)} [${rule.id}]"
    }

    private fun sanitizeDomain(raw: String): String {
        return raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .trimEnd('.')
            .substringBefore('/')
    }

    private fun configSnapshot(isLockdown: Boolean, isPaused: Boolean, isCaptivePortal: Boolean, isCellular: Boolean): List<String> {
        val mode = appConfig.getBraveMode()
        val modeStr = when {
            mode.isDnsMode() -> "DNS only"
            mode.isDnsFirewallMode() -> "DNS + Firewall"
            else -> "Firewall only"
        }
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return buildList {
            add("Mode: $modeStr")
            add("DNS: ${describeDnsSetting()}")
            add("Split DNS: ${persistentState.splitDns}, DNS cache: ${persistentState.enableDnsCache}, DNS ALG: ${persistentState.enableDnsAlg}")
            add("Prevent DNS leaks: ${appConfig.preventDnsLeaks()}, Proxy DNS: ${persistentState.proxyDns}")
            add("WireGuard global lockdown: ${persistentState.wgGlobalLockdown}")
            add("VPN: ${if (VpnController.hasTunnel()) "on" else "off"}, paused: $isPaused, lockdown: $isLockdown, captive portal: $isCaptivePortal")
            add("Network: ${if (isCellular) "cellular" else "non-cellular"}, metered: ${cm.isActiveNetworkMetered}")
            add("Universal firewall: metered-block ${persistentState.getBlockMeteredConnections()}, lockdown ${persistentState.getUniversalLockdown()}, http-block ${persistentState.getBlockHttpConnections()}, udp-block ${persistentState.getUdpBlocked()}, bg-block ${persistentState.getBlockAppWhenBackground()}, block-unknown ${persistentState.getBlockUnknownConnections()}, block-new-apps ${persistentState.getBlockNewlyInstalledApp()}")
        }
    }

    private fun describeDnsSetting(): String {
        return when {
            appConfig.isSystemDns() -> "System DNS"
            appConfig.isSmartDnsEnabled() -> "Smart DNS / Plus"
            appConfig.isRethinkDnsConnected() -> "Rethink DNS (DoH/DoT)"
            appConfig.isDnsProxyActive() -> "DNS proxy"
            else -> "custom"
        }
    }

    private fun isCaptivePortal(cm: ConnectivityManager): Boolean {
        if (!isAtleastP()) return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }

    private fun isActiveNetworkCellular(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
