package com.celzero.bravedns.util

import android.content.Context
import android.content.SharedPreferences
import com.celzero.bravedns.adapter.Apk
import com.celzero.bravedns.database.AppInfo

class PrefManager(var context : Context) {
    //private val PREF_NAME : String = "welcome"
    private val IS_FIRST_LAUNCH : String = "IsFirstTimeLaunch"
    private val APP_INFO : String = "AppInfo"
    private val PRIVATE_MODE: Int
        get() = 0
    
    var sharedPrefFirstLaunch : SharedPreferences
    var sharedPrefAppInfo : SharedPreferences
    var sharedPrefEditor : SharedPreferences.Editor

    init{
        sharedPrefFirstLaunch = context.getSharedPreferences(IS_FIRST_LAUNCH, PRIVATE_MODE)
        sharedPrefAppInfo = context.getSharedPreferences(APP_INFO,PRIVATE_MODE)
        sharedPrefEditor = sharedPrefFirstLaunch.edit()
    }

    fun setFirstTimeLaunch(isFirstTime : Boolean){
        sharedPrefEditor.putBoolean(IS_FIRST_LAUNCH,isFirstTime).commit()
    }

    fun isFirstTimeLaunch() : Boolean {
        return sharedPrefFirstLaunch.getBoolean(IS_FIRST_LAUNCH,true)
    }

    fun setAppInfo(appInformation : Set<String>){
        sharedPrefEditor.putStringSet(APP_INFO,appInformation)
    }

    fun getAppInfo(): MutableSet<String>? {
        return sharedPrefAppInfo.getStringSet(APP_INFO,null)
    }

}