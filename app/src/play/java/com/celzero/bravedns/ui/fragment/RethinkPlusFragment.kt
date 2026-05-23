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

import Logger
import Logger.LOG_TAG_UI
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
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
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
import kotlinx.coroutines.launch

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

    private enum class PurchaseButtonState {
        PURCHASED, NOT_PURCHASED
    }

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
        val primaryText = UIUtils.fetchColor(ctx, R.attr.primaryTextColor)
        val lightText  = UIUtils.fetchColor(ctx, R.attr.primaryLightColorText)

        b.subscribeButton.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentGood)
            setTextColor(primaryText)
            iconTint = android.content.res.ColorStateList.valueOf(primaryText)
        }

        b.btnContactSupport.apply {
            setTextColor(lightText)
            iconTint = android.content.res.ColorStateList.valueOf(lightText)
        }

        b.retryButton.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentGood)
            setTextColor(primaryText)
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
        // In extend mode pre-select ONE_TIME and hide the SUBS tab so the
        // user focuses on one-time purchase options only.
        val initialType = if (viewModel.extendMode) {
            RethinkPlusViewModel.ProductTypeFilter.ONE_TIME
        } else {
            RethinkPlusViewModel.ProductTypeFilter.ONE_TIME
        }
        updateToggleState(initialType)

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

        viewModel.subscriptionState.observe(owner) { state ->
            handleSubscriptionState(state)
        }

        // observe billing errors (user cancel, network, etc.) to dismiss the processing sheet
        InAppBillingHandler.transactionErrorLiveData.observe(owner) { billingResult ->
            billingResult ?: return@observe
            purchaseInFlight = false
            cancelProcessingTimeout()
            dismissProcessingBottomSheet()

            val response = com.celzero.bravedns.iab.BillingResponse(billingResult.responseCode)
            when {
                response.isUserCancelled -> {
                    Logger.d(Logger.LOG_IAB, "$TAG: User cancelled purchase")
                }
                response.isRecoverableError -> {
                    showTransactionError(getString(R.string.billing_error_recoverable))
                }
                else -> {
                    showTransactionError(
                        billingResult.debugMessage.ifBlank { getString(R.string.billing_error_generic) }
                    )
                }
            }
        }
    }

    private fun handleSubscriptionState(state: SubscriptionStateMachineV2.SubscriptionState) {
        Logger.d(LOG_TAG_UI, "$TAG: Observed subscription state: ${state.name}")
        if (state.hasValidSubscription) {
            setPurchaseButtonState(PurchaseButtonState.PURCHASED)
        } else {
            setPurchaseButtonState(PurchaseButtonState.NOT_PURCHASED)
        }
    }

    private fun setPurchaseButtonState(state: PurchaseButtonState) {
        when (state) {
            PurchaseButtonState.PURCHASED -> {
                b.subscribeButton.text = getString(R.string.rpn_purchased_state)
                b.subscribeButton.isEnabled = false
            }
            PurchaseButtonState.NOT_PURCHASED -> {
                updateSubscribeButtonText(viewModel.selectedProductType.value, isResubscribeState)
                b.subscribeButton.isEnabled = true
            }
        }
    }

    private fun handleUiState(state: SubscriptionUiState) {
        Logger.d(Logger.LOG_IAB, "$TAG: Handling UI state: ${state::class.simpleName}")
        when (state) {
            is SubscriptionUiState.Loading -> showLoading()
            is SubscriptionUiState.Ready -> showReady(state.products, state.isResubscribe, state.availabilityData)
            is SubscriptionUiState.Processing -> showProcessing(state.message)
            is SubscriptionUiState.PendingPurchase -> showPendingPurchase()
            is SubscriptionUiState.Success -> showSuccess(state.productId, state.isExtend)
            is SubscriptionUiState.Error -> showError(state.title, state.message, state.isRetryable)
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
            adapter = GooglePlaySubsAdapter(this, requireContext(), products, 1, false)
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
            b.connectionIsp.text =
                if (state.asorg.length > 20) getString(R.string.truncated_text, state.asorg.take(17))
                else state.asorg
        } else {
            b.ispContainer.isVisible = false
        }
    }

    private fun showProcessing(message: String) {
        purchaseInFlight = true
        showProcessingBottomSheet(PurchaseProcessingBottomSheet.ProcessingState.Processing, message)
        startProcessingTimeout()
    }

    private fun showPendingPurchase() {
        purchaseInFlight = true
        showProcessingBottomSheet(PurchaseProcessingBottomSheet.ProcessingState.PendingVerification, null)
        startProcessingTimeout()
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
            delay(1500)
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
            delay(PROCESSING_TIMEOUT_MS)
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
