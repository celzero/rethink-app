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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.R
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.rpnproxy.PipKeyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager
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
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.core.component.KoinComponent
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * Sticky record of the last unresolved user-facing condition (acknowledgement
     * failure / error / pending-timeout). Unlike [uiState] which is overwritten on every
     * transition, this is retained across fragment re-entry so the Fragment can re-surface
     * the error dialog / bottom sheet after the user navigates away and returns
     * Cleared only when the state genuinely resolves
     * (Active / Success / Ready / Loading).
     */
    private val _lastUnresolved = MutableStateFlow<SubscriptionUiState?>(null)
    val lastUnresolved: StateFlow<SubscriptionUiState?> = _lastUnresolved.asStateFlow()

    /**
     * Single funnel for all [uiState] assignments. Records the condition in [_lastUnresolved]
     * when it is one the user must be notified about (Error / ServerAckPending /
     * PendingTimeout / AcknowledgementFailed) and clears it on genuine resolution.
     */
    private fun setUi(state: SubscriptionUiState) {
        rememberIfUnresolved(state)
        _uiState.value = state
    }

    private fun rememberIfUnresolved(state: SubscriptionUiState) {
        when (state) {
            is SubscriptionUiState.Error,
            is SubscriptionUiState.ServerAckPending,
            is SubscriptionUiState.PendingTimeout,
            is SubscriptionUiState.AcknowledgementFailed -> {
                _lastUnresolved.value = state
                // Also publish to the process-wide billing handler so the dashboard
                // (which does not share this ViewModel instance) can show a persistent
                // acknowledgement-failure banner (Section 4.2).
                InAppBillingHandler.setAckFailureState(
                    com.celzero.bravedns.iab.AckFailureInfo(
                        title = (state as? SubscriptionUiState.AcknowledgementFailed)?.title
                            ?: (state as? SubscriptionUiState.Error)?.title
                            ?: getApplication<Application>().getString(R.string.purchase_failed),
                        message = (state as? SubscriptionUiState.AcknowledgementFailed)?.message
                            ?: (state as? SubscriptionUiState.Error)?.message
                            ?: (state as? SubscriptionUiState.ServerAckPending)?.message
                            ?: (state as? SubscriptionUiState.PendingTimeout)?.message
                            ?: "",
                        canRetry = (state as? SubscriptionUiState.AcknowledgementFailed)?.canRetry
                            ?: (state as? SubscriptionUiState.Error)?.isRetryable
                            ?: true,
                        refundInitiated = (state as? SubscriptionUiState.AcknowledgementFailed)?.refundInitiated
                            ?: false
                    )
                )
            }
            is SubscriptionUiState.Success,
            is SubscriptionUiState.Ready,
            is SubscriptionUiState.Loading -> {
                _lastUnresolved.value = null
                InAppBillingHandler.setAckFailureState(null)
            }
            else -> { /* keep current value */ }
        }
    }

    // Watchdog job: cancels Loading if billing never responds.
    private var loadingWatchdogJob: Job? = null


    private val isBillingInitializing = AtomicBoolean(false)

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
        if (!isBillingInitializing.compareAndSet(false, true)) {
            Logger.d(LOG_IAB, "$TAG: initializeBilling already in progress, ignoring re-entrant call")
            return
        }
        billingInitCalled = true

        viewModelScope.launch {
            setUi(SubscriptionUiState.Loading)
            startLoadingWatchdog()

            try {
                // surface a dedicated offline state before attempting
                // any network-dependent billing work so the user sees "You're offline".
                if (isOffline()) {
                    cancelLoadingWatchdog()
                    isBillingInitializing.set(false)
                    setUi(SubscriptionUiState.NoInternet())
                    return@launch
                }

                // Check if already subscribed, skipped in extend mode so the user can
                // purchase an additional one-time plan while their current one is active.
                if (!extendMode && InAppBillingHandler.hasValidSubscription()) {
                    cancelLoadingWatchdog()
                    isBillingInitializing.set(false)
                    setUi(SubscriptionUiState.AlreadySubscribed(
                        RpnProxyManager.getRpnProductId() ?: ""
                    ))
                    return@launch
                }

                // Check availability
                val avd = checkAvailability()

                if (_uiState.value !is SubscriptionUiState.Loading) {
                    Logger.d(LOG_IAB, "$TAG: state resolved while checkAvailability was running, bailing out")
                    isBillingInitializing.set(false)
                    return@launch
                }

                if (!avd.first) {
                    cancelLoadingWatchdog()
                    isBillingInitializing.set(false)
                    setUi( SubscriptionUiState.Error(
                        title = "Not Available",
                        message = avd.second,
                        isRetryable = false
                    ))
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
                isBillingInitializing.set(false)
                setUi( SubscriptionUiState.Error(
                    title = "Initialization Failed",
                    message = e.message ?: "Unknown error occurred",
                    isRetryable = true
                ))
            }
        }
    }

    /** Start a watchdog that emits an Error if Loading is not resolved within LOADING_TIMEOUT_MS. */
    private fun startLoadingWatchdog() {
        cancelLoadingWatchdog()
        loadingWatchdogJob = viewModelScope.launch {
            delay(LOADING_TIMEOUT_MS.milliseconds)
            // If we are still in Loading after the timeout, surface an error so the user can retry.
            if (_uiState.value is SubscriptionUiState.Loading) {
                Logger.w(LOG_IAB, "$TAG: Loading watchdog fired, billing took too long to respond")
                isBillingInitializing.set(false)
                setUi( SubscriptionUiState.Error(
                    title = "Connection Timeout",
                    message = "Google Play Billing is not responding. Please try again.",
                    isRetryable = true
                ))
            }
        }
    }

    private fun cancelLoadingWatchdog() {
        loadingWatchdogJob?.cancel()
        loadingWatchdogJob = null
    }

    /**
     * Whether the device currently has internet connectivity.
     * Fails open (returns false) on SystemService errors so a transient error does not
     * block the user from the purchase screen.
     */
    private fun isOffline(): Boolean {
        return try {
            val cm = getApplication<Application>().getSystemService(
                android.content.Context.CONNECTIVITY_SERVICE
            ) as? android.net.ConnectivityManager
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps == null || !caps.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG: connectivity check failed: ${e.message}")
            false
        }
    }

    /**
     * Set product details from billing handler
     */
    fun setProducts(productList: List<ProductDetail>) {
        allProducts = productList
        _products.value = productList

        if (productList.isEmpty()) {
            setUi( SubscriptionUiState.Error(
                title = "No Products Available",
                message = "Unable to load subscription plans",
                isRetryable = true
            ))
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
                setUi( SubscriptionUiState.AlreadySubscribed(
                    RpnProxyManager.getRpnProductId() ?: ""
                ))
            } else {
                // Subscription is in a non-purchasable, non-valid state (e.g. Uninitialized).
                setUi( SubscriptionUiState.Ready(
                    _filteredProducts.value, false, PipKeyManager.getAvailabilityData()
                ))
            }
            return
        }

        setUi(SubscriptionUiState.Ready(_filteredProducts.value, false, PipKeyManager.getAvailabilityData()))
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
            val first = filtered.last()
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
            setUi(SubscriptionUiState.Ready(_filteredProducts.value, false, PipKeyManager.getAvailabilityData()))
        } else {
            setUi(SubscriptionUiState.Error(
                title = "No Products",
                message = "No ${type.name.lowercase()} products available",
                isRetryable = false
            ))
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
        setUi(SubscriptionUiState.Processing("Initializing purchase..."))
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
                    setUi(SubscriptionUiState.Success(
                        productId = RpnProxyManager.getRpnProductId() ?: "",
                        isExtend = true
                    ))
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
                setUi(SubscriptionUiState.Processing("Initializing purchase..."))
            }

            is SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> {
                // Only start polling if the user actually initiated a purchase in this session.
                if (purchaseFlowActive) {
                    setUi(SubscriptionUiState.PendingPurchase())
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
                    setUi(SubscriptionUiState.Success(
                        productId = RpnProxyManager.getRpnProductId() ?: "",
                        isExtend = false
                    ))
                } else {
                    val current = _uiState.value
                    if (billingInitCalled && !extendMode && current is SubscriptionUiState.Loading) {
                        setUi(SubscriptionUiState.AlreadySubscribed(
                            RpnProxyManager.getRpnProductId() ?: ""
                        ))
                    }
                    // If already in Ready/Error/etc., don't disrupt the current UI.
                }
            }

            is SubscriptionStateMachineV2.SubscriptionState.ServerAckPending -> {
                purchaseFlowActive = false
                extendObserverJob?.cancel()
                extendObserverJob = null
                stopPendingPurchasePolling()
                setUi(SubscriptionUiState.ServerAckPending())
            }

            is SubscriptionStateMachineV2.SubscriptionState.Error -> {
                val wasInFlow = purchaseFlowActive
                purchaseFlowActive = false
                extendObserverJob?.cancel()
                extendObserverJob = null
                stopPendingPurchasePolling()
                if (wasInFlow) {
                    // Real purchase flow failed, show an actionable error.
                    setUi(SubscriptionUiState.Error(
                        title = "Subscription Error",
                        message = "An error occurred while processing your payment",
                        isRetryable = true
                    ))
                } else {
                    // handlePurchase drove PurchasePending → Error because Play returned
                    // no purchases (stale DB row, no real purchase). Show the payment
                    // screen so the user can buy instead of seeing a cryptic error.
                    Logger.d(LOG_IAB, "$TAG: Error state without active flow, showing payment screen")
                    val products = _filteredProducts.value.ifEmpty { allProducts }
                    if (products.isNotEmpty()) {
                        setUi(SubscriptionUiState.Ready(
                            products = products,
                            isResubscribe = false,
                            availabilityData = PipKeyManager.getAvailabilityData()
                        ))
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
                    setUi(SubscriptionUiState.Ready(
                        products = products,
                        isResubscribe = true,
                        availabilityData = PipKeyManager.getAvailabilityData()
                    ))
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

            is SubscriptionStateMachineV2.SubscriptionState.Grace,
            is SubscriptionStateMachineV2.SubscriptionState.OnHold,
            is SubscriptionStateMachineV2.SubscriptionState.Paused -> {
                // These are valid subscription sub-states. The user still has access.
                // Keep the current UI state; show availability data so the dashboard
                // reflects the real connection info.
                Logger.d(LOG_IAB, "$TAG: Subscription in sub-state: ${state.name}, keeping current UI")
                if (_uiState.value is SubscriptionUiState.Loading ||
                    _uiState.value is SubscriptionUiState.Processing) {
                    return
                }
                PipKeyManager.getAvailabilityData()?.let { avail ->
                    setUi(avail)
                }
            }
        }
    }

    /**
     * Start polling for pending purchase status. Updates the UI message with time-based
     * escalation and transitions to [SubscriptionUiState.PendingTimeout]
     * when polling times out.
     */
    private fun startPendingPurchasePolling() {
        if (pollingJob != null) return

        pollingStartTime = System.currentTimeMillis()
        setUi(SubscriptionUiState.PendingPurchase(message = pendingEscalationMessage(0L)))

        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val elapsedTime = System.currentTimeMillis() - pollingStartTime

                if (elapsedTime > POLLING_TIMEOUT_MS) {
                    Logger.i(LOG_IAB, "$TAG: Polling timeout reached")
                    stopPendingPurchasePolling()
                    // surface a specific timeout state with a "Check Status"
                    // action instead of dropping straight into ServerAckPending.
                    setUi(SubscriptionUiState.PendingTimeout())
                    break
                }

                // Refresh the escalation message so the user understands the wait.
                setUi(SubscriptionUiState.PendingPurchase(message = pendingEscalationMessage(elapsedTime)))
                Logger.d(LOG_IAB, "$TAG: Polling pending purchase, elapsed: $elapsedTime ms")
                InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                delay(POLLING_INTERVAL_MS.milliseconds)
            }
        }
    }

    /**
     * Returns a time-based escalation message for the pending state.
     */
    private fun pendingEscalationMessage(elapsedMs: Long): String {
        val res = getApplication<Application>().resources
        return when {
            elapsedMs < 30_000L -> res.getString(R.string.pending_stage_initial)
            elapsedMs < 5 * 60 * 1000L -> res.getString(R.string.pending_stage_waiting)
            else -> res.getString(R.string.pending_stage_delayed)
        }
    }

    /**
     * Manually re-check a pending purchase after a timeout. Triggered by
     * the "Check Status" action on the [SubscriptionUiState.PendingTimeout] sheet.
     */
    fun checkPendingStatus() {
        purchaseFlowActive = true
        setUi(SubscriptionUiState.PendingPurchase(
            message = getApplication<Application>().resources.getString(R.string.pending_checking_status)
        ))
        startPendingPurchasePolling()
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
     */
    fun onBillingConnected(isSuccess: Boolean, message: String) {
        if (!isSuccess) {
            cancelLoadingWatchdog()
            Logger.e(LOG_IAB, "$TAG: Billing connection failed: $message")
            isBillingInitializing.set(false)
            setUi(SubscriptionUiState.Error(
                title = "Connection Failed",
                message = message,
                isRetryable = true
            ))
        } else {
            // Ensure the UI is in Loading while we fetch products.
            if (_uiState.value !is SubscriptionUiState.Loading) {
                setUi(SubscriptionUiState.Loading)
            }
            startLoadingWatchdog()
            isBillingInitializing.set(true)

            viewModelScope.launch(Dispatchers.IO) {
                // Re-check subscription state first; if already active, skip product query.
                // Skip this check in extend mode, the user is here to buy additional time
                // while their existing subscription is still active.
                if (!extendMode && InAppBillingHandler.hasValidSubscription()) {
                    cancelLoadingWatchdog()
                    isBillingInitializing.set(false)
                    withContext(Dispatchers.Main) {
                        setUi(SubscriptionUiState.AlreadySubscribed(
                            RpnProxyManager.getRpnProductId()
                        ))
                    }
                    return@launch
                }

                // Fetch existing purchases to sync state machine, then query displayable products.
                // regardless of availability, the previous purchases should be fetched and
                // see if any active purchases are there
                InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))

                // Check availability before querying products.
                val avd = checkAvailability()
                if (!avd.first) {
                    cancelLoadingWatchdog()
                    isBillingInitializing.set(false)
                    withContext(Dispatchers.Main) {
                        setUi(SubscriptionUiState.Error(
                            title = "Not Available",
                            message = avd.second,
                            isRetryable = false
                        ))
                    }
                    return@launch
                }

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
        isBillingInitializing.set(false)
        if (!isSuccess || productList.isEmpty()) {
            setUi(SubscriptionUiState.Error(
                title = "Products Unavailable",
                message = "Unable to load plans. Please try again.",
                isRetryable = true
            ))
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
        isBillingInitializing.set(false)
        initializeBilling()
        // If billing client is NOT ready, signal the Fragment to reconnect.
        if (!InAppBillingHandler.isBillingClientSetup()) {
            Logger.d(LOG_IAB, "$TAG: retry, billing client not ready, requesting reconnect from Fragment")
            _retryConnectionEvent.tryEmit(Unit)
        }
    }

    /**
     * Re-verify Play + server status after an acknowledgement/transaction failure
     * Clears the sticky unresolved record and the purchase-flow flag, then
     * re-queries Google Play so the purchase processors can retry the server ack. The UI
     * is moved to a PendingPurchase "verifying" state so the user always gets feedback
     * (the button is never a silent no-op after a failure).
     */
    fun reverifyAfterFailure() {
        Logger.i(LOG_IAB, "$TAG: reverifyAfterFailure: clearing unresolved + re-querying Play")
        _lastUnresolved.value = null
        purchaseFlowActive = false
        extendObserverJob?.cancel()
        extendObserverJob = null
        stopPendingPurchasePolling()
        setUi(
            SubscriptionUiState.PendingPurchase(
                message = getApplication<Application>().resources.getString(R.string.verifying_with_play_store)
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
        }
    }

    /**
     * Maps a Google Play Billing response code to a user-friendly error and records it as
     * the sticky unresolved condition. Surfaces a full-screen error with a
     * retry action instead of only a transient Toast.
     */
    fun onPlayServicesError(responseCode: Int) {
        val ctx = getApplication<Application>()
        val res = ctx.resources
        val msg = when (responseCode) {
            com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ->
                res.getString(R.string.billing_err_service_unavailable)
            com.android.billingclient.api.BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                res.getString(R.string.billing_err_billing_unavailable)
            com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_TIMEOUT ->
                res.getString(R.string.billing_err_service_timeout)
            com.android.billingclient.api.BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                res.getString(R.string.billing_err_feature_not_supported)
            else -> res.getString(R.string.billing_err_generic_retry)
        }
        Logger.w(LOG_IAB, "$TAG: onPlayServicesError: code=$responseCode, msg=$msg")
        setUi(
            SubscriptionUiState.Error(
                title = res.getString(R.string.billing_play_services_error_title),
                message = msg,
                isRetryable = true
            )
        )
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

    /**
     * A purchase is pending Play verification. [message] carries a time-based escalation
     * string: "Verifying…" → "Still waiting…" → "taking longer…".
     */
    data class PendingPurchase(val message: String = "") : SubscriptionUiState()

    /**
     * Pending-purchase polling timed out without resolution. Offers a
     * "Check Status" action that manually re-queries Play, and "Contact Support".
     */
    data class PendingTimeout(val message: String = "") : SubscriptionUiState()

    /** No network available */
    data class NoInternet(val message: String = "") : SubscriptionUiState()

    data class Success(val productId: String, val isExtend: Boolean = false) : SubscriptionUiState()

    data class Error(
        val title: String,
        val message: String,
        val isRetryable: Boolean
    ) : SubscriptionUiState()

    data class ServerAckPending(val message: String = "") : SubscriptionUiState()

    /**
     * Both acknowledgement systems (our server and Google Play) failed to confirm the
     * purchase. The payment was taken but verification is blocked on both sides. The user
     * must be explicitly notified and offered retry / support / refund
     */
    data class AcknowledgementFailed(
        val title: String,
        val message: String,
        val canRetry: Boolean,
        val refundInitiated: Boolean = false,
        val supportTicketRecommended: Boolean = true
    ) : SubscriptionUiState()

    data class AlreadySubscribed(val productId: String) : SubscriptionUiState()
}
