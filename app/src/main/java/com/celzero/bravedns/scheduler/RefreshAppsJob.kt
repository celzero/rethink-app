package com.celzero.bravedns.scheduler

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_DB
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RefreshAppsJob(val context: Context, workerParameters: WorkerParameters) :
        Worker(context, workerParameters), KoinComponent {

    private val refreshDatabase by inject<RefreshDatabase>()

    override fun doWork(): Result {
        if(DEBUG) Log.d(LOG_TAG_SCHEDULER, "Refresh database job executed")
        refreshDatabase.refreshAppInfoDatabase()
        return Result.success()
    }
}