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
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import android.content.Context.INPUT_METHOD_SERVICE
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
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
import com.celzero.bravedns.databinding.FragmentConnectionTrackerBinding
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils.formatToRelativeTime
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel.TopLevelFilter
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/** Captures network logs and stores in ConnectionTracker, a room database. */
class ConnectionTrackerFragment :
    Fragment(R.layout.fragment_connection_tracker), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentConnectionTrackerBinding::bind)

    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: ConnectionTrackerViewModel by viewModel()

    private var filterQuery: String = ""
    private var filterCategories: MutableSet<String> = mutableSetOf()
    private var filterType: TopLevelFilter = TopLevelFilter.ALL
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private val persistentState by inject<PersistentState>()

    private var fromWireGuardScreen: Boolean = false
    private var fromUniversalFirewallScreen: Boolean = false

    companion object {
        private const val TAG = "ConnTrackFrag"
        const val PROTOCOL_FILTER_PREFIX = "P:"
        private const val QUERY_TEXT_DELAY: Long = 1000

        fun newInstance(param: String): ConnectionTrackerFragment {
            val args = Bundle()
            args.putString(Constants.SEARCH_QUERY, param)
            val fragment = ConnectionTrackerFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (arguments != null) {
            val query = arguments?.getString(Constants.SEARCH_QUERY) ?: ""
            fromUniversalFirewallScreen = query.contains(UniversalFirewallSettingsActivity.RULES_SEARCH_ID)
            fromWireGuardScreen = query.contains(NetworkLogsActivity.RULES_SEARCH_ID_WIREGUARD)
            if (fromUniversalFirewallScreen) {
                val rule = query.split(UniversalFirewallSettingsActivity.RULES_SEARCH_ID)[1]
                filterCategories.add(rule)
                filterType = TopLevelFilter.BLOCKED
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                hideSearchLayout()
            } else if (fromWireGuardScreen) {
                val rule = query.split(NetworkLogsActivity.RULES_SEARCH_ID_WIREGUARD)[1]
                filterQuery = rule
                filterType = TopLevelFilter.ALL
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                hideSearchLayout()
            } else {
                b.connectionSearch.setQuery(query, true)
                viewModel.setFilter(query, filterCategories, filterType)
                setQueryFilter()
            }
        }
        initView()
        Logger.v(LOG_TAG_UI, "$TAG, view created from univ? $fromUniversalFirewallScreen, from wg? $fromWireGuardScreen")
    }

    private fun initView() {
        if (!persistentState.logsEnabled) {
            b.connectionListLogsDisabledTv.visibility = View.VISIBLE
            b.connectionCardViewTop.visibility = View.GONE
            return
        }

        b.connectionListLogsDisabledTv.visibility = View.GONE

        if (fromWireGuardScreen || fromUniversalFirewallScreen) {
            hideSearchLayout()
        } else {
            b.connectionCardViewTop.visibility = View.VISIBLE
        }
        b.connectionSearch.setOnQueryTextListener(this)

        setupRecyclerView()

        b.connectionSearch.setOnClickListener {
            showParentChipsUi()
            showChildChipsIfNeeded()
            b.connectionSearch.requestFocus()
            b.connectionSearch.onActionViewExpanded()
        }

        b.connectionFilterIcon.setOnClickListener { toggleParentChipsUi() }

        b.connectionDeleteIcon.setOnClickListener { showDeleteDialog() }

        remakeParentFilterChipsUi()
        remakeChildFilterChipsUi(FirewallRuleset.getBlockedRules())
    }

    private fun setupRecyclerView() {
        b.recyclerConnection.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        layoutManager?.isItemPrefetchEnabled = true
        b.recyclerConnection.layoutManager = layoutManager

        val recyclerAdapter = ConnectionTrackerAdapter(requireContext())
        recyclerAdapter.stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

        b.recyclerConnection.adapter = recyclerAdapter

        viewModel.connectionTrackerList.observe(viewLifecycleOwner) { pagingData ->
            recyclerAdapter.submitData(lifecycle, pagingData)
        }

        recyclerAdapter.addLoadStateListener { loadState ->
            val isEmpty = recyclerAdapter.itemCount < 1
            if (loadState.append.endOfPaginationReached && isEmpty) {
                if (fromUniversalFirewallScreen || fromWireGuardScreen) {
                    b.connectionListLogsDisabledTv.text = getString(R.string.ada_ip_no_connection)
                    b.connectionListLogsDisabledTv.visibility = View.VISIBLE
                    b.connectionCardViewTop.visibility = View.GONE
                } else {
                    b.connectionListLogsDisabledTv.visibility = View.GONE
                    b.connectionCardViewTop.visibility = View.VISIBLE
                }
                viewModel.connectionTrackerList.removeObservers(this)
                b.recyclerConnection.visibility = View.GONE
            } else {
                b.connectionListLogsDisabledTv.visibility = View.GONE
                if (!b.recyclerConnection.isVisible) b.recyclerConnection.visibility = View.VISIBLE
                if (fromUniversalFirewallScreen || fromWireGuardScreen) {
                    b.connectionCardViewTop.visibility = View.GONE
                } else {
                    b.connectionCardViewTop.visibility = View.VISIBLE
                }
            }
        }

        b.recyclerConnection.post {
            try {
                if (recyclerAdapter.itemCount > 0) {
                    recyclerAdapter.stateRestorationPolicy =
                        RecyclerView.Adapter.StateRestorationPolicy.ALLOW
                }
            } catch (_: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG; err in setting the recycler restoration policy")
            }
        }
        b.recyclerConnection.layoutAnimation = null
        setupRecyclerScrollListener()
    }


    private fun hideSearchLayout() {
        b.connectionCardViewTop.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // fix for #1939, OEM-specific bug, especially on heavily customized Android
        // some ROMs kill or freeze the keyboard/IME process to save memory or battery,
        // causing SearchView to stop receiving input events
        // this is a workaround to restart the IME process
        //b.connectionSearch.setQuery("", false)
        b.connectionSearch.clearFocus()

        val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(b.connectionSearch)
        b.connectionListRl.requestFocus()
    }

    private fun setupRecyclerScrollListener() {
        val scrollListener =
            object : RecyclerView.OnScrollListener() {

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val firstChild = recyclerView.getChildAt(0)
                    if (firstChild == null) {
                        Logger.v(LOG_TAG_UI, "$TAG; err; no child views found in recyclerView")
                        return
                    }

                    val tag = firstChild.tag as? Long
                    if (tag == null) {
                        Logger.v(LOG_TAG_UI, "$TAG; err; tag is null for first child, rv")
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

        val all = makeParentChip(TopLevelFilter.ALL.id, getString(R.string.lbl_all), true)
        val allowed =
            makeParentChip(TopLevelFilter.ALLOWED.id, getString(R.string.lbl_allowed), false)
        val blocked =
            makeParentChip(TopLevelFilter.BLOCKED.id, getString(R.string.lbl_blocked), false)

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
        chip.chipIcon =
            ContextCompat.getDrawable(requireContext(), FirewallRuleset.getRulesIcon(id))
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

    @OptIn(FlowPreview::class)
    private fun setQueryFilter() {
        lifecycleScope.launch {
            searchQuery
                .debounce(QUERY_TEXT_DELAY)
                .distinctUntilChanged()
                .collect { query ->
                    filterQuery = query
                    viewModel.setFilter(query, filterCategories, filterType)
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
        val rule = filterCategories.firstOrNull()
        if (fromUniversalFirewallScreen && rule != null) {
            // Rule-specific deletion for Universal Firewall Settings
            MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                .setTitle(R.string.conn_track_clear_rule_logs_title)
                .setMessage(R.string.conn_track_clear_rule_logs_message)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
                    io { connectionTrackerRepository.clearLogsByRule(rule) }
                }
                .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
                .create()
                .show()
        } else {
            // Default deletion behavior - delete all logs
            MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                .setTitle(R.string.conn_track_clear_logs_title)
                .setMessage(R.string.conn_track_clear_logs_message)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
                    io { connectionTrackerRepository.clearAllData() }
                }
                .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
                .create()
                .show()
        }
    }

    private fun remakeChildFilterChipsUi(categories: List<FirewallRuleset>) {
        with(b.filterChipGroup) {
            removeAllViews()
            for (c in categories) {
                addView(makeChildChip(c.id, c.title))
            }
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

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
