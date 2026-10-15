/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity
import androidx.activity.compose.setContent
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.trafficlogs.*
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.EventsAdapter
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.formatToRelativeTime
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.util.restoreFrost
import com.celzero.bravedns.viewmodel.EventsViewModel
import com.celzero.bravedns.viewmodel.EventsViewModel.TopLevelFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.time.Duration.Companion.milliseconds

class EventsActivity : BaseActivity() {
    private val state = EventsState()
    private lateinit var presenter: EventsAdapter
    private val persistentState by inject<PersistentState>()
    private val viewModel: EventsViewModel by viewModel()
    private val eventDao by inject<EventDao>()

    private var filterQuery: String = ""
    private val filterSources: MutableSet<EventSource> = mutableSetOf()
    private var filterSeverity: Severity? = null
    private var filterType: TopLevelFilter = TopLevelFilter.ALL

    companion object {
        private const val TAG = "EventsActivity"
        private const val QUERY_TEXT_DELAY: Long = 1000

        // Severity chip tag constants
        private const val CHIP_TAG_ALL = "ALL"
        private const val CHIP_TAG_LOW = "LOW"
        private const val CHIP_TAG_MEDIUM = "MEDIUM"
        private const val CHIP_TAG_HIGH = "HIGH"
        private const val CHIP_TAG_CRITICAL = "CRITICAL"
        private const val CHIP_TAG_SOURCE = "SOURCE"
        private const val REFRESH_TIMEOUT: Long = 4000
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        initView()
        setContent { RethinkTheme { EventsScreen(state, presenter,
            { showSeverityChipsUi(); showSourceChipsIfNeeded(); state.logs.search.expand() },
            ::toggleSeverityChipsUi, ::applySeverityFilter, ::applySourceFilter, ::refreshEvents, ::showDeleteDialog) } }
    }



    override fun onResume() {
        super.onResume()
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        restoreFrost(themeId)

        // Fix for keyboard issues
        state.logs.resumeGeneration++
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("traffic.search", state.logs.search.query) }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) { super.onRestoreInstanceState(savedInstanceState); savedInstanceState.getString("traffic.search")?.let { state.logs.search.setQuery(it) } }

    private fun initView() {
        state.logs.search.listener = { onQueryTextChange(it) }
        state.logs.search.submitListener = { onQueryTextSubmit(it) }
        presenter = EventsAdapter(this)
        viewModel.eventsList.observe(this) { presenter.submitData(lifecycle, it) }
        presenter.addLoadStateListener { state.logs.showList = !(it.append.endOfPaginationReached && presenter.itemCount < 1) }
        setQueryFilter()
    }









    private fun toggleSeverityChipsUi() {
        if (state.logs.showParentFilters) {
            hideSeverityChipsUi()
            hideSourceChipsUi()
        } else {
            showSeverityChipsUi()
            showSourceChipsIfNeeded()
        }
    }

    private fun showSeverityChipsUi() {
        state.logs.showParentFilters = true
    }

    private fun hideSeverityChipsUi() {
        state.logs.showParentFilters = false
    }

    private fun showSourceChipsUi() {
        state.logs.showChildFilters = true
    }

    private fun hideSourceChipsUi() {
        state.logs.showChildFilters = false
    }

    private fun showSourceChipsIfNeeded() {
        when (filterType) {
            TopLevelFilter.ALL -> {
                hideSourceChipsUi()
            }
            TopLevelFilter.SEVERITY -> {
                hideSourceChipsUi()
            }
            TopLevelFilter.SOURCE -> {
                showSourceChipsUi()
            }
        }
    }







    private fun applySeverityFilter(tag: Any) {
        val tagString = tag as String
        if (tagString == CHIP_TAG_ALL) {
            filterSeverity = null
            filterSources.clear()
            filterType = TopLevelFilter.ALL
            viewModel.setFilter(filterQuery, emptySet(), null)
            hideSourceChipsUi()
        } else if (tagString == CHIP_TAG_SOURCE) {
            filterSeverity = null
            filterType = TopLevelFilter.SOURCE
            showSourceChipsUi()
            // Don't clear sources or update filter yet - let user select sources first
            if (filterSources.isEmpty()) {
                viewModel.setFilter(filterQuery, emptySet(), null)
            } else {
                viewModel.setFilter(filterQuery, filterSources, null)
            }
        } else {
            filterSeverity = when (tagString) {
                CHIP_TAG_LOW -> Severity.LOW
                CHIP_TAG_MEDIUM -> Severity.MEDIUM
                CHIP_TAG_HIGH -> Severity.HIGH
                CHIP_TAG_CRITICAL -> Severity.CRITICAL
                else -> null
            }
            filterSources.clear()
            filterType = TopLevelFilter.SEVERITY
            viewModel.setFilter(filterQuery, emptySet(), filterSeverity)
            hideSourceChipsUi()
        }
    }





    private fun applySourceFilter(source: EventSource, isSelected: Boolean) {
        if (isSelected) {
            filterSources.add(source)
        } else {
            filterSources.remove(source)
        }

        if (filterSources.isEmpty()) {
            filterType = TopLevelFilter.ALL
            filterSeverity = null
            viewModel.setFilter(filterQuery, emptySet(), null)
        } else {
            filterType = TopLevelFilter.SOURCE
            filterSeverity = null
            viewModel.setFilter(filterQuery, filterSources, null)
        }
    }

    @OptIn(FlowPreview::class)
    private fun setQueryFilter() {
        lifecycleScope.launch {
            searchQuery
                .debounce(QUERY_TEXT_DELAY.milliseconds)
                .distinctUntilChanged()
                .collect { query ->
                    filterQuery = query
                    viewModel.setFilter(query, filterSources, filterSeverity)
                }
        }
    }

    val searchQuery = MutableStateFlow("")

    fun onQueryTextSubmit(query: String): Boolean {
        searchQuery.value = query
        return true
    }

    fun onQueryTextChange(query: String): Boolean {
        searchQuery.value = query
        return true
    }
    private fun refreshEvents() {
        ui {
            state.refreshing = true
            viewModel.setFilter(filterQuery, filterSources, filterSeverity)
            delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (isFinishing) return@delay

                state.refreshing = false
                showToastUiCentered(this, getString(R.string.dc_refresh_toast), Toast.LENGTH_SHORT)
            }
        }
    }

    private fun showDeleteDialog() {
        WgAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.delete_all_events)
            .setMessage(R.string.delete_all_events_confirmation)
            .setCancelable(true)
            .setPositiveButton(getString(R.string.lbl_delete)) { _, _ ->
                io { eventDao.deleteAll() }
                refreshEvents()
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
            .create()
            .show()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        if (isFinishing || isDestroyed) return

        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}

