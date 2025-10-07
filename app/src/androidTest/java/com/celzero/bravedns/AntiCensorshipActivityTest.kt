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
package com.rethinkdns.retrixed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.rethinkdns.retrixed.service.PersistentState
import com.rethinkdns.retrixed.ui.activity.AntiCensorshipActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RunWith(AndroidJUnit4::class)
class AntiCensorshipActivityTest: KoinComponent {

    @get:Rule
    val activityRule = ActivityScenarioRule(AntiCensorshipActivity::class.java)

    private val persistentState by inject<PersistentState>()

    @Test
    fun shouldSetNeverSplitModeWhenRadioNeverSplitChecked() {
        activityRule.scenario.onActivity { activity ->
            activity.b.acRadioNeverSplit.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.DialStrategies.NEVER_SPLIT.mode, persistentState.dialStrategy)
    }

    @Test
    fun shouldSetSplitAutoModeWhenRadioSplitAutoChecked() {
        activityRule.scenario.onActivity { activity ->
            activity.b.acRadioSplitAuto.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.DialStrategies.SPLIT_AUTO.mode, persistentState.dialStrategy)
    }

    @Test
    fun shouldSetRetryWithSplitModeWhenRadioRetryWithSplitChecked() {
        activityRule.scenario.onActivity { activity ->
            activity.b.acRadioRetryWithSplit.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.RetryStrategies.RETRY_WITH_SPLIT.mode, persistentState.retryStrategy)
    }

    @Test
    fun shouldSetRetryNeverModeWhenRadioNeverRetryChecked() {
        activityRule.scenario.onActivity { activity ->
            activity.b.acRadioNeverRetry.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.RetryStrategies.RETRY_NEVER.mode, persistentState.retryStrategy)
    }

    @Test
    fun shouldSetRetryAfterSplitModeWhenRadioRetryAfterSplitChecked() {
        activityRule.scenario.onActivity { activity ->
            activity.b.acRadioRetryAfterSplit.isChecked = true
        }
        assertEquals(AntiCensorshipActivity.RetryStrategies.RETRY_AFTER_SPLIT.mode, persistentState.retryStrategy)
    }

    @Test
    fun shouldUncheckOtherRadiosWhenNeverSplitChecked() {
        activityRule.scenario.onActivity { activity ->
            activity.b.acRadioNeverSplit.isChecked = true
            assertEquals(false, activity.b.acRadioSplitAuto.isChecked)
            assertEquals(false, activity.b.acRadioSplitTcp.isChecked)
            assertEquals(false, activity.b.acRadioSplitTls.isChecked)
            assertEquals(false, activity.b.acRadioDesync.isChecked)
        }
    }

    @Test
    fun shouldUncheckOtherRetryRadiosWhenRetryWithSplitChecked() {
        activityRule.scenario.onActivity { activity ->
            activity.b.acRadioRetryWithSplit.isChecked = true
            assertEquals(false, activity.b.acRadioNeverRetry.isChecked)
            assertEquals(false, activity.b.acRadioRetryAfterSplit.isChecked)
        }
    }
}

