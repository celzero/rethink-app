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
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.databinding.FragmentSummaryStatisticsBinding
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
    private var loadMoreClicked: Boolean = false

    private var contactedDomainsAdapter: SummaryStatisticsAdapter? = null
    private var blockedDomainsAdapter: SummaryStatisticsAdapter? = null
    private var contactedAsnAdapter: SummaryStatisticsAdapter? = null
    private var blockedAsnAdapter: SummaryStatisticsAdapter? = null
    private var contactedCountriesAdapter: SummaryStatisticsAdapter? = null
    private var contactedIpsAdapter: SummaryStatisticsAdapter? = null
    private var blockedIpsAdapter: SummaryStatisticsAdapter? = null

    // Remove unused loadMore overlay views and rotation animator; add progress drawable for FAB
    private var progressDrawable: CircularProgressDrawable? = null
    private var originalFabText: CharSequence? = null

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
        showTopActiveApps()
        showAppNetworkActivity()
        showBlockedApps()
        if (persistentState.downloadIpInfo) {
            showMostConnectedASN()
            showMostBlockedASN()
        } else {
            b.fssAsnAllowedLl.visibility = View.GONE
            b.fssAsnBlockedLl.visibility = View.GONE
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
        handleTotalUsagesUi()
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
        b.fssFabLoadMore.setOnClickListener {
            showLoadMoreProgress(!loadMoreClicked)
        }
        b.toggleGroup.addOnButtonCheckedListener(listViewToggleListener)

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
                contactedDomainsAdapter?.setTimeCategory(timeCategory)
                blockedDomainsAdapter?.setTimeCategory(timeCategory)
                contactedCountriesAdapter?.setTimeCategory(timeCategory)
                contactedAsnAdapter?.setTimeCategory(timeCategory)
                blockedAsnAdapter?.setTimeCategory(timeCategory)
                contactedIpsAdapter?.setTimeCategory(timeCategory)
                blockedIpsAdapter?.setTimeCategory(timeCategory)
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

    private fun handleLoadMore(isClicked: Boolean) {
        viewModel.setLoadMoreClicked(isClicked)
        if (!isClicked) {
            return
        }
        showMostContactedDomain()
        showMostBlockedDomains()
        showMostContactedIps()
        showMostBlockedIps()
        showMostContactedCountries()
    }

    private fun showLoadMoreProgress(isClicked: Boolean) {
        if (isClicked) {
            loadMoreClicked = true
            b.fssFabLoadMore.isEnabled = false
            // cache original text
            if (originalFabText == null) originalFabText = b.fssFabLoadMore.text
            // create or reuse progress drawable
            if (progressDrawable == null) {
                progressDrawable = CircularProgressDrawable(requireContext()).apply {
                    strokeWidth = PROGRESS_STROKE_WIDTH
                    centerRadius = PROGRESS_CENTER_RADIUS
                    setStyle(CircularProgressDrawable.LARGE)
                }
            }
            progressDrawable?.start()
            // shrink to icon-only then set icon to progress indicator
            b.fssFabLoadMore.shrink()
            b.fssFabLoadMore.icon = progressDrawable
            b.fssFabLoadMore.text = "" // ensure no residual text
            handleLoadMore(true)
            Utilities.delay(LOAD_MORE_TIMEOUT, lifecycleScope) {
                if (!isAdded) return@delay
                progressDrawable?.stop()
                b.fssFabLoadMore.visibility = View.GONE
                loadMoreClicked = false
            }
        } else {
            // reset early
            progressDrawable?.stop()
            b.fssFabLoadMore.text = originalFabText ?: getString(R.string.load_more)
            b.fssFabLoadMore.extend()
            b.fssFabLoadMore.isEnabled = true
            loadMoreClicked = false
            handleLoadMore(false)
        }
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

        // Recycler view height constants
        private const val RECYCLER_ITEM_VIEW_HEIGHT = 480
        private const val RECYCLER_HEIGHT_OFFSET = 80

        // UI constants
        private const val LOAD_MORE_TIMEOUT: Long = 1000
        private const val ALPHA_HALF_TRANSPARENT = 128
        private const val PERCENTAGE_MULTIPLIER = 100

        // Progress drawable constants
        private const val PROGRESS_STROKE_WIDTH = 5f
        private const val PROGRESS_CENTER_RADIUS = 18f
    }

    private fun showTopActiveApps() {
        b.fssActiveAppsRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssActiveAppsRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.TOP_ACTIVE_CONNS
            )

        viewModel.getTopActiveConns.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssActiveAppsLl.visibility = View.GONE
                } else {
                    b.fssActiveAppsLl.visibility = View.VISIBLE
                }
            } else {
                b.fssActiveAppsLl.visibility = View.VISIBLE
            }
        }

        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssActiveAppsRecyclerView.minimumHeight = pixels.toInt()
        b.fssActiveAppsRecyclerView.adapter = recyclerAdapter
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
                SummaryStatisticsType.MOST_CONNECTED_APPS)

        viewModel.getAllowedAppNetworkActivity.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        // remove the view if there is no data
        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.fssAppAllowedLl.visibility = View.GONE
                } else {
                    b.fssAppAllowedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssAppAllowedLl.visibility = View.VISIBLE
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
                } else {
                    b.fssAppBlockedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssAppBlockedLl.visibility = View.VISIBLE
            }
        }

        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssAppBlockedRecyclerView.minimumHeight = pixels.toInt()
        b.fssAppBlockedRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostConnectedASN() {
        b.fssAsnAllowedRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssAsnAllowedRecyclerView.layoutManager = layoutManager

        contactedAsnAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONNECTED_ASN
            )


        val timeCategory = viewModel.getTimeCategory()
        contactedAsnAdapter?.setTimeCategory(timeCategory)

        viewModel.getMostConnectedASN.observe(viewLifecycleOwner) {
            contactedAsnAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }

        contactedAsnAdapter?.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (contactedAsnAdapter!!.itemCount < 1) {
                    b.fssAsnAllowedLl.visibility = View.GONE
                } else {
                    b.fssAsnAllowedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssAsnAllowedLl.visibility = View.VISIBLE
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssAsnAllowedRecyclerView.minimumHeight = pixels.toInt()
        b.fssAsnAllowedRecyclerView.adapter = contactedAsnAdapter
    }

    private fun showMostBlockedASN() {
        b.fssAsnBlockedRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssAsnBlockedRecyclerView.layoutManager = layoutManager

        blockedAsnAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_BLOCKED_ASN
            )

        val timeCategory = viewModel.getTimeCategory()
        blockedAsnAdapter?.setTimeCategory(timeCategory)

        viewModel.getMostBlockedASN.observe(viewLifecycleOwner) {
            blockedAsnAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }

        blockedAsnAdapter?.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (blockedAsnAdapter!!.itemCount < 1) {
                    b.fssAsnBlockedLl.visibility = View.GONE
                } else {
                    b.fssAsnBlockedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssAsnBlockedLl.visibility = View.VISIBLE
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssAsnBlockedRecyclerView.minimumHeight = pixels.toInt()
        b.fssAsnBlockedRecyclerView.adapter = blockedAsnAdapter
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

        contactedDomainsAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONTACTED_DOMAINS
            )


        val timeCategory = viewModel.getTimeCategory()
        contactedDomainsAdapter?.setTimeCategory(timeCategory)

        viewModel.mcd.observe(viewLifecycleOwner) {
            contactedDomainsAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }

        contactedDomainsAdapter?.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (contactedDomainsAdapter!!.itemCount < 1) {
                    b.fssDomainAllowedLl.visibility = View.GONE
                } else {
                    b.fssDomainAllowedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssDomainAllowedLl.visibility = View.VISIBLE
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssContactedDomainRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedDomainRecyclerView.adapter = contactedDomainsAdapter
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

        blockedDomainsAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_BLOCKED_DOMAINS
            )

        val timeCategory = viewModel.getTimeCategory()
        blockedDomainsAdapter?.setTimeCategory(timeCategory)

        viewModel.mbd.observe(viewLifecycleOwner) {
            blockedDomainsAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }

        blockedDomainsAdapter?.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (blockedDomainsAdapter!!.itemCount < 1) {
                    b.fssDomainBlockedLl.visibility = View.GONE
                } else {
                    b.fssDomainBlockedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssDomainBlockedLl.visibility = View.VISIBLE
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssBlockedDomainRecyclerView.minimumHeight = pixels.toInt()
        b.fssBlockedDomainRecyclerView.adapter = blockedDomainsAdapter
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

        contactedIpsAdapter = SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONTACTED_IPS
            )

        val timeCategory = viewModel.getTimeCategory()
        contactedIpsAdapter?.setTimeCategory(timeCategory)

        viewModel.getMostContactedIps.observe(viewLifecycleOwner) {
            contactedIpsAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }

        contactedIpsAdapter?.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (contactedIpsAdapter!!.itemCount < 1) {
                    b.fssIpAllowedLl.visibility = View.GONE
                } else {
                    b.fssIpAllowedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssIpAllowedLl.visibility = View.VISIBLE
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssContactedIpsRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedIpsRecyclerView.adapter = contactedIpsAdapter
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

        blockedIpsAdapter = SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_BLOCKED_IPS
            )

        val timeCategory = viewModel.getTimeCategory()
        blockedIpsAdapter?.setTimeCategory(timeCategory)

        viewModel.getMostBlockedIps.observe(viewLifecycleOwner) {
            blockedIpsAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }

        blockedIpsAdapter?.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (blockedIpsAdapter!!.itemCount < 1) {
                    b.fssIpBlockedLl.visibility = View.GONE
                } else {
                    b.fssIpBlockedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssIpBlockedLl.visibility = View.VISIBLE
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssBlockedIpsRecyclerView.minimumHeight = pixels.toInt()
        b.fssBlockedIpsRecyclerView.adapter = blockedIpsAdapter
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

        contactedCountriesAdapter =
            SummaryStatisticsAdapter(
                requireContext(),
                persistentState,
                appConfig,
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES
            )

        val timeCategory = viewModel.getTimeCategory()
        contactedCountriesAdapter?.setTimeCategory(timeCategory)

        viewModel.getMostContactedCountries.observe(viewLifecycleOwner) {
            contactedCountriesAdapter?.submitData(viewLifecycleOwner.lifecycle, it)
        }

        contactedCountriesAdapter?.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (contactedCountriesAdapter!!.itemCount < 1) {
                    b.fssCountriesAllowedLl.visibility = View.GONE
                } else {
                    b.fssCountriesAllowedLl.visibility = View.VISIBLE
                }
            } else {
                b.fssCountriesAllowedLl.visibility = View.VISIBLE
            }
        }
        val scale = resources.displayMetrics.density
        val pixels = ((RECYCLER_ITEM_VIEW_HEIGHT - RECYCLER_HEIGHT_OFFSET) * scale + 0.5f)
        b.fssContactedCountriesRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedCountriesRecyclerView.adapter = contactedCountriesAdapter
    }

    private fun io(f: suspend () -> Unit) {
        this.lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
