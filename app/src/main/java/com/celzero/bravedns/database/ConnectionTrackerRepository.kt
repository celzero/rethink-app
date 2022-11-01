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

import com.celzero.bravedns.data.AppConnections
import com.celzero.bravedns.util.Constants

class ConnectionTrackerRepository(private val connectionTrackerDAO: ConnectionTrackerDAO) {

    fun insert(connectionTracker: ConnectionTracker) {
        connectionTrackerDAO.insert(connectionTracker)
    }

    fun insertBatch(conns: List<ConnectionTracker>) {
        connectionTrackerDAO.insertBatch(conns)
    }

    fun getLogsForApp(uid: Int): List<AppConnections>? {
        return connectionTrackerDAO.getLogsForApp(uid)
    }

    fun deleteConnectionTrackerCount() {
        connectionTrackerDAO.deleteOlderDataCount(Constants.TOTAL_LOG_ENTRIES_THRESHOLD)
    }

    fun deleteOlderData(date: Long) {
        connectionTrackerDAO.deleteOlderData(date)
    }

    fun clearAllData() {
        connectionTrackerDAO.clearAllData()
    }

}
