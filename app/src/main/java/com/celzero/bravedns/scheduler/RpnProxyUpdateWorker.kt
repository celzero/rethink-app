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
package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_IAB
import Logger.LOG_TAG_PROXY
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.celzero.bravedns.iab.BillingBackendClient
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.iab.RegisterDeviceResult
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.RpnProxyUpdateWorker.Companion.INTERVAL_MINUTES
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * [RpnProxyUpdateWorker] runs periodically every [INTERVAL_MINUTES] minutes and refreshes
 * the RPN/WIN proxy state.
 *
 */
class RpnProxyUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val billingBackendClient by inject<BillingBackendClient>()

    companion object {
        private const val TAG = "RpnProxyUpdateWorker"

        /** Unique name used for [WorkManager.enqueueUniquePeriodicWork]. */
        const val WORK_NAME = "RpnProxyUpdateWorker"

        /** How often the worker fires (and also the initial delay before first run). */
        const val INTERVAL_MINUTES = 45L

        /**
         * Maximum WorkManager retry attempts before the job is marked FAILED.
         * After this many consecutive [Result.retry] responses within one period
         * the worker returns [Result.failure] so WorkManager stops retrying until
         * the next scheduled period.
         */
        const val MAX_RETRIES = 3

        /** Initial back-off delay for retries. */
        private const val BACKOFF_DELAY_MINUTES = 1L

        const val REGISTRATION_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000 //  1 day once

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RpnProxyUpdateWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                // Defer the very first execution by the full interval.  Without this
                // WorkManager fires the PeriodicWorkRequest almost immediately after
                // enqueue, running a redundant update right after WIN registration.
                .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )

            Logger.i(LOG_TAG_PROXY, "$TAG; scheduled: first run in ${INTERVAL_MINUTES}m, then every ${INTERVAL_MINUTES}m (CANCEL_AND_REENQUEUE — fresh timer from registration)")
        }

        /** Cancel the periodic worker (e.g. when RPN is disabled). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Logger.i(LOG_TAG_PROXY, "$TAG; periodic update cancelled")
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            // runAttemptCount is 0-based: 0 = first attempt, 1 = first retry, etc.
            val attempt = runAttemptCount + 1

            Logger.i(LOG_TAG_PROXY, "$TAG; doWork: starting attempt $attempt/$MAX_RETRIES")

            // Cap retries at MAX_RETRIES.  runAttemptCount is 0-based so when it equals
            // MAX_RETRIES we have already exhausted all attempts (0…MAX_RETRIES-1).
            // Return Result.failure() NOT success(): so WorkManager records the job
            // as failed and the next periodic execution starts with a clean slate.
            if (runAttemptCount >= MAX_RETRIES) {
                Logger.e(
                    LOG_TAG_PROXY,
                    "$TAG; doWork: max retries ($MAX_RETRIES) exhausted - " +
                    "marking FAILED; next periodic run in ~${INTERVAL_MINUTES}m"
                )
                return@withContext Result.failure()
            }

            if (!RpnProxyManager.isRpnActive()) {
                Logger.i(LOG_TAG_PROXY, "$TAG; doWork: RPN not active, skipping update (attempt $attempt)")
                return@withContext Result.success()
            }

            try {
                // TODO: Duplicate implementation exists in RpnProxyUpdateWorker and
                // SubscriptionCheckWorker. Extract this into a shared utility or manager.
                checkAndRegisterDeviceIfNeeded()

                val updated = RpnProxyManager.updateWinProxy()
                when {
                    updated == null -> {
                        // null → tunnel failure (VpnController.updateWin returned null).
                        // If a 409 conflict is currently active the failure is waiting for
                        // user action, not a transient network error – stop retrying so we
                        // don't spam further notifications.
                        if (InAppBillingHandler.serverApiErrorLiveData.value is ServerApiError.Conflict409) {
                            Logger.e(
                                LOG_TAG_PROXY,
                                "$TAG; doWork: WIN proxy update failed due to active 409 conflict; " +
                                "marking FAILED (not retrying) on attempt $attempt"
                            )
                            return@withContext Result.failure()
                        }
                        // Retry up to MAX_RETRIES; WorkManager applies exponential back-off.
                        Logger.w(
                            LOG_TAG_PROXY,
                            "$TAG; doWork: WIN proxy update failed (null) on attempt $attempt/$MAX_RETRIES, scheduling retry"
                        )
                        Result.retry()
                    }
                    updated.isEmpty() -> {
                        // empty list → bytes persisted but tunnel has no locations yet.
                        Logger.i(
                            LOG_TAG_PROXY,
                            "$TAG; doWork: WIN proxy update succeeded (no locations yet) on attempt $attempt; SUCCESS"
                        )
                        Result.success()
                    }
                    else -> {
                        Logger.i(
                            LOG_TAG_PROXY,
                            "$TAG; doWork: WIN proxy update succeeded, ${updated.size} location(s) refreshed on attempt $attempt; SUCCESS"
                        )
                        Result.success()
                    }
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; doWork: exception on attempt $attempt/$MAX_RETRIES: ${e.message}", e)
                if (runAttemptCount + 1 >= MAX_RETRIES) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; doWork: max retries reached after exception; marking FAILED")
                    Result.failure()
                } else {
                    Logger.w(LOG_TAG_PROXY, "$TAG; doWork: scheduling retry after exception (attempt $attempt/$MAX_RETRIES)")
                    Result.retry()
                }
            }
        }
    }

    /**
     * Verifies the device is registered with the server (accountId + deviceId).
     */
    private suspend fun checkAndRegisterDeviceIfNeeded() {
        val mname = "checkAndRegisterDeviceIfNeeded"
        try {
            val storedAccountId = billingBackendClient.getAccountId()
            val storedDeviceId  = billingBackendClient.getDeviceId()

            val storedTs = persistentState.deviceRegistrationTimestamp
            val now = System.currentTimeMillis()
            val isStale = (now - storedTs) > REGISTRATION_REFRESH_INTERVAL_MS

            // When a user already has an active purchase (e.g. restored from Play Store
            // backup / reinstall) but no deviceId was ever written to the DB, we must
            // call /g/reg to associate this device. Use the purchase's accountId so
            // the server links the existing account, not a brand-new one.
            val purchases = InAppBillingHandler.getActivePurchasesSnapshot()
            val firstPurchase = purchases.firstOrNull()

            if (firstPurchase != null && storedDeviceId.isBlank()) {
                val purchaseAccountId = firstPurchase.accountId.takeIf { it.isNotBlank() }
                    ?: storedAccountId
                if (purchaseAccountId != storedAccountId) {
                    // reconciles the device id based on the current cid
                    billingBackendClient.getDeviceId(purchaseAccountId)
                }
                val freshDeviceId = billingBackendClient.getDeviceId()
                val freshAccountId = billingBackendClient.getAccountId()
                Logger.i(LOG_IAB, "$TAG; $mname: registration needed " +
                        "(storedAccount=${storedAccountId.take(8)}, freshAccount=${freshAccountId.take(8)}, " +
                        "storedDevice-len=${storedDeviceId.length}, freshDevice-len=${freshDeviceId.length}, " +
                        "storedTs=$storedTs, isStale=$isStale), calling /g/device")
                callDeviceRegistrationWithMeta(freshAccountId, freshDeviceId, firstPurchase)
                return
            }

            if (!isStale) {
                Logger.d(LOG_IAB, "$TAG; $mname: device already registered within window, skipping")
                return
            }

            if (firstPurchase == null) {
                Logger.w(LOG_IAB, "$TAG; $mname: no purchases in snapshot; bare registration (no meta)")
                callBareDeviceRegistration(storedAccountId, storedDeviceId)
            } else {
                Logger.i(LOG_IAB, "$TAG; $mname: registration with meta, productId=${firstPurchase.productId}")
                callDeviceRegistrationWithMeta(storedAccountId, storedDeviceId, firstPurchase)
            }

        } catch (e: Exception) {
            // failure here must not block purchase validation
            Logger.e(LOG_IAB, "$TAG; $mname: error (non-fatal): ${e.message}", e)
        }
    }

    /**
     * Calls `/g/reg` without a meta body (bare registration).
     * Delegates to [BillingBackendClient.registerDeviceWithDeviceMeta].
     *
     * On HTTP 401 the [ServerApiError.Unauthorized401] is posted to
     * [InAppBillingHandler.serverApiErrorLiveData] so it is surfaced the next
     * time the user opens the Manage Subscription screen.
     */
    private suspend fun callBareDeviceRegistration(accountId: String, deviceId: String) {
        val mname = "callBareDeviceRegistration"
        when (val result = billingBackendClient.registerDeviceWithDeviceMeta(accountId, deviceId)) {
            is RegisterDeviceResult.Success -> {
                Logger.i(LOG_IAB, "$TAG; $mname: bare registration succeeded")
            }
            is RegisterDeviceResult.Unauthorized -> {
                Logger.e(LOG_IAB, "$TAG; $mname: 401 unauthorized; posting DeviceAuthError to UI")
                InAppBillingHandler.handleUnauthorized401(
                    operation = ServerApiError.Operation.DEVICE,
                    accountId = accountId,
                    deviceId  = deviceId
                )
            }
            is RegisterDeviceResult.Conflict -> {
                Logger.w(LOG_IAB, "$TAG; $mname: 409 conflict: device already registered (non-fatal)")
            }
            is RegisterDeviceResult.Failure -> {
                Logger.e(LOG_IAB, "$TAG; $mname: bare registration failed: code=${result.httpCode} (non-fatal)")
            }
        }
    }

    /**
     * Calls `/g/reg` with a meta body built from [purchaseDetail].
     * Delegates to [BillingBackendClient.registerDevice].
     */
    private suspend fun callDeviceRegistrationWithMeta(
        accountId: String,
        deviceId: String,
        purchaseDetail: PurchaseDetail
    ) {
        val mname = "callDeviceRegistrationWithMeta"
        Logger.i(LOG_IAB, "$TAG; $mname: initiate registration, productId=${purchaseDetail.productId}")
        val meta = billingBackendClient.buildDeviceMeta(purchaseDetail.productId)
        InAppBillingHandler.registerDevice(accountId, deviceId, meta)
    }
}
