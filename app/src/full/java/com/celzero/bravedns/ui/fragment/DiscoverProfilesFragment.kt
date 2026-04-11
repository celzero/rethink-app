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
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentDiscoverProfilesBinding
import com.celzero.bravedns.util.Utilities.showToastUiCentered

class DiscoverProfilesFragment : Fragment(R.layout.fragment_discover_profiles) {

    private val b by viewBinding(FragmentDiscoverProfilesBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        b.fdpBackCard.setOnClickListener { findNavController().navigateUp() }

        b.fdpSmoothInternetCard.setOnClickListener { showMergePlaceholder(R.string.power_profile_smooth_internet_title) }
        b.fdpExamCard.setOnClickListener { showMergePlaceholder(R.string.power_profile_exam_title) }
        b.fdpFocusCard.setOnClickListener { showMergePlaceholder(R.string.power_profile_focus_title) }
        b.fdpImportCard.setOnClickListener { showImportPlaceholder() }
    }

    private fun showMergePlaceholder(titleRes: Int) {
        showToastUiCentered(
            requireContext(),
            getString(R.string.power_profile_merge_placeholder, getString(titleRes)),
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
