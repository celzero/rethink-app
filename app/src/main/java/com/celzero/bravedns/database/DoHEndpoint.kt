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

@Entity(tableName = "DoHEndpoint")
class DoHEndpoint {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    var dohName: String = ""
    var dohURL: String = ""
    var dohExplanation: String? = null
    var isSelected: Boolean = true
    var isCustom: Boolean = true
    var modifiedDataTime: Long = 0L
    var latency: Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as DoHEndpoint
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.hashCode()
    }


    constructor(id: Int, dohName: String, dohURL: String, dohExplanation: String, isSelected: Boolean, isCustom: Boolean, modifiedDataTime: Long, latency: Int) {
        if(id != -1)
            this.id = id
        this.dohName = dohName
        this.dohURL = dohURL
        this.dohExplanation = dohExplanation
        this.isSelected = isSelected
        this.isCustom = isCustom
        if(modifiedDataTime != 0L)
            this.modifiedDataTime = modifiedDataTime
        else
            this.modifiedDataTime = System.currentTimeMillis()
        this.latency = latency
    }

}