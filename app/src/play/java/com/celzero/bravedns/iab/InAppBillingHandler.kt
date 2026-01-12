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
package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.app.Activity
import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.rpnproxy.PipKeyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.subscription.SubscriptionStateMachineV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import com.celzero.bravedns.customdownloader.ITcpProxy
import com.celzero.bravedns.service.TcpProxyHelper
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

// ref: github.com/hypersoftdev/inappbilling
object InAppBillingHandler : KoinComponent {

    private lateinit var billingClient: BillingClient
    private var billingListener: BillingListener? = null

    const val TAG = "IABHandler"

    private val persistentState by inject<PersistentState>()

    const val HISTORY_LINK = "https://play.google.com/store/account/orderhistory"
    const val LINK = "https://play.google.com/store/account/subscriptions?sku=$1&package=$2"

    const val STD_PRODUCT_ID = "standard.tier"
    const val ONE_TIME_PRODUCT_ID = "test_product"

    private lateinit var queryUtils: QueryUtils
    private val productDetails: CopyOnWriteArrayList<ProductDetail> = CopyOnWriteArrayList()
    private val storeProductDetails: CopyOnWriteArrayList<QueryProductDetail> =
        CopyOnWriteArrayList()

    // NOTE: Removed purchaseDetails list - using state machine as single source of truth

    val productDetailsLiveData = MutableLiveData<List<ProductDetail>>()
    val purchasesLiveData = MutableLiveData<List<PurchaseDetail>>()
    val transactionErrorLiveData = MutableLiveData<BillingResult>()
    val connectionResultLiveData = MutableLiveData<ConnectionResult>()

    private val subscriptionStateMachine: SubscriptionStateMachineV2 by inject()

    // Structured concurrency with supervisor job for error isolation
    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Connection synchronization
    private val connectionMutex = kotlinx.coroutines.sync.Mutex()

    // State tracking (DO NOT use for business logic - use state machine instead)
    @Volatile
    private var isInitialized = false

    // Result state for the billing client
    enum class Priority(val value: Int) {
        HIGH(0),
        MEDIUM(1),
        LOW(2)
    }

    data class ConnectionResult(val isSuccess: Boolean, val message: String)

    internal data class BestPlan(
        val trialDays: Int,
        val pricingPhase: ProductDetails.PricingPhase?
    )

    enum class RecurringMode {
        FREE,
        DISCOUNTED,
        ORIGINAL
    }

    private fun logd(methodName: String, msg: String) {
        Logger.d(LOG_IAB, "$TAG $methodName: $msg")
    }

    private fun logv(methodName: String, msg: String) {
        Logger.v(LOG_IAB, "$TAG $methodName: $msg")
    }

    private fun loge(methodName: String, msg: String, e: Exception? = null) {
        Logger.e(LOG_IAB, "$TAG $methodName: $msg", e)
    }

    private fun log(methodName: String, msg: String) {
        Logger.i(LOG_IAB, "$TAG $methodName: $msg")
    }

    fun initiate(context: Context, billingListener: BillingListener? = null) {
        val mname = this::initiate.name
        this.billingListener = billingListener

        // Initialize billing client
        setupBillingClient(context)

        // Initialize state machine first (before connection)
        if (!isInitialized) {
            billingScope.launch {
                try {
                    subscriptionStateMachine.initialize()
                    startStateObserver()
                    isInitialized = true
                    logd(mname, "State machine initialized successfully")
                } catch (e: Exception) {
                    loge(mname, "Failed to initialize state machine: ${e.message}", e)
                    // Critical error - notify listener
                    withContext(Dispatchers.Main) {
                        billingListener?.onConnectionResult(false, "State machine initialization failed: ${e.message}")
                    }
                    return@launch
                }
            }
        }

        // Start billing connection
        startConnection { isSuccess, message ->
            if (isSuccess) {
                logd(mname, "Billing connected successfully, fetching initial state")
                // Billing connected - fetch purchases to sync state
                val prodTypes = listOf(ProductType.SUBS, ProductType.INAPP)
                fetchPurchases(prodTypes)
            } else {
                loge(mname, "Billing connection failed: $message")
            }
            billingListener?.onConnectionResult(isSuccess, message)
        }
    }


    fun registerListener(billingListener: BillingListener?) {
        this.billingListener = billingListener
    }

    private fun setupBillingClient(context: Context) {
        if (isBillingClientSetup()) {
            logd(this::setupBillingClient.name, "billing client is already setup, skipping setup")
            return
        }
        val mname = this::setupBillingClient.name
        logv(mname, "setting up billing client")
        billingClient = BillingClient.newBuilder(context)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans()
                    .build()
            )
            .setListener(purchasesUpdatedListener)
            .build()
    }

    fun enableInAppMessaging(activity: Activity) {
        val inAppParams = InAppMessageParams.newBuilder().addInAppMessageCategoryToShow(
            InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL
        ).addAllInAppMessageCategoriesToShow().build()
        billingClient.showInAppMessages(activity, inAppParams) { it ->
            val mname = this::enableInAppMessaging.name
            logd(mname, "in-app messaging result: ${it.responseCode}")
            if (it.responseCode == InAppMessageResult.InAppMessageResponseCode.NO_ACTION_NEEDED) {
                logd(mname, "enableInAppMessaging: no action needed")
            } else {
                logd(TAG, "enableInAppMessaging: subs status update, fetching purchases")
                fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
            }
        }
    }

    fun isBillingClientSetup(): Boolean {
        val mname = this::isBillingClientSetup.name
        logv(mname, "checking if billing client is setup")
        if (!::billingClient.isInitialized) { // Corrected initialization check
            logd(mname, "billing client is not initialized")
            return false
        }
        val isReady = billingClient.isReady
        logd(mname, "isInitialized: true, isReady: $isReady")
        return isReady
    }

    private fun onConnectionResultMain(
        callback: (isSuccess: Boolean, message: String) -> Unit,
        isSuccess: Boolean,
        message: String
    ) {
        val res = ConnectionResult(isSuccess, message)
        connectionResultLiveData.postValue(res)
        CoroutineScope(Dispatchers.Main).launch {
            callback.invoke(isSuccess, message)
        }
    }

    fun isListenerRegistered(l: BillingListener?): Boolean {
        val mname = this::isListenerRegistered.name
        logv(mname, "checking if listener is registered")
        // compares reference equality when comparing objects
        val isRegistered = billingListener == l
        log(mname, "isRegistered: $isRegistered")
        return isRegistered
    }

    private fun startConnection(callback: (isSuccess: Boolean, message: String) -> Unit) {
        val mname = this::startConnection.name
        logv(mname, "Starting billing connection")

        // Use coroutine for mutex (thread-safe connection attempt)
        billingScope.launch {
            if (!connectionMutex.tryLock()) {
                logd(mname, "Connection attempt already in progress")
                withContext(Dispatchers.Main) {
                    callback.invoke(false, "Connection already in progress")
                }
                return@launch
            }

            try {
                // Check if already connected
                if (::billingClient.isInitialized && billingClient.isReady) {
                    logd(mname, "Billing already connected")
                    withContext(Dispatchers.Main) {
                        callback.invoke(true, "Already connected")
                    }
                    return@launch
                }

                // Start connection
                withContext(Dispatchers.Main) {
                    billingClient.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(billingResult: BillingResult) {
                            val isOk = BillingResponse(billingResult.responseCode).isOk

                            if (isOk) {
                                logd(mname, "Billing connected successfully")
                                queryUtils = QueryUtils(billingClient)
                                queryBillingConfig()
                                fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                            } else {
                                loge(mname, "Billing connection failed: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                            }

                            callback.invoke(isOk, if (isOk) "Connected" else billingResult.debugMessage)
                            connectionMutex.unlock()
                        }

                        override fun onBillingServiceDisconnected() {
                            log(mname, "Billing service disconnected")

                            // Notify state machine of disconnection
                            billingScope.launch {
                                try {
                                    subscriptionStateMachine.systemCheck()
                                } catch (e: Exception) {
                                    loge(mname, "Error during system check on disconnect: ${e.message}", e)
                                }
                            }

                            callback.invoke(false, "Service disconnected")
                            if (connectionMutex.isLocked) {
                                connectionMutex.unlock()
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                loge(mname, "Error starting connection: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback.invoke(false, "Connection error: ${e.message}")
                }
                if (connectionMutex.isLocked) {
                    connectionMutex.unlock()
                }
            }
        }
    }

    private val purchasesUpdatedListener: PurchasesUpdatedListener =
        PurchasesUpdatedListener { billingResult, purchasesList ->
            val mname = this::purchasesUpdatedListener.name
            logd(mname, "Purchase update: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")

            val response = BillingResponse(billingResult.responseCode)

            // Use structured concurrency for state machine updates
            billingScope.launch {
                try {
                    when {
                        response.isOk -> {
                            log(mname, "Purchase successful, processing ${purchasesList?.size ?: 0} items")
                            handlePurchase(purchasesList)
                        }

                        response.isAlreadyOwned -> {
                            log(mname, "Item already owned - restoring subscription")
                            purchasesList?.forEach { purchase ->
                                try {
                                    val purchaseDetail = createPurchaseDetailFromPurchase(purchase)
                                    subscriptionStateMachine.restoreSubscription(purchaseDetail)
                                } catch (e: Exception) {
                                    loge(mname, "Error restoring purchase: ${e.message}", e)
                                }
                            }
                        }

                        response.isUserCancelled -> {
                            log(mname, "User cancelled purchase flow")
                            // Post to LiveData so UI can dismiss bottom sheet
                            transactionErrorLiveData.postValue(billingResult)
                            try {
                                subscriptionStateMachine.userCancelled()
                            } catch (e: Exception) {
                                loge(mname, "Error notifying cancellation: ${e.message}", e)
                            }
                        }

                        response.isTerribleFailure || response.isNonrecoverableError -> {
                            loge(mname, "Fatal billing error: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                            // Post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            subscriptionStateMachine.purchaseFailed(
                                "Fatal error: ${billingResult.debugMessage}",
                                billingResult
                            )
                        }

                        response.isRecoverableError -> {
                            log(mname, "Recoverable billing error: ${billingResult.debugMessage}")
                            // Post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            subscriptionStateMachine.purchaseFailed(
                                "Recoverable error: ${billingResult.debugMessage}",
                                billingResult
                            )
                        }

                        else -> {
                            loge(mname, "Unknown billing error: ${billingResult.responseCode}")
                            // Post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            subscriptionStateMachine.purchaseFailed(
                                "Unknown error: ${billingResult.debugMessage}",
                                billingResult
                            )
                        }
                    }
                } catch (e: Exception) {
                    loge(mname, "Critical error in purchase listener: ${e.message}", e)
                    try {
                        subscriptionStateMachine.purchaseFailed("Critical error: ${e.message}", billingResult)
                    } catch (smError: Exception) {
                        loge(mname, "Failed to notify state machine: ${smError.message}", smError)
                    }
                }
            }
        }

    private suspend fun handlePurchase(purchasesList: List<Purchase>?) {
        val mname = "handlePurchase"
        if (purchasesList == null || purchasesList.isEmpty()) {
            log(mname, "Purchases list is empty - checking subscription state")
            try {
                val currentState = subscriptionStateMachine.getCurrentState()
                if (currentState is SubscriptionStateMachineV2.SubscriptionState.Active) {
                    val billingExpiry = subscriptionStateMachine.getSubscriptionData()?.subscriptionStatus?.billingExpiry
                    if (billingExpiry == null) {
                        loge(mname, "No billing expiry found in active state - transitioning to expired")
                        subscriptionStateMachine.subscriptionExpired()
                        return
                    }

                    if (billingExpiry < System.currentTimeMillis()) {
                        log(mname, "Billing expired - transitioning to expired state")
                        subscriptionStateMachine.subscriptionExpired()
                    } else {
                        log(mname, "No purchases found but billing valid - user cancelled subscription")
                        RpnProxyManager.handleUserCancellation()
                    }
                } else {
                    logd(mname, "No active subscription state - no action needed, current state: ${currentState.name}")
                }
            } catch (e: Exception) {
                loge(mname, "Error handling empty purchase list: ${e.message}", e)
            }
            return
        }

        // Get current state for validation
        val currentState = subscriptionStateMachine.getCurrentState()
        logd(mname, "Processing ${purchasesList.size} purchases in state: ${currentState.name}")

        // Check if we can process in current state
        if (currentState is SubscriptionStateMachineV2.SubscriptionState.Error) {
            loge(mname, "Cannot process purchases in error state - attempting system check")
            try {
                subscriptionStateMachine.systemCheck()
            } catch (e: Exception) {
                loge(mname, "System check failed: ${e.message}", e)
            }
            return
        }

        // Filter duplicates
        val uniquePurchases = purchasesList.distinctBy { it.purchaseToken }
        if (uniquePurchases.size != purchasesList.size) {
            logd(mname, "Filtered ${purchasesList.size - uniquePurchases.size} duplicate purchases")
        }

        // Process each purchase through state machine
        uniquePurchases.forEach { purchase ->
            try {
                processSinglePurchase(purchase)
            } catch (e: Exception) {
                loge(mname, "Error processing purchase ${purchase.purchaseToken}: ${e.message}", e)
            }
        }
    }

    private suspend fun processSinglePurchase(purchase: Purchase) {
        val mname = "processSinglePurchase"

        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                logd(mname, "Processing purchased state for token: ${purchase.purchaseToken}")

                if (isPurchaseStateCompleted(purchase)) {
                    logd(mname, "Purchase acknowledged - notifying state machine")
                    try {
                        val purchaseDetail = createPurchaseDetailFromPurchase(purchase)
                        subscriptionStateMachine.paymentSuccessful(purchaseDetail)
                    } catch (e: Exception) {
                        loge(mname, "Error processing acknowledged purchase: ${e.message}", e)
                    }
                } else {
                    logd(mname, "Purchase needs acknowledgement")
                    try {
                        val purchaseDetail = createPurchaseDetailFromPurchase(purchase)
                        subscriptionStateMachine.completePurchase(purchaseDetail)
                        subscriptionStateMachine.completePurchase(purchaseDetail)
                    } catch (e: Exception) {
                        loge(mname, "Error handling unacknowledged purchase: ${e.message}", e)
                    }
                }
            }

            Purchase.PurchaseState.PENDING -> {
                logd(mname, "Purchase pending for token: ${purchase.purchaseToken}")
                try {
                    val purchaseDetail = createPurchaseDetailFromPurchase(purchase)
                    subscriptionStateMachine.completePurchase(purchaseDetail)
                } catch (e: Exception) {
                    loge(mname, "Error handling pending purchase: ${e.message}", e)
                }
            }

            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                loge(mname, "Purchase state unspecified for token: ${purchase.purchaseToken}")
                try {
                    subscriptionStateMachine.purchaseFailed("Purchase state unspecified", null)
                } catch (e: Exception) {
                    loge(mname, "Error notifying unspecified state: ${e.message}", e)
                }
            }

            else -> {
                loge(mname, "Unknown purchase state: ${purchase.purchaseState}")
                try {
                    subscriptionStateMachine.purchaseFailed(
                        "Unknown purchase state: ${purchase.purchaseState}",
                        null
                    )
                } catch (e: Exception) {
                    loge(mname, "Error notifying unknown state: ${e.message}", e)
                }
            }
        }
    }

    private fun createPurchaseDetailFromPurchase(purchase: Purchase): PurchaseDetail {
        return try {
            // Find the product details for this purchase
            val productDetails = storeProductDetails.find {
                it.productDetail.productId == purchase.products.firstOrNull()
            }

            val productDetail = productDetails?.productDetail
            val offerDetails = productDetails?.offerDetails

            // Calculate expiry time more accurately
            val expiryTime = calculateExpiryTime(purchase, offerDetails)

            // Validate account ID
            val accountId = purchase.accountIdentifiers?.obfuscatedAccountId ?: ""
            if (accountId.isEmpty()) {
                loge(
                    "createPurchaseDetailFromPurchase",
                    "Account ID is empty for purchase: ${purchase.purchaseToken}"
                )
            }

            PurchaseDetail(
                productId = purchase.products.firstOrNull() ?: "",
                planId = offerDetails?.basePlanId ?: "",
                productTitle = productDetail?.productTitle ?: "",
                planTitle = offerDetails?.let { queryUtils.getPlanTitle(it) } ?: "",
                state = purchase.purchaseState,
                purchaseToken = purchase.purchaseToken,
                productType = purchase.products.firstOrNull()?.let {
                    productDetails?.productDetail?.productType
                        ?: ProductType.SUBS
                } ?: ProductType.SUBS,
                purchaseTime = purchase.purchaseTime.toFormattedDate(),
                purchaseTimeMillis = purchase.purchaseTime,
                isAutoRenewing = purchase.isAutoRenewing,
                accountId = accountId,
                payload = purchase.developerPayload,
                expiryTime = expiryTime,
                status = when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
                    Purchase.PurchaseState.PENDING -> SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
                    else -> SubscriptionStatus.SubscriptionState.STATE_UNKNOWN.id
                }
            )
        } catch (e: Exception) {
            loge(
                "createPurchaseDetailFromPurchase",
                "Error creating purchase detail: ${e.message}",
                e
            )
            // Return a minimal valid PurchaseDetail
            PurchaseDetail(
                productId = purchase.products.firstOrNull() ?: "",
                planId = "",
                productTitle = "",
                planTitle = "",
                state = purchase.purchaseState,
                purchaseToken = purchase.purchaseToken,
                productType = BillingClient.ProductType.SUBS,
                purchaseTime = purchase.purchaseTime.toFormattedDate(),
                purchaseTimeMillis = purchase.purchaseTime,
                isAutoRenewing = purchase.isAutoRenewing,
                accountId = purchase.accountIdentifiers?.obfuscatedAccountId ?: "",
                payload = purchase.developerPayload,
                expiryTime = 0L,
                status = SubscriptionStatus.SubscriptionState.STATE_UNKNOWN.id
            )
        }
    }



    private fun calculateExpiryTime(purchase: Purchase, offerDetails: ProductDetails.SubscriptionOfferDetails?): Long {
        return if (offerDetails != null) {
            val billingPeriod = offerDetails.pricingPhases.pricingPhaseList.lastOrNull()?.billingPeriod ?: "P1M"
            val billingDays = QueryUtils.getBillingPeriodDays(billingPeriod)
            purchase.purchaseTime + (billingDays * 24 * 60 * 60 * 1000L)
        } else {
            // Fallback for cases where offer details are not available
            purchase.purchaseTime + (30L * 24 * 60 * 60 * 1000L) // 30 days
        }
    }

    private fun isTestPurchase(purchase: Purchase?): Boolean {
        val mname = this::isTestPurchase.name
        if (purchase == null) return false

        val orderId = purchase.orderId
        if (orderId.isNullOrEmpty()) {
            logd(mname, "purchase orderId is null or empty")
            return false
        }
        // check if the purchase is a test purchase by looking for the test product ID
        // sample: test purchase orderId = "GPA.1234-5678-9012-TEST"
        //         real purchase orderId = "GPA.1234-5678-9012-34567"
        val isTest = orderId.contains("TEST")
        logv(mname, "isTestPurchase: $isTest for orderId: ${purchase.orderId}")
        return isTest
    }

    private fun isPurchaseStateCompleted(purchase: Purchase?): Boolean {
        val mname = this::isPurchaseStateCompleted.name
        if (purchase?.purchaseState == null) {
            loge(mname, "purchase or purchase state is null, $purchase")
            return false
        }

        val isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        val isAcknowledged = purchase.isAcknowledged

        // For subscriptions, check auto-renewal; for one-time purchases, don't require auto-renewal
        // treat empty product as subscription
        purchase.products.forEach {
            logv(mname, "Product in purchase: $it")
        }
        val isSubs = getPurchaseType(purchase) == ProductType.SUBS
        val isOneTime = getPurchaseType(purchase) == ProductType.INAPP
        //isSubs = purchase.products.any { if (productDetails.isEmpty()) { true } else productDetails.find { pd -> pd.productId == it }?.productType == ProductType.SUBS }
        log(mname, "isPurchaseStateCompleted: isSubs?$isSubs, isOneTime?$isOneTime, isPurchased?$isPurchased, isAcknowledged?$isAcknowledged, isAutoRenewing?${purchase.isAutoRenewing}")
        return if (isSubs) {
            isPurchased && purchase.isAutoRenewing && isAcknowledged
        } else if (isOneTime) {
            isPurchased && isAcknowledged
        } else {
            false
        }
    }

    fun getPurchaseType(purchase: Purchase): String {
        val pId = purchase.products.firstOrNull() ?: return "UNKNOWN"

        return when {
            STD_PRODUCT_ID.contains(pId) -> ProductType.SUBS
            ONE_TIME_PRODUCT_ID.contains(pId) -> ProductType.INAPP
            else -> "UNKNOWN"
        }
    }

    private fun Long.toFormattedDate(
        pattern: String = "MMM dd, yyyy HH:mm:ss",
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = SimpleDateFormat(pattern, locale)
        return formatter.format(Date(this))
    }

    fun fetchPurchases(productType: List<String>) {
        billingScope.launch {
            val mname = "fetchPurchases"
            logv(mname, "Fetching purchases for types: $productType")
            // Determine product types to be fetched
            val hasInApp = productType.any { it == ProductType.INAPP }
            val hasSubs = productType.any { it == ProductType.SUBS }
            val hasBoth = productType.any { it == ProductType.INAPP } &&
                    productType.any { it == ProductType.SUBS }

            // Query for product types to be fetched
            when {
                hasBoth -> queryPurchases(ProductType.INAPP, true)
                hasInApp -> queryPurchases(ProductType.INAPP, false)
                hasSubs -> queryPurchases(ProductType.SUBS, false)
                else -> {
                    loge(mname, "No valid product types provided for fetching purchases")
                    return@launch
                }
            }
            logv(mname, "Purchases fetch complete")
        }
    }

    private fun queryPurchases(productType: String, hasBoth: Boolean) {
        val mname = this::queryPurchases.name
        log(mname, "Querying purchases for type: $productType, hasBoth: $hasBoth")

        val queryPurchasesParams = QueryPurchasesParams.newBuilder().setProductType(productType).build()

        billingClient.queryPurchasesAsync(queryPurchasesParams) { billingResult, purchases ->
            log(mname, "Query result for $productType: ${billingResult.responseCode}, purchases: ${purchases.size}")

            if (BillingResponse(billingResult.responseCode).isOk) {
                billingScope.launch {
                    try {
                        logv(mname, "Processing ${purchases.size} purchases")
                        handlePurchase(purchases)
                    } catch (e: Exception) {
                        loge(mname, "Error processing purchases: ${e.message}", e)
                    }
                }
            } else {
                loge(mname, "Failed to query purchases for $productType: ${billingResult.responseCode}")
            }

            // Query SUBS if we were querying INAPP and hasBoth is true
            if (productType == ProductType.INAPP && hasBoth) {
                queryPurchases(ProductType.SUBS, false)
            }
        }
    }

    suspend fun queryProductDetailsWithTimeout(timeout: Long = 5000) {
        val mname = this::queryProductDetailsWithTimeout.name
        logv(mname, "Querying product details with timeout: ${timeout}ms")

        if (storeProductDetails.isNotEmpty() && productDetails.isNotEmpty()) {
            logd(mname, "Product details already cached, skipping query")
            productDetailsLiveData.postValue(productDetails)
            return
        }

        val result = withTimeoutOrNull(timeout) {
            queryProductDetails()
        }

        if (result == null) {
            loge(mname, "Product details query timed out after ${timeout}ms")
        }
    }

    private fun queryProductDetails() {
        val mname = this::queryProductDetails.name
        // clear the lists before querying for new product details
        storeProductDetails.clear()
        productDetails.clear()
        val productListParams = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(ONE_TIME_PRODUCT_ID)
                .setProductType(ProductType.INAPP)
                .build()
        )
        logd(mname, "query product params, size: ${productListParams.size}")
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productListParams)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            val productDetailsList = queryProductDetailsResult.productDetailsList
            logd(mname, "result: ${billingResult.responseCode}, ${billingResult.debugMessage}")
            logv(mname, "product details: ${productDetailsList.size}")
            if (billingResult.responseCode == BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                processProductList(productDetailsList)
            } else {
                loge(mname, "Failed to query product details: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                billingListener?.productResult(false, emptyList())
            }
        }

        // query SUBS product details
        querySubsProductDetails()
    }

    private fun querySubsProductDetails() {
        val mname = this::querySubsProductDetails.name

        val subsParams = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(STD_PRODUCT_ID)
                .setProductType(ProductType.SUBS)
                .build()
        )

        logd(mname, "query product params, size: ${subsParams.size}")
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(subsParams)
            .build()

        billingClient.queryProductDetailsAsync(params) { br, result ->
            if (br.responseCode == BillingClient.BillingResponseCode.OK) {
                processProductList(result.productDetailsList)
            } else {
                loge(mname, "SUBS query failed: ${br.responseCode}, ${br.debugMessage}")
            }

            // Final callback after all products are processed
            productDetailsLiveData.postValue(productDetails)
            billingListener?.productResult(productDetails.isNotEmpty(), productDetails)
        }
    }


    private fun processProductList(productDetailsList: List<ProductDetails>) {
        val mname = this::processProductList.name
        val queryProductDetail = arrayListOf<QueryProductDetail>()
        logd(mname, "product details size: ${productDetailsList.size}, $productDetailsList")
        productDetailsList.forEach { pd ->
            logd(mname, "product details: $pd")

            when (pd.productType) {
                ProductType.INAPP -> {
                    val offers = pd.oneTimePurchaseOfferDetailsList.orEmpty()
                    if (offers.isEmpty()) {
                        loge(mname, "INAPP product has no one-time offers: ${pd.productId}")
                        return@forEach
                    }

                    offers.forEachIndexed { index, offer ->

                        val billingPeriod = getOneTimeProductBillingPeriod(offer)
                        val planTitle = QueryUtils.getPlanTitle(billingPeriod)
                        val planId = offer.purchaseOptionId ?: offer.offerId
                        ?: "one_time_${pd.productId}_$index"

                        val pricingPhase = PricingPhase(
                            planTitle = planTitle,
                            recurringMode = RecurringMode.ORIGINAL,
                            price = offer.formattedPrice.removeSuffix(".00"),
                            currencyCode = offer.priceCurrencyCode,
                            billingCycleCount = 0,
                            billingPeriod = billingPeriod,
                            priceAmountMicros = offer.priceAmountMicros,
                            freeTrialPeriod = 0
                        )

                        val productDetail = ProductDetail(
                            productId = pd.productId,
                            planId = planId,
                            productTitle = planTitle,
                            productType = ProductType.INAPP,
                            pricingDetails = listOf(pricingPhase)
                        )
                        this.productDetails.add(productDetail)
                        queryProductDetail.add(QueryProductDetail(productDetail, pd, null, offer))
                    }
                }

                ProductType.SUBS -> {
                    pd.subscriptionOfferDetails?.let { offersList ->
                        log(mname, "subs; offersList: ${offersList.size}")
                        offersList.forEach tag@{ offer ->
                            log(
                                mname,
                                "offer: ${offer.basePlanId}, ${offer.offerId}, ${offer.pricingPhases}"
                            )
                            val isExist =
                                this.productDetails.any { it.productId == pd.productId && it.planId == offer.basePlanId }
                            val isExistInStore =
                                storeProductDetails.any { it.productDetail.productId == pd.productId && it.productDetail.planId == offer.basePlanId }
                            logd(
                                mname,
                                "exist? $isExist, $isExistInStore, for $pd, ${offer.basePlanId}, ${pd.productId}"
                            )
                            if (isExist && isExistInStore) {
                                logd(
                                    mname,
                                    "exist: ${storeProductDetails.size}, ${this.productDetails.size} $pd, ${offer.basePlanId}, ${pd.productId}"
                                )
                                loge(mname, "product already exists, skipping")
                                return@tag
                            }

                            val pricingPhaseList = arrayListOf<PricingPhase>()

                            offer.pricingPhases.pricingPhaseList.forEach { pricingPhaseItem ->
                                val pricingPhase = PricingPhase()
                                if (pricingPhaseItem.formattedPrice.equals(
                                        "Free",
                                        ignoreCase = true
                                    )
                                ) {
                                    pricingPhase.recurringMode = RecurringMode.FREE
                                    pricingPhase.freeTrialPeriod = queryUtils.getTrialDay(offer)
                                    pricingPhase.billingCycleCount = 0
                                } else if (pricingPhaseItem.recurrenceMode == 1) {
                                    pricingPhase.recurringMode = RecurringMode.ORIGINAL
                                    pricingPhase.freeTrialPeriod = 0
                                    pricingPhase.billingCycleCount = 0
                                } else if (pricingPhaseItem.recurrenceMode == 2) {
                                    pricingPhase.recurringMode = RecurringMode.DISCOUNTED
                                    pricingPhase.freeTrialPeriod = 0
                                    pricingPhase.billingCycleCount =
                                        pricingPhaseItem.billingCycleCount
                                }
                                pricingPhase.planTitle =
                                    QueryUtils.getPlanTitle(pricingPhaseItem.billingPeriod)
                                pricingPhase.currencyCode = pricingPhaseItem.priceCurrencyCode
                                pricingPhase.billingPeriod = pricingPhaseItem.billingPeriod
                                pricingPhase.price =
                                    pricingPhaseItem.formattedPrice.removeSuffix(".00")
                                pricingPhase.priceAmountMicros = pricingPhaseItem.priceAmountMicros
                                pricingPhaseList.add(pricingPhase)
                            }

                            val productDetail = ProductDetail().apply {
                                productId = pd.productId
                                planId = offer.basePlanId
                                productTitle = pd.title
                                productType = ProductType.SUBS
                                pricingDetails = pricingPhaseList
                            }
                            if (!isExist) {
                                this.productDetails.add(productDetail)
                            }
                            if (!isExistInStore) {
                                queryProductDetail.add(QueryProductDetail(productDetail, pd, offer, null))
                            }
                            logd(
                                mname,
                                "product added: ${productDetail.productId}, ${productDetail.planId}, ${productDetail.productTitle}, ${productDetail.pricingDetails.map { it.price }}"
                            )
                        }
                    }
                }
            }
        }

        storeProductDetails.addAll(queryProductDetail)
        log(mname, "Processed product details list: ${storeProductDetails.size} items")

        storeProductDetails.forEach {
            log(mname, "storeProductDetails item: ${it.productDetail.productId}, ${it.productDetail.planId}, ${it.productDetail.pricingDetails}" )
        }

        productDetails.forEach {
            log(mname, "productDetails item: ${it.productId}, ${it.planId}, ${it.pricingDetails}" )
        }

        // remove duplicates from storeProductDetails and productDetails
        val s = storeProductDetails.distinctBy { it.productDetail.planId }
        val p = productDetails.distinctBy { it.planId }

        storeProductDetails.clear()
        storeProductDetails.addAll(s)
        productDetails.clear()
        productDetails.addAll(p)

        logd(
            mname,
            "final storeProductDetails: ${storeProductDetails.size}, productDetails: ${productDetails.size}"
        )

        productDetailsLiveData.postValue(productDetails)

        if (billingListener == null) {
            logv(mname, "billing listener is null")
            return
        }

        if (productDetails.isEmpty()) {
            log(mname, "no product details found")
            billingListener?.productResult(false, productDetails)
        } else {
            log(mname, "product details found: $productDetails")
            billingListener?.productResult(true, productDetails)
        }
    }

    private fun getOneTimeProductBillingPeriod(offer: ProductDetails.OneTimePurchaseOfferDetails): String {
        // One-time purchases do not have a billing period like subscriptions
        if (offer.purchaseOptionId == "legacy-base") {
            return "P2Y"
        } else if (offer.purchaseOptionId == "legacy-max") {
            return "P5Y"
        } else {
            return "P2Y" // Default to 2 years if unknown
        }
    }

    suspend fun purchaseSubs(
        activity: Activity,
        productId: String,
        planId: String
    ) {
        val mname = this::purchaseSubs.name

        // Check if we can make a purchase through state machine
        if (!subscriptionStateMachine.canMakePurchase()) {
            val currentState = subscriptionStateMachine.getCurrentState()
            loge(mname, "Cannot make purchase - current state: ${currentState.name}")

            loge(mname, "Cannot make purchase in current state: ${currentState.name}")
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        // Notify state machine about purchase initiation
        try {
            subscriptionStateMachine.startPurchase()
        } catch (e: Exception) {
            loge(mname, "Error starting purchase in state machine: ${e.message}", e)
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        log(mname, "Looking for product: $productId, plan: $planId")
        val queryProductDetail = storeProductDetails.find {
            it.productDetail.productId == productId &&
            it.productDetail.planId == planId &&
            it.productDetail.productType == ProductType.SUBS
        }

        if (queryProductDetail == null) {
            val errorMsg = "No product details found for productId: $productId, planId: $planId"
            loge(mname, errorMsg)

            try {
                subscriptionStateMachine.purchaseFailed(errorMsg, null)
            } catch (e: Exception) {
                loge(mname, "Error notifying state machine: ${e.message}", e)
            }

            billingListener?.purchasesResult(false, emptyList())
            return
        }

        try {
            launchFlow(
                activity = activity,
                queryProductDetail.productDetails,
                offerToken = queryProductDetail.offerDetails?.offerToken
            )
        } catch (e: Exception) {
            val errorMsg = "Failed to launch purchase flow: ${e.message}"
            loge(mname, errorMsg, e)

            try {
                subscriptionStateMachine.purchaseFailed(errorMsg, null)
            } catch (stateMachineError: Exception) {
                loge(mname, "Error notifying state machine: ${stateMachineError.message}", stateMachineError)
            }

            billingListener?.purchasesResult(false, emptyList())
        }
    }

    suspend fun purchaseOneTime(activity: Activity, productId: String, planId: String) {
        val mname = this::purchaseOneTime.name

        log(mname, "Looking for one-time product: $productId, plan: $planId")

        // Find the INAPP (one-time) product in your cached list
        val queryProductDetail = storeProductDetails.find {
            it.productDetail.productId == productId &&
                    it.productDetail.productType == ProductType.INAPP
        }

        if (queryProductDetail == null) {
            val errorMsg = "No one-time product details found for productId: $productId, planId: $planId"
            loge(mname, errorMsg)

            // No subscription state machine here – just notify listener
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        try {
            val offerToken = queryProductDetail.oneTimeOfferDetails?.offerToken

            launchFlow(
                activity = activity,
                pds = queryProductDetail.productDetails,
                offerToken = offerToken
            )
            logv(mname, "One-time purchase flow launched for productId: $productId, plan: $planId, offerToken: $offerToken")
        } catch (e: Exception) {
            val errorMsg = "Failed to launch one-time purchase flow: ${e.message}"
            loge(mname, errorMsg, e)

            billingListener?.purchasesResult(false, emptyList())
        }
    }

    private suspend fun launchFlow(
        activity: Activity,
        pds: ProductDetails?,
        offerToken: String?
    ) {
        val mname = this::launchFlow.name

        if (pds == null) {
            loge(mname, "No product details available, cannot launch purchase flow")
            try {
                subscriptionStateMachine.purchaseFailed("No product details found", null)
            } catch (e: Exception) {
                loge(mname, "Error notifying state machine: ${e.message}", e)
            }
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        logd(mname, "Launching purchase flow for: ${pds.title}, ${pds.productId}, offerToken: $offerToken, ${pds.productType}, ${pds.oneTimePurchaseOfferDetailsList}")

        val paramsList = when (offerToken.isNullOrEmpty()) {
            true -> listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pds).build()
            )
            false -> listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pds).setOfferToken(offerToken).build()
            )
        }

        val accountId = getObfuscatedAccountId(activity.applicationContext)
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .setObfuscatedAccountId(accountId)
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, flowParams)
        val isSuccess = billingResult.responseCode == BillingResponseCode.OK

        billingListener?.purchasesResult(isSuccess, emptyList())

        if (!isSuccess) {
            loge(mname, "Failed to launch billing flow: ${billingResult.responseCode}")
            transactionErrorLiveData.postValue(billingResult)
        }
    }

    suspend fun getObfuscatedAccountId(context: Context): String {
        // consider token as obfuscated account id
        return PipKeyManager.getToken(context)
    }

    // Query user's billing configuration using BillingClient v7+ API
    fun queryBillingConfig() {
        val mname = this::queryBillingConfig.name
        val getBillingConfigParams = GetBillingConfigParams.newBuilder().build()
        billingClient.getBillingConfigAsync(getBillingConfigParams) { billingResult, billingConfig ->
            if (billingResult.responseCode == BillingResponseCode.OK
                && billingConfig != null
            ) {
                val countryCode = billingConfig.countryCode
                // TODO: Handle country code, see if we need to use it
                log(mname, "BillingConfig country code: $countryCode")
            } else {
                // TODO: Handle errors
                log(mname, "err in billing config: ${billingResult.debugMessage}")
            }
        }
    }

    private fun startStateObserver() {
        billingScope.launch {
            try {
                subscriptionStateMachine.currentState.collect { state ->
                    logd("StateObserver", "Subscription state changed to: ${state.name}")
                    handleStateChange(state)
                    updateUIForState(state)
                }
            } catch (e: Exception) {
                loge("StateObserver", "Critical error in state observer: ${e.message}", e)
                // Try to recover by doing a system check
                try {
                    subscriptionStateMachine.systemCheck()
                } catch (recoveryError: Exception) {
                    loge("StateObserver", "Failed system check during recovery: ${recoveryError.message}", recoveryError)
                }
            }
        }
    }

    private suspend fun updateUIForState(state: SubscriptionStateMachineV2.SubscriptionState) {
        withContext(Dispatchers.Main) {
            // Get current subscription data from state machine
            val subscriptionData = subscriptionStateMachine.getSubscriptionData()
            subscriptionData?.purchaseDetail?.let { purchaseDetail ->
                // Update LiveData with current purchase
                purchasesLiveData.postValue(listOf(purchaseDetail))
            }
        }
    }

    private fun handleStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Error -> {
                loge("handleStateChange", "Subscription state machine entered error state")
                // Could trigger UI update or error handling
            }

            is SubscriptionStateMachineV2.SubscriptionState.Active -> {
                logd("handleStateChange", "Subscription is now active")
                // Could trigger UI update or feature enablement
            }

            is SubscriptionStateMachineV2.SubscriptionState.Revoked -> {
                loge("handleStateChange", "Subscription has been revoked")
                // Handle revocation scenario
            }

            else -> {
                logd("handleStateChange", "State changed to: ${state.name}")
            }
        }
    }

    fun canMakePurchase(): Boolean {
        return subscriptionStateMachine.canMakePurchase()
    }

    fun hasValidSubscription(): Boolean {
        return RpnProxyManager.hasValidSubscription()
    }

    fun getConnectionStatusWithStateMachine(): String {
        val connectionStatus = when {
            !::billingClient.isInitialized -> "Not initialized"
            !billingClient.isReady -> "Not ready"
            else -> "Ready"
        }

        val subscriptionState = subscriptionStateMachine.getCurrentState()
        val statistics = subscriptionStateMachine.getStatistics()
        val subscriptionData = subscriptionStateMachine.getSubscriptionData()

        return """
            Billing Client: $connectionStatus
            Subscription State: ${subscriptionState.name}
            Can Make Purchase: ${subscriptionStateMachine.canMakePurchase()}
            Has Valid Subscription: ${subscriptionStateMachine.hasValidSubscription()}
            Subscription Active: ${subscriptionStateMachine.isSubscriptionActive()}
            Total Transitions: ${statistics.totalTransitions}
            Success Rate: ${String.format(Locale.getDefault(), "%.2f", statistics.successRate * 100)}%
            Failed Transitions: ${statistics.failedTransitions}
            Current Product: ${subscriptionData?.subscriptionStatus?.productId ?: "None"}
            Last Updated: ${subscriptionData?.lastUpdated?.let { Date(it) } ?: "Never"}
        """.trimIndent()
    }

    /*fun getDetailedErrorInfo(): String? {
        return if (isStateMachineInitialized.get()) {
            val statistics = subscriptionStateMachine.getStatistics()
            //val history = subscriptionStateMachine.getTransitionHistory()
            val failedTransitions = history.filter { !it.success }

            if (failedTransitions.isNotEmpty()) {
                val lastError = failedTransitions.lastOrNull()
                """
                    Last Error: ${lastError?.error}
                    Failed Transitions: ${statistics.failedTransitions}
                    Success Rate: ${String.format("%.2f", statistics.successRate * 100)}%
                    Recent Failed Events: ${failedTransitions.takeLast(3).map { it.event.name }}
                """.trimIndent()
            } else {
                "No errors found"
            }
        } else {
            "State machine not initialized"
        }
    }*/

    fun endConnection() {
        val mname = this::endConnection.name
        logd(mname, "ending billing connection")
        if (::billingClient.isInitialized && billingClient.isReady) {
            billingClient.endConnection()
            logd(mname, "billing connection ended")
        } else {
            logd(mname, "billing client is not ready, cannot end connection")
        }
    }

    suspend fun cancelPlaySubscription(accountId: String, purchaseToken: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val mname = "cancelPlaySubscription"
            logd(mname, "Cancelling subscription for account: $accountId")

            // Use mutex to ensure atomic operation with state machine
            connectionMutex.withLock {
                try {
                    // 1. Call backend API
                    val retrofit = RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                    val retrofitInterface = retrofit.create(ITcpProxy::class.java)

                    val response = retrofitInterface.cancelSubscription(persistentState.appVersion.toString(), accountId, purchaseToken, DEBUG)

                    // 2. Validate response
                    if (response == null) {
                        loge(mname, "No response from server")
                        return@withLock Pair(false, "No response from server")
                    }

                    if (!response.isSuccessful || response.code() != 200) {
                        loge(mname, "API error: ${response.code()}")
                        return@withLock Pair(false, "Server error: ${response.code()}")
                    }

                    // 3. Update local state
                    val localSuccess = RpnProxyManager.updateCancelledSubscription(accountId, purchaseToken)
                    if (!localSuccess) {
                        loge(mname, "Failed to update local state")
                        return@withLock Pair(false, "Local state update failed")
                    }

                    // 4. Update state machine (atomic operation)
                    try {
                        subscriptionStateMachine.userCancelled()
                        logd(mname, "Subscription cancelled successfully")
                        Pair(true, "Subscription cancelled successfully")
                    } catch (e: Exception) {
                        loge(mname, "State machine update failed: ${e.message}", e)
                        Pair(false, "Cancelled on server but state sync failed")
                    }
                } catch (e: Exception) {
                    loge(mname, "Error cancelling subscription: ${e.message}", e)
                    Pair(false, "Exception: ${e.message}")
                }
            }
        }

    suspend fun revokeSubscription(accountId: String, purchaseToken: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val mname = "revokeSubscription"
            logd(mname, "Revoking subscription for account: $accountId")

            // Use mutex for atomic operation
            connectionMutex.withLock {
                try {
                    // 1. Call backend API
                    val retrofit = RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                    val retrofitInterface = retrofit.create(ITcpProxy::class.java)

                    val response = retrofitInterface.revokeSubscription(persistentState.appVersion.toString(), accountId, purchaseToken, DEBUG)

                    // 2. Validate response
                    if (response == null) {
                        loge(mname, "No response from server")
                        return@withLock Pair(false, "No response from server")
                    }

                    if (!response.isSuccessful || response.code() != 200) {
                        loge(mname, "API error: ${response.code()}")
                        return@withLock Pair(false, "Server error: ${response.code()}")
                    }

                    // 3. Update local state
                    val localSuccess = RpnProxyManager.updateRevokedSubscription(accountId, purchaseToken)
                    if (!localSuccess) {
                        loge(mname, "Failed to update local state")
                        return@withLock Pair(false, "Local state update failed")
                    }

                    // 4. Update state machine (atomic operation)
                    try {
                        subscriptionStateMachine.subscriptionRevoked()
                        logd(mname, "Subscription revoked successfully")
                        Pair(true, "Subscription revoked successfully")
                    } catch (e: Exception) {
                        loge(mname, "State machine update failed: ${e.message}", e)
                        Pair(false, "Revoked on server but state sync failed")
                    }
                } catch (e: Exception) {
                    loge(mname, "Error revoking subscription: ${e.message}", e)
                    Pair(false, "Exception: ${e.message}")
                }
            }
        }

    suspend fun queryEntitlementFromServer(accountId: String): String? {
        // g/entitlement?cid&purchaseToken&test
        // response: {"message":"canceled subscription","purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
        val mname = this::queryEntitlementFromServer.name
        logd(mname, "querying entitlement for accountId: $accountId, test? $DEBUG")
        if (accountId.isEmpty()) {
            loge(mname, "accountId is empty, cannot query entitlement")
            return null
        }
        // use retrofit to make the API call
        val retrofit = RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val retrofitInterface = retrofit.create(ITcpProxy::class.java)
        try {
            val response = retrofitInterface.queryEntitlement(persistentState.appVersion.toString(), accountId, DEBUG)
            logd(mname, "query entitlement response: ${response?.headers()}, ${response?.message()}, ${response?.raw()?.request?.url}")
            if (response == null) {
                loge(mname, "response is null, failed to query entitlement")
                return null
            }
            if (!response.isSuccessful) {
                loge(mname, "failed to query entitlement, response code: ${response.code()}, error: ${response.errorBody()?.string()}, message: ${response.message()}, url: ${response.raw().request.url}")
                return null
            }
            // check if the response body is not null and has the status field
            return if (response.body() != null) {
                val body = response.body()!!
                // parse the developer payload from the response
                val developerPayload = JSONObject(body.toString()).optString("developerPayload", "")
                logd(mname, "developer payload empty? ${developerPayload.isEmpty()}")
                return developerPayload
            } else {
                loge(mname, "err in querying entitlement: ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            loge(mname, "err in querying entitlement: ${e.message}")
        }

        return null
    }

    suspend fun acknowledgePurchaseFromServer(
        accountId: String,
        purchaseToken: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val mname = "acknowledgePurchaseFromServer"
        logd(mname, "Acknowledging purchase for account: $accountId")

        try {
            // 1. Call backend API
            val retrofit =
                RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(ITcpProxy::class.java)
            val pt = URLEncoder.encode(purchaseToken, "UTF-8")
            val response = retrofitInterface.acknowledgePurchase(persistentState.appVersion.toString(), accountId, pt, true)

            // 2. Validate response
            if (response == null) {
                loge(mname, "No response from server")
                return@withContext Pair(false, "No response from server")
            }

            if (!response.isSuccessful || response.code() != 200) {
                loge(mname, "API error: ${response.code()}")
                loge(mname, "failed acknowledgePurchaseFromServer, response code: ${response.code()}, error: ${response.errorBody()?.string()}, message: ${response.message()}, url: ${response.raw().request.url}")
                return@withContext Pair(false, "Server error: ${response.code()}")
            }

            logd(mname, "Purchase acknowledged successfully")
            Pair(true, "Purchase acknowledged successfully")
        } catch (e: Exception) {
            loge(mname, "Error acknowledging purchase: ${e.message}", e)
            Pair(false, "Exception: ${e.message}")
        }
    }

    fun getLatestPurchaseToken(): String? {
        return purchasesLiveData.value?.maxByOrNull { it.purchaseTime }?.purchaseToken
    }
}
