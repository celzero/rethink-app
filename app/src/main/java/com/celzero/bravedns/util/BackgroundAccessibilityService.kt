package com.celzero.bravedns.util

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.*
import com.celzero.bravedns.automaton.FirewallManager

class BackgroundAccessibilityService  : AccessibilityService() {

    private val firewallManager = FirewallManager(this)
    override fun onInterrupt() {
        Log.w("______","Interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
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

        /*Log.w("______","onAEvent: sourcepack " + event.source?.packageName + " text? " +
                eventText + " class? " + event.className +
                " package? ppp " + event.packageName)*/

        firewallManager.onAccessibilityEvent(event, this)
    }

}