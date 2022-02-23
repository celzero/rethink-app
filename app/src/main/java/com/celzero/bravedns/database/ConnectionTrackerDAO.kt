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

import androidx.lifecycle.LiveData
import androidx.paging.DataSource
import androidx.room.*
import com.celzero.bravedns.data.AppConnections


@Dao
interface ConnectionTrackerDAO {

    @Update
    fun update(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBulk(connTrackerList: List<ConnectionTracker>)

    @Delete
    fun delete(connectionTracker: ConnectionTracker)

    @Query("select * from ConnectionTracker order by timeStamp desc")
    fun getConnectionTrackerLiveData(): DataSource.Factory<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where appName like :query or ipAddress like :query order by timeStamp desc")
    fun getConnectionTrackerByName(query: String): DataSource.Factory<Int, ConnectionTracker>

    @Query("select * from ConnectionTracker where isBlocked = 1 order by timeStamp desc")
    fun getConnectionBlockedConnections(): DataSource.Factory<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where (appName like :searchString or ipAddress like :searchString) and isBlocked = 1 order by timeStamp desc")
    fun getBlockedConnections(searchString: String): DataSource.Factory<Int, ConnectionTracker>

    @Query(
        "select ipAddress as ipAddress, count(ipAddress) as count, flag from ConnectionTracker where uid = :uid group by ipAddress, flag order by count desc")
    fun getLogsForApp(uid: Int): List<AppConnections>

    @Query(
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 and (appName like :searchString or ipAddress like :searchString) order by timeStamp desc")
    fun getBlockedConnectionsFiltered(searchString: String,
                                      filter: Set<String>): DataSource.Factory<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where  (appName like :searchString or ipAddress like :searchString) and blockedByRule in (:filter) order by timeStamp desc")
    fun getConnectionsFiltered(searchString: String,
                               filter: List<String>): DataSource.Factory<Int, ConnectionTracker>

    @Query("delete from ConnectionTracker where timeStamp < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from ConnectionTracker")
    fun clearAllData()

    @Query("select * from ConnectionTracker where uid = :uid order by ipAddress, timeStamp desc")
    fun getConnTrackerForAppLiveData(uid: Int): LiveData<List<ConnectionTracker>>

    @Query("select count(*) from ConnectionTracker")
    fun getCountConnectionTracker(): Int

    @Query(
        "delete from ConnectionTracker where id < ((select max(id) from ConnectionTracker) - :count)")
    fun deleteOlderDataCount(count: Int)

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and (appName like :query or ipAddress like :query)  order by timeStamp desc")
    fun getAllowedConnections(query: String): DataSource.Factory<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and (appName like :query or ipAddress like :query) and blockedByRule in (:filter) order by timeStamp desc")
    fun getAllowedConnectionsFiltered(query: String,
                                      filter: Set<String>): DataSource.Factory<Int, ConnectionTracker>

}
