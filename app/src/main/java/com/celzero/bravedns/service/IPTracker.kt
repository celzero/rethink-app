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

import Logger
import Logger.LOG_TAG_FIREWALL
import android.content.Context
import com.celzero.bravedns.R
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.EMPTY_PACKAGE_NAME
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.IPUtil
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getCountryCode
import com.celzero.bravedns.util.Utilities.getFlag
import com.celzero.bravedns.util.Utilities.getPackageInfoForUid
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import org.koin.core.component.KoinComponent
import java.net.InetAddress

class IPTracker
internal constructor(
    private val connectionTrackerRepository: ConnectionTrackerRepository,
    private val rethinkLogRepository: RethinkLogRepository,
    private val ctx: Context
) : KoinComponent {

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
        connTracker.downloadBytes = connTrackerMetaData.downloadBytes
        connTracker.uploadBytes = connTrackerMetaData.uploadBytes
        connTracker.rpid = connTrackerMetaData.rpid
        connTracker.synack = connTrackerMetaData.synack
        connTracker.duration = connTrackerMetaData.duration
        connTracker.blockedByRule = connTrackerMetaData.blockedByRule
        connTracker.message = connTrackerMetaData.message

        val serverAddress = convertIpV6ToIpv4IfNeeded(connTrackerMetaData.destIP)
        connTracker.dnsQuery = connTrackerMetaData.query

        val countryCode: String? = getCountryCode(serverAddress, ctx)
        connTracker.flag = getFlag(countryCode)

        // returns pair of appName and packageName
        val appNamePackagePair = fetchAppPackageName(connTracker.uid)
        connTracker.appName = appNamePackagePair.first
        connTracker.packageName = appNamePackagePair.second

        return connTracker
    }

    suspend fun makeRethinkLogs(connTrackerMetaData: ConnTrackerMetaData): RethinkLog {
        val rlog = RethinkLog()
        rlog.ipAddress = connTrackerMetaData.destIP
        rlog.isBlocked = connTrackerMetaData.isBlocked
        rlog.uid = connTrackerMetaData.uid
        rlog.usrId = connTrackerMetaData.usrId
        rlog.port = connTrackerMetaData.destPort
        rlog.protocol = connTrackerMetaData.protocol
        rlog.timeStamp = connTrackerMetaData.timestamp
        rlog.proxyDetails = connTrackerMetaData.proxyDetails
        rlog.connId = connTrackerMetaData.connId
        rlog.connType = connTrackerMetaData.connType
        rlog.downloadBytes = connTrackerMetaData.downloadBytes
        rlog.uploadBytes = connTrackerMetaData.uploadBytes
        rlog.rpid = connTrackerMetaData.rpid
        rlog.synack = connTrackerMetaData.synack
        rlog.duration = connTrackerMetaData.duration
        rlog.message = connTrackerMetaData.message

        val serverAddress = convertIpV6ToIpv4IfNeeded(connTrackerMetaData.destIP)
        rlog.dnsQuery = connTrackerMetaData.query

        val countryCode: String? = getCountryCode(serverAddress, ctx)
        rlog.flag = getFlag(countryCode)

        // no need to use package name for rethink logs
        rlog.appName = fetchAppPackageName(rlog.uid).first

        return rlog
    }

    suspend fun makeSummaryWithTarget(s: ConnectionSummary): ConnectionSummary {
        if (s.targetIp.isNullOrEmpty()) {
            return s
        }
        val serverAddress = convertIpV6ToIpv4IfNeeded(s.targetIp)
        val countryCode: String? = getCountryCode(serverAddress, ctx)
        s.flag = getFlag(countryCode)
        return s
    }

    suspend fun insertBatch(logs: List<*>) {
        val conns = logs as? List<ConnectionTracker> ?: return
        connectionTrackerRepository.insertBatch(conns)
    }

    suspend fun insertRethinkBatch(logs: List<*>) {
        val conns = logs as? List<RethinkLog> ?: return
        rethinkLogRepository.insertBatch(conns)
    }

    suspend fun updateBatch(logs: List<*>) {
        val smms = logs as? List<ConnectionSummary> ?: return
        connectionTrackerRepository.updateBatch(smms)
    }

    suspend fun updateRethinkBatch(logs: List<*>) {
        val smms = logs as? List<ConnectionSummary> ?: return
        rethinkLogRepository.updateBatch(smms)
    }

    private fun convertIpV6ToIpv4IfNeeded(ip: String): InetAddress? {
        // ip maybe a wildcard, so we need to check if it is a valid IP
        if (ip.isEmpty() || isUnspecifiedIp(ip)) return null
        try {
            val inetAddress = HostName(ip).toInetAddress()
            val ipAddress = IPAddressString(ip).address ?: return inetAddress

            // no need to check if IP is not of type IPv6
            if (!IPUtil.isIpV6(ipAddress)) return inetAddress

            val ipv4 = IPUtil.ip4in6(ipAddress)

            return if (ipv4 != null) {
                ipv4.toInetAddress()
            } else {
                inetAddress
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_FIREWALL, "err while converting IP to InetAddress: $ip")
        }
        return null
    }

    private suspend fun fetchAppPackageName(uid: Int): Pair<String, String> {
        if (uid == INVALID_UID) {
            return Pair(ctx.getString(R.string.network_log_app_name_unknown), EMPTY_PACKAGE_NAME)
        }

        val cachedPkgs = FirewallManager.getPackageNamesByUid(uid)

        val pkgs = cachedPkgs.ifEmpty {
            // query the package manager for the package name
            getPackageInfoForUid(ctx, uid)?.toList() ?: emptyList()
        }

        val appName: String =
            if (pkgs.isNotEmpty()) {
                appNameForUidOrPackage(uid, pkgs.firstOrNull() ?: EMPTY_PACKAGE_NAME)
            } else { // For UNKNOWN or Non-App.
                val androidUidConfig = AndroidUidConfig.fromFileSystemUid(uid)
                Logger.i(
                    LOG_TAG_FIREWALL,
                    "android-uid for $uid is uid: ${androidUidConfig.uid}, n: ${androidUidConfig.name}"
                )

                if (androidUidConfig.uid == INVALID_UID) {
                    ctx.getString(R.string.network_log_app_name_unnamed, uid.toString())
                } else {
                    androidUidConfig.name
                }
            }
        return Pair(appName, pkgs.firstOrNull() ?: EMPTY_PACKAGE_NAME)
    }

    private suspend fun appNameForUidOrPackage(uid: Int, packageName: String): String {
        var appName = FirewallManager.getAppNameByUid(uid)

        if (appName.isNullOrEmpty()) {
            val appInfo = Utilities.getApplicationInfo(ctx, packageName) ?: return ""

            Logger.i(LOG_TAG_FIREWALL, "app, $appName, not tracked by FirewallManager")
            appName = try {
                ctx.packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                // fallback if base.apk is not accessible
                ctx.getString(R.string.network_log_app_name_unnamed, uid.toString())
            }
        }
        return appName
    }
}
