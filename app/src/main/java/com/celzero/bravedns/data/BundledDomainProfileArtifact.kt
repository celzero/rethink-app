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

import org.json.JSONObject

data class BundledDomainProfileArtifact(
    val profileId: String,
    val provider: String,
    val sourceDocUrl: String,
    val generatedAtEpochMs: Long,
    val supportedRuleKind: String,
    val domains: List<String>,
    val ips: List<String>
) {
    fun supportedRuleCount(): Int = domains.size + ips.size

    companion object {
        fun fromJson(raw: String): BundledDomainProfileArtifact {
            val json = JSONObject(raw)
            val domainsJson = json.optJSONArray("domains")
            val ipsJson = json.optJSONArray("ips")
            val domains =
                buildList {
                    if (domainsJson != null) {
                        for (index in 0 until domainsJson.length()) {
                            val domain = domainsJson.optString(index, "").trim().lowercase()
                            if (domain.isNotEmpty()) add(domain)
                        }
                    }
                }
            val ips =
                buildList {
                    if (ipsJson != null) {
                        for (index in 0 until ipsJson.length()) {
                            val ip = ipsJson.optString(index, "").trim().lowercase()
                            if (ip.isNotEmpty()) add(ip)
                        }
                    }
                }

            return BundledDomainProfileArtifact(
                profileId = json.optString("profileId", "").trim(),
                provider = json.optString("provider", "").trim(),
                sourceDocUrl = json.optString("sourceDocUrl", "").trim(),
                generatedAtEpochMs = json.optLong("generatedAtEpochMs", 0L),
                supportedRuleKind = json.optString("supportedRuleKind", "").trim(),
                domains = domains,
                ips = ips
            )
        }
    }
}
