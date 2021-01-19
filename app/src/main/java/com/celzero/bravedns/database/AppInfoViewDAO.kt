package com.celzero.bravedns.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AppInfoViewDAO {

    @Query("select * from AppInfoView order by appCategory")
    fun getAllAppDetails(): List<AppInfoView>
}