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

    @Query("SELECT * FROM CountryConfig WHERE cc = :cc")
    suspend fun getConfig(cc: String): CountryConfig?

    @Query("SELECT * FROM CountryConfig WHERE cc = :cc")
    fun getConfigFlow(cc: String): Flow<CountryConfig?>

    @Query("SELECT * FROM CountryConfig")
    suspend fun getAllConfigs(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig")
    fun getAllConfigsFlow(): Flow<List<CountryConfig>>

    @Query("SELECT * FROM CountryConfig WHERE isActive = 1")
    suspend fun getEnabledConfigs(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig WHERE isActive = 1")
    fun getEnabledConfigsFlow(): Flow<List<CountryConfig>>

    @Query("SELECT * FROM CountryConfig WHERE catchAll = 1 AND isActive = 1")
    suspend fun getCatchAllConfigs(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig WHERE catchAll = 1 AND isActive = 1")
    fun getCatchAllConfigsFlow(): Flow<List<CountryConfig>>

    @Query("SELECT * FROM CountryConfig WHERE lockdown = 1 AND isActive = 1")
    suspend fun getLockdownConfigs(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig WHERE lockdown = 1 AND isActive = 1")
    fun getLockdownConfigsFlow(): Flow<List<CountryConfig>>

    @Query("SELECT * FROM CountryConfig WHERE mobileOnly = 1 AND isActive = 1")
    suspend fun getMobileOnlyConfigs(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig WHERE mobileOnly = 1 AND isActive = 1")
    fun getMobileOnlyConfigsFlow(): Flow<List<CountryConfig>>

    @Query("SELECT * FROM CountryConfig WHERE ssidBased = 1 AND isActive = 1")
    suspend fun getSsidBasedConfigs(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig WHERE ssidBased = 1 AND isActive = 1")
    fun getSsidBasedConfigsFlow(): Flow<List<CountryConfig>>

    @Query("SELECT cc FROM CountryConfig WHERE catchAll = 1 AND isActive = 1")
    suspend fun getCatchAllCountryCodes(): List<String>

    @Query("SELECT cc FROM CountryConfig WHERE lockdown = 1 AND isActive = 1")
    suspend fun getLockdownCountryCodes(): List<String>

    @Query("SELECT cc FROM CountryConfig WHERE mobileOnly = 1 AND isActive = 1")
    suspend fun getMobileOnlyCountryCodes(): List<String>

    @Query("SELECT cc FROM CountryConfig WHERE ssidBased = 1 AND isActive = 1")
    suspend fun getSsidBasedCountryCodes(): List<String>

    @Query("SELECT * FROM CountryConfig WHERE isActive = 1 ORDER BY priority DESC, lastModified DESC")
    suspend fun getConfigsByPriority(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig WHERE isActive = 1 ORDER BY priority DESC, lastModified DESC")
    fun getConfigsByPriorityFlow(): Flow<List<CountryConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: CountryConfig)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<CountryConfig>)

    @Update
    suspend fun update(config: CountryConfig)

    @Delete
    suspend fun delete(config: CountryConfig)

    @Query("DELETE FROM CountryConfig WHERE cc = :cc")
    suspend fun deleteByCountryCode(cc: String)

    @Query("DELETE FROM CountryConfig")
    suspend fun deleteAll()

    @Query("UPDATE CountryConfig SET catchAll = :value, lastModified = :timestamp WHERE cc = :cc")
    suspend fun updateCatchAll(cc: String, value: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE CountryConfig SET lockdown = :value, lastModified = :timestamp WHERE cc = :cc")
    suspend fun updateLockdown(cc: String, value: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE CountryConfig SET mobileOnly = :value, lastModified = :timestamp WHERE cc = :cc")
    suspend fun updateMobileOnly(cc: String, value: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE CountryConfig SET ssidBased = :value, lastModified = :timestamp WHERE cc = :cc")
    suspend fun updateSsidBased(cc: String, value: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE CountryConfig SET isActive = :value, lastModified = :timestamp WHERE cc = :cc")
    suspend fun updateEnabled(cc: String, value: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE CountryConfig SET priority = :priority, lastModified = :timestamp WHERE cc = :cc")
    suspend fun updatePriority(cc: String, priority: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE CountryConfig SET catchAll = 0 WHERE catchAll = 1")
    suspend fun clearAllCatchAll()

    @Query("UPDATE CountryConfig SET lockdown = 0 WHERE lockdown = 1")
    suspend fun clearAllLockdown()

    @Query("SELECT COUNT(*) FROM CountryConfig")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM CountryConfig WHERE isActive = 1")
    suspend fun getEnabledCount(): Int

    @Query("SELECT COUNT(*) FROM CountryConfig WHERE catchAll = 1 AND isActive = 1")
    suspend fun getCatchAllCount(): Int

    @Query("SELECT COUNT(*) FROM CountryConfig WHERE lockdown = 1 AND isActive = 1")
    suspend fun getLockdownCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM CountryConfig WHERE cc = :cc)")
    suspend fun exists(cc: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM CountryConfig WHERE cc = :cc AND isActive = 1)")
    suspend fun isEnabled(cc: String): Boolean

    @Query("SELECT * FROM CountryConfig WHERE id = :id")
    suspend fun getById(id: String): CountryConfig?

    @Query("UPDATE CountryConfig SET ssids = :ssids, lastModified = :timestamp WHERE cc = :cc")
    suspend fun updateSsids(cc: String, ssids: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM CountryConfig WHERE ssidBased = 1 AND isActive = 1")
    suspend fun getSsidEnabledConfigs(): List<CountryConfig>

    @Query("SELECT * FROM CountryConfig WHERE ssidBased = 1 AND isActive = 1")
    fun getSsidEnabledConfigsFlow(): Flow<List<CountryConfig>>
}