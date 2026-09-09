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
package com.celzero.bravedns.ui.fragment

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the AboutFragment stats-dialog lifecycle sequence:
 * while stats are collected, the fragment's view can be destroyed (the
 * fragment-scoped coroutine keeps running). The old uiCtx guard skipped the
 * whole callback, so the activity-attached progress dialog was never
 * dismissed and no other cleanup path dismissed it. The progress dialog must
 * be dismissed unconditionally before the result-dialog guard.
 */
@ExperimentalCoroutinesApi
class StatsProgressDialogLifecycleTest {

    private fun mainDispatcher(testScope: TestScope) = StandardTestDispatcher(testScope.testScheduler)

    @Test
    fun `progress dialog is dismissed when the view is destroyed during stats collection`() = runTest {
        val events = mutableListOf<String>()

        dismissProgressAndShowResults(
            isViewAlive = { false }, // view destroyed, coroutine still completes
            mainDispatcher = mainDispatcher(this),
            dismissProgress = { events.add("dismiss") },
            showResults = { events.add("showResults") }
        )

        assertTrue(
            "progress dialog must be dismissed even when the view is gone",
            events.contains("dismiss")
        )
        assertFalse(
            "result dialog must not be created when the view is gone",
            events.contains("showResults")
        )
    }

    @Test
    fun `progress dialog is dismissed before results are shown when the view is alive`() = runTest {
        val events = mutableListOf<String>()

        dismissProgressAndShowResults(
            isViewAlive = { true },
            mainDispatcher = mainDispatcher(this),
            dismissProgress = { events.add("dismiss") },
            showResults = { events.add("showResults") }
        )

        assertEquals(listOf("dismiss", "showResults"), events)
        assertTrue(events.indexOf("dismiss") < events.indexOf("showResults"))
    }
}
