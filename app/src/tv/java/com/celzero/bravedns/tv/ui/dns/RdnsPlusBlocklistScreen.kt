/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.tv.ui.dns

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.RemoteBlocklistCoordinator
import com.celzero.bravedns.database.RemoteBlocklistPacksMap
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvSelectableChip
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** One display row: a pack plus whether its group header must show. */
private data class RdnsPackRow(val pack: RemoteBlocklistPacksMap, val showHeader: Boolean)

private sealed interface RdnsPlusUiState {
    data object Loading : RdnsPlusUiState
    data object Downloading : RdnsPlusUiState

    // blocklists missing; download not running (failed, cancelled or never attempted)
    data object NeedsDownload : RdnsPlusUiState
    data class Ready(val rows: List<RdnsPackRow>) : RdnsPlusUiState
}

/**
 * TV counterpart of the phone's RDNS Plus blocklist screen (simple
 * "packs" view only; the advanced per-blocklist view stays a phone
 * feature for now).
 *
 * Reached from [DnsScreen]'s RDNS tab ("Configure blocklists" pill).
 * There is no Apply/Cancel step: each pack toggle persists the
 * selection, then (debounced) recomputes the Rethink stamp and applies
 * it onto the RDNS Plus endpoint, activating it as the connected
 * resolver — the same writes the phone's Apply button performs.
 *
 * Download plumbing note: the phone screen observes WorkManager via
 * AppDownloadManager.observeWorkManager(lifecycleOwner); Compose has
 * no LifecycleOwner hook, so this screen observes the worker-tag
 * LiveData directly and re-derives its UI state from actual
 * files/WorkManager state on every emission (refreshState()).
 *
 * Ten-foot UX notes:
 *  * The whole pack row is a single D-pad-focusable Surface; pressing
 *    center toggles the pack (same pattern as WgIncludeAppsScreen).
 *  * First-run download (blocklist files absent) auto-starts like the
 *    phone screen; Retry is the only manual control.
 *  * Stamp encoding requires the live Go resolver, so with the VPN
 *    stopped the selection is kept but a hint asks to start Rethink.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RdnsPlusBlocklistScreen(navController: NavController? = null) {
    val context = koinInject<android.content.Context>()
    val appDownloadManager = koinInject<AppDownloadManager>()
    val persistentState = koinInject<PersistentState>()
    val appScope = koinInject<CoroutineScope>()
    val scope = rememberCoroutineScope()

    var downloadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // set by an explicit cancel: suppresses the auto-download until the
    // user asks for a retry
    var suppressed by remember { mutableStateOf(false) }
    var uiState by remember { mutableStateOf<RdnsPlusUiState>(RdnsPlusUiState.Loading) }

    // Re-reads blocklist files/DB, mirroring the phone's hasBlocklist()
    // re-entry after download-state transitions.
    fun refreshState() {
        scope.launch(Dispatchers.IO) {
            val s = when {
                RdnsPlusBlocklistStore.hasBlocklists() -> {
                    RdnsPlusUiState.Ready(buildPackRows(RdnsPlusBlocklistStore.packs()))
                }
                RdnsPlusBlocklistStore.isDownloadActive() -> RdnsPlusUiState.Downloading
                else -> RdnsPlusUiState.NeedsDownload
            }
            withContext(Dispatchers.Main) { uiState = s }
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    // Selection state, decoded from the endpoint URL stamp on first Ready.
    val selectedTags = remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(uiState) {
        if (uiState is RdnsPlusUiState.Ready && selectedTags.value.isEmpty()) {
            selectedTags.value = withContext(Dispatchers.IO) { RdnsPlusBlocklistStore.initialTags() }
        }
    }

    // downloadState is a sticky, process-wide LiveData; Error/Success left
    // over from an earlier screen must be reconciled, not re-delivered.
    val downloadState by appDownloadManager.downloadState.observeAsState()
    LaunchedEffect(downloadState) {
        when (val s = downloadState) {
            is AppDownloadManager.DownloadState.Success -> {
                downloadError = null
                appDownloadManager.resetDownloadState()
                refreshState()
            }
            is AppDownloadManager.DownloadState.Error -> {
                downloadError = s.reason
                appDownloadManager.resetDownloadState()
                refreshState()
            }
            else -> {}
        }
    }

    // WorkManager-driven transitions (the Compose stand-in for the
    // phone's observeWorkManager): re-derive the UI state on every
    // emission and surface worker failures without auto-retrying.
    val remoteWorkInfos by remember {
        WorkManager.getInstance(context)
            .getWorkInfosByTagLiveData(RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER)
    }.observeAsState()
    LaunchedEffect(remoteWorkInfos) {
        val list = remoteWorkInfos
        if (list != null) {
            val inFlight = list.firstOrNull {
                it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED
            }
            if (inFlight == null) {
                val terminal = list.lastOrNull()
                if (terminal != null &&
                    (terminal.state == WorkInfo.State.FAILED ||
                        terminal.state == WorkInfo.State.CANCELLED) &&
                    !suppressed
                ) {
                    downloadError = persistentState.lastDownloadFailureReason
                        .ifBlank { "Blocklist download failed." }
                    WorkManager.getInstance(context).pruneWork()
                } else if (terminal?.state == WorkInfo.State.SUCCEEDED) {
                    downloadError = null
                    WorkManager.getInstance(context).pruneWork()
                }
            }
        }
        refreshState()
    }

    // Auto-start the first-run download unless the previous attempt
    // failed or the user cancelled.
    LaunchedEffect(uiState, downloadError, suppressed) {
        if (uiState is RdnsPlusUiState.NeedsDownload &&
            downloadError == null && !busy && !suppressed
        ) {
            busy = true
            RdnsPlusBlocklistStore.startDownload()
            busy = false
        }
    }

    // Selection applies live: debounce stamp writes so rapid toggles
    // coalesce into one endpoint update (which the VPN service turns
    // into an in-place addTransport, no restart). appScope (not a
    // composition scope) so an in-flight apply survives navigation.
    var applyJob by remember { mutableStateOf<Job?>(null) }
    var dirty by remember { mutableStateOf(false) }

    fun togglePack(row: RdnsPackRow, selected: Boolean) {
        scope.launch(Dispatchers.IO) {
            val tags = RdnsPlusBlocklistStore.toggle(row.pack.blocklistIds, selected)
            withContext(Dispatchers.Main) {
                selectedTags.value = tags
                dirty = true
            }
            applyJob?.cancel()
            applyJob = appScope.launch {
                delay(APPLY_DEBOUNCE_MS)
                RdnsPlusBlocklistStore.apply(selectedTags.value)
                withContext(Dispatchers.Main) { dirty = false }
            }
        }
    }

    // Back press flushes the pending apply immediately instead of
    // waiting out the debounce window.
    BackHandler(enabled = dirty) {
        applyJob?.cancel()
        val tags = selectedTags.value
        appScope.launch { RdnsPlusBlocklistStore.apply(tags) }
        dirty = false
        navController?.popBackStack()
    }

    val vpnHint =
        if (VpnController.hasTunnel()) "" else " Start Rethink to apply blocklists."
    val selectedCount = (uiState as? RdnsPlusUiState.Ready)?.rows?.count { r ->
        selectedTags.value.containsAll(r.pack.blocklistIds)
    } ?: 0

    TvScreenScaffold(
        title = "RDNS Plus blocklists",
        subtitle = when (val s = uiState) {
            is RdnsPlusUiState.Ready ->
                "${s.rows.size} packs, $selectedCount selected. " +
                    "Press center on a pack to toggle.$vpnHint"
            RdnsPlusUiState.Downloading ->
                stringResource(R.string.notif_download_content_text)
            RdnsPlusUiState.NeedsDownload -> "Blocklists not available yet."
            RdnsPlusUiState.Loading -> "Loading blocklists…"
        },
    ) {
        when (val s = uiState) {
            RdnsPlusUiState.Loading -> {}
            RdnsPlusUiState.Downloading -> {
                DownloadStatusView(
                    message = stringResource(R.string.notif_download_content_text),
                    error = downloadError,
                    showCancel = true,
                    busy = busy,
                    onCancel = {
                        suppressed = true
                        RdnsPlusBlocklistStore.cancelDownload()
                        appDownloadManager.resetDownloadState()
                        refreshState()
                    },
                )
            }
            RdnsPlusUiState.NeedsDownload -> {
                DownloadStatusView(
                    message = "Blocklists not available yet.",
                    error = downloadError,
                    showCancel = false,
                    busy = busy,
                    onRetry = {
                        downloadError = null
                        suppressed = false
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            RdnsPlusBlocklistStore.startDownload()
                            withContext(Dispatchers.Main) { busy = false }
                        }
                        refreshState()
                    },
                )
            }
            is RdnsPlusUiState.Ready -> {
                if (s.rows.isEmpty()) {
                    Text(
                        text = "No blocklists available.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    PackList(
                        rows = s.rows,
                        selectedTags = selectedTags.value,
                        onToggle = ::togglePack,
                    )
                }
            }
        }
    }
}

private const val APPLY_DEBOUNCE_MS = 750L

private fun buildPackRows(packs: List<RemoteBlocklistPacksMap>): List<RdnsPackRow> {
    return packs.mapIndexed { i, p ->
        RdnsPackRow(p, i == 0 || packs[i - 1].group != p.group)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DownloadStatusView(
    message: String,
    error: String?,
    showCancel: Boolean,
    busy: Boolean,
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                color = TvColors.Error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.RowGap)) {
            if (showCancel) {
                TvSelectableChip(
                    label = stringResource(R.string.lbl_cancel),
                    selected = false,
                    enabled = !busy,
                    onClick = onCancel,
                )
            } else {
                TvSelectableChip(
                    label = stringResource(R.string.retry),
                    selected = false,
                    enabled = !busy,
                    onClick = onRetry,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PackList(
    rows: List<RdnsPackRow>,
    selectedTags: Set<Int>,
    onToggle: (RdnsPackRow, Boolean) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        contentPadding = PaddingValues(bottom = 32.dp, end = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows, key = { it.pack.pack + "/" + it.pack.level }) { row ->
            val selected = selectedTags.containsAll(row.pack.blocklistIds)
            if (row.showHeader) {
                GroupHeader(group = row.pack.group)
            }
            PackRow(
                pack = row.pack,
                selected = selected,
                onToggle = { onToggle(row, !selected) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupHeader(group: String) {
    val blockType = blockTypeFor(group) ?: return
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        SettingSectionHeader(text = stringResource(blockType.label))
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(blockType.desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PackRow(
    pack: RemoteBlocklistPacksMap,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TvSpacing.ContentHorizontal, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LevelDot(level = pack.level)
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack.pack.replaceFirstChar(Char::titlecase),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.rsv_blocklist_count_text,
                        pack.blocklistIds.size.toString()
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(TvSpacing.IconTextGap))
            StatePill(selected = selected)
        }
    }
}

/** Trailing state pill for [PackRow]: the pack's on/off selection. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatePill(selected: Boolean) {
    val container: Color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    androidx.tv.material3.Surface(
        shape = TvShapes.Pill,
        colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = container),
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                text = if (selected) "On" else "Off",
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Pack aggressiveness cue (0 gray, 1 green, 2 red) — phone parity. */
@Composable
private fun LevelDot(level: Int) {
    val color = when (level) {
        1 -> TvColors.Success
        2 -> TvColors.Error
        else -> TvColors.OnSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

private fun blockTypeFor(group: String): RethinkBlocklistManager.RethinkBlockType? {
    return when {
        group.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true) ->
            RethinkBlocklistManager.PARENTAL_CONTROL
        group.equals(RethinkBlocklistManager.SECURITY.name, true) ->
            RethinkBlocklistManager.SECURITY
        group.equals(RethinkBlocklistManager.PRIVACY.name, true) ->
            RethinkBlocklistManager.PRIVACY
        else -> null
    }
}
