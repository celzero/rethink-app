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

import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.databinding.BottomSheetFirewallSortFilterBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import org.koin.android.ext.android.inject

class FirewallAppFilterBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetFirewallSortFilterBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private val sortValues = FirewallAppFragment.Filters()

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        val filters = FirewallAppFragment.filters.value

        remakeParentFilterChipsUi()
        if (filters == null) {
            applyParentFilter(FirewallAppFragment.TopLevelFilter.ALL.id)
            return
        } else {
            sortValues.firewallFilter = filters.firewallFilter
        }

        applyParentFilter(filters.topLevelFilter.id)
        setFilter(filters.topLevelFilter, filters.categoryFilters)
    }

    private fun initClickListeners() {
        b.fsApply.setOnClickListener {
            FirewallAppFragment.filters.postValue(sortValues)
            this.dismiss()
        }

        b.fsClear.setOnClickListener {
            FirewallAppFragment.filters.postValue(FirewallAppFragment.Filters())
            this.dismiss()
        }
    }

    private fun setFilter(
        topLevelFilter: FirewallAppFragment.TopLevelFilter,
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
            makeParentChip(
                FirewallAppFragment.TopLevelFilter.ALL.id,
                getString(R.string.fapps_filter_parent_all),
                true
            )
        val allowed =
            makeParentChip(
                FirewallAppFragment.TopLevelFilter.INSTALLED.id,
                getString(R.string.fapps_filter_parent_installed),
                false
            )
        val blocked =
            makeParentChip(
                FirewallAppFragment.TopLevelFilter.SYSTEM.id,
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
            FirewallAppFragment.TopLevelFilter.ALL.id -> {
                sortValues.topLevelFilter = FirewallAppFragment.TopLevelFilter.ALL
                sortValues.categoryFilters.clear()
                remakeChildFilterChipsUi(FirewallManager.getAllCategories())
            }
            FirewallAppFragment.TopLevelFilter.INSTALLED.id -> {
                sortValues.topLevelFilter = FirewallAppFragment.TopLevelFilter.INSTALLED
                sortValues.categoryFilters.clear()
                remakeChildFilterChipsUi(FirewallManager.getCategoriesForInstalledApps())
            }
            FirewallAppFragment.TopLevelFilter.SYSTEM.id -> {
                sortValues.topLevelFilter = FirewallAppFragment.TopLevelFilter.SYSTEM
                sortValues.categoryFilters.clear()
                remakeChildFilterChipsUi(FirewallManager.getCategoriesForSystemApps())
            }
        }
    }

    private fun remakeChildFilterChipsUi(categories: List<String>) {
        b.ffaChipGroup.removeAllViews()
        for (c in categories) {
            b.ffaChipGroup.addView(makeChildChip(c))
        }
    }

    private fun makeChildChip(title: String): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.text = title
        chip.tag = title

        chip.setOnCheckedChangeListener { compoundButton: CompoundButton, isSelected: Boolean ->
            applyChildFilter(compoundButton.tag, isSelected)
            colorUpChipIcon(chip)
        }
        return chip
    }

    private fun applyChildFilter(tag: Any, show: Boolean) {
        if (show) {
            sortValues.categoryFilters.add(tag.toString())
        } else {
            sortValues.categoryFilters.remove(tag.toString())
        }
    }
}
