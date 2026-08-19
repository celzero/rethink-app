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
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils
import kotlin.math.cos
import kotlin.math.sin

class MoneyBackBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var daysText: String = "7"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sealPath = Path()
    private val topArcPath = Path()
    private val bottomArcPath = Path()

    private var badgeColor: Int = Color.BLUE
    private var textColor: Int = Color.WHITE

    init {
        badgeColor = UIUtils.fetchColor(context, R.attr.accentGood)
        alpha = 0.85f
        textColor = Color.WHITE
    }

    fun setDays(days: Int) {
        this.daysText = days.toString()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = cx.coerceAtMost(cy) * 0.95f

        // 1. Draw Seal (Sunburst)
        drawSeal(canvas, cx, cy, radius)

        // 2. Draw Curved Text
        drawCurvedText(canvas, cx, cy, radius)

        // 3. Draw Center Text
        drawCenterText(canvas, cx, cy, radius)
    }

    private fun drawSeal(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.color = badgeColor
        paint.style = Paint.Style.FILL
        
        val points = 24
        val innerRadius = radius * 0.88f
        sealPath.reset()
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) radius else innerRadius
            val angle = Math.PI * i / points - Math.PI / 2
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) sealPath.moveTo(x, y) else sealPath.lineTo(x, y)
        }
        sealPath.close()
        canvas.drawPath(sealPath, paint)
    }

    private fun drawCurvedText(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.color = textColor
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        
        val textRadius = radius * 0.70f
        
        // MONEY-BACK
        paint.textSize = radius * 0.16f
        topArcPath.reset()
        topArcPath.addArc(cx - textRadius, cy - textRadius, cx + textRadius, cy + textRadius, 185f, 170f)
        canvas.drawTextOnPath(context.getString(R.string.money_back), topArcPath, 0f, 0f, paint)

        // GUARANTEE
        // To draw bottom text upright, we use a sweep from 175 back to 5
        bottomArcPath.reset()
        bottomArcPath.addArc(cx - textRadius, cy - textRadius, cx + textRadius, cy + textRadius, 175f, -170f)
        // hOffset 0, vOffset 0. Text is drawn on the path. 
        // We use a small vOffset to push it a bit further from the center if needed.
        canvas.drawTextOnPath(context.getString(R.string.money_back_guarantee), bottomArcPath, 0f, radius * 0.12f, paint)
        
        // Side Dots
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - radius * 0.70f, cy, radius * 0.04f, paint)
        canvas.drawCircle(cx + radius * 0.70f, cy, radius * 0.04f, paint)
    }

    private fun drawCenterText(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.color = textColor
        paint.textAlign = Paint.Align.CENTER
        
        // Number
        paint.textSize = radius * 0.65f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(daysText, cx, cy + radius * 0.12f, paint)

        // DAYS
        paint.textSize = radius * 0.22f
        canvas.drawText(context.getString(R.string.days).uppercase(), cx, cy + radius * 0.40f, paint)
    }
}
