/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import androidx.compose.ui.platform.LocalLocale
import com.celzero.bravedns.ui.compose.controls.*
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.utilities.resolveUtilityStyle
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR
import com.celzero.bravedns.util.UIUtils

/** Native confirmation content; action dispatch precedes dismissal, as in the original dialogs. */
class WgAlertDialogBuilder(private val context: Context, private val theme: Int = 0) {
    private var title: CharSequence = ""
    private var message: CharSequence = ""
    private var cancelable = true
    private var icon: Int? = null
    private var rows: List<CharSequence> = emptyList()
    private var choices: Triple<Array<String>, Int, (DialogInterface, Int) -> Unit>? = null
    private var positive: Pair<CharSequence, (DialogInterface, Int) -> Unit>? = null
    private var negative: Pair<CharSequence, (DialogInterface, Int) -> Unit>? = null
    private var neutral: Pair<CharSequence, (DialogInterface, Int) -> Unit>? = null
    private var cancel: ((DialogInterface) -> Unit)? = null
    fun setTitle(value: CharSequence) = apply { title = value }
    fun setTitle(value: Int) = setTitle(context.getText(value))
    fun setMessage(value: CharSequence) = apply { message = value }
    fun setMessage(value: Int) = setMessage(context.getText(value))
    fun setIcon(value: Int) = apply { icon = value }
    fun setCancelable(value: Boolean) = apply { cancelable = value }
    fun setOnCancelListener(value: (DialogInterface) -> Unit) = apply { cancel = value }
    fun setPositiveButton(value: CharSequence, action: (DialogInterface, Int) -> Unit) = apply { positive = value to action }
    fun setPositiveButton(value: Int, action: (DialogInterface, Int) -> Unit) = setPositiveButton(context.getText(value), action)
    fun setNegativeButton(value: CharSequence, action: ((DialogInterface, Int) -> Unit)?) = apply { negative = value to (action ?: { _, _ -> }) }
    fun setNegativeButton(value: Int, action: ((DialogInterface, Int) -> Unit)?) = setNegativeButton(context.getText(value), action)
    fun setNeutralButton(value: CharSequence, action: (DialogInterface, Int) -> Unit) = apply { neutral = value to action }
    fun setNeutralButton(value: Int, action: (DialogInterface, Int) -> Unit) = setNeutralButton(context.getText(value), action)
    fun setItems(value: Array<String>, unused: Nothing?) = apply { rows = value.toList() }
    fun setSingleChoiceItems(value: Array<String>, selected: Int, action: (DialogInterface, Int) -> Unit) = apply { choices = Triple(value, selected, action) }
    fun create(): Dialog {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first()
        val resolvedTheme = if (theme != 0) theme else {
            val value = android.util.TypedValue()
            if (context.theme.resolveAttribute(com.google.android.material.R.attr.materialAlertDialogTheme, value, true) && value.resourceId != 0) value.resourceId
            else {
                context.theme.resolveAttribute(androidx.appcompat.R.attr.alertDialogTheme, value, true)
                value.resourceId
            }
        }
        val dialog = Dialog(context, resolvedTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { cancel?.invoke(it) }
        var selectedChoice by mutableIntStateOf(choices?.second ?: -1)
        var terminalActionDispatched = false
        fun dispatchChoice(action: (DialogInterface, Int) -> Unit, index: Int) {
            if (terminalActionDispatched || !dialog.isShowing) return
            selectedChoice = index
            action(dialog, index)
            // A single-choice list does not dismiss by itself. Respect the caller's decision.
            if (!dialog.isShowing) terminalActionDispatched = true
        }
        fun dispatchButton(action: (DialogInterface, Int) -> Unit, which: Int) {
            if (terminalActionDispatched || !dialog.isShowing) return
            terminalActionDispatched = true
            action(dialog, which)
            dialog.dismiss()
        }
        dialog.setWgContent(activity) {
            val preferredPadding = wgDialogDimension(AppCompatR.attr.dialogPreferredPadding)
            val titlePadding = wgDialogDimension(android.R.attr.paddingTop, MaterialR.attr.materialAlertDialogTitlePanelStyle)
            val bodyStyle = utilityTextStyle(defaultStyleAttribute = MaterialR.attr.materialAlertDialogBodyTextStyle, lineExtraDp = 0f)
            val bodyColor = wgDialogTextColor(MaterialR.attr.materialAlertDialogBodyTextStyle, themeColor(R.attr.colorOnSurfaceVariant))
            val hasTitle = title.isNotEmpty()
            val hasContent = message.isNotEmpty() || rows.isNotEmpty() || choices != null
            val buttons = listOf(neutral to DialogInterface.BUTTON_NEUTRAL, negative to DialogInterface.BUTTON_NEGATIVE,
                positive to DialogInterface.BUTTON_POSITIVE).filter { it.first != null }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    if (hasTitle) {
                        Row(Modifier.fillMaxWidth().padding(start = preferredPadding, end = preferredPadding, top = titlePadding),
                            verticalAlignment = Alignment.CenterVertically) {
                            icon?.let { Icon(painterResource(it), null, Modifier.padding(end = 16.dp).size(24.dp)) }
                            WgDialogTitle(title, Modifier.weight(1f, fill = false))
                        }
                        if (hasContent) Spacer(Modifier.height(16.dp)) // m3_alert_dialog_title_bottom_margin
                    }
                    if (hasContent) {
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(min = 48.dp).verticalScroll(rememberScrollState())) {
                            if (!hasTitle) Spacer(Modifier.height(titlePadding))
                            if (message.isNotEmpty()) Text(wgAnnotatedText(message), Modifier.fillMaxWidth().padding(horizontal = preferredPadding),
                                style = bodyStyle, color = bodyColor)
                            val listStyle = utilityTextStyle(appearance = resolveUtilityStyle(LocalContext.current, AppCompatR.attr.textAppearanceListItemSmall), lineExtraDp = 0f)
                            rows.forEach { Text(wgAnnotatedText(it), Modifier.fillMaxWidth().padding(horizontal = preferredPadding).heightIn(min = 48.dp), style = listStyle) }
                            choices?.let { (labels, _, action) ->
                                labels.forEachIndexed { index, label ->
                                    Row(Modifier.fillMaxWidth().selectable(selected = index == selectedChoice, role = Role.RadioButton,
                                        onClick = { dispatchChoice(action, index) }).padding(horizontal = preferredPadding).heightIn(min = 48.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(index == selectedChoice, onClick = null)
                                        Text(wgAnnotatedText(label), style = utilityTextStyle(appearance = resolveUtilityStyle(LocalContext.current, android.R.attr.textAppearanceMedium), lineExtraDp = 0f))
                                    }
                                }
                            }
                            if (buttons.isEmpty()) Spacer(Modifier.height(titlePadding))
                        }
                    }
                    if (buttons.isNotEmpty()) {
                        // The XML ButtonBarLayout explicitly resolves direction from locale, not a
                        // parent view's forced direction. Preserve its neutral spacer and stacking.
                        val locale = LocalLocale.current.platformLocale
                        val direction = if (android.text.TextUtils.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                            WgDialogButtonBar(neutral != null, Modifier.fillMaxWidth().padding(horizontal = preferredPadding, vertical = 14.dp)) {
                                buttons.forEach { (button, which) ->
                                    val (label, action) = checkNotNull(button)
                                    val styleAttribute = when (which) {
                                        DialogInterface.BUTTON_NEUTRAL -> AppCompatR.attr.buttonBarNeutralButtonStyle
                                        DialogInterface.BUTTON_NEGATIVE -> AppCompatR.attr.buttonBarNegativeButtonStyle
                                        else -> AppCompatR.attr.buttonBarPositiveButtonStyle
                                    }
                                    val style = utilityTextStyle(defaultStyleAttribute = styleAttribute, lineExtraDp = 0f)
                                    // Use the native Material text-button accent. Reading the legacy widget selector
                                    // directly can expose its unresolved magenta fallback instead of themed color.
                                    val surface = MaterialTheme.colorScheme.surfaceContainerHigh.compositeOver(
                                        themeColor(android.R.attr.colorBackground).copy(alpha = 1f))
                                    val primary = MaterialTheme.colorScheme.primary
                                    val color = if (ColorUtils.calculateContrast(primary.compositeOver(surface).toArgb(), surface.toArgb()) >= 4.5)
                                        primary else MaterialTheme.colorScheme.onSurface
                                    OriginalButton(when (which) { android.content.DialogInterface.BUTTON_NEGATIVE -> "@dialog_negative"; android.content.DialogInterface.BUTTON_NEUTRAL -> "@dialog_neutral"; else -> "@dialog_positive" }, { dispatchButton(action, which) }, Modifier.padding(start = 8.dp).defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)) {
                                        Text(wgAnnotatedText(label), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (theme == R.style.App_Dialog_NoDim) {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setDimAmount(0f)
        }
        UIUtils.capDialogWidth(dialog)
        return dialog
    }
    fun show(): Dialog = create().also { it.show() }
}

/** DialogTitle's original single-line ellipsis fallback changes only size and maxLines. */
@Composable private fun WgDialogTitle(title: CharSequence, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val style = utilityTextStyle(defaultStyleAttribute = MaterialR.attr.materialAlertDialogTitleTextStyle, lineExtraDp = 0f)
    val color = wgDialogTextColor(MaterialR.attr.materialAlertDialogTitleTextStyle, MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val text = wgAnnotatedText(title)
    val smallerSize = remember(context, density) {
        context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.textSize), android.R.attr.textAppearanceMedium,
            android.R.style.TextAppearance_Medium).let {
            try { with(density) { it.getDimensionPixelSize(0, 0).toFloat().toSp() } } finally { it.recycle() }
        }
    }
    BoxWithConstraints(modifier) {
        val singleLine = measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = constraints.copy(minWidth = 0, minHeight = 0))
        val reduced = singleLine.isLineEllipsized(0) && smallerSize.value > 0f
        // Re-resolve the font-metric line height at the fallback size; do not retain the
        // original larger SP line-height ratio or convert nonlinear-scaled pixels twice.
        val fallback = utilityTextStyle(defaultStyleAttribute = MaterialR.attr.materialAlertDialogTitleTextStyle, size = smallerSize.value, lineExtraDp = 0f)
        Text(text, style = if (reduced) fallback else style, color = color, maxLines = if (reduced) 2 else 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun wgDialogDimension(attribute: Int, styleAttribute: Int = 0): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density, attribute, styleAttribute) {
        context.obtainOrderedStyledAttributes(null, intArrayOf(attribute), styleAttribute, 0).let {
            try { with(density) { it.getDimension(0, 0f).toDp() } } finally { it.recycle() }
        }
    }
}

/** Widget colors can override their TextAppearance, including NoDim negative/neutral colors. */
// These TypedArray indexes address explicitly ordered attribute arrays, not R.styleable IDs.
@Composable private fun wgDialogTextColor(styleAttribute: Int, fallback: Color): Color {
    val context = LocalContext.current
    return remember(context, styleAttribute, fallback) {
        val widget = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.textColor, android.R.attr.textAppearance), styleAttribute, 0)
        try {
            val appearance = widget.getResourceId(1, 0)
            val inherited = if (appearance != 0) context.obtainOrderedStyledAttributes(appearance, intArrayOf(android.R.attr.textColor)) else null
            try {
                val colors = if (widget.hasValue(0)) widget.getColorStateList(0) else inherited?.getColorStateList(0)
                colors?.let { Color(it.getColorForState(intArrayOf(android.R.attr.state_enabled), it.defaultColor)) } ?: fallback
            } finally { inherited?.recycle() }
        } finally { widget.recycle() }
    }
}

/** Native equivalent of the XML button bar: neutral at start, other actions at end;
 * on width overflow, the XML bar reverses the visible children into an end-aligned stack. */
@Composable private fun WgDialogButtonBar(hasNeutral: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val children = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val sum = children.sumOf { it.width }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else sum
        val stacked = sum > width
        val height = if (stacked) children.sumOf { it.height } else children.maxOfOrNull { it.height } ?: 0
        layout(width, constraints.constrainHeight(height)) {
            if (stacked) {
                var y = 0
                children.asReversed().forEach { child -> child.placeRelative(width - child.width, y); y += child.height }
            } else {
                var x = width - sum
                children.forEachIndexed { index, child ->
                    if (hasNeutral && index == 0) child.placeRelative(0, height - child.height)
                    else child.placeRelative(x, height - child.height)
                    x += child.width
                }
            }
        }
    }
}
