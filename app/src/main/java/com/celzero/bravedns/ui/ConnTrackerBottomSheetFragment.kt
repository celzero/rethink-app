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
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.automaton.FirewallRules.UID_EVERYBODY
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.BottomSheetConnTrackBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.DNSLogTracker
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.FirewallRuleset.Companion.getFirewallRule
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities.Companion.getIcon
import com.celzero.bravedns.util.Utilities.Companion.getPackageInfoForUid
import com.celzero.bravedns.util.Utilities.Companion.isValidAppName
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

/**
 * Renders network logs from where user can also set firewall rules.
 */
class ConnTrackerBottomSheetFragment(private var ipDetails: ConnectionTracker) :
        BottomSheetDialogFragment(), KoinComponent {

    private var _binding: BottomSheetConnTrackBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private var canNav: Boolean = false
    private val dnsLogTracker by inject<DNSLogTracker>()

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

    private val blockedConnectionsRepository: BlockedConnectionsRepository by inject()
    private val persistentState by inject<PersistentState>()

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
        val protocol = Protocol.getProtocolName(ipDetails.protocol).name
        val time = DateUtils.getRelativeTimeSpanString(ipDetails.timeStamp,
                                                       System.currentTimeMillis(),
                                                       DateUtils.MINUTE_IN_MILLIS,
                                                       DateUtils.FORMAT_ABBREV_RELATIVE)

        b.bsConnConnectionTypeHeading.text = ipDetails.ipAddress
        b.bsConnConnectionFlag.text = ipDetails.flag.toString()

        b.bsConnBlockAppTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block))
        b.bsConnBlockConnAllTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_all))

        val packageInfos = try {
            getPackageInfoForUid(requireContext(), ipDetails.uid)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG_FIREWALL, "Package Not Found: " + e.message, e)
            null
        }

        if (packageInfos != null) {
            b.bsConnBlockAppCheck.isChecked = FirewallManager.isUidFirewalled(ipDetails.uid)
            val appCount = (packageInfos.size).minus(1)
            b.bsConnTrackAppName.text = if (packageInfos.size >= 2) {
                getString(R.string.ctbs_app_other_apps, ipDetails.appName, appCount.toString())
            } else if (AndroidUidConfig.isUidAppRange(ipDetails.uid)) {
                canNav = true
                ipDetails.appName + "      ❯"
            } else {
                ipDetails.appName
            }
            b.bsConnTrackAppIcon.setImageDrawable(
                getIcon(requireContext(), packageInfos[0], ipDetails.appName))
        } else { // No info on the uid, Check if its in non-app category else treat as unknown.
            handleNonApp()
        }

        val connRules = ConnectionRules(ipDetails.ipAddress ?: UNSPECIFIED_IP, ipDetails.port,
                                        protocol)
        b.bsConnBlockConnAllSwitch.isChecked = FirewallRules.hasRule(UID_EVERYBODY, connRules)

        ipDetails.ipAddress?.let {
            val ipToDomain = dnsLogTracker.ipDomainLookup.getIfPresent(it)
            ipToDomain?.let {
                b.bsConnConnectionDetails.visibility = View.VISIBLE
                b.bsConnConnectionDetails.text = ipToDomain.fqdn
            }
        }

        if (!ipDetails.blockedByRule.isNullOrBlank()) {
            val rule = ipDetails.blockedByRule!!
            b.bsConnTrackAppKill.text = getFirewallRule(rule)?.title?.let {
                getString(it)
            }
        } else {
            b.bsConnTrackAppKill.text = getString(R.string.firewall_rule_no_rule)
        }

        val protocolDetails = "$protocol/${ipDetails.port}"

        if (ipDetails.isBlocked) {
            b.bsConnTrackPortDetailChip.text = getString(R.string.bsct_conn_desc_blocked,
                                                         protocolDetails, time)
        } else {
            b.bsConnTrackPortDetailChip.text = getString(R.string.bsct_conn_desc_allowed,
                                                         protocolDetails, time)
        }
        lightenUpChip()
    }

    private fun lightenUpChip() {
        // Load icons for the firewall rules if available
        b.bsConnTrackAppKill.chipIcon = ContextCompat.getDrawable(requireContext(),
                                                                  FirewallRuleset.getRulesIcon(
                                                                      ipDetails.blockedByRule))
        if (ipDetails.isBlocked) {
            b.bsConnTrackAppKill.setTextColor(fetchColor(R.attr.chipTextNegative))
            val colorFilter = PorterDuffColorFilter(fetchColor(R.attr.chipTextNegative),
                                                    PorterDuff.Mode.SRC_IN)
            b.bsConnTrackAppKill.chipBackgroundColor = ColorStateList.valueOf(
                fetchColor(R.attr.chipBgColorNegative))
            b.bsConnTrackAppKill.chipIcon?.colorFilter = colorFilter
        } else {
            b.bsConnTrackAppKill.setTextColor(fetchColor(R.attr.chipTextPositive))
            val colorFilter = PorterDuffColorFilter(fetchColor(R.attr.chipTextPositive),
                                                    PorterDuff.Mode.SRC_IN)
            b.bsConnTrackAppKill.chipBackgroundColor = ColorStateList.valueOf(
                fetchColor(R.attr.chipBgColorPositive))
            b.bsConnTrackAppKill.chipIcon?.colorFilter = colorFilter
        }
    }

    private fun fetchColor(attr: Int): Int {
        val typedValue = TypedValue()
        val a: TypedArray = requireContext().obtainStyledAttributes(typedValue.data,
                                                                    intArrayOf(attr))
        val color = a.getColor(0, 0)
        a.recycle()
        return color
    }

    private fun handleNonApp() {
        val app = FirewallManager.getAppInfoByUid(ipDetails.uid)
        if (app == null) {
            b.bsConnBlockAppCheck.isChecked = persistentState.blockUnknownConnections
            b.bsConnTrackAppName.text = getString(R.string.ctbs_unknown_app)
            b.bsConnBlockedRule1Txt.text = getString(R.string.ctbs_rule_5)
            b.bsConnBlockAppTxt.text = requireContext().resources.getString(
                R.string.univ_block_unknown_connections)
        } else {
            b.bsConnBlockAppCheck.isChecked = !app.isInternetAllowed
            b.bsConnTrackAppName.text = app.appName
        }
    }

    private fun setupClickListeners() {
        val protocol = Protocol.getProtocolName(ipDetails.protocol).name
        val connRules = ConnectionRules(ipDetails.ipAddress ?: UNSPECIFIED_IP, ipDetails.port,
                                        protocol)

        b.bsConnBlockAppCheck.setOnCheckedChangeListener(null)
        b.bsConnBlockAppCheck.setOnClickListener {
            if (isValidAppName(ipDetails.appName)) {
                firewallApp(ipDetails.uid)
            } else {
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "Unknown app, universal firewall settings(block unknown app): ${b.bsConnBlockAppCheck.isChecked} ")
                persistentState.blockUnknownConnections = b.bsConnBlockAppCheck.isChecked
            }
        }

        b.bsConnTrackAppKill.setOnClickListener {
            showFirewallRulesDialog(ipDetails.blockedByRule)
        }

        b.bsConnBlockConnAllSwitch.setOnCheckedChangeListener(null)
        b.bsConnBlockConnAllSwitch.setOnClickListener {
            if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                             "Universal isRemove? isRuleUniversal: ${connRules.ipAddress}, ${FirewallRuleset.RULE2.id}")
            if (!b.bsConnBlockConnAllSwitch.isChecked) {
                FirewallRules.removeFirewallRules(UID_EVERYBODY, connRules.ipAddress,
                                                  blockedConnectionsRepository)
                Utilities.showToastUiCentered(requireContext(),
                                              getString(R.string.ctbs_unblocked_app,
                                                        connRules.ipAddress), Toast.LENGTH_SHORT)
            } else {
                FirewallRules.addFirewallRules(UID_EVERYBODY, connRules.ipAddress,
                                               FirewallRuleset.RULE2.id,
                                               blockedConnectionsRepository)
                Utilities.showToastUiCentered(requireContext(),
                                              getString(R.string.ctbs_block_connections,
                                                        connRules.ipAddress), Toast.LENGTH_SHORT)
            }
        }

        b.bsConnTrackAppNameHeader.setOnClickListener {
            try {
                if (canNav) {
                    val packageName = FirewallManager.getPackageNameByUid(ipDetails.uid)

                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                    return@setOnClickListener
                } else {
                    // fall-through
                }
            } catch (e: Exception) { // ActivityNotFoundException | NullPointerException
                Log.w(LOG_TAG_FIREWALL, "Failure calling app info: ${e.message}", e)
            }

            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.ctbs_app_info_not_available_toast),
                                          Toast.LENGTH_SHORT)

        }
    }

    private fun firewallApp(uid: Int) {
        var isBlocked = false
        when (FirewallManager.appStatus(uid)) {
            FirewallManager.AppStatus.WHITELISTED -> {
                showToast(getString(R.string.bsct_firewall_not_available_whitelist))
                b.bsConnBlockAppCheck.isChecked = false
                return
            }
            FirewallManager.AppStatus.EXCLUDED -> {
                showToast(getString(R.string.bsct_firewall_not_available_excluded))
                b.bsConnBlockAppCheck.isChecked = false
                return
            }
            FirewallManager.AppStatus.UNKNOWN -> {
                showToast(getString(R.string.firewall_app_info_not_available))
                b.bsConnBlockAppCheck.isChecked = false
                return
            }
            FirewallManager.AppStatus.BLOCKED -> {
                isBlocked = true
            }
            FirewallManager.AppStatus.ALLOWED -> {
                isBlocked = false
            }
        }

        val appUIDList = FirewallManager.getAppNamesByUid(ipDetails.uid)

        if (appUIDList.size <= 1) {
            updateDetails(ipDetails.uid, isBlocked)
            return
        }

        if (appUIDList.size > 1) {
            var title = getString(R.string.ctbs_block_other_apps, ipDetails.appName,
                                  appUIDList.size.toString())
            var positiveText = getString(R.string.ctbs_block_other_apps_positive_text,
                                         appUIDList.size.toString())
            if (isBlocked) {
                title = getString(R.string.ctbs_unblock_other_apps, ipDetails.appName,
                                  appUIDList.size.toString())
                positiveText = getString(R.string.ctbs_unblock_other_apps_positive_text,
                                         appUIDList.size.toString())
            }
            showFirewallDialog(appUIDList, title, positiveText, isBlocked)
            b.bsConnBlockAppCheck.isChecked = isBlocked
        }
    }

    private fun updateDetails(uid: Int, isBlocked: Boolean) {
        b.bsConnBlockAppCheck.isChecked = !isBlocked
        FirewallManager.updateFirewalledApps(uid, isBlocked)
    }

    private fun showToast(message: String) {
        Utilities.showToastUiCentered(requireContext(), message, Toast.LENGTH_SHORT)
    }

    private fun clearAppRules() {
        val appUIDList = FirewallManager.getAppNamesByUid(ipDetails.uid)
        if (appUIDList.size <= 1) {
            promptClearRulesConfirmation()
            return
        }

        val title = getString(R.string.ctbs_clear_rules_desc, ipDetails.appName,
                              appUIDList.size.toString())
        val positiveText = getString(R.string.ctbs_clear_rules_positive_text)

        showClearRulesDialog(appUIDList, title, positiveText)
    }

    private fun promptClearRulesConfirmation() {
        val builder = AlertDialog.Builder(requireContext()).setTitle(
            R.string.bsct_alert_message_clear_rules_heading)
        builder.setMessage(R.string.bsct_alert_message_clear_rules)
        builder.setPositiveButton(getString(R.string.ctbs_clear_rules_dialog_positive)) { _, _ ->
            FirewallRules.clearFirewallRules(ipDetails.uid, blockedConnectionsRepository)
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.bsct_rules_cleared_toast),
                                          Toast.LENGTH_SHORT)
        }

        builder.setNeutralButton(getString(R.string.ctbs_clear_rules_dialog_negative)) { _, _ ->
        }
        builder.setCancelable(false)
        builder.create().show()
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

    private fun showFirewallDialog(packageList: List<String>, title: String, positiveText: String,
                                   isBlocked: Boolean) {

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(requireContext())

        builderSingle.setIcon(R.drawable.spinner_firewall)
        builderSingle.setTitle(title)
        val positiveTxt: String = positiveText

        val arrayAdapter = ArrayAdapter<String>(requireContext(),
                                                android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { di: DialogInterface, _: Int ->
            // call dialog.dismiss() before updating the details.
            // Without dismissing this dialog, the bottom sheet dialog is not
            // refreshing/updating its UI. One way is to dismiss the dialog
            // before updating the UI.
            // b.root.invalidate()/ b.root.notify didn't help in this case.
            di.dismiss()
            updateDetails(ipDetails.uid, isBlocked)
        }.setNeutralButton(
            getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
        }

        val alertDialog: AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    private fun showClearRulesDialog(packageList: List<String>, title: String,
                                     positiveText: String) {

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(requireContext())

        builderSingle.setIcon(R.drawable.spinner_firewall)
        builderSingle.setTitle(title)
        val positiveTxt: String = positiveText

        val arrayAdapter = ArrayAdapter<String>(requireContext(),
                                                android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
        builderSingle.setCancelable(false)
        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
            FirewallRules.clearFirewallRules(ipDetails.uid, blockedConnectionsRepository)
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.bsct_rules_cleared_toast),
                                          Toast.LENGTH_SHORT)
        }.setNeutralButton(
            getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
        }

        val alertDialog: AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

}
