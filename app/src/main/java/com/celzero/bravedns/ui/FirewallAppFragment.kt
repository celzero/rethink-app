/*
 * Copyright 2021 RethinkDNS and its authors
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
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppListAdapter
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.FragmentFirewallAppListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class FirewallAppFragment : Fragment(R.layout.fragment_firewall_app_list),
                            SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentFirewallAppListBinding::bind)

    private val appInfoViewModel: AppInfoViewModel by viewModel()
    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()

    private var layoutManager: RecyclerView.LayoutManager? = null

    private lateinit var animation: Animation

    private val filters = MutableLiveData<Filters>()

    companion object {
        fun newInstance() = FirewallAppFragment()

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val REFRESH_TIMEOUT: Long = 4000
        private const val QUERY_TEXT_TIMEOUT: Long = 600

        // TODO: Find a solution to replace the below query usage
        // ref: https://stackoverflow.com/questions/61055772/android-room-dao-order-by-case-not-working
        private const val ORDER_BY_DEFAULT = " lower(appName)"
        private const val ORDER_BY_BLOCKED = " CASE firewallStatus WHEN 1 THEN 0 WHEN 0 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 4 END, lower(appName)"
        private const val ORDER_BY_WHITELISTED = " CASE firewallStatus WHEN 2 THEN 0 WHEN 0 THEN 1 WHEN 1 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 4 END, lower(appName)"
        private const val ORDER_BY_EXCLUDED = " CASE firewallStatus WHEN 3 THEN 0 WHEN 0 THEN 1 WHEN 1 THEN 2 WHEN 2 THEN 3 WHEN 4 THEN 4 END, lower(appName)"

    }

    enum class TopLevelFilter(val id: Int) {
        ALL(0), INSTALLED(1), SYSTEM(2)
    }

    enum class SortFilter(val id: Int) {
        NONE(0), BLOCKED(2), WHITELISTED(3), EXCLUDED(4); //RESTRICTED(1),

        companion object {
            fun getSortFilter(id: Int): SortFilter {
                return when (id) {
                    NONE.id -> NONE
                    BLOCKED.id -> BLOCKED
                    WHITELISTED.id -> WHITELISTED
                    EXCLUDED.id -> EXCLUDED
                    else -> NONE
                }
            }
        }
    }

    class Filters {
        var categoryFilters: MutableSet<String> = mutableSetOf()
        var topLevelFilter = TopLevelFilter.ALL
        var sortType = SortFilter.NONE
        var searchString: String = ""

        fun getSortByQuery(): String {
            return when (sortType) {
                SortFilter.NONE -> ORDER_BY_DEFAULT
                SortFilter.BLOCKED -> ORDER_BY_BLOCKED
                SortFilter.WHITELISTED -> ORDER_BY_WHITELISTED
                SortFilter.EXCLUDED -> ORDER_BY_EXCLUDED
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObserver()
        setupClickListener()
    }

    private fun initObserver() {
        filters.observe(this.viewLifecycleOwner) {
            appInfoViewModel.setFilter(it)
            resetFirewallIcons()
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        addQueryToFilters(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(QUERY_TEXT_TIMEOUT, lifecycleScope) {
            if (isAdded) {
                addQueryToFilters(query)
            }
        }
        return true
    }

    private fun addQueryToFilters(query: String) {
        val a = filterObserver()
        a.value?.searchString = query
        filters.postValue(a.value)
    }

    private fun setupClickListener() {
        b.ffaFilterIcon.setOnClickListener {
            openFilterBottomSheet()
        }

        b.ffaRefreshList.setOnClickListener {
            b.ffaRefreshList.isEnabled = false
            b.ffaRefreshList.animation = animation
            b.ffaRefreshList.startAnimation(animation)
            refreshDatabase()
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (isAdded) {
                    b.ffaRefreshList.isEnabled = true
                    b.ffaRefreshList.clearAnimation()
                    Utilities.showToastUiCentered(requireContext(),
                                                  getString(R.string.refresh_complete),
                                                  Toast.LENGTH_SHORT)
                }
            }
        }

        b.ffaToggleAllWifi.setOnClickListener {
            updateWifi()
        }

        b.ffaToggleAllMobileData.setOnClickListener {
            updateMobileData()
        }
    }

    private fun resetFirewallIcons() {
        b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on_grey)
        b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on_grey)
    }

    private fun updateMobileData() {
        if (b.ffaToggleAllMobileData.tag == 0) {
            b.ffaToggleAllMobileData.tag = 1
            b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_off)
            io {
                appInfoViewModel.updateMobileDataStatus(true)
            }
            return
        }

        b.ffaToggleAllMobileData.tag = 0
        b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on)
        io {
            appInfoViewModel.updateMobileDataStatus(false)
        }
    }

    private fun updateWifi() {
        if (b.ffaToggleAllWifi.tag == 0) {
            b.ffaToggleAllWifi.tag = 1
            b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_off)
            io {
                appInfoViewModel.updateWifiStatus(true)
            }
            return
        }

        b.ffaToggleAllWifi.tag = 0
        b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on)
        io {
            appInfoViewModel.updateWifiStatus(false)
        }
    }

    fun filterObserver(): MutableLiveData<Filters> {
        return filters
    }

    private fun initView() {
        initListAdapter()
        b.ffaSearch.setOnQueryTextListener(this)
        addAnimation()
    }

    private fun initListAdapter() {
        b.ffaAppList.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        b.ffaAppList.layoutManager = layoutManager
        val recyclerAdapter = FirewallAppListAdapter(requireContext(), viewLifecycleOwner,
                                                     persistentState)
        appInfoViewModel.appInfo.observe(viewLifecycleOwner,
                                         androidx.lifecycle.Observer(recyclerAdapter::submitList))
        b.ffaAppList.adapter = recyclerAdapter
        val dividerItemDecoration = DividerItemDecoration(b.ffaAppList.context,
                                                          (layoutManager as LinearLayoutManager).orientation)
        b.ffaAppList.addItemDecoration(dividerItemDecoration)

    }

    private fun openFilterBottomSheet() {
        val bottomSheetFragment = FirewallAppFilterBottomSheet(this)
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun addAnimation() {
        animation = RotateAnimation(ANIMATION_START_DEGREE, ANIMATION_END_DEGREE,
                                    Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE,
                                    Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE)
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    private fun refreshDatabase() {
        io {
            refreshDatabase.refreshAppInfoDatabase()
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }
}
