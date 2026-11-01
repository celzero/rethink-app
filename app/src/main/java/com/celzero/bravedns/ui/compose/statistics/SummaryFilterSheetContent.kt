/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.bottomsheet.SummaryStatsFilterBottomSheet.SectionFilter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SummaryFilterSheetContent(selection: SummaryFilterSelection, providers: Boolean, onApply: () -> Unit) {
    Surface {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
            HorizontalDivider(Modifier.width(60.dp).align(Alignment.CenterHorizontally).padding(bottom = 14.dp), thickness = 3.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_filter), null, Modifier.size(22.dp))
                Text(stringResource(R.string.ssv_filter_heading), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                OriginalButton("bottom_sheet_summary_stats_filter:ssfc_select_all", onClick = selection::selectAll, modifier = Modifier.testTag("stats_filter_select_all")) { Text(stringResource(R.string.lbl_select_all)) }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionFilter.entries.forEach { filter ->
                    if (filter != SectionFilter.PROVIDERS || providers) {
                        val label = when (filter) {
                            SectionFilter.APPS -> R.string.apps_info_title
                            SectionFilter.COUNTRIES -> R.string.ssv_filter_countries
                            SectionFilter.PROVIDERS -> R.string.ssv_filter_providers
                            SectionFilter.BLOCKLISTS -> R.string.ssv_filter_blocklist
                            SectionFilter.DOMAINS -> R.string.ssv_filter_domains
                            SectionFilter.IPS -> R.string.ssv_filter_ips
                        }
                        OriginalChip(statisticsFilterSource(filter), selected = filter in selection.selected, onClick = { selection.toggle(filter) }, label = { Text(stringResource(label)) }, modifier = Modifier.padding(3.dp).testTag("stats_filter_${filter.tid}"))
                    }
                }
            }
            OriginalButton("bottom_sheet_summary_stats_filter:ssfc_apply", onClick = onApply, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("stats_filter_apply")) { Text(stringResource(R.string.lbl_apply)) }
        }
    }
}

internal fun statisticsFilterSource(filter: SectionFilter): String = "bottom_sheet_summary_stats_filter:" + when (filter) {
    SectionFilter.APPS -> "fss_filter_apps_chip"
    SectionFilter.COUNTRIES -> "fss_filter_countries_chip"
    SectionFilter.PROVIDERS -> "fss_filter_providers_chip"
    SectionFilter.BLOCKLISTS -> "fss_filter_blocklist_chip"
    SectionFilter.DOMAINS -> "fss_filter_domains_chip"
    SectionFilter.IPS -> "fss_filter_ips_chip"
}
