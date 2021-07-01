/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.databinding.DnsBlockListItemBinding

class DNSBottomSheetBlockAdapter(val context: Context, val data: List<String>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSBottomSheetViewHolder {
        val itemBinding = DnsBlockListItemBinding.inflate(LayoutInflater.from(parent.context),
                                                          parent, false)
        return DNSBottomSheetViewHolder(itemBinding)
    }

    private fun getItem(position: Int): String {
        return data[position]
    }


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val dnsBlockItem = getItem(position)
        if (holder is DNSBottomSheetViewHolder) {
            holder.update(dnsBlockItem)
        }

    }

    override fun getItemCount(): Int {
        return data.size
    }


    inner class DNSBottomSheetViewHolder(private val b: DnsBlockListItemBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(dnsBlockItem: String?) {
            if (dnsBlockItem != null) {
                val items = dnsBlockItem.split(":").toTypedArray()
                if (items.size == 1) {
                    b.dnsBlockListUrlName.text = items[0]
                    b.dnsBlockListHeader.visibility = View.INVISIBLE
                } else if (items.size == 2) {
                    b.dnsBlockListUrlName.text = items[1]
                    b.dnsBlockListHeader.text = items[0]
                }
            }
        }
    }
}