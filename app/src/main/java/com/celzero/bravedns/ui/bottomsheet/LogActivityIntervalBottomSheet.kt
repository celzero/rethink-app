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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.database.WindowCountRow
import com.celzero.bravedns.databinding.BottomSheetLogActivityIntervalBinding
import com.celzero.bravedns.service.LogActivityWindow
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.adapter.AppActivityAdapter
import com.celzero.bravedns.ui.adapter.AppActivityEntry
import com.celzero.bravedns.ui.adapter.AppActivitySummary
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.Utilities.convertLongToTime
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Premium detail view for a selected activity window (default: last 10
 * minutes; selectable up to 24 hours at 10-minute granularity). All data is
 * queried from the dns/connection log databases for the exact window:
 * per-app summaries are grouped via SQL, connection rows load lazily when an
 * app group is expanded.
 */
class LogActivityIntervalBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLogActivityIntervalBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val dnsLogRepository by inject<DnsLogRepository>()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private val rethinkLogRepository by inject<RethinkLogRepository>()

    private lateinit var adapter: AppActivityAdapter

    // built at open time; the sheet starts from the caller-selected window
    // when given (see newInstance(startMs, endMs)), else from the latest
    // ten-minute window
    private var currentWindow: LogActivityWindow =
        LogActivityWindow.fromPreset(0, System.currentTimeMillis())
    private var selectedPresetIndex = LogActivityWindow.defaultPresetIndex()
    private var blockedOnly = false

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val TAG = "LAIBtmSht"

        // args carrying a caller-selected window (e.g. a tapped heatmap cell)
        private const val ARG_WINDOW_START_MS = "argWindowStartMs"
        private const val ARG_WINDOW_END_MS = "argWindowEndMs"

        // display caps; window totals above stay exact
        private const val MAX_APP_GROUPS = 25
        private const val RANGE_LABEL_TEMPLATE = "dd MMM, HH:mm"

        /**
         * Opens the sheet immediately; the window defaults to the latest ten
         * minutes and can be filtered via the range chips once visible.
         */
        fun newInstance(): LogActivityIntervalBottomSheet = LogActivityIntervalBottomSheet()

        /**
         * Opens the sheet on the exact [startMs, endMs) window (e.g. the
         * 10-minute cell tapped on the home-screen activity wall). The
         * default 10-minute range chip is preselected since a wall cell
         * spans one 10-minute interval.
         */
        fun newInstance(startMs: Long, endMs: Long): LogActivityIntervalBottomSheet =
            LogActivityIntervalBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_WINDOW_START_MS, startMs)
                    putLong(ARG_WINDOW_END_MS, endMs)
                }
            }
    }

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLogActivityIntervalBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        // start from the caller-selected window (a tapped wall cell) when one
        // was provided; otherwise the latest ten-minute window at open time
        val args = arguments?.takeIf { it.containsKey(ARG_WINDOW_START_MS) }
        if (args != null) {
            val start = args.getLong(ARG_WINDOW_START_MS)
            val end = args.getLong(ARG_WINDOW_END_MS, start + LogActivityWindow.TEN_MINUTES_MS)
            currentWindow = LogActivityWindow(start, end)
            // a wall cell spans one 10-minute interval == the default chip
            selectedPresetIndex = LogActivityWindow.defaultPresetIndex()
        } else {
            currentWindow = LogActivityWindow.fromPreset(0, System.currentTimeMillis())
            selectedPresetIndex = LogActivityWindow.defaultPresetIndex()
        }

        setupRangeChips()
        setupFilterChips()

        adapter = AppActivityAdapter { summary ->
            viewLifecycleOwner.lifecycleScope.launch { onAppExpandRequested(summary) }
        }
        b.bsLaiRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.bsLaiRecycler.adapter = adapter

        selectChipForPreset()
        load(currentWindow)
    }

    /**
     * Timer selection: each chip selects a historical range ending at the most
     * recent ten-minute boundary. The default selection covers the last 10
     * minutes; the widest selection covers the last 24 hours.
     */
    private fun setupRangeChips() {
        // range labels reuse the same strings as SummaryStatisticsFragment's
        // time-range toggle ("10 min", "1 hr", "24 hr")
        b.bsLaiChip10m.text = getString(R.string.ci_desc, "10", getString(R.string.lbl_min))
        b.bsLaiChip1h.text = getString(R.string.ci_desc, "1", getString(R.string.lbl_hour))
        b.bsLaiChip24h.text = getString(R.string.ci_desc, "24", getString(R.string.lbl_hour))
        val listener =
            MaterialButtonToggleGroup.OnButtonCheckedListener { _, buttonId, isChecked ->
                if (!isChecked) return@OnButtonCheckedListener
                val idx = when (buttonId) {
                    R.id.bs_lai_chip_10m -> 0
                    R.id.bs_lai_chip_1h -> 1
                    R.id.bs_lai_chip_24h -> 2
                    else -> return@OnButtonCheckedListener
                }
                if (idx == selectedPresetIndex) return@OnButtonCheckedListener
                applyPreset(idx)
            }
        b.bsLaiRangeGroup.addOnButtonCheckedListener(listener)
    }

    private fun selectChipForPreset() {
        // fires the listener, whose idx==selectedPresetIndex guard makes it a
        // harmless no-op
        b.bsLaiRangeGroup.check(
            when (selectedPresetIndex) {
                1 -> R.id.bs_lai_chip_1h
                2 -> R.id.bs_lai_chip_24h
                else -> R.id.bs_lai_chip_10m
            }
        )
    }

    /**
     * Filters the app list to surface exactly what was blocked within the
     * selected window; "All" restores the full list.
     */
    private fun setupFilterChips() {
        b.bsLaiFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val blocked = checkedIds.first() == R.id.bs_lai_filter_blocked
            if (blocked != blockedOnly) {
                blockedOnly = blocked
                adapter.setBlockedOnly(blocked)
            }
        }
        b.bsLaiFilterGroup.check(R.id.bs_lai_filter_all)
    }

    private fun applyPreset(presetIndex: Int) {
        selectedPresetIndex = presetIndex
        currentWindow = LogActivityWindow.fromPreset(presetIndex, System.currentTimeMillis())
        load(currentWindow)
    }

    private fun load(window: LogActivityWindow) {
        showTimeRange(window)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    SheetData(
                        counts = sumCounts(
                            dnsLogRepository.getWindowCounts(window.startMs, window.endMs),
                            connectionTrackerRepository.getWindowCounts(window.startMs, window.endMs),
                            rethinkLogRepository.getWindowCounts(window.startMs, window.endMs)
                        ),
                        apps = mergeApps(
                            dnsLogRepository.getAppActivity(window.startMs, window.endMs, MAX_APP_GROUPS),
                            connectionTrackerRepository.getAppActivity(
                                window.startMs,
                                window.endMs,
                                MAX_APP_GROUPS
                            ),
                            rethinkLogRepository.getAppActivity(window.startMs, window.endMs, MAX_APP_GROUPS)
                        )
                    )
                } catch (_: Exception) {
                    SheetData(WindowCountRow(0L, 0L), emptyList())
                }
            }
            render(window, result)
        }
    }

    private suspend fun onAppExpandRequested(summary: AppActivitySummary) {
        val w = currentWindow
        val entries = withContext(Dispatchers.IO) {
            try {
                (
                    dnsLogRepository.getDnsLogsInWindowForUid(w.startMs, w.endMs, summary.uid, AppActivityAdapter.MAX_CHILD_ROWS)
                        .map {
                            AppActivityEntry(
                                it.queryStr,
                                convertLongToTime(it.time, TIME_FORMAT_1),
                                it.isBlocked,
                                it.time
                            )
                        } +
                    connectionTrackerRepository.getConnectionsInWindowForUid(
                        w.startMs,
                        w.endMs,
                        summary.uid,
                        AppActivityAdapter.MAX_CHILD_ROWS
                    ).map {
                        AppActivityEntry(
                            label(it.dnsQuery, it.ipAddress),
                            convertLongToTime(it.timeStamp, TIME_FORMAT_1),
                            it.isBlocked,
                            it.timeStamp
                        )
                    } +
                    rethinkLogRepository.getRethinkLogsInWindowForUid(
                        w.startMs,
                        w.endMs,
                        summary.uid,
                        AppActivityAdapter.MAX_CHILD_ROWS
                    ).map {
                        AppActivityEntry(
                            label(it.dnsQuery, it.ipAddress),
                            convertLongToTime(it.timeStamp, TIME_FORMAT_1),
                            it.isBlocked,
                            it.timeStamp
                        )
                    }
                    ).sortedByDescending { it.timestampMs }
                        .let { if (blockedOnly) it.filter { e -> e.blocked } + it.filter { e -> !e.blocked } else it }
                        .take(AppActivityAdapter.MAX_CHILD_ROWS)
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (_binding != null && isAdded) {
            adapter.setChildren(summary.uid, entries)
        }
    }

    private fun label(primary: String?, fallback: String): String {
        return primary?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun render(window: LogActivityWindow, d: SheetData) {
        if (!isAdded || _binding == null) return

        b.bsLaiBlockedCount.text = d.counts.blocked.toString()
        b.bsLaiAllowedCount.text = (d.counts.total - d.counts.blocked).toString()

        if (d.apps.isEmpty()) {
            b.bsLaiEmpty.visibility = View.VISIBLE
            b.bsLaiRecycler.visibility = View.GONE
        } else {
            b.bsLaiEmpty.visibility = View.GONE
            b.bsLaiRecycler.visibility = View.VISIBLE
            adapter.submit(d.apps)
        }
    }

    private fun sumCounts(vararg rows: WindowCountRow): WindowCountRow {
        var blocked = 0L
        var total = 0L
        for (r in rows) {
            blocked += r.blocked
            total += r.total
        }
        return WindowCountRow(blocked, total)
    }

    // connection-tracker and rethink-log tables hold disjoint uid ranges
    // (rethink's own traffic goes to RethinkLog), so merging by uid+appName
    // cannot double count
    private fun mergeApps(
        dnsRows: List<com.celzero.bravedns.database.AppActivityRow>,
        connRows: List<com.celzero.bravedns.database.AppActivityRow>,
        rrRows: List<com.celzero.bravedns.database.AppActivityRow>
    ): List<AppActivitySummary> {
        val merged = LinkedHashMap<Pair<Int, String>, AppActivitySummary>()
        for (row in dnsRows + connRows + rrRows) {
            val key = row.uid to row.appName
            val existing = merged[key]
            merged[key] =
                if (existing == null) {
                    AppActivitySummary(
                        row.uid,
                        row.appName,
                        row.total,
                        row.total - row.blocked,
                        row.blocked
                    )
                } else {
                    AppActivitySummary(
                        existing.uid,
                        existing.appName,
                        existing.total + row.total,
                        existing.allowed + (row.total - row.blocked),
                        existing.blocked + row.blocked
                    )
                }
        }
        return merged.values.sortedByDescending { it.total }.take(MAX_APP_GROUPS)
    }

    private fun showTimeRange(window: LogActivityWindow) {
        b.bsLaiRange.text = getString(
            R.string.log_activity_interval_range,
            convertLongToTime(window.startMs, RANGE_LABEL_TEMPLATE),
            convertLongToTime(window.endMs, RANGE_LABEL_TEMPLATE)
        )
    }

    private data class SheetData(
        val counts: WindowCountRow,
        val apps: List<AppActivitySummary>
    )
}
