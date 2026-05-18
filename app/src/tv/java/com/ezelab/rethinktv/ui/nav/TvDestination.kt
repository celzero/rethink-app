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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations surfaced in the TV navigation drawer.
 *
 * Each entry maps to a Compose route in [TvNavScaffold]'s `NavHost`.
 * Adding a destination requires three coordinated edits:
 *
 *   1. add an entry here,
 *   2. add the matching `composable(route)` block in
 *      [TvNavScaffold], and
 *   3. add the destination's root screen Composable to
 *      `app/src/tv/java/com/ezelab/rethinktv/ui/<feature>/`.
 *
 * Order is the rendered order in the drawer, which is also the
 * order users will D-pad through. We follow the convention "most-used
 * first": Home, then the four control surfaces (DNS / Firewall / Apps
 * / Proxy), then the two visibility surfaces (Logs / Stats), then
 * Settings last (matching every Android TV launcher's convention of
 * parking Settings at the bottom).
 *
 * Paid RPN destinations (Checkout / PurchaseHistory / Customer
 * Support / RpnConfigDetail / RpnWinProxyDetails) are intentionally
 * excluded from the TV nav — the v1 TV release targets the F-Droid
 * channel which cannot ship billing.
 */
enum class TvDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Home),
    Dns("dns", "DNS", Icons.Filled.Dns),
    Firewall("firewall", "Firewall", Icons.Filled.Shield),
    Apps("apps", "Apps", Icons.Filled.Apps),
    Proxy("proxy", "Proxy", Icons.Filled.Hub),
    Logs("logs", "Logs", Icons.AutoMirrored.Filled.Article),
    Stats("stats", "Stats", Icons.Filled.Insights),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
