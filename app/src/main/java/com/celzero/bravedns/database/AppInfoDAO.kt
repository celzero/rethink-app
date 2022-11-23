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

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface AppInfoDAO {

    @Update fun update(appInfo: AppInfo)

    @Query(
        "update AppInfo set firewallStatus = :firewallStatus, metered = :connectionStatus where uid = :uid"
    )
    fun updateFirewallStatusByUid(uid: Int, firewallStatus: Int, connectionStatus: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(appInfo: AppInfo)

    @Delete fun delete(appInfo: AppInfo)

    @Query("delete from AppInfo where packageInfo in (:packageNames)")
    fun deleteByPackageName(packageNames: List<String>)

    @Query("select * from AppInfo order by appCategory, uid") fun getAllAppDetails(): List<AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and (appName like :search or uid like :search or packageInfo like :search) and firewallStatus in (:firewall) order by lower(appName)"
    )
    fun getSystemApps(search: String, firewall: Set<Int>): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and (appName like :search or uid like :search or packageInfo like :search) and appCategory in (:filter) and firewallStatus in (:firewall)  order by lower(appName)"
    )
    fun getSystemApps(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 0 and (appName like :search or uid like :search or packageInfo like :search) and firewallStatus in (:firewall) order by lower(appName)"
    )
    fun getInstalledApps(search: String, firewall: Set<Int>): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 0 and (appName like :search or uid like :search or packageInfo like :search) and appCategory in (:filter) and firewallStatus in (:firewall) order by lower(appName)"
    )
    fun getInstalledApps(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageInfo like :search) and firewallStatus in (:firewall) order by lower(appName)"
    )
    fun getAppInfos(search: String, firewall: Set<Int>): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageInfo like :search) and appCategory in (:filter)  and firewallStatus in (:firewall)  order by lower(appName)"
    )
    fun getAppInfos(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageInfo like :search) and appCategory in (:cat) and isSystemApp in (:appType) and firewallStatus in (:firewall) "
    )
    fun getFilteredApps(
        search: String,
        cat: Set<String>,
        firewall: Set<Int>,
        appType: Set<Int>
    ): List<AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageInfo like :search) and isSystemApp in (:appType) and firewallStatus in (:firewall) "
    )
    fun getFilteredApps(search: String, firewall: Set<Int>, appType: Set<Int>): List<AppInfo>

    @RawQuery fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
