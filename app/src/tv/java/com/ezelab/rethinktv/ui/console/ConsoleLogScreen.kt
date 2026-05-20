/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.ConsoleLogDAO
import com.celzero.bravedns.database.ConsoleLogRepository
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Console-log destination — TV port of `ConsoleLogActivity`.
 *
 * Rolling app log captured by upstream's `Logger` (writes via
 * [com.celzero.bravedns.service.ConsoleLogManager] into the
 * [com.celzero.bravedns.database.ConsoleLog] room table). Used to
 * diagnose tunnel issues without `adb logcat` — important on TV
 * where USB debugging is rarely available.
 *
 * The screen shows a paged list ordered newest-first plus a level
 * filter (ALL / INFO+ / WARN+ / ERROR only) and a Clear button.
 *
 * Bug-report / share-zip flows from upstream are omitted from v1 —
 * they require WorkManager + FileProvider plumbing that's better
 * landed as its own follow-up.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConsoleLogScreen() {
    val dao = koinInject<ConsoleLogDAO>()
    val repository = koinInject<ConsoleLogRepository>()
    val ioScope = remember { CoroutineScope(Dispatchers.IO) }

    var minLevel by remember { mutableStateOf(LogLevelFilter.ALL) }
    // Bumping this key recreates the Pager — the Clear button uses
    // it to force the list to redraw from an empty Room state.
    var refreshKey by remember { mutableStateOf(0) }

    val pager = remember(minLevel, refreshKey) {
        Pager(
            config = PagingConfig(
                pageSize = 80,
                enablePlaceholders = false,
                initialLoadSize = 200,
            ),
        ) {
            dao.getLogs(input = "%", minLevel = minLevel.threshold)
        }
    }
    val items: LazyPagingItems<ConsoleLog> = pager.flow.collectAsLazyPagingItems()

    TvScreenScaffold(
        title = "Console log",
        subtitle = "App diagnostics. Useful for capturing tunnel issues without adb.",
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogLevelFilter.entries.forEach { f ->
                    LevelChip(
                        label = f.label,
                        selected = minLevel == f,
                        onClick = { minLevel = f },
                    )
                }
                Spacer(Modifier.weight(1f))
                ClearButton(
                    onClick = {
                        ioScope.launch {
                            repository.deleteAllLogs()
                            refreshKey += 1
                        }
                    },
                )
            }

            LogList(items = items)
        }
    }
}

/**
 * Mirrors the levels in upstream's `Logger.LoggerLevel`. Filter
 * values are the inclusive lower bound — picking `WARN` shows WARN
 * and ERROR. The DAO query uses `level >= :minLevel`.
 */
private enum class LogLevelFilter(val label: String, val threshold: Int) {
    ALL("All", 0),
    INFO("Info+", 1),
    WARN("Warn+", 2),
    ERROR("Error only", 3),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ClearButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            focusedContainerColor = MaterialTheme.colorScheme.error,
            focusedContentColor = MaterialTheme.colorScheme.onError,
            pressedContainerColor = MaterialTheme.colorScheme.error,
            pressedContentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(
                text = "Clear log",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun LogList(items: LazyPagingItems<ConsoleLog>) {
    if (items.itemCount == 0) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No log entries yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
        ) { i ->
            items[i]?.let { LogRow(it) }
        }
    }
}

private val LogTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun LogRow(log: ConsoleLog) {
    val (levelText, levelColor) = when (log.level.toInt()) {
        in Int.MIN_VALUE..0 -> "VERB" to Color(0xFF8E8E93)
        1 -> "INFO" to Color(0xFF34C759)
        2 -> "WARN" to Color(0xFFFF9500)
        3 -> "ERROR" to Color(0xFFFF3B30)
        else -> "?" to Color(0xFF8E8E93)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = LogTimeFormat.format(Date(log.timestamp)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(110.dp),
        )
        Box(
            modifier = Modifier
                .width(64.dp)
                .padding(end = 8.dp)
                .background(levelColor.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = levelText,
                color = levelColor,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = log.message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
            overflow = TextOverflow.Ellipsis,
            maxLines = 4,
        )
    }
}
