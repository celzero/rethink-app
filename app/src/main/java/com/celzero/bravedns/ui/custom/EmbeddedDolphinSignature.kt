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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.NestedScrollView
import com.celzero.bravedns.R

/**
 * A tiny, quiet dolphin "signature" meant to sit at the end of a screen's
 * scrollable content: a small dolphin illustration directly above a short
 * quote, both centered, with no card, border, background, or elevation of
 * its own. The artwork is transparent, so the screen's own background shows
 * through and the mark reads as part of the screen rather than a footer.
 *
 * Pair any dolphin with any quote via [setContent]; each screen shows its
 * own random combination from [EmbeddedDolphinContent.random], and tapping
 * the dolphin cycles that screen's artwork and quote.
 *
 * The first time the signature becomes visible on screen, the dolphin swims
 * in along a short curved path and settles into its resting spot, after
 * which the quote fades in. The animation plays once per view instance:
 * content swaps, layout passes, and re-binds never replay it. When the
 * system's animator duration scale is off, the settled state is shown
 * immediately instead.
 *
 * Tap-to-cycle gets its own small transition: the current dolphin dips up
 * with a slight tilt, the next artwork and quote swap in at the top of the
 * dip, and the pair settles back with a gentle overshoot. Programmatic
 * [setContent] calls stay instant so failure states and fresh binds are
 * never animated.
 */
class EmbeddedDolphinSignature @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr),
    ViewTreeObserver.OnScrollChangedListener,
    ViewTreeObserver.OnGlobalLayoutListener {

    private val dolphinView: AppCompatImageView
    private val quoteView: AppCompatTextView

    /** The pairing currently rendered; drives tap-to-cycle. */
    private var current: EmbeddedDolphinContent.Signature? = null

    /** True once the entrance has run (or been skipped) for this view. */
    private var entrancePlayed = false

    /** True while hidden and waiting to become visible for the entrance. */
    private var entrancePending = false

    private var entranceAnimator: AnimatorSet? = null
    private var cycleAnimator: AnimatorSet? = null

    /** True while a tap-cycle transition is mid-flight; swallows re-taps. */
    private var isCycling = false
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private val visibleRect = Rect()

    /**
     * Host scroll view for overlay mode (see [revealAtScrollEndOf]); null
     * when the signature flows inline at the end of the content.
     */
    private var scrollHost: NestedScrollView? = null

    /** Overlay mode only: true while the mark is currently revealed. */
    private var overlayShown = false

    /**
     * Overlay mode only: when true the mark is force-hidden regardless of
     * scroll position (e.g. while a screen is still loading its content).
     */
    private var overlaySuppressed = false

    /** Guards double registration of the shared window watchers. */
    private var windowWatchersAdded = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        // keep the mark grounded to the content above it without turning the
        // area below it into a padded footer
        val hPad = dp(SIGNATURE_HORIZONTAL_PADDING_DP)
        setPadding(hPad, dp(SIGNATURE_TOP_PADDING_DP), hPad, dp(SIGNATURE_BOTTOM_PADDING_DP))

        dolphinView = AppCompatImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            alpha = DOLPHIN_ALPHA
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true
            isFocusable = true
            // tapping the dolphin cycles this screen's pairing to the next
            // artwork + quote, with a small hop between the two
            setOnClickListener {
                val cur = current ?: return@setOnClickListener
                cycleTo(EmbeddedDolphinContent.next(cur))
            }
        }
        addView(
            dolphinView,
            LayoutParams(dp(DOLPHIN_WIDTH_DP), dp(DOLPHIN_HEIGHT_DP))
        )

        quoteView = AppCompatTextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = QUOTE_ALPHA
            setTextColor(resolveThemeColor(R.attr.primaryLightColorText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, QUOTE_TEXT_SIZE_SP)
        }
        addView(
            quoteView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(QUOTE_TOP_MARGIN_DP)
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scrollHost != null) {
            // overlay mode: window-wide scroll and layout changes drive the
            // reveal, so the shared watchers must stay registered
            addWindowWatchers()
        }
        if (entrancePlayed) return
        if (!entrancePending) {
            if (isReducedMotionPreferred()) {
                entrancePlayed = true
                return
            }
            hideForEntrance()
        }
        observeFirstDraw()
    }

    override fun onDetachedFromWindow() {
        removePreDrawListener()
        forceRemoveWindowWatchers()
        // a swim or cycle interrupted by the view going away settles
        // instantly; the hidden/pending state survives so a genuine
        // re-attach can still play the entrance
        animate().cancel()
        entranceAnimator?.cancel()
        entranceAnimator = null
        cycleAnimator?.cancel()
        cycleAnimator = null
        isCycling = false
        super.onDetachedFromWindow()
    }

    /**
     * Shows the given dolphin artwork above the given quote. Any dolphin can
     * be paired with any quote; both are rendered independently. Tapping has
     * no cycle target for this manual variant; prefer [setContent] with a
     * [EmbeddedDolphinContent.Signature].
     */
    fun setContent(@DrawableRes image: Int, quote: String) {
        retireEntranceIfPlayed()
        current = null
        dolphinView.setImageResource(image)
        quoteView.text = quote
    }

    /** Renders [signature] and lets taps cycle to its successor. */
    fun setContent(signature: EmbeddedDolphinContent.Signature) {
        retireEntranceIfPlayed()
        current = signature
        dolphinView.setImageResource(signature.image)
        quoteView.text = context.getString(signature.quote)
    }

    /**
     * Failure-state marker: renders a random sad dolphin above a random
     * quote. Sad dolphins are excluded from the regular pool, so this is
     * the only path that shows one. Tapping cycles back into the regular
     * dolphin pool, recovering the signature to its normal state.
     */
    fun setContentForFailure() {
        setContent(EmbeddedDolphinContent.randomFailure())
    }

    /**
     * Conceals both children just before the first frame so the settled
     * state never flashes ahead of the entrance. Only layout-independent
     * view properties are touched; sizes and positions stay untouched so
     * the resting geometry is identical after the entrance.
     */
    private fun hideForEntrance() {
        entrancePending = true
        dolphinView.alpha = 0f
        quoteView.alpha = 0f
        quoteView.translationY = dp(ENTRANCE_QUOTE_OFFSET_DP).toFloat()
    }

    /**
     * Runs the visibility check at draw time: if the signature is already
     * on screen, swim in immediately; otherwise wait for a scroll or layout
     * change that brings it into view (footer inside a scrolled container).
     */
    private fun observeFirstDraw() {
        if (preDrawListener != null) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            removePreDrawListener()
            val visible = isShown && getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty
            if (visible) {
                playEntrance()
            } else {
                addWindowWatchers()
            }
            true
        }
        preDrawListener = listener
        viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * Overlay mode: instead of flowing after the scroll content, the
     * signature parks itself a little above the bottom edge of its parent
     * (place it bottom-anchored in the screen root) and is revealed — with a
     * short crossfade — only while [host] is scrolled to, or near, its end.
     * Content shorter than the viewport counts as being at the end. The
     * first reveal also plays the swim-in entrance. Call once from
     * onViewCreated; the placement must be bottom-anchored, not inline.
     */
    fun revealAtScrollEndOf(host: NestedScrollView) {
        scrollHost = host
        overlayShown = false
        visibility = View.GONE
        // evaluate once the host has laid out; the shared window watchers
        // registered at attach drive every update after that
        if (isAttachedToWindow) {
            addWindowWatchers()
        }
        host.post { updateOverlayVisibility() }
    }

    /**
     * Overlay mode only: force-hides the mark while [suppress] is true
     * (loading states), resuming normal scroll-end behaviour after.
     */
    fun suppressOverlay(suppress: Boolean) {
        overlaySuppressed = suppress
        if (suppress) {
            setRevealed(false)
        } else {
            updateOverlayVisibility()
        }
    }

    override fun onScrollChanged() {
        updateOverlayVisibility()
        maybePlayEntrance()
    }

    override fun onGlobalLayout() {
        updateOverlayVisibility()
        maybePlayEntrance()
    }

    /**
     * Overlay-mode reveal computation: visible when the host's remaining
     * scroll distance is within a quarter of its viewport (or when the
     * content does not scroll at all).
     */
    private fun updateOverlayVisibility() {
        val host = scrollHost ?: return
        if (!isAttachedToWindow) return
        if (overlaySuppressed) {
            setRevealed(false)
            return
        }
        val viewport = host.height - host.paddingTop - host.paddingBottom
        val show = if (viewport <= 0) {
            false
        } else {
            val content = host.getChildAt(0)
            val remaining = content?.let {
                it.height - host.paddingTop - host.paddingBottom - (host.scrollY + viewport)
            } ?: 0
            remaining <= viewport * SCROLL_END_FRACTION
        }
        setRevealed(show)
    }

    /** Crossfades the overlay in/out; cancelling never strands a hide callback. */
    private fun setRevealed(show: Boolean) {
        if (overlayShown == show) return
        overlayShown = show
        animate().cancel()
        if (show) {
            if (visibility != View.VISIBLE) visibility = View.VISIBLE
            if (alpha < 1f) {
                animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start()
            }
        } else {
            animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction {
                    if (isAttachedToWindow) visibility = View.GONE
                }
                .start()
        }
    }

    private fun maybePlayEntrance() {
        if (!entrancePending) return
        if (!isShown) return
        if (!getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty) return
        playEntrance()
    }

    /**
     * Swims the dolphin along a short curved descent from up-and-to-the-side
     * of its resting spot, with a small tilt-into-the-dive and a gentle
     * scale settle, then fades the quote up underneath it. Every property
     * ends exactly at its resting value, so the final frame is identical to
     * the pre-animation layout.
     */
    private fun playEntrance() {
        if (!entrancePending) return
        entrancePending = false
        entrancePlayed = true
        removeEntranceWatchers()

        // start point sits a small step up and to the side; the quadratic
        // control point bends the travel into a dive that steepens as the
        // dolphin closes on its spot
        val startX = dp(ENTRANCE_START_OFFSET_X_DP).toFloat()
        val startY = dp(ENTRANCE_START_OFFSET_Y_DP).toFloat()
        val swimPath = Path().apply {
            moveTo(startX, startY)
            quadTo(
                startX * ENTRANCE_CURVE_CONTROL_X_FRACTION,
                startY * ENTRANCE_CURVE_CONTROL_Y_FRACTION,
                0f,
                0f
            )
        }

        val swim = ObjectAnimator.ofFloat(
            dolphinView,
            View.TRANSLATION_X,
            View.TRANSLATION_Y,
            swimPath
        ).apply {
            duration = ENTRANCE_SWIM_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
        }

        val fade = ObjectAnimator.ofFloat(dolphinView, View.ALPHA, 0f, DOLPHIN_ALPHA).apply {
            duration = ENTRANCE_FADE_DURATION_MS
        }

        // tilts into the direction of travel, tips just past level, then
        // rests at exactly zero
        val tilt = ObjectAnimator.ofPropertyValuesHolder(
            dolphinView,
            PropertyValuesHolder.ofKeyframe(
                View.ROTATION,
                Keyframe.ofFloat(0f, ENTRANCE_TILT_INTO_SWIM_DEG),
                Keyframe.ofFloat(0.65f, ENTRANCE_TILT_SETTLE_DEG),
                Keyframe.ofFloat(1f, 0f)
            )
        ).apply { duration = ENTRANCE_SWIM_DURATION_MS }

        // slightly small while approaching, a touch full at arrival, exact
        // resting scale at the end
        val scale = ObjectAnimator.ofPropertyValuesHolder(
            dolphinView,
            PropertyValuesHolder.ofKeyframe(
                View.SCALE_X,
                Keyframe.ofFloat(0f, ENTRANCE_SCALE_START),
                Keyframe.ofFloat(0.7f, ENTRANCE_SCALE_SETTLE),
                Keyframe.ofFloat(1f, 1f)
            ),
            PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y,
                Keyframe.ofFloat(0f, ENTRANCE_SCALE_START),
                Keyframe.ofFloat(0.7f, ENTRANCE_SCALE_SETTLE),
                Keyframe.ofFloat(1f, 1f)
            )
        ).apply { duration = ENTRANCE_SWIM_DURATION_MS }

        val quoteIn = AnimatorSet().apply {
            startDelay = ENTRANCE_QUOTE_DELAY_MS
            duration = ENTRANCE_QUOTE_DURATION_MS
            interpolator = DecelerateInterpolator(1.2f)
            playTogether(
                ObjectAnimator.ofFloat(quoteView, View.ALPHA, 0f, QUOTE_ALPHA),
                ObjectAnimator.ofFloat(quoteView, View.TRANSLATION_Y, quoteView.translationY, 0f)
            )
        }

        entranceAnimator = AnimatorSet().apply {
            playTogether(swim, fade, tilt, scale, quoteIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    entranceAnimator = null
                    settleToFinalState()
                }
            })
            start()
        }
    }

    /** Stamps the exact resting values so nothing is left mid-flight. */
    private fun settleToFinalState() {
        dolphinView.translationX = 0f
        dolphinView.translationY = 0f
        dolphinView.rotation = 0f
        dolphinView.scaleX = 1f
        dolphinView.scaleY = 1f
        dolphinView.alpha = DOLPHIN_ALPHA
        quoteView.translationY = 0f
        quoteView.alpha = QUOTE_ALPHA
    }

    /**
     * Used whenever the signature must show different content without the
     * entrance or a cycle in flight: retires both, stamps the resting
     * values, and lets the caller swap content immediately.
     */
    private fun retireEntrance() {
        entrancePending = false
        entrancePlayed = true
        removePreDrawListener()
        removeEntranceWatchers()
        entranceAnimator?.cancel()
        entranceAnimator = null
        cycleAnimator?.cancel()
        cycleAnimator = null
        isCycling = false
        settleToFinalState()
    }

    /**
     * Retires the entrance only once it has already run (or been skipped).
     * Hosts set content in onCreate/onViewCreated, before the view attaches:
     * retiring unconditionally there would mark the entrance as played and
     * the swim-in would never be armed. Leaving a still-pending entrance
     * intact lets it play later, when the signature scrolls into view, with
     * the freshly-set content.
     */
    private fun retireEntranceIfPlayed() {
        if (entrancePlayed) retireEntrance()
    }

    /**
     * Tap-to-cycle transition: the dolphin dips up a few dp with a small
     * tilt as the current pairing recedes, the next artwork and quote swap
     * in at the top of the dip, then the dolphin drops back with a slight
     * overshoot while the quote fades up underneath. All properties end at
     * their exact resting values.
     */
    private fun cycleTo(next: EmbeddedDolphinContent.Signature) {
        if (isCycling) return
        if (isReducedMotionPreferred()) {
            setContent(next)
            return
        }
        // covers an entrance still swimming, still pending offscreen, or
        // already retired; the tap hands over to the cycle animation
        retireEntrance()
        current = next
        isCycling = true

        val dip = dp(CYCLE_DIP_DP).toFloat()
        val quoteShift = dp(CYCLE_QUOTE_OFFSET_DP).toFloat()

        val out = AnimatorSet().apply {
            duration = CYCLE_OUT_DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            playTogether(
                ObjectAnimator.ofFloat(dolphinView, View.TRANSLATION_Y, 0f, dip),
                ObjectAnimator.ofFloat(dolphinView, View.ROTATION, 0f, CYCLE_TILT_DEG),
                ObjectAnimator.ofFloat(quoteView, View.ALPHA, quoteView.alpha, 0f),
                ObjectAnimator.ofFloat(quoteView, View.TRANSLATION_Y, 0f, quoteShift)
            )
        }

        // tips just past level on the way down so the drop reads as a small
        // dive rather than a bounce-back
        val backIn = AnimatorSet().apply {
            duration = CYCLE_IN_DURATION_MS
            interpolator = DecelerateInterpolator(1.2f)
            playTogether(
                ObjectAnimator.ofFloat(dolphinView, View.TRANSLATION_Y, dip, 0f),
                ObjectAnimator.ofPropertyValuesHolder(
                    dolphinView,
                    PropertyValuesHolder.ofKeyframe(
                        View.ROTATION,
                        Keyframe.ofFloat(0f, CYCLE_TILT_DEG),
                        Keyframe.ofFloat(0.75f, CYCLE_TILT_SETTLE_DEG),
                        Keyframe.ofFloat(1f, 0f)
                    )
                ),
                ObjectAnimator.ofFloat(quoteView, View.ALPHA, 0f, QUOTE_ALPHA),
                ObjectAnimator.ofFloat(quoteView, View.TRANSLATION_Y, quoteShift, 0f)
            )
        }

        // swap the artwork and quote at the top of the dip so the new
        // pairing is what drops back into place
        out.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                dolphinView.setImageResource(next.image)
                quoteView.text = context.getString(next.quote)
            }
        })

        // the outer set must be the one started: it drives the out-then-back
        // sequence and its end listener is what clears isCycling — starting
        // only the children would leave it stuck true, swallowing every
        // later tap, and would also make cancellation of this field a no-op
        cycleAnimator = AnimatorSet().apply {
            play(out).before(backIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cycleAnimator = null
                    isCycling = false
                    settleToFinalState()
                }
            })
            start()
        }
    }

    private fun removePreDrawListener() {
        val listener = preDrawListener ?: return
        preDrawListener = null
        try {
            viewTreeObserver.removeOnPreDrawListener(listener)
        } catch (_: Exception) {
            // observer can already be dead after detach; nothing to clean up
        }
    }

    /** Registers the shared window scroll/layout watchers at most once. */
    private fun addWindowWatchers() {
        if (windowWatchersAdded) return
        windowWatchersAdded = true
        viewTreeObserver.addOnScrollChangedListener(this)
        viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    /**
     * Drops the watchers once they are no longer needed. Overlay mode keeps
     * them registered for the whole lifetime — they drive the reveal — so
     * this becomes a no-op there; only detach removes them for real.
     */
    private fun removeEntranceWatchers() {
        if (scrollHost != null) return
        forceRemoveWindowWatchers()
    }

    private fun forceRemoveWindowWatchers() {
        windowWatchersAdded = false
        try {
            viewTreeObserver.removeOnScrollChangedListener(this)
            viewTreeObserver.removeOnGlobalLayoutListener(this)
        } catch (_: Exception) {
            // observer can already be dead after detach; nothing to clean up
        }
    }

    private fun isReducedMotionPreferred(): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        // sized as a signature, not an illustration: the whole mark stays
        // well under ~80dp tall including the quote
        private const val DOLPHIN_WIDTH_DP = 48
        private const val DOLPHIN_HEIGHT_DP = 40
        private const val QUOTE_TEXT_SIZE_SP = 11.5f
        private const val QUOTE_TOP_MARGIN_DP = 4
        private const val QUOTE_ALPHA = 0.55f
        private const val DOLPHIN_ALPHA = 0.92f
        private const val SIGNATURE_HORIZONTAL_PADDING_DP = 24
        private const val SIGNATURE_TOP_PADDING_DP = 6
        private const val SIGNATURE_BOTTOM_PADDING_DP = 10

        // entrance: ~600ms total; the quote starts as the dolphin is about
        // to land so it reads as arriving after the dolphin
        private const val ENTRANCE_SWIM_DURATION_MS = 520L
        private const val ENTRANCE_FADE_DURATION_MS = 220L
        private const val ENTRANCE_QUOTE_DELAY_MS = 360L
        private const val ENTRANCE_QUOTE_DURATION_MS = 240L

        // a small step up-and-to-the-side of the resting spot
        private const val ENTRANCE_START_OFFSET_X_DP = -14
        private const val ENTRANCE_START_OFFSET_Y_DP = -16

        // fractions of the start offset where the arc's bend sits, giving a
        // descent that steepens into the landing
        private const val ENTRANCE_CURVE_CONTROL_X_FRACTION = 0.35f
        private const val ENTRANCE_CURVE_CONTROL_Y_FRACTION = 0.45f

        private const val ENTRANCE_TILT_INTO_SWIM_DEG = 8f
        private const val ENTRANCE_TILT_SETTLE_DEG = -2.5f
        private const val ENTRANCE_SCALE_START = 0.88f
        private const val ENTRANCE_SCALE_SETTLE = 1.03f
        private const val ENTRANCE_QUOTE_OFFSET_DP = 4

        // tap-cycle: a quick dip up and back (~340ms total); the tilt reads
        // as a nose-up hop for right-facing artwork, flip the signs for
        // left-facing pieces
        private const val CYCLE_OUT_DURATION_MS = 140L
        private const val CYCLE_IN_DURATION_MS = 200L
        private const val CYCLE_DIP_DP = 6
        private const val CYCLE_TILT_DEG = -6f
        private const val CYCLE_TILT_SETTLE_DEG = 2f
        private const val CYCLE_QUOTE_OFFSET_DP = 2

        // overlay mode: crossfade duration for show/hide and the fraction of
        // the viewport's remaining scroll distance within which the mark is
        // revealed (content that cannot scroll counts as "at the end")
        private const val OVERLAY_FADE_MS = 200L
        private const val SCROLL_END_FRACTION = 0.25f
    }
}
