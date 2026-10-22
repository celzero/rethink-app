/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.controls

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import com.celzero.bravedns.ui.compose.utilities.utilityResolvedWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.*
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.R as MaterialR
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Presentation-only renderer for the original XML styles. It reads theme/style attributes, not
 * layouts, and never creates a View. Filled, outlined, text/dialog, thin action/filter chips and
 * both segment styles retain their own source resources and per-element overrides.
 */
@Composable
fun OriginalButton(
    source: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean? = null, contentColor: Color? = null, containerColor: Color? = null,
    backgroundTint: ColorStateList? = null, segmentIndex: Int? = null, segmentCount: Int = 1,
    leadingIcon: (@Composable () -> Unit)? = null, iconTint: Color? = null, grouped: Boolean = false,
    content: @Composable RowScope.() -> Unit
) = OriginalControl(source, onClick, modifier, enabled, selected, contentColor, containerColor,
    backgroundTint, leadingIcon = leadingIcon, segmentIndex = segmentIndex, segmentCount = segmentCount, iconTintOverride = iconTint, grouped = grouped, content = content)

@Composable
fun OriginalChip(
    source: String, selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, selectable: Boolean = true,
    contentColor: Color? = null, containerColor: Color? = null, backgroundTint: ColorStateList? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    segmentIndex: Int? = null, segmentCount: Int = 1
) = OriginalControl(source, onClick, modifier, enabled, if (selectable) selected else null,
    contentColor, containerColor, backgroundTint, leadingIcon, trailingIcon, segmentIndex = segmentIndex, segmentCount = segmentCount) { label() }

/** Passive badges retain their visual style without adding button semantics or a touch target. */
@Composable
fun OriginalLabel(source: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean = false, contentColor: Color? = null, containerColor: Color? = null,
    backgroundTint: ColorStateList? = null, content: @Composable RowScope.() -> Unit) =
    OriginalControl(source, {}, modifier, enabled, selected, contentColor, containerColor, backgroundTint,
        interactive = false, content = content)

/** Source text (including annotated controller text) inherits the original typeface and size. */
@Composable
private fun OriginalControl(
    source: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, selected: Boolean?,
    contentColor: Color?, containerColor: Color?, backgroundTint: ColorStateList?,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    interactive: Boolean = true, segmentIndex: Int? = null, segmentCount: Int = 1,
    iconTintOverride: Color? = null, grouped: Boolean = false, content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    val spec = requireNotNull(originalControlSpecs[source]) { "Missing original control style: $source" }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val look = remember(context, spec, density, configuration) { resolveOriginalControl(context, spec, density) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val states = intArrayOf(if (enabled) android.R.attr.state_enabled else -android.R.attr.state_enabled,
        if (selected == true) android.R.attr.state_checked else -android.R.attr.state_checked,
        if (selected == true) android.R.attr.state_selected else -android.R.attr.state_selected,
        if (pressed) android.R.attr.state_pressed else -android.R.attr.state_pressed,
        if (focused) android.R.attr.state_focused else -android.R.attr.state_focused,
        if (hovered) android.R.attr.state_hovered else -android.R.attr.state_hovered)
    val foreground = contentColor ?: look.textColor.resolve(states)
    val fill = (containerColor ?: (backgroundTint ?: look.fill).resolve(states)).compositeOver(look.surface.resolve(states))
    // Keep the unmodified resource as a template. A fresh copy on tint changes restores intrinsic
    // source tint when a controller clears an override, instead of retaining the previous tint.
    val drawableTint = backgroundTint ?: look.drawableTint
    val background = remember(look.background, drawableTint) {
        look.background?.let { original ->
            (original.constantState?.newDrawable(context.resources)?.mutate() ?: original.mutate()).also {
                if (drawableTint != null) it.setTintList(drawableTint)
            }
        }
    }
    val stroke = look.stroke.resolve(states)
    val shape = segmentIndex?.let { OriginalSegmentShape(look.shape, it, segmentCount) } ?: look.shape
    val clickModifier = if (!interactive) Modifier else if (selected == null) Modifier.clickable(enabled = enabled, role = Role.Button,
        interactionSource = interaction, indication = null, onClick = onClick)
    else Modifier.selectable(selected, enabled = enabled, role = if (spec.kind == OriginalControlKind.Chip) Role.Checkbox else Role.RadioButton, interactionSource = interaction,
        indication = null, onClick = onClick)
    // Keep the compact visual silhouette inside an accessible touch target, not a 48dp filled pill.
    // Joined native segments cannot each insert a 48dp layout spacer: that separates the
    // source 32dp visuals. Compose selectable still uses the platform minimum touch hit slop.
    // Standalone controls keep an explicit 48dp target without passing that minimum to paint.
    BoxWithConstraints(modifier.then(if (interactive && !grouped && segmentCount <= 1)
        Modifier.minimumInteractiveComponentSize() else Modifier).then(clickModifier), contentAlignment = Alignment.Center) {
        val callerMinWidth = with(density) { constraints.minWidth.toDp() }
        val visual = Modifier.wrapContentHeight()
            .then(if (spec.iconOnly && spec.visualHeight > 0f) Modifier.height(spec.visualHeight.dp)
                else Modifier.heightIn(min = look.height.dp))
            .then(if (spec.visualWidth > 0f) Modifier.width(spec.visualWidth.dp)
                else Modifier.widthIn(min = maxOf(look.minWidth.dp, callerMinWidth)))
            .padding(start = look.insetStart.dp, end = look.insetEnd.dp, top = look.insetTop.dp, bottom = look.insetBottom.dp)
            .shadow(look.elevation.dp, shape, clip = false)
            .then(if (background != null) Modifier.drawOriginalBackground(background, states)
                else Modifier.clip(shape).background(fill, shape).then(if (look.strokeWidth > 0f) Modifier.border(BorderStroke(look.strokeWidth.dp, stroke), shape) else Modifier))
            .then(if (background == null) Modifier.clip(shape) else Modifier)
            .then(if (interactive && look.ripple != null) Modifier.indication(interaction, ripple(color = look.ripple.resolve(states))) else Modifier)
            // MaterialButton recenters an icon-only compound drawable even when inherited
            // text padding exceeds a small fixed width. Do not collapse its icon to zero.
            .then(if (spec.iconOnly) Modifier else Modifier.padding(start = look.paddingStart.dp,
                end = look.paddingEnd.dp, top = look.paddingTop.dp, bottom = look.paddingBottom.dp))
        CompositionLocalProvider(LocalContentColor provides foreground) {
            ProvideTextStyle(look.textStyle.copy(color = foreground)) {
                Row(visual, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    val checkedIcon = look.checkedIcon.takeIf { selected == true && look.checkedIconVisible }
                    val iconResource = checkedIcon ?: look.icon
                    val resolvedIconTint = iconTintOverride ?: (if (checkedIcon != null) look.checkedIconTint else look.iconTint)?.resolve(states) ?: foreground
                    val iconPainter = if (leadingIcon == null && iconResource != 0) painterResource(iconResource) else null
                    if (leadingIcon != null || iconPainter != null) {
                        // MDC iconSize=0 means the drawable's intrinsic size, not a zero-sized icon.
                        // Keep independent width/height for non-square drawables. Caller-supplied
                        // icons retain their own dimensions when the source has no explicit size.
                        val iconModifier = when {
                            look.iconSize > 0f -> Modifier.size(look.iconSize.dp)
                            iconPainter != null -> Modifier.size(
                                originalIconExtent(iconPainter.intrinsicSize.width, density),
                                originalIconExtent(iconPainter.intrinsicSize.height, density))
                            else -> Modifier
                        }
                        Box(iconModifier, contentAlignment = Alignment.Center) {
                            if (leadingIcon != null) leadingIcon()
                            else if (iconPainter != null) Icon(iconPainter, null, Modifier.fillMaxSize(), tint = resolvedIconTint)
                        }
                        if (!spec.iconOnly) Spacer(Modifier.width(look.iconPadding.dp))
                    }
                    content()
                    if (trailingIcon != null) { Spacer(Modifier.width(look.iconPadding.dp)); trailingIcon() }
                }
            }
        }
    }
}

/** Intrinsic painter dimensions are physical pixels; explicit style dimensions are already dp. */
internal fun originalIconExtent(intrinsicPixels: Float, density: Density): Dp =
    if (intrinsicPixels.isFinite() && intrinsicPixels > 0f) with(density) { intrinsicPixels.toDp() } else 24.dp

private fun ColorStateList?.resolve(states: IntArray): Color =
    this?.let { Color(it.getColorForState(states, it.defaultColor)) } ?: Color.Transparent

/** Drawable resources are painted directly on the Compose canvas; no AndroidView/View inflation. */
private fun Modifier.drawOriginalBackground(drawable: Drawable, states: IntArray) = drawBehind {
    drawable.state = states
    drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
    drawIntoCanvas { drawable.draw(it.nativeCanvas) }
}

internal data class OriginalControlLook(
    val fill: ColorStateList?, val surface: ColorStateList?, val textColor: ColorStateList?, val stroke: ColorStateList?, val ripple: ColorStateList?,
    val strokeWidth: Float, val shape: Shape, val background: Drawable?, val drawableTint: ColorStateList?, val textStyle: TextStyle,
    val height: Float, val minWidth: Float, val paddingStart: Float, val paddingEnd: Float,
    val paddingTop: Float, val paddingBottom: Float, val insetStart: Float, val insetEnd: Float,
    val insetTop: Float, val insetBottom: Float, val icon: Int, val iconSize: Float, val iconPadding: Float,
    val iconTint: ColorStateList?, val checkedIcon: Int, val checkedIconVisible: Boolean,
    val checkedIconTint: ColorStateList?, val elevation: Float
)

/** MDC resolves materialThemeOverlay, then materialSizeOverlay, before reading colors/shapes.
 * In MDC 1.14 a TextButton background selector reads colorContainer; its overlay makes that
 * transparent. Reading the selector in the Activity theme incorrectly paints a filled button.
 * This is style-only theme resolution, with no View or layout construction.
 */
internal fun originalControlContext(context: Context, spec: OriginalControlSpec, defaultAttr: Int,
    defaultStyle: Int): Context {
    val attrs = intArrayOf(MaterialR.attr.materialThemeOverlay, MaterialR.attr.materialSizeOverlay,
        android.R.attr.theme, androidx.appcompat.R.attr.theme)
    val defaults = context.obtainOrderedStyledAttributes(null, attrs, defaultAttr, defaultStyle)
    val source = context.obtainOrderedStyledAttributes(spec.style, attrs)
    fun overlay(index: Int): Int = if (attrs[index] in spec.explicitNulls) 0 else
        (if (source.hasValueOrEmpty(index)) source else defaults).getResourceId(index, 0)
    val materialOverlay = overlay(0)
    val sizeOverlay = if (spec.kind == OriginalControlKind.Button) overlay(1) else 0
    // Android/app theme never falls back to the component's default style (MDC contract).
    val androidOverlay = source.getResourceId(2, 0)
    val appOverlay = source.getResourceId(3, 0)
    source.recycle(); defaults.recycle()
    if (materialOverlay == 0 || context is ContextThemeWrapper && context.themeResId == materialOverlay)
        return context
    return ContextThemeWrapper(context, materialOverlay).also { wrapped ->
        if (sizeOverlay != 0) wrapped.theme.applyStyle(sizeOverlay, true)
        val clientOverlay = if (androidOverlay != 0) androidOverlay else appOverlay
        if (clientOverlay != 0) wrapped.theme.applyStyle(clientOverlay, true)
    }
}

internal fun resolveOriginalControl(sourceContext: Context, spec: OriginalControlSpec, density: Density): OriginalControlLook {
    val defaultAttr = when (spec.kind) {
        OriginalControlKind.Chip -> MaterialR.attr.chipStyle
        OriginalControlKind.Button -> MaterialR.attr.materialButtonStyle
        OriginalControlKind.Text -> android.R.attr.textViewStyle
    }
    val defaultStyle = when (spec.kind) {
        OriginalControlKind.Chip -> MaterialR.style.Widget_MaterialComponents_Chip_Action
        OriginalControlKind.Button -> MaterialR.style.Widget_MaterialComponents_Button
        OriginalControlKind.Text -> 0
    }
    val context = originalControlContext(sourceContext, spec, defaultAttr, defaultStyle)
    val attrs = intArrayOf(
        android.R.attr.textAppearance, android.R.attr.textSize, android.R.attr.textColor, android.R.attr.fontFamily,
        android.R.attr.textStyle, android.R.attr.letterSpacing, android.R.attr.minWidth, android.R.attr.minHeight,
        android.R.attr.padding, android.R.attr.paddingStart, android.R.attr.paddingEnd, android.R.attr.paddingTop,
        android.R.attr.paddingBottom, android.R.attr.insetLeft, android.R.attr.insetRight, android.R.attr.insetTop,
        android.R.attr.insetBottom, android.R.attr.background, android.R.attr.backgroundTint,
        MaterialR.attr.backgroundTint, MaterialR.attr.cornerRadius, MaterialR.attr.strokeWidth,
        MaterialR.attr.strokeColor, MaterialR.attr.rippleColor, MaterialR.attr.shapeAppearance,
        MaterialR.attr.shapeAppearanceOverlay, MaterialR.attr.chipCornerRadius, MaterialR.attr.chipMinHeight,
        MaterialR.attr.chipBackgroundColor, MaterialR.attr.chipSurfaceColor, MaterialR.attr.chipStrokeWidth,
        MaterialR.attr.chipStrokeColor, MaterialR.attr.chipStartPadding, MaterialR.attr.chipEndPadding,
        MaterialR.attr.textStartPadding, MaterialR.attr.textEndPadding, MaterialR.attr.chipIconSize,
        MaterialR.attr.icon, MaterialR.attr.iconSize, MaterialR.attr.iconPadding, MaterialR.attr.iconTint,
        MaterialR.attr.chipIcon, MaterialR.attr.chipIconVisible, MaterialR.attr.chipIconTint,
        android.R.attr.paddingHorizontal, android.R.attr.paddingVertical,
        MaterialR.attr.checkedIcon, MaterialR.attr.checkedIconVisible, MaterialR.attr.checkedIconTint,
        android.R.attr.elevation, MaterialR.attr.elevation, MaterialR.attr.iconStartPadding, MaterialR.attr.iconEndPadding, android.R.attr.paddingLeft, android.R.attr.paddingRight
    )
    val base = context.obtainOrderedStyledAttributes(null, attrs, defaultAttr, defaultStyle)
    val explicit = context.obtainOrderedStyledAttributes(spec.style, attrs)
    fun explicitlyCleared(index: Int) = attrs[index] in spec.explicitNulls
    fun array(index: Int) = if (explicit.hasValueOrEmpty(index) || explicitlyCleared(index)) explicit else base
    fun has(index: Int) = explicitlyCleared(index) || explicit.hasValueOrEmpty(index) || base.hasValueOrEmpty(index)
    fun dim(index: Int, fallback: Float = 0f) = array(index).getDimension(index, fallback * context.resources.displayMetrics.density) / context.resources.displayMetrics.density
    fun res(index: Int, fallback: Int = 0) = array(index).getResourceId(index, fallback)
    fun colors(index: Int): ColorStateList? {
        if (explicitlyCleared(index)) return null
        val values = array(index)
        val resource = values.getResourceId(index, 0)
        // Match MaterialResources: resolve theme-sensitive selectors in the component overlay.
        return if (resource != 0) AppCompatResources.getColorStateList(context, resource)
            else values.getColorStateList(index)
    }
    val appearance = context.obtainOrderedStyledAttributes(res(0), intArrayOf(android.R.attr.textSize, android.R.attr.textColor,
        android.R.attr.fontFamily, android.R.attr.textStyle, android.R.attr.letterSpacing))
    val fontStyle = if (has(4)) array(4).getInt(4, 0) else appearance.getInt(3, 0)
    val fontArray = if (has(3)) array(3) else appearance
    val fontIndex = if (has(3)) 3 else 2
    val fontResource = fontArray.getResourceId(fontIndex, 0)
    val typeface = if (fontResource != 0) runCatching { ResourcesCompat.getFont(context, fontResource) }.getOrNull()
        else fontArray.getString(fontIndex)?.let { Typeface.create(it, fontStyle) }
    val sizeArray = if (has(1)) array(1) else appearance
    val sizeIndex = if (has(1)) 1 else 0
    val sizeValue = sizeArray.peekValue(sizeIndex)
    val textSize = if (sizeValue?.type == android.util.TypedValue.TYPE_DIMENSION &&
        sizeValue.complexUnit == android.util.TypedValue.COMPLEX_UNIT_SP)
        android.util.TypedValue.complexToFloat(sizeValue.data)
        else with(density) { sizeArray.getDimension(sizeIndex, 14.sp.toPx()).toSp().value }
    val resolvedTypeface = typeface ?: Typeface.create("sans-serif", fontStyle)
    val weight = utilityResolvedWeight(if (android.os.Build.VERSION.SDK_INT >= 28) resolvedTypeface.weight else 0,
        -1, fontArray.getString(fontIndex).orEmpty(), fontStyle)
    val textStyle = TextStyle(fontSize = textSize.sp, fontFamily = FontFamily(resolvedTypeface),
        fontWeight = FontWeight(weight), fontStyle = if (fontStyle and Typeface.ITALIC != 0) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = (if (has(5)) array(5).getFloat(5, 0f) else appearance.getFloat(4, 0f)).em,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = true))
    val chip = spec.kind == OriginalControlKind.Chip
    val model = ShapeAppearanceModel.builder(context, res(24), res(25)).build()
    // MDC 1.14 ChipDrawable.loadFromAttributes applies chipCornerRadius after shapeAppearance;
    // setChipCornerRadius uses shapeAppearanceModel.withCornerSize(radius). Preserve both inputs,
    // including the explicit 1dp overlay, but inherit the same final radius precedence as MDC.
    val radius = if (chip && has(26) && !explicitlyCleared(26)) dim(26)
        else if (!chip && has(20) && !explicitlyCleared(20)) dim(20) else null
    val shape: Shape = radius?.let { androidx.compose.foundation.shape.RoundedCornerShape(it.dp) } ?: OriginalMaterialShape(model)
    val padding = dim(8)
    val horizontal = dim(44, padding)
    val vertical = dim(45, padding)
    val sourceBackground = if (explicit.hasValue(17) && !explicitlyCleared(17)) {
        val resource = explicit.getResourceId(17, 0)
        (if (resource != 0) AppCompatResources.getDrawable(context, resource) else explicit.getDrawable(17))?.mutate()
    } else null
    // With a real android:background MDC marks the managed background overwritten. Its
    // shape/stroke/ripple are no longer rendered; the drawable owns these states instead.
    val overwrittenButton = spec.kind == OriginalControlKind.Button && sourceBackground != null
    val result = OriginalControlLook(
        fill = if (spec.kind == OriginalControlKind.Text) null else colors(if (chip) 28 else 19),
        surface = if (chip) colors(29) else null,
        textColor = colors(2) ?: appearance.getColorStateList(1),
        stroke = if (overwrittenButton) null else colors(if (chip) 31 else 22),
        ripple = if (overwrittenButton) null else colors(23),
        strokeWidth = if (overwrittenButton) 0f else dim(if (chip) 30 else 21),
        shape = shape, background = sourceBackground,
        // MaterialButtonHelper uses Material backgroundTint even with an overwritten drawable.
        // Framework backgroundTint belongs to plain TextView backgrounds, not managed buttons.
        drawableTint = if (spec.kind == OriginalControlKind.Button) colors(19) else colors(18),
        textStyle = textStyle, height = if (spec.visualHeight > 0f) spec.visualHeight else dim(if (chip) 27 else 7), minWidth = if (chip) 0f else dim(6),
        paddingStart = if (chip) dim(32) + dim(34) else dim(9, dim(53, horizontal)),
        paddingEnd = if (chip) dim(33) + dim(35) else dim(10, dim(54, horizontal)),
        paddingTop = if (chip) 0f else dim(11, vertical), paddingBottom = if (chip) 0f else dim(12, vertical),
        insetStart = if (chip) 0f else dim(13), insetEnd = if (chip) 0f else dim(14),
        insetTop = if (chip) 0f else dim(15), insetBottom = if (chip) 0f else dim(16),
        icon = if (chip && array(42).getBoolean(42, false)) res(41) else if (!chip) res(37) else 0,
        iconSize = if (chip) dim(36, 18f) else dim(38), iconPadding = if (chip) dim(34, 8f) else dim(39, 8f),
        iconTint = colors(if (chip) 43 else 40),
        checkedIcon = if (chip) res(46) else 0, checkedIconVisible = chip && array(47).getBoolean(47, false),
        checkedIconTint = if (chip) colors(48) else null, elevation = dim(if (has(49)) 49 else 50)
    )
    appearance.recycle(); explicit.recycle(); base.recycle()
    return result
}

private class OriginalMaterialShape(private val model: ShapeAppearanceModel) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rect = RectF(0f, 0f, size.width, size.height)
        fun corner(value: com.google.android.material.shape.CornerSize) = CornerRadius(value.getCornerSize(rect))
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height,
            corner(model.topLeftCornerSize), corner(model.topRightCornerSize),
            corner(model.bottomRightCornerSize), corner(model.bottomLeftCornerSize)))
    }
}

/** MaterialButtonToggleGroup squares adjoining corners while keeping the outside end caps. */
internal class OriginalSegmentShape(private val base: Shape, private val index: Int, private val count: Int) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val outline = base.createOutline(size, layoutDirection, density)
        if (outline !is Outline.Rounded || count <= 1) return outline
        val first = if (layoutDirection == LayoutDirection.Ltr) index == 0 else index == count - 1
        val last = if (layoutDirection == LayoutDirection.Ltr) index == count - 1 else index == 0
        val r = outline.roundRect
        return Outline.Rounded(RoundRect(r.left, r.top, r.right, r.bottom,
            if (first) r.topLeftCornerRadius else CornerRadius.Zero,
            if (last) r.topRightCornerRadius else CornerRadius.Zero,
            if (last) r.bottomRightCornerRadius else CornerRadius.Zero,
            if (first) r.bottomLeftCornerRadius else CornerRadius.Zero))
    }
}
