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

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.*
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.NonStoreAppUpdater
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.ActivityHomeScreenBinding
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_PLAY
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_WEBSITE
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_3
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_UPDATE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.Utilities.Companion.convertLongToTime
import com.celzero.bravedns.util.Utilities.Companion.getBugReportFilePath
import com.celzero.bravedns.util.Utilities.Companion.getPackageMetadata
import com.celzero.bravedns.util.Utilities.Companion.isAtleastR
import com.celzero.bravedns.util.Utilities.Companion.writeTrace
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit


class HomeScreenActivity : AppCompatActivity(R.layout.activity_home_screen) {
    private val b by viewBinding(ActivityHomeScreenBinding::bind)

    private val refreshDatabase by inject<RefreshDatabase>()

    private lateinit var settingsFragment: SettingsFragment
    private lateinit var homeScreenFragment: HomeScreenFragment
    private lateinit var aboutFragment: AboutFragment

    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()
    private val persistentState by inject<PersistentState>()
    private val appUpdateManager by inject<AppUpdater>()
    private val appMode by inject<AppMode>()

    /* TODO : This task need to be completed.
             Add all the appinfo in the global variable during appload
             Handle those things in the application instead of reaching to DB every time
             Call the coroutine scope to insert/update/delete the values */

    object GlobalVariable {

        var braveModeToggler: MutableLiveData<Int> = MutableLiveData()

        var appStartTime: Long = System.currentTimeMillis()
        var DEBUG = false
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
    }

    companion object {
        var enqueue: Long = 0
    }

    //TODO : Remove the unwanted data and the assignments happening
    //TODO : Create methods and segregate the data.
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Utilities.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        if (persistentState.firstTimeLaunch) {
            launchOnboardActivity()
        }

        if (appMode.getAppState() == AppMode.AppState.PAUSE.state) {
            openPauseActivity()
        }

        updateNewVersion()

        if (savedInstanceState == null) {
            homeScreenFragment = HomeScreenFragment()
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                              homeScreenFragment,
                                                              homeScreenFragment.javaClass.simpleName).commit()
        }
        b.navView.setOnNavigationItemSelectedListener(onNavigationItemSelectedListener)

        FirewallRules.loadFirewallRules(blockedConnectionsRepository)

        refreshDatabase.deleteOlderDataFromNetworkLogs()

        insertDefaultData()

        initUpdateCheck()

        detectAppExitInfo()
    }

    private fun detectAppExitInfo() {
        if (!isAtleastR()) return

        val path = getBugReportFilePath(this)
        val am: ActivityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        CoroutineScope(Dispatchers.IO).launch {
            // gets all the historical process exit reasons.
            val appExitInfo = am.getHistoricalProcessExitReasons(null, 0, 0)
            var latestTimestamp = persistentState.lastExitTimestamp
            val file = File(path)
            appExitInfo.forEach {
                // Write only the latest exit reason
                if (latestTimestamp < it.timestamp) {
                    val reportDetails = "${it.packageUid},${it.reason},${it.description},${it.importance},${it.pss},${it.rss},${
                        convertLongToTime(it.timestamp, TIME_FORMAT_3)
                    }\n"
                    file.appendText(reportDetails)
                    // Reason ANR will contain traceInputStream in it.
                    // Write into file when the traceInput is available
                    if (it.reason == ApplicationExitInfo.REASON_ANR) {
                        writeTrace(file, it.traceInputStream)
                    }
                    latestTimestamp = it.timestamp
                }
            }
            // Store the last exit reason time stamp
            persistentState.lastExitTimestamp = latestTimestamp
        }
    }

    private fun removeThisMethod() {
        persistentState.numberOfRequests = persistentState.oldNumberRequests.toLong()
        persistentState.numberOfBlockedRequests = persistentState.oldBlockedRequests.toLong()
    }

    private fun insertDefaultData() {
        if (persistentState.insertionCompleted) return

        CoroutineScope(Dispatchers.IO).launch {
            refreshDatabase.insertDefaultDNSList()
            refreshDatabase.insertDefaultDNSCryptList()
            refreshDatabase.insertDefaultDNSCryptRelayList()
            refreshDatabase.insertDefaultDNSProxy()
            refreshDatabase.updateCategoryRepo()
            persistentState.insertionCompleted = true
        }
    }

    private fun launchOnboardActivity() {
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }

    private fun openPauseActivity() {
        val intent = Intent()
        intent.setClass(this, PauseActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun updateNewVersion() {
        if (isNewVersion()) {
            val version = getLatestVersion()
            persistentState.appVersion = version
            persistentState.showWhatsNewChip = true
            // FIXME - Remove this after the version v053f
            // this is to fix the persistance state which was saved as Int instead of Long.
            // Modification of persistence state
            removeThisMethod()
        }
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

    //FIXME - Move it to Android's built-in WorkManager
    private fun initUpdateCheck() {
        val currentTime = System.currentTimeMillis()
        val diff = currentTime - persistentState.lastAppUpdateCheck

        if (isUpdateRequired()) {
            val numOfDays = TimeUnit.MILLISECONDS.toDays(diff)
            Log.i(LOG_TAG_UI, "App update check initiated, number of days: $numOfDays")
            if (numOfDays <= 1L) return

            checkForUpdate()
        }
    }

    private fun isUpdateRequired(): Boolean {
        val calendar: Calendar = Calendar.getInstance()
        val day: Int = calendar.get(Calendar.DAY_OF_WEEK)
        return (day == Calendar.FRIDAY || day == Calendar.SATURDAY) && persistentState.checkForAppUpdate
    }

    fun checkForUpdate(
            isInteractive: AppUpdater.UserPresent = AppUpdater.UserPresent.NONINTERACTIVE) {

        // Check updates only for play store / website version. Not fDroid.
        if (BuildConfig.FLAVOR != FLAVOR_PLAY && BuildConfig.FLAVOR != FLAVOR_WEBSITE) {
            if (DEBUG) Log.d(LOG_TAG_APP_UPDATE,
                             "Check for update: Not play or website- ${BuildConfig.FLAVOR}")
            return
        }

        if (isGooglePlayServicesAvailable()) {
            appUpdateManager.checkForAppUpdate(isInteractive, this,
                                               installStateUpdatedListener) // Might be play updater or web updater
        } else {
            get<NonStoreAppUpdater>().checkForAppUpdate(isInteractive, this,
                                                        installStateUpdatedListener) // Always web updater
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance()
        val status: Int = googleApiAvailability.isGooglePlayServicesAvailable(this)
        if (status != ConnectionResult.SUCCESS) {
            return false
        }
        return true
    }

    private val installStateUpdatedListener = object : AppUpdater.InstallStateListener {
        override fun onStateUpdate(state: AppUpdater.InstallState) {
            Log.i(LOG_TAG_UI, "InstallStateUpdatedListener: state: " + state.status)
            when (state.status) {
                AppUpdater.InstallStatus.DOWNLOADED -> {
                    //CHECK THIS if AppUpdateType.FLEXIBLE, otherwise you can skip
                    showUpdateCompleteSnackbar()
                }
                else -> {
                    appUpdateManager.unregisterListener(this)
                }
            }
        }

        override fun onUpdateCheckFailed(installSource: AppUpdater.InstallSource,
                                         isInteractive: AppUpdater.UserPresent) {
            runOnUiThread {
                if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                    showDownloadDialog(installSource,
                                       getString(R.string.download_update_dialog_failure_title),
                                       getString(R.string.download_update_dialog_failure_message))
                }
            }
        }

        override fun onUpToDate(installSource: AppUpdater.InstallSource,
                                isInteractive: AppUpdater.UserPresent) {
            runOnUiThread {
                if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                    showDownloadDialog(installSource,
                                       getString(R.string.download_update_dialog_message_ok_title),
                                       getString(R.string.download_update_dialog_message_ok))
                }
            }
        }

        override fun onUpdateAvailable(installSource: AppUpdater.InstallSource) {
            runOnUiThread {
                showDownloadDialog(installSource, getString(R.string.download_update_dialog_title),
                                   getString(R.string.download_update_dialog_message))
            }
        }

        override fun onUpdateQuotaExceeded(installSource: AppUpdater.InstallSource) {
            runOnUiThread {
                showDownloadDialog(installSource,
                                   getString(R.string.download_update_dialog_trylater_title),
                                   getString(R.string.download_update_dialog_trylater_message))
            }
        }
    }

    private fun showUpdateCompleteSnackbar() {
        val snack = Snackbar.make(b.container, getString(R.string.update_complete_snack_message),
                                  Snackbar.LENGTH_INDEFINITE)
        snack.setAction(
            getString(R.string.update_complete_action_snack)) { appUpdateManager.completeUpdate() }
        snack.setActionTextColor(ContextCompat.getColor(this, R.color.textColorMain))
        snack.show()
    }

    private fun showDownloadDialog(source: AppUpdater.InstallSource, title: String,
                                   message: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(true)
        if (message == getString(
                R.string.download_update_dialog_message_ok) || message == getString(
                R.string.download_update_dialog_failure_message) || message == getString(
                R.string.download_update_dialog_trylater_message)) {
            builder.setPositiveButton(
                getString(R.string.hs_download_positive_default)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
        } else {
            if (source == AppUpdater.InstallSource.STORE) {
                builder.setPositiveButton(
                    getString(R.string.hs_download_positive_play_store)) { _, _ ->
                    appUpdateManager.completeUpdate()
                }
            } else {
                builder.setPositiveButton(
                    getString(R.string.hs_download_positive_website)) { _, _ ->
                    initiateDownload()
                }
            }
            builder.setNegativeButton(
                getString(R.string.hs_download_negative_default)) { dialogInterface, _ ->
                persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                dialogInterface.dismiss()
            }
        }

        builder.create().show()
    }


    private fun initiateDownload() {
        val url = Constants.APP_DOWNLOAD_LINK
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = uri
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, @Nullable data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            Log.e(LOG_TAG_DOWNLOAD, "onActivityResult: app download failed")
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

    override fun onResume() {
        super.onResume()
        refreshDatabase.refreshAppInfoDatabase(isForceRefresh = false)
        checkAppState()
    }

    private fun checkAppState() {
        persistentState.appStateLiveData.observe(this, {
            if(it == AppMode.AppState.PAUSE) {
                openPauseActivity()
            }
        })
    }

    private val onNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_internet_manager -> {
                homeScreenFragment = HomeScreenFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                                  homeScreenFragment,
                                                                  homeScreenFragment.javaClass.simpleName).commit()
                return@OnNavigationItemSelectedListener true
            }

            R.id.navigation_settings -> {
                settingsFragment = SettingsFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                                  settingsFragment,
                                                                  settingsFragment.javaClass.simpleName).commit()
                return@OnNavigationItemSelectedListener true
            }

            R.id.navigation_about -> {
                aboutFragment = AboutFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                                  aboutFragment,
                                                                  aboutFragment.javaClass.simpleName).commit()
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }

}
