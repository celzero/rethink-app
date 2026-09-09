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
import android.view.Gravity
import android.widget.FrameLayout
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

    /** Guards against installing the max-width layout-change listener more than once. */
    private var isMaxWidthHooked = false

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

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        applyMaxContentWidth()
    }

    /**
     * Caps the app content to [MAX_CONTENT_WIDTH_DP] and centers it horizontally on
     * expanded windows (foldables in the open state, tablets, split-screen).
     *
     * The width cap is applied by mutating the existing content view's LayoutParams inside
     * [android.R.id.content] (a FrameLayout), **not** by re-parenting it into a wrapper
     * view. Re-parenting breaks bind-mode `ViewBinding` delegates
     * (`viewBinding(Binding::bind)`): they resolve `android.R.id.content`'s child lazily
     * on first binding access (which may happen in `onResume` or later, i.e. after
     * [onPostCreate]) and hard-cast it to the layout's declared root type — an inserted
     * wrapper view at index 0 turns that cast into a `ClassCastException`
     * (cr: `MaxWidthFrameLayout cannot be cast to CoordinatorLayout` in
     * `WgConfigEditorActivity`). Keeping the content view's identity intact avoids the
     * crash for every activity without per-screen workarounds.
     *
     * Windows narrower than the cap (regular phones) keep `MATCH_PARENT` width; wider
     * windows cap the child to [MAX_CONTENT_WIDTH_DP] and center it, so the window
     * background keeps drawing edge-to-edge. Width is re-evaluated whenever the content
     * frame's width changes (first layout, fold/unfold, split-screen resize) via an
     * [android.view.View.OnLayoutChangeListener]; the mutation is a no-op when the target
     * width is unchanged.
     */
    private fun applyMaxContentWidth() {
        val content = findViewById<FrameLayout>(android.R.id.content) ?: return
        if (!isMaxWidthHooked) {
            isMaxWidthHooked = true
            content.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                capContentChildWidth(view as FrameLayout, view.width)
            }
        }
        // May be a no-op pre-layout (width 0); the layout-change listener covers first layout.
        capContentChildWidth(content, content.width)
    }

    private fun capContentChildWidth(content: FrameLayout, contentWidth: Int) {
        if (contentWidth <= 0) return
        val child = content.getChildAt(0) ?: return
        val lp = child.layoutParams as? FrameLayout.LayoutParams ?: return
        val capPx = (MAX_CONTENT_WIDTH_DP * resources.displayMetrics.density).toInt()
        val target = if (contentWidth <= capPx) {
            FrameLayout.LayoutParams.MATCH_PARENT
        } else {
            capPx
        }
        if (lp.width == target && lp.gravity == Gravity.CENTER_HORIZONTAL) return
        lp.width = target
        lp.gravity = Gravity.CENTER_HORIZONTAL
        child.layoutParams = lp
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

    companion object {
        /**
         * Maximum content width in dp. Keeps every screen rendered at phone proportions
         * inside the centered column, so layouts with phone-tuned fixed sizes (square
         * card grids, fixed margins) fit the screen exactly as they do on non-foldable
         * phones.
         */
        private const val MAX_CONTENT_WIDTH_DP = 600
    }
}
