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
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2.Companion.LOCAL_CANCEL_REVOKE_GUARD_MS
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Subscription State Machine V2.
 *
 * 1. **Google Play is the single source of truth.** Every field in [SubscriptionStatus]
 *    must reflect what Play returned. The local DB is only used to restore the in-memory
 *    state between app launches; it is always reconciled against Play on connect.
 * 2. **Expiry comes exclusively from Play** via [InAppBillingHandler.calculateExpiryTime].
 */
open class SubscriptionStateMachineV2 : KoinComponent {

    private val subscriptionDb by inject<SubscriptionStatusRepository>()
    private val dbSyncService: StateMachineDatabaseSyncService by inject()
    private val persistentState by inject<PersistentState>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Lock ordering (must always be acquired in this order to prevent deadlocks)
    // 1. (removed) InAppBillingHandler.connectionMutex - replaced by a non-locking
    //    `connecting` AtomicBoolean dedup flag; cancel/revoke no longer take a lock.
    // 2. stateLock(protects state machine transitions)
    // 3. SubscriptionStatusRepository.mutex(innermost protects DB writes)
    // Never acquire a higher-numbered lock while holding a lower-numbered one.
    // fixme: see if these locks can be removed/made into single transaction
    private val stateLock = Mutex()

    /**
     * Coroutine-context marker installed while [stateLock] is held by [processEventSafely].
     *
     * Reentrancy guard: [stateLock] is a NON-reentrant Mutex, and [StateMachine.processEvent]
     * runs transition actions INLINE (synchronously) inside `stateLock.withLock { ... }`. So if
     * an action itself calls [processEventSafely] on the SAME coroutine, the nested
     * `stateLock.withLock` would self-deadlock (the lock is already held by this coroutine and
     * can never be released because the inner call is waiting for it).
     *
     * The two current call sites that previously needed a transition from within an action
     * avoid this by deferring via `scope.launch { processEventSafely(...) }` ([handleSystemCheck]
     * at ~line 2405) or by recording a flag and firing the event AFTER the `withLock` block
     * ([expireStaleInAppFromDb] at ~line 1395). Those deferred launches run on a SEPARATE
     * coroutine context that does NOT carry this marker, so they pass the guard and simply
     * block on the mutex until the outer transition releases it (correct, no deadlock).
     *
     * This marker exists so that a FUTURE mistake (a synchronous processEventSafely call from
     * an action) is detected and logged instead of hanging the coroutine forever.
     */
    private object StateLockHeldKey : CoroutineContext.Key<StateLockMarker>
    private class StateLockMarker : AbstractCoroutineContextElement(StateLockHeldKey)

    /**
     * Runs [block] with [stateLock] held.
     * If already inside a transition, runs inline to avoid re-locking the mutex.
     */
    private suspend fun <T> withStateLock(block: suspend () -> T): T {
        if (currentCoroutineContext()[StateLockHeldKey] != null) {
            // Lock is already held; run inline.
            return withContext(StateLockMarker()) { block() }
        }
        return stateLock.withLock {
            withContext(StateLockMarker()) { block() }
        }
    }

    var stateMachine: StateMachine<SubscriptionState, SubscriptionEvent, SubscriptionData>

    /**
     * Timestamp when the current purchase flow entered PurchaseInitiated.
     * Used to detect and recover stuck purchase flows.
     */
    @Volatile private var purchaseInitiatedAtMs: Long = 0L

    companion object {
        private const val TAG = "SubscriptionStateMachineV2"

        /** Maximum time allowed in PurchaseInitiated before recovery. */
        internal const val PURCHASE_INITIATED_STUCK_TIMEOUT_MS = 15 * 60 * 1000L

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

        /** Grace period before an empty Play snapshot can expire a recently updated row. */
        private const val EMPTY_SNAPSHOT_FRESH_ROW_GUARD_MS = 24 * 60 * 60 * 1000L // 24 hours

        /**
         * Returns `true` when the state machine's [SubscriptionData] indicates the
         * subscription was recently cancelled or revoked locally (within
         * [LOCAL_CANCEL_REVOKE_GUARD_MS]) and the Play-propagated [PaymentSuccessful]
         * event carries the same purchase token.
         *
         * Used by transition guards to prevent a stale Play reconcile from overwriting
         * a locally-set CANCELLED / REVOKED status back to ACTIVE. A genuine
         * resubscription always has a different token and is never blocked.
         */
        fun isWithinLocalCancelRevokeGuard(
            data: SubscriptionData?,
            event: SubscriptionEvent
        ): Boolean {
            if (data == null) return false
            val sub = data.subscriptionStatus
            if (sub.status != SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id &&
                sub.status != SubscriptionStatus.SubscriptionState.STATE_REVOKED.id) {
                return false
            }
            if (event is SubscriptionEvent.PaymentSuccessful) {
                if (event.purchaseDetail.purchaseToken != sub.purchaseToken) {
                    return false
                }
            }

            return (System.currentTimeMillis() - sub.lastUpdatedTs) < LOCAL_CANCEL_REVOKE_GUARD_MS
        }

        fun isSameRevokedToken(data: SubscriptionData?, purchaseToken: String): Boolean {
            val sub = data?.subscriptionStatus ?: return false
            return sub.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id &&
                sub.purchaseToken == purchaseToken
        }
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
                    event = SubscriptionEvent.UserCancelled(),
                    toState = SubscriptionState.Cancelled,
                    guard = { event, data ->
                        event is SubscriptionEvent.UserCancelled &&
                            isCancelableSubscription(data as? SubscriptionData, event.purchaseToken)
                    },
                    action = { event, data -> handleUserCancelled(data as? SubscriptionData, (event as SubscriptionEvent.UserCancelled).purchaseToken, (event as SubscriptionEvent.UserCancelled).caller) }
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
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.PurchaseInitiated,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
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
                    event = SubscriptionEvent.UserCancelled(),
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
                    event = SubscriptionEvent.ServerAckFailed(""),
                    toState = SubscriptionState.ServerAckPending,
                    guard = { event, _ -> event is SubscriptionEvent.ServerAckFailed },
                    action = { event, _ ->
                        val e = event as SubscriptionEvent.ServerAckFailed
                        handleServerAckFailed(e.error)
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
                        toState = SubscriptionState.PurchaseInitiated,
                        action = { _, _ ->
                            handleSystemCheck()
                            // Defer recovery until the transition completes to avoid the state
                            // machine overwriting the recovered state.
                            scope.launch { recoverStuckPurchaseIfAny() }
                        }
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
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.UserCancelled(),
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
                    event = SubscriptionEvent.ServerAckFailed(""),
                    toState = SubscriptionState.ServerAckPending,
                    guard = { event, _ -> event is SubscriptionEvent.ServerAckFailed },
                    action = { event, _ ->
                        val e = event as SubscriptionEvent.ServerAckFailed
                        handleServerAckFailed(e.error)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.PurchasePending,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.PurchasePending,
                    action = { _, _ -> handleSystemCheck() }
                )

                // ServerAckPending: payment was received by Play but server ack failed.
                // Recovery: if Play eventually propagates the ack or a background retry
                // succeeds, PaymentSuccessful transitions to Active.
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.PurchaseCompleted(createDummyPurchaseDetail()),
                    toState = SubscriptionState.ServerAckPending,
                    guard = { event, _ -> event is SubscriptionEvent.PurchaseCompleted },
                    action = { event, _ ->
                        handlePurchaseCompleted((event as SubscriptionEvent.PurchaseCompleted).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.ServerAckFailed(""),
                    toState = SubscriptionState.ServerAckPending,
                    guard = { event, _ -> event is SubscriptionEvent.ServerAckFailed },
                    action = { event, _ ->
                        handleServerAckFailed((event as SubscriptionEvent.ServerAckFailed).error)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.PaymentSuccessful },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, _ -> event is SubscriptionEvent.SubscriptionRestored },
                    action = { event, _ ->
                        handleSubscriptionRestored((event as SubscriptionEvent.SubscriptionRestored).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.ServerAckPending,
                    action = { _, _ -> handleSystemCheck() }
                )
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.UserCancelled(),
                    toState = SubscriptionState.Cancelled,
                    guard = { event, data ->
                        event is SubscriptionEvent.UserCancelled &&
                            isCancelableSubscription(data as? SubscriptionData, event.purchaseToken)
                    },
                    action = { event, data -> handleUserCancelled(data as? SubscriptionData, (event as SubscriptionEvent.UserCancelled).purchaseToken, (event as SubscriptionEvent.UserCancelled).caller) }
                )
                addTransition(
                    fromState = SubscriptionState.ServerAckPending,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
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
                    event = SubscriptionEvent.UserCancelled(),
                    toState = SubscriptionState.Cancelled,
                    action = { event, data -> handleUserCancelled(data as? SubscriptionData, (event as SubscriptionEvent.UserCancelled).purchaseToken, (event as SubscriptionEvent.UserCancelled).caller) }
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
                        event = SubscriptionEvent.UserCancelled(),
                        toState = SubscriptionState.Cancelled,
                        action = { event, data -> handleUserCancelled(data as? SubscriptionData, (event as SubscriptionEvent.UserCancelled).purchaseToken, (event as SubscriptionEvent.UserCancelled).caller) }
                    )
                    addTransition(
                        fromState = fromState,
                        event = SubscriptionEvent.SystemCheck,
                        toState = fromState,
                        action = { _, _ -> handleSystemCheck() }
                    )
                    if (fromState != SubscriptionState.Grace) {
                        addTransition(
                            fromState = fromState,
                            event = SubscriptionEvent.PurchaseInitiated,
                            toState = fromState
                        )
                    }
                }

                // Canceled + not yet expired - user still has access.
                // Play fires PaymentSuccessful when user re-subscribes.
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, data ->
                        event is SubscriptionEvent.PaymentSuccessful &&
                        !isWithinLocalCancelRevokeGuard(data as? SubscriptionData, event)
                    },
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
                    event = SubscriptionEvent.UserCancelled(),
                    toState = SubscriptionState.Cancelled,
                    action = { _, _ ->
                        Logger.d(LOG_IAB, "$TAG: already Cancelled, re-fire ignored")
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Cancelled,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Cancelled,
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
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.Expired,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Expired,
                    action = { _, _ -> handleSystemCheck() }
                )

                // Revoked
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.PaymentSuccessful(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, data ->
                        event is SubscriptionEvent.PaymentSuccessful &&
                        !isSameRevokedToken(data as? SubscriptionData, event.purchaseDetail.purchaseToken) &&
                        !isWithinLocalCancelRevokeGuard(data as? SubscriptionData, event)
                    },
                    action = { event, _ ->
                        handlePaymentSuccessful((event as SubscriptionEvent.PaymentSuccessful).purchaseDetail)
                    }
                )
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.SubscriptionRestored(createDummyPurchaseDetail()),
                    toState = SubscriptionState.Active,
                    guard = { event, data ->
                        event is SubscriptionEvent.SubscriptionRestored &&
                            !isSameRevokedToken(data as? SubscriptionData, event.purchaseDetail.purchaseToken)
                    },
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
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.PurchaseInitiated,
                    toState = SubscriptionState.PurchaseInitiated
                )
                addTransition(
                    fromState = SubscriptionState.Revoked,
                    event = SubscriptionEvent.SystemCheck,
                    toState = SubscriptionState.Revoked,
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
                    event = SubscriptionEvent.SubscriptionExpired,
                    toState = SubscriptionState.Expired,
                    action = { _, data -> handleSubscriptionExpiredWithData(data as? SubscriptionData) }
                )
                addTransition(
                    fromState = SubscriptionState.Error,
                    event = SubscriptionEvent.SubscriptionRevoked,
                    toState = SubscriptionState.Revoked,
                    action = { _, data -> handleSubscriptionRevoked(data as? SubscriptionData) }
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
        object ServerAckPending: SubscriptionState()
        object Grace: SubscriptionState()
        object OnHold: SubscriptionState()
        object Paused: SubscriptionState()

        val isActive: Boolean
            get() = this is Active || this is Grace || this is OnHold || this is Paused

        val canMakePurchase: Boolean
            get() = this is Initial || this is Expired || this is Revoked ||
                    this is Cancelled || this is Error ||
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
        /** User cancelled a purchase, optionally identifying the purchase and caller. */
        data class UserCancelled(val purchaseToken: String? = null, val caller: String? = null) : SubscriptionEvent()
        object SubscriptionExpired : SubscriptionEvent()
        object SubscriptionRevoked : SubscriptionEvent()
        data class SubscriptionRestored(val purchaseDetail: PurchaseDetail) : SubscriptionEvent()
        object SystemCheck : SubscriptionEvent()
        data class BillingError(val error: String, val billingResultCode: Int = -1) : SubscriptionEvent()
        data class DatabaseError(val error: String) : SubscriptionEvent()
        data class ServerAckFailed(val error: String) : SubscriptionEvent()
        object ErrorRecovered : SubscriptionEvent()
    }

    data class SubscriptionData(
        val subscriptionStatus: SubscriptionStatus,
        val purchaseDetail: PurchaseDetail? = null,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    open val currentState: StateFlow<SubscriptionState> get() = stateMachine.currentState

    private suspend fun processEventSafely(
        event: SubscriptionEvent,
        guard: (() -> Boolean)? = null
    ): Boolean {
        // Reentrancy guard: see [StateLockMarker]. If this coroutine is already executing
        // inside a stateLock.withLock block (i.e. we were called synchronously from a
        // transition action), re-entering stateLock would self-deadlock. Drop the event with
        // a loud log rather than hanging. The event is recoverable: SystemCheck and
        // reconcile fire regularly and will re-derive correct state.
        if (currentCoroutineContext()[StateLockHeldKey] != null) {
            Logger.e(LOG_IAB, "$TAG: processEventSafely RE-ENTERED while stateLock is already held " +
                "by this coroutine (event=${event.name}). Dropping event to avoid stateLock " +
                "self-deadlock. Fix the caller to defer via scope.launch{...} (see " +
                "handleSystemCheck) or fire the event outside the withLock block.")
            return false
        }
        return stateLock.withLock {
            if (guard?.invoke() == false) return@withLock false

            // Install the marker for the duration of the transition so any synchronous
            // re-entry is detectable above. withContext on a bare element (no interceptor)
            // does not redispatch; it just augments coroutineContext for the block.
            withContext(StateLockMarker()) {
                stateMachine.processEvent(event)
            }
            true
        }
    }

    /**
     * Processes a token-scoped update against the current or matching DB row.
     * Returns true when the token belongs to the current purchase.
     */
    private suspend fun processForToken(
        purchaseToken: String,
        processCurrent: suspend () -> Unit,
        processNonCurrent: suspend () -> Unit,
        processAbsent: suspend () -> Unit = processCurrent
    ): Boolean {
        return withStateLock {
            val machineData = stateMachine.getCurrentData()
            val currentStatus = machineData?.subscriptionStatus
                ?: subscriptionDb.getCurrentSubscription()
            val targetStatus = if (currentStatus == null) {
                subscriptionDb.getByPurchaseToken(purchaseToken)
            } else {
                null
            }
            val statusToProcess = currentStatus ?: targetStatus

            when {
                // No row anywhere: the machine's (default) state is the only state there
                // is, so the event is processed on the machine itself (no hydration) and
                // the caller treats it as current.
                statusToProcess == null -> {
                    processAbsent()
                    true
                }
                statusToProcess.purchaseToken != purchaseToken -> {
                    processNonCurrent()
                    false
                }
                else -> {
                    if (machineData == null) {
                        stateMachine.updateData(
                            SubscriptionData(
                                statusToProcess,
                                createPurchaseDetailFromSubscription(statusToProcess)
                            )
                        )
                    }
                    processCurrent()
                    true
                }
            }
        }
    }

    /**
     * Processes a terminal event for [purchaseToken].
     * Returns true when it affects the current purchase.
     */
    private suspend fun processTerminalEventForToken(
        purchaseToken: String,
        event: SubscriptionEvent,
        terminalState: SubscriptionState
    ): Boolean {
        return processForToken(
            purchaseToken,
            processCurrent = { stateMachine.processEvent(event) },
            processNonCurrent = { updateTerminalPurchaseRow(purchaseToken, terminalState) }
        )
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

    suspend fun startPurchase(): Boolean {
        return processEventSafely(SubscriptionEvent.PurchaseInitiated) {
            val current = stateMachine.getCurrentState()
            if (!current.canMakePurchase) {
                Logger.i(LOG_IAB, "$TAG: startPurchase ignored in state ${current.name}")
                false
            } else {
                // Record the purchase start time for stuck-purchase recovery.
                purchaseInitiatedAtMs = System.currentTimeMillis()
                true
            }
        }
    }

    suspend fun completePurchase(purchaseDetail: PurchaseDetail) {
        processEventSafely(SubscriptionEvent.PurchaseCompleted(purchaseDetail))
    }

    suspend fun paymentSuccessful(purchaseDetail: PurchaseDetail) {
        processEventSafely(SubscriptionEvent.PaymentSuccessful(purchaseDetail))
    }

    open suspend fun purchaseFailed(error: String, billingResultCode: Int? = null) {
        processEventSafely(SubscriptionEvent.PurchaseFailed(error, billingResultCode))
    }

    suspend fun serverAckFailed(error: String) {
        processEventSafely(SubscriptionEvent.ServerAckFailed(error))
    }

    suspend fun userCancelled(purchaseToken: String? = null, caller: String? = null) {
        if (purchaseToken.isNullOrBlank()) {
            processEventSafely(SubscriptionEvent.UserCancelled(purchaseToken, caller))
        } else {
            processCancellationEventForToken(purchaseToken, caller)
        }
    }
    private suspend fun processCancellationEventForToken(
        purchaseToken: String,
        caller: String?
    ) {
        processForToken(
            purchaseToken,
            processCurrent = {
                stateMachine.processEvent(SubscriptionEvent.UserCancelled(purchaseToken, caller))
            },
            processNonCurrent = {
                handleUserCancelled(
                    data = null,
                    purchaseToken = purchaseToken,
                    caller = caller,
                    updateMachineData = false
                )
            },
            // Absent row: no machine data to hydrate, record the cancellation row-only
            // (preserves the pre-merge behavior for this branch).
            processAbsent = {
                handleUserCancelled(
                    data = null,
                    purchaseToken = purchaseToken,
                    caller = caller,
                    updateMachineData = false
                )
            }
        )
    }

    /** Records an abandoned Play purchase flow without changing the subscription. */
    suspend fun purchaseFlowCancelled() {
        try {
            Logger.i(LOG_IAB, "$TAG: purchaseFlowCancelled: recording history only (subscription untouched)")
            withStateLock {
                if (stateMachine.getCurrentState() == SubscriptionState.PurchaseInitiated) {
                    stateMachine.directTransitionTo(restoredStateAfterAbandonedPurchase())
                    // Cleared under the same lock that observed PurchaseInitiated so a
                    // concurrent startPurchase's stamp for a NEW flow cannot be zeroed
                    // after the fact (which would disarm stuck-purchase recovery).
                    purchaseInitiatedAtMs = 0L
                }
            }
            dbSyncService.saveUserCancelledPurchaseHistory("User cancelled purchase flow")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: purchaseFlowCancelled error: ${e.message}")
        }
    }

    /** Returns the state to restore after an abandoned purchase flow. */
    private fun restoredStateAfterAbandonedPurchase(): SubscriptionState {
        val status = stateMachine.getCurrentData()?.subscriptionStatus?.status
        return when (SubscriptionStatus.SubscriptionState.fromId(status ?: -1)) {
            SubscriptionStatus.SubscriptionState.STATE_ACTIVE -> SubscriptionState.Active
            SubscriptionStatus.SubscriptionState.STATE_CANCELLED -> SubscriptionState.Cancelled
            SubscriptionStatus.SubscriptionState.STATE_EXPIRED -> SubscriptionState.Expired
            SubscriptionStatus.SubscriptionState.STATE_REVOKED -> SubscriptionState.Revoked
            SubscriptionStatus.SubscriptionState.STATE_GRACE -> SubscriptionState.Grace
            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD -> SubscriptionState.OnHold
            SubscriptionStatus.SubscriptionState.STATE_PAUSED -> SubscriptionState.Paused
            SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING,
            SubscriptionStatus.SubscriptionState.STATE_PURCHASED -> SubscriptionState.PurchasePending
            else -> SubscriptionState.Initial
        }
    }

    /** Restores abandoned purchase flows that have exceeded the stuck timeout. */
    suspend fun recoverStuckPurchaseIfAny() {
        withStateLock { recoverStuckPurchaseInitiatedLocked() }
    }

    /** Recovery body. Caller MUST hold [stateLock] (directly or via [withStateLock]). */
    private suspend fun recoverStuckPurchaseInitiatedLocked() {
        if (stateMachine.getCurrentState() != SubscriptionState.PurchaseInitiated) return
        val enteredAt = purchaseInitiatedAtMs
        if (enteredAt == 0L) return // no purchase flow in progress (e.g. post-restart)
        val heldMs = System.currentTimeMillis() - enteredAt
        if (heldMs < PURCHASE_INITIATED_STUCK_TIMEOUT_MS) return
        Logger.w(LOG_IAB, "$TAG: recoverStuckPurchaseInitiated: PurchaseInitiated held for ${heldMs}ms; restoring pre-purchase state")
        stateMachine.directTransitionTo(restoredStateAfterAbandonedPurchase())
        purchaseInitiatedAtMs = 0L
        dbSyncService.saveUserCancelledPurchaseHistory("Purchase flow abandoned without Play callback (timeout)")
    }

    open suspend fun subscriptionExpired(purchaseToken: String? = null) {
        if (purchaseToken.isNullOrBlank()) {
            processEventSafely(SubscriptionEvent.SubscriptionExpired)
        } else {
            processTerminalEventForToken(
                purchaseToken,
                SubscriptionEvent.SubscriptionExpired,
                SubscriptionState.Expired
            )
        }
    }

    suspend fun subscriptionRevoked(purchaseToken: String? = null): Boolean {
        if (purchaseToken.isNullOrBlank()) {
            processEventSafely(SubscriptionEvent.SubscriptionRevoked)
            return true
        }
        return processTerminalEventForToken(
            purchaseToken,
            SubscriptionEvent.SubscriptionRevoked,
            SubscriptionState.Revoked
        )
    }

    suspend fun restoreSubscription(purchaseDetail: PurchaseDetail) {
        processEventSafely(SubscriptionEvent.SubscriptionRestored(purchaseDetail))
    }

    suspend fun systemCheck() {
        processEventSafely(SubscriptionEvent.SystemCheck)
    }

    suspend fun errorRecovered() {
        processEventSafely(SubscriptionEvent.ErrorRecovered)
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
        queriedProductType: String = BillingClient.ProductType.SUBS,
        shouldExpire: Boolean = true,
        preservedTokens: Set<String> = emptySet(),
        preservedRowIds: Set<Int> = emptySet(),
        authoritativelyExpiredRowIds: Set<Int> = emptySet()
    ) {
        if (purchases.isEmpty()) {
            if (queriedProductType == BillingClient.ProductType.SUBS) {
                // Play has no subscription for this account. Any active/cancelled SUBS row
                // in the local DB is stale the subscription has been revoked, refunded, or
                // fully lapsed without the app receiving a renewal callback.
                Logger.i(LOG_IAB, "$TAG: reconcile: Play returned empty SUBS snapshot, expiring active SUBS in DB (preserved=${preservedTokens.size})")
                expireStaleSubsFromDb(
                    shouldExpire,
                    preservedTokens,
                    preservedRowIds,
                    authoritativelyExpiredRowIds
                )
            } else {
                Logger.i(LOG_IAB, "$TAG: reconcile: Play returned empty INAPP snapshot, no action (INAPP expiry is clock-based)")
            }
            return
        }

        data class ReconcileAction(
            val detail: PurchaseDetail,
            val event: SubscriptionEvent,
            val isPlayCancelled: Boolean = false,
            /**
             * True when a data-less terminal event targets a non-current purchase.
             * Such events are applied to the DB row instead of the machine state.
             */
            val nonCurrentDatalessTerminal: Boolean = false
        )

        // Phase 1 – build actions while holding the lock (read-only, no DB calls).
        // IMPORTANT: currentMachineState / currentData MUST be read INSIDE stateLock.
        // Reading them before acquiring the lock is a TOCTOU race: stateLock is a
        // kotlinx.coroutines suspending Mutex (coroutine-owned, not thread-owned), and a
        // concurrent processEventSafely() running on a different coroutine of the same
        // Dispatchers.IO pool can transition the machine between the read and the lock
        // acquisition. The skipAlreadyActive / skipAlreadyCancelledValid / isResubscription
        // decisions below would then be computed against stale state, silently dropping a
        // needed transition. Reading them under the lock guarantees a consistent snapshot.
        val actions = stateLock.withLock {
            val currentMachineState = stateMachine.getCurrentState()
            val currentData         = stateMachine.getCurrentData()
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
                    // resolve it from BillingBackendClient.getDeviceIdForCid(cid).
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
                        playExpiry == Long.MAX_VALUE ->
                            if (tokenUnchanged) dbExpiry else Long.MAX_VALUE
                        // if DB has a future expiry (likely from server), and play's local estimate is older, keep DB
                        dbExpiry > 0L && tokenUnchanged && playExpiry <= dbExpiry -> dbExpiry
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
                        currentMachineState == SubscriptionState.Cancelled &&
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

                    val datalessTerminal = event is SubscriptionEvent.SubscriptionExpired ||
                        event is SubscriptionEvent.SubscriptionRevoked
                    val nonCurrentDatalessTerminal = datalessTerminal &&
                        currentData != null &&
                        currentData.subscriptionStatus.purchaseToken != reconciledDetail.purchaseToken
                    ReconcileAction(reconciledDetail, event, isPlayCancelled, nonCurrentDatalessTerminal)
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
                    if (action.nonCurrentDatalessTerminal) {
                        val terminalState = if (action.event is SubscriptionEvent.SubscriptionRevoked) {
                            SubscriptionState.Revoked
                        } else {
                            SubscriptionState.Expired
                        }
                        // update only the DB row under the state lock.
                        withStateLock { updateTerminalPurchaseRow(action.detail.purchaseToken, terminalState) }
                    } else if (action.isPlayCancelled) {
                        processPlayCancelledSafely(action.detail)
                    } else if (action.event is SubscriptionEvent.SubscriptionExpired ||
                        action.event is SubscriptionEvent.SubscriptionRevoked
                    ) {
                        val terminalState = if (action.event is SubscriptionEvent.SubscriptionRevoked) {
                            SubscriptionState.Revoked
                        } else {
                            SubscriptionState.Expired
                        }
                        processTerminalEventForToken(
                            action.detail.purchaseToken,
                            action.event,
                            terminalState
                        )
                    } else {
                        processEventSafely(action.event)
                    }
                } else {
                    // Just sync to DB if it's a PaymentSuccessful event.
                    when {
                        action.event is SubscriptionEvent.PaymentSuccessful ->
                            // update the purchase without changing the machine's primary row.
                            withStateLock { handlePaymentSuccessful(action.detail, updateMachineData = false) }
                        action.event is SubscriptionEvent.SubscriptionExpired ||
                        action.event is SubscriptionEvent.SubscriptionRevoked -> {
                            val terminalState = if (action.event is SubscriptionEvent.SubscriptionRevoked) {
                                SubscriptionState.Revoked
                            } else {
                                SubscriptionState.Expired
                            }
                            withStateLock { updateTerminalPurchaseRow(action.detail.purchaseToken, terminalState) }
                        }
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
            expireOrphanedSubsFromDb(
                playTokens,
                preservedTokens,
                preservedRowIds,
                authoritativelyExpiredRowIds
            )
        }
    }

    private suspend fun updateTerminalPurchaseRow(
        purchaseToken: String,
        terminalState: SubscriptionState
    ) {
        try {
            val existing = subscriptionDb.getByPurchaseToken(purchaseToken)
                ?.let(::copySubscriptionStatus) ?: run {
                Logger.w(LOG_IAB, "$TAG: updateTerminalPurchaseRow: no DB row for token=${purchaseToken.take(8)}")
                return
            }
            val targetStatus = when (terminalState) {
                SubscriptionState.Revoked -> SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
                else -> SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            }
            if (existing.status == targetStatus) {
                Logger.d(LOG_IAB, "$TAG: updateTerminalPurchaseRow: token=${purchaseToken.take(8)} already terminal")
                return
            }
            val prevStatus = existing.status
            val now = System.currentTimeMillis()
            existing.status = targetStatus
            existing.lastUpdatedTs = now
            // Clamp an unstarted or unbounded billing window to now, so a terminal row can
            // never look still-active to expiry/reconcile logic.
            if (existing.billingExpiry > now || existing.billingExpiry <= 0L ||
                existing.billingExpiry == Long.MAX_VALUE
            ) {
                existing.billingExpiry = now
                existing.accountExpiry = now
            }
            val updateId = subscriptionDb.upsert(existing)
            if (updateId <= 0L) {
                throw IllegalStateException("Unable to persist terminal state for token=${purchaseToken.take(8)}")
            }
            dbSyncService.recordHistoryOnly(
                subscriptionId = existing.id,
                fromStatusId   = prevStatus,
                toStatusId     = targetStatus,
                reason         = "Play reported ${terminalState.name.lowercase()} for token ${purchaseToken.take(8)}"
            )
            Logger.i(LOG_IAB, "$TAG: updateTerminalPurchaseRow: ${terminalState.name} row id=${existing.id}, token=${purchaseToken.take(8)} (machine state untouched)")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: updateTerminalPurchaseRow: ${e.message}", e)
        }
    }

    private suspend fun processPlayCancelledSafely(detail: PurchaseDetail) {
        withStateLock {
            val existing = subscriptionDb.getByPurchaseToken(detail.purchaseToken)
            val machineData = stateMachine.getCurrentData()
            val machineStatus = machineData?.subscriptionStatus
            val sameRevokedPurchase =
                existing?.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id ||
                    (machineStatus?.let {
                        it.purchaseToken == detail.purchaseToken &&
                            it.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
                    } == true)
            if (sameRevokedPurchase) {
                Logger.w(LOG_IAB, "$TAG: reconcile: ignoring Play cancellation for locally revoked token=${detail.purchaseToken.take(8)}")
                return@withStateLock
            }

            stateMachine.processEvent(SubscriptionEvent.PaymentSuccessful(detail))
            updateCancelledStatusInDb(detail)
            val current = stateMachine.getCurrentData()
            if (current?.subscriptionStatus?.purchaseToken == detail.purchaseToken &&
                current.subscriptionStatus.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id &&
                stateMachine.getCurrentState() != SubscriptionState.Revoked
            ) {
                stateMachine.directTransitionTo(SubscriptionState.Cancelled)
            }
        }
    }

    /**
     * Called after [reconcileWithPlayBilling] fires [PaymentSuccessful] for a purchase
     * where [Purchase.isAutoRenewing] = false (cancelled but not yet expired).
     */
    private suspend fun updateCancelledStatusInDb(detail: PurchaseDetail) {
        try {
            // Token-strict lookup: this function unconditionally writes CANCELLED, so it
            // must NEVER fall back to getCurrentSubscription() — a Play snapshot whose
            // token is unknown to the DB would otherwise stamp "the most recent row"
            // (possibly a different, ACTIVE purchase) as CANCELLED. When the token is
            // unknown, handlePaymentSuccessful is the correct writer: it creates the row
            // with targetStatus derived from Play (CANCELLED for isAutoRenewing=false).
            val existing = subscriptionDb.getByPurchaseToken(detail.purchaseToken)
                ?.let(::copySubscriptionStatus) ?: run {
                Logger.w(LOG_IAB, "$TAG: updateCancelledStatusInDb: no DB row for token " +
                    "${detail.purchaseToken.take(8)}, skipping (token-strict)")
                return
            }

            if (existing.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id) {
                Logger.i(LOG_IAB, "$TAG: updateCancelledStatusInDb: preserving REVOKED status for token=${detail.purchaseToken.take(8)}")
                return
            }

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
     *
     * @param shouldExpire If false, returns without expiring anything. Used by callers
     *                     that perform server validation before deciding whether to expire.
     */
    private suspend fun expireStaleSubsFromDb(
        shouldExpire: Boolean = true,
        preservedTokens: Set<String> = emptySet(),
        preservedRowIds: Set<Int> = emptySet(),
        authoritativelyExpiredRowIds: Set<Int> = emptySet()
    ) {
        if (!shouldExpire) {
            Logger.d(LOG_IAB, "$TAG: expireStaleSubsFromDb: shouldExpire=false, skipping expiry")
            return
        }
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

            val expiredRowIds = mutableSetOf<Int>()
            val protectedRowIds = mutableSetOf<Int>()
            // serialize row updates with state transitions.
            withStateLock {
                subsRows.forEach { sub ->
                    try {
                        if (sub.purchaseToken in preservedTokens || sub.id in preservedRowIds) {
                            protectedRowIds.add(sub.id)
                            Logger.i(LOG_IAB, "$TAG: expireStaleSubsFromDb: preserving token=${sub.purchaseToken.take(8)} (server validation)")
                            return@forEach
                        }
                        if (sub.id !in authoritativelyExpiredRowIds &&
                            now - sub.lastUpdatedTs < EMPTY_SNAPSHOT_FRESH_ROW_GUARD_MS &&
                            sub.billingExpiry !in 1..now
                        ) {
                            protectedRowIds.add(sub.id)
                            Logger.i(LOG_IAB, "$TAG: expireStaleSubsFromDb: preserving row id=${sub.id} " +
                                "(updated ${now - sub.lastUpdatedTs}ms ago < ${EMPTY_SNAPSHOT_FRESH_ROW_GUARD_MS}ms)")
                            return@forEach
                        }

                        val prevStatus = sub.status
                        sub.status        = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                        sub.lastUpdatedTs = now
                        // ensure expired rows have a valid, non-future expiry
                        if (sub.billingExpiry !in 1..now ||
                            sub.billingExpiry == Long.MAX_VALUE) {
                            sub.billingExpiry = now
                            sub.accountExpiry = now
                        }
                        val updateId = subscriptionDb.upsert(sub)
                        if (updateId <= 0L) {
                            throw IllegalStateException("Unable to persist stale SUBS expiry for row=${sub.id}")
                        }
                        expiredRowIds.add(sub.id)

                        dbSyncService.recordHistoryOnly(
                            subscriptionId = sub.id,
                            fromStatusId   = prevStatus,
                            toStatusId     = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                            reason         = "Play returned empty SUBS snapshot; subscription no longer active on Play"
                        )
                        Logger.i(LOG_IAB, "$TAG: expireStaleSubsFromDb: expired row id=${sub.id}, productId=${sub.productId}, token=${sub.purchaseToken.take(8)}")
                    } catch (e: Exception) {
                        protectedRowIds.add(sub.id)
                        Logger.e(LOG_IAB, "$TAG: expireStaleSubsFromDb: error expiring row id=${sub.id}: ${e.message}", e)
                    }
                }
            }
            val expiredCount = expiredRowIds.size

            if (expiredCount > 0) {
                val nowMs = System.currentTimeMillis()
                val expiredTokens = subsRows
                    .filter { it.id in expiredRowIds }
                    .map { it.purchaseToken }
                    .toSet()
                val stillValid = staleRows
                    .filter { it.id !in expiredRowIds }
                    .firstOrNull { row ->
                        row.status in listOf(
                            SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                            SubscriptionStatus.SubscriptionState.STATE_GRACE.id,
                            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id,
                            SubscriptionStatus.SubscriptionState.STATE_PAUSED.id
                        ) &&
                            (row.id in protectedRowIds ||
                                (isInAppProduct(row.productId) &&
                                    (row.billingExpiry <= 0L || row.billingExpiry == Long.MAX_VALUE ||
                                        row.billingExpiry > nowMs)) ||
                                (!isInAppProduct(row.productId) &&
                                    (row.billingExpiry == Long.MAX_VALUE || row.billingExpiry > nowMs)))
                    }
                if (stillValid != null) {
                    Logger.i(LOG_IAB, "$TAG: expireStaleSubsFromDb: expired $expiredCount SUBS row(s) but a still-valid row remains " +
                        "(id=${stillValid.id}, productId=${stillValid.productId}); re-pointing machine instead of Expired")
                    restoreMachineForValidSubscription(stillValid, expiredTokens)
                    return
                }

                val lastExpired = subsRows.lastOrNull {
                    it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                }
                // seed the expired row and transition atomically under the state lock
                withStateLock {
                    if (lastExpired != null) {
                        stateMachine.updateData(SubscriptionData(lastExpired))
                    }
                    stateMachine.processEvent(SubscriptionEvent.SubscriptionExpired)
                }
                Logger.i(LOG_IAB, "$TAG: expireStaleSubsFromDb: expired $expiredCount row(s), state machine transitioned to Expired")
            } else {
                val preservedRow = staleRows.firstOrNull { row ->
                    row.id in protectedRowIds &&
                        row.status in listOf(
                            SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                            SubscriptionStatus.SubscriptionState.STATE_GRACE.id,
                            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id,
                            SubscriptionStatus.SubscriptionState.STATE_PAUSED.id
                        )
                }
                if (preservedRow != null) {
                    restoreMachineForValidSubscription(preservedRow)
                }
                Logger.d(LOG_IAB, "$TAG: expireStaleSubsFromDb: no rows expired")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: expireStaleSubsFromDb: ${e.message}", e)
        }
    }

    /**
     * Re-points the machine to a known-valid row [sub] after an expiry pass.
     *
     * The re-point is skipped when the machine already serves a different, still-valid
     * purchase (non-terminal state, token not among [expiredTokens]): swapping a healthy
     * machine onto an unrelated preserved row would orphan the purchase it was serving
     * and fire a spurious RPN activation for it.
     */
    private suspend fun restoreMachineForValidSubscription(
        sub: SubscriptionStatus,
        expiredTokens: Set<String> = emptySet()
    ) {
        val purchaseDetail = createPurchaseDetailFromSubscription(sub)
        val targetState = when (sub.status) {
            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id -> SubscriptionState.Cancelled
            SubscriptionStatus.SubscriptionState.STATE_GRACE.id -> SubscriptionState.Grace
            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id -> SubscriptionState.OnHold
            SubscriptionStatus.SubscriptionState.STATE_PAUSED.id -> SubscriptionState.Paused
            else -> SubscriptionState.Active
        }
        var needsActivation = false
        withStateLock {
            val currentToken = stateMachine.getCurrentData()?.subscriptionStatus?.purchaseToken.orEmpty()
            if (currentToken.isNotBlank() && currentToken != sub.purchaseToken &&
                currentToken !in expiredTokens &&
                stateMachine.getCurrentState().hasValidSubscription
            ) {
                Logger.i(LOG_IAB, "$TAG: restoreMachineForValidSubscription: machine already serves a " +
                    "valid purchase (token=${currentToken.take(8)}); not re-pointing to token=${sub.purchaseToken.take(8)}")
                return@withStateLock
            }
            val samePurchase = currentToken == sub.purchaseToken
            stateMachine.updateData(SubscriptionData(sub, purchaseDetail))
            if (stateMachine.getCurrentState() != targetState) {
                stateMachine.directTransitionTo(targetState)
            }
            needsActivation = !samePurchase
        }
        if (needsActivation) {
            scope.launch {
                try { RpnProxyManager.activateRpn(purchaseDetail) }
                catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG: restoreMachineForValidSubscription: RPN activate error: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun expireOrphanedSubsFromDb(
        playTokens: Set<String>,
        preservedTokens: Set<String> = emptySet(),
        preservedRowIds: Set<Int> = emptySet(),
        authoritativelyExpiredRowIds: Set<Int> = emptySet()
    ) {
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
                    if (sub.purchaseToken in preservedTokens || sub.id in preservedRowIds) {
                        Logger.i(LOG_IAB, "$TAG: expireOrphanedSubsFromDb: preserving token=${sub.purchaseToken.take(8)} (server validation)")
                        return@forEach
                    }
                    if (sub.id !in authoritativelyExpiredRowIds &&
                        now - sub.lastUpdatedTs < EMPTY_SNAPSHOT_FRESH_ROW_GUARD_MS &&
                        // Same cap as expireStaleSubsFromDb: the fresh-row guard cannot
                        // extend past a recorded billing window that has already ended.
                        !(sub.billingExpiry in 1..now)
                    ) {
                        Logger.i(LOG_IAB, "$TAG: expireOrphanedSubsFromDb: preserving row id=${sub.id} " +
                            "(updated ${now - sub.lastUpdatedTs}ms ago < ${EMPTY_SNAPSHOT_FRESH_ROW_GUARD_MS}ms)")
                        return@forEach
                    }

                    val prevStatus = sub.status
                    sub.status        = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                    sub.lastUpdatedTs = now
                    // Clamp billingExpiry: an orphaned token is absent from Play's
                    // authoritative snapshot (refunded, superseded, or transferred).
                    // Access ended when Play removed the token, not at the end of the
                    // locally-estimated billing period which may still be in the future.
                    // Always clamp: billingExpiry=0 or a future estimate both cause
                    // handleSystemCheckAndDatabaseRestoration to resurrect Expired → Active.
                    if (sub.billingExpiry !in 1..now ||
                        sub.billingExpiry == Long.MAX_VALUE) {
                        sub.billingExpiry = now
                        sub.accountExpiry = now
                    }
                    val updateId = subscriptionDb.upsert(sub)
                    if (updateId <= 0L) {
                        throw IllegalStateException("Unable to persist orphaned SUBS expiry for row=${sub.id}")
                    }
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
    suspend fun expireStaleInAppFromDb(
        playTokens: Set<String>,
        preservedRowIds: Set<Int> = emptySet()
    ) {
        withStateLock {
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

            val inAppRows = rows.filter { sub -> isInAppProduct(sub.productId) }

            if (inAppRows.isEmpty()) {
                Logger.d(LOG_IAB, "$TAG: expireStaleInAppFromDb: no active INAPP rows")
                return@withStateLock
            }

            val now = System.currentTimeMillis()
            var expiredCount = 0
            val expiredRowIds = mutableSetOf<Int>()

            inAppRows.forEach { sub ->
                try {
                    if (sub.id in preservedRowIds) {
                        Logger.i(LOG_IAB, "$TAG: expireStaleInAppFromDb: preserving row id=${sub.id} (server validation)")
                        return@forEach
                    }
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
                        (sub.billingExpiry !in 1..now || sub.billingExpiry == Long.MAX_VALUE)) {
                        sub.billingExpiry = now
                        sub.accountExpiry = now
                    }
                    val updateId = subscriptionDb.upsert(sub)
                    if (updateId <= 0L) {
                        throw IllegalStateException("Unable to persist INAPP expiry for row=${sub.id}")
                    }

                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId = prevStatus,
                        toStatusId = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                        reason = reason
                    )
                    Logger.i(LOG_IAB, "$TAG: expireStaleInAppFromDb: expired id=${sub.id}, " +
                        "productId=${sub.productId}, token=${sub.purchaseToken.take(8)}, reason=$reason")
                    expiredCount++
                    expiredRowIds.add(sub.id)
                } catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG: expireStaleInAppFromDb: error expiring id=${sub.id}: ${e.message}", e)
                }
            }

            // Keep the machine on its current valid row when it survived this pass
            if (expiredCount > 0) {
                val expiredInAppIds = expiredRowIds
                val currentToken = stateMachine.getCurrentData()?.subscriptionStatus?.purchaseToken.orEmpty()
                val currentRow = rows.firstOrNull { it.purchaseToken == currentToken }
                if (currentToken.isNotBlank() && currentRow != null &&
                    currentRow.id !in expiredInAppIds &&
                    currentRow.status in listOf(
                        SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                        SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                        SubscriptionStatus.SubscriptionState.STATE_GRACE.id,
                        SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id,
                        SubscriptionStatus.SubscriptionState.STATE_PAUSED.id
                    ) &&
                    stateMachine.getCurrentState().hasValidSubscription
                ) {
                    Logger.i(LOG_IAB, "$TAG: expireStaleInAppFromDb: machine keeps serving its own " +
                        "surviving purchase (token=${currentToken.take(8)}); skipping re-point")
                    return@withStateLock
                }

                val validSubsRow = rows.firstOrNull { row ->
                    !isInAppProduct(row.productId) &&
                        row.status in listOf(
                            SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                            SubscriptionStatus.SubscriptionState.STATE_GRACE.id,
                            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id,
                            SubscriptionStatus.SubscriptionState.STATE_PAUSED.id
                        )
                }
                if (validSubsRow != null) {
                    val validPd = createPurchaseDetailFromSubscription(validSubsRow)
                    val currentData = stateMachine.getCurrentData()
                    val currentState = stateMachine.getCurrentState()
                    stateMachine.updateData(SubscriptionData(validSubsRow, validPd))
                    val restoredState = when (validSubsRow.status) {
                        SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id -> SubscriptionState.Cancelled
                        SubscriptionStatus.SubscriptionState.STATE_GRACE.id -> SubscriptionState.Grace
                        SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id -> SubscriptionState.OnHold
                        SubscriptionStatus.SubscriptionState.STATE_PAUSED.id -> SubscriptionState.Paused
                        else -> SubscriptionState.Active
                    }
                    stateMachine.directTransitionTo(restoredState)
                    if (currentState != restoredState ||
                        currentData?.subscriptionStatus?.purchaseToken != validSubsRow.purchaseToken
                    ) {
                        scope.launch {
                            try { RpnProxyManager.activateRpn(validPd) }
                            catch (e: Exception) {
                                Logger.e(LOG_IAB, "$TAG: expireStaleInAppFromDb: RPN restore error: ${e.message}", e)
                            }
                        }
                    }
                    Logger.i(
                        LOG_IAB,
                        "$TAG: expireStaleInAppFromDb: preserved current state on valid SUBS row " +
                            "id=${validSubsRow.id}, token=${validSubsRow.purchaseToken.take(8)}"
                    )
                    return@withStateLock
                }

                val validInAppRow = inAppRows.firstOrNull { row ->
                    row.id !in expiredRowIds &&
                        row.status in listOf(
                            SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
                        ) &&
                        (row.id in preservedRowIds ||
                            row.billingExpiry <= 0L ||
                            row.billingExpiry == Long.MAX_VALUE ||
                            row.billingExpiry > now)
                }
                if (validInAppRow != null) {
                    val validPd = createPurchaseDetailFromSubscription(validInAppRow)
                    val currentData = stateMachine.getCurrentData()
                    val currentState = stateMachine.getCurrentState()
                    stateMachine.updateData(SubscriptionData(validInAppRow, validPd))
                    val restoredState = if (
                        validInAppRow.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
                    ) {
                        SubscriptionState.Cancelled
                    } else {
                        SubscriptionState.Active
                    }
                    stateMachine.directTransitionTo(restoredState)
                    if (currentState != restoredState ||
                        currentData?.subscriptionStatus?.purchaseToken != validInAppRow.purchaseToken
                    ) {
                        scope.launch {
                            try { RpnProxyManager.activateRpn(validPd) }
                            catch (e: Exception) {
                                Logger.e(LOG_IAB, "$TAG: expireStaleInAppFromDb: RPN restore error: ${e.message}", e)
                            }
                        }
                    }
                    Logger.i(
                        LOG_IAB,
                        "$TAG: expireStaleInAppFromDb: restored machine to valid INAPP row " +
                            "id=${validInAppRow.id}, token=${validInAppRow.purchaseToken.take(8)}"
                    )
                } else {
                    // Seed machine data with the last expired INAPP row so
                    // handleSubscriptionExpiredWithData targets the correct row.
                    val lastInAppExpired = inAppRows.lastOrNull {
                        it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                    }
                    if (lastInAppExpired != null) {
                        val expiredPd = createPurchaseDetailFromSubscription(lastInAppExpired)
                        stateMachine.updateData(SubscriptionData(lastInAppExpired, expiredPd))
                        stateMachine.processEvent(SubscriptionEvent.SubscriptionExpired)
                    }
                }
            } else {
                Logger.d(LOG_IAB, "$TAG: expireStaleInAppFromDb: all INAPP rows still valid")
            }
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: expireStaleInAppFromDb: ${e.message}", e)
            }
        }

        Logger.i(LOG_IAB, "$TAG: expireStaleInAppFromDb: local expiry reconciliation complete")
    }

    /**
     * Returns all active INAPP (one-time) [SubscriptionStatus] rows from the database.
     *
     * Uses the same active-state set and product-ID filter as [expireStaleInAppFromDb] so the
     * caller always works on exactly the same rows that would otherwise be expired.
     * Propagates database errors instead of swallowing them.
     */
    suspend fun getActiveInAppPurchase(): List<SubscriptionStatus> {
        val activeStatuses = listOf(
            SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
            SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
            SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
        )
        val rows = subscriptionDb.getSubscriptionsByStatesRaw(activeStatuses)

        val inAppRows = rows.filter { sub -> isInAppProduct(sub.productId) }
        return inAppRows.distinctBy { sub ->
            if (sub.purchaseToken.isBlank()) true to sub.id.toString()
            else false to sub.purchaseToken
        }
    }

    /**
     * Returns all active SUBS (subscription) [SubscriptionStatus] rows from the database.
     *
     * Uses the same active-state set as [expireStaleSubsFromDb] so the caller always works on
     * exactly the same rows that would otherwise be expired.
     *
     * Returns an empty list if no active subscriptions exist.
     */
    suspend fun getActiveSubsPurchase(): List<SubscriptionStatus> {
        val activeStatuses = listOf(
            SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
            SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
            SubscriptionStatus.SubscriptionState.STATE_GRACE.id,
            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id,
            SubscriptionStatus.SubscriptionState.STATE_PAUSED.id,
            SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
            SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
        )
        val rows = subscriptionDb.getSubscriptionsByStatesRaw(activeStatuses)
        return rows.filter { sub -> !isInAppProduct(sub.productId) }
            .distinctBy { sub ->
                if (sub.purchaseToken.isBlank()) true to sub.id.toString()
                else false to sub.purchaseToken
            }
    }

    /**
     * Updates the server-issued expiry for [purchaseToken].
     * Only extends the existing expiry; invalid or stale values are ignored.
     *
     * @return true when the row was updated.
     */
    open suspend fun refreshBillingExpiryFromServer(purchaseToken: String, serverExpiry: Long): Boolean {
        if (purchaseToken.isBlank()) return false
        if (serverExpiry <= 0L || serverExpiry == Long.MAX_VALUE) return false
        val now = System.currentTimeMillis()
        if (serverExpiry <= now) return false
        return try {
            withStateLock {
                val row = subscriptionDb.getByPurchaseToken(purchaseToken)
                if (row == null) {
                    Logger.d(LOG_IAB, "$TAG: refreshBillingExpiryFromServer: no row for token=${purchaseToken.take(8)}; skipping")
                    return@withStateLock false
                }
                if (row.billingExpiry != Long.MAX_VALUE && row.billingExpiry >= serverExpiry) {
                    return@withStateLock false
                }
                val updated = subscriptionDb.updateBillingExpiry(row.id, serverExpiry, now)
                if (updated > 0) {
                    Logger.i(LOG_IAB, "$TAG: refreshBillingExpiryFromServer: token=${purchaseToken.take(8)} " +
                        "billingExpiry ${row.billingExpiry} → $serverExpiry (server-issued)")
                    // keep the current machine data in sync with the DB.
                    val currentData = stateMachine.getCurrentData()
                    if (currentData?.subscriptionStatus?.purchaseToken == purchaseToken) {
                        val refreshed = copySubscriptionStatus(row).also { st ->
                            st.billingExpiry = serverExpiry
                            st.lastUpdatedTs = now
                        }
                        stateMachine.updateData(SubscriptionData(refreshed, currentData.purchaseDetail))
                    }
                } else {
                    Logger.w(LOG_IAB, "$TAG: refreshBillingExpiryFromServer: DB update failed for token=${purchaseToken.take(8)}")
                }
                updated > 0
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: refreshBillingExpiryFromServer: ${e.message}", e)
            false
        }
    }

    // NOTE: intentionally NOT named `getCurrentState()` — the JVM signature would collide
    // with the `currentState` property getter above (same name + params, return type only
    // differs). ByteBuddy/MockK cannot proxy such colliding pairs, which broke every unit
    // test that stubbed this class (the real getter ran on mocks whose `stateMachine` field
    // is null, throwing NPE).
    open fun currentMachineState(): SubscriptionState = stateMachine.getCurrentState()
    open fun getSubscriptionData(): SubscriptionData? = stateMachine.getCurrentData()

    /**
     * Updates the payload for the current [token] under the state lock.
     * Returns null when the machine has no data or serves a different token.
     */
    open suspend fun updatePayloadForToken(
        token: String,
        payload: String,
        purchaseDetail: PurchaseDetail? = null
    ): SubscriptionData? {
        if (token.isBlank()) return null
        return withStateLock {
            val current = stateMachine.getCurrentData() ?: return@withStateLock null
            if (current.subscriptionStatus.purchaseToken != token) return@withStateLock null
            val updatedRow = copySubscriptionStatus(current.subscriptionStatus)
            updatedRow.developerPayload = payload
            val updated = current.copy(
                subscriptionStatus = updatedRow,
                purchaseDetail = purchaseDetail ?: current.purchaseDetail
            )
            stateMachine.updateData(updated)
            updated
        }
    }

    /**
     * Refreshes the payload for the current [token] under the state lock,
     * updating both the machine data and DB row.
     */
    open suspend fun refreshPayloadForToken(
        token: String,
        payload: String,
        purchaseDetail: PurchaseDetail? = null
    ): Boolean {
        if (token.isBlank()) return false
        return try {
            withStateLock {
                val current = stateMachine.getCurrentData() ?: return@withStateLock false
                if (current.subscriptionStatus.purchaseToken != token) return@withStateLock false
                val updatedRow = copySubscriptionStatus(current.subscriptionStatus)
                updatedRow.developerPayload = payload
                val upsertId = subscriptionDb.upsert(updatedRow)
                if (upsertId <= 0L) {
                    Logger.e(LOG_IAB, "$TAG: refreshPayloadForToken: DB upsert failed for token=${token.take(8)}")
                    return@withStateLock false
                }
                stateMachine.updateData(
                    current.copy(
                        subscriptionStatus = updatedRow,
                        purchaseDetail = purchaseDetail ?: current.purchaseDetail
                    )
                )
                true
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: refreshPayloadForToken: ${e.message}", e)
            false
        }
    }

    open fun canMakePurchase(): Boolean = stateMachine.getCurrentState().canMakePurchase
    open fun hasValidSubscription(): Boolean {
        val rowStatus = stateMachine.getCurrentData()?.subscriptionStatus?.status
        if (rowStatus == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id ||
            rowStatus == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
        ) {
            return false
        }
        return stateMachine.getCurrentState().hasValidSubscription
    }
    open fun isSubscriptionActive(): Boolean {
        val rowStatus = stateMachine.getCurrentData()?.subscriptionStatus?.status
        if (rowStatus == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id ||
            rowStatus == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
        ) {
            return false
        }
        return stateMachine.getCurrentState().isActive
    }
    open fun getStatistics(): StateMachineStatistics = stateMachine.getStatistics()

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
                throw IllegalStateException("Unable to persist completed purchase ${purchaseDetail.purchaseToken.take(8)}")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handlePurchaseCompleted: ${e.message}", e)
            throw e
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
            // Callers that need the actual device ID must resolve it for the row's cid via
            // BillingBackendClient.getDeviceIdForCid(cid).
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

    private suspend fun handleServerAckFailed(error: String) {
        try {
            Logger.w(LOG_IAB, "$TAG: handleServerAckFailed: payment received but server ack failed: $error")
            dbSyncService.savePurchaseFailureHistory("ServerAckFailed: $error", null)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleServerAckFailed error: ${e.message}", e)
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
            val existingLatest  = if (existingByToken == null) {
                subscriptionDb.getCurrentSubscription()?.let(::copySubscriptionStatus)
            } else {
                null
            }
            val existing        = existingByToken ?: existingLatest
            val machineStatus = stateMachine.getCurrentData()?.subscriptionStatus
            val alreadyRevoked =
                existingByToken?.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id ||
                    (machineStatus?.let {
                        it.purchaseToken == purchaseDetail.purchaseToken &&
                            it.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
                    } == true)

            if (alreadyRevoked) {
                Logger.w(LOG_IAB, "$TAG: handlePaymentSuccessful: refusing same-token reactivation after revoke " +
                    "(token=${purchaseDetail.purchaseToken.take(8)})")
                if (updateMachineData) {
                    val revokedRow = if (
                        machineStatus?.purchaseToken == purchaseDetail.purchaseToken &&
                        machineStatus.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
                    ) {
                        machineStatus
                    } else {
                        existingByToken?.let(::copySubscriptionStatus)
                    }
                    if (revokedRow != null) {
                        stateMachine.updateData(SubscriptionData(revokedRow, purchaseDetail))
                    }
                    stateMachine.directTransitionTo(SubscriptionState.Revoked)
                    scope.launch {
                        try { RpnProxyManager.deactivateRpn("revoked") }
                        catch (e: Exception) {
                            Logger.e(LOG_IAB, "$TAG: handlePaymentSuccessful: revoke enforcement failed: ${e.message}", e)
                        }
                    }
                }
                throw IllegalStateException("Purchase token ${purchaseDetail.purchaseToken.take(8)} is revoked")
            }

            if (existingByToken == null) {
                // new purchase token: restart the monthly forced-reconcile window
                persistentState.lastForcedReconcileTimestamp = System.currentTimeMillis()
            }

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

            val rowToSave: SubscriptionStatus = existingByToken?.let { existingRow ->
                copySubscriptionStatus(existingRow).also { s ->
                    syncAllFields(s, purchaseDetail, billingExpiry, sessionToken)
                    s.status = targetStatus
                }
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
                throw IllegalStateException("Unable to persist successful purchase ${purchaseDetail.purchaseToken.take(8)}")
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
            throw e
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
    }

    private fun copySubscriptionStatus(source: SubscriptionStatus): SubscriptionStatus =
        SubscriptionStatus().also { copy ->
            copy.id = source.id
            copy.accountId = source.accountId
            copy.deviceId = source.deviceId
            copy.purchaseToken = source.purchaseToken
            copy.orderId = source.orderId
            copy.productId = source.productId
            copy.planId = source.planId
            copy.sessionToken = source.sessionToken
            copy.productTitle = source.productTitle
            copy.state = source.state
            copy.purchaseTime = source.purchaseTime
            copy.accountExpiry = source.accountExpiry
            copy.billingExpiry = source.billingExpiry
            copy.developerPayload = source.developerPayload
            copy.status = source.status
            copy.windowDays = source.windowDays
            copy.lastUpdatedTs = source.lastUpdatedTs
            copy.previousProductId = source.previousProductId
            copy.previousPurchaseToken = source.previousPurchaseToken
            copy.replacedAt = source.replacedAt
        }

    private suspend fun isCancelableSubscription(
        data: SubscriptionData?,
        purchaseToken: String?
    ): Boolean {
        val row = when {
            !purchaseToken.isNullOrBlank() -> subscriptionDb.getByPurchaseToken(purchaseToken)
            data != null -> data.subscriptionStatus
            else -> subscriptionDb.getCurrentSubscription()
        } ?: return false
        return row.status != SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id &&
            row.status != SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
    }

    private suspend fun handleUserCancelled(
        data: SubscriptionData?,
        purchaseToken: String? = null,
        caller: String? = null,
        updateMachineData: Boolean = true
    ) {
        try {
            Logger.i(LOG_IAB, "$TAG: handleUserCancelled (token=${purchaseToken?.take(8) ?: "none"}, caller=${caller ?: "unknown"})")
            val source = if (purchaseToken != null) {
                subscriptionDb.getByPurchaseToken(purchaseToken) ?: run {
                    Logger.w(LOG_IAB, "$TAG: handleUserCancelled: no row for token=${purchaseToken.take(8)}; skipping")
                    return
                }
            } else {
                data?.subscriptionStatus
                    ?: subscriptionDb.getCurrentSubscription()
                    ?: run {
                        Logger.w(LOG_IAB, "$TAG: handleUserCancelled: no subscription data or DB row found")
                        return
                    }
            }
            val sub = copySubscriptionStatus(source)
            val prevStatus = sub.status

            if (prevStatus == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id ||
                prevStatus == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
            ) {
                Logger.i(LOG_IAB, "$TAG: handleUserCancelled: row already " +
                    "${SubscriptionStatus.SubscriptionState.fromId(prevStatus).name}; skipping cancel write")
                return
            }

            // If the DB row is already marked CANCELLED, skip the upsert entirely.
            // Cold-start restoration may reach this path; an unnecessary upsert
            // is avoided but the guard window no longer exists to block expiry.
            if (prevStatus == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id) {
                Logger.d(LOG_IAB, "$TAG: handleUserCancelled: already CANCELLED, restoring machine data only, no DB write")
                if (updateMachineData) {
                    stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
                }
                return
            }

            sub.status        = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            sub.lastUpdatedTs = System.currentTimeMillis()
            val updateResult = subscriptionDb.upsert(sub)
            if (updateResult > 0) {
                if (updateMachineData) {
                    stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
                }
                dbSyncService.recordHistoryOnly(
                    subscriptionId = sub.id,
                    fromStatusId = prevStatus,
                    toStatusId = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                    reason = "User cancelled subscription"
                )
            } else {
                Logger.e(LOG_IAB, "$TAG: handleUserCancelled: DB update failed")
                throw IllegalStateException("Unable to persist cancellation for token ${purchaseToken?.take(8) ?: "current"}")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleUserCancelled error: ${e.message}", e)
            throw e
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
            val source = data?.subscriptionStatus
                ?: subscriptionDb.getCurrentSubscription()
                ?: run {
                    Logger.w(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData: no subscription found in DB")
                    return
                }
            val sub = copySubscriptionStatus(source)

            val prevStatus = sub.status
            if (sub.status != SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id) {
                sub.status = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
                sub.lastUpdatedTs = System.currentTimeMillis()
            }
            stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
            stateMachine.directTransitionTo(SubscriptionState.Expired)
            scope.launch {
                try { RpnProxyManager.deactivateRpn("expired") }
                catch (e: Exception) { Logger.e(LOG_IAB, "$TAG: deactivate RPN error: ${e.message}", e) }
            }
            if (prevStatus != SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id) {
                val updateResult = subscriptionDb.upsert(sub)
                if (updateResult > 0) {
                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId   = prevStatus,
                        toStatusId     = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                        reason         = "Subscription expired (billingExpiry=${sub.billingExpiry})"
                    )
                } else {
                    Logger.e(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData: DB update failed")
                    throw IllegalStateException("Unable to persist expiration for token ${sub.purchaseToken.take(8)}")
                }
            } else {
                Logger.d(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData: already EXPIRED in DB, no-op")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSubscriptionExpiredWithData error: ${e.message}", e)
            throw e
        }
    }

    private suspend fun handleSubscriptionRestored(purchaseDetail: PurchaseDetail) {
        try {
            Logger.i(LOG_IAB, "$TAG: handleSubscriptionRestored: ${purchaseDetail.productId}")
            val existing = subscriptionDb.getByPurchaseToken(purchaseDetail.purchaseToken)
                ?.let(::copySubscriptionStatus)

            if (existing != null) {
                if (existing.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id) {
                    stateMachine.updateData(SubscriptionData(existing, purchaseDetail))
                    stateMachine.directTransitionTo(SubscriptionState.Revoked)
                    scope.launch {
                        try { RpnProxyManager.deactivateRpn("revoked") }
                        catch (e: Exception) {
                            Logger.e(LOG_IAB, "$TAG: handleSubscriptionRestored: revoke enforcement failed: ${e.message}", e)
                        }
                    }
                    throw IllegalStateException("Cannot restore revoked purchase ${purchaseDetail.purchaseToken.take(8)}")
                }
                if (existing.status != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id &&
                    existing.status != SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
                ) {
                    handlePaymentSuccessful(purchaseDetail)
                    return
                }
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
                    throw IllegalStateException("Unable to persist restored purchase ${purchaseDetail.purchaseToken.take(8)}")
                }
            } else {
                // No existing row; treat as a fresh payment.
                handlePaymentSuccessful(purchaseDetail)
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSubscriptionRestored error: ${e.message}", e)
            throw e
        }
    }

    private suspend fun handleSubscriptionRevoked(data: SubscriptionData?) {
        try {
            Logger.w(LOG_IAB, "$TAG: handleSubscriptionRevoked")
            val source = data?.subscriptionStatus
                ?: subscriptionDb.getCurrentSubscription()
                ?: run {
                    Logger.w(LOG_IAB, "$TAG: handleSubscriptionRevoked: no subscription found")
                    return
                }
            val sub = copySubscriptionStatus(source)

            val prevStatus = sub.status
            stateMachine.updateData(SubscriptionData(sub, data?.purchaseDetail))
            stateMachine.directTransitionTo(SubscriptionState.Revoked)
            scope.launch {
                try { RpnProxyManager.deactivateRpn("revoked") }
                catch (e: Exception) { Logger.e(LOG_IAB, "$TAG: deactivate RPN error: ${e.message}", e) }
            }
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
                    dbSyncService.recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId = prevStatus,
                        toStatusId = SubscriptionStatus.SubscriptionState.STATE_REVOKED.id,
                        reason = "Subscription revoked by system"
                    )
                } else {
                    Logger.e(LOG_IAB, "$TAG: handleSubscriptionRevoked: DB update failed")
                    throw IllegalStateException("Unable to persist revocation for token ${sub.purchaseToken.take(8)}")
                }
            } else {
                Logger.d(LOG_IAB, "$TAG: handleSubscriptionRevoked: already REVOKED, no-op")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: handleSubscriptionRevoked error: ${e.message}", e)
            throw e
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
            withContext(StateLockMarker()) {
        try {
            Logger.i(LOG_IAB, "$TAG: handleSystemCheckAndDatabaseRestoration")
            val dbStateInfo = dbSyncService.loadStateFromDatabase()
            if (dbStateInfo == null) {
                Logger.d(LOG_IAB, "$TAG: no DB state to restore")
                return@withContext
            }

            val sub = dbStateInfo.currentSubscription
            Logger.i(LOG_IAB, "$TAG: DB state: ${dbStateInfo.recommendedState.name}, billingExpiry=${sub.billingExpiry}")

            val effectiveState: SubscriptionState = dbStateInfo.recommendedState

            // Restore in-memory data first (no DB write)
            stateMachine.updateData(SubscriptionData(sub))

            val pd = createPurchaseDetailFromSubscription(sub)
            when (effectiveState) {
                SubscriptionState.Active,
                SubscriptionState.Grace,
                SubscriptionState.OnHold,
                SubscriptionState.Paused -> {
                    // Restore in memory; fire SubscriptionRestored which is idempotent
                    // (skips DB write if prevStatus already ACTIVE).
                    stateMachine.processEvent(SubscriptionEvent.SubscriptionRestored(pd))
                    // Preserve the purchaseDetail so callers (e.g. cancelPurchase /
                    // revokeSubscription) can read it without hitting the DB again.
                    stateMachine.updateData(SubscriptionData(sub, pd))
                }
                SubscriptionState.Cancelled -> {
                    stateMachine.directTransitionTo(SubscriptionState.Cancelled)
                    stateMachine.updateData(SubscriptionData(sub, pd))
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
            } // withContext(StateLockMarker())
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
                //
                // CRITICAL: cannot call processEventSafely() inline here. handleSystemCheck
                // is a transition ACTION — it runs synchronously INSIDE
                // processEventSafely's `stateLock.withLock { stateMachine.processEvent(...) }`
                // stateLock is a non-reentrant Mutex, so re-entering it would
                // self-deadlock (same class of bug as the identityMutex self-deadlock in
                // BillingBackendClient.getDeviceId)
                scope.launch { processEventSafely(SubscriptionEvent.SubscriptionExpired) }
                Logger.i(LOG_IAB, "$TAG: handleSystemCheck, deferred SubscriptionExpired event (INAPP local expiry)")
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
