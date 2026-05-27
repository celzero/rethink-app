/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import org.koin.android.ext.android.inject

/**
 * Base activity for all UI screens in the app.
 *
 * Responsibilities:
 * - **Status-bar icon appearance (all builds)**: On Android 10+ (API 29+), edge-to-edge
 *   rendering leaves the status bar fully transparent.  [applyStatusBarAppearance] sets
 *   [WindowInsetsControllerCompat.isAppearanceLightStatusBars] so that icon colors
 *   (clock, signal, Wi-Fi, …) are always readable against the surface behind them,
 *   regardless of the user's chosen theme.
 * - **Alpha accent overlay (alpha builds only)**: Overlays a purple accent color so
 *   testers on pre-release builds can immediately tell they are not on a production build.
 *
 * Usage:
 * All activities should extend [BaseActivity] instead of [AppCompatActivity] directly.
 * Each subclass must still apply its user-selected theme before calling super.onCreate()
 * so the ordering is maintained: user-theme → alpha overlay → view inflation → status-bar fix.
 *
 * Example:
 * ```
 * class MyActivity : BaseActivity(R.layout.activity_my) {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
 *         super.onCreate(savedInstanceState)
 *         ...
 *     }
 * }
 * ```
 */
abstract class BaseActivity(@LayoutRes contentLayoutId: Int = 0) :
    AppCompatActivity(contentLayoutId) {

    private val persistentState: PersistentState by inject()

    /**
     * Returns true when the device is currently in dark (night) mode.
     * Defined as a Context extension so callers read naturally without needing a receiver.
     */
    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES

    /**
     * Returns true when the user's resolved theme is a light (day) theme.
     * Single source of truth shared by [applyStatusBarAppearance] and [applyAlphaThemeOverlay].
     */
    private fun isLightThemeActive(): Boolean {
        val resolvedTheme = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        return resolvedTheme == R.style.AppThemeWhite || resolvedTheme == R.style.AppThemeWhitePlus
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Alpha overlay must be applied after the subclass sets the user theme and before
        // AppCompatActivity.onCreate() inflates the views.
        if (Utilities.isAlphaBuild()) {
            applyAlphaThemeOverlay()
        }
        super.onCreate(savedInstanceState)
        // Android 15 (API 35) enforces edge-to-edge — the system no longer draws a scrim
        // behind the status bar, so the app must declare whether icons should be dark or
        // light; without this the icons default to white and are invisible on light surfaces.
        applyStatusBarAppearance()
    }

    /**
     * Configures status-bar icon colours to match the active theme. Applies to **all builds**.
     *
     * Light themes (white/light surface behind the status bar) → dark icons
     * Dark/black themes (dark surface behind the status bar)   → light icons
     *
     * Must be called after [AppCompatActivity.onCreate] so the window is fully initialized.
     */
    private fun applyStatusBarAppearance() {
        if (!isAtleastQ()) return
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            isLightThemeActive()
    }

    /**
     * Overlays a purple accent color on the user-selected theme. Applies to **alpha builds only**.
     *
     * Purple 200 (#CE93D8) for dark/black themes
     * Purple 700 (#7B1FA2) for light themes
     *
     * Must be called after the base theme is applied and before [AppCompatActivity.onCreate].
     */
    private fun applyAlphaThemeOverlay() {
        val overlayRes = if (isLightThemeActive()) {
            R.style.ThemeOverlay_App_AlphaLight
        } else {
            R.style.ThemeOverlay_App_AlphaDark
        }
        theme.applyStyle(overlayRes, true)
    }
}
