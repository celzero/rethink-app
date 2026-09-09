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
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
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
 */
class EmbeddedDolphinSignature @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val dolphinView: AppCompatImageView
    private val quoteView: AppCompatTextView

    /** The pairing currently rendered; drives tap-to-cycle. */
    private var current: EmbeddedDolphinContent.Signature? = null

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
            // artwork + quote
            setOnClickListener {
                val cur = current ?: return@setOnClickListener
                setContent(EmbeddedDolphinContent.next(cur))
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

    /**
     * Shows the given dolphin artwork above the given quote. Any dolphin can
     * be paired with any quote; both are rendered independently. Tapping has
     * no cycle target for this manual variant; prefer [setContent] with a
     * [EmbeddedDolphinContent.Signature].
     */
    fun setContent(@DrawableRes image: Int, quote: String) {
        current = null
        dolphinView.setImageResource(image)
        quoteView.text = quote
    }

    /** Renders [signature] and lets taps cycle to its successor. */
    fun setContent(signature: EmbeddedDolphinContent.Signature) {
        current = signature
        dolphinView.setImageResource(signature.image)
        quoteView.text = context.getString(signature.quote)
    }

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
    }
}
