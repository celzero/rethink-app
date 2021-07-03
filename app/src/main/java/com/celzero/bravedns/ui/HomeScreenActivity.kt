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

import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.ActivityHomeScreenBinding
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQ
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_PLAY_STORE
import com.celzero.bravedns.util.Constants.Companion.RESPONSE_VERSION
import com.celzero.bravedns.util.HttpRequestHelper.Companion.checkStatus
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.common.collect.HashMultimap
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.collections.HashMap

class HomeScreenActivity : AppCompatActivity(R.layout.activity_home_screen) {
    private val b by viewBinding(ActivityHomeScreenBinding::bind)

    private lateinit var internetManagerFragment: InternetManagerFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var homeScreenFragment: HomeScreenFragment
    private lateinit var aboutFragment: AboutFragment
    private lateinit var context: Context
    private val refreshDatabase by inject<RefreshDatabase>()
    private lateinit var downloadManager: DownloadManager
    private var timestamp = 0L

    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()
    private val persistentState by inject<PersistentState>()
    private val appUpdateManager by inject<AppUpdater>()


    /*TODO : This task need to be completed.
             Add all the appinfo in the global variable during appload
             Handle those things in the application instead of reaching to DB every time
             Call the coroutine scope to insert/update/delete the values*/

    object GlobalVariable {
        var braveMode: Int = -1
        var appList: MutableMap<String, AppInfo> = HashMap()
        var backgroundAllowedUID: MutableMap<Int, Boolean> = HashMap()
        var blockedUID: MutableMap<Int, Boolean> = HashMap()
        var appMode: AppMode? = null

        var lifeTimeQueries: Int = -1

        var lifeTimeQ: MutableLiveData<Int> = MutableLiveData()
        var braveModeToggler: MutableLiveData<Int> = MutableLiveData()
        var connectedDNS: MutableLiveData<String> = MutableLiveData()
        var cryptRelayToRemove: String = ""

        var appStartTime: Long = System.currentTimeMillis()
        var firewallRules: HashMultimap<Int, String> = HashMultimap.create()
        var DEBUG = true
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
    }

    companion object {
        var isLoadingComplete: Boolean = false
        var enqueue: Long = 0
    }

    //TODO : Remove the unwanted data and the assignments happening
    //TODO : Create methods and segregate the data.
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Utilities.getCurrentTheme(isDarkThemeOn()))
        super.onCreate(savedInstanceState)
        context = this

        if (persistentState.firstTimeLaunch) {
            GlobalVariable.connectedDNS.postValue(Constants.RETHINK_DNS)
            launchOnBoardingActivity()
            updateNewVersion()
            updateInstallSource()
        } else {
            showNewFeaturesDialog()
        }

        internetManagerFragment = InternetManagerFragment()
        homeScreenFragment = HomeScreenFragment()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                              homeScreenFragment,
                                                              homeScreenFragment.javaClass.simpleName).commit()
        }
        b.navView.setOnNavigationItemSelectedListener(onNavigationItemSelectedListener)
        appMode = get() // Todo don't hold global objects across the app.


        val firewallRules = FirewallRules.getInstance()
        firewallRules.loadFirewallRules(blockedConnectionsRepository)

        refreshDatabase.deleteOlderDataFromNetworkLogs()

        if (!persistentState.insertionCompleted) {
            refreshDatabase.insertDefaultDNSList()
            refreshDatabase.insertDefaultDNSCryptList()
            refreshDatabase.insertDefaultDNSCryptRelayList()
            refreshDatabase.insertDefaultDNSProxy()
            refreshDatabase.updateCategoryInDB()
            persistentState.insertionCompleted = true
        }

        persistentState.isScreenOff = false
        lifeTimeQ.postValue(persistentState.getLifetimeQueries())
        initUpdateCheck()

        backgroundAccessibilityCheck()

    }

    private fun backgroundAccessibilityCheck() {
        if (Utilities.isAccessibilityServiceEnabledViaSettingsSecure(this,
                                                                     BackgroundAccessibilityService::class.java)) {
            if (!Utilities.isAccessibilityServiceEnabled(this,
                                                         BackgroundAccessibilityService::class.java) && persistentState.isBackgroundEnabled) {
                persistentState.isBackgroundEnabled = false
                persistentState.isAccessibilityCrashDetected = true
            }
        }
    }

    private fun updateNewVersion() {
        try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = pInfo.versionCode
            persistentState.appVersion = version
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG_UI, "package not found, cannot fetch version code: ${e.message}", e)
        }
    }

    private fun launchOnBoardingActivity() {
        startActivity(Intent(this, WelcomeActivity::class.java))
    }

    private fun updateInstallSource() {
        when (BuildConfig.FLAVOR) {
            Constants.FLAVOR_PLAY -> {
                persistentState.downloadSource = DOWNLOAD_SOURCE_PLAY_STORE
            }
            Constants.FLAVOR_FDROID -> {
                persistentState.downloadSource = Constants.DOWNLOAD_SOURCE_FDROID
            }
            else -> {
                persistentState.downloadSource = Constants.DOWNLOAD_SOURCE_WEBSITE
            }
        }
    }


    private fun showNewFeaturesDialog() {
        if (checkToShowNewFeatures()) {
            updateInstallSource()
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
        }
    }

    private fun checkToShowNewFeatures(): Boolean {
        val versionStored = persistentState.appVersion
        var version = 0
        try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            version = pInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG_UI, "package not found, cannot fetch version code: ${e.message}", e)
        }
        return if (version != versionStored) {
            persistentState.appVersion = version
            true
        } else {
            false
        }
    }

    private fun initUpdateCheck() {
        val time = persistentState.lastAppUpdateCheck
        val currentTime = System.currentTimeMillis()
        val diff = currentTime - time
        val numOfDays = (diff / (1000 * 60 * 60 * 24)).toInt()
        val calendar: Calendar = Calendar.getInstance()
        val day: Int = calendar.get(Calendar.DAY_OF_WEEK)
        if ((day == Calendar.FRIDAY || day == Calendar.SATURDAY) && persistentState.checkForAppUpdate) {
            Log.i(LOG_TAG_UI, "App update check initiated, number of days: $numOfDays")
            if (numOfDays > 1) {
                checkForUpdate()
                checkForBlockListUpdate()
            }
        }
    }

    fun checkForUpdate(userInitiation: Boolean = false) {
        if (BuildConfig.FLAVOR == Constants.FLAVOR_PLAY) {
            appUpdateManager.checkForAppUpdate(userInitiation, this,
                                               installStateUpdatedListener) // Might be play updater or web updater
        } else if (BuildConfig.FLAVOR == Constants.FLAVOR_WEBSITE) {
            get<NonStoreAppUpdater>().checkForAppUpdate(userInitiation, this,
                                                        installStateUpdatedListener) // Always web updater
        }
    }

    private fun checkForBlockListUpdate() {
        val connectedDOH = doHEndpointRepository.getConnectedDoH()
        var isRethinkPlusConnected = false
        if (connectedDOH != null) {
            if (connectedDOH.dohName == Constants.RETHINK_DNS_PLUS) {
                isRethinkPlusConnected = true
            }
        }
        if (persistentState.localBlocklistEnabled || isRethinkPlusConnected) {
            val blockListTimeStamp = persistentState.localBlocklistDownloadTime
            val appVersionCode = persistentState.appVersion
            val url = "${Constants.REFRESH_BLOCKLIST_URL}$blockListTimeStamp&${Constants.APPEND_VCODE}$appVersionCode"
            serverCheckForBlocklistUpdate(url)
        }
    }

    private val installStateUpdatedListener = object : AppUpdater.InstallStateListener {
        override fun onStateUpdate(state: AppUpdater.InstallState) {
            Log.i(LOG_TAG_UI, "InstallStateUpdatedListener: state: " + state.status)
            when (state.status) {
                AppUpdater.InstallStatus.DOWNLOADED -> {
                    //CHECK THIS if AppUpdateType.FLEXIBLE, otherwise you can skip
                    popupSnackBarForCompleteUpdate()
                }
                AppUpdater.InstallStatus.INSTALLED -> {
                    appUpdateManager.unregisterListener(this)
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

    private fun popupSnackBarForCompleteUpdate() {
        val snackbar = Snackbar.make(b.container, "New Version is downloaded.",
                                     Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction("RESTART") { appUpdateManager.completeUpdate() }
        snackbar.setActionTextColor(ContextCompat.getColor(this, R.color.textColorMain))
        snackbar.show()
    }

    private fun serverCheckForBlocklistUpdate(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG_DOWNLOAD, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
            }

            override fun onResponse(call: Call, response: Response) {
                val stringResponse = response.body!!.string()
                try {
                    val jsonObject = JSONObject(stringResponse)
                    val responseVersion = jsonObject.getInt(Constants.JSON_VERSION)
                    val shouldUpdate = jsonObject.getBoolean(Constants.JSON_UPDATE)
                    timestamp = jsonObject.getLong(Constants.JSON_LATEST)
                    persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                    Log.i(LOG_TAG_DOWNLOAD,
                          "Server response for the new version download is $shouldUpdate, response version number- $responseVersion, timestamp- $timestamp")
                    response.body!!.close()
                    client.connectionPool.evictAll()

                    if (responseVersion != RESPONSE_VERSION || !shouldUpdate) return

                    if (persistentState.downloadSource != DOWNLOAD_SOURCE_PLAY_STORE) {
                        popupSnackBarForBlocklistUpdate()
                    } else {
                        registerReceiverForDownloadManager(context)
                        handleDownloadFiles()
                    }
                } catch (e: JSONException) {
                    Log.w(LOG_TAG_DOWNLOAD,
                          "HomeScreenActivity- Exception while fetching blocklist update", e)
                }
            }
        })
    }

    private fun registerReceiverForDownloadManager(context: Context) {
        (context as HomeScreenActivity).runOnUiThread {
            context.registerReceiver(onComplete,
                                     IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

    }

    private var onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {
            try {
                val action = intent.action
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Download status: $action")
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                    val query = DownloadManager.Query()
                    query.setFilterById(enqueue)
                    val c: Cursor = downloadManager.query(query)
                    if (c.moveToFirst()) {
                        val status = checkStatus(c)
                        if (status == Constants.DOWNLOAD_STATUS_SUCCESSFUL) {
                            val from = File(
                                getExternalFilePath(ctxt, true) + Constants.FILE_TAG_NAME)
                            val to = File(context.filesDir.canonicalPath + Constants.FILE_TAG_NAME)
                            from.copyTo(to, true)
                            persistentState.remoteBraveDNSDownloaded = true
                            persistentState.remoteBlocklistDownloadTime = timestamp
                        } else {
                            Log.e(LOG_TAG_DOWNLOAD, "Error downloading filetag.json file: $status")
                        }
                    }
                }
            } catch (e: FileAlreadyExistsException) {
                Log.e(LOG_TAG_DOWNLOAD, "FileAlreadyExistsException: ${e.message}", e)
            } catch (e: IOException) {
                Log.e(LOG_TAG_DOWNLOAD, "Error downloading filetag.json file: ${e.message}", e)
            }
        }
    }

    private fun getExternalFilePath(context: Context, isAbsolutePathNeeded: Boolean): String {
        return if (isAbsolutePathNeeded) {
            context.getExternalFilesDir(
                null).toString() + Constants.DOWNLOAD_PATH + persistentState.localBlocklistDownloadTime
        } else {
            Constants.DOWNLOAD_PATH + persistentState.localBlocklistDownloadTime
        }
    }

    private fun showDownloadDialog(source: AppUpdater.InstallSource, title: String,
                                   message: String) {
        val builder = AlertDialog.Builder(context)
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
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
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
            //Register or UnRegister your broadcast receiver here
            appUpdateManager.unregisterListener(installStateUpdatedListener)
            context.unregisterReceiver(onComplete)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_DOWNLOAD, "Unregister receiver exception")
        }

    }

    private fun popupSnackBarForBlocklistUpdate() {
        (context as HomeScreenActivity).runOnUiThread {
            val parentLayout = findViewById<View>(android.R.id.content)
            Snackbar.make(parentLayout, getString(R.string.hs_snack_bar_blocklist_message),
                          Snackbar.LENGTH_LONG).setAction("Update") {
                registerReceiverForDownloadManager(this)
                handleDownloadFiles()
            }.setActionTextColor(ContextCompat.getColor(context, R.color.accent_bad)).show()
        }
    }

    private fun handleDownloadFiles() {
        val timestamp = persistentState.localBlocklistDownloadTime
        val url = Constants.JSON_DOWNLOAD_BLOCKLIST_LINK + File.separator + timestamp
        (context as HomeScreenActivity).runOnUiThread {
            downloadManager = this.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadBlockListFiles(url, Constants.FILE_TAG_NAME, this)
        }
    }

    private fun downloadBlockListFiles(url: String, fileName: String, context: Context) {
        try {
            val uri: Uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
            request.setTitle(getString(R.string.hs_download_blocklist_heading))
            request.setDescription(getString(R.string.hs_download_blocklist_desc, fileName))
            val path = getExternalFilePath(this, false)
            request.setDestinationInExternalFilesDir(context, path, fileName)
            Log.i(LOG_TAG_DOWNLOAD, "Path - $path, filename- $fileName, uri - $uri")
            enqueue = downloadManager.enqueue(request)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG_DOWNLOAD, "Download unsuccessful - ${e.message}", e)
        } catch (e: IllegalStateException) {
            Log.w(LOG_TAG_DOWNLOAD, "Download unsuccessful - ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDatabase.refreshAppInfoDatabase()
    }


    private val onNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_internet_manager -> {
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
