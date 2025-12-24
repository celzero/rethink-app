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
        "update ConnectionTracker set proxyDetails = :pid, rpid = :rpid, downloadBytes = :downloadBytes, uploadBytes = :uploadBytes, duration = :duration, synack = :synack, message = :message where connId = :connId"
    )
    fun updateSummary(
        connId: String,
        pid: String,
        rpid: String, // relay proxy id
        downloadBytes: Long,
        uploadBytes: Long,
        duration: Int,
        synack: Long,
        message: String
    )

    @Query(
        "update ConnectionTracker set proxyDetails = :pid, rpid = :rpid, downloadBytes = :downloadBytes, uploadBytes = :uploadBytes, duration = :duration, synack = :synack, message = :message, ipAddress = :ipAddress, flag = :flag where connId = :connId"
    )
    fun updateSummary(
        connId: String,
        pid: String,
        rpid: String,
        downloadBytes: Long,
        uploadBytes: Long,
        duration: Int,
        synack: Long,
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
        "select * from ConnectionTracker where (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query or proxyDetails like :query or connId like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getConnectionTrackerByName(query: String): PagingSource<Int, ConnectionTracker>

    @Query("select * from ConnectionTracker where isBlocked = 1 order by id desc LIMIT $MAX_LOGS")
    fun getBlockedConnections(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query or proxyDetails like :query or connId like :query) and isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query(
        "SELECT uid, ipAddress, port, COUNT(ipAddress) as count, flag as flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName, SUM(downloadBytes) as downloadBytes, SUM(uploadBytes) as uploadBytes, SUM(downloadBytes + uploadBytes) as totalBytes FROM ConnectionTracker WHERE uid = :uid and timeStamp > :to GROUP BY uid, ipAddress, port ORDER BY count DESC"
    )
    fun getAppIpLogs(uid: Int, to: Long): PagingSource<Int, AppConnection>

    @Query(
        "SELECT uid, ipAddress, port, COUNT(ipAddress) as count, flag as flag, 0 as blocked, '' as appOrDnsName, SUM(downloadBytes) as downloadBytes, SUM(uploadBytes) as uploadBytes, SUM(downloadBytes + uploadBytes) as totalBytes FROM ConnectionTracker WHERE uid = :uid and timeStamp > :to GROUP BY uid, ipAddress, port ORDER BY count DESC LIMIT 3"
    )
    fun getAppIpLogsLimited(uid: Int, to: Long): PagingSource<Int, AppConnection>

    @Query(
        "SELECT uid, ipAddress, port, COUNT(ipAddress) as count, flag as flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName, SUM(downloadBytes) as downloadBytes, SUM(uploadBytes) as uploadBytes, SUM(downloadBytes + uploadBytes) as totalBytes FROM ConnectionTracker WHERE uid = :uid and timeStamp > :to and ipAddress like :query GROUP BY  uid, ipAddress, port ORDER BY count DESC"
    )
    fun getAppIpLogsFiltered(uid: Int, to: Long, query: String): PagingSource<Int, AppConnection>

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
        "select * from ConnectionTracker where blockedByRule in (:filter) and isBlocked = 1 and (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query or proxyDetails like :query or connId like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query("delete from ConnectionTracker") fun clearAllData()

    @Query("delete from ConnectionTracker where uid = :uid") fun clearLogsByUid(uid: Int)

    @Query("delete from ConnectionTracker where blockedByRule = :rule") fun clearLogsByRule(rule: String)

    @Query("delete from ConnectionTracker where uid = :uid and timeStamp > :time")
    fun clearLogsByTime(uid: Int, time: Long)

    @Query("DELETE FROM ConnectionTracker WHERE  timeStamp < :date") fun purgeLogsByDate(date: Long)

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query or proxyDetails like :query or connId like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnections(query: String): PagingSource<Int, ConnectionTracker>

    @Query("select * from ConnectionTracker where isBlocked = 0 order by id desc LIMIT $MAX_LOGS")
    fun getAllowedConnections(): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and blockedByRule in (:filter) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnectionsFiltered(filter: Set<String>): PagingSource<Int, ConnectionTracker>

    @Query(
        "select * from ConnectionTracker where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query or proxyDetails like :query or connId like :query) and blockedByRule in (:filter) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnectionsFiltered(
        query: String,
        filter: Set<String>
    ): PagingSource<Int, ConnectionTracker>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName, SUM(downloadBytes) as downloadBytes, SUM(uploadBytes) as uploadBytes, SUM(downloadBytes + uploadBytes) as totalBytes from ConnectionTracker where isBlocked = 0 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc"
    )
    fun getAllContactedIps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 0 as blocked, '' as appOrDnsName, SUM(downloadBytes) as downloadBytes, SUM(uploadBytes) as uploadBytes, SUM(downloadBytes + uploadBytes) as totalBytes from ConnectionTracker where  isBlocked = 0 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc LIMIT 7"
    )
    fun getMostContactedIps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, '' as appOrDnsName, SUM(downloadBytes) as downloadBytes, SUM(uploadBytes) as uploadBytes, SUM(downloadBytes + uploadBytes) as totalBytes from ConnectionTracker where isBlocked = 1 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc LIMIT 7"
    )
    fun getMostBlockedIps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select 0 as uid, ipAddress as ipAddress, port as port, count(id) as count, flag, 1 as blocked, '' as appOrDnsName, SUM(downloadBytes) as downloadBytes, SUM(uploadBytes) as uploadBytes, SUM(downloadBytes + uploadBytes) as totalBytes from ConnectionTracker where isBlocked = 1 and timeStamp > :to and ipAddress != '' group by ipAddress order by count desc"
    )
    fun getAllBlockedIps(to: Long): PagingSource<Int, AppConnection>

    @Query("select count(id) from ConnectionTracker") fun logsCount(): LiveData<Long>

    @Query(
        "select timeStamp from ConnectionTracker where id = (select min(id) from ConnectionTracker)"
    )
    fun getLeastLoggedTime(): Long

    @Query("select connId from ConnectionTracker where uid = :uid and ipAddress = :ipAddress and timeStamp >= :to and message = '' and uploadBytes = 0 and downloadBytes = 0 and synack = 0")
    fun getConnIdByUidIpAddress(uid: Int, ipAddress: String, to: Long): List<String>

    @Query(
        "SELECT uid, SUM(uploadBytes) AS uploadBytes, SUM(downloadBytes) AS downloadBytes FROM ConnectionTracker where timeStamp >= :from and timeStamp <= :to GROUP BY uid"
    )
    fun getDataUsage(from: Long, to: Long): List<DataUsage>

    @Query(
        "select sum(downloadBytes) as totalDownload, sum(uploadBytes) as totalUpload, count(id) as connectionsCount, (select sum(downloadBytes + uploadBytes) from ConnectionTracker where connType = :meteredTxt and timeStamp > :to) as meteredDataUsage from ConnectionTracker as ct where ct.timeStamp > :to"
    )
    fun getTotalUsages(to: Long, meteredTxt: String): DataUsageSummary

    @Query("select * from ConnectionTracker where blockedByRule in ('Rule #1B', 'Rule #1F', 'Rule #3', 'Rule #4', 'Rule #5', 'Rule #6', 'Rule #7', 'Http block', 'Universal Lockdown')")
    fun getBlockedUniversalRulesCount(): List<ConnectionTracker>

    @Query("SELECT uid AS uid, '' AS ipAddress, 0 AS port, COUNT(id) AS count, flag AS flag, 0 AS blocked, appName AS appOrDnsName, SUM(downloadBytes) AS downloadBytes, SUM(uploadBytes) AS uploadBytes, SUM(uploadBytes + downloadBytes) AS totalBytes FROM ConnectionTracker WHERE proxyDetails like :wgId AND timeStamp > :to GROUP BY appName ORDER BY totalBytes DESC")
    fun getWgAppNetworkActivity(wgId: String, to: Long): PagingSource<Int, AppConnection>

    @Query(
        "select sum(downloadBytes) as totalDownload, sum(uploadBytes) as totalUpload, count(id) as connectionsCount, ict.meteredDataUsage as meteredDataUsage from ConnectionTracker as ct join (select sum(downloadBytes + uploadBytes) as meteredDataUsage from ConnectionTracker where connType like :meteredTxt and timeStamp > :to) as ict where timeStamp > :to and proxyDetails = :wgId"
    )
    fun getTotalUsagesByWgId(to: Long, meteredTxt: String, wgId: String): DataUsageSummary

    @Query("update ConnectionTracker set message = :reason, duration = 0 where connId in (:connIds) and message = '' and uploadBytes = 0 and downloadBytes = 0 and synack = 0")
    fun closeConnections(connIds: List<String>, reason: String)

    @Query("update ConnectionTracker set message = :reason, duration = 0 where uid in (:uids) and message = '' and uploadBytes = 0 and downloadBytes = 0 and synack = 0")
    fun closeConnectionForUids( uids: List<Int>, reason: String)

    @Query(
        "SELECT uid, MAX(timeStamp) AS lastBlocked, COUNT(*) AS count FROM ConnectionTracker WHERE isBlocked = 1 and timeStamp > :time GROUP BY uid ORDER BY lastBlocked DESC LIMIT 10"
    )
    suspend fun getRecentlyBlockedApps(time: Long): List<BlockedAppResult>

    @Query(
        "SELECT uid, MAX(timeStamp) AS lastBlocked, COUNT(*) AS count FROM ConnectionTracker WHERE isBlocked = 1 and timeStamp > :time GROUP BY uid ORDER BY lastBlocked DESC"
    )
    fun getRecentlyBlockedAppsPaged(time: Long): PagingSource<Int, BlockedAppResult>

    @Query(
        "SELECT * FROM ConnectionTracker WHERE isBlocked = 1 AND timeStamp >= :since ORDER BY timeStamp DESC"
    )
    suspend fun getBlockedConnectionsSince(since: Long): List<ConnectionTracker>

    @Query(
        "SELECT COUNT(*) FROM ConnectionTracker WHERE isBlocked = 1 AND timeStamp >= :since"
    )
    fun getBlockedConnectionsCountLiveData(since: Long): LiveData<Int>
}

data class BlockedAppResult(
    val uid: Int,
    val lastBlocked: Long,
    val count: Int
)
