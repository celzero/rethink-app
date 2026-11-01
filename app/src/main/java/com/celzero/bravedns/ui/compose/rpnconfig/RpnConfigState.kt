/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rpnconfig

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.*
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.rpn.RpnSignatureState

/** Snapshot presentation values. Defaults copied from runtime XML, never tools previews. */
class RpnConfigState(context: Context) {
    var headerTitle by mutableStateOf<CharSequence?>("")
    var refreshRotation by mutableFloatStateOf(0f)
    var clientIpShimmer by mutableStateOf(true)
    val flagWatermark = RpnConfigFlagState(context)
    val dolphinSignature = RpnSignatureState()
    val headerGradientBg = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val heroContent = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val configNameText = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:config_name_text").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 24f; textStyle = android.graphics.Typeface.BOLD; maxLines = 1; ellipsize = true }
    val tvHeroCity = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:tv_hero_city").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 14f; maxLines = 1; ellipsize = true }
    val refreshChip = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:refresh_chip").apply { text = "Refresh"; visibility = View.VISIBLE; textSizeSp = 11f }
    val chipHeroStats = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:chip_hero_stats").apply { visibility = View.GONE; textSizeSp = 11f; alpha = 0.85f }
    val valueAddlInfo = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_addl_info").apply { visibility = View.GONE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 11f; alpha = 0.75f; maxLines = 1; ellipsize = true }
    val tvHeroPub = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:tv_hero_pub").apply { visibility = View.GONE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 11f; alpha = 0.75f; maxLines = 1; ellipsize = true }
    val statsCard = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; radiusDp = 16f; strokeWidth = 0; elevationDp = 0f; backgroundColor = UIUtils.fetchColor(context, androidx.cardview.R.attr.cardBackgroundColor) }
    val shimmerStats = WgControlState(context, checkable = false).apply { visibility = View.GONE }
    val statsContent = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val rowStatus = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val valueStatus = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_status").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 11f; textStyle = android.graphics.Typeface.BOLD }
    val valueSince = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_since").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 11f }
    val valueRx = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_rx").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 11f }
    val valueTx = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_tx").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 11f }
    val rowLastOk = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val valueLastOk = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_last_ok").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 11f }
    val valueLastOpen = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_last_open").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 11f }
    val rowIpv4 = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val labelIpv4 = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:label_ipv4").apply { text = context.getText(R.string.lbl_client_ipv4); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 13f }
    val valueIpv4 = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:value_ipv4").apply { visibility = View.GONE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 13f }
    val actionsCard = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; radiusDp = 16f; strokeWidth = 0; elevationDp = 0f; backgroundColor = UIUtils.fetchColor(context, androidx.cardview.R.attr.cardBackgroundColor) }
    val applicationsBtn = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; radiusDp = 20f; strokeWidth = 0; elevationDp = 0f; backgroundColor = UIUtils.fetchColor(context, androidx.cardview.R.attr.cardBackgroundColor) }
    val appsLabel = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:apps_label").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.accentGood); textSizeSp = context.resources.getDimension(R.dimen.default_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1; ellipsize = true }
    val hopBtn = WgControlState(context, checkable = false).apply { visibility = View.GONE }
    val hopIcon = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; alpha = 0.5f }
    val hopLabel = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:hop_label").apply { text = "Relay"; visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 12f; maxLines = 1 }
    val hopBadge = WgControlState(context, checkable = false).apply { visibility = View.GONE }
    val logsBtn = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; radiusDp = 20f; strokeWidth = 0; elevationDp = 0f; backgroundColor = UIUtils.fetchColor(context, androidx.cardview.R.attr.cardBackgroundColor) }
    val otherSettingsCard = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; radiusDp = 16f; strokeWidth = 0; elevationDp = 0f; backgroundColor = UIUtils.fetchColor(context, androidx.cardview.R.attr.cardBackgroundColor) }
    val hopRl = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val hopTitleTv = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:hop_title_tv").apply { text = context.getText(R.string.cd_dns_crypt_relay_heading); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 15f; textStyle = android.graphics.Typeface.BOLD }
    val hopCheck = WgControlState(context, checkable = true).apply { visibility = View.VISIBLE; switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val lockdownRl = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val lockdownTitleTv = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:lockdown_title_tv").apply { text = context.getText(R.string.firewall_rule_global_lockdown); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 15f; textStyle = android.graphics.Typeface.BOLD }
    val lockdownCheck = WgControlState(context, checkable = true).apply { visibility = View.VISIBLE; switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val catchAllRl = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val catchAllTitleTv = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:catch_all_title_tv").apply { text = "Route remaining apps"; visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 15f; textStyle = android.graphics.Typeface.BOLD }
    val catchAllCheck = WgControlState(context, checkable = true).apply { visibility = View.VISIBLE; switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val mobileSsidSettingsCard = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; radiusDp = 16f; strokeWidth = 0; elevationDp = 0f; backgroundColor = UIUtils.fetchColor(context, androidx.cardview.R.attr.cardBackgroundColor) }
    val useMobileRl = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val useMobileTitleTv = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:use_mobile_title_tv").apply { text = context.getText(R.string.wg_setting_use_on_mobile); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 15f; textStyle = android.graphics.Typeface.BOLD }
    val useMobileCheck = WgControlState(context, checkable = true).apply { visibility = View.VISIBLE; switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val ssidFilterRl = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE }
    val ssidTitleTv = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:ssid_title_tv").apply { text = context.getText(R.string.wg_setting_ssid_title); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 15f; textStyle = android.graphics.Typeface.BOLD }
    val ssidDescTv = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:ssid_desc_tv").apply { text = context.getText(R.string.wg_setting_ssid_desc); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = 13f }
    val ssidCheck = WgControlState(context, checkable = true).apply { visibility = View.VISIBLE; switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val ssidDisplayGroup = WgControlState(context, checkable = false).apply { visibility = View.GONE }
    val ssidValueTv = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:ssid_value_tv").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor); textSizeSp = 13f }
    val ssidEditBtn = WgControlState(context, checkable = false).apply { visibility = View.VISIBLE; alpha = 0.65f }
    val ssidPermissionErrorLayout = WgControlState(context, checkable = false).apply { visibility = View.GONE }
    val ssidPermissionErrorText = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:ssid_permission_error_text").apply { visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.accentBad); textSizeSp = 12f }
    val ssidPermissionErrorAction = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:ssid_permission_error_action").apply { text = context.getText(R.string.ssid_permission_error_action); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.accentGood); textSizeSp = 12f; textStyle = android.graphics.Typeface.BOLD }
    val ssidLocationErrorLayout = WgControlState(context, checkable = false).apply { visibility = View.GONE }
    val ssidLocationErrorText = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:ssid_location_error_text").apply { text = context.getText(R.string.ssid_location_error); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.accentBad); textSizeSp = 12f }
    val ssidLocationErrorAction = WgControlState(context, checkable = false, textSource = "activity_rpn_config_detail:ssid_location_error_action").apply { text = context.getText(R.string.ssid_location_error_action); visibility = View.VISIBLE; textColor = UIUtils.fetchColor(context, R.attr.accentGood); textSizeSp = 12f; textStyle = android.graphics.Typeface.BOLD }
    private val switches get() = listOf("hop" to hopCheck, "lockdown" to lockdownCheck, "catchAll" to catchAllCheck, "mobile" to useMobileCheck, "ssid" to ssidCheck)
    fun save(bundle: Bundle) { switches.forEach { (key, node) -> bundle.putBoolean("rpn.config." + key, node.isChecked) } }
    fun restore(bundle: Bundle) { switches.forEach { (key, node) -> node.isChecked = bundle.getBoolean("rpn.config." + key, false) } }
}
