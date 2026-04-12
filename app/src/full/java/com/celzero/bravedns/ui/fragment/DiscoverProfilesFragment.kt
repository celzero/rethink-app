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
import android.view.View
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.CuratedPowerProfileCatalog
import com.celzero.bravedns.data.PowerProfileImportManager
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.data.PowerProfileDefinition
import com.celzero.bravedns.databinding.FragmentDiscoverProfilesBinding
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverProfilesFragment : Fragment(R.layout.fragment_discover_profiles) {

    private val b by viewBinding(FragmentDiscoverProfilesBinding::bind)
    private val profiles = CuratedPowerProfileCatalog.profiles

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindCatalog()
        setupClickListeners()
    }

    private fun bindCatalog() {
        val smoothBrowsing = requireProfile(CuratedPowerProfileCatalog.SMOOTH_BROWSING_ID)
        val exam = requireProfile(CuratedPowerProfileCatalog.EXAM_ID)
        val deepFocus = requireProfile(CuratedPowerProfileCatalog.DEEP_FOCUS_ID)

        b.fdpIntroDesc.text =
            getString(R.string.power_discover_profiles_catalog_count, profiles.size)

        bindProfileCard(
            profile = smoothBrowsing,
            root = b.fdpSmoothInternetCard,
            iconId = R.id.fdp_smooth_icon,
            titleId = R.id.fdp_smooth_title,
            descriptionId = R.id.fdp_smooth_desc,
            metaId = R.id.fdp_smooth_meta
        )

        bindProfileCard(
            profile = exam,
            root = b.fdpExamCard,
            iconId = R.id.fdp_exam_icon,
            titleId = R.id.fdp_exam_title,
            descriptionId = R.id.fdp_exam_desc,
            metaId = R.id.fdp_exam_meta
        )

        bindProfileCard(
            profile = deepFocus,
            root = b.fdpFocusCard,
            iconId = R.id.fdp_focus_icon,
            titleId = R.id.fdp_focus_title,
            descriptionId = R.id.fdp_focus_desc,
            metaId = R.id.fdp_focus_meta
        )
    }

    private fun setupClickListeners() {
        b.fdpBackCard.setOnClickListener { findNavController().navigateUp() }

        b.fdpSmoothInternetCard.setOnClickListener {
            activateSmoothBrowsing()
        }
        b.fdpExamCard.setOnClickListener {
            showMergePlaceholder(requireProfile(CuratedPowerProfileCatalog.EXAM_ID))
        }
        b.fdpFocusCard.setOnClickListener {
            showMergePlaceholder(requireProfile(CuratedPowerProfileCatalog.DEEP_FOCUS_ID))
        }
        b.fdpImportCard.setOnClickListener { showImportPlaceholder() }
    }

    private fun bindProfileCard(
        profile: PowerProfileDefinition,
        root: View,
        iconId: Int,
        titleId: Int,
        descriptionId: Int,
        metaId: Int
    ) {
        val iconView = root.findViewById<ImageView>(iconId)
        val titleView = root.findViewById<TextView>(titleId)
        val descriptionView = root.findViewById<TextView>(descriptionId)
        val metaView = root.findViewById<TextView>(metaId)
        iconView.setImageResource(profile.iconRes)
        titleView.setText(profile.titleRes)
        descriptionView.setText(profile.descriptionRes)
        metaView.setText(profile.metaRes)
    }

    private fun requireProfile(id: String): PowerProfileDefinition {
        return checkNotNull(CuratedPowerProfileCatalog.get(id)) { "Missing curated profile: $id" }
    }

    private fun activateSmoothBrowsing() {
        val profile = requireProfile(CuratedPowerProfileCatalog.SMOOTH_BROWSING_ID)
        viewLifecycleOwner.lifecycleScope.launch {
            showToastUiCentered(
                requireContext(),
                getString(R.string.power_profile_activation_in_progress, getString(profile.titleRes)),
                Toast.LENGTH_SHORT
            )
            val importSummary =
                withContext(Dispatchers.IO) {
                    PowerProfileImportManager.importBundledRules(requireContext(), profile)
                }
            val activeProfile = PowerProfileStore.activateProfile(requireContext(), profile, importSummary)
            showToastUiCentered(
                requireContext(),
                getString(
                    R.string.power_profile_activated_with_rules_message,
                    activeProfile.name,
                    activeProfile.importedRuleCount
                ),
                Toast.LENGTH_SHORT
            )
            findNavController().navigate(R.id.activeProfilesFragment)
        }
    }

    private fun showMergePlaceholder(profile: PowerProfileDefinition) {
        showToastUiCentered(
            requireContext(),
            getString(R.string.power_profile_merge_placeholder, getString(profile.titleRes)),
            Toast.LENGTH_SHORT
        )
    }

    private fun showImportPlaceholder() {
        showToastUiCentered(
            requireContext(),
            getString(R.string.power_profile_import_placeholder),
            Toast.LENGTH_SHORT
        )
    }
}
