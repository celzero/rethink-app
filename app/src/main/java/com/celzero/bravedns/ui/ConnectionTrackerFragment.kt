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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionTrackerAdapter
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.databinding.ActivityConnectionTrackerBinding
import com.celzero.bravedns.service.DNSLogTracker
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import com.google.android.material.chip.Chip
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
    private var filterCategories: MutableSet<String> = mutableSetOf()
    private var filterType: TopLevelFilter = TopLevelFilter.ALL
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private val persistentState by inject<PersistentState>()
    private val dnsLogTracker by inject<DNSLogTracker>()

    companion object {
        fun newInstance() = ConnectionTrackerFragment()
    }

    enum class TopLevelFilter(val id: Int) {
        ALL(0), ALLOWED(1), BLOCKED(2)
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
            showParentChipsUi()
            showChildChipsIfNeeded()
            includeView.connectionSearch.requestFocus()
            includeView.connectionSearch.onActionViewExpanded()
        }

        includeView.connectionFilterIcon.setOnClickListener {
            toggleParentChipsUi()
        }

        includeView.connectionDeleteIcon.setOnClickListener {
            showDeleteDialog()
        }

        remakeParentFilterChipsUi()
        remakeChildFilterChipsUi(FirewallRuleset.getBlockedRules())
    }

    private fun toggleParentChipsUi() {
        val includeView = b.connectionListScrollList

        if (includeView.filterChipParentGroup.isVisible) {
            hideParentChipsUi()
            hideChildChipsUi()
        } else {
            showParentChipsUi()
            showChildChipsIfNeeded()
        }
    }

    private fun showChildChipsIfNeeded() {
        when (filterType) {
            TopLevelFilter.ALL -> {
                hideChildChipsUi()
            }
            TopLevelFilter.ALLOWED -> {
                showChildChipsUi()
            }
            TopLevelFilter.BLOCKED -> {
                showChildChipsUi()
            }
        }
    }

    private fun remakeParentFilterChipsUi() {
        val includeView = b.connectionListScrollList
        includeView.filterChipParentGroup.removeAllViews()

        val all = makeParentChip(TopLevelFilter.ALL.id, getString(R.string.ct_filter_parent_all),
                                 true)
        val allowed = makeParentChip(TopLevelFilter.ALLOWED.id,
                                     getString(R.string.ct_filter_parent_allowed), false)
        val blocked = makeParentChip(TopLevelFilter.BLOCKED.id,
                                     getString(R.string.ct_filter_parent_blocked), false)

        includeView.filterChipParentGroup.addView(all)
        includeView.filterChipParentGroup.addView(allowed)
        includeView.filterChipParentGroup.addView(blocked)
    }

    private fun makeParentChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) { // apply filter only when the CompoundButton is selected
                applyParentFilter(button.tag)
            } else { // actions need to be taken when the button is unselected
                unselectParentsChipsUi(button.tag)
            }
        }

        return chip
    }

    private fun makeChildChip(id: String, titleResId: Int): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip
        chip.text = getString(titleResId)
        chip.chipIcon = ContextCompat.getDrawable(requireContext(),
                                                  FirewallRuleset.getRulesIcon(id))
        chip.isCheckedIconVisible = false
        chip.tag = id

        chip.setOnCheckedChangeListener { compoundButton: CompoundButton, isSelected: Boolean ->
            applyChildFilter(compoundButton.tag, isSelected)
        }
        return chip
    }

    private fun applyParentFilter(tag: Any) {
        when (tag) {
            TopLevelFilter.ALL.id -> {
                filterCategories.clear()
                filterType = TopLevelFilter.ALL
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                hideChildChipsUi()
            }
            TopLevelFilter.ALLOWED.id -> {
                filterCategories.clear()
                filterType = TopLevelFilter.ALLOWED
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                remakeChildFilterChipsUi(FirewallRuleset.getAllowedRules())
                showChildChipsUi()
            }
            TopLevelFilter.BLOCKED.id -> {
                filterType = TopLevelFilter.BLOCKED
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                remakeChildFilterChipsUi(FirewallRuleset.getBlockedRules())
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
            io {
                connectionTrackerRepository.clearAllData()
            }
        }

        builder.setNegativeButton(getString(R.string.ct_delete_logs_negative_btn)) { _, _ ->
        }
        builder.create().show()
    }

    fun ipToDomain(ip: String): DNSLogTracker.DnsCacheRecord? {
        return dnsLogTracker.ipDomainLookup.getIfPresent(ip)
    }

    private fun remakeChildFilterChipsUi(categories: List<FirewallRuleset>) {
        val v = b.connectionListScrollList

        v.filterChipGroup.removeAllViews()
        for (c in categories) {
            v.filterChipGroup.addView(makeChildChip(c.id, c.title))
        }
    }

    private fun applyChildFilter(tag: Any, show: Boolean) {
        if (show) {
            filterCategories.add(tag.toString())
        } else {
            filterCategories.remove(tag.toString())
        }
        viewModel.setFilter(filterQuery, filterCategories, filterType)
    }

    // chips: all, allowed, blocked
    // when any chip other than "all" is selected, show the child chips.
    // ignore unselect events from allowed and blocked chip
    private fun unselectParentsChipsUi(tag: Any) {
        when (tag) {
            TopLevelFilter.ALL.id -> {
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

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }
}
