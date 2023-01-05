/*
 * Copyright 2023 RethinkDNS and its authors
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

@Entity(primaryKeys = ["pack", "level"], tableName = "RemoteBlocklistPacksMap")
class RemoteBlocklistPacksMap {
    var pack: String
    var level: Int
    var blocklistIds: List<Int> = arrayListOf()
    var group: String

    override fun equals(other: Any?): Boolean {
        if (other !is LocalBlocklistPacksMap) return false
        if (pack != other.pack) return false
        if (level != other.level) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.pack.hashCode()
        result += result * 31 + this.level.hashCode()
        return result
    }

    constructor(pack: String, level: Int, blocklistIds: List<Int>, group: String) {
        this.pack = pack
        this.level = level
        this.blocklistIds = blocklistIds
        this.group = group
    }
}
