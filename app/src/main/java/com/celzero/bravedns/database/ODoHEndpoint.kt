/*
 * Copyright 2023 RethinkDNS and its authors
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
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS

@Entity(tableName = "ODoHEndpoint")
class ODoHEndpoint {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var name: String = ""
    var proxy: String = ""
    var resolver: String = ""
    var proxyIps: String = ""
    var desc: String = ""
    var isSelected: Boolean = true
    var isCustom: Boolean = true
    var modifiedDataTime: Long = INIT_TIME_MS
    var latency: Int = 0

    override fun equals(other: Any?): Boolean {
        if (other !is ODoHEndpoint) return false
        if (isSelected != other.isSelected) return false
        if (proxy != other.proxy) return false
        if (resolver != other.resolver) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.proxy.hashCode()
        result += result * 31 + this.isSelected.hashCode()
        return result
    }

    constructor(
        id: Int,
        name: String,
        proxy: String,
        resolver: String,
        proxyIps: String,
        desc: String,
        isSelected: Boolean,
        isCustom: Boolean,
        modifiedDataTime: Long,
        latency: Int
    ) {
        // Room auto-increments id when its set to zero.
        // A non-zero id overrides and sets caller-specified id instead.
        this.id = id
        this.name = name
        this.proxy = proxy
        this.resolver = resolver
        this.proxyIps = proxyIps
        this.desc = desc
        this.isSelected = isSelected
        this.isCustom = isCustom
        if (modifiedDataTime != INIT_TIME_MS) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
        this.latency = latency
    }

    fun isDeletable(): Boolean {
        return isCustom && !isSelected
    }
}
