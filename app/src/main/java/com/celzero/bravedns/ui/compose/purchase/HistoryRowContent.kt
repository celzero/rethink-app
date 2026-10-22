/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.controls.OriginalLabel
import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import android.graphics.Typeface

class HistoryRowState {
    val tvTimestamp = RpnControlState()
    val tvStateBadge = RpnControlState()
    val tvTransition = RpnControlState()
    val tvReason = RpnControlState()
    val ivStatusIcon = RpnControlState()
    val tvPurchaseToken = RpnControlState()
    val divider = RpnControlState()
    val tvTypeBadge = RpnControlState()
    val ivTypeIcon = RpnControlState()
    val tvStatusChip = RpnControlState()
    val tvProductName = RpnControlState()
    val tvToken = RpnControlState()
    val tvDatePrimary = RpnControlState()
    val tvDateSecondary = RpnControlState()
    val tvOrderId = RpnControlState()
    val tvTestBadge = RpnControlState()
}

@Composable private fun HistoryChip(source: String, control: RpnControlState, modifier: Modifier = Modifier) {
    if (!control.isVisible) return
    OriginalLabel(source, modifier = modifier, backgroundTint = control.backgroundTintList,
        contentColor = control.foregroundColor?.let(::Color)) {
        val inherited = LocalTextStyle.current
        Text(control.text.toString(), maxLines = control.maxLines, overflow = TextOverflow.Ellipsis,
            style = inherited.copy(fontSize = control.textSizeSp?.sp ?: inherited.fontSize,
                fontWeight = if (control.bold) FontWeight.Bold else inherited.fontWeight,
                fontFamily = if (control.monospace) FontFamily.Monospace else inherited.fontFamily))
    }
}

/** XML text defaults plus the original controller's explicit presentation overrides. */
@Composable internal fun HistoryText(
    control: RpnControlState,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    color: Color = themeColor(R.attr.primaryLightColorText),
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamilyName: String = "sans-serif",
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Start
) {
    if (control.visibility == android.view.View.GONE) return
    Text(control.text.toString(), modifier.rpnVisibility(control).rpnMotion(control),
        color = control.foregroundColor?.let(::Color) ?: color,
        maxLines = minOf(control.maxLines, maxLines), overflow = TextOverflow.Ellipsis,
        style = utilityTextStyle(size = control.textSizeSp ?: fontSize.value,
            weight = if (control.bold || fontWeight == FontWeight.Bold) Typeface.BOLD else Typeface.NORMAL,
            family = if (control.monospace) "monospace" else fontFamilyName,
            spacing = 0f).copy(textAlign = textAlign))
}

@Composable fun HistoryRowContent(state: HistoryRowState, server: Boolean) {
    // Both original row layouts put the icon beside one text column; the divider is outside it.
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (server) 10.dp else 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top) {
            val icon = if (server) state.ivTypeIcon else state.ivStatusIcon
            Box(Modifier.size(if (server) 32.dp else 44.dp)
                .background(themeColor(if (server) R.attr.chipBgColorPositive else R.attr.chipBgColorNeutral), CircleShape),
                contentAlignment = Alignment.Center) {
                if (icon.imageRes != 0) Icon(painterResource(icon.imageRes), null, Modifier.size(24.dp),
                    tint = icon.imageTintList?.defaultColor?.let(::Color) ?: Color.Unspecified)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                if (server) ServerOrderText(state) else PurchaseHistoryText(state)
            }
        }
        if (state.divider.isVisible) HorizontalDivider(
            Modifier.padding(start = if (server) 55.dp else 78.dp),
            thickness = if (server) 1.dp else .5.dp, color = themeColor(R.attr.border))
    }
}

@Composable private fun ServerOrderText(state: HistoryRowState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HistoryText(state.tvProductName, Modifier.weight(1f), fontSize = rpnDimensionSp(R.dimen.large_font_text_view),
            color = themeColor(R.attr.primaryTextColor), fontWeight = FontWeight.Bold, fontFamilyName = "sans-serif-medium", maxLines = 1)
        HistoryChip("list_item_server_order:tv_status_chip", state.tvStatusChip, Modifier.padding(start = 8.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HistoryChip("list_item_server_order:tv_type_badge", state.tvTypeBadge)
        HistoryChip("list_item_server_order:tv_test_badge", state.tvTestBadge, Modifier.padding(start = 6.dp))
    }
    HistoryText(state.tvDatePrimary, Modifier.padding(top = 6.dp), fontSize = rpnDimensionSp(R.dimen.default_font_text_view))
    HistoryText(state.tvDateSecondary, Modifier.padding(top = 2.dp), fontSize = rpnDimensionSp(R.dimen.default_font_text_view))
    HistoryText(state.tvOrderId, Modifier.padding(top = 4.dp).alpha(.7f), fontSize = 11.sp,
        fontFamilyName = "monospace", maxLines = 1)
    HistoryText(state.tvToken, Modifier.padding(top = 2.dp).alpha(.5f), fontSize = rpnDimensionSp(R.dimen.small_font_subheading_text),
        fontFamilyName = "monospace", maxLines = 1)
}

@Composable private fun PurchaseHistoryText(state: HistoryRowState) {
    HistoryChip("list_item_purchase_history:tv_state_badge", state.tvStateBadge)
    HistoryText(state.tvTransition, Modifier.padding(top = 3.dp))
    if (state.tvTimestamp.visibility != android.view.View.GONE) Row(
        Modifier.padding(top = 4.dp).rpnVisibility(state.tvTimestamp), verticalAlignment = Alignment.CenterVertically) {
        val timer = painterResource(R.drawable.ic_timer)
        Icon(timer, null, Modifier.size(24.dp), tint = themeColor(R.attr.primaryLightColorText))
        HistoryText(state.tvTimestamp, Modifier.padding(start = 4.dp))
    }
    HistoryText(state.tvReason, Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 2)
    HistoryText(state.tvPurchaseToken, Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamilyName = "monospace")
}
