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
interface CountryConfigDAO {

    @Query("SELECT * FROM CountryConfig")
    suspend fun getAllConfigs(): List<CountryConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: CountryConfig)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<CountryConfig>)

    @Update
    suspend fun update(config: CountryConfig)

    @Delete
    suspend fun delete(config: CountryConfig)

    @Query("UPDATE CountryConfig SET ssidBased = :value, lastModified = :timestamp WHERE `key` = :key")
    suspend fun updateSsidBased(key: String, value: Boolean, timestamp: Long = System.currentTimeMillis())


    @Query("UPDATE CountryConfig SET name = :name, address = :address, city = :city, `key` = :key, load = :load, link = :link, count = :count, isActive = :isActive, lastModified = :timestamp WHERE id = :id")
    suspend fun updateServer(id: String, name: String, address: String, city: String, key: String, load: Int, link: Int, count: Int, isActive: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM CountryConfig")
    suspend fun getCount(): Int


    @Query("SELECT * FROM CountryConfig WHERE id = :id")
    suspend fun getById(id: String): CountryConfig?

    @Query("UPDATE CountryConfig SET ssids = :ssids, lastModified = :timestamp WHERE `key` = :key")
    suspend fun updateSsids(key: String, ssids: String, timestamp: Long = System.currentTimeMillis())


    @Query("UPDATE CountryConfig SET selectionCount = selectionCount + 1 WHERE `key` = :key AND id != 'AUTO'")
    suspend fun incrementSelectionCount(key: String)

    @Query("UPDATE CountryConfig SET isFavourite = :value WHERE cc = :cc")
    suspend fun updateFavouriteByCountryCode(cc: String, value: Boolean)

    @Query("SELECT * FROM CountryConfig WHERE isFavourite = 1 AND isActive = 1")
    suspend fun getFavouriteConfigs(): List<CountryConfig>

    @Query(
        "SELECT cc FROM CountryConfig " +
        "WHERE id != 'AUTO' AND isActive = 1 AND selectionCount > 0 " +
        "GROUP BY cc " +
        "ORDER BY MAX(selectionCount) DESC " +
        "LIMIT :limit"
    )
    suspend fun getTopFrequentCcs(limit: Int): List<String>

    /** Clears the user-selected flag on every non-AUTO server. */
    @Query("UPDATE CountryConfig SET isEnabled = 0 WHERE id != 'AUTO'")
    suspend fun resetAllIsEnabled()

    /** Clears the favourite flag on every server. */
    @Query("UPDATE CountryConfig SET isFavourite = 0")
    suspend fun resetAllIsFavourite()

    /** Resets the selection-count to 0 on every non-AUTO server. */
    @Query("UPDATE CountryConfig SET selectionCount = 0 WHERE id != 'AUTO'")
    suspend fun resetAllSelectionCounts()
}
