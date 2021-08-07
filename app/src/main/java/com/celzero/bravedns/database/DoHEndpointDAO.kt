/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.database

import androidx.paging.DataSource
import androidx.room.*


@Dao
interface DoHEndpointDAO {

    @Update
    fun update(doHEndpoint: DoHEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(doHEndpoint: DoHEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReplace(doHEndpoint: DoHEndpoint)

    @Delete
    fun delete(doHEndpoint: DoHEndpoint)

    @Transaction
    @Query("select * from DoHEndpoint order by isSelected desc")
    fun getDoHEndpointLiveData(): DataSource.Factory<Int, DoHEndpoint>

    @Transaction
    @Query(
        "select * from DoHEndpoint where dohURL like :query or dohName like :query order by isSelected desc")
    fun getDoHEndpointLiveDataByName(query: String): DataSource.Factory<Int, DoHEndpoint>

    @Query("delete from DoHEndpoint where modifiedDataTime < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from DoHEndpoint")
    fun clearAllData()

    @Query("delete from DoHEndpoint where id = :id and isCustom = 1")
    fun deleteDoHEndpoint(id: Int)

    @Query("update DoHEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Transaction
    @Query("select * from DoHEndpoint where isSelected = 1")
    fun getConnectedDoH(): DoHEndpoint?

    @Query("update DoHEndpoint set dohURL = :url, isSelected = 1 where id = 5")
    fun updateConnectionURL(url: String)

    @Query("select dohURL from DoHEndpoint where id = :id")
    fun getConnectionURL(id: Int): String

    @Query("select count(*) from DoHEndpoint")
    fun getCount(): Int

    @Query("update DoHEndpoint set isSelected = 1 where id = 4")
    fun updateConnectionDefault()

    @Query("select * from DoHEndpoint where id = 5")
    fun getRethinkDnsEndpoint(): DoHEndpoint
}
