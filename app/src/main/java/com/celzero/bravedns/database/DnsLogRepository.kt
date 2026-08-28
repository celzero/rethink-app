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

class DnsLogRepository(private val dnsLogDAO: DnsLogDAO) {

    suspend fun insert(dnsLog: DnsLog) {
        dnsLogDAO.insert(dnsLog)
    }

    suspend fun insertBatch(dnsLogs: List<DnsLog>) {
        dnsLogDAO.insertBatch(dnsLogs)
    }

    suspend fun purgeDnsLogsByDate(date: Long) {
        dnsLogDAO.purgeDnsLogsByDate(date)
    }

    suspend fun clearAllData() {
        dnsLogDAO.clearAllData()
    }

    fun logsCount(): LiveData<Long> {
        return dnsLogDAO.logsCount()
    }

    fun getLeastLoggedTime(): Long {
        return dnsLogDAO.getLeastLoggedTime()
    }

    suspend fun getActivityBuckets(
        dayStart: Long,
        dayEnd: Long,
        bucketMs: Long
    ): List<ActivityBucketRow> {
        return dnsLogDAO.getActivityBuckets(dayStart, dayEnd, bucketMs)
    }

    suspend fun getWindowCounts(start: Long, end: Long): WindowCountRow {
        return dnsLogDAO.getWindowCounts(start, end)
    }

    suspend fun getDnsLogsInWindow(start: Long, end: Long, limit: Int): List<DnsLog> {
        return dnsLogDAO.getDnsLogsInWindow(start, end, limit)
    }

    suspend fun getAppActivity(start: Long, end: Long, limit: Int): List<AppActivityRow> {
        return dnsLogDAO.getAppActivity(start, end, limit)
    }

    suspend fun getDnsLogsInWindowForUid(
        start: Long,
        end: Long,
        uid: Int,
        limit: Int
    ): List<DnsLog> {
        return dnsLogDAO.getDnsLogsInWindowForUid(start, end, uid, limit)
    }
}
