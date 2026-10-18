/*
 * Copyright 2025 RethinkDNS and its authors
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

import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.ui.compose.RethinkTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.AckFailureInfo
import com.celzero.bravedns.iab.DeviceAuthErrorNotifier
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseConflictNotifier
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver.PurchaseUiModel
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.CustomerSupportActivity
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.PingTestActivity
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
import com.celzero.bravedns.viewmodel.ServerSelectionViewModel
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class RethinkPlusDashboardFragment : Fragment() {
    private lateinit var ui: RpnAccountState

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        ui = RpnAccountState(inflater.context)
        return ComposeView(inflater.context).apply {
            id = R.id.rpn_dash_root
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RpnAccountScreen(ui) } }
        }
    }

    private val subscriptionStatusRepository by inject<SubscriptionStatusRepository>()

    private val persistentState by inject<PersistentState>()

    /**
     * Activity-scoped ViewModel that owns the RPN reset coroutine so the IO work
     * survives dialog dismissal / fragment recreation. Mirrors the flow used by
     * [ServerSelectionFragment] and [com.celzero.bravedns.ui.bottomsheet.ServerSettingsBottomSheet].
     */
    private val serverSelectionViewModel: ServerSelectionViewModel by activityViewModel()

    /** Job driving the RPN reset progress loop. */
    private var rpnResetJob: Job? = null
    /** Dialog shown while RPN reset is in progress. */
    private var rpnResetDialog: android.app.Dialog? = null

    companion object {
        private const val TAG = "RPNDashFrag"
        private const val ARG_SHOW_MANAGE_PURCHASE = "arg_show_manage_purchase"
        /** Interval between reset-status poll iterations (kept in sync with ServerSelectionFragment). */
        private const val RESET_STATUS_POLL_INTERVAL_MS = 1_500L

        fun createBundle(showManagePurchase: Boolean): Bundle {
            return Bundle().apply {
                putBoolean(ARG_SHOW_MANAGE_PURCHASE, showManagePurchase)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!isAdded) return
        initView()
        setupClickListeners()
        setupServerErrorObserver()
        observeSubscriptionState()
        observeResetState()
        if (!Utilities.isFdroidFlavour()) {
            observeAckFailureState()
        }

        if (arguments?.getBoolean(ARG_SHOW_MANAGE_PURCHASE) == true) {
            arguments?.putBoolean(ARG_SHOW_MANAGE_PURCHASE, false) // reset so it doesn't show again on orientation change etc.
            showManagePurchase()
        }
    }

    private fun initView() {
        loadSubscriptionBanner()
        setDolphinSignature()
    }

    /** Dolphin signature: bottom overlay revealed at the end of the dashboard scroll. */
    private fun setDolphinSignature() {
        ui.dolphinSignature.setContent(EmbeddedDolphinContent.random())
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) loadSubscriptionBanner()
        InAppBillingHandler.enableInAppMessaging(requireActivity())
    }

    private fun loadSubscriptionBanner() {
        io {
            // Repository prefers valid (Active-first) rows; the raw DAO query returns the
            // most recently touched row of ANY status (including Expired).
            val sub = runCatching { subscriptionStatusRepository.getCurrentSubscription() }.getOrNull()
            val state = RpnProxyManager.getSubscriptionState()
            val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
            val expiry = VpnController.getWinExpiryTs() ?: 0L
            val hex = expiry.toString(16)
            val who = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()
            uiCtx { populateBanner(sub, state, deviceId, hex, who) }
        }
    }

    private fun populateBanner(
        sub: SubscriptionStatus?,
        state: SubscriptionStateMachineV2.SubscriptionState,
        realDeviceId: String = "",
        expiry: String = "",
        who: String = ""
    ) {
        if (!isAdded) return

        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val model = SubscriptionUiStateResolver.resolve(state, sub)

        val colorGood = UIUtils.fetchColor(requireContext(), R.attr.chipTextPositive)
        val colorBad = UIUtils.fetchColor(requireContext(), R.attr.accentBad)
        val colorDim = UIUtils.fetchColor(requireContext(), R.attr.primaryLightColorText)

        // Same guard as Manage Purchase: a CANCELLED DB row must not render as "Active"
        // unless Play still reports an auto-renewing purchase for this token.
        val dbRowCancelled = sub?.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
        val playConfirmsRenewal =
            runCatching { RpnProxyManager.getSubscriptionData()?.purchaseDetail?.isAutoRenewing }
                .getOrNull() == true
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
        ui.tvStatusText.text = statusText
        ui.tvStatusText.setTextColor(statusColor)

        // who
        ui.tvHeroWho.isVisible = who.isNotEmpty()
        ui.tvHeroWho.text = who

        when (model) {
            is PurchaseUiModel.Loading -> {
                // suppress hero paint until the state resolves; chip already shows "Syncing…"
                return
            }

            is PurchaseUiModel.NoPurchase -> renderNoPurchaseHero()
            is PurchaseUiModel.Lapsed -> renderLapsedHero(model, realDeviceId, fmt, expiry)
            is PurchaseUiModel.Valid -> renderValidHero(model, realDeviceId, fmt, expiry)
        }

        // No-purchase guidance: CTA routes to the purchase screen; purchase surfaces are
        // hidden entirely on fdroid (no billing backend).
        val fdroid = Utilities.isFdroidFlavour()
        ui.cardGetPlus.isVisible = model is PurchaseUiModel.NoPurchase && !fdroid
        ui.cardManagePurchaseDashboard.isVisible = !fdroid
        ui.tvPlanDetailsHeader.isVisible = !fdroid

        if (!Utilities.isFdroidFlavour()) {
            updateAckFailureBanner(InAppBillingHandler.ackFailureFlow.value)
        }
    }

    private fun renderNoPurchaseHero() {
        // Identical to RethinkPlusManagePurchaseFragment's NoPurchase hero: keep all
        // three lines visible, showing N/A placeholders instead of hiding the rows.
        ui.tvHeroPlanName.text = getString(R.string.rpn_no_active_plan_title)
        ui.tvHeroPurchasedDate.isVisible = true
        ui.tvHeroPurchasedDate.text = getString(R.string.lbl_not_available_short)
        ui.tvHeroIds.isVisible = true
        ui.tvHeroIds.text = getString(R.string.lbl_not_available_short)
    }

    private fun renderLapsedHero(model: PurchaseUiModel.Lapsed, realDeviceId: String, fmt: SimpleDateFormat, expiry: String) {
        val subscriptionData = RpnProxyManager.getSubscriptionData()
        val plan = resolvePlanName(subscriptionData).ifBlank {
            resolvePlanName(
                model.sub?.productId.orEmpty(),
                model.sub?.planId.orEmpty(),
                model.sub?.productTitle.orEmpty()
            )
        }
        ui.tvHeroPlanName.text = plan.ifBlank { getString(R.string.lbl_not_available_short) }

        ui.tvHeroPurchasedDate.isVisible = true
        ui.tvHeroPurchasedDate.text = if (model.sub != null && model.sub.purchaseTime > 0) {
            getString(R.string.rpn_overhauled_purchased_date_label, fmt.format(Date(model.sub.purchaseTime)))
        } else {
            getString(R.string.lbl_not_available_short)
        }

        renderHeroIds(model.sub?.purchaseToken.orEmpty(), model.sub?.accountId.orEmpty(), realDeviceId, expiry)
    }

    private fun renderValidHero(
        model: PurchaseUiModel.Valid,
        realDeviceId: String,
        fmt: SimpleDateFormat,
        expiry: String
    ) {
        val subscriptionData = RpnProxyManager.getSubscriptionData()
        ui.tvHeroPlanName.text = resolvePlanName(subscriptionData).ifBlank {
            resolvePlanName(
                model.sub?.productId.orEmpty(),
                model.sub?.planId.orEmpty(),
                model.sub?.productTitle.orEmpty()
            )
        }.capitalizeWords()

        ui.tvHeroPurchasedDate.isVisible = true
        ui.tvHeroPurchasedDate.text = if (model.sub != null && model.sub.purchaseTime > 0) {
            getString(R.string.rpn_overhauled_purchased_date_label, fmt.format(Date(model.sub.purchaseTime)))
        } else {
            getString(R.string.placeholder_dash)
        }

        renderHeroIds(model.sub?.purchaseToken.orEmpty(), model.sub?.accountId.orEmpty(), realDeviceId, expiry)
    }

    /**
     * Renders the hero's last line: purchase token (first 12 chars) · accountId
     * (first 12 chars) • deviceId (first 4 chars).
     */
    private fun renderHeroIds(token: String, accountId: String, deviceId: String, expiry: String) {
        val line = heroIdentityLine(token, accountId, deviceId, expiry)
        ui.tvHeroIds.isVisible = line.isNotEmpty()
        ui.tvHeroIds.text = line
    }

    private fun heroIdentityLine(token: String, accountId: String, deviceId: String, expiry: String): String {
        val t = token.take(12)
        val a = accountId.take(12)
        val d = deviceId.take(4)
        val idPart = listOf(a, d).filter { it.isNotBlank() }.joinToString(" · ")
        return listOf(t, idPart, expiry).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun setupClickListeners() {
        ui.cardRunTest.setOnClickListener {
            startActivity(Intent(requireContext(), PingTestActivity::class.java))
        }
        ui.rowManagePurchase.setOnClickListener { showManagePurchase() }
        ui.cardGetPlus.setOnClickListener { showPurchaseScreen() }
        ui.rowReportIssue.setOnClickListener { CustomerSupportActivity.start(requireContext()) }
        ui.rowRestoreDefaults.setOnClickListener { onRestoreDefaultsClicked() }
        ui.tvHeroWho.setOnClickListener { copyWhoToClipboard() }
    }

    /** Copies the hero "who" line to the clipboard. */
    private fun copyWhoToClipboard() {
        val text = ui.tvHeroWho.text?.toString().orEmpty()
        if (text.isBlank()) return
        val clipboard =
            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("who", text))
        showToastUiCentered(requireContext(), getString(R.string.copied_clipboard), Toast.LENGTH_SHORT)
    }

    /**
     * Restore Defaults entry point on the dashboard.
     * The progress dialog and result handling are driven by [observeResetState].
     */
    private fun onRestoreDefaultsClicked() {
        if (!isAdded) return
        if (!VpnController.hasTunnel()) {
            Logger.w(LOG_TAG_UI, "$TAG.onRestoreDefaultsClicked: no VPN tunnel, showing hint")
            showToastUiCentered(requireContext(), getString(R.string.ssv_toast_start_rethink), Toast.LENGTH_SHORT)
            return
        }
        WgAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rpn_restore_confirm_title))
            .setMessage(getString(R.string.rpn_restore_confirm_message))
            .setPositiveButton(getString(R.string.brbs_restore_dialog_positive)) { dialog, _ ->
                dialog.dismiss()
                serverSelectionViewModel.reset()
            }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .show()
    }

    /**
     * Observes [ServerSelectionViewModel.resetState] to drive the reset progress
     * dialog and surface the outcome. Result data (servers/selected lists) is ignored here;
     * the dashboard only needs to re-render the subscription banner.
     */
    private fun observeResetState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverSelectionViewModel.resetState.collect { state ->
                    when (state) {
                        is ServerSelectionViewModel.ResetState.InProgress -> {
                            if (rpnResetDialog?.isShowing != true) showRpnResetDialog()
                        }
                        is ServerSelectionViewModel.ResetState.Done -> {
                            serverSelectionViewModel.onResetConsumed()
                            dismissRpnResetDialog()
                            when (state.result) {
                                is RpnProxyManager.ResetResult.Success -> {
                                    Logger.i(LOG_TAG_UI, "$TAG.observeResetState: reset success")
                                    // Reset worked; recover the signature if a
                                    // previous failure flipped it to a sad dolphin.
                                    ui.dolphinSignature.setContent(EmbeddedDolphinContent.random())
                                    showToastUiCentered(
                                        requireContext(),
                                        getString(R.string.rpn_restore_success),
                                        Toast.LENGTH_SHORT
                                    )
                                }
                                is RpnProxyManager.ResetResult.Failure -> {
                                    Logger.w(LOG_TAG_UI, "$TAG.observeResetState: reset failed: ${state.result.reason}")
                                    ui.dolphinSignature.setContentForFailure()
                                    showToastUiCentered(
                                        requireContext(),
                                        getString(R.string.rpn_restore_failure, state.result.reason),
                                        Toast.LENGTH_LONG
                                    )
                                }
                            }
                            // Re-render the hero/chip; reset may have changed entitlement state.
                            loadSubscriptionBanner()
                        }
                        is ServerSelectionViewModel.ResetState.NoTunnel -> {
                            serverSelectionViewModel.onResetConsumed()
                            dismissRpnResetDialog()
                            ui.dolphinSignature.setContentForFailure()
                            showToastUiCentered(
                                requireContext(),
                                getString(R.string.ssv_toast_start_rethink),
                                Toast.LENGTH_SHORT
                            )
                        }
                        is ServerSelectionViewModel.ResetState.Idle -> { /* no-op */ }
                    }
                }
            }
        }
    }

    /**
     * Progress dialog shown while the RPN reset is running. Reuses the
     * dialog_server_loading layout (spinner + cycling status + timeout bar),
     * matching ServerSelectionFragment.showRpnResetDialog.
     */
    private fun showRpnResetDialog() {
        if (!isAdded) return
        if (rpnResetDialog?.isShowing == true) return
        dismissRpnResetDialog()

        val timeoutMs = ServerSelectionViewModel.RESET_TIMEOUT_MS
        val progress = RpnProgressState(requireContext())
        val tvStatus = progress.status
        val tvHint = progress.hint
        val timeoutBar = progress.timeout

        tvHint.text = getString(R.string.rpn_restore_dialog_hint)
        timeoutBar.max = timeoutMs.toInt()
        timeoutBar.setProgressCompat(0, false)

        val dialog = rpnProgressDialog(requireActivity(), progress)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        rpnResetDialog = dialog

        val statusMessages = listOf(
            getString(R.string.rpn_restore_dialog_status_unregistering),
            getString(R.string.rpn_restore_dialog_status_fetching),
            getString(R.string.rpn_restore_dialog_status_registering),
            getString(R.string.rpn_restore_dialog_status_refreshing),
        )

        rpnResetJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            var msgIdx = 0
            while (serverSelectionViewModel.resetState.value is ServerSelectionViewModel.ResetState.InProgress) {
                val elapsed = System.currentTimeMillis() - startTime
                val statusMsg = when {
                    elapsed > timeoutMs * 0.75 ->
                        getString(R.string.server_loading_dialog_status_timeout)
                    else -> statusMessages[msgIdx % statusMessages.size]
                }
                if (isAdded) {
                    tvStatus.text = statusMsg
                    timeoutBar.setProgressCompat(elapsed.coerceAtMost(timeoutMs).toInt(), true)
                }
                delay(RESET_STATUS_POLL_INTERVAL_MS.milliseconds)
                msgIdx++
            }
        }
    }

    /** Cancels the reset status job and safely dismisses the reset progress dialog. */
    private fun dismissRpnResetDialog() {
        rpnResetJob?.cancel()
        rpnResetJob = null
        runCatching {
            if (rpnResetDialog?.isShowing == true) rpnResetDialog?.dismiss()
        }
        rpnResetDialog = null
    }

    private fun showPurchaseScreen() {
        startActivity(
            FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusFragment::class.java
            )
        )
    }

    private fun showManagePurchase() {
        startActivity(
            FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusManagePurchaseFragment::class.java,
                args = Bundle()
            )
        )
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
                val who = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()
                uiCtx {
                    populateBanner(sub, state, deviceId, who = who)
                }
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
                is ServerApiError.NetworkError -> {
                    ui.dolphinSignature.setContentForFailure()
                    showToastUiCentered(
                        requireContext(),
                        error.message ?: getString(R.string.subscription_action_failed),
                        Toast.LENGTH_LONG
                    )
                }
                is ServerApiError.None -> { /* no-op */ }
            }
        }

    }

    private fun showDeviceNotRegisteredBottomSheet(error: ServerApiError.DeviceNotRegistered) {
        if (!isAdded || isStateSaved) {
            deferServerError(error)
            return
        }
        if (childFragmentManager.findFragmentByTag("deviceNotRegistered") != null) return
        DeviceNotRegisteredNotifier.cancel(requireContext())
        DeviceNotRegisteredBottomSheet.newInstance(error).show(childFragmentManager, "deviceNotRegistered")
    }

    private fun showDeviceAuthErrorBottomSheet(error: ServerApiError.Unauthorized401) {
        if (!isAdded || isStateSaved) {
            deferServerError(error)
            return
        }
        if (childFragmentManager.findFragmentByTag("deviceAuthError401") != null) return
        DeviceAuthErrorBottomSheet.newInstance(error).show(childFragmentManager, "deviceAuthError401")
    }

    private fun showConflictBottomSheet(error: ServerApiError.Conflict409) {
        if (!isAdded || isStateSaved) {
            deferServerError(error)
            return
        }
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

    /**
     * Keeps the error available for the next observer and posts a system
     * notification when the sheet cannot be shown.
     */
    private fun deferServerError(error: ServerApiError) {
        InAppBillingHandler.serverApiErrorLiveData.value = error
        val ctx = context?.applicationContext ?: return
        when (error) {
            is ServerApiError.Unauthorized401 ->
                DeviceAuthErrorNotifier.notify(ctx, error, persistentState.theme)
            is ServerApiError.DeviceNotRegistered ->
                DeviceNotRegisteredNotifier.notify(ctx, error, persistentState.theme)
            is ServerApiError.Conflict409 ->
                PurchaseConflictNotifier.notify(ctx, error, persistentState.theme)
            else -> Unit
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

    private suspend fun uiCtx(f: suspend () -> Unit) =
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }
}
