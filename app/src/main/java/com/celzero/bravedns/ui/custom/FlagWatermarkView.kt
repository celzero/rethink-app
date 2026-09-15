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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.celzero.bravedns.util.UIUtils
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a flag (country-flag emoji text or a drawable) as a soft, upright
 * "wash" anchored to this view's bottom-end corner. The flag is drawn large
 * but at low opacity, with a radial alpha mask that dissolves it into the
 * hosting card's surface; card content (text, icons) draws on top, so the
 * flag reads as ambient colour from that country rather than as an icon.
 *
 * The view stretches to the full height of the hosting card (0dp + top/bottom
 * constraints in the layout) so the wash always spans the card. The card should
 * clip this view with its rounded outline (clipToOutline).
 *
 * The view is reusable across cards: set a flag via [setFlagText] or
 * [setFlagDrawable]; with no flag set it collapses to GONE.
 */
class FlagWatermarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /**
         * Preferred side length of the square the flag content is rendered
         * into. Capped at draw time to fit shorter cards.
         */
        const val CONTENT_SIZE_DP = 20f

        /**
         * Distance the flag used to be pushed past the card's top/end edges;
         * retained for host compatibility (no longer applied by this view).
         */
        const val CORNER_BLEED_DP = 18f

    /**
     * Flag opacity at its most visible point (the flag's centre). Tuned so
     * the country reads at a glance while overlapped card text stays legible.
     */
    private const val PEAK_ALPHA = 0.30f

    /**
     * Distance from the end edge to the flag's near edge; small, so the wash
     * hugs the card's bottom-end corner.
     */
    private const val END_EDGE_INSET_DP = 4f

    /**
     * Distance the flag's centre sits above the bottom edge; small, so the
     * wash visibly anchors to the bottom-end corner of the card.
     */
    private const val BOTTOM_EDGE_INSET_DP = 2f

    /** Radial mask radius as a multiple of the content size. */
    private const val MASK_RADIUS_FACTOR = 0.85f

    /**
     * Fraction of the flag pushed past the end edge in spread mode, so only
     * the remainder (1 - fraction) stays visible inside the banner.
     */
    private const val SPREAD_END_BLEED_FRACTION = 0.4f
    }

    private var flagText: String? = null
    private var flagDrawable: Drawable? = null

    /**
     * When true the wash is enlarged and anchored to the bottom-end corner,
     * bleeding past the end edge so only part of the flag shows inside the
     * view (banner usage); when false it stays small in the same corner
     * (card usage).
     */
    private var spread = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var maskShader: Shader? = null

    private val density = resources.displayMetrics.density
    private val preferredContentSize = CONTENT_SIZE_DP * density

    /** Sets the flag from a country-flag emoji string (e.g. "🇮🇳"). */
    fun setFlagText(text: String?) {
        val next = text?.takeIf { it.isNotBlank() }
        if (next == flagText && flagDrawable == null) return
        flagText = next
        flagDrawable = null
        refresh()
    }

    /**
     * Sets the flag from a drawable (e.g. a fallback glyph for locations with
     * no country flag), optionally tinted via a theme attribute.
     */
    fun setFlagDrawable(drawable: Drawable?, tintAttr: Int? = null) {
        if (drawable === flagDrawable && flagText == null) return
        flagDrawable = drawable
        if (drawable != null) {
            if (tintAttr != null) {
                drawable.setTint(UIUtils.fetchColor(context, tintAttr))
            } else {
                drawable.setTintList(null)
            }
        }
        flagText = null
        refresh()
    }

    /** Removes the flag; the view collapses to GONE. */
    fun clear() {
        if (flagText == null && flagDrawable == null) return
        flagText = null
        flagDrawable = null
        refresh()
    }

    /**
     * Switches between the small corner wash (cards) and the enlarged,
     * end-anchored wash that bleeds off the banner's edge (banners). The
     * cached mask is rebuilt since its geometry depends on the mode.
     */
    fun setSpread(enabled: Boolean) {
        if (spread == enabled) return
        spread = enabled
        maskShader = null
        invalidate()
    }

    private fun refresh() {
        visibility = if (flagText != null || flagDrawable != null) VISIBLE else GONE
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maskShader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mask = maskShader ?: buildMask(w, h).also { maskShader = it }
        // Draw the flag into an offscreen layer, then punch the radial fade
        // into it (DST_IN) so it blends away toward the card's content.
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        drawFlag(canvas)
        maskPaint.shader = mask
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restoreToCount(layer)
    }

    /** X coordinate of the card's end edge: right in LTR, left in RTL. */
    private fun cornerX(w: Float): Float =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) 0f else w

    /** Direction (unit) pointing from the end edge into the view. */
    private fun inwardDir(w: Float): Float =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f else -1f

    /**
     * Centre of the flag wash. In card mode it is inset from the end edge and
     * anchored near the bottom edge, small enough to stay clear of the title
     * row and action chips. In spread mode the flag is larger and pushed past
     * the end edge ([SPREAD_END_BLEED_FRACTION] bleeds off-view), so the wash
     * fills the banner's end corner while the remaining part stays visible.
     */
    private fun flagCenter(w: Float, h: Float): Pair<Float, Float> {
        val anchor = cornerX(w)
        val dir = inwardDir(w)
        val size = flagSize(w, h)
        if (spread) {
            val bleed = size * SPREAD_END_BLEED_FRACTION
            val cx = anchor + dir * (size / 2f - bleed)
            val cy = h - BOTTOM_EDGE_INSET_DP * density - size / 2f
            return cx to cy
        }
        val cx = anchor + dir * (END_EDGE_INSET_DP * density + size / 2f)
        val cy = h - BOTTOM_EDGE_INSET_DP * density - size / 2f
        return cx to cy
    }

    /** Content size, capped so it fits inside shorter cards. */
    private fun flagSize(w: Float, h: Float): Float =
        if (spread) min(w, h) * 0.95f else min(preferredContentSize, h * 0.92f)

    private fun buildMask(w: Float, h: Float): Shader {
        val (cx, cy) = flagCenter(w, h)
        val size = flagSize(w, h)
        // Even wash: full tint at the flag's centre, holding most of that
        // tint across the glyph before dissolving toward the view edges. In
        // spread mode the radius extends past the glyph so the dissolve
        // reaches the banner edges.
        val radius = if (spread) size * 1.1f else size * MASK_RADIUS_FACTOR
        val stops = floatArrayOf(0f, 0.5f, 1f)
        val colors = intArrayOf(
            alphaColor(PEAK_ALPHA),
            alphaColor(PEAK_ALPHA * 0.72f),
            alphaColor(0f)
        )
        return RadialGradient(
            cx, cy, radius,
            colors, stops, Shader.TileMode.CLAMP
        )
    }

    private fun alphaColor(a: Float): Int = Color.argb((255 * a).roundToInt(), 255, 255, 255)

    private fun drawFlag(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = flagSize(w, h)
        val (cx, cy) = flagCenter(w, h)

        // Content square: anchored at the end edge in card mode, positioned
        // at the (partially off-view) wash centre in spread mode; the
        // translate moves its centre onto the wash centre so the glyph stays
        // aligned with the radial mask.
        val rectLeft: Float
        val rectRight: Float
        if (spread) {
            rectLeft = cx - size / 2f
            rectRight = cx + size / 2f
        } else {
            val anchor = cornerX(w)
            val dir = inwardDir(w)
            rectLeft = min(anchor, anchor + dir * size)
            rectRight = if (rectLeft == anchor) anchor + size else anchor
        }
        val centreX = (rectLeft + rectRight) / 2f
        val centreY = size / 2f

        canvas.save()
        canvas.translate(cx - centreX, cy - centreY)

        val d = flagDrawable
        if (d != null) {
            val iw = d.intrinsicWidth
            val ih = d.intrinsicHeight
            if (iw > 0 && ih > 0) {
                // Preserve the drawable's aspect ratio inside the content square.
                val scale = min(size / iw, size / ih)
                val dw = iw * scale
                val dh = ih * scale
                d.setBounds(
                    (centreX - dw / 2f).roundToInt(),
                    (centreY - dh / 2f).roundToInt(),
                    (centreX + dw / 2f).roundToInt(),
                    (centreY + dh / 2f).roundToInt()
                )
            } else {
                d.setBounds(rectLeft.roundToInt(), 0, rectRight.roundToInt(), size.roundToInt())
            }
            d.draw(canvas)
            canvas.restore()
            return
        }

        val text = flagText ?: run {
            canvas.restore()
            return
        }
        // Emoji flags keep their intrinsic glyph ratio; centre them in the
        // content square via font metrics so they are not clipped or stretched.
        textPaint.textSize = size * 0.95f
        val fm = textPaint.fontMetrics
        val baseline = (size - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(text, centreX, baseline, textPaint)
        canvas.restore()
    }
}
