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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.celzero.bravedns.util.Constants.Companion.MAX_LOGS

@Dao
interface DnsLogDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(dnsLog: DnsLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertBatch(dnsLogs: List<DnsLog>)

    // replace order by timeStamp desc with order by id desc, as order by timeStamp desc is building
    // the query with temporary index on the table. This is causing the query to be slow.
    // ref: https://stackoverflow.com/a/50776662 (auto covering index)
    // LIMIT 35000 to avoid the query to be slow
    @Query("select * from DNSLogs order by id desc LIMIT $MAX_LOGS")
    fun getAllDnsLogs(): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or responseIps like :searchString or appName like :searchString) order by id desc LIMIT $MAX_LOGS"
    )
    fun getDnsLogsByName(searchString: String): PagingSource<Int, DnsLog>

    @Query("select * from DNSLogs where proxyId like :wgDnsId order by id desc LIMIT $MAX_LOGS")
    fun getDnsLogsForWireGuard(wgDnsId: String): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where isBlocked = 0 and blockLists = '' order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedDnsLogs(): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or responseIps like :searchString or flag = :searchString or appName like :searchString) and isBlocked = 0 and blockLists = '' order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedDnsLogsByName(searchString: String): PagingSource<Int, DnsLog>

    @Query("select * from DNSLogs where isBlocked = 1 order by id desc LIMIT $MAX_LOGS")
    fun getBlockedDnsLogs(): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or responseIps like :searchString or flag = :searchString or appName like :searchString) and isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedDnsLogsByName(searchString: String): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where isBlocked = 0 and blockLists != '' order by id desc LIMIT $MAX_LOGS"
    )
    fun getMaybeBlockedDnsLogs(): PagingSource<Int, DnsLog>

    @Query("select * from DNSLogs where typeName not in (:types) order by id desc LIMIT $MAX_LOGS")
    fun getUnknownRecordDnsLogs(types: Set<String>): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or responseIps like :searchString or flag = :searchString or appName like :searchString) and typeName not in (:types) order by id desc LIMIT $MAX_LOGS"
    )
    fun getUnknownRecordDnsLogsByName(
        searchString: String,
        types: Set<String>
    ): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or responseIps like :searchString or flag = :searchString or appName like :searchString) and isBlocked = 0 and blockLists != '' order by id desc LIMIT $MAX_LOGS"
    )
    fun getMaybeBlockedDnsLogsByName(searchString: String): PagingSource<Int, DnsLog>

    @Query("delete from DNSLogs") fun clearAllData()

    @Query("delete from DNSLogs where time < :date") fun purgeDnsLogsByDate(date: Long)

    @Query("select count(id) from DNSLogs") fun logsCount(): LiveData<Long>

    @Query("select time from DNSLogs where id = (select min(id) from DNSLogs)")
    fun getLeastLoggedTime(): Long

    @Query(
        "SELECT uid AS uid, MAX(time) AS lastBlocked, COUNT(*) AS count FROM DNSLogs WHERE isBlocked = 1 AND time > :time GROUP BY uid ORDER BY lastBlocked DESC LIMIT 10"
    )
    suspend fun getRecentlyBlockedDnsApps(time: Long): List<BlockedDnsAppResult>

    @Query(
        "SELECT uid AS uid, MAX(time) AS lastBlocked, COUNT(*) AS count FROM DNSLogs WHERE isBlocked = 1 AND time > :time GROUP BY uid ORDER BY lastBlocked DESC"
    )
    fun getRecentlyBlockedDnsAppsPaged(time: Long): PagingSource<Int, BlockedDnsAppResult>
}

data class BlockedDnsAppResult(
    val uid: Int,
    val lastBlocked: Long,
    val count: Int
)
