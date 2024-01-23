/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import by.kirich1409.viewbindingdelegate.viewBinding
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppConnectionAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.databinding.ActivityAppDetailsBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.bottomsheet.RethinkListBottomSheet
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.VIEW_PAGER_SCREEN_TO_LOAD
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.UIUtils.updateHtmlEncodedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppInfoActivity :
    AppCompatActivity(R.layout.activity_app_details), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityAppDetailsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()

    private val ipRulesViewModel: CustomIpViewModel by viewModel()
    private val domainRulesViewModel: CustomDomainViewModel by viewModel()
    private val networkLogsViewModel: AppConnectionsViewModel by viewModel()

    private var uid: Int = INVALID_UID
    private lateinit var appInfo: AppInfo

    private var ipListUiState: Boolean = true
    private var firewallUiState: Boolean = false

    private var appStatus = FirewallManager.FirewallStatus.NONE
    private var connStatus = FirewallManager.ConnectionStatus.ALLOW

    private var showBypassToolTip: Boolean = true

    companion object {
        const val UID_INTENT_NAME = "UID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        uid = intent.getIntExtra(UID_INTENT_NAME, INVALID_UID)
        ipRulesViewModel.setUid(uid)
        domainRulesViewModel.setUid(uid)
        networkLogsViewModel.setUid(uid)
        init()
        observeNetworkLogSize()
        observeAppRules()
        setupClickListeners()
    }

    private fun observeAppRules() {
        ipRulesViewModel.ipRulesCount(uid).observe(this) { b.aadIpBlockHeader.text = it.toString() }

        domainRulesViewModel.domainRulesCount(uid).observe(this) {
            b.aadDomainBlockHeader.text = it.toString()
        }
    }

    private fun observeNetworkLogSize() {
        networkLogsViewModel.getConnectionsCount(uid).observe(this) {
            if (it == null) return@observe

            b.aadConnDetailDesc.text = getString(R.string.ada_ip_connection_count, it.toString())
            if (it == 0) {
                b.aadConnDetailDesc.text = getString(R.string.ada_ip_connection_count_zero)
                b.aadConnDetailRecycler.visibility = View.GONE
                b.aadConnDetailEmptyTxt.visibility = View.VISIBLE
                b.aadConnDetailSearchLl.visibility = View.GONE

                // toggle the state only when the firewall rules are not visible
                if (firewallUiState) {
                    toggleFirewallUiState(firewallUiState)
                    toggleNetworkLogState(ipListUiState)
                }
            } else {
                b.aadConnDetailRecycler.visibility = View.VISIBLE
                b.aadConnDetailEmptyTxt.visibility = View.GONE
                b.aadConnDetailSearchLl.visibility = View.VISIBLE
            }
        }
    }

    private fun init() {
        io {
            val appInfo = FirewallManager.getAppInfoByUid(uid)
            // case: app is uninstalled but still available in RethinkDNS database
            if (appInfo == null || uid == INVALID_UID) {
                uiCtx { showNoAppFoundDialog() }
                return@io
            }

            val packages = FirewallManager.getPackageNamesByUid(appInfo.uid)
            appStatus = FirewallManager.appStatus(appInfo.uid)
            connStatus = FirewallManager.connectionStatus(appInfo.uid)
            uiCtx {
                this.appInfo = appInfo

                b.aadAppDetailName.text = appName(packages.count())
                updateDataUsage()
                displayIcon(
                    Utilities.getIcon(this, appInfo.packageName, appInfo.appName),
                    b.aadAppDetailIcon
                )
                showNetworkLogsIfAny(appInfo.uid)

                // do not show the firewall status if the app is Rethink
                if (appInfo.packageName == this.packageName) {
                    b.aadFirewallStatus.visibility = View.GONE
                    hideFirewallStatusUi()
                    hideDomainBlockUi()
                    hideIpBlockUi()
                    return@uiCtx
                }

                // introduce this on v056
                // updateDnsDetails()
                updateFirewallStatusUi(appStatus, connStatus)
                toggleFirewallUiState(firewallUiState)
                toggleNetworkLogState(ipListUiState)
            }
        }
    }

    private fun hideFirewallStatusUi() {
        b.aadAppSettingsCard.visibility = View.GONE
    }

    private fun hideDomainBlockUi() {
        b.aadDomainBlockCard.visibility = View.GONE
    }

    private fun hideIpBlockUi() {
        b.aadIpBlockCard.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        b.aadConnDetailRecycler.adapter?.notifyDataSetChanged()
    }

    private fun openCustomIpScreen() {
        val intent = Intent(this, CustomRulesActivity::class.java)
        // this activity is either being started in a new task or bringing to the top an
        // existing task, then it will be launched as the front door of the task.
        // This will result in the application to have that task in the proper state.
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        intent.putExtra(VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.IP_RULES.screen)
        intent.putExtra(Constants.INTENT_UID, uid)
        startActivity(intent)
    }

    private fun openCustomDomainScreen() {
        val intent = Intent(this, CustomRulesActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        intent.putExtra(VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.DOMAIN_RULES.screen)
        intent.putExtra(Constants.INTENT_UID, uid)
        startActivity(intent)
    }

    private fun updateDnsDetails() {
        io {
            val isDnsEnabled = appConfig.isAppWiseDnsEnabled(uid)

            uiCtx {
                if (isDnsEnabled) {
                    enableDnsStatusUi()
                    return@uiCtx
                }

                disableDnsStatusUi()
            }
        }
    }

    private fun updateDataUsage() {
        val u = Utilities.humanReadableByteCount(appInfo.uploadBytes, true)
        val uploadBytes = getString(R.string.symbol_upload, u)
        val d = Utilities.humanReadableByteCount(appInfo.downloadBytes, true)
        val downloadBytes = getString(R.string.symbol_download, d)
        b.aadDataUsageStatus.text = getString(R.string.two_argument, uploadBytes, downloadBytes)
    }

    private fun updateFirewallStatusUi(
        firewallStatus: FirewallManager.FirewallStatus,
        connectionStatus: FirewallManager.ConnectionStatus
    ) {
        b.aadFirewallStatus.text =
            updateHtmlEncodedText(
                getString(
                    R.string.ada_firewall_status,
                    getFirewallText(firewallStatus, connectionStatus)
                )
            )

        when (firewallStatus) {
            FirewallManager.FirewallStatus.EXCLUDE -> {
                enableAppExcludedUi()
            }
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> {
                enableAppBypassedUi()
            }
            FirewallManager.FirewallStatus.ISOLATE -> {
                enableIsolateUi()
            }
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> {
                enableDnsFirewallBypassedUi()
            }
            FirewallManager.FirewallStatus.NONE -> {
                when (connectionStatus) {
                    FirewallManager.ConnectionStatus.ALLOW -> {
                        enableAllow()
                    }
                    else -> {
                        enableBlock(connectionStatus)
                    }
                }
                disableWhitelistExcludeUi()
            }
            FirewallManager.FirewallStatus.UNTRACKED -> {
                // no-op
            }
        }
    }

    private fun setupClickListeners() {

        b.aadConnDetailSearch.setOnQueryTextListener(this)

        b.aadAppInfoIcon.setOnClickListener {
            io {
                val packages = FirewallManager.getAppNamesByUid(appInfo.uid)
                uiCtx {
                    if (packages.count() == 1) {
                        openAndroidAppInfo(this, appInfo.packageName)
                    } else if (packages.count() > 1) {
                        showAppInfoDialog(packages)
                    } else {
                        showToastUiCentered(
                            this,
                            this.getString(R.string.ctbs_app_info_not_available_toast),
                            Toast.LENGTH_SHORT
                        )
                    }
                }
            }
        }

        TooltipCompat.setTooltipText(
            b.aadAppSettingsBypassDnsFirewall,
            getString(
                R.string.bypass_dns_firewall_tooltip,
                getString(R.string.ada_app_bypass_dns_firewall)
            )
        )

        b.aadAppSettingsBypassDnsFirewall.setOnClickListener {
            // show the tooltip only once when app is not bypassed (dns + firewall) earlier
            if (showBypassToolTip && appStatus == FirewallManager.FirewallStatus.NONE) {
                b.aadAppSettingsBypassDnsFirewall.performLongClick()
                showBypassToolTip = false
                return@setOnClickListener
            }

            if (appStatus == FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        }

        b.aadAppSettingsBlockWifi.setOnClickListener {
            toggleWifi(appInfo)
            updateFirewallStatusUi(appStatus, connStatus)
        }

        b.aadAppSettingsBlockMd.setOnClickListener {
            toggleMobileData(appInfo)
            updateFirewallStatusUi(appStatus, connStatus)
        }

        b.aadAppSettingsBypassUniv.setOnClickListener {
            // change the status to allowed if already app is bypassed
            if (appStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        }

        b.aadAppSettingsExclude.setOnClickListener {
            if (VpnController.isVpnLockdown()) {
                showToastUiCentered(this, getString(R.string.hsf_exclude_error), Toast.LENGTH_SHORT)
                return@setOnClickListener
            }

            // change the status to allowed if already app is excluded
            if (appStatus == FirewallManager.FirewallStatus.EXCLUDE) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.EXCLUDE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        }

        b.aadAppSettingsIsolate.setOnClickListener {
            // change the status to allowed if already app is isolated
            if (appStatus == FirewallManager.FirewallStatus.ISOLATE) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.ISOLATE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        }

        b.aadConnDetailIndicator.setOnClickListener { toggleNetworkLogState(ipListUiState) }

        b.aadConnDetailRl.setOnClickListener { toggleNetworkLogState(ipListUiState) }

        b.aadAapFirewallIndicator.setOnClickListener { toggleFirewallUiState(firewallUiState) }

        b.aadAapFirewallNewCard.setOnClickListener { toggleFirewallUiState(firewallUiState) }

        b.aadIpBlockCard.setOnClickListener { openCustomIpScreen() }

        b.aadDomainBlockCard.setOnClickListener { openCustomDomainScreen() }

        b.aadAppDetailIcon.setOnClickListener { toggleFirewallUiState(firewallUiState) }

        b.aadAppDetailLl.setOnClickListener { toggleFirewallUiState(firewallUiState) }

        b.aadDnsHeading.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                enableDnsStatusUi()
                // fixme: remove the below code, added for testing
                setAppDns("https://basic.rethinkdns.com/1:IAAQAA==")
                return@setOnCheckedChangeListener
            }

            removeAppDns(uid)
        }

        b.aadAppDnsRethinkConfigure.setOnClickListener { rethinkListBottomSheet() }

        b.aadConnDelete.setOnClickListener { showDeleteConnectionsDialog() }
    }

    private fun showAppInfoDialog(packages: List<String>) {
        val builderSingle = MaterialAlertDialogBuilder(this)

        builderSingle.setTitle(this.getString(R.string.about_settings_app_info))

        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packages)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packages.toTypedArray(), null)

        builderSingle.setPositiveButton(getString(R.string.ada_noapp_dialog_positive)) {
            dialog: DialogInterface,
            _: Int ->
            dialog.dismiss()
        }

        val alertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }

    private fun setAppDns(url: String) {
        io {
            val endpoint =
                RethinkDnsEndpoint(
                    "app_${appInfo.appName}",
                    url,
                    uid,
                    desc = "",
                    isActive = false,
                    isCustom = true,
                    latency = 0,
                    blocklistCount = 0,
                    modifiedDataTime = Constants.INIT_TIME_MS
                )
            appConfig.insertReplaceEndpoint(endpoint)
        }
    }

    private fun rethinkListBottomSheet() {
        val bottomSheetFragment = RethinkListBottomSheet()
        bottomSheetFragment.show(this.supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun removeAppDns(uid: Int) {
        io { appConfig.removeAppWiseDns(uid) }

        disableDnsStatusUi()
    }

    private fun disableDnsStatusUi() {
        b.aadDnsRethinkRl.visibility = View.GONE
        b.aadDnsHeading.isChecked = false
    }

    private fun enableDnsStatusUi() {
        b.aadDnsRethinkRl.visibility = View.VISIBLE
        b.aadDnsHeading.isChecked = true
    }

    private fun toggleMobileData(appInfo: AppInfo) {
        // toggle mobile data: change the connection status based on the current status.
        // if allow -> none(app status) + metered(connection status)
        // if unmetered -> none(app status) + both(connection status)
        // if metered -> none(app status) + allow(connection status)
        // if both -> none(app status) + unmetered(connection status)

        io {
            val connStatus = FirewallManager.connectionStatus(appInfo.uid)
            uiCtx {
                val cStat =
                    when (connStatus) {
                        FirewallManager.ConnectionStatus.METERED -> {
                            FirewallManager.ConnectionStatus.ALLOW
                        }
                        FirewallManager.ConnectionStatus.UNMETERED -> {
                            FirewallManager.ConnectionStatus.BOTH
                        }
                        FirewallManager.ConnectionStatus.BOTH -> {
                            FirewallManager.ConnectionStatus.UNMETERED
                        }
                        FirewallManager.ConnectionStatus.ALLOW -> {
                            FirewallManager.ConnectionStatus.METERED
                        }
                    }
                updateFirewallStatus(FirewallManager.FirewallStatus.NONE, cStat)
            }
        }
    }

    private fun toggleWifi(appInfo: AppInfo) {
        // toggle wifi: change the connection status based on the current status.
        // if Wifi -> none(app status) + wifi(connection status)
        // if MOBILE DATA -> none(app status) + both(connection status)
        // if BOTH -> none(app status) + mobile data(connection status)
        // if ALLOW -> none(app status) + wifi(connection status)

        io {
            val connStatus = FirewallManager.connectionStatus(appInfo.uid)
            uiCtx {
                val cStat =
                    when (connStatus) {
                        FirewallManager.ConnectionStatus.UNMETERED -> {
                            FirewallManager.ConnectionStatus.ALLOW
                        }
                        FirewallManager.ConnectionStatus.BOTH -> {
                            FirewallManager.ConnectionStatus.METERED
                        }
                        FirewallManager.ConnectionStatus.METERED -> {
                            FirewallManager.ConnectionStatus.BOTH
                        }
                        FirewallManager.ConnectionStatus.ALLOW -> {
                            FirewallManager.ConnectionStatus.UNMETERED
                        }
                    }

                updateFirewallStatus(FirewallManager.FirewallStatus.NONE, cStat)
            }
        }
    }

    private fun updateFirewallStatus(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ) {
        io {
            val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
            uiCtx {
                if (appNames.count() > 1) {
                    showDialog(appNames, appInfo, aStat, cStat)
                    return@uiCtx
                }

                completeFirewallChanges(aStat, cStat)
            }
        }
    }

    private fun completeFirewallChanges(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ) {
        appStatus = aStat
        connStatus = cStat
        io { updateFirewallStatus(appInfo.uid, aStat, cStat) }
        updateFirewallStatusUi(aStat, cStat)
    }

    private fun showNetworkLogsIfAny(uid: Int) {
        networkLogsViewModel.setUid(uid)
        b.aadConnDetailRecycler.setHasFixedSize(false)
        val layoutManager = CustomLinearLayoutManager(this)
        b.aadConnDetailRecycler.layoutManager = layoutManager
        val recyclerAdapter = AppConnectionAdapter(this, uid)
        networkLogsViewModel.appNetworkLogs.observe(this) {
            recyclerAdapter.submitData(this.lifecycle, it)
        }
        b.aadConnDetailRecycler.isNestedScrollingEnabled = false
        b.aadConnDetailRecycler.adapter = recyclerAdapter
        val itemAnimator = DefaultItemAnimator()
        itemAnimator.changeDuration = 1500
        b.aadConnDetailRecycler.itemAnimator = itemAnimator
        networkLogsViewModel.setFilter("")
    }

    private fun toggleNetworkLogState(state: Boolean) {
        ipListUiState = !state

        if (state) {
            b.aadConnListTopLl.visibility = View.VISIBLE
            b.aadConnDetailIndicator.setImageResource(R.drawable.ic_arrow_up)
            b.aadConnDelete.visibility = View.VISIBLE
            b.aadConnDetailSearchLl.visibility = View.VISIBLE
        } else {
            b.aadConnListTopLl.visibility = View.GONE
            b.aadConnDetailIndicator.setImageResource(R.drawable.ic_arrow_down)
            b.aadConnDelete.visibility = View.GONE
            b.aadConnDetailSearchLl.visibility = View.GONE
        }
    }

    private fun toggleFirewallUiState(state: Boolean) {
        firewallUiState = !state

        if (state) {
            b.aadAppSettingsLl.visibility = View.VISIBLE
            b.aadAapFirewallIndicator.setImageResource(R.drawable.ic_arrow_up)
        } else {
            b.aadAppSettingsLl.visibility = View.GONE
            b.aadAapFirewallIndicator.setImageResource(R.drawable.ic_arrow_down)
        }
    }

    private fun enableAppBypassedUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_on, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun enableDnsFirewallBypassedUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_on, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun enableAppExcludedUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_on, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun disableWhitelistExcludeUi() {
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
    }

    private fun disableFirewallStatusUi() {
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
    }

    private fun enableIsolateUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_on, b.aadAppSettingsIsolate)
    }

    private fun enableAllow() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    // update the BLOCK status based on connection status (mobile data + wifi + both)
    private fun enableBlock(cStat: FirewallManager.ConnectionStatus) {
        when (cStat) {
            FirewallManager.ConnectionStatus.METERED -> {
                setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.UNMETERED -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.ALLOW -> {
                setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
            }
        }
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun setDrawable(drawable: Int, txt: TextView) {
        val top = ContextCompat.getDrawable(this, drawable)
        txt.setCompoundDrawablesWithIntrinsicBounds(null, top, null, null)
    }

    private fun getFirewallText(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ): CharSequence {
        return when (aStat) {
            FirewallManager.FirewallStatus.NONE -> {
                when {
                    cStat.mobileData() -> getString(R.string.ada_app_status_block_md)
                    cStat.wifi() -> getString(R.string.ada_app_status_block_wifi)
                    cStat.allow() -> getString(R.string.ada_app_status_allow)
                    cStat.blocked() -> getString(R.string.ada_app_status_block)
                    else -> getString(R.string.ada_app_status_unknown)
                }
            }
            FirewallManager.FirewallStatus.EXCLUDE -> getString(R.string.ada_app_status_exclude)
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ->
                getString(R.string.ada_app_status_whitelist)
            FirewallManager.FirewallStatus.ISOLATE -> getString(R.string.ada_app_status_isolate)
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
                getString(R.string.ada_app_status_bypass_dns_firewall)
            FirewallManager.FirewallStatus.UNTRACKED -> getString(R.string.ada_app_status_unknown)
        }
    }

    private fun appName(packageCount: Int): String {
        return if (packageCount >= 2) {
            getString(
                R.string.ctbs_app_other_apps,
                appInfo.appName,
                packageCount.minus(1).toString()
            )
        } else {
            appInfo.appName
        }
    }

    private fun showNoAppFoundDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.ada_noapp_dialog_title))
        builder.setMessage(getString(R.string.ada_noapp_dialog_message))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.ada_noapp_dialog_positive)) {
            dialogInterface,
            _ ->
            dialogInterface.dismiss()
            finish()
        }
        builder.create().show()
    }

    private fun showDeleteConnectionsDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.ada_delete_logs_dialog_title)
        builder.setMessage(R.string.ada_delete_logs_dialog_desc)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_proceed)) { _, _ -> deleteAppLogs() }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
        builder.create().show()
    }

    private fun deleteAppLogs() {
        io { connectionTrackerRepository.clearLogsByUid(uid) }
    }

    private fun showDialog(
        packageList: List<String>,
        appInfo: AppInfo,
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ) {

        val builderSingle = MaterialAlertDialogBuilder(this)

        builderSingle.setIcon(R.drawable.ic_firewall_block_grey)
        val count = packageList.count()
        builderSingle.setTitle(
            this.getString(R.string.ctbs_block_other_apps, appInfo.appName, count.toString())
        )

        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle
            .setPositiveButton(getString(FirewallManager.getLabelForStatus(aStat, cStat))) {
                di: DialogInterface,
                _: Int ->
                di.dismiss()
                completeFirewallChanges(aStat, cStat)
            }
            .setNeutralButton(this.getString(R.string.ctbs_dialog_negative_btn)) {
                _: DialogInterface,
                _: Int ->
            }

        val alertDialog: AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        Glide.with(this).load(drawable).error(Utilities.getDefaultIcon(this)).into(mIconImageView)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        networkLogsViewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        networkLogsViewModel.setFilter(query)
        return true
    }
}
