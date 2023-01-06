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

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkLocalAdvancedViewAdapter
import com.celzero.bravedns.adapter.RethinkRemoteAdvancedViewAdapter
import com.celzero.bravedns.adapter.RethinkSimpleViewPacksAdapter
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator.Companion.CUSTOM_DOWNLOAD
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.FragmentRethinkBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager.RethinkBlocklistType.Companion.getType
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_NAME
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_URL
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Constants.Companion.RETHINK_STAMP_VERSION
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities.Companion.getRemoteBlocklistStamp
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.Companion.hasRemoteBlocklists
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.celzero.bravedns.viewmodel.RethinkLocalFileTagViewModel
import com.celzero.bravedns.viewmodel.RethinkRemoteFileTagViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.regex.Pattern

class RethinkBlocklistFragment :
    Fragment(R.layout.fragment_rethink_blocklist), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentRethinkBlocklistBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()
    private val appConfig by inject<AppConfig>()

    private var type: RethinkBlocklistManager.RethinkBlocklistType = RethinkBlocklistManager.RethinkBlocklistType.REMOTE
    private var remoteName: String = ""
    private var remoteUrl: String = ""

    private val filters = MutableLiveData<Filters>()

    private var advanceRemoteListAdapter: RethinkRemoteAdvancedViewAdapter? = null
    private var advanceLocalListAdapter: RethinkLocalAdvancedViewAdapter? = null
    private var simplePacksListAdapter: RethinkSimpleViewPacksAdapter? = null

    private val remoteFileTagViewModel: RethinkRemoteFileTagViewModel by viewModel()
    private val localFileTagViewModel: RethinkLocalFileTagViewModel by viewModel()

    enum class BlocklistSelectionFilter(val id: Int) {
        ALL(0),
        SELECTED(1)
    }

    class Filters {
        var query: String = "%%"
        var filterSelected: BlocklistSelectionFilter = BlocklistSelectionFilter.ALL
        var groups: MutableSet<String> = mutableSetOf()
        var subGroups: MutableSet<String> = mutableSetOf()
    }

    enum class BlocklistView(val tag: String) {
        PACKS("1"),
        ADVANCED("2");

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

    companion object {
        fun newInstance() = RethinkBlocklistFragment()
        var modifiedStamp: String = ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val bundle = this.arguments
        type =
            getType(
                bundle?.getInt(RETHINK_BLOCKLIST_TYPE, RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal)
                    ?: RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
            )
        remoteName = bundle?.getString(RETHINK_BLOCKLIST_NAME, "") ?: ""
        remoteUrl = bundle?.getString(RETHINK_BLOCKLIST_URL, "") ?: ""
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
        initObservers()
        initClickListeners()
    }

    private fun initObservers() {
        if (type.isLocal()) {
            observeWorkManager()
        }

        filters.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            if (type.isRemote()) {
                remoteFileTagViewModel.setFilter(it)
                b.lbAdvancedRecycler.smoothScrollToPosition(0)
            } else {
                localFileTagViewModel.setFilter(it)
                b.lbAdvancedRecycler.smoothScrollToPosition(0)
            }
            updateFilteredTxtUi(it)
        }
    }

    private fun init() {
        modifiedStamp = getStamp()

        // update ui based on blocklist availability
        hasBlocklist()

        // be default, select the simple blocklist view
        selectToggleBtnUi(b.lbSimpleToggleBtn)
        unselectToggleBtnUi(b.lbAdvToggleBtn)

        remakeFilterChipsUi()
    }

    private fun updateFilteredTxtUi(filter: Filters) {
        if (filter.groups.isEmpty()) {
            b.lbAdvancedFilterLabelTv.text =
                Utilities.updateHtmlEncodedText(
                    getString(R.string.rt_filter_desc, filter.filterSelected.name.lowercase())
                )
        } else {
            if (filter.subGroups.isEmpty()) {
                b.lbAdvancedFilterLabelTv.text =
                    Utilities.updateHtmlEncodedText(
                        getString(
                            R.string.rt_filter_desc_groups,
                            filter.filterSelected.name.lowercase(),
                            filter.groups
                        )
                    )
            } else {
                b.lbAdvancedFilterLabelTv.text =
                    Utilities.updateHtmlEncodedText(
                        getString(
                            R.string.rt_filter_desc_subgroups,
                            filter.filterSelected.name.lowercase(),
                            filter.groups,
                            filter.subGroups
                        )
                    )
            }
        }
    }

    private fun hasBlocklist() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.IO) { hasBlocklists() }
                if (blocklistsExist) {
                    RethinkBlocklistManager.createBraveDns(
                        requireContext(),
                        currentBlocklistTimeStamp(),
                        type
                    )
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

    private fun hasBlocklists(): Boolean {
        return if (type.isLocal()) {
            hasLocalBlocklists(requireContext(), persistentState.localBlocklistTimestamp)
        } else {
            hasRemoteBlocklists(requireContext(), persistentState.remoteBlocklistTimestamp)
        }
    }

    private fun currentBlocklistTimeStamp(): Long {
        return if (type.isLocal()) {
            persistentState.localBlocklistTimestamp
        } else {
            persistentState.remoteBlocklistTimestamp
        }
    }

    private fun showDownloadUi() {
        if (type.isLocal()) {
            b.lbDownloadLayout.visibility = View.VISIBLE
        } else {
            b.lbDownloadProgressRemote.visibility = View.VISIBLE
            downloadBlocklist(type)
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

    private fun isStampChanged(): Boolean {
        // user modified the blocklists
        return getStamp() != modifiedStamp
    }

    private fun initClickListeners() {
        b.lbDownloadBtn.setOnClickListener {
            b.lbDownloadBtn.isEnabled = false
            b.lbDownloadBtn.isClickable = false

            downloadBlocklist(type)
        }

        b.lbCancelDownloadBtn.setOnClickListener {
            cancelDownload()
            requireActivity().finish()
        }

        b.lbBlocklistApplyBtn.setOnClickListener {
            // update rethink stamp
            setStamp(modifiedStamp)
            clearSelectedTags()
            requireActivity().finish()
        }

        b.lbBlocklistCancelBtn.setOnClickListener {
            // close the activity associated with the fragment
            clearSelectedTags()
            requireActivity().finish()
        }

        b.lbListToggleGroup.addOnButtonCheckedListener(listViewToggleListener)

        b.lbAdvSearchFilterIcon.setOnClickListener { openFilterBottomSheet() }

        b.lbAdvSearchSv.setOnQueryTextListener(this)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            // fixme: show dialog if the user selects/unselects from the list and try to close
            // the fragment before saving

            if (!isStampChanged()) {
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
        ui {
            if (type.isLocal()) {
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
                ioCtx {
                    appDownloadManager.downloadRemoteBlocklist(
                        persistentState.remoteBlocklistTimestamp,
                        isRedownload = false
                    )
                }
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

    private fun clearSelectedTags() {
        io {
            if (type.isRemote()) {
                RethinkBlocklistManager.clearTagsSelectionRemote()
            } else {
                RethinkBlocklistManager.clearTagsSelectionLocal()
            }
        }
    }

    private fun showApplyChangesDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.rt_dialog_title))
        builder.setMessage(getString(R.string.rt_dialog_message))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.rt_dialog_positive)) { _, _ ->
            setStamp(modifiedStamp)
            requireActivity().finish()
        }
        builder.setNeutralButton(getString(R.string.rt_dialog_neutral)) { _, _ ->
            // no-op
        }
        builder.setNegativeButton(getString(R.string.rt_dialog_negative)) { _, _ ->
            requireActivity().finish()
        }
        builder.create().show()
    }

    private fun setStamp(stamp: String?) {
        Log.i(
            LoggerConstants.LOG_TAG_VPN,
            "Rethink dns, set stamp for blocklist type: ${type.name} with $stamp"
        )
        if (stamp == null) return

        io {
            val blocklistCount =
                RethinkBlocklistManager.getTagsFromStamp(requireContext(), stamp, type).size
            if (type.isLocal()) {
                persistentState.localBlocklistStamp = stamp
                persistentState.numberOfLocalBlocklists = blocklistCount
                persistentState.blocklistEnabled = true
            } else {
                // set stamp for remote blocklist
                appConfig.updateRethinkEndpoint(
                    Constants.RETHINK_DNS_PLUS,
                    getRemoteUrl(stamp),
                    blocklistCount
                )
                appConfig.enableRethinkDnsPlus()
            }
        }
    }

    private fun getRemoteUrl(stamp: String): String {
        return if (remoteUrl.contains(MAX_ENDPOINT)) {
            Constants.RETHINK_BASE_URL_MAX + stamp
        } else {
            Constants.RETHINK_BASE_URL_SKY + stamp
        }
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
                setSimplePacksViewAdapter()
                b.lbSimpleRecyclerPacks.visibility = View.VISIBLE
                b.lbAdvContainer.visibility = View.INVISIBLE
            }
            BlocklistView.ADVANCED -> {
                b.lbSimpleRecyclerPacks.visibility = View.GONE
                b.lbAdvContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun selectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(requireContext(), R.color.accentGood))
    }

    private fun unselectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(
                fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg)
            )
    }

    private fun setListAdapter() {
        processSelectedFileTags(getStamp())

        if (type.isLocal()) {
            setLocalAdapter()
        } else {
            setRemoteAdapter()
        }
        showList(b.lbSimpleToggleBtn.tag.toString())
    }

    private fun processSelectedFileTags(stamp: String) {
        val list = RethinkBlocklistManager.getTagsFromStamp(requireContext(), stamp, type)

        updateSelectedFileTags(list.toMutableSet())
    }

    private fun updateSelectedFileTags(selectedTags: MutableSet<Int>) {
        io {
            // clear the residues if the selected tags are empty
            if (selectedTags.isEmpty()) {
                if (type.isLocal()) {
                    RethinkBlocklistManager.clearTagsSelectionLocal()
                } else {
                    RethinkBlocklistManager.clearTagsSelectionRemote()
                }
                return@io
            }

            if (type.isLocal()) {
                RethinkBlocklistManager.updateFiletagsLocal(selectedTags, 1 /* isSelected: true */)
                val list = RethinkBlocklistManager.getSelectedFileTagsLocal().toSet()
                val stamp =
                    RethinkBlocklistManager.getStamp(
                        requireContext(),
                        list,
                        RethinkBlocklistManager.RethinkBlocklistType.LOCAL
                    )
                modifiedStamp = stamp
            } else {
                RethinkBlocklistManager.updateFiletagsRemote(selectedTags, 1 /* isSelected: true */)
                val list = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
                val stamp =
                    RethinkBlocklistManager.getStamp(
                        requireContext(),
                        list,
                        RethinkBlocklistManager.RethinkBlocklistType.REMOTE
                    )
                modifiedStamp = stamp
            }
        }
    }

    private fun getStamp(): String {
        return if (type.isLocal()) {
            persistentState.localBlocklistStamp
        } else {
            getRemoteBlocklistStamp(remoteUrl)
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        if (isRethinkStampSearch(query)) {
            return false
        }
        addQueryToFilters(query)
        return false
    }

    override fun onQueryTextChange(query: String): Boolean {
        if (isRethinkStampSearch(query)) {
            return false
        }
        addQueryToFilters(query)
        return false
    }

    private fun isRethinkStampSearch(t: String): Boolean {
        // do not proceed if rethinkdns.com is not available
        if (!t.contains(Constants.RETHINKDNS_DOMAIN)) return false

        val split = t.split("/")

        // split: https://max.rethinkdns.com/1:IAAgAA== [https:, , max.rethinkdns.com, 1:IAAgAA==]
        split.forEach {
            if (it.contains("$RETHINK_STAMP_VERSION:") && isBase64(it)) {
                selectTagsForStamp(it)
                showToastUiCentered(requireContext(), "Blocklists restored", Toast.LENGTH_SHORT)
                return true
            }
        }

        return false
    }

    // ref: netflix/msl/util/Base64
    private fun isBase64(stamp: String): Boolean {
        val whitespaceRegex = "\\s"
        val pattern =
            Pattern.compile(
                "^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)?$"
            )

        val versionSplit = stamp.split(":").getOrNull(1) ?: return false

        if (versionSplit.isEmpty()) return false

        val result = versionSplit.replace(whitespaceRegex, "")
        val match = pattern.matcher(result).matches()
        return match
    }

    private fun selectTagsForStamp(stamp: String) {
        processSelectedFileTags(stamp)
    }

    fun filterObserver(): MutableLiveData<Filters> {
        return filters
    }

    private fun addQueryToFilters(query: String) {
        val a = filterObserver()
        if (a.value == null) {
            val temp = Filters()
            temp.query = formatQuery(query)
            filters.postValue(temp)
            return
        }

        // asserting, as there is a null check
        a.value!!.query = formatQuery(query)
        filters.postValue(a.value)
    }

    private fun formatQuery(q: String): String {
        return "%$q%"
    }

    private suspend fun getSelectedTags(): List<Int> {
        return if (type.isLocal()) {
            RethinkBlocklistManager.getSelectedFileTagsLocal()
        } else {
            RethinkBlocklistManager.getSelectedFileTagsRemote()
        }
    }

    private fun setSimplePacksViewAdapter() {
        io {
            val tags = RethinkBlocklistManager.getSimpleViewPacksTags(type)
            val selectedTags = getSelectedTags()
            uiCtx {
                simplePacksListAdapter =
                    RethinkSimpleViewPacksAdapter(requireContext(), tags, selectedTags, type)
                val layoutManager = LinearLayoutManager(requireContext())
                b.lbSimpleRecyclerPacks.layoutManager = layoutManager
                b.lbSimpleRecyclerPacks.adapter = simplePacksListAdapter
                b.lbSimpleProgress.visibility = View.GONE
            }
        }
    }

    private fun remakeFilterChipsUi() {
        b.filterChipGroup.removeAllViews()

        val all =
            makeChip(
                BlocklistSelectionFilter.ALL.id,
                getString(R.string.rt_filter_parent_all),
                true
            )
        val selected =
            makeChip(
                BlocklistSelectionFilter.SELECTED.id,
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
        val a = filterObserver().value ?: Filters()

        when (tag) {
            BlocklistSelectionFilter.ALL.id -> {
                a.filterSelected = BlocklistSelectionFilter.ALL
            }
            BlocklistSelectionFilter.SELECTED.id -> {
                a.filterSelected = BlocklistSelectionFilter.SELECTED
            }
        }
        filters.postValue(a)
    }

    private fun openFilterBottomSheet() {
        io {
            val bottomSheetFragment = RethinkPlusFilterBottomSheetFragment(this, getAllList())
            uiCtx { bottomSheetFragment.show(childFragmentManager, bottomSheetFragment.tag) }
        }
    }

    private suspend fun getAllList(): List<FileTag> {
        return if (type.isLocal()) {
            localFileTagViewModel.allFileTags()
        } else {
            remoteFileTagViewModel.allFileTags()
        }
    }

    private fun setRemoteAdapter() {
        if (advanceRemoteListAdapter != null) return

        advanceRemoteListAdapter = RethinkRemoteAdvancedViewAdapter(requireContext())
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.lbAdvancedRecycler.layoutManager = layoutManager

        remoteFileTagViewModel.remoteFileTags.observe(viewLifecycleOwner) {
            advanceRemoteListAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.lbAdvancedRecycler.adapter = advanceRemoteListAdapter

        // implement sticky headers
        // ref:
        // https://stackoverflow.com/questions/32949971/how-can-i-make-sticky-headers-in-recyclerview-without-external-lib
        /*b.lbAdvancedRecycler.addItemDecoration(HeaderItemDecoration(b.lbAdvancedRecycler) { itemPosition ->
            itemPosition >= 0 && itemPosition < advanceRemoteListAdapter!!.itemCount
        })*/
    }

    private fun setLocalAdapter() {
        if (advanceLocalListAdapter != null) return

        advanceLocalListAdapter = RethinkLocalAdvancedViewAdapter(requireContext())
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.lbAdvancedRecycler.layoutManager = layoutManager

        localFileTagViewModel.localFiletags.observe(viewLifecycleOwner) {
            advanceLocalListAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.lbAdvancedRecycler.adapter = advanceLocalListAdapter
    }

    private fun observeWorkManager() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for custom download manager worker
        workManager.getWorkInfosByTagLiveData(CUSTOM_DOWNLOAD).observe(viewLifecycleOwner) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Log.i(
                LoggerConstants.LOG_TAG_DOWNLOAD,
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
            Log.i(
                LoggerConstants.LOG_TAG_DOWNLOAD,
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
                if (workInfo != null && workInfo.state == WorkInfo.State.SUCCEEDED) {
                    Log.i(
                        LoggerConstants.LOG_TAG_DOWNLOAD,
                        "AppDownloadManager Work Manager completed - $FILE_TAG"
                    )
                    onDownloadSuccess()
                    workManager.pruneWork()
                } else if (
                    workInfo != null &&
                        (workInfo.state == WorkInfo.State.CANCELLED ||
                            workInfo.state == WorkInfo.State.FAILED)
                ) {
                    onDownloadFail()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(FILE_TAG)
                    Log.i(
                        LoggerConstants.LOG_TAG_DOWNLOAD,
                        "AppDownloadManager Work Manager failed - $FILE_TAG"
                    )
                } else {
                    Log.i(
                        LoggerConstants.LOG_TAG_DOWNLOAD,
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
