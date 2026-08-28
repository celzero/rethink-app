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

/**
 * A half-open [startMs, endMs) time window snapped to ten-minute interval
 * boundaries, used by the activity detail sheet. The default window is the
 * last 10 minutes; users can look back up to 7 days at 10-minute granularity
 * ([MAX_LOOKBACK_MS]).
 */
data class LogActivityWindow(
    val startMs: Long,
    val endMs: Long
) {

    companion object {
        const val TEN_MINUTES_MS = 10L * 60L * 1000L

        // selectable history cap: 7 days of 10-minute intervals
        const val MAX_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1000L

        private val PRESETS_MS = longArrayOf(
            TEN_MINUTES_MS,             // 10 minutes (default)
            60L * 60L * 1000L,          // 1 hour
            24L * 60L * 60L * 1000L,    // 24 hours
            MAX_LOOKBACK_MS             // 7 days
        )

        fun presetDurations(): LongArray = PRESETS_MS.copyOf()

        fun defaultPresetIndex(): Int = 0

        /**
         * Window for preset [presetIndex] ending at the ten-minute boundary at or
         * before [nowMs]. Clamped to [MAX_LOOKBACK_MS].
         */
        fun fromPreset(presetIndex: Int, nowMs: Long): LogActivityWindow {
            val durations = PRESETS_MS
            val idx = presetIndex.coerceIn(0, durations.size - 1)
            return last(durations[idx], nowMs)
        }

        /**
         * Window covering the [durationMs] leading up to the ten-minute boundary
         * at or before [nowMs].
         */
        fun last(durationMs: Long, nowMs: Long): LogActivityWindow {
            val duration = durationMs.coerceIn(TEN_MINUTES_MS, MAX_LOOKBACK_MS)
            val end = snapToInterval(nowMs)
            return LogActivityWindow(end - duration, end)
        }

        /**
         * Single ten-minute interval whose start is [intervalStartMs].
         */
        fun singleInterval(intervalStartMs: Long): LogActivityWindow {
            return LogActivityWindow(intervalStartMs, intervalStartMs + TEN_MINUTES_MS)
        }

        /**
         * Floors [timestampMs] onto its ten-minute interval start.
         */
        fun snapToInterval(timestampMs: Long): Long {
            if (timestampMs < 0) return 0
            return timestampMs - (timestampMs % TEN_MINUTES_MS)
        }
    }
}
