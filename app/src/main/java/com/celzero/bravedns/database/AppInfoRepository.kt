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
        appInfo.modifiedTs = System.currentTimeMillis()
        return appInfoDAO.insert(appInfo)
    }

    suspend fun updateUid(oldUid: Int, newUid: Int, pkg: String): Int {
        val isExist = appInfoDAO.isUidPkgExist(newUid, pkg) != null
        if (isExist) {
            // already app is present with the new uid, so no need to update
            // in that case, old uid with same pkg should be deleted as we intend to update the uid
            appInfoDAO.deletePackage(oldUid, pkg)
            return 0
        }
        val modifiedTs = System.currentTimeMillis()
        return appInfoDAO.updateUid(oldUid, pkg, newUid, modifiedTs)
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

    suspend fun tombstoneApp(oldUid: Int, newUid: Int, packageName: String?, tombstoneTs: Long) {
        val modifiedTs = System.currentTimeMillis()
        try {
            if (packageName == null) {
                appInfoDAO.tombstoneApp(oldUid, newUid, tombstoneTs, modifiedTs)
                return
            }
            appInfoDAO.tombstoneApp(oldUid, newUid, packageName, tombstoneTs, modifiedTs)
        } catch (_: Exception) {
            // tombstoneApp is called when there is a package name change or uid change
            // in both the cases, we try to update the existing record with new uid or package name
            // if the record is not present, it throws exception, which we catch here
            // no need to log this exception
        }
    }

    suspend fun getAppInfo(): List<AppInfo> {
        return appInfoDAO.getAllAppDetails()
    }

    suspend fun getAppInfoByUid(uid: Int): AppInfo? {
        return appInfoDAO.getAppInfoByUid(uid)
    }

    suspend fun updateFirewallStatusByUid(uid: Int, firewallStatus: Int, connectionStatus: Int) {
        appInfoDAO.updateFirewallStatusByUid(uid, firewallStatus, connectionStatus, System.currentTimeMillis())
    }

    suspend fun updateTempAllowByUid(uid: Int, enabled: Boolean, expiryTime: Long) {
        appInfoDAO.updateTempAllowByUid(uid, enabled, expiryTime, System.currentTimeMillis())
    }

    suspend fun getTempAllowedApps(): List<AppInfo> {
        return appInfoDAO.getTempAllowedApps()
    }

    suspend fun getAllTempAllowedApps(now: Long): List<AppInfo> {
        // Filter apps where tempAllowExpiryTime is greater than current time
        return appInfoDAO.getTempAllowedApps().filter { it.tempAllowExpiryTime > now }
    }

    fun getAllTempAllowedAppsPaged(now: Long): androidx.paging.PagingSource<Int, AppInfo> {
        return appInfoDAO.getTempAllowedAppsPaged(now)
    }

    suspend fun clearTempAllowByUid(uid: Int) {
        appInfoDAO.clearTempAllowByUid(uid, System.currentTimeMillis())
    }

    suspend fun clearTempAllowByUidIfExpiry(uid: Int, expectedExpiry: Long): Int {
        return appInfoDAO.clearTempAllowByUidIfExpiry(uid, expectedExpiry, System.currentTimeMillis())
    }

    fun cpUpdate(appInfo: AppInfo): Int {
        appInfo.modifiedTs = System.currentTimeMillis()
        return appInfoDAO.update(appInfo)
    }

    fun cpUpdate(appInfo: AppInfo, clause: String): Int {
        // update only firewall and metered
        // Note: This method updates via raw query so modifiedTs must be included in clause if needed
        return appInfoDAO.cpUpdate(appInfo.firewallStatus, appInfo.connectionStatus, clause)
    }

    fun cpInsert(appInfo: AppInfo): Long {
        appInfo.modifiedTs = System.currentTimeMillis()
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
        appInfoDAO.updateProxyExcluded(uid, isProxyExcluded, System.currentTimeMillis())
    }

    suspend fun getAppInfoUidForPackageName(packageName: String): Int {
        return appInfoDAO.getAppInfoUidForPackageName(packageName)
    }

    suspend fun setRethinkToBypassProxy(bypass: Boolean) {
        appInfoDAO.setRethinkToBypassProxy(bypass)
    }

    suspend fun setRethinkToBypassDnsAndFirewall() {
        appInfoDAO.setRethinkToBypassDnsAndFirewall()
    }

    /**
     * Blocking variant for use from non-coroutine contexts (eg: Guava cache removal listeners).
     */
    fun clearTempAllowByUidBlocking(uid: Int) {
        appInfoDAO.clearTempAllowByUid(uid, System.currentTimeMillis())
    }

    /**
     * Blocking variant for use from non-coroutine contexts (eg: Guava cache removal listeners).
     */
    fun clearTempAllowByUidIfExpiryBlocking(uid: Int, expectedExpiry: Long): Int {
        return appInfoDAO.clearTempAllowByUidIfExpiry(uid, expectedExpiry, System.currentTimeMillis())
    }

    fun getNearestTempAllowExpiryBlocking(now: Long): Long? {
        return appInfoDAO.getNearestTempAllowExpiry(now)
    }

    /** Clears all expired temp-allow entries in a single statement; returns rows updated. */
    fun clearAllExpiredTempAllowsBlocking(now: Long): Int {
        // Clear any temp allow rows whose expiry has passed.
        // (Requires a DAO query; if not present, keep this as best-effort per-uid in worker.)
        return appInfoDAO.clearAllExpiredTempAllows(now, System.currentTimeMillis())
    }
}
