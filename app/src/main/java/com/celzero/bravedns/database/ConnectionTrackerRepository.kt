/*
Copyright 2020 RethinkDNS developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.database

import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class ConnectionTrackerRepository(private val connectionTrackerDAO: ConnectionTrackerDAO) {

    fun updateAsync(connectionTracker: ConnectionTracker,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            connectionTrackerDAO.update(connectionTracker)
        }
    }

    fun insert(connectionTracker: ConnectionTracker) {
        connectionTrackerDAO.insert(connectionTracker)
    }

    fun deleteConnectionTrackerCount() {
        connectionTrackerDAO.deleteOlderDataCount(Constants.TOTAL_NETWORK_LOG_ENTRIES_THRESHOLD)
    }

    fun deleteOlderData(date: Long) {
        connectionTrackerDAO.deleteOlderData(date)
    }
}
