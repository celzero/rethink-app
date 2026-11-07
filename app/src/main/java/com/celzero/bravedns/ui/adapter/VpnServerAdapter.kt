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
package com.celzero.bravedns.ui.adapter

import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.ui.compose.RethinkTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.compose.runtime.*
import com.celzero.bravedns.ui.compose.home.HomeTourAnchors
import com.celzero.bravedns.ui.compose.home.homeTourAnchor
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.util.LruCache
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_COUNTRY_CODE
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.RpnConfigDetailActivity
import com.celzero.bravedns.ui.activity.WgIncludeAppsActivity
import com.celzero.bravedns.ui.bottomsheet.RecentRoutedAppsBottomSheet
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.LAST_ROUTED_APP_POLL_MS
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.MAX_LOCATION_TILES
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.ROUTED_APP_STACK_SIZE
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.SERVER_INFO_POLL_MS
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.IPMetadata
import com.celzero.firestack.backend.RouterStats
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adapter for the list of currently-selected (active) VPN servers shown in ServerSelectionFragment
 */
class VpnServerAdapter(
    private val context: Context,
    serverGroups: List<ServerGroup>,
    private val listener: ServerSelectionListener
) : KoinComponent {
    private var serverGroups by mutableStateOf(serverGroups.toList(), neverEqualPolicy())
    private var allRevision by mutableStateOf(0)
    private val keyRevisions = mutableStateMapOf<String, Int>()
    private val itemCount: Int get() = serverGroups.size + if (hasAddTile()) 1 else 0
    fun notifyDataSetChanged() { allRevision++ }
    private fun notifyItemRangeChanged(start: Int, count: Int) { allRevision++ }
    private fun notifyItemChanged(index: Int) { serverGroups.getOrNull(index)?.key?.let { keyRevisions[it] = (keyRevisions[it] ?: 0) + 1 } }

    @Composable
    fun Content(modifier: Modifier = Modifier, visible: Boolean = true, anchors: HomeTourAnchors? = null) {
        val owner = LocalLifecycleOwner.current
        lifecycleOwner = owner
        RpnServerGrid(modifier) {
            serverGroups.forEach { group -> key(group.key) {
                val row = remember { ServerRow() }
                val revision = allRevision to (keyRevisions[group.key] ?: 0)
                var boundRevision by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                LaunchedEffect(visible, revision) {
                    if (visible && boundRevision != revision) {
                        row.bind(group)
                        boundRevision = revision
                    }
                }
                DisposableEffect(row, owner) {
                    row.attached = true
                    onDispose { row.detach() }
                }
                RpnServerTile(row.ui, group.key.equals(AUTO_SERVER_ID, true))
            } }
            if (hasAddTile()) key("rpn:add") { RpnAddServerTile(if (anchors == null) Modifier else Modifier.homeTourAnchor(R.id.add_server_tile, anchors)) {
                if (proxyStopped) listener.onProxyStoppedItemTapped() else listener.onAddServerTapped()
            } }
        }
    }


    private val connTrackerRepository by inject<ConnectionTrackerRepository>()

    private var lifecycleOwner: LifecycleOwner? = null

    /**
     * True when the RPN proxy has been deliberately stopped.
     * When set, every server item shows a "Stopped" status row and all taps
     */
    private var proxyStopped = false

    /**
     * Keys of selected servers whose WIN tunnel is not yet available
     * (VpnController.getWinByKey returned null immediately after startProxy).
     */
    private val loadingTunnelKeys = mutableSetOf<String>()

    /** Per-key async-rendered details, replayed on rebind to avoid flicker. */
    private class DetailCache {
        var statusPair: Pair<Int?, String>? = null
        var ipText: String? = null
        var appsText: String? = null
        var relayText: String? = null
        var relayPositive: Boolean = false
        var relayGone: Boolean = false
        var routedApps: List<Pair<ConnectionTracker, Drawable?>>? = null
    }

    private val detailCache = mutableMapOf<String, DetailCache>()

    private fun cacheFor(key: String): DetailCache =
        detailCache.getOrPut(key) { DetailCache() }

    /**
     * Replaces the entire set of "tunnel not yet ready" keys and notifies all items
     * so they can switch between loading and live-stats rendering.
     */
    fun setLoadingTunnelKeys(keys: Set<String>) {
        if (loadingTunnelKeys == keys) return
        loadingTunnelKeys.clear()
        loadingTunnelKeys.addAll(keys)
        notifyItemRangeChanged(0, itemCount)
    }

    /**
     * Removes [key] from the loading set and triggers a targeted rebind on its item
     * so it transitions from "Connecting…" to live stats without a full list refresh.
     */
    fun clearLoadingTunnelKey(key: String) {
        if (loadingTunnelKeys.remove(key)) {
            val idx = serverGroups.indexOfFirst { it.key == key }
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    /**
     * Adds [key] to the loading set so the item immediately shows "Connecting…"
     * the next time it is (re-)bound.  Triggers a targeted rebind if the item is
     * already visible so the transition is instant.
     */
    fun addLoadingTunnelKey(key: String) {
        if (loadingTunnelKeys.add(key)) {
            val idx = serverGroups.indexOfFirst { it.key == key }
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    /**
     * Switches all currently-bound items to/from stopped mode.
     * Skips rebind if the flag did not change.
     * Clears [loadingTunnelKeys] when entering stopped mode, the stopped status
     * row takes precedence over the per-item tunnel-setup indicator.
     */
    fun setProxyStopped(stopped: Boolean) {
        if (proxyStopped == stopped) return
        proxyStopped = stopped
        if (stopped) loadingTunnelKeys.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    companion object {
        private const val STATS_POLL_MS = 1500L
        private const val MIN_REFRESH_ANIM_MS = 1500L

        /** Polling interval for the last-routed-app row (and the apps chip refresh). */
        private const val LAST_ROUTED_APP_POLL_MS = 3000L

        /** Polling interval for IP */
        private const val SERVER_INFO_POLL_MS = 3000L

        /** Overlapping launcher-icon stack in the recently-routed-apps chip. */
        private const val ROUTED_APP_STACK_SIZE = 3

        /** Horizontal overlap between consecutive icons in the stack (dp). */
        private const val ROUTED_APP_ICON_STEP_DP = 11f

        /**
         * Total number of grid tiles the selected-locations section renders:
         * AUTO (always present) plus up to 5 user-selected locations.
         * When fewer server cards than this are showing, a trailing
         * "Add location" tile is appended to fill the grid.
         */
        private const val MAX_LOCATION_TILES = 6

        private const val VIEW_TYPE_SERVER = 0
        private const val VIEW_TYPE_ADD_TILE = 1
    }

    data class ServerGroup(
        val key: String,
        val servers: List<CountryConfig>,
        val countryName: String,
        val flagEmoji: String,
        val cityName: String,
        val countryCode: String,
        val bestLinkSpeed: Int,
        val leastLoad: Int,
        val isActive: Boolean
    ) {
        val serverCount: Int get() = servers.size

        fun getBestServer(): CountryConfig = servers.minByOrNull { it.load } ?: servers.first()

        fun proxyId(): String = if (key.equals(AUTO_SERVER_ID, true)) Backend.RpnWin else Backend.RpnWin + key
    }

    interface ServerSelectionListener {
        fun onServerGroupSelected(group: ServerGroup, isSelected: Boolean)
        fun onServerGroupRemoved(group: ServerGroup)
        /**
         * Called when any server item is tapped while the proxy is stopped.
         * The host should open the settings sheet so the user can restart the proxy.
         */
        fun onProxyStoppedItemTapped()

        /**
         * Called when the trailing "Add location" grid tile is tapped.
         * The host should surface the location picker (search list).
         */
        fun onAddServerTapped()

        /**
         * Called after the relay (hop) state of a single server was toggled from
         * this list. The host should re-derive any aggregate UI that depends on
         * the relay state of all servers (e.g. the Relay quick-settings tile).
         */
        fun onRelayToggled()
    }

    /**
     * True while the grid renders fewer server cards than [MAX_LOCATION_TILES],
     * in which case a trailing "Add location" tile is appended.
     */
    private fun hasAddTile(): Boolean = serverGroups.size < MAX_LOCATION_TILES

    // holders currently bound with live polling; main-thread confined. Three shared
    // tickers (stats / routed-apps / server-info) poll all of them per tick instead of
    // three while-loops per visible row.
    private val polledHolders = mutableListOf<ServerRow>()
    private var statsTickerJob: Job? = null
    private var routedAppTickerJob: Job? = null
    private var serverInfoTickerJob: Job? = null

    // launcher icons are immutable per package: decode once, reuse across 3s polls
    private val routedAppIconCache = LruCache<String, Drawable>(128)

    private fun registerForPolling(holder: ServerRow) {
        if (polledHolders.contains(holder)) return
        polledHolders.add(holder)
        ensurePollTickers()
    }

    private fun unregisterFromPolling(holder: ServerRow) {
        polledHolders.remove(holder)
        if (polledHolders.isNotEmpty()) return
        statsTickerJob?.cancel(); statsTickerJob = null
        routedAppTickerJob?.cancel(); routedAppTickerJob = null
        serverInfoTickerJob?.cancel(); serverInfoTickerJob = null
    }

    private fun ensurePollTickers() {
        if (statsTickerJob?.isActive == true) return
        val lco = lifecycleOwner ?: return
        statsTickerJob = lco.lifecycleScope.launch {
            lco.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    withContext(Dispatchers.Main) { tickPolledHolders(PollKind.STATS) }
                    delay(STATS_POLL_MS.milliseconds)
                }
            }
        }
        routedAppTickerJob = lco.lifecycleScope.launch {
            lco.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    withContext(Dispatchers.Main) { tickPolledHolders(PollKind.LAST_ROUTED_APP) }
                    delay(LAST_ROUTED_APP_POLL_MS.milliseconds)
                }
            }
        }
        serverInfoTickerJob = lco.lifecycleScope.launch {
            lco.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    withContext(Dispatchers.Main) { tickPolledHolders(PollKind.SERVER_INFO) }
                    delay(SERVER_INFO_POLL_MS.milliseconds)
                }
            }
        }
    }

    private enum class PollKind { STATS, LAST_ROUTED_APP, SERVER_INFO }

    private suspend fun tickPolledHolders(kind: PollKind) {
        if (lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) return
        val active = polledHolders.filter { it.attached }
        if (active.isEmpty()) {
            unregisterFromPollingAll()
            return
        }
        withContext(Dispatchers.IO) {
            active.forEach { holder ->
                when (kind) {
                    PollKind.STATS -> holder.pollStats()
                    PollKind.LAST_ROUTED_APP -> holder.pollLastRoutedApp()
                    PollKind.SERVER_INFO -> holder.pollServerInfo()
                }
            }
        }
    }

    private fun unregisterFromPollingAll() {
        statsTickerJob?.cancel(); statsTickerJob = null
        routedAppTickerJob?.cancel(); routedAppTickerJob = null
        serverInfoTickerJob?.cancel(); serverInfoTickerJob = null
    }

    fun updateServerGroups(newGroups: List<ServerGroup>) {
        val old = serverGroups
        val oldHadAddTile = old.size < MAX_LOCATION_TILES
        serverGroups = newGroups.toList()
        detailCache.keys.retainAll(newGroups.map { it.key }.toSet())
        keyRevisions.keys.retainAll(newGroups.map { it.key }.toSet())
        if (oldHadAddTile != hasAddTile()) {
            notifyDataSetChanged()
        } else {
            newGroups.forEachIndexed { index, group ->
                val previous = old.find { it.key == group.key }
                if (previous == null || previous.serverCount != group.serverCount || previous.cityName != group.cityName ||
                    previous.flagEmoji != group.flagEmoji || previous.countryCode != group.countryCode || previous.isActive != group.isActive) notifyItemChanged(index)
            }
        }
    }

    fun updateServers(newServers: List<CountryConfig>) {
        val groups = newServers.groupBy { it.key }.map { (key, list) ->
            val rep = list.first()
            ServerGroup(
                key = key,
                servers = list,
                countryName = rep.countryName,
                flagEmoji = rep.flagEmoji,
                cityName = rep.serverLocation,
                countryCode = rep.cc,
                bestLinkSpeed = if (list.all { it.link > 0 }) list.maxOfOrNull { it.link } ?: 0 else 0,
                leastLoad = if (list.all { it.load > 0 }) list.minOfOrNull { it.load } ?: 0 else 0,
                isActive = list.any { it.isActive }
            )
        }.sortedWith(
            compareBy(
                // AUTO is always pinned to the top of the selected list...
                { !it.key.equals(AUTO_SERVER_ID, ignoreCase = true) },
                // ...then remaining groups are arranged alphabetically by city name
                { it.cityName.lowercase() }
            )
        )
        updateServerGroups(groups)
    }

    inner class ServerRow {
        val ui = RpnServerRowState(context)
        var attached = false
        fun detach() {
            attached = false
            cancelStatsJob()
            refreshIconAnimator?.cancel()
            refreshIconAnimator = null
            ui.tvServerStatus.animate().cancel()
        }

        private val ctx: Context = context
        private var boundGroup: ServerGroup? = null

        /** Rotation spin running on [ui.refreshStopIcon] after an AUTO reconnect tap. */
        private var refreshIconAnimator: ObjectAnimator? = null

        /** Latest known exit IPv4 for this item (null while unknown). */
        private var currentIpText: String? = null

        /** Latest known proxy status for this item (null until first stats poll). */
        private var currentProxyStatus: UIUtils.ProxyStatus? = null

        private fun renderStatusRow() {
            // Connected with a known exit IP: just the tick (the IP speaks for
            // itself). No IP yet: show the human status ("Checking…",
            // "Connecting…", "Stopped") instead.
            val showCheck =
                currentProxyStatus == UIUtils.ProxyStatus.TOK && !currentIpText.isNullOrEmpty()
            ui.ivStatusCheck.visibility = if (showCheck) View.VISIBLE else View.GONE
            ui.tvServerStatus.visibility = if (showCheck) View.GONE else View.VISIBLE
        }

        fun bind(group: ServerGroup) {
            boundGroup = group
            // Stop a reconnect spin inherited from a recycled AUTO binding.
            refreshIconAnimator?.cancel()
            refreshIconAnimator = null
            ui.refreshStopIcon.rotation = 0f

            ui.tvServerIp.visibility = View.GONE
            ui.lastRoutedAppContainer.visibility = View.INVISIBLE
            currentIpText = null
            currentProxyStatus = null
            ui.ivStatusCheck.visibility = View.GONE
            ui.tvServerStatus.visibility = View.VISIBLE

            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                ui.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_refresh))
                ui.refreshStopIcon.visibility = View.VISIBLE
                // Monochrome glyph reads stronger than emoji flags; embed deeper.
                ui.flagWatermark.setFlagDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.ic_rpn_auto),
                    R.attr.primaryTextColor,
                    peakAlpha = 0.25f
                )
            } else {
                ui.refreshStopIcon.visibility = View.VISIBLE
                ui.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_cross))
                // Regular server: render the country flag as the corner watermark.
                ui.flagWatermark.setFlagText(group.flagEmoji)
            }

            val locationText = if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                "${group.cityName.capitalizeWords()} · ${AUTO_COUNTRY_CODE.capitalizeWords()}"
            } else if (group.serverCount > 1) {
                val cities = group.servers.map { it.serverLocation }.distinct()
                val cityText = if (cities.size <= 2) cities.joinToString(", ").capitalizeWords()
                else "${cities.first().capitalizeWords()} +${cities.size - 1} more"
                cityText
            } else {
                group.cityName.capitalizeWords()
            }
            ui.tvCountryName.text = locationText
            showAppsCount(group)
            showRelayAction(group)

            // Always cancel any running stats job before setting up the new state.
            cancelStatsJob()

            if (proxyStopped) {
                // Show a "Proxy Stopped" status row instead of live stats.
                showStoppedStatus()
                // Redirect every tap to the settings sheet so the user can restart.
                val stoppedClick: (RpnControlState) -> Unit = { listener.onProxyStoppedItemTapped() }
                ui.serverCard.setOnClickListener(stoppedClick)
                ui.refreshStopIcon.setOnClickListener(stoppedClick)
                ui.appsActionContainer.setOnClickListener(stoppedClick)
                ui.relayActionContainer.setOnClickListener(stoppedClick)
                ui.lastRoutedAppContainer.setOnClickListener(stoppedClick)
            } else if (loadingTunnelKeys.contains(group.key)) {
                // WIN tunnel for this server is still being set up (getWinByKey returned null).
                // Show a "Connecting…" indicator with a gentle pulse.
                showTunnelLoadingStatus()
                ui.refreshStopIcon.setOnClickListener {
                    handleRefreshClick(group)
                }
                ui.appsActionContainer.setOnClickListener { openAppsScreen(group) }
                ui.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }
                ui.relayActionContainer.setOnClickListener { toggleRelay(group) }
                ui.lastRoutedAppContainer.setOnClickListener { openRoutedAppLogs(group) }
                // Always start polling
                startPolling()
            } else {
                ui.refreshStopIcon.setOnClickListener {
                    handleRefreshClick(group)
                }
                // Apps chip opens the per-app mapping screen directly, without
                // routing through RpnConfigDetailActivity first.
                ui.appsActionContainer.setOnClickListener { openAppsScreen(group) }
                ui.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }
                ui.relayActionContainer.setOnClickListener { toggleRelay(group) }
                ui.lastRoutedAppContainer.setOnClickListener { openRoutedAppLogs(group) }

                // Replay cached details; placeholder only when nothing is cached yet.
                val hasCachedStatus = applyCachedDetails(group)
                if (!hasCachedStatus) showCheckingStatus()
                startPolling()
            }
        }

        private fun startPolling() {
            registerForPolling(this)
        }

        /** Re-applies cached details; true when a cached status was rendered. */
        private fun applyCachedDetails(group: ServerGroup): Boolean {
            val cached = detailCache[group.key] ?: return false
            cached.ipText?.let {
                currentIpText = it
                ui.tvServerIp.text = it
                ui.tvServerIp.visibility = View.VISIBLE
            }
            cached.routedApps?.let { applyLastRoutedApps(group.key, it) }
            cached.appsText?.let {
                ui.appsActionContainer.visibility = View.VISIBLE
                ui.appsAction.text = it
            }
            if (cached.relayGone) {
                ui.relayActionContainer.visibility = View.GONE
            } else cached.relayText?.let { text ->
                ui.relayActionContainer.visibility = View.VISIBLE
                ui.relayAction.text = text
                ui.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                ui.relayActionContainer.setBackgroundResource(
                    if (cached.relayPositive) R.drawable.bg_vpn_server_chip_positive
                    else R.drawable.bg_vpn_server_chip
                )
                ui.relayIcon.visibility = if (cached.relayPositive) View.VISIBLE else View.INVISIBLE
            }
            val pair = cached.statusPair ?: return false
            renderStatus(pair)
            return true
        }

        /**
         * Applies fetched IP metadata to the IP row and re-renders the status accordingly.
         */
        private fun applyIp(group: ServerGroup, ip4: IPMetadata?) {
            // Show the actual IP label when available, hide it otherwise.
            val ipText = ip4?.ip?.takeIf { it.isNotEmpty() }
            currentIpText = ipText
            cacheFor(group.key).ipText = ipText
            if (ipText != null) {
                ui.tvServerIp.text = ipText
                ui.tvServerIp.visibility = View.VISIBLE
            } else {
                ui.tvServerIp.visibility = View.GONE
            }
            renderStatusRow()
        }

        private fun handleRefreshClick(group: ServerGroup) {
            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                io {
                    val startTime = System.currentTimeMillis()
                    uiCtx {
                        refreshIconAnimator =
                            ObjectAnimator.ofFloat(ui.refreshStopIcon, "rotation", 0f, 360f).apply {
                                duration = 600L
                                repeatCount = ValueAnimator.INFINITE
                                interpolator = LinearInterpolator()
                                start()
                            }
                    }
                    try {
                        VpnController.reconnectRpnProxy("")
                    } finally {
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed < MIN_REFRESH_ANIM_MS) {
                            delay((MIN_REFRESH_ANIM_MS - elapsed).milliseconds)
                        }
                        uiCtx {
                            refreshIconAnimator?.cancel()
                            refreshIconAnimator = null
                            ui.refreshStopIcon.rotation = 0f
                        }
                    }
                }
            } else {
                listener.onServerGroupRemoved(group)
            }
        }

        /**
         * Displays a minimal "Proxy Stopped" status row.
         * Live stats and IP are hidden since the proxy is not routing traffic.
         */
        private fun showStoppedStatus() {
            ui.ivStatusCheck.visibility = View.GONE
            ui.tvServerStatus.visibility = View.VISIBLE
            ui.tvServerStatus.text = ctx.getString(R.string.server_settings_proxy_stopped)
            ui.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
        }

        /**
         * Displays a pulsing "Connecting…" status row while the WIN tunnel for this
         * server is still being set up asynchronously after startProxy.
         *
         * The [pollStatsLoop] continues to run in parallel; the first successful
         * [applyStats] call will cancel the pulse and display real data.
         */
        private fun showTunnelLoadingStatus() {
            ui.ivStatusCheck.visibility = View.GONE
            ui.tvServerStatus.visibility = View.VISIBLE
            ui.tvServerStatus.text = ctx.getString(R.string.lbl_connecting)
            ui.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
            // Kick off a gentle alpha pulse so the user can tell this item is "live"
            ui.tvServerStatus.animate().cancel()
            ui.tvServerStatus.alpha = 1f
            pulseTvStatus()
        }

        /**
         * Displays a pulsing "Checking…" status row while waiting for the first
         * stats poll to return.  Used as the initial state in the normal (non-loading)
         * path so the item is never left visually stranded before real data arrives.
         *
         * The [pollStatsLoop] runs in parallel; the first successful [applyStats]
         * call will cancel the pulse and display real data.
         */
        private fun showCheckingStatus() {
            ui.ivStatusCheck.visibility = View.GONE
            ui.tvServerStatus.visibility = View.VISIBLE
            ui.tvServerStatus.text = ctx.getString(R.string.lbl_checking)
            ui.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
            // states feel distinct to the user.
            ui.tvServerStatus.animate().cancel()
            ui.tvServerStatus.alpha = 1f
            pulseTvStatus()
        }

        /** Recursive alpha pulse on tvServerStatus. Stops when the view is detached. */
        private fun pulseTvStatus() {
            ui.tvServerStatus.animate()
                .alpha(0.25f).setDuration(700)
                .withEndAction {
                    if (attached) {
                        ui.tvServerStatus.animate()
                            .alpha(1f).setDuration(700)
                            .withEndAction { if (attached) pulseTvStatus() }
                            .start()
                    }
                }.start()
        }

        fun cancelStatsJob() {
            unregisterFromPolling(this)
        }

        // one iteration of the old per-row stats poll; invoked by the shared ticker
        suspend fun pollStats() {
            val group = boundGroup ?: return
            fetchAndApplyStats(group)
        }

        private suspend fun fetchAndApplyStats(group: ServerGroup) {
            try {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val id = group.proxyId()
                val statusPair = VpnController.getProxyStatusById(id)

                Logger.v(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyStats for id: $id, config: $config, status: $statusPair")
                uiCtx {
                    if (!attached) return@uiCtx

                    applyStats(group.key, config, statusPair)
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyStats[${group.key}]: ${t.message}")
                // If stats fetch fails (e.g. tunnel not up yet), keep the "Checking…"
                // pulse visible and retry after the next poll interval.
            }
        }

        // one iteration of the old per-row routed-apps poll; invoked by the shared ticker
        suspend fun pollLastRoutedApp() {
            val group = boundGroup ?: return
            fetchAndApplyLastRoutedApp(group)
        }

        private fun getIconCached(pkg: String, appName: String): Drawable? {
            routedAppIconCache.get(pkg)?.let { return it }
            val icon = runCatching { Utilities.getIcon(ctx, pkg, appName) }.getOrNull()
            if (icon != null) routedAppIconCache.put(pkg, icon)
            return icon
        }

        private suspend fun fetchAndApplyLastRoutedApp(group: ServerGroup) {
            try {
                val key = if (group.key == AUTO_SERVER_ID) {
                    VpnController.getWinProxyId() ?: "wgyrpn**"
                } else {
                    Backend.RpnWin + group.key
                }
                val recents = connTrackerRepository.getRecentRoutedAppsForProxy(
                    key, ROUTED_APP_STACK_SIZE
                )
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val apps = ProxyManager.getAppCountForProxy(Backend.RpnWin + group.key)

                val iconEntries = recents.map { ct ->
                    val icon = ct.packageName.takeIf { it.isNotBlank() }?.let {
                        getIconCached(it, ct.appName)
                    }
                    ct to icon
                }
                Logger.d(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyLastRoutedApp for id: ${group.proxyId()}, config: $config, apps: $apps, key: ${group.key}")
                uiCtx {
                    if (!attached) return@uiCtx
                    applyLastRoutedApps(group.key, iconEntries)
                    applyAppsAction(group.key, config, apps)
                    applyRelayAction(group.key, config)
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyLastRoutedApp[${group.key}]: ${t.message}")
            }
        }

        // one iteration of the old per-row server-info poll; invoked by the shared ticker
        suspend fun pollServerInfo() {
            val group = boundGroup ?: return
            fetchAndApplyServerInfo(group)
        }

        private suspend fun fetchAndApplyServerInfo(group: ServerGroup) {
            try {
                val isAuto = group.key.equals(AUTO_SERVER_ID, ignoreCase = true)
                val ip4 = fetchIpForGroup(group)
                val city = if (isAuto) {
                    runCatching { VpnController.getRpnAddlInfo(group.key) }.getOrNull()
                        ?.city?.trim().orEmpty()
                } else ""
                uiCtx {
                    if (!attached) return@uiCtx
                    applyIp(group, ip4)
                    if (isAuto && city.isNotEmpty()) {
                        ui.tvCountryName.text = context.getString(
                            R.string.two_argument_dot,
                            city.capitalizeWords(),
                            AUTO_COUNTRY_CODE.capitalizeWords()
                        )
                    }
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyServerInfo[${group.key}]: ${t.message}")
            }
        }

        /**
         * Renders the "Recently routed apps" indicator shown after the Relay chip:
         * an overlapping stack (up to [ROUTED_APP_STACK_SIZE]) of the routed apps'
         * launcher icons, newest on top-right. Apps without a resolvable launcher
         * icon are skipped; when none resolve, falls back to the ic_timer glyph
         * plus the most recent app name (marquee, single line). Hidden entirely
         * when no connection has been routed through this server.
         */
        private fun applyLastRoutedApps(key: String, entries: List<Pair<ConnectionTracker, Drawable?>>) {
            cacheFor(key).routedApps = entries
            val firstName = entries.firstOrNull()?.first?.appName?.trim().orEmpty()
            if (firstName.isEmpty()) {
                ui.lastRoutedAppContainer.visibility = View.INVISIBLE
                ui.lastRoutedAppContainer.contentDescription = null
                return
            }
            val relTime = DateUtils.getRelativeTimeSpanString(
                entries.first().first.timeStamp, System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
            )
            ui.lastRoutedAppContainer.contentDescription = ctx.getString(
                R.string.recently_routed_app,
                ctx.getString(R.string.two_argument_space, firstName, relTime.toString())
            )

            val resolved = entries.filter { it.second != null }
            if (resolved.isEmpty()) {
                // Fallback: timer glyph + most recent app name scrolling in a marquee.
                ui.lastRoutedAppIconStack.visibility = View.GONE
                ui.lastRoutedAppFallbackIcon.visibility = View.VISIBLE
                ui.lastRoutedAppAction.text = firstName
                // Marquee only scrolls while the view is "selected".
                ui.lastRoutedAppAction.isSelected = true
                ui.lastRoutedAppAction.visibility = View.VISIBLE
            } else {
                ui.lastRoutedAppFallbackIcon.visibility = View.GONE
                ui.lastRoutedAppAction.visibility = View.GONE
                val iconViews = listOf(ui.routedAppIcon0, ui.routedAppIcon1, ui.routedAppIcon2)
                iconViews.forEach { it.visibility = View.GONE }
                // Oldest of the resolved set sits leftmost, newest overlaps on
                // top-right (later children of the FrameLayout draw on top).
                resolved.forEachIndexed { i, (_, icon) ->
                    val v = iconViews[i]
                    v.setImageDrawable(icon)
                    v.imageTintList = null
                    v.visibility = View.VISIBLE
                }
                ui.lastRoutedAppIconStack.visibility = View.VISIBLE
            }
            ui.lastRoutedAppContainer.visibility = View.VISIBLE
        }

        /**
         * Fetches cached IP metadata for [group], falling back to a live RPN client
         * call only when the tunnel has reconnected (since-timestamp mismatch).
         * Returns the IPv4 [IPMetadata] or null if not yet available.
         */
        private suspend fun fetchIpForGroup(group: ServerGroup): IPMetadata? {
            return try {
                val key = group.key

                // Slow path: fetch live from the Go backend (3 s timeout to stay responsive).
                val client = withTimeoutOrNull(3_000L.milliseconds) {
                    runCatching { VpnController.getRpnClientInfoById(key) }.getOrNull()
                }
                runCatching { client?.iP4() }.getOrNull()
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchIpForGroup[${group.key}]: ${t.message}")
                null
            }
        }

        /**
         * Configures the subtle "Apps" action row. Shows the routed-app count (or
         * "All" for catch-all locations); the row and its separator are hidden
         * when the config is unavailable.
         *
         * This is the initial (bind-time) render; [pollLastRoutedAppLoop] keeps the
         * chip in sync afterwards so edits made in RpnConfigDetailActivity are
         * reflected when the user comes back to this list.
         */
        private fun showAppsCount(group: ServerGroup) {
            ui.appsActionContainer.visibility = View.INVISIBLE
            io {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val apps = ProxyManager.getAppCountForProxy(Backend.RpnWin + group.key)
                uiCtx {
                    if (!attached) return@uiCtx
                    applyAppsAction(group.key, config, apps)
                }
            }
        }

        private fun applyAppsAction(key: String, config: CountryConfig?, apps: Int) {
            if (config == null) {
                ui.appsActionContainer.visibility = View.INVISIBLE
                return
            }
            val text = if (config.catchAll) {
                ctx.getString(R.string.server_item_apps_all)
            } else {
                ctx.getString(R.string.server_item_apps_count, apps)
            }
            cacheFor(key).appsText = text
            ui.appsActionContainer.visibility = View.VISIBLE
            ui.appsAction.text = text
        }

        /**
         * Configures the "Relay" action chip shown next to the Apps chip. Hidden for
         * AUTO (it is the hop source for every other config, mirroring the detail
         * screen which hides hop settings for AUTO) and when the config is missing.
         *
         * This is the initial (bind-time) render; [pollLastRoutedAppLoop] keeps the
         * chip in sync so relay changes made in RpnConfigDetailActivity are reflected
         * when the user returns to this list.
         */
        private fun showRelayAction(group: ServerGroup) {
            ui.relayActionContainer.visibility = View.INVISIBLE
            io {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                uiCtx {
                    if (!attached) return@uiCtx
                    applyRelayAction(group.key, config)
                }
            }
        }

        /**
         * Renders the Relay chip state: a positive-tinted chip with a check
         * icon when the hop is active; the default chip surface when inactive.
         * The states are switched by swapping the background drawable — tinting
         * the base chip composites two translucent layers into near-invisibility.
         */
        private fun applyRelayAction(key: String, config: CountryConfig?) {
            if (config == null) {
                ui.relayActionContainer.visibility = View.INVISIBLE
                return
            }
            if (config.id.equals(AUTO_SERVER_ID, true)) {
                // AUTO carries no relay chip of its own; collapse the line
                // entirely instead of reserving an empty row under the card.
                cacheFor(key).relayGone = true
                ui.relayActionContainer.visibility = View.GONE
                return
            }
            cacheFor(key).relayGone = false
            ui.relayActionContainer.visibility = View.VISIBLE
            val relayLabel = ctx.getString(R.string.cd_dns_crypt_relay_heading) + " · " +
                ctx.getString(if (config.hopEnabled) R.string.lbl_on else R.string.lbl_off)
            if (config.hopEnabled) {
                val text = ctx.getString(
                    R.string.two_argument_space,
                    ctx.getString(R.string.symbol_bunny),
                    relayLabel
                )
                cacheFor(key).relayText = text
                cacheFor(key).relayPositive = true
                ui.relayAction.text = text
                ui.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                ui.relayActionContainer.setBackgroundResource(R.drawable.bg_vpn_server_chip_positive)
                // INVISIBLE (not GONE): the icon's slot stays reserved so the
                // chip keeps a constant width and the flow row never re-wraps
                // mid-interaction when Relay toggles.
                ui.relayIcon.visibility = View.VISIBLE
            } else {
                cacheFor(key).relayText = relayLabel
                cacheFor(key).relayPositive = false
                ui.relayAction.text = relayLabel
                ui.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                ui.relayActionContainer.setBackgroundResource(R.drawable.bg_vpn_server_chip)
                ui.relayIcon.visibility = View.INVISIBLE
            }
        }


        private fun toggleRelay(group: ServerGroup) {
            io {
                try {
                    val config = RpnProxyManager.getCountryConfigByKey(group.key) ?: return@io
                    val newState = !config.hopEnabled
                    if (!newState) {
                        setRelay(group, false)
                        return@io
                    }
                    val automationEnabled = runCatching { RpnProxyManager.isAutoAutomationEnabled() }
                        .onFailure { Logger.w(LOG_TAG_UI, "VpnServerAdapter toggleRelay[${group.key}]: automation check failed: ${it.message}") }
                        .getOrDefault(false)
                    uiCtx {
                        if (!attached) return@uiCtx
                        if (!automationEnabled) {
                            io { setRelay(group, true) }
                            return@uiCtx
                        }
                        WgAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
                            .setTitle(ctx.getString(R.string.qs_relay_automation_dialog_title))
                            .setMessage(ctx.getString(R.string.qs_relay_automation_dialog_message))
                            .setPositiveButton(ctx.getString(R.string.lbl_proceed)) { _, _ ->
                                io { setRelay(group, true) }
                            }
                            .setNegativeButton(ctx.getString(R.string.lbl_cancel), null)
                            .show()
                    }
                } catch (t: Throwable) {
                    Logger.w(LOG_TAG_UI, "VpnServerAdapter toggleRelay[${group.key}]: ${t.message}")
                }
            }
        }

        /**
         * Enables/disables the relay (hop) for [group] via
         * [RpnProxyManager.setHopForWinServer] and re-renders the chip from the
         * freshly persisted config. The periodic poll re-applies the state as well,
         * so even a failed toggle is corrected on the next tick.
         */
        private suspend fun setRelay(group: ServerGroup, newState: Boolean) {
            try {
                RpnProxyManager.setHopForWinServer(group.key, newState)
                val updated = RpnProxyManager.getCountryConfigByKey(group.key)
                uiCtx {
                    if (!attached) return@uiCtx
                    applyRelayAction(group.key, updated)
                    Utilities.showToastUiCentered(
                        ctx,
                        ctx.getString(R.string.cd_dns_crypt_relay_heading) + " " +
                            ctx.getString(if (newState) R.string.lbl_on else R.string.lbl_off),
                        Toast.LENGTH_SHORT
                    )
                    // Let the host re-derive aggregate relay UI (quick-settings tile).
                    listener.onRelayToggled()
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter setRelay[${group.key}]: ${t.message}")
            }
        }

        private fun applyStats(
            key: String,
            config: CountryConfig?,
            statusPair: Pair<Int?, String>
        ) {
            if (config == null) {
                hideStats()
                return
            }
            cacheFor(key).statusPair = statusPair
            renderStatus(statusPair)
        }

        /** Renders a connection status pair (cached or freshly polled). */
        private fun renderStatus(statusPair: Pair<Int?, String>) {
            // Stop any loading-pulse animation that may be running from showTunnelLoadingStatus().
            ui.tvServerStatus.animate().cancel()
            ui.tvServerStatus.alpha = 1f

            // Status chip
            val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }

            currentProxyStatus = status
            ui.tvServerStatus.text = getStatusText(status, statusPair.second)
            ui.tvServerStatus.setTextColor(fetchColor(ctx, getStatusColor(status)))
            renderStatusRow()
        }

        private fun hideStats() {
            ui.tvServerIp.visibility = View.GONE
            ui.ivStatusCheck.visibility = View.GONE
            ui.tvServerStatus.visibility = View.VISIBLE
        }

        private fun getStatusColor(status: UIUtils.ProxyStatus?): Int {
            return when (status) {
                UIUtils.ProxyStatus.TOK -> R.attr.primaryLightColorText
                UIUtils.ProxyStatus.TUP,
                UIUtils.ProxyStatus.TZZ,
                UIUtils.ProxyStatus.TNT -> R.attr.chipTextNeutral
                else -> R.attr.chipTextNegative
            }
        }

        private fun getStatusText(
            status: UIUtils.ProxyStatus?,
            errMsg: String?
        ): String {
            if (status == null) {
                val base = if (!errMsg.isNullOrEmpty())
                    ctx.getString(R.string.status_waiting) + " ($errMsg)"
                else
                    ctx.getString(R.string.status_waiting)
                return base.capitalizeWords()
            }
            if (status == UIUtils.ProxyStatus.TPU) {
                return ctx.getString(UIUtils.getProxyStatusStringRes(status.id))
                    .replaceFirstChar(Char::titlecase)
            }
            // For RPN proxies, trust the status enum directly – no since/lastOK override.
            // See getStatusColor() for the full rationale.
            return ctx.getString(UIUtils.getProxyStatusStringRes(status.id))
                .replaceFirstChar(Char::titlecase)
        }

        private fun getUpTime(stats: RouterStats?): CharSequence {
            val selectedSinceTs = stats?.since ?: 0L
            return if (selectedSinceTs > 0L)
                DateUtils.getRelativeTimeSpanString(
                    selectedSinceTs, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
                )
            else context.getString(R.string.lbl_never)
        }

        private fun openServerDetail(server: CountryConfig) {
            val intent = Intent(ctx, RpnConfigDetailActivity::class.java)
            intent.putExtra(RpnConfigDetailActivity.INTENT_EXTRA_FROM_SERVER_SELECTION, true)
            intent.putExtra(RpnConfigDetailActivity.INTENT_EXTRA_CONFIG_KEY, server.key)
            ctx.startActivity(intent)
        }

        private fun openAppsScreen(group: ServerGroup) {
            if (group.getBestServer().catchAll) {
                openServerDetail(group.getBestServer())
                return
            }
            io {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val proxyId = Backend.RpnWin + group.key
                uiCtx {
                    if (proxyId.isBlank()) {
                        Logger.w(LOG_TAG_UI, "VpnServerAdapter openAppsScreen[${group.key}]: win proxy id unavailable")
                        return@uiCtx
                    }
                    val proxyName = when {
                        group.key.equals(AUTO_SERVER_ID, ignoreCase = true) ->
                            AUTO_SERVER_ID.capitalizeWords()
                        config != null && config.city.isNotBlank() -> "${config.city} - ${config.cc}"
                        config != null && config.name.isNotBlank() -> config.name
                        else -> group.key
                    }
                    ctx.startActivity(WgIncludeAppsActivity.newIntent(ctx, proxyId, proxyName))
                }
            }
        }

        /** opens the recent-routed-apps bottom sheet for this server */
        private fun openRoutedAppLogs(group: ServerGroup) {
            io {
                val proxyId = if (group.key.equals(AUTO_SERVER_ID, true)) {
                    VpnController.getWinProxyId().orEmpty()
                } else {
                    Backend.RpnWin + group.key
                }
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                uiCtx {
                    if (proxyId.isBlank()) {
                        Logger.w(LOG_TAG_UI, "VpnServerAdapter openRoutedAppLogs[${group.key}]: win proxy id unavailable")
                        return@uiCtx
                    }
                    val fm = (ctx as? FragmentActivity)?.supportFragmentManager ?: return@uiCtx
                    val subtitle = when {
                        group.key.equals(AUTO_SERVER_ID, ignoreCase = true) ->
                            AUTO_SERVER_ID.capitalizeWords()
                        config != null && config.city.isNotBlank() -> "${config.city} - ${config.cc}"
                        config != null && config.name.isNotBlank() -> config.name
                        else -> group.cityName
                    }
                    RecentRoutedAppsBottomSheet.newInstance(proxyId, subtitle)
                        .show(fm, RecentRoutedAppsBottomSheet.TAG)
                }
            }
        }

        private fun io(f: suspend () -> Unit) {
            val lco = lifecycleOwner
            lco?.lifecycleScope?.launch(Dispatchers.IO) { f() }
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            val owner = lifecycleOwner ?: return

            withContext(Dispatchers.Main.immediate) {
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@withContext
                }

                f()
            }
        }

        private suspend fun ioCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.IO) { f() }
        }

    }

    /**
     * Trailing grid tile shown while fewer than [MAX_LOCATION_TILES] locations
     * are selected. Tapping it asks the host to surface the location picker.
     * While the proxy is stopped the tap is redirected to the stopped-state
     * handler, mirroring server-card behaviour.
     */
}
