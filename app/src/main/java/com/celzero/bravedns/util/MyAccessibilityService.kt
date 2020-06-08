package com.celzero.bravedns.util

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityRecord
import com.celzero.bravedns.automaton.PermissionsManager
import com.celzero.bravedns.automaton.PrivateDnsManager


class MyAccessibilityService : AccessibilityService() {

    private val permissionsManager = PermissionsManager(this)
    private val privateDnsManager = PrivateDnsManager(this)

    enum class PrivateDnsMode (val mode: Int) {
        NONE(0),
        PRIVATE_DNS_SET(1),
        PRIVATE_DNS_UNSET(2)
    }

    companion object mode {
        var privateDnsMode: PrivateDnsMode = PrivateDnsMode.NONE

        fun setPrivateDnsMode() {
            privateDnsMode = PrivateDnsMode.PRIVATE_DNS_SET
        }

        fun unsetPrivateDnsMode() {
            privateDnsMode = PrivateDnsMode.PRIVATE_DNS_UNSET
        }

        fun isSetPrivateDnsMode(): Boolean {
            return privateDnsMode == PrivateDnsMode.PRIVATE_DNS_SET
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventText = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "Clicked: "
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WindowsChanged: "
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> "Announcement: "
            AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> "AssitReading: "
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "GestureDetectionEnd: "
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "GestureDetectionStart: "
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NotificationStateChanged: "
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "ViewContextClicked: "
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "ViewAccessibilityFocused: "
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "ViewAccessibilityFocus: "
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "ViewFocused: "
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "ViewHoverEnter: "
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "ViewHoverExit: "
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "ViewLongClicked: "
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "ViewScrolled: "
            AccessibilityEvent.CONTENTS_FILE_DESCRIPTOR -> "ContentsFileDesc: "
            AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION -> "ContentChangeTypeDesc: "
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED -> "ContentChangeTypeAppeared: "
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED -> "ContentChangeTypeDisappeared: "
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE -> "ContentChangeTypePaneTitle: "
            AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE -> "ContentChangeTypeSubtree: "
            AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT -> "ContentChangeTypeText: "
            AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED -> "ContentChangeTypeUndefined: "
            AccessibilityEvent.INVALID_POSITION -> "InvalidPosition: "
            AccessibilityEvent.PARCELABLE_WRITE_RETURN_VALUE -> "ParacelableWriteReturnValue: "
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "TypeViewSelected: "
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TypeViewTextChanged: "
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "TypeViewContextClicked: "
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TypeViewTextSelectionChanged: "
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "TypeViewTextTraversedAtGran: "
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TypeWindowContentChanged: "
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TypeWindowStateChanged: "
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TypeWindowChanged: "
            AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED -> "WindowsChangedAccFocused: "
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE -> "WindowsChangeActive: "
            AccessibilityEvent.WINDOWS_CHANGE_ADDED -> "WindowsChangeAdded: "
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS -> "WindowsChangeBounds: "
            AccessibilityEvent.WINDOWS_CHANGE_CHILDREN -> "WindowsChangeChildren: "
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED -> "WindowsChangeFocused: "
            AccessibilityEvent.WINDOWS_CHANGE_LAYER -> "WindowsChangeLayer: "
            AccessibilityEvent.WINDOWS_CHANGE_PARENT -> "WindowsChangeParent: "
            AccessibilityEvent.WINDOWS_CHANGE_PIP -> "WindowsChangePip: "
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED -> "WindowsChangeRemoved: "
            AccessibilityEvent.WINDOWS_CHANGE_TITLE -> "WindowsChangedTite: "
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TypeViewTextSelectionChanged: "
            else -> "Default: "
        } + event.contentDescription + " " + event.text

        Log.w("______","onAEvent: sourcepack " + event.source?.packageName + " text? " +
                eventText + " class? " + event.className +
                " package? ppp " + event.packageName)
        for (i in 0 until event.recordCount) {
            val r: AccessibilityRecord = event.getRecord(i)
            //Log.w("PermissionsManager", "_____ record record $$$$ ______ ${r.source} ${r.className} ${r.contentDescription}")
        }

        if (isPrivateDnsMode()) {
            privateDnsManager.onAccessibilityEvent(event)
        } else {
            permissionsManager.onAccessibilityEvent(event, this)
        }

    }

    private fun isPrivateDnsMode(): Boolean {
        return privateDnsMode == PrivateDnsMode.PRIVATE_DNS_SET ||
                privateDnsMode == PrivateDnsMode.PRIVATE_DNS_UNSET
    }

    fun privateDnsOver() {
        privateDnsMode = PrivateDnsMode.NONE
        privateDnsManager.reset()
    }

    override fun onServiceConnected() {
        Log.w("______", "accessibility service connected")
    }

    override fun onInterrupt() {
        Log.w("______","Interrupted")
    }
}