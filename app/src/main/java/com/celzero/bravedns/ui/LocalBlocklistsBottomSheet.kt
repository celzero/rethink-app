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
package com.celzero.bravedns.ui

import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.LocalBlocklistDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.BottomSheetLocalBlocklistsBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.RETHINK_SEARCH_URL
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class LocalBlocklistsBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetLocalBlocklistsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val appDownloadManager by inject<AppDownloadManager>()

    private var dismissListener: OnBottomSheetDialogFragmentDismiss? = null

    override fun getTheme(): Int = getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                              persistentState.theme)

    interface OnBottomSheetDialogFragmentDismiss {
        fun onBtmSheetDismiss()
    }

    fun setDismissListener(listener: DnsConfigureFragment) {
        dismissListener = listener
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetLocalBlocklistsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissListener?.onBtmSheetDismiss()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateLocalBlocklistUi()
        init()
        initializeObservers()
        initializeClickListeners()
    }

    private fun init() {
        if (persistentState.localBlocklistTimestamp == INIT_TIME_MS) {
            b.lbbsDownloadLl.visibility = View.GONE
            showCheckUpdateUi()
            return
        }

        b.lbbsDownloadLl.visibility = View.VISIBLE

        if (persistentState.newestRemoteBlocklistTimestamp == INIT_TIME_MS) {
            showCheckUpdateUi()
            return
        }

        b.lbbsVersion.text = getString(R.string.settings_local_blocklist_version,
                                       Utilities.convertLongToTime(
                                           persistentState.localBlocklistTimestamp,
                                           Constants.TIME_FORMAT_2))

        if (persistentState.newestLocalBlocklistTimestamp > persistentState.localBlocklistTimestamp) {
            showUpdateUi()
            return
        }

        showCheckUpdateUi()
    }

    private fun initializeObservers() {
        appDownloadManager.timeStampToDownload.observe(viewLifecycleOwner) {
            Log.i(LoggerConstants.LOG_TAG_DNS, "Check for blocklist update, status: $it")
            if (it == AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id) {
                // no-op
                return@observe
            }
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    b.lbbsCheckDownload.isEnabled = true
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED.id) {
                ui {
                    showRedownloadUi()
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_not_required), Toast.LENGTH_SHORT)
                    appDownloadManager.timeStampToDownload.postValue(
                        AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                // no-op
                ui {
                    showCheckDownloadProgressUi()
                }
                return@observe
            }

            b.lbbsCheckDownload.isEnabled = true

            if (it == persistentState.localBlocklistTimestamp) {
                showRedownloadUi()
                appDownloadManager.timeStampToDownload.postValue(
                    AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id)
                return@observe
            }

            if (INIT_TIME_MS == persistentState.localBlocklistTimestamp) {
                showUpdateUi()
                appDownloadManager.timeStampToDownload.postValue(
                    AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id)
                return@observe
            }

            if (it != persistentState.localBlocklistTimestamp) {
                showUpdateUi()
                appDownloadManager.timeStampToDownload.postValue(
                    AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id)
                return@observe
            }
        }

        observeWorkManager()
    }

    private fun showCheckDownloadProgressUi() {
        b.lbbsCheckDownloadProgress.visibility = View.VISIBLE
        b.lbbsCheckDownloadImg.visibility = View.GONE
    }

    private fun onDownloadProgress() {
        b.lbbsDownloadProgress.visibility = View.VISIBLE
        b.lbbsRedownloadProgress.visibility = View.VISIBLE
        b.lbbsDownloadImg.visibility = View.GONE
        b.lbbsRedownloadImg.visibility = View.GONE
    }

    private fun onDownloadSuccess() {
        b.lbbsDownload.isEnabled = true
        b.lbbsRedownload.isEnabled = true
        b.lbbsDownloadProgress.visibility = View.GONE
        b.lbbsRedownloadProgress.visibility = View.GONE
        b.lbbsDownloadImg.visibility = View.VISIBLE
        b.lbbsRedownloadImg.visibility = View.VISIBLE
        Utilities.showToastUiCentered(requireActivity(),
                                      getString(R.string.download_update_dialog_message_success),
                                      Toast.LENGTH_SHORT)
    }

    private fun onDownloadFail() {
        b.lbbsDownload.isEnabled = true
        b.lbbsRedownload.isEnabled = true
        b.lbbsDownloadProgress.visibility = View.GONE
        b.lbbsRedownloadProgress.visibility = View.GONE
        b.lbbsDownloadImg.visibility = View.VISIBLE
        b.lbbsRedownloadImg.visibility = View.VISIBLE
        Utilities.showToastUiCentered(requireActivity(),
                                      getString(R.string.blocklist_update_check_failure),
                                      Toast.LENGTH_SHORT)
    }

    private fun showCheckUpdateUi() {
        b.lbbsCheckDownload.visibility = View.VISIBLE
        b.lbbsDownload.visibility = View.GONE
        b.lbbsRedownload.visibility = View.GONE

        b.lbbsCheckDownloadImg.visibility = View.VISIBLE
        b.lbbsCheckDownloadProgress.visibility = View.GONE
        b.lbbsDownloadProgress.visibility = View.GONE
        b.lbbsRedownloadProgress.visibility = View.GONE
    }

    private fun showUpdateUi() {
        b.lbbsCheckDownload.visibility = View.GONE
        b.lbbsDownload.visibility = View.VISIBLE
        b.lbbsRedownload.visibility = View.GONE

        b.lbbsDownloadImg.visibility = View.VISIBLE
        b.lbbsCheckDownloadProgress.visibility = View.GONE
        b.lbbsDownloadProgress.visibility = View.GONE
        b.lbbsRedownloadProgress.visibility = View.GONE
    }

    private fun showRedownloadUi() {
        b.lbbsCheckDownload.visibility = View.GONE
        b.lbbsDownload.visibility = View.GONE
        b.lbbsRedownload.visibility = View.VISIBLE

        b.lbbsRedownloadImg.visibility = View.VISIBLE
        b.lbbsCheckDownloadProgress.visibility = View.GONE
        b.lbbsDownloadProgress.visibility = View.GONE
        b.lbbsRedownloadProgress.visibility = View.GONE
    }

    private fun showDownloadDialog(isRedownload: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
        if (isRedownload) {
            builder.setTitle(R.string.local_blocklist_redownload)
            builder.setMessage(getString(R.string.local_blocklist_redownload_desc,
                                         Utilities.convertLongToTime(
                                             persistentState.localBlocklistTimestamp,
                                             Constants.TIME_FORMAT_2)))
        } else {
            builder.setTitle(R.string.local_blocklist_download)
            builder.setMessage(R.string.local_blocklist_download_desc)
        }
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.settings_local_blocklist_dialog_positive)) { _, _ ->
            downloadLocalBlocklist(isRedownload)
        }
        builder.setNegativeButton(
            getString(R.string.settings_local_blocklist_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.show()
    }

    private fun downloadLocalBlocklist(isRedownload: Boolean) {
        b.lbbsDownload.isEnabled = false
        b.lbbsRedownload.isEnabled = false
        val timestamp = if (isRedownload) {
            persistentState.localBlocklistTimestamp
        } else {
            persistentState.newestLocalBlocklistTimestamp
        }
        appDownloadManager.downloadLocalBlocklist(timestamp)
    }

    private fun updateLocalBlocklistUi() {
        if (Utilities.isPlayStoreFlavour()) {
            return
        }

        if (persistentState.blocklistEnabled) {
            enableBlocklistUi()
            return
        }

        disableBlocklistUi()
    }

    private fun enableBlocklistUi() {
        b.lbbsEnable.text = getString(R.string.lbbs_enabled)
        b.lbbsHeading.text = getString(R.string.settings_local_blocklist_in_use,
                                       persistentState.numberOfLocalBlocklists.toString())
        setDrawable(R.drawable.ic_tick, b.lbbsEnable)

        b.lbbsConfigure.isEnabled = true
        b.lbbsCopy.isEnabled = true
        b.lbbsSearch.isEnabled = true

        b.lbbsConfigure.alpha = 1f
        b.lbbsCopy.alpha = 1f
        b.lbbsSearch.alpha = 1f
    }

    private fun disableBlocklistUi() {
        b.lbbsEnable.text = getString(R.string.lbbs_enable)
        b.lbbsHeading.text = getString(R.string.lbbs_heading)
        setDrawable(R.drawable.ic_cross, b.lbbsEnable)

        b.lbbsConfigure.isEnabled = false
        b.lbbsCopy.isEnabled = false
        b.lbbsSearch.isEnabled = false

        b.lbbsConfigure.alpha = 0.5f
        b.lbbsCopy.alpha = 0.5f
        b.lbbsSearch.alpha = 0.5f
    }

    private fun initializeClickListeners() {
        b.lbbsEnable.setOnClickListener {
            enableBlocklist()
        }

        b.lbbsConfigure.setOnClickListener {
            invokeRethinkActivity()
        }

        b.lbbsCopy.setOnClickListener {
            ui {
                var baseUrl = Constants.RETHINK_BASE_URL_SKY
                go {
                    if (appConfig.getRethinkPlusEndpoint().url.contains(Constants.MAX_ENDPOINT)) {
                        baseUrl = Constants.RETHINK_BASE_URL_MAX
                    }
                }
                val url = baseUrl + persistentState.localBlocklistStamp
                Utilities.clipboardCopy(requireContext(), url,
                                        requireContext().getString(R.string.copy_clipboard_label))
                Utilities.showToastUiCentered(requireContext(), requireContext().getString(
                    R.string.info_dialog_rethink_toast_msg), Toast.LENGTH_SHORT)
            }
        }

        b.lbbsSearch.setOnClickListener {
            // https://rethinkdns.com/search?s=<uri-encoded-stamp>
            this.dismiss()
            val url = RETHINK_SEARCH_URL + Uri.encode(persistentState.localBlocklistStamp)
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }

        b.lbbsDownload.setOnClickListener {
            showDownloadDialog(isRedownload = false)
        }

        b.lbbsCheckDownload.setOnClickListener {
            b.lbbsCheckDownload.isEnabled = false
            isBlocklistUpdateAvailable()
        }

        b.lbbsRedownload.setOnClickListener {
            showDownloadDialog(isRedownload = true)
        }
    }

    private fun isBlocklistUpdateAvailable() {
        appDownloadManager.isDownloadRequired(AppDownloadManager.DownloadType.LOCAL, retryCount = 0)
    }

    private fun enableBlocklist() {
        if (persistentState.blocklistEnabled) {
            removeBraveDnsLocal()
            updateLocalBlocklistUi()
            return
        }

        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.Default) {
                    Utilities.hasLocalBlocklists(requireContext(),
                                                 persistentState.localBlocklistTimestamp)
                }
                if (blocklistsExist && isLocalBlocklistStampAvailable()) {
                    setBraveDnsLocal()
                    updateLocalBlocklistUi()
                } else {
                    invokeRethinkActivity()
                }
            }
        }
    }

    private fun invokeRethinkActivity() {
        this.dismiss()
        val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(ConfigureRethinkBasicActivity.INTENT,
                        ConfigureRethinkBasicActivity.FragmentLoader.LOCAL.ordinal)
        requireContext().startActivity(intent)
    }

    private fun isLocalBlocklistStampAvailable(): Boolean {
        if (persistentState.localBlocklistStamp.isEmpty()) {
            return false
        }

        return true
    }

    // FIXME: Verification of BraveDns object should be added in future.
    private fun setBraveDnsLocal() {
        persistentState.blocklistEnabled = true
    }

    private fun removeBraveDnsLocal() {
        persistentState.blocklistEnabled = false
    }

    private fun setDrawable(drawable: Int, txt: AppCompatTextView) {
        val end = ContextCompat.getDrawable(requireContext(), drawable)
        txt.setCompoundDrawablesWithIntrinsicBounds(null, null, end, null)
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
                onDownloadProgress()
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
                onDownloadProgress()
            } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(DownloadConstants.DOWNLOAD_TAG)
                workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
            } else { // state == blocked, succeeded
                // no-op
            }
        }

        workManager.getWorkInfosByTagLiveData(DownloadConstants.FILE_TAG).observe(
            viewLifecycleOwner) { workInfoList ->
            if (workInfoList != null && workInfoList.isNotEmpty()) {
                val workInfo = workInfoList[0]
                if (workInfo != null && workInfo.state == WorkInfo.State.SUCCEEDED) {
                    Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                          "AppDownloadManager Work Manager completed - ${DownloadConstants.FILE_TAG}")
                    onDownloadSuccess()
                    workManager.pruneWork()
                } else if (workInfo != null && (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED)) {
                    onDownloadFail()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
                    Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                          "AppDownloadManager Work Manager failed - ${DownloadConstants.FILE_TAG}")
                } else {
                    Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                          "AppDownloadManager Work Manager - ${DownloadConstants.FILE_TAG}, ${workInfo.state}")
                }
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                f()
            }
        }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }

}
