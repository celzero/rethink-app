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

import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.NonStoreAppUpdater
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.ActivityHomeScreenBinding
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_PLAY
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_WEBSITE
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import okhttp3.*
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

class HomeScreenActivity : AppCompatActivity(R.layout.activity_home_screen) {
    private val b by viewBinding(ActivityHomeScreenBinding::bind)

    private val refreshDatabase by inject<RefreshDatabase>()

    private lateinit var settingsFragment: SettingsFragment
    private lateinit var homeScreenFragment: HomeScreenFragment
    private lateinit var aboutFragment: AboutFragment

    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()
    private val persistentState by inject<PersistentState>()
    private val appUpdateManager by inject<AppUpdater>()


    /*TODO : This task need to be completed.
             Add all the appinfo in the global variable during appload
             Handle those things in the application instead of reaching to DB every time
             Call the coroutine scope to insert/update/delete the values*/

    object GlobalVariable {
        var appList: MutableMap<String, AppInfo> = HashMap()
        var backgroundAllowedUID: MutableMap<Int, Boolean> = HashMap()
        var blockedUID: MutableMap<Int, Boolean> = HashMap()

        var braveModeToggler: MutableLiveData<Int> = MutableLiveData()
        var connectedDNS: MutableLiveData<String> = MutableLiveData()
        var cryptRelayToRemove: String = ""

        var appStartTime: Long = System.currentTimeMillis()
        var DEBUG = false
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
    }

    companion object {
        var isLoadingComplete: Boolean = false
        var enqueue: Long = 0

        fun setupComplete() {
            isLoadingComplete = true
        }

        fun setupStart() {
            isLoadingComplete = false
        }

    }

    //TODO : Remove the unwanted data and the assignments happening
    //TODO : Create methods and segregate the data.
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Utilities.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        if (persistentState.firstTimeLaunch) {
            persistentState.setConnectedDNS(Constants.RETHINK_DNS)
            launchOnboardActivity()
        } else {
            showNewFeaturesDialog()
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

    }

    private fun modifyPersistence() {
        persistentState.numberOfRequests = persistentState.oldNumberRequests.toLong()
        persistentState.numberOfBlockedRequests = persistentState.oldBlockedRequests.toLong()
    }

    private fun insertDefaultData() {
        if (persistentState.insertionCompleted) return

        refreshDatabase.insertDefaultDNSList()
        refreshDatabase.insertDefaultDNSCryptList()
        refreshDatabase.insertDefaultDNSCryptRelayList()
        refreshDatabase.insertDefaultDNSProxy()
        refreshDatabase.updateCategoryInDB()
        persistentState.insertionCompleted = true
    }

    private fun launchOnboardActivity() {
        startActivity(Intent(this, WelcomeActivity::class.java))
    }

    private fun showNewFeaturesDialog() {
        if (!isNewVersion()) return

        val inflater: LayoutInflater = LayoutInflater.from(this)
        val view: View = inflater.inflate(R.layout.dialog_whatsnew, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(view).setTitle(getString(R.string.whats_dialog_title))

        builder.setPositiveButton(
            getString(R.string.about_dialog_positive_button)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        builder.setNeutralButton(getString(R.string.about_dialog_neutral_button)) { _, _ ->
            val intent = Intent(Intent.ACTION_VIEW, (getString(R.string.about_mail_to)).toUri())
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject))
            startActivity(intent)
        }

        builder.setCancelable(false)
        builder.create().show()

        // FIXME - Remove this after the version v053f
        // this is to fix the persistance state which was saved as Int instead of Long.
        // Modification of persistence state
        modifyPersistence()
    }

    private fun updateNewVersion() {
        if (isNewVersion()) {
            val version = getLatestVersion()
            persistentState.appVersion = version
        }
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    private fun getLatestVersion(): Int {
        return try {
            val pInfo: PackageInfo = this.packageManager.getPackageInfo(this.packageName, 0)
            pInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG_UI, "package not found, cannot fetch version code: ${e.message}", e)
            0
        }
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

    fun checkForUpdate(userInitiation: Boolean = false) {
        if (BuildConfig.FLAVOR == FLAVOR_PLAY) {
            appUpdateManager.checkForAppUpdate(userInitiation, this,
                                               installStateUpdatedListener) // Might be play updater or web updater
        } else if (BuildConfig.FLAVOR == FLAVOR_WEBSITE) {
            get<NonStoreAppUpdater>().checkForAppUpdate(userInitiation, this,
                                                        installStateUpdatedListener) // Always web updater
        }
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

        override fun onUpdateCheckFailed(installSource: AppUpdater.InstallSource) {
            runOnUiThread {
                showDownloadDialog(installSource,
                                   getString(R.string.download_update_dialog_failure_title),
                                   getString(R.string.download_update_dialog_failure_message))
            }
        }

        override fun onUpToDate(installSource: AppUpdater.InstallSource) {
            runOnUiThread {
                showDownloadDialog(installSource,
                                   getString(R.string.download_update_dialog_message_ok_title),
                                   getString(R.string.download_update_dialog_message_ok))
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
