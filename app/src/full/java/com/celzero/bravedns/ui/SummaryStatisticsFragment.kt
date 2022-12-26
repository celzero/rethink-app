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

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.databinding.FragmentSummaryStatisticsBinding
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class SummaryStatisticsFragment : Fragment(R.layout.fragment_summary_statistics) {
    private val b by viewBinding(FragmentSummaryStatisticsBinding::bind)

    private val viewModel: SummaryStatisticsViewModel by viewModel()

    enum class SummaryStatisticsType {
        MOST_CONNECTED_APPS,
        MOST_BLOCKED_APPS,
        MOST_CONTACTED_DOMAINS,
        MOST_BLOCKED_DOMAINS,
        MOST_CONTACTED_IPS,
        MOST_BLOCKED_IPS
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        showAppNetworkActivity()
        showBlockedApps()
        showMostContactedDomain()
        showBlockedDomains()
        showMostContactedIps()
        showBlockedIps()
    }

    companion object {
        fun newInstance() = SummaryStatisticsFragment()
    }

    private fun showAppNetworkActivity() {
        b.fssAppNetworkActivityRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssAppNetworkActivityRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(requireContext(), SummaryStatisticsType.MOST_CONNECTED_APPS)

        viewModel.getAllowedAppNetworkActivity.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        val height = (resources.displayMetrics.heightPixels * 0.45).toInt()
        b.fssAppNetworkActivityRecyclerView.minimumHeight = height
        b.fssAppNetworkActivityRecyclerView.adapter = recyclerAdapter
    }

    private fun showBlockedApps() {
        b.fssAppBlockedRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssAppBlockedRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(requireContext(), SummaryStatisticsType.MOST_BLOCKED_APPS)

        viewModel.getBlockedAppNetworkActivity.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        val height = (resources.displayMetrics.heightPixels * 0.45).toInt()
        b.fssAppBlockedRecyclerView.minimumHeight = height
        b.fssAppBlockedRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostContactedDomain() {
        b.fssContactedDomainRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssContactedDomainRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(requireContext(), SummaryStatisticsType.MOST_CONTACTED_DOMAINS)

        viewModel.getMostContactedDomain.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        val height = (resources.displayMetrics.heightPixels * 0.55).toInt()
        b.fssContactedDomainRecyclerView.minimumHeight = height
        b.fssContactedDomainRecyclerView.adapter = recyclerAdapter
    }

    private fun showBlockedDomains() {
        b.fssBlockedDomainRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssBlockedDomainRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(requireContext(), SummaryStatisticsType.MOST_BLOCKED_DOMAINS)

        viewModel.getMostBlockedDomain.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        val height = (resources.displayMetrics.heightPixels * 0.55).toInt()
        b.fssBlockedDomainRecyclerView.minimumHeight = height
        b.fssBlockedDomainRecyclerView.adapter = recyclerAdapter
    }

    private fun showMostContactedIps() {
        b.fssContactedIpsRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssContactedIpsRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(requireContext(), SummaryStatisticsType.MOST_CONTACTED_IPS)
        viewModel.getMostContactedIps.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        val height = (resources.displayMetrics.heightPixels * 0.45).toInt()
        b.fssContactedIpsRecyclerView.minimumHeight = height
        b.fssContactedIpsRecyclerView.adapter = recyclerAdapter
    }

    private fun showBlockedIps() {
        b.fssBlockedIpsRecyclerView.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.fssBlockedIpsRecyclerView.layoutManager = layoutManager

        val recyclerAdapter =
            SummaryStatisticsAdapter(requireContext(), SummaryStatisticsType.MOST_BLOCKED_IPS)
        viewModel.getMostBlockedIps.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        val height = (resources.displayMetrics.heightPixels * 0.45).toInt()
        b.fssBlockedIpsRecyclerView.minimumHeight = height
        b.fssBlockedIpsRecyclerView.adapter = recyclerAdapter
    }
}
