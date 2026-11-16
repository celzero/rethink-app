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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Base route for the paid RPN sub-graph; doubles as the hub entry. */
const val RPN_HUB_ROUTE = "rpn"

internal const val RPN_CHECKOUT_PATTERN = "rpn/checkout?extend={extend}"
internal const val RPN_DASHBOARD_ROUTE = "rpn/dashboard"

/**
 * Registers the paid RPN destinations into the main TV NavHost. Called
 * once from [com.celzero.bravedns.tv.ui.nav.TvPaidRoutes] by
 * [RpnTvInitProvider].
 *
 * Route map mirrors the phone flow: hub (entitlement check) → checkout
 * (RethinkPlusFragment) → dashboard (RethinkPlusDashboardFragment) →
 * manage (RethinkPlusManagePurchaseFragment), with servers
 * (ServerSelectionFragment, reduced TV scope) reachable from the
 * dashboard.
 */
fun NavGraphBuilder.rpnNavGraph(navController: NavController) {
    composable(RPN_HUB_ROUTE) { RpnHubScreen(navController) }
    composable(
        route = RPN_CHECKOUT_PATTERN,
        arguments = listOf(
            navArgument("extend") {
                type = NavType.BoolType
                defaultValue = false
            },
        ),
    ) { entry ->
        RpnCheckoutScreen(
            navController = navController,
            extendMode = entry.arguments?.getBoolean("extend") ?: false,
        )
    }
    composable("rpn/servers") { RpnServersScreen(navController) }
    composable(RPN_DASHBOARD_ROUTE) { RpnDashboardScreen(navController) }
    composable("rpn/manage") { RpnManageScreen(navController) }
    composable("rpn/support") { RpnSupportScreen() }
    composable("rpn/orders") { RpnOrderHistoryScreen() }
    composable("rpn/ping") { RpnPingScreen() }
    composable("rpn/stats") { RpnStatsScreen(navController) }
}

/**
 * Entitlement dispatcher: routes to the dashboard when the subscription
 * machine reports a valid entitlement, otherwise to the checkout screen.
 * Redirects replace the hub on the back stack so BACK from either target
 * returns to wherever the user entered the paid area from.
 */
@Composable
private fun RpnHubScreen(navController: NavController) {
    var pending by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val target = withContext(Dispatchers.IO) { rpnHubDestination() }
        pending = false
        navController.navigate(target) {
            popUpTo(RPN_HUB_ROUTE) { inclusive = true }
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (pending) CircularProgressIndicator()
    }
}

/**
 * Pure entitlement check kept separate from the composable so it stays
 * trivially testable: dashboard for a valid subscription, checkout
 * otherwise.
 */
internal fun rpnHubDestination(): String {
    return if (RpnProxyManager.getSubscriptionState().hasValidSubscription) {
        RPN_DASHBOARD_ROUTE
    } else {
        "rpn/checkout"
    }
}
