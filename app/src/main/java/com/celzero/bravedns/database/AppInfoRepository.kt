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

    fun deleteAsync(appInfo : AppInfo, coroutineScope: CoroutineScope= GlobalScope){
        coroutineScope.launch {
            appInfoDAO.delete(appInfo)
        }
    }

    fun insertAsync(appInfo : AppInfo, coroutineScope: CoroutineScope = GlobalScope){
        coroutineScope.launch {
            appInfoDAO.insert(appInfo)
        }
    }

    fun getAppInfoAsync(): List<AppInfo>{
        var list  = ArrayList<AppInfo>()
        list = appInfoDAO.getAllAppDetails() as ArrayList<AppInfo>
        return list
    }



}