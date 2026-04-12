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
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.ActivePowerProfile
import com.celzero.bravedns.data.PowerProfileArtifacts
import com.celzero.bravedns.data.PowerProfileCatalog
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
            b.fapEmptyTitle.text =
                getString(R.string.power_active_profiles_live_title, activeProfiles.size)
            b.fapEmptyDesc.text =
                getString(
                    R.string.power_active_profiles_live_desc_compact,
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
                    val globalDomains = linkedSetOf<String>()
                    val globalIps = linkedSetOf<String>()
                    val appPackages = linkedSetOf<String>()
                    val rethinkTags = linkedSetOf<Int>()

                    activeProfiles.forEach { activeProfile ->
                        val profile = PowerProfileCatalog.get(requireContext(), activeProfile.id) ?: return@forEach
                        val artifact = PowerProfileArtifacts.loadArtifact(requireContext(), profile)
                        artifact?.domains?.let(globalDomains::addAll)
                        artifact?.ips?.let(globalIps::addAll)
                        artifact?.apps?.forEach { appPackages.add(it.packageName) }
                        rethinkTags.addAll(profile.localBlocklistTagIds)
                    }

                    ActiveProfileCoverageSummary(
                        domainCount = globalDomains.size,
                        ipCount = globalIps.size,
                        appCount = appPackages.size,
                        rethinkCount = rethinkTags.size
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
        card.vppscChevron.visibility = View.GONE
        card.root.isClickable = false
        card.root.isFocusable = false
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
