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
 * "wash" anchored to the bottom-end corner, partially bleeding off the edge.
 * The flag is drawn large but at low opacity, with a radial alpha mask that
 * dissolves it into the hosting card's surface; card content (text, icons)
 * draws on top, so the flag reads as ambient colour from that country rather
 * than as an icon.
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
         * Base side length of the square the flag content is rendered into
         * before [CARD_CONTENT_SCALE]; capped at draw time to fit the card.
         */
        const val CONTENT_SIZE_DP = 20f

        /** Card-mode wash size as a multiple of [CONTENT_SIZE_DP]. */
        private const val CARD_CONTENT_SCALE = 4f

        /** Fraction of the card-mode wash bleeding past the end edge (cut off). */
        private const val CARD_END_BLEED_FRACTION = 0.3f

        /** Tilt applied to the card-mode glyph for a slanted bottom-end cut. */
        private const val CARD_TILT_DEGREES = -35f

        /**
         * Distance the flag used to be pushed past the card's top/end edges;
         * retained for host compatibility (no longer applied by this view).
         */
        const val CORNER_BLEED_DP = 18f

    /**
     * Flag opacity at the wash's most visible point (its centre) in banner
     * (spread) mode; the enlarged glyph reads at this low opacity.
     */
    private const val SPREAD_PEAK_ALPHA = 0.30f

    /** Flag opacity at the wash centre in card mode. */
    private const val CARD_PEAK_ALPHA = 0.4f

    /**
     * Text-size multiplier applied to the flag glyph in spread mode. Emoji
     * flag glyphs occupy only part of their em box, so scaling the em box up
     * makes the flag's visible body span the banner's full height.
     */
    private const val SPREAD_TEXT_SCALE = 1.35f

    /**
     * Card-mode mask radius as a multiple of the content size. Large enough
     * that the tint holds across the glyph and only dissolves past it, so the
     * wash reads at full strength where the card edge cuts it off.
     */
    private const val CARD_MASK_RADIUS_FACTOR = 5f

    /**
     * Fraction of the flag pushed past the end edge in spread mode, so only
     * the remainder (1 - fraction) stays visible inside the banner.
     */
    private const val SPREAD_END_BLEED_FRACTION = 0.5f
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

    /** Per-flag peak-alpha override; `null` uses the mode default. */
    private var peakAlphaOverride: Float? = null

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
        if (next == flagText && flagDrawable == null && peakAlphaOverride == null) return
        flagText = next
        flagDrawable = null
        if (peakAlphaOverride != null) {
            peakAlphaOverride = null
            maskShader = null
        }
        refresh()
    }

    /**
     * Sets the flag from a drawable, optionally tinted via a theme attribute
     * and with an optional per-flag peak alpha.
     */
    fun setFlagDrawable(drawable: Drawable?, tintAttr: Int? = null, peakAlpha: Float? = null) {
        if (drawable === flagDrawable && flagText == null && peakAlphaOverride == peakAlpha) return
        flagDrawable = drawable
        if (drawable != null) {
            if (tintAttr != null) {
                drawable.setTint(UIUtils.fetchColor(context, tintAttr))
            } else {
                drawable.setTintList(null)
            }
        }
        flagText = null
        if (peakAlphaOverride != peakAlpha) {
            peakAlphaOverride = peakAlpha
            maskShader = null
        }
        refresh()
    }

    /** Removes the flag; the view collapses to GONE. */
    fun clear() {
        if (flagText == null && flagDrawable == null) return
        flagText = null
        flagDrawable = null
        if (peakAlphaOverride != null) {
            peakAlphaOverride = null
            maskShader = null
        }
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
     * Centre of the flag wash. In card mode the wash is anchored to the
     * bottom-end corner with [CARD_END_BLEED_FRACTION] bleeding past the end
     * edge. In spread mode the wash spans the banner's full height and is
     * pushed past the end edge ([SPREAD_END_BLEED_FRACTION] bleeds off-view),
     * so the flag fills the banner from top to bottom.
     */
    private fun flagCenter(w: Float, h: Float): Pair<Float, Float> {
        val anchor = cornerX(w)
        val dir = inwardDir(w)
        val size = flagSize(w, h)
        if (spread) {
            val bleed = size * SPREAD_END_BLEED_FRACTION
            val cx = anchor + dir * (size / 2f - bleed)
            // Centre vertically so the wash reaches the banner's top and
            // bottom edges instead of hugging one of them.
            return cx to h / 2f
        }
        val bleed = size * CARD_END_BLEED_FRACTION
        val cx = anchor + dir * (size / 2f - bleed)
        return cx to h - size / 2f
    }

    /** Content size: full view height in spread mode, capped otherwise. */
    private fun flagSize(w: Float, h: Float): Float =
        if (spread) h
        else min(preferredContentSize * CARD_CONTENT_SCALE, h * 0.92f)

    private fun buildMask(w: Float, h: Float): Shader {
        val (cx, cy) = flagCenter(w, h)
        val size = flagSize(w, h)
        // Hold most of the tint across the glyph and dissolve past it, so the
        // wash still reads at full strength where the card edge cuts it off.
        val radius = if (spread) size * 1.1f else size * CARD_MASK_RADIUS_FACTOR
        val peak = if (spread) SPREAD_PEAK_ALPHA else peakAlphaOverride ?: CARD_PEAK_ALPHA
        val stops = floatArrayOf(0f, 0.5f, 1f)
        val colors = intArrayOf(
            alphaColor(peak),
            alphaColor(peak * 0.72f),
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

        // Content square centred on the wash centre in both modes; the
        // translate moves its centre onto the wash centre so the glyph stays
        // aligned with the radial mask.
        val rectLeft = cx - size / 2f
        val rectRight = cx + size / 2f
        val centreX = (rectLeft + rectRight) / 2f
        val centreY = size / 2f

        canvas.save()
        canvas.translate(cx - centreX, cy - centreY)
        if (!spread) {
            // Tilt the glyph so the bottom-end cut reads as a slant.
            canvas.rotate(CARD_TILT_DEGREES, centreX, centreY)
        }

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
        // Spread mode scales the em box up so the flag's visible body spans
        // the full height of the banner.
        textPaint.textSize = size * (if (spread) SPREAD_TEXT_SCALE else 0.95f)
        val fm = textPaint.fontMetrics
        val baseline = (size - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(text, centreX, baseline, textPaint)
        canvas.restore()
    }
}
