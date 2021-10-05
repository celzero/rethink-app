/*
 * Copyright 2021 RethinkDNS and its authors
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
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.automaton.CustomDomainManager
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.databinding.ListItemCustomDomainsBinding

class CustomDomainAdapter(private val context: Context, val lifecycleOwner: LifecycleOwner) :
        PagedListAdapter<CustomDomain, CustomDomainAdapter.CustomDomainViewHolder>(DIFF_CALLBACK) {

    companion object {

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CustomDomain>() {
            override fun areItemsTheSame(oldConnection: CustomDomain,
                                         newConnection: CustomDomain): Boolean {
                return (oldConnection.domain == newConnection.domain && oldConnection.status == newConnection.status)
            }

            override fun areContentsTheSame(oldConnection: CustomDomain,
                                            newConnection: CustomDomain): Boolean {
                return (oldConnection.domain == newConnection.domain && oldConnection.status != newConnection.status)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomDomainViewHolder {
        val itemBinding = ListItemCustomDomainsBinding.inflate(LayoutInflater.from(parent.context),
                                                               parent, false)
        return CustomDomainViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: CustomDomainViewHolder, position: Int) {
        val customDomain: CustomDomain = getItem(position) ?: return
        holder.update(customDomain)
    }


    inner class CustomDomainViewHolder(private val b: ListItemCustomDomainsBinding) :
            RecyclerView.ViewHolder(b.root) {
        fun update(customDomain: CustomDomain) {
            b.domainName.text = customDomain.domain
            when (customDomain.status) {
                CustomDomainManager.CustomDomainStatus.WHITELIST.statusId -> {
                    enableWhitelistIcon()
                    disableBlocklistIcon()
                }
                CustomDomainManager.CustomDomainStatus.BLOCKLIST.statusId -> {
                    enableBlocklistIcon()
                    disableWhitelistIcon()
                }
                CustomDomainManager.CustomDomainStatus.NONE.statusId -> {
                    disableWhitelistIcon()
                    disableBlocklistIcon()
                }
            }

            b.whitelistIcon.setOnClickListener {
                CustomDomainManager.toggleStatus(customDomain,
                                                 CustomDomainManager.CustomDomainStatus.WHITELIST)
            }

            b.blocklistIcon.setOnClickListener {
                CustomDomainManager.toggleStatus(customDomain,
                                                 CustomDomainManager.CustomDomainStatus.BLOCKLIST)
            }

            b.deleteIcon.setOnClickListener {
                CustomDomainManager.deleteDomain(customDomain)
            }
        }

        private fun enableWhitelistIcon() {
            b.whitelistIcon.alpha = 1f
        }

        private fun disableWhitelistIcon() {
            b.whitelistIcon.alpha = 0.5f
        }

        private fun enableBlocklistIcon() {
            b.blocklistIcon.alpha = 1f
        }

        private fun disableBlocklistIcon() {
            b.blocklistIcon.alpha = 0.5f
        }
    }
}
