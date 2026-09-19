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
package com.celzero.bravedns.iab

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import java.util.concurrent.atomic.AtomicLong

/**
 * Time-gated single-flight guard for the periodic device-registration check
 * (`checkAndRegisterDeviceIfNeeded`) that is implemented in **both**
 * [com.celzero.bravedns.scheduler.RpnProxyUpdateWorker] and [SubscriptionCheckWorker].
 *
 * ### Why this exists
 * `RpnProxyUpdateWorker` (45-min periodic) enqueues [SubscriptionCheckWorker] at the top
 * of every run and *also* executes its own registration check — so during the daily
 * registration window both workers (plus `RpnProxyManager.registerProxy`) can execute
 * reconcile paths concurrently. Historically each reconcile minted a fresh DID
 * (`POST /d/reg` with a blank `did` header) on any CID mismatch, producing two token
 * seeds per device per account (visible as duplicate `/d/reg` wire-log lines at the
 * same second).
 *
 * This guard coalesces those overlapping runs: the first caller wins and sets the
 * timestamp; every other caller within [MIN_INTERVAL_MS] is skipped. The purchase flow
 * (`PurchasesUpdatedListener` → `reconcileDidForCid`) does **not** go through this guard
 * and remains unaffected.
 *
 * ### Semantics
 * - [tryBegin] is atomic (CAS on the timestamp): exactly one concurrent caller proceeds.
 * - The timestamp is stamped at **begin**, not completion: a slow/hung check still blocks
 *   overlapping periodic runs for the window duration. Workers re-fire every 45 min, so
 *   a skipped run only delays the check by one period.
 * - Callers **must** call [end] in a `finally` block so a later run inside the window
 *   sees a stable timestamp (no-op today, kept for future end-stamping needs).
 */
object DeviceRegistrationGuard {

    private const val TAG = "DeviceRegistrationGuard"

    /** Minimum interval between two device-registration checks. */
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes

    private val lastRunStartMs = AtomicLong(0L)

    /**
     * Returns `true` (and stamps the current time) when the caller may proceed with a
     * device-registration check; `false` when a check already ran within [MIN_INTERVAL_MS].
     */
    fun tryBegin(caller: String): Boolean {
        val now = System.currentTimeMillis()
        while (true) {
            val last = lastRunStartMs.get()
            if (last != 0L && (now - last) < MIN_INTERVAL_MS) {
                Logger.i(LOG_IAB, "$TAG; $caller: skipped, a device-registration check " +
                    "ran ${now - last}ms ago (window=${MIN_INTERVAL_MS}ms)")
                return false
            }
            if (lastRunStartMs.compareAndSet(last, now)) {
                Logger.i(LOG_IAB, "$TAG; $caller: acquired, running device-registration check")
                return true
            }
            // Lost the race with another begin(); loop re-reads and re-evaluates.
        }
    }

    /** Marks the end of a device-registration check. Call from a `finally` block. */
    fun end(caller: String) {
        Logger.v(LOG_IAB, "$TAG; $caller: device-registration check ended")
    }
}
