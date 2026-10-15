package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

// Shimmer 0.5.0 has no public getters for these defaults. Preserve the original
// subscription banner's unconfigured alpha mask: base 0x4cffffff, highlight WHITE.
// https://github.com/facebookarchive/shimmer-android/blob/5ec9d4e73ef84696e6807488d7663a5d5ccf766d/shimmer/src/main/java/com/facebook/shimmer/Shimmer.java
private const val SHIMMER_050_DURATION_MILLIS = 1000
private const val SHIMMER_050_BASE_ALPHA = 0x4c / 255f
private const val SHIMMER_050_HIGHLIGHT_ALPHA = 1f

/** fragment_server_selection: small left bar and end-aligned action placeholder. */
@Composable internal fun RpnHeaderLoadingSkeleton(running: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)
        .then(sourceShimmerModifier(running, 1200, .12f, .35f)), verticalAlignment = Alignment.CenterVertically) {
        HeaderPlaceholder(Modifier.width(80.dp).height(10.dp))
        Spacer(Modifier.weight(1f).height(10.dp))
        HeaderPlaceholder(Modifier.width(60.dp).height(26.dp))
    }
}

/** This source ShimmerFrameLayout has no XML overrides: preserve the pinned 0.5.0 defaults. */
@Composable internal fun RpnSubscriptionLoadingSkeleton(running: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(40.dp)
        .then(sourceShimmerModifier(running, SHIMMER_050_DURATION_MILLIS, SHIMMER_050_BASE_ALPHA, SHIMMER_050_HIGHLIGHT_ALPHA))
        .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderPlaceholder(Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        HeaderPlaceholder(Modifier.weight(1f).height(12.dp))
    }
}

@Composable private fun HeaderPlaceholder(modifier: Modifier) {
    Box(modifier.background(themeColor(R.attr.primaryTextColor), RoundedCornerShape(12.dp)))
}
