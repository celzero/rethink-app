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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.DomainRulesManager
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.databinding.ListItemCustomDomainBinding
import com.celzero.bravedns.util.Utilities

class CustomDomainAdapter(val context: Context) :
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
        val itemBinding = ListItemCustomDomainBinding.inflate(LayoutInflater.from(parent.context),
                                                              parent, false)
        return CustomDomainViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: CustomDomainViewHolder, position: Int) {
        val customDomain: CustomDomain = getItem(position) ?: return
        holder.update(customDomain)
    }

    inner class CustomDomainViewHolder(private val b: ListItemCustomDomainBinding) :
            RecyclerView.ViewHolder(b.root) {
        fun update(customDomain: CustomDomain) {
            b.domainName.text = customDomain.domain
            updateDomainStatus(customDomain)
            setupClickListeners(customDomain)
        }

        private fun updateDomainStatus(customDomain: CustomDomain) {
            when (customDomain.status) {
                DomainRulesManager.DomainStatus.WHITELISTED.statusId -> {
                    enableWhitelistIcon()
                    disableBlocklistIcon()
                }
                DomainRulesManager.DomainStatus.BLOCKED.statusId -> {
                    enableBlocklistIcon()
                    disableWhitelistIcon()
                }
                DomainRulesManager.DomainStatus.NONE.statusId -> {
                    disableWhitelistIcon()
                    disableBlocklistIcon()
                }
            }
        }

        private fun setupClickListeners(customDomain: CustomDomain) {
            b.whitelistIcon.setOnClickListener {
                toggleStatus(customDomain, DomainRulesManager.DomainStatus.WHITELISTED,
                             context.getString(R.string.cd_toast_whitelist))
            }

            b.blocklistIcon.setOnClickListener {
                toggleStatus(customDomain, DomainRulesManager.DomainStatus.BLOCKED,
                             context.getString(R.string.cd_toast_blocklist))
            }

            b.deleteIcon.setOnClickListener {
                showDeleteDomainDialog(customDomain)
            }
        }

        private fun toggleStatus(customDomain: CustomDomain,
                                 status: DomainRulesManager.DomainStatus, toastText: String) {
            DomainRulesManager.toggleStatus(customDomain, status)
            Utilities.showToastUiCentered(context, toastText, Toast.LENGTH_SHORT)
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

        private fun showDeleteDomainDialog(customDomain: CustomDomain) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.cd_remove_dialog_title)
            builder.setMessage(R.string.cd_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(
                context.getString(R.string.cd_remove_dialog_positive)) { _, _ ->
                DomainRulesManager.deleteDomain(customDomain)
                Utilities.showToastUiCentered(context, context.getString(R.string.cd_toast_deleted),
                                              Toast.LENGTH_SHORT)
            }

            builder.setNegativeButton(
                context.getString(R.string.cd_remove_dialog_negative)) { _, _ ->
                // no-op
            }
            builder.create().show()
        }
    }
}
