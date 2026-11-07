/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.bottomsheet.RpnStatsBottomSheet
import com.celzero.bravedns.ui.compose.home.HomeNode
import com.celzero.bravedns.ui.compose.rpn.RpnSignature
import com.celzero.bravedns.ui.compose.rpn.RpnSignatureState
import com.celzero.bravedns.ui.compose.themeColor

class RpnStatRows(context: Context) : HomeNode(context) { var adapter by mutableStateOf<RpnStatsBottomSheet.TopAppsAdapter?>(null) }
class RpnStatisticsPresentation(context: Context) {
    val rpnStatsProgress = HomeNode(context)
    val rpnStatsContent = HomeNode(context).apply { visibility = View.GONE }
    val rpnStatsError = HomeNode(context).apply { visibility = View.GONE }
    val rpnStatsBlockedCard = HomeNode(context)
    val rpnStatsViewLogs = HomeNode(context)
    val rpnStatsRx = HomeNode(context)
    val rpnStatsTx = HomeNode(context)
    val rpnStatsConnCount = HomeNode(context)
    val rpnStatsBlockedCount = HomeNode(context)
    val rpnStatsAppCount = HomeNode(context)
    val rpnStatsTopApps = RpnStatRows(context)
    val rpnStatsTopAppsEmpty = HomeNode(context).apply { visibility = View.GONE }
    val dolphinSignature = RpnSignatureState()
}

@Composable
fun RpnStatisticsContent(b: RpnStatisticsPresentation) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp).testTag("rpn_statistics")) {
        HorizontalDivider(Modifier.width(40.dp).align(Alignment.CenterHorizontally).padding(bottom = 14.dp), thickness = 4.dp)
        Text(stringResource(R.string.rpn_stats_top_apps), style = MaterialTheme.typography.titleMedium)
        if (b.rpnStatsProgress.isVisible) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp).height(3.dp).testTag("rpn_stats_loading"))
        if (b.rpnStatsContent.isVisible) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RpnMetric(b.rpnStatsRx.text.toString(), R.string.rpn_stats_downloaded, true)
                RpnMetric(b.rpnStatsTx.text.toString(), R.string.rpn_stats_uploaded, true)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RpnMetric(b.rpnStatsConnCount.text.toString(), R.string.rpn_stats_connections, false)
                if (b.rpnStatsBlockedCard.isVisible) RpnMetric(b.rpnStatsBlockedCount.text.toString(), R.string.lbl_blocked, false)
                RpnMetric(b.rpnStatsAppCount.text.toString(), R.string.apps_info_title, false)
            }
            if (b.rpnStatsTopApps.isVisible) b.rpnStatsTopApps.adapter?.let { adapter -> repeat(adapter.itemCount) { adapter.Row(it) } }
            if (b.rpnStatsTopAppsEmpty.isVisible) Text(stringResource(R.string.rpn_stats_empty), Modifier.padding(top = 4.dp).alpha(.7f).testTag("rpn_stats_empty"), style = MaterialTheme.typography.bodySmall)
            if (b.rpnStatsViewLogs.isVisible) OriginalButton("bottomsheet_rpn_stats:rpn_stats_view_logs", onClick = { b.rpnStatsViewLogs.performClick() }, modifier = Modifier.fillMaxWidth().padding(top = 26.dp).heightIn(min = 48.dp).alpha(.75f).testTag("rpn_stats_logs")) {
                Text(stringResource(R.string.rpn_stats_view_logs))
            }
        }
        if (b.rpnStatsError.isVisible) Text(stringResource(R.string.rpn_stats_no_active_proxy), Modifier.padding(20.dp).alpha(.7f))
        RpnSignature(b.dolphinSignature)
    }
}
@Composable
private fun RowScope.RpnMetric(value: String, label: Int, large: Boolean) {
    Surface(Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = themeColor(R.attr.colorSurfaceContainer)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
            Text(stringResource(label), Modifier.alpha(.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
