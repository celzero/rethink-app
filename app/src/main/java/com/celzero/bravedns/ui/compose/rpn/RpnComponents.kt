package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.graphics.Typeface
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

@Composable
fun RpnText(state: RpnControlState, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    if (state.visibility == android.view.View.GONE) return
    Text(state.text.toString(), modifier.rpnVisibility(state).rpnMotion(state), maxLines = state.maxLines, overflow = TextOverflow.Ellipsis, style = style.copy(fontSize = state.textSizeSp?.sp ?: style.fontSize, fontWeight = if (state.bold) FontWeight.Bold else style.fontWeight, fontFamily = if (state.monospace) FontFamily.Monospace else style.fontFamily),
        color = state.foregroundColor?.let(::Color) ?: themeColor(R.attr.primaryTextColor))
}

/** A button/chip label inherits its source style, with only actual controller overrides applied. */
@Composable
fun OriginalRpnText(state: RpnControlState) {
    if (state.visibility == android.view.View.GONE) return
    val style = androidx.compose.material3.LocalTextStyle.current
    Text(state.text.toString(), Modifier.rpnVisibility(state).rpnMotion(state), maxLines = state.maxLines,
        overflow = TextOverflow.Ellipsis, color = state.foregroundColor?.let(::Color) ?: androidx.compose.material3.LocalContentColor.current,
        style = style.copy(fontSize = state.textSizeSp?.sp ?: style.fontSize,
            fontWeight = if (state.bold) FontWeight.Bold else style.fontWeight,
            fontFamily = if (state.monospace) FontFamily.Monospace else style.fontFamily))
}

fun Modifier.rpnHeroBackground(): Modifier = composed {
    val start = themeColor(R.attr.serverHeaderGradientStart)
    val center = themeColor(R.attr.serverHeaderGradientCenter)
    val end = themeColor(R.attr.serverHeaderGradientEnd)
    drawBehind { drawRect(Brush.linearGradient(listOf(start, center, end), Offset(size.width, size.height), Offset.Zero)) }
}

class RpnProgressState(context: android.content.Context) {
    val title = RpnControlState().apply { text = context.getString(R.string.rpn_restore_dialog_title) }
    val status = RpnControlState().apply { text = context.getString(R.string.rpn_restore_dialog_status_unregistering) }
    val hint = RpnControlState().apply { text = context.getString(R.string.rpn_restore_dialog_hint); alpha = .55f }
    val timeout = RpnControlState()
}

fun rpnProgressDialog(activity: android.app.Activity, state: RpnProgressState): android.app.Dialog {
    return android.app.Dialog(activity, R.style.App_Dialog_NoDim).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setCancelable(true)
        setWgContent(activity) { RpnProgressContent(state) }
        com.celzero.bravedns.util.UIUtils.capDialogWidth(this)
    }
}

@Composable
private fun RpnProgressContent(state: RpnProgressState) {
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 36.dp, bottom = 28.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            com.celzero.bravedns.ui.compose.controls.OriginalServerLoadingSpinner()
            Spacer(Modifier.height(28.dp))
            Text(state.title.text.toString(), Modifier.fillMaxWidth(), color = themeColor(R.attr.primaryTextColor),
                style = utilityTextStyle(size = 17f, family = "sans-serif-medium", weight = Typeface.BOLD),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(state.status.text.toString(), Modifier.fillMaxWidth().rpnMotion(state.status), minLines = 2,
                style = utilityTextStyle(size = utilityDimensionSp(LocalContext.current, R.dimen.default_font_text_view)),
                color = themeColor(R.attr.primaryLightColorText), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            com.celzero.bravedns.ui.compose.controls.OriginalLinearProgress(
                "dialog_server_loading:server_loading_timeout_bar", Modifier.fillMaxWidth(),
                progress = state.timeout.progress.toFloat() / state.timeout.max.coerceAtLeast(1))
            Spacer(Modifier.height(20.dp))
            Text(state.hint.text.toString(), Modifier.fillMaxWidth().alpha(.55f), color = themeColor(R.attr.primaryLightColorText), style = utilityTextStyle(size = utilityDimensionSp(LocalContext.current, R.dimen.small_font_text_view)),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
