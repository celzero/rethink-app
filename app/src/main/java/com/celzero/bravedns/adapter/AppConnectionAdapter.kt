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
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.automaton.IpRulesManager.addIpRule
import com.celzero.bravedns.data.AppConnections
import com.celzero.bravedns.databinding.ListItemAppConnDetailsBinding
import com.celzero.bravedns.ui.AppConnectionBottomSheet
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchColor

class AppConnectionAdapter(val context: Context, val connLists: List<AppConnections>,
                           val uid: Int) :
        RecyclerView.Adapter<AppConnectionAdapter.ConnectionDetailsViewHolder>(),
        AppConnectionBottomSheet.OnBottomSheetDialogFragmentDismiss {

    private lateinit var adapter: AppConnectionAdapter

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): AppConnectionAdapter.ConnectionDetailsViewHolder {
        val itemBinding = ListItemAppConnDetailsBinding.inflate(LayoutInflater.from(parent.context),
                                                                parent, false)
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: AppConnectionAdapter.ConnectionDetailsViewHolder,
                                  position: Int) {
        holder.update(position)
    }

    override fun getItemCount(): Int {
        return connLists.size
    }

    override fun notifyDataset(position: Int) {
        this.notifyItemChanged(position)
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppConnDetailsBinding) :
            RecyclerView.ViewHolder(b.root) {
        fun update(position: Int) {
            displayTransactionDetails(position)
            setupClickListeners(connLists[position], position)
        }

        private fun setupClickListeners(appConn: AppConnections, position: Int) {
            b.acdStatusButton.setOnClickListener {
                val ipRuleStatus = IpRulesManager.IpRuleStatus.getStatus(it.tag as Int)
                if (ipRuleStatus.noRule()) {
                    // add the ip to allow list
                    addIpRule(uid, appConn.ipAddress, IpRulesManager.IpRuleStatus.BLOCK,
                              IpRulesManager.IPRuleType.IPV4)
                    showBlockedBtn()
                    Utilities.showToastUiCentered(context,
                                                  "Blocking ${appConn.ipAddress} for this app",
                                                  Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }

                // open bottom sheet for options
                openBottomSheet(appConn.ipAddress, ipRuleStatus, position)
            }
        }

        private fun openBottomSheet(ipAddress: String, ipRuleStatus: IpRulesManager.IpRuleStatus,
                                    position: Int) {
            if (context !is AppCompatActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI, context.getString(R.string.ct_btm_sheet_error))
                return
            }

            val bottomSheetFragment = AppConnectionBottomSheet(adapter, uid, ipAddress,
                                                               ipRuleStatus, position)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(position: Int) {
            b.acdCount.text = connLists[position].count.toString()
            b.acdFlag.text = connLists[position].flag
            b.acdIpAddress.text = connLists[position].ipAddress

            when (IpRulesManager.getStatus(uid, connLists[position].ipAddress)) {
                IpRulesManager.IpRuleStatus.NONE -> showBlockBtn()
                IpRulesManager.IpRuleStatus.WHITELIST -> showWhitelistBtn()
                IpRulesManager.IpRuleStatus.BLOCK -> showBlockedBtn()
            }
        }

        private fun showBlockedBtn() {
            b.acdStatusButton.tag = IpRulesManager.IpRuleStatus.BLOCK.id
            b.acdStatusButton.setBackgroundResource(R.drawable.rectangle_border_background)
            b.acdStatusButton.setTextColor(fetchColor(context, R.attr.primaryTextColor))
            b.acdStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null,
                                                                      ContextCompat.getDrawable(
                                                                          context,
                                                                          R.drawable.ic_arrow_down_small),
                                                                      null)
            b.acdStatusButton.text = context.resources.getString(
                R.string.ada_ip_rules_status_blocked)
        }

        private fun showWhitelistBtn() {
            b.acdStatusButton.tag = IpRulesManager.IpRuleStatus.WHITELIST.id
            b.acdStatusButton.setBackgroundResource(R.drawable.rectangle_border_background)
            b.acdStatusButton.setTextColor(fetchColor(context, R.attr.primaryTextColor))
            b.acdStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null,
                                                                      ContextCompat.getDrawable(
                                                                          context,
                                                                          R.drawable.ic_arrow_down_small),
                                                                      null)
            b.acdStatusButton.text = context.resources.getString(
                R.string.ada_ip_rules_status_whitelist)
        }

        private fun showBlockBtn() {
            b.acdStatusButton.tag = IpRulesManager.IpRuleStatus.NONE.id
            b.acdStatusButton.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            b.acdStatusButton.setTextColor(fetchColor(context, R.attr.chipTextColor))
            b.acdStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            b.acdStatusButton.text = context.resources.getString(R.string.ada_ip_rules_status_block)
        }
    }

}
