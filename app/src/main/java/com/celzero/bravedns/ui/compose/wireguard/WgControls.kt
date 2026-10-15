/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.controls.*

import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.text.style.StrikethroughSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

private fun Modifier.wgState(node: WgControlState): Modifier = alpha(if (node.visibility == View.INVISIBLE) 0f else node.alpha)
    .then(if (node.visibility == View.INVISIBLE) Modifier.clearAndSetSemantics { } else Modifier)

@Composable fun WgText(node: WgControlState, modifier: Modifier = Modifier, interactive: Boolean = true) {
    if (node.visibility == View.GONE) return
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val look = resolveWgText(LocalContext.current, LocalDensity.current, node.textSource, node)
    Text(wgAnnotatedText(if (look.allCaps) node.text.toString().uppercase() else node.text),
        modifier.wgState(node).then(if (interactive && node.hasClickListener)
            Modifier.clickable(interactionSource = interactions, indication = null, enabled = node.isEnabled && node.isClickable) { node.performClick() } else Modifier),
        color = Color(look.colors.getColorForState(wgTextStateSet(node, pressed), look.colors.defaultColor)),
        style = look.style, maxLines = look.maxLines, minLines = look.minLines,
        softWrap = !look.singleLine, overflow = look.overflow)
}

fun wgAnnotatedText(value: CharSequence?): AnnotatedString {
    val text = value ?: ""
    if (text !is Spanned) return AnnotatedString(text.toString())
    return AnnotatedString.Builder(text.toString()).apply {
        text.getSpans(0, text.length, Any::class.java).forEach { span ->
            val style = when (span) {
                is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
                is RelativeSizeSpan -> SpanStyle(fontSize = span.sizeChange.em)
                is StyleSpan -> SpanStyle(
                    fontWeight = if (span.style and android.graphics.Typeface.BOLD != 0) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (span.style and android.graphics.Typeface.ITALIC != 0) FontStyle.Italic else FontStyle.Normal)
                is TypefaceSpan -> SpanStyle(fontFamily = FontFamily(android.graphics.Typeface.create(span.family, android.graphics.Typeface.NORMAL)))
                is BackgroundColorSpan -> SpanStyle(background = Color(span.backgroundColor))
                is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                else -> null
            }
            style?.let { addStyle(it, text.getSpanStart(span), text.getSpanEnd(span)) }
        }
    }.toAnnotatedString()
}

@Composable fun WgAction(node: WgControlState, modifier: Modifier = Modifier, source: String) {
    if (node.visibility == View.GONE) return
    com.celzero.bravedns.ui.compose.controls.OriginalButton(source,
        onClick = { node.performClick() }, enabled = node.isEnabled && node.isClickable,
        modifier = modifier.wgState(node), contentColor = node.textColor?.let(::Color),
        backgroundTint = node.backgroundTintList) {
        Text(wgAnnotatedText(if (node.isAllCaps) node.text.toString().uppercase() else node.text))
    }
}

@Composable fun WgCheck(node: WgControlState, label: String? = null, switch: Boolean = false, modifier: Modifier = Modifier, compoundSource: String, interactive: Boolean = true) {
    if (node.visibility == View.GONE) return
    val m = modifier.wgState(node).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
    if (switch) OriginalSwitch(compoundSource, node.isChecked, if (interactive) ({ node.performClick() }) else null, m, enabled = node.isEnabled && node.isClickable)
    else OriginalCheckbox(compoundSource, node.isChecked, if (interactive) ({ node.performClick() }) else null, m, enabled = node.isEnabled && node.isClickable,
        buttonTint = node.buttonTint?.let(android.content.res.ColorStateList::valueOf))
}

@Composable fun WgIcon(node: WgControlState, label: String? = null, modifier: Modifier = Modifier) {
    if (node.visibility == View.GONE) return
    val m = modifier.wgState(node)
    val content: @Composable () -> Unit = {
        if (node.drawable != null) {
            val bitmap = remember(node.drawable) { node.drawable!!.toBitmap(width = 96, height = 96).asImageBitmap() }
            Image(bitmap, label, Modifier.size(40.dp))
        } else node.imageResource?.let { Icon(painterResource(it), label, Modifier.size(24.dp), tint = node.textColor?.let(::Color) ?: Color.Unspecified) }
    }
    if (node.hasClickListener) IconButton({ node.performClick() }, m, enabled = node.isEnabled && node.isClickable) { content() }
    else Box(m) { content() }
}

@Composable fun WgProgress(node: WgControlState, modifier: Modifier = Modifier) {
    if (node.visibility == View.GONE) return
    val source = requireNotNull(node.progressSource) { "WgProgress requires its baseline source" }
    val total = node.max.coerceAtLeast(1).toFloat()
    if (source == "fragment_wg_nw_stats:fss_progress_bar") OriginalHorizontalProgress(
        modifier.wgState(node), node.progress / total, node.secondaryProgress / total, visible = node.isVisible)
    else OriginalLinearProgress(source, modifier.wgState(node), node.progress / total,
        node.isIndeterminate, OriginalProgressOverrides(node.indicatorColor, node.trackColor,
            node.trackThicknessDp, node.trackCornerRadiusDp, node.indicatorTrackGapDp, node.trackStopIndicatorSizeDp),
        animate = node.animateProgress, visible = node.isVisible)
}

/** Each caller supplies its original child-container padding; the card owns only its style. */
@Composable fun WgCard(node: WgControlState? = null, modifier: Modifier = Modifier,
                      contentPadding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    if (node?.visibility == View.GONE) return
    val width = with(LocalDensity.current) { (node?.strokeWidth ?: 0).toDp() }
    val m = modifier.fillMaxWidth().then(if (node != null) Modifier.wgState(node) else Modifier)
        .then(if (node?.hasClickListener == true) Modifier.clickable(enabled = node.isEnabled && node.isClickable) { node.performClick() } else Modifier)
    com.celzero.bravedns.ui.compose.controls.OriginalCardSurface(
        modifier = m,
        cornerRadius = node?.radiusDp?.dp,
        elevation = node?.elevationDp?.dp,
        containerColor = node?.backgroundColor?.let(::Color),
        border = node?.let { BorderStroke(width, Color(it.strokeColor)) },
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable fun WgField(node: WgControlState, label: CharSequence, modifier: Modifier = Modifier, readOnly: Boolean = false,
                        keyboard: KeyboardType = KeyboardType.Text, maxLines: Int = 4, textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start, fieldStyle: OriginalFieldStyle = OriginalFieldStyle.WgEditor, expandedHint: Boolean = true, onDone: (() -> Unit)? = node.onEditorDone, trailing: (@Composable () -> Unit)? = null) {
    if (node.visibility == View.GONE) return
    OriginalTextField(node.text.toString(), { node.setText(it) }, modifier.fillMaxWidth().wgState(node)
        .then(if (fieldStyle == OriginalFieldStyle.WgEditor) Modifier.padding(4.dp).padding(top = 15.dp) else Modifier)
        .then(if (fieldStyle == OriginalFieldStyle.WgInlineSsid) Modifier.semantics { contentDescription = label.toString() } else Modifier),
        style = fieldStyle, expandedHint = expandedHint, enabled = node.isEnabled,
        textStyle = androidx.compose.ui.text.TextStyle(textAlign = textAlign, color = node.textColor?.let(::Color) ?: Color.Unspecified,
            fontSize = node.textSizeSp?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified),
        readOnly = readOnly, label = { Text(label.toString()) }, placeholder = { Text(node.hint.toString()) },
        visualTransformation = if (node.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        maxLines = maxLines, trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = if (node.isPassword) KeyboardType.Password else keyboard, autoCorrectEnabled = !node.noSuggestions, imeAction = if (onDone != null) ImeAction.Done else if (node.imeNext) ImeAction.Next else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }))
}
