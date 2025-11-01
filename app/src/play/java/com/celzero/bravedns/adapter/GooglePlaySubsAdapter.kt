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
/*
import Logger.LOG_IAB
import Logger.LOG_TAG_UI
import android.content.Context
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
    }


    override fun getItemViewType(position: Int): Int {
        return if (showShimmer) VIEW_TYPE_SHIMMER else VIEW_TYPE_REAL
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
        if (holder is ShimmerViewHolder) {
            holder.shimmerLayout.startShimmer()
        } else if (holder is SubscriptionPlansViewHolder) {
            holder.bind(pds[position], position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ShimmerViewHolder) {
            holder.shimmerLayout.stopShimmer()
        }
        super.onViewRecycled(holder)
    }

    fun setData(data: List<ProductDetail>) {
        this.pds = data
        showShimmer = false
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return if (showShimmer) SHIMMER_ITEM_COUNT else pds.size
    }

    inner class ShimmerViewHolder(private val binding: ListItemShimmerCardBinding) : RecyclerView.ViewHolder(binding.root) {
        val shimmerLayout: ShimmerFrameLayout = binding.shimmerViewContainer
    }

    inner class SubscriptionPlansViewHolder(private val binding: ListItemPlaySubsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(prod: ProductDetail, pos: Int) {
            // remove (Rethink: DNS + Firewall + VPN) from the title
            val pricing = prod.pricingDetails.firstOrNull() ?: return
            val title = pricing.planTitle.replace(" (Rethink: DNS + Firewall + VPN)", "")
            var offerPrice = ""
            var originalPrice = ""
            /**
            *   sample
             * [productDetail=ProductDetail(productId=test_product_acp, planId=test-proxy-yearly, productTitle=test_product_acp (Rethink: DNS + Firewall + VPN), productType=subs, pricingDetails=[PricingPhase(recurringMode=ORIGINAL, price=₹1,700, currencyCode=INR, planTitle=Yearly, billingCycleCount=0, billingPeriod=P1Y, priceAmountMicros=1700000000, freeTrialPeriod=0)]), productDetails=ProductDetails{jsonString='{"productId":"test_product_acp","type":"subs","title":"test_product_acp (Rethink: DNS + Firewall + VPN)","name":"test_product_acp","localizedIn":["en-GB"],"skuDetailsToken":"AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY","subscriptionOfferDetails":[{"offerIdToken":"Aezw0slCKFuN3u6CLJqjmmCXlPvzNEDtWjWlaD4dLU71Z2xdPT337FKuo8z5Q\/3dPfd8A5GAY7JiV9TByoq1EBYQRIYcIlKt\/bUO","basePlanId":"test-proxy-yearly","pricingPhases":[{"priceAmountMicros":1700000000,"priceCurrencyCode":"INR","formattedPrice":"₹1,700.00","billingPeriod":"P1Y","recurrenceMode":1}],"offerTags":[]},{"offerIdToken":"Aezw0sk0TeTw2PEG172yjUKkSak0JtKFsIcSLQUrKJDRkkSOnFxZvvhgFlLOlOp\/Jge6TIvquUNLNbQ0U5BR0wZ3PnTYUfZwnhZ2","basePlanId":"test-proxy-monthly","pricingPhases":[{"priceAmountMicros":210000000,"priceCurrencyCode":"INR","formattedPrice":"₹210.00","billingPeriod":"P1M","recurrenceMode":1}],"offerTags":[]}]}', parsedJson={"productId":"test_product_acp","type":"subs","title":"test_product_acp (Rethink: DNS + Firewall + VPN)","name":"test_product_acp","localizedIn":["en-GB"],"skuDetailsToken":"AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY","subscriptionOfferDetails":[{"offerIdToken":"Aezw0slCKFuN3u6CLJqjmmCXlPvzNEDtWjWlaD4dLU71Z2xdPT337FKuo8z5Q\/3dPfd8A5GAY7JiV9TByoq1EBYQRIYcIlKt\/bUO","basePlanId":"test-proxy-yearly","pricingPhases":[{"priceAmountMicros":1700000000,"priceCurrencyCode":"INR","formattedPrice":"₹1,700.00","billingPeriod":"P1Y","recurrenceMode":1}],"offerTags":[]},{"offerIdToken":"Aezw0sk0TeTw2PEG172yjUKkSak0JtKFsIcSLQUrKJDRkkSOnFxZvvhgFlLOlOp\/Jge6TIvquUNLNbQ0U5BR0wZ3PnTYUfZwnhZ2","basePlanId":"test-proxy-monthly","pricingPhases":[{"priceAmountMicros":210000000,"priceCurrencyCode":"INR","formattedPrice":"₹210.00","billingPeriod":"P1M","recurrenceMode":1}],"offerTags":[]}]}, productId='test_product_acp', productType='subs', title='test_product_acp (Rethink: DNS + Firewall + VPN)', productDetailsToken='AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY', subscriptionOfferDetails=[com.android.billingclient.api.ProductDetails$SubscriptionOfferDetails@83478a9, com.android.billingclient.api.ProductDetails$SubscriptionOfferDetails@6d10e2e]}, offerDetails=com.android.billingclient.api.ProductDetails$SubscriptionOfferDetails@83478a9), QueryProductDetail(productDetail=ProductDetail(productId=test_product_acp, planId=test-proxy-monthly, productTitle=test_product_acp (Rethink: DNS + Firewall + VPN), productType=subs, pricingDetails=[PricingPhase(recurringMode=ORIGINAL, price=₹210, currencyCode=INR, planTitle=Monthly, billingCycleCount=0, billingPeriod=P1M, priceAmountMicros=210000000, freeTrialPeriod=0)]), productDetails=ProductDetails{jsonString='{"productId":"test_product_acp","type":"subs","title":"test_product_acp (Rethink: DNS + Firewall + VPN)","name":"test_product_acp","localizedIn":["en-GB"],"skuDetailsToken":"AEuhp4JS1isixh_29bFQ6VAUZIGGn46BJIo2_Vdg5EpA40dZnrVghFzBmVEOCGhoBDTY","subscriptionOfferDetails":[{"offerIdToken":"Aezw0slCKFuN3u6CLJqjmmCXlPvzNEDtWjWlaD4dLU71Z2xdPT337FKuo8z5Q\/3dPfd8A5GAY7JiV9TByoq1EBYQRIYcIlKt\/bUO","basePlanId":"test-proxy-yearly","pricingPhases":[{"priceAmountMicros":1700000000,"priceCurrencyCode":"INR","formattedPrice":"₹1,700.00","billingPeriod":"P1Y","recurrenceMode":1}],"offerTags":[]},{"offerIdToken":"Aezw0sk0TeTw2PEG172yjUKkSak0JtKFsIcSLQUrKJDRkkSOnFxZvvhgFlLOlOp\/Jge6TIvquUNLNbQ0U5BR0wZ3PnTYUfZwnhZ2","basePlanId":"test-proxy-monthly","pricingPhases":[{"priceAmountMicros":210000000,"priceCurrencyCode":"INR","formattedPrice":"₹21
             *
             * Pricing Phase: DISCOUNTED, Price: ₹189, Currency: INR, Plan Title: Monthly, Billing Period: P1M, Price Amount Micros: 189000000, Free Trial Period: 0, cycleCount: 1
             * Pricing Phase: ORIGINAL, Price: ₹210, Currency: INR, Plan Title: Monthly, Billing Period: P1M, Price Amount Micros: 210000000, Free Trial Period: 0, cycleCount: 0
             */

            prod.pricingDetails.forEach {
                if (it.freeTrialPeriod > 0) {
                    offerPrice = "Trial period: ${it.freeTrialPeriod} days"
                } else if (it.recurringMode == InAppBillingHandler.RecurringMode.DISCOUNTED && it.price.isNotEmpty()) {
                    offerPrice = it.price
                } else if (it.recurringMode == InAppBillingHandler.RecurringMode.ORIGINAL && it.price.isNotEmpty()) {
                    originalPrice = it.price
                }
            }

            if (offerPrice.isEmpty()) {
                binding.originalPrice.visibility = View.GONE
                binding.offerPrice.text = originalPrice
            } else {
                binding.offerPrice.text = offerPrice
                binding.originalPrice.visibility = View.VISIBLE
                binding.originalPrice.paintFlags = binding.offerPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.originalPrice.text = originalPrice
            }

            binding.productName.text = title
            setCardStroke(selectedPos == pos)
            Logger.d(LOG_TAG_UI, "Product Title: $title (${selectedPos == pos}), Price: $originalPrice, Offer: $offerPrice")
            setupClickListeners(prod, pos)
        }

        private fun setupClickListeners(prod: ProductDetail, pos: Int) {
            binding.subsCard.setOnClickListener {
                // update the selected item
                selectedPos = pos
                // inform the activity about the selected item
                listener.onSubscriptionSelected(prod.productId, prod.planId)
                Logger.d(LOG_IAB, "Selected Subscription: ${prod.productId}, ${prod.planId}")
                notifyDataSetChanged()
            }
        }

        private fun setCardStroke(checked: Boolean) {
            if (checked) {
                binding.subsCard.strokeWidth = 2
            } else {
                binding.subsCard.strokeWidth = 0
            }
            binding.subsCard.strokeColor = getStrokeColorForStatus(checked)
        }

        private fun getStrokeColorForStatus(isActive: Boolean): Int {
            return if (isActive) {
                fetchColor(context, R.attr.accentGood)
            } else {
                fetchColor(context, R.attr.chipBgColorNeutral)
            }
        }
    }
}
*/
