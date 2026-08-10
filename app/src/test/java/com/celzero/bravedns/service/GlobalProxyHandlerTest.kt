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

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.RpnType
import com.celzero.firestack.backend.Backend
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GlobalProxyHandlerTest : KoinTest {

    private lateinit var context: Context
    private val mockEventLogger: EventLogger = mockk(relaxed = true)

    private val proxies: ConcurrentHashMap<String, GlobalProxyHandler.ProxyEntry>
        get() = getPrivateField<ConcurrentHashMap<String, GlobalProxyHandler.ProxyEntry>>(
            GlobalProxyHandler, "proxies"
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        try { stopKoin() } catch (_: Exception) {}

        startKoin {
            modules(module {
                single { context }
                single { mockEventLogger }
            })
        }

        mockkObject(VpnController)
        mockkObject(RpnProxyManager)
        mockkObject(WireguardManager)

        every { VpnController.hasTunnel() } returns true
        coEvery { VpnController.hasProxy(any()) } returns false
        coEvery { VpnController.hasRpnProxy(any()) } returns false
        coJustRun { VpnController.addWireGuardProxy(any(), any()) }
        coEvery { VpnController.addNewWinServer(any()) } returns Pair(true, "ok")
        every { WireguardManager.getConfigById(any()) } returns mockk(relaxed = true)
        every { RpnProxyManager.isRpnActive() } returns true
        coEvery { RpnProxyManager.registerProxy(any()) } returns true
        coEvery { RpnProxyManager.getEnabledConfigs() } returns emptySet()

        proxies.clear()
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun proxyMissingAfterGrace(id: String): GlobalProxyHandler.ProxyEntry {
        GlobalProxyHandler.track(id)
        val entry = proxies[id]!!
        entry.addedAtElapsed = SystemClock.elapsedRealtime() - 3 * 60 * 1000L
        return entry
    }

    @Test
    fun `no presence check or readd within grace period`() = runTest {
        GlobalProxyHandler.track("wg1")
        // addedAtElapsed is set to now by track(), so the grace period has not elapsed

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 0) { VpnController.hasProxy("wg1") }
        coVerify(exactly = 0) { VpnController.addWireGuardProxy(any(), any()) }
        assertTrue(proxies.containsKey("wg1"))
    }

    @Test
    fun `readd wg proxy when missing after grace`() = runTest {
        val entry = proxyMissingAfterGrace("wg1")

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 1) { VpnController.addWireGuardProxy("wg1", true) }
        assertEquals(1, entry.attempts)
        assertTrue(proxies.containsKey("wg1"))
    }

    @Test
    fun `respects exponential backoff between attempts`() = runTest {
        val entry = proxyMissingAfterGrace("wg1")

        GlobalProxyHandler.checkAndReadd() // attempt 1
        coVerify(exactly = 1) { VpnController.addWireGuardProxy("wg1", true) }

        // backoff of 1 min after the first attempt: second attempt is skipped
        GlobalProxyHandler.checkAndReadd()
        coVerify(exactly = 1) { VpnController.addWireGuardProxy("wg1", true) }

        // after the backoff window elapses, the next attempt happens
        entry.lastAttemptAtElapsed = SystemClock.elapsedRealtime() - 2 * 60 * 1000L
        GlobalProxyHandler.checkAndReadd()
        coVerify(exactly = 2) { VpnController.addWireGuardProxy("wg1", true) }
        assertEquals(2, entry.attempts)
    }

    @Test
    fun `gives up after max attempts`() = runTest {
        val entry = proxyMissingAfterGrace("wg1")
        entry.attempts = 5 // GlobalProxyHandler.MAX_ATTEMPTS

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 0) { VpnController.addWireGuardProxy(any(), any()) }
        assertNull(proxies["wg1"])
        verify { mockEventLogger.log(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resets attempt budget when proxy is present`() = runTest {
        GlobalProxyHandler.track("wg1")
        val entry = proxies["wg1"]!!
        entry.addedAtElapsed = SystemClock.elapsedRealtime() - 3 * 60 * 1000L
        entry.attempts = 3
        coEvery { VpnController.hasProxy("wg1") } returns true

        GlobalProxyHandler.checkAndReadd()

        assertEquals(0, entry.attempts)
        coVerify(exactly = 0) { VpnController.addWireGuardProxy(any(), any()) }
        assertTrue(proxies.containsKey("wg1"))
    }

    @Test
    fun `drops wg proxy when config no longer exists`() = runTest {
        proxyMissingAfterGrace("wg1")
        every { WireguardManager.getConfigById(1) } returns null

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 0) { VpnController.addWireGuardProxy(any(), any()) }
        assertNull(proxies["wg1"])
    }

    @Test
    fun `auto rpn presence check uses hasRpnProxy with Backend RpnWin`() = runTest {
        GlobalProxyHandler.track(Backend.RpnWin)
        val entry = proxies[Backend.RpnWin]!!
        entry.addedAtElapsed = SystemClock.elapsedRealtime() - 3 * 60 * 1000L
        coEvery { VpnController.hasRpnProxy(Backend.RpnWin) } returns true

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 1) { VpnController.hasRpnProxy(Backend.RpnWin) }
        coVerify(exactly = 0) { RpnProxyManager.registerProxy(any()) }
        assertEquals(0, entry.attempts)
    }

    @Test
    fun `auto rpn readd registers win when active`() = runTest {
        proxyMissingAfterGrace(Backend.RpnWin)

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 1) { RpnProxyManager.registerProxy(RpnType.WIN) }
    }

    @Test
    fun `rpn country readd via addNewWinServer`() = runTest {
        val id = Backend.RpnWin + "IN"
        proxyMissingAfterGrace(id)
        coEvery { RpnProxyManager.getEnabledConfigs() } returns
            setOf(CountryConfig(id = "IN", cc = "IN", key = "IN", isEnabled = true))

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 1) { VpnController.addNewWinServer("IN") }
        assertTrue(proxies.containsKey(id))
    }

    @Test
    fun `drops rpn country when no longer enabled`() = runTest {
        val id = Backend.RpnWin + "IN"
        proxyMissingAfterGrace(id)
        coEvery { RpnProxyManager.getEnabledConfigs() } returns emptySet()

        GlobalProxyHandler.checkAndReadd()

        coVerify(exactly = 0) { VpnController.addNewWinServer(any()) }
        assertNull(proxies[id])
    }

    @Test
    fun `untrack removes single proxy and untrackRpn removes win family`() {
        GlobalProxyHandler.track("wg1")
        GlobalProxyHandler.track(Backend.RpnWin)
        GlobalProxyHandler.track(Backend.RpnWin + "IN")

        GlobalProxyHandler.untrack("wg1")
        assertFalse(proxies.containsKey("wg1"))

        GlobalProxyHandler.untrackRpn()
        assertFalse(proxies.containsKey(Backend.RpnWin))
        assertFalse(proxies.containsKey(Backend.RpnWin + "IN"))
    }

    private inline fun <reified T> getPrivateField(obj: Any, name: String): T {
        var field: Field? = obj.javaClass.getDeclaredField(name)
        if (field == null) {
            field = obj.javaClass.superclass?.getDeclaredField(name)
        }
        field!!.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(obj) as T
    }
}
