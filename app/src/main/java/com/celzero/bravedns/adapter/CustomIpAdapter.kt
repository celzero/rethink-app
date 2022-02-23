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
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.automaton.IpRulesManager.UID_EVERYBODY
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.databinding.ListItemCustomIpBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomIpAdapter(private val context: Context) :
        PagedListAdapter<CustomIp, CustomIpAdapter.CustomIpsViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CustomIp>() {

            override fun areItemsTheSame(oldConnection: CustomIp,
                                         newConnection: CustomIp) = oldConnection.ipAddress == oldConnection.ipAddress && oldConnection.status == oldConnection.status

            override fun areContentsTheSame(oldConnection: CustomIp,
                                            newConnection: CustomIp) = oldConnection.ipAddress == oldConnection.ipAddress && oldConnection.status != oldConnection.status
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomIpsViewHolder {
        val itemBinding = ListItemCustomIpBinding.inflate(LayoutInflater.from(parent.context),
                                                          parent, false)
        return CustomIpsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: CustomIpsViewHolder, position: Int) {
        val customIp: CustomIp = getItem(position) ?: return
        holder.update(customIp)
    }

    inner class CustomIpsViewHolder(private val b: ListItemCustomIpBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(customIp: CustomIp) {
            b.customIpsLabelTv.text = customIp.ipAddress
            when (IpRulesManager.IpRuleStatus.getStatus(customIp.status)) {
                IpRulesManager.IpRuleStatus.WHITELIST -> {
                    enableBtn(b.customIpsAllowIcon)
                    disableBtn(b.customIpsBlockIcon)
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    enableBtn(b.customIpsBlockIcon)
                    disableBtn(b.customIpsAllowIcon)
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    disableBtn(b.customIpsAllowIcon)
                    disableBtn(b.customIpsBlockIcon)
                }
            }

            b.customIpsDeleteIcon.setOnClickListener {
                showDialogForDelete(customIp)
            }
            b.customIpsAllowIcon.setOnClickListener {
                whitelistIp(customIp)
            }
            b.customIpsBlockIcon.setOnClickListener {
                blockIp(customIp)
            }
        }

        private fun enableBtn(button: AppCompatImageView) {
            button.alpha = 1f
        }

        private fun disableBtn(button: AppCompatImageView) {
            button.alpha = 0.5f
        }

        private fun whitelistIp(customIp: CustomIp) {
            // TODO: Implement allow ip
            IpRulesManager.whitelistIp(customIp)
        }

        private fun blockIp(customIp: CustomIp) {
            // TODO: Implement block ip
            IpRulesManager.blockIp(customIp)
        }

        private fun showDialogForDelete(customIp: CustomIp) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.univ_firewall_dialog_title)
            builder.setMessage(R.string.univ_firewall_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(
                context.getString(R.string.univ_ip_delete_individual_positive)) { _, _ ->
                IpRulesManager.removeFirewallRules(UID_EVERYBODY, customIp.ipAddress)
                Toast.makeText(context,
                                                   context.getString(R.string.univ_ip_delete_individual_toast,
                                                                     customIp.ipAddress), Toast.LENGTH_SHORT).show()
            }

            builder.setNegativeButton(
                context.getString(R.string.univ_ip_delete_individual_negative)) { _, _ -> }

            builder.create().show()
        }

    }

}
