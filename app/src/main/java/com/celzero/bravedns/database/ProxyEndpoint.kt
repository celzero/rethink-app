/*
Copyright 2020 RethinkDNS and its authors

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
import androidx.room.PrimaryKey
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS

@Entity(tableName = "ProxyEndpoint")
class ProxyEndpoint {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var proxyName: String = ""

    // 0 - SOCKS5, 1 - HTTP, 2 - Orbot (SOCKS5), 3 - Orbot (HTTP)
    var proxyMode: Int = 0
    // NONE for now, later we can add other types
    // ideally this should be an enum (ProxyType)
    var proxyType: String = ""
    var proxyAppName: String? = null
    var proxyIP: String? = null
    var proxyPort: Int = 0
    var userName: String? = null
    var password: String? = null
    var isSelected: Boolean = true
    var isCustom: Boolean = true
    var isUDP: Boolean = false
    var modifiedDataTime: Long = INIT_TIME_MS
    var latency: Int = 0

    override fun equals(other: Any?): Boolean {
        if (other !is ProxyEndpoint) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }

    constructor(
        id: Int,
        proxyName: String,
        proxyMode: Int,
        proxyType: String,
        proxyAppName: String,
        proxyIP: String,
        proxyPort: Int,
        userName: String,
        password: String,
        isSelected: Boolean,
        isCustom: Boolean,
        isUDP: Boolean,
        modifiedDataTime: Long,
        latency: Int
    ) {
        this.id = id
        this.proxyMode = proxyMode
        this.proxyName = proxyName
        this.proxyType = proxyType
        this.proxyAppName = proxyAppName
        this.proxyIP = proxyIP
        this.proxyPort = proxyPort
        this.isSelected = isSelected
        this.isCustom = isCustom
        this.isUDP = isUDP
        this.userName = userName
        this.password = password
        if (modifiedDataTime != 0L) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
        this.latency = latency
    }
}
