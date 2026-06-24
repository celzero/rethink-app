/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.settings

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.theme.RethinkColorPreset
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.ui.compose.theme.rememberReducedMotion
import com.celzero.bravedns.util.Themes

enum class AppearanceMode {
    AUTO,
    LIGHT,
    DARK;

    fun toThemePreference(): Int {
        return when (this) {
            AUTO -> Themes.SYSTEM_DEFAULT.id
            LIGHT -> Themes.LIGHT_PLUS.id
            DARK -> Themes.DARK_PLUS.id
        }
    }

    fun icon(): ImageVector {
        return when (this) {
            AUTO -> Icons.Rounded.BrightnessAuto
            LIGHT -> Icons.Rounded.LightMode
            DARK -> Icons.Rounded.DarkMode
        }
    }

    companion object {
        fun fromThemePreference(preference: Int): AppearanceMode {
            return when (preference) {
                Themes.SYSTEM_DEFAULT.id -> AUTO
                Themes.LIGHT.id, Themes.LIGHT_PLUS.id -> LIGHT
                else -> DARK
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppearanceSettingsCard(
    themePreference: Int,
    colorPresetId: Int,
    onAppearanceModeSelected: (AppearanceMode) -> Unit,
    onColorPresetSelected: (RethinkColorPreset) -> Unit,
    sectionHeaderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showSectionHeader: Boolean = true
) {
    var appearanceMode by remember(themePreference) {
        mutableStateOf(AppearanceMode.fromThemePreference(themePreference))
    }
    var colorPreset by remember(colorPresetId) {
        mutableStateOf(
            RethinkColorPreset.fromId(colorPresetId).let {
                if (it == RethinkColorPreset.AUTO) RethinkColorPreset.DYNAMIC else it
            }
        )
    }

    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val systemDarkMode = isSystemInDarkTheme()
    val dynamicSwatchColor = remember(dynamicSupported, systemDarkMode, context) {
        if (!dynamicSupported) {
            Color(0xff7C8BFF)
        } else {
            if (systemDarkMode) androidx.compose.material3.dynamicDarkColorScheme(context).primary
            else androidx.compose.material3.dynamicLightColorScheme(context).primary
        }
    }
    val dynamicPreviewColor = dynamicSwatchColor
    val selectableColorPresets = remember {
        listOf(
            RethinkColorPreset.DYNAMIC,
            RethinkColorPreset.CORAL,
            RethinkColorPreset.ROSE,
            RethinkColorPreset.ORANGE,
            RethinkColorPreset.AMBER,
            RethinkColorPreset.GREEN,
            RethinkColorPreset.TEAL,
            RethinkColorPreset.CYAN,
            RethinkColorPreset.BLUE,
            RethinkColorPreset.INDIGO,
            RethinkColorPreset.PURPLE
        )
    }

    Column {
        if (showSectionHeader) {
            SectionHeader(
                title = stringResource(R.string.settings_theme_heading),
                color = sectionHeaderColor
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
        ) {
            val modeOptions = listOf(
                Triple(AppearanceMode.AUTO, AppearanceMode.AUTO.toDisplayName(), AppearanceMode.AUTO.icon()),
                Triple(AppearanceMode.LIGHT, AppearanceMode.LIGHT.toDisplayName(), AppearanceMode.LIGHT.icon()),
                Triple(AppearanceMode.DARK, AppearanceMode.DARK.toDisplayName(), AppearanceMode.DARK.icon())
            )
            val selectedIndex = modeOptions.indexOfFirst { it.first == appearanceMode }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.dp, bottom = 2.dp),
                horizontalArrangement =
                    Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                modeOptions.forEachIndexed { index, (mode, label, icon) ->
                    val selected = index == selectedIndex
                    ToggleButton(
                        checked = selected,
                        onCheckedChange = { isChecked ->
                            if (isChecked && appearanceMode != mode) {
                                appearanceMode = mode
                                onAppearanceModeSelected(mode)
                            }
                        },
                        shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                modeOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        colors = ToggleButtonDefaults.toggleButtonColors(
                            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.semantics { role = Role.RadioButton }
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Filled.Check else icon,
                            contentDescription = null,
                            modifier = Modifier.size(ToggleButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 0.dp)
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                selectableColorPresets.forEach { preset ->
                    ThemeColorSwatch(
                        preset = preset,
                        isSelected = preset == colorPreset,
                        isEnabled = preset != RethinkColorPreset.DYNAMIC || dynamicSupported,
                        dynamicColor = dynamicPreviewColor,
                        onClick = {
                            if (preset == colorPreset) return@ThemeColorSwatch
                            colorPreset = preset
                            onColorPresetSelected(preset)
                        }
                    )
                }
            }

            if (!dynamicSupported) {
                Text(
                    text = stringResource(id = R.string.settings_theme_color_dynamic_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ThemeColorSwatch(
    preset: RethinkColorPreset,
    isSelected: Boolean,
    isEnabled: Boolean,
    dynamicColor: Color,
    onClick: () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val pickerPreset = preset.forPicker()
    val baseColor = when (pickerPreset) {
        RethinkColorPreset.DYNAMIC -> dynamicColor
        else -> pickerPreset.seedColor ?: dynamicColor
    }
    val displayColor = if (isEnabled) baseColor else baseColor.copy(alpha = 0.42f)
    val tokenSize = 50.dp
    val glowSize = 56.dp
    val orbSize = 40.dp
    val overallAlpha = if (isEnabled) 1f else 0.52f
    val interactionSource = remember { MutableInteractionSource() }
    val swatchDescription = pickerPreset.toDisplayName()

    val cornerFraction by animateFloatAsState(
        targetValue = if (isSelected) 0.5f else 0.26f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else spring(dampingRatio = 0.7f, stiffness = 520f),
        label = "swatch_corner_${preset.id}"
    )
    val orbScale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 0.86f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else spring(dampingRatio = 0.56f, stiffness = 600f),
        label = "swatch_scale_${preset.id}"
    )
    val orbRotation by animateFloatAsState(
        targetValue = if (isSelected && !reducedMotion) 8f else 0f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else spring(dampingRatio = 0.66f, stiffness = 420f),
        label = "swatch_rotation_${preset.id}"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "swatch_glow_${preset.id}"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "swatch_icon_${preset.id}"
    )
    val orbShape = RoundedCornerShape(percent = (cornerFraction * 100).toInt())

    Box(
        modifier = Modifier
            .size(tokenSize)
            .graphicsLayer { alpha = overallAlpha }
            .clickable(
                enabled = isEnabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .semantics {
                role = Role.RadioButton
                selected = isSelected
                contentDescription = swatchDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(glowSize)
                .graphicsLayer { alpha = glowAlpha }
                .drawBehind {
                    drawCircle(
                        color = displayColor.copy(alpha = 0.44f),
                        radius = size.minDimension * 0.5f
                    )
                }
        )

        Box(
            modifier = Modifier
                .size(orbSize)
                .graphicsLayer {
                    scaleX = orbScale
                    scaleY = orbScale
                    rotationZ = orbRotation
                }
                .clip(orbShape)
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(
                        bounded = true,
                        radius = 18.dp,
                        color = Color.White.copy(alpha = 0.32f)
                    )
                )
                .background(displayColor),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                            start = Offset.Zero,
                            end = Offset(60f, 60f)
                        )
                    )
            )

            when {
                isSelected -> {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                alpha = iconAlpha
                                rotationZ = -orbRotation
                            },
                        tint = Color.White
                    )
                }
                pickerPreset == RethinkColorPreset.DYNAMIC -> {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White.copy(alpha = 0.88f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceMode.toDisplayName(): String {
    return when (this) {
        AppearanceMode.AUTO -> stringResource(id = R.string.settings_theme_dialog_themes_1)
        AppearanceMode.LIGHT -> stringResource(id = R.string.settings_theme_dialog_themes_2)
        AppearanceMode.DARK -> stringResource(id = R.string.settings_theme_dialog_themes_3)
    }
}

private fun RethinkColorPreset.forPicker(): RethinkColorPreset {
    return if (this == RethinkColorPreset.AUTO) RethinkColorPreset.DYNAMIC else this
}

@Composable
private fun RethinkColorPreset.toDisplayName(): String {
    return when (forPicker()) {
        RethinkColorPreset.DYNAMIC -> stringResource(id = R.string.settings_theme_color_dynamic)
        RethinkColorPreset.CORAL -> stringResource(id = R.string.settings_theme_color_coral)
        RethinkColorPreset.ROSE -> stringResource(id = R.string.settings_theme_color_rose)
        RethinkColorPreset.TEAL -> stringResource(id = R.string.settings_theme_color_teal)
        RethinkColorPreset.BLUE -> stringResource(id = R.string.settings_theme_color_blue)
        RethinkColorPreset.PURPLE -> stringResource(id = R.string.settings_theme_color_purple)
        RethinkColorPreset.ORANGE -> stringResource(id = R.string.settings_theme_color_orange)
        RethinkColorPreset.GREEN -> stringResource(id = R.string.settings_theme_color_green)
        RethinkColorPreset.AMBER -> stringResource(id = R.string.settings_theme_color_amber)
        RethinkColorPreset.CYAN -> stringResource(id = R.string.settings_theme_color_cyan)
        RethinkColorPreset.INDIGO -> stringResource(id = R.string.settings_theme_color_indigo)
        else -> stringResource(id = R.string.settings_theme_color_dynamic)
    }
}
