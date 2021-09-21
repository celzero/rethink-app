/*
 * Copyright 2020 RethinkDNS and its authors
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
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppListAdapter
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.FragmentFirewallAllAppsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.FirewallAppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.collections.set

class FirewallAppFragment : Fragment(R.layout.fragment_firewall_all_apps),
                            SearchView.OnQueryTextListener {

    private val b by viewBinding(FragmentFirewallAllAppsBinding::bind)
    private var adapterList: FirewallAppListAdapter? = null

    private var appCategories: MutableList<CategoryInfo> = ArrayList()
    private var filteredCategories: MutableList<CategoryInfo> = ArrayList()
    private var listData: HashMap<CategoryInfo, List<AppInfo>> = HashMap()

    private lateinit var animation: Animation

    private val firewallAppInfoViewModel: FirewallAppViewModel by viewModel()

    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val refreshDatabase by inject<RefreshDatabase>()
    private val persistentState by inject<PersistentState>()

    companion object {
        fun newInstance() = FirewallAppFragment()

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val REFRESH_TIMEOUT: Long = 4000
        private const val QUERY_TEXT_TIMEOUT: Long = 1000
    }


    private fun initView() {
        b.firewallExpandableList.visibility = View.VISIBLE
        b.firewallUpdateProgress.visibility = View.VISIBLE
        b.firewallAppRefreshList.isEnabled = true

        adapterList = FirewallAppListAdapter(requireContext(), viewLifecycleOwner, persistentState,
                                             filteredCategories, listData)
        b.firewallExpandableList.setAdapter(adapterList)

        b.firewallExpandableList.setOnGroupClickListener { _, _, _, _ ->
            false
        }
        b.firewallExpandableList.setOnGroupExpandListener {}
        b.firewallCategorySearch.setOnQueryTextListener(this)

        addAnimation()
    }

    private fun addAnimation() {
        animation = RotateAnimation(ANIMATION_START_DEGREE, ANIMATION_END_DEGREE,
                                    Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE,
                                    Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE)
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    private fun setupClickListeners() {

        b.firewallCategorySearch.setOnClickListener {
            b.firewallCategorySearch.requestFocus()
            b.firewallCategorySearch.onActionViewExpanded()
        }

        b.firewallAppRefreshList.setOnClickListener {
            b.firewallAppRefreshList.isEnabled = false
            b.firewallAppRefreshList.animation = animation
            b.firewallAppRefreshList.startAnimation(animation)
            refreshDatabase()
            Utilities.delay(REFRESH_TIMEOUT) {
                if (isAdded) {
                    b.firewallAppRefreshList.isEnabled = true
                    b.firewallAppRefreshList.clearAnimation()
                    Utilities.showToastUiCentered(requireContext(),
                                                  getString(R.string.refresh_complete),
                                                  Toast.LENGTH_SHORT)
                    adapterList?.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLivedataObservers()
        initView()
        setupClickListeners()
    }

    private fun refreshDatabase() {
        io {
            refreshDatabase.refreshAppInfoDatabase()
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        searchAndExpandCategories(query)
        return false
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(QUERY_TEXT_TIMEOUT) {
            if (isAdded) {
                searchAndExpandCategories(query)
            }
        }
        return true
    }

    private fun searchAndExpandCategories(query: String) {
        firewallAppInfoViewModel.setFilter(query)
        if (filteredCategories.count() <= 0) return

        if (query.isEmpty()) {
            filteredCategories.forEachIndexed { i, _ ->
                b.firewallExpandableList.collapseGroup(i)
            }
        } else {
            for (i in filteredCategories.indices) {
                listData[filteredCategories[i]]?.let {
                    if (it.count() > 0) {
                        b.firewallExpandableList.expandGroup(i)
                    }
                }
            }
            Log.i(LOG_TAG_FIREWALL, "Category block ${filteredCategories.count()}")
        }
    }

    override fun onResume() {
        super.onResume()
        adapterList?.notifyDataSetChanged()
    }

    private fun setupLivedataObservers() {

        categoryInfoRepository.getAppCategoryForLiveData().observe(viewLifecycleOwner, {
            appCategories = it.toMutableList()
        })

        firewallAppInfoViewModel.firewallAppDetailsList.observe(viewLifecycleOwner) { itAppInfo ->
            listData.clear()
            filteredCategories = appCategories.toMutableList()
            val iterator = filteredCategories.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val appList = itAppInfo.filter { a -> a.appCategory == item.categoryName }
                listData[item] = appList
                if (appList.isNotEmpty()) {
                    listData[item] = appList
                } else {
                    iterator.remove()
                }
            }

            adapterList?.updateData(filteredCategories, listData)
            hideProgressBar()
            showExpandableList()
        }
    }

    private fun hideProgressBar() {
        b.firewallUpdateProgress.visibility = View.GONE
    }

    private fun showExpandableList() {
        b.firewallExpandableList.visibility = View.VISIBLE
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

}
