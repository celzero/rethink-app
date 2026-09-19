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
 * Presentation mode of the Stats screen. Both modes render the same
 * [SummaryStatisticsViewModel] state; only the presentation differs.
 */
enum class StatsViewMode(val id: Int) {
    LIST(0),
    INSIGHTS(1);

    companion object {
        fun fromId(id: Int): StatsViewMode {
            return entries.find { it.id == id } ?: LIST
        }
    }
}
