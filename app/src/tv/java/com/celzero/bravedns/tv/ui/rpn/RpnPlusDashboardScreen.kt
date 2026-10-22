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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/**
 * RPN subscription dashboard — TV counterpart of
 * `RethinkPlusDashboardFragment`. Shows the resolved subscription
 * state, identity line, and routes into the purchase / manage /
 * support screens (those live in the channel source sets where they
 * need Play-billing APIs; this screen only uses flavor-safe APIs so
 * it compiles for fdroid too, where the RPN nav entry is hidden).
 *
 * The server-API error dialog (409 / 401 / device-not-registered)
 * mirrors the phone's `PurchaseConflictBottomSheet` family.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnPlusDashboardScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subscriptionRepo: SubscriptionStatusRepository = koinInject()

    val state by produceState<SubscriptionStateMachineV2.SubscriptionState>(
        initialValue = SubscriptionStateMachineV2.SubscriptionState.Uninitialized,
    ) {
        runCatching { RpnProxyManager.collectSubscriptionState() }.getOrNull()?.collect {
            value = it
        }
    }

    var reloadKey by remember { mutableStateOf(0) }
    val sub by produceState<com.celzero.bravedns.database.SubscriptionStatus?>(
        initialValue = null, state, reloadKey,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { subscriptionRepo.getCurrentSubscription() }.getOrNull()
        }
    }

    val identity by produceState("" to "", state) {
        value = withContext(Dispatchers.IO) {
            val id = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()
            val expiry = runCatching { VpnController.getWinExpiryTs() }.getOrNull()
            id to (expiry?.toString().orEmpty())
        }
    }

    var resetInFlight by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showResubscribe by remember { mutableStateOf(false) }
    var resubscribeLatched by remember { mutableStateOf(false) }

    // Resubscribe prompt, phone parity: cancelled DB row + cancelled
    // engine state + Play reports non-auto-renewing. Latched once
    // per screen visit (the phone latches once per session).
    LaunchedEffect(state, sub) {
        val dbCancelled = sub != null && runCatching {
            com.celzero.bravedns.database.SubscriptionStatus.SubscriptionState
                .fromId(sub!!.status) == com.celzero.bravedns.database.SubscriptionStatus
                .SubscriptionState.STATE_CANCELLED
        }.getOrDefault(false)
        val engineCancelled = runCatching {
            val data = RpnProxyManager.getSubscriptionData()
            RpnProxyManager.getSubscriptionState().isCancelled ||
                data?.subscriptionStatus?.status ==
                com.celzero.bravedns.database.SubscriptionStatus.SubscriptionState
                    .STATE_CANCELLED.id
        }.getOrDefault(false)
        val notAutoRenewing = runCatching {
            RpnProxyManager.getSubscriptionData()?.purchaseDetail?.isAutoRenewing == false
        }.getOrDefault(true)
        if (dbCancelled && engineCancelled && notAutoRenewing && !resubscribeLatched) {
            resubscribeLatched = true
            showResubscribe = true
        }
    }

    val resolved = SubscriptionUiStateResolver.resolve(state, sub)
    val canPurchase = state.canMakePurchase && !Utilities.isFdroidFlavour()
    val hasPurchase = sub != null || state != SubscriptionStateMachineV2.SubscriptionState.Initial

    fun resetAll() {
        if (resetInFlight) return
        resetInFlight = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                withTimeoutOrNull(20_000) { RpnProxyManager.resetAndRefetchRpn() }
            }.getOrNull() ?: RpnProxyManager.ResetResult.Failure("Timed out")
            withContext(Dispatchers.Main) {
                resetInFlight = false
                reloadKey++
                val message = when (result) {
                    is RpnProxyManager.ResetResult.Success -> "RPN restored to defaults"
                    is RpnProxyManager.ResetResult.Failure -> "Reset failed: ${result.reason}"
                }
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    TvScreenScaffold(
        title = "Rethink Plus",
        subtitle = "Your RPN subscription and account.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubscriptionHero(state, sub, resolved, identity)

            SettingSectionHeader("Account")
            if (canPurchase) {
                FocusableActionCard(
                    title = "Get Rethink Plus",
                    subtitle = "Choose a plan and subscribe.",
                    valueText = "Get Plus ›",
                    onClick = { navController.navigate("rpn/plus/purchase") },
                )
            }
            if (hasPurchase && !Utilities.isFdroidFlavour()) {
                FocusableActionCard(
                    title = "Manage purchase",
                    subtitle = "Order history, cancel, or request a refund.",
                    valueText = "Manage ›",
                    onClick = { navController.navigate("rpn/plus/manage") },
                )
            }
            if (!Utilities.isFdroidFlavour()) {
                FocusableActionCard(
                    title = "Report an issue",
                    subtitle = "Contact support with your subscription details attached.",
                    valueText = "Support ›",
                    onClick = { navController.navigate("rpn/plus/support") },
                )
            }
            FocusableActionCard(
                title = "Restore defaults",
                subtitle = "Reset RPN selections and re-fetch the server list.",
                valueText = if (resetInFlight) "Working…" else "Reset ›",
                onClick = { confirmReset = true },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showResubscribe) {
        TvConfirmDialog(
            title = "Your plan was cancelled",
            message = "Your subscription will end at the close of the billing period. " +
                "Resubscribe to keep RPN active.",
            confirmLabel = "Resubscribe",
            onConfirm = {
                showResubscribe = false
                navController.navigate("rpn/plus/purchase")
            },
            onDismiss = { showResubscribe = false },
        )
    }
    if (confirmReset) {
        TvConfirmDialog(
            title = "Restore RPN defaults?",
            message = "Selected locations and configuration are reset; " +
                "the server list is re-fetched.",
            confirmLabel = "Reset",
            onConfirm = {
                confirmReset = false
                resetAll()
            },
            onDismiss = { confirmReset = false },
        )
    }

    RpnServerApiErrorDialog(
        onRefundRequested = { /* server already initiated; nothing to do here */ },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubscriptionHero(
    state: SubscriptionStateMachineV2.SubscriptionState,
    sub: com.celzero.bravedns.database.SubscriptionStatus?,
    resolved: SubscriptionUiStateResolver.PurchaseUiModel,
    identity: Pair<String, String>,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RpnStatusPill(chipStatusFor(state))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = sub?.productTitle?.ifBlank { state.name } ?: state.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            val when_ = sub?.purchaseTime?.takeIf { it > 0 }?.let { formatRelative(it) }
            Text(
                text = buildString {
                    append("Purchased: ")
                    append(when_ ?: "—")
                    if (identity.first.isNotBlank()) {
                        append("  ·  ID ")
                        append(identity.first.take(12))
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (resolved is SubscriptionUiStateResolver.PurchaseUiModel.Lapsed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your plan has lapsed — subscribe again to keep RPN active.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun chipStatusFor(state: SubscriptionStateMachineV2.SubscriptionState): RpnUiStatus = when {
    state.isActive -> RpnUiStatus.ACTIVE
    state.isCancelled -> RpnUiStatus.STOPPED
    state.isExpired || state.isRevoked -> RpnUiStatus.FAILED
    state == SubscriptionStateMachineV2.SubscriptionState.ServerAckPending -> RpnUiStatus.REGISTERING
    else -> RpnUiStatus.INACTIVE
}

/**
 * Dismissible dialog for `InAppBillingHandler.serverApiErrorLiveData`
 * events — the TV counterpart of the phone's purchase-conflict /
 * device-auth / device-not-registered bottom sheets. Offered on the
 * billing screens; a single shared instance lives on the dashboard.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RpnServerApiErrorDialog(onRefundRequested: () -> Unit) {
    val error by com.celzero.bravedns.tv.ui.common.rememberAsImmutableState(
        liveData = com.celzero.bravedns.iab.InAppBillingHandler.serverApiErrorLiveData,
        initial = null,
    ) { it }

    val apiError = error ?: return
    if (apiError is ServerApiError.None) return

    val (title, message, offerRefund) = when (apiError) {
        is ServerApiError.Conflict409 -> Triple(
            "Purchase conflict",
            "The server reported a conflict for this purchase" +
                (apiError.serverMessage?.let { ": $it" } ?: ".") +
                " A refund may need to be started.",
            apiError.operation.canRefund,
        )
        is ServerApiError.Unauthorized401 -> Triple(
            "Device not authorized",
            "Your device could not be authorized by the Rethink server. " +
                "Try again, or contact support if it persists.",
            apiError.operation.canRefund,
        )
        is ServerApiError.DeviceNotRegistered -> Triple(
            "Device not registered",
            "Your subscription is active, but this device is not linked to it. " +
                "Contact support to resolve this.",
            false,
        )
        is ServerApiError.GenericError -> Triple(
            "Server error (HTTP ${apiError.httpCode})",
            apiError.message ?: "The Rethink server could not process the request.",
            false,
        )
        is ServerApiError.NetworkError -> Triple(
            "Network error",
            apiError.message ?: "Could not reach the Rethink server.",
            false,
        )
        ServerApiError.None -> return
    }

    TvConfirmDialog(
        title = title,
        message = message,
        confirmLabel = if (offerRefund) "Request refund" else "OK",
        onConfirm = {
            com.celzero.bravedns.iab.InAppBillingHandler.serverApiErrorLiveData.value =
                ServerApiError.None
            if (offerRefund) onRefundRequested()
        },
        onDismiss = {
            com.celzero.bravedns.iab.InAppBillingHandler.serverApiErrorLiveData.value =
                ServerApiError.None
        },
    )
}
