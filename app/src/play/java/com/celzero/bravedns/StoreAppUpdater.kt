/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_APP_UPDATE
import com.celzero.bravedns.util.Utilities
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class StoreAppUpdater(
    context: Context,
    private val persistentState: PersistentState
) : AppUpdater {

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerMapping =
        mutableMapOf<AppUpdater.InstallStateListener, InstallStateUpdatedListener>()

    override fun checkForAppUpdate(
        isInteractive: AppUpdater.UserPresent,
        activity: Activity,
        listener: AppUpdater.InstallStateListener,
        startUpdateFlow: (IntentSender) -> Unit
    ) {
        Logger.i(LOG_TAG_APP_UPDATE, "StoreAppUpdater: beginning update check")
        // drop any stale play listener for this app listener before re-registering
        listenerMapping.remove(listener)?.also { appUpdateManager.unregisterListener(it) }
        val playListener = InstallStateUpdatedListener { state ->
            val mappedStatus =
                when (state.installStatus()) {
                    InstallStatus.DOWNLOADED -> AppUpdater.InstallStatus.DOWNLOADED
                    InstallStatus.CANCELED -> AppUpdater.InstallStatus.CANCELED
                    InstallStatus.DOWNLOADING -> AppUpdater.InstallStatus.DOWNLOADING
                    InstallStatus.FAILED -> AppUpdater.InstallStatus.FAILED
                    InstallStatus.INSTALLED -> AppUpdater.InstallStatus.INSTALLED
                    InstallStatus.INSTALLING -> AppUpdater.InstallStatus.INSTALLING
                    InstallStatus.PENDING -> AppUpdater.InstallStatus.PENDING
                    else -> AppUpdater.InstallStatus.UNKNOWN
                }
            listener.onStateUpdate(AppUpdater.InstallState(mappedStatus))
        }
        listenerMapping[listener] = playListener
        appUpdateManager.registerListener(playListener)

        scope.launch {
            // play's task never completes on some devices; race it with a timeout
            val info =
                withTimeoutOrNull(UPDATE_CHECK_TIMEOUT_MS) {
                    suspendCancellableCoroutine<AppUpdateInfo?> { cont ->
                        appUpdateManager.appUpdateInfo
                            .addOnSuccessListener { r ->
                                if (cont.isActive) cont.resume(r)
                            }
                            .addOnFailureListener { e ->
                                Logger.e(
                                    LOG_TAG_APP_UPDATE,
                                    "StoreAppUpdater: update info task failed: ${e.message}",
                                    e
                                )
                                if (cont.isActive) cont.resume(null)
                            }
                    }
                }
            withContext(Dispatchers.Main) {
                handleUpdateInfo(info, isInteractive, activity, listener, startUpdateFlow)
            }
        }
    }

    private fun handleUpdateInfo(
        info: AppUpdateInfo?,
        isInteractive: AppUpdater.UserPresent,
        activity: Activity,
        listener: AppUpdater.InstallStateListener,
        startUpdateFlow: (IntentSender) -> Unit
    ) {
        // throttle auto-checks for the play path too; the web updater sets this itself
        persistentState.lastAppUpdateCheck = System.currentTimeMillis()

        if (info == null) {
            Logger.e(LOG_TAG_APP_UPDATE, "StoreAppUpdater: update info unavailable (timeout/failure)")
            unregisterListener(listener)
            if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                Utilities.openPlayStorePage(activity)
            }
            return
        }

        val availability = info.updateAvailability()
        Logger.i(
            LOG_TAG_APP_UPDATE,
            "StoreAppUpdater: update info, availability: $availability, " +
                "status: ${info.installStatus()}, version: ${info.availableVersionCode()}"
        )

        // a flexible update is already downloaded; do not start the flow again
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            listener.onStateUpdate(AppUpdater.InstallState(AppUpdater.InstallStatus.DOWNLOADED))
            return
        }

        when (availability) {
            UpdateAvailability.UPDATE_AVAILABLE -> {
                val type = resolveUpdateType(info)
                if (type == null) {
                    Logger.w(LOG_TAG_APP_UPDATE, "StoreAppUpdater: update available but no allowed update types")
                    unregisterListener(listener)
                    listener.onUpdateQuotaExceeded(AppUpdater.InstallSource.STORE)
                } else {
                    launchUpdateFlow(activity, info, type, listener, isInteractive, startUpdateFlow)
                }
            }
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    launchUpdateFlow(
                        activity,
                        info,
                        AppUpdateType.IMMEDIATE,
                        listener,
                        isInteractive,
                        startUpdateFlow
                    )
                } else {
                    Logger.w(LOG_TAG_APP_UPDATE, "StoreAppUpdater: update in progress but immediate not allowed")
                    unregisterListener(listener)
                    if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                        Utilities.openPlayStorePage(activity)
                    }
                }
            }
            UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                unregisterListener(listener)
                listener.onUpToDate(AppUpdater.InstallSource.STORE, isInteractive)
            }
            else -> {
                Logger.i(LOG_TAG_APP_UPDATE, "StoreAppUpdater: unhandled update availability: $availability")
                unregisterListener(listener)
            }
        }
    }

    // flexible-first; immediate for high-priority releases or when flexible is disallowed
    private fun resolveUpdateType(info: AppUpdateInfo): Int? {
        val canDoFlexible = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
        val canDoImmediate = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        val priority = info.updatePriority()
        return when {
            canDoImmediate && (priority >= IMMEDIATE_PRIORITY_THRESHOLD || !canDoFlexible) ->
                AppUpdateType.IMMEDIATE
            canDoFlexible -> AppUpdateType.FLEXIBLE
            else -> null
        }
    }

    private fun launchUpdateFlow(
        activity: Activity,
        info: AppUpdateInfo,
        @AppUpdateType type: Int,
        listener: AppUpdater.InstallStateListener,
        isInteractive: AppUpdater.UserPresent,
        startUpdateFlow: (IntentSender) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.w(LOG_TAG_APP_UPDATE, "StoreAppUpdater: activity finishing, skipping update flow")
            unregisterListener(listener)
            return
        }

        try {
            val options = AppUpdateOptions.newBuilder(type).build()
            appUpdateManager.startUpdateFlowForResult(
                info,
                { sender, _, _, _, _, _, _ -> startUpdateFlow(sender) },
                options,
                0
            )
        } catch (e: IntentSender.SendIntentException) {
            Logger.e(LOG_TAG_APP_UPDATE, "StoreAppUpdater: send intent err: ${e.message}", e)
            unregisterListener(listener)
            if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                Utilities.openPlayStorePage(activity)
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP_UPDATE, "StoreAppUpdater: update flow err: ${e.message}", e)
            unregisterListener(listener)
            if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                Utilities.openPlayStorePage(activity)
            }
        }
    }

    override fun completeUpdate() {
        try {
            appUpdateManager.completeUpdate().addOnFailureListener { e ->
                Logger.e(LOG_TAG_APP_UPDATE, "StoreAppUpdater: complete update failed: ${e.message}", e)
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP_UPDATE, "StoreAppUpdater: complete update err: ${e.message}", e)
        }
    }

    override fun unregisterListener(listener: AppUpdater.InstallStateListener) {
        listenerMapping.remove(listener)?.also {
            appUpdateManager.unregisterListener(it)
        }
    }

    companion object {
        // give up waiting for play's update-info task after this long
        private const val UPDATE_CHECK_TIMEOUT_MS = 10_000L

        // play-console update priority at which the immediate flow is preferred
        private const val IMMEDIATE_PRIORITY_THRESHOLD = 4
    }
}
