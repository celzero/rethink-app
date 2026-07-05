package com.celzero.bravedns.ui.compose.about

import android.content.Context
import android.content.pm.PackageInfo
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_LENGTH
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getPackageMetadata
import com.celzero.bravedns.util.Utilities.getRandomString
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.firestack.intra.Intra
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class AboutUiState(
    val versionName: String = "",
    val installSource: String = "",
    val buildNumber: String = "",
    val lastUpdated: String = "",
    val slicedVersion: String = "",
    val daysSinceInstall: String = "",
    val sponsoredAmount: String = "",
    val firebaseToken: String = "",
    val isFirebaseEnabled: Boolean = false,
    val isFdroid: Boolean = false,
    val isPlayStore: Boolean = false,
    val isDebug: Boolean = DEBUG,
    val isBugReportRunning: Boolean = false
)

class AboutViewModel(
    private val persistentState: PersistentState,
    private val workScheduler: WorkScheduler,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private var lastAppExitInfoDialogInvokeTime = INIT_TIME_MS

    init {
        updateUiState()
        observeBugReportWork()
    }

    fun updateUiState() {
        val version = getVersionName()
        val slicedVersion = if (version.length > 6) version.slice(0..6) else version
        val installSource = getDownloadSource()
        val build = Intra.build(false)
        val updatedTs = getLastUpdatedTs()
        val sponsorInfo = calculateSponsorInfo()

        _uiState.update {
            it.copy(
                versionName = version,
                slicedVersion = slicedVersion,
                installSource = installSource,
                buildNumber = build,
                lastUpdated = updatedTs,
                daysSinceInstall = sponsorInfo.first,
                sponsoredAmount = sponsorInfo.second,
                firebaseToken = persistentState.firebaseUserToken,
                isFirebaseEnabled = persistentState.firebaseErrorReportingEnabled,
                isFdroid = isFdroidFlavour(),
                isPlayStore = isPlayStoreFlavour()
            )
        }
    }

    private fun getVersionName(): String {
        val pInfo: PackageInfo? = getPackageMetadata(context.packageManager, context.packageName)
        return pInfo?.versionName ?: ""
    }

    private fun getLastUpdatedTs(): String {
        val pInfo: PackageInfo? = getPackageMetadata(context.packageManager, context.packageName)
        val updatedTs = pInfo?.lastUpdateTime ?: return ""
        return if (updatedTs > 0) {
            Utilities.convertLongToTime(updatedTs, com.celzero.bravedns.util.Constants.TIME_FORMAT_4)
        } else {
            ""
        }
    }

    private fun getDownloadSource(): String {
        return if (isFdroidFlavour()) "F-Droid"
        else if (isPlayStoreFlavour()) "Google Play"
        else "Website"
    }

    private fun calculateSponsorInfo(): Pair<String, String> {
        val installTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
        val timeDiff = System.currentTimeMillis() - installTime
        val millisecondsPerDay = 1000L * 60L * 60L * 24L
        val days = (timeDiff / millisecondsPerDay).toDouble()
        val month = days / 30.0
        val amount = month * (0.60 + 0.20)
        return Pair(days.toInt().toString(), "%.2f".format(amount))
    }

    fun generateNewToken() {
        if (isFdroidFlavour()) return
        val newToken = getRandomString(TOKEN_LENGTH)
        persistentState.firebaseUserToken = newToken
        persistentState.firebaseUserTokenTimestamp = System.currentTimeMillis()
        _uiState.update { it.copy(firebaseToken = newToken) }
    }

    private fun observeBugReportWork() {
        val workManager = WorkManager.getInstance(context)
        workManager.getWorkInfosByTagLiveData(WorkScheduler.APP_EXIT_INFO_ONE_TIME_JOB_TAG)
            .asFlow()
            .onEach { workInfoList ->
                val workInfo = workInfoList.getOrNull(0) ?: return@onEach
                when (workInfo.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.update { it.copy(isBugReportRunning = false) }
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(isBugReportRunning = false) }
                    }
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        _uiState.update { it.copy(isBugReportRunning = true) }
                    }
                    else -> {}
                }
            }.launchIn(viewModelScope)
    }

    fun triggerBugReport() {
        if (WorkScheduler.isWorkRunning(context, WorkScheduler.APP_EXIT_INFO_JOB_TAG)) return
        workScheduler.scheduleOneTimeWorkForAppExitInfo()
        _uiState.update { it.copy(isBugReportRunning = true) }
    }

    fun setBugReportRunning(isRunning: Boolean) {
        _uiState.update { it.copy(isBugReportRunning = isRunning) }
    }
}
