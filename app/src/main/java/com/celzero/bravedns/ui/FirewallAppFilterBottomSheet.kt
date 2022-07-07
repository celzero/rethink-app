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
import com.celzero.bravedns.automaton.FirewallManager
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
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private val sortValues = FirewallAppFragment.Filters()

    override fun getTheme(): Int = Themes.getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                                     persistentState.theme)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetFirewallSortFilterBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        remakeParentFilterChipsUi()
        remakeFirewallChipsUi()

        val filters = FirewallAppFragment.filters.value
        if (filters == null) {
            applyParentFilter(FirewallAppFragment.TopLevelFilter.ALL.id)
            return
        }

        applyParentFilter(filters.topLevelFilter.id)
        setFilter(filters.topLevelFilter, filters.categoryFilters)
        setFirewallFilter(filters.firewallFilter)
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

    private fun setFilter(topLevelFilter: FirewallAppFragment.TopLevelFilter,
                          categories: MutableSet<String>) {
        val topView: Chip = b.ffaParentChipGroup.findViewWithTag(topLevelFilter.id)
        b.ffaParentChipGroup.check(topView.id)
        colorUpChipIcon(topView)

        categories.forEach {
            val childCategory: Chip = b.ffaChipGroup.findViewWithTag(it)
            b.ffaChipGroup.check(childCategory.id)
        }
    }

    private fun setFirewallFilter(sortType: FirewallAppFragment.FirewallFilter) {
        val view: Chip = b.ffaFirewallChipGroup.findViewWithTag(sortType.id)
        b.ffaFirewallChipGroup.check(view.id)
        colorUpChipIcon(view)
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun remakeParentFilterChipsUi() {
        b.ffaParentChipGroup.removeAllViews()

        val all = makeParentChip(FirewallAppFragment.TopLevelFilter.ALL.id,
                                 getString(R.string.fapps_filter_parent_all), true)
        val allowed = makeParentChip(FirewallAppFragment.TopLevelFilter.INSTALLED.id,
                                     getString(R.string.fapps_filter_parent_installed), false)
        val blocked = makeParentChip(FirewallAppFragment.TopLevelFilter.SYSTEM.id,
                                     getString(R.string.fapps_filter_parent_system), false)

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

    private fun remakeFirewallChipsUi() {
        b.ffaFirewallChipGroup.removeAllViews()

        val none = makeFirewallChip(FirewallAppFragment.SortFilter.NONE.id,
                                    getString(R.string.fapps_firewall_filter_none), true)
        val allowed = makeFirewallChip(FirewallAppFragment.FirewallFilter.ALLOWED.id,
                                       getString(R.string.fapps_firewall_filter_allowed), false)
        val blocked = makeFirewallChip(FirewallAppFragment.SortFilter.BLOCKED.id,
                                       getString(R.string.fapps_firewall_filter_blocked), false)
        val whitelisted = makeFirewallChip(FirewallAppFragment.SortFilter.WHITELISTED.id,
                                           getString(R.string.fapps_firewall_filter_whitelisted),
                                           false)
        val excluded = makeFirewallChip(FirewallAppFragment.SortFilter.EXCLUDED.id,
                                        getString(R.string.fapps_firewall_filter_excluded), false)

        b.ffaFirewallChipGroup.addView(none)
        b.ffaFirewallChipGroup.addView(allowed)
        b.ffaFirewallChipGroup.addView(blocked)
        b.ffaFirewallChipGroup.addView(whitelisted)
        b.ffaFirewallChipGroup.addView(excluded)
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter = PorterDuffColorFilter(
            ContextCompat.getColor(requireContext(), R.color.primaryText), PorterDuff.Mode.SRC_IN)
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun makeFirewallChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applyFirewallFilter(button.tag)
                colorUpChipIcon(chip)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun applyParentFilter(tag: Any) {
        when (tag) {
            FirewallAppFragment.TopLevelFilter.ALL.id -> {
                sortValues.topLevelFilter = FirewallAppFragment.TopLevelFilter.ALL
                remakeChildFilterChipsUi(FirewallManager.getAllCategories())
            }
            FirewallAppFragment.TopLevelFilter.INSTALLED.id -> {
                sortValues.topLevelFilter = FirewallAppFragment.TopLevelFilter.INSTALLED
                remakeChildFilterChipsUi(FirewallManager.getCategoriesForInstalledApps())
            }
            FirewallAppFragment.TopLevelFilter.SYSTEM.id -> {
                sortValues.topLevelFilter = FirewallAppFragment.TopLevelFilter.SYSTEM
                remakeChildFilterChipsUi(FirewallManager.getCategoriesForSystemApps())
            }
        }
    }

    private fun setFirewallFilter() {

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

    private fun applyFirewallFilter(tag: Any) {
        sortValues.firewallFilter = FirewallAppFragment.FirewallFilter.filter(tag as Int)
    }
}
