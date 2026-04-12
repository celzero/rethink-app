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

    const val SMOOTH_BROWSING_ID = "smooth-browsing"
    const val EXAM_ID = "exam"
    const val DEEP_FOCUS_ID = "deep-focus"

    private const val UBO_PROVIDER = "uBlock Origin"
    private const val UBO_DOC_URL =
        "https://github.com/gorhill/uBlock/wiki/Deploying-uBlock-Origin:-configuration"
    private const val UBO_DEFAULT_SUMMARY =
        "uBO default network lists: uBlock filters, EasyList, EasyPrivacy, URLHaus, and Peter Lowe."
    private val UBO_DEFAULT_TOKENS =
        listOf(
            "ublock-filters",
            "ublock-badware",
            "ublock-privacy",
            "ublock-abuse",
            "ublock-unbreak",
            "easylist",
            "easyprivacy",
            "urlhaus-1",
            "plowe-0"
        )

    val profiles: List<PowerProfileDefinition> =
        listOf(
            PowerProfileDefinition(
                id = SMOOTH_BROWSING_ID,
                titleRes = R.string.power_profile_smooth_browsing_title,
                descriptionRes = R.string.power_profile_smooth_browsing_desc,
                metaRes = R.string.power_profile_smooth_browsing_meta,
                iconRes = R.drawable.ic_logs_accent,
                bundledArtifactAssetPath = "power/smooth-browsing-domain-artifact.json",
                sourceProvider = UBO_PROVIDER,
                sourceSummary = UBO_DEFAULT_SUMMARY,
                sourceDocUrl = UBO_DOC_URL,
                sourceTokens = UBO_DEFAULT_TOKENS
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
