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

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.databinding.FragmentSummaryStatisticsBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.DetailedStatisticsActivity
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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

    private var isVpnActive: Boolean = false

    enum class SummaryStatisticsType(val tid: Int) {
        MOST_CONNECTED_APPS(0),
        MOST_BLOCKED_APPS(1),
        MOST_CONTACTED_DOMAINS(2),
        MOST_CONTACTED_COUNTRIES(3),
        MOST_BLOCKED_DOMAINS(4),
        MOST_CONTACTED_IPS(5),
        MOST_BLOCKED_IPS(6),
        MOST_BLOCKED_COUNTRIES(7);

        companion object {
            fun getType(t: Int): SummaryStatisticsType {
                return when (t) {
                    MOST_CONNECTED_APPS.tid -> MOST_CONNECTED_APPS
                    MOST_BLOCKED_APPS.tid -> MOST_BLOCKED_APPS
                    MOST_CONTACTED_DOMAINS.tid -> MOST_CONTACTED_DOMAINS
                    MOST_BLOCKED_DOMAINS.tid -> MOST_BLOCKED_DOMAINS
                    MOST_CONTACTED_COUNTRIES.tid -> MOST_CONTACTED_COUNTRIES
                    MOST_BLOCKED_COUNTRIES.tid -> MOST_BLOCKED_COUNTRIES
                    MOST_CONTACTED_IPS.tid -> MOST_CONTACTED_IPS
                    MOST_BLOCKED_IPS.tid -> MOST_BLOCKED_IPS
                    // make most contacted apps as default
                    else -> MOST_CONNECTED_APPS
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        io {
            uiCtx {
                initView()
                observeAppStart()
                initClickListeners()
            }
        }
    }

    private fun initView() {
        setTabbedViewTxt()
        highlightToggleBtn()
        showAppNetworkActivity()
        showBlockedApps()
        showMostContactedDomain()
        showMostBlockedDomains()
        showMostContactedIps()
        showMostBlockedIps()
        showMostContactedCountries()
        showMostBlockedCountries()
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
        val timeCategory = viewModel.getTimeCategory().value.toString()
        val btn = b.toggleGroup.findViewWithTag<MaterialButton>(timeCategory)
        btn.isChecked = true
        handleTotalUsagesUi()
    }

    private fun handleTotalUsagesUi() {
        io {
            val totalUsage = viewModel.totalUsage()
            uiCtx { setTotalUsagesUi(totalUsage) }
        }
    }

    private fun setTotalUsagesUi(dataUsage: DataUsageSummary) {
        val unmeteredUsage = (dataUsage.totalDownload + dataUsage.totalUpload)
        val totalUsage = unmeteredUsage + dataUsage.meteredDataUsage

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
                Utilities.humanReadableByteCount(dataUsage.meteredDataUsage, true)
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
        val alphaValue = 128 // half-transparent
        val drawable = b.fssMeteredDataUsage.compoundDrawables[0] // drawableLeft
        drawable?.mutate()?.alpha = alphaValue

        // set the progress bar
        val ump = calculatePercentage(unmeteredUsage, totalUsage) // unmetered percentage
        val mp = calculatePercentage(dataUsage.meteredDataUsage, totalUsage) // metered percentage
        val secondaryVal = ump + mp

        b.fssProgressBar.max = secondaryVal
        b.fssProgressBar.progress = ump
        b.fssProgressBar.secondaryProgress = secondaryVal
    }

    private fun calculatePercentage(value: Long, maxValue: Long): Int {
        if (maxValue == 0L) return 0

        return (value * 100 / maxValue).toInt()
    }

    private fun highlightToggleBtn() {
        val timeCategory = "0" // default is 1 hours, "0" tag is 1 hours
        val btn = b.toggleGroup.findViewWithTag<MaterialButton>(timeCategory)
        btn.isChecked = true
        selectToggleBtnUi(btn)
    }

    private fun initClickListeners() {
        b.toggleGroup.addOnButtonCheckedListener(listViewToggleListener)

        b.fssAppInfoChip.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_CONNECTED_APPS)
        }
        b.fssAppInfoChipSecond.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_APPS)
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
        b.fssCountriesChipSecond.setOnClickListener {
            openDetailedStatsUi(SummaryStatisticsType.MOST_BLOCKED_COUNTRIES)
        }
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
                io {
                    val isAppBypassed = FirewallManager.isAnyAppBypassesDns()
                    uiCtx { viewModel.timeCategoryChanged(timeCategory, isAppBypassed) }
                    handleTotalUsagesUi()
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

        private const val RECYCLER_ITEM_VIEW_HEIGHT = 480
    }

    private fun showAppNetworkActivity() {
        b.fssAppNetworkActivityRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssAppNetworkActivityRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONNECTED_APPS
            )

        viewModel.getAllowedAppNetworkActivity.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        // remove the view if there is no data
        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssAppAllowedLl.visibility = View.GONE
                }
            }
        }

        val scale = resources.displayMetrics.density
        val pixels = (RECYCLER_ITEM_VIEW_HEIGHT * scale + 0.5f)
        b.fssAppNetworkActivityRecyclerView.minimumHeight = pixels.toInt()
        b.fssAppNetworkActivityRecyclerView.adapter = recyclerAdapter
    }

    private fun showBlockedApps() {
        b.fssAppBlockedRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssAppBlockedRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_BLOCKED_APPS
            )

        viewModel.getBlockedAppNetworkActivity.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssAppBlockedLl.visibility = View.GONE
                }
            }
        }

        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - 60) * scale + 0.5f)
        b.fssAppBlockedRecyclerView.minimumHeight = pixels.toInt()
        b.fssAppBlockedRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostContactedDomain() {
        // if dns is not active then hide the view
        if (!appConfig.getBraveMode().isDnsActive()) {
            b.fssDomainAllowedLl.visibility = View.GONE
            return
        }

        b.fssContactedDomainRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssContactedDomainRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONTACTED_DOMAINS
            )

        viewModel.getMostContactedDomains.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssDomainAllowedLl.visibility = View.GONE
                }
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - 60) * scale + 0.5f)
        b.fssContactedDomainRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedDomainRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostBlockedDomains() {
        // if dns is not active, hide the view
        if (!appConfig.getBraveMode().isDnsActive()) {
            b.fssDomainBlockedLl.visibility = View.GONE
            return
        }
        b.fssBlockedDomainRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssBlockedDomainRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_BLOCKED_DOMAINS
            )

        viewModel.getMostBlockedDomains.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssDomainBlockedLl.visibility = View.GONE
                }
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - 60) * scale + 0.5f)
        b.fssBlockedDomainRecyclerView.minimumHeight = pixels.toInt()
        b.fssBlockedDomainRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostContactedIps() {
        // if firewall is not active, hide the view
        if (!appConfig.getBraveMode().isFirewallActive()) {
            b.fssIpAllowedLl.visibility = View.GONE
            return
        }

        b.fssContactedIpsRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssContactedIpsRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONTACTED_IPS
            )
        viewModel.getMostContactedIps.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssIpAllowedLl.visibility = View.GONE
                }
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - 60) * scale + 0.5f)
        b.fssContactedIpsRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedIpsRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostContactedCountries() {
        // if firewall is not active, hide the view
        if (!appConfig.getBraveMode().isFirewallActive()) {
            b.fssCountriesAllowedLl.visibility = View.GONE
            return
        }

        b.fssContactedCountriesRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssContactedCountriesRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES
            )
        viewModel.getMostContactedCountries.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssCountriesAllowedLl.visibility = View.GONE
                }
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - 60) * scale + 0.5f)
        b.fssContactedCountriesRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedCountriesRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostBlockedIps() {
        // if firewall is not active, hide the view
        if (!appConfig.getBraveMode().isFirewallActive()) {
            b.fssIpBlockedLl.visibility = View.GONE
            return
        }

        b.fssBlockedIpsRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssBlockedIpsRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_BLOCKED_IPS
            )
        viewModel.getMostBlockedIps.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssIpBlockedLl.visibility = View.GONE
                }
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - 60) * scale + 0.5f)
        b.fssBlockedIpsRecyclerView.minimumHeight = pixels.toInt()
        b.fssBlockedIpsRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostBlockedCountries() {
        // if firewall is not active, hide the view
        if (!appConfig.getBraveMode().isFirewallActive()) {
            b.fssCountriesBlockedLl.visibility = View.GONE
            return
        }

        b.fssContactedCountriesRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssCountriesBlockedRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES
            )
        viewModel.getMostBlockedCountries.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssCountriesBlockedLl.visibility = View.GONE
                }
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - 60) * scale + 0.5f)
        b.fssCountriesBlockedRecyclerView.minimumHeight = pixels.toInt()
        b.fssCountriesBlockedRecyclerView.adapter = recyclerAdapter
    }

    private fun io(f: suspend () -> Unit) {
        this.lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
