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
import com.celzero.bravedns.adapter.FirewallStatusSpinnerAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.databinding.BottomSheetConnTrackBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.FirewallRuleset.Companion.getFirewallRule
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import com.celzero.bravedns.util.Utilities.Companion.getIcon
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

class ConnTrackerBottomSheetFragment(private var ipDetails: ConnectionTracker) :
        BottomSheetDialogFragment(), KoinComponent {

    private var _binding: BottomSheetConnTrackBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        b.bsConnConnectionTypeHeading.text = ipDetails.ipAddress
        b.bsConnConnectionFlag.text = ipDetails.flag

        b.bsConnBlockAppTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block))
        b.bsConnBlockConnAllTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_app))

        // updates the application name and other details
        updateAppDetails()
        // setup click and item selected listeners
        setupClickListeners()
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

    override fun onResume() {
        super.onResume()
        // updates the app firewall's button
        updateFirewallRulesUi(FirewallManager.appStatus(ipDetails.uid),
                              FirewallManager.connectionStatus(ipDetails.uid))
    }

    private fun updateDnsIfAvailable() {
        if (ipDetails.dnsQuery.isNullOrEmpty()) {
            b.bsConnDnsCacheText.visibility = View.GONE
            return
        }

        b.bsConnDnsCacheText.visibility = View.VISIBLE
        b.bsConnDnsCacheText.text = ipDetails.dnsQuery
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
                getString(R.string.ctbs_app_other_apps, appNames[0],
                          appCount.minus(1).toString()) + "      ❯"
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
            val colorFilter = PorterDuffColorFilter(
                fetchColor(requireContext(), R.attr.chipTextNegative), PorterDuff.Mode.SRC_IN)
            b.bsConnTrackAppKill.chipBackgroundColor = ColorStateList.valueOf(
                fetchColor(requireContext(), R.attr.chipBgColorNegative))
            b.bsConnTrackAppKill.chipIcon?.colorFilter = colorFilter
        } else {
            b.bsConnTrackAppKill.setTextColor(fetchColor(requireContext(), R.attr.chipTextPositive))
            val colorFilter = PorterDuffColorFilter(
                fetchColor(requireContext(), R.attr.chipTextPositive), PorterDuff.Mode.SRC_IN)
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

        b.bsConnTrackAppNameHeader.setOnClickListener {
            val ai = FirewallManager.getAppInfoByUid(ipDetails.uid)
            // case: app is uninstalled but still available in RethinkDNS database
            if (ai == null || ipDetails.uid == Constants.INVALID_UID) {
                showToastUiCentered(requireContext(), getString(R.string.ct_bs_app_info_error),
                                    Toast.LENGTH_SHORT)
                return@setOnClickListener
            }

            openAppDetailActivity(ipDetails.uid)
        }

        // spinner to show firewall rules
        b.bsConnFirewallSpinner.adapter = FirewallStatusSpinnerAdapter(requireContext(),
                                                                       FirewallManager.FirewallStatus.getLabel(
                                                                           requireContext()))
        b.bsConnFirewallSpinner.onItemSelectedListener = object :
                AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int,
                                        id: Long) {
                val iv = view.findViewById<AppCompatImageView>(R.id.spinner_icon)
                iv.visibility = View.VISIBLE
                val fStatus = FirewallManager.FirewallStatus.getStatusByLabel(position)
                val connStatus = FirewallManager.ConnectionStatus.getStatusByLabel(position)

                // no change, prev selection and current selection are same
                if (FirewallManager.appStatus(
                        ipDetails.uid) == fStatus && FirewallManager.connectionStatus(
                        ipDetails.uid) == connStatus) return

                Log.i(LOG_TAG_FIREWALL,
                      "Change in firewall rule for app uid: ${ipDetails.uid}, firewall status: $fStatus, conn status: $connStatus")
                applyFirewallRule(fStatus, connStatus)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.bsConnIpRuleSpinner.adapter = FirewallStatusSpinnerAdapter(requireContext(),
                                                                     IpRulesManager.IpRuleStatus.getLabel())
        b.bsConnIpRuleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int,
                                        id: Long) {
                val iv = view.findViewById<AppCompatImageView>(R.id.spinner_icon)
                iv.visibility = View.VISIBLE
                val fid = IpRulesManager.IpRuleStatus.getStatus(position)

                // no need to apply rule, prev selection and current selection are same
                if (IpRulesManager.getStatus(ipDetails.uid, ipDetails.ipAddress) == fid) return

                applyIpRule(fid)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    }

    private fun openAppDetailActivity(uid: Int) {
        val intent = Intent(requireContext(), AppInfoActivity::class.java)
        intent.putExtra(AppInfoActivity.UID_INTENT_NAME, uid)
        requireContext().startActivity(intent)
    }

    private fun updateFirewallRulesUi(firewallStatus: FirewallManager.FirewallStatus,
                                      connStatus: FirewallManager.ConnectionStatus) {
        // no need to update the state if it's untracked
        if (firewallStatus.isUntracked()) return

        when (firewallStatus) {
            FirewallManager.FirewallStatus.ALLOW -> {
                b.bsConnFirewallSpinner.setSelection(0, true)
            }
            FirewallManager.FirewallStatus.BLOCK -> {
                if (connStatus.both()) {
                    b.bsConnFirewallSpinner.setSelection(1, true)
                } else if (connStatus.wifi()) {
                    b.bsConnFirewallSpinner.setSelection(2, true)
                } else {
                    b.bsConnFirewallSpinner.setSelection(3, true)
                }
            }
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> {
                b.bsConnFirewallSpinner.setSelection(4, true)
            }
            FirewallManager.FirewallStatus.EXCLUDE -> {
                b.bsConnFirewallSpinner.setSelection(5, true)
            }
            else -> {
                // no-op
            }
        }

    }

    private fun updateIpRulesUi(uid: Int, ipAddress: String) {
        b.bsConnIpRuleSpinner.setSelection(IpRulesManager.getStatus(uid, ipAddress).id)
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

    private fun applyFirewallRule(firewallStatus: FirewallManager.FirewallStatus,
                                  connStatus: FirewallManager.ConnectionStatus) {
        val appNames = FirewallManager.getAppNamesByUid(ipDetails.uid)
        if (appNames.count() > 1) {
            val prevStatus = FirewallManager.appStatus(ipDetails.uid)
            showFirewallDialog(appNames, firewallStatus, prevStatus, connStatus)
            return
        }

        FirewallManager.updateFirewallStatus(ipDetails.uid, firewallStatus, connStatus)
        updateFirewallRulesUi(firewallStatus, connStatus)
    }

    private fun applyIpRule(ipRuleStatus: IpRulesManager.IpRuleStatus) {
        val protocol = Protocol.getProtocolName(ipDetails.protocol).name
        val connRules = ConnectionRules(ipDetails.ipAddress, ipDetails.port, protocol)

        Log.d(LOG_TAG_FIREWALL,
              "Apply ip rule for ${connRules.ipAddress}, ${FirewallRuleset.RULE2.name}")
        IpRulesManager.updateRule(ipDetails.uid, connRules.ipAddress, ipRuleStatus)
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

    private fun showFirewallDialog(packageList: List<String>,
                                   status: FirewallManager.FirewallStatus,
                                   prevStatus: FirewallManager.FirewallStatus,
                                   connStatus: FirewallManager.ConnectionStatus) {

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
            FirewallManager.updateFirewallStatus(ipDetails.uid, status, connStatus)
            updateFirewallRulesUi(status, connStatus)
        }.setNeutralButton(
            this.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
            updateFirewallRulesUi(prevStatus, FirewallManager.connectionStatus(ipDetails.uid))
        }

        val alertDialog: android.app.AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }
}
