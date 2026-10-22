/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.celzero.bravedns.ui.compose.rpnconfig

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** AppBarLayout permits dragging its header while the scrolling child cannot scroll upward. */
@Composable
internal fun Modifier.rpnHeaderDrag(behavior: TopAppBarScrollBehavior, childAtTop: Boolean): Modifier {
    val scope = rememberCoroutineScope()
    var settling by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(behavior) { onDispose { settling?.cancel() } }
    return pointerInput(behavior, childAtTop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!childAtTop) return@awaitEachGesture
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var overSlop = 0f
            // Do not consume the down/tap: refresh/copy child clicks must remain actionable.
            val first = awaitVerticalTouchSlopOrCancellation(down.id) { change, amount ->
                change.consume(); overSlop = amount
            } ?: return@awaitEachGesture
            settling?.cancel()
            velocity.addPosition(first.uptimeMillis, first.position)
            behavior.state.heightOffset += overSlop
            val completed = verticalDrag(first.id) { change ->
                velocity.addPosition(change.uptimeMillis, change.position)
                behavior.state.heightOffset += change.positionChange().y
                change.consume()
            }
            // Cancellation must not be converted into a release/fling or a child click.
            if (completed) settling = scope.launch {
                behavior.nestedScrollConnection.onPostFling(Velocity.Zero, Velocity(0f, velocity.calculateVelocity().y))
            }
        }
    }
}
