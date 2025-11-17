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
import com.android.billingclient.api.BillingResult
import com.celzero.bravedns.database.SubscriptionStateHistory
import com.celzero.bravedns.database.SubscriptionStateHistoryDao
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.celzero.bravedns.iab.PurchaseDetail

/**
 * Service for synchronizing state machine states with database
 * Handles state persistence, recovery, and transition tracking
 */
class StateMachineDatabaseSyncService : KoinComponent {

    private val subscriptionRepository by inject<SubscriptionStatusRepository>()
    private val historyDAO: SubscriptionStateHistoryDao by inject()

    companion object {
        private const val TAG = "StateMachineDBSync"
    }

    /**
     * Database state information for state machine initialization
     */
    data class DatabaseStateInfo(
        val currentSubscription: SubscriptionStatus,
        val recommendedState: SubscriptionStateMachineV2.SubscriptionState,
        val lastTransitionTime: Long
    )

    /**
     * Load current subscription state from database and determine recommended state
     */
    suspend fun loadStateFromDatabase(): DatabaseStateInfo? {
        return try {
            Logger.d(LOG_IAB, "$TAG Loading state from database")

            val currentSubscription = subscriptionRepository.getCurrentSubscription()
                ?: return null

            val recommendedState = determineStateFromSubscription(currentSubscription)
            val lastTransitionTime = getLastTransitionTime(currentSubscription.id)

            Logger.d(
                LOG_IAB, "$TAG Loaded state: ${recommendedState.name} for subscription: ${currentSubscription.productId}"
            )

            DatabaseStateInfo(
                currentSubscription = currentSubscription,
                recommendedState = recommendedState,
                lastTransitionTime = lastTransitionTime
            )
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG err loading state from database: ${e.message}", e)
            null
        }
    }

    /**
     * Save state transition to database
     */
    suspend fun saveStateTransition(
        fromState: SubscriptionStateMachineV2.SubscriptionState,
        toState: SubscriptionStateMachineV2.SubscriptionState,
        subscriptionData: SubscriptionStateMachineV2.SubscriptionData?,
        reason: String? = null
    ) {
        try {
            Logger.d(LOG_IAB, "$TAG Saving state transition: ${fromState.name} -> ${toState.name}")

            subscriptionData?.let { data ->
                // Update the subscription status in database
                val updatedStatus = data.subscriptionStatus.copy(
                    status = mapStateToStatusId(toState),
                    lastUpdatedTs = System.currentTimeMillis()
                )

                val updateResult = subscriptionRepository.update(updatedStatus)

                if (updateResult > 0) {
                    // Record state transition history
                    val history = SubscriptionStateHistory(
                        subscriptionId = updatedStatus.id,
                        fromState = mapStateToStatusId(fromState),
                        toState = mapStateToStatusId(toState),
                        timestamp = System.currentTimeMillis(),
                        reason = reason
                    )

                    val historyId = historyDAO.insert(history)
                    Logger.d(LOG_IAB, "$TAG State transition saved successfully, historyId: $historyId")
                } else {
                    Logger.e(LOG_IAB, "$TAG Failed to update subscription status")
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error saving state transition: ${e.message}", e)
        }
    }

    /**
     * Save purchase detail to database - ensures single subscription record
     */
    suspend fun savePurchaseDetail(purchaseDetail: PurchaseDetail): Long {
        return try {
            Logger.d(LOG_IAB, "$TAG Saving purchase detail: ${purchaseDetail.productId}")

            // Check if subscription already exists by purchase token
            val existingSubscription = subscriptionRepository.getByPurchaseToken(purchaseDetail.purchaseToken)

            if (existingSubscription != null) {
                Logger.d(LOG_IAB, "$TAG Subscription already exists, updating: ${existingSubscription.id}")
                // in case of missing expiry time in payload, use the one from purchase detail
                val accountExpiry = RpnProxyManager.getExpiryFromPayload(purchaseDetail.payload) ?:
                    purchaseDetail.expiryTime
                // update existing subscription
                val updatedStatus = existingSubscription.copy(
                    status = purchaseDetail.status,
                    lastUpdatedTs = System.currentTimeMillis(),
                    billingExpiry = purchaseDetail.expiryTime,
                    accountExpiry = accountExpiry,
                    productTitle = purchaseDetail.productTitle,
                    planId = purchaseDetail.planId,
                    payload = purchaseDetail.payload
                )

                subscriptionRepository.update(updatedStatus)
                existingSubscription.id.toLong()
            } else {
                // create new subscription only if it doesn't exist
                val subscriptionStatus = convertPurchaseDetailToSubscriptionStatus(purchaseDetail)
                val id = subscriptionRepository.insert(subscriptionStatus)

                Logger.d(LOG_IAB, "$TAG New subscription created with ID: $id")
                id
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error saving purchase detail: ${e.message}", e)
            -1
        }
    }

    /**
     * Update subscription expiry information
     */
    suspend fun updateSubscriptionExpiry(
        subscriptionId: Int,
        billingExpiry: Long,
        accountExpiry: Long
    ) {
        try {
            Logger.d(LOG_IAB, "$TAG Updating subscription expiry for ID: $subscriptionId")

            val currentTime = System.currentTimeMillis()
            subscriptionRepository.updateBillingExpiry(subscriptionId, billingExpiry, currentTime)
            subscriptionRepository.updateAccountExpiry(subscriptionId, accountExpiry, currentTime)

            Logger.d(LOG_IAB, "$TAG Subscription expiry updated successfully")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error updating subscription expiry: ${e.message}", e)
        }
    }

    /**
     * Mark subscription as revoked
     */
    suspend fun markSubscriptionRevoked(subscriptionId: Int) {
        try {
            Logger.w(LOG_IAB, "$TAG Marking subscription as revoked: $subscriptionId")

            val currentTime = System.currentTimeMillis()
            val updateResult = subscriptionRepository.updateStatus(
                subscriptionId,
                SubscriptionStatus.SubscriptionState.STATE_REVOKED.id,
                currentTime
            )

            if (updateResult > 0) {
                // Record revocation in history
                val history = SubscriptionStateHistory(
                    subscriptionId = subscriptionId,
                    fromState = -1, // Unknown previous state
                    toState = SubscriptionStatus.SubscriptionState.STATE_REVOKED.id,
                    timestamp = currentTime,
                    reason = "Subscription revoked by system"
                )
                historyDAO.insert(history)

                Logger.w(LOG_IAB, "$TAG Subscription marked as revoked with history")
            } else {
                Logger.e(LOG_IAB, "$TAG Failed to mark subscription as revoked")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error marking subscription as revoked: ${e.message}", e)
        }
    }

    suspend fun savePurchaseFailureHistory(error: String, billingResult: BillingResult?) {
        try {
            Logger.e(LOG_IAB, "$TAG Saving purchase failure history: $error")
            // update for current subscription
            val currentSubscription = subscriptionRepository.getCurrentSubscription()
            if (currentSubscription == null) {
                Logger.w(LOG_IAB, "No current subscription found to save purchase failure history")
                return
            }
            // Create history entry for purchase failure
            val currentTime = System.currentTimeMillis()
            val history = SubscriptionStateHistory(
                subscriptionId = currentSubscription.id, // No specific subscription
                fromState = currentSubscription.state, // Unknown previous state
                toState = SubscriptionStatus.SubscriptionState.STATE_PURCHASE_FAILED.id, // Failure state
                timestamp = currentTime,
                reason = "Purchase failed: $error, BillingResult: ${billingResult?.responseCode ?: "Unknown"}"
            )

            historyDAO.insert(history)
            Logger.d(LOG_IAB, "$TAG Purchase failure history saved successfully")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error saving purchase failure history: ${e.message}", e)
        }
    }

    /**
     * Perform system check
     */
    suspend fun performSystemCheck(): SystemCheckResult? {
        return try {
            Logger.d(TAG, "Performing system check")

            val currentTime = System.currentTimeMillis()

            // Update expired subscriptions
            val expiredCount = subscriptionRepository.markExpiredSubscriptions(currentTime)

            // Get current subscription status
            val currentSubscription = subscriptionRepository.getCurrentSubscription()

            val result = SystemCheckResult(
                expiredSubscriptionsUpdated = expiredCount,
                duplicateTokensFound = 0,
                invalidSubscriptionsFound = 0,
                currentSubscriptionValid = currentSubscription?.let {
                    determineStateFromSubscription(it) != SubscriptionStateMachineV2.SubscriptionState.Expired
                } ?: false,
                timestamp = currentTime
            )

            Logger.d(LOG_IAB, "$TAG System check completed: $result")
            result
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error during system check: ${e.message}", e)
            null
        }
    }

    // Helper methods
    private fun determineStateFromSubscription(subscription: SubscriptionStatus): SubscriptionStateMachineV2.SubscriptionState {
        val currentTime = System.currentTimeMillis()

        Logger.d(LOG_IAB, "$TAG Determining state for subscription: ${subscription.productId}, status: ${subscription.status}, billingExpiry: ${subscription.billingExpiry}, accountExpiry: ${subscription.accountExpiry}, currentTime: $currentTime")

        return when (SubscriptionStatus.SubscriptionState.fromId(subscription.status)) {
            SubscriptionStatus.SubscriptionState.STATE_ACTIVE -> {
                // For active subscriptions, check expiry more carefully
                when {
                    // If billingExpiry is 0 or in the future, subscription is active
                    subscription.billingExpiry <= 0 || currentTime <= subscription.billingExpiry -> {
                        Logger.d(LOG_IAB, "$TAG Subscription is active (not expired)")
                        SubscriptionStateMachineV2.SubscriptionState.Active
                    }
                    // If billing expired but account expiry is in future, subscription is cancelled but in grace period
                    subscription.accountExpiry > currentTime -> {
                        Logger.d(LOG_IAB, "$TAG Subscription is cancelled but in grace period")
                        SubscriptionStateMachineV2.SubscriptionState.Cancelled
                    }
                    // Both billing and account expired
                    else -> {
                        Logger.d(LOG_IAB, "$TAG Subscription is expired")
                        SubscriptionStateMachineV2.SubscriptionState.Expired
                    }
                }
            }
            SubscriptionStatus.SubscriptionState.STATE_CANCELLED -> {
                // Check if cancelled subscription is still valid
                if (subscription.billingExpiry > 0 && currentTime <= subscription.billingExpiry) {
                    Logger.d(LOG_IAB, "$TAG Cancelled subscription still valid")
                    SubscriptionStateMachineV2.SubscriptionState.Cancelled
                } else {
                    Logger.d(LOG_IAB, "$TAG Cancelled subscription expired")
                    SubscriptionStateMachineV2.SubscriptionState.Expired
                }
            }
            SubscriptionStatus.SubscriptionState.STATE_EXPIRED -> SubscriptionStateMachineV2.SubscriptionState.Expired
            SubscriptionStatus.SubscriptionState.STATE_REVOKED -> SubscriptionStateMachineV2.SubscriptionState.Revoked
            SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING -> SubscriptionStateMachineV2.SubscriptionState.PurchasePending
            SubscriptionStatus.SubscriptionState.STATE_PURCHASED -> SubscriptionStateMachineV2.SubscriptionState.PurchasePending
            else -> {
                Logger.w(LOG_IAB, "$TAG Unknown subscription state: ${subscription.status}, defaulting to Initial")
                SubscriptionStateMachineV2.SubscriptionState.Initial
            }
        }
    }

    private fun mapStateToStatusId(state: SubscriptionStateMachineV2.SubscriptionState): Int {
        return when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Active -> SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            is SubscriptionStateMachineV2.SubscriptionState.Revoked -> SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
            is SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
            is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated -> SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
            is SubscriptionStateMachineV2.SubscriptionState.Error -> SubscriptionStatus.SubscriptionState.STATE_PURCHASE_FAILED.id
            else -> SubscriptionStatus.SubscriptionState.STATE_UNKNOWN.id
        }
    }

    private fun convertPurchaseDetailToSubscriptionStatus(purchaseDetail: PurchaseDetail): SubscriptionStatus {
        val accExpiry = RpnProxyManager.getExpiryFromPayload(purchaseDetail.payload) ?:
            purchaseDetail.expiryTime
        return SubscriptionStatus().apply {
            accountId = purchaseDetail.accountId
            purchaseToken = purchaseDetail.purchaseToken
            productId = purchaseDetail.productId
            planId = purchaseDetail.planId
            productTitle = purchaseDetail.productTitle
            state = purchaseDetail.state
            purchaseTime = purchaseDetail.purchaseTimeMillis
            billingExpiry = purchaseDetail.expiryTime
            accountExpiry = accExpiry
            developerPayload = purchaseDetail.payload
            status = purchaseDetail.status
            lastUpdatedTs = System.currentTimeMillis()
        }
    }

    private suspend fun getLastTransitionTime(subscriptionId: Int): Long {
        return try {
            val history = historyDAO.getHistoryForSubscription(subscriptionId)
            history.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG err in last transition time: ${e.message}", e)
            System.currentTimeMillis()
        }
    }

    private fun SubscriptionStatus.copy(
        status: Int = this.status,
        lastUpdatedTs: Long = this.lastUpdatedTs,
        billingExpiry: Long = this.billingExpiry,
        accountExpiry: Long = this.accountExpiry,
        productTitle: String = this.productTitle,
        planId: String = this.planId,
        payload: String = this.developerPayload
    ): SubscriptionStatus {
        return SubscriptionStatus().apply {
            id = this@copy.id
            accountId = this@copy.accountId
            purchaseToken = this@copy.purchaseToken
            productId = this@copy.productId
            this.planId = planId
            sessionToken = this@copy.sessionToken
            this.productTitle = productTitle
            state = this@copy.state
            purchaseTime = this@copy.purchaseTime
            this.accountExpiry = accountExpiry
            this.billingExpiry = billingExpiry
            this.developerPayload = payload
            this.status = status
            this.lastUpdatedTs = lastUpdatedTs
        }
    }

    /**
     * Result of system check operation
     */
    data class SystemCheckResult(
        val expiredSubscriptionsUpdated: Int,
        val duplicateTokensFound: Int,
        val invalidSubscriptionsFound: Int,
        val currentSubscriptionValid: Boolean,
        val timestamp: Long
    )
}
