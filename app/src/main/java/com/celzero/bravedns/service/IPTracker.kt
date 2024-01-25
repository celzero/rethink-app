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
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.IPUtil
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL_LOG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getCountryCode
import com.celzero.bravedns.util.Utilities.getFlag
import com.celzero.bravedns.util.Utilities.getPackageInfoForUid
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import org.koin.core.component.KoinComponent
import java.net.InetAddress

class IPTracker
internal constructor(
    private val connectionTrackerRepository: ConnectionTrackerRepository,
    private val rethinkLogRepository: RethinkLogRepository,
    private val context: Context
) : KoinComponent {

    companion object {
        private const val PER_USER_RANGE = 100000
    }

    suspend fun makeConnectionTracker(connTrackerMetaData: ConnTrackerMetaData): ConnectionTracker {
        val connTracker = ConnectionTracker()
        connTracker.ipAddress = connTrackerMetaData.destIP
        connTracker.isBlocked = connTrackerMetaData.isBlocked
        connTracker.uid = connTrackerMetaData.uid
        connTracker.usrId = connTrackerMetaData.usrId
        connTracker.port = connTrackerMetaData.destPort
        connTracker.protocol = connTrackerMetaData.protocol
        connTracker.timeStamp = connTrackerMetaData.timestamp
        connTracker.blockedByRule = connTrackerMetaData.blockedByRule
        connTracker.blocklists = connTrackerMetaData.blocklists
        connTracker.proxyDetails = connTrackerMetaData.proxyDetails
        connTracker.connId = connTrackerMetaData.connId
        connTracker.connType = connTrackerMetaData.connType

        val serverAddress = convertIpV6ToIpv4IfNeeded(connTrackerMetaData.destIP)
        connTracker.dnsQuery = connTrackerMetaData.query

        val countryCode: String? = getCountryCode(serverAddress, context)
        connTracker.flag = getFlag(countryCode)

        connTracker.appName = fetchApplicationName(connTracker.uid)

        return connTracker
    }

    suspend fun makeRethinkLogs(connTrackerMetaData: ConnTrackerMetaData): RethinkLog {
        val rlog = RethinkLog()
        rlog.ipAddress = connTrackerMetaData.destIP
        rlog.isBlocked = connTrackerMetaData.isBlocked
        rlog.uid = connTrackerMetaData.uid
        rlog.port = connTrackerMetaData.destPort
        rlog.protocol = connTrackerMetaData.protocol
        rlog.timeStamp = connTrackerMetaData.timestamp
        rlog.proxyDetails = connTrackerMetaData.proxyDetails
        rlog.connId = connTrackerMetaData.connId
        rlog.connType = connTrackerMetaData.connType

        val serverAddress = convertIpV6ToIpv4IfNeeded(connTrackerMetaData.destIP)
        rlog.dnsQuery = connTrackerMetaData.query

        val countryCode: String? = getCountryCode(serverAddress, context)
        rlog.flag = getFlag(countryCode)

        rlog.appName = fetchApplicationName(rlog.uid)

        return rlog
    }

    suspend fun insertBatch(conns: List<ConnectionTracker>) {
        connectionTrackerRepository.insertBatch(conns)
    }

    suspend fun insertRethinkBatch(conns: List<RethinkLog>) {
        rethinkLogRepository.insertBatch(conns)
    }

    suspend fun updateBatch(summary: List<ConnectionSummary>) {
        connectionTrackerRepository.updateBatch(summary)
    }

    suspend fun updateRethinkBatch(summary: List<ConnectionSummary>) {
        rethinkLogRepository.updateBatch(summary)
    }

    private fun convertIpV6ToIpv4IfNeeded(ip: String): InetAddress? {
        val inetAddress = HostName(ip).toInetAddress()
        val ipAddress = IPAddressString(ip).address ?: return inetAddress

        // no need to check if IP is not of type IPv6
        if (!IPUtil.isIpV6(ipAddress)) return inetAddress

        val ipv4 = IPUtil.toIpV4(ipAddress)

        return if (ipv4 != null) {
            ipv4.toInetAddress()
        } else {
            inetAddress
        }
    }

    private suspend fun fetchApplicationName(_uid: Int): String {
        // Returns the app id (base uid) for a given uid, stripping out the user id from it.
        // http://androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/os/UserHandle.java#224
        val uid = _uid % PER_USER_RANGE

        if (uid == INVALID_UID) {
            return context.getString(R.string.network_log_app_name_unknown)
        }

        val packageNameList = getPackageInfoForUid(context, uid)

        val appName: String =
            if (packageNameList != null && packageNameList.isNotEmpty()) {
                val packageName = packageNameList[0]
                getValidAppName(uid, packageName)
            } else { // For UNKNOWN or Non-App.
                val fileSystemUID = AndroidUidConfig.fromFileSystemUid(uid)
                Log.i(
                    LOG_TAG_FIREWALL_LOG,
                    "App name for the uid: ${uid}, AndroidUid: ${fileSystemUID.uid}, fileName: ${fileSystemUID.name}"
                )

                if (fileSystemUID.uid == INVALID_UID) {
                    context.getString(R.string.network_log_app_name_unnamed, uid.toString())
                } else {
                    fileSystemUID.name
                }
            }
        return appName
    }

    private suspend fun getValidAppName(uid: Int, packageName: String): String {
        var appName = FirewallManager.getAppNameByUid(uid)

        if (appName.isNullOrEmpty()) {
            val appInfo = Utilities.getApplicationInfo(context, packageName) ?: return ""

            Log.i(
                LOG_TAG_FIREWALL_LOG,
                "app, $appName, in PackageManager's list not tracked by FirewallManager"
            )
            appName = context.packageManager.getApplicationLabel(appInfo).toString()
        }
        return appName
    }
}
