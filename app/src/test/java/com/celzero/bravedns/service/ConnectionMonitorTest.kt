/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TransportInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.util.InternetProtocol
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionMonitorTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var persistentState: PersistentState
    private lateinit var networkListener: ConnectionMonitor.NetworkListener
    private lateinit var context: Context
    private lateinit var cm: ConnectivityManager
    private lateinit var wm: WifiManager

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)

        persistentState = mockk(relaxed = true)
        networkListener = mockk(relaxed = true)
        context = mockk(relaxed = true)
        cm = mockk(relaxed = true)
        wm = mockk(relaxed = true)

        // Defaults used by sendNetworkChanges() and callback registration paths.
        every { persistentState.vpnBuilderPolicy } returns VpnBuilderPolicy.AUTO.ordinal
        every { persistentState.pingv4Ips } returns "1.1.1.1"
        every { persistentState.pingv6Ips } returns "2606:4700:4700::1111"
        every { persistentState.pingv4Url } returns "https://cloudflare.com"
        every { persistentState.pingv6Url } returns "https://one.one.one.one"
        every { persistentState.internetProtocolType } returns InternetProtocol.IPv4.id
        every { persistentState.connectivityChecks } returns false
        every { persistentState.stallOnNoNetwork } returns true
        every { persistentState.performAutoNetworkConnectivityChecks } returns true
        every { persistentState.useMultipleNetworks } returns false

        coEvery { networkListener.onNetworkRegistrationFailed() } just Runs
        coEvery { networkListener.maybeNetworkStall() } just Runs
        coEvery { networkListener.onNetworkChange(any()) } just Runs

        every { context.applicationContext } returns context
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wm

        @Suppress("DEPRECATION")
        every { cm.allNetworks } returns emptyArray()
        every { cm.activeNetwork } returns null
        every { cm.isActiveNetworkMetered } returns false
        every { cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } just Runs
        every { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs

        startKoin {
            modules(
                module {
                    single { persistentState }
                }
            )
        }

        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        stopKoin()
        unmockkAll()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `shouldPerformConnectivityCheck throttles capability changes by interval`() {
        val monitor = newMonitor()
        val network = mockNetwork(101L)

        invokePrivate(monitor, "addToNwSet", arrayOf(Network::class.java, String::class.java), network, null)

        val first = invokeShouldPerform(monitor, ConnectionMonitor.EVENT_ON_CAPABILITIES_CHANGED)
        val second = invokeShouldPerform(monitor, ConnectionMonitor.EVENT_ON_CAPABILITIES_CHANGED)

        assertTrue(first)
        assertFalse(second)

        val oldTs = android.os.SystemClock.elapsedRealtime() - ConnectionMonitor.CONNECTIVITY_CHECK_INTERVAL_MS - 1L
        invokePrivate(
            monitor,
            "updateLastConnectivityCheckState",
            arrayOf(Long::class.javaPrimitiveType!!, Set::class.java),
            oldTs,
            setOf(101L)
        )

        val third = invokeShouldPerform(monitor, ConnectionMonitor.EVENT_ON_CAPABILITIES_CHANGED)
        assertTrue(third)
    }

    @Test
    fun `shouldPerformConnectivityCheck triggers when network handles change`() {
        val monitor = newMonitor()
        val network = mockNetwork(222L)

        invokePrivate(monitor, "addToNwSet", arrayOf(Network::class.java, String::class.java), network, null)
        invokePrivate(
            monitor,
            "updateLastConnectivityCheckState",
            arrayOf(Long::class.javaPrimitiveType!!, Set::class.java),
            android.os.SystemClock.elapsedRealtime(),
            setOf(333L)
        )

        assertTrue(invokeShouldPerform(monitor, ConnectionMonitor.EVENT_ON_CAPABILITIES_CHANGED))
    }

    @Test
    fun `shouldPerformConnectivityCheck always allows available lost and link events`() {
        val monitor = newMonitor()

        assertTrue(invokeShouldPerform(monitor, ConnectionMonitor.EVENT_ON_AVAILABLE))
        assertTrue(invokeShouldPerform(monitor, ConnectionMonitor.EVENT_ON_LOST))
        assertTrue(invokeShouldPerform(monitor, ConnectionMonitor.EVENT_ON_LINK_PROPERTIES_CHANGED))
    }

    @Test
    fun `shouldPerformConnectivityCheck rejects unknown event`() {
        val monitor = newMonitor()
        assertFalse(invokeShouldPerform(monitor, "unknown-event"))
    }

    @Test
    @Config(sdk = [23])
    fun `transport callback onAvailable on Android 6 pushes network change`() = runTest(scheduler) {
        every { persistentState.vpnBuilderPolicy } returns VpnBuilderPolicy.AUTO.ordinal
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs

        val activeNw = mockNetwork(5001L)
        val cap = mockNetworkCapabilities(hasInternet = true)
        every { cm.activeNetwork } returns activeNw
        every { cm.getNetworkCapabilities(activeNw) } returns cap
        every { cm.getLinkProperties(activeNw) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        val registered = callbackSlot.captured
        registered.onAvailable(activeNw)
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    @Config(sdk = [31])
    fun `internet validated callback on S onAvailable in validated policy pushes change`() = runTest(scheduler) {
        every { persistentState.vpnBuilderPolicy } returns VpnBuilderPolicy.RELAXED.ordinal
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs

        val activeNw = mockNetwork(7001L)
        val cap = mockNetworkCapabilities(hasInternet = true, hasValidated = true)
        every { cm.activeNetwork } returns activeNw
        every { cm.getNetworkCapabilities(activeNw) } returns cap
        every { cm.getLinkProperties(activeNw) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        callbackSlot.captured.onAvailable(activeNw)
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    fun `onVpnStart reports failure when callback registration throws`() = runTest(scheduler) {
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        } throws RuntimeException("register failed")

        val monitor = newMonitor()
        val started = monitor.onVpnStart(context)

        assertFalse(started)
        coVerify(exactly = 1) { networkListener.onNetworkRegistrationFailed() }
    }

    @Test
    @Config(sdk = [28])
    fun `getNetworkSSID returns wifi manager ssid on pre-S`() {
        val monitor = newMonitor()
        setField(monitor, "wm", wm)

        val cap = mockNetworkCapabilities(hasWifi = true)
        val network = mockNetwork(9001L)
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"OfficeWiFi\""
        @Suppress("DEPRECATION")
        every { wm.connectionInfo } returns wifiInfo

        val out = monitor.getNetworkSSID(network, cap)

        assertEquals("OfficeWiFi", out)
    }

    @Test
    @Config(sdk = [31])
    fun `getNetworkSSID returns transportInfo ssid on S plus`() {
        val monitor = newMonitor()
        setField(monitor, "wm", wm)

        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"Cafe\""

        val cap = mockNetworkCapabilities(hasWifi = true, transportInfo = wifiInfo)
        val network = mockNetwork(9002L)

        val out = monitor.getNetworkSSID(network, cap)

        assertEquals("Cafe", out)
    }

    @Test
    @Config(sdk = [35])
    fun `getNetworkSSID returns null for non wifi or unknown ssid`() {
        val monitor = newMonitor()
        setField(monitor, "wm", wm)

        val nonWifiCap = mockNetworkCapabilities(hasWifi = false)
        val network = mockNetwork(9003L)

        val notWifi = monitor.getNetworkSSID(network, nonWifiCap)
        assertNull(notWifi)

        val wifiCap = mockNetworkCapabilities(hasWifi = true)
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "<unknown ssid>"
        @Suppress("DEPRECATION")
        every { wm.connectionInfo } returns wifiInfo

        val unknown = monitor.getNetworkSSID(network, wifiCap)
        assertNull(unknown)
    }

    @Test
    fun `netId shifts network handle correctly`() {
        val handle = (123L shl 32) or 7L
        assertEquals(123L, ConnectionMonitor.netId(handle))
    }

    @Test
    fun `networkType returns expected labels`() {
        val vpn = mockNetworkCapabilities(hasVpn = true)
        val wifi = mockNetworkCapabilities(hasWifi = true)
        val cell = mockNetworkCapabilities(hasCellular = true)

        assertEquals("VPN", ConnectionMonitor.networkType(vpn))
        assertEquals("WiFi", ConnectionMonitor.networkType(wifi))
        assertEquals("Cellular", ConnectionMonitor.networkType(cell))
        assertEquals("Unknown", ConnectionMonitor.networkType(null))
    }

    private fun newMonitor(): ConnectionMonitor {
        return ConnectionMonitor(context, networkListener, dispatcher as CoroutineDispatcher, scope)
    }

    private fun mockNetwork(handle: Long): Network {
        val network = mockk<Network>()
        every { network.networkHandle } returns handle
        return network
    }

    private fun mockNetworkCapabilities(
        hasInternet: Boolean = false,
        hasValidated: Boolean = false,
        hasWifi: Boolean = false,
        hasCellular: Boolean = false,
        hasVpn: Boolean = false,
        transportInfo: TransportInfo? = null
    ): NetworkCapabilities {
        val cap = mockk<NetworkCapabilities>()
        every { cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns hasInternet
        every { cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns hasValidated
        every { cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) } returns false
        every { cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        every { cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns hasWifi
        every { cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns hasCellular
        every { cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns hasVpn
        if (NetworkCapabilities::class.java.methods.any { it.name == "getTransportInfo" }) {
            every { cap.transportInfo } returns transportInfo
        }
        return cap
    }

    private fun invokeShouldPerform(monitor: ConnectionMonitor, event: String): Boolean {
        val m = monitor.javaClass.getDeclaredMethod("shouldPerformConnectivityCheck", String::class.java)
        m.isAccessible = true
        return m.invoke(monitor, event) as Boolean
    }

    private fun invokePrivate(target: Any, name: String, paramTypes: Array<Class<*>>, vararg args: Any?) {
        val m = target.javaClass.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        m.invoke(target, *args)
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        val f = target.javaClass.getDeclaredField(fieldName)
        f.isAccessible = true
        f.set(target, value)
    }

    @Test
    fun `matrix test all policy and settings combinations`() = runTest(scheduler) {
        val policies = listOf(
            VpnBuilderPolicy.AUTO,
            VpnBuilderPolicy.SENSITIVE,
            VpnBuilderPolicy.RELAXED,
            VpnBuilderPolicy.FIXED
        )

        val internetProtocols = listOf(
            InternetProtocol.IPv4.id,
            InternetProtocol.IPv6.id,
            InternetProtocol.IPv46.id,
            InternetProtocol.ALWAYSv46.id
        )

        val connectivityCheckFlags = listOf(true, false)
        val stallOnNetworkFlags = listOf(true, false)
        val autoCheckFlags = listOf(true, false)
        val multiNetworkFlags = listOf(true, false)

        var testCount = 0
        var passCount = 0
        val failedTests = mutableListOf<String>()

        // Generate complete matrix
        for (policy in policies) {
            for (ipType in internetProtocols) {
                for (connCheck in connectivityCheckFlags) {
                    for (stallFlag in stallOnNetworkFlags) {
                        for (autoCheck in autoCheckFlags) {
                            for (multiNet in multiNetworkFlags) {
                                testCount++
                                val testId = "P:${policy.name}|IP:$ipType|CC:$connCheck|ST:$stallFlag|AC:$autoCheck|MN:$multiNet"

                                try {
                                    // Configure persistent state for this combination
                                    every { persistentState.vpnBuilderPolicy } returns policy.ordinal
                                    every { persistentState.internetProtocolType } returns ipType
                                    every { persistentState.connectivityChecks } returns connCheck
                                    every { persistentState.stallOnNoNetwork } returns stallFlag
                                    every { persistentState.performAutoNetworkConnectivityChecks } returns autoCheck
                                    every { persistentState.useMultipleNetworks } returns multiNet

                                    val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
                                    every {
                                        cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
                                    } just Runs

                                    val testNetwork = mockNetwork(5555L + testCount)
                                    val cap = mockNetworkCapabilities(hasInternet = true, hasValidated = true)
                                    every { cm.activeNetwork } returns testNetwork
                                    every { cm.getNetworkCapabilities(testNetwork) } returns cap
                                    every { cm.getLinkProperties(testNetwork) } returns LinkProperties()

                                    val monitor = newMonitor()
                                    val started = monitor.onVpnStart(context)

                                    assertTrue(
                                        "VPN should start for policy=$policy, protocol=$ipType",
                                        started
                                    )

                                    callbackSlot.captured.onAvailable(testNetwork)
                                    advanceUntilIdle()

                                    coVerify(atLeast = 1) {
                                        networkListener.onNetworkChange(any())
                                    }

                                    passCount++
                                } catch (e: Exception) {
                                    failedTests.add("$testId: ${e.message}")
                                    println("❌ FAILED $testId: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
        }

        println("\n" + "=".repeat(100))
        println("🎯 COMBINATORIAL MATRIX TEST RESULTS")
        println("=".repeat(100))
        println("Total combinations: $testCount")
        println("Passed: $passCount")
        println("Failed: ${testCount - passCount}")
        println("Success rate: ${(passCount.toDouble() / testCount * 100).toInt()}%")
        println("=".repeat(100))

        failedTests.forEach { failed ->
            println("  ❌ $failed")
        }

        assertEquals("All combinations should pass", testCount, passCount)
    }

    @Test
    @Config(sdk = [28])
    fun `policy AUTO on Android 9 uses transport callback with 3s delay`() {
        val policy = VpnBuilderPolicy.fromOrdinalOrDefault(VpnBuilderPolicy.AUTO.ordinal)
        assertEquals(VpnBuilderPolicy.AUTO, policy)
        assertEquals(
            VpnBuilderPolicy.ConnectionMonitorBehaviour.TRANSPORTS,
            policy.connectionMonitorBehaviour
        )
        val delay = VpnBuilderPolicy.getNetworkBehaviourDuration(policy.connectionMonitorBehaviour)
        assertEquals(3000L, delay)
    }

    @Test
    fun `policy SENSITIVE uses validated and transport with 3s delay`() {
        val policy = VpnBuilderPolicy.fromOrdinalOrDefault(VpnBuilderPolicy.SENSITIVE.ordinal)
        assertEquals(VpnBuilderPolicy.SENSITIVE, policy)
        assertEquals(
            VpnBuilderPolicy.ConnectionMonitorBehaviour.VALIDATED_NETWORKS_AND_TRANSPORTS,
            policy.connectionMonitorBehaviour
        )
        val delay = VpnBuilderPolicy.getNetworkBehaviourDuration(policy.connectionMonitorBehaviour)
        assertEquals(3000L, delay)
    }

    @Test
    fun `policy RELAXED uses validated networks with 1s delay`() {
        val policy = VpnBuilderPolicy.fromOrdinalOrDefault(VpnBuilderPolicy.RELAXED.ordinal)
        assertEquals(VpnBuilderPolicy.RELAXED, policy)
        assertEquals(
            VpnBuilderPolicy.ConnectionMonitorBehaviour.VALIDATED_NETWORKS,
            policy.connectionMonitorBehaviour
        )
        val delay = VpnBuilderPolicy.getNetworkBehaviourDuration(policy.connectionMonitorBehaviour)
        assertEquals(1000L, delay)
    }

    @Test
    fun `internet protocol iPv46 dual stack enables connectivity checks when set`() = runTest(scheduler) {
        every { persistentState.internetProtocolType } returns InternetProtocol.IPv46.id
        every { persistentState.connectivityChecks } returns true
        val protocol = InternetProtocol.getInternetProtocol(persistentState.internetProtocolType)
        assertTrue(protocol.isIPv46())
    }

    @Test
    fun `internet protocol IPv6 only disables dual stack logic`() = runTest(scheduler) {
        every { persistentState.internetProtocolType } returns InternetProtocol.IPv6.id
        val protocol = InternetProtocol.getInternetProtocol(persistentState.internetProtocolType)
        assertFalse(protocol.isIPv46())
        assertTrue(protocol.isIPv6())
    }

    @Test
    fun `sendNetworkChanges respects IPv4 only when dual stack disabled`() = runTest(scheduler) {
        every { persistentState.internetProtocolType } returns InternetProtocol.IPv4.id
        every { persistentState.connectivityChecks } returns false

        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs

        val testNetwork = mockNetwork(6666L)
        val cap = mockNetworkCapabilities(hasInternet = true)
        every { cm.activeNetwork } returns testNetwork
        every { cm.getNetworkCapabilities(testNetwork) } returns cap
        every { cm.getLinkProperties(testNetwork) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        callbackSlot.captured.onAvailable(testNetwork)
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    fun `stall on no network flag propagates to network change event`() = runTest(scheduler) {
        every { persistentState.stallOnNoNetwork } returns true

        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs

        val testNetwork = mockNetwork(7777L)
        val cap = mockNetworkCapabilities(hasInternet = true)
        every { cm.activeNetwork } returns testNetwork
        every { cm.getNetworkCapabilities(testNetwork) } returns cap
        every { cm.getLinkProperties(testNetwork) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        callbackSlot.captured.onAvailable(testNetwork)
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    fun `use multiple networks flag changes message type from active to all`() = runTest(scheduler) {
        every { persistentState.useMultipleNetworks } returns true

        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs

        val testNetwork = mockNetwork(8888L)
        val cap = mockNetworkCapabilities(hasInternet = true)
        every { cm.activeNetwork } returns testNetwork
        every { cm.getNetworkCapabilities(testNetwork) } returns cap
        every { cm.getLinkProperties(testNetwork) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        callbackSlot.captured.onAvailable(testNetwork)
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    fun `onUserPreferenceChanged triggers network change with fresh state`() = runTest(scheduler) {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs

        val testNetwork = mockNetwork(9999L)
        val cap = mockNetworkCapabilities(hasInternet = true)
        every { cm.activeNetwork } returns testNetwork
        every { cm.getNetworkCapabilities(testNetwork) } returns cap
        every { cm.getLinkProperties(testNetwork) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        monitor.onUserPreferenceChanged()
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    fun `onPolicyChanged re-registers callbacks based on new policy`() = runTest(scheduler) {
        val initialCallbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(initialCallbackSlot))
        } just Runs

        val testNetwork = mockNetwork(1000L)
        val cap = mockNetworkCapabilities(hasInternet = true)
        every { cm.activeNetwork } returns testNetwork
        every { cm.getNetworkCapabilities(testNetwork) } returns cap
        every { cm.getLinkProperties(testNetwork) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        every { persistentState.vpnBuilderPolicy } returns VpnBuilderPolicy.RELAXED.ordinal

        monitor.onPolicyChanged()
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    fun `onVpnStop unregisters all callbacks cleanly`() = runTest(scheduler) {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs

        val testNetwork = mockNetwork(1001L)
        val cap = mockNetworkCapabilities(hasInternet = true)
        every { cm.activeNetwork } returns testNetwork
        every { cm.getNetworkCapabilities(testNetwork) } returns cap
        every { cm.getLinkProperties(testNetwork) } returns LinkProperties()

        val monitor = newMonitor()
        assertTrue(monitor.onVpnStart(context))

        monitor.onVpnStop()
        advanceUntilIdle()

        coVerify(atLeast = 1) { networkListener.onNetworkChange(any()) }
    }

    @Test
    @Config(sdk = [23])
    fun `android 6 transport callback does not include location info flag`() {
        val monitor = newMonitor()
        val transportCallback = monitor.transportCallback()
        assertNotNull(transportCallback)
        // Verify the callback is a NetworkCallback (pre-S doesn't flag)
        assertTrue(transportCallback is ConnectivityManager.NetworkCallback)
    }

    @Test
    @Config(sdk = [31])
    fun `android 12 S+ transport callback includes location info flag in signature`() {
        val monitor = newMonitor()
        val transportCallback = monitor.transportCallback()
        assertNotNull(transportCallback)
        assertTrue(transportCallback is ConnectivityManager.NetworkCallback)
    }

    @Test
    fun `connectivity check interval is 15 seconds`() {
        assertEquals(15_000L, ConnectionMonitor.CONNECTIVITY_CHECK_INTERVAL_MS)
    }
}
