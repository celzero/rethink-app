/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import kotlinx.coroutines.isActive

internal class PurchaseOceanState(val renderer: DolphinOceanRenderer) { var frame by mutableLongStateOf(0L) }
@Composable internal fun rememberPurchaseOcean(running: Boolean, epoch: Int): PurchaseOceanState {
    val density = LocalDensity.current.density
    val renderer = remember(density) { DolphinOceanRenderer(density) }
    val state = remember(renderer) { PurchaseOceanState(renderer) }
    LaunchedEffect(renderer, running, epoch) {
        if (running) {
            renderer.start()
            try { while (isActive) withFrameNanos { state.frame = it } }
            finally { renderer.stop() }
        } else renderer.stop()
    }
    DisposableEffect(renderer) { onDispose { renderer.stop() } }
    return state
}
@Composable internal fun PurchaseOcean(state: PurchaseOceanState, modifier: Modifier = Modifier) {
    Canvas(modifier.onSizeChanged { state.renderer.resize(it.width, it.height) }) {
        state.frame
        state.renderer.draw(drawContext.canvas.nativeCanvas)
    }
}
@Composable internal fun MoneyBackBadge(days: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember(context) { MoneyBackBadgeRenderer(context) }
    val description = "$days ${stringResource(R.string.days)} ${stringResource(R.string.money_back)} ${stringResource(R.string.money_back_guarantee)}"
    Canvas(modifier.size(90.dp).alpha(.85f).semantics { contentDescription = description }) {
        renderer.setDays(days)
        renderer.draw(drawContext.canvas.nativeCanvas, size.width, size.height)
    }
}
