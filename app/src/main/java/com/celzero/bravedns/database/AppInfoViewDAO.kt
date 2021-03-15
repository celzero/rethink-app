package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query

@Dao
interface AppInfoViewDAO {

    @Query("select * from AppInfoView order by appCategory")
    fun getAllAppDetails(): List<AppInfoView>

    @Query("select * from AppInfoView order by appCategory,isInternetAllowed,lower(appName)")
    fun getAllAppDetailsForLiveData(): LiveData<List<AppInfoView>>

    @Query("select count(*) from AppInfoView where whiteListUniv1 = 1")
    fun getWhitelistCountLiveData(): LiveData<Int>

    @Query("select count(*) from AppInfoView where isExcluded = 1")
    fun getExcludedAppListCountLiveData() : LiveData<Int>
}