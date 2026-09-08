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
package com.celzero.bravedns.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * Pill-shaped border whose color is a sweep gradient that rotates, so a
 * highlight travels around the outline. Only the stroke is painted (no fill),
 * which makes it suitable as a subtle "animated border" around a button.
 *
 * Rotation is applied by rebuilding the [SweepGradient] from a pre-computed
 * base color table instead of mutating the shader's local matrix — a fresh
 * shader is snapshotted correctly by hardware-accelerated rendering on every
 * draw, whereas in-place matrix mutation is not reliably reflected once the
 * view's display list has been recorded. Animate the [rotation] property
 * (0f..360f) with a ValueAnimator to move the highlight around the border.
 */
class RotatingBorderDrawable : Drawable() {

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
    private val rect = RectF()

    private var accentColor = 0
    private var strokeWidthPx = 0f
    private var rotationDegrees = 0f

    // base sweep pattern (no rotation): transparent at 0deg, accent at 180deg,
    // sampled every ANGLE_STEP_DEG; index i corresponds to angle i*step
    private var baseColors: IntArray? = null
    private var transparentColor = 0

    /**
     * Current gradient rotation in degrees. Animating this property (e.g. via
     * a ValueAnimator update listener) moves the highlight around the border.
     */
    var rotation: Float
        get() = rotationDegrees
        set(value) {
            val wrapped = ((value % 360f) + 360f) % 360f
            if (wrapped == rotationDegrees) return
            rotationDegrees = wrapped
            refreshShader()
            invalidateSelf()
        }

    /**
     * @param accent color of the highlight; edges of the sweep fade to transparent
     * @param widthPx stroke thickness in px
     */
    fun configure(accent: Int, widthPx: Float) {
        accentColor = accent
        strokeWidthPx = widthPx
        paint.strokeWidth = widthPx
        transparentColor = ColorUtils.setAlphaComponent(accent, 0)
        buildBaseColors()
        refreshShader()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        refreshShader()
        invalidateSelf()
    }

    /**
     * Samples the base (unrotated) sweep pattern into [baseColors]: a triangle
     * wave from transparent (0deg) up to the accent color (180deg) and back.
     */
    private fun buildBaseColors() {
        if (accentColor == 0) {
            baseColors = null
            return
        }
        val table = IntArray(COLOR_TABLE_SIZE + 1)
        for (i in 0..COLOR_TABLE_SIZE) {
            val frac = i * 2f / COLOR_TABLE_SIZE // 0f..2f over the full turn
            val blend = if (frac <= 1f) frac else 2f - frac
            table[i] = ColorUtils.blendARGB(transparentColor, accentColor, blend)
        }
        baseColors = table
    }

    /**
     * Rebuilds the paint's sweep gradient so that the base pattern appears
     * rotated by [rotationDegrees]. The gradient is recreated (not mutated) so
     * hardware-accelerated rendering always snapshots the current rotation.
     */
    private fun refreshShader() {
        val b = bounds
        if (b.isEmpty) return
        // inset by half the stroke so the ring stays within the view bounds
        rect.set(b)
        rect.inset(strokeWidthPx / 2f, strokeWidthPx / 2f)
        if (rect.isEmpty) return

        val table = baseColors ?: return
        // shift the base table by the rotation, expressed in table steps
        val shift =
            Math.round(rotationDegrees / 360f * COLOR_TABLE_SIZE).toInt() % COLOR_TABLE_SIZE
        val colors = IntArray(COLOR_TABLE_SIZE) { i -> table[(i + shift) % COLOR_TABLE_SIZE] }
        val positions = FloatArray(COLOR_TABLE_SIZE) { i -> i / (COLOR_TABLE_SIZE - 1f) }
        paint.shader = SweepGradient(rect.centerX(), rect.centerY(), colors, positions)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        if (paint.shader == null) {
            refreshShader()
            if (paint.shader == null) return
        }
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    companion object {
        // resolution of the sweep pattern; 5deg steps are visually smooth at
        // the animation speed used (one full turn every ~3s)
        private const val COLOR_TABLE_SIZE = 72
    }
}
