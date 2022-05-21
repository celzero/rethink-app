/*
Copyright 2022 RethinkDNS and its authors

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
import com.celzero.bravedns.util.Constants

@Entity(primaryKeys = ["name", "uid"], tableName = "RethinkDnsEndpoint")
class RethinkDnsEndpoint {
    var name: String = ""
    var url: String = ""
    var uid: Int = Constants.MISSING_UID
    var desc: String = ""
    var isActive: Boolean = true
    var isCustom: Boolean = true
    var latency: Int = 0
    var modifiedDataTime: Long = Constants.INIT_TIME_MS

    override fun equals(other: Any?): Boolean {
        if (other !is RethinkDnsEndpoint) return false
        if (uid != other.uid) return false
        if (url != other.url) return false
        return true
    }

    override fun hashCode(): Int {
        var result = 0
        result += result * 31 + this.uid.hashCode()
        result += result * 31 + this.url.hashCode()
        return result
    }


    constructor(name: String, url: String, uid: Int, desc: String, isActive: Boolean,
                isCustom: Boolean, latency: Int, modifiedDataTime: Long) {
        this.name = name
        this.url = url
        this.uid = uid
        this.desc = desc
        this.isActive = isActive
        this.isCustom = isCustom
        if (modifiedDataTime != Constants.INIT_TIME_MS) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
        this.latency = latency
    }

    fun isDeletable(): Boolean {
        return isCustom && !isActive && uid != Constants.MISSING_UID
    }

    fun isAppSpecificEndpoint(): Boolean {
        return uid != Constants.MISSING_UID
    }
}
