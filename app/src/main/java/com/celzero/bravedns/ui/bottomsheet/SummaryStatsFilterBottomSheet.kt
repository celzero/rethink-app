/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.bottomsheet

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.statistics.SummaryFilterSelection
import com.celzero.bravedns.ui.compose.statistics.SummaryFilterSheetContent
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

class SummaryStatsFilterBottomSheet : BaseBottomSheetDialogFragment() {
    private val persistentState by inject<PersistentState>()
    private var selection: SummaryFilterSelection? = null
    enum class SectionFilter(val tid: Int, @param:IdRes val chipId: Int) {
        APPS(0, R.id.fss_filter_apps_chip),
        COUNTRIES(1, R.id.fss_filter_countries_chip),
        PROVIDERS(2, R.id.fss_filter_providers_chip),
        BLOCKLISTS(3, R.id.fss_filter_blocklist_chip),
        DOMAINS(4, R.id.fss_filter_domains_chip),
        IPS(5, R.id.fss_filter_ips_chip);
    }


    override fun getTheme(): Int = Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val model = SummaryFilterSelection(filters.value ?: SectionFilter.entries.toSet()) { filters.value = it }
        selection = model
        val providers = persistentState.downloadIpInfo
        return ComposeView(inflater.context).apply {
            id = R.id.summary_filter_compose
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { SummaryFilterSheetContent(model, providers) { dismiss() } } }
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { Themes.applyBottomSheetSystemBarAppearance(it, isDarkThemeOn(), persistentState.theme) }
    }
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.getIntArray(SAVED_CHECKED)?.let { ids ->
            selection?.restore(SectionFilter.entries.filter { it.tid in ids }.toSet())
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        selection?.let { outState.putIntArray(SAVED_CHECKED, it.selected.map { entry -> entry.tid }.toIntArray()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() { selection = null; super.onDestroyView() }
    private fun isDarkThemeOn() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    companion object {
        private const val SAVED_CHECKED = "statistics.filter.checked.children"
        val filters = MutableLiveData<Set<SectionFilter>>()
    }
}
