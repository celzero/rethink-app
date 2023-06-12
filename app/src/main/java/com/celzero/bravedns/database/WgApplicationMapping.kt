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

@Entity(tableName = "WgApplicationMapping", primaryKeys = ["uid", "packageName", "wgInterfaceId"])
class WgApplicationMapping {

    var uid: Int = 0
    var packageName: String = ""
    var appName: String = ""
    var wgInterfaceName: String = ""
    var isActive: Boolean = false
    var wgInterfaceId: Int = -1

    override fun equals(other: Any?): Boolean {
        if (other !is WgApplicationMapping) return false
        if (packageName != other.packageName) return false
        return true
    }

    override fun hashCode(): Int {
        return this.packageName.hashCode()
    }

    constructor(
        uid: Int,
        packageName: String,
        appName: String,
        wgInterfaceName: String,
        isActive: Boolean,
        wgInterfaceId: Int
    ) {
        this.uid = uid
        this.packageName = packageName
        this.appName = appName
        this.wgInterfaceName = wgInterfaceName
        this.isActive = isActive
        this.wgInterfaceId = wgInterfaceId
    }
}
