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
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PowerProfileDisableSummary(
    val removedRuleCount: Int
)

data class PowerProfileLocalBlocklistRuntimeState(
    val tagId: Int,
    val effectiveInCurrentSetup: Boolean,
    val disabledInCurrentSetup: Boolean,
    val ownerProfiles: List<ActivePowerProfile>
)

data class PowerProfileRuleRuntimeState(
    val effectiveInCurrentSetup: Boolean,
    val disabledInCurrentSetup: Boolean,
    val ownerProfiles: List<ActivePowerProfile>
)

object PowerProfileManager : KoinComponent {
    private const val LOCAL_BLOCKLIST_STAMP_RETRY_ATTEMPTS = 20
    private const val LOCAL_BLOCKLIST_STAMP_RETRY_DELAY_MS = 250L

    private val appInfoRepository by inject<AppInfoRepository>()
    private val customDomainRepository by inject<CustomDomainRepository>()
    private val customIpRepository by inject<CustomIpRepository>()
    private val persistentState by inject<PersistentState>()
    internal var computeLocalBlocklistStamp: suspend (Set<Int>) -> String =
        { selectedTagIds ->
            RethinkBlocklistManager.getStamp(
                selectedTagIds,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            )
        }
    internal var waitBeforeRetry: suspend (Long) -> Unit = { delay(it) }
    internal var syncLocalBlocklistSelections: suspend (Set<Int>) -> Unit =
        { selectedTagIds ->
            RethinkBlocklistManager.clearTagsSelectionLocal()
            if (selectedTagIds.isNotEmpty()) {
                RethinkBlocklistManager.updateFiletagsLocal(selectedTagIds, 1)
            }
        }
    internal var applyAppFirewallRule: suspend (Int, Int, Int) -> Unit = ::defaultApplyAppFirewallRule
    internal var reloadDomainRules: suspend () -> Unit = ::defaultReloadDomainRules
    internal var reloadIpRules: suspend () -> Unit = ::defaultReloadIpRules

    suspend fun reconcileActiveProfiles(context: Context): Boolean {
        val activeProfiles = PowerProfileStore.listActiveProfiles(context)
        if (activeProfiles.isEmpty()) return false

        val activeNativeProfiles =
            activeProfiles.filter {
                PowerProfileOwnershipStore.read(context, it.id).localBlocklistTagIds.isNotEmpty()
            }
        if (activeNativeProfiles.isEmpty()) return false

        val currentSelectedLocalBlocklists =
            RethinkBlocklistManager.getTagsFromStamp(
                persistentState.localBlocklistStamp,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            )
        val desiredLocalBlocklists =
            activeNativeProfiles
                .flatMap { PowerProfileOwnershipStore.read(context, it.id).localBlocklistTagIds }
                .toSet()
        val disabledLocalBlocklists =
            PowerProfileCurrentSetupOverrideStore.read(context).disabledLocalBlocklistTagIds.toSet()
        val effectiveLocalBlocklists =
            computeEffectiveLocalBlocklists(
                context,
                desiredLocalBlocklists,
                disabledLocalBlocklistTagIds = disabledLocalBlocklists
            )

        val nativeStateAlreadyAligned =
            if (effectiveLocalBlocklists.isEmpty()) {
                !persistentState.blocklistEnabled &&
                    currentSelectedLocalBlocklists.isEmpty() &&
                    persistentState.numberOfLocalBlocklists == 0
            } else {
                persistentState.blocklistEnabled &&
                    persistentState.localBlocklistStamp.isNotBlank() &&
                    persistentState.numberOfLocalBlocklists == currentSelectedLocalBlocklists.size &&
                    currentSelectedLocalBlocklists.containsAll(effectiveLocalBlocklists) &&
                    effectiveLocalBlocklists.containsAll(currentSelectedLocalBlocklists)
            }

        if (nativeStateAlreadyAligned) return false

        val reconciledSelection =
            applySetupOverrideToCurrentSelection(
                currentSelectedLocalBlocklists,
                effectiveLocalBlocklists,
                disabledLocalBlocklists
            )
        val applied = applyLocalBlocklistSelection(reconciledSelection)
        if (applied) return true

        activeNativeProfiles.forEach { activeProfile ->
            PowerProfileStore.deactivateProfile(context, activeProfile.id)
        }
        return true
    }

    suspend fun enableProfile(context: Context, profile: PowerProfileDefinition): ActivePowerProfile? {
        if (!profile.readyForActivation) return null

        val currentActiveIds = PowerProfileStore.listActiveProfiles(context).map { it.id }
        val existingManagedRules =
            PowerProfileOwnershipStore.aggregateOwnership(context, currentActiveIds)
        val importResult =
            PowerProfileImportManager.importBundledRules(
                context = context,
                profile = profile,
                currentlyManagedRules = existingManagedRules
            )
                ?: PowerProfileImportResult(
                    summary = PowerProfileImportSummary(0, 0, 0, 0, "", 0L),
                    ownedRules = PowerProfileOwnedRules.empty()
                )

        if (importResult.summary.artifactRuleCount == 0 && profile.localBlocklistTagIds.isEmpty()) {
            return null
        }

        val currentSelectedLocalBlocklists =
            RethinkBlocklistManager.getTagsFromStamp(
                persistentState.localBlocklistStamp,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            )
        val profileLocalBlocklists = profile.localBlocklistTagIds.toSet()
        val disabledLocalBlocklists =
            PowerProfileCurrentSetupOverrideStore.read(context).disabledLocalBlocklistTagIds.toSet()
        val desiredLocalBlocklists = existingManagedRules.localBlocklistTagIds.toSet() + profileLocalBlocklists
        val effectiveLocalBlocklists =
            computeEffectiveLocalBlocklists(
                context,
                desiredLocalBlocklists,
                disabledLocalBlocklistTagIds = disabledLocalBlocklists
            )
        val alreadySelectedLocalBlocklists =
            effectiveLocalBlocklists.intersect(currentSelectedLocalBlocklists)
        val newlySelectedLocalBlocklists = effectiveLocalBlocklists - currentSelectedLocalBlocklists

        if (profileLocalBlocklists.isNotEmpty()) {
            val applied =
                applyLocalBlocklistSelection(
                    applySetupOverrideToCurrentSelection(
                        currentSelectedLocalBlocklists,
                        effectiveLocalBlocklists,
                        disabledLocalBlocklists
                    )
                )
            if (!applied) {
                rollbackImportedRules(importResult.ownedRules)
                return null
            }
        }

        val mergedSummary =
            importResult.summary.copy(
                importedCount = importResult.summary.importedCount + newlySelectedLocalBlocklists.size,
                alreadyBlockedCount =
                    importResult.summary.alreadyBlockedCount + alreadySelectedLocalBlocklists.size,
                artifactRuleCount = importResult.summary.artifactRuleCount + profileLocalBlocklists.size,
                supportedRuleKind =
                    mergeSupportedKinds(
                        importResult.summary.supportedRuleKind,
                        profileLocalBlocklists.isNotEmpty()
                    )
            )

        PowerProfileOwnershipStore.write(
            context,
            profile.id,
            PowerProfileOwnedRules(
                domains = importResult.ownedRules.domains,
                ips = importResult.ownedRules.ips,
                appDomains = importResult.ownedRules.appDomains,
                appIps = importResult.ownedRules.appIps,
                appFirewalls = importResult.ownedRules.appFirewalls,
                localBlocklistTagIds = profileLocalBlocklists.toList()
            )
        )
        val activeProfile = PowerProfileStore.activateProfile(context, profile, mergedSummary)
        applyNonNativeCurrentSetupOverrides(context)
        return activeProfile
    }

    suspend fun disableProfile(context: Context, profileId: String): PowerProfileDisableSummary {
        val ownedRules = resolveOwnedRulesForDisable(context, profileId)
        val remainingProfileIds =
            PowerProfileStore.listActiveProfiles(context).map { it.id }.filterNot { it == profileId }
        val remainingOwnedRules =
            PowerProfileOwnershipStore.aggregateOwnership(context, remainingProfileIds)
        val remainingOwnedDomains = remainingOwnedRules.domains.toSet()
        val remainingOwnedIps = remainingOwnedRules.ips.toSet()
        val remainingOwnedAppDomains = remainingOwnedRules.appDomains.map { it.key() }.toSet()
        val remainingOwnedAppIps = remainingOwnedRules.appIps.map { it.key() }.toSet()
        val remainingOwnedAppFirewalls =
            remainingOwnedRules.appFirewalls.map { it.key() }.toSet()
        val remainingOwnedLocalBlocklists = remainingOwnedRules.localBlocklistTagIds.toSet()

        val removableDomains = ownedRules.domains.filterNot { it in remainingOwnedDomains }
        val removableIps = ownedRules.ips.filterNot { it in remainingOwnedIps }
        val removableAppDomains = ownedRules.appDomains.filterNot { it.key() in remainingOwnedAppDomains }
        val removableAppIps = ownedRules.appIps.filterNot { it.key() in remainingOwnedAppIps }
        val removableAppFirewalls =
            ownedRules.appFirewalls.filterNot { it.key() in remainingOwnedAppFirewalls }
        val removableLocalBlocklists =
            ownedRules.localBlocklistTagIds.filterNot { it in remainingOwnedLocalBlocklists }
        val currentSelectedLocalBlocklists =
            RethinkBlocklistManager.getTagsFromStamp(
                persistentState.localBlocklistStamp,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            )

        if (removableLocalBlocklists.isNotEmpty()) {
            val desiredLocalBlocklists =
                (currentSelectedLocalBlocklists - removableLocalBlocklists.toSet()) +
                    remainingOwnedLocalBlocklists
            val effectiveLocalBlocklists =
                computeEffectiveLocalBlocklists(context, desiredLocalBlocklists)
            val applied = applyLocalBlocklistSelection(effectiveLocalBlocklists)
            if (!applied) {
                return PowerProfileDisableSummary(0)
            }
        }

        val removedDomains =
            customDomainRepository.deleteDomains(Constants.UID_EVERYBODY, removableDomains)
        val removedIps =
            customIpRepository.deleteRulesByUidAndIpAddresses(
                Constants.UID_EVERYBODY,
                Constants.UNSPECIFIED_PORT,
                removableIps
            )
        val removedAppDomains =
            removableAppDomains.groupBy { it.packageName }.entries.sumOf { (packageName, rules) ->
                val uid = appInfoRepository.getAppInfoUidForPackageName(packageName)
                if (uid <= 0) return@sumOf 0
                customDomainRepository.deleteDomains(uid, rules.map { it.domain })
            }
        val removedAppIps =
            removableAppIps.groupBy { it.packageName }.entries.sumOf { (packageName, rules) ->
                val uid = appInfoRepository.getAppInfoUidForPackageName(packageName)
                if (uid <= 0) return@sumOf 0
                rules.groupBy { it.port }.entries.sumOf { (port, portRules) ->
                    customIpRepository.deleteRulesByUidAndIpAddresses(
                        uid,
                        port,
                        portRules.map { it.ipAddress }
                    )
                }
            }
        val removedAppFirewalls =
            removableAppFirewalls.sumOf { ownedFirewall ->
                val uid = appInfoRepository.getAppInfoUidForPackageName(ownedFirewall.packageName)
                if (uid <= 0) return@sumOf 0
                val appInfo = appInfoRepository.getAppInfoByUid(uid) ?: return@sumOf 0
                if (
                    appInfo.firewallStatus != ownedFirewall.firewallStatus ||
                        appInfo.connectionStatus != ownedFirewall.connectionStatus
                ) {
                    return@sumOf 0
                }
                applyAppFirewallRule(
                    uid,
                    PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW
                )
                1
            }
        if (removedDomains > 0 || removedAppDomains > 0) reloadDomainRules()
        if (removedIps > 0 || removedAppIps > 0) reloadIpRules()

        PowerProfileOwnershipStore.delete(context, profileId)
        PowerProfileStore.deactivateProfile(context, profileId)
        pruneCurrentSetupOverrides(context)

        return PowerProfileDisableSummary(
            removedDomains +
                removedIps +
                removedAppDomains +
                removedAppIps +
                removedAppFirewalls +
                removableLocalBlocklists.size
        )
    }

    suspend fun setLocalBlocklistEnabledInCurrentSetup(
        context: Context,
        tagId: Int,
        enabled: Boolean
    ): Boolean {
        val activeProfiles = PowerProfileStore.listActiveProfiles(context)
        if (activeProfiles.isEmpty()) return false

        val desiredLocalBlocklists =
            activeProfiles
                .flatMap { PowerProfileOwnershipStore.read(context, it.id).localBlocklistTagIds }
                .toSet()
        if (tagId !in desiredLocalBlocklists) return false

        val currentOverrides = PowerProfileCurrentSetupOverrideStore.read(context)
        val updatedDisabled =
            if (enabled) {
                currentOverrides.disabledLocalBlocklistTagIds.toSet() - tagId
            } else {
                currentOverrides.disabledLocalBlocklistTagIds.toSet() + tagId
            }
        if (updatedDisabled == currentOverrides.disabledLocalBlocklistTagIds.toSet()) return true

        val effectiveLocalBlocklists =
            computeEffectiveLocalBlocklists(
                context,
                desiredLocalBlocklists,
                disabledLocalBlocklistTagIds = updatedDisabled
            )
        val currentSelectedLocalBlocklists =
            RethinkBlocklistManager.getTagsFromStamp(
                persistentState.localBlocklistStamp,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            )
        val applied =
            applyLocalBlocklistSelection(
                applySetupOverrideToCurrentSelection(
                    currentSelectedLocalBlocklists,
                    effectiveLocalBlocklists,
                    updatedDisabled
                )
            )
        if (!applied) return false

        PowerProfileCurrentSetupOverrideStore.write(
            context,
            PowerProfileCurrentSetupOverrides(
                disabledLocalBlocklistTagIds = updatedDisabled.toList().sorted()
            )
        )
        return true
    }

    fun getLocalBlocklistRuntimeState(
        context: Context,
        tagId: Int
    ): PowerProfileLocalBlocklistRuntimeState {
        val ownerProfiles =
            PowerProfileStore
                .listActiveProfiles(context)
                .filter { PowerProfileOwnershipStore.read(context, it.id).localBlocklistTagIds.contains(tagId) }
        val disabledLocalBlocklists =
            PowerProfileCurrentSetupOverrideStore.read(context).disabledLocalBlocklistTagIds.toSet()

        return PowerProfileLocalBlocklistRuntimeState(
            tagId = tagId,
            effectiveInCurrentSetup = ownerProfiles.isNotEmpty() && tagId !in disabledLocalBlocklists,
            disabledInCurrentSetup = tagId in disabledLocalBlocklists,
            ownerProfiles = ownerProfiles
        )
    }

    fun getManagedRuleSources(
        context: Context
    ): PowerProfileOwnershipStore.ManagedRuleSources {
        return PowerProfileOwnershipStore.listManagedRuleSources(context)
    }

    fun getDomainRuntimeState(
        context: Context,
        domain: String
    ): PowerProfileRuleRuntimeState {
        val normalizedDomain = domain.trim().trimEnd('.').lowercase()
        val ownerProfiles =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .domains
                .firstOrNull { it.domain == normalizedDomain }
                ?.ownerProfiles
                .orEmpty()
        val overrides = PowerProfileCurrentSetupOverrideStore.read(context)
        return PowerProfileRuleRuntimeState(
            effectiveInCurrentSetup =
                ownerProfiles.isNotEmpty() && normalizedDomain !in overrides.disabledDomains.toSet(),
            disabledInCurrentSetup = normalizedDomain in overrides.disabledDomains.toSet(),
            ownerProfiles = ownerProfiles
        )
    }

    fun getIpRuntimeState(
        context: Context,
        ipAddress: String
    ): PowerProfileRuleRuntimeState {
        val normalizedIp = normalizeIp(ipAddress) ?: return PowerProfileRuleRuntimeState(false, false, emptyList())
        val ownerProfiles =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .ips
                .firstOrNull { it.ipAddress == normalizedIp }
                ?.ownerProfiles
                .orEmpty()
        val overrides = PowerProfileCurrentSetupOverrideStore.read(context)
        return PowerProfileRuleRuntimeState(
            effectiveInCurrentSetup =
                ownerProfiles.isNotEmpty() && normalizedIp !in overrides.disabledIps.toSet(),
            disabledInCurrentSetup = normalizedIp in overrides.disabledIps.toSet(),
            ownerProfiles = ownerProfiles
        )
    }

    fun getAppDomainRuntimeState(
        context: Context,
        rule: PowerProfileOwnedAppDomainRule
    ): PowerProfileRuleRuntimeState {
        val ownerProfiles =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .appDomains
                .firstOrNull { it.rule.key() == rule.key() }
                ?.ownerProfiles
                .orEmpty()
        val overrides = PowerProfileCurrentSetupOverrideStore.read(context)
        return PowerProfileRuleRuntimeState(
            effectiveInCurrentSetup =
                ownerProfiles.isNotEmpty() &&
                    overrides.disabledAppDomains.none { it.key() == rule.key() },
            disabledInCurrentSetup = overrides.disabledAppDomains.any { it.key() == rule.key() },
            ownerProfiles = ownerProfiles
        )
    }

    fun getAppIpRuntimeState(
        context: Context,
        rule: PowerProfileOwnedAppIpRule
    ): PowerProfileRuleRuntimeState {
        val ownerProfiles =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .appIps
                .firstOrNull { it.rule.key() == rule.key() }
                ?.ownerProfiles
                .orEmpty()
        val overrides = PowerProfileCurrentSetupOverrideStore.read(context)
        return PowerProfileRuleRuntimeState(
            effectiveInCurrentSetup =
                ownerProfiles.isNotEmpty() && overrides.disabledAppIps.none { it.key() == rule.key() },
            disabledInCurrentSetup = overrides.disabledAppIps.any { it.key() == rule.key() },
            ownerProfiles = ownerProfiles
        )
    }

    fun getAppFirewallRuntimeState(
        context: Context,
        rule: PowerProfileOwnedAppFirewallRule
    ): PowerProfileRuleRuntimeState {
        val ownerProfiles =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .appFirewalls
                .firstOrNull { it.rule.key() == rule.key() }
                ?.ownerProfiles
                .orEmpty()
        val overrides = PowerProfileCurrentSetupOverrideStore.read(context)
        return PowerProfileRuleRuntimeState(
            effectiveInCurrentSetup =
                ownerProfiles.isNotEmpty() &&
                    overrides.disabledAppFirewalls.none { it.key() == rule.key() },
            disabledInCurrentSetup = overrides.disabledAppFirewalls.any { it.key() == rule.key() },
            ownerProfiles = ownerProfiles
        )
    }

    suspend fun setDomainEnabledInCurrentSetup(
        context: Context,
        domain: String,
        enabled: Boolean
    ): Boolean {
        val normalizedDomain = domain.trim().trimEnd('.').lowercase()
        if (normalizedDomain.isEmpty()) return false

        val managedRule =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .domains
                .firstOrNull { it.domain == normalizedDomain }
                ?: return false
        val currentOverrides = PowerProfileCurrentSetupOverrideStore.read(context)
        val updatedDisabled =
            if (enabled) {
                currentOverrides.disabledDomains.toSet() - normalizedDomain
            } else {
                currentOverrides.disabledDomains.toSet() + normalizedDomain
            }
        if (updatedDisabled == currentOverrides.disabledDomains.toSet()) return true

        if (enabled) {
            ensureGlobalDomainApplied(normalizedDomain)
            reloadDomainRules()
        } else {
            customDomainRepository.deleteDomains(Constants.UID_EVERYBODY, listOf(normalizedDomain))
            reloadDomainRules()
        }

        PowerProfileCurrentSetupOverrideStore.write(
            context,
            currentOverrides.copy(disabledDomains = updatedDisabled.toList().sorted())
        )
        return managedRule.ownerProfiles.isNotEmpty()
    }

    suspend fun setIpEnabledInCurrentSetup(
        context: Context,
        ipAddress: String,
        enabled: Boolean
    ): Boolean {
        val normalizedIp = normalizeIp(ipAddress) ?: return false
        val managedRule =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .ips
                .firstOrNull { it.ipAddress == normalizedIp }
                ?: return false
        val currentOverrides = PowerProfileCurrentSetupOverrideStore.read(context)
        val updatedDisabled =
            if (enabled) {
                currentOverrides.disabledIps.toSet() - normalizedIp
            } else {
                currentOverrides.disabledIps.toSet() + normalizedIp
            }
        if (updatedDisabled == currentOverrides.disabledIps.toSet()) return true

        if (enabled) {
            ensureGlobalIpApplied(normalizedIp)
            reloadIpRules()
        } else {
            customIpRepository.deleteRulesByUidAndIpAddresses(
                Constants.UID_EVERYBODY,
                Constants.UNSPECIFIED_PORT,
                listOf(normalizedIp)
            )
            reloadIpRules()
        }

        PowerProfileCurrentSetupOverrideStore.write(
            context,
            currentOverrides.copy(disabledIps = updatedDisabled.toList().sorted())
        )
        return managedRule.ownerProfiles.isNotEmpty()
    }

    suspend fun setAppDomainEnabledInCurrentSetup(
        context: Context,
        rule: PowerProfileOwnedAppDomainRule,
        enabled: Boolean
    ): Boolean {
        val managedRule =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .appDomains
                .firstOrNull { it.rule.key() == rule.key() }
                ?: return false
        val currentOverrides = PowerProfileCurrentSetupOverrideStore.read(context)
        val updatedDisabled =
            if (enabled) {
                currentOverrides.disabledAppDomains.filterNot { it.key() == rule.key() }
            } else {
                (currentOverrides.disabledAppDomains.filterNot { it.key() == rule.key() } + rule)
            }
        if (updatedDisabled.map { it.key() }.toSet() == currentOverrides.disabledAppDomains.map { it.key() }.toSet()) {
            return true
        }

        val uid = appInfoRepository.getAppInfoUidForPackageName(rule.packageName)
        if (uid <= 0) return false

        if (enabled) {
            ensureAppDomainApplied(context, rule, uid, managedRule.ownerProfiles)
            reloadDomainRules()
        } else {
            customDomainRepository.deleteDomains(uid, listOf(rule.domain))
            reloadDomainRules()
        }

        PowerProfileCurrentSetupOverrideStore.write(
            context,
            currentOverrides.copy(
                disabledAppDomains = updatedDisabled.distinctBy { it.key() }.sortedBy { it.key() }
            )
        )
        return true
    }

    suspend fun setAppIpEnabledInCurrentSetup(
        context: Context,
        rule: PowerProfileOwnedAppIpRule,
        enabled: Boolean
    ): Boolean {
        val managedRule =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .appIps
                .firstOrNull { it.rule.key() == rule.key() }
                ?: return false
        val currentOverrides = PowerProfileCurrentSetupOverrideStore.read(context)
        val updatedDisabled =
            if (enabled) {
                currentOverrides.disabledAppIps.filterNot { it.key() == rule.key() }
            } else {
                currentOverrides.disabledAppIps.filterNot { it.key() == rule.key() } + rule
            }
        if (updatedDisabled.map { it.key() }.toSet() == currentOverrides.disabledAppIps.map { it.key() }.toSet()) {
            return true
        }

        val uid = appInfoRepository.getAppInfoUidForPackageName(rule.packageName)
        if (uid <= 0) return false

        if (enabled) {
            ensureAppIpApplied(context, rule, uid, managedRule.ownerProfiles)
            reloadIpRules()
        } else {
            customIpRepository.deleteRulesByUidAndIpAddresses(uid, rule.port, listOf(rule.ipAddress))
            reloadIpRules()
        }

        PowerProfileCurrentSetupOverrideStore.write(
            context,
            currentOverrides.copy(
                disabledAppIps = updatedDisabled.distinctBy { it.key() }.sortedBy { it.key() }
            )
        )
        return true
    }

    suspend fun setAppFirewallEnabledInCurrentSetup(
        context: Context,
        rule: PowerProfileOwnedAppFirewallRule,
        enabled: Boolean
    ): Boolean {
        val managedRule =
            PowerProfileOwnershipStore
                .listManagedRuleSources(context)
                .appFirewalls
                .firstOrNull { it.rule.key() == rule.key() }
                ?: return false
        val currentOverrides = PowerProfileCurrentSetupOverrideStore.read(context)
        val updatedDisabled =
            if (enabled) {
                currentOverrides.disabledAppFirewalls.filterNot { it.key() == rule.key() }
            } else {
                currentOverrides.disabledAppFirewalls.filterNot { it.key() == rule.key() } + rule
            }
        if (
            updatedDisabled.map { it.key() }.toSet() ==
                currentOverrides.disabledAppFirewalls.map { it.key() }.toSet()
        ) {
            return true
        }

        val uid = appInfoRepository.getAppInfoUidForPackageName(rule.packageName)
        if (uid <= 0) return false

        if (enabled) {
            applyAppFirewallRule(uid, rule.firewallStatus, rule.connectionStatus)
        } else {
            val appInfo = appInfoRepository.getAppInfoByUid(uid) ?: return false
            if (
                appInfo.firewallStatus == rule.firewallStatus &&
                    appInfo.connectionStatus == rule.connectionStatus
            ) {
                applyAppFirewallRule(
                    uid,
                    PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW
                )
            }
        }

        PowerProfileCurrentSetupOverrideStore.write(
            context,
            currentOverrides.copy(
                disabledAppFirewalls =
                    updatedDisabled.distinctBy { it.key() }.sortedBy { it.key() }
            )
        )
        return managedRule.ownerProfiles.isNotEmpty()
    }

    private fun resolveOwnedRulesForDisable(
        context: Context,
        profileId: String
    ): PowerProfileOwnedRules {
        val storedRules = PowerProfileOwnershipStore.read(context, profileId)
        if (
                storedRules.domains.isNotEmpty() ||
                storedRules.ips.isNotEmpty() ||
                storedRules.appDomains.isNotEmpty() ||
                storedRules.appIps.isNotEmpty() ||
                storedRules.appFirewalls.isNotEmpty() ||
                storedRules.localBlocklistTagIds.isNotEmpty()
        ) {
            return storedRules
        }

        val profile = PowerProfileCatalog.get(context, profileId) ?: return storedRules
        val artifact = PowerProfileArtifacts.loadArtifact(context, profile)
        return PowerProfileOwnedRules(
            artifact?.domains ?: emptyList(),
            artifact?.ips ?: emptyList(),
            artifact?.apps?.flatMap { app ->
                app.domainRules.map { PowerProfileOwnedAppDomainRule(app.packageName, it.domain) }
            } ?: emptyList(),
            artifact?.apps?.flatMap { app ->
                app.ipRules.map { PowerProfileOwnedAppIpRule(app.packageName, it.ipAddress, it.port) }
            } ?: emptyList(),
            artifact?.apps
                ?.filter { it.hasFirewallRule() }
                ?.map {
                    PowerProfileOwnedAppFirewallRule(
                        packageName = it.packageName,
                        firewallStatus = it.firewallStatus,
                        connectionStatus = it.connectionStatus
                    )
                } ?: emptyList(),
            profile.localBlocklistTagIds
        )
    }

    private suspend fun applyLocalBlocklistSelection(selectedTagIds: Set<Int>): Boolean {
        if (selectedTagIds.isEmpty()) {
            persistentState.localBlocklistStamp = ""
            persistentState.numberOfLocalBlocklists = 0
            persistentState.blocklistEnabled = false
            syncLocalBlocklistSelections(emptySet())
            return true
        }

        val stamp = computeLocalBlocklistStampWithRetries(selectedTagIds)
        if (stamp.isBlank()) return false

        persistentState.localBlocklistStamp = stamp
        persistentState.numberOfLocalBlocklists = selectedTagIds.size
        persistentState.blocklistEnabled = true
        syncLocalBlocklistSelections(selectedTagIds)
        return true
    }

    private suspend fun computeLocalBlocklistStampWithRetries(selectedTagIds: Set<Int>): String {
        repeat(LOCAL_BLOCKLIST_STAMP_RETRY_ATTEMPTS) { attempt ->
            val stamp = computeLocalBlocklistStamp(selectedTagIds)
            if (stamp.isNotBlank()) return stamp
            if (attempt < LOCAL_BLOCKLIST_STAMP_RETRY_ATTEMPTS - 1) {
                waitBeforeRetry(LOCAL_BLOCKLIST_STAMP_RETRY_DELAY_MS)
            }
        }

        return ""
    }

    private suspend fun rollbackImportedRules(ownedRules: PowerProfileOwnedRules) {
        if (ownedRules.domains.isNotEmpty()) {
            customDomainRepository.deleteDomains(Constants.UID_EVERYBODY, ownedRules.domains)
        }

        if (ownedRules.ips.isNotEmpty()) {
            customIpRepository.deleteRulesByUidAndIpAddresses(
                Constants.UID_EVERYBODY,
                Constants.UNSPECIFIED_PORT,
                ownedRules.ips
            )
        }

        ownedRules.appDomains.groupBy { it.packageName }.forEach { (packageName, rules) ->
            val uid = appInfoRepository.getAppInfoUidForPackageName(packageName)
            if (uid > 0) {
                customDomainRepository.deleteDomains(uid, rules.map { it.domain })
            }
        }

        ownedRules.appIps.groupBy { it.packageName }.forEach { (packageName, rules) ->
            val uid = appInfoRepository.getAppInfoUidForPackageName(packageName)
            if (uid <= 0) return@forEach
            rules.groupBy { it.port }.forEach { (port, portRules) ->
                customIpRepository.deleteRulesByUidAndIpAddresses(
                    uid,
                    port,
                    portRules.map { it.ipAddress }
                )
            }
        }
        ownedRules.appFirewalls.forEach { ownedFirewall ->
            val uid = appInfoRepository.getAppInfoUidForPackageName(ownedFirewall.packageName)
            if (uid <= 0) return@forEach
            val appInfo = appInfoRepository.getAppInfoByUid(uid) ?: return@forEach
            if (
                appInfo.firewallStatus == ownedFirewall.firewallStatus &&
                    appInfo.connectionStatus == ownedFirewall.connectionStatus
            ) {
                applyAppFirewallRule(
                    uid,
                    PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW
                )
            }
        }

        if (
            ownedRules.domains.isNotEmpty() ||
                ownedRules.appDomains.isNotEmpty()
        ) {
            reloadDomainRules()
        }
        if (
            ownedRules.ips.isNotEmpty() ||
                ownedRules.appIps.isNotEmpty()
        ) {
            reloadIpRules()
        }
    }

    private fun computeEffectiveLocalBlocklists(
        context: Context,
        desiredLocalBlocklists: Set<Int>,
        disabledLocalBlocklistTagIds: Set<Int> =
            PowerProfileCurrentSetupOverrideStore.read(context).disabledLocalBlocklistTagIds.toSet()
    ): Set<Int> {
        if (desiredLocalBlocklists.isEmpty()) return emptySet()
        return desiredLocalBlocklists - disabledLocalBlocklistTagIds
    }

    private fun applySetupOverrideToCurrentSelection(
        currentSelectedLocalBlocklists: Set<Int>,
        effectiveLocalBlocklists: Set<Int>,
        disabledLocalBlocklistTagIds: Set<Int>
    ): Set<Int> {
        return (currentSelectedLocalBlocklists - disabledLocalBlocklistTagIds) + effectiveLocalBlocklists
    }

    private suspend fun ensureGlobalDomainApplied(domain: String) {
        customDomainRepository.insertAll(
            listOf(
                CustomDomain(
                    domain = domain,
                    uid = Constants.UID_EVERYBODY,
                    ips = "",
                    type = DomainRulesManager.DomainType.DOMAIN.id,
                    status = DomainRulesManager.Status.BLOCK.id,
                    proxyId = "",
                    proxyCC = "",
                    modifiedTs = System.currentTimeMillis(),
                    deletedTs = 0L,
                    version = CustomDomain.getCurrentVersion()
                )
            )
        )
    }

    private suspend fun ensureGlobalIpApplied(ipAddress: String) {
        customIpRepository.insertAll(
            listOf(
                CustomIp().apply {
                    uid = Constants.UID_EVERYBODY
                    this.ipAddress = ipAddress
                    status = IpRulesManager.IpRuleStatus.BLOCK.id
                    port = Constants.UNSPECIFIED_PORT
                    protocol = ""
                    isActive = true
                    wildcard = false
                    proxyId = ""
                    proxyCC = ""
                    modifiedDateTime = System.currentTimeMillis()
                    ruleType =
                        if (ipAddress.contains(":")) {
                            IpRulesManager.IPRuleType.IPV6.id
                        } else {
                            IpRulesManager.IPRuleType.IPV4.id
                        }
                }
            )
        )
    }

    private suspend fun ensureAppDomainApplied(
        context: Context,
        rule: PowerProfileOwnedAppDomainRule,
        uid: Int,
        ownerProfiles: List<ActivePowerProfile>
    ) {
        val resolvedRule = resolveAppDomainRule(context, rule, ownerProfiles)
        customDomainRepository.insertAll(
            listOf(
                CustomDomain(
                    domain = rule.domain,
                    uid = uid,
                    ips = resolvedRule?.ips.orEmpty(),
                    type = resolvedRule?.type ?: DomainRulesManager.DomainType.DOMAIN.id,
                    status = resolvedRule?.status ?: DomainRulesManager.Status.BLOCK.id,
                    proxyId = resolvedRule?.proxyId.orEmpty(),
                    proxyCC = resolvedRule?.proxyCC.orEmpty(),
                    modifiedTs = System.currentTimeMillis(),
                    deletedTs = 0L,
                    version = CustomDomain.getCurrentVersion()
                )
            )
        )
    }

    private suspend fun ensureAppIpApplied(
        context: Context,
        rule: PowerProfileOwnedAppIpRule,
        uid: Int,
        ownerProfiles: List<ActivePowerProfile>
    ) {
        val resolvedRule = resolveAppIpRule(context, rule, ownerProfiles)
        customIpRepository.insertAll(
            listOf(
                CustomIp().apply {
                    this.uid = uid
                    ipAddress = rule.ipAddress
                    status = resolvedRule?.status ?: IpRulesManager.IpRuleStatus.BLOCK.id
                    port = rule.port
                    protocol = resolvedRule?.protocol.orEmpty()
                    isActive = resolvedRule?.isActive ?: true
                    wildcard = resolvedRule?.wildcard ?: false
                    proxyId = resolvedRule?.proxyId.orEmpty()
                    proxyCC = resolvedRule?.proxyCC.orEmpty()
                    modifiedDateTime = System.currentTimeMillis()
                    ruleType =
                        if (rule.ipAddress.contains(":")) {
                            IpRulesManager.IPRuleType.IPV6.id
                        } else {
                            IpRulesManager.IPRuleType.IPV4.id
                        }
                }
            )
        )
    }

    private suspend fun resolveAppDomainRule(
        context: Context,
        rule: PowerProfileOwnedAppDomainRule,
        ownerProfiles: List<ActivePowerProfile>
    ): PowerProfileAppDomainRule? {
        ownerProfiles.forEach { ownerProfile ->
            val profile = PowerProfileCatalog.get(context, ownerProfile.id) ?: return@forEach
            val artifact = PowerProfileArtifacts.loadArtifact(context, profile) ?: return@forEach
            val app =
                artifact.apps.firstOrNull { it.packageName.equals(rule.packageName, ignoreCase = true) }
                    ?: return@forEach
            val resolved =
                app.domainRules.firstOrNull {
                    it.domain.trim().trimEnd('.').lowercase() == rule.domain.lowercase()
                }
            if (resolved != null) return resolved
        }
        return null
    }

    private suspend fun resolveAppIpRule(
        context: Context,
        rule: PowerProfileOwnedAppIpRule,
        ownerProfiles: List<ActivePowerProfile>
    ): PowerProfileAppIpRule? {
        ownerProfiles.forEach { ownerProfile ->
            val profile = PowerProfileCatalog.get(context, ownerProfile.id) ?: return@forEach
            val artifact = PowerProfileArtifacts.loadArtifact(context, profile) ?: return@forEach
            val app =
                artifact.apps.firstOrNull { it.packageName.equals(rule.packageName, ignoreCase = true) }
                    ?: return@forEach
            val resolved =
                app.ipRules.firstOrNull {
                    normalizeIp(it.ipAddress) == rule.ipAddress && it.port == rule.port
                }
            if (resolved != null) return resolved
        }
        return null
    }

    private fun pruneCurrentSetupOverrides(
        context: Context,
        managedSources: PowerProfileOwnershipStore.ManagedRuleSources =
            PowerProfileOwnershipStore.listManagedRuleSources(context)
    ) {
        val currentOverrides = PowerProfileCurrentSetupOverrideStore.read(context)
        val prunedOverrides =
            currentOverrides.copy(
                disabledDomains =
                    currentOverrides.disabledDomains.filter { disabledDomain ->
                        managedSources.domains.any { it.domain == disabledDomain }
                    },
                disabledIps =
                    currentOverrides.disabledIps.filter { disabledIp ->
                        managedSources.ips.any { it.ipAddress == disabledIp }
                    },
                disabledAppDomains =
                    currentOverrides.disabledAppDomains.filter { disabledRule ->
                        managedSources.appDomains.any { it.rule.key() == disabledRule.key() }
                    },
                disabledAppIps =
                    currentOverrides.disabledAppIps.filter { disabledRule ->
                        managedSources.appIps.any { it.rule.key() == disabledRule.key() }
                    },
                disabledAppFirewalls =
                    currentOverrides.disabledAppFirewalls.filter { disabledRule ->
                        managedSources.appFirewalls.any { it.rule.key() == disabledRule.key() }
                    },
                disabledLocalBlocklistTagIds =
                    currentOverrides.disabledLocalBlocklistTagIds.filter { disabledTagId ->
                        managedSources.localBlocklists.any { it.tagId == disabledTagId }
                    }
            )
        PowerProfileCurrentSetupOverrideStore.write(context, prunedOverrides)
    }

    private suspend fun applyNonNativeCurrentSetupOverrides(context: Context) {
        val managedSources = PowerProfileOwnershipStore.listManagedRuleSources(context)
        val overrides = PowerProfileCurrentSetupOverrideStore.read(context)

        val disabledDomains =
            managedSources.domains.map { it.domain }.intersect(overrides.disabledDomains.toSet())
        if (disabledDomains.isNotEmpty()) {
            customDomainRepository.deleteDomains(Constants.UID_EVERYBODY, disabledDomains.toList())
        }

        val disabledIps = managedSources.ips.map { it.ipAddress }.intersect(overrides.disabledIps.toSet())
        if (disabledIps.isNotEmpty()) {
            customIpRepository.deleteRulesByUidAndIpAddresses(
                Constants.UID_EVERYBODY,
                Constants.UNSPECIFIED_PORT,
                disabledIps.toList()
            )
        }

        overrides.disabledAppDomains.forEach { disabledRule ->
            val uid = appInfoRepository.getAppInfoUidForPackageName(disabledRule.packageName)
            if (uid > 0 && managedSources.appDomains.any { it.rule.key() == disabledRule.key() }) {
                customDomainRepository.deleteDomains(uid, listOf(disabledRule.domain))
            }
        }

        overrides.disabledAppIps.forEach { disabledRule ->
            val uid = appInfoRepository.getAppInfoUidForPackageName(disabledRule.packageName)
            if (uid > 0 && managedSources.appIps.any { it.rule.key() == disabledRule.key() }) {
                customIpRepository.deleteRulesByUidAndIpAddresses(
                    uid,
                    disabledRule.port,
                    listOf(disabledRule.ipAddress)
                )
            }
        }

        overrides.disabledAppFirewalls.forEach { disabledRule ->
            val uid = appInfoRepository.getAppInfoUidForPackageName(disabledRule.packageName)
            if (uid <= 0 || managedSources.appFirewalls.none { it.rule.key() == disabledRule.key() }) {
                return@forEach
            }
            val appInfo = appInfoRepository.getAppInfoByUid(uid) ?: return@forEach
            if (
                appInfo.firewallStatus == disabledRule.firewallStatus &&
                    appInfo.connectionStatus == disabledRule.connectionStatus
            ) {
                applyAppFirewallRule(
                    uid,
                    PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW
                )
            }
        }

        if (disabledDomains.isNotEmpty() || overrides.disabledAppDomains.isNotEmpty()) {
            reloadDomainRules()
        }
        if (disabledIps.isNotEmpty() || overrides.disabledAppIps.isNotEmpty()) {
            reloadIpRules()
        }
    }

    private fun normalizeIp(ipAddress: String): String? {
        return try {
            IPAddressString(ipAddress).address?.toNormalizedString()
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeSupportedKinds(existingKind: String, hasLocalBlocklists: Boolean): String {
        val kinds = linkedSetOf<String>()
        if (existingKind.isNotBlank()) kinds.add(existingKind)
        if (hasLocalBlocklists) kinds.add("rethink-local-blocklists")
        return kinds.joinToString(", ")
    }

    private suspend fun defaultReloadDomainRules() {
        DomainRulesManager.load()
    }

    private suspend fun defaultReloadIpRules() {
        IpRulesManager.load()
    }

    private suspend fun defaultApplyAppFirewallRule(
        uid: Int,
        firewallStatus: Int,
        connectionStatus: Int
    ) {
        FirewallManager.updateFirewallStatus(
            uid,
            FirewallManager.FirewallStatus.getStatus(firewallStatus),
            FirewallManager.ConnectionStatus.getStatus(connectionStatus)
        )
    }
}
