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
import Logger.LOG_TAG_UI
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
import androidx.paging.filter
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
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.FragmentRethinkBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.RethinkBlocklistManager.RethinkBlocklistType.Companion.getType
import com.celzero.bravedns.service.RethinkBlocklistManager.getStamp
import com.celzero.bravedns.service.RethinkBlocklistManager.getTagsFromStamp
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_NAME
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_URL
import com.celzero.bravedns.ui.bottomsheet.RethinkPlusFilterBottomSheet
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.DEAD_PACK
import com.celzero.bravedns.util.Constants.Companion.DEFAULT_RDNS_REMOTE_DNS_NAMES
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Constants.Companion.RETHINK_STAMP_VERSION
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.Utilities.getRemoteBlocklistStamp
import com.celzero.bravedns.util.Utilities.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.hasRemoteBlocklists
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.LocalBlocklistPacksMapViewModel
import com.celzero.bravedns.viewmodel.RemoteBlocklistPacksMapViewModel
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
import java.util.regex.Pattern

class RethinkBlocklistFragment :
    Fragment(R.layout.fragment_rethink_blocklist), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentRethinkBlocklistBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()
    private val appConfig by inject<AppConfig>()

    private var type: RethinkBlocklistManager.RethinkBlocklistType =
        RethinkBlocklistManager.RethinkBlocklistType.REMOTE
    private var remoteName: String = ""
    private var remoteUrl: String = ""

    private val filters = MutableLiveData<Filters>()

    private var advanceRemoteViewAdapter: RemoteAdvancedViewAdapter? = null
    private var advanceLocalViewAdapter: LocalAdvancedViewAdapter? = null
    private var localSimpleViewAdapter: LocalSimpleViewAdapter? = null
    private var remoteSimpleViewAdapter: RemoteSimpleViewAdapter? = null

    private val remoteFileTagViewModel: RethinkRemoteFileTagViewModel by viewModel()
    private val localFileTagViewModel: RethinkLocalFileTagViewModel by viewModel()
    private val remoteBlocklistPacksMapViewModel: RemoteBlocklistPacksMapViewModel by viewModel()
    private val localBlocklistPacksMapViewModel: LocalBlocklistPacksMapViewModel by viewModel()

    private var modifiedStamp: String = ""

    enum class BlocklistSelectionFilter(val id: Int) {
        ALL(0),
        SELECTED(1)
    }

    class Filters {
        var query: String = "%%"
        var filterSelected: BlocklistSelectionFilter = BlocklistSelectionFilter.ALL
        var subGroups: MutableSet<String> = mutableSetOf()
    }

    enum class BlocklistView(val tag: String) {
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

    companion object {
        fun newInstance() = RethinkBlocklistFragment()

        private var selectedFileTags: MutableLiveData<MutableSet<Int>> = MutableLiveData()

        fun updateFileTagList(fileTags: Set<Int>) {
            selectedFileTags.postValue(fileTags.toMutableSet())
        }

        fun getSelectedFileTags(): Set<Int> {
            return selectedFileTags.value ?: emptySet()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val bundle = this.arguments
        type =
            getType(
                bundle?.getInt(
                    RETHINK_BLOCKLIST_TYPE,
                    RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
                ) ?: RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
            )
        remoteName = bundle?.getString(RETHINK_BLOCKLIST_NAME, "") ?: ""
        remoteUrl = bundle?.getString(RETHINK_BLOCKLIST_URL, "") ?: ""
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.v(LOG_TAG_UI, "init Rethink blocklist fragment")
        init()
        initObservers()
        initClickListeners()
    }

    private fun initObservers() {
        if (type.isLocal()) {
            observeWorkManager()
        }

        selectedFileTags.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            io { modifiedStamp = getStamp(it, type) }
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

        val typeName =
            if (type.isLocal()) {
                getString(R.string.lbl_on_device)
            } else {
                getString(R.string.rdns_plus)
            }
        b.lbBlocklistApplyBtn.text =
            getString(R.string.ct_ip_details, getString(R.string.lbl_apply), typeName)

        io {
            val flags = getTagsFromStamp(modifiedStamp, type)
            updateFileTagList(flags)
        }

        // update ui based on blocklist availability
        hasBlocklist()

        // be default, select the simple blocklist view
        selectToggleBtnUi(b.lbSimpleToggleBtn)
        unselectToggleBtnUi(b.lbAdvToggleBtn)

        remakeFilterChipsUi()
    }

    private fun updateFilteredTxtUi(filter: Filters) {
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
        return if (type.isLocal()) {
            hasLocalBlocklists(requireContext(), persistentState.localBlocklistTimestamp)
        } else {
            hasRemoteBlocklists(requireContext(), persistentState.remoteBlocklistTimestamp)
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
        // no need to check on the stamp when the remote name is in the default list
        // eg., rec, sec, pec etc
        if (DEFAULT_RDNS_REMOTE_DNS_NAMES.contains(remoteName)) {
            return false
        }

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
            requireActivity().finish()
        }

        b.lbBlocklistCancelBtn.setOnClickListener {
            // close the activity associated with the fragment after reverting to old stamp
            io {
                val stamp = getStamp()
                val list = RethinkBlocklistManager.getTagsFromStamp(stamp, type)
                updateSelectedFileTags(list.toMutableSet())
                setStamp(stamp)
                Logger.i(LOG_TAG_UI, "revert to old stamp for blocklist type: ${type.name}, $stamp, $list")
                uiCtx {
                    requireActivity().finish()
                }
            }
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
        // Check if VPN is in lockdown mode and custom download manager is disabled
        if (VpnController.isVpnLockdown() && !persistentState.useCustomDownloadManager) {
            showLockdownDownloadDialog(type)
            return
        }

        proceedWithBlocklistDownload(type)
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
            proceedWithBlocklistDownload(type)
        }
        builder.create().show()
    }

    private fun proceedWithBlocklistDownload(type: RethinkBlocklistManager.RethinkBlocklistType) {
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
            setStamp(modifiedStamp)
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

    private fun setStamp(stamp: String?) {
        Logger.i(LOG_TAG_UI, "set stamp for blocklist type: ${type.name} with $stamp")
        if (stamp == null) {
            Logger.i(LOG_TAG_UI, "stamp is null")
            return
        }

        io {
            val blocklistCount = getTagsFromStamp(stamp, type).size
            if (type.isLocal()) {
                persistentState.localBlocklistStamp = stamp
                persistentState.numberOfLocalBlocklists = blocklistCount
                persistentState.blocklistEnabled = true
                Logger.i(LOG_TAG_UI, "set stamp for local blocklist with $stamp, $blocklistCount")
            } else {
                // set stamp for remote blocklist
                appConfig.updateRethinkEndpoint(
                    Constants.RETHINK_DNS_PLUS,
                    getRemoteUrl(stamp),
                    blocklistCount
                )
                appConfig.enableRethinkDnsPlus()
                Logger.i(LOG_TAG_UI, "set stamp for remote blocklist with $stamp, $blocklistCount")
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
        io {
            processSelectedFileTags(getStamp())
            uiCtx {
                if (type.isLocal()) {
                    setLocalAdapter()
                } else {
                    setRemoteAdapter()
                }
                showList(b.lbSimpleToggleBtn.tag.toString())
            }
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
        if (type.isLocal()) {
            setLocalSimpleViewAdapter()
        } else {
            setRemoteSimpleViewAdapter()
        }
    }

    private suspend fun processSelectedFileTags(stamp: String) {
        val list = RethinkBlocklistManager.getTagsFromStamp(stamp, type)
        updateSelectedFileTags(list.toMutableSet())
    }

    private suspend fun updateSelectedFileTags(selectedTags: MutableSet<Int>) {
        // clear the residues if the selected tags are empty
        if (selectedTags.isEmpty()) {
            if (type.isLocal()) {
                RethinkBlocklistManager.clearTagsSelectionLocal()
            } else {
                RethinkBlocklistManager.clearTagsSelectionRemote()
            }
            return
        }

        if (type.isLocal()) {
            RethinkBlocklistManager.clearTagsSelectionLocal()
            RethinkBlocklistManager.updateFiletagsLocal(selectedTags, 1 /* isSelected: true */)
            val list = RethinkBlocklistManager.getSelectedFileTagsLocal().toSet()
            updateFileTagList(list)
        } else {
            RethinkBlocklistManager.clearTagsSelectionRemote()
            RethinkBlocklistManager.updateFiletagsRemote(selectedTags, 1 /* isSelected: true */)
            val list = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
            updateFileTagList(list)
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
                io { processSelectedFileTags(it) }
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
        return pattern.matcher(result).matches()
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

    private fun setLocalSimpleViewAdapter() {
        localSimpleViewAdapter = LocalSimpleViewAdapter(requireContext())
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.lbSimpleRecyclerPacks.layoutManager = layoutManager

        localBlocklistPacksMapViewModel.simpleTags.observe(viewLifecycleOwner) {
            val l = it.filter { it1 -> !it1.pack.contains(DEAD_PACK) && it1.pack.isNotEmpty() }
            localSimpleViewAdapter?.submitData(viewLifecycleOwner.lifecycle, l)
        }
        b.lbSimpleRecyclerPacks.adapter = localSimpleViewAdapter
        setupRecyclerScrollListener(b.lbSimpleRecyclerPacks, BlocklistView.PACKS)
    }

    private fun setRemoteSimpleViewAdapter() {
        remoteSimpleViewAdapter = RemoteSimpleViewAdapter(requireContext())
        val layoutManager = CustomLinearLayoutManager(requireContext())
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

        val all = makeChip(BlocklistSelectionFilter.ALL.id, getString(R.string.lbl_all), true)
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
            val bottomSheetFragment = RethinkPlusFilterBottomSheet.newInstance(this, getAllList())
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
        if (advanceRemoteViewAdapter != null) return

        advanceRemoteViewAdapter = RemoteAdvancedViewAdapter(requireContext())
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.lbAdvancedRecycler.layoutManager = layoutManager

        remoteFileTagViewModel.remoteFileTags.observe(viewLifecycleOwner) {
            advanceRemoteViewAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.lbAdvancedRecycler.adapter = advanceRemoteViewAdapter
        setupRecyclerScrollListener(b.lbAdvancedRecycler, BlocklistView.ADVANCED)

        // implement sticky headers
        // ref:
        // https://stackoverflow.com/questions/32949971/how-can-i-make-sticky-headers-in-recyclerview-without-external-lib
        /*b.lbAdvancedRecycler.addItemDecoration(HeaderItemDecoration(b.lbAdvancedRecycler) { itemPosition ->
            itemPosition >= 0 && itemPosition < advanceRemoteListAdapter!!.itemCount
        })*/
    }

    private fun setLocalAdapter() {
        if (advanceLocalViewAdapter != null) return

        advanceLocalViewAdapter = LocalAdvancedViewAdapter(requireContext())
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.lbAdvancedRecycler.layoutManager = layoutManager

        localFileTagViewModel.localFiletags.observe(viewLifecycleOwner) {
            advanceLocalViewAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
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
