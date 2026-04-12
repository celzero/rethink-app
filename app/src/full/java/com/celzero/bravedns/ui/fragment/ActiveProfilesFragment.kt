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
import android.text.format.DateUtils
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.ActivePowerProfile
import com.celzero.bravedns.data.PowerProfileCurrentSetupOverrideStore
import com.celzero.bravedns.data.PowerProfileManager
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.data.SavedPowerProfile
import com.celzero.bravedns.databinding.FragmentActiveProfilesBinding
import com.celzero.bravedns.databinding.ViewPowerProfileSectionCardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActiveProfilesFragment : Fragment(R.layout.fragment_active_profiles) {

    private val b by viewBinding(FragmentActiveProfilesBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindState()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        bindState()
    }

    private fun bindState() {
        val activeProfiles = PowerProfileStore.listActiveProfiles(requireContext())
        val savedProfiles = PowerProfileStore.listSavedProfiles(requireContext())
        bindCoverageCards(activeProfiles)

        if (activeProfiles.isNotEmpty()) {
            val latestProfile = activeProfiles.first()
            b.fapEmptyTitle.text = getString(R.string.power_active_profiles_live_title)
            b.fapEmptyDesc.text =
                getString(
                    R.string.power_active_profiles_active_summary,
                    activeProfiles.size,
                    latestProfile.name,
                    formatActiveTimestamp(latestProfile)
                )
        } else if (savedProfiles.isEmpty()) {
            b.fapEmptyTitle.text = getString(R.string.power_active_profiles_empty_title)
            b.fapEmptyDesc.text = getString(R.string.power_active_profiles_empty_desc)
        } else {
            val latestProfile = savedProfiles.first()
            b.fapEmptyTitle.text =
                getString(R.string.power_active_profiles_saved_title, savedProfiles.size)
            b.fapEmptyDesc.text =
                getString(
                    R.string.power_active_profiles_saved_desc_compact,
                    latestProfile.name,
                    formatProfileTimestamp(latestProfile)
                )
        }
    }

    private fun setupClickListeners() {
        b.fapInfoIcon.setOnClickListener { showInfoDialog() }
        b.fapDomainCard.root.setOnClickListener { openEntriesSection(PowerProfileEntriesFragment.SECTION_DOMAINS) }
        b.fapIpCard.root.setOnClickListener { openEntriesSection(PowerProfileEntriesFragment.SECTION_IPS) }
        b.fapAppsCard.root.setOnClickListener { openAppsSection() }
        b.fapRethinkCard.root.setOnClickListener { openEntriesSection(PowerProfileEntriesFragment.SECTION_RETHINK) }
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(R.string.power_active_profiles_title)
            .setMessage(
                getString(R.string.power_active_profiles_screen_desc) +
                    "\n\n" +
                    getString(R.string.power_active_profiles_current_setup_desc)
            )
            .setPositiveButton(R.string.lbl_dismiss, null)
            .show()
    }

    private fun bindCoverageCards(activeProfiles: List<ActivePowerProfile>) {
        bindSectionCard(
            b.fapDomainCard,
            getString(R.string.power_profile_domain_blocklist_title),
            R.drawable.ic_dns_cache,
            "0",
            getString(R.string.power_profile_domain_card_meta, "0")
        )
        bindSectionCard(
            b.fapIpCard,
            getString(R.string.power_profile_ip_blocklist_title),
            R.drawable.ic_firewall_shield,
            "0",
            getString(R.string.power_profile_ip_card_meta, "0")
        )
        bindSectionCard(
            b.fapAppsCard,
            getString(R.string.power_profile_apps_blocklist_title),
            R.drawable.ic_app_info_accent,
            "0",
            getString(R.string.power_profile_apps_card_meta_empty)
        )
        bindSectionCard(
            b.fapRethinkCard,
            getString(R.string.power_profile_rethink_blocklists_title),
            R.drawable.ic_dns_firewall,
            "0",
            getString(R.string.power_profile_rethink_card_meta_fallback, "0")
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val summary =
                withContext(Dispatchers.IO) {
                    val managedSources = PowerProfileManager.getManagedRuleSources(requireContext())
                    val overrides = PowerProfileCurrentSetupOverrideStore.read(requireContext())
                    val effectiveDomains =
                        managedSources.domains.count { it.domain !in overrides.disabledDomains.toSet() }
                    val effectiveIps =
                        managedSources.ips.count { it.ipAddress !in overrides.disabledIps.toSet() }
                    val effectiveBlocklists =
                        managedSources.localBlocklists.count {
                            it.tagId !in overrides.disabledLocalBlocklistTagIds.toSet()
                        }
                    val effectiveApps =
                        buildSet {
                            managedSources.appDomains
                                .filter { managed ->
                                    overrides.disabledAppDomains.none { it.key() == managed.rule.key() }
                                }
                                .forEach { add(it.rule.packageName) }
                            managedSources.appIps
                                .filter { managed ->
                                    overrides.disabledAppIps.none { it.key() == managed.rule.key() }
                                }
                                .forEach { add(it.rule.packageName) }
                            managedSources.appFirewalls
                                .filter { managed ->
                                    overrides.disabledAppFirewalls.none { it.key() == managed.rule.key() }
                                }
                                .forEach { add(it.rule.packageName) }
                        }

                    ActiveProfileCoverageSummary(
                        domainCount = effectiveDomains,
                        ipCount = effectiveIps,
                        appCount = effectiveApps.size,
                        rethinkCount = effectiveBlocklists
                    )
                }
            if (!isAdded) return@launch

            bindSectionCard(
                b.fapDomainCard,
                getString(R.string.power_profile_domain_blocklist_title),
                R.drawable.ic_dns_cache,
                formatCount(summary.domainCount),
                getString(R.string.power_profile_domain_card_meta, formatCount(summary.domainCount))
            )
            bindSectionCard(
                b.fapIpCard,
                getString(R.string.power_profile_ip_blocklist_title),
                R.drawable.ic_firewall_shield,
                formatCount(summary.ipCount),
                getString(R.string.power_profile_ip_card_meta, formatCount(summary.ipCount))
            )
            bindSectionCard(
                b.fapAppsCard,
                getString(R.string.power_profile_apps_blocklist_title),
                R.drawable.ic_app_info_accent,
                formatCount(summary.appCount),
                if (summary.appCount == 0) {
                    getString(R.string.power_profile_apps_card_meta_empty)
                } else {
                    getString(
                        R.string.power_active_profiles_apps_card_meta,
                        formatCount(summary.appCount)
                    )
                }
            )
            bindSectionCard(
                b.fapRethinkCard,
                getString(R.string.power_profile_rethink_blocklists_title),
                R.drawable.ic_dns_firewall,
                formatCount(summary.rethinkCount),
                getString(
                    R.string.power_active_profiles_rethink_card_meta,
                    formatCount(summary.rethinkCount)
                )
            )
        }
    }

    private fun bindSectionCard(
        card: ViewPowerProfileSectionCardBinding,
        title: String,
        iconRes: Int,
        count: String,
        meta: String
    ) {
        card.vppscTitle.apply {
            text = title
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        }
        card.vppscCount.text = count
        card.vppscMeta.text = meta
        card.vppscChevron.visibility = View.VISIBLE
        card.root.isClickable = true
        card.root.isFocusable = true
    }

    private fun openEntriesSection(section: String) {
        findNavController().navigate(
            R.id.powerProfileEntriesFragment,
            Bundle().apply {
                putString(PowerProfileEntriesFragment.ARG_PROFILE_ID, PowerProfileStore.MERGED_ACTIVE_PROFILE_ID)
                putString(PowerProfileEntriesFragment.ARG_SECTION, section)
            }
        )
    }

    private fun openAppsSection() {
        findNavController().navigate(
            R.id.powerProfileAppsFragment,
            Bundle().apply {
                putString(PowerProfileAppsFragment.ARG_PROFILE_ID, PowerProfileStore.MERGED_ACTIVE_PROFILE_ID)
            }
        )
    }

    private fun formatProfileTimestamp(profile: SavedPowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.createdAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun formatActiveTimestamp(profile: ActivePowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.activatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun formatCount(value: Int): String = value.toString()

    private data class ActiveProfileCoverageSummary(
        val domainCount: Int,
        val ipCount: Int,
        val appCount: Int,
        val rethinkCount: Int
    )
}
