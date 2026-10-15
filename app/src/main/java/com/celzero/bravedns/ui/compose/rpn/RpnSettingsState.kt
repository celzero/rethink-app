package com.celzero.bravedns.ui.compose.rpn

import android.content.Context
import android.view.View
import com.celzero.bravedns.R

class RpnSettingsState(context: Context) {
    val btnConfigAuto = RpnControlState() .apply { text = context.getString(R.string.server_settings_config_mode_auto) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.invertedPrimaryTextColor)); textSizeSp = 12f }
    val btnConfigManual = RpnControlState() .apply { text = context.getString(R.string.server_settings_config_mode_manual) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.invertedPrimaryTextColor)); textSizeSp = 12f }
    val btnResetRpn = RpnControlState() .apply { text = context.getString(R.string.rpn_restore_confirm_title) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 14f; bold = true }
    val cbDnsFamily = RpnControlState() .apply { isClickable = false; isFocusable = false }
    val cbDnsPrivacy = RpnControlState() .apply { isClickable = false; isFocusable = false }
    val cbDnsSecurity = RpnControlState() .apply { isClickable = false; isFocusable = false }
    val configModeToggle = RpnChoiceState()
    val dnsCheckboxContainer = RpnControlState()
    val dnsRowFamily = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val dnsRowPrivacy = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val dnsRowSecurity = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val dolphinSignature = RpnSignatureState()
    val excludeCountriesRow = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val identityRow = RpnControlState() .apply { alpha = 0.35f }
    val identitySwitch = RpnControlState() .apply { isEnabled = false }
    val permanentConfigRow = RpnControlState() .apply { alpha = 0.35f; visibility = View.GONE }
    val permanentConfigSwitch = RpnControlState() .apply { isEnabled = false }
    val portRow = RpnControlState() .apply { alpha = 0.35f; isClickable = false; isFocusable = false }
    val refreshBtn = RpnControlState() .apply { contentDescription = context.getString(R.string.server_settings_refresh_servers) }
    val splitDnsBanner = RpnControlState() .apply { visibility = View.GONE }
    val splitDnsEnableBtn = RpnControlState() .apply { text = context.getString(R.string.server_settings_enable_split_dns) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 11f }
    val tvConfigModeHint = RpnControlState() .apply { text = context.getString(R.string.server_settings_config_auto_hint); alpha = 0.6f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 11.5f }
    val tvDnsFilterDesc = RpnControlState() .apply { alpha = 0.6f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 12f }
    val tvExcludeCcDesc = RpnControlState() .apply { text = context.getString(R.string.auto_exclude_cc_none) }
    val tvPortValue = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood)); textSizeSp = 14f; bold = true }
}
