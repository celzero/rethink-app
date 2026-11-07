/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import androidx.compose.runtime.*
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.ui.bottomsheet.SummaryStatsFilterBottomSheet.SectionFilter
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment.SummaryStatisticsType as Type
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel.TimeCategory

internal class StatisticsSection {
    var visible by mutableStateOf(false)
    var items by mutableStateOf<List<AppConnection>>(emptyList())
    var presenter by mutableStateOf<SummaryStatisticsAdapter?>(null)
}
internal class SummaryStatisticsState {
    var alpha by mutableStateOf(false)
    var mode by mutableStateOf(StatsViewMode.INSIGHTS)
    var timeCategory by mutableStateOf(TimeCategory.ONE_HOUR)
    var providersAvailable by mutableStateOf(false)
    var dnsActive by mutableStateOf(false)
    var firewallActive by mutableStateOf(false)
    var filters by mutableStateOf(SectionFilter.entries.toSet())
    var listUsage by mutableStateOf<DataUsageSummary?>(null)
    var insightUsage by mutableStateOf<DataUsageSummary?>(null)
    val sections = Type.entries.associateWith { StatisticsSection() }
    var closeDialog by mutableStateOf(false)
    var onMode: (StatsViewMode) -> Unit = {}
    var onTimeCategory: (TimeCategory) -> Unit = {}
    var onFilter: () -> Unit = {}
    var onDetail: (Type) -> Unit = {}
    var onClose: () -> Unit = {}
    var onConfirmClose: () -> Unit = {}
}
internal val summarySectionOrder = listOf(Type.TOP_ACTIVE_CONNS, Type.MOST_CONNECTED_APPS, Type.MOST_BLOCKED_APPS,
    Type.MOST_CONTACTED_COUNTRIES, Type.MOST_CONNECTED_ASN, Type.MOST_BLOCKED_ASN, Type.MOST_BLOCKED_BLOCKLISTS,
    Type.MOST_CONTACTED_DOMAINS, Type.MOST_BLOCKED_DOMAINS, Type.MOST_CONTACTED_IPS, Type.MOST_BLOCKED_IPS)
internal fun statisticsTitle(type: Type): Int = when (type) {
    Type.TOP_ACTIVE_CONNS -> R.string.top_active_conns
    Type.MOST_CONNECTED_APPS -> R.string.ssv_app_network_activity_heading
    Type.MOST_BLOCKED_APPS -> R.string.ssv_app_blocked_heading
    Type.MOST_CONNECTED_ASN -> R.string.most_contacted_asn
    Type.MOST_BLOCKED_ASN -> R.string.most_blocked_asn
    Type.MOST_CONTACTED_DOMAINS -> R.string.ssv_most_contacted_domain_heading
    Type.MOST_BLOCKED_DOMAINS -> R.string.ssv_most_blocked_domain_heading
    Type.MOST_CONTACTED_IPS -> R.string.ssv_most_contacted_ips_heading
    Type.MOST_BLOCKED_IPS -> R.string.ssv_most_blocked_ips_heading
    Type.MOST_CONTACTED_COUNTRIES -> R.string.ssv_most_contacted_countries_heading
    Type.MOST_BLOCKED_BLOCKLISTS -> R.string.ssv_blocklists_heading
}
internal fun statisticsFilter(type: Type): SectionFilter = when (type) {
    Type.TOP_ACTIVE_CONNS, Type.MOST_CONNECTED_APPS, Type.MOST_BLOCKED_APPS -> SectionFilter.APPS
    Type.MOST_CONNECTED_ASN, Type.MOST_BLOCKED_ASN -> SectionFilter.PROVIDERS
    Type.MOST_CONTACTED_DOMAINS, Type.MOST_BLOCKED_DOMAINS -> SectionFilter.DOMAINS
    Type.MOST_CONTACTED_IPS, Type.MOST_BLOCKED_IPS -> SectionFilter.IPS
    Type.MOST_CONTACTED_COUNTRIES -> SectionFilter.COUNTRIES
    Type.MOST_BLOCKED_BLOCKLISTS -> SectionFilter.BLOCKLISTS
}
internal fun SummaryStatisticsState.sectionAvailable(type: Type): Boolean = statisticsFilter(type) in filters && when (statisticsFilter(type)) {
    SectionFilter.APPS -> true
    SectionFilter.PROVIDERS -> providersAvailable
    SectionFilter.DOMAINS, SectionFilter.BLOCKLISTS -> dnsActive
    SectionFilter.COUNTRIES, SectionFilter.IPS -> firewallActive
}
internal fun statisticsMetric(item: AppConnection, type: Type): Long =
    if (type == Type.MOST_CONNECTED_APPS) (item.downloadBytes ?: 0L) + (item.uploadBytes ?: 0L) else item.count.toLong()
internal fun statisticsFilterBadge(selected: Set<SectionFilter>, providers: Boolean): Int? {
    val eligible = if (providers) SectionFilter.entries else SectionFilter.entries - SectionFilter.PROVIDERS
    val count = eligible.count { it in selected }
    return count.takeUnless { it == eligible.size }
}
internal fun selectStatisticsTags(tags: List<String>, selected: Set<String>, clicked: String): Set<String> {
    val changed = if (clicked in selected) selected - clicked else selected + clicked
    val effective = changed.ifEmpty { selected }
    return tags.filter { it in effective }.toSet()
}
