/*
 * Copyright 2024 RethinkDNS and its authors
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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.animation.AnticipateOvershootInterpolator
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.GooglePlaySubsAdapter
import com.celzero.bravedns.databinding.FragmentRethinkPlusPremiumBinding
import com.celzero.bravedns.iab.BillingListener
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.bottomsheet.PurchaseProcessingBottomSheet
import com.celzero.bravedns.ui.dialog.SubscriptionAnimDialog
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.RethinkPlusViewModel
import com.celzero.bravedns.viewmodel.SubscriptionUiState
import com.facebook.shimmer.Shimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus_premium),
    GooglePlaySubsAdapter.SubscriptionChangeListener,
    BillingListener {

    private val b by viewBinding(FragmentRethinkPlusPremiumBinding::bind)
    private val viewModel: RethinkPlusViewModel by viewModels()

    private var adapter: GooglePlaySubsAdapter? = null
    private var processingBottomSheet: PurchaseProcessingBottomSheet? = null

    // dismiss the processing sheet after 60 s if no callback arrives.
    private var processingTimeoutJob: kotlinx.coroutines.Job? = null
    // flag set when a timeout fires so we re-check billing on next resume.
    private var shouldRecheckOnResume: Boolean = false
    // guard against double-taps on the subscribe button while a purchase is in flight.
    private var purchaseInFlight: Boolean = false

    companion object {
        private const val TAG = "R+Ui"
        private const val PROCESSING_TIMEOUT_MS = 60_000L

        /**
         * When this argument is `true` the fragment opens in *extend mode*:
         * - The ONE_TIME tab is pre-selected.
         * - The "already subscribed" guard is bypassed so users with an active one-time
         *   purchase can buy an additional access window before their current one expires.
         */
        const val ARG_EXTEND_MODE = "arg_extend_mode"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Read extend mode argument before any other setup so the ViewModel and UI are
        // configured correctly from the start.
        val extendMode = arguments?.getBoolean(ARG_EXTEND_MODE, false) ?: false
        if (extendMode) {
            viewModel.extendMode = true
        }
        setupUI()
        setupObservers()
        setupClickListeners()
        initializeBilling()
    }

    override fun onResume() {
        super.onResume()
        if (b.loadingContainer.isVisible) startShimmer()
        if (shouldRecheckOnResume) {
            shouldRecheckOnResume = false
            viewModel.initializeBilling()
        }
        // Re-surface any unresolved acknowledgement/error condition
        // if the user navigated away and came back, the bottom sheet /
        // error container was torn down but the failure is still pending. Re-emit it so
        // the dialog is shown again and the user is not left unaware of the failure.
        val unresolved = viewModel.lastUnresolved.value
        if (unresolved != null && isAdded) {
            Logger.d(Logger.LOG_IAB, "$TAG: onResume re-surfacing unresolved state: ${unresolved::class.simpleName}")
            handleUiState(unresolved)
        }
        // Show any pending Play Billing in-app messages (e.g. payment recovery overlay).
        InAppBillingHandler.enableInAppMessaging(requireActivity())
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelProcessingTimeout()
        dismissProcessingBottomSheet()
        adapter = null
    }

    private fun setupUI() {
        b.fhsTitleRethink.text = getString(R.string.rpn_title).lowercase()
        applyButtonTheme()
        setupRecyclerView()
        setupTermsAndPolicy()
        setupProductTypeToggle()
        adjustCtaBottomMargin()

        if (viewModel.extendMode) {
            // In extend mode: hide the tab toggle and the page title,show only one-time products.
            b.productTypeToggle.isVisible = false
            // Show the extend-mode banner so the user knows they are adding more access time.
            b.extendModeBanner.isVisible = true
            // hide the connection info card since it's not relevant in extend mode
            b.connectionInfoCard.visibility = View.GONE
            }
        }

    private fun applyButtonTheme() {
        val ctx = requireContext()

        // subscribe button
        val accentGood = UIUtils.fetchColor(ctx, R.attr.accentGood)
        val lightText  = UIUtils.fetchColor(ctx, R.attr.primaryLightColorText)
        val htxtClr = UIUtils.fetchColor(ctx, R.attr.homeScreenHeaderTextColor)

        b.subscribeButton.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentGood)
            setTextColor(htxtClr)
            iconTint = android.content.res.ColorStateList.valueOf(htxtClr)
        }

        b.btnContactSupport.apply {
            setTextColor(lightText)
            iconTint = android.content.res.ColorStateList.valueOf(lightText)
        }

        b.retryButton.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentGood)
            setTextColor(htxtClr)
        }

        b.btnContactSupportError.apply {
            setTextColor(lightText)
            iconTint = android.content.res.ColorStateList.valueOf(lightText)
        }
    }

    private fun setupRecyclerView() {
        b.subscriptionPlans.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            setHasFixedSize(false)
        }
    }

    private fun setupTermsAndPolicy() {
        b.termsText.apply {
            text = updateHtmlEncodedText(getString(R.string.rethink_terms))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
    }

    private fun adjustCtaBottomMargin() {
        if (activity is FragmentHostActivity) {
            val lp = b.ctaInnerContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            lp?.bottomMargin = 0
            b.ctaInnerContainer.layoutParams = lp
        }
    }

    private fun syncScrollBottomPaddingWithCta() {
        if (!isAdded) return
        val ctaHeight = b.ctaContainer.height
        if (ctaHeight <= 0) return
        val scrollContent = b.scrollView.getChildAt(0) ?: return
        scrollContent.setPadding(
            scrollContent.paddingLeft,
            scrollContent.paddingTop,
            scrollContent.paddingRight,
            ctaHeight
        )
    }

    private fun setupProductTypeToggle() {
        updateToggleState(RethinkPlusViewModel.ProductTypeFilter.ONE_TIME)

        if (!viewModel.extendMode) {
            b.btnSubscription.setOnClickListener {
                animateButtonPress(b.btnSubscription)
                viewModel.selectProductType(RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION)
            }
        }

        b.btnOneTime.setOnClickListener {
            animateButtonPress(b.btnOneTime)
            viewModel.selectProductType(RethinkPlusViewModel.ProductTypeFilter.ONE_TIME)
        }
    }

    private fun updateToggleState(selectedType: RethinkPlusViewModel.ProductTypeFilter) {
        when (selectedType) {
            RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION -> {
                b.btnSubscription.apply {
                    setBackgroundColor(UIUtils.fetchColor(requireContext(), R.attr.primaryColor))
                    setTextColor(UIUtils.fetchColor(requireContext(), R.attr.accentGood))
                }
                b.btnOneTime.apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
                }
            }
            RethinkPlusViewModel.ProductTypeFilter.ONE_TIME -> {
                b.btnOneTime.apply {
                    setBackgroundColor(UIUtils.fetchColor(requireContext(), R.attr.primaryColor))
                    setTextColor(UIUtils.fetchColor(requireContext(), R.attr.accentGood))
                }
                b.btnSubscription.apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
                }
            }
        }
        updateSubscribeButtonText(selectedType, isResubscribeState)
    }

    private fun updateSubscribeButtonText(
        productType: RethinkPlusViewModel.ProductTypeFilter,
        isResubscribe: Boolean = false
    ) {
        b.subscribeButton.text = when (productType) {
            RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION ->
                if (isResubscribe) getString(R.string.resubscribe_title)
                else getString(R.string.subscribe_now)
            RethinkPlusViewModel.ProductTypeFilter.ONE_TIME ->
                getString(R.string.purchase_now)
        }
    }

    // whether the current state is a resubscription (canceled / expired).
    private var isResubscribeState: Boolean = false

    private fun setupClickListeners() {
        b.subscribeButton.setOnClickListener {
            if (purchaseInFlight) {
                Logger.d(Logger.LOG_IAB, "$TAG: purchase already in flight, ignoring tap")
                return@setOnClickListener
            }
            // if a previous acknowledgement/transaction failed, re-verify
            // Play + server status first instead of silently launching another purchase
            // attempt (which would give the user no feedback).
            val unresolved = viewModel.lastUnresolved.value
            if (unresolved is SubscriptionUiState.ServerAckPending ||
                unresolved is SubscriptionUiState.Error ||
                unresolved is SubscriptionUiState.AcknowledgementFailed
            ) {
                animateButtonPress(b.subscribeButton)
                Utilities.showToastUiCentered(
                    requireContext(),
                    getString(R.string.verifying_with_play_store),
                    Toast.LENGTH_SHORT
                )
                viewModel.reverifyAfterFailure()
                return@setOnClickListener
            }
            animateButtonPress(b.subscribeButton)
            purchaseSubscription()
        }

        b.retryButton.setOnClickListener {
            animateButtonPress(b.retryButton)
            viewModel.retry()
        }

        b.btnContactSupport.setOnClickListener {
            openHelpAndSupport()
        }

        b.btnContactSupportError.setOnClickListener {
            openHelpAndSupport()
        }

        b.btnRestorePurchases.setOnClickListener {
            restorePurchases()
        }
    }

    private fun openHelpAndSupport() {
        val args = Bundle().apply { putString("ARG_KEY", "Launch_Rethink_Support_Dashboard") }
        startActivity(
            FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = args
            )
        )
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> handleUiState(state) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedProductType.collect { productType ->
                    updateToggleState(productType)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedProduct.collect { selection ->
                    adapter?.setSelectedProduct(selection?.first, selection?.second)
                }
            }
        }

        // when retry() detects the billing client is not ready, the ViewModel cannot reconnect
        // itself (it has no live context). This event tells the Fragment to do it.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.retryConnectionEvent.collect {
                    Logger.d(Logger.LOG_IAB, "$TAG: retryConnectionEvent, re-initiating billing")
                    reconnectBilling()
                }
            }
        }

        setupIab()
    }

    private fun setupIab() {
        val owner = viewLifecycleOwner


        // observe billing errors (user cancel, network, etc.) to update the processing sheet
        // Single-shot: clear after consumption so config changes / re-subscription do not
        // replay a stale error (e.g. a previously dismissed user-cancel).
        InAppBillingHandler.transactionErrorLiveData.observe(owner) { billingResult ->
            if (billingResult == null) return@observe
            InAppBillingHandler.transactionErrorLiveData.value = null
            purchaseInFlight = false
            cancelProcessingTimeout()
            viewModel.onTransactionError()

            val response = com.celzero.bravedns.iab.BillingResponse(billingResult.responseCode)
            when {
                response.isUserCancelled -> {
                    Logger.d(Logger.LOG_IAB, "$TAG: User cancelled purchase")
                    dismissProcessingBottomSheet()
                }
                else -> {
                    // map each response code to a friendly message.
                    showProcessingBottomSheet(
                        PurchaseProcessingBottomSheet.ProcessingState.Error,
                        friendlyBillingMessage(response)
                    )
                }
            }
        }

        // when Play returns ITEM_ALREADY_OWNED the handler silently restores
        // the purchase; dismiss the "Processing" sheet and acknowledge the restore so the user
        // is never left staring at a spinner. The resulting Active state drives the success UI.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                InAppBillingHandler.itemAlreadyOwnedFlow.collect {
                    Logger.d(Logger.LOG_IAB, "$TAG: item already owned, restoring")
                    dismissProcessingBottomSheet()
                    showTransactionError(getString(R.string.item_already_owned_restoring))
                }
            }
        }

        // observe server-side API errors (401 unauthorized / 409 conflict) surfaced during
        // the purchase lifecycle (device registration, entitlement/ack queries). The purchase
        // screen is the active surface during a buy flow, so surface these in-app here instead
        // of only via a background notification. Without this, a 401/409 leaves the processing
        // sheet stuck until the 60s timeout fires with a misleading "processing timeout".
        InAppBillingHandler.serverApiErrorLiveData.observe(owner) { error ->
            if (error == null) return@observe
            InAppBillingHandler.serverApiErrorLiveData.value = null
            purchaseInFlight = false
            cancelProcessingTimeout()
            dismissProcessingBottomSheet()
            when (error) {
                is ServerApiError.Unauthorized401 -> {
                    showTransactionError(getString(R.string.subscription_action_failed))
                }

                is ServerApiError.Conflict409 -> {
                    showTransactionError(getString(R.string.subscription_action_failed))
                }

                else -> {
                    /* GenericError / NetworkError / DeviceNotRegistered handled elsewhere or via toast */
                }
            }
        }
        // Google Play services interrupted / fatal billing error. Surface a
        // user-friendly error (full-screen, retryable) instead of only a transient toast.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                InAppBillingHandler.playServicesInterruptedFlow.collect { code ->
                    if (code <= 0) return@collect
                    Logger.w(Logger.LOG_IAB, "$TAG: playServicesInterrupted code=$code")
                    viewModel.onPlayServicesError(code)
                }
            }
        }
    }

    private fun handleUiState(state: SubscriptionUiState) {
        Logger.d(Logger.LOG_IAB, "$TAG: Handling UI state: ${state::class.simpleName}")
        when (state) {
            is SubscriptionUiState.Loading -> showLoading()
            is SubscriptionUiState.Ready -> showReady(state.products, state.isResubscribe, state.availabilityData)
            is SubscriptionUiState.Processing -> showProcessing(state.message)
            is SubscriptionUiState.PendingPurchase -> showPendingPurchase(state.message)
            is SubscriptionUiState.PendingTimeout -> showPendingTimeout(state.message)
            is SubscriptionUiState.NoInternet -> showNoInternet(state.message)
            is SubscriptionUiState.Success -> showSuccess(state.productId, state.isExtend)
            is SubscriptionUiState.Error -> showError(state.title, state.message, state.isRetryable)
            is SubscriptionUiState.ServerAckPending -> showServerAckPending(state.message)
            is SubscriptionUiState.AcknowledgementFailed ->
                showAcknowledgementFailed(state.title, state.message, state.canRetry, state.refundInitiated)
            is SubscriptionUiState.AlreadySubscribed -> navigateToDashboard(state.productId)
            is SubscriptionUiState.Available -> showConnectionInfo(state)
        }
    }

    private fun showLoading() {
        purchaseInFlight = false
        hideAllContainers()
        b.loadingContainer.isVisible = true
        startShimmer()
    }

    private fun showReady(
        products: List<ProductDetail>,
        isResubscribe: Boolean,
        availabilityData: SubscriptionUiState.Available?
    ) {
        // A transition back to Ready (e.g., Expired/Canceled state restoring the
        // purchase screen) must clear purchaseInFlight so the buy button is not
        // silently blocked by a prior failed purchase attempt.
        purchaseInFlight = false
        hideAllContainers()
        stopShimmer()

        b.scrollView.isVisible = true
        b.ctaContainer.isVisible = true

        b.ctaContainer.post { syncScrollBottomPaddingWithCta() }

        Logger.i(LOG_TAG_UI, "$TAG: Ready: ${products.size} products, resubscribe=$isResubscribe")

        availabilityData?.let { showConnectionInfo(it) }

        isResubscribeState = isResubscribe
        updateSubscribeButtonText(viewModel.selectedProductType.value, isResubscribe)

        // reset subscribe button state in case it was locked by a previous PURCHASED state.
        b.subscribeButton.isEnabled = true

        if (adapter == null) {
            val selection = viewModel.selectedProduct.value
            adapter = GooglePlaySubsAdapter(
                this,
                requireContext(),
                products,
                selection?.first,
                selection?.second,
                false
            )
            b.subscriptionPlans.adapter = adapter
        } else {
            adapter?.setData(products)
        }

        animateContentEntrance()
    }

    private fun showConnectionInfo(state: SubscriptionUiState.Available) {
        if (state.ip.isEmpty() || viewModel.extendMode) {
            b.connectionInfoCard.isVisible = false
            return
        }

        b.connectionInfoCard.isVisible = true
        b.connectionIp.text = state.ip

        val locationParts = mutableListOf<String>()
        if (state.city.isNotEmpty()) locationParts.add(state.city)
        if (state.country.isNotEmpty()) locationParts.add(state.country.uppercase())
        var locationText = locationParts.joinToString(", ")
        if (state.colo.isNotEmpty()) {
            locationText = getString(R.string.connection_location_with_colo, locationText, state.colo)
        }
        b.connectionLocation.text = locationText

        if (state.asorg.isNotEmpty()) {
            b.ispContainer.isVisible = true
            b.connectionIsp.text = state.asorg
        } else {
            b.ispContainer.isVisible = false
        }
    }

    private fun showProcessing(message: String) {
        purchaseInFlight = true
        showProcessingBottomSheet(PurchaseProcessingBottomSheet.ProcessingState.Processing, message)
        startProcessingTimeout()
    }

    private fun showPendingPurchase(message: String) {
        purchaseInFlight = true
        val display = message.ifBlank { getString(R.string.verifying_with_play_store) }
        showProcessingBottomSheet(PurchaseProcessingBottomSheet.ProcessingState.PendingVerification, display)
        startProcessingTimeout()
    }

    /**
     * Pending-purchase polling timed out. Show a sheet with a
     * "Check Status" action that re-queries Google Play.
     */
    private fun showPendingTimeout(message: String) {
        purchaseInFlight = false
        cancelProcessingTimeout()
        val display = message.ifBlank { getString(R.string.pending_timeout_message) }
        showProcessingBottomSheet(PurchaseProcessingBottomSheet.ProcessingState.PendingTimeout, display)
        processingBottomSheet?.primaryActionListener = { viewModel.checkPendingStatus() }
    }

    private fun showNoInternet(message: String) {
        purchaseInFlight = false
        cancelProcessingTimeout()
        dismissProcessingBottomSheet()
        hideAllContainers()
        stopShimmer()
        b.errorContainer.isVisible = true
        b.errorIcon.setImageResource(R.drawable.ic_error_state)
        b.errorTitle.text = getString(R.string.no_internet_title)
        b.errorMessage.text = message.ifBlank { getString(R.string.no_internet_premium_msg) }
        b.retryButton.isVisible = true
    }

    private fun showServerAckPending(message: String) {
        purchaseInFlight = false
        cancelProcessingTimeout()
        val displayMessage = message.ifBlank { getString(R.string.server_ack_pending_message) }
        showProcessingBottomSheet(
            PurchaseProcessingBottomSheet.ProcessingState.ServerAckPending,
            displayMessage
        )
    }

    private fun showSuccess(productId: String, isExtend: Boolean = false) {
        purchaseInFlight = false
        cancelProcessingTimeout()

        val successMessage = if (isExtend) {
            getString(R.string.extend_purchase_activated)
        } else {
            getString(R.string.subscription_activated)
        }

        showProcessingBottomSheet(
            PurchaseProcessingBottomSheet.ProcessingState.Success,
            successMessage
        )

        try {
            val dialog = if (isExtend) {
                SubscriptionAnimDialog.newInstance(
                    title = getString(R.string.extend_purchase_activated),
                    message = getString(R.string.extend_purchase_success_message)
                )
            } else {
                SubscriptionAnimDialog.newInstance(
                    title = getString(R.string.subscription_congrats_title),
                    message = getString(R.string.subscription_congrats_desc)
                )
            }
            dialog.show(childFragmentManager, "SubscriptionAnimDialog")
        } catch (e: Exception) {
            Logger.w(Logger.LOG_IAB, "$TAG: err showing subscription anim: ${e.message}")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(1500.milliseconds)
            if (isExtend) {
                navigateBackToDashboard()
            } else {
                navigateToDashboard(productId)
            }
        }
    }

    private fun showError(title: String, message: String, isRetryable: Boolean) {
        purchaseInFlight = false
        cancelProcessingTimeout()
        dismissProcessingBottomSheet()
        hideAllContainers()
        stopShimmer()

        b.errorContainer.isVisible = true
        b.errorTitle.text = title
        b.errorMessage.text = message
        b.retryButton.isVisible = isRetryable
    }

    /**
     * Both acknowledgement systems (our server and Google Play) failed
     * Shows a full-screen error with the title/message and
     * a retry action. Appends a refund note when the purchase is being auto-refunded.
     */
    private fun showAcknowledgementFailed(
        title: String,
        message: String,
        canRetry: Boolean,
        refundInitiated: Boolean
    ) {
        purchaseInFlight = false
        cancelProcessingTimeout()
        dismissProcessingBottomSheet()
        hideAllContainers()
        stopShimmer()

        val fullMessage = if (refundInitiated) {
            "$message\n\n${getString(R.string.ack_refund_initiated)}"
        } else {
            message
        }

        b.errorContainer.isVisible = true
        b.errorTitle.text = title
        b.errorMessage.text = fullMessage
        b.retryButton.isVisible = canRetry
    }

    private fun showTransactionError(message: String) {
        if (!isAdded || context == null) return
        Utilities.showToastUiCentered(requireContext(), message, Toast.LENGTH_LONG)
    }

    private fun hideAllContainers() {
        b.loadingContainer.isVisible = false
        b.scrollView.isVisible = false
        b.ctaContainer.isVisible = false
        b.errorContainer.isVisible = false
    }

    private fun navigateToDashboard(productId: String) {
        if (!isAdded) return
        Logger.i(Logger.LOG_IAB, "$TAG: navigating to dashboard for: $productId")

        try {
            val navController = findNavController()
            navController.navigate(R.id.action_switch_to_rethinkPlusDashboardFragment)
        } catch (_: IllegalStateException) {
            Logger.w(Logger.LOG_IAB, "$TAG: no NavController found, finishing host activity")
            if (isAdded) requireActivity().finish()
        } catch (e: Exception) {
            Logger.e(Logger.LOG_IAB, "$TAG: navigation failed: ${e.message}", e)
            if (isAdded) requireActivity().finish()
        }
    }

    /**
     * Pop back to the caller ([RethinkPlusDashboardFragment]) after an extend-mode purchase.
     * The dashboard auto-refreshes on resume via its own state observation, so no extra
     * data-passing is needed.
     */
    private fun navigateBackToDashboard() {
        if (!isAdded) return
        Logger.i(Logger.LOG_IAB, "$TAG: extend-mode: popping back to dashboard")
        try {
            val popped = findNavController().popBackStack()
            if (!popped) {
                // Nothing to pop — finish the host activity so the user isn't stuck.
                requireActivity().finish()
            }
        } catch (_: IllegalStateException) {
            Logger.w(Logger.LOG_IAB, "$TAG: no NavController for pop, finishing host activity")
            if (isAdded) requireActivity().finish()
        } catch (e: Exception) {
            Logger.e(Logger.LOG_IAB, "$TAG: popBackStack failed: ${e.message}", e)
            if (isAdded) requireActivity().finish()
        }
    }

    private fun initializeBilling() {
        viewModel.initializeBilling()
        if (!InAppBillingHandler.isBillingClientSetup()) {
            InAppBillingHandler.initiate(requireContext().applicationContext, this)
        } else {
            InAppBillingHandler.registerListener(this)
            viewModel.onBillingConnected(true, "already connected")
        }
    }

    private fun reconnectBilling() {
        Logger.d(Logger.LOG_IAB, "$TAG: reconnectBilling, calling initiate()")
        InAppBillingHandler.initiate(requireContext().applicationContext, this)
    }

    /**
     * Initiate a purchase for the currently selected plan.
     */
    private fun purchaseSubscription() {
        val selection = viewModel.selectedProduct.value
        if (selection == null) {
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.select_plan_first),
                Toast.LENGTH_SHORT
            )
            return
        }

        // check connectivity before launching the purchase flow so the
        // user gets a clear "No internet" message instead of a spinner-then-generic-error.
        if (!isOnline()) {
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.no_internet_purchase_msg),
                Toast.LENGTH_LONG
            )
            return
        }

        val (productId, planId) = selection
        lifecycleScope.launch {
            when (viewModel.selectedProductType.value) {
                RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION ->
                    InAppBillingHandler.purchaseSubs(requireActivity(), productId, planId)
                RethinkPlusViewModel.ProductTypeFilter.ONE_TIME -> {
                    // In extend mode the state machine stays in Active (no PurchaseInitiated
                    // transition from Active), so purchaseFlowActive would never be set by the
                    // state change observer. Set it manually so the Active callback shows Success.
                    if (viewModel.extendMode) viewModel.markPurchaseFlowActive()
                    Logger.vv(Logger.LOG_IAB, "$TAG: Initiating one-time purchase for productId=$productId, planId=$planId, extendMode=${viewModel.extendMode}")
                    InAppBillingHandler.purchaseOneTime(requireActivity(), productId, planId, forceExtend = viewModel.extendMode)
                }
            }
        }
    }

    private fun showProcessingBottomSheet(
        state: PurchaseProcessingBottomSheet.ProcessingState,
        message: String?
    ) {
        // Reuse a sheet that the FragmentManager restored across a config change
        // (our field is nulled on onDestroyView) to avoid creating a second sheet
        // or losing the reference and re-enabling the buy button mid-flight.
        if (processingBottomSheet == null) {
            processingBottomSheet =
                childFragmentManager.findFragmentByTag("processing") as? PurchaseProcessingBottomSheet
        }
        if (processingBottomSheet == null || processingBottomSheet?.isAdded != true) {
            processingBottomSheet = PurchaseProcessingBottomSheet.newInstance(state, message)
            processingBottomSheet?.show(childFragmentManager, "processing")
        } else {
            processingBottomSheet?.updateState(state, message)
        }
    }

    private fun dismissProcessingBottomSheet() {
        cancelProcessingTimeout()
        try {
            processingBottomSheet?.dismissAllowingStateLoss()
        } catch (e: Exception) {
            Logger.w(Logger.LOG_IAB, "$TAG: err dismissing btmsht: ${e.message}")
        } finally {
            processingBottomSheet = null
        }
    }

    private fun startProcessingTimeout() {
        cancelProcessingTimeout()
        processingTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(PROCESSING_TIMEOUT_MS.milliseconds)
            if (isAdded && processingBottomSheet?.isAdded == true) {
                Logger.w(Logger.LOG_IAB, "$TAG: processing timeout, dismissing sheet")
                purchaseInFlight = false
                dismissProcessingBottomSheet()
                shouldRecheckOnResume = true
                showTransactionError(getString(R.string.subscription_processing_timeout))
            }
        }
    }

    private fun cancelProcessingTimeout() {
        processingTimeoutJob?.cancel()
        processingTimeoutJob = null
    }

    /**
     * Maps a [com.celzero.bravedns.iab.BillingResponse] to a user-friendly message.
     */
    private fun friendlyBillingMessage(response: com.celzero.bravedns.iab.BillingResponse): String {
        return when (response.rawCode) {
            com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ->
                getString(R.string.billing_err_service_unavailable)
            com.android.billingclient.api.BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                getString(R.string.billing_err_billing_unavailable)
            com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                getString(R.string.billing_err_item_unavailable)
            com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_TIMEOUT ->
                getString(R.string.billing_err_service_timeout)
            com.android.billingclient.api.BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
                getString(R.string.billing_err_developer_error)
            com.android.billingclient.api.BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                getString(R.string.billing_err_feature_not_supported)
            com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_NOT_OWNED ->
                getString(R.string.billing_err_item_not_owned)
            else -> getString(R.string.billing_err_generic_retry)
        }
    }

    /**
     * Whether the device currently has network connectivity
     */
    private fun isOnline(): Boolean {
        return try {
            val cm = requireContext()
                .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps != null && caps.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        } catch (e: Exception) {
            Logger.w(Logger.LOG_IAB, "$TAG: connectivity check failed: ${e.message}")
            true // fail-open so a transient SystemService error does not block purchase
        }
    }

    /**
     * Explicitly restore purchases. Re-queries Google Play; the resulting
     * state-machine transition drives the UI.
     */
    private fun restorePurchases() {
        if (!isOnline()) {
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.no_internet_purchase_msg),
                Toast.LENGTH_LONG
            )
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            InAppBillingHandler.fetchPurchases(
                listOf(
                    com.android.billingclient.api.BillingClient.ProductType.SUBS,
                    com.android.billingclient.api.BillingClient.ProductType.INAPP
                )
            )
        }
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.pending_checking_status),
            Toast.LENGTH_SHORT
        )
    }


    private fun startShimmer() {
        if (!b.shimmerContainer.isShimmerStarted) {
            val shimmer = Shimmer.AlphaHighlightBuilder()
                .setDuration(2000)
                .setBaseAlpha(0.85f)
                .setDropoff(1f)
                .setHighlightAlpha(0.35f)
                .build()
            b.shimmerContainer.setShimmer(shimmer)
            b.shimmerContainer.startShimmer()
        }
    }

    private fun stopShimmer() {
        if (b.shimmerContainer.isShimmerStarted) b.shimmerContainer.stopShimmer()
    }

    private fun animateContentEntrance() {
        b.scrollView.alpha = 0f
        b.scrollView.animate().alpha(1f).setDuration(300).start()
        b.ctaContainer.alpha = 0f
        b.ctaContainer.animate().alpha(1f).setDuration(300).start()
    }

    private fun animateButtonPress(view: View) {
        ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f, 1f).apply {
            duration = 100; interpolator = AnticipateOvershootInterpolator(); start()
        }
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f, 1f).apply {
            duration = 100; interpolator = AnticipateOvershootInterpolator(); start()
        }
    }

    private fun updateHtmlEncodedText(text: String): Spanned =
        htmlToSpannedText(text)

    override fun onConnectionResult(isSuccess: Boolean, message: String) {
        viewModel.onBillingConnected(isSuccess, message)
    }

    override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) {
        // handled by state machine via InAppBillingHandler.
    }

    override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
        viewModel.onProductsFetched(isSuccess, productList)
    }

    override fun onSubscriptionSelected(productId: String, planId: String) {
        viewModel.selectProduct(productId, planId)
    }
}
