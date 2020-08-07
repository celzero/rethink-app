package com.celzero.bravedns.automaton

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.celzero.bravedns.adapter.FirewallApk
import com.celzero.bravedns.adapter.FirewallHeader
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.FirewallActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.MyAccessibilityService
import kotlinx.coroutines.*

/*TODO : Initial check is for firewall app completely
           Later modification required for Data, WiFi, ScreenOn/Off, Background
           Lot of redundant code - streamline the code.
           */

class FirewallManager {

    private val accessibilityService: BackgroundAccessibilityService
    private lateinit var packageManager: PackageManager
    private val packagesStack = LinkedHashSet<String>()
    private var latestTrackedPackage: String? = null
    private var packageElect: String? = null

    constructor(service: BackgroundAccessibilityService) {
        accessibilityService = service
    }

    val TAG = "BraveDNS"
    //private val packageInstaller = "com.google.android.packageinstaller"

    companion object{

        fun checkInternetPermission (packageName : String) : Boolean{
            return  !(GlobalVariable.appList[packageName]!!.isInternetAllowed)
        }

        fun updateAppInternetPermission(packageName : String, isAllowed : Boolean){
            //GlobalScope.launch ( Dispatchers.Default ) {
                val appInfo = GlobalVariable.appList.get(packageName)
                if(appInfo != null){
                    //Log.w("BraveDNS", "updateInternetBackground : $packageName and internet isAllowed $isAllowed" )
                    appInfo.isInternetAllowed = isAllowed
                    GlobalVariable.appList.set(packageName,appInfo)
                }
                if(GlobalVariable.firewallMode == 2)
                    BraveVPNService.vpnController!!.getBraveVpnService()!!.restarVPNfromExternalForce()
           // }
        }

        fun updateInternetBackground(packageName: String, isAllowed: Boolean){
            //GlobalScope.launch ( Dispatchers.Default ) {
                //Log.w("BraveDNS", "Before updateInternetBackground : $packageName and internet isAllowed $isAllowed" )
                val appInfo = GlobalVariable.appList.get(packageName)
                if(appInfo != null){
                    //Log.w("BraveDNS", "updateInternetBackground : $packageName and internet isAllowed $isAllowed" )
                    appInfo.isInternetAllowed = isAllowed
                    GlobalVariable.appList.set(packageName,appInfo)
                }
                if(GlobalVariable.firewallMode == 2)
                    BraveVPNService.vpnController!!.getBraveVpnService()!!.restarVPNfromExternalForce()
           // }

        }

        fun updateCategoryAppsInternetPermission(categoryName : String, isAllowed: Boolean, context: Context ){
            //GlobalScope.launch ( Dispatchers.Default ) {
                GlobalVariable.appList.forEach {
                    if (it.value.appCategory.equals(categoryName)) {
                        it.value.isInternetAllowed = isAllowed
                        GlobalVariable.appList.put(it.key, it.value)
                        PersistentState.setExcludedPackagesWifi(it.key, isAllowed, context)
                    }
                }
                //val firewallHeader = FirewallHeader(categoryName, isAllowed)
                //GlobalVariable.categoryList.put(categoryName, firewallHeader)
                PersistentState.setCategoriesBlocked(categoryName, isAllowed, FirewallHeader.context)
                if(GlobalVariable.firewallMode == 2)
                    BraveVPNService.vpnController!!.getBraveVpnService()!!.restarVPNfromExternalForce()
               // withContext(Dispatchers.Main.immediate) {

                //}
            //}
        }


        fun updateInternetPermissionForAllApp(isAllowed: Boolean, context: Context){
            GlobalScope.launch ( Dispatchers.Default ) {
                GlobalVariable.appList.forEach {
                    //if(it.value.appCategory.equals(categoryName)){
                    it.value.isInternetAllowed = isAllowed
                    GlobalVariable.appList.put(it.key, it.value)
                    PersistentState.setExcludedPackagesWifi(it.key, !isAllowed, context)
                    //}
                }
            }
                if (GlobalVariable.firewallMode == 2)
                    BraveVPNService.vpnController!!.getBraveVpnService()!!
                        .restarVPNfromExternalForce()
            //}
        }

        fun modifyInternetPermissionForAllApps(isAllowed: Boolean){
            //GlobalScope.launch ( Dispatchers.Default ) {
                GlobalVariable.appList.forEach {
                    it.value.isInternetAllowed = isAllowed
                    GlobalVariable.appList.put(it.key, it.value)
                }
            //}
        }

        fun printAllAppStatus(){
            GlobalVariable.appList.forEach{
                Log.d("BraveDNS", "PackageName : "+ it.key + " , isInternet : "+ it.value.isInternetAllowed)
            }
        }

        /*fun isCategoryInternetAllowed(categoryName : String ) : Boolean{
            val categoryDetail = GlobalVariable.categoryList.get(categoryName)
            if(categoryDetail == null)
                return true
            //Log.d("BraveDNS", "categoryName : "+ categoryDetail!!.categoryName + " , isInternet : "+ categoryDetail!!.isInternet)
            if(categoryDetail.isInternet){
                return true
            }
            return false
        }*/

    }


    fun onAccessibilityEvent(event: AccessibilityEvent, that: BackgroundAccessibilityService){
        packageManager = accessibilityService.packageManager
        val eventPackageName = event.packageName?.toString()

        val hasContentDisappeared = event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED
        // is the package showing content and being backgrounded?
        if (hasContentDisappeared) {
            if (GlobalVariable.appList.containsKey(eventPackageName)){//PermissionsManager.packageRules.contains(eventPackageName)) {
                // FIXME: Gross hack that no one likes
                // packagesStack tracks apps that have disappeared
                // after user interaction, and so: check for event.source
                // to be not null, because the content change disappeared
                // event may come up even when the app isn't going background
                // BUT whenever event.source is null, it is observed that
                // the app is not disappearing... this is fragile.
                // determine a better heuristic for when to push the
                // package to the stack.
                // packagesStack is also used by #revokePermissions
                // and so, we must be extra careful to when we add to it.
                if (eventPackageName != null && event.source != null) {
                    packagesStack.add(eventPackageName)
                }
                latestTrackedPackage = event.packageName.toString()

               Log.w(TAG, "bbbbb............... Added package to the stack: ${eventPackageName} size: ${packagesStack.size}")
            }/* else if (isPackageInstaller(eventPackageName) && isGrant()) {
                // if content-disappeared and there's nothing to track
                // make sure to untrack PERMISSIONS_GRANT state set below
                //currentAutoState = PermissionsManager.AutoState.DORMANT
            }*/
        }


        val packageName =  event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // https://stackoverflow.com/a/27642535
            // top window is launcher? try revoke queued up permissions
            // FIXME: Figure out a fool-proof way to determine is launcher visible
            //Log.d("BraveDNS","isPackageLauncher : ${isPackageLauncher(packageName)}")
            if (isPackageLauncher(packageName)) {
                Log.d("BraveDNS","add package to firewall : $packageName and eventPack : $eventPackageName")
                // TODO: revoke permissions only if there are any to revoke
                addOrRemovePackageForBackground(false)
            }
        }else{
            Log.d("BraveDNS","remove package to firewall : $packageName and eventPack : $eventPackageName")
            addOrRemovePackageForBackground(true)
        }

    }

    private fun isPackageLauncher(packageName: String?): Boolean {
        if (TextUtils.isEmpty(packageName)) return false
        val intent = Intent("android.intent.action.MAIN")
        intent.addCategory("android.intent.category.HOME")
        val thisPackage = packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return thisPackage == packageName
    }


    private fun addOrRemovePackageForBackground(isAllowed: Boolean){
        if(!GlobalVariable.isBackgroundEnabled)
            return
        if (packagesStack.isNullOrEmpty()) {
            return
        }else {
            Log.w(TAG, "bbbbb ____ revokePermissions :" + packagesStack.elementAt(0))
            val currentPackage = packagesStack.elementAt(0)
            packagesStack.remove(currentPackage)
            packageElect = currentPackage
            updateInternetBackground(currentPackage,isAllowed)
        }
    }

}