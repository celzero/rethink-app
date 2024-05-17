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
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.DataUsage
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.util.Constants.Companion.MAX_LOGS

@Dao
interface ConnectionTrackerDAO {

    @Update fun update(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBatch(connTrackerList: List<ConnectionTracker>)

    @Query(
        "update ConnectionTracker set downloadBytes = :downloadBytes, uploadBytes = :uploadBytes, duration = :duration, synack = :synack, message = :message where connId = :connId"
    )
    fun updateSummary(
        connId: String,
        downloadBytes: Long,
        uploadBytes: Long,
        duration: Int,
        synack: Int,
        message: String
    )

    @Query(
        "update ConnectionTracker set downloadBytes = :downloadBytes, uploadBytes = :uploadBytes, duration = :duration, synack = :synack, message = :message, ipAddress = :ipAddress, flag = :flag where connId = :connId"
    )
    fun updateSummary(
        connId: String,
        downloadBytes: Long,
        uploadBytes: Long,
        duration: Int,
        synack: Int,
        message: String,
        ipAddress: String,
        flag: String
    )

    @Delete fun delete(connectionTracker: ConnectionTracker)

    // replace order by timeStamp desc with order by id desc, as order by timeStamp desc is building
    // the query with temporary index on the table. This is causing the query to be slow.
    // ref: https://stackoverflow.com/a/50776662 (auto covering index)
    // explain QUERY plan SELECT * from ConnectionTracker ORDER by timeStamp desc
    // add LIMIT 35000 (Constants.MAX_LOGS) to the query to avoid the query to be slow
    @Query("select * from ConnectionTracker order by id desc LIMIT $MAX_LOGS")
    fun getConnectionTrackerByName(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getConnectionTrackerByName(query: String): PagingSource<Int, ConnectionTracker>

    @Query("select * from ConnectionTracker where isBlocked = 1 order by id desc LIMIT $MAX_LOGS")
    fun getBlockedConnections(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) and isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "SELECT uid, ipAddress, port, COUNT(ipAddress) as count, flag as flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName FROM ConnectionTracker WHERE uid = :uid and timeStamp > :to GROUP BY ipAddress, uid, port ORDER BY count DESC"
    )
    fun getAppIpLogs(uid: Int, to: Long): PagingSource<Int, AppConnection>

    @Query(
        "SELECT uid, ipAddress, port, COUNT(ipAddress) as count, flag as flag, 0 as blocked, '' as appOrDnsName FROM ConnectionTracker WHERE uid = :uid GROUP BY ipAddress, uid, port ORDER BY count DESC LIMIT 3"
    )
    fun getAppIpLogsLimited(uid: Int): PagingSource<Int, AppConnection>

    @Query(
        "SELECT uid, ipAddress, port, COUNT(ipAddress) as count, flag as flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName FROM ConnectionTracker WHERE uid = :uid and timeStamp > :to and ipAddress like :query GROUP BY ipAddress, uid, port ORDER BY count DESC"
    )
    fun getAppIpLogsFiltered(uid: Int, to: Long, query: String): PagingSource<Int, AppConnection>

    @Query(
        "SELECT uid, GROUP_CONCAT(DISTINCT ipAddress) as ipAddress, port, COUNT(dnsQuery) as count, flag as flag, 0 as blocked, dnsQuery as appOrDnsName FROM ConnectionTracker WHERE uid = :uid and timeStamp > :to and dnsQuery != '' GROUP BY dnsQuery ORDER BY count DESC"
    )
    fun getAppDomainLogs(uid: Int, to: Long): PagingSource<Int, AppConnection>

    @Query(
        "SELECT uid, '' as ipAddress, port, COUNT(dnsQuery) as count, flag as flag, 0 as blocked, dnsQuery as appOrDnsName FROM ConnectionTracker WHERE uid = :uid and dnsQuery != '' GROUP BY dnsQuery ORDER BY count DESC LIMIT 3"
    )
    fun getAppDomainLogsLimited(uid: Int): PagingSource<Int, AppConnection>

    @Query(
        "SELECT uid, GROUP_CONCAT(DISTINCT ipAddress) as ipAddress, port, COUNT(dnsQuery) as count, flag as flag, 0 as blocked, dnsQuery as appOrDnsName FROM ConnectionTracker WHERE uid = :uid and timeStamp > :to and dnsQuery != '' and dnsQuery like :query GROUP BY dnsQuery ORDER BY count DESC"
    )
    fun getAppDomainLogsFiltered(
        uid: Int,
        to: Long,
        query: String
    ): PagingSource<Int, AppConnection>

    @Query("select count(DISTINCT(ipAddress)) from ConnectionTracker where uid = :uid")
    fun getAppConnectionsCount(uid: Int): LiveData<Int>

    @Query(
        "select count(DISTINCT(dnsQuery)) from ConnectionTracker where uid = :uid and dnsQuery != ''"
    )
    fun getAppDomainConnectionsCount(uid: Int): LiveData<Int>

    @Query(
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnectionsFiltered(filter: Set<String>): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where protocol = :protocol order by id desc LIMIT $MAX_LOGS"
    )
    fun getProtocolFilteredConnections(protocol: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where protocol = :protocol and blockedByRule in (:filter) order by id desc LIMIT $MAX_LOGS"
    )
    fun getProtocolFilteredConnections(
        protocol: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 and (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query("delete from ConnectionTracker") fun clearAllData()

    @Query("delete from ConnectionTracker where uid = :uid") fun clearLogsByUid(uid: Int)

    @Query("DELETE FROM ConnectionTracker WHERE  timeStamp < :date") fun purgeLogsByDate(date: Long)

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query("select * from ConnectionTracker where isBlocked = 0 order by id desc LIMIT $MAX_LOGS")
    fun getAllowedConnections(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and blockedByRule in (:filter) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnectionsFiltered(filter: Set<String>): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) and blockedByRule in (:filter) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 0 as blocked, appName as appOrDnsName, sum(downloadBytes) as downloadBytes, sum(uploadBytes) as uploadBytes, sum(uploadBytes+downloadBytes) as totalBytes from ConnectionTracker where isBlocked = 0 and timeStamp > :to group by appName order by totalBytes desc LIMIT 7"
    )
    fun getAllowedAppNetworkActivity(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 0 as blocked, appName as appOrDnsName, sum(downloadBytes) as downloadBytes, sum(uploadBytes) as uploadBytes, sum(uploadBytes+downloadBytes) as totalBytes from ConnectionTracker where isBlocked = 0 and timeStamp > :to group by appName order by totalBytes desc"
    )
    fun getAllAllowedAppNetworkActivity(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 1 as blocked, appName as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to group by appName order by count desc LIMIT 7"
    )
    fun getBlockedAppNetworkActivity(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, '' as ipAddress, 0 as port, count(id) as count, 0 as flag, 1 as blocked, appName as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to group by appName order by count desc"
    )
    fun getAllBlockedAppNetworkActivity(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName from ConnectionTracker where  isBlocked = 0 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc LIMIT 7"
    )
    fun getMostContactedIps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 0 and timeStamp > :to and flag not in ('', '--')  group by flag order by count desc LIMIT 7"
    )
    fun getMostContactedCountries(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 0 and timeStamp > :to and flag not in ('', '--')  group by flag order by count desc"
    )
    fun getAllContactedCountries(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to and flag not in ('', '--')  group by flag order by count desc LIMIT 7"
    )
    fun getMostBlockedCountries(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to  and flag not in ('', '--') group by flag order by count desc"
    )
    fun getAllBlockedCountries(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 0 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc"
    )
    fun getAllContactedIps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc LIMIT 7"
    )
    fun getMostBlockedIps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, '' as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc"
    )
    fun getAllBlockedIps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 0 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where  isBlocked = 0 and timeStamp > :to and dnsQuery != '' group by dnsQuery order by count desc LIMIT 7"
    )
    fun getMostContactedDomains(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 0 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where  isBlocked = 0 and timeStamp > :to and dnsQuery != '' group by dnsQuery order by count desc"
    )
    fun getAllContactedDomains(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 1 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to and blockedByRule like 'Rule #2G%' and dnsQuery != '' group by dnsQuery order by count desc LIMIT 7"
    )
    fun getMostBlockedDomains(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 1 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where isBlocked = 1 and timeStamp > :to and blockedByRule like 'Rule #2G%' and dnsQuery != '' group by dnsQuery order by count desc"
    )
    fun getAllBlockedDomains(to: Long): PagingSource<Int, AppConnection>

    @Query("select count(id) from ConnectionTracker") fun logsCount(): LiveData<Long>

    @Query(
        "select timeStamp from ConnectionTracker where id = (select min(id) from ConnectionTracker)"
    )
    fun getLeastLoggedTime(): Long

    @Query(
        "SELECT uid, SUM(uploadBytes) AS uploadBytes, SUM(downloadBytes) AS downloadBytes FROM ConnectionTracker where timeStamp >= :from and timeStamp <= :to GROUP BY uid"
    )
    fun getDataUsage(from: Long, to: Long): List<DataUsage>

    // blocked by rule #2, #2D (ip block, ip block universal)
    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, appName as appOrDnsName from ConnectionTracker where blockedByRule in ('Rule #2', 'Rule #2D') and  timeStamp > :from and timeStamp < :to group by ipAddress, appName order by count desc LIMIT 5"
    )
    fun getBlockedIpLogList(from: Long, to: Long): LiveData<List<AppConnection>>

    // blocked by rule #2E, #2G, #2H (domain block, dns blocked by upstream, domain block universal)
    @Query(
        "select 0 as uid, '' as ipAddress, port as port, count(id) as count, flag, 1 as blocked, dnsQuery as appOrDnsName from ConnectionTracker where blockedByRule in ('Rule #2E', 'Rule #2G', 'Rule #2H') and timeStamp > :from and timeStamp < :to group by dnsQuery order by count desc LIMIT 5"
    )
    fun getBlockedDomainsList(from: Long, to: Long): LiveData<List<AppConnection>>

    // TODO: add blocked by rule #1, #1B, #1D, #1E, #1G (app block, new app block, unmetered block,
    // metered block, isolate)
    @Query(
        "select uid as uid, '' as ipAddress, port as port, count(id) as count, flag, 1 as blocked, appName as appOrDnsName from ConnectionTracker where timeStamp > :from and timeStamp < :to group by appName order by count desc LIMIT 5"
    )
    fun getBlockedAppLogList(from: Long, to: Long): LiveData<List<AppConnection>>

    @Query(
        "select sum(downloadBytes) as totalDownload, sum(uploadBytes) as totalUpload, count(id) as connectionsCount, ict.meteredDataUsage as meteredDataUsage from ConnectionTracker as ct join (select sum(downloadBytes + uploadBytes) as meteredDataUsage from ConnectionTracker where connType like :meteredTxt and timeStamp > :to) as ict where timeStamp > :to"
    )
    fun getTotalUsages(to: Long, meteredTxt: String): DataUsageSummary
}
