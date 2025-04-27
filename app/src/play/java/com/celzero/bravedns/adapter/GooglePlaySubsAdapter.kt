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
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemPlaySubsBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.util.UIUtils.fetchColor

class GooglePlaySubsAdapter(val listener: SubscriptionClickListener, val context: Context, val list: List<ProductDetail>, pos: Int = 0): RecyclerView.Adapter<GooglePlaySubsAdapter.SubscriptionPlansViewHolder>() {
    var selectedPos = -1
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): SubscriptionPlansViewHolder {
        val binding = ListItemPlaySubsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SubscriptionPlansViewHolder(binding)
    }

    init {
        selectedPos = pos
    }

    interface SubscriptionClickListener {
        fun onSubscriptionSelected(productId: String, planId: String)
    }

    override fun onBindViewHolder(
        holder: SubscriptionPlansViewHolder,
        position: Int
    ) {
        holder.bind(list[position], position)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    inner class SubscriptionPlansViewHolder(private val binding: ListItemPlaySubsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(prod: ProductDetail, pos: Int) {
            // remove (Rethink: DNS + Firewall + VPN) from the title
            val title = prod.productTitle.replace(" (Rethink: DNS + Firewall + VPN)", "")
            var offer = ""
            var price = ""
            /**
            *   sample
             * Product Title: Annual Proxy Access (Rethink: DNS + Firewall + VPN), pricing size: 2,
             *
             * [
             * PricingPhase(recurringMode=FREE, price=Free, currencyCode=INR, planTitle=Weekly, billingCycleCount=0, billingPeriod=P1W, priceAmountMicros=0, freeTrialPeriod=7),
             *
             * PricingPhase(recurringMode=ORIGINAL, price=₹850, currencyCode=INR, planTitle=Yearly, billingCycleCount=0, billingPeriod=P1Y, priceAmountMicros=850000000, freeTrialPeriod=0)]
             */
            prod.pricingDetails.forEach { p ->
                if (p.recurringMode == InAppBillingHandler.RecurringMode.FREE) {
                    offer = "Trail period: ${p.freeTrialPeriod} days"
                } else {
                    price = "${p.price} ${p.currencyCode}"
                }
            }
            if (offer.isNotEmpty()) {
                binding.offerDetail.text = offer
            } else {
                binding.offerDetail.text = ""
            }

            if (price.isNotEmpty()) {
                binding.subsPrice.text = price
            } else {
                binding.subsPrice.text = ""
            }
            binding.subsName.text = title
            setCardStroke(selectedPos == pos)
            Logger.d(LOG_TAG_UI, "Product Title: $title (${selectedPos == pos}), pricing size: ${prod.pricingDetails.size}, ${prod.pricingDetails}")
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
