/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import Logger
import Logger.LOG_TAG_APP_UPDATE
import Logger.LOG_TAG_BACKUP_RESTORE
import Logger.LOG_TAG_DOWNLOAD
import Logger.LOG_TAG_UI
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.NonStoreAppUpdater
import com.celzero.bravedns.R
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_FILE_EXTN
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_RESTART_APP
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_SCHEME
import com.celzero.bravedns.backup.RestoreAgent
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.ActivityHomeScreenBinding
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.activity.WelcomeActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.PKG_NAME_PLAY_STORE
import com.celzero.bravedns.util.RemoteFileTagUtil
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getPackageMetadata
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.isWebsiteFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

class HomeScreenActivity : AppCompatActivity(R.layout.activity_home_screen) {
    private val b by viewBinding(ActivityHomeScreenBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val appUpdateManager by inject<AppUpdater>()
    private val rdb by inject<RefreshDatabase>()

    // support for biometric authentication
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // private var biometricPromptRetryCount = 1
    private var onResumeCalledAlready = false

    companion object {
        private const val ON_RESUME_CALLED_PREFERENCE_KEY = "onResumeCalled"
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        // stackoverflow.com/questions/44221195/multiple-onstop-onresume-calls-in-android-activity
        // Restore value of members from saved state
        onResumeCalledAlready =
            savedInstanceState?.getBoolean(ON_RESUME_CALLED_PREFERENCE_KEY) ?: false

        // do not launch on board activity when app is running on TV
        if (persistentState.firstTimeLaunch && !isAppRunningOnTv()) {
            launchOnboardActivity()
            rdnsRemote()
            return
        }
        updateNewVersion()

        setupNavigationItemSelectedListener()

        // handle intent receiver for backup/restore
        handleIntent()

        initUpdateCheck()

        observeAppState()
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        outState.putBoolean(ON_RESUME_CALLED_PREFERENCE_KEY, onResumeCalledAlready)
        super.onSaveInstanceState(outState, outPersistentState)
    }

    override fun onResume() {
        super.onResume()
        if (persistentState.biometricAuth && !isAppRunningOnTv() && !onResumeCalledAlready) {
            biometricPrompt()
        }
    }

    // check if app running on TV
    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (ignored: Exception) {
            false
        }
    }

    private fun biometricPrompt() {
        // if the biometric authentication is already done in the last 15 minutes, then skip
        // fixme - #324 - move the 15 minutes to a configurable value
        if (
            SystemClock.elapsedRealtime() - persistentState.biometricAuthTime <
                TimeUnit.MINUTES.toMillis(15)
        ) {
            return
        }

        promptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.hs_biometeric_title))
                .setSubtitle(getString(R.string.hs_biometeric_desc))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .setConfirmationRequired(false)
                .build()

        // ref: https://developer.android.com/training/sign-in/biometric-auth
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt =
            BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Logger.i(
                            LOG_TAG_UI,
                            "Biometric authentication error (code: $errorCode): $errString"
                        )
                        // error code 5 (ERROR_CANCELED), this may happen when the device is locked
                        // or another pending operation prevents or disables it
                        // error code 10 (ERROR_USER_CANCELED), retry once after user cancelled
                        // the biometric prompt. ref issuetracker.google.com/issues/145231213
                        // commenting the code below, as the retry is buggy and not working as
                        // expected, have to revisit this code later
                        /* if (
                            biometricPromptRetryCount > 0 &&
                                (errorCode == BiometricPrompt.ERROR_CANCELED ||
                                    errorCode == BiometricPrompt.ERROR_USER_CANCELED)
                        ) {
                            biometricPromptRetryCount--
                            if (isInForeground()) biometricPrompt.authenticate(promptInfo)
                        } else {
                            showToastUiCentered(
                                applicationContext,
                                errString.toString(),
                                Toast.LENGTH_SHORT
                            )
                            finish()
                        } */
                        showToastUiCentered(
                            this@HomeScreenActivity,
                            errString.toString(),
                            Toast.LENGTH_SHORT
                        )
                        finish()
                    }

                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        super.onAuthenticationSucceeded(result)
                        // biometricPromptRetryCount = 1
                        persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
                        Logger.i(LOG_TAG_UI, "Biometric success @ ${System.currentTimeMillis()}")
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        showToastUiCentered(
                            this@HomeScreenActivity,
                            getString(R.string.hs_biometeric_failed),
                            Toast.LENGTH_SHORT
                        )
                        Logger.i(LOG_TAG_UI, "Biometric authentication failed")
                        // show the biometric prompt again only if the ui is in foreground
                        if (isInForeground()) biometricPrompt.authenticate(promptInfo)
                    }
                }
            )

        // BIOMETRIC_WEAK :Any biometric (e.g. fingerprint, iris, or face) on the device that meets
        // or exceeds the requirements for Class 2(formerly Weak), as defined by the Android CDD.
        if (
            BiometricManager.from(this)
                .canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) == BiometricManager.BIOMETRIC_SUCCESS
        ) {
            biometricPrompt.authenticate(promptInfo)
        } else {
            showToastUiCentered(
                applicationContext,
                getString(R.string.hs_biometeric_feature_not_supported),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun isInForeground(): Boolean {
        return !this.isFinishing && !this.isDestroyed
    }

    private fun handleIntent() {
        val intent = this.intent ?: return
        if (
            intent.scheme?.equals(INTENT_SCHEME) == true &&
                intent.data?.path?.contains(BACKUP_FILE_EXTN) == true
        ) {
            handleRestoreProcess(intent.data)
        } else if (intent.scheme?.equals(INTENT_SCHEME) == true) {
            showToastUiCentered(
                this,
                getString(R.string.brbs_restore_no_uri_toast),
                Toast.LENGTH_SHORT
            )
        } else if (intent.getBooleanExtra(INTENT_RESTART_APP, false)) {
            Logger.i(LOG_TAG_UI, "Restart from restore, so refreshing app database...")
            io { rdb.refresh(RefreshDatabase.ACTION_REFRESH_RESTORE) }
        }
    }

    private fun handleRestoreProcess(uri: Uri?) {
        if (uri == null) {
            showToastUiCentered(
                this,
                getString(R.string.brbs_restore_no_uri_toast),
                Toast.LENGTH_SHORT
            )
            return
        }

        showRestoreDialog(uri)
    }

    private fun showRestoreDialog(uri: Uri) {
        if (!isInForeground()) return

        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.brbs_restore_dialog_title)
        builder.setMessage(R.string.brbs_restore_dialog_message)
        builder.setPositiveButton(getString(R.string.brbs_restore_dialog_positive)) { _, _ ->
            startRestore(uri)
            observeRestoreWorker()
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun startRestore(fileUri: Uri) {
        Logger.i(LOG_TAG_BACKUP_RESTORE, "invoke worker to initiate the restore process")
        val data = Data.Builder()
        data.putString(BackupHelper.DATA_BUILDER_RESTORE_URI, fileUri.toString())

        val importWorker =
            OneTimeWorkRequestBuilder<RestoreAgent>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(RestoreAgent.TAG)
                .build()
        WorkManager.getInstance(this).beginWith(importWorker).enqueue()
    }

    private fun observeRestoreWorker() {
        val workManager = WorkManager.getInstance(this.applicationContext)

        // observer for custom download manager worker
        workManager.getWorkInfosByTagLiveData(RestoreAgent.TAG).observe(this) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "WorkManager state: ${workInfo.state} for ${RestoreAgent.TAG}"
            )
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                showToastUiCentered(
                    this,
                    getString(R.string.brbs_restore_complete_toast),
                    Toast.LENGTH_SHORT
                )
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                    WorkInfo.State.FAILED == workInfo.state
            ) {
                showToastUiCentered(
                    this,
                    getString(R.string.brbs_restore_no_uri_toast),
                    Toast.LENGTH_SHORT
                )
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(RestoreAgent.TAG)
            } else { // state == blocked
                // no-op
            }
        }
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                startActivity(Intent().setClass(this, PauseActivity::class.java))
                finish()
            }
        }
    }

    private fun removeThisMethod() {
        // change the persistent state for defaultDnsUrl, if its google.com (only for v055d)
        // fixme: remove this post v054.
        // this is to fix the default dns url, as the default dns url is changed from
        // dns.google.com to dns.google. In servers.xml default ips available for dns.google
        // so changing the default dns url to dns.google
        if (persistentState.defaultDnsUrl.contains("dns.google.com")) {
            persistentState.defaultDnsUrl = Constants.DEFAULT_DNS_LIST[2].url
        }
        moveRemoteBlocklistFileFromAsset()
        // reset the bio metric auth time, as now the value is changed from System.currentTimeMillis
        // to SystemClock.elapsedRealtime
        persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
    }

    private fun rdnsRemote() {
        // enforce the dns to sky for play store build, and max for website and f-droid build
        // on first time launch
        io {
            if (isPlayStoreFlavour()) {
                appConfig.switchRethinkDnsToSky()
            } else {
                appConfig.switchRethinkDnsToMax()
            }
        }
    }

    // fixme: find a cleaner way to implement this, move this to some other place
    private fun moveRemoteBlocklistFileFromAsset() {
        io {
            // already there is a remote blocklist file available
            if (
                persistentState.remoteBlocklistTimestamp >
                    Constants.PACKAGED_REMOTE_FILETAG_TIMESTAMP
            ) {
                RethinkBlocklistManager.readJson(
                    this,
                    RethinkBlocklistManager.DownloadType.REMOTE,
                    persistentState.remoteBlocklistTimestamp
                )
                return@io
            }

            RemoteFileTagUtil.moveFileToLocalDir(this.applicationContext, persistentState)
        }
    }

    private fun launchOnboardActivity() {
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
        finish()
    }

    private fun updateNewVersion() {
        if (!isNewVersion()) return

        val version = getLatestVersion()
        Logger.i(LOG_TAG_UI, "New version detected, updating the app version, version: $version")
        persistentState.appVersion = version
        persistentState.showWhatsNewChip = true

        // FIXME: remove this post v054
        // this is to fix the local blocklist default download location
        removeThisMethod()
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? = getPackageMetadata(this.packageManager, this.packageName)
        return pInfo?.versionCode ?: 0
    }

    // FIXME - Move it to Android's built-in WorkManager
    private fun initUpdateCheck() {
        if (!isUpdateRequired()) return

        val diff = System.currentTimeMillis() - persistentState.lastAppUpdateCheck

        val daysElapsed = TimeUnit.MILLISECONDS.toDays(diff)
        Logger.i(LOG_TAG_UI, "App update check initiated, number of days: $daysElapsed")
        if (daysElapsed <= 1L) return

        checkForUpdate()
    }

    private fun isUpdateRequired(): Boolean {
        val calendar: Calendar = Calendar.getInstance()
        val day: Int = calendar.get(Calendar.DAY_OF_WEEK)
        return (day == Calendar.FRIDAY || day == Calendar.SATURDAY) &&
            persistentState.checkForAppUpdate
    }

    fun checkForUpdate(
        isInteractive: AppUpdater.UserPresent = AppUpdater.UserPresent.NONINTERACTIVE
    ) {
        // do not check for debug builds
        if (BuildConfig.DEBUG) return

        // Check updates only for play store / website version. Not fDroid.
        if (!isPlayStoreFlavour() && !isWebsiteFlavour()) {
            Logger.d(
                LOG_TAG_APP_UPDATE,
                "Check for update: Not play or website- ${BuildConfig.FLAVOR}"
            )
            return
        }

        if (isGooglePlayServicesAvailable() && isPlayStoreFlavour()) {
            try {
                appUpdateManager.checkForAppUpdate(
                    isInteractive,
                    this,
                    installStateUpdatedListener
                ) // Might be play updater or web updater
            } catch (e: Exception) {
                Logger.e(LOG_TAG_APP_UPDATE, "err in app update check: ${e.message}", e)
                showDownloadDialog(
                    AppUpdater.InstallSource.STORE,
                    getString(R.string.download_update_dialog_failure_title),
                    getString(R.string.download_update_dialog_failure_message)
                )
            }
        } else {
            try {
                get<NonStoreAppUpdater>()
                    .checkForAppUpdate(
                        isInteractive,
                        this,
                        installStateUpdatedListener
                    ) // Always web updater
            } catch (e: Exception) {
                Logger.e(LOG_TAG_APP_UPDATE, "Error in app (web) update check: ${e.message}", e)
                showDownloadDialog(
                    AppUpdater.InstallSource.OTHER,
                    getString(R.string.download_update_dialog_failure_title),
                    getString(R.string.download_update_dialog_failure_message)
                )
            }
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        // applicationInfo.enabled - When false, indicates that all components within
        // this application are considered disabled, regardless of their individually set enabled
        // status.
        // TODO: prompt dialog to user that Playservice is disabled, so switch to update
        // check for website
        return Utilities.getApplicationInfo(this, PKG_NAME_PLAY_STORE)?.enabled ?: false
    }

    private val installStateUpdatedListener =
        object : AppUpdater.InstallStateListener {
            override fun onStateUpdate(state: AppUpdater.InstallState) {
                Logger.i(LOG_TAG_UI, "InstallStateUpdatedListener: state: " + state.status)
                when (state.status) {
                    AppUpdater.InstallStatus.DOWNLOADED -> {
                        // CHECK THIS if AppUpdateType.FLEXIBLE, otherwise you can skip
                        showUpdateCompleteSnackbar()
                    }
                    else -> {
                        appUpdateManager.unregisterListener(this)
                    }
                }
            }

            override fun onUpdateCheckFailed(
                installSource: AppUpdater.InstallSource,
                isInteractive: AppUpdater.UserPresent
            ) {
                runOnUiThread {
                    if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                        showDownloadDialog(
                            installSource,
                            getString(R.string.download_update_dialog_failure_title),
                            getString(R.string.download_update_dialog_failure_message)
                        )
                    }
                }
            }

            override fun onUpToDate(
                installSource: AppUpdater.InstallSource,
                isInteractive: AppUpdater.UserPresent
            ) {
                runOnUiThread {
                    if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                        showDownloadDialog(
                            installSource,
                            getString(R.string.download_update_dialog_message_ok_title),
                            getString(R.string.download_update_dialog_message_ok)
                        )
                    }
                }
            }

            override fun onUpdateAvailable(installSource: AppUpdater.InstallSource) {
                runOnUiThread {
                    showDownloadDialog(
                        installSource,
                        getString(R.string.download_update_dialog_title),
                        getString(R.string.download_update_dialog_message)
                    )
                }
            }

            override fun onUpdateQuotaExceeded(installSource: AppUpdater.InstallSource) {
                runOnUiThread {
                    showDownloadDialog(
                        installSource,
                        getString(R.string.download_update_dialog_trylater_title),
                        getString(R.string.download_update_dialog_trylater_message)
                    )
                }
            }
        }

    private fun showUpdateCompleteSnackbar() {
        val snack =
            Snackbar.make(
                b.container,
                getString(R.string.update_complete_snack_message),
                Snackbar.LENGTH_INDEFINITE
            )
        snack.setAction(getString(R.string.update_complete_action_snack)) {
            appUpdateManager.completeUpdate()
        }
        snack.setActionTextColor(ContextCompat.getColor(this, R.color.primaryLightColorText))
        snack.show()
    }

    private fun showDownloadDialog(
        source: AppUpdater.InstallSource,
        title: String,
        message: String
    ) {
        if (!isInForeground()) return

        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(false)
        if (
            message == getString(R.string.download_update_dialog_message_ok) ||
                message == getString(R.string.download_update_dialog_failure_message) ||
                message == getString(R.string.download_update_dialog_trylater_message)
        ) {
            builder.setPositiveButton(getString(R.string.hs_download_positive_default)) {
                dialogInterface,
                _ ->
                dialogInterface.dismiss()
            }
        } else {
            if (source == AppUpdater.InstallSource.STORE) {
                builder.setPositiveButton(getString(R.string.hs_download_positive_play_store)) {
                    dialogInterface,
                    _ ->
                    appUpdateManager.completeUpdate()
                    dialogInterface.dismiss()
                }
            } else {
                builder.setPositiveButton(getString(R.string.hs_download_positive_website)) {
                    dialogInterface,
                    _ ->
                    initiateDownload()
                    dialogInterface.dismiss()
                }
            }
            builder.setNegativeButton(getString(R.string.hs_download_negative_default)) {
                dialogInterface,
                _ ->
                persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                dialogInterface.dismiss()
            }
        }

        builder.create().show()
    }

    private fun initiateDownload() {
        try {
            val url = Constants.RETHINK_APP_DOWNLOAD_LINK
            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(this, getString(R.string.no_browser_error), Toast.LENGTH_SHORT)
            Logger.w(Logger.LOG_TAG_VPN, "Failure opening rethink download link: ${e.message}", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        } catch (e: IllegalArgumentException) {
            Logger.w(LOG_TAG_DOWNLOAD, "Unregister receiver exception")
        }
    }

    private fun setupNavigationItemSelectedListener() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        val btmNavView = findViewById<BottomNavigationView>(R.id.nav_view)
        btmNavView.setupWithNavController(navController)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
