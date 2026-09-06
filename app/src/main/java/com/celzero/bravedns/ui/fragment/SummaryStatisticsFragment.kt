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
package com.celzero.bravedns.ui.fragment

import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.FragmentSummaryStatisticsBinding
import com.celzero.bravedns.databinding.ViewInsightsRankRowBinding
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.DetailedStatisticsActivity
import com.celzero.bravedns.ui.custom.DonutChartView
import com.celzero.bravedns.ui.stats.CountryInsightsMapper
import com.celzero.bravedns.ui.stats.StatsInsightsMath
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import androidx.lifecycle.LiveData
import androidx.paging.PagingData
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SummaryStatisticsFragment : Fragment(R.layout.fragment_summary_statistics) {
    private val b by viewBinding(FragmentSummaryStatisticsBinding::bind)

    private val viewModel: SummaryStatisticsViewModel by viewModel()
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private var isVpnActive: Boolean = false
    private var loadMoreInitialized: Boolean = false

    // current Stats presentation mode; persisted across sessions via PersistentState
    private var statsViewMode: StatsViewMode = StatsViewMode.INSIGHTS

    // latest snapshot per section, kept in sync with the (shared) adapters so
    // switching to Insights renders instantly without refetching anything
    private val insightsSnapshots = mutableMapOf<SummaryStatisticsType, List<AppConnection>>()

    // theme-resolved Insights colors; refreshed on every full render
    private var allowedColor: Int = 0
    private var blockedColor: Int = 0
    private var trackColor: Int = 0
    private var centerTextColor: Int = 0

    // adapters keyed by section type; used to propagate time-category changes
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
        TOP_ACTIVE_CONNS(9);

        companion object {
            fun getType(t: Int): SummaryStatisticsType {
                return entries.find { it.tid == t } ?: MOST_CONNECTED_APPS
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // all of these are main-thread operations; running them directly avoids
        // two unnecessary thread hops and renders the first frame sooner
        initView()
        observeAppStart()
        initClickListeners()
    }

    private fun initView() {
        // Show "α" badge next to the app name when running an alpha build so testers
        // can immediately identify they are on a pre-release version.
        if (Utilities.isAlphaBuild()) {
            b.fssTitleRethink.setText(R.string.app_name_alpha)
            b.fssTitleRethink.isAllCaps = false
        }
        // restore the persisted presentation mode before listeners attach
        // (setting checkedButton here does not fire the toggle listener)
        statsViewMode = StatsViewMode.fromId(persistentState.statsViewMode)
        val modeBtn = b.fssViewModeToggleGroup.findViewById<MaterialButton>(
            if (statsViewMode == StatsViewMode.INSIGHTS) {
                b.fssViewModeInsightsBtn.id
            } else {
                b.fssViewModeListBtn.id
            }
        )
        modeBtn.isChecked = true
        applyViewModeUi()
        setTabbedViewTxt()
        highlightToggleBtn()
        showTopActiveApps()
        showAppNetworkActivity()
        showBlockedApps()
        if (persistentState.downloadIpInfo) {
            showMostConnectedASN()
            showMostBlockedASN()
        } else {
            b.fssAsnAllowedLl.visibility = View.GONE
            b.fssAsnBlockedLl.visibility = View.GONE
            b.fssIaAsnAllowedLl.visibility = View.GONE
            b.fssIaAsnBlockedLl.visibility = View.GONE
        }
        // load domain/ip/country sections eagerly; previously these were gated
        // behind the (now removed) "load more" FAB
        initLazySections()
    }

    /** Flips the two presentation containers; does not touch any data state. */
    private fun applyViewModeUi() {
        val insights = statsViewMode == StatsViewMode.INSIGHTS
        b.fssListContainer.visibility = if (insights) View.GONE else View.VISIBLE
        b.fssInsightsContainer.visibility = if (insights) View.VISIBLE else View.GONE
        refreshViewModeToggleUi()
        if (insights) {
            renderInsights()
            refreshInsightsTraffic()
        }
    }

    /** Applies the selected/unselected styling to both view-mode buttons. */
    private fun refreshViewModeToggleUi() {
        styleViewModeBtn(b.fssViewModeListBtn)
        styleViewModeBtn(b.fssViewModeInsightsBtn)
    }

    private fun styleViewModeBtn(mb: MaterialButton) {
        // derive selection from statsViewMode (not isChecked): the group fires
        // the checked/unchecked pair in quick succession and isChecked can be
        // mid-transition, which previously left both buttons looking selected
        val selected =
            (mb.id == b.fssViewModeInsightsBtn.id) == (statsViewMode == StatsViewMode.INSIGHTS)
        if (selected) {
            mb.backgroundTintList =
                ColorStateList.valueOf(
                    UIUtils.fetchToggleBtnColors(requireContext(), R.color.accentGood)
                )
            mb.iconTint =
                ColorStateList.valueOf(
                    UIUtils.fetchColor(requireContext(), R.attr.homeScreenHeaderTextColor)
                )
        } else {
            mb.backgroundTintList =
                ColorStateList.valueOf(
                    UIUtils.fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg)
                )
            mb.iconTint =
                ColorStateList.valueOf(
                    UIUtils.fetchColor(requireContext(), R.attr.defaultToggleBtnTxt)
                )
        }
    }

    private fun setTabbedViewTxt() {
        b.tbRecentToggleBtn.text = getString(R.string.ci_desc, "1", getString(R.string.lbl_hour))
        b.tbDailyToggleBtn.text = getString(R.string.ci_desc, "24", getString(R.string.lbl_hour))
        b.tbWeeklyToggleBtn.text = getString(R.string.ci_desc, "7", getString(R.string.lbl_day))
    }

    override fun onResume() {
        super.onResume()
        // get the tabbed view from the view model and set the toggle button
        // to the selected one. in case of fragment resume, the recycler view
        // and the toggle button to be in sync
        val tc = viewModel.getTimeCategory().value.toString()
        val btn = b.toggleGroup.findViewWithTag<MaterialButton>(tc)
        btn.isChecked = true
        refreshViewModeToggleUi()
        handleTotalUsagesUi()
        if (statsViewMode == StatsViewMode.INSIGHTS) {
            refreshInsightsTraffic()
        }
    }

    private fun handleTotalUsagesUi() {
        io {
            val totalUsage = viewModel.totalUsage()
            uiCtx { setTotalUsagesUi(totalUsage) }
        }
    }

    private fun setTotalUsagesUi(dataUsage: DataUsageSummary) {
        val totalUsage = (dataUsage.totalDownload + dataUsage.totalUpload)
        val unmeteredUsage = totalUsage - dataUsage.meteredDataUsage
        val meteredUsage = dataUsage.meteredDataUsage

        b.fssUnmeteredDataUsage.text =
            getString(
                R.string.two_argument_colon,
                getString(R.string.ada_app_unmetered),
                Utilities.humanReadableByteCount(unmeteredUsage, true)
            )
        b.fssMeteredDataUsage.text =
            getString(
                R.string.two_argument_colon,
                getString(R.string.ada_app_metered),
                Utilities.humanReadableByteCount(meteredUsage, true)
            )
        b.fssTotalDataUsage.text =
            getString(
                R.string.two_argument_colon,
                getString(R.string.lbl_overall),
                Utilities.humanReadableByteCount(totalUsage, true)
            )
        b.fssMeteredDataUsage.setCompoundDrawablesWithIntrinsicBounds(
            R.drawable.dot_accent,
            0,
            0,
            0
        )

        // set the alpha for the drawable
        val drawable = b.fssMeteredDataUsage.compoundDrawables[0] // drawableLeft
        drawable?.mutate()?.alpha = ALPHA_HALF_TRANSPARENT

        // set the progress bar
        val ump = calculatePercentage(unmeteredUsage, totalUsage) // unmetered percentage
        val mp = calculatePercentage(meteredUsage, totalUsage) // metered percentage
        val secondaryVal = ump + mp

        b.fssProgressBar.max = secondaryVal
        b.fssProgressBar.progress = ump
        b.fssProgressBar.secondaryProgress = secondaryVal
    }

    private fun calculatePercentage(value: Long, maxValue: Long): Int {
        if (maxValue == 0L) return 0

        return (value * PERCENTAGE_MULTIPLIER / maxValue).toInt()
    }

    private fun highlightToggleBtn() {
        val timeCategory = "0" // default is 1 hours, "0" tag is 1 hours
        val btn = b.toggleGroup.findViewWithTag<MaterialButton>(timeCategory)
        btn.isChecked = true
        selectToggleBtnUi(btn)
    }

    private fun initClickListeners() {
        b.toggleGroup.addOnButtonCheckedListener(listViewToggleListener)


        b.fssViewModeToggleGroup.addOnButtonCheckedListener(viewModeToggleListener)

        // list-view chips
        b.fssCloseConnsChip.setOnClickListener {
            showCloseConnectionDialog()
        }
        b.fssActiveAppsChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.TOP_ACTIVE_CONNS)
        }
        b.fssAppInfoChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONNECTED_APPS)
        }
        b.fssAppInfoChipSecond.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_APPS)
        }
        b.fssAsnChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONNECTED_ASN)
        }
        b.fssAsnChipSecond.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_ASN)
        }
        b.fssDnsLogsChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONTACTED_DOMAINS)
        }
        b.fssDnsLogsChipSecond.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_DOMAINS)
        }

        b.fssNetworkLogsChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONTACTED_IPS)
        }
        b.fssNetworkLogsChipSecond.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_IPS)
        }

        b.fssCountriesLogsChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONTACTED_COUNTRIES)
        }

        // insights-view chips (same detailed screens as the list view)
        b.fssIaActiveConnsChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.TOP_ACTIVE_CONNS)
        }
        b.fssIaAllowedAppsChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONNECTED_APPS)
        }
        b.fssIaBlockedAppsChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_APPS)
        }
        b.fssIaAsnAllowedChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONNECTED_ASN)
        }
        b.fssIaAsnBlockedChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_ASN)
        }
        b.fssIaCountriesChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONTACTED_COUNTRIES)
        }
        b.fssIaDomainsAllowedChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONTACTED_DOMAINS)
        }
        b.fssIaDomainsBlockedChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_DOMAINS)
        }
        b.fssIaIpsAllowedChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONTACTED_IPS)
        }
        b.fssIaIpsBlockedChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_IPS)
        }
    }

    /**
     * View-mode switch: persists the mode and flips the presentation only.
     * The time category and the loaded Stats state are shared by both views,
     * so switching never refetches data and never resets the time range.
     */
    private val viewModeToggleListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { _, _, isChecked ->
            // restyle on BOTH events (checked + unchecked): the group emits the
            // unchecked callback for the old button after (or before) the checked
            // callback for the new one, so each event re-syncs the visuals
            refreshViewModeToggleUi()
            if (!isChecked) return@OnButtonCheckedListener
            val newMode =
                if (b.fssViewModeInsightsBtn.id == b.fssViewModeToggleGroup.checkedButtonId) {
                    StatsViewMode.INSIGHTS
                } else {
                    StatsViewMode.LIST
                }
            if (newMode == statsViewMode) return@OnButtonCheckedListener
            statsViewMode = newMode
            persistentState.statsViewMode = newMode.id
            applyViewModeUi()
        }

    private val listViewToggleListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            val mb: MaterialButton = b.toggleGroup.findViewById(checkedId)
            if (isChecked) {
                selectToggleBtnUi(mb)
                val tcValue = (mb.tag as String).toIntOrNull() ?: 0
                val timeCategory =
                    SummaryStatisticsViewModel.TimeCategory.fromValue(tcValue)
                        ?: SummaryStatisticsViewModel.TimeCategory.ONE_HOUR
                viewModel.timeCategoryChanged(timeCategory)
                handleTotalUsagesUi()
                adaptersByType.values.forEach { it.setTimeCategory(timeCategory) }
                // sections re-render automatically when the adapters receive
                // the new paged data; traffic/graph depend on the time
                // category directly and are refreshed here
                if (statsViewMode == StatsViewMode.INSIGHTS) {
                    refreshInsightsTraffic()
                }
                return@OnButtonCheckedListener
            }

            unselectToggleBtnUi(mb)
        }

    private fun selectToggleBtnUi(mb: MaterialButton) {
        mb.backgroundTintList =
            ColorStateList.valueOf(
                UIUtils.fetchToggleBtnColors(requireContext(), R.color.accentGood)
            )
        mb.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(mb: MaterialButton) {
        mb.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
        mb.backgroundTintList =
            ColorStateList.valueOf(
                UIUtils.fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg)
            )
    }

    /**
     * Wires the domain/ip/country sections. The ViewModel primes their
     * LiveData (domains/ips/countries) in [SummaryStatisticsViewModel.setLoadMoreClicked],
     * which MUST run before the observers below attach — switchMap only computes
     * upon observation, so the primed values are picked up then. Runs once.
     */
    private fun initLazySections() {
        if (loadMoreInitialized) {
            return
        }
        loadMoreInitialized = true
        viewModel.setLoadMoreClicked(true)
        showMostContactedDomain()
        showMostBlockedDomains()
        showMostContactedIps()
        showMostBlockedIps()
        showMostContactedCountries()
    }

    private fun showCloseConnectionDialog() {
        Logger.v(LOG_TAG_UI, "show close connection dialog all apps")
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(this.getString(R.string.close_conns_dialog_title))
            .setMessage(getString(R.string.close_conns_dialog_desc, getString(R.string.lbl_all_apps).lowercase()))
            .setPositiveButton(R.string.lbl_proceed) { _, _ ->
                // close the connection
                VpnController.closeConnectionsIfNeeded(Constants.UID_EVERYBODY, "summ-stats-manual-close")
                Logger.i(LOG_TAG_UI, "closed connection for all apps")
                showToastUiCentered(requireContext(), getString(R.string.config_add_success_toast), Toast.LENGTH_LONG)
                logEvent("close connections",
                    "Closed active connections for all apps from stats screen")
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .create()
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun openDetailedStatsUi(type: SummaryStatisticsType) {
        val mb = b.toggleGroup.checkedButtonId
        val timeCategory =
            (b.toggleGroup.findViewById<MaterialButton>(mb).tag as String).toIntOrNull() ?: 0
        val intent = Intent(requireContext(), DetailedStatisticsActivity::class.java)
        intent.putExtra(DetailedStatisticsActivity.INTENT_TYPE, type.tid)
        intent.putExtra(DetailedStatisticsActivity.INTENT_TIME_CATEGORY, timeCategory)
        startActivity(intent)
    }

    private fun observeAppStart() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner) { isVpnActive = it }
    }

    companion object {
        fun newInstance() = SummaryStatisticsFragment()

        // UI constants
        private const val ALPHA_HALF_TRANSPARENT = 128
        private const val PERCENTAGE_MULTIPLIER = 100
        private const val UNKNOWN_COUNTRY_LABEL = "--"

        // donut slices: top items only, one hue stepped by intensity
        private const val TOP_SLICES = 5
        private val SLICE_ALPHAS = intArrayOf(255, 190, 140, 100, 70)
    }

    /**
     * Wires a summary section: creates the adapter, observes the paged data and
     * toggles the section's visibility based on load state.
     *
     * Height: the RecyclerView uses wrap_content with nested scrolling disabled,
     * so it sizes itself exactly to its content — no pre-computed heights needed.
     */
    private fun setupSummaryRecycler(
        recyclerView: RecyclerView,
        container: View,
        type: SummaryStatisticsType,
        data: LiveData<PagingData<AppConnection>>
    ): SummaryStatisticsAdapter {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        // fixed-size assumption is wrong here: wrap_content height means data
        // changes resize the view, so the RecyclerView must re-layout on updates
        recyclerView.setHasFixedSize(false)
        recyclerView.itemAnimator = null

        val adapter = SummaryStatisticsAdapter(
            requireContext(),
            persistentState,
            appConfig,
            type
        )
        // automatically reverts to ALLOW once the adapter is non-empty, so no
        // post-based hacks are required
        adapter.stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        adaptersByType[type] = adapter

        data.observe(viewLifecycleOwner) {
            adapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        // hide the whole section (header + list) when there is no data
        adapter.addLoadStateListener { loadStates ->
            if (loadStates.append.endOfPaginationReached) {
                container.visibility =
                    if (adapter.itemCount < 1) View.GONE else View.VISIBLE
            } else {
                container.visibility = View.VISIBLE
            }
        }

        // keep the Insights snapshot for this section in sync with the exact
        // same data the list view renders; no second query is issued
        adapter.registerAdapterDataObserver(InsightsSnapshotObserver(type))

        recyclerView.adapter = adapter
        return adapter
    }

    /**
     * Mirrors adapter list changes into [insightsSnapshots] and re-renders the
     * matching Insights section (only when the Insights view is visible).
     */
    private inner class InsightsSnapshotObserver(
        private val type: SummaryStatisticsType
    ) : RecyclerView.AdapterDataObserver() {
        private fun cacheAndRender() {
            insightsSnapshots[type] = adaptersByType[type]?.snapshot()?.items.orEmpty()
            if (statsViewMode == StatsViewMode.INSIGHTS) {
                renderInsightsSection(type)
            }
        }

        override fun onChanged() = cacheAndRender()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = cacheAndRender()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
            cacheAndRender()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = cacheAndRender()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = cacheAndRender()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            cacheAndRender()
    }

    private fun showTopActiveApps() {
        setupSummaryRecycler(
            b.fssActiveAppsRecyclerView,
            b.fssActiveAppsLl,
            SummaryStatisticsType.TOP_ACTIVE_CONNS,
            viewModel.getTopActiveConns
        )
    }

    private fun showAppNetworkActivity() {
        setupSummaryRecycler(
            b.fssAppNetworkActivityRecyclerView,
            b.fssAppAllowedLl,
            SummaryStatisticsType.MOST_CONNECTED_APPS,
            viewModel.getAllowedAppNetworkActivity
        )
    }

    private fun showBlockedApps() {
        setupSummaryRecycler(
            b.fssAppBlockedRecyclerView,
            b.fssAppBlockedLl,
            SummaryStatisticsType.MOST_BLOCKED_APPS,
            viewModel.getBlockedAppNetworkActivity
        )
    }

    private fun showMostConnectedASN() {
        setupSummaryRecycler(
            b.fssAsnAllowedRecyclerView,
            b.fssAsnAllowedLl,
            SummaryStatisticsType.MOST_CONNECTED_ASN,
            viewModel.getMostConnectedASN
        ).setTimeCategory(viewModel.getTimeCategory())
    }

    private fun showMostBlockedASN() {
        setupSummaryRecycler(
            b.fssAsnBlockedRecyclerView,
            b.fssAsnBlockedLl,
            SummaryStatisticsType.MOST_BLOCKED_ASN,
            viewModel.getMostBlockedASN
        ).setTimeCategory(viewModel.getTimeCategory())
    }

    private fun showMostContactedDomain() {
        // if dns is not active then hide the view
        if (!appConfig.getBraveMode().isDnsActive()) {
            b.fssDomainAllowedLl.visibility = View.GONE
            return
        }
        setupSummaryRecycler(
            b.fssContactedDomainRecyclerView,
            b.fssDomainAllowedLl,
            SummaryStatisticsType.MOST_CONTACTED_DOMAINS,
            viewModel.mcd
        ).setTimeCategory(viewModel.getTimeCategory())
    }

    private fun showMostBlockedDomains() {
        // if dns is not active, hide the view
        if (!appConfig.getBraveMode().isDnsActive()) {
            b.fssDomainBlockedLl.visibility = View.GONE
            return
        }
        setupSummaryRecycler(
            b.fssBlockedDomainRecyclerView,
            b.fssDomainBlockedLl,
            SummaryStatisticsType.MOST_BLOCKED_DOMAINS,
            viewModel.mbd
        ).setTimeCategory(viewModel.getTimeCategory())
    }

    private fun showMostContactedIps() {
        // if firewall is not active, hide the view
        if (!appConfig.getBraveMode().isFirewallActive()) {
            b.fssIpAllowedLl.visibility = View.GONE
            return
        }
        setupSummaryRecycler(
            b.fssContactedIpsRecyclerView,
            b.fssIpAllowedLl,
            SummaryStatisticsType.MOST_CONTACTED_IPS,
            viewModel.getMostContactedIps
        ).setTimeCategory(viewModel.getTimeCategory())
    }

    private fun showMostBlockedIps() {
        // if firewall is not active, hide the view
        if (!appConfig.getBraveMode().isFirewallActive()) {
            b.fssIpBlockedLl.visibility = View.GONE
            return
        }
        setupSummaryRecycler(
            b.fssBlockedIpsRecyclerView,
            b.fssIpBlockedLl,
            SummaryStatisticsType.MOST_BLOCKED_IPS,
            viewModel.getMostBlockedIps
        ).setTimeCategory(viewModel.getTimeCategory())
    }

    private fun showMostContactedCountries() {
        // if firewall is not active, hide the view
        if (!appConfig.getBraveMode().isFirewallActive()) {
            b.fssCountriesAllowedLl.visibility = View.GONE
            return
        }
        setupSummaryRecycler(
            b.fssContactedCountriesRecyclerView,
            b.fssCountriesAllowedLl,
            SummaryStatisticsType.MOST_CONTACTED_COUNTRIES,
            viewModel.getMostContactedCountries
        ).setTimeCategory(viewModel.getTimeCategory())
    }

    /** Re-renders every Insights section from the cached snapshots. */
    private fun renderInsights() {
        applyInsightsTheme()
        SummaryStatisticsType.entries.forEach { renderInsightsSection(it) }
        applyInsightsSectionGating()
    }

    /** Hides Insights sections whose list-view counterparts are unavailable. */
    private fun applyInsightsSectionGating() {
        if (persistentState.downloadIpInfo) {
            b.fssIaAsnAllowedLl.visibility = View.VISIBLE
            b.fssIaAsnBlockedLl.visibility = View.VISIBLE
        } else {
            b.fssIaAsnAllowedLl.visibility = View.GONE
            b.fssIaAsnBlockedLl.visibility = View.GONE
        }
        val dnsActive = appConfig.getBraveMode().isDnsActive()
        b.fssIaDomainsAllowedLl.visibility = if (dnsActive) View.VISIBLE else View.GONE
        b.fssIaDomainsBlockedLl.visibility = if (dnsActive) View.VISIBLE else View.GONE
        val firewallActive = appConfig.getBraveMode().isFirewallActive()
        b.fssIaIpsAllowedLl.visibility = if (firewallActive) View.VISIBLE else View.GONE
        b.fssIaIpsBlockedLl.visibility = if (firewallActive) View.VISIBLE else View.GONE
        b.fssIaCountriesLl.visibility = if (firewallActive) View.VISIBLE else View.GONE
    }

    /** Resolves every Insights color from the active theme; no hardcoded colors. */
    private fun applyInsightsTheme() {
        allowedColor = UIUtils.fetchColor(requireContext(), R.attr.accentGood)
        blockedColor = UIUtils.fetchColor(requireContext(), R.attr.accentBad)
        trackColor = UIUtils.fetchColor(requireContext(), R.attr.colorSurfaceContainerHighest)
        centerTextColor = UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor)
        val subtle = UIUtils.fetchColor(requireContext(), R.attr.secondaryTextColor)
        b.fssIaCountriesMap.setColors(trackColor, allowedColor, subtle)
        allInsightsDonuts().forEach { donut ->
            donut.setTrackColor(trackColor)
            donut.setCenterTextColor(centerTextColor)
        }
    }

    private fun allInsightsDonuts(): List<DonutChartView> {
        return listOf(
            b.fssIaActiveConnsDonut,
            b.fssIaAllowedAppsDonut,
            b.fssIaBlockedAppsDonut,
            b.fssIaAsnAllowedDonut,
            b.fssIaAsnBlockedDonut,
            b.fssIaDomainsAllowedDonut,
            b.fssIaDomainsBlockedDonut,
            b.fssIaIpsAllowedDonut,
            b.fssIaIpsBlockedDonut
        )
    }

    private fun renderInsightsSection(type: SummaryStatisticsType) {
        val items = insightsSnapshots[type].orEmpty()
        when (type) {
            SummaryStatisticsType.TOP_ACTIVE_CONNS ->
                renderRanking(
                    b.fssIaActiveConnsRows, b.fssIaActiveConnsEmpty,
                    b.fssIaActiveConnsDonut, false, type, items
                )
            SummaryStatisticsType.MOST_CONNECTED_APPS ->
                renderRanking(
                    b.fssIaAllowedAppsRows, b.fssIaAllowedAppsEmpty,
                    b.fssIaAllowedAppsDonut, false, type, items
                )
            SummaryStatisticsType.MOST_BLOCKED_APPS ->
                renderRanking(
                    b.fssIaBlockedAppsRows, b.fssIaBlockedAppsEmpty,
                    b.fssIaBlockedAppsDonut, true, type, items
                )
            SummaryStatisticsType.MOST_CONNECTED_ASN ->
                renderRanking(
                    b.fssIaAsnAllowedRows, b.fssIaAsnAllowedEmpty,
                    b.fssIaAsnAllowedDonut, false, type, items
                )
            SummaryStatisticsType.MOST_BLOCKED_ASN ->
                renderRanking(
                    b.fssIaAsnBlockedRows, b.fssIaAsnBlockedEmpty,
                    b.fssIaAsnBlockedDonut, true, type, items
                )
            SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                renderRanking(
                    b.fssIaCountriesRows, b.fssIaCountriesEmpty,
                    null, false, type, items
                )
                updateCountryMap(items)
            }
            SummaryStatisticsType.MOST_CONTACTED_DOMAINS ->
                renderRanking(
                    b.fssIaDomainsAllowedRows, b.fssIaDomainsAllowedEmpty,
                    b.fssIaDomainsAllowedDonut, false, type, items
                )
            SummaryStatisticsType.MOST_BLOCKED_DOMAINS ->
                renderRanking(
                    b.fssIaDomainsBlockedRows, b.fssIaDomainsBlockedEmpty,
                    b.fssIaDomainsBlockedDonut, true, type, items
                )
            SummaryStatisticsType.MOST_CONTACTED_IPS ->
                renderRanking(
                    b.fssIaIpsAllowedRows, b.fssIaIpsAllowedEmpty,
                    b.fssIaIpsAllowedDonut, false, type, items
                )
            SummaryStatisticsType.MOST_BLOCKED_IPS ->
                renderRanking(
                    b.fssIaIpsBlockedRows, b.fssIaIpsBlockedEmpty,
                    b.fssIaIpsBlockedDonut, true, type, items
                )
        }
    }

    /**
     * Renders a section as a donut chart (top slices, single hue stepped by
     * intensity) plus normalized ranking rows (bar length = value / maxValue,
     * the longest item at ~100%), or the section's empty state.
     */
    private fun renderRanking(
        rowsContainer: LinearLayout,
        emptyView: View,
        donut: DonutChartView?,
        isBlockedSection: Boolean,
        type: SummaryStatisticsType,
        items: List<AppConnection>
    ) {
        if (items.isEmpty()) {
            rowsContainer.removeAllViews()
            emptyView.visibility = View.VISIBLE
            donut?.visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE
        donut?.visibility = View.VISIBLE
        val fractions = StatsInsightsMath.normalizeFractions(items.map { metricValue(it, type) })
        renderDonut(donut, isBlockedSection, type, fractions, items)
        val inflater = LayoutInflater.from(requireContext())
        val allowed = allowedColor
        val blocked = blockedColor
        rowsContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            val rowBinding = ViewInsightsRankRowBinding.inflate(
                inflater,
                rowsContainer,
                false
            )
            val label = rowLabel(item, type)
            val metric = metricLabel(item, type)
            rowBinding.irName.text = label
            rowBinding.irCount.text = metric
            rowBinding.irBar.max = PERCENTAGE_MULTIPLIER
            rowBinding.irBar.progress = (fractions[index] * PERCENTAGE_MULTIPLIER).toInt()
            rowBinding.irBar.setIndicatorColor(if (item.blocked) blocked else allowed)
            rowBinding.root.contentDescription = getString(R.string.ci_desc, label, metric)
            rowsContainer.addView(rowBinding.root)
        }
    }

    /**
     * Donut slices: the top [TOP_SLICES] items, one hue (accent for
     * allowed/contacted sections, red for blocked sections) at stepped
     * intensities; the remainder stays visible as the neutral track ring.
     * The center carries the section total.
     */
    private fun renderDonut(
        donut: DonutChartView?,
        isBlockedSection: Boolean,
        type: SummaryStatisticsType,
        fractions: List<Float>,
        items: List<AppConnection>
    ) {
        donut ?: return
        val base = if (isBlockedSection) blockedColor else allowedColor
        val top = minOf(TOP_SLICES, fractions.size)
        val slices = (0 until top).map { i ->
            DonutChartView.Slice(fractions[i], ColorUtils.setAlphaComponent(base, SLICE_ALPHAS[i]))
        }
        donut.setData(slices)
        donut.setCenterText(metricTotalLabel(type, items))
    }

    /** Human-readable total of the section metric for the donut center. */
    private fun metricTotalLabel(type: SummaryStatisticsType, items: List<AppConnection>): String {
        val total = items.sumOf { metricValue(it, type) }
        return if (type == SummaryStatisticsType.MOST_CONNECTED_APPS) {
            Utilities.humanReadableByteCount(total, true)
        } else {
            total.toString()
        }
    }

    /** Value used for ranking bars, mirroring the list adapter's semantics. */
    private fun metricValue(item: AppConnection, type: SummaryStatisticsType): Long {
        return if (type == SummaryStatisticsType.MOST_CONNECTED_APPS) {
            (item.downloadBytes ?: 0L) + (item.uploadBytes ?: 0L)
        } else {
            item.count.toLong()
        }
    }

    /** Human-readable form of the ranking metric (bytes for allowed apps). */
    private fun metricLabel(item: AppConnection, type: SummaryStatisticsType): String {
        val value = metricValue(item, type)
        return if (type == SummaryStatisticsType.MOST_CONNECTED_APPS) {
            Utilities.humanReadableByteCount(value, true)
        } else {
            value.toString()
        }
    }

    private fun rowLabel(item: AppConnection, type: SummaryStatisticsType): String {
        return when (type) {
            SummaryStatisticsType.TOP_ACTIVE_CONNS,
            SummaryStatisticsType.MOST_CONNECTED_APPS,
            SummaryStatisticsType.MOST_BLOCKED_APPS ->
                item.appOrDnsName?.takeIf { it.isNotEmpty() }
                    ?: getString(R.string.network_log_app_name_unnamed, item.uid.toString())
            SummaryStatisticsType.MOST_CONNECTED_ASN,
            SummaryStatisticsType.MOST_BLOCKED_ASN ->
                getString(R.string.two_argument_space, item.flag, item.appOrDnsName.orEmpty())
            SummaryStatisticsType.MOST_CONTACTED_DOMAINS,
            SummaryStatisticsType.MOST_BLOCKED_DOMAINS ->
                item.appOrDnsName?.dropLastWhile { it == '.' }.orEmpty()
            SummaryStatisticsType.MOST_CONTACTED_IPS,
            SummaryStatisticsType.MOST_BLOCKED_IPS -> item.ipAddress
            SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                val name = getCountryNameFromFlag(item.flag)
                if (name.isNotEmpty() && name != UNKNOWN_COUNTRY_LABEL) {
                    name
                } else {
                    getString(
                        R.string.two_argument_space,
                        getString(R.string.network_log_app_name_unknown),
                        item.flag
                    )
                }
            }
        }
    }

    /** Feeds the offline map: emoji flag -> ISO code -> connection count. */
    private fun updateCountryMap(items: List<AppConnection>) {
        val stats = items.mapNotNull { item ->
            CountryInsightsMapper.toCountryCode(item.flag)?.let { it to item.count }
        }.toMap()
        b.fssIaCountriesMap.setCountryCounts(stats)
        // accessibility summary: top few countries, list holds exact numbers
        val summary = stats.entries.take(3).joinToString(", ") {
            getString(R.string.ci_desc, it.key, it.value.toString())
        }
        b.fssIaCountriesMap.contentDescription =
            if (summary.isEmpty()) {
                getString(R.string.cd_stats_country_map)
            } else {
                getString(R.string.two_argument_colon, getString(R.string.cd_stats_country_map), summary)
            }
    }

    /** Loads traffic totals + timeline (same DAO/VM as the list view). */
    private fun refreshInsightsTraffic() {
        // resolve the lazily created view model on the main thread first: its
        // init touches LiveData, which must never happen on a background thread
        val vm = viewModel
        io {
            val usage = vm.totalUsage()
            uiCtx { setInsightsTrafficUi(usage) }
        }
    }

    private fun setInsightsTrafficUi(usage: DataUsageSummary) {
        val total = usage.totalDownload + usage.totalUpload
        val unmetered = total - usage.meteredDataUsage
        val metered = usage.meteredDataUsage

        // KPI metric cards: value first, label below (set in XML)
        b.fssIaKpiUnmetered.text = Utilities.humanReadableByteCount(unmetered, true)
        b.fssIaKpiMetered.text = Utilities.humanReadableByteCount(metered, true)
        b.fssIaKpiTotal.text = Utilities.humanReadableByteCount(total, true)
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, true, details)
    }

    private fun io(f: suspend () -> Unit) {
        this.lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }
}
