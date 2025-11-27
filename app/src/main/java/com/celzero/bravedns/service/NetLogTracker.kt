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

import Logger
import Logger.LOG_BATCH_LOGGER
import android.content.Context
import android.util.Log
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.util.Daemons
import com.celzero.bravedns.util.NetLogBatcher
import com.celzero.firestack.backend.DNSSummary
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import java.util.Calendar

class NetLogTracker
internal constructor(
    context: Context,
    connectionTrackerRepository: ConnectionTrackerRepository,
    rethinkLogRepository: RethinkLogRepository,
    dnsLogRepository: DnsLogRepository,
    consoleLogRepository: ConsoleLogRepository,
    private val persistentState: PersistentState
) : KoinComponent {

    @Volatile private var scope: CoroutineScope? = null

    private var dnsdb: DnsLogTracker = DnsLogTracker(dnsLogRepository, persistentState, context)
    private var ipdb: IPTracker =
        IPTracker(connectionTrackerRepository, rethinkLogRepository, context)
    private var consoleLogDb: ConsoleLogManager = ConsoleLogManager(consoleLogRepository)

    private var dnsBatcher: NetLogBatcher<DnsLog, Nothing>? = null
    private var ipBatcher: NetLogBatcher<ConnectionTracker, ConnectionSummary>? = null
    private var rrBatcher: NetLogBatcher<RethinkLog, ConnectionSummary>? = null
    private var consoleLogBatcher: NetLogBatcher<ConsoleLog, Nothing>? = null

    // dispatch buffer to consumer if greater than batch size for dns, ip and rr logs
    // expected per row size is 100 bytes to 500 bytes, so a batch of 40 rows is around 4KB to 20KB
    private val logBatchSize = 40
    // dispatch buffer to consumer if greater than batch size, for console logs
    private val consoleLogBatchSize = 1024

    // a single thread to run sig and batch co-routines in;
    // to avoid use of mutex/semaphores over shared-state
    // looper is never closed / cancelled and is always active
    private val looper = Daemons.make("netl")

    private val consoleLogLooper = Daemons.make("consl")

    suspend fun restart(s: CoroutineScope) {
        this.scope = s
        serializer("restart", looper) {

            // create new batchers on every new scope as their lifecycle is tied to the scope
            val b1 = NetLogBatcher<DnsLog, Nothing>("dns", looper, logBatchSize, dnsdb::insertBatch)
            val b2 =
                NetLogBatcher<ConnectionTracker, ConnectionSummary>(
                    "ip",
                    looper,
                    logBatchSize,
                    ipdb::insertBatch,
                    ipdb::updateBatch
                )
            val b3 =
                NetLogBatcher<RethinkLog, ConnectionSummary>(
                    "rr",
                    looper,
                    logBatchSize,
                    ipdb::insertRethinkBatch,
                    ipdb::updateRethinkBatch
                )
            val b4 =
                NetLogBatcher<ConsoleLog, Nothing>("console", consoleLogLooper, consoleLogBatchSize, consoleLogDb::insertBatch)

            b1.begin(s)
            b2.begin(s)
            b3.begin(s)
            b4.begin(s)

            this.dnsBatcher = b1
            this.ipBatcher = b2
            this.rrBatcher = b3
            this.consoleLogBatcher = b4

            s.launch(Dispatchers.IO) { monitorCancellation() }
            Log.d(LOG_BATCH_LOGGER, "tracker: restart, $scope")
        }
    }

    // stackoverflow.com/a/68905423
    private suspend fun monitorCancellation() {
        try {
            awaitCancellation()
        } finally {
            withContext(looper + NonCancellable) {
                dnsBatcher?.close()
                ipBatcher?.close()
                rrBatcher?.close()
                dnsBatcher = null
                ipBatcher = null
                rrBatcher = null
                Logger.d(LOG_BATCH_LOGGER, "tracker: close scope")
            }
            withContext(consoleLogLooper + NonCancellable) {
                consoleLogBatcher?.close()
                consoleLogBatcher = null
                Logger.d(LOG_BATCH_LOGGER, "tracker: close consoleLogLooper")
            }
        }
    }

    fun writeIpLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        serializer("writeIpLog", looper) {
            val connTracker = ipdb.makeConnectionTracker(info)
            ipBatcher?.add(connTracker)
        }
    }

    fun writeRethinkLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        serializer("writeRethinkLog", looper) {
            val rlog = ipdb.makeRethinkLogs(info)
            rrBatcher?.add(rlog)
        }
    }

    fun updateIpSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        serializer("updateIpSmm", looper) {
            val s =
                if (summary.targetIp?.isNotEmpty() == true) {
                    ipdb.makeSummaryWithTarget(summary)
                } else {
                    summary
                }

            ipBatcher?.update(s)
        }
    }

    fun updateRethinkSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        serializer("updateRethinkSmm", looper) {
            val s =
                if (summary.targetIp?.isNotEmpty() == true) {
                    ipdb.makeSummaryWithTarget(summary)
                } else {
                    summary
                }

            rrBatcher?.update(s)
        }
    }

    // now, this method is doing multiple things which should be removed.
    // fixme: should intend to only write the logs to database.
    fun processDnsLog(summary: DNSSummary, rethinkUid: Int) {
        val transaction = dnsdb.processOnResponse(summary, rethinkUid)

        transaction.responseCalendar = Calendar.getInstance()

        // TODO: This method should be part of BraveVPNService
        dnsdb.updateVpnConnectionState(transaction)

        if (!persistentState.logsEnabled) return

        serializer("writeDnsLog", looper) {
            val dnsLog = dnsdb.makeDnsLogObj(transaction)
            dnsBatcher?.add(dnsLog)
        }
    }

    fun writeConsoleLog(log: ConsoleLog) {
        serializer("writeConsoleLog", consoleLogLooper) {
            consoleLogBatcher?.add(log)
        }
    }

    private fun serializer(s: String, e: ExecutorCoroutineDispatcher, f: suspend () -> Unit) =
        scope?.launch(CoroutineName(s) + e) { f() }
            ?: Log.e(LOG_BATCH_LOGGER, "scope is null", Exception())
}
