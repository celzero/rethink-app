package com.celzero.bravedns.service

import com.celzero.firestack.backend.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRoutingEngineTest {

    @Test
    fun `special app should be false outside dns firewall mode`() {
        val request =
            specialAppRequest(
                isDnsFirewallMode = false,
                isCustomSocks5Enabled = true,
                packageName = "com.test.app",
                socks5ProxyAppName = "com.test.app"
            )

        assertFalse(ProxyRoutingEngine.isSpecialApp(request))
    }

    @Test
    fun `special app should be true when socks5 proxy app matches`() {
        val request =
            specialAppRequest(
                isCustomSocks5Enabled = true,
                packageName = "com.test.app",
                socks5ProxyAppName = "com.test.app"
            )

        assertTrue(ProxyRoutingEngine.isSpecialApp(request))
    }

    @Test
    fun `resolve base or exit should return base for dns proxy rule`() {
        val result =
            ProxyRoutingEngine.resolveBaseOrExitProxyId(
                doubleLoopback = false,
                blockedByRule = FirewallRuleset.RULE9.id,
                rinr = false,
                uid = 1000,
                rethinkUid = 2000,
                autoProxyEnabled = true
            )

        assertEquals(Backend.Base, result)
    }

    @Test
    fun `resolve base or exit should force auto or exit for rethink in rinr`() {
        val result =
            ProxyRoutingEngine.resolveBaseOrExitProxyId(
                doubleLoopback = true,
                blockedByRule = FirewallRuleset.RULE9.id,
                rinr = true,
                uid = 2000,
                rethinkUid = 2000,
                autoProxyEnabled = true
            )

        assertEquals(Backend.Auto, result)
    }

    @Test
    fun `determine route should return rethink direct route`() {
        val decision =
            ProxyRoutingEngine.determineRoute(
                routingRequest(
                    uid = 1000,
                    rethinkUid = 1000,
                    rinr = false
                )
            )

        assertEquals(Backend.Exit, decision.proxyIds)
        assertEquals(ProxyRoutingEngine.Reason.RETHINK_DIRECT, decision.reason)
    }

    @Test
    fun `determine route should apply rule15 for excluded app from rule0`() {
        val decision =
            ProxyRoutingEngine.determineRoute(
                routingRequest(
                    appExcludedFromProxy = true,
                    blockedByRule = FirewallRuleset.RULE0.id,
                    baseOrExitProxyId = Backend.Base
                )
            )

        assertEquals(Backend.Base, decision.proxyIds)
        assertEquals(FirewallRuleset.RULE15.id, decision.blockedByRuleOverride)
        assertEquals(ProxyRoutingEngine.Reason.EXCLUDED_APP, decision.reason)
    }

    @Test
    fun `determine route should mark block and rule17 for wireguard block candidate`() {
        val decision =
            ProxyRoutingEngine.determineRoute(
                routingRequest(
                    wireguardProxyIds = listOf(ProxyManager.ID_WG_BASE + "10", Backend.Block)
                )
            )

        assertTrue(decision.markBlocked)
        assertEquals(FirewallRuleset.RULE17.id, decision.blockedByRuleOverride)
        assertEquals(ProxyRoutingEngine.Reason.WIREGUARD, decision.reason)
    }

    @Test
    fun `determine route should use orbot proxy id when app assigned`() {
        val decision =
            ProxyRoutingEngine.determineRoute(
                routingRequest(
                    isOrbotProxyEnabled = true,
                    orbotProxyAssignedToApp = true
                )
            )

        assertEquals(ProxyManager.ID_ORBOT_BASE, decision.proxyIds)
        assertEquals(ProxyRoutingEngine.Reason.ORBOT_PROXY, decision.reason)
    }

    @Test
    fun `determine route should prefer socks5 over http`() {
        val decision =
            ProxyRoutingEngine.determineRoute(
                routingRequest(
                    isCustomSocks5Enabled = true,
                    isCustomHttpProxyEnabled = true
                )
            )

        assertEquals(ProxyManager.ID_S5_BASE, decision.proxyIds)
        assertEquals(ProxyRoutingEngine.Reason.SOCKS5_PROXY, decision.reason)
    }

    @Test
    fun `determine route should use dns direct app when only dns proxy app matches`() {
        val decision =
            ProxyRoutingEngine.determineRoute(
                routingRequest(
                    isProxyEnabled = false,
                    isDnsProxyActive = true,
                    packageName = "com.test.app",
                    dnsProxyAppName = "com.test.app"
                )
            )

        assertEquals(Backend.Exit, decision.proxyIds)
        assertEquals(ProxyRoutingEngine.Reason.DNS_PROXY_DIRECT_APP, decision.reason)
    }

    @Test
    fun `determine route precedence matrix`() {
        data class Case(
            val name: String,
            val request: ProxyRoutingEngine.RoutingRequest,
            val expectedProxy: String,
            val expectedReason: ProxyRoutingEngine.Reason,
            val expectedOrbotNotIncluded: Boolean? = null
        )

        val wgProxyId = ProxyManager.ID_WG_BASE + "7"
        val cases =
            listOf(
                Case(
                    name = "wireguard takes precedence",
                    request =
                        routingRequest(
                            wireguardProxyIds = listOf(wgProxyId),
                            isOrbotProxyEnabled = true,
                            orbotProxyAssignedToApp = true,
                            isCustomSocks5Enabled = true,
                            isCustomHttpProxyEnabled = true
                        ),
                    expectedProxy = wgProxyId,
                    expectedReason = ProxyRoutingEngine.Reason.WIREGUARD
                ),
                Case(
                    name = "orbot direct app takes precedence",
                    request =
                        routingRequest(
                            isOrbotProxyEnabled = true,
                            packageName = "com.test.app",
                            orbotProxyAppName = "com.test.app",
                            orbotProxyAssignedToApp = true
                        ),
                    expectedProxy = Backend.Exit,
                    expectedReason = ProxyRoutingEngine.Reason.ORBOT_DIRECT_APP
                ),
                Case(
                    name = "orbot proxy takes precedence over socks and http",
                    request =
                        routingRequest(
                            isOrbotProxyEnabled = true,
                            orbotProxyAssignedToApp = true,
                            isCustomSocks5Enabled = true,
                            isCustomHttpProxyEnabled = true
                        ),
                    expectedProxy = ProxyManager.ID_ORBOT_BASE,
                    expectedReason = ProxyRoutingEngine.Reason.ORBOT_PROXY
                ),
                Case(
                    name = "socks5 direct app takes precedence over proxy id",
                    request =
                        routingRequest(
                            isCustomSocks5Enabled = true,
                            packageName = "com.test.app",
                            socks5ProxyAppName = "com.test.app"
                        ),
                    expectedProxy = Backend.Exit,
                    expectedReason = ProxyRoutingEngine.Reason.SOCKS5_DIRECT_APP
                ),
                Case(
                    name = "socks5 proxy takes precedence over http proxy",
                    request =
                        routingRequest(
                            isCustomSocks5Enabled = true,
                            isCustomHttpProxyEnabled = true
                        ),
                    expectedProxy = ProxyManager.ID_S5_BASE,
                    expectedReason = ProxyRoutingEngine.Reason.SOCKS5_PROXY
                ),
                Case(
                    name = "http direct app selected",
                    request =
                        routingRequest(
                            isCustomHttpProxyEnabled = true,
                            packageName = "com.test.app",
                            httpProxyAppName = "com.test.app"
                        ),
                    expectedProxy = Backend.Exit,
                    expectedReason = ProxyRoutingEngine.Reason.HTTP_DIRECT_APP
                ),
                Case(
                    name = "http proxy selected when app does not match",
                    request =
                        routingRequest(
                            isCustomHttpProxyEnabled = true,
                            packageName = "com.other.app",
                            httpProxyAppName = "com.test.app"
                        ),
                    expectedProxy = ProxyManager.ID_HTTP_BASE,
                    expectedReason = ProxyRoutingEngine.Reason.HTTP_PROXY
                ),
                Case(
                    name = "fallback base or exit with orbot enabled but app not assigned",
                    request =
                        routingRequest(
                            isOrbotProxyEnabled = true,
                            orbotProxyAssignedToApp = false,
                            baseOrExitProxyId = Backend.Base
                        ),
                    expectedProxy = Backend.Base,
                    expectedReason = ProxyRoutingEngine.Reason.FALLBACK_BASE_OR_EXIT,
                    expectedOrbotNotIncluded = true
                ),
                Case(
                    name = "no proxy active returns base or exit",
                    request =
                        routingRequest(
                            isProxyEnabled = false,
                            isDnsProxyActive = false,
                            baseOrExitProxyId = Backend.Base
                        ),
                    expectedProxy = Backend.Base,
                    expectedReason = ProxyRoutingEngine.Reason.NO_PROXY_ACTIVE
                )
            )

        cases.forEach { case ->
            val decision = ProxyRoutingEngine.determineRoute(case.request)
            assertEquals("${case.name} proxy", case.expectedProxy, decision.proxyIds)
            assertEquals("${case.name} reason", case.expectedReason, decision.reason)
            case.expectedOrbotNotIncluded?.let {
                assertEquals("${case.name} orbot-not-included flag", it, decision.orbotProxyEnabledButAppNotIncluded)
            }
        }
    }

    private fun specialAppRequest(
        isDnsFirewallMode: Boolean = true,
        isOrbotProxyEnabled: Boolean = false,
        isCustomSocks5Enabled: Boolean = false,
        isCustomHttpProxyEnabled: Boolean = false,
        isDnsProxyActive: Boolean = false,
        packageName: String? = null,
        orbotProxyAppName: String? = null,
        socks5ProxyAppName: String? = null,
        httpProxyAppName: String? = null,
        dnsProxyAppName: String? = null
    ): ProxyRoutingEngine.SpecialAppRequest {
        return ProxyRoutingEngine.SpecialAppRequest(
            isDnsFirewallMode = isDnsFirewallMode,
            isOrbotProxyEnabled = isOrbotProxyEnabled,
            isCustomSocks5Enabled = isCustomSocks5Enabled,
            isCustomHttpProxyEnabled = isCustomHttpProxyEnabled,
            isDnsProxyActive = isDnsProxyActive,
            packageName = packageName,
            orbotProxyAppName = orbotProxyAppName,
            socks5ProxyAppName = socks5ProxyAppName,
            httpProxyAppName = httpProxyAppName,
            dnsProxyAppName = dnsProxyAppName
        )
    }

    private fun routingRequest(
        uid: Int = 2001,
        rethinkUid: Int = 1000,
        rinr: Boolean = false,
        autoProxyEnabled: Boolean = false,
        blockedByRule: String = FirewallRuleset.RULE8.id,
        appExcludedFromProxy: Boolean = false,
        baseOrExitProxyId: String = Backend.Exit,
        wireguardProxyIds: List<String> = emptyList(),
        isProxyEnabled: Boolean = true,
        isDnsProxyActive: Boolean = false,
        isOrbotProxyEnabled: Boolean = false,
        isCustomSocks5Enabled: Boolean = false,
        isCustomHttpProxyEnabled: Boolean = false,
        packageName: String? = null,
        orbotProxyAppName: String? = null,
        orbotProxyAssignedToApp: Boolean = false,
        socks5ProxyAppName: String? = null,
        httpProxyAppName: String? = null,
        dnsProxyAppName: String? = null
    ): ProxyRoutingEngine.RoutingRequest {
        return ProxyRoutingEngine.RoutingRequest(
            uid = uid,
            rethinkUid = rethinkUid,
            rinr = rinr,
            autoProxyEnabled = autoProxyEnabled,
            blockedByRule = blockedByRule,
            appExcludedFromProxy = appExcludedFromProxy,
            baseOrExitProxyId = baseOrExitProxyId,
            wireguardProxyIds = wireguardProxyIds,
            isProxyEnabled = isProxyEnabled,
            isDnsProxyActive = isDnsProxyActive,
            isOrbotProxyEnabled = isOrbotProxyEnabled,
            isCustomSocks5Enabled = isCustomSocks5Enabled,
            isCustomHttpProxyEnabled = isCustomHttpProxyEnabled,
            packageName = packageName,
            orbotProxyAppName = orbotProxyAppName,
            orbotProxyAssignedToApp = orbotProxyAssignedToApp,
            socks5ProxyAppName = socks5ProxyAppName,
            httpProxyAppName = httpProxyAppName,
            dnsProxyAppName = dnsProxyAppName
        )
    }
}
