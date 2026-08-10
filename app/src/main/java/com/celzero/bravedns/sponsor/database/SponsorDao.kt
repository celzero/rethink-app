/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.sponsor.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SponsorDao {

    @Query("SELECT * FROM Sponsor ORDER BY id DESC LIMIT 1")
    fun observeLatestSponsor(): Flow<SponsorEntity?>

    @Query("SELECT * FROM Sponsor ORDER BY id DESC LIMIT 1")
    suspend fun getLatestSponsor(): SponsorEntity?

    @Query("SELECT COUNT(*) FROM Sponsor")
    suspend fun getSponsorCount(): Int

    @Query("SELECT * FROM Sponsor ORDER BY id DESC")
    fun observeAllSponsors(): Flow<List<SponsorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sponsor: SponsorEntity): Long

    @Query("UPDATE Sponsor SET consumed = :consumed WHERE id = :id")
    suspend fun updateConsumed(id: Long, consumed: Boolean)

    @Query("UPDATE Sponsor SET contribution_count = contribution_count + 1, last_contribution_time = :timestamp WHERE id = :id")
    suspend fun incrementContribution(id: Long, timestamp: Long)

    @Query("DELETE FROM Sponsor")
    suspend fun deleteAll()
}
