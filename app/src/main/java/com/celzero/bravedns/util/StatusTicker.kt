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
package com.celzero.bravedns.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Repeating tick for UI status refresh loops.
 *
 * Guarantees at most one active loop per instance: [start] is a no-op while a
 * previous loop is still running, and while the ticker is stopped no timer or
 * coroutine exists at all (no scheduling activity while idle).
 *
 * The first [tick] fires immediately on [start]; afterwards [tick] runs once
 * per [intervalMs] milliseconds on [scope]'s context. Returning false from
 * [tick] stops the ticker without arming the next delay.
 */
class StatusTicker {

    private var job: Job? = null

    /** True while the ticker loop is active. */
    fun isActive(): Boolean = job?.isActive == true

    /**
     * Starts the ticker on [scope] unless it is already running. [tick] runs
     * immediately and then once per [intervalMs]; return false from [tick] to
     * stop the ticker.
     *
     * @return true if a new loop was launched (its first [tick] fires
     * immediately), false if a loop was already active and nothing happened
     */
    fun start(scope: CoroutineScope, intervalMs: Long, tick: suspend () -> Boolean): Boolean {
        if (job?.isActive == true) return false
        job = scope.launch {
            while (true) {
                if (!tick()) break
                delay(intervalMs.milliseconds)
            }
        }
        return true
    }

    /** Cancels the ticker loop; [start] may be called again afterwards. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
