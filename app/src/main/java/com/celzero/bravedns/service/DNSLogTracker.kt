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
import com.celzero.bravedns.database.DNSLogRepository
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.net.dns.DnsPacket
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.celzero.bravedns.util.Utilities.Companion.makeAddressPair
import com.google.common.net.InetAddresses.forString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ProtocolException

class DNSLogTracker internal constructor(private val dnsLogRepository: DNSLogRepository, private val persistentState: PersistentState, private val context: Context) {

    fun recordTransaction(transaction: Transaction?) {
        if (transaction != null) {
            insertToDB(transaction)
        }
    }

    private fun insertToDB(transaction: Transaction) {
        GlobalScope.launch(Dispatchers.IO) {
            val dnsLogs = DNSLogs()

            dnsLogs.blockLists = transaction.blockList
            if (transaction.isDNSCrypt) {
                dnsLogs.dnsType = PREF_DNS_MODE_DNSCRYPT
                dnsLogs.relayIP = transaction.relayIp
            } else {
                dnsLogs.dnsType = PREF_DNS_MODE_DOH
                dnsLogs.relayIP = ""
            }
            dnsLogs.latency = transaction.responseTime
            dnsLogs.queryStr = transaction.name
            dnsLogs.responseTime = transaction.responseTime
            dnsLogs.serverIP = transaction.serverIp
            dnsLogs.status = transaction.status.name
            dnsLogs.time = transaction.responseCalendar.timeInMillis
            dnsLogs.typeName = Utilities.getTypeName(transaction.type.toInt())

            val serverAddress = if (transaction.serverIp != null) {
                // InetAddresses - 'com.google.common.net.InetAddresses' is marked unstable with @Beta
                forString(transaction.serverIp)
            } else {
                null
            }
            if (serverAddress != null) {
                val countryCode: String = getCountryCode(serverAddress, context) //TODO: Country code things
                dnsLogs.resolver = makeAddressPair(countryCode, serverAddress.hostAddress)
            } else {
                dnsLogs.resolver = transaction.serverIp
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
                        val destination = addresses[0]
                        if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "transaction.response - ${destination.address}")
                        val countryCode: String = getCountryCode(destination, context) //TODO : Check on the country code stuff
                        dnsLogs.response = makeAddressPair(countryCode, destination.hostAddress)
                        if (destination.hostAddress.contains("0.0.0.0")) {
                            dnsLogs.isBlocked = true
                        }

                        if (destination.isAnyLocalAddress) {
                            if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Local address: ${destination.hostAddress}")
                            dnsLogs.isBlocked = true
                        } else if (destination.hostAddress == "::0" || destination.hostAddress == "::1") {
                            if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Local equals(::0): ${destination.hostAddress}")
                            dnsLogs.isBlocked = true
                        }
                        if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "transaction.response - ${destination.hostAddress}")
                        dnsLogs.flag = getFlag(countryCode)
                    } else {
                        dnsLogs.response = "NXDOMAIN"
                        dnsLogs.flag = context.getString(R.string.unicode_question_sign) // White question mark
                    }
                } else {
                    dnsLogs.response = err
                    dnsLogs.flag = context.getString(R.string.unicode_warning_sign) // Warning sign
                }
            } else {
                dnsLogs.response = transaction.status.name
                dnsLogs.flag = if (transaction.status === Transaction.Status.CANCELED) {
                    context.getString(R.string.unicode_x_sign)// "X" mark
                } else {
                    context.getString(R.string.unicode_warning_sign) // Warning sign
                }
            }
            if (dnsLogs.isBlocked) {
                persistentState.incrementBlockedReq()
            }
            persistentState.setLifetimeQueries()
            dnsLogRepository.insertAsync(dnsLogs)
            fetchFavIcon(dnsLogs)
        }
    }

    private fun fetchFavIcon(dnsLogs: DNSLogs) {
        if (persistentState.fetchFavIcon) {
            if (dnsLogs.status == Transaction.Status.COMPLETE.toString() && dnsLogs.response != Constants.NXDOMAIN && !dnsLogs.isBlocked) {
                val url = "${Constants.FAV_ICON_URL}${dnsLogs.queryStr}ico"
                if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide - fetchFavIcon() -$url")
                val favIconFetcher = FavIconDownloader(context, url)
                favIconFetcher.run()
            }
        }
    }
}
