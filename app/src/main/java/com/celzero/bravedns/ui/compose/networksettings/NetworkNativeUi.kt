/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksettings

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import androidx.compose.ui.platform.LocalLocale
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalSpinner
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import com.celzero.bravedns.ui.compose.fields.originalFieldFamily
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.MotionEvent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.settings.SettingsCard
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.wireguard.*

/** SeekBar's changed-value/programmatic/user callback ordering, without a View or persistence. */
class NetworkSeekState {
    var max = 100
    private var value by mutableIntStateOf(0)
    var progress: Int
        get() = value
        set(next) { update(next, false) }
    var isEnabled by mutableStateOf(true)
    private var tracking = false
    private var listener: OnSeekBarChangeListener? = null
    interface OnSeekBarChangeListener {
        fun onProgressChanged(seekBar: NetworkSeekState?, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(seekBar: NetworkSeekState?)
        fun onStopTrackingTouch(seekBar: NetworkSeekState?)
    }
    private fun update(next: Int, fromUser: Boolean) {
        val clamped = next.coerceIn(0, max)
        if (value == clamped) return
        value = clamped
        listener?.onProgressChanged(this, clamped, fromUser)
    }
    fun setOnSeekBarChangeListener(next: OnSeekBarChangeListener) { listener = next }
    fun startGesture() {
        if (!tracking) { tracking = true; listener?.onStartTrackingTouch(this) }
    }
    fun userProgress(next: Int) { update(next, true) }
    fun finishGesture() {
        if (!tracking) return
        tracking = false
        listener?.onStopTrackingTouch(this)
    }
}

/** The app-name selector retains index/default selection and the lockdown touch interception. */
class NetworkSpinnerState {
    var items: List<String> by mutableStateOf(emptyList())
    private var selectedIndex by mutableIntStateOf(0)
    var alpha by mutableFloatStateOf(1f)
    private var touch: ((NetworkSpinnerState, MotionEvent) -> Boolean)? = null
    val selectedItem: String? get() = items.getOrNull(selectedIndex)
    fun setSelection(index: Int) { selectedIndex = index }
    fun setOnTouchListener(listener: (NetworkSpinnerState, MotionEvent) -> Boolean) { touch = listener }
    fun dispatchTouch(event: MotionEvent): Boolean = touch?.invoke(this, event) ?: false
    fun intercepted(): Boolean {
        val listener = touch ?: return false
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        return try { listener(this, event) } finally { event.recycle() }
    }
}

data class NetworkRefreshState(val generation: Int, val durationMs: Int, val interpolator: android.view.animation.Interpolator)

enum class NetworkKind { SCROLL, COLUMN, ROW, OVERLAY, CARD, TEXT, ICON, SWITCH, CHECK, RADIO, BUTTON, FIELD, SLIDER, SPINNER, DIVIDER, PROGRESS }
data class NetworkInsets(val start: Float = 0f, val top: Float = 0f, val end: Float = 0f, val bottom: Float = 0f)
data class NetworkNode(
    val kind: NetworkKind,
    val key: String? = null,
    val control: WgControlState? = null,
    val slider: NetworkSeekState? = null,
    val spinner: NetworkSpinnerState? = null,
    val children: List<NetworkNode> = emptyList(),
    val appearance: Int = 0,
    val textSize: Float? = null,
    val textSizeRes: Int? = null,
    val leadingIcon: Int? = null,
    val iconGap: Float = 0f,
    val alignEnd: Boolean = false,
    val centerText: Boolean = false,
    val backgroundResource: Int? = null,
    val hideSemantics: Boolean = false,
    val textWeight: Int? = null,
    val fontFamily: String? = null,
    val letterSpacing: Float? = null,
    val margin: NetworkInsets = NetworkInsets(),
    val padding: NetworkInsets = NetworkInsets(),
    val width: Float? = null,
    val height: Float? = null,
    val fillWidth: Boolean = false,
    val weight: Float = 0f,
    val trailing: Boolean = false,
    val flexible: Boolean = false,
    val numeric: Boolean = false,
    val selectable: Boolean = false,
    val description: CharSequence? = null,
    val editableBackground: Boolean = false,
    val cornerRadius: Float? = null,
)

fun networkAnnotatedText(value: CharSequence): AnnotatedString {
    val base = wgAnnotatedText(value)
    if (value !is android.text.Spanned) return base
    return AnnotatedString.Builder(base).apply {
        value.getSpans(0, value.length, Any::class.java).forEach { span ->
            val start = value.getSpanStart(span); val end = value.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            when (span) {
                is android.text.style.URLSpan -> addLink(LinkAnnotation.Url(span.url), start, end)
                is android.text.style.UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is android.text.style.StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            }
        }
    }.toAnnotatedString()
}

@Composable
fun NetworkSettingsScreen(layout: NetworkNode, refreshing: Map<WgControlState, NetworkRefreshState> = emptyMap()) {
    Surface(color = themeColor(R.attr.background)) {
        NetworkContent(layout, Modifier.fillMaxSize().safeDrawingPadding(), refreshing)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NetworkContent(node: NetworkNode, modifier: Modifier = Modifier, refreshing: Map<WgControlState, NetworkRefreshState> = emptyMap(),
                   extraChildren: Map<WgControlState, List<NetworkNode>> = emptyMap(), scrollContainer: Boolean = true) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val resolvedTextSize = node.textSize ?: node.textSizeRes?.let { with(density) { context.resources.getDimension(it).toSp().value } }
    val state = node.control
    if (state?.visibility == View.GONE) return
    var m = modifier.then(if (node.key != null) Modifier.testTag(node.key) else Modifier).padding(node.margin.start.dp, node.margin.top.dp, node.margin.end.dp, node.margin.bottom.dp)
    if (node.width != null && node.width > 0f) m = m.width(node.width.dp)
    else if (node.fillWidth) m = m.fillMaxWidth()
    if (node.height != null && node.height > 0f) m = m.height(node.height.dp)
    if (node.kind !in listOf(NetworkKind.SWITCH, NetworkKind.CHECK)) m = m.alpha(if (state?.visibility == View.INVISIBLE) 0f else state?.alpha ?: 1f)
    if (state?.visibility == View.INVISIBLE || node.hideSemantics) m = m.clearAndSetSemantics { }
    val clickable = state?.hasClickListener == true
    if (clickable && node.kind !in listOf(NetworkKind.BUTTON, NetworkKind.SWITCH, NetworkKind.CHECK, NetworkKind.RADIO, NetworkKind.ICON)) {
        m = m.clickable(enabled = state!!.isEnabled && state.isClickable) { state.performClick() }
    }
    node.backgroundResource?.let { resource ->
        val drawable = remember(context, resource) { androidx.appcompat.content.res.AppCompatResources.getDrawable(context, resource) }
        m = m.drawBehind { drawable?.let { it.setBounds(0, 0, size.width.toInt(), size.height.toInt()); drawIntoCanvas { canvas -> it.draw(canvas.nativeCanvas) } } }
    }
    if (state?.backgroundColor != null) {
        val color = state.backgroundColor!!
        val radius = with(density) { 16f.toDp() }
        val stroke = with(density) { 1f.toDp() }
        val shape = RoundedCornerShape(radius)
        m = m.background(Color(color and 0x00FFFFFF or 0x1A000000), shape).border(stroke, Color(color), shape)
    }
    if (node.kind !in listOf(NetworkKind.SWITCH, NetworkKind.CHECK, NetworkKind.RADIO, NetworkKind.SLIDER))
        m = m.padding(node.padding.start.dp, node.padding.top.dp, node.padding.end.dp, node.padding.bottom.dp)
    val children = node.children + (state?.let { extraChildren[it] } ?: emptyList())
    when (node.kind) {
        NetworkKind.SCROLL -> Column(if (scrollContainer) m.verticalScroll(remember { ScrollState(0) }) else m) { children.forEach { NetworkContent(it, if (it.alignEnd) Modifier.align(Alignment.End) else Modifier, refreshing = refreshing, extraChildren = extraChildren) } }
        NetworkKind.COLUMN -> Column(m) { children.forEach { NetworkContent(it, if (it.alignEnd) Modifier.align(Alignment.End) else Modifier, refreshing = refreshing, extraChildren = extraChildren) } }
        NetworkKind.CARD -> SettingsCard(m, contentPadding = 0.dp, cornerRadius = node.cornerRadius?.dp) {
            children.forEach { NetworkContent(it, if (it.alignEnd) Modifier.align(Alignment.End) else Modifier, refreshing = refreshing, extraChildren = extraChildren) }
        }
        NetworkKind.OVERLAY -> Box(m, contentAlignment = Alignment.CenterEnd) { children.forEach { NetworkContent(it, refreshing = refreshing, extraChildren = extraChildren) } }
        NetworkKind.ROW -> Row(m.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (node.alignEnd) Arrangement.End else Arrangement.Start) {
            val grow = children.firstOrNull { it.flexible || it.weight > 0f }
                ?: children.firstOrNull { it.kind in listOf(NetworkKind.COLUMN, NetworkKind.TEXT, NetworkKind.FIELD) }
            children.forEach { child ->
                val childModifier = when {
                    child.weight > 0f -> Modifier.weight(child.weight)
                    child === grow -> Modifier.weight(1f)
                    else -> Modifier
                }
                val title = children.asSequence().flatMap { descendantTexts(it).asSequence() }.firstOrNull { it.isNotBlank() }
                val labelled = if (child.description == null && child.kind in listOf(NetworkKind.SWITCH, NetworkKind.CHECK, NetworkKind.FIELD, NetworkKind.SPINNER)) child.copy(description = title) else child
                NetworkContent(labelled, childModifier, refreshing, extraChildren)
            }
        }
        NetworkKind.TEXT -> {
            val text = @Composable { textModifier: Modifier ->
                Text(networkAnnotatedText(if (state!!.isAllCaps) state.text.toString().uppercase() else state.text), textModifier,
                    color = state.textColor?.let(::Color) ?: themeColor(R.attr.primaryTextColor),
                    textAlign = if (node.centerText) TextAlign.Center else if (node.alignEnd) TextAlign.End else TextAlign.Start,
                    maxLines = state.maxLines,
                    style = utilityTextStyle(node.appearance, resolvedTextSize, node.fontFamily, node.textWeight, node.letterSpacing))
            }
            val body = @Composable {
                if (node.leadingIcon != null) Row(m, verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(node.leadingIcon), null, Modifier.size(24.dp))
                    Spacer(Modifier.width(node.iconGap.dp))
                    text(Modifier.weight(1f))
                } else text(m)
            }
            if (node.selectable) SelectionContainer { body() } else body()
        }
        NetworkKind.ICON -> {
            val icon = @Composable {
                if (state!!.drawable != null) {
                    val bitmap = remember(state.drawable) { state.drawable!!.toBitmap().asImageBitmap() }
                    Image(bitmap, node.description?.toString(), Modifier.size(24.dp), colorFilter = state.textColor?.let { ColorFilter.tint(Color(it)) })
                } else state.imageResource?.let { Image(painterResource(it), node.description?.toString(), Modifier.size(24.dp), colorFilter = state.textColor?.let { tint -> ColorFilter.tint(Color(tint)) }) }
            }
            val animation = state?.let { refreshing[it] }
            val angle = if (animation != null) key(animation.generation) {
                val transition = rememberInfiniteTransition(label = "proxy refresh")
                val degrees by transition.animateFloat(0f, 360f,
                    infiniteRepeatable(tween(animation.durationMs, easing = Easing { animation.interpolator.getInterpolation(it) })), label = "refresh angle")
                degrees
            } else 0f
            if (clickable) IconButton({ state!!.performClick() }, m.rotate(angle), enabled = state!!.isEnabled && state.isClickable) { icon() }
            else Box(m.rotate(angle)) { icon() }
        }
        NetworkKind.SWITCH, NetworkKind.CHECK -> WgCheck(state!!, node.description?.toString(), node.kind == NetworkKind.SWITCH, m, compoundSource = originalCompoundKeyForId(node.key!!.substringAfterLast('.')))
        NetworkKind.RADIO -> OriginalRadioButton(originalCompoundKeyForId(node.key!!.substringAfterLast('.')), state!!.isChecked, { state.performClick() }, m, enabled = state.isEnabled)
        NetworkKind.BUTTON -> OriginalButton("dialog_set_proxy:" + node.key.orEmpty().substringAfterLast('.'), { state!!.performClick() }, m, enabled = state!!.isEnabled && state.isClickable) {
            Text(networkAnnotatedText(state.text), color = state.textColor?.let(::Color) ?: MaterialTheme.colorScheme.primary, style = utilityTextStyle(node.appearance, resolvedTextSize, node.fontFamily, node.textWeight, node.letterSpacing))
        }
        NetworkKind.FIELD -> NetworkEditField(state!!, m.fillMaxWidth(), node.numeric, node.description?.toString(),
            utilityTextStyle(node.appearance, resolvedTextSize, node.fontFamily, node.textWeight, node.letterSpacing))
        NetworkKind.SLIDER -> OriginalSlider(originalCompoundKeyForId(node.key!!.substringAfterLast('.')), node.slider!!.progress.toFloat(), { node.slider.userProgress(it.toInt()) }, m.pointerInput(node.slider) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                node.slider.startGesture()
                waitForUpOrCancellation(pass = PointerEventPass.Final)
                node.slider.finishGesture()
            }
        },
            enabled = node.slider.isEnabled, valueRange = 0f..node.slider.max.toFloat(), steps = (node.slider.max - 1).coerceAtLeast(0),
            onValueChangeFinished = { node.slider.finishGesture() })
        NetworkKind.SPINNER -> {
            val spinner = node.spinner!!
            var expanded by remember { mutableStateOf(false) }
            Box(m.alpha(spinner.alpha).pointerInteropFilter { spinner.dispatchTouch(it) }) {
                OriginalSpinner(spinner.selectedItem.orEmpty(), { expanded = true })
                DropdownMenu(expanded, { expanded = false }) {
                    spinner.items.forEachIndexed { index, item -> DropdownMenuItem({ Text(item) }, { spinner.setSelection(index); expanded = false }) }
                }
            }
        }
        NetworkKind.DIVIDER -> HorizontalDivider(m, color = themeColor(com.google.android.material.R.attr.colorOutlineVariant))
        NetworkKind.PROGRESS -> CircularProgressIndicator(m.size(32.dp))
    }
}

@Composable
fun NetworkTaskerContent(context: android.content.Context, input: WgControlState) {
    val density = LocalDensity.current
    fun px(value: Int) = with(density) { value.toDp() }
    Column(Modifier.fillMaxWidth().padding(start = px(40), top = px(10), end = px(40))) {
        SelectionContainer {
            Text(context.getString(R.string.adv_tasker_dialog_msg), Modifier.padding(start = px(50), top = px(40), end = px(50)), style = utilityTextStyle(size = 16f))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The original multiline editor explicitly enabled horizontal scrolling and px padding.
            OriginalTextField(input.text.toString(), { input.setText(it) },
                Modifier.horizontalScroll(remember { ScrollState(0) }).widthIn(min = maxWidth),
                style = OriginalFieldStyle.Tasker,
                placeholder = { Text(input.hint.toString()) })
        }
        SelectionContainer {
            Text(context.getString(R.string.adv_tasker_dialog_instructions), Modifier.padding(start = px(50), top = px(40), end = px(50)), style = utilityTextStyle(size = 16f))
        }
    }
}

private fun descendantTexts(node: NetworkNode): List<String> =
    if (node.kind == NetworkKind.TEXT) listOf(node.control?.text?.toString().orEmpty()) else node.children.flatMap(::descendantTexts)

/** Mirrors inputType=number's DigitsKeyListener filtering, including paste and locale digits. */
@Suppress("DEPRECATION")
fun networkDigitsOnly(value: String, locale: java.util.Locale): String {
    val filter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        android.text.method.DigitsKeyListener.getInstance(locale, false, false)
    } else {
        android.text.method.DigitsKeyListener.getInstance(false, false)
    }
    return filter.filter(value, 0, value.length, android.text.SpannedString(""), 0, 0)?.toString() ?: value
}

/** Compose text input with the selected Android editTextStyle background, rather than an outlined M3 field. */
@Composable
// These TypedArray indexes address explicitly ordered attribute arrays, not R.styleable IDs.
private fun NetworkEditField(state: WgControlState, modifier: Modifier, numeric: Boolean = false,
                             description: String? = null, style: TextStyle = utilityTextStyle()) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    val decoration = remember(context) {
        val attrs = context.obtainOrderedStyledAttributes(null,
            intArrayOf(android.R.attr.background, android.R.attr.textColorHint, android.R.attr.minHeight),
            androidx.appcompat.R.attr.editTextStyle, 0)
        val drawable = attrs.getDrawable(0)?.mutate()
        val hintColor = attrs.getColorStateList(1)
        val minHeight = attrs.getDimension(2, 0f)
        attrs.recycle()
        val padding = android.graphics.Rect()
        drawable?.getPadding(padding)
        Triple(drawable, padding, hintColor to minHeight)
    }
    val states = intArrayOf(if (state.isEnabled) android.R.attr.state_enabled else -android.R.attr.state_enabled,
        if (focused) android.R.attr.state_focused else -android.R.attr.state_focused)
    val foreground = themeColor(R.attr.primaryTextColor)
    val hintColor = decoration.third.first?.let { Color(it.getColorForState(states, it.defaultColor)) } ?: themeColor(R.attr.primaryLightColorText)
    val padding = decoration.second
    BasicTextField(state.text.toString(), {
        state.setText(if (numeric) networkDigitsOnly(it, locale) else it)
    }, modifier.heightIn(min = maxOf(48.dp, with(density) { decoration.third.second.toDp() }))
        .semantics { originalFieldFamily = OriginalFieldStyle.Network.name }
        .onFocusChanged { focused = it.isFocused }
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .drawBehind {
            decoration.first?.let { drawable ->
                drawable.state = states
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawIntoCanvas { drawable.draw(it.nativeCanvas) }
            }
        }.padding(start = with(density) { padding.left.toDp() }, top = with(density) { padding.top.toDp() },
            end = with(density) { padding.right.toDp() }, bottom = with(density) { padding.bottom.toDp() }),
        enabled = state.isEnabled, maxLines = state.maxLines,
        textStyle = style.copy(color = foreground), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = if (state.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (state.isPassword) KeyboardType.Password else if (numeric) KeyboardType.Number else KeyboardType.Text),
        decorationBox = { field ->
            Box {
                if (state.text.isEmpty()) Text(state.hint.toString(), style = style, color = hintColor)
                field()
            }
        })
}
