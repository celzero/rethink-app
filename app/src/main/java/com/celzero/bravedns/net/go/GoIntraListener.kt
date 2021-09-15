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
import com.celzero.bravedns.service.DNSLogTracker
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import dnscrypt.Dnscrypt
import dnscrypt.Summary
import doh.Doh
import doh.Token
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
    private val dnsLogTracker by inject<DNSLogTracker>()

    // UDP is often used for one-off messages and pings.  The relative overhead of reporting metrics
    // on these short messages would be large, so we only report metrics on sockets that transfer at
    // least this many bytes.
    private const val UDP_THRESHOLD_BYTES = 10000
    private val goStatusMap = LongSparseArray<Transaction.Status>()
    private val dohStatusMap = LongSparseArray<Transaction.Status>()
    private val dnscryptStatusMap = LongSparseArray<Transaction.Status>()

    private fun len(a: ByteArray?): Int {
        return a?.size ?: 0
    }

    init {
        goStatusMap.put(Doh.Complete, Transaction.Status.COMPLETE)
        goStatusMap.put(Doh.SendFailed, Transaction.Status.SEND_FAIL)
        goStatusMap.put(Doh.HTTPError, Transaction.Status.HTTP_ERROR)
        goStatusMap.put(Doh.BadQuery,
                        Transaction.Status.INTERNAL_ERROR) // TODO: Add a BAD_QUERY Status
        goStatusMap.put(Doh.BadResponse, Transaction.Status.BAD_RESPONSE)
        goStatusMap.put(Doh.InternalError, Transaction.Status.INTERNAL_ERROR)

        // TODO: Add a BAD_QUERY Status
        dohStatusMap.put(Doh.Complete, Transaction.Status.COMPLETE)
        dohStatusMap.put(Doh.SendFailed, Transaction.Status.SEND_FAIL)
        dohStatusMap.put(Doh.HTTPError, Transaction.Status.HTTP_ERROR)
        dohStatusMap.put(Doh.BadQuery, Transaction.Status.INTERNAL_ERROR)
        dohStatusMap.put(Doh.BadResponse, Transaction.Status.BAD_RESPONSE)
        dohStatusMap.put(Doh.InternalError, Transaction.Status.INTERNAL_ERROR)
        dnscryptStatusMap.put(Dnscrypt.Complete, Transaction.Status.COMPLETE)
        dnscryptStatusMap.put(Dnscrypt.SendFailed, Transaction.Status.SEND_FAIL)
        dnscryptStatusMap.put(Dnscrypt.Error, Transaction.Status.CANCELED)
        dnscryptStatusMap.put(Dnscrypt.BadQuery, Transaction.Status.INTERNAL_ERROR)
        dnscryptStatusMap.put(Dnscrypt.BadResponse, Transaction.Status.BAD_RESPONSE)
        dnscryptStatusMap.put(Dnscrypt.InternalError, Transaction.Status.INTERNAL_ERROR)
    }

    override fun onDNSCryptQuery(s: String): Boolean {
        return false
    }

    override fun onDNSCryptResponse(summary: Summary?) {
        if (summary == null) {
            Log.i(LOG_TAG_DNS_LOG, "received null dnscrypt summary")
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
        transaction.response = summary.response
        transaction.responseTime = latencyMs
        transaction.serverIp = summary.server
        transaction.relayIp = summary.relayServer
        transaction.blocklist = summary.blocklists
        transaction.status = dnscryptStatusMap[summary.status]
        transaction.responseCalendar = Calendar.getInstance()
        transaction.isDNSCrypt = true
        recordTransaction(transaction)
    }

    override fun onQuery(url: String): Token? {
        return null
    }

    override fun onResponse(token: Token?, summary: doh.Summary?) {
        if (token == null) {
            // ignore tokens, not used
        }
        if (summary == null) {
            Log.i(LOG_TAG_DNS_LOG, "received null doh summary")
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
        transaction.response = summary.response
        transaction.responseTime = latencyMs
        transaction.serverIp = summary.server
        transaction.status = goStatusMap[summary.status]
        transaction.responseCalendar = Calendar.getInstance()
        transaction.blocklist = summary.blocklists
        transaction.isDNSCrypt = false
        recordTransaction(transaction)
    }

    private fun recordTransaction(transaction: Transaction?) {
        if (transaction == null) {
            Log.i(LOG_TAG_DNS_LOG, "doh transaction is null, no need to record")
            return
        }

        transaction.responseCalendar = Calendar.getInstance()
        // All the transactions are recorded in the DNS logs.
        // Quantile estimation correction - Not adding the transactions with server IP
        // as null in the quantile estimator.
        if (!transaction.serverIp.isNullOrEmpty()) {
            dnsLatencyTracker.recordTransaction(transaction)
        }
        dnsLogTracker.recordTransaction(transaction)
        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "Record Transaction: status as ${transaction.status} with blocklist ${transaction.blocklist}")
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
