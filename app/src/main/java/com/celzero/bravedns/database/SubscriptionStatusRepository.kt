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
package com.rethinkdns.retrixed.database

import Logger
import Logger.LOG_IAB
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SubscriptionStatusRepository(private val subscriptionStatusDAO: SubscriptionStatusDao) {


    private val mutex = Mutex()

    companion object {
        private const val TAG = "SubscriptionStatusRepoEnhanced"
    }

    /**
     * Insert or update subscription status with proper state validation
     */
    suspend fun upsert(subscriptionStatus: SubscriptionStatus): Long {
        return mutex.withLock {
            try {
                Logger.d(
                    LOG_IAB,
                    "$TAG Upserting subscription: ${subscriptionStatus.productId}, status: ${subscriptionStatus.status}"
                )

                // Validate subscription before saving
                validateSubscriptionStatus(subscriptionStatus)

                // Update timestamp
                subscriptionStatus.lastUpdatedTs = System.currentTimeMillis()

                val result = if (subscriptionStatus.id == 0) {
                    // Insert new subscription
                    subscriptionStatusDAO.insert(subscriptionStatus)
                } else {
                    // Update existing subscription
                    val updateCount = subscriptionStatusDAO.update(subscriptionStatus)
                    if (updateCount > 0) subscriptionStatus.id.toLong() else -1L
                }

                Logger.d(LOG_IAB, "$TAG Upsert completed with result: $result")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error during upsert: ${e.message}", e)
                throw e
            }
        }
    }

    suspend fun update(subscriptionStatus: SubscriptionStatus): Int {
        return mutex.withLock {
            try {
                Logger.d(LOG_IAB, "$TAG Updating subscription: ${subscriptionStatus.productId}")
                val result = subscriptionStatusDAO.update(subscriptionStatus)
                Logger.d(LOG_IAB, "$TAG Update result: $result")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error updating subscription: ${e.message}", e)
                throw e
            }
        }
    }

    suspend fun insert(subscriptionStatus: SubscriptionStatus): Long {
        return mutex.withLock {
            try {
                Logger.d(LOG_IAB, "$TAG Inserting subscription: ${subscriptionStatus.productId}")
                val result = subscriptionStatusDAO.insert(subscriptionStatus)
                Logger.d(LOG_IAB, "$TAG Insert result: $result")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error inserting subscription: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Get current active subscription
     */
    suspend fun getCurrentSubscription(): SubscriptionStatus? {
        return try {
            Logger.d(LOG_IAB, "$TAG Getting current subscription")
            val subscription = subscriptionStatusDAO.getCurrentSubscription()
            Logger.d(LOG_IAB, "$TAG Current subscription: ${subscription?.productId}")
            subscription
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting current subscription: ${e.message}", e)
            null
        }
    }

    /**
     * Get subscription by purchase token
     */
    suspend fun getByPurchaseToken(token: String): SubscriptionStatus? {
        return try {
            subscriptionStatusDAO.getSubscriptionByToken(token)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting subscription by token: ${e.message}", e)
            null
        }
    }

    /**
     * Get subscription by product ID
     */
    suspend fun getByProductId(productId: String): SubscriptionStatus? {
        return try {
            subscriptionStatusDAO.getSubscriptionByProductId(productId)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting subscription by product ID: ${e.message}", e)
            null
        }
    }

    /**
     * Get subscriptions by account ID
     */
    suspend fun getByAccountId(accountId: String): List<SubscriptionStatus> {
        return try {
            subscriptionStatusDAO.getSubscriptionsByAccountId(accountId)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting subscriptions by account ID: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Update subscription status
     */
    suspend fun updateStatus(id: Int, newStatus: Int, timestamp: Long): Int {
        return mutex.withLock {
            try {
                Logger.d(LOG_IAB, "$TAG Updating subscription status: ID=$id, status=$newStatus")
                val result = subscriptionStatusDAO.updateStatus(id, newStatus, timestamp)
                Logger.d(LOG_IAB, "$TAG Status update result: $result")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error updating status: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Update billing expiry
     */
    suspend fun updateBillingExpiry(id: Int, expiryTime: Long, timestamp: Long): Int {
        return mutex.withLock {
            try {
                subscriptionStatusDAO.updateBillingExpiry(id, expiryTime, timestamp)
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error updating billing expiry: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Update account expiry
     */
    suspend fun updateAccountExpiry(id: Int, expiryTime: Long, timestamp: Long): Int {
        return mutex.withLock {
            try {
                subscriptionStatusDAO.updateAccountExpiry(id, expiryTime, timestamp)
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error updating account expiry: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Mark expired subscriptions
     */
    suspend fun markExpiredSubscriptions(currentTime: Long): Int {
        return mutex.withLock {
            try {
                Logger.d(LOG_IAB, "$TAG Marking expired subscriptions at time: $currentTime")
                val result = subscriptionStatusDAO.markExpiredSubscriptions(currentTime)
                Logger.d(LOG_IAB, "$TAG Marked $result subscriptions as expired")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error marking expired subscriptions: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Delete expired subscriptions older than cutoff time
     */
    suspend fun deleteExpiredOlderThan(cutoffTime: Long): Int {
        return mutex.withLock {
            try {
                Logger.d(LOG_IAB, "$TAG Deleting expired subscriptions older than: $cutoffTime")
                val result = subscriptionStatusDAO.deleteExpiredOlderThan(cutoffTime)
                Logger.d(LOG_IAB, "$TAG Deleted $result expired subscriptions")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error deleting expired subscriptions: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Get all valid subscriptions
     */
    suspend fun getValidSubscriptions(): List<SubscriptionStatus> {
        return try {
            subscriptionStatusDAO.getValidSubscriptions()
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting valid subscriptions: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get all invalid subscriptions
     */
    suspend fun getInvalidSubscriptions(): List<SubscriptionStatus> {
        return try {
            subscriptionStatusDAO.getInvalidSubscriptions()
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting invalid subscriptions: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Find duplicate tokens
     */
    suspend fun findDuplicateTokens(): List<TokenCount> {
        return try {
            subscriptionStatusDAO.findDuplicateTokens()
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error finding duplicate tokens: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Remove duplicate tokens (keep the most recent)
     */
    suspend fun removeDuplicateTokens(): Int {
        return mutex.withLock {
            try {
                Logger.d(LOG_IAB, "$TAG Removing duplicate tokens")
                val result = subscriptionStatusDAO.removeDuplicateTokens()
                Logger.d(LOG_IAB, "$TAG Removed $result duplicate subscriptions")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error removing duplicates: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Get status distribution for analytics
     */
    suspend fun getStatusDistribution(): List<StatusCount> {
        return try {
            subscriptionStatusDAO.getStatusDistribution()
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting status distribution: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get subscriptions expiring in time range
     */
    suspend fun getSubscriptionsExpiringInRange(
        startTime: Long,
        endTime: Long
    ): List<SubscriptionStatus> {
        return try {
            subscriptionStatusDAO.getSubscriptionsExpiringInRange(startTime, endTime)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG Error getting subscriptions expiring in range: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Observe current subscription with Flow
     */
    fun observeCurrentSubscription(): Flow<SubscriptionStatus?> {
        return subscriptionStatusDAO.observeCurrentSubscription()
    }

    /**
     * Observe all subscriptions with Flow
     */
    fun observeAllSubscriptions(): Flow<List<SubscriptionStatus>> {
        return subscriptionStatusDAO.observeAllSubscriptions()
    }

    /**
     * Delete subscription
     */
    suspend fun delete(subscriptionStatus: SubscriptionStatus): Int {
        return mutex.withLock {
            try {
                Logger.d(LOG_IAB, "$TAG Deleting subscription: ${subscriptionStatus.productId}")
                val result = subscriptionStatusDAO.delete(subscriptionStatus)
                Logger.d(LOG_IAB, "$TAG Delete result: $result")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error deleting subscription: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Delete all subscriptions
     */
    suspend fun deleteAll(): Int {
        return mutex.withLock {
            try {
                Logger.w(LOG_IAB, "$TAG Deleting all subscriptions")
                val result = subscriptionStatusDAO.deleteAllSubscriptions()
                Logger.w(LOG_IAB, "$TAG Deleted all subscriptions: $result")
                result
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG Error deleting all subscriptions: ${e.message}", e)
                throw e
            }
        }
    }

    // Private helper methods

    private fun validateSubscriptionStatus(subscriptionStatus: SubscriptionStatus) {
        require(subscriptionStatus.purchaseToken.isNotEmpty()) {
            "Purchase token cannot be empty"
        }
        require(subscriptionStatus.productId.isNotEmpty()) {
            "Product ID cannot be empty"
        }
        require(subscriptionStatus.accountId.isNotEmpty()) {
            "Account ID cannot be empty"
        }

        // Validate status is within valid range
        val validStatuses = SubscriptionStatus.SubscriptionState.entries.map { it.id }
        require(subscriptionStatus.status in validStatuses) {
            "Invalid subscription status: ${subscriptionStatus.status}"
        }

        // Validate expiry times
        /*if (subscriptionStatus.billingExpiry > 0 && subscriptionStatus.accountExpiry > 0) {
            require(subscriptionStatus.accountExpiry >= subscriptionStatus.billingExpiry) {
                "Account expiry must be >= billing expiry, but got " +
                        "accountExpiry=${subscriptionStatus.accountExpiry}, billingExpiry=${subscriptionStatus.billingExpiry}"
            }
        }*/

        Logger.d(LOG_IAB, "$TAG Subscription validation passed for: ${subscriptionStatus.productId}")
    }

}