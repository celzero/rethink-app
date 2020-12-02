/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import kotlinx.coroutines.InternalCoroutinesApi


class BraveScreenStateReceiver : BroadcastReceiver() {

    @InternalCoroutinesApi
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent!!.action.equals(Intent.ACTION_SCREEN_OFF)) {
            if(DEBUG) Log.d(LOG_TAG,"BraveScreenStateReceiver : Action_screen_off detected from the receiver")
            if(PersistentState.getFirewallModeForScreenState(context!!) && !PersistentState.getScreenLockData(context)) {
                if(DEBUG) Log.d(LOG_TAG,"BraveSCreenStateReceiver : Screen lock data not true, calling DeviceLockService service")
                val newIntent = Intent(context, DeviceLockService::class.java)
                newIntent.action = DeviceLockService.ACTION_CHECK_LOCK
                newIntent.putExtra(DeviceLockService.EXTRA_STATE, intent.action)
                context.startService(newIntent)
            }
        } else if (intent.action.equals(Intent.ACTION_USER_PRESENT) || intent.action.equals(Intent.ACTION_SCREEN_ON)) {
            if(DEBUG) Log.d(LOG_TAG,"BraveScreenStateReceiver : ACTION_USER_PRESENT/ACTION_SCREEN_ON detected from the receiver")
            if(PersistentState.getFirewallModeForScreenState(context!!)) {
                val state = PersistentState.getScreenLockData(context)
                if(state) {
                    PersistentState.setScreenLockData(context, false)
                }
            }
        } /*else if (intent.action.equals(Intent.ACTION_PACKAGE_ADDED)) {
            val packageName = intent.dataString
            addPackagetoList(packageName!!,context!!)
        } else if (intent.action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
            val packageName = intent.dataString
            removePackageFromList(packageName, context!!)
        }*/
    }

    /*private fun removePackageFromList(pacakgeName :String? , context : Context){
        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "RemovePackage: $pacakgeName")
        GlobalScope.launch(Dispatchers.IO){
            val packageName = pacakgeName!!.removePrefix("package:")
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()
            appInfoRepository.removeUninstalledPackage(pacakgeName)
            HomeScreenActivity.GlobalVariable.appList.remove(pacakgeName)
            PersistentState.setExcludedPackagesWifi(packageName, true, context)
            //mDb.close()
        }
    }

    private fun addPackagetoList(packageName: String, context: Context) {
        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "Add Package: $packageName")
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()
            val packageName = packageName!!.removePrefix("package:")
            try {
                var details =
                    context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                if ((details.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                    launch(Dispatchers.Default) {
                        val applicationInfo: ApplicationInfo = details
                        val appInfo = AppInfo()
                        appInfo.appName = context.packageManager.getApplicationLabel(applicationInfo).toString()
                        val category = ApplicationInfo.getCategoryTitle(context, applicationInfo.category)
                        if (category != null)
                            appInfo.appCategory = category.toString()
                        else
                            appInfo.appCategory = "Other"
                        appInfo.isDataEnabled = true
                        appInfo.isWifiEnabled = true
                        appInfo.isSystemApp = false
                        appInfo.whiteListUniv1 = false
                        appInfo.whiteListUniv2 = false
                        if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                            appInfo.isSystemApp = true
                            appInfo.whiteListUniv1 = true
                            appInfo.whiteListUniv2 = true
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
                        //HomeScreenActivity.GlobalVariable.installedAppCount = HomeScreenActivity.GlobalVariable.installedAppCount + 1
                        appInfoRepository.insertAsync(appInfo, this)
                    }
                }
            }catch(e: PackageManager.NameNotFoundException){
                Log.e(LOG_TAG,"Package Not Found received from the receiver")
            }
        }

    }*/
}