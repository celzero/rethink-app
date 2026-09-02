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
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Lightweight donut (doughnut) chart for the Insights stats view. Renders the
 * pre-normalized slices prepared by the caller; the view performs no data
 * processing. Design: thin ring, small angular gaps between slices, optional
 * single-line center label (e.g. the section total). Zero/empty data renders
 * as a plain track ring instead of a broken chart.
 *
 * All arcs/text are rebuilt only in [setData]/[setCenterText]; onDraw replays
 * the prepared values without allocations.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** One ring slice; [fraction] is relative to the whole ring (0..1). */
    data class Slice(val fraction: Float, val color: Int)

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val centerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var slices: List<Slice> = emptyList()
    private var centerText: String? = null

    /** Ring thickness relative to the smaller view dimension (0f..1f). */
    fun setTrackColor(color: Int) {
        trackPaint.color = color
        invalidate()
    }

    fun setCenterTextColor(color: Int) {
        centerPaint.color = color
        invalidate()
    }

    fun setCenterTextSizeSp(sp: Float) {
        centerPaint.textSize = sp * resources.displayMetrics.scaledDensity
        invalidate()
    }

    /**
     * @param slices prepared slices in draw order; fractions should sum to
     * <= 1f (any remainder stays as visible track ring).
     */
    fun setData(slices: List<Slice>) {
        this.slices = slices
        invalidate()
    }

    fun setCenterText(text: String?) {
        centerText = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ringWidth = minOf(w, h) * RING_WIDTH_RATIO
        slicePaint.strokeWidth = ringWidth
        trackPaint.strokeWidth = ringWidth
        val r = (minOf(w, h) - ringWidth) / 2f
        val cx = w / 2f
        val cy = h / 2f
        rect.set(cx - r, cy - r, cx + r, cy + r)

        val total = slices.sumOf { it.fraction.toDouble() }.toFloat()
        if (slices.isEmpty() || total <= 0f) {
            // empty state: plain track ring
            canvas.drawArc(rect, 0f, MAX_SWEEP, false, trackPaint)
        } else {
            // track ring stays visible underneath; any fraction remainder
            // (total < 1) shows through as neutral track
            canvas.drawArc(rect, 0f, MAX_SWEEP, false, trackPaint)
            var start = START_ANGLE
            slices.forEach { slice ->
                val sweep = slice.fraction / total * MAX_SWEEP
                if (sweep > MIN_SWEEP) {
                    slicePaint.color = slice.color
                    canvas.drawArc(rect, start, sweep - SLICE_GAP_DEGREES, false, slicePaint)
                }
                start += sweep
            }
        }

        centerText?.let {
            val textPaint = centerPaint
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(it, cx, textY, textPaint)
        }
    }

    companion object {
        private const val RING_WIDTH_RATIO = 0.14f
        private const val START_ANGLE = -90f
        private const val MAX_SWEEP = 360f
        // small visual gap between adjacent slices
        private const val SLICE_GAP_DEGREES = 1.5f
        private const val MIN_SWEEP = SLICE_GAP_DEGREES + 0.5f
    }
}
