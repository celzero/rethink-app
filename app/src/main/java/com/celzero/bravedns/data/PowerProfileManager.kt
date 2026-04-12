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
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PowerProfileDisableSummary(
    val removedRuleCount: Int
)

object PowerProfileManager : KoinComponent {
    private val customDomainRepository by inject<CustomDomainRepository>()
    private val customIpRepository by inject<CustomIpRepository>()
    private val persistentState by inject<PersistentState>()

    suspend fun enableProfile(context: Context, profile: PowerProfileDefinition): ActivePowerProfile? {
        if (!profile.readyForActivation) return null

        val currentActiveIds = PowerProfileStore.listActiveProfiles(context).map { it.id }
        val existingManagedRules =
            PowerProfileOwnershipStore.aggregateOwnership(context, currentActiveIds)
        val importResult =
            PowerProfileImportManager.importBundledRules(
                context = context,
                profile = profile,
                currentlyManagedDomains = existingManagedRules.domains.toSet(),
                currentlyManagedIps = existingManagedRules.ips.toSet()
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
        val alreadySelectedLocalBlocklists = profileLocalBlocklists.intersect(currentSelectedLocalBlocklists)
        val newlySelectedLocalBlocklists = profileLocalBlocklists - currentSelectedLocalBlocklists

        if (profileLocalBlocklists.isNotEmpty()) {
            applyLocalBlocklistSelection(currentSelectedLocalBlocklists + profileLocalBlocklists)
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
                importResult.ownedRules.domains,
                importResult.ownedRules.ips,
                profileLocalBlocklists.toList()
            )
        )
        return PowerProfileStore.activateProfile(context, profile, mergedSummary)
    }

    suspend fun disableProfile(context: Context, profileId: String): PowerProfileDisableSummary {
        val ownedRules = resolveOwnedRulesForDisable(context, profileId)
        val remainingProfileIds =
            PowerProfileStore.listActiveProfiles(context).map { it.id }.filterNot { it == profileId }
        val remainingOwnedRules =
            PowerProfileOwnershipStore.aggregateOwnership(context, remainingProfileIds)
        val remainingOwnedDomains = remainingOwnedRules.domains.toSet()
        val remainingOwnedIps = remainingOwnedRules.ips.toSet()
        val remainingOwnedLocalBlocklists = remainingOwnedRules.localBlocklistTagIds.toSet()

        val removableDomains = ownedRules.domains.filterNot { it in remainingOwnedDomains }
        val removableIps = ownedRules.ips.filterNot { it in remainingOwnedIps }
        val removableLocalBlocklists =
            ownedRules.localBlocklistTagIds.filterNot { it in remainingOwnedLocalBlocklists }

        val removedDomains =
            customDomainRepository.deleteDomains(Constants.UID_EVERYBODY, removableDomains)
        val removedIps =
            customIpRepository.deleteRulesByUidAndIpAddresses(
                Constants.UID_EVERYBODY,
                Constants.UNSPECIFIED_PORT,
                removableIps
            )
        val currentSelectedLocalBlocklists =
            RethinkBlocklistManager.getTagsFromStamp(
                persistentState.localBlocklistStamp,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            )
        if (removableLocalBlocklists.isNotEmpty()) {
            applyLocalBlocklistSelection(currentSelectedLocalBlocklists - removableLocalBlocklists.toSet())
        }

        if (removedDomains > 0) DomainRulesManager.load()
        if (removedIps > 0) IpRulesManager.load()

        PowerProfileOwnershipStore.delete(context, profileId)
        PowerProfileStore.deactivateProfile(context, profileId)

        return PowerProfileDisableSummary(removedDomains + removedIps + removableLocalBlocklists.size)
    }

    private fun resolveOwnedRulesForDisable(
        context: Context,
        profileId: String
    ): PowerProfileOwnedRules {
        val storedRules = PowerProfileOwnershipStore.read(context, profileId)
        if (storedRules.domains.isNotEmpty() || storedRules.ips.isNotEmpty()) {
            return storedRules
        }

        val profile = PowerProfileCatalog.get(context, profileId) ?: return storedRules
        val artifact = PowerProfileArtifacts.loadArtifact(context, profile)
        return PowerProfileOwnedRules(
            artifact?.domains ?: emptyList(),
            artifact?.ips ?: emptyList(),
            profile.localBlocklistTagIds
        )
    }

    private suspend fun applyLocalBlocklistSelection(selectedTagIds: Set<Int>) {
        val stamp =
            RethinkBlocklistManager.getStamp(
                selectedTagIds,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            )
        persistentState.localBlocklistStamp = stamp
        persistentState.numberOfLocalBlocklists = selectedTagIds.size
        persistentState.blocklistEnabled = selectedTagIds.isNotEmpty()
        RethinkBlocklistManager.clearTagsSelectionLocal()
        if (selectedTagIds.isNotEmpty()) {
            RethinkBlocklistManager.updateFiletagsLocal(selectedTagIds, 1)
        }
    }

    private fun mergeSupportedKinds(existingKind: String, hasLocalBlocklists: Boolean): String {
        val kinds = linkedSetOf<String>()
        if (existingKind.isNotBlank()) kinds.add(existingKind)
        if (hasLocalBlocklists) kinds.add("rethink-local-blocklists")
        return kinds.joinToString(", ")
    }
}
