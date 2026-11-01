package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import com.celzero.bravedns.ui.compose.controls.OriginalCardSurface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

/** Alpha mask shared by the original ShimmerFrameLayout descendants, not a replacement block grid. */
@Composable internal fun sourceShimmerModifier(
    running: Boolean, durationMillis: Int, baseAlpha: Float, highlightAlpha: Float
): Modifier {
    val phase = if (running) {
        val value by rememberInfiniteTransition(label = "source loading shimmer").animateFloat(
            0f, 1f, infiniteRepeatable(tween(durationMillis, easing = LinearEasing)), label = "source shimmer sweep")
        value
    } else 0f
    return Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val start = (phase * 3f - 1f) * size.width
        drawRect(Brush.linearGradient(
            listOf(Color.Black.copy(alpha = baseAlpha), Color.Black.copy(alpha = highlightAlpha), Color.Black.copy(alpha = baseAlpha)),
            start = Offset(start, 0f), end = Offset(start + size.width, 0f)), blendMode = BlendMode.DstIn)
    }
}

/** list_item_shimmer_server_row: icon + two fixed-width bars + trailing square. */
@Composable internal fun RpnServerLoadingRows(
    count: Int,
    running: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int = 1200,
    baseAlpha: Float = .10f,
    highlightAlpha: Float = .30f
) {
    Column(modifier.fillMaxWidth().then(sourceShimmerModifier(running, durationMillis, baseAlpha, highlightAlpha))) {
        repeat(count) {
            OriginalCardSurface(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                cornerRadius = 16.dp, border = BorderStroke(0.dp, Color.Transparent)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceShimmerBlock(Modifier.size(36.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        SourceShimmerBlock(Modifier.width(120.dp).height(14.dp))
                        SourceShimmerBlock(Modifier.padding(top = 6.dp).width(70.dp).height(10.dp))
                    }
                    SourceShimmerBlock(Modifier.size(20.dp))
                }
            }
        }
    }
}

/** activity_purchase_history includes five vertical list_item_shimmer_card children. */
@Composable internal fun PurchaseHistoryLoadingRows(running: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)
        .then(sourceShimmerModifier(running, 1500, .85f, .35f))) {
        repeat(5) {
            OriginalCardSurface(Modifier.fillMaxWidth().padding(4.dp), cornerRadius = 16.dp,
                containerColor = themeColor(R.attr.background)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    SourceShimmerBlock(Modifier.width(72.dp).height(12.dp))
                    SourceShimmerBlock(Modifier.padding(top = 8.dp).width(96.dp).height(24.dp))
                    SourceShimmerBlock(Modifier.padding(top = 6.dp).width(80.dp).height(10.dp))
                }
            }
        }
    }
}

@Composable private fun SourceShimmerBlock(modifier: Modifier) {
    Box(modifier.background(themeColor(R.attr.primaryTextColor), RoundedCornerShape(12.dp)))
}
