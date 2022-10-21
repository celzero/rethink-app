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

@Entity(tableName = "RethinkLocalFileTag")
class RethinkLocalFileTag(
    @PrimaryKey var value: Int,
    var uname: String,
    var vname: String,
    var group: String,
    var subg: String,
    var url: String,
    var show: Int,
    var entries: Int,
    var simpleTagId: Int,
    var isSelected: Boolean = false
) {

    companion object {
        const val INVALID_SIMPLE_TAG_ID = -1
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RethinkLocalFileTag) return false
        if (value != other.value) return false
        return true
    }

    override fun hashCode(): Int {
        return this.value.hashCode()
    }


}
