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
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.filter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.LocalAdvancedViewAdapter
import com.celzero.bravedns.adapter.LocalSimpleViewAdapter
import com.celzero.bravedns.adapter.RemoteAdvancedViewAdapter
import com.celzero.bravedns.adapter.RemoteSimpleViewAdapter
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator.Companion.CUSTOM_DOWNLOAD
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.FragmentRethinkBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkBlocklistFragment :
    Fragment(R.layout.fragment_rethink_blocklist), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentRethinkBlocklistBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    private val viewModel: RethinkBlocklistViewModel by viewModel()

    private var advanceRemoteViewAdapter: RemoteAdvancedViewAdapter? = null
    private var advanceLocalViewAdapter: LocalAdvancedViewAdapter? = null
    private var localSimpleViewAdapter: LocalSimpleViewAdapter? = null
    private var remoteSimpleViewAdapter: RemoteSimpleViewAdapter? = null

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
        viewModel.configure(type, remoteName, remoteUrl)
        return super.onCreateView(inflater, container, savedInstanceState)
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
        if (viewModel.isLocal()) {
            observeWorkManager()
        }

        viewModel.selectedFileTags.observe(viewLifecycleOwner) {
            localSimpleViewAdapter?.notifyDataSetChanged()
            remoteSimpleViewAdapter?.notifyDataSetChanged()
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

        // be default, select the simple blocklist view
        selectToggleBtnUi(b.lbSimpleToggleBtn)
        unselectToggleBtnUi(b.lbAdvToggleBtn)

        remakeFilterChipsUi()
    }

    private fun updateFilteredTxtUi(filter: RethinkBlocklistViewModel.Filters) {
        if (filter.subGroups.isEmpty()) {
            b.lbAdvancedFilterLabelTv.text =
                htmlToSpannedText(
                    getString(R.string.rt_filter_desc, filter.filterSelected.name.lowercase())
                )
        } else {
            b.lbAdvancedFilterLabelTv.text =
                htmlToSpannedText(
                    getString(
                        R.string.rt_filter_desc_subgroups,
                        filter.filterSelected.name.lowercase(),
                        "",
                        filter.subGroups
                    )
                )
        }
    }

    private fun hasBlocklist() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.IO) { hasBlocklists() }
                if (blocklistsExist) {
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
            b.lbDownloadLayout.visibility = View.VISIBLE
        } else {
            b.lbDownloadProgressRemote.visibility = View.VISIBLE
            downloadBlocklist(viewModel.getType())
        }
    }

    private fun showConfigureUi() {
        b.lbConfigureLayout.visibility = View.VISIBLE
    }

    private fun hideDownloadUi() {
        b.lbDownloadLayout.visibility = View.GONE
        b.lbDownloadProgressRemote.visibility = View.GONE
    }

    private fun hideConfigureUi() {
        b.lbConfigureLayout.visibility = View.GONE
    }

    private fun initClickListeners() {
        b.lbDownloadBtn.setOnClickListener {
            b.lbDownloadBtn.isEnabled = false
            b.lbDownloadBtn.isClickable = false

            downloadBlocklist(viewModel.getType())
        }

        b.lbCancelDownloadBtn.setOnClickListener {
            cancelDownload()
            requireActivity().finish()
        }

        b.lbBlocklistApplyBtn.setOnClickListener {
            viewModel.applyStamp()
            requireActivity().finish()
        }

        b.lbBlocklistCancelBtn.setOnClickListener {
            // close the activity associated with the fragment after reverting to old stamp
            viewModel.revertStamp()
            requireActivity().finish()
        }

        b.lbListToggleGroup.addOnButtonCheckedListener(listViewToggleListener)

        b.lbAdvSearchFilterIcon.setOnClickListener { openFilterBottomSheet() }

        b.lbAdvSearchSv.setOnQueryTextListener(this)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            // fixme: show dialog if the user selects/unselects from the list and try to close
            // the fragment before saving

            if (!viewModel.isStampChanged()) {
                requireActivity().finish()
                return@addCallback
            }

            showApplyChangesDialog()
        }
    }

    private fun cancelDownload() {
        // cancel the local blocklist download
        appDownloadManager.cancelDownload(type = RethinkBlocklistManager.DownloadType.LOCAL)
    }

    private fun downloadBlocklist(type: RethinkBlocklistManager.RethinkBlocklistType) {
        // Check if VPN is in lockdown mode and custom download manager is disabled
        if (VpnController.isVpnLockdown() && !persistentState.useCustomDownloadManager) {
            showLockdownDownloadDialog(type)
            return
        }

        proceedWithBlocklistDownload()
    }

    private fun showLockdownDownloadDialog(type: RethinkBlocklistManager.RethinkBlocklistType) {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.lockdown_download_enable_inapp)
        builder.setMessage(R.string.lockdown_download_message)
        builder.setCancelable(true)
        builder.setPositiveButton(R.string.lockdown_download_enable_inapp) { _, _ ->
            // Enable in-app downloader and proceed with download
            persistentState.useCustomDownloadManager = true
            downloadBlocklist(type)
        }
        builder.setNegativeButton(R.string.lbl_cancel) { dialog, _ ->
            dialog.dismiss()
            // Proceed with Android download manager (useCustomDownloadManager stays false)
            proceedWithBlocklistDownload()
        }
        builder.create().show()
    }

    private fun proceedWithBlocklistDownload() {
        ui {
            if (viewModel.isLocal()) {
                var status = AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                ioCtx {
                    status =
                        appDownloadManager.downloadLocalBlocklist(
                            persistentState.localBlocklistTimestamp,
                            isRedownload = false
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
                b.lbDownloadProgressRemote.visibility = View.GONE
                hasBlocklist()
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
                observeWorkManager()
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
                onDownloadFail()
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
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.rt_dialog_title))
        builder.setMessage(getString(R.string.rt_dialog_message))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_apply)) { _, _ ->
            viewModel.applyStamp()
            requireActivity().finish()
        }
        builder.setNeutralButton(getString(R.string.rt_dialog_neutral)) { _, _ ->
            // no-op
        }
        builder.setNegativeButton(getString(R.string.notif_dialog_pause_dialog_negative)) { _, _ ->
            requireActivity().finish()
        }
        builder.create().show()
    }

    private val listViewToggleListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            val mb: MaterialButton = b.lbListToggleGroup.findViewById(checkedId)
            if (isChecked) {
                selectToggleBtnUi(mb)
                showList(mb.tag.toString())
                return@OnButtonCheckedListener
            }

            unselectToggleBtnUi(mb)
        }

    private fun showList(id: String) {
        // change the check based on the tag
        when (BlocklistView.getTag(id)) {
            BlocklistView.PACKS -> {
                b.lbSimpleRecyclerPacks.visibility = View.VISIBLE
                b.lbAdvContainer.visibility = View.INVISIBLE
            }
            BlocklistView.ADVANCED -> {
                b.lbSimpleRecyclerPacks.visibility = View.GONE
                b.lbAdvContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun selectToggleBtnUi(mb: MaterialButton) {
        mb.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(requireContext(), R.color.accentGood))
        mb.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(mb: MaterialButton) {
        mb.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
        mb.backgroundTintList =
            ColorStateList.valueOf(
                fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg)
            )
    }

    private fun setListAdapter() {
        ui {
            if (viewModel.isLocal()) {
                setLocalAdapter()
            } else {
                setRemoteAdapter()
            }
            showList(b.lbSimpleToggleBtn.tag.toString())
        }
    }

    private fun setupRecyclerScrollListener(recycler: RecyclerView, viewType: BlocklistView) {
        val scrollListener =
            object : RecyclerView.OnScrollListener() {

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (recyclerView.getChildAt(0)?.tag == null) return

                    val tag: String = recyclerView.getChildAt(0).tag as String

                    if (viewType.isSimple()) {
                        b.recyclerScrollHeaderSimple.visibility = View.VISIBLE
                        b.recyclerScrollHeaderSimple.text = tag
                        b.recyclerScrollHeaderAdv.visibility = View.GONE
                    } else {
                        b.recyclerScrollHeaderAdv.visibility = View.VISIBLE
                        b.recyclerScrollHeaderAdv.text = tag
                        b.recyclerScrollHeaderSimple.visibility = View.GONE
                    }
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        b.recyclerScrollHeaderSimple.visibility = View.GONE
                        b.recyclerScrollHeaderAdv.visibility = View.GONE
                    }
                }
            }
        recycler.addOnScrollListener(scrollListener)
    }

    private fun setSimpleAdapter() {
        if (viewModel.isLocal()) {
            setLocalSimpleViewAdapter()
        } else {
            setRemoteSimpleViewAdapter()
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        if (viewModel.isRethinkStampSearch(query)) {
            viewModel.restoreStamp(query)
            showToastUiCentered(requireContext(), "Blocklists restored", Toast.LENGTH_SHORT)
            return true
        }
        viewModel.addQueryToFilters(query)
        return false
    }

    override fun onQueryTextChange(query: String): Boolean {
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
        localSimpleViewAdapter = LocalSimpleViewAdapter(requireContext(), this)
        val layoutManager = LinearLayoutManager(requireContext())
        b.lbSimpleRecyclerPacks.layoutManager = layoutManager

        localBlocklistPacksMapViewModel.simpleTags.observe(viewLifecycleOwner) {
            val l = it.filter { it1 -> !it1.pack.contains(DEAD_PACK) && it1.pack.isNotEmpty() }
            localSimpleViewAdapter?.submitData(viewLifecycleOwner.lifecycle, l)
        }
        b.lbSimpleRecyclerPacks.adapter = localSimpleViewAdapter
        setupRecyclerScrollListener(b.lbSimpleRecyclerPacks, BlocklistView.PACKS)
    }

    private fun setRemoteSimpleViewAdapter() {
        remoteSimpleViewAdapter = RemoteSimpleViewAdapter(requireContext(), this)
        val layoutManager = LinearLayoutManager(requireContext())
        b.lbSimpleRecyclerPacks.layoutManager = layoutManager

        remoteBlocklistPacksMapViewModel.simpleTags.observe(viewLifecycleOwner) {
            val r = it.filter { it1 -> !it1.pack.contains(DEAD_PACK) && it1.pack.isNotEmpty() }
            remoteSimpleViewAdapter?.submitData(viewLifecycleOwner.lifecycle, r)
        }
        b.lbSimpleRecyclerPacks.adapter = remoteSimpleViewAdapter
        setupRecyclerScrollListener(b.lbSimpleRecyclerPacks, BlocklistView.PACKS)
    }

    private fun remakeFilterChipsUi() {
        b.filterChipGroup.removeAllViews()

        val all = makeChip(RethinkBlocklistViewModel.BlocklistSelectionFilter.ALL.id, getString(R.string.lbl_all), true)
        val selected =
            makeChip(
                RethinkBlocklistViewModel.BlocklistSelectionFilter.SELECTED.id,
                getString(R.string.rt_filter_parent_selected),
                false
            )

        b.filterChipGroup.addView(all)
        b.filterChipGroup.addView(selected)
    }

    private fun makeChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) { // apply filter only when the CompoundButton is selected
                applyFilter(button.tag)
            }
        }

        return chip
    }

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
        if (advanceRemoteViewAdapter != null) return

        advanceRemoteViewAdapter = RemoteAdvancedViewAdapter(requireContext(), this)
        val layoutManager = LinearLayoutManager(requireContext())
        b.lbAdvancedRecycler.layoutManager = layoutManager

        remoteFileTagViewModel.remoteFileTags.observe(viewLifecycleOwner) {
            advanceRemoteViewAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }
        advanceRemoteViewAdapter?.addLoadStateListener { loadState ->
            if (loadState.refresh is LoadState.NotLoading) {
                b.lbAdvancedRecycler.scrollToPosition(0)
            }
        }
        b.lbAdvancedRecycler.adapter = advanceRemoteViewAdapter
        setupRecyclerScrollListener(b.lbAdvancedRecycler, BlocklistView.ADVANCED)

        // implement sticky headers
        // ref:
        // https://stackoverflow.com/questions/32949971/how-can-i-make-sticky-headers-in-recyclerview-without-external-lib
        /*b.lbAdvancedRecycler.addItemDecoration(HeaderItemDecoration(b.lbAdvancedRecycler) { itemPosition ->
            itemPosition >= 0 && itemPosition < advanceRemoteListAdapter?.itemCount
        })*/
    }

    private fun setLocalAdapter() {
        if (advanceLocalViewAdapter != null) return

        advanceLocalViewAdapter = LocalAdvancedViewAdapter(requireContext(), this)
        val layoutManager = LinearLayoutManager(requireContext())
        b.lbAdvancedRecycler.layoutManager = layoutManager

        localFileTagViewModel.localFiletags.observe(viewLifecycleOwner) {
            advanceLocalViewAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }
        advanceLocalViewAdapter?.addLoadStateListener { loadState ->
            if (loadState.refresh is LoadState.NotLoading) {
                b.lbAdvancedRecycler.scrollToPosition(0)
            }
        }
        b.lbAdvancedRecycler.adapter = advanceLocalViewAdapter
        setupRecyclerScrollListener(b.lbAdvancedRecycler, BlocklistView.ADVANCED)
    }

    private fun observeWorkManager() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for custom download manager worker
        workManager.getWorkInfosByTagLiveData(CUSTOM_DOWNLOAD).observe(viewLifecycleOwner) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                Logger.LOG_TAG_DOWNLOAD,
                "WorkManager state: ${workInfo.state} for $CUSTOM_DOWNLOAD"
            )
            if (
                WorkInfo.State.ENQUEUED == workInfo.state ||
                    WorkInfo.State.RUNNING == workInfo.state
            ) {
                onDownloadStart()
            } else if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                onDownloadSuccess()
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                    WorkInfo.State.FAILED == workInfo.state
            ) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(CUSTOM_DOWNLOAD)
            } else { // state == blocked
                // no-op
            }
        }

        // observer for Androids default download manager
        workManager.getWorkInfosByTagLiveData(DOWNLOAD_TAG).observe(viewLifecycleOwner) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                Logger.LOG_TAG_DOWNLOAD,
                "WorkManager state: ${workInfo.state} for $DOWNLOAD_TAG"
            )
            if (
                WorkInfo.State.ENQUEUED == workInfo.state ||
                    WorkInfo.State.RUNNING == workInfo.state
            ) {
                onDownloadStart()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                    WorkInfo.State.FAILED == workInfo.state
            ) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
                workManager.cancelAllWorkByTag(FILE_TAG)
            } else { // state == blocked, succeeded
                // no-op
            }
        }

        workManager.getWorkInfosByTagLiveData(FILE_TAG).observe(viewLifecycleOwner) { workInfoList
            ->
            if (workInfoList != null && workInfoList.isNotEmpty()) {
                val workInfo = workInfoList[0]
                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                    Logger.i(
                        Logger.LOG_TAG_DOWNLOAD,
                        "AppDownloadManager Work Manager completed - $FILE_TAG"
                    )
                    onDownloadSuccess()
                    workManager.pruneWork()
                } else if (
                    workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED
                ) {
                    onDownloadFail()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(FILE_TAG)
                    Logger.i(
                        Logger.LOG_TAG_DOWNLOAD,
                        "AppDownloadManager Work Manager failed - $FILE_TAG"
                    )
                } else {
                    Logger.i(
                        Logger.LOG_TAG_DOWNLOAD,
                        "AppDownloadManager Work Manager - $FILE_TAG, ${workInfo.state}"
                    )
                }
            }
        }
    }

    private fun onDownloadStart() {
        // update ui for download start
        showDownloadUi()
        b.lbDownloadProgress.visibility = View.VISIBLE
        b.lbDownloadBtn.text = getString(R.string.rt_download_start)
        hideConfigureUi()
    }

    private fun onDownloadFail() {
        // update ui for download fail
        b.lbDownloadProgress.visibility = View.GONE
        b.lbDownloadProgressRemote.visibility = View.GONE
        b.lbDownloadBtn.visibility = View.VISIBLE
        b.lbDownloadBtn.isEnabled = true
        b.lbDownloadBtn.text = getString(R.string.rt_download)
        showDownloadUi()
        hideConfigureUi()
    }

    private fun onDownloadSuccess() {
        // update ui for download success
        b.lbDownloadProgress.visibility = View.GONE
        b.lbDownloadProgressRemote.visibility = View.GONE
        b.lbDownloadBtn.text = getString(R.string.rt_download)
        hideDownloadUi()
        // showConfigureUi()
        hasBlocklist()
        b.lbListToggleGroup.check(R.id.lb_simple_toggle_btn)
        showToastUiCentered(
            requireContext(),
            getString(R.string.download_update_dialog_message_success),
            Toast.LENGTH_SHORT
        )
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
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
