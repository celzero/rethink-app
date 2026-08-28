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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adapter for the list of currently-selected (active) VPN servers shown in ServerSelectionFragment
 */
class VpnServerAdapter(
    private val context: Context,
    private var serverGroups: List<ServerGroup>,
    private val listener: ServerSelectionListener
) : RecyclerView.Adapter<VpnServerAdapter.ServerViewHolder>(), KoinComponent {

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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        if (lifecycleOwner == null) lifecycleOwner = parent.findViewTreeLifecycleOwner()
        val b = ListItemVpnServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerViewHolder(b)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        if (lifecycleOwner == null) lifecycleOwner = holder.itemView.findViewTreeLifecycleOwner()
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
        private var lastRoutedAppJob: Job? = null


        fun bind(group: ServerGroup) {
            b.tvServerIp.visibility = View.GONE
            b.tvUptime.visibility = View.GONE
            b.tvCcSep.visibility = View.GONE
            b.tvUptimeSep.visibility = View.GONE
            b.tvLastRoutedApp.visibility = View.GONE

            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                b.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_refresh))
                b.refreshStopIcon.visibility = View.VISIBLE
                // AUTO server: show the vector ic_rpn_auto, hide the emoji text view
                b.tvFlag.text = ""
                b.ivFlagImage.visibility = View.VISIBLE
            } else {
                b.refreshStopIcon.visibility = View.VISIBLE
                b.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_cross))
                // Regular server: show the country flag emoji, hide the globe image
                b.tvFlag.text = group.flagEmoji
                b.ivFlagImage.visibility = View.GONE
            }

            val locationText = if (group.serverCount > 1) {
                val cities = group.servers.map { it.serverLocation }.distinct()
                val cityText = if (cities.size <= 2) cities.joinToString(", ").capitalizeWords()
                else "${cities.first().capitalizeWords()} +${cities.size - 1} more"
                cityText
            } else {
                group.cityName.capitalizeWords()
            }
            b.tvCountryName.text = locationText
            b.tvCountryCode.text = group.countryCode
            showAppsCount(group)

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
            } else if (loadingTunnelKeys.contains(group.key)) {
                // WIN tunnel for this server is still being set up (getWinByKey returned null).
                // Show a "Connecting…" indicator with a gentle pulse.
                showTunnelLoadingStatus()
                b.refreshStopIcon.setOnClickListener {
                    handleRefreshClick(group)
                }
                b.appsActionContainer.setOnClickListener { openServerDetail(group.getBestServer()) }
                b.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }
                // Always start polling
                statsJob = pollStatsLoop(group)
                lastRoutedAppJob = pollLastRoutedAppLoop(group)
                handleIpView(group)
            } else {
                b.refreshStopIcon.setOnClickListener {
                    handleRefreshClick(group)
                }
                b.appsActionContainer.setOnClickListener { openServerDetail(group.getBestServer()) }
                b.serverCard.setOnClickListener { openServerDetail(group.getBestServer()) }

                // Show "Checking…" immediately so the item is never left stranded
                showCheckingStatus()
                statsJob = pollStatsLoop(group)
                lastRoutedAppJob = pollLastRoutedAppLoop(group)
                handleIpView(group)
            }
        }

        private fun handleIpView(group: ServerGroup) {
            io {
                // Fetch IP metadata for this server
                val ip4 = fetchIpForGroup(group)
                uiCtx {
                    // Server IP row.
                    // Show the actual IP label when available, hide it otherwise
                    val ipText = ip4?.ip?.takeIf { it.isNotEmpty() }
                    if (ipText != null) {
                        b.tvServerIp.text = ipText
                        b.tvServerIp.visibility = View.VISIBLE
                        b.tvCcSep.visibility = View.VISIBLE
                    } else {
                        b.tvServerIp.visibility = View.GONE
                        b.tvCcSep.visibility = View.GONE
                    }
                }
            }
        }

        private fun handleRefreshClick(group: ServerGroup) {
            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                io {
                    val startTime = System.currentTimeMillis()
                    var animator: ObjectAnimator? = null
                    uiCtx {
                        animator = ObjectAnimator.ofFloat(b.refreshStopIcon, "rotation", 0f, 360f).apply {
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
                            animator?.cancel()
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
            b.statsLayout.visibility = View.VISIBLE
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
            b.statsLayout.visibility = View.VISIBLE
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
            b.statsLayout.visibility = View.VISIBLE
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
                val stats = VpnController.getProxyStats(id)

                Logger.v(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyStats for id: $id, config: $config, status: $statusPair, stats: $stats")
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx

                    applyStats(config, statusPair, stats)
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
                    group.key
                }
                val ct = connTrackerRepository.getLastRoutedConnectionForProxy(key)
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val apps = ProxyManager.getAppCountForProxy(group.proxyId())
                Logger.d(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyLastRoutedApp for id: ${group.proxyId()}, config: $config, apps: $apps, key: ${group.key}")
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyLastRoutedApp(ct)
                    applyAppsAction(config, apps)
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyLastRoutedApp[${group.key}]: ${t.message}")
            }
        }

        /**
         * Renders the "last routed app" row: "<app> • <relative time>".
         * Hidden when no connection has been routed through this server (yet).
         */
        private fun applyLastRoutedApp(ct: ConnectionTracker?) {
            val appName = ct?.appName?.trim().orEmpty()
            if (appName.isEmpty()) {
                b.tvLastRoutedApp.visibility = View.GONE
                return
            }
            val relTime = DateUtils.getRelativeTimeSpanString(
                ct?.timeStamp ?: 0L, System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
            )
            val txt = ctx.getString(
                R.string.two_argument_space, appName,
                ctx.getString(R.string.single_argument_parenthesis, relTime.toString())
            )
            b.tvLastRoutedApp.text = ctx.getString(R.string.temp_allow_desc_with_time, txt)
            b.tvLastRoutedApp.visibility = View.VISIBLE
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
            b.appsActionContainer.visibility = View.GONE
            b.actionDivider.visibility = View.GONE
            io {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                val apps = ProxyManager.getAppCountForProxy(group.proxyId())
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyAppsAction(config, apps)
                }
            }
        }

        private fun applyAppsAction(config: CountryConfig?, apps: Int) {
            if (config == null) {
                b.appsActionContainer.visibility = View.GONE
                b.actionDivider.visibility = View.GONE
                return
            }
            b.appsActionContainer.visibility = View.VISIBLE
            b.actionDivider.visibility = View.VISIBLE
            b.appsAction.text = if (config.catchAll) {
                ctx.getString(R.string.server_item_apps_all)
            } else {
                ctx.getString(R.string.server_item_apps_count, apps)
            }
        }

        private fun applyStats(
            config: CountryConfig?,
            statusPair: Pair<Int?, String>,
            stats: RouterStats?
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
            b.tvServerStatus.text = getStatusText(status, statusPair.second)
            b.tvServerStatus.setTextColor(fetchColor(ctx, getStatusColor(status)))

            // Uptime
            val uptime = getUpTime(stats)
            b.tvUptimeSep.visibility = if (uptime.isNotEmpty()) View.VISIBLE else View.GONE
            b.tvUptime.visibility = if (uptime.isNotEmpty()) View.VISIBLE else View.GONE
            if (uptime.isNotEmpty()) b.tvUptime.text = uptime
        }

        private fun hideStats() {
            b.statsLayout.visibility = View.GONE
            b.tvServerIp.visibility = View.GONE
        }

        private fun getStatusColor(status: UIUtils.ProxyStatus?): Int {
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
}
