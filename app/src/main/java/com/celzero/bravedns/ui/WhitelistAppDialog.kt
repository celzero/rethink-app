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
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.AppInfoViewRepository
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.databinding.CustomDialogLayoutBinding
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.google.android.material.chip.Chip
import java.util.stream.Collectors


class WhitelistAppDialog(private var activity: Context,
                         private val appInfoRepository: AppInfoRepository,
                         private val appInfoViewRepository: AppInfoViewRepository,
                         private val categoryInfoRepository: CategoryInfoRepository,
                         internal var adapter: RecyclerView.Adapter<*>,
                         var viewModel: AppListViewModel,
                         themeID :Int)
    : Dialog(activity, themeID), View.OnClickListener, SearchView.OnQueryTextListener {

    private lateinit var b: CustomDialogLayoutBinding

    private var mLayoutManager: RecyclerView.LayoutManager? = null
    private var filterCategories: MutableList<String> = ArrayList()
    private var category: List<String> = ArrayList()

    var filterState: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = CustomDialogLayoutBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)

        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        mLayoutManager = LinearLayoutManager(activity)

        b.recyclerViewDialog.layoutManager = mLayoutManager
        b.recyclerViewDialog.adapter = adapter

        b.customDialogOkButton.setOnClickListener(this)
        b.customDialogWhitelistSearchView.setOnQueryTextListener(this)
        b.customDialogWhitelistSearchView.setOnSearchClickListener(this)
        filterCategories.clear()

        b.customDialogWhitelistSearchView.setOnCloseListener {
            showCategoryChips()
            false
        }

        val appCount = appList.size
        val act: FirewallActivity = activity as FirewallActivity
        appInfoViewRepository.getWhitelistCountLiveData().observe(act, {
            b.customSelectAllOptionCount.text = act.getString(R.string.whitelist_dialog_apps_in_use, it.toString(), appCount.toString())
        })

        b.customSelectAllOptionCheckbox.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            modifyAppsInUniversalAppList(b)
            if (b) {
                Utilities.showToastUiCentered(activity, act.getString(R.string.whitelist_toast_positive), Toast.LENGTH_SHORT)
            } else {
                Utilities.showToastUiCentered(activity, act.getString(R.string.whitelist_toast_negative), Toast.LENGTH_SHORT)
            }
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    adapter.notifyDataSetChanged()
                }
            }.start()

        }
        b.customDialogWhitelistSearchFilter.setOnClickListener(this)

        categoryListByAppNameFromDB("")
    }


    private fun modifyAppsInUniversalAppList(checked: Boolean) {
        if (filterCategories.isNullOrEmpty()) {
            appInfoRepository.updateWhiteListForAllApp(checked)
            val categoryList = appInfoRepository.getAppCategoryList()
            categoryList.forEach {
                val countBlocked = appInfoRepository.getBlockedCountForCategory(it)
                categoryInfoRepository.updateBlockedCount(it, countBlocked)
            }
            categoryInfoRepository.updateWhitelistCountForAll(checked)
        } else {
            filterCategories.forEach {
                val update = appInfoRepository.updateWhiteListForCategories(it, checked)
                categoryInfoRepository.updateWhitelistForCategory(it, checked)
                val countBlocked = appInfoRepository.getBlockedCountForCategory(it)
                categoryInfoRepository.updateBlockedCount(it, countBlocked)
                if(DEBUG) Log.d(LOG_TAG_FIREWALL, "Update whitelist count: $update")
            }

        }
    }


    private fun categoryListByAppNameFromDB(name: String) {
        category = appInfoRepository.getAppCategoryForAppName("%$name%")
        setCategoryChips(category)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.custom_dialog_ok_button -> {
                filterCategories.clear()
                viewModel.setFilter("")
                dismiss()
            }
            R.id.custom_dialog_whitelist_search_filter -> {
                showCategoryChips()
            }
            R.id.custom_dialog_whitelist_search_view -> {
                showCategoryChips()
            }
            else -> {
                filterCategories.clear()
                viewModel.setFilter("")
                dismiss()
            }
        }

    }

    private fun showCategoryChips() {
        if (!filterState) {
            filterState = true
            b.customDialogChipGroup.visibility = View.VISIBLE
        } else {
            filterState = false
            b.customDialogChipGroup.visibility = View.GONE
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
        b.customDialogChipGroup.removeAllViews()
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
            b.customDialogChipGroup.addView(mChip)
        }
    }


}