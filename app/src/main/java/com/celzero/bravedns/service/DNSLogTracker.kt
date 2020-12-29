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
import com.celzero.bravedns.database.DNSLogRepository
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.net.dns.DnsPacket
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Constants.Companion.DNS_TYPE_DNS_CRYPT
import com.celzero.bravedns.util.Constants.Companion.DNS_TYPE_DOH
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.celzero.bravedns.util.Utilities.Companion.makeAddressPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ProtocolException
import java.net.UnknownHostException

class DNSLogTracker internal constructor(private val dnsLogRepository: DNSLogRepository,
                                         private val persistentState:PersistentState,
                                         private val context: Context) {

    @Synchronized
    fun recordTransaction(transaction: Transaction?) {
        if (transaction != null) {
            insertToDB(transaction)
        }
    }

    private fun insertToDB(transaction: Transaction) {
        GlobalScope.launch(Dispatchers.IO) {
            val dnsLogs = DNSLogs()

            dnsLogs.blockLists = transaction.blockList
            if(transaction.isDNSCrypt) {
                dnsLogs.dnsType = DNS_TYPE_DNS_CRYPT
                dnsLogs.relayIP = transaction.relayIp
            } else {
                dnsLogs.dnsType = DNS_TYPE_DOH
                dnsLogs.relayIP = ""
            }
            dnsLogs.latency = transaction.responseTime// - transaction.queryTime
            dnsLogs.queryStr = transaction.name
            dnsLogs.blockLists = transaction.blockList
            dnsLogs.responseTime = transaction.responseTime
            dnsLogs.serverIP = transaction.serverIp
            dnsLogs.status = transaction.status.name
            dnsLogs.time = transaction.responseCalendar.timeInMillis
            dnsLogs.typeName = Utilities.getTypeName(transaction.type.toInt())

            try {
                val serverAddress = if (transaction.serverIp != null) {
                    try {
                        InetAddress.getByName(transaction.serverIp)
                    }catch(ex : UnknownHostException){
                        null
                    }
                } else {
                    null
                }
                if (serverAddress != null) {
                    val countryCode: String = getCountryCode(serverAddress, context) //TODO: Country code things
                    dnsLogs.resolver = makeAddressPair(countryCode, serverAddress.hostAddress)
                } else {
                    dnsLogs.resolver = transaction.serverIp
                }
            }catch (e: Exception){
                Log.w(LOG_TAG, "DNSLogTracker - exception while fetching the resolver: ${e.message}", e)
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
                        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "transaction.response - ${destination.address}")
                        val countryCode: String = getCountryCode(destination, context) //TODO : Check on the country code stuff
                        dnsLogs.response = makeAddressPair(countryCode, destination.hostAddress)
                        if (destination.hostAddress.contains("0.0.0.0")){
                            dnsLogs.isBlocked = true
                        }

                        if (destination.isAnyLocalAddress) {
                            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "Local address: ${destination.hostAddress}")
                            dnsLogs.isBlocked = true
                        } else if (destination.hostAddress == "::0" || destination.hostAddress == "::1") {
                            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "Local equals(::0): ${destination.hostAddress}")
                            dnsLogs.isBlocked = true
                        }
                        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "transaction.response - ${destination.hostAddress}")
                        dnsLogs.flag = getFlag(countryCode)
                    } else {
                        dnsLogs.response = "NXDOMAIN"
                        dnsLogs.flag = "\u2754" // White question mark
                    }
                } else {
                    dnsLogs.response = err
                    dnsLogs.flag = "\u26a0" // Warning sign
                }
            } else {
                dnsLogs.response = transaction.status.name
                dnsLogs.flag = if (transaction.status === Transaction.Status.CANCELED) {
                    "\u274c" // "X" mark
                } else {
                    "\u26a0" // Warning sign
                }
            }
            if(dnsLogs.isBlocked){
                persistentState.incrementBlockedReq()
            }
            persistentState.setNumOfReq()
            dnsLogRepository.insertAsync(dnsLogs)
        }
    }
}
