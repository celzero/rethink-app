/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.celzero.bravedns.ui.compose.about

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import com.celzero.bravedns.ui.compose.themeColor

/** Same platform recognizer as the original TextView's OnTouchListener, without a View.
 * Only recognized taps are consumed. Down, movement and long-press events stay available
 * to Compose selection. A recognized second down must precede Text's word-selection handler.
 */
internal class AboutTokenGestureDetector(
    context: Context,
    private val onSingleTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onPerformedClick: () -> Unit
) {
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap()
            return true
        }
    })

    fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event).also { consumed ->
        if (consumed) onPerformedClick()
    }

    fun accessibilityClick() { onSingleTap(); onPerformedClick() }

    fun cancel() {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).let { event ->
            detector.onTouchEvent(event)
            event.recycle()
        }
    }
}

@Composable
fun AboutTokenContent(b: AboutState, selectionState: SelectionState = remember { SelectionState() }) {
    val context = LocalContext.current
    val gestures = remember(context, b) {
        AboutTokenGestureDetector(context, { b.tokenSingleTap() }, { b.tokenDoubleTap() }, { b.tokenTextView.performClick() })
    }
    DisposableEffect(gestures) { onDispose { gestures.cancel() } }
    SelectionContainer(selectionState) {
        Text(b.tokenTextView.text.toString(), Modifier.fillMaxWidth().alpha(.75f).testTag("about.token")
            .semantics { onClick { gestures.accessibilityClick(); true } }
            .pointerInput(gestures) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val motion = event.motionEvent ?: continue
                        if (gestures.onTouchEvent(motion)) event.changes.forEach { it.consume() }
                    }
                }
            }
            .padding(vertical = 10.dp, horizontal = 16.dp), style = utilityTextStyle(
                size = utilityDimensionSp(context, R.dimen.small_font_text_view),
                family = "monospace", lineExtraDp = 4f),
            color = themeColor(R.attr.primaryLightColorText), textAlign = TextAlign.Center)
    }
}
