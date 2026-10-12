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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import android.app.Activity
import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.AcknowledgePurchaseParams
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
import com.celzero.bravedns.rpnproxy.RpnProxyManager.getEntitlementDetails
import com.celzero.bravedns.rpnproxy.RpnProxyManager.getExpiryFromPayload
import com.celzero.bravedns.rpnproxy.RpnProxyManager.storeWinEntitlement
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

object InAppBillingHandler : KoinComponent {

    private lateinit var billingClient: BillingClient

    private val billingListeners = java.util.concurrent.CopyOnWriteArraySet<BillingListener>()

    private val billingListener: BillingListener = object : BillingListener {
        override fun onConnectionResult(isSuccess: Boolean, message: String) =
            billingListeners.forEach { it.onConnectionResult(isSuccess, message) }
        override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) =
            billingListeners.forEach { it.purchasesResult(isSuccess, purchaseDetailList) }
        override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) =
            billingListeners.forEach { it.productResult(isSuccess, productList) }
        override fun purchaseInProgress() =
            billingListeners.forEach { it.purchaseInProgress() }
    }

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
    const val REVOKE_WINDOW_ONE_TIME_5YRS_DAYS = 4 * 7

    const val MONEYBACK_WINDOW_DAYS = 31

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
     * Emits when Google Play returns ITEM_ALREADY_OWNED and the purchase is being
     * silently restored. The hosting UI observes this to dismiss
     * the "Processing" sheet and show a "restoring your purchase…" acknowledgement,
     * otherwise the user sees no feedback at all.
     */
    private val _itemAlreadyOwnedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val itemAlreadyOwnedFlow: SharedFlow<Unit> = _itemAlreadyOwnedFlow.asSharedFlow()

    /**
     * Emits when Google Play services are interrupted or a fatal / non-recoverable billing
     * error occurs (SERVICE_DISCONNECTED during a purchase, SERVICE_UNAVAILABLE,
     * BILLING_UNAVAILABLE, DEVELOPER_ERROR, etc.). The hosting UI observes this to surface a
     * user-friendly error instead of only a transient toast. The emitted
     * value is the BillingResponseCode (0 when no code is available / to reset).
     *
     * Defined on both the play and website flavors so [RethinkPlusFragment] stays in parity
     * across build targets (both use Google Play Billing).
     */
    private val _playServicesInterruptedFlow = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val playServicesInterruptedFlow: SharedFlow<Int> = _playServicesInterruptedFlow.asSharedFlow()

    /**
     * Sticky, process-wide record of the last unresolved acknowledgement / verification
     * failure (ServerAckPending, Error after a real purchase flow, PendingTimeout,
     * AcknowledgementFailed). Unlike the per-fragment [RethinkPlusViewModel.lastUnresolved],
     * this lives on the singleton billing handler so it survives the destruction of
     * [RethinkPlusFragment] and is visible to [RethinkPlusDashboardFragment] which uses a
     * different (or no) RethinkPlusViewModel instance. The dashboard observes this to show a
     * persistent acknowledgement-failure banner.
     */
    private val _ackFailureFlow = MutableStateFlow<AckFailureInfo?>(null)
    val ackFailureFlow: StateFlow<AckFailureInfo?> = _ackFailureFlow.asStateFlow()

    /** Sets / clears the sticky acknowledgement-failure record observed by the dashboard. */
    fun setAckFailureState(info: AckFailureInfo?) {
        _ackFailureFlow.value = info
    }

    /**
     * Re-verify Play + server status after an acknowledgement / transaction failure
     * Clears the sticky failure record and re-queries Google Play so the
     * purchase processors can retry the server ack. Unlike the per-fragment
     * [RethinkPlusViewModel.reverifyAfterFailure], this works from the dashboard because it
     * lives on the singleton handler and needs no live ViewModel context. The [callback] is
     * invoked on completion (true when the failure record was cleared, i.e. the re-query
     * resolved the condition; false otherwise).
     */
    fun reverifyAfterFailure(callback: ((success: Boolean) -> Unit)? = null) {
        if (!isBillingClientSetup()) {
            logd("reverifyAfterFailure", "billing client not setup; cannot re-verify")
            callback?.invoke(false)
            return
        }
        // Clear the sticky record so the banner hides immediately; the state machine re-publishes
        // via setAckFailureState if the verification still fails.
        _ackFailureFlow.value = null
        billingScope.launch {
            try {
                subscriptionStateMachine.systemCheck()
                refreshPurchasesAwait(listOf(ProductType.SUBS, ProductType.INAPP))
                val resolved = _ackFailureFlow.value == null
                callback?.invoke(resolved)
            } catch (e: Exception) {
                loge("reverifyAfterFailure", "re-verify failed: ${e.message}", e)
                callback?.invoke(false)
            }
        }
    }

    /**
     * Emits whenever an INAPP (one-time) purchase is successfully processed by the billing
     * listener. Unlike [purchasesLiveData], this SharedFlow always fires even for Active →
     * Active transitions that occur during extend-mode purchases where the subscription state
     * does not change.
     */
    private val _oneTimePurchaseCompletedFlow = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val oneTimePurchaseCompletedFlow: SharedFlow<Unit> = _oneTimePurchaseCompletedFlow.asSharedFlow()

    private val subscriptionStateMachine: SubscriptionStateMachineV2 by inject()

    private val billingScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Logger.e(LOG_IAB, "$TAG unhandled billing coroutine error: ${t.message}", t as? Exception ?: RuntimeException(t))
        }
    )
    private val purchaseFlowTimeoutJob = AtomicReference<Job?>(null)

    private fun schedulePurchaseFlowTimeout() {
        purchaseFlowTimeoutJob.getAndSet(billingScope.launch {
            delay(SubscriptionStateMachineV2.PURCHASE_INITIATED_STUCK_TIMEOUT_MS)
            try {
                subscriptionStateMachine.recoverStuckPurchaseIfAny()
            } catch (e: Exception) {
                loge("purchaseFlowTimeout", "stuck-purchase recovery failed: ${e.message}", e)
            }
        })?.cancel()
    }

    private fun cancelPurchaseFlowTimeout() {
        purchaseFlowTimeoutJob.getAndSet(null)?.cancel()
    }

    // Guards "at most one connection attempt is in flight at a time". Replaces the
    // former connectionMutex, which was held across the (unbounded) Play connection
    // callback and force-unlocked after a 60s safety-timeout by a coroutine that did
    // NOT own it. kotlinx.coroutines.sync.Mutex.unlock() without an owner releases the
    // lock regardless of holder, so under the very race the timeout was meant to fix
    // (late callback + a second connection attempt), a later caller's lock could be
    // released by an earlier caller's unlock() - corrupting mutual exclusion.
    //
    // This flag is set atomically when a connection attempt starts and cleared exactly
    // once when the attempt settles (success / failure / disconnect / 60s watchdog).
    // No lock is held across the Play callback, so cancel/revoke are never blocked by a
    // stuck connection and there is nothing to "force-unlock".
    private val connecting = AtomicBoolean(false)

    // Serializes queryProductDetails() so concurrent calls (e.g. onBillingSetupFinished
    // callback + purchaseSubs on-demand fetch) cannot interleave the clear and async
    // repopulate of productDetails / storeProductDetails.
    private val productCacheMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Most severe Google Play Billing response code observed during the last
     * [queryProductDetails] / [queryProductDetailsWithTimeout] run. Read by
     * [com.celzero.bravedns.viewmodel.RethinkPlusViewModel.onProductsFetched] to surface a
     * specific failure reason (timeout, empty response, billing unavailable, etc.) instead
     * of a generic error.
     *
     * Volatile + assigned on the same coroutine that notifies [billingListener] of the
     * product result, so it is already populated by the time the listener (and hence the
     * ViewModel) reads it.
     */
    @Volatile
    var lastProductQueryResponseCode: Int = BillingResponseCode.OK

    // state tracking. Idempotency guard for initiate(): compareAndSet guarantees
    // startStateObserver() and subscriptionStateMachine.initialize() run exactly once
    // even if initiate() is invoked concurrently (Dispatchers.IO is a pool); a plain
    // @Volatile check-then-set would let both callers register a second state observer.
    private val isInitialized = AtomicBoolean(false)

    /**
     * Guards [setupProcessors] against concurrent double-creation (e.g. the "already
     * connected" fast-path racing the onBillingSetupFinished callback). The atomic
     * compare-and-set guarantees only the first caller performs the allocation.
     */
    private val isSetupProcessorsDone = AtomicBoolean(false)


    private const val EMPTY_QUERY_THRESHOLD = 3
    internal const val EMPTY_QUERY_WINDOW_MS = 45 * 60 * 1000L
    @Volatile private var consecutiveEmptyInAppQueries = 0

    /**
     * How many times a token must be seen still-unacknowledged before we stop waiting
     * for Play to propagate the ack and escalate to server-side acknowledgement.
     */
    const val UNACK_ESCALATION_THRESHOLD = 3

    /**
     * Delay before the one-shot background retry of a failed server acknowledgement
     * from the [SubscriptionStateMachineV2.SubscriptionState.ServerAckPending] state
     * Chosen within the 5–10 minute window
     */
    const val SERVER_ACK_RETRY_DELAY_MS = 5 * 60 * 1000L

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

    private fun logw(methodName: String, msg: String) {
        Logger.w(LOG_IAB, "$TAG $methodName: $msg")
    }

    private fun log(methodName: String, msg: String) {
        Logger.i(LOG_IAB, "$TAG $methodName: $msg")
    }

    fun initiate(context: Context, billingListener: BillingListener? = null) {
        val mname = this::initiate.name
        billingListener?.let { billingListeners.add(it) }
        if (appContext == null) {
            appContext = context.applicationContext
        }

        // initialize billing client
        setupBillingClient(context)

        // Serialize state machine init and billing connection in a single
        // coroutine so the connection (and subsequent purchase reconciliation)
        // never runs before the machine has transitioned out of Uninitialized.
        billingScope.launch {
            // compareAndSet guarantees initialize() + startStateObserver() execute exactly
            // once even if initiate() is called concurrently from two coroutines
            // (Dispatchers.IO is a pool). A plain @Volatile check-then-set would let both
            // callers pass the guard and register a SECOND long-lived state observer.
            if (isInitialized.compareAndSet(false, true)) {
                try {
                    subscriptionStateMachine.initialize()
                    startStateObserver()
                    logd(mname, "state machine initialized")
                } catch (e: Exception) {
                    // roll back so a later initiate() can retry
                    isInitialized.set(false)
                    loge(mname, "failed to initialize state machine: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        this@InAppBillingHandler.billingListener?.onConnectionResult(false, "State machine initialization failed: ${e.message}")
                    }
                    return@launch
                }
            }

            startConnection { isSuccess, message ->
                if (isSuccess) {
                    log(mname, "billing connected, fetching initial state")
                    resetEmptySubsWindow()
                    consecutiveEmptyInAppQueries = 0
                    connectionAutoRetryUsed.set(false)
                    fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                } else {
                    loge(mname, "billing connection failed: $message")
                    maybeRetryInitialConnection(message)
                }
                this@InAppBillingHandler.billingListener?.onConnectionResult(isSuccess, message)
            }
        }
    }

    /** Guards the one automatic connection retry in [maybeRetryInitialConnection]. */
    private val connectionAutoRetryUsed = AtomicBoolean(false)
    private fun maybeRetryInitialConnection(message: String) {
        if (message == "Connection already in progress") return
        if (!connectionAutoRetryUsed.compareAndSet(false, true)) {
            logd("maybeRetryInitialConnection", "auto-retry already used; waiting for the next worker/UI initiate")
            return
        }
        billingScope.launch {
            delay(10_000L.milliseconds)
            logw("maybeRetryInitialConnection", "retrying billing connection after failure: $message")
            try {
                startConnection { isSuccess, msg ->
                    if (isSuccess) connectionAutoRetryUsed.set(false)
                    this@InAppBillingHandler.billingListener?.onConnectionResult(isSuccess, msg)
                }
            } catch (e: Exception) {
                loge("maybeRetryInitialConnection", "connection retry failed: ${e.message}", e)
            }
        }
    }

    fun registerListener(billingListener: BillingListener?) {
        val mname = this::registerListener.name
        if (billingListener != null) {
            billingListeners.add(billingListener)
            log(mname, "listener registered (total=${billingListeners.size})")
        }
    }

    fun unregisterListener(billingListener: BillingListener?) {
        val mname = this::unregisterListener.name
        if (billingListener != null && billingListeners.remove(billingListener)) {
            log(mname, "listener unregistered (remaining=${billingListeners.size})")
        } else {
            logd(mname, "unregisterListener: listener null or not registered")
        }
    }

    private fun setupBillingClient(context: Context) {
        if (isBillingClientSetup()) {
            logd(this::setupBillingClient.name, "billing client is already setup, skipping setup")
            return
        }
        val mname = this::setupBillingClient.name
        // If a previous BillingClient exists but is no longer ready (e.g. after a
        // disconnect), end its connection before replacing it. Otherwise the orphaned
        // client can stay connected and keep delivering updates through the shared
        // purchasesUpdatedListener, causing the same purchase to be processed twice.
        if (::billingClient.isInitialized) {
            try { billingClient.endConnection() } catch (e: Exception) {
                loge(mname, "failed to end previous billing client: ${e.message}", e)
            }
        }
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
        if (!isBillingClientSetup() || !billingClient.isReady) {
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
        logv(mname, "in-app messaging setup complete")
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
        val isRegistered = l != null && billingListeners.contains(l)
        log(mname, "is listener registered? $isRegistered")
        return isRegistered
    }

    private fun startConnection(callback: (isSuccess: Boolean, message: String) -> Unit) {
        val mname = this::startConnection.name
        logv(mname, "starting billing connection")

        // Dedup: at most one connection attempt in flight. This flag replaces the former
        // connectionMutex that was held across the unbounded Play callback and
        // force-unlocked after 60s (see the comment on `connecting`). No lock is held
        // across the callback, so cancel/revoke are never blocked by a stuck connection.
        if (!connecting.compareAndSet(false, true)) {
            logd(mname, "connection attempt already in progress")
            billingScope.launch(Dispatchers.Main) {
                callback.invoke(false, "Connection already in progress")
            }
            return
        }

        // Settle the attempt exactly once across the success / disconnect / error /
        // watchdog paths: cancel the watchdog, clear the dedup flag, and invoke the UI
        // callback on the Main dispatcher. `settled` (per-attempt) guarantees the user
        // callback fires exactly once even if a late Play callback arrives after the
        // watchdog has already settled.
        val settled = AtomicBoolean(false)
        val settle: (Boolean, String) -> Unit = { success, message ->
            if (settled.compareAndSet(false, true)) {
                connecting.set(false)
                billingScope.launch(Dispatchers.Main) {
                    callback.invoke(success, message)
                }
            }
        }

        // Watchdog: if Play never invokes onBillingSetupFinished /
        // onBillingServiceDisconnected within 60s, settle the attempt as failed so the
        // dedup flag is cleared and a subsequent startConnection() can proceed. Unlike
        // the old safety-timeout, this does NOT release a lock it does not own.
        val watchdog = billingScope.launch {
            delay(60_000L.milliseconds)
            logd(mname, "connection watchdog fired after 60s (no Play callback)")
            settle(false, "Connection timed out")
        }

        billingScope.launch {
            try {
                // check if already connected
                if (::billingClient.isInitialized && billingClient.isReady) {
                    logd(mname, "billing already connected")
                    if (!::queryUtils.isInitialized) {
                        queryUtils = QueryUtils(billingClient)
                    }
                    setupProcessors()
                    watchdog.cancel()
                    settle(true, "Already connected")
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
                            } else {
                                loge(mname, "billing connection failed: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                            }

                            watchdog.cancel()
                            settle(isOk, if (isOk) "Connected" else billingResult.debugMessage)
                        }

                        override fun onBillingServiceDisconnected() {
                            log(mname, "billing service disconnected")

                            // a disconnect that happens while a purchase/ack is
                            // in flight strands the user on a spinner with no explanation. Emit
                            // an interruption signal so the UI can show a friendly
                            // "Google Play unavailable" error. Routine disconnects (auto-reconnect
                            // enabled) are handled silently by the reconnect path below.
                            if (subscriptionStateMachine.currentMachineState()
                                is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated ||
                                subscriptionStateMachine.currentMachineState()
                                is SubscriptionStateMachineV2.SubscriptionState.PurchasePending ||
                                subscriptionStateMachine.currentMachineState()
                                is SubscriptionStateMachineV2.SubscriptionState.ServerAckPending) {
                                _playServicesInterruptedFlow.tryEmit(
                                    com.android.billingclient.api.BillingClient
                                        .BillingResponseCode.SERVICE_DISCONNECTED
                                )
                            }

                            // Keep the cached product details. Clearing them
                            // here left RethinkPlusFragment showing an empty product list and a
                            // loading shimmer that never resolved during the disconnect→reconnect
                            // window (auto-reconnect is enabled, so a fresh fetch replaces these
                            // once the connection is re-established with fresh data).
                            // storeProductDetails / productDetails are intentionally NOT cleared.

                            // notify state machine of disconnection
                            billingScope.launch {
                                try {
                                    subscriptionStateMachine.systemCheck()
                                    logv(mname, "service disconnect, perform state-mac system check")
                                } catch (e: Exception) {
                                    loge(mname, "err during state-mac system check on disconnect: ${e.message}", e)
                                }
                            }

                            watchdog.cancel()
                            settle(false, "Service disconnected")
                        }
                    })
                }
            } catch (e: Exception) {
                loge(mname, "err starting billing connection: ${e.message}", e)
                watchdog.cancel()
                settle(false, "Connection error: ${e.message}")
            }
        }
    }

    private val purchasesUpdatedListener: PurchasesUpdatedListener =
        PurchasesUpdatedListener { billingResult, purchasesList ->
            val mname = this::purchasesUpdatedListener.name
            logd(mname, "purchase update, code: ${billingResult.responseCode}, msg: ${billingResult.debugMessage}")

            // Any Play callback ends the purchase-flow attempt; stop the abandonment timer.
            cancelPurchaseFlowTimeout()

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
                                        purchasesList
                                            ?.filter { it.purchaseToken in newTokens }
                                            ?.forEach { purchase ->
                                                val accountId = purchase.accountIdentifiers?.obfuscatedAccountId
                                                var isRegistered = false

                                                if (accountId.isNullOrBlank()) {
                                                    loge(mname, "obfuscatedAccountId missing from purchase ${purchase.purchaseToken.take(8)}; skipping registerDevice")
                                                    return@forEach
                                                }
                                                var deviceId: String? = billingBackendClient
                                                    .getDeviceIdForCid(accountId).ifBlank { null }

                                                if (deviceId == null) {
                                                    val didResult = billingBackendClient.reconcileDidForCid(accountId)
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

                            logPurchaseFunnelEvent(
                                "completed",
                                "count=${purchasesList?.size ?: 0}"
                            )

                            // Emit so extend-mode observers can detect INAPP purchase success
                            // even when the subscription state machine stays in Active (no StateFlow
                            // re-emission for same-state Active → Active transitions).
                            if (purchasesList?.any { getProductType(it) == ProductType.INAPP } == true) {
                                _oneTimePurchaseCompletedFlow.tryEmit(Unit)
                            }
                        }

                        response.isAlreadyOwned -> {
                            log(mname, "item already owned; restoring subscription")
                            // notify the UI so the "Processing" sheet is
                            // dismissed and the user sees a restore acknowledgement instead of
                            // silent no-op feedback.
                            _itemAlreadyOwnedFlow.tryEmit(Unit)
                            logPurchaseFunnelEvent("item_already_owned")
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
                            logPurchaseFunnelEvent("failed", "cancelled")
                            // post to livedata so ui can dismiss bottom sheet
                            transactionErrorLiveData.postValue(billingResult)
                            try {
                                logd(mname, "user dismissed Play purchase sheet; recording flow-cancel history only")
                                subscriptionStateMachine.purchaseFlowCancelled()
                            } catch (e: Exception) {
                                loge(mname, "err notifying cancellation: ${e.message}", e)
                            }
                        }

                        isDuplicatePurchaseAttempt(
                            billingResult.responseCode,
                            billingResult.debugMessage
                        ) -> {
                            log(mname, "duplicate purchase response; keeping the current purchase flow active")
                        }

                        response.isTerribleFailure || response.isNonrecoverableError -> {
                            loge(mname, "fatal billing error: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                            logPurchaseFunnelEvent("failed", "fatal=${billingResult.responseCode}")
                            // post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            // surface a user-friendly Play-services error in the UI.
                            _playServicesInterruptedFlow.tryEmit(billingResult.responseCode)
                            subscriptionStateMachine.purchaseFailed(
                                "Fatal error: ${billingResult.debugMessage}",
                                billingResult.responseCode
                            )
                        }

                        response.serviceDisconnected -> {
                            // a SERVICE_DISCONNECTED purchase response is
                            // transient and resolved silently by auto-reconnect. Treating it as
                            // a (recoverable) error previously surfaced a misleading
                            // "Recoverable error" toast; treating it as unknown shows a generic
                            // error. Do neither — the in-flight purchase resolves on reconnect.
                            log(mname, "service disconnected during purchase update; will resolve on reconnect")
                        }

                        response.isRecoverableError -> {
                            log(mname, "recoverable billing error: ${billingResult.debugMessage}")
                            logPurchaseFunnelEvent("failed", "recoverable=${billingResult.responseCode}")
                            // post to LiveData so UI can dismiss bottom sheet and show error
                            transactionErrorLiveData.postValue(billingResult)
                            subscriptionStateMachine.purchaseFailed(
                                "Recoverable error: ${billingResult.debugMessage}",
                                billingResult.responseCode
                            )
                        }

                        else -> {
                            loge(mname, "unknown billing error: ${billingResult.responseCode}")
                            logPurchaseFunnelEvent("failed", "unknown=${billingResult.responseCode}")
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

    /** Tracks the start of an empty-SUBS expiry window. */
    private fun registerEmptySubsWindow(mname: String): Boolean {
        val now = System.currentTimeMillis()
        val first = persistentState.iabEmptySubsFirstSeenTs
        return when {
            first == 0L -> {
                persistentState.iabEmptySubsFirstSeenTs = now
                logd(mname, "empty-SUBS snapshot: window opened (expires in ${EMPTY_QUERY_WINDOW_MS}ms)")
                false
            }
            now - first >= EMPTY_QUERY_WINDOW_MS -> {
                persistentState.iabEmptySubsFirstSeenTs = 0L
                logd(mname, "empty-SUBS snapshot: window expired (${now - first}ms >= ${EMPTY_QUERY_WINDOW_MS}ms); expiry path armed")
                true
            }
            else -> {
                logd(mname, "empty-SUBS snapshot: window still open (${now - first}ms < ${EMPTY_QUERY_WINDOW_MS}ms); ignoring")
                false
            }
        }
    }

    private fun resetEmptySubsWindow() {
        persistentState.iabEmptySubsFirstSeenTs = 0L
    }

    private val purchasesInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Processes each Play purchase token at most once concurrently. */
    private suspend fun handlePurchase(
        purchasesList: List<Purchase>?,
        queriedProductType: String? = null
    ) {
        // play may deliver the same purchase twice in one callback
        val normalized = purchasesList?.distinctBy { it.purchaseToken } ?: emptyList()
        if (normalized.isEmpty()) {
            handlePurchaseInternal(normalized, queriedProductType)
            return
        }
        val claimed = normalized.filter { purchasesInFlight.add(it.purchaseToken) }
        if (claimed.isEmpty()) {
            Logger.d(LOG_IAB, "$TAG handlePurchase: all ${normalized.size} token(s) already in flight; skipping concurrent duplicate")
            return
        }
        if (queriedProductType == null) {
            unackedSeenCount.keys.retainAll(claimed.map { it.purchaseToken }.toSet())
        }
        try {
            handlePurchaseInternal(claimed, queriedProductType)
        } finally {
            claimed.forEach { purchasesInFlight.remove(it.purchaseToken) }
        }
    }

    private suspend fun handlePurchaseInternal(
        purchasesList: List<Purchase>?,
        queriedProductType: String? = null
    ) {
        val mname = "handlePurchase"
        val normalized = purchasesList ?: emptyList()
        logd(mname, "processing ${normalized.size} play purchases (prdType=$queriedProductType)")

        if (normalized.isEmpty()) {
            // purchasesUpdatedListener never delivers an empty list, so queriedProductType is
            // always non-null here (set by queryPurchases). Guard defensively anyway.
            if (queriedProductType == null) return

            // google play can return empty purchase lists on some devices (Play Services issue,
            // network flake, etc.). single empty response must never expire an active purchase.
            if (queriedProductType == ProductType.SUBS) {
                if (!registerEmptySubsWindow(mname)) {
                    return
                }
                logd(mname, "SUBS query returned empty beyond the expiry window, reconcile...")

                // reset so repeated polls don't re-fire the reconcile path every iteration.
                resetEmptySubsWindow()

                // Before expiring, confirm from the server that no active SUBS purchase
                // still has a valid entitlement. This prevents expiring valid purchases when
                // Play returns empty due to network issues or Play Services glitches.
                val preservation = validateSubsWithServerBeforeExpiry() ?: return
                subscriptionStateMachine.reconcileWithPlayBilling(
                    purchases = emptyList(),
                    queriedProductType = queriedProductType,
                    shouldExpire = true,
                    preservedTokens = preservation.tokens,
                    preservedRowIds = preservation.rowIds,
                    authoritativelyExpiredRowIds = preservation.expiredRowIds
                )
            } else {
                consecutiveEmptyInAppQueries++
                if (consecutiveEmptyInAppQueries < EMPTY_QUERY_THRESHOLD) {
                    logd(mname, "INAPP, empty purchase, ignoring (consecutive=$consecutiveEmptyInAppQueries/$EMPTY_QUERY_THRESHOLD)")
                    queryPurchases(queriedProductType, false, purchaseRefreshGeneration.get())
                    return
                }
                logd(mname, "INAPP query returned empty, threshold reached ($consecutiveEmptyInAppQueries/$EMPTY_QUERY_THRESHOLD), confirm with server...")

                consecutiveEmptyInAppQueries = 0

                // Before expiring, confirm from the server that no active INAPP purchase
                // still has a valid entitlement. this can happen when the user chose to
                // purchase one time before 30 day of expiry, to purchase one-time the
                // prev purchase should be consumed before the purchase.
                val serverConfirmedValidTokens = mutableSetOf<String>()
                val serverConfirmedValidRowIds = mutableSetOf<Int>()
                try {
                    val activeInAppRows = subscriptionStateMachine.getActiveInAppPurchase()
                    logd(mname, "INAPP empty-threshold: checking ${activeInAppRows.size} active INAPP row(s) against server")
                    if (activeInAppRows.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        fun preserve(sub: SubscriptionStatus) {
                            if (sub.purchaseToken.isNotBlank()) {
                                serverConfirmedValidTokens.add(sub.purchaseToken)
                            }
                            serverConfirmedValidRowIds.add(sub.id)
                        }
                        for (sub in activeInAppRows) {
                            try {
                                val billingKnownExpired = sub.billingExpiry > 0L &&
                                    sub.billingExpiry != Long.MAX_VALUE &&
                                    sub.billingExpiry <= now
                                if (sub.purchaseToken.isBlank()) {
                                    logd(mname, "INAPP row id=${sub.id} has blank token; fail-safe keep")
                                    if (!billingKnownExpired) preserve(sub)
                                    continue
                                }
                                val effectiveAccountId = sub.accountId.ifEmpty { billingBackendClient.peekStoredCid() }
                                val deviceId = billingBackendClient.getDeviceIdForCid(effectiveAccountId)
                                if (deviceId.isBlank()) {
                                    loge(mname, "INAPP row id=${sub.id}: did unavailable for cid=${effectiveAccountId.take(8)}; fail-safe keep")
                                    if (!billingKnownExpired) preserve(sub)
                                    continue
                                }
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
                                val result = try {
                                    billingBackendClient.queryEntitlement(
                                        effectiveAccountId,
                                        deviceId,
                                        purchaseDetailForQuery,
                                        sub.purchaseToken
                                    )
                                } catch (serverEx: Exception) {
                                    loge(mname, "server entitlement check failed for token=${sub.purchaseToken.take(8)}: ${serverEx.message}", serverEx)
                                    if (!billingKnownExpired) preserve(sub)
                                    continue
                                }

                                when (result) {
                                    is QueryEntitlementResult.Expired -> {
                                        logd(mname, "INAPP token=${sub.purchaseToken.take(8)}: server authoritatively confirms expired")
                                    }
                                    is QueryEntitlementResult.Unauthorized -> {
                                        handleUnauthorized401(
                                            ServerApiError.Operation.ACKNOWLEDGE,
                                            result.accountId,
                                            result.deviceId
                                        )
                                        if (!billingKnownExpired) preserve(sub)
                                    }
                                    is QueryEntitlementResult.Conflict -> {
                                        handleConflict409(
                                            ServerApiError.Operation.ACKNOWLEDGE,
                                            effectiveAccountId,
                                            deviceId,
                                            sub.purchaseToken,
                                            skuForType(ProductType.INAPP),
                                            "Conflict: 409"
                                        )
                                        if (!billingKnownExpired) preserve(sub)
                                    }
                                    is QueryEntitlementResult.Transient -> {
                                        if (!billingKnownExpired) preserve(sub)
                                    }
                                    is QueryEntitlementResult.Failure -> {
                                        val linkedToken = result.linkedPurchaseId
                                        if (!linkedToken.isNullOrBlank()) {
                                            try {
                                                RpnProxyManager.tryReactivateLinkedPurchase(
                                                    effectiveAccountId,
                                                    deviceId,
                                                    linkedToken
                                                )
                                            } catch (linkedEx: Exception) {
                                                loge(mname, "linked purchase reactivation failed: ${linkedEx.message}", linkedEx)
                                            }
                                        }
                                        if (!billingKnownExpired) preserve(sub)
                                    }
                                    is QueryEntitlementResult.Success -> {
                                        val updatedDetail = result.purchase
                                        val serverAnswered = updatedDetail.payload.isNotEmpty() ||
                                            updatedDetail.expiryTime > 0L
                                        if (!serverAnswered) {
                                            if (!billingKnownExpired) preserve(sub)
                                            continue
                                        }

                                        if (updatedDetail.payload.isNotEmpty() &&
                                            updatedDetail.payload != sub.developerPayload
                                        ) {
                                            logd(mname, "INAPP token=${sub.purchaseToken.take(8)}: payload updated from server, syncing...")
                                            subscriptionStateMachine.paymentSuccessful(updatedDetail)
                                        }

                                        val tunnelExpiry = getExpiryFromPayload(updatedDetail.payload) ?: 0L
                                        val serverBillingExpiryFuture =
                                            updatedDetail.expiryTime > now &&
                                                updatedDetail.expiryTime != Long.MAX_VALUE
                                        if (tunnelExpiry > now || serverBillingExpiryFuture || !billingKnownExpired) {
                                            preserve(sub)
                                        } else {
                                            logd(mname, "INAPP token=${sub.purchaseToken.take(8)}: server session and local billing window expired")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                loge(mname, "unexpected error checking INAPP entitlement for id=${sub.id}: ${e.message}", e)
                                val billingKnownExpired = sub.billingExpiry > 0L &&
                                    sub.billingExpiry != Long.MAX_VALUE &&
                                    sub.billingExpiry <= now
                                if (!billingKnownExpired) preserve(sub)
                            }
                        }
                    }
                } catch (outerEx: Exception) {
                    loge(mname, "error fetching active INAPP rows before expiry: ${outerEx.message}", outerEx)
                    // Fail-safe: if we couldn't even read the DB rows, abort the expiry path
                    // entirely by treating all tokens as valid.
                    return
                }
                logd(mname, "INAPP empty-threshold: serverConfirmedValidTokens=${serverConfirmedValidTokens.size}")
                subscriptionStateMachine.expireStaleInAppFromDb(
                    playTokens = serverConfirmedValidTokens,
                    preservedRowIds = serverConfirmedValidRowIds
                )
            }

            val currentState = subscriptionStateMachine.currentMachineState()
            if (currentState == SubscriptionStateMachineV2.SubscriptionState.PurchasePending) {
                // Only mark as failed if the pending purchase type MATCHES the queried type.
                // An empty SUBS result must NOT fail an INAPP (one-time) purchase that is
                // waiting for acknowledgement and there is no subscription to find in Play,
                // which is the expected state after a one-time purchase.
                val pendingProductId = subscriptionStateMachine.getSubscriptionData()
                    ?.subscriptionStatus?.productId ?: ""
                val pendingIsInApp = SubscriptionStateMachineV2.isInAppProduct(pendingProductId)
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

        // Reset only the counter for the type that returned a non-empty result.
        when (queriedProductType) {
            ProductType.SUBS  -> {
                logd(mname, "non-empty SUBS response, size=${purchasesList?.size}; resetting SUBS empty window")
                resetEmptySubsWindow()
            }
            ProductType.INAPP -> {
                logd(mname, "non-empty INAPP response, size=${purchasesList?.size}; resetting INAPP empty counter (was $consecutiveEmptyInAppQueries)")
                consecutiveEmptyInAppQueries = 0
            }
            else -> {
                // queriedProductType unknown (e.g. called from purchasesUpdatedListener);
                // infer the types present and only reset those counters.
                val hasSubs = normalized.any { getProductType(it, null) == ProductType.SUBS }
                val hasInApp = normalized.any { getProductType(it, null) == ProductType.INAPP }
                if (hasSubs) {
                    logd(mname, "non-empty SUBS (inferred), size=${purchasesList?.size}; resetting SUBS empty window")
                    resetEmptySubsWindow()
                }
                if (hasInApp) {
                    logd(mname, "non-empty INAPP (inferred), size=${purchasesList?.size}; resetting INAPP empty counter (was $consecutiveEmptyInAppQueries)")
                    consecutiveEmptyInAppQueries = 0
                }
                if (!hasSubs && !hasInApp) {
                    // No recognizable purchase types; reset both defensively.
                    logd(mname, "non-empty response, size=${purchasesList?.size}; resetting both empty counters")
                    resetEmptySubsWindow()
                    consecutiveEmptyInAppQueries = 0
                }
            }
        }

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
            resetEmptySubsWindow()

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

            // Clean up unacked-seen counters for purchases that Play has finally
            // acknowledged, so the ConcurrentHashMap does not grow unbounded.
            ackedSubs.forEach { purchase ->
                unackedSeenCount.remove(purchase.purchaseToken)
            }

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
            if (registerEmptySubsWindow(mname)) {
                logd(mname, "No SUBS in purchase list despite SUBS query;" +
                    "empty window elapsed, " +
                    "expiring stale SUBS DB rows")
                resetEmptySubsWindow()
                try {
                    // Before expiring, confirm from the server that no active SUBS purchase
                    // still has a valid entitlement. This prevents expiring valid purchases when
                    // Play returns empty due to network issues or Play Services glitches.
                    val preservation = validateSubsWithServerBeforeExpiry()
                    if (preservation == null) {
                        logd(mname, "server validation could not read active SUBS rows; skipping expiry")
                    } else {
                        subscriptionStateMachine.reconcileWithPlayBilling(
                            purchases = emptyList(),
                            queriedProductType = ProductType.SUBS,
                            shouldExpire = true,
                            preservedTokens = preservation.tokens,
                            preservedRowIds = preservation.rowIds,
                            authoritativelyExpiredRowIds = preservation.expiredRowIds
                        )
                    }
                } catch (e: Exception) {
                    loge(mname, "Error expiring stale SUBS rows: ${e.message}", e)
                }
            } else {
                logd(mname, "No SUBS in purchase list despite SUBS query;" +
                    "ignoring (empty window still open)")
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
        // Atomic guard so concurrent callers (e.g. the "already connected" fast-path
        // racing the onBillingSetupFinished callback) cannot double-create the processors.
        if (!isSetupProcessorsDone.compareAndSet(false, true)) {
            logd(this::setupProcessors.name, "setupProcessors already done, skipping")
            return
        }
        val activateRpnFn: suspend (PurchaseDetail) -> Unit = { pd ->
            RpnProxyManager.activateRpn(pd)
        }

        val getProductTypeFn: (Purchase) -> String = { p -> getProductType(p) }

        val getDeviceIdFn: suspend (cid: String) -> String = { cid ->
            billingBackendClient.didForCid(cid, mintIfMissing = false).deviceId
        }

        val mintDeviceIdFn: suspend (cid: String) -> String = { cid ->
            val res = billingBackendClient.didForCid(cid, mintIfMissing = true)
            if (res.deviceId.isBlank()) {
                scheduleAckRetryAndAlert(cid, alert = isDefinitiveDidFailure(res.errorCode))
            }
            res.deviceId
        }

        val onInAppAckSuccessFn: suspend (Purchase) -> Unit = { purchase ->
            // accountId for an existing purchase always comes from the play purchase.
            val accountId = purchase.accountIdentifiers?.obfuscatedAccountId
            if (accountId.isNullOrBlank()) {
                loge("onInAppAckSuccess", "obfuscatedAccountId missing from INAPP purchase ${purchase.purchaseToken.take(8)}; skipping registerDevice")
            } else {
                val deviceId = billingBackendClient.didForCid(accountId, mintIfMissing = true).deviceId
                if (deviceId.isBlank()) {
                    loge("onInAppAckSuccess", "did unavailable for cid=${accountId.take(8)}; skipping registerDevice")
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
            mintDeviceId = mintDeviceIdFn,
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
            mintDeviceId = mintDeviceIdFn,
            onAckSuccess = onInAppAckSuccessFn,
            reconcileCidDid = ::reconcileCidDidFromPurchase,
            validatePayloadAndFetchIfRequired = ::validatePayloadAndFetchIfRequired
        )
        logd("setupProcessors", "SubscriptionPurchaseProcessor and InAppPurchaseProcessor created")
    }

    /** (cid, did) for [pd]'s own cid; the stored identity when the row has no cid. */
    private suspend fun identityForPurchase(pd: PurchaseDetail): Pair<String, String> {
        if (pd.accountId.isBlank()) return fetchOrEnsureCustomerIds()
        return Pair(pd.accountId, billingBackendClient.getDeviceIdForCid(pd.accountId))
    }

    /** Returns true when the DID failure is definitive and should be surfaced to the user. */
    internal fun isDefinitiveDidFailure(errorCode: Int): Boolean =
        errorCode in 400..499 || errorCode == BillingBackendClient.DID_REBIND_REFUSED

    /**
     * Schedules a background retry for a missing DID and optionally alerts the user.
     */
    internal suspend fun scheduleAckRetryAndAlert(cid: String, alert: Boolean) {
        appContext?.let { ctx -> PendingPurchaseWorker.scheduleExpedited(ctx) }
        if (!alert) {
            logw("scheduleAckRetryAndAlert", "did unavailable for cid=${cid.take(8)} (transient); scheduled silent retry")
            return
        }
        val current = serverApiErrorLiveData.value
        if (current is ServerApiError.DeviceNotRegistered && current.entitlementCid == cid) return
        logw("scheduleAckRetryAndAlert", "did unavailable for cid=${cid.take(8)}; scheduled retry + device-not-registered alert")
        val storedCid = runCatching { billingBackendClient.peekStoredCid() }.getOrDefault("")
        serverApiErrorLiveData.postValue(ServerApiError.DeviceNotRegistered(cid, storedCid, ""))
    }

    private suspend fun validatePayloadAndFetchIfRequired(purchaseDtl: PurchaseDetail): PurchaseDetail {
        val mname = this::validatePayloadAndFetchIfRequired.name
        val now = System.currentTimeMillis()
        val tunnelExpiry: Long? = getExpiryFromPayload(purchaseDtl.payload)
        if (tunnelExpiry != null && tunnelExpiry > now) {
            return purchaseDtl
        }
        Logger.i(mname, "Payload validation failed or expired for token=${purchaseDtl.purchaseToken.take(8)}; fetching updated entitlement from server")
        val (cid, did) = identityForPurchase(purchaseDtl)
        return queryEntitlementFromServer(cid, did, purchaseDtl)
    }

    suspend fun reconcileCidDidFromPurchase(cid: String) {
        val mname = this::reconcileCidDidFromPurchase.name
        if (cid.isEmpty()) {
            logd(mname, "cid empty, skipping reconcile")
            return
        }
        logd(mname, "reconciling did for cid=${cid.take(8)}")
        val didResult = billingBackendClient.reconcileDidForCid(cid)
        when {
            didResult.isSuccess -> {
                logd(mname, "reconcile succeeded; did persisted by BillingBackendClient (len=${didResult.deviceId.length})")
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

    /**
     * Resets and re-claims the stored identity for [entitlementCid].
     * Resets registration state and schedules an immediate re-registration.
     *
     * @return true when the identity was successfully re-claimed.
     */
    suspend fun resetIdentityForRebind(entitlementCid: String): Boolean {
        val mname = "resetIdentityForRebind"
        return try {
            val claimed = billingBackendClient.resetAndClaimIdentity(entitlementCid)
            if (claimed) {
                persistentState.deviceRegistrationTimestamp = 0L
                persistentState.identityRecoveryAttemptTs = 0L
                logw(mname, "stored identities cleared and re-claimed for cid=${entitlementCid.take(8)}")
                scheduleRegistrationRecheck(mname)
            }
            claimed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loge(mname, "identity reset failed: ${e.message}", e)
            false
        }
    }

    suspend fun primaryPurchaseCid(): String {
        val mname = this::primaryPurchaseCid.name
        return try {
            val machineToken = try {
                subscriptionStateMachine.getSubscriptionData()?.subscriptionStatus?.purchaseToken
            } catch (e: Exception) {
                logd(mname, "machine data unavailable: ${e.message}")
                null
            }
            val primary = PrimaryPurchaseResolver.primaryPurchase(
                getActivePurchasesSnapshot(), machineToken)
            primary?.accountId.orEmpty()
        } catch (e: Exception) {
            loge(mname, "failed to resolve the primary purchase cid: ${e.message}", e)
            ""
        }
    }

    fun postDeviceNotRegisteredError(entitlementCid: String, didErrorCode: Int) {
        val mname = this::postDeviceNotRegisteredError.name
        if (entitlementCid.isBlank()) return
        if (!isDefinitiveDidFailure(didErrorCode)) {
            logd(mname, "transient did failure (code=$didErrorCode) for cid=${entitlementCid.take(8)}; not alerting")
            return
        }
        val current = serverApiErrorLiveData.value
        if (current is ServerApiError.DeviceNotRegistered && current.entitlementCid == entitlementCid) {
            logd(mname, "device-not-registered already active for cid=${entitlementCid.take(8)}")
            return
        }
        logw(mname, "definitive did failure (code=$didErrorCode) for cid=${entitlementCid.take(8)}; posting device-not-registered")
        billingScope.launch {
            val storedCid = runCatching { billingBackendClient.peekStoredCid() }.getOrDefault("")
            serverApiErrorLiveData.postValue(
                ServerApiError.DeviceNotRegistered(entitlementCid, storedCid, ""))
        }
    }

    /** Queues a one-shot registration re-check for the freshly claimed identity. */
    private fun scheduleRegistrationRecheck(caller: String) {
        val ctx = appContext ?: return
        try {
            val request = OneTimeWorkRequestBuilder<SubscriptionCheckWorker>().build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                SubscriptionCheckWorker.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        } catch (e: Exception) {
            logw(caller, "could not enqueue registration re-check: ${e.message}")
        }
    }

    suspend fun reconcilePurchase(force: Boolean = false): Boolean {
        val mname = this::reconcilePurchase.name
        val thresholdToCheckWinExpiryTs: Long = TimeUnit.DAYS.toMillis(10) // 10 days in ms
        // time when the entitlement reconciliation should be considered CONST
        val expiryDifferenceTs: Long = TimeUnit.DAYS.toMillis(30) // 30 days in ms

        val activePurchase = getActivePurchasesSnapshot()
        // reconcile only when entitlement expiry is ahead of windscribe expiry by at least
        // thresholdToCheckWinExpiryTs. Smaller differences are ignored by the server
        val entitlement = getEntitlementDetails()
        val entitlementExpiry = entitlement?.expiry()

        if (activePurchase.isEmpty()) {
            logd(mname, "no active purchase, skipping reconcile")
            return false
        }

        if (!force) {
            if (entitlementExpiry == null) {
                logd(mname, "missing entitlement expiry, skipping reconcile")
                return false
            }

            val windscribeExpiry = VpnController.getWinExpiryTs()

            if (windscribeExpiry == null) {
                logd(mname, "missing entitlement or win expiry, skipping reconcile")
                return false
            }

            if (windscribeExpiry > entitlementExpiry) {
                logd(mname, "win expiry is ahead of entitlement expiry, skipping reconcile")
                return false
            }

            // if the value of the win expiry is less than 30 days then start checking
            val now = System.currentTimeMillis()
            if (windscribeExpiry - now > expiryDifferenceTs) {
                logd(mname, "win expiry is beyond threshold, skipping reconcile")
                return false
            }

            if (entitlementExpiry - windscribeExpiry < thresholdToCheckWinExpiryTs) {
                logd(mname, "expiry gap is below reconciliation threshold, skipping reconcile")
                return false
            }
        } else {
            log(mname, "forced reconcile, bypassing threshold checks")
        }

        // fetch entitlement and reconcile with the purchase
        val purchaseDtl = subscriptionStateMachine.getSubscriptionData()?.purchaseDetail
        if (purchaseDtl == null) {
            loge(mname, "missing purchase detail, skipping reconcile")
            return false
        }

        // windscribe entitlement can be less than what the expiry which user has purchased
        // (entitlement expiry), if the value of the win expiry is less than 30
        // [thresholdToCheckWinExpiryTs] days then start checking for the windscribe expiry
        return try {
            val (cid, did) = identityForPurchase(purchaseDtl)
            val newPurchaseDtl = queryEntitlementFromServer(cid, did, purchaseDtl)
            storeWinEntitlement(newPurchaseDtl.payload)
            log(mname, "new entitlement updated")

            try {
                val refreshed = subscriptionStateMachine.refreshPayloadForToken(
                    purchaseDtl.purchaseToken, newPurchaseDtl.payload, newPurchaseDtl)
                if (refreshed) {
                    log(mname, "state machine + DB payload updated")
                } else {
                    logd(mname, "machine not serving token=${purchaseDtl.purchaseToken.take(8)}; payload left unchanged")
                }
            } catch (e: Exception) {
                loge(mname, "state machine update failed (non-fatal): ${e.message}")
            }
            true
        } catch (e: Exception) {
            loge(mname, "reconcile failed: ${e.message}", e)
            false
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
            // The actual ID is resolved for the purchase's own cid via getObfuscatedDeviceId().
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
            // The actual ID is resolved for the purchase's own cid via getObfuscatedDeviceId().
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
            // The actual ID is resolved for the purchase's own cid via getObfuscatedDeviceId().
            deviceId = if (getObfuscatedDeviceId().isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload = purchase.developerPayload,
            expiryTime = 0L,
            status = purchase.purchaseState.toSubscriptionStatusId(),
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
        val expiry = if (added) {
            cal.timeInMillis
        } else {
            val days = QueryUtils.getBillingPeriodDays(billingPeriod)
            if (days > 0) { cal.add(Calendar.DAY_OF_YEAR, days); cal.timeInMillis }
            else Long.MAX_VALUE
        }
        if (expiry == Long.MAX_VALUE) return expiry

        if (expiry <= System.currentTimeMillis() && purchase.isAutoRenewing) {
            logd("calculateExpiryTimeInline",
                "frozen first-period estimate for token=${purchase.purchaseToken.take(8)} " +
                    "(expiry=$expiry, autoRenewing=true) → MAX_VALUE (unknown; Play is master)")
            return Long.MAX_VALUE
        }
        return expiry
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
        // Legacy one-time product has the same 2-year access window as ONE_TIME_PRODUCT_2YRS.
        ONE_TIME_PRODUCT_ID -> REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
        ONE_TIME_TEST_PRODUCT_ID -> REVOKE_WINDOW_SUBS_MONTHLY_DAYS
        else -> REVOKE_WINDOW_ONE_TIME_2YRS_DAYS // conservative default for unknown one-time products
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
     *
     * ONE_TIME_PRODUCT_2YRS ("proxy-yearly-2") and ONE_TIME_PRODUCT_5YRS ("proxy-yearly-5") are
     * standalone INAPP product IDs distinct from ONE_TIME_PRODUCT_ID ("onetime.tier").
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
        fetchPurchasesInternal(productType, purchaseRefreshGeneration.get())
    }

    private fun fetchPurchasesInternal(productType: List<String>, generation: Long) {
        if (!isBillingClientSetup()) {
            loge("fetchPurchases", "billing client not setup, cannot fetch purchases")
            signalPurchaseRefreshComplete(generation)
            return
        }
        billingScope.launch {
            val mname = "fetchPurchases"
            logv(mname, "fetching purchases for types: $productType (gen=$generation)")
            // determine product types to be fetched
            val hasInApp = productType.any { it == ProductType.INAPP }
            val hasSubs = productType.any { it == ProductType.SUBS }
            val hasBoth = productType.any { it == ProductType.INAPP } &&
                    productType.any { it == ProductType.SUBS }


            // query for product types to be fetched
            when {
                hasBoth -> queryPurchases(ProductType.INAPP, true, generation)
                hasInApp -> queryPurchases(ProductType.INAPP, false, generation)
                hasSubs -> queryPurchases(ProductType.SUBS, false, generation)
                else -> {
                    loge(mname, "invalid product type for purchases fetch, $productType")
                    signalPurchaseRefreshComplete(generation)
                    return@launch
                }
            }
            logv(mname, "purchases fetch complete")
        }
    }


    private val purchaseRefreshWaiters =
        CopyOnWriteArrayList<Pair<Long, CompletableDeferred<Unit>>>()
    private val purchaseRefreshGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /** Completes waiters whose refresh generation is covered by [generation]. */
    private fun signalPurchaseRefreshComplete(generation: Long) {
        purchaseRefreshWaiters.removeAll { (waiterGen, deferred) ->
            if (waiterGen <= generation) {
                deferred.complete(Unit)
                true
            } else {
                false
            }
        }
    }

    /** Refreshes Play purchases and waits for processing to complete. */
    suspend fun refreshPurchasesAwait(productType: List<String>, timeoutMs: Long = 45_000): Boolean {
        val mname = "refreshPurchasesAwait"
        if (!isBillingClientSetup()) {
            loge(mname, "billing client not setup, cannot refresh purchases")
            return false
        }
        val hasValidType = productType.any { it == ProductType.SUBS || it == ProductType.INAPP }
        if (!hasValidType) {
            loge(mname, "invalid product type for purchases refresh, $productType")
            return false
        }
        val generation = purchaseRefreshGeneration.incrementAndGet()
        val waiter = CompletableDeferred<Unit>()
        purchaseRefreshWaiters.add(generation to waiter)
        try {
            fetchPurchasesInternal(productType, generation)
            val completed = withTimeoutOrNull(timeoutMs) {
                waiter.await()
                true
            } ?: false
            if (!completed) {
                logw(mname, "purchase refresh did not complete within ${timeoutMs}ms; returning without reconciliation guarantee")
            } else {
                logd(mname, "purchase refresh completed")
            }
            return completed
        } finally {
            purchaseRefreshWaiters.remove(generation to waiter)
        }
    }

    private fun queryPurchases(pt: String, hasBoth: Boolean, generation: Long) {
        if (!::billingClient.isInitialized) {
            loge("queryPurchases", "billingClient not initialized, cannot query purchases for $pt")
            signalPurchaseRefreshComplete(generation)
            return
        }
        val mname = this::queryPurchases.name
        log(mname, "querying purchases for type: $pt, hasBoth: $hasBoth")

        val queryPurchasesParams = QueryPurchasesParams.newBuilder().setProductType(pt).build()

        try {
            billingClient.queryPurchasesAsync(queryPurchasesParams) { result, purchases ->
            log(mname, "query result for $pt: code: ${result.responseCode}, purchases: ${purchases.size}")
            if (DEBUG) {
                purchases.forEachIndexed { index, purchase ->
                    log(mname, "purchase ($index): $purchase")
                }
            }

            val processingJob = if (BillingResponse(result.responseCode).isOk) {
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
                null
            }

            // Chain SUBS query AFTER INAPP processing completes so the two
            // handlePurchase calls cannot run concurrently on Dispatchers.IO and
            // race on DB writes.  On INAPP query error, fire immediately.
            if (pt == ProductType.INAPP && hasBoth) {
                if (processingJob != null) {
                    processingJob.invokeOnCompletion { queryPurchases(ProductType.SUBS, false, generation) }
                } else {
                    queryPurchases(ProductType.SUBS, false, generation)
                }
            } else {
                if (processingJob != null) {
                    processingJob.invokeOnCompletion { signalPurchaseRefreshComplete(generation) }
                } else {
                    signalPurchaseRefreshComplete(generation)
                }
            }
            }
        } catch (e: Exception) {
            loge(mname, "queryPurchasesAsync threw for $pt: ${e.message}", e)
            if (pt == ProductType.INAPP && hasBoth) {
                queryPurchases(ProductType.SUBS, false, generation)
            } else {
                signalPurchaseRefreshComplete(generation)
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

        val result = withTimeoutOrNull(timeout) {
            queryProductDetails()
        }

        if (result == null) {
            loge(mname, "product details query timed out after ${timeout}ms")
            // Mark the failure as a timeout so the ViewModel surfaces REQUEST_TIMEOUT.
            lastProductQueryResponseCode = BillingResponseCode.SERVICE_TIMEOUT
            withContext(Dispatchers.Main) {
                billingListener?.productResult(false, emptyList())
            }
        }
    }

    private suspend fun queryProductDetails() {
        val mname = this::queryProductDetails.name
        productCacheMutex.withLock {
            // launch INAPP and SUBS queries concurrently and await both.
            val inAppResult = kotlinx.coroutines.CompletableDeferred<List<ProductDetails>>()
            val subsResult  = kotlinx.coroutines.CompletableDeferred<List<ProductDetails>>()
            // Capture the response code from each query so the most severe one can be surfaced
            // to the user via lastProductQueryResponseCode (used by RethinkPlusViewModel).
            val inAppResponseCode = java.util.concurrent.atomic.AtomicInteger(BillingResponseCode.OK)
            val subsResponseCode  = java.util.concurrent.atomic.AtomicInteger(BillingResponseCode.OK)

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
            inAppResponseCode.set(br.responseCode)
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
            subsResponseCode.set(br.responseCode)
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

        // Atomically swap the cache: clear + repopulate in one short critical section
        // AFTER the async results are in, so readers outside this lock never observe an
        // empty list mid-refresh. (Previously the cache was cleared at the top and stayed
        // empty for the whole query duration, causing an empty product-list flash.)
        storeProductDetails.clear()
        productDetails.clear()

        if (inAppList.isNotEmpty()) processProductList(inAppList)
        if (subsList.isNotEmpty())  processProductList(subsList)


        val merged = productDetails.toList()
        // Surface the most severe (first non-OK) response code so the ViewModel can show a
        // specific failure reason. When both queries succeed this stays OK.
        lastProductQueryResponseCode = if (inAppResponseCode.get() != BillingResponseCode.OK) {
            inAppResponseCode.get()
        } else {
            subsResponseCode.get()
        }
        logd(mname, "product query complete: ${merged.size} total products (inApp=${inAppList.size}, subs=${subsList.size})")
        withContext(Dispatchers.Main) {
            productDetailsLiveData.postValue(merged)
            billingListener?.productResult(merged.isNotEmpty(), merged)
        }
        } // productCacheMutex.withLock
    }


    private fun processProductList(pds: List<ProductDetails>) {
        val mname = this::processProductList.name
        val queryProductDetails = arrayListOf<QueryProductDetail>()
        logd(mname, "product details size: ${pds.size}, $pds")
        pds.forEach { pd ->
            logd(mname, "product detail: $pd")

            when (pd.productType) {
                ProductType.INAPP -> {
                    // One-time products surface one raw entry per purchase option plus one
                    // entry per offer attached to a purchase option. Group them and keep the
                    // best (cheapest eligible) entry per purchase option so an eligible
                    // discount offer shadows the base price of the same purchase option.
                    // The selected offer's offerToken is what makes Play charge the offer
                    // price when the flow is launched (see purchaseOneTime).
                    val offers = selectBestOneTimeOffers(
                        pd.oneTimePurchaseOfferDetailsList.orEmpty(), pd.productId
                    )
                    if (offers.isEmpty()) {
                        loge(mname, "INAPP product has no eligible one-time offers: ${pd.productId}")
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
                            freeTrialPeriod = 0,
                            discountPercent = oneTimeDiscountPercent(offer)
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
                        logd(mname, "INAPP offer selected: option=${offer.purchaseOptionId}, " +
                            "offerId=${offer.offerId}, price=${offer.formattedPrice}, " +
                            "discountPercent=${pricingPhase.discountPercent}, planId=$planId")
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

    /**
     * Reduces the raw one-time offer list to at most one offer per purchase option,
     * preferring eligible discount offers (offerId != null) over the base price entry
     * (offerId == null) of the same purchase option; among the eligible discount offers
     * the cheapest one wins. Ineligible offers (sold-out limited-quantity offers or
     * offers outside their validity window) are never selected, so the purchase flow
     * launched with the selected offerToken cannot fail with an offer-eligibility error.
     */
    private fun selectBestOneTimeOffers(
        offers: List<ProductDetails.OneTimePurchaseOfferDetails>,
        productId: String
    ): List<ProductDetails.OneTimePurchaseOfferDetails> {
        val mname = this::selectBestOneTimeOffers.name
        val selected = offers
            .filter { isOneTimeOfferEligible(it) }
            .groupBy { it.purchaseOptionId ?: it.offerId ?: productId }
            .map { (optionId, group) ->
                val discountOffers = group.filter { !it.offerId.isNullOrBlank() }
                // prefer discount offers; fall back to the plain purchase-option entry
                val best = discountOffers.ifEmpty { group }.minByOrNull { it.priceAmountMicros }
                if (discountOffers.isNotEmpty()) {
                    log(mname, "purchase option $optionId: discount offer selected " +
                        "(offerId=${best?.offerId}, price=${best?.formattedPrice})")
                }
                best ?: group.first()
            }
        log(mname, "selected ${selected.size} offer(s) from ${offers.size} raw offer(s) for $productId")
        return selected
    }

    /**
     * A one-time offer is purchasable when its limited quantity is not exhausted and,
     * when it has a validity window, "now" falls inside that window.
     */
    private fun isOneTimeOfferEligible(
        offer: ProductDetails.OneTimePurchaseOfferDetails
    ): Boolean {
        offer.limitedQuantityInfo?.let { lq ->
            if (lq.remainingQuantity <= 0) return false
        }
        offer.validTimeWindow?.let { window ->
            val now = System.currentTimeMillis()
            val start = window.startTimeMillis
            val end = window.endTimeMillis
            if (start != null && start > now) return false
            if (end != null && end < now) return false
        }
        return true
    }

    /**
     * Returns the offer's discount percentage. Play expresses a one-time offer discount
     * in one of two ways, both handled here:
     * 1. Percentage offer → [DiscountDisplayInfo.getPercentageDiscount] (e.g. 20% off).
     * 2. Absolute offer   → [DiscountDisplayInfo.getDiscountAmount] (e.g. $5 off), where
     *    priceAmountMicros is already the final discounted price, so the equivalent
     *    percentage is derived against base = final + discount.
     * As a last resort the percentage is derived from fullPriceMicros when Play
     * populates it. Returns 0 when the offer carries no discount.
     */
    private fun oneTimeDiscountPercent(
        offer: ProductDetails.OneTimePurchaseOfferDetails
    ): Int {
        offer.discountDisplayInfo?.let { ddi ->
            // type 1: percentage offer, Play reports the percentage directly
            ddi.percentageDiscount?.let { pct -> return pct }
            // type 2: absolute (fixed-amount) offer; final price is priceAmountMicros
            ddi.discountAmount?.let { amt ->
                val discount = amt.discountAmountMicros
                val base = offer.priceAmountMicros + discount
                if (discount > 0L && base > 0L) return ((discount * 100) / base).toInt()
            }
        }
        // fallback: some offer shapes expose the full (undiscounted) price instead
        val full = offer.fullPriceMicros ?: return 0
        if (full <= 0L || offer.priceAmountMicros >= full) return 0
        return (((full - offer.priceAmountMicros) * 100) / full).toInt()
    }

    suspend fun purchaseSubs(
        activity: Activity,
        productId: String,
        planId: String,
        forceResubscribe: Boolean = false
    ) {
        val mname = this::purchaseSubs.name

        if (!forceResubscribe) {
            if (!subscriptionStateMachine.canMakePurchase()) {
                val currentState = subscriptionStateMachine.currentMachineState()
                if (currentState is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated) {
                    logd(mname, "purchase already initiated; ignoring duplicate request")
                    billingListener?.purchaseInProgress()
                } else {
                    loge(mname, "cannot make purchase, current state: ${currentState.name}")
                    billingListener?.purchasesResult(false, emptyList())
                }
                return
            }

            try {
                if (!subscriptionStateMachine.startPurchase()) {
                    val currentState = subscriptionStateMachine.currentMachineState()
                    if (currentState is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated) {
                        logd(mname, "purchase already initiated; ignoring duplicate request")
                        billingListener?.purchaseInProgress()
                    } else {
                        loge(mname, "cannot make purchase, current state: ${currentState.name}")
                        billingListener?.purchasesResult(false, emptyList())
                    }
                    return
                }
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
                withTimeoutOrNull(10_000.milliseconds) { queryProductDetails() }
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
            val currentState = subscriptionStateMachine.currentMachineState()
            if (currentState is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated) {
                logd(mname, "purchase already initiated; ignoring duplicate request")
                billingListener?.purchaseInProgress()
            } else {
                loge(mname, "cannot make one-time purchase in state: ${currentState.name}")
                billingListener?.purchasesResult(false, emptyList())
            }
            return
        }

        if (!forceExtend) {
            try {
                if (!subscriptionStateMachine.startPurchase()) {
                    val currentState = subscriptionStateMachine.currentMachineState()
                    if (currentState is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated) {
                        logd(mname, "purchase already initiated; ignoring duplicate request")
                        billingListener?.purchaseInProgress()
                    } else {
                        loge(mname, "cannot make one-time purchase in state: ${currentState.name}")
                        billingListener?.purchasesResult(false, emptyList())
                    }
                    return
                }
            } catch (e: Exception) {
                loge(mname, "err starting one-time purchase in state machine: ${e.message}", e)
                billingListener?.purchasesResult(false, emptyList())
                return
            }
        }

        var queryProductDetail = storeProductDetails.find {
            it.productDetail.productId == productId &&
                    it.productDetail.productType == ProductType.INAPP &&
                    it.productDetail.planId == planId
        }

        if (queryProductDetail == null) {
            log(mname, "one-time product not found in cache (size=${storeProductDetails.size}), fetching on-demand and retrying")
            try {
                withTimeoutOrNull(10_000.milliseconds) { queryProductDetails() }
            } catch (e: Exception) {
                loge(mname, "on-demand product details fetch failed: ${e.message}", e)
            }
            queryProductDetail = storeProductDetails.find {
                it.productDetail.productId == productId &&
                        it.productDetail.productType == ProductType.INAPP &&
                        it.productDetail.planId == planId
            }
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

    private fun isDuplicatePurchaseAttempt(responseCode: Int, debugMessage: String): Boolean =
        responseCode == BillingResponseCode.DEVELOPER_ERROR &&
            debugMessage.contains("Duplicate purchase attempted in quick succession", ignoreCase = true)

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

        if (!billingClient.isReady) {
            loge(mname, "billing client no longer ready; aborting launchBillingFlow to avoid ProxyBillingActivity NPE")
            try { subscriptionStateMachine.purchaseFailed("Billing client disconnected before launch", null) } catch (_: Exception) {}
            billingListener?.purchasesResult(false, emptyList())
            return
        }

        val billingResult = billingClient.launchBillingFlow(activity, flowParams)
        val isSuccess = billingResult.responseCode == BillingResponseCode.OK

        if (isSuccess) {
            billingListener?.purchasesResult(true, emptyList())
            schedulePurchaseFlowTimeout()
            logPurchaseFunnelEvent("flow_shown", pds?.productId.orEmpty())
        } else if (isDuplicatePurchaseAttempt(billingResult.responseCode, billingResult.debugMessage)) {
            log(mname, "duplicate purchase launch response; keeping the current purchase flow active")
            billingListener?.purchaseInProgress()
        } else {
            billingListener?.purchasesResult(false, emptyList())
            loge(mname, "err launch billing flow: ${billingResult.responseCode}")
            logPurchaseFunnelEvent("failed", "launch=${billingResult.responseCode}")
            transactionErrorLiveData.postValue(billingResult)
            try {
                subscriptionStateMachine.purchaseFailed(
                    "launch=${billingResult.responseCode}", billingResult.responseCode)
            } catch (e: Exception) {
                loge(mname, "purchaseFailed after launch error: ${e.message}")
            }
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
                handleUnauthorized401(ServerApiError.Operation.CUSTOMER, result.accountId, "")
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
        val mname = "getObfuscatedDeviceId"
        return when (val result = billingBackendClient.resolveIdentity()) {
            is RefreshIdentityResult.Success -> result.did
            is RefreshIdentityResult.Unauthorized -> {
                loge(mname, "401 unauthorized on device ID resolution; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.CUSTOMER, result.accountId, "")
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

    suspend fun getObfuscatedDeviceId(accountId: String): String {
        val mname = "getObfuscatedDeviceId"
        if (accountId.isBlank()) {
            loge(mname, "blank accountId passed; refusing to pair a did with an unknown cid")
            return ""
        }
        return billingBackendClient.didForCid(accountId, mintIfMissing = true).deviceId
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
                handleUnauthorized401(ServerApiError.Operation.CUSTOMER, result.accountId, "")
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
            val alreadyAuthErrorActive = serverApiErrorLiveData.value is ServerApiError.Unauthorized401
            serverApiErrorLiveData.value = error
            if (!alreadyAuthErrorActive && !serverApiErrorLiveData.hasActiveObservers()) {
                val ctx = appContext
                if (ctx != null) {
                    logd("handleUnauthorized401", "posting auth-error notification")
                    DeviceAuthErrorNotifier.notify(ctx, error, persistentState.theme)
                } else {
                    loge("handleUnauthorized401", "appContext null; cannot post auth-error notification")
                }
            } else if (alreadyAuthErrorActive) {
                logd("handleUnauthorized401", "auth error already active, skipping duplicate notification")
            }
        }
        loge("handleUnauthorized401", "401 on ${operation.endpoint}: accLen=${accountId.length}")

        if (accountId.isNotBlank()) {
            billingScope.launch {
                val outcome = runCatching {
                    billingBackendClient.recoverIdentityAfterAuthFailure(accountId)
                }.getOrElse {
                    loge("handleUnauthorized401", "identity recovery failed: ${it.message}")
                    IdentityRecoveryOutcome.FAILED
                }
                if (outcome == IdentityRecoveryOutcome.CID_REJECTED) {
                    rebindIdentityToPrimaryPurchase(accountId)
                }
            }
        }
    }

    /** Reclaims the identity store for the primary purchase CID after a rejected CID. */
    private suspend fun rebindIdentityToPrimaryPurchase(failingCid: String) {
        val mname = this::rebindIdentityToPrimaryPurchase.name
        try {
            val entitlementCid = primaryPurchaseCid()
            if (entitlementCid.isBlank() || entitlementCid == failingCid) {
                logw(mname, "primary purchase cid unavailable or equals the rejected cid; " +
                    "leaving recovery to the user-triggered reset")
                return
            }
            logw(mname, "cid=${failingCid.take(8)} rejected by the server; re-claiming the " +
                "store for the primary purchase cid=${entitlementCid.take(8)}")
            val claimed = resetIdentityForRebind(entitlementCid)
            if (claimed) {
                log(mname, "store re-claimed for the primary purchase cid; registration re-check scheduled")
            } else {
                logw(mname, "re-claim for the primary purchase cid failed; previous identity kept")
            }
        } catch (e: Exception) {
            loge(mname, "identity rebind failed: ${e.message}", e)
        }
    }

    /** Adapter for the legacy [Pair]-returning conflict handler (see its KDoc). */
    private fun Pair<Boolean, String>.toMutationResult(): PurchaseMutationResult =
        if (first) PurchaseMutationResult.Success(second) else PurchaseMutationResult.Failure(second)

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
                is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated,
                is SubscriptionStateMachineV2.SubscriptionState.ServerAckPending -> {
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
            is SubscriptionStateMachineV2.SubscriptionState.Grace ->
                logd(mname, "subscription in grace period; still valid but payment failing")

            is SubscriptionStateMachineV2.SubscriptionState.Error ->
                loge(mname, "state machine entered error state; Play will re-confirm on next query")

            is SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> {
                logd(mname, "purchase in progress: ${state.name}")
                logPurchaseFunnelEvent("pending")
                // schedule a background re-check so a pending
                // purchase is re-queried even after the user navigates away from the
                // purchase screen (in-screen polling is cancelled on onDestroyView).
                schedulePendingRecheck()
            }

            is SubscriptionStateMachineV2.SubscriptionState.ServerAckPending -> {
                logd(mname, "ack pending; scheduling periodic background re-check")
                // Symmetric with PurchasePending: keep a periodic + expedited background
                // re-check so an acknowledgement failure is reconciled even after the user
                // navigates away. The 5-min one-shot retry below is retained as well.
                schedulePendingRecheck()
                scheduleServerAckRetry(mname)
            }

            is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated ->
                logd(mname, "purchase in progress: ${state.name}")

            is SubscriptionStateMachineV2.SubscriptionState.Active,
            is SubscriptionStateMachineV2.SubscriptionState.Grace,
            is SubscriptionStateMachineV2.SubscriptionState.OnHold,
            is SubscriptionStateMachineV2.SubscriptionState.Paused,
            is SubscriptionStateMachineV2.SubscriptionState.Error,
            is SubscriptionStateMachineV2.SubscriptionState.Expired,
            is SubscriptionStateMachineV2.SubscriptionState.Revoked,
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                logd(mname, "state resolved to: ${state.name}")
                cancelPendingRecheck()
            }

            else ->
                logd(mname, "state changed to: ${state.name}")
        }
    }

    /**
     * Schedules the [PendingPurchaseWorker] periodic + expedited re-checks
     * Safe to call repeatedly; the worker uses unique-work policies.
     */
    private fun schedulePendingRecheck() {
        val ctx = appContext ?: return
        try {
            PendingPurchaseWorker.schedule(ctx)
            PendingPurchaseWorker.scheduleExpedited(ctx)
        } catch (e: Exception) {
            loge("schedulePendingRecheck", "could not schedule pending re-check: ${e.message}", e)
        }
    }

    /** Cancels the [PendingPurchaseWorker] re-checks once the purchase is resolved. */
    private fun cancelPendingRecheck() {
        val ctx = appContext ?: return
        try {
            PendingPurchaseWorker.cancel(ctx)
        } catch (e: Exception) {
            loge("cancelPendingRecheck", "could not cancel pending re-check: ${e.message}", e)
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
    ): PurchaseMutationResult = withContext(Dispatchers.IO) {
        val mname = "cancelOneTimePurchase"
        logd(mname, "delegating to BillingServerRepository, productId=$productId, accLen=${accountId.length}")
        // No connectionMutex: this path talks to the backend server + state machine only
        // (the state machine is guarded by its own stateLock); it never touches the Play
        // connection or queryUtils/processors, so it must not serialize against connect.
        when (val res = billingBackendClient.cancelPurchase(accountId, deviceId, productId, purchaseToken)) {
            is PurchaseMutationResult.Unauthorized -> {
                loge(mname, "cancelOneTimePurchase 401; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.CANCEL, accountId, deviceId)
                return@withContext res
            }
            is PurchaseMutationResult.Conflict ->
                return@withContext handleConflict409(ServerApiError.Operation.CANCEL, accountId, deviceId, purchaseToken, productId, res.message).toMutationResult()
            is PurchaseMutationResult.Failure -> return@withContext res
            is PurchaseMutationResult.Success -> { /* server accepted; update local state below */ }
        }
        val localSuccess = RpnProxyManager.updateCancelledSubscription(accountId, purchaseToken)
        if (!localSuccess) {
            logEvent(EventType.PROXY_ERROR, Severity.HIGH, "cancelOneTimePurchase", "Local state update failed")
            return@withContext PurchaseMutationResult.Failure("Local state update failed")
        } else {
            logEvent(EventType.PROXY_SWITCH, Severity.LOW, "cancelOneTimePurchase", "cancelOneTimePurchase success")
        }
        PurchaseMutationResult.Success("One-time purchase cancelled successfully")
    }

    /**
     * Revoke a one-time (INAPP) purchase (full refund within revoke window).
     *
     * Mirrors [revokeSubscription] exactly but uses the correct one-time product SKU.
     * Calls [revokeSubscription] → updates local state → fires
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
    ): PurchaseMutationResult = withContext(Dispatchers.IO) {
        val mname = "revokeOneTimePurchase"
        logd(mname, "delegating to BillingServerRepository, productId=$productId, accLen=${accountId.length}")
        // No connectionMutex: see cancelOneTimePurchase - server + state machine only.
        when (val res = billingBackendClient.revokePurchase(accountId, deviceId, productId, purchaseToken)) {
            is PurchaseMutationResult.Unauthorized -> {
                loge(mname, "revokeOneTimePurchase 401; surfacing auth error")
                handleUnauthorized401(ServerApiError.Operation.REVOKE, accountId, deviceId)
                return@withContext res
            }
            is PurchaseMutationResult.Conflict ->
                return@withContext handleConflict409(ServerApiError.Operation.REVOKE, accountId, deviceId, purchaseToken, productId, res.message).toMutationResult()
            is PurchaseMutationResult.Failure -> return@withContext res
            is PurchaseMutationResult.Success -> { /* server accepted; update local state below */ }
        }
        val localSuccess = RpnProxyManager.updateRevokedSubscription(accountId, purchaseToken)
        if (!localSuccess) {
            logEvent(EventType.PROXY_ERROR, Severity.HIGH, "revokeOneTimePurchase", "Local state update failed")
            return@withContext PurchaseMutationResult.Failure("Local state update failed")
        } else {
            logEvent(EventType.PROXY_SWITCH, Severity.LOW, "revokeOneTimePurchase", "revokeOneTimePurchase success")
        }
        PurchaseMutationResult.Success("One-time purchase revoked successfully")
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
        val isInApp = SubscriptionStateMachineV2.isInAppProduct(sub.productId)
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
        val isInApp = SubscriptionStateMachineV2.isInAppProduct(sub.productId)
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

    suspend fun cancelPlaySubscription(accountId: String, deviceId: String, purchaseToken: String, sku: String): PurchaseMutationResult =
        withContext(Dispatchers.IO) {
            val mname = "cancelPlaySubscription"
            logd(mname, "delegating to BillingServerRepository, accLen=${accountId.length}")
            // No connectionMutex: see cancelOneTimePurchase - server + state machine only.
            when (val res = billingBackendClient.cancelPurchase(accountId, deviceId, sku, purchaseToken)) {
                is PurchaseMutationResult.Unauthorized -> {
                    loge(mname, "cancelPlaySubscription 401; surfacing auth error")
                    handleUnauthorized401(ServerApiError.Operation.CANCEL, accountId, deviceId)
                    return@withContext res
                }
                is PurchaseMutationResult.Conflict ->
                    return@withContext handleConflict409(ServerApiError.Operation.CANCEL, accountId, deviceId, purchaseToken, sku, res.message).toMutationResult()
                is PurchaseMutationResult.Failure -> return@withContext res
                is PurchaseMutationResult.Success -> { /* server accepted; update local state below */ }
            }
            val localSuccess = RpnProxyManager.updateCancelledSubscription(accountId, purchaseToken)
            if (!localSuccess) {
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "cancelPlaySubscription", "Local state update failed")
                return@withContext PurchaseMutationResult.Failure("Local state update failed")
            } else {
                logEvent(EventType.PROXY_SWITCH, Severity.LOW, "cancelPlaySubscription", "cancelPlaySubscription success")
            }
            PurchaseMutationResult.Success("Subscription cancelled successfully")
        }

    suspend fun revokeSubscription(accountId: String, deviceId: String, purchaseToken: String, sku: String): PurchaseMutationResult =
        withContext(Dispatchers.IO) {
            val mname = "revokeSubscription"
            logd(mname, "delegating to BillingServerRepository, accLen=${accountId.length}")
            // No connectionMutex: see cancelOneTimePurchase - server + state machine only.
            when (val res = billingBackendClient.revokePurchase(accountId, deviceId, sku, purchaseToken)) {
                is PurchaseMutationResult.Unauthorized -> {
                    loge(mname, "revokeSubscription 401; surfacing auth error")
                    handleUnauthorized401(ServerApiError.Operation.REVOKE, accountId, deviceId)
                    return@withContext res
                }
                is PurchaseMutationResult.Conflict ->
                    return@withContext handleConflict409(ServerApiError.Operation.REVOKE, accountId, deviceId, purchaseToken, sku, res.message).toMutationResult()
                is PurchaseMutationResult.Failure -> return@withContext res
                is PurchaseMutationResult.Success -> { /* server accepted; update local state below */ }
            }
            val localSuccess = RpnProxyManager.updateRevokedSubscription(accountId, purchaseToken)
            if (!localSuccess) {
                logEvent(EventType.PROXY_ERROR, Severity.HIGH, "revokeSubscription", "Local state update failed")
                return@withContext PurchaseMutationResult.Failure("Local state update failed")
            } else {
                logEvent(EventType.PROXY_SWITCH, Severity.LOW, "revokeSubscription", "revokeSubscription success")
            }
            PurchaseMutationResult.Success("Subscription revoked successfully")
        }

    suspend fun queryEntitlementFromServer(accountId: String, deviceId: String, purchase: PurchaseDetail): PurchaseDetail {
        val mname = this::queryEntitlementFromServer.name
        logd(mname, "delegating to BillingServerRepository, accLen=${accountId.length}")
        val pt = purchase.purchaseToken
        if (pt.isEmpty()) {
            logd(mname, "no purchase token; skipping")
            return purchase
        }

        return when (val result = billingBackendClient.queryEntitlement(accountId, deviceId, purchase, pt)) {
            is QueryEntitlementResult.Success -> {
                val updated = result.purchase
                val serverAnswered = updated.payload.isNotEmpty() || updated.expiryTime > 0L
                if (!serverAnswered) {
                    logd(mname, "empty entitlement response for token=${pt.take(8)}; returning original purchase (fail-safe)")
                    purchase
                } else {
                    if (purchase.productType == ProductType.SUBS &&
                        updated.expiryTime > System.currentTimeMillis() &&
                        updated.expiryTime != Long.MAX_VALUE
                    ) {
                        subscriptionStateMachine.refreshBillingExpiryFromServer(pt, updated.expiryTime)
                    }
                    updated
                }
            }
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
                // Server responded with a business error (e.g. purchase canceled/revoked).
                // If the server included a linkedPurchaseId, the revoked purchase may have been
                // superseded by an older one that is still valid. Attempt to reactivate it so
                // the user is not left in a broken state.
                val linked = result.linkedPurchaseId
                if (!linked.isNullOrBlank()) {
                    // The revoked purchase may have been superseded by an older one that is
                    // still valid. Attempt to reactivate it so the user is not left in a
                    // broken state.
                    loge(mname, "queryEntitlement server business error for token=${pt.take(8)}; linkedPurchaseId present, attempting reactivation of linkedToken=${linked.take(8)}")
                    try {
                        RpnProxyManager.tryReactivateLinkedPurchase(accountId, deviceId, linked)
                    } catch (e: Exception) {
                        loge(mname, "tryReactivateLinkedPurchase threw for linkedToken=${linked.take(8)}: ${e.message}", e)
                    }
                } else {
                    loge(mname, "queryEntitlement server business error for token=${pt.take(8)}; no linkedPurchaseId, preserving local purchase (fail-safe)")
                }
                result.purchase
            }
            is QueryEntitlementResult.Expired -> {
                // Server has authoritatively confirmed the subscription is expired.
                // treat this as an expired entitlement rather than re-storing the old one.
                loge(mname, "queryEntitlement server confirmed subscription expired for token=${pt.take(8)}; expiring local purchase")
                result.purchase.copy(expiryTime = 0L, payload = "")
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
        if (success) {
            serverAckFailureCount.remove(purchaseToken)
        } else {
            if (payload == BillingBackendClient.ACK_MARKER_UNAUTHORIZED) {
                loge(mname, "acknowledgePurchaseFromServer 401: acc=${accountId.take(8)}, prod=$productType, token=${purchaseToken.take(8)}")
                handleUnauthorized401(ServerApiError.Operation.ACKNOWLEDGE, accountId, deviceId)
            }
            if (payload == BillingBackendClient.ACK_MARKER_CONFLICT) {
                loge(mname, "acknowledgePurchaseFromServer 409: acc=${accountId.take(8)}, prod=$productType, token=${purchaseToken.take(8)}")
                handleConflict409(
                    ServerApiError.Operation.ACKNOWLEDGE, accountId, deviceId,
                    purchaseToken, skuForType(productType), payload
                )
            }
            val failures = serverAckFailureCount.merge(purchaseToken, 1, Int::plus) ?: 1
            if (failures >= SERVER_ACK_PLAY_FALLBACK_THRESHOLD) {
                acknowledgePurchaseWithPlay(purchaseToken, mname)
            }
        }
        return Pair(success, payload)
    }

    private val serverAckFailureCount: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    const val SERVER_ACK_PLAY_FALLBACK_THRESHOLD = 3

    private fun acknowledgePurchaseWithPlay(purchaseToken: String, caller: String) {
        if (!playAckFallbackDone.add(purchaseToken)) return
        if (!isBillingClientSetup()) {
            playAckFallbackDone.remove(purchaseToken)
            logw(caller, "billing client not setup; cannot ack token=${purchaseToken.take(8)} with Play")
            return
        }
        try {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingResponseCode.OK) {
                    serverAckFailureCount.remove(purchaseToken)
                    log(caller, "client-side Play ack succeeded for token=${purchaseToken.take(8)}")
                } else {
                    // allow a later retry after another failed server-ack round
                    playAckFallbackDone.remove(purchaseToken)
                    loge(caller, "client-side Play ack failed (${result.responseCode}) for token=${purchaseToken.take(8)}")
                }
            }
        } catch (e: Exception) {
            playAckFallbackDone.remove(purchaseToken)
            loge(caller, "client-side Play ack threw for token=${purchaseToken.take(8)}: ${e.message}", e)
        }
    }

    private val playAckFallbackDone: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun getSubscriptionState(): SubscriptionStateMachineV2.SubscriptionState {
        return subscriptionStateMachine.currentMachineState()
    }

    fun getSubscriptionStateFlow(): StateFlow<SubscriptionStateMachineV2.SubscriptionState> {
        return subscriptionStateMachine.currentState
    }

    suspend fun serverAckFailed(error: String) {
        val mname = this::serverAckFailed.name
        logd(mname, "server ack failed: ${error.take(80)}")
        logPurchaseFunnelEvent("ack_failed", error.take(40))
        subscriptionStateMachine.serverAckFailed(error)
        // ServerAckPending previously had no automatic retry — its only
        // recovery was the 24h SubscriptionCheckWorker. Schedule a single delayed
        // background re-check that re-queries Play and re-runs the purchase processors,
        // which retry the server-side acknowledgement.
        scheduleServerAckRetry(mname)
    }

    /**
     * One-shot delayed retry of the server acknowledgement from the [ServerAckPending]
     * state. Only fires if the machine is still in ServerAckPending
     * when the delay elapses, so a successful reconciliation in the meantime is a no-op.
     */
    private fun scheduleServerAckRetry(caller: String) {
        billingScope.launch {
            try {
                delay(SERVER_ACK_RETRY_DELAY_MS.milliseconds)
                val state = subscriptionStateMachine.currentMachineState()
                logd(caller, "server-ack retry fired: state=${state.name}")
                if (state is SubscriptionStateMachineV2.SubscriptionState.ServerAckPending) {
                    fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
                }
            } catch (e: Exception) {
                loge(caller, "server-ack retry failed: ${e.message}", e)
            }
        }
    }

    private fun logEvent(eventType: EventType, severity: Severity, msg: String, details: String) {
        eventLogger.log(eventType, severity, msg, EventSource.PROXY, false, details)
    }

    /**
     * Structured purchase-funnel analytics. Emits a single, uniformly
     * shaped event for each funnel stage so conversion can be analysed downstream:
     *
     * stage ∈ {flow_initiated, flow_shown, completed, pending, failed, restored,
     *          item_already_owned, ack_success, ack_failed, entitlement_activated}
     */
    private fun logPurchaseFunnelEvent(stage: String, extra: String = "") {
        val details = if (extra.isEmpty()) stage else "$stage|$extra"
        Logger.i(LOG_IAB, "$TAG funnel: $details")
        try {
            logEvent(
                eventType = EventType.PROXY_CONNECT,
                severity = Severity.LOW,
                msg = "purchase_funnel",
                details = details
            )
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG funnel log failed: ${e.message}")
        }
    }

    /**
     * Validates active SUBS purchases with the server before expiring them.
     *
     * Called when Play returns empty SUBS snapshot after EMPTY_QUERY_THRESHOLD.
     * This prevents expiring valid purchases when Play returns empty due to network
     * issues or Play Services glitches.
     *
     */
    private data class PreservedPurchaseRows(
        val tokens: Set<String>,
        val rowIds: Set<Int>,
        val expiredRowIds: Set<Int>
    )

    private suspend fun validateSubsWithServerBeforeExpiry(): PreservedPurchaseRows? {
        val mname = "validateSubsWithServerBeforeExpiry"
        try {
            val activeSubsRows = subscriptionStateMachine.getActiveSubsPurchase()
            logd(mname, "checking ${activeSubsRows.size} active SUBS row(s) against server")
            if (activeSubsRows.isEmpty()) {
                logd(mname, "no active SUBS rows to validate, nothing to preserve")
                return PreservedPurchaseRows(emptySet(), emptySet(), emptySet())
            }

            val preserved = mutableSetOf<String>()
            val preservedRowIds = mutableSetOf<Int>()
            val expiredRowIds = mutableSetOf<Int>()
            fun preserve(sub: SubscriptionStatus) {
                if (sub.purchaseToken.isNotBlank()) preserved.add(sub.purchaseToken)
                preservedRowIds.add(sub.id)
            }
            val now = System.currentTimeMillis()
            for (sub in activeSubsRows) {
                try {
                    if (sub.purchaseToken.isBlank()) {
                        logd(mname, "SUBS row id=${sub.id} has blank token; preserving by row id")
                        preserve(sub)
                        continue
                    }

                    // Read-only fallback: validation must never mint a fresh identity.
                    val effectiveAccountId = sub.accountId.ifEmpty { billingBackendClient.peekStoredCid() }
                    val deviceId = billingBackendClient.getDeviceIdForCid(effectiveAccountId)
                    if (deviceId.isBlank()) {
                        loge(mname, "SUBS row id=${sub.id}: did unavailable for cid=${effectiveAccountId.take(8)}; preserving (fail-safe)")
                        preserve(sub)
                        continue
                    }
                    val purchaseDetailForQuery = PurchaseDetail(
                        productId = sub.productId,
                        planId = sub.planId,
                        productTitle = sub.productTitle,
                        state = sub.state,
                        planTitle = "",
                        purchaseToken = sub.purchaseToken,
                        productType = ProductType.SUBS,
                        purchaseTime = sub.purchaseTime.toString(),
                        purchaseTimeMillis = sub.purchaseTime,
                        isAutoRenewing = false, // Not used in entitlement query
                        accountId = effectiveAccountId,
                        deviceId = deviceId,
                        payload = sub.developerPayload,
                        expiryTime = sub.billingExpiry,
                        status = sub.status,
                        windowDays = sub.windowDays,
                        orderId = sub.orderId
                    )

                    val result = try {
                        billingBackendClient.queryEntitlement(
                            effectiveAccountId, deviceId, purchaseDetailForQuery, sub.purchaseToken
                        )
                    } catch (serverEx: Exception) {
                        loge(mname, "server entitlement check failed for token=${sub.purchaseToken.take(8)}: ${serverEx.message}", serverEx)
                        preserve(sub)
                        continue
                    }

                    when (result) {
                        is QueryEntitlementResult.Unauthorized -> {
                            logd(mname, "SUBS token=${sub.purchaseToken.take(8)}: server 401; preserving (auth failure is not expiry)")
                            InAppBillingHandler.handleUnauthorized401(
                                ServerApiError.Operation.ACKNOWLEDGE, result.accountId, result.deviceId
                            )
                            preserve(sub)
                        }
                        is QueryEntitlementResult.Conflict,
                        is QueryEntitlementResult.Transient,
                        is QueryEntitlementResult.Failure -> {
                            logd(mname, "SUBS token=${sub.purchaseToken.take(8)}: non-definitive server result; preserving (fail-safe)")
                            preserve(sub)
                        }
                        is QueryEntitlementResult.Expired -> {
                            logd(mname, "SUBS token=${sub.purchaseToken.take(8)}: server authoritatively confirms expired; allowing expiry")
                            expiredRowIds.add(sub.id)
                        }
                        is QueryEntitlementResult.Success -> {
                            val updatedDetail = result.purchase
                            val serverAnswered = updatedDetail.payload.isNotEmpty() ||
                                updatedDetail.expiryTime > 0L
                            if (!serverAnswered) {
                                logd(mname, "SUBS token=${sub.purchaseToken.take(8)}: empty entitlement response (server had no answer); preserving (fail-safe)")
                                preserve(sub)
                            } else {
                                val tunnelExpiry = getExpiryFromPayload(updatedDetail.payload) ?: 0L
                                val serverExpiryFuture = updatedDetail.expiryTime > now &&
                                    updatedDetail.expiryTime != Long.MAX_VALUE

                                if (serverExpiryFuture) {
                                    logd(mname, "SUBS entitlement for token=${sub.purchaseToken.take(8)}: " +
                                        "tunnelExpiry=$tunnelExpiry, billingExpiry=${sub.billingExpiry}; " +
                                        "server confirms a future billing expiry, preserving + re-anchoring")
                                    preserve(sub)
                                    subscriptionStateMachine.refreshBillingExpiryFromServer(
                                        sub.purchaseToken, updatedDetail.expiryTime
                                    )
                                } else if (tunnelExpiry > now) {
                                    logd(mname, "SUBS entitlement for token=${sub.purchaseToken.take(8)}: " +
                                        "tunnelExpiry=$tunnelExpiry still valid; preserving")
                                    preserve(sub)
                                } else {
                                    logw(mname, "SUBS token=${sub.purchaseToken.take(8)}: server expiry past " +
                                        "(${updatedDetail.expiryTime}), tunnelExpiry=$tunnelExpiry ended; allowing expiry")
                                    expiredRowIds.add(sub.id)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    loge(mname, "unexpected error checking SUBS entitlement for id=${sub.id}: ${e.message}", e)
                    preserve(sub)
                }
            }

            logd(mname, "validation complete: preserved=${preserved.size}/${activeSubsRows.size} token(s)")
            return PreservedPurchaseRows(preserved, preservedRowIds, expiredRowIds)
        } catch (outerEx: Exception) {
            loge(mname, "error fetching active SUBS rows before expiry: ${outerEx.message}", outerEx)
            return null
        }
    }
}
