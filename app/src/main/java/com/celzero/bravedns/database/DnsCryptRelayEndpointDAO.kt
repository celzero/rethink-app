/*
Copyright 2020 RethinkDNS developers

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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface DnsCryptRelayEndpointDAO {

    @Update fun update(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint)

    @Delete fun delete(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint)

    @Transaction
    @Query("select * from DNSCryptRelayEndpoint order by isSelected desc")
    fun getDnsCryptRelayEndpointLiveData(): PagingSource<Int, DnsCryptRelayEndpoint>

    @Transaction
    @Query(
        "select * from DNSCryptRelayEndpoint where dnsCryptRelayURL like :query or dnsCryptRelayName like :query order by isSelected desc"
    )
    fun getDnsCryptRelayEndpointLiveDataByName(
        query: String
    ): PagingSource<Int, DnsCryptRelayEndpoint>

    @Query("delete from DNSCryptRelayEndpoint where modifiedDataTime < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from DNSCryptRelayEndpoint") fun clearAllData()

    @Query("delete from DNSCryptRelayEndpoint where id = :id and isCustom = 1")
    fun deleteDnsCryptRelayEndpoint(id: Int)

    @Query("update DNSCryptRelayEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Query(
        "update DNSCryptRelayEndpoint set isSelected = 0 where isSelected = 1 and dnsCryptRelayURL = :stamp"
    )
    fun unselectRelay(stamp: String)

    @Transaction
    @Query("select * from DNSCryptRelayEndpoint where isSelected = 1")
    fun getConnectedRelays(): List<DnsCryptRelayEndpoint>

    @Transaction @Query("select count(*) from DNSCryptRelayEndpoint") fun getCount(): Int
}
