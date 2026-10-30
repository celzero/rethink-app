/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.nav

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvGradients
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSizes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import com.celzero.bravedns.tv.ui.apps.AppDetailScreen
import com.celzero.bravedns.tv.ui.apps.AppsScreen
import com.celzero.bravedns.tv.ui.apps.RpnBypassAppsScreen
import com.celzero.bravedns.tv.ui.console.ConsoleLogScreen
import com.celzero.bravedns.tv.ui.dns.DnsScreen
import com.celzero.bravedns.tv.ui.dns.DnscryptAddScreen
import com.celzero.bravedns.tv.ui.dns.DohAddScreen
import com.celzero.bravedns.tv.ui.dns.DotAddScreen
import com.celzero.bravedns.tv.ui.dns.OdohAddScreen
import com.celzero.bravedns.tv.ui.dns.ProxyAddScreen
import com.celzero.bravedns.tv.ui.firewall.FirewallScreen
import com.celzero.bravedns.tv.ui.home.HomeScreen
import com.celzero.bravedns.tv.ui.logs.LogsScreen
import com.celzero.bravedns.tv.ui.proxy.ProxyEditorKind
import com.celzero.bravedns.tv.ui.proxy.ProxyEditorScreen
import com.celzero.bravedns.tv.ui.proxy.ProxyScreen
import com.celzero.bravedns.tv.ui.proxy.WgDetailScreen
import com.celzero.bravedns.tv.ui.proxy.WgImportScreen
import com.celzero.bravedns.tv.ui.proxy.WgIncludeAppsScreen
import com.celzero.bravedns.tv.ui.proxy.WgListScreen
import com.celzero.bravedns.tv.ui.rules.AddDomainRuleScreen
import com.celzero.bravedns.tv.ui.rules.AddIpRuleScreen
import com.celzero.bravedns.tv.ui.rules.AppPickerScreen
import com.celzero.bravedns.tv.ui.rules.RulesScreen
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.tv.ui.settings.AntiCensorshipScreen
import com.celzero.bravedns.tv.ui.settings.DnsBypassScreen
import com.celzero.bravedns.tv.ui.settings.PauseVpnScreen
import com.celzero.bravedns.tv.ui.settings.SettingsScreen
import com.celzero.bravedns.tv.ui.stats.StatsDetailKind
import com.celzero.bravedns.tv.ui.stats.StatsDetailScreen
import com.celzero.bravedns.tv.ui.stats.StatsScreen
import com.celzero.bravedns.tv.ui.theme.RethinkTvTheme

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
 *    need the one [com.celzero.bravedns.tv.ui.TvHomeActivity] entry.
 *  * Lets us re-enter detail screens with the D-pad BACK key the same
 *    way users expect, without juggling activity-level taskAffinity /
 *    launchMode combinations.
 *  * Lets every screen share the same Koin scope and the same
 *    underlying upstream singletons without a re-bind dance.
 *
 * @param pendingRoute optional Compose route to navigate to once the
 *   nav host is ready (set from the hosting Activity's intent extras,
 *   e.g. the billing-error trampoline's `EXTRA_RPN_ROUTE`).
 * @param onRouteConsumed invoked after [pendingRoute] has been
 *   navigated so the host can clear it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvNavScaffold(pendingRoute: String? = null, onRouteConsumed: () -> Unit = {}) {
    RethinkTvTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route

            LaunchedEffect(pendingRoute) {
                val route = pendingRoute ?: return@LaunchedEffect
                runCatching { navController.navigate(route) { launchSingleTop = true } }
                onRouteConsumed()
            }

            // The paid RPN rail entry materializes only when the
            // billing-capable init provider installed the rpn sub-graph (see
            // TvPaidRoutes); evaluated once — the provider runs before
            // the first composition frame.
            val destinations = remember { visibleTvDestinations() }

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
                            .padding(start = TvSpacing.RowGap),
                        contentAlignment = Alignment.TopStart,
                    ) {
                    NavHost(
                        navController = navController,
                        startDestination = TvDestination.Home.route,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(TvDestination.Home.route) { HomeScreen(navController) }
                        composable(TvDestination.Dns.route) { DnsScreen(navController) }
                        composable("dns/odoh/add") { OdohAddScreen(navController) }
                        composable("dns/doh/add") { DohAddScreen(navController) }
                        composable("dns/dot/add") { DotAddScreen(navController) }
                        composable("dns/dnscrypt/add") { DnscryptAddScreen(navController) }
                        composable("dns/proxy/add") { ProxyAddScreen(navController) }
                        composable(TvDestination.Firewall.route) { FirewallScreen() }
                        composable(TvDestination.Apps.route) { AppsScreen(navController) }
                        composable(TvDestination.Rules.route) { RulesScreen(navController) }
                        composable(
                            route = "rules/add/domain?uid={uid}",
                            arguments = listOf(
                                androidx.navigation.navArgument("uid") {
                                    type = androidx.navigation.NavType.IntType
                                    defaultValue = Constants.UID_EVERYBODY
                                },
                            ),
                        ) { backStackEntry ->
                            val uid = backStackEntry.arguments?.getInt("uid")
                                ?: Constants.UID_EVERYBODY
                            AddDomainRuleScreen(navController = navController, uid = uid)
                        }
                        composable(
                            route = "rules/add/ip?uid={uid}",
                            arguments = listOf(
                                androidx.navigation.navArgument("uid") {
                                    type = androidx.navigation.NavType.IntType
                                    defaultValue = Constants.UID_EVERYBODY
                                },
                            ),
                        ) { backStackEntry ->
                            val uid = backStackEntry.arguments?.getInt("uid")
                                ?: Constants.UID_EVERYBODY
                            AddIpRuleScreen(navController = navController, uid = uid)
                        }
                        composable("rules/app-picker") { AppPickerScreen(navController) }
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
                            WgDetailScreen(configId = id, navController = navController)
                        }
                        composable(
                            route = "wg/{id}/apps",
                            arguments = listOf(
                                androidx.navigation.navArgument("id") {
                                    type = androidx.navigation.NavType.IntType
                                },
                            ),
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getInt("id") ?: -1
                            WgIncludeAppsScreen(configId = id, navController = navController)
                        }
                        composable("proxy/socks5") { ProxyEditorScreen(kind = ProxyEditorKind.SOCKS5) }
                        composable("proxy/http") { ProxyEditorScreen(kind = ProxyEditorKind.HTTP) }
                        composable("proxy/rpn-bypass") { RpnBypassAppsScreen() }
                        composable("wg/import") { WgImportScreen(navController) }
                        composable("wg/list") { WgListScreen(navController) }
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
                        composable("settings/dns-bypass") { DnsBypassScreen() }
                        composable("settings/console-log") { ConsoleLogScreen() }

                        // Paid RPN sub-graph (rpn/hub → checkout / servers /
                        // dashboard / manage). Registered only when the
                        // billing-capable init provider installed a builder; the
                        // matching rail entry is filtered out otherwise.
                        TvPaidRoutes.rpnGraphBuilder?.invoke(this, navController)
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
                .width(TvSizes.RailWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = TvSpacing.ScreenVertical / 2, horizontal = TvSpacing.RowGap),
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
                    iconRes = dest.iconRes,
                    labelRes = dest.labelRes,
                    onClick = { onSelect(dest) },
                    modifier = itemModifier,
                )
                Spacer(Modifier.height(TvSpacing.RowGap / 2))
            }
        }
        // Layered-surface cue: a quiet border separates the rail from
        // the content pane so the elevation step is visible at 10 ft.
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = TvSpacing.ScreenVertical / 2)
                .background(TvColors.BorderVariant),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Subtle tonal wash behind the content pane so the
                // screen reads as depth (Background → Surface), not
                // one flat color.
                .background(brush = TvGradients.header()),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavRailItem(
    selected: Boolean,
    @androidx.annotation.DrawableRes iconRes: Int,
    @androidx.annotation.StringRes labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
        colors = TvSurfaceStyles.railItemColors(selected),
        scale = TvSurfaceStyles.expressiveScale(),
        modifier = modifier
            .width(TvSizes.RailWidth)
            .height(TvSizes.RailItem),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Spacer(Modifier.width(TvSpacing.ContentHorizontal))
            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(labelRes),
                modifier = Modifier.size(TvSizes.RailIcon),
            )
            Spacer(Modifier.width(TvSpacing.HeaderGap + 4.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
