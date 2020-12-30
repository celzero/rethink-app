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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.stream.Collectors


class ExcludeAppDialog(private var activity: Context,
                       private val appInfoRepository: AppInfoRepository,
                       private val categoryInfoRepository: CategoryInfoRepository,
                       private val persistentState:PersistentState,
                       internal var adapter: RecyclerView.Adapter<*>,
                       var viewModel: ExcludedAppViewModel) : Dialog(activity),
    View.OnClickListener, SearchView.OnQueryTextListener {
    var dialog: Dialog? = null

    private var recyclerView: RecyclerView? = null
    private var mLayoutManager: RecyclerView.LayoutManager? = null
    private lateinit var okBtn: Button
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var searchView: SearchView
    private lateinit var filterIcon: ImageView
    private lateinit var selectAllCheckBox: AppCompatCheckBox
    private lateinit var countSelectedText : TextView

    private var filterCategories: MutableList<String> = ArrayList()
    private var category: List<String> = ArrayList()

    var filterState: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.exclude_app_dialog_layout)
        setCancelable(false)

        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        recyclerView = findViewById(R.id.exclude_app_recycler_view_dialog)
        mLayoutManager = LinearLayoutManager(activity)

        recyclerView?.layoutManager = mLayoutManager
        recyclerView?.adapter = adapter

        okBtn = findViewById(R.id.exclude_app_dialog_ok_button)
        searchView = findViewById(R.id.exclude_app_dialog_whitelist_search_view)
        categoryChipGroup = findViewById(R.id.exclude_app_dialog_chip_group)
        selectAllCheckBox = findViewById(R.id.exclude_app_select_all_option_checkbox)
        filterIcon = findViewById(R.id.exclude_app_dialog_whitelist_search_filter)
        countSelectedText = findViewById(R.id.exclude_app_select_count_text)

        okBtn.setOnClickListener(this)

        searchView.setOnQueryTextListener(this)
        searchView.setOnSearchClickListener(this)

        searchView.setOnCloseListener {
            showCategoryChips()
            false
        }

        val appCount = HomeScreenActivity.GlobalVariable.appList.size
        val act: HomeScreenActivity = activity as HomeScreenActivity
        appInfoRepository.getExcludedAppListCountLiveData().observe(act, {
            countSelectedText.text = "$it/$appCount apps excluded"
        })


        selectAllCheckBox.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
            modifyAppsInExcludedAppList(b)
            object : CountDownTimer(1000, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }
                override fun onFinish() {
                    adapter.notifyDataSetChanged()
                }
            }.start()
        }
        filterIcon.setOnClickListener(this)

        categoryListByAppNameFromDB("")
    }

    private fun modifyAppsInExcludedAppList(checked: Boolean) {
        if(filterCategories.isNullOrEmpty()){
            appInfoRepository.updateExcludedForAllApp(checked)
            categoryInfoRepository.updateExcludedCountForAllApp(checked)
            if(checked) {
                categoryInfoRepository.updateWhitelistCountForAll(!checked)
            }
        }else{
            filterCategories.forEach{
                appInfoRepository.updateExcludedForCategories(it, checked)
                categoryInfoRepository.updateExcludedCountForCategory(it, checked)
                if(checked) {
                    categoryInfoRepository.updateWhitelistForCategory(it, !checked)
                }
            }
        }
    }


    private fun categoryListByAppNameFromDB(name : String){
        category = appInfoRepository.getAppCategoryForAppName("%$name%")
        if(DEBUG) Log.d(LOG_TAG,"Category - ${category.size}")
        setCategoryChips(category)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.exclude_app_dialog_ok_button -> {
                applyChanges()
                filterCategories.clear()
                viewModel.setFilter("")
                dismiss()
            }
            R.id.exclude_app_dialog_whitelist_search_filter -> {
                showCategoryChips()
            }
            R.id.exclude_app_dialog_whitelist_search_view -> {
                showCategoryChips()
            }
            else -> {
                filterCategories.clear()
                viewModel.setFilter("")
                dismiss()
            }
        }
    }

    private fun applyChanges() {
        val excludedApps = appInfoRepository.getExcludedAppList()
        persistentState.excludedAppsFromVPN = excludedApps.toMutableSet()
        //Toast.makeText(activity,"Update Successful",Toast.LENGTH_SHORT).show()
    }

    private fun showCategoryChips() {
        if (!filterState) {
            filterState = true
            categoryChipGroup.visibility = View.VISIBLE
        } else {
            filterState = false
            categoryChipGroup.visibility = View.GONE
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        categoryListByAppNameFromDB(query!!)
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        categoryListByAppNameFromDB(query!!)
        viewModel.setFilter(query)
        return true
    }

    private fun setCategoryChips(categories: List<String>) {
        categoryChipGroup.removeAllViews()
        for (category in categories) {
            val mChip = this.layoutInflater.inflate(R.layout.item_chip_category, null, false) as Chip
            mChip.text = category

            mChip.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                val categoryName = compoundButton.text.toString()
                if (b) {
                    filterCategories.add(categoryName)
                } else {
                    if (filterCategories.contains(categoryName)) {
                        filterCategories.remove(categoryName)
                    }
                }
                val filterString =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        filterCategories.stream().collect(Collectors.joining(","))
                    } else {
                        var catTitle = ""
                        filterCategories.forEach {
                            catTitle = "$it,$catTitle"
                        }
                        if (catTitle.length > 1) {
                            catTitle.substring(0, catTitle.length - 1)
                        } else {
                            catTitle
                        }
                    }
                if (filterString.isNotEmpty()) {
                    if (DEBUG) Log.d(LOG_TAG, "category - $filterString")
                    viewModel.setFilter("category:$filterString")
                } else {
                    viewModel.setFilter("")
                }
            }
            categoryChipGroup.addView(mChip)
        }
    }


}