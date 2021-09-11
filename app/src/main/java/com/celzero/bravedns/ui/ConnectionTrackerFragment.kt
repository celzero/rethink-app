/*
Copyright 2020 RethinkDNS and its authors

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
package com.celzero.bravedns.ui

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionTrackerAdapter
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.databinding.ActivityConnectionTrackerBinding
import com.celzero.bravedns.service.DNSLogTracker
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Captures network logs and stores in ConnectionTracker, a room database.
 */
class ConnectionTrackerFragment : Fragment(R.layout.activity_connection_tracker),
                                  SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityConnectionTrackerBinding::bind)

    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: ConnectionTrackerViewModel by viewModel()

    private var filterQuery: String? = ""
    private var filterCategories: MutableList<String> = ArrayList()
    private var filterType: ConnectionTrackerViewModel.FilterType = ConnectionTrackerViewModel.FilterType.ALL

    private val connectionTrackerDAO by inject<ConnectionTrackerDAO>()
    private val persistentState by inject<PersistentState>()
    private val dnsLogTracker by inject<DNSLogTracker>()

    companion object {
        fun newInstance() = ConnectionTrackerFragment()

        private const val PARENT_FILTER_ALL = 0
        private const val PARENT_FILTER_ALLOWED = 1
        private const val PARENT_FILTER_BLOCKED = 2
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        val includeView = b.connectionListScrollList

        if (!persistentState.logsEnabled) {
            includeView.connectionListLogsDisabledTv.visibility = View.VISIBLE
            includeView.connectionCardViewTop.visibility = View.GONE
            return
        }

        includeView.connectionListLogsDisabledTv.visibility = View.GONE
        includeView.connectionCardViewTop.visibility = View.VISIBLE

        includeView.recyclerConnection.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        includeView.recyclerConnection.layoutManager = layoutManager
        val recyclerAdapter = ConnectionTrackerAdapter(this)
        viewModel.connectionTrackerList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter::submitList))
        includeView.recyclerConnection.adapter = recyclerAdapter

        includeView.connectionSearch.setOnQueryTextListener(this)
        includeView.connectionSearch.setOnClickListener {
            toggleParentChipsUi()
            includeView.connectionSearch.requestFocus()
            includeView.connectionSearch.onActionViewExpanded()
        }

        includeView.connectionFilterIcon.setOnClickListener {
            toggleParentChipsUi()
        }

        includeView.connectionDeleteIcon.setOnClickListener {
            showDeleteDialog()
        }

        setParentFilterChips()
        setChildFilterChips(FirewallRuleset.getBlockedRules())
    }

    private fun toggleParentChipsUi() {
        val includeView = b.connectionListScrollList

        if (includeView.filterChipParentGroup.isVisible) {
            hideParentChipsUi()
        } else {
            showParentChipsUi()
        }
        hideChildChipsUi()
    }

    private fun setParentFilterChips() {
        val includeView = b.connectionListScrollList

        includeView.filterChipParentGroup.removeAllViews()
        for (i in 0 until 3) {
            val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip

            val text = when (i) {
                PARENT_FILTER_ALL -> {
                    chip.isChecked = true
                    getString(R.string.ct_filter_parent_all)
                }
                PARENT_FILTER_ALLOWED -> {
                    getString(R.string.ct_filter_parent_allowed)
                }
                PARENT_FILTER_BLOCKED -> {
                    getString(R.string.ct_filter_parent_blocked)
                }
                else -> {
                    getString(R.string.ct_filter_parent_all)
                }
            }

            chip.text = text
            chip.tag = i

            chip.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                if (!b) {
                    handleCategoryChipsUi(compoundButton.tag)
                    return@setOnCheckedChangeListener
                }
                applyParentFilter(compoundButton.tag)
            }

            includeView.filterChipParentGroup.addView(chip)
        }

    }

    private fun applyParentFilter(tag: Any) {
        when (tag) {
            PARENT_FILTER_ALL -> {
                filterCategories.clear()
                filterType = ConnectionTrackerViewModel.FilterType.ALL
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                hideChildChipsUi()
            }
            PARENT_FILTER_ALLOWED -> {
                filterCategories.clear()
                filterType = ConnectionTrackerViewModel.FilterType.ALLOWED
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                setChildFilterChips(FirewallRuleset.getAllowedRules())
                showParentChipsUi()
            }
            PARENT_FILTER_BLOCKED -> {
                filterType = ConnectionTrackerViewModel.FilterType.BLOCKED
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                setChildFilterChips(FirewallRuleset.getBlockedRules())
                showChildChipsUi()
            }
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        this.filterQuery = query
        viewModel.setFilter(query, filterCategories, filterType)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        this.filterQuery = query
        viewModel.setFilter(query, filterCategories, filterType)
        return true
    }

    private fun showDeleteDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.conn_track_clear_logs_title)
        builder.setMessage(R.string.conn_track_clear_logs_message)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.ct_delete_logs_positive_btn)) { _, _ ->
            CoroutineScope(Dispatchers.IO).launch {
                connectionTrackerDAO.clearAllData()
            }
        }

        builder.setNegativeButton(getString(R.string.ct_delete_logs_negative_btn)) { _, _ ->
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    fun ipToDomain(ip: String): DNSLogTracker.DnsCacheRecord? {
        return dnsLogTracker.ipDomainLookup.getIfPresent(ip)
    }

    private fun setChildFilterChips(categories: List<FirewallRuleset>) {
        val includeView = b.connectionListScrollList

        includeView.filterChipGroup.removeAllViews()
        for (category in categories) {
            val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip
            chip.text = getString(category.title)
            chip.chipIcon = ContextCompat.getDrawable(requireContext(),
                                                      FirewallRuleset.getRulesIcon(category.id))
            chip.isCheckedIconVisible = false
            chip.tag = category.id

            chip.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                applyChildFilter(compoundButton.tag, b)
            }
            includeView.filterChipGroup.addView(chip)
        }
    }

    private fun applyChildFilter(tag: Any, b: Boolean) {
        if (b) {
            filterCategories.add(tag.toString())
        } else {
            filterCategories.remove(tag.toString())
        }
        viewModel.setFilter(filterQuery, filterCategories, filterType)
    }

    private fun handleCategoryChipsUi(tag: Any) {
        when (tag) {
            PARENT_FILTER_ALL -> {
                showChildChipsUi()
            }
        }
    }

    private fun showChildChipsUi() {
        b.connectionListScrollList.filterChipGroup.visibility = View.VISIBLE
    }

    private fun hideChildChipsUi() {
        b.connectionListScrollList.filterChipGroup.visibility = View.GONE
    }

    private fun showParentChipsUi() {
        b.connectionListScrollList.filterChipParentGroup.visibility = View.VISIBLE
    }

    private fun hideParentChipsUi() {
        b.connectionListScrollList.filterChipParentGroup.visibility = View.GONE
    }
}
