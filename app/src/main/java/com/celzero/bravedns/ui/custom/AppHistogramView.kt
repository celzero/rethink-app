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

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.celzero.bravedns.util.UIUtils
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Monochrome histogram of per-app activity for the home-screen logs card:
 * one fixed-width slot per app, ordered left to right by the caller's chosen
 * order (most recent activity first). Every slot
 * draws the app's icon above a rounded bar whose height encodes the value.
 *
 * Two render modes:
 * - Allowed: the bar stacks two segments of one neutral hue — unmetered
 *   (Wi-Fi) usage at full strength and metered (mobile data) usage dimmer —
 *   so the total height reads as "data used" and the split stays subtle.
 * - Blocked: a single solid segment encodes the blocked-attempt count.
 *
 * The view is intentionally not scrollable: all slots share the available
 * width so the top apps are visible (and swipe-safe inside a ViewPager2)
 * without any inner scrolling. Tapping a slot selects it and reports the
 * entry back via [onEntrySelected]; tapping the selected slot deselects it.
 */
class AppHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Entry(
        val uid: Int,
        val appName: String,
        // byte sums by connection type (allowed mode); zero in blocked mode
        val unmeteredBytes: Long,
        val meteredBytes: Long,
        // blocked-attempt count (blocked mode); zero in allowed mode
        val blockedCount: Long,
        val icon: Drawable?
    )

    /** Reports the tapped slot; null when the selection was cleared. */
    var onEntrySelected: ((Entry?) -> Unit)? = null

    // the app supports manual light/dark themes that can diverge from the
    // system uiMode, so the fragment passes its resolved theme down before
    // submit(); defaults to the system setting
    private var lightTheme = isLightThemeBySystem()

    fun setLightTheme(light: Boolean) {
        // no early return: the palette must be resolved even when the passed
        // value matches the system default, otherwise the legend colors stay
        // transparent until the first submit
        lightTheme = light
        buildDrawables()
        invalidate()
    }

    private var entries: List<Entry> = emptyList()
    private var blockedMode = false
    private var selectedIndex = -1

    // geometry, recomputed on size/data changes
    private var slotWidth = 0f
    private var barWidth = 0f
    private var barHeight = 0f
    private var barTop = 0f
    private var maxValue = 1f

    // drawables rebuilt only when data, size or theme changes
    private var trackDrawables: MutableList<GradientDrawable> = mutableListOf()

    // segments are drawn as plain rects clipped to a pill-shaped path, so a
    // stacked bar reads as one continuous shape with curved top and bottom
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barClipPath = Path()

    private val cornerRadiusPx
        get() = barWidth / 2f
    private val iconSizePx =
        ICON_SIZE_DP * resources.displayMetrics.density
    private val minSegmentPx =
        MIN_SEGMENT_DP * resources.displayMetrics.density
    private val selectionInsetPx =
        SLOT_GAP_DP * resources.displayMetrics.density

    private fun isLightThemeBySystem(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
            Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Replaces the rendered data. [items] is expected pre-sorted by the
     * caller's display order (most recent activity first); more entries than
     * fit the width are simply not
     * drawn. Icons must be resolved by the caller (off the ui thread) before
     * calling this.
     */
    fun submit(items: List<Entry>, blocked: Boolean) {
        entries = items
        blockedMode = blocked
        selectedIndex = -1
        onEntrySelected?.invoke(null)
        computeGeometry()
        buildDrawables()
        invalidate()
    }

    private fun computeGeometry() {
        if (entries.isEmpty() || width <= 0 || height <= 0) {
            slotWidth = 0f
            return
        }
        val slots = entries.size
        val idealSlot = width / slots.toFloat()
        val maxSlot = MAX_SLOT_DP * resources.displayMetrics.density
        slotWidth = minOf(idealSlot, maxSlot)
        // left-align slots when they do not fill the width
        val gridWidth = slotWidth * slots
        val startX = ((width - gridWidth) / 2f).coerceAtLeast(0f)
        slotWidth = slotWidth.coerceAtLeast(1f)
        // bar area: below the icon, down to the bottom edge
        val iconGap = ICON_GAP_DP * resources.displayMetrics.density
        barTop = iconSizePx + iconGap
        barHeight = max(1f, height - barTop)
        barWidth = minOf(BAR_WIDTH_DP * resources.displayMetrics.density, slotWidth - SLOT_GAP_DP * resources.displayMetrics.density)
            .coerceAtLeast(2f)
        maxValue = max(
            1f,
            entries.maxOf { entryValue(it) }.toFloat()
        )
        // store start offset for drawing
        gridStartX = startX
    }

    private var gridStartX = 0f

    private fun entryValue(entry: Entry): Long {
        return if (blockedMode) {
            entry.blockedCount
        } else {
            entry.unmeteredBytes + entry.meteredBytes
        }
    }

    /**
     * Resolves the monochrome palette and rebuilds the track drawables. The
     * palette is resolved even when no data is set yet, so the legend swatch
     * colors exposed via [unmeteredColor]/[meteredColor] are always valid.
     * One neutral hue from the theme, with alpha steps separating unmetered
     * from metered usage. Light themes use higher alphas so bars stay visible
     * on white surfaces, mirroring the activity wall's palette.
     */
    private fun buildDrawables() {
        val base = UIUtils.fetchColor(context, com.celzero.bravedns.R.attr.primaryLightColorText)

        val trackAlpha = if (lightTheme) TRACK_ALPHA_LIGHT else TRACK_ALPHA_DARK
        val unmeteredAlpha = if (lightTheme) UNMETERED_ALPHA_LIGHT else UNMETERED_ALPHA_DARK
        val meteredAlpha = if (lightTheme) METERED_ALPHA_LIGHT else METERED_ALPHA_DARK
        val blockedAlpha = if (lightTheme) BLOCKED_ALPHA_LIGHT else BLOCKED_ALPHA_DARK

        segmentAlphaUnmetered = ColorUtils.setAlphaComponent(base, unmeteredAlpha)
        segmentAlphaMetered = ColorUtils.setAlphaComponent(base, meteredAlpha)
        segmentAlphaBlocked = ColorUtils.setAlphaComponent(base, blockedAlpha)
        segmentColorsResolved = true

        trackDrawables.clear()
        if (entries.isEmpty()) return

        val track = roundedRect(ColorUtils.setAlphaComponent(base, trackAlpha))
        repeat(entries.size) { trackDrawables.add(track) }
    }

    /** Segment color for the Wi-Fi (unmetered) legend swatch. */
    fun unmeteredColor(): Int = segmentAlphaUnmetered

    /** Segment color for the mobile-data (metered) legend swatch. */
    fun meteredColor(): Int = segmentAlphaMetered

    private var segmentColorsResolved = false
    private var segmentAlphaUnmetered: Int = Color.TRANSPARENT
    private var segmentAlphaMetered: Int = Color.TRANSPARENT
    private var segmentAlphaBlocked: Int = Color.TRANSPARENT

    private fun roundedRect(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // the alpha lives in the color itself; a drawable-level alpha
            // would stack on top of it and over-dim the shape
            setColor(color)
            cornerRadius = cornerRadiusPx
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeGeometry()
        buildDrawables()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty() || slotWidth <= 0f || !segmentColorsResolved) return

        entries.forEachIndexed { index, entry ->
            val slotLeft = gridStartX + index * slotWidth
            val centerX = slotLeft + slotWidth / 2f

            // selected slot: faint pill behind icon+bar so the tapped app is obvious
            if (index == selectedIndex) {
                selectionDrawable.setBounds(
                    (centerX - slotWidth / 2f + selectionInsetPx).roundToInt(),
                    0,
                    (centerX + slotWidth / 2f - selectionInsetPx).roundToInt(),
                    height
                )
                selectionDrawable.draw(canvas)
            }

            // track
            val track = trackDrawables[index]
            track.setBounds(
                (centerX - barWidth / 2f).roundToInt(),
                barTop.roundToInt(),
                (centerX + barWidth / 2f).roundToInt(),
                (barTop + barHeight).roundToInt()
            )
            track.draw(canvas)

            // segments, bottom-up: unmetered (Wi-Fi) then metered (mobile);
            // plain rects clipped to the bar's pill shape so the stack keeps
            // one continuous curve at the top and bottom
            val barLeft = (centerX - barWidth / 2f).roundToInt()
            val barRight = (centerX + barWidth / 2f).roundToInt()
            var bottom = barTop + barHeight
            barClipPath.reset()
            barClipPath.addRoundRect(
                barLeft.toFloat(), barTop, barRight.toFloat(), (barTop + barHeight),
                cornerRadiusPx, cornerRadiusPx, Path.Direction.CW
            )
            canvas.save()
            canvas.clipPath(barClipPath)
            if (blockedMode) {
                val segH = segmentHeight(entry.blockedCount)
                if (segH > 0f) {
                    drawSegment(canvas, barLeft.toFloat(), barRight.toFloat(), bottom - segH, bottom, segmentAlphaBlocked)
                }
            } else {
                val unmeteredH = segmentHeight(entry.unmeteredBytes)
                if (unmeteredH > 0f) {
                    drawSegment(canvas, barLeft.toFloat(), barRight.toFloat(), bottom - unmeteredH, bottom, segmentAlphaUnmetered)
                    bottom -= unmeteredH
                }
                val meteredH = segmentHeight(entry.meteredBytes)
                if (meteredH > 0f) {
                    drawSegment(canvas, barLeft.toFloat(), barRight.toFloat(), bottom - meteredH, bottom, segmentAlphaMetered)
                }
            }
            canvas.restore()

            // app icon above the bar, centered on the bar's axis
            entry.icon?.let { icon ->
                val iconLeftPos = centerX - iconSizePx / 2f
                icon.setBounds(
                    iconLeftPos.roundToInt(),
                    0,
                    (iconLeftPos + iconSizePx).roundToInt(),
                    iconSizePx.roundToInt()
                )
                // must be set before draw(): alpha changes only take effect
                // on the next draw pass otherwise
                icon.alpha = ICON_ALPHA
                icon.draw(canvas)
            }
        }
    }

    // the selection pill; drawn from a cached drawable so no per-frame alloc
    private val selectionDrawable: GradientDrawable by lazy {
        roundedRect(ColorUtils.setAlphaComponent(Color.GRAY, SELECTION_ALPHA)).also {
            it.cornerRadius = cornerRadiusPx * 2f
        }
    }

    private fun drawSegment(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        color: Int
    ) {
        if (bottom <= top) return
        segmentPaint.color = color
        canvas.drawRect(left, top, right, bottom, segmentPaint)
    }

    private fun segmentHeight(value: Long): Float {
        if (value <= 0L) return 0f
        val fraction = value / maxValue
        // non-zero values get a small floor so tiny usage stays visible
        return max(barHeight * fraction, minSegmentPx)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (entries.isEmpty() || slotWidth <= 0f) return false

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> return true
            android.view.MotionEvent.ACTION_UP -> {
                val index =
                    ((event.x - gridStartX) / slotWidth).toInt()
                if (index < 0 || index >= entries.size) return true
                selectedIndex = if (selectedIndex == index) -1 else index
                invalidate()
                onEntrySelected?.invoke(entries.getOrNull(selectedIndex))
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        // slot and bar sizing (dp); slots never exceed the max so a handful of
        // entries do not stretch into oversized bars; the bar is a slim pill —
        // its corner radius is half its width, giving curved top and bottom
        private const val MAX_SLOT_DP = 40f
        private const val BAR_WIDTH_DP = 10f
        private const val SLOT_GAP_DP = 3f
        private const val ICON_SIZE_DP = 14f
        private const val ICON_GAP_DP = 5f
        private const val MIN_SEGMENT_DP = 2f

        // monochrome alpha steps (0-255); light themes need stronger alphas to
        // read on white surfaces, mirroring the activity wall palette
        private const val TRACK_ALPHA_LIGHT = 0x1F
        private const val TRACK_ALPHA_DARK = 0x24
        private const val UNMETERED_ALPHA_LIGHT = 0xE6
        private const val UNMETERED_ALPHA_DARK = 0xCC
        private const val METERED_ALPHA_LIGHT = 0x73
        private const val METERED_ALPHA_DARK = 0x52
        private const val BLOCKED_ALPHA_LIGHT = 0xE6
        private const val BLOCKED_ALPHA_DARK = 0xCC

        private const val SELECTION_ALPHA = 0x1A

        // app icon opacity (0.90 on a 0-255 scale)
        private const val ICON_ALPHA = 230
    }
}
