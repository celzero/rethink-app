package com.celzero.bravedns.service

import com.celzero.firestack.backend.Backend

object ProxyRoutingEngine {

    enum class Reason {
        RETHINK_DIRECT,
        EXCLUDED_APP,
        WIREGUARD,
        NO_PROXY_ACTIVE,
        ORBOT_DIRECT_APP,
        ORBOT_PROXY,
        SOCKS5_DIRECT_APP,
        SOCKS5_PROXY,
        HTTP_DIRECT_APP,
        HTTP_PROXY,
        DNS_PROXY_DIRECT_APP,
        FALLBACK_BASE_OR_EXIT
    }

    data class SpecialAppRequest(
        val isDnsFirewallMode: Boolean,
        val isOrbotProxyEnabled: Boolean,
        val isCustomSocks5Enabled: Boolean,
        val isCustomHttpProxyEnabled: Boolean,
        val isDnsProxyActive: Boolean,
        val packageName: String?,
        val orbotProxyAppName: String?,
        val socks5ProxyAppName: String?,
        val httpProxyAppName: String?,
        val dnsProxyAppName: String?
    )

    data class RoutingRequest(
        val uid: Int,
        val rethinkUid: Int,
        val rinr: Boolean,
        val autoProxyEnabled: Boolean,
        val blockedByRule: String,
        val appExcludedFromProxy: Boolean,
        val baseOrExitProxyId: String,
        val wireguardProxyIds: List<String>,
        val isProxyEnabled: Boolean,
        val isDnsProxyActive: Boolean,
        val isOrbotProxyEnabled: Boolean,
        val isCustomSocks5Enabled: Boolean,
        val isCustomHttpProxyEnabled: Boolean,
        val packageName: String?,
        val orbotProxyAppName: String?,
        val orbotProxyAssignedToApp: Boolean,
        val socks5ProxyAppName: String?,
        val httpProxyAppName: String?,
        val dnsProxyAppName: String?
    )

    data class RoutingDecision(
        val proxyIds: String,
        val reason: Reason,
        val markBlocked: Boolean = false,
        val blockedByRuleOverride: String? = null,
        val orbotProxyEnabledButAppNotIncluded: Boolean = false
    )

    fun matchesSelectedProxyApp(proxyAppPackageName: String?, packageName: String?): Boolean {
        return !proxyAppPackageName.isNullOrBlank() &&
            !packageName.isNullOrBlank() &&
            proxyAppPackageName == packageName
    }

    fun resolveBaseOrExitProxyId(
        doubleLoopback: Boolean,
        blockedByRule: String,
        rinr: Boolean,
        uid: Int,
        rethinkUid: Int,
        autoProxyEnabled: Boolean
    ): String {
        val autoOrExit = if (autoProxyEnabled) Backend.Auto else Backend.Exit
        var baseOrExit =
            if (doubleLoopback || blockedByRule == FirewallRuleset.RULE9.id) {
                Backend.Base
            } else {
                autoOrExit
            }

        // When route-rethink-in-rethink is on, rethink itself should not use Base.
        if (rinr && uid == rethinkUid) {
            baseOrExit = autoOrExit
        }
        return baseOrExit
    }

    fun isSpecialApp(request: SpecialAppRequest): Boolean {
        if (!request.isDnsFirewallMode) return false

        val anySpecialProxyEnabled =
            request.isOrbotProxyEnabled ||
                request.isCustomSocks5Enabled ||
                request.isCustomHttpProxyEnabled ||
                request.isDnsProxyActive

        if (!anySpecialProxyEnabled) return false

        if (request.isOrbotProxyEnabled &&
            matchesSelectedProxyApp(request.orbotProxyAppName, request.packageName)
        ) {
            return true
        }

        if (request.isCustomSocks5Enabled &&
            matchesSelectedProxyApp(request.socks5ProxyAppName, request.packageName)
        ) {
            return true
        }

        if (request.isCustomHttpProxyEnabled &&
            matchesSelectedProxyApp(request.httpProxyAppName, request.packageName)
        ) {
            return true
        }

        return request.isDnsProxyActive &&
            matchesSelectedProxyApp(request.dnsProxyAppName, request.packageName)
    }

    fun determineRoute(request: RoutingRequest): RoutingDecision {
        val autoOrExit = if (request.autoProxyEnabled) Backend.Auto else Backend.Exit

        if (request.uid == request.rethinkUid && !request.rinr) {
            return RoutingDecision(proxyIds = autoOrExit, reason = Reason.RETHINK_DIRECT)
        }

        if (request.appExcludedFromProxy) {
            val overrideRule =
                if (request.blockedByRule == FirewallRuleset.RULE0.id) {
                    FirewallRuleset.RULE15.id
                } else {
                    null
                }

            return RoutingDecision(
                proxyIds = request.baseOrExitProxyId,
                reason = Reason.EXCLUDED_APP,
                blockedByRuleOverride = overrideRule
            )
        }

        if (request.wireguardProxyIds.isNotEmpty() &&
            request.wireguardProxyIds.first() != request.baseOrExitProxyId
        ) {
            val hasBlock = request.wireguardProxyIds.contains(Backend.Block)
            val ids = request.wireguardProxyIds.joinToString(",")

            return RoutingDecision(
                proxyIds = if (ids.isEmpty()) request.baseOrExitProxyId else ids,
                reason = Reason.WIREGUARD,
                markBlocked = hasBlock,
                blockedByRuleOverride = if (hasBlock) FirewallRuleset.RULE17.id else null
            )
        }

        if (!request.isProxyEnabled && !request.isDnsProxyActive) {
            return RoutingDecision(
                proxyIds = request.baseOrExitProxyId,
                reason = Reason.NO_PROXY_ACTIVE
            )
        }

        if (request.isOrbotProxyEnabled) {
            if (matchesSelectedProxyApp(request.orbotProxyAppName, request.packageName)) {
                return RoutingDecision(proxyIds = autoOrExit, reason = Reason.ORBOT_DIRECT_APP)
            }

            if (request.orbotProxyAssignedToApp) {
                return RoutingDecision(
                    proxyIds = ProxyManager.ID_ORBOT_BASE,
                    reason = Reason.ORBOT_PROXY
                )
            }
        }

        if (request.isCustomSocks5Enabled) {
            if (matchesSelectedProxyApp(request.socks5ProxyAppName, request.packageName)) {
                return RoutingDecision(proxyIds = autoOrExit, reason = Reason.SOCKS5_DIRECT_APP)
            }

            return RoutingDecision(proxyIds = ProxyManager.ID_S5_BASE, reason = Reason.SOCKS5_PROXY)
        }

        if (request.isCustomHttpProxyEnabled) {
            if (matchesSelectedProxyApp(request.httpProxyAppName, request.packageName)) {
                return RoutingDecision(proxyIds = autoOrExit, reason = Reason.HTTP_DIRECT_APP)
            }

            return RoutingDecision(proxyIds = ProxyManager.ID_HTTP_BASE, reason = Reason.HTTP_PROXY)
        }

        if (request.isDnsProxyActive &&
            matchesSelectedProxyApp(request.dnsProxyAppName, request.packageName)
        ) {
            return RoutingDecision(proxyIds = autoOrExit, reason = Reason.DNS_PROXY_DIRECT_APP)
        }

        return RoutingDecision(
            proxyIds = request.baseOrExitProxyId,
            reason = Reason.FALLBACK_BASE_OR_EXIT,
            orbotProxyEnabledButAppNotIncluded = request.isOrbotProxyEnabled
        )
    }
}
