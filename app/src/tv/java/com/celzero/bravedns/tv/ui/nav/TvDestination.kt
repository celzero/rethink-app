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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Utilities

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
 *      `app/src/tv/java/com/celzero/bravedns/tv/ui/<feature>/`.
 *
 * Order is the rendered order in the drawer, which is also the
 * order users will D-pad through. We follow the convention "most-used
 * first": Home, Apps, then the paid RPN destination, then the four
 * control surfaces (Proxy / DNS / Firewall / Rules), then Stats,
 * Settings, and Logs.
 *
 * The paid [Rpn] destination renders only on billing-capable
 * channels (see [visibleTvDestinations]); its sub-graph is contributed
 * by the billing-capable TV variants via [TvPaidRoutes].
 */
enum class TvDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Home("home", R.string.txt_home, R.drawable.tv_rail_home),
    Apps("apps", R.string.apps_info_title, R.drawable.tv_rail_apps),
    Rpn("rpn", R.string.rpn_title, R.drawable.tv_rail_rpn),
    Proxy("proxy", R.string.settings_proxy_header, R.drawable.tv_rail_proxy),
    Dns("dns", R.string.dns_mode_info_title, R.drawable.tv_rail_dns),
    Firewall("firewall", R.string.firewall_mode_info_title, R.drawable.tv_rail_firewall),
    Rules("rules", R.string.dc_block_heading, R.drawable.tv_rail_rules),
    Stats("stats", R.string.title_statistics, R.drawable.tv_rail_stats),
    Settings("settings", R.string.title_settings, R.drawable.tv_rail_settings),
    Logs("logs", R.string.lbl_logs, R.drawable.tv_rail_logs),
}

/**
 * Drawer entries to render for this process: every destination except
 * the paid [TvDestination.Rpn] entry, which requires (a) the
 * billing-capable graph installed via [TvPaidRoutes] and (b) a channel
 * that ships billing — the fdroid channel keeps its no-billing guarantee
 * by never showing it.
 */
fun visibleTvDestinations(): List<TvDestination> {
    val paidVisible = TvPaidRoutes.isInstalled && !Utilities.isFdroidFlavour()
    if (paidVisible) return TvDestination.entries.toList()
    return TvDestination.entries.filter { it != TvDestination.Rpn }
}
