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
package com.celzero.bravedns

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.AntiCensorshipActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RunWith(AndroidJUnit4::class)
class AntiCensorshipActivityTest : KoinComponent {

    private val persistentState by inject<PersistentState>()
    private lateinit var scenario: ActivityScenario<AntiCensorshipActivity>

    @Before
    fun setUp() {
        persistentState.dialStrategy = AntiCensorshipActivity.DialStrategies.SPLIT_AUTO.mode
        persistentState.retryStrategy = AntiCensorshipActivity.RetryStrategies.RETRY_AFTER_SPLIT.mode
        persistentState.autoProxyEnabled = false
        scenario = ActivityScenario.launch(AntiCensorshipActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun shouldSetNeverSplitModeWhenRadioNeverSplitChecked() {
        scenario.onActivity { activity ->
            activity.b.acRadioNeverSplit.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.DialStrategies.NEVER_SPLIT.mode, persistentState.dialStrategy)
    }

    @Test
    fun shouldSetSplitAutoModeWhenRadioSplitAutoChecked() {
        scenario.onActivity { activity ->
            activity.b.acRadioSplitAuto.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.DialStrategies.SPLIT_AUTO.mode, persistentState.dialStrategy)
    }

    @Test
    fun shouldSetRetryWithSplitModeWhenRadioRetryWithSplitChecked() {
        scenario.onActivity { activity ->
            activity.b.acRadioRetryWithSplit.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.RetryStrategies.RETRY_WITH_SPLIT.mode, persistentState.retryStrategy)
    }

    @Test
    fun shouldSetRetryNeverModeWhenRadioNeverRetryChecked() {
        scenario.onActivity { activity ->
            activity.b.acRadioNeverRetry.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.RetryStrategies.RETRY_NEVER.mode, persistentState.retryStrategy)
    }

    @Test
    fun shouldSetRetryAfterSplitModeWhenRadioRetryAfterSplitChecked() {
        scenario.onActivity { activity ->
            activity.b.acRadioRetryAfterSplit.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.RetryStrategies.RETRY_AFTER_SPLIT.mode, persistentState.retryStrategy)
    }

    @Test
    fun shouldUncheckOtherRadiosWhenNeverSplitChecked() {
        scenario.onActivity { activity ->
            activity.b.acRadioNeverSplit.isChecked = true
            assertEquals(false, activity.b.acRadioSplitAuto.isChecked)
            assertEquals(false, activity.b.acRadioSplitTcp.isChecked)
            assertEquals(false, activity.b.acRadioSplitTls.isChecked)
            assertEquals(false, activity.b.acRadioDesync.isChecked)
        }
    }

    @Test
    fun shouldUncheckOtherRetryRadiosWhenRetryWithSplitChecked() {
        scenario.onActivity { activity ->
            activity.b.acRadioRetryWithSplit.isChecked = true
            assertEquals(false, activity.b.acRadioNeverRetry.isChecked)
            assertEquals(false, activity.b.acRadioRetryAfterSplit.isChecked)
        }
    }
}
