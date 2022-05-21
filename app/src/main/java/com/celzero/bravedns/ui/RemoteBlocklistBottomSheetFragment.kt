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
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetRemoteBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_2
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.Companion.hasRemoteBlocklists
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RemoteBlocklistBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRemoteBlocklistBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!
    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    override fun getTheme(): Int = Themes.getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                                     persistentState.theme)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetRemoteBlocklistBinding.inflate(inflater, container, false)
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
        refreshOnDeviceBlocklistUi()
    }

    private fun setupClickListeners() {
        b.bsrbDownloadBtn.setOnClickListener {

            go {
                uiCtx {
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist) {
                        b.bsrbDesc.text = getString(R.string.settings_local_blocklist_in_use,
                                                    persistentState.numberOfLocalBlocklists.toString())
                    } else {
                        if (VpnController.isVpnLockdown()) {
                            showVpnLockdownDownloadDialog()
                        } else {
                            updateBlocklistIfNeeded(isRefresh = false)
                        }
                    }
                }
            }
        }

        b.bsrbCheckUpdate.setOnClickListener {
            updateBlocklistIfNeeded(isRefresh = true)
        }

        b.bsrbConfigureBtn.setOnClickListener {
            launchConfigureRethinkPlusActivity()
        }

        b.bsrbDismissBtn.setOnClickListener {
            this.dismiss()
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
        appDownloadManager.isDownloadRequired(AppDownloadManager.DownloadType.REMOTE).observe(
            viewLifecycleOwner) {
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    handleFailedDownload(isRefresh)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED.id) {
                ui {
                    handleRedownload(isRefresh, persistentState.remoteBlocklistTimestamp)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                // no-op
                return@observe
            }

            downloadRemoteBlocklist(it)
        }
    }

    private fun downloadRemoteBlocklist(timestamp: Long) {
        onDownloadStart()
        appDownloadManager.downloadRemoteBlocklist(timestamp).observe(viewLifecycleOwner) {
            Log.d(LoggerConstants.LOG_TAG_DOWNLOAD, "Remote blocklist download status id: $it")
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    handleFailedDownload(isRefresh = false)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                // no-op
                return@observe
            }

            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "Remote blocklist, Is download successful? $it(timestamp/status)")
            onDownloadSuccess()
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
        builder.setNeutralButton(getString(R.string.local_blocklist_neutral)) { _, _ ->
            downloadRemoteBlocklist(timestamp)
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
        builder.setPositiveButton(getString(R.string.local_blocklist_corrupt_positive)) { _, _ ->
            downloadRemoteBlocklist(timestamp)
        }
        builder.create().show()
    }

    private fun handleFailedDownload(isRefresh: Boolean) {
        if (isRefresh) {
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.blocklist_update_check_failure),
                                          Toast.LENGTH_SHORT)
        } else {
            onDownloadAbort()
        }
    }

    private fun launchConfigureRethinkPlusActivity() {
        val intent = Intent(requireContext(), ConfigureRethinkPlusActivity::class.java)
        intent.putExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA,
                        AppDownloadManager.DownloadType.REMOTE)
        intent.putExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA, "")
        startActivity(intent)
    }

    private fun onDownloadAbort() {
        // TODO: Download failure
        b.bsrbDownloadBtn.text = getString(R.string.bslb_download_btn)
        //b.bslbDownloadProgress.visibility = View.GONE
        //b.bslbDownloadRl.visibility = View.VISIBLE
        b.bsrbDownloadBtn.visibility = View.VISIBLE
        b.bsrbCheckUpdate.visibility = View.GONE
    }

    private fun onDownloadSuccess() {
        b.bsrbDownloadBtn.text = getString(R.string.bslb_download_btn)
        //b.bslbDownloadProgress.visibility = View.GONE
        //b.bsrbDownloadRl.visibility = View.GONE
        b.bsrbConfigureBtn.visibility = View.VISIBLE
        b.bsrbCheckUpdate.visibility = View.VISIBLE
    }

    private fun onDownloadStart() {
        //b.bsrbDownloadProgress.visibility = View.VISIBLE
        b.bsrbDownloadBtn.visibility = View.VISIBLE
        //b.bsrbDownloadRl.visibility = View.VISIBLE
        b.bsrbCheckUpdate.visibility = View.GONE
        b.bsrbDownloadBtn.text = getString(R.string.bslb_downloading_btn)
    }

    private fun refreshOnDeviceBlocklistUi() {
        // this switch is hidden to show the download-progress bar,
        // enable it whenever the download is complete
        if (persistentState.blocklistEnabled) {
            b.bsrbConfigureBtn.visibility = View.VISIBLE
            b.bsrbDownloadBtn.visibility = View.GONE
        } else {
            isBlocklistFilesAvailable()
        }

        refreshOnDeviceBlocklistStatus()
    }

    private fun isBlocklistFilesAvailable() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.Default) {
                    hasRemoteBlocklists(requireContext(), persistentState.remoteBlocklistTimestamp)
                }
                if (blocklistsExist) {
                    b.bsrbConfigureBtn.visibility = View.VISIBLE
                    b.bsrbDesc.text = getString(R.string.dc_on_device_block_btn_inactive)
                    b.bsrbDownloadBtn.visibility = View.GONE
                } else {
                    b.bsrbConfigureBtn.visibility = View.GONE
                    b.bsrbDownloadBtn.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun refreshOnDeviceBlocklistStatus() {
        if (!persistentState.blocklistEnabled) {
            b.bsrbDesc.text = getString(R.string.settings_local_blocklist_desc_1)
            b.bsrbAppVersion.visibility = View.GONE
            return
        }

        b.bsrbDesc.text = getString(R.string.settings_local_blocklist_in_use,
                                    persistentState.numberOfLocalBlocklists.toString())

        b.bsrbAppVersion.visibility = View.VISIBLE
        b.bsrbAppVersion.text = getString(R.string.settings_local_blocklist_version,
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
