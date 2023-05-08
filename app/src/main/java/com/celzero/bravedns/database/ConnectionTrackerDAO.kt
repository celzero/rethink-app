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
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.util.Constants.Companion.MAX_LOGS

@Dao
interface ConnectionTrackerDAO {

    @Update fun update(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBatch(connTrackerList: List<ConnectionTracker>)

    @Delete fun delete(connectionTracker: ConnectionTracker)

    // replace order by timeStamp desc with order by id desc, as order by timeStamp desc is building
    // the query with temporary index on the table. This is causing the query to be slow.
    // ref: https://stackoverflow.com/a/50776662 (auto covering index)
    // explain QUERY plan SELECT * from ConnectionTracker ORDER by timeStamp desc
    // add LIMIT 35000 (Constants.MAX_LOGS) to the query to avoid the query to be slow
    @Query("select * from ConnectionTracker order by id desc LIMIT $MAX_LOGS")
    fun getConnectionTrackerByName(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where (appName like :query or ipAddress like :query or dnsQuery like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getConnectionTrackerByName(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnections(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where  (appName like :query or ipAddress like :query or dnsQuery like :query) and isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select uid as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName from ConnectionTracker where uid = :uid group by ipAddress order by count desc"
    )
    fun getLogsForApp(uid: Int): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName from ConnectionTracker where uid = :uid and ipAddress like :ipAddress group by ipAddress order by count desc"
    )
    fun getLogsForAppFiltered(uid: Int, ipAddress: String): PagingSource<Int, AppConnection>

    @Query("select count(DISTINCT(ipAddress)) from ConnectionTracker where uid = :uid")
    fun getAppConnectionsCount(uid: Int): LiveData<Int>

    @Query(
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnectionsFiltered(filter: Set<String>): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 and (appName like :query or ipAddress like :query or dnsQuery like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query("delete from ConnectionTracker") fun clearAllData()

    @Query("delete from ConnectionTracker where uid = :uid") fun clearLogsByUid(uid: Int)

    @Query("DELETE FROM ConnectionTracker WHERE  timeStamp < :date") fun purgeLogsByDate(date: Long)

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnections(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and blockedByRule in (:filter) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnectionsFiltered(filter: Set<String>): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query) and blockedByRule in (:filter) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 0 as blocked, appName as appOrDnsName from ConnectionTracker where isBlocked = 0 group by appName order by count desc LIMIT 7"
    )
    fun getAllowedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 0 as blocked, appName as appOrDnsName from ConnectionTracker where isBlocked = 0 group by appName order by count desc"
    )
    fun getAllAllowedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 1 as blocked, appName as appOrDnsName from ConnectionTracker where isBlocked = 1 group by appName order by count desc LIMIT 7"
    )
    fun getBlockedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 1 as blocked, appName as appOrDnsName from ConnectionTracker where isBlocked = 1 group by appName order by count desc"
    )
    fun getAllBlockedAppNetworkActivity(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 0 group by ipAddress order by count desc LIMIT 7"
    )
    fun getMostContactedIps(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 0 group by ipAddress order by count desc"
    )
    fun getAllContactedIps(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 1 group by ipAddress order by count desc LIMIT 7"
    )
    fun getMostBlockedIps(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 1 group by ipAddress order by count desc"
    )
    fun getAllBlockedIps(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 0 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where isBlocked = 0 and dnsQuery != '' group by dnsQuery order by count desc LIMIT 7"
    )
    fun getMostContactedDomains(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 0 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where isBlocked = 0 and dnsQuery != '' group by dnsQuery order by count desc"
    )
    fun getAllContactedDomains(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 1 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where isBlocked = 1 and blockedByRule like '%Rule #2G%' group by dnsQuery order by count desc LIMIT 7"
    )
    fun getMostBlockedDomains(): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 1 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where isBlocked = 1 and blockedByRule like '%Rule #2G%' group by dnsQuery order by count desc"
    )
    fun getAllBlockedDomains(): PagingSource<Int, AppConnection>

    @Query("select count(id) from ConnectionTracker") fun logsCount(): LiveData<Long>
}
