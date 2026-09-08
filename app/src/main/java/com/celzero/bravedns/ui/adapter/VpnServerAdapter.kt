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
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.Toast
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
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_COUNTRY_CODE
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.activity.RpnConfigDetailActivity
import com.celzero.bravedns.ui.activity.WgIncludeAppsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
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

        /** Polling interval for IP */
        private const val SERVER_INFO_POLL_MS = 3000L

        /** Overlapping launcher-icon stack in the recently-routed-apps chip. */
        private const val ROUTED_APP_STACK_SIZE = 3

        /** Horizontal overlap between consecutive icons in the stack (dp). */
        private const val ROUTED_APP_ICON_STEP_DP = 11f
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
         * Called after the relay (hop) state of a single server was toggled from
         * this list. The host should re-derive any aggregate UI that depends on
         * the relay state of all servers (e.g. the Relay quick-settings tile).
         */
        fun onRelayToggled()
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
        private var serverInfoJob: Job? = null

        /** Latest known exit IPv4 for this item (null while unknown). */
        private var currentIpText: String? = null

        /** Latest known proxy status for this item (null until first stats poll). */
        private var currentProxyStatus: UIUtils.ProxyStatus? = null

        private fun renderStatusRow() {
            val showCheck =
                currentProxyStatus == UIUtils.ProxyStatus.TOK && !currentIpText.isNullOrEmpty()
            b.ivStatusCheck.visibility = if (showCheck) View.VISIBLE else View.GONE
            b.tvServerStatus.visibility = if (showCheck) View.GONE else View.VISIBLE
        }


        fun bind(group: ServerGroup) {
            b.tvServerIp.visibility = View.GONE
            b.lastRoutedAppContainer.visibility = View.GONE
            currentIpText = null
            currentProxyStatus = null
            b.ivStatusCheck.visibility = View.GONE
            b.tvServerStatus.visibility = View.VISIBLE
            setStatusLeadingSpacing(false)

            if (group.key.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                b.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_refresh))
                b.refreshStopIcon.visibility = View.VISIBLE
                // AUTO server: show the vector ic_rpn_auto, hide the emoji text view
                b.tvFlag.text = ""
                b.ivFlagImage.visibility = View.VISIBLE
                // AUTO's config carries no city; resolve the actual exit city from the
                // backend (mirrors RpnConfigDetailActivity#showServerInfo for tvHeroCity).
                resolveAutoCity(group)
            } else {
                b.refreshStopIcon.visibility = View.VISIBLE
                b.refreshStopIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_cross))
                // Regular server: show the country flag emoji, hide the globe image
                b.tvFlag.text = group.flagEmoji
                b.ivFlagImage.visibility = View.GONE
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
                handleIpView(group)
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

                // Show "Checking…" immediately so the item is never left stranded
                showCheckingStatus()
                statsJob = pollStatsLoop(group)
                lastRoutedAppJob = pollLastRoutedAppLoop(group)
                serverInfoJob = pollServerInfoLoop(group)
                handleIpView(group)
            }
        }

        private fun handleIpView(group: ServerGroup) {
            io {
                // Fetch IP metadata for this server
                val ip4 = fetchIpForGroup(group)
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyIp(ip4)
                }
            }
        }

        /**
         * Applies fetched IP metadata to the IP row and re-renders the status accordingly.
         */
        private fun applyIp(ip4: IPMetadata?) {
            // Show the actual IP label when available, hide it otherwise.
            val ipText = ip4?.ip?.takeIf { it.isNotEmpty() }
            currentIpText = ipText
            if (ipText != null) {
                b.tvServerIp.text = ipText
                b.tvServerIp.visibility = View.VISIBLE
            } else {
                b.tvServerIp.visibility = View.GONE
            }
            setStatusLeadingSpacing(ipText != null)
            renderStatusRow()
        }

        /**
         * Toggles the status label's leading margin/padding (the gap between it
         * and the IP label)
         */
        private fun setStatusLeadingSpacing(hasLeading: Boolean) {
            val dp = ctx.resources.displayMetrics.density
            val lp = b.tvServerStatus.layoutParams as android.widget.LinearLayout.LayoutParams
            val margin = if (hasLeading) (4 * dp).toInt() else 0
            if (lp.marginStart != margin) {
                lp.marginStart = margin
                b.tvServerStatus.layoutParams = lp
            }
            val pad = if (hasLeading) (6 * dp).toInt() else 0
            if (b.tvServerStatus.paddingStart != pad) {
                b.tvServerStatus.setPadding(
                    pad, b.tvServerStatus.paddingTop,
                    b.tvServerStatus.paddingEnd, b.tvServerStatus.paddingBottom
                )
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

                    applyStats(config, statusPair)
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
                val apps = ProxyManager.getAppCountForProxy(key)

                val iconEntries = recents.map { ct ->
                    val icon = ct.packageName.takeIf { it.isNotBlank() }?.let {
                        runCatching { Utilities.getIcon(ctx, it, ct.appName) }.getOrNull()
                    }
                    ct to icon
                }
                Logger.d(LOG_TAG_UI, "VpnServerAdapter fetchAndApplyLastRoutedApp for id: ${group.proxyId()}, config: $config, apps: $apps, key: ${group.key}")
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyLastRoutedApps(iconEntries)
                    applyAppsAction(config, apps)
                    applyRelayAction(config)
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
                    applyIp(ip4)
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
        private fun applyLastRoutedApps(entries: List<Pair<ConnectionTracker, Drawable?>>) {
            val firstName = entries.firstOrNull()?.first?.appName?.trim().orEmpty()
            if (firstName.isEmpty()) {
                b.lastRoutedAppContainer.visibility = View.GONE
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
            b.appsActionContainer.visibility = View.GONE
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
                return
            }
            b.appsActionContainer.visibility = View.VISIBLE
            b.appsAction.text = if (config.catchAll) {
                ctx.getString(R.string.server_item_apps_all)
            } else {
                ctx.getString(R.string.server_item_apps_count, apps)
            }
        }

        /**
         * Resolves the actual exit city for the AUTO server from the backend's
         * additional-info (same source as RpnConfigDetailActivity#showServerInfo).
         * Only the city is shown — no country code and no capitalisation applied.
         */
        private fun resolveAutoCity(group: ServerGroup) {
            io {
                val addl = runCatching { VpnController.getRpnAddlInfo(group.key) }.getOrNull()
                val city = addl?.city?.trim().orEmpty()
                if (city.isEmpty()) return@io
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    b.tvCountryName.text = context.getString(R.string.two_argument_dot, city.capitalizeWords(), AUTO_COUNTRY_CODE.capitalizeWords())
                }
            }
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
            b.relayActionContainer.visibility = View.GONE
            io {
                val config = RpnProxyManager.getCountryConfigByKey(group.key)
                uiCtx {
                    if (!b.root.isAttachedToWindow) return@uiCtx
                    applyRelayAction(config)
                }
            }
        }

        /**
         * Renders the Relay chip state: "🐇 Relay · On" with a positive background and
         * a check icon when the hop is active; "Relay · Off" with the default chip
         * background when inactive. Tapping toggles the hop for this server.
         */
        private fun applyRelayAction(config: CountryConfig?) {
            if (config == null || config.id.equals(AUTO_SERVER_ID, true)) {
                b.relayActionContainer.visibility = View.GONE
                return
            }
            b.relayActionContainer.visibility = View.VISIBLE
            val relayLabel = ctx.getString(R.string.cd_dns_crypt_relay_heading) + " · " +
                ctx.getString(if (config.hopEnabled) R.string.lbl_on else R.string.lbl_off)
            if (config.hopEnabled) {
                b.relayAction.text = ctx.getString(
                    R.string.two_argument_space,
                    ctx.getString(R.string.symbol_bunny),
                    relayLabel
                )
                b.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                b.relayActionContainer.backgroundTintList =
                    ColorStateList.valueOf(fetchColor(ctx, R.attr.chipBgColorPositive))
                b.relayIcon.visibility = View.VISIBLE
            } else {
                b.relayAction.text = relayLabel
                b.relayAction.setTextColor(fetchColor(ctx, R.attr.serverChipTextColor))
                b.relayActionContainer.backgroundTintList = null
                b.relayIcon.visibility = View.GONE
            }
        }

        /**
         * Enables/disables the relay (hop) for [group] via
         * [RpnProxyManager.setHopForWinServer] and re-renders the chip from the
         * freshly persisted config. The periodic poll re-applies the state as well,
         * so even a failed toggle is corrected on the next tick.
         */
        private fun toggleRelay(group: ServerGroup) {
            io {
                try {
                    val config = RpnProxyManager.getCountryConfigByKey(group.key) ?: return@io
                    val newState = !config.hopEnabled
                    RpnProxyManager.setHopForWinServer(group.key, newState)
                    val updated = RpnProxyManager.getCountryConfigByKey(group.key)
                    uiCtx {
                        if (!b.root.isAttachedToWindow) return@uiCtx
                        applyRelayAction(updated)
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
                    Logger.w(LOG_TAG_UI, "VpnServerAdapter toggleRelay[${group.key}]: ${t.message}")
                }
            }
        }

        private fun applyStats(
            config: CountryConfig?,
            statusPair: Pair<Int?, String>
        ) {
            if (config == null) {
                hideStats()
                return
            }
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
            setStatusLeadingSpacing(false)
            b.ivStatusCheck.visibility = View.GONE
            b.tvServerStatus.visibility = View.VISIBLE
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
                val proxyId = if (group.key.equals(AUTO_SERVER_ID, true)) {
                    VpnController.getWinProxyId()
                } else {
                    Backend.RpnWin + group.key
                }
                uiCtx {
                    if (proxyId.isNullOrBlank()) {
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
}
