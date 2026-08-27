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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogActivityWindowTest {

    private val tenMin = LogActivityWindow.TEN_MINUTES_MS
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val week = 7L * day

    // arbitrary mid-interval timestamp: e.g. 12:34:56.789 within its slot
    private val nowMs = 1_780_000_000_000L - (1_780_000_000_000L % tenMin) + 4 * 60_000L + 56_789L
    private val snappedNow = nowMs - (nowMs % tenMin)

    @Test
    fun `default preset covers the last ten minutes`() {
        val w = LogActivityWindow.fromPreset(LogActivityWindow.defaultPresetIndex(), nowMs)
        assertEquals(tenMin, w.endMs - w.startMs)
        assertEquals(snappedNow, w.endMs)
    }

    @Test
    fun `end snaps down to the current ten minute boundary`() {
        val w = LogActivityWindow.last(hour, nowMs)
        assertEquals(snappedNow, w.endMs)
        assertEquals(snappedNow - hour, w.startMs)
    }

    @Test
    fun `presets expose four ranges up to seven days`() {
        val presets = LogActivityWindow.presetDurations()
        assertEquals(4, presets.size)
        assertEquals(tenMin, presets[0])
        assertEquals(week, presets[3])
    }

    @Test
    fun `lookback is capped at seven days`() {
        val w = LogActivityWindow.last(30L * day, nowMs)
        assertEquals(LogActivityWindow.MAX_LOOKBACK_MS, w.endMs - w.startMs)

        val tooFar = LogActivityWindow.fromPreset(99, nowMs) // clamped to last preset
        assertEquals(week, tooFar.endMs - tooFar.startMs)
    }

    @Test
    fun `single interval spans exactly one ten minute slot`() {
        val start = snappedNow - 37 * tenMin
        val w = LogActivityWindow.singleInterval(start)
        assertEquals(start, w.startMs)
        assertEquals(start + tenMin, w.endMs)
    }

    @Test
    fun `snap floors negative timestamps to zero`() {
        assertEquals(0L, LogActivityWindow.snapToInterval(-12345L))
    }

    @Test
    fun `windows are half-open and non-overlapping for consecutive slots`() {
        val a = LogActivityWindow.singleInterval(snappedNow)
        val b = LogActivityWindow.singleInterval(snappedNow + tenMin)
        assertTrue(a.endMs <= b.startMs)
    }
}
