package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_FIREWALL
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.AppInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * One-shot worker that clears expired temporary-allow entries and schedules the next run.
 *
 * Accurate across process death. No coroutine delay loops.
 */
class TempAllowExpiryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val db by inject<AppInfoRepository>()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val cleared = db.clearAllExpiredTempAllowsBlocking(now)
            if (cleared > 0) {
                Logger.i(LOG_TAG_FIREWALL, "TempAllowExpiryWorker cleared $cleared expired temp-allow entries")
            }

            // schedule the next nearest expiry
            scheduleNext(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "TempAllowExpiryWorker failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object : KoinComponent {
        private const val UNIQUE_WORK_NAME = "fw_temp_allow_expiry"

        private val db by inject<AppInfoRepository>()

        fun scheduleNext(context: Context) {
            val now = System.currentTimeMillis()
            val next = runCatching { db.getNearestTempAllowExpiryBlocking(now) }.getOrNull()

            // If nothing is scheduled anymore, cancel the existing work.
            if (next == null) {
                cancel(context)
                return
            }

            if (next <= now) {
                // if already expired, run almost immediately
                enqueueAt(context, now + 1_000L)
                return
            }
            enqueueAt(context, next)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        fun enqueueAt(context: Context, runAtMs: Long) {
            val delayMs = (runAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
            val req = OneTimeWorkRequestBuilder<TempAllowExpiryWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(UNIQUE_WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, req)

            Logger.d(LOG_TAG_FIREWALL, "TempAllowExpiryWorker scheduled in ${delayMs}ms")
        }
    }
}
