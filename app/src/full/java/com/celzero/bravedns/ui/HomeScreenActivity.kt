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
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.NonStoreAppUpdater
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_FILE_EXTN
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_RESTART_APP
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_SCHEME
import com.celzero.bravedns.backup.RestoreAgent
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.activity.WelcomeActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Constants.Companion.PKG_NAME_PLAY_STORE
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_LENGTH
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_REGENERATION_PERIOD_DAYS
import com.celzero.bravedns.util.NewSettingsManager
import com.celzero.bravedns.util.RemoteFileTagUtil
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getPackageMetadata
import com.celzero.bravedns.util.Utilities.getRandomString
import com.celzero.bravedns.util.Utilities.isAtleastO_MR1
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.isWebsiteFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.util.Calendar
import java.util.concurrent.TimeUnit

class HomeScreenActivity : AppCompatActivity(R.layout.activity_home_screen) {
    private val persistentState by inject<PersistentState>()
    private val appInfoDb by inject<AppInfoRepository>()
    private val appUpdateManager by inject<AppUpdater>()
    private val rdb by inject<RefreshDatabase>()
    private val appConfig by inject<AppConfig>()

    // TODO: see if this can be replaced with a more robust solution
    // keep track of when app went to background
    private var appInBackground = false

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (isAtleastO_MR1()) {
            Logger.vv(LOG_TAG_UI, "Setting up window insets for Android 27+")
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_view)) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = systemBars.bottom) // Add bottom padding to keep icons visible
                insets
                WindowInsetsCompat.CONSUMED
            }
        }

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        // do not launch on board activity when app is running on TV
        if (persistentState.firstTimeLaunch && !isAppRunningOnTv()) {
            launchOnboardActivity()
            return
        }

        handleFrostEffectIfNeeded(persistentState.theme)

        updateNewVersion()

        setupNavigationItemSelectedListener()

        // handle intent receiver for backup/restore
        handleIntent()

        initUpdateCheck()

        observeAppState()

        handleOnBackPressed()

        NewSettingsManager.handleNewSettings()

        regenerateFirebaseTokenIfNeeded()

        // enable in-app messaging, will be used to show in-app messages in case of billing issues
        //enableInAppMessaging()
    }


    /*private fun enableInAppMessaging() {
        initiateBillingIfNeeded()
        // enable in-app messaging
        InAppBillingHandler.enableInAppMessaging(this)
        Logger.v(LOG_IAB, "enableInAppMessaging: enabled")
    }

    private fun initiateBillingIfNeeded() {
        if (InAppBillingHandler.isBillingClientSetup()) {
            Logger.i(LOG_IAB, "ensureBillingSetup: billing client already setup")
            return
        }

        InAppBillingHandler.initiate(this.applicationContext)
        Logger.i(LOG_IAB, "ensureBillingSetup: billing client initiated")
    }*/

    /*override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // by simply receiving and setting the new intent, we ensure that when the activity
        // is brought back to the foreground, it uses the latest intent state
        Logger.v(LOG_TAG_UI, "home screen activity received new intent")
    }*/

    override fun onResume() {
        super.onResume()
        // if app is coming from background, don't reset the activity stack
        if (appInBackground) {
            appInBackground = false
            Logger.d(LOG_TAG_UI, "app restored from background, maintaining activity stack")
        }
    }

    // check if app running on TV
    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
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

    private fun regenerateFirebaseTokenIfNeeded() {
        if (Utilities.isFdroidFlavour()) return
        if (!persistentState.firebaseErrorReportingEnabled) return

        val now = System.currentTimeMillis()
        val fortyFiveDaysMs = TimeUnit.DAYS.toMillis(TOKEN_REGENERATION_PERIOD_DAYS)

        var token = persistentState.firebaseUserToken
        var ts = persistentState.firebaseUserTokenTimestamp
        if (token.isBlank() || now - ts > fortyFiveDaysMs) {
            token = getRandomString(TOKEN_LENGTH)
            ts = now
            persistentState.firebaseUserToken = token
            persistentState.firebaseUserTokenTimestamp = ts
            FirebaseErrorReporting.setUserId(token)
        }
    }

    private fun showRestoreDialog(uri: Uri) {
        if (!isInForeground()) return

        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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
        val dialog = builder.create()
        dialog.show()
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
        // set allowBypass to false for all versions, overriding the user's preference.
        // the default was true for Play Store and website versions, and false for F-Droid.
        // when allowBypass is true, some OEMs bypass the VPN service, causing connections
        // to fail due to the "Block connections without VPN" option.
        persistentState.allowBypass = false

        io {
            appInfoDb.setRethinkToBypassDnsAndFirewall()
            appInfoDb.setRethinkToBypassProxy(true)
        }

        // change the persistent state for defaultDnsUrl, if its google.com (only for v055d)
        // TODO: remove this post v054.
        // this is to fix the default dns url, as the default dns url is changed from
        // dns.google.com to dns.google. In servers.xml default ips available for dns.google
        // so changing the default dns url to dns.google
        if (persistentState.defaultDnsUrl.contains("dns.google.com")) {
            persistentState.defaultDnsUrl = Constants.DEFAULT_DNS_LIST[2].url
        }
        moveRemoteBlocklistFileFromAsset()
        // if biometric auth is enabled, then set the biometric auth type to 3 (15 minutes)
        if (persistentState.biometricAuth) {
            persistentState.biometricAuthType =
                MiscSettingsActivity.BioMetricType.FIFTEEN_MIN.action
            // reset the bio metric auth time, as now the value is changed from System.currentTimeMillis
            // to SystemClock.elapsedRealtime
            persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
        }

        // reset the local blocklist download from android download manager to custom in v055o
        persistentState.useCustomDownloadManager = true

        // delete residue wgs from database, remove this post v055o
        io { WireguardManager.deleteResidueWgs() }
        // reset the plus url to empty if it is set as /rec
        io {
            val rpe = appConfig.getRethinkPlusEndpoint()
            val url = rpe?.url ?: return@io
            if (url == "https://max.rethinkdns.com/rec" || url == "https://rplus.rethinkdns.com/rec") {
                val newUrl = if (rpe.url.contains(MAX_ENDPOINT)) {
                    Constants.RETHINK_BASE_URL_MAX
                } else {
                    Constants.RETHINK_BASE_URL_SKY
                }
                appConfig.updateRethinkEndpoint(Constants.RETHINK_DNS_PLUS, newUrl, 0)
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
        startActivity(intent)
        finish()
    }

    private fun updateNewVersion() {
        if (!isNewVersion()) return

        // no need to show new settings on first time launch
        if (persistentState.appVersion != 0) {
            // if app version is not 0, then it means the app is updated
            NewSettingsManager.initializeNewSettings()
            Logger.i(LOG_TAG_UI, "app version already set, so its update, showing new settings")
        }
        val version = getLatestVersion()
        Logger.i(LOG_TAG_UI, "new version detected, updating the app version, version: $version")
        persistentState.appVersion = version
        persistentState.showWhatsNewChip = true
        persistentState.appUpdateTimeTs = System.currentTimeMillis()

        // FIXME: remove this post v054
        removeThisMethod()
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? = getPackageMetadata(this.packageManager, this.packageName)
        // TODO: modify this to use the latest version code api
        @Suppress("DEPRECATION")
        val v = pInfo?.versionCode ?: 0
        // latest version has apk variant (baseAbiVersionCode * 10000000 + variant.versionCode)
        // so we need to mod the version code by 10000000 to get the actual version code
        // for example: 10000000 + 45 = 10000045, so the version code is 1
        // see build.gradle (:app), #project.ext.versionCodes
        val latestVersionCode = v % 10000000 // 10000000 is the base version code
        Logger.i(LOG_TAG_UI, "latest version code: $latestVersionCode")
        return latestVersionCode
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
            Logger.i(LOG_TAG_APP_UPDATE, "update check not for ${BuildConfig.FLAVOR}")
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
                Logger.crash(LOG_TAG_APP_UPDATE, "err in app update check: ${e.message}", e)
                runOnUiThread {
                    showDownloadDialog(
                        AppUpdater.InstallSource.STORE,
                        getString(R.string.download_update_dialog_failure_title),
                        getString(R.string.download_update_dialog_failure_message)
                    )
                }
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
                runOnUiThread {
                    showDownloadDialog(
                        AppUpdater.InstallSource.OTHER,
                        getString(R.string.download_update_dialog_failure_title),
                        getString(R.string.download_update_dialog_failure_message)
                    )
                }
            }
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        // applicationInfo.enabled - When false, indicates that all components within
        // this application are considered disabled, regardless of their individually set enabled
        // status.
        // TODO: prompt dialog to user that Playservice is disabled, so switch to update
        // check for website
        return Utilities.getApplicationInfo(this, PKG_NAME_PLAY_STORE)?.enabled == true
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
        try {
            val container: View = findViewById(R.id.container)
            val snack =
                Snackbar.make(
                    container,
                    getString(R.string.update_complete_snack_message),
                    Snackbar.LENGTH_INDEFINITE
                )
            snack.setAction(getString(R.string.update_complete_action_snack)) {
                appUpdateManager.completeUpdate()
            }
            snack.setActionTextColor(ContextCompat.getColor(this, R.color.primaryLightColorText))
            snack.show()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err showing update complete snackbar: ${e.message}", e)
        }
    }

    private fun showDownloadDialog(
        source: AppUpdater.InstallSource,
        title: String,
        message: String
    ) {
        if (!isInForeground()) return

        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(title)

        // Determine dialog type based on title to decide if it should be modal
        val isUpdateAvailable = title == getString(R.string.download_update_dialog_title)
        val isUpToDate = message == getString(R.string.download_update_dialog_message_ok)
        val isError = message == getString(R.string.download_update_dialog_failure_message)
        val isQuotaExceeded = message == getString(R.string.download_update_dialog_trylater_message)

        // Adjust message for Play Store if needed
        if (isUpdateAvailable && source == AppUpdater.InstallSource.STORE) {
            // Play Store updates should use native UI, but if we reach here, show appropriate message
            builder.setMessage("A new version is available. Please update from Play Store.")
        } else {
            builder.setMessage(message)
        }

        // Make dialog non-dismissible (modal) only when an actual update is available
        // User cannot dismiss by tapping outside or pressing back button
        // However, user can still choose "Remind me later" button
        builder.setCancelable(!isUpdateAvailable)

        when {
            isUpdateAvailable -> {
                // Update is available - modal dialog with explicit user choice
                if (source == AppUpdater.InstallSource.STORE) {
                    // For Play Store updates, this dialog rarely appears as Google's native UI handles it
                    // But if it does appear, just show OK to dismiss (native UI should have been shown)
                    builder.setPositiveButton(getString(R.string.hs_download_positive_default)) { dialogInterface, _ ->
                        appUpdateManager.completeUpdate()
                        dialogInterface.dismiss()
                    }
                    builder.setNegativeButton(getString(R.string.hs_download_negative_default)) { dialogInterface, _ ->
                        persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                        dialogInterface.dismiss()
                    }
                } else {
                    // For website version, open browser to download - this is the main use case
                    builder.setPositiveButton(getString(R.string.hs_download_positive_website)) { dialogInterface, _ ->
                        initiateDownload()
                        dialogInterface.dismiss()
                    }
                    // Negative button allows user to postpone the update
                    builder.setNegativeButton(getString(R.string.hs_download_negative_default)) { dialogInterface, _ ->
                        persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                        dialogInterface.dismiss()
                    }
                }
            }
            isUpToDate || isError || isQuotaExceeded -> {
                // Informational dialogs - dismissible with OK button
                builder.setCancelable(true)
                builder.setPositiveButton(getString(R.string.hs_download_positive_default)) { dialogInterface, _ ->
                    dialogInterface.dismiss()
                }
            }
            else -> {
                // Fallback for any other case - make it dismissible
                builder.setCancelable(true)
                builder.setPositiveButton(getString(R.string.hs_download_positive_default)) { dialogInterface, _ ->
                    dialogInterface.dismiss()
                }
            }
        }

        try {
            val dialog = builder.create()
            dialog.show()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err showing download dialog: ${e.message}", e)
        }
    }

    private fun initiateDownload() {
        try {
            val url = Constants.RETHINK_APP_DOWNLOAD_LINK
            val uri = url.toUri()
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(this, getString(R.string.no_browser_error), Toast.LENGTH_SHORT)
            Logger.w(Logger.LOG_TAG_VPN, "err opening rethink download link: ${e.message}", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        } catch (e: IllegalArgumentException) {
            Logger.w(LOG_TAG_DOWNLOAD, "Unregister receiver exception")
        }
        // mark that app is going to background
        appInBackground = true
        Logger.v(LOG_TAG_UI, "home screen activity is stopped, app going to background")
    }

    private fun handleOnBackPressed() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val navHostFragment =
                        supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
                    val navController = navHostFragment?.navController
                    val homeId = R.id.homeScreenFragment
                    if (navController?.currentDestination?.id != homeId) {
                        val btmNavView = findViewById<BottomNavigationView>(R.id.nav_view)
                        btmNavView.selectedItemId = homeId
                        navController?.navigate(
                            homeId,
                            null,
                            NavOptions.Builder().setPopUpTo(homeId, true).build()
                        )
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    private fun updateRethinkPlusHighlight() {
        val btmNavView = findViewById<BottomNavigationView>(R.id.nav_view) ?: run {
            Logger.w(LOG_TAG_UI, "BottomNavigationView (R.id.nav_view) not found")
            return
        }

        val menu = btmNavView.menu
        val rethinkPlusItem = menu.findItem(R.id.rethinkPlus)
        if (rethinkPlusItem != null) {
            try {
                rethinkPlusItem.setIcon(R.drawable.ic_rethink_plus_sparkle)
                btmNavView.removeBadge(R.id.rethinkPlus)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "Failed to update rethinkPlus icon/badge: ${e.message}", e)
            }
        } else {
            Logger.w(LOG_TAG_UI, "Menu item R.id.rethinkPlus not present in BottomNavigationView")
        }
    }

    private fun setupNavigationItemSelectedListener() {
        val btmNavView = findViewById<BottomNavigationView>(R.id.nav_view) ?: run {
            Logger.w(LOG_TAG_UI, "setupNavigationItemSelectedListener: BottomNavigationView not found")
            return
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val navController = navHostFragment?.navController

        btmNavView.setOnItemSelectedListener { item ->
            val homeId = R.id.homeScreenFragment

            when (item.itemId) {
                R.id.rethinkPlus -> {
                    // Navigate to rethinkPlus fragment regardless of subscription status
                    // The fragment itself will handle redirecting to dashboard if needed
                    if (navController?.currentDestination?.id != R.id.rethinkPlus) {
                        navController?.navigate(
                            R.id.rethinkPlus,
                            null,
                            NavOptions.Builder().setPopUpTo(homeId, false).build()
                        )
                    }
                    // safe-check before marking checked
                    btmNavView.menu.findItem(R.id.rethinkPlus)?.isChecked = true
                    true
                }

                homeId -> {
                    if (navController != null && navController.currentDestination?.id != homeId) {
                        navController.navigate(
                            homeId,
                            null,
                            NavOptions.Builder().setPopUpTo(homeId, true).build()
                        )
                        true
                    } else {
                        false
                    }
                }

                else -> {
                    if (navController != null && navController.currentDestination?.id != item.itemId) {
                        navController.navigate(
                            item.itemId,
                            null,
                            NavOptions.Builder().setPopUpTo(homeId, false).build()
                        )
                        true
                    } else {
                        false
                    }
                }
            }
        }

        // Sync highlight with nav changes, guard call to update method
        navController?.addOnDestinationChangedListener { _, _, _ ->
            updateRethinkPlusHighlight()
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
