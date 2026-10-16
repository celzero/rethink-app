/*
 * Copyright 2026 RethinkDNS and its authors
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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RpnLogDAO {
    @Insert
    suspend fun insert(log: RpnLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(logs: List<RpnLog>)

    @Query("SELECT * FROM RpnLog where id > :lastId LIMIT :limit OFFSET :offset")
    suspend fun getLogsChunked(lastId: Int, limit: Int, offset: Int): List<RpnLog>

    @Query("SELECT * FROM RpnLog WHERE message LIKE :input AND level >= :minLevel ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getLogsForUi(input: String, minLevel: Int, limit: Int): List<RpnLog>

    @Query("select timestamp from RpnLog order by id limit 1")
    suspend fun sinceTime(): Long

    @Query("DELETE FROM RpnLog WHERE timestamp < :to")
    suspend fun deleteOldLogs(to: Long)

    @Query("select count(*) from RpnLog")
    suspend fun getLogCount(): Int

    @Query("DELETE FROM RpnLog")
    suspend fun deleteAllLogs()
}
