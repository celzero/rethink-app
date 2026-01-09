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

import android.content.ContentValues
import android.os.SystemClock
import androidx.room.Entity
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.util.Constants
import java.io.Serializable

@Entity(primaryKeys = ["domain", "uid"], tableName = "CustomDomain")
class CustomDomain : Serializable {
    var domain: String = ""
    var uid: Int = Constants.UID_EVERYBODY
    var ips: String = ""
    var status: Int = 0
    var type: Int = 0
    var proxyId: String = ""
    var proxyCC: String = ""
    var modifiedTs: Long = SystemClock.elapsedRealtime()
    var deletedTs: Long = SystemClock.elapsedRealtime()
    var version: Long = getCurrentVersion()

    override fun equals(other: Any?): Boolean {
        if (other !is CustomDomain) return false
        if (domain != other.domain) return false
        if (status != other.status) return false
        if (modifiedTs != other.modifiedTs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.domain.hashCode()
        result += result * 31 + this.status
        result += result * 31 + this.modifiedTs.hashCode()
        return result
    }

    constructor(values: ContentValues?) {
        if (values != null) {
            val a = values.valueSet()
            a.forEach {
                when (it.key) {
                    "domain" -> domain = it.value as String
                    "uid" -> uid == it.value as Int
                    "ips" -> ips = it.value as String
                    "status" -> status = it.value as Int
                    "type" -> type = it.value as Int
                    "proxyId" -> proxyId = it.value as String
                    "proxyCC" -> proxyCC = it.value as String
                    "modifiedTs" -> modifiedTs = it.value as Long
                    "deletedTs" -> deletedTs = it.value as Long
                    "version" -> version = it.value as Long
                }
            }
        }
    }

    constructor(
        domain: String,
        uid: Int,
        ips: String,
        type: Int,
        status: Int,
        proxyId: String,
        proxyCC: String,
        modifiedTs: Long,
        deletedTs: Long,
        version: Long
    ) {
        this.domain = domain.dropLastWhile { it == '.' }
        this.uid = uid
        this.ips = ips
        this.status = status
        this.type = type
        this.proxyId = proxyId
        this.proxyCC = proxyCC
        this.modifiedTs = modifiedTs
        this.deletedTs = deletedTs
        this.version = version
    }

    companion object {
        private const val CURRENT_VERSION: Long = 1L

        fun getCurrentVersion(): Long {
            return CURRENT_VERSION
        }
    }

    fun isBlocked(): Boolean {
        return this.status == DomainRulesManager.Status.BLOCK.id
    }
}
