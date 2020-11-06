/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ExpandableListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppListAdapter
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.isSearchEnabled
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.viewmodel.FirewallAppViewModel

class FirewallAppFragment : Fragment(), SearchView.OnQueryTextListener {

    private var adapterList: FirewallAppListAdapter? = null

    private var titleList: MutableList<CategoryInfo>? = ArrayList()
    private var listData: HashMap<CategoryInfo, ArrayList<AppInfo>> = HashMap()
    private var editSearch: SearchView? = null
    private lateinit var categoryShowTxt: TextView
    private var categoryState: Boolean = false
    private lateinit var loadingProgressBar: ProgressBar
    private var firewallExpandableList: ExpandableListView? = null

    private val firewallAppInfoViewModel : FirewallAppViewModel by viewModels()


    companion object {
           fun newInstance() = FirewallAppFragment()
       }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)
        val view: View = inflater.inflate(R.layout.fragment_firewall_all_apps, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        FirewallAppViewModel.setContext(requireContext())
        categoryShowTxt = view.findViewById(R.id.firewall_category_show_txt)
        categoryState = true
        loadingProgressBar = view.findViewById(R.id.firewall_update_progress)
        firewallExpandableList = view.findViewById(R.id.firewall_expandable_list)
        firewallExpandableList!!.visibility = View.VISIBLE
        if (firewallExpandableList != null) {
            adapterList = FirewallAppListAdapter(requireContext(), titleList as ArrayList<CategoryInfo>, listData)
            firewallExpandableList!!.setAdapter(adapterList)

            firewallExpandableList!!.setOnGroupClickListener { _, view, i, l ->
                //setListViewHeight(expandableListView, i);
                false
            }

            firewallExpandableList!!.setOnGroupExpandListener { it ->
                //listData[titleList!![it]]!!.sortBy { it.isInternetAllowed }
            }
        }
        loadingProgressBar.visibility = View.VISIBLE

       /* if (HomeScreenActivity.isLoadingComplete) {
            loadingProgressBar.visibility = View.GONE
            firewallExpandableList!!.visibility = View.VISIBLE
        }*/

        editSearch = view.findViewById(R.id.firewall_category_search)
        //TODO Search
        editSearch!!.setOnQueryTextListener(this)

        editSearch!!.setOnClickListener {
            editSearch!!.requestFocus()
            editSearch!!.onActionViewExpanded()
        }

        categoryShowTxt.setOnClickListener {
            if (!categoryState) {
                categoryState = true
                firewallExpandableList!!.visibility = View.VISIBLE
                categoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, requireContext().getDrawable(R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            } else {
                firewallExpandableList!!.visibility = View.GONE
                categoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, requireContext().getDrawable(R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
                categoryState = false
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        observersForUI()
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        //(adapterList as FirewallAppListAdapter).filterData(query!!)
        if(DEBUG) Log.d(LOG_TAG, "Category block onQueryTextSubmit: ${HomeScreenActivity.GlobalVariable.isSearchEnabled}, $query")
        if (HomeScreenActivity.GlobalVariable.isSearchEnabled) {
            object : CountDownTimer(500, 1000) {
                override fun onTick(millisUntilFinished: Long) {

                }

                override fun onFinish() {
                    if(DEBUG) Log.d(LOG_TAG, "Category block onQueryTextSubmit final: ${HomeScreenActivity.GlobalVariable.isSearchEnabled}, $query")
                    firewallAppInfoViewModel.setFilter(query)
                    if (query.isNullOrEmpty()) {
                        var i = 0
                        titleList!!.forEach { _ ->
                            firewallExpandableList!!.collapseGroup(i)
                            i += 1
                        }
                    } else {
                        //var i = 0

                        if (titleList!!.size > 0) {
                            for (i in titleList!!.indices) {
                                if (listData[titleList!![i]] != null) {
                                    if (listData[titleList!![i]]!!.size > 0) {
                                        firewallExpandableList!!.expandGroup(i)
                                    }
                                }
                            }

                            /*titleList!!.forEach { _ ->
                                firewallExpandableList!!.expandGroup(i)
                                i += 1
                            }*/
                        }
                        if(DEBUG) Log.d(LOG_TAG, "Category block  ${titleList!!.size}")
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
        if(DEBUG) Log.d(LOG_TAG, "Category block onQueryTextChange : ${HomeScreenActivity.GlobalVariable.isSearchEnabled}, $query")
        if (HomeScreenActivity.GlobalVariable.isSearchEnabled) {
            object : CountDownTimer(500, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    if(DEBUG) Log.d(LOG_TAG, "Category block onQueryTextChange final: ${HomeScreenActivity.GlobalVariable.isSearchEnabled}, $query")
                    firewallAppInfoViewModel.setFilter(query)
                    if (query.isNullOrEmpty()) {
                        var i = 0
                        titleList!!.forEach { _ ->
                            firewallExpandableList!!.collapseGroup(i)
                            i += 1
                        }

                    } else {
                        //var i = 0
                        if(titleList!!.size > 0) {

                            for(i in titleList!!.indices){
                                if(listData[titleList!![i]] != null) {
                                    if (listData[titleList!![i]]!!.size > 0) {
                                        firewallExpandableList!!.expandGroup(i)
                                    }
                                }
                            }

                            /*titleList!!.forEach { _ ->
                                firewallExpandableList!!.expandGroup(i)
                                i += 1
                            }*/
                        }
                        if(DEBUG) Log.d(LOG_TAG, "Category block ${titleList!!.size}")
                    }
                }
            }.start()
        }
        return true
    }
/*
   private fun setListViewHeight(listView: ExpandableListView, group: Int) {
        val listAdapter = listView.expandableListAdapter as ExpandableListAdapter
        var totalHeight = 0
        val desiredWidth = View.MeasureSpec.makeMeasureSpec(
            listView.width,
            View.MeasureSpec.EXACTLY
        )
        for (i in 0 until listAdapter.groupCount) {
            val groupItem = listAdapter.getGroupView(i, false, null, listView)
            groupItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED)
            totalHeight += groupItem.measuredHeight
            if (listView.isGroupExpanded(i) && i != group
                || !listView.isGroupExpanded(i) && i == group
            ) {
                for (j in 0 until 1) {
                    val listItem = listAdapter.getChildView(
                        i, j, false, null, listView
                    )
                    listItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED)
                    totalHeight += listItem.measuredHeight
                }
               *//* for (j in 0 until listAdapter.getChildrenCount(i)) {
                    val listItem = listAdapter.getChildView(
                        i, j, false, null, listView
                    )
                    listItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED)
                    totalHeight += listItem.measuredHeight
                }*//*
            }
        }
        val params: ViewGroup.LayoutParams = listView.layoutParams
        var height = (totalHeight
                + listView.dividerHeight * (listAdapter.groupCount - 1))
        if (height < 10) height = 100
        params.height = height
        listView.layoutParams = params
        listView.invalidate()
        //listView.requestLayout()
    }*/

    override fun onResume() {
        super.onResume()
        adapterList?.notifyDataSetChanged()
    }


    private fun observersForUI() {
        val mDb = AppDatabase.invoke(requireContext().applicationContext)
        //val appInfoRepository = mDb.appInfoRepository()

        val categoryInfoRepository = mDb.categoryInfoRepository()
        categoryInfoRepository.getAppCategoryForLiveData().observe(viewLifecycleOwner, Observer {
            titleList = it.toMutableList()
        })

        firewallAppInfoViewModel.firewallAppDetailsList.observe(viewLifecycleOwner, Observer { itAppInfo ->
            isSearchEnabled  = false
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

            /*titleList!!.forEach {
                val categoryList = list.filter { a -> a.appCategory == it.categoryName }
                val count = categoryList.filter { a -> !a.isInternetAllowed }
                if (categoryList.isNotEmpty()) {
                    it.numOfAppsBlocked = count.size
                    if (categoryList.size == count.size)
                        it.isInternetBlocked = true
                    listData[it] = categoryList as java.util.ArrayList<AppInfo>
                } else {
                    titleList!!.remove(it)
                }

            }*/
            if (adapterList != null) {
                (adapterList as FirewallAppListAdapter).updateData(titleList!!, listData, list as ArrayList<AppInfo>)
                loadingProgressBar.visibility = View.GONE
                firewallExpandableList!!.visibility = View.VISIBLE
            } else {
                loadingProgressBar.visibility = View.VISIBLE
                firewallExpandableList!!.visibility = View.GONE
            }
            isSearchEnabled  = true
        })

        /*appInfoRepository.getAppDetailsForLiveData(input).observe(viewLifecycleOwner, Observer { itAppInfo ->
            val list = itAppInfo!!
            titleList!!.forEach {
                val categoryList = list.filter { a -> a.appCategory == it.categoryName }
                val count = categoryList.filter { a -> !a.isInternetAllowed }
                it.numOfAppsBlocked = count.size
                if (categoryList.size == count.size)
                    it.isInternetBlocked = true
                listData[it] = categoryList as java.util.ArrayList<AppInfo>
            }
            if (adapterList != null) {
                (adapterList as FirewallAppListAdapter).updateData(titleList!!, listData, list as ArrayList<AppInfo>)
                if (HomeScreenActivity.isLoadingComplete) {
                    loadingProgressBar.visibility = View.GONE
                    firewallExpandableList!!.visibility = View.VISIBLE
                }
            } else {
                loadingProgressBar.visibility = View.GONE
            }
        })*/


    }

}