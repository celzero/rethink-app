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

@Entity(tableName = "DNSProxyEndpoint")
class DNSProxyEndpoint {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var proxyName: String = ""
    var proxyType: String = ""
    var proxyAppName: String? = null
    var proxyIP: String? = null
    var proxyPort: Int = 0
    var isSelected: Boolean = true
    var isCustom: Boolean = true
    var modifiedDataTime: Long = 0L
    var latency: Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as DNSProxyEndpoint
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.hashCode()
    }


    constructor(id: Int, proxyName: String, proxyType: String, proxyAppName: String,
                proxyIP: String, proxyPort: Int, isSelected: Boolean, isCustom: Boolean,
                modifiedDataTime: Long, latency: Int) {
        if (id != -1) this.id = id
        this.proxyName = proxyName
        this.proxyType = proxyType
        this.proxyAppName = proxyAppName
        this.proxyIP = proxyIP
        this.proxyPort = proxyPort
        this.isSelected = isSelected
        this.isCustom = isCustom
        if (modifiedDataTime != 0L) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
        this.latency = latency
    }
}
