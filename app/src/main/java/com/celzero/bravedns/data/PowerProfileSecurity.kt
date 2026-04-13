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
import inet.ipaddr.IPAddressString

object PowerProfileSecurity {
    const val MAX_PROFILE_BYTES = 2 * 1024 * 1024
    private const val MAX_ID_LENGTH = 80
    private const val MAX_SHORT_TEXT_LENGTH = 120
    private const val MAX_MEDIUM_TEXT_LENGTH = 280
    private const val MAX_LONG_TEXT_LENGTH = 2048
    private const val MAX_SOURCE_TOKENS = 64
    private const val MAX_GLOBAL_DOMAINS = 150_000
    private const val MAX_GLOBAL_IPS = 25_000
    private const val MAX_APPS = 256
    private const val MAX_APP_DOMAIN_RULES = 10_000
    private const val MAX_APP_IP_RULES = 10_000
    private const val MAX_LOCAL_BLOCKLIST_TAGS = 512

    private val profileIdRegex = Regex("^[a-z0-9._-]{1,80}$")
    private val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val domainRegex =
        Regex("^(\\*\\.)?([a-z0-9-]+\\.)*[a-z0-9-]+$")
    private val allowedProtocols = setOf("", "tcp", "udp")

    data class SanitizedPortableProfile(
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
    )

    data class SanitizedArtifact(
        val profileId: String,
        val provider: String,
        val sourceDocUrl: String,
        val generatedAtEpochMs: Long,
        val supportedRuleKind: String,
        val domains: List<String>,
        val ips: List<String>,
        val apps: List<PowerProfileAppBlocklist>
    )

    fun sanitizePortableProfile(
        id: String,
        name: String,
        description: String,
        meta: String,
        provider: String,
        sourceSummary: String,
        sourceDocUrl: String,
        sourceTokens: List<String>,
        generatedAtEpochMs: Long,
        supportedRuleKind: String,
        domains: List<String>,
        ips: List<String>,
        apps: List<PowerProfileAppBlocklist>,
        localBlocklistTagIds: List<Int>
    ): SanitizedPortableProfile {
        return SanitizedPortableProfile(
            id = sanitizeId(id),
            name = sanitizeText(name, MAX_SHORT_TEXT_LENGTH),
            description = sanitizeText(description, MAX_MEDIUM_TEXT_LENGTH),
            meta = sanitizeText(meta, MAX_MEDIUM_TEXT_LENGTH),
            provider = sanitizeText(provider, MAX_SHORT_TEXT_LENGTH),
            sourceSummary = sanitizeText(sourceSummary, MAX_LONG_TEXT_LENGTH),
            sourceDocUrl = sanitizeText(sourceDocUrl, MAX_LONG_TEXT_LENGTH),
            sourceTokens = sanitizeTokens(sourceTokens),
            generatedAtEpochMs = generatedAtEpochMs.coerceAtLeast(0L),
            supportedRuleKind = sanitizeText(supportedRuleKind, MAX_SHORT_TEXT_LENGTH),
            domains = sanitizeDomains(domains, MAX_GLOBAL_DOMAINS),
            ips = sanitizeIps(ips, MAX_GLOBAL_IPS),
            apps = sanitizeApps(apps),
            localBlocklistTagIds = sanitizeLocalBlocklistTagIds(localBlocklistTagIds)
        )
    }

    fun sanitizeArtifact(
        profileId: String,
        provider: String,
        sourceDocUrl: String,
        generatedAtEpochMs: Long,
        supportedRuleKind: String,
        domains: List<String>,
        ips: List<String>,
        apps: List<PowerProfileAppBlocklist>
    ): SanitizedArtifact {
        return SanitizedArtifact(
            profileId = sanitizeId(profileId),
            provider = sanitizeText(provider, MAX_SHORT_TEXT_LENGTH),
            sourceDocUrl = sanitizeText(sourceDocUrl, MAX_LONG_TEXT_LENGTH),
            generatedAtEpochMs = generatedAtEpochMs.coerceAtLeast(0L),
            supportedRuleKind = sanitizeText(supportedRuleKind, MAX_SHORT_TEXT_LENGTH),
            domains = sanitizeDomains(domains, MAX_GLOBAL_DOMAINS),
            ips = sanitizeIps(ips, MAX_GLOBAL_IPS),
            apps = sanitizeApps(apps)
        )
    }

    private fun sanitizeApps(apps: List<PowerProfileAppBlocklist>): List<PowerProfileAppBlocklist> {
        val deduped = linkedMapOf<String, PowerProfileAppBlocklist>()
        apps.take(MAX_APPS).forEach { app ->
            val packageName = sanitizePackageName(app.packageName) ?: return@forEach
            val appName = sanitizeText(app.appName, MAX_SHORT_TEXT_LENGTH).ifBlank { packageName }
            val firewallStatus =
                PowerProfileFirewallValue.sanitizeFirewallStatus(app.firewallStatus)
            val connectionStatus =
                PowerProfileFirewallValue.sanitizeConnectionStatus(app.connectionStatus)
            val domainRules =
                app.domainRules
                    .mapNotNull { sanitizeAppDomainRule(it) }
                    .distinctBy { "${it.domain}|${it.type}|${it.status}|${it.proxyId}|${it.proxyCC}" }
                    .take(MAX_APP_DOMAIN_RULES)
            val ipRules =
                app.ipRules
                    .mapNotNull { sanitizeAppIpRule(it) }
                    .distinctBy { "${it.ipAddress}|${it.port}|${it.protocol}|${it.status}|${it.wildcard}" }
                    .take(MAX_APP_IP_RULES)
            deduped[packageName] =
                PowerProfileAppBlocklist(
                    packageName = packageName,
                    appName = appName,
                    firewallStatus = firewallStatus,
                    connectionStatus = connectionStatus,
                    domainRules = domainRules,
                    ipRules = ipRules
                )
        }
        return deduped.values.toList()
    }

    private fun sanitizeAppDomainRule(rule: PowerProfileAppDomainRule): PowerProfileAppDomainRule? {
        val domain = sanitizeDomain(rule.domain) ?: return null
        return PowerProfileAppDomainRule(
            domain = domain,
            status = DomainRulesManager.Status.getStatus(rule.status).id,
            type = DomainRulesManager.DomainType.getType(rule.type).id,
            ips = sanitizeText(rule.ips, MAX_LONG_TEXT_LENGTH),
            proxyId = sanitizeText(rule.proxyId, MAX_SHORT_TEXT_LENGTH),
            proxyCC = sanitizeText(rule.proxyCC, 8)
        )
    }

    private fun sanitizeAppIpRule(rule: PowerProfileAppIpRule): PowerProfileAppIpRule? {
        val ipAddress = normalizeIp(rule.ipAddress) ?: return null
        return PowerProfileAppIpRule(
            ipAddress = ipAddress,
            port = sanitizePort(rule.port),
            protocol = sanitizeProtocol(rule.protocol),
            status = IpRulesManager.IpRuleStatus.getStatus(rule.status).id,
            isActive = rule.isActive,
            wildcard = rule.wildcard,
            proxyId = sanitizeText(rule.proxyId, MAX_SHORT_TEXT_LENGTH),
            proxyCC = sanitizeText(rule.proxyCC, 8)
        )
    }

    private fun sanitizeId(value: String): String {
        val normalized = value.trim().lowercase().take(MAX_ID_LENGTH)
        return if (profileIdRegex.matches(normalized)) normalized else ""
    }

    private fun sanitizePackageName(value: String): String? {
        val normalized = value.trim().take(255)
        return if (packageRegex.matches(normalized)) normalized else null
    }

    private fun sanitizeTokens(values: List<String>): List<String> {
        return values
            .map { sanitizeText(it, MAX_SHORT_TEXT_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SOURCE_TOKENS)
    }

    private fun sanitizeDomains(values: List<String>, maxCount: Int): List<String> {
        return values
            .mapNotNull { sanitizeDomain(it) }
            .distinct()
            .take(maxCount)
    }

    private fun sanitizeIps(values: List<String>, maxCount: Int): List<String> {
        return values
            .mapNotNull { normalizeIp(it) }
            .distinct()
            .take(maxCount)
    }

    private fun sanitizeDomain(value: String): String? {
        val normalized = value.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty() || normalized.length > 253) return null
        if (!domainRegex.matches(normalized)) return null
        if (normalized.contains("..")) return null
        return normalized
    }

    private fun normalizeIp(value: String): String? {
        return try {
            IPAddressString(value.trim()).address?.toNormalizedString()
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeProtocol(value: String): String {
        val normalized = value.trim().lowercase()
        return if (normalized in allowedProtocols) normalized else ""
    }

    private fun sanitizePort(value: Int): Int {
        return if (value in Constants.UNSPECIFIED_PORT..65535) value else Constants.UNSPECIFIED_PORT
    }

    private fun sanitizeLocalBlocklistTagIds(values: List<Int>): List<Int> {
        return values.filter { it >= 0 }.distinct().take(MAX_LOCAL_BLOCKLIST_TAGS)
    }

    private fun sanitizeText(value: String, maxLength: Int): String {
        return value.trim().take(maxLength)
    }
}
