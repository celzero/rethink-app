/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.ui.compose.themeColor

@Composable fun WgStatsContent(b: WgStatsState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy((-1).dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
                WgToggle(b.tbRecentToggleBtn, source = "fragment_wg_nw_stats:tb_recent_toggle_btn"); WgToggle(b.tbDailyToggleBtn, source = "fragment_wg_nw_stats:tb_daily_toggle_btn"); WgToggle(b.tbWeeklyToggleBtn, source = "fragment_wg_nw_stats:tb_weekly_toggle_btn")
            }
            WgText(b.tbLogsDisabledTv, Modifier.padding(16.dp))
            WgCard(b.tbStatsCard, Modifier.padding(horizontal = 12.dp), contentPadding = PaddingValues(20.dp)) {
                val accent = themeColor(androidx.appcompat.R.attr.colorAccent)
                WgProgress(b.fssProgressBar, Modifier.fillMaxWidth().padding(5.dp))
                FlowRow(horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Box(Modifier.padding(end = 10.dp).size(8.dp).background(accent, CircleShape)); WgText(b.fssUnmeteredDataUsage) }
                    WgText(b.fssTotalDataUsage)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Box(Modifier.padding(end = 10.dp).size(8.dp).background(accent.copy(alpha = .5f), CircleShape)); WgText(b.fssMeteredDataUsage) }
            }
            b.statsRecyclerView.adapter?.Content(Modifier.weight(1f))
        }
    }
}
