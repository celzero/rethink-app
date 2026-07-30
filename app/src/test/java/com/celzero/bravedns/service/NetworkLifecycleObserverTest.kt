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
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkLifecycleObserverTest {

    private lateinit var context: Context
    private lateinit var cm: ConnectivityManager
    private lateinit var listener: NetworkLifecycleObserver.Listener

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        cm = mockk(relaxed = true)
        listener = mockk(relaxed = true)

        every { context.applicationContext } returns context
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        } just Runs
        every { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun newObserver(): NetworkLifecycleObserver {
        return NetworkLifecycleObserver(context, listener)
    }

    private fun mockNetwork(handle: Long): Network {
        val network = mockk<Network>()
        every { network.networkHandle } returns handle
        return network
    }

    /**
     * Captures every callback registered via registerNetworkCallback into the returned
     * list. Registration order in start() is WiFi first, Cellular second, so the first
     * element is the WiFi callback and the second is the Cellular callback.
     */
    private fun captureCallbacks(): MutableList<ConnectivityManager.NetworkCallback> {
        val list = mutableListOf<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(list))
        } just Runs
        return list
    }

    @Test
    fun `start registers wifi and cellular network callbacks`() {
        captureCallbacks()
        val observer = newObserver()

        val started = observer.start()

        assertTrue(started)
        verify(exactly = 2) {
            cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `start returns true and is idempotent when already started`() {
        captureCallbacks()
        val observer = newObserver()

        assertTrue(observer.start())
        // second start must not register more callbacks
        assertTrue(observer.start())
        verify(exactly = 2) {
            cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `start returns false when connectivity manager is unavailable`() {
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
        val observer = newObserver()

        val started = observer.start()

        assertFalse(started)
        verify(exactly = 0) {
            cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `start returns false when registerNetworkCallback throws`() {
        captureCallbacks()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        } throws SecurityException("not allowed")
        val observer = newObserver()

        assertFalse(observer.start())
    }

    @Test
    fun `stop unregisters both callbacks when started`() {
        val list = captureCallbacks()
        val observer = newObserver()
        observer.start()

        observer.stop()

        assertEquals(2, list.size)
        verify(exactly = 1) { cm.unregisterNetworkCallback(list[0]) }
        verify(exactly = 1) { cm.unregisterNetworkCallback(list[1]) }
    }

    @Test
    fun `stop is a no-op when not started`() {
        val observer = newObserver()

        observer.stop() // should not throw

        verify(exactly = 0) { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `stop is idempotent`() {
        captureCallbacks()
        val observer = newObserver()
        observer.start()

        observer.stop()
        observer.stop()

        verify(exactly = 2) { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `wifi onAvailable is forwarded as NETWORK_ADDED with null ssid when transport unknown`() {
        val list = captureCallbacks()
        newObserver().start()
        val network = mockNetwork(11L)
        val wifiCallback = list[0]

        wifiCallback.onAvailable(network)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.NETWORK_ADDED, null)
        }
    }

    @Test
    fun `wifi onLost is forwarded as NETWORK_LOST`() {
        val list = captureCallbacks()
        newObserver().start()
        val network = mockNetwork(22L)
        val wifiCallback = list[0]

        wifiCallback.onLost(network)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.NETWORK_LOST, null)
        }
    }

    @Test
    fun `wifi onCapabilitiesChanged resolves ssid via wifi manager on pre-S`() {
        val list = captureCallbacks()
        val observer = newObserver()
        assertTrue(observer.start())

        // pre-S (sdk 28) resolves SSID through WifiManager#connectionInfo, which is read
        // when the callback fires, so the dependency is stubbed after start().
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"MyWifi\""
        val wm = mockk<WifiManager>()
        every { wm.connectionInfo } returns wifiInfo
        every { context.applicationContext.getSystemService(Context.WIFI_SERVICE) } returns wm

        val network = mockNetwork(33L)
        val cap = mockk<NetworkCapabilities>()
        every { cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        val wifiCallback = list[0]

        wifiCallback.onCapabilitiesChanged(network, cap)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.CAPABILITY_CHANGE, "MyWifi")
        }
    }

    @Test
    fun `wifi onCapabilitiesChanged forwards null ssid when transport is not wifi`() {
        val list = captureCallbacks()
        newObserver().start()
        val network = mockNetwork(44L)
        // relaxed so transportLabel()'s CELLULAR probe returns a default instead of throwing
        val cap = mockk<NetworkCapabilities>(relaxed = true)
        every { cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        val wifiCallback = list[0]

        wifiCallback.onCapabilitiesChanged(network, cap)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.CAPABILITY_CHANGE, null)
        }
    }

    @Test
    fun `wifi onLinkPropertiesChanged is forwarded as LINK_PROPERTY_CHANGE`() {
        val list = captureCallbacks()
        newObserver().start()
        val network = mockNetwork(55L)
        val lp = mockk<LinkProperties>(relaxed = true)
        val wifiCallback = list[0]

        wifiCallback.onLinkPropertiesChanged(network, lp)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.LINK_PROPERTY_CHANGE, null)
        }
    }

    @Test
    fun `cellular onAvailable is forwarded with null ssid`() {
        val list = captureCallbacks()
        newObserver().start()
        val network = mockNetwork(66L)
        val cellularCallback = list[1]

        cellularCallback.onAvailable(network)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.NETWORK_ADDED, null)
        }
    }

    @Test
    fun `cellular onCapabilitiesChanged is forwarded with null ssid`() {
        val list = captureCallbacks()
        newObserver().start()
        val network = mockNetwork(77L)
        val cap = mockk<NetworkCapabilities>(relaxed = true)
        val cellularCallback = list[1]

        cellularCallback.onCapabilitiesChanged(network, cap)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.CAPABILITY_CHANGE, null)
        }
    }

    @Test
    fun `callbacks delivered after stop are not forwarded`() {
        val list = captureCallbacks()
        val observer = newObserver()
        assertTrue(observer.start())
        val network = mockNetwork(88L)
        val wifiCallback = list[0]
        val cap = mockk<NetworkCapabilities>(relaxed = true)

        observer.stop()

        // unregisterNetworkCallback does not synchronously cancel in-flight callbacks;
        // the stopped-guard in dispatch() must drop anything delivered after stop().
        wifiCallback.onAvailable(network)
        wifiCallback.onCapabilitiesChanged(network, cap)
        wifiCallback.onLinkPropertiesChanged(network, mockk(relaxed = true))
        wifiCallback.onLost(network)

        verify(exactly = 0) {
            listener.onNetworkEvent(network, any(), any())
        }
    }

    @Test
    fun `start re-enables forwarding after a stop`() {
        val list = captureCallbacks()
        val observer = newObserver()
        assertTrue(observer.start())
        observer.stop()
        // second start() registers two fresh callbacks (indices 2 and 3) and must
        // reset the stopped-guard so the new callbacks are forwarded again.
        assertTrue(observer.start())

        val network = mockNetwork(99L)
        val newCellularCallback = list[3]

        newCellularCallback.onAvailable(network)

        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.NETWORK_ADDED, null)
        }
    }

    @Test
    fun `wifi onLost does not query capabilities for ssid resolution`() {
        val list = captureCallbacks()
        val observer = newObserver()
        assertTrue(observer.start())
        val network = mockNetwork(101L)
        val wifiCallback = list[0]

        wifiCallback.onLost(network)

        // NETWORK_LOST must still be forwarded with a null SSID ...
        verify(exactly = 1) {
            listener.onNetworkEvent(network, NetworkLifecycleObserver.EventType.NETWORK_LOST, null)
        }
        // ... but must not trigger a capabilities IPC against the (now dead) network.
        verify(exactly = 0) { cm.getNetworkCapabilities(network) }
    }

    @Test
    fun `event type enum exposes the four lifecycle events`() {
        val values = NetworkLifecycleObserver.EventType.values().map { it.name }.toSet()
        val expected = setOf("NETWORK_ADDED", "NETWORK_LOST", "CAPABILITY_CHANGE", "LINK_PROPERTY_CHANGE")
        assertEquals(expected, values)
    }
}
