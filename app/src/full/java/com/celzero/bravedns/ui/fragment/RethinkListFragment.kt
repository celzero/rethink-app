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
package com.celzero.bravedns.ui.fragment

import Logger
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkEndpointAdapter
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator
import com.celzero.bravedns.customdownloader.RemoteBlocklistCoordinator
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentRethinkListBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.UID
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.RethinkEndpointViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkListFragment : Fragment(R.layout.fragment_rethink_list) {
    private val b by viewBinding(FragmentRethinkListBinding::bind)

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    // rethink doh ui elements
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: RethinkEndpointAdapter? = null
    private val viewModel: RethinkEndpointViewModel by viewModel()

    private var uid: Int = Constants.MISSING_UID

    companion object {
        fun newInstance() = RethinkListFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val bundle = this.arguments
        uid = bundle?.getInt(UID, Constants.MISSING_UID) ?: Constants.MISSING_UID
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
    }

    private fun showBlocklistVersionUi() {
        if (getDownloadTimeStamp() == INIT_TIME_MS) {
            b.dohFabAddServerIcon.visibility = View.GONE
            b.lbVersion.visibility = View.GONE
            return
        }

        b.lbVersion.text =
            getString(
                R.string.settings_local_blocklist_version,
                Utilities.convertLongToTime(getDownloadTimeStamp(), Constants.TIME_FORMAT_2)
            )
    }

    private fun showProgress(chip: Chip) {
        val cpDrawable = CircularProgressDrawable(requireContext())
        cpDrawable.setStyle(CircularProgressDrawable.DEFAULT)
        val color = fetchColor(requireContext(), R.attr.chipTextPositive)
        cpDrawable.colorFilter =
            BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                color,
                BlendModeCompat.SRC_ATOP
            )
        cpDrawable.start()

        chip.chipIcon = cpDrawable
        chip.isChipIconVisible = true
    }

    private fun hideProgress() {
        b.bslbCheckUpdateBtn.isChipIconVisible = false
        b.bslbRedownloadBtn.isChipIconVisible = false
        b.bslbUpdateAvailableBtn.isChipIconVisible = false
    }

    private fun showUpdateCheckUi() {
        if (isBlocklistUpdateAvailable()) {
            b.bslbUpdateAvailableBtn.visibility = View.VISIBLE
            b.bslbRedownloadBtn.visibility = View.GONE
            b.bslbCheckUpdateBtn.visibility = View.GONE
            return
        }

        b.bslbCheckUpdateBtn.visibility = View.VISIBLE
        b.bslbRedownloadBtn.visibility = View.GONE
        b.bslbUpdateAvailableBtn.visibility = View.GONE
        return
    }

    private fun isBlocklistUpdateAvailable(): Boolean {
        Logger.d(
            Logger.LOG_TAG_DOWNLOAD,
            "Update available? newest: ${persistentState.newestRemoteBlocklistTimestamp}, available: ${persistentState.remoteBlocklistTimestamp}"
        )
        return (persistentState.newestRemoteBlocklistTimestamp != INIT_TIME_MS &&
            persistentState.newestRemoteBlocklistTimestamp >
                persistentState.remoteBlocklistTimestamp)
    }

    private fun checkBlocklistUpdate() {
        io { appDownloadManager.isDownloadRequired(RethinkBlocklistManager.DownloadType.REMOTE) }
    }

    private fun getDownloadTimeStamp(): Long {
        return persistentState.remoteBlocklistTimestamp
    }

    private fun initView() {
        showBlocklistVersionUi()
        showUpdateCheckUi()
        updateMaxSwitchUi()

        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager

        recyclerAdapter = RethinkEndpointAdapter(requireContext(), get())
        viewModel.setFilter(uid)
        viewModel.rethinkEndpointList.observe(viewLifecycleOwner) {
            recyclerAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerDohConnections.adapter = recyclerAdapter
    }

    private fun updateMaxSwitchUi() {
        ui {
            var endpointUrl: String? = null
            ioCtx { endpointUrl = appConfig.getRethinkPlusEndpoint()?.url }
            updateRethinkRadioUi(isMax = endpointUrl?.contains(MAX_ENDPOINT) == true)
        }
    }

    private fun initClickListeners() {
        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.dohFabAddServerIcon.bringToFront()
        b.dohFabAddServerIcon.setOnClickListener {
            val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
            intent.putExtra(
                RETHINK_BLOCKLIST_TYPE,
                RethinkBlocklistManager.RethinkBlocklistType.REMOTE
            )
            requireContext().startActivity(intent)
        }

        b.bslbCheckUpdateBtn.setOnClickListener {
            b.bslbCheckUpdateBtn.isEnabled = false
            showProgress(b.bslbCheckUpdateBtn)
            checkBlocklistUpdate()
        }

        b.bslbUpdateAvailableBtn.setOnClickListener {
            b.bslbUpdateAvailableBtn.isEnabled = false
            val timestamp = getDownloadTimeStamp()

            // show dialog if the download type is local
            showProgress(b.bslbUpdateAvailableBtn)
            download(timestamp, isRedownload = false)
        }

        b.bslbRedownloadBtn.setOnClickListener {
            b.bslbRedownloadBtn.isEnabled = false
            showProgress(b.bslbRedownloadBtn)
            download(getDownloadTimeStamp(), isRedownload = true)
        }

        b.radioMax.setOnCheckedChangeListener(null)
        b.radioMax.setOnClickListener {
            if (b.radioMax.isChecked) {
                io { appConfig.switchRethinkDnsToMax() }
                updateRethinkRadioUi(isMax = true)
            }
        }

        b.radioSky.setOnCheckedChangeListener(null)
        b.radioSky.setOnClickListener {
            if (b.radioSky.isChecked) {
                io { appConfig.switchRethinkDnsToSky() }
                updateRethinkRadioUi(isMax = false)
            }
        }
    }

    private fun updateRethinkRadioUi(isMax: Boolean) {
        if (isMax) {
            b.radioMax.isChecked = true
            b.radioSky.isChecked = false
            b.frlDesc.text = getString(R.string.rethink_max_desc)
        } else {
            b.radioSky.isChecked = true
            b.radioMax.isChecked = false
            b.frlDesc.text = getString(R.string.rethink_sky_desc)
        }
    }

    private fun download(timestamp: Long, isRedownload: Boolean) {
        io {
            val initiated = appDownloadManager.downloadRemoteBlocklist(timestamp, isRedownload)
            uiCtx {
                if (!initiated) {
                    onRemoteDownloadFailure()
                }
            }
        }
    }

    private fun initObservers() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)
        // observer for custom download manager worker
        workManager
            .getWorkInfosByTagLiveData(RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER)
            .observe(viewLifecycleOwner) { workInfoList ->
                val workInfo = workInfoList?.getOrNull(0) ?: return@observe
                Logger.i(
                    Logger.LOG_TAG_DOWNLOAD,
                    "WorkManager state: ${workInfo.state} for ${RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER}"
                )
                if (
                    WorkInfo.State.ENQUEUED == workInfo.state ||
                        WorkInfo.State.RUNNING == workInfo.state
                ) {
                    // no-op
                } else if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                    hideProgress()
                    onDownloadSuccess()
                    workManager.pruneWork()
                } else if (
                    WorkInfo.State.CANCELLED == workInfo.state ||
                        WorkInfo.State.FAILED == workInfo.state
                ) {
                    hideProgress()
                    onRemoteDownloadFailure()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.blocklist_update_check_failure),
                        Toast.LENGTH_SHORT
                    )
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(LocalBlocklistCoordinator.CUSTOM_DOWNLOAD)
                } else { // state == blocked
                    // no-op
                }
            }

        appDownloadManager.downloadRequired.observe(viewLifecycleOwner) {
            Logger.i(Logger.LOG_TAG_DNS, "Check for blocklist update, status: $it")
            if (it == null) return@observe

            when (it) {
                AppDownloadManager.DownloadManagerStatus.NOT_STARTED -> {
                    // no-op
                }
                AppDownloadManager.DownloadManagerStatus.IN_PROGRESS -> {
                    // no-op
                }
                AppDownloadManager.DownloadManagerStatus.NOT_AVAILABLE -> {
                    // TODO: prompt user for app update
                    Utilities.showToastUiCentered(
                        requireContext(),
                        "Download latest version to update the blocklists",
                        Toast.LENGTH_SHORT
                    )
                    hideProgress()
                }
                AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED -> {
                    hideProgress()
                    showRedownloadUi()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.blocklist_update_check_not_required),
                        Toast.LENGTH_SHORT
                    )
                    appDownloadManager.downloadRequired.postValue(
                        AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                    )
                }
                AppDownloadManager.DownloadManagerStatus.FAILURE -> {
                    hideProgress()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.blocklist_update_check_failure),
                        Toast.LENGTH_SHORT
                    )
                    appDownloadManager.downloadRequired.postValue(
                        AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                    )
                }
                AppDownloadManager.DownloadManagerStatus.SUCCESS -> {
                    hideProgress()
                    showNewUpdateUi()
                    appDownloadManager.downloadRequired.postValue(
                        AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                    )
                }
                AppDownloadManager.DownloadManagerStatus.STARTED -> {
                    // no-op
                }
            }
        }
    }

    private fun showNewUpdateUi() {
        enableChips()
        b.bslbUpdateAvailableBtn.visibility = View.VISIBLE
        b.bslbCheckUpdateBtn.visibility = View.GONE
    }

    private fun showRedownloadUi() {
        enableChips()
        b.bslbUpdateAvailableBtn.visibility = View.GONE
        b.bslbCheckUpdateBtn.visibility = View.GONE
        b.bslbRedownloadBtn.visibility = View.VISIBLE
    }

    private fun onRemoteDownloadFailure() {
        enableChips()
    }

    private fun enableChips() {
        b.bslbUpdateAvailableBtn.isEnabled = true
        b.bslbCheckUpdateBtn.isEnabled = true
        b.bslbRedownloadBtn.isEnabled = true
    }

    private fun onDownloadSuccess() {
        b.lbVersion.text =
            getString(
                R.string.settings_local_blocklist_version,
                Utilities.convertLongToTime(getDownloadTimeStamp(), Constants.TIME_FORMAT_2)
            )
        enableChips()
        showRedownloadUi()
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.download_update_dialog_message_success),
            Toast.LENGTH_SHORT
        )
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
