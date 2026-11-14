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

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.livedata.observeAsState
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.R
import com.celzero.bravedns.iab.BillingListener
import com.celzero.bravedns.iab.BillingResponse
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSizes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.RethinkPlusViewModel
import com.celzero.bravedns.viewmodel.SubscriptionUiState
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RpnTvCheckout"
private const val PROCESSING_TIMEOUT_MS = 60_000L

/**
 * Overlay / dialog states driven by [SubscriptionUiState] plus the
 * process-wide billing event streams. `Processing` carries a session id
 * so the 60 s watchdog does not restart every time the pending-purchase
 * poller updates the message.
 */
private sealed class CheckoutDialog {
    data class Processing(val message: String, val sessionId: Int) : CheckoutDialog()
    data class PendingTimeout(val message: String) : CheckoutDialog()
    data class AckPending(val message: String) : CheckoutDialog()
    data class TransactionError(val message: String) : CheckoutDialog()
    data class Success(val title: String, val message: String, val isExtend: Boolean) : CheckoutDialog()
}

/**
 * TV port of the website channel's `RethinkPlusFragment`. All business
 * logic lives in the shared [RethinkPlusViewModel]; this screen only
 * renders its states and forwards billing events, mirroring the
 * fragment's observers one-for-one:
 *
 *  * `transactionErrorLiveData` / `serverApiErrorLiveData` are consumed
 *    one-shot (nulled after handling) exactly like the fragment.
 *  * `itemAlreadyOwnedFlow`, `playServicesInterruptedFlow` and the
 *    ViewModel's `retryConnectionEvent` are collected while composed.
 *  * The processing overlay is dropped after 60 s without a callback,
 *    then billing re-initializes.
 *
 * Payment is launched on-device via `launchBillingFlow`; on Google TV
 * the Play sheet is remote-navigable.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnCheckoutScreen(navController: NavController, extendMode: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val vm: RethinkPlusViewModel = viewModel()

    var dialog by remember { mutableStateOf<CheckoutDialog?>(null) }
    var session by remember { mutableIntStateOf(0) }
    var purchaseInFlight by remember { mutableStateOf(false) }

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val selectedProduct by vm.selectedProduct.collectAsStateWithLifecycle()
    val productType by vm.selectedProductType.collectAsStateWithLifecycle()

    val listener = remember {
        TvBillingListener(
            onConnection = { ok, msg -> vm.onBillingConnected(ok, msg) },
            onProducts = { ok, list -> vm.onProductsFetched(ok, list, InAppBillingHandler.lastProductQueryResponseCode) },
            onPurchaseRejected = {
                // state machine refused the purchase start; re-enable Subscribe
                purchaseInFlight = false
                dialog = null
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { InAppBillingHandler.unregisterListener(listener) }
    }

    LaunchedEffect(Unit) {
        vm.extendMode = extendMode
        vm.initializeBilling()
        if (!InAppBillingHandler.isBillingClientSetup()) {
            InAppBillingHandler.initiate(context.applicationContext, listener)
        } else {
            InAppBillingHandler.registerListener(listener)
            vm.onBillingConnected(true, "already connected")
        }
        activity?.let { runCatching { InAppBillingHandler.enableInAppMessaging(it) } }
    }

    LaunchedEffect(Unit) {
        vm.retryConnectionEvent.collect {
            Logger.d(LOG_IAB, "$TAG: retryConnectionEvent, re-initiating billing")
            InAppBillingHandler.initiate(context.applicationContext, listener)
        }
    }

    // One-shot transaction errors (user cancel, network, ...). Consumed
    // by nulling the LiveData so recomposition does not replay them.
    val txError by InAppBillingHandler.transactionErrorLiveData.observeAsState()
    LaunchedEffect(txError) {
        val result: BillingResult = txError ?: return@LaunchedEffect
        InAppBillingHandler.transactionErrorLiveData.value = null
        purchaseInFlight = false
        vm.onTransactionError()
        val response = BillingResponse(result.responseCode)
        if (response.isUserCancelled) {
            Logger.d(LOG_IAB, "$TAG: user cancelled purchase")
            dialog = null
        } else {
            dialog = CheckoutDialog.TransactionError(friendlyBillingMessage(context, response.rawCode))
        }
    }

    // Server API errors (401/409/...) surfaced during the purchase flow.
    val serverError by InAppBillingHandler.serverApiErrorLiveData.observeAsState()
    LaunchedEffect(serverError) {
        val error = serverError ?: return@LaunchedEffect
        InAppBillingHandler.serverApiErrorLiveData.value = null
        purchaseInFlight = false
        dialog = null
        when (error) {
            is ServerApiError.Unauthorized401,
            is ServerApiError.Conflict409,
            -> tvToast(context, context.getString(R.string.subscription_action_failed))
            else -> { /* handled by the state machine / toasts elsewhere */ }
        }
    }

    LaunchedEffect(Unit) {
        InAppBillingHandler.itemAlreadyOwnedFlow.collect {
            dialog = null
            tvToast(context, context.getString(R.string.item_already_owned_restoring))
        }
    }

    LaunchedEffect(Unit) {
        InAppBillingHandler.playServicesInterruptedFlow.collect { code ->
            if (code > 0) {
                Logger.w(LOG_IAB, "$TAG: playServicesInterrupted code=$code")
                vm.onPlayServicesError(code)
            }
        }
    }

    // Map ViewModel UI state → overlay dialogs and navigation.
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is SubscriptionUiState.Loading,
            is SubscriptionUiState.Ready,
            is SubscriptionUiState.NoInternet,
            is SubscriptionUiState.Error,
            is SubscriptionUiState.AcknowledgementFailed,
            -> {
                purchaseInFlight = false
                dialog = null
            }
            is SubscriptionUiState.Processing -> {
                purchaseInFlight = true
                dialog = CheckoutDialog.Processing(s.message, ++session)
            }
            is SubscriptionUiState.PendingPurchase -> {
                purchaseInFlight = true
                val msg = s.message.ifBlank { context.getString(R.string.verifying_with_play_store) }
                dialog = if (dialog is CheckoutDialog.Processing) {
                    (dialog as CheckoutDialog.Processing).copy(message = msg)
                } else {
                    CheckoutDialog.Processing(msg, ++session)
                }
            }
            is SubscriptionUiState.PendingTimeout -> {
                purchaseInFlight = false
                dialog = CheckoutDialog.PendingTimeout(
                    s.message.ifBlank { context.getString(R.string.pending_timeout_message) },
                )
            }
            is SubscriptionUiState.ServerAckPending -> {
                purchaseInFlight = false
                dialog = CheckoutDialog.AckPending(
                    s.message.ifBlank { context.getString(R.string.server_ack_pending_message) },
                )
            }
            is SubscriptionUiState.Success -> {
                purchaseInFlight = false
                dialog = if (s.isExtend) {
                    CheckoutDialog.Success(
                        context.getString(R.string.extend_purchase_activated),
                        context.getString(R.string.extend_purchase_success_message),
                        isExtend = true,
                    )
                } else {
                    CheckoutDialog.Success(
                        context.getString(R.string.subscription_congrats_title),
                        context.getString(R.string.subscription_congrats_desc),
                        isExtend = false,
                    )
                }
            }
            is SubscriptionUiState.AlreadySubscribed -> {
                navController.navigate(RPN_DASHBOARD_ROUTE) {
                    popUpTo(RPN_CHECKOUT_PATTERN) { inclusive = true }
                }
            }
            is SubscriptionUiState.Available -> { /* keep the current surface */ }
        }
    }

    // Drop the processing overlay after 60 s without a callback, then
    // re-check billing (the phone fragment does this on resume).
    LaunchedEffect((dialog as? CheckoutDialog.Processing)?.sessionId) {
        val id = (dialog as? CheckoutDialog.Processing)?.sessionId ?: return@LaunchedEffect
        delay(PROCESSING_TIMEOUT_MS)
        if ((dialog as? CheckoutDialog.Processing)?.sessionId == id) {
            // a purchase is pending with Play; keep waiting rather than
            // dropping the overlay and re-initializing billing mid-verify
            if (uiState is SubscriptionUiState.PendingPurchase) return@LaunchedEffect
            Logger.w(LOG_IAB, "$TAG: processing timeout, dismissing overlay")
            purchaseInFlight = false
            dialog = null
            tvToast(context, context.getString(R.string.subscription_processing_timeout))
            vm.initializeBilling()
        }
    }

    // Success → auto-navigate to the dashboard (extend pops back to it).
    LaunchedEffect(dialog) {
        val d = dialog as? CheckoutDialog.Success ?: return@LaunchedEffect
        delay(1500)
        if (d.isExtend) {
            navController.popBackStack()
        } else {
            navController.navigate(RPN_DASHBOARD_ROUTE) {
                popUpTo(RPN_CHECKOUT_PATTERN) { inclusive = true }
            }
        }
    }

    when (val s = uiState) {
        is SubscriptionUiState.Loading -> CheckoutLoadingContent()
        is SubscriptionUiState.NoInternet -> CheckoutErrorContent(
            title = stringResource(R.string.no_internet_title),
            message = s.message.ifBlank { stringResource(R.string.no_internet_premium_msg) },
            reason = null,
            retryable = true,
            onRetry = { vm.retry() },
            onSupport = { navController.navigate("rpn/support") },
        )
        is SubscriptionUiState.Error -> {
            // An empty plan list from Play usually means the account is not a
            // license tester or the products are not published for this listing —
            // surface that on the ten-foot UI so the cause is actionable instead
            // of a bare "try again".
            val emptyReason = stringResource(R.string.product_fetch_err_empty)
            CheckoutErrorContent(
                title = s.title,
                message = s.message,
                reason = s.reason.ifBlank { null },
                retryable = s.isRetryable,
                onRetry = { vm.retry() },
                onSupport = { navController.navigate("rpn/support") },
                hint = if (s.reason == emptyReason) {
                    "Play returned no plans for this build. Verify this account is a " +
                        "license tester for the app and that its products are published " +
                        "for this listing."
                } else {
                    null
                },
            )
        }        is SubscriptionUiState.AcknowledgementFailed -> {
            val fullMessage = if (s.refundInitiated) {
                s.message + "\n\n" + stringResource(R.string.ack_refund_initiated)
            } else {
                s.message
            }
            CheckoutErrorContent(
                title = s.title,
                message = fullMessage,
                reason = null,
                retryable = s.canRetry,
                onRetry = { vm.retry() },
                onSupport = { navController.navigate("rpn/support") },
            )
        }
        else -> CheckoutReadyContent(
            state = s,
            selectedProduct = selectedProduct,
            productType = productType,
            extendMode = extendMode,
            purchaseInFlight = purchaseInFlight,
            onToggleType = { vm.selectProductType(it) },
            onSelectProduct = { pid, plid -> vm.selectProduct(pid, plid) },
            onSubscribe = {
                onSubscribeClicked(
                    context = context,
                    activity = activity,
                    vm = vm,
                    purchaseInFlight = purchaseInFlight,
                    markInFlight = { purchaseInFlight = it },
                    scope = scope,
                )
            },
        )
    }

    CheckoutDialogHost(
        dialog = dialog,
        onCheckStatus = { vm.checkPendingStatus() },
        onSupport = { navController.navigate("rpn/support") },
        onDismissError = { dialog = null },
    )
}

/** Subscribe CTA handler; mirrors `RethinkPlusFragment.purchaseSubscription`. */
private fun onSubscribeClicked(
    context: Context,
    activity: Activity?,
    vm: RethinkPlusViewModel,
    purchaseInFlight: Boolean,
    markInFlight: (Boolean) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (purchaseInFlight) {
        Logger.d(LOG_IAB, "$TAG: purchase already in flight, ignoring tap")
        return
    }
    val unresolved = vm.lastUnresolved.value
    if (unresolved is SubscriptionUiState.ServerAckPending ||
        unresolved is SubscriptionUiState.Error ||
        unresolved is SubscriptionUiState.AcknowledgementFailed
    ) {
        tvToast(context, context.getString(R.string.verifying_with_play_store))
        vm.reverifyAfterFailure()
        return
    }
    val selection = vm.selectedProduct.value
    if (selection == null) {
        tvToast(context, context.getString(R.string.select_plan_first))
        return
    }
    if (!isOnline(context)) {
        tvToast(context, context.getString(R.string.no_internet_purchase_msg))
        return
    }
    val act = activity ?: return
    markInFlight(true)
    scope.launch {
        val (productId, planId) = selection
        when (vm.selectedProductType.value) {
            RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION ->
                InAppBillingHandler.purchaseSubs(act, productId, planId)
            RethinkPlusViewModel.ProductTypeFilter.ONE_TIME -> {
                if (vm.extendMode) vm.markPurchaseFlowActive()
                Logger.vv(
                    LOG_IAB,
                    "$TAG: one-time purchase productId=$productId planId=$planId extend=${vm.extendMode}",
                )
                InAppBillingHandler.purchaseOneTime(act, productId, planId, forceExtend = vm.extendMode)
            }
        }
    }
}

/** `BillingListener` forwarding to the shared ViewModel. */
private class TvBillingListener(
    private val onConnection: (Boolean, String) -> Unit,
    private val onProducts: (Boolean, List<ProductDetail>) -> Unit,
    private val onPurchaseRejected: () -> Unit,
) : BillingListener {
    override fun onConnectionResult(isSuccess: Boolean, message: String) = onConnection(isSuccess, message)
    override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<com.celzero.bravedns.iab.PurchaseDetail>) {
        // handled by the state machine via InAppBillingHandler
    }
    override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) =
        onProducts(isSuccess, productList)
    override fun purchaseRejected() = onPurchaseRejected()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CheckoutLoadingContent() {
    TvTopLevel(title = "RPN") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Loading plans…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CheckoutErrorContent(
    title: String,
    message: String,
    reason: String?,
    retryable: Boolean,
    onRetry: () -> Unit,
    onSupport: () -> Unit,
    hint: String? = null,
) {
    TvTopLevel(title = "RPN") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = reason ?: message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
                    if (retryable) {
                        RpnActionRow(
                            label = "Retry",
                            icon = Icons.Filled.Autorenew,
                            onClick = onRetry,
                            modifier = Modifier.width(260.dp),
                        )
                    }
                    RpnActionRow(
                        label = "Contact support",
                        icon = Icons.Filled.SupportAgent,
                        onClick = onSupport,
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CheckoutReadyContent(
    state: SubscriptionUiState,
    selectedProduct: Pair<String, String>?,
    productType: RethinkPlusViewModel.ProductTypeFilter,
    extendMode: Boolean,
    purchaseInFlight: Boolean,
    onToggleType: (RethinkPlusViewModel.ProductTypeFilter) -> Unit,
    onSelectProduct: (String, String) -> Unit,
    onSubscribe: () -> Unit,
) {
    val context = LocalContext.current
    val products = (state as? SubscriptionUiState.Ready)?.products ?: emptyList()
    val availability = (state as? SubscriptionUiState.Ready)?.availabilityData
        ?: (state as? SubscriptionUiState.Available)

    TvTopLevel(
        title = if (extendMode) "Extend your plan" else "RPN",
        subtitle = "Rethink Private Network — faster, private proxy servers.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (extendMode) {
                InfoBanner(text = "You are buying additional access time on top of your current plan.")
            } else {
                ProductTypeToggle(selected = productType, onSelect = onToggleType)
            }

            if (!extendMode && availability != null && availability.ip.isNotEmpty()) {
                ConnectionInfoCard(availability)
            }

            if (products.isEmpty()) {
                Text(
                    text = "No plans match this filter.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Play returns the legacy single-offer shape for one-time
                // products on devices with an older Play Store — the 2y/5y
                // purchase options simply aren't in the response. Tell the
                // user why the plan list looks short instead of leaving a
                // lone card unexplained.
                if (productType == RethinkPlusViewModel.ProductTypeFilter.ONE_TIME &&
                    products.size == 1 &&
                    products.first().planId == InAppBillingHandler.ONE_TIME_PRODUCT_ID
                ) {
                    InfoBanner(
                        text = "Only the base one-time plan was returned by the Play Store on " +
                            "this device. Other plans (e.g. the 5-year option) require a " +
                            "newer Google Play Store version — update the Play Store app " +
                            "and reopen this screen.",
                    )
                }
                products.chunked(2).forEach { rowProducts ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        rowProducts.forEach { product ->
                            PlanCard(
                                product = product,
                                selected = selectedProduct?.first == product.productId &&
                                    selectedProduct.second == product.planId,
                                onSelected = onSelectProduct,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowProducts.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            SubscribeCta(
                selected = selectedProduct,
                purchaseInFlight = purchaseInFlight,
                onSubscribe = onSubscribe,
            )

            Text(
                text = stripHtml(context.getString(R.string.rethink_terms)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvTopLevel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    com.celzero.bravedns.tv.ui.common.TvScreenScaffold(title = title, subtitle = subtitle) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProductTypeToggle(
    selected: RethinkPlusViewModel.ProductTypeFilter,
    onSelect: (RethinkPlusViewModel.ProductTypeFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
        ToggleChip(
            label = "One-time",
            selected = selected == RethinkPlusViewModel.ProductTypeFilter.ONE_TIME,
            onClick = { onSelect(RethinkPlusViewModel.ProductTypeFilter.ONE_TIME) },
        )
        ToggleChip(
            label = "Subscription",
            selected = selected == RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION,
            onClick = { onSelect(RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION) },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoBanner(text: String) {
    androidx.tv.material3.Surface(
        shape = TvShapes.Small,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectionInfoCard(availability: SubscriptionUiState.Available) {
    androidx.tv.material3.Surface(
        shape = TvShapes.Small,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            val locationParts = mutableListOf<String>()
            if (availability.city.isNotEmpty()) locationParts.add(availability.city)
            if (availability.country.isNotEmpty()) locationParts.add(availability.country.uppercase())
            val locationText = locationParts.joinToString(", ")
            Text(
                text = "Your connection: ${availability.ip} · $locationText · ${availability.colo}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * One plan card; content mirrors `GooglePlaySubsAdapter`'s binding
 * (price, trial, savings, duration label, billing text).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlanCard(
    product: ProductDetail,
    selected: Boolean,
    onSelected: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pricing = product.pricingDetails.firstOrNull()

    var currentPrice = ""
    var discountedPrice = ""
    var currentPriceMicros = 0L
    var discountedPriceMicros = 0L
    var freeTrialDays = 0
    pricing?.let { p0 ->
        product.pricingDetails.forEach { phase ->
            when {
                phase.freeTrialPeriod > 0 -> freeTrialDays = phase.freeTrialPeriod
                phase.recurringMode == InAppBillingHandler.RecurringMode.DISCOUNTED -> {
                    discountedPrice = phase.price
                    discountedPriceMicros = phase.priceAmountMicros
                }
                phase.recurringMode == InAppBillingHandler.RecurringMode.ORIGINAL -> {
                    currentPrice = phase.price
                    currentPriceMicros = phase.priceAmountMicros
                }
            }
        }
    }
    val displayPrice = discountedPrice.ifEmpty { currentPrice }
    val isInApp = product.productType == BillingClient.ProductType.INAPP
    val durationMonths = inAppDurationMonths(product.planId)
    val planTitle = pricing?.planTitle.orEmpty()
    val title = if (isInApp && durationMonths > 0) durationLabel(context, durationMonths) else planTitle

    val billingInfo = when {
        freeTrialDays > 0 -> context.getString(R.string.trial_days_format, freeTrialDays)
        isInApp -> context.getString(R.string.billing_no_recurring)
        pricing?.billingPeriod?.contains("P1M", true) == true ->
            context.getString(R.string.billing_monthly_cancel)
        pricing?.billingPeriod?.contains("P1Y", true) == true ->
            context.getString(R.string.billing_annually_cancel)
        else -> context.getString(R.string.billing_sub_cancel)
    }
    val savingsPct = if (isInApp) {
        pricing?.discountPercent ?: 0
    } else if (discountedPrice.isNotEmpty()) {
        savingsPercent(currentPriceMicros, discountedPriceMicros)
    } else {
        0
    }

    Surface(
        onClick = { onSelected(product.productId, product.planId) },
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Medium),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = TvSpacing.ContentHorizontal,
                    vertical = 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { product.productId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = billingInfo,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (savingsPct > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = context.getString(R.string.savings_percent, "$savingsPct%"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(TvSpacing.IconTextGap))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = displayPrice.ifBlank { "—" },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (selected) {
                    Spacer(Modifier.height(4.dp))
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(TvSizes.LeadingIcon),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubscribeCta(
    selected: Pair<String, String>?,
    purchaseInFlight: Boolean,
    onSubscribe: () -> Unit,
) {
    val context = LocalContext.current
    val days = revokeDaysFor(selected?.first, selected?.second)
    androidx.tv.material3.Surface(
        shape = TvShapes.Medium,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(TvSpacing.ContentHorizontal),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "${InAppBillingHandler.MONEYBACK_WINDOW_DAYS}-day money-back guarantee",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = context.getString(R.string.cancel_refund_policy, days.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                onClick = onSubscribe,
                enabled = !purchaseInFlight,
                shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
                ),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (purchaseInFlight) "Processing…" else "Subscribe",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CheckoutDialogHost(
    dialog: CheckoutDialog?,
    onCheckStatus: () -> Unit,
    onSupport: () -> Unit,
    onDismissError: () -> Unit,
) {
    when (val d = dialog) {
        null -> {}
        is CheckoutDialog.Processing -> TvMessageDialog(
            title = "Processing purchase",
            message = d.message,
            onDismiss = {},
            confirmLabel = "",
            onConfirm = null,
            spinner = true,
        )
        is CheckoutDialog.PendingTimeout -> TvMessageDialog(
            title = "Still processing",
            message = d.message,
            onDismiss = onDismissError,
            confirmLabel = "Check status",
            onConfirm = onCheckStatus,
            dismissLabel = "Contact support",
            icon = Icons.Filled.ErrorOutline,
        )
        is CheckoutDialog.AckPending -> TvMessageDialog(
            title = "Verifying purchase",
            message = d.message,
            onDismiss = onDismissError,
            confirmLabel = "Contact support",
            onConfirm = onSupport,
            icon = Icons.Filled.ErrorOutline,
        )
        is CheckoutDialog.TransactionError -> TvMessageDialog(
            title = "",
            message = d.message,
            onDismiss = onDismissError,
            confirmLabel = "OK",
            onConfirm = onDismissError,
            icon = Icons.Filled.ErrorOutline,
        )
        is CheckoutDialog.Success -> RpnSuccessDialog(title = d.title, message = d.message)
    }
}

// ---- helpers mirroring GooglePlaySubsAdapter / RethinkPlusFragment ----

private fun tvToast(ctx: Context, msg: String) {
    Utilities.showToastUiCentered(ctx, msg, Toast.LENGTH_LONG)
}

private fun friendlyBillingMessage(ctx: Context, code: Int): String = when (code) {
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> ctx.getString(R.string.billing_err_service_unavailable)
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> ctx.getString(R.string.billing_err_billing_unavailable)
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> ctx.getString(R.string.billing_err_item_unavailable)
    BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> ctx.getString(R.string.billing_err_service_timeout)
    BillingClient.BillingResponseCode.DEVELOPER_ERROR -> ctx.getString(R.string.billing_err_developer_error)
    BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> ctx.getString(R.string.billing_err_feature_not_supported)
    BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> ctx.getString(R.string.billing_err_item_not_owned)
    else -> ctx.getString(R.string.billing_err_generic_retry)
}

private fun inAppDurationMonths(planId: String): Int = when (planId) {
    InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> 24
    InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> 60
    InAppBillingHandler.ONE_TIME_PRODUCT_ID -> 24
    else -> 0
}

private fun durationLabel(ctx: Context, months: Int): String {
    return if (months >= 12 && months % 12 == 0) {
        val yrs = months / 12
        ctx.resources.getQuantityString(R.plurals.duration_years, yrs, yrs)
    } else {
        ""
    }
}

private fun savingsPercent(originalMicros: Long, discountedMicros: Long): Int {
    if (originalMicros <= 0L || discountedMicros <= 0L) return 0
    return (((originalMicros - discountedMicros).toDouble() / originalMicros) * 100).toInt()
}

private fun revokeDaysFor(productId: String?, planId: String?): Int = when (productId) {
    InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
    InAppBillingHandler.SUBS_PRODUCT_YEARLY -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
    InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
    InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
    InAppBillingHandler.ONE_TIME_PRODUCT_ID -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
    else -> when (planId) {
        InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
        InAppBillingHandler.SUBS_PRODUCT_YEARLY -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
        InAppBillingHandler.ONE_TIME_PRODUCT_ID -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
        else -> 7
    }
}

private fun isOnline(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
    }
}

private fun stripHtml(text: String): String {
    return text
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}
