/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.CombinedLoadStates
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.DetailedStatisticsActivity
import com.celzero.bravedns.ui.bottomsheet.SummaryStatsFilterBottomSheet
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.statistics.SummaryStatisticsScreen
import com.celzero.bravedns.ui.compose.statistics.SummaryStatisticsState
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SummaryStatisticsFragment : Fragment() {
    private val viewModel: SummaryStatisticsViewModel by viewModel()
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private var isVpnActive = false
    private var loadMoreInitialized = false
    private var state = SummaryStatisticsState()
    private val detachListeners = mutableListOf<() -> Unit>()
    private val adaptersByType = mutableMapOf<SummaryStatisticsType, SummaryStatisticsAdapter>()
    enum class SummaryStatisticsType(val tid: Int) {
        MOST_CONNECTED_APPS(0),
        MOST_BLOCKED_APPS(1),
        MOST_CONNECTED_ASN(2),
        MOST_BLOCKED_ASN(3),
        MOST_CONTACTED_DOMAINS(4),
        MOST_CONTACTED_COUNTRIES(5),
        MOST_BLOCKED_DOMAINS(6),
        MOST_CONTACTED_IPS(7),
        MOST_BLOCKED_IPS(8),
        TOP_ACTIVE_CONNS(9),
        MOST_BLOCKED_BLOCKLISTS(10);

        companion object {
            fun getType(t: Int): SummaryStatisticsType {
                return entries.find { it.tid == t } ?: MOST_CONNECTED_APPS
            }
        }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        state = SummaryStatisticsState()
        return ComposeView(inflater.context).apply {
            id = R.id.summary_statistics_compose
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { SummaryStatisticsScreen(state) } }
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        observeAppStart()
        initFilterChips()
    }
    private fun initView() {
        state.alpha = Utilities.isAlphaBuild()
        state.mode = StatsViewMode.fromId(persistentState.statsViewMode)
        state.providersAvailable = persistentState.downloadIpInfo
        state.dnsActive = appConfig.getBraveMode().isDnsActive()
        state.firewallActive = appConfig.getBraveMode().isFirewallActive()
        state.onMode = { mode ->
            if (mode != state.mode) {
                state.mode = mode
                persistentState.statsViewMode = mode.id
                if (mode == StatsViewMode.INSIGHTS) refreshInsightsTraffic()
            }
        }
        state.onTimeCategory = { selectTimeCategory(it) }
        state.onFilter = { showStatsFilterSheet() }
        state.onDetail = { openDetailedStatsUi(it) }
        state.onClose = { state.closeDialog = true }
        state.onConfirmClose = {
            VpnController.closeConnectionsIfNeeded(Constants.UID_EVERYBODY, "summ-stats-manual-close")
            Logger.i(LOG_TAG_UI, "closed connection for all apps")
            showToastUiCentered(requireContext(), getString(R.string.config_add_success_toast), Toast.LENGTH_LONG)
            logEvent("close connections", "Closed active connections for all apps from stats screen")
        }
        if (state.mode == StatsViewMode.INSIGHTS) refreshInsightsTraffic()
        setupSummary(SummaryStatisticsType.TOP_ACTIVE_CONNS, viewModel.getTopActiveConns)
        setupSummary(SummaryStatisticsType.MOST_CONNECTED_APPS, viewModel.getAllowedAppNetworkActivity)
        setupSummary(SummaryStatisticsType.MOST_BLOCKED_APPS, viewModel.getBlockedAppNetworkActivity)
        if (persistentState.downloadIpInfo) {
            setupSummary(SummaryStatisticsType.MOST_CONNECTED_ASN, viewModel.getMostConnectedASN).setTimeCategory(viewModel.getTimeCategory())
            setupSummary(SummaryStatisticsType.MOST_BLOCKED_ASN, viewModel.getMostBlockedASN).setTimeCategory(viewModel.getTimeCategory())
        }
        initLazySections()
        if (appConfig.getBraveMode().isDnsActive()) {
            val type = SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS
            val adapter = newPresenter(type)
            viewModel.mostBlockedBlocklists.observe(viewLifecycleOwner) { items ->
                adapter.submitData(viewLifecycleOwner.lifecycle, PagingData.from(items))
                state.sections.getValue(type).visible = items.isNotEmpty()
            }
        }
    }
    private fun initLazySections() {
        if (!loadMoreInitialized) {
            loadMoreInitialized = true
            // Prime exactly once per Fragment, before first observation of these switchMaps.
            viewModel.setLoadMoreClicked(true)
        }
        // A recreated view reattaches to the existing cached sources without reprime queries.
        if (appConfig.getBraveMode().isDnsActive()) {
            setupSummary(SummaryStatisticsType.MOST_CONTACTED_DOMAINS, viewModel.mcd).setTimeCategory(viewModel.getTimeCategory())
            setupSummary(SummaryStatisticsType.MOST_BLOCKED_DOMAINS, viewModel.mbd).setTimeCategory(viewModel.getTimeCategory())
        }
        if (appConfig.getBraveMode().isFirewallActive()) {
            setupSummary(SummaryStatisticsType.MOST_CONTACTED_IPS, viewModel.getMostContactedIps).setTimeCategory(viewModel.getTimeCategory())
            setupSummary(SummaryStatisticsType.MOST_BLOCKED_IPS, viewModel.getMostBlockedIps).setTimeCategory(viewModel.getTimeCategory())
            setupSummary(SummaryStatisticsType.MOST_CONTACTED_COUNTRIES, viewModel.getMostContactedCountries).setTimeCategory(viewModel.getTimeCategory())
        }
    }
    private fun newPresenter(type: SummaryStatisticsType): SummaryStatisticsAdapter {
        val adapter = SummaryStatisticsAdapter(requireContext(), persistentState, appConfig, type)
        adaptersByType[type] = adapter
        val section = state.sections.getValue(type)
        section.presenter = adapter
        val snapshot: () -> Unit = { section.items = adapter.snapshot().items }
        adapter.addSnapshotListener(snapshot)
        detachListeners += { adapter.removeSnapshotListener(snapshot) }
        return adapter
    }
    private fun setupSummary(type: SummaryStatisticsType, data: LiveData<PagingData<AppConnection>>): SummaryStatisticsAdapter {
        val adapter = newPresenter(type)
        data.observe(viewLifecycleOwner) { adapter.submitData(viewLifecycleOwner.lifecycle, it) }
        val section = state.sections.getValue(type)
        val loads: (CombinedLoadStates) -> Unit = { section.visible = !it.append.endOfPaginationReached || adapter.itemCount > 0 }
        adapter.addLoadStateListener(loads)
        detachListeners += { adapter.removeLoadStateListener(loads) }
        return adapter
    }
    override fun onResume() {
        super.onResume()
        val tc = viewModel.getTimeCategory()
        if (state.timeCategory != tc) selectTimeCategory(tc)
        handleTotalUsagesUi()
        if (state.mode == StatsViewMode.INSIGHTS) refreshInsightsTraffic()
    }
    private fun selectTimeCategory(timeCategory: SummaryStatisticsViewModel.TimeCategory) {
        if (timeCategory == state.timeCategory) return
        state.timeCategory = timeCategory
        viewModel.timeCategoryChanged(timeCategory)
        handleTotalUsagesUi()
        adaptersByType.values.forEach { it.setTimeCategory(timeCategory) }
        if (state.mode == StatsViewMode.INSIGHTS) refreshInsightsTraffic()
    }
    private fun handleTotalUsagesUi() {
        val vm = viewModel
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val usage = vm.totalUsage()
            withContext(Dispatchers.Main) { if (isAdded && view != null) state.listUsage = usage }
        }
    }
    private fun refreshInsightsTraffic() {
        val vm = viewModel
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val usage = vm.totalUsage()
            withContext(Dispatchers.Main) { if (isAdded && view != null) state.insightUsage = usage }
        }
    }
    private fun initFilterChips() {
        if (SummaryStatsFilterBottomSheet.filters.value == null) SummaryStatsFilterBottomSheet.filters.value = SummaryStatsFilterBottomSheet.SectionFilter.entries.toSet()
        SummaryStatsFilterBottomSheet.filters.observe(viewLifecycleOwner) { state.filters = it }
        state.filters = SummaryStatsFilterBottomSheet.filters.value.orEmpty()
    }
    private fun showStatsFilterSheet() {
        Logger.v(LOG_TAG_UI, "show summary stats filter sheet")
        SummaryStatsFilterBottomSheet().show(childFragmentManager, SummaryStatsFilterBottomSheet::class.java.simpleName)
    }
    private fun openDetailedStatsUi(type: SummaryStatisticsType) {
        val timeCategory = state.timeCategory.value
        val intent = Intent(requireContext(), DetailedStatisticsActivity::class.java)
        intent.putExtra(DetailedStatisticsActivity.INTENT_TYPE, type.tid)
        intent.putExtra(DetailedStatisticsActivity.INTENT_TIME_CATEGORY, timeCategory)
        startActivity(intent)
    }
    private fun observeAppStart() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner) { isVpnActive = it }
    }
    override fun onDestroyView() {
        detachListeners.forEach { it() }; detachListeners.clear(); adaptersByType.clear()
        super.onDestroyView()
    }
    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, true, details)
    }
    companion object { fun newInstance() = SummaryStatisticsFragment() }
}
