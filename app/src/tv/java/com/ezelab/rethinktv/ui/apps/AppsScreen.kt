/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.FirewallManager
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import com.ezelab.rethinktv.ui.common.rememberAsImmutableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Immutable snapshot of an [AppInfo] row, projected out of the upstream
 * mutate-in-place LiveData collection. Holding plain `AppInfo`s in
 * Compose state hits the structural-equality-doesn't-fire problem
 * documented in [com.ezelab.rethinktv.ui.common.rememberAsImmutableState].
 */
internal data class AppRow(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val firewallStatus: FirewallManager.FirewallStatus,
    val connectionStatus: FirewallManager.ConnectionStatus,
    val appCategory: String,
)

internal fun AppInfo.toRow() = AppRow(
    uid = uid,
    packageName = packageName,
    appName = appName,
    isSystemApp = isSystemApp,
    firewallStatus = FirewallManager.FirewallStatus.getStatus(firewallStatus),
    connectionStatus = FirewallManager.ConnectionStatus.getStatus(connectionStatus),
    appCategory = appCategory,
)

/**
 * Apps destination — per-app firewall rules at-a-glance.
 *
 * Mirrors upstream's `AppListActivity`: a grid of installed apps with
 * icon + name + status pill. Focused cards are highlighted with the
 * standard TV selection ring; pressing the D-pad center pushes a
 * detail screen onto the nav back-stack where the user changes
 * firewall + connection status (the same fields the phone's
 * `AppInfoActivity` exposes via spinners).
 *
 * Caveat: per-app domain/IP rules and per-app proxy mapping aren't
 * surfaced in v1 — those will land as their own sub-destinations
 * during the polish phase.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppsScreen(navController: NavController? = null) {
    val rows by rememberAsImmutableState(
        liveData = FirewallManager.getApplistObserver(),
        initial = emptyList<AppRow>(),
    ) { apps ->
        apps.orEmpty()
            .map { it.toRow() }
            .sortedWith(
                compareBy({ it.isSystemApp }, { it.appName.lowercase() }),
            )
    }

    TvScreenScaffold(
        title = "Apps",
        subtitle = if (rows.isEmpty()) {
            "Discovering installed apps…"
        } else {
            "${rows.count { !it.isSystemApp }} user · ${rows.count { it.isSystemApp }} system apps. Open one to change its firewall rule."
        },
    ) {
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "No apps yet — the first refresh after install can take a few seconds.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@TvScreenScaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp, end = 24.dp),
        ) {
            items(rows, key = { it.uid.toString() + "/" + it.packageName }) { row ->
                AppCard(
                    row = row,
                    onClick = {
                        navController?.navigate("apps/${row.uid}")
                    },
                )
            }
        }
    }
}

/**
 * One focusable card in the apps grid.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppCard(row: AppRow, onClick: () -> Unit) {
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
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = row.packageName, size = 56)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.appName.ifBlank { row.packageName },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                StatusPill(row.firewallStatus, row.connectionStatus)
            }
        }
    }
}

/**
 * One-word status pill summarising the (FirewallStatus, ConnectionStatus)
 * pair into the same shorthand upstream uses in its rule-summary
 * tooltips.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun StatusPill(
    firewallStatus: FirewallManager.FirewallStatus,
    connectionStatus: FirewallManager.ConnectionStatus,
) {
    val (label, color) = pillFor(firewallStatus, connectionStatus)
    Box(
        modifier = Modifier
            .padding(horizontal = 0.dp)
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

internal fun pillFor(
    firewallStatus: FirewallManager.FirewallStatus,
    connectionStatus: FirewallManager.ConnectionStatus,
): Pair<String, Color> = when (firewallStatus) {
    FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ->
        "Bypass" to Color(0xFF6E51BD)
    FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
        "Bypass+" to Color(0xFF6E51BD)
    FirewallManager.FirewallStatus.EXCLUDE ->
        "Exclude" to Color(0xFF607D8B)
    FirewallManager.FirewallStatus.ISOLATE ->
        "Isolate" to Color(0xFFEF6C00)
    FirewallManager.FirewallStatus.UNTRACKED ->
        "Untracked" to Color(0xFF455A64)
    FirewallManager.FirewallStatus.NONE -> when (connectionStatus) {
        FirewallManager.ConnectionStatus.ALLOW -> "Allow" to Color(0xFF2E7D32)
        FirewallManager.ConnectionStatus.BOTH -> "Block" to Color(0xFFC62828)
        FirewallManager.ConnectionStatus.METERED ->
            "Block metered" to Color(0xFFAD1457)
        FirewallManager.ConnectionStatus.UNMETERED ->
            "Block Wi-Fi" to Color(0xFFAD1457)
    }
}

/**
 * Async-loads the app icon via PackageManager on a background
 * dispatcher. Falls back to a generic Android glyph if PM can't find
 * the package (uninstalled mid-grid, or a synthetic Rethink uid).
 */
@Composable
internal fun AppIcon(packageName: String, size: Int) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
        }
    }

    val sizeDp = size.dp
    Box(
        modifier = Modifier
            .size(sizeDp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val d = drawable
        if (d != null) {
            val bitmap = remember(d) { d.toSafeBitmap(size) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = packageName,
                    modifier = Modifier.size(sizeDp),
                )
                return@Box
            }
        }
        Icon(
            imageVector = Icons.Filled.Android,
            contentDescription = packageName,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size * 0.6f).dp),
        )
    }
}

/**
 * Drawable -> Bitmap helper that avoids the IllegalArgumentException
 * Android throws for zero-sized adaptive drawables before their bounds
 * are set.
 */
private fun Drawable.toSafeBitmap(sizeDp: Int): Bitmap? = try {
    when (this) {
        is BitmapDrawable -> bitmap
        else -> {
            val w = if (intrinsicWidth > 0) intrinsicWidth else sizeDp * 2
            val h = if (intrinsicHeight > 0) intrinsicHeight else sizeDp * 2
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
            bmp
        }
    }
} catch (t: Throwable) {
    null
}
