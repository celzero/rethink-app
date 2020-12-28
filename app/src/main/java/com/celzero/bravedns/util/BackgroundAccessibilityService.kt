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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.*
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG

class BackgroundAccessibilityService  : AccessibilityService() {

    private val firewallManager = FirewallManager(this)
    override fun onInterrupt() {
        Log.w("BraveDNS","BackgroundAccessibilityService Interrupted")
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if(DEBUG) {
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
        }
        /*Log.w("______","onAEvent: sourcepack " + event.source?.packageName + " text? " +
                eventText + " class? " + event.className +
                " package? ppp " + event.packageName)*/
        if(BraveVPNService.isBackgroundEnabled) {
            firewallManager.onAccessibilityEvent(event, this, rootInActiveWindow)
        }
    }

}