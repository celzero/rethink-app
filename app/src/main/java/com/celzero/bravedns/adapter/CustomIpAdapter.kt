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
import android.content.res.TypedArray
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.automaton.IpRulesManager.UID_EVERYBODY
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.databinding.ListItemCustomIpBinding
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.net.InetAddress

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

    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

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

        private lateinit var customIp: CustomIp
        fun update(ci: CustomIp) {
            customIp = ci
            b.customIpsLabelTv.text = customIp.ipAddress
            b.customIpToggleGroup.tag = 1
            val status = IpRulesManager.IpRuleStatus.getStatus(customIp.status)

            // whether to show the toggle group
            toggleActionsUi()
            // update the toggle group button based on the status
            updateToggleGroup(status.id)
            // update flag for the available ips
            updateFlagIfAvailable(customIp)
            // update the status in desc and status flag (N/B/W)
            updateStatusUi(status)

            b.customIpToggleGroup.addOnButtonCheckedListener(ipRulesGroupListener)

            b.customIpsExpandIcon.setOnClickListener {
                toggleActionsUi()
            }

        }

        private fun updateToggleGroup(id: Int) {
            val fid = findSelectedIpRule(id) ?: return

            val t = toggleBtnUi(fid)

            when (id) {
                IpRulesManager.IpRuleStatus.NONE.id -> {
                    selectToggleBtnUi(b.customIpTgNoRule, t)
                    unselectToggleBtnUi(b.customIpTgBlock)
                    unselectToggleBtnUi(b.customIpTgWhitelist)
                }
                IpRulesManager.IpRuleStatus.BLOCK.id -> {
                    selectToggleBtnUi(b.customIpTgBlock, t)
                    unselectToggleBtnUi(b.customIpTgNoRule)
                    unselectToggleBtnUi(b.customIpTgWhitelist)
                }
                IpRulesManager.IpRuleStatus.WHITELIST.id -> {
                    selectToggleBtnUi(b.customIpTgWhitelist, t)
                    unselectToggleBtnUi(b.customIpTgBlock)
                    unselectToggleBtnUi(b.customIpTgNoRule)
                }
            }
        }

        private val ipRulesGroupListener = MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            val b: MaterialButton = b.customIpToggleGroup.findViewById(checkedId)
            if (isChecked) {
                val statusId = findSelectedIpRule(getTag(b.tag))
                // delete button
                if (statusId == null) {
                    showDialogForDelete(customIp)
                    return@OnButtonCheckedListener
                }

                val t = toggleBtnUi(statusId)
                // update the toggle button
                selectToggleBtnUi(b, t)
                // update the status in desc and status flag (N/B/W)
                updateStatusUi(statusId)

                updateIpStatus(statusId)
                return@OnButtonCheckedListener
            }

            unselectToggleBtnUi(b)
        }

        private fun updateIpStatus(id: IpRulesManager.IpRuleStatus) {
            when (id) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    noRuleIp(customIp)
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    blockIp(customIp)
                }
                IpRulesManager.IpRuleStatus.WHITELIST -> {
                    whitelistIp(customIp)
                }
            }
        }

        private fun toggleBtnUi(id: IpRulesManager.IpRuleStatus): ToggleBtnUi {
            return when (id) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    ToggleBtnUi(
                        fetchTextColor(R.color.firewallNoRuleToggleBtnTxt),
                        fetchTextColor(R.color.firewallNoRuleToggleBtnBg))
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    ToggleBtnUi(
                        fetchTextColor(R.color.firewallBlockToggleBtnTxt),
                        fetchTextColor(R.color.firewallBlockToggleBtnBg))
                }
                IpRulesManager.IpRuleStatus.WHITELIST -> {
                    ToggleBtnUi(
                        fetchTextColor(R.color.firewallWhiteListToggleBtnTxt),
                        fetchTextColor(R.color.firewallWhiteListToggleBtnBg))
                }
            }
        }

        private fun selectToggleBtnUi(b: MaterialButton, toggleBtnUi: ToggleBtnUi) {
            b.setTextColor(toggleBtnUi.txtColor)
            b.backgroundTintList = ColorStateList.valueOf(toggleBtnUi.bgColor)
        }

        private fun unselectToggleBtnUi(b: MaterialButton) {
            b.setTextColor(fetchTextColor(R.color.defaultToggleBtnTxt))
            b.backgroundTintList = ColorStateList.valueOf(
                fetchTextColor(R.color.defaultToggleBtnBg))
        }

        private fun fetchTextColor(attr: Int): Int {
            val attributeFetch = if (attr == R.color.firewallNoRuleToggleBtnTxt) {
                R.attr.firewallNoRuleToggleBtnTxt
            } else if (attr == R.color.firewallNoRuleToggleBtnBg) {
                R.attr.firewallNoRuleToggleBtnBg
            } else if (attr == R.color.firewallBlockToggleBtnTxt) {
                R.attr.firewallBlockToggleBtnTxt
            } else if (attr == R.color.firewallBlockToggleBtnBg) {
                R.attr.firewallBlockToggleBtnBg
            } else if (attr == R.color.firewallWhiteListToggleBtnTxt) {
                R.attr.firewallWhiteListToggleBtnTxt
            } else if (attr == R.color.firewallWhiteListToggleBtnBg) {
                R.attr.firewallWhiteListToggleBtnBg
            } else if (attr == R.color.firewallExcludeToggleBtnBg) {
                R.attr.firewallExcludeToggleBtnBg
            } else if (attr == R.color.firewallExcludeToggleBtnTxt) {
                R.attr.firewallExcludeToggleBtnTxt
            } else if (attr == R.color.defaultToggleBtnBg) {
                R.attr.defaultToggleBtnBg
            } else if (attr == R.color.defaultToggleBtnTxt) {
                R.attr.defaultToggleBtnTxt
            } else {
                R.attr.chipBgColorPositive
            }
            val typedValue = TypedValue()
            val a: TypedArray = context.obtainStyledAttributes(typedValue.data,
                                                                        intArrayOf(attributeFetch))
            val color = a.getColor(0, 0)
            a.recycle()
            return color
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
                IpRulesManager.IpRuleStatus.WHITELIST.id -> {
                    IpRulesManager.IpRuleStatus.WHITELIST
                }
                IpRulesManager.IpRuleStatus.BLOCK.id -> {
                    IpRulesManager.IpRuleStatus.BLOCK
                }
                else -> {
                    null
                }
            }
        }

        private fun updateFlagIfAvailable(ip: CustomIp) {
            if (ip.wildcard) return

            b.customIpsFlag.text = getFlag(getCountryCode(InetAddress.getByName(ip.ipAddress), context))
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
                IpRulesManager.IpRuleStatus.WHITELIST -> {
                    b.customIpsStatusIcon.text = "W"
                    b.customIpsStatusTv.text = "Whitelisted"
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    b.customIpsStatusIcon.text = "B"
                    b.customIpsStatusTv.text = "Blocked"
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    b.customIpsStatusIcon.text = "N"
                    b.customIpsStatusTv.text = "No Rule"
                }
            }
        }

        private fun whitelistIp(customIp: CustomIp) {
            // TODO: Implement allow ip
            IpRulesManager.whitelistIp(customIp)
        }

        private fun blockIp(customIp: CustomIp) {
            // TODO: Implement block ip
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
                context.getString(R.string.univ_ip_delete_individual_positive)) { _, _ ->
                IpRulesManager.removeFirewallRules(UID_EVERYBODY, customIp.ipAddress)
                Toast.makeText(context, context.getString(R.string.univ_ip_delete_individual_toast,
                                                          customIp.ipAddress),
                               Toast.LENGTH_SHORT).show()
            }

            builder.setNegativeButton(
                context.getString(R.string.univ_ip_delete_individual_negative)) { _, _ -> }

            builder.create().show()
        }
    }

}
