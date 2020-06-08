package com.celzero.bravedns.util

import android.content.Context
import android.content.SharedPreferences

class PrefManager(var context : Context) {
    //private val PREF_NAME : String = "welcome"
    private val IS_FIRST_LAUNCH : String = "IsFirstTimeLaunch"
    private val PRIVATE_MODE: Int
        get() = 0
    
    var sharedPref : SharedPreferences
    var sharedPrefEditor : SharedPreferences.Editor

    init{
        sharedPref = context.getSharedPreferences(IS_FIRST_LAUNCH, PRIVATE_MODE)
        sharedPrefEditor = sharedPref.edit()
    }

    fun setFirstTimeLaunch(isFirstTime : Boolean){
        sharedPrefEditor.putBoolean(IS_FIRST_LAUNCH,isFirstTime).commit()
    }

    fun isFirstTimeLaunch() : Boolean {
        return sharedPref.getBoolean(IS_FIRST_LAUNCH,true)
    }

}