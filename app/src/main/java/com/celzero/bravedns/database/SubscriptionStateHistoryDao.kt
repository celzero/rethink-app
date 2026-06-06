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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    /**
     *
     * Applies the noise-filter rules defined at the top of this file:
     *  - same-state rows excluded
     *  - fromState = STATE_UNKNOWN(4) or negative (-1 sentinel) excluded
     *  - toState   = STATE_UNKNOWN(4) or STATE_INITIAL(0) excluded
     * Initial(0) → Active(1) is intentionally shown (fromState=0 is still a valid source).
     */
    @Query("""
        SELECT * FROM SubscriptionStateHistory
        WHERE fromState != toState
          AND fromState NOT IN (-1, 4)
          AND toState   NOT IN (0, 4)
        ORDER BY timestamp DESC
    """)
    fun observeHistoryPaged(): PagingSource<Int, SubscriptionStateHistory>


    /** Total count of meaningful (non-noise) history entries shown to the user. */
    @Query("""
        SELECT COUNT(*) FROM SubscriptionStateHistory
        WHERE fromState != toState
          AND fromState NOT IN (-1, 4)
          AND toState   NOT IN (0, 4)
    """)
    suspend fun getMeaningfulCount(): Int

}
