/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.celzero.bravedns.ui.compose.fields

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.resolveUtilityStyle
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.google.android.material.R as MaterialR
import com.google.android.material.shape.ShapeAppearanceModel

/** Each entry maps to a baseline XML/programmatic family; see original-field-style-map.json.
 * A null radius means inherit the selected Android theme, never a Compose shape token.
 */
enum class OriginalFieldStyle(val radiusDp: Float? = null, val textSizeSp: Float? = null) {
    WgEditor(8f), WgSsid, WgInlineSsid(4f),
    Dns, DnsProxy, Rule(8f), Ping(16f, 15f), Support(12f, 14f), RpnSsid(12f, 15f),
    Lan(4f), Notes, Network, Tasker,
    Search, AppLogSearch(textSizeSp = 14f), ServerSearch(textSizeSp = 14f), TransparentSearch(textSizeSp = 14f);

    val materialBox get() = this in setOf(WgEditor, WgSsid, Dns, Rule, Ping, Support, RpnSsid)
    val outlined get() = this in setOf(WgEditor, WgSsid, Rule, Ping, Support)
    val search get() = this in setOf(Search, AppLogSearch, ServerSearch)
}

val OriginalFieldFamily = SemanticsPropertyKey<String>("OriginalFieldFamily")
var SemanticsPropertyReceiver.originalFieldFamily by OriginalFieldFamily

internal fun fieldStateSet(enabled: Boolean, focused: Boolean) = intArrayOf(
    if (enabled) android.R.attr.state_enabled else -android.R.attr.state_enabled,
    if (focused) android.R.attr.state_focused else -android.R.attr.state_focused)

internal fun fieldCursorColor(normal: Color, error: Color, isError: Boolean): Color = if (isError) error else normal

internal fun ColorStateList.fieldColor(enabled: Boolean, focused: Boolean): Color =
    Color(getColorForState(fieldStateSet(enabled, focused), defaultColor))

internal data class FieldResources(
    val mode: Int, val background: Drawable?, val padding: Rect, val minHeight: Float,
    val text: ColorStateList?, val hint: ColorStateList?, val stroke: ColorStateList?,
    val fill: ColorStateList?, val cursor: ColorStateList?, val highlight: Color, val shape: RoundedCornerShape,
    val strokeWidth: Float, val focusedStrokeWidth: Float, val search: SearchFieldResources? = null
)

/** abc_search_view separates the leading magnifier from the tinted query plate. */
internal data class SearchFieldResources(
    val editorPadding: Rect, val includeFontPadding: Boolean, val icon: Drawable?,
    val iconSlotWidth: Float, val iconStartMargin: Float, val clearWidth: Float
)

// SearchView.updateFocusedState forwards focused/empty state, not the editor's enabled state.
internal fun searchPlateStateSet(focused: Boolean) =
    if (focused) intArrayOf(android.R.attr.state_focused) else intArrayOf()

/** SearchAutoComplete uses autoCompleteTextViewStyle, with an explicitly null editor background.
 * Read the widget's TextAppearance as well as direct attributes. Do not inherit editTextStyle's
 * minimum height or backdrop. All of this is resource lookup; production creates no Views.
 */
private fun resolveSearchFieldResources(context: Context, style: OriginalFieldStyle): FieldResources {
    val density = context.resources.displayMetrics.density
    val editor = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.textAppearance,
        android.R.attr.textColor, android.R.attr.textColorHint, android.R.attr.textColorHighlight,
        android.R.attr.padding, android.R.attr.paddingTop, android.R.attr.paddingBottom,
        android.R.attr.includeFontPadding), androidx.appcompat.R.attr.autoCompleteTextViewStyle, 0)
    val appearance = context.obtainOrderedStyledAttributes(editor.getResourceId(0, 0), intArrayOf(
        android.R.attr.textColor, android.R.attr.textColorHint, android.R.attr.textColorHighlight))
    val search = context.obtainOrderedStyledAttributes(null, intArrayOf(androidx.appcompat.R.attr.queryBackground,
        androidx.appcompat.R.attr.searchIcon, androidx.appcompat.R.attr.closeIcon),
        androidx.appcompat.R.attr.searchViewStyle, 0)
    // Match TintTypedArray.getDrawable: resource-backed values go through AppCompat's tint manager.
    fun drawable(index: Int): Drawable? {
        val id = search.getResourceId(index, 0)
        return (if (id != 0) AppCompatResources.getDrawable(context, id) else search.getDrawable(index))?.mutate()
    }
    try {
        val background = drawable(0)
        val platePadding = Rect().also { background?.getPadding(it) }
        val allPadding = editor.getDimensionPixelSize(4, 0)
        val editorPadding = if (style == OriginalFieldStyle.ServerSearch) Rect(0, 0, 0, 0) else Rect(
            context.resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_dropdownitem_text_padding_left),
            editor.getDimensionPixelSize(5, allPadding),
            context.resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_dropdownitem_text_padding_right),
            editor.getDimensionPixelSize(6, allPadding))
        val icon = if (style == OriginalFieldStyle.AppLogSearch) null else drawable(1)
        val close = if (style == OriginalFieldStyle.ServerSearch) AppCompatResources.getDrawable(context, R.drawable.ic_cross) else drawable(2)
        val cursor = context.obtainOrderedStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorControlActivated)).let {
            try { it.getColorStateList(0) } finally { it.recycle() }
        }
        return FieldResources(0, background, platePadding,
            if (style == OriginalFieldStyle.ServerSearch) 40f else
                context.resources.getDimension(androidx.appcompat.R.dimen.abc_search_view_preferred_height) / density,
            editor.getColorStateList(1) ?: appearance.getColorStateList(0),
            editor.getColorStateList(2) ?: appearance.getColorStateList(1), null, null, cursor,
            Color(if (editor.hasValue(3)) editor.getColor(3, 0) else appearance.getColor(2, 0)),
            RoundedCornerShape(0.dp), 0f, 0f,
            SearchFieldResources(editorPadding, style != OriginalFieldStyle.ServerSearch && editor.getBoolean(7, true),
                icon, context.resources.getDimension(androidx.appcompat.R.dimen.abc_dropdownitem_icon_width) / density,
                context.resources.getDimension(androidx.appcompat.R.dimen.abc_dropdownitem_text_padding_left) / density,
                (close?.intrinsicWidth?.takeIf { it > 0 }?.div(density) ?: 24f) + 16f))
    } finally { search.recycle(); appearance.recycle(); editor.recycle() }
}

/** Resource lookup only. No Views, layout inflation, or theme-independent M3 substitutions. */
// These TypedArray indexes address explicitly ordered attribute arrays, not R.styleable IDs.
internal fun resolveFieldResources(context: Context, style: OriginalFieldStyle): FieldResources {
    if (style.search) return resolveSearchFieldResources(context, style)
    val density = context.resources.displayMetrics.density
    val widgetStyle = if (style.outlined) MaterialR.style.Widget_Material3_TextInputLayout_OutlinedBox
        else resolveUtilityStyle(context, MaterialR.attr.textInputStyle)
    val box = context.obtainOrderedStyledAttributes(widgetStyle, intArrayOf(MaterialR.attr.boxBackgroundMode,
        MaterialR.attr.boxStrokeColor, MaterialR.attr.boxBackgroundColor, MaterialR.attr.hintTextColor,
        MaterialR.attr.shapeAppearance, MaterialR.attr.shapeAppearanceOverlay,
        MaterialR.attr.boxStrokeWidth, MaterialR.attr.boxStrokeWidthFocused))
    val editStyle = if (style == OriginalFieldStyle.Notes) android.R.attr.editTextStyle else androidx.appcompat.R.attr.editTextStyle
    val edit = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.background,
        android.R.attr.textColor, android.R.attr.textColorHint, android.R.attr.minHeight,
        android.R.attr.paddingLeft, android.R.attr.paddingTop, android.R.attr.paddingRight, android.R.attr.paddingBottom),
        editStyle, 0)
    val mode = if (style.materialBox) box.getInt(0, if (style.outlined) 2 else 1) else 0
    val stroke = box.getColorStateList(1)
    val fill = box.getColorStateList(2)
    val hint = if (style.materialBox) box.getColorStateList(3) ?: edit.getColorStateList(2) else edit.getColorStateList(2)
    val shapeModel = ShapeAppearanceModel.builder(context, box.getResourceId(4, 0), box.getResourceId(5, 0)).build()
    val bounds = RectF(0f, 0f, 1000f * density, 56f * density)
    fun corner(size: com.google.android.material.shape.CornerSize) = (style.radiusDp ?: (size.getCornerSize(bounds) / density)).dp
    val shape = RoundedCornerShape(corner(shapeModel.topLeftCornerSize), corner(shapeModel.topRightCornerSize),
        corner(shapeModel.bottomRightCornerSize), corner(shapeModel.bottomLeftCornerSize))
    val background = when {
        style == OriginalFieldStyle.Lan || style == OriginalFieldStyle.WgInlineSsid -> AppCompatResources.getDrawable(context, R.drawable.edittext_default)?.mutate()
        style == OriginalFieldStyle.TransparentSearch || style.materialBox -> null
        else -> edit.getDrawable(0)?.mutate()
    }
    val padding = Rect().also { background?.getPadding(it) }
    if (style == OriginalFieldStyle.WgInlineSsid) padding.set((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
    else if (style == OriginalFieldStyle.Tasker) padding.set(50, 40, 50, 40) // Programmatic baseline uses px.
    else if (!style.materialBox && !style.search && style != OriginalFieldStyle.Lan) {
        if (edit.hasValue(4)) padding.left = edit.getDimensionPixelSize(4, padding.left)
        if (edit.hasValue(5)) padding.top = edit.getDimensionPixelSize(5, padding.top)
        if (edit.hasValue(6)) padding.right = edit.getDimensionPixelSize(6, padding.right)
        if (edit.hasValue(7)) padding.bottom = edit.getDimensionPixelSize(7, padding.bottom)
    }
    val cursor = context.obtainOrderedStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorControlActivated)).let {
        try { it.getColorStateList(0) } finally { it.recycle() }
    }
    val highlight = context.obtainOrderedStyledAttributes(intArrayOf(android.R.attr.textColorHighlight)).let {
        try { Color(it.getColor(0, android.graphics.Color.TRANSPARENT)) } finally { it.recycle() }
    }
    return FieldResources(mode, background, padding, edit.getDimension(3, 0f) / density,
        edit.getColorStateList(1), hint, stroke, fill, cursor, highlight, shape,
        box.getDimension(6, density) / density, box.getDimension(7, 2 * density) / density).also { edit.recycle(); box.recycle() }
}

/** abc_search_view.xml defaults to actionSearch and textNoSuggestions. Fill only unset search
 * options: callers still own explicit IME actions, autocorrection, keyboard type and callbacks.
 * OriginalSearchInputMethod separately retains the native textAutoComplete/noSuggestions bits.
 */
internal fun originalFieldKeyboardOptions(style: OriginalFieldStyle, options: KeyboardOptions): KeyboardOptions {
    if (!style.search) return options
    return options.copy(
        imeAction = if (options.imeAction == ImeAction.Unspecified) ImeAction.Search else options.imeAction,
        autoCorrectEnabled = options.autoCorrectEnabled ?: false,
        // KeyboardOptions.copy does not default these two arguments to the receiver's values.
        showKeyboardOnFocus = options.showKeyboardOnFocus,
        hintLocales = options.hintLocales)
}

@Composable
fun OriginalTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    style: OriginalFieldStyle, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default, label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false, errorMessage: String? = null, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    expandedHint: Boolean = true
) {
    OriginalField(value, modifier, style, enabled, textStyle, label, placeholder, leadingIcon, trailingIcon, supportingText,
        isError, errorMessage, visualTransformation, singleLine, expandedHint) { fieldModifier, resolvedText, cursor, interaction, decoration ->
        OriginalSearchInputMethod(style, keyboardOptions) {
            BasicTextField(value, onValueChange, fieldModifier, enabled, readOnly, resolvedText,
                originalFieldKeyboardOptions(style, keyboardOptions), keyboardActions, singleLine, maxLines, minLines, visualTransformation,
                interactionSource = interaction, cursorBrush = SolidColor(cursor), decorationBox = decoration)
        }
    }
}

/** Keep selection/composition intact for the notes editor; no String round trip. */
@Composable
fun OriginalTextField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    style: OriginalFieldStyle, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default, placeholder: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1
) {
    OriginalField(value.text, modifier, style, enabled, textStyle, null, placeholder, null, null, null,
        false, null, VisualTransformation.None, singleLine, true) { fieldModifier, resolvedText, cursor, interaction, decoration ->
        OriginalSearchInputMethod(style, keyboardOptions) {
            BasicTextField(value, onValueChange, fieldModifier, enabled, readOnly, resolvedText,
                originalFieldKeyboardOptions(style, keyboardOptions), keyboardActions, singleLine, maxLines, minLines, VisualTransformation.None,
                interactionSource = interaction, cursorBrush = SolidColor(cursor), decorationBox = decoration)
        }
    }
}

@Composable
private fun OriginalField(value: String, modifier: Modifier, style: OriginalFieldStyle, enabled: Boolean,
    textStyle: TextStyle, label: (@Composable () -> Unit)?, placeholder: (@Composable () -> Unit)?,
    leading: (@Composable () -> Unit)?, trailing: (@Composable () -> Unit)?, supporting: (@Composable () -> Unit)?,
    isError: Boolean, errorMessage: String?, visualTransformation: VisualTransformation, singleLine: Boolean, expandedHint: Boolean,
    editor: @Composable (Modifier, TextStyle, Color, MutableInteractionSource, @Composable (@Composable () -> Unit) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val resources = remember(context, config, style) { resolveFieldResources(context, style) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val primary = themeColor(R.attr.primaryTextColor)
    val accent = themeColor(R.attr.accentGood)
    val hint = if (style in setOf(OriginalFieldStyle.Ping, OriginalFieldStyle.WgInlineSsid, OriginalFieldStyle.TransparentSearch))
        themeColor(R.attr.primaryLightColorText) else resources.hint?.fieldColor(enabled, focused) ?: themeColor(R.attr.primaryLightColorText)
    val foreground = if (style in setOf(OriginalFieldStyle.Dns, OriginalFieldStyle.Rule, OriginalFieldStyle.Ping,
        OriginalFieldStyle.Support, OriginalFieldStyle.WgInlineSsid, OriginalFieldStyle.TransparentSearch)) primary
        else resources.text?.fieldColor(enabled, focused) ?: primary
    val errorColor = themeColor(androidx.appcompat.R.attr.colorError)
    val normalCursor = resources.cursor?.fieldColor(true, true) ?: accent
    val cursor = fieldCursorColor(normalCursor, errorColor, isError)
    val appearance = if (style == OriginalFieldStyle.Dns || style == OriginalFieldStyle.DnsProxy)
        resolveUtilityStyle(context, MaterialR.attr.textAppearanceBodyLarge) else 0
    val textSize = style.textSizeSp ?: if (style == OriginalFieldStyle.Rule) utilityDimensionSp(context, R.dimen.large_font_text_view)
        else null
    val baseText = if (style.search) originalSearchTextStyle(style) else utilityTextStyle(
        appearance = appearance, size = textSize,
        defaultStyleAttribute = if (style == OriginalFieldStyle.Notes) android.R.attr.editTextStyle else androidx.appcompat.R.attr.editTextStyle)
    val resolvedText = baseText.merge(textStyle).copy(
        color = if (textStyle.color != Color.Unspecified) textStyle.color else foreground,
        platformStyle = PlatformTextStyle(includeFontPadding = resources.search?.includeFontPadding ?: true))
    val invalidInputMessage = errorMessage ?: stringResource(R.string.compose_field_invalid_input)
    // Keep source search dimensions separate from generic text-input minimum heights.
    val fieldModifier = modifier
        .then(if (resources.mode == 2 && label != null) Modifier.padding(top = 8.dp) else Modifier)
        .heightIn(min = (if (style.search) resources.minHeight else maxOf(if (style.materialBox) 56f else 48f, resources.minHeight)).dp)
        .semantics {
            originalFieldFamily = style.name
            if (isError) error(invalidInputMessage)
        }
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(normalCursor, resources.highlight)) {
    editor(fieldModifier, resolvedText, cursor, interaction) { inner ->
        if (style.materialBox) {
            val focusedLabel = if (style == OriginalFieldStyle.Dns || style == OriginalFieldStyle.Rule) accent else resources.hint?.fieldColor(true, true) ?: cursor
            val stroke = resources.stroke
            val focusedStroke = stroke?.fieldColor(true, true) ?: cursor
            val unfocusedStroke = stroke?.fieldColor(true, false) ?: themeColor(R.attr.colorOutline)
            val disabledStroke = stroke?.fieldColor(false, false) ?: unfocusedStroke.copy(alpha = .38f)
            val error = errorColor
            val fill = resources.fill
            val transparent = Color.Transparent
            val surface = themeColor(MaterialR.attr.colorSurface)
            fun fillColor(enabled: Boolean, focused: Boolean): Color =
                (fill?.fieldColor(enabled, focused) ?: transparent).let { if (resources.mode == 1) it.compositeOver(surface) else it }
            val colors = if (resources.mode == 2) OutlinedTextFieldDefaults.colors(
                focusedTextColor = foreground, unfocusedTextColor = foreground, disabledTextColor = foreground,
                focusedContainerColor = transparent, unfocusedContainerColor = transparent, disabledContainerColor = transparent, errorContainerColor = transparent,
                focusedBorderColor = focusedStroke, unfocusedBorderColor = unfocusedStroke, disabledBorderColor = disabledStroke, errorBorderColor = error,
                focusedLabelColor = focusedLabel, unfocusedLabelColor = hint, disabledLabelColor = hint, errorLabelColor = error,
                focusedPlaceholderColor = hint, unfocusedPlaceholderColor = hint, disabledPlaceholderColor = hint,
                cursorColor = cursor, errorCursorColor = error)
            else TextFieldDefaults.colors(
                focusedTextColor = foreground, unfocusedTextColor = foreground, disabledTextColor = foreground,
                focusedContainerColor = fillColor(true, true),
                unfocusedContainerColor = fillColor(true, false),
                disabledContainerColor = fillColor(false, false),
                errorContainerColor = fillColor(true, false),
                focusedIndicatorColor = focusedStroke, unfocusedIndicatorColor = unfocusedStroke, disabledIndicatorColor = disabledStroke, errorIndicatorColor = error,
                focusedLabelColor = focusedLabel, unfocusedLabelColor = hint, disabledLabelColor = hint, errorLabelColor = error,
                focusedPlaceholderColor = hint, unfocusedPlaceholderColor = hint, disabledPlaceholderColor = hint,
                cursorColor = cursor, errorCursorColor = error)
            val decorationValue = if (!expandedHint && value.isEmpty()) "\u200b" else value
            val decoratedInner: @Composable () -> Unit = {
                Box { if (!expandedHint && value.isEmpty()) CompositionLocalProvider(LocalContentColor provides hint) { placeholder?.invoke() }; inner() }
            }
            ProvideTextStyle(resolvedText) {
                if (resources.mode == 2) OutlinedTextFieldDefaults.DecorationBox(
                    value = decorationValue, innerTextField = decoratedInner, enabled = enabled, singleLine = singleLine,
                    visualTransformation = visualTransformation, interactionSource = interaction, isError = isError,
                    label = label, placeholder = if (expandedHint) placeholder else null, leadingIcon = leading, trailingIcon = trailing,
                    supportingText = supporting, colors = colors,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    container = { OutlinedTextFieldDefaults.Container(enabled, isError, interaction, colors = colors, shape = resources.shape,
                        focusedBorderThickness = resources.focusedStrokeWidth.dp, unfocusedBorderThickness = resources.strokeWidth.dp) })
                else TextFieldDefaults.DecorationBox(
                    value = decorationValue, innerTextField = decoratedInner, enabled = enabled, singleLine = singleLine,
                    visualTransformation = visualTransformation, interactionSource = interaction, isError = isError,
                    label = label, placeholder = if (expandedHint) placeholder else null, leadingIcon = leading, trailingIcon = trailing,
                    supportingText = supporting, colors = colors,
                    contentPadding = if (style == OriginalFieldStyle.RpnSsid) PaddingValues(16.dp) else TextFieldDefaults.contentPaddingWithLabel(),
                    container = { TextFieldDefaults.Container(enabled, isError, interaction, colors = colors, shape = resources.shape,
                        focusedIndicatorLineThickness = resources.focusedStrokeWidth.dp, unfocusedIndicatorLineThickness = resources.strokeWidth.dp) })
            }
        } else if (style.search) {
            OriginalSearchDecoration(resources, value, focused, resolvedText, hint, leading, trailing, placeholder ?: label, inner)
        } else {
            val padding = resources.padding
            Row(Modifier.fillMaxWidth().drawBehind {
                resources.background?.let { drawable ->
                    drawable.state = fieldStateSet(enabled, focused)
                    drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    drawIntoCanvas { drawable.draw(it.nativeCanvas) }
                }
            }.padding(start = with(density) { padding.left.toDp() }, top = with(density) { padding.top.toDp() },
                end = with(density) { padding.right.toDp() }, bottom = with(density) { padding.bottom.toDp() }),
                verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) leading()
                Box(Modifier.weight(1f).heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) CompositionLocalProvider(LocalContentColor provides hint) { ProvideTextStyle(resolvedText.copy(color = hint)) { (placeholder ?: label)?.invoke() } }
                    inner()
                }
                trailing?.invoke()
            }
        }
    }
    }
}

@Composable
internal fun originalSearchTextStyle(style: OriginalFieldStyle): TextStyle = utilityTextStyle(
    size = style.textSizeSp, defaultStyleAttribute = androidx.appcompat.R.attr.autoCompleteTextViewStyle)

/** Compose equivalent of the uniconified abc_search_view edit frame and query plate. */
@Composable
private fun OriginalSearchDecoration(resources: FieldResources, value: String, focused: Boolean,
    text: TextStyle, hint: Color, leading: (@Composable () -> Unit)?, trailing: (@Composable () -> Unit)?,
    placeholder: (@Composable () -> Unit)?, inner: @Composable () -> Unit) {
    val search = checkNotNull(resources.search)
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun Rect.asPadding() = with(density) { PaddingValues(
        start = (if (rtl) right else left).toDp(), top = top.toDp(),
        end = (if (rtl) left else right).toDp(), bottom = bottom.toDp()) }
    // Native clear/search slots have their own dimensions; M3's 48dp minimum changes the layout.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = resources.minHeight.dp)
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null || search.icon != null) {
                Box(Modifier.padding(start = search.iconStartMargin.dp).width(search.iconSlotWidth.dp)
                    .fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (leading != null) leading() else search.icon?.let { icon ->
                        Box(Modifier.size(with(density) { icon.intrinsicWidth.toDp() }, with(density) { icon.intrinsicHeight.toDp() })
                            .drawBehind {
                                icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                                drawIntoCanvas { icon.draw(it.nativeCanvas) }
                            })
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxHeight().drawBehind {
                resources.background?.let { drawable ->
                    drawable.state = searchPlateStateSet(focused)
                    drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    drawIntoCanvas { drawable.draw(it.nativeCanvas) }
                }
            }.padding(resources.padding.asPadding()), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 36.dp).padding(search.editorPadding.asPadding()),
                    contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) CompositionLocalProvider(LocalContentColor provides hint) {
                        ProvideTextStyle(text.copy(color = hint)) { placeholder?.invoke() }
                    }
                    inner()
                }
                if (value.isNotEmpty() && trailing != null) Box(Modifier.width(search.clearWidth.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center) { trailing() }
            }
        }
    }
}

/** The baseline DNS/proxy app chooser is a Spinner, not an outlined/text action button.
 * The caller still owns its dialog/menu and selection callbacks.
 */
@Composable
// These TypedArray indexes address explicitly ordered attribute arrays, not R.styleable IDs.
fun OriginalSpinner(value: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = remember(context, configuration) {
        val attrs = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.background),
            androidx.appcompat.R.attr.spinnerStyle, 0)
        try { attrs.getDrawable(0)?.mutate() } finally { attrs.recycle() }
    }
    val padding = remember(context, configuration, background) {
        Rect().also { padding ->
            background?.getPadding(padding)
            // Both original adapters use android.R.layout.simple_spinner_dropdown_item.
            val item = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.paddingLeft, android.R.attr.paddingTop,
                android.R.attr.paddingRight, android.R.attr.paddingBottom), android.R.attr.spinnerDropDownItemStyle, 0)
            try {
                padding.left += item.getDimensionPixelSize(0, 0); padding.top += item.getDimensionPixelSize(1, 0)
                padding.right += item.getDimensionPixelSize(2, 0); padding.bottom += item.getDimensionPixelSize(3, 0)
            } finally { item.recycle() }
        }
    }
    val colors = remember(context, configuration) {
        val attrs = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.textColor), android.R.attr.spinnerDropDownItemStyle, 0)
        try { attrs.getColorStateList(0) } finally { attrs.recycle() }
    }
    val color = colors?.fieldColor(enabled, focused) ?: themeColor(R.attr.primaryTextColor)
    Box(modifier.heightIn(min = 48.dp)
        .semantics { originalFieldFamily = "Spinner" }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
        .drawBehind {
            background?.let { drawable ->
                drawable.state = fieldStateSet(enabled, focused) + if (pressed) android.R.attr.state_pressed else -android.R.attr.state_pressed
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawIntoCanvas { drawable.draw(it.nativeCanvas) }
            }
        }
        .padding(start = with(density) { padding.left.toDp() }, top = with(density) { padding.top.toDp() },
            end = with(density) { padding.right.toDp() }, bottom = with(density) { padding.bottom.toDp() }),
        contentAlignment = Alignment.CenterStart) {
        Text(value, color = color, style = utilityTextStyle(defaultStyleAttribute = android.R.attr.spinnerDropDownItemStyle))
    }
}
