package com.celzero.bravedns.database

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "AppInfo")
class AppInfo {
    @PrimaryKey
    var packageInfo : String = ""
    var appName : String = ""
    var uid : Int = 0
    var trackers : Int = 0
    var isWifiEnabled : Boolean = true
    var isDataEnabled : Boolean = true
    var appCategory : Int = 0
    var wifiDataUsed : Long = 0
    var mobileDataUsed : Long = 0
}