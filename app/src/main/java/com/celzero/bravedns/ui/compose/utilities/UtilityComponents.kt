/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities

import androidx.compose.ui.platform.LocalLocale
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor


@Composable
fun UtilityText(control: UtilityControl, modifier: Modifier = Modifier) {
    if (control.isVisible) Text(if (control.allCaps) control.text.toString().uppercase(LocalLocale.current.platformLocale) else control.text.toString(), modifier.testTag(control.tag),
        maxLines = control.maxLines, overflow = if (control.ellipsize) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
        textAlign = if (control.centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
        color = control.textColor?.let(::Color) ?: themeColor(R.attr.primaryTextColor),
        style = utilityTextStyle(control.textAppearance, control.fontSize, control.fontFamily, control.fontStyle, control.letterSpacing))
}

/** Draw platform vector/shape artwork directly in Compose; never inflates or wraps a View. */
@Composable
fun UtilityImage(control: UtilityControl, modifier: Modifier = Modifier, description: String? = null, tagged: Boolean = true) {
    if (!control.isVisible) return
    val context = LocalContext.current
    val drawable = control.drawable ?: control.imageRes?.let { remember(it, context) { ContextCompat.getDrawable(context, it) } } ?: return
    val bitmap = remember(drawable) { drawable.toBitmap(width = maxOf(1, drawable.intrinsicWidth).coerceAtMost(256), height = maxOf(1, drawable.intrinsicHeight).coerceAtMost(256)).asImageBitmap() }
    Image(bitmap, description, if (tagged) modifier.testTag(control.tag) else modifier, colorFilter = control.colorFilter?.let { ColorFilter.tint(Color(it)) })
}

@Composable
fun utilityImage(id: Int, modifier: Modifier = Modifier, description: String? = null, tint: Color? = null) {
    val context = LocalContext.current
    val control = remember(context, id) { UtilityControl(context).apply { imageRes = id } }
    control.colorFilter = tint?.toArgb()
    UtilityImage(control, modifier, description)
}

@Composable
fun Modifier.utilityHeroBackground(): Modifier {
    val colors = listOf(themeColor(R.attr.serverHeaderGradientStart), themeColor(R.attr.serverHeaderGradientCenter), themeColor(R.attr.serverHeaderGradientEnd))
    return this.drawBehind { drawRect(Brush.linearGradient(colors, start = androidx.compose.ui.geometry.Offset(size.width, size.height), end = androidx.compose.ui.geometry.Offset.Zero)) }
}

@Composable
fun UtilityButton(control: UtilityControl, modifier: Modifier = Modifier, source: String) {
    if (control.isVisible) com.celzero.bravedns.ui.compose.controls.OriginalButton(source,
        onClick = { control.performClick() }, enabled = control.isEnabled,
        modifier = modifier.testTag(control.tag), contentColor = control.textColor?.let(::Color),
        backgroundTint = control.backgroundTintList) {
        Text(control.text.toString())
    }
}
