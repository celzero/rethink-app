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
package com.celzero.bravedns.ui.bottomsheet

import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [TaskerAutomationBottomSheet].
 *
 * Two layers:
 *  1. Resource-free unit tests for [TaskerAutomationBottomSheet.normalizePackages].
 *  2. Robolectric UI tests for the sheet itself (prefill / save / wg switch), hosted on
 *     [MiscSettingsActivity]. These follow the same resource-error guard used by
 *     [com.celzero.bravedns.ui.activity.MiscSettingsActivityRobolectricTest]: this repo's
 *     Robolectric setup does not package Android resources (isIncludeAndroidResources is
 *     off), so view inflation reports a resource error in that environment and the UI
 *     assertions are skipped; the moment resources are enabled they run for real.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskerAutomationBottomSheetTest {

    private lateinit var mockPersistentState: PersistentState
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockPersistentState = mockk(relaxed = true)
        every { mockPersistentState.theme } returns 0
        every { mockPersistentState.logsEnabled } returns false
        every { mockPersistentState.pcapMode } returns 0
        every { mockPersistentState.notificationActionType } returns 0
        every { mockPersistentState.biometricAuthType } returns 0
        every { mockPersistentState.prefAutoStartBootUp } returns false
        every { mockPersistentState.downloadIpInfo } returns false
        every { mockPersistentState.tombstoneApps } returns false
        every { mockPersistentState.firewallBubbleEnabled } returns false
        every { mockPersistentState.persistentNotification } returns false
        every { mockPersistentState.firebaseErrorReportingEnabled } returns false
        every { mockPersistentState.checkForAppUpdate } returns false
        every { mockPersistentState.goLoggerLevel } returns 4L
        every { mockPersistentState.firebaseUserToken } returns "test-token"
        every { mockPersistentState.firebaseUserTokenTimestamp } returns System.currentTimeMillis()
        every { mockPersistentState.appTriggerPackages } returns ""
        every { mockPersistentState.shouldRequestNotificationPermission } returns false
        every { mockPersistentState.wgTaskerAutomationEnabled } returns false

        stopKoinSafely()
        startKoin {
            modules(module {
                single<PersistentState> { mockPersistentState }
                single<AppConfig> { mockk(relaxed = true) }
                single<RefreshDatabase> { mockk(relaxed = true) }
                single<EventLogger> { mockk(relaxed = true) }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoinSafely()
        Dispatchers.resetMain()
    }

    private fun stopKoinSafely() {
        try { stopKoin() } catch (_: Exception) { }
    }

    /**
     * Shows the sheet on [MiscSettingsActivity]. Returns null (skipping UI assertions)
     * when the environment cannot inflate android resources, mirroring the guard used by
     * MiscSettingsActivityRobolectricTest#buildAndStartActivity.
     */
    private fun showSheet(): Pair<MiscSettingsActivity, TaskerAutomationBottomSheet>? {
        return try {
            val activity = Robolectric.buildActivity(MiscSettingsActivity::class.java)
                .create().start().resume().get()
            val sheet = TaskerAutomationBottomSheet()
            sheet.show(activity.supportFragmentManager, TaskerAutomationBottomSheet.TAG)
            activity.supportFragmentManager.executePendingTransactions()
            activity to sheet
        } catch (e: Exception) {
            if (isExpectedResourceError(e)) null else throw e
        }
    }

    private fun isExpectedResourceError(e: Throwable): Boolean {
        val msg = e.message ?: e.cause?.message ?: ""
        return e is android.content.res.Resources.NotFoundException
            || e is IllegalStateException
            || msg.contains("Resource", ignoreCase = true)
            || msg.contains("NotFoundException", ignoreCase = true)
    }

    // ── resource-free logic tests ────────────────────────────────────────────

    @Test
    fun `normalizePackages trims whitespace`() {
        assertEquals("com.a,com.b", TaskerAutomationBottomSheet.normalizePackages(" com.a,com.b "))
    }

    @Test
    fun `normalizePackages rejects blank input`() {
        assertNull(TaskerAutomationBottomSheet.normalizePackages(""))
        assertNull(TaskerAutomationBottomSheet.normalizePackages("   "))
        assertNull(TaskerAutomationBottomSheet.normalizePackages(null))
    }

    @Test
    fun `normalizePackages keeps inner content untouched`() {
        assertEquals("net.dinglisch.android.taskerm", TaskerAutomationBottomSheet.normalizePackages("net.dinglisch.android.taskerm"))
    }

    // ── Robolectric UI tests (guarded when resources are unavailable) ────────

    @Test
    fun `wg switch off by default and persists when toggled on`() {
        val shown = showSheet() ?: return
        val switch = shown.second.requireView().findViewById<SwitchCompat>(R.id.tasker_wg_switch)
        assertNotNull("wg switch must be present", switch)
        assertFalse("wg switch must be off by default", switch.isChecked)

        switch.performClick()
        verify(exactly = 1) { mockPersistentState.wgTaskerAutomationEnabled = true }
    }

    @Test
    fun `wg switch reflects saved state`() {
        every { mockPersistentState.wgTaskerAutomationEnabled } returns true
        val shown = showSheet() ?: return
        val switch = shown.second.requireView().findViewById<SwitchCompat>(R.id.tasker_wg_switch)
        assertTrue("wg switch must reflect the saved state", switch.isChecked)
    }

    @Test
    fun `packages input prefilled from persistent state`() {
        every { mockPersistentState.appTriggerPackages } returns "com.a,com.b"
        val shown = showSheet() ?: return
        val et = shown.second.requireView().findViewById<EditText>(R.id.tasker_packages_et)
        assertEquals("com.a,com.b", et.text.toString())
    }

    @Test
    fun `save persists packages and dismisses the sheet`() {
        val shown = showSheet() ?: return
        val (activity, sheet) = shown
        val et = sheet.requireView().findViewById<EditText>(R.id.tasker_packages_et)
        et.setText("net.dinglisch.android.taskerm")
        sheet.requireView()
            .findViewById<android.view.View>(R.id.tasker_packages_save_btn)
            .performClick()

        verify(exactly = 1) {
            mockPersistentState.appTriggerPackages = "net.dinglisch.android.taskerm"
        }
        activity.supportFragmentManager.executePendingTransactions()
        assertNull(
            "sheet must be dismissed after save",
            activity.supportFragmentManager.findFragmentByTag(TaskerAutomationBottomSheet.TAG)
        )
    }

    @Test
    fun `save with empty input persists empty allowlist and dismisses the sheet`() {
        val shown = showSheet() ?: return
        val (activity, sheet) = shown
        val et = sheet.requireView().findViewById<EditText>(R.id.tasker_packages_et)
        et.setText("")
        sheet.requireView()
            .findViewById<android.view.View>(R.id.tasker_packages_save_btn)
            .performClick()

        verify(exactly = 1) { mockPersistentState.appTriggerPackages = "" }
        activity.supportFragmentManager.executePendingTransactions()
        assertNull(
            "sheet must be dismissed after save",
            activity.supportFragmentManager.findFragmentByTag(TaskerAutomationBottomSheet.TAG)
        )
    }
}
