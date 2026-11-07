/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.home

import androidx.compose.ui.platform.LocalLocale
import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.ui.adapter.AppActivityAdapter
import com.celzero.bravedns.ui.adapter.AppActivityEntry
import com.celzero.bravedns.ui.adapter.AppActivitySummary
import com.celzero.bravedns.ui.compose.rpn.RpnSignature
import com.celzero.bravedns.ui.compose.rpn.RpnSignatureState
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.custom.EmbeddedDolphinContent
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.Utilities
import java.util.Locale

class IntervalRangeGroup(context: Context) : HomeNode(context) {
    fun interface OnButtonCheckedListener { fun onButtonChecked(group: IntervalRangeGroup, buttonId: Int, checked: Boolean) }
    var checkedId by mutableIntStateOf(View.NO_ID)
        private set
    private val listeners = mutableListOf<OnButtonCheckedListener>()
    fun addOnButtonCheckedListener(listener: OnButtonCheckedListener) { listeners += listener }
    fun check(id: Int) {
        if (checkedId == id) return
        val previous = checkedId
        checkedId = id
        if (previous != View.NO_ID) listeners.toList().forEach { it.onButtonChecked(this, previous, false) }
        listeners.toList().forEach { it.onButtonChecked(this, id, true) }
    }
}
class IntervalRows(context: Context) : HomeNode(context) { var adapter: AppActivityAdapter? by mutableStateOf(null) }
class IntervalSignature(context: Context) : HomeNode(context) {
    val presentation = RpnSignatureState()
    fun setContent(content: EmbeddedDolphinContent.Signature) { presentation.setContent(content) }
}
class LogIntervalPresentation(context: Context) {
    val bsLaiRange = HomeNode(context)
    val bsLaiRangeCard = HomeNode(context)
    val bsLaiRangeGroup = IntervalRangeGroup(context)
    val bsLaiChip10m = HomeNode(context, R.id.bs_lai_chip_10m)
    val bsLaiChip1h = HomeNode(context, R.id.bs_lai_chip_1h)
    val bsLaiChip24h = HomeNode(context, R.id.bs_lai_chip_24h)
    val bsLaiCountsRow = HomeNode(context)
    val bsLaiAllowedCount = HomeNode(context).apply { text = "0" }
    val bsLaiBlockedCount = HomeNode(context).apply { text = "0" }
    val bsLaiEmpty = HomeNode(context).apply { visibility = View.GONE }
    val bsLaiRecycler = IntervalRows(context)
    val dolphinSignature = IntervalSignature(context)
}

@Composable
fun LogIntervalContent(b: LogIntervalPresentation) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 14.dp).size(60.dp, 3.dp).background(themeColor(R.attr.border)))
        Text(b.bsLaiRange.text?.toString().orEmpty(), Modifier.padding(top = 4.dp), color = themeColor(R.attr.colorOnSurfaceVariant), fontSize = 14.sp)
        if (b.bsLaiRangeCard.isVisible) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, themeColor(R.attr.border), RoundedCornerShape(22.dp)), horizontalArrangement = Arrangement.spacedBy((-1).dp)) {
                listOf(b.bsLaiChip10m, b.bsLaiChip1h, b.bsLaiChip24h).forEachIndexed { index, node ->
                    val selected = b.bsLaiRangeGroup.checkedId == node.id
                    OriginalChip("bottom_sheet_log_activity_interval:" + node.context.resources.getResourceEntryName(node.id), selected = selected, onClick = { b.bsLaiRangeGroup.check(node.id) }, label = { Text(node.text?.toString().orEmpty(), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp, fontWeight = FontWeight.Bold) }, segmentIndex = index, segmentCount = 3, modifier = Modifier.weight(1f).heightIn(min = 38.dp).testTag(node.context.resources.getResourceEntryName(node.id)))
                }
            }
        }
        if (b.bsLaiCountsRow.isVisible) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IntervalCount(b.bsLaiAllowedCount, R.string.lbl_allowed, themeColor(androidx.appcompat.R.attr.colorPrimary), Modifier.weight(1f))
            IntervalCount(b.bsLaiBlockedCount, R.string.lbl_blocked, themeColor(R.attr.accentBad), Modifier.weight(1f))
        }
        if (b.bsLaiEmpty.isVisible) Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.illustrations_no_record), stringResource(R.string.log_activity_interval_empty), Modifier.fillMaxWidth().padding(horizontal = 30.dp))
            Text(stringResource(R.string.log_activity_interval_empty), Modifier.padding(top = 8.dp), textAlign = TextAlign.Center, color = themeColor(R.attr.primaryLightColorText).copy(alpha = .6f), fontSize = 11.sp)
        }
        if (b.bsLaiRecycler.isVisible) Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            b.bsLaiRecycler.adapter?.let { adapter -> repeat(adapter.getItemCount()) { index -> adapter.Row(index) } }
        }
        if (b.dolphinSignature.isVisible) RpnSignature(b.dolphinSignature.presentation, Modifier.fillMaxWidth())
    }
}

@Composable
private fun IntervalCount(node: HomeNode, label: Int, color: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, themeColor(R.attr.border))) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(node.text?.toString().orEmpty(), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(label).uppercase(LocalLocale.current.platformLocale), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .7f), fontSize = 11.sp)
        }
    }
}

/** Presentation-only row renderer. An expansion event is the sole lazy-load callback. */
@Composable
fun AppActivityNativeRow(
    row: Any,
    expanded: Boolean,
    showBlockedCount: Boolean,
    favIconEnabled: Boolean,
    formatCount: (Long) -> String,
    resolveIconForUid: (Context, Int) -> Drawable?,
    onGroupClicked: (AppActivitySummary) -> Unit,
) {
    val context = LocalContext.current
    when (row) {
        is AppActivitySummary -> {
            val name = row.appName.ifBlank { stringResource(R.string.lbl_unknown) }
            val icon = remember(row.uid, context) { resolveIconForUid(context, row.uid)?.toBitmap()?.asImageBitmap() }
            val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(150), label = "app expansion")
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onGroupClicked(row) }.padding(vertical = 8.dp).testTag("activity_app_${row.uid}").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }, verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon, stringResource(R.string.settings_dns_proxy_dialog_app), Modifier.size(36.dp))
                else Box(Modifier.size(36.dp).background(themeColor(R.attr.accentGood).copy(alpha = 0x1A / 255f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(LocalLocale.current.platformLocale), color = themeColor(androidx.appcompat.R.attr.colorPrimary), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, color = themeColor(R.attr.primaryTextColor), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.log_activity_conn_count, row.total), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .6f), fontSize = 12.sp)
                }
                if (row.allowed > 0) Text(formatCount(row.allowed), Modifier.padding(start = 8.dp), color = themeColor(androidx.appcompat.R.attr.colorPrimary), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (showBlockedCount && row.blocked > 0) Text(formatCount(row.blocked), Modifier.padding(start = 8.dp), color = themeColor(R.attr.accentBad), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Icon(painterResource(R.drawable.ic_arrow_down_small), null, Modifier.padding(start = 10.dp).size(12.dp).rotate(chevron), tint = themeColor(R.attr.primaryLightColorText).copy(alpha = .75f))
            }
        }
        is AppActivityEntry -> {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 48.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CachedActivityIcon(row, favIconEnabled)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(row.label, color = themeColor(R.attr.primaryLightColorText), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (row.allowed > 0) Text(formatCount(row.allowed), Modifier.padding(start = 8.dp), color = themeColor(R.attr.accentGood), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (showBlockedCount && row.blocked > 0) Text(formatCount(row.blocked), Modifier.padding(start = 8.dp), color = themeColor(R.attr.accentBad), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(stringResource(R.string.log_activity_domain_recent, Utilities.convertLongToTime(row.lastSeenMs, TIME_FORMAT_1)), Modifier.padding(top = 2.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .55f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CachedActivityIcon(entry: AppActivityEntry, enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    var drawable by remember(entry.label, enabled) { mutableStateOf<Drawable?>(null) }
    DisposableEffect(entry.label, enabled, context) {
        var active = true
        var target: CustomTarget<Drawable>? = null
        if (enabled && entry.label.isNotBlank()) {
            val domain = entry.label.dropLastWhile { it == '.' }
            if (FavIconDownloader.isUrlAvailableInFailedCache(domain) == null) {
                val requestTarget = object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) { if (active) drawable = resource }
                    override fun onLoadCleared(placeholder: Drawable?) { if (active) drawable = null }
                    override fun onLoadFailed(errorDrawable: Drawable?) { if (active) drawable = null }
                }
                target = requestTarget
                val nextDns = FavIconDownloader.constructFavIcoUrlNextDns(domain)
                val duck = FavIconDownloader.constructFavUrlDuckDuckGo(domain)
                val parent = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(domain)
                fun cached(url: String): RequestBuilder<Drawable> = Glide.with(context).load(url).onlyRetrieveFromCache(true).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                val fallback = runCatching { cached(duck).error(cached(parent)) }.getOrNull()
                try {
                    var request = cached(nextDns)
                    if (fallback != null) request = request.error(fallback)
                    request.into(requestTarget)
                } catch (_: Exception) { fallback?.into(requestTarget) }
            }
        }
        onDispose { active = false; target?.let { Glide.with(context).clear(it) } }
    }
    Crossfade(targetState = drawable, animationSpec = tween(300), modifier = Modifier.size(18.dp), label = "cached icon") { image ->
        if (image == null) Text(entry.flag, Modifier.fillMaxSize(), textAlign = TextAlign.Center, color = themeColor(R.attr.primaryLightColorText), fontSize = 12.sp)
        else Image(remember(image) { image.toBitmap().asImageBitmap() }, null, Modifier.padding(1.dp).size(16.dp))
    }
}
