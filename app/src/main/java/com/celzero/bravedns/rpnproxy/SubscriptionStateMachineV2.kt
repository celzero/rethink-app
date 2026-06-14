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
package com.celzero.bravedns.rpnproxy

import Logger
import Logger.LOG_IAB
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.InAppBillingHandler.ONE_TIME_PRODUCT_2YRS
import com.celzero.bravedns.iab.InAppBillingHandler.ONE_TIME_PRODUCT_5YRS
import com.celzero.bravedns.iab.InAppBillingHandler.ONE_TIME_PRODUCT_ID
import com.celzero.bravedns.iab.InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID
import com.celzero.bravedns.iab.InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
import com.celzero.bravedns.iab.InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
import com.celzero.bravedns.iab.InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Subscription State Machine V2.
 *
 * 1. **Google Play is the single source of truth.** Every field in [SubscriptionStatus]
 *    must reflect what Play returned. The local DB is only used to restore the in-memory
 *    state between app launches; it is always reconciled against Play on connect.
 * 2. **Expiry comes exclusively from Play** via [InAppBillingHandler.calculateExpiryTime].
 */
class SubscriptionStateMachineV2 : KoinComponent {

    private val subscriptionDb by inject<SubscriptionStatusRepository>()
    private val dbSyncService: StateMachineDatabaseSyncService by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Lock ordering (must always be acquired in this order to prevent deadlocks)
    // 1. InAppBillingHandler.connectionMutex(outermost protects billing API calls)
    // 2. stateLock(protects state machine transitions)
    // 3. SubscriptionStatusRepository.mutex(innermost protects DB writes)
    // Never acquire a higher-numbered lock while holding a lower-numbered one.
    // fixme: see if these locks can be removed/made into single transaction
    private val stateLock = Mutex()
    var stateMachine: StateMachine<SubscriptionState, SubscriptionEvent, SubscriptionData>

    companion object {
        private const val TAG = "SubscriptionStateMachineV2"

        // do not expire a subscription whose lastUpdatedTs is within
        // this window, even if Play returned an empty snapshot.
        // REMOVED: the 3-empty-query threshold is the correct guard against
        // transient Play misses; a time-based guard overrides Play's authority.
        // private const val RECENTLY_ACTIVE_GUARD_MS = 2 * 60 * 60 * 1000L

        fun isInAppProduct(productId: String): Boolean =
            productId == ONE_TIME_PRODUCT_ID ||
            productId == ONE_TIME_PRODUCT_2YRS ||
            productId == ONE_TIME_PRODUCT_5YRS ||
            productId == ONE_TIME_TEST_PRODUCT_ID ||
            productId.contains("onetime", ignoreCase = true) ||
            productId.contains("inapp",   ignoreCase = true)

        /**
         * Guard window after a local server-driven cancel/revoke during which a Play
         * reconcile will NOT overwrite the locally-set CANCELLED/REVOKED status.
         *
         * When the user cancels or revokes via our server, we immediately update the
         * state machine and DB. A concurrent or shortly-following Play reconcile may
         * still return the purchase with `purchaseState=PURCHASED` / `isAutoRenewing=true`
         * because Google Play hasn't yet propagated the server-side change.
         *
         * Without this guard `handlePaymentSuccessful` would overwrite CANCELLED/REVOKED
         * back to ACTIVE. After this window, Play is expected to have caught up and
         * reconcile is allowed to take over.
         *
         * 5 minutes is enough to cover normal Play propagation delays while still
         * converging on the correct state in any edge case.
         */
        private const val LOCAL_CANCEL_REVOKE_GUARD_MS = 5 * 60 * 1000L // 5 minutes
    }

    init {
        stateMachine =
            createStateMachine<SubscriptionState, SubscriptionEvent, SubscriptionData>(
                initialState = SubscriptionState.Uninitialized,
                tag = TAG
            ) {
                addTransition(
                    fromState = SubscriptionState.Uninitialized,
                    event = SubscriptionEvent.Initialize,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleInitialize() }
                )

                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.Initialize,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleInitialize() }
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                // Cold-start restore from Initial (first init after machine moved to Initial)
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        handleSubscriptionRestored((event as SubscriptionEvent.SubscriptionRestored).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.PurchaseCompleted(createDummyPurchaseDetail()),
                    toState = SubscriptionState.PurchasePending,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseCompleted },
                    action = { event, _ ->
                        handlePurchaseCompleted((event as SubscriptionEvent.PurchaseCompleted).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Cancelled,
                    action = { _, data -> handleUserCancelled(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.BillingError("", -1),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.BillingError },
                    action = { event, _ ->
                        val e = event as SubscriptionEvent.BillingError
                        handleBillingError(e.error, e.billingResultCode)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Initial,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.PurchaseCompleted(createDummyPurchaseDetail()),
                    toState = SubscriptionState.PurchasePending,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseCompleted },
                    action = { event, _ ->
                        handlePurchaseCompleted((event as SubscriptionEvent.PurchaseCompleted).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Initial,
                    action = { _, data -> handleUserCancelledFromPurchase(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.PurchaseFailed("", null),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseFailed },
                    action = { event, _ ->
                        val e = event as SubscriptionEvent.PurchaseFailed
                        handlePurchaseFailed(e.error, e.billingResultCode)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.BillingError("", -1),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.BillingError },
                    action = { event, _ ->
                        val e = event as SubscriptionEvent.BillingError
                        handleBillingError(e.error, e.billingResultCode)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.PurchaseCompleted(createDummyPurchaseDetail()),
                    toState = SubscriptionState.PurchasePending,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseCompleted },
                    action = { event, _ ->
                        handlePurchaseCompleted((event as SubscriptionEvent.PurchaseCompleted).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Initial,
                    action = { _, data -> handleUserCancelledFromPurchase(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.BillingError("", -1),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.BillingError },
                    action = { event, _ ->
                        val e = event as SubscriptionEvent.BillingError
                        handleBillingError(e.error, e.billingResultCode)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.PurchaseFailed("", null),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseFailed },
                    action = { event, _ ->
                        val e = event as SubscriptionEvent.PurchaseFailed
                        handlePurchaseFailed(e.error, e.billingResultCode)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                // Renewal: Active to Active (idempotent, updates expiry)
                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                // Cold-start restore: Active to Active (idempotent, memory-only via handleSubscriptionRestored dedup)
                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        handleSubscriptionRestored((event as SubscriptionEvent.SubscriptionRestored).purchaseDetail)
                    }
                )
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
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.DatabaseError(""),
                    toState = SubscriptionState.Error,
                    guard = { event, _ -> event is SubscriptionEvent.DatabaseError },
                    action = { event, _ ->
                        handleDatabaseError((event as SubscriptionEvent.DatabaseError).error)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Active,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Active,
                    action = { _, _ -> handleSystemCheck() }
                )

                // These sub-states all share the same transition set.
                listOf(
                    SubscriptionState.Grace,
                    SubscriptionState.OnHold,
                    SubscriptionState.Paused
                ).forEach { fromState ->
                    addTransition(
                        fromState = fromState,
                        event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                        toState = SubscriptionState.Active,
                        guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                        action = { event, _ ->
                            handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                        }
                    )
                    // Cold-start restore
                    addTransition(
                        fromState = fromState,
                        event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                        toState = SubscriptionState.Active,
                        guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                        action = { event, _ ->
                            handleSubscriptionRestored((event as SubscriptionEvent.SubscriptionRestored).purchaseDetail)
                        }
                    )
                    addTransition(
                        fromState = fromState,
                        event = SubscriptionEvent.SubscriptionExpired,
                        toState = SubscriptionState.Expired,
                        action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                    )
                    addTransition(
                        fromState = fromState,
                        event = SubscriptionEvent.SubscriptionRevoked,
                        toState = SubscriptionState.Revoked,
                        action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                    )
                    addTransition(
                        fromState = fromState,
                        event = SubscriptionEvent.UserCancelled,
                        toState = SubscriptionState.Cancelled,
                        action = { _, data -> handleUserCancelled(data as? SubscriptionData) }
                    )
                    addTransition(
                        fromState = fromState,
                        event = SubscriptionEvent.SystemCheck,
                        toState = SubscriptionState.Active,
                        action = { _, _ -> handleSystemCheck() }
                    )
                }

                // Canceled + not yet expired - user still has access.
                // Play fires PaymentSuccessful when user re-subscribes.
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                // Cold-start restore: Canceled - Active in memory (billing period still valid)
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        handleSubscriptionRestored((event as SubscriptionEvent.SubscriptionRestored).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                // Idempotent: already canceled - Play confirms it again (e.g. reconcile on reconnect).
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.UserCancelled,
                    toState = SubscriptionState.Cancelled,
                    action = { _, _ ->
                        Logger.d(LOG_IAB, "$TAG: already Cancelled, re-fire ignored")
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                // Expired
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        handleSubscriptionRestored((event as SubscriptionEvent.SubscriptionRestored).purchaseDetail)
                    }
                )
                // Idempotent: already expired - Play confirms it again.
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, _ ->
                        Logger.d(LOG_IAB, "$TAG: already Expired, re-fire ignored")
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                // Revoked
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        handleSubscriptionRestored((event as SubscriptionEvent.SubscriptionRestored).purchaseDetail)
                    }
                )
                // Idempotent: already revoked - Play confirms it again.
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, _ ->
                        Logger.d(LOG_IAB, "$TAG: already Revoked, re-fire ignored")
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )

                // Error
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.ErrorRecovered,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleErrorRecovery() }
                )
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.PurchaseCompleted(createDummyPurchaseDetail()),
                    toState = SubscriptionState.PurchasePending,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseCompleted },
                    action = { event, _ ->
                        Logger.i(LOG_IAB, "$TAG: recovering from Error → PurchasePending on PurchaseCompleted")
                        handlePurchaseCompleted((event as SubscriptionEvent.PurchaseCompleted).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Initial,
                    action = { _, _ -> handleSystemCheck() }
                )
                onStateChanged { fromState, toState, event ->
                    Logger.i(LOG_IAB, "$TAG: ${fromState.name} → ${toState.name} on ${event.name}")
                }
                onTransitionFailed { fromState, event, error ->
                    Logger.e(LOG_IAB, "$TAG: Transition failed from ${fromState.name} on ${event.name}: $error")
                }
                onInvalidTransition { fromState, event ->
                    Logger.w(LOG_IAB, "$TAG: Invalid transition from ${fromState.name} on ${event.name}")
                }
            }

        Logger.i(LOG_IAB, "$TAG: Initializing SubscriptionStateMachineV2")
        initializeStateMachine()
        Logger.i(LOG_IAB, "$TAG: SubscriptionStateMachineV2 initialized")
    }

    sealed class SubscriptionState : State {
        object Uninitialized: SubscriptionState()
        object Initial: SubscriptionState()
        object PurchaseInitiated: SubscriptionState()
        object PurchasePending: SubscriptionState()
        object Active: SubscriptionState()
        object Cancelled: SubscriptionState()
        object Expired: SubscriptionState()
        object Revoked: SubscriptionState()
        object Error: SubscriptionState()
        object Grace: SubscriptionState()
        object OnHold: SubscriptionState()
        object Paused: SubscriptionState()

        val isActive: Boolean
            get() = this is Active || this is Grace

        val canMakePurchase: Boolean
            get() = this is Initial || this is Expired || this is Revoked ||
                    this is Cancelled || this is Error || this is PurchaseInitiated ||
                    this is PurchasePending || this is OnHold || this is Paused

        val hasValidSubscription: Boolean
            get() = this is Active || this is Grace || this is OnHold || this is Paused ||
                    this is Cancelled   // Cancelled: still valid until billingExpiry

        val isCancelled: Boolean get() = this is Cancelled
        val isExpired: Boolean   get() = this is Expired
        val isRevoked: Boolean   get() = this is Revoked

        fun state(): SubscriptionState = when (this) {
            is Uninitialized -> Initial
            else             -> this
        }
    }

    sealed class SubscriptionEvent : Event {
        object Initialize : SubscriptionEvent()
        object PurchaseInitiated : SubscriptionEvent()
        data class PurchaseCompleted(val purchaseDetail: PurchaseDetail) : SubscriptionEvent()
        data class PurchaseFailed(val error: String, val billingResultCode: Int? = null) : SubscriptionEvent()
        data class PaymentSuccessful(val purchaseDetail: PurchaseDetail) : SubscriptionEvent()
        object UserCancelled : SubscriptionEvent()
        object SubscriptionExpired : SubscriptionEvent()
        object SubscriptionRevoked : SubscriptionEvent()
        data class SubscriptionRestored(val purchaseDetail: PurchaseDetail) : SubscriptionEvent()
        object SystemCheck : SubscriptionEvent()
        data class BillingError(val error: String, val billingResultCode: Int = -1) : SubscriptionEvent()
        data class DatabaseError(val error: String) : SubscriptionEvent()
        object ErrorRecovered : SubscriptionEvent()
    }

    data class SubscriptionData(
        val subscriptionStatus: SubscriptionStatus,
        val purchaseDetail: PurchaseDetail? = null,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    val currentState: StateFlow<SubscriptionState> = stateMachine.currentState

    private suspend fun processEventSafely(event: SubscriptionEvent) {
        stateLock.withLock { stateMachine.processEvent(event) }
    }

    private fun initializeStateMachine() {
        scope.launch {
            initialize()
            handleSystemCheckAndDatabaseRestoration()
        }
    }

    suspend fun initialize() {
        Logger.i(LOG_IAB, "$TAG: initialize()")
        processEventSafely(SubscriptionEvent.Initialize)
    }

    suspend fun startPurchase() {
        processEventSafely(SubscriptionEvent.PurchaseInitiated)
    }

    suspend fun completePurchase(purchaseDetail: PurchaseDetail) {
        processEventSafely(SubscriptionEvent.PurchaseCompleted(purchaseDetail))
    }

    suspend fun paymentSuccessful(purchaseDetail: PurchaseDetail) {
        processEventSafely(SubscriptionEvent.PaymentSuccessful(purchaseDetail))
    }

    suspend fun purchaseFailed(error: String, billingResultCode: Int? = null) {
        processEventSafely(SubscriptionEvent.PurchaseFailed(error, billingResultCode))
    }

    suspend fun userCancelled() {
        processEventSafely(SubscriptionEvent.UserCancelled)
    }

    suspend fun subscriptionExpired() {
        processEventSafely(SubscriptionEvent.SubscriptionExpired)
    }

    suspend fun subscriptionRevoked() {
        processEventSafely(SubscriptionEvent.SubscriptionRevoked)
    }

    suspend fun restoreSubscription(purchaseDetail: PurchaseDetail) {
        processEventSafely(SubscriptionEvent.SubscriptionRestored(purchaseDetail))
    }

    suspend fun systemCheck() {
        processEventSafely(SubscriptionEvent.SystemCheck)
    }

    /**
     * Reconcile local state against the authoritative Google Play snapshot.
     *
     * - Google Play is the master: derive state purely from [Purchase] fields.
     * - [Purchase.PurchaseState.UNSPECIFIED_STATE] → [PurchasePending] (never Unknown).
     * - [SubscriptionState.Cancelled] (autoRenewing=false, not yet expired) fires
     *   [PaymentSuccessful] so the user retains access, then [updateCancelledStatusInDb]
     *   writes CANCELLED status without changing the in-memory state.
     * - **Empty SUBS snapshot**: if Play returns 0 subscriptions and [queriedProductType]
     *   is SUBS, any active/cancelled SUBS rows in the DB are no longer known to Play
     *   (revoked, refunded, fully expired). They are marked [SubscriptionState.Expired]
     *   and the DB rows are updated accordingly.  INAPP rows are never touched here.
     *
     * @param purchases           Raw Play [Purchase] objects.
     * @param productMeta         productId → Pair(planId, productTitle).
     * @param purchaseExpiryMap   purchaseToken → Play-computed expiry epoch-millis.
     * @param queriedProductType  The [BillingClient.ProductType] that was queried.
     *                            Defaults to SUBS so legacy callers are unaffected.
     */
    suspend fun reconcileWithPlayBilling(
        purchases: List<Purchase>,
        productMeta: Map<String, Pair<String, String>> = emptyMap(),
        purchaseExpiryMap: Map<String, Long> = emptyMap(),
        queriedProductType: String = BillingClient.ProductType.SUBS
    ) {
        if (purchases.isEmpty()) {
            if (queriedProductType == BillingClient.ProductType.SUBS) {
                // Play has no subscription for this account. Any active/cancelled SUBS row
                // in the local DB is stale the subscription has been revoked, refunded, or
                // fully lapsed without the app receiving a renewal callback.
                Logger.i(LOG_IAB, "$TAG: reconcile: Play returned empty SUBS snapshot, expiring active SUBS in DB")
                expireStaleSubsFromDb()
            } else {
                Logger.i(LOG_IAB, "$TAG: reconcile: Play returned empty INAPP snapshot, no action (INAPP expiry is clock-based)")
            }
            return
        }

        data class ReconcileAction(
            val detail: PurchaseDetail,
            val event: SubscriptionEvent,
            val isPlayCancelled: Boolean = false
        )

        // Phase 1 – build actions while holding the lock (read-only, no DB calls).
        val currentMachineState = stateMachine.getCurrentState()
        val currentData         = stateMachine.getCurrentData()

        val actions = stateLock.withLock {
            purchases
                .distinctBy { it.purchaseToken }
                .mapNotNull { purchase ->
                    val pId = purchase.products.firstOrNull() ?: return@mapNotNull null

                    // Resolve planId + title: try productId key first (covers the productId-level
                    // fallback added by handlePurchase), then try planId keys in productMeta.
                    // This ensures planId/planTitle are always populated even when the purchase
                    // doesn't expose basePlanId directly.
                    val (enrichedPlanId, enrichedTitle) = productMeta[pId]
                        ?: productMeta.entries.firstOrNull { (_, v) -> v.first.isNotEmpty() && pId.contains(v.first, ignoreCase = true) }?.value
                        ?: Pair("", "")

                    val playExpiry = purchaseExpiryMap[purchase.purchaseToken] ?: Long.MAX_VALUE
                    // Device ID is stored only in SecureIdentityStore (never in the DB row).
                    // Pass empty here; callers that need the real device ID for API calls
                    // resolve it from BillingBackendClient.getDeviceId().
                    val detail = purchase.toPurchaseDetail(
                        enrichedProductTitle = enrichedTitle,
                        enrichedPlanId       = enrichedPlanId,
                        playExpiry           = playExpiry,
                        existingDeviceId     = ""
                    ) ?: return@mapNotNull null

                    val targetState = deriveStateFromPlay(detail, purchase)
                    Logger.d(LOG_IAB, "$TAG: reconcile token=${purchase.purchaseToken.take(8)}, playExpiry=$playExpiry, targetState=${targetState.name}, currentState=${currentMachineState.name}")

                    val isInApp = detail.productType == BillingClient.ProductType.INAPP ||
                        isInAppProduct(detail.productId)

                    val dbStatusIsCancelled = currentData?.subscriptionStatus?.status ==
                            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id

                    val isResubscription = !isInApp && dbStatusIsCancelled && detail.isAutoRenewing

                    val tokenUnchanged = currentData?.subscriptionStatus?.purchaseToken == detail.purchaseToken
                    val dbExpiry = currentData?.subscriptionStatus?.billingExpiry ?: 0L
                    val effectiveExpiry = when {
                        // if play says unknown, use DB
                        playExpiry == Long.MAX_VALUE -> dbExpiry
                        // if DB has a future expiry (likely from server), and play's local estimate is older, keep DB
                        dbExpiry > 0L && playExpiry <= dbExpiry -> dbExpiry
                        // otherwise use play's estimate (might be a renewal)
                        else -> playExpiry
                    }
                    val expiryUnchanged = currentData != null &&
                        currentData.subscriptionStatus.billingExpiry == effectiveExpiry

                    val currentPayloadHasWs = RpnProxyManager.extractWsObject(currentData?.subscriptionStatus?.developerPayload ?: "") != null
                    val newPayloadHasWs = RpnProxyManager.extractWsObject(detail.payload) != null
                    // If current has ws but new doesn't, we consider payload "unchanged" to avoid noisy updates
                    // that would downgrade the payload.
                    val payloadUnchanged = if (currentPayloadHasWs && !newPayloadHasWs) true
                                         else currentData?.subscriptionStatus?.developerPayload == detail.payload

                    // machine already Active, Play still says Active, token+expiry+payload unchanged.
                    val skipAlreadyActive = !isResubscription &&
                        currentMachineState == SubscriptionState.Active &&
                        targetState          == SubscriptionState.Active &&
                        tokenUnchanged && expiryUnchanged && payloadUnchanged

                    val skipAlreadyCancelledValid = !isInApp && !isResubscription &&
                        currentMachineState == SubscriptionState.Active &&
                        targetState          == SubscriptionState.Cancelled &&
                        dbStatusIsCancelled &&
                        tokenUnchanged && expiryUnchanged && payloadUnchanged

                    if (skipAlreadyActive || skipAlreadyCancelledValid) {
                        Logger.d(LOG_IAB, "$TAG: reconcile skip: machine=${currentMachineState.name}, " +
                            "target=${targetState.name}, token+expiry+payload unchanged" +
                            if (skipAlreadyCancelledValid) " (DB already CANCELLED, SUBS)" else "")
                        return@mapNotNull null
                    }

                    val reconciledDetail = detail.copy(expiryTime = effectiveExpiry)
                    if (isResubscription) {
                        Logger.i(LOG_IAB, "$TAG: reconcile: SUBS resubscription detected, DB is CANCELLED but Play isAutoRenewing=true for token=${detail.purchaseToken.take(8)}, proceeding with PaymentSuccessful")
                    }

                    val isPlayCancelled = targetState == SubscriptionState.Cancelled

                    val event: SubscriptionEvent = when (targetState) {
                        SubscriptionState.PurchasePending,
                        SubscriptionState.PurchaseInitiated ->
                            SubscriptionEvent.PurchaseCompleted(reconciledDetail)

                        SubscriptionState.Active,
                        SubscriptionState.Grace,
                        SubscriptionState.OnHold,
                        SubscriptionState.Paused,
                        SubscriptionState.Cancelled ->
                            SubscriptionEvent.PaymentSuccessful(reconciledDetail)

                        SubscriptionState.Expired  -> SubscriptionEvent.SubscriptionExpired
                        SubscriptionState.Revoked  -> SubscriptionEvent.SubscriptionRevoked

                        else -> {
                            Logger.w(LOG_IAB, "$TAG: reconcile: unmapped state ${targetState.name} for token ${detail.purchaseToken.take(8)}, skipping")
                            null
                        }
                    } ?: return@mapNotNull null

                    ReconcileAction(reconciledDetail, event, isPlayCancelled)
                }
        }

        if (actions.isEmpty()) return

        // Elect the "best" action to drive the state machine data.
        // Priority: 1. SUBS, 2. Latest Expiry.
        val bestAction = actions.sortedWith(compareByDescending<ReconcileAction> {
            it.detail.productType == BillingClient.ProductType.SUBS
        }.thenByDescending {
            it.detail.expiryTime
        }).first()

        Logger.i(LOG_IAB, "$TAG: reconcile: electing best action for state machine: " +
            "token=${bestAction.detail.purchaseToken.take(8)}, prod=${bestAction.detail.productId}, " +
            "type=${bestAction.detail.productType}, expiry=${bestAction.detail.expiryTime}")

        actions.forEach { action ->
            try {
                // If this is the best action, it will drive handlePaymentSuccessful to update
                // the state machine data. If it's NOT the best action, we just want to ensure
                // it's synced to the DB, but NOT overwrite the machine's primary data.

                if (action == bestAction) {
                    processEventSafely(action.event)
                    // After activating, write CANCELLED status to DB if Play says so.
                    if (action.isPlayCancelled) {
                        stateLock.withLock {
                            updateCancelledStatusInDb(action.detail)
                        }
                    }
                } else {
                    // Just sync to DB if it's a PaymentSuccessful event.
                    if (action.event is SubscriptionEvent.PaymentSuccessful) {
                        handlePaymentSuccessful(action.detail, updateMachineData = false)
                    }
                }
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: reconcile: error processing action for token ${action.detail.purchaseToken.take(8)}: ${e.message}", e)
            }
        }

        // Phase 3 – expire SUBS DB rows whose purchaseToken is absent from the Play snapshot.
        //
        // When Play returns a non-empty list it is the COMPLETE snapshot of all active
        // subscriptions for this account. Any SUBS row whose token does not appear in that
        // list is no longer known to Play - it was revoked, refunded, or superseded by a
        // new subscription token. We expire those orphaned rows here.
        if (queriedProductType == BillingClient.ProductType.SUBS) {
            val playTokens = purchases.map { it.purchaseToken }.toSet()
            expireOrphanedSubsFromDb(playTokens)
        }
    }

    /**
     * Called after [reconcileWithPlayBilling] fires [PaymentSuccessful] for a purchase
     * where [Purchase.isAutoRenewing] = false (cancelled but not yet expired).
     *
     * Updates only the `status` column in the DB row that was already written by
     * [handlePaymentSuccessful] no second full upsert.  Records history only if
     * the status actually changed.
     */
    private suspend fun updateCancelledStatusInDb(detail: PurchaseDetail) {
        try {
            val existing = subscriptionDb.getByPurchaseToken(detail.purchaseToken)
                ?: subscriptionDb.getCurrentSubscription()
                ?: return

            if (existing.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id) {
                Logger.d(LOG_IAB, "$TAG: updateCancelledStatusInDb: already CANCELLED, no-op (DB)")
                val currentInMemoryStatus =
                    stateMachine.getCurrentData()?.subscriptionStatus?.status
                if (currentInMemoryStatus != SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id) {
                    Logger.d(LOG_IAB, "$TAG: updateCancelledStatusInDb: correcting in-memory status " +
                        "from ${SubscriptionStatus.SubscriptionState.fromId(currentInMemoryStatus ?: -1).name} " +
                        "to CANCELLED for token ${detail.purchaseToken.take(8)}")
                    stateMachine.updateData(SubscriptionData(existing, detail))
                }
                return
            }

            val prevStatus = existing.status
            existing.status       = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            existing.lastUpdatedTs = System.currentTimeMillis()
            subscriptionDb.upsert(existing)

            stateMachine.updateData(SubscriptionData(existing, detail))

            dbSyncService.recordHistoryOnly(
                subscriptionId = existing.id,
                fromStatusId   = prevStatus,
                toStatusId     = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                reason         = "Play: autoRenewing=false (cancelled, access until billingExpiry=${existing.billingExpiry})"
            )
            Logger.i(LOG_IAB, "$TAG: updateCancelledStatusInDb: status updated to CANCELLED for token ${detail.purchaseToken.take(8)}, billingExpiry=${existing.billingExpiry}")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: updateCancelledStatusInDb: ${e.message}", e)
        }
    }

    /**
     * Called when Google Play returns an empty subscription list for the SUBS product type.
     *
     * This is the authoritative signal that the user has no active subscription:
     * - Subscription was cancelled and the billing period has fully lapsed.
     * - Subscription was revoked by Google (refund / policy violation).
     * - Account changed or subscription was transferred.
     *
     * For each SUBS row that is currently active/cancelled in the DB we:
     * 1. Update its `status` to [SubscriptionStatus.SubscriptionState.STATE_EXPIRED].
     * 2. Record a history entry (fromState → Expired, reason = "Play returned empty snapshot").
     * 3. Fire [SubscriptionEvent.SubscriptionExpired] on the state machine once so the
     *    in-memory state transitions to [SubscriptionState.Expired].
     *
     * INAPP rows are intentionally skipped - their expiry is governed by the local clock.
     */
    private suspend fun expireStaleSubsFromDb() {
        try {
            // Active and Cancelled states are "still alive" from the local perspective.
            // Grace / OnHold / Paused are also Play-managed states that should be expired
            // when Play returns nothing.
            val activeStatuses = listOf(
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                SubscriptionStatus.SubscriptionState.STATE_GRACE.id,
                SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id,
                SubscriptionStatus.SubscriptionState.STATE_PAUSED.id,
                SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
                SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
            )

            val staleRows = subscriptionDb.getSubscriptionsByStates(activeStatuses)

            val subsRows = staleRows.filter { sub -> !isInAppProduct(sub.productId) }

            if (subsRows.isEmpty()) {
                Logger.d(LOG_IAB, "$TAG: expireStaleSubsFromDb: no stale SUBS rows to expire")
                return
            }

            val now = System.currentTimeMillis()

            Logger.w(LOG_IAB, "$TAG: expireStaleSubsFromDb: expiring up to ${subsRows.size} stale SUBS row(s)")

            var expiredCount = 0
            subsRows.forEach { sub ->
                try {
                    // The 3-empty-query threshold already guards against transient Play
                    // misses. When Play has consistently returned empty across multiple
                    // queries, the local state must yield — Play is the authority.
                    // No per-row recently-active guard: if Play says empty, we expire.

                    val prevStatus = sub.status
                    sub.status        = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                    sub.lastUpdatedTs = now
                    // Always clamp billingExpiry to now when expiring, so the DB never
                    // stores STATE_EXPIRED with billingExpiry=0 or a future estimate.
                    // A zero expiry would cause handleSystemCheckAndDatabaseRestoration
                    // to resurrect the subscription to Active on the next cold start.
                    if (sub.billingExpiry > now || sub.billingExpiry <= 0L ||
                        sub.billingExpiry == Long.MAX_VALUE) {
                        sub.billingExpiry = now
                        sub.accountExpiry = now
                    }
                    subscriptionDb.upsert(sub)
                    expiredCount++

                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId   = prevStatus,
                        toStatusId     = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                        reason         = "Play returned empty SUBS snapshot; subscription no longer active on Play"
                    )
                    Logger.i(LOG_IAB, "$TAG: expireStaleSubsFromDb: expired row id=${sub.id}, productId=${sub.productId}, token=${sub.purchaseToken.take(8)}")
                } catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG: expireStaleSubsFromDb: error expiring row id=${sub.id}: ${e.message}", e)
                }
            }

            if (expiredCount > 0) {
                // Before firing the event, seed the machine data with the last-expired
                // SUBS row so handleSubscriptionExpiredWithData uses it instead of
                // falling back to getCurrentSubscription(), which could return a
                // co-existing INAPP row that should NOT be expired.
                val lastExpired = subsRows.lastOrNull {
                    it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                }
                if (lastExpired != null) {
                    stateMachine.updateData(SubscriptionData(lastExpired))
                }
                // Transition the in-memory state machine to Expired once (idempotent).
                processEventSafely(SubscriptionEvent.SubscriptionExpired)
                Logger.i(LOG_IAB, "$TAG: expireStaleSubsFromDb: expired $expiredCount row(s), state machine transitioned to Expired")
            } else {
                Logger.d(LOG_IAB, "$TAG: expireStaleSubsFromDb: all rows within guard window, no rows expired")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: expireStaleSubsFromDb: ${e.message}", e)
        }
    }

    /**
     * Called during a non-empty Play SUBS reconcile to expire DB rows whose purchaseToken
     * is absent from the authoritative Play snapshot.
     *
     * These are orphaned tokens - the subscription was:
     * - Upgraded / downgraded (Play issues a new token, old token disappears).
     * - Transferred to a different account.
     * - Refunded and re-purchased (new token).
     * - Fully expired and Play has pruned it from the active list.
     *
     * [playTokens] is the complete set of tokens returned by the current Play query.
     * Any active/canceled SUBS row NOT in this set is expired in the DB + history,
     * but the in-memory state is NOT fired (the active token's event already set
     * the correct state in Phase 2).
     */
    private suspend fun expireOrphanedSubsFromDb(playTokens: Set<String>) {
        try {
            val activeStatuses = listOf(
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                SubscriptionStatus.SubscriptionState.STATE_GRACE.id,
                SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id,
                SubscriptionStatus.SubscriptionState.STATE_PAUSED.id,
                SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
                SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
            )

            val rows = subscriptionDb.getSubscriptionsByStates(activeStatuses)

            val orphaned = rows.filter { sub ->
                !isInAppProduct(sub.productId) &&
                sub.purchaseToken !in playTokens
            }

            if (orphaned.isEmpty()) {
                Logger.d(LOG_IAB, "$TAG: expireOrphanedSubsFromDb: no orphaned SUBS rows")
                return
            }

            Logger.w(LOG_IAB, "$TAG: expireOrphanedSubsFromDb: expiring ${orphaned.size} orphaned SUBS row(s) absent from Play snapshot")
            val now = System.currentTimeMillis()
            orphaned.forEach { sub ->
                try {
                    val prevStatus = sub.status
                    sub.status        = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                    sub.lastUpdatedTs = now
                    // Clamp billingExpiry: an orphaned token is absent from Play's
                    // authoritative snapshot (refunded, superseded, or transferred).
                    // Access ended when Play removed the token, not at the end of the
                    // locally-estimated billing period which may still be in the future.
                    // Always clamp: billingExpiry=0 or a future estimate both cause
                    // handleSystemCheckAndDatabaseRestoration to resurrect Expired → Active.
                    if (sub.billingExpiry > now || sub.billingExpiry <= 0L ||
                        sub.billingExpiry == Long.MAX_VALUE) {
                        sub.billingExpiry = now
                        sub.accountExpiry = now
                    }
                    subscriptionDb.upsert(sub)
                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId = prevStatus,
                        toStatusId = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                        reason = "Token absent from Play SUBS snapshot (revoked, refunded, or superseded)"
                    )
                    Logger.i(LOG_IAB, "$TAG: expireOrphanedSubsFromDb: expired id=${sub.id}, token=${sub.purchaseToken.take(8)}")
                } catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG: expireOrphanedSubsFromDb: error expiring id=${sub.id}: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: expireOrphanedSubsFromDb: ${e.message}", e)
        }
    }

    /**
     * Expire stale one-time (INAPP) purchase rows in the local DB.
     *
     * Called from [InAppBillingHandler] after processing the INAPP purchase list from Play.
     *
     * ### Why this is needed
     * Google Play permanently retains a record of one-time purchases and will keep
     * returning them with `purchaseState = PURCHASED` even after the
     * fixed access window (e.g. 2 years) has passed. Play has no concept of per-
     * entitlement expiry for INAPP products. The **local expiry calculation** (based on
     * `purchaseTime + plan duration`) is therefore the **only authority** for whether an
     * INAPP purchase is still valid.
     *
     * ### What is expired
     * For each active INAPP DB row:
     * 1. If `billingExpiry > 0` and `billingExpiry < now` → locally expired, mark Expired.
     * 2. If the row's `purchaseToken` is **not** in [playTokens] (and [playTokens] is
     *    non-empty) → absent from Play (refunded / never purchased), mark Expired.
     * 3. If [playTokens] is empty → Play returned no INAPP purchases at all, expire all
     *    active INAPP rows.
     *
     * SUBS rows are intentionally skipped, they are handled by [expireStaleSubsFromDb].
     *
     * @param playTokens The set of purchaseTokens returned by the current Play INAPP query.
     *                   Pass an empty set when Play returned an empty INAPP list.
     */
    suspend fun expireStaleInAppFromDb(playTokens: Set<String>) {
        // Track whether all INAPP rows were expired so we can fire
        // SubscriptionExpired OUTSIDE the stateLock (processEventSafely
        // acquires stateLock internally and would deadlock if called here).
        var shouldTransitionToExpired = false

        stateLock.withLock {
            try {
            val activeStatuses = listOf(
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
                SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
            )

            val rows = subscriptionDb.getSubscriptionsByStates(activeStatuses)

            val inAppRows = rows.filter { sub -> isInAppProduct(sub.productId) }

            if (inAppRows.isEmpty()) {
                Logger.d(LOG_IAB, "$TAG: expireStaleInAppFromDb: no active INAPP rows")
                return
            }

            val now = System.currentTimeMillis()
            var expiredCount = 0
            // Collect tokens that are expired in this pass so we can detect a stale state-machine
            // pointer in the remainingValid > 0 block below.
            val expiredTokens = mutableSetOf<String>()

            inAppRows.forEach { sub ->
                try {
                    val locallyExpired = sub.billingExpiry > 0L &&
                            sub.billingExpiry != Long.MAX_VALUE &&
                            sub.billingExpiry < now

                    val absentFromPlay = playTokens.isNotEmpty() &&
                            sub.purchaseToken !in playTokens

                    val noPlayRecord = playTokens.isEmpty()

                    if (!locallyExpired && !absentFromPlay && !noPlayRecord) {
                        // Still valid
                        return@forEach
                    }

                    val reason = when {
                        locallyExpired -> "INAPP access window expired: billingExpiry=${sub.billingExpiry}, now=$now"
                        absentFromPlay -> "INAPP token absent from Play snapshot (refunded or superseded)"
                        noPlayRecord -> "Play returned empty INAPP snapshot (not purchased or refunded)"
                        else -> "INAPP expired"
                    }

                    val prevStatus = sub.status
                    sub.status        = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                    sub.lastUpdatedTs = now
                    // Always clamp: billingExpiry=0 or a future estimate both cause
                    // handleSystemCheckAndDatabaseRestoration to resurrect Expired → Active.
                    if ((absentFromPlay || noPlayRecord) &&
                        (sub.billingExpiry > now || sub.billingExpiry <= 0L ||
                         sub.billingExpiry == Long.MAX_VALUE)) {
                        sub.billingExpiry = now
                        sub.accountExpiry = now
                    }
                    subscriptionDb.upsert(sub)

                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId = prevStatus,
                        toStatusId = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                        reason = reason
                    )
                    Logger.i(LOG_IAB, "$TAG: expireStaleInAppFromDb: expired id=${sub.id}, " +
                        "productId=${sub.productId}, token=${sub.purchaseToken.take(8)}, reason=$reason")
                    expiredCount++
                    expiredTokens.add(sub.purchaseToken)
                } catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG: expireStaleInAppFromDb: error expiring id=${sub.id}: ${e.message}", e)
                }
            }

            if (expiredCount > 0) {
                val remainingValid = inAppRows.size - expiredCount
                if (remainingValid > 0) {
                    Logger.i(LOG_IAB, "$TAG: expireStaleInAppFromDb: expired $expiredCount INAPP " +
                        "row(s) but $remainingValid still active — DB cleaned, state unchanged")

                    val currentToken =
                        stateMachine.getCurrentData()?.subscriptionStatus?.purchaseToken.orEmpty()
                    if (currentToken.isNotEmpty() && currentToken in expiredTokens) {
                        val validSub = inAppRows.firstOrNull { it.purchaseToken !in expiredTokens }
                        if (validSub != null) {
                            val validPd = createPurchaseDetailFromSubscription(validSub)
                            stateMachine.updateData(SubscriptionData(validSub, validPd))
                            Logger.i(
                                LOG_IAB,
                                "$TAG: expireStaleInAppFromDb: state machine re-pointed to " +
                                "still-valid sub id=${validSub.id}, " +
                                "token=${validSub.purchaseToken.take(8)}"
                            )
                            // activate RPN with the elected valid subscription
                            scope.launch {
                                try { RpnProxyManager.activateRpn(validPd) }
                                catch (e: Exception) { Logger.e(LOG_IAB, "$TAG: RPN activate error: ${e.message}", e) }
                            }
                        } else {
                            Logger.w(
                                LOG_IAB,
                                "$TAG: expireStaleInAppFromDb: no valid sub found to redirect " +
                                "state machine (unexpected; remainingValid=$remainingValid)"
                            )
                        }
                    }
                } else {
                    shouldTransitionToExpired = true
                    // Seed machine data with the last expired INAPP row so
                    // handleSubscriptionExpiredWithData targets the correct row.
                    val lastInAppExpired = inAppRows.lastOrNull {
                        it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                    }
                    if (lastInAppExpired != null) {
                        val expiredPd = createPurchaseDetailFromSubscription(lastInAppExpired)
                        stateMachine.updateData(SubscriptionData(lastInAppExpired, expiredPd))
                    }
                }
            } else {
                Logger.d(LOG_IAB, "$TAG: expireStaleInAppFromDb: all INAPP rows still valid")
            }
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: expireStaleInAppFromDb: ${e.message}", e)
            }
        } // stateLock.withLock

        // Fire the state-machine transition OUTSIDE the lock to avoid deadlock
        // (processEventSafely internally acquires stateLock).
        if (shouldTransitionToExpired) {
            processEventSafely(SubscriptionEvent.SubscriptionExpired)
            Logger.i(LOG_IAB, "$TAG: expireStaleInAppFromDb: state → Expired")
        }
    }

    /**
     * Returns all active INAPP (one-time) [SubscriptionStatus] rows from the database.
     *
     * Uses the same active-state set and product-ID filter as [expireStaleInAppFromDb] so the
     * caller always works on exactly the same rows that would otherwise be expired.
     *
     * Returns an empty list on any active in-app subscriptions
     */
    suspend fun getActiveInAppPurchase(): List<SubscriptionStatus> {
        return try {
            val activeStatuses = listOf(
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
                SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
            )
            val rows = subscriptionDb.getSubscriptionsByStates(activeStatuses)

            val inAppRows = rows.filter { sub -> isInAppProduct(sub.productId) }
            // Deduplicate by purchase token (safety guard for multiple INAPP rows)
            inAppRows.distinctBy { it.purchaseToken }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: getActiveInAppPurchase: ${e.message}", e)
            emptyList()
        }
    }

    fun getCurrentState(): SubscriptionState = stateMachine.getCurrentState()
    fun getSubscriptionData(): SubscriptionData? = stateMachine.getCurrentData()
    fun canMakePurchase(): Boolean = stateMachine.getCurrentState().canMakePurchase
    fun hasValidSubscription(): Boolean = stateMachine.getCurrentState().hasValidSubscription
    fun isSubscriptionActive(): Boolean = stateMachine.getCurrentState().isActive
    fun getStatistics(): StateMachineStatistics = stateMachine.getStatistics()

    /**
     * Returns the effective billing expiry for the user's active INAPP (one-time) purchase(s).
     *
     * When two INAPP purchases are active simultaneously (e.g. a user extended access before
     * expiry), this returns the MAXIMUM billingExpiry across all active INAPP rows so the UI
     * always shows the latest "Expires" date and remaining-days calculation is correct.
     *
     * Returns null when no active INAPP rows exist.
     */
    suspend fun getEffectiveInAppExpiryMs(): Long? {
        return try {
            val activeStatuses = listOf(
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id,
                SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
                SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            )
            val rows = subscriptionDb.getSubscriptionsByStates(activeStatuses)
            val inAppRows = rows.filter { sub -> isInAppProduct(sub.productId) }
            val maxExpiry = inAppRows
                .filter { it.billingExpiry > 0L && it.billingExpiry != Long.MAX_VALUE }
                .maxOfOrNull { it.billingExpiry }
            Logger.vv(LOG_IAB, "$TAG: getEffectiveInAppExpiryMs: maxExpiry=$maxExpiry from ${inAppRows.size} active INAPP row(s)")
            maxExpiry
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: getEffectiveInAppExpiryMs: ${e.message}", e)
            // Fall back to state machine data
            stateMachine.getCurrentData()?.subscriptionStatus?.billingExpiry
                ?.takeIf { it > 0L && it != Long.MAX_VALUE }
        }
    }

    /**
     * Returns true when [purchaseToken] is NOT yet present in the local database.
     *
     * Used by [InAppBillingHandler] to decide whether to call [registerDevice]:
     * - true  → brand-new purchase → call registerDevice once
     * - false → already persisted → skip (was registered in a prior session)
     *
     */
    suspend fun isNewPurchase(purchaseToken: String): Boolean {
        return try {
            subscriptionDb.getByPurchaseToken(purchaseToken) == null
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: isNewPurchase: DB read error: ${e.message}", e)
            false
        }
    }

    private fun handleInitialize() {
        Logger.i(LOG_IAB, "$TAG: handleInitialize()")
        // Intentionally empty
        // All DB restoration and RPN activation happens in
        // handleSystemCheckAndDatabaseRestoration(), called by initializeStateMachine()
        // immediately after this event is processed.
    }

    private suspend fun handlePurchaseCompleted(purchaseDetail: PurchaseDetail) {
        try {
            Logger.i(LOG_IAB, "$TAG: handlePurchaseCompleted: ${purchaseDetail.productId}")
            val subscriptionId = dbSyncService.savePurchaseDetail(purchaseDetail)
            Logger.d(LOG_IAB, "$TAG: purchase saved/updated with ID: $subscriptionId")
            if (subscriptionId > 0) {
                val subscriptionStatus = convertPurchaseDetailToSubscriptionStatus(purchaseDetail, subscriptionId)
                val subscriptionData   = SubscriptionData(subscriptionStatus, purchaseDetail)
                stateMachine.updateData(subscriptionData)
                // Only record ACK_PENDING history for a genuinely new pending purchase.
                // Skip if already ACTIVE (reconcile re-fire for an acknowledged purchase).
                // Skip if already ACK_PENDING (cold-start: DB had a stale pending row
                //   handleSystemCheckAndDatabaseRestoration fires PurchaseCompleted to
                //   restore the in-memory state, but the DB is already correct and writing
                //   again would create a spurious duplicate "ACK_PENDING → ACK_PENDING"
                //   history entry on every app restart).
                val existing = subscriptionDb.getByPurchaseToken(purchaseDetail.purchaseToken)
                val alreadyActive  = existing?.status == SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
                val alreadyPending = existing?.status == SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
                if (!alreadyActive && !alreadyPending) {
                    dbSyncService.recordHistoryOnly(
                        subscriptionId = subscriptionStatus.id,
                        fromStatusId   = existing?.status ?: SubscriptionStatus.SubscriptionState.STATE_INITIAL.id,
                        toStatusId     = SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
                        reason         = "Purchase completed, awaiting acknowledgment"
                    )
                }
            } else {
                Logger.e(LOG_IAB, "$TAG: handlePurchaseCompleted: failed to save purchase detail")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handlePurchaseCompleted: ${e.message}", e)
        }
    }

    private suspend fun convertPurchaseDetailToSubscriptionStatus(
        purchaseDetail: PurchaseDetail,
        subscriptionId: Long
    ): SubscriptionStatus {
        val rawExpiry  = purchaseDetail.expiryTime
        val playExpiry = if (rawExpiry > 0L && rawExpiry != Long.MAX_VALUE) rawExpiry else 0L
        return SubscriptionStatus().also { s ->
            s.id = subscriptionId.toInt()
            s.accountId = purchaseDetail.accountId
            // Store the indicator never the raw device ID so the DB never holds a
            // sensitive plain-text credential.  The real value lives in SecureIdentityStore.
            s.deviceId = if (purchaseDetail.deviceId.isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else ""
            s.purchaseToken = purchaseDetail.purchaseToken
            s.orderId = purchaseDetail.orderId
            s.productId = purchaseDetail.productId
            s.planId = purchaseDetail.planId.ifBlank { purchaseDetail.productId }
            s.productTitle = purchaseDetail.productTitle.ifBlank { purchaseDetail.productId }
            s.state = purchaseDetail.state
            s.purchaseTime = purchaseDetail.purchaseTimeMillis
            s.billingExpiry = playExpiry
            s.accountExpiry = playExpiry
            s.sessionToken = RpnProxyManager.getSessionTokenFromPayload(purchaseDetail.payload)
            s.developerPayload = purchaseDetail.payload
            s.status = purchaseDetail.status
            s.lastUpdatedTs = System.currentTimeMillis()
        }
    }

    /**
     * Reconstruct a [PurchaseDetail] from a stored [SubscriptionStatus].
     *
     * Primary use: DB-restoration in [handleSystemCheckAndDatabaseRestoration].
     * Also exposed publicly so callers (e.g. [RpnProxyManager.handleStateChange]) can
     * recover a [PurchaseDetail] when [SubscriptionData.purchaseDetail] is null but
     * [SubscriptionData.subscriptionStatus] is populated (e.g. cold-start race window).
     */
    fun createPurchaseDetailFromSubscription(sub: SubscriptionStatus): PurchaseDetail {
        val productType = if (isInAppProduct(sub.productId))
            BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS

        val isAutoRenewing = productType != BillingClient.ProductType.INAPP &&
                sub.status != SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id

        return PurchaseDetail(
            productId = sub.productId,
            planId = sub.planId,
            productTitle = sub.productTitle,
            state = sub.state,
            planTitle = sub.productTitle,
            purchaseToken = sub.purchaseToken,
            productType = productType,
            purchaseTime = Utilities.convertLongToTime(sub.purchaseTime, Constants.TIME_FORMAT_4),
            purchaseTimeMillis = sub.purchaseTime,
            isAutoRenewing = isAutoRenewing,
            accountId = sub.accountId,
            // sub.deviceId holds only a sentinel indicator, not the real value.
            // Callers that need the actual device ID must resolve it from
            // SecureIdentityStore / BillingBackendClient.getDeviceId().
            deviceId = "",
            payload = sub.developerPayload,
            expiryTime = sub.billingExpiry,
            status = sub.status,
            windowDays = sub.windowDays,
            orderId = sub.orderId
        )
    }

    /**
     * Derive the target [SubscriptionState] purely from Play data.
     *
     * ### Subscription vs one-time (INAPP) expiry semantics
     *
     * **SUBS**: Google Play is the *only* authority. If Play returns a purchase with
     * `purchaseState == PURCHASED` the user has an active subscription; we must
     * never mark it `Expired` based on our locally-computed billing-period estimate
     * because Play may have already silently renewed the subscription without us
     * receiving the callback yet.  Expiry is stored for *display* only.
     *
     * **INAPP**: The access window is fixed at purchase time (e.g. 2 years for
     * `proxy-yearly-2`).  Play does not track per-entitlement expiry for one-time
     * purchases, so our local `expiryTime` IS the authoritative access gate.
     *
     * ### No Unknown state
     * [Purchase.PurchaseState.UNSPECIFIED_STATE] maps to [PurchasePending] so the
     * machine always has a valid next state.  The `else` branch is also [PurchasePending]
     * as a safe default.
     */
    private fun deriveStateFromPlay(detail: PurchaseDetail, purchase: Purchase): SubscriptionState {
        // PENDING: Google Play is still processing the payment.
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            return SubscriptionState.PurchasePending
        }

        // PURCHASED: payment confirmed by Google Play.
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                return SubscriptionState.PurchasePending
            }

            val isInApp = detail.productType == BillingClient.ProductType.INAPP ||
                isInAppProduct(detail.productId)
            if (isInApp) {
                val now = System.currentTimeMillis()
                val hasRealExpiry = detail.expiryTime > 0L && detail.expiryTime != Long.MAX_VALUE
                val isExpired = hasRealExpiry && detail.expiryTime < now
                if (isExpired) return SubscriptionState.Expired
                // One-time purchases do not auto-renew - Canceled would be misleading.
                return SubscriptionState.Active
            }

            // autoRenewing=false means the user canceled but is still in the paid period.
            return if (!purchase.isAutoRenewing) SubscriptionState.Cancelled
                   else SubscriptionState.Active
        }

        // UNSPECIFIED_STATE or any future Play state: treat as Pending so the machine
        // always has a valid next state (never reaches Unknown).
        return SubscriptionState.PurchasePending
    }

    /**
     * Convert a raw Play [Purchase] to a [PurchaseDetail].
     * [playExpiry] is the sole source of expiry from [InAppBillingHandler.calculateExpiryTime].
     */
    private fun Purchase.toPurchaseDetail(
        enrichedProductTitle: String = "",
        enrichedPlanId: String = "",
        playExpiry: Long = Long.MAX_VALUE,
        existingDeviceId: String = ""
    ): PurchaseDetail? {
        val productId = products.firstOrNull() ?: return null
        val payloadStr = developerPayload.ifBlank { originalJson }

        val productType = if (isInAppProduct(productId))
            BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS

        val status = if (isAcknowledged)
            SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
        else
            SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id

        val windowDays = when {
            productId == ONE_TIME_PRODUCT_2YRS -> REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
            productId == ONE_TIME_PRODUCT_5YRS -> REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
            productId == ONE_TIME_PRODUCT_ID   -> REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
            productId == ONE_TIME_TEST_PRODUCT_ID -> REVOKE_WINDOW_SUBS_MONTHLY_DAYS

            enrichedPlanId.contains("yearly", ignoreCase = true) -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
            enrichedPlanId.contains("annual", ignoreCase = true) -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
            enrichedPlanId == InAppBillingHandler.SUBS_PRODUCT_YEARLY -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
            else -> REVOKE_WINDOW_SUBS_MONTHLY_DAYS
        }

        return PurchaseDetail(
            productId = productId,
            planId = enrichedPlanId.ifBlank { productId },
            productTitle = enrichedProductTitle.ifBlank { productId },
            state = purchaseState,
            planTitle = enrichedProductTitle.ifBlank { productId },
            purchaseToken = purchaseToken,
            productType = productType,
            purchaseTime = Utilities.convertLongToTime(purchaseTime, Constants.TIME_FORMAT_4),
            purchaseTimeMillis = purchaseTime,
            isAutoRenewing = isAutoRenewing,
            accountId = accountIdentifiers?.obfuscatedAccountId.orEmpty(),
            deviceId = existingDeviceId,
            payload = payloadStr,
            expiryTime = playExpiry,
            status = status,
            windowDays = windowDays,
            orderId = orderId.orEmpty()
        )
    }

    private suspend fun handlePurchaseFailed(error: String, billingResultCode: Int?) {
        try {
            Logger.e(LOG_IAB, "$TAG: handlePurchaseFailed: $error")
            dbSyncService.savePurchaseFailureHistory(error, billingResultCode)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handlePurchaseFailed error: ${e.message}", e)
        }
    }

    /**
     * The primary writer of [SubscriptionStatus] for purchase-success paths.
     *
     * ### No-duplicate-writes guarantee
     * This is the primary place that calls [subscriptionDb.upsert] for a live purchase.
     * Other lifecycle writers (cancel, expire, revoke, restore) update status-only fields
     * and are serialized via [stateLock] through [processEventSafely].
     * [saveStateTransition] in [StateMachineDatabaseSyncService] records history only and
     * never touches the subscription row independently, preventing double-writes and
     * status overwrite races (e.g. CANCELLED overwritten back to ACTIVE).
     *
     * @param purchaseDetail   The purchase data from Play.
     * @param updateMachineData If true (default), updates the state machine's in-memory data pointer.
     */
    private suspend fun handlePaymentSuccessful(
        purchaseDetail: PurchaseDetail,
        updateMachineData: Boolean = true
    ) {
        try {
            Logger.i(LOG_IAB, "$TAG: handlePaymentSuccessful: ${purchaseDetail.productId}, token=${purchaseDetail.purchaseToken.take(8)}, updateData=$updateMachineData")

            val existingByToken = subscriptionDb.getByPurchaseToken(purchaseDetail.purchaseToken)
            val existingLatest  = if (existingByToken == null) subscriptionDb.getCurrentSubscription() else null
            val existing        = existingByToken ?: existingLatest

            val billingExpiry = purchaseDetail.expiryTime
            val sessionToken = RpnProxyManager.getSessionTokenFromPayload(purchaseDetail.payload)

            val existingBillingExpiry = existing?.billingExpiry ?: 0L
            val newBillingExpiry = if (billingExpiry == Long.MAX_VALUE) 0L else billingExpiry
            // Direct comparison: 0L matches 0L, Long.MAX_VALUE matches Long.MAX_VALUE.
            val expiryAlreadyCurrent = existingBillingExpiry == billingExpiry

            val currentPayloadHasWs = RpnProxyManager.extractWsObject(existing?.developerPayload ?: "") != null
            val newPayloadHasWs = RpnProxyManager.extractWsObject(purchaseDetail.payload) != null
            // same as in reconcile: don't downgrade payload
            val payloadUnchanged = if (currentPayloadHasWs && !newPayloadHasWs) true
                                 else existing?.developerPayload == purchaseDetail.payload

            val targetStatus = if (purchaseDetail.isAutoRenewing || isInAppProduct(purchaseDetail.productId)) {
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            } else {
                SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            }

            val existingIsCurrent = existing != null &&
                existing.purchaseToken == purchaseDetail.purchaseToken &&
                existing.status == targetStatus &&
                expiryAlreadyCurrent &&
                existing.productId == purchaseDetail.productId &&
                existing.planId == purchaseDetail.planId &&
                existing.productTitle == purchaseDetail.productTitle &&
                payloadUnchanged

            if (existingIsCurrent) {
                val statusName = SubscriptionStatus.SubscriptionState.fromId(existing.status).name
                Logger.i(LOG_IAB, "$TAG: handlePaymentSuccessful: no-op, all fields current (status=$statusName)")

                if (updateMachineData) {
                    stateMachine.updateData(SubscriptionData(existing, purchaseDetail))
                }

                // Always ensure RPN is active when the dedup path fires.
                // Previously this only ran for CANCELLED, which left a gap:
                // if the processor (e.g. already-acknowledged INAPP) called
                // paymentSuccessful but handlePaymentSuccessful returned without
                // activating RPN, the VPN would not start.
                scope.launch {
                    try { RpnProxyManager.processRpnPurchase(purchaseDetail, existing) }
                    catch (e: Exception) {
                        Logger.e(LOG_IAB, "$TAG: RPN ensure-active (dedup) failed: ${e.message}", e)
                    }
                }
                return
            }

            // After LOCAL_CANCEL_REVOKE_GUARD_MS, Play is expected to have propagated:
            //  CANCELLED → Play returns isAutoRenewing=false → updateCancelledStatusInDb
            //  REVOKED → Play removes the token → expireOrphanedSubsFromDb / expireStaleSubsFromDb
            //
            // NOTE: the old condition also included `&& !purchaseDetail.isAutoRenewing` to
            // "bypass the guard on genuine resubscription". That condition was wrong:
            //  - After a server-side REVOKE, Play still returns the purchase with
            //    isAutoRenewing=true until the cancellation/revoke propagates (typically
            //    seconds to minutes). Because !true == false the guard was defeated entirely
            //    for revocations, causing handlePaymentSuccessful to overwrite STATE_REVOKED
            //    back to STATE_ACTIVE and re-enable RPN.
            //  - A genuine resubscription ALWAYS gets a NEW purchaseToken from Play. The
            //    `existing.purchaseToken == purchaseDetail.purchaseToken` equality check
            //    above already ensures this guard never fires for a true resubscription
            //    (different token → falls through to normal payment handling).
            // After LOCAL_CANCEL_REVOKE_GUARD_MS, Play is expected to have propagated:
            //  CANCELLED -> Play returns isAutoRenewing=false -> updateCancelledStatusInDb
            //  REVOKED   -> Play removes the token    -> expireOrphanedSubsFromDb / expireStaleSubsFromDb
            //
            // Do NOT gate on !purchaseDetail.isAutoRenewing: after a server-side REVOKE,
            // Play may still return isAutoRenewing=true for minutes until the revocation
            // propagates.  The token-equality check above ensures genuine resubscriptions
            // (which always carry a NEW token) are never blocked by this guard.
            if (existing != null &&
                existing.purchaseToken == purchaseDetail.purchaseToken &&
                (existing.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id ||
                 existing.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id) &&
                (System.currentTimeMillis() - existing.lastUpdatedTs) < LOCAL_CANCEL_REVOKE_GUARD_MS
            ) {
                Logger.i(LOG_IAB, "$TAG: handlePaymentSuccessful: preserving local " +
                    "${SubscriptionStatus.SubscriptionState.fromId(existing.status).name} status " +
                    "for token=${purchaseDetail.purchaseToken.take(8)}, Play propagation pending " +
                    "(guard=${LOCAL_CANCEL_REVOKE_GUARD_MS / 60_000}min)")
                if (updateMachineData) {
                    stateMachine.updateData(SubscriptionData(existing, purchaseDetail))
                }
                return
            }

            val preExistingStatus = existing?.status

            val isPlanChange = existing != null &&
                    existing.purchaseToken.isNotEmpty() &&
                    existing.purchaseToken != purchaseDetail.purchaseToken &&
                    existing.status == SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id

            val prevProductId = existing?.productId ?: ""

            // For INAPP-to-INAPP transitions (e.g. buying a second one-time plan while the first
            // is still active), preserve the old row so both access windows coexist in the DB.
            // The new purchase becomes the primary (latest lastUpdatedTs) and will be returned by
            // getCurrentValidSubscription(). The old INAPP row is expired naturally by
            // expireStaleInAppFromDb() once its billingExpiry passes.
            val isInApp = purchaseDetail.productType == BillingClient.ProductType.INAPP ||
                    isInAppProduct(purchaseDetail.productId)
            val existingIsInApp = existing?.productId?.let { isInAppProduct(it) } ?: false

            // Only expire the old row if BOTH are SUBS. INAPP purchases always coexist
            // with other INAPPs and with SUBS.
            val shouldExpirePrev = isPlanChange && !isInApp && !existingIsInApp

            if (shouldExpirePrev) {
                // SUBS plan change: expire the old row.
                val prev = existing
                Logger.i(LOG_IAB, "$TAG: plan change (SUBS): ${prev?.productId}/${prev?.purchaseToken?.take(8)} → ${purchaseDetail.productId}/${purchaseDetail.purchaseToken.take(8)}")
                prev?.status  = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                prev?.lastUpdatedTs = System.currentTimeMillis()
                prev?.let { subscriptionDb.upsert(it) }
                dbSyncService.recordHistoryOnly(
                    subscriptionId = prev?.id ?: 0,
                    fromStatusId = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                    toStatusId = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                    reason = "Superseded by new SUBS purchase: token=${purchaseDetail.purchaseToken.take(8)}, product=${purchaseDetail.productId}"
                )
            } else if (isPlanChange) {
                // INAPP extension or cross-type purchase: log but keep the old row active.
                Logger.i(LOG_IAB, "$TAG: purchase coexists with old row: " +
                    "${existing?.productId}/${existing?.purchaseToken?.take(8)}, " +
                    "new: ${purchaseDetail.productId}/${purchaseDetail.purchaseToken.take(8)}")
            }

            val rowToSave: SubscriptionStatus = existingByToken?.also { s ->
                syncAllFields(s, purchaseDetail, billingExpiry, sessionToken)
                s.status = targetStatus
            } ?: SubscriptionStatus().also { s ->
                    syncAllFields(s, purchaseDetail, billingExpiry, sessionToken)
                    s.status = targetStatus
                    if (shouldExpirePrev) {
                        s.previousProductId = existing?.productId ?: ""
                        s.previousPurchaseToken = existing?.purchaseToken ?: ""
                        s.replacedAt = System.currentTimeMillis()
                    }
                }

            val upsertId = subscriptionDb.upsert(rowToSave)
            if (upsertId <= 0L) {
                Logger.e(LOG_IAB, "$TAG: handlePaymentSuccessful: DB upsert failed")
                return
            }
            if (rowToSave.id == 0) rowToSave.id = upsertId.toInt()

            val currentState = stateMachine.getCurrentState()
            if (updateMachineData) {
                val subscriptionData = SubscriptionData(rowToSave, purchaseDetail)
                stateMachine.updateData(subscriptionData)
            }

            // Record history for:
            //  - any status change       (prevStatusId != newStatusId)
            //  - plan changes            (isPlanChange)
            //  - renewals: Active→Active with a new billingExpiry the most common
            //    meaningful same-state event. Without this, every subscription renewal
            //    was silently dropped from the history log.
            //
            // Special case: when Play reports autoRenewing=false for a SUBS whose DB status is
            // already CANCELLED, the reconcile path fires PaymentSuccessful (to update expiry +
            // activate RPN) and then immediately calls updateCancelledStatusInDb which writes
            // status back to CANCELLED.  Recording CANCELLED→ACTIVE here would create a noisy
            // paired entry (CANCELLED→ACTIVE then ACTIVE→CANCELLED within the same reconcile).
            // Skip it; the single ACTIVE→CANCELLED entry from updateCancelledStatusInDb is the
            // meaningful one.
            val isReconcileOfExistingCancelled = !purchaseDetail.isAutoRenewing &&
                    !isInApp &&
                    preExistingStatus == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id

            val prevStatusId  = preExistingStatus ?: SubscriptionStatus.SubscriptionState.STATE_INITIAL.id
            val newStatusId   = targetStatus
            val expiryChanged = existingBillingExpiry > 0L &&
                    newBillingExpiry > 0L &&
                    existingBillingExpiry != newBillingExpiry
            if (!isReconcileOfExistingCancelled &&
                (prevStatusId != newStatusId || (shouldExpirePrev) || expiryChanged)) {
                val historyReason = when {
                    isPlanChange && (isInApp || existingIsInApp) -> "Purchase coexists: new token=${purchaseDetail.purchaseToken.take(8)}, product=${purchaseDetail.productId}"
                    shouldExpirePrev -> "Plan changed from $prevProductId to ${purchaseDetail.productId}"
                    expiryChanged -> "Subscription renewed from ${currentState.name}: billingExpiry $existingBillingExpiry → $newBillingExpiry"
                    else          -> "Payment successful from ${currentState.name}"
                }
                dbSyncService.recordHistoryOnly(
                    subscriptionId = rowToSave.id,
                    fromStatusId   = prevStatusId,
                    toStatusId     = newStatusId,
                    reason         = historyReason
                )
            }

            scope.launch {
                try {
                    val res = RpnProxyManager.processRpnPurchase(purchaseDetail, rowToSave)
                    Logger.d(LOG_IAB, "$TAG: RPN activated? $res for ${purchaseDetail.productId}")
                } catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG: RPN activation failed: ${e.message}", e)
                }
            }

            Logger.i(LOG_IAB, "$TAG: handlePaymentSuccessful complete. state→Active, planChange=$isPlanChange, billingExpiry=${rowToSave.billingExpiry}, product=${purchaseDetail.productId}")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handlePaymentSuccessful error: ${e.message}", e)
        }
    }

    /**
     * Sync **every** field of [s] from [d] (Play data), EXCEPT the status column.
     *
     * Expiry invariant: only overwrites billingExpiry/accountExpiry when the incoming
     * value is a real positive epoch-millis (not 0 or MAX_VALUE sentinel).
     */
    private fun syncAllFields(
        s: SubscriptionStatus,
        d: PurchaseDetail,
        billingExpiry: Long,
        sessionToken: String
    ) {
        s.purchaseToken = d.purchaseToken
        s.orderId = d.orderId.ifBlank { s.orderId }
        s.accountId = d.accountId.ifBlank { s.accountId }
        // Store only the sentinel indicator, so the DB never
        // holds a sensitive plain-text credential.  If the incoming PurchaseDetail carries
        // a non-blank deviceId (sentinel set after resolving via getObfuscatedDeviceId()), mark that
        // the ID is present in SecureIdentityStore.  If blank (e.g. reconcile path), leave
        // the existing indicator/empty value untouched.
        if (d.deviceId.isNotBlank()) s.deviceId = SubscriptionStatus.DEVICE_ID_INDICATOR
        s.productId = d.productId
        s.planId = d.planId.ifBlank { d.productId }
        s.productTitle = d.productTitle.ifBlank { s.productTitle.ifBlank { d.productId } }
        s.state = d.state
        s.purchaseTime = if (d.purchaseTimeMillis > 0L) d.purchaseTimeMillis else s.purchaseTime
        if (billingExpiry > 0L && billingExpiry != Long.MAX_VALUE) {
            s.billingExpiry = billingExpiry
            s.accountExpiry = billingExpiry
        }

        val newPayloadHasWs = RpnProxyManager.extractWsObject(d.payload) != null
        val oldPayloadHasWs = RpnProxyManager.extractWsObject(s.developerPayload) != null

        if (newPayloadHasWs || !oldPayloadHasWs) {
            s.developerPayload = d.payload.ifBlank { s.developerPayload }
            s.sessionToken = sessionToken.ifBlank { s.sessionToken }
        }

        s.lastUpdatedTs  = System.currentTimeMillis()
        s.windowDays = d.windowDays
        s.orderId = d.orderId
    }

    private suspend fun handleUserCancelled(data: SubscriptionData?) {
        try {
            Logger.i(LOG_IAB, "$TAG: handleUserCancelled")
            // Prefer state machine data; fall back to DB when data is absent
            // (e.g. cold-start where the machine was not yet populated).
            // Mirrors the pattern used by handleSubscriptionRevoked /
            // handleSubscriptionExpiredWithData.
            val sub = data?.subscriptionStatus
                ?: subscriptionDb.getCurrentSubscription()
                ?: run {
                    Logger.w(LOG_IAB, "$TAG: handleUserCancelled: no subscription data or DB row found")
                    return
                }
            val prevStatus = sub.status

            // If the DB row is already marked CANCELLED, skip the upsert entirely.
            // Cold-start restoration may reach this path; an unnecessary upsert
            // is avoided but the guard window no longer exists to block expiry.
            if (prevStatus == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id) {
                Logger.d(LOG_IAB, "$TAG: handleUserCancelled: already CANCELLED, restoring machine data only, no DB write")
                stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
                return
            }

            sub.status        = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            sub.lastUpdatedTs = System.currentTimeMillis()
            val updateResult = subscriptionDb.upsert(sub)
            if (updateResult > 0) {
                stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
                dbSyncService.recordHistoryOnly(
                    subscriptionId = sub.id,
                    fromStatusId = prevStatus,
                    toStatusId = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                    reason = "User cancelled subscription"
                )
            } else {
                Logger.e(LOG_IAB, "$TAG: handleUserCancelled: DB update failed")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleUserCancelled error: ${e.message}", e)
        }
    }

    private suspend fun handleUserCancelledFromPurchase(data: SubscriptionData?) {
        try {
            Logger.i(LOG_IAB, "$TAG: handleUserCancelledFromPurchase")
            if (data != null) {
                // Use the actual current subscription status as fromState instead of the
                // hardcoded STATE_ACK_PENDING.  This transition can be reached from either:
                //   PurchaseInitiated → UserCancelled  (status could be anything prior)
                //   PurchasePending   → UserCancelled  (status is STATE_ACK_PENDING)
                // Recording the real status avoids a misleading "Pending → Initial" entry
                // when the user cancelled before the Google Play dialog even appeared.
                dbSyncService.recordHistoryOnly(
                    subscriptionId = data.subscriptionStatus.id,
                    fromStatusId   = data.subscriptionStatus.status,
                    toStatusId     = SubscriptionStatus.SubscriptionState.STATE_INITIAL.id,
                    reason         = "User cancelled purchase flow"
                )
            } else {
                dbSyncService.saveUserCancelledPurchaseHistory("User cancelled purchase flow")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleUserCancelledFromPurchase error: ${e.message}", e)
        }
    }

    /**
     * Handles SubscriptionExpired event carrying a [SubscriptionData] payload.
     *
     * This is only called when the event is fired WITH data (i.e. from DB restoration
     * in [handleSystemCheckAndDatabaseRestoration]).  When fired from
     * [reconcileWithPlayBilling] (no data payload), the machine still transitions to
     * [Expired] but the action receives null - the DB was already updated by
     * [handlePaymentSuccessful] or is already in the correct state.
     *
     * Either way we use the current DB row to update status and deactivate RPN.
     */
    private suspend fun handleSubscriptionExpiredWithData(data: SubscriptionData?) {
        try {
            Logger.w(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData")
            // Prefer the data passed in; fall back to whatever is in DB for this token.
            val sub = data?.subscriptionStatus
                ?: subscriptionDb.getCurrentSubscription()
                ?: run {
                    Logger.w(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData: no subscription found in DB")
                    return
                }

            val prevStatus = sub.status
            // Only write if not already marked expired - avoid spurious duplicate history.
            if (sub.status != SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id) {
                sub.status        = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                sub.lastUpdatedTs = System.currentTimeMillis()
                val updateResult = subscriptionDb.upsert(sub)
                if (updateResult > 0) {
                    stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId   = prevStatus,
                        toStatusId     = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                        reason         = "Subscription expired (billingExpiry=${sub.billingExpiry})"
                    )
                } else {
                    Logger.e(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData: DB update failed")
                }
            } else {
                Logger.d(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData: already EXPIRED in DB, no-op")
            }

            scope.launch {
                try { RpnProxyManager.deactivateRpn("expired") }
                catch (e: Exception) { Logger.e(LOG_IAB, "$TAG: deactivate RPN error: ${e.message}", e) }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData error: ${e.message}", e)
        }
    }

    private suspend fun handleSubscriptionRestored(purchaseDetail: PurchaseDetail) {
        try {
            Logger.i(LOG_IAB, "$TAG: handleSubscriptionRestored: ${purchaseDetail.productId}")
            val existing = subscriptionDb.getByPurchaseToken(purchaseDetail.purchaseToken)
                ?: subscriptionDb.getCurrentSubscription()

            if (existing != null) {
                val prevStatus = existing.status

                // Dedup: if already ACTIVE or CANCELLED with same token+product+expiry+payload → no write, no history.
                //
                // ACTIVE: common cold-start path; DB is already correct, just restore memory.
                //
                // CANCELLED: cold-start restore for a subscription that was cancelled but whose
                //   billingExpiry has not yet passed.  The in-memory state machine becomes Active
                //   so the user retains access, but the DB status MUST stay CANCELLED, do NOT
                //   overwrite it to ACTIVE here.  If we did, every restart would generate a
                //   spurious "Cancelled → Active" history entry that never corresponds to a real
                //   payment event.  Play reconcile will write the authoritative status seconds
                //   after startup via updateCancelledStatusInDb / handlePaymentSuccessful.
                val billingExpiry = purchaseDetail.expiryTime
                val sessionToken  = RpnProxyManager.getSessionTokenFromPayload(purchaseDetail.payload)

                val newBillingExpiry = if (billingExpiry == Long.MAX_VALUE) 0L else billingExpiry
                // Direct comparison so both 0L and Long.MAX_VALUE are treated as "unchanged".
                // Previously we compared against newBillingExpiry (converted Long.MAX_VALUE→0L)
                // and required > 0L, which caused the dedup to always fail for rows whose
                // billingExpiry was 0 or Long.MAX_VALUE, writing lastUpdatedTs on every cold
                // start. That timestamp update defeated expireStaleSubsFromDb's guard window,
                // blocking legitimate expiry.
                val expiryUnchanged = existing.billingExpiry == billingExpiry

                val currentPayloadHasWs = RpnProxyManager.extractWsObject(existing.developerPayload) != null
                val newPayloadHasWs = RpnProxyManager.extractWsObject(purchaseDetail.payload) != null
                // same logic: don't downgrade
                val payloadUnchanged = if (currentPayloadHasWs && !newPayloadHasWs) true
                                     else existing.developerPayload == purchaseDetail.payload

                if (expiryUnchanged && payloadUnchanged &&
                    (prevStatus == SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id ||
                     prevStatus == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id) &&
                    existing.purchaseToken == purchaseDetail.purchaseToken &&
                    existing.productId     == purchaseDetail.productId
                ) {
                    Logger.d(LOG_IAB, "$TAG: handleSubscriptionRestored: already ${SubscriptionStatus.SubscriptionState.fromId(prevStatus).name} with current data, memory-only restore")
                    stateMachine.updateData(SubscriptionData(existing, purchaseDetail))
                    return
                }

                syncAllFields(existing, purchaseDetail, billingExpiry, sessionToken)
                val updateResult = subscriptionDb.upsert(existing)
                if (updateResult > 0) {
                    stateMachine.updateData(SubscriptionData(existing, purchaseDetail))
                    if (prevStatus != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) {
                        dbSyncService.recordHistoryOnly(
                            subscriptionId = existing.id,
                            fromStatusId = prevStatus,
                            toStatusId = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                            reason = "Subscription restored"
                        )
                    }
                    scope.launch {
                        try { RpnProxyManager.activateRpn(purchaseDetail) }
                        catch (e: Exception) { Logger.e(LOG_IAB, "$TAG: RPN activate error: ${e.message}", e) }
                    }
                } else {
                    Logger.e(LOG_IAB, "$TAG: handleSubscriptionRestored: DB update failed")
                }
            } else {
                // No existing row; treat as a fresh payment.
                handlePaymentSuccessful(purchaseDetail)
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSubscriptionRestored error: ${e.message}", e)
        }
    }

    private suspend fun handleSubscriptionRevoked(data: SubscriptionData?) {
        try {
            Logger.w(LOG_IAB, "$TAG: handleSubscriptionRevoked")
            val sub = data?.subscriptionStatus
                ?: subscriptionDb.getCurrentSubscription()
                ?: run {
                    Logger.w(LOG_IAB, "$TAG: handleSubscriptionRevoked: no subscription found")
                    return
                }

            val prevStatus = sub.status
            if (sub.status != SubscriptionStatus.SubscriptionState.STATE_REVOKED.id) {
                val now = System.currentTimeMillis()
                sub.status        = SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
                sub.lastUpdatedTs = now
                // Revocation = immediate loss of access.  Clamp billingExpiry/accountExpiry
                // to now so that:
                //   1. isExpired() returns true immediately after revoke.
                //   2. The UI never shows a misleading future "Expires: <date>" for a
                //      purchase that is already revoked and inaccessible.
                //   3. A zero / Long.MAX_VALUE billingExpiry won't resurrect the row
                //      to Active on the next cold start.
                if (sub.billingExpiry > now || sub.billingExpiry <= 0L ||
                    sub.billingExpiry == Long.MAX_VALUE) {
                    sub.billingExpiry = now
                    sub.accountExpiry = now
                }
                val updateResult = subscriptionDb.upsert(sub)
                if (updateResult > 0) {
                    stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId = prevStatus,
                        toStatusId = SubscriptionStatus.SubscriptionState.STATE_REVOKED.id,
                        reason = "Subscription revoked by system"
                    )
                } else {
                    Logger.e(LOG_IAB, "$TAG: handleSubscriptionRevoked: DB update failed")
                }
            } else {
                Logger.d(LOG_IAB, "$TAG: handleSubscriptionRevoked: already REVOKED, no-op")
            }

            scope.launch {
                try { RpnProxyManager.deactivateRpn("revoked") }
                catch (e: Exception) { Logger.e(LOG_IAB, "$TAG: deactivate RPN error: ${e.message}", e) }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSubscriptionRevoked error: ${e.message}", e)
        }
    }

    /**
     * On app start: load the DB row, compute the effective state from stored fields
     * (billingExpiry, status) and transition the in-memory machine to match.
     *
     * ### Key design rule
     * This is a **memory-only restoration** - it must NOT write to the DB or insert
     * history entries.  Doing so creates spurious "Unknown→Active" entries every cold
     * start, which is exactly what we want to avoid.
     *
     * The only writer of history is [handlePaymentSuccessful] (on real Play events).
     * [reconcileWithPlayBilling] will overwrite any discrepancy within seconds.
     *
     */
    private suspend fun handleSystemCheckAndDatabaseRestoration() {
        stateLock.withLock {
        try {
            Logger.i(LOG_IAB, "$TAG: handleSystemCheckAndDatabaseRestoration")
            val dbStateInfo = dbSyncService.loadStateFromDatabase() ?: run {
                Logger.d(LOG_IAB, "$TAG: no DB state to restore")
                return
            }

            val sub = dbStateInfo.currentSubscription
            Logger.i(LOG_IAB, "$TAG: DB state: ${dbStateInfo.recommendedState.name}, billingExpiry=${sub.billingExpiry}")

            val now = System.currentTimeMillis()

            // The DB row's status comes from a previous session's reconcile or expiry.
            // If the row is already Expired, respect that — do NOT resurrect to Active
            // because billingExpiry happens to be 0 (unset / default). Play reconcile
            // will correct any discrepancy within seconds. Treating Expired+0 as Active
            // on every cold start blocks the machine from ever converging to Expired
            // when the subscription ended while billingExpiry was 0.
            val effectiveState: SubscriptionState = dbStateInfo.recommendedState

            // Restore in-memory data first (no DB write)
            stateMachine.updateData(SubscriptionData(sub))

            // Restore in-memory state using light-weight events that do NOT trigger
            // handlePaymentSuccessful / DB writes / history inserts.
            // SubscriptionRestored is used for Active/Canceled/Grace so it calls
            // handleSubscriptionRestored (which only writes if status actually changed).
            // For Expired/Revoked/Pending we set the machine state directly without
            // firing events that have DB-write side effects; Play reconcile will
            // authoritatively correct any discrepancy within seconds of app start.
            val pd = createPurchaseDetailFromSubscription(sub)
            when (effectiveState) {
                SubscriptionState.Active,
                SubscriptionState.Grace -> {
                    // Restore in memory; fire SubscriptionRestored which is idempotent
                    // (skips DB write if prevStatus already ACTIVE).
                    stateMachine.processEvent(SubscriptionEvent.SubscriptionRestored(pd))
                    // Preserve the purchaseDetail so callers (e.g. cancelPurchase /
                    // revokeSubscription) can read it without hitting the DB again.
                    stateMachine.updateData(SubscriptionData(sub, pd))
                }
                SubscriptionState.Cancelled -> {
                    // Still within billing period (or expiry unknown) → Active in memory.
                    // DB status stays CANCELLED - do NOT write.
                    val billingExpiryKnown = sub.billingExpiry > 0L && sub.billingExpiry != Long.MAX_VALUE
                    if (sub.billingExpiry > now || !billingExpiryKnown) {
                        stateMachine.processEvent(SubscriptionEvent.SubscriptionRestored(pd))
                        Logger.i(LOG_IAB, "$TAG: restore Cancelled → Active in memory (still valid)")
                        // Preserve the purchaseDetail alongside the subscription status.
                        stateMachine.updateData(SubscriptionData(sub, pd))
                    } else {
                        // Billing period has definitively ended. Transition to Expired,
                        // not UserCancelled — Cancelled. hasValidSubscription is true,
                        // which keeps the UI in the wrong state. handleSubscriptionExpiredWithData
                        // will write EXPIRED to the DB and update the machine data.
                        stateMachine.processEvent(SubscriptionEvent.SubscriptionExpired)
                        Logger.i(LOG_IAB, "$TAG: restore Cancelled → Expired (billing period ended: billingExpiry=${sub.billingExpiry} < now=$now)")
                    }
                }
                SubscriptionState.Expired ->
                    stateMachine.processEvent(SubscriptionEvent.SubscriptionExpired)
                SubscriptionState.Revoked ->
                    stateMachine.processEvent(SubscriptionEvent.SubscriptionRevoked)
                SubscriptionState.PurchasePending -> {
                    stateMachine.processEvent(SubscriptionEvent.PurchaseCompleted(pd))
                }
                SubscriptionState.PurchaseInitiated ->
                    stateMachine.processEvent(SubscriptionEvent.Initialize)
                SubscriptionState.Initial ->
                    Logger.i(LOG_IAB, "$TAG: DB state Initial, no transition needed")
                else ->
                    Logger.w(LOG_IAB, "$TAG: unhandled DB state: ${effectiveState.name}")
            }
            Logger.i(LOG_IAB, "$TAG: DB restoration complete, effective=${effectiveState.name}, machine=${stateMachine.getCurrentState().name}")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSystemCheckAndDatabaseRestoration error: ${e.message}", e)
        }
        } // stateLock.withLock
    }

    private suspend fun handleSystemCheck() {
        // Run the DB-level system check.  This:
        //  1. Expires INAPP rows whose local billingExpiry has passed (SQL bulk update).
        //  2. Records history for every row that was just expired.
        //  3. Returns a SystemCheckResult so we know whether any rows were expired.
        //
        // SUBS expiry is NOT performed here - Google Play is the sole authority for SUBS.
        // reconcileWithPlayBilling() handles SUBS expiry and is called every time Play
        // returns a fresh snapshot. handleSystemCheck() is triggered by SystemCheck events
        // (e.g. onBillingServiceDisconnected) and therefore runs without a Play connection,
        // so we must not touch SUBS rows here.
        Logger.i(LOG_IAB, "$TAG: handleSystemCheck, running INAPP local-expiry check")
        try {
            val result = dbSyncService.performSystemCheck()
            if (result == null) {
                Logger.w(LOG_IAB, "$TAG: handleSystemCheck, performSystemCheck returned null, skipping state transition")
                return
            }
            Logger.i(LOG_IAB, "$TAG: handleSystemCheck, expiredCount=${result.expiredSubscriptionsUpdated}, currentValid=${result.currentSubscriptionValid}")
            if (result.expiredSubscriptionsUpdated > 0 && !result.currentSubscriptionValid) {
                // At least one INAPP row was expired AND the current subscription is
                // no longer valid → transition the in-memory state to Expired.
                // We call processEventSafely here because handleSystemCheck is always
                // invoked from within a state-machine action, not under stateLock directly,
                // so re-entrance is safe.
                processEventSafely(SubscriptionEvent.SubscriptionExpired)
                Logger.i(LOG_IAB, "$TAG: handleSystemCheck, state machine transitioned to Expired (INAPP local expiry)")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSystemCheck error: ${e.message}", e)
        }
    }

    private suspend fun handleErrorRecovery() {
        try {
            Logger.i(LOG_IAB, "$TAG: handleErrorRecovery")
            val dbStateInfo = dbSyncService.loadStateFromDatabase() ?: return
            val pd = createPurchaseDetailFromSubscription(dbStateInfo.currentSubscription)
            stateMachine.updateData(SubscriptionData(dbStateInfo.currentSubscription, pd))
            Logger.i(LOG_IAB, "$TAG: handleErrorRecovery: state restored from DB")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleErrorRecovery error: ${e.message}", e)
        }
    }

    private fun handleBillingError(error: String, billingResultCode: Int) {
        // Billing errors are transient infrastructure failures (e.g. network, Play API).
        // We do NOT write a history entry here because:
        //  1. The SubscriptionStatus row is NOT updated when a billing error occurs.
        //  2. Writing toState=STATE_PURCHASE_FAILED without updating the row's `status`
        //     field creates a mismatch: history says "went to PURCHASE_FAILED" but the
        //     current DB row still shows the previous state (e.g. ACTIVE).
        //  3. Billing errors are recoverable; the machine goes to Error state and returns
        //     to Initial on ErrorRecovered, no lasting subscription change occurred.
        //
        // Real purchase failures (user attempted payment and it was declined) are recorded
        // separately in handlePurchaseFailed → savePurchaseFailureHistory, which *does*
        // have a matching SubscriptionStatus update.
        Logger.e(LOG_IAB, "$TAG: handleBillingError: $error (code=$billingResultCode)")
    }

    private fun handleDatabaseError(error: String) {
        Logger.e(LOG_IAB, "$TAG: handleDatabaseError: $error")
    }

    private fun createDummyPurchaseDetail(): PurchaseDetail = PurchaseDetail(
        productId = "", planId = "", productTitle = "",
        state = 1 /* Purchase.PurchaseState.PURCHASED */, planTitle = "",
        purchaseToken = "", productType = "subs" /* BillingClient.ProductType.SUBS */,
        purchaseTime = "", purchaseTimeMillis = 0L, isAutoRenewing = false,
        accountId = "", payload = "", expiryTime = 0L,
        status = 1 /* Purchase.PurchaseState.PURCHASED */,
        windowDays = REVOKE_WINDOW_SUBS_MONTHLY_DAYS,
        orderId = ""
    )
}
