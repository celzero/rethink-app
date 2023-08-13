/*
 * Copyright 2022 RethinkDNS and its authors
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

import android.content.Context
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.util.NetLogBatcher
import dnsx.Summary
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NetLogTracker
internal constructor(
    private val context: Context,
    private val connectionTrackerRepository: ConnectionTrackerRepository,
    private val dnsLogRepository: DnsLogRepository,
    private val persistentState: PersistentState
) : KoinComponent {

    private val dnsLatencyTracker by inject<QueryTracker>()

    private var scope: CoroutineScope? = null
        private set

    private var dnsLogTracker: DnsLogTracker? = null
    private var ipTracker: IPTracker? = null

    private var dnsNetLogBatcher: NetLogBatcher<DnsLog>? = null
    private var ipNetLogBatcher: NetLogBatcher<ConnectionTracker>? = null
    private var summaryBatcher: NetLogBatcher<ConnectionSummary>? = null

    suspend fun startLogger(s: CoroutineScope) {
        if (ipTracker == null) {
            ipTracker = IPTracker(connectionTrackerRepository, context)
        }

        if (dnsLogTracker == null) {
            dnsLogTracker = DnsLogTracker(dnsLogRepository, persistentState, context)
        }

        this.scope = s

        // asserting, created object above
        ipNetLogBatcher = NetLogBatcher(ipTracker!!::insertBatch)
        ipNetLogBatcher!!.begin(scope!!)

        dnsNetLogBatcher = NetLogBatcher(dnsLogTracker!!::insertBatch)
        dnsNetLogBatcher!!.begin(scope!!)

        summaryBatcher = NetLogBatcher(ipTracker!!::updateBatch)
        summaryBatcher!!.begin(scope!!)
    }

    fun writeIpLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        scope?.launch {
            val connTracker = ipTracker?.makeConnectionTracker(info) ?: return@launch
            ipNetLogBatcher?.add(connTracker)
        }
    }

    fun updateIpSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        scope?.launch { summaryBatcher?.add(summary) }
    }

    // now, this method is doing multiple things which should be removed.
    // fixme: should intend to only write the logs to database.
    fun processDnsLog(summary: Summary) {
        val transaction = dnsLogTracker?.processOnResponse(summary) ?: return

        transaction.responseCalendar = Calendar.getInstance()
        // quantile estimator
        dnsLatencyTracker.recordTransaction(transaction)

        val dnsLog = dnsLogTracker?.makeDnsLogObj(transaction) ?: return

        // TODO: This method should be part of BraveVPNService
        dnsLogTracker?.updateVpnConnectionState(transaction)

        // ideally this check should be carried out before processing the dns object.
        // Now, the ipDomain cache is adding while making the dnsLog object.
        // TODO: move ipDomain cache out of DnsLog object creation
        if (!persistentState.logsEnabled) return

        scope?.launch { dnsNetLogBatcher?.add(dnsLog) }
    }
}
