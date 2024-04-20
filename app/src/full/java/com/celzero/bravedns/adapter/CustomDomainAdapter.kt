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
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.databinding.DialogAddCustomDomainBinding
import com.celzero.bravedns.databinding.ListItemCustomAllDomainBinding
import com.celzero.bravedns.databinding.ListItemCustomDomainBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.DomainRulesManager.isValidDomain
import com.celzero.bravedns.service.DomainRulesManager.isWildCardEntry
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomDomainAdapter(val context: Context, val rule: CustomRulesActivity.RULES) :
    PagingDataAdapter<CustomDomain, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == R.layout.list_item_custom_all_domain) {
            val itemBinding =
                ListItemCustomAllDomainBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            return CustomDomainViewHolderWithHeader(itemBinding)
        } else {
            val itemBinding =
                ListItemCustomDomainBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            return CustomDomainViewHolderWithoutHeader(itemBinding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val customDomain: CustomDomain = getItem(position) ?: return
        if (holder is CustomDomainViewHolderWithHeader) {
            holder.update(customDomain)
        } else if (holder is CustomDomainViewHolderWithoutHeader) {
            holder.update(customDomain)
        } else {
            Logger.w(LOG_TAG_UI, "unknown view holder in CustomDomainRulesAdapter")
            return
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (rule == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
            return R.layout.list_item_custom_domain
        }

        return if (position == 0) {
            R.layout.list_item_custom_all_domain
        } else if (getItem(position - 1)?.uid != getItem(position)?.uid) {
            R.layout.list_item_custom_all_domain
        } else {
            R.layout.list_item_custom_domain
        }
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        Glide.with(context)
            .load(drawable)
            .error(Utilities.getDefaultIcon(context))
            .into(mIconImageView)
    }

    private fun changeDomainStatus(id: DomainRulesManager.Status, cd: CustomDomain) {
        io {
            when (id) {
                DomainRulesManager.Status.NONE -> {
                    noRule(cd)
                }
                DomainRulesManager.Status.BLOCK -> {
                    block(cd)
                }
                DomainRulesManager.Status.TRUST -> {
                    whitelist(cd)
                }
            }
        }
    }

    private suspend fun whitelist(cd: CustomDomain) {
        DomainRulesManager.trust(cd)
    }

    private suspend fun block(cd: CustomDomain) {
        DomainRulesManager.block(cd)
    }

    private suspend fun noRule(cd: CustomDomain) {
        DomainRulesManager.noRule(cd)
    }

    private fun findSelectedRuleByTag(ruleId: Int): DomainRulesManager.Status? {
        return when (ruleId) {
            DomainRulesManager.Status.NONE.id -> {
                DomainRulesManager.Status.NONE
            }
            DomainRulesManager.Status.TRUST.id -> {
                DomainRulesManager.Status.TRUST
            }
            DomainRulesManager.Status.BLOCK.id -> {
                DomainRulesManager.Status.BLOCK
            }
            else -> {
                null
            }
        }
    }

    private fun toggleBtnUi(id: DomainRulesManager.Status): ToggleBtnUi {
        return when (id) {
            DomainRulesManager.Status.NONE -> {
                ToggleBtnUi(
                    fetchColor(context, R.attr.chipTextNeutral),
                    fetchColor(context, R.attr.chipBgColorNeutral)
                )
            }
            DomainRulesManager.Status.BLOCK -> {
                ToggleBtnUi(
                    fetchColor(context, R.attr.chipTextNegative),
                    fetchColor(context, R.attr.chipBgColorNegative)
                )
            }
            DomainRulesManager.Status.TRUST -> {
                ToggleBtnUi(
                    fetchColor(context, R.attr.chipTextPositive),
                    fetchColor(context, R.attr.chipBgColorPositive)
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

    private fun showDialogForDelete(customDomain: CustomDomain) {
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.cd_remove_dialog_title)
        builder.setMessage(R.string.cd_remove_dialog_message)
        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.lbl_delete)) { _, _ ->
            io { DomainRulesManager.deleteDomain(customDomain) }
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.cd_toast_deleted),
                Toast.LENGTH_SHORT
            )
        }

        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun showEditDomainDialog(customDomain: CustomDomain) {
        val dBind =
            DialogAddCustomDomainBinding.inflate((context as CustomRulesActivity).layoutInflater)
        val builder = MaterialAlertDialogBuilder(context).setView(dBind.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        var selectedType: DomainRulesManager.DomainType =
            DomainRulesManager.DomainType.getType(customDomain.type)

        when (selectedType) {
            DomainRulesManager.DomainType.DOMAIN -> {
                dBind.dacdDomainChip.isChecked = true
            }
            DomainRulesManager.DomainType.WILDCARD -> {
                dBind.dacdWildcardChip.isChecked = true
            }
        }

        dBind.dacdDomainEditText.setText(customDomain.domain)

        dBind.dacdDomainEditText.addTextChangedListener {
            if (it?.contains("*") == true) {
                dBind.dacdWildcardChip.isChecked = true
            }
        }

        dBind.dacdDomainChip.setOnCheckedChangeListener { _, isSelected ->
            if (isSelected) {
                selectedType = DomainRulesManager.DomainType.DOMAIN
                dBind.dacdDomainEditText.hint =
                    context.getString(
                        R.string.cd_dialog_edittext_hint,
                        context.getString(R.string.lbl_domain)
                    )
                dBind.dacdTextInputLayout.hint =
                    context.getString(
                        R.string.cd_dialog_edittext_hint,
                        context.getString(R.string.lbl_domain)
                    )
            }
        }

        dBind.dacdWildcardChip.setOnCheckedChangeListener { _, isSelected ->
            if (isSelected) {
                selectedType = DomainRulesManager.DomainType.WILDCARD
                dBind.dacdDomainEditText.hint =
                    context.getString(
                        R.string.cd_dialog_edittext_hint,
                        context.getString(R.string.lbl_wildcard)
                    )
                dBind.dacdTextInputLayout.hint =
                    context.getString(
                        R.string.cd_dialog_edittext_hint,
                        context.getString(R.string.lbl_wildcard)
                    )
            }
        }

        dBind.dacdUrlTitle.text = context.getString(R.string.cd_dialog_title)
        dBind.dacdDomainEditText.hint =
            context.getString(
                R.string.cd_dialog_edittext_hint,
                context.getString(R.string.lbl_domain)
            )
        dBind.dacdTextInputLayout.hint =
            context.getString(
                R.string.cd_dialog_edittext_hint,
                context.getString(R.string.lbl_domain)
            )

        dBind.dacdBlockBtn.setOnClickListener {
            handleDomain(dBind, selectedType, customDomain, DomainRulesManager.Status.BLOCK)
        }

        dBind.dacdTrustBtn.setOnClickListener {
            handleDomain(dBind, selectedType, customDomain, DomainRulesManager.Status.TRUST)
        }

        dBind.dacdCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleDomain(
        dBind: DialogAddCustomDomainBinding,
        selectedType: DomainRulesManager.DomainType,
        prevDomain: CustomDomain,
        status: DomainRulesManager.Status
    ) {
        dBind.dacdFailureText.visibility = View.GONE
        val url = dBind.dacdDomainEditText.text.toString().trim()
        when (selectedType) {
            DomainRulesManager.DomainType.WILDCARD -> {
                if (!isWildCardEntry(url)) {
                    dBind.dacdFailureText.text =
                        context.getString(R.string.cd_dialog_error_invalid_wildcard)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    return
                }
            }
            DomainRulesManager.DomainType.DOMAIN -> {
                if (!isValidDomain(url)) {
                    dBind.dacdFailureText.text =
                        context.getString(R.string.cd_dialog_error_invalid_domain)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    return
                }
            }
        }

        io {
            insertDomain(
                Utilities.removeLeadingAndTrailingDots(url),
                selectedType,
                prevDomain,
                status
            )
        }
    }

    private suspend fun insertDomain(
        domain: String,
        type: DomainRulesManager.DomainType,
        prevDomain: CustomDomain,
        status: DomainRulesManager.Status
    ) {
        DomainRulesManager.updateDomainRule(domain, status, type, prevDomain)
        uiCtx {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.cd_toast_edit),
                Toast.LENGTH_SHORT
            )
        }
    }

    inner class CustomDomainViewHolderWithHeader(private val b: ListItemCustomAllDomainBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customDomain: CustomDomain

        fun update(cd: CustomDomain) {
            io {
                val appInfo = FirewallManager.getAppInfoByUid(cd.uid)
                val appNames = FirewallManager.getAppNamesByUid(cd.uid)
                uiCtx {
                    val appName = getAppName(cd.uid, appNames)

                    b.customDomainAppName.text = appName
                    displayIcon(
                        Utilities.getIcon(
                            context,
                            appInfo?.packageName ?: "",
                            appInfo?.appName ?: ""
                        ),
                        b.customDomainAppIconIv
                    )

                    this.customDomain = cd
                    b.customDomainLabelTv.text = customDomain.domain
                    b.customDomainToggleGroup.tag = 1

                    // update toggle group button based on the status
                    updateToggleGroup(customDomain.status)
                    // whether to show the toggle group or not
                    toggleActionsUi()
                    // update status in desc and status flag (N/B/W)
                    updateStatusUi(
                        DomainRulesManager.Status.getStatus(customDomain.status),
                        customDomain.modifiedTs
                    )

                    b.customDomainToggleGroup.addOnButtonCheckedListener(domainRulesGroupListener)

                    b.customDomainEditIcon.setOnClickListener { showEditDomainDialog(customDomain) }

                    b.customDomainExpandIcon.setOnClickListener { toggleActionsUi() }

                    b.customDomainContainer.setOnClickListener { toggleActionsUi() }
                }
            }
        }

        private fun getAppName(uid: Int, appNames: List<String>): String {
            if (uid == Constants.UID_EVERYBODY) {
                return context
                    .getString(R.string.firewall_act_universal_tab)
                    .replaceFirstChar(Char::titlecase)
            }

            if (appNames.isEmpty()) {
                return context.getString(R.string.network_log_app_name_unnamed, "($uid)")
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

        private fun updateToggleGroup(id: Int) {
            val fid = findSelectedRuleByTag(id) ?: return

            val t = toggleBtnUi(fid)

            when (id) {
                DomainRulesManager.Status.NONE.id -> {
                    b.customDomainToggleGroup.check(b.customDomainTgNoRule.id)
                    selectToggleBtnUi(b.customDomainTgNoRule, t)
                    unselectToggleBtnUi(b.customDomainTgBlock)
                    unselectToggleBtnUi(b.customDomainTgWhitelist)
                }
                DomainRulesManager.Status.BLOCK.id -> {
                    b.customDomainToggleGroup.check(b.customDomainTgBlock.id)
                    selectToggleBtnUi(b.customDomainTgBlock, t)
                    unselectToggleBtnUi(b.customDomainTgNoRule)
                    unselectToggleBtnUi(b.customDomainTgWhitelist)
                }
                DomainRulesManager.Status.TRUST.id -> {
                    b.customDomainToggleGroup.check(b.customDomainTgWhitelist.id)
                    selectToggleBtnUi(b.customDomainTgWhitelist, t)
                    unselectToggleBtnUi(b.customDomainTgBlock)
                    unselectToggleBtnUi(b.customDomainTgNoRule)
                }
            }
        }

        private val domainRulesGroupListener =
            MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
                val b: MaterialButton = b.customDomainToggleGroup.findViewById(checkedId)

                val statusId = findSelectedRuleByTag(getTag(b.tag))
                // delete button
                if (statusId == null && isChecked) {
                    group.clearChecked()
                    showDialogForDelete(customDomain)
                    return@OnButtonCheckedListener
                }

                // invalid selection
                if (statusId == null) {
                    return@OnButtonCheckedListener
                }

                if (isChecked) {
                    // See CustomIpAdapter.kt for the same code (ipRulesGroupListener)
                    val hasStatusChanged = customDomain.status != statusId.id
                    if (!hasStatusChanged) {
                        return@OnButtonCheckedListener
                    }
                    val t = toggleBtnUi(statusId)
                    // update toggle button
                    selectToggleBtnUi(b, t)
                    // update status in desc and status flag (N/B/W)
                    updateStatusUi(statusId, customDomain.modifiedTs)
                    // change status based on selected btn
                    changeDomainStatus(statusId, customDomain)
                } else {
                    unselectToggleBtnUi(b)
                }
            }

        // each button in the toggle group is associated with tag value.
        // tag values are ids of DomainRulesManager.DomainStatus
        private fun getTag(tag: Any): Int {
            return tag.toString().toIntOrNull() ?: 0
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

        private fun updateStatusUi(status: DomainRulesManager.Status, modifiedTs: Long) {
            val now = System.currentTimeMillis()
            val uptime = System.currentTimeMillis() - modifiedTs
            // returns a string describing 'time' as a time relative to 'now'
            val time =
                DateUtils.getRelativeTimeSpanString(
                    now - uptime,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            when (status) {
                DomainRulesManager.Status.TRUST -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.ci_trust_initial)
                    b.customDomainStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_trust_txt),
                            time
                        )
                }
                DomainRulesManager.Status.BLOCK -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.cd_blocked_initial)
                    b.customDomainStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.lbl_blocked),
                            time
                        )
                }
                DomainRulesManager.Status.NONE -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.cd_no_rule_initial)
                    b.customDomainStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.cd_no_rule_txt),
                            time
                        )
                }
            }

            // update the background color and text color of the status icon
            val t = toggleBtnUi(status)
            b.customDomainStatusIcon.setTextColor(t.txtColor)
            b.customDomainStatusIcon.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        }
    }

    inner class CustomDomainViewHolderWithoutHeader(private val b: ListItemCustomDomainBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customDomain: CustomDomain

        fun update(cd: CustomDomain) {
            this.customDomain = cd
            b.customDomainLabelTv.text = customDomain.domain
            b.customDomainToggleGroup.tag = 1

            // update toggle group button based on the status
            updateToggleGroup(customDomain.status)
            // whether to show the toggle group or not
            toggleActionsUi()
            // update status in desc and status flag (N/B/W)
            updateStatusUi(
                DomainRulesManager.Status.getStatus(customDomain.status),
                customDomain.modifiedTs
            )

            b.customDomainToggleGroup.addOnButtonCheckedListener(domainRulesGroupListener)

            b.customDomainEditIcon.setOnClickListener { showEditDomainDialog(customDomain) }

            b.customDomainExpandIcon.setOnClickListener { toggleActionsUi() }

            b.customDomainContainer.setOnClickListener { toggleActionsUi() }
        }

        private fun updateToggleGroup(id: Int) {
            val fid = findSelectedRuleByTag(id) ?: return

            val t = toggleBtnUi(fid)

            when (id) {
                DomainRulesManager.Status.NONE.id -> {
                    b.customDomainToggleGroup.check(b.customDomainTgNoRule.id)
                    selectToggleBtnUi(b.customDomainTgNoRule, t)
                    unselectToggleBtnUi(b.customDomainTgBlock)
                    unselectToggleBtnUi(b.customDomainTgWhitelist)
                }
                DomainRulesManager.Status.BLOCK.id -> {
                    b.customDomainToggleGroup.check(b.customDomainTgBlock.id)
                    selectToggleBtnUi(b.customDomainTgBlock, t)
                    unselectToggleBtnUi(b.customDomainTgNoRule)
                    unselectToggleBtnUi(b.customDomainTgWhitelist)
                }
                DomainRulesManager.Status.TRUST.id -> {
                    b.customDomainToggleGroup.check(b.customDomainTgWhitelist.id)
                    selectToggleBtnUi(b.customDomainTgWhitelist, t)
                    unselectToggleBtnUi(b.customDomainTgBlock)
                    unselectToggleBtnUi(b.customDomainTgNoRule)
                }
            }
        }

        private val domainRulesGroupListener =
            MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
                val b: MaterialButton = b.customDomainToggleGroup.findViewById(checkedId)

                val statusId = findSelectedRuleByTag(getTag(b.tag))
                // delete button
                if (statusId == null && isChecked) {
                    group.clearChecked()
                    showDialogForDelete(customDomain)
                    return@OnButtonCheckedListener
                }

                // invalid selection
                if (statusId == null) {
                    return@OnButtonCheckedListener
                }

                if (isChecked) {
                    // See CustomIpAdapter.kt for the same code (ipRulesGroupListener)
                    val hasStatusChanged = customDomain.status != statusId.id
                    if (!hasStatusChanged) {
                        return@OnButtonCheckedListener
                    }
                    val t = toggleBtnUi(statusId)
                    // update toggle button
                    selectToggleBtnUi(b, t)
                    // update status in desc and status flag (N/B/W)
                    updateStatusUi(statusId, customDomain.modifiedTs)
                    // change status based on selected btn
                    changeDomainStatus(statusId, customDomain)
                } else {
                    unselectToggleBtnUi(b)
                }
            }

        // each button in the toggle group is associated with tag value.
        // tag values are ids of DomainRulesManager.DomainStatus
        private fun getTag(tag: Any): Int {
            return tag.toString().toIntOrNull() ?: 0
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

        private fun updateStatusUi(status: DomainRulesManager.Status, modifiedTs: Long) {
            val now = System.currentTimeMillis()
            val uptime = System.currentTimeMillis() - modifiedTs
            // returns a string describing 'time' as a time relative to 'now'
            val time =
                DateUtils.getRelativeTimeSpanString(
                    now - uptime,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            when (status) {
                DomainRulesManager.Status.TRUST -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.ci_trust_initial)
                    b.customDomainStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.ci_trust_rule),
                            time
                        )
                }
                DomainRulesManager.Status.BLOCK -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.cd_blocked_initial)
                    b.customDomainStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.lbl_blocked),
                            time
                        )
                }
                DomainRulesManager.Status.NONE -> {
                    b.customDomainStatusIcon.text = context.getString(R.string.cd_no_rule_initial)
                    b.customDomainStatusTv.text =
                        context.getString(
                            R.string.ci_desc,
                            context.getString(R.string.cd_no_rule_txt),
                            time
                        )
                }
            }

            // update the background color and text color of the status icon
            val t = toggleBtnUi(status)
            b.customDomainStatusIcon.setTextColor(t.txtColor)
            b.customDomainStatusIcon.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
