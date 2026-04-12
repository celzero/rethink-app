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
    val apps: List<PowerProfileAppBlocklist>,
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
            put(
                "apps",
                JSONArray().apply {
                    apps.forEach { app ->
                        put(
                            JSONObject().apply {
                                put("packageName", app.packageName)
                                put("appName", app.appName)
                                put("firewallStatus", app.firewallStatus)
                                put("connectionStatus", app.connectionStatus)
                                put(
                                    "domainRules",
                                    JSONArray().apply {
                                        app.domainRules.forEach { domainRule ->
                                            put(
                                                JSONObject().apply {
                                                    put("domain", domainRule.domain)
                                                    put("status", domainRule.status)
                                                    put("type", domainRule.type)
                                                    put("ips", domainRule.ips)
                                                    put("proxyId", domainRule.proxyId)
                                                    put("proxyCC", domainRule.proxyCC)
                                                }
                                            )
                                        }
                                    }
                                )
                                put(
                                    "ipRules",
                                    JSONArray().apply {
                                        app.ipRules.forEach { ipRule ->
                                            put(
                                                JSONObject().apply {
                                                    put("ipAddress", ipRule.ipAddress)
                                                    put("port", ipRule.port)
                                                    put("protocol", ipRule.protocol)
                                                    put("status", ipRule.status)
                                                    put("isActive", ipRule.isActive)
                                                    put("wildcard", ipRule.wildcard)
                                                    put("proxyId", ipRule.proxyId)
                                                    put("proxyCC", ipRule.proxyCC)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
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
            val appsJson = json.optJSONArray("apps")
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
                apps =
                    buildList {
                        if (appsJson != null) {
                            for (index in 0 until appsJson.length()) {
                                val appJson = appsJson.optJSONObject(index) ?: continue
                                val packageName = appJson.optString("packageName", "").trim()
                                val appName = appJson.optString("appName", "").trim()
                                if (packageName.isEmpty() || appName.isEmpty()) continue
                                val domainRules =
                                    buildList {
                                        val domainJsonArray = appJson.optJSONArray("domainRules")
                                        if (domainJsonArray != null) {
                                            for (domainIndex in 0 until domainJsonArray.length()) {
                                                val domainJson = domainJsonArray.optJSONObject(domainIndex) ?: continue
                                                val domain = domainJson.optString("domain", "").trim()
                                                if (domain.isEmpty()) continue
                                                add(
                                                    PowerProfileAppDomainRule(
                                                        domain = domain,
                                                        status = domainJson.optInt("status", 0),
                                                        type = domainJson.optInt("type", 0),
                                                        ips = domainJson.optString("ips", "").trim(),
                                                        proxyId = domainJson.optString("proxyId", "").trim(),
                                                        proxyCC = domainJson.optString("proxyCC", "").trim()
                                                    )
                                                )
                                            }
                                        }
                                    }
                                val ipRules =
                                    buildList {
                                        val ipJsonArray = appJson.optJSONArray("ipRules")
                                        if (ipJsonArray != null) {
                                            for (ipIndex in 0 until ipJsonArray.length()) {
                                                val ipJson = ipJsonArray.optJSONObject(ipIndex) ?: continue
                                                val ipAddress = ipJson.optString("ipAddress", "").trim()
                                                if (ipAddress.isEmpty()) continue
                                                add(
                                                    PowerProfileAppIpRule(
                                                        ipAddress = ipAddress,
                                                        port = ipJson.optInt("port", 0),
                                                        protocol = ipJson.optString("protocol", "").trim(),
                                                        status = ipJson.optInt("status", 0),
                                                        isActive = ipJson.optBoolean("isActive", true),
                                                        wildcard = ipJson.optBoolean("wildcard", false),
                                                        proxyId = ipJson.optString("proxyId", "").trim(),
                                                        proxyCC = ipJson.optString("proxyCC", "").trim()
                                                    )
                                                )
                                            }
                                        }
                                    }
                                add(
                                    PowerProfileAppBlocklist(
                                        packageName = packageName,
                                        appName = appName,
                                        firewallStatus = appJson.optInt("firewallStatus", 0),
                                        connectionStatus = appJson.optInt("connectionStatus", 0),
                                        domainRules = domainRules,
                                        ipRules = ipRules
                                    )
                                )
                            }
                        }
                    },
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
