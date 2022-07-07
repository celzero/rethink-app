/*
 * Copyright 2022 RethinkDNS and its authors
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.databinding.ListItemAppIpRulesBinding
import com.celzero.bravedns.ui.AppConnectionBottomSheet
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities.Companion.fetchColor

class AppIpRulesAdapter(private val context: Context, val uid: Int) :
        PagedListAdapter<CustomIp, AppIpRulesAdapter.CustomIpViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CustomIp>() {

            override fun areItemsTheSame(oldConnection: CustomIp,
                                         newConnection: CustomIp) = oldConnection.ipAddress == newConnection.ipAddress && oldConnection.status == newConnection.status

            override fun areContentsTheSame(oldConnection: CustomIp,
                                            newConnection: CustomIp) = oldConnection.ipAddress == newConnection.ipAddress && oldConnection.status == newConnection.status
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomIpViewHolder {
        val itemBinding = ListItemAppIpRulesBinding.inflate(LayoutInflater.from(parent.context),
                                                            parent, false)
        return CustomIpViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: AppIpRulesAdapter.CustomIpViewHolder, position: Int) {
        val customIp: CustomIp = getItem(position) ?: return

        holder.update(customIp, position)
    }

    inner class CustomIpViewHolder(private val b: ListItemAppIpRulesBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(customIp: CustomIp, position: Int) {
            displayIpDetails(customIp)
            setupClickListeners(customIp, position)
        }

        private fun setupClickListeners(customIp: CustomIp, position: Int) {
            b.aipStatusButton.setOnClickListener {
                val ipRuleStatus = IpRulesManager.IpRuleStatus.getStatus(customIp.status)
                // TODO: now we are showing delete button when the IP status is set as NONE,
                // maybe we should again show bottom sheet to add the rules back?
                if (ipRuleStatus.noRule()) {
                    // delete option is loaded when the ip rules is set as none
                    IpRulesManager.removeFirewallRules(uid, customIp.ipAddress)
                    return@setOnClickListener
                }

                // open bottom sheet for options
                openBottomSheet(customIp.ipAddress, ipRuleStatus, position)
            }
        }

        private fun openBottomSheet(ipAddress: String, ipRuleStatus: IpRulesManager.IpRuleStatus,
                                    position: Int) {
            if (context !is AppCompatActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI, context.getString(R.string.ct_btm_sheet_error))
                return
            }

            val bottomSheetFragment = AppConnectionBottomSheet(null, uid, ipAddress, ipRuleStatus,
                                                               position)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayIpDetails(customIp: CustomIp) {
            b.aipIpAddress.text = customIp.ipAddress

            when (IpRulesManager.getStatus(uid, customIp.ipAddress)) {
                IpRulesManager.IpRuleStatus.NONE -> showDeleteBtn()
                IpRulesManager.IpRuleStatus.WHITELIST -> showWhitelistBtn()
                IpRulesManager.IpRuleStatus.BLOCK -> showBlockedBtn()
            }
        }

        private fun showBlockedBtn() {
            b.aipStatusButton.tag = IpRulesManager.IpRuleStatus.BLOCK.id
            b.aipStatusButton.setBackgroundResource(R.drawable.rectangle_border_background)
            b.aipStatusButton.setTextColor(fetchColor(context, R.attr.primaryTextColor))
            b.aipStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null,
                                                                      ContextCompat.getDrawable(
                                                                          context,
                                                                          R.drawable.ic_arrow_down_small),
                                                                      null)
            b.aipStatusButton.text = context.resources.getString(
                R.string.ada_ip_rules_status_blocked)
        }

        private fun showWhitelistBtn() {
            b.aipStatusButton.tag = IpRulesManager.IpRuleStatus.WHITELIST.id
            b.aipStatusButton.setBackgroundResource(R.drawable.rectangle_border_background)
            b.aipStatusButton.setTextColor(fetchColor(context, R.attr.primaryTextColor))
            b.aipStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null,
                                                                      ContextCompat.getDrawable(
                                                                          context,
                                                                          R.drawable.ic_arrow_down_small),
                                                                      null)
            b.aipStatusButton.text = context.resources.getString(
                R.string.ada_ip_rules_status_whitelist)
        }

        private fun showDeleteBtn() {
            b.aipStatusButton.tag = IpRulesManager.IpRuleStatus.NONE.id
            b.aipStatusButton.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            b.aipStatusButton.setTextColor(fetchColor(context, R.attr.chipTextColor))
            b.aipStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            b.aipStatusButton.text = context.resources.getString(R.string.ada_ip_rules_delete)
        }
    }
}
