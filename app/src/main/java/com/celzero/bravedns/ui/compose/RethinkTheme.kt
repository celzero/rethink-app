/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import androidx.annotation.AttrRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.google.android.material.R as MaterialR

/** Uses the host's selected Activity or Dialog theme, including Plus/Frost/alpha. */
@Composable
fun themeColor(@AttrRes attribute: Int): Color {
    LocalConfiguration.current
    val values = LocalContext.current.obtainOrderedStyledAttributes(intArrayOf(attribute))
    return try {
        check(values.hasValue(0)) { "Required Rethink theme color is missing: $attribute" }
        Color(values.getColor(0, android.graphics.Color.TRANSPARENT))
    } finally { values.recycle() }
}

@Composable
fun RethinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = themeColor(androidx.appcompat.R.attr.colorPrimary),
            onPrimary = themeColor(MaterialR.attr.colorOnPrimary),
            primaryContainer = themeColor(MaterialR.attr.colorPrimaryContainer),
            onPrimaryContainer = themeColor(MaterialR.attr.colorOnPrimaryContainer),
            secondary = themeColor(MaterialR.attr.colorSecondary),
            onSecondary = themeColor(MaterialR.attr.colorOnSecondary),
            secondaryContainer = themeColor(R.attr.colorSecondaryContainer),
            onSecondaryContainer = themeColor(R.attr.colorOnSecondaryContainer),
            tertiary = themeColor(MaterialR.attr.colorTertiary),
            onTertiary = themeColor(MaterialR.attr.colorOnTertiary),
            tertiaryContainer = themeColor(R.attr.colorTertiaryContainer),
            onTertiaryContainer = themeColor(R.attr.colorOnTertiaryContainer),
            error = themeColor(androidx.appcompat.R.attr.colorError),
            onError = themeColor(MaterialR.attr.colorOnError),
            errorContainer = themeColor(R.attr.colorErrorContainer),
            onErrorContainer = themeColor(R.attr.colorOnErrorContainer),
            background = themeColor(R.attr.background),
            onBackground = themeColor(R.attr.primaryTextColor),
            surface = themeColor(MaterialR.attr.colorSurface),
            onSurface = themeColor(R.attr.colorOnSurface),
            surfaceVariant = themeColor(R.attr.colorSurfaceVariant),
            onSurfaceVariant = themeColor(R.attr.colorOnSurfaceVariant),
            surfaceTint = themeColor(androidx.appcompat.R.attr.colorPrimary),
            inverseSurface = themeColor(MaterialR.attr.colorSurfaceInverse),
            inverseOnSurface = themeColor(MaterialR.attr.colorOnSurfaceInverse),
            inversePrimary = themeColor(MaterialR.attr.colorPrimaryInverse),
            outline = themeColor(R.attr.colorOutline),
            outlineVariant = themeColor(R.attr.border),
            scrim = Color.Black,
            surfaceDim = themeColor(R.attr.colorSurfaceContainerLowest),
            surfaceBright = themeColor(R.attr.colorSurfaceContainerHighest),
            surfaceContainer = themeColor(R.attr.colorSurfaceContainer),
            surfaceContainerLow = themeColor(R.attr.colorSurfaceContainerLow),
            surfaceContainerHigh = themeColor(R.attr.colorSurfaceContainerHigh),
            surfaceContainerHighest = themeColor(R.attr.colorSurfaceContainerHighest),
            surfaceContainerLowest = themeColor(R.attr.colorSurfaceContainerLowest),
        ),
        typography = rethinkTypography(),
        content = content,
    )
}

/** Resolve the existing Android TextAppearance values, including font-relative spacing.
 * Keeping SP spacing constants would incorrectly scale letter spacing on Android 14+.
 */
@Composable
private fun rethinkTypography(): Typography {
    val context = LocalContext.current
    @Composable fun style(attribute: Int) = com.celzero.bravedns.ui.compose.utilities.utilityTextStyle(
        appearance = com.celzero.bravedns.ui.compose.utilities.resolveUtilityStyle(context, attribute)
    )
    return Typography(
        displayLarge = style(MaterialR.attr.textAppearanceDisplayLarge),
        displayMedium = style(MaterialR.attr.textAppearanceDisplayMedium),
        displaySmall = style(MaterialR.attr.textAppearanceDisplaySmall),
        headlineLarge = style(MaterialR.attr.textAppearanceHeadlineLarge),
        headlineMedium = style(MaterialR.attr.textAppearanceHeadlineMedium),
        headlineSmall = style(MaterialR.attr.textAppearanceHeadlineSmall),
        titleLarge = style(MaterialR.attr.textAppearanceTitleLarge),
        titleMedium = style(MaterialR.attr.textAppearanceTitleMedium),
        titleSmall = style(MaterialR.attr.textAppearanceTitleSmall),
        bodyLarge = style(MaterialR.attr.textAppearanceBodyLarge),
        bodyMedium = style(MaterialR.attr.textAppearanceBodyMedium),
        bodySmall = style(MaterialR.attr.textAppearanceBodySmall),
        labelLarge = style(MaterialR.attr.textAppearanceLabelLarge),
        labelMedium = style(MaterialR.attr.textAppearanceLabelMedium),
        labelSmall = style(MaterialR.attr.textAppearanceLabelSmall),
    )
}
