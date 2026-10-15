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

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.statistics.RpnStatisticsPresentation
import com.celzero.bravedns.ui.compose.statistics.RpnStatisticsContent
import com.celzero.bravedns.ui.compose.statistics.RpnStatisticsAppRow
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.RpnConnStatsSummary
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.humanReadableByteCount
import com.celzero.firestack.backend.Backend
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

import com.celzero.bravedns.ui.custom.EmbeddedDolphinContent

/**
 * Premium stats bottom sheet for Rethink Proxy Network (RPN) traffic.
 *
 * Shows, at a glance:
 * - Last-24h data usage (rx/tx) from [RpnConnStatsSummary], plus active-since +
 *   last handshake from the WIN proxy [com.celzero.firestack.backend.RouterStats].
 * - Last-24h aggregates (connections, blocked, distinct apps) and the top apps
 *   by usage.
 */
class RpnStatsBottomSheet : BaseBottomSheetDialogFragment() {

    private var _binding: RpnStatisticsPresentation? = null
    private val b
        get() = checkNotNull(_binding) { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val connectionTrackerDAO by inject<ConnectionTrackerDAO>()


    private var loadJob: Job? = null

    /** Aggregation window; callers may narrow it (e.g. the pulse card's 1h). */
    private var timeWindowMs: Long = TIME_WINDOW_MS

    companion object {
        const val TAG = "RpnStatsBtmSheet"

        private const val TIME_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val TIME_WINDOW_MS_ARG = "timeWindowMs"
        private const val TOP_APPS_LIMIT = 10

        fun newInstance(timeWindowMs: Long = TIME_WINDOW_MS): RpnStatsBottomSheet =
            RpnStatsBottomSheet().apply {
                arguments = Bundle().apply { putLong(TIME_WINDOW_MS_ARG, timeWindowMs) }
            }
    }

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
        timeWindowMs = arguments?.getLong(TIME_WINDOW_MS_ARG, TIME_WINDOW_MS) ?: TIME_WINDOW_MS
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val presentation = RpnStatisticsPresentation(inflater.context)
        _binding = presentation
        return ComposeView(inflater.context).apply {
            id = R.id.rpn_statistics_compose
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RpnStatisticsContent(presentation) } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        // Expand fully on first show so the stats are immediately visible.
        dialog?.setOnShowListener {
            val sheet = dialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
        b.rpnStatsBlockedCard.isVisible = false
        b.rpnStatsViewLogs.setOnClickListener { openConnectionLogs() }
        setDolphinSignature()
        loadStats()
    }

    /** Dolphin signature (at the end of the sheet content.); random pairing, fresh on every visit. */
    private fun setDolphinSignature() {
        b.dolphinSignature.setContent(EmbeddedDolphinContent.random())
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Loads everything off the main thread:
     * 1. live WIN proxy id (needed to scope the DB queries),
     * 2. [RouterStats] for active-since + last handshake,
     * 3. last-24h aggregates + top apps from the connection-log DB.
     */
    private fun loadStats() {
        showLoadingState()

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val proxyId = Backend.RpnWin

            if (!isAdded) return@launch

            val since = System.currentTimeMillis() - timeWindowMs
            val summary = withContext(Dispatchers.IO) {
                try {
                    connectionTrackerDAO.getRpnConnStats(proxyId, since)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG: getRpnConnStats failed: ${e.message}")
                    null
                }
            }
            val topApps = withContext(Dispatchers.IO) {
                try {
                    connectionTrackerDAO.getRpnTopAppsForProxy(proxyId, since, TOP_APPS_LIMIT)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG: getRpnTopAppsForProxy failed: ${e.message}")
                    emptyList()
                }
            }

            if (!isAdded) return@launch
            showContentState()
            applyStats( summary, topApps)
        }
    }

    private fun applyStats(
        summary: RpnConnStatsSummary?,
        topApps: List<AppConnection>
    ) {
        // Last-24h data usage, aggregated from connection logs
        val rx = summary?.totalDownload ?: 0L
        val tx = summary?.totalUpload ?: 0L
        b.rpnStatsRx.text = getString(R.string.symbol_download, humanReadableByteCount(rx, true))
        b.rpnStatsTx.text = getString(R.string.symbol_upload, humanReadableByteCount(tx, true))

        // Last-24h aggregates
        b.rpnStatsConnCount.text = formatCount(summary?.connectionsCount ?: 0)
        b.rpnStatsBlockedCount.text = formatCount(summary?.blockedCount ?: 0)
        b.rpnStatsAppCount.text = formatCount(summary?.appCount ?: 0)

        // Top apps
        val hasApps = topApps.isNotEmpty()
        b.rpnStatsTopApps.isVisible = hasApps
        b.rpnStatsTopAppsEmpty.isVisible = !hasApps
        if (hasApps) {
            b.rpnStatsTopApps.adapter = TopAppsAdapter(topApps)
        }
    }

    private fun relativeTime(ts: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            ts,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

    private fun formatCount(count: Int): String = String.format("%,d", count)

    /**
     * Opens the connection logs screen filtered to the RPN proxy, mirroring
     * RpnConfigDetailActivity.invokeNetworkLogs().
     */
    private fun openConnectionLogs() {
        if (!isAdded) return
        val proxyId = Backend.RpnWin
        if (proxyId.isBlank()) {
            b.dolphinSignature.setContentForFailure()
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.rpn_stats_no_active_proxy),
                android.widget.Toast.LENGTH_SHORT
            )
            return
        }
        val intent = Intent(requireContext(), NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, NetworkLogsActivity.RULES_SEARCH_ID_RPN + proxyId)
        startActivity(intent)
    }

    private fun showLoadingState() {
        b.rpnStatsProgress.isVisible = true
        b.rpnStatsContent.isVisible = false
        b.rpnStatsError.isVisible = false
    }

    private fun showContentState() {
        b.rpnStatsProgress.isVisible = false
        b.rpnStatsContent.isVisible = true
        b.rpnStatsError.isVisible = false
    }

    private fun showErrorState() {
        b.rpnStatsProgress.isVisible = false
        b.rpnStatsContent.isVisible = false
        b.rpnStatsError.isVisible = true
        b.rpnStatsViewLogs.isVisible = false
    }

    inner class TopAppsAdapter(private val items: List<AppConnection>) {
        val itemCount: Int get() = items.size
        @Composable
        fun Row(position: Int) {
            val item = items[position]
            val context = LocalContext.current
            val icon = remember(context, item.uid) { loadIconForUid(context, item.uid) }
            val meta = getString(R.string.rpn_stats_conn_count, formatCount(item.count)) + " · " +
                humanReadableByteCount(item.downloadBytes ?: 0L, true) + " 🔻 · " +
                humanReadableByteCount(item.uploadBytes ?: 0L, true) + " 🔺"
            RpnStatisticsAppRow(item.appOrDnsName ?: stringResource(R.string.lbl_unknown), meta,
                humanReadableByteCount(item.totalBytes ?: 0L, true), icon = icon)
        }
        private fun loadIconForUid(context: android.content.Context, uid: Int) =
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()?.let {
                Utilities.getIcon(context, it)
            } ?: Utilities.getDefaultIcon(context)
    }
}
