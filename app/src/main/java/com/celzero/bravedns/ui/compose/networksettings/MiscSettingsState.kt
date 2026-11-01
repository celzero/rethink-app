/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksettings

import android.content.Context
import android.os.Bundle
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.util.UIUtils

/** Native presentation state. XML-derived order and defaults; no View inflation or backend access. */
class MiscSettingsState(context: Context) {
    val node0 = WgControlState(context, checkable = false)
    val node1 = WgControlState(context, checkable = false)
    val settingsHeading = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_general_header); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node3 = WgControlState(context, checkable = false)
    val node4 = WgControlState(context, checkable = false)
    val settingsActivityCheckUpdateRl = WgControlState(context, checkable = false)
    val settingsActivityCheckUpdateIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_update }
    val node7 = WgControlState(context, checkable = false)
    val genSettingsCheckUpdateTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_check_update_heading) }
    val node9 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_check_update_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityCheckUpdateSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val dividerCheckUpdate = WgControlState(context, checkable = false)
    val settingsActivityImportExportRl = WgControlState(context, checkable = false)
    val settingsActivityImportExportIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_backup_restore }
    val node14 = WgControlState(context, checkable = false)
    val genSettingsImportExportTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_import_export_heading) }
    val node16 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_import_export_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityImportExportImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_arrow_down_small }
    val node18 = WgControlState(context, checkable = false)
    val tombstoneAppRl = WgControlState(context, checkable = false)
    val tombstoneAppIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_tombstone }
    val node21 = WgControlState(context, checkable = false)
    val tombstoneAppTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.tombstone_app_title) }
    val node23 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.tombstone_app_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val tombstoneAppSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node25 = WgControlState(context, checkable = false)
    val settingsFirewallBubbleRl = WgControlState(context, checkable = false)
    val settingsFirewallBubbleIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_firewall_shield }
    val node28 = WgControlState(context, checkable = false)
    val settingsFirewallBubbleTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.firewall_bubble_title) }
    val settingsFirewallBubbleDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.firewall_bubble_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsFirewallBubbleSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val dividerFirewallBubble = WgControlState(context, checkable = false)
    val settingsIpInfoRl = WgControlState(context, checkable = false)
    val settingsIpInfoIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_ip_info }
    val node35 = WgControlState(context, checkable = false)
    val genSettingsIpInfoTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.download_ip_info_title) }
    val genSettingsIpInfoDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.download_ip_info_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val dvIpInfoSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val settingsHeadingLogs = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.lbl_logs); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node40 = WgControlState(context, checkable = false)
    val node41 = WgControlState(context, checkable = false)
    val settingsActivityPcapRl = WgControlState(context, checkable = false)
    val settingsActivityPcapIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_pcap }
    val node44 = WgControlState(context, checkable = false)
    val settingsActivityPcapHeading = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_pcap_heading) }
    val settingsActivityPcapDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_pcap_dialog_option_1); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityPcapImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val node48 = WgControlState(context, checkable = false)
    val settingsActivityEnableLogsRl = WgControlState(context, checkable = false)
    val settingsActivityEnableLogsIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_logs }
    val node51 = WgControlState(context, checkable = false)
    val genSettingsEnableLogsTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_enable_logs) }
    val node53 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_enable_logs_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityEnableLogsSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val node55 = WgControlState(context, checkable = false)
    val settingsGoLogRl = WgControlState(context, checkable = false)
    val settingsGoLogIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_log_level }
    val node58 = WgControlState(context, checkable = false)
    val genSettingsGoLogTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_go_log_heading) }
    val genSettingsGoLogDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_go_log_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityGoLogImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val node62 = WgControlState(context, checkable = false)
    val settingsConsoleLogRl = WgControlState(context, checkable = false)
    val settingsConsoleLogIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_app_log }
    val node65 = WgControlState(context, checkable = false)
    val genSettingsConsoleLogTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.console_log_title) }
    val genSettingsConsoleLogDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_go_log_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityConsoleLogImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val node69 = WgControlState(context, checkable = false)
    val settingsLogLifespanRl = WgControlState(context, checkable = false)
    val settingsLogLifespanIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_log_lifespan }
    val node72 = WgControlState(context, checkable = false)
    val genSettingsLogLifespanTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_log_lifespan_heading) }
    val genSettingsLogLifespanDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_log_lifespan_desc_default); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityLogLifespanImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val settingsHeadingNotification = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.lbl_notification); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node77 = WgControlState(context, checkable = false)
    val node78 = WgControlState(context, checkable = false)
    val settingsActivityNotificationRl = WgControlState(context, checkable = false)
    val settingsActivityNotificationIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_notification }
    val node81 = WgControlState(context, checkable = false)
    val genSettingsNotificationTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_notification_heading) }
    val genSettingsNotificationDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_notification_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityNotificationImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val dividerNotification = WgControlState(context, checkable = false)
    val settingsActivityAppNotificationRl = WgControlState(context, checkable = false)
    val settingsActivityAppNotificationIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.show_notification }
    val node88 = WgControlState(context, checkable = false)
    val genSettingsAppNotificationTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_app_notification_a13_heading) }
    val node90 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_app_notification_a13_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityAppNotificationSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val dividerAppNotification = WgControlState(context, checkable = false)
    val settingsActivityAppNotificationPersistentRl = WgControlState(context, checkable = false)
    val settingsActivityAppNotificationPersistentIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.persistent_notification }
    val node95 = WgControlState(context, checkable = false)
    val genSettingsAppNotificationPersistentTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_app_notification_persistent_heading) }
    val node97 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_app_notification_persistent_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityAppNotificationPersistentSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val settingsHeadingCustomize = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_general_customize); textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary) }
    val node100 = WgControlState(context, checkable = false)
    val node101 = WgControlState(context, checkable = false)
    val settingsActivityThemeRl = WgControlState(context, checkable = false)
    val settingsActivityThemeIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_appearance }
    val node104 = WgControlState(context, checkable = false)
    val genSettingsThemeTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_theme_heading) }
    val genSettingsThemeDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_theme_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityThemeSwitchImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val node108 = WgControlState(context, checkable = false)
    val settingsLocaleRl = WgControlState(context, checkable = false)
    val settingsLocaleIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_translate }
    val node111 = WgControlState(context, checkable = false)
    val settingsLocaleTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_locale_heading) }
    val settingsLocaleDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_locale_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsLocaleImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val node115 = WgControlState(context, checkable = false)
    val settingsBiometricRl = WgControlState(context, checkable = false)
    val settingsBiometricIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_biometric }
    val node118 = WgControlState(context, checkable = false)
    val settingsBiometricTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_biometeric_heading) }
    val settingsBiometricDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_biometric_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsBiometricImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val dividerBiometric = WgControlState(context, checkable = false)
    val settingsTaskerRl = WgControlState(context, checkable = false)
    val settingsTaskerIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_tasker }
    val node125 = WgControlState(context, checkable = false)
    val genSettingsTaskerTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.adv_taster_title) }
    val genSettingsTaskerDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.adv_taster_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityTaskerImg = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_right_arrow_white }
    val node129 = WgControlState(context, checkable = false)
    val settingsActivityAutoStartRl = WgControlState(context, checkable = false)
    val settingsActivityAutoStartIcon = WgControlState(context, checkable = false) .apply { imageResource = R.drawable.ic_dns_cache }
    val node132 = WgControlState(context, checkable = false)
    val genSettingsAutoStartTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_autostart_bootup_heading) }
    val node134 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_autostart_bootup_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsActivityAutoStartSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val dividerAutoStart = WgControlState(context, checkable = false)
    val settingsFirebaseErrorReportingRl = WgControlState(context, checkable = false)
    val settingsFirebaseErrorReportingIcon = WgControlState(context, checkable = false) .apply { alpha = 0.5f; imageResource = R.drawable.ic_stability_improvement }
    val settingsFirebaseErrorReportingTexts = WgControlState(context, checkable = false)
    val genSettingsFirebaseErrorReportingTxt = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_firebase_error_reporting_heading) }
    val genSettingsFirebaseErrorReportingDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_firebase_error_reporting_desc); textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val settingsFirebaseErrorReportingSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val layout = NetworkNode(NetworkKind.SCROLL, control = node0, fillWidth = true, children = listOf(
        NetworkNode(NetworkKind.COLUMN, control = node1, fillWidth = true, children = listOf(
            NetworkNode(NetworkKind.TEXT, control = settingsHeading, key = "network.settings_heading", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node3, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node4, padding = NetworkInsets(0f, 5f, 0f, 5f), fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityCheckUpdateRl, key = "network.settings_activity_check_update_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityCheckUpdateIcon, key = "network.settings_activity_check_update_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node7, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsCheckUpdateTxt, key = "network.gen_settings_check_update_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node9, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityCheckUpdateSwitch, key = "network.settings_activity_check_update_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerCheckUpdate, key = "network.divider_check_update", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityImportExportRl, key = "network.settings_activity_import_export_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityImportExportIcon, key = "network.settings_activity_import_export_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node14, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsImportExportTxt, key = "network.gen_settings_import_export_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node16, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityImportExportImg, key = "network.settings_activity_import_export_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node18, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = tombstoneAppRl, key = "network.tombstone_app_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = tombstoneAppIcon, key = "network.tombstone_app_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node21, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = tombstoneAppTxt, key = "network.tombstone_app_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node23, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = tombstoneAppSwitch, key = "network.tombstone_app_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node25, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsFirewallBubbleRl, key = "network.settings_firewall_bubble_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsFirewallBubbleIcon, key = "network.settings_firewall_bubble_icon", margin = NetworkInsets(15f, 5f, 15f, 5f), width = 24f, height = 24f),
                        NetworkNode(NetworkKind.COLUMN, control = node28, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = settingsFirewallBubbleTxt, key = "network.settings_firewall_bubble_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = settingsFirewallBubbleDesc, key = "network.settings_firewall_bubble_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsFirewallBubbleSwitch, key = "network.settings_firewall_bubble_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerFirewallBubble, key = "network.divider_firewall_bubble", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsIpInfoRl, key = "network.settings_ip_info_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsIpInfoIcon, key = "network.settings_ip_info_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node35, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsIpInfoTxt, key = "network.gen_settings_ip_info_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsIpInfoDesc, key = "network.gen_settings_ip_info_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = dvIpInfoSwitch, key = "network.dv_ip_info_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.TEXT, control = settingsHeadingLogs, key = "network.settings_heading_logs", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node40, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node41, padding = NetworkInsets(0f, 5f, 0f, 5f), fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityPcapRl, key = "network.settings_activity_pcap_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityPcapIcon, key = "network.settings_activity_pcap_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node44, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityPcapHeading, key = "network.settings_activity_pcap_heading", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = settingsActivityPcapDesc, key = "network.settings_activity_pcap_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityPcapImg, key = "network.settings_activity_pcap_img", padding = NetworkInsets(20f, 0f, 20f, 0f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node48, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityEnableLogsRl, key = "network.settings_activity_enable_logs_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityEnableLogsIcon, key = "network.settings_activity_enable_logs_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node51, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsEnableLogsTxt, key = "network.gen_settings_enable_logs_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node53, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityEnableLogsSwitch, key = "network.settings_activity_enable_logs_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node55, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsGoLogRl, key = "network.settings_go_log_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsGoLogIcon, key = "network.settings_go_log_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node58, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsGoLogTxt, key = "network.gen_settings_go_log_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsGoLogDesc, key = "network.gen_settings_go_log_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityGoLogImg, key = "network.settings_activity_go_log_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node62, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsConsoleLogRl, key = "network.settings_console_log_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsConsoleLogIcon, key = "network.settings_console_log_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node65, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsConsoleLogTxt, key = "network.gen_settings_console_log_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsConsoleLogDesc, key = "network.gen_settings_console_log_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityConsoleLogImg, key = "network.settings_activity_console_log_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node69, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsLogLifespanRl, key = "network.settings_log_lifespan_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsLogLifespanIcon, key = "network.settings_log_lifespan_icon", hideSemantics = true, margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node72, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsLogLifespanTxt, key = "network.gen_settings_log_lifespan_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsLogLifespanDesc, key = "network.gen_settings_log_lifespan_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityLogLifespanImg, key = "network.settings_activity_log_lifespan_img", hideSemantics = true, margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.TEXT, control = settingsHeadingNotification, key = "network.settings_heading_notification", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node77, margin = NetworkInsets(4f, 4f, 4f, 4f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node78, padding = NetworkInsets(0f, 5f, 0f, 5f), fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityNotificationRl, key = "network.settings_activity_notification_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityNotificationIcon, key = "network.settings_activity_notification_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node81, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsNotificationTxt, key = "network.gen_settings_notification_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsNotificationDesc, key = "network.gen_settings_notification_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityNotificationImg, key = "network.settings_activity_notification_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerNotification, key = "network.divider_notification", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityAppNotificationRl, key = "network.settings_activity_app_notification_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityAppNotificationIcon, key = "network.settings_activity_app_notification_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node88, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsAppNotificationTxt, key = "network.gen_settings_app_notification_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node90, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityAppNotificationSwitch, key = "network.settings_activity_app_notification_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerAppNotification, key = "network.divider_app_notification", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityAppNotificationPersistentRl, key = "network.settings_activity_app_notification_persistent_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityAppNotificationPersistentIcon, key = "network.settings_activity_app_notification_persistent_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node95, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsAppNotificationPersistentTxt, key = "network.gen_settings_app_notification_persistent_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node97, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityAppNotificationPersistentSwitch, key = "network.settings_activity_app_notification_persistent_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            )),
            NetworkNode(NetworkKind.TEXT, control = settingsHeadingCustomize, key = "network.settings_heading_customize", appearance = R.style.TextAppearance_Material3_TitleSmall, margin = NetworkInsets(55f, 10f, 0f, 5f), padding = NetworkInsets(5f, 5f, 5f, 5f)),
            NetworkNode(NetworkKind.CARD, control = node100, margin = NetworkInsets(4f, 4f, 4f, 80f), fillWidth = true, cornerRadius = 8f, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node101, padding = NetworkInsets(0f, 5f, 0f, 5f), fillWidth = true, children = listOf(
                    NetworkNode(NetworkKind.ROW, control = settingsActivityThemeRl, key = "network.settings_activity_theme_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityThemeIcon, key = "network.settings_activity_theme_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node104, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsThemeTxt, key = "network.gen_settings_theme_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsThemeDesc, key = "network.gen_settings_theme_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityThemeSwitchImg, key = "network.settings_activity_theme_switch_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node108, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsLocaleRl, key = "network.settings_locale_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsLocaleIcon, key = "network.settings_locale_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node111, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = settingsLocaleTxt, key = "network.settings_locale_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = settingsLocaleDesc, key = "network.settings_locale_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsLocaleImg, key = "network.settings_locale_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node115, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsBiometricRl, key = "network.settings_biometric_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsBiometricIcon, key = "network.settings_biometric_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node118, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = settingsBiometricTxt, key = "network.settings_biometric_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = settingsBiometricDesc, key = "network.settings_biometric_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsBiometricImg, key = "network.settings_biometric_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerBiometric, key = "network.divider_biometric", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsTaskerRl, key = "network.settings_tasker_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsTaskerIcon, key = "network.settings_tasker_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node125, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsTaskerTxt, key = "network.gen_settings_tasker_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsTaskerDesc, key = "network.gen_settings_tasker_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.ICON, control = settingsActivityTaskerImg, key = "network.settings_activity_tasker_img", margin = NetworkInsets(0f, 0f, 10f, 0f), padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = node129, margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsActivityAutoStartRl, key = "network.settings_activity_auto_start_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsActivityAutoStartIcon, key = "network.settings_activity_auto_start_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = node132, flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsAutoStartTxt, key = "network.gen_settings_auto_start_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = node134, appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsActivityAutoStartSwitch, key = "network.settings_activity_auto_start_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    )),
                    NetworkNode(NetworkKind.DIVIDER, control = dividerAutoStart, key = "network.divider_auto_start", margin = NetworkInsets(56f, 5f, 16f, 5f), fillWidth = true),
                    NetworkNode(NetworkKind.ROW, control = settingsFirebaseErrorReportingRl, key = "network.settings_firebase_error_reporting_rl", padding = NetworkInsets(0f, 15f, 0f, 15f), children = listOf(
                        NetworkNode(NetworkKind.ICON, control = settingsFirebaseErrorReportingIcon, key = "network.settings_firebase_error_reporting_icon", margin = NetworkInsets(5f, 5f, 5f, 5f), padding = NetworkInsets(10f, 10f, 10f, 10f)),
                        NetworkNode(NetworkKind.COLUMN, control = settingsFirebaseErrorReportingTexts, key = "network.settings_firebase_error_reporting_texts", margin = NetworkInsets(0f, 0f, 0f, 5f), flexible = true, children = listOf(
                            NetworkNode(NetworkKind.TEXT, control = genSettingsFirebaseErrorReportingTxt, key = "network.gen_settings_firebase_error_reporting_txt", appearance = R.style.TextAppearance_Material3_BodyLarge_Emphasized),
                            NetworkNode(NetworkKind.TEXT, control = genSettingsFirebaseErrorReportingDesc, key = "network.gen_settings_firebase_error_reporting_desc", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 3f, 0f, 0f))
                        )),
                        NetworkNode(NetworkKind.SWITCH, control = settingsFirebaseErrorReportingSwitch, key = "network.settings_firebase_error_reporting_switch", padding = NetworkInsets(10f, 10f, 10f, 10f), trailing = true)
                    ))
                ))
            ))
        ))
    ))
    fun saveChecks(bundle: Bundle) {
        bundle.putBoolean("compose.MiscSettingsState.settingsActivityCheckUpdateSwitch", settingsActivityCheckUpdateSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.tombstoneAppSwitch", tombstoneAppSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.settingsFirewallBubbleSwitch", settingsFirewallBubbleSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.dvIpInfoSwitch", dvIpInfoSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.settingsActivityEnableLogsSwitch", settingsActivityEnableLogsSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.settingsActivityAppNotificationSwitch", settingsActivityAppNotificationSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.settingsActivityAppNotificationPersistentSwitch", settingsActivityAppNotificationPersistentSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.settingsActivityAutoStartSwitch", settingsActivityAutoStartSwitch.isChecked)
        bundle.putBoolean("compose.MiscSettingsState.settingsFirebaseErrorReportingSwitch", settingsFirebaseErrorReportingSwitch.isChecked)
    }
    fun restoreChecks(bundle: Bundle) {
        if (bundle.containsKey("compose.MiscSettingsState.settingsActivityCheckUpdateSwitch")) settingsActivityCheckUpdateSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.settingsActivityCheckUpdateSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.tombstoneAppSwitch")) tombstoneAppSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.tombstoneAppSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.settingsFirewallBubbleSwitch")) settingsFirewallBubbleSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.settingsFirewallBubbleSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.dvIpInfoSwitch")) dvIpInfoSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.dvIpInfoSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.settingsActivityEnableLogsSwitch")) settingsActivityEnableLogsSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.settingsActivityEnableLogsSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.settingsActivityAppNotificationSwitch")) settingsActivityAppNotificationSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.settingsActivityAppNotificationSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.settingsActivityAppNotificationPersistentSwitch")) settingsActivityAppNotificationPersistentSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.settingsActivityAppNotificationPersistentSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.settingsActivityAutoStartSwitch")) settingsActivityAutoStartSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.settingsActivityAutoStartSwitch")
        if (bundle.containsKey("compose.MiscSettingsState.settingsFirebaseErrorReportingSwitch")) settingsFirebaseErrorReportingSwitch.isChecked = bundle.getBoolean("compose.MiscSettingsState.settingsFirebaseErrorReportingSwitch")
    }
}
