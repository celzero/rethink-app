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
package com.celzero.bravedns.ui.bottomsheet
import com.celzero.bravedns.ui.compose.firewallapps.*
import com.celzero.bravedns.ui.compose.RethinkTheme

import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.util.Themes
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class FirewallAppFilterBottomSheet : BaseBottomSheetDialogFragment() {
    private val b = FirewallFilterState()

    private val persistentState by inject<PersistentState>()
    private val filters = AppListActivity.Filters()

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { FirewallFilterContent(b) } }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        initClickListeners()
    }

    private fun initView() {
        val f = AppListActivity.filters.value

        remakeParentFilterChipsUi()

        if (f != null) {
            this.filters.firewallFilter = f.firewallFilter
            this.filters.categoryFilters.clear()
            this.filters.categoryFilters.addAll(f.categoryFilters)
            this.filters.sort = f.sort
            this.filters.searchString = f.searchString
        }

        remakeSortChipsUi()

        if (f == null) {
            applyParentFilter(AppListActivity.TopLevelFilter.ALL.id)
            return
        }

        applyParentFilter(f.topLevelFilter.id)
        setFilter(f.topLevelFilter, f.categoryFilters)
    }

    private fun initClickListeners() {
        b.fsApply.setOnClickListener {
            AppListActivity.filters.postValue(filters)
            this.dismiss()
        }

        b.fsClear.setOnClickListener {
            val new = AppListActivity.filters.value
            if (new == null) {
                this.dismiss()
                return@setOnClickListener
            }
            new.categoryFilters.clear()
            new.topLevelFilter = AppListActivity.TopLevelFilter.ALL
            new.sort = AppListActivity.SortOption.NAME
            AppListActivity.filters.postValue(new)
            this.dismiss()
        }
    }

    private fun setFilter(topLevelFilter: AppListActivity.TopLevelFilter, categories: MutableSet<String>) {
        b.selectParent(topLevelFilter.id)
        categories.forEach { if (it !in b.categories) return; b.selectCategory(it, true) }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }



    private fun remakeParentFilterChipsUi() {
        b.parent = AppListActivity.TopLevelFilter.ALL.id
        b.onParent = { applyParentFilter(it) }
        b.onCategory = { tag, show -> applyChildFilter(tag, show) }
    }



    private fun remakeSortChipsUi() {
        b.sort = filters.sort
        b.onSort = { filters.sort = it }
    }





    private fun applyParentFilter(tag: Any) {
        when (tag) {
            AppListActivity.TopLevelFilter.ALL.id -> {
                filters.topLevelFilter = AppListActivity.TopLevelFilter.ALL
                io {
                    val categories = FirewallManager.getAllCategories()
                    uiCtx { remakeChildFilterChipsUi(categories) }
                }
            }
            AppListActivity.TopLevelFilter.INSTALLED.id -> {
                filters.topLevelFilter = AppListActivity.TopLevelFilter.INSTALLED
                io {
                    val categories = FirewallManager.getCategoriesForInstalledApps()
                    uiCtx { remakeChildFilterChipsUi(categories) }
                }
            }
            AppListActivity.TopLevelFilter.SYSTEM.id -> {
                filters.topLevelFilter = AppListActivity.TopLevelFilter.SYSTEM
                io {
                    val categories = FirewallManager.getCategoriesForSystemApps()
                    uiCtx { remakeChildFilterChipsUi(categories) }
                }
            }
        }
    }

    private fun remakeChildFilterChipsUi(categories: List<String>) {
        b.categories = categories
        b.selectedCategories.clear()
        b.selectedCategories.addAll(categories.filter { filters.categoryFilters.contains(it) })
    }



    private fun applyChildFilter(tag: Any, show: Boolean) {
        if (show) {
            filters.categoryFilters.add(tag.toString())
        } else {
            filters.categoryFilters.remove(tag.toString())
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }
}
