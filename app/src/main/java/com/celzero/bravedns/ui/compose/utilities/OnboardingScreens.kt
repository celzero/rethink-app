/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import kotlinx.coroutines.flow.distinctUntilChanged

/** ViewPager's currentItem changes synchronously, before its scroll animation settles. */
class WelcomeUiState(initial: Int = 0) {
    var currentItem by mutableIntStateOf(initial.coerceIn(0, 3))
    var requestVersion by mutableIntStateOf(0)
    var pending by mutableStateOf(false)
    fun next(finish: () -> Unit) {
        if (currentItem + 1 >= 4) finish()
        else { currentItem++; pending = true; requestVersion++ }
    }
}

@Composable
fun WelcomeScreen(state: WelcomeUiState, finish: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = state.currentItem) { 4 }
    LaunchedEffect(state.requestVersion) {
        if (!state.pending) return@LaunchedEffect
        val request = state.requestVersion
        try { pager.animateScrollToPage(state.currentItem) }
        finally { if (request == state.requestVersion) { state.currentItem = pager.currentPage; state.pending = false } }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { if (!state.pending) state.currentItem = it }
    }
    val images = listOf(R.drawable.ic_launcher, R.drawable.ic_wireguard_welcome, R.drawable.ic_firewall_welcome, R.drawable.ic_dns_welcome)
    val titles = listOf(R.string.slide_2_title, R.string.wireguard_title, R.string.firewall_title, R.string.dns_title)
    val descriptions = listOf(R.string.slide_2_desc, R.string.wireguard_desc, R.string.firewall_desc, R.string.dns_desc)
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(themeColor(R.attr.colorSurfaceVariant), themeColor(com.google.android.material.R.attr.colorSurface)))).safeDrawingPadding()) {
        HorizontalPager(pager, Modifier.weight(1f).testTag("welcome.pager")) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(80.dp))
                utilityImage(images[page], Modifier.size(if (page == 0) 120.dp else 200.dp, if (page == 0) 120.dp else 180.dp), stringResource(titles[page]))
                Text(stringResource(titles[page]), Modifier.padding(top = 50.dp).semantics { heading() },
                    style = utilityTextStyle(resolveUtilityStyle(context, com.google.android.material.R.attr.textAppearanceHeadlineMedium), weight = android.graphics.Typeface.BOLD),
                    color = themeColor(R.attr.colorOnSurface), textAlign = TextAlign.Center)
                Text(stringResource(descriptions[page]), Modifier.padding(top = 20.dp, bottom = 24.dp),
                    style = utilityTextStyle(resolveUtilityStyle(context, com.google.android.material.R.attr.textAppearanceBodyLarge)),
                    color = themeColor(R.attr.primaryTextColor), textAlign = TextAlign.Center)
            }
        }
        HorizontalDivider(color = themeColor(R.attr.divider))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.currentItem < 3) OriginalButton("activity_welcome:btn_skip", finish, Modifier.testTag("welcome.skip")) { Text(stringResource(R.string.skip), color = themeColor(R.attr.primaryLightColorText)) }
            else Spacer(Modifier.width(64.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                repeat(4) { index -> Box(Modifier.padding(horizontal = 4.dp).size(8.dp).background(themeColor(R.attr.svgStrokeColor).copy(alpha = if (state.currentItem == index) 1f else 80f/255), CircleShape)
                    .semantics { selected = state.currentItem == index; contentDescription = "${index + 1} / 4" }) }
            }
            OriginalButton("activity_welcome:btn_next", { state.next(finish) }, Modifier.testTag("welcome.next")) { Text(stringResource(if (state.currentItem == 3) R.string.finish else R.string.next)) }
        }
    }
}

@Composable fun AppLockScreen() {
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            utilityImage(R.drawable.ic_launcher, Modifier.size(108.dp), stringResource(R.string.app_name))
        }
    }
}

/** Alerts' baseline controller and adapter are disabled; retain the empty visible surface. */
@Composable fun AlertsScreen() { Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {} }
