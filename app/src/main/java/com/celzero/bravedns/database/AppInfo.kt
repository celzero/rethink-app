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

@Entity(tableName = "AppInfo")
class AppInfo {
    @PrimaryKey var packageInfo: String = ""
    var appName: String = ""
    var uid: Int = 0
    var trackers: Int = 0
    var isWifiEnabled: Boolean = true
    var isDataEnabled: Boolean = true
    var isSystemApp: Boolean = false
    var isScreenOff: Boolean = false
    var isInternetAllowed: Boolean = true
    var isBackgroundEnabled: Boolean = true
    var whiteListUniv1: Boolean = false
    var whiteListUniv2: Boolean = false
    var isExcluded: Boolean = false
    var appCategory: String = ""
    var wifiDataUsed: Long = 0
    var mobileDataUsed: Long = 0


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as AppInfo
        if (packageInfo != other.packageInfo) return false
        return true
    }

    override fun hashCode(): Int {
        return this.hashCode()
    }

}
