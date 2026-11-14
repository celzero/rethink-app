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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.InAppBillingHandler
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
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ManagePurchaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val TAG = "RpnTvManage"
private const val ROW_DISABLED_ALPHA = 0.38f

/** Render model for the manage hero + gated action rows. */
private data class ManageUi(
    val statusText: String,
    val statusKind: RpnStatusKind,
    val planName: String,
    val purchasedDate: String,
    val identityLine: String,
    val who: String,
    val model: PurchaseUiModel,
    val state: SubscriptionStateMachineV2.SubscriptionState,
    val isOneTime: Boolean,
    val showRefund: Boolean,
    val showCancel: Boolean,
    val isActive: Boolean,
    val hasPurchaseRow: Boolean,
)

/**
 * TV port of `RethinkPlusManagePurchaseFragment`: plan status hero plus
 * order history / manage-on-Play / refund / cancel actions, with the
 * same row gating (disabled at alpha 0.38, manage-on-Play hidden for
 * one-time products, cancel/refund offered per revoke window).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnManageScreen(navController: NavController) {
    val context = LocalContext.current
    val repo = koinInject<SubscriptionStatusRepository>()
    val vm: ManagePurchaseViewModel = viewModel()

    var reloadKey by remember { mutableIntStateOf(0) }
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var opMessage by remember { mutableStateOf<String?>(null) }
    var opIsCancel by remember { mutableStateOf(false) }
    var alreadyEnded by remember { mutableStateOf(false) }

    val ui by produceState<ManageUi?>(initialValue = null, reloadKey) {
        RpnProxyManager.collectSubscriptionState().collect { st ->
            value = withContext(Dispatchers.IO) { buildManageUi(context, repo, st) }
        }
    }

    val ackInfo by InAppBillingHandler.ackFailureFlow.collectAsStateWithLifecycle()

    // Re-sync purchases when returning from the Play subscriptions page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                InAppBillingHandler.fetchPurchases(
                    listOf(
                        InAppBillingHandler.PRODUCT_TYPE_SUBS,
                        InAppBillingHandler.PRODUCT_TYPE_INAPP,
                    ),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Cancel / revoke operation lifecycle → progress dialog / toasts.
    LaunchedEffect(Unit) {
        vm.operationState.collect { state ->
            when (state) {
                is ManagePurchaseViewModel.OperationState.Idle -> opMessage = null
                is ManagePurchaseViewModel.OperationState.InProgress -> {
                    opIsCancel = state.isCancel
                    opMessage = buildString {
                        append(
                            if (state.isCancel) context.getString(R.string.manage_sub_cancelling)
                            else context.getString(R.string.manage_sub_revoking),
                        )
                        append("\n")
                        append(stepLabel(context, state.step))
                        append("\n")
                        append(context.getString(R.string.progress_do_not_close))
                    }
                }
                is ManagePurchaseViewModel.OperationState.Success -> {
                    opMessage = null
                    tvToast(context, context.getString(state.messageRes))
                    reloadKey++
                    vm.resetOperationState()
                }
                is ManagePurchaseViewModel.OperationState.Failure -> {
                    opMessage = null
                    tvToast(
                        context,
                        state.serverMessage ?: context.getString(state.messageRes),
                    )
                    vm.resetOperationState()
                }
                is ManagePurchaseViewModel.OperationState.AlreadyEnded -> {
                    opMessage = null
                    alreadyEnded = true
                    reloadKey++
                    vm.resetOperationState()
                }
            }
        }
    }

    val m = ui
    TvScreenScaffold(
        title = "Manage purchase",
        subtitle = when {
            m == null -> stringResource(R.string.rpn_status_syncing)
            else -> m.statusText
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

            ManageHeroCard(m)

            SettingSectionHeader("Purchase")

            RpnActionRow(
                label = "Order history",
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                enabled = m?.hasPurchaseRow == true,
                onClick = { navController.navigate("rpn/orders") },
            )

            if (m?.isOneTime == false) {
                RpnActionRow(
                    label = "Manage subscription on Google Play",
                    support = "Change payment method or cancel auto-renew on the Play Store.",
                    icon = Icons.Filled.Shop,
                    enabled = m.model !is PurchaseUiModel.NoPurchase,
                    onClick = { manageOnPlay(context) },
                )
            }

            RpnActionRow(
                label = "Report billing issue",
                icon = Icons.Filled.SupportAgent,
                onClick = { navController.navigate("rpn/support") },
            )

            if (m != null && (m.showRefund || m.showCancel)) {
                SettingSectionHeader("Ending your plan")
                if (m.showRefund) {
                    RpnActionRow(
                        label = stringResource(R.string.revoke_subscription),
                        support = stringResource(R.string.confirm_revoke_message),
                        icon = Icons.Filled.CurrencyExchange,
                        destructive = true,
                        onClick = { confirmRevoke = true },
                    )
                }
                if (m.showCancel) {
                    RpnActionRow(
                        label = stringResource(R.string.cancel_subscription),
                        support = stringResource(R.string.confirm_cancel_message),
                        icon = Icons.Filled.Cancel,
                        destructive = true,
                        onClick = { confirmCancel = true },
                    )
                }
            }

            if (m?.isActive == true) {
                SettingSectionHeader("Extend")
                RpnActionRow(
                    label = "Extend current plan",
                    support = "Buy additional access time before expiry.",
                    icon = Icons.Filled.AddCard,
                    onClick = { navController.navigate("rpn/checkout?extend=true") },
                )
            }
            Spacer(Modifier.height(TvSpacing.SectionGap))
        }
    }

    if (confirmCancel || confirmRevoke) {
        val isCancel = confirmCancel
        TvMessageDialog(
            title = stringResource(if (isCancel) R.string.confirm_cancel_title else R.string.confirm_revoke_title),
            message = stringResource(if (isCancel) R.string.confirm_cancel_message else R.string.confirm_revoke_message),
            onDismiss = {
                confirmCancel = false
                confirmRevoke = false
            },
            confirmLabel = stringResource(
                if (isCancel) R.string.cancel_subscription else R.string.revoke_subscription,
            ),
            onConfirm = {
                if (isCancel) vm.cancelSubscription() else vm.revokeSubscription()
                confirmCancel = false
                confirmRevoke = false
            },
            dismissLabel = stringResource(R.string.lbl_cancel),
            destructive = true,
        )
    }

    opMessage?.let { msg ->
        TvMessageDialog(
            title = stringResource(
                if (opIsCancel) R.string.manage_sub_cancelling else R.string.manage_sub_revoking,
            ),
            message = msg,
            onDismiss = {},
            confirmLabel = "",
            onConfirm = null,
            spinner = true,
        )
    }

    if (alreadyEnded) {
        TvMessageDialog(
            title = stringResource(R.string.rpn_purchase_already_ended_title),
            message = stringResource(R.string.rpn_purchase_already_ended_message),
            onDismiss = { alreadyEnded = false },
            confirmLabel = stringResource(android.R.string.ok),
            onConfirm = { alreadyEnded = false },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageHeroCard(ui: ManageUi?) {
    androidx.tv.material3.Surface(
        shape = TvShapes.Medium,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            if (ui != null) {
                RpnStatusChip(text = ui.statusText, kind = ui.statusKind)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = ui.planName,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.rpn_overhauled_purchased_date_label, ui.purchasedDate),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.identityLine.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = ui.identityLine,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.rpn_status_syncing),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * Mirrors `RethinkPlusManagePurchaseFragment.populateView` incl. the
 * row-gating rules (effectively-cancelled guard, revoke window, one-time
 * product checks).
 */
private suspend fun buildManageUi(
    ctx: Context,
    repo: SubscriptionStatusRepository,
    state: SubscriptionStateMachineV2.SubscriptionState,
): ManageUi {
    return withContext(Dispatchers.IO) {
        val sub = runCatching { repo.getCurrentSubscription() }.getOrNull()
        val data = RpnProxyManager.getSubscriptionData()
        val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
        val expiry = runCatching<Long?> { VpnController.getWinExpiryTs() }.getOrNull() ?: 0L
        val who = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()

        val model = SubscriptionUiStateResolver.resolve(state, sub)
        val dbRowCancelled = sub?.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
        val playConfirmsRenewal = data?.purchaseDetail?.isAutoRenewing == true
        val effectivelyCancelled = dbRowCancelled && !playConfirmsRenewal

        val (statusText, kind) = resolveStatusChip(ctx, state, model, effectivelyCancelled)
        val fmt = heroDateFormat()

        val productId = sub?.productId ?: data?.purchaseDetail?.productId.orEmpty()
        val planId = sub?.planId ?: data?.purchaseDetail?.planId.orEmpty()
        val isOneTime = isOneTimeProduct(productId, planId)

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
                planName = resolveTvPlanName(ctx, productId, planId, model.sub?.productTitle.orEmpty())
                    .ifBlank { ctx.getString(R.string.lbl_not_available_short) }
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
                planName = resolveTvPlanName(ctx, productId, planId, sub?.productTitle.orEmpty())
                purchasedDate = formatHeroDate(fmt, sub?.purchaseTime ?: 0)
                    ?: ctx.getString(R.string.lbl_not_available_short)
                identityLine = heroIdentityLine(
                    sub?.purchaseToken.orEmpty(),
                    sub?.accountId.orEmpty(),
                    deviceId,
                    expiry.toString(16),
                )
            }
        }

        val activeAndNotEnding = state.isActive && !effectivelyCancelled
        val canRevoke = canRevokePurchase(data)

        ManageUi(
            statusText = statusText,
            statusKind = kind,
            planName = planName,
            purchasedDate = purchasedDate,
            identityLine = identityLine,
            who = who,
            model = model,
            state = state,
            isOneTime = isOneTime,
            showRefund = activeAndNotEnding && canRevoke,
            showCancel = activeAndNotEnding && !canRevoke && !isOneTime,
            isActive = state.isActive && !effectivelyCancelled,
            hasPurchaseRow = sub != null,
        )
    }
}

/** Opens the Play subscriptions page; purchases re-sync on ON_RESUME. */
private fun manageOnPlay(context: Context) {
    try {
        val productId = RpnProxyManager.getRpnProductId()
        if (productId.isEmpty()) {
            tvToast(context, context.getString(R.string.error_loading_manage_subscription))
            return
        }
        val link = InAppBillingHandler.PLAY_SUBS_LINK
            .replace("$1", productId)
            .replace("$2", context.packageName)
        UIUtils.openUrl(context, link)
    } catch (e: Exception) {
        Logger.e(LOG_TAG_UI, "$TAG err managing play store subs: ${e.message}", e)
        tvToast(context, context.getString(R.string.error_loading_manage_subscription))
    }
}

private fun stepLabel(ctx: Context, step: ManagePurchaseViewModel.Step): String = when (step) {
    ManagePurchaseViewModel.Step.VALIDATING -> "Validating current subscription…"
    ManagePurchaseViewModel.Step.CONTACTING_SERVER -> "Contacting the Rethink server…"
    ManagePurchaseViewModel.Step.UPDATING_LOCAL -> "Updating local state…"
    ManagePurchaseViewModel.Step.REFRESHING -> "Refreshing purchase data…"
    ManagePurchaseViewModel.Step.DONE -> "Done"
}

private fun tvToast(ctx: Context, msg: String) {
    Utilities.showToastUiCentered(ctx, msg, Toast.LENGTH_LONG)
}
