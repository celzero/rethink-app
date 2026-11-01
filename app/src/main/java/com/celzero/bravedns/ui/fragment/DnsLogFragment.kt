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

class DnsLogFragment : Fragment() {
    private val viewModel: DnsLogViewModel by viewModel()
    private var filterValue = ""
    private var filterType = DnsLogFilter.ALL
    private var fromWireGuardScreen = false
    private var fromRpnScreen = false
    private val dnsLogRepository by inject<DnsLogRepository>()
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val logActivityAggregator by inject<LogActivityAggregator>()
    private lateinit var state: LogScreenState
    private var selectedChip by mutableIntStateOf(DnsLogFilter.ALL.id)
    private var presenter: DnsLogAdapter? = null
    companion object {
        private const val QUERY_TEXT_DELAY = 1000L
        fun newInstance(param: String) = DnsLogFragment().apply { arguments = Bundle().apply { putString(Constants.SEARCH_QUERY, param) } }
    }
    enum class DnsLogFilter(val id: Int) { ALL(0), ALLOWED(1), BLOCKED(2), MAYBE_BLOCKED(3), UNKNOWN_RECORDS(4) }
    private fun resetViewPresentation() { selectedChip = DnsLogFilter.ALL.id; presenter = null }

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
        initView()
        (view as ComposeView).setContent { RethinkTheme { Surface(color = themeColor(R.attr.background), contentColor = themeColor(R.attr.primaryTextColor)) { Content() } } }
        if (arguments != null) {
            val query = arguments?.getString(Constants.SEARCH_QUERY) ?: ""
            fromWireGuardScreen = query.contains(RULES_SEARCH_ID_WIREGUARD)
            fromRpnScreen = query.contains(RULES_SEARCH_ID_RPN)
            if (fromWireGuardScreen) {
                val wgId = query.substringAfter(RULES_SEARCH_ID_WIREGUARD)
                hideSearchLayout()
                viewModel.setIsWireGuardLogs(true, wgId)
            } else if (fromRpnScreen) {
                val rpnId = query.substringAfter(RULES_SEARCH_ID_RPN)
                hideSearchLayout()
                viewModel.setIsRpnLogs(true, rpnId)
            } else {
                if (query.isEmpty()) return
                if (query.contains(UniversalFirewallSettingsActivity.RULES_SEARCH_ID)) {
                    // do nothing, as the search is for the firewall rules and not for the dns
                    return
                }
                state.search.setQuery(query, true)
            }
        }
    }

    private fun initView() {
        if (!persistentState.logsEnabled) { state.message = getString(R.string.show_logs_disabled_dns_message); state.showSearch = false; return }
        val adapter = DnsLogAdapter(requireContext(), persistentState.fetchFavIcon, appConfig.isRethinkDnsConnected()); presenter = adapter
        viewModel.dnsLogsList.observe(viewLifecycleOwner) { adapter.submitData(viewLifecycleOwner.lifecycle, it) }
        adapter.addLoadStateListener { loadState ->
            if (loadState.append.endOfPaginationReached && adapter.itemCount < 1) { viewModel.dnsLogsList.removeObservers(this); state.showList = false } else state.showList = true
        }
        state.search.listener = { onQueryTextChange(it) }; state.search.submitListener = { onQueryTextSubmit(it) }
        setQueryFilter()
    }
    private fun hideSearchLayout() { state.showSearch = false }
    override fun onResume() { super.onResume(); state.resumeGeneration++; viewModel.setFilter(filterValue, filterType) }
    private fun showChipsUi() { state.showParentFilters = true }
    private fun hideChipsUi() { state.showParentFilters = false }
    private fun toggleChipsUi() { state.showParentFilters = !state.showParentFilters }
    fun onQueryTextSubmit(query: String): Boolean {
        searchQuery.value = query
        return true
    }
    fun onQueryTextChange(query: String): Boolean {
        searchQuery.value = query
        return true
    }
    @OptIn(FlowPreview::class)
    private fun setQueryFilter() {
        lifecycleScope.launch {
            searchQuery
                .debounce(QUERY_TEXT_DELAY)
                .distinctUntilChanged()
                .collect { query ->
                    filterValue = query
                    viewModel.setFilter(filterValue, filterType)
                }
        }
    }
    private fun showDnsLogsDeleteDialog() {
        WgAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(R.string.dns_query_clear_logs_title)
            .setMessage(R.string.dns_query_clear_logs_message)
            .setCancelable(true)
            .setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
                io {
                    Glide.get(requireActivity()).clearDiskCache()
                    dnsLogRepository.clearAllData()
                    // deletion committed; rebuild the home-screen activity
                    // wall so its counts shed the deleted rows
                    logActivityAggregator.forceRestoreFromDatabase()
                }
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
            .create()
            .show()
    }
    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    val searchQuery = MutableStateFlow("")
    @Composable private fun Content() {
        Column(Modifier.fillMaxSize().background(themeColor(R.attr.background))) {
            LogSearchBar(state, stringResource(R.string.search_firewall_network_log), onSearchFocus = { showChipsUi(); state.search.expand() }, filters = {
                if (state.showParentFilters) {
                    val options = listOf(DnsLogFilter.ALL to R.string.lbl_all, DnsLogFilter.ALLOWED to R.string.lbl_allowed, DnsLogFilter.MAYBE_BLOCKED to R.string.lbl_maybe_blocked, DnsLogFilter.BLOCKED to R.string.lbl_blocked, DnsLogFilter.UNKNOWN_RECORDS to R.string.network_log_app_name_unknown)
                    LogFilterChips(options.map { LogFilterChip(it.first.id.toString(), stringResource(it.second), selectedChip == it.first.id) }) {
                        if (!it.selected) { selectedChip = it.key.toInt(); filterType = DnsLogFilter.entries.first { it.id == selectedChip }; viewModel.setFilter(filterValue, filterType) }
                    }
                }
            }, actions = {
                LogAction(R.drawable.ic_filter, stringResource(R.string.fapps_filter_filter_heading), ::toggleChipsUi)
                LogAction(R.drawable.ic_delete, stringResource(R.string.lbl_delete), ::showDnsLogsDeleteDialog)
            })
            presenter?.let { LogList(state, it, Modifier.weight(1f), timestamp = { row -> row.time }) } ?: state.message?.let { Text(it) }
        }
    }
}
