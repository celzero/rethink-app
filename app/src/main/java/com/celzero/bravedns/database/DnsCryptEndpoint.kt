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
import androidx.room.PrimaryKey
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS

@Entity(tableName = "DNSCryptEndpoint")
class DnsCryptEndpoint {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var dnsCryptName: String = ""
    var dnsCryptURL: String = ""
    var dnsCryptExplanation: String = ""
    var isSelected: Boolean = true
    var isCustom: Boolean = true
    var modifiedDataTime: Long = INIT_TIME_MS
    var latency: Int = 0

    override fun equals(other: Any?): Boolean {
        if (other !is DnsCryptEndpoint) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }

    constructor(
        id: Int,
        dnsCryptName: String,
        dnsCryptURL: String,
        dnsCryptExplanation: String,
        isSelected: Boolean,
        isCustom: Boolean,
        modifiedDataTime: Long,
        latency: Int
    ) {
        // Room auto-increments id when its set to zero.
        // A non-zero id overrides and sets caller-specified id instead.
        this.id = id
        this.dnsCryptName = dnsCryptName
        this.dnsCryptURL = dnsCryptURL
        this.dnsCryptExplanation = dnsCryptExplanation
        this.isSelected = isSelected
        this.isCustom = isCustom
        if (modifiedDataTime <= INIT_TIME_MS) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
        this.latency = latency
    }

    fun isDeletable(): Boolean {
        return isCustom && !isSelected
    }
}
