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
interface DNSCryptEndpointDAO {

    @Update
    fun update(dnsCryptEndpoint: DNSCryptEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(dnsCryptEndpoint: DNSCryptEndpoint)

    @Delete
    fun delete(dnsCryptEndpoint: DNSCryptEndpoint)

    @Transaction
    @Query("select * from DNSCryptEndpoint order by isSelected desc")
    fun getDNSCryptEndpointLiveData() : DataSource.Factory<Int, DNSCryptEndpoint>

    @Transaction
    @Query("select * from DNSCryptEndpoint where dnsCryptURL like :query or dnsCryptName like :query order by isSelected desc")
    fun getDNSCryptEndpointLiveDataByName(query : String) : DataSource.Factory<Int,DNSCryptEndpoint>

    @Query("delete from DNSCryptEndpoint where modifiedDataTime < :date")
    fun deleteOlderData(date : Long)

    @Query("delete from DNSCryptEndpoint")
    fun clearAllData()

    @Query("delete from DNSCryptEndpoint where dnsCryptURL like :url and isCustom = 1")
    fun deleteDNSCryptEndpoint(url : String)

    @Query("update DNSCryptEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Transaction
    @Query("select * from DNSCryptEndpoint where isSelected = 1")
    fun getConnectedDNSCrypt(): List<DNSCryptEndpoint>

    @Query("select count(*) from DNSCryptEndpoint where isSelected = 1")
    fun getConnectedCount() : Int

    @Query("select count(*) from DNSCryptEndpoint")
    fun getCount() : Int

    @Transaction
    @Query("update DNSCryptEndpoint set isSelected = 1 where id = :liveServerID")
    fun updateConnectionStatus(liveServerID : Int)

    @Transaction
    @Query("update DNSCryptEndpoint set isSelected=0")
    fun updateFailingConnections()
}