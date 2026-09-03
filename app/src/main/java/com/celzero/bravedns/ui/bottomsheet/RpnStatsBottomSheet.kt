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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.RpnConnStatsSummary
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.databinding.BottomsheetRpnStatsBinding
import com.celzero.bravedns.databinding.ListItemRpnStatAppBinding
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

    private var _binding: BottomsheetRpnStatsBinding? = null
    private val b
        get() = checkNotNull(_binding) { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val connectionTrackerDAO by inject<ConnectionTrackerDAO>()

    /** Live WIN proxy id; null when the tunnel is down or the sheet is detached. */
    private var winProxyId: String? = null

    private var loadJob: Job? = null

    companion object {
        const val TAG = "RpnStatsBtmSheet"

        private const val TIME_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val TOP_APPS_LIMIT = 10

        fun newInstance(): RpnStatsBottomSheet = RpnStatsBottomSheet()
    }

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetRpnStatsBinding.inflate(inflater, container, false)
        return b.root
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

        b.rpnStatsViewLogs.setOnClickListener { openConnectionLogs() }
        loadStats()
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
            val proxyId = withContext(Dispatchers.IO) {
                try {
                    VpnController.getWinProxyId()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG: getWinProxyId failed: ${e.message}")
                    null
                }
            }
            winProxyId = proxyId

            if (!isAdded) return@launch
            if (proxyId.isNullOrBlank()) {
                showErrorState()
                return@launch
            }

            val since = System.currentTimeMillis() - TIME_WINDOW_MS
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
            b.rpnStatsTopApps.layoutManager = LinearLayoutManager(requireContext())
            b.rpnStatsTopApps.adapter = TopAppsAdapter(topApps)
            b.rpnStatsTopApps.itemAnimator = null
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
        val proxyId = winProxyId
        if (proxyId.isNullOrBlank()) {
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

    inner class TopAppsAdapter(private val items: List<AppConnection>) :
        RecyclerView.Adapter<TopAppsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ListItemRpnStatAppBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ListItemRpnStatAppBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.binding) {
                rpnStatAppName.text = item.appOrDnsName ?: root.context.getString(R.string.lbl_unknown)

                rpnStatAppMeta.text = getString(
                    R.string.rpn_stats_conn_count,
                    formatCount(item.count)
                ) + " · " +
                    humanReadableByteCount(item.downloadBytes ?: 0L, true) + " 🔻 · " +
                    humanReadableByteCount(item.uploadBytes ?: 0L, true) + " 🔺"

                rpnStatAppTotal.text = humanReadableByteCount(item.totalBytes ?: 0L, true)

                val icon = loadIconForUid(root.context, item.uid)
                Glide.with(root.context)
                    .load(icon)
                    .error(Utilities.getDefaultIcon(root.context))
                    .into(rpnStatAppIcon)
            }
        }

        /** Resolves an app icon from a bare uid (AppConnection carries no package name). */
        private fun loadIconForUid(context: android.content.Context, uid: Int) =
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()?.let {
                Utilities.getIcon(context, it)
            } ?: Utilities.getDefaultIcon(context)

        override fun getItemCount(): Int = items.size
    }
}
