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
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.celzero.bravedns.ui.BaseActivity
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
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.disableFrostTemporarily
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.util.restoreFrost
import com.celzero.bravedns.viewmodel.ConsoleLogViewModel
import com.google.android.material.checkbox.MaterialCheckBox
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

class ConsoleLogActivity : BaseActivity(R.layout.activity_console_log), androidx.appcompat.widget.SearchView.OnQueryTextListener {

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
        private const val CRASH_CODE = "**CRASH**"
    }

    // Guard against rapid double-taps on share buttons while a job is in-progress
    private var isShareInProgress = false

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
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

        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        restoreFrost(themeId)
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
        b.fabShareLog.text = getString(R.string.about_bug_report_desc).capitalizeWords()
        b.searchView.setOnQueryTextListener(this)
        val logLevel = Logger.uiLogLevel.toInt()
        if (logLevel >= Logger.LoggerLevel.ERROR.id) {
            showFilterDialog()
        }
    }

    var recyclerAdapter: ConsoleLogAdapter? = null

    private fun setAdapter() {
        try {
            b.consoleLogList.setHasFixedSize(true)
            // disable all animations to prevent state inconsistencies
            b.consoleLogList.itemAnimator = null

            // Set a custom layout manager that handles errors gracefully
            layoutManager = object : LinearLayoutManager(this@ConsoleLogActivity) {
                override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
                    try {
                        super.onLayoutChildren(recycler, state)
                    } catch (e: IndexOutOfBoundsException) {
                        Logger.w(LOG_TAG_UI, "err(console) layout children: ${e.message}")
                    }
                }
            }
            // Disable RecyclerView's GapWorker-driven item prefetch on this list.
            // The console log is updated at very high frequency by background logging
            // coroutines (Paging 3 inserts), and prefetch races with the paging
            // update path to produce the IndexOutOfBoundsException in ConsoleLogAdapter
            // reported as #2591 ("v055u: IndexOutOfBoundsException ConsoleLogAdapter").
            // The existing try/catch above SWALLOWS the exception when it does fire;
            // this prevents the race that causes it. Trade-off: marginally slower
            // scroll because the next item is bound on-demand instead of one-frame
            // ahead. For a debug log view that's a non-issue.
            //
            // Note: ConnectionTrackerFragment / DnsLogFragment elsewhere in this app
            // explicitly enable prefetch (lm.isItemPrefetchEnabled = true). The
            // console log has a fundamentally higher write rate so the default needs
            // to differ.
            (layoutManager as? LinearLayoutManager)?.isItemPrefetchEnabled = false

            b.consoleLogList.layoutManager = layoutManager
            recyclerAdapter = ConsoleLogAdapter(this)
            b.consoleLogList.adapter = recyclerAdapter
            viewModel.setLogLevel(Logger.uiLogLevel)
            observeLog()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err setting up console, recycler: ${e.message}")
        }
    }

    private fun observeLog() {
        viewModel.logs.observe(this) { pagingData ->
            recyclerAdapter?.submitData(lifecycle, pagingData)
        }
    }
    private fun setupClickListener() {

        b.consoleLogShare.setOnClickListener {
            if (isShareInProgress) return@setOnClickListener
            val filePath = makeConsoleLogFile()
            if (filePath == null) {
                showFileCreationErrorToast()
                return@setOnClickListener
            }
            handleShareLogs(filePath)
        }

        b.fabShareLog.setOnClickListener {
            if (isShareInProgress) return@setOnClickListener
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
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.console_log_title))
        val items = arrayOf(
            getString(R.string.settings_gologger_dialog_option_0),
            getString(R.string.settings_gologger_dialog_option_1),
            getString(R.string.settings_gologger_dialog_option_2),
            getString(R.string.settings_gologger_dialog_option_3),
            getString(R.string.settings_gologger_dialog_option_4),
            getString(R.string.settings_gologger_dialog_option_5),
            getString(R.string.settings_gologger_dialog_option_6),
            getString(R.string.settings_gologger_dialog_option_7),
        )
        val checkedItem = Logger.uiLogLevel.toInt()
        builder.setSingleChoiceItems(
            items.map { it }.toTypedArray(),
            checkedItem
        ) { _, which ->
            Logger.uiLogLevel = which.toLong()
            GoVpnAdapter.setLogLevel(
                persistentState.goLoggerLevel.toInt(),
                Logger.uiLogLevel.toInt(),
                persistentState.includeFileTrace
            )
            viewModel.setLogLevel(which.toLong())
            if (which < Logger.LoggerLevel.ERROR.id) {
                consoleLogRepository.setStartTimestamp(System.currentTimeMillis())
            }
            Logger.i(LOG_TAG_BUG_REPORT, "Log level set to ${items[which]}")
        }

        val cb = MaterialCheckBox(this)
        cb.text = getString(R.string.console_log_include_file_trace)
        cb.isChecked = persistentState.includeFileTrace
        cb.setOnCheckedChangeListener { _, isChecked ->
            persistentState.includeFileTrace = isChecked
            GoVpnAdapter.setLogLevel(
                persistentState.goLoggerLevel.toInt(),
                Logger.uiLogLevel.toInt(),
                persistentState.includeFileTrace
            )
            viewModel.setLogLevel(Logger.uiLogLevel)
            Logger.i(LOG_TAG_BUG_REPORT, "File trace set to $isChecked")
        }
        val density = resources.displayMetrics.density
        val margin = (20 * density).toInt()
        val container = LinearLayout(this)
        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        params.setMargins(margin, 0, margin, 0)
        container.addView(cb, params)
        builder.setView(container)

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
        if (isShareInProgress) return
        isShareInProgress = true

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
                isShareInProgress = false
                onSuccess()
                shareZipFileViaEmail(filePath)
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                WorkInfo.State.FAILED == workInfo.state
            ) {
                isShareInProgress = false
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
        disableFrostTemporarily()
        val file = File(filePath)
        if (!file.exists()) {
            Logger.w(LOG_TAG_BUG_REPORT, "log file does not exist: $filePath")
            showFileCreationErrorToast()
            return
        }

        // Get the URI of the file using FileProvider.
        val uri: Uri? = try {
            FileProvider.getUriForFile(this, BugReportZipper.FILE_PROVIDER_NAME, file)
        } catch (e: IllegalArgumentException) {
            Logger.e(
                LOG_TAG_BUG_REPORT,
                "err(shareZip) FileProvider authority not found (${BugReportZipper.FILE_PROVIDER_NAME}): ${e.message}",
                e
            )
            null
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err(shareZip) getting file uri: ${e.message}", e)
            null
        }

        if (uri == null) {
            showToastUiCentered(
                this,
                getString(R.string.error_loading_log_file),
                Toast.LENGTH_SHORT
            )
            return
        }

        // Create the intent
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_bugreport_subject))
                putExtra(Intent.EXTRA_TEXT, "Crash/Issue Report (Logs Attached)")
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.about_mail_to)))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        // start the email app
        try {
            startActivity(Intent.createChooser(intent, "Send email..."))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err(shareZip) starting share intent: ${e.message}", e)
            showToastUiCentered(
                this,
                getString(R.string.error_loading_log_file),
                Toast.LENGTH_SHORT
            )
        }
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
            Logger.w(LOG_TAG_BUG_REPORT, "err creating log file, ${e.message}")
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
        if (query == CRASH_CODE) {
            crashTun()
            return true
        }
        searchQuery.value = query
        return true
    }

    @OptIn(FlowPreview::class)
    override fun onQueryTextChange(query: String): Boolean {
        if (query == CRASH_CODE) {
            crashTun()
            return true
        }
        searchQuery.value = query
        return true
    }

    private fun crashTun() {
        io {
            VpnController.crashTun()
        }
    }
}
