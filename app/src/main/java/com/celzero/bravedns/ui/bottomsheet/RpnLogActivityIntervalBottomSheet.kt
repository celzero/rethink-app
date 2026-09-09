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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTrackerRepository
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
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

import com.celzero.bravedns.ui.custom.EmbeddedDolphinContent

/**
 * RPN-scoped variant of [LogActivityIntervalBottomSheet]: identical list
 * interaction (per-app summaries, lazy expansion), but every query is
 * restricted to connection logs routed through RPN proxies (proxyDetails
 * prefixed with [Backend.RpnWin]). Opened when the user taps a cell of the
 * RPN heat map in ServerSelectionFragment; unlike the home-screen sheet it
 * has no range toggle and no allowed/blocked summary cards — it always shows
 * the exact tapped 10-minute interval.
 */
class RpnLogActivityIntervalBottomSheet : BaseBottomSheetDialogFragment() {

    private var _binding: BottomSheetLogActivityIntervalBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()

    private lateinit var adapter: AppActivityAdapter

    // built at open time; the sheet starts from the caller-selected window
    // (the tapped heat-map cell) when given, else from the latest ten-minute
    // window
    private var currentWindow: LogActivityWindow =
        LogActivityWindow.fromPreset(0, System.currentTimeMillis())

    // bumped on every preset change; results of a superseded query must never
    // render (same guard as LogActivityIntervalBottomSheet)
    private var requestGeneration = 0L

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val TAG = "RpnLAIBtmSht"

        // SQL LIKE pattern matching every RPN WIN proxy id stored in
        // proxyDetails (mirrors the RPN heat map filter in
        // ServerSelectionFragment and RpnStatsBottomSheet's scoping)
        private val RPN_PROXY_FILTER = Backend.RpnWin + "%"

        // args carrying a caller-selected window (the tapped heat-map cell)
        private const val ARG_WINDOW_START_MS = "argWindowStartMs"
        private const val ARG_WINDOW_END_MS = "argWindowEndMs"

        // display caps; window totals above stay exact
        private const val MAX_APP_GROUPS = 25
        private const val RANGE_LABEL_TEMPLATE = "dd MMM, HH:mm"

        fun newInstance(): RpnLogActivityIntervalBottomSheet =
            RpnLogActivityIntervalBottomSheet()

        /**
         * Opens the sheet on the exact [startMs, endMs) window (the 10-minute
         * heat-map cell tapped in ServerSelectionFragment).
         */
        fun newInstance(startMs: Long, endMs: Long): RpnLogActivityIntervalBottomSheet =
            RpnLogActivityIntervalBottomSheet().apply {
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

        // start from the caller-selected window (the tapped heat-map cell)
        // when one was provided; otherwise the latest ten-minute window
        val args = arguments?.takeIf { it.containsKey(ARG_WINDOW_START_MS) }
        if (args != null) {
            val start = args.getLong(ARG_WINDOW_START_MS)
            val end = args.getLong(ARG_WINDOW_END_MS, start + LogActivityWindow.TEN_MINUTES_MS)
            currentWindow = LogActivityWindow(start, end)
        } else {
            currentWindow = LogActivityWindow.fromPreset(0, System.currentTimeMillis())
        }

        // opened from ServerSelectionFragment's heat map: the range toggle and
        // the allowed/blocked summary cards are home-screen-only chrome; the
        // sheet always shows the exact tapped 10-minute interval
        b.bsLaiRangeCard.isVisible = false
        b.bsLaiCountsRow.isVisible = false

        adapter = AppActivityAdapter { summary ->
            viewLifecycleOwner.lifecycleScope.launch { onAppExpandRequested(summary) }
        }
        b.bsLaiRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.bsLaiRecycler.adapter = adapter
        setDolphinSignature()

        load(currentWindow)
    }

    /** Dolphin signature (at the end of the sheet content.); random pairing, fresh on every visit. */
    private fun setDolphinSignature() {
        b.dolphinSignature.setContent(EmbeddedDolphinContent.random())
    }

    private fun load(window: LogActivityWindow) {
        showTimeRange(window)
        val gen = ++requestGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    SheetData(
                        counts = connectionTrackerRepository.getRpnWindowCounts(
                            RPN_PROXY_FILTER,
                            window.startMs,
                            window.endMs
                        ),
                        apps = connectionTrackerRepository.getRpnAppActivity(
                            RPN_PROXY_FILTER,
                            window.startMs,
                            window.endMs,
                            MAX_APP_GROUPS
                        ).map {
                            AppActivitySummary(
                                it.uid,
                                it.appName,
                                it.total,
                                it.total - it.blocked,
                                it.blocked
                            )
                        }
                    )
                } catch (_: Exception) {
                    SheetData(WindowCountRow(0L, 0L), emptyList())
                }
            }
            // a preset change while this query was pending superseded it
            if (gen != requestGeneration) return@launch
            render(window, result)
        }
    }

    private suspend fun onAppExpandRequested(summary: AppActivitySummary) {
        // expand results belong to the window that was current when the
        // request was made; discard them when the preset changed meanwhile
        val gen = requestGeneration
        val w = currentWindow
        val entries = withContext(Dispatchers.IO) {
            try {
                connectionTrackerRepository.getRpnConnectionsInWindowForUid(
                    RPN_PROXY_FILTER,
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
                }.sortedByDescending { it.timestampMs }
                    .take(AppActivityAdapter.MAX_CHILD_ROWS)
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (gen != requestGeneration) return
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
