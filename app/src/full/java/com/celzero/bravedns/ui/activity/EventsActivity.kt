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

import Logger
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.EventsAdapter
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivityEventsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.formatToRelativeTime
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.util.restoreFrost
import com.celzero.bravedns.viewmodel.EventsViewModel
import com.celzero.bravedns.viewmodel.EventsViewModel.TopLevelFilter
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class EventsActivity : AppCompatActivity(R.layout.activity_events), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityEventsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val viewModel: EventsViewModel by viewModel()
    private val eventDao by inject<EventDao>()

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var filterQuery: String = ""
    private var filterSources: MutableSet<EventSource> = mutableSetOf()
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
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        initView()
    }

    override fun onResume() {
        super.onResume()
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        restoreFrost(themeId)

        // Fix for keyboard issues
        b.eventsSearch.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(b.eventsSearch)
        b.eventsListRl.requestFocus()
    }

    private fun initView() {
        setupSearchView()
        setupRecyclerView()
        setupClickListeners()
        remakeSeverityFilterChipsUi()
        setQueryFilter()
    }

    private fun setupSearchView() {
        b.eventsSearch.setOnQueryTextListener(this)

        b.eventsSearch.setOnClickListener {
            showSeverityChipsUi()
            showSourceChipsIfNeeded()
            b.eventsSearch.requestFocus()
            b.eventsSearch.onActionViewExpanded()
        }
    }

    private fun setupClickListeners() {
        b.eventsFilterIcon.setOnClickListener { toggleSeverityChipsUi() }
        b.eventsRefreshIcon.setOnClickListener { refreshEvents() }
        b.eventsDeleteIcon.setOnClickListener { showDeleteDialog() }
    }

    private fun setupRecyclerView() {
        b.eventsRecyclerView.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this)
        b.eventsRecyclerView.layoutManager = layoutManager

        val recyclerAdapter = EventsAdapter(this)
        recyclerAdapter.stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

        b.eventsRecyclerView.adapter = recyclerAdapter

        viewModel.eventsList.observe(this) { pagingData ->
            recyclerAdapter.submitData(lifecycle, pagingData)
        }

        recyclerAdapter.addLoadStateListener { loadState ->
            val isEmpty = recyclerAdapter.itemCount < 1
            if (loadState.append.endOfPaginationReached && isEmpty) {
                b.emptyStateContainer.visibility = View.VISIBLE
                b.eventsRecyclerView.visibility = View.GONE
            } else {
                b.emptyStateContainer.visibility = View.GONE
                if (!b.eventsRecyclerView.isVisible) b.eventsRecyclerView.visibility = View.VISIBLE
            }
        }

        b.eventsRecyclerView.post {
            try {
                if (recyclerAdapter.itemCount > 0) {
                    recyclerAdapter.stateRestorationPolicy =
                        RecyclerView.Adapter.StateRestorationPolicy.ALLOW
                }
            } catch (_: Exception) {
                Logger.e(Logger.LOG_TAG_UI, "$TAG; err in setting recycler restoration policy")
            }
        }

        setupRecyclerScrollListener()
    }

    private fun setupRecyclerScrollListener() {
        val scrollListener =
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val firstChild = recyclerView.getChildAt(0)
                    if (firstChild == null) {
                        Logger.v(Logger.LOG_TAG_UI, "$TAG; err; no child views found")
                        return
                    }

                    val tag = firstChild.tag as? Long
                    if (tag == null) {
                        Logger.v(Logger.LOG_TAG_UI, "$TAG; err; tag is null for first child")
                        return
                    }

                    b.eventsListScrollHeader.text = formatToRelativeTime(this@EventsActivity, tag)
                    b.eventsListScrollHeader.visibility = View.VISIBLE
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        b.eventsListScrollHeader.visibility = View.GONE
                    }
                }
            }
        b.eventsRecyclerView.addOnScrollListener(scrollListener)
    }

    private fun toggleSeverityChipsUi() {
        if (b.filterChipSeverityGroup.isVisible) {
            hideSeverityChipsUi()
            hideSourceChipsUi()
        } else {
            showSeverityChipsUi()
            showSourceChipsIfNeeded()
        }
    }

    private fun showSeverityChipsUi() {
        b.filterChipSeverityGroup.visibility = View.VISIBLE
    }

    private fun hideSeverityChipsUi() {
        b.filterChipSeverityGroup.visibility = View.GONE
    }

    private fun showSourceChipsUi() {
        b.filterChipSourceGroup.visibility = View.VISIBLE
    }

    private fun hideSourceChipsUi() {
        b.filterChipSourceGroup.visibility = View.GONE
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

    private fun remakeSeverityFilterChipsUi() {
        b.filterChipSeverityGroup.removeAllViews()

        val all = makeSeverityChip(CHIP_TAG_ALL, getString(R.string.lbl_all), true)
        val low = makeSeverityChip(CHIP_TAG_LOW, CHIP_TAG_LOW, false)
        val medium = makeSeverityChip(CHIP_TAG_MEDIUM, CHIP_TAG_MEDIUM, false)
        val high = makeSeverityChip(CHIP_TAG_HIGH, CHIP_TAG_HIGH, false)
        val critical = makeSeverityChip(CHIP_TAG_CRITICAL, CHIP_TAG_CRITICAL, false)

        b.filterChipSeverityGroup.addView(all)
        b.filterChipSeverityGroup.addView(low)
        b.filterChipSeverityGroup.addView(medium)
        b.filterChipSeverityGroup.addView(high)
        b.filterChipSeverityGroup.addView(critical)
    }

    private fun makeSeverityChip(tag: String, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = tag
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applySeverityFilter(button.tag)
            } else {
                unselectSeverityChipsUi(button.tag)
            }
        }

        return chip
    }

    private fun makeSourceChip(source: EventSource): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.text = source.name
        chip.isCheckedIconVisible = false
        chip.tag = source

        chip.setOnCheckedChangeListener { compoundButton: CompoundButton, isSelected: Boolean ->
            applySourceFilter(compoundButton.tag as EventSource, isSelected)
        }
        return chip
    }

    private fun applySeverityFilter(tag: Any) {
        val tagString = tag as String
        if (tagString == CHIP_TAG_ALL) {
            filterSeverity = null
            filterSources.clear()
            filterType = TopLevelFilter.ALL
            viewModel.setFilter(filterQuery, emptySet(), null)
            hideSourceChipsUi()
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

    private fun unselectSeverityChipsUi(tag: Any) {
        val chipCount = b.filterChipSeverityGroup.childCount
        val tagString = tag as String
        for (i in 0 until chipCount) {
            val chip = b.filterChipSeverityGroup.getChildAt(i) as Chip
            if (chip.tag != tagString && chip.tag == CHIP_TAG_ALL) {
                chip.isChecked = true
                return
            }
        }
    }

    private fun remakeSourceFilterChipsUi() {
        b.filterChipSourceGroup.removeAllViews()

        EventSource.entries.forEach { source ->
            val chip = makeSourceChip(source)
            if (filterSources.contains(source)) {
                chip.isChecked = true
            }
            b.filterChipSourceGroup.addView(chip)
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
                .debounce(QUERY_TEXT_DELAY)
                .distinctUntilChanged()
                .collect { query ->
                    filterQuery = query
                    viewModel.setFilter(query, filterSources, filterSeverity)
                }
        }
    }

    val searchQuery = MutableStateFlow("")

    override fun onQueryTextSubmit(query: String): Boolean {
        searchQuery.value = query
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        searchQuery.value = query
        return true
    }

    private fun refreshEvents() {
        viewModel.setFilter(filterQuery, filterSources, filterSeverity)
    }

    private fun showDeleteDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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
}

