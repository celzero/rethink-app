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
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.util.NetLogBatcher
import dnsx.Summary
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar

class NetLogTracker
internal constructor(
    private val context: Context,
    private val connectionTrackerRepository: ConnectionTrackerRepository,
    private val rethinkLogRepository: RethinkLogRepository,
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
    private var rethinkLogBatcher: NetLogBatcher<RethinkLog>? = null
    private var rethinkSummaryBatcher: NetLogBatcher<ConnectionSummary>? = null

    suspend fun startLogger(s: CoroutineScope) {
        if (ipTracker == null) {
            ipTracker = IPTracker(connectionTrackerRepository, rethinkLogRepository, context)
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

        rethinkLogBatcher = NetLogBatcher(ipTracker!!::insertRethinkBatch)
        rethinkLogBatcher!!.begin(scope!!)

        rethinkSummaryBatcher = NetLogBatcher(ipTracker!!::updateRethinkBatch)
        rethinkSummaryBatcher!!.begin(scope!!)
    }

    fun writeIpLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        io("writeIpLog") {
            val connTracker = ipTracker?.makeConnectionTracker(info) ?: return@io
            ipNetLogBatcher?.add(connTracker)
        }
    }

    fun writeRethinkLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        io("writeRethinkLog") {
            val rlog = ipTracker?.makeRethinkLogs(info) ?: return@io
            rethinkLogBatcher?.add(rlog)
        }
    }

    fun updateIpSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        io("writeIpSummary") { summaryBatcher?.add(summary) }
    }

    fun updateRethinkSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        io("writeRethinkSummary") { rethinkSummaryBatcher?.add(summary) ?: return@io }
    }

    // now, this method is doing multiple things which should be removed.
    // fixme: should intend to only write the logs to database.
    fun processDnsLog(summary: Summary) {
        val transaction = dnsLogTracker?.processOnResponse(summary) ?: return

        transaction.responseCalendar = Calendar.getInstance()
        // refresh latency from GoVpnAdapter
        io("refreshDnsLatency") { dnsLatencyTracker.refreshLatencyIfNeeded(transaction) }

        // TODO: This method should be part of BraveVPNService
        dnsLogTracker?.updateVpnConnectionState(transaction)

        if (!persistentState.logsEnabled) return

        val dnsLog = dnsLogTracker?.makeDnsLogObj(transaction) ?: return
        io("dnsLogger") { dnsNetLogBatcher?.add(dnsLog) }
    }

    private fun io(s: String, f: suspend () -> Unit) =
        scope?.launch(CoroutineName(s) + Dispatchers.IO) { f() }
}
