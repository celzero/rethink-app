/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import java.util.Locale
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.chrome.homeNavigationClearance
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.ui.compose.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.appdetails.AppCountryMap
import com.celzero.bravedns.ui.compose.appdetails.appDonutSweeps
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment.SummaryStatisticsType as Type
import com.celzero.bravedns.ui.stats.StatsInsightsMath
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel.TimeCategory

@Composable
internal fun SummaryStatisticsScreen(state: SummaryStatisticsState) {
    Surface(color = themeColor(R.attr.background)) {
        LazyColumn(Modifier.fillMaxSize()
            .safeDrawingPadding()
            .testTag("summary_statistics"),
            contentPadding = PaddingValues(top = 15.dp, bottom = homeNavigationClearance(90.dp, contentGap = 10.dp))) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).alpha(.5f)) {
                        val appName = stringResource(if (state.alpha) R.string.app_name_alpha else R.string.app_name_small_case)
                        val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
                        Text(if (state.alpha) appName else appName.uppercase(locale),
                            Modifier.testTag("stats_header_title"), color = themeColor(R.attr.colorOnSurface),
                            style = utilityTextStyle(R.style.TextAppearance_Heading_HomeScreen))
                        Text(stringResource(R.string.about_title_desc), Modifier.testTag("stats_header_subtitle"),
                            color = themeColor(R.attr.colorOnSurface),
                            style = utilityTextStyle(R.style.TextAppearance_SubHeading_HomeScreen))
                    }
                    // The original 32dp buttons share a 1dp stroke and squared inner corners.
                    Row(Modifier.padding(start = 8.dp).heightIn(min = 48.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(-dimensionResource(R.dimen.default_stroke_width)),
                        verticalAlignment = Alignment.CenterVertically) {
                        StatsModeButton(state.mode == StatsViewMode.LIST, R.string.cd_stats_switch_to_list, "stats_mode_list") { state.onMode(StatsViewMode.LIST) }
                        StatsModeButton(state.mode == StatsViewMode.INSIGHTS, R.string.cd_stats_switch_to_insights, "stats_mode_insights") { state.onMode(StatsViewMode.INSIGHTS) }
                    }
                    Box(Modifier.padding(start = 8.dp)) {
                        val filterDescription = stringResource(R.string.ssv_filter_heading)
                        OriginalButton("fragment_summary_statistics:fss_filter_btn", onClick = state.onFilter,
                            modifier = Modifier.testTag("stats_filter").semantics { contentDescription = filterDescription }) { }
                        statisticsFilterBadge(state.filters, state.providersAvailable)?.let { count ->
                            // The button's 32dp source bounds are centered in its 48dp touch target;
                            // source badge margins are 2dp from that visual bottom/end.
                            StatsFilterBadge(count, Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp))
                        }
                    }
                }
                // Keep the original three joined 100dp segments centered, with one shared stroke.
                // LazyRow permits overflow at larger font scales without shrinking the source controls.
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("stats_time_group").selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(-dimensionResource(R.dimen.default_stroke_width), Alignment.CenterHorizontally)) {
                    items(TimeCategory.entries.size) { index ->
                        val tc = TimeCategory.entries[index]
                        val amount = when (tc) { TimeCategory.ONE_HOUR -> "1"; TimeCategory.TWENTY_FOUR_HOUR -> "24"; TimeCategory.SEVEN_DAYS -> "7" }
                        OriginalChip("fragment_summary_statistics:" + when (tc) { TimeCategory.TWENTY_FOUR_HOUR -> "tb_daily_toggle_btn"; TimeCategory.SEVEN_DAYS -> "tb_weekly_toggle_btn"; else -> "tb_recent_toggle_btn" }, segmentIndex = index, segmentCount = 3, containerColor = themeColor(if (state.timeCategory == tc) R.attr.accentGood else R.attr.defaultToggleBtnBg), contentColor = themeColor(if (state.timeCategory == tc) R.attr.homeScreenHeaderTextColor else R.attr.primaryTextColor), selected = state.timeCategory == tc, onClick = { state.onTimeCategory(tc) }, modifier = Modifier.widthIn(min = 100.dp).testTag("stats_time_${tc.value}"), label = { Text(stringResource(R.string.ci_desc, amount, stringResource(if (tc == TimeCategory.SEVEN_DAYS) R.string.lbl_day else R.string.lbl_hour))) })
                    }
                }
            }
            item { TrafficUsage(if (state.mode == StatsViewMode.INSIGHTS) state.insightUsage else state.listUsage, state.mode == StatsViewMode.INSIGHTS) }
            summarySectionOrder.forEach { type ->
                val section = state.sections.getValue(type)
                val visible = state.sectionAvailable(type) && if (state.mode == StatsViewMode.INSIGHTS) section.items.isNotEmpty() else section.visible
                if (visible) {
                    item(key = "header_${type.tid}") {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).testTag("stats_section_${type.tid}"), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(statisticsTitle(type)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (type == Type.TOP_ACTIVE_CONNS) {
                                OriginalButton("fragment_summary_statistics:" + if (state.mode == StatsViewMode.INSIGHTS) "fss_ia_close_conns_chip" else "fss_close_conns_chip",
                                    onClick = state.onClose, modifier = Modifier.testTag("stats_close_all")) { Text(stringResource(R.string.close_all)) }
                                Spacer(Modifier.width(6.dp))
                            }
                            OriginalButton(statisticsDetailSource(type, state.mode), onClick = { state.onDetail(type) }, modifier = Modifier.testTag("stats_detail_${type.tid}")) { Text(stringResource(R.string.ssv_see_more)) }
                        }
                    }
                    if (state.mode == StatsViewMode.INSIGHTS) item(key = "insights_${type.tid}") { Box(Modifier.padding(horizontal = 16.dp)) { SummaryInsights(type, section.items) } }
                    else section.presenter?.let { presenter -> items(presenter.itemCount, key = { "row_${type.tid}_$it" }) { Box(Modifier.padding(horizontal = 16.dp)) { presenter.RowAt(it) } } }
                }
            }
        }
    }
    if (state.closeDialog) AlertDialog(onDismissRequest = { state.closeDialog = false },
        title = { NoDimDialogWindow(); Text(stringResource(R.string.close_conns_dialog_title)) },
        text = { Text(stringResource(R.string.close_conns_dialog_desc, stringResource(R.string.lbl_all_apps).lowercase())) },
        confirmButton = { OriginalButton("@dialog_positive", onClick = { if (state.closeDialog) { state.closeDialog = false; state.onConfirmClose() } }, modifier = Modifier.testTag("stats_confirm_close")) { Text(stringResource(R.string.lbl_proceed)) } },
        dismissButton = { OriginalButton("@dialog_negative", onClick = { state.closeDialog = false }, modifier = Modifier.testTag("stats_cancel_close")) { Text(stringResource(R.string.lbl_cancel)) } })
}

@Composable
private fun StatsModeButton(selected: Boolean, description: Int, tag: String, onClick: () -> Unit) {
    val label = stringResource(description)
    OriginalButton("fragment_summary_statistics:" + if (tag == "stats_mode_list") "fss_view_mode_list_btn" else "fss_view_mode_insights_btn",
        onClick = { if (!selected) onClick() }, modifier = Modifier.testTag(tag).semantics { contentDescription = label },
        selected = selected, segmentIndex = if (tag == "stats_mode_list") 0 else 1, segmentCount = 2, grouped = true,
        containerColor = themeColor(if (selected) R.attr.accentGood else R.attr.defaultToggleBtnBg),
        iconTint = themeColor(if (selected) R.attr.homeScreenHeaderTextColor else R.attr.defaultToggleBtnTxt)) { }

}
/** Each action retains its own XML instance, including positive/negative chip overrides. */
internal fun statisticsDetailSource(type: Type, mode: StatsViewMode): String = "fragment_summary_statistics:" +
    if (mode == StatsViewMode.INSIGHTS) when (type) {
        Type.TOP_ACTIVE_CONNS -> "fss_ia_active_conns_chip"
        Type.MOST_CONNECTED_APPS -> "fss_ia_allowed_apps_chip"
        Type.MOST_BLOCKED_APPS -> "fss_ia_blocked_apps_chip"
        Type.MOST_CONTACTED_COUNTRIES -> "fss_ia_countries_chip"
        Type.MOST_CONNECTED_ASN -> "fss_ia_asn_allowed_chip"
        Type.MOST_BLOCKED_ASN -> "fss_ia_asn_blocked_chip"
        Type.MOST_BLOCKED_BLOCKLISTS -> "fss_ia_blocklists_chip"
        Type.MOST_CONTACTED_DOMAINS -> "fss_ia_domains_allowed_chip"
        Type.MOST_BLOCKED_DOMAINS -> "fss_ia_domains_blocked_chip"
        Type.MOST_CONTACTED_IPS -> "fss_ia_ips_allowed_chip"
        Type.MOST_BLOCKED_IPS -> "fss_ia_ips_blocked_chip"
    } else when (type) {
        Type.TOP_ACTIVE_CONNS -> "fss_active_apps_chip"
        Type.MOST_CONNECTED_APPS -> "fss_app_info_chip"
        Type.MOST_BLOCKED_APPS -> "fss_app_info_chip_second"
        Type.MOST_CONTACTED_COUNTRIES -> "fss_countries_logs_chip"
        Type.MOST_CONNECTED_ASN -> "fss_asn_chip"
        Type.MOST_BLOCKED_ASN -> "fss_asn_chip_second"
        Type.MOST_BLOCKED_BLOCKLISTS -> "fss_blocklist_chip"
        Type.MOST_CONTACTED_DOMAINS -> "fss_dns_logs_chip"
        Type.MOST_BLOCKED_DOMAINS -> "fss_dns_logs_chip_second"
        Type.MOST_CONTACTED_IPS -> "fss_network_logs_chip"
        Type.MOST_BLOCKED_IPS -> "fss_network_logs_chip_second"
    }

@Composable
private fun StatsFilterBadge(count: Int, modifier: Modifier = Modifier) {
    val badgeColor = colorResource(R.color.accentBad)
    Box(modifier.sizeIn(minWidth = 16.dp, minHeight = 16.dp)
        .drawBehind { drawOval(badgeColor) }.padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(count.toString(), color = Color.White, style = utilityTextStyle(size = 9f, weight = android.graphics.Typeface.BOLD))
    }
}

/** Mirrors the two source usage containers; changing presentation never changes the totals. */
@Composable
private fun TrafficUsage(usage: DataUsageSummary?, insights: Boolean) {
    if (usage == null) return
    val total = usage.totalDownload + usage.totalUpload
    val unmetered = total - usage.meteredDataUsage
    val metered = usage.meteredDataUsage
    val entries = listOf(R.string.ada_app_unmetered to unmetered, R.string.ada_app_metered to metered, R.string.lbl_overall to total)
    val markerColor = themeColor(R.attr.accentGood)
    val markerColors = listOf(markerColor, markerColor.copy(alpha = .5f), markerColor)
    // MaterialCardView's theme owns fill, elevation, and compat shadow/corner clearance.
    // The source instance supplies only its 16dp radius and 1dp secondary-text border.
    OriginalCardSurface(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)
        .testTag(if (insights) "stats_usage_insights" else "stats_usage_list"),
        cornerRadius = 16.dp, border = BorderStroke(1.dp, themeColor(R.attr.secondaryTextColor))) {
        if (insights) {
            val context = LocalContext.current
            val valueStyle = utilityTextStyle(size = utilityDimensionSp(context, R.dimen.heading_font_text_view),
                weight = android.graphics.Typeface.BOLD)
            val captionStyle = utilityTextStyle(size = utilityDimensionSp(context, R.dimen.small_font_subheading_text))
            // Equal-width centered KPIs and full-height vertical separators replace the wrapping
            // independent tiles. Intrinsics preserve the source match_parent divider height.
            Row(Modifier.fillMaxWidth().padding(14.dp).height(IntrinsicSize.Min)
                .testTag("stats_usage_columns"), verticalAlignment = Alignment.CenterVertically) {
                entries.forEachIndexed { index, (label, bytes) ->
                    if (index > 0) Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        .fillMaxHeight().width(1.dp).background(themeColor(R.attr.secondaryTextColor))
                        .testTag("stats_usage_divider_$index"))
                    Column(Modifier.weight(1f).testTag("stats_usage_column_$index"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Utilities.humanReadableByteCount(bytes, true),
                            Modifier.testTag("stats_usage_value_$index"), maxLines = 1,
                            color = themeColor(R.attr.primaryTextColor), style = valueStyle)
                        Row(Modifier.padding(top = 2.dp).testTag("stats_usage_label_$index"),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), Modifier.weight(1f, fill = false).testTag("stats_usage_caption_$index"),
                                color = themeColor(R.attr.primaryLightColorText), style = captionStyle)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.padding(14.dp)) {
                val ump = if (total == 0L) 0 else (unmetered * 100 / total).toInt()
                val mp = if (total == 0L) 0 else (metered * 100 / total).toInt()
                LinearProgressIndicator(progress = { if (ump + mp == 0) 0f else ump.toFloat() / (ump + mp) },
                    modifier = Modifier.fillMaxWidth().testTag("stats_usage_progress"),
                    color = themeColor(R.attr.accentGood), trackColor = themeColor(R.attr.accentGood).copy(alpha = .5f),
                    gapSize = 0.dp, drawStopIndicator = {})
                entries.forEachIndexed { index, (label, bytes) ->
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(18.dp)) { drawCircle(markerColors[index]) }
                        Text(stringResource(R.string.two_argument_colon, stringResource(label), Utilities.humanReadableByteCount(bytes, true)))
                    }
                }
            }
        }
    }
}
@Composable
internal fun SummaryInsights(type: Type, items: List<AppConnection>) {
    val fractions = StatsInsightsMath.normalizeFractions(items.map { statisticsMetric(it, type) })
    val positive = themeColor(R.attr.accentGood)
    val negative = themeColor(R.attr.accentBad)
    val track = themeColor(R.attr.colorSurfaceContainerHighest)
    val blocked = type in listOf(Type.MOST_BLOCKED_APPS, Type.MOST_BLOCKED_ASN, Type.MOST_BLOCKED_DOMAINS, Type.MOST_BLOCKED_BLOCKLISTS, Type.MOST_BLOCKED_IPS)
    val base = if (blocked) negative else positive
    val context = LocalContext.current
    val bytes = type == Type.MOST_CONNECTED_APPS
    val total = items.sumOf { statisticsMetric(it, type) }.let { if (bytes) Utilities.humanReadableByteCount(it, true) else it.toString() }
    val rows: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { index, item ->
                val label = if (type == Type.MOST_CONNECTED_ASN || type == Type.MOST_BLOCKED_ASN) stringResource(R.string.two_argument_space, item.flag, item.appOrDnsName.orEmpty()) else statisticsLabel(context, item, type)
                val metric = statisticsMetric(item, type).let { if (bytes) Utilities.humanReadableByteCount(it, true) else it.toString() }
                val description = stringResource(R.string.ci_desc, label, metric)
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
                    Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(metric, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall) }
                    LinearProgressIndicator(progress = { (fractions[index] * 100).toInt() / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp),
                        color = if (item.blocked) negative else positive, trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
        }
    }
    if (type == Type.MOST_CONTACTED_COUNTRIES) Column { AppCountryMap(items); rows() }
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val sweeps = appDonutSweeps(fractions.take(5))
        val label = stringResource(statisticsTitle(type))
        Box(Modifier.size(96.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $total" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val ring = minOf(size.width, size.height) * .14f
                val diameter = minOf(size.width, size.height) - ring
                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                val area = Size(diameter, diameter)
                drawArc(track, 0f, 360f, false, topLeft, area, style = Stroke(ring))
                var start = -90f
                val alphas = listOf(255, 190, 140, 100, 70)
                sweeps.forEachIndexed { index, sweep ->
                    if (sweep > 2f) drawArc(base.copy(alpha = alphas[index] / 255f), start, sweep - 1.5f, false, topLeft, area, style = Stroke(ring))
                    start += sweep
                }
            }
            Text(total, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(14.dp)); Box(Modifier.weight(1f)) { rows() }
    }
}
