/*
 * Copyright 2023 RethinkDNS and its authors
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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.databinding.DialogAddCustomIpBinding
import com.celzero.bravedns.databinding.ListItemCustomAllIpBinding
import com.celzero.bravedns.databinding.ListItemCustomIpBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getCountryCode
import com.celzero.bravedns.util.Utilities.getFlag
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomIpAdapter(private val context: Context, private val type: CustomRulesActivity.RULES) :
    PagingDataAdapter<CustomIp, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == R.layout.list_item_custom_all_ip) {
            val itemBinding =
                ListItemCustomAllIpBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            CustomIpsViewHolderWithHeader(itemBinding)
        } else {
            val itemBinding =
                ListItemCustomIpBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            CustomIpsViewHolderWithoutHeader(itemBinding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val customIp: CustomIp = getItem(position) ?: return
        when (holder) {
            is CustomIpAdapter.CustomIpsViewHolderWithHeader -> {
                holder.update(customIp)
            }
            is CustomIpAdapter.CustomIpsViewHolderWithoutHeader -> {
                holder.update(customIp)
            }
            else -> {
                Logger.w(LOG_TAG_UI, "unknown view holder in CustomDomainRulesAdapter")
                return
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        // in case of app specific rules, the header is not required.
        if (type == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
            return R.layout.list_item_custom_ip
        }

        return if (position == 0) {
            R.layout.list_item_custom_all_ip
        } else if (getItem(position - 1)?.uid != getItem(position)?.uid) {
            R.layout.list_item_custom_all_ip
        } else {
            R.layout.list_item_custom_ip
        }
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        Glide.with(context)
            .load(drawable)
            .error(Utilities.getDefaultIcon(context))
            .into(mIconImageView)
    }

    private fun changeIpStatus(id: IpRulesManager.IpRuleStatus, customIp: CustomIp) {
        io {
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
    }

    private fun getToggleBtnUiParams(id: IpRulesManager.IpRuleStatus): ToggleBtnUi {
        return when (id) {
            IpRulesManager.IpRuleStatus.NONE -> {
                ToggleBtnUi(
                    fetchColor(context, R.attr.chipTextNeutral),
                    fetchColor(context, R.attr.chipBgColorNeutral)
                )
            }
            IpRulesManager.IpRuleStatus.BLOCK -> {
                ToggleBtnUi(
                    fetchColor(context, R.attr.chipTextNegative),
                    fetchColor(context, R.attr.chipBgColorNegative)
                )
            }
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                ToggleBtnUi(
                    fetchColor(context, R.attr.chipTextPositive),
                    fetchColor(context, R.attr.chipBgColorPositive)
                )
            }
            IpRulesManager.IpRuleStatus.TRUST -> {
                ToggleBtnUi(
                    fetchColor(context, R.attr.chipTextPositive),
                    fetchColor(context, R.attr.chipBgColorPositive)
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

    private suspend fun byPassUniversal(customIp: CustomIp) {
        IpRulesManager.updateBypass(customIp)
    }

    private suspend fun byPassAppRule(customIp: CustomIp) {
        IpRulesManager.updateTrust(customIp)
    }

    private suspend fun blockIp(customIp: CustomIp) {
        IpRulesManager.updateBlock(customIp)
    }

    private suspend fun noRuleIp(customIp: CustomIp) {
        IpRulesManager.updateNoRule(customIp)
    }

    inner class CustomIpsViewHolderWithHeader(private val b: ListItemCustomAllIpBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customIp: CustomIp

        fun update(ci: CustomIp) {
            io {
                val appNames = FirewallManager.getAppNamesByUid(ci.uid)
                val appName = getAppName(ci.uid, appNames)
                val appInfo = FirewallManager.getAppInfoByUid(ci.uid)
                uiCtx {
                    b.customIpAppName.text = appName
                    displayIcon(
                        Utilities.getIcon(
                            context,
                            appInfo?.packageName ?: "",
                            appInfo?.appName ?: ""
                        ),
                        b.customIpAppIconIv
                    )
                }
            }

            customIp = ci
            b.customIpLabelTv.text =
                context.getString(
                    R.string.ci_ip_label,
                    customIp.ipAddress,
                    customIp.port.toString()
                )
            b.customIpToggleGroup.tag = 1
            val status = findSelectedIpRule(customIp.status) ?: return

            // decide whether to show bypass-universal or bypass-app rule
            showBypassUi(ci.uid)
            // whether to show the toggle group or not
            toggleActionsUi()
            // update toggle group button based on the status
            updateToggleGroup(status)
            // update flag for the available ips
            updateFlagIfAvailable(customIp)
            // update status in desc and status flag (N/B/W)
            updateStatusUi(status)

            b.customIpToggleGroup.addOnButtonCheckedListener(ipRulesGroupListener)

            b.customIpEditIcon.setOnClickListener { showEditIpDialog(customIp) }

            b.customIpExpandIcon.setOnClickListener { toggleActionsUi() }

            b.customIpContainer.setOnClickListener { toggleActionsUi() }
        }

        private fun getAppName(uid: Int, appNames: List<String>): String {
            if (uid == UID_EVERYBODY) {
                return context
                    .getString(R.string.firewall_act_universal_tab)
                    .replaceFirstChar(Char::titlecase)
            }

            if (appNames.isEmpty()) {
                return context.getString(R.string.network_log_app_name_unknown) + " ($uid)"
            }

            val packageCount = appNames.count()
            return if (packageCount >= 2) {
                context.getString(
                    R.string.ctbs_app_other_apps,
                    appNames[0],
                    packageCount.minus(1).toString()
                )
            } else {
                appNames[0]
            }
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
                    // checked change listener is called multiple times, even for position change
                    // so, check if the status has changed or not
                    // also see CustomDomainAdapter#domainRulesGroupListener
                    val hasStatusChanged = customIp.status != statusId.id
                    if (hasStatusChanged) {
                        val t = getToggleBtnUiParams(statusId)
                        // update the toggle button
                        selectToggleBtnUi(b, t)
                        // update the status in desc and status flag (N/B/BU)
                        updateStatusUi(statusId)

                        changeIpStatus(statusId, customIp)
                    } else {
                        // no-op
                    }
                } else {
                    unselectToggleBtnUi(b)
                }
            }

        // each button in the toggle group is associated with tag value.
        // tag values are ids of the IpRulesManager.IpRuleStatus
        private fun getTag(tag: Any): Int {
            return tag.toString().toIntOrNull() ?: 0
        }

        private fun showDialogForDelete(customIp: CustomIp) {
            val builder = MaterialAlertDialogBuilder(context)
            builder.setTitle(R.string.univ_firewall_dialog_title)
            builder.setMessage(R.string.univ_firewall_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.lbl_delete)) { _, _ ->
                io { IpRulesManager.removeIpRule(customIp.uid, customIp.ipAddress, customIp.port) }
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.univ_ip_delete_individual_toast, customIp.ipAddress),
                    Toast.LENGTH_SHORT
                )
            }

            builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
                updateStatusUi(IpRulesManager.IpRuleStatus.getStatus(customIp.status))
            }

            builder.create().show()
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
            val now = System.currentTimeMillis()
            val uptime = System.currentTimeMillis() - customIp.modifiedDateTime
            // returns a string describing 'time' as a time relative to 'now'
            val time =
                DateUtils.getRelativeTimeSpanString(
                    now - uptime,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            when (status) {
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    b.customIpStatusIcon.text =
                        context.getString(R.string.ci_bypass_universal_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_bypass_universal_txt),
                            time
                        )
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_blocked_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.lbl_blocked),
                            time
                        )
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_no_rule_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_no_rule_txt),
                            time
                        )
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_trust_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_trust_txt),
                            time
                        )
                }
            }

            // update the background color and text color of the status icon
            val t = getToggleBtnUiParams(status)
            b.customIpStatusIcon.setTextColor(t.txtColor)
            b.customIpStatusIcon.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        }
    }

    inner class CustomIpsViewHolderWithoutHeader(private val b: ListItemCustomIpBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customIp: CustomIp

        fun update(ci: CustomIp) {
            customIp = ci
            b.customIpLabelTv.text =
                context.getString(
                    R.string.ci_ip_label,
                    customIp.ipAddress,
                    customIp.port.toString()
                )
            b.customIpToggleGroup.tag = 1
            val status = findSelectedIpRule(customIp.status) ?: return

            // decide whether to show bypass-universal or bypass-app rule
            showBypassUi(ci.uid)
            // whether to show the toggle group or not
            toggleActionsUi()
            // update toggle group button based on the status
            updateToggleGroup(status)
            // update flag for the available ips
            updateFlagIfAvailable(customIp)
            // update status in desc and status flag (N/B/W)
            updateStatusUi(status)

            b.customIpToggleGroup.addOnButtonCheckedListener(ipRulesGroupListener)

            b.customIpEditIcon.setOnClickListener { showEditIpDialog(customIp) }

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
                    val hasStatusChanged = customIp.status != statusId.id
                    if (hasStatusChanged) {
                        val t = getToggleBtnUiParams(statusId)
                        // update the toggle button
                        selectToggleBtnUi(b, t)
                        // update the status in desc and status flag (N/B/BU)
                        updateStatusUi(statusId)

                        changeIpStatus(statusId, customIp)
                    } else {
                        // no-op
                    }
                } else {
                    unselectToggleBtnUi(b)
                }
            }

        // each button in the toggle group is associated with tag value.
        // tag values are ids of the IpRulesManager.IpRuleStatus
        private fun getTag(tag: Any): Int {
            return tag.toString().toIntOrNull() ?: 0
        }

        private fun showDialogForDelete(customIp: CustomIp) {
            val builder = MaterialAlertDialogBuilder(context)
            builder.setTitle(R.string.univ_firewall_dialog_title)
            builder.setMessage(R.string.univ_firewall_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.lbl_delete)) { _, _ ->
                io { IpRulesManager.removeIpRule(customIp.uid, customIp.ipAddress, customIp.port) }
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.univ_ip_delete_individual_toast, customIp.ipAddress),
                    Toast.LENGTH_SHORT
                )
            }

            builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
                updateStatusUi(IpRulesManager.IpRuleStatus.getStatus(customIp.status))
            }

            builder.create().show()
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
            val now = System.currentTimeMillis()
            val uptime = System.currentTimeMillis() - customIp.modifiedDateTime
            // returns a string describing 'time' as a time relative to 'now'
            val time =
                DateUtils.getRelativeTimeSpanString(
                    now - uptime,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            when (status) {
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    b.customIpStatusIcon.text =
                        context.getString(R.string.ci_bypass_universal_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_bypass_universal_txt),
                            time
                        )
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_blocked_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.lbl_blocked),
                            time
                        )
                }
                IpRulesManager.IpRuleStatus.NONE -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_no_rule_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_no_rule_txt),
                            time
                        )
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    b.customIpStatusIcon.text = context.getString(R.string.ci_trust_initial)
                    b.customIpStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_trust_txt),
                            time
                        )
                }
            }

            // update the background color and text color of the status icon
            val t = getToggleBtnUiParams(status)
            b.customIpStatusIcon.setTextColor(t.txtColor)
            b.customIpStatusIcon.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        }
    }

    private fun showEditIpDialog(customIp: CustomIp) {
        val dBind =
            DialogAddCustomIpBinding.inflate((context as CustomRulesActivity).layoutInflater)
        val builder = MaterialAlertDialogBuilder(context).setView(dBind.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        dBind.daciIpTitle.text = context.getString(R.string.ci_dialog_title)
        if (customIp.port != 0) {
            val ipNetPort = IpRulesManager.joinIpNetPort(customIp.ipAddress, customIp.port)
            dBind.daciIpEditText.setText(ipNetPort)
        } else {
            dBind.daciIpEditText.setText(customIp.ipAddress)
        }

        if (customIp.uid == UID_EVERYBODY) {
            dBind.daciTrustBtn.text = context.getString(R.string.bypass_universal)
        } else {
            dBind.daciTrustBtn.text = context.getString(R.string.ci_trust_rule)
        }

        dBind.daciIpEditText.addTextChangedListener {
            if (dBind.daciFailureTextView.isVisible) {
                dBind.daciFailureTextView.visibility = View.GONE
            }
        }

        dBind.daciBlockBtn.setOnClickListener {
            handleIp(dBind, customIp, IpRulesManager.IpRuleStatus.BLOCK)
        }

        dBind.daciTrustBtn.setOnClickListener {
            if (customIp.uid == UID_EVERYBODY) {
                handleIp(dBind, customIp, IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL)
            } else {
                handleIp(dBind, customIp, IpRulesManager.IpRuleStatus.TRUST)
            }
        }

        dBind.daciCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleIp(
        dBind: DialogAddCustomIpBinding,
        customIp: CustomIp,
        status: IpRulesManager.IpRuleStatus
    ) {
        ui {
            val input = dBind.daciIpEditText.text.toString()
            val ipString = Utilities.removeLeadingAndTrailingDots(input)
            var ip: IPAddress? = null
            var port: Int? = null

            // chances of creating NetworkOnMainThread exception, handling with io operation
            ioCtx {
                val ipPair = IpRulesManager.getIpNetPort(ipString)
                ip = ipPair.first
                port = ipPair.second
            }

            if (ip == null || ipString.isEmpty()) {
                dBind.daciFailureTextView.text =
                    context.getString(R.string.ci_dialog_error_invalid_ip)
                dBind.daciFailureTextView.visibility = View.VISIBLE
                return@ui
            }

            updateCustomIp(customIp, ip, port, status)
        }
    }

    private fun updateCustomIp(
        prev: CustomIp,
        ipString: IPAddress?,
        port: Int?,
        status: IpRulesManager.IpRuleStatus
    ) {
        if (ipString == null) return // invalid ip (ui error shown already)

        io { IpRulesManager.replaceIpRule(prev, ipString, port, status) }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
