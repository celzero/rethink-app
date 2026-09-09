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

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.databinding.FragmentRethinkPlusManagePurchaseBinding
import com.celzero.bravedns.iab.AckFailureInfo
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseConflictNotifier
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver.PurchaseUiModel
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.CustomerSupportActivity
import com.celzero.bravedns.ui.activity.ServerOrderHistoryActivity
import com.celzero.bravedns.ui.bottomsheet.DeviceAuthErrorBottomSheet
import com.celzero.bravedns.ui.bottomsheet.DeviceNotRegisteredBottomSheet
import com.celzero.bravedns.ui.bottomsheet.PurchaseConflictBottomSheet
import com.celzero.bravedns.ui.custom.EmbeddedDolphinContent
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.ManagePurchaseViewModel
import com.celzero.bravedns.viewmodel.ManagePurchaseViewModel.OperationState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RethinkPlusManagePurchaseFragment : Fragment(R.layout.fragment_rethink_plus_manage_purchase) {
    private val b by viewBinding(FragmentRethinkPlusManagePurchaseBinding::bind)

    private val subscriptionStatusRepository by inject<SubscriptionStatusRepository>()
    private val viewModel: ManagePurchaseViewModel by viewModel()

    companion object {
        private const val TAG = "RPNManagePurchaseFrag"
        private const val ROW_DISABLED_ALPHA = 0.38f

        fun newInstance(): RethinkPlusManagePurchaseFragment {
            return RethinkPlusManagePurchaseFragment()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!isAdded) return
        initView()
        setupClickListeners()
        setupServerErrorObserver()
        observeSubscriptionState()
        observeOperationState()
        if (!Utilities.isFdroidFlavour()) {
            observeAckFailureState()
        }
    }

    private fun initView() {
        loadSubscriptionDetails()
        setDolphinSignature()
    }

    /** Dolphin signature (at the end of the manage-purchase content.); random pairing, fresh on every visit. */
    private fun setDolphinSignature() {
        b.dolphinSignature.setContent(EmbeddedDolphinContent.random())
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) loadSubscriptionDetails()
        InAppBillingHandler.enableInAppMessaging(requireActivity())
    }

    private fun loadSubscriptionDetails() {
        io {
            // Repository prefers valid (Active-first) rows; the raw DAO query returns the
            // most recently touched row of ANY status (including Expired).
            val sub = runCatching { subscriptionStatusRepository.getCurrentSubscription() }.getOrNull()
            val state = RpnProxyManager.getSubscriptionState()
            val subscriptionData = RpnProxyManager.getSubscriptionData()
            val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
            val expiry = VpnController.getWinExpiryTs() ?: 0L
            val hex = expiry.toString(16)
            val who = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()
            uiCtx { populateView(sub, state, subscriptionData, deviceId, hex, who) }
        }
    }

    private fun populateView(
        sub: SubscriptionStatus?,
        state: SubscriptionStateMachineV2.SubscriptionState,
        subscriptionData: SubscriptionStateMachineV2.SubscriptionData?,
        realDeviceId: String,
        expiry: String,
        who: String = ""
    ) {
        if (!isAdded) return

        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val model = SubscriptionUiStateResolver.resolve(state, sub)

        val colorGood = UIUtils.fetchColor(requireContext(), R.attr.accentGood)
        val colorBad = UIUtils.fetchColor(requireContext(), R.attr.accentBad)
        val colorDim = UIUtils.fetchColor(requireContext(), R.attr.primaryLightColorText)

        // A CANCELLED DB row must not render as "Active" unless Play still reports an
        // auto-renewing purchase for this token (a stale/wrong row that the next
        // reconcile heals to ACTIVE). Cold-start restoration puts the machine in Active
        // while the row says CANCELLED; without this guard the screen shows the machine
        // state and the DB truth simultaneously (Active chip + cancelled entitlement).
        val dbRowCancelled = sub?.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
        val playConfirmsRenewal = subscriptionData?.purchaseDetail?.isAutoRenewing == true
        val effectivelyCancelled = dbRowCancelled && !playConfirmsRenewal

        val (statusText, statusColor) = when (model) {
            is PurchaseUiModel.Loading -> getString(R.string.rpn_status_syncing) to colorDim
            is PurchaseUiModel.NoPurchase -> getString(R.string.rpn_status_no_plan) to colorDim
            else -> when (state) {
                is SubscriptionStateMachineV2.SubscriptionState.Active ->
                    if (effectivelyCancelled) getString(R.string.lbl_cancelled) to colorBad
                    else getString(R.string.lbl_active) to colorGood
                is SubscriptionStateMachineV2.SubscriptionState.Grace -> getString(R.string.lbl_grace_period) to colorGood
                is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> getString(R.string.lbl_cancelled) to colorBad
                is SubscriptionStateMachineV2.SubscriptionState.Expired -> getString(R.string.lbl_expired) to colorBad
                is SubscriptionStateMachineV2.SubscriptionState.Revoked -> getString(R.string.status_revoked) to colorBad
                is SubscriptionStateMachineV2.SubscriptionState.Paused -> getString(R.string.lbl_paused) to colorDim
                is SubscriptionStateMachineV2.SubscriptionState.OnHold -> getString(R.string.lbl_paused) to colorDim
                else -> getString(R.string.placeholder_dash) to colorDim
            }
        }
        b.tvManageStatusText.text = statusText
        b.tvManageStatusText.setTextColor(statusColor)

        b.tvHeroWho.isVisible = who.isNotEmpty()
        b.tvHeroWho.text = who

        when (model) {
            is PurchaseUiModel.Loading -> return // suppress paint until state resolves

            is PurchaseUiModel.NoPurchase -> {
                b.tvManagePlanName.text = getString(R.string.rpn_no_active_plan_title)
                b.tvManagePurchasedDate.text = getString(R.string.lbl_not_available_short)
                b.tvManageToken.text = getString(R.string.lbl_not_available_short)
            }

            is PurchaseUiModel.Lapsed -> {
                b.tvManagePlanName.text = resolvePlanName(subscriptionData).ifBlank {
                    resolvePlanName(
                        model.sub?.productId.orEmpty(),
                        model.sub?.planId.orEmpty(),
                        model.sub?.productTitle.orEmpty()
                    )
                }.ifBlank { getString(R.string.lbl_not_available_short) }
                b.tvManagePurchasedDate.text = if (model.sub != null && model.sub.purchaseTime > 0) {
                    getString(R.string.rpn_overhauled_purchased_date_label, fmt.format(Date(model.sub.purchaseTime)))
                } else {
                    getString(R.string.lbl_not_available_short)
                }
                b.tvManageToken.text = formatToken(model.sub, realDeviceId, expiry)
            }

            is PurchaseUiModel.Valid -> {
                b.tvManagePlanName.text = resolvePlanName(subscriptionData).ifBlank {
                    resolvePlanName(
                        sub?.productId.orEmpty(),
                        sub?.planId.orEmpty(),
                        sub?.productTitle.orEmpty()
                    )
                }.capitalizeWords()
                b.tvManagePurchasedDate.text = if (sub != null && sub.purchaseTime > 0) {
                    getString(R.string.rpn_overhauled_purchased_date_label, fmt.format(Date(sub.purchaseTime)))
                } else {
                    getString(R.string.placeholder_dash)
                }
                b.tvManageToken.text = formatToken(sub, realDeviceId, expiry)
            }
        }

        showCancelOrRevokeButton(subscriptionData, state, effectivelyCancelled)
        gateActionRows(model, sub)
        gateManageOnPlayRow(sub, subscriptionData)

        if (!Utilities.isFdroidFlavour()) {
            updateAckFailureBanner(InAppBillingHandler.ackFailureFlow.value)
        }
    }

    /**
     * Same value that [RethinkPlusDashboardFragment], [CustomerSupportActivity] and
     * [ServerOrderHistoryActivity] show as their hero's last line: purchase token
     * (first 12 chars) · accountId (first 12 chars) • deviceId (first 4 chars).
     */
    private fun formatToken(sub: SubscriptionStatus?, realDeviceId: String, expiry: String): String {
        val line = heroIdentityLine(sub?.purchaseToken.orEmpty(), sub?.accountId.orEmpty(), realDeviceId, expiry)
        return line.ifBlank { getString(R.string.lbl_not_available_short) }
    }

    private fun heroIdentityLine(token: String, accountId: String, deviceId: String, expiry: String): String {
        val t = token.take(12)
        val a = accountId.take(12)
        val d = deviceId.take(4)
        val idPart = listOf(a, d).filter { it.isNotBlank() }.joinToString(" • ")
        return listOf(t, idPart, expiry).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * Entitlement-dependent rows stay visible but disabled (uniform 0.38 alpha, dimmed
     * ripple) when there is no active plan; Manage-on-Play is additionally disabled when
     * nothing was ever purchased since the Play deep-link requires a product id.
     */
    private fun gateActionRows(model: PurchaseUiModel, sub: SubscriptionStatus?) {
        setRowAvailability(b.rowOrderHistory, enabled = sub != null)
        setRowAvailability(b.rowManageOnPlay, enabled = model !is PurchaseUiModel.NoPurchase)
    }

    /**
     * Manage-on-Play is only relevant to auto-renewing Play subscriptions: hide the
     * row (and its divider) entirely for one-time (in-app) purchases.
     */
    private fun gateManageOnPlayRow(
        sub: SubscriptionStatus?,
        subscriptionData: SubscriptionStateMachineV2.SubscriptionData?
    ) {
        val productId = sub?.productId ?: subscriptionData?.purchaseDetail?.productId.orEmpty()
        val planId = sub?.planId ?: subscriptionData?.purchaseDetail?.planId.orEmpty()
        val isOneTimePurchase = productId.isNotEmpty() && isInAppProduct(productId, planId)

        b.rowManageOnPlay.isVisible = !isOneTimePurchase
        b.dividerManagePlay.isVisible = !isOneTimePurchase
    }

    private fun setRowAvailability(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else ROW_DISABLED_ALPHA
        if (!enabled) row.contentDescription = getString(R.string.rpn_unavailable_without_plan)
    }

    private fun setupClickListeners() {
        b.rowOrderHistory.setOnClickListener { openServerOrderHistory() }
        b.rowManageOnPlay.setOnClickListener { managePlayStoreSubs() }
        b.rowReportBillingIssue.setOnClickListener { CustomerSupportActivity.start(requireContext()) }
        b.rowRequestRefund.setOnClickListener { showDialogConfirmCancelOrRevoke(isCancel = false) }
        b.rowCancelPurchase.setOnClickListener { showDialogConfirmCancelOrRevoke(isCancel = true) }
        b.tvHeroWho.setOnClickListener { copyWhoToClipboard() }
    }

    /** Copies the hero "who" line to the clipboard. */
    private fun copyWhoToClipboard() {
        val text = b.tvHeroWho.text?.toString().orEmpty()
        if (text.isBlank()) return
        val clipboard =
            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("who", text))
        showToastUiCentered(requireContext(), getString(R.string.copied_clipboard), Toast.LENGTH_SHORT)
    }

    private fun showCancelOrRevokeButton(
        subscriptionData: SubscriptionStateMachineV2.SubscriptionData?,
        state: SubscriptionStateMachineV2.SubscriptionState,
        dbRowCancelled: Boolean
    ) {
        val planId  = subscriptionData?.purchaseDetail?.planId.orEmpty()
        val isInApp = isInAppProduct(subscriptionData?.purchaseDetail?.productId.orEmpty(), planId)

        b.rowCancelPurchase.isVisible = false
        b.rowRequestRefund.isVisible = false
        b.dividerRefund.isVisible = false
        b.tvEndNote.isVisible = false

        // dbRowCancelled covers the cold-start restoration case where the machine is
        // Active-in-memory but the persisted row is CANCELLED (and Play does not report
        // a renewal): the plan is already ending — never offer "Cancel purchase" again.
        if (!state.isActive || dbRowCancelled) {
            // Collapse the entire "Ending your plan" section: never render an empty card shell.
            b.tvEndingPlanHeader.isVisible = false
            b.cardEndingPlan.isVisible = false
            return
        }

        val canRevoke = canRevoke(subscriptionData)
        if (canRevoke) {
            b.rowRequestRefund.isVisible = true
            b.tvEndNote.text = getString(R.string.revoke_subscription_note)
        } else if (!isInApp) {
            b.rowCancelPurchase.isVisible = true
            b.tvEndNote.text = getString(R.string.cancel_subscription_note_future)
        }

        // Never render a dangling "Ending your plan" header or an empty card shell:
        // the section is shown only when at least one action row is visible (e.g. a
        // one-time purchase past its revoke window has neither cancel nor refund).
        val hasEndingOption = b.rowRequestRefund.isVisible || b.rowCancelPurchase.isVisible
        b.tvEndingPlanHeader.isVisible = hasEndingOption
        b.cardEndingPlan.isVisible = hasEndingOption
        b.tvEndNote.isVisible = hasEndingOption

        if (b.rowRequestRefund.isVisible && b.rowCancelPurchase.isVisible) {
            b.dividerRefund.isVisible = true
        }
    }

    private fun canRevoke(subscriptionData: SubscriptionStateMachineV2.SubscriptionData?): Boolean {
        val purchaseTs = subscriptionData?.subscriptionStatus?.purchaseTime ?: return false
        if (purchaseTs <= 0) return false
        val status = subscriptionData.subscriptionStatus.status
        if (status != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) return false

        val planId = subscriptionData.purchaseDetail?.planId.orEmpty()
        val productId = subscriptionData.purchaseDetail?.productId.orEmpty()
        val revokeWindowMs = when {
            productId == InAppBillingHandler.ONE_TIME_PRODUCT_2YRS ||
                planId == InAppBillingHandler.ONE_TIME_PRODUCT_2YRS ->
                InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS * 24 * 60 * 60 * 1000L
            productId == InAppBillingHandler.ONE_TIME_PRODUCT_5YRS ||
                planId == InAppBillingHandler.ONE_TIME_PRODUCT_5YRS ->
                InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS * 24 * 60 * 60 * 1000L
            productId == InAppBillingHandler.SUBS_PRODUCT_YEARLY ||
                planId == InAppBillingHandler.SUBS_PRODUCT_YEARLY ->
                InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS * 24 * 60 * 60 * 1000L
            productId == InAppBillingHandler.SUBS_PRODUCT_MONTHLY ||
                planId == InAppBillingHandler.SUBS_PRODUCT_MONTHLY ->
                InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS * 24 * 60 * 60 * 1000L
            isInAppProduct(productId, planId) -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS * 24 * 60 * 60 * 1000L
            else -> InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS * 24 * 60 * 60 * 1000L
        }
        return (System.currentTimeMillis() - purchaseTs) < revokeWindowMs
    }

    private fun showDialogConfirmCancelOrRevoke(isCancel: Boolean) {
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(if (isCancel) getString(R.string.confirm_cancel_title) else getString(R.string.confirm_revoke_title))
            .setMessage(if (isCancel) getString(R.string.confirm_cancel_message) else getString(R.string.confirm_revoke_message))
            .setPositiveButton(if (isCancel) getString(R.string.cancel_subscription) else getString(R.string.revoke_subscription)) { _, _ ->
                if (isCancel) viewModel.cancelSubscription() else viewModel.revokeSubscription()
            }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .setCancelable(true)
            .show()
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
                            loadSubscriptionDetails()
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
        val opLabel = if (state.isCancel)
            getString(R.string.manage_sub_cancelling)
        else
            getString(R.string.manage_sub_revoking)

        b.tvLoadingMessage.text = opLabel
        b.tvLoadingSubMessage.text = getString(R.string.progress_do_not_close)

        val currentOrdinal = state.step.ordinal
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
    }

    private fun observeAckFailureState() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            InAppBillingHandler.ackFailureFlow.collect { info ->
                updateAckFailureBanner(info)
            }
        }
    }

    private fun updateAckFailureBanner(info: AckFailureInfo?) {
        try {
            if (info == null) return
            Logger.i(LOG_TAG_UI, "$TAG ack-failure banner shown: title=${info.title}")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG updateAckFailureBanner error (non-fatal): ${e.message}")
        }
    }

    private fun observeSubscriptionState() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            RpnProxyManager.collectSubscriptionState().collect { state ->
                val sub = runCatching { subscriptionStatusRepository.getCurrentSubscription() }.getOrNull()
                val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
                val subscriptionData = RpnProxyManager.getSubscriptionData()
                val expiry = VpnController.getWinExpiryTs() ?: 0L
                val hex = expiry.toString(16)
                val who = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()
                uiCtx { populateView(sub, state, subscriptionData, deviceId, hex, who) }
            }
        }
    }

    private fun setupServerErrorObserver() {
        InAppBillingHandler.serverApiErrorLiveData.observe(viewLifecycleOwner) { error ->
            error ?: return@observe
            InAppBillingHandler.serverApiErrorLiveData.value = null
            when (error) {
                is ServerApiError.Conflict409 -> showConflictBottomSheet(error)
                is ServerApiError.Unauthorized401 -> showDeviceAuthErrorBottomSheet(error)
                is ServerApiError.DeviceNotRegistered -> showDeviceNotRegisteredBottomSheet(error)
                is ServerApiError.GenericError -> showToastUiCentered(requireContext(), error.message, Toast.LENGTH_LONG)
                is ServerApiError.NetworkError -> showToastUiCentered(
                    requireContext(),
                    error.message ?: getString(R.string.subscription_action_failed),
                    Toast.LENGTH_LONG
                )
                is ServerApiError.None -> { /* no-op */ }
            }
        }
    }

    private fun showDeviceNotRegisteredBottomSheet(error: ServerApiError.DeviceNotRegistered) {
        if (!isAdded || isStateSaved) return
        DeviceNotRegisteredNotifier.cancel(requireContext())
        DeviceNotRegisteredBottomSheet.newInstance(error).show(childFragmentManager, "deviceNotRegistered")
    }

    private fun showDeviceAuthErrorBottomSheet(error: ServerApiError.Unauthorized401) {
        if (!isAdded || isStateSaved) return
        DeviceAuthErrorBottomSheet.newInstance(error).show(childFragmentManager, "deviceAuthError401")
    }

    private fun showConflictBottomSheet(error: ServerApiError.Conflict409) {
        if (!isAdded || isStateSaved) return
        if (childFragmentManager.findFragmentByTag("conflict409") != null) return
        PurchaseConflictNotifier.cancel(requireContext())
        val sheet = PurchaseConflictBottomSheet.newInstance(error)
        sheet.onRefundResult = { success, _ ->
            if (success) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { initView() }
            }
        }
        sheet.show(childFragmentManager, "conflict409")
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
            UIUtils.openUrl(requireContext(), link)
            InAppBillingHandler.fetchPurchases(
                listOf(InAppBillingHandler.PRODUCT_TYPE_SUBS, InAppBillingHandler.PRODUCT_TYPE_INAPP)
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err managing play store subs: ${e.message}", e)
            showToastUiCentered(requireContext(), getString(R.string.error_loading_manage_subscription), Toast.LENGTH_SHORT)
        }
    }

    private fun openServerOrderHistory() {
        try {
            startActivity(Intent(requireContext(), ServerOrderHistoryActivity::class.java))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG openServerOrderHistory error: ${e.message}", e)
            showToastUiCentered(requireContext(), getString(R.string.server_order_open_error), Toast.LENGTH_SHORT)
        }
    }

    private fun resolvePlanName(subscriptionData: SubscriptionStateMachineV2.SubscriptionData?): String {
        if (subscriptionData == null) return ""
        return resolvePlanName(
            productId = subscriptionData.purchaseDetail?.productId.orEmpty(),
            planId = subscriptionData.purchaseDetail?.planId.orEmpty(),
            fallbackTitle = subscriptionData.purchaseDetail?.productTitle.orEmpty()
        )
    }

    private fun resolvePlanName(productId: String, planId: String, fallbackTitle: String = ""): String {
        when (planId) {
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> return getString(R.string.plan_2yr)
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> return getString(R.string.plan_5yr)
            InAppBillingHandler.SUBS_PRODUCT_YEARLY -> return getString(R.string.billing_yearly)
            InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> return getString(R.string.monthly_plan)
        }
        return when (productId) {
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> getString(R.string.plan_2yr)
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> getString(R.string.plan_5yr)
            InAppBillingHandler.SUBS_PRODUCT_YEARLY -> getString(R.string.billing_yearly)
            InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> getString(R.string.monthly_plan)
            else -> fallbackTitle.ifEmpty { productId }
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

    private suspend fun uiCtx(f: suspend () -> Unit) =
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }
}
