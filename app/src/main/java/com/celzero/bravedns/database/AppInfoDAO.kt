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

@Dao
interface AppInfoDAO {

    @Update
    fun update(appInfo: AppInfo)

    @Query("update AppInfo set isInternetAllowed = :isInternetAllowed where uid = :uid")
    fun updateInternetPermissionForAlluid(uid: Int, isInternetAllowed: Boolean)

    @Query("select * from AppInfo where uid = :uid")
    fun getAppListForUID(uid: Int): List<AppInfo>

    @Query(
        "update AppInfo set isInternetAllowed = :isInternetAllowed where appCategory = :categoryName and whiteListUniv1 != 1 and isExcluded != 1")
    fun updateInternetPermissionForCategory(categoryName: String, isInternetAllowed: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(appInfo: AppInfo)

    @Delete
    fun delete(appInfo: AppInfo)

    @Query("select * from AppInfo order by appCategory,uid")
    fun getAllAppDetails(): List<AppInfo>

    @Query("select * from AppInfo order by appCategory,isInternetAllowed,lower(appName)")
    fun getAllAppDetailsForLiveData(): LiveData<List<AppInfo>>

    @Query(
        "select * from AppInfo where appName like :input order by appCategory,isInternetAllowed,lower(appName)")
    fun getAppDetailsForLiveData(input: String): LiveData<List<AppInfo>>

    @Query("delete from AppInfo where packageInfo = :packageName")
    fun removeUninstalledPackage(packageName: String)

    @Query("select distinct appCategory from AppInfo  order by appCategory")
    fun getAppCategoryList(): List<String>

    @Query(
        "select distinct appCategory from AppInfo where appName like :appName order by appCategory")
    fun getAppCategoryForAppName(appName: String): List<String>

    @Query("select count(appCategory) from AppInfo where appCategory = :categoryName")
    fun getAppCountForCategory(categoryName: String): Int

    @Query(
        "select count(appCategory) from AppInfo where appCategory = :categoryName and isInternetAllowed = 0")
    fun getBlockedCountForCategory(categoryName: String): Int

    @Query("select packageInfo from AppInfo where appName = :appName")
    fun getPackageNameForAppName(appName: String): String

    @Query("select packageInfo from AppInfo where uid = :uid")
    fun getPackageNameForUid(uid: Int): String

    @Query(
        "select * from AppInfo where isExcluded = 0  order by whiteListUniv1 desc,lower(appName) ")
    fun getUnivAppDetailsLiveData(): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and isExcluded=0  order by whiteListUniv1 desc,lower(appName)")
    fun getUnivAppSystemAppsLiveData(): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where appName like :filter and isExcluded = 0   order by whiteListUniv1 desc,lower(appName)")
    fun getUnivAppDetailsFilterLiveData(filter: String): DataSource.Factory<Int, AppInfo>

    @Transaction
    @Query(
        "select * from AppInfo where appCategory in (:filter) and isExcluded = 0  order by whiteListUniv1 desc,lower(appName)")
    fun getUnivAppDetailsFilterForCategoryLiveData(
            filter: List<String>): DataSource.Factory<Int, AppInfo>

    @Query("select uid from AppInfo  where whiteListUniv1 = 1 ")
    fun getUIDForUnivWhiteList(): LiveData<List<Int>>

    @Query("select appName from AppInfo where isSystemApp = 0  order by appName")
    fun getAppNameList(): List<String>

    @Query(
        "update AppInfo set whiteListUniv1 = :isEnabled , isInternetAllowed = 1 where uid = :uid")
    fun updateWhiteList(uid: Int, isEnabled: Boolean)

    @Query("update AppInfo set whiteListUniv1 = :isEnabled, isInternetAllowed = 1")
    fun updateWhiteListForAllApp(isEnabled: Boolean): Int

    @Query(
        "update AppInfo set whiteListUniv1 = :isEnabled, isInternetAllowed = 1  where appCategory = :category")
    fun updateWhiteListForCategories(category: String, isEnabled: Boolean): Int

    @Query("select * from AppInfo  order by isExcluded desc,lower(appName)")
    fun getExcludedAppDetailsLiveData(): DataSource.Factory<Int, AppInfo>

    @Query("select * from AppInfo where isSystemApp = 1 order by isExcluded desc,lower(appName)")
    fun getExcludedAAppSystemAppsLiveData(): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where appCategory in (:filter)  order by isExcluded desc,lower(appName)")
    fun getExcludedAppDetailsFilterForCategoryLiveData(
            filter: List<String>): DataSource.Factory<Int, AppInfo>

    @Query(
        "select * from AppInfo where appName like :filter order by isExcluded desc,lower(appName)")
    fun getExcludedAppDetailsFilterLiveData(filter: String): DataSource.Factory<Int, AppInfo>

    @Query(
        "update AppInfo set isExcluded = :isExcluded, isInternetAllowed = 1, whiteListUniv1 = 0 ")
    fun updateExcludedForAllApp(isExcluded: Boolean)

    @Query(
        "update AppInfo set isExcluded = :isExcluded, isInternetAllowed = 1, whiteListUniv1 = 0 where appCategory = :category ")
    fun updateExcludedForCategories(category: String, isExcluded: Boolean)

    @Query(
        "update AppInfo set isExcluded = :isExcluded, isInternetAllowed = 1, whiteListUniv1 = 0 where uid = :uid")
    fun updateExcludedList(uid: Int, isExcluded: Boolean)

    @Query("select packageInfo from AppInfo where isExcluded = 1 ")
    fun getExcludedAppList(): List<String>

    @Query("select appName from AppInfo where appName = 'ANDROID'")
    fun isRootAvailable(): String

    @Query("select * from AppInfo where packageInfo = :packageName")
    fun getAppInfoForPackageName(packageName: String): AppInfo?

    @Query("select count(*) from AppInfo where isInternetAllowed = 0")
    fun getBlockedAppCount(): LiveData<Int>

    @Query("select count(*) from AppInfo where whiteListUniv1 = 1 and appCategory = :categoryName")
    fun getWhitelistCount(categoryName: String): Int

    @Query("select appName from AppInfo where uid = :uid")
    fun getAppNameForUID(uid: Int): String

    @Query("select count(*) from AppInfo where isExcluded= 1 and appCategory = :categoryName")
    fun getExcludedAppCountForCategory(categoryName: String): Int

    @Query("select count(*) from AppInfo where packageInfo like 'no_package%'")
    fun getNonAppCount(): Int

}
