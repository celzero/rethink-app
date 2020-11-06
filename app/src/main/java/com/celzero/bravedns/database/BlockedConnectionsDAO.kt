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
interface BlockedConnectionsDAO {

    @Update
    fun update(blockedConnections: BlockedConnections)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(blockedConnections: BlockedConnections)

    @Delete
    fun delete(blockedConnections: BlockedConnections)

    @Transaction
    @Query("select uid, * from BlockedConnections order by uid")
    fun getBlockedConnections() : List<BlockedConnections>

    @Transaction
    @Query ("select uid,* from BlockedConnections where uid = :uid and isActive = 1")
    fun getBlockedConnectionsByUID(uid: Int ) :List< BlockedConnections>

    @Query ("delete from BlockedConnections where uid = :uid")
    fun clearFirewallRules(uid : Int)

    @Transaction
    @Query ("select * from BlockedConnections where uid = :uid or uid = -1000")
    fun getAllBlockedConnectionsForUID(uid : Int) :List< BlockedConnections>

    @Transaction
    @Query("select * from BlockedConnections where isActive = 1 and uid = -1000 order by modifiedDateTime desc")
    fun getUnivBlockedConnectionsLiveData(): DataSource.Factory<Int, BlockedConnections>

    @Transaction
    @Query("select * from BlockedConnections where ipAddress like :query and uid = -1000 and  isActive = 1 order by modifiedDateTime desc")
    fun getUnivBlockedConnectionsByIP(query: String): DataSource.Factory<Int, BlockedConnections>

    @Query("delete from BlockedConnections where ipAddress = :ipAddress and uid = -1000")
    fun deleteIPRulesUniversal(ipAddress: String)

    @Transaction
    @Query("delete from BlockedConnections where ipAddress = :ipAddress and uid = :uid")
    fun deleteIPRulesForUID(uid: Int, ipAddress: String)

    @Query("delete from BlockedConnections where uid = -1000")
    fun deleteAllIPRulesUniversal()

    @Query("select count(*) from BlockedConnections where uid = -1000")
    fun getBlockedConnectionsCount() : Int
}