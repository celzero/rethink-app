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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

// ref: github.com/hypersoftdev/inappbilling
object InAppBillingHandler : KoinComponent {

    private lateinit var billingClient: BillingClient
    private var billingListener: BillingListener? = null
    private val persistentState by inject<PersistentState>()

    const val TAG = "Handler"

    const val HISTORY_LINK = "https://play.google.com/store/account/orderhistory"
    const val LINK = "https://play.google.com/store/account/subscriptions?sku=$1&package=$2"

    //const val PRODUCT_ID_TEST = "test_monthly_sub_1usd"
    const val PROD_ID_ANNUAL_TEST = "proxy_annual_subscription_test"
    const val PROD_ID_MONTHLY_TEST = "proxy_monthly_subscription_test"

    private val queryUtils: QueryUtils by lazy { QueryUtils(billingClient) }
    private val productDetails: CopyOnWriteArrayList<ProductDetail> = CopyOnWriteArrayList()
    private val storeProductDetails: CopyOnWriteArrayList<QueryProductDetail> = CopyOnWriteArrayList()
    private val purchases = CopyOnWriteArrayList<Purchase>()
    private val purchaseDetails = CopyOnWriteArrayList<PurchaseDetail>()
    private val consumables: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    private val _purchasesLiveData = MutableLiveData<List<PurchaseDetail>>()
    val purchasesLiveData: LiveData<List<PurchaseDetail>> = _purchasesLiveData

    private val _productDetailsLiveData = MutableLiveData<List<ProductDetail>>()
    val productDetailsLiveData: LiveData<List<ProductDetail>> = _productDetailsLiveData

    private val _connectionResultLiveData = MutableLiveData<ConnectionResult>()
    val connectionStateLiveData: LiveData<ConnectionResult> = _connectionResultLiveData

    private var lastPurchaseFetchTime: Long = 0

    // 1 minute interval
    private const val PURCHASE_REFRESH_INTERVAL = 1 * 60 * 1000

    // Result state for the billing client
    enum class Priority(val value: Int) {
        HIGH (0),
        MEDIUM(1),
        LOW(2)
    }

    data class ConnectionResult(val isSuccess: Boolean, val message: String)

    data class CompletePurchase(
        val purchase: Purchase,
        val productDetailList: List<ProductDetails>
    )

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
            billingListener?.onConnectionResult(isSuccess, message)
        }
    }

    fun registerListener(billingListener: BillingListener?) {
        this.billingListener = billingListener
    }

    private fun setupBillingClient(context: Context) {
        val mname = this::setupBillingClient.name
        logv(mname, "setting up billing client")
        billingClient = BillingClient.newBuilder(context)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
            .setListener(purchasesListener)
            .build()
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
        _connectionResultLiveData.postValue(res)
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

        if (billingClient.isReady) {
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
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    logd(mname, "billing client is ready")
                    // only fetch the purchases for the subscription type
                    val prodTypes = listOf(ProductType.SUBS)
                    fetchPurchases(prodTypes)
                    queryProductDetails()
                } else {
                    log(mname, "billing client setup failed; code: ${billingResult.responseCode}, msg: ${billingResult.debugMessage}")
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
                onConnectionResultMain(callback, isSuccess = false, message = ResultState.CONNECTION_DISCONNECTED.message)
            }
        })
    }

    private val purchasesListener: PurchasesUpdatedListener =
        PurchasesUpdatedListener { billingResult, purchasesList: List<Purchase>? ->
            val mname = this::purchasesListener.name
            logd(mname, "purchases listener: ${billingResult.responseCode}; ${billingResult.debugMessage}")
            val response = BillingResponse(billingResult.responseCode)
            // based on the response, we can handle the purchase also inform the listeners
            when {
                response.isOk -> {
                    Result.setResultState(ResultState.PURCHASING_SUCCESSFULLY)
                    handlePurchase(purchasesList)
                    return@PurchasesUpdatedListener
                }
                response.isAlreadyOwned -> {
                    // If already owned but has not been consumed yet.
                    purchasesList?.let { queryUtils.checkForAcknowledgements(it, consumables) }
                    Result.setResultState(ResultState.PURCHASING_ALREADY_OWNED)
                    return@PurchasesUpdatedListener
                }
                response.isUserCancelled -> {
                    Result.setResultState(ResultState.LAUNCHING_FLOW_INVOCATION_USER_CANCELLED)
                    log(mname, "user cancelled the purchase flow")
                }
                response.isTerribleFailure -> {
                    log(mname, "terrible failure occurred")
                    Result.setResultState(ResultState.PURCHASING_FAILURE)
                }
                response.isRecoverableError -> {
                    log(mname, "recoverable error occurred")
                    Result.setResultState(ResultState.LAUNCHING_FLOW_INVOCATION_EXCEPTION_FOUND)
                }
                response.isNonrecoverableError -> {
                    log(mname, "non-recoverable error occurred")
                    Result.setResultState(ResultState.PURCHASING_FAILURE)
                }
                else -> log(mname, "unknown error occurred")
            }
        }

    private fun handlePurchase(purchasesList: List<Purchase>?) =
        CoroutineScope(Dispatchers.IO).launch {
            val mname = "handlePurchase"
            if (purchasesList == null) {
                loge(mname, "purchases list is null")
                Result.setResultState(ResultState.PURCHASING_NO_PURCHASES_FOUND)
                return@launch
            }

            purchasesList.forEach { purchase ->
                // Iterate and search for consumable product if any
                var isConsumable = false
                purchase.products.forEach inner@{
                    if (consumables.contains(it)) {
                        isConsumable = true
                        return@inner
                    }
                }

                // true / false
                if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
                    Result.setResultState(ResultState.PURCHASING_FAILURE)
                    logd(mname, "purchase state is not purchased, skipping")
                    return@forEach
                }

                logd(mname, "purchase state is purchased, processing...")
                Result.setResultState(ResultState.PURCHASING_SUCCESSFULLY)

                if (purchase.isAcknowledged) {
                    log(mname, "payment acknowledged for ${purchase.products}")
                } else {
                    if (isConsumable) {
                        queryUtils.checkForAcknowledgementsAndConsumable(purchasesList) {
                            if (it) {
                                Result.setResultState(ResultState.PURCHASE_CONSUME)
                                log(mname, "consumable product has been consumed")
                            } else {
                                Result.setResultState(ResultState.PURCHASE_FAILURE)
                                log(mname, "consumable product has not been consumed")
                            }
                        }
                    } else {
                        queryUtils.checkForAcknowledgements(purchasesList)
                        log(mname, "payment not acknowledged for ${purchase.products}")
                    }
                }
            }
        }

    fun fetchPurchases(productType: List<String>) {
        val mname = this::fetchPurchases.name
        if (lastPurchaseFetchTime != 0L) {
            val currentTime = System.currentTimeMillis()
            val diff = currentTime - lastPurchaseFetchTime
            if (diff < PURCHASE_REFRESH_INTERVAL) {
                loge(mname, "fetching purchases within 60 seconds, skipping")
                return
            }
        }
        purchases.clear()
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
            else -> processPurchases()
        }
        logv(mname, "purchases fetch complete, processing...")
    }

    private fun queryPurchases(productType: String, hasBoth: Boolean) {
        val mname = this::queryPurchases.name
        log(mname, "type: $productType, hasBoth? $hasBoth")

        when (productType) {
            ProductType.INAPP -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING)
            ProductType.SUBS -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING)
        }

        val queryPurchasesParams = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        billingClient.queryPurchasesAsync(queryPurchasesParams) { billingResult, purchases ->
                log(mname, "type: $productType -> purchases: $purchases, result: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                if (BillingResponse(billingResult.responseCode).isOk) {
                    this.purchases.addAll(purchases)
                    when (productType) {
                        ProductType.INAPP -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_SUCCESS)
                        ProductType.SUBS -> Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_SUCCESS)
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
                processPurchases()
            }
    }


    private fun processPurchases() {
        val mname = this::processPurchases.name
        Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_RESPONSE_PROCESSING)
        io {
            val resultList = ArrayList<PurchaseDetail>()
            val completePurchaseList = ArrayList<CompletePurchase>()
            purchases.forEach { purchase ->
                if (isPurchaseStatePurchased(purchase)) {
                    logd(mname, "product purchased: ${purchase.products}")
                    val productParams = queryUtils.getPurchaseParams(
                        ProductType.SUBS,
                        purchase.products
                    )
                    val productDetailsList = queryUtils.queryProductDetailsAsync(productParams)
                    completePurchaseList.add(CompletePurchase(purchase, productDetailsList))
                } else {
                    logd(mname, "product not purchased: ${purchase.products}")
                }
            }

            completePurchaseList.forEach { completePurchase ->
                logd(mname, "complete purchase: ${completePurchase.purchase.products}")

                completePurchase.productDetailList.forEach { pd ->
                    val productType =
                        if (pd.productType == ProductType.INAPP) ProductType.INAPP else ProductType.SUBS
                    val splitList =
                        completePurchase.purchase.accountIdentifiers?.obfuscatedAccountId?.split("_")
                            ?: emptyList()
                    val planId = if (splitList.isNotEmpty() && splitList.size >= 2) {
                        splitList[1]
                    } else {
                        ""
                    }
                    val offerDetails =
                        pd.subscriptionOfferDetails?.find { it.basePlanId == planId }
                    val planTitle = offerDetails?.let { queryUtils.getPlanTitle(it) } ?: ""

                    val purchaseDetail = PurchaseDetail(
                        productId = pd.productId,
                        planId = planId,
                        productTitle = pd.title,
                        planTitle = planTitle,
                        state = completePurchase.purchase.purchaseState,
                        purchaseToken = completePurchase.purchase.purchaseToken,
                        productType = productType,
                        purchaseTime = completePurchase.purchase.purchaseTime.toFormattedDate(),
                        purchaseTimeMillis = completePurchase.purchase.purchaseTime,
                        isAutoRenewing = completePurchase.purchase.isAutoRenewing
                    )
                    resultList.add(purchaseDetail)
                }
            }

            Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_RESPONSE_COMPLETE)
            Result.setResultState(ResultState.CONSOLE_PURCHASE_PRODUCTS_CHECKED_FOR_ACKNOWLEDGEMENT)

            queryUtils.checkForAcknowledgements(purchases, consumables)

            purchaseDetails.clear()
            purchaseDetails.addAll(resultList)
            _purchasesLiveData.postValue(resultList)
            logv(mname, "purchases processed: $resultList")
            if (resultList.isNotEmpty()) {
                RpnProxyManager.activateRpn()
            } else {
                RpnProxyManager.deactivateRpn()
            }

            if (billingListener == null) {
                loge(mname, "billing listener is null")
                return@io
            }
            billingListener?.purchasesResult(true, resultList)
            lastPurchaseFetchTime = System.currentTimeMillis()
        }
    }

    private fun isPurchaseStatePurchased(purchase: Purchase?): Boolean {
        if (purchase?.purchaseState == null) {
            return false
        }
        return if (isSignatureValid(purchase)) {
            // there is no one-time purchase for now, so only check for subscription
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED && purchase.isAutoRenewing
        } else {
            false
        }
    }

    private fun isSignatureValid(purchase: Purchase): Boolean {
        val mname = this::isSignatureValid.name
        val verify = Security.verifyPurchase(purchase.originalJson, purchase.signature)
        logd(mname, "signature verified? $verify for purchase: ${purchase.originalJson}, ${purchase.signature}")
        return verify
    }

    private fun Long.toFormattedDate(pattern: String = "MMM dd, yyyy", locale: Locale = Locale.getDefault()): String {
        val formatter = SimpleDateFormat(pattern, locale)
        return formatter.format(Date(this))
    }

    suspend fun queryProductDetailsWithTimeout() {
        val mname = this::queryProductDetailsWithTimeout.name
        logv(mname, "init query product details with timeout")
        if (storeProductDetails.isNotEmpty()) {
            loge(mname, "store product details is not empty, skipping product details query")
            return
        }
        val result = withTimeoutOrNull(5000) {
            queryProductDetails()
        }
        if (result == null) {
            loge(mname, "query product details timed out")
        }
    }

    private fun queryProductDetails() {
        val mname = this::queryProductDetails.name
        val productListParams = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PROD_ID_MONTHLY_TEST)
                .setProductType(ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PROD_ID_ANNUAL_TEST)
                .setProductType(ProductType.SUBS)
                .build()
        )
        logd(mname, "query product params: $productListParams")
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productListParams)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            logd(mname, "result: ${billingResult.responseCode}, ${billingResult.debugMessage}")
            logv(mname, "product details: ${productDetailsList.size}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processProductList(productDetailsList)
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
                        offersList.forEach tag@{ offer ->       // Weekly, Monthly, etc // Free-Regular  // Regular
                            log(mname, "offer: ${offer.basePlanId}, ${offer.offerId}, ${offer.pricingPhases}")
                            val isExist =
                                this.productDetails.any { it.productId == pd.productId && it.planId == offer.basePlanId }
                            val isExistInStore = storeProductDetails.any { it.productDetail.productId == pd.productId && it.productDetail.planId == offer.basePlanId }
                            logd(mname, "exist? $isExist, $isExistInStore, for $pd, ${offer.basePlanId}, ${pd.productId}")
                            if (isExist && isExistInStore) {
                                logd(mname, "exist: ${storeProductDetails.size}, ${this.productDetails.size} $pd, ${offer.basePlanId}, ${pd.productId}")
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
                                    queryUtils.getPlanTitle(pricingPhaseItem.billingPeriod)
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
                            logd(mname, "product added: ${productDetail.productId}, ${productDetail.planId}, ${productDetail.productTitle}, ${productDetail.pricingDetails.map { it.price }}")
                        }
                    }
                }
            }
        }


        storeProductDetails.addAll(queryProductDetail)
        log(mname, "storeProductDetailsList: $storeProductDetails")
        Result.setResultState(ResultState.CONSOLE_QUERY_PRODUCTS_COMPLETED)

        // remove duplicates from storeProductDetails and productDetails
        // to make sure the list is unique and not duplicated
        val s = storeProductDetails.distinctBy { it.productDetail.productId }
        val p = productDetails.distinctBy { it.productId }

        storeProductDetails.clear()
        storeProductDetails.addAll(s)
        productDetails.clear()
        productDetails.addAll(p)

        logd(mname, "final storeProductDetails: ${storeProductDetails.size}, productDetails: ${productDetails.size}")

        _productDetailsLiveData.postValue(productDetails)

        if (billingListener == null) {
            loge(mname, "billing listener is null")
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

    fun purchaseSubs(
        activity: Activity,
        productId: String,
        planId: String
    ) {
        val mname = this::purchaseSubs.name
        log(mname, "productId: $productId, planId: $planId, ${storeProductDetails.size}")
        val queryProductDetail = storeProductDetails.find {
            it.productDetail.productId == productId
                    && it.productDetail.planId == planId
                    && it.productDetail.productType == ProductType.SUBS
        }

        if (queryProductDetail == null) {
            loge(mname, "no product details found for productId: $productId, planId: $planId, store: $storeProductDetails, size: ${storeProductDetails.size}")
            return
        }

        // initiating purchase flow, init the last purchase fetch time
        lastPurchaseFetchTime = 0

        launchFlow(
            activity = activity,
            queryProductDetail.productDetails,
            offerToken = queryProductDetail.offerDetails?.offerToken
        )
    }

    private fun launchFlow(
        activity: Activity,
        pds: ProductDetails?,
        offerToken: String?
    ) {
        val mname = this::launchFlow.name

        if (pds == null) {
            loge(mname, "no product details found, purchase flow cannot be initiated")
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

        val flowParams =
            BillingFlowParams.newBuilder().setProductDetailsParamsList(paramsList).build()
        val billingResult = billingClient.launchBillingFlow(activity, flowParams)

        Result.setResultState(ResultState.LAUNCHING_FLOW_INVOCATION_SUCCESSFULLY)

        // handle the result, and inform to the listeners
        // listeners should show the appropriate message to the user / update the UI
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                log(mname, "billing result: OK")
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // User canceled the operation, show a message if needed
                log(mname, "billing result: user cancelled the operation")
            }

            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> {
                // The item is unavailable, display an error message
                log(mname, "billing result: item unavailable")
            }

            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                // Handle case when service is disconnected
                log(mname, "billing result: service disconnected")
            }

            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                // Handle timeout
                log(mname, "billing result: service unavailable")
            }

            else -> {
                // For any other error code, log or show a general error message
                log(mname, "billing result: unknown error")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun io(f: suspend () -> Unit) {
          scope.launch { f() }
    }

}
