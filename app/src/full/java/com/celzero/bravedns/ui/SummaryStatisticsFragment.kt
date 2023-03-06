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
package com.celzero.bravedns.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentSummaryStatisticsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
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
        MOST_BLOCKED_DOMAINS(3),
        MOST_CONTACTED_IPS(4),
        MOST_BLOCKED_IPS(5);

        companion object {
            fun getType(t: Int): SummaryStatisticsType {
                return when (t) {
                    MOST_CONNECTED_APPS.tid -> MOST_CONNECTED_APPS
                    MOST_BLOCKED_APPS.tid -> MOST_BLOCKED_APPS
                    MOST_CONTACTED_DOMAINS.tid -> MOST_CONTACTED_DOMAINS
                    MOST_BLOCKED_DOMAINS.tid -> MOST_BLOCKED_DOMAINS
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
        initView()
        observeAppStart()
        initClickListeners()
    }

    private fun initView() {
        showAppNetworkActivity()
        showBlockedApps()
        showMostContactedDomain()
        showBlockedDomains()
        showMostContactedIps()
        showBlockedIps()
    }

    private fun initClickListeners() {
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
    }

    private fun openDetailedStatsUi(type: SummaryStatisticsType) {
        val intent = Intent(requireContext(), DetailedStatisticsActivity::class.java)
        intent.putExtra(DetailedStatisticsActivity.INTENT_TYPE, type.tid)
        startActivity(intent)
    }

    private fun observeAppStart() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner) { isVpnActive = it }
    }

    companion object {
        fun newInstance() = SummaryStatisticsFragment()
        private const val RECYCLER_ITEM_VIEW_HEIGHT = 420
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
        val pixels = (RECYCLER_ITEM_VIEW_HEIGHT * scale + 0.5f)
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
        val pixels = (RECYCLER_ITEM_VIEW_HEIGHT * scale + 0.5f)
        b.fssContactedDomainRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedDomainRecyclerView.adapter = recyclerAdapter
    }

    private fun showBlockedDomains() {
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
        val pixels = (RECYCLER_ITEM_VIEW_HEIGHT * scale + 0.5f)
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
        val pixels = (RECYCLER_ITEM_VIEW_HEIGHT * scale + 0.5f)
        b.fssContactedIpsRecyclerView.minimumHeight = pixels.toInt()
        b.fssContactedIpsRecyclerView.adapter = recyclerAdapter
    }

    private fun showBlockedIps() {
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
        val pixels = (RECYCLER_ITEM_VIEW_HEIGHT * scale + 0.5f)
        b.fssBlockedIpsRecyclerView.minimumHeight = pixels.toInt()
        b.fssBlockedIpsRecyclerView.adapter = recyclerAdapter
    }
}
