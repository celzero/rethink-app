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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isAtleastU
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receiver for external automation apps.
 *
 * Actions (all require the sender package to be in [PersistentState.appTriggerPackages]):
 *  - [ACTION_START] - start the Rethink VPN
 *  - [ACTION_STOP] - stop the Rethink VPN
 *  - [ACTION_WG_START] - enable wireguard config(s), adds the proxy to the tunnel
 *  - [ACTION_WG_STOP] - disable wireguard config(s), removes the proxy from the tunnel
 *  - [ACTION_WG_PAUSE] - pause live wireguard proxy(es)
 *  - [ACTION_WG_RESUME] - resume live wireguard proxy(es)
 *
 * Caller identity:
 *  - Android 14 (API 34) and above: resolved exclusively from the OS-provided
 *    [BroadcastReceiver.getSentFromPackage] / [BroadcastReceiver.getSentFromUid]; intent extras
 *    are never trusted, even when the OS identity is absent.
 *  - Below Android 14: no OS-provided broadcast sender identity exists, so the legacy
 *    [EXTRA_SENDER] extra convention is honored instead. NOTE: that extra is sender-controlled
 *    and therefore spoofable on this path; it is kept solely to preserve automation support on
 *    older Android versions.
 *
 * Extras:
 *  - [EXTRA_SENDER] (String, legacy, only honored below Android 14): calling package name
 *  - [EXTRA_WG_IDS] (String, required for the WG_* actions): comma separated wireguard config
 *    ids, e.g. "1,3"
 *
 * The WG_* actions are only honored when [PersistentState.wgTaskerAutomationEnabled] is on.
 */
class VpnControlReceiver: BroadcastReceiver(), KoinComponent {
    private val persistentState by inject<PersistentState>()
    private val appScope by inject<CoroutineScope>()

    // seam for tests; executes validated wg commands
    internal var wgCommands: WgCommands = WgCommands()

    // seam for tests; resolves the caller identity (never from untrusted extras on API 34+)
    internal var callerResolver: (Context, Intent) -> String? =
        { context, intent -> defaultCallerResolver(context, intent) }

    companion object {
        private const val TAG = "VpnCtrlRecr"
        const val ACTION_START = "com.celzero.bravedns.intent.action.VPN_START"
        const val ACTION_STOP = "com.celzero.bravedns.intent.action.VPN_STOP"
        const val ACTION_WG_START = "com.celzero.bravedns.intent.action.WG_START"
        const val ACTION_WG_STOP = "com.celzero.bravedns.intent.action.WG_STOP"
        const val ACTION_WG_PAUSE = "com.celzero.bravedns.intent.action.WG_PAUSE"
        const val ACTION_WG_RESUME = "com.celzero.bravedns.intent.action.WG_RESUME"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_WG_IDS = "wg_ids"
        private const val STOP_REASON = "tasker_stop"

        /**
         * Parses the [EXTRA_WG_IDS] extra (comma separated config ids, e.g. "1, 3").
         * Blank segments and non-numeric segments are dropped; an empty/missing value
         * yields an empty list which callers should treat as an invalid intent.
         */
        fun parseWgIds(raw: String?): List<Int> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        }
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

        when (intent.action) {
            ACTION_START -> handleVpnStart(context)
            ACTION_STOP -> handleVpnStop(context)
            ACTION_WG_START, ACTION_WG_STOP, ACTION_WG_PAUSE, ACTION_WG_RESUME ->
                handleWgAction(context, intent)
            else -> Logger.w(LOG_TAG_VPN, "$TAG Received unknown action: ${intent.action}")
        }
    }

    private fun handleWgAction(context: Context, intent: Intent) {
        if (!persistentState.wgTaskerAutomationEnabled) {
            Logger.i(
                LOG_TAG_VPN,
                "$TAG wg tasker automation is disabled, ignoring action: ${intent.action}"
            )
            return
        }

        val ids = parseWgIds(intent.getStringExtra(EXTRA_WG_IDS))
        if (ids.isEmpty()) {
            Logger.w(
                LOG_TAG_VPN,
                "$TAG missing or invalid $EXTRA_WG_IDS extra, ignoring action: ${intent.action}"
            )
            return
        }

        Logger.i(LOG_TAG_VPN, "$TAG handling ${intent.action} for wg ids: $ids")
        // wg commands touch the db + go backend, run them off the main thread; goAsync-style
        // correctness is not critical here as the commands are idempotent
        val pendingResult = goAsync()
        appScope.launch {
            try {
                val failures = when (intent.action) {
                    ACTION_WG_START -> wgCommands.start(ids)
                    ACTION_WG_STOP -> wgCommands.stop(ids)
                    ACTION_WG_PAUSE -> wgCommands.pause(ids)
                    ACTION_WG_RESUME -> wgCommands.resume(ids)
                    else -> emptyList()
                }
                WgAutomationNotifier.onCommandResult(context, intent.action, ids.size, failures)
            } finally {
                pendingResult.finish()
            }
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
            } catch (e: IllegalStateException) {
                Logger.w(LOG_TAG_VPN, "$TAG VPN unavailable: in lockdown mode", e)
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
        return callerResolver(context, intent)
    }

    internal fun defaultCallerResolver(context: Context, intent: Intent): String? {
        if (DEBUG) dumpIntent(intent)

        // Android 14 (API 34) and above: the OS stamps the sender identity on every broadcast;
        // only trust that, never sender-controlled extras
        if (isAtleastU()) {
            val pkg = sentFromPackage
                ?: context.packageManager.getPackagesForUid(sentFromUid)?.firstOrNull()
            if (pkg != null) {
                Logger.i(LOG_TAG_VPN, "$TAG caller from OS-provided identity: $pkg")
                return pkg
            }
            Logger.w(LOG_TAG_VPN, "$TAG no OS-provided sender identity, rejecting intent")
            return null
        }

        // below Android 14 there is no OS-provided broadcast sender identity; fall back to the
        // legacy "sender" extra convention (spoofable on this path, accepted for automation)
        val legacy = intent.getStringExtra(EXTRA_SENDER)
        if (legacy != null) {
            Logger.i(LOG_TAG_VPN, "$TAG caller from legacy extra: $legacy")
        }
        return legacy
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
