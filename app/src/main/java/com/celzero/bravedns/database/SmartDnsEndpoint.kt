/*
 * Copyright 2026 RethinkDNS and its authors
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
import androidx.room.PrimaryKey
import com.celzero.firestack.settings.Settings

@Entity(tableName = "SmartDnsEndpoint")
data class SmartDnsEndpoint(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val dnsName: String,
    // filter mode of the smart dns endpoint, see SmartDnsMode
    val dnsMode: Int,
    val dnsExplanation: String,
    var isSelected: Boolean = false,
    val modifiedDataTime: Long = 0,
    val latency: Int = 0
) {
    companion object {
        const val SMART_DNS_TABLE_NAME = "SmartDnsEndpoint"

        fun isNoFilterMode(mode: Int): Boolean {
            return mode == SmartDnsMode.NO_FILTER.mode
        }

        fun isSecurityMode(mode: Int): Boolean {
            return mode == SmartDnsMode.SECURITY.mode
        }

        fun isFamilyMode(mode: Int): Boolean {
            return mode == SmartDnsMode.FAMILY.mode
        }
    }
}

// supported smart dns filter modes, seeded in AppDatabase.MIGRATION_33_34
enum class SmartDnsMode(val mode: Int) {
    NO_FILTER(0),
    SECURITY(1),
    PRIVACY(2),
    FAMILY(3);

    companion object {
        fun getMode(id: Int): SmartDnsMode {
            return when (id) {
                NO_FILTER.mode -> NO_FILTER
                SECURITY.mode -> SECURITY
                PRIVACY.mode -> PRIVACY
                FAMILY.mode -> FAMILY
                else -> NO_FILTER
            }
        }

        fun getTunMode(id: Int): Long {
            return when (id) {
                NO_FILTER.mode -> Settings.PlusFilterNone
                SECURITY.mode -> Settings.PlusFilterAdblock
                FAMILY.mode -> Settings.PlusFilterAdblock
                PRIVACY.mode -> Settings.PlusFilterAdblock
                else -> Settings.PlusFilterNone
            }
        }
    }
}
