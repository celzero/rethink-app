/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.celzero.bravedns.ui.compose.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.celzero.bravedns.util.Themes
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicMaterialThemeState

private val RethinkCoralSeed = Color(0xffFF5D73)
private val RethinkRoseSeed = Color(0xffEC4899)
private val RethinkTealSeed = Color(0xff14B8A6)
private val RethinkBlueSeed = Color(0xff3B82F6)
private val RethinkPurpleSeed = Color(0xffA855F7)
private val RethinkOrangeSeed = Color(0xffF97316)
private val RethinkGreenSeed = Color(0xff22C55E)
private val RethinkAmberSeed = Color(0xffF2B705)
private val RethinkCyanSeed = Color(0xff06B6D4)
private val RethinkIndigoSeed = Color(0xff6366F1)

// M3 Expressive shape scale — generous corner radii throughout
val RethinkShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimensions.cornerRadiusSm),
    small = RoundedCornerShape(Dimensions.cornerRadiusSmMd),
    medium = RoundedCornerShape(Dimensions.cornerRadiusLg),
    large = RoundedCornerShape(Dimensions.cornerRadius2xl),
    extraLarge = RoundedCornerShape(Dimensions.heroCornerRadius)
)

enum class RethinkColorPreset(val id: Int, val seedColor: Color?) {
    AUTO(0, null),
    DYNAMIC(1, null),
    CORAL(2, RethinkCoralSeed),
    ROSE(11, RethinkRoseSeed),
    TEAL(3, RethinkTealSeed),
    BLUE(4, RethinkBlueSeed),
    PURPLE(5, RethinkPurpleSeed),
    ORANGE(6, RethinkOrangeSeed),
    GREEN(7, RethinkGreenSeed),
    AMBER(8, RethinkAmberSeed),
    CYAN(9, RethinkCyanSeed),
    INDIGO(10, RethinkIndigoSeed);

    companion object {
        fun fromId(id: Int): RethinkColorPreset {
            return entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}

@Composable
fun RethinkTheme(
    themePreference: Int = Themes.SYSTEM_DEFAULT.id,
    colorPreset: RethinkColorPreset = RethinkColorPreset.AUTO,
    content: @Composable () -> Unit
) {
    val resolvedThemePreference = Themes.resolveThemePreference(
        isDarkThemeOn = androidx.compose.foundation.isSystemInDarkTheme(),
        preference = themePreference
    )
    val darkTheme = resolvedThemePreference == Themes.DARK.id || resolvedThemePreference == Themes.DARK_PLUS.id

    val useDynamicColor = when (colorPreset) {
        RethinkColorPreset.AUTO -> Themes.useDynamicColor(themePreference)
        RethinkColorPreset.DYNAMIC -> Themes.useDynamicColor(themePreference)
        else -> false
    } && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val seedColor = colorPreset.seedColor ?: RethinkCoralSeed

    val colorScheme = when {
        useDynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }

        else ->
            rememberDynamicMaterialThemeState(
                seedColor = seedColor,
                isDark = darkTheme,
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
            ).colorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = RethinkShapes,
        content = content
    )
}
