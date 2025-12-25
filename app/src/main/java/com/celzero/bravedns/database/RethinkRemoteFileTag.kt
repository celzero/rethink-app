/*
 * Copyright 2022 RethinkDNS and its authors
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

@Entity(tableName = "RethinkRemoteFileTag")
class RethinkRemoteFileTag {
    @PrimaryKey var value: Int = 0
    var uname: String = ""
    var vname: String = ""
    var group: String = ""
    var subg: String = ""
    var url: List<String> = arrayListOf()
    var show: Int = 0
    var entries: Int = 0
    var pack: List<String>? = null
    var level: List<Int>? = null
    var simpleTagId: Int = INVALID_SIMPLE_TAG_ID
    var isSelected: Boolean = false

    companion object {
        const val INVALID_SIMPLE_TAG_ID = -1
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RethinkRemoteFileTag) return false
        if (value != other.value) return false
        if (isSelected != other.isSelected) return false
        return true
    }

    override fun hashCode(): Int {
        return this.value.hashCode()
    }

    constructor(
        value: Int,
        uname: String,
        vname: String,
        group: String,
        subg: String,
        pack: List<String>?,
        level: List<Int>?,
        url: List<String>,
        show: Int,
        entries: Int,
        simpleTagId: Int,
        isSelected: Boolean = false
    ) {
        this.value = value
        this.uname = uname
        this.vname = vname
        this.group = group
        this.subg = subg
        this.url = url
        this.show = show
        this.entries = entries
        this.pack = pack
        this.level = level
        this.simpleTagId = simpleTagId
        this.isSelected = isSelected
    }
}
