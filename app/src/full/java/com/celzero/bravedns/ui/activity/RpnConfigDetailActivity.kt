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
import Logger.LOG_TAG_UI
import android.animation.ValueAnimator
import android.content.ClipData
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.withRotation
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.celzero.bravedns.ui.BaseActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.ActivityRpnConfigDetailBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.activity.RpnConfigDetailActivity.Companion.STATS_POLL_MS
import com.celzero.bravedns.ui.dialog.CountrySsidDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.SnackbarHelper
import com.celzero.bravedns.util.SsidPermissionManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.IPMetadata
import com.celzero.firestack.backend.RouterStats
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale
import kotlin.math.abs

/**
 * Detail screen for a server-provided WireGuard / WIN proxy.
 *
 * Hero banner: flag emoji + country name + city + server-key chip.
 *
 * Stats card: The poll runs every [STATS_POLL_MS] milliseconds while the activity
 * is resumed, and is canceled on pause so it does not drain battery in the background.
 */
class RpnConfigDetailActivity : BaseActivity(R.layout.activity_rpn_config_detail) {
    private val b by viewBinding(ActivityRpnConfigDetailBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private var configKey: String = ""
    private var countryConfig: CountryConfig? = null
    private var winIdentifier: String? = null

    /** Coroutine that polls VpnController every [STATS_POLL_MS] ms. */
    private var statsJob: Job? = null
    /** Looping spin animator for the refresh chip icon. */
    private var chipAnimator: ValueAnimator? = null

    // SSID permission callback
    private val ssidPermissionCallback = object : SsidPermissionManager.PermissionCallback {
        override fun onPermissionsGranted() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions granted")
            ui { refreshSsidSection() }
        }
        override fun onPermissionsDenied() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions denied")
            ui { showPermissionDeniedDialog() }
        }
        override fun onPermissionsRationale() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions rationale")
            showSsidPermissionExplanationDialog()
        }
    }

    companion object {
        const val INTENT_EXTRA_FROM_SERVER_SELECTION = "FROM_SERVER_SELECTION"
        const val INTENT_EXTRA_CONFIG_KEY = "CONFIG_KEY"

        /** Polling interval for live stats. */
        private const val STATS_POLL_MS = 2_000L
    }

    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        configKey = intent.getStringExtra(INTENT_EXTRA_CONFIG_KEY) ?: ""
        applyScrollPadding()
    }

    private fun applyScrollPadding() {
        b.nestedScroll.post {
            b.nestedScroll.setPadding(
                b.nestedScroll.paddingLeft,
                0,
                b.nestedScroll.paddingRight,
                b.nestedScroll.paddingBottom
            )
        }
    }

    override fun onResume() {
        super.onResume()
        init()
    }

    override fun onPause() {
        super.onPause()
        // Stop polling to conserve battery when screen is not visible.
        cancelStatsJob()
        chipAnimator?.cancel()
        chipAnimator = null
    }

    private fun init() {
        io {
            val proxy = if (configKey.isBlank() || configKey.contains(AUTO_SERVER_ID, ignoreCase = true))
                VpnController.getWinByKey("")
            else
                VpnController.getWinByKey(configKey)

            uiCtx {
                if (configKey.isBlank() || proxy == null) {
                    showInvalidConfigDialog()
                    return@uiCtx
                }
                populateHeroBanner()
                observeAppCount(configKey)
                loadConfigSettings(configKey)
                setupHeaderUI()
            }
        }

        b.lockdownTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.firewall_rule_global_lockdown),
                getString(R.string.symbol_lockdown)
            )

        b.catchAllTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.catch_all_wg_dialog_title),
                getString(R.string.symbol_lightening)
            )

        val mobileOnlyExperimentalTxt = getString(
            R.string.two_argument_space,
            getString(R.string.wg_setting_use_on_mobile),
            getString(R.string.lbl_experimental)
        )
        b.useMobileTitleTv.text =
            getString(
                R.string.two_argument_space,
                mobileOnlyExperimentalTxt,
                getString(R.string.symbol_mobile)
            )
        val ssidExperimentalTxt = getString(
            R.string.two_argument_space,
            getString(R.string.wg_setting_ssid_title),
            getString(R.string.lbl_experimental)
        )
        b.ssidTitleTv.text = getString(
            R.string.two_argument_space,
            ssidExperimentalTxt,
            getString(R.string.symbol_id)
        )
        b.ssidDescTv.text = getString(R.string.wg_setting_ssid_desc, getString(R.string.lbl_ssids))
    }

    /**
     * Populates flag emoji, country name, city subtitle and key chip in
     * the hero banner. Stats polling starts immediately; client IPs are
     * resolved separately in a background coroutine so VpnController
     * stats appear without delay.
     */
    private fun populateHeroBanner() {
        // Placeholder while we fetch from DB.
        b.configNameText.text = ""
        b.tvHeroCity.text = ""
        b.tvHeroFlag.text = "\uD83C\uDF10" // globe
        b.tvHeroWho.text = ""
        winIdentifier = null
        b.chipHeroStats.visibility = View.GONE

        // Show inline shimmer for client IPs (stats table is already visible).
        showClientIpShimmer()

        io {
            val config = RpnProxyManager.getCountryConfigByKey(configKey)

            uiCtx {
                // Cache for use in the stats table and SSID.
                countryConfig = config

                // SSID section needs countryConfig.
                setupSsidSection(configKey)
                // Banner.
                if (config != null) {
                    b.tvHeroFlag.text = config.flagEmoji
                    b.configNameText.text = config.countryName
                    val city = config.city.ifBlank { config.serverLocation }
                    b.tvHeroCity.text = city.ifBlank { config.cc }
                } else {
                    b.tvHeroFlag.text = "\uD83C\uDF10"
                    b.configNameText.text = configKey.ifBlank { getString(R.string.lbl_server_config) }
                    b.tvHeroCity.text     = ""
                }
                // Update the collapsing toolbar title now that we have the real config name.
                b.collapsingToolbar.title = b.configNameText.text

                startStatsPolling(configKey)
            }

            resolveClientIps(configKey)
        }
    }

    /**
     * Resolves client tunnel IPs (and all [IPMetadata]) on IO and applies them on the main thread.
     * Runs independently of stats polling so the table renders fast.
     */
    private suspend fun resolveClientIps(id: String) {
        var ip4Meta: IPMetadata? = null
        var ip6Meta: IPMetadata? = null
        var sinceTs: Long
        try {
            val pid = if (id.contains(AUTO_SERVER_ID, ignoreCase = true)) {
                Backend.RpnWin
            } else {
                Backend.RpnWin + id
            }
            sinceTs = VpnController.getProxyStats(pid)?.since ?: 0L

            if (DEBUG) Logger.d(
                LOG_TAG_UI,
                "resolveClientIps[$id]: live fetch, sinceTs=$sinceTs"
            )
            // GoVpnAdapter handles AUTO and empty-string ids centrally; pass id as-is.
            val client = VpnController.getRpnClientInfoById(id)
            ip4Meta = client?.iP4()
            ip6Meta = client?.iP6()
            Logger.v(LOG_TAG_UI, "client ips resolved for $id: ip4=${ip4Meta?.ip} ip6=${ip6Meta?.ip}, sinceTs=$sinceTs")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "failed to resolve client ips: ${e.message}")
        }
        uiCtx { applyClientIps(ip4Meta, ip6Meta) }
    }

    /** Show inline shimmer placeholders for client IPv4/IPv6 value cells. */
    private fun showClientIpShimmer() {
        b.shimmerIpv4.visibility = View.VISIBLE
        b.shimmerIpv4.startShimmer()
        b.valueIpv4.visibility = View.GONE

        b.shimmerIpv6.visibility = View.VISIBLE
        b.shimmerIpv6.startShimmer()
        b.valueIpv6.visibility = View.GONE
    }

    /**
     * Replaces the inline shimmer with rich IP + metadata text.
     * ASN / location / providerUrl are embedded inside
     */
    private fun applyClientIps(ip4: IPMetadata?, ip6: IPMetadata?) {
        val na = getString(R.string.lbl_not_available_short)

        // IPv4
        b.shimmerIpv4.stopShimmer()
        b.shimmerIpv4.visibility = View.GONE
        b.valueIpv4.visibility = View.VISIBLE
        b.valueIpv4.text = ip4
            ?.takeIf { it.ip?.isNotBlank() == true }
            ?.let { buildIpDetailSpan(it) }
            ?: na

        // ipv6, hide the entire row if unavailable
        b.shimmerIpv6.stopShimmer()
        b.shimmerIpv6.visibility = View.GONE
        val ip6Addr = ip6?.ip?.takeIf { it.isNotBlank() }
        if (ip6 != null && ip6Addr != null) {
            b.rowIpv6.visibility = View.VISIBLE
            b.valueIpv6.visibility = View.VISIBLE
            b.valueIpv6.text = buildIpDetailSpan(ip6)
        } else {
            b.rowIpv6.visibility = View.GONE
        }
    }

    /**
     * Builds a multi-line [SpannableStringBuilder] for a single [IPMetadata].
     *
     * ```
     * 10.0.0.1
     * ASN  AS13335 · Cloudflare Inc · net.cloudflare.com
     * LOC  Frankfurt · 50.1109°, 8.6821°
     * via  cloudflare.com
     * ```
     */
    private fun buildIpDetailSpan(meta: IPMetadata): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val labelColor = fetchColor(this, R.attr.primaryLightColorText)

        fun styleLabel(start: Int, end: Int) {
            sb.setSpan(ForegroundColorSpan(labelColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.80f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun appendLine(label: String, value: String) {
            if (value.isBlank()) return
            sb.append("\n")
            val ls = sb.length
            sb.append(label)
            styleLabel(ls, sb.length)
            sb.append("  $value")
        }

        // ip
        val ipStart = 0
        sb.append(meta.ip ?: "")
        val ipEnd = sb.length
        sb.setSpan(StyleSpan(Typeface.BOLD),  ipStart, ipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan("monospace"), ipStart, ipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.07f),   ipStart, ipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // asn
        val asnParts = buildList {
            val asn = meta.asn ?: ""
            val org = meta.asnOrg ?: ""
            val dom = meta.asnDom ?: ""
            if (asn.isNotBlank()) add("AS$asn")
            if (org.isNotBlank()) add(org)
            if (dom.isNotBlank()) add(dom)
        }
        if (asnParts.isNotEmpty()) appendLine("ASN", asnParts.joinToString("  ·  "))

        // loc
        val locParts = buildList {
            val city = meta.city ?: ""
            val lat = meta.lat
            val lon = meta.lon
            if (city.isNotBlank()) add(city)
            if (lat != 0.0 || lon != 0.0) add(String.format(Locale.US, "%.4f°, %.4f°", lat, lon))
        }
        if (locParts.isNotEmpty()) appendLine("LOC", locParts.joinToString("  ·  "))

        // provider url
        val providerUrl = meta.providerURL ?: ""
        if (providerUrl.isNotBlank()) {
            val display = providerUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
            appendLine("VIA", display)
        }

        return sb
    }


    private fun cancelStatsJob() {
        if (statsJob?.isActive == true) statsJob?.cancel()
        statsJob = null
    }

    /**
     * Launches a coroutine that fetches and applies live stats every
     * [STATS_POLL_MS] ms. Client IPs are resolved separately by
     * [resolveClientIps]: this method only handles VpnController stats.
     */
    private fun startStatsPolling(id: String) {
        cancelStatsJob()
        statsJob = io {
            while (true) {
                try {
                    fetchAndApplyStats(id)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "stats poll error: ${e.message}")
                }
                delay(STATS_POLL_MS)
            }
        }
    }

    private suspend fun fetchAndApplyStats(id: String) {
        // For AUTO use the live win proxy ID from the tunnel so the stats lookup targets the
        // real proxy entry rather than a hardcoded wildcard.  Fall back to the wildcard only
        val pid = if (id.contains(AUTO_SERVER_ID, ignoreCase = true)) {
            Backend.RpnWin
        } else {
            Backend.RpnWin + id
        }
        val statusPair = VpnController.getProxyStatusById(pid)
        val stats = VpnController.getProxyStats(pid)
        if (winIdentifier.isNullOrEmpty()) {
            winIdentifier = VpnController.getWinIdentifier()
        }
        val who = winIdentifier
        val config = countryConfig
        // Use the time when this server key was selected by the user, not the VPN uptime.
        val selectedSinceTs = RpnProxyManager.getSelectedSinceTs(id)

        uiCtx {
            applyStats(statusPair, stats, config, who, selectedSinceTs)
        }
    }

    /**
     * Populates every row of the stats table from live data (except client
     * IPs which are populated by [applyClientIps]).  Runs on the main thread.
     *
     * @param selectedSinceTs epoch-ms when this server key was selected by the user
     *   (supplied by [RpnProxyManager.getSelectedSinceTs]); shown as the "active since" value.
     */
    private fun applyStats(
        statusPair: Pair<Long?, String>,
        stats: RouterStats?,
        config: CountryConfig?,
        who: String?,
        selectedSinceTs: Long
    ) {
        val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
        val statusText = buildStatusText(ps, statusPair.second)
        val statusColor = buildStatusColor(ps)
        b.valueStatus.text = statusText
        b.valueStatus.setTextColor(fetchColor(this, statusColor))

        if (who.isNullOrEmpty()) {
            b.tvHeroWho.visibility = View.GONE
        } else {
            b.tvHeroWho.visibility = View.VISIBLE
            b.tvHeroWho.text = who
        }

        val rx = stats?.rx ?: 0L
        val tx = stats?.tx ?: 0L
        b.valueRx.visibility = View.VISIBLE
        b.valueTx.visibility = View.VISIBLE
        b.valueRx.text = getString(R.string.symbol_download, Utilities.humanReadableByteCount(rx, true))
        b.valueTx.text = getString(R.string.symbol_upload, Utilities.humanReadableByteCount(tx, true))
        b.rowHeroRxtx.visibility = View.VISIBLE

        val lastOK = stats?.lastOK ?: 0L
        b.valueLastOk.text = if (lastOK > 0L)
            DateUtils.getRelativeTimeSpanString(
                lastOK, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
            )
        else getString(R.string.lbl_never)

        // Show when the user selected this server, not the VPN tunnel's uptime.
        b.valueSince.text = if (selectedSinceTs > 0L)
            DateUtils.getRelativeTimeSpanString(
                selectedSinceTs, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
            )
        else getString(R.string.lbl_never)

        val loadPct = config?.load ?: 0
        val linkMbps = config?.link ?: 0
        buildLoadSpeedText(loadPct, linkMbps)

        // only shown when proxy is in a failing state.
        val isFailing = isFailing(ps)
        if (isFailing && (rx == 0L && tx == 0L && selectedSinceTs > 0L)) {
            b.rowErrors.visibility = View.VISIBLE
            b.dividerErrors.visibility = View.VISIBLE
            b.valueErrors.text = getString(R.string.status_failing)
        } else {
            b.rowErrors.visibility = View.GONE
            b.dividerErrors.visibility = View.GONE
        }
    }


    private fun buildStatusText(
        status  : UIUtils.ProxyStatus?,
        errMsg  : String?
    ): String {
        if (status == null) {
            return if (!errMsg.isNullOrEmpty())
                "${getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)} ($errMsg)"
            else
                getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
        }
        if (status == UIUtils.ProxyStatus.TPU) {
            return getString(UIUtils.getProxyStatusStringRes(status.id))
                .replaceFirstChar(Char::titlecase)
        }
        if (isFailing(status)) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }
        val base = getString(UIUtils.getProxyStatusStringRes(status.id))
            .replaceFirstChar(Char::titlecase)
        return base
    }

    private fun buildStatusColor(status: UIUtils.ProxyStatus?): Int {
        return when {
            status == null -> R.attr.primaryLightColorText
            isFailing(status) -> R.attr.chipTextNegative
            status == UIUtils.ProxyStatus.TOK -> R.attr.accentGood
            status == UIUtils.ProxyStatus.TUP ||
            status == UIUtils.ProxyStatus.TZZ ||
            status == UIUtils.ProxyStatus.TNT -> R.attr.chipTextNeutral
            else -> R.attr.chipTextNegative
        }
    }

    /**
     * Returns true only when the status enum explicitly signals a failure (TKO, END, or any
     * unknown non-success value).
     *
     * For RPN the status enum IS the authoritative source:
     *   TOK / TUP / TZZ / TNT → not failing (healthy or transitioning)
     *   TKO / END / unknown   → failing
     */
    private fun isFailing(status: UIUtils.ProxyStatus?): Boolean {
        if (status == null || status == UIUtils.ProxyStatus.TPU) return false
        return when (status) {
            UIUtils.ProxyStatus.TOK,
            UIUtils.ProxyStatus.TUP,
            UIUtils.ProxyStatus.TZZ,
            UIUtils.ProxyStatus.TNT -> false
            else -> true  // TKO, END, and any future explicitly-bad status
        }
    }

    /**
     * Returns a human-readable combined load + speed string, e.g.
     * "35% · Normal"
     * "1 Gbps · Fast"
     */
    private fun buildLoadSpeedText(loadPct: Int, linkMbps: Int) {
        val healthText: String
        if (loadPct >= 0) {
            val tier = when {
                loadPct <= 20 -> getString(R.string.server_load_light)
                loadPct <= 40 -> getString(R.string.server_load_normal)
                loadPct <= 60 -> getString(R.string.server_load_busy)
                loadPct <= 80 -> getString(R.string.server_load_very_busy)
                else -> getString(R.string.server_load_overloaded)
            }
            healthText = "$loadPct% · $tier"
        } else {
            healthText = getString(R.string.lbl_not_available_short)
        }

        val speedText: String
        if (linkMbps > 0) {
            val formatted = when {
                linkMbps >= 10_000 -> String.format(Locale.US, "%.0f Gbps", linkMbps / 1_000.0)
                linkMbps >=  1_000 -> {
                    val g = linkMbps / 1_000.0
                    if (g == g.toLong().toDouble())
                        String.format(Locale.US, "%.0f Gbps", g)
                    else
                        String.format(Locale.US, "%.1f Gbps", g)
                }
                else -> "$linkMbps Mbps"
            }
            speedText = formatted
        } else {
            speedText = getString(R.string.lbl_not_available_short)
        }

        // Compose hero-banner stats chip: "1 Gbps · 35% · Normal"
        val parts = listOf(speedText, healthText).filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            b.chipHeroStats.text = parts.joinToString(" · ")
            b.chipHeroStats.visibility = View.VISIBLE
        } else {
            b.chipHeroStats.visibility = View.GONE
        }
    }

    private fun observeAppCount(configKey: String) {
        if (configKey.isBlank()) return
        // proxyId stored in ProxyApplicationMapping is always Backend.RpnWin + configKey.
        val pid = Backend.RpnWin + configKey
        mappingViewModel.getAppCountById(pid).observe(this) { count ->
            // Don't override the "All apps" state when catch-all is active
            if (b.catchAllCheck.isChecked) return@observe
            val c = count ?: 0
            b.appsLabel.text = getString(R.string.two_argument_parenthesis, getString(R.string.apps_info_title), c)
            b.appsLabel.setTextColor(
                fetchColor(this, if (c > 0) R.attr.accentGood else R.attr.accentBad)
            )
        }
    }

    private fun loadConfigSettings(key: String) {
        if (key.isBlank()) {
            b.otherSettingsCard.visibility = View.GONE
            b.mobileSsidSettingsCard.visibility = View.GONE
            return
        }
        io {
            val config = RpnProxyManager.getCountryConfigByKey(key)
            uiCtx {
                if (config != null) {
                    b.lockdownCheck.isChecked = config.lockdown
                    b.catchAllCheck.isChecked = config.catchAll
                    b.useMobileCheck.isChecked = config.mobileOnly
                    b.ssidCheck.isChecked = config.ssidBased
                    b.hopCheck.isChecked = config.hopEnabled
                    b.otherSettingsCard.visibility = View.VISIBLE
                    b.mobileSsidSettingsCard.visibility = View.VISIBLE
                    // Update apps section immediately based on catchAll state
                    if (config.catchAll) {
                        b.applicationsBtn.isEnabled = false
                        b.applicationsBtn.alpha = 0.5f
                        b.appsLabel.setTextColor(fetchColor(this, R.attr.primaryTextColor))
                        b.appsLabel.text = getString(R.string.lbl_all_apps)
                    }
                    if (config.id == AUTO_SERVER_ID) {
                        // Hide hop settings for AUTO since it will be the src for all other
                        // configs
                        b.hopRl.visibility = View.GONE
                    }
                } else {
                    showInvalidConfigDialog()
                }
                setupClickListeners(key)
            }
        }
    }

    private fun setupClickListeners(key: String) {
        b.applicationsBtn.setOnClickListener { openAppsDialog() }
        b.hopBtn.setOnClickListener { openHopDialog() }
        b.logsBtn.setOnClickListener { openLogsDialog(key) }

        b.configIdText.setOnClickListener {
            initiateReconnect(key)
        }
        b.valueWho.setOnClickListener(null)
        b.tvHeroWho.setOnClickListener {
            val text = b.tvHeroWho.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("who", text))
            Utilities.showToastUiCentered(
                this,
                getString(R.string.copied_clipboard),
                Toast.LENGTH_SHORT
            )
        }

        if (configKey.isBlank()) {
            b.otherSettingsCard.visibility = View.GONE
            b.mobileSsidSettingsCard.visibility = View.GONE
            return
        }

        b.hopCheck.setOnCheckedChangeListener { _, isChecked ->
            io {
                RpnProxyManager.setHopForWinServer(configKey, isChecked)
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        if (isChecked) "Hop mode enabled" else "Hop mode disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        b.catchAllCheck.setOnCheckedChangeListener { _, isChecked ->
            io {
                RpnProxyManager.setCatchAllForWinServer(configKey, isChecked)
                uiCtx {
                    // Update apps section immediately to reflect the new catch-all state
                    b.applicationsBtn.isEnabled = !isChecked
                    b.applicationsBtn.alpha = if (isChecked) 0.5f else 1.0f
                    if (isChecked) {
                        b.appsLabel.setTextColor(fetchColor(this, R.attr.primaryTextColor))
                        b.appsLabel.text = getString(R.string.lbl_all_apps)
                    } else {
                        observeAppCount(configKey)
                    }
                    Utilities.showToastUiCentered(
                        this,
                        if (isChecked) "Catch all mode enabled" else "Catch all mode disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        b.lockdownCheck.setOnCheckedChangeListener { _, isChecked ->
            io {
                RpnProxyManager.setLockdownForWinServer(configKey, isChecked)
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        if (isChecked) "Lockdown mode enabled" else "Lockdown mode disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        b.useMobileCheck.setOnCheckedChangeListener { _, isChecked ->
            io {
                RpnProxyManager.setMobileOnlyForWinServer(configKey, isChecked)
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        if (isChecked) "Mobile data only enabled" else "Mobile data only disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        b.catchAllRl.setOnClickListener { b.catchAllCheck.performClick() }
        b.useMobileRl.setOnClickListener { b.useMobileCheck.performClick() }
        // ssidFilterRl click listener and ssidCheck listener are managed by setupSsidSectionUI
    }

    private fun initiateReconnect(key: String) {
        setRefreshReconnectEnabled(false)
        io {
            // GoVpnAdapter handles AUTO and empty-string ids centrally
            val reconnect = if (key.contains(AUTO_SERVER_ID, ignoreCase = true)) {
                VpnController.reconnectRpnProxy("")
            } else {
                VpnController.reconnectRpnProxy(key)
            }
            uiCtx {
                setRefreshReconnectEnabled(true)
                if (reconnect) {
                    Utilities.showToastUiCentered(this, getString(R.string.dc_refresh_toast), Toast.LENGTH_SHORT)
                } else {
                    Utilities.showToastUiCentered(this, "Failed to reconnect", Toast.LENGTH_SHORT)
                }
            }
        }
    }

    private fun setRefreshReconnectEnabled(enabled: Boolean) {
        val targetAlpha = if (enabled) 1f else 0.40f
        b.configIdText.isEnabled = enabled
        b.configIdText.isClickable = enabled
        b.configIdText.animate().alpha(targetAlpha).setDuration(160).start()
        if (enabled) stopChipIconAnimation() else startChipIconAnimation()
    }

    /** Starts a continuous clockwise spin on the chip's icon only. */
    private fun startChipIconAnimation() {
        chipAnimator?.cancel()
        val raw = b.configIdText.chipIcon
        val spinning: RotatingDrawable = raw as? RotatingDrawable ?: RotatingDrawable(raw ?: return).also { b.configIdText.chipIcon = it }
        spinning.rotation = 0f
        chipAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { spinning.rotation = it.animatedValue as Float }
            start()
        }
    }

    /**
     * Stops the spin and eases the chip icon back to 0° so it doesn't
     * snap abruptly when the reconnect operation completes.
     */
    private fun stopChipIconAnimation() {
        chipAnimator?.cancel()
        chipAnimator = null
        val icon = b.configIdText.chipIcon as? RotatingDrawable ?: return
        ValueAnimator.ofFloat(icon.rotation, 0f).apply {
            duration = 200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { icon.rotation = it.animatedValue as Float }
            start()
        }
    }

    /**
     * A [DrawableWrapper] that applies a canvas rotation around its own centre
     * during [draw], leaving the host view's background and tint untouched.
     */
    private class RotatingDrawable(drawable: Drawable) : DrawableWrapper(drawable.mutate()) {
        var rotation: Float = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.withRotation(rotation, b.exactCenterX(), b.exactCenterY()) {
                super@RotatingDrawable.draw(this)
            }
        }
    }

    private fun showInvalidConfigDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.lbl_wireguard))
            .setMessage(getString(R.string.config_invalid_desc))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { _, _ -> finish() }
            .create().show()
    }

    private fun openHopDialog() {
        Utilities.showToastUiCentered(this, "Configure Hops - Coming Soon", Toast.LENGTH_SHORT)
    }

    private fun openLogsDialog(proxyId: String) {
        var proxyId = proxyId
        if (proxyId.isEmpty()) {
            Utilities.showToastUiCentered(this, getString(R.string.bad_config_reason_invalid_key), Toast.LENGTH_SHORT)
            return
        }
        if (proxyId.contains(AUTO_SERVER_ID, ignoreCase = true)) {
            io {
                proxyId = VpnController.getWinProxyId() ?: ""
                uiCtx {
                    if (proxyId.isBlank()) {
                        Utilities.showToastUiCentered(this, getString(R.string.bad_config_reason_invalid_key), Toast.LENGTH_SHORT)
                    } else {
                        invokeNetworkLogs(proxyId)
                    }
                }
            }
        } else {
            invokeNetworkLogs(proxyId)
        }
    }

    private fun invokeNetworkLogs(proxyId: String) {
        val intent = Intent(this, NetworkLogsActivity::class.java)
        val query = RULES_SEARCH_ID_RPN + proxyId
        intent.putExtra(Constants.SEARCH_QUERY, query)
        startActivity(intent)
    }

    private fun openAppsDialog() {
        if (configKey.isBlank()) {
            Logger.e(LOG_TAG_UI, "openAppsDialog: configKey blank or proxy null")
            return
        }
        val proxyId = Backend.RpnWin + configKey
        val cc = countryConfig
        val proxyName = when {
            cc != null && cc.city.isNotBlank() -> "${cc.cc} - ${cc.city}"
            cc != null && cc.name.isNotBlank() -> cc.name
            else -> configKey
        }
        val adapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        // Remove any observers registered by previous openAppsDialog()
        mappingViewModel.apps.removeObservers(this)
        mappingViewModel.apps.observe(this) { adapter.submitData(lifecycle, it) }
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) themeId = R.style.App_Dialog_NoDim
        val dlg = WgIncludeAppsDialog(this, adapter, mappingViewModel, themeId, proxyId, proxyName)
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()
    }

    private fun setupHeaderUI() {
        // Title will be set in populateHeroBanner() once the config name is loaded asynchronously.
        b.collapsingToolbar.title = ""
        b.collapsingToolbar.titleCollapseMode = CollapsingToolbarLayout.TITLE_COLLAPSE_MODE_SCALE
        b.collapsingToolbar.setExpandedTitleColor(Color.TRANSPARENT)
        b.collapsingToolbar.setCollapsedTitleTextColor(fetchColor(this, R.attr.primaryTextColor))
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // Fade out the entire hero banner content as the toolbar collapses so that
        // other views do not peek through when fully collapsed.
        b.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) return@addOnOffsetChangedListener
            val fraction = 1f - (abs(verticalOffset).toFloat() / totalScrollRange.toFloat())
            val alpha = (fraction / 0.6f).coerceIn(0f, 1f)
            b.heroContent.alpha = alpha
            b.heroContent.visibility = if (alpha == 0f) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun setupSsidSection(cc: String) {
        io {
            countryConfig = RpnProxyManager.getCountryConfigByKey(cc)
            uiCtx { setupSsidSectionUI(countryConfig) }
        }
    }

    private fun setupSsidSectionUI(config: CountryConfig?) {
        val sw = b.ssidCheck
        val displayGroup: LinearLayout = b.ssidDisplayGroup
        val editBtn: AppCompatImageView = b.ssidEditBtn
        val valueTv = b.ssidValueTv
        val layout = b.ssidFilterRl
        val permissionErrorLayout: LinearLayout = b.ssidPermissionErrorLayout
        val locationErrorLayout: LinearLayout = b.ssidLocationErrorLayout

        if (config == null) {
            displayGroup.visibility = View.GONE
            hideErrorLayouts(permissionErrorLayout, locationErrorLayout)
            sw.isEnabled = false
            Logger.w(LOG_TAG_UI, "setupSsidSectionUI: config is null for $configKey")
            return
        }

        // Check if device supports required features
        if (!SsidPermissionManager.isDeviceSupported(this)) {
            displayGroup.visibility = View.GONE
            hideErrorLayouts(permissionErrorLayout, locationErrorLayout)
            sw.isEnabled = false
            layout.visibility = View.GONE
            Logger.w(LOG_TAG_UI, "setupSsidSectionUI: device not supported for SSID feature")
            return
        }

        // Always keep switch and layout enabled
        sw.isEnabled = true
        layout.visibility = View.VISIBLE

        val hasPermissions = SsidPermissionManager.hasRequiredPermissions(this)
        val isLocationEnabled = SsidPermissionManager.isLocationEnabled(this)

        val enabled = config.ssidBased
        val ssidItems = SsidItem.parseStorageList(config.ssids)
        sw.isChecked = enabled

        if (enabled && hasPermissions && isLocationEnabled) {
            // SSID enabled and all permissions/location available — show current values
            if (ssidItems.isEmpty()) {
                val allTxt = getString(
                    R.string.single_argument_parenthesis,
                    getString(R.string.two_argument_space, getString(R.string.lbl_all), getString(R.string.lbl_ssids))
                )
                valueTv.text = allTxt
            } else {
                valueTv.text = ssidItems.joinToString(", ") { "${it.name} (${it.type.getDisplayName(this)})" }
            }
            displayGroup.visibility = View.VISIBLE
        } else {
            displayGroup.visibility = View.GONE
        }

        if (DEBUG) Logger.d(
            LOG_TAG_UI,
            "setupSsidSectionUI: hasPermissions=$hasPermissions, locationEnabled=$isLocationEnabled, checked=${sw.isChecked}"
        )
        updateErrorLayouts(hasPermissions, isLocationEnabled, permissionErrorLayout, locationErrorLayout)

        sw.setOnCheckedChangeListener { _, isChecked ->
            val currentHasPermissions = SsidPermissionManager.hasRequiredPermissions(this)
            val currentLocationEnabled = SsidPermissionManager.isLocationEnabled(this)

            if (isChecked && !currentHasPermissions) {
                SsidPermissionManager.checkAndRequestPermissions(this, ssidPermissionCallback)
                Logger.d(LOG_TAG_UI, "SSID permissions not granted, requesting...")
                return@setOnCheckedChangeListener
            }

            if (isChecked && !currentLocationEnabled) {
                showLocationEnableDialog()
                Logger.d(LOG_TAG_UI, "Location services not enabled, prompting user...")
                return@setOnCheckedChangeListener
            }

            // Persist the new state
            io { RpnProxyManager.updateSsidBased(configKey, isChecked) }

            if (isChecked && currentHasPermissions && currentLocationEnabled) {
                if (persistentState.enableStabilityDependentSettings()) {
                    SnackbarHelper.showStabilityProgram(b.root, persistentState)
                }
                // Load and display the latest SSID list
                io {
                    val cur = RpnProxyManager.getCountryConfigByKey(configKey)?.ssids.orEmpty()
                    val list = SsidItem.parseStorageList(cur)
                    uiCtx {
                        if (list.isEmpty()) {
                            val allTxt = getString(
                                R.string.single_argument_parenthesis,
                                getString(R.string.two_argument_space, getString(R.string.lbl_all), getString(R.string.lbl_ssids))
                            )
                            valueTv.text = allTxt
                        } else {
                            valueTv.text = list.joinToString(", ") { "${it.name} (${it.type.getDisplayName(this@RpnConfigDetailActivity)})" }
                        }
                        displayGroup.visibility = View.VISIBLE
                    }
                }
                Logger.i(LOG_TAG_UI, "SSID feature enabled for configKey: $configKey")
            } else {
                displayGroup.visibility = View.GONE
                Logger.i(LOG_TAG_UI, "SSID feature disabled for configKey: $configKey")
            }

            updateErrorLayouts(currentHasPermissions, currentLocationEnabled, permissionErrorLayout, locationErrorLayout)
        }

        layout.setOnClickListener { sw.performClick() }

        editBtn.setOnClickListener { openSsidDialog() }

        setupSsidErrorActionListeners()
    }

    private fun setupSsidErrorActionListeners() {
        // Permission error action — opens app settings so the user can grant permission manually
        b.ssidPermissionErrorAction.setOnClickListener {
            openAndroidAppInfo(this, this.packageName)
        }
        // Location error action — opens location settings
        b.ssidLocationErrorAction.setOnClickListener {
            SsidPermissionManager.requestLocationEnable(this)
        }
    }

    private fun updateErrorLayouts(
        hasPermissions: Boolean,
        isLocationEnabled: Boolean,
        permissionErrorLayout: LinearLayout,
        locationErrorLayout: LinearLayout
    ) {
        val sw = b.ssidCheck
        // Show permission error only when SSID is enabled but permissions are missing
        if (sw.isChecked && !hasPermissions) {
            Logger.vv(LOG_TAG_UI, "Showing SSID permission error layout")
            permissionErrorLayout.visibility = View.VISIBLE
        } else {
            permissionErrorLayout.visibility = View.GONE
        }
        // Show location error only when SSID is enabled, permissions ok, but location is off
        if (sw.isChecked && hasPermissions && !isLocationEnabled) {
            Logger.vv(LOG_TAG_UI, "Showing SSID location error layout")
            locationErrorLayout.visibility = View.VISIBLE
        } else {
            locationErrorLayout.visibility = View.GONE
        }
    }

    private fun hideErrorLayouts(
        permissionErrorLayout: LinearLayout,
        locationErrorLayout: LinearLayout
    ) {
        Logger.vv(LOG_TAG_UI, "Hiding all SSID error layouts")
        permissionErrorLayout.visibility = View.GONE
        locationErrorLayout.visibility = View.GONE
    }

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.ssid_permission_error_action))
            .setMessage(SsidPermissionManager.getPermissionExplanation(this))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.ssid_permission_error_action)) { _, _ ->
                SsidPermissionManager.openAppSettings(this)
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
                b.ssidCheck.isChecked = false
                io { RpnProxyManager.updateSsidBased(configKey, false) }
            }
            .create().show()
    }

    private fun refreshSsidSection() {
        io {
            countryConfig = RpnProxyManager.getCountryConfigByKey(configKey)
            uiCtx { setupSsidSectionUI(countryConfig) }
        }
    }

    private fun openSsidDialog() {
        if (configKey.isBlank() || countryConfig == null) return
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) themeId = R.style.App_Dialog_NoDim
        val dlg = CountrySsidDialog(
            this, themeId, configKey,
            countryConfig?.countryName ?: configKey,
            countryConfig?.ssids.orEmpty()
        ) { newSsids ->
            io {
                RpnProxyManager.updateSsids(configKey, newSsids)
                uiCtx {
                    refreshSsidSection()
                    Utilities.showToastUiCentered(
                        this@RpnConfigDetailActivity,
                        "SSID settings saved",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }
        dlg.setCanceledOnTouchOutside(false)
        dlg.setOnDismissListener { refreshSsidSection() }
        dlg.show()
    }

    private fun showLocationEnableDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.ssid_location_error))
            .setMessage(getString(R.string.location_enable_explanation, getString(R.string.lbl_ssids)))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.ssid_location_error_action)) { dlg, _ ->
                SsidPermissionManager.requestLocationEnable(this); dlg.dismiss()
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
                b.ssidCheck.isChecked = false
                io {
                    RpnProxyManager.updateSsidBased(configKey, false)
                }
            }
            .create().show()
    }

    private fun showSsidPermissionExplanationDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.ssid_permission_error_action))
            .setMessage(getString(R.string.ssid_permission_explanation, getString(R.string.lbl_ssids)))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.ssid_permission_error_action)) { dlg, _ ->
                SsidPermissionManager.requestSsidPermissions(this); dlg.dismiss()
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
                b.ssidCheck.isChecked = false
                io {
                    RpnProxyManager.updateSsidBased(configKey, false)
                }
            }
            .create().show()
    }

    override fun onRequestPermissionsResult(
        requestCode : Int,
        permissions : Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        SsidPermissionManager.handlePermissionResult(
            requestCode, permissions, grantResults, ssidPermissionCallback
        )
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: () -> Unit) {
        if (isFinishing) return

        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private suspend fun uiCtx(f: () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}

