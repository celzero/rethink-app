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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import com.celzero.bravedns.util.AnimatedBorderDrawable
import com.google.android.material.card.MaterialCardView

/**
 * A [MaterialCardView] that can render a Google Photos-style animated accent
 * border travelling around its rounded perimeter while it is selected.
 *
 * Animation model (deliberately kept as direct as possible):
 *  - this view owns the [ValueAnimator];
 *  - every animator tick updates the stateless [AnimatedBorderDrawable]'s
 *    phase and calls [postInvalidateOnAnimation] on this view directly -
 *    there is no drawable-callback or overlay invalidation that the
 *    framework can drop;
 *  - [onDraw] renders the highlight on top of the card's own background,
 *    stroke and children (children never overlap the border area, and the
 *    ripple foreground only draws while pressed).
 *
 * All objects are created once and reused; no per-frame allocations.
 */
class AnimatedBorderCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    companion object {
        // duration of one complete revolution around the perimeter
        private const val REVOLUTION_DURATION_MS = 2500L
        private const val TAG = "AnimatedBorderCardView"
    }

    private val borderDrawable = AnimatedBorderDrawable()
    private var animator: ValueAnimator? = null

    // set once per start() to log that the first animated frame rendered
    private var logFirstFrame = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // keep the highlight path in sync with the card's actual size
        borderDrawable.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (animator?.isRunning != true || !borderDrawable.isReady()) return
        borderDrawable.draw(canvas)
        if (logFirstFrame) {
            logFirstFrame = false
            android.util.Log.d(
                TAG,
                "animated border frame drawn: w=$width h=$height " +
                    "perimeterReady=${borderDrawable.isReady()}"
            )
        }
    }

    /**
     * Starts (or keeps running) the animated accent border. [strokeWidthPx]
     * is the width of the moving highlight; the corner radius always tracks
     * the card's own radius.
     */
    fun startBorderAnimation(strokeWidthPx: Float, accentColor: Int) {
        borderDrawable.setStrokeWidth(strokeWidthPx)
        borderDrawable.setCornerRadius(radius)
        borderDrawable.setAccentColor(accentColor)
        borderDrawable.setBounds(0, 0, width, height)
        logFirstFrame = true

        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = REVOLUTION_DURATION_MS
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener {
                    borderDrawable.setPhase(it.animatedValue as Float)
                    // direct, unconditional self-invalidation per frame
                    postInvalidateOnAnimation()
                }
            }
        }
        if (animator?.isRunning != true) {
            animator?.start()
        }
        postInvalidateOnAnimation()
    }

    /** Stops the animated border; the highlight disappears immediately. */
    fun stopBorderAnimation() {
        animator?.cancel()
        postInvalidateOnAnimation()
    }
}
