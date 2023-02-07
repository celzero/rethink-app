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

import android.database.Cursor

class AppInfoRepository(private val appInfoDAO: AppInfoDAO) {

    companion object {
        // No package applications
        const val NO_PACKAGE = "no_package"
    }

    suspend fun delete(appInfo: AppInfo) {
        appInfoDAO.delete(appInfo)
    }

    suspend fun insert(appInfo: AppInfo): Long {
        return appInfoDAO.insert(appInfo)
    }

    suspend fun deleteByPackageName(packageNames: List<String>) {
        appInfoDAO.deleteByPackageName(packageNames)
    }

    suspend fun getAppInfo(): List<AppInfo> {
        return appInfoDAO.getAllAppDetails()
    }

    suspend fun updateFirewallStatusByUid(uid: Int, firewallStatus: Int, connectionStatus: Int) {
        appInfoDAO.updateFirewallStatusByUid(uid, firewallStatus, connectionStatus)
    }

    fun cpUpdate(appInfo: AppInfo): Int {
        return appInfoDAO.update(appInfo)
    }

    fun cpUpdate(appInfo: AppInfo, clause: String): Int {
        // update only firewall and metered
        return appInfoDAO.cpUpdate(appInfo.firewallStatus, appInfo.connectionStatus, clause)
    }

    fun cpInsert(appInfo: AppInfo): Long {
        return appInfoDAO.insert(appInfo)
    }

    fun getAppsCursor(): Cursor {
        return appInfoDAO.getAllAppDetailsCursor()
    }

    fun cpDelete(uid: Int): Int {
        return appInfoDAO.deleteByUid(uid)
    }
}
