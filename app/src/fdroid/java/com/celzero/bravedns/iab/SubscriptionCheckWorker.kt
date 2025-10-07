package com.rethinkdns.retrixed.iab

import Logger
import Logger.LOG_IAB
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rethinkdns.retrixed.service.PersistentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SubscriptionCheckWorker(
    val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val persistentState by inject<PersistentState>()
    private var attempts = 0

    companion object {
        const val WORK_NAME = "SubscriptionCheckWorker"
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                initiate()
                // by default, return success
                Result.success()
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$WORK_NAME; failed: ${e.message}")
                Result.retry()
            }
        }
    }

    private fun initiate() {
        // implement check for stripe subscription
    }

    private fun reinitiate(attempt: Int = 0) {
        if (attempt > 3) {
            Logger.e(LOG_IAB, "$WORK_NAME; reinitiate failed after 3 attempts")
            return
        }
        // reinitiate the billing client
        initiate()
    }

}
