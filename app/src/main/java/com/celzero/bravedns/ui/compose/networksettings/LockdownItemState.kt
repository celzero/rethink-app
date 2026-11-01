/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksettings

import android.content.Context
import android.os.Bundle
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.util.UIUtils

/** Native presentation state. XML-derived order and defaults; no View inflation or backend access. */
class LockdownItemState(context: Context) {
    val node0 = WgControlState(context, checkable = false)
    val lockdownCheckItemIcon = WgControlState(context, checkable = false)
    val node2 = WgControlState(context, checkable = false)
    val lockdownCheckItemLabel = WgControlState(context, checkable = false)
    val lockdownCheckItemDesc = WgControlState(context, checkable = false) .apply { textColor = UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant) }
    val lockdownCheckItemStatus = WgControlState(context, checkable = false)
    val layout = NetworkNode(NetworkKind.ROW, control = node0, padding = NetworkInsets(0f, 12f, 0f, 12f), fillWidth = true, children = listOf(
        NetworkNode(NetworkKind.ICON, control = lockdownCheckItemIcon, key = "network.lockdown_check_item_icon", margin = NetworkInsets(0f, 0f, 12f, 0f), width = 24f, height = 24f),
        NetworkNode(NetworkKind.COLUMN, control = node2, width = 0f, weight = 1f, children = listOf(
            NetworkNode(NetworkKind.TEXT, control = lockdownCheckItemLabel, key = "network.lockdown_check_item_label", appearance = R.style.TextAppearance_Material3_BodyLarge),
            NetworkNode(NetworkKind.TEXT, control = lockdownCheckItemDesc, key = "network.lockdown_check_item_desc", appearance = R.style.TextAppearance_Material3_BodySmall, padding = NetworkInsets(0f, 2f, 0f, 0f))
        )),
        NetworkNode(NetworkKind.TEXT, control = lockdownCheckItemStatus, key = "network.lockdown_check_item_status", appearance = R.style.TextAppearance_Material3_LabelMedium, centerText = true, margin = NetworkInsets(8f, 0f, 0f, 0f), padding = NetworkInsets(8f, 4f, 8f, 4f))
    ))
}
