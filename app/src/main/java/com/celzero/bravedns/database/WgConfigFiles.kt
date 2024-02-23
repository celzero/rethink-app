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
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "WgConfigFiles")
class WgConfigFiles {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var name: String = ""
    var configPath: String = ""
    var serverResponse: String = ""
    var isActive: Boolean = false
    var isCatchAll: Boolean = false
    var isLockdown: Boolean = false
    var oneWireGuard: Boolean = false
    var isDeletable: Boolean = true

    override fun equals(other: Any?): Boolean {
        if (other !is WgConfigFiles) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }

    @Ignore
    constructor(
        id: Int,
        name: String,
        configPath: String,
        serverResponse: String,
        isActive: Boolean,
        isCatchAll: Boolean,
        isLockdown: Boolean,
        oneWireGuard: Boolean
    ) {
        this.id = id
        this.name = name
        this.configPath = configPath
        this.serverResponse = serverResponse
        this.isActive = isActive
        this.isCatchAll = isCatchAll
        this.isLockdown = isLockdown
        this.oneWireGuard = oneWireGuard
    }

    constructor(
        name: String,
        configPath: String,
        serverResponse: String,
        isActive: Boolean,
        isCatchAll: Boolean,
        isLockdown: Boolean,
        oneWireGuard: Boolean
    ) {
        this.name = name
        this.configPath = configPath
        this.serverResponse = serverResponse
        this.isActive = isActive
        this.isCatchAll = isCatchAll
        this.isLockdown = isLockdown
        this.oneWireGuard = oneWireGuard
    }
}
