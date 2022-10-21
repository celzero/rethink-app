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
class ProxyEndpoint// Room auto-increments id when its set to zero.
// A non-zero id overrides and sets caller-specified id instead.
    (
    @PrimaryKey(autoGenerate = true) var id: Int,
    var proxyName: String,//Set as 1 for Socks5
    var proxyMode: Int,
    var proxyType: String,
    proxyAppName: String,
    proxyIP: String,
    var proxyPort: Int,
    userName: String,
    password: String,
    var isSelected: Boolean,
    var isCustom: Boolean,
    var isUDP: Boolean,
    modifiedDataTime: Long,
    var latency: Int
) {

    var proxyAppName: String? = proxyAppName
    var proxyIP: String? = proxyIP
    var userName: String? = userName
    var password: String? = password
    var modifiedDataTime: Long = INIT_TIME_MS

    override fun equals(other: Any?): Boolean {
        if (other !is ProxyEndpoint) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }


    init {
        if (modifiedDataTime != 0L) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
    }

}
