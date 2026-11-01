package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.runtime.*
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.ui.adapter.CountryServerAdapter
import com.celzero.bravedns.ui.adapter.VpnServerAdapter
import android.content.Context
import android.view.View
import com.celzero.bravedns.R

class RpnSelectionState(context: Context) {
    var countryAdapter by mutableStateOf<CountryServerAdapter?>(null)
    var selectedAdapter by mutableStateOf<VpnServerAdapter?>(null)
    var avatars by mutableStateOf<List<CountryConfig>>(emptyList())
    var heatmap by mutableStateOf(LongArray(144))
    var fabBottomPx by mutableStateOf(0)
    var scrollBottomPx by mutableStateOf(0)
    var focusSearchRequest by mutableStateOf(0)
    var onHeatmapTap: (Float, Float) -> Unit = { _, _ -> }
    val avatarRow = RpnControlState()
    val capacityDotFive = RpnControlState() .apply { alpha = 0.25f ; backgroundTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)) }
    val capacityDotFour = RpnControlState() .apply { alpha = 0.25f ; backgroundTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)) }
    val capacityDotOne = RpnControlState() .apply { backgroundTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood)) }
    val capacityDotThree = RpnControlState() .apply { backgroundTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood)) }
    val capacityDotTwo = RpnControlState() .apply { backgroundTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood)) }
    val emptySelectionCard = RpnControlState()
    val errorHint = RpnControlState() .apply { text = context.getString(R.string.server_selection_error_hint); alpha = 0.8f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 14f }
    val errorIllustration = RpnControlState() .apply { contentDescription = context.getString(R.string.server_selection_error_title); setImageResource(R.drawable.dolphin_sad_1) }
    val errorReportBtn = RpnControlState() .apply { text = context.getString(R.string.contact_support_title) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 14f }
    val errorResetBtn = RpnControlState() .apply { text = context.getString(R.string.lbl_reset) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentBad)); textSizeSp = 14f }
    val errorRetryBtn = RpnControlState() .apply { text = context.getString(R.string.server_selection_error_retry) ; setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.white)); textSizeSp = 14f }
    val errorStateContainer = RpnControlState() .apply { visibility = View.GONE }
    val errorTitle = RpnControlState() .apply { text = context.getString(R.string.server_selection_error_title) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 17f; bold = true }
    val fabStartProxy = RpnControlState() .apply { text = context.getString(R.string.server_settings_start_rpn); visibility = View.GONE ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.homeScreenHeaderTextColor)) }
    val fabStopProxy = RpnControlState() .apply { contentDescription = context.getString(R.string.server_settings_stop_proxies); visibility = View.GONE; setImageResource(R.drawable.ic_stop) }
    val frequentChipGroup = RpnControlState()
    val frequentCountriesSection = RpnControlState() .apply { visibility = View.GONE }
    val fxOverlay = RpnControlState() .apply { isClickable = false; isFocusable = false }
    val locationCapacityIndicator = RpnControlState()
    val locationContent = RpnControlState()
    val qsBypassAppsState = RpnControlState()
    val qsBypassAppsTile = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val qsRelayIcon = RpnControlState() .apply { alpha = 0.5f; setImageResource(R.drawable.ic_hop) ; imageTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)) }
    val qsRelayLabel = RpnControlState() .apply { text = context.getString(R.string.cd_dns_crypt_relay_heading) }
    val qsRelayState = RpnControlState()
    val qsRelayTile = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val qsStatsState = RpnControlState()
    val qsStatsTile = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val quickSettingsPills = RpnControlState()
    val quickSettingsRow = RpnControlState()
    val registrationProgressBar = RpnControlState() .apply { visibility = View.GONE }
    val rpnHeatmapGrid = RpnControlState()
    val rvSelectedServers = RpnControlState() .apply { visibility = View.VISIBLE }
    val rvServers = RpnControlState() .apply { visibility = View.GONE }
    val searchBar = RpnSearchState()
    val searchCard = RpnControlState()
    val searchFilterBtn = RpnControlState() .apply { contentDescription = context.getString(R.string.server_selection_filter_locations) }
    val serverCountLayout = RpnControlState()
    val serversScrollView = RpnControlState()
    val settingsBtn = RpnControlState() .apply { contentDescription = context.getString(R.string.server_settings_open) }
    val shimmerHeader = RpnControlState() .apply { visibility = View.GONE }
    val shimmerServerList = RpnControlState() .apply { visibility = View.VISIBLE }
    val shimmerSubscriptionBanner = RpnControlState() .apply { visibility = View.GONE }
    val statusCard = RpnControlState()
    val statusChip = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val statusIndicator = RpnControlState() .apply { backgroundTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood)) }
    val subscriptionBanner = RpnControlState() .apply { visibility = View.GONE }
    val supportBtn = RpnControlState() .apply { contentDescription = context.getString(R.string.contact_support_title) }
    val swipeRefresh = RpnRefreshState()
    val tvActiveDuration = RpnControlState() .apply { alpha = 0.65f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = context.resources.getDimension(R.dimen.small_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1 }
    val tvActiveFilterSummary = RpnControlState() .apply { contentDescription = context.getString(R.string.server_selection_filter_clear_desc); visibility = View.GONE ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood)); textSizeSp = 12f }
    val tvConnectionStatus = RpnControlState() .apply { text = context.getString(R.string.dns_connected) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = context.resources.getDimension(R.dimen.small_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1 }
    val tvDaysRemaining = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 11f }
    val tvExpiryDate = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 12f; bold = true }
    val tvExpiryLabel = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 12f }
    val tvServerCount = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 16f; bold = true }
}

class RpnSearchState : RpnControlState() {
    private var queryValue by mutableStateOf("")
    val query: String get() = queryValue
    var onQueryChanged: (String) -> Unit = {}
    var onFocusChanged: (Boolean) -> Unit = {}
    fun setQuery(value: String) { queryValue = value; onQueryChanged(value) }
}
class RpnRefreshState : RpnControlState() {
    var isRefreshing by mutableStateOf(false)
    var onRefresh: () -> Unit = {}
}
