package com.celzero.bravedns.database

import androidx.room.*

@Dao
interface AppInfoDAO {

    @Update
    fun update(appInfo: AppInfo)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(appInfo: AppInfo)

    @Query("select * from AppInfo")
    fun getAllAppDetails(): List<AppInfo>

}
