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
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Stateless renderer for a Google Photos-style animated border: draws a short
 * accent-colored highlight segment travelling around the perimeter of a
 * rounded rectangle.
 *
 * The drawable owns NO animation and performs NO invalidation - it is purely
 * a function of (bounds, cornerRadius, strokeWidth, accentColor, phase).
 * The hosting view (see AnimatedBorderCardView) owns the ValueAnimator,
 * drives [setPhase], and invalidates itself; this keeps the rendering path
 * free of drawable-callback machinery entirely.
 *
 * All objects (Paint, Path, PathMeasure, scratch Path) are created once and
 * reused, so there are no per-frame allocations while animating.
 */
class AnimatedBorderDrawable : Drawable() {

    companion object {
        // fraction of the perimeter occupied by the moving highlight
        private const val HIGHLIGHT_FRACTION = 0.18f
        // alpha of the soft glow pass under the main highlight
        private const val GLOW_ALPHA = 90
        // width multiplier of the glow pass relative to the highlight stroke
        private const val GLOW_WIDTH_FACTOR = 2.4f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val borderPath = Path()
    private val segmentPath = Path()
    private val pathMeasure = PathMeasure()
    private val boundsRect = RectF()

    private var perimeter = 0f
    private var cornerRadius = 0f
    private var strokeW = 4f
    private var accent = 0
    private var phase = 0f

    /** Sets the highlight position as a fraction of one full revolution. */
    fun setPhase(value: Float) {
        phase = value.coerceIn(0f, 1f)
    }

    fun setAccentColor(color: Int) {
        if (accent == color) return
        accent = color
    }

    fun setCornerRadius(radius: Float) {
        if (cornerRadius == radius) return
        cornerRadius = radius
        rebuildPath()
    }

    fun setStrokeWidth(width: Float) {
        if (strokeW == width) return
        strokeW = width
        rebuildPath()
    }

    /** True when the drawable has a valid path and color to render. */
    fun isReady(): Boolean = perimeter > 0f && accent != 0

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPath()
    }

    /**
     * Rebuilds the rounded-rect path (inset by half the stroke width so the
     * highlight stays fully inside the view bounds) and refreshes the
     * PathMeasure.
     */
    private fun rebuildPath() {
        borderPath.reset()
        if (bounds.isEmpty) {
            perimeter = 0f
            return
        }
        val inset = strokeW * 0.5f
        boundsRect.set(bounds)
        boundsRect.inset(inset, inset)
        val r = cornerRadius.coerceAtLeast(0f)
        borderPath.addRoundRect(boundsRect, r, r, Path.Direction.CW)
        pathMeasure.setPath(borderPath, false)
        perimeter = pathMeasure.length
    }

    override fun draw(canvas: Canvas) {
        if (!isReady()) return
        paint.color = accent
        val segLen = perimeter * HIGHLIGHT_FRACTION
        val start = phase * perimeter
        val end = start + segLen

        // soft glow pass: same accent color, wide and translucent
        paint.strokeWidth = strokeW * GLOW_WIDTH_FACTOR
        paint.alpha = GLOW_ALPHA
        drawSegment(canvas, start, end)

        // main highlight pass: full opacity accent stroke
        paint.strokeWidth = strokeW
        paint.alpha = 255
        drawSegment(canvas, start, end)
    }

    /** Draws the highlight segment, wrapping around the path end if needed. */
    private fun drawSegment(canvas: Canvas, start: Float, end: Float) {
        segmentPath.reset()
        if (end <= perimeter) {
            pathMeasure.getSegment(start, end, segmentPath, true)
        } else {
            pathMeasure.getSegment(start, perimeter, segmentPath, true)
            pathMeasure.getSegment(0f, end - perimeter, segmentPath, true)
        }
        canvas.drawPath(segmentPath, paint)
    }

    override fun setAlpha(alpha: Int) {
        // alpha is controlled by the passes in draw(); nothing to do
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
