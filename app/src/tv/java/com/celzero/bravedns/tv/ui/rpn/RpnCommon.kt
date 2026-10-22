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

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.firestack.backend.Backend

/** Tunnel-proxy id for a user-selected RPN location, as the Go engine knows it. */
internal fun rpnProxyId(key: String): String = Backend.RpnWin + key

/** SQL LIKE filter matching any RPN win proxy id; used by log queries. */
internal const val RPN_ID_FILTER = "RPNWIN%"

/**
 * Connection status for the RPN hero chip. Mirrors the phone's
 * `deriveConnectionUiState()`: tunnel presence dominates, then the
 * pending-selection watcher, then RPN mode itself.
 */
internal enum class RpnUiStatus {
    ACTIVE, CONNECTING, REGISTERING, INACTIVE, STOPPED, FAILED
}

/**
 * True while RPN mode is effectively off (user picked "none" /
 * stopped the proxy from the RPN screen). Distinct from INACTIVE,
 * which means the tunnel is down.
 */
internal fun isProxyStopped(): Boolean = RpnProxyManager.rpnMode().isNone()

internal fun deriveRpnUiStatus(
    hasTunnel: Boolean,
    rpnActive: Boolean,
    pendingSelection: Boolean,
): RpnUiStatus = when {
    !hasTunnel -> RpnUiStatus.INACTIVE
    pendingSelection -> RpnUiStatus.CONNECTING
    rpnActive -> RpnUiStatus.ACTIVE
    isProxyStopped() -> RpnUiStatus.STOPPED
    else -> RpnUiStatus.CONNECTING
}

internal fun statusColor(status: RpnUiStatus): Color = when (status) {
    RpnUiStatus.ACTIVE -> Color(0xFF2E7D32)
    RpnUiStatus.CONNECTING, RpnUiStatus.REGISTERING -> Color(0xFF9E7C0C)
    RpnUiStatus.STOPPED -> Color(0xFF616161)
    RpnUiStatus.INACTIVE -> Color(0xFF616161)
    RpnUiStatus.FAILED -> Color(0xFFB3261E)
}

internal fun statusLabel(status: RpnUiStatus): String = when (status) {
    RpnUiStatus.ACTIVE -> "Active"
    RpnUiStatus.CONNECTING -> "Connecting"
    RpnUiStatus.REGISTERING -> "Registering"
    RpnUiStatus.INACTIVE -> "Inactive"
    RpnUiStatus.STOPPED -> "Stopped"
    RpnUiStatus.FAILED -> "Failed"
}

internal fun formatRelative(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/** Colored status pill used by the RPN hero and location cards. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RpnStatusPill(status: RpnUiStatus, modifier: Modifier = Modifier) {
    androidx.tv.material3.Surface(
        shape = RoundedCornerShape(50),
        colors = SurfaceDefaults.colors(containerColor = statusColor(status)),
        modifier = modifier,
    ) {
        Text(
            text = statusLabel(status),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * Generic focusable row card: bold title, optional sub-line, trailing
 * action/value text. The workhorse for dashboard rows and entries.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun FocusableActionCard(
    title: String,
    subtitle: String?,
    valueText: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!valueText.isNullOrBlank()) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = modifier.fillMaxWidth(),
        ) { content() }
    } else {
        androidx.tv.material3.Surface(
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = modifier.fillMaxWidth(),
        ) { content() }
    }
}

/**
 * Modal confirmation dialog (destructive or informational). TV
 * material has no AlertDialog, so the phone-style compose-material3
 * one is used; buttons are focusable and D-pad operable.
 */
@Composable
internal fun TvConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/** "Active • N ago" line derived from the tunnel's RouterStats.since. */
/** "Active • N ago" line derived from the tunnel's RouterStats.since. */
internal suspend fun activeSinceLabel(): String? {
    val stats = runCatching { VpnController.getProxyStats(Backend.RpnWin) }.getOrNull()
    val since = stats?.since ?: return null
    if (since <= 0L) return null
    return "Active · ${formatRelative(since)}"
}
