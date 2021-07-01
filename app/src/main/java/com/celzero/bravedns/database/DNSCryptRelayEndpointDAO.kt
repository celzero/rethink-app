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

import androidx.paging.DataSource
import androidx.room.*


@Dao
interface DNSCryptRelayEndpointDAO {

    @Update
    fun update(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint)

    @Delete
    fun delete(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint)

    @Transaction
    @Query("select * from DNSCryptRelayEndpoint order by isSelected desc")
    fun getDNSCryptRelayEndpointLiveData(): DataSource.Factory<Int, DNSCryptRelayEndpoint>

    @Transaction
    @Query(
        "select * from DNSCryptRelayEndpoint where dnsCryptRelayURL like :query or dnsCryptRelayName like :query order by isSelected desc")
    fun getDNSCryptRelayEndpointLiveDataByName(
            query: String): DataSource.Factory<Int, DNSCryptRelayEndpoint>

    @Query("delete from DNSCryptRelayEndpoint where modifiedDataTime < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from DNSCryptRelayEndpoint")
    fun clearAllData()

    @Query("delete from DNSCryptRelayEndpoint where dnsCryptRelayURL like :url and isCustom = 1")
    fun deleteDNSCryptRelayEndpoint(url: String)

    @Query("update DNSCryptRelayEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Transaction
    @Query("select * from DNSCryptRelayEndpoint where isSelected = 1")
    fun getConnectedRelays(): List<DNSCryptRelayEndpoint>

    @Transaction
    @Query("select count(*) from DNSCryptRelayEndpoint")
    fun getCount(): Int
}
