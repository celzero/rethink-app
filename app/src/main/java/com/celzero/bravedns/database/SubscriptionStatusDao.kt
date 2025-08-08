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
package com.celzero.bravedns.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionStatus): Long

    @Query("SELECT * FROM SubscriptionStatus WHERE accountId = :accountId LIMIT 1")
    suspend fun getByAccountId(accountId: String): SubscriptionStatus?

    @Query("SELECT * FROM SubscriptionStatus order by billingExpiry desc LIMIT 1")
    suspend fun getCurrent(): SubscriptionStatus?

    @Query("DELETE FROM SubscriptionStatus")
    suspend fun deleteAll()

    @Query("Update SubscriptionStatus SET state = :state WHERE accountId = :accountId AND purchaseToken = :purchaseToken")
    suspend fun updateSubscriptionState(accountId: String, purchaseToken: String, state: Int): Int


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(subscriptionStatus: SubscriptionStatus): Long

    @Update
    suspend fun update(subscriptionStatus: SubscriptionStatus): Int

    @Delete
    suspend fun delete(subscriptionStatus: SubscriptionStatus): Int

    @Query("DELETE FROM SubscriptionStatus WHERE id = :id")
    suspend fun deleteById(id: Int): Int

    @Query("SELECT * FROM SubscriptionStatus WHERE id = :id")
    suspend fun getSubscriptionById(id: Int): SubscriptionStatus?

    @Query("SELECT * FROM SubscriptionStatus WHERE purchaseToken = :token LIMIT 1")
    suspend fun getSubscriptionByToken(token: String): SubscriptionStatus?

    @Query("SELECT * FROM SubscriptionStatus WHERE productId = :productId ORDER BY lastUpdatedTs DESC LIMIT 1")
    suspend fun getSubscriptionByProductId(productId: String): SubscriptionStatus?

    @Query("SELECT * FROM SubscriptionStatus WHERE accountId = :accountId ORDER BY lastUpdatedTs DESC")
    suspend fun getSubscriptionsByAccountId(accountId: String): List<SubscriptionStatus>

    @Query("SELECT * FROM SubscriptionStatus ORDER BY lastUpdatedTs DESC LIMIT 1")
    suspend fun getCurrentSubscription(): SubscriptionStatus?

    @Query("SELECT * FROM SubscriptionStatus ORDER BY lastUpdatedTs DESC")
    suspend fun getAllSubscriptions(): List<SubscriptionStatus>

    @Query("SELECT * FROM SubscriptionStatus WHERE status = :status ORDER BY lastUpdatedTs DESC")
    suspend fun getSubscriptionsByStatus(status: Int): List<SubscriptionStatus>

    @Query("SELECT * FROM SubscriptionStatus WHERE status IN (:statuses) ORDER BY lastUpdatedTs DESC")
    suspend fun getSubscriptionsByStates(statuses: List<Int>): List<SubscriptionStatus>

    @Query("SELECT COUNT(*) FROM SubscriptionStatus")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM SubscriptionStatus WHERE status = :status")
    suspend fun getCountByStatus(status: Int): Int

    @Query("SELECT COUNT(*) FROM SubscriptionStatus WHERE status IN (:statuses)")
    suspend fun getCountByStates(statuses: List<Int>): Int

    @Query("UPDATE SubscriptionStatus SET status = :newStatus, lastUpdatedTs = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: Int, newStatus: Int, timestamp: Long): Int

    @Query("UPDATE SubscriptionStatus SET status = :newStatus, lastUpdatedTs = :timestamp WHERE purchaseToken = :token")
    suspend fun updateStatusByToken(token: String, newStatus: Int, timestamp: Long): Int

    @Query("UPDATE SubscriptionStatus SET status = :newStatus, lastUpdatedTs = :timestamp WHERE accountId = :accountId")
    suspend fun updateStatusByAccountId(accountId: String, newStatus: Int, timestamp: Long): Int

    @Query("UPDATE SubscriptionStatus SET billingExpiry = :expiryTime, lastUpdatedTs = :timestamp WHERE id = :id")
    suspend fun updateBillingExpiry(id: Int, expiryTime: Long, timestamp: Long): Int

    @Query("UPDATE SubscriptionStatus SET accountExpiry = :expiryTime, lastUpdatedTs = :timestamp WHERE id = :id")
    suspend fun updateAccountExpiry(id: Int, expiryTime: Long, timestamp: Long): Int

    @Query(
        """
        UPDATE SubscriptionStatus 
        SET status = 3, lastUpdatedTs = :currentTime 
        WHERE billingExpiry > 0 AND billingExpiry < :currentTime 
        AND status NOT IN (3, 10)
    """
    )
    suspend fun markExpiredSubscriptions(currentTime: Long): Int

    @Query(
        """
        DELETE FROM SubscriptionStatus 
        WHERE status = 3 
        AND lastUpdatedTs < :cutoffTime
    """
    )
    suspend fun deleteExpiredOlderThan(cutoffTime: Long): Int

    @Query("DELETE FROM SubscriptionStatus WHERE status = :status")
    suspend fun deleteByStatus(status: Int): Int

    @Query("DELETE FROM SubscriptionStatus WHERE lastUpdatedTs < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long): Int

    // Reactive queries with Flow
    @Query("SELECT * FROM SubscriptionStatus ORDER BY lastUpdatedTs DESC LIMIT 1")
    fun observeCurrentSubscription(): Flow<SubscriptionStatus?>

    @Query("SELECT * FROM SubscriptionStatus ORDER BY lastUpdatedTs DESC")
    fun observeAllSubscriptions(): Flow<List<SubscriptionStatus>>

    @Query("SELECT * FROM SubscriptionStatus WHERE status = :status ORDER BY lastUpdatedTs DESC")
    fun observeSubscriptionsByStatus(status: Int): Flow<List<SubscriptionStatus>>

    @Query("SELECT * FROM SubscriptionStatus WHERE accountId = :accountId ORDER BY lastUpdatedTs DESC")
    fun observeSubscriptionsByAccount(accountId: String): Flow<List<SubscriptionStatus>>

    // Advanced queries for analytics
    @Query("""
        SELECT status, COUNT(*) as count 
        FROM SubscriptionStatus 
        GROUP BY status 
        ORDER BY count DESC
    """)
    suspend fun getStatusDistribution(): List<StatusCount>

    @Query("""
        SELECT productId, COUNT(*) as count 
        FROM SubscriptionStatus 
        GROUP BY productId 
        ORDER BY count DESC
    """)
    suspend fun getProductDistribution(): List<ProductCount>

    @Query(
        """
        SELECT * FROM SubscriptionStatus 
        WHERE lastUpdatedTs BETWEEN :startTime AND :endTime 
        ORDER BY lastUpdatedTs DESC
    """
    )
    suspend fun getSubscriptionsInTimeRange(startTime: Long, endTime: Long): List<SubscriptionStatus>

    @Query("""
        SELECT * FROM SubscriptionStatus 
        WHERE billingExpiry > 0 AND billingExpiry BETWEEN :startTime AND :endTime 
        ORDER BY billingExpiry ASC
    """)
    suspend fun getSubscriptionsExpiringInRange(startTime: Long, endTime: Long): List<SubscriptionStatus>

    @Query("""
        SELECT * FROM SubscriptionStatus 
        WHERE status = 4
        AND billingExpiry < :currentTime
        ORDER BY billingExpiry ASC
    """)
    suspend fun getExpiredGracePeriodSubscriptions(currentTime: Long): List<SubscriptionStatus>

    // Batch operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subscriptions: List<SubscriptionStatus>): List<Long>

    @Update
    suspend fun updateAll(subscriptions: List<SubscriptionStatus>): Int

    @Delete
    suspend fun deleteAll(subscriptions: List<SubscriptionStatus>): Int

    @Query("DELETE FROM SubscriptionStatus")
    suspend fun deleteAllSubscriptions(): Int

    // Complex state transition queries
    @Query(
        """
        UPDATE SubscriptionStatus 
        SET status = :newStatus, lastUpdatedTs = :timestamp 
        WHERE status = :oldStatus
    """
    )
    suspend fun bulkStatusTransition(oldStatus: Int, newStatus: Int, timestamp: Long): Int

    @Query(
        """
        SELECT * FROM SubscriptionStatus 
        WHERE status IN (1, 4, 5, 6)
        ORDER BY lastUpdatedTs DESC
    """
    )
    suspend fun getValidSubscriptions(): List<SubscriptionStatus>

    @Query(
        """
        SELECT * FROM SubscriptionStatus 
        WHERE status IN (3, 10)
        ORDER BY lastUpdatedTs DESC
    """
    )
    suspend fun getInvalidSubscriptions(): List<SubscriptionStatus>

    // Duplicate detection
    @Query("""
        SELECT purchaseToken, COUNT(*) as count 
        FROM SubscriptionStatus 
        GROUP BY purchaseToken 
        HAVING count > 1
    """)
    suspend fun findDuplicateTokens(): List<TokenCount>

    @Query("""
        DELETE FROM SubscriptionStatus 
        WHERE id NOT IN (
            SELECT MIN(id) 
            FROM SubscriptionStatus 
            GROUP BY purchaseToken
        )
    """)
    suspend fun removeDuplicateTokens(): Int
}
