package com.celzero.bravedns.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import android.widget.Toast
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.ui.ApplicationManagerActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import kotlinx.coroutines.*


class BraveScreenStateReceiver : BroadcastReceiver() {

    @InternalCoroutinesApi
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent!!.action.equals(Intent.ACTION_SCREEN_OFF)) {
            println("ACTION_SCREEN_OFF:" + PersistentState.getFirewallModeForScreenState(context!!))
            if(PersistentState.getFirewallModeForScreenState(context!!)) {
                //FirewallManager.modifyInternetPermissionForAllApps(false)
                BraveVPNService.vpnController!!.getBraveVpnService()!!.blockTraffic()
            }
        } else if (intent.action.equals(Intent.ACTION_SCREEN_ON)) {
            println("ACTION_SCREEN_ON:" + PersistentState.getFirewallModeForScreenState(context!!))
            if(PersistentState.getFirewallModeForScreenState(context!!)) {
                //FirewallManager.modifyInternetPermissionForAllApps(true)
                BraveVPNService.vpnController!!.getBraveVpnService()!!.resumeTraffic()
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
            //val appInfoDAO  = mDb.appInfoDAO()
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
            //val appInfoDAO  = mDb.appInfoDAO()
            val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
            try {
                var details =
                    context.packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.GET_META_DATA
                    )
                if (details != null) {
                    if ((details.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {

                        launch(Dispatchers.Default) {

                            val applicationInfo: ApplicationInfo = details
                            val appInfo = AppInfo()
                            appInfo.appName =
                                context.packageManager.getApplicationLabel(applicationInfo)
                                    .toString()
                            val category =
                                ApplicationInfo.getCategoryTitle(context, applicationInfo.category)
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
                            HomeScreenActivity.GlobalVariable.appList[applicationInfo.packageName] =
                                appInfo
                            HomeScreenActivity.GlobalVariable.installedAppCount =
                                HomeScreenActivity.GlobalVariable.installedAppCount + 1
                            appInfoRepository.insertAsync(appInfo, this)
                            //Log.w("DB Inserts","App Size : " + appInfo.packageInfo +": "+appInfo.uid)
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        ApplicationManagerActivity.updateUI(packageName!!, true)
                    }
                }
            }catch(e: PackageManager.NameNotFoundException){
                Log.e("BraveDNS","Package Not Foundation received from the receiver")
            }
        }

    }
}