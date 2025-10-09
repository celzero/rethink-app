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
/*
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
import org.json.JSONObject

// ref: github.com/hypersoftdev/inappbilling
object InAppBillingHandler : KoinComponent {

    private lateinit var billingClient: BillingClient
    private var billingListener: BillingListener? = null

    const val TAG = "IABHandler"

    private val persistentState by inject<PersistentState>()

    const val HISTORY_LINK = "https://play.google.com/store/account/orderhistory"
    const val LINK = "https://play.google.com/store/account/subscriptions?sku=$1&package=$2"

    const val STD_PRODUCT_ID = "standard.tier"

    private lateinit var queryUtils: QueryUtils
    private val productDetails: CopyOnWriteArrayList<ProductDetail> = CopyOnWriteArrayList()
    private val storeProductDetails: CopyOnWriteArrayList<QueryProductDetail> =
        CopyOnWriteArrayList()
    private val purchaseDetails = CopyOnWriteArrayList<PurchaseDetail>()

    val productDetailsLiveData = MutableLiveData<List<ProductDetail>>()
    val purchasesLiveData = MutableLiveData<List<PurchaseDetail>>()
    val transactionErrorLiveData = MutableLiveData<BillingResult>()
    val connectionResultLiveData = MutableLiveData<ConnectionResult>()

    private val subscriptionStateMachine: SubscriptionStateMachineV2 by inject()
    private val stateObserverJob = SupervisorJob()
    private val stateObserverScope = CoroutineScope(Dispatchers.IO + stateObserverJob)

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
        this.billingListener = billingListener
        setupBillingClient(context)
        startConnection { isSuccess, message ->
            if (isSuccess) {
                // Initialize subscription state machine after successful connection
                io {
                    try {
                        // Start observing state changes
                        startStateObserver()
                        logd(
                            "initiate",
                            "subscription state machine initialized and observer started"
                        )
                    } catch (e: Exception) {
                        loge(
                            "initiate",
                            "failed to initialize subscription state machine: ${e.message}",
                            e
                        )
                        // Continue with legacy handling
                    }
                }
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
                fetchPurchases(listOf(ProductType.SUBS))
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
        logv(mname, "starting connection")

        if (Result.getResultState() == ResultState.CONNECTION_ESTABLISHING) {
            logd(mname, "connection establishing in progress")
            Result.setResultState(ResultState.CONNECTION_ESTABLISHING_IN_PROGRESS)
            onConnectionResultMain(
                callback,
                false,
                ResultState.CONNECTION_ESTABLISHING_IN_PROGRESS.message
            )
            return
        }
        Result.setResultState(ResultState.CONNECTION_ESTABLISHING)

        if (::billingClient.isInitialized && billingClient.isReady) {
            logd(mname, "connection already established")
            Result.setResultState(ResultState.CONNECTION_ALREADY_ESTABLISHED)
            onConnectionResultMain(
                callback,
                true,
                ResultState.CONNECTION_ALREADY_ESTABLISHED.message
            )
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    logd(mname, "billing client is ready")
                    // only fetch the purchases for the subscription type
                    queryUtils = QueryUtils(billingClient)
                    queryBillingConfig()
                    val prodTypes = listOf(ProductType.SUBS)
                    fetchPurchases(prodTypes)
                } else {
                    log(
                        mname,
                        "billing client setup failed; code: ${billingResult.responseCode}, msg: ${billingResult.debugMessage}"
                    )
                }
                val isOk = BillingResponse(billingResult.responseCode).isOk
                when (isOk) {
                    true -> Result.setResultState(ResultState.CONNECTION_ESTABLISHED)
                    false -> Result.setResultState(ResultState.CONNECTION_FAILED)
                }
                val message = when (isOk) {
                    true -> ResultState.CONNECTION_ESTABLISHED.message
                    false -> billingResult.debugMessage
                }
                onConnectionResultMain(callback = callback, isSuccess = isOk, message = message)
            }

            override fun onBillingServiceDisconnected() {
                // Try to restart the connection on the next request to
                // Google Play by calling the startConnection() method.
                log(mname, "billing service disconnected")
                Result.setResultState(ResultState.CONNECTION_DISCONNECTED)
                onConnectionResultMain(
                    callback,
                    isSuccess = false,
                    message = ResultState.CONNECTION_DISCONNECTED.message
                )
                // Don't automatically reconnect to avoid infinite loops
                // Let the caller handle reconnection logic
            }
        })
    }

    private val purchasesUpdatedListener: PurchasesUpdatedListener =
        PurchasesUpdatedListener { billingResult, purchasesList ->
            val mname = this::purchasesUpdatedListener.name
            logd(
                mname,
                "purchases listener: ${billingResult.responseCode}; ${billingResult.debugMessage}"
            )

            val response = BillingResponse(billingResult.responseCode)
            when {
                response.isOk -> {
                    Result.setResultState(ResultState.PURCHASING_SUCCESSFULLY)
                    io {
                        handlePurchase(purchasesList)
                    }
                    return@PurchasesUpdatedListener
                }

                response.isAlreadyOwned -> {
                    Result.setResultState(ResultState.PURCHASING_ALREADY_OWNED)
                    log(mname, "already owned, but not consumed yet")
                    io {
                        try {
                            purchasesList?.forEach { purchase ->
                                val purchaseDetail = createPurchaseDetailFromPurchase(purchase)
                                subscriptionStateMachine.restoreSubscription(purchaseDetail)
                            }
                        } catch (e: Exception) {
                            loge(
                                mname,
                                "Error restoring subscription through state machine: ${e.message}",
                                e
                            )
                        }
                    }
                }

                response.isUserCancelled -> {
                    log(mname, "user cancelled the purchase flow")
                    // no-op, just return
                }

                response.isTerribleFailure -> {
                    loge(
                        mname,
                        "terrible failure occurred, billing result: ${billingResult.responseCode}, ${billingResult.debugMessage}"
                    )
                    Result.setResultState(ResultState.PURCHASING_FAILURE)
                    io {
                        try {
                            subscriptionStateMachine.purchaseFailed(billingResult.debugMessage, billingResult)
                        } catch (e: Exception) {
                            loge(
                                mname,
                                "Error handling terrible failure in state machine: ${e.message}",
                                e
                            )
                        }
                    }
                }

                response.isRecoverableError -> {
                    log(mname, "recoverable error occurred")
                    Result.setResultState(ResultState.LAUNCHING_FLOW_INVOCATION_EXCEPTION_FOUND)
                    io {
                        try {
                            subscriptionStateMachine.purchaseFailed(billingResult.debugMessage, billingResult)
                        } catch (e: Exception) {
                            loge(
                                mname,
                                "Error handling recoverable error in state machine: ${e.message}",
                                e
                            )
                        }

                    }
                }

                response.isNonrecoverableError -> {
                    loge(
                        mname,
                        "non-recoverable error occurred, billing result: ${billingResult.responseCode}, ${billingResult.debugMessage}"
                    )
                    Result.setResultState(ResultState.PURCHASING_FAILURE)
                    io {
                        try {
                            subscriptionStateMachine.purchaseFailed(billingResult.debugMessage, billingResult)
                        }catch (e: Exception) {
                                loge(mname, "Error handling non-recoverable error in state machine: ${e.message}", e)
                        }
                    }
                }

                else -> {
                    log(mname, "unknown error occurred")
                    io {
                        try {
                            subscriptionStateMachine.purchaseFailed(billingResult.debugMessage, billingResult)
                        }catch (e: Exception) {
                                loge(mname, "Error handling unknown error in state machine: ${e.message}", e)
                        }
                    }
                }
            }
        }

    private suspend fun handlePurchase(purchasesList: List<Purchase>?) {
        val mname = "handlePurchase"
        if (purchasesList == null || purchasesList.isEmpty()) {
            loge(mname, "purchases list is null")
            Result.setResultState(ResultState.PURCHASING_NO_PURCHASES_FOUND)
            // TODO: no purchases found, handle this case. Either should be in cancelled state or expired
            try {
                if (subscriptionStateMachine.getCurrentState() is SubscriptionStateMachineV2.SubscriptionState.Active) {
                    val billingExpiry = subscriptionStateMachine.getSubscriptionData()?.subscriptionStatus?.billingExpiry
                    if (billingExpiry == null) {
                        loge(mname, "no billing or account expiry found, should not be in active state")
                        subscriptionStateMachine.subscriptionExpired()
                        return
                    }
                    // if there is a valid billing period then user has cancelled the subscription
                    // but in grace period. notify the state machine to handle user cancellation
                    // if billing expiry are less than current time then the subscription is expired
                    if (billingExpiry < System.currentTimeMillis()) {
                        log(mname, "no purchases found, subscription is expired")
                        try {
                            subscriptionStateMachine.subscriptionExpired()
                        } catch (e: Exception) {
                            loge(mname, "err handling expiry in state machine: ${e.message}", e)
                        }
                    } else {
                        // no need to take account expiry into account here
                        log(mname, "no purchases found, user has cancelled the subscription.")
                        // Notify state machine about user cancellation
                        try {
                            RpnProxyManager.handleUserCancellation() // this will notify the state machine
                        } catch (e: Exception) {
                            loge(mname, "err handling user cancellation in state machine: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                loge(mname, "err handling no purchases: ${e.message}", e)
            }
            return
        }

        // Validate current state machine state

        val canProcess: Boolean
        val currentState = subscriptionStateMachine.getCurrentState()
        logd(mname, "Current state machine state: ${currentState.name}")

        // Allow processing in most states, but warn about unexpected ones
        when (currentState) {
            is SubscriptionStateMachineV2.SubscriptionState.Error -> {
                loge(mname, "Processing purchase while in error state")
                canProcess = false
            }
            else -> {
                canProcess = true
            }
        }

        // Filter out duplicates based on purchase token
        val uniquePurchases = purchasesList.distinctBy { it.purchaseToken }
        if (uniquePurchases.size != purchasesList.size) {
            logd(mname, "Found ${purchasesList.size - uniquePurchases.size} duplicate purchases")
        }

        uniquePurchases.forEach { purchase ->
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    logd(mname, "purchase state is purchased, processing...")
                    Result.setResultState(ResultState.PURCHASING_SUCCESSFULLY)

                    if (isPurchaseStateCompleted(purchase)) {
                        logd(mname, "purchase is acknowledged, processing as valid purchase")
                        // processValidPurchase(purchase) - This is now handled by the state machine

                        // Notify state machine about successful payment
                        try {
                            val purchaseDetail = createPurchaseDetailFromPurchase(purchase)
                            subscriptionStateMachine.paymentSuccessful(purchaseDetail)
                        } catch (e: Exception) {
                            loge(mname, "Error notifying state machine of successful payment: ${e.message}", e)
                        }
                    } else { // isPurchaseStatePurchased will be true in this case
                        // treat as ack pending
                        Result.setResultState(ResultState.PURCHASE_ACK_PENDING)
                        createUnacknowledgedPurchase(purchase)
                        try {
                            // Correct event for new unacknowledged purchase
                            subscriptionStateMachine.completePurchase(createPurchaseDetailFromPurchase(purchase))
                        } catch (e: Exception) {
                            loge(mname, "Error handling pending purchase in state machine: ${e.message}", e)
                        }
                    }
                }

                Purchase.PurchaseState.PENDING -> {
                    logd(mname, "purchase state is pending, showing pending UI")
                    Result.setResultState(ResultState.PURCHASE_PENDING)
                }

                Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                    logd(mname, "purchase state unspecified")
                    if (canProcess) {
                        try {
                            subscriptionStateMachine.purchaseFailed(
                                "Purchase state unspecified",
                                null
                            )
                        } catch (e: Exception) {
                            loge(mname, "Error handling unspecified state in state machine: ${e.message}", e)
                        }
                    }
                }

                else -> {
                    logd(mname, "purchase state unknown: ${purchase.purchaseState}")
                    if (canProcess) {
                        try {
                            subscriptionStateMachine.purchaseFailed(
                                "Purchase state unknown: ${purchase.purchaseState}",
                                null
                            )
                        } catch (e: Exception) {
                            loge(mname, "Error handling unknown state in state machine: ${e.message}", e)
                        }
                    }
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
                productType = ProductType.SUBS,
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

    private fun createUnacknowledgedPurchase(purchase: Purchase) {
        val unacknowledgedPurchase = PurchaseDetail(
            productId = purchase.products.firstOrNull().orEmpty(),
            planId = "",
            productTitle = "Pending Purchase",
            state = Purchase.PurchaseState.PENDING,
            status = SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
            planTitle = "Pending",
            purchaseToken = purchase.purchaseToken,
            productType = ProductType.SUBS,
            purchaseTime = purchase.purchaseTime.toFormattedDate(),
            purchaseTimeMillis = purchase.purchaseTime,
            isAutoRenewing = false,
            accountId = purchase.accountIdentifiers?.obfuscatedAccountId ?: "",
            payload = purchase.developerPayload,
            expiryTime = 0L,
        )

        // Update the purchase details list atomically
        synchronized(purchaseDetails) {
            // Remove any existing pending purchase with the same token
            purchaseDetails.removeAll { it.purchaseToken == purchase.purchaseToken }
            purchaseDetails.add(unacknowledgedPurchase)
        }

        purchasesLiveData.postValue(listOf(unacknowledgedPurchase))
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
        if (purchase?.purchaseState == null) {
            return false
        }

        val isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        val isAcknowledged = purchase.isAcknowledged

        // For subscriptions, check auto-renewal; for one-time purchases, don't require auto-renewal
        return if (purchase.products.any { productDetails.find { pd -> pd.productId == it }?.productType == ProductType.SUBS }) {
            isPurchased && purchase.isAutoRenewing && isAcknowledged
        } else {
            isPurchased && isAcknowledged
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
        io {
            val mname = this::fetchPurchases.name
            logv(mname, "fetching purchases...")
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
                    // should not happen
                    loge(mname, "No valid product types provided for fetching purchases")
                    return@io
                }
            }
            logv(mname, "purchases fetch complete, processing...")
        }
    }

    private fun queryPurchases(productType: String, hasBoth: Boolean) {

        val mname = this::queryPurchases.name
        log(mname, "type: $productType, hasBoth? $hasBoth")

        when (productType) {
            ProductType.INAPP -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING)
            ProductType.SUBS -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING)
        }

        val queryPurchasesParams =
            QueryPurchasesParams.newBuilder().setProductType(productType).build()

        billingClient.queryPurchasesAsync(queryPurchasesParams) { billingResult, purchases ->
            // sample purchase: [Purchase. Json: {"orderId":"GPA.3377-3462-2269-94965","packageName":"com.celzero.bravedns","productId":"standard.tier","purchaseTime":1753189609259,"purchaseState":0,"purchaseToken":"fjeciomnalbegbfjfgaaedoa.AO-J1Oy8-PhoHC-Uu23oYerGLKJachIeqicR-bAUn5c0bfN5j4L_rZ34pFUMSEdJi43XaC-Remq9HSdbViCMEqzHbedLURq47g","obfuscatedAccountId":"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e","quantity":1,"autoRenewing":true,"acknowledged":true,"developerPayload":"{\"ws\":{\"kind\":\"ws#v1\",\"cid\":\"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e\",\"sessiontoken\":\"22695:4:1752256088:524537c17ba103463ba1d330efaf05c146ba3404af:023f958b6c1949568f55078e3c58fe6885d3e57322\",\"expiry\":\"2025-08-11T00:00:00.000Z\",\"status\":\"valid\",\"test\":true}}"}]
            log(
                mname,
                "type: $productType -> purchases: ${purchases}, result: ${billingResult.responseCode}, ${billingResult.debugMessage}"
            )
            if (BillingResponse(billingResult.responseCode).isOk) {
                when (productType) {
                    ProductType.INAPP -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_SUCCESS)
                    ProductType.SUBS -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_SUCCESS)
                }
                io {
                    logv(mname, "processing purchases...")
                    handlePurchase(purchases)
                }
            } else {
                loge(mname, "failed to query purchases. result: ${billingResult.responseCode}")
                when (productType) {
                    ProductType.INAPP -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_FAILED)
                    ProductType.SUBS -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_FAILED)
                }
            }

            if (productType == ProductType.INAPP && hasBoth) {
                queryPurchases(ProductType.SUBS, false)
                return@queryPurchasesAsync
            }
        }
    }

    suspend fun queryProductDetailsWithTimeout(timeout: Long = 5000) {
        val mname = this::queryProductDetailsWithTimeout.name
        logv(mname, "init query product details with timeout")
        if (storeProductDetails.isNotEmpty() && productDetails.isNotEmpty()) {
            logd(mname, "store product details is not empty, skipping product details query")
            productDetailsLiveData.postValue(productDetails)
            return
        }
        val result = withTimeoutOrNull(timeout) {
            queryProductDetails()
        }
        if (result == null) {
            loge(mname, "query product details timed out")
            Result.setResultState(ResultState.CONSOLE_QUERY_PRODUCTS_FAILED)
        }
    }

    private fun queryProductDetails() {
        val mname = this::queryProductDetails.name
        // clear the lists before querying for new product details
        storeProductDetails.clear()
        productDetails.clear()
        val productListParams = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(STD_PRODUCT_ID)
                .setProductType(ProductType.SUBS)
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
                loge(
                    mname,
                    "failed to query product details, response code: ${billingResult.responseCode}, message: ${billingResult.debugMessage}"
                )
                billingListener?.productResult(false, emptyList())
                Result.setResultState(ResultState.CONSOLE_QUERY_PRODUCTS_FAILED)
            }
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
                    val pricingPhase = PricingPhase(
                        planTitle = "",
                        recurringMode = RecurringMode.ORIGINAL,
                        price = pd.oneTimePurchaseOfferDetails?.formattedPrice.toString()
                            .removeSuffix(".00"),
                        currencyCode = pd.oneTimePurchaseOfferDetails?.priceCurrencyCode.toString(),
                        billingCycleCount = 0,
                        billingPeriod = "",
                        priceAmountMicros = pd.oneTimePurchaseOfferDetails?.priceAmountMicros
                            ?: 0L,
                        freeTrialPeriod = 0
                    )

                    val productDetail = ProductDetail(
                        productId = pd.productId,
                        planId = "",
                        productTitle = pd.title,
                        productType = ProductType.INAPP,
                        pricingDetails = listOf(pricingPhase)
                    )
                    this.productDetails.add(productDetail)
                    queryProductDetail.add(QueryProductDetail(productDetail, pd, null))
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
                                queryProductDetail.add(QueryProductDetail(productDetail, pd, offer))
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
        log(mname, "storeProductDetailsList: $storeProductDetails")
        Result.setResultState(ResultState.CONSOLE_QUERY_PRODUCTS_COMPLETED)

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

            // Provide specific error messages based on state
            val errorMessage = when (currentState) {
                is SubscriptionStateMachineV2.SubscriptionState.Active -> "Subscription is already active"
                is SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> "Purchase is already pending"
                is SubscriptionStateMachineV2.SubscriptionState.Error -> "System is in error state"
                else -> "Cannot make purchase in current state: ${currentState.name}"
            }

            // TODO: handle error in billing listener and notify user
            billingListener?.purchasesResult(false, emptyList())
            Result.setResultState(ResultState.PURCHASING_FAILURE)
            return
        }

        // Notify state machine about purchase initiation
        try {
            subscriptionStateMachine.startPurchase()
        } catch (e: Exception) {
            loge(mname, "Error starting purchase in state machine: ${e.message}", e)
        }

        log(mname, "productId: $productId, planId: $planId, ${storeProductDetails.size}")
        val queryProductDetail = storeProductDetails.find {
            it.productDetail.productId == productId
                    && it.productDetail.planId == planId
                    && it.productDetail.productType == ProductType.SUBS
        }

        if (queryProductDetail == null) {
            val errorMsg = "no product details found for productId: $productId, planId: $planId"
            loge(mname, errorMsg)

            try {
                subscriptionStateMachine.purchaseFailed(errorMsg, null)
            } catch (e: Exception) {
                loge(mname, "Error reporting purchase failure to state machine: ${e.message}", e)
            }

            // TODO: handle error in billing listener and notify user
            billingListener?.purchasesResult(false, emptyList())
            Result.setResultState(ResultState.PURCHASING_FAILURE)
            return
        }

        try {
            launchFlow(
                activity = activity,
                queryProductDetail.productDetails,
                offerToken = queryProductDetail.offerDetails?.offerToken
            )
        } catch (e: Exception) {
            val errorMsg = "failed to launch purchase flow: ${e.message}"
            loge(mname, errorMsg, e)

            try {
                subscriptionStateMachine.purchaseFailed(errorMsg, null)
            } catch (stateMachineError: Exception) {
                loge(mname, "Error reporting launch failure to state machine: ${stateMachineError.message}", stateMachineError)
            }

            // TODO: handle error in billing listener and notify user
            billingListener?.purchasesResult(false, emptyList())
            Result.setResultState(ResultState.PURCHASING_FAILURE)
        }
    }

    private suspend fun launchFlow(
        activity: Activity,
        pds: ProductDetails?,
        offerToken: String?
    ) {
        val mname = this::launchFlow.name

        if (pds == null) {
            loge(mname, "no product details found, purchase flow cannot be initiated")
            try {
                subscriptionStateMachine.purchaseFailed("No product details found", null)
            } catch (e: Exception) {
                loge(mname, "Error reporting purchase failure to state machine: ${e.message}", e)
            }
            billingListener?.purchasesResult(false, emptyList())
            Result.setResultState(ResultState.PURCHASING_FAILURE)
            return
        }

        logd(mname, "proceeding with purchase flow for product: ${pds.title}")
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
        val flowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(paramsList)
            .setObfuscatedAccountId(accountId).build()
        val billingResult = billingClient.launchBillingFlow(activity, flowParams)

        Result.setResultState(ResultState.LAUNCHING_FLOW_INVOCATION_SUCCESSFULLY)
        billingListener?.purchasesResult(
            billingResult.responseCode == BillingResponseCode.OK,
            emptyList()
        )

        if (billingResult.responseCode != BillingResponseCode.OK) {
            transactionErrorLiveData.postValue(billingResult)
        }
    }

    private suspend fun getObfuscatedAccountId(context: Context): String {
        // consider token as obfuscated account id
        return PipKeyManager.getToken(context)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun io(f: suspend () -> Unit) {
        scope.launch { f() }
    }

    // Query user's billing configuration using BillingClient v7+ API
    fun queryBillingConfig() {
        val getBillingConfigParams = GetBillingConfigParams.newBuilder().build()
        billingClient.getBillingConfigAsync(getBillingConfigParams) { billingResult, billingConfig ->
            if (billingResult.responseCode == BillingResponseCode.OK
                && billingConfig != null
            ) {
                val countryCode = billingConfig.countryCode
                // TODO: Handle country code, see if we need to use it
                Logger.i(LOG_IAB, "BillingConfig country code: $countryCode")
            } else {
                // TODO: Handle errors
                Logger.i(LOG_IAB, "err in billing config: ${billingResult.debugMessage}")
            }
        }
    }

    private fun startStateObserver() {
        stateObserverScope.launch {
            try {
                subscriptionStateMachine.currentState.collect { state ->
                    logd("StateObserver", "Subscription state changed to: ${state.name}")
                    // Handle state changes if needed
                    handleStateChange(state)
                }
            } catch (e: Exception) {
                loge("StateObserver", "Error in state observer: ${e.message}", e)
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
            Current Data: ${subscriptionData?.subscriptionStatus?.productId ?: "None"}
            Last Updated: ${subscriptionData?.lastUpdated?.let { Date(it) } ?: "Never"}
            Legacy Purchase Details: ${purchaseDetails.size}
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

    suspend fun cancelPlaySubscription(accountId: String, purchaseToken: String): Pair<Boolean, String> {
        // g/stop?cid&purchaseToken&test
        // response: {"message":"canceled subscription","purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
        // {"error":"cannot revoke, subscription canceled or expired","expired":false,"canceled":true,"cancelCtx":{"userInitiatedCancellation":{"cancelSurveyResult":null,"cancelTime":"2025-07-10T13:21:24.743Z"},"systemInitiatedCancellation":null,"developerInitiatedCancellation":null,"replacementCancellation":null},"purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
        val mname = this::cancelPlaySubscription.name
        // call ITcpProxy.cancelSubscription with the current account ID and purchase token
        // make an API call to cancel the subscription
        logd(mname, "canceling subscription for accountId: $accountId, purchaseToken: $purchaseToken")
        // use retrofit to make the API call
        val retrofit = RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val retrofitInterface = retrofit.create(ITcpProxy::class.java)
        try {
            val response = retrofitInterface.cancelSubscription(
                accountId,
                purchaseToken,
                DEBUG
            )
            logd(mname, "cancel subscription, response? ${response != null} url: ${response?.raw()?.request?.url}")
            if (response == null) {
                loge(mname, "response is null, failed to cancel subscription")
                return Pair(false, "No response from server, try again later")
            }
            if (!response.isSuccessful) {
                loge(mname, "failed to cancel subscription, response code: ${response.code()}")
                return Pair(
                    false,
                    "Failed to cancel subscription, response code: ${response.code()}, error: ${response.errorBody()?.string()}"
                )
            }
            // check if the response body is not null and has the status field
            return if (response.code() == 200) {
                val res = RpnProxyManager.updateCancelledSubscription(accountId, purchaseToken)
                Pair(res, "Subscription cancelled successfully")
            } else {
                loge(mname, "err in canceling subscription: ${response.errorBody()?.string()}")
                Pair(false, "Error canceling subscription, reason: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            loge(mname, "err in canceling subscription: ${e.message}")
        }

        return Pair(false, "Error canceling subscription, reason: Unknown error")
    }

    suspend fun revokeSubscription(accountId: String, purchaseToken: String): Pair<Boolean, String> {
        // g/refund?cid&purchaseToken&test
        // response: {"message":"canceled subscription","purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
        // {"error":"cannot revoke, subscription canceled or expired","expired":false,"canceled":true,"cancelCtx":{"userInitiatedCancellation":{"cancelSurveyResult":null,"cancelTime":"2025-07-10T13:21:24.743Z"},"systemInitiatedCancellation":null,"developerInitiatedCancellation":null,"replacementCancellation":null},"purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
        val mname = this::revokeSubscription.name
        logd(mname, "revoking subscription for accountId: $accountId, purchaseToken: $purchaseToken")
        // use retrofit to make the API call
        val retrofit = RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val retrofitInterface = retrofit.create(ITcpProxy::class.java)
        try {
            val response = retrofitInterface.revokeSubscription(
                accountId,
                purchaseToken,
                DEBUG
            )
            logd(mname, "revoke subscription response: ${response?.headers()}, ${response?.message()}, ${response?.raw()?.request?.url}")
            if (response == null) {
                loge(mname, "response is null, failed to revoke subscription")
                return Pair(false, "No response from server, try again later")
            }
            if (!response.isSuccessful) {
                loge(mname, "failed to revoke subscription, response code: ${response.code()}, error: ${response.errorBody()?.string()}, message: ${response.message()}, url: ${response.raw().request.url}")
                return Pair(false, "Failed to revoke subscription, response code: ${response.code()}, error: ${response.errorBody()?.string()}")
            }
            // check if the response body is not null and has the status field
            return if (response.code() == 200) {
                val res = RpnProxyManager.updateRevokedSubscription(accountId, purchaseToken)
                Pair(res, "Subscription revoked successfully")
            } else {
                loge(mname, "err in canceling subscription: ${response.errorBody()?.string()}")
                Pair(false, "Error revoking subscription, reason: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            loge(mname, "err in revoking subscription: ${e.message}")
        }

        return Pair(false, "Error revoking subscription, reason: Unknown error")
    }

    suspend fun queryEntitlementFromServer(accountId: String): String? {
        // g/entitlement?cid&purchaseToken&test
        // response: {"message":"canceled subscription","purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
        val mname = this::queryEntitlementFromServer.name
        logd(mname, "querying entitlement for accountId: $accountId, test? $DEBUG")
        // use retrofit to make the API call
        val retrofit = RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val retrofitInterface = retrofit.create(ITcpProxy::class.java)
        try {
            val response = retrofitInterface.queryEntitlement(accountId, DEBUG)
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
}
*/
