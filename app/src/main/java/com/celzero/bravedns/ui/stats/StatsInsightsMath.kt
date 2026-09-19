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


/**
 * Pure math helpers for the Insights view. Kept free of Android types so the
 * normalization/bucketing rules are trivially unit-testable.
 */
object StatsInsightsMath {

    /**
     * Normalizes [values] against the largest value, so the largest item maps
     * to 1f and every other item to value/max. All-zero (or empty) input maps
     * to zeros — never divides by zero.
     */
    fun normalizeFractions(values: List<Long>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val max = values.max()
        if (max <= 0L) return values.map { 0f }
        return values.map { if (it <= 0L) 0f else it.toFloat() / max }
    }

}
