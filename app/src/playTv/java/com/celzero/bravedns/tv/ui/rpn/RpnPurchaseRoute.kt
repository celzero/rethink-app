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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.iab.BillingListener
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text

import com.celzero.bravedns.viewmodel.RethinkPlusViewModel
import com.celzero.bravedns.viewmodel.SubscriptionUiState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * RPN checkout — the TV counterpart of the play/website
 * `RethinkPlusFragment`, rebuilt in Compose on top of the same
 * [RethinkPlusViewModel] + [InAppBillingHandler] facade so billing
 * logic is not duplicated. Lives in the channel source set (not the
 * shared `tv` set) because it references Play-billing-only APIs that
 * the fdroid stub does not provide.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnPurchaseRoute(navController: NavController? = null) {
    val context = LocalContext.current
    val vm: RethinkPlusViewModel = viewModel()

    val uiState by vm.uiState.collectAsState()
    val productType by vm.selectedProductType.collectAsState()
    val selected by vm.selectedProduct.collectAsState()
    val retryEvent by vm.retryConnectionEvent.collectAsState(initial = null)

    // Billing connection + listener lifecycle (the phone fragment's
    // BillingListener role).
    val listener = remember {
        object : BillingListener {
            override fun onConnectionResult(isSuccess: Boolean, message: String) {
                vm.onBillingConnected(isSuccess, message)
            }

            override fun purchasesResult(
                isSuccess: Boolean,
                purchaseDetailList: List<com.celzero.bravedns.iab.PurchaseDetail>,
            ) = Unit

            override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
                vm.onProductsFetched(isSuccess, productList, InAppBillingHandler.lastProductQueryResponseCode)
            }
        }
    }
    DisposableEffect(Unit) {
        val appContext = context.applicationContext
        if (!InAppBillingHandler.isBillingClientSetup()) {
            InAppBillingHandler.initiate(appContext, listener)
        } else {
            InAppBillingHandler.registerListener(listener)
            vm.onBillingConnected(true, "already connected")
        }
        onDispose { InAppBillingHandler.unregisterListener(listener) }
    }
    LaunchedEffect(Unit) {
        vm.initializeBilling()
        vm.checkAvailability()
    }
    LaunchedEffect(retryEvent) {
        if (retryEvent != null) {
            InAppBillingHandler.initiate(context.applicationContext, listener)
        }
    }

    // Transaction failures: consume the live-data event and let the
    // VM unwind its purchase-in-flight state (the host owns this hook
    // — see RethinkPlusViewModel.onTransactionError()).
    val txError by com.celzero.bravedns.tv.ui.common.rememberAsImmutableState(
        liveData = InAppBillingHandler.transactionErrorLiveData,
        initial = null,
    ) { it }
    LaunchedEffect(txError) {
        val result = txError ?: return@LaunchedEffect
        InAppBillingHandler.transactionErrorLiveData.value = null
        vm.onTransactionError()
        val userCancelled = result.responseCode ==
            com.android.billingclient.api.BillingClient.BillingResponseCode.USER_CANCELED
        if (!userCancelled) {
            Toast.makeText(context, "Purchase failed (${result.responseCode})", Toast.LENGTH_LONG).show()
        }
    }

    val purchaseScope = rememberCoroutineScope()

    fun buy(activity: Activity, productId: String, planId: String) {
        purchaseScope.launch {
            when (productType) {
                RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION ->
                    InAppBillingHandler.purchaseSubs(activity, productId, planId)
                RethinkPlusViewModel.ProductTypeFilter.ONE_TIME -> {
                    if (vm.extendMode) vm.markPurchaseFlowActive()
                    InAppBillingHandler.purchaseOneTime(activity, productId, planId, vm.extendMode)
                }
            }
        }
    }

    var confirmBuy by remember { mutableStateOf<Pair<String, String>?>(null) }

    TvScreenScaffold(
        title = "Get Rethink Plus",
        subtitle = "Choose a plan to enable the Rethink Private Network.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val state = uiState) {
                is SubscriptionUiState.Loading -> {
                    Text(
                        text = "Loading plans…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SubscriptionUiState.Available -> {
                    BannerText(
                        if (state.canSell) {
                            "Available in your region (${state.country})."
                        } else {
                            "Rethink Plus is not yet sold in your region (${state.country})."
                        },
                    )
                }
                is SubscriptionUiState.Ready -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TypeChip(
                            label = "Subscription",
                            selected = productType == RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION,
                        ) { vm.selectProductType(RethinkPlusViewModel.ProductTypeFilter.SUBSCRIPTION) }
                        TypeChip(
                            label = "One-time",
                            selected = productType == RethinkPlusViewModel.ProductTypeFilter.ONE_TIME,
                        ) { vm.selectProductType(RethinkPlusViewModel.ProductTypeFilter.ONE_TIME) }
                    }

                    val products = state.products
                    if (products.isEmpty()) {
                        Text(
                            text = "No plans available right now.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(products, key = { it.productId + "/" + it.planId }) { product ->
                                ProductCard(
                                    product = product,
                                    selected = selected?.first == product.productId &&
                                        selected?.second == product.planId,
                                    onClick = { vm.selectProduct(product.productId, product.planId) },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    val activity = context as? Activity
                    Button(
                        onClick = {
                            val sel = selected
                            if (sel != null && activity != null) {
                                confirmBuy = sel
                            }
                        },
                        enabled = selected != null && activity != null,
                    ) {
                        Text(
                            text = if (state.isResubscribe) "Resubscribe" else "Continue",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                is SubscriptionUiState.Processing -> BannerText(state.message.ifBlank { "Processing…" })
                is SubscriptionUiState.PendingPurchase -> BannerText(
                    state.message.ifBlank { "Purchase pending — this resolves automatically." },
                )
                is SubscriptionUiState.PendingTimeout -> {
                    BannerText(state.message.ifBlank { "The purchase is taking longer than usual." })
                    Button(onClick = { vm.retry() }) { Text("Check again") }
                }
                is SubscriptionUiState.NoInternet -> {
                    BannerText(state.message.ifBlank { "You appear to be offline." })
                    Button(onClick = { vm.retry() }) { Text("Retry") }
                }
                is SubscriptionUiState.Error -> {
                    BannerText("${state.title}: ${state.message}")
                    if (state.isRetryable) {
                        Button(onClick = { vm.retry() }) { Text("Retry") }
                    }
                }
                is SubscriptionUiState.ServerAckPending ->
                    BannerText(state.message.ifBlank { "Purchase received — verifying on the server…" })
                is SubscriptionUiState.AcknowledgementFailed -> {
                    BannerText("${state.title}: ${state.message}")
                    if (state.canRetry) {
                        Button(onClick = { vm.reverifyAfterFailure() }) { Text("Verify again") }
                    }
                }
                is SubscriptionUiState.AlreadySubscribed -> {
                    BannerText("You are already subscribed (${state.productId}).")
                    if (vm.extendMode) {
                        Button(onClick = { navController?.popBackStack() }) { Text("Done") }
                    }
                }
                is SubscriptionUiState.Success -> {
                    LaunchedEffect(state.productId) {
                        Toast.makeText(context, "Subscribed to Rethink Plus", Toast.LENGTH_LONG).show()
                        navController?.popBackStack()
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    confirmBuy?.let { (productId, planId) ->
        TvConfirmDialog(
            title = "Confirm purchase",
            message = "Continue with $productId?",
            confirmLabel = "Buy",
            onConfirm = {
                confirmBuy = null
                (context as? Activity)?.let { buy(it, productId, planId) }
            },
            onDismiss = { confirmBuy = null },
        )
    }
}

@Composable
private fun BannerText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
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
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
private fun ProductCard(product: ProductDetail, selected: Boolean, onClick: () -> Unit) {
    val price = product.pricingDetails.firstOrNull()?.price.orEmpty()
    val planTitle = product.pricingDetails.firstOrNull()?.planTitle.orEmpty()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
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
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.productTitle.ifBlank { product.productId },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (planTitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = planTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = price.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
