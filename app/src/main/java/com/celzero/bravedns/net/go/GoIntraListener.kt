/*
 * Copyright 2019 Jigsaw Operations LLC
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.net.go

import android.os.SystemClock
import android.util.Log
import androidx.collection.LongSparseArray
import com.celzero.bravedns.net.dns.DnsPacket
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import dnsx.Dnsx
import dnsx.Summary
import intra.Listener
import intra.TCPSocketSummary
import intra.UDPSocketSummary
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.TimeUnit

object GoIntraListener : Listener, KoinComponent {

    override fun onTCPSocketClosed(summary: TCPSocketSummary?) {}
    override fun onUDPSocketClosed(summary: UDPSocketSummary?) {}
    private val dnsLatencyTracker by inject<QueryTracker>()
    private val dnsLogTracker by inject<DnsLogTracker>()

    // UDP is often used for one-off messages and pings.  The relative overhead of reporting metrics
    // on these short messages would be large, so we only report metrics on sockets that transfer at
    // least this many bytes.
    private const val UDP_THRESHOLD_BYTES = 10000
    private val goStatusMap = LongSparseArray<Transaction.Status>()

    init {
        goStatusMap.put(Dnsx.Complete, Transaction.Status.COMPLETE)
        goStatusMap.put(Dnsx.SendFailed, Transaction.Status.SEND_FAIL)
        goStatusMap.put(Dnsx.NoResponse, Transaction.Status.NO_RESPONSE)
        goStatusMap.put(Dnsx.TransportError, Transaction.Status.TRANSPORT_ERROR)
        goStatusMap.put(Dnsx.BadQuery, Transaction.Status.BAD_QUERY)
        goStatusMap.put(Dnsx.BadResponse, Transaction.Status.BAD_RESPONSE)
        goStatusMap.put(Dnsx.InternalError, Transaction.Status.INTERNAL_ERROR)
    }

    override fun onQuery(query: String?): String {
        // return empty response for now
        return ""
    }

    override fun onResponse(summary: Summary?) {
        if (summary == null) {
            Log.i(LOG_TAG_DNS_LOG, "received null summary")
            return
        }

        val query: DnsPacket = try {
            DnsPacket(summary.query)
        } catch (e: Exception) {
            return
        }
        val latencyMs = (TimeUnit.SECONDS.toMillis(1L) * summary.latency).toLong()
        val nowMs = SystemClock.elapsedRealtime()
        val queryTimeMs = nowMs - latencyMs
        val transaction = Transaction(query, queryTimeMs)
        transaction.queryType = Transaction.QueryType.getType(summary.type)
        transaction.response = summary.response
        transaction.responseTime = latencyMs
        transaction.serverIp = summary.server
        transaction.status = goStatusMap[summary.status]
        transaction.responseCalendar = Calendar.getInstance()
        transaction.blocklist = summary.blocklists
        transaction.queryType = Transaction.QueryType.DOH
        recordTransaction(transaction)
    }

    private fun recordTransaction(transaction: Transaction?) {
        if (transaction == null) {
            Log.i(LOG_TAG_DNS_LOG, "Transaction is null, no need to record")
            return
        }

        transaction.responseCalendar = Calendar.getInstance()
        // All the transactions are recorded in the DNS logs.
        // Quantile estimation correction - Not adding the transactions with server IP
        // as null in the quantile estimator.
        if (!transaction.serverIp.isNullOrEmpty() || transaction.blocklist.isNullOrEmpty()) {
            dnsLatencyTracker.recordTransaction(transaction)
        }
        dnsLogTracker.recordTransaction(transaction)
        updateVpnConnectionState(transaction)
    }

    private fun updateVpnConnectionState(transaction: Transaction?) {
        if (transaction == null) return

        // Update the connection state.  If the transaction succeeded, then the connection is working.
        // If the transaction failed, then the connection is not working.
        // If the transaction was canceled, then we don't have any new information about the status
        // of the connection, so we don't send an update.
        // commented the code for reporting good or bad network.
        // Connection state will be unknown if the transaction is blocked locally in that case,
        // transaction status will be set as complete. So introduced check while
        // setting the connection state.
        if (transaction.status === Transaction.Status.COMPLETE) {
            if (isLocallyResolved(transaction)) return
            VpnController.onConnectionStateChanged(BraveVPNService.State.WORKING)
        } else if (transaction.status !== Transaction.Status.CANCELED) {
            VpnController.onConnectionStateChanged(BraveVPNService.State.FAILING)
        }
    }

    private fun isLocallyResolved(transaction: Transaction?): Boolean {
        if (transaction == null) return false

        return transaction.serverIp.isEmpty()
    }

}
