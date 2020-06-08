package com.celzero.bravedns.database

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class AppInfoRepository  (private val appInfoDAO: AppInfoDAO){

    fun updateAsync(appInfo: AppInfo, coroutineScope: CoroutineScope = GlobalScope){
        coroutineScope.launch {
            appInfoDAO.update(appInfo)
        }
    }

    fun insertAsync(appInfo : AppInfo, coroutineScope: CoroutineScope = GlobalScope){
        coroutineScope.launch {
            Log.w("DB Save","App Info :"+appInfo.appName)
            appInfoDAO.insert(appInfo)
        }
    }

    fun getAppInfoAsync(): List<AppInfo>{
        var list  = ArrayList<AppInfo>()
        list = appInfoDAO.getAllAppDetails() as ArrayList<AppInfo>
        return list
    }



}