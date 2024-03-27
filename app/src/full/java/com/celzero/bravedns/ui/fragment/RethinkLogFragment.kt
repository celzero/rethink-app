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

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkLogAdapter
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.databinding.ActivityConnectionTrackerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils.formatToRelativeTime
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.RethinkLogViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkLogFragment :
    Fragment(R.layout.activity_connection_tracker), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityConnectionTrackerBinding::bind)

    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: RethinkLogViewModel by viewModel()

    private val rethinkLogRepository by inject<RethinkLogRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
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
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rlogList.observe(viewLifecycleOwner) { it ->
                    recyclerAdapter.submitData(lifecycle, it)
                }
            }
        }
        b.recyclerConnection.adapter = recyclerAdapter

        setupRecyclerScrollListener()

        b.connectionSearch.setOnQueryTextListener(this)

        b.connectionDeleteIcon.setOnClickListener { showDeleteDialog() }
    }

    private fun setupRecyclerScrollListener() {
        val scrollListener =
            object : RecyclerView.OnScrollListener() {

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (recyclerView.getChildAt(0)?.tag == null) return

                    val tag: Long = recyclerView.getChildAt(0).tag as Long

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

    override fun onQueryTextSubmit(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(500, lifecycleScope) {
            if (this.isAdded) {
                viewModel.setFilter(query)
            }
        }
        return true
    }

    private fun showDeleteDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
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
