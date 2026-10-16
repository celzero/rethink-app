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
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemAppCountryDetailsBinding
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag
import com.celzero.bravedns.util.Utilities.isAtleastN
import kotlin.math.log2

class AppWiseCountriesAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : PagingDataAdapter<AppConnection, AppWiseCountriesAdapter.CountryViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppConnection>() {
            override fun areItemsTheSame(old: AppConnection, new: AppConnection): Boolean {
                return old.flag == new.flag
            }

            override fun areContentsTheSame(old: AppConnection, new: AppConnection): Boolean {
                return old == new
            }
        }

        private const val PERCENTAGE_MULTIPLIER = 100
        private const val MIN_VISIBLE_PERCENTAGE = 5
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryViewHolder {
        val binding = ListItemAppCountryDetailsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CountryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountryViewHolder, position: Int) {
        val conn = getItem(position) ?: return
        holder.bind(conn)
    }

    inner class CountryViewHolder(private val b: ListItemAppCountryDetailsBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(conn: AppConnection) {
            setFlag(conn.flag)
            setName(conn.flag)
            setCount(conn.count)
            setProgress(conn.count)
        }

        private fun setFlag(flag: String) {
            // the query only returns valid emoji flags; guard against empty
            // values anyway so a recycled row never shows a stale emoji
            b.accFlag.text = flag
        }

        private fun setName(flag: String) {
            val name = getCountryNameFromFlag(flag)
            b.accCountryName.text =
                if (name.isNotEmpty() && !isDashPlaceholder(name)) {
                    name
                } else {
                    // the flag emoji is already shown in the leading column, so
                    // the unknown case needs no dash marker or flag suffix
                    context.getString(R.string.network_log_app_name_unknown)
                }
        }

        private fun isDashPlaceholder(name: String): Boolean {
            return name.all { it == '-' }
        }

        private fun setCount(count: Int) {
            b.accCount.text = count.toString()
        }

        /**
         * Normalized against the current page snapshot on every bind, so the
         * bar length never depends on stale state from a previous data load.
         */
        private fun setProgress(count: Int) {
            b.accProgress.setIndicatorColor(fetchToggleBtnColors(context, R.color.accentGood))
            val percentage = calculatePercentage(count)
            if (isAtleastN()) {
                b.accProgress.setProgress(percentage, true)
            } else {
                b.accProgress.progress = percentage
            }
        }

        private fun calculatePercentage(count: Int): Int {
            val current = logScale(count)
            var max = current
            snapshot().items.forEach {
                val v = logScale(it.count)
                if (v > max) max = v
            }
            if (max <= 0.0) return 0
            val pct = (current / max * PERCENTAGE_MULTIPLIER).toInt()
            // keep a sliver visible for non-zero counts so the bar never disappears
            return if (pct == 0 && count > 0) MIN_VISIBLE_PERCENTAGE else pct
        }

        private fun logScale(count: Int): Double {
            return log2(count.coerceAtLeast(1).toDouble())
        }
    }
}
