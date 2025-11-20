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
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProxyEndpointDAO {

    @Update fun update(proxyEndpoint: ProxyEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(proxyEndpoint: ProxyEndpoint)

    @Delete fun delete(proxyEndpoint: ProxyEndpoint)

    @Query("select * from ProxyEndpoint where proxyMode = 1 order by isSelected desc")
    fun getDNSProxyEndpointLiveData(): PagingSource<Int, ProxyEndpoint>

    @Query("select * from ProxyEndpoint where proxyName like :query order by isSelected desc")
    fun getDNSProxyEndpointLiveDataByType(query: String): PagingSource<Int, ProxyEndpoint>

    @Query("update ProxyEndpoint set isSelected = 0 where isSelected = 1")
    fun removeConnectionStatus()

    @Query("select count(*) from ProxyEndpoint") fun getCount(): Int

    @Query("select * from ProxyEndpoint where isSelected = 1")
    fun getConnectedProxyLiveData(): LiveData<ProxyEndpoint?>

    @Query("select * from ProxyEndpoint where proxyMode = 0") // 0 for Custom SOCKS5
    suspend fun getCustomSocks5Endpoint(): ProxyEndpoint?

    @Query("select * from ProxyEndpoint where isSelected = 1 and proxyMode = 0")
    suspend fun getConnectedSocks5Proxy(): ProxyEndpoint?

    @Query("select * from ProxyEndpoint where proxyMode = 1") // 1 for Custom HTTP
    suspend fun getHttpProxyDetails(): ProxyEndpoint?

    @Query("select * from ProxyEndpoint where proxyMode = 1 and isSelected = 1")
    suspend fun getConnectedHttpProxy(): ProxyEndpoint?


    @Query("select * from ProxyEndpoint where isSelected = 1 and (proxyMode = 2 or proxyMode = 3)")
    suspend fun getConnectedOrbotProxy(): ProxyEndpoint?

    @Query("select * from ProxyEndpoint where proxyMode = 2") // 2 for Orbot SOCKS5
    suspend fun getOrbotSocks5Endpoint(): ProxyEndpoint?

    @Query("select * from ProxyEndpoint where proxyMode = 3") // 3 for Orbot HTTP
    suspend fun getOrbotHttpEndpoint(): ProxyEndpoint?
}
