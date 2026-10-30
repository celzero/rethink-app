/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rpn

import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.R
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.PingTestOutcome
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private const val MIN_TEST_DURATION_MS = 1500L

/**
 * TV-native counterpart of `PingTestActivity`: runs the default RPN
 * reachability probes via [VpnController.testRpnProxy] and shows the
 * outcome, total latency and the in-memory test history from
 * [RpnProxyManager.pingTestHistory]. Custom per-domain targets are a
 * phone-only affordance (soft-keyboard heavy) and are intentionally not
 * offered here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnPingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<PingTestOutcome?>(null) }
    var latencyMs by remember { mutableStateOf(0L) }

    val history by RpnProxyManager.pingTestHistory.collectAsStateWithLifecycle()

    fun runTest() {
        if (testing) return
        testing = true
        outcome = null
        scope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val result = VpnController.testRpnProxy()
                val latency = System.currentTimeMillis() - startTime
                RpnProxyManager.recordPingTest(
                    targets = "",
                    outcome = if (result) PingTestOutcome.SUCCESS else PingTestOutcome.FAILURE,
                    latencyMs = latency,
                    passed = if (result) 1 else 0,
                    total = 1,
                )
                // honour the phone screen's minimum animation duration
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < MIN_TEST_DURATION_MS) {
                    delay((MIN_TEST_DURATION_MS - elapsed).milliseconds)
                }
                latencyMs = latency
                outcome = if (result) PingTestOutcome.SUCCESS else PingTestOutcome.FAILURE
            } catch (e: Exception) {
                RpnProxyManager.recordPingTest(
                    targets = "",
                    outcome = PingTestOutcome.FAILURE,
                    latencyMs = System.currentTimeMillis() - startTime,
                    passed = 0,
                    total = 1,
                )
                latencyMs = 0L
                outcome = PingTestOutcome.FAILURE
            } finally {
                testing = false
            }
        }
    }

    val (statusTitle, statusDesc) = when {
        testing -> stringResource(R.string.ping_testing_title) to stringResource(R.string.ping_testing_desc)
        outcome == PingTestOutcome.SUCCESS ->
            stringResource(R.string.ping_success_title) to stringResource(R.string.ping_success_desc)
        outcome == PingTestOutcome.FAILURE ->
            stringResource(R.string.ping_failure_title) to stringResource(R.string.ping_failure_desc)
        else -> "Ready" to stringResource(R.string.ping_ready_desc)
    }

    TvScreenScaffold(
        title = "Connection test",
        subtitle = "Probe Rethink Private Network reachability through the active tunnel.",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(
                title = statusTitle,
                description = statusDesc,
                latencyMs = if (!testing && outcome != null) latencyMs else 0L,
                testing = testing,
            )

            Surface(
                onClick = { runTest() },
                enabled = !testing,
                shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
                ),
                modifier = Modifier
                    .width(360.dp)
                    .height(60.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        text = when {
                            testing -> stringResource(R.string.ping_testing_title)
                            outcome != null -> stringResource(R.string.ping_test_again)
                            else -> stringResource(R.string.rpn_perform_test)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            SettingSectionHeader("Recent tests")
            if (history.isEmpty()) {
                Text(
                    text = "No tests recorded in this session yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
                    contentPadding = PaddingValues(bottom = 32.dp, end = 24.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(history, key = { it.timestamp }) { entry ->
                        HistoryRow(entry)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusCard(
    title: String,
    description: String,
    latencyMs: Long,
    testing: Boolean,
) {
    val (icon, tint) = when {
        testing -> Icons.Filled.Speed to MaterialTheme.colorScheme.primary
        latencyMs > 0 -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        else -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    }
    androidx.tv.material3.Surface(
        shape = TvShapes.Medium,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
            if (!testing && latencyMs > 0) {
                Text(
                    text = stringResource(R.string.ping_total_latency, latencyMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HistoryRow(entry: RpnProxyManager.PingTestHistoryEntry) {
    val context = LocalContext.current
    val outcome = entry.outcomeEnum()
    val (icon, tint) = when (outcome) {
        PingTestOutcome.SUCCESS -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        PingTestOutcome.PARTIAL -> Icons.Filled.WarningAmber to MaterialTheme.colorScheme.onSurfaceVariant
        PingTestOutcome.FAILURE -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    }
    androidx.tv.material3.Surface(
        shape = TvShapes.Small,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TvSpacing.ContentHorizontal, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.targets.ifBlank { "Default probes" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                        .format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = when (outcome) {
                    PingTestOutcome.SUCCESS -> stringResource(R.string.ping_success_title)
                    PingTestOutcome.PARTIAL ->
                        "${entry.passed}/${entry.total} " + stringResource(R.string.ping_partial_title)
                    PingTestOutcome.FAILURE -> stringResource(R.string.ping_failure_title)
                },
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = context.getString(R.string.ping_total_latency, entry.latencyMs),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
