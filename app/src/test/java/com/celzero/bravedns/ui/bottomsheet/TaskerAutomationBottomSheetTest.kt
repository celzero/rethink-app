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

import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Reapply the production theme on every instance, including ActivityController.recreate(). */
class TaskerTestHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppThemeWhite)
        super.onCreate(savedInstanceState)
    }
}

/** Native Compose interactions; failures are not silently skipped as resource errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TaskerAutomationBottomSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var persistent: PersistentState
    private var host: ActivityController<TaskerTestHostActivity>? = null

    @Before fun setUp() {
        persistent = mockk(relaxed = true)
        every { persistent.theme } returns 0
        every { persistent.appTriggerPackages } returns ""
        every { persistent.wgTaskerAutomationEnabled } returns false
        stopKoin()
        startKoin { modules(module { single<PersistentState> { persistent } }) }
    }

    @After fun tearDown() {
        host?.pause()?.stop()?.destroy()
        host = null
        stopKoin()
    }

    private fun showSheet() {
        compose.runOnUiThread {
            host = Robolectric.buildActivity(TaskerTestHostActivity::class.java).apply {
                create().start().resume().visible()
            }
            val activity = checkNotNull(host).get()
            TaskerAutomationBottomSheet().showNow(activity.supportFragmentManager, TaskerAutomationBottomSheet.TAG)
        }
        compose.waitForIdle()
    }

    @Test fun `normalizePackages trims whitespace`() {
        assertEquals("com.a,com.b", TaskerAutomationBottomSheet.normalizePackages(" com.a,com.b "))
    }
    @Test fun `normalizePackages rejects blank input`() {
        assertNull(TaskerAutomationBottomSheet.normalizePackages(""))
        assertNull(TaskerAutomationBottomSheet.normalizePackages("   "))
        assertNull(TaskerAutomationBottomSheet.normalizePackages(null))
    }
    @Test fun `normalizePackages keeps inner content untouched`() {
        assertEquals("net.dinglisch.android.taskerm", TaskerAutomationBottomSheet.normalizePackages("net.dinglisch.android.taskerm"))
    }

    @Test fun `wg switch persists each repeated toggle exactly once`() {
        showSheet()
        val row = compose.onNodeWithTag("tasker_wg_rl")
        row.assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
        verify(exactly = 1) { persistent.wgTaskerAutomationEnabled = true }
        verify(exactly = 1) { persistent.wgTaskerAutomationEnabled = false }
    }

    @Test fun `wg switch reflects saved state without writing it`() {
        every { persistent.wgTaskerAutomationEnabled } returns true
        showSheet()
        compose.onNodeWithTag("tasker_wg_rl").assertIsOn()
        verify(exactly = 0) { persistent.wgTaskerAutomationEnabled = any() }
    }

    @Test fun `packages input prefilled from persistent state`() {
        every { persistent.appTriggerPackages } returns "com.a,com.b"
        showSheet()
        compose.onNodeWithTag("tasker_packages_et").assertTextContains("com.a,com.b")
    }

    @Test fun `save persists trimmed packages and dismisses the sheet`() {
        showSheet()
        compose.onNodeWithTag("tasker_packages_et").performTextReplacement(" net.dinglisch.android.taskerm ")
        compose.onNodeWithTag("tasker_packages_save_btn").performClick()
        verify(exactly = 1) { persistent.appTriggerPackages = "net.dinglisch.android.taskerm" }
        assertDismissed()
    }

    @Test fun `empty input explicitly clears allowlist and dismisses`() {
        every { persistent.appTriggerPackages } returns "com.a"
        showSheet()
        compose.onNodeWithTag("tasker_packages_et").performTextReplacement("   ")
        compose.onNodeWithTag("tasker_packages_save_btn").performClick()
        verify(exactly = 1) { persistent.appTriggerPackages = "" }
        assertDismissed()
    }

    @Test fun `unsaved package draft survives activity recreation without persisting`() {
        showSheet()
        compose.onNodeWithTag("tasker_packages_et").performTextReplacement("com.draft")
        compose.runOnUiThread { checkNotNull(host).recreate() }
        compose.waitForIdle()
        compose.onNodeWithTag("tasker_packages_et").assertTextContains("com.draft")
        verify(exactly = 0) { persistent.appTriggerPackages = any() }
    }

    @Test fun `dismiss without save does not change allowlist`() {
        showSheet()
        compose.onNodeWithTag("tasker_packages_et").performTextReplacement("com.draft")
        compose.runOnUiThread {
            val activity = checkNotNull(host).get()
            (activity.supportFragmentManager.findFragmentByTag(TaskerAutomationBottomSheet.TAG) as TaskerAutomationBottomSheet).dismissNow()
        }
        verify(exactly = 0) { persistent.appTriggerPackages = any() }
        assertDismissed()
    }

    private fun assertDismissed() {
        compose.runOnUiThread {
            val activity = checkNotNull(host).get()
            activity.supportFragmentManager.executePendingTransactions()
            assertNull(activity.supportFragmentManager.findFragmentByTag(TaskerAutomationBottomSheet.TAG))
        }
        compose.onNodeWithTag("tasker_packages_save_btn").assertDoesNotExist()
    }
}
