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

class RpnLogRepository(private val rpnLogDAO: RpnLogDAO) {

    suspend fun insert(log: RpnLog) {
        rpnLogDAO.insert(log)
    }

    suspend fun deleteOldLogs(to: Long) {
        rpnLogDAO.deleteOldLogs(to)
    }

    suspend fun insertBatch(logs: List<RpnLog>) {
        rpnLogDAO.insertBatch(logs)
    }

    suspend fun getLogCount(): Int {
        return rpnLogDAO.getLogCount()
    }

    suspend fun getLogsChunked(lastId: Int, limit: Int, offset: Int): List<RpnLog> {
        return rpnLogDAO.getLogsChunked(lastId, limit, offset)
    }

    suspend fun deleteAllLogs() {
        rpnLogDAO.deleteAllLogs()
    }
}
