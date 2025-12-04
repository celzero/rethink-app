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
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.celzero.bravedns.data.DataUsage

@Dao
interface AppInfoDAO {

    @Update fun update(appInfo: AppInfo): Int

    @Query(
        "update AppInfo set firewallStatus = :firewallStatus, connectionStatus = :connectionStatus where uid = :uid"
    )
    fun updateFirewallStatusByUid(uid: Int, firewallStatus: Int, connectionStatus: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(appInfo: AppInfo): Long

    @Query("update AppInfo set uid = :newUid, tombstoneTs = 0 where uid = :oldUid and packageName = :pkg")
    fun updateUid(oldUid: Int, pkg: String, newUid: Int): Int

    @Query("select * from AppInfo where uid = :uid and packageName = :pkg")
    fun isUidPkgExist(uid: Int, pkg: String): AppInfo?

    @Delete fun delete(appInfo: AppInfo)

    @Query("delete from AppInfo where packageName in (:packageNames)")
    fun deleteByPackageName(packageNames: List<String>)

    @Query("delete from AppInfo where uid = :uid and packageName = :packageName")
    fun deletePackage(uid: Int, packageName: String)

    @Query("update AppInfo set uid = :newUid, tombstoneTs = :tombstoneTs where uid = :uid and packageName = :packageName")
    fun tombstoneApp(newUid: Int, uid: Int, packageName: String, tombstoneTs: Long)

    @Query("update AppInfo set uid = :newUid, tombstoneTs = :tombstoneTs where uid = :oldUid")
    fun tombstoneApp(oldUid: Int, newUid: Int, tombstoneTs: Long)

    @Query("select * from AppInfo order by appCategory, uid") fun getAllAppDetails(): List<AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and (appName like :search or uid like :search or packageName like :search) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getSystemApps(
        search: String,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and (appName like :search or uid like :search or packageName like :search) and appCategory in (:filter) and (firewallStatus in (:firewall)  or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getSystemApps(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 0 and (appName like :search or uid like :search or packageName like :search) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getInstalledApps(
        search: String,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 0 and (appName like :search or uid like :search or packageName like :search) and appCategory in (:filter) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getInstalledApps(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getAppInfos(
        search: String,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and appCategory in (:filter)  and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getAppInfos(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and appCategory in (:cat) and isSystemApp in (:appType) and firewallStatus in (:firewall) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getFilteredApps(
        search: String,
        cat: Set<String>,
        firewall: Set<Int>,
        appType: Set<Int>,
        connectionStatus: Set<Int>
    ): List<AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and isSystemApp in (:appType) and firewallStatus in (:firewall) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getFilteredApps(
        search: String,
        firewall: Set<Int>,
        appType: Set<Int>,
        connectionStatus: Set<Int>
    ): List<AppInfo>

    @Query(
        "update AppInfo set firewallStatus = :firewall, connectionStatus = :connectionStatus where :clause"
    )
    fun cpUpdate(firewall: Int, connectionStatus: Int, clause: String): Int

    @Query("select * from AppInfo order by appCategory, uid") fun getAllAppDetailsCursor(): Cursor

    @Query("delete from AppInfo where uid = :uid") fun deleteByUid(uid: Int): Int

    @Query(
        "select uid as uid, downloadBytes as downloadBytes, uploadBytes as uploadBytes from AppInfo where uid = :uid"
    )
    fun getDataUsageByUid(uid: Int): DataUsage?

    @Query(
        "update AppInfo set  uploadBytes = :uploadBytes, downloadBytes = :downloadBytes where uid = :uid"
    )
    fun updateDataUsageByUid(uid: Int, uploadBytes: Long, downloadBytes: Long)

    @Query("update AppInfo set isProxyExcluded = :isProxyExcluded where uid = :uid")
    fun updateProxyExcluded(uid: Int, isProxyExcluded: Boolean)

    @Query("select uid from AppInfo where packageName = :packageName")
    fun getAppInfoUidForPackageName(packageName: String): Int
}
