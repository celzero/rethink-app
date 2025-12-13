/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.util

import Logger
import Logger.LOG_TAG_FIREWALL
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Utilities.isAtleastP
import com.celzero.bravedns.util.Utilities.isAtleastT
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

class BackgroundAccessibilityService : AccessibilityService(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    private var possibleUid: Int? = null
    private var possibleAppName: String? = null

    override fun onServiceConnected() {
        // Service connected
    }

    override fun onInterrupt() {
        Logger.w(LOG_TAG_FIREWALL, "BackgroundAccessibilityService Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        handleAccessibilityEvent(event)
    }

    // Handle the received event.
    // Earlier the event handling is taken care in FirewallManager.
    // Now, the firewall manager usage is modified, so moving this part here.
    private fun handleAccessibilityEvent(event: AccessibilityEvent) {

        // ref:
        // https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.html#lifecycle
        // see:
        // https://stackoverflow.com/questions/40433449/how-can-i-programmatically-start-and-stop-an-accessibilityservice
        // no need to handle the events when the vpn is not running
        @Suppress("DEPRECATION")
        if (!VpnController.isOn()) return

        if (!persistentState.getBlockAppWhenBackground()) return

        val latestTrackedPackage = getEventPackageName(event)

        if (latestTrackedPackage.isNullOrEmpty()) return

        val hasContentChanged =
            if (isAtleastP()) {
                event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            } else {
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            }
        Logger.d(
            LOG_TAG_FIREWALL,
            "onAccessibilityEvent: ${event.packageName}, ${event.eventType}, $hasContentChanged")

        if (!hasContentChanged) return

        // If the package received is Rethink, do nothing.
        if (event.packageName == this.packageName) return

        possibleUid = getEventUid(latestTrackedPackage) ?: return

        possibleAppName = Utilities.getPackageInfoForUid(this, possibleUid!!)?.firstOrNull()

        // https://stackoverflow.com/a/27642535
        // top window is launcher? try revoke queued up permissions
        // FIXME: Figure out a fool-proof way to determine is launcher visible
        // TODO: Handle widgets on the homescreen
        if (isPackageLauncher(latestTrackedPackage)) {
            FirewallManager.untrackForegroundApps()
        } else {
            val uid = possibleUid ?: return
            FirewallManager.trackForegroundApp(uid)
        }
    }

    // https://stackoverflow.com/questions/45620584/event-getsource-returns-null-in-accessibility-service-catch-source-for-a-3rd-p
    /**
     * If the event retrieved package name is null then the check for the package name is carried
     * out in (getRootInActiveWindow)AccessibilityNodeInfo
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

        // #428- Issue fix
        // there is a case where the accessibility services received event from the package
        // which is not part of Android's packageInfo. This method in utilities will handle the
        // error in that case and will return null instead of exception
        // return this.packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA).uid
        return Utilities.getApplicationInfo(this, pkgName)?.uid
    }

    private fun isPackageLauncher(packageName: String?): Boolean {
        if (TextUtils.isEmpty(packageName)) return false

        val intent = Intent("android.intent.action.MAIN")
        intent.addCategory("android.intent.category.HOME")
        // package manager returns null,
        val thisPackage =
            if (isAtleastT()) {
                this.packageManager
                    .resolveActivity(
                        intent,
                        PackageManager.ResolveInfoFlags.of(
                            PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                    ?.activityInfo
                    ?.packageName
            } else {
                this.packageManager
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo
                    ?.packageName
            }
        return thisPackage == packageName
    }
}
