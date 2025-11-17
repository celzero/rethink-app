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
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.animation.AnticipateOvershootInterpolator
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.GooglePlaySubsAdapter
import com.celzero.bravedns.databinding.FragmentRethinkPlusPremiumBinding
import com.celzero.bravedns.iab.BillingListener
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.ui.bottomsheet.PurchaseProcessingBottomSheet
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.RethinkPlusViewModel
import com.celzero.bravedns.viewmodel.SubscriptionUiState
import com.facebook.shimmer.Shimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus_premium),
    GooglePlaySubsAdapter.SubscriptionChangeListener,
    BillingListener {

    private val binding by viewBinding(FragmentRethinkPlusPremiumBinding::bind)
    private val viewModel: RethinkPlusViewModel by viewModels()

    private var adapter: GooglePlaySubsAdapter? = null
    private var processingBottomSheet: PurchaseProcessingBottomSheet? = null

    companion object {
        private const val TAG = "R+Ui"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupObservers()
        setupClickListeners()
        initializeBilling()
    }

    private fun setupUI() {
        setupRecyclerView()
        setupTermsAndPolicy()
    }

    private fun setupRecyclerView() {
        binding.subscriptionPlans.apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }
    }

    private fun setupTermsAndPolicy() {
        binding.termsText.apply {
            text = updateHtmlEncodedText(getString(R.string.rethink_terms))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
    }

    private fun setupObservers() {
        // observe UI state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    handleUiState(state)
                }
            }
        }

        // observe selected product
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedProduct.collect { selection ->
                    selection?.let {
                        Logger.d(Logger.LOG_IAB, "$TAG: Selected product: ${it.first}, plan: ${it.second}")
                    }
                }
            }
        }

        // observe InAppBillingHandler LiveData
        InAppBillingHandler.productDetailsLiveData.observe(viewLifecycleOwner) { products ->
            viewModel.onProductsFetched(products.isNotEmpty(), products)
        }

        InAppBillingHandler.connectionResultLiveData.observe(viewLifecycleOwner) { result ->
            viewModel.onBillingConnected(result.isSuccess, result.message)
        }

        // observe transaction errors - dismiss bottom sheet and show error
        InAppBillingHandler.transactionErrorLiveData.observe(viewLifecycleOwner) { billingResult ->
            Logger.w(Logger.LOG_IAB, "$TAG: Transaction error received: ${billingResult.responseCode}, ${billingResult.debugMessage}")
            // Dismiss bottom sheet immediately
            dismissProcessingBottomSheet()

            // Show error based on response code
            when (billingResult.responseCode) {
                1 -> { // USER_CANCELED
                    // User cancelled - just dismiss, no error message needed
                    Logger.d(Logger.LOG_IAB, "$TAG: User cancelled purchase, bottom sheet dismissed")
                }
                else -> {
                    // Show error for other cases
                    val errorMessage = billingResult.debugMessage ?: "Transaction failed"
                    showTransactionError(errorMessage)
                }
            }
        }
    }

    /**
     * Handle UI state changes
     */
    private fun handleUiState(state: SubscriptionUiState) {
        Logger.d(Logger.LOG_IAB, "$TAG: Handling UI state: ${state::class.simpleName}")

        when (state) {
            is SubscriptionUiState.Loading -> showLoading()
            is SubscriptionUiState.Ready -> showReady(state.products, state.isResubscribe)
            is SubscriptionUiState.Processing -> showProcessing(state.message)
            is SubscriptionUiState.PendingPurchase -> showPendingPurchase()
            is SubscriptionUiState.Success -> showSuccess(state.productId)
            is SubscriptionUiState.Error -> showError(state.title, state.message, state.isRetryable)
            is SubscriptionUiState.AlreadySubscribed -> navigateToDashboard(state.productId)
        }
    }

    /**
     * Show loading state
     */
    private fun showLoading() {
        hideAllContainers()
        binding.loadingContainer.isVisible = true
        startShimmer()
    }

    /**
     * Show ready state with products
     */
    private fun showReady(products: List<ProductDetail>, isResubscribe: Boolean) {
        hideAllContainers()
        stopShimmer()

        binding.scrollView.isVisible = true
        binding.subscribeButtonContainer.isVisible = true

        // update button text
        binding.subscribeButton.text = if (isResubscribe) {
            getString(R.string.resubscribe_title)
        } else {
            getString(R.string.subscribe_title)
        }

        // set adapter data
        if (adapter == null) {
            // create adapter with products and showShimmer = false
            adapter = GooglePlaySubsAdapter(this, requireContext(), products, 0, false)
            binding.subscriptionPlans.adapter = adapter
        } else {
            adapter?.setData(products)
        }

        // animate entrance
        animateContentEntrance()
    }

    /**
     * Show processing state
     */
    private fun showProcessing(message: String) {
        showProcessingBottomSheet(
            PurchaseProcessingBottomSheet.ProcessingState.Processing,
            message
        )
    }

    /**
     * Show pending purchase state
     */
    private fun showPendingPurchase() {
        showProcessingBottomSheet(
            PurchaseProcessingBottomSheet.ProcessingState.PendingVerification,
            null
        )
    }

    /**
     * Show success state
     */
    private fun showSuccess(productId: String) {
        showProcessingBottomSheet(
            PurchaseProcessingBottomSheet.ProcessingState.Success,
            getString(R.string.subscription_activated)
        )

        // Navigate after delay
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1500)
            navigateToDashboard(productId)
        }
    }

    /**
     * Show error state
     */
    private fun showError(title: String, message: String, isRetryable: Boolean) {
        // dismiss any processing bottom sheet
        dismissProcessingBottomSheet()

        hideAllContainers()
        stopShimmer()

        binding.errorContainer.isVisible = true
        binding.errorTitle.text = title
        binding.errorMessage.text = message
        binding.retryButton.isVisible = isRetryable
    }

    /**
     * Show transaction error via toast
     */
    private fun showTransactionError(message: String) {
        if (!isAdded || context == null) {
            Logger.w(Logger.LOG_IAB, "$TAG: Cannot show error - fragment not attached")
            return
        }

        Utilities.showToastUiCentered(
            requireContext(),
            message,
            Toast.LENGTH_LONG
        )
    }

    /**
     * Show/update processing bottom sheet
     */
    private fun showProcessingBottomSheet(
        state: PurchaseProcessingBottomSheet.ProcessingState,
        message: String?
    ) {
        if (processingBottomSheet == null || processingBottomSheet?.isAdded != true) {
            processingBottomSheet = PurchaseProcessingBottomSheet.Companion.newInstance(state, message)
            processingBottomSheet?.show(childFragmentManager, "processing")
        } else {
            processingBottomSheet?.updateState(state, message)
        }
    }

    /**
     * Dismiss processing bottom sheet
     */
    private fun dismissProcessingBottomSheet() {
        try {
            // use dismissAllowingStateLoss to avoid IllegalStateException
            processingBottomSheet?.dismissAllowingStateLoss()
        } catch (e: Exception) {
            Logger.w(Logger.LOG_IAB, "$TAG: err dismissing btmsht: ${e.message}")
        } finally {
            processingBottomSheet = null
        }
    }

    /**
     * Hide all container views
     */
    private fun hideAllContainers() {
        binding.loadingContainer.isVisible = false
        binding.scrollView.isVisible = false
        binding.subscribeButtonContainer.isVisible = false
        binding.errorContainer.isVisible = false
    }

    /**
     * Setup click listeners
     */
    private fun setupClickListeners() {
        binding.subscribeButton.setOnClickListener {
            animateButtonPress(it)
            purchaseSubscription()
        }

        binding.retryButton.setOnClickListener {
            animateButtonPress(it)
            viewModel.retry()
        }
    }

    /**
     * Initialize billing
     */
    private fun initializeBilling() {
        if (!InAppBillingHandler.isBillingClientSetup()) {
            InAppBillingHandler.initiate(requireContext().applicationContext, this)
        }
        viewModel.initializeBilling()
    }

    /**
     * Purchase subscription
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
            InAppBillingHandler.purchaseSubs(requireActivity(), productId, planId)
        }
    }

    /**
     * Navigate to dashboard
     */
    private fun navigateToDashboard(productId: String) {
        if (!isAdded) return

        Logger.i(Logger.LOG_IAB, "$TAG: Navigating to dashboard for product: $productId")
        try {
            findNavController().navigate(R.id.action_switch_to_rethinkPlusDashboardFragment)
        } catch (e: Exception) {
            Logger.e(Logger.LOG_IAB, "$TAG: Navigation failed: ${e.message}", e)
        }
    }

    /**
     * Shimmer animations
     */
    private fun startShimmer() {
        if (!binding.shimmerContainer.isShimmerStarted) {
            val shimmer = Shimmer.AlphaHighlightBuilder()
                .setDuration(2000)
                .setBaseAlpha(0.85f)
                .setDropoff(1f)
                .setHighlightAlpha(0.35f)
                .build()
            binding.shimmerContainer.setShimmer(shimmer)
            binding.shimmerContainer.startShimmer()
        }
    }

    private fun stopShimmer() {
        if (binding.shimmerContainer.isShimmerStarted) {
            binding.shimmerContainer.stopShimmer()
        }
    }

    /**
     * Animate content entrance
     */
    private fun animateContentEntrance() {
        binding.scrollView.alpha = 0f
        binding.scrollView.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    /**
     * Animate button press
     */
    private fun animateButtonPress(view: View) {
        ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f, 1f).apply {
            duration = 100
            interpolator = AnticipateOvershootInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f, 1f).apply {
            duration = 100
            interpolator = AnticipateOvershootInterpolator()
            start()
        }
    }

    /**
     * Utility: Update HTML encoded text
     */
    private fun updateHtmlEncodedText(text: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    // BillingListener callbacks
    override fun onConnectionResult(isSuccess: Boolean, message: String) {
        viewModel.onBillingConnected(isSuccess, message)
    }

    override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) {
        // Handled by state machine
    }

    override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
        viewModel.onProductsFetched(isSuccess, productList)
    }

    // SubscriptionChangeListener callback
    override fun onSubscriptionSelected(productId: String, planId: String) {
        viewModel.selectProduct(productId, planId)
    }

    override fun onResume() {
        super.onResume()
        if (binding.loadingContainer.isVisible) {
            startShimmer()
        }
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissProcessingBottomSheet()
        adapter = null
    }
}
