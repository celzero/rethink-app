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
import com.celzero.bravedns.automaton.DomainRulesManager
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.databinding.ListItemCustomDomainBinding
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchToggleBtnColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class CustomDomainAdapter(val context: Context) :
    PagingDataAdapter<CustomDomain, CustomDomainAdapter.CustomDomainViewHolder>(DIFF_CALLBACK) {

    companion object {

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<CustomDomain>() {
                override fun areItemsTheSame(
                    oldConnection: CustomDomain,
                    newConnection: CustomDomain
                ): Boolean {
                    return (oldConnection.domain == newConnection.domain &&
                        oldConnection.status == newConnection.status)
                }

                override fun areContentsTheSame(
                    oldConnection: CustomDomain,
                    newConnection: CustomDomain
                ): Boolean {
                    return (oldConnection.domain == newConnection.domain &&
                        oldConnection.status != newConnection.status)
                }
            }
    }

    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomDomainViewHolder {
        val itemBinding =
            ListItemCustomDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CustomDomainViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: CustomDomainViewHolder, position: Int) {
        val customDomain: CustomDomain = getItem(position) ?: return
        holder.update(customDomain)
    }

    inner class CustomDomainViewHolder(private val b: ListItemCustomDomainBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customDomain: CustomDomain
        fun update(cd: CustomDomain) {
            this.customDomain = cd
            b.customDomainLabelTv.text = customDomain.domain

            // whether to show the toggle group
            toggleActionsUi()
            // update toggle group button based on the status
            updateToggleGroup(customDomain.status)
            // update status in desc and status flag (N/B/W)
            updateStatusUi(DomainRulesManager.DomainStatus.getStatus(customDomain.status))

            b.customDomainToggleGroup.addOnButtonCheckedListener(domainRulesGroupListener)

            b.customDomainExpandIcon.setOnClickListener { toggleActionsUi() }
        }

        private fun updateToggleGroup(id: Int) {
            val fid = findSelectedRuleByTag(id) ?: return

            val t = toggleBtnUi(fid)

            when (id) {
                DomainRulesManager.DomainStatus.NONE.id -> {
                    selectToggleBtnUi(b.customDomainTgNoRule, t)
                    unselectToggleBtnUi(b.customDomainTgBlock)
                    unselectToggleBtnUi(b.customDomainTgWhitelist)
                }
                DomainRulesManager.DomainStatus.BLOCK.id -> {
                    selectToggleBtnUi(b.customDomainTgBlock, t)
                    unselectToggleBtnUi(b.customDomainTgNoRule)
                    unselectToggleBtnUi(b.customDomainTgWhitelist)
                }
                DomainRulesManager.DomainStatus.WHITELIST.id -> {
                    selectToggleBtnUi(b.customDomainTgWhitelist, t)
                    unselectToggleBtnUi(b.customDomainTgBlock)
                    unselectToggleBtnUi(b.customDomainTgNoRule)
                }
            }
        }

        private val domainRulesGroupListener =
            MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
                val b: MaterialButton = b.customDomainToggleGroup.findViewById(checkedId)
                if (isChecked) {
                    val statusId = findSelectedRuleByTag(getTag(b.tag))
                    // delete button
                    if (statusId == null) {
                        showDialogForDelete(customDomain)
                        return@OnButtonCheckedListener
                    }

                    val t = toggleBtnUi(statusId)
                    // update toggle button
                    selectToggleBtnUi(b, t)
                    // update status in desc and status flag (N/B/W)
                    updateStatusUi(statusId)
                    // change status based on selected btn
                    changeDomainStatus(statusId)
                    return@OnButtonCheckedListener
                }

                unselectToggleBtnUi(b)
            }

        private fun changeDomainStatus(id: DomainRulesManager.DomainStatus) {
            when (id) {
                DomainRulesManager.DomainStatus.NONE -> {
                    noRule(customDomain)
                }
                DomainRulesManager.DomainStatus.BLOCK -> {
                    block(customDomain)
                }
                DomainRulesManager.DomainStatus.WHITELIST -> {
                    whitelist(customDomain)
                }
            }
        }

        private fun toggleBtnUi(id: DomainRulesManager.DomainStatus): ToggleBtnUi {
            return when (id) {
                DomainRulesManager.DomainStatus.NONE -> {
                    ToggleBtnUi(
                        fetchToggleBtnColors(context, R.color.firewallNoRuleToggleBtnTxt),
                        fetchToggleBtnColors(context, R.color.firewallNoRuleToggleBtnBg)
                    )
                }
                DomainRulesManager.DomainStatus.BLOCK -> {
                    ToggleBtnUi(
                        fetchToggleBtnColors(context, R.color.firewallBlockToggleBtnTxt),
                        fetchToggleBtnColors(context, R.color.firewallBlockToggleBtnBg)
                    )
                }
                DomainRulesManager.DomainStatus.WHITELIST -> {
                    ToggleBtnUi(
                        fetchToggleBtnColors(context, R.color.firewallWhiteListToggleBtnTxt),
                        fetchToggleBtnColors(context, R.color.firewallWhiteListToggleBtnBg)
                    )
                }
            }
        }

        private fun selectToggleBtnUi(b: MaterialButton, toggleBtnUi: ToggleBtnUi) {
            b.setTextColor(toggleBtnUi.txtColor)
            b.backgroundTintList = ColorStateList.valueOf(toggleBtnUi.bgColor)
        }

        private fun unselectToggleBtnUi(b: MaterialButton) {
            b.setTextColor(fetchToggleBtnColors(context, R.color.defaultToggleBtnTxt))
            b.backgroundTintList =
                ColorStateList.valueOf(fetchToggleBtnColors(context, R.color.defaultToggleBtnBg))
        }

        // each button in the toggle group is associated with tag value.
        // tag values are ids of DomainRulesManager.DomainStatus
        private fun getTag(tag: Any): Int {
            return tag.toString().toIntOrNull() ?: 0
        }

        private fun findSelectedRuleByTag(ruleId: Int): DomainRulesManager.DomainStatus? {
            return when (ruleId) {
                DomainRulesManager.DomainStatus.NONE.id -> {
                    DomainRulesManager.DomainStatus.NONE
                }
                DomainRulesManager.DomainStatus.WHITELIST.id -> {
                    DomainRulesManager.DomainStatus.WHITELIST
                }
                DomainRulesManager.DomainStatus.BLOCK.id -> {
                    DomainRulesManager.DomainStatus.BLOCK
                }
                else -> {
                    null
                }
            }
        }

        private fun toggleActionsUi() {
            if (b.customDomainToggleGroup.tag == 0) {
                b.customDomainToggleGroup.tag = 1
                b.customDomainToggleGroup.visibility = View.VISIBLE
                return
            }

            b.customDomainToggleGroup.tag = 0
            b.customDomainToggleGroup.visibility = View.GONE
        }

        private fun updateStatusUi(status: DomainRulesManager.DomainStatus) {
            when (status) {
                DomainRulesManager.DomainStatus.WHITELIST -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.cd_whitelist_initial)
                    b.customDomainStatusTv.text = context.getString(R.string.cd_whitelist_txt)
                }
                DomainRulesManager.DomainStatus.BLOCK -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.cd_blocked_initial)
                    b.customDomainStatusTv.text = context.getString(R.string.cd_blocked_txt)
                }
                DomainRulesManager.DomainStatus.NONE -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.cd_no_rule_initial)
                    b.customDomainStatusTv.text = context.getString(R.string.cd_no_rule_txt)
                }
            }
        }

        private fun whitelist(cd: CustomDomain) {
            DomainRulesManager.whitelist(cd)
        }

        private fun block(cd: CustomDomain) {
            DomainRulesManager.block(cd)
        }

        private fun noRule(cd: CustomDomain) {
            DomainRulesManager.noRule(cd)
        }

        private fun showDialogForDelete(customDomain: CustomDomain) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.cd_remove_dialog_title)
            builder.setMessage(R.string.cd_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.cd_remove_dialog_positive)) { _, _
                ->
                DomainRulesManager.deleteDomain(customDomain)
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.cd_toast_deleted),
                    Toast.LENGTH_SHORT
                )
            }

            builder.setNegativeButton(context.getString(R.string.cd_remove_dialog_negative)) { _, _
                ->
                // no-op
            }
            builder.create().show()
        }
    }
}
