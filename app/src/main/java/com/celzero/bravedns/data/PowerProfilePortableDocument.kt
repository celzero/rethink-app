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
import org.json.JSONArray
import org.json.JSONObject

data class PowerProfilePortableDocument(
    val id: String,
    val name: String,
    val description: String,
    val meta: String,
    val provider: String,
    val sourceSummary: String,
    val sourceDocUrl: String,
    val sourceTokens: List<String>,
    val generatedAtEpochMs: Long,
    val supportedRuleKind: String,
    val domains: List<String>,
    val ips: List<String>,
    val localBlocklistTagIds: List<Int>
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("meta", meta)
            put("provider", provider)
            put("sourceSummary", sourceSummary)
            put("sourceDocUrl", sourceDocUrl)
            put("generatedAtEpochMs", generatedAtEpochMs)
            put("supportedRuleKind", supportedRuleKind)
            put("sourceTokens", JSONArray(sourceTokens))
            put("domains", JSONArray(domains))
            put("ips", JSONArray(ips))
            put("localBlocklistTagIds", JSONArray(localBlocklistTagIds))
        }.toString()
    }

    fun toDefinition(localArtifactFileName: String): PowerProfileDefinition {
        return PowerProfileDefinition(
            id = id,
            titleText = name,
            descriptionText = description,
            metaText = meta,
            iconRes = R.drawable.ic_logs_accent,
            localArtifactFileName = localArtifactFileName,
            localBlocklistTagIds = localBlocklistTagIds,
            sourceProvider = provider,
            sourceSummary = sourceSummary,
            sourceDocUrl = sourceDocUrl,
            sourceTokens = sourceTokens,
            readyForActivation = true
        )
    }

    companion object {
        fun fromJson(raw: String): PowerProfilePortableDocument {
            val json = JSONObject(raw)
            val sourceTokensJson = json.optJSONArray("sourceTokens")
            val domainsJson = json.optJSONArray("domains")
            val ipsJson = json.optJSONArray("ips")
            val localBlocklistTagIdsJson = json.optJSONArray("localBlocklistTagIds")

            fun readStrings(array: JSONArray?): List<String> =
                buildList {
                    if (array != null) {
                        for (index in 0 until array.length()) {
                            val value = array.optString(index, "").trim()
                            if (value.isNotEmpty()) add(value)
                        }
                    }
                }

            return PowerProfilePortableDocument(
                id = json.optString("id", "").trim(),
                name = json.optString("name", "").trim(),
                description = json.optString("description", "").trim(),
                meta = json.optString("meta", "").trim(),
                provider = json.optString("provider", "").trim(),
                sourceSummary = json.optString("sourceSummary", "").trim(),
                sourceDocUrl = json.optString("sourceDocUrl", "").trim(),
                sourceTokens = readStrings(sourceTokensJson),
                generatedAtEpochMs = json.optLong("generatedAtEpochMs", 0L),
                supportedRuleKind = json.optString("supportedRuleKind", "").trim(),
                domains = readStrings(domainsJson),
                ips = readStrings(ipsJson),
                localBlocklistTagIds =
                    buildList {
                        if (localBlocklistTagIdsJson != null) {
                            for (index in 0 until localBlocklistTagIdsJson.length()) {
                                val value = localBlocklistTagIdsJson.optInt(index, Int.MIN_VALUE)
                                if (value != Int.MIN_VALUE) add(value)
                            }
                        }
                    }
            )
        }
    }
}
