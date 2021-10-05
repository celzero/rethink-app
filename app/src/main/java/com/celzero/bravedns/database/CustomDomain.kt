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

import android.os.SystemClock
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.celzero.bravedns.automaton.CustomDomainManager
import java.sql.Date

@Entity(tableName = "CustomDomain")
class CustomDomain {
    @PrimaryKey var domain: String = ""
    var ips: String = ""
    var status: Int = 0
    var createdTs: Date = Date(SystemClock.elapsedRealtime())
    var deletedTs: Date = Date(SystemClock.elapsedRealtime())
    var version: Long = getCurrentVersion()

    override fun equals(other: Any?): Boolean {
        if (other !is CustomDomain) return false
        if (domain != other.domain) return false
        return true
    }

    override fun hashCode(): Int {
        return this.domain.hashCode()
    }

    constructor(domain: String, ips: String, status: Int, createdTs: Date, deletedTs: Date,
                version: Long) {
        this.domain = domain
        this.ips = ips
        this.status = status
        this.createdTs = createdTs
        this.deletedTs = deletedTs
        this.version = version
    }

    companion object {
        private const val currentVersion: Long = 1L

        fun getCurrentVersion(): Long {
            return currentVersion
        }
    }

    fun isBlocked(): Boolean {
        return this.status == CustomDomainManager.CustomDomainStatus.BLOCKLIST.statusId
    }

    fun isWhitelisted(): Boolean {
        return this.status == CustomDomainManager.CustomDomainStatus.WHITELIST.statusId
    }
}
