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
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.paging.PagingDataAdapter
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
        PagingDataAdapter<CustomIp, AppIpRulesAdapter.CustomIpViewHolder>(DIFF_CALLBACK) {

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
                // open bottom sheet for options
                openBottomSheet(customIp.ipAddress, customIp.port, ipRuleStatus, position)
            }
        }

        private fun openBottomSheet(ipAddress: String, port: Int,
                                    ipRuleStatus: IpRulesManager.IpRuleStatus, position: Int) {
            if (context !is AppCompatActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI, "Error opening the app conn bottomsheet")
                return
            }

            val bottomSheetFragment = AppConnectionBottomSheet()
            // Fix: free-form window crash
            // all BottomSheetDialogFragment classes created must have a public, no-arg constructor.
            // the best practice is to simply never define any constructors at all.
            // so sending the data using Bundles
            val bundle = Bundle()
            bundle.putInt(AppConnectionBottomSheet.UID, uid)
            bundle.putString(AppConnectionBottomSheet.IPADDRESS, ipAddress)
            bundle.putInt(AppConnectionBottomSheet.PORT, port)
            bundle.putInt(AppConnectionBottomSheet.IPRULESTATUS, ipRuleStatus.id)
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.dismissListener(null, position)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayIpDetails(customIp: CustomIp) {
            b.aipIpAddress.text = "${customIp.ipAddress}:${customIp.port}"

            when (IpRulesManager.IpRuleStatus.getStatus(customIp.status)) {
                IpRulesManager.IpRuleStatus.NONE -> showNoRuleBtn()
                IpRulesManager.IpRuleStatus.BYPASS_APP_RULES -> showBypassAppRulesBtn()
                IpRulesManager.IpRuleStatus.BLOCK -> showBlockedBtn()
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> { /* no-op */
                }
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

        private fun showBypassAppRulesBtn() {
            b.aipStatusButton.tag = IpRulesManager.IpRuleStatus.BYPASS_APP_RULES.id
            b.aipStatusButton.setBackgroundResource(R.drawable.rectangle_border_background)
            b.aipStatusButton.setTextColor(fetchColor(context, R.attr.primaryTextColor))
            b.aipStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null,
                                                                      ContextCompat.getDrawable(
                                                                          context,
                                                                          R.drawable.ic_arrow_down_small),
                                                                      null)
            b.aipStatusButton.text = context.resources.getString(
                R.string.ada_ip_rules_status_bypass_app_rules)
        }

        private fun showNoRuleBtn() {
            b.aipStatusButton.tag = IpRulesManager.IpRuleStatus.NONE.id
            b.aipStatusButton.setBackgroundResource(R.drawable.rectangle_border_background)
            b.aipStatusButton.setTextColor(fetchColor(context, R.attr.primaryTextColor))
            b.aipStatusButton.setCompoundDrawablesWithIntrinsicBounds(null, null,
                                                                      ContextCompat.getDrawable(
                                                                          context,
                                                                          R.drawable.ic_arrow_down_small),
                                                                      null)
            b.aipStatusButton.text = context.resources.getString(
                R.string.ada_ip_rules_status_allowed)
        }
    }
}
