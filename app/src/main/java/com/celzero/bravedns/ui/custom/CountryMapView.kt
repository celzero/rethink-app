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
package com.celzero.bravedns.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.celzero.bravedns.util.WorldMapPaths

/**
 * Offline, non-interactive world-map visualization for "Most Contacted
 * Countries". Draws the simplified country polygons from [WorldMapPaths] and
 * highlights countries from the actual stats with a single accent color at
 * varying alpha (intensity = count / maxCount); countries without stats are
 * painted in a neutral base color. Country codes that carry stats but have no
 * polygon (or an unresolvable/"unknown" code) are simply not drawn — the
 * ranked list next to the map remains the source for exact numbers.
 *
 * Polygons are parsed from the compact string encoding once per data change
 * and cached as [Path] objects; onDraw replays the cached paths only.
 */
class CountryMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
        alpha = STROKE_ALPHA
    }

    // countryCode -> normalized intensity 0f..1f
    private var intensities: Map<String, Float> = emptyMap()

    // parsed geometry cache; rebuilt only on data change / size change
    private var paths: List<Pair<Path, Float>> = emptyList()
    private var drawMatrixScale: Float = 0f

    /** Sets theme-resolved colors: base fill, accent highlight, hairline stroke. */
    fun setColors(baseColor: Int, accentColor: Int, strokeColor: Int) {
        basePaint.color = baseColor
        highlightPaint.color = accentColor
        strokePaint.color = strokeColor
        invalidate()
    }

    /**
     * @param stats map of ISO alpha-2 code -> connection count. Codes without
     * polygons ("XX"-style unknowns, unmapped territories) are ignored here.
     */
    fun setCountryCounts(stats: Map<String, Int>) {
        val max = stats.values.maxOrNull() ?: 0
        intensities = if (max <= 0) {
            emptyMap()
        } else {
            stats.mapValues { (it.value.toFloat() / max).coerceIn(0f, 1f) }
        }
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        invalidate()
    }

    /**
     * The map has an intrinsic aspect ratio ([WorldMapPaths.MAP_WIDTH] x
     * [WorldMapPaths.MAP_HEIGHT]); derive the unconstrained dimension from the
     * constrained one so the view never letterboxes (which previously left the
     * map looking squashed into a thin, off-center strip).
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val wMode = MeasureSpec.getMode(widthSpec)
        val hMode = MeasureSpec.getMode(heightSpec)
        val aspect = WorldMapPaths.MAP_WIDTH.toFloat() / WorldMapPaths.MAP_HEIGHT
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        val h = getDefaultSize(suggestedMinimumHeight, heightSpec)
        val vPadding = paddingTop + paddingBottom
        val hPadding = paddingLeft + paddingRight
        val measured: Pair<Int, Int> = when {
            hMode != MeasureSpec.EXACTLY && wMode == MeasureSpec.EXACTLY -> {
                // width drives: height = contentWidth / aspect
                val content = (w - hPadding).coerceAtLeast(0)
                var derived = (content / aspect).toInt() + vPadding
                if (hMode == MeasureSpec.AT_MOST) {
                    derived = derived.coerceAtMost(h)
                }
                Pair(w, derived)
            }
            wMode != MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY -> {
                // height drives: width = contentHeight * aspect
                val content = (h - vPadding).coerceAtLeast(0)
                var derived = (content * aspect).toInt() + hPadding
                if (wMode == MeasureSpec.AT_MOST) {
                    derived = derived.coerceAtMost(w)
                }
                Pair(derived, h)
            }
            else -> Pair(w, h)
        }
        setMeasuredDimension(measured.first, measured.second)
    }

    private fun rebuild() {
        if (width == 0 || height == 0) {
            paths = emptyList()
            return
        }
        // fit MAP_WIDTH x MAP_HEIGHT into the view while preserving aspect
        val scale = minOf(
            width.toFloat() / WorldMapPaths.MAP_WIDTH,
            height.toFloat() / WorldMapPaths.MAP_HEIGHT
        )
        drawMatrixScale = scale
        val parsed = ArrayList<Pair<Path, Float>>(WorldMapPaths.PATHS.size)
        for ((code, encoded) in WorldMapPaths.PATHS) {
            val path = parsePath(encoded, scale) ?: continue
            val intensity = intensities[code] ?: -1f
            parsed.add(Pair(path, intensity))
        }
        paths = parsed
    }

    private fun parsePath(encoded: String, scale: Float): Path? {
        val path = Path()
        var hadRing = false
        for (ring in encoded.split(RING_SEP)) {
            val points = ring.trim().split(POINT_SEP)
            if (points.size < MIN_RING_POINTS) continue
            var first = true
            for (pt in points) {
                val xy = pt.split(COORD_SEP)
                if (xy.size != 2) continue
                // encoded values are quantized at 2x for sub-unit precision
                val x = (xy[0].toFloat() / WorldMapPaths.QUANT_SCALE) * scale
                val y = (xy[1].toFloat() / WorldMapPaths.QUANT_SCALE) * scale
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }
            path.close()
            hadRing = true
        }
        return if (hadRing) path else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paths.isEmpty()) return
        // center the (aspect-fitted) map in both axes; with an aspect-aware
        // onMeasure there is no leftover space, but a fixed-size parent can
        // still leave gutters, so center explicitly rather than pin to (0, 0)
        val mapW = WorldMapPaths.MAP_WIDTH * drawMatrixScale
        val mapH = WorldMapPaths.MAP_HEIGHT * drawMatrixScale
        val dx = (width - mapW) / 2f
        val dy = (height - mapH) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        for ((path, intensity) in paths) {
            if (intensity < 0f) {
                canvas.drawPath(path, basePaint)
            } else {
                highlightPaint.alpha =
                    (HIGHLIGHT_MIN_ALPHA + intensity * (MAX_ALPHA - HIGHLIGHT_MIN_ALPHA)).toInt()
                canvas.drawPath(path, highlightPaint)
            }
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }

    companion object {
        private const val RING_SEP = "|"
        private const val POINT_SEP = " "
        private const val COORD_SEP = ","
        private const val MIN_RING_POINTS = 4
        private const val HIGHLIGHT_MIN_ALPHA = 60
        private const val MAX_ALPHA = 255
        private const val STROKE_ALPHA = 50
        private const val STROKE_WIDTH_PX = 1f
    }
}
