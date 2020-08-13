package com.celzero.bravedns.database

import androidx.room.*

@Dao
interface AppInfoDAO {

    @Update
    fun update(appInfo: AppInfo)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(appInfo: AppInfo)

    @Delete
    fun delete(appInfo : AppInfo)

    @Query("select * from AppInfo")
    fun getAllAppDetails(): List<AppInfo>

    @Query("select * from AppInfo where packageInfo like :packageName")
    fun getAppDetailByName(packageName : String ): AppInfo

}
