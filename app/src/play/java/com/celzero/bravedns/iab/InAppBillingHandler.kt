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
import androidx.lifecycle.asLiveData
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
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
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.iab.InAppBillingHandler.ONE_TIME_PRODUCT_2YRS
import com.celzero.bravedns.iab.InAppBillingHandler.ONE_TIME_PRODUCT_5YRS
import com.celzero.bravedns.iab.InAppBillingHandler.UNACK_ESCALATION_THRESHOLD
import com.celzero.bravedns.iab.InAppBillingHandler.cancelPlaySubscription
import com.celzero.bravedns.iab.InAppBillingHandler.getObfuscatedDeviceId
import com.celzero.bravedns.iab.InAppBillingHandler.getRemainingDaysForInApp
import com.celzero.bravedns.iab.InAppBillingHandler.getRemainingDaysForInAppSuspend
import com.celzero.bravedns.iab.InAppBillingHandler.handleConflict409
import com.celzero.bravedns.iab.InAppBillingHandler.handlePurchase
import com.celzero.bravedns.iab.InAppBillingHandler.purchasesLiveData
import com.celzero.bravedns.iab.InAppBillingHandler.queryProductDetails
import com.celzero.bravedns.iab.InAppBillingHandler.queryUtils
import com.celzero.bravedns.iab.InAppBillingHandler.registerDevice
import com.celzero.bravedns.iab.InAppBillingHandler.revokeSubscription
import com.celzero.bravedns.iab.InAppBillingHandler.serverApiErrorLiveData
import com.celzero.bravedns.iab.InAppBillingHandler.startStateObserver
import com.celzero.bravedns.iab.InAppBillingHandler.updateUIForState
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.getExpiryFromPayload
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object InAppBillingHandler : KoinComponent {

    private lateinit var billingClient: BillingClient
    private var billingListener: BillingListener? = null

    // Application context stored during initiate() for use in background coroutines / ui updates
    // like notifications
    @Volatile
    private var appContext: Context? = null

    const val TAG = "IABHandler"

    private val persistentState by inject<PersistentState>()
    private val billingBackendClient by inject<BillingBackendClient>()
    private val secureIdentityStore by inject<SecureIdentityStore>()

    private val eventLogger by inject<EventLogger>()

    const val PLAY_SUBS_LINK = "https://play.google.com/store/account/subscriptions?sku=$1&package=$2"

    const val STD_PRODUCT_ID = "standard.tier"
    const val ONE_TIME_PRODUCT_ID = "onetime.tier"
    const val ONE_TIME_TEST_PRODUCT_ID = "test_product"

    const val ONE_TIME_PRODUCT_2YRS = "proxy-yearly-2"
    const val ONE_TIME_PRODUCT_5YRS = "proxy-yearly-5"

    const val SUBS_PRODUCT_MONTHLY = "proxy-monthly"
    const val SUBS_PRODUCT_YEARLY = "proxy-yearly"

    // Product type string constants, mirrors BillingClient.ProductType so that
    // shared `full` source-set code (ManagePurchaseFragment, ManagePurchaseViewModel)
    // compiles on all flavors without importing BillingClient directly.
    const val PRODUCT_TYPE_SUBS  = "subs"
    const val PRODUCT_TYPE_INAPP = "inapp"

    const val REVOKE_WINDOW_SUBS_MONTHLY_DAYS = 3
    const val REVOKE_WINDOW_SUBS_YEARLY_DAYS = 7
    const val REVOKE_WINDOW_ONE_TIME_2YRS_DAYS = 2 * 7
    const val REVOKE_WINDOW_ONE_TIME_5YRS_DAYS = 5 * 7

    private lateinit var queryUtils: QueryUtils
    private val productDetails: CopyOnWriteArrayList<ProductDetail> = CopyOnWriteArrayList()
    private val storeProductDetails: CopyOnWriteArrayList<QueryProductDetail> =
        CopyOnWriteArrayList()

    private var subsProcessor: SubscriptionPurchaseProcessor? = null
    private var oneTimeProcessor: OneTimePurchaseProcessor? = null

    val productDetailsLiveData = MutableLiveData<List<ProductDetail>>()
    val purchasesLiveData = MutableLiveData<List<PurchaseDetail>>()
    val transactionErrorLiveData = MutableLiveData<BillingResult?>()

    val serverApiErrorLiveData = MutableLiveData<ServerApiError?>()

    /**
     * Emits whenever an INAPP (one-time) purchase is successfully processed by the billing
     * listener. Unlike [purchasesLiveData], this SharedFlow always fires even for Active →
     * Active transitions that occur during extend-mode purchases where the subscription state
     * does not change.
     */
    private val _oneTimePurchaseCompletedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val oneTimePurchaseCompletedFlow: SharedFlow<Unit> = _oneTimePurchaseCompletedFlow.asSharedFlow()

    private val subscriptionStateMachine: SubscriptionStateMachineV2 by inject()

    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectionMutex = kotlinx.coroutines.sync.Mutex()

    // state tracking
    @Volatile private var isInitialized = false


    private const val EMPTY_QUERY_THRESHOLD = 3
    @Volatile private var consecutiveEmptyQueries = 0

    /**
     * How many times a token must be seen still-unacknowledged before we stop waiting
     * for Play to propagate the ack and escalate to server-side acknowledgement.
     */
    const val UNACK_ESCALATION_THRESHOLD = 3

    /**
     * Per-token count of how many times a purchase has been delivered to [handlePurchase]
     * while still unacknowledged.
     *
     * Key = purchase token
     * Value = number of times this token has been seen with `isAcknowledged = false`
     */
    private val unackedSeenCount: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    // result state for the billing client
    enum class Priority(val value: Int) {
        HIGH(0),
        MEDIUM(1),
        LOW(2)
    }

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
        if (appContext == null) {
            appContext = context.applicationContext
        }

        // initialize billing client
        setupBillingClient(context)

        // initialize state machine first (before connection)
        if (!isInitialized) {
            billingScope.launch {
                try {
                    subscriptionStateMachine.initialize()
                    startStateObserver()
                    isInitialized = true
                    logd(mname, "state machine initialized")
                } catch (e: Exception) {
                    loge(mname, "failed to initialize state machine: ${e.message}", e)
                    // notify listener on init failures
                    withContext(Dispatchers.Main) {
                        billingListener?.onConnectionResult(false, "State machine initialization failed: ${e.message}")
                    }
                    return@launch
                }
            }
        }

        // start billing connection
        startConnection { isSuccess, message ->
            if (isSuccess) {
                logd(mname, "billing connected, fetching initial state")
                // reset empty-query counters on a fresh connection
                consecutiveEmptyQueries = 0
                fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
            } else {
                loge(mname, "billing connection failed: $message")
            }
            billingListener?.onConnectionResult(isSuccess, message)
        }
    }

    fun registerListener(billingListener: BillingListener?) {
        val mname = this::registerListener.name
        this.billingListener = billingListener
        log(mname, "listener registered")
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
            .enableAutoServiceReconnection()
            .build()
    }

    fun enableInAppMessaging(activity: Activity) {
        val mname = this::enableInAppMessaging.name
        if (!isBillingClientSetup()) {
            logd(mname, "billing client not ready; skipping in-app messages")
            return
        }
        val inAppParams = InAppMessageParams.newBuilder()
            .addInAppMessageCategoryToShow(InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)
            .addAllInAppMessageCategoriesToShow()
            .build()
        billingClient.showInAppMessages(activity, inAppParams) { result ->
            logd(mname, "in-app messaging result: ${result.responseCode}")
            when (result.responseCode) {
                InAppMessageResult.InAppMessageResponseCode.NO_ACTION_NEEDED -> {
                    logd(mname, "no action needed")
                }
                InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED -> {
                    // Play has updated the subscription status (e.g. payment recovered,
                    // grace-period entered, subscription canceled/revoked). Re-query
                    // purchases so the state machine and UI reflect the new state.
                    log(mname, "subscription status updated, refreshing purchases")
                    fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                }
                else -> {
                    logd(mname, "unknown response code: ${result.responseCode}")
                }
            }
        }
    }

    fun isBillingClientSetup(): Boolean {
        val mname = this::isBillingClientSetup.name
        logv(mname, "checking if billing client is setup")
        if (!::billingClient.isInitialized) {
            logd(mname, "billing client is not initialized")
            return false
        }
        val isReady = billingClient.isReady
        logd(mname, "isInitialized: true, isReady: $isReady")
        return isReady
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

        // use coroutine for mutex (thread-safe connection attempt)
        billingScope.launch {
            if (!connectionMutex.tryLock()) {
                logd(mname, "connection attempt already in progress")
                withContext(Dispatchers.Main) {
                    callback.invoke(false, "Connection already in progress")
                }
                return@launch
            }

            try {
                // check if already connected
                if (::billingClient.isInitialized && billingClient.isReady) {
                    logd(mname, "billing already connected")
                    if (!::queryUtils.isInitialized) {
                        queryUtils = QueryUtils(billingClient)
                    }
                    setupProcessors()
                    // release the mutex acquired above before returning
                    connectionMutex.unlock()
                    withContext(Dispatchers.Main) {
                        callback.invoke(true, "Already connected")
                    }
                    return@launch
                }

                // start connection
                withContext(Dispatchers.Main) {
                    billingClient.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(billingResult: BillingResult) {
                            val isOk = BillingResponse(billingResult.responseCode).isOk

                            if (isOk) {
                                logd(mname, "billing client setup finished")
                                queryUtils = QueryUtils(billingClient)
                                setupProcessors()
                                queryBillingConfig()
                                billingScope.launch (Dispatchers.IO) { queryProductDetails() }
                                fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                            } else {
                                loge(mname, "billing connection failed: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                            }

                            callback.invoke(isOk, if (isOk) "Connected" else billingResult.debugMessage)
                            connectionMutex.unlock()
                        }

                        override fun onBillingServiceDisconnected() {
                            log(mname, "billing service disconnected")

                            // notify state machine of disconnection
                            billingScope.launch {
                                try {
                                    subscriptionStateMachine.systemCheck()
                                } catch (e: Exception) {
                                    loge(mname, "err during system check on disconnect: ${e.message}", e)
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
                loge(mname, "err starting billing connection: ${e.message}", e)
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

            billingScope.launch {
                try {
                    when {
                        response.isOk -> {
                            log(mname, "purchase successful, processing ${purchasesList?.size ?: 0} items")
                            // notify state machine of purchases
                            val newTokens: Set<String> = purchasesList
                                ?.filter { purchase ->
                                    try { subscriptionStateMachine.isNewPurchase(purchase.purchaseToken) }
                                    catch (_: Exception) { false }
                                }
                                ?.map { it.purchaseToken }
                                ?.toSet()
                                ?: emptySet()

                            // register device
                            if (newTokens.isNotEmpty()) {
                                billingScope.launch {
                                    try {
                                        var deviceId = resolveDeviceId(mname)
                                        purchasesList
                                            ?.filter { it.purchaseToken in newTokens }
                                            ?.forEach { purchase ->
                                                val accountId = purchase.accountIdentifiers?.obfuscatedAccountId
                                                val storedAccId = getObfuscatedAccountId()
                                                var isRegistered: Boolean = false

                                                if (accountId.isNullOrBlank()) {
                                                    loge(mname, "obfuscatedAccountId missing from purchase ${purchase.purchaseToken.take(8)}; skipping registerDevice")
                                                    return@forEach
                                                }
                                                if (deviceId == null || accountId != storedAccId) {
                                                    val didResult = billingBackendClient.createOrRegisterDid(accountId)
                                                    if (didResult.errorCode == 401) {
                                                        loge(mname, "createOrRegisterDid 401 for accountId-len=${accountId.length}; showing auth error")
                                                        handleUnauthorized401(ServerApiError.Operation.CUSTOMER, accountId, "")
                                                        return@forEach
                                                    }
                                                    if (didResult.errorCode == 409) {
                                                        loge(mname, "createOrRegisterDid 409 conflict for accountId-len=${accountId.length}")
                                                        handleConflict409(ServerApiError.Operation.CUSTOMER, accountId, "", "", "", null)
                                                        return@forEach
                                                    }
                                                    deviceId = didResult.deviceId.takeIf { it.isNotBlank() }
                                                    isRegistered = true
                                                }
                                                try {
                                                    logd(mname, "registerDevice for new token=${purchase.purchaseToken.take(8)}, accountId-len=${accountId.length}, deviceId-len=${deviceId?.length}")
                                                    if (deviceId == null) {
                                                        loge(mname, "deviceId missing from purchase ${purchase.purchaseToken.take(8)}; skipping registerDevice")
                                                    } else {
                                                        if (!isRegistered) registerDevice(accountId, deviceId)
                                                    }
                                                } catch (e: Exception) {
                                                    loge(mname, "registerDevice for failed (non-fatal): ${e.message}", e)
                                                }
                                            }
                                    } catch (e: Exception) {
                                        loge(mname, "registerDevice batch for failed (non-fatal): ${e.message}", e)
                                    }
                                }
                            }

                            handlePurchase(purchasesList)

                            // Emit so extend-mode observers can detect INAPP purchase success
                            // even when the subscription state machine stays in Active (no StateFlow
                            // re-emission for same-state Active → Active transitions).
                            if (purchasesList?.any { getProductType(it) == ProductType.INAPP } == true) {
                                _oneTimePurchaseCompletedFlow.tryEmit(Unit)
                            }
                        }

                        response.isAlreadyOwned -> {
                            log(mname, "item already owned; restoring subscription")
                            purchasesList?.forEach { purchase ->
                                try {
                                    val purchaseDetail = createPurchaseDetailFromPurchase(purchase) ?: return@forEach
                                    subscriptionStateMachine.restoreSubscription(purchaseDetail)
                                } catch (e: Exception) {
                                    loge(mname, "err restoring purchase: ${e.message}", e)
                                }
                            }
                        }

                        response.isUserCancelled -> {
                            log(mname, "user cancelled purchase flow")
                            // post to livedata so ui can dismiss bottom sheet
                            transactionErrorLiveData.postValue(billingResult)
                            try {
                                subscriptionStateMachine.userCancelled()
                            } catch (e: Exception) {
                                loge(mname, "err notifying cancellation: ${e.message}", e)
                            }
                        }

                        response.isTerribleFailure || response.isNonrecoverableError -> {
                            loge(mname, "fatal billing error: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                            // post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            subscriptionStateMachine.purchaseFailed(
                                "Fatal error: ${billingResult.debugMessage}",
                                billingResult.responseCode
                            )
                        }

                        response.isRecoverableError -> {
                            log(mname, "recoverable billing error: ${billingResult.debugMessage}")
                            // post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            subscriptionStateMachine.purchaseFailed(
                                "Recoverable error: ${billingResult.debugMessage}",
                                billingResult.responseCode
                            )
                        }

                        else -> {
                            loge(mname, "unknown billing error: ${billingResult.responseCode}")
                            // post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            subscriptionStateMachine.purchaseFailed(
                                "Unknown error: ${billingResult.debugMessage}",
                                billingResult.responseCode
                            )
                        }
                    }
                } catch (e: Exception) {
                    loge(mname, "err in purchase listener: ${e.message}", e)
                    try {
                        subscriptionStateMachine.purchaseFailed("Critical error: ${e.message}", billingResult.responseCode)
                    } catch (smError: Exception) {
                        loge(mname, "failed to notify state machine: ${smError.message}", smError)
                    }
                }
            }
        }

    private suspend fun handlePurchase(
        purchasesList: List<Purchase>?,
        queriedProductType: String? = null
    ) {
        val mname = this::handlePurchase.name
        // play may deliver the same purchase twice in one callback
        val normalized = purchasesList?.distinctBy { it.purchaseToken } ?: emptyList()
        logd(mname, "processing ${normalized.size} play purchases (prdType=$queriedProductType)")

        if (normalized.isEmpty()) {
            // purchasesUpdatedListener never delivers an empty list, so queriedProductType is
            // always non-null here (set by queryPurchases). Guard defensively anyway.
            if (queriedProductType == null) return

            // google play can return empty purchase lists on some devices (Play Services issue,
            // network flake, etc.). single empty response must never expire an active purchase.
            if (queriedProductType == ProductType.SUBS) {
                consecutiveEmptyQueries++
                if (consecutiveEmptyQueries < EMPTY_QUERY_THRESHOLD) {
                    logd(mname, "SUBS, empty purchase, ignoring (consecutive=$consecutiveEmptyQueries/$EMPTY_QUERY_THRESHOLD)")
                    queryPurchases(queriedProductType, false)
                    return
                }
                logd(mname, "SUBS query returned empty, threshold reached ($consecutiveEmptyQueries/$EMPTY_QUERY_THRESHOLD), reconcile...")

                // reset so repeated polls don't re-fire the reconcile path every iteration.
                consecutiveEmptyQueries = 0
                subscriptionStateMachine.reconcileWithPlayBilling(
                    purchases = emptyList(),
                    queriedProductType = queriedProductType
                )
            } else {
                consecutiveEmptyQueries++
                if (consecutiveEmptyQueries < EMPTY_QUERY_THRESHOLD) {
                    logd(mname, "INAPP, empty purchase, ignoring (consecutive=$consecutiveEmptyQueries/$EMPTY_QUERY_THRESHOLD)")
                    queryPurchases(queriedProductType, false)
                    return
                }
                logd(mname, "INAPP query returned empty, threshold reached ($consecutiveEmptyQueries/$EMPTY_QUERY_THRESHOLD), expire old purchases...")

                consecutiveEmptyQueries = 0

                // Before expiring, confirm from the server that no active INAPP purchase
                // still has a valid entitlement. this can happen when the user chose to
                // purchase one time before 30 day of expiry, to purchase one-time the
                // prev purchase should be consumed before the purchase.
                val serverConfirmedValidTokens = mutableSetOf<String>()
                try {
                    val activeInAppRows = subscriptionStateMachine.getActiveInAppPurchase()
                    logd(mname, "INAPP empty-threshold: checking ${activeInAppRows.size} active INAPP row(s) against server")
                    if (activeInAppRows.isNotEmpty()) {
                        // device id in the db is just a path to actual device id
                        val deviceId  = getObfuscatedDeviceId()
                        val now = System.currentTimeMillis()
                        for (sub in activeInAppRows) {
                            try {
                                if (sub.purchaseToken.isBlank()) {
                                    logd(mname, "INAPP row id=${sub.id} has blank token; fail-safe keep")
                                    // Fail-safe: cannot query server without a token; don't expire.
                                    continue
                                }
                                // Use the account ID stored on the row; fall back to the
                                // current identity-store value if the row pre-dates that field.
                                val effectiveAccountId = sub.accountId.ifEmpty { getObfuscatedAccountId() }
                                val purchaseDetailForQuery = PurchaseDetail(
                                    productId = sub.productId,
                                    planId = sub.planId,
                                    productTitle = sub.productTitle,
                                    state = sub.state,
                                    planTitle = "",
                                    purchaseToken = sub.purchaseToken,
                                    productType = ProductType.INAPP,
                                    purchaseTime = sub.purchaseTime.toString(),
                                    purchaseTimeMillis = sub.purchaseTime,
                                    isAutoRenewing = false,
                                    accountId = effectiveAccountId,
                                    deviceId = deviceId,
                                    payload = sub.developerPayload,
                                    expiryTime = sub.billingExpiry,
                                    status = sub.status,
                                    windowDays = sub.windowDays,
                                    orderId = sub.orderId
                                )
                                // Always query the server to get the freshest entitlement for this
                                // purchase token.
                                val updatedDetail = try {
                                    queryEntitlementFromServer(effectiveAccountId, deviceId, purchaseDetailForQuery)
                                } catch (serverEx: Exception) {
                                    loge(mname, "server entitlement check failed for token=${sub.purchaseToken.take(8)}: ${serverEx.message}", serverEx)
                                    // Fail-safe: unexpected exception (queryEntitlementFromServer normally
                                    // catches all errors internally, but guard here for safety).
                                    serverConfirmedValidTokens.add(sub.purchaseToken)
                                    continue
                                }
                                val tunnelExpiry: Long = getExpiryFromPayload(updatedDetail.payload) ?: 0L
                                // billingExpiry is the authoritative local clock for INAPP purchases.
                                // The VPN session token (tunnelExpiry) can expire weeks or months before
                                // the billing window ends (e.g. 2 years). Only skip preservation when
                                // BOTH the session token AND the local billing window are confirmed expired.
                                // This prevents internet outages or server errors from silently expiring
                                // an otherwise-valid purchase.
                                val billingKnownExpired = sub.billingExpiry > 0L &&
                                    sub.billingExpiry != Long.MAX_VALUE &&
                                    sub.billingExpiry <= now
                                logd(mname, "INAPP entitlement for token=${sub.purchaseToken.take(8)}: " +
                                    "tunnelExpiry=$tunnelExpiry, billingExpiry=${sub.billingExpiry}, " +
                                    "now=$now, billingKnownExpired=$billingKnownExpired, did=${deviceId.take(8)}")
                                if (tunnelExpiry > now) {
                                    // Server returned a fresh, valid session token — definitely preserve.
                                    logd(mname, "INAPP token=${sub.purchaseToken.take(8)} server-confirmed valid " +
                                        "(tunnelExpiry=$tunnelExpiry); skipping expire")
                                    serverConfirmedValidTokens.add(sub.purchaseToken)
                                } else if (!billingKnownExpired) {
                                    // Session token has expired (or server/network unavailable) but the
                                    // local billing window is still open (or unknown).
                                    // Covers: network errors, 401 (surfaced to UI by queryEntitlementFromServer),
                                    // 409, server business errors, stale session needing refresh.
                                    // Billing window is the authority — do NOT expire a valid purchase
                                    // simply because the server could not be reached.
                                    logd(mname, "INAPP token=${sub.purchaseToken.take(8)}: tunnelExpiry expired/zero " +
                                        "but billing window not expired (billingExpiry=${sub.billingExpiry}); " +
                                        "preserving (fail-safe — internet/server issues must not expire a valid purchase)")
                                    serverConfirmedValidTokens.add(sub.purchaseToken)
                                } else {
                                    // Both the session token AND the local billing window are expired.
                                    // Allow expireStaleInAppFromDb to handle via the locallyExpired check.
                                    logd(mname, "INAPP token=${sub.purchaseToken.take(8)}: tunnelExpiry=$tunnelExpiry " +
                                        "and billing=${sub.billingExpiry} both expired; will expire")
                                }
                            } catch (e: Exception) {
                                loge(mname, "unexpected error checking INAPP entitlement for id=${sub.id}: ${e.message}", e)
                                // Fail-safe: unexpected error → preserve the token.
                                serverConfirmedValidTokens.add(sub.purchaseToken)
                            }
                        }
                    }
                } catch (outerEx: Exception) {
                    loge(mname, "error fetching active INAPP rows before expiry: ${outerEx.message}", outerEx)
                    // Fail-safe: if we couldn't even read the DB rows, abort the expiry path
                    // entirely by treating all tokens as valid.
                    return
                }
                // Tokens in serverConfirmedValidTokens are still valid per the server and will
                // NOT be expired by expireStaleInAppFromDb (the non-empty set prevents noPlayRecord
                // from triggering for those tokens). Tokens absent from the set ARE expired.
                logd(mname, "INAPP empty-threshold: serverConfirmedValidTokens=${serverConfirmedValidTokens.size}")
                subscriptionStateMachine.expireStaleInAppFromDb(playTokens = serverConfirmedValidTokens)
            }

            val currentState = subscriptionStateMachine.getCurrentState()
            if (currentState == SubscriptionStateMachineV2.SubscriptionState.PurchasePending) {
                // Only mark as failed if the pending purchase type MATCHES the queried type.
                // An empty SUBS result must NOT fail an INAPP (one-time) purchase that is
                // waiting for acknowledgement and there is no subscription to find in Play,
                // which is the expected state after a one-time purchase.
                val pendingProductId = subscriptionStateMachine.getSubscriptionData()
                    ?.subscriptionStatus?.productId ?: ""
                val pendingIsInApp = pendingProductId == ONE_TIME_PRODUCT_ID || pendingProductId == ONE_TIME_PRODUCT_2YRS || pendingProductId == ONE_TIME_PRODUCT_5YRS
                        || pendingProductId == ONE_TIME_TEST_PRODUCT_ID
                val pendingMatchesQueriedType = when (queriedProductType) {
                    ProductType.INAPP -> pendingIsInApp
                    ProductType.SUBS  -> !pendingIsInApp && pendingProductId.isNotBlank()
                    else              -> false
                }
                if (pendingMatchesQueriedType) {
                    logd(mname, "purchase pending in db but play returned 0 $queriedProductType purchases, mark failed...")
                    subscriptionStateMachine.purchaseFailed(
                        "No purchase found in Play after repeated queries",
                        null
                    )
                } else {
                    logd(mname, "PurchasePending is for a different product type than $queriedProductType " +
                        "(pendingProduct=$pendingProductId), skipping failure")
                }
            }
            return
        }

        logd(mname, "non-empty response, size (${purchasesList?.size}; resetting empty counter (was $consecutiveEmptyQueries)")
        consecutiveEmptyQueries = 0

        // print all the values in purchase
        if (DEBUG) {
            purchasesList?.forEach {
                logd(
                    mname,
                    "purchase: productIds: ${it.products}}, purchaseTime: ${it.purchaseTime}, purchaseState: ${it.purchaseState}"
                )
            }
        }

        // get product meta for each productId in the purchase (planId, title)
        val productMetaByPlanId: Map<String, Pair<String, String>> = buildMap {
            val grouped = storeProductDetails.groupBy { it.productDetail.productId }
            grouped.forEach { (productId, entries) ->
                // per-plan entries keyed by planId
                entries.forEach { qpd ->
                    val planId = qpd.productDetail.planId.ifBlank { qpd.offerDetails?.basePlanId ?: "" }
                    val title = qpd.productDetail.productTitle.ifBlank { qpd.productDetails.title }
                    if (planId.isNotEmpty()) put(planId, Pair(planId, title))
                }

                val basePlan = entries.firstOrNull { it.offerDetails?.offerId == null }
                    ?: entries.firstOrNull()
                if (basePlan != null) {
                    val planId = basePlan.productDetail.planId.ifBlank { basePlan.offerDetails?.basePlanId ?: "" }
                    val title  = basePlan.productDetail.productTitle.ifBlank { basePlan.productDetails.title }
                    // write productId fallback if not already available
                    putIfAbsent(productId, Pair(planId, title))
                    put(productId, Pair(planId, title))
                }
            }
        }
        logd(mname, "productMetaByPlanId built: ${productMetaByPlanId.size} entries")
        if (DEBUG) {
            productMetaByPlanId.entries.forEach { (k, v) ->
                logd(mname, "meta[$k] → planId=${v.first}, title=${v.second}")
            }
        }

        val purchaseExpiryMap: Map<String, Long> = normalized.associate { purchase ->
            val offerDetails = resolveOfferDetailsForPurchase(purchase)
            val expiry = calculateExpiryTime(purchase, offerDetails)
            logd(mname, "expiry for ${purchase.purchaseToken.take(8)}: " +
                    "productId=${purchase.products.firstOrNull()}, " +
                    "expiry=$expiry")
            purchase.purchaseToken to expiry
        }

        val (subsPurchases, oneTimePurchases) = normalized.partition {
            getProductType(it, queriedProductType) == ProductType.SUBS
        }
        logd(mname, "Reconciling ${subsPurchases.size} subscription purchases")
        logd(mname, "Handling ${oneTimePurchases.size} one-time purchases")

        if (subsPurchases.isNotEmpty()) {
            consecutiveEmptyQueries = 0

            // Partition SUBS by acknowledgement state.
            // Acknowledged tokens always go straight to reconcile so no counter needed.
            // Unacknowledged tokens are tracked; once the seen-count reaches
            // UNACK_ESCALATION_THRESHOLD they are escalated to server-side ack via
            // subsProcessor instead of being handled by the state machine's reconcile path.
            val (unackedSubs, ackedSubs) = subsPurchases.partition { !it.isAcknowledged }

            // Build the list for reconcileWithPlayBilling: acknowledged purchases + unacked
            // ones that are still below the escalation threshold.
            val subsForReconcile = ackedSubs.toMutableList()
            val subsToEscalate   = mutableListOf<Purchase>()

            for (purchase in unackedSubs) {
                val count = unackedSeenCount.merge(purchase.purchaseToken, 1, Int::plus) ?: 1
                val escalate = count >= UNACK_ESCALATION_THRESHOLD
                logd(mname, "SUBS token=${purchase.purchaseToken.take(8)} unacked: " +
                        "seenCount=$count/$UNACK_ESCALATION_THRESHOLD, escalate=$escalate")
                if (escalate) {
                    unackedSeenCount.remove(purchase.purchaseToken)
                    subsToEscalate.add(purchase)
                } else {
                    // Still waiting, include in reconcile so state machine stays PurchasePending.
                    subsForReconcile.add(purchase)
                }
            }

            // Reconcile the non-escalated purchases normally.
            // Use queriedProductType when available; fall back to SUBS because purchases
            // in subsPurchases were already confirmed as SUBS by the partition logic.
            if (subsForReconcile.isNotEmpty()) {
                try {
                    subscriptionStateMachine.reconcileWithPlayBilling(
                        subsForReconcile,
                        productMetaByPlanId,
                        purchaseExpiryMap,
                        queriedProductType ?: ProductType.SUBS
                    )
                } catch (e: Exception) {
                    loge(mname, "err reconciling subscription purchases: ${e.message}", e)
                }
            }

            // Escalate threshold-crossed unacked tokens to server ack.
            if (subsToEscalate.isNotEmpty()) {
                val processor = subsProcessor
                if (processor == null) {
                    loge(mname, "subsProcessor not ready; cannot escalate ${subsToEscalate.size} SUBS token(s)")
                } else {
                    subsToEscalate.forEach { purchase ->
                        logd(mname, "SUBS token=${purchase.purchaseToken.take(8)} escalating to server ack")
                        try {
                            processor.process(purchase, shouldEscalateToServer = true)
                        } catch (e: Exception) {
                            loge(mname, "err escalating SUBS token=${purchase.purchaseToken.take(8)}: ${e.message}", e)
                        }
                    }
                }
            }
        } else if (queriedProductType == ProductType.SUBS) {
            consecutiveEmptyQueries++
            if (consecutiveEmptyQueries >= EMPTY_QUERY_THRESHOLD) {
                logd(mname, "No SUBS in purchase list despite SUBS query;" +
                    "threshold reached ($consecutiveEmptyQueries/$EMPTY_QUERY_THRESHOLD), " +
                    "expiring stale SUBS DB rows")
                try {
                    subscriptionStateMachine.reconcileWithPlayBilling(
                        purchases          = emptyList<Purchase>(),
                        queriedProductType = ProductType.SUBS
                    )
                } catch (e: Exception) {
                    loge(mname, "Error expiring stale SUBS rows: ${e.message}", e)
                }
            } else {
                logd(mname, "No SUBS in purchase list despite SUBS query;" +
                    "ignoring (consecutive=$consecutiveEmptyQueries/$EMPTY_QUERY_THRESHOLD)")
            }
        }

        if (oneTimePurchases.isNotEmpty()) {
            oneTimePurchases.forEach { purchase ->
                try {
                    processSinglePurchase(purchase)
                } catch (e: Exception) {
                    loge(mname, "err processing one-time purchase ${purchase.purchaseToken.take(8)}: ${e.message}", e)
                }
            }
            // Expire any DB rows that are past their access window or absent from Play.
            val oneTimePlayTokens = oneTimePurchases.map { it.purchaseToken }.toSet()
            try {
                subscriptionStateMachine.expireStaleInAppFromDb(oneTimePlayTokens)
            } catch (e: Exception) {
                loge(mname, "err expiring stale one-time DB rows: ${e.message}", e)
            }
        }
    }

    /**
     * Creates [SubscriptionPurchaseProcessor] and [OneTimePurchaseProcessor].
     * Must be called after [queryUtils] is initialized (i.e. after billing connects).
     */
    private fun setupProcessors() {
        if (subsProcessor != null && oneTimeProcessor != null) return
        val activateRpnFn: suspend (PurchaseDetail) -> Unit = { pd ->
            RpnProxyManager.activateRpn(pd)
        }

        val getProductTypeFn: (Purchase) -> String = { p -> getProductType(p) }

        val getDeviceIdFn: suspend () -> String = { getObfuscatedDeviceId() }

        val onInAppAckSuccessFn: suspend (Purchase) -> Unit = { purchase ->
            // accountId for an existing purchase always comes from the play purchase.
            val accountId = purchase.accountIdentifiers?.obfuscatedAccountId
            if (accountId.isNullOrBlank()) {
                loge("onInAppAckSuccess", "obfuscatedAccountId missing from INAPP purchase ${purchase.purchaseToken.take(8)}; skipping registerDevice")
            } else {
                // resolveDeviceId() returns null on failure
                val deviceId = resolveDeviceId("onInAppAckSuccess")
                if (deviceId == null) {
                    // error already logged inside resolveDeviceId
                } else {
                    try {
                        logd("onInAppAckSuccess", "ack succeeded; registerDevice token=${purchase.purchaseToken.take(8)}, accountId-len=${accountId.length}, deviceId-len=${deviceId.length}")
                        registerDevice(accountId, deviceId)
                    } catch (e: Exception) {
                        loge("onInAppAckSuccess", "registerDevice failed (non-fatal): ${e.message}", e)
                    }
                }
            }
        }

        subsProcessor = SubscriptionPurchaseProcessor(
            storeProductDetails = storeProductDetails,
            subscriptionStateMachine = subscriptionStateMachine,
            queryUtils = { queryUtils },
            acknowledgePurchase = ::acknowledgePurchaseFromServer,
            activateRpn = activateRpnFn,
            getProductType = getProductTypeFn,
            getDeviceId = getDeviceIdFn,
            reconcileCidDid = ::reconcileCidDidFromPurchase,
            validatePayloadAndFetchIfRequired = ::validatePayloadAndFetchIfRequired
        )
        oneTimeProcessor = OneTimePurchaseProcessor(
            storeProductDetails = storeProductDetails,
            subscriptionStateMachine = subscriptionStateMachine,
            acknowledgePurchase = ::acknowledgePurchaseFromServer,
            activateRpn = activateRpnFn,
            getProductType = getProductTypeFn,
            getDeviceId = getDeviceIdFn,
            onAckSuccess = onInAppAckSuccessFn,
            reconcileCidDid = ::reconcileCidDidFromPurchase,
            validatePayloadAndFetchIfRequired = ::validatePayloadAndFetchIfRequired
        )
        logd("setupProcessors", "SubscriptionPurchaseProcessor and InAppPurchaseProcessor created")
    }

    private suspend fun validatePayloadAndFetchIfRequired(purchaseDtl: PurchaseDetail): PurchaseDetail {
        val mname = this::validatePayloadAndFetchIfRequired.name
        val now = System.currentTimeMillis()
        val tunnelExpiry: Long? = getExpiryFromPayload(purchaseDtl.payload)
        if (tunnelExpiry != null && tunnelExpiry > now) {
            return purchaseDtl
        }
        Logger.i(mname, "Payload validation failed or expired for token=${purchaseDtl.purchaseToken.take(8)}; fetching updated entitlement from server")
        return queryEntitlementFromServer(getObfuscatedAccountId(), getObfuscatedDeviceId(), purchaseDtl)
    }

    suspend fun reconcileCidDidFromPurchase(cid: String) {
        val mname = this::reconcileCidDidFromPurchase.name
        if (cid.isEmpty()) {
            logd(mname, "cid empty, skipping reconcile")
            return
        }
        val (storedCid, storedDid) = secureIdentityStore.get()
        if (storedCid == cid && storedDid?.isNotEmpty() == true) {
            logv(mname, "no need to reconcile cid, did from purchase")
            return
        }

        // CID has changed (or DID is missing): re-register this device under the new CID.
        // Call createOrRegisterDid() directly so we can inspect the error code and surface
        // 401/409 to the UI — getDeviceId(cid) obscures the error by falling through to the
        // (now stale) stored DID.
        logd(mname, "reconciling did for new cid=${cid.take(8)}")
        val didResult = billingBackendClient.createOrRegisterDid(cid)
        when {
            didResult.isSuccess -> {
                logd(mname, "reconcile succeeded; persisting new (cid, did)")
                secureIdentityStore.save(cid, didResult.deviceId)
            }
            didResult.errorCode == 401 -> {
                loge(mname, "401 unauthorized on device re-registration for cid=${cid.take(8)}; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.DEVICE, cid, "")
            }
            didResult.errorCode == 409 -> {
                loge(mname, "409 conflict on device re-registration for cid=${cid.take(8)}; surfacing conflict error")
                handleConflict409(ServerApiError.Operation.DEVICE, cid, "", "", "", "Conflict: 409")
            }
            else -> {
                loge(mname, "device re-registration failed (code=${didResult.errorCode}) for cid=${cid.take(8)}; non-fatal")
            }
        }
    }


    private fun resolveOfferDetailsForPurchase(
        purchase: Purchase
    ): ProductDetails.SubscriptionOfferDetails? {
        if (getProductType(purchase) == ProductType.INAPP) return null

        return subsProcessor?.resolveOfferDetails(purchase)
            ?: resolveOfferDetailsInline(purchase)
    }

    private fun resolveOfferDetailsInline(purchase: Purchase): ProductDetails.SubscriptionOfferDetails? {
        val productId = purchase.products.firstOrNull() ?: return null
        val candidates = storeProductDetails.filter {
            it.productDetail.productId == productId && it.offerDetails != null
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.offerDetails?.offerId == null }?.offerDetails
            ?: candidates.first().offerDetails
    }

    /**
     * Processes a single ONETIME [purchase] by delegating to [OneTimePurchaseProcessor].
     * Called from [handlePurchase] for each one-time purchase in the list.
     *
     * Tracks how many times each unacknowledged token has been seen. Once the count
     * reaches [UNACK_ESCALATION_THRESHOLD] the processor is told to escalate to
     * server-side acknowledgement. The counter is cleared on acknowledgement or escalation.
     */
    private suspend fun processSinglePurchase(purchase: Purchase) {
        val mname = "processSinglePurchase"
        val processor = oneTimeProcessor ?: run {
            loge(mname, "inAppProcessor not ready; setupProcessors() not called yet")
            return
        }

        if (purchase.isAcknowledged) {
            // Acknowledged; clear any stale counter and process normally.
            unackedSeenCount.remove(purchase.purchaseToken)
            processor.process(purchase, shouldEscalateToServer = false)
            return
        }

        // Not yet acknowledged: increment seen-count and decide whether to escalate.
        val count = unackedSeenCount.merge(purchase.purchaseToken, 1, Int::plus) ?: 1
        val escalate = count >= UNACK_ESCALATION_THRESHOLD
        logd(mname, "INAPP token=${purchase.purchaseToken.take(8)} unacked: " +
                "seenCount=$count/$UNACK_ESCALATION_THRESHOLD, escalate=$escalate")

        if (escalate) {
            // Remove the counter now, whether ack succeeds or fails the next cycle
            // starts fresh (avoids accumulating unbounded counts).
            unackedSeenCount.remove(purchase.purchaseToken)
        }

        processor.process(purchase, shouldEscalateToServer = escalate)
    }


    /**
     * Builds a [PurchaseDetail] by delegating to the appropriate processor based on
     * product type.
     *
     * - SUBS → [SubscriptionPurchaseProcessor.buildPurchaseDetail]: never returns null
     * - INAPP → [OneTimePurchaseProcessor.buildPurchaseDetail]: returns null
     *
     */
    private suspend fun createPurchaseDetailFromPurchase(purchase: Purchase): PurchaseDetail? {
        return try {
            when (getProductType(purchase)) {
                ProductType.SUBS  -> subsProcessor?.buildPurchaseDetail(purchase)
                    ?: buildSubsPurchaseDetailInline(purchase)
                ProductType.INAPP -> oneTimeProcessor?.buildPurchaseDetail(purchase)
                    ?: buildInAppPurchaseDetailInline(purchase)
                else -> {
                    loge("createPurchaseDetailFromPurchase", "unknown product type for ${purchase.products}")
                    null
                }
            }
        } catch (e: Exception) {
            loge("createPurchaseDetailFromPurchase", "error building PurchaseDetail: ${e.message}", e)
            buildFallbackPurchaseDetail(purchase)
        }
    }

    private suspend fun buildSubsPurchaseDetailInline(purchase: Purchase): PurchaseDetail {
        val offerDetails = resolveOfferDetailsInline(purchase)
        val storeEntry = storeProductDetails.find { it.productDetail.productId == purchase.products.firstOrNull() }
        val expiryTime = calculateExpiryTimeInline(purchase, offerDetails)

        val accountId = purchase.accountIdentifiers?.obfuscatedAccountId.orEmpty()
        if (accountId.isBlank()) {
            loge("buildSubsPurchaseDetailInline", "obfuscatedAccountId blank for SUBS token=${purchase.purchaseToken.take(8)}; was launchFlow called?")
        }
        return PurchaseDetail(
            productId = purchase.products.firstOrNull().orEmpty(),
            planId = offerDetails?.basePlanId ?: storeEntry?.productDetail?.planId.orEmpty(),
            productTitle = storeEntry?.productDetail?.productTitle.orEmpty(),
            planTitle = offerDetails?.let { queryUtils.getPlanTitle(it) }.orEmpty(),
            state = purchase.purchaseState,
            purchaseToken = purchase.purchaseToken,
            productType = ProductType.SUBS,
            purchaseTime = purchase.purchaseTime.toFormattedDate(),
            purchaseTimeMillis = purchase.purchaseTime,
            isAutoRenewing = purchase.isAutoRenewing,
            accountId = accountId,
            // Never store the real device ID in PurchaseDetail; use sentinel only.
            // The actual ID is always fetched from SecureIdentityStore via getObfuscatedDeviceId().
            deviceId = if (getObfuscatedDeviceId().isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload = purchase.developerPayload,
            expiryTime = expiryTime,
            status = purchase.purchaseState.toSubscriptionStatusId(),
            windowDays = REVOKE_WINDOW_SUBS_MONTHLY_DAYS,
            orderId = purchase.orderId.orEmpty()
        )
    }

    private suspend fun buildInAppPurchaseDetailInline(purchase: Purchase): PurchaseDetail? {
        val productId  = purchase.products.firstOrNull().orEmpty()
        val expiryTime = calculateOneTimeExpiryTime(purchase)
        if (expiryTime < System.currentTimeMillis()) return null
        val storeEntry = storeProductDetails.find { it.productDetail.productId == productId }
        val accountId = purchase.accountIdentifiers?.obfuscatedAccountId.orEmpty()
        if (accountId.isBlank()) {
            loge("buildInAppPurchaseDetailInline", "obfuscatedAccountId blank for INAPP token=${purchase.purchaseToken.take(8)}, was launchFlow called?")
        }
        return PurchaseDetail(
            productId = productId,
            planId = storeEntry?.oneTimeOfferDetails?.purchaseOptionId ?: productId,
            productTitle = storeEntry?.productDetail?.productTitle ?: QueryUtils.getPlanTitle(getOneTimeBillingPeriod(productId)),
            planTitle = storeEntry?.productDetail?.productTitle.orEmpty(),
            state = purchase.purchaseState,
            purchaseToken = purchase.purchaseToken,
            productType = ProductType.INAPP,
            purchaseTime = purchase.purchaseTime.toFormattedDate(),
            purchaseTimeMillis = purchase.purchaseTime,
            isAutoRenewing = false,
            accountId = accountId,
            // Never store the real device ID in PurchaseDetail; use sentinel only.
            // The actual ID is always fetched from SecureIdentityStore via getObfuscatedDeviceId().
            deviceId = if (getObfuscatedDeviceId().isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload = purchase.developerPayload,
            expiryTime = expiryTime,
            status = purchase.purchaseState.toSubscriptionStatusId(),
            windowDays = resolveOneTimeRevokeDays(productId),
            orderId = purchase.orderId.orEmpty()
        )
    }

    private suspend fun buildFallbackPurchaseDetail(purchase: Purchase): PurchaseDetail {
        return PurchaseDetail(
            productId = purchase.products.firstOrNull() ?: "",
            planId = "",
            productTitle= "",
            planTitle = "",
            state = purchase.purchaseState,
            purchaseToken = purchase.purchaseToken,
            productType = getProductType(purchase),
            purchaseTime = purchase.purchaseTime.toFormattedDate(),
            purchaseTimeMillis = purchase.purchaseTime,
            isAutoRenewing = purchase.isAutoRenewing,
            accountId = purchase.accountIdentifiers?.obfuscatedAccountId ?: "",
            // Never store the real device ID in PurchaseDetail; use sentinel only.
            // The actual ID is always fetched from SecureIdentityStore via getObfuscatedDeviceId().
            deviceId = if (getObfuscatedDeviceId().isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload = purchase.developerPayload,
            expiryTime = 0L,
            status = SubscriptionStatus.SubscriptionState.STATE_UNKNOWN.id,
            windowDays = REVOKE_WINDOW_SUBS_MONTHLY_DAYS,
            orderId = purchase.orderId ?: ""
        )
    }

    private fun calculateExpiryTime(
        purchase: Purchase,
        offerDetails: ProductDetails.SubscriptionOfferDetails?
    ): Long {
        return when (getProductType(purchase)) {
            ProductType.INAPP -> calculateOneTimeExpiryTime(purchase)
            // note: no where the expiry for subs should be calculated
            else -> calculateExpiryTimeInline(purchase, offerDetails)
        }
    }

    private fun calculateExpiryTimeInline(
        purchase: Purchase,
        offerDetails: ProductDetails.SubscriptionOfferDetails?
    ): Long {
        if (offerDetails == null) return Long.MAX_VALUE

        val pricingPhases = offerDetails.pricingPhases.pricingPhaseList
        val recurringPhase = pricingPhases
            .lastOrNull { it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING }
            ?: pricingPhases.lastOrNull()

        val billingPeriod = recurringPhase?.billingPeriod
        if (billingPeriod.isNullOrEmpty()) return Long.MAX_VALUE

        val cal = Calendar.getInstance().apply { timeInMillis = purchase.purchaseTime }
        val added = addIso8601Period(cal, billingPeriod)
        return if (added) {
            cal.timeInMillis
        } else {
            val days = QueryUtils.getBillingPeriodDays(billingPeriod)
            if (days > 0) { cal.add(Calendar.DAY_OF_YEAR, days); cal.timeInMillis }
            else Long.MAX_VALUE
        }
    }

    private fun calculateOneTimeExpiryTime(purchase: Purchase): Long {
        val productId = purchase.products.firstOrNull() ?: ""
        val cal = Calendar.getInstance().apply { timeInMillis = purchase.purchaseTime }
        return when (productId) {
            ONE_TIME_PRODUCT_2YRS -> {
                cal.add(Calendar.YEAR, 2)
                cal.timeInMillis
            }
            ONE_TIME_PRODUCT_5YRS -> {
                cal.add(Calendar.YEAR, 5)
                cal.timeInMillis
            }
            ONE_TIME_TEST_PRODUCT_ID -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            ONE_TIME_PRODUCT_ID -> {
                cal.add(Calendar.YEAR, 2)
                cal.timeInMillis
            }
            else -> {
                cal.add(Calendar.YEAR, 2)
                cal.timeInMillis
            }
        }
    }

    private fun addIso8601Period(cal: Calendar, period: String): Boolean {
        val regex = Regex("""^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$""")
        val match = regex.matchEntire(period) ?: return false
        val years = match.groupValues[1].toIntOrNull() ?: 0
        val months = match.groupValues[2].toIntOrNull() ?: 0
        val weeks = match.groupValues[3].toIntOrNull() ?: 0
        val days = match.groupValues[4].toIntOrNull() ?: 0
        if (years == 0 && months == 0 && weeks == 0 && days == 0) return false
        if (years > 0) cal.add(Calendar.YEAR, years)
        if (months > 0) cal.add(Calendar.MONTH, months)
        if (weeks > 0) cal.add(Calendar.WEEK_OF_YEAR, weeks)
        if (days > 0) cal.add(Calendar.DAY_OF_YEAR, days)
        return true
    }

    private fun getOneTimeBillingPeriod(productId: String): String = when (productId) {
        ONE_TIME_PRODUCT_2YRS -> "P2Y"
        ONE_TIME_PRODUCT_5YRS -> "P5Y"
        ONE_TIME_TEST_PRODUCT_ID -> "P1D"
        ONE_TIME_PRODUCT_ID -> "P2Y"
        else -> "P2Y"
    }

    private fun resolveOneTimeRevokeDays(productId: String): Int = when (productId) {
        ONE_TIME_PRODUCT_2YRS -> REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
        ONE_TIME_PRODUCT_5YRS -> REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
        ONE_TIME_PRODUCT_ID -> REVOKE_WINDOW_SUBS_MONTHLY_DAYS
        ONE_TIME_TEST_PRODUCT_ID -> REVOKE_WINDOW_SUBS_MONTHLY_DAYS
        else -> REVOKE_WINDOW_SUBS_MONTHLY_DAYS
    }

    private fun Int.toSubscriptionStatusId(): Int = when (this) {
        Purchase.PurchaseState.PURCHASED -> SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
        Purchase.PurchaseState.PENDING -> SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
        else -> SubscriptionStatus.SubscriptionState.STATE_UNKNOWN.id
    }

    private fun isPurchaseStateCompleted(purchase: Purchase?): Boolean {
        val mname = this::isPurchaseStateCompleted.name
        if (purchase?.purchaseState == null) {
            loge(mname, "purchase or purchase state is null, $purchase")
            return false
        }

        val isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        val isAcknowledged = purchase.isAcknowledged

        // treat empty product as subscription
        purchase.products.forEach {
            logv(mname, "Product in purchase: $it")
        }
        val isSubs = getProductType(purchase) == ProductType.SUBS
        val isOneTime = getProductType(purchase) == ProductType.INAPP
        log(mname, "isPurchaseStateCompleted: isSubs?$isSubs, isOneTime?$isOneTime, isPurchased?$isPurchased, isAcknowledged?$isAcknowledged, isAutoRenewing?${purchase.isAutoRenewing}")
        // why isAutoRenewing is not checked for subscription?
        // isAutoRenewing=false simply means the user canceled but the purchase is still valid.
        return if (isSubs) {
            isPurchased && isAcknowledged
        } else if (isOneTime) {
            isPurchased && isAcknowledged
        } else {
            false
        }
    }

    /**
     * Determines the Google Play product type ([ProductType.SUBS] or [ProductType.INAPP])
     * for a [purchase]
     *
     * If [queryProductDetails] has run, each [QueryProductDetail] carries the raw
     * [ProductDetails] object returned by Play, whose `productType` field is set by
     * Google and is the single source of truth.  Cross-reference by `productId`.
     *
     * - offerDetails != null - SUBS (SubscriptionOfferDetails only exist for subs)
     * - oneTimeOfferDetails != null - ONETIME (OneTimePurchaseOfferDetails only exist for onetime)
     *
     * Used when the product-details cache has not yet been populated (e.g. first launch
     * before [queryProductDetails] completes, or billing connection latency).
     * Only exact-match lookups; no substring heuristics that could misfire on future IDs.
     *
     * Returns [ProductType.SUBS] for any truly unknown product.
     * Logging at ERROR level so unknown products are caught during QA.
     *
     * @param queriedProductType When the purchase was returned by queryPurchasesAsync,
     * @param purchase The purchase for which to resolve the product type.
     */
    fun getProductType(
        purchase: Purchase,
        queriedProductType: String? = null
    ): String {
        val mname = "getProductType"
        val productId = purchase.products.firstOrNull()

        if (productId == null) {
            loge(mname, "purchase has no productIds, defaulting to SUBS")
            return ProductType.SUBS
        }

        // queryPurchasesAsync is called with an explicit product type; every purchase
        // in the callback belongs to that type.  Trust it unconditionally.
        if (queriedProductType != null) {
            logv(mname, "productId=$productId resolved via queriedProductType=$queriedProductType")
            return queriedProductType
        }

        // ProductDetails.productType is set by Google Play and is the ground truth.
        val cachedEntry = storeProductDetails.find { it.productDetail.productId == productId }
        if (cachedEntry != null) {
            val cachedType = cachedEntry.productDetails.productType
            logv(mname, "productId=$productId resolved via cache productType=$cachedType")
            return cachedType
        }

        // SubscriptionOfferDetails only exist on SUBS products.
        // OneTimePurchaseOfferDetails only exist on INAPP products.
        // If we find any cache entry for this productId (even a different plan), its
        // offer-detail presence is structurally conclusive.
        val anyEntry = storeProductDetails.find { qpd ->
            qpd.productDetail.productId == productId
        }
        if (anyEntry != null) {
            return when {
                anyEntry.offerDetails != null -> {
                    logv(mname, "productId=$productId resolved via offerDetails presence → SUBS")
                    ProductType.SUBS
                }
                anyEntry.oneTimeOfferDetails != null -> {
                    logv(mname, "productId=$productId resolved via oneTimeOfferDetails presence → INAPP")
                    ProductType.INAPP
                }
                else -> {
                    // Entry exists but both offer fields are null; shouldn't happen.
                    // Fall through to tier 3.
                    logd(mname, "productId=$productId cache entry has no offer details; failing")
                    resolveProductTypeFromKnownIds(productId)
                }
            }
        }

        return resolveProductTypeFromKnownIds(productId)
    }

    /**
     * resolves product type from the known product-ID set.
     */
    private fun resolveProductTypeFromKnownIds(productId: String): String {
        val mname = "resolveProductTypeFromKnownIds"
        return when (productId) {
            STD_PRODUCT_ID -> {
                logd(mname, "productId=$productId → SUBS (known)")
                ProductType.SUBS
            }
            ONE_TIME_PRODUCT_ID,
            ONE_TIME_TEST_PRODUCT_ID,
            ONE_TIME_PRODUCT_2YRS,
            ONE_TIME_PRODUCT_5YRS -> {
                logd(mname, "productId=$productId → INAPP (known)")
                ProductType.INAPP
            }
            else -> {
                loge(mname, "unknown productId=$productId, defaulting to SUBS; " +
                    "add this product ID to resolveProductTypeFromKnownIds()")
                ProductType.SUBS // by default assume as subs; should not happen
            }
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
            logv(mname, "fetching purchases for types: $productType")
            // determine product types to be fetched
            val hasInApp = productType.any { it == ProductType.INAPP }
            val hasSubs = productType.any { it == ProductType.SUBS }
            val hasBoth = productType.any { it == ProductType.INAPP } &&
                    productType.any { it == ProductType.SUBS }


            // query for product types to be fetched
            when {
                hasBoth -> queryPurchases(ProductType.INAPP, true)
                hasInApp -> queryPurchases(ProductType.INAPP, false)
                hasSubs -> queryPurchases(ProductType.SUBS, false)
                else -> {
                    loge(mname, "invalid product type for purchases fetch, $productType")
                    return@launch
                }
            }
            logv(mname, "purchases fetch complete")
        }
    }

    private fun queryPurchases(pt: String, hasBoth: Boolean) {
        val mname = this::queryPurchases.name
        log(mname, "querying purchases for type: $pt, hasBoth: $hasBoth")

        val queryPurchasesParams = QueryPurchasesParams.newBuilder().setProductType(pt).build()

        billingClient.queryPurchasesAsync(queryPurchasesParams) { result, purchases ->
            log(mname, "query result for $pt: code: ${result.responseCode}, purchases: ${purchases.size}")
            if (DEBUG) {
                purchases.forEachIndexed { index, purchase ->
                    log(mname, "purchase ($index): $purchase")
                }
            }
            if (BillingResponse(result.responseCode).isOk) {
                billingScope.launch {
                    try {
                        logv(mname, "processing($pt) ${purchases.size} purchases")
                        handlePurchase(purchases, pt)
                    } catch (e: Exception) {
                        loge(mname, "err processing($pt) purchases: ${e.message}", e)
                    }
                }
            } else {
                loge(mname, "err in query purchases response $pt: ${result.responseCode}, ${result.debugMessage}")
            }

            // query SUBS if we were querying INAPP and hasBoth is true
            if (pt == ProductType.INAPP && hasBoth) {
                queryPurchases(ProductType.SUBS, false)
            }
        }
    }

    suspend fun queryProductDetailsWithTimeout(timeout: Long = 10_000) {
        val mname = this::queryProductDetailsWithTimeout.name
        logv(mname, "querying product details with timeout: ${timeout}ms")

        if (productDetails.isNotEmpty()) {
            logd(mname, "product details cached (${productDetails.size} items), notifying listener")
            val cached = productDetails.toList()
            withContext(Dispatchers.Main) {
                productDetailsLiveData.value = cached
                billingListener?.productResult(true, cached)
            }
            return
        }

        if (!billingClient.isReady) {
            loge(mname, "billing client not ready; cannot query product details")
            appContext?.let { setupBillingClient(it) }
        }

        val result = withTimeoutOrNull(timeout) {
            queryProductDetails()
        }

        if (result == null) {
            loge(mname, "product details query timed out after ${timeout}ms")
            withContext(Dispatchers.Main) {
                billingListener?.productResult(false, emptyList())
            }
        }
    }

    private suspend fun queryProductDetails() {
        val mname = this::queryProductDetails.name
        // clear before a fresh query so stale data doesn't leak into results.
        storeProductDetails.clear()
        productDetails.clear()

        // launch INAPP and SUBS queries concurrently and await both.
        val inAppResult = kotlinx.coroutines.CompletableDeferred<List<ProductDetails>>()
        val subsResult  = kotlinx.coroutines.CompletableDeferred<List<ProductDetails>>()

        val inAppParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(ONE_TIME_PRODUCT_ID)
                        .setProductType(ProductType.INAPP)
                        .build()
                )
            ).build()

        logd(mname, "launching INAPP product query")
        billingClient.queryProductDetailsAsync(inAppParams) { br, result ->
            logd(mname, "INAPP result: code=${br.responseCode}, items=${result.productDetailsList.size}")
            if (br.responseCode == BillingResponseCode.OK) {
                inAppResult.complete(result.productDetailsList)
            } else {
                loge(mname, "INAPP query failed: ${br.responseCode}, ${br.debugMessage}")
                inAppResult.complete(emptyList()) // complete with empty so SUBS still runs
            }
        }

        val subsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(STD_PRODUCT_ID)
                        .setProductType(ProductType.SUBS)
                        .build()
                )
            ).build()

        logd(mname, "launching SUBS product query")
        billingClient.queryProductDetailsAsync(subsParams) { br, result ->
            logd(mname, "SUBS result: code=${br.responseCode}, items=${result.productDetailsList.size}")
            if (br.responseCode == BillingResponseCode.OK) {
                subsResult.complete(result.productDetailsList)
            } else {
                loge(mname, "SUBS query failed: ${br.responseCode}, ${br.debugMessage}")
                subsResult.complete(emptyList())
            }
        }

        // await for both the results before merging them
        val inAppList = inAppResult.await()
        val subsList  = subsResult.await()

        if (inAppList.isNotEmpty()) processProductList(inAppList)
        if (subsList.isNotEmpty())  processProductList(subsList)


        val merged = productDetails.toList()
        logd(mname, "product query complete: ${merged.size} total products (inApp=${inAppList.size}, subs=${subsList.size})")
        withContext(Dispatchers.Main) {
            productDetailsLiveData.postValue(merged)
            billingListener?.productResult(merged.isNotEmpty(), merged)
        }
    }


    private fun processProductList(pds: List<ProductDetails>) {
        val mname = this::processProductList.name
        val queryProductDetails = arrayListOf<QueryProductDetail>()
        logd(mname, "product details size: ${pds.size}, $pds")
        pds.forEach { pd ->
            logd(mname, "product detail: $pd")

            when (pd.productType) {
                ProductType.INAPP -> {
                    // no need to handle oneTimePurchaseOfferDetails as the list will have all
                    // the available offers for the in-app product
                    val offers = pd.oneTimePurchaseOfferDetailsList.orEmpty()
                    if (offers.isEmpty()) {
                        loge(mname, "INAPP product has no one-time offers: ${pd.productId}")
                        return@forEach
                    }

                    offers.forEachIndexed { _, offer ->
                        val billingPeriod = getOneTimeBillingPeriod(offer.purchaseOptionId ?: offer.offerId ?: pd.productId)
                        val planTitle = QueryUtils.getPlanTitle(billingPeriod)
                        val planId = offer.purchaseOptionId ?: offer.offerId ?: pd.productId

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
                        queryProductDetails.add(QueryProductDetail(productDetail, pd, null, offer))
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
                                queryProductDetails.add(QueryProductDetail(productDetail, pd, offer, null))
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

        storeProductDetails.addAll(queryProductDetails)
        log(mname, "processed product details list: ${storeProductDetails.size} items")

        if (DEBUG) {
            storeProductDetails.forEach {
                log(
                    mname,
                    "storeProductDetails item: ${it.productDetail.productId}, ${it.productDetail.planId}, ${it.productDetail.pricingDetails}"
                )
            }

            productDetails.forEach {
                log(mname, "productDetails item: ${it.productId}, ${it.planId}, ${it.pricingDetails}" )
            }
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
    }

    suspend fun purchaseSubs(
        activity: Activity,
        productId: String,
        planId: String,
        forceResubscribe: Boolean = false
    ) {
        val mname = this::purchaseSubs.name

        // check if we can make a purchase through state machine
        // forceResubscribe bypasses the state check when the user explicitly resubscribes from
        // the ResubscribeBottomSheet while the subscription is still Active (canceled but not
        // yet expired). Active has no PurchaseInitiated transition, so startPurchase() is also
        // skipped, the result comes back via PaymentSuccessful which Active→Active handles.
        if (!forceResubscribe && !subscriptionStateMachine.canMakePurchase()) {
            val currentState = subscriptionStateMachine.getCurrentState()
            loge(mname, "cannot make purchase, current state: ${currentState.name}")
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        // notify state machine about purchase initiation (skipped in forceResubscribe mode
        // because Active state has no PurchaseInitiated transition, it would be a no-op or
        // an invalid transition warning)
        if (!forceResubscribe) {
            try {
                subscriptionStateMachine.startPurchase()
            } catch (e: Exception) {
                loge(mname, "err starting purchase in state machine: ${e.message}", e)
                billingListener?.purchasesResult(false, emptyList())
                return
            }
        }

        log(mname, "looking for product: $productId, plan: $planId")
        var pd = storeProductDetails.find {
            it.productDetail.productId == productId &&
            it.productDetail.planId == planId &&
            it.productDetail.productType == ProductType.SUBS
        }

        // storeProductDetails may be empty when purchaseSubs is reached without the RethinkPlus
        // screen having been opened first (e.g. directly from ResubscribeBottomSheet on a cold
        // start). Fetch product details on-demand and retry the lookup once before giving up.
        if (pd == null) {
            log(mname, "product not found in cache (size=${storeProductDetails.size}), fetching on-demand and retrying")
            try {
                withTimeoutOrNull(10_000) { queryProductDetails() }
            } catch (e: Exception) {
                loge(mname, "on-demand product details fetch failed: ${e.message}", e)
            }
            pd = storeProductDetails.find {
                it.productDetail.productId == productId &&
                it.productDetail.planId == planId &&
                it.productDetail.productType == ProductType.SUBS
            }
        }

        if (pd == null) {
            val errorMsg = "No product details found for productId: $productId, planId: $planId"
            loge(mname, errorMsg)

            try {
                subscriptionStateMachine.purchaseFailed(errorMsg, null)
            } catch (e: Exception) {
                loge(mname, "err notifying state machine: ${e.message}", e)
            }

            billingListener?.purchasesResult(false, emptyList())
            return
        }

        try {
            launchFlow(
                activity = activity,
                pd.productDetails,
                offerToken = pd.offerDetails?.offerToken
            )
        } catch (e: Exception) {
            val errorMsg = "Failed to launch purchase flow: ${e.message}"
            loge(mname, errorMsg, e)

            try {
                subscriptionStateMachine.purchaseFailed(errorMsg, null)
            } catch (stateMachineError: Exception) {
                loge(mname, "err notifying state machine: ${stateMachineError.message}", stateMachineError)
            }

            billingListener?.purchasesResult(false, emptyList())
        }
    }

    suspend fun purchaseOneTime(activity: Activity, productId: String, planId: String, forceExtend: Boolean = false) {
        val mname = this::purchaseOneTime.name

        log(mname, "init one-time purchase product: $productId, plan: $planId, forceExtend=$forceExtend")

        if (!forceExtend && !subscriptionStateMachine.canMakePurchase()) {
            val currentState = subscriptionStateMachine.getCurrentState()
            loge(mname, "cannot make one-time purchase in state: ${currentState.name}")
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        // startPurchase fires PurchaseInitiated event. In extend mode the state is Active,
        // which has no PurchaseInitiated transition, the event is safely dropped (no-op).
        // The purchase result comes back via PaymentSuccessful which Active→Active handles.
        if (!forceExtend) {
            try {
                subscriptionStateMachine.startPurchase()
            } catch (e: Exception) {
                loge(mname, "err starting one-time purchase in state machine: ${e.message}", e)
                billingListener?.purchasesResult(false, emptyList())
                return
            }
        }

        val queryProductDetail = storeProductDetails.find {
            it.productDetail.productId == productId &&
                    it.productDetail.productType == ProductType.INAPP &&
                    it.productDetail.planId == planId
        }

        if (queryProductDetail == null) {
            val errorMsg = "No one-time product details found for productId: $productId, planId: $planId"
            loge(mname, errorMsg)
            try { subscriptionStateMachine.purchaseFailed(errorMsg, null) } catch (_: Exception) {}
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        try {
            val offerToken = queryProductDetail.oneTimeOfferDetails?.offerToken
            launchFlow(activity = activity, pds = queryProductDetail.productDetails, offerToken = offerToken)
            logv(mname, "one-time purchase flow launched for productId: $productId, offerToken: $offerToken")
        } catch (e: Exception) {
            val errorMsg = "Failed to launch one-time purchase flow: ${e.message}"
            loge(mname, errorMsg, e)
            try { subscriptionStateMachine.purchaseFailed(errorMsg, null) } catch (_: Exception) {}
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
            loge(mname, "no product details available, cannot launch purchase flow")
            try {
                subscriptionStateMachine.purchaseFailed("No product details found", null)
            } catch (e: Exception) {
                loge(mname, "err notifying state machine: ${e.message}", e)
            }
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        logd(mname, "launching purchase flow for: ${pds.title}, ${pds.productId}, offerToken: $offerToken, ${pds.productType}, ${pds.oneTimePurchaseOfferDetailsList}")

        // Ensure server-driven accountId + deviceId are available BEFORE launching the
        // billing flow. fetchOrEnsureCustomerIds() calls /customer when IDs are absent,
        // then falls back to PipKeyManager local tokens if the server is unreachable,
        // so the purchase flow is never blocked by a server outage.
        val (accountId, resolvedDeviceId) = try {
            fetchOrEnsureCustomerIds()
        } catch (e: Exception) {
            loge(mname, "fetchOrEnsureCustomerIds threw unexpectedly; aborting: ${e.message}", e)
            try { subscriptionStateMachine.purchaseFailed("ID resolution error: ${e.message}", null) } catch (_: Exception) {}
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        if (accountId.isBlank()) {
            loge(mname, "accountId is blank after resolution; aborting purchase flow")
            try { subscriptionStateMachine.purchaseFailed("accountId unavailable", null) } catch (_: Exception) {}
            billingListener?.purchasesResult(false, emptyList())
            return
        }
        if (resolvedDeviceId.isBlank()) {
            loge(mname, "deviceId is blank after resolution; aborting purchase flow")
            try { subscriptionStateMachine.purchaseFailed("deviceId unavailable", null) } catch (_: Exception) {}
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        logd(mname, "accountId ready (len=${accountId.length}), deviceId ready (len=${resolvedDeviceId.length})")

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

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .setObfuscatedAccountId(accountId)
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, flowParams)
        val isSuccess = billingResult.responseCode == BillingResponseCode.OK

        billingListener?.purchasesResult(isSuccess, emptyList())

        if (!isSuccess) {
            loge(mname, "err launch billing flow: ${billingResult.responseCode}")
            transactionErrorLiveData.postValue(billingResult)
        }
    }

    /**
     * Returns the obfuscated **account** ID to embed in [BillingFlowParams].
     *
     * Resolution order (via [BillingBackendClient.resolveIdentity]):
     * 1. [SecureIdentityStore] (AES-encrypted file) — fast path, no network call.
     * 2. `POST /d/acc` + `POST /d/reg` — when store is empty or stale.
     *
     * On HTTP 401 / 409 from the server the corresponding [ServerApiError] is posted to
     * [serverApiErrorLiveData] so the UI can surface it immediately.
     * Returns blank if all sources fail; callers must guard against a blank return.
     */
    suspend fun getObfuscatedAccountId(): String {
        val mname = this::getObfuscatedAccountId.name
        return when (val result = billingBackendClient.resolveIdentity()) {
            is RefreshIdentityResult.Success      -> result.cid
            is RefreshIdentityResult.Unauthorized -> {
                loge(mname, "401 unauthorized on account ID resolution; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.CUSTOMER, "", "")
                ""
            }
            is RefreshIdentityResult.Conflict     -> {
                loge(mname, "409 conflict on account ID resolution; surfacing conflict error")
                handleConflict409(ServerApiError.Operation.CUSTOMER, "", "", "", "", null)
                ""
            }
            is RefreshIdentityResult.Failure      -> {
                loge(mname, "account ID resolution failed (transient)")
                ""
            }
        }
    }

    /**
     * Returns the obfuscated **device** ID used for device registration.
     *
     * Resolution order (via [BillingBackendClient.resolveIdentity]):
     * 1. [SecureIdentityStore] (AES-encrypted file) — fast path, no network call.
     * 2. `POST /d/acc` + `POST /d/reg` — when store is empty or stale.
     *
     * On HTTP 401 / 409 from the server the corresponding [ServerApiError] is posted to
     * [serverApiErrorLiveData] so the UI can surface it immediately.
     * Returns blank if all sources fail; callers must guard against a blank return.
     */
    suspend fun getObfuscatedDeviceId(): String {
        val mname = this::getObfuscatedDeviceId.name
        return when (val result = billingBackendClient.resolveIdentity()) {
            is RefreshIdentityResult.Success -> result.did
            is RefreshIdentityResult.Unauthorized -> {
                loge(mname, "401 unauthorized on device ID resolution; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.CUSTOMER, "", "")
                ""
            }
            is RefreshIdentityResult.Conflict -> {
                loge(mname, "409 conflict on device ID resolution; surfacing conflict error")
                handleConflict409(ServerApiError.Operation.CUSTOMER, "", "", "", "", null)
                ""
            }
            is RefreshIdentityResult.Failure -> {
                loge(mname, "device ID resolution failed (transient)")
                ""
            }
        }
    }

    /**
     * Ensures valid accountId and deviceId are available before launching a purchase flow
     * or making any server API call.
     *
     * ### When no active purchase exists (new user / first-time buyer):
     * Calls `d/acc` with empty IDs so the server assigns a fresh pair.
     *
     * ### When server IDs are already persisted:
     * Returns immediately without a network call.
     *
     * ### Failure:
     * Returns `Pair("", "")` if the server is unreachable and no IDs are stored.
     * The caller is responsible for aborting the purchase flow in this case.
     *
     * @return Pair(accountId, deviceId): blank pair if server unavailable.
     */
    private suspend fun fetchOrEnsureCustomerIds(): Pair<String, String> {
        val mname = "fetchOrEnsureCustomerIds"
        logd(mname, "resolving identity via BillingServerRepository")
        return when (val result = billingBackendClient.resolveIdentity()) {
            is RefreshIdentityResult.Success -> {
                logd(mname, "identity resolved (cidLen=${result.cid.length}, didLen=${result.did.length})")
                Pair(result.cid, result.did)
            }
            is RefreshIdentityResult.Unauthorized -> {
                loge(mname, "401 unauthorized from identity resolution; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.CUSTOMER, "", "")
                Pair("", "")
            }
            is RefreshIdentityResult.Conflict -> {
                loge(mname, "409 conflict from identity resolution; surfacing conflict error")
                handleConflict409(ServerApiError.Operation.CUSTOMER, "", "", "", "", null)
                Pair("", "")
            }
            is RefreshIdentityResult.Failure -> {
                loge(mname, "identity resolution failed (transient)")
                Pair("", "")
            }
        }
    }

    /**
     * Returns a valid deviceId for use in [registerDevice].
     *
     * Resolves via [getObfuscatedDeviceId] (cache → prefs → /customer API → PipKeyManager
     * local fallback).
     *
     * Returns **null** if all sources fail or return blank, and logs an ERROR so the
     * issue is visible.  Callers must skip [registerDevice] when null is returned.
     */
    private suspend fun resolveDeviceId(caller: String): String? {
        return try {
            val id = getObfuscatedDeviceId()
            id.ifBlank {
                loge(caller, "resolveDeviceId: blank deviceId from all sources; skipping registerDevice")
                null
            }
        } catch (e: Exception) {
            loge(caller, "resolveDeviceId failed; skipping registerDevice: ${e.message}", e)
            null
        }
    }

    /** Maps a Play [ProductType] string to the server-side SKU string. */
    private fun skuForType(productType: String): String =
        if (productType == ProductType.SUBS) STD_PRODUCT_ID else ONE_TIME_PRODUCT_ID

    /**
     * Builds a [ServerApiError.Unauthorized401] for [operation], posts it to
     * [serverApiErrorLiveData] on the main thread.
     *
     * Kept in [InAppBillingHandler] alongside [handleConflict409] because it needs
     * to post to [serverApiErrorLiveData], a UI-bound [MutableLiveData] owned here.
     *
     * @param operation    The operation that produced the 401 (DEVICE or CUSTOMER).
     * @param accountId    Full account ID to surface in the error screen.
     * @param deviceId     The device ID used; only the first 8 chars are shown in the UI.
     */
    internal suspend fun handleUnauthorized401(
        operation: ServerApiError.Operation,
        accountId: String,
        deviceId: String
    ) {
        val error = ServerApiError.Unauthorized401(
            operation = operation,
            accountId = accountId,
            deviceIdPrefix = deviceId.take(6)
        )
        withContext(Dispatchers.Main) {
            serverApiErrorLiveData.value = error
        }
        loge("handleUnauthorized401", "401 on ${operation.endpoint}: accLen=${accountId.length}")
    }

    /**
     * Builds a [ServerApiError.Conflict409] for [operation], posts it to
     * [serverApiErrorLiveData] on the main thread, and returns the standard
     * failure [Pair] so all existing callers remain unchanged.
     *
     * Kept in [InAppBillingHandler] (not moved to [BillingBackendClient]) because it
     * needs to post to [serverApiErrorLiveData] which is a UI-bound [MutableLiveData]
     * owned by this object.
     */
    private suspend fun handleConflict409(
        operation: ServerApiError.Operation,
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        sku: String,
        serverMsg: String?
    ): Pair<Boolean, String> {
        val error = ServerApiError.Conflict409(
            endpoint      = operation.endpoint,
            operation     = operation,
            serverMessage = serverMsg?.takeIf { it.isNotBlank() },
            accountId     = accountId,
            purchaseToken = purchaseToken,
            sku           = sku
        )
        withContext(Dispatchers.Main) {
            // Guard: if a Conflict409 is already the current live value, don't post another
            // notification.  Retries can call handleConflict409 multiple times and each
            // would otherwise fire a new notification once the user dismisses the first one.
            val alreadyConflictActive = serverApiErrorLiveData.value is ServerApiError.Conflict409
            serverApiErrorLiveData.value = error
            // If no UI is observing the LiveData right now, also post a notification so
            // the user is alerted even when the app is backgrounded.
            if (!alreadyConflictActive && !serverApiErrorLiveData.hasActiveObservers()) {
                val ctx = appContext
                if (ctx != null) {
                    logd("handleConflict409", "posting conflict notification")
                    PurchaseConflictNotifier.notify(ctx, error, persistentState.theme)
                } else {
                    loge("handleConflict409", "appContext null; cannot post conflict notification")
                }
            } else if (alreadyConflictActive) {
                logd("handleConflict409", "conflict already active, skipping duplicate notification")
            }
        }
        loge("handleConflict409", "409 on ${operation.endpoint}: $serverMsg")
        return Pair(false, serverMsg ?: "Conflict: 409")
    }

    /**
     * Returns a point-in-time snapshot of all purchases currently tracked in
     * [purchasesLiveData]. Intended for use by [SubscriptionCheckWorker] which
     * runs outside the billing lifecycle and cannot observe LiveData directly.
     */
    fun getActivePurchasesSnapshot(): List<PurchaseDetail> {
        val pd = subscriptionStateMachine.getSubscriptionData()?.purchaseDetail
        return if (pd != null) listOf(pd).toList() else emptyList()
    }

    /**
     * Registers a newly-detected purchase with the backend so the server can
     * associate this device with the account and purchase.
     *
     * On HTTP 401 the [ServerApiError.Unauthorized401] error is posted to
     * [serverApiErrorLiveData] so the UI can immediately surface the
     * "Device Authorization Failed" screen.
     *
     * @param accountId Obfuscated account identifier (same value set in BillingFlowParams).
     * @param deviceId Stable per-device identifier generated by [getObfuscatedDeviceId].
     */
    suspend fun registerDevice(
        accountId: String,
        deviceId: String,
        meta: JsonObject? = null
    ) = withContext(Dispatchers.IO) {
        val mname = "registerDevice"
        if (accountId.isBlank() || deviceId.isBlank()) {
            loge(mname, "accountId or deviceId is blank; skipping registerDevice")
            return@withContext
        }
        val effectiveMeta = meta ?: billingBackendClient.buildDeviceMeta()
        when (val result = billingBackendClient.registerDevice(accountId, deviceId, effectiveMeta)) {
            is RegisterDeviceResult.Success -> {
                logd(mname, "registerDevice success")
                logEvent(EventType.PROXY_CONNECT, Severity.LOW, "registerDevice", "registerDevice success")
            }
            is RegisterDeviceResult.Unauthorized -> {
                loge(mname, "registerDevice 401 unauthorized; surfacing DeviceAuthError to UI")
                handleUnauthorized401(ServerApiError.Operation.DEVICE, accountId, deviceId)
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "registerDevice", "registerDevice 401 unauthorized")
            }
            is RegisterDeviceResult.Conflict -> {
                loge(mname, "registerDevice 409 conflict (device already registered, non-fatal)")
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "registerDevice", "registerDevice 409 conflict")
            }
            is RegisterDeviceResult.Failure -> {
                loge(mname, "registerDevice failed: code=${result.httpCode} msg=${result.message} (non-fatal)")
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "registerDevice", "registerDevice failed: ${result.message}")
            }
        }
    }


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
                try {
                    subscriptionStateMachine.systemCheck()
                } catch (recoveryError: Exception) {
                    loge("StateObserver", "Failed system check during recovery: ${recoveryError.message}", recoveryError)
                }
            }
        }
    }

    /**
     * Updates all billing-related LiveData based on the current subscription [state].
     *
     * Design:
     * - **Active / Grace / OnHold / Paused / Canceled**: the user has (or recently had) a
     *   valid subscription: post current purchase details so the UI can show subscription
     *   info. For Active/Grace also clear any stale transaction error LiveData.
     * - **PurchasePending / PurchaseInitiated**: purchase is in flight, keep whatever the
     *   UI currently shows; do not clear or update mid-flight.
     * - **Expired / Revoked / Error / Initial / Uninitialized**: no valid purchase, post
     *   an empty list so the UI clears previously shown subscription details.
     *
     * The [state] parameter is the primary driver, it is never ignored.
     */
    private suspend fun updateUIForState(state: SubscriptionStateMachineV2.SubscriptionState) {
        withContext(Dispatchers.Main) {
            when (state) {
                is SubscriptionStateMachineV2.SubscriptionState.Active,
                is SubscriptionStateMachineV2.SubscriptionState.Grace,
                is SubscriptionStateMachineV2.SubscriptionState.OnHold,
                is SubscriptionStateMachineV2.SubscriptionState.Paused,
                is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                    val purchaseDetails = buildPurchaseListFromState()
                    logd("updateUIForState", "state=${state.name} → posting ${purchaseDetails.size} purchases")
                    purchasesLiveData.postValue(purchaseDetails)
                    // Clear stale error banners when subscription is confirmed active/valid.
                    if (state is SubscriptionStateMachineV2.SubscriptionState.Active ||
                        state is SubscriptionStateMachineV2.SubscriptionState.Grace) {
                        transactionErrorLiveData.postValue(null)
                    }
                }

                is SubscriptionStateMachineV2.SubscriptionState.PurchasePending,
                is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated -> {
                    logd("updateUIForState", "state=${state.name} → keeping current UI state (purchase in flight)")
                }

                is SubscriptionStateMachineV2.SubscriptionState.Expired,
                is SubscriptionStateMachineV2.SubscriptionState.Revoked,
                is SubscriptionStateMachineV2.SubscriptionState.Error,
                is SubscriptionStateMachineV2.SubscriptionState.Initial,
                is SubscriptionStateMachineV2.SubscriptionState.Uninitialized -> {
                    logd("updateUIForState", "state=${state.name} → clearing purchase list")
                    purchasesLiveData.postValue(emptyList())
                }
            }
        }
    }

    /**
     * Builds the list of [PurchaseDetail] to post to [purchasesLiveData].
     * Uses the state machine as the single source of truth, then merges in any
     * already-posted INAPP purchases that may not yet have been reconciled into the
     * state machine (e.g. first launch before the first Play billing query completes).
     */
    private fun buildPurchaseListFromState(): List<PurchaseDetail> {
        val result = mutableListOf<PurchaseDetail>()
        subscriptionStateMachine.getSubscriptionData()?.purchaseDetail?.let {
            result.add(it)
        }
        // Merge in cached INAPP purchases that aren't already in the result.
        val cachedInApp = purchasesLiveData.value
            ?.filter { it.productType == ProductType.INAPP }
            ?.filter { existing -> result.none { it.purchaseToken == existing.purchaseToken } }
            ?: emptyList()
        result.addAll(cachedInApp)
        return result
    }

    /**
     * Handles non-UI side-effects when the subscription state changes.
     * UI updates (LiveData) are handled by [updateUIForState] which is always
     * called immediately after this function from [startStateObserver].
     */
    private fun handleStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        val mname = this::handleStateChange.name
        when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Active ->
                logd(mname, "subscription is active")

            is SubscriptionStateMachineV2.SubscriptionState.Grace ->
                logd(mname, "subscription in grace period; still valid but payment failing")

            is SubscriptionStateMachineV2.SubscriptionState.Cancelled ->
                logd(mname, "subscription cancelled; still valid until billing expiry")

            is SubscriptionStateMachineV2.SubscriptionState.Expired ->
                logd(mname, "subscription expired")

            is SubscriptionStateMachineV2.SubscriptionState.Revoked ->
                loge(mname, "subscription revoked")

            is SubscriptionStateMachineV2.SubscriptionState.Error ->
                loge(mname, "state machine entered error state; Play will re-confirm on next query")

            is SubscriptionStateMachineV2.SubscriptionState.OnHold ->
                logd(mname, "subscription on hold; payment pending")

            is SubscriptionStateMachineV2.SubscriptionState.Paused ->
                logd(mname, "subscription paused")

            is SubscriptionStateMachineV2.SubscriptionState.PurchasePending,
            is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated ->
                logd(mname, "purchase in progress: ${state.name}")

            else ->
                logd(mname, "state changed to: ${state.name}")
        }
    }

    fun hasValidSubscription(): Boolean {
        return RpnProxyManager.hasValidSubscription()
    }

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

    /**
     * Cancel a one-time (INAPP) purchase.
     *
     * Mirrors [cancelPlaySubscription] exactly but routes to the correct SKU and
     * uses [ONE_TIME_PRODUCT_2YRS] / [ONE_TIME_PRODUCT_5YRS] as the product identifier.
     *
     * This should only be called when the purchase is confirmed active and within the
     * revoke window (UI is responsible for enforcing that guard).
     *
     * @param accountId    Obfuscated account ID (PipKeyManager token).
     * @param purchaseToken Play purchase token for this one-time purchase.
     * @param productId    The one-time product ID (e.g. ONE_TIME_PRODUCT_2YRS).
     */
    suspend fun cancelOneTimePurchase(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val mname = "cancelOneTimePurchase"
        logd(mname, "delegating to BillingServerRepository, productId=$productId, accLen=${accountId.length}")
        connectionMutex.withLock {
            val (success, msg) = billingBackendClient.cancelPurchase(accountId, deviceId, productId, purchaseToken)
            if (!success && msg.startsWith("Unauthorized")) {
                loge(mname, "cancelOneTimePurchase 401; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.CANCEL, accountId, deviceId)
                return@withLock Pair(false, msg)
            }
            if (!success && msg.startsWith("Conflict")) {
                return@withLock handleConflict409(ServerApiError.Operation.CANCEL, accountId, deviceId, purchaseToken, productId, msg)
            }
            if (!success) return@withLock Pair(false, msg)
            val localSuccess = RpnProxyManager.updateCancelledSubscription(accountId, purchaseToken)
            if (!localSuccess) {
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "cancelOneTimePurchase", "Local state update failed")
                return@withLock Pair(false, "Local state update failed")
            } else {
                logEvent(EventType.PROXY_SWITCH, Severity.LOW, "cancelOneTimePurchase", "cancelOneTimePurchase success")
            }
            fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
            try {
                subscriptionStateMachine.userCancelled()
                logd(mname, "One-time purchase cancelled successfully")
                Pair(true, "One-time purchase cancelled successfully")
            } catch (e: Exception) {
                loge(mname, "State machine update failed: ${e.message}", e)
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "cancelOneTimePurchase", "State machine update failed: ${e.message}")
                Pair(false, "Cancelled on server but state sync failed")
            }
        }
    }

    /**
     * Revoke a one-time (INAPP) purchase (full refund within revoke window).
     *
     * Mirrors [revokeSubscription] exactly but uses the correct one-time product SKU.
     * Calls [ITcpProxy.revokeSubscription] → updates local state → fires
     * [SubscriptionStateMachineV2.subscriptionRevoked].
     *
     * @param accountId    Obfuscated account ID.
     * @param purchaseToken Play purchase token.
     * @param productId    The one-time product ID (e.g. ONE_TIME_PRODUCT_2YRS).
     */
    suspend fun revokeOneTimePurchase(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val mname = "revokeOneTimePurchase"
        logd(mname, "delegating to BillingServerRepository, productId=$productId, accLen=${accountId.length}")
        connectionMutex.withLock {
            val (success, msg) = billingBackendClient.revokePurchase(accountId, deviceId, productId, purchaseToken)
            if (!success && msg.startsWith("Unauthorized")) {
                loge(mname, "revokeOneTimePurchase 401; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.REVOKE, accountId, deviceId)
                return@withLock Pair(false, msg)
            }
            if (!success && msg.startsWith("Conflict")) {
                return@withLock handleConflict409(ServerApiError.Operation.REVOKE, accountId, deviceId, purchaseToken, productId, msg)
            }
            if (!success) return@withLock Pair(false, msg)
            val localSuccess = RpnProxyManager.updateRevokedSubscription(accountId, purchaseToken)
            if (!localSuccess) {
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "revokeOneTimePurchase", "Local state update failed")
                return@withLock Pair(false, "Local state update failed")
            } else {
                logEvent(EventType.PROXY_SWITCH, Severity.LOW, "revokeOneTimePurchase", "revokeOneTimePurchase success")
            }
            fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
            try {
                subscriptionStateMachine.subscriptionRevoked()
                logd(mname, "One-time purchase revoked successfully")
                Pair(true, "One-time purchase revoked successfully")
            } catch (e: Exception) {
                loge(mname, "State machine update failed: ${e.message}", e)
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "revokeOneTimePurchase", "State machine update failed: ${e.message}")
                Pair(false, "Revoked on server but state sync failed")
            }
        }
    }

    /**
     * Computes the number of days remaining until the one-time purchase expires.
     *
     * Returns null when:
     * - The current subscription is not a one-time (INAPP) purchase.
     * - `billingExpiry` is 0 or [Long.MAX_VALUE] (unknown / subscription product).
     *
     * A negative value means the purchase is already past its access window
     * (should be expired but hasn't been swept yet).
     *
     * When two INAPP purchases are simultaneously active (e.g. the user extended before expiry)
     * this method uses the **maximum** billing expiry across all active INAPP rows via
     * [SubscriptionStateMachineV2.getEffectiveInAppExpiryMs]. Call [getRemainingDaysForInAppSuspend]
     * from a coroutine to get the multi-row-aware result; this synchronous overload falls back
     * to the in-memory state machine data for backward compatibility.
     */
    fun getRemainingDaysForInApp(): Long? {
        val sub = subscriptionStateMachine.getSubscriptionData()?.subscriptionStatus ?: return null
        // only compute remaining days for INAPP products
        val isInApp = sub.productId.contains("onetime", ignoreCase = true) ||
                sub.productId.contains("inapp", ignoreCase = true) ||
                sub.productId == ONE_TIME_TEST_PRODUCT_ID ||
                sub.productId == ONE_TIME_PRODUCT_2YRS ||
                sub.productId == ONE_TIME_PRODUCT_5YRS ||
                sub.productId == ONE_TIME_PRODUCT_ID
        if (!isInApp) return null
        val expiry = sub.billingExpiry
        // TODO: should we check with the vpnAdapter?.winExpiry here?
        if (expiry <= 0L || expiry == Long.MAX_VALUE) return null
        val nowMs = System.currentTimeMillis()
        return (expiry - nowMs) / (24L * 60 * 60 * 1000)
    }

    /**
     * Suspend version of [getRemainingDaysForInApp] that accounts for multiple concurrent
     * active INAPP rows. Returns remaining days until the LATEST expiry across all active
     * INAPP subscriptions. Use this from coroutines for accurate results in the extend-flow.
     */
    suspend fun getRemainingDaysForInAppSuspend(): Long? {
        val mname = "getRemainingDaysForInAppSuspend"
        val sub = subscriptionStateMachine.getSubscriptionData()?.subscriptionStatus
        if (sub == null) {
            loge(mname, "no subscription data available in state machine; cannot calculate remaining days for INAPP")
            return null
        }
        val isInApp = sub.productId.contains("onetime", ignoreCase = true) ||
                sub.productId.contains("inapp", ignoreCase = true) ||
                sub.productId == ONE_TIME_TEST_PRODUCT_ID ||
                sub.productId == ONE_TIME_PRODUCT_2YRS ||
                sub.productId == ONE_TIME_PRODUCT_5YRS ||
                sub.productId == ONE_TIME_PRODUCT_ID
        if (!isInApp) {
            loge(mname, "Current subscription is not an INAPP product; cannot calculate remaining days for INAPP")
            return null
        }
        val effectiveExpiry = subscriptionStateMachine.getEffectiveInAppExpiryMs()
        if (effectiveExpiry == null) {
            loge(mname, "Effective INAPP expiry is null; cannot calculate remaining days")
            return null
        }
        if (effectiveExpiry <= 0L || effectiveExpiry == Long.MAX_VALUE) {
            loge(mname, "Effective INAPP expiry is invalid (expiry=$effectiveExpiry); cannot calculate remaining days")
            return null
        }
        val nowMs = System.currentTimeMillis()
        return (effectiveExpiry - nowMs) / (24L * 60 * 60 * 1000)
    }

    suspend fun cancelPlaySubscription(accountId: String, deviceId: String, purchaseToken: String, sku: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val mname = "cancelPlaySubscription"
            logd(mname, "delegating to BillingServerRepository, accLen=${accountId.length}")
            connectionMutex.withLock {
                val (success, msg) = billingBackendClient.cancelPurchase(accountId, deviceId, sku, purchaseToken)
                if (!success && msg.startsWith("Unauthorized")) {
                    loge(mname, "cancelPlaySubscription 401; surfacing auth error")
                    handleUnauthorized401(ServerApiError.Operation.CANCEL, accountId, deviceId)
                    return@withLock Pair(false, msg)
                }
                if (!success && msg.startsWith("Conflict")) {
                    return@withLock handleConflict409(ServerApiError.Operation.CANCEL, accountId, deviceId, purchaseToken, sku, msg)
                }
                if (!success) return@withLock Pair(false, msg)
                val localSuccess = RpnProxyManager.updateCancelledSubscription(accountId, purchaseToken)
                if (!localSuccess) {
                    logEvent(EventType.PROXY_ERROR, Severity.HIGH, "cancelPlaySubscription", "Local state update failed")
                    return@withLock Pair(false, "Local state update failed")
                } else {
                    logEvent(EventType.PROXY_SWITCH, Severity.LOW, "cancelPlaySubscription", "cancelPlaySubscription success")
                }
                fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                try {
                    subscriptionStateMachine.userCancelled()
                    logd(mname, "subscription cancelled successfully")
                    Pair(true, "Subscription cancelled successfully")
                } catch (e: Exception) {
                    loge(mname, "state machine update failed: ${e.message}", e)
                    logEvent(EventType.PROXY_ERROR, Severity.HIGH, "cancelPlaySubscription", "State machine update failed: ${e.message}")
                    Pair(false, "Cancelled on server but state sync failed")
                }
            }
        }

    suspend fun revokeSubscription(accountId: String, deviceId: String, purchaseToken: String, sku: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val mname = "revokeSubscription"
            logd(mname, "delegating to BillingServerRepository, accLen=${accountId.length}")
            connectionMutex.withLock {
                val (success, msg) = billingBackendClient.revokePurchase(accountId, deviceId, sku, purchaseToken)
                if (!success && msg.startsWith("Unauthorized")) {
                    loge(mname, "revokeSubscription 401; surfacing auth error")
                    handleUnauthorized401(ServerApiError.Operation.REVOKE, accountId, deviceId)
                    return@withLock Pair(false, msg)
                }
                if (!success && msg.startsWith("Conflict")) {
                    return@withLock handleConflict409(ServerApiError.Operation.REVOKE, accountId, deviceId, purchaseToken, sku, msg)
                }
                if (!success) return@withLock Pair(false, msg)
                val localSuccess = RpnProxyManager.updateRevokedSubscription(accountId, purchaseToken)
                if (!localSuccess) {
                    logEvent(EventType.PROXY_ERROR, Severity.HIGH, "revokeSubscription", "Local state update failed")
                    return@withLock Pair(false, "Local state update failed")
                } else {
                    logEvent(EventType.PROXY_SWITCH, Severity.LOW, "revokeSubscription", "revokeSubscription success")
                }
                fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                try {
                    subscriptionStateMachine.subscriptionRevoked()
                    logd(mname, "Subscription revoked successfully")
                    Pair(true, "Subscription revoked successfully")
                } catch (e: Exception) {
                    loge(mname, "State machine update failed: ${e.message}", e)
                    logEvent(EventType.PROXY_ERROR, Severity.HIGH, "revokeSubscription", "State machine update failed: ${e.message}")
                    Pair(false, "Revoked on server but state sync failed")
                }
            }
        }

    suspend fun queryEntitlementFromServer(accountId: String, deviceId: String, purchase: PurchaseDetail): PurchaseDetail {
        val mname = this::queryEntitlementFromServer.name
        logd(mname, "delegating to BillingServerRepository, accLen=${accountId.length}")
        val pt = purchase.purchaseToken.ifEmpty { getLatestPurchaseToken() } ?: run {
            logd(mname, "no purchase token; skipping")
            return purchase
        }

        return when (val result = billingBackendClient.queryEntitlement(accountId, deviceId, purchase, pt)) {
            is QueryEntitlementResult.Success -> result.purchase
            is QueryEntitlementResult.Unauthorized -> {
                loge(mname, "queryEntitlement 401 unauthorized for token=${pt.take(8)}; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.ACKNOWLEDGE, result.accountId, result.deviceId)
                // Fail-safe: server auth error must not expire a locally-valid purchase.
                purchase
            }
            is QueryEntitlementResult.Conflict -> {
                loge(mname, "queryEntitlement 409 conflict for token=${pt.take(8)}")
                handleConflict409(
                    ServerApiError.Operation.ACKNOWLEDGE, accountId, deviceId,
                    pt, skuForType(purchase.productType), "Conflict: 409"
                )
                // Fail-safe: conflict error must not expire a locally-valid purchase.
                purchase
            }
            is QueryEntitlementResult.Failure -> {
                // Server responded with a business error (e.g. purchase cancelled/revoked).
                // Preserve the original purchase; the local billing expiry is the authority.
                loge(mname, "queryEntitlement server business error for token=${pt.take(8)}; preserving original purchase")
                // If the server included a linkedPurchaseId, the revoked purchase may have been
                // superseded by an older one that is still valid.  Attempt to reactivate it so
                // the user is not left in a broken state.
                val linked = result.linkedPurchaseId
                if (!linked.isNullOrBlank()) {
                    logd(mname, "linkedPurchaseId present for token=${pt.take(8)}; attempting reactivation of linkedToken=${linked.take(8)}")
                    try {
                        RpnProxyManager.tryReactivateLinkedPurchase(accountId, deviceId, linked)
                    } catch (e: Exception) {
                        loge(mname, "tryReactivateLinkedPurchase threw for linkedToken=${linked.take(8)}: ${e.message}", e)
                    }
                }
                result.purchase
            }
            is QueryEntitlementResult.Transient -> {
                // Network/transient failure — server was not reached.
                // Always preserve the original purchase; never expire on a connectivity issue.
                logd(mname, "queryEntitlement transient failure for token=${pt.take(8)}; preserving original purchase (fail-safe)")
                result.purchase
            }
        }
    }

    suspend fun acknowledgePurchaseFromServer(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productType: String
    ): Pair<Boolean, String> {
        val mname = "acknowledgePurchaseFromServer"
        logd(mname, "delegating to BillingServerRepository, accLen=${accountId.length}")
        val (success, payload) = billingBackendClient.acknowledgePurchase(accountId, deviceId, purchaseToken, productType)
        if (!success && payload.startsWith("Unauthorized")) {
            loge(mname, "acknowledgePurchaseFromServer 401: acc=${accountId.take(8)}, prod=$productType, token=${purchaseToken.take(8)}")
            handleUnauthorized401(ServerApiError.Operation.ACKNOWLEDGE, accountId, deviceId)
        }
        if (!success && payload.startsWith("Conflict")) {
            loge(mname, "acknowledgePurchaseFromServer 409: acc=${accountId.take(8)}, prod=$productType, token=${purchaseToken.take(8)}")
            handleConflict409(
                ServerApiError.Operation.ACKNOWLEDGE, accountId, deviceId,
                purchaseToken, skuForType(productType), payload
            )
        }
        return Pair(success, payload)
    }

    fun getLatestPurchaseToken(): String? {
        return purchasesLiveData.value?.maxByOrNull { it.purchaseTime }?.purchaseToken
    }

    fun getSubscriptionState(): SubscriptionStateMachineV2.SubscriptionState {
        return subscriptionStateMachine.getCurrentState()
    }

    fun getSubscriptionStateLiveData(): LiveData<SubscriptionStateMachineV2.SubscriptionState> {
        return subscriptionStateMachine.currentState.asLiveData()
    }

    fun getSubscriptionStateFlow(): StateFlow<SubscriptionStateMachineV2.SubscriptionState> {
        return subscriptionStateMachine.currentState
    }

    private fun logEvent(eventType: EventType, severity: Severity, msg: String, details: String) {
        eventLogger.log(eventType, severity, msg, EventSource.PROXY, false, details)
    }
}
