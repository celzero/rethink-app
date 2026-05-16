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
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemPlaySubsBinding
import com.celzero.bravedns.databinding.ListItemShimmerCardBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.facebook.shimmer.ShimmerFrameLayout
import java.util.Locale

class GooglePlaySubsAdapter(
    val listener: SubscriptionChangeListener,
    val context: Context,
    var pds: List<ProductDetail> = emptyList(),
    pos: Int = 0,
    var showShimmer: Boolean = true
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var selectedPos = -1

    companion object {
        private const val SHIMMER_ITEM_COUNT = 4
        private const val VIEW_TYPE_SHIMMER = 0
        private const val VIEW_TYPE_REAL = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (showShimmer) VIEW_TYPE_SHIMMER else VIEW_TYPE_REAL
    }

    override fun getItemCount(): Int {
        return if (showShimmer) SHIMMER_ITEM_COUNT else pds.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ShimmerViewHolder -> holder.shimmerLayout.startShimmer()
            is SubscriptionPlansViewHolder -> holder.bind(pds[position], position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ShimmerViewHolder) holder.shimmerLayout.stopShimmer()
        super.onViewRecycled(holder)
    }

    fun setData(data: List<ProductDetail>) {
        Logger.d(LOG_TAG_UI, "setData called with ${data.size} products, showShimmer: $showShimmer -> false")
        val oldList = pds
        val wasShimmer = showShimmer
        this.pds = data
        showShimmer = false

        if (wasShimmer) {
            // transition from shimmer to real data
            notifyDataSetChanged()
        } else {
            // Real data - real data: use DiffUtil
            val diff = DiffUtil.calculateDiff(ProductDiffCallback(oldList, data))
            diff.dispatchUpdatesTo(this)
        }
    }

    class ShimmerViewHolder(binding: ListItemShimmerCardBinding): RecyclerView.ViewHolder(binding.root) {
        val shimmerLayout: ShimmerFrameLayout = binding.shimmerViewContainer
    }

    inner class SubscriptionPlansViewHolder(private val binding: ListItemPlaySubsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(prod: ProductDetail, pos: Int) {
            val pricing = prod.pricingDetails.firstOrNull() ?: return

            val planTitle = pricing.planTitle

            var currentPrice = ""
            var currentPriceMicros = 0L
            var discountedPrice = ""
            var discountedPriceMicros = 0L
            var currencyCode = ""
            var freeTrialDays = 0
            var isYearly = false

            prod.pricingDetails.forEach { phase ->
                when {
                    phase.freeTrialPeriod > 0 -> freeTrialDays = phase.freeTrialPeriod
                    phase.recurringMode == InAppBillingHandler.RecurringMode.DISCOUNTED -> {
                        discountedPrice = phase.price
                        discountedPriceMicros = phase.priceAmountMicros
                        currencyCode = phase.currencyCode
                    }
                    phase.recurringMode == InAppBillingHandler.RecurringMode.ORIGINAL -> {
                        currentPrice = phase.price
                        currentPriceMicros = phase.priceAmountMicros
                        isYearly = phase.billingPeriod.contains("Y")
                        currencyCode = phase.currencyCode
                    }
                }
            }

            val displayPrice = discountedPrice.ifEmpty { currentPrice }
            val displayPriceMicros = if (discountedPriceMicros > 0) discountedPriceMicros else currentPriceMicros
            val isSelected = selectedPos == pos
            val isInApp = prod.productType == ProductType.INAPP

            // Plan Duration label
            val durationMonths = getInAppDurationMonths(prod.planId)
            binding.planDuration.text = if (isInApp && durationMonths > 0) {
                formatDurationLabel(durationMonths, planTitle)
            } else {
                planTitle
            }

            // Main price
            binding.price.text = displayPrice

            // Original Price (struck through, below price)
            if (discountedPrice.isNotEmpty() && currentPrice.isNotEmpty()) {
                binding.originalPrice.visibility = View.VISIBLE
                binding.originalPrice.text = currentPrice
                binding.originalPrice.paintFlags = binding.originalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.originalPrice.visibility = View.GONE
            }

            // Per-month breakdown for INAPP (e.g. "2-year plan = ₹141/mo")
            if (isInApp && durationMonths > 0 && displayPriceMicros > 0) {
                val perMonthMicros = displayPriceMicros / durationMonths
                val perMonthFormatted = formatMicrosAsCurrency(perMonthMicros, currencyCode, displayPrice)
                if (perMonthFormatted != null) {
                    binding.pricePerMonth.visibility = View.VISIBLE
                    binding.pricePerMonth.text = context.getString(R.string.price_per_month_format, perMonthFormatted)
                } else {
                    binding.pricePerMonth.visibility = View.GONE
                }
            } else {
                binding.pricePerMonth.visibility = View.GONE
            }

            val billingText = getBillingText(prod.productType, pricing.billingPeriod)
            if (freeTrialDays > 0) {
                binding.billingInfo.text = context.getString(R.string.trial_days_format, freeTrialDays)
            } else {
                binding.billingInfo.text = billingText
            }

            if (discountedPrice.isNotEmpty()) {
                val pct = calculateSavings(currentPrice, discountedPrice)
                if (pct > 0) {
                    binding.savingsText.visibility = View.VISIBLE
                    binding.savingsText.text = context.getString(R.string.save_percentage, "${pct}%")
                } else {
                    binding.savingsText.visibility = View.GONE
                }
            } else {
                binding.savingsText.visibility = View.GONE
            }

            // selection via card stroke only (no radio button)
            applySelectionStyle(isSelected)

            Logger.d(LOG_TAG_UI, "Premium Plan: $planTitle, Price: $displayPrice, Yearly: $isYearly, Selected: $isSelected")

            binding.planCard.setOnClickListener {
                val prev = selectedPos
                selectedPos = pos
                listener.onSubscriptionSelected(prod.productId, prod.planId)
                Logger.d(LOG_IAB, "Selected Plan: ${prod.productId}, ${prod.planId}")
                if (prev >= 0 && prev < pds.size) notifyItemChanged(prev)
                notifyItemChanged(pos)
                animateSelection()
            }
        }

        /** Returns the total duration in months for known INAPP product IDs, or 0 if unknown. */
        private fun getInAppDurationMonths(productId: String): Int = when (productId) {
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> 24
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> 60
            InAppBillingHandler.ONE_TIME_PRODUCT_ID -> 24 // legacy default 2yr
            else -> 0
        }

        /**
         * Formats a duration in months to a human-readable label like "2 Years" or appends to
         * planTitle.
         */
        private fun formatDurationLabel(months: Int, planTitle: String): String {
            return when {
                months >= 12 && months % 12 == 0 -> {
                    val yrs = months / 12
                    context.resources.getQuantityString(R.plurals.duration_years, yrs, yrs)
                }
                else -> planTitle
            }
        }

        /**
         * Attempts to format [micros] as a currency string using the same symbol/format as
         * [sampleFormatted] (the already-formatted full price from Play). Strips digits/decimal
         * from [sampleFormatted] and replaces with the per-month amount.
         */
        private fun formatMicrosAsCurrency(micros: Long, currencyCode: String, sampleFormatted: String): String? {
            return try {
                val amount = micros / 1_000_000.0
                // Extract currency prefix/suffix from sample (e.g. "₹" or "US$")
                val numericPart = sampleFormatted.replace(Regex("[0-9,. ]+"), "").trim()
                val formatted = if (amount >= 100) {
                    String.format(Locale.getDefault(), "%.0f", amount)
                } else {
                    String.format(Locale.getDefault(), "%.2f", amount).trimEnd('0').trimEnd('.')
                }
                if (numericPart.isNotEmpty()) "$numericPart$formatted" else "$currencyCode $formatted"
            } catch (_: Exception) {
                null
            }
        }

        private fun applySelectionStyle(selected: Boolean) {
            if (selected) {
                binding.planCard.strokeWidth = 3
                binding.planCard.strokeColor = fetchColor(context, R.attr.accentGood)
                binding.planCard.cardElevation = context.resources.displayMetrics.density * 4f
            } else {
                binding.planCard.strokeWidth = 1
                binding.planCard.strokeColor = fetchColor(context, R.attr.chipBgColorNeutral)
                binding.planCard.cardElevation = context.resources.displayMetrics.density * 1f
            }
        }

        private fun animateSelection() {
            val scaleX = ObjectAnimator.ofFloat(binding.planCard, "scaleX", 1f, 0.96f, 1f)
            val scaleY = ObjectAnimator.ofFloat(binding.planCard, "scaleY", 1f, 0.96f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 120
                start()
            }
        }

        private fun calculateSavings(originalPrice: String, discountedPrice: String): Int {
            return try {
                val original = originalPrice.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                val discounted = discountedPrice.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                if (original > 0 && discounted > 0) ((original - discounted) / original * 100).toInt() else 0
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err calculating savings: ${e.message}")
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

    /** DiffUtil callback for efficient list updates */
    private class ProductDiffCallback(
        private val old: List<ProductDetail>,
        private val new: List<ProductDetail>
    ): DiffUtil.Callback() {
        override fun getOldListSize() = old.size

        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos].productId == new[newPos].productId && old[oldPos].planId == new[newPos].planId

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos] == new[newPos]
    }
}
