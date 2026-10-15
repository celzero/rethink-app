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

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.MutableStateFlow
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.blocklists.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.filter
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator.Companion.CUSTOM_DOWNLOAD
import com.celzero.bravedns.customdownloader.RemoteBlocklistCoordinator
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.RethinkBlocklistManager.RethinkBlocklistType.Companion.getType
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_NAME
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_URL
import com.celzero.bravedns.ui.bottomsheet.RethinkPlusFilterBottomSheet
import com.celzero.bravedns.util.Constants.Companion.DEAD_PACK
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.Utilities.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.hasRemoteBlocklists
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.LocalBlocklistPacksMapViewModel
import com.celzero.bravedns.viewmodel.RemoteBlocklistPacksMapViewModel
import com.celzero.bravedns.viewmodel.RethinkBlocklistViewModel
import com.celzero.bravedns.viewmodel.RethinkLocalFileTagViewModel
import com.celzero.bravedns.viewmodel.RethinkRemoteFileTagViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkBlocklistFragment :
    Fragment() {
    private var b = BlocklistConfigurationState()
    private val packs = MutableStateFlow<PagingData<BlocklistRow>>(PagingData.empty())
    private val advanced = MutableStateFlow<PagingData<BlocklistRow>>(PagingData.empty())
    private lateinit var presenter: BlocklistSelectionPresenter
    private var advanceRemoteInitialized = false
    private var advanceLocalInitialized = false

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()
    private val appScope by inject<CoroutineScope>()

    private val viewModel: RethinkBlocklistViewModel by viewModel()


    // Guards the one-time configure() call into the (surviving) ViewModel so that
    // repeated onCreateView() invocations don't reset the user's in-progress selection.
    private var blocklistConfigured = false

    private val remoteFileTagViewModel: RethinkRemoteFileTagViewModel by viewModel()
    private val localFileTagViewModel: RethinkLocalFileTagViewModel by viewModel()
    private val remoteBlocklistPacksMapViewModel: RemoteBlocklistPacksMapViewModel by viewModel()
    private val localBlocklistPacksMapViewModel: LocalBlocklistPacksMapViewModel by viewModel()

    private enum class BlocklistView(val tag: String) {
        PACKS("1"),
        ADVANCED("2");

        fun isSimple() = this == PACKS

        companion object {
            fun getTag(tag: String): BlocklistView {
                return if (tag == PACKS.tag) {
                    PACKS
                } else {
                    ADVANCED
                }
            }
        }
    }

    fun updateFileTagList(fileTags: Set<Int>) {
        viewModel.updateSelectedFileTags(fileTags)
    }

    fun getSelectedFileTags(): Set<Int> {
        return viewModel.selectedFileTags.value ?: emptySet()
    }

    companion object {
        fun newInstance() = RethinkBlocklistFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val bundle = this.arguments
        val type =
            getType(
                bundle?.getInt(
                    RETHINK_BLOCKLIST_TYPE,
                    RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
                ) ?: RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
            )
        val remoteName = bundle?.getString(RETHINK_BLOCKLIST_NAME, "") ?: ""
        val remoteUrl = bundle?.getString(RETHINK_BLOCKLIST_URL, "") ?: ""
        // onCreateView() is re-invoked on view recreation while the ViewModel
        // (and its session state) survives. Avoid re-calling configure here so
        // the user's in-progress stamp/tag selection is not reset. The ViewModel
        // itself also guards against duplicate initialization.
        if (!blocklistConfigured) {
            blocklistConfigured = true
            viewModel.configure(type, remoteName, remoteUrl)
        }
        b = BlocklistConfigurationState()
        presenter = BlocklistSelectionPresenter(inflater.context, this)
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { BlocklistConfigurationScreen(b, packs, advanced, viewLifecycleOwner,
                onTab = ::selectTab, onQueryChange = ::onQueryTextChange, onQuerySubmit = ::onQueryTextSubmit,
                onFilter = ::applyFilter) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("blocklist_tab_advanced", b.selectedAdvanced)
        outState.putString("blocklist_query", b.query)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.let {
            selectTab(it.getBoolean("blocklist_tab_advanced", false))
            val query = it.getString("blocklist_query", "") ?: ""
            if (b.query != query) { b.query = query; onQueryTextChange(query) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // a download-error dialog shown against this activity must not outlive the view,
        // else the activity's window is torn down with the dialog attached (WindowLeaked)
        b.prompt = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.v(LOG_TAG_UI, "init Rethink blocklist fragment")
        init()
        initObservers()
        initClickListeners()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initObservers() {
        appDownloadManager.observeWorkManager(viewLifecycleOwner)

        appDownloadManager.downloadState.observe(viewLifecycleOwner) {
            handleDownloadState(it)
        }

        viewModel.selectedFileTags.observe(viewLifecycleOwner) {
            b.selectedTags = it ?: emptySet()
        }

        viewModel.filters.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            if (viewModel.isRemote()) {
                remoteFileTagViewModel.setFilter(it)
            } else {
                localFileTagViewModel.setFilter(it)
            }
            updateFilteredTxtUi(it)
        }
    }

    private fun handleDownloadState(state: AppDownloadManager.DownloadState) {
        when (state) {
            is AppDownloadManager.DownloadState.Idle -> {
                b.lbDownloadBtn.isEnabled = true
                hasBlocklist()
            }
            is AppDownloadManager.DownloadState.Starting -> {
                onDownloadStart()
                b.lbDownloadProgress.isIndeterminate = true
            }
            is AppDownloadManager.DownloadState.Downloading -> {
                onDownloadStart()
                b.lbDownloadProgress.isIndeterminate = false
                b.lbDownloadProgress.progress = state.progress
                b.lbDownloadBtn.text = getString(R.string.download_progress_format, getString(R.string.rt_download), state.progress)
            }
            is AppDownloadManager.DownloadState.Processing -> {
                onDownloadStart()
                b.lbDownloadProgress.isIndeterminate = true
                b.lbDownloadBtn.text = getString(R.string.notif_download_content_text)
            }
            is AppDownloadManager.DownloadState.Success -> {
                onDownloadSuccess()
            }
            is AppDownloadManager.DownloadState.Error -> {
                onDownloadFail(state.reason)
            }
        }
    }

    private fun init() {
        val typeName =
            if (viewModel.isLocal()) {
                getString(R.string.lbl_on_device)
            } else {
                getString(R.string.rdns_plus)
            }
        b.lbBlocklistApplyBtn.text =
            getString(R.string.ct_ip_details, getString(R.string.lbl_apply), typeName)

        // update ui based on blocklist availability
        hasBlocklist()

        // A terminal state (Error/Success) left over from a cancelled download or a
        // previous attempt must not pop the error dialog when this screen is reopened.
        maybeResetStaleTerminalDownloadState()

        // be default, select the simple blocklist view
        b.selectedAdvanced = false

        remakeFilterChipsUi()
    }

    private fun updateFilteredTxtUi(filter: RethinkBlocklistViewModel.Filters) {
        b.lbAdvancedFilterLabelTv.text = if (filter.subGroups.isEmpty()) {
            getString(R.string.rt_filter_desc, filter.filterSelected.name.lowercase())
        } else {
            getString(R.string.rt_filter_desc_subgroups, filter.filterSelected.name.lowercase(), "", filter.subGroups)
        }
    }

    private fun hasBlocklist() {
        go {
            // downloadState is a sticky, process-wide LiveData; it can be left in an
            // in-flight state when a download (started from, say, LocalBlocklistsBottomSheet)
            // finished while no screen observing observeWorkManager() was alive. Reconcile
            // it against actual WorkManager state (both calls do blocking queries: keep
            // them off the main thread) before deciding which UI to show.
            withContext(Dispatchers.IO) {
                appDownloadManager.reconcileStaleInFlightDownloadState(downloadType())
            }
            val isDownloadWorkActive =
                withContext(Dispatchers.IO) { appDownloadManager.isDownloadWorkActive(downloadType()) }
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.IO) { hasBlocklists() }
                // trust actual WorkManager state over the possibly-stale sticky LiveData
                val isDownloadRunning = isDownloadWorkActive

                if (blocklistsExist && !isDownloadRunning) {
                    setListAdapter()
                    setSimpleAdapter()
                    showConfigureUi()
                    hideDownloadUi()
                    return@uiCtx
                }

                showDownloadUi()
                hideConfigureUi()
            }
        }
    }

    private fun hasBlocklists(): Boolean {
        return if (viewModel.isLocal()) {
            hasLocalBlocklists(requireContext(), persistentState.localBlocklistTimestamp)
        } else {
            hasRemoteBlocklists(requireContext(), persistentState.remoteBlocklistTimestamp)
        }
    }

    private fun showDownloadUi() {
        if (viewModel.isLocal()) {
            b.lbDownloadLayout.visible = true
        } else {
            b.lbDownloadProgressRemote.visible = true
            // Only auto-start the remote download the first time (or if no download is already
            // active). This prevents re-triggering the download on every recreation / Idle
            // state transition / failure, which would otherwise start duplicate downloads.
            if (!isRemoteDownloadActive()) {
                downloadBlocklist(viewModel.getType())
            }
        }
    }

    private fun showConfigureUi() {
        b.lbConfigureLayout.visible = true
    }

    private fun hideDownloadUi() {
        b.lbDownloadLayout.visible = false
        b.lbDownloadProgressRemote.visible = false
    }

    private fun hideConfigureUi() {
        b.lbConfigureLayout.visible = false
    }

    private fun initClickListeners() {
        b.lbDownloadBtn.onClick = {
            b.lbDownloadBtn.isEnabled = false
            b.lbDownloadBtn.isClickable = false

            downloadBlocklist(viewModel.getType())
        }

        b.lbCancelDownloadBtn.onClick = {
            cancelDownload()
            // the screen is going away; clear the sticky terminal state the cancellation
            // produces so no later screen re-delivers it as an error dialog
            appDownloadManager.resetDownloadState()
            activity?.finish()
        }

        b.lbBlocklistApplyBtn.onClick = {
            val a = activity
            appScope.launch {
                viewModel.applyStamp()
                uiCtx { a?.finish() }
            }
        }

        b.lbBlocklistCancelBtn.onClick = {
            // close the activity associated with the fragment after reverting to old stamp
            val a = activity
            appScope.launch {
                viewModel.revertStamp()
                uiCtx { a?.finish() }
            }
        }


        b.lbAdvSearchFilterIcon.onClick = { openFilterBottomSheet() }


        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            // fixme: show dialog if the user selects/unselects from the list and try to close
            // the fragment before saving

            if (!viewModel.isStampChanged()) {
                activity?.finish()
                return@addCallback
            }

            showApplyChangesDialog()
        }
    }

    private fun downloadType(): RethinkBlocklistManager.DownloadType {
        return if (viewModel.isLocal()) {
            RethinkBlocklistManager.DownloadType.LOCAL
        } else {
            RethinkBlocklistManager.DownloadType.REMOTE
        }
    }

    private fun cancelDownload() {
        // cancel the blocklist download for the type this screen shows
        appDownloadManager.cancelDownload(type = downloadType())
    }

    private fun maybeResetStaleTerminalDownloadState() {
        val state = appDownloadManager.downloadState.value ?: return
        if (
            state !is AppDownloadManager.DownloadState.Error &&
                state !is AppDownloadManager.DownloadState.Success
        ) {
            return
        }

        // only clear when no download work is actually active; otherwise the live
        // observers will re-drive the UI from the real worker states anyway
        val ctx = requireContext()
        val active =
            WorkScheduler.isWorkScheduled(ctx, DOWNLOAD_TAG) ||
                WorkScheduler.isWorkScheduled(ctx, FILE_TAG) ||
                WorkScheduler.isWorkScheduled(ctx, CUSTOM_DOWNLOAD) ||
                WorkScheduler.isWorkScheduled(ctx, RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER)

        if (!active) {
            appDownloadManager.resetDownloadState()
        }
    }

    private fun isRemoteDownloadActive(): Boolean {
        return WorkScheduler.isWorkScheduled(
            requireContext(),
            RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER
        ) ||
            WorkScheduler.isWorkRunning(
                requireContext(),
                RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER
            )
    }

    private fun downloadBlocklist(type: RethinkBlocklistManager.RethinkBlocklistType) {
        // Check if VPN is in lockdown mode and custom download manager is disabled
        if (VpnController.isVpnLockdown() && !persistentState.useCustomDownloadManager) {
            showLockdownDownloadDialog(type)
            return
        }

        // Downloads are only allowed while the VPN is on, and while the VPN is the
        // default network the system download manager's JobScheduler jobs never dispatch
        // (downloads sit in STATUS_PENDING forever; see
        // PersistentState.useCustomDownloadManager). Fall back to the in-app downloader
        // for this attempt instead of starting a download that cannot proceed. Local
        // downloads only: remote blocklists always use the in-app worker.
        var forceInApp = false
        if (type.isLocal() && !persistentState.useCustomDownloadManager && VpnController.hasTunnel()) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.download_inapp_vpn_toast),
                Toast.LENGTH_SHORT
            )
            forceInApp = true
        }

        proceedWithBlocklistDownload(forceInApp)
    }

    private fun showLockdownDownloadDialog(type: RethinkBlocklistManager.RethinkBlocklistType) {
        b.prompt = BlocklistPrompt(getString(R.string.lockdown_download_enable_inapp), getString(R.string.lockdown_download_message),
            getString(R.string.lockdown_download_enable_inapp), {
                persistentState.useCustomDownloadManager = true
                downloadBlocklist(type)
            }, getString(R.string.lbl_cancel), { proceedWithBlocklistDownload() })
    }

    private fun proceedWithBlocklistDownload(forceInApp: Boolean = false) {
        ui {
            if (viewModel.isLocal()) {
                var status = AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                ioCtx {
                    status =
                        appDownloadManager.downloadLocalBlocklist(
                            persistentState.localBlocklistTimestamp,
                            isRedownload = false,
                            forceInApp
                        )
                }
                handleDownloadStatus(status)
            } else { // remote blocklist
                // default remote download will happen from rethink-dns list screen
                // check RethinkListFragment.kt
                // if it enters this block, download the blocklist regardless of the timestamp
                ioCtx {
                    appDownloadManager.downloadRemoteBlocklist(
                        persistentState.remoteBlocklistTimestamp,
                        isRedownload = true
                    )
                }
                // UI state is driven by the appDownloadManager.downloadState observer
                // (Starting -> Downloading -> Processing -> Success/Error). Do not hide the
                // progress bar or re-run hasBlocklist() here, as that would discard the
                // in-flight download state on recreation.
            }
        }
    }

    private fun handleDownloadStatus(status: AppDownloadManager.DownloadManagerStatus) {
        when (status) {
            AppDownloadManager.DownloadManagerStatus.IN_PROGRESS -> {
                // no-op
            }
            AppDownloadManager.DownloadManagerStatus.STARTED -> {
                // the job of download status stops after initiating the work manager observer
            }
            AppDownloadManager.DownloadManagerStatus.NOT_STARTED -> {
                // no-op
            }
            AppDownloadManager.DownloadManagerStatus.SUCCESS -> {
                // no-op
                // as the download initiated is tracked with this status
                // download complete status will be from coroutine worker.
                // the job of download status stops after initiating the work manager observer
            }
            AppDownloadManager.DownloadManagerStatus.FAILURE -> {
                // The actual failure UI is driven by the AppDownloadManager.downloadState
                // observer (AppDownloadManager posts DownloadState.Error on early failures,
                // and the WorkManager observers post it for worker failures). Handling it
                // here too would show a duplicate error dialog.
                // But a FAILURE can also arrive with no Error posted at all (eg, the
                // "download already in progress" guard in AppDownloadManager); the click
                // listener disabled the download button before this call, so re-enable it
                // or the UI goes dead with no message until the process restarts.
                ui {
                    b.lbDownloadBtn.isEnabled = true
                    b.lbDownloadBtn.isClickable = true
                }
            }
            AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED -> {
                // no-op, no need to update any ui in this screen
            }
            AppDownloadManager.DownloadManagerStatus.NOT_AVAILABLE -> {
                // TODO: Prompt for app update
                showToastUiCentered(
                    requireContext(),
                    "Download latest version to update the blocklists",
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun showApplyChangesDialog() {
        b.prompt = BlocklistPrompt(getString(R.string.rt_dialog_title), getString(R.string.rt_dialog_message), getString(R.string.lbl_apply), {
            val a = activity
            appScope.launch {
                viewModel.applyStamp()
                uiCtx { a?.finish() }
            }
        }, getString(R.string.notif_dialog_pause_dialog_negative), { activity?.finish() }, getString(R.string.rt_dialog_neutral), {})
    }

    private fun selectTab(advanced: Boolean) {
        if (b.selectedAdvanced == advanced) return
        b.selectedAdvanced = advanced
        showList(if (advanced) BlocklistView.ADVANCED.tag else BlocklistView.PACKS.tag)
    }

    private fun showList(id: String) { b.showAdvanced = !BlocklistView.getTag(id).isSimple() }





    private fun setListAdapter() {
        ui {
            if (viewModel.isLocal()) {
                setLocalAdapter()
            } else {
                setRemoteAdapter()
            }
            showList(BlocklistView.PACKS.tag)
        }
    }



    private fun setSimpleAdapter() {
        if (viewModel.isLocal()) {
            setLocalSimpleViewAdapter()
        } else {
            setRemoteSimpleViewAdapter()
        }
    }

    fun onQueryTextSubmit(query: String): Boolean {
        if (viewModel.isRethinkStampSearch(query)) {
            viewModel.restoreStamp(query)
            showToastUiCentered(requireContext(), "Blocklists restored", Toast.LENGTH_SHORT)
            return true
        }
        viewModel.addQueryToFilters(query)
        return false
    }

    fun onQueryTextChange(query: String): Boolean {
        if (viewModel.isRethinkStampSearch(query)) {
            return false
        }
        viewModel.addQueryToFilters(query)
        return false
    }

    fun filterObserver(): MutableLiveData<RethinkBlocklistViewModel.Filters> {
        return viewModel.filters
    }

    private fun setLocalSimpleViewAdapter() {
        localBlocklistPacksMapViewModel.simpleTags.observe(viewLifecycleOwner) {
            val filtered = it.filter { item -> !item.pack.contains(DEAD_PACK) && item.pack.isNotEmpty() }
            packs.value = filtered.map { item -> presenter.localPack(item) }
        }
    }

    private fun setRemoteSimpleViewAdapter() {
        remoteBlocklistPacksMapViewModel.simpleTags.observe(viewLifecycleOwner) {
            val filtered = it.filter { item -> !item.pack.contains(DEAD_PACK) && item.pack.isNotEmpty() }
            packs.value = filtered.map { item -> presenter.remotePack(item) }
        }
    }

    private fun remakeFilterChipsUi() { b.selectionFilter = RethinkBlocklistViewModel.BlocklistSelectionFilter.ALL.id }



    private fun applyFilter(tag: Any) {
        if (tag is Int) {
            viewModel.applySelectionFilter(tag)
        }
    }

    private fun openFilterBottomSheet() {
        io {
            val bottomSheetFragment = RethinkPlusFilterBottomSheet.newInstance(this, getAllList())
            uiCtx { bottomSheetFragment.show(childFragmentManager, bottomSheetFragment.tag) }
        }
    }

    private suspend fun getAllList(): List<FileTag> {
        return if (viewModel.isLocal()) {
            localFileTagViewModel.allFileTags()
        } else {
            remoteFileTagViewModel.allFileTags()
        }
    }

    private fun setRemoteAdapter() {
        if (advanceRemoteInitialized) return
        advanceRemoteInitialized = true
        remoteFileTagViewModel.remoteFileTags.observe(viewLifecycleOwner) {
            advanced.value = it.map { item -> presenter.remoteTag(item) }
        }
    }

    private fun setLocalAdapter() {
        if (advanceLocalInitialized) return
        advanceLocalInitialized = true
        localFileTagViewModel.localFiletags.observe(viewLifecycleOwner) {
            advanced.value = it.map { item -> presenter.localTag(item) }
        }
    }


    private fun onDownloadStart() {
        // update ui for download start
        showDownloadUi()
        b.lbDownloadProgress.visible = true
        b.lbDownloadBtn.text = getString(R.string.rt_download_start)
        hideConfigureUi()
    }

    private fun onDownloadFail(reason: String? = null) {
        // update ui for download fail
        b.lbDownloadProgress.visible = false
        b.lbDownloadProgressRemote.visible = false
        b.lbDownloadBtn.visible = true
        b.lbDownloadBtn.isEnabled = true
        b.lbDownloadBtn.text = getString(R.string.rt_download)
        showDownloadUi()
        hideConfigureUi()

        if (reason != null) {
            showDownloadErrorDialog(reason)
        }
    }

    // tracked so the dialog can be dismissed when this view tears down; otherwise an
    // Error delivered while the activity is finishing leaks the dialog's window
    // (android.view.WindowLeaked)

    private fun showDownloadErrorDialog(reason: String) {
        val a = activity
        if (a == null || !isAdded || a.isFinishing || a.isDestroyed) return
        val custom = persistentState.useCustomDownloadManager
        b.prompt = BlocklistPrompt(getString(R.string.download_update_dialog_failure_title), getString(R.string.download_update_dialog_failure_message) + "\n\n" + reason,
            getString(R.string.retry), {
                appDownloadManager.resetDownloadState()
                downloadBlocklist(viewModel.getType())
            }, getString(R.string.lbl_cancel), { appDownloadManager.resetDownloadState() },
            getString(if (custom) R.string.download_switch_to_system else R.string.settings_custom_downloader_heading), {
                appDownloadManager.cancelDownload(type = downloadType())
                persistentState.useCustomDownloadManager = !custom
                appDownloadManager.resetDownloadState()
                downloadBlocklist(viewModel.getType())
            })
    }

    private fun onDownloadSuccess() {
        // update ui for download success
        b.lbDownloadProgress.visible = false
        b.lbDownloadProgressRemote.visible = false
        b.lbDownloadBtn.text = getString(R.string.rt_download)
        hideDownloadUi()
        appDownloadManager.resetDownloadState()
        // hasBlocklist() is triggered by resetDownloadState -> Idle
        selectTab(false)
        showToastUiCentered(
            requireContext(),
            getString(R.string.download_update_dialog_message_success),
            Toast.LENGTH_SHORT
        )
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.Main) { f() } }
    }
}
