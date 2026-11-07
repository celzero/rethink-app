/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.controls

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.android.material.R as MaterialR

/**
 * Compose owns gestures, keyboard, range semantics, and caller callbacks. Only its visual slots
 * change: AppCompatSeekBar uses theme drawables; Sponsor resolves its own MDC style, including
 * inherited thumb width/height (which take precedence over thumbRadius), gaps, ticks and elevation.
 * No controller or persistence code lives here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OriginalSlider(source: String, value: Float, onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val spec = requireNotNull(originalCompoundSpecs[source]) { "Unmapped original slider: $source" }
    require(spec.kind == OriginalCompoundKind.SeekBar || spec.kind == OriginalCompoundKind.Slider)
    val look = remember(context, configuration, density, spec) { resolveOriginalSlider(context, spec) }
    val interaction = remember { MutableInteractionSource() }
    val active = originalSliderActiveInteraction(interaction)
    val focused by interaction.collectIsFocusedAsState()
    val states = intArrayOf(if (enabled) android.R.attr.state_enabled else -android.R.attr.state_enabled,
        if (active) android.R.attr.state_pressed else -android.R.attr.state_pressed,
        if (focused) android.R.attr.state_focused else -android.R.attr.state_focused)
    fun color(tint: ColorStateList?) = tint?.let { Color(it.getColorForState(states, it.defaultColor)) } ?: Color.Transparent
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
    val thumbWidth = look.thumbWidth
    val thumbHeight = look.thumbHeight
    // MDC 1.14 halves its handle width while pressed; the measured slot stays stable.
    val paintedThumbWidth = if (look.material && active) thumbWidth * .5f else thumbWidth
    val trackGap = (look.thumbTrackGap - (thumbWidth - paintedThumbWidth) / 2f).coerceAtLeast(0f)
    // Compose lays out its track between thumb centers, subtracting half a thumb at either end.
    val extraSidePadding = if (look.material) (look.trackSidePadding - thumbWidth / 2f).coerceAtLeast(0f) else 0f
    Slider(value, onValueChange, modifier.semantics { originalCompoundSource = source }
        .padding(start = (look.start + extraSidePadding).dp, end = (look.end + extraSidePadding).dp, top = look.top.dp, bottom = look.bottom.dp)
        .heightIn(min = look.minHeight.dp), enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished, interactionSource = interaction,
        thumb = {
            Box(Modifier.size(thumbWidth.dp, thumbHeight.dp), contentAlignment = Alignment.Center) {
                if (look.material && enabled && (active || focused)) Canvas(Modifier.matchParentSize()) {
                    val halo = color(look.haloTint)
                    drawCircle(halo.copy(alpha = halo.alpha * 63f / 255f), look.haloRadius.dp.toPx())
                }
                Canvas(Modifier.size(paintedThumbWidth.dp, thumbHeight.dp).then(
                    if (look.material) Modifier.shadow(look.thumbElevation.dp, RoundedCornerShape(50), clip = false) else Modifier)) {
                    if (look.material) drawRoundRect(color(look.thumbTint), cornerRadius = CornerRadius(size.width / 2f))
                    else look.thumb?.let { drawable ->
                        drawable.state = states
                        look.thumbTint?.let(drawable::setTintList)
                        drawable.jumpToCurrentState()
                        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
                    }
                }
            }
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(look.trackHeight.dp)) {
                val start = if (direction == LayoutDirection.Ltr) 0f else size.width
                val end = size.width - start
                val selected = start + (end - start) * fraction
                if (look.material) {
                    val gap = trackGap.dp.toPx()
                    val outer = look.trackCorner.dp.toPx()
                    val inner = look.trackInsideCorner.dp.toPx()
                    fun segment(from: Float, to: Float, active: Boolean) {
                        if (to <= from) return
                        val leftRadius = if (active) outer else inner
                        val rightRadius = if (active) inner else outer
                        val left = if (direction == LayoutDirection.Ltr) from else size.width - to
                        val right = if (direction == LayoutDirection.Ltr) to else size.width - from
                        val l = CornerRadius(if (direction == LayoutDirection.Ltr) leftRadius else rightRadius)
                        val r = CornerRadius(if (direction == LayoutDirection.Ltr) rightRadius else leftRadius)
                        val path = Path().apply { addRoundRect(RoundRect(left, 0f, right, size.height, l, r, r, l)) }
                        drawPath(path, color(if (active) look.activeTint else look.inactiveTint))
                    }
                    val valueX = size.width * fraction
                    if (fraction > 0f) segment(-outer, valueX - gap, true)
                    if (fraction < 1f) segment(valueX + gap, size.width + outer, false)
                    val thumbExclusion = gap + paintedThumbWidth.dp.toPx() / 2f
                    if (look.ticksVisible && steps > 0) (0..steps + 1).forEach { index ->
                        val f = index.toFloat() / (steps + 1)
                        val x = start + (end - start) * f
                        if (kotlin.math.abs(x - selected) > thumbExclusion) {
                            val active = f <= fraction
                            drawCircle(color(if (active) look.activeTickTint else look.inactiveTickTint),
                                (if (active) look.activeTickRadius else look.inactiveTickRadius).dp.toPx(), Offset(x, center.y))
                        }
                    }
                    if (fraction < 1f && look.stopIndicatorSize > 0f && kotlin.math.abs(end - selected) > thumbExclusion)
                        drawCircle(color(look.inactiveTickTint), look.stopIndicatorSize.dp.toPx() / 2f, Offset(end, center.y))
                } else {
                    look.track?.let { drawable ->
                        drawable.state = states
                        (drawable as? LayerDrawable)?.findDrawableByLayerId(android.R.id.progress)?.level = (fraction * 10000).toInt()
                        if (drawable !is LayerDrawable) drawable.level = (fraction * 10000).toInt()
                        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        drawIntoCanvas { canvas ->
                            // Drawable levels grow left-to-right; mirror the source track in RTL.
                            val native = canvas.nativeCanvas
                            native.save()
                            if (direction == LayoutDirection.Rtl) { native.translate(size.width, 0f); native.scale(-1f, 1f) }
                            drawable.draw(native); native.restore()
                        }
                    }
                    if (steps > 0) look.tick?.let { tick ->
                        tick.state = states; look.tickTint?.let(tick::setTintList)
                        val halfWidth = tick.intrinsicWidth.coerceAtLeast(2) / 2
                        val halfHeight = tick.intrinsicHeight.coerceAtLeast(2) / 2
                        (0..steps + 1).forEach { index ->
                            val x = (start + (end - start) * index / (steps + 1)).toInt()
                            tick.setBounds(x - halfWidth, center.y.toInt() - halfHeight, x + halfWidth, center.y.toInt() + halfHeight)
                            drawIntoCanvas { tick.draw(it.nativeCanvas) }
                        }
                    }
                }
            }
        })
}

/** Slider cancels PressInteraction when a drag takes over. The handle remains visually active
 * until both interactions end; caller value/finish callbacks are unaffected. */
@Composable internal fun originalSliderActiveInteraction(interaction: MutableInteractionSource): Boolean {
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    return pressed || dragged
}

internal data class OriginalSliderLook(
    val material: Boolean, val thumb: Drawable?, val track: Drawable?, val tick: Drawable?,
    val thumbTint: ColorStateList?, val activeTint: ColorStateList?, val inactiveTint: ColorStateList?,
    val tickTint: ColorStateList?, val activeTickTint: ColorStateList?, val inactiveTickTint: ColorStateList?, val haloTint: ColorStateList?,
    val thumbWidth: Float, val thumbHeight: Float, val thumbRadius: Float, val haloRadius: Float,
    val trackHeight: Float, val thumbElevation: Float, val activeTickRadius: Float, val inactiveTickRadius: Float,
    val thumbTrackGap: Float, val trackCorner: Float, val trackInsideCorner: Float, val stopIndicatorSize: Float, val trackSidePadding: Float,
    val ticksVisible: Boolean, val minHeight: Float,
    val start: Float, val top: Float, val end: Float, val bottom: Float, val label: OriginalSliderLabelLook?,
)

internal fun resolveOriginalSlider(context: Context, spec: OriginalCompoundSpec): OriginalSliderLook {
    val material = spec.kind == OriginalCompoundKind.Slider
    val defaultAttr = if (material) MaterialR.attr.sliderStyle else androidx.appcompat.R.attr.seekBarStyle
    val defaultStyle = if (material) MaterialR.style.Widget_MaterialComponents_Slider else androidx.appcompat.R.style.Widget_AppCompat_SeekBar
    val overlayAttrs = intArrayOf(MaterialR.attr.materialThemeOverlay)
    val overlayStyle = context.obtainOrderedStyledAttributes(spec.style, overlayAttrs)
    val overlayBase = context.obtainOrderedStyledAttributes(null, overlayAttrs, defaultAttr, defaultStyle)
    val overlay = if (overlayStyle.hasValueOrEmpty(0)) overlayStyle.getResourceId(0, 0) else overlayBase.getResourceId(0, 0)
    overlayStyle.recycle(); overlayBase.recycle()
    val themed = if (overlay != 0) ContextThemeWrapper(context, overlay) else context
    val attrs = intArrayOf(android.R.attr.thumb, android.R.attr.progressDrawable, androidx.appcompat.R.attr.tickMark,
        android.R.attr.thumbTint, android.R.attr.progressTint, android.R.attr.progressBackgroundTint, androidx.appcompat.R.attr.tickMarkTint,
        MaterialR.attr.thumbColor, MaterialR.attr.trackColorActive, MaterialR.attr.trackColorInactive,
        MaterialR.attr.tickColorActive, MaterialR.attr.tickColorInactive, MaterialR.attr.haloColor,
        MaterialR.attr.thumbRadius, MaterialR.attr.haloRadius, MaterialR.attr.trackHeight, MaterialR.attr.tickVisible,
        android.R.attr.minHeight, android.R.attr.padding, android.R.attr.paddingStart, android.R.attr.paddingEnd,
        android.R.attr.paddingTop, android.R.attr.paddingBottom, MaterialR.attr.thumbElevation,
        MaterialR.attr.thumbWidth, MaterialR.attr.thumbHeight, MaterialR.attr.thumbTrackGapSize,
        MaterialR.attr.trackCornerSize, MaterialR.attr.trackInsideCornerSize, MaterialR.attr.trackStopIndicatorSize,
        MaterialR.attr.tickRadiusActive, MaterialR.attr.tickRadiusInactive, MaterialR.attr.labelStyle)
    val base = themed.obtainOrderedStyledAttributes(null, attrs, defaultAttr, defaultStyle)
    val explicit = themed.obtainOrderedStyledAttributes(spec.style, attrs)
    fun array(i: Int) = if (explicit.hasValueOrEmpty(i)) explicit else base
    fun drawable(i: Int): Drawable? { val id = array(i).getResourceId(i, 0); return (if (id != 0) AppCompatResources.getDrawable(themed, id) else array(i).getDrawable(i))?.mutate() }
    fun tint(i: Int): ColorStateList? {
        val values = array(i)
        val id = values.getResourceId(i, 0)
        return if (id != 0) AppCompatResources.getColorStateList(themed, id) else values.getColorStateList(i)
    }
    val density = context.resources.displayMetrics.density
    fun dim(i: Int, fallback: Float = 0f) = array(i).getDimension(i, fallback * density) / density
    val thumb = if (material) null else drawable(0)
    val track = if (material) null else drawable(1)
    val tick = if (material) null else drawable(2)
    if (track is LayerDrawable) {
        tint(4)?.let { track.findDrawableByLayerId(android.R.id.progress)?.setTintList(it) }
        tint(5)?.let { track.findDrawableByLayerId(android.R.id.background)?.setTintList(it) }
    }
    val padding = dim(18)
    val radius = dim(13)
    val thumbWidth = if (material) dim(24, radius * 2f) else (thumb?.intrinsicWidth ?: 0) / density
    val thumbHeight = if (material) dim(25, radius * 2f) else (thumb?.intrinsicHeight ?: 0) / density
    val trackHeight = if (material) dim(15) else (track?.intrinsicHeight ?: 0).coerceAtLeast(1) / density
    val stopSize = dim(29)
    val activeTickRadius = dim(30, stopSize / 2f)
    val inactiveTickRadius = dim(31, stopSize / 2f)
    val sidePadding = if (material) 16f + maxOf(0f, thumbWidth / 2f - 10f, (trackHeight - 4f) / 2f, activeTickRadius - 1f, inactiveTickRadius - 1f) else 0f
    val result = OriginalSliderLook(material, thumb, track, tick,
        tint(if (material) 7 else 3), tint(if (material) 8 else 4), tint(if (material) 9 else 5), tint(6), tint(10), tint(11), tint(12),
        thumbWidth, thumbHeight, radius, dim(14), trackHeight, dim(23), activeTickRadius, inactiveTickRadius,
        dim(26), dim(27, trackHeight / 2f), dim(28), stopSize, sidePadding,
        material && array(16).getBoolean(16, true), if (material) maxOf(48f, dim(17), trackHeight, thumbHeight) else dim(17),
        dim(19, padding), dim(21, padding), dim(20, padding), dim(22, padding),
        if (material) resolveOriginalSliderLabel(themed, array(32).getResourceId(32, MaterialR.style.Widget_MaterialComponents_Tooltip)) else null)
    explicit.recycle(); base.recycle(); return result
}

/** The Sponsor floating label uses MDC's labelStyle contract, without constructing a Tooltip View
 * or calling restricted TooltipDrawable APIs. It is a visual overlay; range semantics stay on Slider. */
@Composable fun OriginalSliderValueLabel(source: String, text: String, fraction: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowWidth = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    var windowX by remember { mutableFloatStateOf(0f) }
    val spec = originalCompoundSpecs.getValue(source)
    val look = remember(context, configuration, density, spec) { resolveOriginalSlider(context, spec) }
    val label = requireNotNull(look.label) { "Only source Material Slider has a floating label" }
    Canvas(modifier.onGloballyPositioned { windowX = it.positionInWindow().x }) {
        val labelWidth = maxOf(label.minWidth, label.padding * 2f + label.paint.measureText(text)).toInt()
        val labelHeight = maxOf(label.minHeight, label.paint.textSize).toInt()
        val f = if (direction == LayoutDirection.Ltr) fraction else 1f - fraction
        val trackPadding = look.trackSidePadding.dp.toPx()
        val centerX = trackPadding + (size.width - trackPadding * 2f) * f.coerceIn(0f, 1f)
        val minimumX = label.windowMargin - windowX
        val maximumX = (windowWidth - windowX - label.windowMargin - labelWidth).coerceAtLeast(minimumX)
        val x = (centerX - labelWidth / 2f).coerceIn(minimumX, maximumX)
        val bottom = center.y - look.thumbHeight.dp.toPx() / 2f - 4.dp.toPx()
        val top = bottom - labelHeight
        label.background.setBounds(x.toInt(), top.toInt(), x.toInt() + labelWidth, top.toInt() + labelHeight)
        drawIntoCanvas { canvas ->
            label.background.draw(canvas.nativeCanvas)
            val metrics = label.paint.fontMetrics
            val baseline = (top + labelHeight / 2f - (metrics.descent + metrics.ascent) / 2f).toInt().toFloat()
            canvas.nativeCanvas.drawText(text, x + labelWidth / 2f, baseline, label.paint)
        }
    }
}

internal data class OriginalSliderLabelLook(val background: com.google.android.material.shape.MaterialShapeDrawable,
    val paint: android.text.TextPaint, val padding: Float, val minWidth: Float, val minHeight: Float, val windowMargin: Float)

private fun resolveOriginalSliderLabel(context: Context, style: Int): OriginalSliderLabelLook {
    val a = context.obtainOrderedStyledAttributes(style, intArrayOf(android.R.attr.textAppearance, android.R.attr.textColor,
        MaterialR.attr.backgroundTint, android.R.attr.padding, android.R.attr.minWidth, android.R.attr.minHeight,
        android.R.attr.layout_margin))
    val appearance = context.obtainOrderedStyledAttributes(a.getResourceId(0, 0), intArrayOf(android.R.attr.textSize,
        android.R.attr.textColor, android.R.attr.fontFamily, android.R.attr.textStyle, android.R.attr.letterSpacing,
        androidx.appcompat.R.attr.fontFamily, android.R.attr.typeface))
    val fontStyle = appearance.getInt(3, android.graphics.Typeface.NORMAL)
    val fontIndex = if (appearance.hasValue(5)) 5 else 2
    val fontResource = appearance.getResourceId(fontIndex, 0)
    val fallback = when (appearance.getInt(6, 0)) {
        1 -> android.graphics.Typeface.SANS_SERIF
        2 -> android.graphics.Typeface.SERIF
        3 -> android.graphics.Typeface.MONOSPACE
        else -> android.graphics.Typeface.DEFAULT
    }
    val typeface = if (fontResource != 0) runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, fontResource) }.getOrNull()?.let { android.graphics.Typeface.create(it, fontStyle) }
        else appearance.getString(fontIndex)?.let { android.graphics.Typeface.create(it, fontStyle) }
    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = appearance.getDimension(0, 14f * context.resources.displayMetrics.scaledDensity)
        this.typeface = typeface ?: android.graphics.Typeface.create(fallback, fontStyle)
        letterSpacing = appearance.getFloat(4, 0f)
        color = (if (a.hasValue(1)) a.getColorStateList(1) else appearance.getColorStateList(1))?.defaultColor ?: android.graphics.Color.BLACK
    }
    val background = com.google.android.material.shape.MaterialShapeDrawable(context, null, 0, style).apply {
        fillColor = ColorStateList.valueOf(a.getColor(2, android.graphics.Color.TRANSPARENT))
    }
    // TooltipDrawable 1.14 reads only android:padding. Start/end/top/bottom values in its M3 style
    // do not override this scalar; keep that constructor behavior instead of guessing from XML.
    val result = OriginalSliderLabelLook(background, paint, a.getDimension(3, 0f), a.getDimension(4, 0f),
        a.getDimension(5, 0f), a.getDimension(6, 0f))
    appearance.recycle(); a.recycle(); return result
}
