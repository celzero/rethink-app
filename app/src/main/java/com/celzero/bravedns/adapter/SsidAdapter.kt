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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.databinding.ItemSsidBinding
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities

class SsidAdapter(
    private val ssidItems: MutableList<SsidItem>,
    private val onDeleteClick: (SsidItem) -> Unit
) : RecyclerView.Adapter<SsidAdapter.SsidViewHolder>() {

    class SsidViewHolder(private val binding: ItemSsidBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(ssidItem: SsidItem, onDeleteClick: (SsidItem) -> Unit) {
            binding.ssidNameText.text = ssidItem.name
            binding.ssidTypeText.text = ssidItem.type.displayName

            val context = binding.root.context
            val color = UIUtils.fetchColor(context, R.attr.accentBad)
            binding.ssidTypeText.setTextColor(color)

            binding.deleteSsidBtn.setOnClickListener {
                onDeleteClick(ssidItem)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SsidViewHolder {
        val binding = ItemSsidBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SsidViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SsidViewHolder, position: Int) {
        holder.bind(ssidItems[position], onDeleteClick)
    }

    override fun getItemCount(): Int = ssidItems.size

    fun addSsidItem(ssidItem: SsidItem) {
        // Check if already exists
        if (!ssidItems.any { it.name == ssidItem.name && it.type == ssidItem.type }) {
            ssidItems.add(ssidItem)
            notifyItemInserted(ssidItems.size - 1)
        }
    }

    fun removeSsidItem(ssidItem: SsidItem) {
        val index = ssidItems.indexOf(ssidItem)
        if (index != -1) {
            ssidItems.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun getSsidItems(): List<SsidItem> = ssidItems.toList()
}
