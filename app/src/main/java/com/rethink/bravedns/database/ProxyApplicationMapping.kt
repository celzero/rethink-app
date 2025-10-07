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
package com.rethinkdns.retrixed.database

import android.Manifest
import android.content.pm.PackageManager
import androidx.room.Entity
import com.rethinkdns.retrixed.database.AppInfoRepository.Companion.NO_PACKAGE_PREFIX

@Entity(tableName = "ProxyApplicationMapping", primaryKeys = ["uid", "packageName", "proxyId"])
class ProxyApplicationMapping {

    var uid: Int = 0
    var packageName: String = ""
    var proxyId: String = ""
    var appName: String = ""
    var proxyName: String = ""
    var isActive: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (other !is ProxyApplicationMapping) return false
        if (packageName != other.packageName) return false
        if (uid != other.uid) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.uid.hashCode()
        result += result * 31 + this.packageName.hashCode()
        return result
    }

    constructor(
        uid: Int,
        packageName: String,
        appName: String,
        proxyName: String,
        isActive: Boolean,
        proxyId: String
    ) {
        this.uid = uid
        this.packageName = packageName
        this.appName = appName
        this.proxyName = proxyName
        this.isActive = isActive
        this.proxyId = proxyId
    }

    fun hasInternetPermission(packageManager: PackageManager): Boolean {
        if (packageName.startsWith(NO_PACKAGE_PREFIX)) return true

        // INTERNET permission if defined, can not be denied so this is safe to use
        return packageManager.checkPermission(
            Manifest.permission.INTERNET,
            packageName
        ) == PackageManager.PERMISSION_GRANTED
    }
}
