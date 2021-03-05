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
import android.widget.CompoundButton
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.databinding.ExcludeAppDialogLayoutBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import com.google.android.material.chip.Chip
import java.util.stream.Collectors


class ExcludeAppDialog(private var activity: Context, private val appInfoRepository: AppInfoRepository, private val categoryInfoRepository: CategoryInfoRepository, private val persistentState: PersistentState, internal var adapter: RecyclerView.Adapter<*>, var viewModel: ExcludedAppViewModel) : Dialog(activity), View.OnClickListener, SearchView.OnQueryTextListener {
    private lateinit var b: ExcludeAppDialogLayoutBinding
    var dialog: Dialog? = null

    private var mLayoutManager: RecyclerView.LayoutManager? = null

    private var filterCategories: MutableList<String> = ArrayList()
    private var category: List<String> = ArrayList()

    var filterState: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = ExcludeAppDialogLayoutBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)

        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        mLayoutManager = LinearLayoutManager(activity)

        b.excludeAppRecyclerViewDialog.layoutManager = mLayoutManager
        b.excludeAppRecyclerViewDialog.adapter = adapter

        b.excludeAppDialogOkButton.setOnClickListener(this)

        b.excludeAppDialogWhitelistSearchView.setOnQueryTextListener(this)
        b.excludeAppDialogWhitelistSearchView.setOnSearchClickListener(this)

        b.excludeAppDialogWhitelistSearchView.setOnCloseListener {
            showCategoryChips()
            false
        }

        val appCount = HomeScreenActivity.GlobalVariable.appList.size
        val act: HomeScreenActivity = activity as HomeScreenActivity
        appInfoRepository.getExcludedAppListCountLiveData().observe(act, {
            b.excludeAppSelectCountText.text = act.getString(R.string.ex_dialog_count, it.toString(), appCount.toString())
        })


        b.excludeAppSelectAllOptionCheckbox.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            modifyAppsInExcludedAppList(b)
            object : CountDownTimer(1000, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    adapter.notifyDataSetChanged()
                }
            }.start()
        }
        b.excludeAppDialogWhitelistSearchFilter.setOnClickListener(this)

        categoryListByAppNameFromDB("")
    }

    private fun modifyAppsInExcludedAppList(checked: Boolean) {
        if (filterCategories.isNullOrEmpty()) {
            appInfoRepository.updateExcludedForAllApp(checked)
            categoryInfoRepository.updateExcludedCountForAllApp(checked)
            if (checked) {
                categoryInfoRepository.updateWhitelistCountForAll(!checked)
            }
        } else {
            filterCategories.forEach {
                appInfoRepository.updateExcludedForCategories(it, checked)
                categoryInfoRepository.updateExcludedCountForCategory(it, checked)
                if (checked) {
                    categoryInfoRepository.updateWhitelistForCategory(it, !checked)
                }
            }
        }
    }


    private fun categoryListByAppNameFromDB(name: String) {
        category = appInfoRepository.getAppCategoryForAppName("%$name%")
        if (DEBUG) Log.d(LOG_TAG, "Category - ${category.size}")
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
    }

    private fun showCategoryChips() {
        if (!filterState) {
            filterState = true
            b.excludeAppDialogChipGroup.visibility = View.VISIBLE
        } else {
            filterState = false
            b.excludeAppDialogChipGroup.visibility = View.GONE
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
        b.excludeAppDialogChipGroup.removeAllViews()
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
                val filterString = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
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
                    viewModel.setFilter("category:$filterString")
                } else {
                    viewModel.setFilter("")
                }
            }
            b.excludeAppDialogChipGroup.addView(mChip)
        }
    }


}