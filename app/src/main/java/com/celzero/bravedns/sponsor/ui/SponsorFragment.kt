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
package com.celzero.bravedns.sponsor.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentSponsorBinding
import com.celzero.bravedns.sponsor.billing.SponsorPurchaseResult
import com.celzero.bravedns.sponsor.viewmodel.SponsorUiState
import com.celzero.bravedns.sponsor.viewmodel.SponsorViewModel
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_SPONSOR_LINK
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class SponsorFragment : Fragment(R.layout.fragment_sponsor) {

    private val b by viewBinding(FragmentSponsorBinding::bind)
    private val viewModel: SponsorViewModel by inject()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configurePaymentOptions()

        val amounts = SponsorUiState.SUPPORTED_AMOUNTS

        // Floating "$X" bubble while dragging; slider value is the level index.
        b.contributionSeekbar.setLabelFormatter { value ->
            "\$${amounts.getOrElse(value.toInt()) { SponsorUiState.DEFAULT_AMOUNT }}"
        }

        b.contributionSeekbar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.selectIndex(value.toInt())
        }

        // Quick-select chips map straight to their slider index.
        b.chip5.setOnClickListener { viewModel.selectIndex(amounts.indexOf(5)) }
        b.chip10.setOnClickListener { viewModel.selectIndex(amounts.indexOf(10)) }
        b.chip25.setOnClickListener { viewModel.selectIndex(amounts.indexOf(25)) }
        b.chip50.setOnClickListener { viewModel.selectIndex(amounts.indexOf(50)) }

        b.supportButton.setOnClickListener {
            viewModel.purchase(requireActivity())
        }

        // Stripe is always offered as an alternative; on degoogled devices it is the
        // only option. Same web endpoint the F-Droid build uses.
        b.payWithStripeLink.setOnClickListener {
            openUrl(requireContext(), RETHINKDNS_SPONSOR_LINK)
        }

        observeUiState()
        observeSponsorState()
    }

    /**
     * Decides which payment options are visible based on Google Play availability.
     *
     * - Google Play Services present (play/website flavor): the contribution selector
     *   and the Play "Support" button are shown alongside the Stripe link.
     * - Google Play Services absent (or fdroid): the selector and Play button are
     *   hidden, the "Play billing unavailable" notice is shown, and only the Stripe
     *   link remains actionable.
     */
    private fun configurePaymentOptions() {
        val playBillingAvailable = Utilities.isGooglePlayServicesAvailable(requireContext())

        b.contributionCard.isVisible = playBillingAvailable
        b.supportButton.isVisible = playBillingAvailable
        b.sponsorPlayUnavailableText.isVisible = !playBillingAvailable
        // The Stripe link is always visible.
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUi(state)
                }
            }
        }
    }

    /**
     * The existing DB entry is the single source of truth for whether the user is
     * already a sponsor. When a row exists, surface the "already a sponsor" banner so
     * the user is never led to believe their prior contribution was lost. They may
     * still contribute again, which upserts (increments) the same row.
     */
    private fun observeSponsorState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSponsored.collect { sponsored ->
                    b.alreadySponsorBanner.isVisible = sponsored
                }
            }
        }
    }

    private fun updateUi(state: SponsorUiState) {
        val amount = state.selectedAmount
        val amountLabel = "\$$amount"

        b.contributionPrice.text = amountLabel

        if (b.contributionSeekbar.value.toInt() != state.selectedIndex) {
            b.contributionSeekbar.value = state.selectedIndex.toFloat()
        }

        // Reflect the selection on the quick-select chips; clear when the level
        // does not match any preset.
        b.chip5.isChecked = amount == 5
        b.chip10.isChecked = amount == 10
        b.chip25.isChecked = amount == 25
        b.chip50.isChecked = amount == 50
        if (amount !in setOf(5, 10, 25, 50)) b.chipGroup.clearCheck()

        // The Play button is only meaningful when Google Play billing is available.
        if (!b.supportButton.isVisible) return

        b.supportButton.isEnabled = !state.isPurchasing && state.isBillingReady

        if (state.isPurchasing) {
            b.supportButton.text = getString(R.string.sponsor_processing)
        } else {
            b.supportButton.text = getString(R.string.sponsor_action_label, amountLabel)
        }

        if (state.purchaseResult is SponsorPurchaseResult.Success) {
            showSuccessFragment()
            return
        }

        b.errorText.isVisible = state.errorMessage != null
        state.errorMessage?.let { b.errorText.text = it }
    }

    private fun showSuccessFragment() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SponsorSuccessFragment())
            .addToBackStack(null)
            .commit()
    }
}
