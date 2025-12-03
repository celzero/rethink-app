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
    var oneWireGuard: Boolean = false
    var useOnlyOnMetered: Boolean = false
    var isDeletable: Boolean = true
    // new: ssid based activation
    var ssidEnabled: Boolean = false
    var ssids: String = "" // newline separated list
    var modifiedTs: Long = System.currentTimeMillis()

    override fun equals(other: Any?): Boolean {
        if (other !is WgConfigFiles) return false
        if (id != other.id) return false
        if (name != other.name) return false
        if (isActive != other.isActive) return false
        if (isCatchAll != other.isCatchAll) return false
        if (oneWireGuard != other.oneWireGuard) return false
        if (useOnlyOnMetered != other.useOnlyOnMetered) return false
        if (ssidEnabled != other.ssidEnabled) return false
        if (ssids != other.ssids) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.id.hashCode()
        result += result * 31 + this.name.hashCode()
        result += result * 31 + this.isActive.hashCode()
        result += result * 31 + this.isCatchAll.hashCode()
        result += result * 31 + this.oneWireGuard.hashCode()
        result += result * 31 + this.useOnlyOnMetered.hashCode()
        result += result * 31 + this.ssidEnabled.hashCode()
        result += result * 31 + this.ssids.hashCode()
        return result
    }

    @Ignore
    constructor(
        id: Int,
        name: String,
        configPath: String,
        serverResponse: String,
        isActive: Boolean,
        isCatchAll: Boolean,
        oneWireGuard: Boolean,
        useOnlyOnMetered: Boolean,
        isDeletable: Boolean = true,
        ssidEnabled: Boolean = false,
        ssids: String = ""
    ) {
        this.id = id
        this.name = name
        this.configPath = configPath
        this.serverResponse = serverResponse
        this.isActive = isActive
        this.isCatchAll = isCatchAll
        this.oneWireGuard = oneWireGuard
        this.useOnlyOnMetered = useOnlyOnMetered
        this.isDeletable = isDeletable
        this.ssidEnabled = ssidEnabled
        this.ssids = ssids
    }

    constructor(
        name: String,
        configPath: String,
        serverResponse: String,
        isActive: Boolean,
        isCatchAll: Boolean,
        oneWireGuard: Boolean,
        useOnlyOnMetered: Boolean,
        isDeletable: Boolean,
        ssidEnabled: Boolean = false,
        ssids: String = ""
    ) {
        this.name = name
        this.configPath = configPath
        this.serverResponse = serverResponse
        this.isActive = isActive
        this.isCatchAll = isCatchAll
        this.oneWireGuard = oneWireGuard
        this.useOnlyOnMetered = useOnlyOnMetered
        this.isDeletable = isDeletable
        this.ssidEnabled = ssidEnabled
        this.ssids = ssids
    }

    fun toImmutable(): WgConfigFilesImmutable {
        return WgConfigFilesImmutable(
            id,
            name,
            configPath,
            serverResponse,
            isActive,
            isCatchAll,
            oneWireGuard,
            useOnlyOnMetered,
            isDeletable,
            ssidEnabled,
            ssids
        )
    }

    companion object {
        fun fromImmutable(data: WgConfigFilesImmutable): WgConfigFiles {
            return WgConfigFiles(
                data.id,
                data.name,
                data.configPath,
                data.serverResponse,
                data.isActive,
                data.isCatchAll,
                data.oneWireGuard,
                data.useOnlyOnMetered,
                data.isDeletable,
                data.ssidEnabled,
                data.ssids
            )
        }
    }
}

data class WgConfigFilesImmutable(
    val id: Int,
    val name: String,
    val configPath: String,
    val serverResponse: String,
    val isActive: Boolean,
    val isCatchAll: Boolean,
    val oneWireGuard: Boolean,
    val useOnlyOnMetered: Boolean,
    val isDeletable: Boolean,
    val ssidEnabled: Boolean,
    val ssids: String
)
