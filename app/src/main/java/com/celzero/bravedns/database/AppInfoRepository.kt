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
import com.celzero.bravedns.data.DataUsage

class AppInfoRepository(private val appInfoDAO: AppInfoDAO) {

    companion object {
        // No package applications
        const val NO_PACKAGE_PREFIX = "no_package_"
    }

    suspend fun delete(appInfo: AppInfo) {
        appInfoDAO.delete(appInfo)
    }

    suspend fun insert(appInfo: AppInfo): Long {
        return appInfoDAO.insert(appInfo)
    }

    suspend fun updateUid(oldUid: Int, uid: Int, pkg: String): Int {
        val isExist = appInfoDAO.isUidPkgExist(uid, pkg) != null
        if (isExist) {
            // already app is present with the new uid, so no need to update
            // in that case, old uid with same pkg should be deleted as we intend to update the uid
            appInfoDAO.deletePackage(oldUid, pkg)
            return 0
        }

        return appInfoDAO.updateUid(oldUid, pkg, uid)
    }

    suspend fun hasUidConflict(uid: Int, packageName: String): Boolean {
        val existingApps = appInfoDAO.getAppInfoByUid(uid)
        // Check if there are any apps with this UID that have different package names
        return existingApps.any { it.packageName != packageName }
    }

    suspend fun cleanupUidConflicts(uid: Int, newPackageName: String) {
        val conflictingApps = appInfoDAO.getAppInfoByUid(uid)
        // Remove any apps with the same UID but different package names
        conflictingApps.forEach { existingApp ->
            if (existingApp.packageName != newPackageName) {
                appInfoDAO.deletePackage(uid, existingApp.packageName)
            }
        }
    }

    suspend fun deleteByPackageName(packageNames: List<String>) {
        appInfoDAO.deleteByPackageName(packageNames)
    }

    suspend fun deletePackage(uid: Int, packageName: String?) {
        if (packageName == null) {
            appInfoDAO.deleteByUid(uid)
        } else {
            appInfoDAO.deletePackage(uid, packageName)
        }
    }

    suspend fun tombstoneApp(uid: Int, packageName: String?, tombstoneTs: Long) {
        if (packageName == null) {
            appInfoDAO.tombstoneApp(uid, tombstoneTs)
            return
        }
        appInfoDAO.tombstoneApp(uid, packageName, tombstoneTs)
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

    suspend fun getDataUsageByUid(uid: Int): DataUsage? {
        return appInfoDAO.getDataUsageByUid(uid)
    }

    suspend fun updateDataUsageByUid(uid: Int, uploadBytes: Long, downloadBytes: Long) {
        appInfoDAO.updateDataUsageByUid(uid, uploadBytes, downloadBytes)
    }

    suspend fun updateProxyExcluded(uid: Int, isProxyExcluded: Boolean) {
        appInfoDAO.updateProxyExcluded(uid, isProxyExcluded)
    }

    suspend fun resetRethinkAppFirewallMode() {
        appInfoDAO.resetRethinkAppFirewallMode()
    }

    suspend fun getAppInfoUidForPackageName(packageName: String): Int {
        return appInfoDAO.getAppInfoUidForPackageName(packageName)
    }
}
