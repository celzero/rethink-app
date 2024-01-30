/*
 * Copyright 2023 RethinkDNS and its authors
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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface DoTEndpointDAO {

    @Update fun update(endpoint: DoTEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(endpoint: DoTEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertReplace(endpoint: DoTEndpoint)

    @Delete fun delete(endpoint: DoTEndpoint)

    @Transaction
    @Query("select * from DoTEndpoint order by isSelected desc")
    fun getDoTEndpointLiveData(): PagingSource<Int, DoTEndpoint>

    @Transaction
    @Query(
        "select * from DoTEndpoint where url like :query or name like :query order by isSelected desc"
    )
    fun getDoTEndpointLiveDataByName(query: String): PagingSource<Int, DoTEndpoint>

    @Query("delete from DoTEndpoint where modifiedDataTime < :date") fun deleteOlderData(date: Long)

    @Query("delete from DoTEndpoint") fun clearAllData()

    @Query("delete from DoTEndpoint where id = :id and isCustom = 1") fun deleteDoTEndpoint(id: Int)

    @Query("update DoTEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Transaction
    @Query("select * from DoTEndpoint where isSelected = 1")
    fun getConnectedDoT(): DoTEndpoint?

    @Query("select count(*) from DoTEndpoint") fun getCount(): Int
}
