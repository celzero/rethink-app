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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.LayoutInflater
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.CuratedPowerProfileCatalog
import com.celzero.bravedns.data.ImportedPowerProfileStore
import com.celzero.bravedns.data.PowerProfileCatalog
import com.celzero.bravedns.data.PowerProfileDefinition
import com.celzero.bravedns.databinding.FragmentDiscoverProfilesBinding
import com.celzero.bravedns.util.Utilities.showToastUiCentered

class DiscoverProfilesFragment : Fragment(R.layout.fragment_discover_profiles) {

    private val b by viewBinding(FragmentDiscoverProfilesBinding::bind)
    private lateinit var importActivityResult:
        androidx.activity.result.ActivityResultLauncher<Intent>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActivityResults()
        bindCatalog()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        bindCatalog()
    }

    private fun bindCatalog() {
        val profiles = PowerProfileCatalog.list(requireContext())
        val smoothBrowsing = requireProfile("smooth-browsing")
        val safeBeautiful = requireProfile(CuratedPowerProfileCatalog.SAFE_BEAUTIFUL_INTERNET_ID)
        val appHorse = requireProfile(CuratedPowerProfileCatalog.APP_HORSE_ID)
        val exam = requireProfile("exam")
        val deepFocus = requireProfile("deep-focus")

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
            profile = safeBeautiful,
            root = b.fdpSafeBeautifulCard,
            iconId = R.id.fdp_safe_icon,
            titleId = R.id.fdp_safe_title,
            descriptionId = R.id.fdp_safe_desc,
            metaId = R.id.fdp_safe_meta
        )

        bindProfileCard(
            profile = appHorse,
            root = b.fdpAppHorseCard,
            iconId = R.id.fdp_app_horse_icon,
            titleId = R.id.fdp_app_horse_title,
            descriptionId = R.id.fdp_app_horse_desc,
            metaId = R.id.fdp_app_horse_meta
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

        bindImportedProfiles(PowerProfileCatalog.listImported(requireContext()))
    }

    private fun setupClickListeners() {
        b.fdpBackCard.setOnClickListener { findNavController().navigateUp() }

        b.fdpSmoothInternetCard.setOnClickListener {
            openProfileDetail("smooth-browsing")
        }
        b.fdpSafeBeautifulCard.setOnClickListener {
            openProfileDetail(CuratedPowerProfileCatalog.SAFE_BEAUTIFUL_INTERNET_ID)
        }
        b.fdpAppHorseCard.setOnClickListener {
            openProfileDetail(CuratedPowerProfileCatalog.APP_HORSE_ID)
        }
        b.fdpExamCard.setOnClickListener {
            openProfileDetail("exam")
        }
        b.fdpFocusCard.setOnClickListener {
            openProfileDetail("deep-focus")
        }
        b.fdpImportCard.setOnClickListener { importProfile() }
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
        titleView.text = profile.resolveTitle(requireContext())
        descriptionView.text = profile.resolveDescription(requireContext())
        metaView.text = profile.resolveMeta(requireContext())
    }

    private fun requireProfile(id: String): PowerProfileDefinition {
        return checkNotNull(PowerProfileCatalog.get(requireContext(), id)) {
            "Missing profile: $id"
        }
    }

    private fun openProfileDetail(profileId: String) {
        findNavController().navigate(
            R.id.powerProfileDetailFragment,
            Bundle().apply { putString(PowerProfileDetailFragment.ARG_PROFILE_ID, profileId) }
        )
    }

    private fun bindImportedProfiles(importedProfiles: List<PowerProfileDefinition>) {
        b.fdpImportedProfilesContainer.removeAllViews()
        val hasImported = importedProfiles.isNotEmpty()
        b.fdpImportedHeading.visibility = if (hasImported) View.VISIBLE else View.GONE
        b.fdpImportedProfilesContainer.visibility = if (hasImported) View.VISIBLE else View.GONE
        if (!hasImported) return

        val inflater = LayoutInflater.from(requireContext())
        importedProfiles.forEach { profile ->
            val card =
                inflater.inflate(
                    R.layout.view_active_power_profile_item,
                    b.fdpImportedProfilesContainer,
                    false
                )
            card.findViewById<TextView>(R.id.vappi_title).text = profile.resolveTitle(requireContext())
            card.findViewById<TextView>(R.id.vappi_desc).text =
                profile.resolveDescription(requireContext())
            card.findViewById<TextView>(R.id.vappi_meta).text = profile.resolveMeta(requireContext())
            card.setOnClickListener { openProfileDetail(profile.id) }
            b.fdpImportedProfilesContainer.addView(card)
        }
    }

    private fun importProfile() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
        importActivityResult.launch(intent)
    }

    private fun setupActivityResults() {
        importActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result: ActivityResult ->
                if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
                val fileUri = result.data?.data ?: return@registerForActivityResult
                val importedProfile = ImportedPowerProfileStore.importFromUri(requireContext(), fileUri)
                if (importedProfile == null) {
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.power_profile_import_failure_message),
                        Toast.LENGTH_SHORT
                    )
                    return@registerForActivityResult
                }
                showToastUiCentered(
                    requireContext(),
                    getString(
                        R.string.power_profile_import_success_message,
                        importedProfile.resolveTitle(requireContext())
                    ),
                    Toast.LENGTH_SHORT
                )
                bindCatalog()
                openProfileDetail(importedProfile.id)
            }
    }
}
