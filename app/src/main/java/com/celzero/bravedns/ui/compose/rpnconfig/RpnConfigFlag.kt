/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rpnconfig

import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.LayoutDirection
import com.celzero.bravedns.util.UIUtils
import kotlin.math.min
import kotlin.math.roundToInt

class RpnConfigFlagState(private val context: Context) {
    var text by mutableStateOf<String?>(null)
        private set
    var drawable by mutableStateOf<Drawable?>(null)
        private set
    var spreadEnabled by mutableStateOf(false)
        private set
    fun setSpread(value: Boolean) { spreadEnabled = value }
    fun setFlagText(value: String?) { text = value?.takeIf { it.isNotBlank() }; drawable = null }
    fun setFlagDrawable(value: Drawable?, tintAttr: Int? = null) {
        if (tintAttr != null) value?.setTint(UIUtils.fetchColor(context, tintAttr)) else value?.setTintList(null)
        drawable = value; text = null
    }
    fun clear() { text = null; drawable = null }
}

/** Direct port of FlagWatermarkView's geometry and DST_IN radial mask, with no embedded View. */
@Composable
internal fun RpnConfigFlag(state: RpnConfigFlagState, modifier: Modifier) {
    if (state.text == null && state.drawable == null) return
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val side = if (state.spreadEnabled) h else min(80f * density, h * .92f)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val cx = (if (rtl) 0f else w) + (if (rtl) 1f else -1f) * (side / 2f - side * if (state.spreadEnabled) .5f else .3f)
        val cy = if (state.spreadEnabled) h / 2f else h - side / 2f
        val peak = if (state.spreadEnabled) .30f else .4f
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = RadialGradient(cx, cy, side * if (state.spreadEnabled) 1.1f else 5f,
                intArrayOf(android.graphics.Color.argb((255 * peak).roundToInt(), 255, 255, 255),
                    android.graphics.Color.argb((255 * peak * .72f).roundToInt(), 255, 255, 255), android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        }
        drawIntoCanvas { target ->
            val canvas = target.nativeCanvas
            val layer = canvas.saveLayer(0f, 0f, w, h, null)
            canvas.save()
            if (!state.spreadEnabled) canvas.rotate(-35f, cx, cy)
            val drawable = state.drawable
            if (drawable != null) {
                val iw = drawable.intrinsicWidth; val ih = drawable.intrinsicHeight
                val scale = if (iw > 0 && ih > 0) min(side / iw, side / ih) else 1f
                val dw = if (iw > 0) iw * scale else side; val dh = if (ih > 0) ih * scale else side
                drawable.setBounds((cx - dw / 2f).roundToInt(), (cy - dh / 2f).roundToInt(), (cx + dw / 2f).roundToInt(), (cy + dh / 2f).roundToInt())
                drawable.draw(canvas)
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = side * if (state.spreadEnabled) 1.35f else .95f }
                val fm = paint.fontMetrics
                canvas.drawText(state.text.orEmpty(), cx, cy - (fm.descent + fm.ascent) / 2f, paint)
            }
            canvas.restore(); canvas.drawRect(0f, 0f, w, h, mask); canvas.restoreToCount(layer)
        }
    }
}
