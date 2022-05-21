/*
Copyright 2022 RethinkDNS and its authors

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
interface RethinkDnsEndpointDao {

    @Update
    fun update(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReplace(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Delete
    fun delete(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Query("update RethinkDnsEndpoint set isActive = 0 where isActive = 1 and uid = -2000")
    fun removeConnectionStatus()

    @Query("update RethinkDnsEndpoint set isActive = 0 where uid = :uid")
    fun removeAppDns(uid: Int)

    @Query("delete from RethinkDnsEndpoint where name = :name and url =:url and uid = :uid and isCustom = 1 and uid = -2000")
    fun deleteDoHEndpoint(name: String, url: String, uid: Int)

    @Transaction
    @Query("select * from RethinkDnsEndpoint where uid = -2000 order by isActive desc")
    fun getRethinkEndpoints(): DataSource.Factory<Int, RethinkDnsEndpoint>

    @Transaction
    @Query("select * from RethinkDnsEndpoint order by isActive desc")
    fun getAllRethinkEndpoints(): DataSource.Factory<Int, RethinkDnsEndpoint>

    @Query("select isActive from RethinkDnsEndpoint where uid = :uid")
    fun isAppDnsEnabled(uid: Int): Boolean?

    @Transaction
    @Query(
        "select * from RethinkDnsEndpoint where name like :query or url like :query and uid = -2000 order by isActive desc")
    fun getRethinkEndpointsByName(query: String): DataSource.Factory<Int, RethinkDnsEndpoint>

    @Query("select * from RethinkDnsEndpoint where isActive = 1 and uid = -2000 LIMIT 1")
    fun getConnectedEndpoint(): RethinkDnsEndpoint?

    @Query("update RethinkDnsEndpoint set isActive = 1 where uid = -2000 and name = 'Default'")
    fun updateConnectionDefault()

}
