/*
Copyright 2020 RethinkDNS and its authors

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DnsLogRepository(private val dnsLogDAO: DnsLogDAO) {

    suspend fun insert(dnsLog: DnsLog) {
        ioCtx {
            dnsLogDAO.insert(dnsLog)
        }
    }

    suspend fun deleteOlderData(date: Long) {
        ioCtx {
            dnsLogDAO.deleteOlderData(date)
        }
    }

    suspend fun deleteConnectionTrackerCount() {
        ioCtx {
            dnsLogDAO.deleteOlderDataCount(Constants.TOTAL_LOG_ENTRIES_THRESHOLD)
        }
    }

    suspend fun clearAllData() {
        ioCtx {
            dnsLogDAO.clearAllData()
        }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) {
            f()
        }
    }

}
