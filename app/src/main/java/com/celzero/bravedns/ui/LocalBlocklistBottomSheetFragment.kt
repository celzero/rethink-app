/*
Copyright 2022 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.LocalBlocklistDownloader
import com.celzero.bravedns.databinding.BottomSheetLocalBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_2
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class LocalBlocklistBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLocalBlocklistBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!
    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    override fun getTheme(): Int = Themes.getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                                     persistentState.theme)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetLocalBlocklistBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        observeWorkManager()
        refreshOnDeviceBlocklistUi()
        checkVpnLockdown()
    }

    private fun checkVpnLockdown() {
        if (VpnController.isVpnLockdown()) {
            b.bslbVpnProfile.visibility = View.VISIBLE
            return
        }

        b.bslbVpnProfile.visibility = View.GONE
    }

    private fun setupClickListeners() {
        b.bslbDownloadBtn.setOnClickListener {

            go {
                uiCtx {
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist) {
                        //setBraveDnsLocal() // TODO: Move this to vpnService observer
                        b.bslbDesc.text = getString(R.string.settings_local_blocklist_in_use,
                                                    persistentState.numberOfLocalBlocklists.toString())
                    } else {
                        if (VpnController.isVpnLockdown()) {
                            b.bslbDesc.text = getString(R.string.download_lockdown_dialog_desc)
                            b.bslbVpnProfile.visibility = View.VISIBLE
                        } else {
                            updateBlocklistIfNeeded(isRefresh = false)
                        }
                    }
                }
            }
        }

        b.bslbCheckUpdate.setOnClickListener {
            updateBlocklistIfNeeded(isRefresh = true)
        }

        b.bslbConfigureBtn.setOnClickListener {
            launchConfigureRethinkPlusActivity()
        }

        b.bslbDismissBtn.setOnClickListener {
            this.dismiss()
        }
    }

    private fun observeWorkManager() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for custom download manager worker
        workManager.getWorkInfosByTagLiveData(LocalBlocklistDownloader.CUSTOM_DOWNLOAD).observe(
            viewLifecycleOwner) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "WorkManager state: ${workInfo.state} for ${LocalBlocklistDownloader.CUSTOM_DOWNLOAD}")
            if (WorkInfo.State.ENQUEUED == workInfo.state || WorkInfo.State.RUNNING == workInfo.state) {
                onDownloadStart()
            } else if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                onDownloadSuccess()
                workManager.pruneWork()
            } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(LocalBlocklistDownloader.CUSTOM_DOWNLOAD)
            } else { // state == blocked
                // no-op
            }
        }

        // observer for Androids default download manager
        workManager.getWorkInfosByTagLiveData(DownloadConstants.DOWNLOAD_TAG).observe(
            viewLifecycleOwner) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "WorkManager state: ${workInfo.state} for ${DownloadConstants.DOWNLOAD_TAG}")
            if (WorkInfo.State.ENQUEUED == workInfo.state || WorkInfo.State.RUNNING == workInfo.state) {
                onDownloadStart()
            } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(DownloadConstants.DOWNLOAD_TAG)
                workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
            } else { // state == blocked, succeeded
                // no-op
            }
        }

        // observer for File watcher worker(part of Android default download manager)
        workManager.getWorkInfosByTagLiveData(DownloadConstants.FILE_TAG).observe(
            viewLifecycleOwner) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "WorkManager state: ${workInfo.state} for ${DownloadConstants.FILE_TAG}")
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                onDownloadSuccess()
                workManager.pruneWork()
            } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
            } else { // state == blocked, queued, or running
                // no-op
            }
        }
    }

    private fun showVpnLockdownDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.download_lockdown_dialog_heading)
        builder.setMessage(R.string.download_lockdown_dialog_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.download_lockdown_dialog_positive)) { _, _ ->
            openVpnProfile(requireContext())
        }
        builder.setNegativeButton(
            getString(R.string.download_lockdown_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun showDownloadDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_download)
        builder.setMessage(R.string.local_blocklist_download_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.settings_local_blocklist_dialog_positive)) { _, _ ->
            updateBlocklistIfNeeded(isRefresh = false)
        }
        builder.setNegativeButton(
            getString(R.string.settings_local_blocklist_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun updateBlocklistIfNeeded(isRefresh: Boolean) {
        appDownloadManager.isDownloadRequired(AppDownloadManager.DownloadType.LOCAL).observe(
            viewLifecycleOwner) {
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    handleFailedDownload(isRefresh)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED.id) {
                ui {
                    handleRedownload(isRefresh, persistentState.localBlocklistTimestamp)
                }
                return@observe
            }

            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD, "Local blocklist, Is download required? $it")
            appDownloadManager.downloadLocalBlocklist(it)
        }
    }

    private fun handleRedownload(isRefresh: Boolean, timestamp: Long) {
        if (!isRefresh) {
            showFileCorruptionDialog(timestamp)
            return
        }

        if (VpnController.isVpnLockdown()) {
            showRedownloadDialogLockdown(timestamp)
        } else {
            showRedownloadDialog(timestamp)
        }
    }


    private fun showRedownloadDialogLockdown(timestamp: Long) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_lockdown_redownload)
        builder.setMessage(getString(R.string.local_blocklist_lockdown_redownload_desc,
                                     Utilities.convertLongToTime(timestamp, TIME_FORMAT_2)))
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.local_blocklist_lockdown_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.create().show()
    }

    private fun showRedownloadDialog(timestamp: Long) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_redownload)
        builder.setMessage(getString(R.string.local_blocklist_redownload_desc,
                                     Utilities.convertLongToTime(timestamp, TIME_FORMAT_2)))
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.local_blocklist_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNeutralButton(
            getString(R.string.local_blocklist_neutral)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            onDownloadStart()
            appDownloadManager.downloadLocalBlocklist(timestamp)
        }
        builder.create().show()
    }

    private fun showFileCorruptionDialog(timestamp: Long) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_corrupt)
        builder.setMessage(R.string.local_blocklist_corrupt_desc)
        builder.setCancelable(false)
        builder.setNegativeButton(
            getString(R.string.local_blocklist_corrupt_negative)) { dialogInterface, _ ->
            handleFailedDownload(isRefresh = false)
            dialogInterface.dismiss()
        }
        builder.setPositiveButton(
            getString(R.string.local_blocklist_corrupt_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            appDownloadManager.downloadLocalBlocklist(timestamp)
        }
        builder.create().show()
    }

    private fun handleFailedDownload(isRefresh: Boolean) {
        if (isRefresh) {
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.blocklist_update_check_failure),
                                          Toast.LENGTH_SHORT)
        } else {
            onDownloadFail()
        }
    }

    private fun launchConfigureRethinkPlusActivity() {
        val intent = Intent(requireContext(), ConfigureRethinkPlusActivity::class.java)
        intent.putExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA,
                        AppDownloadManager.DownloadType.LOCAL.id)
        intent.putExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA, persistentState.localBlocklistStamp)
        startActivity(intent)
        this.dismiss()
    }

    private fun onDownloadFail() {
        // TODO: Download failure ui update
        b.bslbDownloadBtn.text = getString(R.string.bslb_download_btn)
        b.bslbDownloadProgress.visibility = View.GONE
        b.bslbDownloadRl.visibility = View.VISIBLE
        b.bslbDownloadBtn.visibility = View.VISIBLE
        b.bslbCheckUpdate.visibility = View.GONE
    }

    private fun onDownloadSuccess() {
        // TODO: Download success ui update
        b.bslbDownloadBtn.text = getString(R.string.bslb_download_btn)
        b.bslbDownloadProgress.visibility = View.GONE
        b.bslbDownloadRl.visibility = View.GONE
        b.bslbConfigureBtn.visibility = View.VISIBLE
        b.bslbCheckUpdate.visibility = View.VISIBLE
        refreshOnDeviceBlocklistStatus()
    }

    private fun onDownloadStart() {
        // TODO: enable the progress inside the download button
        b.bslbDownloadProgress.visibility = View.VISIBLE
        b.bslbDownloadBtn.visibility = View.VISIBLE
        b.bslbDownloadRl.visibility = View.VISIBLE
        b.bslbCheckUpdate.visibility = View.GONE
        b.bslbDownloadBtn.text = getString(R.string.bslb_downloading_btn)
    }

    private fun refreshOnDeviceBlocklistUi() {
        // this switch is hidden to show the download-progress bar,
        // enable it whenever the download is complete
        if (persistentState.blocklistEnabled) {
            b.bslbConfigureBtn.visibility = View.VISIBLE
            b.bslbDownloadRl.visibility = View.GONE
        } else {
            isBlocklistFilesAvailable()
        }

        refreshOnDeviceBlocklistStatus()
    }

    private fun isBlocklistFilesAvailable() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.Default) {
                    hasLocalBlocklists(requireContext(), persistentState.localBlocklistTimestamp)
                }
                if (DEBUG) Log.d(LoggerConstants.LOG_TAG_DOWNLOAD,
                                 "Block list files exist? $blocklistsExist")
                if (blocklistsExist) {
                    onDownloadSuccess()
                } else {
                    onDownloadFail()
                }
            }
        }
    }

    private fun refreshOnDeviceBlocklistStatus() {
        if (!persistentState.blocklistEnabled) {
            b.bslbDesc.text = getString(R.string.settings_local_blocklist_desc_1)
            b.bslbAppVersion.visibility = View.GONE
            return
        }

        b.bslbDesc.text = getString(R.string.settings_local_blocklist_in_use,
                                    persistentState.numberOfLocalBlocklists.toString())

        b.bslbAppVersion.visibility = View.VISIBLE
        b.bslbAppVersion.text = getString(R.string.settings_local_blocklist_version,
                                          Utilities.convertLongToTime(
                                              persistentState.localBlocklistTimestamp,
                                              TIME_FORMAT_2))
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                f()
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }
}
