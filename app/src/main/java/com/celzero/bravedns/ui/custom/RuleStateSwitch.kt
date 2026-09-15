/*
 * Copyright 2020 RethinkDNS and its authors
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
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils.fetchColor

/**
 * Pill-shaped rule indicator shown at the trailing edge of connection-tracker
 * rule rows in place of the plain navigation arrow. The thumb position and its
 * inner glyph encode the verdict: thumb on the right with a tick for a trusted
 * (positive) rule, thumb on the left with a cross for a blocking rule, and a
 * dim, glyph-less thumb when no rule exists.
 */
class RuleStateSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class SwitchState {
        /** No rule applies; rendered dim with no glyph. */
        NEUTRAL,
        /** Blocking rule; rendered in the negative accent with a cross. */
        OFF,
        /** Trust/allow rule; rendered in the positive accent with a tick. */
        ON
    }

    var switchState: SwitchState = SwitchState.NEUTRAL
        private set

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val trackRect = RectF()
    private val glyphPath = Path()

    // theme-dependent colors; resolved once per attach since the bottom sheet
    // re-inflates this view on every theme change
    private var positiveColor = 0
    private var negativeColor = 0
    private var neutralColor = 0
    private var thumbColor = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshThemeColors()
    }

    private fun refreshThemeColors() {
        positiveColor = fetchColor(context, R.attr.chipTextPositive)
        negativeColor = fetchColor(context, R.attr.accentBad)
        neutralColor = fetchColor(context, R.attr.border)
        thumbColor = fetchColor(context, R.attr.invertedPrimaryTextColor)
    }

    fun setRuleState(state: SwitchState) {
        if (switchState == state) return
        switchState = state
        contentDescription = contentDescriptionFor(state)
        invalidate()
    }

    private fun contentDescriptionFor(state: SwitchState): String {
        val res = resources
        return when (state) {
            SwitchState.NEUTRAL -> res.getString(R.string.ci_no_rule)
            SwitchState.OFF -> res.getString(R.string.ci_block)
            SwitchState.ON -> res.getString(R.string.ci_trust_rule)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / HALF

        val accentColor =
            when (switchState) {
                SwitchState.ON -> positiveColor
                SwitchState.OFF -> negativeColor
                SwitchState.NEUTRAL -> neutralColor
            }

        trackPaint.color = accentColor
        trackRect.set(INSET_ZERO, INSET_ZERO, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val inset = h * THUMB_INSET_FRACTION
        val thumbRadius = radius - inset
        val thumbCx = if (switchState == SwitchState.ON) w - radius else radius
        val thumbCy = h / HALF
        thumbPaint.color = thumbColor
        canvas.drawCircle(thumbCx, thumbCy, thumbRadius, thumbPaint)

        when (switchState) {
            SwitchState.OFF -> drawCross(canvas, thumbCx, thumbCy, thumbRadius, negativeColor)
            SwitchState.ON -> drawTick(canvas, thumbCx, thumbCy, thumbRadius, positiveColor)
            SwitchState.NEUTRAL -> Unit // dim thumb only; no verdict glyph
        }
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        glyphPaint.color = color
        glyphPaint.strokeWidth = r * GLYPH_STROKE_FRACTION
        val arm = r * CROSS_ARM_FRACTION
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, glyphPaint)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, glyphPaint)
    }

    private fun drawTick(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        glyphPaint.color = color
        glyphPaint.strokeWidth = r * GLYPH_STROKE_FRACTION
        glyphPath.reset()
        glyphPath.moveTo(cx - r * TICK_START_X, cy + r * TICK_START_Y)
        glyphPath.lineTo(cx - r * TICK_MID_X, cy + r * TICK_MID_Y)
        glyphPath.lineTo(cx + r * TICK_END_X, cy - r * TICK_END_Y)
        canvas.drawPath(glyphPath, glyphPaint)
    }

    private companion object {
        const val HALF = 2f
        const val INSET_ZERO = 0f
        const val THUMB_INSET_FRACTION = 0.14f
        const val GLYPH_STROKE_FRACTION = 0.32f
        const val CROSS_ARM_FRACTION = 0.4f
        const val TICK_START_X = 0.48f
        const val TICK_START_Y = 0.08f
        const val TICK_MID_X = 0.12f
        const val TICK_MID_Y = 0.42f
        const val TICK_END_X = 0.5f
        const val TICK_END_Y = 0.34f
    }
}
