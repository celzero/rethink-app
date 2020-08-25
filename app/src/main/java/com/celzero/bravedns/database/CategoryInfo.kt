package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "CategoryInfo")
class CategoryInfo{

    @PrimaryKey
    var categoryName: String = ""
    var numberOFApps: Int = 0
    var numOfAppsBlocked : Int = 0
    var isInternetBlocked: Boolean = false

}