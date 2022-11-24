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
package com.celzero.bravedns.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppConnectionAdapter
import com.celzero.bravedns.adapter.AppIpRulesAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnections
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.databinding.ActivityAppDetailsBinding
import com.celzero.bravedns.databinding.DialogAddCustomIpBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_3
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.celzero.bravedns.viewmodel.AppCustomIpViewModel
import inet.ipaddr.HostName
import inet.ipaddr.HostNameException
import inet.ipaddr.IPAddressString
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

    private val appCustomIpViewModel: AppCustomIpViewModel by viewModel()

    private var uid: Int = 0
    private lateinit var appInfo: AppInfo

    private var connIpList: List<AppConnections>? = null
    private var appConnAdapter: AppConnectionAdapter? = null

    private var ipListUiState: Boolean = true
    private var ipRulesUiState: Boolean = false
    private var appDetailsUiState: Boolean = false
    private var firewallUiState: Boolean = false

    private var appStatus = FirewallManager.FirewallStatus.ALLOW
    private var connStatus = FirewallManager.ConnectionStatus.BOTH

    companion object {
        const val UID_INTENT_NAME = "UID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        uid = intent.getIntExtra(UID_INTENT_NAME, INVALID_UID)
        init()
        appCustomIpViewModel.setUid(uid)
        observeCustomIpSize()
        setupClickListeners()
    }

    private fun observeCustomIpSize() {
        appCustomIpViewModel.appWiseIpRulesSize(uid).observe(this) {
            b.aadIpBlockDesc.text = getString(R.string.ada_ip_block_count, it.toString())
            if (it == 0) {
                b.aadIpBlockEmptyTxt.visibility = View.VISIBLE
                b.aadIpBlockRecycler.visibility = View.GONE
                return@observe
            }

            b.aadIpBlockEmptyTxt.visibility = View.GONE
            b.aadIpBlockRecycler.visibility = View.VISIBLE
        }
    }

    private fun init() {
        val ai = FirewallManager.getAppInfoByUid(uid)
        // case: app is uninstalled but still available in RethinkDNS database
        if (ai == null || uid == INVALID_UID) {
            showNoAppFoundDialog()
            return
        }

        appInfo = ai

        val packages = FirewallManager.getPackageNamesByUid(appInfo.uid)

        if (packages.count() != 1) {
            b.aadAppInfoIcon.visibility = View.GONE
        }

        b.aadAppDetailName.text = appName(packages.count())
        b.aadAppDetailDesc.text = appInfo.packageInfo
        appStatus = FirewallManager.appStatus(appInfo.uid)
        connStatus = FirewallManager.connectionStatus(appInfo.uid)
        updateFirewallStatusUi(appStatus, connStatus)
        toggleIpConnectionsState(ipListUiState)
        toggleIpRulesState(ipRulesUiState)
        toggleFirewallUiState(firewallUiState)
        updateBasicAppInfo()
        updateDnsDetails()

        displayIcon(
            Utilities.getIcon(this, appInfo.packageInfo, appInfo.appName),
            b.aadAppDetailIcon
        )

        appCustomIpViewModel.setUid(appInfo.uid)
        displayIpRulesIfAny(appInfo.uid)
        io { displayNetworkLogsIfAny(appInfo.uid) }
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
            FirewallManager.FirewallStatus.ALLOW -> {
                disableWhitelistExcludeUi()
                enableAllow()
            }
            FirewallManager.FirewallStatus.BLOCK -> {
                disableWhitelistExcludeUi()
                enableBlock(connectionStatus)
            }
            FirewallManager.FirewallStatus.EXCLUDE -> {
                enableAppExcludedUi()
            }
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> {
                enableAppWhitelistedUi()
            }
            FirewallManager.FirewallStatus.LOCKDOWN -> {
                disableFirewallStatusUi()
                enableLockdownUi()
            }
            FirewallManager.FirewallStatus.UNTRACKED -> {
                // no-op
            }
        }
    }

    private fun setupClickListeners() {

        b.aadConnDetailSearch.setOnQueryTextListener(this)

        b.aadAppInfoIcon.setOnClickListener {
            Utilities.openAndroidAppInfo(this, appInfo.packageInfo)
        }

        b.aadAppSettingsBlock.setOnClickListener {
            // update both the wifi and mobile data to either block or allow state
            if (appStatus == FirewallManager.FirewallStatus.ALLOW) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.BLOCK,
                    FirewallManager.ConnectionStatus.BOTH
                )
            } else {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.ALLOW,
                    FirewallManager.ConnectionStatus.BOTH
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

        b.aadAppSettingsWhitelist.setOnClickListener {
            // change the status to allowed if already app is whitelisted
            if (appStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.ALLOW,
                    FirewallManager.ConnectionStatus.BOTH
                )
                return@setOnClickListener
            }

            updateFirewallStatus(
                FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
                FirewallManager.ConnectionStatus.BOTH
            )
        }

        b.aadAppSettingsExclude.setOnClickListener {
            if (VpnController.isVpnLockdown()) {
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.hsf_exclude_error),
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }

            // change the status to allowed if already app is excluded
            if (appStatus == FirewallManager.FirewallStatus.EXCLUDE) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.ALLOW,
                    FirewallManager.ConnectionStatus.BOTH
                )
                return@setOnClickListener
            }

            updateFirewallStatus(
                FirewallManager.FirewallStatus.EXCLUDE,
                FirewallManager.ConnectionStatus.BOTH
            )
        }

        b.aadAppSettingsLockdown.setOnClickListener {
            if (appStatus == FirewallManager.FirewallStatus.LOCKDOWN) {
                updateFirewallStatus(
                    FirewallManager.FirewallStatus.ALLOW,
                    FirewallManager.ConnectionStatus.BOTH
                )
                return@setOnClickListener
            }

            updateFirewallStatus(
                FirewallManager.FirewallStatus.LOCKDOWN,
                FirewallManager.ConnectionStatus.BOTH
            )
        }

        b.aadConnDetailIndicator.setOnClickListener { toggleIpConnectionsState(ipListUiState) }

        b.aadConnDetailRl.setOnClickListener { toggleIpConnectionsState(ipListUiState) }

        b.aadAapFirewallIndicator.setOnClickListener { toggleFirewallUiState(firewallUiState) }

        b.aadFirewallTitleRl.setOnClickListener { toggleFirewallUiState(firewallUiState) }

        b.aadIpBlockIndicator.setOnClickListener { toggleIpRulesState(ipRulesUiState) }

        b.aadIpBlockRl.setOnClickListener { toggleIpRulesState(ipRulesUiState) }

        b.aadDownArrowIcon.setOnClickListener { toggleAppDetailsState(appDetailsUiState) }

        b.aadAppDetailIcon.setOnClickListener { toggleAppDetailsState(appDetailsUiState) }

        b.aadAppDetailLl.setOnClickListener { toggleAppDetailsState(appDetailsUiState) }

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

        b.aadIpAddIndicator.setOnClickListener { showAddIpDialog() }

        b.aadConnDelete.setOnClickListener { showDeleteConnectionsDialog() }
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
        val status = FirewallManager.appStatus(appInfo.uid)
        var aStat: FirewallManager.FirewallStatus = FirewallManager.FirewallStatus.BLOCK
        var cStat: FirewallManager.ConnectionStatus = FirewallManager.ConnectionStatus.BOTH

        // toggle mobile data: change the app status and connection status based on the current
        // status.
        // if Mobile Data -> allow(app status) + Mobile Data(connection status)
        // if BOTH -> no need to change the app status, toggle connection status
        // based on the current status
        when (FirewallManager.connectionStatus(appInfo.uid)) {
            FirewallManager.ConnectionStatus.MOBILE_DATA -> {
                aStat = FirewallManager.FirewallStatus.ALLOW
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                cStat =
                    if (status.blocked()) {
                        FirewallManager.ConnectionStatus.WIFI
                    } else {
                        FirewallManager.ConnectionStatus.MOBILE_DATA
                    }
            }
            else -> {
                //  no-op
            }
        }

        updateFirewallStatus(aStat, cStat)
    }

    private fun toggleWifi(appInfo: AppInfo) {
        val currentStatus = FirewallManager.appStatus(appInfo.uid)

        var aStat: FirewallManager.FirewallStatus = FirewallManager.FirewallStatus.BLOCK
        var cStat: FirewallManager.ConnectionStatus = FirewallManager.ConnectionStatus.BOTH

        // toggle wifi: change the app status and connection status based on the current status.
        // if Wifi -> allow(app status) + wifi(connection status)
        // if BOTH -> no need to change the app status, toggle connection status
        // based on the current status
        when (FirewallManager.connectionStatus(appInfo.uid)) {
            FirewallManager.ConnectionStatus.WIFI -> {
                aStat = FirewallManager.FirewallStatus.ALLOW
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                cStat =
                    if (currentStatus.blocked()) {
                        FirewallManager.ConnectionStatus.MOBILE_DATA
                    } else {
                        FirewallManager.ConnectionStatus.WIFI
                    }
            }
            else -> {
                // no-op
            }
        }

        updateFirewallStatus(aStat, cStat)
    }

    private fun updateFirewallStatus(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ) {
        val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
        if (appNames.count() > 1) {
            showDialog(appNames, appInfo, aStat, cStat)
            return
        }

        completeFirewallChanges(aStat, cStat)
    }

    private fun completeFirewallChanges(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ) {
        appStatus = aStat
        connStatus = cStat
        updateFirewallStatus(appInfo.uid, aStat, cStat)
        updateFirewallStatusUi(aStat, cStat)
    }

    private fun displayIpRulesIfAny(uid: Int) {
        b.aadIpBlockEmptyTxt.visibility = View.GONE
        appCustomIpViewModel.setUid(uid)
        b.aadIpBlockRecycler.setHasFixedSize(false)
        val layoutManager = CustomLinearLayoutManager(this)
        b.aadIpBlockRecycler.layoutManager = layoutManager
        val recyclerAdapter = AppIpRulesAdapter(this, uid)
        appCustomIpViewModel.customIpDetails.observe(this) {
            recyclerAdapter.submitData(this.lifecycle, it)
        }
        b.aadIpBlockRecycler.adapter = recyclerAdapter
    }

    private suspend fun displayNetworkLogsIfAny(uid: Int) {
        connIpList = connectionTrackerRepository.getLogsForApp(uid)

        uiCtx {
            if (connIpList.isNullOrEmpty()) {
                b.aadConnDetailDesc.text = getString(R.string.ada_ip_connection_count_zero)
                b.aadConnDetailSearchContainer.visibility = View.GONE
                b.aadConnDetailEmptyTxt.visibility = View.VISIBLE
                b.aadConnDetailRecycler.visibility = View.GONE
                toggleFirewallUiState(firewallUiState)
                b.aadConnDelete.visibility = View.GONE
                return@uiCtx
            }

            // asserting as the null check is above
            b.aadConnDetailDesc.text =
                getString(R.string.ada_ip_connection_count, connIpList!!.size.toString())
            // set listview adapter
            b.aadConnDetailRecycler.setHasFixedSize(true)
            val layoutManager = LinearLayoutManager(this)
            b.aadConnDetailRecycler.layoutManager = layoutManager
            appConnAdapter = AppConnectionAdapter(this, connIpList!!, uid)
            b.aadConnDetailRecycler.adapter = appConnAdapter
        }
    }

    private fun toggleIpConnectionsState(state: Boolean) {
        ipListUiState = !state
        if (state) {
            b.aadConnDetailTopLl.visibility = View.VISIBLE
            b.aadConnDetailSearchLl.visibility = View.VISIBLE
            b.aadConnDelete.visibility = View.VISIBLE
            b.aadConnDetailIndicator.setImageResource(R.drawable.ic_arrow_up)
        } else {
            b.aadConnDetailSearchLl.visibility = View.GONE
            b.aadConnDelete.visibility = View.GONE
            b.aadConnDetailTopLl.visibility = View.GONE
            b.aadConnDetailIndicator.setImageResource(R.drawable.ic_arrow_down)
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

    private fun toggleIpRulesState(state: Boolean) {
        ipRulesUiState = !state

        if (state) {
            b.aadIpBlockTopLl.visibility = View.VISIBLE
            b.aadIpBlockIndicator.setImageResource(R.drawable.ic_arrow_up)
            b.aadIpAddIndicator.visibility = View.VISIBLE
            return
        }

        b.aadIpBlockTopLl.visibility = View.GONE
        b.aadIpAddIndicator.visibility = View.GONE
        b.aadIpBlockIndicator.setImageResource(R.drawable.ic_arrow_down)
    }

    private fun toggleAppDetailsState(state: Boolean) {
        appDetailsUiState = !state
        if (state) {
            b.aadAppStatsCard.visibility = View.VISIBLE
            b.aadDownArrowIcon.setImageResource(R.drawable.ic_arrow_up)
            return
        }

        b.aadAppStatsCard.visibility = View.GONE
        b.aadDownArrowIcon.setImageResource(R.drawable.ic_arrow_down)
    }

    private fun enableAppWhitelistedUi() {
        setDrawable(R.drawable.ic_firewall_allow_grey, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_whitelist_on, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsLockdown)
    }

    private fun enableAppExcludedUi() {
        setDrawable(R.drawable.ic_firewall_allow_grey, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_on, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsLockdown)
    }

    private fun disableWhitelistExcludeUi() {
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsLockdown)
    }

    private fun disableFirewallStatusUi() {
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsLockdown)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
    }

    private fun enableLockdownUi() {
        setDrawable(R.drawable.ic_firewall_allow_grey, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_on, b.aadAppSettingsLockdown)
    }

    private fun enableAllow() {
        setDrawable(R.drawable.ic_firewall_allow, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsLockdown)
    }

    // update the BLOCK status based on connection status (mobile data + wifi + both)
    private fun enableBlock(cStat: FirewallManager.ConnectionStatus) {
        when (cStat) {
            FirewallManager.ConnectionStatus.MOBILE_DATA -> {
                setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.WIFI -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
        }
        setDrawable(R.drawable.ic_firewall_block, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
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
            FirewallManager.FirewallStatus.ALLOW -> getString(R.string.ada_app_status_allow)
            FirewallManager.FirewallStatus.EXCLUDE -> getString(R.string.ada_app_status_exclude)
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ->
                getString(R.string.ada_app_status_whitelist)
            FirewallManager.FirewallStatus.BLOCK -> {
                when {
                    cStat.mobileData() -> getString(R.string.ada_app_status_block_md)
                    cStat.wifi() -> getString(R.string.ada_app_status_block_wifi)
                    else -> getString(R.string.ada_app_status_block)
                }
            }
            FirewallManager.FirewallStatus.LOCKDOWN -> getString(R.string.ada_app_status_lockdown)
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
        val builder = AlertDialog.Builder(this)
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

    private fun showAddIpDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.ci_dialog_title))
        val dBind = DialogAddCustomIpBinding.inflate(layoutInflater)
        dialog.setContentView(dBind.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        dBind.daciIpTitle.text = getString(R.string.ci_dialog_title)

        dBind.daciIpEditText.addTextChangedListener {
            if (dBind.daciFailureTextView.isVisible) {
                dBind.daciFailureTextView.visibility = View.GONE
            }
        }

        dBind.daciAddBtn.setOnClickListener {
            val input = dBind.daciIpEditText.text.toString()

            val ipString = Utilities.removeLeadingAndTrailingDots(input)

            val hostName = getHostName(ipString)
            val ip = hostName?.address
            if (ip == null || ipString.isEmpty()) {
                dBind.daciFailureTextView.text = getString(R.string.ci_dialog_error_invalid_ip)
                dBind.daciFailureTextView.visibility = View.VISIBLE
                return@setOnClickListener
            }

            dBind.daciIpEditText.text.clear()
            insertCustomIp(hostName)
        }

        dBind.daciCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showDeleteConnectionsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.ada_delete_logs_dialog_title)
        builder.setMessage(R.string.ada_delete_logs_dialog_desc)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.ada_delete_logs_dialog_positive)) { _, _ ->
            deleteAppLogs()
        }

        builder.setNegativeButton(getString(R.string.ada_delete_logs_delete_negative)) { _, _ -> }
        builder.create().show()
    }

    private fun deleteAppLogs() {
        io {
            connectionTrackerRepository.clearLogsByUid(uid)
            displayNetworkLogsIfAny(uid)
        }
    }

    private fun getHostName(ip: String): HostName? {
        return try {
            val host = HostName(ip)
            host.validate()
            host
        } catch (e: HostNameException) {
            val ipAddress = IPAddressString(ip).address ?: return null
            HostName(ipAddress)
        }
    }

    private fun insertCustomIp(ip: HostName) {
        IpRulesManager.addIpRule(uid, ip, IpRulesManager.IpRuleStatus.BLOCK)
        Utilities.showToastUiCentered(
            this,
            getString(R.string.ci_dialog_added_success),
            Toast.LENGTH_SHORT
        )
    }

    private fun showDialog(
        packageList: List<String>,
        appInfo: AppInfo,
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ) {

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(this)

        builderSingle.setIcon(R.drawable.ic_firewall_block)
        val count = packageList.count()
        builderSingle.setTitle(
            this.getString(R.string.ctbs_block_other_apps, appInfo.appName, count.toString())
        )

        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle
            .setPositiveButton(aStat.name) { di: DialogInterface, _: Int ->
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

    private fun updateBasicAppInfo() {
        // TODO: As we get the packageInfo details, we can now show the permission
        // details of the application as well. Need to add when the permission manager is
        // introduced.
        try {
            val packageInfo: PackageInfo =
                this.packageManager.getPackageInfo(
                    appInfo.packageInfo,
                    PackageManager.GET_PERMISSIONS
                )
            val installTime =
                Utilities.convertLongToTime(packageInfo.firstInstallTime, TIME_FORMAT_3)
            val updateTime = Utilities.convertLongToTime(packageInfo.lastUpdateTime, TIME_FORMAT_3)
            b.aadDetails.text =
                updateHtmlEncodedText(
                    getString(
                        R.string.ada_uid,
                        appInfo.uid.toString(),
                        appInfo.appCategory,
                        installTime,
                        updateTime
                    )
                )
            toggleAppDetailsState(appDetailsUiState)
            enableAppInfoIndicators()
        } catch (ignored: PackageManager.NameNotFoundException) {
            // pass the app state to false; so that the toggle will
            // hide the app's basic info for non-apps
            toggleAppDetailsState(state = false)
            disableAppInfoIndicators()
        }
    }

    private fun enableAppInfoIndicators() {
        b.aadAppInfoIcon.visibility = View.VISIBLE
        b.aadDownArrowIcon.visibility = View.VISIBLE
        b.aadAppDetailIcon.isEnabled = true
        b.aadAppDetailLl.isEnabled = true
        b.aadAppDetailLl.isClickable = true
        b.aadAppDetailIcon.isClickable = true
    }

    private fun disableAppInfoIndicators() {
        b.aadAppInfoIcon.visibility = View.GONE
        b.aadDownArrowIcon.visibility = View.GONE
        b.aadAppDetailIcon.isEnabled = false
        b.aadAppDetailLl.isEnabled = false
        b.aadAppDetailLl.isClickable = false
        b.aadAppDetailIcon.isClickable = false
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        GlideApp.with(this)
            .load(drawable)
            .error(Utilities.getDefaultIcon(this))
            .into(mIconImageView)
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
        if (appConnAdapter == null) return true

        val filter = connIpList?.filter { it.ipAddress.contains(query) }
        appConnAdapter?.filter(filter)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        if (appConnAdapter == null) return true

        val filter = connIpList?.filter { it.ipAddress.contains(query) }
        appConnAdapter?.filter(filter)
        return true
    }
}
