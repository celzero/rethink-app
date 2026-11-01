/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import android.graphics.Typeface
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.ui.compose.appdetails.DrawableImage
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment.SummaryStatisticsType as Type
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag

@Composable
internal fun StatisticsRow(title: String, subtitle: String?, flag: String, count: String,
    percentage: Int? = null, color: Color = MaterialTheme.colorScheme.primary, icon: Drawable? = null,
    downArrow: Boolean = false, arrowRotation: Float = 0f, tag: String = "", onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().testTag(tag).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp, horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (icon != null) DrawableImage(icon, Modifier.size(22.dp)) else Text(flag, style = utilityTextStyle(size = 18f), maxLines = 1)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                Text(title, style = utilityTextStyle(size = 14f, weight = Typeface.BOLD), color = themeColor(R.attr.primaryTextColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, Modifier.padding(top = 2.dp), style = utilityTextStyle(size = 11f), color = themeColor(R.attr.primaryLightColorText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (percentage != null) LinearProgressIndicator(progress = { percentage.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).alpha(.9f), color = color,
                    trackColor = themeColor(R.attr.background), gapSize = 0.dp, drawStopIndicator = {})
            }
            Text(count, Modifier.alpha(.85f), style = utilityTextStyle(size = 12f), color = themeColor(R.attr.primaryTextColor))
            if (onClick != null) Icon(painterResource(if (downArrow) R.drawable.ic_arrow_down_small else R.drawable.ic_right_arrow_white), null,
                Modifier.padding(start = 6.dp).size(14.dp).alpha(.35f).rotate(arrowRotation))
        }
    }
}
/** Original list_item_rpn_stat_app is deliberately not the summary-row template. */
@Composable
internal fun RpnStatisticsAppRow(title: String, metadata: String, total: String, icon: Drawable?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp)) { if (icon != null) DrawableImage(icon, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = utilityTextStyle(size = 14f), color = themeColor(R.attr.primaryTextColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metadata, Modifier.alpha(.7f), style = utilityTextStyle(size = 11f), color = themeColor(R.attr.primaryLightColorText), maxLines = 1, overflow = TextOverflow.Clip)
        }
        Text(total, Modifier.padding(start = 8.dp), style = utilityTextStyle(size = 13f, family = "sans-serif-medium"), color = themeColor(R.attr.primaryTextColor), maxLines = 1)
    }
}

/** Original expandable app header; keeps its 32dp icon and separate 16sp count. */
@Composable
internal fun BlocklistStatisticsAppRow(title: String, count: String, icon: Drawable?, expanded: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp)) { if (icon != null) DrawableImage(icon, Modifier.fillMaxSize()) }
        Text(title, Modifier.weight(1f).padding(start = 12.dp), style = utilityTextStyle(appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, size = 14f), color = themeColor(R.attr.primaryTextColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, Modifier.padding(start = 8.dp).widthIn(min = 32.dp), style = utilityTextStyle(appearance = android.R.style.TextAppearance_Material_Subhead, size = 16f, family = "sans-serif-light", weight = Typeface.BOLD), textAlign = TextAlign.Center, maxLines = 1)
        Icon(painterResource(R.drawable.ic_expand_more_24), null, Modifier.padding(start = 6.dp).padding(4.dp).size(24.dp).alpha(.4f).rotate(if (expanded) 180f else 0f), tint = Color.Unspecified)
    }
}

@Composable
internal fun BlocklistStatisticsDomainRow(domain: String, count: String) {
    val color = themeColor(R.attr.primaryLightColorText)
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 52.dp, end = 46.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(domain, Modifier.weight(1f), style = utilityTextStyle(size = 12f), color = color, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(count, Modifier.padding(start = 8.dp).widthIn(min = 32.dp), style = utilityTextStyle(size = 12f), color = color, maxLines = 1, textAlign = TextAlign.End)
    }
}

internal fun statisticsLabel(context: Context, item: AppConnection, type: Type): String = when (type) {
    Type.TOP_ACTIVE_CONNS, Type.MOST_CONNECTED_APPS, Type.MOST_BLOCKED_APPS -> item.appOrDnsName?.takeIf { it.isNotEmpty() }
        ?: context.getString(R.string.network_log_app_name_unnamed, item.uid.toString())
    Type.MOST_CONNECTED_ASN, Type.MOST_BLOCKED_ASN -> item.appOrDnsName.orEmpty()
    Type.MOST_CONTACTED_DOMAINS, Type.MOST_BLOCKED_DOMAINS -> item.appOrDnsName?.dropLastWhile { it == '.' }.orEmpty()
    Type.MOST_BLOCKED_BLOCKLISTS -> item.appOrDnsName.orEmpty()
    Type.MOST_CONTACTED_IPS, Type.MOST_BLOCKED_IPS -> item.ipAddress
    Type.MOST_CONTACTED_COUNTRIES -> {
        val name = getCountryNameFromFlag(item.flag)
        if (name.isNotEmpty() && !name.all { it == '-' }) name else context.getString(R.string.two_argument_space,
            context.getString(R.string.network_log_app_name_unknown), item.flag)
    }
}
