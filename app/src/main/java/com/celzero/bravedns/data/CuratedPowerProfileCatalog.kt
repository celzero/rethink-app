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
    const val SAFE_BEAUTIFUL_INTERNET_ID = "safe-beautiful-internet"
    const val APP_HORSE_ID = "app-horse"
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
                sourceTokens = UBO_DEFAULT_TOKENS,
                readyForActivation = true
            ),
            PowerProfileDefinition(
                id = SAFE_BEAUTIFUL_INTERNET_ID,
                titleRes = R.string.power_profile_safe_beautiful_title,
                descriptionRes = R.string.power_profile_safe_beautiful_desc,
                metaRes = R.string.power_profile_safe_beautiful_meta,
                iconRes = R.drawable.ic_local_blocklist,
                localBlocklistTagIds =
                    listOf(
                        94, 95, 112, 113, 114, 122, 115, 116, 118, 119, 121, 123, 120, 131,
                        125, 107, 108, 124, 129, 110, 98, 99, 127, 100, 102, 103, 101, 132,
                        104, 105, 106, 126, 109, 111, 128, 93, 83, 65, 66, 55, 54, 56, 75,
                        68, 71, 72, 61, 64, 70, 69, 90, 88, 87, 58, 180, 178, 77, 195, 76,
                        89, 85, 91, 60, 97, 59, 81, 84, 74, 57, 82, 62, 79, 78, 63, 67, 92,
                        86, 80, 73, 189
                    ),
                sourceProvider = "Rethink DNS",
                sourceSummary =
                    "Samsung-curated local blocklist setup with 80 privacy and security selections.",
                sourceTokens = listOf("rethink-local-blocklists", "privacy", "security"),
                readyForActivation = true
            ),
            PowerProfileDefinition(
                id = APP_HORSE_ID,
                titleRes = R.string.power_profile_app_horse_title,
                descriptionRes = R.string.power_profile_app_horse_desc,
                metaRes = R.string.power_profile_app_horse_meta,
                iconRes = R.drawable.ic_app_info_accent,
                bundledArtifactAssetPath = "power/app-horse-profile.json",
                sourceProvider = "App Horse",
                sourceSummary = "Community app pack for Parrot Downloader with the validated ad-network IP blocklist.",
                sourceTokens = listOf("community-app-pack", "com.parrot.downloader", "ad-network"),
                readyForActivation = true
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
