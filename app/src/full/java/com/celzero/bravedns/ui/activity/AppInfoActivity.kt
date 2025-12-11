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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseDomainsAdapter
import com.celzero.bravedns.adapter.AppWiseIpsAdapter
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivityAppDetailsBinding
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.RETHINK_PACKAGE
import com.celzero.bravedns.util.Constants.Companion.VIEW_PAGER_SCREEN_TO_LOAD
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppInfoActivity : AppCompatActivity(R.layout.activity_app_details) {
    private val b by viewBinding(ActivityAppDetailsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private val ipRulesViewModel: CustomIpViewModel by viewModel()
    private val domainRulesViewModel: CustomDomainViewModel by viewModel()
    private val networkLogsViewModel: AppConnectionsViewModel by viewModel()

    private var uid: Int = INVALID_UID
    private lateinit var appInfo: AppInfo

    private var appStatus = FirewallManager.FirewallStatus.NONE
    private var connStatus = FirewallManager.ConnectionStatus.ALLOW

    private var showBypassToolTip: Boolean = true

    companion object {
        const val INTENT_UID = "UID"
        const val INTENT_ACTIVE_CONNS = "ACTIVE_CONNS"
        const val INTENT_ASN = "ASN"
        private const val TAG = "AppInfoActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        handleFrostEffectIfNeeded(persistentState.theme)
        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        uid = intent.getIntExtra(INTENT_UID, INVALID_UID)
        Logger.d(LOG_TAG_UI, "AppInfoActivity, intent uid: $uid")
        ipRulesViewModel.setUid(uid)
        domainRulesViewModel.setUid(uid)
        networkLogsViewModel.setUid(uid)
        init()
        observeAppRules()
        setupClickListeners()
    }

    private fun observeAppRules() {
        ipRulesViewModel.ipRulesCount(uid).observe(this) { b.aadIpBlockHeader.text = it.toString() }

        domainRulesViewModel.domainRulesCount(uid).observe(this) {
            b.aadDomainBlockHeader.text = it.toString()
        }
    }

    private fun init() {
        io {
            val appInfo = FirewallManager.getAppInfoByUid(uid)
            // case: app is uninstalled but still available in RethinkDNS database
            if (appInfo == null || uid == INVALID_UID || appInfo.tombstoneTs > 0) {
                uiCtx { showNoAppFoundDialog() }
                return@io
            }

            val packages = FirewallManager.getPackageNamesByUid(appInfo.uid)
            appStatus = FirewallManager.appStatus(appInfo.uid)
            connStatus = FirewallManager.connectionStatus(appInfo.uid)
            uiCtx {
                this.appInfo = appInfo

                b.aadAppDetailName.text = appName(packages.count())
                b.aadPkgName.text = appInfo.packageName
                b.excludeProxySwitch.isChecked = appInfo.isProxyExcluded
                displayDataUsage()
                displayProxyStatus()
                displayIcon(
                    Utilities.getIcon(this, appInfo.packageName, appInfo.appName),
                    b.aadAppDetailIcon
                )

                if (appInfo.packageName == RETHINK_PACKAGE) {
                    updateFirewallStatusUi(appStatus, connStatus)
                    setActiveConnsAdapter(true)
                    setRethinkDomainLogsAdapter()
                    setRethinkIpLogsAdapter()
                } else {
                    updateFirewallStatusUi(appStatus, connStatus)
                    setActiveConnsAdapter(false)
                    if (persistentState.downloadIpInfo) {
                        setASNAdapter()
                    }
                    setDomainsAdapter()
                    setIpAdapter()
                }

                // disable exclude app option for apps with no package name
                if (FirewallManager.isUnknownPackage(uid)) {
                    b.aadAppSettingsExclude.alpha = 0.5f
                    b.aadAppSettingsExclude.isEnabled = false
                } else {
                    b.aadAppSettingsExclude.alpha = 1.0f
                    b.aadAppSettingsExclude.isEnabled = true
                }
            }
        }
    }

    private fun displayProxyStatus() {
        val proxy = ProxyManager.getProxyIdForApp(uid)
        if (proxy.isEmpty() || proxy == ID_NONE) {
            b.aadProxyDetails.visibility = View.GONE
            return
        }
        b.aadProxyDetails.visibility = View.VISIBLE
        b.aadProxyDetails.text = getString(R.string.wireguard_apps_proxy_map_desc, proxy)
    }

    private fun openCustomIpScreen() {
        val intent = Intent(this, CustomRulesActivity::class.java)
        intent.putExtra(VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.IP_RULES.screen)
        intent.putExtra(Constants.INTENT_UID, uid)
        startActivity(intent)
    }

    private fun openCustomDomainScreen() {
        val intent = Intent(this, CustomRulesActivity::class.java)
        intent.putExtra(VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.DOMAIN_RULES.screen)
        intent.putExtra(Constants.INTENT_UID, uid)
        startActivity(intent)
    }

    private fun displayDataUsage() {
        if (!::appInfo.isInitialized) {
            Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in displayDataUsage")
            // Set default values when appInfo is not available
            b.aadDataUsageStatus.text = getString(R.string.two_argument,
                getString(R.string.symbol_upload, "0 B"),
                getString(R.string.symbol_download, "0 B"))
            return
        }

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
        val statusText = getFirewallText(firewallStatus, connectionStatus)
        val statusWithTime = if (::appInfo.isInitialized && appInfo.modifiedTs > 0) {
            val now = System.currentTimeMillis()
            val uptime = now - appInfo.modifiedTs
            val relativeTime = DateUtils.getRelativeTimeSpanString(
                now - uptime,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            "$statusText $relativeTime"
        } else {
            statusText
        }

        b.aadFirewallStatus.text =
            htmlToSpannedText(
                getString(
                    R.string.ada_firewall_status,
                    statusWithTime
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

        b.aadAppInfoIcon.setOnClickListener {
            if (!::appInfo.isInitialized) {
                Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in aadAppInfoIcon click listener, using uid: $uid")
                showToastUiCentered(
                    this,
                    this.getString(R.string.ctbs_app_info_not_available_toast),
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }

            io {
                val appNames = FirewallManager.getAppNamesByUid(uid)
                uiCtx {
                    if (appNames.count() == 1) {
                        openAndroidAppInfo(this, appInfo.packageName)
                    } else if (appNames.count() > 1) {
                        showAppInfoDialog(appNames)
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

        TooltipCompat.setTooltipText(b.aadCloseConnsChip, getString(R.string.close_conns_dialog_title))

        b.aadAppSettingsBypassDnsFirewall.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBypassDnsFirewall") {
                // show the tooltip only once when app is not bypassed (dns + firewall) earlier
                if (showBypassToolTip && appStatus == FirewallManager.FirewallStatus.NONE) {
                    b.aadAppSettingsBypassDnsFirewall.performLongClick()
                    showBypassToolTip = false
                    return@guardAppInfoInitialized
                }

                if (appStatus == FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL) {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "DNS + Firewall bypass disabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                } else {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "DNS + Firewall bypass enabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                }
            }
        }

        b.aadAppSettingsBlockWifi.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBlockWifi") {
                toggleWifi(appInfo)
                updateFirewallStatusUi(appStatus, connStatus)
            }
        }

        b.aadAppSettingsBlockMd.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBlockMd") {
                toggleMobileData(appInfo)
                updateFirewallStatusUi(appStatus, connStatus)
            }
        }

        b.aadAppSettingsBypassUniv.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBypassUniv") {
                // change the status to allowed if already app is bypassed
                if (appStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL) {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "Universal bypass disabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                } else {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "Universal bypass enabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                }
            }
        }

        b.aadAppSettingsExclude.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsExclude") {
                if (VpnController.isVpnLockdown()) {
                    showToastUiCentered(this, getString(R.string.hsf_exclude_error), Toast.LENGTH_SHORT)
                    return@guardAppInfoInitialized
                }

                io {
                    if (FirewallManager.isUnknownPackage(uid) && appStatus == FirewallManager.FirewallStatus.EXCLUDE) {
                        uiCtx {
                            showToastUiCentered(
                                this,
                                getString(R.string.exclude_no_package_err_toast),
                                Toast.LENGTH_LONG
                            )
                        }
                        return@io
                    }

                    // change the status to allowed if already app is excluded
                    if (appStatus == FirewallManager.FirewallStatus.EXCLUDE) {
                        updateFirewallStatus(
                            FirewallManager.FirewallStatus.NONE,
                            FirewallManager.ConnectionStatus.ALLOW
                        )
                        logEvent(
                            "firewall rule change",
                            "App exclusion disabled for ${appInfo.appName} (${appInfo.uid})"
                        )
                    } else {
                        updateFirewallStatus(
                            FirewallManager.FirewallStatus.EXCLUDE,
                            FirewallManager.ConnectionStatus.ALLOW
                        )
                        logEvent(
                            "firewall rule change",
                            "App exclusion enabled for ${appInfo.appName} (${appInfo.uid})"
                        )
                    }
                }
            }
        }

        b.aadAppSettingsIsolate.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsIsolate") {
                // change the status to allowed if already app is isolated
                if (appStatus == FirewallManager.FirewallStatus.ISOLATE) {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "App isolation disabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                } else {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.ISOLATE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "App isolation enabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                }
            }
        }

        b.aadIpBlockCard.setOnClickListener { openCustomIpScreen() }

        b.aadDomainBlockCard.setOnClickListener { openCustomDomainScreen() }

        b.aadIpsChip.setOnClickListener { openAppWiseIpLogsActivity() }

        b.aadDomainsChip.setOnClickListener { openAppWiseDomainLogsActivity() }

        b.aadActiveConnsChip.setOnClickListener { openAppWiseDomainLogsActivity(activeConns = true) }

        b.aadAsnChip.setOnClickListener { openAppWiseIpLogsActivity(asn = true) }

        b.excludeProxySwitch.setOnCheckedChangeListener { _, isChecked ->
            updateExcludeProxyStatus(isChecked)
        }

        b.excludeProxyRl.setOnClickListener {
            b.excludeProxySwitch.isChecked = !b.excludeProxySwitch.isChecked
        }

        b.aadCloseConnsChip.setOnClickListener {
            if (!::appInfo.isInitialized) {
                Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in aadCloseConnsChip click listener, using uid: $uid")
                showCloseConnectionDialog(uid, "Unknown App")
                return@setOnClickListener
            }

            showCloseConnectionDialog(uid, appInfo.appName)
        }
    }

    private fun updateExcludeProxyStatus(isExcluded: Boolean) {
        io {
            FirewallManager.updateIsProxyExcluded(uid, isExcluded)
            logEvent(
                "proxy exclude change",
                "Proxy exclude status changed for ${appInfo.appName} (${appInfo.uid}), new status: $isExcluded"
            )
        }
    }

    private fun openAppWiseDomainLogsActivity(activeConns: Boolean = false) {
        val intent = Intent(this, AppWiseDomainLogsActivity::class.java)
        intent.putExtra(INTENT_UID, uid)
        intent.putExtra(INTENT_ACTIVE_CONNS, activeConns)
        startActivity(intent)
    }

    private fun openAppWiseIpLogsActivity(asn: Boolean = false) {
        val intent = Intent(this, AppWiseIpLogsActivity::class.java)
        intent.putExtra(INTENT_UID, uid)
        intent.putExtra(INTENT_ASN, asn)
        startActivity(intent)
    }

    private fun setActiveConnsAdapter(isRethink: Boolean) {
        val layoutManager = LinearLayoutManager(this)
        b.aadActiveConnsRv.layoutManager = layoutManager
        val adapter = AppWiseDomainsAdapter(this, this, uid, isActiveConn = true)
        val uptime = VpnController.uptimeMs()
        Logger.i(LOG_TAG_UI, "app-info-act, active conns, uptime: $uptime ms")
        if (isRethink) {
            networkLogsViewModel.getRethinkActiveConnsLimited(uptime).observe(this) {
                adapter.submitData(this.lifecycle, it)
            }
        } else {
            networkLogsViewModel.fetchTopActiveConnections(uid, uptime).observe(this) {
                adapter.submitData(this.lifecycle, it)
            }
        }
        b.aadActiveConnsRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadActiveConnsRl.visibility = View.VISIBLE
                } else {
                    b.aadActiveConnsRl.visibility = View.GONE
                }
            }
        }
    }

    private fun setASNAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.aadAsnRv.layoutManager = layoutManager
        val adapter = AppWiseIpsAdapter(this, this, uid,  isAsn = true)
        networkLogsViewModel.getAsnLogsLimited(uid).observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadAsnRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadAsnRl.visibility = View.VISIBLE
                } else {
                    b.aadAsnRl.visibility = View.GONE
                }
            }
        }
    }

    private fun setDomainsAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedDomainRv.layoutManager = layoutManager
        val adapter = AppWiseDomainsAdapter(this, this, uid)
        networkLogsViewModel.getDomainLogsLimited(uid).observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedDomainRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedDomainRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedDomainRl.visibility = View.GONE
                }
            }
        }
    }

    private fun setRethinkDomainLogsAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedDomainRv.layoutManager = layoutManager
        val adapter = AppWiseDomainsAdapter(this, this, uid)
        networkLogsViewModel.getRethinkDomainLogsLimited().observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedDomainRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedDomainRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedDomainRl.visibility = View.GONE
                }
            }
        }
    }

    private fun setRethinkIpLogsAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedIpsRv.layoutManager = layoutManager
        val adapter = AppWiseIpsAdapter(this, this, uid)
        networkLogsViewModel.getRethinkIpLogsLimited().observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedIpsRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedIpsRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedIpsRl.visibility = View.GONE
                }
            }
        }
    }

    private fun setIpAdapter() {
        b.aadMostContactedIpsRv.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedIpsRv.layoutManager = layoutManager
        val adapter = AppWiseIpsAdapter(this, this, uid)
        networkLogsViewModel.getIpLogsLimited(uid).observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedIpsRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedIpsRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedIpsRl.visibility = View.GONE
                }
            }
        }
    }

    private fun showAppInfoDialog(appNames: List<String>) {
        val builderSingle = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builderSingle.setTitle(this.getString(R.string.about_settings_app_info))

        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(appNames)
        builderSingle.setCancelable(false)

        builderSingle.setItems(appNames.toTypedArray(), null)

        builderSingle.setPositiveButton(getString(R.string.ada_noapp_dialog_positive)) {
            dialog: DialogInterface,
            _: Int ->
            dialog.dismiss()
        }

        val alertDialog = builderSingle.create()
        val ctx = this.applicationContext
        alertDialog.listView.setOnItemClickListener { _, _, position, _ ->
            io {
                val pkg = FirewallManager.getPackageNameByAppName(appNames[position])
                uiCtx {
                    Logger.i(Logger.LOG_TAG_UI, "AppInfoActivity, package name: $pkg")
                    openAndroidAppInfo(ctx, pkg)
                }
            }
        }
        alertDialog.show()
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
                updateFirewallStatus(FirewallManager.FirewallStatus.NONE, cStat, connStatus)
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

                updateFirewallStatus(FirewallManager.FirewallStatus.NONE, cStat, connStatus)
                logEvent(
                    "firewall rule change",
                    "Toggled WIFI for ${appInfo.appName} (${appInfo.uid}), new conn status: $cStat"
                )
            }
        }
    }

    private fun updateFirewallStatus(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus,
        prevConnStat: FirewallManager.ConnectionStatus = FirewallManager.ConnectionStatus.ALLOW
    ) {
        io {
            val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
            uiCtx {
                if (appNames.count() > 1) {
                    showDialog(appNames, appInfo, aStat, cStat, prevConnStat)
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
        logEvent(
            "firewall rule change",
            "Firewall status changed for ${appInfo.appName} (${appInfo.uid}), new status: $aStat, conn status: $cStat"
        )
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
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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

    private fun showDialog(
        packageList: List<String>,
        appInfo: AppInfo,
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus,
        prevConnStat: FirewallManager.ConnectionStatus
    ) {

        val builderSingle = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)

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
            .setPositiveButton(getString(FirewallManager.getLabelForStatus(aStat, cStat, prevConnStat))) {
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

    private fun showCloseConnectionDialog(uid: Int, appName: String) {
        Logger.v(LOG_TAG_UI, "$TAG show close connection dialog for uid: $uid")
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(this.getString(R.string.close_conns_dialog_title))
            .setMessage(getString(R.string.close_conns_dialog_desc, appName))
            .setPositiveButton(R.string.lbl_proceed) { _, _ ->
                // close the connection
                VpnController.closeConnectionsIfNeeded(uid, "app-info-dialog-manual-close")
                Logger.i(LOG_TAG_UI, "$TAG closed connection for uid: $uid")
                showToastUiCentered(this, getString(R.string.config_add_success_toast), Toast.LENGTH_LONG)
                logEvent("close connections",
                    "Closed active connections for $appName ($uid) from AppInfoActivity")
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .create()
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        Glide.with(this).load(drawable).error(Utilities.getDefaultIcon(this)).into(mIconImageView)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun guardAppInfoInitialized(listenerName: String, block: () -> Unit) {
        if (!::appInfo.isInitialized) {
            Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in $listenerName click listener, using uid: $uid")
            showToastUiCentered(
                this,
                this.getString(R.string.ctbs_app_info_not_available_toast),
                Toast.LENGTH_SHORT
            )
            return
        }
        block()
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
