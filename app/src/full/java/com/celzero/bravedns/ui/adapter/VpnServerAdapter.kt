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

import Logger
import Logger.LOG_TAG_UI
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.ListItemVpnServerBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.RpnConfigDetailActivity
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.IPMetadata
import com.celzero.firestack.backend.RouterStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Adapter for the list of currently-selected (active) VPN servers shown in ServerSelectionFragment
 */
class VpnServerAdapter(
    private val context: Context,
    private var serverGroups: List<ServerGroup>,
    private val listener: ServerSelectionListener
) : RecyclerView.Adapter<VpnServerAdapter.ServerViewHolder>() {

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
        private const val STATS_POLL_MS = 2500L
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

        fun proxyId(): String = if (key.equals(AUTO_SERVER_ID, true)) "${Backend.RpnWin}**" else Backend.RpnWin + key
    }

    interface ServerSelectionListener {
        fun onServerGroupSelected(group: ServerGroup, isSelected: Boolean)
        fun onServerGroupRemoved(group: ServerGroup)
        /**
         * Called when any server item is tapped while the proxy is stopped.
         * The host should open the settings sheet so the user can restart the proxy.
         */
        fun onProxyStoppedItemTapped()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        if (lifecycleOwner == null) lifecycleOwner = parent.findViewTreeLifecycleOwner()
        val b = ListItemVpnServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerViewHolder(b)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(serverGroups[position])
    }

    override fun onViewDetachedFromWindow(holder: ServerViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.cancelStatsJob()
    }

    override fun getItemCount(): Int = serverGroups.size

    fun updateServerGroups(newGroups: List<ServerGroup>) {
        val old = serverGroups
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newGroups.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key == newGroups[n].key
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newGroups[n]
        })
        serverGroups = newGroups.toList()
        diff.dispatchUpdatesTo(this)
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
        }.sortedBy { it.cityName.lowercase() }
        updateServerGroups(groups)
    }

    inner class ServerViewHolder(private val b: ListItemVpnServerBinding) :
        RecyclerView.ViewHolder(b.root) {

        private val ctx: Context = b.root.context
        private var statsJob: Job? = null


        fun bind(group: ServerGroup) {
            b.tvServerIp.visibility = View.GONE

            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                b.infoIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_refresh_white))
                b.infoIcon.visibility = View.VISIBLE
                // AUTO server: show the vector ic_rpn_auto, hide the emoji text view
                b.tvFlag.text = ""
                b.ivFlagImage.visibility = View.VISIBLE
            } else {
                b.infoIcon.visibility = View.VISIBLE
                b.infoIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_cross))
                // Regular server: show the country flag emoji, hide the globe image
                b.tvFlag.text = group.flagEmoji
                b.ivFlagImage.visibility = View.GONE
            }

            val locationText = if (group.serverCount > 1) {
                val cities = group.servers.map { it.serverLocation }.distinct()
                val cityText = if (cities.size <= 2) cities.joinToString(", ").capitalizeWords()
                else "${cities.first().capitalizeWords()} +${cities.size - 1} more"
                "$cityText • ${group.countryCode}"
            } else {
                if (group.cityName.equals(group.countryCode, true)) {
                    group.cityName.capitalizeWords()
                } else {
                    ctx.getString(
                        R.string.hero_plan_and_account,
                        group.cityName.capitalizeWords(),
                        group.countryCode
                    )
                }
            }
            b.tvCountryName.text = locationText

            val hasSpeed = group.bestLinkSpeed > 0
            val hasLoad  = group.leastLoad > 0

            when {
                hasSpeed && hasLoad -> {
                    val speedStr = speedInfo(group.bestLinkSpeed).first
                    val (loadStr, loadAttr) = loadInfo(group.leastLoad)
                    b.latencyBadge.text = ctx.getString(R.string.two_argument_dot, speedStr, loadStr)
                    b.latencyBadge.setTextColor(fetchColor(ctx, loadAttr))
                    b.latencyBadge.visibility = View.VISIBLE
                }
                hasSpeed -> {
                    val (speedStr, speedAttr) = speedInfo(group.bestLinkSpeed)
                    b.latencyBadge.text = speedStr
                    b.latencyBadge.setTextColor(fetchColor(ctx, speedAttr))
                    b.latencyBadge.visibility = View.VISIBLE
                }
                hasLoad -> {
                    val (loadStr, loadAttr) = loadInfo(group.leastLoad)
                    b.latencyBadge.text = loadStr
                    b.latencyBadge.setTextColor(fetchColor(ctx, loadAttr))
                    b.latencyBadge.visibility = View.VISIBLE
                }
                else -> {
                    // No speed or load data available.
                    b.latencyBadge.visibility = View.GONE
                }
            }

            // Always cancel any running stats job before setting up the new state.
            cancelStatsJob()

            if (proxyStopped) {
                // Show a "Proxy Stopped" status row instead of live stats.
                showStoppedStatus()
                // Redirect every tap to the settings sheet so the user can restart.
                val stoppedClick = View.OnClickListener { listener.onProxyStoppedItemTapped() }
                b.serverCard.setOnClickListener(stoppedClick)
                b.infoIcon.setOnClickListener(stoppedClick)
            } else if (loadingTunnelKeys.contains(group.key)) {
                // WIN tunnel for this server is still being set up (getWinByKey returned null).
                // Show a "Connecting…" indicator with a gentle pulse.
                showTunnelLoadingStatus()
                b.infoIcon.setOnClickListener {
                    handleInfoIconClick(group)
                }
                b.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }
                // Always start polling
                statsJob = pollStatsLoop(group)
            } else {
                b.infoIcon.setOnClickListener {
                    handleInfoIconClick(group)
                }
                b.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }

                // Show "Checking…" immediately so the item is never left stranded
                showCheckingStatus()
                statsJob = pollStatsLoop(group)
            }
        }

        private fun handleInfoIconClick(group: ServerGroup) {
            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                io {
                    var animator: ObjectAnimator? = null
                    uiCtx {
                        try {
                            uiCtx {
                                animator =
                                    ObjectAnimator.ofFloat(b.infoIcon, "rotation", 0f, 360f).apply {
                                        duration = 600L
                                        repeatCount = ObjectAnimator.INFINITE
                                        interpolator = LinearInterpolator()
                                        start()
                                    }
                            }
                            VpnController.refreshRpnProxy(Backend.RpnWin)
                        } finally {
                            uiCtx {
                                animator?.cancel()
                                b.infoIcon.rotation = 0f
                            }
                        }
                    }
                    VpnController.refreshRpnProxy(Backend.RpnWin)
                    uiCtx {
                        animator?.cancel()
                        b.infoIcon.rotation = 0f
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
            b.tvServerIp.visibility = View.GONE
            b.statsLayout.visibility = View.VISIBLE
            b.tvServerStatus.text = ctx.getString(R.string.server_settings_proxy_stopped)
            b.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
            b.tvStatusSep.visibility = View.GONE
            b.tvAppsCount.visibility = View.GONE
            b.tvUptimeSep.visibility = View.GONE
            b.tvUptime.visibility = View.GONE
        }

        /**
         * Displays a pulsing "Connecting…" status row while the WIN tunnel for this
         * server is still being set up asynchronously after startProxy.
         *
         * The [pollStatsLoop] continues to run in parallel; the first successful
         * [applyStats] call will cancel the pulse and display real data.
         */
        private fun showTunnelLoadingStatus() {
            b.tvServerIp.visibility = View.GONE
            b.statsLayout.visibility = View.VISIBLE
            b.tvServerStatus.text = ctx.getString(R.string.lbl_connecting)
            b.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
            b.tvStatusSep.visibility = View.GONE
            b.tvAppsCount.visibility = View.GONE
            b.tvUptimeSep.visibility = View.GONE
            b.tvUptime.visibility = View.GONE
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
            b.tvServerIp.visibility = View.GONE
            b.statsLayout.visibility = View.VISIBLE
            b.tvServerStatus.text = ctx.getString(R.string.lbl_checking)
            b.tvServerStatus.setTextColor(fetchColor(ctx, R.attr.chipTextNeutral))
            b.tvStatusSep.visibility = View.GONE
            b.tvAppsCount.visibility = View.GONE
            b.tvUptimeSep.visibility = View.GONE
            b.tvUptime.visibility = View.GONE
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
        }

        private fun pollStatsLoop(group: ServerGroup): Job? {
            val lco = lifecycleOwner ?: return null
            // repeatOnLifecycle(STARTED) automatically suspends the inner block whenever
            // the lifecycle drops below STARTED
            return lco.lifecycleScope.launch {
                lco.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        withContext(Dispatchers.IO) { fetchAndApplyStats(group) }
                        delay(STATS_POLL_MS)
                    }
                }
            }
        }

        private suspend fun fetchAndApplyStats(group: ServerGroup) {
            try {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val id = group.proxyId()
                val statusPair = VpnController.getProxyStatusById(id)
                val stats = VpnController.getProxyStats(id)
                val apps = ProxyManager.getAppCountForProxy(id)

                // Fetch IP metadata for this server (cached by since-timestamp; live fetches
                // only happen when the tunnel connects for the first time or after a reconnect).
                val ip4 = fetchIpForGroup(group)

                Logger.v(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyStats for id: $id, config: $config, status: $statusPair, stats: $stats, apps/always-on: $apps/${config?.catchAll}")
                withContext(Dispatchers.Main) {
                    if (!b.root.isAttachedToWindow) return@withContext

                    applyStats(config, statusPair, stats, apps, ip4)
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyStats[${group.key}]: ${t.message}")
                // If stats fetch fails (e.g. tunnel not up yet), keep the "Checking…"
                // pulse visible and retry after the next poll interval.
            }
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
                val client = withTimeoutOrNull(3_000L) {
                    runCatching { VpnController.getRpnClientInfoById(key) }.getOrNull()
                }
                val ip4 = runCatching { client?.iP4() }.getOrNull()
                val ip6 = runCatching { client?.iP6() }.getOrNull()
                ip4 ?: ip6
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchIpForGroup[${group.key}]: ${t.message}")
                null
            }
        }

        private fun applyStats(
            config: CountryConfig?,
            statusPair: Pair<Long?, String>,
            stats: RouterStats?,
            appsCount: Int,
            ip4: IPMetadata? = null
        ) {
            if (config == null) {
                hideStats()
                return
            }
            // Stop any loading-pulse animation that may be running from showTunnelLoadingStatus().
            b.tvServerStatus.animate().cancel()
            b.tvServerStatus.alpha = 1f

            b.statsLayout.visibility = View.VISIBLE

            // Status chip
            val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
            b.tvServerStatus.text = getStatusText(status, stats, statusPair.second)
            b.tvServerStatus.setTextColor(fetchColor(ctx, getStatusColor(status, stats)))

            // Apps count  (R.string.add_remove_apps = "Add / Remove (%1$s apps)")
            b.tvStatusSep.visibility = View.VISIBLE
            b.tvAppsCount.visibility = View.VISIBLE
            if (config.catchAll) {
                b.tvAppsCount.text = ctx.getString(R.string.routing_remaining_apps)
            } else {
                b.tvAppsCount.text = ctx.getString(R.string.firewall_card_status_active, appsCount)
            }
            b.tvAppsCount.setTextColor(
                fetchColor(ctx, if (appsCount > 0 || config.catchAll) R.attr.primaryLightColorText else R.attr.accentBad)
            )

            // Uptime
            val uptime = getUpTime(config.key)
            b.tvUptimeSep.visibility = if (uptime.isNotEmpty()) View.VISIBLE else View.GONE
            b.tvUptime.visibility = if (uptime.isNotEmpty()) View.VISIBLE else View.GONE
            if (uptime.isNotEmpty()) b.tvUptime.text = uptime

            // Server IP row.
            // Show the actual IP label when available, fall back to "N/A"
            val ipText = ip4?.ip?.takeIf { it.isNotEmpty() }
            when {
                ipText != null -> {
                    b.tvServerIp.text = ipText
                    b.tvServerIp.visibility = View.VISIBLE
                }
                stats != null -> {
                    // Tunnel is up and returning stats but the IP metadata isn't available
                    // yet (or the backend didn't return one), show "N/A" so the row is
                    // never left empty.
                    b.tvServerIp.text = ctx.getString(R.string.lbl_not_available_short)
                    b.tvServerIp.visibility = View.VISIBLE
                }
                else -> {
                    // No stats yet, keep the IP row hidden until we have real data.
                    b.tvServerIp.visibility = View.GONE
                    b.tvUptimeSep.visibility = View.GONE
                }
            }
        }

        private fun hideStats() {
            b.statsLayout.visibility = View.GONE
            b.tvServerIp.visibility = View.GONE
        }

        @Suppress("UNUSED_PARAMETER")
        private fun getStatusColor(status: UIUtils.ProxyStatus?, stats: RouterStats?): Int {
            // For RPN proxies, trust the status enum directly.  The since/lastOK heuristic
            // (lastOK == 0 && since > WG_UPTIME_THRESHOLD → "Failing") is designed for
            // WireGuard where lastOK is the handshake timestamp.  For RPN, lastOK tracks
            // routed-traffic time; it is 0 on a healthy just-connected proxy that hasn't
            // yet forwarded a packet.  Applying the heuristic causes the card to oscillate
            // between green (Connected) during the brief startup window (< 5 s) and red
            // (Failing) once that window expires – even though the backend reports TOK.
            return when (status) {
                UIUtils.ProxyStatus.TOK -> R.attr.accentGood
                UIUtils.ProxyStatus.TUP,
                UIUtils.ProxyStatus.TZZ,
                UIUtils.ProxyStatus.TNT -> R.attr.chipTextNeutral
                else -> R.attr.chipTextNegative
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun getStatusText(
            status: UIUtils.ProxyStatus?,
            stats: RouterStats?,
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

        private fun getUpTime(id: String): CharSequence {
            val selectedSinceTs = RpnProxyManager.getSelectedSinceTs(id)
            return if (selectedSinceTs > 0L)
                DateUtils.getRelativeTimeSpanString(
                    selectedSinceTs, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
                )
            else context.getString(R.string.lbl_never)
        }

        /**
         * Returns (formattedSpeed, tierLabel, textColorAttr) for [linkMbps].
         *
         * Tier thresholds (same as CountryServerAdapter):
         *   ≥ 10 000 Mbps → Very Fast (chipTextPositive)
         *   ≥ 1 000 Mbps → Fast (accentGood)
         *   ≥ 100 Mbps → Good (chipTextNeutral)
         *   ≥ 10 Mbps → Moderate (chipTextNeutral)
         *   > 0 Mbps → Slow (chipTextNegative)
         */
        private fun speedInfo(linkMbps: Int): Pair<String, Int> {
            val formatted: String
            val attr: Int
            when {
                linkMbps >= 10_000 -> {
                    formatted = String.format(Locale.US, "%.0f Gbps", linkMbps / 1_000.0)
                    attr = R.attr.chipTextPositive
                }
                linkMbps >= 1_000 -> {
                    val gbps  = linkMbps / 1_000.0
                    formatted = if (gbps == gbps.toLong().toDouble())
                        String.format(Locale.US, "%.0f Gbps", gbps)
                    else
                        String.format(Locale.US, "%.1f Gbps", gbps)
                    attr = R.attr.accentGood
                }
                linkMbps >= 100 -> {
                    formatted = "$linkMbps Mbps"
                    attr = R.attr.chipTextNeutral
                }
                linkMbps >= 10 -> {
                    formatted = "$linkMbps Mbps"
                    attr = R.attr.chipTextNeutral
                }
                else -> {
                    formatted = "$linkMbps Mbps"
                    attr = R.attr.chipTextNegative
                }
            }
            return Pair(formatted, attr)
        }

        /**
         * Returns (displayText, textColorAttr) for [loadPercent].
         *
         * Tier thresholds (same as CountryServerAdapter):
         *   ≤ 20 → Light (chipTextPositive)
         *   ≤ 40 → Normal (accentGood)
         *   ≤ 60 → Busy (chipTextNeutral)
         *   ≤ 80 → Very Busy (chipTextNegative)
         *   > 80 → Overloaded (chipTextNegative)
         */
        private fun loadInfo(loadPercent: Int): Pair<String, Int> {
            val label: String
            val attr: Int
            when {
                loadPercent <= 20 -> {
                    label = "$loadPercent% · ${ctx.getString(R.string.server_load_light)}"
                    attr = R.attr.chipTextPositive
                }
                loadPercent <= 40 -> {
                    label = "$loadPercent% · ${ctx.getString(R.string.server_load_normal)}"
                    attr = R.attr.accentGood
                }
                loadPercent <= 60 -> {
                    label = "$loadPercent% · ${ctx.getString(R.string.server_load_busy)}"
                    attr = R.attr.chipTextNeutral
                }
                loadPercent <= 80 -> {
                    label = "$loadPercent% · ${ctx.getString(R.string.server_load_very_busy)}"
                    attr = R.attr.chipTextNegative
                }
                else -> {
                    label = "$loadPercent% · ${ctx.getString(R.string.server_load_overloaded)}"
                    attr = R.attr.chipTextNegative
                }
            }
            return Pair(label, attr)
        }

        private fun openServerDetail(server: CountryConfig) {
            val intent = Intent(ctx, RpnConfigDetailActivity::class.java)
            intent.putExtra(RpnConfigDetailActivity.INTENT_EXTRA_FROM_SERVER_SELECTION, true)
            intent.putExtra(RpnConfigDetailActivity.INTENT_EXTRA_CONFIG_KEY, server.key)
            ctx.startActivity(intent)
        }

        private fun io(f: suspend () -> Unit) {
            b.root.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) { f() }
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.Main) { f() }
        }

    }
}
