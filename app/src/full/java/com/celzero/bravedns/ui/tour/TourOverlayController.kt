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

import Logger
import android.app.Activity
import android.graphics.Rect
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.doOnLayout
import com.celzero.bravedns.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Orchestrates the guided tour experience for a single Activity.
 *
 * ## Lifecycle
 * 1. Call [start] after the host Fragment's views are laid out.
 * 2. The controller attaches a [SpotlightOverlayView] + tooltip card to the DecorView.
 * 3. It walks through the [steps] list on Next/Skip; on the last step it fires [onComplete].
 * 4. When [onComplete] fires, the overlay is already removed, no manual cleanup needed.
 *
 * ## Thread safety
 * Must be constructed and used entirely on the main thread.
 *
 * @param activity   Host activity, used only to attach/detach views.
 * @param steps      Ordered list of [TourStep]s produced by [GuidedTourManager.homeScreenSteps].
 * @param onComplete Invoked once when the user completes or skips the entire tour.
 */
class TourOverlayController(
    private val activity: Activity,
    private val steps: List<TourStep>,
    private val onComplete: () -> Unit,
) {
    companion object {
        private const val TAG = "TourOverlayController"

        /** Minimum screen pixels below the spotlight before we flip the tooltip to ABOVE. */
        private const val MIN_SPACE_BELOW_PX = 280
        /** Vertical gap between spotlight edge and tooltip card edge, in pixels. */
        private const val TOOLTIP_MARGIN_PX  = 16
        /** Dot indicator size, in dp. */
        private const val DOT_SIZE_DP = 6f
        /** Dot spacing, in dp. */
        private const val DOT_SPACING_DP = 5f

        /**
         * Resolves a theme colour attribute (e.g. `R.attr.colorGolden`) to an integer color.
         * Falls back to [fallback] if the attribute is not found in the theme.
         */
        private fun Activity.resolveAttrColor(attrRes: Int, fallback: Int): Int {
            val tv = TypedValue()
            return if (theme.resolveAttribute(attrRes, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                    tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    tv.data
                } else {
                    try {
                        resources.getColor(tv.resourceId, theme)
                    } catch (_: Exception) { fallback }
                }
            } else fallback
        }
    }

    private var currentStepIndex = 0
    private var isAttached = false

    // Views
    private val decorRoot: FrameLayout =
        activity.window.decorView.rootView as FrameLayout

    private val overlayView = SpotlightOverlayView(activity)
    private val tooltipView: View = LayoutInflater.from(activity)
        .inflate(R.layout.view_tour_tooltip, decorRoot, false)

    // Tooltip child refs
    private val tvCounter: AppCompatTextView = tooltipView.findViewById(R.id.tour_step_counter)
    private val tvTitle: AppCompatTextView   = tooltipView.findViewById(R.id.tour_title)
    private val tvDesc: AppCompatTextView    = tooltipView.findViewById(R.id.tour_desc)
    private val btnNext: MaterialButton      = tooltipView.findViewById(R.id.tour_next_btn)
    private val btnSkip: AppCompatTextView   = tooltipView.findViewById(R.id.tour_skip_btn)
    private val btnClose: View               = tooltipView.findViewById(R.id.tour_close_btn)
    private val dotsContainer: ViewGroup     = tooltipView.findViewById(R.id.tour_dots_container)
    private val premiumBadge: View           = tooltipView.findViewById(R.id.tour_premium_badge)
    private val tooltipCard: MaterialCardView = tooltipView as MaterialCardView

    /** Default card stroke color, cached once so we can restore it on non-premium steps. */
    private val defaultStrokeColor: Int by lazy { tooltipCard.strokeColorStateList?.defaultColor ?: 0 }
    /** Golden color resolved from the current theme, used for the premium step card border. */
    private val goldenColor: Int by lazy { activity.resolveAttrColor(R.attr.colorGolden, 0xFFC9A000.toInt()) }

    /** Attaches the overlay to the DecorView and shows the first step. */
    fun start() {
        if (steps.isEmpty()) { onComplete(); return }
        if (isAttached) return
        isAttached = true

        // Layer order: overlay (dim + cutout) beneath tooltip card
        decorRoot.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        decorRoot.addView(
            tooltipView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Tooltip is invisible until it knows where to position itself
        tooltipView.alpha = 0f

        buildDots()
        bindClickListeners()
        showStep(0)
    }

    // -----------------------------------------------------------------------
    // Step navigation
    // -----------------------------------------------------------------------

    private fun showStep(index: Int) {
        if (index >= steps.size) { completeTour(); return }

        currentStepIndex = index
        val step = steps[index]

        // Bind text content
        tvCounter.text = activity.getString(R.string.tour_step_counter, step.position, step.total)
        tvTitle.text   = activity.getString(step.titleRes)
        tvDesc.text    = activity.getString(step.descRes)
        btnNext.text   = activity.getString(
            if (step.isLastStep) R.string.tour_btn_finish else R.string.next
        )
        updateDots(index)

        // Premium-step UI: show golden badge + golden card border; restore defaults otherwise
        if (step.isPremium) {
            premiumBadge.visibility = View.VISIBLE
            tooltipCard.strokeColor = goldenColor
            tooltipCard.strokeWidth = activity.resources.displayMetrics.density.times(1.5f).toInt()
        } else {
            premiumBadge.visibility = View.GONE
            tooltipCard.strokeColor = defaultStrokeColor
            tooltipCard.strokeWidth = activity.resources.displayMetrics.density.times(0.5f).toInt()
        }

        // Find the target view; if it's not laid out yet, wait for the global layout pass
        val target = activity.findViewById<View>(step.targetViewId)
        if (target == null) {
            Logger.w("TourOverlay", "$TAG: target view ${step.targetViewId} not found, skipping step")
            showStep(index + 1)
            return
        }

        target.doOnLayout { positionOnView(target, step) }
        if (target.isLaidOut) positionOnView(target, step)
    }

    private fun positionOnView(target: View, step: TourStep) {
        val targetRect = target.globalVisibleRect() ?: run {
            Logger.w("TourOverlay", "$TAG: target not visible on screen, skipping")
            showStep(currentStepIndex + 1)
            return
        }

        // Animate spotlight
        overlayView.animateTo(targetRect, step.spotlightShape)

        // Tap inside spotlight → advance; tap outside → same as next
        overlayView.onSpotlightTap = { _ -> advanceOrComplete() }

        // Position tooltip after it is measured
        tooltipView.doOnLayout {
            placeTooltip(targetRect, step.tooltipSide)
        }
        tooltipView.requestLayout()
    }

    private fun advanceOrComplete() {
        if (currentStepIndex < steps.lastIndex) showStep(currentStepIndex + 1)
        else completeTour()
    }

    private fun skipTour() {
        completeTour()
    }

    private fun completeTour() {
        overlayView.dismiss {
            detachViews()
            onComplete()
        }
    }

    // -----------------------------------------------------------------------
    // Tooltip positioning
    // -----------------------------------------------------------------------

    /**
     * Positions the tooltip card above or below the spotlight rect.
     *
     * [TooltipSide.AUTO] picks [TooltipSide.BELOW] unless there isn't enough room below,
     * in which case it flips to [TooltipSide.ABOVE].
     */
    private fun placeTooltip(spotlightRect: Rect, requestedSide: TooltipSide) {
        val screenHeight = decorRoot.height
        val tooltipH     = tooltipView.measuredHeight.takeIf { it > 0 } ?: return

        val spaceBelow = screenHeight - spotlightRect.bottom
        val resolvedSide = when (requestedSide) {
            TooltipSide.AUTO  -> if (spaceBelow >= MIN_SPACE_BELOW_PX) TooltipSide.BELOW else TooltipSide.ABOVE
            else               -> requestedSide
        }

        val topY = when (resolvedSide) {
            TooltipSide.BELOW -> (spotlightRect.bottom + TOOLTIP_MARGIN_PX).toFloat()
            else              -> (spotlightRect.top - tooltipH - TOOLTIP_MARGIN_PX).toFloat()
                                    .coerceAtLeast(TOOLTIP_MARGIN_PX.toFloat())
        }

        val params = tooltipView.layoutParams as FrameLayout.LayoutParams
        params.topMargin = topY.toInt()
        tooltipView.layoutParams = params

        // Fade the tooltip in once positioned
        if (tooltipView.alpha == 0f) {
            tooltipView.animate().alpha(1f).setDuration(180).start()
        }
    }

    // -----------------------------------------------------------------------
    // Dot indicators
    // -----------------------------------------------------------------------

    private fun buildDots() {
        dotsContainer.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val sizePx  = (DOT_SIZE_DP * density).toInt()
        val margin  = (DOT_SPACING_DP * density).toInt()

        repeat(steps.size) { i ->
            val dot = View(activity).apply {
                layoutParams = ViewGroup.MarginLayoutParams(sizePx, sizePx).also {
                    it.marginStart = if (i == 0) 0 else margin
                }
                setBackgroundResource(R.drawable.ic_circle)
                alpha = if (i == 0) 1f else 0.3f
            }
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots(activeIndex: Int) {
        for (i in 0 until dotsContainer.childCount) {
            dotsContainer.getChildAt(i).animate()
                .alpha(if (i == activeIndex) 1f else 0.3f)
                .setDuration(150)
                .start()
        }
    }

    // -----------------------------------------------------------------------
    // Click listeners
    // -----------------------------------------------------------------------

    private fun bindClickListeners() {
        btnNext.setOnClickListener  { advanceOrComplete() }
        btnSkip.setOnClickListener  { skipTour() }
        btnClose.setOnClickListener { skipTour() }
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    private fun detachViews() {
        runCatching { decorRoot.removeView(tooltipView) }
        runCatching { decorRoot.removeView(overlayView) }
        isAttached = false
    }

    // -----------------------------------------------------------------------
    // Extension helpers
    // -----------------------------------------------------------------------

    /** Returns the global (screen-absolute) visible rect for this view, or null if not visible. */
    private fun View.globalVisibleRect(): Rect? {
        val r = Rect()
        return if (getGlobalVisibleRect(r) && !r.isEmpty) r else null
    }
}


