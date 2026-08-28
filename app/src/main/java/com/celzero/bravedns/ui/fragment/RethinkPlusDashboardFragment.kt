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

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusDao
import com.celzero.bravedns.databinding.FragmentRethinkPlusDashboardBinding
import com.celzero.bravedns.iab.AckFailureInfo
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseConflictNotifier
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver.PurchaseUiModel
import com.celzero.bravedns.ui.activity.CustomerSupportActivity
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.PingTestActivity
import com.celzero.bravedns.ui.bottomsheet.DeviceAuthErrorBottomSheet
import com.celzero.bravedns.ui.bottomsheet.DeviceNotRegisteredBottomSheet
import com.celzero.bravedns.ui.bottomsheet.PurchaseConflictBottomSheet
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RethinkPlusDashboardFragment : Fragment(R.layout.fragment_rethink_plus_dashboard) {
    private val b by viewBinding(FragmentRethinkPlusDashboardBinding::bind)

    private val subscriptionStatusDao by inject<SubscriptionStatusDao>()

    companion object {
        private const val TAG = "RPNDashFrag"
        private const val ARG_SHOW_MANAGE_PURCHASE = "arg_show_manage_purchase"

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
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) loadSubscriptionBanner()
        InAppBillingHandler.enableInAppMessaging(requireActivity())
    }

    private fun loadSubscriptionBanner() {
        io {
            val sub = runCatching { subscriptionStatusDao.getCurrentSubscription() }.getOrNull()
            val state = RpnProxyManager.getSubscriptionState()
            val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
            uiCtx { populateBanner(sub, state, deviceId) }
        }
    }

    private fun populateBanner(
        sub: SubscriptionStatus?,
        state: SubscriptionStateMachineV2.SubscriptionState,
        realDeviceId: String = ""
    ) {
        if (!isAdded) return

        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val model = SubscriptionUiStateResolver.resolve(state, sub)

        val colorGood = UIUtils.fetchColor(requireContext(), R.attr.accentGood)
        val colorBad = UIUtils.fetchColor(requireContext(), R.attr.accentBad)
        val colorDim = UIUtils.fetchColor(requireContext(), R.attr.primaryLightColorText)
        val (statusText, statusColor) = when (model) {
            is PurchaseUiModel.Loading -> getString(R.string.rpn_status_syncing) to colorDim
            is PurchaseUiModel.NoPurchase -> getString(R.string.rpn_status_no_plan) to colorDim
            else -> when (state) {
                is SubscriptionStateMachineV2.SubscriptionState.Active -> getString(R.string.lbl_active) to colorGood
                is SubscriptionStateMachineV2.SubscriptionState.Grace -> getString(R.string.lbl_grace_period) to colorGood
                is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> getString(R.string.lbl_cancelled) to colorBad
                is SubscriptionStateMachineV2.SubscriptionState.Expired -> getString(R.string.lbl_expired) to colorBad
                is SubscriptionStateMachineV2.SubscriptionState.Revoked -> getString(R.string.status_revoked) to colorBad
                is SubscriptionStateMachineV2.SubscriptionState.Paused -> getString(R.string.lbl_paused) to colorDim
                is SubscriptionStateMachineV2.SubscriptionState.OnHold -> getString(R.string.lbl_paused) to colorDim
                else -> getString(R.string.placeholder_dash) to colorDim
            }
        }
        b.tvStatusText.text = statusText
        b.tvStatusText.setTextColor(statusColor)

        when (model) {
            is PurchaseUiModel.Loading -> {
                // suppress hero paint until the state resolves; chip already shows "Syncing…"
                return
            }

            is PurchaseUiModel.NoPurchase -> renderNoPurchaseHero()
            is PurchaseUiModel.Lapsed -> renderLapsedHero(model, fmt)
            is PurchaseUiModel.Valid -> renderValidHero(model, realDeviceId, fmt)
        }

        // No-purchase guidance: CTA routes to the purchase screen; purchase surfaces are
        // hidden entirely on fdroid (no billing backend).
        val fdroid = Utilities.isFdroidFlavour()
        b.cardGetPlus.isVisible = model is PurchaseUiModel.NoPurchase && !fdroid
        b.cardManagePurchaseDashboard.isVisible = !fdroid
        b.tvPlanDetailsHeader.isVisible = !fdroid

        if (!Utilities.isFdroidFlavour()) {
            updateAckFailureBanner(InAppBillingHandler.ackFailureFlow.value)
        }
    }

    private fun renderNoPurchaseHero() {
        b.tvHeroPlanName.text = getString(R.string.rpn_no_active_plan_title)
        b.tvHeroPurchasedDate.isVisible = false
        b.heroMetaDot.isVisible = false
        b.tvHeroServerSlots.isVisible = false
        b.tvHeroIds.isVisible = false
    }

    private fun renderLapsedHero(model: PurchaseUiModel.Lapsed, fmt: SimpleDateFormat) {
        val subscriptionData = RpnProxyManager.getSubscriptionData()
        val plan = resolvePlanName(subscriptionData).ifBlank {
            resolvePlanName(model.sub?.productId.orEmpty(), model.sub?.planId.orEmpty())
        }
        b.tvHeroPlanName.text = plan.ifBlank { getString(R.string.lbl_not_available_short) }

        b.tvHeroPurchasedDate.isVisible = true
        b.tvHeroPurchasedDate.text = if (model.sub != null && model.sub.purchaseTime > 0) {
            getString(R.string.rpn_overhauled_purchased_date_label, fmt.format(Date(model.sub.purchaseTime)))
        } else {
            getString(R.string.lbl_not_available_short)
        }
        b.heroMetaDot.isVisible = true
        b.tvHeroServerSlots.isVisible = false

        val accountId = model.sub?.accountId?.take(12).orEmpty()
        b.tvHeroIds.isVisible = accountId.isNotEmpty()
        b.tvHeroIds.text = if (accountId.isNotEmpty()) "ID $accountId" else ""
    }

    private fun renderValidHero(
        model: PurchaseUiModel.Valid,
        realDeviceId: String,
        fmt: SimpleDateFormat
    ) {
        val accountId = model.sub?.accountId?.take(12).orEmpty()
        val deviceId = realDeviceId.take(4)
        b.tvHeroIds.isVisible = accountId.isNotEmpty()
        b.tvHeroIds.text = if (accountId.isNotEmpty()) "ID $accountId · $deviceId" else ""

        val subscriptionData = RpnProxyManager.getSubscriptionData()
        b.tvHeroPlanName.text = resolvePlanName(subscriptionData).ifBlank {
            resolvePlanName(model.sub?.productId.orEmpty(), model.sub?.planId.orEmpty())
        }.capitalizeWords()

        b.tvHeroPurchasedDate.isVisible = true
        b.tvHeroPurchasedDate.text = if (model.sub != null && model.sub.purchaseTime > 0) {
            getString(R.string.rpn_overhauled_purchased_date_label, fmt.format(Date(model.sub.purchaseTime)))
        } else {
            getString(R.string.placeholder_dash)
        }
        b.heroMetaDot.isVisible = true
        b.tvHeroServerSlots.isVisible = true
        b.tvHeroServerSlots.text = getString(R.string.rpn_overhauled_server_slots_label, 5)
    }

    private fun setupClickListeners() {
        b.cardRunTest.setOnClickListener {
            startActivity(Intent(requireContext(), PingTestActivity::class.java))
        }
        b.cardManagePurchaseDashboard.setOnClickListener { showManagePurchase() }
        b.cardGetPlus.setOnClickListener { showPurchaseScreen() }
        b.cardReportIssue.setOnClickListener { CustomerSupportActivity.start(requireContext()) }
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
                val sub = runCatching { subscriptionStatusDao.getCurrentSubscription() }.getOrNull()
                val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
                uiCtx {
                    populateBanner(sub, state, deviceId)
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
                is ServerApiError.NetworkError -> showToastUiCentered(
                    requireContext(),
                    error.message ?: getString(R.string.subscription_action_failed),
                    Toast.LENGTH_LONG
                )
                is ServerApiError.None -> { /* no-op */ }
            }
        }

        InAppBillingHandler.accountMismatchLiveData.observe(viewLifecycleOwner) {
            if (!isAdded || !isResumed) return@observe
            InAppBillingHandler.accountMismatchLiveData.value = null
            Logger.w(LOG_TAG_UI, "$TAG account mismatch detected; showing warning")
            showToastUiCentered(requireContext(), getString(R.string.account_mismatch_msg), Toast.LENGTH_LONG)
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
