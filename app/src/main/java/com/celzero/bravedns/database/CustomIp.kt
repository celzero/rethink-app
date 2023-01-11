/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import android.util.Log
import androidx.room.Entity
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.LoggerConstants
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString

/**
 * The CustomIp table will contain the firewall rules based on IP address, port and protocol.
 *
 * The rules will be added to the database with the combination of uid, ipaddress, port, protocol.
 * Special case: when the uid is assigned as FirewallRules#EVERYBODY_UID then the rules with
 * combination of ipaddress, port, protocol will be applied for all the available apps.
 */
@Entity(primaryKeys = ["uid", "ipAddress", "port", "protocol"], tableName = "CustomIp")
class CustomIp {
    var uid: Int = UID_EVERYBODY
    var ipAddress: String = ""
    var port: Int = UNSPECIFIED_PORT
    var protocol: String = ""
    var isActive: Boolean = true

    // BLOCK(0), WHITELIST(1), NONE(2)
    var status: Int = 0
    var wildcard: Boolean = false

    // fixme: Is this needed in database as column?
    // IPV4(0), IPV4_WILDCARD(1), IPV6(2), IPV6_WILDCARD(3)
    var ruleType: Int = 0
    var modifiedDateTime: Long = INIT_TIME_MS

    override fun equals(other: Any?): Boolean {
        if (other !is CustomIp) return false
        if (ipAddress != other.ipAddress && uid != other.uid) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.uid.hashCode()
        result += result * 31 + this.ipAddress.hashCode()
        return result
    }

    fun getCustomIpAddress(): HostName {
        return if (port == UNSPECIFIED_PORT) {
            HostName(ipAddress)
        } else {
            HostName(IPAddressString(ipAddress).address, port)
        }
    }

    // chances of null pointer exception while converting the string object to
    // HostName().address
    // ref: https://seancfoley.github.io/IPAddress/
    fun setCustomIpAddress(ipAddress: String) {
        var ip = ipAddress
        try {
            if (HostName(ipAddress).address.isIPv4) {
                if (ipAddress.count { it == '.' } < 3) {
                    ip = getPaddedIp(ip)
                }
            }
            this.ipAddress = HostName(ip).address.toNormalizedString()
        } catch (ignored: NullPointerException) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "Invalid IP address added", ignored)
            this.ipAddress = ""
        }
    }

    fun setCustomIpAddress(hostName: HostName) {
        try {
            this.ipAddress = hostName.address.toNormalizedString()
        } catch (ignored: NullPointerException) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "Invalid IP address added", ignored)
            this.ipAddress = ""
        }
    }

    private fun getPaddedIp(ip: String): String {
        return if (ip.contains("/")) {
            val index = ip.indexOf("/")
            ip.substring(0, index) + ".*" + ip.substring(index)
        } else {
            "$ip.*"
        }
    }
}
