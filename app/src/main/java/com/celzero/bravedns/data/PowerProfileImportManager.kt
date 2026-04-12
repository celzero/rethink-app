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

object PowerProfileImportManager : KoinComponent {

    private val customDomainRepository by inject<CustomDomainRepository>()
    private val customIpRepository by inject<CustomIpRepository>()

    suspend fun importBundledRules(
        context: Context,
        profile: PowerProfileDefinition
    ): PowerProfileImportSummary? {
        val assetPath = profile.bundledArtifactAssetPath ?: return null
        val raw = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val artifact = BundledDomainProfileArtifact.fromJson(raw)
        if (artifact.domains.isEmpty() && artifact.ips.isEmpty()) {
            return PowerProfileImportSummary(0, 0, 0, 0, artifact.supportedRuleKind, artifact.generatedAtEpochMs)
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
        var alreadyBlockedCount = 0
        var skippedExistingCount = 0
        val now = System.currentTimeMillis()

        artifact.domains.forEach { domain ->
            val existing = existingRules[domain]
            when {
                existing == null -> {
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
                existing.status == DomainRulesManager.Status.BLOCK.id -> alreadyBlockedCount += 1
                else -> skippedExistingCount += 1
            }
        }

        artifact.ips.forEach { ipAddress ->
            val normalizedIp = normalizeIp(ipAddress) ?: return@forEach
            val existing = existingIpRules[normalizedIp]
            when {
                existing == null -> {
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
                existing.status == IpRulesManager.IpRuleStatus.BLOCK.id -> alreadyBlockedCount += 1
                else -> skippedExistingCount += 1
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

        return PowerProfileImportSummary(
            importedCount = domainsToInsert.size + ipsToInsert.size,
            alreadyBlockedCount = alreadyBlockedCount,
            skippedExistingCount = skippedExistingCount,
            artifactRuleCount = artifact.domains.size + artifact.ips.size,
            supportedRuleKind = artifact.supportedRuleKind,
            artifactGeneratedAtEpochMs = artifact.generatedAtEpochMs
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
