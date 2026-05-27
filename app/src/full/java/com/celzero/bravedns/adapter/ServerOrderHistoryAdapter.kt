/*
 * Copyright 2026 RethinkDNS and its authors
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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemServerOrderBinding
import com.celzero.bravedns.iab.ServerOrderEntry
import com.celzero.bravedns.util.UIUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [ListAdapter] for [ServerOrderEntry] items fetched from the billing server.
 *
 * Displays subscription and one-time purchase records with a card UI,
 * including product type badge, status chip, dates and order ID.
 */
class ServerOrderHistoryAdapter(private val context: Context) :
    ListAdapter<ServerOrderEntry, ServerOrderHistoryAdapter.OrderViewHolder>(DIFF_CB) {

    companion object {
        private val DIFF_CB = object : DiffUtil.ItemCallback<ServerOrderEntry>() {
            override fun areItemsTheSame(old: ServerOrderEntry, new: ServerOrderEntry) =
                old.purchaseToken == new.purchaseToken
            override fun areContentsTheSame(old: ServerOrderEntry, new: ServerOrderEntry) =
                old == new
        }

        private val DATE_FMT = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.ENGLISH)
        private val DATE_ONLY = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ListItemServerOrderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position), position == itemCount - 1)
    }

    inner class OrderViewHolder(private val b: ListItemServerOrderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(entry: ServerOrderEntry, isLast: Boolean) {
            bindTypeBadge(entry)
            bindStatusChip(entry)
            bindProductInfo(entry)
            bindDates(entry)
            bindOrderId(entry)
            bindTestBadge(entry)
            b.divider.visibility = if (isLast) View.GONE else View.VISIBLE
        }

        private fun bindTypeBadge(entry: ServerOrderEntry) {
            if (entry.isSubscription) {
                b.tvTypeBadge.text = context.getString(R.string.server_order_type_subscription)
                b.tvTypeBadge.backgroundTintList = ContextCompat.getColorStateList(
                    context, R.color.chipBackgroundColor
                )
                b.tvTypeBadge.setTextColor(
                    UIUtils.fetchColor(context, R.attr.chipTextPositive)
                )
                b.ivTypeIcon.setImageResource(R.drawable.ic_rethink_plus)
                b.ivTypeIcon.imageTintList = ContextCompat.getColorStateList(
                    context, R.color.chipBackgroundColor
                )
            } else {
                b.tvTypeBadge.text = context.getString(R.string.server_order_type_onetime)
                b.tvTypeBadge.backgroundTintList = ContextCompat.getColorStateList(
                    context, R.color.chipBgNeutral
                )
                b.tvTypeBadge.setTextColor(
                    UIUtils.fetchColor(context, R.attr.chipTextNeutral)
                )
                b.ivTypeIcon.setImageResource(R.drawable.ic_rethink_plus_sparkle)
                b.ivTypeIcon.imageTintList = ContextCompat.getColorStateList(
                    context, R.color.chipBgNeutral
                )
            }
        }

        private fun bindStatusChip(entry: ServerOrderEntry) {
            val (label, bgAttr, textAttr) = statusStyle(entry)
            b.tvStatusChip.text = label
            b.tvStatusChip.backgroundTintList = ContextCompat.getColorStateList(
                context, bgAttr
            )
            b.tvStatusChip.setTextColor(UIUtils.fetchColor(context, textAttr))
        }

        /**
         * Returns Triple(label, bgColorRes, textAttr) for the status chip.
         */
        private fun statusStyle(entry: ServerOrderEntry): Triple<String, Int, Int> {
            if (entry.isSubscription) {
                return when (entry.subscriptionState) {
                    ServerOrderEntry.STATE_ACTIVE -> Triple(
                        context.getString(R.string.lbl_active),
                        R.color.chipBgPositive,
                        R.attr.chipTextPositive
                    )
                    ServerOrderEntry.STATE_CANCELLED -> Triple(
                        context.getString(R.string.lbl_cancelled),
                        R.color.chipBgNegative,
                        R.attr.chipTextNegative
                    )
                    ServerOrderEntry.STATE_EXPIRED -> Triple(
                        context.getString(R.string.lbl_expired),
                        R.color.chipBgNeutral,
                        R.attr.chipTextNeutral
                    )
                    ServerOrderEntry.STATE_PAUSED -> Triple(
                        context.getString(R.string.lbl_paused),
                        R.color.chipBgNeutral,
                        R.attr.chipTextNeutral
                    )
                    ServerOrderEntry.STATE_ON_HOLD -> Triple(
                        context.getString(R.string.server_selection_sub_on_hold),
                        R.color.chipBgNeutral,
                        R.attr.chipTextNeutral
                    )
                    else -> Triple(
                        entry.subscriptionState
                            ?.removePrefix("SUBSCRIPTION_STATE_")
                            ?.replace('_', ' ')
                            ?.lowercase()
                            ?.replaceFirstChar { it.uppercase() }
                            ?: context.getString(R.string.network_log_app_name_unknown),
                        R.color.chipBgNeutral,
                        R.attr.primaryLightColorText
                    )
                }
            } else {
                return when (entry.purchaseState) {
                    ServerOrderEntry.PURCHASE_STATE_PURCHASED -> Triple(
                        context.getString(R.string.rpn_purchased_state),
                        R.color.chipBgPositive,
                        R.attr.chipTextPositive
                    )
                    ServerOrderEntry.PURCHASE_STATE_CANCELLED -> Triple(
                        context.getString(R.string.lbl_cancelled),
                        R.color.chipBgNegative,
                        R.attr.chipTextNegative
                    )
                    ServerOrderEntry.PURCHASE_STATE_PENDING -> Triple(
                        context.getString(R.string.payment_history_state_pending),
                        R.color.chipBgNeutral,
                        R.attr.chipTextNeutral
                    )
                    else -> Triple(
                        context.getString(R.string.network_log_app_name_unknown),
                        R.color.chipBgNeutral,
                        R.attr.primaryLightColorText
                    )
                }
            }
        }

        private fun bindProductInfo(entry: ServerOrderEntry) {
            b.tvProductName.text = formatProductName(entry)
            val tokenShort = entry.purchaseToken.take(12)
            val cidShort = entry.cid.take(12)
            b.tvToken.text = context.getString(R.string.two_argument_dot, tokenShort, cidShort)
        }

        private fun formatProductName(entry: ServerOrderEntry): String {
            val sku = entry.planId.ifBlank { entry.productId }.ifBlank { entry.sku }
            return sku.split('.', '_', '-')
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        }

        private fun bindDates(entry: ServerOrderEntry) {
            if (entry.isSubscription) {
                val startLabel = if (entry.startTimeMs > 0)
                    context.getString(R.string.server_order_started_fmt,
                        DATE_ONLY.format(Date(entry.startTimeMs))) else ""

                b.tvDatePrimary.text = startLabel.ifEmpty { DATE_FMT.format(Date(entry.mtime)) }

                val now = System.currentTimeMillis()
                val expiryPassed = entry.expiryTimeMs in 1..now
                val showExpiry = !entry.autoRenewEnabled || expiryPassed

                val secondaryText = if (showExpiry) {
                    if (entry.expiryTimeMs > 0)
                        context.getString(R.string.server_order_expires_fmt,
                            DATE_ONLY.format(Date(entry.expiryTimeMs)))
                    else ""
                } else {
                    context.getString(R.string.server_order_auto_renew, DATE_ONLY.format(Date(entry.expiryTimeMs)))
                }

                b.tvDateSecondary.text = secondaryText
                b.tvDateSecondary.visibility =
                    if (secondaryText.isNotEmpty()) View.VISIBLE else View.GONE
            } else {
                b.tvDatePrimary.text = if (entry.purchaseTimeMs > 0)
                    DATE_FMT.format(Date(entry.purchaseTimeMs))
                else DATE_FMT.format(Date(entry.mtime))
                b.tvDateSecondary.visibility = View.GONE
            }
        }

        private fun bindOrderId(entry: ServerOrderEntry) {
            val orderId = entry.orderId
            if (orderId.isNullOrBlank()) {
                b.tvOrderId.visibility = View.GONE
            } else {
                b.tvOrderId.visibility = View.VISIBLE
                b.tvOrderId.text = context.getString(R.string.server_order_id_fmt, orderId)
            }
        }

        private fun bindTestBadge(entry: ServerOrderEntry) {
            b.tvTestBadge.visibility = if (entry.isTestPurchase) View.VISIBLE else View.GONE
            if (entry.isTestPurchase) {
                b.tvTestBadge.text = "Test"
                b.tvTestBadge.backgroundTintList = ContextCompat.getColorStateList(
                    context, R.color.chipBgNeutral
                )
                b.tvTestBadge.setTextColor(
                    UIUtils.fetchColor(context, R.attr.chipTextNeutral)
                )
            }
        }
    }
}

