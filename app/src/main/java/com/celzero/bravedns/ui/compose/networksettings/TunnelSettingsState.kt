/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksettings

import android.content.Context
import android.os.Bundle
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.util.UIUtils

/** Native presentation state. XML-derived order and defaults; no View inflation or backend access. */
class TunnelSettingsState(context: Context) {
    val node0 = WgControlState(context, checkable = false)
    val node1 = WgControlState(context, checkable = false)
    val node2 = WgControlState(context, checkable = false)
    val settingsActivityVpnHeadingText = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.lbl_network); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val settingsRestoreDefaults = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_backup_restore }
    val settingsActivityVpnLockdownDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_lock_down_mode_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant); visibility = View.VISIBLE }
    val node6 = WgControlState(context, checkable = false)
    val node7 = WgControlState(context, checkable = false)
    val settingsStallNoNwRl = WgControlState(context, checkable = false)
    val settingsStallNoNwIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_no_network }
    val node10 = WgControlState(context, checkable = false)
    val genStallNoNwTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.fail_open_network_title) }
    val genStallNoNwDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.fail_open_network_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsStallNoNwSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch; imageResource = R.drawable.ic_right_arrow_small }
    val node14 = WgControlState(context, checkable = false)
    val settingsActivityAllowBypassRl = WgControlState(context, checkable = false)
    val settingsActivityAllowBypassIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_allow_bypass }
    val node17 = WgControlState(context, checkable = false)
    val genSettingsAllowBypassTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_allow_bypass_heading) }
    val node19 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_allow_bypass_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityAllowBypassSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val settingsActivityAllowBypassProgress = WgControlState(context, checkable = false) .apply { visibility = View.GONE }
    val node22 = WgControlState(context, checkable = false)
    val settingsActivityLanTrafficRl = WgControlState(context, checkable = false)
    val settingsActivityLanTrafficIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_private_network }
    val node25 = WgControlState(context, checkable = false)
    val genSettingsLanTrafficTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_allow_lan_heading) }
    val node27 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_allow_lan_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityLanTrafficSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node29 = WgControlState(context, checkable = false)
    val settingsActivityAllNetworkRl = WgControlState(context, checkable = false)
    val settingsActivityAllNetworkIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_network }
    val node32 = WgControlState(context, checkable = false)
    val genSettingsAllNetworkTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_network_all_networks) }
    val node34 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_network_all_networks_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityAllNetworkSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node36 = WgControlState(context, checkable = false)
    val settingsActivityTunnelMeteredRl = WgControlState(context, checkable = false)
    val settingsActivityTunnelMeteredIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_allow_metered }
    val node39 = WgControlState(context, checkable = false)
    val genSettingsTunnelMeteredTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_tunnel_metered) }
    val node41 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_tunnel_metered_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityTunnelMeteredSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val dividerTunnelMetered = WgControlState(context, checkable = false)
    val settingsActivityMobileMeteredRl = WgControlState(context, checkable = false)
    val settingsActivityMobileMeteredIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_meter_mobile_only }
    val node46 = WgControlState(context, checkable = false)
    val genSettingsMobileMeteredTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_mobile_metered_heading) }
    val node48 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_mobile_metered_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityMobileMeteredSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node50 = WgControlState(context, checkable = false)
    val settingsRInRRl = WgControlState(context, checkable = false)
    val settingsRInRIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_loopback }
    val node53 = WgControlState(context, checkable = false)
    val genRInRTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_rinr_heading) }
    val genRInRDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_rinr_desc_enabled); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsRInRSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch; imageResource = R.drawable.ic_right_arrow_small }
    val node57 = WgControlState(context, checkable = false)
    val settingsActivityDefaultDnsRl = WgControlState(context, checkable = false)
    val settingsActivityDefaultDnsIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_fallback }
    val node60 = WgControlState(context, checkable = false)
    val settingsActivityDefaultDnsHeading = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_default_dns_heading) }
    val settingsActivityDefaultDnsDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_default_dns_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityDefaultDnsImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val node64 = WgControlState(context, checkable = false)
    val settingsVpnProcessPolicyRl = WgControlState(context, checkable = false)
    val settingsVpnNwPolicyIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_tun_nw_policy }
    val node67 = WgControlState(context, checkable = false)
    val settingsVpnNwPolicyHeading = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.vpn_policy_title) }
    val settingsVpnNwPolicyDesc = WgControlState(context, checkable = false) .apply { textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsVpnNwPolicyImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val settingsActivityWireguardText = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_proxy_header); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node72 = WgControlState(context, checkable = false)
    val node73 = WgControlState(context, checkable = false)
    val settingsActivityExcludeProxyAppsRl = WgControlState(context, checkable = false)
    val settingsActivityExcludeProxyAppsIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_loopback_proxy }
    val node76 = WgControlState(context, checkable = false)
    val genSettingsExcludeProxyAppsTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_exclude_proxy_apps_heading) }
    val node78 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_exclude_proxy_apps_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityExcludeProxyAppsSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node80 = WgControlState(context, checkable = false)
    val dvWgListenPortRl = WgControlState(context, checkable = false)
    val dvWgListenPortIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_randomize_wg_port }
    val node83 = WgControlState(context, checkable = false)
    val dvWgListenPortTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.do_not_randomize_listen_port_title) }
    val dvWgListenPortDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.do_not_randomize_listen_port_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvWgListenPortSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node87 = WgControlState(context, checkable = false)
    val dvWgAllowIncomingRl = WgControlState(context, checkable = false)
    val dvWgAllowIncomingIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_accept_incoming_wg }
    val node90 = WgControlState(context, checkable = false)
    val dvWgAllowIncomingTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_allow_incoming_wg_packets) }
    val dvWgAllowIncomingDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_allow_incoming_wg_packets_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvWgAllowIncomingSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val dividerWgAllowIncoming = WgControlState(context, checkable = false)
    val dvWgLockdownRl = WgControlState(context, checkable = false)
    val dvWgLockdownIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_device_lock }
    val node97 = WgControlState(context, checkable = false)
    val dvWgLockdownTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_wireguard_lockdown_mode) }
    val dvWgLockdownDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_wireguard_lockdown_mode_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvWgLockdownSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val dividerFloodWg = WgControlState(context, checkable = false)
    val dvFloodWgRl = WgControlState(context, checkable = false)
    val dvFloodWgIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_flood_wg }
    val node104 = WgControlState(context, checkable = false)
    val dvFloodWgTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_flood_wg) }
    val dvFloodWgDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_flood_wg_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvFloodWgSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node108 = WgControlState(context, checkable = false)
    val dvWgSmartPersistentKeepaliveRl = WgControlState(context, checkable = false)
    val dvWgSmartPersistentKeepaliveIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_tcp_keep_alive }
    val node111 = WgControlState(context, checkable = false)
    val dvWgSmartPersistentKeepaliveTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_smart_persistent_keepalive) }
    val dvWgSmartPersistentKeepaliveDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_smart_persistent_keepalive_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvWgSmartPersistentKeepaliveSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val settingsActivityTcpText = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.orbot_status_arg_2); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node116 = WgControlState(context, checkable = false)
    val node117 = WgControlState(context, checkable = false)
    val dvTcpKeepAliveRl = WgControlState(context, checkable = false)
    val dvTcpKeepAliveIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_tcp_keep_alive }
    val node120 = WgControlState(context, checkable = false)
    val dvTcpKeepAliveTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.tcp_keep_alive_title) }
    val dvTcpKeepAliveDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.tcp_keep_alive_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvTcpKeepAliveSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node124 = WgControlState(context, checkable = false)
    val dvEimfRl = WgControlState(context, checkable = false)
    val dvEimfIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_endpoint_independent }
    val node127 = WgControlState(context, checkable = false)
    val dvEimfTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.endpoint_independent_mapping_title) }
    val dvEimfDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.endpoint_independent_mapping_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvEimfSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node131 = WgControlState(context, checkable = false)
    val dvTimeoutRl = WgControlState(context, checkable = false)
    val dvTimeoutIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_idle_timeout }
    val node134 = WgControlState(context, checkable = false)
    val node135 = WgControlState(context, checkable = false)
    val dvTimeoutLl = WgControlState(context, checkable = false)
    val dvTimeoutTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.adv_set_dial_timeout_title) }
    val dvTimeoutDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.adv_set_dial_timeout_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvTimeoutValue = WgControlState(context, checkable = false) .apply { text = "" }
    val dvTimeoutSeekbar = NetworkSeekState() .apply { max = 120; progress = 0 }
    val node141 = WgControlState(context, checkable = false)
    val settingsUseMaxMtuRl = WgControlState(context, checkable = false)
    val settingsUseMaxMtuIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_bandwidth_booster }
    val node144 = WgControlState(context, checkable = false)
    val settingsUseMaxMtuHeading = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_jumbo_packets) }
    val settingsUseMaxMtuDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_jumbo_packets_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsUseMaxMtuSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node148 = WgControlState(context, checkable = false)
    val dvSocketBufferSizeRl = WgControlState(context, checkable = false)
    val dvSocketBufferSizeIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_socket_buffer_size }
    val node151 = WgControlState(context, checkable = false)
    val node152 = WgControlState(context, checkable = false)
    val dvSocketBufferSizeLl = WgControlState(context, checkable = false)
    val dvSocketBufferSizeTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_socket_buffer_size) }
    val dvSocketBufferSizeDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_socket_buffer_size_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvSocketBufferSizeValue = WgControlState(context, checkable = false) .apply { text = "" }
    val dvSocketBufferSizeSeekbar = NetworkSeekState() .apply { max = 7; progress = 0 }
    val node158 = WgControlState(context, checkable = false)
    val settingsActivityIpRl = WgControlState(context, checkable = false)
    val settingsActivityIpIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_ip_network }
    val node161 = WgControlState(context, checkable = false)
    val genSettingsIpTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_ip_network_heading) }
    val genSettingsIpDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_ip_network_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityIpImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val dividerIp = WgControlState(context, checkable = false)
    val settingsActivityPtransRl = WgControlState(context, checkable = false)
    val settingsActivityPtransIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_protocol_translations }
    val node168 = WgControlState(context, checkable = false)
    val genSettingsPtransTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_ptrans_new_heading) }
    val genSettingsPtransDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_ptrans_new_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityPtransSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch; imageResource = R.drawable.ic_right_arrow_small }
    val dividerPtrans = WgControlState(context, checkable = false)
    val settingsActivityConnectivityChecksRl = WgControlState(context, checkable = false)
    val settingsActivityConnectivityChecksIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_connectivity_checks }
    val settingsActivityConnectivityChecksLl = WgControlState(context, checkable = false)
    val genSettingsConnectivityChecksTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_connectivity_checks) }
    val node177 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_connectivity_checks_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityConnectivityChecksImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val settingsActivityPingIpsBtn = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.rt_edit_dialog_positive); textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor) }
    val node180 = WgControlState(context, checkable = false)
    val node181 = WgControlState(context, checkable = false)
    val settingsCustomLanIpRl = WgControlState(context, checkable = false)
    val settingsCustomLanIpIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_tun_nw_policy }
    val node184 = WgControlState(context, checkable = false)
    val settingsCustomLanIpHeading = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.custom_lan_ip_title) }
    val settingsCustomLanIpDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.custom_lan_ip_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsCustomLanIpImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val layout = NetworkNode(NetworkKind.SCROLL, control = node0, fillWidth = true, children = listOf(
        NetworkNode(NetworkKind.COLUMN, control = node1, fillWidth = true, children = listOf(
            NetworkNode(NetworkKind.ROW, control = node2, margin = NetworkInsets(55f, 10f, 20f, 0f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = settingsActivityVpnHeadingText, key = "network.settings_activity_vpn_heading_text", appearance = R.style.TextAppearance_Material3_TitleSmall, padding = NetworkInsets(5f, 5f, 5f, 5f), flexible = true),
                NetworkNode(NetworkKind.ICON, control = settingsRestoreDefaults, key = "network.settings_restore_defaults", padding = NetworkInsets(5f, 5f, 5f, 5f), trailing = true)
            )),
            NetworkNode(NetworkKind.TEXT, control = settingsActivityVpnLockdownDesc, key = "network.settings_activity_vpn_lockdown_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, leadingIcon = R.drawable.dis_allowed, iconGap = 10f, padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true),
            NetworkNode(NetworkKind.CARD, control = node6, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node7, padding = NetworkInsets(0f, 5f, 0f, 5f), fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsStallNoNwRl, key = "network.settings_stall_no_nw_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsStallNoNwIcon, key = "network.settings_stall_no_nw_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node10, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genStallNoNwTxt, key = "network.gen_stall_no_nw_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genStallNoNwDesc, key = "network.gen_stall_no_nw_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsStallNoNwSwitch, key = "network.settings_stall_no_nw_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node14, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityAllowBypassRl, key = "network.settings_activity_allow_bypass_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityAllowBypassIcon, key = "network.settings_activity_allow_bypass_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node17, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsAllowBypassTxt, key = "network.gen_settings_allow_bypass_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node19, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.OVERLAY, children = listOf(NetworkNode(NetworkKind.SWITCH, control = settingsActivityAllowBypassSwitch, key = "network.settings_activity_allow_bypass_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true), NetworkNode(NetworkKind.PROGRESS, control = settingsActivityAllowBypassProgress, key = "network.settings_activity_allow_bypass_progress", margin = NetworkInsets(8f, 0f, 8f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), width = 48f, height = 48f, trailing = true)))
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node22, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityLanTrafficRl, key = "network.settings_activity_lan_traffic_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityLanTrafficIcon, key = "network.settings_activity_lan_traffic_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node25, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsLanTrafficTxt, key = "network.gen_settings_lan_traffic_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node27, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityLanTrafficSwitch, key = "network.settings_activity_lan_traffic_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node29, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityAllNetworkRl, key = "network.settings_activity_all_network_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityAllNetworkIcon, key = "network.settings_activity_all_network_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node32, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsAllNetworkTxt, key = "network.gen_settings_all_network_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node34, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityAllNetworkSwitch, key = "network.settings_activity_all_network_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node36, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityTunnelMeteredRl, key = "network.settings_activity_tunnel_metered_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityTunnelMeteredIcon, key = "network.settings_activity_tunnel_metered_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node39, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsTunnelMeteredTxt, key = "network.gen_settings_tunnel_metered_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node41, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityTunnelMeteredSwitch, key = "network.settings_activity_tunnel_metered_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerTunnelMetered, key = "network.divider_tunnel_metered", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityMobileMeteredRl, key = "network.settings_activity_mobile_metered_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityMobileMeteredIcon, key = "network.settings_activity_mobile_metered_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node46, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsMobileMeteredTxt, key = "network.gen_settings_mobile_metered_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node48, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityMobileMeteredSwitch, key = "network.settings_activity_mobile_metered_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node50, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsRInRRl, key = "network.settings_r_in_r_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsRInRIcon, key = "network.settings_r_in_r_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node53, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genRInRTxt, key = "network.gen_r_in_r_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genRInRDesc, key = "network.gen_r_in_r_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsRInRSwitch, key = "network.settings_r_in_r_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node57, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityDefaultDnsRl, key = "network.settings_activity_default_dns_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityDefaultDnsIcon, key = "network.settings_activity_default_dns_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node60, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityDefaultDnsHeading, key = "network.settings_activity_default_dns_heading", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityDefaultDnsDesc, key = "network.settings_activity_default_dns_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityDefaultDnsImg, key = "network.settings_activity_default_dns_img", padding = NetworkInsets(20f, 0f, 20f, 0f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node64, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsVpnProcessPolicyRl, key = "network.settings_vpn_process_policy_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsVpnNwPolicyIcon, key = "network.settings_vpn_nw_policy_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node67, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = settingsVpnNwPolicyHeading, key = "network.settings_vpn_nw_policy_heading", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = settingsVpnNwPolicyDesc, key = "network.settings_vpn_nw_policy_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsVpnNwPolicyImg, key = "network.settings_vpn_nw_policy_img", padding = NetworkInsets(20f, 0f, 20f, 0f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.TEXT, control = settingsActivityWireguardText, key = "network.settings_activity_wireguard_text", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node72, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node73, padding = NetworkInsets(0f, 5f, 0f, 5f), fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityExcludeProxyAppsRl, key = "network.settings_activity_exclude_proxy_apps_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityExcludeProxyAppsIcon, key = "network.settings_activity_exclude_proxy_apps_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node76, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsExcludeProxyAppsTxt, key = "network.gen_settings_exclude_proxy_apps_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node78, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityExcludeProxyAppsSwitch, key = "network.settings_activity_exclude_proxy_apps_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node80, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvWgListenPortRl, key = "network.dv_wg_listen_port_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvWgListenPortIcon, key = "network.dv_wg_listen_port_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node83, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = dvWgListenPortTxt, key = "network.dv_wg_listen_port_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = dvWgListenPortDesc, key = "network.dv_wg_listen_port_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvWgListenPortSwitch, key = "network.dv_wg_listen_port_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node87, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvWgAllowIncomingRl, key = "network.dv_wg_allow_incoming_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvWgAllowIncomingIcon, key = "network.dv_wg_allow_incoming_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node90, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = dvWgAllowIncomingTxt, key = "network.dv_wg_allow_incoming_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = dvWgAllowIncomingDesc, key = "network.dv_wg_allow_incoming_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvWgAllowIncomingSwitch, key = "network.dv_wg_allow_incoming_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerWgAllowIncoming, key = "network.divider_wg_allow_incoming", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvWgLockdownRl, key = "network.dv_wg_lockdown_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvWgLockdownIcon, key = "network.dv_wg_lockdown_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node97, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = dvWgLockdownTxt, key = "network.dv_wg_lockdown_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = dvWgLockdownDesc, key = "network.dv_wg_lockdown_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvWgLockdownSwitch, key = "network.dv_wg_lockdown_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerFloodWg, key = "network.divider_flood_wg", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvFloodWgRl, key = "network.dv_flood_wg_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvFloodWgIcon, key = "network.dv_flood_wg_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node104, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = dvFloodWgTxt, key = "network.dv_flood_wg_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = dvFloodWgDesc, key = "network.dv_flood_wg_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvFloodWgSwitch, key = "network.dv_flood_wg_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node108, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvWgSmartPersistentKeepaliveRl, key = "network.dv_wg_smart_persistent_keepalive_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvWgSmartPersistentKeepaliveIcon, key = "network.dv_wg_smart_persistent_keepalive_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node111, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = dvWgSmartPersistentKeepaliveTxt, key = "network.dv_wg_smart_persistent_keepalive_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = dvWgSmartPersistentKeepaliveDesc, key = "network.dv_wg_smart_persistent_keepalive_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvWgSmartPersistentKeepaliveSwitch, key = "network.dv_wg_smart_persistent_keepalive_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.TEXT, control = settingsActivityTcpText, key = "network.settings_activity_tcp_text", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node116, margin = NetworkInsets(4f, 4f, 4f, 80f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node117, padding = NetworkInsets(0f, 0f, 0f, 10f), fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = dvTcpKeepAliveRl, key = "network.dv_tcp_keep_alive_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvTcpKeepAliveIcon, key = "network.dv_tcp_keep_alive_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node120, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = dvTcpKeepAliveTxt, key = "network.dv_tcp_keep_alive_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = dvTcpKeepAliveDesc, key = "network.dv_tcp_keep_alive_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvTcpKeepAliveSwitch, key = "network.dv_tcp_keep_alive_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node124, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvEimfRl, key = "network.dv_eimf_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvEimfIcon, key = "network.dv_eimf_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node127, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = dvEimfTxt, key = "network.dv_eimf_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = dvEimfDesc, key = "network.dv_eimf_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvEimfSwitch, key = "network.dv_eimf_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node131, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvTimeoutRl, key = "network.dv_timeout_rl", padding = NetworkInsets(0f, 15f, 0f, 5f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvTimeoutIcon, key = "network.dv_timeout_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node134, margin = NetworkInsets(0f, 0f, 10f, 0f), fillWidth = true, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.ROW, control = node135, fillWidth = true, children = listOf(
                                NetworkNode(NetworkKind.COLUMN, control = dvTimeoutLl, key = "network.dv_timeout_ll", flexible = true, children = listOf(
                                    NetworkNode(NetworkKind.TEXT, control = dvTimeoutTxt, key = "network.dv_timeout_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                                    NetworkNode(NetworkKind.TEXT, control = dvTimeoutDesc, key = "network.dv_timeout_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(0f, 3f, 0f, 0f))
                                )),
                                NetworkNode(NetworkKind.TEXT, control = dvTimeoutValue, key = "network.dv_timeout_value", appearance = R.style.TextAppearance_Material3_BodyMedium, margin = NetworkInsets(0f, 0f, 10f, 0f), trailing = true)
                            )),
                            NetworkNode(NetworkKind.SLIDER, slider = dvTimeoutSeekbar, key = "network.dv_timeout_seekbar", margin = NetworkInsets(0f, 5f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f), fillWidth = true)
                        ))
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node141, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsUseMaxMtuRl, key = "network.settings_use_max_mtu_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsUseMaxMtuIcon, key = "network.settings_use_max_mtu_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node144, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = settingsUseMaxMtuHeading, key = "network.settings_use_max_mtu_heading", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = settingsUseMaxMtuDesc, key = "network.settings_use_max_mtu_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsUseMaxMtuSwitch, key = "network.settings_use_max_mtu_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node148, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = dvSocketBufferSizeRl, key = "network.dv_socket_buffer_size_rl", padding = NetworkInsets(0f, 15f, 0f, 5f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = dvSocketBufferSizeIcon, key = "network.dv_socket_buffer_size_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node151, margin = NetworkInsets(0f, 0f, 10f, 0f), fillWidth = true, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.ROW, control = node152, fillWidth = true, children = listOf(
                                NetworkNode(NetworkKind.COLUMN, control = dvSocketBufferSizeLl, key = "network.dv_socket_buffer_size_ll", flexible = true, children = listOf(
                                    NetworkNode(NetworkKind.TEXT, control = dvSocketBufferSizeTxt, key = "network.dv_socket_buffer_size_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                                    NetworkNode(NetworkKind.TEXT, control = dvSocketBufferSizeDesc, key = "network.dv_socket_buffer_size_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(0f, 3f, 0f, 0f))
                                )),
                                NetworkNode(NetworkKind.TEXT, control = dvSocketBufferSizeValue, key = "network.dv_socket_buffer_size_value", appearance = R.style.TextAppearance_Material3_BodyMedium, margin = NetworkInsets(0f, 0f, 10f, 0f), trailing = true)
                            )),
                            NetworkNode(NetworkKind.SLIDER, slider = dvSocketBufferSizeSeekbar, key = "network.dv_socket_buffer_size_seekbar", margin = NetworkInsets(0f, 5f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f), fillWidth = true)
                        ))
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node158, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityIpRl, key = "network.settings_activity_ip_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityIpIcon, key = "network.settings_activity_ip_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node161, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsIpTxt, key = "network.gen_settings_ip_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsIpDesc, key = "network.gen_settings_ip_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityIpImg, key = "network.settings_activity_ip_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerIp, key = "network.divider_ip", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityPtransRl, key = "network.settings_activity_ptrans_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityPtransIcon, key = "network.settings_activity_ptrans_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node168, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsPtransTxt, key = "network.gen_settings_ptrans_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsPtransDesc, key = "network.gen_settings_ptrans_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityPtransSwitch, key = "network.settings_activity_ptrans_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerPtrans, key = "network.divider_ptrans", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityConnectivityChecksRl, key = "network.settings_activity_connectivity_checks_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityConnectivityChecksIcon, key = "network.settings_activity_connectivity_checks_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = settingsActivityConnectivityChecksLl, key = "network.settings_activity_connectivity_checks_ll", flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsConnectivityChecksTxt, key = "network.gen_settings_connectivity_checks_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node177, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityConnectivityChecksImg, key = "network.settings_activity_connectivity_checks_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.TEXT, control = settingsActivityPingIpsBtn, key = "network.settings_activity_ping_ips_btn", alignEnd = true, backgroundResource = R.drawable.rectangle_border_background, margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(15f, 7f, 15f, 7f)),
                    NetworkNode(NetworkKind.COLUMN, control = node180, padding = NetworkInsets(0f, 5f, 0f, 5f), fillWidth = true, children = listOf(
                        NetworkNode(NetworkKind.DIVIDER, control = node181, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                        NetworkNode(NetworkKind.ROW, control = settingsCustomLanIpRl, key = "network.settings_custom_lan_ip_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                            NetworkNode(NetworkKind.ICON, control = settingsCustomLanIpIcon, key = "network.settings_custom_lan_ip_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                            NetworkNode(NetworkKind.COLUMN, control = node184, flexible = true, children = listOf(
                                NetworkNode(NetworkKind.TEXT, control = settingsCustomLanIpHeading, key = "network.settings_custom_lan_ip_heading", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                                NetworkNode(NetworkKind.TEXT, control = settingsCustomLanIpDesc, key = "network.settings_custom_lan_ip_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                            )),
                            NetworkNode(NetworkKind.ICON, control = settingsCustomLanIpImg, key = "network.settings_custom_lan_ip_img", padding = NetworkInsets(20f, 0f, 20f, 0f), trailing = true)
                        ))
                    ))
                ))
            ))
        ))
    ))
    fun saveChecks(bundle: Bundle) {
        bundle.putBoolean("compose.TunnelSettingsState.settingsStallNoNwSwitch", settingsStallNoNwSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsActivityAllowBypassSwitch", settingsActivityAllowBypassSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsActivityLanTrafficSwitch", settingsActivityLanTrafficSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsActivityAllNetworkSwitch", settingsActivityAllNetworkSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsActivityTunnelMeteredSwitch", settingsActivityTunnelMeteredSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsActivityMobileMeteredSwitch", settingsActivityMobileMeteredSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsRInRSwitch", settingsRInRSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsActivityExcludeProxyAppsSwitch", settingsActivityExcludeProxyAppsSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.dvWgListenPortSwitch", dvWgListenPortSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.dvWgAllowIncomingSwitch", dvWgAllowIncomingSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.dvWgLockdownSwitch", dvWgLockdownSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.dvFloodWgSwitch", dvFloodWgSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.dvWgSmartPersistentKeepaliveSwitch", dvWgSmartPersistentKeepaliveSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.dvTcpKeepAliveSwitch", dvTcpKeepAliveSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.dvEimfSwitch", dvEimfSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsUseMaxMtuSwitch", settingsUseMaxMtuSwitch.isChecked)
        bundle.putBoolean("compose.TunnelSettingsState.settingsActivityPtransSwitch", settingsActivityPtransSwitch.isChecked)
        bundle.putInt("compose.TunnelSettingsState.dvTimeoutSeekbar", dvTimeoutSeekbar.progress)
        bundle.putInt("compose.TunnelSettingsState.dvSocketBufferSizeSeekbar", dvSocketBufferSizeSeekbar.progress)
    }
    fun restoreChecks(bundle: Bundle) {
        if (bundle.containsKey("compose.TunnelSettingsState.settingsStallNoNwSwitch")) settingsStallNoNwSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsStallNoNwSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsActivityAllowBypassSwitch")) settingsActivityAllowBypassSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsActivityAllowBypassSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsActivityLanTrafficSwitch")) settingsActivityLanTrafficSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsActivityLanTrafficSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsActivityAllNetworkSwitch")) settingsActivityAllNetworkSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsActivityAllNetworkSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsActivityTunnelMeteredSwitch")) settingsActivityTunnelMeteredSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsActivityTunnelMeteredSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsActivityMobileMeteredSwitch")) settingsActivityMobileMeteredSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsActivityMobileMeteredSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsRInRSwitch")) settingsRInRSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsRInRSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsActivityExcludeProxyAppsSwitch")) settingsActivityExcludeProxyAppsSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsActivityExcludeProxyAppsSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvWgListenPortSwitch")) dvWgListenPortSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.dvWgListenPortSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvWgAllowIncomingSwitch")) dvWgAllowIncomingSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.dvWgAllowIncomingSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvWgLockdownSwitch")) dvWgLockdownSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.dvWgLockdownSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvFloodWgSwitch")) dvFloodWgSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.dvFloodWgSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvWgSmartPersistentKeepaliveSwitch")) dvWgSmartPersistentKeepaliveSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.dvWgSmartPersistentKeepaliveSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvTcpKeepAliveSwitch")) dvTcpKeepAliveSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.dvTcpKeepAliveSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvEimfSwitch")) dvEimfSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.dvEimfSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsUseMaxMtuSwitch")) settingsUseMaxMtuSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsUseMaxMtuSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.settingsActivityPtransSwitch")) settingsActivityPtransSwitch.isChecked = bundle.getBoolean("compose.TunnelSettingsState.settingsActivityPtransSwitch")
        if (bundle.containsKey("compose.TunnelSettingsState.dvTimeoutSeekbar")) dvTimeoutSeekbar.progress = bundle.getInt("compose.TunnelSettingsState.dvTimeoutSeekbar")
        if (bundle.containsKey("compose.TunnelSettingsState.dvSocketBufferSizeSeekbar")) dvSocketBufferSizeSeekbar.progress = bundle.getInt("compose.TunnelSettingsState.dvSocketBufferSizeSeekbar")
    }
}
