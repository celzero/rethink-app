package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface AppInfoDAO {

    @Update
    fun update(appInfo: AppInfo)

    @Query("update AppInfo set isInternetAllowed = :isInternetAllowed where uid = :uid")
    fun updateInternetPermissionForAlluid(uid : Int, isInternetAllowed : Boolean)

    @Query("select * from AppInfo where uid = :uid")
    fun getAppListForUID(uid : Int) : List<AppInfo>

    @Query("update AppInfo set isInternetAllowed = :isInternetAllowed where appCategory = :categoryName")
    fun updateInternetPermissionForCategory(categoryName : String, isInternetAllowed: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(appInfo: AppInfo)

    @Delete
    fun delete(appInfo : AppInfo)

    @Query("select * from AppInfo order by appCategory,uid")
    fun getAllAppDetails(): List<AppInfo>

    @Query("select * from AppInfo order by appCategory,appName")
    fun getAllAppDetailsForLiveData() : LiveData<List<AppInfo>>

    @Query("delete from AppInfo where packageInfo = :packageName")
    fun removeUninstalledPackage(packageName : String)

    @Query("select distinct appCategory from AppInfo order by appCategory")
    fun getAppCategoryList() : List<String>

    @Query("select count(appCategory) from AppInfo where appCategory = :categoryName")
    fun getAppCountForCategory(categoryName : String) : Int

    @Query ("select packageInfo from AppInfo where appName = :appName")
    fun getPackageNameForAppName(appName: String): String

}
