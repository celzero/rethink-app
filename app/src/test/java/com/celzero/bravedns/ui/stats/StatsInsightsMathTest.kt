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
package com.celzero.bravedns.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsInsightsMathTest {

    @Test
    fun `normalizeFractions - empty list returns empty`() {
        assertEquals(emptyList<Float>(), StatsInsightsMath.normalizeFractions(emptyList()))
    }

    @Test
    fun `normalizeFractions - single item maps to full bar`() {
        val out = StatsInsightsMath.normalizeFractions(listOf(42L))
        assertEquals(listOf(1f), out)
    }

    @Test
    fun `normalizeFractions - multiple items relative to max`() {
        val out = StatsInsightsMath.normalizeFractions(listOf(100L, 50L, 25L))
        assertEquals(listOf(1f, 0.5f, 0.25f), out)
    }

    @Test
    fun `normalizeFractions - all zeros does not divide by zero`() {
        val out = StatsInsightsMath.normalizeFractions(listOf(0L, 0L, 0L))
        assertEquals(listOf(0f, 0f, 0f), out)
    }

    @Test
    fun `normalizeFractions - negatives clamp to zero without dividing by zero`() {
        val out = StatsInsightsMath.normalizeFractions(listOf(0L, -5L))
        assertEquals(listOf(0f, 0f), out)
    }

}
