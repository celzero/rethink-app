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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.work.*
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.NonStoreAppUpdater
import com.celzero.bravedns.R
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_FILE_EXTN
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_SCHEME
import com.celzero.bravedns.backup.RestoreAgent
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityHomeScreenBinding
import com.celzero.bravedns.service.*
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.PKG_NAME_PLAY_STORE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_UPDATE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_BACKUP_RESTORE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.Companion.blocklistDownloadBasePath
import com.celzero.bravedns.util.Utilities.Companion.getPackageMetadata
import com.celzero.bravedns.util.Utilities.Companion.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.Companion.isWebsiteFlavour
import com.celzero.bravedns.util.Utilities.Companion.oldLocalBlocklistDownloadDir
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

class HomeScreenActivity : AppCompatActivity(R.layout.activity_home_screen) {
    private val b by viewBinding(ActivityHomeScreenBinding::bind)

    private lateinit var settingsFragment: SettingsFragment
    private lateinit var homeScreenFragment: HomeScreenFragment
    private lateinit var aboutFragment: AboutFragment
    private lateinit var statisticsFragment: SummaryStatisticsFragment

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val appUpdateManager by inject<AppUpdater>()

    // support for biometric authentication
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    object GlobalVariable {
        var DEBUG = BuildConfig.DEBUG
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        if (persistentState.firstTimeLaunch) {
            launchOnboardActivity()
            return
        }
        updateNewVersion()

        if (savedInstanceState == null) {
            homeScreenFragment = HomeScreenFragment()
            supportFragmentManager
                .beginTransaction()
                .replace(
                    R.id.fragment_container,
                    homeScreenFragment,
                    homeScreenFragment.javaClass.simpleName
                )
                .commit()
        }

        setupNavigationItemSelectedListener()

        // handle intent receiver for backup/restore
        handleIntent()

        initUpdateCheck()

        observeAppState()

        if (persistentState.biometricAuth) {
            biometricPrompt()
        }
    }

    private fun biometricPrompt() {
        // ref: https://developer.android.com/training/sign-in/biometric-auth
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt =
            BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Log.i(
                            LOG_TAG_UI,
                            "Biometric authentication error (code: $errorCode): $errString"
                        )
                        showToastUiCentered(
                            applicationContext,
                            getString(R.string.hs_biometeric_error) + errString,
                            Toast.LENGTH_SHORT
                        )
                        finish()
                    }

                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        super.onAuthenticationSucceeded(result)
                        Log.i(LOG_TAG_UI, "Biometric authentication succeeded")
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        showToastUiCentered(
                            applicationContext,
                            getString(R.string.hs_biometeric_failed),
                            Toast.LENGTH_SHORT
                        )
                        Log.i(LOG_TAG_UI, "Biometric authentication failed")
                        biometricPrompt.authenticate(promptInfo)
                    }
                }
            )

        promptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.hs_biometeric_title))
                .setSubtitle(getString(R.string.hs_biometeric_desc))
                .setDeviceCredentialAllowed(true)
                .setConfirmationRequired(false)
                .build()

        // BIOMETRIC_WEAK :Any biometric (e.g. fingerprint, iris, or face) on the device that meets
        // or exceeds the requirements for Class 2(formerly Weak), as defined by the Android CDD.
        if (
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
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
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.brbs_restore_dialog_title)
        builder.setMessage(R.string.brbs_restore_dialog_message)
        builder.setPositiveButton(getString(R.string.brbs_restore_dialog_positive)) { _, _ ->
            startRestore(uri)
            observeRestoreWorker()
        }

        builder.setNegativeButton(getString(R.string.brbs_restore_dialog_negative)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun startRestore(fileUri: Uri) {
        Log.i(LOG_TAG_BACKUP_RESTORE, "invoke worker to initiate the restore process")
        val data = Data.Builder()
        data.putString(BackupHelper.DATA_BUILDER_RESTORE_URI, fileUri.toString())

        val importWorker =
            OneTimeWorkRequestBuilder<RestoreAgent>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
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
            Log.i(
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
        // for version v03k
        changeDefaultInternetProtocol()
        removeKeyFromSharedPref()
        changeDefaultToMax()

        // for version v054
        updateIfRethinkConnectedv053x()
        moveRemoteBlocklistFileFromAsset()

        io {
            moveLocalBlocklistFiles()

            // path: /data/data/com.celzero.bravedns/files
            val oldFolder = File(this.filesDir.canonicalPath)
            deleteUnwantedFolders(oldFolder)

            // already there is a local blocklist file available, complete the initial filetag read
            if (persistentState.localBlocklistTimestamp != INIT_TIME_MS) {
                RethinkBlocklistManager.readJson(
                    this,
                    RethinkBlocklistManager.DownloadType.LOCAL,
                    persistentState.localBlocklistTimestamp
                )
            }
        }
    }

    private fun changeDefaultToMax() {
        // only change the rethink dns to max for website and f-droid build
        if (isPlayStoreFlavour()) return

        io { appConfig.switchRethinkDnsToMax() }
    }

    private fun changeDefaultInternetProtocol() {
        // (v053k) change the internet protocol version to IPv4 as default
        persistentState.internetProtocolType = InternetProtocol.IPv4.id
    }

    private fun removeKeyFromSharedPref() {
        // below keys are not used, remove from shared pref
        val removeLocal = "local_blocklist_update_check"
        val removeRemote = "remote_blocklist_update_check"
        val killApp = "kill_app_on_firewall"
        val dnsCryptRelay = "dnscrypt_relay"

        val sharedPref: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = sharedPref.edit()
        editor.remove(removeLocal)
        editor.remove(removeRemote)
        editor.remove(killApp)
        editor.remove(dnsCryptRelay)
        editor.apply()
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

    private fun updateIfRethinkConnectedv053x() {
        if (!appConfig.isRethinkDnsConnectedv053x()) return

        io {
            appConfig.updateRethinkPlusCountv053x(persistentState.getRemoteBlocklistCount())
            persistentState.dnsType = AppConfig.DnsType.RETHINK_REMOTE.type
            appConfig.enableRethinkDnsPlus()
        }
    }

    private fun moveLocalBlocklistFiles() {
        val path = oldLocalBlocklistDownloadDir(this, persistentState.localBlocklistTimestamp)
        val blocklistsExist =
            Constants.ONDEVICE_BLOCKLISTS_ADM.all {
                File(path + File.separator + it.filename).exists()
            }
        if (!blocklistsExist) return

        changeDefaultLocalBlocklistLocation()
    }

    // move the files of local blocklist to specific folder (../files/local_blocklist/<timestamp>)
    private fun changeDefaultLocalBlocklistLocation() {
        val baseDir =
            blocklistDownloadBasePath(
                this,
                LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                persistentState.localBlocklistTimestamp
            )
        File(baseDir).mkdirs()
        Constants.ONDEVICE_BLOCKLISTS_ADM.forEach {
            val currentFile =
                File(
                    oldLocalBlocklistDownloadDir(this, persistentState.localBlocklistTimestamp) +
                        it.filename
                )
            val newFile = File(baseDir + File.separator + it.filename)
            com.google.common.io.Files.move(currentFile, newFile)
        }
    }

    private fun deleteUnwantedFolders(fileOrDirectory: File) {
        // TODO: delete the old folders with timestamp in the files dir (../files/<timestamp>)
        // this operation is performed after moving the local blocklist to
        // specific folder

        if (fileOrDirectory.name.startsWith("16")) {
            if (fileOrDirectory.isDirectory) {
                fileOrDirectory.listFiles()?.forEach { child -> deleteUnwantedFolders(child) }
            }
            fileOrDirectory.delete()
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
        Log.i(LOG_TAG_UI, "App update check initiated, number of days: $daysElapsed")
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

        // Check updates only for play store / website version. Not fDroid.
        if (!isPlayStoreFlavour() && !isWebsiteFlavour()) {
            if (DEBUG)
                Log.d(
                    LOG_TAG_APP_UPDATE,
                    "Check for update: Not play or website- ${BuildConfig.FLAVOR}"
                )
            return
        }

        if (isGooglePlayServicesAvailable() && isPlayStoreFlavour()) {
            appUpdateManager.checkForAppUpdate(
                isInteractive,
                this,
                installStateUpdatedListener
            ) // Might be play updater or web updater
        } else {
            get<NonStoreAppUpdater>()
                .checkForAppUpdate(
                    isInteractive,
                    this,
                    installStateUpdatedListener
                ) // Always web updater
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
                Log.i(LOG_TAG_UI, "InstallStateUpdatedListener: state: " + state.status)
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
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(true)
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
                    _,
                    _ ->
                    appUpdateManager.completeUpdate()
                }
            } else {
                builder.setPositiveButton(getString(R.string.hs_download_positive_website)) { _, _
                    ->
                    initiateDownload()
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
            Log.w(
                LoggerConstants.LOG_TAG_VPN,
                "Failure opening rethink download link: ${e.message}",
                e
            )
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_DOWNLOAD, "Unregister receiver exception")
        }
    }

    private fun setupNavigationItemSelectedListener() {
        b.navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home_screen -> {
                    homeScreenFragment = HomeScreenFragment()
                    supportFragmentManager
                        .beginTransaction()
                        .replace(
                            R.id.fragment_container,
                            homeScreenFragment,
                            homeScreenFragment.javaClass.simpleName
                        )
                        .commit()
                    return@setOnItemSelectedListener true
                }
                R.id.navigation_settings -> {
                    settingsFragment = SettingsFragment()
                    supportFragmentManager
                        .beginTransaction()
                        .replace(
                            R.id.fragment_container,
                            settingsFragment,
                            settingsFragment.javaClass.simpleName
                        )
                        .commit()
                    return@setOnItemSelectedListener true
                }
                R.id.navigation_statistics -> {
                    statisticsFragment = SummaryStatisticsFragment()
                    supportFragmentManager
                        .beginTransaction()
                        .replace(
                            R.id.fragment_container,
                            statisticsFragment,
                            statisticsFragment.javaClass.simpleName
                        )
                        .commit()
                    return@setOnItemSelectedListener true
                }
                R.id.navigation_about -> {
                    aboutFragment = AboutFragment()
                    supportFragmentManager
                        .beginTransaction()
                        .replace(
                            R.id.fragment_container,
                            aboutFragment,
                            aboutFragment.javaClass.simpleName
                        )
                        .commit()
                    return@setOnItemSelectedListener true
                }
            }
            false
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
