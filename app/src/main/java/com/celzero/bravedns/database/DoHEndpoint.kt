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
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS

@Entity(tableName = "DoHEndpoint")
class DoHEndpoint {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var dohName: String = ""
    var dohURL: String = ""
    var dohExplanation: String? = null
    var isSelected: Boolean = true
    var isCustom: Boolean = true
    var modifiedDataTime: Long = INIT_TIME_MS
    var latency: Int = 0

    override fun equals(other: Any?): Boolean {
        if(other !is DoHEndpoint) return false
        if (dohURL != other.dohURL) return false
        if (isSelected != isSelected) return false
        return true
    }

    override fun hashCode(): Int {
        return this.hashCode()
    }


    constructor(id: Int, dohName: String, dohURL: String, dohExplanation: String,
                isSelected: Boolean, isCustom: Boolean, modifiedDataTime: Long, latency: Int) {

        // Insert methods treat 0 as not-set while inserting the item.
        // The below check is for manual insert of the default Doh entities.
        // For every other entries the id is assigned as -1 so that the
        // autoGenerate parameter will generate the id accordingly.
        if (id != -1) this.id = id
        this.dohName = dohName
        this.dohURL = dohURL
        this.dohExplanation = dohExplanation
        this.isSelected = isSelected
        this.isCustom = isCustom
        if (modifiedDataTime != INIT_TIME_MS) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
        this.latency = latency
    }

    fun isDeletable(): Boolean {
        return isCustom && !isSelected
    }

    fun isRethinkDns(): Boolean {
        return Constants.RETHINK_DNS_PLUS == this.dohName
    }

}
