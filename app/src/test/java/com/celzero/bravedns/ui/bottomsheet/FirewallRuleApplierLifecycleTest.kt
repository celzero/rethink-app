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
package com.celzero.bravedns.ui.bottomsheet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the ConnTrackerBottomSheet firewall-rule lifecycle
 * sequence: the fragment's view can be destroyed while the fragment remains
 * attached, and the old uiCtx guard skipped the whole callback — silently
 * dropping the user's firewall change and its audit log entry. Persistence
 * must always run; only the spinner UI update may be skipped.
 */
@ExperimentalCoroutinesApi
class FirewallRuleApplierLifecycleTest {

    private fun mainDispatcher(testScope: TestScope) = StandardTestDispatcher(testScope.testScheduler)

    @Test
    fun `firewall change persists even when the view is destroyed before the ui update`() = runTest {
        val events = mutableListOf<String>()

        applyFirewallRuleWithLifecycle(
            isViewAlive = { false }, // view destroyed, fragment still attached
            mainDispatcher = mainDispatcher(this),
            persistAndLog = {
                events.add("persist")
                events.add("log")
            },
            renderUi = { events.add("render") }
        )

        assertTrue(
            "persistence must not be gated on the view lifecycle",
            events.contains("persist")
        )
        assertTrue(
            "audit log must not be gated on the view lifecycle",
            events.contains("log")
        )
        assertFalse(
            "ui update must be skipped when the view is gone",
            events.contains("render")
        )
    }

    @Test
    fun `firewall change persists and renders when the view is alive`() = runTest {
        val events = mutableListOf<String>()

        applyFirewallRuleWithLifecycle(
            isViewAlive = { true },
            mainDispatcher = mainDispatcher(this),
            persistAndLog = { events.add("persist") },
            renderUi = { events.add("render") }
        )

        assertEquals(listOf("persist", "render"), events)
    }

    @Test
    fun `persistence happens before the ui update`() = runTest {
        val events = mutableListOf<String>()

        applyFirewallRuleWithLifecycle(
            isViewAlive = { true },
            mainDispatcher = mainDispatcher(this),
            persistAndLog = { events.add("persist") },
            renderUi = { events.add("render") }
        )

        assertEquals(
            "persistence must be ordered before rendering",
            0,
            events.indexOf("persist")
        )
        assertTrue(events.indexOf("render") > events.indexOf("persist"))
    }
}
