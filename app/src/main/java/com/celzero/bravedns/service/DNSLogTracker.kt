package com.celzero.bravedns.service

import android.content.Context
import android.util.Log
import com.celzero.bravedns.database.AppDatabase
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

class DNSLogTracker(var context: Context?) {

    var mDb : AppDatabase ?= null

   private fun getDBInstance(context : Context): AppDatabase? {
       if(mDb == null) {
           mDb = AppDatabase.invoke(context.applicationContext)
       }
       return mDb
   }

    @Synchronized
    fun recordTransaction(context: Context?, transaction: Transaction?) {
        if (context != null && transaction != null) {
            insertToDB(context, transaction)
        }
    }

    private fun insertToDB(context: Context, transaction: Transaction) {
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = getDBInstance(context)
            val dnsLogRepository = mDb!!.dnsLogRepository()
            val dnsLogs = DNSLogs()

            dnsLogs.blockLists = transaction.blockList
            if(transaction.isDNSCrypt) {
                dnsLogs.dnsType = DNS_TYPE_DNS_CRYPT
                dnsLogs.relayIP = transaction.relayIp
            } else {
                dnsLogs.dnsType = DNS_TYPE_DOH
                dnsLogs.relayIP = ""
            }
            dnsLogs.latency = transaction.responseTime - transaction.queryTime
            dnsLogs.queryStr = transaction.name
            dnsLogs.blockLists = transaction.blockList
            dnsLogs.responseTime = transaction.responseTime
            dnsLogs.serverIP = transaction.serverIp
            dnsLogs.status = transaction.status.name
            dnsLogs.time = transaction.responseCalendar.timeInMillis
            dnsLogs.typeName = Utilities.getTypeName(transaction.type.toInt())

            try {
                val serverAddress = if (transaction.serverIp != null) {
                    InetAddress.getByName(transaction.serverIp)
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
                Log.w(LOG_TAG,"DNSLogTracker - exception while fetching the resolver: ${e.message}",e)
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
            dnsLogRepository.insertAsync(dnsLogs)
        }
    }




}