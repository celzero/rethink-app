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
import Logger.LOG_TAG_UI
import android.content.res.Configuration
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetManageRpnPurchaseBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
import com.celzero.bravedns.iab.InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
import com.celzero.bravedns.iab.InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
import com.celzero.bravedns.iab.InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.ManagePurchaseViewModel
import com.celzero.bravedns.viewmodel.ManagePurchaseViewModel.OperationState
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ManageRpnPurchaseBtmSht : BottomSheetDialogFragment() {

    private var _binding: BottomsheetManageRpnPurchaseBinding? = null
    private val b get() = checkNotNull(_binding) { "Binding accessed outside of view lifecycle" }

    private val viewModel: ManagePurchaseViewModel by viewModel()
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "ManageRpnPurchaseBtmSht"
        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

        fun newInstance(): ManageRpnPurchaseBtmSht = ManageRpnPurchaseBtmSht()
    }

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetManageRpnPurchaseBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        setupClickListeners()

        observeOperationState()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.operationState.value is OperationState.Idle) {
            initView()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun dismiss() {
        if (isAdded && !isStateSaved) super.dismiss()
    }

    override fun dismissAllowingStateLoss() {
        if (isAdded) super.dismissAllowingStateLoss()
    }

    private fun observeOperationState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.operationState.collect { state ->
                    when (state) {
                        is OperationState.Idle -> hideProgressOverlay()

                        is OperationState.InProgress -> showProgressOverlay(state)

                        is OperationState.Success -> {
                            hideProgressOverlay()
                            showToastUiCentered(requireContext(), state.message, Toast.LENGTH_SHORT)
                            initView()
                            viewModel.resetOperationState()
                        }

                        is OperationState.Failure -> {
                            hideProgressOverlay()
                            showToastUiCentered(requireContext(), state.message, Toast.LENGTH_LONG)
                            viewModel.resetOperationState()
                        }
                    }
                }
            }
        }
    }

    private fun showProgressOverlay(state: OperationState.InProgress) {
        b.loadingOverlay.isVisible = true
        // Block dismissal while the operation runs.
        isCancelable = false

        val opLabel = if (state.isCancel)
            getString(R.string.manage_sub_cancelling)
        else
            getString(R.string.manage_sub_revoking)

        b.tvLoadingMessage.text = opLabel
        b.tvLoadingSubMessage.text = getString(R.string.progress_do_not_close)

        val currentOrdinal = state.step.ordinal // VALIDATING=0, SERVER=1, LOCAL=2, REFRESH=3, DONE=4

        data class StepViews(val icon: AppCompatImageView, val label: AppCompatTextView)

        val steps = listOf(
            StepViews(b.stepIconValidating, b.stepLabelValidating),
            StepViews(b.stepIconServer,     b.stepLabelServer),
            StepViews(b.stepIconLocal,      b.stepLabelLocal),
            StepViews(b.stepIconRefresh,    b.stepLabelRefresh)
        )

        val colorDone    = UIUtils.fetchColor(requireContext(), R.attr.accentGood)
        val colorPending = UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor)

        steps.forEachIndexed { index, sv ->
            val isDone    = index < currentOrdinal
            val isCurrent = index == currentOrdinal
            val tint      = if (isDone || isCurrent) colorDone else colorPending
            sv.icon.setColorFilter(tint)
            if (isDone || isCurrent) {
                sv.label.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
                sv.label.alpha = 1f
            } else {
                sv.label.setTextColor(colorPending)
                sv.label.alpha = 0.5f
            }
        }
    }

    private fun hideProgressOverlay() {
        b.loadingOverlay.isVisible = false
        isCancelable = true
    }

    private fun initView() {
        try {
            val subscriptionData  = RpnProxyManager.getSubscriptionData()
            val subscriptionState = RpnProxyManager.getSubscriptionState()

            val hasSubscription = subscriptionData != null && subscriptionState.hasValidSubscription
            val isKnownExpiredOrCancelled = !hasSubscription &&
                    subscriptionData != null &&
                    (subscriptionState.state().isExpired || subscriptionState.state().isCancelled)

            if (!hasSubscription && !isKnownExpiredOrCancelled) {
                return
            }

            populateHeader(subscriptionData, subscriptionState)
            showCancelOrRevokeButton()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err initializing view: ${e.message}", e)
            showToastUiCentered(requireContext(), getString(R.string.error_loading_manage_subscription), Toast.LENGTH_SHORT)
        }
    }

    /**
     * Populates the subscription details header (plan, status, expiry).
     */
    private fun populateHeader(
        subscriptionData: SubscriptionStateMachineV2.SubscriptionData?,
        state: SubscriptionStateMachineV2.SubscriptionState
    ) {
        // Plan name
        b.tvSubPlan.text = resolvePlanName(subscriptionData).ifEmpty { getString(R.string.placeholder_dash) }.capitalizeWords()

        // Status label + colour
        val colorGood = UIUtils.fetchColor(requireContext(), R.attr.accentGood)
        val colorBad  = UIUtils.fetchColor(requireContext(), R.attr.accentBad)
        val colorDim  = UIUtils.fetchColor(requireContext(), R.attr.primaryLightColorText)
        val (statusText, statusColor) = when (state.state()) {
            is SubscriptionStateMachineV2.SubscriptionState.Active -> getString(R.string.lbl_active) to colorGood
            is SubscriptionStateMachineV2.SubscriptionState.Grace -> getString(R.string.lbl_grace_period) to colorGood
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> getString(R.string.lbl_cancelled) to colorBad
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> getString(R.string.lbl_expired) to colorBad
            is SubscriptionStateMachineV2.SubscriptionState.Paused -> getString(R.string.lbl_paused) to colorDim
            else -> getString(R.string.placeholder_dash) to colorDim
        }
        b.tvSubStatus.text = statusText
        b.tvSubStatus.setTextColor(statusColor)

        val billingExpiry = subscriptionData?.subscriptionStatus?.billingExpiry ?: 0L
        val hasExpiry = billingExpiry > 0L && billingExpiry != Long.MAX_VALUE && (
                subscriptionData?.subscriptionStatus?.let { isInAppProduct(it.productId, it.planId) } == true ||
                state.state().isExpired || state.state().isCancelled
        )
        b.rowSubExpiry.isVisible = hasExpiry
        if (hasExpiry) {
            val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            b.tvSubExpiry.text = fmt.format(Date(billingExpiry))
        }
    }

    private fun resolvePlanName(subscriptionData: SubscriptionStateMachineV2.SubscriptionData?): String {
        val productId = subscriptionData?.purchaseDetail?.productId.orEmpty()
        val planId    = subscriptionData?.purchaseDetail?.planId.orEmpty()
        return when {
            planId    == InAppBillingHandler.ONE_TIME_PRODUCT_2YRS  -> getString(R.string.plan_2yr)
            planId    == InAppBillingHandler.ONE_TIME_PRODUCT_5YRS  -> getString(R.string.plan_5yr)
            planId    == InAppBillingHandler.SUBS_PRODUCT_YEARLY    -> getString(R.string.billing_yearly)
            planId    == InAppBillingHandler.SUBS_PRODUCT_MONTHLY   -> getString(R.string.monthly_plan)
            productId == InAppBillingHandler.ONE_TIME_PRODUCT_2YRS  -> getString(R.string.plan_2yr)
            productId == InAppBillingHandler.ONE_TIME_PRODUCT_5YRS  -> getString(R.string.plan_5yr)
            productId == InAppBillingHandler.SUBS_PRODUCT_YEARLY    -> getString(R.string.billing_yearly)
            productId == InAppBillingHandler.SUBS_PRODUCT_MONTHLY   -> getString(R.string.monthly_plan)
            else -> subscriptionData?.purchaseDetail?.productTitle?.ifEmpty { productId } ?: productId
        }
    }

    private fun setupClickListeners() {
        b.tvManageSubscriptionOnGooglePlay.apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { managePlayStoreSubs() }
        }
        b.btnRevoke.setOnClickListener { showDialogConfirmCancelOrRevoke(isCancel = false) }
        b.btnCancel.setOnClickListener { showDialogConfirmCancelOrRevoke(isCancel = true) }
        b.btnResubscribe.setOnClickListener { launchResubscribe() }
        b.btnConsumePurchase.setOnClickListener { io { RpnProxyManager.consumePurchaseIfTest() } }
    }

    private fun showCancelOrRevokeButton() {
        try {
            io {
                val isTestEntitlement = RpnProxyManager.getIsTestEntitlement()
                uiCtx {
                    b.btnConsumePurchase.isVisible = isTestEntitlement
                }
            }
            val state            = RpnProxyManager.getSubscriptionState()
            val subscriptionData = RpnProxyManager.getSubscriptionData()

            b.btnCancel.isVisible      = false
            b.btnRevoke.isVisible      = false
            b.btnResubscribe.isVisible = false
            b.cancelNoteCard.isVisible = false

            val planId  = subscriptionData?.purchaseDetail?.planId.orEmpty()
            val isInApp = isInAppProduct(subscriptionData?.purchaseDetail?.productId.orEmpty(), planId)

            if (!state.state().isActive) {
                when {
                    state.state().isCancelled && !isInApp -> b.btnResubscribe.isVisible = true
                    else -> { /* expired / no subscription — nothing to show */ }
                }
                return
            }

            b.tvManageSubscriptionOnGooglePlay.isVisible = !isInApp

            if (canRevoke(subscriptionData)) {
                b.btnRevoke.isVisible      = true
                b.cancelNoteCard.isVisible = true
                b.tvCancelNote.text        = getString(R.string.revoke_subscription_note)
            } else if (!isInApp) {
                b.btnCancel.isVisible      = true
                b.cancelNoteCard.isVisible = true
                b.tvCancelNote.text        = getString(R.string.cancel_subscription_note_future)
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG error showing cancel/revoke button: ${e.message}", e)
            b.btnCancel.isVisible      = false
            b.btnRevoke.isVisible      = false
            b.cancelNoteCard.isVisible = false
        }
    }

    private fun canRevoke(subscriptionData: SubscriptionStateMachineV2.SubscriptionData?): Boolean {
        val purchaseTs = subscriptionData?.subscriptionStatus?.purchaseTime
        if (purchaseTs == null || purchaseTs <= 0) {
            Logger.w(LOG_TAG_UI, "$TAG purchase time is invalid, cannot determine revocation eligibility")
            return false
        }
        val planId = subscriptionData.purchaseDetail?.planId.orEmpty()
        val revokeWindowMs = when (planId) {
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS  -> REVOKE_WINDOW_ONE_TIME_2YRS_DAYS * ONE_DAY_MS
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS  -> REVOKE_WINDOW_ONE_TIME_5YRS_DAYS * ONE_DAY_MS
            InAppBillingHandler.SUBS_PRODUCT_YEARLY    -> REVOKE_WINDOW_SUBS_YEARLY_DAYS   * ONE_DAY_MS
            else                                       -> REVOKE_WINDOW_SUBS_MONTHLY_DAYS  * ONE_DAY_MS
        }
        return (System.currentTimeMillis() - purchaseTs) < revokeWindowMs
    }

    private fun showDialogConfirmCancelOrRevoke(isCancel: Boolean) {
        try {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (isCancel) getString(R.string.confirm_cancel_title) else getString(R.string.confirm_revoke_title))
                .setMessage(if (isCancel) getString(R.string.confirm_cancel_message) else getString(R.string.confirm_revoke_message))
                .setPositiveButton(if (isCancel) getString(R.string.cancel_subscription) else getString(R.string.revoke_subscription)) { _, _ ->
                    if (isCancel) viewModel.cancelSubscription() else viewModel.revokeSubscription()
                }
                .setNegativeButton(getString(R.string.lbl_cancel), null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG error showing confirmation dialog: ${e.message}", e)
        }
    }

    /**
     * Launches the Google Play billing flow for the current plan.
     * Used when the subscription is Canceled (auto-renewal off, still active).
     * No nested bottom sheet needed — Play shows a targeted resubscribe sheet itself.
     */
    private fun launchResubscribe() {
        val subscriptionData = RpnProxyManager.getSubscriptionData()
        val purchaseDetail   = subscriptionData?.purchaseDetail
        if (purchaseDetail == null || purchaseDetail.productId.isBlank() || purchaseDetail.planId.isBlank()) {
            Logger.w(LOG_TAG_UI, "$TAG: cannot resubscribe, missing purchaseDetail")
            showToastUiCentered(requireContext(), getString(R.string.resubscribe_error), Toast.LENGTH_SHORT)
            return
        }

        b.btnResubscribe.isEnabled      = false
        b.progressResubscribe.isVisible = true

        io {
            try {
                Logger.i(LOG_TAG_UI, "$TAG: launching resubscription for productId=${purchaseDetail.productId}, planId=${purchaseDetail.planId}")
                InAppBillingHandler.purchaseSubs(
                    activity = requireActivity(),
                    productId = purchaseDetail.productId,
                    planId = purchaseDetail.planId,
                    forceResubscribe = true
                )
                uiCtx { dismissAllowingStateLoss() }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG: resubscription failed: ${e.message}", e)
                uiCtx {
                    b.btnResubscribe.isEnabled      = true
                    b.progressResubscribe.isVisible = false
                    showToastUiCentered(requireContext(), getString(R.string.resubscribe_error), Toast.LENGTH_SHORT)
                }
            }
        }
    }

    private fun managePlayStoreSubs() {
        try {
            val productId = RpnProxyManager.getRpnProductId()
            if (productId.isEmpty()) {
                showToastUiCentered(requireContext(), getString(R.string.error_loading_manage_subscription), Toast.LENGTH_SHORT)
                return
            }
            val link = InAppBillingHandler.PLAY_SUBS_LINK
                .replace("$1", productId)
                .replace("$2", requireContext().packageName)
            openUrl(requireContext(), link)
            InAppBillingHandler.fetchPurchases(
                listOf(InAppBillingHandler.PRODUCT_TYPE_SUBS, InAppBillingHandler.PRODUCT_TYPE_INAPP)
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err managing play store subs: ${e.message}", e)
            showToastUiCentered(requireContext(), getString(R.string.error_loading_manage_subscription), Toast.LENGTH_SHORT)
        }
    }

    private fun isInAppProduct(productId: String, planId: String): Boolean {
        val inAppIds = setOf(
            InAppBillingHandler.ONE_TIME_PRODUCT_ID,
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS,
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS,
            InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID
        )
        return productId in inAppIds || planId in inAppIds
    }

    private suspend fun uiCtx(f: suspend () -> Unit) = withContext(Dispatchers.Main) { f() }
    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }
}
