/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.celzero.bravedns.data.AppConnections


@Dao
interface ConnectionTrackerDAO {

    @Update
    fun update(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBatch(connTrackerList: List<ConnectionTracker>)

    @Delete
    fun delete(connectionTracker: ConnectionTracker)

    @Query("select * from ConnectionTracker order by timeStamp desc")
    fun getConnectionTrackerLiveData(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where (appName like :query or ipAddress like :query or dnsQuery like :query) order by timeStamp desc")
    fun getConnectionTrackerByName(query: String): PagingSource<Int, ConnectionTracker>

    @Query("select * from ConnectionTracker where isBlocked = 1 order by timeStamp desc")
    fun getConnectionBlockedConnections(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where  (appName like :query or ipAddress like :query or dnsQuery like :query) and isBlocked = 1 order by timeStamp desc")
    fun getBlockedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, GROUP_CONCAT(DISTINCT dnsQuery) as dnsQuery from ConnectionTracker where uid = :uid group by ipAddress, flag order by count desc")
    fun getLogsForApp(uid: Int): List<AppConnections>?

    @Query(
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 and (appName like :query or ipAddress like :query or dnsQuery like :query) order by timeStamp desc")
    fun getBlockedConnectionsFiltered(query: String,
                                      filter: Set<String>): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where  (appName like :query or ipAddress like :query or dnsQuery like :query) and blockedByRule in (:filter) order by timeStamp desc")
    fun getConnectionsFiltered(query: String,
                               filter: List<String>): PagingSource<Int, ConnectionTracker>

    @Query("delete from ConnectionTracker where timeStamp < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from ConnectionTracker")
    fun clearAllData()

    @Query(
        "delete from ConnectionTracker where id < ((select max(id) from ConnectionTracker) - :count)")
    fun deleteOlderDataCount(count: Int)

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query)  order by timeStamp desc")
    fun getAllowedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query) and blockedByRule in (:filter) order by timeStamp desc")
    fun getAllowedConnectionsFiltered(query: String,
                                      filter: Set<String>): PagingSource<Int, ConnectionTracker>

    @RawQuery
    fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
