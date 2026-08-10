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
package com.celzero.bravedns.iab

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.android.billingclient.api.BillingClient
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background re-check for purchases left in the `PurchasePending` state.
 *
 * ### Why this exists
 * When Google Play returns `PENDING`, the in-screen polling loop only runs while
 * `RethinkPlusFragment` is alive. If the user navigates away, the purchase is never
 * re-checked until the next app launch or the 24h [SubscriptionCheckWorker] — far too
 * long for a pending purchase.
 *
 * This worker fills that gap: it is scheduled the moment a `PurchasePending` state is
 * detected and re-queries Play on a ~20 minute cadence. As soon as the state machine
 * transitions out of `PurchasePending` (to `Active`, `Error`, etc.) the periodic work
 * is cancelled by [cancel] / the worker's own self-cancellation guard.
 *
 * ### Also used for the expedited one-shot
 * [scheduleExpedited] enqueues a single expedited run so a just-detected pending
 * purchase gets an immediate background retry without waiting for the periodic window.
 */
class PendingPurchaseWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PendingPurchaseWorker"

        /** Unique name used for the periodic re-check. */
        const val WORK_NAME = "PendingPurchaseWorker"

        /** Unique name used for the one-shot expedited re-check. */
        const val WORK_NAME_EXPEDITED = "PendingPurchaseWorkerExpedited"

        /**
         * WorkManager enforces a 15 minute minimum for periodic work. The pending
         * purchase window can legitimately last hours, so 20 minutes is a good balance
         * between responsiveness and battery/network cost.
         */
        const val INTERVAL_MINUTES = 20L
        private const val BACKOFF_DELAY_MINUTES = 2L

        /**
         * Schedule the periodic background re-check. Idempotent: re-scheduling with
         * [ExistingPeriodicWorkPolicy.UPDATE] keeps a single instance and preserves the
         * existing enrolment if one already exists.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PendingPurchaseWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
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
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Logger.i(LOG_IAB, "$TAG; scheduled periodic re-check every ${INTERVAL_MINUTES}m")
        }

        /**
         * Enqueue a single expedited re-check so a pending purchase
         * gets an immediate background retry. Uses [ExistingWorkPolicy.KEEP] so multiple
         * detections do not stack up duplicate expedited jobs.
         */
        fun scheduleExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingPurchaseWorker>()
                .addTag(WORK_NAME_EXPEDITED)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_EXPEDITED,
                ExistingWorkPolicy.KEEP,
                request
            )

            Logger.i(LOG_IAB, "$TAG; scheduled expedited one-shot re-check")
        }

        /** Cancel both the periodic and expedited re-checks (state left PurchasePending). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_EXPEDITED)
            Logger.i(LOG_IAB, "$TAG; cancelled periodic + expedited re-checks")
        }
    }

    private val listener = object : BillingListener {
        override fun onConnectionResult(isSuccess: Boolean, message: String) {
            Logger.d(LOG_IAB, "$TAG; onConnectionResult: $isSuccess, $message")
        }

        override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) {
            Logger.v(LOG_IAB, "$TAG; purchasesResult: $isSuccess, size=${purchaseDetailList.size}")
        }

        override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
            // Not relevant for pending re-checks.
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val state = InAppBillingHandler.getSubscriptionState()
                Logger.i(LOG_IAB, "$TAG; doWork: current state=${state.name}")

                // Self-cancellation: if we are no longer pending there is nothing to
                // re-check. Cancel the periodic work so it stops firing.
                if (state !is SubscriptionStateMachineV2.SubscriptionState.PurchasePending &&
                    state !is SubscriptionStateMachineV2.SubscriptionState.PurchaseInitiated &&
                    state !is SubscriptionStateMachineV2.SubscriptionState.ServerAckPending) {
                    Logger.i(LOG_IAB, "$TAG; state=$state no longer pending, cancelling periodic work")
                    cancel(applicationContext)
                    return@withContext Result.success()
                }

                refreshPurchases()
                Result.success()
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG; doWork failed: ${e.message}", e)
                Result.retry()
            }
        }
    }

    /**
     * Ensure the billing client is connected (re-init if needed) then re-query both
     * product types. The resulting [InAppBillingHandler.handlePurchase] call resolves
     * the pending purchase into Active / Error as appropriate.
     */
    private suspend fun refreshPurchases() {
        val mname = "refreshPurchases"
        if (!InAppBillingHandler.isBillingClientSetup()) {
            Logger.i(LOG_IAB, "$TAG; $mname: billing client not ready, initiating")
            InAppBillingHandler.initiate(applicationContext, listener)
            // Give the connection callback a moment to settle before querying.
            delay(2000)
        }

        if (!InAppBillingHandler.isListenerRegistered(listener)) {
            InAppBillingHandler.registerListener(listener)
        }

        InAppBillingHandler.fetchPurchases(
            listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)
        )
        Logger.i(LOG_IAB, "$TAG; $mname: purchases re-queried")
    }
}
