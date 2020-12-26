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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG

class DNSBottomSheetBlockAdapter(val context: Context, val data: List<String>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSBottomSheetViewHolder {
        val v: View = LayoutInflater.from(parent.context).inflate(
            R.layout.dns_block_list_item,
            parent, false
        )
        return DNSBottomSheetViewHolder(v)
    }

    private fun getItem(position: Int): String? {
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


    inner class DNSBottomSheetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var listNameTxt: TextView
        private var headerTxt: TextView
        private var subHeaderTxt: TextView


        init {
            rowView = itemView
            listNameTxt = itemView.findViewById(R.id.dns_block_list_url_name)
            headerTxt = itemView.findViewById(R.id.dns_block_list_header)
            subHeaderTxt = itemView.findViewById(R.id.dns_block_list_subheader)
        }

        fun update(dnsBlockItem: String?) {
            if (dnsBlockItem != null) {
                if (DEBUG) Log.d(LOG_TAG, "Update - blocklist ==> $dnsBlockItem")
                val items = dnsBlockItem.split(":").toTypedArray()
                if(items.size ==1){
                    listNameTxt.text = items[0]
                    headerTxt.visibility = View.INVISIBLE
                }else if(items.size == 2){
                    listNameTxt.text = items[1]
                    headerTxt.text = items[0]
                }
            }
        }
    }
}