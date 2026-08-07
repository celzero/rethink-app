/*
Copyright 2020 RethinkDNS developers

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
package com.celzero.bravedns.database

import androidx.room.ColumnInfo

/**
 * Read-only POJO used to display both ConnectionTracker and RethinkLog rows in a single list.
 * The [source] column distinguishes the origin table.
 */
data class MergedConnectionLog(
    @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "appName") val appName: String,
    @ColumnInfo(name = "uid") val uid: Int,
    @ColumnInfo(name = "packageName") val packageName: String?,
    @ColumnInfo(name = "usrId") val usrId: Int,
    @ColumnInfo(name = "ipAddress") val ipAddress: String,
    @ColumnInfo(name = "port") val port: Int,
    @ColumnInfo(name = "protocol") val protocol: Int,
    @ColumnInfo(name = "isBlocked") val isBlocked: Boolean,
    @ColumnInfo(name = "blockedByRule") val blockedByRule: String,
    @ColumnInfo(name = "blocklists") val blocklists: String,
    @ColumnInfo(name = "proxyDetails") val proxyDetails: String,
    @ColumnInfo(name = "flag") val flag: String,
    @ColumnInfo(name = "dnsQuery") val dnsQuery: String?,
    @ColumnInfo(name = "timeStamp") val timeStamp: Long,
    @ColumnInfo(name = "connId") val connId: String,
    @ColumnInfo(name = "downloadBytes") val downloadBytes: Long,
    @ColumnInfo(name = "uploadBytes") val uploadBytes: Long,
    @ColumnInfo(name = "duration") val duration: Int,
    @ColumnInfo(name = "synack") val synack: Long,
    @ColumnInfo(name = "rpid") val rpid: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "connType") val connType: String
) {
    companion object {
        const val SOURCE_CONNECTION_TRACKER = "ct"
        const val SOURCE_RETHINK_LOG = "rethink"
    }

    fun isConnectionTracker(): Boolean {
        return source == SOURCE_CONNECTION_TRACKER
    }

    fun isRethinkLog(): Boolean {
        return source == SOURCE_RETHINK_LOG
    }
}

fun MergedConnectionLog.toConnectionTracker(): ConnectionTracker {
    val ct = ConnectionTracker()
    ct.id = this.id
    ct.appName = this.appName
    ct.uid = this.uid
    ct.packageName = this.packageName ?: ""
    ct.usrId = this.usrId
    ct.ipAddress = this.ipAddress
    ct.port = this.port
    ct.protocol = this.protocol
    ct.isBlocked = this.isBlocked
    ct.blockedByRule = this.blockedByRule
    ct.blocklists = this.blocklists
    ct.proxyDetails = this.proxyDetails
    ct.flag = this.flag
    ct.dnsQuery = this.dnsQuery
    ct.timeStamp = this.timeStamp
    ct.connId = this.connId
    ct.downloadBytes = this.downloadBytes
    ct.uploadBytes = this.uploadBytes
    ct.duration = this.duration
    ct.synack = this.synack
    ct.rpid = this.rpid
    ct.message = this.message
    ct.connType = this.connType
    return ct
}

fun MergedConnectionLog.toRethinkLog(): RethinkLog {
    val log = RethinkLog()
    log.id = this.id
    log.appName = this.appName
    log.uid = this.uid
    log.usrId = this.usrId
    log.ipAddress = this.ipAddress
    log.port = this.port
    log.protocol = this.protocol
    log.isBlocked = this.isBlocked
    log.blockedByRule = this.blockedByRule
    log.blocklists = this.blocklists
    log.proxyDetails = this.proxyDetails
    log.flag = this.flag
    log.dnsQuery = this.dnsQuery
    log.timeStamp = this.timeStamp
    log.connId = this.connId
    log.downloadBytes = this.downloadBytes
    log.uploadBytes = this.uploadBytes
    log.duration = this.duration
    log.synack = this.synack
    log.rpid = this.rpid
    log.message = this.message
    log.connType = this.connType
    return log
}
