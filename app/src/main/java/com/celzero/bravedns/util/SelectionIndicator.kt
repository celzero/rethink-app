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
package com.celzero.bravedns.util

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Trailing selection indicator for single-choice DNS endpoint rows: a static
 * primary-text-colored ring while unselected and an accent "switch" once
 * selected. The swap is a subtle scale+fade (~150-200ms).
 *
 * The requested state is tracked in the pill view's tag so RecyclerView
 * rebinding during recycling can never leave a stale end-action showing the
 * wrong state.
 */
class SelectionIndicator(private val orbital: View, private val pill: View) {

    companion object {
        private const val SWAP_IN_MS = 180L
        private const val SWAP_OUT_MS = 160L
        private const val PILL_SHRINK_SCALE = 0.6f

        private const val TAG_SELECTED = "selected"
        private const val TAG_UNSELECTED = "unselected"
    }

    fun update(selected: Boolean) {
        val targetTag = if (selected) TAG_SELECTED else TAG_UNSELECTED
        if (pill.tag == targetTag) return

        pill.animate().cancel()
        orbital.animate().cancel()
        pill.tag = targetTag

        if (selected) {
            hideOrbital()
            showPill()
        } else {
            hidePill()
            showOrbital()
        }
    }

    private fun showPill() {
        pill.alpha = 0f
        pill.scaleX = PILL_SHRINK_SCALE
        pill.scaleY = PILL_SHRINK_SCALE
        pill.visibility = View.VISIBLE
        pill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SWAP_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hidePill() {
        if (pill.visibility != View.VISIBLE) return
        pill.animate()
            .alpha(0f)
            .scaleX(PILL_SHRINK_SCALE)
            .scaleY(PILL_SHRINK_SCALE)
            .setDuration(SWAP_OUT_MS)
            .withEndAction {
                if (pill.tag == TAG_UNSELECTED) {
                    pill.visibility = View.GONE
                }
            }
            .start()
    }

    private fun showOrbital() {
        orbital.alpha = 0f
        orbital.visibility = View.VISIBLE
        orbital.animate().alpha(1f).setDuration(SWAP_IN_MS).start()
    }

    private fun hideOrbital() {
        if (orbital.visibility != View.VISIBLE) return
        orbital.animate()
            .alpha(0f)
            .setDuration(SWAP_OUT_MS)
            .withEndAction {
                if (pill.tag == TAG_SELECTED) {
                    orbital.visibility = View.GONE
                }
            }
            .start()
    }
}
