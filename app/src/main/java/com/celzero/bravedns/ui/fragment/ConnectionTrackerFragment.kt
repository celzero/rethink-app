/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.*
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_WIREGUARD
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.trafficlogs.*
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.time.Duration.Companion.milliseconds
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel.TopLevelFilter

class ConnectionTrackerFragment : Fragment() {

    private val viewModel: ConnectionTrackerViewModel by viewModel()
    private var filterQuery = ""
    private val filterCategories: MutableSet<String> = mutableSetOf()
    private var filterType = TopLevelFilter.ALL
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private val rethinkLogRepository by inject<RethinkLogRepository>()
    private val persistentState by inject<PersistentState>()
    private val logActivityAggregator by inject<LogActivityAggregator>()
    private var fromWireGuardScreen = false
    private var fromRpnScreen = false
    private var fromUniversalFirewallScreen = false
    private lateinit var state: LogScreenState
    private var parentChip by mutableIntStateOf(TopLevelFilter.ALL.id)
    private var childChips by mutableStateOf(emptySet<String>())
    private var children by mutableStateOf(emptyList<FirewallRuleset>())
    private var merged: ConnectionLogAdapter? = null
    private var connections: ConnectionTrackerAdapter? = null
    companion object {
        private const val TAG = "ConnTrackFrag"
        private const val QUERY_TEXT_DELAY = 1000L
        const val PROTOCOL_FILTER_PREFIX = "P:"
        const val MERGE_RETHINK_LOGS = true
        fun newInstance(param: String) = ConnectionTrackerFragment().apply { arguments = Bundle().apply { putString(Constants.SEARCH_QUERY, param) } }
    }
    private fun resetViewPresentation() { parentChip = TopLevelFilter.ALL.id; childChips = emptySet(); children = FirewallRuleset.getBlockedRules(); merged = null; connections = null }

    private var viewSearchSnapshot: String? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        state = LogScreenState()
        resetViewPresentation()
        return ComposeView(requireContext()).apply { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("traffic.search", if (::state.isInitialized) state.search.query else viewSearchSnapshot)
    }
    override fun onDestroyView() {
        viewSearchSnapshot = state.search.query
        super.onDestroyView()
    }
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // TextView hierarchy restoration happens after arguments and listener installation.
        (savedInstanceState?.getString("traffic.search") ?: viewSearchSnapshot)?.let { state.search.setQuery(it) }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (arguments != null) {
            val query = arguments?.getString(Constants.SEARCH_QUERY) ?: ""
            fromUniversalFirewallScreen = query.contains(UniversalFirewallSettingsActivity.RULES_SEARCH_ID)
            fromWireGuardScreen = query.contains(NetworkLogsActivity.RULES_SEARCH_ID_WIREGUARD)
            fromRpnScreen = query.contains(NetworkLogsActivity.RULES_SEARCH_ID_RPN)
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
            } else if (fromRpnScreen) {
                val rule = query.split(NetworkLogsActivity.RULES_SEARCH_ID_RPN)[1]
                filterQuery = rule
                filterType = TopLevelFilter.ALL
                viewModel.setFilter(filterQuery, filterCategories, filterType)
                hideSearchLayout()
            } else {
                state.search.setQuery(query, true)
                viewModel.setFilter(query, filterCategories, filterType)
                setQueryFilter()
            }
        }
        initView()
        (view as ComposeView).setContent { RethinkTheme { Surface(color = themeColor(R.attr.background), contentColor = themeColor(R.attr.primaryTextColor)) { Content() } } }
        Logger.v(LOG_TAG_UI, "$TAG, view created from univ? $fromUniversalFirewallScreen, from wg? $fromWireGuardScreen")
    }

    private fun initView() {
        if (!persistentState.logsEnabled) { state.message = getString(R.string.show_logs_disabled_network_message); state.showSearch = false; return }
        state.showSearch = !(fromWireGuardScreen || fromUniversalFirewallScreen)
        state.search.listener = { onQueryTextChange(it) }
        state.search.submitListener = { onQueryTextSubmit(it) }
        if (MERGE_RETHINK_LOGS) {
            val adapter = ConnectionLogAdapter(requireContext()); merged = adapter
            viewModel.connectionTrackerListMerged.observe(viewLifecycleOwner) { adapter.submitData(lifecycle, it) }
            adapter.addLoadStateListener { updateLoadState(it, adapter.itemCount) { viewModel.connectionTrackerListMerged.removeObservers(this) } }
        } else {
            val adapter = ConnectionTrackerAdapter(requireContext()); connections = adapter
            viewModel.connectionTrackerList.observe(viewLifecycleOwner) { adapter.submitData(lifecycle, it) }
            adapter.addLoadStateListener { updateLoadState(it, adapter.itemCount) { viewModel.connectionTrackerList.removeObservers(this) } }
        }
    }
    private fun updateLoadState(loadState: androidx.paging.CombinedLoadStates, count: Int, remove: () -> Unit) {
        if (loadState.refresh is LoadState.Loading && count < 1) { state.message = getString(R.string.loading); state.showList = false; return }
        val scoped = fromUniversalFirewallScreen || fromWireGuardScreen || fromRpnScreen
        if (loadState.append.endOfPaginationReached && count < 1) {
            state.message = if (scoped) getString(R.string.ada_ip_no_connection) else null
            state.showSearch = !scoped; remove(); state.showList = false
        } else { state.message = null; state.showList = true; state.showSearch = !scoped }
    }
    override fun onResume() { super.onResume(); state.resumeGeneration++ }
    private fun hideSearchLayout() { state.showSearch = false }
    private fun toggleParentChipsUi() { if (state.showParentFilters) { hideParentChipsUi(); hideChildChipsUi() } else { showParentChipsUi(); showChildChipsIfNeeded() } }
    private fun showParentChipsUi() { state.showParentFilters = true }
    private fun hideParentChipsUi() { state.showParentFilters = false }
    private fun showChildChipsUi() { state.showChildFilters = true }
    private fun hideChildChipsUi() { state.showChildFilters = false }
    private fun remakeChildFilterChipsUi(categories: List<FirewallRuleset>) { children = categories; childChips = emptySet() }
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
                .debounce(QUERY_TEXT_DELAY.milliseconds)
                .distinctUntilChanged()
                .collect { query ->
                    filterQuery = query
                    viewModel.setFilter(query, filterCategories, filterType)
                }
        }
    }
    fun onQueryTextSubmit(query: String): Boolean {
        searchQuery.value = query
        return true
    }
    fun onQueryTextChange(query: String): Boolean {
        searchQuery.value = query
        return true
    }
    private fun showDeleteDialog() {
        val rule = filterCategories.firstOrNull()
        if (fromUniversalFirewallScreen && rule != null) {
            // Rule-specific deletion for Universal Firewall Settings
            WgAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                .setTitle(R.string.conn_track_clear_rule_logs_title)
                .setMessage(R.string.conn_track_clear_rule_logs_message)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
                    io {
                        connectionTrackerRepository.clearLogsByRule(rule)
                        if (MERGE_RETHINK_LOGS) {
                            rethinkLogRepository.clearLogsByRule(rule)
                        }
                        // rebuild the home-screen activity wall
                        logActivityAggregator.forceRestoreFromDatabase()
                    }
                }
                .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
                .create()
                .show()
        } else {
            // Default deletion behavior - delete all logs
            WgAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                .setTitle(R.string.conn_track_clear_logs_title)
                .setMessage(R.string.conn_track_clear_logs_message)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
                    io {
                        connectionTrackerRepository.clearAllData()
                        if (MERGE_RETHINK_LOGS) {
                            rethinkLogRepository.clearAllData()
                        }
                        // rebuild the home-screen activity wall
                        logActivityAggregator.forceRestoreFromDatabase()
                    }
                }
                .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
                .create()
                .show()
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
    private fun unselectParentsChipsUi(tag: Any) {
        when (tag) {
            TopLevelFilter.ALL.id -> {
                showChildChipsUi()
            }
        }
    }
    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    val searchQuery = MutableStateFlow("")
    @Composable private fun Content() {
        Column(Modifier.fillMaxSize().background(themeColor(R.attr.background))) {
            LogSearchBar(state, stringResource(R.string.search_firewall_network_log), onSearchFocus = { showParentChipsUi(); showChildChipsIfNeeded(); state.search.expand() }, filters = {
                if (state.showParentFilters) {
                    val options = listOf(TopLevelFilter.ALL to R.string.lbl_all, TopLevelFilter.ALLOWED to R.string.lbl_allowed, TopLevelFilter.BLOCKED to R.string.lbl_blocked)
                    LogFilterChips(options.map { LogFilterChip(it.first.id.toString(), stringResource(it.second), parentChip == it.first.id) }) {
                        if (!it.selected) { val old = parentChip; parentChip = it.key.toInt(); unselectParentsChipsUi(old); applyParentFilter(parentChip) }
                        else unselectParentsChipsUi(parentChip) // selectionRequired restores the internal check before the external listener.
                    }
                }
                if (state.showChildFilters) LogFilterChips(children.map { LogFilterChip(it.id, stringResource(it.title), it.id in childChips, FirewallRuleset.getRulesIcon(it.id)) }, singleLine = true) {
                    childChips = if (it.selected) childChips - it.key else childChips + it.key
                    applyChildFilter(it.key, !it.selected)
                }
            }, actions = {
                LogAction(R.drawable.ic_filter, stringResource(R.string.fapps_filter_filter_heading), ::toggleParentChipsUi)
                LogAction(R.drawable.ic_delete, stringResource(R.string.lbl_delete), ::showDeleteDialog)
            })
            merged?.let { LogList(state, it, Modifier.weight(1f), timestamp = { row -> row.timeStamp }, headerThrottleMillis = 100) }
            connections?.let { LogList(state, it, Modifier.weight(1f), timestamp = { row -> row.timeStamp }, headerThrottleMillis = 100) }
            if (merged == null && connections == null) state.message?.let { Text(it) }
        }
    }
}
