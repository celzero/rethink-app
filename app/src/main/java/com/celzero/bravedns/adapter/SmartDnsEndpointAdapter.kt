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
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.util.SelectionIndicator
import com.celzero.bravedns.database.SmartDnsEndpoint
import com.celzero.bravedns.database.SmartDnsMode
import com.celzero.bravedns.databinding.ListItemEndpointBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SmartDnsEndpointAdapter(
    private val context: Context,
    private val isSmartDnsActive: () -> Boolean
) :
    ListAdapter<SmartDnsEndpoint, SmartDnsEndpointAdapter.SmartDnsEndpointViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<SmartDnsEndpoint>() {
                override fun areItemsTheSame(
                    oldEndpoint: SmartDnsEndpoint,
                    newEndpoint: SmartDnsEndpoint
                ): Boolean {
                    return oldEndpoint.id == newEndpoint.id
                }

                override fun areContentsTheSame(
                    oldEndpoint: SmartDnsEndpoint,
                    newEndpoint: SmartDnsEndpoint
                ): Boolean {
                    return oldEndpoint == newEndpoint
                }
            }
    }

    var onEndpointSelected: ((SmartDnsEndpoint) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmartDnsEndpointViewHolder {
        val itemBinding =
            ListItemEndpointBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SmartDnsEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: SmartDnsEndpointViewHolder, position: Int) {
        holder.update(getItem(position))
    }

    inner class SmartDnsEndpointViewHolder(private val b: ListItemEndpointBinding) :
        RecyclerView.ViewHolder(b.root) {
        private val selectionIndicator =
            SelectionIndicator(b.endpointSelectionOrbital, b.endpointSelectionPill)


        fun update(endpoint: SmartDnsEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: SmartDnsEndpoint) {
            b.root.setOnClickListener { onEndpointSelected?.invoke(endpoint) }
            b.endpointInfoImg.setOnClickListener { showExplanationDialog(endpoint) }
        }

        private fun displayDetails(endpoint: SmartDnsEndpoint) {
            b.endpointName.text = endpoint.dnsName
            val isSelected = endpoint.isSelected && isSmartDnsActive()
            b.root.contentDescription =
                context.getString(
                    if (isSelected) R.string.dns_list_item_selected_cd
                    else R.string.dns_list_item_select_cd,
                    endpoint.dnsName
                )
            selectionIndicator.update(isSelected)
            if (isSelected) {
                b.endpointDesc.text = context.getString(R.string.rt_filter_parent_selected)
                b.endpointDesc.visibility = View.VISIBLE
            } else {
                b.endpointDesc.visibility = View.GONE
            }
            // the info icon shows the explanation; there is no delete action as all
            // smart dns entries are default (non-custom)
            b.endpointInfoImg.setImageResource(R.drawable.ic_info)
            // flag text is not applicable for smart dns options
            b.endpointFlagText.visibility = View.GONE
        }

        private fun showExplanationDialog(endpoint: SmartDnsEndpoint) {
            val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
            builder.setTitle(endpoint.dnsName)
            builder.setMessage(endpoint.dnsExplanation)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_info_positive)) { dialog, _ ->
                dialog.dismiss()
            }
            builder.create().show()
        }
    }
}
