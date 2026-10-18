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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.purchase.*

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastT
import org.koin.android.ext.android.inject
import java.io.Serializable

/**
 * Bottom sheet for displaying purchase processing states
 * Provides visual feedback during subscription purchase, activation, and completion
 */
class PurchaseProcessingBottomSheet : BaseBottomSheetDialogFragment() {

    private var primaryAction: (() -> Unit)? = null
    private var presentation: ProcessingStateUi? = null
    private val ui get() = checkNotNull(presentation) { "Presentation accessed outside of view lifecycle" }

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        presentation = ProcessingStateUi()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { ProcessingContent(ui, onPrimary = { primaryAction?.invoke() }, onSecondary = { dismiss() }) } }
        }
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
        ui.progress = true
        ui.iconVisible = false
        ui.title = getString(R.string.processing_purchase)
        ui.message = message ?: getString(R.string.please_wait_processing)
        ui.primaryVisible = false
        ui.secondaryVisible = false
    }

    private fun showPendingVerification(message: String?) {
        ui.progress = true
        ui.iconVisible = false
        ui.title = getString(R.string.verifying_purchase)
        ui.message = message ?: getString(R.string.verifying_with_play_store)
        ui.primaryVisible = false
        ui.secondaryVisible = false
    }

    private fun showSuccess(message: String?) {
        ui.progress = false
        ui.iconVisible = true
        ui.icon = R.drawable.ic_check_circle
        ui.title = getString(R.string.purchase_successful)
        ui.message = message ?: getString(R.string.subscription_activated)

        ui.primaryVisible = true
        ui.primaryText = getString(R.string.rt_dialog_neutral)
        primaryAction = {
            dismiss()
        }
        ui.secondaryVisible = false
    }

    private fun showError(message: String?) {
        ui.progress = false
        ui.iconVisible = true
        ui.icon = R.drawable.ic_error_state
        ui.title = getString(R.string.purchase_failed)
        ui.message = message ?: getString(R.string.something_went_wrong)

        ui.primaryVisible = true
        ui.primaryText = getString(R.string.close)
        primaryAction = {
            dismiss()
        }
        ui.secondaryVisible = false
    }

    private fun showServerAckPending(message: String?) {
        ui.progress = true
        ui.iconVisible = false
        ui.title = getString(R.string.server_ack_pending_title)
        ui.message = message ?: getString(R.string.server_ack_pending_message)

        ui.primaryVisible = true
        ui.primaryText = getString(R.string.contact_support_title)
        primaryAction = {
            dismiss()
        }
        ui.secondaryVisible = false
    }

    /**
     * Pending purchase timed out. Offers a "Check Status primary action that manually re-queries
     * Google Play, and a "Contact Support" secondary action.
     */
    private fun showPendingTimeout(message: String?) {
        ui.progress = false
        ui.iconVisible = true
        ui.icon = R.drawable.ic_error_state
        ui.title = getString(R.string.pending_timeout_title)
        ui.message = message ?: getString(R.string.pending_timeout_message)

        ui.primaryVisible = true
        ui.primaryText = getString(R.string.check_status)
        primaryAction = {
            primaryActionListener?.invoke()
        }
        ui.secondaryVisible = true
        ui.secondaryText = getString(R.string.contact_support_title)

    }

    /**
     * Payment was declined / canceled after pending.
     */
    private fun showPaymentDeclined(message: String?) {
        ui.progress = false
        ui.iconVisible = true
        ui.icon = R.drawable.ic_error_state
        ui.title = getString(R.string.purchase_failed)
        ui.message = message ?: getString(R.string.billing_err_payment_declined)

        ui.primaryVisible = true
        ui.primaryText = getString(R.string.retry)
        primaryAction = {
            primaryActionListener?.invoke()
        }
        ui.secondaryVisible = true
        ui.secondaryText = getString(R.string.contact_support_title)

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
        presentation = null
        primaryAction = null
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
