/*
 * Copyright 2025 RethinkDNS and its authors
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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStateHistory
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.databinding.ListItemPurchaseHistoryBinding
import com.celzero.bravedns.util.UIUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Paging adapter for [SubscriptionStateHistory] rows.
 */
class PurchaseHistoryAdapter(private val context: Context) :
    PagingDataAdapter<SubscriptionStateHistory, PurchaseHistoryAdapter.HistoryViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SubscriptionStateHistory>() {
            override fun areItemsTheSame(
                old: SubscriptionStateHistory,
                new: SubscriptionStateHistory,
            ) = old.id == new.id

            override fun areContentsTheSame(
                old: SubscriptionStateHistory,
                new: SubscriptionStateHistory,
            ) = old == new
        }

        private val DATE_FORMAT = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ListItemPurchaseHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        // getItem() may return null while a placeholder page is loading
        val entry = getItem(position) ?: return
        holder.bind(entry)
    }

    inner class HistoryViewHolder(private val b: ListItemPurchaseHistoryBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(entry: SubscriptionStateHistory) {
            b.tvTimestamp.text = DATE_FORMAT.format(Date(entry.timestamp))

            val stateLabel = context.getString(stateStringRes(entry.toState))
            b.tvStateBadge.text = stateLabel
            val badgeBg = UIUtils.fetchColor(context, stateBadgeAttrs(entry))
            b.tvStateBadge.backgroundTintList = ColorStateList.valueOf(badgeBg)

            b.tvTransition.text = context.getString(
                R.string.payment_history_transition_fmt,
                friendlyStateName(context, entry.fromState),
                friendlyStateName(context, entry.toState)
            )

            val reason = entry.reason
            if (reason.isNullOrBlank()) {
                b.tvReason.visibility = View.GONE
            } else {
                b.tvReason.visibility = View.VISIBLE
                // trim very long error strings
                b.tvReason.text = if (reason.length > 120) reason.take(120) + "…" else reason
            }

            val (iconRes, iconTint) = stateIconAndTint(entry)
            b.ivStatusIcon.setImageResource(iconRes)
            val tintColor = UIUtils.fetchColor(context, iconTint)
            b.ivStatusIcon.imageTintList = ColorStateList.valueOf(tintColor)

            // purchase token is not available without the JOIN; always hide.
            b.tvPurchaseToken.visibility = View.GONE

            b.divider.visibility =
                if (bindingAdapterPosition == itemCount - 1) View.GONE else View.VISIBLE
        }

        private fun isSuccess(entry: SubscriptionStateHistory) =
            entry.toState == SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id ||
                entry.toState == SubscriptionStatus.SubscriptionState.STATE_PURCHASED.id

        private fun isFailure(entry: SubscriptionStateHistory) =
            entry.toState == SubscriptionStatus.SubscriptionState.STATE_PURCHASE_FAILED.id ||
                entry.toState == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id

        private fun isPending(entry: SubscriptionStateHistory) =
            entry.toState == SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id

        private fun stateBadgeAttrs(entry: SubscriptionStateHistory): Int = when {
            isSuccess(entry) -> R.attr.chipBgColorPositive
            isFailure(entry) -> R.attr.chipBgColorNegative
            isPending(entry) -> R.attr.chipBgColorNeutral
            entry.toState == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id ->
                R.attr.chipBgColorNegative
            entry.toState == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id ->
                R.attr.chipBgColorNeutral
            else -> R.attr.chipBgColorNeutral
        }

        private fun stateIconAndTint(entry: SubscriptionStateHistory): Pair<Int, Int> = when {
            isSuccess(entry) -> Pair(R.drawable.ic_check_circle, R.attr.accentGood)
            isFailure(entry) -> Pair(R.drawable.ic_stop, R.attr.primaryTextColor)
            isPending(entry) -> Pair(R.drawable.ic_refresh, R.attr.primaryTextColor)
            entry.toState == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id ->
                Pair(R.drawable.ic_stop, R.attr.accentBad)
            entry.toState == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id ->
                Pair(R.drawable.ic_idle_timeout, R.attr.primaryTextColor)
            else -> Pair(R.drawable.ic_info, R.attr.primaryTextColor)
        }
    }

    private fun stateStringRes(stateId: Int): Int {
        return when (SubscriptionStatus.SubscriptionState.fromId(stateId)) {
            SubscriptionStatus.SubscriptionState.STATE_ACTIVE -> R.string.lbl_active
            SubscriptionStatus.SubscriptionState.STATE_CANCELLED -> R.string.lbl_cancelled
            SubscriptionStatus.SubscriptionState.STATE_EXPIRED -> R.string.lbl_expired
            SubscriptionStatus.SubscriptionState.STATE_REVOKED -> R.string.status_revoked
            SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING -> R.string.payment_history_state_pending
            SubscriptionStatus.SubscriptionState.STATE_PURCHASED -> R.string.rpn_purchased_state
            SubscriptionStatus.SubscriptionState.STATE_PURCHASE_FAILED -> R.string.ping_status_failed
            SubscriptionStatus.SubscriptionState.STATE_GRACE -> R.string.lbl_grace_period
            SubscriptionStatus.SubscriptionState.STATE_ON_HOLD -> R.string.server_selection_sub_on_hold
            SubscriptionStatus.SubscriptionState.STATE_PAUSED -> R.string.lbl_paused
            else -> R.string.network_log_app_name_unknown
        }
    }

    private fun friendlyStateName(ctx: Context, stateId: Int): String {
        return ctx.getString(stateStringRes(stateId))
    }
}
