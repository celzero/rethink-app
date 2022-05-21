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
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkSimpleViewAdapter
import com.celzero.bravedns.automaton.RethinkBlocklistsManager
import com.celzero.bravedns.databinding.FragmentRemoteBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.hasRemoteBlocklists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RethinkRemoteBlocklistFragment : Fragment(R.layout.fragment_remote_blocklist) {
    private val b by viewBinding(FragmentRemoteBlocklistBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    companion object {
        fun newInstance() = RethinkRemoteBlocklistFragment()
        val selectedFileTags: MutableLiveData<ArrayList<Int>> = MutableLiveData()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
        initClickListeners()
    }

    private fun init() {
        hasBlocklist()
    }

    private fun hasBlocklist() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.Default) {
                    hasRemoteBlocklists(requireContext(), persistentState.remoteBlocklistTimestamp)
                }
                if (blocklistsExist) {
                    setFileTagAdapter()
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
        b.bsrbDownloadLayout.visibility = View.VISIBLE
    }

    private fun showConfigureUi() {
        b.bsrbConfigureLayout.visibility = View.VISIBLE
    }

    private fun hideDownloadUi() {
        b.bsrbDownloadLayout.visibility = View.GONE
    }

    private fun hideConfigureUi() {
        b.bsrbConfigureLayout.visibility = View.GONE
    }


    private fun initClickListeners() {
        b.bsrbDownloadBtn.setOnClickListener {
            go {
                uiCtx {
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasRemoteBlocklists(requireContext(),
                                            persistentState.remoteBlocklistTimestamp)
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

        b.bsrbAdvancedBtn.setOnClickListener {
            launchConfigureRethinkPlusActivity()
        }
    }

    private fun launchConfigureRethinkPlusActivity() {
        val intent = Intent(requireContext(), ConfigureRethinkPlusActivity::class.java)
        intent.putExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA,
                        AppDownloadManager.DownloadType.REMOTE.id)
        intent.putExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA, persistentState.remoteBlocklistTimestamp)
        startActivity(intent)
    }

    private fun setFileTagAdapter() {
        val fileTag = RethinkBlocklistsManager.readJson(requireContext(),
                                                        AppDownloadManager.DownloadType.REMOTE.id,
                                                        persistentState.remoteBlocklistTimestamp)

        if (fileTag.isEmpty()) { // error while reading json
            b.bsrbDownloadBtn.visibility = View.VISIBLE
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.blocklist_update_check_failure),
                                          Toast.LENGTH_SHORT)
            return
        }

        val recylcerAdapter = RethinkSimpleViewAdapter(fileTag,
                                                       ConfigureRethinkBasicActivity.FragmentLoader.REMOTE)

        val layoutManager = LinearLayoutManager(requireContext())
        b.bsrbConfigureList.layoutManager = layoutManager
        b.bsrbConfigureList.adapter = recylcerAdapter
    }

    private fun updateBlocklistIfNeeded(isRefresh: Boolean) {
        appDownloadManager.isDownloadRequired(AppDownloadManager.DownloadType.REMOTE).observe(
            viewLifecycleOwner) {
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

            downloadRemoteBlocklist(it)
        }
    }

    private fun downloadRemoteBlocklist(timestamp: Long) {
        appDownloadManager.downloadRemoteBlocklist(timestamp).observe(viewLifecycleOwner) {
            Log.d(LoggerConstants.LOG_TAG_DOWNLOAD, "Remote blocklist download status id: $it")
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                    onDownloadFail()
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                ui {
                    onDownloadStart()
                }
                return@observe
            }

            ui {
                onDownloadSuccess()
            }
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "Remote blocklist, Is download successful? $it(timestamp/status)")
        }
    }

    private fun onDownloadStart() {
        // update the ui for download start
        showDownloadUi()
        b.bsrbDownloadProgress.visibility = View.VISIBLE
        b.bsrbDownloadBtn.text = "Downloading..."
        hideConfigureUi()
    }

    private fun onDownloadFail() {
        // update the ui for download fail
        b.bsrbDownloadProgress.visibility = View.GONE
        b.bsrbDownloadBtn.visibility = View.VISIBLE
        b.bsrbDownloadBtn.text = "Download"
        showDownloadUi()
        hideConfigureUi()
    }

    private fun onDownloadSuccess() {
        // update the ui for download success
        b.bsrbDownloadProgress.visibility = View.GONE
        b.bsrbDownloadBtn.text = "Download"
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
