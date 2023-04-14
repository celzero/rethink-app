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

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface ProxyEndpointDAO {

    @Update fun update(proxyEndpoint: ProxyEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(proxyEndpoint: ProxyEndpoint)

    @Delete fun delete(proxyEndpoint: ProxyEndpoint)

    @Query("select * from ProxyEndpoint where proxyMode = 1 order by isSelected desc")
    fun getDNSProxyEndpointLiveData(): PagingSource<Int, ProxyEndpoint>

    @Query("select * from ProxyEndpoint where proxyName like :query order by isSelected desc")
    fun getDNSProxyEndpointLiveDataByType(query: String): PagingSource<Int, ProxyEndpoint>

    @Query("delete from ProxyEndpoint where modifiedDataTime < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from ProxyEndpoint") fun clearAllData()

    @Query("delete from ProxyEndpoint where proxyName = 'ORBOT'") fun clearOrbotData()

    @Query("update ProxyEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Query("select count(*) from ProxyEndpoint") fun getCount(): Int

    @Query("select * from ProxyEndpoint where isSelected = 1")
    fun getConnectedProxy(): ProxyEndpoint?

    @Query("select * from ProxyEndpoint where isSelected = 1")
    fun getConnectedProxyLiveData(): LiveData<ProxyEndpoint?>

    @Query("select * from ProxyEndpoint where isSelected = 1 and proxyName = 'ORBOT'")
    fun getConnectedOrbotProxy(): ProxyEndpoint
}
