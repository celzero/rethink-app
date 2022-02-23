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
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionTrackerAdapter
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.databinding.ActivityConnectionTrackerBinding
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Utilities
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
    private val dnsLogTracker by inject<DnsLogTracker>()

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

        if (!persistentState.logsEnabled) {
            b.connectionListLogsDisabledTv.visibility = View.VISIBLE
            b.connectionCardViewTop.visibility = View.GONE
            return
        }

        b.connectionListLogsDisabledTv.visibility = View.GONE
        b.connectionCardViewTop.visibility = View.VISIBLE

        b.recyclerConnection.setHasFixedSize(true)
        layoutManager = CustomLinearLayoutManager(requireContext())
        b.recyclerConnection.layoutManager = layoutManager
        val recyclerAdapter = ConnectionTrackerAdapter(this)
        viewModel.connectionTrackerList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter::submitList))
        b.recyclerConnection.adapter = recyclerAdapter

        setupRecyclerScrollListener()

        b.connectionSearch.setOnQueryTextListener(this)
        b.connectionSearch.setOnClickListener {
            showParentChipsUi()
            showChildChipsIfNeeded()
            b.connectionSearch.requestFocus()
            b.connectionSearch.onActionViewExpanded()
        }

        b.connectionFilterIcon.setOnClickListener {
            toggleParentChipsUi()
        }

        b.connectionDeleteIcon.setOnClickListener {
            showDeleteDialog()
        }

        remakeParentFilterChipsUi()
        remakeChildFilterChipsUi(FirewallRuleset.getBlockedRules())
    }

    private fun setupRecyclerScrollListener() {
        val scrollListener = object : RecyclerView.OnScrollListener() {

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (recyclerView.getChildAt(0).tag == null) return

                val tag: Long = recyclerView.getChildAt(0).tag as Long

                if (dy > 0) {
                    b.connectionListScrollHeader.text = Utilities.formatToRelativeTime(tag)
                    b.connectionListScrollHeader.visibility = View.VISIBLE
                } else {
                    b.connectionListScrollHeader.visibility = View.GONE
                }
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

    private fun toggleParentChipsUi() {
        if (b.filterChipParentGroup.isVisible) {
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
        b.filterChipParentGroup.removeAllViews()

        val all = makeParentChip(TopLevelFilter.ALL.id, getString(R.string.ct_filter_parent_all),
                                 true)
        val allowed = makeParentChip(TopLevelFilter.ALLOWED.id,
                                     getString(R.string.ct_filter_parent_allowed), false)
        val blocked = makeParentChip(TopLevelFilter.BLOCKED.id,
                                     getString(R.string.ct_filter_parent_blocked), false)

        b.filterChipParentGroup.addView(all)
        b.filterChipParentGroup.addView(allowed)
        b.filterChipParentGroup.addView(blocked)
    }

    private fun makeParentChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
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
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
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
            go {
                connectionTrackerRepository.clearAllData()
            }
        }

        builder.setNegativeButton(getString(R.string.ct_delete_logs_negative_btn)) { _, _ ->
        }
        builder.create().show()
    }

    fun ipToDomain(ip: String): DnsLogTracker.DnsCacheRecord? {
        return dnsLogTracker.ipDomainLookup.getIfPresent(ip)
    }

    private fun remakeChildFilterChipsUi(categories: List<FirewallRuleset>) {
        b.filterChipGroup.removeAllViews()
        for (c in categories) {
            b.filterChipGroup.addView(makeChildChip(c.id, c.title))
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
        b.filterChipGroup.visibility = View.VISIBLE
    }

    private fun hideChildChipsUi() {
        b.filterChipGroup.visibility = View.GONE
    }

    private fun showParentChipsUi() {
        b.filterChipParentGroup.visibility = View.VISIBLE
    }

    private fun hideParentChipsUi() {
        b.filterChipParentGroup.visibility = View.GONE
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }
}
