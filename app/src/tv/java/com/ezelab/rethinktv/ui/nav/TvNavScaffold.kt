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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ezelab.rethinktv.ui.apps.AppsScreen
import com.ezelab.rethinktv.ui.dns.DnsScreen
import com.ezelab.rethinktv.ui.firewall.FirewallScreen
import com.ezelab.rethinktv.ui.home.HomeScreen
import com.ezelab.rethinktv.ui.logs.LogsScreen
import com.ezelab.rethinktv.ui.proxy.ProxyScreen
import com.ezelab.rethinktv.ui.settings.SettingsScreen
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
            LaunchedEffect(Unit) {
                runCatching { firstItemFocus.requestFocus() }
            }

            NavigationDrawer(
                drawerContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 24.dp, horizontal = 12.dp),
                    ) {
                        Text(
                            text = "Rethink TV",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ),
                        )
                        Spacer(Modifier.height(16.dp))
                        destinations.forEachIndexed { index, dest ->
                            NavigationDrawerItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    if (currentRoute != dest.route) {
                                        navController.navigate(dest.route) {
                                            // Top-level destinations are a flat set:
                                            // popping back to start prevents the
                                            // back-stack from accumulating
                                            // duplicates as the user pingpongs
                                            // through the drawer.
                                            popUpTo(TvDestination.Home.route) {
                                                inclusive = false
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = dest.label,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstItemFocus)
                                } else {
                                    Modifier
                                },
                            ) {
                                Text(text = dest.label)
                            }
                        }
                    }
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = TvDestination.Home.route,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(TvDestination.Home.route) { HomeScreen() }
                        composable(TvDestination.Dns.route) { DnsScreen() }
                        composable(TvDestination.Firewall.route) { FirewallScreen() }
                        composable(TvDestination.Apps.route) { AppsScreen() }
                        composable(TvDestination.Proxy.route) { ProxyScreen() }
                        composable(TvDestination.Logs.route) { LogsScreen() }
                        composable(TvDestination.Stats.route) { StatsScreen() }
                        composable(TvDestination.Settings.route) { SettingsScreen() }
                    }
                }
            }
        }
    }
}
