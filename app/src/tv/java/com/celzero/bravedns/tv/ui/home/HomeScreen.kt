/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.database.ActivityBucketRow
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.tv.ui.common.AnimatedProtectionState
import com.celzero.bravedns.tv.ui.common.ActivitySlot
import com.celzero.bravedns.tv.ui.common.PillSeverity
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvButton
import com.celzero.bravedns.tv.ui.common.TvEventRow
import com.celzero.bravedns.tv.ui.common.TvQuickActionCard
import com.celzero.bravedns.tv.ui.common.TvSectionHeader
import com.celzero.bravedns.tv.ui.common.TvStatusModule
import com.celzero.bravedns.tv.ui.common.TvStatusPill
import com.celzero.bravedns.tv.ui.common.TvWaveLegend
import com.celzero.bravedns.tv.ui.common.TvActivityWave
import com.celzero.bravedns.tv.ui.common.WelcomeBanner
import com.celzero.bravedns.tv.ui.common.rememberAsImmutableState
import com.celzero.bravedns.tv.ui.common.rememberOnboardingState
import com.celzero.bravedns.tv.ui.nav.TvDestination
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvGradients
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSizes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import org.koin.compose.koinInject
import kotlinx.coroutines.delay
import java.util.Calendar

private const val HOUR_MILLIS = 60L * 60L * 1000L
private const val DAY_MILLIS = 24L * HOUR_MILLIS
private const val REFRESH_INTERVAL_MILLIS = 60_000L

/**
 * Selectable look-back window for the network-activity panel.
 * [segments] is the number of wave buckets the window is sliced
 * into; the bucket size is [windowMs] / [segments].
 */
private enum class TimeRange(val label: String, val windowMs: Long, val segments: Int) {
    M15("15 minutes", 15L * 60L * 1000L, 6),
    M30("30 minutes", 30L * 60L * 1000L, 10),
    H1("1 hour", HOUR_MILLIS, 12),
    H6("6 hours", 6 * HOUR_MILLIS, 12),
    H24("24 hours", DAY_MILLIS, 12),
}

/**
 * Protection status of the tunnel, computed from real engine state —
 * never hard-coded. Drives the hero pill and the headline tint.
 */
private enum class ProtectionState(val label: String, val severity: PillSeverity) {
    OFF("PROTECTION OFF", PillSeverity.NEUTRAL),
    PARTIAL("PARTIALLY PROTECTED", PillSeverity.WARNING),
    ATTENTION("ATTENTION NEEDED", PillSeverity.WARNING),
    PROTECTED("PROTECTED", PillSeverity.SUCCESS),
}

/**
 * One merged recent event (connection log row or DNS log row)
 * rendered by [TvEventRow] in the Recent Activity section.
 */
private data class HomeEvent(
    val timestamp: Long,
    val kind: String,
    val primary: String,
    val secondary: String?,
    val kindColor: Color,
    val kindIcon: ImageVector,
)

/**
 * Home destination — the expressive dashboard.
 *
 * Layout, top to bottom:
 *
 *  * **Header** — time-aware greeting plus the large headline
 *    ("Your device is protected") whose key word carries the
 *    success/neutral tint.
 *  * **Protection hero** — the layered asymmetric panel: tonal base,
 *    indigo bloom, teal counterweight, then the animated status pill,
 *    the state line, the state-colored start/stop CTA, and three
 *    status modules (DNS / Firewall / Proxy, with WireGuard names
 *    folded into the Proxy detail) fed by the same upstream
 *    observables the rest of the app uses.
 *  * **Quick actions** — four shortcut cards into the control
 *    surfaces (DNS, Firewall, Proxy, Apps).
 *  * **Network activity** — the visual centerpiece: a focusable
 *    panel with the selected-window stat line over a three-wave
 *    flowing composition (Total / Allowed / Blocked) merged from the
 *    connection and DNS log tables, refreshed once a minute; a
 *    compact pill cycles the look-back window and OK opens Logs.
 *  * **Recent activity** — the latest merged events; deliberately
 *    secondary (non-focusable rows).
 *
 * Wiring notes:
 *
 *  * `VpnController.connectionStatus` carries an enum, not a mutable
 *    upstream model — `observeAsState` semantics are safe. We use the
 *    project-local [rememberAsImmutableState] for the model-bearing
 *    LiveData (upstream mutates those objects in place).
 *  * The toggle reuses [VpnController.start] / [VpnController.stop]
 *    verbatim so the engine sees identical lifecycle signals
 *    regardless of whether the phone or TV UI initiated the change.
 *  * All DAO/repository reads happen inside the refresh coroutine
 *    (`LaunchedEffect` + `runCatching`), never on the composition
 *    thread; a failure leaves the previous data on screen.
 *  * `VpnController.hasTunnel()` replaces the deprecated `isOn()`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val persistentState: PersistentState = koinInject()
    val appConfig: AppConfig = koinInject()
    val connTrackRepo: ConnectionTrackerRepository = koinInject()
    val connTrackDao: ConnectionTrackerDAO = koinInject()
    val dnsLogRepo: DnsLogRepository = koinInject()
    val dnsLogDao: DnsLogDAO = koinInject()

    val connectionStatus by rememberAsImmutableState(
        liveData = VpnController.connectionStatus,
        initial = null,
    ) { it }
    val isOn = remember(connectionStatus) { VpnController.hasTunnel() }

    val modeOrdinal by rememberAsImmutableState(
        liveData = appConfig.getBraveModeObservable(),
        initial = persistentState.braveMode,
    ) { it ?: persistentState.braveMode }
    val mode = remember(modeOrdinal) { AppConfig.BraveMode.getMode(modeOrdinal) }

    val connectedDns by rememberAsImmutableState(
        liveData = appConfig.getConnectedDnsObservable(),
        initial = persistentState.connectedDnsName,
    ) { it ?: persistentState.connectedDnsName }

    // Project the proxy LiveData to just the endpoint name so a
    // mutate-in-place upstream emission still recomposes when the
    // name the user sees actually changes.
    val connectedProxyName by rememberAsImmutableState(
        liveData = appConfig.connectedProxy,
        initial = null as String?,
    ) { endpoint -> endpoint?.proxyName }

    // WireGuard mappings have no observable of their own; keying on
    // connectionStatus re-evaluates them whenever the tunnel state
    // moves, which is the only moment they can flip.
    val wgNames = remember(connectionStatus) {
        WireguardManager.getAllMappings().filter { it.isActive }.map { it.name }
    }

    // Live dashboard data; null means "first load still running".
    var usage by remember { mutableStateOf<DataUsageSummary?>(null) }
    var blockedCount by remember { mutableStateOf(0L) }
    var slots by remember { mutableStateOf<List<ActivitySlot>?>(null) }
    var events by remember { mutableStateOf<List<HomeEvent>?>(null) }
    // Selected look-back window for the activity panel; deliberately
    // not persisted — the dashboard always opens on "last 30 minutes".
    var timeRange by remember { mutableStateOf(TimeRange.M30) }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // user-initiated: autoAttempt=true would drop the start request
            // when the service is alive without a tunnel
            VpnController.start(context)
        }
    }

    val onboarding = rememberOnboardingState()
    // Mark the welcome banner seen as soon as the user actually
    // starts protection — they've clearly figured out how the app
    // works. Leaving the banner up after that just clutters Home
    // and pushes the hero further off-screen.
    LaunchedEffect(isOn) {
        if (isOn && onboarding.shouldShow) onboarding.markSeen()
    }

    // One refresh loop drives every DB-backed widget (window usage,
    // blocked count, activity buckets, recent events) so they never
    // disagree about "now". Keyed on the selected time range so a
    // range change reloads the panel immediately instead of waiting
    // for the next tick.
    LaunchedEffect(timeRange) {
        while (true) {
            val now = System.currentTimeMillis()
            usage = runCatching {
                connTrackDao.getTotalUsages(
                    now - timeRange.windowMs,
                    ConnectionTracker.ConnType.METERED.value,
                )
            }.getOrNull()
            blockedCount = runCatching {
                connTrackRepo.getWindowCounts(now - timeRange.windowMs, now).blocked
            }.getOrDefault(0L)
            slots = runCatching {
                loadActivitySlots(
                    connTrackRepo,
                    dnsLogRepo,
                    now,
                    timeRange.windowMs,
                    timeRange.segments,
                )
            }.getOrNull()
            events = runCatching {
                loadRecentEvents(connTrackDao, dnsLogDao, now)
            }.getOrNull()
            delay(REFRESH_INTERVAL_MILLIS)
        }
    }

    val dnsActive = isOn && mode.isDnsActive()
    val firewallActive = isOn && mode.isFirewallActive()
    val protectionState = when {
        !isOn -> ProtectionState.OFF
        connectedDns.isBlank() -> ProtectionState.ATTENTION
        dnsActive && firewallActive -> ProtectionState.PROTECTED
        else -> ProtectionState.PARTIAL
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Subtle tonal wash behind the header block — material
            // depth, not decoration.
            .background(brush = TvGradients.header())
            .padding(horizontal = TvSpacing.ScreenHorizontal, vertical = TvSpacing.ScreenVertical)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TvSpacing.SectionGap),
    ) {
        // Custom expressive header (Home opts out of the shared
        // scaffold title — the headline IS the page title).
        Text(
            text = greeting(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = headline(protectionState), style = MaterialTheme.typography.displaySmall)

        WelcomeBanner(onboarding)
        ProtectionHero(
            isOn = isOn,
            protectionState = protectionState,
            mode = mode,
            connectedDns = connectedDns,
            dnsActive = dnsActive,
            firewallActive = firewallActive,
            proxyName = connectedProxyName,
            wgNames = wgNames,
            onToggle = {
                onToggleVpnClicked(
                    isOn = isOn,
                    context = context,
                    onNeedsConsent = { consentIntent ->
                        try {
                            vpnConsentLauncher.launch(consentIntent)
                        } catch (_: ActivityNotFoundException) {
                            // OEM stripped the VPN-consent UI from
                            // their TV image. Tell the user instead
                            // of silently failing.
                            Toast.makeText(
                                context,
                                "This device can't show the VPN consent screen. " +
                                    "Sideloading a stock VpnDialogs APK usually fixes it.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            },
        )

        QuickActionsSection(onNavigate = { dest -> navController?.navigate(dest.route) })

        NetworkActivitySection(
            usage = usage,
            blockedCount = blockedCount,
            slots = slots,
            timeRange = timeRange,
            onCycleRange = {
                timeRange = TimeRange.entries[(timeRange.ordinal + 1) % TimeRange.entries.size]
            },
            onOpenLogs = { navController?.navigate(TvDestination.Logs.route) },
        )

        RecentActivitySection(
            events = events,
            onViewAll = { navController?.navigate(TvDestination.Logs.route) },
        )
    }
}

/**
 * The dashboard's expressive hero: the asymmetric [TvShapes.Hero]
 * panel layered with the indigo/teal gradient washes, carrying the
 * animated status pill, the state line, mode/DNS context, the
 * state-colored start/stop CTA, and the three status modules (WG
 * names fold into the Proxy module's detail). The toggle keeps its
 * focus-parking behavior — it is the first thing the user reaches.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProtectionHero(
    isOn: Boolean,
    protectionState: ProtectionState,
    mode: AppConfig.BraveMode,
    connectedDns: String,
    dnsActive: Boolean,
    firewallActive: Boolean,
    proxyName: String?,
    wgNames: List<String>,
    onToggle: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isOn) {
        runCatching { focusRequester.requestFocus() }
    }
    // Proxy endpoint rows can persist in the DB while the tunnel is
    // down — gate on isOn so a selected-but-idle proxy reads Standby.
    val proxyOn = isOn && (proxyName != null || wgNames.isNotEmpty())
    Surface(
        shape = TvShapes.Hero,
        colors = SurfaceDefaults.colors(
            containerColor = TvColors.SurfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Layered gradient washes, clipped to the hero shape:
            // the indigo bloom from the top-right, the teal
            // counterweight from the bottom-left.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(brush = TvGradients.hero(), shape = TvShapes.Hero),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(brush = TvGradients.heroAccent(), shape = TvShapes.Hero),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(brush = TvGradients.tealAccent(), shape = TvShapes.Hero),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.SectionGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TvSpacing.SectionGap),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AnimatedProtectionState(targetState = protectionState.name) { stateName ->
                        val state = ProtectionState.valueOf(stateName)
                        TvStatusPill(
                            text = state.label,
                            active = state != ProtectionState.OFF,
                            severity = state.severity,
                        )
                    }
                    Spacer(Modifier.height(TvSpacing.GroupGap + 4.dp))
                    Text(
                        text = if (isOn) "Protection is on" else "Protection is off",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Spacer(Modifier.height(TvSpacing.HeaderGap))
                    Text(
                        text = buildString {
                            append(mode.label())
                            append(" mode")
                            if (connectedDns.isNotEmpty()) append(" · DNS $connectedDns")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(TvSpacing.GroupGap))
                    TvButton(
                        text = if (isOn) "Stop protection" else "Start protection",
                        onClick = onToggle,
                        containerColor = if (isOn) TvColors.Error else TvColors.Secondary,
                        contentColor = if (isOn) TvColors.OnError else TvColors.OnSecondary,
                        textStyle = MaterialTheme.typography.titleMedium
                            .copy(fontWeight = FontWeight.Bold),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                }
                // Right rail of semantic status modules.
                Column(
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap + 2.dp),
                    modifier = Modifier.width(200.dp),
                ) {
                    TvStatusModule(
                        label = "DNS",
                        value = if (dnsActive) "Active" else "Off",
                        dotColor = if (dnsActive) TvColors.Success else TvColors.DisabledContent,
                        detail = connectedDns,
                    )
                    TvStatusModule(
                        label = "Firewall",
                        value = if (firewallActive) "Active" else "Off",
                        dotColor = if (firewallActive) TvColors.Success else TvColors.DisabledContent,
                    )
                    TvStatusModule(
                        label = "Proxy",
                        value = if (proxyOn) "Connected" else "Standby",
                        dotColor = when {
                            isOn && wgNames.isNotEmpty() -> TvColors.Success
                            proxyOn -> TvColors.Info
                            else -> TvColors.DisabledContent
                        },
                        detail = if (isOn) {
                            (wgNames + listOfNotNull(proxyName))
                                .joinToString(", ")
                                .ifBlank { null }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * The visual centerpiece: a focusable [TvShapes.Panel] carrying the
 * selected-window stat line, the three-wave flowing activity
 * composition, its legend, and window-relative time labels. OK on the
 * panel opens Logs; the compact range pill at the end of the header
 * row cycles the look-back window (second focus stop, after the
 * panel).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkActivitySection(
    usage: DataUsageSummary?,
    blockedCount: Long,
    slots: List<ActivitySlot>?,
    timeRange: TimeRange,
    onCycleRange: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Surface(
        onClick = onOpenLogs,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Panel),
        colors = TvSurfaceStyles.interactiveColors(
            container = TvColors.SurfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(TvSpacing.SectionGap),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Network activity",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Visually subordinate range selector; a separate
                // focus stop reached after the panel itself.
                Surface(
                    onClick = onCycleRange,
                    shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
                    colors = TvSurfaceStyles.rowColors(
                        container = TvColors.SurfaceContainerHigh,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        text = "Last ${timeRange.label} ▾",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Text(
                text = activityStatLine(usage, blockedCount),
                style = MaterialTheme.typography.bodyLarge,
            )
            when {
                slots == null -> {
                    // First load: a quiet placeholder keeps the layout
                    // stable instead of popping the waves in.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TvSizes.ActivityWaveCompositionHeight)
                            .clip(TvShapes.Panel)
                            .background(TvColors.SurfaceContainerHighest),
                    )
                }
                else -> {
                    // Low-amplitude baseline waves render even when
                    // every bucket is empty — the timeline stays calm,
                    // never a blank panel.
                    TvActivityWave(slots = slots, modifier = Modifier.fillMaxWidth())
                }
            }
            TvWaveLegend()
            Text(
                text = timeLabels(timeRange).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * The panel's compact headline numbers for the selected window:
 * colored received/sent/blocked values in a single line. Bytes come
 * from the connection-log since-timestamp sums, blocked count from
 * the same table's window aggregate — waves carry the shape, this
 * line carries the exact figures (also the semantics for the wave).
 */
private fun activityStatLine(usage: DataUsageSummary?, blocked: Long) = buildAnnotatedString {
    withStyle(SpanStyle(color = TvColors.TrafficReceived)) {
        append(formatBytes(usage?.totalDownload))
    }
    append(" received · ")
    withStyle(SpanStyle(color = TvColors.TrafficSent)) {
        append(formatBytes(usage?.totalUpload))
    }
    append(" sent · ")
    withStyle(SpanStyle(color = TvColors.TrafficBlocked)) {
        append(formatCount(blocked))
    }
    append(" blocked")
}

/**
 * Window-relative time labels, evenly spread across the selected
 * range ("30 min ago · 20 min · 10 min · Now"). Purely decorative —
 * no axis, no ticks.
 */
private fun timeLabels(range: TimeRange): List<String> {
    val totalMinutes = range.windowMs / 60_000L
    val step = totalMinutes / 3
    fun fmt(minutes: Long): String = when {
        minutes >= 60L && minutes % 60L == 0L -> "${minutes / 60L} h"
        minutes >= 60L -> "${minutes / 60L} h ${minutes % 60L} m"
        else -> "$minutes min"
    }
    return listOf(
        "${fmt(totalMinutes)} ago",
        fmt(totalMinutes - step),
        fmt(totalMinutes - 2 * step),
        "Now",
    )
}

/**
 * Four shortcut cards into the control surfaces. One coherent neutral
 * card system; only the icon chips carry the module accent.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickActionsSection(onNavigate: (TvDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.HeaderGap)) {
        TvSectionHeader(text = "Quick actions")
        Text(
            text = "Shortcuts to manage your protection",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = TvSpacing.HeaderGap),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.CardGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TvQuickActionCard(
                icon = Icons.Filled.Dns,
                title = "DNS",
                accent = TvColors.Primary,
                accentContainer = TvColors.PrimaryContainer,
                onClick = { onNavigate(TvDestination.Dns) },
                modifier = Modifier.weight(1f),
            )
            TvQuickActionCard(
                icon = Icons.Filled.Shield,
                title = "Firewall",
                accent = TvColors.Tertiary,
                accentContainer = TvColors.TertiaryContainer,
                onClick = { onNavigate(TvDestination.Firewall) },
                modifier = Modifier.weight(1f),
            )
            TvQuickActionCard(
                icon = Icons.Filled.Hub,
                title = "Proxy",
                accent = TvColors.Secondary,
                accentContainer = TvColors.SecondaryContainer,
                onClick = { onNavigate(TvDestination.Proxy) },
                modifier = Modifier.weight(1f),
            )
            TvQuickActionCard(
                icon = Icons.Filled.Apps,
                title = "Apps",
                accent = TvColors.Warning,
                accentContainer = TvColors.WarningContainer,
                onClick = { onNavigate(TvDestination.Apps) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The latest merged events; intentionally display-only so the section
 * stays secondary — Logs is where rows become interactive.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentActivitySection(
    events: List<HomeEvent>?,
    onViewAll: () -> Unit,
) {
    TvSectionHeader(text = "Recent activity") {
        Surface(
            onClick = onViewAll,
            shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
            colors = TvSurfaceStyles.rowColors(
                container = TvColors.SurfaceContainer,
                content = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                text = "View all logs →",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    Surface(
        shape = TvShapes.Panel,
        colors = SurfaceDefaults.colors(
            containerColor = TvColors.SurfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = TvSpacing.ContentHorizontal + 4.dp,
                vertical = TvSpacing.ContentVertical,
            ),
        ) {
            when {
                events == null -> Text(
                    text = "Loading recent activity…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                events.isEmpty() -> Text(
                    text = "Nothing recorded yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> events.forEach { event ->
                    TvEventRow(
                        kind = event.kind,
                        primary = event.primary,
                        secondary = event.secondary,
                        time = relativeTime(event.timestamp),
                        kindColor = event.kindColor,
                        kindIcon = event.kindIcon,
                    )
                }
            }
        }
    }
}

/**
 * Merge the selected window's connection + DNS log rows into
 * [segments] per-window [ActivitySlot]s by (bucketIndex, blocked) —
 * the same aggregation the phone's log activity aggregator performs,
 * minus the UI.
 */
private suspend fun loadActivitySlots(
    connRepo: ConnectionTrackerRepository,
    dnsRepo: DnsLogRepository,
    now: Long,
    windowMs: Long,
    segments: Int,
): List<ActivitySlot> {
    val rangeStart = now - windowMs
    val bucketMs = windowMs / segments
    val merged = HashMap<Long, ActivitySlot>()
    fun absorb(rows: List<ActivityBucketRow>) {
        rows.forEach { row ->
            val idx = row.bucketIndex.coerceIn(0L, (segments - 1).toLong())
            val current = merged[idx] ?: ActivitySlot(0L, 0L)
            merged[idx] = if (row.blocked == 1) {
                current.copy(blocked = current.blocked + row.total)
            } else {
                current.copy(allowed = current.allowed + row.total)
            }
        }
    }
    absorb(connRepo.getActivityBuckets(rangeStart, now, bucketMs))
    absorb(dnsRepo.getActivityBuckets(rangeStart, now, bucketMs))
    return (0 until segments).map { merged[it.toLong()] ?: ActivitySlot(0L, 0L) }
}

/**
 * Merge the latest connection and DNS log rows into display events,
 * newest first, capped at four. DNS rows render as "DNS query";
 * connection rows as blocked/allowed with hostname (when resolved),
 * app, and IP context.
 */
private suspend fun loadRecentEvents(
    connDao: ConnectionTrackerDAO,
    dnsDao: DnsLogDAO,
    now: Long,
): List<HomeEvent> {
    val windowStart = now - DAY_MILLIS
    val connections = connDao.getConnectionsInWindow(windowStart, now, 6).map { conn ->
        HomeEvent(
            timestamp = conn.timeStamp,
            kind = if (conn.isBlocked) "Blocked" else "Allowed",
            primary = conn.dnsQuery?.takeIf { it.isNotBlank() } ?: conn.ipAddress,
            secondary = listOfNotNull(
                conn.appName.takeIf { it.isNotBlank() },
                conn.ipAddress.takeIf { conn.dnsQuery?.isNotBlank() == true },
            ).joinToString(" · "),
            kindColor = if (conn.isBlocked) TvColors.TrafficBlockedConn else TvColors.TrafficAllowed,
            kindIcon = if (conn.isBlocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
        )
    }
    val queries = dnsDao.getDnsLogsInWindow(windowStart, now, 6).map { log ->
        HomeEvent(
            timestamp = log.time,
            kind = if (log.isBlocked) "Blocked" else "DNS query",
            primary = log.queryStr,
            secondary = log.appName.takeIf { it.isNotBlank() },
            kindColor = if (log.isBlocked) TvColors.TrafficBlocked else TvColors.Info,
            kindIcon = Icons.Filled.Dns,
        )
    }
    return (connections + queries)
        .sortedByDescending { it.timestamp }
        .take(4)
}

/**
 * Time-aware greeting — small personal touch that costs nothing and
 * makes the dashboard feel alive even before any traffic flows.
 */
private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}

/**
 * The large headline with the semantic key word tinted via
 * [buildAnnotatedString]: success-tinted while protected, neutral
 * (variant tone) whenever the device is not fully protected.
 */
private fun headline(state: ProtectionState) = buildAnnotatedString {
    when (state) {
        ProtectionState.PROTECTED -> {
            append("Your device is ")
            withStyle(SpanStyle(color = TvColors.Success)) { append("protected") }
        }
        else -> {
            append("Your device is ")
            withStyle(SpanStyle(color = TvColors.OnSurfaceVariant)) { append("unprotected") }
        }
    }
}

/**
 * Pretty-print a byte count for the dashboard tiles ('1.2 GB' rather
 * than '1288490188').
 */
private fun formatBytes(bytes: Long?): String = when {
    bytes == null || bytes < 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/**
 * Pretty-print a non-negative count for the dashboard tiles. Keeps
 * the rendered string short enough to fit at ten-foot reading
 * distance ('1.2M' rather than '1,234,567').
 */
private fun formatCount(n: Long): String = when {
    n < 0L -> "—"
    n < 1_000L -> n.toString()
    n < 1_000_000L -> String.format("%.1fK", n / 1_000.0)
    n < 1_000_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    else -> String.format("%.1fB", n / 1_000_000_000.0)
}

/** Coarse relative time for event rows (now / 5 m / 2 h / 3 d). */
private fun relativeTime(then: Long): String {
    val diffMinutes = ((System.currentTimeMillis() - then) / 60_000L).coerceAtLeast(0L)
    return when {
        diffMinutes < 1L -> "just now"
        diffMinutes < 60L -> "$diffMinutes m ago"
        diffMinutes < 24L * 60L -> "${diffMinutes / 60L} h ago"
        else -> "${diffMinutes / (24L * 60L)} d ago"
    }
}

private fun AppConfig.BraveMode.label(): String = when (this) {
    AppConfig.BraveMode.DNS -> "DNS"
    AppConfig.BraveMode.FIREWALL -> "Firewall"
    AppConfig.BraveMode.DNS_FIREWALL -> "DNS + Firewall"
}

/**
 * Mirrors the upstream phone fragment's start/stop flow:
 *
 *  * if already on → just stop (with `userInitiated=true` so the
 *    notification is dismissed too);
 *  * else → call [VpnService.prepare]. If it returns an Intent (= the
 *    user hasn't granted VPN consent yet, or it expired), launch it
 *    via the consent launcher. If it returns null, the user already
 *    consented at some point — start immediately.
 *
 * Kept as a top-level function (not a method on a ViewModel) so the
 * Composable above stays trivially previewable. Phase J introduces a
 * `HomeViewModel` if cross-tab state turns out to need sharing.
 */
private fun onToggleVpnClicked(
    isOn: Boolean,
    context: Context,
    onNeedsConsent: (Intent) -> Unit,
) {
    if (isOn) {
        VpnController.stop(reason = "tv-home", context = context, userInitiated = true)
        return
    }
    // Defensive try/catch: some Android builds throw NPE here when the
    // device doesn't actually support system-wide VPN. Upstream catches
    // this too — see HomeScreenFragment.prepareVpnService.
    // IllegalStateException is thrown when another VPN app is set as
    // Always-on VPN with "Block connections without VPN" (lockdown).
    val consentIntent: Intent? = try {
        VpnService.prepare(context)
    } catch (_: NullPointerException) {
        return
    } catch (_: IllegalStateException) {
        return
    }
    if (consentIntent == null) {
        // user-initiated: autoAttempt=true would drop the start request
        // when the service is alive without a tunnel
        VpnController.start(context)
    } else {
        onNeedsConsent(consentIntent)
    }
}
