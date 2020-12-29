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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_OTHERS
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.HttpRequestHelper.Companion.checkStatus
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.common.collect.HashMultimap
import okhttp3.*
import org.json.JSONObject
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.collections.HashMap


class HomeScreenActivity : AppCompatActivity() {

    lateinit var internetManagerFragment: InternetManagerFragment
    lateinit var settingsFragment: SettingsFragment
    lateinit var homeScreenFragment: HomeScreenFragment
    lateinit var aboutFragment: AboutFragment
    lateinit var context: Context
    private val refreshDatabase by inject<RefreshDatabase>()
    lateinit var appUpdateManager: AppUpdateManager
    lateinit var downloadManager : DownloadManager
    private var timeStamp  = 0L
    //lateinit var appSample : AppInfo

    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()
    private val persistentState by inject<PersistentState>()

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
        var filesDownloaded: Int = 0
        var lifeTimeQueries: Int = -1
        var lifeTimeQ: MutableLiveData<Int> = MutableLiveData()
        var medianP90: Long = -1
        var median50: MutableLiveData<Long> = MutableLiveData()
        var blockedCount: MutableLiveData<Int> = MutableLiveData()
        var dnsType : MutableLiveData<Int> = MutableLiveData()
        var braveModeToggler : MutableLiveData<Int> = MutableLiveData()
        //var cryptModeInProgress : Int = 0
        var cryptRelayToRemove : String = ""

        //var appsBlocked : MutableLiveData<Int> = MutableLiveData()
        //var excludedAppsFromVPN : MutableMap<String,Boolean> = HashMap()
        var numUniversalBlock: MutableLiveData<Int> = MutableLiveData()
        var appStartTime: Long = System.currentTimeMillis()
        var isBackgroundEnabled: Boolean = false
        var firewallRules: HashMultimap<Int, String> = HashMultimap.create()
        var DEBUG = false

        //Screen off - whether the screen preference is set 0-off, 1- on. -1 not initialized
        var isScreenLockedSetting : Int = -1
        //Screen off state - set - 0 if screen is off, 1 - screen is on, -1 not initialized.
        var isScreenLocked : Int = -1
        var localDownloadComplete : MutableLiveData<Int> = MutableLiveData()
        var isSearchEnabled : Boolean = true

        var swipeDetector : MutableLiveData<Boolean> = MutableLiveData()
    }

    companion object {
        var isLoadingComplete: Boolean = false
        const val DAYS_TO_MAINTAIN_NETWORK_LOG = 2
        var enqueue: Long = 0
    }


    //TODO : Remove the unwanted data and the assignments happening
    //TODO : Create methods and segregate the data.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_home_screen)

        if (persistentState.firstTimeLaunch) {
            launchOnBoardingActivity()
        }

        context = this
        //dbHandler = DatabaseHandler(this)

        internetManagerFragment = InternetManagerFragment()
        homeScreenFragment = HomeScreenFragment()

        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        appUpdateManager = AppUpdateManagerFactory.create(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, homeScreenFragment,
                homeScreenFragment.javaClass.simpleName).commit()
        }
        navView.setOnNavigationItemSelectedListener(onNavigationItemSelectedListener)
        appMode = get<AppMode>() // Todo don't hold global objects across the app.


        val firewallRules = FirewallRules.getInstance()
        firewallRules.loadFirewallRules(blockedConnectionsRepository)

        refreshDatabase.deleteOlderDataFromNetworkLogs()
        if (!persistentState.insertionCompleted) {
            refreshDatabase.insertDefaultDNSList()
            refreshDatabase.insertDefaultDNSCryptList()
            refreshDatabase.insertDefaultDNSCryptRelayList()
            refreshDatabase.updateCategoryInDB()
            persistentState.insertionCompleted = true
        }
        GlobalVariable.isBackgroundEnabled = persistentState.backgroundEnabled
        persistentState.setScreenLockData(false)
        updateInstallSource()
        initUpdateCheck()
        showNewFeaturesDialog()
    }

    private fun launchOnBoardingActivity() {
        startActivity(Intent(this, WelcomeActivity::class.java))
    }

    private fun updateInstallSource() {
        if (persistentState.downloadSource == 0) {
            val packageManager = packageManager
            try {
                val applicationInfo: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
                if (DEBUG) Log.d(LOG_TAG, "Install location: ${packageManager.getInstallerPackageName(applicationInfo.packageName)}")
                if ("com.android.vending" == packageManager.getInstallerPackageName(applicationInfo.packageName)) {
                    // App was installed by Play Store
                    persistentState.downloadSource = Constants.DOWNLOAD_SOURCE_PLAY_STORE
                } else {
                    // App was installed from somewhere else
                    persistentState.downloadSource = Constants.DOWNLOAD_SOURCE_OTHERS
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(LOG_TAG, "Exception while fetching the app download source: ${e.message}", e)
            }
        }
    }


    private fun showNewFeaturesDialog() {
        if(checkToShowNewFeatures()){
            val inflater: LayoutInflater = LayoutInflater.from(this)
            val view: View = inflater.inflate(R.layout.dialog_whatsnew, null)
            val builder = AlertDialog.Builder(this)
            builder.setView(view).setTitle(getString(R.string.whats_dialog_title))

            builder.setPositiveButton("Let\'s Go") { dialogInterface, which ->
                dialogInterface.dismiss()
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
            Log.e(LOG_TAG, "Error while fetching version code: ${e.message}", e)
        }
        return if(version != versionStored){
            persistentState.appVersion = version
            true
        }else{
            false
        }
    }

    private fun initUpdateCheck(){
        val time = persistentState.lastAppUpdateCheck
        val currentTime = System.currentTimeMillis()
        val diff = currentTime - time
        val numOfDays = (diff / (1000 * 60 * 60 * 24)).toInt()
        val calendar: Calendar = Calendar.getInstance()
        val day: Int = calendar.get(Calendar.DAY_OF_WEEK)
        if(day == Calendar.FRIDAY || day == Calendar.SATURDAY) {
            if (numOfDays > 0) {
                Log.i(LOG_TAG, "App update check initiated, number of days: $numOfDays")
                checkForAppUpdate(false)
                checkForBlockListUpdate()
            } else {
                Log.i(LOG_TAG, "App update check not initiated")
            }
        }
    }

    private fun checkForBlockListUpdate() {
        val connectedDOH = doHEndpointRepository.getConnectedDoH()
        var isRethinkPlusConnected = false
        if(connectedDOH != null) {
            if (connectedDOH.dohName == Constants.RETHINK_DNS_PLUS) {
                isRethinkPlusConnected = true
            }
        }
        if (persistentState.localBlocklistEnabled || isRethinkPlusConnected) {
            val blockListTimeStamp = persistentState.localBlockListDownloadTime
            val appVersionCode = persistentState.appVersion
            val url = "${Constants.REFRESH_BLOCKLIST_URL}$blockListTimeStamp&${Constants.APPEND_VCODE}$appVersionCode"
            serverCheckForBlocklistUpdate(url)
        }
    }

    fun checkForAppUpdate(isUserInitiated : Boolean) {
        var version = 0
        try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            version = pInfo.versionCode
            persistentState.appVersion = version
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG, "Error while fetching version code: ${e.message}", e)
        }

        if (persistentState.downloadSource == Constants.DOWNLOAD_SOURCE_PLAY_STORE) {
            appUpdateManager.registerListener(installStateUpdatedListener)

            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->

                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.FLEXIBLE, this, version)
                    } catch (e: IntentSender.SendIntentException) {
                        appUpdateManager.unregisterListener(installStateUpdatedListener)
                        Log.e(LOG_TAG, "SendIntentException: ${e.message} ", e)
                    }
                } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.IMMEDIATE, this, version)
                    } catch (e: IntentSender.SendIntentException) {
                        appUpdateManager.unregisterListener(installStateUpdatedListener)
                        Log.e(LOG_TAG, "SendIntentException: ${e.message} ", e)
                    }
                }  else {
                    appUpdateManager.unregisterListener(installStateUpdatedListener)
                    Log.e(LOG_TAG, "checkForAppUpdateAvailability: something else")
                    if(isUserInitiated) {
                        showDownloadDialog(false, getString(R.string.download_update_dialog_failure_title), getString(R.string.download_update_dialog_failure_message))
                    }
                }
            }
        }else{
            checkForAppDownload(version, isUserInitiated)
        }
    }

    private val installStateUpdatedListener: InstallStateUpdatedListener = object : InstallStateUpdatedListener {
        override fun onStateUpdate(state: InstallState) {
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                //CHECK THIS if AppUpdateType.FLEXIBLE, otherwise you can skip
                popupSnackBarForCompleteUpdate()
                //showDownloadDialog(true, getString(R.string.download_update_dialog_title), getString(R.string.download_update_dialog_message))
            } else if (state.installStatus() == InstallStatus.INSTALLED) {
                Log.i(LOG_TAG, "InstallStateUpdatedListener: state: " + state.installStatus())
                appUpdateManager.unregisterListener(this)
            } else {
                appUpdateManager.unregisterListener(this)
                Log.i(LOG_TAG, "InstallStateUpdatedListener: state: " + state.installStatus())
               // checkForDownload()
            }
        }
    }

    private fun popupSnackBarForCompleteUpdate() {
        val snackbar = Snackbar.make(this.findViewById(R.id.container),
                "New Version is downloaded.",
                Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction("RESTART") { appUpdateManager.completeUpdate() }
        snackbar.setActionTextColor(ContextCompat.getColor(this, R.color.textColorMain))
        snackbar.show()
    }

    private fun checkForAppDownload(version: Int, isUserInitiated : Boolean) {
        Log.i(LOG_TAG, "App update check initiated")
        val url = Constants.APP_DOWNLOAD_AVAILABLE_CHECK + version
        serverCheckForAppUpdate(url, isUserInitiated)
    }

    /**
     * TODO - Remove the function from the activity and place in the
     * HttpRequestHelper file which will return the boolean to check if there is
     * update available.
     */
    private fun serverCheckForAppUpdate(url: String, isUserInitiatedUpdateCheck : Boolean) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(LOG_TAG, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                (context as HomeScreenActivity).runOnUiThread {
                    if (isUserInitiatedUpdateCheck) {
                        showDownloadDialog(false, getString(R.string.download_update_dialog_failure_title), getString(R.string.download_update_dialog_failure_message))
                    }
                }
                call.cancel()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val stringResponse = response.body!!.string()
                    //creating json object
                    val jsonObject = JSONObject(stringResponse)
                    val responseVersion = jsonObject.getInt("version")
                    val updateValue = jsonObject.getBoolean("update")
                    val latestVersion = jsonObject.getInt("latest")
                    persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                    Log.i(LOG_TAG, "Server response for the new version download is true, version number-  $latestVersion")
                    if (responseVersion == 1) {
                        if (updateValue) {
                            (context as HomeScreenActivity).runOnUiThread {
                                showDownloadDialog(false, getString(R.string.download_update_dialog_title), getString(R.string.download_update_dialog_message))
                            }
                        } else {
                            (context as HomeScreenActivity).runOnUiThread {
                                if (isUserInitiatedUpdateCheck) {
                                    showDownloadDialog(false, getString(R.string.download_update_dialog_message_ok_title), getString(R.string.download_update_dialog_message_ok))
                                }
                            }
                        }
                    }
                    response.close()
                    client.connectionPool.evictAll()
                } catch (e: Exception) {
                    if (isUserInitiatedUpdateCheck) {
                        showDownloadDialog(false, getString(R.string.download_update_dialog_failure_title), getString(R.string.download_update_dialog_failure_message))
                    }
                }
            }
        })
    }

    private fun serverCheckForBlocklistUpdate(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(LOG_TAG, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
            }

            override fun onResponse(call: Call, response: Response) {
                val stringResponse = response.body!!.string()
                //creating json object
                try {
                    val jsonObject = JSONObject(stringResponse)
                    val responseVersion = jsonObject.getInt("version")
                    val updateValue = jsonObject.getBoolean("update")
                    timeStamp = jsonObject.getLong("latest")
                    persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                    Log.i(LOG_TAG, "Server response for the new version download is $updateValue, response version number- $responseVersion, timestamp- $timeStamp")
                    if (responseVersion == 1) {
                        //PersistentState.setLocalBlockListDownloadTime(context, timeStamp)
                        if (updateValue) {
                            if (persistentState.downloadSource == DOWNLOAD_SOURCE_OTHERS) {
                                (context as HomeScreenActivity).runOnUiThread {
                                    popupSnackBarForBlocklistUpdate()
                                }
                            } else {
                                (context as HomeScreenActivity).runOnUiThread {
                                    registerReceiverForDownloadManager(context)
                                    downloadManager = HttpRequestHelper.downloadBlockListFiles(context)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG,"HomeScreenActivity- Exception while fetching blocklist update",e)
                }
                response.body!!.close()
                client.connectionPool.evictAll()
            }
        })
    }

    private fun registerReceiverForDownloadManager(context: Context) {
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }


    private var onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {

            if (DEBUG) Log.d(LOG_TAG, "Intent on receive ")
            try {
                val action = intent.action
                if (DEBUG) Log.d(LOG_TAG, "Download status: $action")
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                    if (DEBUG) Log.d(LOG_TAG, "Download status: $action")
                    //val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)
                    val query = DownloadManager.Query()
                    query.setFilterById(enqueue)
                    val c: Cursor = downloadManager.query(query)
                    if (c.moveToFirst()) {
                        //val columnIndex: Int = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = checkStatus(c)
                        if (DEBUG) Log.d(LOG_TAG, "Download status: $status,${GlobalVariable.filesDownloaded}")
                        if (status == "STATUS_SUCCESSFUL") {
                            val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_TAG_NAME)
                            val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_TAG_NAME)
                            from.copyTo(to, true)
                            /*val destDir = File(ctxt.filesDir.canonicalPath)
                            Utilities.moveTo(from, to, destDir)*/
                            persistentState.remoteBraveDNSDownloaded = true
                            persistentState.remoteBlockListDownloadTime = timeStamp
                        }else{
                            Log.e(LOG_TAG, "Error downloading filetag.json file: $status")
                        }
                    }
                }
            }catch (e: Exception) {
                Log.e(LOG_TAG, "Error downloading filetag.json file: ${e.message}", e)
            }
        }
    }


    private fun showDownloadDialog(isPlayStore: Boolean, title: String, message: String) {
        val builder = AlertDialog.Builder(context)
        //set title for alert dialog
        builder.setTitle(title)
        //set message for alert dialog
        builder.setMessage(message)
        builder.setCancelable(true)
        //performing positive action
        if(message == getString(R.string.download_update_dialog_message_ok) || message == getString(R.string.download_update_dialog_failure_message)){
            builder.setPositiveButton("ok") { dialogInterface, which ->
                dialogInterface.dismiss()
            }
        }else {
            builder.setPositiveButton("Visit website") { dialogInterface, which ->
                if (isPlayStore) {
                    appUpdateManager.completeUpdate()
                } else {
                    initiateDownload()
                }
            }
            builder.setNegativeButton("Remind me later") { dialogInterface, which ->
                dialogInterface.dismiss()
            }
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
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
            Log.e(LOG_TAG, "onActivityResult: app download failed")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            //Register or UnRegister your broadcast receiver here
            appUpdateManager.unregisterListener(installStateUpdatedListener)
            context.unregisterReceiver(onComplete)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG, "Unregister receiver exception")
        }

    }

    private fun popupSnackBarForBlocklistUpdate() {
        val parentLayout = findViewById<View>(android.R.id.content)
        Snackbar.make(parentLayout, "Update custom blocklist files", Snackbar.LENGTH_LONG)
            .setAction("Update") {
                registerReceiverForDownloadManager(this)
                handleDownloadFiles()
            }
            .setActionTextColor(resources.getColor(R.color.accent_bad))
            .show()
    }

    private fun handleDownloadFiles() {
        downloadManager = this.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val timeStamp = persistentState.localBlockListDownloadTime
        val url = Constants.JSON_DOWNLOAD_BLOCKLIST_LINK + "/" + timeStamp
        downloadBlockListFiles(url, Constants.FILE_TAG_NAME, this)
    }

    private fun downloadBlockListFiles(url: String, fileName: String, context: Context) {
        try {
            if (DEBUG) Log.d(LOG_TAG, "downloadBlockListFiles - url: $url")
            val uri: Uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
            request.setTitle("RethinkDNS Blocklists")
            request.setDescription("$fileName download in progress..")
            request.setDestinationInExternalFilesDir(context, Constants.DOWNLOAD_PATH, fileName)
            Log.d(LOG_TAG, "Path - ${context.filesDir.canonicalPath}${Constants.DOWNLOAD_PATH}${fileName}")
            enqueue = downloadManager.enqueue(request)
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG, "Download unsuccessful - ${e.message}", e)
        }
    }



    override fun onResume() {
        super.onResume()
        refreshDatabase.refreshAppInfoDatabase()
    }


    private val onNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_internet_manager -> {
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, homeScreenFragment, homeScreenFragment.javaClass.simpleName).commit()
                return@OnNavigationItemSelectedListener true
            }

            R.id.navigation_settings -> {
                settingsFragment = SettingsFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, settingsFragment, settingsFragment.javaClass.simpleName).commit()
                return@OnNavigationItemSelectedListener true
            }

            R.id.navigation_about -> {
                aboutFragment = AboutFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, aboutFragment, aboutFragment.javaClass.simpleName).commit()
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }


}
