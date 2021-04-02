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
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Html
import android.text.Html.FROM_HTML_MODE_LEGACY
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
import androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.BottomSheetConnTrackBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.ThrowingHandler
import com.celzero.bravedns.util.Utilities
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

class ConnTrackerBottomSheetFragment(private var contextVal: Context, private var ipDetails: ConnectionTracker) : BottomSheetDialogFragment() {
    private var _binding: BottomSheetConnTrackBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private lateinit var firewallRules: FirewallRules

    private var isAppBlocked: Boolean = false
    private var isRuleBlocked: Boolean = false
    private var isRuleUniversal: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetConnTrackBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firewallRules = FirewallRules.getInstance()
    }

    companion object {
        const val UNIVERSAL_RULES_UID = -1000
    }

    override fun getTheme(): Int = if (persistentState.theme == 0) {
        if (isDarkThemeOn()) {
            R.style.BottomSheetDialogThemeTrueBlack
        } else {
            R.style.BottomSheetDialogThemeWhite
        }
    } else if (persistentState.theme == 1) {
        R.style.BottomSheetDialogThemeWhite
    } else if (persistentState.theme == 2) {
        R.style.BottomSheetDialogTheme
    } else {
        R.style.BottomSheetDialogThemeTrueBlack
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private val appInfoRepository: AppInfoRepository by inject()
    private val blockedConnectionsRepository: BlockedConnectionsRepository by inject()
    private val categoryInfoRepository: CategoryInfoRepository by inject()
    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }


    private fun initView() {
        val protocol = Protocol.getProtocolName(ipDetails.protocol).name

        b.bsConnConnectionTypeHeading.text = ipDetails.ipAddress!!
        b.bsConnConnectionFlag.text = ipDetails.flag.toString()

        var text = getString(R.string.bsct_block)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            b.bsConnBlockAppTxt.text = Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
        } else {
            b.bsConnBlockAppTxt.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }

        text = getString(R.string.bsct_block_all)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            b.bsConnBlockConnAllTxt.text = Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
        } else {
            b.bsConnBlockConnAllTxt.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }

        val time = DateUtils.getRelativeTimeSpanString(ipDetails.timeStamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)

        if (ipDetails.appName != getString(R.string.ctbs_unknown_app)) {
            try {
                val appArray = contextVal.packageManager.getPackagesForUid(ipDetails.uid)
                val appCount = (appArray?.size)?.minus(1)
                if(ipDetails.uid != 0) {
                    b.bsConnTrackAppName.text = ipDetails.appName + "      ❯"
                } else {
                    b.bsConnTrackAppName.text = ipDetails.appName
                }
                if (appArray != null) {
                    if (appArray.size > 2) {
                        b.bsConnTrackAppName.text = getString(R.string.ctbs_app_other_apps, ipDetails.appName, appCount.toString())
                        b.bsConnTrackAppKill.visibility = View.GONE
                    } else if (appArray.size == 2) {
                        b.bsConnTrackAppName.text = getString(R.string.ctbs_app_other_app, ipDetails.appName, appCount.toString())
                        b.bsConnTrackAppKill.visibility = View.GONE
                    }
                    b.bsConnTrackAppIcon.setImageDrawable(contextVal.packageManager.getApplicationIcon(appArray[0]!!))
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Package Not Found - " + e.message, e)
            }
        } else {
            b.bsConnTrackAppName.text = getString(R.string.ctbs_unknown_app)
            b.bsConnTrackAppKill.visibility = View.GONE
            b.bsConnBlockedRule1Txt.text = getString(R.string.ctbs_rule_5)
            b.bsConnBlockAppTxt.text = contextVal.resources.getString(R.string.univ_block_unknown_connections)
        }

        val listBlocked = blockedConnectionsRepository.getAllBlockedConnectionsForUID(ipDetails.uid)
        listBlocked.forEach {
            if (it.ruleType == (BraveVPNService.BlockedRuleNames.RULE2.ruleName) && ipDetails.ipAddress.equals(it.ipAddress) && it.uid == UNIVERSAL_RULES_UID) {
                b.bsConnBlockConnAllSwitch.isChecked = true
            }
        }

        isAppBlocked = FirewallManager.checkInternetPermission(ipDetails.uid)
        val connRules = ConnectionRules(ipDetails.ipAddress!!, ipDetails.port, protocol)
        isRuleBlocked = firewallRules.checkRules(ipDetails.uid, connRules)
        isRuleUniversal = firewallRules.checkRules(UNIVERSAL_RULES_UID, connRules)

        b.bsConnBlockAppCheck.setOnCheckedChangeListener(null)
        b.bsConnBlockAppCheck.setOnClickListener {
            if (ipDetails.appName != getString(R.string.ctbs_unknown_app)) {
                firewallApp(FirewallManager.checkInternetPermission(ipDetails.uid))
            } else {
                if (DEBUG) Log.d(LOG_TAG, "setBlockUnknownConnections - ${b.bsConnBlockAppCheck.isChecked} ")
                persistentState.blockUnknownConnections = b.bsConnBlockAppCheck.isChecked
            }
        }

        if (ipDetails.isBlocked) {
            b.bsConnTrackAppKill.visibility = View.VISIBLE
            b.bsConnTrackAppKill.text = ipDetails.blockedByRule
            text = getString(R.string.bsct_conn_conn_desc_blocked, protocol, ipDetails.port.toString(), time)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                b.bsConnConnectionDetails.text = Html.fromHtml(text, FROM_HTML_MODE_COMPACT)
            } else {
                b.bsConnConnectionDetails.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }
        } else {
            text = getString(R.string.bsct_conn_conn_desc_allowed, protocol, ipDetails.port.toString(), time)
            b.bsConnTrackAppKill.visibility = View.GONE
            if (ipDetails.blockedByRule.equals(BraveVPNService.BlockedRuleNames.RULE7.ruleName)) {
                b.bsConnTrackAppKill.visibility = View.VISIBLE
                b.bsConnTrackAppKill.text = getString(R.string.ctbs_whitelisted)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                b.bsConnConnectionDetails.text = Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
            } else {
                b.bsConnConnectionDetails.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }
        }

        if (ipDetails.appName != getString(R.string.ctbs_unknown_app)) {
            b.bsConnBlockAppCheck.isChecked = isAppBlocked
        } else {
            b.bsConnBlockAppCheck.isChecked = persistentState.blockUnknownConnections
        }

        b.bsConnTrackAppKill.setOnClickListener {
            showDialogForInfo()
        }

        b.bsConnBlockConnAllSwitch.setOnCheckedChangeListener(null)
        b.bsConnBlockConnAllSwitch.setOnClickListener {
            if (isRuleUniversal) {
                if (DEBUG) Log.d(LOG_TAG, "Universal Remove - ${connRules.ipAddress}, ${BraveVPNService.BlockedRuleNames.RULE2.ruleName}")
                firewallRules.removeFirewallRules(UNIVERSAL_RULES_UID, connRules.ipAddress,  blockedConnectionsRepository)
                isRuleUniversal = false
                Utilities.showToastInMidLayout(contextVal, getString(R.string.ctbs_unblocked_app, connRules.ipAddress), Toast.LENGTH_SHORT)
            } else {
                if (DEBUG) Log.d(LOG_TAG, "Universal Add - ${connRules.ipAddress}, ${BraveVPNService.BlockedRuleNames.RULE2.ruleName}")
                firewallRules.addFirewallRules(UNIVERSAL_RULES_UID, connRules.ipAddress, BraveVPNService.BlockedRuleNames.RULE2.ruleName, blockedConnectionsRepository)
                isRuleUniversal = true
                Utilities.showToastInMidLayout(contextVal, getString(R.string.ctbs_block_connections, connRules.ipAddress), Toast.LENGTH_SHORT)
            }
            b.bsConnBlockConnAllSwitch.isChecked = isRuleUniversal
        }

        b.bsConnTrackAppNameHeader.setOnClickListener {
            try {
                val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
                if (appUIDList.size == 1) {
                    if (ipDetails.appName != null || ipDetails.appName!! != getString(R.string.ctbs_unknown_app)) {
                        val packageName = appInfoRepository.getPackageNameForAppName(ipDetails.appName!!)
                        appInfoForPackage(packageName)
                    } else {
                        Utilities.showToastInMidLayout(contextVal, getString(R.string.ctbs_app_info_not_available_toast), Toast.LENGTH_SHORT)
                    }
                } else {
                    Utilities.showToastInMidLayout(contextVal, getString(R.string.ctbs_app_info_not_available_toast), Toast.LENGTH_SHORT)
                }
            } catch (e: java.lang.Exception) {
                Utilities.showToastInMidLayout(contextVal, getString(R.string.ctbs_app_info_not_available_toast), Toast.LENGTH_SHORT)
            }
        }

        b.bsConnTrackAppClearRules.setOnClickListener {
            clearAppRules()
        }
    }

    private fun appInfoForPackage(packageName: String) {
        if(DEBUG) Log.d(LOG_TAG, "appInfoForPackage: $packageName")
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            //Open the generic Apps page:
            Log.w(LOG_TAG,"Exception while opening app info: ${e.message}",e)
            val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun firewallApp(isBlocked: Boolean) {
        var blockAllApps = false
        val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
        if (!appUIDList.isNullOrEmpty()) {
            if (appUIDList[0].whiteListUniv1) {
                Utilities.showToastInMidLayout(contextVal, getString(R.string.bsct_firewall_not_available_whitelist), Toast.LENGTH_SHORT)
                b.bsConnBlockAppCheck.isChecked = false
                return
            } else if (appUIDList[0].isExcluded) {
                Utilities.showToastInMidLayout(contextVal, getString(R.string.bsct_firewall_not_available_excluded), Toast.LENGTH_SHORT)
                b.bsConnBlockAppCheck.isChecked = false
                return
            }
            if (appUIDList.size > 1) {
                var title = getString(R.string.ctbs_block_other_apps, ipDetails.appName, appUIDList.size.toString())
                var positiveText = getString(R.string.ctbs_block_other_apps_positive_text, appUIDList.size.toString())
                if (isBlocked) {
                    title = getString(R.string.ctbs_unblock_other_apps, ipDetails.appName, appUIDList.size.toString())
                    positiveText = getString(R.string.ctbs_unblock_other_apps_positive_text, appUIDList.size.toString())
                }
                blockAllApps = showDialog(appUIDList, title, positiveText)
            }
            if (appUIDList.size <= 1 || blockAllApps) {
                val uid = ipDetails.uid
                CoroutineScope(Dispatchers.IO).launch {
                    appUIDList.forEach {
                        persistentState.modifyAllowedWifi(it.packageInfo, isBlocked)
                        FirewallManager.updateAppInternetPermission(it.packageInfo, isBlocked)
                        FirewallManager.updateAppInternetPermissionByUID(it.uid, isBlocked)
                        categoryInfoRepository.updateNumberOfBlocked(it.appCategory, !isBlocked)
                        if (DEBUG) Log.d(LOG_TAG, "Category block executed with blocked as $isBlocked")
                    }
                    appInfoRepository.updateInternetForUID(uid, isBlocked)
                }
            } else {
                b.bsConnBlockAppCheck.isChecked = isBlocked
            }
        } else {
            Utilities.showToastInMidLayout(contextVal, getString(R.string.firewall_app_info_not_available), Toast.LENGTH_SHORT)
            b.bsConnBlockAppCheck.isChecked = false
        }
    }

    private fun clearAppRules() {
        val blockAllApps: Boolean
        val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
        if (appUIDList.size > 1) {
            val title = getString(R.string.ctbs_clear_rules_desc, ipDetails.appName, appUIDList.size.toString())
            val positiveText = getString(R.string.ctbs_clear_rules_positive_text)
            blockAllApps = showDialog(appUIDList, title, positiveText)
            if (blockAllApps) {
                firewallRules.clearFirewallRules(ipDetails.uid, blockedConnectionsRepository)
                Utilities.showToastInMidLayout(contextVal, getString(R.string.bsct_rules_cleared_toast), Toast.LENGTH_SHORT)
            }
        } else {
            showAlertForClearRules()
        }
    }

    private fun showAlertForClearRules() {
        val builder = AlertDialog.Builder(contextVal)
        //set title for alert dialog
        .setTitle(R.string.bsct_alert_message_clear_rules_heading)
        //set message for alert dialog

        builder.setMessage(R.string.bsct_alert_message_clear_rules)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(getString(R.string.ctbs_clear_rules_dialog_positive)) { _, _ ->
            firewallRules.clearFirewallRules(ipDetails.uid, blockedConnectionsRepository)
            Utilities.showToastInMidLayout(contextVal, getString(R.string.bsct_rules_cleared_toast), Toast.LENGTH_SHORT)
        }

        //performing negative action
        builder.setNeutralButton(getString(R.string.ctbs_clear_rules_dialog_negative)) { _, _ ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
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
    private fun showDialog(packageList: List<AppInfo>, title: String, positiveText: String): Boolean {
        // TODO Change the handler logic into some other
        val handler: Handler = ThrowingHandler()

        val packageNameList: List<String> = packageList.map { it.appName }
        var proceedBlocking = false

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(contextVal)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        builderSingle.setTitle(title)
        val positiveTxt: String = positiveText

        val arrayAdapter = ArrayAdapter<String>(contextVal, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageNameList)
        builderSingle.setCancelable(false)
        builderSingle.setItems(packageNameList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
            proceedBlocking = true
            handler.sendMessage(handler.obtainMessage())
        }.setNeutralButton(getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
            handler.sendMessage(handler.obtainMessage())
            proceedBlocking = false
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.setCancelable(false)
        try {
            Looper.loop()
        } catch (e2: java.lang.RuntimeException) {
        }

        return proceedBlocking
    }

}
