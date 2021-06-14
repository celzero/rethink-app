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

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.text.BidiFormatter
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.text.HtmlCompat
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_PERMISSION
import com.celzero.bravedns.util.MyAccessibilityService
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.collections.LinkedHashSet


class PermissionsManager(service: MyAccessibilityService) {

    companion object{
        var packageRules = LinkedHashMap<String, Rules>()
    }

    enum class Rules(val code: Int) {
        NONE(0),
        BG_REMOVE(1),
        BG_REMOVE_FG_ADD(11)
    }

    enum class AutoState(val code: Int) {
        DORMANT(0),
        TRACKING(11),
        SETTINGS_PAGE(1),
        PERMISSIONS_PAGE(2),
        PERMISSIONS_PAGE_TOGGLE(3),
        PERMISSIONS_PAGE_DIALOG(4),
        PERMISSIONS_GRANT(5)
    }

    private val packagesStack = LinkedHashSet<String>()
    private val accessibilityService: MyAccessibilityService = service
    private val packageInstaller = "com.google.android.packageinstaller"
    private val packageSettings = "com.android.settings"
    private val permissionsSwitchId = "android:id/switch_widget"
    private val permissionsListId = "android:id/list"
    private val packageInstallerDenyAnywayId = "android:id/button1"
    private val permissionsAllowButtonId = "com.android.packageinstaller:id/permission_allow_button"
    // https://github.com/aosp-mirror/platform_packages_apps_settings/blob/eae6f13389e3ec913db35079ee9e44b5abec19e1/res/xml/app_info_settings.xml#L45
    private val settingsLabelPermission = "Permissions"
    private lateinit var packageManager: PackageManager
    private lateinit var activityManager: ActivityManager


    private var latestTrackedPackage: String? = null
    private var packageElect: String? = null
    private var currentAutoState: AutoState = AutoState.DORMANT

    fun onAccessibilityEvent(event: AccessibilityEvent, that: MyAccessibilityService) {
        packageManager = accessibilityService.packageManager
        activityManager = accessibilityService.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager

        val eventPackageName = event.packageName?.toString()

        val hasContentDisappeared = event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED
        // is the package showing content and being backgrounded?
        if (hasContentDisappeared) {
            if (packageRules.contains(eventPackageName)) {
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
                // unset any previous state like PERMISSIONS_GRANT
                // or DORMANT to TRACKING here.
                currentAutoState = AutoState.TRACKING
            } else if (isPackageInstaller(eventPackageName) && isGrant()) {
                // if content-disappeared and there's nothing to track
                // make sure to untrack PERMISSIONS_GRANT state set below
                currentAutoState = AutoState.DORMANT
            }
        }

        if (event.source == null) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // regardless of #isRevokeGrant, proceed, since events sometimes arrive out of order
        // and mess up the state machine, unfortunately.
        if (isPackageInstaller(packageName) && !isDormantOrTracking() &&
            event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) {
            // deny anyway dialog is here?
            if (event.className == "android.app.AlertDialog") {
                currentAutoState = AutoState.PERMISSIONS_PAGE_DIALOG
                clickDenyIfPresent(event)
            }
        }

        if (isPackageInstaller(packageName) && isDormantOrTracking() &&
            event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) {
            if (event.className == "com.android.packageinstaller.permission.ui.GrantPermissionsActivity") {
                if (isAutoGrant(latestTrackedPackage)) {
                    currentAutoState = AutoState.PERMISSIONS_GRANT
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // https://stackoverflow.com/a/27642535
            // top window is launcher? try revoke queued up permissions
            // FIXME: Figure out a fool-proof way to determine is launcher visible
            if (isPackageLauncher(packageName)) {
                // TODO: revoke permissions only if there are any to revoke
                revokePermissions()
            } else if (packageRules.contains(packageName)) {
                // track latest event as it might represent the top window
                // of a package we need to revoke permissions later
                latestTrackedPackage = packageName
            } else if (isPackageSettings(packageName)) {
                when {
                    isSettingsPage() -> {
                        // if settings shows up, and we're primed to revoke-grant,
                        // click on permissions pref to navigate to package-installer
                        Log.w(LOG_TAG_PERMISSION, "bbbbb ____ settings revokegrant?")
                        revokePermissionsFirstStage(event)
                    }
                }
            } else if (isPackageInstaller(packageName)) {
                when {
                    isGrant() -> {
                        // if the state is set to grant permissions
                        // look for allow button and go
                        clickAllowIfPresent(event)
                    }
                    isRevokeGrant() -> {
                        // if package-installer shows up during revoke-grant,
                        // we might need to either disable perms or get rid
                        // of the "deny anyway" dialog.
                        Log.w(LOG_TAG_PERMISSION, "bbbbb ____ packageinstaller revokegrant?")
                        revokePermissionsSecondStage(event)
                    }
                    else -> Log.w(LOG_TAG_PERMISSION, "___Last package not signed up for auto-grant $packageName")
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            Log.w(LOG_TAG_PERMISSION, "onAEvent____: window_changed $event")

            // get the source node of the event
            event.source?.apply {

                // take action on behalf of the user
                // performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

                // recycle the nodeInfo object
                recycle()
            }
        }
    }

    private fun revokePermissionsFirstStage(event: AccessibilityEvent) {
        when (currentAutoState) {
            AutoState.SETTINGS_PAGE -> {
                navigateToPermissionsPage(event)
            }
            else -> {
                Log.w(LOG_TAG_PERMISSION, "bbbb revokepermissionfirststage, but nothing to do, reset $event")
            }
        }
    }

    private fun revokePermissionsSecondStage(event: AccessibilityEvent) {
        Log.w(LOG_TAG_PERMISSION,"bbbbb_____ clicktodisable now permissions_page $currentAutoState")
        clickToDisablePermissions(event)
    }

    private fun revokePermissionsThirdStage(event: AccessibilityEvent) {
        currentAutoState = AutoState.DORMANT
        packageElect = null
        navigateToLauncher()
        Log.w(LOG_TAG_PERMISSION, "bbbb ____ dismiss node ${event.source}")
    }

    private fun isDormant(): Boolean {
        return currentAutoState == AutoState.DORMANT
    }

    private fun isTracking(): Boolean {
        return currentAutoState == AutoState.TRACKING
    }

    private fun isDormantOrTracking(): Boolean {
        return isDormant() || isTracking()
    }

    private fun isSettingsPage(): Boolean {
        return currentAutoState == AutoState.SETTINGS_PAGE
    }

    private fun isRevokeGrant(): Boolean {
        return currentAutoState == AutoState.PERMISSIONS_PAGE ||
                currentAutoState == AutoState.PERMISSIONS_PAGE_TOGGLE ||
                currentAutoState == AutoState.PERMISSIONS_PAGE_DIALOG
    }

    private fun isGrant(): Boolean {
        return currentAutoState == AutoState.PERMISSIONS_GRANT
    }

    private fun revokePermissions(): Boolean {

        // remove from package when launcher-top is the stage
        if (packagesStack.isNullOrEmpty()) {
            Log.i(LOG_TAG_PERMISSION, "PackagesStack is empty, no tasks queued up, nothing to do.")
            // currentAutoState = AutoState.DORMANT
            return false
        }
        val currentPackage = packagesStack.elementAt(0)
        packagesStack.remove(currentPackage)
        packageElect = currentPackage

        startSettingsPermissionActivity(currentPackage)

        return true
    }

    private fun clickToDisablePermissions(event: AccessibilityEvent) {
        currentAutoState = AutoState.PERMISSIONS_PAGE_TOGGLE

        val source = event.source

        val listItems: List<AccessibilityNodeInfo>? =
            source.findAccessibilityNodeInfosByViewId(permissionsListId)

        val togglesRequired: ArrayList<AccessibilityNodeInfo> = ArrayList()

        // TODO: verify packageElect with the title shown on the current the page
        val packageInfo = getPackageInfo(packageElect) ?: return

        val appPermissions = AppPermissions(packageManager, packageInfo)

        Log.w(LOG_TAG_PERMISSION, "___________________________________ bbb ${appPermissions.permissionGroups.size}")

        for (n in appPermissions.permissionGroups) {
            var px = ""
            for (p in n.permissions) {
                px = p.key + " " + p
            }
            Log.w(LOG_TAG_PERMISSION, "____ bbb label/name/hasPer: ${n.label}/${n.name} $px ____ bbb")
        }


        if (listItems != null && listItems.isNotEmpty()) {
            val children = findChildren(listItems[0])
            var what = ""
            for (element in children) {
                val item: AccessibilityNodeInfo = element
                val switches: List<AccessibilityNodeInfo> =
                    source.findAccessibilityNodeInfosByViewId(permissionsSwitchId)
                if (switches.isEmpty()) {
                    Log.w(LOG_TAG_PERMISSION, "____ bbbbb couldn't find toggle-switch for $item")
                    continue
                }
                val switch = switches[0]
                val temp: List<AccessibilityNodeInfo>  = item.findAccessibilityNodeInfosByViewId("android:id/title")


                val permissionGroup: AppPermissionGroup? = appPermissions.getPermissionGroup(temp[0].text)
                what = what + permissionGroup?.label + " ${temp[0].text} " + switch.isChecked + " ${switches.size} granted? ${permissionGroup?.areRuntimePermissionsGranted()} ___ bbbb; "

                if ((permissionGroup != null && permissionGroup.areRuntimePermissionsGranted())) {
                    togglesRequired.add(item)
                }
            }
            Log.w(LOG_TAG_PERMISSION, what)

            for (item in togglesRequired) {
                // label https://github.com/aosp-mirror/platform_packages_apps_packageinstaller/blob/pie-release-2/src/com/android/packageinstaller/permission/ui/television/AllAppPermissionsFragment.java#L247
                item.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                val performed2 = item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.w(LOG_TAG_PERMISSION, "bbbb uncheck _______ perf? " + performed2 + " .... newchecked? " + item.isChecked + " " + item)
                // process just the first successful click
                if (performed2) break
            }

            Log.w(LOG_TAG_PERMISSION, "____ bbbb nocookie " + event.source + " togglesRequired? " + togglesRequired.size)

            if (togglesRequired.isEmpty()) {
                revokePermissionsThirdStage(event)
            }
        } else {
            Log.w(LOG_TAG_PERMISSION, "____ bbbb empty list for android:id/switch_widget ")
        }
    }

    private fun navigateToLauncher() {
        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    // https://github.com/aosp-mirror/platform_packages_apps_settings/blob/eae6f13389e3ec913db35079ee9e44b5abec19e1/src/com/android/settings/applications/appinfo/AppPermissionPreferenceController.java#L100
    // https://github.com/aosp-mirror/platform_packages_apps_packageinstaller/blob/ed73c7a484ced5c9d238a673510cba109538983e/src/com/android/packageinstaller/permission/ui/handheld/AppPermissionsFragment.java
    // https://github.com/aosp-mirror/platform_packages_apps_packageinstaller/blob/ed73c7a484ced5c9d238a673510cba109538983e/src/com/android/packageinstaller/permission/model/AppPermissions.java
    private fun navigateToPermissionsPage(event: AccessibilityEvent) {
        currentAutoState = AutoState.PERMISSIONS_PAGE

        // multiple permission grant screens
        // deny anyway not working, because a retry is need after click
        // maintain state after typing deny anyway
        val preferenceList: List<AccessibilityNodeInfo> =
            event.source.findAccessibilityNodeInfosByText(settingsLabelPermission)
        Log.w(LOG_TAG_PERMISSION, "bbbbb_____ pereference permission found? " + preferenceList.size + " for " + event)

        for (node: AccessibilityNodeInfo in preferenceList) {
            Log.w(LOG_TAG_PERMISSION, "_______ bbbbb parent ###+++ " + node.parent?.parent)
            if (node.parent?.parent == null) {
                Log.w(LOG_TAG_PERMISSION, "Something changed with the layout, expected: tv -> relative -> linear")
                continue
            }
            node.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            val performed2 = node.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            val performed3 = node.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.w(LOG_TAG_PERMISSION, "bbbbb uncheck _______ perf? $performed2 .... performed3? $performed3")
        }
    }

    // com.google.android.gm
    private fun startSettingsPermissionActivity(packageName: String) {
        currentAutoState = AutoState.SETTINGS_PAGE
        Intent("android.settings.APPLICATION_DETAILS_SETTINGS").also {
            it.setPackage("com.android.settings")
            it.data = Uri.parse("package:$packageName")
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            accessibilityService.startActivity(it)
            Log.w("_______", "launched " + it.data)
        }
    }

    private fun clickDenyIfPresent(event: AccessibilityEvent): Boolean {
        val denyButtons = event.source.findAccessibilityNodeInfosByViewId(packageInstallerDenyAnywayId)

        Log.w(LOG_TAG_PERMISSION, "bbbbb ____ denybuttonpresent? ${denyButtons.size}")
        for (node in denyButtons) {
            Log.w(LOG_TAG_PERMISSION, "_______ bbbbb node ###+++ $node")
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            val performed2 = node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            val performed3 = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.w(LOG_TAG_PERMISSION, "bbbbb click _______ perf2? $performed2 .... performed3? $performed3")
        }
        return true
    }

    private fun clickAllowIfPresent(event: AccessibilityEvent): Boolean {
        // 11-10 20:28:56.568  1711  1711 W  : onAEvent: package com.google.android.packageinstaller
        // text? ContentChangeTypeDisappeared: null [Allow Uber to access this device's location?]
        // class? com.android.packageinstaller.permission.ui.GrantPermissionsActivity

        Log.w(LOG_TAG_PERMISSION, "_____ bbbb clickallow: $latestTrackedPackage")
        val packageInfo = getPackageInfo(latestTrackedPackage) ?: return false

        val appLabel = getAppLabel(packageInfo)
        val confirmation = event.text.contains(appLabel)
        AppPermissions(packageManager, packageInfo)

        val list: List<AccessibilityNodeInfo> = event.source.findAccessibilityNodeInfosByViewId(permissionsAllowButtonId)
        Log.w(LOG_TAG_PERMISSION, "____ bbbbb click allow ? ${list.size}")

        if (confirmation) {
            Log.w(LOG_TAG_PERMISSION, "click allow bbbbb _______ event text contains appLabel")
        }

        for (node in list) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.w(LOG_TAG_PERMISSION, "______ bbbb clicked on allow first $node")
        }

        return true
    }

    private fun findChildren(parent: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val children: LinkedList<AccessibilityNodeInfo> = LinkedList()
        for (i in 0 until parent.childCount) {
            val c = parent.getChild(i)
            children.push(c)
        }
        return children
    }

    fun findRecurisvely(source: AccessibilityNodeInfo, viewId: String) {

        for (i in 0 until source.childCount) {
            val c = source.getChild(i)
            if (c != null) {
                val cl: List<AccessibilityNodeInfo> = c.findAccessibilityNodeInfosByViewId(viewId)
                val w = "" + c.isCheckable + " " + c.isChecked + " " + c.viewIdResourceName + " " + c.className + " " + cl.size
                Log.w("____", "____ children $w")
                findRecurisvely(c, viewId)
            } else Log.w("____", "____ children null $viewId")
        }
    }

    // https://stackoverflow.com/a/23110115
    private fun isPackageLauncher(packageName: String?): Boolean {
        if (TextUtils.isEmpty(packageName)) return false
        val intent = Intent("android.intent.action.MAIN")
        intent.addCategory("android.intent.category.HOME")
        val thisPackage = packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return thisPackage == packageName
    }

    private fun isPackageSettings(packageName: String): Boolean {
        return packageSettings == packageName
    }

    private fun isPackageInstaller(packageName: String?): Boolean {
        return packageInstaller == packageName
    }

    private fun isAutoGrant(event: AccessibilityEvent?): Boolean {
        if (event?.source == null) return false
        val packageName = event.source!!.packageName.toString()
        return isAutoGrant(packageName)
    }

    private fun isAutoGrant(packageName: String?): Boolean {
        if (packageName == null) return false
        val rule = packageRules[packageName]
        return Rules.BG_REMOVE_FG_ADD == rule
    }

    private fun getPackageInfo(packageName: String?): PackageInfo? {
        if (packageName == null) return null
        return try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(LOG_TAG_PERMISSION, "No package: $packageName", e)
            null
        }
    }

    private fun getAppLabel(packageInfo: PackageInfo): String {
        return BidiFormatter.getInstance().unicodeWrap(
            loadSafeLabel(packageInfo.applicationInfo.loadLabel(packageManager).toString(),
                packageInfo.packageName).toString())
    }

    // https://github.com/aosp-mirror/platform_frameworks_base/blob/pie-dev/core/java/android/content/pm/PackageItemInfo.java#L96
    // Replace this with TextUtils#makeSafeForPresentation?
    private fun loadSafeLabel(label: String, defaultSafeLabel: String): CharSequence {

        val maxSafeLabelLength = 50000

        // strip HTML tags to avoid <br> and other tags overwriting original message
        var labelStr = HtmlCompat.fromHtml(label, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

        // If the label contains new line characters it may push the UI
        // down to hide a part of it. Labels shouldn't have new line
        // characters, so just truncate at the first time one is seen.
        val labelLength = Math.min(labelStr.length, maxSafeLabelLength)
        val sb = StringBuffer(labelLength)
        var offset = 0
        while (offset < labelLength) {
            val codePoint = labelStr.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (type == Character.LINE_SEPARATOR.toInt()
                || type == Character.CONTROL.toInt()
                || type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) {
                labelStr.substring(0, offset)
                break
            }
            // replace all non-break space to " " in order to be trimmed
            val charCount = Character.charCount(codePoint)
            if (type == Character.SPACE_SEPARATOR.toInt()) {
                sb.append(' ')
            } else {
                sb.append(labelStr[offset])
                if (charCount == 2) {
                    sb.append(labelStr[offset + 1])
                }
            }
            offset += charCount
        }

        labelStr = sb.toString().trim { it <= ' ' }
        if (labelStr.isEmpty()) {
            return defaultSafeLabel
        }
        val paint = TextPaint()
        paint.textSize = 42f

        return TextUtils.ellipsize(
            labelStr, paint, 500f /* DEFAULT_MAX_LABEL_SIZE_PX */,
            TextUtils.TruncateAt.END
        )
    }

}