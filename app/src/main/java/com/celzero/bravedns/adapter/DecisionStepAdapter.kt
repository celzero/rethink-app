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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemDecisionStepBinding
import com.celzero.bravedns.service.ConnectionSimulator
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.util.UIUtils

class DecisionStepAdapter(private val context: Context) :
    ListAdapter<ConnectionSimulator.DecisionStep, DecisionStepAdapter.DecisionStepViewHolder>(DiffCallback) {

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ConnectionSimulator.DecisionStep>() {
            override fun areItemsTheSame(oldItem: ConnectionSimulator.DecisionStep, newItem: ConnectionSimulator.DecisionStep): Boolean {
                return oldItem.text == newItem.text
            }

            override fun areContentsTheSame(oldItem: ConnectionSimulator.DecisionStep, newItem: ConnectionSimulator.DecisionStep): Boolean {
                return oldItem == newItem
            }
        }
    }

    inner class DecisionStepViewHolder(private val b: ListItemDecisionStepBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(step: ConnectionSimulator.DecisionStep) {
            b.stepText.text = step.text
            val rule = step.rule
            val iconRes = rule?.let { FirewallRuleset.getRulesIcon(it.id) } ?: R.drawable.ic_whats_new
            b.stepIcon.setImageResource(iconRes)
            val tint = when {
                rule == null -> UIUtils.fetchColor(context, R.attr.secondaryTextColor)
                FirewallRuleset.ground(rule) -> UIUtils.fetchColor(context, R.attr.accentBad)
                else -> UIUtils.fetchColor(context, R.attr.accentGood)
            }
            b.stepIcon.setColorFilter(tint)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DecisionStepViewHolder {
        val b = ListItemDecisionStepBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DecisionStepViewHolder(b)
    }

    override fun onBindViewHolder(holder: DecisionStepViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
