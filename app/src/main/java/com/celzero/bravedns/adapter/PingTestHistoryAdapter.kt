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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ItemPingTestHistoryBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.PingTestHistoryEntry
import com.celzero.bravedns.util.UIUtils

/**
 * Renders the recent RPN reachability-test history maintained by
 * [RpnProxyManager.pingTestHistory] inside PingTestActivity.
 */
class PingTestHistoryAdapter(private val context: Context) :
    ListAdapter<PingTestHistoryEntry, PingTestHistoryAdapter.HistoryViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<PingTestHistoryEntry>() {
                override fun areItemsTheSame(
                    oldItem: PingTestHistoryEntry,
                    newItem: PingTestHistoryEntry
                ): Boolean {
                    return oldItem.timestamp == newItem.timestamp
                }

                override fun areContentsTheSame(
                    oldItem: PingTestHistoryEntry,
                    newItem: PingTestHistoryEntry
                ): Boolean {
                    return oldItem == newItem
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding =
            ItemPingTestHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.update(getItem(position))
    }

    inner class HistoryViewHolder(private val b: ItemPingTestHistoryBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(entry: PingTestHistoryEntry) {
            displayStatus(entry)
            displayDetails(entry)
        }

        private fun displayStatus(entry: PingTestHistoryEntry) {
            when (entry.outcomeEnum()) {
                RpnProxyManager.PingTestOutcome.SUCCESS -> {
                    b.historyIcon.setImageResource(R.drawable.ic_tick)
                    b.historyIcon.backgroundTintList =
                        ColorStateList.valueOf(
                            UIUtils.fetchColor(context, R.attr.colorSurfaceVariant)
                        )
                    b.historyIcon.imageTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.accentGood)
                        )
                    b.historyOutcome.text = context.getString(R.string.ping_reach_reachable)
                    b.historyOutcome.setTextColor(
                        ContextCompat.getColor(context, R.color.accentGood)
                    )
                }
                RpnProxyManager.PingTestOutcome.PARTIAL -> {
                    b.historyIcon.setImageResource(R.drawable.ic_cross_accent)
                    b.historyIcon.backgroundTintList =
                        ColorStateList.valueOf(
                            UIUtils.fetchColor(context, R.attr.colorSurfaceVariant)
                        )
                    b.historyIcon.imageTintList =
                        ColorStateList.valueOf(
                            UIUtils.fetchColor(context, R.attr.accentWarning)
                        )
                    b.historyOutcome.text = context.getString(R.string.ping_partial_title)
                    b.historyOutcome.setTextColor(
                        UIUtils.fetchColor(context, R.attr.accentWarning)
                    )
                }
                RpnProxyManager.PingTestOutcome.FAILURE -> {
                    b.historyIcon.setImageResource(R.drawable.ic_cross_accent)
                    b.historyIcon.backgroundTintList =
                        ColorStateList.valueOf(
                            UIUtils.fetchColor(context, R.attr.colorSurfaceVariant)
                        )
                    b.historyIcon.imageTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.accentBad)
                        )
                    b.historyOutcome.text = context.getString(R.string.ping_failure_title)
                    b.historyOutcome.setTextColor(
                        ContextCompat.getColor(context, R.color.accentBad)
                    )
                }
            }
        }

        private fun displayDetails(entry: PingTestHistoryEntry) {
            b.historyTarget.text = displayTarget(entry)

            val time = UIUtils.getRelativeTimeSpan(entry.timestamp) ?: ""
            b.historyMeta.text = if (entry.total > 1) {
                context.getString(R.string.ping_history_meta_passed, time, entry.passed, entry.total)
            } else {
                time
            }

            b.historyLatency.text = context.getString(R.string.ping_total_latency, entry.latencyMs)
        }

        private fun displayTarget(entry: PingTestHistoryEntry): String {
            return if (entry.isAuto()) {
                context.getString(R.string.ping_history_auto_targets)
            } else {
                entry.targets
            }
        }
    }
}
