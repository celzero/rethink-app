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
package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SmartDnsEndpointDAO {

    @Query("SELECT * FROM SmartDnsEndpoint ORDER BY dnsMode ASC")
    suspend fun getSmartDnsEndpoints(): List<SmartDnsEndpoint>

    @Query("SELECT * FROM SmartDnsEndpoint ORDER BY dnsMode ASC")
    fun getSmartDnsEndpointsLiveData(): LiveData<List<SmartDnsEndpoint>>

    @Query("SELECT * FROM SmartDnsEndpoint WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedEndpoint(): SmartDnsEndpoint?

    @Query("SELECT COUNT(*) FROM SmartDnsEndpoint")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(endpoint: SmartDnsEndpoint)

    @Query("UPDATE SmartDnsEndpoint SET isSelected = 0 WHERE isSelected = 1")
    suspend fun removeConnectionStatus()

    @Query("UPDATE SmartDnsEndpoint SET isSelected = 1 WHERE id = :id")
    suspend fun setConnectionStatus(id: Int)
}
