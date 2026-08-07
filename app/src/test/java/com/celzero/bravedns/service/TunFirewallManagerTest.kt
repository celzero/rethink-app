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
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class TunFirewallManagerTest : KoinTest {

    private val persistentState = mockk<PersistentState>(relaxed = true)
    private val appConfig = mockk<AppConfig>(relaxed = true)
    private val rdb = mockk<RefreshDatabase>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val rethinkUid = 10000

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single { persistentState }
                single { appConfig }
                single { rdb }
            })
        }
        
        TunFirewallManager.setRethinkUidForTest(rethinkUid)
        mockkObject(FirewallManager)
        mockkObject(DomainRulesManager)
        mockkObject(IpRulesManager)
        mockkObject(Utilities)

        // Default behavior to avoid unmocked exceptions
        coEvery { FirewallManager.appStatus(any()) } returns FirewallManager.FirewallStatus.NONE
        coEvery { FirewallManager.connectionStatus(any()) } returns FirewallManager.ConnectionStatus.ALLOW
        coEvery { FirewallManager.isTempAllowed(any()) } returns false
        coEvery { FirewallManager.hasUid(any()) } returns true
        coEvery { FirewallManager.isUidFirewalled(any()) } returns false
        every { Utilities.isAtleastR() } returns true
        every { Utilities.isMissingOrInvalidUid(any()) } answers { it.invocation.args[0] as Int == INVALID_UID }
        every { Utilities.isUnspecifiedIp(any()) } returns false
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkObject(FirewallManager)
        unmockkObject(DomainRulesManager)
        unmockkObject(IpRulesManager)
        unmockkObject(Utilities)
    }

    private fun createConnInfo(
        uid: Int = 10123,
        destIP: String = "1.1.1.1",
        destPort: Int = 443,
        protocol: Int = Protocol.TCP.protocolType
    ): ConnTrackerMetaData {
        return ConnTrackerMetaData(
            uid = uid,
            usrId = 0,
            sourceIP = "10.111.222.1",
            sourcePort = 12345,
            destIP = destIP,
            destPort = destPort,
            timestamp = System.currentTimeMillis(),
            isBlocked = false,
            blockedByRule = "",
            proxyDetails = "",
            blocklists = "",
            protocol = protocol,
            query = null,
            connId = "test-conn",
            connType = "",
            rpid = "",
            message = "",
            downloadBytes = 0,
            uploadBytes = 0,
            duration = 0,
            synack = 0
        )
    }

    private fun createParams(
        connInfo: ConnTrackerMetaData = createConnInfo(),
        domains: String? = null,
        anyRealIpBlocked: Boolean = false,
        isSplApp: Boolean = false,
        rinr: Boolean = false,
        isAlg: Boolean = false,
        forUpstreamAnswer: Boolean = false,
        isDeviceLocked: Boolean = false,
        underlyingNetworks: ConnectionMonitor.UnderlyingNetworks? = null,
        isLockdown: Boolean = false,
        isAppPaused: Boolean = false,
        accessibilityServiceFunctional: Boolean = false,
        keyguardManager: KeyguardManager? = null
    ): TunFirewallManager.FirewallParameters {
        return TunFirewallManager.FirewallParameters(
            scope = CoroutineScope(SupervisorJob()),
            connInfo = connInfo,
            domains = domains,
            anyRealIpBlocked = anyRealIpBlocked,
            isSplApp = isSplApp,
            rinr = rinr,
            isAlg = isAlg,
            forUpstreamAnswer = forUpstreamAnswer,
            isDeviceLocked = isDeviceLocked,
            onDeviceLocked = null,
            underlyingNetworks = underlyingNetworks,
            isLockdown = isLockdown,
            isAppPaused = isAppPaused,
            accessibilityServiceFunctional = accessibilityServiceFunctional,
            onAccessibilityFailure = null,
            keyguardManager = keyguardManager,
            connectivityManager = connectivityManager
        )
    }

    @Test
    fun `RULE0 - Allow when rethink uid`() = runTest {
        val connInfo = createConnInfo(uid = rethinkUid)
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE0, result)
    }

    @Test
    fun `Rethink uid should be processed if rinr is true`() = runTest {
        val connInfo = createConnInfo(uid = rethinkUid)
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo, rinr = true))
        assertEquals(FirewallRuleset.RULE0, result)
    }

    @Test
    fun `RULE9B - Allow Orbot`() = runTest {
        val uid = 10999
        val connInfo = createConnInfo(uid = uid)
        TunFirewallManager.setSettingUpOrbot(true)
        coEvery { FirewallManager.getPackageNameByUid(uid) } returns OrbotHelper.ORBOT_PACKAGE_NAME
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE9B, result)
    }

    @Test
    fun `RULE5 - Block unknown app`() = runTest {
        val uid = INVALID_UID
        val connInfo = createConnInfo(uid = uid)
        every { persistentState.getBlockUnknownConnections() } returns true
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE5, result)
    }

    @Test
    fun `RULE5 - Skip unknown app rule if requested`() = runTest {
        val uid = INVALID_UID
        val connInfo = createConnInfo(uid = uid)
        every { persistentState.getBlockUnknownConnections() } returns true
        every { persistentState.splitDns } returns false
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo, forUpstreamAnswer = true))
        assertEquals(FirewallRuleset.RULE0, result)
    }

    @Test
    fun `RULE1B - Block new app`() = runTest {
        val uid = 10555
        val connInfo = createConnInfo(uid = uid)
        coEvery { FirewallManager.hasUid(uid) } returns false
        every { persistentState.getBlockNewlyInstalledApp() } returns true

        // Mocking failure of testWithBackoff by having isUidFirewalled return true
        coEvery { FirewallManager.isUidFirewalled(uid) } returns true
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE1B, result)
        coVerify { rdb.addNewApp(uid) }
    }

    @Test
    fun `New app allowed after database refresh`() = runTest {
        val uid = 10555
        val connInfo = createConnInfo(uid = uid)
        coEvery { FirewallManager.hasUid(uid) } returnsMany listOf(false, true, true)
        coEvery { FirewallManager.isUidFirewalled(uid) } returns false
        every { persistentState.getBlockNewlyInstalledApp() } returns true
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE0, result)
    }

    @Test
    fun `RULE19 - Temp Allow`() = runTest {
        val uid = 10123
        val connInfo = createConnInfo(uid = uid)
        coEvery { FirewallManager.isTempAllowed(uid) } returns true
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE19, result)
    }

    @Test
    fun `RULE1 - App Blocked`() = runTest {
        val uid = 10123
        val connInfo = createConnInfo(uid = uid)
        val connectionStatus = mockk<FirewallManager.ConnectionStatus>()
        every { connectionStatus.blocked() } returns true
        coEvery { FirewallManager.connectionStatus(uid) } returns connectionStatus
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE1, result)
    }

    @Test
    fun `RULE1D - Wifi Blocked for Unmetered`() = runTest {
        val uid = 10123
        val connInfo = createConnInfo(uid = uid)
        val connectionStatus = mockk<FirewallManager.ConnectionStatus>()
        every { connectionStatus.blocked() } returns false
        every { connectionStatus.wifi() } returns true
        every { connectionStatus.mobileData() } returns false
        coEvery { FirewallManager.connectionStatus(uid) } returns connectionStatus
        
        // Mocking unmetered connection
        val underlyingNetworks = mockk<ConnectionMonitor.UnderlyingNetworks>(relaxed = true)
        every { underlyingNetworks.useActive } returns true
        every { underlyingNetworks.isActiveNetworkMetered } returns false
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo, underlyingNetworks = underlyingNetworks))
        assertEquals(FirewallRuleset.RULE1D, result)
    }

    @Test
    fun `RULE1E - Mobile Data Blocked for Metered`() = runTest {
        val uid = 10123
        val connInfo = createConnInfo(uid = uid)
        val connectionStatus = mockk<FirewallManager.ConnectionStatus>()
        every { connectionStatus.blocked() } returns false
        every { connectionStatus.wifi() } returns false
        every { connectionStatus.mobileData() } returns true
        coEvery { FirewallManager.connectionStatus(uid) } returns connectionStatus
        
        val underlyingNetworks = mockk<ConnectionMonitor.UnderlyingNetworks>(relaxed = true)
        every { underlyingNetworks.useActive } returns true
        every { underlyingNetworks.isActiveNetworkMetered } returns true
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo, underlyingNetworks = underlyingNetworks))
        assertEquals(FirewallRuleset.RULE1E, result)
    }

    @Test
    fun `RULE16 - App paused in lockdown`() = runTest {
        val result = TunFirewallManager.firewall(createParams(isLockdown = true, isAppPaused = true))
        assertEquals(FirewallRuleset.RULE16, result)
    }

    @Test
    fun `RULE2E - Domain blocked`() = runTest {
        val uid = 10123
        val domains = "blocked.com"
        every { DomainRulesManager.status("blocked.com", uid) } returns DomainRulesManager.Status.BLOCK
        
        val connInfo = createConnInfo(uid = uid)
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo, domains = domains))
        assertEquals(FirewallRuleset.RULE2E, result)
        assertEquals("blocked.com", connInfo.query)
    }

    @Test
    fun `RULE2F - Domain trusted`() = runTest {
        val uid = 10123
        val domains = "trusted.com"
        every { DomainRulesManager.status("trusted.com", uid) } returns DomainRulesManager.Status.TRUST
        
        val connInfo = createConnInfo(uid = uid)
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo, domains = domains))
        assertEquals(FirewallRuleset.RULE2F, result)
        assertEquals("trusted.com", connInfo.query)
    }

    @Test
    fun `Domain rule evaluation for multiple domains on pre-R`() = runTest {
        every { Utilities.isAtleastR() } returns false
        val uid = 10123
        val domains = "trusted.com,blocked.com"
        every { DomainRulesManager.status("trusted.com", uid) } returns DomainRulesManager.Status.TRUST
        every { DomainRulesManager.status("blocked.com", uid) } returns DomainRulesManager.Status.BLOCK
        
        val result = TunFirewallManager.firewall(createParams(createConnInfo(uid = uid), domains = domains))
        assertEquals(FirewallRuleset.RULE2F, result)
    }

    @Test
    fun `RULE2 - IP blocked`() = runTest {
        val uid = 10123
        val ip = "1.2.3.4"
        every { IpRulesManager.hasRule(uid, ip, any()) } returns IpRulesManager.IpRuleStatus.BLOCK
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid, destIP = ip)))
        assertEquals(FirewallRuleset.RULE2, result)
    }

    @Test
    fun `RULE2B - IP trusted`() = runTest {
        val uid = 10123
        val ip = "1.2.3.4"
        every { IpRulesManager.hasRule(uid, ip, any()) } returns IpRulesManager.IpRuleStatus.TRUST
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid, destIP = ip)))
        assertEquals(FirewallRuleset.RULE2B, result)
    }

    @Test
    fun `IPv4 in IPv6 filtering`() = runTest {
        val uid = 10123
        val ipv6 = "::ffff:1.2.3.4"
        val ipv4 = "1.2.3.4"
        every { persistentState.filterIpv4inIpv6 } returns true
        every { IpRulesManager.hasRule(uid, ipv6, any()) } returns IpRulesManager.IpRuleStatus.NONE
        every { IpRulesManager.hasRule(uid, ipv4, any()) } returns IpRulesManager.IpRuleStatus.BLOCK
        
        val result = TunFirewallManager.firewall(createParams(createConnInfo(uid = uid, destIP = ipv6)))
        assertEquals(FirewallRuleset.RULE2, result)
    }

    @Test
    fun `RULE1H - Bypass DNS Firewall`() = runTest {
        val uid = 10123
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.bypassDnsFirewall() } returns true
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid)))
        assertEquals(FirewallRuleset.RULE1H, result)
    }

    @Test
    fun `RULE1G - Isolate mode`() = runTest {
        val uid = 10123
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.bypassDnsFirewall() } returns false
        every { appStatus.isolate() } returns true
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid)))
        assertEquals(FirewallRuleset.RULE1G, result)
    }

    @Test
    fun `RULE2G - Bypass universal but DNS blocked`() = runTest {
        val uid = 10123
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.bypassUniversal() } returns true
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), domains = "some.com", anyRealIpBlocked = true))
        assertEquals(FirewallRuleset.RULE2G, result)
    }

    @Test
    fun `RULE9 - Bypass universal allow when DNS proxied`() = runTest {
        val uid = 10123
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.bypassUniversal() } returns true
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        val braveMode = mockk<AppConfig.BraveMode>()
        every { braveMode.isDnsFirewallMode() } returns true
        every { appConfig.getBraveMode() } returns braveMode
        every { appConfig.preventDnsLeaks() } returns true
        
        val result = TunFirewallManager.firewall(createParams(createConnInfo(uid = uid, destPort = 53)))
        assertEquals(FirewallRuleset.RULE9, result)
    }

    @Test
    fun `RULE8 - Bypass universal allow`() = runTest {
        val uid = 10123
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.bypassUniversal() } returns true
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), domains = "some.com", anyRealIpBlocked = false))
        assertEquals(FirewallRuleset.RULE8, result)
    }

    @Test
    fun `RULE2H - Global domain blocked`() = runTest {
        val domains = "global-blocked.com"
        every { DomainRulesManager.status("global-blocked.com", UID_EVERYBODY) } returns DomainRulesManager.Status.BLOCK
        
        val result = TunFirewallManager.firewall(createParams(domains = domains))
        assertEquals(FirewallRuleset.RULE2H, result)
    }

    @Test
    fun `RULE2I - Global domain trusted`() = runTest {
        val domains = "global-trusted.com"
        every { DomainRulesManager.status("global-trusted.com", UID_EVERYBODY) } returns DomainRulesManager.Status.TRUST
        
        val result = TunFirewallManager.firewall(createParams(domains = domains))
        assertEquals(FirewallRuleset.RULE2I, result)
    }

    @Test
    fun `RULE2D - Global IP blocked`() = runTest {
        val ip = "8.8.8.8"
        every { IpRulesManager.hasRule(UID_EVERYBODY, ip, any()) } returns IpRulesManager.IpRuleStatus.BLOCK
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(destIP = ip)))
        assertEquals(FirewallRuleset.RULE2D, result)
    }

    @Test
    fun `RULE2C - Global IP bypass universal`() = runTest {
        val ip = "8.8.8.8"
        every { IpRulesManager.hasRule(UID_EVERYBODY, ip, any()) } returns IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
        
        val result = TunFirewallManager.firewall(createParams(createConnInfo(destIP = ip)))
        assertEquals(FirewallRuleset.RULE2C, result)
    }

    @Test
    fun `RULE0 - Special App bypasses universal rules`() = runTest {
        every { persistentState.getBlockMeteredConnections() } returns true
        val underlyingNetworks = mockk<ConnectionMonitor.UnderlyingNetworks>(relaxed = true)
        every { underlyingNetworks.useActive } returns true
        every { underlyingNetworks.isActiveNetworkMetered } returns true
        
        val result = TunFirewallManager.firewall(createParams(isSplApp = true, underlyingNetworks = underlyingNetworks))
        assertEquals(FirewallRuleset.RULE0, result)
    }

    @Test
    fun `RULE1F - Metered blocked universally`() = runTest {
        every { persistentState.getBlockMeteredConnections() } returns true
        val underlyingNetworks = mockk<ConnectionMonitor.UnderlyingNetworks>(relaxed = true)
        every { underlyingNetworks.useActive } returns true
        every { underlyingNetworks.isActiveNetworkMetered } returns true
        
        val result = TunFirewallManager.firewall(createParams(underlyingNetworks = underlyingNetworks))
        assertEquals(FirewallRuleset.RULE1F, result)
    }

    @Test
    fun `RULE11 - Universal Lockdown`() = runTest {
        every { persistentState.getUniversalLockdown() } returns true
        val result = TunFirewallManager.firewall(createParams())
        assertEquals(FirewallRuleset.RULE11, result)
    }

    @Test
    fun `RULE10 - HTTP Blocked`() = runTest {
        every { persistentState.getBlockHttpConnections() } returns true
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(destPort = 80)))
        assertEquals(FirewallRuleset.RULE10, result)
    }

    @Test
    fun `RULE3 - Device Locked`() = runTest {
        val onDeviceLocked = mockk<() -> Unit>(relaxed = true)
        val params = createParams(isDeviceLocked = true).copy(onDeviceLocked = onDeviceLocked)
        val result = TunFirewallManager.firewall(params)
        assertEquals(FirewallRuleset.RULE3, result)
        verify { onDeviceLocked() }
    }

    @Test
    fun `RULE6 - UDP Blocked`() = runTest {
        every { persistentState.getUdpBlocked() } returns true
        val connInfo = createConnInfo(protocol = Protocol.UDP.protocolType)
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE6, result)
    }

    @Test
    fun `RULE6 - NTP from System App not blocked`() = runTest {
        every { persistentState.getUdpBlocked() } returns true
        val uid = 1000
        val connInfo = createConnInfo(uid = uid, protocol = Protocol.UDP.protocolType, destPort = 123)
        coEvery { FirewallManager.isUidSystemApp(uid) } returns true
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE0, result)
    }

    @Test
    fun `RULE4 - Background data blocked`() = runTest {
        val uid = 10123
        every { persistentState.getBlockAppWhenBackground() } returns true
        every { FirewallManager.isAppForeground(uid, any()) } returns false
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), accessibilityServiceFunctional = true))
        assertEquals(FirewallRuleset.RULE4, result)
    }

    @Test
    fun `RULE4 - Background data not blocked if app is foreground`() = runTest {
        val uid = 10123
        every { persistentState.getBlockAppWhenBackground() } returns true
        every { FirewallManager.isAppForeground(uid, any()) } returns true
        
        val result = TunFirewallManager.firewall(createParams(createConnInfo(uid = uid), accessibilityServiceFunctional = true))
        assertEquals(FirewallRuleset.RULE0, result)
    }

    @Test
    fun `RULE7 - DNS Bypassed`() = runTest {
        every { persistentState.getDisallowDnsBypass() } returns true
        val connInfo = createConnInfo()
        connInfo.query = null // simulating unresolved by user DNS
        
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE7, result)
    }

    @Test
    fun `Isolate Mode - App-Specific TRUSTED domain takes precedence`() = runTest {
        val uid = 10123
        val domains = "trusted.com"
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isolate() } returns true
        every { appStatus.bypassDnsFirewall() } returns false
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        every { DomainRulesManager.status("trusted.com", uid) } returns DomainRulesManager.Status.TRUST
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), domains = domains))
        assertEquals(FirewallRuleset.RULE2F, result)
    }

    @Test
    fun `Isolate Mode - App-Specific BLOCKED domain takes precedence`() = runTest {
        val uid = 10123
        val domains = "blocked.com"
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isolate() } returns true
        every { appStatus.bypassDnsFirewall() } returns false
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        every { DomainRulesManager.status("blocked.com", uid) } returns DomainRulesManager.Status.BLOCK
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), domains = domains))
        assertEquals(FirewallRuleset.RULE2E, result)
    }

    @Test
    fun `Isolate Mode - Global TRUSTED domain is IGNORED`() = runTest {
        val uid = 10123
        val domains = "global-trusted.com"
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isolate() } returns true
        every { appStatus.bypassDnsFirewall() } returns false
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        every { DomainRulesManager.status("global-trusted.com", uid) } returns DomainRulesManager.Status.NONE
        every { DomainRulesManager.status("global-trusted.com", UID_EVERYBODY) } returns DomainRulesManager.Status.TRUST
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), domains = domains))
        assertEquals(FirewallRuleset.RULE1G, result)
    }

    @Test
    fun `Isolate Mode - No rules results in RULE1G`() = runTest {
        val uid = 10123
        val domains = "any.com"
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.isolate() } returns true
        every { appStatus.bypassDnsFirewall() } returns false
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        every { DomainRulesManager.status(any(), any()) } returns DomainRulesManager.Status.NONE
        every { IpRulesManager.hasRule(any(), any(), any()) } returns IpRulesManager.IpRuleStatus.NONE
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), domains = domains))
        assertEquals(FirewallRuleset.RULE1G, result)
    }

    @Test
    fun `Bypass DNS Firewall - App-Specific IP TRUSTED takes precedence`() = runTest {
        val uid = 10123
        val ip = "1.2.3.4"
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.bypassDnsFirewall() } returns true
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        every { IpRulesManager.hasRule(uid, ip, any()) } returns IpRulesManager.IpRuleStatus.TRUST
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid, destIP = ip)))
        assertEquals(FirewallRuleset.RULE2B, result)
    }

    @Test
    fun `Bypass Universal - Global TRUSTED domain overrides DNS block`() = runTest {
        val uid = 10123
        val domains = "trusted.com"
        val appStatus = mockk<FirewallManager.FirewallStatus>()
        every { appStatus.bypassUniversal() } returns true
        coEvery { FirewallManager.appStatus(uid) } returns appStatus
        
        every { DomainRulesManager.status(domains, UID_EVERYBODY) } returns DomainRulesManager.Status.TRUST
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid), domains = domains, anyRealIpBlocked = true))
        assertEquals(FirewallRuleset.RULE8, result)
    }

    @Test
    fun `forUpstreamAnswer = true skips HTTP and UDP checks`() = runTest {
        every { persistentState.getBlockHttpConnections() } returns true
        every { persistentState.getUdpBlocked() } returns true
        
        val resultHttp = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(destPort = 80), forUpstreamAnswer = true))
        assertEquals(FirewallRuleset.RULE0, resultHttp)
        
        val resultUdp = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(protocol = Protocol.UDP.protocolType), forUpstreamAnswer = true))
        assertEquals(FirewallRuleset.RULE0, resultUdp)
    }

    @Test
    fun `isAlg = true should check multiple IPs`() = runTest {
        val uid = 10123
        val ips = "1.1.1.1,2.2.2.2"
        every { IpRulesManager.hasRule(uid, "1.1.1.1", any()) } returns IpRulesManager.IpRuleStatus.NONE
        every { IpRulesManager.hasRule(uid, "2.2.2.2", any()) } returns IpRulesManager.IpRuleStatus.BLOCK
        
        val result = TunFirewallManager.firewall(createParams(connInfo = createConnInfo(uid = uid, destIP = ips), isAlg = true))
        assertEquals(FirewallRuleset.RULE2, result)
    }

    @Test
    fun `isALG=true mutation test for domains`() = runTest {
        val uid = 10123
        val domains = "none.com,blocked.com"
        every { DomainRulesManager.status("none.com", uid) } returns DomainRulesManager.Status.NONE
        every { DomainRulesManager.status("blocked.com", uid) } returns DomainRulesManager.Status.BLOCK
        
        val connInfo = createConnInfo(uid = uid)
        TunFirewallManager.firewall(createParams(connInfo = connInfo, domains = domains, isAlg = true))
        assertEquals("blocked.com", connInfo.query)
    }

    @Test
    fun `onAccessibilityFailure callback should be triggered`() = runTest {
        val uid = 10123
        every { persistentState.getBlockAppWhenBackground() } returns true
        val onAccessibilityFailure = mockk<() -> Unit>(relaxed = true)
        val params = createParams(connInfo = createConnInfo(uid = uid), accessibilityServiceFunctional = false).copy(onAccessibilityFailure = onAccessibilityFailure)
        
        TunFirewallManager.firewall(params)
        verify { onAccessibilityFailure() }
    }

    @Test
    fun `RULE1C - Catch generic exception in firewall`() = runTest {
        coEvery { FirewallManager.isTempAllowed(any()) } throws RuntimeException("Unexpected error")
        val result = TunFirewallManager.firewall(createParams())
        assertEquals(FirewallRuleset.RULE1C, result)
    }

    @Test
    fun `Metered connection check fallbacks to active network when underlyingNetworks is null`() = runTest {
        val connInfo = createConnInfo(destIP = "1.1.1.1")
        every { connectivityManager.isActiveNetworkMetered } returns true
        every { persistentState.getBlockMeteredConnections() } returns true
        val result = TunFirewallManager.firewall(createParams(connInfo = connInfo))
        assertEquals(FirewallRuleset.RULE1F, result)
    }
}
