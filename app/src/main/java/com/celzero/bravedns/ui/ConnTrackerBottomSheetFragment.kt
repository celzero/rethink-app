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
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.automaton.FirewallRules.UID_EVERYBODY
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.BottomSheetConnTrackBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities.Companion.isValidAppName
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * ConnectionTrackerBottomSheetFragment
 * Displays the details about the network logs. Renders in NetworkMonitor UI as bottom sheet.
 * Fetches the details of the network logs, users can apply the rules based on the request or
 * based on the app.
 *
 * TODO : Need to move the strings to strings.xml file.
 */

class ConnTrackerBottomSheetFragment(private var contextVal: Context,
                                     private var ipDetails: ConnectionTracker) :
        BottomSheetDialogFragment() {
    private var _binding: BottomSheetConnTrackBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private var isAppBlocked: Boolean = false
    private var isRuleBlocked: Boolean = false
    private var isRuleUniversal: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetConnTrackBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun getTheme(): Int = Utilities.getBottomsheetCurrentTheme(isDarkThemeOn())

    private val appInfoRepository: AppInfoRepository by inject()
    private val blockedConnectionsRepository: BlockedConnectionsRepository by inject()
    private val categoryInfoRepository: CategoryInfoRepository by inject()
    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }


    private fun initView() {
        val protocol = Protocol.getProtocolName(ipDetails.protocol).name
        isAppBlocked = FirewallManager.checkInternetPermission(ipDetails.uid)
        val connRules = ConnectionRules(ipDetails.ipAddress!!, ipDetails.port, protocol)
        isRuleBlocked = FirewallRules.checkRules(ipDetails.uid, connRules)
        isRuleUniversal = FirewallRules.checkRules(UID_EVERYBODY, connRules)

        displayDetails(protocol)

        setupClickListeners(connRules)
    }

    private fun displayDetails(protocol: String) {
        b.bsConnConnectionTypeHeading.text = ipDetails.ipAddress!!
        b.bsConnConnectionFlag.text = ipDetails.flag.toString()

        var text = getString(R.string.bsct_block)
        b.bsConnBlockAppTxt.text = updateHtmlEncodedText(text)

        text = getString(R.string.bsct_block_all)
        b.bsConnBlockConnAllTxt.text = updateHtmlEncodedText(text)

        val time = DateUtils.getRelativeTimeSpanString(ipDetails.timeStamp,
                                                       System.currentTimeMillis(),
                                                       DateUtils.MINUTE_IN_MILLIS,
                                                       DateUtils.FORMAT_ABBREV_RELATIVE)


        if (isValidAppName(ipDetails.appName)) {
            b.bsConnBlockAppCheck.isChecked = isAppBlocked
            try {
                val appArray = contextVal.packageManager.getPackagesForUid(ipDetails.uid)
                val appCount = (appArray?.size)?.minus(1)
                if (AndroidUidConfig.isUidAppRange(ipDetails.uid)) {
                    b.bsConnTrackAppName.text = ipDetails.appName + "      ❯"
                } else {
                    b.bsConnTrackAppName.text = ipDetails.appName
                }
                if (appArray != null) {
                    if (appArray.size >= 2) {
                        b.bsConnTrackAppName.text = getString(R.string.ctbs_app_other_apps,
                                                              ipDetails.appName,
                                                              appCount.toString())
                        b.bsConnTrackAppKill.visibility = View.GONE
                    }
                    b.bsConnTrackAppIcon.setImageDrawable(
                        contextVal.packageManager.getApplicationIcon(appArray[0]!!))
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(LOG_TAG_FIREWALL, "Package Not Found - " + e.message, e)
            }
        } else {
            b.bsConnBlockAppCheck.isChecked = persistentState.blockUnknownConnections
            b.bsConnTrackAppName.text = getString(R.string.ctbs_unknown_app)
            b.bsConnTrackAppKill.visibility = View.GONE
            b.bsConnBlockedRule1Txt.text = getString(R.string.ctbs_rule_5)
            b.bsConnBlockAppTxt.text = contextVal.resources.getString(
                R.string.univ_block_unknown_connections)
        }

        val listBlocked = blockedConnectionsRepository.getAllBlockedConnectionsForUID(ipDetails.uid)
        listBlocked.forEach {
            if (FirewallRuleset.RULE2.ruleName == it.ruleType && ipDetails.ipAddress.equals(
                    it.ipAddress) && it.uid == UID_EVERYBODY) {
                b.bsConnBlockConnAllSwitch.isChecked = true
            }
        }

        if (ipDetails.isBlocked) {
            b.bsConnTrackAppKill.visibility = View.VISIBLE
            b.bsConnTrackAppKill.text = ipDetails.blockedByRule
            text = getString(R.string.bsct_conn_conn_desc_blocked, protocol,
                             ipDetails.port.toString(), time)
            b.bsConnConnectionDetails.text = updateHtmlEncodedText(text)
        } else {
            text = getString(R.string.bsct_conn_conn_desc_allowed, protocol,
                             ipDetails.port.toString(), time)
            b.bsConnTrackAppKill.visibility = View.GONE
            //FIXME - #306 - Compare the enum instead of the string value of the enum
            if (FirewallRuleset.RULE7.ruleName == ipDetails.blockedByRule) {
                b.bsConnTrackAppKill.visibility = View.VISIBLE
                b.bsConnTrackAppKill.text = getString(R.string.ctbs_whitelisted)
            }
            b.bsConnConnectionDetails.text = updateHtmlEncodedText(text)
        }
    }

    private fun setupClickListeners(connRules: ConnectionRules) {
        b.bsConnBlockAppCheck.setOnCheckedChangeListener(null)
        b.bsConnBlockAppCheck.setOnClickListener {
            if (isValidAppName(ipDetails.appName)) {
                firewallApp(FirewallManager.checkInternetPermission(ipDetails.uid))
            } else {
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "setBlockUnknownConnections - ${b.bsConnBlockAppCheck.isChecked} ")
                persistentState.blockUnknownConnections = b.bsConnBlockAppCheck.isChecked
            }
        }

        b.bsConnTrackAppKill.setOnClickListener {
            showDialogForInfo()
        }

        b.bsConnBlockConnAllSwitch.setOnCheckedChangeListener(null)
        b.bsConnBlockConnAllSwitch.setOnClickListener {
            if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                             "Universal isRemove? isRuleUniversal - ${connRules.ipAddress}, ${FirewallRuleset.RULE2.ruleName}")
            if (isRuleUniversal) {
                FirewallRules.removeFirewallRules(UID_EVERYBODY, connRules.ipAddress,
                                                  blockedConnectionsRepository)
                isRuleUniversal = false
                Utilities.showToastUiCentered(contextVal, getString(R.string.ctbs_unblocked_app,
                                                                    connRules.ipAddress),
                                              Toast.LENGTH_SHORT)
            } else {
                FirewallRules.addFirewallRules(UID_EVERYBODY, connRules.ipAddress,
                                               FirewallRuleset.RULE2.ruleName,
                                               blockedConnectionsRepository)
                isRuleUniversal = true
                Utilities.showToastUiCentered(contextVal, getString(R.string.ctbs_block_connections,
                                                                    connRules.ipAddress),
                                              Toast.LENGTH_SHORT)
            }
            b.bsConnBlockConnAllSwitch.isChecked = isRuleUniversal
        }

        b.bsConnTrackAppNameHeader.setOnClickListener {
            val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)

            if (appUIDList.size != 1 || ipDetails.appName.isNullOrEmpty() || !isValidAppName(
                    ipDetails.appName)) {
                Utilities.showToastUiCentered(contextVal,
                                              getString(R.string.ctbs_app_info_not_available_toast),
                                              Toast.LENGTH_SHORT)
                return@setOnClickListener
            }

            val packageName = appInfoRepository.getPackageNameForUid(ipDetails.uid)

            launchSettingsAppInfo(packageName)
        }

        b.bsConnTrackAppClearRules.setOnClickListener {
            clearAppRules()
        }
    }

    private fun launchSettingsAppInfo(packageName: String) {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "appInfoForPackage: $packageName")
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            //Open the generic Apps page
            Log.w(LOG_TAG_FIREWALL, "Failure calling app info: ${e.message}", e)
            val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun firewallApp(isBlocked: Boolean) {
        val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)

        if (appUIDList.isNullOrEmpty()) {
            showToast(getString(R.string.firewall_app_info_not_available))
            b.bsConnBlockAppCheck.isChecked = false
            return
        }

        if (appUIDList[0].whiteListUniv1) {
            showToast(getString(R.string.bsct_firewall_not_available_whitelist))
            b.bsConnBlockAppCheck.isChecked = false
            return
        } else if (appUIDList[0].isExcluded) {
            showToast(getString(R.string.bsct_firewall_not_available_excluded))
            b.bsConnBlockAppCheck.isChecked = false
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
        }
        if (appUIDList.size <= 1) {
            updateDetails(appUIDList, isBlocked)
        } else {
            b.bsConnBlockAppCheck.isChecked = isBlocked
        }
    }

    private fun updateDetails(appUIDList: List<AppInfo>, isBlocked: Boolean) {
        val uid = ipDetails.uid
        b.bsConnBlockAppCheck.isChecked = !isBlocked
        CoroutineScope(Dispatchers.IO).launch {
            appUIDList.forEach {
                persistentState.modifyAllowedWifi(it.packageInfo, isBlocked)
                FirewallManager.updateAppInternetPermission(it.packageInfo, isBlocked)
                FirewallManager.updateAppInternetPermissionByUID(it.uid, isBlocked)
                categoryInfoRepository.updateNumberOfBlocked(it.appCategory, !isBlocked)
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "Category block executed with blocked as $isBlocked")
            }
            appInfoRepository.updateInternetForUID(uid, isBlocked)
        }
    }

    private fun showToast(message: String) {
        Utilities.showToastUiCentered(contextVal, message, Toast.LENGTH_SHORT)
    }

    private fun clearAppRules() {
        val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
        if (appUIDList.size <= 1) {
            showAlertForClearRules()
            return
        }

        val title = getString(R.string.ctbs_clear_rules_desc, ipDetails.appName,
                              appUIDList.size.toString())
        val positiveText = getString(R.string.ctbs_clear_rules_positive_text)

        showClearRulesDialog(appUIDList, title, positiveText)
    }

    private fun showAlertForClearRules() {
        val builder = AlertDialog.Builder(contextVal).setTitle(
            R.string.bsct_alert_message_clear_rules_heading)
        builder.setMessage(R.string.bsct_alert_message_clear_rules)
        builder.setPositiveButton(getString(R.string.ctbs_clear_rules_dialog_positive)) { _, _ ->
            FirewallRules.clearFirewallRules(ipDetails.uid, blockedConnectionsRepository)
            Utilities.showToastUiCentered(contextVal, getString(R.string.bsct_rules_cleared_toast),
                                          Toast.LENGTH_SHORT)
        }

        builder.setNeutralButton(getString(R.string.ctbs_clear_rules_dialog_negative)) { _, _ ->
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    private fun showDialogForInfo() {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(dialogBinding.root)
        val okBtn = dialogBinding.infoRulesDialogCancelImg
        val descText = dialogBinding.infoRulesDialogRulesDesc

        var text = getString(R.string.bsct_conn_rule_explanation)
        text = text.replace("\n", "<br /><br />")
        val styledText = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        descText.text = styledText

        okBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()

    }

    /**
     *TODO : Come up with better way to handle the dialog instead of using the handlers.
     */
    private fun showFirewallDialog(packageList: List<AppInfo>, title: String, positiveText: String,
                                   isBlocked: Boolean) {

        val packageNameList: List<String> = packageList.map { it.appName }

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(contextVal)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        builderSingle.setTitle(title)
        val positiveTxt: String = positiveText

        val arrayAdapter = ArrayAdapter<String>(contextVal,
                                                android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageNameList)
        builderSingle.setItems(packageNameList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { di: DialogInterface, _: Int ->
            // call dialog.dismiss() before updating the details.
            // Without dismissing this dialog, the bottom sheet dialog is not
            // refreshing/updating its UI. One way is to dismiss the dialog
            // before updating the UI.
            // b.root.invalidate()/ b.root.notify didn't help in this case.
            di.dismiss()
            updateDetails(packageList, isBlocked)
        }.setNeutralButton(
            getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.setCancelable(false)

    }

    private fun showClearRulesDialog(packageList: List<AppInfo>, title: String,
                                     positiveText: String) {

        val packageNameList: List<String> = packageList.map { it.appName }

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(contextVal)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        builderSingle.setTitle(title)
        val positiveTxt: String = positiveText

        val arrayAdapter = ArrayAdapter<String>(contextVal,
                                                android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageNameList)
        builderSingle.setCancelable(false)
        builderSingle.setItems(packageNameList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
            FirewallRules.clearFirewallRules(ipDetails.uid, blockedConnectionsRepository)
            Utilities.showToastUiCentered(contextVal, getString(R.string.bsct_rules_cleared_toast),
                                          Toast.LENGTH_SHORT)
        }.setNeutralButton(
            getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.setCancelable(false)
    }

}
