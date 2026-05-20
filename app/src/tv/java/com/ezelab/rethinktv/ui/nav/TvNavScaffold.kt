/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import com.ezelab.rethinktv.ui.apps.AppDetailScreen
import com.ezelab.rethinktv.ui.apps.AppsScreen
import com.ezelab.rethinktv.ui.console.ConsoleLogScreen
import com.ezelab.rethinktv.ui.dns.DnsScreen
import com.ezelab.rethinktv.ui.dns.OdohAddScreen
import com.ezelab.rethinktv.ui.firewall.FirewallScreen
import com.ezelab.rethinktv.ui.home.HomeScreen
import com.ezelab.rethinktv.ui.logs.LogsScreen
import com.ezelab.rethinktv.ui.proxy.ProxyEditorKind
import com.ezelab.rethinktv.ui.proxy.ProxyEditorScreen
import com.ezelab.rethinktv.ui.proxy.ProxyScreen
import com.ezelab.rethinktv.ui.proxy.WgDetailScreen
import com.ezelab.rethinktv.ui.proxy.WgImportScreen
import com.ezelab.rethinktv.ui.rules.RulesScreen
import com.ezelab.rethinktv.ui.settings.AntiCensorshipScreen
import com.ezelab.rethinktv.ui.settings.PauseVpnScreen
import com.ezelab.rethinktv.ui.settings.SettingsScreen
import com.ezelab.rethinktv.ui.stats.StatsDetailKind
import com.ezelab.rethinktv.ui.stats.StatsDetailScreen
import com.ezelab.rethinktv.ui.stats.StatsScreen
import com.ezelab.rethinktv.ui.theme.RethinkTvTheme

/**
 * Root Compose entry-point for the TV launcher.
 *
 * Hosts the TV-material [NavigationDrawer] on the left and a
 * Navigation-Compose [NavHost] on the right. The drawer is the
 * permanent ten-foot navigation rail — collapsed it shows just icons,
 * expanded it shows icon + label, the standard Android TV pattern.
 *
 * Why an Activity-internal NavHost (vs. multiple Activities)?
 *
 *  * Keeps the TV-flavor [`AndroidManifest.xml`][app/src/tv/AndroidManifest.xml]
 *    diff against `app/src/full/AndroidManifest.xml` minimal — we only
 *    need the one [com.ezelab.rethinktv.ui.TvHomeActivity] entry.
 *  * Lets us re-enter detail screens with the D-pad BACK key the same
 *    way users expect, without juggling activity-level taskAffinity /
 *    launchMode combinations.
 *  * Lets every screen share the same Koin scope and the same
 *    underlying upstream singletons without a re-bind dance.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvNavScaffold() {
    RethinkTvTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            val destinations = remember { TvDestination.entries }

            // Park initial focus on the drawer's first item so the
            // user's first D-pad press is always interpreted as
            // navigation. Without this the focus engine picks an
            // arbitrary focusable on the active screen, which on Home
            // means the big "Start protection" button — confusing
            // because D-pad LEFT then has nowhere obvious to go.
            val firstItemFocus = remember { FocusRequester() }
            // A FocusRequester per top-level rail destination. When
            // the user descends into a sub-screen (e.g. proxy → wg/0)
            // the OS focus engine snaps focus to the topologically
            // first focusable in the tree, which is *not* the rail
            // item that matches the new route. Result: the rail's
            // "focused" highlight lands on the wrong icon (typically
            // the second item, DNS) while the correct icon is shown
            // "selected". We compensate by re-anchoring focus on the
            // parent rail item on every route change so the rail's
            // focused & selected highlight stay in lock-step with the
            // visible route. The user can still press D-pad RIGHT to
            // enter the screen content — same as the existing
            // top-level destination UX — so this preserves the
            // ten-foot rail navigation pattern.
            val railFocusRequesters = remember {
                TvDestination.entries.associateWith { FocusRequester() }
            }
            LaunchedEffect(Unit) {
                runCatching { firstItemFocus.requestFocus() }
            }

            var previousRoute by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(currentRoute) {
                val prev = previousRoute
                previousRoute = currentRoute
                if (currentRoute == null || prev == null || currentRoute == prev) {
                    return@LaunchedEffect
                }
                val parent = TvDestination.entries.firstOrNull {
                    currentRoute == it.route ||
                        currentRoute.startsWith(it.route + "/")
                } ?: return@LaunchedEffect
                // Wait for the rail's recomposition (selected-state
                // change) to settle so the FocusRequester has actually
                // attached to the new measure pass before we request.
                delay(80)
                runCatching { railFocusRequesters[parent]?.requestFocus() }
            }

            NavRailContent(
                destinations = destinations,
                currentRoute = currentRoute,
                firstItemFocus = firstItemFocus,
                railFocusRequesters = railFocusRequesters,
                onSelect = { dest ->
                    if (currentRoute != dest.route) {
                        navController.navigate(dest.route) {
                            popUpTo(TvDestination.Home.route) {
                                inclusive = false
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 8.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = TvDestination.Home.route,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(TvDestination.Home.route) { HomeScreen() }
                        composable(TvDestination.Dns.route) { DnsScreen(navController) }
                        composable("dns/odoh/add") { OdohAddScreen(navController) }
                        composable(TvDestination.Firewall.route) { FirewallScreen() }
                        composable(TvDestination.Apps.route) { AppsScreen(navController) }
                        composable(TvDestination.Rules.route) { RulesScreen() }
                        composable(
                            route = "apps/{uid}",
                            arguments = listOf(
                                androidx.navigation.navArgument("uid") {
                                    type = androidx.navigation.NavType.IntType
                                },
                            ),
                        ) { backStackEntry ->
                            val uid = backStackEntry.arguments?.getInt("uid") ?: -1
                            AppDetailScreen(uid = uid, navController = navController)
                        }
                        composable(TvDestination.Proxy.route) { ProxyScreen(navController) }
                        composable(
                            route = "wg/{id}",
                            arguments = listOf(
                                androidx.navigation.navArgument("id") {
                                    type = androidx.navigation.NavType.IntType
                                },
                            ),
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getInt("id") ?: -1
                            WgDetailScreen(configId = id)
                        }
                        composable("proxy/socks5") { ProxyEditorScreen(kind = ProxyEditorKind.SOCKS5) }
                        composable("proxy/http") { ProxyEditorScreen(kind = ProxyEditorKind.HTTP) }
                        composable("wg/import") { WgImportScreen(navController) }
                        composable(TvDestination.Logs.route) { LogsScreen() }
                        composable(TvDestination.Stats.route) { StatsScreen(navController) }
                        composable(
                            route = "stats/detail/{kind}/{value}",
                            arguments = listOf(
                                androidx.navigation.navArgument("kind") {
                                    type = androidx.navigation.NavType.StringType
                                },
                                androidx.navigation.navArgument("value") {
                                    type = androidx.navigation.NavType.StringType
                                },
                            ),
                        ) { backStackEntry ->
                            val kindArg = backStackEntry.arguments?.getString("kind") ?: "domain"
                            val value = backStackEntry.arguments?.getString("value").orEmpty()
                            val kind = if (kindArg == "ip") StatsDetailKind.IP else StatsDetailKind.DOMAIN
                            StatsDetailScreen(value = value, kind = kind)
                        }
                        composable(TvDestination.Settings.route) { SettingsScreen(navController) }
                        composable("settings/anti-censorship") { AntiCensorshipScreen() }
                        composable("settings/pause") { PauseVpnScreen() }
                        composable("settings/console-log") { ConsoleLogScreen() }
                    }
                }
            }
        }
    }
}

/**
 * Permanent left nav rail.
 *
 * We previously used [androidx.tv.material3.NavigationDrawer], but in
 * tv-material 1.0.0 its content slot mis-measures on a 1920×1080
 * surface — the NavHost renders zero-sized and every destination
 * appears blank. A hand-rolled [Row] with a fixed-width rail + a
 * weighted content [Box] sidesteps that entirely and gives us full
 * control over focus traversal.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavRailContent(
    destinations: List<TvDestination>,
    currentRoute: String?,
    firstItemFocus: FocusRequester,
    railFocusRequesters: Map<TvDestination, FocusRequester>,
    onSelect: (TvDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 8.dp),
        ) {
            destinations.forEachIndexed { index, dest ->
                val perItemFocus = railFocusRequesters[dest]
                val itemModifier = when {
                    // The first-item requester must win over the
                    // per-destination one for the Home item so the
                    // initial-launch focus park is preserved.
                    index == 0 -> Modifier.focusRequester(firstItemFocus)
                    perItemFocus != null -> Modifier.focusRequester(perItemFocus)
                    else -> Modifier
                }
                NavRailItem(
                    selected = currentRoute == dest.route ||
                        (currentRoute != null && currentRoute.startsWith(dest.route + "/")),
                    icon = dest.icon,
                    label = dest.label,
                    onClick = { onSelect(dest) },
                    modifier = itemModifier,
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavRailItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.size(56.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
