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
package com.celzero.bravedns.ui.bottomsheet

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetPurchaseProcessingBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import java.io.Serializable

/**
 * Bottom sheet for displaying purchase processing states
 * Provides visual feedback during subscription purchase, activation, and completion
 */
class PurchaseProcessingBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetPurchaseProcessingBinding? = null
    private val binding
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private var currentState: ProcessingState = ProcessingState.Processing
    private val persistentState by inject<PersistentState>()

    /**
     * Optional callback invoked when the user taps the primary action on an actionable
     * state (e.g. "Check Status" on [ProcessingState.PendingTimeout]). The hosting
     * Fragment supplies this before/after showing the sheet.
     */
    var primaryActionListener: (() -> Unit)? = null

    companion object {
        private const val TAG = "PurchaseProcessingBS"
        private const val ARG_STATE = "state"
        private const val ARG_MESSAGE = "message"

        fun newInstance(state: ProcessingState, message: String? = null): PurchaseProcessingBottomSheet {
            return PurchaseProcessingBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_STATE, state)
                    message?.let { putString(ARG_MESSAGE, it) }
                }
            }
        }
    }

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetPurchaseProcessingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        val state = if (isAtleastT()) {
            arguments?.getSerializable(ARG_STATE, ProcessingState::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable(ARG_STATE) as? ProcessingState
        } ?: ProcessingState.Processing

        val message = arguments?.getString(ARG_MESSAGE)

        updateState(state, message)
    }

    /**
     * Update the bottom sheet state
     */
    fun updateState(state: ProcessingState, message: String? = null) {
        currentState = state

        when (state) {
            ProcessingState.Processing -> showProcessing(message)
            ProcessingState.PendingVerification -> showPendingVerification(message)
            ProcessingState.Success -> showSuccess(message)
            ProcessingState.Error -> showError(message)
            ProcessingState.ServerAckPending -> showServerAckPending(message)
            ProcessingState.PendingTimeout -> showPendingTimeout(message)
            ProcessingState.PaymentDeclined -> showPaymentDeclined(message)
        }
    }

    private fun showProcessing(message: String?) {
        binding.progressIndicator.isVisible = true
        binding.statusIcon.isVisible = false
        binding.titleText.text = getString(R.string.processing_purchase)
        binding.messageText.text = message ?: getString(R.string.please_wait_processing)
        binding.actionButton.isVisible = false
        binding.secondaryButton.isVisible = false
    }

    private fun showPendingVerification(message: String?) {
        binding.progressIndicator.isVisible = true
        binding.statusIcon.isVisible = false
        binding.titleText.text = getString(R.string.verifying_purchase)
        binding.messageText.text = message ?: getString(R.string.verifying_with_play_store)
        binding.actionButton.isVisible = false
        binding.secondaryButton.isVisible = false
    }

    private fun showSuccess(message: String?) {
        binding.progressIndicator.isVisible = false
        binding.statusIcon.isVisible = true
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.titleText.text = getString(R.string.purchase_successful)
        binding.messageText.text = message ?: getString(R.string.subscription_activated)

        binding.actionButton.isVisible = true
        binding.actionButton.text = getString(R.string.rt_dialog_neutral)
        binding.actionButton.setOnClickListener {
            dismiss()
        }
        binding.secondaryButton.isVisible = false
    }

    private fun showError(message: String?) {
        binding.progressIndicator.isVisible = false
        binding.statusIcon.isVisible = true
        binding.statusIcon.setImageResource(R.drawable.ic_error_state)
        binding.titleText.text = getString(R.string.purchase_failed)
        binding.messageText.text = message ?: getString(R.string.something_went_wrong)

        binding.actionButton.isVisible = true
        binding.actionButton.text = getString(R.string.close)
        binding.actionButton.setOnClickListener {
            dismiss()
        }
        binding.secondaryButton.isVisible = false
    }

    private fun showServerAckPending(message: String?) {
        binding.progressIndicator.isVisible = true
        binding.statusIcon.isVisible = false
        binding.titleText.text = getString(R.string.server_ack_pending_title)
        binding.messageText.text = message ?: getString(R.string.server_ack_pending_message)

        binding.actionButton.isVisible = true
        binding.actionButton.text = getString(R.string.contact_support_title)
        binding.actionButton.setOnClickListener {
            dismiss()
        }
        binding.secondaryButton.isVisible = false
    }

    /**
     * Pending purchase timed out. Offers a "Check Status primary action that manually re-queries
     * Google Play, and a "Contact Support" secondary action.
     */
    private fun showPendingTimeout(message: String?) {
        binding.progressIndicator.isVisible = false
        binding.statusIcon.isVisible = true
        binding.statusIcon.setImageResource(R.drawable.ic_error_state)
        binding.titleText.text = getString(R.string.pending_timeout_title)
        binding.messageText.text = message ?: getString(R.string.pending_timeout_message)

        binding.actionButton.isVisible = true
        binding.actionButton.text = getString(R.string.check_status)
        binding.actionButton.setOnClickListener {
            primaryActionListener?.invoke()
        }
        binding.secondaryButton.isVisible = true
        binding.secondaryButton.text = getString(R.string.contact_support_title)
        binding.secondaryButton.setOnClickListener {
            dismiss()
        }
    }

    /**
     * Payment was declined / canceled after pending.
     */
    private fun showPaymentDeclined(message: String?) {
        binding.progressIndicator.isVisible = false
        binding.statusIcon.isVisible = true
        binding.statusIcon.setImageResource(R.drawable.ic_error_state)
        binding.titleText.text = getString(R.string.purchase_failed)
        binding.messageText.text = message ?: getString(R.string.billing_err_payment_declined)

        binding.actionButton.isVisible = true
        binding.actionButton.text = getString(R.string.retry)
        binding.actionButton.setOnClickListener {
            primaryActionListener?.invoke()
        }
        binding.secondaryButton.isVisible = true
        binding.secondaryButton.text = getString(R.string.contact_support_title)
        binding.secondaryButton.setOnClickListener {
            dismiss()
        }
    }

    override fun dismiss() {
        // Check if fragment is in valid state before dismissing
        if (isAdded && !isStateSaved) {
            super.dismiss()
        }
    }

    override fun dismissAllowingStateLoss() {
        // Safe dismiss that works even after onSaveInstanceState
        if (isAdded) {
            super.dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    enum class ProcessingState : Serializable {
        Processing,
        PendingVerification,
        Success,
        Error,
        ServerAckPending,
        PendingTimeout,
        PaymentDeclined
    }
}
