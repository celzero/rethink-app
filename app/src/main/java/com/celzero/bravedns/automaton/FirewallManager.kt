package com.celzero.bravedns.automaton

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.util.BackgroundAccessibilityService
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

    companion object{

        fun checkInternetPermission(packageName : String) : Boolean{
            return  !(GlobalVariable.appList[packageName]!!.isInternetAllowed)
        }

        fun checkInternetPermission(uid : Int): Boolean{
            if(GlobalVariable.blockedUID[uid] == null)
                return false
            return true
        }

        @OptIn(InternalCoroutinesApi::class)
        fun updateAppInternetPermission(packageName : String, isAllowed : Boolean){
                val appInfo = GlobalVariable.appList.get(packageName)
                if(appInfo != null){
                    appInfo.isInternetAllowed = isAllowed
                    GlobalVariable.appList.set(packageName,appInfo)
                }
        }

        fun updateAppInternetPermissionByUID(uid: Int, isInternetAllowed : Boolean){
            if(isInternetAllowed)
                GlobalVariable.blockedUID.remove(uid)
            else
                GlobalVariable.blockedUID.put(uid,isInternetAllowed)
        }

        @OptIn(InternalCoroutinesApi::class)
        fun updateInternetBackground(packageName: String, isAllowed: Boolean){
            val appInfo = GlobalVariable.appList.get(packageName)
            if(appInfo != null){
                appInfo.isInternetAllowed = isAllowed
                GlobalVariable.appList.set(packageName,appInfo)
            }
        }

        @OptIn(InternalCoroutinesApi::class)
        fun updateCategoryAppsInternetPermission(categoryName : String, isAllowed: Boolean, context: Context ){
            GlobalScope.launch ( Dispatchers.IO ) {
                GlobalVariable.appList.forEach {
                    if (it.value.appCategory.equals(categoryName)) {
                        it.value.isInternetAllowed = isAllowed
                        GlobalVariable.appList.put(it.key, it.value)
                        PersistentState.setExcludedPackagesWifi(it.key, isAllowed, context)
                        updateAppInternetPermissionByUID(it.value.uid, isAllowed)
                    }
                }
            }
        }
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

            }
        }
        val packageName =  event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // https://stackoverflow.com/a/27642535
            // top window is launcher? try revoke queued up permissions
            // FIXME: Figure out a fool-proof way to determine is launcher visible
            if (isPackageLauncher(packageName)) {
                // TODO: revoke permissions only if there are any to revoke
                addOrRemovePackageForBackground(false)
            }
        }else{
            addOrRemovePackageForBackground(true)
        }

    }

    private fun isPackageLauncher(packageName: String?): Boolean {
        if (TextUtils.isEmpty(packageName)) return false
        val intent = Intent("android.intent.action.MAIN")
        intent.addCategory("android.intent.category.HOME")
        val thisPackage = packageManager.resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return thisPackage == packageName
    }


    private fun addOrRemovePackageForBackground(isAllowed: Boolean){
        if(!GlobalVariable.isBackgroundEnabled)
            return
        if (packagesStack.isNullOrEmpty()) {
            return
        }else {
            val currentPackage = packagesStack.elementAt(0)
            packagesStack.remove(currentPackage)
            packageElect = currentPackage
            updateInternetBackground(currentPackage,isAllowed)
        }
    }

}