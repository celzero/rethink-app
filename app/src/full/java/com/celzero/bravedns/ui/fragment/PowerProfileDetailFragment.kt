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

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.ActivePowerProfile
import com.celzero.bravedns.data.ImportedPowerProfileStore
import com.celzero.bravedns.data.PowerProfileArtifacts
import com.celzero.bravedns.data.PowerProfileCatalog
import com.celzero.bravedns.data.PowerProfileDefinition
import com.celzero.bravedns.data.PowerProfileManager
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.databinding.FragmentPowerProfileDetailBinding
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PowerProfileDetailFragment : Fragment(R.layout.fragment_power_profile_detail) {
    private val b by viewBinding(FragmentPowerProfileDetailBinding::bind)
    private var profileId: String = ""
    private var pendingExportProfileId: String? = null
    private val exportActivityResult =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            handleExportResult(uri)
        }

    companion object {
        const val ARG_PROFILE_ID = "power.profile_id"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileId = arguments?.getString(ARG_PROFILE_ID).orEmpty()
        setupClickListeners()
        bindState()
    }

    override fun onResume() {
        super.onResume()
        bindState()
    }

    private fun setupClickListeners() {
        b.fppdBackCard.setOnClickListener { findNavController().navigateUp() }
        b.fppdActionBtn.setOnClickListener { handleAction() }
        b.fppdExportBtn.setOnClickListener { exportProfile() }
    }

    private fun bindState() {
        val profile = requireProfile()
        val activeProfile = PowerProfileStore.getActiveProfile(requireContext(), profile.id)

        b.fppdTitle.text = profile.resolveTitle(requireContext())
        b.fppdDesc.text = profile.resolveDescription(requireContext())
        b.fppdSourceProvider.text =
            getString(
                R.string.power_profile_detail_provider,
                profile.sourceProvider ?: getString(R.string.app_name)
            )
        b.fppdSourceSummary.text =
            getString(
                R.string.power_profile_detail_source_summary,
                profile.sourceSummary ?: profile.resolveMeta(requireContext())
            )
        b.fppdSourceTokens.text =
            getString(
                R.string.power_profile_detail_source_tokens,
                if (profile.sourceTokens.isEmpty()) "-" else profile.sourceTokens.joinToString(", ")
            )
        b.fppdExportBtn.isEnabled = profile.readyForActivation

        when {
            activeProfile != null -> bindActiveState(activeProfile)
            profile.readyForActivation -> bindReadyState(profile)
            else -> bindComingSoonState()
        }
    }

    private fun bindActiveState(activeProfile: ActivePowerProfile) {
        b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_active)
        b.fppdStatusDesc.text =
            getString(
                R.string.power_profile_detail_active_desc,
                formatActiveTimestamp(activeProfile),
                activeProfile.importedRuleCount,
                activeProfile.artifactRuleCount
            )
        b.fppdStatusMeta.text =
            getString(
                R.string.power_profile_detail_rule_meta,
                activeProfile.artifactRuleCount,
                activeProfile.supportedRuleKind.ifBlank { "-" }
            )
        b.fppdActionBtn.isEnabled = true
        b.fppdActionBtn.setText(R.string.power_profile_disable_action)
    }

    private fun bindReadyState(profile: PowerProfileDefinition) {
        b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_ready)
        viewLifecycleOwner.lifecycleScope.launch {
            val artifact =
                withContext(Dispatchers.IO) {
                    PowerProfileArtifacts.loadArtifact(requireContext(), profile)
                }
            val supportedEntries = (artifact?.supportedRuleCount() ?: 0) + profile.localBlocklistTagIds.size
            val supportedKind = mergeSupportedKinds(artifact?.supportedRuleKind.orEmpty(), profile)
            b.fppdStatusDesc.text =
                getString(
                    R.string.power_profile_detail_ready_desc,
                    supportedEntries,
                    supportedKind
                )
            b.fppdStatusMeta.text =
                getString(
                    R.string.power_profile_detail_rule_meta,
                    supportedEntries,
                    supportedKind
                )
        }
        b.fppdActionBtn.isEnabled = true
        b.fppdActionBtn.setText(R.string.power_profile_enable_action)
    }

    private fun bindComingSoonState() {
        b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_coming_soon)
        b.fppdStatusDesc.text = getString(R.string.power_profile_detail_coming_desc)
        b.fppdStatusMeta.text = ""
        b.fppdActionBtn.isEnabled = false
        b.fppdActionBtn.setText(R.string.power_profile_coming_soon_action)
        b.fppdExportBtn.isEnabled = false
    }

    private fun handleAction() {
        val profile = requireProfile()
        val activeProfile = PowerProfileStore.getActiveProfile(requireContext(), profile.id)
        if (activeProfile != null) {
            disableProfile(activeProfile)
            return
        }
        if (profile.readyForActivation) {
            enableProfile(profile)
        }
    }

    private fun enableProfile(profile: PowerProfileDefinition) {
        viewLifecycleOwner.lifecycleScope.launch {
            showToastUiCentered(
                requireContext(),
                getString(
                    R.string.power_profile_activation_in_progress,
                    profile.resolveTitle(requireContext())
                ),
                Toast.LENGTH_SHORT
            )
            val activatedProfile =
                withContext(Dispatchers.IO) {
                    PowerProfileManager.enableProfile(requireContext(), profile)
                }
            if (activatedProfile != null) {
                showToastUiCentered(
                    requireContext(),
                    getString(
                        R.string.power_profile_activated_with_rules_message,
                        activatedProfile.name,
                        activatedProfile.importedRuleCount
                    ),
                    Toast.LENGTH_SHORT
                )
            }
            bindState()
        }
    }

    private fun exportProfile() {
        val profile = requireProfile()
        if (!profile.readyForActivation) return
        pendingExportProfileId = profile.id
        exportActivityResult.launch(suggestExportName(profile))
    }

    private fun handleExportResult(uri: Uri?) {
        val exportProfileId = pendingExportProfileId
        pendingExportProfileId = null
        if (uri == null || exportProfileId.isNullOrBlank()) return
        val profile = PowerProfileCatalog.get(requireContext(), exportProfileId) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val exported =
                withContext(Dispatchers.IO) {
                    val exportFile =
                        ImportedPowerProfileStore.exportToCache(requireContext(), profile) ?: return@withContext false
                    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                        exportFile.inputStream().use { input -> input.copyTo(output) }
                    } != null
                }
            val messageId =
                if (exported) {
                    R.string.power_profile_export_success_message
                } else {
                    R.string.power_profile_export_failure_message
                }
            val message =
                if (exported) {
                    getString(messageId, profile.resolveTitle(requireContext()))
                } else {
                    getString(messageId)
                }
            showToastUiCentered(
                requireContext(),
                message,
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun requireProfile(): PowerProfileDefinition {
        return checkNotNull(PowerProfileCatalog.get(requireContext(), profileId)) {
            "Missing profile: $profileId"
        }
    }

    private fun suggestExportName(profile: PowerProfileDefinition): String {
        return "${profile.id}.powerprofile.json"
    }

    private fun mergeSupportedKinds(existingKind: String, profile: PowerProfileDefinition): String {
        val kinds = linkedSetOf<String>()
        if (existingKind.isNotBlank()) kinds.add(existingKind)
        if (profile.localBlocklistTagIds.isNotEmpty()) kinds.add("rethink-local-blocklists")
        return kinds.joinToString(", ").ifBlank { "-" }
    }

    private fun disableProfile(activeProfile: ActivePowerProfile) {
        viewLifecycleOwner.lifecycleScope.launch {
            val disableSummary =
                withContext(Dispatchers.IO) {
                    PowerProfileManager.disableProfile(requireContext(), activeProfile.id)
                }
            showToastUiCentered(
                requireContext(),
                getString(
                    R.string.power_profile_disabled_with_rules_message,
                    activeProfile.name,
                    disableSummary.removedRuleCount
                ),
                Toast.LENGTH_SHORT
            )
            bindState()
        }
    }

    private fun formatActiveTimestamp(profile: ActivePowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.activatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }
}
