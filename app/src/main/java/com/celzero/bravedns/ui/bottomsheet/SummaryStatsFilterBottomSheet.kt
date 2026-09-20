/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.bottomsheet

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetSummaryStatsFilterBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.google.android.material.chip.Chip
import org.koin.android.ext.android.inject

/**
 * Section filter picker for the summary statistics screen. Shows one filter
 * chip per stats section (apps, countries, providers, blocklists, domains,
 * ips) inside a scrollable, theme-aware bottom sheet.
 *
 * Toggles apply immediately: the checked set is posted to [filters], which
 * the host fragment observes to show/hide section groups. The selection is
 * session-only; it is seeded with all sections enabled on first use.
 */
class SummaryStatsFilterBottomSheet : BaseBottomSheetDialogFragment() {

    private var _binding: BottomSheetSummaryStatsFilterBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    /** Stats sections that can be shown/hidden, mapped to their filter chip. */
    enum class SectionFilter(val tid: Int, @IdRes val chipId: Int) {
        APPS(0, R.id.fss_filter_apps_chip),
        COUNTRIES(1, R.id.fss_filter_countries_chip),
        PROVIDERS(2, R.id.fss_filter_providers_chip),
        BLOCKLISTS(3, R.id.fss_filter_blocklist_chip),
        DOMAINS(4, R.id.fss_filter_domains_chip),
        IPS(5, R.id.fss_filter_ips_chip);
    }

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSummaryStatsFilterBinding.inflate(inflater, container, false)
        return b.root
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
        if (!persistentState.downloadIpInfo) {
            b.fssFilterProvidersChip.visibility = View.GONE
        }

        val selected = filters.value ?: SectionFilter.entries.toSet()
        SectionFilter.entries.forEach { f ->
            b.root.findViewById<Chip>(f.chipId)?.isChecked = selected.contains(f)
        }

        b.ssfcChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            filters.value =
                SectionFilter.entries.filter { checkedIds.contains(it.chipId) }.toSet()
        }
    }

    private fun initClickListeners() {
        b.ssfcSelectAll.setOnClickListener {
            SectionFilter.entries.forEach { f -> b.ssfcChipGroup.check(f.chipId) }
        }
        b.ssfcApply.setOnClickListener { this.dismiss() }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        val filters = MutableLiveData<Set<SectionFilter>>()
    }
}
