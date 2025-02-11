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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.databinding.ListItemPlaySubsBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PricingPhase

class GooglePlaySubsAdapter(val list: List<PricingPhase>): RecyclerView.Adapter<GooglePlaySubsAdapter.SubscriptionPlansViewHolder>() {


    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): GooglePlaySubsAdapter.SubscriptionPlansViewHolder {
        val binding = ListItemPlaySubsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SubscriptionPlansViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: GooglePlaySubsAdapter.SubscriptionPlansViewHolder,
        position: Int
    ) {
        holder.bind(position)
    }

    override fun getItemCount(): Int {
        return list.size
    }


    inner class SubscriptionPlansViewHolder(private val binding: ListItemPlaySubsBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(pos: Int) {
            binding.subsName.text = list[pos].planTitle
            binding.subsPrice.text = list[pos].price
        }
    }

}
