package com.celzero.bravedns.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.databinding.ItemSsidBinding
import com.celzero.bravedns.util.UIUtils

class SsidAdapter(
    private val ssidItems: MutableList<SsidItem>,
    private val onDeleteClick: (SsidItem) -> Unit
) : RecyclerView.Adapter<SsidAdapter.SsidViewHolder>() {

    class SsidViewHolder(private val binding: ItemSsidBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(ssidItem: SsidItem, onDeleteClick: (SsidItem) -> Unit) {
            val context = binding.root.context
            binding.ssidNameText.text = ssidItem.name
            binding.ssidTypeText.text = ssidItem.type.getDisplayName(context)

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