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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DnsProxyEndpointDAO {

    @Update fun update(dnsProxyEndpoint: DnsProxyEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(dnsProxyEndpoint: DnsProxyEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertWithReplace(dnsProxyEndpoint: DnsProxyEndpoint)

    @Delete fun delete(dnsProxyEndpoint: DnsProxyEndpoint)

    @Query("select * from DNSProxyEndpoint order by isSelected desc")
    fun getDnsProxyEndpointLiveData(): PagingSource<Int, DnsProxyEndpoint>

    @Query("select * from DNSProxyEndpoint where proxyName like :query order by isSelected desc")
    fun getDnsProxyEndpointLiveDataByType(query: String): PagingSource<Int, DnsProxyEndpoint>

    @Query("delete from DNSProxyEndpoint where modifiedDataTime < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from DNSProxyEndpoint") fun clearAllData()

    @Query("delete from DNSProxyEndpoint where id = :id and isSelected = 0")
    fun deleteDnsProxyEndpoint(id: Int)

    @Query("update DNSProxyEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Query("select count(*) from DNSProxyEndpoint") fun getCount(): Int

    @Query("select * from DNSProxyEndpoint where isSelected = 1")
    fun getSelectedProxy(): DnsProxyEndpoint?

    @Query("select * from DNSProxyEndpoint where proxyName = 'Orbot' and isCustom = 0 LIMIT 1")
    fun getOrbotDnsEndpoint(): DnsProxyEndpoint?
}
