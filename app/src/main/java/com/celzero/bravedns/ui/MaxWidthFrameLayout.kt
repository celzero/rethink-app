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
package com.celzero.bravedns.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A [FrameLayout] that caps its own measured width to [maxWidthDp] and centers its child
 * horizontally when the parent offers more space than the cap.
 *
 * This is the documented pattern for limiting content width on large screens (foldables in
 * the open state, tablets): the view measures itself with an exact max-width spec instead of
 * mutating padding on system containers, so it is deterministic at measure time and has no
 * interaction with insets dispatch or layout-change timing.
 *
 * Width is recalculated on every measure pass, so fold/unfold and split-screen resizes are
 * handled automatically without listeners.
 */
class MaxWidthFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Maximum width of the content, in dp. */
    var maxWidthDp: Int = DEFAULT_MAX_WIDTH_DP

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSpec = capWidthSpec(widthMeasureSpec)
        super.onMeasure(widthSpec, heightMeasureSpec)
    }

    private fun capWidthSpec(widthMeasureSpec: Int): Int {
        val density = resources.displayMetrics.density
        val maxWidthPx = (maxWidthDp * density).toInt()
        val available = MeasureSpec.getSize(widthMeasureSpec)
        if (available <= maxWidthPx) return widthMeasureSpec
        // Parent offers more than the cap: measure at exactly the cap. The child's
        // layout_gravity is CENTER_HORIZONTAL, so FrameLayout centers it in the leftover
        // space and the window background shows through on both sides.
        return MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
    }

    companion object {
        /** Matches the widest common phone width (~411dp). */
        const val DEFAULT_MAX_WIDTH_DP = 600
    }
}
