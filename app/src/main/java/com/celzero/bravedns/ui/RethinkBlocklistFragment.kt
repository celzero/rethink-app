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
import com.celzero.bravedns.adapter.RethinkSimpleViewAdapter
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.customdownloader.LocalBlocklistDownloader.Companion.CUSTOM_DOWNLOAD
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.FragmentRethinkBlocklistBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_NAME
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_STAMP
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.RethinkBlocklistFragment.RethinkBlocklistType.Companion.getType
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities.Companion.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.Companion.hasRemoteBlocklists
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.celzero.bravedns.viewmodel.RethinkLocalFileTagViewModel
import com.celzero.bravedns.viewmodel.RethinkRemoteFileTagViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkBlocklistFragment : Fragment(R.layout.fragment_rethink_blocklist),
                                 SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentRethinkBlocklistBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    private var type: RethinkBlocklistType = RethinkBlocklistType.REMOTE
    private var remoteName: String = ""
    private var remoteStamp: String = ""

    private val filters = MutableLiveData<Filters>()

    private var advanceRemoteListAdapter: RethinkRemoteAdvancedViewAdapter? = null
    private var advanceLocalListAdapter: RethinkLocalAdvancedViewAdapter? = null
    private var simpleListAdapter: RethinkSimpleViewAdapter? = null

    private val remoteFileTagViewModel: RethinkRemoteFileTagViewModel by viewModel()
    private val localFileTagViewModel: RethinkLocalFileTagViewModel by viewModel()

    private var modifiedStamp: String = ""
    private var isDownloadInitiated = false


    class Filters {
        var query: String = "%%"
        var groups: MutableSet<String> = mutableSetOf()
        var subGroups: MutableSet<String> = mutableSetOf()
    }

    enum class RethinkBlocklistType {
        LOCAL, REMOTE;

        companion object {
            fun getType(id: Int): RethinkBlocklistType {
                if (id == LOCAL.ordinal) return LOCAL

                return REMOTE
            }
        }

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    companion object {
        fun newInstance() = RethinkBlocklistFragment()
        var selectedFileTags: MutableLiveData<MutableSet<Int>> = MutableLiveData()

        const val SIMPLE_VIEW = "0"
        const val ADVANCED_VIEW = "1"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val bundle = this.arguments
        type = getType(bundle?.getInt(RETHINK_BLOCKLIST_TYPE,
                                      RethinkBlocklistType.REMOTE.ordinal) ?: RethinkBlocklistType.REMOTE.ordinal)
        remoteName = bundle?.getString(RETHINK_BLOCKLIST_NAME, "") ?: ""
        remoteStamp = bundle?.getString(RETHINK_BLOCKLIST_STAMP, "") ?: ""
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
            } else {
                localFileTagViewModel.setFilter(it)
            }
        }

        selectedFileTags.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            modifiedStamp = RethinkBlocklistManager.getStamp(requireContext(),
                                                             getDownloadTimeStamp(), it, type)
        }

        appDownloadManager.timeStampToDownload.observe(viewLifecycleOwner) {
            if (!isDownloadInitiated) return@observe

            Log.d(LoggerConstants.LOG_TAG_DNS, "Check for blocklist update, status: $it")
            if (it == AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id) {
                // no-op
                return@observe
            }
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    showToastUiCentered(requireContext(),
                                        getString(R.string.blocklist_update_check_failure),
                                        Toast.LENGTH_SHORT)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED.id) {
                ui {
                    showToastUiCentered(requireContext(),
                                        getString(R.string.blocklist_update_check_not_required),
                                        Toast.LENGTH_SHORT)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                // no-op
                return@observe
            }

            if (it == getDownloadTimeStamp()) {
                return@observe
            }

            downloadLocalBlocklist(it)
        }
    }

    private fun init() {
        modifiedStamp = getStamp()

        hasBlocklist()

        // be default, select the simple blocklist view
        selectToggleBtnUi(b.lbSimpleToggleBtn)
        unselectToggleBtnUi(b.lbAdvToggleBtn)
    }

    private fun hasBlocklist() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.IO) {
                    hasBlocklists()
                }
                if (blocklistsExist) {
                    RethinkBlocklistManager.createBraveDns(requireContext(), getDownloadTimeStamp(),
                                                           type)
                    setListAdapter(getDownloadTimeStamp())
                    showConfigureUi()
                    hideDownloadUi()
                    return@uiCtx
                }

                persistentState.localBlocklistTimestamp = INIT_TIME_MS
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

    private fun getDownloadTimeStamp(): Long {
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
            isBlocklistUpdateAvailable(getDownloadType())
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

            // no-op if download already in progress
            if (isLocalBlocklistDownloadInitiated()) return@setOnClickListener

            isDownloadInitiated = true
            go {
                uiCtx {
                    isBlocklistUpdateAvailable(getDownloadType())
                }
            }
        }

        b.lbCancelDownloadBtn.setOnClickListener {
            cancelDownloadWorkManager()
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

        b.lbAdvSearchFilterIcon.setOnClickListener {
            openFilterBottomSheet()
        }

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

    private fun isLocalBlocklistDownloadInitiated(): Boolean {
        return if (persistentState.useCustomDownloadManager) {
            WorkScheduler.isWorkScheduled(requireContext(), CUSTOM_DOWNLOAD)
        } else {
            WorkScheduler.isWorkScheduled(requireContext(),
                                          DOWNLOAD_TAG) || WorkScheduler.isWorkScheduled(
                requireContext(), FILE_TAG)
        }
    }

    private fun cancelDownloadWorkManager() {
        if (!isLocalBlocklistDownloadInitiated()) return

        if (persistentState.useCustomDownloadManager) {
            WorkManager.getInstance(requireContext().applicationContext).cancelAllWorkByTag(
                CUSTOM_DOWNLOAD)
        } else {
            WorkManager.getInstance(requireContext().applicationContext).cancelAllWorkByTag(
                DOWNLOAD_TAG)
        }
        WorkManager.getInstance(requireContext().applicationContext).cancelAllWorkByTag(FILE_TAG)
    }

    private fun clearSelectedTags() {
        io {
            if (type.isRemote()) {
                RethinkBlocklistManager.clearSelectedTagsRemote()
            } else {
                RethinkBlocklistManager.clearSelectedTagsLocal()
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
            selectedFileTags.value = mutableSetOf()
            requireActivity().finish()
        }
        builder.create().show()
    }

    private fun getDownloadType(): AppDownloadManager.DownloadType {
        if (type.isLocal()) {
            return AppDownloadManager.DownloadType.LOCAL
        }

        return AppDownloadManager.DownloadType.REMOTE
    }

    private fun setStamp(stamp: String) {
        Log.i(LoggerConstants.LOG_TAG_VPN,
              "Rethink dns, set stamp for blocklist type: ${type.name} with $stamp, count: ${selectedFileTags.value?.size}")
        if (type.isLocal()) {
            persistentState.localBlocklistStamp = stamp

            if (selectedFileTags.value.isNullOrEmpty()) {
                persistentState.numberOfLocalBlocklists = 0
                persistentState.blocklistEnabled = true
                return
            }

            persistentState.numberOfLocalBlocklists = selectedFileTags.value?.size!!
            persistentState.blocklistEnabled = true
            selectedFileTags.value = mutableSetOf()

            return
        }

        // set stamp for remote blocklist
        val rs = RethinkListFragment.ModifiedStamp(remoteName, stamp,
                                                   selectedFileTags.value?.size ?: 0)
        RethinkListFragment.modifiedStamp.postValue(rs)
        selectedFileTags.value = mutableSetOf()
    }

    private val listViewToggleListener = MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
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
        if (id == SIMPLE_VIEW) {
            setSimpleViewAdapter()
            b.lbSimpleRecycler.visibility = View.VISIBLE
            b.lbAdvContainer.visibility = View.INVISIBLE
            return
        }

        b.lbSimpleRecycler.visibility = View.INVISIBLE
        b.lbAdvContainer.visibility = View.VISIBLE
    }

    private fun selectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList = ColorStateList.valueOf(
            fetchToggleBtnColors(requireContext(), R.color.accentGood))
    }

    private fun unselectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList = ColorStateList.valueOf(
            fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg))
    }

    private fun setListAdapter(timestamp: Long) {
        getSelectedFileTags(timestamp)

        if (type.isLocal()) {
            setLocalAdapter()
        } else {
            setRemoteAdapter()
        }
        showList(b.lbSimpleToggleBtn.tag.toString())
    }

    private fun getSelectedFileTags(timestamp: Long) {
        val list = RethinkBlocklistManager.getSelectedFileTags(requireContext(), timestamp,
                                                               getStamp(), type)

        if (selectedFileTags.value.isNullOrEmpty()) {
            selectedFileTags.value = list.toMutableSet()
        } else {
            selectedFileTags.value?.addAll(list)
        }

        updateSelectedFileTags(list.toMutableSet())
    }

    private fun updateSelectedFileTags(selectedTags: MutableSet<Int>) {
        io {
            // if the list is empty clear if there is residual selections
            if (selectedTags.isEmpty()) {
                if (type.isLocal()) {
                    RethinkBlocklistManager.clearSelectedTagsLocal()
                } else {
                    RethinkBlocklistManager.clearSelectedTagsRemote()
                }
                return@io
            }

            if (type.isLocal()) {
                RethinkBlocklistManager.updateSelectedFiletagsLocal(selectedTags,
                                                                    1 /* isSelected: true */)
            } else {
                RethinkBlocklistManager.updateSelectedFiletagsRemote(selectedTags,
                                                                     1 /* isSelected: true */)
            }
        }
    }

    private fun getStamp(): String {
        return if (type.isLocal()) {
            persistentState.localBlocklistStamp
        } else {
            remoteStamp
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        addQueryToFilters(query)
        return false
    }

    override fun onQueryTextChange(query: String): Boolean {
        addQueryToFilters(query)
        return false
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

    private fun setSimpleViewAdapter() {
        io {
            val tags = RethinkBlocklistManager.getSimpleViewTags(type)
            uiCtx {
                // set recycler for simple view adapter
                simpleListAdapter = RethinkSimpleViewAdapter(requireContext(), tags, type)
                //getSimpleViewFileTags
                val layoutManager = LinearLayoutManager(requireContext())
                b.lbSimpleRecycler.layoutManager = layoutManager
                b.lbSimpleRecycler.adapter = simpleListAdapter
                b.lbSimpleProgress.visibility = View.GONE
            }
        }
    }

    private fun openFilterBottomSheet() {
        io {
            val bottomSheetFragment = RethinkPlusFilterBottomSheetFragment(this, getAllList())
            uiCtx {
                bottomSheetFragment.show(childFragmentManager, bottomSheetFragment.tag)
            }
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
        // ref: https://stackoverflow.com/questions/32949971/how-can-i-make-sticky-headers-in-recyclerview-without-external-lib
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

    private fun isBlocklistUpdateAvailable(downloadType: AppDownloadManager.DownloadType) {
        appDownloadManager.isDownloadRequired(downloadType, retryCount = 0)
    }

    private fun downloadLocalBlocklist(timestamp: Long) {
        appDownloadManager.downloadLocalBlocklist(timestamp)
    }

    private fun observeWorkManager() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for custom download manager worker
        workManager.getWorkInfosByTagLiveData(CUSTOM_DOWNLOAD).observe(
            viewLifecycleOwner) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "WorkManager state: ${workInfo.state} for $CUSTOM_DOWNLOAD")
            if (WorkInfo.State.ENQUEUED == workInfo.state || WorkInfo.State.RUNNING == workInfo.state) {
                onDownloadStart()
            } else if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                onDownloadSuccess()
                workManager.pruneWork()
            } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(CUSTOM_DOWNLOAD)
            } else { // state == blocked
                // no-op
            }
        }

        // observer for Androids default download manager
        workManager.getWorkInfosByTagLiveData(DOWNLOAD_TAG).observe(
            viewLifecycleOwner) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "WorkManager state: ${workInfo.state} for $DOWNLOAD_TAG")
            if (WorkInfo.State.ENQUEUED == workInfo.state || WorkInfo.State.RUNNING == workInfo.state) {
                onDownloadStart()
            } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                onDownloadFail()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
                workManager.cancelAllWorkByTag(FILE_TAG)
            } else { // state == blocked, succeeded
                // no-op
            }
        }

        workManager.getWorkInfosByTagLiveData(FILE_TAG).observe(
            viewLifecycleOwner) { workInfoList ->
            if (workInfoList != null && workInfoList.isNotEmpty()) {
                val workInfo = workInfoList[0]
                if (workInfo != null && workInfo.state == WorkInfo.State.SUCCEEDED) {
                    Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                          "AppDownloadManager Work Manager completed - $FILE_TAG")
                    onDownloadSuccess()
                    workManager.pruneWork()
                } else if (workInfo != null && (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED)) {
                    onDownloadFail()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(FILE_TAG)
                    Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                          "AppDownloadManager Work Manager failed - $FILE_TAG")
                } else {
                    Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                          "AppDownloadManager Work Manager - $FILE_TAG, ${workInfo.state}")
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
        isDownloadInitiated = false
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
        isDownloadInitiated = false
        // update ui for download success
        b.lbDownloadProgress.visibility = View.GONE
        b.lbDownloadProgressRemote.visibility = View.GONE
        b.lbDownloadBtn.text = getString(R.string.rt_download)
        hideDownloadUi()
        //showConfigureUi()
        hasBlocklist()
        b.lbListToggleGroup.check(R.id.lb_simple_toggle_btn)
        showToastUiCentered(requireContext(),
                            getString(R.string.download_update_dialog_message_success),
                            Toast.LENGTH_SHORT)
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
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
