/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.celzero.bravedns.R
import com.celzero.bravedns.backup.BackupAgent
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_FILE_EXTN
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_FILE_NAME_DATETIME
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_BACKUP_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_RESTORE_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_RESTART_APP
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_TYPE_OCTET
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_TYPE_XZIP
import com.celzero.bravedns.backup.RestoreAgent
import com.celzero.bravedns.databinding.ActivityBackupRestoreBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BackupRestoreBottomSheet : BottomSheetDialogFragment() {
    private var _binding: ActivityBackupRestoreBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b
        get() = _binding!!

    val persistentState by inject<PersistentState>()
    private lateinit var backupActivityResult: ActivityResultLauncher<Intent>
    private lateinit var restoreActivityResult: ActivityResultLauncher<Intent>

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityBackupRestoreBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        result()
        init()
    }

    private fun init() {
        showVersion()
        b.brbsBackup.setOnClickListener { showBackupDialog() }

        b.brbsRestore.setOnClickListener { showRestoreDialog() }
    }

    private fun showVersion() {
        val version = getVersionName()
        b.brbsAppVersion.text =
            getString(R.string.about_version_install_source, version, getDownloadSource())
    }

    private fun getVersionName(): String {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(
                requireContext().packageManager,
                requireContext().packageName
            )
        return pInfo?.versionName ?: ""
    }

    private fun getDownloadSource(): String {
        if (Utilities.isFdroidFlavour()) return getString(R.string.build__flavor_fdroid)

        if (Utilities.isPlayStoreFlavour()) return getString(R.string.build__flavor_play_store)

        return getString(R.string.build__flavor_website)
    }

    private fun backup() {
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            // have complete access to the physical location returned as part of result
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = INTENT_TYPE_OCTET
            val sdf = SimpleDateFormat(BACKUP_FILE_NAME_DATETIME, Locale.ROOT)
            // filename format (Rethink_version_DATA_FORMAT.bk)
            val version = getVersionName().replace(' ', '_')
            val zipFileName: String = BACKUP_FILE_NAME + version + sdf.format(Date()) + BACKUP_FILE_EXTN

            intent.putExtra(Intent.EXTRA_TITLE, zipFileName)

            // Check if there's an activity that can handle this intent
            try {
                if (intent.resolveActivity(requireContext().packageManager) != null) {
                    backupActivityResult.launch(intent)
                } else {
                    Logger.e(LOG_TAG_BACKUP_RESTORE, "No activity found to handle CREATE_DOCUMENT intent")
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.brbs_backup_dialog_failure_message),
                        Toast.LENGTH_LONG
                    )
                }
            } catch (e: android.content.ActivityNotFoundException) {
                Logger.e(LOG_TAG_BACKUP_RESTORE, "Activity not found for CREATE_DOCUMENT: ${e.message}")
                Utilities.showToastUiCentered(
                    requireContext(),
                    getString(R.string.brbs_backup_dialog_failure_message),
                    Toast.LENGTH_LONG
                )
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BACKUP_RESTORE, "err opening file picker for backup: ${e.message}")
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.brbs_backup_dialog_failure_message),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun observeBackupWorker() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for backup agent worker
        workManager.getWorkInfosByTagLiveData(BackupAgent.TAG).observe(viewLifecycleOwner) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe

            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "WorkManager state: ${workInfo.state} for ${BackupAgent.TAG}"
            )
            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    showBackupSuccessUi()
                    workManager.pruneWork()
                }
                WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> {
                    showBackupFailureDialog()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(BackupAgent.TAG)
                }
                else -> {
                    // no-op
                }
            }
        }
    }

    private fun observeRestoreWorker() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for restore agent worker
        workManager.getWorkInfosByTagLiveData(RestoreAgent.TAG).observe(viewLifecycleOwner) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "WorkManager state: ${workInfo.state} for ${RestoreAgent.TAG}"
            )
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                showRestoreSuccessUi()
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                    WorkInfo.State.FAILED == workInfo.state
            ) {
                showRestoreFailureDialog()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(RestoreAgent.TAG)
            } else { // state == blocked
                // no-op
            }
        }
    }

    private fun restore() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            val mimeTypes = arrayOf(INTENT_TYPE_OCTET, INTENT_TYPE_XZIP)
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)

            restoreActivityResult.launch(intent)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BACKUP_RESTORE, "err opening file picker: ${e.message}")
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun result() {
        restoreActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result: ActivityResult ->
                when (result.resultCode) {
                    Activity.RESULT_OK -> {
                        // result data contains the uri from the file picker
                        var fileUri: Uri? = null
                        result.data?.also { uri -> fileUri = uri.data }
                        Logger.i(
                            LOG_TAG_BACKUP_RESTORE,
                            "activity result for restore process with uri: $fileUri"
                        )
                        startRestoreProcess(fileUri)
                    }
                    Activity.RESULT_CANCELED -> {
                        showRestoreFailureDialog()
                    }
                    else -> {
                        showRestoreFailureDialog()
                    }
                }
            }

        backupActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result: ActivityResult ->
                when (result.resultCode) {
                    Activity.RESULT_OK -> {
                        // get URI of file created by picker
                        var backupFileUri: Uri? = null
                        result.data?.also { uri -> backupFileUri = uri.data }
                        Logger.i(
                            LOG_TAG_BACKUP_RESTORE,
                            "activity result for backup process with uri: $backupFileUri"
                        )
                        startBackupProcess(backupFileUri)
                    }
                    Activity.RESULT_CANCELED -> {
                        showBackupFailureDialog()
                    }
                    else -> {
                        showBackupFailureDialog()
                    }
                }
            }
    }

    private fun startRestoreProcess(fileUri: Uri?) {
        if (fileUri == null) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "uri received from activity result is null, cancel restore process"
            )
            showRestoreFailureDialog()
            return
        }

        Logger.i(LOG_TAG_BACKUP_RESTORE, "invoke worker to initiate the restore process")
        val data = Data.Builder()
        data.putString(DATA_BUILDER_RESTORE_URI, fileUri.toString())

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
        WorkManager.getInstance(requireContext()).beginWith(importWorker).enqueue()
    }

    private fun startBackupProcess(backupUri: Uri?) {
        if (backupUri == null) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "uri received from activity result is null, cancel backup process"
            )
            showBackupFailureDialog()
            return
        }

        // stop vpn before beginning the backup process
        BackupHelper.stopVpn(requireContext())

        Logger.i(LOG_TAG_BACKUP_RESTORE, "invoke worker to initiate the backup process")
        val data = Data.Builder()

        data.putString(DATA_BUILDER_BACKUP_URI, backupUri.toString())
        val downloadWatcher =
            OneTimeWorkRequestBuilder<BackupAgent>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(BackupAgent.TAG)
                .build()
        WorkManager.getInstance(requireContext()).beginWith(downloadWatcher).enqueue()
    }

    private fun showBackupFailureDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_backup_dialog_failure_title)
        builder.setMessage(R.string.brbs_backup_dialog_failure_message)
        builder.setPositiveButton(getString(R.string.brbs_backup_dialog_failure_positive)) { _, _ ->
            backup()
            observeBackupWorker()
        }

        builder.setNegativeButton(getString(R.string.lbl_dismiss)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun showBackupSuccessUi() {
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.brbs_backup_complete_toast),
            Toast.LENGTH_SHORT
        )
    }

    private fun showRestoreSuccessUi() {
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.brbs_restore_complete_toast),
            Toast.LENGTH_LONG
        )
        delay(TimeUnit.MILLISECONDS.toMillis(1000), lifecycleScope) { restartApp(requireContext()) }
    }

    // do a force restart of app, sometimes the database which is restored is throws exception
    // with error code 1032 (DBMOVED), trying to change the moved database to writeable state
    // is also not helping but restarting the app does solve this issue.
    // https://issuetracker.google.com/issues/37047677
    // SQLITE_READONLY_DBMOVED error code indicates that a database cannot be modified because the
    // database file has been moved since it was opened
    private fun restartApp(context: Context) {
        val packageManager: PackageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent!!.component
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        mainIntent.putExtra(INTENT_RESTART_APP, true)
        context.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun showRestoreFailureDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_restore_dialog_failure_title)
        builder.setMessage(R.string.brbs_restore_dialog_failure_message)
        builder.setPositiveButton(getString(R.string.brbs_restore_dialog_failure_positive)) { _, _
            ->
            restore()
            observeRestoreWorker()
        }

        builder.setNegativeButton(getString(R.string.lbl_dismiss)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun showRestoreDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_restore_dialog_title)
        builder.setMessage(R.string.brbs_restore_dialog_message)
        builder.setPositiveButton(getString(R.string.brbs_restore_dialog_positive)) { _, _ ->
            restore()
            observeRestoreWorker()
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun showBackupDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_backup_dialog_title)
        builder.setMessage(R.string.brbs_backup_dialog_message)
        builder.setPositiveButton(getString(R.string.brbs_backup_dialog_positive)) { _, _ ->
            backup()
            observeBackupWorker()
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }
}
