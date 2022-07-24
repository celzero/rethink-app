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
import android.util.Log
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.IpManager
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL_LOG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.celzero.bravedns.util.Utilities.Companion.getPackageInfoForUid
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetAddress

class IPTracker internal constructor(
        private val connectionTrackerRepository: ConnectionTrackerRepository,
        private val context: Context) : KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val dnsLogTracker by inject<DnsLogTracker>()

    fun recordTransaction(ipDetails: IPDetails?) {
        if (ipDetails == null) return
        if (!persistentState.logsEnabled) return

        //Modified the call of the insert to database inside the coroutine scope.
        io {
            insertToDB(ipDetails)
        }
    }

    private suspend fun insertToDB(ipDetails: IPDetails) {
        connectionTrackerRepository.insert(makeConnectionTracker(ipDetails))
    }

    suspend fun makeConnectionTracker(ipDetails: IPDetails): ConnectionTracker {
        val connTracker = ConnectionTracker()
        connTracker.ipAddress = ipDetails.destIP
        connTracker.isBlocked = ipDetails.isBlocked
        connTracker.uid = ipDetails.uid
        connTracker.port = ipDetails.destPort
        connTracker.protocol = ipDetails.protocol
        connTracker.timeStamp = ipDetails.timestamp
        connTracker.blockedByRule = ipDetails.blockedByRule

        val serverAddress = convertIpV6ToIpv4IfNeeded(ipDetails.destIP)
        connTracker.dnsQuery = ipDetails.query

        val countryCode: String? = getCountryCode(serverAddress, context)
        connTracker.flag = getFlag(countryCode)

        connTracker.appName = fetchApplicationName(connTracker.uid)

        return connTracker
    }

    suspend fun insertBatch(conns: List<ConnectionTracker>) {
        connectionTrackerRepository.insertBatch(conns)
    }

    private fun convertIpV6ToIpv4IfNeeded(ip: String): InetAddress? {
        val inetAddress = HostName(ip)?.toInetAddress()
        val ipAddress = IPAddressString(ip)?.address ?: return inetAddress

        // no need to check if IP is not of type IPv6
        if (!IpManager.isIpV6(ipAddress)) return inetAddress

        val ipv4 = IpManager.toIpV4(ipAddress)

        return if (ipv4 != null) {
            ipv4.toInetAddress()
        } else {
            inetAddress
        }
    }

    private suspend fun fetchApplicationName(uid: Int): String {
        if (uid == INVALID_UID) {
            return context.getString(R.string.network_log_app_name_unknown)
        }

        val packageNameList = getPackageInfoForUid(context, uid)

        val appName: String = if (packageNameList != null && packageNameList.isNotEmpty()) {
            val packageName = packageNameList[0]
            getValidAppName(uid, packageName)
        } else { // For UNKNOWN or Non-App.
            val fileSystemUID = AndroidUidConfig.fromFileSystemUid(uid)
            Log.i(LOG_TAG_FIREWALL_LOG,
                  "App name for the uid: ${uid}, AndroidUid: ${fileSystemUID.uid}, fileName: ${fileSystemUID.name}")

            if (fileSystemUID.uid == INVALID_UID) {
                context.getString(R.string.network_log_app_name_unnamed, uid.toString())
            } else {
                fileSystemUID.name
            }
        }
        return appName
    }

    private fun getValidAppName(uid: Int, packageName: String): String {
        var appName: String? = null

        if (appName.isNullOrEmpty()) {
            appName = FirewallManager.getAppNameByUid(uid)
        }
        if (appName.isNullOrEmpty()) {
            val appInfo = Utilities.getApplicationInfo(context, packageName) ?: return ""

            Log.i(LOG_TAG_FIREWALL_LOG,
                  "app, $appName, in PackageManager's list not tracked by FirewallManager")
            appName = context.packageManager.getApplicationLabel(appInfo).toString()
        }
        return appName
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }

}
