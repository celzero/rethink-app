/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.service

import android.content.Context
import android.util.Log
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.net.dns.DnsPacket
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOOPBACK_IPV6
import com.celzero.bravedns.util.Constants.Companion.NXDOMAIN
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IPV6
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.celzero.bravedns.util.Utilities.Companion.makeAddressPair
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.net.InetAddresses.forString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

class DnsLogTracker internal constructor(private val dnsLogRepository: DnsLogRepository,
                                         private val persistentState: PersistentState,
                                         private val context: Context) {

    companion object {
        private const val PERSISTENCE_STATE_INSERT_SIZE = 100L
        private const val DNS_LEAK_TEST = "dnsleaktest"

        private const val CACHE_BUILDER_MAX_SIZE = 20000L
        private val CACHE_BUILDER_WRITE_EXPIRE_HRS = TimeUnit.DAYS.toHours(3L)

        // Some apps like firefox, instagram do not respect ttls
        // add a reasonable grace period to account for that
        // for eg: https://support.mozilla.org/en-US/questions/1213045
        private val DNS_TTL_GRACE_SEC = TimeUnit.MINUTES.toSeconds(5L)
    }

    private var numRequests: Long = 0
    private var numBlockedRequests: Long = 0

    data class DnsCacheRecord(val ttl: Long, val fqdn: String)

    val ipDomainLookup: Cache<String, DnsCacheRecord> = CacheBuilder.newBuilder().maximumSize(
        CACHE_BUILDER_MAX_SIZE).expireAfterWrite(CACHE_BUILDER_WRITE_EXPIRE_HRS,
                                                 TimeUnit.HOURS).build()

    init {
        // init values from persistence state
        numRequests = persistentState.numberOfRequests
        numBlockedRequests = persistentState.numberOfBlockedRequests
        // trigger livedata update with init'd values
        persistentState.dnsRequestsCountLiveData.postValue(numRequests)
        persistentState.dnsBlockedCountLiveData.postValue(numBlockedRequests)
    }

    fun recordTransaction(transaction: Transaction?) {
        if (!persistentState.logsEnabled || transaction == null) return
        insertToDB(transaction)
    }

    private fun insertToDB(transaction: Transaction) {
        CoroutineScope(Dispatchers.IO).launch {
            val dnsLog = DnsLog()

            dnsLog.blockLists = transaction.blocklist
            if (transaction.isDNSCrypt) {
                dnsLog.dnsType = AppConfig.DnsType.DNSCRYPT.type
                dnsLog.relayIP = transaction.relayIp
            } else {
                dnsLog.dnsType = AppConfig.DnsType.DOH.type
                dnsLog.relayIP = ""
            }
            dnsLog.latency = transaction.responseTime
            dnsLog.queryStr = transaction.name
            dnsLog.responseTime = transaction.responseTime
            dnsLog.serverIP = transaction.serverIp
            dnsLog.status = transaction.status.name
            dnsLog.time = transaction.responseCalendar.timeInMillis
            dnsLog.typeName = Utilities.getTypeName(transaction.type.toInt())
            val serverAddress = try {
                if (!transaction.serverIp.isNullOrEmpty()) {
                    // InetAddresses - 'com.google.common.net.InetAddresses' is marked unstable with @Beta
                    forString(transaction.serverIp)
                } else {
                    null
                }
            } catch (e: IllegalArgumentException) {
                Log.e(LOG_TAG_DNS_LOG, "Failure converting string to InetAddresses: ${e.message}",
                      e)
                null
            }

            if (serverAddress != null) {
                val countryCode: String? = getCountryCode(serverAddress,
                                                          context) //TODO: Country code things
                dnsLog.resolver = makeAddressPair(countryCode, serverAddress.hostAddress)
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

                    packet.answer.forEach { r ->
                        val ip = r.ip ?: return@forEach
                        // drop trailing period . from the fqdn sent in dns-answer, ie a.com. => a.com
                        val dnsCacheRecord = DnsCacheRecord(calculateTtl(r.ttl),
                                                            transaction.name.dropLast(1))
                        ipDomainLookup.put(ip.hostAddress, dnsCacheRecord)
                    }

                    if (addresses.isNotEmpty()) {
                        val destination = addresses[0]
                        if (DEBUG) Log.d(LOG_TAG_DNS_LOG,
                                         "Address: ${destination.address[0]}, HostAddress: ${destination.hostAddress}")
                        val countryCode: String? = getCountryCode(destination, context)

                        dnsLog.response = addresses.joinToString(separator = ",") {
                            makeAddressPair(getCountryCode(it, context), it.hostAddress)
                        }
                        dnsLog.responseIps = addresses.joinToString(
                            separator = ",") { it.hostAddress }

                        if (destination.hostAddress.contains(UNSPECIFIED_IP)) {
                            dnsLog.isBlocked = true
                        }
                        if (destination.isLoopbackAddress) {
                            dnsLog.isBlocked = true
                        } else if (destination.hostAddress == UNSPECIFIED_IPV6 || destination.hostAddress == LOOPBACK_IPV6) {
                            dnsLog.isBlocked = true
                        }
                        dnsLog.flag = getFlag(countryCode)
                    } else {
                        dnsLog.response = NXDOMAIN
                        dnsLog.flag = context.getString(
                            R.string.unicode_question_sign) // White question mark
                    }
                } else {
                    dnsLog.response = err
                    dnsLog.flag = context.getString(R.string.unicode_warning_sign) // Warning sign
                }
            } else {
                dnsLog.response = transaction.status.name
                dnsLog.flag = if (transaction.status === Transaction.Status.CANCELED) {
                    context.getString(R.string.unicode_x_sign)// "X" mark
                } else {
                    context.getString(R.string.unicode_warning_sign) // Warning sign
                }
            }

            fetchFavIcon(dnsLog)

            // Post number of requests and blocked count to livedata.
            persistentState.dnsRequestsCountLiveData.postValue(++numRequests)
            if (dnsLog.isBlocked) persistentState.dnsBlockedCountLiveData.postValue(
                ++numBlockedRequests)

            dnsLogRepository.insert(dnsLog)

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

    // TODO: Check if the domain is generated by a DGA (Domain Generation Algorithm)
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
