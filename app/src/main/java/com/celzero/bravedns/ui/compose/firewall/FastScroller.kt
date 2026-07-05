package com.celzero.bravedns.ui.compose.firewall

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun <T> IndexedFastScroller(
    items: List<T>,
    listState: LazyListState,
    getIndexKey: (T) -> String,
    modifier: Modifier = Modifier,
    minItemCount: Int = 24,
    onInteractionStart: () -> Unit = {},
    onInteractionEnd: () -> Unit = {},
    scrollItemOffset: Int = 0,
) {
    if (items.size < minItemCount) return

    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val labelToIndexMap =
        remember(items, items.hashCode()) {
            val map = mutableMapOf<String, Int>()
            items.forEachIndexed { index, item ->
                val label = normalizeIndexLabel(getIndexKey(item))
                if (!map.containsKey(label)) {
                    map[label] = index
                }
            }
            map
        }
    val labels =
        remember(labelToIndexMap) {
            labelToIndexMap.keys.sortedWith(
                compareBy<String> { if (it == "#") 0 else 1 }.thenBy { it },
            )
        }

    if (labels.isEmpty()) return

    val trackInset = 14.dp
    val bubbleSize = 72.dp
    val bubbleOffsetX = (-60).dp
    val trackInsetPx = with(density) { trackInset.toPx() }
    val bubbleSizePx = with(density) { bubbleSize.toPx() }

    var trackSize by remember { mutableStateOf(IntSize.Zero) }
    var currentDragY by remember { mutableFloatStateOf(0f) }
    var currentLabel by remember { mutableStateOf("") }
    var previousLabel by remember { mutableStateOf("") }
    var interacting by remember { mutableStateOf(false) }

    fun scrollToLabel(label: String) {
        val targetIndex = labelToIndexMap[label] ?: return
        coroutineScope.launch {
            listState.scrollToItem((targetIndex - scrollItemOffset).coerceAtLeast(0))
        }
    }

    fun selectFromPosition(y: Float) {
        if (trackSize.height <= 0 || labels.isEmpty()) return
        val trackHeight = trackSize.height.toFloat()
        val clampedY = y.coerceIn(trackInsetPx, trackHeight - trackInsetPx)
        val usableHeight = (trackHeight - (trackInsetPx * 2f)).coerceAtLeast(1f)
        val progress = ((clampedY - trackInsetPx) / usableHeight).coerceIn(0f, 1f)
        val index = (progress * (labels.size - 1)).roundToInt().coerceIn(0, labels.size - 1)
        val selected = labels[index]

        if (selected != previousLabel) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            previousLabel = selected
        }

        currentLabel = selected
        currentDragY =
            if (labels.size > 1) {
                trackInsetPx + (index / (labels.size - 1).toFloat()) * usableHeight
            } else {
                trackHeight / 2f
            }
        scrollToLabel(selected)
    }

    Box(
        modifier =
            modifier
                .width(44.dp)
                .fillMaxHeight(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = trackInset)
                    .onGloballyPositioned { trackSize = it.size }
                    .pointerInput(labels, items.hashCode()) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown()
                                interacting = true
                                onInteractionStart()
                                selectFromPosition(down.position.y)

                                val change =
                                    awaitTouchSlopOrCancellation(down.id) { pointerChange, _ ->
                                        pointerChange.consume()
                                    }

                                if (change != null) {
                                    drag(change.id) { dragChange ->
                                        selectFromPosition(dragChange.position.y)
                                    }
                                }

                                interacting = false
                                previousLabel = ""
                                onInteractionEnd()
                            }
                        }
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val usableHeight = (trackSize.height.toFloat() - (trackInsetPx * 2f)).coerceAtLeast(1f)
            val adjustedDragY = (currentDragY - trackInsetPx).coerceIn(0f, usableHeight)
            val dragProgress = if (usableHeight > 0f) adjustedDragY / usableHeight else 0f

            labels.forEachIndexed { index, label ->
                val labelPosition =
                    if (labels.size > 1) index.toFloat() / (labels.size - 1) else 0.5f
                val distance = abs(labelPosition - dragProgress)

                val scale by animateFloatAsState(
                    targetValue =
                        if (interacting) {
                            when {
                                distance < 0.06f -> 1.45f
                                distance < 0.12f -> 1.2f
                                else -> 0.95f
                            }
                        } else {
                            1f
                        },
                    animationSpec = spring(dampingRatio = 0.82f),
                    label = "fastScrollerScale_$index",
                )
                val alpha by animateFloatAsState(
                    targetValue =
                        if (interacting) {
                            when {
                                distance < 0.06f -> 1f
                                distance < 0.12f -> 0.85f
                                else -> 0.55f
                            }
                        } else {
                            0.72f
                        },
                    animationSpec = spring(),
                    label = "fastScrollerAlpha_$index",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                },
                    )
                }
            }
        }

        if (interacting && currentLabel.isNotEmpty()) {
            val bubbleYPx = (currentDragY - bubbleSizePx / 2f)
                .coerceIn(0f, (trackSize.height.toFloat() - bubbleSizePx).coerceAtLeast(0f))
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .size(bubbleSize)
                        .offset {
                            IntOffset(
                                x = with(density) { bubbleOffsetX.roundToPx() },
                                y = bubbleYPx.roundToInt(),
                            )
                        },
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun normalizeIndexLabel(raw: String): String {
    val key = raw.trim()
    if (key.isEmpty()) return "#"

    if (key.all { it.isDigit() } && key.length <= 3) {
        return key
    }

    val first = key.first().uppercaseChar()
    return if (first.isLetter()) first.toString() else "#"
}
