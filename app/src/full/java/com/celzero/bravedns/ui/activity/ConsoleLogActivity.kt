/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import Logger.LOG_TAG_BUG_REPORT
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConsoleLogAdapter
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.databinding.ActivityConsoleLogBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO_MR1
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.ConsoleLogViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class ConsoleLogActivity : AppCompatActivity(R.layout.activity_console_log) {

    private val b by viewBinding(ActivityConsoleLogBinding::bind)
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val persistentState by inject<PersistentState>()

    private val consoleLogViewModel by inject<ConsoleLogViewModel>()
    private val consoleLogRepository by inject<ConsoleLogRepository>()
    private val workScheduler by inject<WorkScheduler>()

    companion object {
        private const val FOLDER_NAME = "Rethink"
        private const val SCHEME_PACKAGE = "package"
        private const val FILE_NAME = "rethink_app_logs_"
        private const val FILE_EXTENSION = ".zip"
        private const val STORAGE_PERMISSION_CODE = 231 // request code for storage permission
    }

    enum class SaveType {
        SAVE,
        SHARE;

        fun isSave(): Boolean {
            return this == SAVE
        }

        fun isShare(): Boolean {
            return this == SHARE
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        initView()
        setupClickListener()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (persistentState.consoleLogEnabled) {
                        showStopDialog()
                    } else {
                        finish()
                    }
                    return
                }
            }
        )
    }

    private fun initView() {
        setAdapter()
        // update the text view with the time since logs are available
        io {
            val sinceTime = consoleLogViewModel.sinceTime()
            if (sinceTime == 0L) return@io

            val since = Utilities.convertLongToTime(sinceTime, Constants.TIME_FORMAT_3)
            uiCtx {
                val desc = getString(R.string.console_log_desc)
                val sinceTxt = getString(R.string.logs_card_duration, since)
                val descWithTime = getString(R.string.two_argument_space, desc, sinceTxt)
                b.consoleLogInfoText.text = descWithTime
            }
        }
        if (persistentState.consoleLogEnabled) {
            b.consoleLogStartStop.text = getString(R.string.hsf_stop_btn_state)
        } else {
            b.consoleLogStartStop.text = getString(R.string.hsf_start_btn_state)
            showStartDialog() // show dialog to user to start logging
        }
    }

    var recyclerAdapter: ConsoleLogAdapter? = null

    private fun setAdapter() {
        b.consoleLogList.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this@ConsoleLogActivity)
        b.consoleLogList.layoutManager = layoutManager
        recyclerAdapter = ConsoleLogAdapter(this)
        b.consoleLogList.adapter = recyclerAdapter
        observeLog()

        /*lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                consoleLogViewModel.logs.collectLatest { pagingData ->
                    recyclerAdapter.submitData(pagingData)
                }
            }
        }*/
    }

    private fun observeLog() {
        consoleLogViewModel.logs.observe(this) { l ->
            lifecycleScope.launch {
                recyclerAdapter?.submitData(l)
            }
        }
    }

    private fun unobserveLog1() {
        consoleLogViewModel.logs.removeObservers(this)
    }

    private fun setupClickListener() {
        b.consoleLogSave.setOnClickListener {
            val filePath = createFile(SaveType.SAVE)
            if (filePath == null) {
                showFileCreationErrorToast()
                return@setOnClickListener
            }
            handleSaveOrShareLogs(filePath, SaveType.SAVE)
        }

        b.consoleLogShare.setOnClickListener {
            val filePath = createFile(SaveType.SHARE)
            if (filePath == null) {
                showFileCreationErrorToast()
                return@setOnClickListener
            }
            handleSaveOrShareLogs(filePath, SaveType.SHARE)
        }
        b.fabShareLog.setOnClickListener {
            val filePath = createFile(SaveType.SHARE)
            if (filePath == null) {
                showFileCreationErrorToast()
                return@setOnClickListener
            }
            handleSaveOrShareLogs(filePath, SaveType.SHARE)
        }
        b.consoleLogStartStop.setOnClickListener {
            persistentState.consoleLogEnabled = !persistentState.consoleLogEnabled
            if (persistentState.consoleLogEnabled) {
                b.consoleLogStartStop.text = getString(R.string.hsf_stop_btn_state)
                consoleLogRepository.consoleLogStartTimestamp = System.currentTimeMillis()
            } else {
                b.consoleLogStartStop.text = getString(R.string.hsf_start_btn_state)
                consoleLogRepository.consoleLogStartTimestamp = 0L
            }
        }

        b.consoleStatInfo.setOnClickListener { showStatsDialog() }

        b.consoleLogDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.console_log_delete_title))
                .setMessage(getString(R.string.console_log_delete_desc))
                .setPositiveButton(getString(R.string.lbl_delete)) { _, _ ->
                    io {
                        consoleLogRepository.deleteAllLogs()
                        uiCtx {
                            showToastUiCentered(
                                this,
                                getString(R.string.console_log_delete_toast),
                                Toast.LENGTH_SHORT
                            )
                            finish()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showStartDialog() {
        //unobserveLog()
        val handler = Handler(Looper.getMainLooper())
        val builder = MaterialAlertDialogBuilder(this)
        val title = getString(R.string.console_log_title)
        builder.setTitle(title)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.hsf_start_btn_state)) { dialogInterface, _ ->
            handler.post {
                persistentState.consoleLogEnabled = true
                consoleLogRepository.consoleLogStartTimestamp = System.currentTimeMillis()
                b.consoleLogStartStop.text = getString(R.string.hsf_stop_btn_state)
                showToastUiCentered(
                    this,
                    getString(R.string.config_add_success_toast),
                    Toast.LENGTH_SHORT
                )
                observeLog()
            }
            dialogInterface.dismiss()
        }

        builder.setNeutralButton(getString(R.string.lbl_cancel)) { dialogInterface, _ ->
            handler.post {
                b.consoleLogStartStop.text = getString(R.string.hsf_start_btn_state)
                observeLog()
            }
            dialogInterface.dismiss()
        }
        builder.setOnCancelListener {
            handler.post {
                observeLog()
            }
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun showStopDialog() {
       // unobserveLog()
        val handler = Handler(Looper.getMainLooper())
        val builder = MaterialAlertDialogBuilder(this)
        val title = getString(R.string.console_log_title)
        builder.setTitle(title)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.hsf_stop_btn_state)) { dialogInterface, _ ->
            handler.post {
                persistentState.consoleLogEnabled = false
                consoleLogRepository.consoleLogStartTimestamp = 0L
                b.consoleLogStartStop.text = getString(R.string.hsf_start_btn_state)
                showToastUiCentered(
                    this,
                    getString(R.string.config_add_success_toast),
                    Toast.LENGTH_SHORT
                )
            }
            dialogInterface.dismiss()
            handler.post { finish() }
        }

        builder.setNeutralButton(getString(R.string.lbl_cancel)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            handler.post { finish() }
        }
        builder.setOnCancelListener {
            observeLog()
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun showStatsDialog() {
        //unobserveLog()
        io {
            val stat = VpnController.getNetStat()
            val formatedStat = UIUtils.formatNetStat(stat)
            uiCtx {
                val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
                val builder = MaterialAlertDialogBuilder(this).setView(dialogBinding.root)
                val lp = WindowManager.LayoutParams()
                val dialog = builder.create()
                dialog.show()
                lp.copyFrom(dialog.window?.attributes)
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT

                dialog.setCancelable(true)
                dialog.window?.attributes = lp

                val heading = dialogBinding.infoRulesDialogRulesTitle
                val okBtn = dialogBinding.infoRulesDialogCancelImg
                val descText = dialogBinding.infoRulesDialogRulesDesc
                dialogBinding.infoRulesDialogRulesIcon.visibility = View.GONE

                heading.text = "Network Stats"
                heading.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, R.drawable.ic_info_white),
                    null,
                    null,
                    null
                )

                descText.movementMethod = LinkMovementMethod.getInstance()
                descText.text = formatedStat

                okBtn.setOnClickListener {
                    dialog.dismiss()
                    observeLog()
                }

                dialog.setOnCancelListener {
                    observeLog()
                }

                dialog.show()
            }
        }
    }

    private fun handleSaveOrShareLogs(filePath: String, type: SaveType) {
        if (WorkScheduler.isWorkRunning(this, WorkScheduler.CONSOLE_LOG_SAVE_JOB_TAG)) return

        workScheduler.scheduleConsoleLogSaveJob(filePath)
        showLogGenerationProgressUi()

        val workManager = WorkManager.getInstance(this.applicationContext)
        workManager.getWorkInfosByTagLiveData(WorkScheduler.CONSOLE_LOG_SAVE_JOB_TAG).observe(
            this
        ) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                Logger.LOG_TAG_SCHEDULER,
                "WorkManager state: ${workInfo.state} for ${WorkScheduler.CONSOLE_LOG_SAVE_JOB_TAG}"
            )
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                onSuccess()
                if (type.isShare()) {
                    shareZipFileViaEmail(filePath)
                }
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                WorkInfo.State.FAILED == workInfo.state
            ) {
                onFailure()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(WorkScheduler.CONSOLE_LOG_SAVE_JOB_TAG)
            } else { // state == blocked, queued, or running
                // no-op
            }
        }
    }

    private fun onSuccess() {
        // show success message
        Logger.i(LOG_TAG_BUG_REPORT, "Logs saved successfully")
        b.consoleLogProgressBar.visibility = View.GONE
        Toast.makeText(this, getString(R.string.config_add_success_toast), Toast.LENGTH_LONG).show()
    }

    private fun onFailure() {
        // show failure message
        Logger.i(LOG_TAG_BUG_REPORT, "Logs save failed")
        b.consoleLogProgressBar.visibility = View.GONE
        Toast.makeText(
            this,
            getString(R.string.download_update_dialog_failure_title),
            Toast.LENGTH_LONG
        )
            .show()
    }

    private fun showLogGenerationProgressUi() {
        // show progress dialog or progress bar
        Logger.i(LOG_TAG_BUG_REPORT, "Logs generation in progress")
        b.consoleLogProgressBar.visibility = View.VISIBLE
    }

    private fun createFile(type: SaveType): String? {
        if (type.isShare()) {
            // create file in localdir and share, no need to check for permissions
            return makeConsoleLogFile(type)
        }

        // check for storage permissions
        if (!checkStoragePermissions()) {
            // request for storage permissions
            Logger.i(LOG_TAG_BUG_REPORT, "requesting for storage permissions")
            requestForStoragePermissions()
            return null
        }

        Logger.i(LOG_TAG_BUG_REPORT, "storage permission granted, creating pcap file")
        try {
            val filePath = makeConsoleLogFile(type)
            if (filePath == null) {
                showFileCreationErrorToast()
                return null
            }
            return filePath
        } catch (e: Exception) {
            showFileCreationErrorToast()
        }
        return null
    }

    private fun shareZipFileViaEmail(filePath: String) {
        val file = File(filePath)
        // Get the URI of the file using FileProvider
        val uri: Uri = FileProvider.getUriForFile(this, "${this.packageName}.provider", file)

        // Create the intent
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, "Log File")
                putExtra(Intent.EXTRA_TEXT, "Please find the attached log file.")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        // Start the email app
        startActivity(Intent.createChooser(intent, "Send email..."))
    }

    private fun makeConsoleLogFile(type: SaveType): String? {
        return try {
            val appVersion = getVersionName() + "_" + System.currentTimeMillis()
            return if (type.isShare()) {
                // create file in filesdir, no need to check for permissions
                val dir = filesDir.canonicalPath + File.separator
                val fileName: String = FILE_NAME + appVersion + FILE_EXTENSION
                val file = File(dir, fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.absolutePath
            } else {
                // create folder in DOWNLOADS
                val dir =
                    if (isAtleastR()) {
                        val downloadsDir =
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            )
                        // create folder in DOWNLOADS/Rethink
                        File(downloadsDir, FOLDER_NAME)
                    } else {
                        val downloadsDir = Environment.getExternalStorageDirectory()
                        // create folder in DOWNLOADS/Rethink
                        File(downloadsDir, FOLDER_NAME)
                    }
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                // filename format (rethink_app_logs_<version>.txt)
                val logFileName: String = FILE_NAME + appVersion + FILE_EXTENSION
                val file = File(dir, logFileName)
                // just in case, create the parent dir if it doesn't exist
                if (file.parentFile?.exists() != true) file.parentFile?.mkdirs()
                // create the file if it doesn't exist
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.absolutePath
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "error creating log file", e)
            null
        }
    }

    private fun getVersionName(): String {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(this.packageManager, this.packageName)
        return pInfo?.versionName ?: ""
    }

    private fun showFileCreationErrorToast() {
        // show toast message
    }

    private fun requestForStoragePermissions() {
        // version 11 (R) or above
        if (isAtleastR()) {
            try {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                val uri = Uri.fromParts(SCHEME_PACKAGE, this.packageName, null)
                intent.data = uri
                storageActivityResultLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                storageActivityResultLauncher.launch(intent)
            }
        } else {
            // below version 11
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_CODE
            )
        }
    }

    private val storageActivityResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isAtleastR()) {
                // version 11 (R) or above
                if (Environment.isExternalStorageManager()) {
                    createFile(SaveType.SAVE)
                } else {
                    showFileCreationErrorToast()
                }
            } else {
                // below ver 11 (R), the permission is handled via onRequestPermissionsResult
            }
        }

    private fun checkStoragePermissions(): Boolean {
        return if (isAtleastR()) {
            // version 11 (R) or above
            Environment.isExternalStorageManager()
        } else {
            // below version 11
            val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val read =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
