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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.databinding.ListItemCustomIpBinding
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import inet.ipaddr.IPAddressString

class CustomIpAdapter(private val context: Context) :
    PagingDataAdapter<CustomIp, CustomIpAdapter.CustomIpsViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<CustomIp>() {

                override fun areItemsTheSame(oldConnection: CustomIp, newConnection: CustomIp) =
                    oldConnection.ipAddress == newConnection.ipAddress &&
                        oldConnection.status == newConnection.status

                override fun areContentsTheSame(oldConnection: CustomIp, newConnection: CustomIp) =
                    oldConnection.ipAddress == newConnection.ipAddress &&
                        oldConnection.status != newConnection.status
            }
    }

    // ui component to update/toggle the buttons
    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomIpsViewHolder {
        val itemBinding =
            ListItemCustomIpBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CustomIpsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: CustomIpsViewHolder, position: Int) {
        val customIp: CustomIp = getItem(position) ?: return
        holder.update(customIp)
    }

    inner class CustomIpsViewHolder(private val b: ListItemCustomIpBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customIp: CustomIp
        fun update(ci: CustomIp) {
            customIp = ci
            b.customIpLabelTv.text = "${customIp.ipAddress}:${customIp.port}"
            b.customIpToggleGroup.tag = 1
            val status = findSelectedIpRule(customIp.status) ?: return

            // decide whether to show bypass-universal  or bypass-app rule
            showBypassUi(ci.uid)
            // whether to show the toggle group
            toggleActionsUi()
            // update toggle group button based on the status
            updateToggleGroup(status)
            // update flag for the available ips
            updateFlagIfAvailable(customIp)
            // update status in desc and status flag (N/B/W)
            updateStatusUi(status)

            b.customIpToggleGroup.addOnButtonCheckedListener(ipRulesGroupListener)

            b.customIpExpandIcon.setOnClickListener { toggleActionsUi() }

            b.customIpContainer.setOnClickListener { toggleActionsUi() }
        }

        private fun showBypassUi(uid: Int) {
            if (uid == UID_EVERYBODY) {
                b.customIpTgBypassUniv.visibility = View.VISIBLE
                b.customIpTgBypassApp.visibility = View.GONE
            } else {
                b.customIpTgBypassUniv.visibility = View.GONE
                b.customIpTgBypassApp.visibility = View.VISIBLE
            }
        }

        private fun updateToggleGroup(id: IpRulesManager.IpRuleStatus) {
            val t = getToggleBtnUiParams(id)

            when (id) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    b.customIpToggleGroup.check(b.customIpTgNoRule.id)
                    selectToggleBtnUi(b.customIpTgNoRule, t)
                    unselectToggleBtnUi(b.customIpTgBlock)
                    unselectToggleBtnUi(b.customIpTgBypassUniv)
                    unselectToggleBtnUi(b.customIpTgBypassApp)
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    b.customIpToggleGroup.check(b.customIpTgBlock.id)
                    selectToggleBtnUi(b.customIpTgBlock, t)
                    unselectToggleBtnUi(b.customIpTgNoRule)
                    unselectToggleBtnUi(b.customIpTgBypassUniv)
                    unselectToggleBtnUi(b.customIpTgBypassApp)
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    b.customIpToggleGroup.check(b.customIpTgBypassUniv.id)
                    selectToggleBtnUi(b.customIpTgBypassUniv, t)
                    unselectToggleBtnUi(b.customIpTgBlock)
                    unselectToggleBtnUi(b.customIpTgNoRule)
                    unselectToggleBtnUi(b.customIpTgBypassApp)
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    b.customIpToggleGroup.check(b.customIpTgBypassApp.id)
                    selectToggleBtnUi(b.customIpTgBypassApp, t)
                    unselectToggleBtnUi(b.customIpTgBlock)
                    unselectToggleBtnUi(b.customIpTgNoRule)
                    unselectToggleBtnUi(b.customIpTgBypassUniv)
                }
            }
        }

        private val ipRulesGroupListener =
            MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
                val b: MaterialButton = b.customIpToggleGroup.findViewById(checkedId)

                val statusId = findSelectedIpRule(getTag(b.tag))
                // delete button
                if (statusId == null && isChecked) {
                    group.clearChecked()
                    showDialogForDelete(customIp)
                    return@OnButtonCheckedListener
                }

                // invalid selection
                if (statusId == null) {
                    return@OnButtonCheckedListener
                }

                if (isChecked) {
                    val t = getToggleBtnUiParams(statusId)
                    // update the toggle button
                    selectToggleBtnUi(b, t)
                    // update the status in desc and status flag (N/B/BU)
                    updateStatusUi(statusId)

                    changeIpStatus(statusId)
                    return@OnButtonCheckedListener
                }

                unselectToggleBtnUi(b)
            }

        private fun changeIpStatus(id: IpRulesManager.IpRuleStatus) {
            when (id) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    noRuleIp(customIp)
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    blockIp(customIp)
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    byPassUniversal(customIp)
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    byPassAppRule(customIp)
                }
            }
        }

        private fun getToggleBtnUiParams(id: IpRulesManager.IpRuleStatus): ToggleBtnUi {
            return when (id) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    ToggleBtnUi(
                        Utilities.fetchColor(context, R.attr.chipTextNeutral),
                        Utilities.fetchColor(context, R.attr.chipBgColorNeutral)
                    )
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    ToggleBtnUi(
                        Utilities.fetchColor(context, R.attr.chipTextNegative),
                        Utilities.fetchColor(context, R.attr.chipBgColorNegative)
                    )
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    ToggleBtnUi(
                        Utilities.fetchColor(context, R.attr.chipTextPositive),
                        Utilities.fetchColor(context, R.attr.chipBgColorPositive)
                    )
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    ToggleBtnUi(
                        Utilities.fetchColor(context, R.attr.chipTextPositive),
                        Utilities.fetchColor(context, R.attr.chipBgColorPositive)
                    )
                }
            }
        }

        private fun selectToggleBtnUi(btn: MaterialButton, toggleBtnUi: ToggleBtnUi) {
            btn.setTextColor(toggleBtnUi.txtColor)
            btn.backgroundTintList = ColorStateList.valueOf(toggleBtnUi.bgColor)
        }

        private fun unselectToggleBtnUi(btn: MaterialButton) {
            btn.setTextColor(fetchToggleBtnColors(context, R.color.defaultToggleBtnTxt))
            btn.backgroundTintList =
                ColorStateList.valueOf(fetchToggleBtnColors(context, R.color.defaultToggleBtnBg))
        }

        // each button in the toggle group is associated with tag value.
        // tag values are ids of the IpRulesManager.IpRuleStatus
        private fun getTag(tag: Any): Int {
            return tag.toString().toIntOrNull() ?: 0
        }

        private fun findSelectedIpRule(ruleId: Int): IpRulesManager.IpRuleStatus? {
            return when (ruleId) {
                IpRulesManager.IpRuleStatus.NONE.id -> {
                    IpRulesManager.IpRuleStatus.NONE
                }
                IpRulesManager.IpRuleStatus.BLOCK.id -> {
                    IpRulesManager.IpRuleStatus.BLOCK
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL.id -> {
                    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
                }
                IpRulesManager.IpRuleStatus.TRUST.id -> {
                    IpRulesManager.IpRuleStatus.TRUST
                }
                else -> {
                    null
                }
            }
        }

        private fun updateFlagIfAvailable(ip: CustomIp) {
            if (ip.wildcard) return

            b.customIpFlag.text =
                getFlag(
                    getCountryCode(
                        IPAddressString(ip.ipAddress).hostAddress.toInetAddress(),
                        context
                    )
                )
        }

        private fun toggleActionsUi() {
            if (b.customIpToggleGroup.tag == 0) {
                b.customIpToggleGroup.tag = 1
                b.customIpToggleGroup.visibility = View.VISIBLE
                return
            }

            b.customIpToggleGroup.tag = 0
            b.customIpToggleGroup.visibility = View.GONE
        }

        private fun updateStatusUi(status: IpRulesManager.IpRuleStatus) {
            when (status) {
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    b.customIpStatusIcon.text =
                        context.getString(R.string.ci_bypass_universal_initial)
                    b.customIpStatusTv.text = context.getString(R.string.ci_bypass_universal_txt)
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_blocked_initial)
                    b.customIpStatusTv.text = context.getString(R.string.ci_blocked_txt)
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_no_rule_initial)
                    b.customIpStatusTv.text = context.getString(R.string.ci_no_rule_txt)
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_trust_initial)
                    b.customIpStatusTv.text = context.getString(R.string.ci_trust_txt)
                }
            }

            // update the background color and text color of the status icon
            val t = getToggleBtnUiParams(status)
            b.customIpStatusIcon.setTextColor(t.txtColor)
            b.customIpStatusIcon.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        }

        private fun byPassUniversal(customIp: CustomIp) {
            IpRulesManager.byPassUniversal(customIp)
        }

        private fun byPassAppRule(customIp: CustomIp) {
            IpRulesManager.trustIpRules(customIp)
        }

        private fun blockIp(customIp: CustomIp) {
            IpRulesManager.blockIp(customIp)
        }

        private fun noRuleIp(customIp: CustomIp) {
            IpRulesManager.noRuleIp(customIp)
        }

        private fun showDialogForDelete(customIp: CustomIp) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.univ_firewall_dialog_title)
            builder.setMessage(R.string.univ_firewall_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(
                context.getString(R.string.univ_ip_delete_individual_positive)
            ) { _, _ ->
                IpRulesManager.removeFirewallRules(customIp.uid, customIp.ipAddress, customIp.port)
                Toast.makeText(
                        context,
                        context.getString(
                            R.string.univ_ip_delete_individual_toast,
                            customIp.ipAddress
                        ),
                        Toast.LENGTH_SHORT
                    )
                    .show()
            }

            builder.setNegativeButton(
                context.getString(R.string.univ_ip_delete_individual_negative)
            ) { _, _ ->
                updateStatusUi(IpRulesManager.IpRuleStatus.getStatus(customIp.status))
            }

            builder.create().show()
        }
    }
}
