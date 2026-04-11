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
package com.celzero.bravedns.data

import com.celzero.bravedns.R

object CuratedPowerProfileCatalog {

    const val SMOOTH_INTERNET_ID = "smooth-internet"
    const val EXAM_ID = "exam"
    const val DEEP_FOCUS_ID = "deep-focus"

    val profiles: List<PowerProfileDefinition> =
        listOf(
            PowerProfileDefinition(
                id = SMOOTH_INTERNET_ID,
                titleRes = R.string.power_profile_smooth_internet_title,
                descriptionRes = R.string.power_profile_smooth_internet_desc,
                metaRes = R.string.power_profile_smooth_internet_meta,
                iconRes = R.drawable.ic_logs_accent
            ),
            PowerProfileDefinition(
                id = EXAM_ID,
                titleRes = R.string.power_profile_exam_title,
                descriptionRes = R.string.power_profile_exam_desc,
                metaRes = R.string.power_profile_exam_meta,
                iconRes = R.drawable.ic_statistics
            ),
            PowerProfileDefinition(
                id = DEEP_FOCUS_ID,
                titleRes = R.string.power_profile_focus_title,
                descriptionRes = R.string.power_profile_focus_desc,
                metaRes = R.string.power_profile_focus_meta,
                iconRes = R.drawable.ic_firewall_shield
            )
        )

    fun get(id: String): PowerProfileDefinition? {
        return profiles.firstOrNull { it.id == id }
    }
}
