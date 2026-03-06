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
package com.celzero.bravedns.viewmodel

import Logger
import Logger.LOG_IAB
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.rpnproxy.PipKeyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.subscription.SubscriptionStateMachineV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ViewModel for Rethink Plus subscription management
 * Handles all business logic and state management for the subscription UI
 */
class RethinkPlusViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val subscriptionStateMachine: SubscriptionStateMachineV2 by inject()

    // UI State
    private val _uiState = MutableStateFlow<SubscriptionUiState>(SubscriptionUiState.Loading)
    val uiState: StateFlow<SubscriptionUiState> = _uiState.asStateFlow()

    // Selected product
    private val _selectedProduct = MutableStateFlow<Pair<String, String>?>(null)
    val selectedProduct: StateFlow<Pair<String, String>?> = _selectedProduct.asStateFlow()

    // Product details
    private val _products = MutableStateFlow<List<ProductDetail>>(emptyList())
    val products: StateFlow<List<ProductDetail>> = _products.asStateFlow()

    // All products (unfiltered)
    private var allProducts: List<ProductDetail> = emptyList()

    // Product type selection
    private val _selectedProductType = MutableStateFlow(ProductTypeFilter.SUBSCRIPTION)
    val selectedProductType: StateFlow<ProductTypeFilter> = _selectedProductType.asStateFlow()

    // Filtered products based on type
    private val _filteredProducts = MutableStateFlow<List<ProductDetail>>(emptyList())
    val filteredProducts: StateFlow<List<ProductDetail>> = _filteredProducts.asStateFlow()

    // Polling job for pending purchases
    private var pollingJob: Job? = null
    private var pollingStartTime = 0L

    companion object {
        private const val TAG = "RethinkPlusVM"
        private const val POLLING_INTERVAL_MS = 1500L
        private const val POLLING_TIMEOUT_MS = 30000L
    }

    enum class ProductTypeFilter {
        SUBSCRIPTION,
        ONE_TIME
    }

    init {
        observeSubscriptionState()
    }

    /**
     * Observe subscription state machine changes
     */
    private fun observeSubscriptionState() {
        viewModelScope.launch {
            RpnProxyManager.collectSubscriptionState().collect { state ->
                Logger.d(LOG_IAB, "$TAG: Subscription state changed to ${state.name}")
                handleSubscriptionStateChange(state)
            }
        }
    }

    /**
     * Check if Rethink Plus is available for the user
     */
    suspend fun checkAvailability(): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            PipKeyManager.isRethinkPlusActive(getApplication())
        }
    }

    /**
     * Initialize billing and query products
     */
    fun initializeBilling() {
        viewModelScope.launch {
            _uiState.value = SubscriptionUiState.Loading

            try {
                // Check if already subscribed
                if (InAppBillingHandler.hasValidSubscription()) {
                    _uiState.value = SubscriptionUiState.AlreadySubscribed(
                        RpnProxyManager.getRpnProductId()
                    )
                    return@launch
                }

                // Check availability
                val availability = checkAvailability()
                if (!availability.first) {
                    _uiState.value = SubscriptionUiState.Error(
                        title = "Not Available",
                        message = availability.second,
                        isRetryable = false
                    )
                    return@launch
                }

                // Query product details
                InAppBillingHandler.queryProductDetailsWithTimeout()

            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: Error initializing billing: ${e.message}", e)
                _uiState.value = SubscriptionUiState.Error(
                    title = "Initialization Failed",
                    message = e.message ?: "Unknown error occurred",
                    isRetryable = true
                )
            }
        }
    }

    /**
     * Set product details from billing handler
     */
    fun setProducts(productList: List<ProductDetail>) {
        allProducts = productList
        _products.value = productList

        if (productList.isEmpty()) {
            _uiState.value = SubscriptionUiState.Error(
                title = "No Products Available",
                message = "Unable to load subscription plans",
                isRetryable = true
            )
            return
        }

        // Filter by current selection
        filterProductsByType(_selectedProductType.value)

        // Check if can make purchase
        val currentState = subscriptionStateMachine.getCurrentState()
        if (!currentState.canMakePurchase) {
            Logger.i(LOG_IAB, "$TAG: Cannot make purchase in state: ${currentState.name}")
            return
        }

        _uiState.value = SubscriptionUiState.Ready(_filteredProducts.value)
    }

    /**
     * Filter products by type
     */
    private fun filterProductsByType(type: ProductTypeFilter) {
        val filtered = when (type) {
            ProductTypeFilter.SUBSCRIPTION -> allProducts.filter { it.productType == ProductType.SUBS }
            ProductTypeFilter.ONE_TIME -> allProducts.filter { it.productType == ProductType.INAPP }
        }
        
        _filteredProducts.value = filtered
        Logger.d(LOG_IAB, "$TAG: Filtered products by $type: ${filtered.size} items")

        // Auto-select first product if available
        if (filtered.isNotEmpty()) {
            val first = filtered.first()
            _selectedProduct.value = Pair(first.productId, first.planId)
        }
    }

    /**
     * Switch product type filter
     */
    fun selectProductType(type: ProductTypeFilter) {
        if (_selectedProductType.value == type) return
        
        _selectedProductType.value = type
        filterProductsByType(type)
        
        // Update UI state with filtered products
        if (_filteredProducts.value.isNotEmpty()) {
            _uiState.value = SubscriptionUiState.Ready(_filteredProducts.value)
        } else {
            _uiState.value = SubscriptionUiState.Error(
                title = "No Products",
                message = "No ${type.name.lowercase()} products available",
                isRetryable = false
            )
        }
    }

    /**
     * Update selected product
     */
    fun selectProduct(productId: String, planId: String) {
        _selectedProduct.value = Pair(productId, planId)
        Logger.d(LOG_IAB, "$TAG: Selected product: $productId, plan: $planId")
    }

    /**
     * Handle subscription state machine changes
     */
    private fun handleSubscriptionStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated -> {
                _uiState.value = SubscriptionUiState.Processing("Initializing purchase...")
            }

            is SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> {
                _uiState.value = SubscriptionUiState.PendingPurchase
                startPendingPurchasePolling()
            }

            is SubscriptionStateMachineV2.SubscriptionState.Active -> {
                stopPendingPurchasePolling()
                _uiState.value = SubscriptionUiState.Success(
                    RpnProxyManager.getRpnProductId()
                )
            }

            is SubscriptionStateMachineV2.SubscriptionState.Error -> {
                stopPendingPurchasePolling()
                _uiState.value = SubscriptionUiState.Error(
                    title = "Subscription Error",
                    message = "An error occurred while processing your subscription",
                    isRetryable = true
                )
            }

            is SubscriptionStateMachineV2.SubscriptionState.Cancelled,
            is SubscriptionStateMachineV2.SubscriptionState.Revoked,
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                stopPendingPurchasePolling()
                // Show products with resubscribe option
                if (_products.value.isNotEmpty()) {
                    _uiState.value = SubscriptionUiState.Ready(
                        products = _products.value,
                        isResubscribe = true
                    )
                }
            }

            else -> {
                // Handle other states if needed
            }
        }
    }

    /**
     * Start polling for pending purchase status
     */
    private fun startPendingPurchasePolling() {
        if (pollingJob != null) return

        pollingStartTime = System.currentTimeMillis()
        var counter = 0

        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val elapsedTime = System.currentTimeMillis() - pollingStartTime

                if (elapsedTime > POLLING_TIMEOUT_MS) {
                    Logger.i(LOG_IAB, "$TAG: Polling timeout reached")
                    stopPendingPurchasePolling()
                    withContext(Dispatchers.Main) {
                        _uiState.value = SubscriptionUiState.Error(
                            title = "Timeout",
                            message = "Purchase verification timeout. Please check your subscription status.",
                            isRetryable = false
                        )
                    }
                    break
                }

                Logger.d(LOG_IAB, "$TAG: Polling pending purchase, elapsed: $elapsedTime ms")
                InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))

                // Query entitlement from server once
                if (counter == 0) {
                    val accountId = InAppBillingHandler.getObfuscatedAccountId(getApplication())
                    val purchaseToken = InAppBillingHandler.getLatestPurchaseToken()
                    Logger.d(
                        LOG_IAB,
                        "$TAG: Querying entitlement from server; with purchaseToken: $purchaseToken"
                    )
                    if (purchaseToken == null) {
                        Logger.e(LOG_IAB, "$TAG: Purchase token is null, cannot query entitlement")
                    } else {
                        // this will initiate (if not done) the acknowledgement from server side
                        InAppBillingHandler.acknowledgePurchaseFromServer(accountId, purchaseToken)
                    }
                    counter++
                }

                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop polling for pending purchase
     */
    private fun stopPendingPurchasePolling() {
        pollingJob?.cancel()
        pollingJob = null
        Logger.i(LOG_IAB, "$TAG: Pending purchase polling stopped")
    }

    /**
     * Handle billing connection result
     */
    fun onBillingConnected(isSuccess: Boolean, message: String) {
        if (!isSuccess) {
            Logger.e(LOG_IAB, "$TAG: Billing connection failed: $message")
            _uiState.value = SubscriptionUiState.Error(
                title = "Connection Failed",
                message = message,
                isRetryable = true
            )
        } else {
            // Fetch purchases after connection
            viewModelScope.launch(Dispatchers.IO) {
                InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
            }
        }
    }

    /**
     * Handle product details result
     */
    fun onProductsFetched(isSuccess: Boolean, productList: List<ProductDetail>) {
        if (!isSuccess || productList.isEmpty()) {
            _uiState.value = SubscriptionUiState.Error(
                title = "Products Unavailable",
                message = "Unable to load subscription plans. Please try again.",
                isRetryable = true
            )
        } else {
            setProducts(productList)
        }
    }

    /**
     * Retry initialization
     */
    fun retry() {
        initializeBilling()
    }

    override fun onCleared() {
        super.onCleared()
        stopPendingPurchasePolling()
    }
}

/**
 * Sealed class representing all possible UI states
 */
sealed class SubscriptionUiState {
    object Loading : SubscriptionUiState()

    data class Ready(
        val products: List<ProductDetail>,
        val isResubscribe: Boolean = false
    ) : SubscriptionUiState()

    data class Processing(val message: String) : SubscriptionUiState()

    object PendingPurchase : SubscriptionUiState()

    data class Success(val productId: String) : SubscriptionUiState()

    data class Error(
        val title: String,
        val message: String,
        val isRetryable: Boolean
    ) : SubscriptionUiState()

    data class AlreadySubscribed(val productId: String) : SubscriptionUiState()
}

