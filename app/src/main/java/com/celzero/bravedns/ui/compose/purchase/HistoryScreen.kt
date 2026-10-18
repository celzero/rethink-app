/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase
import com.celzero.bravedns.ui.compose.controls.OriginalChip
import com.celzero.bravedns.ui.compose.controls.OriginalButton
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import com.celzero.bravedns.ui.compose.controls.OriginalCardSurface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import android.graphics.Typeface
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.ui.compose.themeColor
internal class HistoryScreenState {
    val tvHeroSubtitle = RpnControlState()
    val tvEntryCount = RpnControlState()
    val shimmer = RpnControlState()
    val emptyState = RpnControlState().apply { isVisible = false }
    val errorState = RpnControlState().apply { isVisible = false }
    val rvHistory = RpnControlState().apply { isVisible = false }
    val rvOrders = RpnControlState().apply { isVisible = false }
    val tvEmptySubtitle = RpnControlState()
    val tvErrorMessage = RpnControlState()
    val btnRetry = RpnControlState()
    val chipEntitlement = RpnControlState()
    val chipPaymentHistory = RpnControlState()
    val dolphinSignature = RpnSignatureState()
    val fetchProgress = RpnControlState().apply { isVisible = false }
    var fetchTarget by mutableFloatStateOf(0f)
    var listEpoch by mutableIntStateOf(0)
    var shimmerRunning by mutableStateOf(false)
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun HistoryScreen(state: HistoryScreenState, server: Boolean, count: Int, key: (Int) -> Any,
    onBack: () -> Unit, onFetchHidden: () -> Unit, row: @Composable (Int) -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val alpha = remember { Animatable(1f) }
    val fetchAlpha = remember { Animatable(0f) }
    LaunchedEffect(state.listEpoch) { if (state.listEpoch > 0) { alpha.snapTo(0f); alpha.animateTo(1f, tween(if (server) 280 else 300)) } }
    LaunchedEffect(state.fetchTarget) {
        fetchAlpha.animateTo(state.fetchTarget, tween(if (state.fetchTarget == 1f) 200 else 400))
        if (state.fetchTarget == 0f) onFetchHidden()
    }
    Scaffold(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection), containerColor = themeColor(R.attr.background), topBar = {
        Column {
            LargeTopAppBar(modifier = Modifier.rpnHeroBackground(), colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent), title = {
                Column {
                    Text(stringResource(if (server) R.string.server_order_title else R.string.payment_history_title))
                    if (scroll.state.collapsedFraction < .99f) HistoryText(state.tvHeroSubtitle,
                        Modifier.alpha(.7f * (1f - scroll.state.collapsedFraction)),
                        fontSize = if (server) rpnDimensionSp(R.dimen.mini_font_text_view) else 13.sp,
                        fontFamilyName = if (server) "monospace" else "sans-serif",
                        color = themeColor(if (server) R.attr.homeScreenHeaderTextColor else R.attr.primaryTextColor), maxLines = 1)
                }
            }, expandedHeight = 180.dp, scrollBehavior = scroll)
            if (state.fetchProgress.isVisible) com.celzero.bravedns.ui.compose.controls.OriginalLinearProgress(
                "activity_server_order_history:fetch_progress", Modifier.fillMaxWidth().alpha(fetchAlpha.value), indeterminate = true)
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The summary/actions stay above one shared, weighted history card, as in both XML hosts.
            Column(Modifier.fillMaxSize().padding(bottom = 100.dp)) {
                if (server) FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    OriginalChip("activity_server_order_history:chip_entitlement", selected = false,
                        onClick = { state.chipEntitlement.performClick() }, selectable = false,
                        label = { Text(stringResource(R.string.entitlement)) })
                    OriginalChip("activity_server_order_history:chip_payment_history", selected = false,
                        onClick = { state.chipPaymentHistory.performClick() }, selectable = false,
                        label = { Text(stringResource(R.string.payment_history_title)) })
                } else HistorySummary(state)
                OriginalCardSurface(Modifier.fillMaxWidth().weight(1f).padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    cornerRadius = 16.dp, border = BorderStroke(0.dp, Color.Transparent),
                    containerColor = if (server) themeColor(R.attr.background) else null) {
                    Box(Modifier.fillMaxSize()) {
                        if (state.shimmer.isVisible) {
                            if (server) RpnServerLoadingRows(count = 4, running = state.shimmerRunning,
                                modifier = Modifier.padding(vertical = 8.dp), durationMillis = 1500,
                                baseAlpha = .85f, highlightAlpha = .35f)
                            else PurchaseHistoryLoadingRows(state.shimmerRunning)
                        }
                        if (state.emptyState.isVisible) HistoryEmpty(state, server)
                        if (state.errorState.isVisible) HistoryError(state, server)
                        if ((if (server) state.rvOrders else state.rvHistory).isVisible) {
                            LazyColumn(Modifier.fillMaxSize().alpha(alpha.value), state = listState,
                                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
                                items(count, key = key) { index -> row(index) }
                            }
                        }
                    }
                }
            }
            RpnSignature(state.dolphinSignature, Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 12.dp).alpha(if (server) 1f else .8f))
        }
    }
}

@Composable private fun HistorySummary(state: HistoryScreenState) {
    OriginalCardSurface(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        cornerRadius = 16.dp, border = BorderStroke(0.dp, Color.Transparent),
        containerColor = themeColor(R.attr.chipBgColorPositive)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_rethink_plus), null, Modifier.size(36.dp).padding(4.dp), tint = themeColor(R.attr.accentGood))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                HistoryStaticText(stringResource(R.string.payment_history_summary_title), fontSize = 14,
                    color = themeColor(R.attr.primaryTextColor), bold = true)
                HistoryStaticText(stringResource(R.string.payment_history_summary_subtitle), Modifier.padding(top = 2.dp), fontSize = 12)
            }
            HistoryText(state.tvEntryCount, Modifier.widthIn(min = 32.dp), fontSize = 22.sp,
                color = themeColor(R.attr.accentGood), fontWeight = FontWeight.Bold, fontFamilyName = "sans-serif-medium", textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun HistoryEmpty(state: HistoryScreenState, server: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(if (server) R.drawable.ic_rethink_plus_sparkle else R.drawable.ic_rethink_plus), null,
            Modifier.size(72.dp).alpha(.5f), tint = themeColor(R.attr.primaryLightColorText))
        HistoryStaticText(stringResource(if (server) R.string.server_order_empty_title else R.string.payment_history_empty_title),
            Modifier.padding(top = 16.dp), fontSize = 16, color = themeColor(R.attr.primaryTextColor), bold = true, align = TextAlign.Center)
        HistoryStaticText(if (server && state.tvEmptySubtitle.text.isNotEmpty()) state.tvEmptySubtitle.text.toString()
            else stringResource(if (server) R.string.server_order_empty_subtitle else R.string.payment_history_empty_subtitle),
            Modifier.padding(top = 8.dp), fontSize = 13, align = TextAlign.Center)
    }
}

@Composable private fun HistoryError(state: HistoryScreenState, server: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(if (server) R.drawable.ic_cloud_error else R.drawable.ic_cross), null,
            Modifier.size(64.dp).alpha(.7f), tint = themeColor(R.attr.accentBad))
        HistoryText(state.tvErrorMessage, Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 14.sp, textAlign = TextAlign.Center)
        OriginalButton(if (server) "activity_server_order_history:btn_retry" else "activity_purchase_history:btn_retry",
            { state.btnRetry.performClick() }, Modifier.padding(top = 16.dp).testTag("history_retry")) { Text(stringResource(R.string.ping_test_again)) }
    }
}

@Composable private fun HistoryStaticText(text: String, modifier: Modifier = Modifier, fontSize: Int,
    color: androidx.compose.ui.graphics.Color = themeColor(R.attr.primaryLightColorText), bold: Boolean = false, align: TextAlign = TextAlign.Start) {
    Text(text, modifier, color = color, style = utilityTextStyle(size = fontSize.toFloat(),
        family = if (bold) "sans-serif-medium" else "sans-serif", weight = if (bold) Typeface.BOLD else Typeface.NORMAL,
        spacing = 0f).copy(textAlign = align))
}
