package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
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
        return appInfoDAO.getAllAppDetails()
    }


    fun updateInternetForuid(uid : Int, isInternetAllowed : Boolean){
        return appInfoDAO.updateInternetPermissionForAlluid(uid, isInternetAllowed)
    }

    fun getAppListForUID(uid:Int) : List<AppInfo>{
        return appInfoDAO.getAppListForUID(uid)
    }

    fun updateInternetForAppCategory(categoryName : String, isInternetAllowed: Boolean){
        return appInfoDAO.updateInternetPermissionForCategory(categoryName,isInternetAllowed)
    }

    fun getAllAppDetailsForLiveData(): LiveData<List<AppInfo>>{
        return appInfoDAO.getAllAppDetailsForLiveData()
    }

    fun removeUninstalledPackage(packageName : String) {
        return appInfoDAO.removeUninstalledPackage(packageName)
    }

    fun getAppCategoryList(): List<String>{
        return appInfoDAO.getAppCategoryList()
    }

    fun getAppCountForCategory(categoryName : String): Int {
        return appInfoDAO.getAppCountForCategory(categoryName)
    }

}