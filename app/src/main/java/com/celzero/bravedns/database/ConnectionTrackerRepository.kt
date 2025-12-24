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
import com.celzero.bravedns.data.DataUsage
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ConnectionTrackerRepository(private val connectionTrackerDAO: ConnectionTrackerDAO): KoinComponent {

    private val persistentState by inject<PersistentState>()

    suspend fun insert(connectionTracker: ConnectionTracker) {
        connectionTrackerDAO.insert(connectionTracker)
    }

    suspend fun insertBatch(conns: List<ConnectionTracker>) {
        connectionTrackerDAO.insertBatch(conns)
    }

    suspend fun updateBatch(summary: List<ConnectionSummary>) {
        summary.forEach {
            if (!it.targetIp.isNullOrEmpty()) {
                val flag = it.flag ?: ""
                connectionTrackerDAO.updateSummary(
                    it.connId,
                    it.pid,
                    it.rpid,
                    it.downloadBytes,
                    it.uploadBytes,
                    it.duration,
                    it.rtt,
                    it.message,
                    it.targetIp,
                    flag
                )
            } else {
                connectionTrackerDAO.updateSummary(
                    it.connId,
                    it.pid,
                    it.rpid,
                    it.downloadBytes,
                    it.uploadBytes,
                    it.duration,
                    it.rtt,
                    it.message
                )
            }
        }
    }

    suspend fun purgeLogsByDate(date: Long) {
        connectionTrackerDAO.purgeLogsByDate(date)
    }

    suspend fun clearAllData() {
        connectionTrackerDAO.clearAllData()
    }

    fun logsCount(): LiveData<Long> {
        return connectionTrackerDAO.logsCount()
    }

    fun getLeastLoggedTime(): Long {
        return connectionTrackerDAO.getLeastLoggedTime()
    }

    suspend fun getConnIdByUidIpAddress(uid: Int, ipAddress: String, to: Long): List<String> {
        return connectionTrackerDAO.getConnIdByUidIpAddress(uid, ipAddress, to)
    }

    suspend fun clearLogsByUid(uid: Int) {
        connectionTrackerDAO.clearLogsByUid(uid)
    }

    suspend fun clearLogsByRule(rule: String) {
        connectionTrackerDAO.clearLogsByRule(rule)
    }

    suspend fun getDataUsage(before: Long, current: Long): List<DataUsage>? {
        return connectionTrackerDAO.getDataUsage(before, current)
    }

    suspend fun getBlockedUniversalRulesCount(): List<ConnectionTracker> {
        return connectionTrackerDAO.getBlockedUniversalRulesCount()
    }

    suspend fun closeConnections( connIds: List<String>, reason: String) {
        connectionTrackerDAO.closeConnections(connIds, reason)
    }

    suspend fun closeConnectionForUids( uids: List<Int>, reason: String) {
        connectionTrackerDAO.closeConnectionForUids(uids, reason)
    }

    private val BLOCKED_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    fun getBlockedConnectionsCountLiveData(): LiveData<Int> {
        val since = System.currentTimeMillis() - BLOCKED_WINDOW_MS

        return connectionTrackerDAO.getBlockedConnectionsCountLiveData(since)
    }
}
