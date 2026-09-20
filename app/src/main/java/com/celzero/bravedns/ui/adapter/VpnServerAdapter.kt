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

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.ListItemVpnServerAddBinding
import com.celzero.bravedns.databinding.ListItemVpnServerBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_COUNTRY_CODE
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.activity.RpnConfigDetailActivity
import com.celzero.bravedns.ui.activity.WgIncludeAppsActivity
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.LAST_ROUTED_APP_POLL_MS
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.MAX_LOCATION_TILES
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.ROUTED_APP_STACK_SIZE
import com.celzero.bravedns.ui.adapter.VpnServerAdapter.Companion.SERVER_INFO_POLL_MS
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.IPMetadata
import com.celzero.firestack.backend.RouterStats
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var serverGroups: List<ServerGroup>,
    private val listener: ServerSelectionListener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), KoinComponent {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (lifecycleOwner == null) lifecycleOwner = parent.findViewTreeLifecycleOwner()
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ADD_TILE -> AddTileViewHolder(
                ListItemVpnServerAddBinding.inflate(inflater, parent, false)
            )
            else -> {
                val b = ListItemVpnServerBinding.inflate(inflater, parent, false)
                // Clip the corner flag watermark against the card's rounded outline.
                b.serverCard.clipToOutline = true
                ServerViewHolder(b)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (hasAddTile() && position == serverGroups.size) VIEW_TYPE_ADD_TILE
        else VIEW_TYPE_SERVER
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (lifecycleOwner == null) lifecycleOwner = holder.itemView.findViewTreeLifecycleOwner()
        when (holder) {
            is ServerViewHolder -> holder.bind(serverGroups[position])
            is AddTileViewHolder -> holder.bind()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is ServerViewHolder) holder.cancelStatsJob()
    }

    override fun getItemCount(): Int = serverGroups.size + if (hasAddTile()) 1 else 0

    fun updateServerGroups(newGroups: List<ServerGroup>) {
        val old = serverGroups
        val oldHadAddTile = old.size < MAX_LOCATION_TILES
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newGroups.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key == newGroups[n].key
            // Diff only rendered fields; volatile load/link stats would rebind every card.
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = newGroups[n]
                return a.serverCount == b.serverCount &&
                    a.cityName == b.cityName &&
                    a.flagEmoji == b.flagEmoji &&
                    a.countryCode == b.countryCode &&
                    a.isActive == b.isActive
            }
        })
        serverGroups = newGroups.toList()
        detailCache.keys.retainAll(newGroups.map { it.key }.toSet())
        // The add tile is not represented in DiffUtil's lists.
        if (oldHadAddTile != hasAddTile()) {
            notifyDataSetChanged()
        } else {
            diff.dispatchUpdatesTo(this)
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

    inner class ServerViewHolder(private val b: ListItemVpnServerBinding) :
        RecyclerView.ViewHolder(b.root) {

        private val ctx: Context = b.root.context
        private var statsJob: Job? = null
        private var lastRoutedAppJob: Job? = null
        private var serverInfoJob: Job? = null

        /** Rotation spin running on [b.refreshStopIcon] after an AUTO reconnect tap. */
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
            b.ivStatusCheck.visibility = if (showCheck) View.VISIBLE else View.GONE
            b.tvServerStatus.visibility = if (showCheck) View.GONE else View.VISIBLE
        }

        fun bind(group: ServerGroup) {
            // Stop a reconnect spin inherited from a recycled AUTO binding.
            refreshIconAnimator?.cancel()
            refreshIconAnimator = null
            b.refreshStopIcon.rotation = 0f

            // AUTO stacks the routed-apps chip under the Apps chip; other
            // proxies place it to the right.
            val isAuto = group.key.equals(AUTO_SERVER_ID, ignoreCase = true)
            b.chipsRow.orientation =
                if (isAuto) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val routedLp = b.lastRoutedAppContainer.layoutParams as LinearLayout.LayoutParams
            val gap = (6f * context.resources.displayMetrics.density).roundToInt()
            if (isAuto) {
                routedLp.topMargin = gap
                routedLp.marginStart = 0
            } else {
                routedLp.topMargin = 0
                routedLp.marginStart = (10f * context.resources.displayMetrics.density).roundToInt()
            }
            b.lastRoutedAppContainer.layoutParams = routedLp

            // Bottom anchor: non-AUTO cards hang the relay chip off the card
            // bottom (its bottom margin then sizes the card); for AUTO the
            // relay is GONE, so chips_row takes the bottom anchor instead.
            val chipsLp = b.chipsRow.layoutParams as ConstraintLayout.LayoutParams
            val relayClLp = b.relayActionContainer.layoutParams as ConstraintLayout.LayoutParams
            if (isAuto) {
                chipsLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                relayClLp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            } else {
                chipsLp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                relayClLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            b.chipsRow.layoutParams = chipsLp
            b.relayActionContainer.layoutParams = relayClLp

            b.tvServerIp.visibility = View.GONE
            b.lastRoutedAppContainer.visibility = View.INVISIBLE
            currentIpText = null
            currentProxyStatus = null
            b.ivStatusCheck.visibility = View.GONE
            b.tvServerStatus.visibility = View.VISIBLE

            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                b.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_refresh))
                b.refreshStopIcon.visibility = View.VISIBLE
                // Monochrome glyph reads stronger than emoji flags; embed deeper.
                b.flagWatermark.setFlagDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.ic_rpn_auto),
                    R.attr.primaryTextColor,
                    peakAlpha = 0.25f
                )
            } else {
                b.refreshStopIcon.visibility = View.VISIBLE
                b.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_cross))
                // Regular server: render the country flag as the corner watermark.
                b.flagWatermark.setFlagText(group.flagEmoji)
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
            b.tvCountryName.text = locationText
            showAppsCount(group)
            showRelayAction(group)

            // Always cancel any running stats job before setting up the new state.
            cancelStatsJob()

            if (proxyStopped) {
                // Show a "Proxy Stopped" status row instead of live stats.
                showStoppedStatus()
                // Redirect every tap to the settings sheet so the user can restart.
                val stoppedClick = View.OnClickListener { listener.onProxyStoppedItemTapped() }
                b.serverCard.setOnClickListener(stoppedClick)
                b.refreshStopIcon.setOnClickListener(stoppedClick)
                b.appsActionContainer.setOnClickListener(stoppedClick)
                b.relayActionContainer.setOnClickListener(stoppedClick)
                b.lastRoutedAppContainer.setOnClickListener(stoppedClick)
            } else if (loadingTunnelKeys.contains(group.key)) {
                // WIN tunnel for this server is still being set up (getWinByKey returned null).
                // Show a "Connecting…" indicator with a gentle pulse.
                showTunnelLoadingStatus()
                b.refreshStopIcon.setOnClickListener {
                    handleRefreshClick(group)
                }
                b.appsActionContainer.setOnClickListener { openAppsScreen(group) }
                b.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }
                b.relayActionContainer.setOnClickListener { toggleRelay(group) }
                b.lastRoutedAppContainer.setOnClickListener { openRoutedAppLogs(group) }
                // Always start polling
                statsJob = pollStatsLoop(group)
                lastRoutedAppJob = pollLastRoutedAppLoop(group)
                serverInfoJob = pollServerInfoLoop(group)
            } else {
                b.refreshStopIcon.setOnClickListener {
                    handleRefreshClick(group)
                }
                // Apps chip opens the per-app mapping screen directly, without
                // routing through RpnConfigDetailActivity first.
                b.appsActionContainer.setOnClickListener { openAppsScreen(group) }
                b.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }
                b.relayActionContainer.setOnClickListener { toggleRelay(group) }
                b.lastRoutedAppContainer.setOnClickListener { openRoutedAppLogs(group) }

                // Replay cached details; placeholder only when nothing is cached yet.
                val hasCachedStatus = applyCachedDetails(group)
                if (!hasCachedStatus) showCheckingStatus()
                statsJob = pollStatsLoop(group)
                lastRoutedAppJob = pollLastRoutedAppLoop(group)
                serverInfoJob = pollServerInfoLoop(group)
            }
        }

        /** Re-applies cached details; true when a cached status was rendered. */
        private fun applyCachedDetails(group: ServerGroup): Boolean {
            val cached = detailCache[group.key] ?: return false
            cached.ipText?.let {
                currentIpText = it
                b.tvServerIp.text = it
                b.tvServerIp.visibility = View.VISIBLE
            }
            cached.routedApps?.let { applyLastRoutedApps(group.key, it) }
            cached.appsText?.let {
                b.appsActionContainer.visibility = View.VISIBLE
                b.appsAction.text = it
            }
            if (cached.relayGone) {
                b.relayActionContainer.visibility = View.GONE
            } else cached.relayText?.let { text ->
                b.relayActionContainer.visibility = View.VISIBLE
                b.relayAction.text = text
                b.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                b.relayActionContainer.setBackgroundResource(
                    if (cached.relayPositive) R.drawable.bg_vpn_server_chip_positive
                    else R.drawable.bg_vpn_server_chip
                )
                b.relayIcon.visibility = if (cached.relayPositive) View.VISIBLE else View.INVISIBLE
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
                b.tvServerIp.text = ipText
                b.tvServerIp.visibility = View.VISIBLE
            } else {
                b.tvServerIp.visibility = View.GONE
            }
            renderStatusRow()
        }

        private fun handleRefreshClick(group: ServerGroup) {
            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                io {
                    val startTime = System.currentTimeMillis()
                    uiCtx {
                        refreshIconAnimator =
                            ObjectAnimator.ofFloat(b.refreshStopIcon, "rotation", 0f, 360f).apply {
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
                            b.refreshStopIcon.rotation = 0f
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
            b.ivStatusCheck.visibility = View.GONE
            b.tvServerStatus.visibility = View.VISIBLE
            b.tvServerStatus.text = ctx.getString(R.string.server_settings_proxy_stopped)
            b.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
        }

        /**
         * Displays a pulsing "Connecting…" status row while the WIN tunnel for this
         * server is still being set up asynchronously after startProxy.
         *
         * The [pollStatsLoop] continues to run in parallel; the first successful
         * [applyStats] call will cancel the pulse and display real data.
         */
        private fun showTunnelLoadingStatus() {
            b.ivStatusCheck.visibility = View.GONE
            b.tvServerStatus.visibility = View.VISIBLE
            b.tvServerStatus.text = ctx.getString(R.string.lbl_connecting)
            b.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
            // Kick off a gentle alpha pulse so the user can tell this item is "live"
            b.tvServerStatus.animate().cancel()
            b.tvServerStatus.alpha = 1f
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
            b.ivStatusCheck.visibility = View.GONE
            b.tvServerStatus.visibility = View.VISIBLE
            b.tvServerStatus.text = ctx.getString(R.string.lbl_checking)
            b.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
            // states feel distinct to the user.
            b.tvServerStatus.animate().cancel()
            b.tvServerStatus.alpha = 1f
            pulseTvStatus()
        }

        /** Recursive alpha pulse on tvServerStatus. Stops when the view is detached. */
        private fun pulseTvStatus() {
            b.tvServerStatus.animate()
                .alpha(0.25f).setDuration(700)
                .withEndAction {
                    if (b.root.isAttachedToWindow) {
                        b.tvServerStatus.animate()
                            .alpha(1f).setDuration(700)
                            .withEndAction { if (b.root.isAttachedToWindow) pulseTvStatus() }
                            .start()
                    }
                }.start()
        }

        fun cancelStatsJob() {
            if (statsJob?.isActive == true) statsJob?.cancel()
            statsJob = null
            if (lastRoutedAppJob?.isActive == true) lastRoutedAppJob?.cancel()
            lastRoutedAppJob = null
            if (serverInfoJob?.isActive == true) serverInfoJob?.cancel()
            serverInfoJob = null
        }

        private fun pollStatsLoop(group: ServerGroup): Job? {
            val lco = lifecycleOwner ?: return null
            // repeatOnLifecycle(STARTED) automatically suspends the inner block whenever
            // the lifecycle drops below STARTED
            return lco.lifecycleScope.launch {
                lco.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        ioCtx { fetchAndApplyStats(group) }
                        delay(STATS_POLL_MS.milliseconds)
                    }
                }
            }
        }

        private suspend fun fetchAndApplyStats(group: ServerGroup) {
            try {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val id = group.proxyId()
                val statusPair = VpnController.getProxyStatusById(id)

                Logger.v(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyStats for id: $id, config: $config, status: $statusPair")
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx

                    applyStats(group.key, config, statusPair)
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyStats[${group.key}]: ${t.message}")
                // If stats fetch fails (e.g. tunnel not up yet), keep the "Checking…"
                // pulse visible and retry after the next poll interval.
            }
        }

        /**
         * Polls, every [LAST_ROUTED_APP_POLL_MS] ms, the last connection routed through this
         * RPN server (matched on [ServerGroup.key], the same configKey sent to
         * RpnConfigDetailActivity) and refreshes the apps chip so changes made inside
         * RpnConfigDetailActivity / WgIncludeAppsActivity are reflected when the user
         * returns without a rebind.
         */
        private fun pollLastRoutedAppLoop(group: ServerGroup): Job? {
            val lco = lifecycleOwner ?: return null
            // repeatOnLifecycle(STARTED) automatically suspends the inner block whenever
            // the lifecycle drops below STARTED
            return lco.lifecycleScope.launch {
                lco.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        ioCtx { fetchAndApplyLastRoutedApp(group) }
                        delay(LAST_ROUTED_APP_POLL_MS.milliseconds)
                    }
                }
            }
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
                        runCatching { Utilities.getIcon(ctx, it, ct.appName) }.getOrNull()
                    }
                    ct to icon
                }
                Logger.d(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyLastRoutedApp for id: ${group.proxyId()}, config: $config, apps: $apps, key: ${group.key}")
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyLastRoutedApps(group.key, iconEntries)
                    applyAppsAction(group.key, config, apps)
                    applyRelayAction(group.key, config)
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyLastRoutedApp[${group.key}]: ${t.message}")
            }
        }

        /**
         * Polls, every [SERVER_INFO_POLL_MS] ms
         */
        private fun pollServerInfoLoop(group: ServerGroup): Job? {
            val lco = lifecycleOwner ?: return null
            return lco.lifecycleScope.launch {
                lco.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        ioCtx { fetchAndApplyServerInfo(group) }
                        delay(SERVER_INFO_POLL_MS.milliseconds)
                    }
                }
            }
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
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyIp(group, ip4)
                    if (isAuto && city.isNotEmpty()) {
                        b.tvCountryName.text = context.getString(
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
                b.lastRoutedAppContainer.visibility = View.INVISIBLE
                b.lastRoutedAppContainer.contentDescription = null
                return
            }
            val relTime = DateUtils.getRelativeTimeSpanString(
                entries.first().first.timeStamp, System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
            )
            b.lastRoutedAppContainer.contentDescription = ctx.getString(
                R.string.recently_routed_app,
                ctx.getString(R.string.two_argument_space, firstName, relTime.toString())
            )

            val resolved = entries.filter { it.second != null }
            if (resolved.isEmpty()) {
                // Fallback: timer glyph + most recent app name scrolling in a marquee.
                b.lastRoutedAppIconStack.visibility = View.GONE
                b.lastRoutedAppFallbackIcon.visibility = View.VISIBLE
                b.lastRoutedAppAction.text = firstName
                // Marquee only scrolls while the view is "selected".
                b.lastRoutedAppAction.isSelected = true
                b.lastRoutedAppAction.visibility = View.VISIBLE
            } else {
                b.lastRoutedAppFallbackIcon.visibility = View.GONE
                b.lastRoutedAppAction.visibility = View.GONE
                val iconViews = listOf(b.routedAppIcon0, b.routedAppIcon1, b.routedAppIcon2)
                iconViews.forEach { it.visibility = View.GONE }
                // Oldest of the resolved set sits leftmost, newest overlaps on
                // top-right (later children of the FrameLayout draw on top).
                resolved.forEachIndexed { i, (_, icon) ->
                    val v = iconViews[i]
                    v.setImageDrawable(icon)
                    v.imageTintList = null
                    v.visibility = View.VISIBLE
                    v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
                        marginStart = (ROUTED_APP_ICON_STEP_DP * i *
                                ctx.resources.displayMetrics.density).toInt()
                    }
                }
                b.lastRoutedAppIconStack.visibility = View.VISIBLE
            }
            b.lastRoutedAppContainer.visibility = View.VISIBLE
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
            b.appsActionContainer.visibility = View.INVISIBLE
            io {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val apps = ProxyManager.getAppCountForProxy(Backend.RpnWin + group.key)
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyAppsAction(group.key, config, apps)
                }
            }
        }

        private fun applyAppsAction(key: String, config: CountryConfig?, apps: Int) {
            if (config == null) {
                b.appsActionContainer.visibility = View.INVISIBLE
                return
            }
            val text = if (config.catchAll) {
                ctx.getString(R.string.server_item_apps_all)
            } else {
                ctx.getString(R.string.server_item_apps_count, apps)
            }
            cacheFor(key).appsText = text
            b.appsActionContainer.visibility = View.VISIBLE
            b.appsAction.text = text
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
            b.relayActionContainer.visibility = View.INVISIBLE
            io {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
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
                b.relayActionContainer.visibility = View.INVISIBLE
                return
            }
            if (config.id.equals(AUTO_SERVER_ID, true)) {
                // AUTO carries no relay chip of its own; collapse the line
                // entirely instead of reserving an empty row under the card.
                cacheFor(key).relayGone = true
                b.relayActionContainer.visibility = View.GONE
                return
            }
            cacheFor(key).relayGone = false
            b.relayActionContainer.visibility = View.VISIBLE
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
                b.relayAction.text = text
                b.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                b.relayActionContainer.setBackgroundResource(R.drawable.bg_vpn_server_chip_positive)
                // INVISIBLE (not GONE): the icon's slot stays reserved so the
                // chip keeps a constant width and the flow row never re-wraps
                // mid-interaction when Relay toggles.
                b.relayIcon.visibility = View.VISIBLE
            } else {
                cacheFor(key).relayText = relayLabel
                cacheFor(key).relayPositive = false
                b.relayAction.text = relayLabel
                b.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                b.relayActionContainer.setBackgroundResource(R.drawable.bg_vpn_server_chip)
                b.relayIcon.visibility = View.INVISIBLE
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
                        if (!b.root.isAttachedToWindow) return@uiCtx
                        if (!automationEnabled) {
                            io { setRelay(group, true) }
                            return@uiCtx
                        }
                        MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
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
                    if (!b.root.isAttachedToWindow) return@uiCtx
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
            b.tvServerStatus.animate().cancel()
            b.tvServerStatus.alpha = 1f

            // Status chip
            val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }

            currentProxyStatus = status
            b.tvServerStatus.text = getStatusText(status, statusPair.second)
            b.tvServerStatus.setTextColor(fetchColor(ctx, getStatusColor(status)))
            renderStatusRow()
        }

        private fun hideStats() {
            b.tvServerIp.visibility = View.GONE
            b.ivStatusCheck.visibility = View.GONE
            b.tvServerStatus.visibility = View.VISIBLE
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
                        config != null && config.city.isNotBlank() -> "${config.cc} - ${config.city}"
                        config != null && config.name.isNotBlank() -> config.name
                        else -> group.key
                    }
                    ctx.startActivity(WgIncludeAppsActivity.newIntent(ctx, proxyId, proxyName))
                }
            }
        }

        /**
         * Opens the network-logs screen pre-filtered for this server
         */
        private fun openRoutedAppLogs(group: ServerGroup) {
            io {
                val proxyId = if (group.key.equals(AUTO_SERVER_ID, true)) {
                    VpnController.getWinProxyId().orEmpty()
                } else {
                    Backend.RpnWin + group.key
                }
                uiCtx {
                    if (proxyId.isBlank()) {
                        Logger.w(LOG_TAG_UI, "VpnServerAdapter openRoutedAppLogs[${group.key}]: win proxy id unavailable")
                        return@uiCtx
                    }
                    val intent = Intent(ctx, NetworkLogsActivity::class.java)
                    intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_RPN + proxyId)
                    ctx.startActivity(intent)
                }
            }
        }

        private fun io(f: suspend () -> Unit) {
            val lco = lifecycleOwner ?: b.root.findViewTreeLifecycleOwner()
            lco?.lifecycleScope?.launch(Dispatchers.IO) { f() }
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            val owner = lifecycleOwner ?: b.root.findViewTreeLifecycleOwner() ?: return

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
    inner class AddTileViewHolder(private val b: ListItemVpnServerAddBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            b.addServerTile.setOnClickListener {
                if (proxyStopped) listener.onProxyStoppedItemTapped()
                else listener.onAddServerTapped()
            }
        }
    }
}
