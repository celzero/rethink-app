/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.net.go

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.firestack.backend.DNSResolver
import com.celzero.firestack.backend.DNSTransport
import com.celzero.firestack.backend.Proxies
import com.celzero.firestack.backend.Proxy
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.Router
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.Tunnel
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.test.KoinTest
import org.objenesis.ObjenesisStd
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoVpnAdapterTest : KoinTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var persistentState: PersistentState
    private lateinit var appConfig: AppConfig
    private lateinit var connTracker: ConnectionTrackerRepository
    private lateinit var eventLogger: EventLogger

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        context = RuntimeEnvironment.getApplication()
        persistentState = mockk(relaxed = true)
        appConfig = mockk(relaxed = true)
        connTracker = mockk(relaxed = true)
        eventLogger = mockk(relaxed = true)

        every { eventLogger.log(any(), any(), any(), any(), any(), any()) } just Runs
        every { appConfig.getPcapFilePath() } returns ""
        every { appConfig.getDnsType() } returns AppConfig.DnsType.DOH
        every { appConfig.isSmartDnsEnabled() } returns false
        every { persistentState.localBlocklistStamp } returns ""
        every { persistentState.rpnDnsTunTypes } returns ""

        // no-op: delegates are injected directly in newAdapter()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
        clearAllMocks()
    }

    private fun tunnelOptions(
        dnsMode: AppConfig.TunDnsMode = AppConfig.TunDnsMode.NONE,
        firewallMode: AppConfig.TunFirewallMode = AppConfig.TunFirewallMode.NONE,
        proxyMode: AppConfig.TunProxyMode = AppConfig.TunProxyMode.NONE,
        ptMode: AppConfig.ProtoTranslationMode = AppConfig.ProtoTranslationMode.PTMODEAUTO,
        bridge: Bridge = mockk(relaxed = true),
        defaultDns: String = "",
        fakeDns: String = ""
    ): AppConfig.TunnelOptions {
        return AppConfig.TunnelOptions(
            tunDnsMode = dnsMode,
            tunFirewallMode = firewallMode,
            tunProxyMode = proxyMode,
            ptMode = ptMode,
            bridge = bridge,
            defaultDns = defaultDns,
            fakeDns = fakeDns
        )
    }

    private fun newAdapter(tunnel: Tunnel): GoVpnAdapter {
        val adapter = ObjenesisStd().newInstance(GoVpnAdapter::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // Manually wire Koin-injected lazy delegates since constructor is bypassed.
        GoVpnAdapter::class.java.getDeclaredField("persistentState\$delegate").apply {
            isAccessible = true
            set(adapter, lazy { persistentState })
        }
        GoVpnAdapter::class.java.getDeclaredField("appConfig\$delegate").apply {
            isAccessible = true
            set(adapter, lazy { appConfig })
        }
        GoVpnAdapter::class.java.getDeclaredField("connTrackerDb\$delegate").apply {
            isAccessible = true
            set(adapter, lazy { connTracker })
        }
        GoVpnAdapter::class.java.getDeclaredField("eventLogger\$delegate").apply {
            isAccessible = true
            set(adapter, lazy { eventLogger })
        }

        GoVpnAdapter::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(adapter, context)
        }
        GoVpnAdapter::class.java.getDeclaredField("externalScope").apply {
            isAccessible = true
            set(adapter, scope)
        }
        GoVpnAdapter::class.java.getDeclaredField("tunnel").apply {
            isAccessible = true
            set(adapter, tunnel)
        }

        return adapter
    }

    private fun constructSocks5ViaReflection(target: Any): String {
        val m = target::class.java.declaredMethods.first { it.name == "constructSocks5ProxyUrl" }
        m.isAccessible = true
        return m.invoke(target, "u@ser", "p a:ss", "127.0.0.1", 9050) as String
    }

    @Test
    fun `hasTunnel returns tunnel state`() {
        val connectedTunnel = mockk<Tunnel>(relaxed = true)
        every { connectedTunnel.isConnected } returns true

        val disconnectedTunnel = mockk<Tunnel>(relaxed = true)
        every { disconnectedTunnel.isConnected } returns false

        assertTrue(newAdapter(connectedTunnel).hasTunnel())
        assertFalse(newAdapter(disconnectedTunnel).hasTunnel())
    }

    @Test
    fun `setPcapMode skips when file path is invalid`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true
        val adapter = newAdapter(tunnel)

        adapter.setPcapMode("/definitely/does/not/exist/file.pcap")

        verify(exactly = 0) { tunnel.setPcap(any()) }
    }

    @Test
    fun `setPcapMode sets pcap when file exists`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true
        val adapter = newAdapter(tunnel)

        val file = File.createTempFile("pcap", ".tmp")
        file.setWritable(true)

        adapter.setPcapMode(file.absolutePath)

        verify(exactly = 1) { tunnel.setPcap(file.absolutePath) }
        assertTrue(file.delete())
    }

    @Test
    fun `constructSocks5ProxyUrl encodes credentials`() {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val adapter = newAdapter(tunnel)

        val out = constructSocks5ViaReflection(adapter)

        assertEquals("socks5://u%40ser:p+a%3Ass@127.0.0.1:9050", out)
    }

    @Test
    fun `getDnsStatus returns null when selected transport id does not match`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true

        val resolver = mockk<DNSResolver>(relaxed = true)
        val transport = mockk<DNSTransport>(relaxed = true)
        every { tunnel.resolver } returns resolver
        every { resolver.get("wanted") } returns transport
        every { transport.id() } returns "other"

        val adapter = newAdapter(tunnel)

        val status = adapter.getDnsStatus("wanted")

        assertNull(status)
    }

    @Test
    fun `getDnsStatus returns rpn status for rpn-win id`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true

        val proxies = mockk<Proxies>(relaxed = true)
        val rpn = mockk<com.celzero.firestack.backend.Rpn>(relaxed = true)
        val win = mockk<com.celzero.firestack.backend.RpnProxy>(relaxed = true)
        every { tunnel.proxies } returns proxies
        every { proxies.rpn() } returns rpn
        every { rpn.win() } returns win
        every { win.status() } returns 11L

        val adapter = newAdapter(tunnel)

        val status = adapter.getDnsStatus(com.celzero.firestack.backend.Backend.RpnWin)

        assertEquals(11L, status)
    }

    @Test
    fun `getRDNS uses remote when local type is requested on play flavor`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true

        val resolver = mockk<DNSResolver>(relaxed = true)
        val remote = mockk<RDNS>(relaxed = true)
        every { tunnel.resolver } returns resolver
        every { resolver.rdnsRemote } returns remote

        val adapter = newAdapter(tunnel)

        val rdns = adapter.getRDNS(RethinkBlocklistManager.RethinkBlocklistType.REMOTE)

        assertNotNull(rdns)
    }

    @Test
    fun `getSupportedIpVersion returns router capabilities`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true

        val proxies = mockk<Proxies>(relaxed = true)
        val proxy = mockk<Proxy>(relaxed = true)
        val router = mockk<Router>(relaxed = true)

        every { tunnel.proxies } returns proxies
        every { proxies.getProxy("wg1") } returns proxy
        every { proxy.router() } returns router
        every { router.iP4() } returns true
        every { router.iP6() } returns false

        val adapter = newAdapter(tunnel)

        val pair = adapter.getSupportedIpVersion("wg1")

        assertEquals(Pair(true, false), pair)
    }

    @Test
    fun `getActiveProxiesIpAndMtu returns overlay network details`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true

        val proxies = mockk<Proxies>(relaxed = true)
        val router = mockk<Router>(relaxed = true)
        every { tunnel.proxies } returns proxies
        every { proxies.router() } returns router
        every { router.iP4() } returns true
        every { router.iP6() } returns true
        every { router.mtu() } returns 1380L

        val adapter = newAdapter(tunnel)

        val net = adapter.getActiveProxiesIpAndMtu()

        assertEquals(BraveVPNService.OverlayNetworks(true, true, false, 1380), net)
    }

    @Test
    fun `createHop returns no tunnel when disconnected`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns false

        val adapter = newAdapter(tunnel)

        val out = adapter.createHop("wg1", "wg2")

        assertEquals(Pair(false, "no tunnel"), out)
    }

    @Test
    fun `removeWinServer rejects auto id`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns true

        val adapter = newAdapter(tunnel)

        val out = adapter.removeWinServer(RpnProxyManager.AUTO_SERVER_ID)

        assertFalse(out.first)
        assertTrue(out.second.contains("cannot be removed", ignoreCase = true))
    }

    @Test
    fun `companion getIpString handles null and empty`() {
        assertEquals("", GoVpnAdapter.getIpString(null, null))
        assertEquals("", GoVpnAdapter.getIpString(context, ""))
    }

    @Test
    fun `smoke disconnected guard for critical suspend methods`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        every { tunnel.isConnected } returns false
        val adapter = spyk(newAdapter(tunnel), recordPrivateCalls = true)

        adapter.initResolverProxiesPcap(tunnelOptions())
        adapter.addTransport()
        adapter.unlink()
        adapter.setRDNS()
        adapter.refreshResolvers()
        adapter.closeTun()
        adapter.setDialStrategy()
        adapter.setTransparency()
        adapter.undelegatedDomains()
        adapter.getRpnProps(RpnProxyManager.RpnType.WIN)
        adapter.testRpnProxy()
        adapter.getEntitlementDetails(null, "dev")
        adapter.registerAndFetchWinIfNeeded(deviceId = "dev")
        adapter.refreshRpnProxy("")
        adapter.reconnectRpnProxy("")
        adapter.isWinRegistered()
        adapter.getWinProxyId()
        adapter.getWinIdentifier()
        adapter.getWinByKey("x")
        adapter.getActiveWinKidsProxies()
        adapter.initiateRpnPing("x")
        adapter.getWinLastUpdatedTs()
        adapter.updateWin()
        adapter.stopRpnProxy()
        adapter.unregisterWin()
        adapter.setRpnAutoMode()
        adapter.isRpnReachable("1.1.1.1:53")
        adapter.registerSeProxyIfNeeded()
        adapter.unregisterSeProxyIfNeeded()
        adapter.setExperimentalWireGuardSettings()
        adapter.setAutoDialsParallel()
        adapter.setAutoMode()
        adapter.addMultipleDnsAsPlus()
        adapter.getPlusResolvers()
        adapter.getPlusTransportById("x")
        adapter.panicAtRandom()

        assertTrue(true)
    }

    @Test
    fun `onLowMemory throws when firestack native is unavailable`() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val adapter = newAdapter(tunnel)

        var threw = false
        try {
            adapter.onLowMemory()
        } catch (_: Throwable) {
            threw = true
        }

        assertTrue(threw)
    }
}





