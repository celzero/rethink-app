/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.dns

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.settings.SettingsCard
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun <T: Any> DnsEndpointScreen(
    source: LiveData<PagingData<T>>, row: (T) -> DnsEndpointRowState,
    emptyTitle: Int, emptyDescription: Int, onAdd: () -> Unit,
    header: @Composable () -> Unit = {}
) {
    val owner = LocalLifecycleOwner.current
    val entries = remember(source, owner) { source.asFlow().flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED) }.collectAsLazyPagingItems()
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            header()
            DnsEndpointList(entries, row, emptyTitle, emptyDescription, Modifier.weight(1f))
        }
        FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(painterResource(R.drawable.ic_fab_without_border), stringResource(R.string.lbl_create), Modifier.size(24.dp))
        }
    }
}

@Composable internal fun <T: Any> DnsEndpointList(
    entries: LazyPagingItems<T>, row: (T) -> DnsEndpointRowState,
    emptyTitle: Int, emptyDescription: Int, modifier: Modifier = Modifier, bottomPadding: Int = 80
) {
    Box(modifier.fillMaxWidth()) {
        when {
            entries.itemCount == 0 && entries.loadState.source.refresh is LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            entries.itemCount == 0 && entries.loadState.source.refresh is LoadState.NotLoading -> DnsEmptyState(emptyTitle, emptyDescription, Modifier.align(Alignment.Center))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPadding.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries.itemCount, key = entries.itemKey { row(it).id }) { index ->
                    entries[index]?.let { DnsEndpointRow(rememberDnsEndpointRow(it, row)) }
                }
            }
        }
    }
}

// Endpoint entity equality can ignore changed fields (some protocols compare only the id).
// A new paged entity must bind again, but redrawing its existing instance must not manufacture
// fresh effect callbacks. Bound presenter method references compare equal across parent redraws.
@Composable internal fun <T: Any> rememberDnsEndpointRow(endpoint: T, row: (T) -> DnsEndpointRowState): DnsEndpointRowState =
    remember(DnsEndpointSourceIdentity(endpoint), row) { row(endpoint) }

private class DnsEndpointSourceIdentity(private val endpoint: Any) {
    override fun equals(other: Any?): Boolean = other is DnsEndpointSourceIdentity && endpoint === other.endpoint
    override fun hashCode(): Int = System.identityHashCode(endpoint)
}

@Composable internal fun DnsEmptyState(title: Int, description: Int, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.illustrations_no_record), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(title), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(description), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = themeColor(R.attr.primaryLightColorText), textAlign = TextAlign.Center)
    }
}

@Composable internal fun DnsEndpointRow(item: DnsEndpointRowState, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    val owner = LocalLifecycleOwner.current
    var description by remember(item) { mutableStateOf(item.initialDescription) }
    var flag by remember(item.id, item.selected, item.address) { mutableStateOf<String?>(null) }
    // A new row snapshot is a bind, including replacement callbacks for the same id/address.
    // START/STOP alone must not rebind: the adapters fetched flags once per bind and stopped
    // polling after an inactive tick, without restarting it on the next START.
    DisposableEffect(item, owner, ioDispatcher) {
        val statusJob = owner.lifecycleScope.launch {
            val readDescription = item.description ?: return@launch
            do {
                if (item.pollStatus && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) break
                // The adapters scheduled each read separately, so a pending read must not
                // postpone the next lifecycle check or turn a stopped poll into a later retry.
                launch {
                    val value = withContext(ioDispatcher) { readDescription() }
                    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) description = value
                }
                if (!item.pollStatus) break
                delay(1_000L)
            } while (isActive)
        }
        val flagJob = owner.lifecycleScope.launch {
            val value = withContext(ioDispatcher) { item.flag() }
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) flag = value
        }
        onDispose { statusJob.cancel(); flagJob.cancel() }
    }
    val announcement = stringResource(if (item.selected) R.string.dns_list_item_selected_cd else R.string.dns_list_item_select_cd, item.name)
    SettingsCard(contentPadding = 0.dp, cornerRadius = 12.dp) {
        Row(Modifier.fillMaxWidth().testTag("dns_endpoint_${item.id}").semantics {
            selected = item.selected; contentDescription = announcement
            if (item.relay) toggleableState = if (item.selected) ToggleableState.On else ToggleableState.Off
        }.clickable(role = if (item.relay) Role.Checkbox else Role.RadioButton, onClick = item.onSelect).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            flag?.let { DnsEndpointFlag(it, item.flagStyle) }
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, color = themeColor(R.attr.primaryTextColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
                description?.takeIf(String::isNotEmpty)?.let { Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = themeColor(R.attr.primaryLightColorText), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(item.onInfo, Modifier.testTag("dns_endpoint_info_${item.id}")) {
                Icon(painterResource(if (item.deletable) R.drawable.ic_fab_uninstall else R.drawable.ic_info), stringResource(if (item.deletable) R.string.lbl_delete else R.string.bsct_meta_info), Modifier.size(24.dp), tint = themeColor(R.attr.primaryTextColor).copy(alpha = .55f))
            }
            DnsSelectionIndicator(item.selected, Modifier.padding(start = 4.dp, end = 12.dp))
        }
    }
}

/** A flag keeps its source minimum slot; proxy rows use their explicit AppCompat appearance. */
@Composable internal fun DnsEndpointFlag(flag: String, style: DnsEndpointFlagStyle) {
    Box(Modifier.padding(end = 5.dp).sizeIn(minWidth = 32.dp, minHeight = 32.dp), contentAlignment = Alignment.Center) {
        Text(flag, textAlign = TextAlign.Center,
            style = if (style == DnsEndpointFlagStyle.Proxy)
                utilityTextStyle(appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Headline)
            else utilityTextStyle(size = 25f))
    }
}

@Composable private fun DnsSelectionIndicator(selected: Boolean, modifier: Modifier) {
    val progress by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "DNS selection")
    val primary = themeColor(R.attr.primaryTextColor)
    val accent = themeColor(R.attr.accentGood)
    val knob = themeColor(R.attr.invertedPrimaryTextColor)
    Canvas(modifier.size(32.dp, 18.dp)) {
        drawCircle(primary.copy(alpha = 1f - progress), 8.dp.toPx(), style = Stroke(2.dp.toPx()))
        val scale = .6f + .4f * progress
        val width = size.width * scale
        val height = 16.dp.toPx() * scale
        drawRoundRect(accent.copy(alpha = progress), Offset((size.width - width) / 2, (size.height - height) / 2), Size(width, height), CornerRadius(height / 2))
        drawCircle(knob.copy(alpha = progress), 6.dp.toPx() * scale, Offset(size.width / 2 + 8.dp.toPx() * scale, size.height / 2))
    }
}

@Composable internal fun <T: Any> DnsRelaysDialog(source: LiveData<PagingData<T>>, row: (T) -> DnsEndpointRowState, noDim: Boolean, onDismiss: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val entries = remember(source, owner) { source.asFlow().flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED) }.collectAsLazyPagingItems()
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (noDim) NoDimDialogWindow()
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(), color = themeColor(R.attr.background)) {
            Column(Modifier.padding(10.dp)) {
                Text(stringResource(R.string.cd_dnscrypt_relay_heading), Modifier.padding(horizontal = 20.dp, vertical = 5.dp), color = themeColor(R.attr.accentGood), style = MaterialTheme.typography.titleMedium)
                DnsEndpointList(entries, row, R.string.dns_crypt_empty_state_title, R.string.dns_crypt_empty_state_desc, Modifier.weight(1f), 8)
                OriginalButton("dialog_dnscrypt_relays:custom_dialog_ok_button", onDismiss, Modifier.align(Alignment.End).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { Text(stringResource(R.string.lbl_dismiss)) }
            }
        }
    }
}
