/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.runtime.*
import com.celzero.bravedns.R
import com.celzero.bravedns.service.LogActivityInterval
import com.celzero.bravedns.ui.custom.AppHistogramView
import com.celzero.bravedns.util.UIUtils

/** Observable presentation only. No Android Views, controller calls, or persistent policy. */
open class HomeNode(val context: Context, val id: Int = 0) {
    var text: CharSequence? by mutableStateOf("")
    var visibility by mutableIntStateOf(View.VISIBLE)
    var isVisible: Boolean
        get() = visibility == View.VISIBLE
        set(value) { visibility = if (value) View.VISIBLE else View.GONE }
    var alpha by mutableFloatStateOf(1f)
    var isEnabled by mutableStateOf(true)
    var isClickable by mutableStateOf(false)
    var isSelected by mutableStateOf(false)
    var contentDescription: CharSequence? by mutableStateOf(null)
    var textSize by mutableFloatStateOf(14f * context.resources.displayMetrics.scaledDensity)
    var textColor: Int? by mutableStateOf(null)
    var backgroundTintList: ColorStateList? by mutableStateOf(null)
    var imageTintList: ColorStateList? by mutableStateOf(null)
    var fillColor: Int? by mutableStateOf(null)
    var strokeColor: Any? by mutableStateOf(null)
    var strokeWidth by mutableIntStateOf(0)
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)
    var allCaps = false
    var monospace = false
    var click: ((HomeNode) -> Unit)? = null
    private var checkedValue by mutableStateOf(false)
    internal var onChecked: ((Boolean) -> Unit)? = null
    private var broadcasting = false
    var isChecked: Boolean
        get() = checkedValue
        set(value) {
            if (checkedValue == value) return
            checkedValue = value
            // CompoundButton's presentation semantics: a reentrant assignment
            // updates checked state but does not recursively broadcast.
            if (broadcasting) return
            broadcasting = true
            try { onChecked?.invoke(value) } finally { broadcasting = false }
        }
    fun setOnClickListener(listener: (HomeNode) -> Unit) { isClickable = true; click = listener }
    fun performClick() { if (isEnabled && isClickable) click?.invoke(this) }
    fun setText(res: Int) { text = context.getString(res) }
    fun setTextColor(color: Int) { textColor = color }
    fun setBackgroundColor(color: Int) { fillColor = color }
    fun setTextSize(unit: Int, size: Float) { textSize = size }
}

class HomeToggleGroup(context: Context, id: Int) : HomeNode(context, id) {
    private var listener: ((HomeToggleGroup, List<Int>) -> Unit)? = null
    private var items: List<HomeNode> = emptyList()
    fun bind(vararg nodes: HomeNode) {
        items = nodes.toList()
        nodes.forEach { node -> node.onChecked = { checked -> if (checked) {
            nodes.filter { it !== node }.forEach { it.isChecked = false }
            listener?.invoke(this, listOf(node.id))
        } } }
    }
    fun setOnCheckedStateChangeListener(value: (HomeToggleGroup, List<Int>) -> Unit) { listener = value }
    fun select(node: HomeNode) { if (isEnabled && node.isEnabled) node.isChecked = true }
}

interface HomePages { val itemCount: Int }
open class HomePagerCallback { open fun onPageSelected(position: Int) {} }
class HomePager(context: Context, id: Int) : HomeNode(context, id) {
    var adapter: HomePages? by mutableStateOf(null)
    var currentItem by mutableIntStateOf(0)
        private set
    private val callbacks = mutableListOf<HomePagerCallback>()
    fun setCurrentItem(position: Int, smooth: Boolean) {
        val target = position.coerceIn(0, ((adapter?.itemCount ?: 1) - 1).coerceAtLeast(0))
        if (target == currentItem) return
        currentItem = target
        callbacks.toList().forEach { it.onPageSelected(target) }
    }
    fun registerOnPageChangeCallback(callback: HomePagerCallback) { callbacks += callback }
    fun unregisterOnPageChangeCallback(callback: HomePagerCallback) { callbacks -= callback }
}
class HomeRulesPages(val labels: List<String>) : HomePages {
    override val itemCount get() = labels.size
    val counts = mutableStateListOf("0", "0", "0")
    var onRulePageTapped: ((Int) -> Unit)? = null
    fun updateCount(page: Int, count: String) { counts[page] = count }
}
class HomeHeatmap(context: Context, id: Int) : HomeNode(context, id) {
    var intervals: List<LogActivityInterval> by mutableStateOf(emptyList())
    var blocked by mutableStateOf(false)
    var light by mutableStateOf(false)
    var onTouch: ((Float, Float) -> Unit)? = null
    var onKeyboardClick: (() -> Unit)? = null
}
class HomeHistogram(context: Context, id: Int) : HomeNode(context, id) {
    var entries: List<AppHistogramView.Entry> by mutableStateOf(emptyList())
    var blocked by mutableStateOf(false)
    var light by mutableStateOf(false)
    var selectedIndex by mutableIntStateOf(-1)
    var onEntrySelected: ((AppHistogramView.Entry?) -> Unit)? = null
    fun setLightTheme(value: Boolean) { light = value }
    fun submit(items: List<AppHistogramView.Entry>, blocked: Boolean) {
        entries = items; this.blocked = blocked; selectedIndex = -1
        onEntrySelected?.invoke(null)
    }
    fun select(index: Int) {
        selectedIndex = if (selectedIndex == index) -1 else index
        onEntrySelected?.invoke(entries.getOrNull(selectedIndex))
    }
    fun unmeteredColor(): Int = androidx.core.graphics.ColorUtils.setAlphaComponent(UIUtils.fetchColor(context, R.attr.primaryLightColorText), if (light) 230 else 204)
    fun meteredColor(): Int = androidx.core.graphics.ColorUtils.setAlphaComponent(UIUtils.fetchColor(context, R.attr.primaryLightColorText), if (light) 115 else 82)
}

class HomePresentation(val context: Context) {
    val logsPage = HomeLogsPage(context)
    val topAppsPage = HomeTopAppsPage(context)
    val dialogs = mutableStateListOf<HomeDialog>()
    var borderAnimating by mutableStateOf(false)
    var borderStartedAt by mutableLongStateOf(0L)
    var animateLogs by mutableStateOf(false)
    val fhsScrollView = HomeNode(context, R.id.fhs_scroll_view).apply {
    }
    val fhsProtectionBar = HomeNode(context, R.id.fhs_protection_bar).apply {
    }
    val fhsProtectionLevelTxt = HomeNode(context, R.id.fhs_protection_level_txt).apply {
        textSize = context.resources.getDimension(R.dimen.default_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.accentGood))
        allCaps = true
    }
    val fhsActiveSinceTxt = HomeNode(context, R.id.fhs_active_since_txt).apply {
        text = ""
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.7f
        monospace = true
    }
    val fhsCardDnsLl = HomeNode(context, R.id.fhs_card_dns_ll).apply {
    }
    val fhsDnsTextContainer = HomeNode(context, R.id.fhs_dns_text_container).apply {
    }
    val fhsCardDnsConnectedDns = HomeNode(context, R.id.fhs_card_dns_connected_dns).apply {
        textSize = context.resources.getDimension(R.dimen.large_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
    }
    val fhsCardDnsLatency = HomeNode(context, R.id.fhs_card_dns_latency).apply {
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
    }
    val fhsThroughputLl = HomeNode(context, R.id.fhs_throughput_ll).apply {
    }
    val fhsInternetSpeed = HomeNode(context, R.id.fhs_internet_speed).apply {
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
    }
    val fhsInternetSpeedUnit = HomeNode(context, R.id.fhs_internet_speed_unit).apply {
        textSize = context.resources.getDimension(R.dimen.mini_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
    }
    val fhsFirewallProxyRow = HomeNode(context, R.id.fhs_firewall_proxy_row).apply {
    }
    val fhsCardFirewallLl = HomeNode(context, R.id.fhs_card_firewall_ll).apply {
    }
    val fhsFirewallBadgesRow = HomeNode(context, R.id.fhs_firewall_badges_row).apply {
    }
    val fhsCardFirewallRulesCard = HomeNode(context, R.id.fhs_card_firewall_rules_card).apply {
    }
    val fhsFirewallRulesPager = HomePager(context, R.id.fhs_firewall_rules_pager).apply {
        contentDescription = context.getString(R.string.hsf_rules_pager_desc)
    }
    val fhsFirewallPagerDots = HomeNode(context, R.id.fhs_firewall_pager_dots).apply {
    }
    val fhsRulesDot0 = HomeNode(context, R.id.fhs_rules_dot_0).apply {
        alpha = 0.35f
    }
    val fhsRulesDot1 = HomeNode(context, R.id.fhs_rules_dot_1).apply {
        alpha = 0.35f
    }
    val fhsRulesDot2 = HomeNode(context, R.id.fhs_rules_dot_2).apply {
        alpha = 0.35f
    }
    val fhsCardProxyLl = HomeNode(context, R.id.fhs_card_proxy_ll).apply {
    }
    val fhsCardOtherProxyCount = HomeNode(context, R.id.fhs_card_other_proxy_count).apply {
        textSize = context.resources.getDimension(R.dimen.large_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
    }
    val fhsProxyHealthContainer = HomeNode(context, R.id.fhs_proxy_health_container).apply {
    }
    val fhsProxyBarActive = HomeNode(context, R.id.fhs_proxy_bar_active).apply {
    }
    val fhsProxyBarIdle = HomeNode(context, R.id.fhs_proxy_bar_idle).apply {
    }
    val fhsProxyBarFailing = HomeNode(context, R.id.fhs_proxy_bar_failing).apply {
    }
    val fhsProxyBarStopped = HomeNode(context, R.id.fhs_proxy_bar_stopped).apply {
        visibility = View.GONE
    }
    val fhsProxyDotActive = HomeNode(context, R.id.fhs_proxy_dot_active).apply {
    }
    val fhsProxyLiveCount = HomeNode(context, R.id.fhs_proxy_live_count).apply {
        textSize = context.resources.getDimension(R.dimen.default_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.accentGood))
        monospace = true
    }
    val fhsProxyDotIdle = HomeNode(context, R.id.fhs_proxy_dot_idle).apply {
    }
    val fhsProxyIdleCount = HomeNode(context, R.id.fhs_proxy_idle_count).apply {
        textSize = context.resources.getDimension(R.dimen.default_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.accentWarning))
        monospace = true
    }
    val fhsProxyDotFailing = HomeNode(context, R.id.fhs_proxy_dot_failing).apply {
    }
    val fhsProxyFailingCount = HomeNode(context, R.id.fhs_proxy_failing_count).apply {
        textSize = context.resources.getDimension(R.dimen.default_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.accentBad))
        monospace = true
    }
    val fhsProxyStoppedColumn = HomeNode(context, R.id.fhs_proxy_stopped_column).apply {
        visibility = View.GONE
    }
    val fhsProxyDotStopped = HomeNode(context, R.id.fhs_proxy_dot_stopped).apply {
    }
    val fhsProxyStoppedCount = HomeNode(context, R.id.fhs_proxy_stopped_count).apply {
        textSize = context.resources.getDimension(R.dimen.default_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        monospace = true
    }
    val fhsCardLogsLl = HomeNode(context, R.id.fhs_card_logs_ll).apply {
    }
    val fhsLogsPager = HomePager(context, R.id.fhs_logs_pager).apply {
        contentDescription = context.getString(R.string.logs_card_pager_desc)
    }
    val fhsLogsDots = HomeNode(context, R.id.fhs_logs_dots).apply {
    }
    val fhsLogsDot0 = HomeNode(context, R.id.fhs_logs_dot_0).apply {
        alpha = 0.35f
    }
    val fhsLogsDot1 = HomeNode(context, R.id.fhs_logs_dot_1).apply {
        alpha = 0.35f
    }
    val fhsLogsDisabledRow = HomeNode(context, R.id.fhs_logs_disabled_row).apply {
        visibility = View.GONE
    }
    val fhsLogsDisabledTxt = HomeNode(context, R.id.fhs_logs_disabled_txt).apply {
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
    }
    val fhsLogsEnableChip = HomeNode(context, R.id.fhs_logs_enable_chip).apply {
        text = context.getString(R.string.lbl_enable)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
        visibility = View.GONE
    }
    val fhsCardAppsCv = HomeNode(context, R.id.fhs_card_apps_cv).apply {
    }
    val fhsAppsCountContainer = HomeNode(context, R.id.fhs_apps_count_container).apply {
    }
    val fhsCardAllowedApps = HomeNode(context, R.id.fhs_card_allowed_apps).apply {
        textSize = context.resources.getDimension(R.dimen.home_screen_stat_text)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
        monospace = true
    }
    val fhsCardAppsAllApps = HomeNode(context, R.id.fhs_card_apps_all_apps).apply {
        textSize = context.resources.getDimension(R.dimen.large_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        monospace = true
    }
    val fhsAppsLabel = HomeNode(context, R.id.fhs_apps_label).apply {
        text = context.getString(R.string.apps_info_title)
        textSize = context.resources.getDimension(R.dimen.default_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsAppsFlow = HomeNode(context, R.id.fhs_apps_flow).apply {
    }
    val fhsCardAppsBlockedCount = HomeNode(context, R.id.fhs_card_apps_blocked_count).apply {
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsCardAppsIsolatedCount = HomeNode(context, R.id.fhs_card_apps_isolated_count).apply {
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsCardAppsBypassedCount = HomeNode(context, R.id.fhs_card_apps_bypassed_count).apply {
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsCardAppsExcludedCount = HomeNode(context, R.id.fhs_card_apps_excluded_count).apply {
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsAppsArrow = HomeNode(context, R.id.fhs_apps_arrow).apply {
        text = "›"
        textSize = 20f * context.resources.displayMetrics.scaledDensity
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
    }
    val fhsControlCluster = HomeNode(context, R.id.fhs_control_cluster).apply {
    }
    val homeFragmentPauseIcon = HomeNode(context, R.id.home_fragment_pause_icon).apply {
    }
    val fhsStartBtnBorderContainer = HomeNode(context, R.id.fhs_start_btn_border_container).apply {
    }
    val fhsDnsOnOffBtn = HomeNode(context, R.id.fhs_dns_on_off_btn).apply {
        textSize = context.resources.getDimension(R.dimen.extra_large_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.invertedPrimaryTextColor))
        allCaps = true
        monospace = true
    }
    val fhsAnimatedBorderView = HomeNode(context, R.id.fhs_animated_border_view).apply {
        visibility = View.GONE
    }
    val homeFragmentBottomSheetIcon = HomeNode(context, R.id.home_fragment_bottom_sheet_icon).apply {
    }
}

class HomeLogsPage(val context: Context) {
    val root = HomeNode(context)
    val fhsLogsHeader = HomeNode(context, R.id.fhs_logs_header).apply {
    }
    val fhsLogsAllowedHeader = HomeNode(context, R.id.fhs_logs_allowed_header).apply {
    }
    val fhsCardAllowedLogsCount = HomeNode(context, R.id.fhs_card_allowed_logs_count).apply {
        textSize = context.resources.getDimension(R.dimen.rethink_sub_header_text)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
        monospace = true
    }
    val fhsCardAllowedLogsLabel = HomeNode(context, R.id.fhs_card_allowed_logs_label).apply {
        text = context.getString(R.string.lbl_allowed)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsLogsBlockedHeader = HomeNode(context, R.id.fhs_logs_blocked_header).apply {
    }
    val fhsCardBlockedLogsCount = HomeNode(context, R.id.fhs_card_blocked_logs_count).apply {
        textSize = context.resources.getDimension(R.dimen.rethink_header_text)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        monospace = true
    }
    val fhsCardBlockedLogsLabel = HomeNode(context, R.id.fhs_card_blocked_logs_label).apply {
        text = context.getString(R.string.lbl_blocked)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsLogsToggleGroup = HomeToggleGroup(context, R.id.fhs_logs_toggle_group).apply {
    }
    val fhsLogsAllowedChip = HomeNode(context, R.id.fhs_logs_allowed_chip).apply {
        text = context.getString(R.string.lbl_allowed)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        isChecked = true
    }
    val fhsLogsBlockedChip = HomeNode(context, R.id.fhs_logs_blocked_chip).apply {
        text = context.getString(R.string.lbl_blocked)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
    }
    val fhsLogsHourAxis = HomeNode(context, R.id.fhs_logs_hour_axis).apply {
    }
    val fhsLogsHourStart = HomeNode(context, R.id.fhs_logs_hour_start).apply {
        text = "24"
        textSize = context.resources.getDimension(R.dimen.extra_small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.55f
        monospace = true
    }
    val fhsLogsHourStartPlus6 = HomeNode(context, R.id.fhs_logs_hour_start_plus_6).apply {
        text = "18"
        textSize = context.resources.getDimension(R.dimen.extra_small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.55f
        monospace = true
    }
    val fhsLogsHourStartPlus12 = HomeNode(context, R.id.fhs_logs_hour_start_plus_12).apply {
        text = "12"
        textSize = context.resources.getDimension(R.dimen.extra_small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.55f
        monospace = true
    }
    val fhsLogsHourStartPlus18 = HomeNode(context, R.id.fhs_logs_hour_start_plus_18).apply {
        text = "6"
        textSize = context.resources.getDimension(R.dimen.extra_small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.55f
        monospace = true
    }
    val fhsLogsGrid = HomeHeatmap(context, R.id.fhs_logs_grid).apply {
    }
    val fhsCardLogsDuration = HomeNode(context, R.id.fhs_card_logs_duration).apply {
        text = context.getString(R.string.logs_card_heatmap_window)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsLogsLegend = HomeNode(context, R.id.fhs_logs_legend).apply {
    }
    val fhsLogsSwatch0 = HomeNode(context, R.id.fhs_logs_swatch_0).apply {
    }
    val fhsLogsSwatch1 = HomeNode(context, R.id.fhs_logs_swatch_1).apply {
    }
    val fhsLogsSwatch2 = HomeNode(context, R.id.fhs_logs_swatch_2).apply {
    }
    val fhsLogsSwatch3 = HomeNode(context, R.id.fhs_logs_swatch_3).apply {
    }
    init { fhsLogsToggleGroup.bind(fhsLogsAllowedChip, fhsLogsBlockedChip) }
}

class HomeTopAppsPage(val context: Context) {
    val root = HomeNode(context)
    val fhsTopAppsHeader = HomeNode(context, R.id.fhs_top_apps_header).apply {
    }
    val fhsTopAppsWifiHeader = HomeNode(context, R.id.fhs_top_apps_wifi_header).apply {
    }
    val fhsTopAppsWifiValue = HomeNode(context, R.id.fhs_top_apps_wifi_value).apply {
        textSize = context.resources.getDimension(R.dimen.heading_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
        monospace = true
    }
    val fhsTopAppsWifiLabel = HomeNode(context, R.id.fhs_top_apps_wifi_label).apply {
        text = context.getString(R.string.logs_card_apps_legend_wifi)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsTopAppsMobileHeader = HomeNode(context, R.id.fhs_top_apps_mobile_header).apply {
    }
    val fhsTopAppsMobileValue = HomeNode(context, R.id.fhs_top_apps_mobile_value).apply {
        textSize = context.resources.getDimension(R.dimen.heading_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
        monospace = true
    }
    val fhsTopAppsMobileLabel = HomeNode(context, R.id.fhs_top_apps_mobile_label).apply {
        text = context.getString(R.string.logs_card_apps_legend_mobile)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsTopAppsToggleGroup = HomeToggleGroup(context, R.id.fhs_top_apps_toggle_group).apply {
    }
    val fhsTopAppsAllowedChip = HomeNode(context, R.id.fhs_top_apps_allowed_chip).apply {
        text = context.getString(R.string.lbl_allowed)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        isChecked = true
    }
    val fhsTopAppsBlockedChip = HomeNode(context, R.id.fhs_top_apps_blocked_chip).apply {
        text = context.getString(R.string.lbl_blocked)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
    }
    val fhsTopAppsHistogram = HomeHistogram(context, R.id.fhs_top_apps_histogram).apply {
    }
    val fhsTopAppsEmpty = HomeNode(context, R.id.fhs_top_apps_empty).apply {
        text = context.getString(R.string.logs_card_apps_empty)
        textSize = context.resources.getDimension(R.dimen.default_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.7f
        visibility = View.GONE
    }
    val fhsTopAppsCaptionRow = HomeNode(context, R.id.fhs_top_apps_caption_row).apply {
    }
    val fhsTopAppsWindow = HomeNode(context, R.id.fhs_top_apps_window).apply {
        text = context.getString(R.string.logs_card_heatmap_window)
        textSize = context.resources.getDimension(R.dimen.small_font_text_view)
        setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        alpha = 0.8f
        allCaps = true
    }
    val fhsTopAppsLegend = HomeNode(context, R.id.fhs_top_apps_legend).apply {
    }
    val fhsTopAppsSwatchWifi = HomeNode(context, R.id.fhs_top_apps_swatch_wifi).apply {
        contentDescription = context.getString(R.string.logs_card_apps_legend_wifi)
    }
    val fhsTopAppsSwatchMobile = HomeNode(context, R.id.fhs_top_apps_swatch_mobile).apply {
        contentDescription = context.getString(R.string.logs_card_apps_legend_mobile)
    }
    init { fhsTopAppsToggleGroup.bind(fhsTopAppsAllowedChip, fhsTopAppsBlockedChip) }
}
