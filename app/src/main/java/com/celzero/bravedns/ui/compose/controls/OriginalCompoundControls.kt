/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.controls

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.R as MaterialR

internal val OriginalCompoundSource = SemanticsPropertyKey<String>("OriginalCompoundSource")
internal var SemanticsPropertyReceiver.originalCompoundSource by OriginalCompoundSource

@Composable fun OriginalSwitch(source: String, checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) =
    OriginalCompound(source, if (checked) ToggleableState.On else ToggleableState.Off,
        onCheckedChange?.let { { it(!checked) } }, modifier, enabled, Role.Switch, onSwitchChange = onCheckedChange)

@Composable fun OriginalCheckbox(source: String, checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true, buttonTint: ColorStateList? = null,
    indeterminate: Boolean = false, indicatorOnly: Boolean = false) =
    OriginalCompound(source, if (indeterminate) ToggleableState.Indeterminate else if (checked) ToggleableState.On else ToggleableState.Off,
        onCheckedChange?.let { { it(!checked) } }, modifier, enabled, Role.Checkbox, buttonTint, indicatorOnly = indicatorOnly)

@Composable fun OriginalRadioButton(source: String, selected: Boolean, onClick: (() -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true, indicatorOnly: Boolean = false) =
    OriginalCompound(source, if (selected) ToggleableState.On else ToggleableState.Off,
        onClick, modifier, enabled, Role.RadioButton, indicatorOnly = indicatorOnly)

/**
 * Paint the resolved source widget drawables on a Compose canvas. No View is constructed.
 * A null callback is decorative inside its owning selectable/toggleable row, exactly like Compose
 * controls, so this renderer never invents a second accessibility action for those rows.
 * Source padding surrounds the visual drawable; the interactive minimum does not enlarge its ink.
 */
@Composable private fun OriginalCompound(source: String, state: ToggleableState, onClick: (() -> Unit)?,
    modifier: Modifier, enabled: Boolean, role: Role, buttonTint: ColorStateList? = null, onSwitchChange: ((Boolean) -> Unit)? = null, indicatorOnly: Boolean = false) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val spec = requireNotNull(originalCompoundSpecs[source]) { "Unmapped original compound control: $source" }
    val look = remember(context, configuration, density, spec) { resolveOriginalCompound(context, spec) }
    // Re-resolve on override removal as well as addition. Never recolor a remembered template:
    // clearing a controller tint must restore the resource's own intrinsic state-list tint.
    val drawing = remember(context, configuration, density, spec, buttonTint) { resolveOriginalCompound(context, spec) }
    val interaction = remember { MutableInteractionSource() }
    val currentState by rememberUpdatedState(state)
    val switchCallback by rememberUpdatedState(onSwitchChange)
    var dragging by remember(source) { mutableStateOf(false) }
    var dragFraction by remember(source) { mutableFloatStateOf(if (state == ToggleableState.On) 1f else 0f) }
    val settledFraction by animateFloatAsState(if (state == ToggleableState.On) 1f else 0f,
        animationSpec = tween(250), label = "source switch thumb")
    val position = if (dragging) dragFraction else settledFraction
    val flingThreshold = remember(context) { android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val states = originalCompoundStates(enabled, state, pressed || dragging, focused)
    val redraw = rememberOriginalCompoundDrawables(drawing)
    val action = if (onClick == null) Modifier else if (role == Role.RadioButton)
        Modifier.selectable(selected = state == ToggleableState.On, enabled = enabled, role = role, interactionSource = interaction, indication = null, onClick = onClick)
    else Modifier.triStateToggleable(state = state, interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    val pxDensity = density.density
    // Labeled CompoundButtons allocate padding/minimums to their whole row, not just the glyph.
    val visualLook = if (indicatorOnly) look.copy(minWidth = 0f, minHeight = 0f, start = 0f, top = 0f, end = 0f, bottom = 0f) else look
    val width = (if (spec.width > 0f) spec.width else maxOf(visualLook.minWidth, visualLook.width + visualLook.start + visualLook.end)).dp
    val height = (if (spec.height > 0f) spec.height else maxOf(visualLook.minHeight, visualLook.height + visualLook.top + visualLook.bottom)).dp
    Box(modifier.then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
        .then(action).semantics { originalCompoundSource = source }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width, height).then(if (role == Role.Switch && enabled && onSwitchChange != null)
            Modifier.pointerInput(source, enabled, direction, look, flingThreshold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val visualLeft = (size.width - look.width * pxDensity) / 2f
                    val visualTop = (size.height - look.height * pxDensity) / 2f
                    val checked = currentState == ToggleableState.On
                    val startPosition = if (checked) 1f else 0f
                    val thumbLeft = visualLeft + (look.width - look.thumbWidth) * pxDensity *
                        (if (direction == LayoutDirection.Ltr) startPosition else 1f - startPosition)
                    val slop = viewConfiguration.touchSlop
                    // SwitchCompat starts a drag only when the down hits its thumb, plus touch slop.
                    if (down.position.x !in (thumbLeft - slop)..(thumbLeft + look.thumbWidth * pxDensity + slop) ||
                        down.position.y !in (visualTop - slop)..(visualTop + look.height * pxDensity + slop)) return@awaitEachGesture
                    val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    val travel = ((look.width - look.thumbWidth) * pxDensity).coerceAtLeast(1f)
                    val directionSign = if (direction == LayoutDirection.Ltr) 1f else -1f
                    dragFraction = startPosition
                    val firstDrag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        dragFraction = (dragFraction + overSlop * directionSign / travel).coerceIn(0f, 1f)
                    } ?: return@awaitEachGesture
                    dragging = true
                    velocity.addPosition(firstDrag.uptimeMillis, firstDrag.position)
                    try {
                        val released = horizontalDrag(firstDrag.id) { change ->
                            velocity.addPosition(change.uptimeMillis, change.position)
                            dragFraction = (dragFraction + (change.position.x - change.previousPosition.x) * directionSign / travel).coerceIn(0f, 1f)
                            change.consume()
                        }
                        if (released) {
                            val next = originalSwitchDragResult(dragFraction, velocity.calculateVelocity().x, direction == LayoutDirection.Rtl, flingThreshold)
                            if (next != (currentState == ToggleableState.On)) switchCallback?.invoke(next)
                        }
                    } finally { dragging = false }
                }
            } else Modifier)) {
            // Read invalidations in the draw phase so native state-list/ripple transitions can run.
            redraw.revision.intValue
            val left = if (direction == LayoutDirection.Ltr) visualLook.start * pxDensity else visualLook.end * pxDensity
            val availableWidth = size.width - (visualLook.start + visualLook.end) * pxDensity
            val x = left + (availableWidth - look.width * pxDensity) / 2f
            val y = visualLook.top * pxDensity + (size.height - (visualLook.top + visualLook.bottom + look.height) * pxDensity) / 2f
            drawing.background?.let { background ->
                redraw.configure {
                    background.state = states
                    background.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    if (spec.kind == OriginalCompoundKind.MaterialSwitch) {
                        val thumbX = x + (look.width - look.thumbWidth) * pxDensity *
                            (if (direction == LayoutDirection.Ltr) position else 1f - position)
                        val thumbY = y + (look.height - look.thumbHeight) * pxDensity / 2f
                        background.setHotspotBounds(thumbX.toInt(), thumbY.toInt(),
                            (thumbX + look.thumbWidth * pxDensity).toInt(), (thumbY + look.thumbHeight * pxDensity).toInt())
                        background.setHotspot(thumbX + look.thumbWidth * pxDensity / 2f, center.y)
                    } else background.setHotspot(center.x, center.y)
                }
                drawIntoCanvas { background.draw(it.nativeCanvas) }
            }
            fun paint(drawable: Drawable?, tint: ColorStateList?, dx: Float, dy: Float, w: Float, h: Float) {
                if (drawable == null) return
                redraw.configure {
                    DrawableCompat.setLayoutDirection(drawable, if (direction == LayoutDirection.Rtl) 1 else 0)
                    drawable.state = states
                    // Source drawables keep their own tint when no explicit per-node/controller tint exists.
                    if (tint != null) drawable.setTintList(tint)
                    drawable.setBounds(dx.toInt(), dy.toInt(), (dx + w).toInt(), (dy + h).toInt())
                }
                drawIntoCanvas { drawable.draw(it.nativeCanvas) }
            }
            val w = look.width * pxDensity; val h = look.height * pxDensity
            fun switchTint(tint: ColorStateList?): ColorStateList? = tint?.let {
                ColorStateList.valueOf(originalSwitchTint(it, enabled, position, pressed || dragging, focused))
            }
            if (spec.kind == OriginalCompoundKind.MaterialSwitch) {
                paint(drawing.track, switchTint(drawing.trackTint), x, y, w, h)
                paint(drawing.trackDecoration, switchTint(drawing.trackDecorationTint), x, y, w, h)
                val thumbWidth = look.thumbWidth * pxDensity; val thumbHeight = look.thumbHeight * pxDensity
                val thumbX = x + (w - thumbWidth) * (if (direction == LayoutDirection.Ltr) position else 1f - position)
                paint(drawing.button, switchTint(drawing.buttonTint), thumbX, y + (h - thumbHeight) / 2f, thumbWidth, thumbHeight)
                paint(drawing.buttonIcon, switchTint(drawing.buttonIconTint), thumbX, y + (h - thumbHeight) / 2f, thumbWidth, thumbHeight)
            } else {
                paint(drawing.button, buttonTint ?: drawing.buttonTint, x, y, w, h)
                paint(drawing.buttonIcon, drawing.buttonIconTint, x, y, w, h)
            }
        }
    }
}

/** Release without a fling uses the midpoint; a fling follows physical direction, mirrored in RTL. */
internal fun originalSwitchDragResult(position: Float, velocityX: Float, rtl: Boolean, minimumFlingVelocity: Float): Boolean =
    if (kotlin.math.abs(velocityX) > minimumFlingVelocity) if (rtl) velocityX < 0f else velocityX > 0f else position > .5f

internal fun originalSwitchTint(tint: ColorStateList, enabled: Boolean, fraction: Float, pressed: Boolean = false, focused: Boolean = false): Int =
    ColorUtils.blendARGB(tint.getColorForState(originalCompoundStates(enabled, ToggleableState.Off, pressed, focused), tint.defaultColor),
        tint.getColorForState(originalCompoundStates(enabled, ToggleableState.On, pressed, focused), tint.defaultColor), fraction.coerceIn(0f, 1f))

internal fun originalCompoundStates(enabled: Boolean, state: ToggleableState, pressed: Boolean = false, focused: Boolean = false) =
    intArrayOf(if (enabled) android.R.attr.state_enabled else -android.R.attr.state_enabled,
        if (state == ToggleableState.On) android.R.attr.state_checked else -android.R.attr.state_checked,
        if (state == ToggleableState.Indeterminate) MaterialR.attr.state_indeterminate else -MaterialR.attr.state_indeterminate,
        if (pressed) android.R.attr.state_pressed else -android.R.attr.state_pressed,
        if (focused) android.R.attr.state_focused else -android.R.attr.state_focused)

internal data class OriginalCompoundLook(
    val button: Drawable?, val buttonIcon: Drawable?, val track: Drawable?, val trackDecoration: Drawable?, val background: Drawable?,
    val buttonTint: ColorStateList?, val buttonIconTint: ColorStateList?, val trackTint: ColorStateList?, val trackDecorationTint: ColorStateList?,
    val width: Float, val height: Float, val thumbWidth: Float, val thumbHeight: Float,
    val minWidth: Float, val minHeight: Float, val start: Float, val top: Float, val end: Float, val bottom: Float,
)

/** Styles are resolved in the widget overlay, rather than substituting the global Compose palette. */
internal fun resolveOriginalCompound(context: Context, spec: OriginalCompoundSpec): OriginalCompoundLook {
    val switch = spec.kind == OriginalCompoundKind.MaterialSwitch
    val radio = spec.kind in listOf(OriginalCompoundKind.MaterialRadioButton, OriginalCompoundKind.AppCompatRadioButton)
    val defaultAttr = if (switch) MaterialR.attr.materialSwitchStyle else if (radio) androidx.appcompat.R.attr.radioButtonStyle else androidx.appcompat.R.attr.checkboxStyle
    val defaultStyle = when (spec.kind) {
        OriginalCompoundKind.MaterialSwitch -> MaterialR.style.Widget_Material3_CompoundButton_MaterialSwitch
        OriginalCompoundKind.MaterialCheckBox -> MaterialR.style.Widget_MaterialComponents_CompoundButton_CheckBox
        OriginalCompoundKind.MaterialRadioButton -> MaterialR.style.Widget_MaterialComponents_CompoundButton_RadioButton
        OriginalCompoundKind.AppCompatRadioButton -> androidx.appcompat.R.style.Widget_AppCompat_CompoundButton_RadioButton
        else -> androidx.appcompat.R.style.Widget_AppCompat_CompoundButton_CheckBox
    }
    // MaterialSwitch inherits these core switch attributes from AppCompat SwitchCompat.
    // Keep their owning namespace when android.nonTransitiveRClass is enabled.
    val attrs = intArrayOf(android.R.attr.button, androidx.appcompat.R.attr.buttonCompat, android.R.attr.buttonTint,
        androidx.appcompat.R.attr.buttonTint, MaterialR.attr.buttonIcon, MaterialR.attr.buttonIconTint,
        android.R.attr.thumb, androidx.appcompat.R.attr.thumbTint, androidx.appcompat.R.attr.track, androidx.appcompat.R.attr.trackTint,
        MaterialR.attr.thumbIcon, MaterialR.attr.thumbIconTint, MaterialR.attr.trackDecoration, MaterialR.attr.trackDecorationTint,
        android.R.attr.minWidth, android.R.attr.minHeight, android.R.attr.padding, android.R.attr.paddingStart,
        android.R.attr.paddingEnd, android.R.attr.paddingTop, android.R.attr.paddingBottom, android.R.attr.drawableBottom,
        androidx.appcompat.R.attr.switchMinWidth, android.R.attr.paddingHorizontal, android.R.attr.paddingVertical,
        MaterialR.attr.materialThemeOverlay, android.R.attr.theme, android.R.attr.background, MaterialR.attr.rippleColor)
    fun overlay(attr: Int): Int {
        val a = context.obtainOrderedStyledAttributes(spec.style, intArrayOf(attr))
        val b = context.obtainOrderedStyledAttributes(null, intArrayOf(attr), defaultAttr, defaultStyle)
        val result = if (a.hasValueOrEmpty(0)) a.getResourceId(0, 0) else b.getResourceId(0, 0)
        a.recycle(); b.recycle(); return result
    }
    val material = spec.kind in listOf(OriginalCompoundKind.MaterialSwitch, OriginalCompoundKind.MaterialCheckBox, OriginalCompoundKind.MaterialRadioButton)
    val materialOverlay = if (material) overlay(MaterialR.attr.materialThemeOverlay) else 0
    val themed = if (materialOverlay != 0) ContextThemeWrapper(context, materialOverlay) else context
    val androidTheme = context.obtainOrderedStyledAttributes(spec.style, intArrayOf(android.R.attr.theme)).let { try { it.getResourceId(0, 0) } finally { it.recycle() } }
    val wrapped = if (androidTheme != 0) ContextThemeWrapper(themed, androidTheme) else themed
    val base = wrapped.obtainOrderedStyledAttributes(null, attrs, defaultAttr, defaultStyle)
    val explicit = wrapped.obtainOrderedStyledAttributes(spec.style, attrs)
    fun array(i: Int) = if (explicit.hasValueOrEmpty(i)) explicit else base
    fun has(i: Int) = array(i).hasValue(i)
    fun tint(i: Int): ColorStateList? {
        val values = array(i)
        val id = values.getResourceId(i, 0)
        return if (id != 0) AppCompatResources.getColorStateList(wrapped, id) else values.getColorStateList(i)
    }
    fun drawable(i: Int): Drawable? {
        val resource = array(i).getResourceId(i, 0)
        return (if (resource != 0) AppCompatResources.getDrawable(wrapped, resource) else array(i).getDrawable(i))?.mutate()
    }
    val density = context.resources.displayMetrics.density
    fun dim(i: Int, fallback: Float = 0f) = array(i).getDimension(i, fallback * density) / density
    val compatButtonId = array(1).getResourceId(1, 0)
    var button = if (switch) drawable(6) else if (spec.buttonCleared) drawable(21) else drawable(if (compatButtonId != 0) 1 else 0)
    var icon = if (switch) drawable(10) else if (spec.kind == OriginalCompoundKind.MaterialCheckBox) drawable(4) else null
    // MDC replaces only the framework legacy checkbox under a Material3 theme. Custom buttons
    // and AppCompatCheckBox remain intact; bulk select keeps its independent accentGood/Bad icon.
    val material3 = android.util.TypedValue().let { wrapped.theme.resolveAttribute(MaterialR.attr.isMaterial3Theme, it, true) && it.data != 0 }
    val legacyButton = android.content.res.Resources.getSystem().getIdentifier("btn_check_material_anim", "drawable", "android")
    if (spec.kind == OriginalCompoundKind.MaterialCheckBox && !spec.buttonCleared && button != null && material3 &&
        array(0).getResourceId(0, 0) == legacyButton && compatButtonId == 0) {
        button = AppCompatResources.getDrawable(wrapped, MaterialR.drawable.mtrl_checkbox_button)?.mutate()
        if (icon == null) icon = AppCompatResources.getDrawable(wrapped, MaterialR.drawable.mtrl_checkbox_button_icon)?.mutate()
    }
    val track = if (switch) drawable(8) else null
    val decoration = if (switch) drawable(12) else null
    val width = if (switch) (track?.intrinsicWidth?.takeIf { it >= 0 }?.div(density) ?: dim(22)) else (button?.intrinsicWidth ?: 0) / density
    val height = if (switch) maxOf(track?.intrinsicHeight ?: 0, button?.intrinsicHeight ?: 0) / density else (button?.intrinsicHeight ?: 0) / density
    val background = drawable(27)
    if (spec.kind in listOf(OriginalCompoundKind.MaterialCheckBox, OriginalCompoundKind.MaterialRadioButton) && has(28)) {
        val ripple = if (background is DrawableWrapper) background.drawable else background
        tint(28)?.let { (ripple as? RippleDrawable)?.setColor(it) }
    }
    val padding = dim(16); val horizontal = dim(23, padding); val vertical = dim(24, padding)
    val result = OriginalCompoundLook(button, icon, track, decoration, background,
        // AppCompatCompoundButtonHelper applies app:buttonTint after framework inflation;
        // MaterialRadioButton applies it again. Theme-provided app tint also wins over android tint.
        if (switch) tint(7) else if (spec.buttonCleared) null else tint(if (has(3)) 3 else 2), if (switch) tint(11) else if (spec.kind == OriginalCompoundKind.MaterialCheckBox) tint(5) else null,
        if (switch) tint(9) else null, if (switch) tint(13) else null,
        width, height, (button?.intrinsicWidth ?: 0) / density, (button?.intrinsicHeight ?: 0) / density,
        dim(14), dim(15), dim(17, horizontal), dim(19, vertical), dim(18, horizontal), dim(20, vertical))
    explicit.recycle(); base.recycle()
    return result
}

/** Drawable scheduling is a rendering concern only. Each remembered source gets isolated drawable
 * instances; detaching cancels their own frame callbacks without constructing any Android View. */
private class OriginalDrawableInvalidation {
    val revision = mutableIntStateOf(0)
    var applyingProperties = false
    fun configure(block: () -> Unit) {
        applyingProperties = true
        try { block() } finally { applyingProperties = false }
    }
}

@Composable private fun rememberOriginalCompoundDrawables(look: OriginalCompoundLook): OriginalDrawableInvalidation {
    val redraw = remember { OriginalDrawableInvalidation() }
    DisposableEffect(look) {
        val handler = Handler(Looper.getMainLooper())
        val drawables = listOfNotNull(look.button, look.buttonIcon, look.track, look.trackDecoration, look.background)
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { if (!redraw.applyingProperties) redraw.revision.intValue++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, who, `when`) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what, who) }
        }
        drawables.forEach { it.callback = callback }
        onDispose { drawables.forEach { it.callback = null; handler.removeCallbacksAndMessages(it) } }
    }
    return redraw
}
