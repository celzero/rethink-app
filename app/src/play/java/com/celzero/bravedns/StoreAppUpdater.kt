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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants

class StoreAppUpdater(context: Context, private val persistentState: PersistentState) : AppUpdater {
    private val LOG_TAG = "${Constants.LOG_TAG}/StoreAppUpdater"
    private val listenerMapping = mutableMapOf<AppUpdater.InstallStateListener, InstallStateUpdatedListener>()
    private val appUpdateManager by lazy {
        AppUpdateManagerFactory.create(context)
    }

    override fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    override fun unregisterListener(listener: AppUpdater.InstallStateListener) {
        listenerMapping.remove(listener)?.also {
            appUpdateManager.unregisterListener(it)
        }
    }

    override fun checkForAppUpdate(isUserInitiated: Boolean, activity: Activity, listener: AppUpdater.InstallStateListener) {
        Log.d(LOG_TAG, "Beginning update check.")
        val playListener = InstallStateUpdatedListener { state ->
            val mappedStatus = when (state.installStatus()) {
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

        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                Log.i(LOG_TAG, "Update available, starting flexible update")
                try {
                    appUpdateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.FLEXIBLE, activity, 1)
                } catch (e: IntentSender.SendIntentException) {
                    unregisterListener(listener)
                    Log.e(LOG_TAG, "SendIntentException: ${e.message} ", e)
                }
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                Log.i(LOG_TAG, "Update available, starting immediate update")
                try {
                    appUpdateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.IMMEDIATE, activity, 1)
                } catch (e: IntentSender.SendIntentException) {
                    unregisterListener(listener)
                    Log.e(LOG_TAG, "SendIntentException: ${e.message} ", e)
                }
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                if (isUserInitiated) {
                    listener.onUpdateQuotaExceeded(AppUpdater.InstallSource.STORE)
                }
            } else if(appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE){
                unregisterListener(listener)
                Log.e(LOG_TAG, "no update")
                if (isUserInitiated) {
                    listener.onUpToDate(AppUpdater.InstallSource.STORE)
                }
            }
        }
        appUpdateManager.appUpdateInfo.addOnFailureListener { e ->
            Log.e(LOG_TAG, "Update check failed", e)
            unregisterListener(listener)
            if (isUserInitiated) {
                listener.onUpdateCheckFailed(AppUpdater.InstallSource.STORE)
            }
        }
    }
}
