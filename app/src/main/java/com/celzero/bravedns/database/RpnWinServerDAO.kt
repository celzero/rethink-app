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

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for RPN WIN server database operations.
 * Provides CRUD operations and queries for server data management.
 */
@Dao
interface RpnWinServerDAO {

    /**
     * Get all servers as a Flow for reactive updates
     */
    @Query("SELECT * FROM RpnWinServers ORDER BY countryCode ASC, city ASC")
    fun getAllServersFlow(): Flow<List<RpnWinServer>>

    /**
     * Get all servers (one-time read)
     */
    @Query("SELECT * FROM RpnWinServers ORDER BY countryCode ASC, city ASC")
    suspend fun getAllServers(): List<RpnWinServer>

    /**
     * Get all active servers
     */
    @Query("SELECT * FROM RpnWinServers WHERE isActive = 1 ORDER BY countryCode ASC, city ASC")
    suspend fun getActiveServers(): List<RpnWinServer>

    /**
     * Get server by ID
     */
    @Query("SELECT * FROM RpnWinServers WHERE id = :serverId")
    suspend fun getServerById(serverId: String): RpnWinServer?

    /**
     * Get servers by country code
     */
    @Query("SELECT * FROM RpnWinServers WHERE countryCode = :cc ORDER BY city ASC")
    suspend fun getServersByCountryCode(cc: String): List<RpnWinServer>

    /**
     * Get count of all servers
     */
    @Query("SELECT COUNT(*) FROM RpnWinServers")
    suspend fun getServerCount(): Int

    /**
     * Insert a single server (replace on conflict)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: RpnWinServer)

    /**
     * Insert multiple servers (replace on conflict)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<RpnWinServer>)

    /**
     * Update a server
     */
    @Update
    suspend fun updateServer(server: RpnWinServer)

    /**
     * Delete a server by ID
     */
    @Query("DELETE FROM RpnWinServers WHERE id = :serverId")
    suspend fun deleteServerById(serverId: String)

    /**
     * Delete servers by list of IDs
     */
    @Query("DELETE FROM RpnWinServers WHERE id IN (:serverIds)")
    suspend fun deleteServersByIds(serverIds: List<String>)

    /**
     * Delete all servers
     */
    @Query("DELETE FROM RpnWinServers")
    suspend fun deleteAllServers()

    /**
     * Mark all servers as inactive
     */
    @Query("UPDATE RpnWinServers SET isActive = 0")
    suspend fun markAllServersInactive()

    /**
     * Update server load and link (latency) metrics
     */
    @Query("UPDATE RpnWinServers SET load = :load, link = :link, lastUpdated = :timestamp WHERE id = :serverId")
    suspend fun updateServerMetrics(serverId: String, load: Int, link: Int, timestamp: Long)

    /**
     * Get servers that haven't been updated since the given timestamp
     */
    @Query("SELECT * FROM RpnWinServers WHERE lastUpdated < :timestamp")
    suspend fun getStaleServers(timestamp: Long): List<RpnWinServer>

    /**
     * Delete servers that haven't been updated since the given timestamp
     */
    @Query("DELETE FROM RpnWinServers WHERE lastUpdated < :timestamp")
    suspend fun deleteStaleServers(timestamp: Long)
}

