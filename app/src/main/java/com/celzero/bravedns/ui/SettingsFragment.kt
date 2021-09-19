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
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ExcludedAppListAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.ActivitySettingsScreenBinding
import com.celzero.bravedns.databinding.DialogSetHttpProxyBinding
import com.celzero.bravedns.databinding.DialogSetProxyBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_2
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.Companion.delay
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.Companion.isAtleastQ
import com.celzero.bravedns.util.Utilities.Companion.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.Companion.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment(R.layout.activity_settings_screen) {
    private val b by viewBinding(ActivitySettingsScreenBinding::bind)

    //For exclude apps dialog
    private lateinit var excludeAppAdapter: ExcludedAppListAdapter
    private val excludeAppViewModel: ExcludedAppViewModel by viewModel()

    private var proxyEndpoint: ProxyEndpoint? = null

    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()
    private val orbotHelper by inject<OrbotHelper>()
    private val appDownloadManager by inject<AppDownloadManager>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun initView() {
        b.settingsActivityAllowBypassProgress.visibility = View.GONE
        b.settingsActivityHttpProxyProgress.visibility = View.GONE

        if (isFdroidFlavour()) {
            b.settingsActivityCheckUpdateRl.visibility = View.GONE
        }

        // allow apps part of the vpn to request networks outside of it, effectively letting it bypass the vpn itself
        b.settingsActivityAllowBypassSwitch.isChecked = persistentState.allowBypass
        // display fav icon in dns logs
        b.settingsActivityFavIconSwitch.isChecked = persistentState.fetchFavIcon
        // use all internet-capable networks, not just the active network, as underlying transports for the vpn tunnel
        b.settingsActivityAllNetworkSwitch.isChecked = persistentState.useMultipleNetworks
        // enable logs
        b.settingsActivityEnableLogsSwitch.isChecked = persistentState.logsEnabled
        // Auto start app after reboot
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        // Kill app when firewalled
        b.settingsActivityKillAppSwitch.isChecked = persistentState.killAppOnFirewall
        // check for app updates
        b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate

        b.settingsActivityPreventDnsLeaksSwitch.isChecked = persistentState.preventDnsLeaks

        observeCustomProxy()
        observeBraveMode()

        displayAppThemeUi()
        displayNotificationActionUi()
        displaySocks5Ui()
        displayHttpProxyUi()
        refreshOnDeviceBlocklistUi()

        //For exclude apps
        excludeAppAdapter = ExcludedAppListAdapter(requireContext())
        excludeAppViewModel.excludedAppList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            excludeAppAdapter::submitList))

        FirewallManager.getApplistObserver().observe(viewLifecycleOwner, {
            val excludedCount = it.filter { a -> a.isExcluded }.count()
            b.settingsActivityExcludeAppsCountText.text = getString(R.string.ex_dialog_count,
                                                                    excludedCount.toString())
        })
    }

    private fun observeCustomProxy() {
        appMode.connectedProxy.observe(viewLifecycleOwner, {
            proxyEndpoint = it
            displaySocks5Ui()
        })
    }

    private fun displayHttpProxyUi() {
        if (!isAtleastQ()) {
            b.settingsActivityHttpProxyContainer.visibility = View.GONE
            return
        }

        b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
        b.settingsActivityHttpProxySwitch.isChecked = appMode.isCustomHttpProxyEnabled()
        if (b.settingsActivityHttpProxySwitch.isChecked) {
            b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc,
                                                             persistentState.httpProxyHostAddress,
                                                             persistentState.httpProxyPort.toString())
        }
    }

    private fun displaySocks5Ui() {
        val isCustomSocks5Enabled = appMode.isCustomSocks5Enabled()

        b.settingsActivitySocks5Progress.visibility = View.GONE
        b.settingsActivitySocks5Switch.isChecked = isCustomSocks5Enabled

        if (!isCustomSocks5Enabled) {
            return
        }

        val appName = if (proxyEndpoint?.proxyAppName == getString(
                R.string.settings_app_list_default_app)) {
            getString(R.string.settings_app_list_default_app)
        } else {
            FirewallManager.getAppInfoByPackage(proxyEndpoint?.proxyAppName)?.appName
        }
        b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_desc,
                                                      proxyEndpoint?.proxyIP,
                                                      proxyEndpoint?.proxyPort.toString(), appName)
    }

    private fun displayNotificationActionUi() {
        b.settingsActivityNotificationRl.isEnabled = true
        when (NotificationActionType.getNotificationActionType(
            persistentState.notificationActionType)) {
            NotificationActionType.PAUSE_STOP -> {
                b.genSettingsNotificationDesc.text = getString(R.string.settings_notification_desc,
                                                               getString(
                                                                   R.string.settings_notification_desc1))
            }
            NotificationActionType.DNS_FIREWALL -> {
                b.genSettingsNotificationDesc.text = getString(R.string.settings_notification_desc,
                                                               getString(
                                                                   R.string.settings_notification_desc2))
            }
            NotificationActionType.NONE -> {
                b.genSettingsNotificationDesc.text = getString(R.string.settings_notification_desc,
                                                               getString(
                                                                   R.string.settings_notification_desc3))
            }
        }
    }

    private fun displayAppThemeUi() {
        b.settingsActivityThemeRl.isEnabled = true
        when (persistentState.theme) {
            Themes.SYSTEM_DEFAULT.id -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_1))
            }
            Themes.LIGHT.id -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_2))
            }
            Themes.DARK.id -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_3))
            }
            else -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_4))
            }
        }
    }

    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivityExcludeAppsRl.alpha = 0.5f
            b.settingsActivityAllowBypassRl.alpha = 0.5f
        } else {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
            b.settingsActivityExcludeAppsRl.alpha = 1f
            b.settingsActivityAllowBypassRl.alpha = 1f
        }
        b.settingsActivityOnDeviceBlockRl.isEnabled = !isLockdown
        b.settingsActivityExcludeAppsRl.isEnabled = !isLockdown
        b.settingsActivityAllowBypassSwitch.isEnabled = !isLockdown
        b.settingsActivityExcludeAppsImg.isEnabled = !isLockdown
    }

    private fun refreshOrbotUi() {
        // Checks whether the Orbot is installed.
        // If not, then prompt the user for installation.
        // Else, enable the Orbot bottom sheet fragment.
        if (!FirewallManager.isOrbotInstalled()) {
            b.settingsActivityHttpOrbotDesc.text = getString(R.string.settings_orbot_install_desc)
            return
        }

        if (!appMode.isOrbotProxyEnabled()) {
            b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
            return
        }

        when (appMode.getProxyType()) {
            AppMode.ProxyType.HTTP.name -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_2)
            }
            AppMode.ProxyType.SOCKS5.name -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_1)
            }
            AppMode.ProxyType.HTTP_SOCKS5.name -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_3)
            }
            else -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
            }
        }
    }

    private fun setupClickListeners() {
        b.settingsActivityEnableLogsSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.logsEnabled = b
        }

        b.settingsActivityFavIconSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.fetchFavIcon = b
        }

        b.settingsActivityPreventDnsLeaksSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.preventDnsLeaks = b
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.prefAutoStartBootUp = b
        }

        b.settingsActivityKillAppSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.killAppOnFirewall = b
        }


        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.checkForAppUpdate = b
        }

        b.settingsActivityAllNetworkSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.useMultipleNetworks = b
        }

        b.settingsActivityAllowBypassSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            persistentState.allowBypass = checked
            b.settingsActivityAllowBypassSwitch.isEnabled = false
            b.settingsActivityAllowBypassSwitch.visibility = View.INVISIBLE
            b.settingsActivityAllowBypassProgress.visibility = View.VISIBLE
            delay(TimeUnit.SECONDS.toMillis(1L)) {
                if (isAdded) {
                    b.settingsActivityAllowBypassSwitch.isEnabled = true
                    b.settingsActivityAllowBypassProgress.visibility = View.GONE
                    b.settingsActivityAllowBypassSwitch.visibility = View.VISIBLE
                }
            }
        }

        b.settingsActivityVpnLockdownDesc.setOnClickListener {
            openVpnProfile(requireContext())
        }

        b.settingsActivitySocks5Switch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!checked) {
                appMode.removeProxy(AppMode.ProxyType.SOCKS5, AppMode.ProxyProvider.CUSTOM)
                b.settingsActivitySocks5Desc.text = getString(
                    R.string.settings_socks_forwarding_default_desc)
                return@setOnCheckedChangeListener
            }

            if (!appMode.canEnableSocks5Proxy()) {
                Utilities.showToastUiCentered(requireContext(),
                                              getString(R.string.settings_socks5_disabled_error),
                                              Toast.LENGTH_SHORT)
                b.settingsActivitySocks5Switch.isChecked = false
                return@setOnCheckedChangeListener
            }

            showSocks5ProxyDialog()
        }

        b.settingsActivityOrbotImg.setOnClickListener {
            handleOrbotUiEvent()
        }

        b.settingsActivityOrbotContainer.setOnClickListener {
            handleOrbotUiEvent()
        }

        b.settingsActivityHttpProxySwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!checked) {
                appMode.removeProxy(AppMode.ProxyType.HTTP, AppMode.ProxyProvider.CUSTOM)
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
                return@setOnCheckedChangeListener
            }

            if (!appMode.canEnableHttpProxy()) {
                Utilities.showToastUiCentered(requireContext(),
                                              getString(R.string.settings_https_disabled_error),
                                              Toast.LENGTH_SHORT)
                b.settingsActivityHttpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            showHttpProxyDialog(checked)
        }

        b.settingsActivityExcludeAppsImg.setOnClickListener {
            enableAfterDelay(500, b.settingsActivityExcludeAppsImg)
            showExcludeAppDialog(excludeAppAdapter, excludeAppViewModel)
        }

        b.settingsActivityExcludeAppsRl.setOnClickListener {
            enableAfterDelay(500, b.settingsActivityExcludeAppsRl)
            showExcludeAppDialog(excludeAppAdapter, excludeAppViewModel)
        }

        b.settingsActivityThemeRl.setOnClickListener {
            enableAfterDelay(500, b.settingsActivityThemeRl)
            showThemeDialog()
        }

        // Ideally this property should be part of VPN category / section.
        // As of now the VPN section will be disabled when the
        // VPN is in lockdown mode.
        // TODO - Find a way to place this property to place in correct section.
        b.settingsActivityNotificationRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityNotificationRl)
            showNotificationActionDialog()
        }

        b.settingsActivityOnDeviceBlockSwitch.setOnCheckedChangeListener(null)
        b.settingsActivityOnDeviceBlockSwitch.setOnClickListener {
            go {
                uiCtx {
                    enableAfterDelayCo(TimeUnit.SECONDS.toMillis(1L),
                                       b.settingsActivityOnDeviceBlockSwitch)

                    b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE
                    if (!b.settingsActivityOnDeviceBlockSwitch.isChecked) {
                        removeBraveDNSLocal()
                        return@uiCtx
                    }

                    b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist) {
                        setBraveDNSLocal() // TODO: Move this to vpnService observer
                        b.settingsActivityOnDeviceBlockDesc.text = getString(
                            R.string.settings_local_blocklist_in_use,
                            persistentState.numberOfLocalBlocklists.toString())
                    } else {
                        b.settingsActivityOnDeviceBlockSwitch.isChecked = false
                        if (VpnController.isVpnLockdown()) {
                            showVpnLockdownDownloadDialog()
                        } else {
                            showDownloadDialog()
                        }
                    }
                }
            }
        }

        b.settingsActivityOnDeviceBlockConfigureBtn.setOnClickListener {
            val intent = Intent(requireContext(), DNSConfigureWebViewActivity::class.java)
            val stamp = persistentState.localBlocklistStamp
            if (DEBUG) Log.d(LOG_TAG_VPN, "Stamp value in settings screen: $stamp")
            intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            intent.putExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA,
                            DNSConfigureWebViewActivity.LOCAL)
            intent.putExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA, stamp)
            requireContext().startActivity(intent)
        }

        b.settingsActivityOnDeviceBlockRefreshBtn.setOnClickListener {
            updateBlocklistIfNeeded(isRefresh = true)
        }

        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        workManager.getWorkInfosByTagLiveData(DownloadConstants.DOWNLOAD_TAG).observe(
            viewLifecycleOwner, { workInfoList ->
                val workInfo = workInfoList?.getOrNull(0) ?: return@observe
                Log.i(LOG_TAG_DOWNLOAD,
                      "WorkManager state: ${workInfo.state} for ${DownloadConstants.DOWNLOAD_TAG}")
                if (WorkInfo.State.ENQUEUED == workInfo.state || WorkInfo.State.RUNNING == workInfo.state) {
                    onDownloadStart()
                } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                    onDownloadAbort()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(DownloadConstants.DOWNLOAD_TAG)
                    workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
                }
            })

        workManager.getWorkInfosByTagLiveData(DownloadConstants.FILE_TAG).observe(
            viewLifecycleOwner, { workInfoList ->
                val workInfo = workInfoList?.getOrNull(0) ?: return@observe
                Log.i(LOG_TAG_DOWNLOAD,
                      "WorkManager state: ${workInfo.state} for ${DownloadConstants.FILE_TAG}")
                if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                    onDownloadSuccess()
                    workManager.pruneWork()
                } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                    onDownloadAbort()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
                } else { // state == blocked, queued, or running
                    // no-op
                }
            })
    }

    private fun refreshOnDeviceBlocklistUi() {
        if (isPlayStoreFlavour()) { // hide the parent view
            b.settingsActivityOnDeviceBlockRl.visibility = View.GONE
            return
        }

        b.settingsActivityOnDeviceBlockRl.visibility = View.VISIBLE
        // this switch is hidden to show the download-progress bar,
        // enable it whenever the download is complete
        b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
        if (persistentState.blocklistEnabled) {
            b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
            b.settingsActivityOnDeviceBlockSwitch.isChecked = true
        } else {
            b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
        }

        refreshOnDeviceBlocklistStatus()
    }

    private fun refreshOnDeviceBlocklistStatus() {
        if (!persistentState.blocklistEnabled) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(
                R.string.settings_local_blocklist_desc_1)
            return
        }

        b.settingsActivityOnDeviceBlockDesc.text = getString(
            R.string.settings_local_blocklist_in_use,
            persistentState.numberOfLocalBlocklists.toString())

        b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(
            R.string.settings_local_blocklist_version,
            Utilities.convertLongToTime(persistentState.localBlocklistTimestamp, TIME_FORMAT_2))
    }

    private fun updateBlocklistIfNeeded(isRefresh: Boolean) {
        val timestamp = persistentState.localBlocklistTimestamp
        val appVersionCode = persistentState.appVersion
        val url = "${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_URL}$timestamp&${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_PARAMETER_VCODE}$appVersionCode"
        if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Check for local download, url: $url")
        downloadBlocklistIfNeeded(url, isRefresh)
    }

    private fun downloadBlocklistIfNeeded(url: String, isRefresh: Boolean) {

        val bootstrapClient = OkHttpClient()
        // FIXME: Use user set doh provider
        // using quad9 doh provider
        val dns = DnsOverHttps.Builder().client(bootstrapClient).url(
            "https://dns.quad9.net/dns-query".toHttpUrl()).bootstrapDnsHosts(
            InetAddress.getByName("9.9.9.9"), InetAddress.getByName("149.112.112.112"),
            InetAddress.getByName("2620:fe::9"), InetAddress.getByName("2620:fe::fe")).build()

        val client = bootstrapClient.newBuilder().dns(
            dns).build() // FIXME: Move it to the http-request-helper class
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG_DOWNLOAD,
                      "onFailure, cancelled? ${call.isCanceled()}, exec? ${call.isExecuted()}")
                handleFailedDownload(isRefresh)
            }

            override fun onResponse(call: Call, response: Response) {
                val stringResponse = response.body?.string() ?: return
                response.body?.close()

                val json = JSONObject(stringResponse)
                val version = json.optInt(Constants.JSON_VERSION, 0)
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                                 "client onResponse for refresh blocklist files-  $version")
                if (version != Constants.UPDATE_CHECK_RESPONSE_VERSION) {
                    return
                }

                val shouldUpdate = json.optBoolean(Constants.JSON_UPDATE, false)
                val timestamp = json.optLong(Constants.JSON_LATEST, Constants.INIT_TIME_MS)
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "onResponse:  update? $shouldUpdate")
                if (shouldUpdate) {
                    appDownloadManager.downloadLocalBlocklist(timestamp)
                    return
                }

                handleRedownload(isRefresh, timestamp)
            }
        })
    }

    private fun handleFailedDownload(isRefresh: Boolean) {
        uithread(activity) {
            if (isRefresh) {
                Utilities.showToastUiCentered(requireContext(), getString(
                    R.string.local_blocklist_update_check_failure), Toast.LENGTH_SHORT)
            } else {
                onDownloadAbort()
            }
        }
    }

    private fun handleRedownload(isRefresh: Boolean, timestamp: Long) {
        if (!isRefresh) {
            onDownloadAbort()
            return
        }

        if (VpnController.isVpnLockdown()) {
            showRedownloadDialogLockdown(timestamp)
        } else {
            showRedownloadDialog(timestamp)
        }
    }

    private fun onDownloadAbort() {
        uithread(activity) {
            refreshOnDeviceBlocklistUi()
            b.settingsActivityOnDeviceBlockDesc.text = getString(
                R.string.settings_local_blocklist_desc4)
            Utilities.showToastUiCentered(activity as Context,
                                          getString(R.string.settings_local_blocklist_desc4),
                                          Toast.LENGTH_SHORT)
        }
    }

    private fun onDownloadSuccess() {
        refreshOnDeviceBlocklistUi()
        b.settingsActivityOnDeviceBlockDesc.text = getString(
            R.string.settings_local_blocklist_desc3)
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(
            R.string.settings_local_blocklist_version,
            Utilities.convertLongToTime(persistentState.localBlocklistTimestamp, TIME_FORMAT_2))
    }

    private fun onDownloadStart() {
        uithread(activity) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(
                R.string.settings_local_blocklist_desc2)
            b.settingsActivityOnDeviceBlockSwitch.visibility = View.GONE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
        }
    }

    // FIXME: Verification of BraveDns object should be added in future.
    private fun setBraveDNSLocal() {
        persistentState.blocklistEnabled = true
        refreshOnDeviceBlocklistUi()
    }

    private fun removeBraveDNSLocal() {
        persistentState.blocklistEnabled = false
        refreshOnDeviceBlocklistUi()
    }

    private fun showVpnLockdownDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.download_lockdown_dialog_heading)
        builder.setMessage(R.string.download_lockdown_dialog_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.download_lockdown_dialog_positive)) { _, _ ->
            openVpnProfile(requireContext())
        }
        builder.setNegativeButton(
            getString(R.string.download_lockdown_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun showDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_download)
        builder.setMessage(R.string.local_blocklist_download_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.settings_local_blocklist_dialog_positive)) { _, _ ->
            updateBlocklistIfNeeded(isRefresh = false)
        }
        builder.setNegativeButton(
            getString(R.string.settings_local_blocklist_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun showRedownloadDialogLockdown(timestamp: Long) {
        uithread(activity) {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(R.string.local_blocklist_lockdown_redownload)
            builder.setMessage(getString(R.string.local_blocklist_lockdown_redownload_desc,
                                         Utilities.convertLongToTime(timestamp, TIME_FORMAT_2)))
            builder.setCancelable(false)
            builder.setPositiveButton(
                getString(R.string.local_blocklist_lockdown_positive)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            builder.create().show()
        }
    }

    private fun showRedownloadDialog(timestamp: Long) {
        uithread(activity) {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(R.string.local_blocklist_redownload)
            builder.setMessage(getString(R.string.local_blocklist_redownload_desc,
                                         Utilities.convertLongToTime(timestamp, TIME_FORMAT_2)))
            builder.setCancelable(false)
            builder.setPositiveButton(
                getString(R.string.local_blocklist_positive)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            builder.setNeutralButton(getString(R.string.local_blocklist_neutral)) { _, _ ->
                onDownloadStart()
                appDownloadManager.downloadLocalBlocklist(timestamp)
            }
            builder.create().show()
        }
    }

    private fun showFileCorruptionDialog(timestamp: Long) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_corrupt)
        builder.setMessage(R.string.local_blocklist_corrupt_desc)
        builder.setCancelable(false)
        builder.setNegativeButton(
            getString(R.string.local_blocklist_corrupt_negative)) { dialogInterface, _ ->
            handleFailedDownload(isRefresh = false)
            dialogInterface.dismiss()
        }
        builder.setPositiveButton(getString(R.string.local_blocklist_corrupt_positive)) { _, _ ->
            appDownloadManager.downloadLocalBlocklist(timestamp)
        }
        builder.create().show()
    }

    private fun handleOrbotUiEvent() {
        if (!FirewallManager.isOrbotInstalled()) {
            showOrbotInstallDialog()
            return
        }

        if (!appMode.canEnableOrbotProxy()) {
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.settings_orbot_disabled_error),
                                          Toast.LENGTH_SHORT)
            return
        }

        openOrbotBottomSheet()
    }

    private fun openOrbotBottomSheet() {
        // FIXME #200 - Inject VpnController via Koin
        if (!VpnController.hasTunnel()) {
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.settings_socks5_vpn_disabled_error),
                                          Toast.LENGTH_SHORT)
            return
        }

        val bottomSheetFragment = OrbotBottomSheetFragment()
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showThemeDialog() {
        val alertBuilder = AlertDialog.Builder(requireContext())
        alertBuilder.setTitle(getString(R.string.settings_theme_dialog_title))
        val items = arrayOf(getString(R.string.settings_theme_dialog_themes_1),
                            getString(R.string.settings_theme_dialog_themes_2),
                            getString(R.string.settings_theme_dialog_themes_3),
                            getString(R.string.settings_theme_dialog_themes_4))
        val checkedItem = persistentState.theme
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.theme == which) {
                return@setSingleChoiceItems
            }

            persistentState.theme = which
            when (which) {
                Themes.SYSTEM_DEFAULT.id -> {
                    if (requireActivity().isDarkThemeOn()) {
                        setThemeRecreate(R.style.AppTheme)
                    } else {
                        setThemeRecreate(R.style.AppThemeWhite)
                    }
                }
                Themes.LIGHT.id -> {
                    setThemeRecreate(R.style.AppThemeWhite)
                }
                Themes.DARK.id -> {
                    setThemeRecreate(R.style.AppTheme)
                }
                Themes.TRUE_BLACK.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlack)
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun setThemeRecreate(theme: Int) {
        requireActivity().setTheme(theme)
        requireActivity().recreate()
    }

    private fun showNotificationActionDialog() {
        val alertBuilder = AlertDialog.Builder(requireContext())
        alertBuilder.setTitle(getString(R.string.settings_notification_dialog_title))
        val items = arrayOf(getString(R.string.settings_notification_dialog_option_1),
                            getString(R.string.settings_notification_dialog_option_2),
                            getString(R.string.settings_notification_dialog_option_3))
        val checkedItem = persistentState.notificationActionType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.notificationActionType == which) {
                return@setSingleChoiceItems
            }

            when (NotificationActionType.getNotificationActionType(which)) {
                NotificationActionType.PAUSE_STOP -> {
                    b.genSettingsNotificationDesc.text = getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc1))
                    persistentState.notificationActionType = NotificationActionType.PAUSE_STOP.action
                }
                NotificationActionType.DNS_FIREWALL -> {
                    b.genSettingsNotificationDesc.text = getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc2))
                    persistentState.notificationActionType = NotificationActionType.DNS_FIREWALL.action
                }
                NotificationActionType.NONE -> {
                    b.genSettingsNotificationDesc.text = getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc3))
                    persistentState.notificationActionType = NotificationActionType.NONE.action
                }
            }
        }
        alertBuilder.create().show()
    }

    override fun onResume() {
        super.onResume()
        refreshOnDeviceBlocklistUi()
        refreshOrbotUi()
        handleLockdownModeIfNeeded()
        handleProxyUi()
    }

    private fun observeBraveMode() {
        appMode.braveModeObserver.observe(viewLifecycleOwner, {
            when (it) {
                AppMode.BraveMode.DNS.mode -> handleDnsModeUi()
                AppMode.BraveMode.FIREWALL.mode -> handleFirewallModeUi()
                AppMode.BraveMode.DNS_FIREWALL.mode -> handleDnsFirewallModeUi()
            }
        })
    }

    private fun handleDnsModeUi() {
        b.settingsActivityPreventDnsLeaksRl.alpha = 0.5f
        b.settingsActivityOnDeviceBlockRl.alpha = 1f
        b.settingsActivityFavIconRl.alpha = 1f
        b.settingsActivityPreventDnsLeaksSwitch.isClickable = false
        b.settingsActivityOnDeviceBlockConfigureBtn.isClickable = true
        b.settingsActivityOnDeviceBlockRefreshBtn.isClickable = true
        b.settingsActivityOnDeviceBlockSwitch.isClickable = true
        b.settingsActivityFavIconSwitch.isClickable = true
    }

    private fun handleFirewallModeUi() {
        b.settingsActivityPreventDnsLeaksRl.alpha = 0.5f
        b.settingsActivityOnDeviceBlockRl.alpha = 0.5f
        b.settingsActivityFavIconRl.alpha = 0.5f
        b.settingsActivityPreventDnsLeaksSwitch.isClickable = false
        b.settingsActivityOnDeviceBlockConfigureBtn.isClickable = false
        b.settingsActivityOnDeviceBlockRefreshBtn.isClickable = false
        b.settingsActivityOnDeviceBlockSwitch.isClickable = false
        b.settingsActivityFavIconSwitch.isClickable = false
    }

    private fun handleDnsFirewallModeUi() {
        b.settingsActivityPreventDnsLeaksRl.alpha = 1f
        b.settingsActivityOnDeviceBlockRl.alpha = 1f
        b.settingsActivityFavIconRl.alpha = 1f
        b.settingsActivityPreventDnsLeaksSwitch.isClickable = true
        b.settingsActivityOnDeviceBlockConfigureBtn.isClickable = true
        b.settingsActivityOnDeviceBlockRefreshBtn.isClickable = true
        b.settingsActivityOnDeviceBlockSwitch.isClickable = true
        b.settingsActivityFavIconSwitch.isClickable = true
    }

    // Should be in disabled state when the brave mode is in DNS only / Vpn in lockdown mode.
    private fun handleProxyUi() {
        val canEnableProxy = appMode.isDnsOnlyMode()

        if (canEnableProxy) {
            b.settingsActivitySocks5Rl.alpha = 1f
            b.settingsActivityHttpProxyContainer.alpha = 1f
            b.settingsActivityOrbotContainer.alpha = 1f
        } else {
            b.settingsActivitySocks5Rl.alpha = 0.5f
            b.settingsActivityHttpProxyContainer.alpha = 0.5f
            b.settingsActivityOrbotContainer.alpha = 0.5f
        }
        // Orbot
        b.settingsActivityOrbotImg.isEnabled = canEnableProxy
        b.settingsActivityOrbotContainer.isEnabled = canEnableProxy
        // SOCKS5
        b.settingsActivitySocks5Switch.isEnabled = canEnableProxy
        // HTTP Proxy
        b.settingsActivityHttpProxySwitch.isEnabled = canEnableProxy
    }

    private fun showHttpProxyDialog(isEnabled: Boolean) {
        if (!isEnabled) {
            appMode.removeProxy(AppMode.ProxyType.HTTP, AppMode.ProxyProvider.CUSTOM)
            b.settingsActivityHttpProxySwitch.isChecked = false
            b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
            return
        }

        var isValid: Boolean
        var host: String
        var port = INVALID_PORT
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        dialog.setTitle(getString(R.string.settings_http_proxy_dialog_title))
        val dialogBinding = DialogSetHttpProxyBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        val applyURLBtn = dialogBinding.dialogHttpProxyOkBtn
        val cancelURLBtn = dialogBinding.dialogHttpProxyCancelBtn
        val hostAddressEditText = dialogBinding.dialogHttpProxyEditText
        val portEditText = dialogBinding.dialogHttpProxyEditTextPort
        val errorTxt = dialogBinding.dialogHttpProxyFailureText

        val hostName = persistentState.httpProxyHostAddress
        val portAddr = persistentState.httpProxyPort
        if (hostName.isNotBlank()) {
            hostAddressEditText.setText(hostName, TextView.BufferType.EDITABLE)
        }
        // TODO portAddress check for 0 is needed only for version v053f. Remove.
        if (portAddr != INVALID_PORT || portAddr == UNSPECIFIED_PORT) {
            portEditText.setText(portAddr.toString(), TextView.BufferType.EDITABLE)
        } else {
            portEditText.setText(Constants.HTTP_PROXY_PORT, TextView.BufferType.EDITABLE)
        }
        applyURLBtn.setOnClickListener {
            host = hostAddressEditText.text.toString()
            var isHostValid = true
            try {
                port = portEditText.text.toString().toInt()
                isValid = Utilities.isValidLocalPort(port)
                if (!isValid) {
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                    errorTxt.visibility = View.VISIBLE
                }
            } catch (e: NumberFormatException) {
                Log.e(LOG_TAG_VPN, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                errorTxt.visibility = View.VISIBLE
                isValid = false
            }

            if (host.isBlank()) {
                isHostValid = false
                errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                errorTxt.visibility = View.VISIBLE
            }

            if (isValid && isHostValid) {
                errorTxt.visibility = View.INVISIBLE
                io {
                    appMode.addProxy(AppMode.ProxyType.HTTP, AppMode.ProxyProvider.CUSTOM)
                    appMode.insertCustomHttpProxy(host, port)
                }
                dialog.dismiss()
                Toast.makeText(requireContext(),
                               getString(R.string.settings_http_proxy_toast_success),
                               Toast.LENGTH_SHORT).show()
                if (b.settingsActivityHttpProxySwitch.isChecked) {
                    b.settingsActivityHttpProxyDesc.text = getString(
                        R.string.settings_http_proxy_desc, host, port.toString())
                }
            }
        }
        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
            appMode.removeProxy(AppMode.ProxyType.HTTP, AppMode.ProxyProvider.CUSTOM)
            b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
            b.settingsActivityHttpProxySwitch.isChecked = false
        }

    }

    /**
     * Prompt user to download the Orbot app based on the current BUILDCONFIG flavor.
     */
    private fun showOrbotInstallDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.orbot_install_dialog_title)
        builder.setMessage(R.string.orbot_install_dialog_message)
        builder.setPositiveButton(getString(R.string.orbot_install_dialog_positive)) { _, _ ->
            handleOrbotInstall()
        }
        builder.setNegativeButton(getString(R.string.orbot_install_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.setNeutralButton(getString(R.string.orbot_install_dialog_neutral)) { _, _ ->
            launchOrbotWebsite()
        }
        builder.create().show()
    }

    private fun launchOrbotWebsite() {
        val intent = Intent(Intent.ACTION_VIEW, getString(R.string.orbot_website_link).toUri())
        startActivity(intent)
    }

    private fun handleOrbotInstall() {
        startOrbotInstallActivity(orbotHelper.getIntentForDownload())
    }

    private fun startOrbotInstallActivity(intent: Intent?) {
        if (intent == null) {
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.orbot_install_activity_error),
                                          Toast.LENGTH_SHORT)
            return
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.orbot_install_activity_error),
                                          Toast.LENGTH_SHORT)
        }
    }

    private fun showExcludeAppDialog(recyclerAdapter: ExcludedAppListAdapter,
                                     excludeAppViewModel: ExcludedAppViewModel) {
        val themeID = getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val excludeAppDialog = ExcludeAppsDialog(requireActivity(), recyclerAdapter,
                                                 excludeAppViewModel, themeID)
        excludeAppDialog.setCanceledOnTouchOutside(false)
        excludeAppDialog.show()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showSocks5ProxyDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogBinding = DialogSetProxyBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        val applyURLBtn = dialogBinding.dialogProxyApplyBtn
        val cancelURLBtn = dialogBinding.dialogProxyCancelBtn
        val ipAddressEditText: EditText = dialogBinding.dialogProxyEditIp
        val portEditText: EditText = dialogBinding.dialogProxyEditPort
        val appNameSpinner: Spinner = dialogBinding.dialogProxySpinnerAppname
        val errorTxt: TextView = dialogBinding.dialogProxyErrorText
        val userNameEditText: EditText = dialogBinding.dialogProxyEditUsername
        val passwordEditText: EditText = dialogBinding.dialogProxyEditPassword
        val udpBlockCheckBox: CheckBox = dialogBinding.dialogProxyUdpCheck

        udpBlockCheckBox.isChecked = persistentState.udpBlockedSettings

        val appNames: MutableList<String> = ArrayList()
        appNames.add(getString(R.string.settings_app_list_default_app))
        appNames.addAll(FirewallManager.getAllAppNames())
        val proxySpinnerAdapter = ArrayAdapter(requireContext(),
                                               android.R.layout.simple_spinner_dropdown_item,
                                               appNames)
        appNameSpinner.adapter = proxySpinnerAdapter
        if (proxyEndpoint != null && !proxyEndpoint?.proxyIP.isNullOrBlank()) {
            ipAddressEditText.setText(proxyEndpoint?.proxyIP, TextView.BufferType.EDITABLE)
            portEditText.setText(proxyEndpoint?.proxyPort.toString(), TextView.BufferType.EDITABLE)
            userNameEditText.setText(proxyEndpoint?.userName.toString(),
                                     TextView.BufferType.EDITABLE)
            if (!proxyEndpoint?.proxyAppName.isNullOrBlank() && proxyEndpoint?.proxyAppName != getString(
                    R.string.settings_app_list_default_app)) {
                val packageName = proxyEndpoint?.proxyAppName
                val app = FirewallManager.getAppInfoByPackage(packageName)
                var position = 0
                for ((i, item) in appNames.withIndex()) {
                    if (item == app?.appName) {
                        position = i
                    }
                }
                appNameSpinner.setSelection(position)
            } else {
                // no-op
            }
        } else {
            ipAddressEditText.setText(Constants.SOCKS_DEFAULT_IP, TextView.BufferType.EDITABLE)
            portEditText.setText(Constants.SOCKS_DEFAULT_PORT, TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var port = 0
            var isValid: Boolean
            var isIPValid = true
            var isUDPBlock = false
            val mode: String = getString(R.string.cd_dns_proxy_mode_external)
            val appName: String = appNames[appNameSpinner.selectedItemPosition]
            val appPackageName = if (appName.isBlank() || appName == getString(
                    R.string.settings_app_list_default_app)) {
                appNames[0]
            } else {
                FirewallManager.getPackageNameByAppName(appName)
            }
            val ip: String = ipAddressEditText.text.toString()

            if (ip.isBlank()) {
                isIPValid = false
                errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                errorTxt.visibility = View.VISIBLE
            }

            try {
                port = portEditText.text.toString().toInt()
                isValid = if (Utilities.isLanIpv4(ip)) {
                    Utilities.isValidLocalPort(port)
                } else {
                    Utilities.isValidPort(port)
                }
                if (!isValid) {
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                }
            } catch (e: NumberFormatException) {
                Log.w(LOG_TAG_VPN, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                isValid = false
            }
            if (udpBlockCheckBox.isChecked) {
                isUDPBlock = true
            }

            val userName: String = userNameEditText.text.toString()
            val password: String = passwordEditText.text.toString()
            if (isValid && isIPValid) {
                //Do the Socks5 Proxy setting there
                persistentState.udpBlockedSettings = udpBlockCheckBox.isChecked
                insertProxyEndpointDB(mode, Constants.SOCKS, appPackageName, ip, port, userName,
                                      password, isUDPBlock)
                b.settingsActivitySocks5Desc.text = getString(
                    R.string.settings_socks_forwarding_desc, ip, port.toString(), appName)
                dialog.dismiss()
            } else {
                // no-op
            }
        }

        cancelURLBtn.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = false
            appMode.removeProxy(AppMode.ProxyType.SOCKS5, AppMode.ProxyProvider.CUSTOM)
            b.settingsActivitySocks5Desc.text = getString(
                R.string.settings_socks_forwarding_default_desc)
            dialog.dismiss()
        }
        dialog.show()
    }


    private fun insertProxyEndpointDB(mode: String, name: String, appName: String?, ip: String,
                                      port: Int, userName: String, password: String,
                                      isUDPBlock: Boolean) {
        if (appName == null) return

        b.settingsActivitySocks5Switch.isEnabled = false
        b.settingsActivitySocks5Switch.visibility = View.GONE
        b.settingsActivitySocks5Progress.visibility = View.VISIBLE
        delay(TimeUnit.SECONDS.toMillis(1L)) {
            if (isAdded) {
                b.settingsActivitySocks5Switch.isEnabled = true
                b.settingsActivitySocks5Progress.visibility = View.GONE
                b.settingsActivitySocks5Switch.visibility = View.VISIBLE
            }
        }
        io {
            var proxyName = name
            if (proxyName.isBlank()) {
                proxyName = if (mode == getString(R.string.cd_dns_proxy_mode_internal)) {
                    appName
                } else ip
            }
            val proxyEndpoint = ProxyEndpoint(id = 0, proxyName, proxyMode = 1, mode, appName, ip,
                                              port, userName, password, isSelected = true,
                                              isCustom = true, isUDP = isUDPBlock,
                                              modifiedDataTime = 0L, latency = 0)

            appMode.insertCustomSocks5Proxy(proxyEndpoint)
        }
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        delay(ms) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

    // TODO: move mixed workloads to coroutines
    private suspend fun enableAfterDelayCo(ms: Long, vararg views: View) {
        uiCtx {
            for (v in views) v.isEnabled = false

            delay(ms)

            if (!isAdded) return@uiCtx

            for (v in views) v.isEnabled = true
        }
    }

    private fun uithread(a: FragmentActivity?, f: () -> Unit) {
        a?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            f()
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }
}
