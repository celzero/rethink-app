/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.home.HomeTourAnchors
import com.celzero.bravedns.ui.compose.themeColor
import kotlin.math.ceil

/** Home-only policy; nested ComposeView propagation has a pending synthetic bridge test. */
internal val LocalHomeNavigationReserved = staticCompositionLocalOf { false }

/** Keep legacy standalone spacing; the Home shell already reserves its entire navigation bar. */
@Composable
internal fun homeNavigationClearance(legacyPadding: Dp, contentGap: Dp = 0.dp): Dp =
    if (LocalHomeNavigationReserved.current) contentGap else legacyPadding

/** The destination owns only the viewport above the measured navigation bar and system strip. */
@Composable
internal fun HomeChromeLayout(
    selectedItem: Int,
    anchors: HomeTourAnchors,
    onSelect: (Int) -> Unit,
    bottomInsets: WindowInsets,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(themeColor(R.attr.background))) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("root_content_viewport")
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .consumeWindowInsets(bottomInsets)) {
            CompositionLocalProvider(LocalHomeNavigationReserved provides true) { content() }
        }
        HomeBottomNavigation(selectedItem, anchors, windowInsets = bottomInsets, onSelect = onSelect)
    }
}

/** Geometry already reserved by the parent must not be added again as child/FAB padding. */
internal fun navigationOverlapBottomPx(viewportBottomPx: Int, navigationTopPx: Float): Int =
    ceil((viewportBottomPx - navigationTopPx).coerceAtLeast(0f)).toInt()
