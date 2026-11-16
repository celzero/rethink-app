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

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.R
import com.celzero.bravedns.iab.ServerOrderEntry
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ServerOrderHistoryViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "RpnTvOrders"

/**
 * TV-native counterpart of `ServerOrderHistoryActivity`: the
 * server-authoritative purchase list from the billing backend, driven
 * by the same [ServerOrderHistoryViewModel] the phone screen uses.
 *
 * Ten-foot adaptations: whole-row focusable order cards in a LazyColumn
 * (no RecyclerView/shimmer), an inline retry action on errors, and no
 * drill-down chips — the local state-machine purchase history
 * (PurchaseHistoryActivity) remains a phone-only surface for now.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnOrderHistoryScreen() {
    val context = LocalContext.current
    val vm: ServerOrderHistoryViewModel = koinViewModel()
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    TvScreenScaffold(
        title = stringResource(R.string.server_order_title),
        subtitle = "Purchases as recorded on the Rethink billing server.",
    ) {
        when (val state = uiState) {
            is ServerOrderHistoryViewModel.UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            is ServerOrderHistoryViewModel.UiState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.server_order_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (state.isNoCredentials) {
                                stringResource(R.string.server_order_empty_no_credentials)
                            } else {
                                stringResource(R.string.server_order_empty_subtitle)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            is ServerOrderHistoryViewModel.UiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
                        ) {
                        Text(
                            text = "Couldn't load orders",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = state.message.take(160),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        RpnActionRow(
                            label = "Retry",
                            icon = Icons.Filled.Autorenew,
                            onClick = { vm.reload() },
                            modifier = Modifier.width(280.dp),
                        )
                    }
                }
            }
            is ServerOrderHistoryViewModel.UiState.Success -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
                    contentPadding = PaddingValues(bottom = 32.dp, end = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.orders, key = { it.purchaseToken + "#" + it.mtime }) { order ->
                        OrderCard(order)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OrderCard(order: ServerOrderEntry) {
    val context = LocalContext.current
    val isSub = order.isSubscription
    val stateLine = when {
        isSub -> order.subscriptionState?.removePrefix("SUBSCRIPTION_STATE_")
            ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—"
        order.purchaseState == ServerOrderEntry.PURCHASE_STATE_PENDING -> "Pending"
        order.purchaseState == ServerOrderEntry.PURCHASE_STATE_CANCELLED -> "Cancelled"
        else -> "Purchased"
    }
    val purchaseTs = if (isSub) order.startTimeMs else order.purchaseTimeMs
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val dateLine = buildString {
        if (purchaseTs > 0) append("Started ${fmt.format(Date(purchaseTs))}")
        if (isSub && order.expiryTimeMs > 0) {
            append(if (isEmpty()) "Expires " else " · Expires ")
            append(fmt.format(Date(order.expiryTimeMs)))
        }
    }

    Surface(
        onClick = {
            val orderId = order.orderId
            if (!orderId.isNullOrBlank()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("order", orderId)
                )
                tvToast(context, context.getString(R.string.copied_clipboard))
            }
        },
        shape = TvSurfaceStyles.interactiveShape(),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TvSpacing.ContentHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isSub) Icons.Filled.EventRepeat else Icons.Filled.CardGiftcard,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(TvSpacing.IconTextGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resolveTvPlanName(context, order.productId, order.planId, order.sku),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        order.orderId,
                        dateLine.ifBlank { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(TvSpacing.IconTextGap))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isSub) "Subscription" else "One-time",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stateLine,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (stateLine == "Active") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun tvToast(context: Context, msg: String) {
    Utilities.showToastUiCentered(context, msg, Toast.LENGTH_LONG)
}
