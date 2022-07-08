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

import androidx.lifecycle.LiveData
import androidx.paging.DataSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface AppInfoDAO {

    @Update
    fun update(appInfo: AppInfo)

    @Query(
        "update AppInfo set firewallStatus = :firewallStatus, metered = :connectionStatus where uid = :uid")
    fun updateFirewallStatusByUid(uid: Int, firewallStatus: Int, connectionStatus: Int)

    @Query("update AppInfo set firewallStatus = :status")
    fun updateFirewallStatusForAllApps(status: Int): Int

    @Query("update AppInfo set firewallStatus = :status where appCategory = :categoryName")
    fun updateFirewallStatusByCategory(categoryName: String, status: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(appInfo: AppInfo)

    @Delete
    fun delete(appInfo: AppInfo)

    @Query("delete from AppInfo where packageInfo in (:packageNames)")
    fun deleteByPackageName(packageNames: List<String>)

    @Query("select * from AppInfo order by appCategory, uid")
    fun getAllAppDetails(): List<AppInfo>

    @Query("select * from AppInfo order by appCategory, lower(appName)")
    fun getAllAppDetailsForLiveData(): LiveData<List<AppInfo>>

    @Query("select * from AppInfo where appName like :input order by appCategory, lower(appName)")
    fun getAppDetailsForLiveData(input: String): LiveData<List<AppInfo>>

    @Query("select distinct appCategory from AppInfo  order by appCategory")
    fun getAppCategoryList(): List<String>

    @Query(
        "select count(appCategory) from AppInfo where appCategory = :categoryName and firewallStatus = :status")
    fun getBlockedCountForCategory(categoryName: String, status: Int): Int

    @Query("select * from AppInfo where isSystemApp = 1 order by lower(appName)")
    fun getWhitelistedSystemApps(): DataSource.Factory<Int, AppInfo>

    @Query("select * from AppInfo where appName like :filter order by lower(appName)")
    fun getWhitelistedApps(filter: String): DataSource.Factory<Int, AppInfo>

    @Transaction
    @Query("select * from AppInfo where appCategory in (:filter) order by lower(appName)")
    fun getWhitelistedAppsByCategory(filter: List<String>): DataSource.Factory<Int, AppInfo>

    @Query("select * from AppInfo where appCategory != 'Non-App System' order by lower(appName)")
    fun getExcludedAppDetails(): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and appCategory != 'Non-App System' order by lower(appName)")
    fun getExcludedAAppSystemApps(): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where appCategory in (:filter) and appCategory != 'Non-App System' order by lower(appName)")
    fun getExcludedAppDetailsFilterForCategory(
            filter: List<String>): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where appName like :filter and appCategory != 'Non-App System' order by lower(appName)")
    fun getExcludedAppDetailsFilterLiveData(filter: String): DataSource.Factory<Int, AppInfo>

    @Query("select * from AppInfo where isSystemApp = 1 and appName like :name order by :orderBy")
    fun getSystemApps(name: String, orderBy: String): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and appName like :name and appCategory in (:filter) order by :orderBy")
    fun getSystemApps(name: String, filter: Set<String>,
                      orderBy: String): DataSource.Factory<Int, AppInfo>

    @Query("select * from AppInfo where isSystemApp = 0 and appName like :name order by :orderBy")
    fun getInstalledApps(name: String, orderBy: String): DataSource.Factory<Int, AppInfo>

    @RawQuery(observedEntities = [AppInfo::class])
    fun getQuery(query: SupportSQLiteQuery): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 0 and appName like :name and appCategory in (:filter) order by :orderBy")
    fun getInstalledApps(name: String, filter: Set<String>,
                         orderBy: String): DataSource.Factory<Int, AppInfo>

    @Query("select * from AppInfo where appName like :name order by :orderBy")
    fun getAppInfos(name: String, orderBy: String): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where appName like :name and appCategory in (:filter) order by :orderBy")
    fun getAppInfos(name: String, filter: Set<String>,
                    orderBy: String): DataSource.Factory<Int, AppInfo>

    @Query("select * from AppInfo where appName like :name and appCategory in (:cat)")
    fun getFilteredApps(name: String, cat: Set<String>): List<AppInfo>

    @Query("select * from AppInfo where appName like :name")
    fun getFilteredApps(name: String): List<AppInfo>

}
