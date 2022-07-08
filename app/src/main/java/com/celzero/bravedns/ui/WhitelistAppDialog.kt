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

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.databinding.DialogWhitelistAppsBinding
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.google.android.material.chip.Chip

class WhitelistAppDialog(val activity: Activity, val adapter: RecyclerView.Adapter<*>,
                         val viewModel: AppListViewModel, themeID: Int) : Dialog(activity, themeID),
                                                                          View.OnClickListener,
                                                                          SearchView.OnQueryTextListener {

    private lateinit var b: DialogWhitelistAppsBinding

    private var mLayoutManager: RecyclerView.LayoutManager? = null
    private var filterCategories: MutableSet<String> = mutableSetOf()
    private var category: List<String> = ArrayList()

    private val CATEGORY_FILTER_CONST = "category:"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogWhitelistAppsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)

        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                          WindowManager.LayoutParams.MATCH_PARENT)

        mLayoutManager = LinearLayoutManager(context)

        b.recyclerViewDialog.layoutManager = mLayoutManager
        b.recyclerViewDialog.adapter = adapter

        b.customDialogOkButton.setOnClickListener(this)
        b.customDialogWhitelistSearchView.setOnQueryTextListener(this)
        b.customDialogWhitelistSearchView.setOnSearchClickListener(this)
        filterCategories.clear()

        b.customDialogWhitelistSearchView.setOnCloseListener {
            toggleCategoryChipsUi()
            false
        }

        FirewallManager.getApplistObserver().observe(activity as LifecycleOwner) {
            val blockedCount = it.filter { a -> a.firewallStatus == FirewallManager.FirewallStatus.WHITELIST.id }.count()
            b.customSelectAllOptionCount.text = context.getString(
                R.string.whitelist_dialog_apps_in_use, blockedCount.toString())
        }

        b.customSelectAllOptionCheckbox.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            //  FirewallManager.updateWhitelistedAppsByCategories(filterCategories, b)
            if (b) {
                Utilities.showToastUiCentered(context,
                                              context.getString(R.string.whitelist_toast_positive),
                                              Toast.LENGTH_SHORT)
            } else {
                Utilities.showToastUiCentered(context,
                                              context.getString(R.string.whitelist_toast_negative),
                                              Toast.LENGTH_SHORT)
            }
        }
        b.customDialogWhitelistSearchFilter.setOnClickListener(this)

        categoryListByAppNameFromDB("")
    }


    private fun categoryListByAppNameFromDB(name: String) {
        category = FirewallManager.getCategoryListByAppName(name)
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
                toggleCategoryChipsUi()
            }
            R.id.custom_dialog_whitelist_search_view -> {
                toggleCategoryChipsUi()
            }
            else -> {
                filterCategories.clear()
                viewModel.setFilter("")
                dismiss()
            }
        }

    }

    private fun toggleCategoryChipsUi() {
        if (!b.customDialogChipGroup.isVisible) {
            b.customDialogChipGroup.visibility = View.VISIBLE
        } else {
            b.customDialogChipGroup.visibility = View.GONE
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        categoryListByAppNameFromDB(query)
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        categoryListByAppNameFromDB(query)
        viewModel.setFilter(query)
        return true
    }

    private fun setCategoryChips(categories: List<String>) {
        b.customDialogChipGroup.removeAllViews()
        for (category in categories) {
            val mChip = this.layoutInflater.inflate(R.layout.item_chip_category, null,
                                                    false) as Chip
            mChip.text = category

            mChip.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                val categoryName = compoundButton.text.toString()
                if (b) {
                    filterCategories.add(categoryName)
                } else {
                    filterCategories.remove(categoryName)
                }

                var filterString = ""
                filterCategories.forEach {
                    filterString = "$it,$filterString"
                }

                filterString.dropLast(1)

                if (filterString.isNotEmpty()) {
                    viewModel.setFilter("$CATEGORY_FILTER_CONST$filterString")
                } else {
                    viewModel.setFilter("")
                }
            }
            b.customDialogChipGroup.addView(mChip)
        }
    }
}
