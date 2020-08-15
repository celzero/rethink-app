package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.ApplicationManagerActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import kotlinx.coroutines.*


class BraveScreenStateReceiver : BroadcastReceiver() {

    @InternalCoroutinesApi
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent!!.action.equals(Intent.ACTION_SCREEN_OFF)) {
            if(PersistentState.getFirewallModeForScreenState(context!!) && !PersistentState.getScreenLockData(context)) {
                val newIntent = Intent(context, DeviceLockService::class.java)
                newIntent.action = DeviceLockService.ACTION_CHECK_LOCK
                newIntent.putExtra(DeviceLockService.EXTRA_STATE, intent.action)
                context.startService(newIntent)
            }
        } else if (intent.action.equals(Intent.ACTION_USER_PRESENT)) {
            println("ACTION_SCREEN_ON")
            if(PersistentState.getFirewallModeForScreenState(context!!)) {
                var state = PersistentState.getScreenLockData(context)
                if(state) {
                    PersistentState.setScreenLockData(context, false)
                }
            }
        } else if (intent.action.equals(Intent.ACTION_PACKAGE_ADDED)) {
            val packageName = intent.dataString
            addPackagetoList(packageName,context!!)
        } else if (intent.action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
            val packageName = intent.dataString
            removePackageFromList(packageName, context!!)
        }
    }

    private fun removePackageFromList(packName :String? , context : Context){
        GlobalScope.launch(Dispatchers.IO){
            val packageName = packName!!.removePrefix("package:").toString()
            var appInfo = HomeScreenActivity.GlobalVariable.appList.get(packageName)
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
            if(appInfo != null)
                appInfoRepository.deleteAsync(appInfo, this)
            HomeScreenActivity.GlobalVariable.appList.remove(packageName)
            withContext(Dispatchers.Main.immediate) {
                ApplicationManagerActivity.updateUI(packageName, false)
            }
        }


    }

    private fun addPackagetoList(packageName: String?, context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
            try {
                var details =
                    context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                if (details != null) {
                    if ((details.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                        launch(Dispatchers.Default) {
                            val applicationInfo: ApplicationInfo = details
                            val appInfo = AppInfo()
                            appInfo.appName = context.packageManager.getApplicationLabel(applicationInfo).toString()
                            val category = ApplicationInfo.getCategoryTitle(context, applicationInfo.category)
                            if (category != null)
                                appInfo.appCategory = category.toString()
                            else
                                appInfo.appCategory = "Unknown Category"

                            appInfo.isDataEnabled = true
                            appInfo.isWifiEnabled = true
                            appInfo.isSystemApp = false
                            if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                                //count += 1
                                appInfo.isSystemApp = false
                            }
                            appInfo.isScreenOff = false
                            appInfo.isInternetAllowed = true
                            appInfo.isBackgroundEnabled = false
                            appInfo.mobileDataUsed = 0
                            appInfo.packageInfo = applicationInfo.packageName
                            appInfo.trackers = 0
                            appInfo.wifiDataUsed = 0
                            appInfo.uid = applicationInfo.uid

                            //TODO Handle this Global scope variable properly. Only half done.
                            HomeScreenActivity.GlobalVariable.appList[applicationInfo.packageName] = appInfo
                            HomeScreenActivity.GlobalVariable.installedAppCount = HomeScreenActivity.GlobalVariable.installedAppCount + 1
                            appInfoRepository.insertAsync(appInfo, this)
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        ApplicationManagerActivity.updateUI(packageName!!, true)
                    }
                }
            }catch(e: PackageManager.NameNotFoundException){
                Log.e("BraveDNS","Package Not Found received from the receiver")
            }
        }

    }
}