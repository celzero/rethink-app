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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseConflictNotifier
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver.PurchaseUiModel
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ServerSelectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val RESET_STATUS_POLL_INTERVAL_MS = 1_500L

/** Everything the hero card renders, resolved off the main thread. */
private data class DashboardUi(
    val statusText: String,
    val statusKind: RpnStatusKind,
    val planName: String,
    val purchasedDate: String,
    val identityLine: String,
    val who: String,
    val expiryDateText: String,
    val model: PurchaseUiModel,
    val isActiveEntitlement: Boolean,
)

private data class ServerErrorUi(val title: String, val message: String)

/**
 * TV port of `RethinkPlusDashboardFragment`: entitlement hero, entries
 * into checkout / servers / manage / order-history, the RPN
 * restore-defaults flow (with progress dialog) driven by
 * [ServerSelectionViewModel], the ack-failure banner and the one-shot
 * server-error observers.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnDashboardScreen(navController: NavController) {
    val context = LocalContext.current
    val repo = koinInject<SubscriptionStatusRepository>()
    val vm: ServerSelectionViewModel = viewModel()

    var reloadKey by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetInProgress by remember { mutableStateOf(false) }
    var resetStatusIndex by remember { mutableIntStateOf(0) }
    var serverError by remember { mutableStateOf<ServerErrorUi?>(null) }

    val banner by produceState<DashboardUi?>(initialValue = null, reloadKey) {
        RpnProxyManager.collectSubscriptionState().collect { st ->
            value = withContext(Dispatchers.IO) { buildDashboardUi(context, repo, st) }
        }
    }

    val ackInfo by InAppBillingHandler.ackFailureFlow.collectAsStateWithLifecycle()

    // One-shot server API errors; consumed by nulling the LiveData.
    val serverApiError by InAppBillingHandler.serverApiErrorLiveData.observeAsState()
    LaunchedEffect(serverApiError) {
        val error = serverApiError ?: return@LaunchedEffect
        InAppBillingHandler.serverApiErrorLiveData.value = null
        when (error) {
            is ServerApiError.Conflict409 -> {
                PurchaseConflictNotifier.cancel(context)
                serverError = ServerErrorUi(
                    title = "Purchase conflict",
                    message = "The server reported a conflict while ${error.operation.name.lowercase()} " +
                        "was processed (${error.sku}). Contact support to resolve or refund this purchase.",
                )
            }
            is ServerApiError.Unauthorized401 -> {
                serverError = ServerErrorUi(
                    title = "Device authorization failed",
                    message = "Account ${error.accountId}\nDevice ${error.deviceIdPrefix}…\n" +
                        "Contact support with these identifiers.",
                )
            }
            is ServerApiError.DeviceNotRegistered -> {
                DeviceNotRegisteredNotifier.cancel(context)
                serverError = ServerErrorUi(
                    title = "Device not registered",
                    message = "This device is not registered under the account that owns the " +
                        "subscription (account ${error.entitlementCid}, device ${error.deviceIdPrefix}…). " +
                        "Contact support to re-link your account.",
                )
            }
            is ServerApiError.GenericError -> tvToast(context, error.message)
            is ServerApiError.NetworkError -> tvToast(
                context,
                error.message ?: context.getString(R.string.subscription_action_failed),
            )
            is ServerApiError.None -> {}
        }
    }

    LaunchedEffect(Unit) {
        vm.resetState.collect { st ->
            when (st) {
                is ServerSelectionViewModel.ResetState.InProgress -> resetInProgress = true
                is ServerSelectionViewModel.ResetState.Done -> {
                    vm.onResetConsumed()
                    resetInProgress = false
                    when (val result = st.result) {
                        is RpnProxyManager.ResetResult.Success ->
                            tvToast(context, context.getString(R.string.rpn_restore_success))
                        is RpnProxyManager.ResetResult.Failure ->
                            tvToast(context, context.getString(R.string.rpn_restore_failure, result.reason))
                    }
                    reloadKey++
                }
                is ServerSelectionViewModel.ResetState.NoTunnel -> {
                    vm.onResetConsumed()
                    resetInProgress = false
                    tvToast(context, context.getString(R.string.ssv_toast_start_rethink))
                }
                is ServerSelectionViewModel.ResetState.Idle -> {}
            }
        }
    }

    // Cycle the reset progress message the way the phone dialog does.
    LaunchedEffect(resetInProgress) {
        while (resetInProgress) {
            resetStatusIndex++
            delay(RESET_STATUS_POLL_INTERVAL_MS)
        }
    }

    val ui = banner
    TvScreenScaffold(
        title = "RPN dashboard",
        subtitle = when {
            ui == null -> stringResource(R.string.rpn_status_syncing)
            else -> ui.statusText
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            ackInfo?.let { info ->
                AckFailureBanner(info = info, onSupport = { navController.navigate("rpn/support") })
            }

            HeroCard(ui)

            SettingSectionHeader("Your plan")

            if (ui?.model is PurchaseUiModel.NoPurchase) {
                RpnActionRow(
                    label = "Get RPN",
                    support = "Unlock faster, private proxy servers.",
                    icon = Icons.Filled.AddCard,
                    onClick = { navController.navigate("rpn/checkout") },
                )
            } else if (ui?.isActiveEntitlement == true) {
                RpnActionRow(
                    label = "Extend current plan",
                    support = "Buy additional access time before expiry.",
                    icon = Icons.Filled.AddCard,
                    onClick = { navController.navigate("rpn/checkout?extend=true") },
                )
            }

            RpnActionRow(
                label = "Select server",
                support = "Choose the RPN locations your traffic exits from.",
                icon = Icons.Filled.TravelExplore,
                onClick = { navController.navigate("rpn/servers") },
            )
            RpnActionRow(
                label = "Manage purchase",
                support = "Cancel, refund, order history, manage on Play.",
                icon = Icons.Filled.ManageAccounts,
                onClick = { navController.navigate("rpn/manage") },
            )
            RpnActionRow(
                label = "Order history",
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                onClick = { navController.navigate("rpn/orders") },
            )
            RpnActionRow(
                label = "Run connection test",
                icon = Icons.Filled.Speed,
                onClick = { navController.navigate("rpn/ping") },
            )
            RpnActionRow(
                label = "View RPN stats",
                support = "Windowed traffic totals for RPN-routed connections.",
                icon = Icons.Filled.QueryStats,
                onClick = { navController.navigate("rpn/stats") },
            )
            RpnActionRow(
                label = "Report an issue",
                icon = Icons.Filled.SupportAgent,
                onClick = { navController.navigate("rpn/support") },
            )
            RpnActionRow(
                label = "Restore defaults",
                support = "Reset server selection and re-register this device.",
                icon = Icons.Filled.Autorenew,
                onClick = {
                    if (!VpnController.hasTunnel()) {
                        tvToast(context, context.getString(R.string.ssv_toast_start_rethink))
                    } else {
                        confirmReset = true
                    }
                },
            )
            Spacer(Modifier.height(TvSpacing.SectionGap))
        }
    }

    if (confirmReset) {
        TvMessageDialog(
            title = stringResource(R.string.rpn_restore_confirm_title),
            message = stringResource(R.string.rpn_restore_confirm_message),
            onDismiss = { confirmReset = false },
            confirmLabel = stringResource(R.string.brbs_restore_dialog_positive),
            onConfirm = {
                confirmReset = false
                vm.reset()
            },
            dismissLabel = stringResource(R.string.lbl_cancel),
        )
    }

    if (resetInProgress) {
        val statuses = listOf(
            R.string.rpn_restore_dialog_status_unregistering,
            R.string.rpn_restore_dialog_status_fetching,
            R.string.rpn_restore_dialog_status_registering,
            R.string.rpn_restore_dialog_status_refreshing,
        )
        TvMessageDialog(
            title = stringResource(R.string.rpn_restore_confirm_title),
            message = stringResource(statuses[resetStatusIndex % statuses.size]) +
                "\n" + stringResource(R.string.progress_do_not_close),
            onDismiss = {},
            confirmLabel = "",
            onConfirm = null,
            spinner = true,
        )
    }

    serverError?.let { err ->
        TvMessageDialog(
            title = err.title,
            message = err.message,
            onDismiss = { serverError = null },
            confirmLabel = "Contact support",
            onConfirm = {
                serverError = null
                navController.navigate("rpn/support")
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroCard(ui: DashboardUi?) {
    androidx.tv.material3.Surface(
        shape = TvShapes.Medium,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui == null) {
                    RpnStatusChip(
                        text = "",
                        kind = RpnStatusKind.DIM,
                    )
                } else {
                    RpnStatusChip(text = ui.statusText, kind = ui.statusKind)
                }
                Spacer(Modifier.width(TvSpacing.CardGap))
                if (ui?.who?.isNotEmpty() == true) {
                    Text(
                        text = ui.who,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = ui?.planName ?: stringResource(R.string.rpn_status_syncing),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (ui != null) {
                Text(
                    text = stringResource(R.string.rpn_overhauled_purchased_date_label, ui.purchasedDate),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.expiryDateText.isNotEmpty()) {
                    Text(
                        text = "Expires ${ui.expiryDateText}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (ui.identityLine.isNotBlank()) {
                    Text(
                        text = ui.identityLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Mirrors `RethinkPlusDashboardFragment.loadSubscriptionBanner` + `populateBanner`. */
private suspend fun buildDashboardUi(
    ctx: Context,
    repo: SubscriptionStatusRepository,
    state: SubscriptionStateMachineV2.SubscriptionState,
): DashboardUi {
    return withContext(Dispatchers.IO) {
        val sub = runCatching { repo.getCurrentSubscription() }.getOrNull()
        val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
        val expiry = runCatching<Long?> { VpnController.getWinExpiryTs() }.getOrNull() ?: 0L
        val who = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()

        val model = SubscriptionUiStateResolver.resolve(state, sub)
        val dbRowCancelled = sub?.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
        val playConfirmsRenewal =
            runCatching { RpnProxyManager.getSubscriptionData()?.purchaseDetail?.isAutoRenewing }
                .getOrNull() == true
        val effectivelyCancelled = dbRowCancelled && !playConfirmsRenewal

        val (statusText, kind) = resolveStatusChip(ctx, state, model, effectivelyCancelled)
        val fmt = heroDateFormat()

        val planName: String
        val purchasedDate: String
        val identityLine: String
        when (model) {
            is PurchaseUiModel.Loading -> {
                planName = ctx.getString(R.string.rpn_status_syncing)
                purchasedDate = ctx.getString(R.string.lbl_not_available_short)
                identityLine = ""
            }
            is PurchaseUiModel.NoPurchase -> {
                planName = ctx.getString(R.string.rpn_no_active_plan_title)
                purchasedDate = ctx.getString(R.string.lbl_not_available_short)
                identityLine = ""
            }
            is PurchaseUiModel.Lapsed -> {
                planName = resolvePlanFromData(ctx, model.sub?.productId, model.sub?.planId, model.sub?.productTitle)
                purchasedDate = formatHeroDate(fmt, model.sub?.purchaseTime ?: 0)
                    ?: ctx.getString(R.string.lbl_not_available_short)
                identityLine = heroIdentityLine(
                    model.sub?.purchaseToken.orEmpty(),
                    model.sub?.accountId.orEmpty(),
                    deviceId,
                    expiry.toString(16),
                )
            }
            is PurchaseUiModel.Valid -> {
                planName = resolvePlanFromData(ctx, sub?.productId, sub?.planId, sub?.productTitle)
                purchasedDate = formatHeroDate(fmt, sub?.purchaseTime ?: 0)
                    ?: ctx.getString(R.string.placeholder_dash)
                identityLine = heroIdentityLine(
                    sub?.purchaseToken.orEmpty(),
                    sub?.accountId.orEmpty(),
                    deviceId,
                    expiry.toString(16),
                )
            }
        }

        DashboardUi(
            statusText = statusText,
            statusKind = kind,
            planName = planName,
            purchasedDate = purchasedDate,
            identityLine = identityLine,
            who = who,
            expiryDateText = formatHeroDate(fmt, expiry).orEmpty(),
            model = model,
            isActiveEntitlement = state.isActive && !effectivelyCancelled,
        )
    }
}

/** Plan name preferring the live purchase detail, falling back to the DB row. */
private fun resolvePlanFromData(ctx: Context, productId: String?, planId: String?, title: String?): String {
    val fromData = RpnProxyManager.getSubscriptionData()?.let { data ->
        val pid = data.purchaseDetail?.productId.orEmpty()
        val plid = data.purchaseDetail?.planId.orEmpty()
        resolveTvPlanName(
            ctx,
            pid,
            plid,
            data.purchaseDetail?.productTitle.orEmpty(),
        )
    }.orEmpty()
    return fromData.ifBlank {
        resolveTvPlanName(ctx, productId.orEmpty(), planId.orEmpty(), title.orEmpty())
    }.ifBlank { ctx.getString(R.string.lbl_not_available_short) }
}

private fun tvToast(ctx: Context, msg: String) {
    Utilities.showToastUiCentered(ctx, msg, Toast.LENGTH_LONG)
}
