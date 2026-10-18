/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksettings

import android.content.Context
import android.os.Bundle
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.util.UIUtils

/** Native presentation state. XML-derived order and defaults; no View inflation or backend access. */
class LockdownDialogState(context: Context) {
    val node0 = WgControlState(context, checkable = false)
    val node1 = WgControlState(context, checkable = false)
    val lockdownCheckSubtitle = WgControlState(context, checkable = false) .apply { textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val lockdownCheckItemsContainer = WgControlState(context, checkable = false)
    val lockdownCheckAllOk = WgControlState(context, checkable = false) .apply { textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary); visibility = View.GONE }
    val lockdownCheckDivider = WgControlState(context, checkable = false)
    val lockdownCheckDisableRow = WgControlState(context, checkable = false)
    val node7 = WgControlState(context, checkable = false)
    val lockdownCheckDisableLabel = WgControlState(context, checkable = false)
    val lockdownCheckDisableDesc = WgControlState(context, checkable = false) .apply { textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val lockdownCheckDisableSwitch = WgControlState(context, checkable = true) .apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch }
    val layout = NetworkNode(NetworkKind.SCROLL, control = node0, fillWidth = true, children = listOf(
        NetworkNode(NetworkKind.COLUMN, control = node1, padding = NetworkInsets(24f, 16f, 24f, 8f), fillWidth = true, children = listOf(
            NetworkNode(NetworkKind.TEXT, control = lockdownCheckSubtitle, key = "network.lockdown_check_subtitle", appearance = R.style.TextAppearance_Material3_BodyMedium, padding = NetworkInsets(0f, 0f, 0f, 12f), fillWidth = true),
            NetworkNode(NetworkKind.COLUMN, control = lockdownCheckItemsContainer, key = "network.lockdown_check_items_container", fillWidth = true),
            NetworkNode(NetworkKind.TEXT, control = lockdownCheckAllOk, key = "network.lockdown_check_all_ok", appearance = R.style.TextAppearance_Material3_BodyMedium, centerText = true, padding = NetworkInsets(0f, 12f, 0f, 12f), fillWidth = true),
            NetworkNode(NetworkKind.DIVIDER, control = lockdownCheckDivider, key = "network.lockdown_check_divider", margin = NetworkInsets(0f, 4f, 0f, 4f), fillWidth = true, height = 1f),
            NetworkNode(NetworkKind.ROW, control = lockdownCheckDisableRow, key = "network.lockdown_check_disable_row", padding = NetworkInsets(0f, 8f, 0f, 8f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node7, width = 0f, weight = 1f, children = listOf(
                    NetworkNode(NetworkKind.TEXT, control = lockdownCheckDisableLabel, key = "network.lockdown_check_disable_label", appearance = R.style.TextAppearance_Material3_BodyLarge),
                    NetworkNode(NetworkKind.TEXT, control = lockdownCheckDisableDesc, key = "network.lockdown_check_disable_desc", appearance = R.style.TextAppearance_Material3_BodySmall, padding = NetworkInsets(0f, 2f, 0f, 0f))
                )),
                NetworkNode(NetworkKind.SWITCH, control = lockdownCheckDisableSwitch, key = "network.lockdown_check_disable_switch")
            ))
        ))
    ))
}
