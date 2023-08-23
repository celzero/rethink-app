/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.FirewallStatusSpinnerAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.databinding.BottomSheetConnTrackBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.getLabelForStatus
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.FirewallRuleset.Companion.getFirewallRule
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UiUtils
import com.celzero.bravedns.util.UiUtils.fetchColor
import com.celzero.bravedns.util.UiUtils.updateHtmlEncodedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import java.util.Locale
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

class ConnTrackerBottomSheetFragment : BottomSheetDialogFragment(), KoinComponent {

    private var _binding: BottomSheetConnTrackBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private var info: ConnectionTracker? = null
    private val appConfig by inject<AppConfig>()

    companion object {
        const val INSTANCE_STATE_IPDETAILS = "IPDETAILS"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetConnTrackBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val data = arguments?.getString(INSTANCE_STATE_IPDETAILS)
        info = Gson().fromJson(data, ConnectionTracker::class.java)
        initView()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        if (info == null) {
            Log.w(LOG_TAG_FIREWALL, "ip-details missing: initView called before onViewCreated?")
            this.dismiss()
            return
        }

        b.bsConnConnectionTypeHeading.text = info!!.ipAddress
        b.bsConnConnectionFlag.text = info!!.flag

        b.bsConnBlockAppTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block))
        b.bsConnBlockConnAllTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_ip))
        b.bsConnDomainTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_domain))

        // updates the application name and other details
        updateAppDetails()
        // setup click and item selected listeners
        setupClickListeners()
        // updates the ip rules button
        updateIpRulesUi(info!!.uid, info!!.ipAddress, info!!.port)
        // updates the blocked rules chip
        updateBlockedRulesChip()
        // updates the connection detail chip
        updateConnDetailsChip()
        // assigns color for the blocked rules chip
        lightenUpChip()
        // updates the value from dns request cache if available
        updateDnsIfAvailable()
        // updates the summary details
        displaySummaryDetails()
    }

    override fun onResume() {
        super.onResume()
        if (info == null) {
            Log.w(LOG_TAG_FIREWALL, "ip-details missing: initView called before onViewCreated?")
            this.dismiss()
            return
        }
        // updates the app firewall's button
        updateFirewallRulesUi(
            FirewallManager.appStatus(info!!.uid),
            FirewallManager.connectionStatus(info!!.uid)
        )
    }

    private fun updateDnsIfAvailable() {
        val domain = info?.dnsQuery
        val uid = info?.uid
        val flag = info?.flag

        if (domain.isNullOrEmpty() || uid == null) {
            b.bsConnDnsCacheText.visibility = View.VISIBLE
            b.bsConnDnsCacheText.text = UiUtils.getCountryNameFromFlag(flag)
            b.bsConnDomainRuleLl.visibility = View.GONE
            b.bsConnTrustedMsg.visibility = View.GONE
            return
        }

        val status = DomainRulesManager.getDomainRule(domain, uid)
        b.bsConnDomainSpinner.setSelection(status.id)
        b.bsConnDnsCacheText.visibility = View.VISIBLE
        b.bsConnDnsCacheText.text =
            requireContext()
                .getString(R.string.two_argument, UiUtils.getCountryNameFromFlag(flag), domain)

        if (showTrustDomainTip(status)) {
            b.bsConnTrustedMsg.visibility = View.VISIBLE
        } else {
            b.bsConnTrustedMsg.visibility = View.GONE
        }
    }

    private fun showTrustDomainTip(status: DomainRulesManager.Status): Boolean {
        return status == DomainRulesManager.Status.TRUST &&
            !appConfig.getDnsType().isRethinkRemote()
    }

    private fun updateConnDetailsChip() {
        if (info == null) {
            Log.w(LOG_TAG_FIREWALL, "ip-details missing: not updating the chip details")
            return
        }

        val protocol = Protocol.getProtocolName(info!!.protocol).name
        val time =
            DateUtils.getRelativeTimeSpanString(
                info!!.timeStamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        val protocolDetails = "$protocol/${info!!.port}"
        if (info!!.isBlocked) {
            b.bsConnTrackPortDetailChip.text =
                getString(R.string.bsct_conn_desc_blocked, protocolDetails, time)
            return
        }

        b.bsConnTrackPortDetailChip.text =
            getString(R.string.bsct_conn_desc_allowed, protocolDetails, time)
    }

    private fun updateBlockedRulesChip() {
        if (info!!.blockedByRule.isBlank()) {
            b.bsConnTrackAppInfo.text = getString(R.string.firewall_rule_no_rule)
            return
        }

        val rule = info!!.blockedByRule
        // TODO: below code is not required, remove it in future (20/03/2023)
        if (rule.contains(FirewallRuleset.RULE2G.id)) {
            b.bsConnTrackAppInfo.text =
                getFirewallRule(FirewallRuleset.RULE2G.id)?.title?.let { getString(it) }
            return
        } else {
            b.bsConnTrackAppInfo.text = getFirewallRule(rule)?.title?.let { getString(it) }
        }
    }

    private fun updateAppDetails() {
        if (info == null) return

        val appNames = FirewallManager.getAppNamesByUid(info!!.uid)

        val appCount = appNames.count()
        if (appCount >= 1) {
            b.bsConnBlockedRule2HeaderLl.visibility = View.GONE
            b.bsConnTrackAppName.text =
                if (appCount >= 2) {
                    getString(
                        R.string.ctbs_app_other_apps,
                        appNames[0],
                        appCount.minus(1).toString()
                    ) + "      ❯"
                } else {
                    appNames[0] + "      ❯"
                }
            val pkgName = FirewallManager.getPackageNameByAppName(appNames[0]) ?: return
            b.bsConnTrackAppIcon.setImageDrawable(getIcon(requireContext(), pkgName, info?.appName))
        } else {
            // apps which are not available in cache are treated as non app.
            // TODO: check packageManager#getApplicationInfo() for appInfo
            handleNonApp()
        }
    }

    private fun displaySummaryDetails() {
        if (
            info?.message?.isEmpty() == true &&
                info?.duration == 0 &&
                info?.downloadBytes == 0L &&
                info?.uploadBytes == 0L
        ) {
            b.connectionSummaryLl.visibility = View.GONE
            b.connectionUploadDownload.visibility = View.GONE
            return
        }

        b.connectionSummaryLl.visibility = View.VISIBLE
        b.connectionMessage.text = info?.message
        val uploadBytes = Utilities.humanReadableByteCount(info?.uploadBytes ?: 0L, true)
        val downloadBytes = Utilities.humanReadableByteCount(info?.downloadBytes ?: 0L, true)
        b.connectionUploadDownload.text =
            getString(R.string.ct_bs_upload_download, uploadBytes, downloadBytes)
    }

    private fun lightenUpChip() {
        // Load icons for the firewall rules if available
        b.bsConnTrackAppInfo.chipIcon =
            ContextCompat.getDrawable(
                requireContext(),
                FirewallRuleset.getRulesIcon(info?.blockedByRule)
            )
        if (info!!.isBlocked) {
            b.bsConnTrackAppInfo.setTextColor(fetchColor(requireContext(), R.attr.chipTextNegative))
            val colorFilter =
                PorterDuffColorFilter(
                    fetchColor(requireContext(), R.attr.chipTextNegative),
                    PorterDuff.Mode.SRC_IN
                )
            b.bsConnTrackAppInfo.chipBackgroundColor =
                ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorNegative))
            b.bsConnTrackAppInfo.chipIcon?.colorFilter = colorFilter
        } else {
            b.bsConnTrackAppInfo.setTextColor(fetchColor(requireContext(), R.attr.chipTextPositive))
            val colorFilter =
                PorterDuffColorFilter(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    PorterDuff.Mode.SRC_IN
                )
            b.bsConnTrackAppInfo.chipBackgroundColor =
                ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorPositive))
            b.bsConnTrackAppInfo.chipIcon?.colorFilter = colorFilter
        }
    }

    private fun handleNonApp() {
        // show universal setting layout
        b.bsConnBlockedRule2HeaderLl.visibility = View.VISIBLE
        // hide the app firewall layout
        b.bsConnBlockedRule1HeaderLl.visibility = View.GONE
        b.bsConnUnknownAppCheck.isChecked = persistentState.getBlockUnknownConnections()
        b.bsConnTrackAppName.text = info!!.appName
    }

    private fun setupClickListeners() {
        b.bsConnUnknownAppCheck.setOnCheckedChangeListener(null)
        b.bsConnUnknownAppCheck.setOnClickListener {
            if (DEBUG)
                Log.d(
                    LOG_TAG_FIREWALL,
                    "Unknown app, universal firewall settings(block unknown app): ${b.bsConnUnknownAppCheck.isChecked} "
                )
            persistentState.setBlockUnknownConnections(b.bsConnUnknownAppCheck.isChecked)
        }

        b.bsConnTrackAppInfo.setOnClickListener { showFirewallRulesDialog(info!!.blockedByRule) }

        b.bsConnTrackAppNameHeader.setOnClickListener {
            val ai = FirewallManager.getAppInfoByUid(info!!.uid)
            // case: app is uninstalled but still available in RethinkDNS database
            if (ai == null || info?.uid == Constants.INVALID_UID) {
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.ct_bs_app_info_error),
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }

            openAppDetailActivity(info!!.uid)
        }

        // spinner to show firewall rules
        b.bsConnFirewallSpinner.adapter =
            FirewallStatusSpinnerAdapter(
                requireContext(),
                FirewallManager.getLabel(requireContext())
            )
        b.bsConnFirewallSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val iv = view?.findViewById<AppCompatImageView>(R.id.spinner_icon)
                    iv?.visibility = View.VISIBLE
                    val fStatus = FirewallManager.FirewallStatus.getStatusByLabel(position)
                    val connStatus = FirewallManager.ConnectionStatus.getStatusByLabel(position)

                    // no change, prev selection and current selection are same
                    if (
                        FirewallManager.appStatus(info!!.uid) == fStatus &&
                            FirewallManager.connectionStatus(info!!.uid) == connStatus
                    )
                        return

                    Log.i(
                        LOG_TAG_FIREWALL,
                        "Change in firewall rule for app uid: ${info?.uid}, firewall status: $fStatus, conn status: $connStatus"
                    )
                    applyFirewallRule(fStatus, connStatus)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        b.bsConnIpRuleSpinner.adapter =
            FirewallStatusSpinnerAdapter(
                requireContext(),
                IpRulesManager.IpRuleStatus.getLabel(requireContext())
            )
        b.bsConnIpRuleSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val iv = view?.findViewById<AppCompatImageView>(R.id.spinner_icon)
                    iv?.visibility = View.VISIBLE
                    val fid = IpRulesManager.IpRuleStatus.getStatus(position)

                    // no need to apply rule, prev selection and current selection are same
                    if (
                        IpRulesManager.isIpRuleAvailable(
                            info!!.uid,
                            info!!.ipAddress,
                            info!!.port
                        ) == fid
                    )
                        return

                    applyIpRule(fid)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        b.bsConnDomainSpinner.adapter =
            FirewallStatusSpinnerAdapter(
                requireContext(),
                DomainRulesManager.Status.getLabel(requireContext())
            )
        b.bsConnDomainSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (info!!.dnsQuery == null) {
                        Log.w(LOG_TAG_FIREWALL, "DNS query is null, cannot apply domain rule")
                        return
                    }

                    val iv = view?.findViewById<AppCompatImageView>(R.id.spinner_icon)
                    iv?.visibility = View.VISIBLE
                    val fid = DomainRulesManager.Status.getStatus(position)

                    // no need to apply rule, prev selection and current selection are same
                    if (
                        info!!.dnsQuery?.let { DomainRulesManager.getDomainRule(it, info!!.uid) } ==
                            fid
                    )
                        return

                    if (showTrustDomainTip(fid)) {
                        b.bsConnTrustedMsg.visibility = View.VISIBLE
                    } else {
                        b.bsConnTrustedMsg.visibility = View.GONE
                    }

                    applyDomainRule(fid)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun openAppDetailActivity(uid: Int) {
        this.dismiss()
        val intent = Intent(requireContext(), AppInfoActivity::class.java)
        intent.putExtra(AppInfoActivity.UID_INTENT_NAME, uid)
        requireContext().startActivity(intent)
    }

    private fun updateFirewallRulesUi(
        firewallStatus: FirewallManager.FirewallStatus,
        connStatus: FirewallManager.ConnectionStatus
    ) {
        // no need to update the state if it's untracked
        if (firewallStatus.isUntracked()) return

        when (firewallStatus) {
            FirewallManager.FirewallStatus.NONE -> {
                when (connStatus) {
                    FirewallManager.ConnectionStatus.ALLOW -> {
                        b.bsConnFirewallSpinner.setSelection(0, true)
                    }
                    FirewallManager.ConnectionStatus.BOTH -> {
                        b.bsConnFirewallSpinner.setSelection(1, true)
                    }
                    FirewallManager.ConnectionStatus.UNMETERED -> {
                        b.bsConnFirewallSpinner.setSelection(2, true)
                    }
                    FirewallManager.ConnectionStatus.METERED -> {
                        b.bsConnFirewallSpinner.setSelection(3, true)
                    }
                }
            }
            FirewallManager.FirewallStatus.ISOLATE -> {
                b.bsConnFirewallSpinner.setSelection(4, true)
            }
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> {
                b.bsConnFirewallSpinner.setSelection(5, true)
            }
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> {
                b.bsConnFirewallSpinner.setSelection(6, true)
            }
            FirewallManager.FirewallStatus.EXCLUDE -> {
                b.bsConnFirewallSpinner.setSelection(7, true)
            }
            else -> {
                // no-op
            }
        }
    }

    private fun updateIpRulesUi(uid: Int, ipAddress: String, port: Int) {
        b.bsConnIpRuleSpinner.setSelection(
            IpRulesManager.isIpRuleAvailable(uid, ipAddress, port).id
        )
    }

    private fun showFirewallRulesDialog(blockedRule: String?) {
        if (blockedRule == null) return

        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(dialogBinding.root)
        val heading = dialogBinding.infoRulesDialogRulesTitle
        val okBtn = dialogBinding.infoRulesDialogCancelImg
        val desc = dialogBinding.infoRulesDialogRulesDesc
        val icon = dialogBinding.infoRulesDialogRulesIcon
        icon.visibility = View.VISIBLE
        val headingText: String
        var descText: Spanned

        if (blockedRule.contains(FirewallRuleset.RULE2G.id)) {
            val group: Multimap<String, String> = HashMultimap.create()

            val blocklists =
                if (info?.blocklists?.isEmpty() == true) {
                    val startIndex = blockedRule.indexOfFirst { it == '|' }
                    blockedRule.substring(startIndex + 1).split(",")
                } else {
                    info?.blocklists?.split(",") ?: listOf()
                }

            blocklists.forEach {
                val items = it.split(":")
                if (items.count() <= 1) return@forEach

                group.put(items[0], items[1])
            }
            descText = formatText(group)
            val groupCount = group.keys().distinct().count()

            headingText =
                if (groupCount > 1) {
                    "${group.keys().first()} +${groupCount - 1}"
                } else if (groupCount == 1) {
                    group.keys().first()
                } else {
                    val tempDesc =
                        getFirewallRule(FirewallRuleset.RULE2G.id)?.let { getString(it.desc) }
                            ?: getString(R.string.firewall_rule_no_rule_desc)
                    descText = updateHtmlEncodedText(tempDesc)
                    getFirewallRule(FirewallRuleset.RULE2G.id)?.let { getString(it.title) }
                        ?: getString(R.string.firewall_rule_no_rule)
                }
        } else {
            headingText =
                getFirewallRule(blockedRule)?.let { getString(it.title) }
                    ?: getString(R.string.firewall_rule_no_rule)
            val tempDesc =
                getFirewallRule(blockedRule)?.let { getString(it.desc) }
                    ?: getString(R.string.firewall_rule_no_rule_desc)
            descText = updateHtmlEncodedText(tempDesc)
        }

        desc.text = descText
        heading.text = headingText
        icon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), FirewallRuleset.getRulesIcon(blockedRule))
        )

        okBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun formatText(groupNames: Multimap<String, String>): Spanned {
        var text = ""
        groupNames.keys().distinct().forEach {
            val heading =
                it.replaceFirstChar { a ->
                    if (a.isLowerCase()) a.titlecase(Locale.getDefault()) else a.toString()
                }
            text +=
                getString(
                    R.string.dns_btm_sheet_dialog_message,
                    heading,
                    groupNames.get(it).count().toString(),
                    TextUtils.join(", ", groupNames.get(it))
                )
        }
        text = text.replace(",", ", ")
        return updateHtmlEncodedText(text)
    }

    private fun applyFirewallRule(
        firewallStatus: FirewallManager.FirewallStatus,
        connStatus: FirewallManager.ConnectionStatus
    ) {
        val appNames = FirewallManager.getAppNamesByUid(info!!.uid)
        if (appNames.count() > 1) {
            val prevStatus = FirewallManager.appStatus(info!!.uid)
            showFirewallDialog(appNames, firewallStatus, prevStatus, connStatus)
            return
        }

        FirewallManager.updateFirewallStatus(info!!.uid, firewallStatus, connStatus)
        updateFirewallRulesUi(firewallStatus, connStatus)
    }

    private fun applyIpRule(ipRuleStatus: IpRulesManager.IpRuleStatus) {
        val protocol = Protocol.getProtocolName(info!!.protocol).name
        val connRules = ConnectionRules(info!!.ipAddress, info!!.port, protocol)

        Log.i(
            LOG_TAG_FIREWALL,
            "Apply ip rule for ${connRules.ipAddress}, ${FirewallRuleset.RULE2.name}"
        )
        IpRulesManager.updateRule(info!!.uid, connRules.ipAddress, connRules.port, ipRuleStatus)
    }

    private fun applyDomainRule(domainRuleStatus: DomainRulesManager.Status) {
        Log.i(
            LOG_TAG_FIREWALL,
            "Apply domain rule for ${info!!.dnsQuery}, ${domainRuleStatus.name}"
        )
        DomainRulesManager.addDomainRule(
            info!!.dnsQuery!!,
            domainRuleStatus,
            DomainRulesManager.DomainType.DOMAIN,
            info!!.uid,
        )
    }

    private fun getAppName(): String {
        val appNames = FirewallManager.getAppNamesByUid(info!!.uid)

        val packageCount = appNames.count()
        return if (packageCount >= 2) {
            getString(R.string.ctbs_app_other_apps, appNames[0], packageCount.minus(1).toString())
        } else {
            appNames[0]
        }
    }

    private fun showFirewallDialog(
        packageList: List<String>,
        status: FirewallManager.FirewallStatus,
        prevStatus: FirewallManager.FirewallStatus,
        connStatus: FirewallManager.ConnectionStatus
    ) {

        val builderSingle = MaterialAlertDialogBuilder(requireContext())

        builderSingle.setIcon(R.drawable.ic_firewall_block_grey)
        val count = packageList.count()
        builderSingle.setTitle(
            this.getString(R.string.ctbs_block_other_apps, getAppName(), count.toString())
        )

        val arrayAdapter =
            ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle
            .setPositiveButton(getString(getLabelForStatus(status, connStatus))) {
                dialog: DialogInterface,
                _: Int ->
                // call dialog.dismiss() before updating the details.
                // Without dismissing this dialog, the bottom sheet dialog is not
                // refreshing/updating its UI. One way is to dismiss the dialog
                // before updating the UI.
                // b.root.invalidate()/ b.root.notify didn't help in this case.
                dialog.dismiss()

                Log.i(LOG_TAG_FIREWALL, "Apply firewall rule for uid: ${info?.uid}, ${status.name}")
                FirewallManager.updateFirewallStatus(info!!.uid, status, connStatus)
                updateFirewallRulesUi(status, connStatus)
            }
            .setNeutralButton(this.getString(R.string.ctbs_dialog_negative_btn)) {
                _: DialogInterface,
                _: Int ->
                updateFirewallRulesUi(prevStatus, FirewallManager.connectionStatus(info!!.uid))
            }

        val alertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }
}
