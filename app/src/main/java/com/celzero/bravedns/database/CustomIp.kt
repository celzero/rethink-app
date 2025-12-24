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

import androidx.room.Entity
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import java.io.Serializable

/**
 * The CustomIp table will contain the firewall rules based on IP address, port and protocol.
 *
 * The rules will be added to the database with the combination of uid, ipaddress, port, protocol.
 * Special case: when the uid is assigned as FirewallRules#EVERYBODY_UID then the rules with
 * combination of ipaddress, port, protocol will be applied for all the available apps.
 */
@Entity(primaryKeys = ["uid", "ipAddress", "port", "protocol"], tableName = "CustomIp")
class CustomIp : Serializable {
    var uid: Int = UID_EVERYBODY
    var ipAddress: String = ""
    var port: Int = UNSPECIFIED_PORT
    var protocol: String = ""
    var isActive: Boolean = true
    var proxyId: String = ""
    var proxyCC: String = ""

    // BLOCK(0), WHITELIST(1), NONE(2)
    var status: Int = 0
    var wildcard: Boolean = false

    // fixme: Is this needed in database as column?
    // IPV4(0), IPV4_WILDCARD(1), IPV6(2), IPV6_WILDCARD(3)
    var ruleType: Int = 0
    var modifiedDateTime: Long = INIT_TIME_MS

    // Deep copy to prevent in-place mutation issues with paging diff util
    fun deepCopy(): CustomIp {
        val c = CustomIp()
        c.uid = uid
        c.ipAddress = ipAddress
        c.port = port
        c.protocol = protocol
        c.isActive = isActive
        c.proxyId = proxyId
        c.proxyCC = proxyCC
        c.status = status
        c.wildcard = wildcard
        c.ruleType = ruleType
        c.modifiedDateTime = modifiedDateTime
        return c
    }

    override fun equals(other: Any?): Boolean {
        if (other !is CustomIp) return false
        if (ipAddress != other.ipAddress) return false
        if (port != other.port) return false
        if (uid != other.uid) return false
        if (status != other.status) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.uid.hashCode()
        result += result * 31 + this.ipAddress.hashCode()
        result += result * 31 + this.port
        result += result * 31 + this.status
        return result
    }

    fun getCustomIpAddress(): Pair<IPAddress, Int>? {
        try {
            val ip = IPAddressString(ipAddress).address
            return Pair(ip, port)
        } catch (_: Exception) {
            return null
        }
    }
}
