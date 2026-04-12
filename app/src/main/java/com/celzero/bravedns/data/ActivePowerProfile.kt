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

import org.json.JSONArray
import org.json.JSONObject

data class ActivePowerProfile(
    val id: String,
    val name: String,
    val provider: String,
    val sourceSummary: String,
    val sourceDocUrl: String,
    val sourceTokens: List<String>,
    val activatedAt: Long,
    val importedRuleCount: Int = 0,
    val alreadyPresentRuleCount: Int = 0,
    val skippedExistingRuleCount: Int = 0,
    val artifactRuleCount: Int = 0,
    val supportedRuleKind: String = "",
    val artifactGeneratedAtEpochMs: Long = 0L
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("provider", provider)
            put("sourceSummary", sourceSummary)
            put("sourceDocUrl", sourceDocUrl)
            put("activatedAt", activatedAt)
            put("importedRuleCount", importedRuleCount)
            put("alreadyPresentRuleCount", alreadyPresentRuleCount)
            put("skippedExistingRuleCount", skippedExistingRuleCount)
            put("artifactRuleCount", artifactRuleCount)
            put("supportedRuleKind", supportedRuleKind)
            put("artifactGeneratedAtEpochMs", artifactGeneratedAtEpochMs)
            put("sourceTokens", JSONArray(sourceTokens))
        }
    }

    companion object {
        fun fromJson(json: JSONObject?): ActivePowerProfile? {
            if (json == null) return null

            val id = json.optString("id", "").trim()
            val name = json.optString("name", "").trim()
            val provider = json.optString("provider", "").trim()
            val sourceSummary = json.optString("sourceSummary", "").trim()
            val sourceDocUrl = json.optString("sourceDocUrl", "").trim()
            val activatedAt = json.optLong("activatedAt", 0L)
            val importedRuleCount = json.optInt("importedRuleCount", 0)
            val alreadyPresentRuleCount = json.optInt("alreadyPresentRuleCount", 0)
            val skippedExistingRuleCount = json.optInt("skippedExistingRuleCount", 0)
            val artifactRuleCount = json.optInt("artifactRuleCount", 0)
            val supportedRuleKind = json.optString("supportedRuleKind", "").trim()
            val artifactGeneratedAtEpochMs = json.optLong("artifactGeneratedAtEpochMs", 0L)
            val sourceTokensJson = json.optJSONArray("sourceTokens")
            val sourceTokens =
                buildList {
                    if (sourceTokensJson != null) {
                        for (index in 0 until sourceTokensJson.length()) {
                            val token = sourceTokensJson.optString(index, "").trim()
                            if (token.isNotEmpty()) add(token)
                        }
                    }
                }

            if (id.isEmpty() || name.isEmpty() || activatedAt <= 0L) return null

            return ActivePowerProfile(
                id = id,
                name = name,
                provider = provider,
                sourceSummary = sourceSummary,
                sourceDocUrl = sourceDocUrl,
                sourceTokens = sourceTokens,
                activatedAt = activatedAt,
                importedRuleCount = importedRuleCount,
                alreadyPresentRuleCount = alreadyPresentRuleCount,
                skippedExistingRuleCount = skippedExistingRuleCount,
                artifactRuleCount = artifactRuleCount,
                supportedRuleKind = supportedRuleKind,
                artifactGeneratedAtEpochMs = artifactGeneratedAtEpochMs
            )
        }

        fun parseStorageList(storageString: String): List<ActivePowerProfile> {
            if (storageString.isBlank()) return emptyList()

            return try {
                val jsonArray = JSONArray(storageString)
                (0 until jsonArray.length()).mapNotNull { index ->
                    fromJson(jsonArray.optJSONObject(index))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toStorageList(profiles: List<ActivePowerProfile>): String {
            val jsonArray = JSONArray()
            profiles.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }
    }
}
