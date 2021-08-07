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
package com.celzero.bravedns.util

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.service.VpnControllerHelper
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL

class BackgroundAccessibilityService : AccessibilityService() {

    override fun onInterrupt() {
        Log.w(LOG_TAG_FIREWALL, "BackgroundAccessibilityService Interrupted")
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Commenting out the below code - warning during the build process
        /*if(DEBUG) {
            val eventText = when (event.eventType) {
                TYPE_VIEW_CLICKED -> "Clicked: "
                TYPE_WINDOWS_CHANGED -> "WindowsChanged: "
                TYPE_ANNOUNCEMENT -> "Announcement: "
                TYPE_ASSIST_READING_CONTEXT -> "AssitReading: "
                TYPE_GESTURE_DETECTION_END -> "GestureDetectionEnd: "
                TYPE_GESTURE_DETECTION_START -> "GestureDetectionStart: "
                TYPE_NOTIFICATION_STATE_CHANGED -> "NotificationStateChanged: "
                TYPE_VIEW_CONTEXT_CLICKED -> "ViewContextClicked: "
                TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "ViewAccessibilityFocused: "
                TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "ViewAccessibilityFocus: "
                TYPE_VIEW_FOCUSED -> "ViewFocused: "
                TYPE_VIEW_HOVER_ENTER -> "ViewHoverEnter: "
                TYPE_VIEW_HOVER_EXIT -> "ViewHoverExit: "
                TYPE_VIEW_LONG_CLICKED -> "ViewLongClicked: "
                TYPE_VIEW_SCROLLED -> "ViewScrolled: "
                CONTENTS_FILE_DESCRIPTOR -> "ContentsFileDesc: "
                CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION -> "ContentChangeTypeDesc: "
                CONTENT_CHANGE_TYPE_PANE_APPEARED -> "ContentChangeTypeAppeared: "
                CONTENT_CHANGE_TYPE_PANE_DISAPPEARED -> "ContentChangeTypeDisappeared: "
                CONTENT_CHANGE_TYPE_PANE_TITLE -> "ContentChangeTypePaneTitle: "
                CONTENT_CHANGE_TYPE_SUBTREE -> "ContentChangeTypeSubtree: "
                CONTENT_CHANGE_TYPE_TEXT -> "ContentChangeTypeText: "
                CONTENT_CHANGE_TYPE_UNDEFINED -> "ContentChangeTypeUndefined: "
                INVALID_POSITION -> "InvalidPosition: "
                PARCELABLE_WRITE_RETURN_VALUE -> "ParacelableWriteReturnValue: "
                TYPE_VIEW_SELECTED -> "TypeViewSelected: "
                TYPE_VIEW_TEXT_CHANGED -> "TypeViewTextChanged: "
                TYPE_VIEW_CONTEXT_CLICKED -> "TypeViewContextClicked: "
                TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TypeViewTextSelectionChanged: "
                TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "TypeViewTextTraversedAtGran: "
                TYPE_WINDOW_CONTENT_CHANGED -> "TypeWindowContentChanged: "
                TYPE_WINDOW_STATE_CHANGED -> "TypeWindowStateChanged: "
                TYPE_WINDOWS_CHANGED -> "TypeWindowChanged: "
                WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED -> "WindowsChangedAccFocused: "
                WINDOWS_CHANGE_ACTIVE -> "WindowsChangeActive: "
                WINDOWS_CHANGE_ADDED -> "WindowsChangeAdded: "
                WINDOWS_CHANGE_BOUNDS -> "WindowsChangeBounds: "
                WINDOWS_CHANGE_CHILDREN -> "WindowsChangeChildren: "
                WINDOWS_CHANGE_FOCUSED -> "WindowsChangeFocused: "
                WINDOWS_CHANGE_LAYER -> "WindowsChangeLayer: "
                WINDOWS_CHANGE_PARENT -> "WindowsChangeParent: "
                WINDOWS_CHANGE_PIP -> "WindowsChangePip: "
                WINDOWS_CHANGE_REMOVED -> "WindowsChangeRemoved: "
                WINDOWS_CHANGE_TITLE -> "WindowsChangedTite: "
                TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TypeViewTextSelectionChanged: "
                else -> "Default: "
            } + event.contentDescription + " " + event.text
        }*/
        /*Log.w("______","onAEvent: sourcepack " + event.source?.packageName + " text? " +
                eventText + " class? " + event.className +
                " package? ppp " + event.packageName)*/
        handleAccessibilityEvent(event)
    }

    // Handle the received event.
    // Earlier the event handling is taken care in FirewallManager.
    // Now, the firewall manager usage is modified, so moving this part here. 
    fun handleAccessibilityEvent(event: AccessibilityEvent) {

        if (!VpnControllerHelper.persistentState.backgroundEnabled) return

        val latestTrackedPackage = getEventPackageName(event)

        if (latestTrackedPackage.isNullOrEmpty()) return

        val hasContentChanged = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "onAccessibilityEvent: ${event.packageName}, ${event.eventType}, $hasContentChanged")

        if (!hasContentChanged) return

        // If the package received is Rethink, do nothing.
        if (event.packageName == this.packageName) return

        // https://stackoverflow.com/a/27642535
        // top window is launcher? try revoke queued up permissions
        // FIXME: Figure out a fool-proof way to determine is launcher visible
        if (isPackageLauncher(latestTrackedPackage)) {
            FirewallManager.untrackForegroundApps()
        } else {
            val uid = getEventUid(latestTrackedPackage) ?: return
            FirewallManager.trackForegroundApp(uid)
        }

    }

    // https://stackoverflow.com/questions/45620584/event-getsource-returns-null-in-accessibility-service-catch-source-for-a-3rd-p
    /**
     * If the event retrieved package name is null then the check for the package name
     * is carried out in (getRootInActiveWindow)AccessibilityNodeInfo
     */
    private fun getEventPackageName(event: AccessibilityEvent): String? {
        return if (event.packageName.isNullOrBlank()) {
            this.rootInActiveWindow?.packageName?.toString()
        } else {
            event.packageName.toString()
        }
    }

    private fun getEventUid(pkgName: String): Int? {
        if (pkgName.isBlank()) return null

        return this.packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA).uid
    }

    private fun isPackageLauncher(packageName: String?): Boolean {
        if (TextUtils.isEmpty(packageName)) return false

        val intent = Intent("android.intent.action.MAIN")
        intent.addCategory("android.intent.category.HOME")
        val thisPackage = this.packageManager.resolveActivity(intent,
                                                              PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return thisPackage == packageName
    }

}
