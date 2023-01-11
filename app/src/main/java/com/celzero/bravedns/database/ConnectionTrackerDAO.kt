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

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.celzero.bravedns.data.AppConnection

@Dao
interface ConnectionTrackerDAO {

    @Update fun update(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBatch(connTrackerList: List<ConnectionTracker>)

    @Delete fun delete(connectionTracker: ConnectionTracker)

    @Query(
        "select * from ConnectionTracker where (appName like :query or ipAddress like :query or dnsQuery like :query) order by timeStamp desc"
    )
    fun getConnectionTrackerByName(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where  (appName like :query or ipAddress like :query or dnsQuery like :query) and isBlocked = 1 order by timeStamp desc"
    )
    fun getBlockedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select uid as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as dnsQuery from ConnectionTracker where uid = :uid group by ipAddress order by count desc"
    )
    fun getLogsForApp(uid: Int): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as dnsQuery from ConnectionTracker where uid = :uid and ipAddress like :ipAddress group by ipAddress order by count desc"
    )
    fun getLogsForAppFiltered(uid: Int, ipAddress: String): PagingSource<Int, AppConnection>

    @Query("select count(DISTINCT(ipAddress)) from ConnectionTracker where uid = :uid")
    fun getAppConnectionsCount(uid: Int): LiveData<Int>

    @Query(
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 and (appName like :query or ipAddress like :query or dnsQuery like :query) order by timeStamp desc"
    )
    fun getBlockedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query("delete from ConnectionTracker") fun clearAllData()

    @Query("delete from ConnectionTracker where uid = :uid") fun clearLogsByUid(uid: Int)

    @Query("DELETE FROM ConnectionTracker WHERE  timeStamp < :date") fun purgeLogsByDate(date: Long)

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query)  order by timeStamp desc"
    )
    fun getAllowedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query) and blockedByRule in (:filter) order by timeStamp desc"
    )
    fun getAllowedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(uid) as count, 0 as flag, 0 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 0 group by uid order by count desc LIMIT 7"
    )
    fun getAllowedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(uid) as count, 0 as flag, 0 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 0 group by uid order by count desc"
    )
    fun getAllAllowedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(uid) as count, 0 as flag, 1 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 1 group by uid order by count desc LIMIT 7"
    )
    fun getBlockedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(uid) as count, 0 as flag, 1 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 1 group by uid order by count desc"
    )
    fun getAllBlockedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 0 group by ipAddress, flag order by count desc LIMIT 7"
    )
    fun getMostContactedIps(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 0 group by ipAddress, flag order by count desc"
    )
    fun getAllContactedIps(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 1 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 1 group by ipAddress, flag order by count desc LIMIT 7"
    )
    fun getMostBlockedIps(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 1 as blocked, '' as dnsQuery from ConnectionTracker where isBlocked = 1 group by ipAddress, flag order by count desc"
    )
    fun getAllBlockedIps(): PagingSource<Int, AppConnection>

    @RawQuery fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
