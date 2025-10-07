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
package com.rethinkdns.retrixed.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TcpProxyDAO {

    @Update fun update(tcpProxy: TcpProxyEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tcpProxyEndpoints: List<TcpProxyEndpoint>): LongArray

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(tcpProxyEndpoints: TcpProxyEndpoint): Long

    @Query("select * from TcpProxyEndpoint")
    fun getTcpProxiesLiveData(): PagingSource<Int, TcpProxyEndpoint>

    @Query("select * from TcpProxyEndpoint") fun getTcpProxies(): List<TcpProxyEndpoint>

    @Delete fun delete(tcpProxy: TcpProxyEndpoint)

    @Query("delete from TcpProxyEndpoint where id = :id") fun deleteById(id: Int)
}
