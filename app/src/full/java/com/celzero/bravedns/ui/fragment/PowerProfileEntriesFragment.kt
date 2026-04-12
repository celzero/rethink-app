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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.BundledDomainProfileArtifact
import com.celzero.bravedns.data.PowerProfileArtifacts
import com.celzero.bravedns.data.PowerProfileBlocklistPreviewGroup
import com.celzero.bravedns.data.PowerProfileBlocklistPreviewManager
import com.celzero.bravedns.data.PowerProfileCatalog
import com.celzero.bravedns.data.PowerProfileDefinition
import com.celzero.bravedns.databinding.FragmentPowerProfileEntriesBinding
import com.google.android.material.card.MaterialCardView
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
        b.fppeBackCard.setOnClickListener { findNavController().navigateUp() }
        bindState()
    }

    private fun bindState() {
        val profile = requireProfile()
        b.fppeTitle.text = sectionTitle()
        b.fppeDesc.text =
            getString(
                R.string.power_profile_entries_desc,
                sectionTitle().lowercase(),
                profile.resolveTitle(requireContext())
            )

        viewLifecycleOwner.lifecycleScope.launch {
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

    private suspend fun bindRethinkBlocklists(profile: PowerProfileDefinition) {
        val groups =
            withContext(Dispatchers.IO) {
                PowerProfileBlocklistPreviewManager.loadLocalGroups(
                    requireContext(),
                    profile.localBlocklistTagIds
                )
            }

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
            getString(
                R.string.power_profile_entries_rule_meta,
                formatCount(profile.localBlocklistTagIds.size),
                profile.resolveTitle(requireContext())
            )

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
            addGroupedBlocklistEntries(content, group)
            b.fppeContentContainer.addView(card)
        }
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

    private fun bindAppsEmptyState(profile: PowerProfileDefinition) {
        b.fppeSummaryTitle.text = getString(R.string.power_profile_apps_blocklist_title)
        b.fppeSummaryDesc.text =
            getString(R.string.power_profile_apps_entries_desc, profile.resolveTitle(requireContext()))
        b.fppeSummaryMeta.text =
            getString(
                R.string.power_profile_entries_rule_meta,
                formatCount(0),
                profile.resolveTitle(requireContext())
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

    private fun addGroupedBlocklistEntries(
        container: LinearLayout,
        group: PowerProfileBlocklistPreviewGroup
    ) {
        var currentSubgroup = ""
        group.entries.forEach { entry ->
            val subgroup = entry.subg.ifBlank { getString(R.string.power_profile_blocklist_subgroup_other) }
            if (!subgroup.equals(currentSubgroup, ignoreCase = true)) {
                currentSubgroup = subgroup
                container.addView(
                    TextView(requireContext()).apply {
                        text = getString(R.string.power_profile_detail_blocklists_subgroup, subgroup)
                        setPadding(0, dp(14), 0, dp(4))
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                        setTextColor(resolveThemeColor(R.attr.primaryTextColor))
                    }
                )
            }

            container.addView(
                TextView(requireContext()).apply {
                    text =
                        getString(
                            R.string.power_profile_blocklist_entry_line,
                            entry.vname,
                            formatCount(entry.entries)
                        )
                    setPadding(dp(6), dp(2), 0, dp(2))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(resolveThemeColor(R.attr.secondaryTextColor))
                }
            )
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
}
