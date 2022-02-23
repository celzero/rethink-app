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
import android.text.format.DateUtils
import android.util.Log
import android.view.*
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.automaton.IpRulesManager.UID_EVERYBODY
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.databinding.BottomSheetConnTrackBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.FirewallRuleset.Companion.getFirewallRule
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import com.celzero.bravedns.util.Utilities.Companion.getIcon
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

/**
 * Renders network logs from where user can also set firewall rules.
 */
class ConnTrackerBottomSheetFragment(private var ipDetails: ConnectionTracker) :
        BottomSheetDialogFragment(), KoinComponent {

    private var _binding: BottomSheetConnTrackBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    private val dnsLogTracker by inject<DnsLogTracker>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetConnTrackBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getTheme(): Int = Themes.getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                                     persistentState.theme)

    private val persistentState by inject<PersistentState>()

    private val appToggleGroupListener = MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
        val btn: MaterialButton = b.bsConnBlockAppToggleGroup.findViewById(checkedId)
        if (!isChecked) {
            disableToggleButton(btn)
            return@OnButtonCheckedListener
        }

        applyFirewallRule(findSelectedFirewallRule(getTag(btn.tag)))
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        displayDetails()
        setupClickListeners()
    }

    private fun displayDetails() {
        b.bsConnConnectionTypeHeading.text = ipDetails.ipAddress
        b.bsConnConnectionFlag.text = ipDetails.flag

        b.bsConnBlockAppTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block))
        b.bsConnBlockConnAllTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_all))

        // updates the application name and other details
        updateAppDetails()
        // updates the app firewall's button
        updateFirewallRulesUi(FirewallManager.appStatus(ipDetails.uid))
        // updates the ip rules button
        updateIpRulesUi(ipDetails.uid, ipDetails.ipAddress)
        // updates the blocked rules chip
        updateBlockedRulesChip()
        // updates the connection detail chip
        updateConnDetailsChip()
        // assigns color for the blocked rules chip
        lightenUpChip()
        // updates the value from dns request cache if available
        updateDnsIfAvailable()
    }

    private fun updateDnsIfAvailable() {
        val ipToDomain = dnsLogTracker.ipDomainLookup.getIfPresent(ipDetails.ipAddress)
        ipToDomain?.let {
            b.bsConnDnsCacheText.visibility = View.VISIBLE
            b.bsConnDnsCacheText.text = ipToDomain.fqdn
        }
    }

    private fun updateConnDetailsChip() {
        val protocol = Protocol.getProtocolName(ipDetails.protocol).name
        val time = DateUtils.getRelativeTimeSpanString(ipDetails.timeStamp,
                                                       System.currentTimeMillis(),
                                                       DateUtils.MINUTE_IN_MILLIS,
                                                       DateUtils.FORMAT_ABBREV_RELATIVE)
        val protocolDetails = "$protocol/${ipDetails.port}"
        if (ipDetails.isBlocked) {
            b.bsConnTrackPortDetailChip.text = getString(R.string.bsct_conn_desc_blocked,
                                                         protocolDetails, time)
            return
        }

        b.bsConnTrackPortDetailChip.text = getString(R.string.bsct_conn_desc_allowed,
                                                     protocolDetails, time)
    }

    private fun updateBlockedRulesChip() {
        if (ipDetails.blockedByRule.isBlank()) {
            b.bsConnTrackAppKill.text = getString(R.string.firewall_rule_no_rule)
            return
        }

        val rule = ipDetails.blockedByRule
        b.bsConnTrackAppKill.text = getFirewallRule(rule)?.title?.let {
            getString(it)
        }
    }

    private fun updateAppDetails() {
        val appNames = FirewallManager.getAppNamesByUid(ipDetails.uid)

        val appCount = appNames.count()
        if (appCount >= 1) {
            b.bsConnBlockedRule2HeaderLl.visibility = View.GONE
            b.bsConnTrackAppName.text = if (appCount >= 2) {
                getString(R.string.ctbs_app_other_apps, appNames[0], appCount.minus(1).toString())
            } else {
                appNames[0] + "      ❯"
            }
            val pkgName = FirewallManager.getPackageNameByAppName(appNames[0]) ?: return
            b.bsConnTrackAppIcon.setImageDrawable(
                getIcon(requireContext(), pkgName, ipDetails.appName))
        } else {
            // apps which are not available in cache are treated as non app.
            // TODO: check packageManager#getApplicationInfo() for appInfo
            handleNonApp()
        }
    }

    private fun lightenUpChip() {
        // Load icons for the firewall rules if available
        b.bsConnTrackAppKill.chipIcon = ContextCompat.getDrawable(requireContext(),
                                                                  FirewallRuleset.getRulesIcon(
                                                                      ipDetails.blockedByRule))
        if (ipDetails.isBlocked) {
            b.bsConnTrackAppKill.setTextColor(fetchColor(requireContext(), R.attr.chipTextNegative))
            val colorFilter = PorterDuffColorFilter(fetchColor(requireContext(), R.attr.chipTextNegative),
                                                    PorterDuff.Mode.SRC_IN)
            b.bsConnTrackAppKill.chipBackgroundColor = ColorStateList.valueOf(
                fetchColor(requireContext(), R.attr.chipBgColorNegative))
            b.bsConnTrackAppKill.chipIcon?.colorFilter = colorFilter
        } else {
            b.bsConnTrackAppKill.setTextColor(fetchColor(requireContext(), R.attr.chipTextPositive))
            val colorFilter = PorterDuffColorFilter(fetchColor(requireContext(), R.attr.chipTextPositive),
                                                    PorterDuff.Mode.SRC_IN)
            b.bsConnTrackAppKill.chipBackgroundColor = ColorStateList.valueOf(
                fetchColor(requireContext(), R.attr.chipBgColorPositive))
            b.bsConnTrackAppKill.chipIcon?.colorFilter = colorFilter
        }
    }

    private fun handleNonApp() {
        // show universal setting layout
        b.bsConnBlockedRule2HeaderLl.visibility = View.VISIBLE
        // hide the app firewall layout
        b.bsConnBlockedRule1HeaderLl.visibility = View.GONE
        b.bsConnUnknownAppCheck.isChecked = persistentState.blockUnknownConnections
        b.bsConnTrackAppName.text = ipDetails.appName
    }

    private fun setupClickListeners() {
        b.bsConnUnknownAppCheck.setOnCheckedChangeListener(null)
        b.bsConnUnknownAppCheck.setOnClickListener {
            if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                             "Unknown app, universal firewall settings(block unknown app): ${b.bsConnUnknownAppCheck.isChecked} ")
            persistentState.blockUnknownConnections = b.bsConnUnknownAppCheck.isChecked
        }

        b.bsConnTrackAppKill.setOnClickListener {
            showFirewallRulesDialog(ipDetails.blockedByRule)
        }

        b.bsConnBlockAppToggleGroup.addOnButtonCheckedListener(appToggleGroupListener)
        b.bsConnBlockIpToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            val btn: MaterialButton = b.bsConnBlockIpToggleGroup.findViewById(checkedId)
            if (!isChecked) {
                disableToggleButton(btn)
                return@addOnButtonCheckedListener
            }

            group.check(checkedId)
            enableToggleButton(btn)
            applyIpRule(findSelectedIpRule(getTag(btn.tag)))
        }

        b.bsConnTrackAppNameHeader.setOnClickListener {
            openAppDetailActivity(ipDetails.uid)
        }
    }

    // each button in the toggle group is associated with tag value.
    // tag values are ids of the FirewallManager.AppStatus and IpRulesManager.IpRuleStatus
    private fun getTag(tag: Any): Int {
        return tag.toString().toIntOrNull() ?: 0
    }

    // returns the firewall app status based on the button tag
    private fun findSelectedFirewallRule(ruleId: Int): FirewallManager.AppStatus {
        return when (ruleId) {
            FirewallManager.AppStatus.ALLOW.id -> {
                FirewallManager.AppStatus.ALLOW
            }
            FirewallManager.AppStatus.BLOCK.id -> {
                FirewallManager.AppStatus.BLOCK
            }
            FirewallManager.AppStatus.WHITELIST.id -> {
                FirewallManager.AppStatus.WHITELIST
            }
            FirewallManager.AppStatus.EXCLUDE.id -> {
                FirewallManager.AppStatus.EXCLUDE
            }
            else -> {
                FirewallManager.AppStatus.ALLOW
            }
        }
    }

    // returns the ip rule status value based on button tag
    private fun findSelectedIpRule(ruleId: Int): IpRulesManager.IpRuleStatus {
        return when (ruleId) {
            IpRulesManager.IpRuleStatus.NONE.id -> {
                IpRulesManager.IpRuleStatus.NONE
            }
            IpRulesManager.IpRuleStatus.BLOCK.id -> {
                IpRulesManager.IpRuleStatus.BLOCK
            }
            IpRulesManager.IpRuleStatus.WHITELIST.id -> {
                IpRulesManager.IpRuleStatus.WHITELIST
            }
            else -> {
                IpRulesManager.IpRuleStatus.NONE
            }
        }
    }

    private fun openAppDetailActivity(uid: Int) {
        val intent = Intent(requireContext(), AppInfoActivity::class.java)
        intent.putExtra(AppInfoActivity.UID_INTENT_NAME, uid)
        requireContext().startActivity(intent)
    }

    private fun updateFirewallRulesUi(appStatus: FirewallManager.AppStatus) {
        when (appStatus) {
            FirewallManager.AppStatus.ALLOW -> {
                b.bsConnBlockAppToggleGroup.check(b.bsConnTgNoRule.id)
                enableToggleButton(b.bsConnTgNoRule)
            }
            FirewallManager.AppStatus.BLOCK -> {
                b.bsConnBlockAppToggleGroup.check(b.bsConnTgBlock.id)
                enableToggleButton(b.bsConnTgBlock)
            }
            FirewallManager.AppStatus.WHITELIST -> {
                b.bsConnBlockAppToggleGroup.check(b.bsConnTgWhitelist.id)
                enableToggleButton(b.bsConnTgWhitelist)
            }
            FirewallManager.AppStatus.EXCLUDE -> {
                b.bsConnBlockAppToggleGroup.check(b.bsConnTgExclude.id)
                enableToggleButton(b.bsConnTgExclude)
            }
            FirewallManager.AppStatus.UNTRACKED -> {
                b.bsConnBlockAppToggleGroup.check(b.bsConnTgNoRule.id)
                enableToggleButton(b.bsConnTgNoRule)
            }
        }
    }

    private fun updateIpRulesUi(uid: Int, ipAddress: String) {
        when (IpRulesManager.getStatus(uid, ipAddress)) {
            IpRulesManager.IpRuleStatus.NONE -> {
                b.bsConnBlockIpToggleGroup.check(b.bsConnIpTgNoRule.id)
                enableToggleButton(b.bsConnIpTgNoRule)
            }
            IpRulesManager.IpRuleStatus.WHITELIST -> {
                b.bsConnBlockIpToggleGroup.check(b.bsConnIpTgWhitelist.id)
                enableToggleButton(b.bsConnIpTgWhitelist)
            }
            IpRulesManager.IpRuleStatus.BLOCK -> {
                b.bsConnBlockIpToggleGroup.check(b.bsConnIpTgBlock.id)
                enableToggleButton(b.bsConnIpTgBlock)
            }
        }
    }

    private fun enableToggleButton(button: MaterialButton) {
        button.setTextColor(fetchColor(requireContext(), R.attr.secondaryTextColor))
    }

    private fun disableToggleButton(button: MaterialButton) {
        button.setTextColor(fetchColor(requireContext(), R.attr.primaryTextColor))
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
        val descText = dialogBinding.infoRulesDialogRulesDesc
        val icon = dialogBinding.infoRulesDialogRulesIcon
        icon.visibility = View.VISIBLE

        heading.text = getFirewallRule(blockedRule)?.let { getString(it.title) } ?: getString(
            R.string.firewall_rule_no_rule)
        val desc = getFirewallRule(blockedRule)?.let { getString(it.desc) } ?: getString(
            R.string.firewall_rule_no_rule_desc)

        descText.text = updateHtmlEncodedText(desc)

        icon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), FirewallRuleset.getRulesIcon(blockedRule)))

        okBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun applyFirewallRule(status: FirewallManager.AppStatus) {
        val appNames = FirewallManager.getAppNamesByUid(ipDetails.uid)
        if (appNames.count() > 1) {
            val prevStatus = FirewallManager.appStatus(ipDetails.uid)
            showFirewallDialog(appNames, status, prevStatus)
            return
        }

        Log.d(LOG_TAG_FIREWALL, "Apply firewall rule for uid: ${ipDetails.uid}, ${status.name}")
        FirewallManager.updateFirewallStatus(ipDetails.uid, status,
                                             FirewallManager.ConnectionStatus.BOTH)
        updateFirewallRulesUi(status)
    }

    private fun applyIpRule(ipRuleStatus: IpRulesManager.IpRuleStatus) {
        val protocol = Protocol.getProtocolName(ipDetails.protocol).name
        val connRules = ConnectionRules(ipDetails.ipAddress, ipDetails.port, protocol)

        Log.d(LOG_TAG_FIREWALL,
              "Apply ip rule for ${connRules.ipAddress}, ${FirewallRuleset.RULE2.name}")
        IpRulesManager.updateRule(UID_EVERYBODY, connRules.ipAddress, ipRuleStatus)
    }

    private fun getAppName(): String {
        val appNames = FirewallManager.getAppNamesByUid(ipDetails.uid)

        val packageCount = appNames.count()
        return if (packageCount >= 2) {
            getString(R.string.ctbs_app_other_apps, appNames[0], packageCount.minus(1).toString())
        } else {
            appNames[0]
        }
    }

    private fun showFirewallDialog(packageList: List<String>, status: FirewallManager.AppStatus,
                                   prevStatus: FirewallManager.AppStatus) {

        val builderSingle: android.app.AlertDialog.Builder = android.app.AlertDialog.Builder(
            requireContext())

        builderSingle.setIcon(R.drawable.spinner_firewall)
        val count = packageList.count()
        builderSingle.setTitle(
            this.getString(R.string.ctbs_block_other_apps, getAppName(), count.toString()))

        val arrayAdapter = ArrayAdapter<String>(requireContext(),
                                                android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle.setPositiveButton(status.name) { dialog: DialogInterface, _: Int ->
            // call dialog.dismiss() before updating the details.
            // Without dismissing this dialog, the bottom sheet dialog is not
            // refreshing/updating its UI. One way is to dismiss the dialog
            // before updating the UI.
            // b.root.invalidate()/ b.root.notify didn't help in this case.
            dialog.dismiss()

            Log.d(LOG_TAG_FIREWALL, "Apply firewall rule for uid: ${ipDetails.uid}, ${status.name}")
            FirewallManager.updateFirewallStatus(ipDetails.uid, status,
                                                 FirewallManager.ConnectionStatus.BOTH)
            updateFirewallRulesUi(status)
        }.setNeutralButton(
            this.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
            // move the toggle to the previous state. remove and re-assign the
            // button-checked listener so that reverting the check status won't invoke the listener
            b.bsConnBlockAppToggleGroup.removeOnButtonCheckedListener(appToggleGroupListener)
            updateFirewallRulesUi(prevStatus)
            b.bsConnBlockAppToggleGroup.addOnButtonCheckedListener(appToggleGroupListener)
        }

        val alertDialog: android.app.AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }
}
