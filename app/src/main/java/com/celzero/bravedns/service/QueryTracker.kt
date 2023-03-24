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
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.util.P2QuantileEstimation
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp

/**
 * A class for tracking DNS transactions. This class counts the number of successful transactions,
 * records the last minute of query timestamps, and optionally maintains a history of recent
 * transactions. Thread-safe.
 */
class QueryTracker(private var persistentState: PersistentState) {

    private var numRequests: Long = 0
    private var quantileEstimator: P2QuantileEstimation? = null

    init {
        reinitializeQuantileEstimator()
    }

    companion object {
        private const val HISTORY_SIZE = 1000
    }

    private fun reinitializeQuantileEstimator() {
        quantileEstimator = P2QuantileEstimation(0.5)
        numRequests = 1
    }

    fun recordTransaction(transaction: Transaction) {
        // if server-ip is nil and blocklists are not empty, skip because this tx was resolved
        // locally
        if (TextUtils.isEmpty(transaction.serverName) && !TextUtils.isEmpty(transaction.blocklist))
            return
        ++numRequests
        if (numRequests % HISTORY_SIZE == 0L) {
            reinitializeQuantileEstimator()
        }
        sync(transaction)
    }

    fun sync(transaction: Transaction?) {
        if (
            transaction == null ||
                transaction.serverName.isEmpty() ||
                isUnspecifiedIp(transaction.serverName) ||
                transaction.status != Transaction.Status.COMPLETE
        ) {
            return
        }
        // Restore number of requests from storage, or 0 if it isn't defined yet.
        quantileEstimator!!.addValue(transaction.responseTime.toDouble())
        persistentState.setMedianLatency(quantileEstimator!!.getQuantile())
    }
}
