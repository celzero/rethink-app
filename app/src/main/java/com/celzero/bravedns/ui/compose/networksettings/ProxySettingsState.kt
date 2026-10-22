/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksettings

import android.content.Context
import android.os.Bundle
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.util.UIUtils

/** Native presentation state. XML-derived order and defaults; no View inflation or backend access. */
class ProxySettingsState(context: Context) {
    val node0 = WgControlState(context, checkable = false)
    val node1 = WgControlState(context, checkable = false)
    val settingsActivityVpnLockdownDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_lock_down_proxy_desc); visibility = View.VISIBLE }
    val rpnHeader = WgControlState(context, checkable = false)
    val rpnTitle = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.rpn_title); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val rpnRefresh = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_refresh_white }
    val node6 = WgControlState(context, checkable = false)
    val node7 = WgControlState(context, checkable = false)
    val settingsActivityRpnContainer = WgControlState(context, checkable = false)
    val settingsActivityRpnIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_rethink_plus_sparkle }
    val node10 = WgControlState(context, checkable = false)
    val node11 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.proxy_rpn_heading) }
    val settingsActivityRpnDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.proxy_rpn_desc_inactive) }
    val settingsActivityRpnImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val wgHeader = WgControlState(context, checkable = false)
    val settingsWireguardTitle = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.lbl_wireguard); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val wgRefresh = WgControlState(context, checkable = false) .apply { visibility = View.GONE; imageResource = R.drawable.ic_refresh_white }
    val node17 = WgControlState(context, checkable = false)
    val node18 = WgControlState(context, checkable = false)
    val settingsActivityWireguardContainer = WgControlState(context, checkable = false) .apply { visibility = View.VISIBLE }
    val settingsActivityWireguardIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_wireguard_icon }
    val node21 = WgControlState(context, checkable = false)
    val node22 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.setup_wireguard) }
    val settingsActivityWireguardDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.wireguard_description) }
    val settingsActivityWireguardImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val orbotTitle = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.orbot); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node26 = WgControlState(context, checkable = false)
    val node27 = WgControlState(context, checkable = false)
    val settingsActivityOrbotContainer = WgControlState(context, checkable = false) .apply { visibility = View.VISIBLE }
    val settingsActivityOrbotIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_orbot }
    val node30 = WgControlState(context, checkable = false)
    val node31 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_orbot_heading) }
    val settingsActivityHttpOrbotDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_orbot_desc) }
    val settingsActivityOrbotImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_arrow_down_small }
    val otherTitle = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.category_name_others); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node35 = WgControlState(context, checkable = false)
    val node36 = WgControlState(context, checkable = false)
    val settingsActivitySocks5Rl = WgControlState(context, checkable = false)
    val settingsActivitySocks5Icon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_socks5 }
    val node39 = WgControlState(context, checkable = false)
    val node40 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_socks5_heading) }
    val settingsActivitySocks5Desc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_socks5_desc) }
    val settingsActivitySocks5Switch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val settingsActivitySocks5Progress = WgControlState(context, checkable = false)
    val settingsActivityHttpProxyContainer = WgControlState(context, checkable = false) .apply { visibility = View.VISIBLE }
    val settingsActivityHttpIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_http }
    val node46 = WgControlState(context, checkable = false)
    val node47 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_https_heading) }
    val settingsActivityHttpProxyDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_https_desc) }
    val settingsActivityHttpProxySwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val settingsActivityHttpProxyProgress = WgControlState(context, checkable = false)
    val refreshing = androidx.compose.runtime.mutableStateMapOf<WgControlState, NetworkRefreshState>()
    private var refreshGeneration = 0
    fun startRefresh(control: WgControlState, animation: android.view.animation.Animation? = null) {
        refreshing[control] = NetworkRefreshState(++refreshGeneration, animation?.duration?.toInt() ?: 750,
            animation?.interpolator ?: android.view.animation.AccelerateDecelerateInterpolator())
    }
    fun stopRefresh(control: WgControlState) { refreshing.remove(control) }
    val layout = NetworkNode(NetworkKind.SCROLL, control = node0, fillWidth = true, children = listOf(
        NetworkNode(NetworkKind.COLUMN, control = node1, margin = NetworkInsets(0f, 10f, 0f, 0f), fillWidth = true, children = listOf(
            NetworkNode(NetworkKind.TEXT, control = settingsActivityVpnLockdownDesc, key = "network.settings_activity_vpn_lockdown_desc", appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, textSizeRes = R.dimen.default_font_text_view, leadingIcon = R.drawable.dis_allowed, iconGap = 10f, margin = NetworkInsets(4f, 0f, 4f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true),
            NetworkNode(NetworkKind.ROW, control = rpnHeader, key = "network.rpn_header", margin = NetworkInsets(55f, 0f, 0f, 0f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = rpnTitle, key = "network.rpn_title", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(0f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f), flexible = true),
                NetworkNode(NetworkKind.ICON, control = rpnRefresh, key = "network.rpn_refresh", margin = NetworkInsets(0f, 0f, 20f, 0f), padding = NetworkInsets(5f, 5f, 5f, 5f), trailing = true)
            )),
            NetworkNode(NetworkKind.CARD, control = node6, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node7, fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityRpnContainer, key = "network.settings_activity_rpn_container", padding = NetworkInsets(0f, 15f, 0f, 15f), fillWidth = true, children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityRpnIcon, key = "network.settings_activity_rpn_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node10, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = node11, appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, textSizeRes = R.dimen.large_font_text_view),
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityRpnDesc, key = "network.settings_activity_rpn_desc", textSizeRes = R.dimen.default_font_text_view, padding = NetworkInsets(0f, 5f, 0f, 5f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityRpnImg, key = "network.settings_activity_rpn_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.ROW, control = wgHeader, key = "network.wg_header", fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = settingsWireguardTitle, key = "network.settings_wireguard_title", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
                NetworkNode(NetworkKind.ICON, control = wgRefresh, key = "network.wg_refresh", margin = NetworkInsets(0f, 0f, 20f, 0f), padding = NetworkInsets(5f, 5f, 5f, 5f), trailing = true)
            )),
            NetworkNode(NetworkKind.CARD, control = node17, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node18, fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityWireguardContainer, key = "network.settings_activity_wireguard_container", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityWireguardIcon, key = "network.settings_activity_wireguard_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node21, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = node22, appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, textSizeRes = R.dimen.large_font_text_view),
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityWireguardDesc, key = "network.settings_activity_wireguard_desc", textSizeRes = R.dimen.default_font_text_view, padding = NetworkInsets(0f, 5f, 0f, 5f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityWireguardImg, key = "network.settings_activity_wireguard_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.TEXT, control = orbotTitle, key = "network.orbot_title", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node26, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node27, fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityOrbotContainer, key = "network.settings_activity_orbot_container", padding = NetworkInsets(0f, 15f, 0f, 20f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityOrbotIcon, key = "network.settings_activity_orbot_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node30, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = node31, appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, textSizeRes = R.dimen.large_font_text_view),
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityHttpOrbotDesc, key = "network.settings_activity_http_orbot_desc", textSizeRes = R.dimen.default_font_text_view, padding = NetworkInsets(0f, 5f, 0f, 5f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityOrbotImg, key = "network.settings_activity_orbot_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.TEXT, control = otherTitle, key = "network.other_title", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node35, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node36, fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivitySocks5Rl, key = "network.settings_activity_socks5_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivitySocks5Icon, key = "network.settings_activity_socks5_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node39, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = node40, appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, textSizeRes = R.dimen.large_font_text_view),
                            NetworkNode(NetworkKind.TEXT, control = settingsActivitySocks5Desc, key = "network.settings_activity_socks5_desc", textSizeRes = R.dimen.default_font_text_view, padding = NetworkInsets(0f, 5f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.OVERLAY, children = listOf(NetworkNode(NetworkKind.SWITCH, control = settingsActivitySocks5Switch, key = "network.settings_activity_socks5_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true), NetworkNode(NetworkKind.PROGRESS, control = settingsActivitySocks5Progress, key = "network.settings_activity_socks5_progress", margin = NetworkInsets(8f, 0f, 8f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), width = 48f, height = 48f, trailing = true)))
                    )),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityHttpProxyContainer, key = "network.settings_activity_http_proxy_container", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityHttpIcon, key = "network.settings_activity_http_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node46, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = node47, appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, textSizeRes = R.dimen.large_font_text_view),
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityHttpProxyDesc, key = "network.settings_activity_http_proxy_desc", textSizeRes = R.dimen.default_font_text_view, padding = NetworkInsets(0f, 5f, 0f, 5f))
                        )),
                        NetworkNode(NetworkKind.OVERLAY, children = listOf(NetworkNode(NetworkKind.SWITCH, control = settingsActivityHttpProxySwitch, key = "network.settings_activity_http_proxy_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true), NetworkNode(NetworkKind.PROGRESS, control = settingsActivityHttpProxyProgress, key = "network.settings_activity_http_proxy_progress", margin = NetworkInsets(8f, 0f, 8f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), width = 48f, height = 48f, trailing = true)))
                    ))
                ))
            ))
        ))
    ))
    fun saveChecks(bundle: Bundle) {
        bundle.putBoolean("compose.ProxySettingsState.settingsActivitySocks5Switch", settingsActivitySocks5Switch.isChecked)
        bundle.putBoolean("compose.ProxySettingsState.settingsActivityHttpProxySwitch", settingsActivityHttpProxySwitch.isChecked)
    }
    fun restoreChecks(bundle: Bundle) {
        if (bundle.containsKey("compose.ProxySettingsState.settingsActivitySocks5Switch")) settingsActivitySocks5Switch.isChecked = bundle.getBoolean("compose.ProxySettingsState.settingsActivitySocks5Switch")
        if (bundle.containsKey("compose.ProxySettingsState.settingsActivityHttpProxySwitch")) settingsActivityHttpProxySwitch.isChecked = bundle.getBoolean("compose.ProxySettingsState.settingsActivityHttpProxySwitch")
    }
}
