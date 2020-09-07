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
import com.celzero.bravedns.ui.HomeScreenActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch


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
            if(PersistentState.getFirewallModeForScreenState(context!!)) {
                var state = PersistentState.getScreenLockData(context)
                if(state) {
                    PersistentState.setScreenLockData(context, false)
                }
            }
        } else if (intent.action.equals(Intent.ACTION_PACKAGE_ADDED)) {
            val packageName = intent.dataString
            addPackagetoList(packageName!!,context!!)
        } else if (intent.action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
            val packageName = intent.dataString
            removePackageFromList(packageName, context!!)
        }
    }

    private fun removePackageFromList(pacakgeName :String? , context : Context){
        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d("BraveDNS", "RemovePackage: $pacakgeName")
        GlobalScope.launch(Dispatchers.IO){
            val packageName = pacakgeName!!.removePrefix("package:")
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()
            appInfoRepository.removeUninstalledPackage(pacakgeName)
            HomeScreenActivity.GlobalVariable.appList.remove(pacakgeName)
            PersistentState.setExcludedPackagesWifi(packageName, true, context)
        }


    }

    private fun addPackagetoList(packageName: String, context: Context) {
        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d("BraveDNS", "Add Package: $packageName")
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
                        if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
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
                        //HomeScreenActivity.GlobalVariable.installedAppCount = HomeScreenActivity.GlobalVariable.installedAppCount + 1
                        appInfoRepository.insertAsync(appInfo, this)
                    }
                }
            }catch(e: PackageManager.NameNotFoundException){
                Log.e("BraveDNS","Package Not Found received from the receiver")
            }
        }

    }
}