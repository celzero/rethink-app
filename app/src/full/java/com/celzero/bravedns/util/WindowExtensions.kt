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
package com.celzero.bravedns.util

import Logger
import Logger.LOG_TAG_UI
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.function.Consumer

/** Utility extension functions to configure Activity/Dialog/BottomSheet window appearance generically. */

// Extension function to handle frost effect for any activity
fun AppCompatActivity.handleFrostEffectIfNeeded(themeId: Int) {
    if (!Themes.isFrostTheme(themeId)) return

    if (isAtleastR()) {
        setTranslucent(true)
    }

    val windowBackgroundDrawable: Drawable? =
        AppCompatResources.getDrawable(this, R.drawable.window_background)
    window.setBackgroundDrawable(windowBackgroundDrawable)

    if (isAtleastS()) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        setupWindowBlurListener(windowBackgroundDrawable)
        val enabled = windowManager.isCrossWindowBlurEnabled
        Logger.v(LOG_TAG_UI, "Blur enabled by system? $enabled")
    } else {
        Logger.v(LOG_TAG_UI, "Blurs not supported, below Android S")
        updateWindowForBlurs(windowBackgroundDrawable, blursEnabled = false /* blursEnabled */)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun AppCompatActivity.setupWindowBlurListener(windowBackgroundDrawable: Drawable?) {
    val windowBlurEnabledListener =
        Consumer<Boolean> { blursEnabled ->
            updateWindowForBlurs(windowBackgroundDrawable, blursEnabled)
        }

    window.decorView.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                windowManager.addCrossWindowBlurEnabledListener(windowBlurEnabledListener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                windowManager.removeCrossWindowBlurEnabledListener(windowBlurEnabledListener)
            }
        }
    )
}

private const val BACKGROUND_BLUR_RADIUS = 80
private const val BLUR_BEHIND_RADIUS = 80
private const val DIM_AMOUNT_WITH_BLUR = 0.7f
private const val DIM_AMOUNT_NO_BLUR = 1f
private const val WINDOW_BACKGROUND_ALPHA_WITH_BLUR = 55
private const val WINDOW_BACKGROUND_ALPHA_NO_BLUR = 255

private fun AppCompatActivity.updateWindowForBlurs(
    windowBackgroundDrawable: Drawable?,
    blursEnabled: Boolean,
) {
    windowBackgroundDrawable?.alpha =
        if (blursEnabled) WINDOW_BACKGROUND_ALPHA_WITH_BLUR
        else WINDOW_BACKGROUND_ALPHA_NO_BLUR

    window.setDimAmount(if (blursEnabled) DIM_AMOUNT_WITH_BLUR else DIM_AMOUNT_NO_BLUR)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Set the window background blur and blur behind radii
        window.setBackgroundBlurRadius(BACKGROUND_BLUR_RADIUS)
        window.attributes.blurBehindRadius = BLUR_BEHIND_RADIUS
        window.attributes = window.attributes
    }
}

fun Dialog.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    // clear the dim behind flag so the underlying activity is not dimmed
    window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    // explicitly set dim amount to 0
    window?.setDimAmount(0f)
    // set transparent (or given) background
    window?.setBackgroundDrawable(color.toDrawable())
}

fun AppCompatDialog.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    (this as Dialog?)?.useTransparentNoDimBackground(color)
}

fun BottomSheetDialog.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    (this as Dialog?)?.useTransparentNoDimBackground(color)
}

/** Allow calling the helper directly on a DialogFragment/BottomSheetDialogFragment. */
fun DialogFragment?.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    this?.dialog?.useTransparentNoDimBackground(color)
}

fun BottomSheetDialogFragment?.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    this?.dialog?.useTransparentNoDimBackground(color)
}

private var frostWasEnabled = false

fun AppCompatActivity.disableFrostTemporarily() {
    frostWasEnabled = window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0

    if (frostWasEnabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(0)
            window.attributes.blurBehindRadius = 0
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setBackgroundDrawable(Color.BLACK.toDrawable())
    }
}

fun AppCompatActivity.restoreFrost(themeId: Int) {
    if (!frostWasEnabled) return

    handleFrostEffectIfNeeded(themeId)
}

fun Fragment.disableFrostTemporarily() {
    val activity = activity as? AppCompatActivity ?: return
    activity.disableFrostTemporarily()
}

fun Fragment.restoreFrost(themeId: Int) {
    val activity = activity as? AppCompatActivity ?: return
    activity.restoreFrost(themeId)
}
