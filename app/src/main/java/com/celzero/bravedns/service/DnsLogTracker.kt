/*
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

import android.content.Context
import android.os.SystemClock
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.UIUtils.fetchFavIcon
import com.celzero.bravedns.util.Utilities.getCountryCode
import com.celzero.bravedns.util.Utilities.getFlag
import com.celzero.bravedns.util.Utilities.makeAddressPair
import com.celzero.bravedns.util.Utilities.normalizeIp
import dnsx.Summary
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DnsLogTracker
internal constructor(
    private val dnsLogRepository: DnsLogRepository,
    private val persistentState: PersistentState,
    private val context: Context
) {

    companion object {
        private const val PERSISTENCE_STATE_INSERT_SIZE = 100L
        const val DNS_LEAK_TEST = "dnsleaktest"

        // Some apps like firefox, instagram do not respect ttls
        // add a reasonable grace period to account for that
        // for eg: https://support.mozilla.org/en-US/questions/1213045
        val DNS_TTL_GRACE_SEC = TimeUnit.MINUTES.toSeconds(5L)
        private const val RDATA_MAX_LENGTH = 100
    }

    private var numRequests: Long = 0
    private var numBlockedRequests: Long = 0
    private val vpnStateMap = HashMap<Transaction.Status, BraveVPNService.State>()

    init {
        // init values from persistence state
        numRequests = persistentState.numberOfRequests
        numBlockedRequests = persistentState.numberOfBlockedRequests
        // trigger livedata update with init'd values
        persistentState.dnsRequestsCountLiveData.postValue(numRequests)
        persistentState.dnsBlockedCountLiveData.postValue(numBlockedRequests)

        vpnStateMap[Transaction.Status.COMPLETE] = BraveVPNService.State.WORKING
        vpnStateMap[Transaction.Status.SEND_FAIL] = BraveVPNService.State.NO_INTERNET
        vpnStateMap[Transaction.Status.NO_RESPONSE] = BraveVPNService.State.DNS_SERVER_DOWN
        vpnStateMap[Transaction.Status.TRANSPORT_ERROR] = BraveVPNService.State.DNS_SERVER_DOWN
        vpnStateMap[Transaction.Status.BAD_QUERY] = BraveVPNService.State.DNS_ERROR
        vpnStateMap[Transaction.Status.BAD_RESPONSE] = BraveVPNService.State.DNS_ERROR
        vpnStateMap[Transaction.Status.INTERNAL_ERROR] = BraveVPNService.State.APP_ERROR
    }

    fun processOnResponse(summary: Summary): Transaction {
        val latencyMs = (TimeUnit.SECONDS.toMillis(1L) * summary.latency).toLong()
        val nowMs = SystemClock.elapsedRealtime()
        val queryTimeMs = nowMs - latencyMs
        val transaction = Transaction()
        transaction.name = summary.qName
        transaction.type = summary.qType
        transaction.id = summary.id
        transaction.queryTime = queryTimeMs
        transaction.transportType = Transaction.TransportType.getType(summary.type)
        transaction.response = summary.rData ?: ""
        transaction.ttl = summary.rTtl
        transaction.responseTime = latencyMs
        transaction.serverName = summary.server ?: ""
        transaction.status = Transaction.Status.fromId(summary.status)
        transaction.responseCalendar = Calendar.getInstance()
        transaction.blocklist = summary.blocklists ?: ""
        transaction.relayName = summary.relayServer ?: ""
        return transaction
    }

    fun makeDnsLogObj(transaction: Transaction): DnsLog {
        val dnsLog = DnsLog()

        dnsLog.blockLists = transaction.blocklist
        dnsLog.resolverId = transaction.id
        if (transaction.transportType.isDnsCrypt()) {
            dnsLog.relayIP = transaction.relayName
        } else {
            // fixme: handle for DoH and Dns proxy
            dnsLog.relayIP = ""
        }
        dnsLog.dnsType = transaction.transportType.ordinal
        dnsLog.latency = transaction.responseTime
        dnsLog.queryStr = transaction.name
        dnsLog.responseTime = transaction.responseTime
        dnsLog.serverIP = transaction.serverName
        dnsLog.status = transaction.status.name
        dnsLog.time = transaction.responseCalendar.timeInMillis
        val typeName = ResourceRecordTypes.getTypeName(transaction.type.toInt())
        if (typeName == ResourceRecordTypes.UNKNOWN) {
            dnsLog.typeName = transaction.type.toString()
        } else {
            dnsLog.typeName = typeName.desc
        }
        dnsLog.resolver = transaction.serverName

        if (transaction.status === Transaction.Status.COMPLETE) {

            if (ResourceRecordTypes.mayContainIP(transaction.type.toInt())) {
                val addresses = transaction.response.split(",").toTypedArray()
                val destination = normalizeIp(addresses.getOrNull(0))

                if (destination != null) {
                    val countryCode: String? = getCountryCode(destination, context)
                    // addresses cannot be empty if destination is not null
                    dnsLog.response = makeAddressPair(countryCode, addresses[0])

                    dnsLog.responseIps =
                        addresses.joinToString(separator = ",") {
                            val addr = normalizeIp(it)
                            makeAddressPair(getCountryCode(addr, context), it)
                        }
                    dnsLog.isBlocked =
                        destination.hostAddress == UNSPECIFIED_IP_IPV4 ||
                            destination.hostAddress == UNSPECIFIED_IP_IPV6

                    dnsLog.flag = getFlagIfPresent(countryCode)
                } else {
                    // no ip address found
                    dnsLog.flag =
                        context.getString(R.string.unicode_question_sign) // white question mark
                }
            } else {
                // make sure we don't log too much data
                dnsLog.response = transaction.response.take(RDATA_MAX_LENGTH)
                dnsLog.flag = context.getString(R.string.unicode_check_sign) // green check mark
            }
        } else {
            // error
            dnsLog.response = transaction.status.name
            dnsLog.flag = context.getString(R.string.unicode_warning_sign) // Warning sign
        }

        if (persistentState.fetchFavIcon) {
            fetchFavIcon(context, dnsLog)
        }
        return dnsLog
    }

    private fun getFlagIfPresent(hostAddress: String?): String {
        if (hostAddress == null) {
            return context.getString(R.string.unicode_warning_sign)
        }
        return getFlag(hostAddress)
    }

    suspend fun insertBatch(dnsLogs: List<DnsLog>) {
        dnsLogRepository.insertBatch(dnsLogs)
    }

    fun updateVpnConnectionState(transaction: Transaction?) {
        if (transaction == null) return

        // Update the connection state.  If the transaction succeeded, then the connection is
        // working.
        // If the transaction failed, then the connection is not working.
        // commented the code for reporting good or bad network.
        // Connection state will be unknown if the transaction is blocked locally in that case,
        // transaction status will be set as complete. So introduced check while
        // setting the connection state.
        if (transaction.status === Transaction.Status.COMPLETE) {
            if (isLocallyResolved(transaction)) return
            VpnController.onConnectionStateChanged(BraveVPNService.State.WORKING)
        } else {
            val vpnState = vpnStateMap[transaction.status] ?: BraveVPNService.State.FAILING
            VpnController.onConnectionStateChanged(vpnState)
        }
    }

    private fun isLocallyResolved(transaction: Transaction?): Boolean {
        if (transaction == null) return false

        return transaction.serverName.isEmpty()
    }

    fun updateDnsRequestCount(dnsLog: DnsLog) {
        CoroutineScope(Dispatchers.IO).launch {
            // Post number of requests and blocked count to livedata.
            persistentState.dnsRequestsCountLiveData.postValue(++numRequests)
            if (dnsLog.isBlocked)
                persistentState.dnsBlockedCountLiveData.postValue(++numBlockedRequests)

            // avoid excessive disk I/O from syncing the counter to disk after every request
            if (numRequests % PERSISTENCE_STATE_INSERT_SIZE == 0L) {
                // Blocked request count
                if (numBlockedRequests > persistentState.numberOfBlockedRequests) {
                    persistentState.numberOfBlockedRequests = numBlockedRequests
                } else {
                    numBlockedRequests = persistentState.numberOfBlockedRequests
                }

                // Number of request count
                if (numRequests > persistentState.numberOfRequests) {
                    persistentState.numberOfRequests = numRequests
                } else {
                    numRequests = persistentState.numberOfRequests
                }
            }
        }
    }
}
