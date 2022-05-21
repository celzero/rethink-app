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

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkSimpleViewAdapter
import com.celzero.bravedns.automaton.RethinkBlocklistsManager
import com.celzero.bravedns.customdownloader.LocalBlocklistDownloader
import com.celzero.bravedns.databinding.FragmentLocalBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RethinkLocalBlocklistFragment : Fragment(R.layout.fragment_local_blocklist) {
    private val b by viewBinding(FragmentLocalBlocklistBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    companion object {
        fun newInstance() = RethinkLocalBlocklistFragment()
        val selectedFileTags: MutableLiveData<ArrayList<Int>> = MutableLiveData()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
        initClickListeners()
    }

    private fun init() {
        hasBlocklist()
        observeWorkManager()
    }

    private fun hasBlocklist() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.Default) {
                    hasLocalBlocklists(requireContext(), persistentState.localBlocklistTimestamp)
                }
                if (blocklistsExist) {
                    setListAdapter()
                    showConfigureUi()
                    hideDownloadUi()
                    return@uiCtx
                }

                showDownloadUi()
                hideConfigureUi()
            }
        }
    }

    private fun showDownloadUi() {
        b.bslbDownloadLayout.visibility = View.VISIBLE
    }

    private fun showConfigureUi() {
        b.bslbConfigureLayout.visibility = View.VISIBLE
    }

    private fun hideDownloadUi() {
        b.bslbDownloadLayout.visibility = View.GONE
    }

    private fun hideConfigureUi() {
        b.bslbConfigureLayout.visibility = View.GONE
    }

    private fun initClickListeners() {
        b.bslbDownloadBtn.setOnClickListener {

            go {
                uiCtx {
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist) {
                        b.bslbDesc.text = getString(R.string.settings_local_blocklist_in_use,
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

        b.bslbAdvancedBtn.setOnClickListener {
            launchConfigureRethinkPlusActivity()
        }
    }

    private fun launchConfigureRethinkPlusActivity() {
        val intent = Intent(requireContext(), ConfigureRethinkPlusActivity::class.java)
        intent.putExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA,
                        AppDownloadManager.DownloadType.LOCAL.id)
        intent.putExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA, persistentState.localBlocklistStamp)
        startActivity(intent)
    }

    private fun setListAdapter() {
        val fileTag = RethinkBlocklistsManager.readJson(requireContext(),
                                                        AppDownloadManager.DownloadType.LOCAL.id,
                                                        persistentState.localBlocklistTimestamp)

        // json parse error will result in empty  list
        if (fileTag.isEmpty()) {
            b.bslbDownloadBtn.visibility = View.VISIBLE
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.blocklist_update_check_failure),
                                          Toast.LENGTH_SHORT)
            return
        }

        // set recycler view adapter
        val recylcerAdapter = RethinkSimpleViewAdapter(fileTag,
                                                       ConfigureRethinkBasicActivity.FragmentLoader.LOCAL)

        val layoutManager = LinearLayoutManager(requireContext())
        b.bslbConfigureList.layoutManager = layoutManager
        b.bslbConfigureList.adapter = recylcerAdapter
    }


    private fun updateBlocklistIfNeeded(isRefresh: Boolean) {
        appDownloadManager.isDownloadRequired(AppDownloadManager.DownloadType.LOCAL).observe(
            viewLifecycleOwner) {
            Log.d(LoggerConstants.LOG_TAG_DNS, "Check for local blocklist update, status: $it")
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED.id) {
                ui {
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                // no-op
                return@observe
            }

            showNewUpdateUi(it)
        }
    }

    private fun showNewUpdateUi(t: Long) {
        appDownloadManager.downloadLocalBlocklist(t)
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

    private fun onDownloadStart() {
        // update the ui for download start
        showDownloadUi()
        b.bslbDownloadProgress.visibility = View.VISIBLE
        b.bslbDownloadBtn.text = "Downloading..."
        hideConfigureUi()
    }

    private fun onDownloadFail() {
        // update the ui for download fail
        b.bslbDownloadProgress.visibility = View.GONE
        b.bslbDownloadBtn.visibility = View.VISIBLE
        b.bslbDownloadBtn.text = "Download"
        showDownloadUi()
        hideConfigureUi()
    }

    private fun onDownloadSuccess() {
        // update the ui for download success
        b.bslbDownloadProgress.visibility = View.GONE
        b.bslbDownloadBtn.text = "Download"
        hideDownloadUi()
        showConfigureUi()
    }

    private fun showVpnLockdownDownloadDialog() {
        // fixme: instead of dialog, show some illustrator / info regarding vpn lockdown
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.download_lockdown_dialog_heading)
        builder.setMessage(R.string.download_lockdown_dialog_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.download_lockdown_dialog_positive)) { _, _ ->
            Utilities.openVpnProfile(requireContext())
        }
        builder.setNegativeButton(
            getString(R.string.download_lockdown_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
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

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                f()
            }
        }
    }

}
