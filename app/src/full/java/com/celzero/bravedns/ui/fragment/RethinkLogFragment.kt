/*
Copyright 2023 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import android.content.Context.INPUT_METHOD_SERVICE
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkLogAdapter
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.databinding.FragmentConnectionTrackerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils.formatToRelativeTime
import com.celzero.bravedns.viewmodel.RethinkLogViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkLogFragment :
    Fragment(R.layout.fragment_connection_tracker), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentConnectionTrackerBinding::bind)

    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: RethinkLogViewModel by viewModel()

    private val rethinkLogRepository by inject<RethinkLogRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val QUERY_TEXT_DELAY: Long = 1000

        fun newInstance(param: String): RethinkLogFragment {
            val args = Bundle()
            args.putString(Constants.SEARCH_QUERY, param)
            val fragment = RethinkLogFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        if (arguments != null) {
            val query = arguments?.getString(Constants.SEARCH_QUERY) ?: return
            b.connectionSearch.setQuery(query, true)
        }
    }

    override fun onResume() {
        super.onResume()
        // fix for #1939, OEM-specific bug, especially on heavily customized Android
        // some ROMs kill or freeze the keyboard/IME process to save memory or battery,
        // causing SearchView to stop receiving input events
        // this is a workaround to restart the IME process
        b.connectionSearch.setQuery("", false)
        b.connectionSearch.clearFocus()

        val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(b.connectionSearch)
    }

    private fun initView() {

        // no need to show filter options for rethink logs
        b.connectionFilterIcon.visibility = View.GONE

        if (!persistentState.logsEnabled) {
            b.connectionListLogsDisabledTv.visibility = View.VISIBLE
            b.connectionCardViewTop.visibility = View.GONE
            return
        }

        b.connectionListLogsDisabledTv.visibility = View.GONE
        b.connectionCardViewTop.visibility = View.VISIBLE

        b.recyclerConnection.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerConnection.layoutManager = layoutManager
        val recyclerAdapter = RethinkLogAdapter(requireContext())
        viewModel.rlogList.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(lifecycle, it)
        }
        b.recyclerConnection.adapter = recyclerAdapter
        b.recyclerConnection.layoutAnimation = null

        setupRecyclerScrollListener()
        b.connectionSearch.setOnQueryTextListener(this)
        setQueryFilter()

        b.connectionDeleteIcon.setOnClickListener { showDeleteDialog() }

    }

    private fun setupRecyclerScrollListener() {
        val scrollListener =
            object : RecyclerView.OnScrollListener() {

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val firstChild = recyclerView.getChildAt(0)
                    if (firstChild == null) {
                        Logger.w(LOG_TAG_UI, "RinRLogs; err; no child views found in recyclerView")
                        return
                    }

                    val tag = firstChild.tag as? Long
                    if (tag == null) {
                        Logger.w(LOG_TAG_UI, "RinRLogs; err; tag is null for first child, rv")
                        return
                    }

                    b.connectionListScrollHeader.text = formatToRelativeTime(requireContext(), tag)
                    b.connectionListScrollHeader.visibility = View.VISIBLE
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        b.connectionListScrollHeader.visibility = View.GONE
                    }
                }
            }
        b.recyclerConnection.addOnScrollListener(scrollListener)
    }

    @OptIn(FlowPreview::class)
    private fun setQueryFilter() {
        lifecycleScope.launch {
            searchQuery
                .debounce(QUERY_TEXT_DELAY)
                .distinctUntilChanged()
                .collect { query ->
                    viewModel.setFilter(query)
                }
        }
    }

    val searchQuery = MutableStateFlow("")

    override fun onQueryTextSubmit(query: String): Boolean {
        searchQuery.value = query
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        searchQuery.value = query
        return true
    }

    private fun showDeleteDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.conn_track_clear_logs_title)
        builder.setMessage(R.string.conn_track_clear_logs_message)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
            io { rethinkLogRepository.clearAllData() }
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
        builder.create().show()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
