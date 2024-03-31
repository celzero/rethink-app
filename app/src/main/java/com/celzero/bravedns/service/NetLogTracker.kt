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
import backend.DNSSummary
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.util.NetLogBatcher
import java.util.Calendar
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NetLogTracker
internal constructor(
    context: Context,
    connectionTrackerRepository: ConnectionTrackerRepository,
    rethinkLogRepository: RethinkLogRepository,
    dnsLogRepository: DnsLogRepository,
    private val persistentState: PersistentState
) : KoinComponent {

    private val dnsLatencyTracker by inject<QueryTracker>()

    private var scope: CoroutineScope? = null

    private var dnsLogTracker: DnsLogTracker =
        DnsLogTracker(dnsLogRepository, persistentState, context)
    private var ipTracker: IPTracker =
        IPTracker(connectionTrackerRepository, rethinkLogRepository, context)

    private var dnsNetLogBatcher: NetLogBatcher<DnsLog, Nothing> =
        NetLogBatcher("dns", dnsLogTracker::insertBatch)
    private var ipNetLogBatcher: NetLogBatcher<ConnectionTracker, ConnectionSummary> =
        NetLogBatcher("ip", ipTracker::insertBatch, ipTracker::updateBatch)
    private var rethinkLogBatcher: NetLogBatcher<RethinkLog, ConnectionSummary> =
        NetLogBatcher("rinr", ipTracker::insertRethinkBatch, ipTracker::updateRethinkBatch)

    suspend fun startLogger(s: CoroutineScope) {
        this.scope = s

        dnsNetLogBatcher.begin(s)
        ipNetLogBatcher.begin(s)
        rethinkLogBatcher.begin(s)
    }

    fun writeIpLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        io("writeIpLog") {
            val connTracker = ipTracker.makeConnectionTracker(info)
            ipNetLogBatcher.add(connTracker)
        }
    }

    fun writeRethinkLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        io("writeRethinkLog") {
            val rlog = ipTracker.makeRethinkLogs(info) ?: return@io
            rethinkLogBatcher.add(rlog)
        }
    }

    fun updateIpSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        io("writeIpSummary") {
            val s =
                if (DEBUG && summary.targetIp?.isNotEmpty() == true) {
                    ipTracker.makeSummaryWithTarget(summary)
                } else {
                    summary
                }

            ipNetLogBatcher.update(s)
        }
    }

    fun updateRethinkSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        io("writeRethinkSummary") {
            val s =
                if (DEBUG && summary.targetIp?.isNotEmpty() == true) {
                    ipTracker.makeSummaryWithTarget(summary)
                } else {
                    summary
                }

            rethinkLogBatcher.update(s)
        }
    }

    // now, this method is doing multiple things which should be removed.
    // fixme: should intend to only write the logs to database.
    fun processDnsLog(summary: DNSSummary) {
        val transaction = dnsLogTracker.processOnResponse(summary)

        transaction.responseCalendar = Calendar.getInstance()
        // refresh latency from GoVpnAdapter
        io("refreshDnsLatency") { dnsLatencyTracker.refreshLatencyIfNeeded(transaction) }

        // TODO: This method should be part of BraveVPNService
        dnsLogTracker.updateVpnConnectionState(transaction)

        if (!persistentState.logsEnabled) return

        val dnsLog = dnsLogTracker.makeDnsLogObj(transaction)
        io("dnsLogger") { dnsNetLogBatcher.add(dnsLog) }
    }

    private fun io(s: String, f: suspend () -> Unit) =
        scope?.launch(CoroutineName(s) + Dispatchers.IO) { f() }
}
