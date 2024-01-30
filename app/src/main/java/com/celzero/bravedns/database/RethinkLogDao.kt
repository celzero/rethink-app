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
import com.celzero.bravedns.util.Constants.Companion.MAX_LOGS

@Dao
interface RethinkLogDao {

    @Update fun update(log: RethinkLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(log: RethinkLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBatch(connTrackerList: List<RethinkLog>)

    @Query(
        "update RethinkLog set downloadBytes = :downloadBytes, uploadBytes = :uploadBytes, duration = :duration, synack = :synack, message = :message where connId = :connId"
    )
    fun updateSummary(
        connId: String,
        downloadBytes: Long,
        uploadBytes: Long,
        duration: Int,
        synack: Int,
        message: String
    )

    @Delete fun delete(log: RethinkLog)

    // replace order by timeStamp desc with order by id desc, as order by timeStamp desc is building
    // the query with temporary index on the table. This is causing the query to be slow.
    // ref: https://stackoverflow.com/a/50776662 (auto covering index)
    // explain QUERY plan SELECT * from RethinkLog ORDER by timeStamp desc
    // add LIMIT 35000 (Constants.MAX_LOGS) to the query to avoid the query to be slow
    @Query("select * from RethinkLog order by id desc LIMIT $MAX_LOGS")
    fun getRethinkLogByName(): PagingSource<Int, RethinkLog>

    @Query(
        "select * from RethinkLog where (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getRethinkLogByName(query: String): PagingSource<Int, RethinkLog>

    @Query("select * from RethinkLog where isBlocked = 1 order by id desc LIMIT $MAX_LOGS")
    fun getBlockedConnections(): PagingSource<Int, RethinkLog>

    @Query(
        "select * from RethinkLog where  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) and isBlocked = 1 order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnections(query: String): PagingSource<Int, RethinkLog>

    @Query(
        "select uid as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName from RethinkLog where uid = :uid group by ipAddress order by count desc"
    )
    fun getLogsForApp(uid: Int): PagingSource<Int, AppConnection>

    @Query(
        "select uid as uid, ipAddress as ipAddress, port as port, count(ipAddress) as count, flag, 0 as blocked, GROUP_CONCAT(DISTINCT dnsQuery) as appOrDnsName from RethinkLog where uid = :uid and ipAddress like :ipAddress group by ipAddress order by count desc"
    )
    fun getLogsForAppFiltered(uid: Int, ipAddress: String): PagingSource<Int, AppConnection>

    @Query("select count(DISTINCT(ipAddress)) from RethinkLog where uid = :uid")
    fun getAppConnectionsCount(uid: Int): LiveData<Int>

    @Query("select * from RethinkLog where isBlocked = 1 order by id desc LIMIT $MAX_LOGS")
    fun getBlockedConnectionsFiltered(): PagingSource<Int, RethinkLog>

    @Query(
        "select * from RethinkLog where isBlocked = 1 and (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getBlockedConnectionsFiltered(query: String): PagingSource<Int, RethinkLog>

    @Query("delete from RethinkLog") fun clearAllData()

    @Query("delete from RethinkLog where uid = :uid") fun clearLogsByUid(uid: Int)

    @Query("DELETE FROM RethinkLog WHERE  timeStamp < :date") fun purgeLogsByDate(date: Long)

    @Query(
        "select * from RethinkLog where isBlocked = 0 and  (appName like :query or ipAddress like :query or dnsQuery like :query or flag like :query) order by id desc LIMIT $MAX_LOGS"
    )
    fun getAllowedConnections(query: String): PagingSource<Int, RethinkLog>

    @Query("select * from RethinkLog where isBlocked = 0 order by id desc LIMIT $MAX_LOGS")
    fun getAllowedConnections(): PagingSource<Int, RethinkLog>

    @Query("select count(id) from RethinkLog") fun logsCount(): LiveData<Long>

    @Query("select timeStamp from RethinkLog where id = (select min(id) from RethinkLog)")
    fun getLeastLoggedTime(): Long

    @Query(
        "SELECT uid, SUM(uploadBytes) AS uploadBytes, SUM(downloadBytes) AS downloadBytes FROM RethinkLog where timeStamp >= :fromTime and timeStamp <= :toTime GROUP BY uid"
    )
    fun getDataUsage(fromTime: Long, toTime: Long): List<DataUsage>
}
