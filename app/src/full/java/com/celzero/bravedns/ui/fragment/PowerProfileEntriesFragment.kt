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
package com.celzero.bravedns.ui.fragment

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.PowerProfileArtifacts
import com.celzero.bravedns.data.PowerProfileBlocklistPreviewGroup
import com.celzero.bravedns.data.PowerProfileBlocklistPreviewManager
import com.celzero.bravedns.data.PowerProfileCatalog
import com.celzero.bravedns.data.PowerProfileCurrentSetupOverrideStore
import com.celzero.bravedns.data.PowerProfileDefinition
import com.celzero.bravedns.data.PowerProfileLocalBlocklistRuntimeState
import com.celzero.bravedns.data.PowerProfileManager
import com.celzero.bravedns.data.PowerProfileOwnershipStore
import com.celzero.bravedns.data.PowerProfileRuleRuntimeState
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.databinding.FragmentPowerProfileEntriesBinding
import com.celzero.bravedns.databinding.ViewPowerProfileBlocklistRowBinding
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PowerProfileEntriesFragment : Fragment(R.layout.fragment_power_profile_entries) {
    private val b by viewBinding(FragmentPowerProfileEntriesBinding::bind)
    private var profileId: String = ""
    private var sectionId: String = SECTION_RETHINK

    companion object {
        const val ARG_PROFILE_ID = "power.profile_id"
        const val ARG_SECTION = "power.profile_section"

        const val SECTION_DOMAINS = "domains"
        const val SECTION_IPS = "ips"
        const val SECTION_APPS = "apps"
        const val SECTION_RETHINK = "rethink"

        private const val PREVIEW_LIMIT = 120
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileId = arguments?.getString(ARG_PROFILE_ID).orEmpty()
        sectionId = arguments?.getString(ARG_SECTION).orEmpty().ifBlank { SECTION_RETHINK }
        b.fppeInfoIcon.setOnClickListener { showInfoDialog() }
        bindState()
    }

    private fun bindState() {
        b.fppeTitle.text = sectionTitle()

        viewLifecycleOwner.lifecycleScope.launch {
            if (isMergedActiveProfile()) {
                bindMergedState()
                return@launch
            }

            val profile = requireProfile()
            val artifact =
                withContext(Dispatchers.IO) {
                    PowerProfileArtifacts.loadArtifact(requireContext(), profile)
                }
            when (sectionId) {
                SECTION_DOMAINS -> bindExactEntries(profile, artifact?.domains.orEmpty(), true)
                SECTION_IPS -> bindExactEntries(profile, artifact?.ips.orEmpty(), false)
                SECTION_APPS -> bindAppsEmptyState(profile)
                else -> bindRethinkBlocklists(profile)
            }
        }
    }

    private suspend fun bindMergedState() {
        val managedSources =
            withContext(Dispatchers.IO) {
                PowerProfileManager.getManagedRuleSources(requireContext())
            }

        when (sectionId) {
            SECTION_DOMAINS -> bindMergedDomains(managedSources)
            SECTION_IPS -> bindMergedIps(managedSources)
            SECTION_APPS -> bindAppsEmptyState(null)
            else -> bindMergedRethinkBlocklists(managedSources)
        }
    }

    private suspend fun bindRethinkBlocklists(profile: PowerProfileDefinition) {
        val isProfileActive = false
        val groups =
            withContext(Dispatchers.IO) {
                PowerProfileBlocklistPreviewManager.loadLocalGroups(
                    requireContext(),
                    profile.localBlocklistTagIds
                )
            }
        val runtimeStates =
            if (isProfileActive) {
                withContext(Dispatchers.IO) {
                    profile.localBlocklistTagIds.associateWith {
                        PowerProfileManager.getLocalBlocklistRuntimeState(requireContext(), it)
                    }
                }
            } else {
                emptyMap()
            }
        val disabledCount =
            runtimeStates.values.count { it.disabledInCurrentSetup && it.ownerProfiles.isNotEmpty() }

        b.fppeSummaryTitle.text = getString(R.string.power_profile_rethink_blocklists_title)
        b.fppeSummaryDesc.text =
            if (profile.localBlocklistTagIds.isNotEmpty() && groups.isEmpty()) {
                getString(
                    R.string.power_profile_rethink_blocklists_unavailable_desc,
                    formatCount(profile.localBlocklistTagIds.size)
                )
            } else {
                getString(
                    R.string.power_profile_rethink_blocklists_desc,
                    formatCount(profile.localBlocklistTagIds.size),
                    formatCount(groups.size)
                )
            }
        b.fppeSummaryMeta.text =
            if (disabledCount > 0) {
                getString(
                    R.string.power_profile_entries_rule_meta_with_disabled,
                    formatCount(profile.localBlocklistTagIds.size),
                    profile.resolveTitle(requireContext()),
                    formatCount(disabledCount)
                )
            } else {
                getString(
                    R.string.power_profile_entries_rule_meta,
                    formatCount(profile.localBlocklistTagIds.size),
                    profile.resolveTitle(requireContext())
                )
            }

        b.fppeContentContainer.removeAllViews()
        if (groups.isEmpty()) {
            addInfoCard(
                getString(R.string.power_profile_entries_empty_title),
                if (profile.localBlocklistTagIds.isNotEmpty()) {
                    getString(R.string.power_profile_rethink_blocklists_unavailable_meta)
                } else {
                    getString(R.string.power_profile_rethink_blocklists_empty_desc)
                },
                if (profile.localBlocklistTagIds.isNotEmpty()) {
                    getString(R.string.power_profile_rethink_blocklists_unavailable_hint)
                } else {
                    getString(R.string.power_profile_entries_empty_meta)
                }
            )
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        groups.forEach { group ->
            val card =
                inflater.inflate(
                    R.layout.view_active_power_profile_item,
                    b.fppeContentContainer,
                    false
                ) as MaterialCardView
            val content = card.getChildAt(0) as LinearLayout
            card.findViewById<TextView>(R.id.vappi_title).text = group.label
            card.findViewById<TextView>(R.id.vappi_desc).text = group.description
            card.findViewById<TextView>(R.id.vappi_meta).text =
                getString(R.string.power_profile_detail_blocklists_meta, group.entries.size)
            addGroupedBlocklistEntries(
                content = content,
                group = group,
                runtimeStates = runtimeStates,
                profile = profile,
                interactive = isProfileActive
            )
            b.fppeContentContainer.addView(card)
        }
    }

    private suspend fun bindMergedRethinkBlocklists(
        managedSources: PowerProfileOwnershipStore.ManagedRuleSources
    ) {
        val tagIds = managedSources.localBlocklists.map { it.tagId }
        val groups =
            withContext(Dispatchers.IO) {
                PowerProfileBlocklistPreviewManager.loadLocalGroups(requireContext(), tagIds)
            }
        val runtimeStates =
            managedSources.localBlocklists.associate { managedRule ->
                managedRule.tagId to
                    PowerProfileLocalBlocklistRuntimeState(
                        tagId = managedRule.tagId,
                        effectiveInCurrentSetup =
                            managedRule.tagId !in
                                PowerProfileCurrentSetupOverrideStore
                                    .read(requireContext())
                                    .disabledLocalBlocklistTagIds
                                    .toSet(),
                        disabledInCurrentSetup =
                            managedRule.tagId in
                                PowerProfileCurrentSetupOverrideStore
                                    .read(requireContext())
                                    .disabledLocalBlocklistTagIds
                                    .toSet(),
                        ownerProfiles = managedRule.ownerProfiles
                    )
            }
        val disabledCount = runtimeStates.values.count { it.disabledInCurrentSetup }

        b.fppeSummaryTitle.text = getString(R.string.power_profile_rethink_blocklists_title)
        b.fppeSummaryDesc.text =
            getString(
                R.string.power_profile_rethink_blocklists_desc,
                formatCount(tagIds.size),
                formatCount(groups.size)
            )
        b.fppeSummaryMeta.text =
            getString(
                R.string.power_profile_entries_current_setup_meta,
                formatCount(tagIds.size - disabledCount),
                formatCount(disabledCount)
            )

        b.fppeContentContainer.removeAllViews()
        if (groups.isEmpty()) {
            addInfoCard(
                getString(R.string.power_profile_entries_empty_title),
                getString(R.string.power_profile_rethink_blocklists_empty_desc),
                getString(R.string.power_profile_entries_empty_meta)
            )
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        groups.forEach { group ->
            val card =
                inflater.inflate(
                    R.layout.view_active_power_profile_item,
                    b.fppeContentContainer,
                    false
                ) as MaterialCardView
            val content = card.getChildAt(0) as LinearLayout
            card.findViewById<TextView>(R.id.vappi_title).text = group.label
            card.findViewById<TextView>(R.id.vappi_desc).text = group.description
            card.findViewById<TextView>(R.id.vappi_meta).text =
                getString(R.string.power_profile_detail_blocklists_meta, group.entries.size)
            addGroupedBlocklistEntries(
                content = content,
                group = group,
                runtimeStates = runtimeStates,
                profile = null,
                interactive = true
            )
            b.fppeContentContainer.addView(card)
        }
    }

    private fun bindMergedDomains(managedSources: PowerProfileOwnershipStore.ManagedRuleSources) {
        val overrides = PowerProfileCurrentSetupOverrideStore.read(requireContext())
        val disabledCount =
            managedSources.domains.count { it.domain in overrides.disabledDomains.toSet() }
        b.fppeSummaryTitle.text = getString(R.string.power_profile_domain_blocklist_title)
        b.fppeSummaryDesc.text =
            getString(
                R.string.power_profile_domain_entries_desc,
                formatCount(managedSources.domains.size)
            )
        b.fppeSummaryMeta.text =
            getString(
                R.string.power_profile_entries_current_setup_meta,
                formatCount(managedSources.domains.size - disabledCount),
                formatCount(disabledCount)
            )
        renderManagedExactEntries(
            managedSources.domains
                .sortedBy { it.domain }
                .map { managedRule ->
                    ManagedExactEntry(
                        title = managedRule.domain,
                        meta = getString(
                            R.string.power_profile_rule_entry_meta_sources,
                            managedRule.ownerProfiles.joinToString(", ") { it.name }
                        ),
                        runtimeState = PowerProfileManager.getDomainRuntimeState(requireContext(), managedRule.domain),
                        onToggle = { enable ->
                            PowerProfileManager.setDomainEnabledInCurrentSetup(
                                requireContext(),
                                managedRule.domain,
                                enable
                            )
                        }
                    )
                }
        )
    }

    private fun bindMergedIps(managedSources: PowerProfileOwnershipStore.ManagedRuleSources) {
        val overrides = PowerProfileCurrentSetupOverrideStore.read(requireContext())
        val disabledCount =
            managedSources.ips.count { it.ipAddress in overrides.disabledIps.toSet() }
        b.fppeSummaryTitle.text = getString(R.string.power_profile_ip_blocklist_title)
        b.fppeSummaryDesc.text =
            getString(
                R.string.power_profile_ip_entries_desc,
                formatCount(managedSources.ips.size)
            )
        b.fppeSummaryMeta.text =
            getString(
                R.string.power_profile_entries_current_setup_meta,
                formatCount(managedSources.ips.size - disabledCount),
                formatCount(disabledCount)
            )
        renderManagedExactEntries(
            managedSources.ips
                .sortedBy { it.ipAddress }
                .map { managedRule ->
                    ManagedExactEntry(
                        title = managedRule.ipAddress,
                        meta = getString(
                            R.string.power_profile_rule_entry_meta_sources,
                            managedRule.ownerProfiles.joinToString(", ") { it.name }
                        ),
                        runtimeState = PowerProfileManager.getIpRuntimeState(requireContext(), managedRule.ipAddress),
                        onToggle = { enable ->
                            PowerProfileManager.setIpEnabledInCurrentSetup(
                                requireContext(),
                                managedRule.ipAddress,
                                enable
                            )
                        }
                    )
                }
        )
    }

    private fun bindExactEntries(
        profile: PowerProfileDefinition,
        entries: List<String>,
        isDomainSection: Boolean
    ) {
        val headingRes =
            if (isDomainSection) {
                R.string.power_profile_domain_blocklist_title
            } else {
                R.string.power_profile_ip_blocklist_title
            }
        val descRes =
            if (isDomainSection) {
                R.string.power_profile_domain_entries_desc
            } else {
                R.string.power_profile_ip_entries_desc
            }
        b.fppeSummaryTitle.text = getString(headingRes)
        b.fppeSummaryDesc.text = getString(descRes, formatCount(entries.size))
        b.fppeSummaryMeta.text =
            getString(
                R.string.power_profile_entries_rule_meta,
                formatCount(entries.size),
                profile.resolveTitle(requireContext())
            )

        b.fppeContentContainer.removeAllViews()
        if (entries.isEmpty()) {
            addInfoCard(
                getString(R.string.power_profile_entries_empty_title),
                if (isDomainSection) {
                    getString(R.string.power_profile_domain_entries_empty_desc)
                } else {
                    getString(R.string.power_profile_ip_entries_empty_desc)
                },
                getString(R.string.power_profile_entries_empty_meta)
            )
            return
        }

        val preview = entries.take(PREVIEW_LIMIT)
        addInfoCard(
            getString(R.string.power_profile_entries_preview_title),
            preview.joinToString("\n"),
            getString(
                R.string.power_profile_entries_preview_meta,
                formatCount(preview.size),
                formatCount(entries.size)
            )
        )
    }

    private fun bindAppsEmptyState(profile: PowerProfileDefinition?) {
        b.fppeSummaryTitle.text = getString(R.string.power_profile_apps_blocklist_title)
        b.fppeSummaryDesc.text =
            getString(
                R.string.power_profile_apps_entries_desc,
                profile?.resolveTitle(requireContext()) ?: getString(R.string.power_active_profiles_title)
            )
        b.fppeSummaryMeta.text =
            getString(
                R.string.power_profile_entries_rule_meta,
                formatCount(0),
                profile?.resolveTitle(requireContext()) ?: getString(R.string.power_active_profiles_title)
            )
        b.fppeContentContainer.removeAllViews()
        addInfoCard(
            getString(R.string.power_profile_entries_empty_title),
            getString(R.string.power_profile_apps_entries_empty_desc),
            getString(R.string.power_profile_entries_empty_meta)
        )
    }

    private fun addInfoCard(title: String, description: String, meta: String) {
        val card =
            LayoutInflater.from(requireContext()).inflate(
                R.layout.view_active_power_profile_item,
                b.fppeContentContainer,
                false
            ) as MaterialCardView
        card.findViewById<TextView>(R.id.vappi_title).text = title
        card.findViewById<TextView>(R.id.vappi_desc).text = description
        card.findViewById<TextView>(R.id.vappi_meta).text = meta
        b.fppeContentContainer.addView(card)
    }

    private fun showInfoDialog() {
        val profile = if (isMergedActiveProfile()) null else requireProfile()
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(sectionTitle())
            .setMessage(
                getString(
                    R.string.power_profile_entries_desc,
                    sectionTitle().lowercase(),
                    profile?.resolveTitle(requireContext()) ?: getString(R.string.power_active_profiles_title)
                )
            )
            .setPositiveButton(R.string.lbl_dismiss, null)
            .show()
    }

    private fun addGroupedBlocklistEntries(
        content: LinearLayout,
        group: PowerProfileBlocklistPreviewGroup,
        runtimeStates: Map<Int, PowerProfileLocalBlocklistRuntimeState>,
        profile: PowerProfileDefinition?,
        interactive: Boolean
    ) {
        var currentSubgroup = ""
        group.entries.forEach { entry ->
            val subgroup = entry.subg.ifBlank { getString(R.string.power_profile_blocklist_subgroup_other) }
            if (!subgroup.equals(currentSubgroup, ignoreCase = true)) {
                currentSubgroup = subgroup
                content.addView(
                    TextView(requireContext()).apply {
                        text = getString(R.string.power_profile_detail_blocklists_subgroup, subgroup)
                        setPadding(0, dp(14), 0, dp(4))
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                        setTextColor(resolveThemeColor(R.attr.primaryTextColor))
                    }
                )
            }

            val rowBinding =
                ViewPowerProfileBlocklistRowBinding.inflate(
                    LayoutInflater.from(requireContext()),
                    content,
                    false
                )
            val runtimeState = runtimeStates[entry.value]
            rowBinding.vppbrTitle.text = entry.vname
            rowBinding.vppbrMeta.text =
                getString(
                    R.string.power_profile_blocklist_entry_meta,
                    formatCount(entry.entries)
                )
            val statusText = blocklistStatusText(runtimeState)
            rowBinding.vppbrStatus.text = statusText
            rowBinding.vppbrStatus.visibility =
                if (statusText.isNullOrBlank()) View.GONE else View.VISIBLE
            rowBinding.vppbrRoot.isClickable = interactive
            rowBinding.vppbrRoot.isFocusable = interactive
            rowBinding.vppbrRoot.alpha =
                if (runtimeState?.disabledInCurrentSetup == true) 0.72f else 1f
            rowBinding.vppbrRoot.setOnClickListener(
                if (interactive && runtimeState != null) {
                    View.OnClickListener {
                        showBlocklistActionDialog(profile, entry.vname, runtimeState)
                    }
                } else {
                    null
                }
            )
            content.addView(rowBinding.root)
        }
    }

    private fun blocklistStatusText(runtimeState: PowerProfileLocalBlocklistRuntimeState?): String? {
        runtimeState ?: return null
        return when {
            runtimeState.disabledInCurrentSetup ->
                getString(R.string.power_profile_blocklist_disabled_in_current_setup)
            runtimeState.ownerProfiles.size > 1 ->
                getString(
                    R.string.power_profile_rule_shared_by_profiles,
                    formatCount(runtimeState.ownerProfiles.size)
                )
            else -> null
        }
    }

    private fun showBlocklistActionDialog(
        profile: PowerProfileDefinition?,
        blocklistName: String,
        runtimeState: PowerProfileLocalBlocklistRuntimeState
    ) {
        val isDisabled = runtimeState.disabledInCurrentSetup
        val ownerNames = runtimeState.ownerProfiles.joinToString(", ") { it.name }
        val titleRes =
            if (isDisabled) {
                R.string.power_profile_blocklist_reenable_dialog_title
            } else {
                R.string.power_profile_blocklist_disable_dialog_title
            }
        val message =
            when {
                isDisabled ->
                    getString(
                        R.string.power_profile_blocklist_reenable_dialog_message,
                        blocklistName
                    )
                runtimeState.ownerProfiles.size > 1 ->
                    getString(
                        R.string.power_profile_rule_disable_dialog_message_shared,
                        blocklistName,
                        formatCount(runtimeState.ownerProfiles.size),
                        ownerNames
                    )
                else ->
                    getString(
                        R.string.power_profile_blocklist_disable_dialog_message,
                        blocklistName,
                        profile?.resolveTitle(requireContext()) ?: getString(R.string.power_active_profiles_title)
                    )
            }
        val positiveRes =
            if (isDisabled) {
                R.string.power_profile_blocklist_reenable_action
            } else {
                R.string.power_profile_blocklist_disable_action
            }

        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(getString(titleRes))
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ ->
                toggleBlocklist(blocklistName, runtimeState, enable = isDisabled)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun toggleBlocklist(
        blocklistName: String,
        runtimeState: PowerProfileLocalBlocklistRuntimeState,
        enable: Boolean
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val applied =
                withContext(Dispatchers.IO) {
                    PowerProfileManager.setLocalBlocklistEnabledInCurrentSetup(
                        requireContext(),
                        runtimeState.tagId,
                        enable
                    )
                }
            if (!isAdded) return@launch

            if (applied) {
                showToastUiCentered(
                    requireContext(),
                    if (enable) {
                        getString(
                            R.string.power_profile_blocklist_reenabled_message,
                            blocklistName
                        )
                    } else {
                        getString(
                            R.string.power_profile_blocklist_disabled_message,
                            blocklistName
                        )
                    },
                    Toast.LENGTH_SHORT
                )
                bindState()
            } else {
                showToastUiCentered(
                    requireContext(),
                    getString(
                        R.string.power_profile_blocklist_update_failed_message,
                        blocklistName
                    ),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun renderManagedExactEntries(entries: List<ManagedExactEntry>) {
        b.fppeContentContainer.removeAllViews()
        if (entries.isEmpty()) {
            addInfoCard(
                getString(R.string.power_profile_entries_empty_title),
                getString(R.string.power_profile_entries_empty_meta),
                getString(R.string.power_profile_entries_empty_meta)
            )
            return
        }

        entries.forEach { entry ->
            val rowBinding =
                ViewPowerProfileBlocklistRowBinding.inflate(
                    LayoutInflater.from(requireContext()),
                    b.fppeContentContainer,
                    false
                )
            rowBinding.vppbrTitle.text = entry.title
            rowBinding.vppbrMeta.text = entry.meta
            val statusText = ruleStatusText(entry.runtimeState)
            rowBinding.vppbrStatus.text = statusText
            rowBinding.vppbrStatus.visibility =
                if (statusText.isNullOrBlank()) View.GONE else View.VISIBLE
            rowBinding.vppbrRoot.alpha =
                if (entry.runtimeState.disabledInCurrentSetup) 0.72f else 1f
            rowBinding.vppbrRoot.setOnClickListener { showRuleActionDialog(entry) }
            b.fppeContentContainer.addView(rowBinding.root)
        }
    }

    private fun ruleStatusText(runtimeState: PowerProfileRuleRuntimeState): String? {
        return when {
            runtimeState.disabledInCurrentSetup ->
                getString(R.string.power_profile_blocklist_disabled_in_current_setup)
            runtimeState.ownerProfiles.size > 1 ->
                getString(
                    R.string.power_profile_rule_shared_by_profiles,
                    formatCount(runtimeState.ownerProfiles.size)
                )
            else -> null
        }
    }

    private fun showRuleActionDialog(entry: ManagedExactEntry) {
        val isDisabled = entry.runtimeState.disabledInCurrentSetup
        val ownerNames = entry.runtimeState.ownerProfiles.joinToString(", ") { it.name }
        val message =
            when {
                isDisabled ->
                    getString(
                        R.string.power_profile_rule_reenable_dialog_message,
                        entry.title
                    )
                entry.runtimeState.ownerProfiles.size > 1 ->
                    getString(
                        R.string.power_profile_rule_disable_dialog_message_shared,
                        entry.title,
                        formatCount(entry.runtimeState.ownerProfiles.size),
                        ownerNames
                    )
                else ->
                    getString(
                        R.string.power_profile_rule_disable_dialog_message,
                        entry.title
                    )
            }

        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(
                if (isDisabled) {
                    R.string.power_profile_rule_reenable_dialog_title
                } else {
                    R.string.power_profile_rule_disable_dialog_title
                }
            )
            .setMessage(message)
            .setPositiveButton(
                if (isDisabled) {
                    R.string.power_profile_rule_reenable_action
                } else {
                    R.string.power_profile_rule_disable_action
                }
            ) { _, _ ->
                toggleRule(entry, enable = isDisabled)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun toggleRule(entry: ManagedExactEntry, enable: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) { entry.onToggle(enable) }
            if (!isAdded) return@launch

            if (applied) {
                showToastUiCentered(
                    requireContext(),
                    if (enable) {
                        getString(R.string.power_profile_rule_reenabled_message, entry.title)
                    } else {
                        getString(R.string.power_profile_rule_disabled_message, entry.title)
                    },
                    Toast.LENGTH_SHORT
                )
                bindState()
            } else {
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.power_profile_rule_update_failed_message, entry.title),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun sectionTitle(): String {
        return when (sectionId) {
            SECTION_DOMAINS -> getString(R.string.power_profile_domain_blocklist_title)
            SECTION_IPS -> getString(R.string.power_profile_ip_blocklist_title)
            SECTION_APPS -> getString(R.string.power_profile_apps_blocklist_title)
            else -> getString(R.string.power_profile_rethink_blocklists_title)
        }
    }

    private fun requireProfile(): PowerProfileDefinition {
        return checkNotNull(PowerProfileCatalog.get(requireContext(), profileId)) {
            "Missing profile: $profileId"
        }
    }

    private fun isMergedActiveProfile(): Boolean {
        return profileId == PowerProfileStore.MERGED_ACTIVE_PROFILE_ID
    }

    private fun formatCount(value: Int): String {
        return NumberFormat.getIntegerInstance().format(value)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun resolveThemeColor(attrId: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attrId, typedValue, true)
        return if (typedValue.resourceId != 0) {
            resources.getColor(typedValue.resourceId, requireContext().theme)
        } else {
            typedValue.data
        }
    }

    private data class ManagedExactEntry(
        val title: String,
        val meta: String,
        val runtimeState: PowerProfileRuleRuntimeState,
        val onToggle: suspend (Boolean) -> Boolean
    )
}
