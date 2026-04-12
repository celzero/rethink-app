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

import android.content.Context
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.util.Constants
import inet.ipaddr.IPAddressString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PowerProfileImportSummary(
    val importedCount: Int,
    val alreadyBlockedCount: Int,
    val skippedExistingCount: Int,
    val artifactRuleCount: Int,
    val supportedRuleKind: String,
    val artifactGeneratedAtEpochMs: Long
)

data class PowerProfileImportResult(
    val summary: PowerProfileImportSummary,
    val ownedRules: PowerProfileOwnedRules
)

object PowerProfileImportManager : KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()
    private val customDomainRepository by inject<CustomDomainRepository>()
    private val customIpRepository by inject<CustomIpRepository>()

    suspend fun importBundledRules(
        context: Context,
        profile: PowerProfileDefinition,
        currentlyManagedRules: PowerProfileOwnedRules = PowerProfileOwnedRules.empty()
    ): PowerProfileImportResult? {
        val artifact = PowerProfileArtifacts.loadArtifact(context, profile) ?: return null
        if (artifact.domains.isEmpty() && artifact.ips.isEmpty() && artifact.apps.isEmpty()) {
            return PowerProfileImportResult(
                PowerProfileImportSummary(
                    0,
                    0,
                    0,
                    0,
                    artifact.supportedRuleKind,
                    artifact.generatedAtEpochMs
                ),
                PowerProfileOwnedRules.empty()
            )
        }

        val existingRules =
            customDomainRepository
                .getDomainsByUID(Constants.UID_EVERYBODY)
                .associateBy { it.domain.lowercase() }
        val existingIpRules =
            customIpRepository
                .getRulesByUid(Constants.UID_EVERYBODY)
                .associateBy { it.ipAddress.lowercase() }

        val domainsToInsert = mutableListOf<CustomDomain>()
        val ipsToInsert = mutableListOf<CustomIp>()
        val ownedDomains = linkedSetOf<String>()
        val ownedIps = linkedSetOf<String>()
        val ownedAppDomains = linkedSetOf<PowerProfileOwnedAppDomainRule>()
        val ownedAppIps = linkedSetOf<PowerProfileOwnedAppIpRule>()
        var alreadyBlockedCount = 0
        var skippedExistingCount = 0
        val now = System.currentTimeMillis()

        artifact.domains.forEach { domain ->
            val existing = existingRules[domain]
            when {
                existing == null -> {
                    ownedDomains.add(domain)
                    domainsToInsert.add(
                        CustomDomain(
                            domain = domain,
                            uid = Constants.UID_EVERYBODY,
                            ips = "",
                            type = DomainRulesManager.DomainType.DOMAIN.id,
                            status = DomainRulesManager.Status.BLOCK.id,
                            proxyId = "",
                            proxyCC = "",
                            modifiedTs = now,
                            deletedTs = 0L,
                            version = CustomDomain.getCurrentVersion()
                        )
                    )
                }
                existing.status == DomainRulesManager.Status.BLOCK.id -> {
                    alreadyBlockedCount += 1
                    if (domain in currentlyManagedRules.domains) ownedDomains.add(domain)
                }
                else -> skippedExistingCount += 1
            }
        }

        artifact.ips.forEach { ipAddress ->
            val normalizedIp = normalizeIp(ipAddress) ?: return@forEach
            val existing = existingIpRules[normalizedIp]
            when {
                existing == null -> {
                    ownedIps.add(normalizedIp)
                    ipsToInsert.add(
                        CustomIp().apply {
                            uid = Constants.UID_EVERYBODY
                            this.ipAddress = normalizedIp
                            status = IpRulesManager.IpRuleStatus.BLOCK.id
                            port = Constants.UNSPECIFIED_PORT
                            protocol = ""
                            isActive = true
                            wildcard = false
                            proxyId = ""
                            proxyCC = ""
                            modifiedDateTime = now
                            ruleType =
                                if (normalizedIp.contains(":")) {
                                    IpRulesManager.IPRuleType.IPV6.id
                                } else {
                                    IpRulesManager.IPRuleType.IPV4.id
                                }
                        }
                    )
                }
                existing.status == IpRulesManager.IpRuleStatus.BLOCK.id -> {
                    alreadyBlockedCount += 1
                    if (normalizedIp in currentlyManagedRules.ips) ownedIps.add(normalizedIp)
                }
                else -> skippedExistingCount += 1
            }
        }

        artifact.apps.forEach { app ->
            val targetUid = appInfoRepository.getAppInfoUidForPackageName(app.packageName)
            if (targetUid <= 0) {
                skippedExistingCount += app.supportedRuleCount()
                return@forEach
            }
            val existingAppDomains =
                customDomainRepository.getDomainsByUID(targetUid).associateBy { it.domain.lowercase() }
            val existingAppIps =
                customIpRepository.getRulesByUid(targetUid).associateBy {
                    "${it.ipAddress.lowercase()}|${it.port}"
                }

            app.domainRules.forEach { appDomain ->
                val normalizedDomain = appDomain.domain.trim().trimEnd('.').lowercase()
                if (normalizedDomain.isEmpty()) return@forEach
                val existing = existingAppDomains[normalizedDomain]
                val ownedRule = PowerProfileOwnedAppDomainRule(app.packageName, normalizedDomain)
                when {
                    existing == null -> {
                        ownedAppDomains.add(ownedRule)
                        domainsToInsert.add(
                            CustomDomain(
                                domain = normalizedDomain,
                                uid = targetUid,
                                ips = appDomain.ips,
                                type = appDomain.type,
                                status = appDomain.status,
                                proxyId = appDomain.proxyId,
                                proxyCC = appDomain.proxyCC,
                                modifiedTs = now,
                                deletedTs = 0L,
                                version = CustomDomain.getCurrentVersion()
                            )
                        )
                    }
                    existing.status == DomainRulesManager.Status.BLOCK.id -> {
                        alreadyBlockedCount += 1
                        if (currentlyManagedRules.appDomains.any { it.key() == ownedRule.key() }) {
                            ownedAppDomains.add(ownedRule)
                        }
                    }
                    else -> skippedExistingCount += 1
                }
            }

            app.ipRules.forEach { appIp ->
                val normalizedIp = normalizeIp(appIp.ipAddress) ?: return@forEach
                val ownedRule = PowerProfileOwnedAppIpRule(app.packageName, normalizedIp, appIp.port)
                val existing = existingAppIps["$normalizedIp|${appIp.port}"]
                when {
                    existing == null -> {
                        ownedAppIps.add(ownedRule)
                        ipsToInsert.add(
                            CustomIp().apply {
                                uid = targetUid
                                ipAddress = normalizedIp
                                status = appIp.status
                                port = appIp.port
                                protocol = appIp.protocol
                                isActive = appIp.isActive
                                wildcard = appIp.wildcard
                                proxyId = appIp.proxyId
                                proxyCC = appIp.proxyCC
                                modifiedDateTime = now
                                ruleType =
                                    if (normalizedIp.contains(":")) {
                                        IpRulesManager.IPRuleType.IPV6.id
                                    } else {
                                        IpRulesManager.IPRuleType.IPV4.id
                                    }
                            }
                        )
                    }
                    existing.status == IpRulesManager.IpRuleStatus.BLOCK.id -> {
                        alreadyBlockedCount += 1
                        if (currentlyManagedRules.appIps.any { it.key() == ownedRule.key() }) {
                            ownedAppIps.add(ownedRule)
                        }
                    }
                    else -> skippedExistingCount += 1
                }
            }
        }

        customDomainRepository.insertAll(domainsToInsert)
        customIpRepository.insertAll(ipsToInsert)
        if (domainsToInsert.isNotEmpty()) {
            DomainRulesManager.load()
        }
        if (ipsToInsert.isNotEmpty()) {
            IpRulesManager.load()
        }

        return PowerProfileImportResult(
            summary =
                PowerProfileImportSummary(
                    importedCount = domainsToInsert.size + ipsToInsert.size,
                    alreadyBlockedCount = alreadyBlockedCount,
                    skippedExistingCount = skippedExistingCount,
                    artifactRuleCount = artifact.supportedRuleCount(),
                    supportedRuleKind = artifact.supportedRuleKind,
                    artifactGeneratedAtEpochMs = artifact.generatedAtEpochMs
                ),
            ownedRules =
                PowerProfileOwnedRules(
                    domains = ownedDomains.toList(),
                    ips = ownedIps.toList(),
                    appDomains = ownedAppDomains.toList(),
                    appIps = ownedAppIps.toList()
                )
        )
    }

    private fun normalizeIp(ipAddress: String): String? {
        return try {
            IPAddressString(ipAddress).address?.toNormalizedString()
        } catch (_: Exception) {
            null
        }
    }
}
