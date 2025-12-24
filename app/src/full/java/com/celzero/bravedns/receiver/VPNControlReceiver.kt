/*
 * Copyright 2025 RethinkDNS and its authors
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

package com.celzero.bravedns.receiver

import Logger
import Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isAtleastU
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class VpnControlReceiver: BroadcastReceiver(), KoinComponent {
    private val persistentState by inject<PersistentState>()
    companion object {
        private const val TAG = "VpnCtrlRecr"
        private const val ACTION_START = "com.celzero.bravedns.intent.action.VPN_START"
        private const val ACTION_STOP = "com.celzero.bravedns.intent.action.VPN_STOP"
        private const val STOP_REASON = "tasker_stop"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) {
            Logger.w(LOG_TAG_VPN, "$TAG Received null action intent")
            return
        }

        val allowedPackages = persistentState.appTriggerPackages.split(",").map { it.trim() }.toSet()
        Logger.d(LOG_TAG_VPN, "$TAG Allowed packages: $allowedPackages")
        if (allowedPackages.isEmpty()) {
            Logger.i(LOG_TAG_VPN, "$TAG No allowed packages, ignoring intent")
            return
        }

        val callerPkg = getCallerPkg(context, intent)
        if (callerPkg == null) {
            Logger.w(LOG_TAG_VPN, "$TAG Received intent with null package name")
            return
        }

        if (!allowedPackages.contains(callerPkg)) {
            Logger.w(LOG_TAG_VPN, "$TAG Received intent from untrusted package: $callerPkg")
            return
        }

        if (intent.action == ACTION_START) {
            handleVpnStart(context)
        }
        if (intent.action == ACTION_STOP) {
            handleVpnStop(context)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleVpnStart(context: Context) {
        if (VpnController.isOn()) {
            Logger.i(LOG_TAG_VPN, "$TAG VPN is already running, ignoring start intent")
            return
        }
        val prepareVpnIntent: Intent? =
            try {
                Logger.i(LOG_TAG_VPN, "$TAG Attempting to prepare VPN before starting")
                VpnService.prepare(context)
            } catch (_: NullPointerException) {
                // This shouldn't happen normally as Broadcast Intent sender apps like Tasker
                // won't come up as early as Always-on VPNs
                // Context can be null in case of auto-restart VPNs:
                // ref stackoverflow.com/questions/73147633/getting-null-in-context-while-auto-restart-with-broadcast-receiver-in-android-ap
                Logger.w(LOG_TAG_VPN, "$TAG Device does not support system-wide VPN mode")
                return
            }
        if (prepareVpnIntent == null) {
            Logger.i(LOG_TAG_VPN, "$TAG VPN is prepared, invoking start")
            VpnController.start(context)
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun handleVpnStop(context: Context) {
        if (!VpnController.isOn()) {
            Logger.i(LOG_TAG_VPN, "$TAG VPN is not running, ignoring stop intent")
            return
        }
        if (VpnController.isAlwaysOn(context)) {
            Logger.w(LOG_TAG_VPN, "$TAG VPN is always-on, ignoring stop intent")
            return
        }

        Logger.i(LOG_TAG_VPN, "$TAG VPN stopping")
        VpnController.stop(STOP_REASON, context)
    }

    private fun getCallerPkg(context: Context, intent: Intent): String? {
        // package name of the app that sent the broadcast
        if (DEBUG) dumpIntent(intent)

        // expecting the intent to have a package name in the extras with key "sender"
        var callerPkg = intent.getStringExtra("sender")
        if (callerPkg != null) {
            Logger.i(LOG_TAG_VPN, "$TAG Received intent from extra sender: $callerPkg, from sender")
            return callerPkg
        }

        // above 34 (Android U) sentFromPackage and sentFromUid are available
        if (isAtleastU()) {
            callerPkg = sentFromPackage
            if (callerPkg != null) {
                Logger.i(LOG_TAG_VPN, "$TAG Received intent from sentFromPackage: $callerPkg")
                return callerPkg
            }
            val uid = sentFromUid
            callerPkg = context.packageManager.getPackagesForUid(uid)?.firstOrNull()
            if (callerPkg != null) {
                Logger.i(LOG_TAG_VPN, "$TAG received intent from sentFromUid, $uid, $callerPkg")
                return callerPkg
            }
        }

        // see if the intent has a package name in the extras with key "EXTRA_PACKAGE_NAME" or
        // "EXTRA_INTENT", less reliable method unless the app explicitly sets it
        // Intent.EXTRA_PACKAGE_NAME is typically for identifying the target package of an explicit
        // intent, not usually the sender of a broadcast. However, checking it doesn't hurt.
        // Intent.EXTRA_INTENT might contain package name.
        callerPkg = if (isAtleastT()) {
            intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        } else {
            intent.getStringExtra(Intent.EXTRA_INTENT)
        }
        if (callerPkg != null) {
            Logger.i(LOG_TAG_VPN, "$TAG Received intent from extra sender: $callerPkg, from sender")
            return callerPkg
        }


        Logger.i(LOG_TAG_VPN, "$TAG could not determine caller pkg")
        return callerPkg
    }

    fun dumpIntent(intent: Intent) {
        val sb = StringBuilder()
        sb.append("Intent content:\n")
        sb.append("Action: ${intent.action}\n")
        sb.append("Data: ${intent.data}\n")
        sb.append("Type: ${intent.type}\n")
        sb.append("Categories: ${intent.categories}\n")
        sb.append("Component: ${intent.component}\n")
        sb.append("Package: ${intent.`package`}\n")
        sb.append("Extra :${intent.getStringExtra(Intent.EXTRA_REFERRER)}\n")
        sb.append("Flags: ${intent.flags}\n")
        sb.append("Source bounds: ${intent.sourceBounds}\n")
        if (isAtleastT()) sb.append("Sender: ${intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)}\n")
        if (isAtleastU()) {
            sb.append("Sent from package: $sentFromPackage\n")
            sb.append("Sent from UID: $sentFromUid\n")
        }
        sb.append("Extras:\n")
        val extras = intent.extras
        if (extras == null) {
            sb.append("No extras\n")
            Logger.i(LOG_TAG_VPN, "$TAG $sb ")
            return
        }

        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            sb.append("  $key -> ${extras.get(key)}\n")
        }

        Logger.i(LOG_TAG_VPN, "$TAG $sb")
    }
}
