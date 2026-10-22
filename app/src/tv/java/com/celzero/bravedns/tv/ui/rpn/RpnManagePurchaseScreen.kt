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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ServerOrderHistoryRepository
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.viewmodel.ManagePurchaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

/**
 * Manage purchase — TV counterpart of
 * `RethinkPlusManagePurchaseFragment`: order history, cancel
 * (auto-renew subs) / refund (revoke-window), and the Play
 * subscriptions deep-link. Only uses APIs present in all three
 * channels (`InAppBillingHandler` cancel/revoke are stubbed no-ops
 * returning Failure on fdroid, where this screen is unreachable).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnManagePurchaseScreen(navController: NavController) {
    val context = LocalContext.current
    val vm: ManagePurchaseViewModel = koinViewModel()
    val orderRepo: ServerOrderHistoryRepository = koinInject()

    val data by produceState<SubscriptionStateMachineV2.SubscriptionData?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { RpnProxyManager.getSubscriptionData() }.getOrNull()
        }
    }
    val state by produceState<SubscriptionStateMachineV2.SubscriptionState>(
        initialValue = SubscriptionStateMachineV2.SubscriptionState.Uninitialized,
    ) {
        runCatching { RpnProxyManager.collectSubscriptionState() }.getOrNull()?.collect {
            value = it
        }
    }

    val op by vm.operationState.collectAsState()

    var confirmCancel by remember { mutableStateOf(false) }
    var confirmRefund by remember { mutableStateOf(false) }

    val sub = data?.subscriptionStatus
    val isInApp = sub?.productId?.contains("onetime") == true ||
        sub?.productId?.contains("inapp") == true
    val canRevoke = canRevoke(sub)
    val showCancel = state.isActive && sub != null && !isInApp && !canRevoke
    val showRefund = state.isActive && sub != null && canRevoke

    val orders by produceState<OrderUi>(OrderUi.Loading, sub?.purchaseToken) {
        if (sub == null) {
            value = OrderUi.Empty
            return@produceState
        }
        value = when (val result = withContext(Dispatchers.IO) {
            runCatching { orderRepo.fetchOrders() }.getOrNull()
        }) {
            is ServerOrderHistoryRepository.Result.Success ->
                if (result.orders.isEmpty()) OrderUi.Empty else OrderUi.Ready(result.orders.size)
            is ServerOrderHistoryRepository.Result.Error -> OrderUi.Error(result.message)
            ServerOrderHistoryRepository.Result.NoCredentials ->
                OrderUi.Error("No server credentials on this build.")
            null -> OrderUi.Error("Could not load order history.")
        }
    }

    TvScreenScaffold(
        title = "Manage purchase",
        subtitle = sub?.productTitle ?: "No purchase found.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = state.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    if (sub != null && sub.purchaseTime > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Purchased ${formatRelative(sub.purchaseTime)} · " +
                                "order ${sub.orderId.ifBlank { "—" }}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            val progressText = when (val o = op) {
                is ManagePurchaseViewModel.OperationState.InProgress ->
                    "${if (o.isCancel) "Cancelling" else "Requesting refund"} — ${o.step.name.lowercase().replace('_', ' ')}…"
                is ManagePurchaseViewModel.OperationState.Success ->
                    stringResource(o.messageRes)
                is ManagePurchaseViewModel.OperationState.Failure ->
                    stringResource(o.messageRes) +
                        (o.serverMessage?.let { " ($it)" } ?: "")
                is ManagePurchaseViewModel.OperationState.AlreadyEnded ->
                    "This purchase has already ended."
                ManagePurchaseViewModel.OperationState.Idle -> null
            }
            if (progressText != null) {
                Text(
                    text = progressText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            SettingSectionHeader("Order history")
            FocusableActionCard(
                title = "Server order history",
                subtitle = when (val o = orders) {
                    OrderUi.Loading -> "Loading…"
                    is OrderUi.Ready -> "${o.count} orders on record."
                    is OrderUi.Error -> o.message
                    OrderUi.Empty -> "No orders yet."
                },
                valueText = null,
                onClick = null,
            )

            if (showCancel) {
                FocusableActionCard(
                    title = "Cancel purchase",
                    subtitle = "Ends auto-renewal at the close of the billing period.",
                    valueText = "Cancel plan ›",
                    onClick = { confirmCancel = true },
                )
            }
            if (showRefund) {
                FocusableActionCard(
                    title = "Request refund",
                    subtitle = "You are inside the refund window for this purchase.",
                    valueText = "Refund ›",
                    onClick = { confirmRefund = true },
                )
            }
            if (!isInApp && sub != null) {
                FocusableActionCard(
                    title = "Manage on Google Play",
                    subtitle = "Opens your Play subscriptions page.",
                    valueText = "Open ›",
                    onClick = {
                        val link = InAppBillingHandler.PLAY_SUBS_LINK
                            .replace("\$1", sub.productId)
                            .replace("\$2", context.packageName)
                        UIUtils.openUrl(context, link)
                        InAppBillingHandler.fetchPurchases(
                            listOf(
                                InAppBillingHandler.PRODUCT_TYPE_SUBS,
                                InAppBillingHandler.PRODUCT_TYPE_INAPP,
                            ),
                        )
                    },
                )
            }

            RpnServerApiErrorDialog(
                onRefundRequested = { vm.revokeSubscription() },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmCancel) {
        TvConfirmDialog(
            title = "Cancel subscription?",
            message = "RPN stays active until the end of the paid period, then lapses.",
            confirmLabel = "Cancel plan",
            onConfirm = {
                confirmCancel = false
                vm.cancelSubscription()
            },
            onDismiss = { confirmCancel = false },
        )
    }
    if (confirmRefund) {
        TvConfirmDialog(
            title = "Request a refund?",
            message = "The purchase is revoked and access ends immediately.",
            confirmLabel = "Refund",
            onConfirm = {
                confirmRefund = false
                vm.revokeSubscription()
            },
            onDismiss = { confirmRefund = false },
        )
    }
}

private sealed interface OrderUi {
    data object Loading : OrderUi
    data object Empty : OrderUi
    data class Ready(val count: Int) : OrderUi
    data class Error(val message: String) : OrderUi
}

/**
 * Revoke-window math from the phone's manage-purchase fragment:
 * refund windows are per-product (2yr one-time 14d, 5yr one-time
 * 28d, yearly subs 7d, monthly subs 3d), and the purchase must be
 * ACTIVE.
 */
private fun canRevoke(sub: SubscriptionStatus?): Boolean {
    val purchaseTs = sub?.purchaseTime ?: return false
    if (purchaseTs <= 0L) return false
    if (sub.status != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) return false
    val windowDays = when {
        sub.productId == InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> 14L
        sub.productId == InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> 28L
        sub.productId == InAppBillingHandler.SUBS_PRODUCT_YEARLY -> 7L
        sub.productId == InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> 3L
        else -> 14L
    }
    return System.currentTimeMillis() - purchaseTs < windowDays * 24L * 60L * 60L * 1000L
}
