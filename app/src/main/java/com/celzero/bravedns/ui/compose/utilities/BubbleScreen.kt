/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import androidx.compose.ui.platform.LocalLocale
import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AllowedAppInfo
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BubbleQuickFix(val drawable: Int, val label: Int, val active: Boolean, val onClick: () -> Unit)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BubbleScreen(ui: BubbleUiState) {
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().testTag("bubble.list"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item("title") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.firewall_bubble_title), style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Title, weight = android.graphics.Typeface.BOLD), color = themeColor(R.attr.primaryTextColor), modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.firewall_bubble_subtitle), style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2), color = themeColor(R.attr.primaryLightColorText))
                }
            }
            item("quick-fixes") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ui.quickFixes, key = { it.label }) { fix ->
                    val label = stringResource(fix.label)
                    val iconPadding = with(LocalDensity.current) { 8.toDp() }
                    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), { PlainTooltip { Text(label) } }, rememberTooltipState()) {
                        IconButton(fix.onClick, Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(themeColor(R.attr.background)).testTag("bubble.quick.${fix.label}").semantics { selected = fix.active }) {
                            utilityImage(fix.drawable, Modifier.size(48.dp).padding(iconPadding).alpha(if (fix.active) 1f else .4f), label, themeColor(if (fix.active) R.attr.accentGood else R.attr.primaryLightColorText))
                        }
                    }
                }
            } }
            if (ui.bubbleAllowedAppsLl.isVisible) {
                item("allowed-title") { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BubbleHeading(R.string.bubble_allowed_title, Modifier.weight(1f)); UtilityText(ui.bubbleAllowedCount)
                } }
                ui.allowedAdapter?.let { adapter -> items(adapter.itemCount, key = { "allowed-${adapter.peek(it)?.uid ?: it}" }) { index -> adapter.getItem(index)?.let { adapter.Row(it) } } }
            }
            item("blocked-title") { BubbleHeading(R.string.bubble_activity_title, Modifier.padding(12.dp)) }
            if (ui.bubbleProgressCard.isVisible) item("loading") { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (ui.bubbleProgressBar.isVisible) CircularProgressIndicator(Modifier.size(36.dp).testTag("bubble.loading"))
                Text(stringResource(R.string.loading), Modifier.padding(top = 10.dp), style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2))
            } }
            if (ui.bubbleRecyclerView.isVisible) ui.blockedAdapter?.let { adapter -> items(adapter.itemCount, key = { "blocked-${adapter.peek(it)?.uid ?: it}" }) { index -> adapter.getItem(index)?.let { adapter.Row(it) } } }
            if (ui.bubbleEmptyState.isVisible) item("empty") { Column(Modifier.fillMaxWidth().padding(24.dp).testTag("bubble.empty"), horizontalAlignment = Alignment.CenterHorizontally) {
                utilityImage(R.drawable.ic_firewall_shield, Modifier.size(44.dp), tint = themeColor(androidx.appcompat.R.attr.colorPrimary))
                UtilityText(ui.bubbleEmptyTitle, Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.bubble_empty_state_desc), Modifier.padding(top = 6.dp), style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2), textAlign = TextAlign.Center, color = themeColor(R.attr.primaryLightColorText))
            } }
        }
    }
}
@Composable private fun BubbleHeading(id: Int, modifier: Modifier) { Text(stringResource(id), modifier.semantics { heading() }, style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, weight = android.graphics.Typeface.BOLD), color = themeColor(R.attr.primaryLightColorText)) }
@Composable private fun AppIcon(packageName: String, blocked: Boolean) {
    val context = LocalContext.current
    val icon = remember(packageName, context) {
        val fallback = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.default_app_icon)
        val drawable = if (blocked) {
            try { if (packageName == "Unknown") fallback else context.packageManager.getApplicationIcon(packageName) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { Logger.e("BubbleBlockedAppsAdapter", "App icon not found for $packageName"); fallback }
        } else { try { context.packageManager.getApplicationIcon(packageName) } catch (_: Exception) { fallback } }
        UtilityControl(context).apply { setImageDrawable(drawable) }
    }
    // item_*_app: both variants use a 28dp image centered in a 44dp, 22dp-radius frame.
    Surface(Modifier.size(44.dp), shape = CircleShape, color = themeColor(R.attr.background),
        tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Box(contentAlignment = Alignment.Center) { UtilityImage(icon, Modifier.size(28.dp)) }
    }
}
@Composable fun BubbleAllowedRow(app: AllowedAppInfo, action: (AllowedAppInfo) -> Unit, now: Long = remember(app) { System.currentTimeMillis() }) {
    val age = bubbleAllowedAge(app.allowedAt, now)
    val label = when { age.expired -> stringResource(R.string.lbl_expired); age.minutesAgo <= 0 -> stringResource(R.string.bubble_time_just_now); else -> stringResource(R.string.bubble_time_minutes_ago, age.minutesAgo.toInt()) }
    BubbleRow(app.appName, app.packageName, label, null, false, stringResource(R.string.lbl_remove), "bubble.remove.${app.uid}", { action(app) }, age.expired)
}
@Composable fun BubbleBlockedRow(app: BlockedAppInfo, action: (BlockedAppInfo) -> Unit, now: Long = remember(app) { System.currentTimeMillis() }) {
    val age = bubbleBlockedAge(app.lastBlocked, now)
    val ago = when (age.unit) { "now" -> stringResource(R.string.bubble_time_just_now); "minutes" -> stringResource(R.string.bubble_time_minutes_ago, age.value); "hours" -> stringResource(R.string.bubble_time_hours_ago, age.value); else -> SimpleDateFormat("MMM dd, HH:mm", LocalLocale.current.platformLocale).format(Date(app.lastBlocked)) }
    BubbleRow(app.appName, app.packageName, stringResource(R.string.bubble_blocked_count, app.count), ago, true, stringResource(R.string.bubble_allow_btn), "bubble.allow.${app.uid}", { action(app) }, true)
}
@Composable private fun BubbleRow(name: String, pkg: String, line: String, time: String?, blocked: Boolean, action: String, tag: String, click: () -> Unit, bad: Boolean) {
    // Retain the existing large-font action reflow. It is a deliberate accessibility alternate;
    // the default-font row below follows the two independent original ConstraintLayouts.
    val large = LocalDensity.current.fontScale > 1.3f
    val context = LocalContext.current
    val cardStyle = resolveUtilityStyle(context, androidx.cardview.R.attr.cardViewStyle)
        .takeIf { it != 0 } ?: androidx.cardview.R.style.CardView
    OriginalCardSurface(
        Modifier.fillMaxWidth().padding(horizontal = if (blocked) 6.dp else 4.dp,
            vertical = if (blocked) 6.dp else 3.dp),
        style = cardStyle, styleAttribute = androidx.cardview.R.attr.cardViewStyle,
        useCompatPadding = true, containerColor = themeColor(R.attr.background)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(pkg, blocked)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        maxLines = 1, softWrap = false,
                        style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, weight = android.graphics.Typeface.BOLD),
                        color = themeColor(R.attr.primaryTextColor))
                    if (blocked) {
                        // XML marks only the app name selected in the adapter. The package's
                        // marquee is therefore clipped, not made into a second running marquee.
                        Text(pkg, Modifier.fillMaxWidth().padding(top = 2.dp), maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Clip,
                            style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Caption),
                            color = themeColor(R.attr.primaryLightColorText))
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                            Text(line, style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Caption),
                                color = themeColor(R.attr.accentBad))
                            if (time != null) Text(time, Modifier.weight(1f).padding(start = 10.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Caption),
                                color = themeColor(R.attr.primaryLightColorText))
                        }
                    } else {
                        Text(line, Modifier.fillMaxWidth().padding(top = 2.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Caption),
                            color = themeColor(if (bad) R.attr.accentBad else R.attr.accentGood))
                    }
                }
                if (!large) BubbleRowAction(blocked, action, name, tag, click)
            }
            if (large) Box(Modifier.align(Alignment.End)) { BubbleRowAction(blocked, action, name, tag, click) }
        }
    }
}

@Composable private fun BubbleRowAction(blocked: Boolean, action: String, name: String, tag: String, click: () -> Unit) {
    // Keep the source 34dp visual/top anchor; Compose's clickable supplies minimum hit slop
    // without the standalone helper's extra 48dp layout spacer shifting the original row.
    OriginalButton(if (blocked) "item_blocked_app:blocked_app_allow_btn" else "item_allowed_app:allowed_remove_btn",
        click, Modifier.testTag(tag).semantics { contentDescription = "$action $name" }, grouped = true) { Text(action) }
}
