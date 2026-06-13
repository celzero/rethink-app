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

import Logger
import Logger.LOG_IAB
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetPurchaseConflictBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.CustomerSupportActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Bottom sheet shown whenever an [com.celzero.bravedns.customdownloader.IBillingServerApi] server
 * call returns HTTP 409 (Conflict).
 *
 * Surfaces:
 * - Which endpoint returned the conflict
 * - A human-readable explanation appropriate to the operation
 * - A **Refund** action (calls `/g/refund` via [InAppBillingHandler]) when applicable
 * - A **Manage on Google Play** fallback link
 *
 * The sheet is self-contained: it receives a [ServerApiError.Conflict409] via [newInstance],
 * performs the refund call itself, and delivers the result via [onRefundResult].
 */
class PurchaseConflictBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetPurchaseConflictBinding? = null
    private val binding
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    /** Called on the main thread when a refund completes (success or failure). */
    var onRefundResult: ((success: Boolean, message: String) -> Unit)? = null

    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "ConflictBS"
        private const val ARG_ENDPOINT = "endpoint"
        private const val ARG_OPERATION = "operation"
        private const val ARG_SERVER_MSG = "server_message"
        private const val ARG_ACCOUNT_ID = "account_id"
        private const val ARG_PURCHASE_TOKEN = "purchase_token"
        private const val ARG_SKU = "sku"

        /**
         * Create a new instance from a [ServerApiError.Conflict409].
         *
         * All data is passed via [Bundle] args so the fragment survives
         * configuration changes without holding a live reference to the error object.
         * Note: deviceId is intentionally excluded, it is fetched fresh from
         * [SecureIdentityStore] via [InAppBillingHandler.getObfuscatedDeviceId] at
         * refund time so it is never written to saved instance state.
         */
        fun newInstance(error: ServerApiError.Conflict409): PurchaseConflictBottomSheet {
            return PurchaseConflictBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ENDPOINT, error.endpoint)
                    putString(ARG_OPERATION, error.operation.name)
                    putString(ARG_SERVER_MSG, error.serverMessage)
                    putString(ARG_ACCOUNT_ID, error.accountId)
                    putString(ARG_PURCHASE_TOKEN, error.purchaseToken)
                    putString(ARG_SKU, error.sku)
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
        _binding = BottomsheetPurchaseConflictBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        val args = requireArguments()
        val operation = ServerApiError.Operation.valueOf(
            args.getString(ARG_OPERATION, ServerApiError.Operation.CANCEL.name)
        )
        val endpoint = args.getString(ARG_ENDPOINT, operation.endpoint)
        val serverMsg = args.getString(ARG_SERVER_MSG)
        val accountId  = args.getString(ARG_ACCOUNT_ID, "")
        val purchaseToken = args.getString(ARG_PURCHASE_TOKEN, "")
        val sku = args.getString(ARG_SKU, "")

        applyContent(operation, endpoint, serverMsg)
        setupButtons(operation, accountId, purchaseToken, sku)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyContent(
        operation: ServerApiError.Operation,
        endpoint: String,
        serverMsg: String?
    ) {
        binding.tvTitle.text = titleFor(operation)
        binding.tvDescription.text = descriptionFor(operation)
        binding.tvEndpoint.text = endpoint
        binding.tvHttpCode.text = "409"

        if (!serverMsg.isNullOrBlank()) {
            binding.serverMessageRow.isVisible = true
            binding.tvServerMessage.isVisible = true
            binding.tvServerMessage.text = serverMsg
        }

        binding.btnRefund.isVisible = operation.canRefund
        if (operation.canRefund) {
            binding.btnRefund.text = getString(R.string.conflict_btn_refund)
        } else {
            binding.btnRefund.isVisible = false
        }
    }

    private fun setupButtons(
        operation: ServerApiError.Operation,
        accountId: String,
        purchaseToken: String,
        sku: String
    ) {
        binding.btnRefund.setOnClickListener {
            initiateRefund(accountId, purchaseToken, sku)
        }

        binding.btnContactSupport.setOnClickListener {
            CustomerSupportActivity.start(requireContext())
        }

        binding.btnDismiss.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    private fun initiateRefund(accountId: String, purchaseToken: String, sku: String) {
        if (accountId.isBlank() || purchaseToken.isBlank()) {
            Logger.w(LOG_IAB, "$TAG: cannot refund, missing accountId or purchaseToken")
            showToastUiCentered(
                requireContext(),
                getString(R.string.subscription_action_failed),
                Toast.LENGTH_SHORT
            )
            return
        }

        setRefundInFlight(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
            if (deviceId.isBlank()) {
                Logger.e(LOG_IAB, "$TAG: refund aborted, deviceId unavailable from SecureIdentityStore")
                withContext(Dispatchers.Main) {
                    setRefundInFlight(false)
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.subscription_action_failed),
                        Toast.LENGTH_SHORT
                    )
                }
                return@launch
            }
            val result = try {
                Logger.i(LOG_IAB, "$TAG: initiating refund for accountId-len=${accountId.length}, sku=$sku")
                InAppBillingHandler.revokeSubscription(accountId, deviceId, purchaseToken, sku)
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: refund exception: ${e.message}", e)
                Pair(false, e.message ?: getString(R.string.subscription_action_failed))
            }

            withContext(Dispatchers.Main) {
                setRefundInFlight(false)
                if (result.first) {
                    Logger.i(LOG_IAB, "$TAG: refund success")
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.conflict_refund_success),
                        Toast.LENGTH_LONG
                    )
                    onRefundResult?.invoke(true, result.second)
                    dismissAllowingStateLoss()
                } else {
                    Logger.w(LOG_IAB, "$TAG: refund failed: ${result.second}")
                    // Update button to Retry and show the error inline
                    binding.btnRefund.text = getString(R.string.conflict_btn_refund_retry)
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.conflict_refund_failed, result.second),
                        Toast.LENGTH_LONG
                    )
                    onRefundResult?.invoke(false, result.second)
                }
            }
        }
    }

    private fun setRefundInFlight(inFlight: Boolean) {
        binding.progressRefund.isVisible = inFlight
        binding.btnRefund.isEnabled = !inFlight
        binding.btnContactSupport.isEnabled  = !inFlight
        binding.btnDismiss.isEnabled = !inFlight
        isCancelable = !inFlight
    }

    override fun dismiss() {
        if (isAdded && !isStateSaved) super.dismiss()
    }

    override fun dismissAllowingStateLoss() {
        if (isAdded) super.dismissAllowingStateLoss()
    }

    private fun titleFor(op: ServerApiError.Operation): String = when (op) {
        ServerApiError.Operation.CANCEL -> getString(R.string.conflict_title_cancel)
        ServerApiError.Operation.REVOKE -> getString(R.string.conflict_title_revoke)
        ServerApiError.Operation.ACKNOWLEDGE -> getString(R.string.conflict_title_ack)
        ServerApiError.Operation.CONSUME -> getString(R.string.conflict_title_consume)
        ServerApiError.Operation.DEVICE -> getString(R.string.conflict_title_device)
        ServerApiError.Operation.CUSTOMER -> getString(R.string.conflict_title_customer)
    }

    private fun descriptionFor(op: ServerApiError.Operation): String = when (op) {
        ServerApiError.Operation.CANCEL -> getString(R.string.conflict_desc_cancel)
        ServerApiError.Operation.REVOKE -> getString(R.string.conflict_desc_revoke)
        ServerApiError.Operation.ACKNOWLEDGE -> getString(R.string.conflict_desc_ack)
        ServerApiError.Operation.CONSUME -> getString(R.string.conflict_desc_consume)
        ServerApiError.Operation.DEVICE -> getString(R.string.conflict_desc_device)
        ServerApiError.Operation.CUSTOMER -> getString(R.string.conflict_desc_customer)
    }
}

