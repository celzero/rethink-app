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

    //Set as 1 for Socks5
    var proxyMode: Int = 0
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
        if (other?.javaClass != javaClass) return false
        other as ProxyEndpoint
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.hashCode()
    }


    constructor(id: Int, proxyName: String, proxyMode: Int, proxyType: String, proxyAppName: String,
                proxyIP: String, proxyPort: Int, userName: String, password: String,
                isSelected: Boolean, isCustom: Boolean, isUDP: Boolean, modifiedDataTime: Long,
                latency: Int) {
        // Insert methods treat 0 as not-set while inserting the item.
        // The below check is for manual insert of the default Doh entities.
        // For every other entries the id is assigned as -1 so that the
        // autoGenerate parameter will generate the id accordingly.
        if (id != -1) this.id = id
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
