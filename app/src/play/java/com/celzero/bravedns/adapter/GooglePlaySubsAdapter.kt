/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.adapter

import Logger.LOG_IAB
import Logger.LOG_TAG_UI
import android.content.Context
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemPlaySubsBinding
import com.celzero.bravedns.databinding.ListItemShimmerCardBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.facebook.shimmer.ShimmerFrameLayout

class GooglePlaySubsAdapter(val listener: SubscriptionChangeListener, val context: Context, var pds: List<ProductDetail> = emptyList(), pos: Int = 0, var showShimmer: Boolean = true): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var selectedPos = -1

    companion object {
        private const val SHIMMER_ITEM_COUNT = 2
        private const val VIEW_TYPE_SHIMMER = 0
        private const val VIEW_TYPE_REAL = 1
        private const val RETHINK_TITLE = " (Rethink: DNS + Firewall + VPN)"
    }

    override fun getItemViewType(position: Int): Int {
        return if (showShimmer) {
            VIEW_TYPE_SHIMMER
        } else {
            VIEW_TYPE_REAL
        }
    }

    override fun getItemCount(): Int {
        return if (showShimmer) SHIMMER_ITEM_COUNT else pds.size
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_SHIMMER) {
            val binding = ListItemShimmerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            ShimmerViewHolder(binding)
        } else {
            val binding = ListItemPlaySubsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            SubscriptionPlansViewHolder(binding)
        }
    }

    init {
        selectedPos = pos
    }

    interface SubscriptionChangeListener {
        fun onSubscriptionSelected(productId: String, planId: String)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        when (holder) {
            is ShimmerViewHolder -> {
                holder.shimmerLayout.startShimmer()
            }
            is SubscriptionPlansViewHolder -> {
                holder.bind(pds[position], position)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ShimmerViewHolder) {
            holder.shimmerLayout.stopShimmer()
        }
        super.onViewRecycled(holder)
    }

    fun setData(data: List<ProductDetail>) {
        Logger.d(LOG_TAG_UI, "setData called with ${data.size} products, showShimmer: $showShimmer -> false")
        this.pds = data
        showShimmer = false
        notifyDataSetChanged()
    }

    inner class ShimmerViewHolder(private val binding: ListItemShimmerCardBinding) : RecyclerView.ViewHolder(binding.root) {
        val shimmerLayout: ShimmerFrameLayout = binding.shimmerViewContainer
    }

    inner class SubscriptionPlansViewHolder(private val binding: ListItemPlaySubsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(prod: ProductDetail, pos: Int) {
            val pricing = prod.pricingDetails.firstOrNull() ?: return

            /**
             *   sample
             * [productDetail=ProductDetail(productId=test_product_acp, planId=test-proxy-yearly, productTitle=test_product_acp (Rethink: DNS + Firewall + VPN), productType=subs, pricingDetails=[PricingPhase(recurringMode=ORIGINAL, price=₹1,700, currencyCode=INR, planTitle=Yearly, billingCycleCount=0, billingPeriod=P1Y, priceAmountMicros=1700000000, freeTrialPeriod=0)]), productDetails=ProductDetails{jsonString='{"productId":"test_product_acp","type":"subs","title":"test_product_acp (Rethink: DNS + Firewall + VPN)","name":"test_product_acp","localizedIn":["en-GB"],"skuDetailsToken":"AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY","subscriptionOfferDetails":[{"offerIdToken":"Aezw0slCKFuN3u6CLJqjmmCXlPvzNEDtWjWlaD4dLU71Z2xdPT337FKuo8z5Q\/3dPfd8A5GAY7JiV9TByoq1EBYQRIYcIlKt\/bUO","basePlanId":"test-proxy-yearly","pricingPhases":[{"priceAmountMicros":1700000000,"priceCurrencyCode":"INR","formattedPrice":"₹1,700.00","billingPeriod":"P1Y","recurrenceMode":1}],"offerTags":[]},{"offerIdToken":"Aezw0sk0TeTw2PEG172yjUKkSak0JtKFsIcSLQUrKJDRkkSOnFxZvvhgFlLOlOp\/Jge6TIvquUNLNbQ0U5BR0wZ3PnTYUfZwnhZ2","basePlanId":"test-proxy-monthly","pricingPhases":[{"priceAmountMicros":210000000,"priceCurrencyCode":"INR","formattedPrice":"₹210.00","billingPeriod":"P1M","recurrenceMode":1}],"offerTags":[]}]}', parsedJson={"productId":"test_product_acp","type":"subs","title":"test_product_acp (Rethink: DNS + Firewall + VPN)","name":"test_product_acp","localizedIn":["en-GB"],"skuDetailsToken":"AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY","subscriptionOfferDetails":[{"offerIdToken":"Aezw0slCKFuN3u6CLJqjmmCXlPvzNEDtWjWlaD4dLU71Z2xdPT337FKuo8z5Q\/3dPfd8A5GAY7JiV9TByoq1EBYQRIYcIlKt\/bUO","basePlanId":"test-proxy-yearly","pricingPhases":[{"priceAmountMicros":1700000000,"priceCurrencyCode":"INR","formattedPrice":"₹1,700.00","billingPeriod":"P1Y","recurrenceMode":1}],"offerTags":[]},{"offerIdToken":"Aezw0sk0TeTw2PEG172yjUKkSak0JtKFsIcSLQUrKJDRkkSOnFxZvvhgFlLOlOp\/Jge6TIvquUNLNbQ0U5BR0wZ3PnTYUfZwnhZ2","basePlanId":"test-proxy-monthly","pricingPhases":[{"priceAmountMicros":210000000,"priceCurrencyCode":"INR","formattedPrice":"₹210.00","billingPeriod":"P1M","recurrenceMode":1}],"offerTags":[]}]}, productId='test_product_acp', productType='subs', title='test_product_acp (Rethink: DNS + Firewall + VPN)', productDetailsToken='AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY', subscriptionOfferDetails=[com.android.billingclient.api.ProductDetails$SubscriptionOfferDetails@83478a9, com.android.billingclient.api.ProductDetails$SubscriptionOfferDetails@6d10e2e]}, offerDetails=com.android.billingclient.api.ProductDetails$SubscriptionOfferDetails@83478a9), QueryProductDetail(productDetail=ProductDetail(productId=test_product_acp, planId=test-proxy-monthly, productTitle=test_product_acp (Rethink: DNS + Firewall + VPN), productType=subs, pricingDetails=[PricingPhase(recurringMode=ORIGINAL, price=₹210, currencyCode=INR, planTitle=Monthly, billingCycleCount=0, billingPeriod=P1M, priceAmountMicros=210000000, freeTrialPeriod=0)]), productDetails=ProductDetails{jsonString='{"productId":"test_product_acp","type":"subs","title":"test_product_acp (Rethink: DNS + Firewall + VPN)","name":"test_product_acp","localizedIn":["en-GB"],"skuDetailsToken":"AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY","subscriptionOfferDetails":[{"offerIdToken":"Aezw0slCKFuN3u6CLJqjmmCXlPvzNEDtWjWlaD4dLU71Z2xdPT337FKuo8z5Q\/3dPfd8A5GAY7JiV9TByoq1EBYQRIYcIlKt\/bUO","basePlanId":"test-proxy-yearly","pricingPhases":[{"priceAmountMicros":1700000000,"priceCurrencyCode":"INR","formattedPrice":"₹1,700.00","billingPeriod":"P1Y","recurrenceMode":1}],"offerTags":[]},{"offerIdToken":"Aezw0sk0TeTw2PEG172yjUKkSak0JtKFsIcSLQUrKJDRkkSOnFxZvvhgFlLOlOp\/Jge6TIvquUNLNbQ0U5BR0wZ3PnTYUfZwnhZ2","basePlanId":"test-proxy-monthly","pricingPhases":[{"priceAmountMicros":210000000,"priceCurrencyCode":"INR","formattedPrice":"₹21
             *
             * Pricing Phase: DISCOUNTED, Price: ₹189, Currency: INR, Plan Title: Monthly, Billing Period: P1M, Price Amount Micros: 189000000, Free Trial Period: 0, cycleCount: 1
             * Pricing Phase: ORIGINAL, Price: ₹210, Currency: INR, Plan Title: Monthly, Billing Period: P1M, Price Amount Micros: 210000000, Free Trial Period: 0, cycleCount: 0
             */
            // Extract plan title and clean it
            val planTitle = pricing.planTitle

            // Variables for pricing logic
            var currentPrice = ""
            var discountedPrice = ""
            var freeTrialDays = 0
            var isYearly = false

            // Parse pricing details
            prod.pricingDetails.forEach { phase ->
                when {
                    phase.freeTrialPeriod > 0 -> {
                        freeTrialDays = phase.freeTrialPeriod
                    }
                    phase.recurringMode == InAppBillingHandler.RecurringMode.DISCOUNTED -> {
                        discountedPrice = phase.price
                    }
                    phase.recurringMode == InAppBillingHandler.RecurringMode.ORIGINAL -> {
                        currentPrice = phase.price
                        isYearly = phase.billingPeriod.contains("Y")
                    }
                }
            }

            // Determine final price to display
            val displayPrice = discountedPrice.ifEmpty { currentPrice }

            // 1. Plan Duration
            binding.planDuration.text = planTitle

            // 2. Badge - Show "BEST VALUE" for yearly plans
            if (isYearly) {
                binding.badgeContainer.visibility = View.VISIBLE
                binding.badgeText.text = context.getString(R.string.best_value)
            } else {
                binding.badgeContainer.visibility = View.GONE
            }

            // 3. Price Display
            binding.price.text = displayPrice

            // 4. Price Period
            val periodText = if (isYearly) {
                context.getString(R.string.per_year)
            } else {
                context.getString(R.string.per_month)
            }
            binding.pricePeriod.text = periodText

            // 5. Original Price (if discounted)
            if (discountedPrice.isNotEmpty() && currentPrice.isNotEmpty()) {
                binding.originalPrice.visibility = View.VISIBLE
                binding.originalPrice.text = context.getString(R.string.original_price, currentPrice)
                binding.originalPrice.paintFlags = binding.originalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.originalPrice.visibility = View.GONE
            }

            // 6. Billing Info
            val billingText = getBillingText(prod.productType, pricing.billingPeriod)
            binding.billingInfo.text = billingText

            // 7. Free Trial Container
            if (freeTrialDays > 0) {
                binding.trialContainer.visibility = View.VISIBLE
                binding.trialText.text = "$freeTrialDays days free trial"
            } else {
                binding.trialContainer.visibility = View.GONE
            }

            // 8. Savings Badge - Calculate savings for yearly plans
            if (isYearly && discountedPrice.isNotEmpty()) {
                binding.savingsText.visibility = View.VISIBLE
                // Calculate approximate savings percentage
                val savingsPercentage = calculateSavings(currentPrice, discountedPrice)
                if (savingsPercentage > 0) {
                    binding.savingsText.text = context.getString(R.string.save_percentage, "${savingsPercentage}%")
                } else {
                    binding.savingsText.visibility = View.GONE
                }
            } else {
                binding.savingsText.visibility = View.GONE
            }

            // 9. Selection Indicator
            binding.selectionIndicator.isChecked = (selectedPos == pos)

            // 10. Card Stroke for selection
            setCardStroke(selectedPos == pos)

            Logger.d(LOG_TAG_UI, "Premium Plan: $planTitle, Price: $displayPrice, Yearly: $isYearly, Selected: ${selectedPos == pos}")

            // Setup click listeners
            setupClickListeners(prod, pos)
        }

        private fun calculateSavings(originalPrice: String, discountedPrice: String): Int {
            return try {
                // Extract numeric values from price strings
                val original = originalPrice.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                val discounted = discountedPrice.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0

                if (original > 0 && discounted > 0) {
                    val savings = ((original - discounted) / original * 100).toInt()
                    savings
                } else {
                    0
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err calculating savings: ${e.message}")
                0
            }
        }

        private fun setupClickListeners(prod: ProductDetail, pos: Int) {
            binding.planCard.setOnClickListener {
                // Update selected position
                selectedPos = pos
                // Notify listener
                listener.onSubscriptionSelected(prod.productId, prod.planId)
                Logger.d(LOG_IAB, "Selected Plan: ${prod.productId}, ${prod.planId}")
                // Refresh all items to update selection state
                notifyDataSetChanged()
            }
        }

        private fun setCardStroke(checked: Boolean) {
            if (checked) {
                binding.planCard.strokeWidth = 4
                binding.planCard.strokeColor = fetchColor(context, R.attr.accentGood)
            } else {
                binding.planCard.strokeWidth = 0
                binding.planCard.strokeColor = fetchColor(context, R.attr.chipBgColorNeutral)
            }
        }

        /**
         * Get billing text based on product type and billing period
         * Supports: Monthly (P1M), Yearly (P1Y), 2 Years (P2Y), 5 Years (P5Y)
         * For one-time purchases (INAPP), shows "One-time payment"
         */
        private fun getBillingText(productType: String, billingPeriod: String): String {
            // Check if it's a one-time purchase (INAPP)
            if (productType == ProductType.INAPP) {
                return "One-time payment • No recurring charges"
            }

            // Parse billing period for subscriptions
            // as of now, only monthly and yearly plans are available
            return when {
                billingPeriod.contains("P1M", ignoreCase = true) -> {
                    "Billed monthly • Cancel anytime"
                }
                billingPeriod.contains("P1Y", ignoreCase = true) -> {
                    "Billed annually • Cancel anytime"
                }
                billingPeriod.contains("P2Y", ignoreCase = true) -> {
                    "Billed every 2 years • Cancel anytime"
                }
                billingPeriod.contains("P5Y", ignoreCase = true) -> {
                    "Billed every 5 years • Cancel anytime"
                }
                else -> {
                    // Fallback for unknown billing periods
                    "Subscription • Cancel anytime"
                }
            }
        }
    }
}
