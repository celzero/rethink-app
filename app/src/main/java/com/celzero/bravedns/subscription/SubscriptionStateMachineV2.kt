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
package com.celzero.bravedns.subscription

import Logger.LOG_IAB
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Subscription State Machine using the framework
 * This demonstrates how to use the generic state machine framework
 * for managing subscription states with proper error handling and RPN integration
 */
class SubscriptionStateMachineV2 : KoinComponent {

    private val subscriptionDb by inject<SubscriptionStatusRepository>()
    private val dbSyncService: StateMachineDatabaseSyncService by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var stateMachine: StateMachine<SubscriptionState, SubscriptionEvent, SubscriptionData>

    companion object {
        private const val TAG = "SubscriptionStateMachineV2"
    }

    init {
        stateMachine =
            createStateMachine<SubscriptionState, SubscriptionEvent, SubscriptionData>(
                initialState = SubscriptionState.Uninitialized,
                tag = TAG
            ) {
                // Initialize transition
                addTransition(
                    fromState = SubscriptionState.Uninitialized,
                    event = SubscriptionEvent.Initialize,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleInitialize() }
                )

                // initial state can be from Uninitialized or Initial to active/on-hold/expired/revoked
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.Initialize,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleInitialize() }
                )

                // initial state to cancelled state
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Cancelled,
                    action = { _, data -> handleUserCancelled(data as? SubscriptionData) }
                )

                // initial state to error state
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.BillingError("", BillingResult.newBuilder().build()),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.BillingError },
                    action = { event, _ ->
                        val errorEvent = event as SubscriptionEvent.BillingError
                        handleBillingError(errorEvent.error, errorEvent.billingResult)
                    }
                )

                // initial state to expired state
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpired(data as? SubscriptionData) }
                )

                // Purchase flow transitions
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )

                // Add missing PaymentSuccessful transition from Initial state
                // This handles cases where payment is processed directly from initial state
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        val paymentEvent = event as SubscriptionEvent.PaymentSuccessful
                        handlePaymentSuccessful(paymentEvent.purchaseDetail)
                    }
                )

                // Error recovery transitions
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.ErrorRecovered,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleErrorRecovery() }
                )

                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                // Purchase completion transitions - FIXED: Direct event matching
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.PurchaseCompleted(createDummyPurchaseDetail()),
                    toState = SubscriptionState.PurchasePending,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseCompleted },
                    action = { event, _ ->
                        val purchaseEvent = event as SubscriptionEvent.PurchaseCompleted
                        handlePurchaseCompleted(purchaseEvent.purchaseDetail)
                    }
                )

                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        val paymentEvent = event as SubscriptionEvent.PaymentSuccessful
                        handlePaymentSuccessful(paymentEvent.purchaseDetail)
                    }
                )

                // FIXED: Add missing UserCancelled transition from PurchaseInitiated state
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Initial,
                    action = { _, data -> handleUserCancelledFromPurchase(data as? SubscriptionData) }
                )

                // FIXED: Add missing UserCancelled transition from PurchasePending state
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Initial,
                    action = { _, data -> handleUserCancelledFromPurchase(data as? SubscriptionData) }
                )

                // Failure transitions - FIXED
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.PurchaseFailed("", null),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseFailed },
                    action = { event, _ ->
                        val failureEvent = event as SubscriptionEvent.PurchaseFailed
                        handlePurchaseFailed(failureEvent.error, failureEvent.billingResult)
                    }
                )

                // Payment successful transition from pending
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        val paymentEvent = event as SubscriptionEvent.PaymentSuccessful
                        handlePaymentSuccessful(paymentEvent.purchaseDetail)
                    }
                )
                // Active state transitions
                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Cancelled,
                    action = { _, data -> handleUserCancelled(data as? SubscriptionData) }
                )

                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpired(data as? SubscriptionData) }
                )

                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                )

                // Cancelled state transitions - FIXED
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpired(data as? SubscriptionData) }
                )

                /*addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        val paymentEvent = event as SubscriptionEvent.PaymentSuccessful
                        handlePaymentSuccessful(paymentEvent.purchaseDetail)
                    }
                )*/

                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                )

                // Expired state transitions - FIXED
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )

                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        val restoreEvent = event as SubscriptionEvent.SubscriptionRestored
                        handleSubscriptionRestored(restoreEvent.purchaseDetail)
                    }
                )

                // Revoked state transitions - FIXED
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )

                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        val restoreEvent = event as SubscriptionEvent.SubscriptionRestored
                        handleSubscriptionRestored(restoreEvent.purchaseDetail)
                    }
                )

                // System transitions from any state
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        val paymentEvent = event as SubscriptionEvent.PaymentSuccessful
                        handlePaymentSuccessful(paymentEvent.purchaseDetail)
                    }
                )

                // Error transitions from any problematic state - FIXED
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.BillingError("", BillingResult.newBuilder().build()),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.BillingError },
                    action = { event, _ ->
                        val errorEvent = event as SubscriptionEvent.BillingError
                        handleBillingError(errorEvent.error, errorEvent.billingResult)
                    }
                )

                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.BillingError("", BillingResult.newBuilder().build()),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.BillingError },
                    action = { event, _ ->
                        val errorEvent = event as SubscriptionEvent.BillingError
                        handleBillingError(errorEvent.error, errorEvent.billingResult)
                    }
                )

                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.DatabaseError(""),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.DatabaseError },
                    action = { event, _ ->
                        val errorEvent = event as SubscriptionEvent.DatabaseError
                        handleDatabaseError(errorEvent.error)
                    }
                )

                // handle state from cancelled state to initial state
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                // handle state from error state to payment initiated state
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated,
                    action = { _, _ -> handleSystemCheck() }
                )

                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                // handle state from cancelled state to purchase initiated state
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated,
                    action = { _, _ -> handleSystemCheck() }
                )

                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated,
                    action = { _, _ -> handleSystemCheck() }
                )

                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated,
                    action = { _, _ -> handleSystemCheck() }
                )

                // Event handlers
                onStateChanged { fromState, toState, event ->
                    Logger.i(
                        LOG_IAB,
                        "$TAG: State changed from ${fromState.name} to ${toState.name} on event ${event.name}"
                    )
                }

                onTransitionFailed { fromState, event, error ->
                    Logger.e(
                        LOG_IAB,
                        "$TAG: Transition failed from ${fromState.name} on event ${event.name}: $error"
                    )
                }

                onInvalidTransition { fromState, event ->
                    Logger.w(
                        LOG_IAB,
                        "$TAG: Invalid transition attempted from ${fromState.name} on event ${event.name}"
                    )
                }
            }

        Logger.i(LOG_IAB, "$TAG: Initializing SubscriptionStateMachineV2")
        initializeStateMachine()
        Logger.i(LOG_IAB, "$TAG: SubscriptionStateMachineV2 initialized")

    }

    // Define states
    sealed class SubscriptionState : State {
        object Uninitialized : SubscriptionState()
        object Initial : SubscriptionState()
        object PurchaseInitiated : SubscriptionState()
        object PurchasePending : SubscriptionState()
        object Active : SubscriptionState()
        object Cancelled : SubscriptionState()
        object Expired : SubscriptionState()
        object Revoked : SubscriptionState()
        object Error : SubscriptionState()

        val isActive: Boolean get() = this is Active
        val canMakePurchase: Boolean get() = this is Initial || this is Expired || this is Revoked || this is Cancelled || this is Error || this is PurchaseInitiated || this is PurchasePending
        val hasValidSubscription: Boolean get() = this is Active
        val isCancelled: Boolean get() = this is Cancelled
        val isRevoked: Boolean get() = this is Revoked
        val isExpired: Boolean get() = this is Expired
        fun state(): SubscriptionState {
            return when (this) {
                is Uninitialized -> Initial
                is Initial -> this
                is PurchaseInitiated -> this
                is PurchasePending -> this
                is Active -> this
                is Cancelled -> this
                is Expired -> this
                is Revoked -> this
                is Error -> this
            }
        }
    }

    // Define events
    sealed class SubscriptionEvent : Event {
        object Initialize : SubscriptionEvent()
        object PurchaseInitiated : SubscriptionEvent()
        data class PurchaseCompleted(val purchaseDetail: PurchaseDetail) : SubscriptionEvent()
        data class PurchaseFailed(val error: String, val billingResult: BillingResult? = null) : SubscriptionEvent()
        data class PaymentSuccessful(val purchaseDetail: PurchaseDetail) : SubscriptionEvent()
        object UserCancelled : SubscriptionEvent()
        object SubscriptionExpired : SubscriptionEvent()
        object SubscriptionRevoked : SubscriptionEvent()
        data class SubscriptionRestored(val purchaseDetail: PurchaseDetail) : SubscriptionEvent()
        object SystemCheck : SubscriptionEvent()
        data class BillingError(val error: String, val billingResult: BillingResult) : SubscriptionEvent()
        data class DatabaseError(val error: String) : SubscriptionEvent()
        object ErrorRecovered : SubscriptionEvent()
    }

    // Define data
    data class SubscriptionData(
        val subscriptionStatus: SubscriptionStatus,
        val purchaseDetail: PurchaseDetail? = null,
        val lastUpdated: Long = System.currentTimeMillis()
    )


    val currentState: StateFlow<SubscriptionState> = stateMachine.currentState


    private fun initializeStateMachine() {
        io {
            initialize()
            handleSystemCheckAndDatabaseRestoration()
            // Add missing Flow properties for reactive state observation
            currentState.to(stateMachine.currentState)
        }
    }


    // Helper function to create dummy purchase detail for event matching
    private fun createDummyPurchaseDetail(): PurchaseDetail {
        return PurchaseDetail(
            productId = "",
            planId = "",
            productTitle = "",
            state = Purchase.PurchaseState.PURCHASED,
            planTitle = "",
            purchaseToken = "",
            productType = com.android.billingclient.api.BillingClient.ProductType.SUBS,
            purchaseTime = "",
            purchaseTimeMillis = 0L,
            isAutoRenewing = false,
            accountId = "",
            payload = "",
            expiryTime = 0L,
            status = Purchase.PurchaseState.PURCHASED
        )
    }

    suspend fun initialize() {
        Logger.i(LOG_IAB, "$TAG: Initializing subscription state machine")
        stateMachine.processEvent(SubscriptionEvent.Initialize)
        Logger.i(LOG_IAB, "$TAG: Subscription state machine initialized")
    }

    suspend fun startPurchase() {
        stateMachine.processEvent(SubscriptionEvent.PurchaseInitiated)
    }

    suspend fun completePurchase(purchaseDetail: PurchaseDetail) {
        stateMachine.processEvent(SubscriptionEvent.PurchaseCompleted(purchaseDetail))
    }

    suspend fun paymentSuccessful(purchaseDetail: PurchaseDetail) {
        stateMachine.processEvent(SubscriptionEvent.PaymentSuccessful(purchaseDetail))
    }

    suspend fun purchaseFailed(error: String, billingResult: BillingResult? = null) {
        stateMachine.processEvent(SubscriptionEvent.PurchaseFailed(error, billingResult))
    }

    suspend fun userCancelled() {
        stateMachine.processEvent(SubscriptionEvent.UserCancelled)
    }

    suspend fun subscriptionExpired() {
        stateMachine.processEvent(SubscriptionEvent.SubscriptionExpired)
    }

    suspend fun subscriptionRevoked() {
        stateMachine.processEvent(SubscriptionEvent.SubscriptionRevoked)
    }

    suspend fun restoreSubscription(purchaseDetail: PurchaseDetail) {
        stateMachine.processEvent(SubscriptionEvent.SubscriptionRestored(purchaseDetail))
    }

    suspend fun systemCheck() {
        stateMachine.processEvent(SubscriptionEvent.SystemCheck)
    }

    fun getCurrentState(): SubscriptionState = stateMachine.getCurrentState()

    fun getSubscriptionData(): SubscriptionData? = stateMachine.getCurrentData()

    fun canMakePurchase(): Boolean = stateMachine.getCurrentState().canMakePurchase

    fun hasValidSubscription(): Boolean = stateMachine.getCurrentState().hasValidSubscription

    fun isSubscriptionActive(): Boolean = stateMachine.getCurrentState().isActive

    fun getStatistics(): StateMachineStatistics = stateMachine.getStatistics()

    // Action handlers

    private suspend fun handleInitialize() {
        try {
            Logger.i(LOG_IAB, "$TAG: ***Initializing subscription state machine***")

            // Load state from database
            /*val dbStateInfo = dbSyncService.loadStateFromDatabase()
            Logger.i(LOG_IAB, "$TAG: Loaded state from database: $dbStateInfo")
            if (dbStateInfo != null) {
                Logger.d(LOG_IAB, "$TAG: Found database state: ${dbStateInfo.recommendedState.name} for subscription: ${dbStateInfo.currentSubscription.productId}")

                val subscriptionData = SubscriptionData(subscriptionStatus = dbStateInfo.currentSubscription)
                stateMachine.updateData(subscriptionData)

                // Directly transition to the recommended state from database
                stateMachine.directTransitionTo(dbStateInfo.recommendedState)
                stateMachine.processEvent(dbStateInfo.recommendedState)

                Logger.i(LOG_IAB, "$TAG: State machine initialized with state: ${stateMachine.getCurrentState().name}")
            } else {
                Logger.d(LOG_IAB, "$TAG: No existing subscription data found in database - staying in Initial state")
                // Transition from Uninitialized to Initial
                stateMachine.directTransitionTo(SubscriptionState.Initial)
            }*/
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error during initialization: ${e.message}", e)
            // Continue with initial state on error
            //stateMachine.directTransitionTo(SubscriptionState.Initial)
        }
    }


    private suspend fun handlePurchaseCompleted(purchaseDetail: PurchaseDetail) {
        try {
            Logger.i(LOG_IAB, "$TAG: Handling purchase completed: ${purchaseDetail.productId}")

            // Save purchase detail to database (handles duplicates properly)
            val subscriptionId = dbSyncService.savePurchaseDetail(purchaseDetail)
            Logger.d(LOG_IAB, "$TAG: Purchase detail saved/updated with ID: $subscriptionId")

            if (subscriptionId > 0) {
                // Create subscription data with the correct ID
                val subscriptionStatus =
                    convertPurchaseDetailToSubscriptionStatus(purchaseDetail, subscriptionId)
                val subscriptionData = SubscriptionData(
                    subscriptionStatus = subscriptionStatus,
                    purchaseDetail = purchaseDetail
                )

                stateMachine.updateData(subscriptionData)

                // FIXED: Save state transition history
                dbSyncService.saveStateTransition(
                    fromState = stateMachine.getCurrentState(),  // Use actual current state
                    toState = SubscriptionState.PurchasePending,  // Transition to pending
                    subscriptionData = subscriptionData,
                    reason = "Purchase completed, awaiting acknowledgment"
                )

                Logger.i(LOG_IAB, "$TAG: Purchase completed and saved to database with history")
            } else {
                Logger.e(LOG_IAB, "$TAG: Failed to save purchase detail")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling purchase completed: ${e.message}", e)
        }
    }

    // Helper function to convert PurchaseDetail to SubscriptionStatus
    private fun convertPurchaseDetailToSubscriptionStatus(
        purchaseDetail: PurchaseDetail,
        subscriptionId: Long
    ): SubscriptionStatus {
        val accExpiry = RpnProxyManager.getExpiryFromPayload(purchaseDetail.payload)
            ?: purchaseDetail.expiryTime
        val subscriptionStatus = SubscriptionStatus()
        subscriptionStatus.id = subscriptionId.toInt()
        subscriptionStatus.accountId = purchaseDetail.accountId
        subscriptionStatus.purchaseToken = purchaseDetail.purchaseToken
        subscriptionStatus.productId = purchaseDetail.productId
        subscriptionStatus.planId = purchaseDetail.planId
        subscriptionStatus.productTitle = purchaseDetail.productTitle
        subscriptionStatus.state = purchaseDetail.state
        subscriptionStatus.purchaseTime = purchaseDetail.purchaseTimeMillis
        subscriptionStatus.billingExpiry = purchaseDetail.expiryTime
        subscriptionStatus.accountExpiry = accExpiry
        subscriptionStatus.developerPayload = purchaseDetail.payload
        subscriptionStatus.status = purchaseDetail.status
        subscriptionStatus.lastUpdatedTs = System.currentTimeMillis()
        return subscriptionStatus
    }

    private fun createPurchaseDetailFromSubscription(subscriptionStatus: SubscriptionStatus): PurchaseDetail {
        return PurchaseDetail(
            productId = subscriptionStatus.productId,
            planId = subscriptionStatus.planId,
            productTitle = subscriptionStatus.productTitle,
            state = subscriptionStatus.state,
            planTitle = subscriptionStatus.productTitle,
            purchaseToken = subscriptionStatus.purchaseToken,
            productType = BillingClient.ProductType.SUBS,
            purchaseTime = Utilities.convertLongToTime(
                subscriptionStatus.purchaseTime,
                Constants.TIME_FORMAT_4
            ),
            purchaseTimeMillis = subscriptionStatus.purchaseTime,
            isAutoRenewing = true, // Default for subscriptions
            accountId = subscriptionStatus.accountId,
            payload = subscriptionStatus.developerPayload,
            expiryTime = subscriptionStatus.billingExpiry,
            status = subscriptionStatus.status
        )
    }

    // not a valid state transition, but makes an entry in the database for maintaining history
    private suspend fun handlePurchaseFailed(error: String, billingResult: BillingResult?) {
        try {
            Logger.e(LOG_IAB, "$TAG: Handling purchase failed: $error")
            // put an entry in the database history as purchase failed
            dbSyncService.savePurchaseFailureHistory(error, billingResult)
            Logger.i(LOG_IAB, "$TAG: Purchase failure handled")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling purchase failure: ${e.message}", e)
        }
    }

    private suspend fun handlePaymentSuccessful(purchaseDetail: PurchaseDetail) {
        try {
            Logger.i(LOG_IAB, "$TAG: Handling payment successful: ${purchaseDetail.productId}")

            // Get existing subscription instead of creating new one
            val existingSubscription = getCurrentSubscriptionStatus(purchaseDetail)

            if (existingSubscription != null) {
                // Update existing subscription to active
                val accExpiry = RpnProxyManager.getExpiryFromPayload(purchaseDetail.payload)
                    ?: purchaseDetail.expiryTime
                existingSubscription.status = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
                existingSubscription.billingExpiry = purchaseDetail.expiryTime
                existingSubscription.accountExpiry = accExpiry
                existingSubscription.sessionToken =
                    RpnProxyManager.getSessionTokenFromPayload(purchaseDetail.payload)
                existingSubscription.developerPayload = purchaseDetail.payload

                val updateResult = subscriptionDb.upsert(existingSubscription)

                if (updateResult > 0) {
                    // Update state machine data
                    val subscriptionData = SubscriptionData(
                        subscriptionStatus = existingSubscription,
                        purchaseDetail = purchaseDetail
                    )
                    stateMachine.updateData(subscriptionData)

                    // FIXED: Use actual current state instead of hardcoded fromState
                    val currentState = stateMachine.getCurrentState()
                    dbSyncService.saveStateTransition(
                        fromState = currentState,  // Use actual current state
                        toState = SubscriptionState.Active,
                        subscriptionData = subscriptionData,
                        reason = "Payment successful from ${currentState.name}"
                    )

                    // Activate RPN if needed
                    scope.launch {
                        try {
                            val res = RpnProxyManager.processRpnPurchase(
                                purchaseDetail,
                                existingSubscription
                            )
                            Logger.d(
                                LOG_IAB,
                                "$TAG: RPN activated? $res for purchase: ${purchaseDetail.productId}"
                            )
                        } catch (e: Exception) {
                            Logger.e(LOG_IAB, "$TAG: err activating RPN: ${e.message}", e)
                        }
                    }

                    Logger.i(
                        LOG_IAB,
                        "$TAG: Payment successful handled from state: ${currentState.name}"
                    )
                } else {
                    Logger.e(LOG_IAB, "$TAG: Failed to update subscription status")
                }
            } else {
                Logger.e(LOG_IAB, "$TAG: No existing subscription found for payment")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: err handling payment successful: ${e.message}", e)
        }
    }

    private suspend fun handleUserCancelled(data: SubscriptionData?) {
        try {
            Logger.i(LOG_IAB, "$TAG: Handling user cancellation")

            data?.let { subscriptionData ->
                subscriptionData.subscriptionStatus.status =
                    SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id

                val updateResult = subscriptionDb.upsert(subscriptionData.subscriptionStatus)

                if (updateResult > 0) {
                    // Update state machine data
                    val updatedData =
                        subscriptionData.copy(subscriptionStatus = subscriptionData.subscriptionStatus)
                    stateMachine.updateData(updatedData)

                    // Save state transition
                    dbSyncService.saveStateTransition(
                        fromState = SubscriptionState.Active,
                        toState = SubscriptionState.Cancelled,
                        subscriptionData = updatedData,
                        reason = "User cancelled subscription"
                    )

                    Logger.i(LOG_IAB, "$TAG: User cancellation handled")
                } else {
                    Logger.e(LOG_IAB, "$TAG: Failed to update subscription for cancellation")
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling user cancellation: ${e.message}", e)
        }
    }

    private suspend fun handleUserCancelledFromPurchase(data: SubscriptionData?) {
        try {
            Logger.i(LOG_IAB, "$TAG: Handling user cancellation from purchase flow")

            // For cancellations during purchase flow, we don't need to update any subscription
            // status since the purchase never completed. Just log the cancellation.
            Logger.i(LOG_IAB, "$TAG: User cancelled purchase flow - returning to initial state")

            // Optionally save this event for analytics
            data?.let { subscriptionData ->
                dbSyncService.saveStateTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    toState = SubscriptionState.Initial,
                    subscriptionData = subscriptionData,
                    reason = "User cancelled purchase flow"
                )
            }
        } catch (e: Exception) {
            Logger.e(
                LOG_IAB,
                "$TAG: Error handling user cancellation from purchase: ${e.message}",
                e
            )
        }
    }

    private suspend fun handleSystemCheckAndDatabaseRestoration() {
        try {
            Logger.i(LOG_IAB, "$TAG: Performing system check and database restoration")

            // First perform system check
            val isRpnActive = RpnProxyManager.isRpnActive()
            Logger.i(LOG_IAB, "$TAG: RPN active status: $isRpnActive")

            // Then check for database state restoration
            val dbStateInfo = dbSyncService.loadStateFromDatabase()
            if (dbStateInfo != null) {
                Logger.i(
                    LOG_IAB,
                    "$TAG: Found database state: ${dbStateInfo.recommendedState.name}"
                )

                val subscriptionData =
                    SubscriptionData(subscriptionStatus = dbStateInfo.currentSubscription)
                stateMachine.updateData(subscriptionData)

                // Trigger appropriate events to transition to the correct state
                when (dbStateInfo.recommendedState) {
                    SubscriptionState.Active -> {
                        val purchaseDetail =
                            createPurchaseDetailFromSubscription(dbStateInfo.currentSubscription)
                        stateMachine.processEvent(SubscriptionEvent.PaymentSuccessful(purchaseDetail))
                    }

                    SubscriptionState.Cancelled -> {
                        // check if the billing expiry is in the past then handle as expired
                        val isExpired =
                            dbStateInfo.currentSubscription.billingExpiry < System.currentTimeMillis()
                        if (isExpired) {
                            stateMachine.processEvent(SubscriptionEvent.UserCancelled)
                        } else {
                            stateMachine.processEvent(SubscriptionEvent.UserCancelled)
                        }
                    }

                    SubscriptionState.Expired -> {
                        stateMachine.processEvent(SubscriptionEvent.SubscriptionExpired)
                    }

                    SubscriptionState.Revoked -> {
                        stateMachine.processEvent(SubscriptionEvent.SubscriptionRevoked)
                    }

                    SubscriptionState.Initial -> {
                        Logger.i(
                            LOG_IAB,
                            "$TAG: Database state matches current state - no transition needed"
                        )
                    }

                    SubscriptionState.PurchasePending -> {
                        val purchaseDetail =
                            createPurchaseDetailFromSubscription(dbStateInfo.currentSubscription)
                        stateMachine.processEvent(SubscriptionEvent.PurchaseCompleted(purchaseDetail))
                    }

                    SubscriptionState.PurchaseInitiated -> {
                        stateMachine.processEvent(SubscriptionEvent.Initialize)
                    }

                    else -> {
                        Logger.w(
                            LOG_IAB,
                            "$TAG: Unexpected database state: ${dbStateInfo.recommendedState.name}"
                        )
                    }
                }

                Logger.i(LOG_IAB, "$TAG: Database state restoration completed")
            } else {
                Logger.d(LOG_IAB, "$TAG: No database state to restore - system check completed")
            }
        } catch (e: Exception) {
            Logger.e(
                LOG_IAB,
                "$TAG: Error during system check and database restoration: ${e.message}",
                e
            )
        }
    }

    private suspend fun handleSubscriptionExpired(data: SubscriptionData?) {
        try {
            Logger.w(LOG_IAB, "$TAG: Handling subscription expiry")

            data?.let { subscriptionData ->
                subscriptionData.subscriptionStatus.status =
                    SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id

                val updateResult = subscriptionDb.upsert(subscriptionData.subscriptionStatus)

                if (updateResult > 0) {
                    // Update state machine data
                    val updatedData =
                        subscriptionData.copy(subscriptionStatus = subscriptionData.subscriptionStatus)
                    stateMachine.updateData(updatedData)

                    // Save state transition
                    dbSyncService.saveStateTransition(
                        fromState = stateMachine.getCurrentState(),
                        toState = SubscriptionState.Expired,
                        subscriptionData = updatedData,
                        reason = "Subscription expired"
                    )

                    // Update expiry information
                    dbSyncService.updateSubscriptionExpiry(
                        subscriptionData.subscriptionStatus.id,
                        subscriptionData.subscriptionStatus.billingExpiry,
                        subscriptionData.subscriptionStatus.accountExpiry
                    )

                    // Deactivate RPN
                    scope.launch {
                        try {
                            RpnProxyManager.deactivateRpn("expired")
                            Logger.i(LOG_IAB, "$TAG: RPN deactivated due to expiry")
                        } catch (e: Exception) {
                            Logger.e(LOG_IAB, "$TAG: Error deactivating RPN: ${e.message}", e)
                        }
                    }

                    Logger.w(LOG_IAB, "$TAG: Subscription expiry handled")
                } else {
                    Logger.e(LOG_IAB, "$TAG: Failed to update subscription for expiry")
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling subscription expiry: ${e.message}", e)
        }
    }

    private suspend fun handleSubscriptionRestored(purchaseDetail: PurchaseDetail) {
        try {
            Logger.i(LOG_IAB, "$TAG: Handling subscription restored: ${purchaseDetail.productId}")

            // Get existing subscription instead of creating new one
            val existingSubscription = getCurrentSubscriptionStatus(purchaseDetail)

            if (existingSubscription != null) {
                // Update existing subscription to active
                existingSubscription.status = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id

                val updateResult = subscriptionDb.upsert(existingSubscription)

                if (updateResult > 0) {
                    // Update state machine data
                    val subscriptionData = SubscriptionData(
                        subscriptionStatus = existingSubscription,
                        purchaseDetail = purchaseDetail
                    )
                    stateMachine.updateData(subscriptionData)

                    // Save state transition (this will now properly update history)
                    dbSyncService.saveStateTransition(
                        fromState = SubscriptionState.Cancelled,
                        toState = SubscriptionState.Active,
                        subscriptionData = subscriptionData,
                        reason = "Subscription restored"
                    )

                    // Activate RPN if needed
                    scope.launch {
                        try {
                            RpnProxyManager.activateRpn(purchaseDetail)
                            Logger.i(LOG_IAB, "$TAG: RPN activated successfully")
                        } catch (e: Exception) {
                            Logger.e(LOG_IAB, "$TAG: Error activating RPN: ${e.message}", e)
                        }
                    }

                    Logger.i(LOG_IAB, "$TAG: Subscription restored handled")
                } else {
                    Logger.e(LOG_IAB, "$TAG: Failed to update subscription status")
                }
            } else {
                Logger.e(LOG_IAB, "$TAG: No existing subscription found for restoration")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling subscription restoration: ${e.message}", e)
        }
    }

    private suspend fun handleSubscriptionRevoked(data: SubscriptionData?) {
        try {
            Logger.w(LOG_IAB, "$TAG: Handling subscription revocation")

            data?.let { subscriptionData ->
                subscriptionData.subscriptionStatus.status =
                    SubscriptionStatus.SubscriptionState.STATE_REVOKED.id

                val updateResult = subscriptionDb.upsert(subscriptionData.subscriptionStatus)

                if (updateResult > 0) {
                    // Update state machine data
                    val updatedData =
                        subscriptionData.copy(subscriptionStatus = subscriptionData.subscriptionStatus)
                    stateMachine.updateData(updatedData)

                    // Mark as revoked in database (this handles history)
                    dbSyncService.markSubscriptionRevoked(updatedData.subscriptionStatus.id)

                    // Save state transition
                    dbSyncService.saveStateTransition(
                        fromState = stateMachine.getCurrentState(),
                        toState = SubscriptionState.Revoked,
                        subscriptionData = updatedData,
                        reason = "Subscription revoked by system"
                    )

                    // Immediately deactivate RPN
                    scope.launch {
                        try {
                            RpnProxyManager.deactivateRpn("revoked")
                            Logger.w(LOG_IAB, "$TAG: RPN deactivated due to revocation")
                        } catch (e: Exception) {
                            Logger.e(LOG_IAB, "$TAG: Error deactivating RPN: ${e.message}", e)
                        }
                    }

                    Logger.w(LOG_IAB, "$TAG: Subscription revocation handled")
                } else {
                    Logger.e(LOG_IAB, "$TAG: Failed to update subscription for revocation")
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling subscription revocation: ${e.message}", e)
        }
    }

    /**
     * Get current subscription status - prevents duplicate creation
     */
    private suspend fun getCurrentSubscriptionStatus(purchaseDetail: PurchaseDetail): SubscriptionStatus? {
        return try {
            // First try by purchase token
            subscriptionDb.getByPurchaseToken(purchaseDetail.purchaseToken)
                ?: subscriptionDb.getCurrentSubscription()
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error getting current subscription: ${e.message}", e)
            null
        }
    }

    private suspend fun handleSystemCheck() {
        try {
            Logger.i(LOG_IAB, "$TAG: performing system check")

            // TODO: perform any necessary system checks here
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: err during system check: ${e.message}", e)
        }
    }

    private suspend fun handleErrorRecovery() {
        try {
            Logger.i(LOG_IAB, "$TAG: Handling error recovery")

            // Clear any error state and attempt to reload from database
            val dbStateInfo = dbSyncService.loadStateFromDatabase()
            if (dbStateInfo != null) {
                val subscriptionData =
                    SubscriptionData(subscriptionStatus = dbStateInfo.currentSubscription)
                stateMachine.updateData(subscriptionData)
                Logger.i(LOG_IAB, "$TAG: Error recovery completed, restored state from database")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error during error recovery: ${e.message}", e)
        }
    }

    private suspend fun handleBillingError(error: String, billingResult: BillingResult) {
        try {
            Logger.e(LOG_IAB, "$TAG: Handling billing error: $error")

            stateMachine.getCurrentData()?.let { data ->
                dbSyncService.saveStateTransition(
                    fromState = stateMachine.getCurrentState(),
                    toState = SubscriptionState.Error,
                    subscriptionData = data,
                    reason = "Billing error: $error (${billingResult.responseCode})"
                )
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling billing error: ${e.message}", e)
        }
    }

    private fun handleDatabaseError(error: String) {
        try {
            Logger.e(LOG_IAB, "$TAG: Handling database error: $error")
            // Don't try to save to database since that's what failed
            // Just log the error and potentially trigger recovery
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling database error: ${e.message}", e)
        }
    }

    private suspend fun handlePaymentInitiated(purchaseDetail: PurchaseDetail) {
        try {
            Logger.i(
                LOG_IAB,
                "$TAG: Handling payment initiated for purchase: ${purchaseDetail.productId}"
            )

            // Update the existing subscription status to indicate payment processing has started
            val existingSubscription = getCurrentSubscriptionStatus(purchaseDetail)

            if (existingSubscription != null) {
                // Mark subscription as pending payment processing
                existingSubscription.status = SubscriptionStatus.SubscriptionState.STATE_INITIAL.id

                val updateResult = subscriptionDb.upsert(existingSubscription)

                if (updateResult > 0) {
                    // Update state machine data
                    val subscriptionData = SubscriptionData(
                        subscriptionStatus = existingSubscription,
                        purchaseDetail = purchaseDetail
                    )
                    stateMachine.updateData(subscriptionData)

                    // Save state transition
                    dbSyncService.saveStateTransition(
                        fromState = SubscriptionState.Initial,
                        toState = SubscriptionState.PurchaseInitiated,
                        subscriptionData = subscriptionData,
                        reason = "Payment processing initiated"
                    )

                    Logger.i(
                        LOG_IAB,
                        "$TAG: Payment initiation handled - subscription marked as pending"
                    )
                } else {
                    Logger.e(LOG_IAB, "$TAG: Failed to update subscription for payment initiation")
                }
            } else {
                Logger.e(LOG_IAB, "$TAG: No existing subscription found for payment initiation")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: Error handling payment initiation: ${e.message}", e)
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
