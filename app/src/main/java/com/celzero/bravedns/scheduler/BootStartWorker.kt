/*
 * Copyright 2026 RethinkDNS and its authors
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

package com.celzero.bravedns.scheduler

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.content.Context
import android.net.VpnService
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Worker that attempts to auto-start the VPN after reboot or profile unlock.
 *
 * Running this inside a WorkManager job (instead of directly from the broadcast receiver)
 * makes the boot-time start more resilient to OEM boot killers and Android 12+ background
 * service-start restrictions.
 */
class BootStartWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {

    private val persistentState by inject<PersistentState>()

    override suspend fun doWork(): Result {
        val eventType = inputData.getString(KEY_EVENT_TYPE) ?: BOOT_COMPLETE_EVENT

        // MY_PACKAGE_REPLACED is intentionally exempt from this check so an app update
        // can restore a previously-running VPN.
        if (eventType != PACKAGE_REPLACED_EVENT && !persistentState.prefAutoStartBootUp) {
            Logger.i(LOG_TAG_VPN, "auto-start not enabled, skipping boot start, event $eventType")
            return Result.success()
        }

        if (!VpnController.state().activationRequested) {
            Logger.i(LOG_TAG_VPN, "vpn not active before shutdown, skipping boot start, event $eventType")
            return Result.success()
        }

        // If always-on is enabled the OS is expected to bring the VPN up on its own.
        if (VpnController.isAlwaysOn(applicationContext)) {
            Logger.i(LOG_TAG_VPN, "always-on vpn enabled, OS should take care, $eventType")
            return Result.success()
        }

        // The app UI (e.g. HomeScreenFragment) may also auto-start the VPN around the same
        // time (app update / process restart). Skip if the VPN is already active.
        if (VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_VPN, "vpn already active, skipping boot start, event $eventType")
            return Result.success()
        }

        val prepareVpnIntent =
            try {
                Logger.v(LOG_TAG_VPN, "start preparing vpn service")
                VpnService.prepare(applicationContext)
            } catch (_: NullPointerException) {
                Logger.w(LOG_TAG_VPN, "device does not support system-wide VPN mode")
                return Result.success()
            }

        if (prepareVpnIntent != null) {
            Logger.i(LOG_TAG_VPN, "vpn not prepared, skipping boot start, event $eventType")
            return Result.success()
        }

        Logger.i(LOG_TAG_VPN, "attempting to auto-start VPN, event $eventType")
        VpnController.start(applicationContext, autoAttempt = true)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "boot_start_worker"
        private const val KEY_EVENT_TYPE = "event_type"

        const val BOOT_COMPLETE_EVENT = "action.BOOT_COMPLETED"
        const val REBOOT_EVENT = "action.REBOOT"
        const val PACKAGE_REPLACED_EVENT = "action.MY_PACKAGE_REPLACED"
        const val USER_UNLOCKED_EVENT = "maybe.privatespace.action.USER_UNLOCKED"

        // Short delay so that any app-UI initiated auto-start (e.g. HomeScreenFragment
        // restoring the VPN after an update / process restart) can finish before the worker
        // runs, avoiding a duplicate start that can tear down the VPN.
        private const val START_DELAY_MS = 1500L

        fun enqueue(context: Context, eventType: String) {
            val inputData =
                Data.Builder()
                    .putString(KEY_EVENT_TYPE, eventType)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<BootStartWorker>()
                    .setInputData(inputData)
                    .setInitialDelay(START_DELAY_MS, TimeUnit.MILLISECONDS)
                    .addTag(UNIQUE_WORK_NAME)
                    .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
