/*
 * Copyright 2026 RethinkDNS and its authors
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
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpoint.Companion.DEFAULT_PROXY_TYPE
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivitySocks5ConfigBinding
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_S5_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.firestack.backend.RouterStats
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Custom SOCKS5 proxy screen, opened from proxy settings the same way a WireGuard config is.
 * Holds the on/off state, the apps routed via SOCKS5 (shared, exclusive picker), the server
 * endpoint and the forwarder app options.
 */
class Socks5ConfigActivity : BaseActivity(R.layout.activity_socks5_config) {
    private val b by viewBinding(ActivitySocks5ConfigBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()
    private val rdb by inject<RefreshDatabase>()

    // last saved endpoint; never mutated, a new instance is saved on every change
    private var endpoint: ProxyEndpoint? = null
    private var statsJob: Job? = null

    private val discardCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                showDiscardDialog()
            }
        }

    private val enableListener =
        CompoundButton.OnCheckedChangeListener { _, checked -> onEnableToggled(checked) }

    companion object {
        private const val STATS_POLL_MS = 2_000L

        fun newIntent(context: Context): Intent = Intent(context, Socks5ConfigActivity::class.java)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        onBackPressedDispatcher.addCallback(this, discardCallback)
        setupClickListeners()
        loadEndpoint(fillForm = savedInstanceState == null)
    }

    override fun onResume() {
        super.onResume()
        // app count may have changed in the picker, lockdown / dns mode in other screens
        refreshStateUi()
        startStatsPolling()
    }

    override fun onPause() {
        super.onPause()
        statsJob?.cancel()
        statsJob = null
    }

    private fun loadEndpoint(fillForm: Boolean) {
        io {
            val ep = appConfig.getSocks5ProxyDetails()
            if (ep == null) {
                uiCtx {
                    showToastUiCentered(
                        this,
                        getString(R.string.blocklist_update_check_failure),
                        Toast.LENGTH_SHORT,
                    )
                    finish()
                }
                return@io
            }
            val fwdrName = forwarderAppName(ep)
            uiCtx {
                endpoint = ep
                if (fillForm) fillForm(ep)
                updateEndpointUi(ep, fwdrName)
                updateDirtyState()
            }
        }
    }

    private fun fillForm(ep: ProxyEndpoint) {
        b.hostEdit.setText(baseHost(ep))
        b.portEdit.setText(basePort(ep))
        b.usernameEdit.setText(ep.userName.orEmpty())
        b.passwordEdit.setText(ep.password.orEmpty())
        clearFieldErrors()
    }

    private fun updateEndpointUi(ep: ProxyEndpoint, fwdrName: String?) {
        b.endpointText.text =
            if (ep.proxyIP.isNullOrBlank()) {
                getString(R.string.settings_app_list_default_app)
            } else {
                "${ep.proxyIP}:${ep.proxyPort}"
            }
        b.authText.text =
            if (ep.userName.isNullOrBlank()) {
                getString(R.string.socks5_auth_none)
            } else {
                getString(R.string.socks5_auth_user, ep.userName)
            }
        if (fwdrName == null) {
            b.forwarderText.visibility = View.GONE
            b.forwarderValueText.text = getString(R.string.settings_app_list_default_app)
        } else {
            b.forwarderText.visibility = View.VISIBLE
            b.forwarderText.text = getString(R.string.socks5_forwarder_value, fwdrName)
            b.forwarderValueText.text = fwdrName
        }
    }

    // on/off switch, lockdown hints, app count; everything that may change outside this screen
    private fun refreshStateUi() {
        setEnableSwitch(appConfig.isCustomSocks5Enabled())

        val vpnLockdown = VpnController.isVpnLockdown()
        b.loopbackSwitch.isChecked = !persistentState.excludeAppsInProxy
        b.loopbackSwitch.isEnabled = !vpnLockdown
        b.loopbackRl.alpha = if (vpnLockdown) 0.5f else 1f
        b.lockdownDesc.visibility = if (vpnLockdown) View.VISIBLE else View.GONE

        // proxy lockdown forces the forwarder to none (see the old socks5 dialog)
        b.forwarderRl.alpha = if (persistentState.wgGlobalLockdown) 0.5f else 1f

        refreshAppCount()
    }

    private fun refreshAppCount() {
        io {
            val count = ProxyManager.getAppCountForProxy(ID_S5_BASE)
            uiCtx {
                b.appsLabel.text = getString(R.string.settings_socks5_apps_btn, count.toString())
                b.noAppsHint.visibility =
                    if (count == 0 && appConfig.isCustomSocks5Enabled()) View.VISIBLE
                    else View.GONE
            }
        }
    }

    private fun setEnableSwitch(checked: Boolean) {
        b.enableSwitch.setOnCheckedChangeListener(null)
        b.enableSwitch.isChecked = checked
        b.enableSwitch.setOnCheckedChangeListener(enableListener)
    }

    private fun setupClickListeners() {
        b.enableSwitch.setOnCheckedChangeListener(enableListener)

        b.appsBtn.setOnClickListener { openAppsPicker() }

        b.logsBtn.setOnClickListener { openLogs() }

        b.hostEdit.doAfterTextChanged { onFieldEdited() }
        b.portEdit.doAfterTextChanged { onFieldEdited() }
        b.usernameEdit.doAfterTextChanged { onFieldEdited() }
        b.passwordEdit.doAfterTextChanged { onFieldEdited() }

        b.saveBtn.setOnClickListener { saveForm(enable = false) }

        b.revertBtn.setOnClickListener { endpoint?.let { fillForm(it) } }

        b.forwarderRl.setOnClickListener { openForwarderPicker() }

        b.loopbackRl.setOnClickListener {
            if (b.loopbackSwitch.isEnabled) b.loopbackSwitch.isChecked = !b.loopbackSwitch.isChecked
        }

        b.loopbackSwitch.setOnCheckedChangeListener { _, checked ->
            if (persistentState.excludeAppsInProxy != !checked) {
                persistentState.excludeAppsInProxy = !checked
                logEvent(
                    "loopback proxy forwarder apps SOCKS5 Proxy toggled",
                    "loopback proxy forwarder apps in SOCKS5 proxy: $checked",
                )
            }
        }

        b.lockdownDesc.setOnClickListener { UIUtils.openVpnProfile(this) }
    }

    private fun onEnableToggled(checked: Boolean) {
        if (!checked) {
            appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
            logEvent("Custom SOCKS5 Proxy disabled", "disabled custom SOCKS5 proxy")
            afterStateChange()
            return
        }

        if (!appConfig.canEnableSocks5Proxy()) {
            showToastUiCentered(
                this,
                getString(R.string.settings_lock_down_proxy_desc),
                Toast.LENGTH_SHORT,
            )
            setEnableSwitch(false)
            return
        }

        // turning on uses what the form shows, saved or not
        saveForm(enable = true)
    }

    private fun afterStateChange() {
        refreshAppCount()
        startStatsPolling()
    }

    private fun onFieldEdited() {
        clearFieldErrors()
        updateDirtyState()
    }

    // never configured: the form suggests the usual local forwarder address
    private fun baseHost(ep: ProxyEndpoint): String =
        ep.proxyIP?.takeUnless { it.isBlank() } ?: Constants.SOCKS_DEFAULT_IP

    private fun basePort(ep: ProxyEndpoint): String =
        if (ep.proxyIP.isNullOrBlank()) Constants.SOCKS_DEFAULT_PORT.toString()
        else ep.proxyPort.toString()

    private fun isFormDirty(): Boolean {
        val ep = endpoint ?: return false
        return b.hostEdit.text.toString().trim() != baseHost(ep) ||
            b.portEdit.text.toString().trim() != basePort(ep) ||
            b.usernameEdit.text.toString() != ep.userName.orEmpty() ||
            b.passwordEdit.text.toString() != ep.password.orEmpty()
    }

    private fun updateDirtyState() {
        val dirty = isFormDirty()
        b.saveBtn.isEnabled = dirty
        b.revertBtn.isEnabled = dirty
        b.unsavedText.visibility = if (dirty) View.VISIBLE else View.INVISIBLE
        discardCallback.isEnabled = dirty
    }

    private fun clearFieldErrors() {
        b.hostLayout.error = null
        b.portLayout.error = null
    }

    // returns host and port, or null after flagging the bad field
    private fun validateForm(): Pair<String, Int>? {
        val host = b.hostEdit.text.toString().trim()
        if (host.isBlank()) {
            b.hostLayout.error = getString(R.string.settings_http_proxy_error_text3)
            return null
        }
        val port = b.portEdit.text.toString().trim().toIntOrNull()
        if (port == null) {
            b.portLayout.error = getString(R.string.settings_http_proxy_error_text2)
            return null
        }
        val isValid =
            if (Utilities.isLanIpv4(host)) {
                Utilities.isValidLocalPort(port)
            } else {
                Utilities.isValidPort(port)
            }
        if (!isValid || !Utilities.isValidPort(port)) {
            b.portLayout.error = getString(R.string.settings_http_proxy_error_text1)
            return null
        }
        return Pair(host, port)
    }

    private fun saveForm(enable: Boolean) {
        val ep = endpoint ?: return
        val hostPort = validateForm()
        if (hostPort == null) {
            if (enable) setEnableSwitch(false)
            return
        }
        val (host, port) = hostPort
        val updated =
            copyOf(
                ep,
                ip = host,
                port = port,
                userName = b.usernameEdit.text.toString(),
                password = b.passwordEdit.text.toString(),
                fwdrPkg = ep.proxyAppName.orEmpty(),
            )
        persist(updated, enable)
        logEvent("Custom SOCKS5 Proxy set", "custom SOCKS5 proxy to $host:$port, enable: $enable")
    }

    private fun persist(updated: ProxyEndpoint, enable: Boolean) {
        b.enableSwitch.isEnabled = false
        b.enableProgress.visibility = View.VISIBLE
        io {
            if (enable) {
                appConfig.updateCustomSocks5Proxy(updated)
            } else {
                appConfig.saveCustomSocks5Endpoint(updated)
            }
            val fwdrName = forwarderAppName(updated)
            val noApps = ProxyManager.getAppCountForProxy(ID_S5_BASE) == 0
            uiCtx {
                endpoint = updated
                b.enableSwitch.isEnabled = true
                b.enableProgress.visibility = View.GONE
                updateEndpointUi(updated, fwdrName)
                updateDirtyState()
                afterStateChange()
                if (!enable) {
                    showToastUiCentered(
                        this,
                        getString(R.string.socks5_saved_toast),
                        Toast.LENGTH_SHORT,
                    )
                }
                // socks5 routes no app until some are picked
                if (enable && noApps) openAppsPicker()
            }
        }
    }

    private fun copyOf(
        ep: ProxyEndpoint,
        ip: String?,
        port: Int,
        userName: String?,
        password: String?,
        fwdrPkg: String,
    ): ProxyEndpoint {
        return ProxyEndpoint(
            ep.id,
            ProxyManager.ProxyMode.SOCKS5.name,
            ProxyManager.ProxyMode.SOCKS5.value,
            proxyType = DEFAULT_PROXY_TYPE,
            fwdrPkg,
            ip,
            port,
            userName,
            password,
            isSelected = true,
            isCustom = true,
            isUDP = ep.isUDP,
            modifiedDataTime = 0L,
            latency = 0,
        )
    }

    private suspend fun forwarderAppName(ep: ProxyEndpoint): String? {
        return ep.proxyAppName
            ?.takeUnless { it.isBlank() || it == getString(R.string.settings_app_list_default_app) }
            ?.let { FirewallManager.getAppInfoByPackage(it)?.appName ?: it }
    }

    private fun openForwarderPicker() {
        if (persistentState.wgGlobalLockdown) {
            showToastUiCentered(
                this,
                getString(R.string.lockdown_check_setting_disabled),
                Toast.LENGTH_SHORT,
            )
            return
        }
        val ep = endpoint ?: return
        io {
            // newly installed apps should be listed too
            rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE)
            val none = getString(R.string.settings_app_list_default_app)
            val names = listOf(none) + FirewallManager.getAllAppNamesSortedByVpnPermission(this)
            val current = forwarderAppName(ep) ?: none
            uiCtx { showForwarderDialog(names, names.indexOf(current).coerceAtLeast(0)) }
        }
    }

    private fun showForwarderDialog(names: List<String>, checked: Int) {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.socks5_forwarder_title)
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which != checked) onForwarderPicked(names[which], isNone = which == 0)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun onForwarderPicked(appName: String, isNone: Boolean) {
        val ep = endpoint ?: return
        io {
            val pkg = if (isNone) "" else FirewallManager.getPackageNameByAppName(appName).orEmpty()
            // only the forwarder changes; unsaved server edits stay in the form
            val updated = copyOf(ep, ep.proxyIP, ep.proxyPort, ep.userName, ep.password, pkg)
            uiCtx { persist(updated, enable = false) }
            logEvent("Custom SOCKS5 forwarder set", "custom SOCKS5 forwarder app: $pkg")
        }
    }

    private fun openAppsPicker() {
        startActivity(
            WgIncludeAppsActivity.newIntent(this, ID_S5_BASE, AppConfig.ProxyType.SOCKS5.name)
        )
    }

    private fun openLogs() {
        // the rpn log view is a plain proxy-id filter over connection and dns logs
        val intent = Intent(this, NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_RPN + ID_S5_BASE)
        startActivity(intent)
    }

    private fun showDiscardDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.socks5_discard_title)
            .setPositiveButton(R.string.socks5_discard) { _, _ ->
                discardCallback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = io {
            while (true) {
                try {
                    updateStatusUi()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_PROXY, "socks5 status poll err: ${e.message}")
                }
                if (!appConfig.isCustomSocks5Enabled()) break
                delay(STATS_POLL_MS.milliseconds)
            }
        }
    }

    private suspend fun updateStatusUi() {
        if (!appConfig.isCustomSocks5Enabled()) {
            uiCtx {
                b.statusCard.strokeWidth = 0
                b.statusText.text =
                    getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
            return
        }
        if (!VpnController.isOn()) {
            uiCtx {
                b.statusCard.strokeWidth = 0
                b.statusText.text = getString(R.string.settings_socks5_vpn_disabled_error)
            }
            return
        }

        val statusPair = VpnController.getProxyStatusById(ID_S5_BASE)
        val stats = VpnController.getProxyStats(ID_S5_BASE)
        val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
        val text = getStatusText(ps, stats, statusPair.second)
        val color = getStrokeColorForStatus(ps, stats)
        uiCtx {
            b.statusText.text = text
            b.statusCard.strokeWidth = 2
            b.statusCard.strokeColor = fetchColor(this, color)
        }
    }

    private fun getStatusText(
        status: UIUtils.ProxyStatus?,
        stats: RouterStats?,
        errMsg: String?,
    ): String {
        if (status == null) {
            val txt =
                if (!errMsg.isNullOrEmpty()) {
                    getString(R.string.status_waiting) + " ($errMsg)"
                } else {
                    getString(R.string.status_waiting)
                }
            return txt.replaceFirstChar(Char::titlecase)
        }

        val baseText =
            getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
        val lastOk = stats?.lastOK ?: 0L
        if (lastOk == 0L) return baseText

        val since =
            DateUtils.getRelativeTimeSpanString(
                lastOk,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            )
        return getString(R.string.about_version_install_source, baseText, since.toString())
    }

    private fun getStrokeColorForStatus(status: UIUtils.ProxyStatus?, stats: RouterStats?): Int {
        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        val isFailing = now - since > WG_UPTIME_THRESHOLD && lastOk == 0L
        return when (status) {
            UIUtils.ProxyStatus.TOK -> if (isFailing) R.attr.chipTextNeutral else R.attr.accentGood
            // an idle socks5 (no app traffic yet) is not an error
            UIUtils.ProxyStatus.TUP, UIUtils.ProxyStatus.TZZ, null -> R.attr.chipTextNeutral
            else -> R.attr.chipTextNegative
        }
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }
}
