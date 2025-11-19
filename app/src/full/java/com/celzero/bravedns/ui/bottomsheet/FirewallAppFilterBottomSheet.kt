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

import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetFirewallSortFilterBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class FirewallAppFilterBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetFirewallSortFilterBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private val filters = AppListActivity.Filters()

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFirewallSortFilterBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        initView()
        initClickListeners()
    }

    private fun initView() {
        val f = AppListActivity.filters.value

        remakeParentFilterChipsUi()
        if (f == null) {
            applyParentFilter(AppListActivity.TopLevelFilter.ALL.id)
            return
        } else {
            this.filters.firewallFilter = f.firewallFilter
            this.filters.categoryFilters.addAll(f.categoryFilters)
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
            AppListActivity.filters.postValue(new)
            this.dismiss()
        }
    }

    private fun setFilter(
        topLevelFilter: AppListActivity.TopLevelFilter,
        categories: MutableSet<String>
    ) {
        val topView: Chip = b.ffaParentChipGroup.findViewWithTag(topLevelFilter.id) ?: return
        b.ffaParentChipGroup.check(topView.id)
        colorUpChipIcon(topView)

        categories.forEach {
            val childCategory: Chip = b.ffaChipGroup.findViewWithTag(it) ?: return
            b.ffaChipGroup.check(childCategory.id)
        }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun remakeParentFilterChipsUi() {
        b.ffaParentChipGroup.removeAllViews()

        val all =
            makeParentChip(AppListActivity.TopLevelFilter.ALL.id, getString(R.string.lbl_all), true)
        val allowed =
            makeParentChip(
                AppListActivity.TopLevelFilter.INSTALLED.id,
                getString(R.string.fapps_filter_parent_installed),
                false
            )
        val blocked =
            makeParentChip(
                AppListActivity.TopLevelFilter.SYSTEM.id,
                getString(R.string.fapps_filter_parent_system),
                false
            )

        b.ffaParentChipGroup.addView(all)
        b.ffaParentChipGroup.addView(allowed)
        b.ffaParentChipGroup.addView(blocked)
    }

    private fun makeParentChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applyParentFilter(button.tag)
                colorUpChipIcon(chip)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter =
            PorterDuffColorFilter(
                ContextCompat.getColor(requireContext(), R.color.primaryText),
                PorterDuff.Mode.SRC_IN
            )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
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
        b.ffaChipGroup.removeAllViews()
        for (c in categories) {
            if (filters.categoryFilters.contains(c)) {
                // if the category is already selected, check the chip
                b.ffaChipGroup.addView(makeChildChip(c, true))
            } else {
                b.ffaChipGroup.addView(makeChildChip(c, false))
            }
        }
    }

    private fun makeChildChip(title: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.text = title
        chip.tag = title
        chip.isChecked = checked
        if (checked) colorUpChipIcon(chip)

        chip.setOnCheckedChangeListener { compoundButton: CompoundButton, isSelected: Boolean ->
            applyChildFilter(compoundButton.tag, isSelected)
            colorUpChipIcon(chip)
        }
        return chip
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
        withContext(Dispatchers.Main) { f() }
    }
}
