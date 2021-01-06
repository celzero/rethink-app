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
import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.isSearchEnabled
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.FirewallAppViewModel
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class FirewallAppFragment : Fragment(R.layout.fragment_firewall_all_apps), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentFirewallAllAppsBinding::bind)
    private var adapterList: FirewallAppListAdapter? = null

    private var titleList: MutableList<CategoryInfo>? = ArrayList()
    private var listData: HashMap<CategoryInfo, ArrayList<AppInfo>> = HashMap()
    private var categoryState: Boolean = false

    private lateinit var animation: Animation

    private val firewallAppInfoViewModel: FirewallAppViewModel by viewModel()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val refreshDatabase by inject<RefreshDatabase>()
    private val persistentState by inject<PersistentState>()

    companion object {
        fun newInstance() = FirewallAppFragment()
    }


    private fun initView() {
        categoryState = true
        b.firewallExpandableList.visibility = View.VISIBLE
        adapterList = FirewallAppListAdapter(requireContext(), get(), categoryInfoRepository, persistentState, titleList as ArrayList<CategoryInfo>, listData)
        b.firewallExpandableList.setAdapter(adapterList)

        b.firewallExpandableList.setOnGroupClickListener { _, _, _, _ ->
            //setListViewHeight(expandableListView, i);
            false
        }

        b.firewallExpandableList.setOnGroupExpandListener {
            //listData[titleList!![it]]!!.sortBy { it.isInternetAllowed }
        }
        b.firewallUpdateProgress.visibility = View.VISIBLE

        b.firewallCategorySearch.setOnQueryTextListener(this)

        animation = RotateAnimation(0.0f, 360.0f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        animation.repeatCount = -1
        animation.duration = 750
    }

    private fun initClickListeners() {

        b.firewallCategorySearch.setOnClickListener {
            b.firewallCategorySearch.requestFocus()
            b.firewallCategorySearch.onActionViewExpanded()
        }

        b.firewallCategoryShowTxt.setOnClickListener {
            if (!categoryState) {
                categoryState = true
                b.firewallExpandableList.visibility = View.VISIBLE
                b.firewallCategoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            } else {
                b.firewallExpandableList.visibility = View.GONE
                b.firewallCategoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
                categoryState = false
            }
        }

        b.firewallAppRefreshList.setOnClickListener {
            b.firewallAppRefreshList.isEnabled = false
            refreshDatabase()
            Handler().postDelayed({ b.firewallAppRefreshList.isEnabled = true }, 4000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observersForUI()
        initView()
        initClickListeners()
    }

    private fun refreshDatabase() {
        b.firewallAppRefreshList.animation = animation
        b.firewallAppRefreshList.startAnimation(animation)
        object : CountDownTimer(4000, 500) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                if (isAdded) {
                    b.firewallAppRefreshList.clearAnimation()
                    if (RethinkDNSApplication.context != null) {
                        Utilities.showToastInMidLayout(RethinkDNSApplication.context!!, getString(R.string.refresh_complete), Toast.LENGTH_SHORT)
                    }
                }
            }
        }.start()
        refreshDatabase.refreshAppInfoDatabase()
        refreshDatabase.updateCategoryInDB()
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        //(adapterList as FirewallAppListAdapter).filterData(query!!)
        if (DEBUG) Log.d(LOG_TAG, "Category block onQueryTextSubmit: ${isSearchEnabled}, $query")
        if (isSearchEnabled) {
            object : CountDownTimer(500, 1000) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    if (DEBUG) Log.d(LOG_TAG, "Category block onQueryTextSubmit final: ${isSearchEnabled}, $query")
                    firewallAppInfoViewModel.setFilter(query)
                    if (query.isNullOrEmpty()) {
                        var i = 0
                        titleList!!.forEach { _ ->
                            b.firewallExpandableList.collapseGroup(i)
                            i += 1
                        }
                    } else {
                        if (titleList!!.size > 0) {
                            for (i in titleList!!.indices) {
                                if (listData[titleList!![i]] != null) {
                                    if (listData[titleList!![i]]!!.size > 0) {
                                        b.firewallExpandableList.expandGroup(i)
                                    }
                                }
                            }
                        }
                        if (DEBUG) Log.d(LOG_TAG, "Category block  ${titleList!!.size}")
                    }
                }
            }.start()


        }
        //observersForUI("%$query%")
        return false
    }

    override fun onQueryTextChange(query: String?): Boolean {
        //(adapterList as FirewallAppListAdapter).filterData(query!!)
        //observersForUI("%$query%")
        if (DEBUG) Log.d(LOG_TAG, "Category block onQueryTextChange : ${isSearchEnabled}, $query")
        if (isSearchEnabled) {
            object : CountDownTimer(500, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    if (DEBUG) Log.d(LOG_TAG, "Category block onQueryTextChange final: ${isSearchEnabled}, $query")
                    firewallAppInfoViewModel.setFilter(query)
                    if (query.isNullOrEmpty()) {
                        var i = 0
                        titleList!!.forEach { _ ->
                            b.firewallExpandableList.collapseGroup(i)
                            i += 1
                        }

                    } else {
                        //var i = 0
                        if (titleList!!.size > 0) {

                            for (i in titleList!!.indices) {
                                if (listData[titleList!![i]] != null) {
                                    if (listData[titleList!![i]]!!.size > 0) {
                                        b.firewallExpandableList.expandGroup(i)
                                    }
                                }
                            }
                        }
                        if (DEBUG) Log.d(LOG_TAG, "Category block ${titleList!!.size}")
                    }
                }
            }.start()
        }
        return true
    }


    override fun onResume() {
        super.onResume()
        adapterList?.notifyDataSetChanged()
    }


    private fun observersForUI() {
        categoryInfoRepository.getAppCategoryForLiveData().observe(viewLifecycleOwner, {
            titleList = it.toMutableList()
        })

        firewallAppInfoViewModel.firewallAppDetailsList.observe(viewLifecycleOwner) { itAppInfo ->
            isSearchEnabled = false
            val list = itAppInfo!!
            titleList = categoryInfoRepository.getAppCategoryList().toMutableList()
            val iterator = titleList!!.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (DEBUG) Log.d(LOG_TAG, "Category : ${item.categoryName}, ${item.numberOFApps}, ${item.numOfAppsBlocked}, ${item.isInternetBlocked}")
                val appList = list.filter { a -> a.appCategory == item.categoryName }
                //val count = categoryList.filter { a -> !a.isInternetAllowed }
                if (appList.isNotEmpty()) {
                    listData[item] = appList as java.util.ArrayList<AppInfo>
                } else {
                    iterator.remove()
                }
            }


            if (adapterList != null) {
                (adapterList as FirewallAppListAdapter).updateData(titleList!!, listData, list as ArrayList<AppInfo>)
                b.firewallUpdateProgress.visibility = View.GONE
                b.firewallExpandableList.visibility = View.VISIBLE
            } else {
                b.firewallUpdateProgress.visibility = View.VISIBLE
                b.firewallExpandableList.visibility = View.GONE
            }
            isSearchEnabled = true
        }


    }

}
