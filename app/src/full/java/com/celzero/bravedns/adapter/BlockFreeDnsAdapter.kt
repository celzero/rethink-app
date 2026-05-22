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
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.BlockFreeDnsItem
import com.celzero.bravedns.data.BlockFreeDnsType
import com.celzero.bravedns.databinding.ListItemBlockFreeDnsBinding
import com.celzero.bravedns.util.UIUtils.fetchColor

class BlockFreeDnsAdapter(
    private val context: Context,
    private var selectedKey: String,
    private val onItemSelected: (BlockFreeDnsItem) -> Unit
) : ListAdapter<BlockFreeDnsItem, BlockFreeDnsAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BlockFreeDnsItem>() {
            override fun areItemsTheSame(old: BlockFreeDnsItem, new: BlockFreeDnsItem) =
                old.key == new.key

            override fun areContentsTheSame(old: BlockFreeDnsItem, new: BlockFreeDnsItem) =
                old == new
        }
    }

    fun updateSelectedKey(key: String) {
        selectedKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ListItemBlockFreeDnsBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item, item.key == selectedKey)
    }

    inner class ViewHolder(private val b: ListItemBlockFreeDnsBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: BlockFreeDnsItem, isSelected: Boolean) {
            b.bfdItemName.text = item.name
            b.bfdItemUrl.text = if (item.type == BlockFreeDnsType.SYSTEM) "Underlying network's DNS" else item.url

            // Type chip label and color
            b.bfdItemTypeChip.text = item.type.label
            b.bfdItemTypeChip.chipBackgroundColor = ColorStateList.valueOf(typeColor())

            // Selection indicator
            b.bfdItemCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

            b.root.setOnClickListener {
                onItemSelected(item)
            }
        }

        private fun typeColor(): Int {
            return fetchColor(context, R.attr.chipBgColorPositive)
        }
    }
}

