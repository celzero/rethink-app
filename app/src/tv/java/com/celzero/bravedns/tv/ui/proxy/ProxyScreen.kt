/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.nav.TvPaidRoutes
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Proxy destination — RPN status, WireGuard tunnel summary, and
 * SOCKS5/HTTP/Orbot status.
 *
 * Layout:
 *
 *  1. **RPN** — status card mirroring the phone's
 *     `ProxySettingsActivity.displayRpnUi()`: "Active · cities" when
 *     enabled + active, "RPN" when enabled or subscribed, "Subscribe"
 *     otherwise. Navigates to the RPN hub (`rpn`), which
 *     dispatches to dashboard or checkout. Hidden on channels without
 *     billing (fdroid).
 *  2. **WireGuard** — live summary of active tunnel names; drills
 *     into the tunnel list (`wg/list`) which owns per-tunnel cards,
 *     enable/disable, and "+ Add tunnel".
 *  3. **Other proxies** — read-only summary card for the legacy
 *     SOCKS5 / HTTP / Orbot proxy state (the phone surfaces a full
 *     form for these; on TV the editor will land alongside the
 *     custom-DNS editor in the polish phase).
 *
 * Out of scope for v1 (noted in code):
 *
 *  * WG add/import (file picker + clipboard paste — needs TV-friendly
 *    text-entry dialog); lives behind `wg/list`.
 *  * WG detail (peers, allowed IPs, DNS, per-app mapping).
 *  * TCP / anti-censorship sub-screens.
 *  * SOCKS5 / HTTP credentials editor (form needs paste-friendly
 *    TextField; TBD).
 *  * Orbot enable/disable (we surface its state, not the toggle).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyScreen(navController: NavController) {
    val appConfig = koinInject<AppConfig>()
    val rpnVisible = remember { TvPaidRoutes.isInstalled && !Utilities.isFdroidFlavour() }

    val proxyDetails by produceState(initialValue = ProxyDetails()) {
        value = withContext(Dispatchers.IO) {
            val tunnels = loadTunnels()
            ProxyDetails(
                socks5 = appConfig.getSocks5ProxyDetails()?.let { "${it.proxyIP}:${it.proxyPort}" },
                http = appConfig.getHttpProxyDetails()?.let { "${it.proxyIP}:${it.proxyPort}" },
                orbot = appConfig.isOrbotProxyEnabled(),
                wgSummary = summarizeActiveWgs(
                    tunnels.filter { it.isActive }
                        .map { it.name.ifBlank { "tunnel #${it.id}" } },
                    tunnels.size,
                ),
            )
        }
    }

    val rpnStatus by produceState(initialValue = "Subscribe") {
        if (!rpnVisible) return@produceState
        value = withContext(Dispatchers.IO) { rpnStatusValue() }
    }

    TvScreenScaffold(
        title = "Proxy",
        subtitle = "RPN, WireGuard, plus SOCKS5 / HTTP / Orbot status.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        ) {
            if (rpnVisible) {
                SettingSectionHeader("Rethink Private Network")
                ProxyStatusCard(
                    label = "RPN",
                    value = rpnStatus,
                    onClick = { navController.navigate("rpn") },
                )
            }

            SettingSectionHeader("WireGuard")
            ProxyStatusCard(
                label = "WireGuard",
                value = proxyDetails.wgSummary,
                onClick = { navController.navigate("wg/list") },
            )

            SettingSectionHeader("Other proxies")
            ProxyStatusCard(
                label = "SOCKS5",
                value = proxyDetails.socks5,
                onClick = { navController.navigate("proxy/socks5") },
            )
            ProxyStatusCard(
                label = "HTTP",
                value = proxyDetails.http,
                onClick = { navController.navigate("proxy/http") },
            )
            ProxyStatusCard(
                label = "Orbot",
                value = if (proxyDetails.orbot) "Active" else null,
                onClick = null,
            )

            Spacer(Modifier.height(TvSpacing.SectionGap))
            Text(
                text = "Tap RPN to manage your subscription and servers. Tap WireGuard to add or manage tunnels. Tap SOCKS5 or HTTP to edit the proxy credentials.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

private data class ProxyDetails(
    val socks5: String? = null,
    val http: String? = null,
    val orbot: Boolean = false,
    val wgSummary: String? = null,
)

// "2 active · Home, Work" / "+3 more" once names exceed the cap;
// "Inactive" when tunnels exist but none are active; null (renders
// "Not configured") when no tunnels are set up.
private const val WG_SUMMARY_NAME_CAP = 3

private fun summarizeActiveWgs(active: List<String>, tunnelCount: Int): String? {
    if (tunnelCount == 0) return null
    if (active.isEmpty()) return "Inactive"
    val shown = active.take(WG_SUMMARY_NAME_CAP).joinToString(", ")
    val more = if (active.size > WG_SUMMARY_NAME_CAP) {
        " +${active.size - WG_SUMMARY_NAME_CAP} more"
    } else {
        ""
    }
    return "${active.size} active · $shown$more"
}

// Same three-state resolution as the phone's displayRpnUi(), without
// the string resources (TV convention is hardcoded English).
private suspend fun rpnStatusValue(): String {
    val isEnabled = RpnProxyManager.isRpnEnabled()
    val hasValidSub = RpnProxyManager.hasValidSubscription()
    val isActive = RpnProxyManager.isRpnActive()
    return when {
        isEnabled && isActive -> {
            val ccs = RpnProxyManager.getSelectedCCs()
                .map { it.city.capitalizeWords() }
                .filter { it.isNotBlank() }
            if (ccs.isNotEmpty()) {
                "Active · ${ccs.take(6).joinToString(", ")}"
            } else {
                "Active · RPN"
            }
        }
        isEnabled || hasValidSub -> "RPN"
        else -> "Subscribe"
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyStatusCard(label: String, value: String?, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = TvSpacing.ContentHorizontal, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.width(96.dp),
            )
            Text(
                text = value ?: "Not configured",
                color = if (value != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = TvSurfaceStyles.interactiveShape(),
            colors = TvSurfaceStyles.rowColors(),
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    } else {
        Surface(
            shape = TvShapes.Small,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    }
}
