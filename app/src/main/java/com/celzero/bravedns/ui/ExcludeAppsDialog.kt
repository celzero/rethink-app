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
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CompoundButton
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.databinding.ExcludeAppDialogLayoutBinding
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import com.google.android.material.chip.Chip

class ExcludeAppsDialog(private var activity: Activity,
                        internal var adapter: RecyclerView.Adapter<*>,
                        var viewModel: ExcludedAppViewModel, themeID: Int) :
        Dialog(activity, themeID), View.OnClickListener, SearchView.OnQueryTextListener {

    private lateinit var b: ExcludeAppDialogLayoutBinding

    private var mLayoutManager: RecyclerView.LayoutManager? = null

    private var filterCategories: MutableSet<String> = mutableSetOf()
    private var category: List<String> = ArrayList()

    private var isVpnRestartRequired: Boolean = false

    private val CATEGORY_FILTER_CONST = "category:"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = ExcludeAppDialogLayoutBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)

        initializeValues()
        initializeClickListeners()
    }

    private fun initializeValues() {
        isVpnRestartRequired = false

        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                          WindowManager.LayoutParams.MATCH_PARENT)

        mLayoutManager = LinearLayoutManager(activity)

        b.excludeAppRecyclerViewDialog.layoutManager = mLayoutManager
        b.excludeAppRecyclerViewDialog.adapter = adapter

        FirewallManager.getApplistObserver().observe(activity as LifecycleOwner, {
            val excludedCount = it.filter { a -> a.isExcluded }.count()
            b.excludeAppSelectCountText.text = activity.getString(R.string.ex_dialog_count,
                                                                  excludedCount.toString())
        })

        // By default, show all the categories.
        showAllCategories()
    }

    private fun initializeClickListeners() {
        b.excludeAppDialogOkButton.setOnClickListener(this)

        b.excludeAppDialogWhitelistSearchView.setOnQueryTextListener(this)
        b.excludeAppDialogWhitelistSearchView.setOnSearchClickListener(this)

        b.excludeAppDialogWhitelistSearchView.setOnCloseListener {
            toggleCategoryChipsUi()
            false
        }

        b.excludeAppSelectAllOptionCheckbox.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            FirewallManager.updateExcludedAppsByCategories(filterCategories, b)
        }

        b.excludeAppDialogWhitelistSearchFilter.setOnClickListener(this)
    }

    private fun showAllCategories() {
        setupCategoryChips("")
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.exclude_app_dialog_ok_button -> {
                clearSearch()
                dismiss()
            }
            R.id.exclude_app_dialog_whitelist_search_filter -> {
                toggleCategoryChipsUi()
            }
            R.id.exclude_app_dialog_whitelist_search_view -> {
                toggleCategoryChipsUi()
            }
            else -> {
                clearSearch()
                dismiss()
            }
        }
    }

    private fun clearSearch() {
        filterCategories.clear()
        viewModel.setFilter("")
    }

    private fun toggleCategoryChipsUi() {
        if (!b.excludeAppDialogChipGroup.isVisible) {
            b.excludeAppDialogChipGroup.visibility = View.VISIBLE
        } else {
            b.excludeAppDialogChipGroup.visibility = View.GONE
        }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        setupCategoryChips(query)
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        setupCategoryChips(query)
        viewModel.setFilter(query)
        return true
    }

    private fun setupCategoryChips(name: String) {
        val categories = FirewallManager.getCategoryListByAppName(name)
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Category: ${category.size}")

        b.excludeAppDialogChipGroup.removeAllViews()

        for (category in categories) {
            // Ignore non-app system category in excluded list
            if (CategoryInfoRepository.CategoryConstants.isNonApp(context, category)) continue

            val chip = makeChip(category)
            b.excludeAppDialogChipGroup.addView(chip)

            chip.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
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
                    viewModel.setFilter(filterString)
                }
            }
        }
    }

    private fun makeChip(category: String): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_category, null, false) as Chip
        chip.text = category
        return chip
    }

}
