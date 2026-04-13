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

import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.util.Constants
import org.json.JSONObject

data class BundledDomainProfileArtifact(
    val profileId: String,
    val provider: String,
    val sourceDocUrl: String,
    val generatedAtEpochMs: Long,
    val supportedRuleKind: String,
    val domains: List<String>,
    val ips: List<String>,
    val apps: List<PowerProfileAppBlocklist> = emptyList()
) {
    fun supportedRuleCount(): Int = domains.size + ips.size + apps.sumOf { it.supportedRuleCount() }

    companion object {
        fun fromJson(raw: String): BundledDomainProfileArtifact {
            val json = JSONObject(raw)
            val domainsJson = json.optJSONArray("domains")
            val ipsJson = json.optJSONArray("ips")
            val appsJson = json.optJSONArray("apps")
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
            val apps =
                buildList {
                    if (appsJson != null) {
                        for (index in 0 until appsJson.length()) {
                            val appJson = appsJson.optJSONObject(index) ?: continue
                            val packageName = appJson.optString("packageName", "").trim()
                            val appName = appJson.optString("appName", "").trim()
                            if (packageName.isEmpty() || appName.isEmpty()) continue
                            val appDomainsJson = appJson.optJSONArray("domainRules")
                            val appIpsJson = appJson.optJSONArray("ipRules")
                            val domainRules =
                                buildList {
                                    if (appDomainsJson != null) {
                                        for (domainIndex in 0 until appDomainsJson.length()) {
                                            val domainJson = appDomainsJson.optJSONObject(domainIndex) ?: continue
                                            val domain = domainJson.optString("domain", "").trim().lowercase()
                                            if (domain.isEmpty()) continue
                                            add(
                                                PowerProfileAppDomainRule(
                                                    domain = domain,
                                                    status = domainJson.optInt("status", DomainRulesManager.Status.BLOCK.id),
                                                    type = domainJson.optInt("type", DomainRulesManager.DomainType.DOMAIN.id),
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
                                    if (appIpsJson != null) {
                                        for (ipIndex in 0 until appIpsJson.length()) {
                                            val ipJson = appIpsJson.optJSONObject(ipIndex) ?: continue
                                            val ipAddress = ipJson.optString("ipAddress", "").trim().lowercase()
                                            if (ipAddress.isEmpty()) continue
                                            add(
                                                PowerProfileAppIpRule(
                                                    ipAddress = ipAddress,
                                                    port = ipJson.optInt("port", Constants.UNSPECIFIED_PORT),
                                                    protocol = ipJson.optString("protocol", "").trim(),
                                                    status = ipJson.optInt("status", IpRulesManager.IpRuleStatus.BLOCK.id),
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
                                    firewallStatus =
                                        PowerProfileFirewallValue.sanitizeFirewallStatus(
                                            appJson.optInt(
                                                "firewallStatus",
                                                PowerProfileFirewallValue.FIREWALL_STATUS_NONE
                                            )
                                        ),
                                    connectionStatus =
                                        PowerProfileFirewallValue.sanitizeConnectionStatus(
                                            appJson.optInt(
                                                "connectionStatus",
                                                PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW
                                            )
                                        ),
                                    domainRules = domainRules,
                                    ipRules = ipRules
                                )
                            )
                        }
                    }
                }

            val sanitized =
                PowerProfileSecurity.sanitizeArtifact(
                    profileId = json.optString("profileId", "").trim(),
                    provider = json.optString("provider", "").trim(),
                    sourceDocUrl = json.optString("sourceDocUrl", "").trim(),
                    generatedAtEpochMs = json.optLong("generatedAtEpochMs", 0L),
                    supportedRuleKind = json.optString("supportedRuleKind", "").trim(),
                    domains = domains,
                    ips = ips,
                    apps = apps
                )

            return BundledDomainProfileArtifact(
                profileId = sanitized.profileId,
                provider = sanitized.provider,
                sourceDocUrl = sanitized.sourceDocUrl,
                generatedAtEpochMs = sanitized.generatedAtEpochMs,
                supportedRuleKind = sanitized.supportedRuleKind,
                domains = sanitized.domains,
                ips = sanitized.ips,
                apps = sanitized.apps
            )
        }
    }
}
