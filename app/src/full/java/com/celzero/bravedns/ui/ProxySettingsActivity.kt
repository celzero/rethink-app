/*
 * Copyright 2023 RethinkDNS and its authors
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
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.databinding.DialogSetHttpProxyBinding
import com.celzero.bravedns.databinding.DialogSetProxyBinding
import com.celzero.bravedns.databinding.FragmentProxyConfigureBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UiUtils
import com.celzero.bravedns.util.UiUtils.openUrl
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class ProxySettingsActivity : AppCompatActivity(R.layout.fragment_proxy_configure) {
    private val b by viewBinding(FragmentProxyConfigureBinding::bind)

    private var proxyEndpoint: ProxyEndpoint? = null
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        initView()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshOrbotUi()
        handleProxyUi()
        displayWireguardUi()
    }

    private fun initView() {
        b.settingsActivityHttpProxyProgress.visibility = View.GONE
        b.settingsWireguardTitle.text = getString(R.string.lbl_wireguard).lowercase()
        b.orbotTitle.text = getString(R.string.orbot).lowercase()
        b.otherTitle.text = getString(R.string.category_name_others).lowercase()

        observeCustomProxy()
        displayTcpProxyUi()
        displayHttpProxyUi()
        displaySocks5Ui()
    }

    private fun initClickListeners() {

        b.settingsActivityTcpProxyContainer.setOnClickListener { handleTcpProxy() }

        b.settingsActivitySocks5Rl.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = !b.settingsActivitySocks5Switch.isChecked
        }

        b.settingsActivitySocks5Switch.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            if (!checked) {
                appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
                b.settingsActivitySocks5Desc.text =
                    getString(R.string.settings_socks_forwarding_default_desc)
                return@setOnCheckedChangeListener
            }

            if (appConfig.getBraveMode().isDnsMode()) {
                b.settingsActivitySocks5Switch.isChecked = false
                return@setOnCheckedChangeListener
            }

            if (!appConfig.canEnableSocks5Proxy()) {
                val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
                showToastUiCentered(
                    this,
                    getString(R.string.settings_socks5_disabled_error, s),
                    Toast.LENGTH_SHORT
                )

                b.settingsActivitySocks5Switch.isChecked = false
                return@setOnCheckedChangeListener
            }

            showSocks5ProxyDialog()
        }

        b.settingsActivityOrbotImg.setOnClickListener { handleOrbotUiEvent() }

        b.settingsActivityOrbotContainer.setOnClickListener { handleOrbotUiEvent() }

        b.settingsActivityWireguardContainer.setOnClickListener { openWireguardActivity() }

        b.settingsActivityWireguardImg.setOnClickListener { openWireguardActivity() }

        b.settingsActivityHttpProxyContainer.setOnClickListener {
            b.settingsActivityHttpProxySwitch.isChecked =
                !b.settingsActivityHttpProxySwitch.isChecked
        }

        b.settingsActivityHttpProxySwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            if (!checked) {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
                return@setOnCheckedChangeListener
            }

            if (appConfig.getBraveMode().isDnsMode()) {
                b.settingsActivityHttpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            if (!appConfig.canEnableHttpProxy()) {
                val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
                showToastUiCentered(
                    this,
                    getString(R.string.settings_https_disabled_error, s),
                    Toast.LENGTH_SHORT
                )
                b.settingsActivityHttpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            showHttpProxyDialog()
        }
    }

    private fun handleTcpProxy() {
        // disable the click event until below coroutine is completed.
        disableTcpProxyUi()

        io {
            when (TcpProxyHelper.getTcpProxyPaymentStatus()) {
                TcpProxyHelper.PaymentStatus.PAID -> {
                    uiCtx {
                        enableTcpProxyUi()
                        val intent = Intent(this, TcpProxyMainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        startActivity(intent)
                    }
                }
                TcpProxyHelper.PaymentStatus.INITIATED -> {
                    uiCtx {
                        enableTcpProxyUi()
                        val intent = Intent(this, CheckoutActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        startActivity(intent)
                    }
                }
                else -> {
                    val isTcpWorking = TcpProxyHelper.publicKeyUsable()
                    uiCtx {
                        if (isTcpWorking) {
                            enableTcpProxyUi()
                            val intent = Intent(this, CheckoutActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            startActivity(intent)
                        } else {
                            showTcpProxyErrorDialog()
                            enableTcpProxyUi()
                        }
                    }
                }
            }
        }
    }

    private fun disableTcpProxyUi() {
        b.settingsActivityTcpProxyContainer.isClickable = false
        b.settingsActivityTcpProxyProgress.visibility = View.VISIBLE
        b.settingsActivityTcpProxyImg.visibility = View.GONE
    }

    private fun enableTcpProxyUi() {
        b.settingsActivityTcpProxyContainer.isClickable = true
        b.settingsActivityTcpProxyProgress.visibility = View.GONE
        b.settingsActivityTcpProxyImg.visibility = View.VISIBLE
    }

    private fun showTcpProxyErrorDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Rethink Proxy")
        builder.setMessage(
            "Issue checking for Rethink Proxy. There may be a problem with your network or the proxy server. Please try again later."
        )
        builder.setPositiveButton("Okay") { dialog, _ -> dialog.dismiss() }
        builder.create().show()
    }

    /** Prompt user to download the Orbot app based on the current BUILDCONFIG flavor. */
    private fun showOrbotInstallDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.orbot_install_dialog_title)
        builder.setMessage(R.string.orbot_install_dialog_message)
        builder.setPositiveButton(getString(R.string.orbot_install_dialog_positive)) { _, _ ->
            handleOrbotInstall()
        }
        builder.setNegativeButton(getString(R.string.lbl_dismiss)) { dialog, _ -> dialog.dismiss() }
        builder.setNeutralButton(getString(R.string.orbot_install_dialog_neutral)) { _, _ ->
            launchOrbotWebsite()
        }
        builder.create().show()
    }

    private fun launchOrbotWebsite() {
        openUrl(this, getString(R.string.orbot_website_link))
    }

    private fun handleOrbotInstall() {
        startOrbotInstallActivity(orbotHelper.getIntentForDownload())
    }

    private fun startOrbotInstallActivity(intent: Intent?) {
        if (intent == null) {
            showToastUiCentered(
                this,
                getString(R.string.orbot_install_activity_error),
                Toast.LENGTH_SHORT
            )
            return
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.orbot_install_activity_error),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun handleOrbotUiEvent() {
        if (!FirewallManager.isOrbotInstalled()) {
            showOrbotInstallDialog()
            return
        }

        if (!appConfig.canEnableOrbotProxy()) {
            val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
            if (s.lowercase() == AppConfig.ProxyProvider.CUSTOM.name.lowercase()) {
                showToastUiCentered(
                    this,
                    getString(R.string.settings_orbot_disabled_error),
                    Toast.LENGTH_SHORT
                )
            } else {
                showToastUiCentered(
                    this,
                    getString(R.string.settings_socks5_disabled_error, s),
                    Toast.LENGTH_SHORT
                )
            }
            return
        }

        openOrbotBottomSheet()
    }

    private fun openWireguardActivity() {
        val intent = Intent(this, WgMainActivity::class.java)
        startActivity(intent)
    }

    private fun openOrbotBottomSheet() {
        if (!VpnController.hasTunnel()) {
            showToastUiCentered(
                this,
                getString(R.string.settings_socks5_vpn_disabled_error),
                Toast.LENGTH_SHORT
            )
            return
        }

        val bottomSheetFragment = OrbotBottomSheetFragment()
        bottomSheetFragment.show(this.supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun displayHttpProxyUi() {
        if (!isAtleastQ()) {
            b.settingsActivityHttpProxyContainer.visibility = View.GONE
            return
        }

        b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
        b.settingsActivityHttpProxySwitch.isChecked = appConfig.isCustomHttpProxyEnabled()
        if (b.settingsActivityHttpProxySwitch.isChecked) {
            b.settingsActivityHttpProxyDesc.text =
                getString(
                    R.string.settings_http_proxy_desc,
                    persistentState.httpProxyHostAddress
                )
        }
    }

    private fun displayTcpProxyUi() {
        val tcpProxies = TcpProxyHelper.getActiveTcpProxy()
        if (tcpProxies == null || !tcpProxies.isActive) {
            b.settingsActivityTcpProxyDesc.text =
                "Not active" // getString(R.string.tcp_proxy_description)
            return
        }

        Log.i(
            LOG_TAG_UI,
            "displayTcpProxyUi: ${tcpProxies?.isActive}, ${tcpProxies?.name}, ${tcpProxies?.url}"
        )
        b.settingsActivityTcpProxyDesc.text =
            "Active" // getString(R.string.tcp_proxy_description_active)
    }

    private fun displayWireguardUi() {
        val activeWgs = WireguardManager.getActiveConfigs()
        if (activeWgs.isEmpty()) {
            b.settingsActivityWireguardDesc.text = getString(R.string.wireguard_description)
            return
        }

        var wgStatus = ""
        activeWgs.forEach {
            val id = ProxyManager.ID_WG_BASE + it.getId()
            val statusId = VpnController.getProxyStatusById(id)
            if (statusId != null) {
                val resId = UiUtils.getProxyStatusStringRes(statusId)
                val s = getString(resId).replaceFirstChar(Char::titlecase)
                wgStatus += getString(R.string.ci_ip_label, it.getName(), s.padStart(1, ' ')) + "\n"
                if (DEBUG) Log.d(LoggerConstants.LOG_TAG_PROXY, "current proxy status for $id: $s")
            } else {
                wgStatus +=
                    getString(
                            R.string.ci_ip_label,
                            it.getName(),
                            getString(R.string.status_failing).padStart(1, ' ')
                        )
                        .replaceFirstChar(Char::titlecase) + "\n"
                if (DEBUG)
                    Log.d(LoggerConstants.LOG_TAG_PROXY, "current proxy status is null for $id")
            }
        }
        wgStatus = wgStatus.trimEnd()
        b.settingsActivityWireguardDesc.text = wgStatus
    }

    private fun displaySocks5Ui() {
        val isCustomSocks5Enabled = appConfig.isCustomSocks5Enabled()

        b.settingsActivitySocks5Progress.visibility = View.GONE
        b.settingsActivitySocks5Switch.isChecked = isCustomSocks5Enabled

        if (!isCustomSocks5Enabled) {
            return
        }

        val appName =
            if (proxyEndpoint?.proxyAppName == getString(R.string.settings_app_list_default_app)) {
                getString(R.string.settings_app_list_default_app)
            } else {
                FirewallManager.getAppInfoByPackage(proxyEndpoint?.proxyAppName)?.appName
            }
        b.settingsActivitySocks5Desc.text =
            getString(
                R.string.settings_socks_forwarding_desc,
                proxyEndpoint?.proxyIP,
                proxyEndpoint?.proxyPort.toString(),
                appName
            )
    }

    private fun observeCustomProxy() {
        appConfig.connectedProxy.observe(this) {
            proxyEndpoint = it
            displaySocks5Ui()
        }
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
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_1,
                                    getString(R.string.orbot_status_arg_3)
                                )
                        } else {
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_1,
                                    getString(R.string.orbot_status_arg_2)
                                )
                        }
                    }
                    AppConfig.ProxyType.HTTP_SOCKS5.name -> {
                        if (isOrbotDns) {
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_3,
                                    getString(R.string.orbot_status_arg_3)
                                )
                        } else {
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_3,
                                    getString(R.string.orbot_status_arg_2)
                                )
                        }
                    }
                    else -> {
                        b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
                    }
                }
            }
        }
    }

    private fun showSocks5ProxyDialog() {
        val dialog = Dialog(this)
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

        udpBlockCheckBox.isChecked = persistentState.getUdpBlocked()

        val appNames: MutableList<String> = ArrayList()
        appNames.add(getString(R.string.settings_app_list_default_app))
        if (!VpnController.isVpnLockdown()) {
            appNames.addAll(FirewallManager.getAllAppNames())
        }
        val proxySpinnerAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appNames)
        appNameSpinner.adapter = proxySpinnerAdapter
        if (proxyEndpoint != null && !proxyEndpoint?.proxyIP.isNullOrBlank()) {
            ipAddressEditText.setText(proxyEndpoint?.proxyIP, TextView.BufferType.EDITABLE)
            portEditText.setText(proxyEndpoint?.proxyPort.toString(), TextView.BufferType.EDITABLE)
            userNameEditText.setText(
                proxyEndpoint?.userName.toString(),
                TextView.BufferType.EDITABLE
            )
            if (VpnController.isVpnLockdown()) {
                appNameSpinner.setSelection(0)
                appNameSpinner.isEnabled = false
            } else if (
                !proxyEndpoint?.proxyAppName.isNullOrBlank() &&
                    proxyEndpoint?.proxyAppName != getString(R.string.settings_app_list_default_app)
            ) {
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
            val pos =
                if (appNameSpinner.selectedItemPosition > appNames.size) {
                    0
                } else {
                    appNameSpinner.selectedItemPosition
                }
            val appName: String = appNames[pos]
            val appPackageName =
                if (
                    appName.isBlank() ||
                        appName == getString(R.string.settings_app_list_default_app)
                ) {
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
                isValid =
                    if (Utilities.isLanIpv4(ip)) {
                        Utilities.isValidLocalPort(port)
                    } else {
                        Utilities.isValidPort(port)
                    }
                if (!isValid) {
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                }
            } catch (e: NumberFormatException) {
                Log.w(LoggerConstants.LOG_TAG_VPN, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                isValid = false
            }
            if (udpBlockCheckBox.isChecked) {
                isUDPBlock = true
            }

            val userName: String = userNameEditText.text.toString()
            val password: String = passwordEditText.text.toString()
            if (isValid && isIPValid) {
                // Do the Socks5 Proxy setting there
                persistentState.setUdpBlocked(udpBlockCheckBox.isChecked)
                insertSocks5ProxyEndpointDB(
                    mode,
                    appPackageName,
                    ip,
                    port,
                    userName,
                    password,
                    isUDPBlock
                )
                b.settingsActivitySocks5Desc.text =
                    getString(R.string.settings_socks_forwarding_desc, ip, port.toString(), appName)
                dialog.dismiss()
            } else {
                // no-op
            }
        }

        cancelURLBtn.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = false
            appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
            b.settingsActivitySocks5Desc.text =
                getString(R.string.settings_socks_forwarding_default_desc)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun enableTcpProxy() {
        io { TcpProxyHelper.enable() }
    }

    // Should be in disabled state when the brave mode is in DNS only / Vpn in lockdown mode.
    private fun handleProxyUi() {
        val canEnableProxy = appConfig.canEnableProxy()

        if (canEnableProxy) {
            b.settingsActivityOrbotContainer.alpha = 1f
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
            b.settingsActivityWireguardContainer.alpha = 1f
            b.settingsActivityTcpProxyContainer.alpha = 1f
            b.settingsActivitySocks5Rl.alpha = 1f
            b.settingsActivityHttpProxyContainer.alpha = 1f
        } else {
            b.settingsActivityOrbotContainer.alpha = 0.5f
            b.settingsActivityWireguardContainer.alpha = 0.5f
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivityTcpProxyContainer.alpha = 0.5f
            b.settingsActivitySocks5Rl.alpha = 0.5f
            b.settingsActivityHttpProxyContainer.alpha = 0.5f
        }

        // Wireguard
        b.settingsActivityWireguardImg.isEnabled = canEnableProxy
        b.settingsActivityWireguardContainer.isEnabled = canEnableProxy
        // TCP Proxy
        b.settingsActivityTcpProxyIcon.isEnabled = canEnableProxy
        b.settingsActivityTcpProxyContainer.isEnabled = canEnableProxy
        // Orbot
        // case, when VPN is in lockdown mode, Orbot proxy should be disabled.
        if (VpnController.isVpnLockdown()) {
            b.settingsActivityOrbotContainer.alpha = 0.5f
        } else {
            b.settingsActivityOrbotContainer.alpha = 1f
        }
        b.settingsActivityOrbotImg.isEnabled = canEnableProxy && !VpnController.isVpnLockdown()
        b.settingsActivityOrbotContainer.isEnabled =
            canEnableProxy && !VpnController.isVpnLockdown()
        // SOCKS5
        b.settingsActivitySocks5Switch.isEnabled = canEnableProxy
        // HTTP Proxy
        b.settingsActivityHttpProxySwitch.isEnabled = canEnableProxy
    }

    private fun showHttpProxyDialog() {
        var host: String
        val dialog = Dialog(this)
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
        val errorTxt = dialogBinding.dialogHttpProxyFailureText

        val hostName = persistentState.httpProxyHostAddress
        if (hostName.isNotBlank()) {
            hostAddressEditText.setText(hostName, TextView.BufferType.EDITABLE)
        }
        applyURLBtn.setOnClickListener {
            host = hostAddressEditText.text.toString()
            var isHostValid = true

            if (host.isBlank()) {
                isHostValid = false
                errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                errorTxt.visibility = View.VISIBLE
            }

            if (isHostValid) {
                errorTxt.visibility = View.INVISIBLE
                io {
                    appConfig.addProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
                    appConfig.insertCustomHttpProxy(host)
                }
                dialog.dismiss()
                Toast.makeText(
                        this,
                        getString(R.string.settings_http_proxy_toast_success),
                        Toast.LENGTH_SHORT
                    )
                    .show()
                if (b.settingsActivityHttpProxySwitch.isChecked) {
                    b.settingsActivityHttpProxyDesc.text =
                        getString(R.string.settings_http_proxy_desc, host)
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

    private fun insertSocks5ProxyEndpointDB(
        mode: String,
        appName: String?,
        ip: String,
        port: Int,
        userName: String,
        password: String,
        isUDPBlock: Boolean
    ) {
        if (appName == null) return

        b.settingsActivitySocks5Switch.isEnabled = false
        b.settingsActivitySocks5Switch.visibility = View.GONE
        b.settingsActivitySocks5Progress.visibility = View.VISIBLE
        delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
            b.settingsActivitySocks5Switch.isEnabled = true
            b.settingsActivitySocks5Progress.visibility = View.GONE
            b.settingsActivitySocks5Switch.visibility = View.VISIBLE
        }
        io {
            val proxyName = Constants.SOCKS
            val proxyEndpoint =
                ProxyEndpoint(
                    id = 0,
                    proxyName,
                    proxyMode = 1,
                    mode,
                    appName,
                    ip,
                    port,
                    userName,
                    password,
                    isSelected = true,
                    isCustom = true,
                    isUDP = isUDPBlock,
                    modifiedDataTime = 0L,
                    latency = 0
                )

            appConfig.insertCustomSocks5Proxy(proxyEndpoint)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
