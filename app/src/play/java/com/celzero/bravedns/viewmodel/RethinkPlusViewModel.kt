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
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.rpnproxy.PipKeyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val _selectedProductType = MutableStateFlow(ProductTypeFilter.ONE_TIME)
    val selectedProductType: StateFlow<ProductTypeFilter> = _selectedProductType.asStateFlow()

    // Filtered products based on type
    private val _filteredProducts = MutableStateFlow<List<ProductDetail>>(emptyList())

    // Polling job for pending purchases
    private var pollingJob: Job? = null
    private var pollingStartTime = 0L

    val subscriptionState: LiveData<SubscriptionStateMachineV2.SubscriptionState> =
        InAppBillingHandler.getSubscriptionStateLiveData()

    companion object {
        private const val TAG = "RethinkPlusVM"
        private const val POLLING_INTERVAL_MS = 1500L
        private const val POLLING_TIMEOUT_MS = 30000L
        // If Loading is not resolved within this window, emit an Error to unblock the UI.
        private const val LOADING_TIMEOUT_MS = 15_000L
    }

    // Emitted when retry() determines the billing client needs a fresh connection.
    // The Fragment observes this and calls InAppBillingHandler.initiate() with a live context.
    private val _retryConnectionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val retryConnectionEvent: SharedFlow<Unit> = _retryConnectionEvent.asSharedFlow()

    // Watchdog job: cancels Loading if billing never responds.
    private var loadingWatchdogJob: Job? = null


    @Volatile private var isBillingInitializing = false

    @Volatile private var billingInitCalled = false

    /**
     * When true, the user entered this screen to an existing active one-time purchase
     * (e.g. the expiry banner button in ManagePurchaseFragment). In this mode:
     * - The "already subscribed" guard is bypassed so the user can buy again.
     * - The ONE_TIME tab is pre-selected.
     */
    @Volatile var extendMode: Boolean = false

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
            InAppBillingHandler.getSubscriptionStateFlow().collect { state ->
                handleSubscriptionStateChange(state)
                Logger.d(LOG_IAB, "$TAG: Subscription state changed to ${state.name}")
            }
        }
    }

    /**
     * Check if Rethink Plus is available for the user
     */
    suspend fun checkAvailability(): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            PipKeyManager.checkRpnAvailability(getApplication())
        }
    }

    /**
     * Initialize billing and query products.
     */
    fun initializeBilling() {
        // Re-entrant guard: if a billing init flight is already in progress, ignore.
        // This prevents a 2nd/3rd call (from state-machine callbacks or reconnect events)
        // from overwriting an already-resolved Error/Ready state with Loading.
        if (isBillingInitializing) {
            Logger.d(LOG_IAB, "$TAG: initializeBilling already in progress, ignoring re-entrant call")
            return
        }
        isBillingInitializing = true
        billingInitCalled = true

        viewModelScope.launch {
            _uiState.value = SubscriptionUiState.Loading
            startLoadingWatchdog()

            try {
                // Check if already subscribed, skipped in extend mode so the user can
                // purchase an additional one-time plan while their current one is active.
                if (!extendMode && InAppBillingHandler.hasValidSubscription()) {
                    cancelLoadingWatchdog()
                    isBillingInitializing = false
                    _uiState.value = SubscriptionUiState.AlreadySubscribed(
                        RpnProxyManager.getRpnProductId() ?: ""
                    )
                    return@launch
                }

                // Check availability
                val avd = checkAvailability()

                if (_uiState.value !is SubscriptionUiState.Loading) {
                    Logger.d(LOG_IAB, "$TAG: state resolved while checkAvailability was running, bailing out")
                    isBillingInitializing = false
                    return@launch
                }

                if (!avd.first) {
                    cancelLoadingWatchdog()
                    isBillingInitializing = false
                    _uiState.value = SubscriptionUiState.Error(
                        title = "Not Available",
                        message = avd.second,
                        isRetryable = false
                    )
                    return@launch
                }

                // If billing client is already connected, query products immediately.
                // Otherwise, stay in Loading, onBillingConnected() will drive the rest.
                if (InAppBillingHandler.isBillingClientSetup()) {
                    Logger.d(LOG_IAB, "$TAG: Billing already ready, querying products")
                    // isBillingInitializing cleared by onProductsFetched / watchdog
                    InAppBillingHandler.queryProductDetailsWithTimeout()
                } else {
                    Logger.d(LOG_IAB, "$TAG: Billing not ready, waiting for connection callback")
                    // Maintain Loading state, onBillingConnected() will drive the rest.
                    // Guard against race: connection may complete between initiate() and here.
                    delay(500)
                    if (_uiState.value is SubscriptionUiState.Loading && InAppBillingHandler.isBillingClientSetup()) {
                        Logger.d(LOG_IAB, "$TAG: Billing became ready during wait, querying products now")
                        InAppBillingHandler.queryProductDetailsWithTimeout()
                    }
                    // else: still not ready, onBillingConnected / watchdog will handle it
                }

            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: Error initializing billing: ${e.message}", e)
                cancelLoadingWatchdog()
                isBillingInitializing = false
                _uiState.value = SubscriptionUiState.Error(
                    title = "Initialization Failed",
                    message = e.message ?: "Unknown error occurred",
                    isRetryable = true
                )
            }
        }
    }

    /** Start a watchdog that emits an Error if Loading is not resolved within LOADING_TIMEOUT_MS. */
    private fun startLoadingWatchdog() {
        cancelLoadingWatchdog()
        loadingWatchdogJob = viewModelScope.launch {
            delay(LOADING_TIMEOUT_MS)
            // If we are still in Loading after the timeout, surface an error so the user can retry.
            if (_uiState.value is SubscriptionUiState.Loading) {
                Logger.w(LOG_IAB, "$TAG: Loading watchdog fired, billing took too long to respond")
                isBillingInitializing = false
                _uiState.value = SubscriptionUiState.Error(
                    title = "Connection Timeout",
                    message = "Google Play Billing is not responding. Please try again.",
                    isRetryable = true
                )
            }
        }
    }

    private fun cancelLoadingWatchdog() {
        loadingWatchdogJob?.cancel()
        loadingWatchdogJob = null
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

        // Filter by current selection; in extend mode always force ONE_TIME
        if (extendMode) {
            _selectedProductType.value = ProductTypeFilter.ONE_TIME
        }
        filterProductsByType(_selectedProductType.value)

        // If user already has a valid subscription, navigate to dashboard instead
        // of showing the purchase UI.
        // In extend mode this check is intentionally skipped.
        val currentState = InAppBillingHandler.getSubscriptionState()
        if (!extendMode && !currentState.canMakePurchase) {
            Logger.i(LOG_IAB, "$TAG: Cannot make purchase in state: ${currentState.name}, " +
                    "hasValid=${currentState.hasValidSubscription}")
            if (currentState.hasValidSubscription) {
                _uiState.value = SubscriptionUiState.AlreadySubscribed(
                    RpnProxyManager.getRpnProductId() ?: ""
                )
            } else {
                // Subscription is in a non-purchasable, non-valid state (e.g. Uninitialized).
                _uiState.value = SubscriptionUiState.Ready(
                    _filteredProducts.value, false, PipKeyManager.getAvailabilityData()
                )
            }
            return
        }

        _uiState.value = SubscriptionUiState.Ready(_filteredProducts.value, false, PipKeyManager.getAvailabilityData())
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
            _uiState.value = SubscriptionUiState.Ready(_filteredProducts.value, false, PipKeyManager.getAvailabilityData())
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

    // Tracks whether the user explicitly started a purchase flow in THIS screen session.
    @Volatile
    private var purchaseFlowActive = false

    // Job that watches oneTimePurchaseCompletedFlow while an extend-mode purchase is in flight.
    // Canceled when the flow resolves (success or error) or the ViewModel is cleared.
    private var extendObserverJob: Job? = null

    /**
     * Called by the Fragment when a billing transaction error fires while in extend mode.
     * The state machine stays in [SubscriptionStateMachineV2.SubscriptionState.Active]
     * (no PurchaseFailed transition exists from Active), so [observeSubscriptionState] never
     * sees an Error state and cannot clean up the extend-mode flow flags itself.
     * This method must be called explicitly from the Fragment's transactionErrorLiveData
     * observer so [purchaseFlowActive] and [extendObserverJob] are always reset on failure.
     */
    fun onTransactionError() {
        if (purchaseFlowActive) {
            Logger.d(LOG_IAB, "$TAG: onTransactionError: clearing purchaseFlowActive (extendMode=$extendMode)")
            purchaseFlowActive = false
            extendObserverJob?.cancel()
            extendObserverJob = null
        }
    }

    fun markPurchaseFlowActive() {
        purchaseFlowActive = true
        _uiState.value = SubscriptionUiState.Processing("Initializing purchase...")
        Logger.d(LOG_IAB, "$TAG: purchaseFlowActive set manually (extend mode)")

        // Watch InAppBillingHandler.oneTimePurchaseCompletedFlow so we can detect success even
        // when the state machine stays in Active (Active → Active is de-duplicated by StateFlow
        // and therefore never re-emitted to our regular subscription state observer).
        extendObserverJob?.cancel()
        extendObserverJob = viewModelScope.launch {
            InAppBillingHandler.oneTimePurchaseCompletedFlow.collect {
                if (purchaseFlowActive && extendMode) {
                    Logger.i(LOG_IAB, "$TAG: extend-mode INAPP purchase completed, emitting Success")
                    purchaseFlowActive = false
                    extendObserverJob = null
                    _uiState.value = SubscriptionUiState.Success(
                        productId = RpnProxyManager.getRpnProductId() ?: "",
                        isExtend = true
                    )
                    return@collect
                }
            }
        }
    }

    /**
     * Handle subscription state machine changes.
     *
     */
    private fun handleSubscriptionStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        Logger.d(LOG_IAB, "$TAG: handleSubscriptionStateChange: ${state.name}, purchaseFlowActive=$purchaseFlowActive")
        when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated -> {
                // Only PurchaseInitiated (triggered by user tapping Buy) marks an active flow.
                purchaseFlowActive = true
                _uiState.value = SubscriptionUiState.Processing("Initializing purchase...")
            }

            is SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> {
                // Only start polling if the user actually initiated a purchase in this session.
                if (purchaseFlowActive) {
                    _uiState.value = SubscriptionUiState.PendingPurchase
                    startPendingPurchasePolling()
                } else {
                    Logger.d(LOG_IAB, "$TAG: PurchasePending without active flow, querying Play once to reconcile")
                    viewModelScope.launch(Dispatchers.IO) {
                        InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                    }
                }
            }

            is SubscriptionStateMachineV2.SubscriptionState.Active -> {
                stopPendingPurchasePolling()
                if (purchaseFlowActive) {
                    // Real new purchase completed (non-extend path) → show success with confetti.
                    // In extend mode this block is typically skipped because StateFlow
                    // de-duplicates the Active → Active transition; success is detected by
                    // extendObserverJob watching oneTimePurchaseCompletedFlow instead.
                    purchaseFlowActive = false
                    extendObserverJob?.cancel()
                    extendObserverJob = null
                    _uiState.value = SubscriptionUiState.Success(
                        productId = RpnProxyManager.getRpnProductId() ?: "",
                        isExtend = false
                    )
                } else {
                    val current = _uiState.value
                    if (billingInitCalled && !extendMode && current is SubscriptionUiState.Loading) {
                        _uiState.value = SubscriptionUiState.AlreadySubscribed(
                            RpnProxyManager.getRpnProductId() ?: ""
                        )
                    }
                    // If already in Ready/Error/etc., don't disrupt the current UI.
                }
            }

            is SubscriptionStateMachineV2.SubscriptionState.Error -> {
                val wasInFlow = purchaseFlowActive
                purchaseFlowActive = false
                extendObserverJob?.cancel()
                extendObserverJob = null
                stopPendingPurchasePolling()
                if (wasInFlow) {
                    // Real purchase flow failed, show an actionable error.
                    _uiState.value = SubscriptionUiState.Error(
                        title = "Subscription Error",
                        message = "An error occurred while processing your payment",
                        isRetryable = true
                    )
                } else {
                    // handlePurchase drove PurchasePending → Error because Play returned
                    // no purchases (stale DB row, no real purchase). Show the payment
                    // screen so the user can buy instead of seeing a cryptic error.
                    Logger.d(LOG_IAB, "$TAG: Error state without active flow, showing payment screen")
                    val products = _filteredProducts.value.ifEmpty { allProducts }
                    if (products.isNotEmpty()) {
                        _uiState.value = SubscriptionUiState.Ready(
                            products = products,
                            isResubscribe = false,
                            availabilityData = PipKeyManager.getAvailabilityData()
                        )
                    } else {
                        // Products not fetched yet trigger fetch; setProducts() will update UI
                        viewModelScope.launch(Dispatchers.IO) {
                            if (InAppBillingHandler.isBillingClientSetup()) {
                                InAppBillingHandler.queryProductDetailsWithTimeout()
                            }
                        }
                    }
                }
            }

            is SubscriptionStateMachineV2.SubscriptionState.Cancelled,
            is SubscriptionStateMachineV2.SubscriptionState.Revoked,
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                purchaseFlowActive = false
                extendObserverJob?.cancel()
                extendObserverJob = null
                stopPendingPurchasePolling()
                val currentUi = _uiState.value
                // Don't disrupt Loading or Processing states triggered by an explicit action
                if (currentUi is SubscriptionUiState.Loading || currentUi is SubscriptionUiState.Processing) return

                val products = _filteredProducts.value.ifEmpty { allProducts }
                if (products.isNotEmpty()) {
                    _uiState.value = SubscriptionUiState.Ready(
                        products = products,
                        isResubscribe = true,
                        availabilityData = PipKeyManager.getAvailabilityData()
                    )
                } else {
                    // Products not loaded yet - trigger load; UI will update via onProductsFetched
                    Logger.d(LOG_IAB, "$TAG: $state but no products loaded, triggering fetch")
                    viewModelScope.launch(Dispatchers.IO) {
                        if (InAppBillingHandler.isBillingClientSetup()) {
                            InAppBillingHandler.queryProductDetailsWithTimeout()
                        }
                    }
                }
            }

            is SubscriptionStateMachineV2.SubscriptionState.Initial,
            is SubscriptionStateMachineV2.SubscriptionState.Uninitialized -> {
                // Transient init states, ignore to avoid premature navigation.
                // The state machine always passes through these during startup.
                Logger.d(LOG_IAB, "$TAG: Ignoring transient init state ${state.name}")
            }

            else -> {
                Logger.d(LOG_IAB, "$TAG: Unhandled state: ${state.name}")
            }
        }
    }

    /**
     * Start polling for pending purchase status
     */
    private fun startPendingPurchasePolling() {
        if (pollingJob != null) return

        pollingStartTime = System.currentTimeMillis()

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
     * Handle billing connection result.
     * BUG FIX: previously only fetched purchases on connect, never queried product details →
     * if the billing client was not yet ready when initializeBilling() ran, products were never
     * loaded and the UI stayed in Loading forever.
     */
    fun onBillingConnected(isSuccess: Boolean, message: String) {
        if (!isSuccess) {
            cancelLoadingWatchdog()
            Logger.e(LOG_IAB, "$TAG: Billing connection failed: $message")
            isBillingInitializing = false
            _uiState.value = SubscriptionUiState.Error(
                title = "Connection Failed",
                message = message,
                isRetryable = true
            )
        } else {
            // Ensure the UI is in Loading while we fetch products.
            if (_uiState.value !is SubscriptionUiState.Loading) {
                _uiState.value = SubscriptionUiState.Loading
            }
            startLoadingWatchdog()
            isBillingInitializing = true

            viewModelScope.launch(Dispatchers.IO) {
                // Re-check subscription state first; if already active, skip product query.
                // Skip this check in extend mode, the user is here to buy additional time
                // while their existing subscription is still active.
                if (!extendMode && InAppBillingHandler.hasValidSubscription()) {
                    cancelLoadingWatchdog()
                    isBillingInitializing = false
                    withContext(Dispatchers.Main) {
                        _uiState.value = SubscriptionUiState.AlreadySubscribed(
                            RpnProxyManager.getRpnProductId() ?: ""
                        )
                    }
                    return@launch
                }

                // Check availability before querying products.
                val avd = checkAvailability()
                if (!avd.first) {
                    cancelLoadingWatchdog()
                    isBillingInitializing = false
                    withContext(Dispatchers.Main) {
                        _uiState.value = SubscriptionUiState.Error(
                            title = "Not Available",
                            message = avd.second,
                            isRetryable = false
                        )
                    }
                    return@launch
                }

                // Fetch existing purchases to sync state machine, then query displayable products.
                InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                InAppBillingHandler.queryProductDetailsWithTimeout()
                // isBillingInitializing cleared by onProductsFetched
            }
        }
    }

    /**
     * Handle product details result
     */
    fun onProductsFetched(isSuccess: Boolean, productList: List<ProductDetail>) {
        cancelLoadingWatchdog()
        isBillingInitializing = false
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
     * Retry initialization after an error.
     *
     * 1. Reset the re-entrant guard so [initializeBilling] is not blocked.
     * 2. Call [initializeBilling]: sets Loading + watchdog.
     * 3. If billing client is not ready, emit [retryConnectionEvent] so the Fragment
     *    calls [InAppBillingHandler.initiate] with a live context, the ViewModel cannot
     *    do this itself because [InAppBillingHandler.initiate] needs an Android Context.
     */
    fun retry() {
        // Always clear the guard before retrying so initializeBilling() is not blocked.
        isBillingInitializing = false
        initializeBilling()
        // If billing client is NOT ready, signal the Fragment to reconnect.
        if (!InAppBillingHandler.isBillingClientSetup()) {
            Logger.d(LOG_IAB, "$TAG: retry, billing client not ready, requesting reconnect from Fragment")
            _retryConnectionEvent.tryEmit(Unit)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPendingPurchasePolling()
        cancelLoadingWatchdog()
        extendObserverJob?.cancel()
        extendObserverJob = null
    }
}

/**
 * Sealed class representing all possible UI states
 */
sealed class SubscriptionUiState {
    object Loading : SubscriptionUiState()

    data class Ready(
        val products: List<ProductDetail>,
        val isResubscribe: Boolean = false,
        val availabilityData: Available? = null
    ) : SubscriptionUiState()

    data class Available(
        val vcode: String,
        val minVcode: String,
        val canSell: Boolean,
        val ip: String,
        val country: String,
        val asorg: String,
        val city: String,
        val colo: String,
        val region: String,
        val postalCode: String,
        val addr: String
    ) : SubscriptionUiState()

    data class Processing(val message: String) : SubscriptionUiState()

    object PendingPurchase : SubscriptionUiState()

    data class Success(val productId: String, val isExtend: Boolean = false) : SubscriptionUiState()

    data class Error(
        val title: String,
        val message: String,
        val isRetryable: Boolean
    ) : SubscriptionUiState()

    data class AlreadySubscribed(val productId: String) : SubscriptionUiState()
}
