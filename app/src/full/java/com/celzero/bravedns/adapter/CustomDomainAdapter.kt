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
import android.content.Intent
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.DialogAddCustomDomainBinding
import com.celzero.bravedns.databinding.ListItemCustomAllDomainBinding
import com.celzero.bravedns.databinding.ListItemCustomDomainBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.DomainRulesManager.isValidDomain
import com.celzero.bravedns.service.DomainRulesManager.isWildCardEntry
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.bottomsheet.CustomDomainRulesBtmSheet
import com.celzero.bravedns.ui.bottomsheet.CustomDomainRulesBtmSheet.ToggleBtnUi
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class CustomDomainAdapter(
    val context: Context,
    val fragment: Fragment,
    val rule: CustomRulesActivity.RULES,
    val eventLogger: EventLogger
) :
    PagingDataAdapter<CustomDomain, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private val selectedItems = mutableSetOf<CustomDomain>()
    private var isSelectionMode = false
    private lateinit var adapter: CustomDomainAdapter

    companion object {
        private const val TAG = "CustomDomainAdapter"
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<CustomDomain>() {
                override fun areItemsTheSame(
                    oldConnection: CustomDomain,
                    newConnection: CustomDomain
                ): Boolean {
                    return (oldConnection.domain == newConnection.domain &&
                            oldConnection.status == newConnection.status &&
                            oldConnection.proxyId == newConnection.proxyId &&
                            oldConnection.proxyCC == newConnection.proxyCC)
                }

                override fun areContentsTheSame(
                    oldConnection: CustomDomain,
                    newConnection: CustomDomain
                ): Boolean {
                    return oldConnection == newConnection
                }
            }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        adapter = this
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
        when (holder) {
            is CustomDomainViewHolderWithHeader -> {
                holder.update(customDomain)
            }

            is CustomDomainViewHolderWithoutHeader -> {
                holder.update(customDomain)
            }

            else -> {
                Logger.w(LOG_TAG_UI, "unknown view holder in CustomDomainRulesAdapter")
                return
            }
        }
    }

    fun getSelectedItems(): List<CustomDomain> = selectedItems.toList()

    fun clearSelection() {
        selectedItems.clear()
        isSelectionMode = false
        // Fix: Use notifyItemRangeChanged instead of notifyDataSetChanged for PagingDataAdapter
        // to avoid IndexOutOfBoundsException from adapter inconsistency
        try {
            if (itemCount > 0) {
                notifyItemRangeChanged(0, itemCount)
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG error clearing selection: ${e.message}", e)
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

    private fun showEditDomainDialog(customDomain: CustomDomain) {
        val dBind =
            DialogAddCustomDomainBinding.inflate((context as CustomRulesActivity).layoutInflater)
        val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim).setView(dBind.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        var selectedType: DomainRulesManager.DomainType =
            DomainRulesManager.DomainType.getType(customDomain.type)

        if (customDomain.domain.startsWith("*") || customDomain.domain.startsWith(".")) {
            dBind.dacdWildcardChip.isChecked = true
        } else {
            dBind.dacdDomainChip.isChecked = true
        }

        dBind.dacdDomainEditText.setText(customDomain.domain)

        dBind.dacdDomainEditText.addTextChangedListener {
            if (it?.startsWith("*") == true || it?.startsWith(".") == true) {
                dBind.dacdWildcardChip.isChecked = true
            } else {
                dBind.dacdDomainChip.isChecked = true
            }
        }

        dBind.dacdDomainChip.setOnCheckedChangeListener { _, isSelected ->
            Logger.vv(LOG_TAG_UI, "$TAG domain chip selected: $isSelected")
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
            Logger.vv(LOG_TAG_UI, "$TAG wildcard chip selected: $isSelected")
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
        val extractedHost = extractHost(url) ?: run {
            dBind.dacdFailureText.text =
                context.getString(R.string.cd_dialog_error_invalid_domain)
            dBind.dacdFailureText.visibility = View.VISIBLE
            Logger.vv(LOG_TAG_UI, "$TAG invalid domain: $url")
            return
        }
        when (selectedType) {
            DomainRulesManager.DomainType.WILDCARD -> {
                if (!isWildCardEntry(extractedHost)) {
                    dBind.dacdFailureText.text =
                        context.getString(R.string.cd_dialog_error_invalid_wildcard)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    Logger.vv(LOG_TAG_UI, "$TAG invalid wildcard domain: $url")
                    return
                }
            }

            DomainRulesManager.DomainType.DOMAIN -> {
                if (!isValidDomain(extractedHost)) {
                    dBind.dacdFailureText.text =
                        context.getString(R.string.cd_dialog_error_invalid_domain)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    Logger.vv(LOG_TAG_UI, "$TAG invalid domain: $url")
                    return
                }
            }
        }

        io {
            Logger.vv(LOG_TAG_UI, "$TAG domain: $extractedHost, type: $selectedType")
            insertDomain(
                Utilities.removeLeadingAndTrailingDots(extractedHost),
                selectedType,
                prevDomain,
                status
            )
        }
    }

    // fixme: same as extractHost in CustomDomainFragment, should be moved to a common place
    private fun extractHost(input: String): String? {
        // Use centralized domain extraction logic from DomainRulesManager
        return DomainRulesManager.extractHost(input)
    }

    private suspend fun insertDomain(
        domain: String,
        type: DomainRulesManager.DomainType,
        prevDomain: CustomDomain,
        status: DomainRulesManager.Status
    ) {
        Logger.i(LOG_TAG_UI, "$TAG insert/update domain: $domain, type: $type")
        DomainRulesManager.updateDomainRule(domain, status, type, prevDomain)
        uiCtx {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.cd_toast_edit),
                Toast.LENGTH_SHORT
            )
        }
        logEvent("Custom domain insert/update $domain, status: $status")
    }

    inner class CustomDomainViewHolderWithHeader(private val b: ListItemCustomAllDomainBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customDomain: CustomDomain

        fun update(cd: CustomDomain) {
            this.customDomain = cd

            b.customDomainCheckbox.isChecked = selectedItems.contains(cd)
            b.customDomainCheckbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
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


                    b.customDomainLabelTv.text = customDomain.domain

                    // update status in desc and status flag (N/B/W)
                    updateStatusUi(
                        DomainRulesManager.Status.getStatus(cd.status),
                        cd.modifiedTs
                    )

                    b.customDomainEditIcon.setOnClickListener { showEditDomainDialog(cd) }

                    b.customDomainExpandIcon.setOnClickListener {
                        showButtonsBottomSheet(customDomain)
                        //toggleActionsUi()
                    }

                    b.customDomainContainer.setOnClickListener {
                        if (isSelectionMode) {
                            toggleSelection(cd)
                        } else {
                            showButtonsBottomSheet(customDomain)
                        }
                    }

                    b.customDomainCheckbox.setOnClickListener {
                        toggleSelection(cd)
                    }

                    b.customDomainSeeMoreChip.setOnClickListener { openAppWiseRulesActivity(cd.uid) }

                    b.customDomainContainer.setOnLongClickListener {
                        isSelectionMode = true
                        selectedItems.add(cd)
                        // Fix: Use notifyItemRangeChanged instead of notifyDataSetChanged
                        try {
                            if (itemCount > 0) {
                                notifyItemRangeChanged(0, itemCount)
                            }
                        } catch (e: Exception) {
                            Logger.e(LOG_TAG_UI, "$TAG error in long click: ${e.message}", e)
                        }
                        true
                    }
                }
            }
        }

        private fun toggleSelection(item: CustomDomain) {
            if (selectedItems.contains(item)) {
                selectedItems.remove(item)
                b.customDomainCheckbox.isChecked = false
            } else {
                selectedItems.add(item)
                b.customDomainCheckbox.isChecked = true
            }
        }

        private fun openAppWiseRulesActivity(uid: Int) {
            val intent = Intent(context, CustomRulesActivity::class.java)
            intent.putExtra(
                Constants.VIEW_PAGER_SCREEN_TO_LOAD,
                CustomRulesActivity.Tabs.DOMAIN_RULES.screen
            )
            intent.putExtra(Constants.INTENT_UID, uid)
            context.startActivity(intent)
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
            b.customDomainCheckbox.isChecked = selectedItems.contains(cd)
            b.customDomainCheckbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            b.customDomainLabelTv.text = customDomain.domain

            // update status in desc and status flag (N/B/W)
            updateStatusUi(
                DomainRulesManager.Status.getStatus(customDomain.status),
                customDomain.modifiedTs
            )

            b.customDomainEditIcon.setOnClickListener { showEditDomainDialog(customDomain) }

            b.customDomainExpandIcon.setOnClickListener {
                showButtonsBottomSheet(customDomain)
                //toggleActionsUi()
            }

            b.customDomainContainer.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(cd)
                } else {
                    showButtonsBottomSheet(customDomain)
                }
            }

            b.customDomainCheckbox.setOnClickListener {
                toggleSelection(cd)
            }

            b.customDomainContainer.setOnLongClickListener {
                isSelectionMode = true
                selectedItems.add(cd)
                // Fix: Use notifyItemRangeChanged instead of notifyDataSetChanged
                try {
                    if (itemCount > 0) {
                        notifyItemRangeChanged(0, itemCount)
                    }
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_UI, "$TAG error in long click: ${e.message}", e)
                }
                true
            }
        }

        private fun toggleSelection(item: CustomDomain) {
            if (selectedItems.contains(item)) {
                selectedItems.remove(item)
                b.customDomainCheckbox.isChecked = false
            } else {
                selectedItems.add(item)
                b.customDomainCheckbox.isChecked = true
            }
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

    private fun showButtonsBottomSheet(customDomain: CustomDomain) {
        val bottomSheetFragment = CustomDomainRulesBtmSheet(customDomain)
        bottomSheetFragment.show(fragment.parentFragmentManager, bottomSheetFragment.tag)
    }

    private fun logEvent(details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "Custom Domain", EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}

