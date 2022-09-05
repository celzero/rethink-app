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
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DnsLogDAO {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(dnsLog: DnsLog)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBatch(dnsLogs: List<DnsLog>)

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or resolver like :searchString or response like :searchString) order by time desc")
    fun getDnsLogsByName(searchString: String): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or resolver like :searchString or response like :searchString) and isBlocked = 0 order by time desc")
    fun getAllowedDnsLogsByName(searchString: String): PagingSource<Int, DnsLog>

    @Query(
        "select * from DNSLogs where (queryStr like :searchString or resolver like :searchString or response like :searchString) and isBlocked = 1 order by time desc")
    fun getBlockedDnsLogsByName(searchString: String): PagingSource<Int, DnsLog>


    @Query("delete from DNSLogs")
    fun clearAllData()

    @Query("delete from DNSLogs where time < :date")
    fun deleteOlderData(date: Long)

    @Query("delete from DNSLogs where id < ((select max(id) from DNSLogs) - :count)")
    fun deleteOlderDataCount(count: Int)

}
