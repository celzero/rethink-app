/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.tour

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import com.celzero.bravedns.R

/**
 * Full-screen overlay that:
 *  • draws a semi-transparent scrim over the entire window
 *  • punches a rounded-rect (or circle) "spotlight" cutout around the target
 *  • animates smoothly between spotlight positions on step advances
 *  • fires [onSpotlightTap] with `inside = true/false` on every touch
 *
 * Attach to the DecorView's root FrameLayout so it sits above all content.
 * Theme colours are resolved via `?attr/tourOverlayColor` and `?attr/tourStrokeColor`.
 *
 * ## Usage
 * ```
 * val overlay = SpotlightOverlayView(activity)
 * (activity.window.decorView as FrameLayout).addView(overlay, MATCH_PARENT, MATCH_PARENT)
 * overlay.animateTo(targetRect, SpotlightShape.ROUNDED_RECT)
 * overlay.onSpotlightTap = { insideSpotlight -> … }
 * ```
 */
class SpotlightOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // -----------------------------------------------------------------------
    // Callback
    // -----------------------------------------------------------------------

    /**
     * Invoked on every raw touch-down.
     *
     * @param insideSpotlight `true` if the finger is inside the spotlight cutout.
     */
    var onSpotlightTap: ((insideSpotlight: Boolean) -> Unit)? = null

    // -----------------------------------------------------------------------
    // Paint / drawing state
    // -----------------------------------------------------------------------

    private val overlayColor: Int = resolveAttrColor(R.attr.tourOverlayColor, Color.parseColor("#D1000000"))
    private val strokeColor: Int  = resolveAttrColor(R.attr.tourStrokeColor,  Color.parseColor("#FF18ffff"))

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = overlayColor
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Clears pixels inside the spotlight rect to make it transparent
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style  = Paint.Style.STROKE
        color  = strokeColor
        strokeWidth = dp(1.5f)
        alpha  = 140 // ~55% opacity
    }

    /** Corner radius for ROUNDED_RECT spotlight, in pixels. */
    private val cornerRadius = dp(16f)

    /** Padding around the target rect, gives the spotlight a little breathing room. */
    private val spotlightPad = dp(8f)

    private val currentRect  = RectF()  // currently drawn spotlight
    private val targetRect   = RectF()  // destination rect
    private var shape        = SpotlightShape.ROUNDED_RECT

    private var animator: ValueAnimator? = null

    /** Off-screen bitmap used for the DST_OUT compositing trick. */
    private var offscreenBitmap: Bitmap? = null
    private var offscreenCanvas: Canvas? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Animate the spotlight from its current position to [destRect].
     *
     * If this is the very first call (no current position), the overlay fades in while
     * the spotlight expands from the centre of [destRect].
     *
     * Duration: 220 ms, fast enough to feel responsive, long enough to be smooth.
     */
    fun animateTo(destRect: Rect, destShape: SpotlightShape = SpotlightShape.ROUNDED_RECT) {
        shape = destShape

        val destRectF = destRect.toRectFWithPadding(spotlightPad)

        animator?.cancel()

        if (currentRect.isEmpty) {
            // First appearance: start from the centre point of the destination
            currentRect.set(destRectF.centerX(), destRectF.centerY(),
                            destRectF.centerX(), destRectF.centerY())
            // Also fade in the overlay
            alpha = 0f
            animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        }

        val startLeft   = currentRect.left
        val startTop    = currentRect.top
        val startRight  = currentRect.right
        val startBottom = currentRect.bottom

        targetRect.set(destRectF)

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = MOVE_DURATION_MS
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                val t = va.animatedFraction
                currentRect.set(
                    lerp(startLeft,   targetRect.left,   t),
                    lerp(startTop,    targetRect.top,    t),
                    lerp(startRight,  targetRect.right,  t),
                    lerp(startBottom, targetRect.bottom, t),
                )
                invalidate()
            }
            doOnEnd { currentRect.set(targetRect) }
            start()
        }
    }

    /**
     * Fade out the overlay and call [onEnd] when the animation completes.
     * The view is NOT removed automatically, the caller must detach it.
     */
    fun dismiss(onEnd: () -> Unit = {}) {
        animator?.cancel()
        animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            })
            .start()
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Recreate the offscreen bitmap whenever the view size changes
        offscreenBitmap?.recycle()
        if (w > 0 && h > 0) {
            offscreenBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            offscreenCanvas = Canvas(offscreenBitmap!!)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = offscreenBitmap ?: return
        val oCanvas = offscreenCanvas ?: return

        // 1. Fill the offscreen bitmap with the scrim colour
        oCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        oCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // 2. Erase (punch out) the spotlight area
        if (!currentRect.isEmpty) {
            when (shape) {
                SpotlightShape.ROUNDED_RECT ->
                    oCanvas.drawRoundRect(currentRect, cornerRadius, cornerRadius, eraserPaint)
                SpotlightShape.CIRCLE -> {
                    val radius = maxOf(currentRect.width(), currentRect.height()) / 2f
                    oCanvas.drawCircle(currentRect.centerX(), currentRect.centerY(), radius, eraserPaint)
                }
            }
        }

        // 3. Blit the composited bitmap to the real canvas
        canvas.drawBitmap(bmp, 0f, 0f, null)

        // 4. Draw the subtle spotlight ring on top
        if (!currentRect.isEmpty) {
            when (shape) {
                SpotlightShape.ROUNDED_RECT ->
                    canvas.drawRoundRect(currentRect, cornerRadius, cornerRadius, strokePaint)
                SpotlightShape.CIRCLE -> {
                    val radius = maxOf(currentRect.width(), currentRect.height()) / 2f
                    canvas.drawCircle(currentRect.centerX(), currentRect.centerY(), radius, strokePaint)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Touch handling
    // -----------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val inside = !currentRect.isEmpty && currentRect.contains(event.x, event.y)
            onSpotlightTap?.invoke(inside)
            if (inside) performClick()
        }
        // Consume all touches so they don't pass through to underlying views
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // -----------------------------------------------------------------------
    // Memory management
    // -----------------------------------------------------------------------

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
        offscreenBitmap?.recycle()
        offscreenBitmap = null
        offscreenCanvas = null
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun resolveAttrColor(attrRes: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attrRes, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                tv.data
            } else {
                try { context.resources.getColor(tv.resourceId, context.theme) } catch (e: Exception) { fallback }
            }
        } else {
            fallback
        }
    }

    private fun dp(value: Float): Float =
        value * context.resources.displayMetrics.density

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun Rect.toRectFWithPadding(pad: Float) = RectF(
        left   - pad,
        top    - pad,
        right  + pad,
        bottom + pad,
    )

    companion object {
        private const val FADE_DURATION_MS = 180L
        private const val MOVE_DURATION_MS = 220L
    }
}


