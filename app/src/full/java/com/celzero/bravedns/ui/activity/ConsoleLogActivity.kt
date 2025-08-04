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

import Logger
import Logger.LOG_TAG_BUG_REPORT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
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
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.ConsoleLogViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

class ConsoleLogActivity : AppCompatActivity(R.layout.activity_console_log), androidx.appcompat.widget.SearchView.OnQueryTextListener {

    private val b by viewBinding(ActivityConsoleLogBinding::bind)
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val persistentState by inject<PersistentState>()

    private val viewModel by inject<ConsoleLogViewModel>()
    private val consoleLogRepository by inject<ConsoleLogRepository>()
    private val workScheduler by inject<WorkScheduler>()

    companion object {
        private const val FILE_NAME = "rethink_app_logs_"
        private const val FILE_EXTENSION = ".zip"
        private const val QUERY_TEXT_DELAY: Long = 1000
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
        setQueryFilter()
    }

    @OptIn(FlowPreview::class)
    private fun setQueryFilter() {
        lifecycleScope.launch {
            searchQuery
                .debounce(QUERY_TEXT_DELAY)
                .distinctUntilChanged()
                .collect { query ->
                    viewModel.setFilter(query)
                }
        }
    }

    override fun onResume() {
        super.onResume()
        // fix for #1939, OEM-specific bug, especially on heavily customized Android
        // some ROMs kill or freeze the keyboard/IME process to save memory or battery,
        // causing SearchView to stop receiving input events
        // this is a workaround to restart the IME process
        b.searchView.setQuery("", false)
        b.searchView.clearFocus()

        val imm = this.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(b.searchView)
    }

    private fun initView() {
        setAdapter()
        // update the text view with the time since logs are available
        io {
            val sinceTime = viewModel.sinceTime()
            if (sinceTime == 0L) return@io

            val since = Utilities.convertLongToTime(sinceTime, Constants.TIME_FORMAT_3)
            uiCtx {
                val desc = getString(R.string.console_log_desc)
                val sinceTxt = getString(R.string.logs_card_duration, since)
                val descWithTime = getString(R.string.two_argument_space, desc, sinceTxt)
                b.consoleLogInfoText.text = descWithTime
            }
        }
        b.searchView.setOnQueryTextListener(this)
    }

    var recyclerAdapter: ConsoleLogAdapter? = null

    private fun setAdapter() {
        b.consoleLogList.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this@ConsoleLogActivity)
        b.consoleLogList.layoutManager = layoutManager
        recyclerAdapter = ConsoleLogAdapter(this)
        b.consoleLogList.adapter = recyclerAdapter
        viewModel.setLogLevel(Logger.uiLogLevel)
        observeLog()
    }

    private fun observeLog() {
        viewModel.logs.observe(this) { l ->
            lifecycleScope.launch {
                recyclerAdapter?.submitData(l)
            }
        }
    }

    private fun setupClickListener() {

        b.consoleLogShare.setOnClickListener {
            val filePath = makeConsoleLogFile()
            if (filePath == null) {
                showFileCreationErrorToast()
                return@setOnClickListener
            }
            handleShareLogs(filePath)
        }

        b.fabShareLog.setOnClickListener {
            val filePath = makeConsoleLogFile()
            if (filePath == null) {
                showFileCreationErrorToast()
                return@setOnClickListener
            }
            handleShareLogs(filePath)
        }

        b.consoleLogDelete.setOnClickListener {
            io {
                Logger.i(LOG_TAG_BUG_REPORT, "deleting all console logs")
                consoleLogRepository.deleteAllLogs()
                uiCtx {
                    showToastUiCentered(
                        this,
                        getString(R.string.config_add_success_toast),
                        Toast.LENGTH_SHORT
                    )
                    finish()
                }
            }
        }

        b.searchFilterIcon.setOnClickListener {
            showFilterDialog()
        }
    }

    private fun showFilterDialog() {
        // show dialog with level filter
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.console_log_title))
        val items = Logger.LoggerLevel.entries
        val checkedItem = Logger.uiLogLevel.toInt()
        builder.setSingleChoiceItems(
            items.map { it.name }.toTypedArray(),
            checkedItem
        ) { _, which ->
            Logger.uiLogLevel = items[which].id
            viewModel.setLogLevel(which.toLong())
            if (which < Logger.LoggerLevel.ERROR.id) {
                consoleLogRepository.setStartTimestamp(System.currentTimeMillis())
            }
            Logger.i(LOG_TAG_BUG_REPORT, "Log level set to ${items[which].name}")
        }
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNeutralButton(getString(R.string.lbl_cancel)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun handleShareLogs(filePath: String) {
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
                shareZipFileViaEmail(filePath)
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
        Logger.i(LOG_TAG_BUG_REPORT, "created logs successfully")
        b.consoleLogProgressBar.visibility = View.GONE
        Toast.makeText(this, getString(R.string.config_add_success_toast), Toast.LENGTH_LONG).show()
    }

    private fun onFailure() {
        // show failure message
        Logger.i(LOG_TAG_BUG_REPORT, "failed to create logs")
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
        Logger.i(LOG_TAG_BUG_REPORT, "showing log generation progress UI")
        b.consoleLogProgressBar.visibility = View.VISIBLE
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
                putExtra(Intent.EXTRA_TEXT, "Attached is the log file for RethinkDNS.")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        // start the email app
        startActivity(Intent.createChooser(intent, "Send email..."))
    }

    private fun makeConsoleLogFile(): String? {
        return try {
            val appVersion = getVersionName() + "_" + System.currentTimeMillis()
            // create file in filesdir, no need to check for permissions
            val dir = filesDir.canonicalPath + File.separator
            val fileName: String = FILE_NAME + appVersion + FILE_EXTENSION
            val file = File(dir, fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            return file.absolutePath
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "error creating log file, ${e.message}")
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
        showToastUiCentered(
            this,
            getString(R.string.error_loading_log_file),
            Toast.LENGTH_SHORT
        )
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    val searchQuery = MutableStateFlow("")
    @OptIn(FlowPreview::class)
    override fun onQueryTextSubmit(query: String): Boolean {
        searchQuery.value = query
        return true
    }

    @OptIn(FlowPreview::class)
    override fun onQueryTextChange(query: String): Boolean {
        searchQuery.value = query
        return true
    }
}
