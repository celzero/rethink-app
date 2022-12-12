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
import android.util.Log
import androidx.collection.LongSparseArray
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.net.dns.DnsPacket
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.FirewallManager.ipDomainLookup
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOOPBACK_IPV6
import com.celzero.bravedns.util.Constants.Companion.NXDOMAIN
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.celzero.bravedns.util.IpManager
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.celzero.bravedns.util.Utilities.Companion.makeAddressPair
import dnsx.Dnsx
import dnsx.Summary
import inet.ipaddr.HostName
import inet.ipaddr.IPAddress
import java.net.InetAddress
import java.net.ProtocolException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
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
        private const val DNS_LEAK_TEST = "dnsleaktest"

        // Some apps like firefox, instagram do not respect ttls
        // add a reasonable grace period to account for that
        // for eg: https://support.mozilla.org/en-US/questions/1213045
        private val DNS_TTL_GRACE_SEC = TimeUnit.MINUTES.toSeconds(5L)
    }

    private var numRequests: Long = 0
    private var numBlockedRequests: Long = 0
    private val goStatusMap = LongSparseArray<Transaction.Status>()
    private val vpnStateMap = HashMap<Transaction.Status, BraveVPNService.State>()

    init {
        // init values from persistence state
        numRequests = persistentState.numberOfRequests
        numBlockedRequests = persistentState.numberOfBlockedRequests
        // trigger livedata update with init'd values
        persistentState.dnsRequestsCountLiveData.postValue(numRequests)
        persistentState.dnsBlockedCountLiveData.postValue(numBlockedRequests)

        goStatusMap.put(Dnsx.Complete, Transaction.Status.COMPLETE)
        goStatusMap.put(Dnsx.SendFailed, Transaction.Status.SEND_FAIL)
        goStatusMap.put(Dnsx.NoResponse, Transaction.Status.NO_RESPONSE)
        goStatusMap.put(Dnsx.TransportError, Transaction.Status.TRANSPORT_ERROR)
        goStatusMap.put(Dnsx.BadQuery, Transaction.Status.BAD_QUERY)
        goStatusMap.put(Dnsx.BadResponse, Transaction.Status.BAD_RESPONSE)
        goStatusMap.put(Dnsx.InternalError, Transaction.Status.INTERNAL_ERROR)

        vpnStateMap[Transaction.Status.COMPLETE] = BraveVPNService.State.WORKING
        vpnStateMap[Transaction.Status.SEND_FAIL] = BraveVPNService.State.NO_INTERNET
        vpnStateMap[Transaction.Status.NO_RESPONSE] = BraveVPNService.State.DNS_SERVER_DOWN
        vpnStateMap[Transaction.Status.TRANSPORT_ERROR] = BraveVPNService.State.DNS_SERVER_DOWN
        vpnStateMap[Transaction.Status.BAD_QUERY] = BraveVPNService.State.DNS_ERROR
        vpnStateMap[Transaction.Status.BAD_RESPONSE] = BraveVPNService.State.DNS_ERROR
        vpnStateMap[Transaction.Status.INTERNAL_ERROR] = BraveVPNService.State.APP_ERROR
    }

    fun processOnResponse(summary: Summary): Transaction? {
        val query: DnsPacket =
            try {
                DnsPacket(summary.query)
            } catch (e: Exception) {
                return null
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
        return transaction
    }

    fun makeDnsLogObj(transaction: Transaction): DnsLog {
        val dnsLog = DnsLog()

        dnsLog.blockLists = transaction.blocklist
        if (transaction.queryType.isDnsCrypt) {
            dnsLog.dnsType = AppConfig.DnsType.DNSCRYPT.type
            dnsLog.relayIP = transaction.relayIp
        } else {
            // fixme: handle for DoH and Dns proxy
            dnsLog.dnsType = transaction.queryType.ordinal
            dnsLog.relayIP = ""
        }
        dnsLog.latency = transaction.responseTime
        dnsLog.queryStr = transaction.name
        dnsLog.responseTime = transaction.responseTime
        dnsLog.serverIP = transaction.serverIp
        dnsLog.status = transaction.status.name
        dnsLog.time = transaction.responseCalendar.timeInMillis
        val typeName = ResourceRecordTypes.getTypeName(transaction.type.toInt())
        if (typeName == ResourceRecordTypes.UNKNOWN) {
            dnsLog.typeName = transaction.type.toString()
        } else {
            dnsLog.typeName = typeName.desc
        }
        val serverAddress = IpManager.getIpAddress(transaction.serverIp)

        if (serverAddress?.toInetAddress()?.hostAddress != null) {
            val countryCode: String? =
                getCountryCode(serverAddress.toInetAddress(), context) // TODO: Country code things
            dnsLog.resolver = makeAddressPair(countryCode, transaction.serverIp)
        } else {
            dnsLog.resolver = transaction.serverIp
        }
        if (transaction.status === Transaction.Status.COMPLETE) {
            var packet: DnsPacket? = null
            var err = ""
            try {
                packet = DnsPacket(transaction.response)
            } catch (e: ProtocolException) {
                err = e.message.toString()
            }
            if (packet != null) {
                val addresses: List<InetAddress> = packet.responseAddresses

                if (addresses.isNotEmpty()) {
                    val destination = convertIpV6ToIpv4IfNeeded(addresses[0])
                    val countryCode: String? = getCountryCode(destination, context)

                    val inetAddress = convertIpV6ToIpv4IfNeeded(addresses[0])
                    dnsLog.response =
                        makeAddressPair(
                            getCountryCode(inetAddress, context),
                            addresses[0].hostAddress
                        )

                    dnsLog.responseIps =
                        addresses.joinToString(separator = ",") {
                            val addr = convertIpV6ToIpv4IfNeeded(it)
                            makeAddressPair(getCountryCode(addr, context), it.hostAddress)
                        }

                    if (destination.hostAddress?.contains(UNSPECIFIED_IP_IPV4) == true) {
                        dnsLog.isBlocked = true
                    }
                    if (destination.isLoopbackAddress) {
                        dnsLog.isBlocked = true
                    } else if (
                        destination.hostAddress == UNSPECIFIED_IP_IPV6 ||
                            destination.hostAddress == LOOPBACK_IPV6
                    ) {
                        dnsLog.isBlocked = true
                    }

                    val flag = getFlagIfPresent(countryCode)
                    dnsLog.flag = flag

                    packet.answer.forEach { r ->
                        val ip = r.ip ?: return@forEach
                        // drop trailing period . from the fqdn sent in dns-answer, ie a.com. =>
                        // a.com
                        val dnsCacheRecord =
                            FirewallManager.DnsCacheRecord(
                                calculateTtl(r.ttl),
                                transaction.name.dropLast(1),
                                flag
                            )
                        ip.hostAddress?.let { ipDomainLookup.put(it, dnsCacheRecord) }
                    }
                } else {
                    // fixme: for queries with empty AAAA records, we are setting as NXDOMAIN
                    //  which needs a fix. need to check for the response's status
                    dnsLog.response = NXDOMAIN
                    dnsLog.flag =
                        context.getString(R.string.unicode_question_sign) // White question mark
                }
            } else {
                dnsLog.response = err
                dnsLog.flag = context.getString(R.string.unicode_warning_sign) // Warning sign
            }
        } else {
            dnsLog.response = transaction.status.name
            dnsLog.flag =
                if (transaction.status === Transaction.Status.CANCELED) {
                    context.getString(R.string.unicode_x_sign) // "X" mark
                } else {
                    context.getString(R.string.unicode_warning_sign) // Warning sign
                }
        }

        fetchFavIcon(dnsLog)

        return dnsLog
    }

    private fun getFlagIfPresent(hostAddress: String?): String {
        if (hostAddress == null) {
            return context.getString(R.string.unicode_warning_sign)
        }
        return ipDomainLookup.getIfPresent(hostAddress)?.flag ?: getFlag(hostAddress)
    }

    suspend fun insertBatch(dnsLogs: List<DnsLog>) {
        dnsLogRepository.insertBatch(dnsLogs)
    }

    fun updateVpnConnectionState(transaction: Transaction?) {
        if (transaction == null) return

        // Update the connection state.  If the transaction succeeded, then the connection is
        // working.
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
            val vpnState = vpnStateMap[transaction.status] ?: BraveVPNService.State.FAILING
            VpnController.onConnectionStateChanged(vpnState)
        }
    }

    private fun isLocallyResolved(transaction: Transaction?): Boolean {
        if (transaction == null) return false

        return transaction.serverIp.isEmpty()
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

    private fun convertIpV6ToIpv4IfNeeded(ip: InetAddress): InetAddress {
        val ipAddress: IPAddress = HostName(ip).address ?: return ip

        // no need to check if IP is not of type IPv6
        if (!IpManager.isIpV6(ipAddress)) return ip

        val ipv4 = IpManager.toIpV4(ipAddress)

        return if (ipv4 != null) {
            ipv4.toInetAddress()
        } else {
            ip
        }
    }

    private fun calculateTtl(ttl: Int): Long {
        val now = System.currentTimeMillis()

        // on negative ttl, cache dns record for a day
        if (ttl < 0) return now + TimeUnit.DAYS.toMillis(1L)

        return now + TimeUnit.SECONDS.toMillis((ttl + DNS_TTL_GRACE_SEC))
    }

    private fun fetchFavIcon(dnsLog: DnsLog) {
        if (!persistentState.fetchFavIcon || dnsLog.groundedQuery()) return

        if (isDgaDomain(dnsLog.queryStr)) return

        if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide - fetchFavIcon() -${dnsLog.queryStr}")
        FavIconDownloader(context, dnsLog.queryStr).run()
    }

    // check if the domain is generated by a DGA (Domain Generation Algorithm)
    private fun isDgaDomain(fqdn: String): Boolean {
        // dnsleaktest.com fqdn's are auto-generated
        if (fqdn.contains(DNS_LEAK_TEST)) return true

        // fqdn's which has uuids are auto-generated
        return containsUuid(fqdn)
    }

    private fun containsUuid(fqdn: String): Boolean {
        // ref: https://stackoverflow.com/a/39611414
        val regex = "\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}"
        val pattern: Pattern = Pattern.compile(regex)
        val matcher: Matcher = pattern.matcher(fqdn)
        return matcher.find()
    }
}
