/*
 * Copyright 2018 Jigsaw Operations LLC
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
package com.celzero.bravedns.service

import android.text.TextUtils
import backend.Backend
import com.celzero.bravedns.net.doh.Transaction

class QueryTracker(val persistentState: PersistentState) {

    private var numRequests: Long = 0

    companion object {
        private const val HISTORY_SIZE = 20
    }

    suspend fun refreshLatencyIfNeeded(transaction: Transaction) {
        // if server-ip is nil and blocklists are not empty, skip because this tx was resolved
        // locally
        if (TextUtils.isEmpty(transaction.serverName) && !TextUtils.isEmpty(transaction.blocklist))
            return
        ++numRequests
        if (
            numRequests % HISTORY_SIZE == 0L ||
                persistentState.median.value == null ||
                persistentState.median.value == 0L
        ) {
            refreshP50Latency(transaction.id)
        }
    }

    private suspend fun refreshP50Latency(id: String) {
        // Dnsx.Local is multicast DNS, ignore its latency
        if (id == Backend.Local) return

        VpnController.syncP50Latency(id)
    }
}
