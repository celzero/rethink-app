/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.receiver

import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [WgCommands] failure reporting: every command returns the
 * per-config failures (with a [WgCommandError] matching the messages the
 * in-app WireGuard screens show) instead of silently skipping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WgCommandsTest {

    private lateinit var commands: WgCommands

    @Before
    fun setup() {
        commands = WgCommands()
        mockkObject(VpnController)
        mockkObject(WireguardManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun mapping(
        id: Int,
        isActive: Boolean = false,
        isCatchAll: Boolean = false,
        oneWireGuard: Boolean = false
    ): WgConfigFilesImmutable {
        val m = mockk<WgConfigFilesImmutable>(relaxed = true)
        every { m.id } returns id
        every { m.name } returns "wg-$id"
        every { m.isActive } returns isActive
        every { m.isCatchAll } returns isCatchAll
        every { m.oneWireGuard } returns oneWireGuard
        return m
    }

    // ── start ────────────────────────────────────────────────────────────────

    @Test
    fun `start with vpn off reports VPN_OFF`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1)
        every { VpnController.hasTunnel() } returns false

        val failures = commands.start(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.VPN_OFF)), failures)
    }

    @Test
    fun `start with missing config reports INVALID_CONFIG`() = runTest {
        every { WireguardManager.getConfigFilesById(9) } returns null

        val failures = commands.start(listOf(9))

        assertEquals(listOf(WgCommands.Failure(9, WgCommandError.INVALID_CONFIG)), failures)
    }

    @Test
    fun `start in dns-only mode reports DNS_MODE`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canEnableProxy() } returns false

        val failures = commands.start(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.DNS_MODE)), failures)
    }

    @Test
    fun `start while another one-wg is active reports ONE_WG_ACTIVE`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canEnableProxy() } returns true
        every { WireguardManager.isValidConfig(1) } returns true
        every { WireguardManager.oneWireGuardEnabled() } returns true

        val failures = commands.start(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.ONE_WG_ACTIVE)), failures)
    }

    @Test
    fun `start with overlapping keys is allowed with a warning`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canEnableProxy() } returns true
        every { WireguardManager.isValidConfig(1) } returns true
        every { WireguardManager.oneWireGuardEnabled() } returns false
        every { WireguardManager.canEnableProxy(1) } returns false
        coEvery { WireguardManager.enableConfig(any()) } just Runs

        val failures = commands.start(listOf(1))

        assertTrue(failures.isEmpty())
        coVerify { WireguardManager.enableConfig(any()) }
    }

    @Test
    fun `start on already active config is a silent no-op`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1, isActive = true)

        val failures = commands.start(listOf(1))

        assertTrue(failures.isEmpty())
    }

    @Test
    fun `start success reports no failures`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canEnableProxy() } returns true
        every { WireguardManager.isValidConfig(1) } returns true
        every { WireguardManager.oneWireGuardEnabled() } returns false
        every { WireguardManager.canEnableProxy(1) } returns true
        coEvery { WireguardManager.enableConfig(any()) } just Runs

        val failures = commands.start(listOf(1))

        assertTrue(failures.isEmpty())
    }

    @Test
    fun `start batching reports per-config failures without aborting`() = runTest {
        // id 1: missing config; id 2: success; id 3: key overlap warning, still enabled
        every { WireguardManager.getConfigFilesById(1) } returns null
        every { WireguardManager.getConfigFilesById(2) } returns mapping(2)
        every { WireguardManager.getConfigFilesById(3) } returns mapping(3)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canEnableProxy() } returns true
        every { WireguardManager.isValidConfig(2) } returns true
        every { WireguardManager.isValidConfig(3) } returns true
        every { WireguardManager.oneWireGuardEnabled() } returns false
        every { WireguardManager.canEnableProxy(2) } returns true
        every { WireguardManager.canEnableProxy(3) } returns false
        coEvery { WireguardManager.enableConfig(any()) } just Runs

        val failures = commands.start(listOf(1, 2, 3))

        assertEquals(
            listOf(WgCommands.Failure(1, WgCommandError.INVALID_CONFIG)),
            failures
        )
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop on catch-all config reports DISABLE_CATCH_ALL`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1, isActive = true, isCatchAll = true)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canDisableConfig(any()) } returns false

        val failures = commands.stop(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.DISABLE_CATCH_ALL)), failures)
    }

    @Test
    fun `stop on hop config reports DISABLE_HOP`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1, isActive = true)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canDisableConfig(any()) } returns false

        val failures = commands.stop(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.DISABLE_HOP)), failures)
    }

    @Test
    fun `stop on inactive config is a silent no-op`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1, isActive = false)

        val failures = commands.stop(listOf(1))

        assertTrue(failures.isEmpty())
    }

    // ── pause / resume ───────────────────────────────────────────────────────

    @Test
    fun `pause with vpn off reports VPN_OFF`() = runTest {
        every { VpnController.hasTunnel() } returns false

        val failures = commands.pause(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.VPN_OFF)), failures)
    }

    @Test
    fun `pause on inactive config reports INVALID_CONFIG`() = runTest {
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1, isActive = false)

        val failures = commands.pause(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.INVALID_CONFIG)), failures)
    }

    @Test
    fun `pause success reports no failures`() = runTest {
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1, isActive = true)
        coEvery { VpnController.pauseWireGuardProxy(any()) } just Runs

        val failures = commands.pause(listOf(1))

        assertTrue(failures.isEmpty())
    }

    @Test
    fun `resume success reports no failures`() = runTest {
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1, isActive = true)
        coEvery { VpnController.resumeWireGuardProxy(any()) } just Runs

        val failures = commands.resume(listOf(1))

        assertTrue(failures.isEmpty())
    }

    // ── unexpected errors ────────────────────────────────────────────────────

    @Test
    fun `unexpected error is reported as ACTION_FAILED without aborting the batch`() = runTest {
        every { WireguardManager.getConfigFilesById(1) } returns mapping(1)
        every { VpnController.hasTunnel() } returns true
        every { WireguardManager.canEnableProxy() } returns true
        every { WireguardManager.isValidConfig(1) } returns true
        every { WireguardManager.oneWireGuardEnabled() } returns false
        every { WireguardManager.canEnableProxy(1) } returns true
        coEvery { WireguardManager.enableConfig(any()) } throws RuntimeException("boom")
        // id 2 stops cleanly (inactive)
        every { WireguardManager.getConfigFilesById(2) } returns mapping(2, isActive = false)

        val failures = commands.start(listOf(1))

        assertEquals(listOf(WgCommands.Failure(1, WgCommandError.ACTION_FAILED)), failures)
        assertTrue(commands.stop(listOf(2)).isEmpty())
    }
}
