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

package com.celzero.bravedns.automaton

import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.backgroundAllowedUID
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/*TODO : Initial check is for firewall app completely
           Later modification required for Data, WiFi, ScreenOn/Off, Background
           Lot of redundant code - streamline the code.
           */

class FirewallManager(service: BackgroundAccessibilityService) : KoinComponent {

    private val accessibilityService: BackgroundAccessibilityService = service
    private lateinit var packageManager: PackageManager
    private val packagesStack = LinkedHashSet<String>()
    private var latestTrackedPackage: String? = null
    private var packageElect: String? = null
    private val persistentState by inject<PersistentState>()

    companion object {

        fun checkInternetPermission(packageName: String): Boolean {
            return !(GlobalVariable.appList[packageName]!!.isInternetAllowed)
        }

        fun checkInternetPermission(uid: Int): Boolean {
            if (GlobalVariable.blockedUID[uid] == null) return false
            return true
        }


        fun updateAppInternetPermission(packageName: String, isAllowed: Boolean) {
            val appInfo = GlobalVariable.appList[packageName]
            if (appInfo != null) {
                appInfo.isInternetAllowed = isAllowed
                GlobalVariable.appList[packageName] = appInfo
            }
        }

        fun updateAppInternetPermissionByUID(uid: Int, isInternetAllowed: Boolean) {
            if (isInternetAllowed) GlobalVariable.blockedUID.remove(uid)
            else GlobalVariable.blockedUID[uid] = isInternetAllowed
        }

        fun updateInternetBackground(packageName: String, isAllowed: Boolean) {
            val appInfo = GlobalVariable.appList[packageName]
            if (appInfo != null) {
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "AccessibilityEvent: Update Internet Permission from background: ${appInfo.appName}, ${appInfo.isInternetAllowed}")
                if (isAllowed && AndroidUidConfig.isUIDAppRange(appInfo.uid)) {
                    if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                     "AccessibilityEvent: ${appInfo.appName},${appInfo.packageInfo} is in foreground")
                    backgroundAllowedUID[appInfo.uid] = isAllowed
                } else {
                    backgroundAllowedUID.remove(appInfo.uid)
                    if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                     "AccessibilityEvent: ${appInfo.appName},${appInfo.packageInfo} removed from foreground")
                }

            }
        }

        fun updateCategoryAppsInternetPermission(categoryName: String, isAllowed: Boolean,
                                                 persistentState: PersistentState) {
            GlobalScope.launch(Dispatchers.IO) {
                GlobalVariable.appList.forEach {
                    if (it.value.appCategory == categoryName && !it.value.whiteListUniv1) {
                        it.value.isInternetAllowed = isAllowed
                        GlobalVariable.appList[it.key] = it.value
                        persistentState.modifyAllowedWifi(it.key, isAllowed)
                        updateAppInternetPermissionByUID(it.value.uid, isAllowed)
                    }
                }
            }
        }
    }


    fun onAccessibilityEvent(event: AccessibilityEvent,
                             rootInActiveWindow: AccessibilityNodeInfo?) {
        packageManager = accessibilityService.packageManager

        val eventPackageName = getEventPackageName(event, rootInActiveWindow)

        val hasContentDisappeared = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "onAccessibilityEvent: ${event.packageName}, ${event.eventType}, $hasContentDisappeared")
        // is the package showing content and being backgrounded?
        if (hasContentDisappeared) {
            if (GlobalVariable.appList.containsKey(
                    eventPackageName)) {//PermissionsManager.packageRules.contains(eventPackageName)) {
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
                latestTrackedPackage = getEventPackageName(event, rootInActiveWindow)

            }
        }
        val packageName = latestTrackedPackage ?: return
        if (hasContentDisappeared) {
            // https://stackoverflow.com/a/27642535
            // top window is launcher? try revoke queued up permissions
            // FIXME: Figure out a fool-proof way to determine is launcher visible
            if (!isPackageLauncher(packageName)) {
                // TODO: revoke permissions only if there are any to revoke
                addOrRemovePackageForBackground(true)
            } else {
                backgroundAllowedUID.clear()
            }
            if (DEBUG) printAllowedUID()
        }
        // FIXME: 18-12-2020 - Figure out why the below code exists
        //probably not required.
        else {
            if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                             "addOrRemovePackageForBackground:isPackageLauncher ${packageName}, true")
            addOrRemovePackageForBackground(true)
        }

    }

    //https://stackoverflow.com/questions/45620584/event-getsource-returns-null-in-accessibility-service-catch-source-for-a-3rd-p
    /**
     * If the event retrieved package name is null then the check for the package name
     * is carried out in (getRootInActiveWindow)AccessibilityNodeInfo
     */
    private fun getEventPackageName(event: AccessibilityEvent,
                                    rootInActiveWindow: AccessibilityNodeInfo?): String? {
        var packageName: String? = event.packageName?.toString()
        if (packageName.isNullOrEmpty() && rootInActiveWindow != null) {
            packageName = rootInActiveWindow.packageName?.toString()
            if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                             "AccessibilityEvent: Value from rootInActiveWindow : $packageName")
        }
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "AccessibilityEvent: $packageName")
        return packageName
    }

    private fun printAllowedUID() {
        Log.d(LOG_TAG_FIREWALL, "AccessibilityEvent: printAllowedUID UID: --------")
        backgroundAllowedUID.forEach {
            Log.d(LOG_TAG_FIREWALL,
                  "AccessibilityEvent: printAllowedUID UID: ${it.key}, ${it.value}")
        }
    }

    private fun isPackageLauncher(packageName: String?): Boolean {
        if (TextUtils.isEmpty(packageName)) return false
        val intent = Intent("android.intent.action.MAIN")
        intent.addCategory("android.intent.category.HOME")
        val thisPackage = packageManager.resolveActivity(intent,
                                                         PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return thisPackage == packageName
    }


    private fun addOrRemovePackageForBackground(isAllowed: Boolean) {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "isBackgroundEnabled: ${persistentState.isBackgroundEnabled}")
        if (!persistentState.isBackgroundEnabled) return
        if (latestTrackedPackage.isNullOrEmpty()) {
            return
        } else {
            val currentPackage = latestTrackedPackage
            if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Package: $currentPackage, $isAllowed")
            packageElect = currentPackage
            updateInternetBackground(currentPackage!!, isAllowed)
        }
    }
}
