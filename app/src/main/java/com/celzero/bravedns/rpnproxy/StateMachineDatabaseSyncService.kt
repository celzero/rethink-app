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
// BillingResult import removed: savePurchaseFailureHistory now uses Int? for cross-flavor compat
import com.celzero.bravedns.database.SubscriptionStateHistory
import com.celzero.bravedns.database.SubscriptionStateHistoryDao
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.PurchaseDetail
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles persistence of subscription state history and DB-state loading.
 */
class StateMachineDatabaseSyncService : KoinComponent {

    private val subscriptionRepository by inject<SubscriptionStatusRepository>()
    private val historyDAO: SubscriptionStateHistoryDao by inject()

    companion object {
        private const val TAG = "StateMachineDBSync"
    }

    data class DatabaseStateInfo(
        val currentSubscription: SubscriptionStatus,
        val recommendedState: SubscriptionStateMachineV2.SubscriptionState,
        val lastTransitionTime: Long
    )

    suspend fun loadStateFromDatabase(): DatabaseStateInfo? {
        return try {
            Logger.d(LOG_IAB, "$TAG: loadStateFromDatabase")
            val sub = subscriptionRepository.getCurrentSubscription() ?: return null
            val state = determineStateFromSubscription(sub)
            val lastTs = getLastTransitionTime(sub.id)
            Logger.d(LOG_IAB, "$TAG: loaded state=${state.name} for productId=${sub.productId}, billingExpiry=${sub.billingExpiry}")
            DatabaseStateInfo(sub, state, lastTs)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: loadStateFromDatabase error: ${e.message}", e)
            null
        }
    }

    /**
     * Derive the recommended in-memory state from the stored [SubscriptionStatus].
     *
     * - **SUBS**: Google Play is the single authority on active/expired. The local
     *   billingExpiry is only an estimate.  We never expire a SUBS from the local clock
     *   [reconcileWithPlayBilling] will correct the state seconds after app start.
     * - **INAPP**: The access window is fixed (e.g. 2 years). Play has no server-side
     *   record of per-entitlement expiry for one-time purchases, so our local expiryTime
     *   IS the authority gate.
     * - billingExpiry = 0 → expiry unknown → return Active (reconcile will correct).
     * - Never return state values that have no registered transitions (Unknown removed).
     */
    private fun determineStateFromSubscription(
        sub: SubscriptionStatus
    ): SubscriptionStateMachineV2.SubscriptionState {
        val now = System.currentTimeMillis()
        val hasRealExpiry = sub.billingExpiry > 0L && sub.billingExpiry != Long.MAX_VALUE
        val isLocallyExpired = hasRealExpiry && sub.billingExpiry < now

        val isInApp = SubscriptionStateMachineV2.isInAppProduct(sub.productId)

        Logger.d(
            LOG_IAB, "$TAG: determineState: status=${sub.status}, productId=${sub.productId}, " +
                "isInApp=$isInApp, billingExpiry=${sub.billingExpiry}, " +
                "hasRealExpiry=$hasRealExpiry, isLocallyExpired=$isLocallyExpired"
        )

        return when (SubscriptionStatus.SubscriptionState.fromId(sub.status)) {

            SubscriptionStatus.SubscriptionState.STATE_ACTIVE -> {
                when {
                    // INAPP: fixed access window, local expiry IS authoritative
                    isInApp && isLocallyExpired -> {
                        Logger.d(LOG_IAB, "$TAG: INAPP active purchase expired locally → Expired")
                        SubscriptionStateMachineV2.SubscriptionState.Expired
                    }
                    // SUBS: Play is the ONLY authority, never expire from local clock.
                    // billingExpiry is an estimate only; Play may have already renewed.
                    // Play reconcile (run seconds after app start) will correct if truly expired.
                    else -> {
                        Logger.d(LOG_IAB, "$TAG: active → Active (${if (isInApp) "INAPP valid" else "SUBS, deferring to Play"})")
                        SubscriptionStateMachineV2.SubscriptionState.Active
                    }
                }
            }

            SubscriptionStatus.SubscriptionState.STATE_CANCELLED -> {
                when {
                    // INAPP cancelled/expired
                    isInApp && isLocallyExpired -> {
                        Logger.d(LOG_IAB, "$TAG: INAPP cancelled purchase expired locally → Expired")
                        SubscriptionStateMachineV2.SubscriptionState.Expired
                    }
                    // SUBS canceled: NEVER use local clock to mark Expired.
                    // The user still has access until Play says otherwise.
                    // Canceled + billingExpiry < now → return Canceled so the machine
                    // stays in Canceled (which hasValidSubscription = true for UI).
                    // Play reconcile will fire SubscriptionExpired if truly expired.
                    !isInApp -> {
                        Logger.d(LOG_IAB, "$TAG: SUBS cancelled → Cancelled (Play is master for expiry)")
                        SubscriptionStateMachineV2.SubscriptionState.Cancelled
                    }
                    // INAPP not locally expired and billing period still valid
                    else -> {
                        Logger.d(LOG_IAB, "$TAG: INAPP cancelled, still valid �� Cancelled")
                        SubscriptionStateMachineV2.SubscriptionState.Cancelled
                    }
                }
            }

            SubscriptionStatus.SubscriptionState.STATE_EXPIRED ->
                SubscriptionStateMachineV2.SubscriptionState.Expired

            SubscriptionStatus.SubscriptionState.STATE_REVOKED ->
                SubscriptionStateMachineV2.SubscriptionState.Revoked

            SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING,
            SubscriptionStatus.SubscriptionState.STATE_PURCHASED ->
                SubscriptionStateMachineV2.SubscriptionState.PurchasePending

            SubscriptionStatus.SubscriptionState.STATE_GRACE ->
                SubscriptionStateMachineV2.SubscriptionState.Grace

            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD ->
                SubscriptionStateMachineV2.SubscriptionState.OnHold

            SubscriptionStatus.SubscriptionState.STATE_PAUSED ->
                SubscriptionStateMachineV2.SubscriptionState.Paused

            // STATE_INITIAL, STATE_PURCHASE_FAILED, STATE_UNKNOWN → Initial
            else -> {
                Logger.w(LOG_IAB, "$TAG: unrecognized DB status ${sub.status} → Initial")
                SubscriptionStateMachineV2.SubscriptionState.Initial
            }
        }
    }

    /**
     * Insert a single [SubscriptionStateHistory] row.
     *
     * When [fromStatusId] == [toStatusId] no real state transition occurred (e.g.
     * `Active → Active` during a subscription renewal where only billingExpiry
     * changed).  These entries are pure noise in the payment history UI and are
     * skipped here.  Every meaningful transition (Initial → Active, Active →
     * Canceled, etc.) always has different from/to values, so this guard never
     * suppresses a real event.
     */
    suspend fun recordHistoryOnly(
        subscriptionId: Int,
        fromStatusId: Int,
        toStatusId: Int,
        reason: String? = null
    ) {
        val normalizedFrom = if (fromStatusId < 0) SubscriptionStatus.SubscriptionState.STATE_INITIAL.id else fromStatusId
        val normalizedTo   = if (toStatusId   < 0) SubscriptionStatus.SubscriptionState.STATE_INITIAL.id else toStatusId

        if (normalizedFrom == normalizedTo) {
            Logger.d(LOG_IAB, "$TAG: recordHistoryOnly: skipping same-state entry sub=$subscriptionId state=$normalizedFrom reason=$reason")
            return
        }
        try {
            val history = SubscriptionStateHistory(
                subscriptionId = subscriptionId,
                fromState      = normalizedFrom,
                toState        = normalizedTo,
                timestamp      = System.currentTimeMillis(),
                reason         = reason
            )
            val id = historyDAO.insert(history)
            Logger.d(LOG_IAB, "$TAG: history recorded id=$id sub=$subscriptionId: $normalizedFrom → $normalizedTo reason=$reason")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: recordHistoryOnly error: ${e.message}", e)
        }
    }

    /**
     * Records a state transition in history only.
     * Does NOT update the subscription row - the state machine handlers own that.
     * Kept for callers that pass SubscriptionData; delegates to [recordHistoryOnly].
     */
    suspend fun saveStateTransition(
        fromState: SubscriptionStateMachineV2.SubscriptionState,
        toState: SubscriptionStateMachineV2.SubscriptionState,
        subscriptionData: SubscriptionStateMachineV2.SubscriptionData?,
        reason: String? = null
    ) {
        val data = subscriptionData ?: return
        recordHistoryOnly(
            subscriptionId = data.subscriptionStatus.id,
            fromStatusId   = mapStateToStatusId(fromState),
            toStatusId     = mapStateToStatusId(toState),
            reason         = reason
        )
    }

    /**
     * Save a new purchase to the DB (insert only if not already present by token).
     * Returns the row id on success, -1 on failure.
     *
     * NOTE: This method only creates the initial row. All subsequent updates are
     * handled exclusively by [SubscriptionStateMachineV2.handlePaymentSuccessful].
     */
    suspend fun savePurchaseDetail(purchaseDetail: PurchaseDetail): Long {
        return try {
            Logger.d(LOG_IAB, "$TAG: savePurchaseDetail: ${purchaseDetail.productId}")
            val existing = subscriptionRepository.getByPurchaseToken(purchaseDetail.purchaseToken)
            if (existing != null) {
                Logger.d(LOG_IAB, "$TAG: savePurchaseDetail: row already exists id=${existing.id}")
                // update developerPayload and orderId if they are provided in purchaseDetail
                // but missing in DB. This handles the case where /g/ack returns the payload
                // but handlePaymentSuccessful hasn't run yet.
                var needsUpdate = false
                if (purchaseDetail.payload.isNotEmpty() && existing.developerPayload != purchaseDetail.payload) {
                    existing.developerPayload = purchaseDetail.payload
                    needsUpdate = true
                }
                if (purchaseDetail.orderId.isNotEmpty() && existing.orderId != purchaseDetail.orderId) {
                    existing.orderId = purchaseDetail.orderId
                    needsUpdate = true
                }
                if (needsUpdate) {
                    subscriptionRepository.upsert(existing)
                    Logger.i(LOG_IAB, "$TAG: savePurchaseDetail: updated existing row id=${existing.id} with new data")
                }
                existing.id.toLong()
            } else {
                val sub = convertPurchaseDetailToSubscriptionStatus(purchaseDetail)
                val id  = subscriptionRepository.insert(sub)
                Logger.d(LOG_IAB, "$TAG: savePurchaseDetail: new row id=$id")
                id
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: savePurchaseDetail error: ${e.message}", e)
            -1
        }
    }

    suspend fun updateSubscriptionExpiry(
        subscriptionId: Int,
        billingExpiry: Long,
        accountExpiry: Long
    ) {
        try {
            Logger.d(LOG_IAB, "$TAG: updateSubscriptionExpiry id=$subscriptionId, billing=$billingExpiry, account=$accountExpiry")
            val ts = System.currentTimeMillis()
            subscriptionRepository.updateBillingExpiry(subscriptionId, billingExpiry, ts)
            subscriptionRepository.updateAccountExpiry(subscriptionId, accountExpiry, ts)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: updateSubscriptionExpiry error: ${e.message}", e)
        }
    }

    suspend fun markSubscriptionRevoked(subscriptionId: Int) {
        try {
            Logger.w(LOG_IAB, "$TAG: markSubscriptionRevoked id=$subscriptionId")
            // Fetch the actual current status so history records the real transition
            // (e.g. CANCELLED → REVOKED) instead of always assuming ACTIVE → REVOKED.
            val prevStatus = try {
                subscriptionRepository.getById(subscriptionId)?.status
                    ?: SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            } catch (_: Exception) {
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            }
            val ts = System.currentTimeMillis()
            subscriptionRepository.updateStatus(
                subscriptionId,
                SubscriptionStatus.SubscriptionState.STATE_REVOKED.id,
                ts
            )
            recordHistoryOnly(
                subscriptionId = subscriptionId,
                fromStatusId   = prevStatus,
                toStatusId     = SubscriptionStatus.SubscriptionState.STATE_REVOKED.id,
                reason         = "Subscription revoked by system"
            )
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: markSubscriptionRevoked error: ${e.message}", e)
        }
    }

    suspend fun savePurchaseFailureHistory(error: String, billingResultCode: Int?) {
        try {
            Logger.e(LOG_IAB, "$TAG: savePurchaseFailureHistory: $error")
            val sub = try {
                subscriptionRepository.getCurrentSubscription()
            } catch (_: Exception) { null }
            val subscriptionId = sub?.id ?: 0
            // Use the current DB status as fromState (not hardcoded STATE_INITIAL)
            // so history accurately reflects where the failure originated.
            val fromStatusId = sub?.status
                ?: SubscriptionStatus.SubscriptionState.STATE_INITIAL.id
            recordHistoryOnly(
                subscriptionId = subscriptionId,
                fromStatusId   = fromStatusId,
                toStatusId     = SubscriptionStatus.SubscriptionState.STATE_PURCHASE_FAILED.id,
                reason         = "Purchase failed: $error, BillingResult: ${billingResultCode ?: "Unknown"}"
            )
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: savePurchaseFailureHistory error: ${e.message}", e)
        }
    }

    suspend fun saveUserCancelledPurchaseHistory(reason: String = "User cancelled purchase flow") {
        try {
            val sub = try {
                subscriptionRepository.getCurrentSubscription()
            } catch (_: Exception) { null }
            val subscriptionId = sub?.id ?: 0
            // Use the actual current subscription status as fromState so history accurately
            // records where the cancellation came from.  For a brand-new user who never
            // completed a purchase, sub is null → fromState defaults to STATE_INITIAL.
            // In that case fromState == toState == STATE_INITIAL, which is meaningless noise
            // (user just opened the screen and backed out), skip the insert entirely.
            val fromStatusId = sub?.status
                ?: SubscriptionStatus.SubscriptionState.STATE_INITIAL.id
            // fromState == STATE_INITIAL means the user had no active subscription and never
            // completed a purchase flow, toState is always STATE_INITIAL in this function.
            // Recording Initial → Initial is pure noise in the payment history UI; skip it.
            if (fromStatusId == SubscriptionStatus.SubscriptionState.STATE_INITIAL.id) {
                Logger.d(LOG_IAB, "$TAG: saveUserCancelledPurchaseHistory: no-op for Initial→Initial (no purchase was ever started)")
                return
            }
            recordHistoryOnly(
                subscriptionId = subscriptionId,
                fromStatusId   = fromStatusId,
                toStatusId     = SubscriptionStatus.SubscriptionState.STATE_INITIAL.id,
                reason         = reason
            )
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: saveUserCancelledPurchaseHistory error: ${e.message}", e)
        }
    }

    suspend fun performSystemCheck(): SystemCheckResult? {
        return try {
            val now = System.currentTimeMillis()

            // Fetch INAPP rows that are locally expired BEFORE the SQL update so we can
            // record history for each one.  markExpiredSubscriptions only touches INAPP rows
            // (productId LIKE '%onetime%' / '%inapp%' / 'test_product') that have a real
            // billingExpiry < now and are not already expired/revoked (status NOT IN (3,10)).
            val activeStatuses = listOf(
                SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
                SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id,
                SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id,
                SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id
            )
            val rowsBeforeUpdate = try {
                subscriptionRepository.getSubscriptionsByStates(activeStatuses)
            } catch (_: Exception) { emptyList() }

            val inAppRowsToExpire = rowsBeforeUpdate.filter { sub ->
                SubscriptionStateMachineV2.isInAppProduct(sub.productId) &&
                sub.billingExpiry > 0L &&
                sub.billingExpiry != Long.MAX_VALUE &&
                sub.billingExpiry < now
            }

            // Run the bulk SQL expiry update
            val expiredCount = subscriptionRepository.markExpiredSubscriptions(now)

            // Record history for every row the SQL just expired so the history table stays
            // consistent with the subscription-status table.
            inAppRowsToExpire.forEach { sub ->
                try {
                    recordHistoryOnly(
                        subscriptionId = sub.id,
                        fromStatusId   = sub.status,
                        toStatusId     = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id,
                        reason         = "INAPP access window expired (billingExpiry=${sub.billingExpiry}, now=$now) [systemCheck]"
                    )
                } catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG: performSystemCheck: failed to record history for id=${sub.id}: ${e.message}", e)
                }
            }

            val sub = subscriptionRepository.getCurrentSubscription()
            val isValid = sub?.let {
                determineStateFromSubscription(it) != SubscriptionStateMachineV2.SubscriptionState.Expired
            } ?: false
            SystemCheckResult(
                expiredSubscriptionsUpdated = expiredCount,
                duplicateTokensFound        = 0,
                invalidSubscriptionsFound   = 0,
                currentSubscriptionValid    = isValid,
                timestamp                   = now
            )
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: performSystemCheck error: ${e.message}", e)
            null
        }
    }

    private fun mapStateToStatusId(state: SubscriptionStateMachineV2.SubscriptionState): Int {
        return when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Active          -> SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled       -> SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            is SubscriptionStateMachineV2.SubscriptionState.Expired         -> SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            is SubscriptionStateMachineV2.SubscriptionState.Revoked         -> SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
            is SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
            is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated -> SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
            is SubscriptionStateMachineV2.SubscriptionState.Error           -> SubscriptionStatus.SubscriptionState.STATE_PURCHASE_FAILED.id
            is SubscriptionStateMachineV2.SubscriptionState.Grace           -> SubscriptionStatus.SubscriptionState.STATE_GRACE.id
            is SubscriptionStateMachineV2.SubscriptionState.OnHold          -> SubscriptionStatus.SubscriptionState.STATE_ON_HOLD.id
            is SubscriptionStateMachineV2.SubscriptionState.Paused          -> SubscriptionStatus.SubscriptionState.STATE_PAUSED.id
            else -> SubscriptionStatus.SubscriptionState.STATE_INITIAL.id
        }
    }

    /**
     * Convert a [PurchaseDetail] to a new [SubscriptionStatus] row (insert path only).
     * Expiry comes from Play (purchaseDetail.expiryTime); never from the ws payload.
     */
    private fun convertPurchaseDetailToSubscriptionStatus(purchaseDetail: PurchaseDetail): SubscriptionStatus {
        val rawExpiry  = purchaseDetail.expiryTime
        val playExpiry = if (rawExpiry > 0L && rawExpiry != Long.MAX_VALUE) rawExpiry else 0L
        return SubscriptionStatus().apply {
            accountId        = purchaseDetail.accountId
            // Store only the sentinel indicator
            // never holds a sensitive plain-text credential.  The real value lives in
            // SecureIdentityStore and is obtained via BillingBackendClient.getDeviceId().
            deviceId         = if (purchaseDetail.deviceId.isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else ""
            purchaseToken    = purchaseDetail.purchaseToken
            orderId          = purchaseDetail.orderId
            productId        = purchaseDetail.productId
            planId           = purchaseDetail.planId.ifBlank { purchaseDetail.productId }
            productTitle     = purchaseDetail.productTitle.ifBlank { purchaseDetail.productId }
            state            = purchaseDetail.state
            purchaseTime     = purchaseDetail.purchaseTimeMillis
            billingExpiry    = playExpiry
            accountExpiry    = playExpiry    // same Play value; ws payload is not used for expiry
            developerPayload = purchaseDetail.payload
            status           = purchaseDetail.status
            lastUpdatedTs    = System.currentTimeMillis()
        }
    }

    private suspend fun getLastTransitionTime(subscriptionId: Int): Long {
        return try {
            historyDAO.getHistoryForSubscription(subscriptionId).firstOrNull()?.timestamp
                ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: getLastTransitionTime error: ${e.message}", e)
            System.currentTimeMillis()
        }
    }

    data class SystemCheckResult(
        val expiredSubscriptionsUpdated: Int,
        val duplicateTokensFound: Int,
        val invalidSubscriptionsFound: Int,
        val currentSubscriptionValid: Boolean,
        val timestamp: Long
    )
}
