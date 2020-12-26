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
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.celzero.bravedns.util.MyAccessibilityService


class PrivateDnsManager(private val accessibilityService: MyAccessibilityService) {
    val TAG = "PrivateDnsManager"

    enum class AutoState(val code: Int) {
        DASHBOARD(0),
        DIALOG_DNS(1),
        RADIO_CLICK(2),
        TEXT_ENTRY(3),
        BUTTON_SAVE(4)
    }

    var nextState = AutoState.DASHBOARD

    val privateDnsModeProviderViewId = "com.android.settings:id/private_dns_mode_provider"
    val privateDnsModeOffViewId = "com.android.settings:id/private_dns_mode_off"
    val privateDnsModeHostnameViewId = "com.android.settings:id/private_dns_mode_provider_hostname"
    val saveViewId = "android:id/button1"

    private val dns = "dns.adguard.com"

    fun onAccessibilityEvent(event: AccessibilityEvent) {

        if (event.source == null) {
            return
        }

        val packageName = event.packageName ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            packageName == "com.android.settings" &&
            isDashboard()) {
            val list: List<AccessibilityNodeInfo> = event.source.findAccessibilityNodeInfosByText("Private DNS")
            //Log.w(TAG, "ppppp ____dashboard_____ ${list.size}")
            for (node in list) {
                val perf: Boolean? = node.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                //Log.w(TAG, "ppppp ________ $perf node ${node.parent?.parent}")
                nextState = AutoState.DIALOG_DNS
            }

            // important to return and not do anything else
            return
        }

        if (event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED &&
            packageName == "com.android.settings" &&
            isDialog() &&
            event.className == "android.app.AlertDialog") {

            nextState = AutoState.RADIO_CLICK

            val prov: List<AccessibilityNodeInfo>
            if (MyAccessibilityService.isSetPrivateDnsMode()) {
                prov = event.source.findAccessibilityNodeInfosByViewId(privateDnsModeProviderViewId)
            } else {
                prov = event.source.findAccessibilityNodeInfosByViewId(privateDnsModeOffViewId)
            }

            //Log.w(TAG, "ppppp _____privatednsmode____ ${prov.size}")
            for (node in prov) {
                val perf: Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val perf2: Boolean = node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                //Log.w(TAG, "ppppp ________ cli: $perf sel: $perf2 node $node")
                break
            }
            /*}
            // ContentChangeTypeDesc: null [Private DNS provider hostname] class? android.widget.RadioButton package? ppp com.android.settings

            if (event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION &&
                    packageName == "com.android.settings" &&
                    isRadio() &&
                    event.className == "android.widget.RadioButton") {*/

            val list: List<AccessibilityNodeInfo> =
                event.source.findAccessibilityNodeInfosByViewId(privateDnsModeHostnameViewId)
            val save: List<AccessibilityNodeInfo> =
                event.source.findAccessibilityNodeInfosByViewId(saveViewId)

            //Log.w(TAG, "ppppp _____privatednsmode____ ${list.size} ${save.size}")

            if (MyAccessibilityService.isSetPrivateDnsMode()) {
                nextState = AutoState.TEXT_ENTRY
                for (node in list) {
                    val arguments = Bundle()
                    arguments.putString(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, dns)
                    val perf2: Boolean = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    //Log.w(TAG, "ppppp ________ text: $perf2 node $node")
                    break
                }
            }

            nextState = AutoState.BUTTON_SAVE
            for (node in save) {
                val perf2: Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                //Log.w(TAG, "ppppp ________ text: $perf2 node $node")
                break
            }

            // navigate back to whence we came from
            // FIXME: Figure out why ACTION_BACK doesn't navigate out of settings
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

            // FIXME: do something better rather than cross-deps
            accessibilityService.privateDnsOver()
        }

    }

    fun reset() {
        nextState = AutoState.DASHBOARD
    }

    private fun isDashboard(): Boolean {
        return nextState == AutoState.DASHBOARD
    }

    private fun isDialog(): Boolean {
        return nextState == AutoState.DIALOG_DNS
    }

}