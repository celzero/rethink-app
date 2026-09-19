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
import android.util.Log
import com.celzero.bravedns.service.AppUpdater
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class StoreAppUpdater(context: Context) : AppUpdater {
    private val LOG_TAG = "StoreAppUpdater"
    private val listenerMapping =
        mutableMapOf<AppUpdater.InstallStateListener, InstallStateUpdatedListener>()
    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(context) }

    companion object {
        private const val APP_UPDATE_REQUEST_CODE = 20023
    }

    override fun checkForAppUpdate(
        isInteractive: AppUpdater.UserPresent,
        activity: Activity,
        listener: AppUpdater.InstallStateListener
    ) {
        Log.i(LOG_TAG, "Beginning update check.")
        val playListener = InstallStateUpdatedListener { state ->
            val status = state.installStatus()
            Log.i(LOG_TAG, "InstallStateUpdatedListener: status: $status")
            val mappedStatus =
                when (status) {
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

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                val availability = appUpdateInfo.updateAvailability()
                val installStatus = appUpdateInfo.installStatus()
                val versionCode = appUpdateInfo.availableVersionCode()

                Log.i(
                    LOG_TAG,
                    "Update info success. Availability: $availability, Status: $installStatus, Version: $versionCode"
                )

                if (installStatus == InstallStatus.DOWNLOADED) {
                    Log.i(LOG_TAG, "Update already downloaded, notifying listener")
                    listener.onStateUpdate(
                        AppUpdater.InstallState(AppUpdater.InstallStatus.DOWNLOADED)
                    )
                }

                if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
                    val priority = appUpdateInfo.updatePriority()
                    Log.i(LOG_TAG, "Update available with priority: $priority")

                    // Decide update type based on priority or availability
                    // Priority 4-5: Immediate, others: Flexible if allowed
                    val canDoImmediate = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    val canDoFlexible = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

                    if (canDoFlexible) {
                        Log.i(LOG_TAG, "Starting flexible update flow")
                        startUpdateFlow(
                            activity,
                            appUpdateInfo,
                            AppUpdateType.FLEXIBLE,
                            listener
                        )
                    } else if (canDoImmediate) {
                        Log.i(LOG_TAG, "Starting immediate update flow")
                        startUpdateFlow(
                            activity,
                            appUpdateInfo,
                            AppUpdateType.IMMEDIATE,
                            listener
                        )
                    } else {
                        Log.w(LOG_TAG, "Update available but no allowed update types")
                        listener.onUpdateQuotaExceeded(AppUpdater.InstallSource.STORE)
                    }
                } else if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    Log.i(LOG_TAG, "Update already in progress, attempting to resume")
                    // Resume immediate update if it was in progress
                    if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        startUpdateFlow(
                            activity,
                            appUpdateInfo,
                            AppUpdateType.IMMEDIATE,
                            listener
                        )
                    }
                } else if (availability == UpdateAvailability.UPDATE_NOT_AVAILABLE) {
                    Log.i(LOG_TAG, "No update available for Play Store flavor")
                    unregisterListener(listener)
                    listener.onUpToDate(AppUpdater.InstallSource.STORE, isInteractive)
                } else {
                    Log.i(LOG_TAG, "Update check result: $availability (unhandled)")
                    unregisterListener(listener)
                }
            }
            .addOnFailureListener { e ->
                Log.e(LOG_TAG, "Update info request failed: ${e.message}", e)
                unregisterListener(listener)
                listener.onUpdateCheckFailed(AppUpdater.InstallSource.STORE, isInteractive)
            }
    }

    private fun startUpdateFlow(
        activity: Activity,
        appUpdateInfo: com.google.android.play.core.appupdate.AppUpdateInfo,
        @AppUpdateType type: Int,
        listener: AppUpdater.InstallStateListener
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(LOG_TAG, "Activity is finishing or destroyed, skipping update flow")
            unregisterListener(listener)
            return
        }

        try {
            val options = AppUpdateOptions.newBuilder(type).build()
            appUpdateManager.startUpdateFlowForResult(
                appUpdateInfo,
                activity,
                options,
                APP_UPDATE_REQUEST_CODE
            )
        } catch (e: IntentSender.SendIntentException) {
            Log.e(LOG_TAG, "SendIntentException while starting update flow: ${e.message}", e)
            unregisterListener(listener)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Exception while starting update flow: ${e.message}", e)
            unregisterListener(listener)
        }
    }


    override fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    override fun unregisterListener(listener: AppUpdater.InstallStateListener) {
        listenerMapping.remove(listener)?.also {
            appUpdateManager.unregisterListener(it)
        }
    }
}
