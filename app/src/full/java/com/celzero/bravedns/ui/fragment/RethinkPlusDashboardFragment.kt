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

import Logger
import Logger.LOG_TAG_UI
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.doOnAttach
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusDao
import com.celzero.bravedns.databinding.ActivityRethinkPlusDashboardBinding
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseConflictNotifier
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.ui.activity.CustomerSupportActivity
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.PingTestActivity
import com.celzero.bravedns.ui.activity.ServerOrderHistoryActivity
import com.celzero.bravedns.ui.bottomsheet.DeviceAuthErrorBottomSheet
import com.celzero.bravedns.ui.bottomsheet.DeviceNotRegisteredBottomSheet
import com.celzero.bravedns.ui.bottomsheet.ManageRpnPurchaseBtmSht
import com.celzero.bravedns.ui.bottomsheet.PurchaseConflictBottomSheet
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RethinkPlusDashboardFragment : Fragment(R.layout.activity_rethink_plus_dashboard) {
    private val b by viewBinding(ActivityRethinkPlusDashboardBinding::bind)

    private val subscriptionStatusDao by inject<SubscriptionStatusDao>()

    companion object {
        private const val TAG = "RPNDashFrag"
        /** Show "expiring soon" banner when fewer than this many days remain for an INAPP purchase. */
        private const val EXPIRING_SOON_THRESHOLD_DAYS = 30L
        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    }

    private fun safeNavigate(actionId: Int) {
        try {
            findNavController().navigate(actionId)
        } catch (_: IllegalStateException) {
            Logger.w(LOG_TAG_UI, "$TAG safeNavigate: no NavController (action=$actionId)")
            requireActivity().finish()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!isAdded) return
        initView()
        setupClickListeners()
        setupServerErrorObserver()
        observeSubscriptionState()
        applyScrollPadding()
    }

    private fun initView() {
        setupToolbar()
        loadSubscriptionBanner()
    }

    private fun applyScrollPadding() {
        b.nestedScroll.doOnAttach { view ->
            view.doOnNextLayout {
                view.updatePadding(top = 0)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh banner on resume so changes from ManageSubscription are reflected
        if (isAdded) loadSubscriptionBanner()
        // Show any pending Play Billing in-app messages (payment declined, grace-period, etc.).
        // enableInAppMessaging is a no-op when the billing client is not ready.
        InAppBillingHandler.enableInAppMessaging(requireActivity())
    }

    private fun setupToolbar() {
        b.collapsingToolbar.title = getString(R.string.rpn_title)
    }

    /**
     * Load the current subscription from DB and populate the collapsing header
     * and the details card below.  Runs on IO; posts to Main.
     */
    private fun loadSubscriptionBanner() {
        io {
            val sub = runCatching { subscriptionStatusDao.getCurrentSubscription() }.getOrNull()
            val state = RpnProxyManager.getSubscriptionState()
            // SubscriptionStatus.deviceId which holds only the sentinel "pip/identity.json".
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
        // Purchase token (show first 12 chars)
        var token = sub?.purchaseToken.orEmpty()
        token = token.length.let { if (it > 12) token.take(12) else token.ifBlank { "" } }

        // Hero subtitle: "RPN Standard · 74b4c00217"
        val accountId = sub?.accountId?.take(12).orEmpty()
        // Use the real device ID fetched from SecureIdentityStore (never sub.deviceId directly).
        val deviceId = realDeviceId.take(4)
        val id = "$accountId • $deviceId"
        b.tvHeroSubtitle.text = when {
            token.isNotEmpty() && accountId.isNotEmpty() ->
                getString(R.string.hero_plan_and_account, token, id)
            token.isNotEmpty() -> token
            accountId.isNotEmpty() -> id
            else -> getString(R.string.rpn_title)
        }

        val subscriptionData  = RpnProxyManager.getSubscriptionData()
        val displayPlan = resolvePlanName(subscriptionData)
        b.tvDetailPlan.text = displayPlan

        b.tvDetailActivated.text = if (sub != null && sub.purchaseTime > 0)
            fmt.format(Date(sub.purchaseTime))
        else getString(R.string.placeholder_dash)

        val isInApp = sub != null && isInAppProduct(sub.productId, sub.planId)
        val isRevoked = state is SubscriptionStateMachineV2.SubscriptionState.Revoked
        val hasKnownExpiry = !isRevoked &&
                sub != null && sub.billingExpiry > 0 &&
                sub.billingExpiry != Long.MAX_VALUE &&
                (isInApp ||
                 state is SubscriptionStateMachineV2.SubscriptionState.Expired ||
                 state is SubscriptionStateMachineV2.SubscriptionState.Cancelled)

        b.dividerExpiry.isVisible = hasKnownExpiry
        b.rowDetailExpiry.isVisible = hasKnownExpiry
        if (hasKnownExpiry) {
            b.tvDetailExpiry.text = fmt.format(Date(sub.billingExpiry))
        }

        // Renew CTA
        b.renewButton.isVisible = !state.hasValidSubscription

        // Expiring-soon banner - only for active INAPP purchases within 30 days of expiry
        updateExpiringBanner(subscriptionData, state)
    }

    /**
     * Shows a renewal banner when an INAPP purchase is expiring within 30 days.
     *
     * The banner is shown only for one-time (INAPP) purchases, subscriptions auto-renew
     * so they never need a manual renewal prompt. The threshold is 30 days to give users
     * enough time to repurchase before losing access.
     */
    private fun updateExpiringBanner(
        subscriptionData: SubscriptionStateMachineV2.SubscriptionData?,
        state: SubscriptionStateMachineV2.SubscriptionState
    ) {
        try {
            val sub = subscriptionData?.subscriptionStatus ?: return
            val isInApp = isInAppProduct(sub.productId, sub.planId)

            // Only show for active INAPP purchases
            if (!isInApp || !state.hasValidSubscription) {
                b.expiringBannerCard.isVisible = false
                return
            }

            val billingExpiry = sub.billingExpiry
            if (billingExpiry > 0L && billingExpiry != Long.MAX_VALUE) {
                val days = (billingExpiry - System.currentTimeMillis()) / ONE_DAY_MS
                if (days in 0..EXPIRING_SOON_THRESHOLD_DAYS) {
                    b.expiringBannerCard.isVisible = true
                    b.tvExpiringBanner.text = getString(R.string.inapp_expiry_soon, days.coerceAtLeast(0L))
                    b.btnExtendAccess.setOnClickListener { navigateToOneTimePurchase() }
                }
            }

            io {
                val remainingDays = InAppBillingHandler.getRemainingDaysForInAppSuspend()
                uiCtx {
                    if (remainingDays == null) {
                        Logger.w(LOG_TAG_UI, "$TAG could not fetch remaining days for INAPP expiry banner")
                        return@uiCtx
                    }
                    val isExpiringSoon = remainingDays in 0..EXPIRING_SOON_THRESHOLD_DAYS
                    b.expiringBannerCard.isVisible = isExpiringSoon || DEBUG
                    if (isExpiringSoon || DEBUG) {
                        val days = remainingDays.coerceAtLeast(0L)
                        b.tvExpiringBanner.text = getString(R.string.inapp_expiry_soon, days)
                        b.btnExtendAccess.setOnClickListener { navigateToOneTimePurchase() }
                        Logger.i(LOG_TAG_UI, "$TAG expiring banner shown: remainingDays=$remainingDays")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG updateExpiringBanner error (non-fatal): ${e.message}")
        }
    }

    /**
     * Navigates to [RethinkPlusFragment] in **extend mode**: ONE_TIME tab is pre-selected and the
     * "already subscribed" guard is bypassed so the user can purchase an additional one-time plan
     * while their current one-time access is still active but expiring soon.
     */
    private fun navigateToOneTimePurchase() {
        try {
            val intent = FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusFragment::class.java,
                args = Bundle().apply {
                    putString("ARG_KEY", "Launch_Rethink_Plus_Extend")
                    putBoolean("arg_extend_mode", true)
                }
            )
            startActivity(intent)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG error navigating to one-time purchase: ${e.message}", e)
            showToastUiCentered(requireContext(), getString(R.string.error_loading_manage_subscription), Toast.LENGTH_SHORT)
        }
    }

    /** Maps a raw product title/id to a friendly display name. */
    private fun resolvePlanName(subscriptionData: SubscriptionStateMachineV2.SubscriptionData?): String {
        if (subscriptionData == null) return ""

        val productId = subscriptionData.purchaseDetail?.productId.orEmpty()
        val planId = subscriptionData.purchaseDetail?.planId.orEmpty()
        Logger.vv("TEST", "resolvePlanName: productId=$productId, planId=$planId")
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
            else -> subscriptionData.purchaseDetail?.productTitle?.ifEmpty { productId } ?: productId
        }
    }

    /** Returns true if the given productId/planId belongs to a one-time INAPP purchase. */
    private fun isInAppProduct(productId: String, planId: String): Boolean {
        val inAppIds = setOf(
            InAppBillingHandler.ONE_TIME_PRODUCT_ID,
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS,
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS,
            InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID
        )
        return productId in inAppIds || planId in inAppIds
    }

    private fun observeSubscriptionState() {
        io {
            RpnProxyManager.collectSubscriptionState().collect { state ->
                val sub = runCatching { subscriptionStatusDao.getCurrentSubscription() }.getOrNull()
                val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
                uiCtx {
                    populateBanner(sub, state, deviceId)
                    handleStateChange(state)
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
        if (childFragmentManager.findFragmentByTag("conflict409") != null) {
            Logger.d(LOG_TAG_UI, "$TAG: conflict409 sheet already visible, skipping duplicate")
            return
        }
        PurchaseConflictNotifier.cancel(requireContext())
        val sheet = PurchaseConflictBottomSheet.newInstance(error)
        sheet.onRefundResult = { success, _ ->
            if (success) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { initView() }
            }
        }
        sheet.show(childFragmentManager, "conflict409")
    }


    private fun handleStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Active,
            is SubscriptionStateMachineV2.SubscriptionState.Grace -> {
                b.renewButton.isVisible = false
            }
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                b.renewButton.isVisible = true
            }
            is SubscriptionStateMachineV2.SubscriptionState.Revoked -> {
                b.renewButton.isVisible = true
            }
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                b.renewButton.isVisible = true
            }
            is SubscriptionStateMachineV2.SubscriptionState.Uninitialized,
            is SubscriptionStateMachineV2.SubscriptionState.Initial -> {
                // transient, ignore
            }
            else -> Logger.d(LOG_TAG_UI, "$TAG state: ${state.javaClass.simpleName}")
        }
    }

    private fun setupClickListeners() {
        b.pingTestRl.setOnClickListener {
            startActivity(Intent(requireContext(), PingTestActivity::class.java))
        }
        b.manageSubsRl.setOnClickListener { managePlayStoreSubs() }
        b.serverOrderHistoryRl.setOnClickListener { openServerOrderHistory() }
        b.reportIssueRl.setOnClickListener { CustomerSupportActivity.start(requireContext()) }
        b.renewButton.setOnClickListener {
            safeNavigate(R.id.action_rethinkPlusDashboard_to_rethinkPlus)
        }
    }

    private fun managePlayStoreSubs() {
        if (!isAdded || isStateSaved) return
        if (childFragmentManager.findFragmentByTag("manageRpnPurchase") != null) return
        ManageRpnPurchaseBtmSht.newInstance().show(childFragmentManager, "manageRpnPurchase")
    }

    private fun openServerOrderHistory() {
        try {
            startActivity(Intent(requireContext(), ServerOrderHistoryActivity::class.java))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG openServerOrderHistory error: ${e.message}", e)
            showToastUiCentered(requireContext(), getString(R.string.server_order_open_error), Toast.LENGTH_SHORT)
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) = withContext(Dispatchers.Main) { f() }
    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }
}
