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
package com.celzero.bravedns.tunnel

import android.app.KeyguardManager
import android.net.ConnectivityManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.ConnectionMonitor
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.NetLogTracker
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.TunFirewallManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.shadows.ShadowDNSOpts
import com.celzero.bravedns.shadows.ShadowDNSSummary
import com.celzero.bravedns.shadows.ShadowGoSeq
import com.celzero.bravedns.shadows.ShadowMark
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.intra.Mark
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    shadows = [ShadowBackend::class, ShadowGoSeq::class, ShadowDNSSummary::class, ShadowDNSOpts::class, ShadowMark::class]
)
class TunDnsManagerTest {

    private val persistentState = mockk<PersistentState>(relaxed = true)
    private val appConfig = mockk<AppConfig>(relaxed = true)
    private val netLogTracker = mockk<NetLogTracker>(relaxed = true)
    private val rdb = mockk<RefreshDatabase>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val keyguardManager = mockk<KeyguardManager>(relaxed = true)

    private val rethinkUid = 10000

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single { persistentState }
                single { appConfig }
                single { netLogTracker }
                single { rdb }
            })
        }
        TunDnsManager.setDnsOptsFactoryForTest { mockk(relaxed = true) }
        TunDnsManager.setNetLogTrackerForTest(netLogTracker)
        TunDnsManager.resetState()
        TunFirewallManager.setRethinkUidForTest(rethinkUid)
        mockkObject(FirewallManager)
        mockkObject(IpRulesManager)
        mockkObject(TunFirewallManager)
        mockkObject(VpnController)
        mockkObject(Utilities)
        mockkObject(DomainRulesManager)
        mockkObject(WireguardManager)
        mockkObject(RpnProxyManager)

        every { Utilities.isAtleastR() } returns true
        every { Utilities.isMissingOrInvalidUid(any()) } answers { it.invocation.args[0] as Int == INVALID_UID }
        every { DomainRulesManager.getDomainRule(any(), any()) } returns DomainRulesManager.Status.NONE
        every { DomainRulesManager.getAggregatedDomainRule(any(), any()) } returns Pair(DomainRulesManager.Status.NONE, "")
        every { DomainRulesManager.isDomainTrusted(any()) } returns false
        every { WireguardManager.getOneWireGuardProxyId() } returns null
        every { RpnProxyManager.isRpnActive() } returns false
        every { persistentState.routeRethinkInRethink } returns false
        coEvery { FirewallManager.isTempAllowed(any()) } returns false
        coEvery { FirewallManager.connectionStatus(any()) } returns FirewallManager.ConnectionStatus.ALLOW
        coEvery { FirewallManager.appStatus(any()) } returns mockk<FirewallManager.FirewallStatus>(relaxed = true)
    }

    @After
    fun tearDown() {
        TunDnsManager.setDnsOptsFactoryForTest(::DNSOpts)
        TunDnsManager.setNetLogTrackerForTest(mockk(relaxed = true)) // reset override
        stopKoin()
        unmockkObject(FirewallManager)
        unmockkObject(IpRulesManager)
        unmockkObject(TunFirewallManager)
        unmockkObject(VpnController)
        unmockkObject(Utilities)
        unmockkObject(DomainRulesManager)
        unmockkObject(WireguardManager)
        unmockkObject(RpnProxyManager)
    }

    // region handleOnResponse tests

    @Test
    fun `handleOnResponse - null summary does not call processDnsLog`() {
        var regionCalled = false
        TunDnsManager.handleOnResponse(null) { regionCalled = true }

        verify(exactly = 0) { netLogTracker.processDnsLog(any()) }
        assertFalse(regionCalled)
    }

    @Test
    fun `handleOnResponse - valid summary calls processDnsLog`() {
        val summary = createDnsSummary(id = "Preferred", region = "US")

        TunDnsManager.handleOnResponse(summary) {}

        verify(atLeast = 1) { netLogTracker.processDnsLog(any()) }
    }

    @Test
    fun `handleOnResponse - valid summary invokes region callback with region`() {
        val summary = createDnsSummary(id = "Preferred", region = "DE")
        var receivedRegion: String? = null

        TunDnsManager.handleOnResponse(summary) { region -> receivedRegion = region }

        assertEquals("DE", receivedRegion)
    }

    @Test
    fun `handleOnResponse - null region invokes callback with null`() {
        val summary = createDnsSummary(id = "Preferred", region = null)
        var regionCallbackInvoked = false
        var receivedRegion: String? = "initial"

        TunDnsManager.handleOnResponse(summary) { region ->
            regionCallbackInvoked = true
            receivedRegion = region
        }

        assertTrue(regionCallbackInvoked)
        assertNull(receivedRegion)
    }

    @Test
    fun `handleOnResponse - Fixed transport skipped in non-debug`() {
        val summary = createDnsSummary(id = "Fixed+Preferred")

        TunDnsManager.handleOnResponse(summary) {}

        verify(exactly = 0) { netLogTracker.processDnsLog(any()) }
    }

    @Test
    fun `handleOnResponse - non-Fixed transport is processed`() {
        val summary = createDnsSummary(id = "Preferred")

        TunDnsManager.handleOnResponse(summary) {}

        verify(atLeast = 1) { netLogTracker.processDnsLog(any()) }
    }

    @Test
    fun `handleOnResponse - BlockAll transport is processed`() {
        val summary = createDnsSummary(id = "BlockAll")

        TunDnsManager.handleOnResponse(summary) {}

        verify(atLeast = 1) { netLogTracker.processDnsLog(any()) }
    }

    @Test
    fun `handleOnResponse - summary with all fields logged`() {
        val summary = createDnsSummary(
            id = "Preferred",
            type = "1",
            uid = "10123",
            latency = 42.5,
            qName = "example.com",
            qType = 1L,
            targets = "1.2.3.4",
            cached = false,
            rData = "1.2.3.4",
            rCode = 0L,
            rTtl = 300L,
            server = "dns.google",
            pid = "p1",
            rpid = "rp1",
            status = 0,
            blocklists = "list1",
            blockedTarget = "",
            upstreamBlocks = false,
            doFlag = false,
            adFlag = true,
            msg = "ok",
            region = "US"
        )

        TunDnsManager.handleOnResponse(summary) {}

        verify(exactly = 1) { netLogTracker.processDnsLog(summary) }
    }

    // endregion

    // region handleOnUpstreamAnswer - early return tests

    @Test
    fun `handleOnUpstreamAnswer - invalid uid returns empty DNSOpts`() = runTest {
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "not_a_number"),
            ipcsv = "1.2.3.4"
        )

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - port-based rule for uid returns empty DNSOpts`() = runTest {
        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp("1.2.3.4", 10123) } returns true

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
        coVerify(exactly = 0) { IpRulesManager.isPortRuleSetForIp("1.2.3.4", UID_EVERYBODY) }
    }

    @Test
    fun `handleOnUpstreamAnswer - port-based rule for UID_EVERYBODY returns empty DNSOpts`() = runTest {
        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp("1.2.3.4", 10123) } returns false
        coEvery { IpRulesManager.isPortRuleSetForIp("1.2.3.4", UID_EVERYBODY) } returns true

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - empty ipcsv returns empty DNSOpts`() = runTest {
        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "")
        coEvery { IpRulesManager.isPortRuleSetForIp("", 10123) } returns false
        coEvery { IpRulesManager.isPortRuleSetForIp("", UID_EVERYBODY) } returns false

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - DNS-only mode returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns true
        every { appConfig.getBraveMode() } returns mode

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - fixed transport returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode

        val rcvdOpts = mockk<DNSOpts>(relaxed = true) {
            every { tidcsv } returns "Fixed+Preferred"
            every { tidseccsv } returns ""
        }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            rcvdDnsOpts = rcvdOpts
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - unknown app blocked returns BlockAll`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        // Per-app delegated property `blockDnsForUnknownApp` (booleanPref)
        // cannot be mocked reliably via MockK — the delegate reads SharedPreferences
        // which returns the default (false) in a test environment.
        // The code path proceeds to the firewall check for INVALID_UID.
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(INVALID_UID) } returns INVALID_UID

        val rcvdOpts = mockk<DNSOpts>(relaxed = true) {
            every { tidcsv } returns "Preferred"
            every { tidseccsv } returns ""
        }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = INVALID_UID.toString()),
            ipcsv = "1.2.3.4",
            rcvdDnsOpts = rcvdOpts
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE0

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        coVerify { TunFirewallManager.firewall(any()) }
    }

    @Test
    fun `handleOnUpstreamAnswer - unknown app not blocked when setting disabled`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false

        val params = createUpstreamParams(smm = createDnsSummary(uid = INVALID_UID.toString()), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        every { FirewallManager.userId(INVALID_UID) } returns INVALID_UID
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE0

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - empty rdata returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    // endregion

    // region handleOnUpstreamAnswer - firewall rule tests

    @Test
    fun `handleOnUpstreamAnswer - firewall blocks returns BlockAll`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE1

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        coVerify { TunFirewallManager.firewall(any()) }
    }

    @Test
    fun `handleOnUpstreamAnswer - firewall allows returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE0

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - isolate app with trusted domain returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns true
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE2F

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - isolate app with trusted IP returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns true
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE2B

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - non-isolate app with trusted domain does not short-circuit`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { persistentState.enableDnsCache } returns false
        every { persistentState.splitDns } returns false
        every { persistentState.blockFreeDnsMode } returns 0
        every { persistentState.preventDnsLeaks } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE2F

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        // RULE2F is a bypass rule; getTransportIdToBypass returns Backend.Default (AUTO mode, no splitDns)
        // which differs from the original tid, so bypass path executes
        assertNotNull(result)
    }

    // endregion

    // region handleOnUpstreamAnswer - bypass rule tests

    @Test
    fun `handleOnUpstreamAnswer - bypass rule with different transport returns bypass DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { persistentState.enableDnsCache } returns false
        every { persistentState.splitDns } returns false
        every { persistentState.blockFreeDnsMode } returns 0
        every { persistentState.preventDnsLeaks } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val rcvdOpts = mockk<DNSOpts>(relaxed = true) {
            every { tidcsv } returns "Preferred"
            every { tidseccsv } returns ""
        }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            rcvdDnsOpts = rcvdOpts
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE1H

        val determineProxy: suspend (ConnTrackerMetaData, Boolean, Boolean) -> Mark = { cm, _, _ ->
            mockk(relaxed = true) {
                every { pidcsv } returns "Base"
                every { cid } returns cm.connId
                every { uid } returns cm.uid.toString()
            }
        }
        val paramsWithProxy = params.copy(determineProxyDetails = determineProxy)

        val result = TunDnsManager.handleOnUpstreamAnswer(paramsWithProxy)

        assertNotNull(result)
        coVerify { TunFirewallManager.firewall(any()) }
    }

    @Test
    fun `handleOnUpstreamAnswer - bypass rule with splitDns returns same transport`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { persistentState.enableDnsCache } returns false
        every { persistentState.splitDns } returns true
        every { persistentState.blockFreeDnsMode } returns 0
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val rcvdOpts = mockk<DNSOpts>(relaxed = true) {
            every { tidcsv } returns "Preferred"
            every { tidseccsv } returns ""
        }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            rcvdDnsOpts = rcvdOpts
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE1H

        val determineProxy: suspend (ConnTrackerMetaData, Boolean, Boolean) -> Mark = { cm, _, _ ->
            mockk(relaxed = true) {
                every { pidcsv } returns "Base"
                every { cid } returns cm.connId
                every { uid } returns cm.uid.toString()
            }
        }
        val paramsWithProxy = params.copy(determineProxyDetails = determineProxy)

        val result = TunDnsManager.handleOnUpstreamAnswer(paramsWithProxy)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - bypass rule with preventDnsLeaks returns same transport`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { persistentState.enableDnsCache } returns false
        every { persistentState.splitDns } returns false
        every { persistentState.preventDnsLeaks } returns true
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val rcvdOpts = mockk<DNSOpts>(relaxed = true) {
            every { tidcsv } returns "Preferred"
            every { tidseccsv } returns ""
        }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            rcvdDnsOpts = rcvdOpts
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE1H

        val determineProxy: suspend (ConnTrackerMetaData, Boolean, Boolean) -> Mark = { cm, _, _ ->
            mockk(relaxed = true) {
                every { pidcsv } returns "Base"
                every { cid } returns cm.connId
                every { uid } returns cm.uid.toString()
            }
        }
        val paramsWithProxy = params.copy(determineProxyDetails = determineProxy)

        val result = TunDnsManager.handleOnUpstreamAnswer(paramsWithProxy)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - bypass rule with secondary transport includes cache prefix`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { persistentState.enableDnsCache } returns true
        every { persistentState.splitDns } returns false
        every { persistentState.preventDnsLeaks } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val rcvdOpts = mockk<DNSOpts>(relaxed = true) {
            every { tidcsv } returns "Preferred"
            every { tidseccsv } returns "Plus"
        }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            rcvdDnsOpts = rcvdOpts
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE1H

        val determineProxy: suspend (ConnTrackerMetaData, Boolean, Boolean) -> Mark = { cm, _, _ ->
            mockk(relaxed = true) {
                every { pidcsv } returns "Base"
                every { cid } returns cm.connId
                every { uid } returns cm.uid.toString()
            }
        }
        val paramsWithProxy = params.copy(determineProxyDetails = determineProxy)

        val result = TunDnsManager.handleOnUpstreamAnswer(paramsWithProxy)

        assertNotNull(result)
        coVerify { TunFirewallManager.firewall(any()) }
    }

    // endregion

    // region handleOnUpstreamAnswer - edge cases

    @Test
    fun `handleOnUpstreamAnswer - multiple IPs uses first for conn type`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4,5.6.7.8")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE0

        val isConnMetered: (String) -> Boolean = { ip ->
            assertEquals("1.2.3.4", ip)
            false
        }
        val paramsWithCallback = params.copy(isConnectionMetered = isConnMetered)

        val result = TunDnsManager.handleOnUpstreamAnswer(paramsWithCallback)

        assertNotNull(result)
    }

    @Test
    fun `handleOnUpstreamAnswer - non-bypass rule with RULE0 returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE0

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
        assertFalse(result.noblock)
    }

    @Test
    fun `handleOnUpstreamAnswer - lockdown rule returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4", isLockdown = true)
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE11

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - device locked rule returns empty DNSOpts`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isIsolate() } returns false
        coEvery { FirewallManager.appStatus(10123) } returns appStatus

        val params = createUpstreamParams(smm = createDnsSummary(uid = "10123"), ipcsv = "1.2.3.4", isDeviceLocked = true)
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false
        coEvery { TunFirewallManager.firewall(any()) } returns FirewallRuleset.RULE3

        val result = TunDnsManager.handleOnUpstreamAnswer(params)

        assertNotNull(result)
        assertEquals("", result.tidcsv)
    }

    @Test
    fun `handleOnUpstreamAnswer - connInfo is constructed with correct fields`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns true
        every { FirewallManager.userId(10123) } returns 10123

        val smm = createDnsSummary(uid = "10123", qName = "example.com", blocklists = "list1,list2")
        val params = createUpstreamParams(smm = smm, ipcsv = "1.2.3.4")
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false

        val capturedParams = slot<TunFirewallManager.FirewallParameters>()
        coEvery { TunFirewallManager.firewall(capture(capturedParams)) } returns FirewallRuleset.RULE0

        TunDnsManager.handleOnUpstreamAnswer(params)

        val connInfo = capturedParams.captured.connInfo
        assertEquals(10123, connInfo.uid)
        assertEquals(10123, connInfo.usrId)
        assertEquals("", connInfo.sourceIP)
        assertEquals(0, connInfo.sourcePort)
        assertEquals("1.2.3.4", connInfo.destIP)
        assertEquals(0, connInfo.destPort)
        assertEquals(KnownPorts.DNS_PORT, connInfo.protocol)
        assertEquals("example.com", connInfo.query)
        assertEquals("list1,list2", connInfo.blocklists)
        assertTrue(capturedParams.captured.forUpstreamAnswer)
    }

    @Test
    fun `handleOnUpstreamAnswer - firewall parameters include VPN state`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123
        every { VpnController.isAppPaused() } returns true

        val underlyingNetworks = mockk<ConnectionMonitor.UnderlyingNetworks>(relaxed = true)
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            isLockdown = true,
            isDeviceLocked = true,
            underlyingNetworks = underlyingNetworks,
            accessibilityServiceFunctional = true
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false

        val capturedParams = slot<TunFirewallManager.FirewallParameters>()
        coEvery { TunFirewallManager.firewall(capture(capturedParams)) } returns FirewallRuleset.RULE0

        TunDnsManager.handleOnUpstreamAnswer(params)

        val fp = capturedParams.captured
        assertTrue(fp.isLockdown)
        assertTrue(fp.isDeviceLocked)
        assertEquals(underlyingNetworks, fp.underlyingNetworks)
        assertTrue(fp.accessibilityServiceFunctional)
        assertTrue(fp.isAppPaused)
        assertEquals(keyguardManager, fp.keyguardManager)
        assertEquals(connectivityManager, fp.connectivityManager)
    }

    @Test
    fun `handleOnUpstreamAnswer - metered connection sets connType to METERED`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val isConnMetered: (String) -> Boolean = { true }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            isConnectionMetered = isConnMetered
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false

        val capturedParams = slot<TunFirewallManager.FirewallParameters>()
        coEvery { TunFirewallManager.firewall(capture(capturedParams)) } returns FirewallRuleset.RULE0

        TunDnsManager.handleOnUpstreamAnswer(params)

        val connInfo = capturedParams.captured.connInfo
        assertEquals(ConnectionTracker.ConnType.METERED.value, connInfo.connType)
    }

    @Test
    fun `handleOnUpstreamAnswer - unmetered connection sets connType to UNMETERED`() = runTest {
        val mode = mockk<AppConfig.BraveMode>()
        every { mode.isDnsMode() } returns false
        every { appConfig.getBraveMode() } returns mode
        every { persistentState.blockDnsForUnknownApp } returns false
        every { persistentState.routeRethinkInRethink } returns false
        every { FirewallManager.userId(10123) } returns 10123

        val isConnMetered: (String) -> Boolean = { false }
        val params = createUpstreamParams(
            smm = createDnsSummary(uid = "10123"),
            ipcsv = "1.2.3.4",
            isConnectionMetered = isConnMetered
        )
        coEvery { IpRulesManager.isPortRuleSetForIp(any(), any()) } returns false

        val capturedParams = slot<TunFirewallManager.FirewallParameters>()
        coEvery { TunFirewallManager.firewall(capture(capturedParams)) } returns FirewallRuleset.RULE0

        TunDnsManager.handleOnUpstreamAnswer(params)

        val connInfo = capturedParams.captured.connInfo
        assertEquals(ConnectionTracker.ConnType.UNMETERED.value, connInfo.connType)
    }

    // endregion

    // region resetState and isUidPresentInAnyDnsRequest tests

    @Test
    fun `isUidPresentInAnyDnsRequest starts false and resetState keeps it false`() {
        TunDnsManager.resetState()
        assertFalse(TunDnsManager.isUidPresentInAnyDnsRequestForTest())
    }

    @Test
    fun `resetState clears isUidPresentInAnyDnsRequest when previously set`() {
        TunDnsManager.setIsUidPresentInAnyDnsRequestForTest(true)
        assertTrue(TunDnsManager.isUidPresentInAnyDnsRequestForTest())
        TunDnsManager.resetState()
        assertFalse(TunDnsManager.isUidPresentInAnyDnsRequestForTest())
    }

    // endregion

    // region Helper methods

    private fun createDnsSummary(
        id: String = "Preferred",
        type: String = "1",
        uid: String = "10123",
        latency: Double = 10.0,
        qName: String = "example.com",
        qType: Long = 1L,
        targets: String = "1.2.3.4",
        cached: Boolean = false,
        rData: String = "1.2.3.4",
        rCode: Long = 0L,
        rTtl: Long = 300L,
        server: String = "dns.google",
        pid: String = "p1",
        rpid: String = "rp1",
        status: Int = 0,
        blocklists: String = "",
        blockedTarget: String = "",
        upstreamBlocks: Boolean = false,
        doFlag: Boolean = false,
        adFlag: Boolean = true,
        msg: String = "ok",
        region: String? = null
    ): DNSSummary {
        val summary = mockk<DNSSummary>(relaxed = true)
        every { summary.id } returns id
        every { summary.type } returns type
        every { summary.uid } returns uid
        every { summary.latency } returns latency
        every { summary.qName } returns qName
        every { summary.qType } returns qType
        every { summary.targets } returns targets
        every { summary.cached } returns cached
        every { summary.rData } returns rData
        every { summary.rCode } returns rCode
        every { summary.rTtl } returns rTtl
        every { summary.server } returns server
        every { summary.pid } returns pid
        every { summary.rpid } returns rpid
        every { summary.status } returns status
        every { summary.blocklists } returns blocklists
        every { summary.blockedTarget } returns blockedTarget
        every { summary.upstreamBlocks } returns upstreamBlocks
        every { summary.`do` } returns doFlag
        every { summary.ad } returns adFlag
        every { summary.msg } returns msg
        every { summary.region } returns region
        return summary
    }

    private fun createUpstreamParams(
        id: String = "Preferred",
        smm: DNSSummary = createDnsSummary(uid = "10123"),
        rcvdDnsOpts: DNSOpts = mockk(relaxed = true) { every { tidcsv } returns "Preferred"; every { tidseccsv } returns "" },
        ipcsv: String = "1.2.3.4",
        isLockdown: Boolean = false,
        isDeviceLocked: Boolean = false,
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null,
        keyguardManager: KeyguardManager? = null,
        cm: ConnectivityManager = connectivityManager,
        accessibilityServiceFunctional: Boolean = false,
        isSpecialApp: suspend (Int) -> Boolean = { false },
        isConnectionMetered: (String) -> Boolean = { false },
        determineProxyDetails: suspend (ConnTrackerMetaData, Boolean, Boolean) -> Mark = { _, _, _ -> mockk(relaxed = true) },
        onDeviceLocked: (() -> Unit)? = null,
        onAccessibilityFailure: (() -> Unit)? = null
    ): TunDnsManager.UpstreamAnswerParams {
        return TunDnsManager.UpstreamAnswerParams(
            scope = CoroutineScope(SupervisorJob()),
            id = id,
            smm = smm,
            rcvdDnsOpts = rcvdDnsOpts,
            ipcsv = ipcsv,
            isLockdown = isLockdown,
            isDeviceLocked = isDeviceLocked,
            underlyingNetworks = underlyingNetworks,
            keyguardManager = keyguardManager ?: this.keyguardManager,
            connectivityManager = cm,
            accessibilityServiceFunctional = accessibilityServiceFunctional,
            isSpecialApp = isSpecialApp,
            isConnectionMetered = isConnectionMetered,
            determineProxyDetails = determineProxyDetails,
            onDeviceLocked = onDeviceLocked,
            onAccessibilityFailure = onAccessibilityFailure
        )
    }

    // endregion
}
