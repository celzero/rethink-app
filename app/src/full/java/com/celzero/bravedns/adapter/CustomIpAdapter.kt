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
import com.celzero.bravedns.ui.bottomsheet.CustomIpRulesBtmSheet
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getCountryCode
import com.celzero.bravedns.util.Utilities.getFlag
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomIpAdapter(private val context: Context, private val type: CustomRulesActivity.RULES) :
    PagingDataAdapter<CustomIp, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private val selectedItems = mutableSetOf<CustomIp>()
    private var isSelectionMode = false

    companion object {
        private const val TAG = "CustomIpAdapter"
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<CustomIp>() {

                override fun areItemsTheSame(oldConnection: CustomIp, newConnection: CustomIp) =
                    oldConnection.uid == newConnection.uid &&
                            oldConnection.ipAddress == newConnection.ipAddress &&
                            oldConnection.port == newConnection.port

                override fun areContentsTheSame(oldConnection: CustomIp, newConnection: CustomIp) =
                    oldConnection.status == newConnection.status &&
                            oldConnection.proxyCC == newConnection.proxyCC &&
                            oldConnection.proxyId == newConnection.proxyId &&
                            oldConnection.modifiedDateTime == newConnection.modifiedDateTime
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
            is CustomIpsViewHolderWithHeader -> {
                holder.update(customIp)

            }
            is CustomIpsViewHolderWithoutHeader -> {
                holder.update(customIp)
            }
            else -> {
                Logger.w(LOG_TAG_UI, "$TAG unknown view holder in CustomDomainRulesAdapter")
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

    fun getSelectedItems(): List<CustomIp> = selectedItems.toList()

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

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        Glide.with(context)
            .load(drawable)
            .error(Utilities.getDefaultIcon(context))
            .into(mIconImageView)
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

    inner class CustomIpsViewHolderWithHeader(private val b: ListItemCustomAllIpBinding) :
        RecyclerView.ViewHolder(b.root) {

        private lateinit var customIp: CustomIp

        fun update(ci: CustomIp) {
            customIp = ci

            b.customIpCheckbox.isChecked = selectedItems.contains(customIp)
            b.customIpCheckbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
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


            b.customIpLabelTv.text =
                context.getString(
                    R.string.ci_ip_label,
                    customIp.ipAddress,
                    customIp.port.toString()
                )
            val status = findSelectedIpRule(customIp.status) ?: return

            // update flag for the available ips
            updateFlagIfAvailable(customIp)
            // update status in desc and status flag (N/B/W)
            updateStatusUi(status)

            b.customIpEditIcon.setOnClickListener { showEditIpDialog(customIp) }

            b.customIpExpandIcon.setOnClickListener { showBtmSheet() }

            b.customIpContainer.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(customIp)
                } else {
                    showBtmSheet()
                }
            }

            b.customIpCheckbox.setOnClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                }
                toggleSelection(customIp)
                // Fix: Use notifyItemChanged for single item instead of notifyDataSetChanged
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < itemCount) {
                    notifyItemChanged(position)
                }
            }

            b.customIpSeeMoreChip.setOnClickListener { openAppWiseRulesActivity(customIp.uid) }

            b.customIpContainer.setOnLongClickListener {
                isSelectionMode = true
                selectedItems.add(customIp)
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

        private fun showBtmSheet() {
            val bottomSheet = CustomIpRulesBtmSheet.newInstance(customIp)
            bottomSheet.show((context as CustomRulesActivity).supportFragmentManager, bottomSheet.tag)
        }

        private fun toggleSelection(item: CustomIp) {
            if (selectedItems.contains(item)) {
                selectedItems.remove(item)
                b.customIpCheckbox.isChecked = false
            } else {
                selectedItems.add(item)
                b.customIpCheckbox.isChecked = true
            }
        }

        private fun openAppWiseRulesActivity(uid: Int) {
            val intent = Intent(context, CustomRulesActivity::class.java)
            intent.putExtra(
                Constants.VIEW_PAGER_SCREEN_TO_LOAD,
                CustomRulesActivity.Tabs.IP_RULES.screen
            )
            intent.putExtra(Constants.INTENT_UID, uid)
            context.startActivity(intent)
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

        private fun updateFlagIfAvailable(ip: CustomIp) {
            if (ip.wildcard) return

            val inetAddr = try {
                IPAddressString(ip.ipAddress).hostAddress.toInetAddress()
            } catch (e: Exception) {
                null // invalid ip
            }

            b.customIpFlag.text = getFlag(getCountryCode(inetAddr, context))
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
            b.customIpCheckbox.isChecked = selectedItems.contains(customIp)
            b.customIpCheckbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE

            b.customIpLabelTv.text =
                context.getString(
                    R.string.ci_ip_label,
                    customIp.ipAddress,
                    customIp.port.toString()
                )
            val status = findSelectedIpRule(customIp.status) ?: return

            // update flag for the available ips
            updateFlagIfAvailable(customIp)
            // update status in desc and status flag (N/B/W)
            updateStatusUi(status)

            b.customIpEditIcon.setOnClickListener { showEditIpDialog(customIp) }

            b.customIpExpandIcon.setOnClickListener { showBtmSheet() }

            b.customIpContainer.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(customIp)
                } else {
                    showBtmSheet()
                }
            }

            b.customIpCheckbox.setOnClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                }
                toggleSelection(customIp)
                // Fix: Use notifyItemChanged for single item instead of notifyDataSetChanged
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < itemCount) {
                    notifyItemChanged(position)
                }
            }

            b.customIpContainer.setOnLongClickListener {
                isSelectionMode = true
                selectedItems.add(customIp)
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

        private fun showBtmSheet() {
            val bottomSheet = CustomIpRulesBtmSheet.newInstance(customIp)
            bottomSheet.show((context as CustomRulesActivity).supportFragmentManager, bottomSheet.tag)
        }

        private fun toggleSelection(item: CustomIp) {
            if (selectedItems.contains(item)) {
                selectedItems.remove(item)
                b.customIpCheckbox.isChecked = false
            } else {
                selectedItems.add(item)
                b.customIpCheckbox.isChecked = true
            }
        }

        private fun updateFlagIfAvailable(ip: CustomIp) {
            if (ip.wildcard) return

            val inetAddr = try {
                IPAddressString(ip.ipAddress).hostAddress.toInetAddress()
            } catch (e: Exception) {
                null // invalid ip
            }

            b.customIpFlag.text = getFlag(getCountryCode(inetAddr, context))
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
        val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim).setView(dBind.root)
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
            Logger.i(LOG_TAG_UI, "$TAG ip: $ip, port: $port, status: $status")
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

        io { IpRulesManager.replaceIpRule(prev, ipString, port, status, "", "") }
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
