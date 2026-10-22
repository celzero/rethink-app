package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.controls.OriginalCardSurface
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle

/** All keys share one composition scope, so moving a tile to another row does not restart its jobs. */
@Composable
fun RpnServerGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        // Baseline GridLayoutManager(2) has no item decoration; card compat padding supplies gutters.
        val cell = (constraints.maxWidth / 2).coerceAtLeast(0)
        val heights = measurables.chunked(2).map { row -> row.maxOf { it.maxIntrinsicHeight(cell) } }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(cell, heights[index / 2]))
        }
        layout(constraints.maxWidth, heights.sum()) {
            var y = 0
            placeables.chunked(2).forEachIndexed { index, row ->
                row.forEachIndexed { column, child -> child.placeRelative(column * cell, y) }
                y += heights[index]
            }
        }
    }
}

@Composable
fun RpnServerTile(ui: RpnServerRowState, isAuto: Boolean) {
    OriginalCardSurface(Modifier.fillMaxWidth(), style = R.style.RethinkPlus_ContentCard) {
        Box(Modifier.fillMaxWidth().clickable { ui.serverCard.performClick() }) {
            RpnFlagWatermark(ui.flagWatermark, Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
                // Status follows the title's height, not the taller independently anchored remove icon.
                RpnText(ui.tvCountryName, Modifier.fillMaxWidth().padding(end = 32.dp),
                    tileTextStyle(16.sp, family = "sans-serif-medium").copy(letterSpacing = .01.em))
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.ivStatusCheck.isVisible) RpnImage(ui.ivStatusCheck, Modifier.padding(end = 4.dp).size(10.dp).alpha(.75f))
                    RpnText(ui.tvServerStatus, Modifier.padding(end = 4.dp), tileTextStyle(rpnDimensionSp(R.dimen.small_font_text_view)).copy(textAlign = TextAlign.Center))
                    RpnText(ui.tvServerIp, style = tileTextStyle(rpnDimensionSp(R.dimen.small_font_text_view), family = "monospace").copy(letterSpacing = .02.em))
                }
                if (isAuto) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Apps(ui); Recent(ui) }
                else Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) { Apps(ui); Recent(ui) }
                if (ui.relayActionContainer.visibility != android.view.View.GONE) RpnTileChip(ui.relayActionContainer, Modifier.padding(top = 10.dp)) {
                    RpnText(ui.relayAction, style = tileTextStyle(rpnDimensionSp(R.dimen.small_font_text_view), family = "sans-serif-medium")
                        .copy(letterSpacing = .02.em, textAlign = TextAlign.Center))
                    if (ui.relayIcon.visibility != android.view.View.GONE) RpnImage(ui.relayIcon, Modifier.padding(start = 4.dp).size(10.dp).rpnVisibility(ui.relayIcon))
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 8.dp).size(28.dp)
                .rpnMotion(ui.refreshStopIcon).clickable { ui.refreshStopIcon.performClick() }.padding(7.dp)) {
                RpnImage(ui.refreshStopIcon, Modifier.fillMaxSize())
            }
        }
    }
}
@Composable private fun tileTextStyle(size: androidx.compose.ui.unit.TextUnit, family: String? = null) =
    utilityTextStyle(size = size.value, family = family)

@Composable private fun Apps(ui: RpnServerRowState) {
    RpnTileChip(ui.appsActionContainer) {
        RpnText(ui.appsAction, Modifier.width(44.dp), tileTextStyle(rpnDimensionSp(R.dimen.small_font_text_view), family = "sans-serif-medium").copy(letterSpacing = .02.em, textAlign = TextAlign.Center))
        Icon(painterResource(R.drawable.ic_right_arrow_small), null, Modifier.padding(start = 4.dp).size(11.dp), tint = themeColor(R.attr.serverChipTextColor))
    }
}
@Composable private fun Recent(ui: RpnServerRowState) {
    RpnTileChip(ui.lastRoutedAppContainer, compact = true) {
        if (ui.lastRoutedAppIconStack.isVisible) Box(Modifier.size(36.dp, 20.dp)) {
            listOf(ui.routedAppIcon2 to 0, ui.routedAppIcon1 to 10, ui.routedAppIcon0 to 20).forEach { (state, x) ->
                if (state.isVisible) RpnImage(state, Modifier.align(Alignment.CenterStart).offset(x.dp).size(16.dp)
                    .background(themeColor(R.attr.vpnHeaderGradientStart), CircleShape)
                    .border(1.dp, themeColor(R.attr.border), CircleShape).padding(1.dp))
            }
        }
        if (ui.lastRoutedAppFallbackIcon.isVisible) RpnImage(ui.lastRoutedAppFallbackIcon, Modifier.size(12.dp))
        RpnText(ui.lastRoutedAppAction, Modifier.padding(start = 4.dp).width(32.dp).basicMarquee(iterations = Int.MAX_VALUE), tileTextStyle(10.sp, family = "sans-serif-medium").copy(letterSpacing = .02.em))
    }
}
@Composable private fun RpnTileChip(state: RpnControlState, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable RowScope.() -> Unit) {
    if (state.visibility == android.view.View.GONE) return
    val positive = state.backgroundRes == R.drawable.bg_vpn_server_chip_positive
    Row(modifier.rpnVisibility(state).clip(RoundedCornerShape(14.dp)).background(if (positive) themeColor(R.attr.chipBgColorPositive) else Color.Transparent)
        .border(1.dp, themeColor(if (positive) R.attr.chipTextPositive else R.attr.serverChipStrokeColor), RoundedCornerShape(14.dp))
        .semantics { state.contentDescription?.let { contentDescription = it.toString() } }
        .clickable(enabled = state.isEnabled && state.isClickable) { state.performClick() }
        .padding(start = if (compact) 6.dp else 12.dp, end = if (compact) 6.dp else 9.dp, top = if (compact) 3.dp else 6.dp, bottom = if (compact) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}
@Composable internal fun RpnImage(state: RpnControlState, modifier: Modifier = Modifier) {
    val drawable = state.drawableValue
    val description = state.contentDescription?.toString()
    val filter = state.imageTintList?.defaultColor?.let { androidx.compose.ui.graphics.ColorFilter.tint(Color(it)) }
    if (drawable != null) {
        val bitmap = remember(drawable) { drawable.toBitmap(width = drawable.intrinsicWidth.coerceAtLeast(1), height = drawable.intrinsicHeight.coerceAtLeast(1)).asImageBitmap() }
        Image(bitmap, description, modifier, colorFilter = filter)
    } else if (state.imageRes != 0) Image(painterResource(state.imageRes), description, modifier, colorFilter = filter)
}
@Composable fun RpnAddServerTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val borderColor = themeColor(R.attr.border)
    Column(modifier.fillMaxWidth().padding(6.dp).heightIn(min = 80.dp)
        .clip(RoundedCornerShape(16.dp)).drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(borderColor, topLeft = Offset(stroke / 2, stroke / 2),
                size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
        }.clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.ic_add), null, Modifier.size(26.dp).alpha(.8f), tint = themeColor(R.attr.primaryLightColorText))
        Text(stringResource(R.string.server_selection_add_tile), Modifier.padding(top = 6.dp).alpha(.8f),
            color = themeColor(R.attr.primaryLightColorText), maxLines = 1, overflow = TextOverflow.Clip,
            style = tileTextStyle(11.sp, family = "sans-serif-medium").copy(letterSpacing = .02.em))
    }
}
