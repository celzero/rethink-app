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

import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.paging.PagingData
import kotlinx.coroutines.flow.MutableStateFlow
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.blocklists.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator
import com.celzero.bravedns.customdownloader.RemoteBlocklistCoordinator
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.UID
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.RethinkEndpointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkListFragment : Fragment() {
    private var b = RethinkListState()
    private val pages = MutableStateFlow<PagingData<RethinkDnsEndpoint>>(PagingData.empty())

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    private val viewModel: RethinkEndpointViewModel by viewModel()

    private var uid: Int = Constants.MISSING_UID

    companion object {
        fun newInstance() = RethinkListFragment()

        private val dpToPx: Float by lazy {
            Resources.getSystem().displayMetrics.density
        }

        private val spacing4dp: Int by lazy { (4 * dpToPx).toInt() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val bundle = this.arguments
        uid = bundle?.getInt(UID, Constants.MISSING_UID) ?: Constants.MISSING_UID
        b = RethinkListState()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RethinkListScreen(b, pages, viewLifecycleOwner, requireContext(), appConfig) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBooleanArray("rethink_radio_checks", booleanArrayOf(b.radioSky.isChecked, b.radioMax.isChecked))
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.getBooleanArray("rethink_radio_checks")?.takeIf { it.size == 2 }?.let {
            b.radioSky.isChecked = it[0]
            b.radioMax.isChecked = it[1]
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
    }

    private fun showBlocklistVersionUi() {
        if (getDownloadTimeStamp() == INIT_TIME_MS) {
            b.dohFabAddServerIcon.visible = false
            b.lbVersion.visible = false
            return
        }

        b.lbVersion.text =
            getString(
                R.string.settings_local_blocklist_version,
                Utilities.convertLongToTime(getDownloadTimeStamp(), Constants.TIME_FORMAT_2)
            )
    }

    private fun showProgress(chip: RethinkChipState) { chip.isChipIconVisible = true }

    private fun hideProgress() {
        b.bslbCheckUpdateBtn.isChipIconVisible = false
        b.bslbRedownloadBtn.isChipIconVisible = false
        b.bslbUpdateAvailableBtn.isChipIconVisible = false
    }

    private fun showUpdateCheckUi() {
        if (isBlocklistUpdateAvailable()) {
            b.bslbUpdateAvailableBtn.visible = true
            b.bslbRedownloadBtn.visible = false
            b.bslbCheckUpdateBtn.visible = false
            return
        }

        b.bslbCheckUpdateBtn.visible = true
        b.bslbRedownloadBtn.visible = false
        b.bslbUpdateAvailableBtn.visible = false
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
        viewModel.setFilter(uid)
        viewModel.rethinkEndpointList.observe(viewLifecycleOwner) { pages.value = it }
    }

    private fun updateMaxSwitchUi() {
        viewLifecycleOwner.lifecycleScope.launch {
            val endpointUrl = withContext(Dispatchers.IO) { appConfig.getRethinkPlusEndpoint()?.url }
            updateRethinkRadioUi(isMax = endpointUrl?.contains(MAX_ENDPOINT) == true)
        }
    }

    private fun initClickListeners() {
        b.dohFabAddServerIcon.onClick = {
            val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
            intent.putExtra(
                RETHINK_BLOCKLIST_TYPE,
                RethinkBlocklistManager.RethinkBlocklistType.REMOTE
            )
            requireContext().startActivity(intent)
        }

        b.bslbCheckUpdateBtn.onClick = {
            b.bslbCheckUpdateBtn.isEnabled = false
            showProgress(b.bslbCheckUpdateBtn)
            checkBlocklistUpdate()
        }

        b.bslbUpdateAvailableBtn.onClick = {
            b.bslbUpdateAvailableBtn.isEnabled = false
            val timestamp = getDownloadTimeStamp()

            showProgress(b.bslbUpdateAvailableBtn)
            download(timestamp, isRedownload = false)
        }

        b.bslbRedownloadBtn.onClick = {
            b.bslbRedownloadBtn.isEnabled = false
            showProgress(b.bslbRedownloadBtn)
            download(getDownloadTimeStamp(), isRedownload = true)
        }

        b.radioMax.onCheckedChange = null
        b.radioMax.onClick = {
            if (b.radioMax.isChecked) {
                io { appConfig.switchRethinkDnsToMax() }
                updateRethinkRadioUi(isMax = true)
            }
        }

        b.radioSky.onCheckedChange = null
        b.radioSky.onClick = {
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
                } else {
                }
            }

        appDownloadManager.downloadRequired.observe(viewLifecycleOwner) {
            Logger.i(Logger.LOG_TAG_DNS, "Check for blocklist update, status: $it")
            if (it == null) return@observe

            when (it) {
                AppDownloadManager.DownloadManagerStatus.NOT_STARTED -> {
                }
                AppDownloadManager.DownloadManagerStatus.IN_PROGRESS -> {
                }
                AppDownloadManager.DownloadManagerStatus.NOT_AVAILABLE -> {
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
                }
            }
        }
    }

    private fun showNewUpdateUi() {
        enableChips()
        b.bslbUpdateAvailableBtn.visible = true
        b.bslbCheckUpdateBtn.visible = false
    }

    private fun showRedownloadUi() {
        enableChips()
        b.bslbUpdateAvailableBtn.visible = false
        b.bslbCheckUpdateBtn.visible = false
        b.bslbRedownloadBtn.visible = true
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

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }
}