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
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO for state transition history
 */
@Dao
interface SubscriptionStateHistoryDao {

    @Insert
    suspend fun insert(history: SubscriptionStateHistory): Long

    @Query("SELECT * FROM SubscriptionStateHistory WHERE subscriptionId = :subscriptionId ORDER BY timestamp DESC")
    suspend fun getHistoryForSubscription(subscriptionId: Int): List<SubscriptionStateHistory>

    @Query("SELECT * FROM SubscriptionStateHistory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int = 100): List<SubscriptionStateHistory>

    @Query("DELETE FROM SubscriptionStateHistory WHERE timestamp < :cutoffTime")
    suspend fun deleteOldHistory(cutoffTime: Long): Int

    @Query("SELECT COUNT(*) FROM SubscriptionStateHistory WHERE subscriptionId = :subscriptionId")
    suspend fun getTransitionCount(subscriptionId: Int): Int

    @Query("""
        SELECT fromState, toState, COUNT(*) as count 
        FROM SubscriptionStateHistory 
        GROUP BY fromState, toState 
        ORDER BY count DESC
    """)
    suspend fun getTransitionStatistics(): List<TransitionStatistic>

    @Query("DELETE FROM SubscriptionStateHistory")
    suspend fun clearHistory(): Int
}
