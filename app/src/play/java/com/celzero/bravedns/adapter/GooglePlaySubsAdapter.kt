/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.R
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.ui.compose.purchase.*
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import com.celzero.bravedns.util.Logger.LOG_TAG_UI

/** Observable presentation collection only; never starts a billing operation. */
class GooglePlaySubsAdapter(val listener: SubscriptionChangeListener, val context: Context,
    pds: List<ProductDetail> = emptyList(), productId: String? = null, planId: String? = null, showShimmer: Boolean = true) {
    var pds by mutableStateOf(pds)
    var showShimmer by mutableStateOf(showShimmer)
    private var selectedProductId by mutableStateOf(productId)
    private var selectedPlanId by mutableStateOf(planId)
    companion object { private const val TAG = "GooglePlaySubsAdapter" }
    interface SubscriptionChangeListener { fun onSubscriptionSelected(productId: String, planId: String) }
    fun setSelectedProduct(productId: String?, planId: String?) { selectedProductId = productId; selectedPlanId = planId }
    fun setData(data: List<ProductDetail>) {
        Logger.d(LOG_TAG_UI, "$TAG setData called with ${data.size} products, showShimmer: $showShimmer -> false")
        pds = data
        showShimmer = false
    }
    @Composable fun Content() {
        if (showShimmer) { PurchaseShimmer(); return }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pds.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { product ->
                        val presentation = present(product)
                        if (presentation != null) PurchasePlanCard(presentation,
                            selected = product.productId == selectedProductId && product.planId == selectedPlanId,
                            modifier = Modifier.weight(1f), onSelect = {
                                selectedProductId = product.productId
                                selectedPlanId = product.planId
                                listener.onSubscriptionSelected(product.productId, product.planId)
                                Logger.d(LOG_IAB, "Selected Plan: ${product.productId}, ${product.planId}")
                            })
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    internal fun present(prod: ProductDetail): PurchasePlan? {
        val pricing = prod.pricingDetails.firstOrNull() ?: return null
        val planTitle = pricing.planTitle
        var currentPrice = ""
        var discountedPrice = ""
        var freeTrialDays = 0
        prod.pricingDetails.forEach { phase ->
            when {
                phase.freeTrialPeriod > 0 -> freeTrialDays = phase.freeTrialPeriod
                phase.recurringMode == InAppBillingHandler.RecurringMode.DISCOUNTED -> discountedPrice = phase.price
                phase.recurringMode == InAppBillingHandler.RecurringMode.ORIGINAL -> currentPrice = phase.price
            }
        }
        val displayPrice = discountedPrice.ifEmpty { currentPrice }
        val isInApp = prod.productType == ProductType.INAPP
        val durationMonths = getInAppDurationMonths(prod.planId)
        val duration = if (isInApp && durationMonths > 0) formatDurationLabel(durationMonths, planTitle) else planTitle
        val billing = if (freeTrialDays > 0) context.getString(R.string.trial_days_format, freeTrialDays) else getBillingText(prod.productType, pricing.billingPeriod)
        val savings = when { isInApp -> pricing.discountPercent; discountedPrice.isNotEmpty() -> calculateSavings(currentPrice, discountedPrice); else -> 0 }
        return PurchasePlan(prod.productId + ":" + prod.planId, duration, displayPrice, billing,
            if (savings > 0) context.getString(R.string.savings_percent, "$savings%") else "")
    }
    private fun getInAppDurationMonths(planId: String): Int = when (planId) {
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> 24
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> 60
        InAppBillingHandler.ONE_TIME_PRODUCT_ID -> 24 // legacy default 2yr
        else -> 0
    }

    private fun formatDurationLabel(months: Int, planTitle: String): String {
        return when {
            months >= 12 && months % 12 == 0 -> {
                val yrs = months / 12
                context.resources.getQuantityString(R.plurals.duration_years, yrs, yrs)
            }
            else -> planTitle
        }
    }

    private fun calculateSavings(originalPrice: String, discountedPrice: String): Int {
        return try {
            val original = originalPrice.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
            val discounted = discountedPrice.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
            if (original > 0 && discounted > 0) ((original - discounted) / original * 100).toInt() else 0
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err calculating savings: ${e.message}")
            0
        }
    }

    private fun getBillingText(productType: String, billingPeriod: String): String {
        if (productType == ProductType.INAPP) {
            return context.getString(R.string.billing_no_recurring)
        }
        return when {
            billingPeriod.contains("P1M", true) -> context.getString(R.string.billing_monthly_cancel)
            billingPeriod.contains("P1Y", true) -> context.getString(R.string.billing_annually_cancel)
            else -> context.getString(R.string.billing_sub_cancel)
        }
    }
}
