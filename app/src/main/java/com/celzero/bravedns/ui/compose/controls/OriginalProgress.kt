/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.controls

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.google.android.material.progressindicator.DeterminateDrawable
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.progressindicator.LinearProgressIndicatorSpec
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.R as MaterialR
import kotlin.math.roundToInt

/** Source resource IDs hold only the XML attributes; unspecified values inherit the actual MDC
 * 1.14 widget theme, including gap/stop/animation. These are Drawables in a Compose Canvas, not Views. */
internal val originalLinearProgressSources = mapOf(
    "activity_server_order_history:fetch_progress" to R.style.OriginalWgProgress_activity_server_order_history_fetch_progress,
    "dialog_server_loading:server_loading_timeout_bar" to R.style.OriginalWgProgress_dialog_server_loading_server_loading_timeout_bar,
    "list_item_wg_hop:wg_hop_list_progress" to R.style.OriginalWgProgress_list_item_wg_hop_wg_hop_list_progress,
    "list_item_statistics_summary:ss_progress" to R.style.OriginalWgProgress_list_item_statistics_summary_ss_progress,
)

data class OriginalProgressOverrides(val indicator: Int? = null, val track: Int? = null,
    val thicknessDp: Float? = null, val radiusDp: Float? = null, val gapDp: Float? = null,
    val stopSizeDp: Float? = null)

@SuppressLint("RestrictedApi")
internal fun resolveOriginalLinearProgress(context: Context, source: String, overrides: OriginalProgressOverrides = OriginalProgressOverrides()): LinearProgressIndicatorSpec {
    val style = requireNotNull(originalLinearProgressSources[source]) { "Unknown progress source: $source" }
    val spec = LinearProgressIndicatorSpec(context, null)
    val a = context.obtainOrderedStyledAttributes(style, intArrayOf(MaterialR.attr.trackThickness,
        MaterialR.attr.trackCornerRadius, MaterialR.attr.indicatorColor, MaterialR.attr.trackColor,
        MaterialR.attr.indicatorTrackGapSize, MaterialR.attr.trackStopIndicatorSize))
    val density = context.resources.displayMetrics.density
    fun px(value: Float) = (value * density).roundToInt()
    try {
        spec.trackThickness = overrides.thicknessDp?.let(::px) ?: if (a.hasValue(0)) a.getDimensionPixelSize(0, spec.trackThickness) else spec.trackThickness
        if (overrides.radiusDp != null || a.hasValue(1)) {
            spec.trackCornerRadius = minOf(overrides.radiusDp?.let(::px) ?: a.getDimensionPixelSize(1, 0), spec.trackThickness / 2)
            spec.useRelativeTrackCornerRadius = false
        }
        if (overrides.indicator != null || a.hasValue(2)) spec.indicatorColors = intArrayOf(overrides.indicator ?: a.getColor(2, spec.indicatorColors[0]))
        if (overrides.track != null || a.hasValue(3)) spec.trackColor = overrides.track ?: a.getColor(3, spec.trackColor)
        spec.indicatorTrackGapSize = overrides.gapDp?.let(::px) ?: if (a.hasValue(4)) a.getDimensionPixelSize(4, spec.indicatorTrackGapSize) else spec.indicatorTrackGapSize
        spec.trackStopIndicatorSize = overrides.stopSizeDp?.let(::px) ?: if (a.hasValue(5)) a.getDimensionPixelSize(5, spec.trackStopIndicatorSize) else spec.trackStopIndicatorSize
        return spec
    } finally { a.recycle() }
}

/** Expose range and let MDC's source drawing delegate own exact caps, gaps, stop and disjoint
 * indeterminate animation. In particular, do not replace theme gap/stop with Compose defaults/zero. */
@SuppressLint("RestrictedApi")
@Composable fun OriginalLinearProgress(source: String, modifier: Modifier = Modifier, progress: Float = 0f,
    indeterminate: Boolean = false, overrides: OriginalProgressOverrides = OriginalProgressOverrides(),
    animate: Boolean = false, visible: Boolean = true) {
    val context = LocalContext.current; val density = LocalDensity.current; val configuration = LocalConfiguration.current
    val spec = remember(context, configuration, source, overrides) { resolveOriginalLinearProgress(context, source, overrides) }
    // The two Material drawables share a package-private superclass. Keep remember's
    // inferred return type public, otherwise the generated checkcast fails at runtime.
    val drawable = remember<Drawable>(spec, indeterminate) {
        if (indeterminate) {
            val result: Drawable = IndeterminateDrawable.createLinearDrawable(context, spec)
            result
        } else {
            val result: Drawable = DeterminateDrawable.createLinearDrawable(context, spec)
            result
        }
    }
    // MDC sets start/end direction in View.onLayout. Apply the same direction to the Canvas,
    // retaining the spec's own absolute right-to-left handling for direction == 1.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirror = (spec.indicatorDirection == 2 && rtl) || (spec.indicatorDirection == 3 && !rtl)
    val fraction = progress.coerceIn(0f, 1f)
    SideEffect {
        drawable.level = (fraction * 10000).roundToInt()
        if (!animate) drawable.jumpToCurrentState()
    }
    OriginalProgressDrawable(drawable, modifier.fillMaxWidth().height(with(density) { spec.trackThickness.toDp() })
        .semantics { progressBarRangeInfo = if (indeterminate) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f) },
        visible = visible, mirror = mirror)
}

/** dialog_server_loading's circular source inherits inset, gap, track and corner defaults. */
@SuppressLint("RestrictedApi")
@Composable fun OriginalServerLoadingSpinner(modifier: Modifier = Modifier) {
    val context = LocalContext.current; val density = LocalDensity.current; val configuration = LocalConfiguration.current
    val spec = remember(context, configuration) {
        CircularProgressIndicatorSpec(context, null).apply {
            val a = context.obtainOrderedStyledAttributes(R.style.OriginalWgProgress_dialog_server_loading_server_loading_spinner,
                intArrayOf(MaterialR.attr.indicatorSize, MaterialR.attr.trackThickness))
            try { indicatorSize = a.getDimensionPixelSize(0, indicatorSize); trackThickness = a.getDimensionPixelSize(1, trackThickness) }
            finally { a.recycle() }
        }
    }
    val drawable = remember(spec) { IndeterminateDrawable.createCircularDrawable(context, spec) }
    OriginalProgressDrawable(drawable, modifier.size(with(density) { (spec.indicatorSize + spec.indicatorInset * 2).toDp() })
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, visible = true)
}

/** Source ProgressBar uses the platform horizontal drawable and its secondary layer/tints. */
@Composable fun OriginalHorizontalProgress(modifier: Modifier = Modifier, progress: Float, secondary: Float,
    visible: Boolean = true) {
    val context = LocalContext.current; val configuration = LocalConfiguration.current; val density = LocalDensity.current
    val look = remember(context, configuration) {
        val a = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.progressDrawable, android.R.attr.minHeight,
            android.R.attr.maxHeight, android.R.attr.progressTint, android.R.attr.secondaryProgressTint,
            android.R.attr.progressBackgroundTint, android.R.attr.mirrorForRtl), android.R.attr.progressBarStyleHorizontal, 0)
        try {
            val drawable = requireNotNull(a.getDrawable(0)).mutate()
            val layers = drawable as? LayerDrawable
            listOf(android.R.id.progress, android.R.id.secondaryProgress, android.R.id.background).forEachIndexed { i, id ->
                a.getColorStateList(i + 3)?.let { layers?.findDrawableByLayerId(id)?.setTintList(it) }
            }
            val min = a.getDimensionPixelSize(1, 0); val max = a.getDimensionPixelSize(2, Int.MAX_VALUE)
            Triple(drawable, drawable.intrinsicHeight.coerceAtLeast(min).coerceAtMost(max).coerceAtLeast(1), a.getBoolean(6, false))
        } finally { a.recycle() }
    }
    val fraction = progress.coerceIn(0f, 1f)
    SideEffect {
        (look.first as? LayerDrawable)?.let {
            it.findDrawableByLayerId(android.R.id.background)?.level = 10000
            it.findDrawableByLayerId(android.R.id.secondaryProgress)?.level = (secondary.coerceIn(0f, 1f) * 10000).roundToInt()
            it.findDrawableByLayerId(android.R.id.progress)?.level = (fraction * 10000).roundToInt()
        } ?: run { look.first.level = (fraction * 10000).roundToInt() }
    }
    val mirror = look.third && LocalLayoutDirection.current == LayoutDirection.Rtl
    OriginalProgressDrawable(look.first, modifier.fillMaxWidth().height(with(density) { look.second.toDp() })
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }, visible, mirror)
}

@Composable private fun OriginalProgressDrawable(drawable: Drawable, modifier: Modifier, visible: Boolean, mirror: Boolean = false) {
    var redraw by remember(drawable) { mutableIntStateOf(0) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(drawable, visible) {
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { redraw++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) { handler.postAtTime(what, drawable, whenMillis) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what) }
        }
        drawable.setVisible(visible, true)
        onDispose { drawable.setVisible(false, false); drawable.callback = null; handler.removeCallbacksAndMessages(drawable) }
    }
    Canvas(modifier) {
        redraw // Snapshot read invalidates this draw scope on source animation frames.
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas; val save = native.save()
            if (mirror) native.scale(-1f, 1f, size.width / 2f, size.height / 2f)
            drawable.draw(native); native.restoreToCount(save)
        }
    }
}
