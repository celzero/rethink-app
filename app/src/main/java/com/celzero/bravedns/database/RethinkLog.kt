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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS

@Entity(
    tableName = "RethinkLog",
    indices =
        [
            Index(value = arrayOf("ipAddress"), unique = false),
            Index(value = arrayOf("appName"), unique = false),
            Index(value = arrayOf("dnsQuery"), unique = false),
            Index(value = arrayOf("connId"), unique = false)
        ]
)
class RethinkLog {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var appName: String = ""
    var uid: Int = 0
    var usrId: Int = 0
    var ipAddress: String = ""
    var port: Int = 0
    var protocol: Int = 0
    var isBlocked: Boolean = false
    var blockedByRule: String = ""
    var blocklists: String = ""
    var proxyDetails: String = ""
    var flag: String = ""
    var dnsQuery: String? = null
    var timeStamp: Long = INIT_TIME_MS
    var connId: String = ""
    var downloadBytes: Long = 0
    var uploadBytes: Long = 0
    var duration: Int = 0
    var synack: Long = 0
    var rpid = "" // relay proxy id
    var message: String = ""
    var connType: String = ""

    override fun equals(other: Any?): Boolean {
        if (other !is RethinkLog) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }

    enum class ConnType(val id: Int, val value: String) {
        NONE(0, "None"),
        METERED(1, "Metered"),
        UNMETERED(2, "Unmetered");

        fun isMetered(): Boolean {
            return this == METERED
        }

        fun isUnmetered(): Boolean {
            return this == UNMETERED
        }

        fun isNone(): Boolean {
            return this == NONE
        }

        companion object {
            fun get(v: Int?): ConnType {
                if (v == null) return NONE
                return when (v) {
                    NONE.id -> NONE
                    METERED.id -> METERED
                    UNMETERED.id -> UNMETERED
                    else -> NONE
                }
            }

            fun get(v: String?): ConnType {
                if (v == null) return NONE
                return when (v) {
                    NONE.value -> NONE
                    METERED.value -> METERED
                    UNMETERED.value -> UNMETERED
                    else -> NONE
                }
            }
        }
    }
}
