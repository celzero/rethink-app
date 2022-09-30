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
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.databinding.DialogSetHttpProxyBinding
import com.celzero.bravedns.databinding.DialogSetProxyBinding
import com.celzero.bravedns.databinding.FragmentSettingsScreenBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.delay
import com.celzero.bravedns.util.Utilities.Companion.isAtleastQ
import com.celzero.bravedns.util.Utilities.Companion.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment(R.layout.fragment_settings_screen) {
    private val b by viewBinding(FragmentSettingsScreenBinding::bind)

    private var proxyEndpoint: ProxyEndpoint? = null

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()

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
        // use custom download manager
        b.settingsActivityDownloaderSwitch.isChecked = persistentState.useCustomDownloadManager
        // for protocol translation, enable only on DNS/DNS+Firewall mode
        if (appConfig.getBraveMode().isDnsActive()) {
            b.settingsActivityPtransSwitch.isChecked = persistentState.protocolTranslationType
        } else {
            persistentState.protocolTranslationType = false
            b.settingsActivityPtransSwitch.isChecked = false
        }

        observeCustomProxy()

        displayInternetProtocolUi()
        displayAppThemeUi()
        displayNotificationActionUi()
        displaySocks5Ui()
        displayHttpProxyUi()
    }

    private fun observeCustomProxy() {
        appConfig.connectedProxy.observe(viewLifecycleOwner) {
            proxyEndpoint = it
            displaySocks5Ui()
        }
    }

    private fun displayHttpProxyUi() {
        if (!isAtleastQ()) {
            b.settingsActivityHttpProxyContainer.visibility = View.GONE
            return
        }

        b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
        b.settingsActivityHttpProxySwitch.isChecked = appConfig.isCustomHttpProxyEnabled()
        if (b.settingsActivityHttpProxySwitch.isChecked) {
            b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc,
                                                             persistentState.httpProxyHostAddress,
                                                             persistentState.httpProxyPort.toString())
        }
    }

    private fun displaySocks5Ui() {
        val isCustomSocks5Enabled = appConfig.isCustomSocks5Enabled()

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

    private fun displayInternetProtocolUi() {
        b.settingsActivityIpRl.isEnabled = true
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_text_ipv4))
                b.settingsActivityPtransRl.visibility = View.GONE
            }
            InternetProtocol.IPv6.id -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_dialog_ipv6))
                b.settingsActivityPtransRl.visibility = View.VISIBLE
            }
            InternetProtocol.IPv46.id -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_dialog_ipv46))
                b.settingsActivityPtransRl.visibility = View.GONE
            }
            else -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_text_ipv4))
                b.settingsActivityPtransRl.visibility = View.GONE
            }
        }
    }

    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivityAllowBypassRl.alpha = 0.5f
        } else {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
            b.settingsActivityAllowBypassRl.alpha = 1f
        }
        b.settingsActivityAllowBypassSwitch.isEnabled = !isLockdown
    }

    private fun refreshOrbotUi() {
        // Checks whether the Orbot is installed.
        // If not, then prompt the user for installation.
        // Else, enable the Orbot bottom sheet fragment.
        if (!FirewallManager.isOrbotInstalled()) {
            b.settingsActivityHttpOrbotDesc.text = getString(R.string.settings_orbot_install_desc)
            return
        }

        if (!appConfig.isOrbotProxyEnabled()) {
            b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
            return
        }

        io {
            val isOrbotDns = appConfig.isOrbotDns()

            uiCtx {

                when (appConfig.getProxyType()) {
                    AppConfig.ProxyType.HTTP.name -> {
                        b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_2)
                    }
                    AppConfig.ProxyType.SOCKS5.name -> {
                        if (isOrbotDns) {
                            b.settingsActivityHttpOrbotDesc.text = getString(
                                R.string.orbot_bs_status_1, getString(R.string.orbot_status_arg_3))
                        } else {
                            b.settingsActivityHttpOrbotDesc.text = getString(
                                R.string.orbot_bs_status_1, getString(R.string.orbot_status_arg_2))
                        }
                    }
                    AppConfig.ProxyType.HTTP_SOCKS5.name -> {
                        if (isOrbotDns) {
                            b.settingsActivityHttpOrbotDesc.text = getString(
                                R.string.orbot_bs_status_3, getString(R.string.orbot_status_arg_3))
                        } else {
                            b.settingsActivityHttpOrbotDesc.text = getString(
                                R.string.orbot_bs_status_3, getString(R.string.orbot_status_arg_2))
                        }
                    }
                    else -> {
                        b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
                    }
                }
            }
        }

    }

    private fun setupClickListeners() {
        b.settingsActivityEnableLogsRl.setOnClickListener {
            b.settingsActivityEnableLogsSwitch.isChecked = !b.settingsActivityEnableLogsSwitch.isChecked
        }

        b.settingsActivityEnableLogsSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.logsEnabled = b
        }

        b.settingsActivityAutoStartRl.setOnClickListener {
            b.settingsActivityAutoStartSwitch.isChecked = !b.settingsActivityAutoStartSwitch.isChecked
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.prefAutoStartBootUp = b
        }

        b.settingsActivityKillAppRl.setOnClickListener {
            b.settingsActivityKillAppSwitch.isChecked = !b.settingsActivityKillAppSwitch.isChecked
        }

        b.settingsActivityKillAppSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.killAppOnFirewall = b
        }

        b.settingsActivityCheckUpdateRl.setOnClickListener {
            b.settingsActivityCheckUpdateSwitch.isChecked = !b.settingsActivityCheckUpdateSwitch.isChecked
        }

        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.checkForAppUpdate = b
        }

        b.settingsActivityAllNetworkRl.setOnClickListener {
            b.settingsActivityAllNetworkSwitch.isChecked = !b.settingsActivityAllNetworkSwitch.isChecked
        }

        b.settingsActivityAllNetworkSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            if (b) {
                showAllNetworksDialog()
                return@setOnCheckedChangeListener
            }

            persistentState.useMultipleNetworks = b
        }

        b.settingsActivityAllowBypassRl.setOnClickListener {
            b.settingsActivityAllowBypassSwitch.isChecked = !b.settingsActivityAllowBypassSwitch.isChecked
        }

        b.settingsActivityAllowBypassSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            persistentState.allowBypass = checked
            b.settingsActivityAllowBypassSwitch.isEnabled = false
            b.settingsActivityAllowBypassSwitch.visibility = View.INVISIBLE
            b.settingsActivityAllowBypassProgress.visibility = View.VISIBLE

            delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
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

        b.settingsActivitySocks5Rl.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = !b.settingsActivitySocks5Switch.isChecked
        }

        b.settingsActivitySocks5Switch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!checked) {
                appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
                b.settingsActivitySocks5Desc.text = getString(
                    R.string.settings_socks_forwarding_default_desc)
                return@setOnCheckedChangeListener
            }

            if (!appConfig.canEnableSocks5Proxy()) {
                showToastUiCentered(requireContext(),
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

        b.settingsActivityHttpProxyContainer.setOnClickListener {
            b.settingsActivityHttpProxySwitch.isChecked = !b.settingsActivityHttpProxySwitch.isChecked
        }

        b.settingsActivityHttpProxySwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!checked) {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
                return@setOnCheckedChangeListener
            }

            if (!appConfig.canEnableHttpProxy()) {
                showToastUiCentered(requireContext(),
                                    getString(R.string.settings_https_disabled_error),
                                    Toast.LENGTH_SHORT)
                b.settingsActivityHttpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            showHttpProxyDialog(checked)
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

        b.settingsActivityDownloaderRl.setOnClickListener {
            b.settingsActivityDownloaderSwitch.isChecked = !b.settingsActivityDownloaderSwitch.isChecked
        }

        b.settingsActivityDownloaderSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.useCustomDownloadManager = b
        }

        b.settingsActivityIpRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityIpRl)
            showIpDialog()
        }

        b.settingsActivityPtransRl.setOnClickListener {
            b.settingsActivityPtransSwitch.isChecked = !b.settingsActivityPtransSwitch.isChecked
        }

        b.settingsActivityPtransSwitch.setOnCheckedChangeListener { _, isSelected ->
            if (appConfig.getBraveMode().isDnsActive()) {
                persistentState.protocolTranslationType = isSelected
            } else {
                b.settingsActivityPtransSwitch.isChecked = false
                showToastUiCentered(requireContext(),
                                    getString(R.string.settings_protocol_translation_dns_inactive),
                                    Toast.LENGTH_SHORT)
            }
        }

        // settings_activity_import_export_rl
        b.settingsActivityImportExportRl.setOnClickListener {
            invokeImportExport()
        }
    }

    private fun invokeImportExport() {
        val bottomSheetFragment = BackupRestoreBottomSheetFragment()
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun handleOrbotUiEvent() {
        if (!FirewallManager.isOrbotInstalled()) {
            showOrbotInstallDialog()
            return
        }

        if (!appConfig.canEnableOrbotProxy()) {
            showToastUiCentered(requireContext(), getString(R.string.settings_orbot_disabled_error),
                                Toast.LENGTH_SHORT)
            return
        }

        openOrbotBottomSheet()
    }

    private fun openOrbotBottomSheet() {
        if (!VpnController.hasTunnel()) {
            showToastUiCentered(requireContext(),
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

    private fun showIpDialog() {
        val alertBuilder = AlertDialog.Builder(requireContext())
        alertBuilder.setTitle(getString(R.string.settings_ip_dialog_title))
        val items = arrayOf(getString(R.string.settings_ip_dialog_ipv4),
                            getString(R.string.settings_ip_dialog_ipv6),
                            getString(R.string.settings_ip_dialog_ipv46))
        val checkedItem = persistentState.internetProtocolType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            // return if already selected item is same as current item
            if (persistentState.internetProtocolType == which) {
                return@setSingleChoiceItems
            }

            val protocolType = InternetProtocol.getInternetProtocol(which)
            persistentState.internetProtocolType = protocolType.id

            displayInternetProtocolUi()
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
        refreshOrbotUi()
        handleLockdownModeIfNeeded()
        handleProxyUi()
    }

    // Should be in disabled state when the brave mode is in DNS only / Vpn in lockdown mode.
    private fun handleProxyUi() {
        val canEnableProxy = appConfig.canEnableProxy()

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
            appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
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
                    appConfig.addProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
                    appConfig.insertCustomHttpProxy(host, port)
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
            appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
            b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
            b.settingsActivityHttpProxySwitch.isChecked = false
        }

    }

    private fun showAllNetworksDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.settings_all_networks_dialog_title))
        builder.setMessage(getString(R.string.settings_all_networks_dialog_message))
        builder.setPositiveButton(
            getString(R.string.settings_all_networks_dialog_positive_btn)) { dialog, _ ->
            b.settingsActivityAllNetworkSwitch.isChecked = true
            persistentState.useMultipleNetworks = true
            dialog.dismiss()
        }

        builder.setNegativeButton(
            getString(R.string.settings_all_networks_dialog_negative_btn)) { dialog, _ ->
            b.settingsActivityAllNetworkSwitch.isChecked = false
            persistentState.useMultipleNetworks = false
            dialog.dismiss()
        }
        builder.setCancelable(false)
        builder.create().show()
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
            showToastUiCentered(requireContext(), getString(R.string.orbot_install_activity_error),
                                Toast.LENGTH_SHORT)
            return
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(requireContext(), getString(R.string.orbot_install_activity_error),
                                Toast.LENGTH_SHORT)
        }
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
                insertSocks5ProxyEndpointDB(mode, appPackageName, ip, port, userName, password,
                                            isUDPBlock)
                b.settingsActivitySocks5Desc.text = getString(
                    R.string.settings_socks_forwarding_desc, ip, port.toString(), appName)
                dialog.dismiss()
            } else {
                // no-op
            }
        }

        cancelURLBtn.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = false
            appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
            b.settingsActivitySocks5Desc.text = getString(
                R.string.settings_socks_forwarding_default_desc)
            dialog.dismiss()
        }
        dialog.show()
    }


    private fun insertSocks5ProxyEndpointDB(mode: String, appName: String?, ip: String, port: Int,
                                            userName: String, password: String,
                                            isUDPBlock: Boolean) {
        if (appName == null) return

        b.settingsActivitySocks5Switch.isEnabled = false
        b.settingsActivitySocks5Switch.visibility = View.GONE
        b.settingsActivitySocks5Progress.visibility = View.VISIBLE
        delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
            if (isAdded) {
                b.settingsActivitySocks5Switch.isEnabled = true
                b.settingsActivitySocks5Progress.visibility = View.GONE
                b.settingsActivitySocks5Switch.visibility = View.VISIBLE
            }
        }
        io {
            val proxyName = Constants.SOCKS
            val proxyEndpoint = ProxyEndpoint(id = 0, proxyName, proxyMode = 1, mode, appName, ip,
                                              port, userName, password, isSelected = true,
                                              isCustom = true, isUDP = isUDPBlock,
                                              modifiedDataTime = 0L, latency = 0)

            appConfig.insertCustomSocks5Proxy(proxyEndpoint)
        }
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

}
