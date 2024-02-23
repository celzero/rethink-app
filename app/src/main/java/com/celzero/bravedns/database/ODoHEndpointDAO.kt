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
interface ODoHEndpointDAO {

    @Update fun update(endpoint: ODoHEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(endpoint: ODoHEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertReplace(endpoint: ODoHEndpoint)

    @Delete fun delete(endpoint: ODoHEndpoint)

    @Transaction
    @Query("select * from ODoHEndpoint order by isSelected desc")
    fun getODoHEndpointLiveData(): PagingSource<Int, ODoHEndpoint>

    @Transaction
    @Query(
        "select * from ODoHEndpoint where resolver like :query or name like :query order by isSelected desc"
    )
    fun getODoHEndpointLiveDataByName(query: String): PagingSource<Int, ODoHEndpoint>

    @Query("delete from ODoHEndpoint where modifiedDataTime < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from ODoHEndpoint") fun clearAllData()

    @Query("delete from ODoHEndpoint where id = :id and isCustom = 1")
    fun deleteODoHEndpoint(id: Int)

    @Query("update ODoHEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Transaction
    @Query("select * from ODoHEndpoint where isSelected = 1")
    fun getConnectedODoH(): ODoHEndpoint?

    @Query("select count(*) from ODoHEndpoint") fun getCount(): Int
}
