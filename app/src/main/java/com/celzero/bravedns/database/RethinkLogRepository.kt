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
import com.celzero.bravedns.data.ConnectionSummary

class RethinkLogRepository(private val logDao: RethinkLogDao) {

    suspend fun insert(log: RethinkLog) {
        logDao.insert(log)
    }

    suspend fun insertBatch(logs: List<RethinkLog>) {
        logDao.insertBatch(logs)
    }

    suspend fun updateBatch(summary: List<ConnectionSummary>) {
        summary.forEach {
            logDao.updateSummary(
                it.connId,
                it.downloadBytes,
                it.uploadBytes,
                it.duration,
                it.synack,
                it.message
            )
        }
    }

    suspend fun purgeLogsByDate(date: Long) {
        logDao.purgeLogsByDate(date)
    }

    suspend fun clearAllData() {
        logDao.clearAllData()
    }

    fun logsCount(): LiveData<Long> {
        return logDao.logsCount()
    }
}
