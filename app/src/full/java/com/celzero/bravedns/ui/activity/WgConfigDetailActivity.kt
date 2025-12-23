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
package com.celzero.bravedns.ui.activity

import Logger
import Logger.LOG_TAG_PROXY
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.adapter.WgPeersAdapter
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.ActivityWgDetailBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_OTHER_WG_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_FULL
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_WG_INVALID
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_WIREGUARD
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.ui.dialog.WgHopDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.ui.dialog.WgSsidDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.SsidPermissionManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.firestack.backend.RouterStats
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.getValue

class WgConfigDetailActivity : AppCompatActivity(R.layout.activity_wg_detail) {
    private val b by viewBinding(ActivityWgDetailBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private var wgPeersAdapter: WgPeersAdapter? = null
    private var layoutManager: LinearLayoutManager? = null

    private var configId: Int = INVALID_CONF_ID
    private var wgInterface: WgInterface? = null
    private val peers: MutableList<Peer> = mutableListOf()
    private var wgType: WgType = WgType.DEFAULT

    // SSID permission handling
    private val ssidPermissionCallback = object : SsidPermissionManager.PermissionCallback {
        override fun onPermissionsGranted() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions granted")
            // Refresh the SSID section to update error layouts and functionality
            val cfg = WireguardManager.getConfigFilesById(configId)
            ui {
                setupSsidSection(cfg)
            }
        }

        override fun onPermissionsDenied() {
            // Show dialog asking user to manually enable permissions in settings
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions denied")
            ui {
                showPermissionDeniedDialog()
            }
        }

        override fun onPermissionsRationale() {
            // Show explanation dialog before requesting permissions
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions rationale")
            ui {
                showSsidPermissionExplanationDialog()
            }
        }
    }

    companion object {
        private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
        const val INTENT_EXTRA_WG_TYPE = "WIREGUARD_TUNNEL_TYPE"
    }

    enum class WgType(val value: Int) {
        DEFAULT(0),
        ONE_WG(1);

        fun isOneWg() = this == ONE_WG

        fun isDefault() = this == DEFAULT

        companion object {
            fun fromInt(value: Int): WgType {
                return entries.firstOrNull { it.value == value }
                    ?: throw IllegalArgumentException("Invalid WgType value: $value")
            }
        }
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
        configId = intent.getIntExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, INVALID_CONF_ID)
        wgType = WgType.fromInt(intent.getIntExtra(INTENT_EXTRA_WG_TYPE, WgType.DEFAULT.value))

        b.ssidDescTv.text = getString(R.string.wg_setting_ssid_desc, getString(R.string.lbl_ssids))
    }

    override fun onResume() {
        super.onResume()
        init()
        setupClickListeners()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.vv(LOG_TAG_UI, "onRequestPermissionsResult: requestCode=$requestCode")
        // Handle SSID permission results
        SsidPermissionManager.handlePermissionResult(
            requestCode,
            permissions,
            grantResults,
            ssidPermissionCallback
        )
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        if (!VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_PROXY, "VPN not active, config may not be available")
            Utilities.showToastUiCentered(
                this,
                ERR_CODE_VPN_NOT_ACTIVE +
                        getString(R.string.settings_socks5_vpn_disabled_error),
                Toast.LENGTH_LONG
            )
            finish()
            return
        }

        b.hopBtn.text = getString(
            R.string.two_argument_space,
            getString(R.string.hop_add_remove_title),
            getString(R.string.lbl_experimental)
        )
        b.editBtn.text = getString(R.string.rt_edit_dialog_positive).uppercase()
        b.deleteBtn.text = getString(R.string.lbl_delete).uppercase()

        b.catchAllTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.catch_all_wg_dialog_title),
                getString(R.string.symbol_lightening)
            )

        val mobileOnlyExperimentalTxt = getString(
            R.string.two_argument_space,
            getString(R.string.wg_setting_use_on_mobile),
            getString(R.string.lbl_experimental)
        )
        b.useMobileTitleTv.text =
            getString(
                R.string.two_argument_space,
                mobileOnlyExperimentalTxt,
                getString(R.string.symbol_mobile)
            )
        val ssidExperimentalTxt = getString(
            R.string.two_argument_space,
            getString(R.string.wg_setting_ssid_title),
            getString(R.string.lbl_experimental)
        )
        b.ssidTitleTv.text = getString(
            R.string.two_argument_space,
            ssidExperimentalTxt,
            getString(R.string.symbol_id)
        )
        if (wgType.isDefault()) {
            b.wgHeaderTv.text = getString(R.string.lbl_advanced).replaceFirstChar(Char::titlecase)
            b.catchAllRl.visibility = View.VISIBLE
            b.oneWgInfoTv.visibility = View.GONE
            b.hopBtn.visibility = View.VISIBLE
            b.hopBtnX.visibility = View.VISIBLE
            b.mobileSsidSettingsCard.visibility = View.VISIBLE
        } else if (wgType.isOneWg()) {
            b.wgHeaderTv.text =
                getString(R.string.rt_list_simple_btn_txt).replaceFirstChar(Char::titlecase)
            b.catchAllRl.visibility = View.GONE
            b.hopBtn.visibility = View.GONE
            b.hopBtnX.visibility = View.GONE
            b.oneWgInfoTv.visibility = View.VISIBLE
            b.applicationsBtn.isEnabled = false
            b.applicationsBtnX.isEnabled = false
            b.mobileSsidSettingsCard.visibility = View.GONE
            b.applicationsBtn.text = getString(R.string.one_wg_apps_added)
            b.appsLabel.text = "All apps"
        } else {
            // invalid wireguard type, finish the activity
            finish()
            return
        }
        val config = WireguardManager.getConfigById(configId)
        val mapping = WireguardManager.getConfigFilesById(configId)

        if (config == null) {
            showInvalidConfigDialog()
            return
        }

        if (mapping != null) {
            // if catch all is enabled, disable the add apps button and lockdown
            b.catchAllCheck.isChecked = mapping.isCatchAll
            if (mapping.isCatchAll) {
                b.applicationsBtn.isEnabled = false
                b.applicationsBtn.text = getString(R.string.routing_remaining_apps)
            }
            b.useMobileCheck.isChecked = mapping.useOnlyOnMetered
        }

        if (shouldObserveAppsCount()) {
            handleAppsCount()
        } else {
            b.applicationsBtn.isEnabled = false
            // texts are updated based on the catch all and one-wg
        }
        setupSsidSection(mapping)
        io { updateStatusUi(config.getId()) }
        prefillConfig(config)
    }

    private suspend fun updateStatusUi(id: Int) {
        val config = WireguardManager.getConfigFilesById(id)
        val cid = ID_WG_BASE + id
        if (config?.isActive == true) {
            val statusPair = VpnController.getProxyStatusById(cid)
            val stats = VpnController.getProxyStats(cid)
            val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
            val dnsStatusId = if (persistentState.splitDns) {
                VpnController.getDnsStatus(cid)
            } else {
                null
            }
            uiCtx {
                if (dnsStatusId != null && isDnsError(dnsStatusId)) {
                    // check for dns failure cases and update the UI
                    b.statusText.text = getString(R.string.status_failing)
                        .replaceFirstChar(Char::titlecase)
                    b.interfaceDetailCard.strokeWidth = 2
                    b.interfaceDetailCard.strokeColor = fetchColor(this, R.attr.chipTextNegative)
                    return@uiCtx
                } else if (statusPair.first != null) {
                    val handshakeTime = getHandshakeTime(stats).toString()
                    val statusText = getStatusText(ps, handshakeTime, stats, statusPair.second)
                    b.statusText.text = statusText
                } else {
                    if (statusPair.second.isEmpty()) {
                        b.statusText.text =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                    } else {
                        val txt =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase) + " (${statusPair.second})"
                        b.statusText.text = txt
                    }
                }
                val strokeColor = getStrokeColorForStatus(ps, stats)
                b.interfaceDetailCard.strokeWidth = 2
                b.interfaceDetailCard.strokeColor = fetchColor(this, strokeColor)
            }
        } else {
            uiCtx {
                b.interfaceDetailCard.strokeWidth = 0
                b.statusText.text =
                    getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }
    }

    private fun isDnsError(statusId: Long?): Boolean {
        if (statusId == null) return true

        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
    }

    private fun getStatusText(
        status: UIUtils.ProxyStatus?,
        handshakeTime: String? = null,
        stats: RouterStats?,
        errMsg: String? = null
    ): String {
        if (status == null) {
            val txt = if (!errMsg.isNullOrEmpty()) {
                getString(R.string.status_waiting) + " ($errMsg)"
            } else {
                getString(R.string.status_waiting)
            }
            return txt.replaceFirstChar(Char::titlecase)
        }

        // no need to check for lastOk/since for paused wg
        if (status == UIUtils.ProxyStatus.TPU) {
            return getString(UIUtils.getProxyStatusStringRes(status.id))
                .replaceFirstChar(Char::titlecase)
        }

        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }

        val baseText = getString(UIUtils.getProxyStatusStringRes(status.id))
            .replaceFirstChar(Char::titlecase)

        return if (stats?.lastOK != 0L && handshakeTime != null) {
            getString(R.string.about_version_install_source, baseText, handshakeTime)
        } else {
            baseText
        }
    }

    private fun getHandshakeTime(stats: RouterStats?): CharSequence {
        if (stats == null) {
            return ""
        }
        if (stats.lastOK == 0L) {
            return ""
        }
        val now = System.currentTimeMillis()
        // returns a string describing 'time' as a time relative to 'now'
        return DateUtils.getRelativeTimeSpanString(
            stats.lastOK,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun getStrokeColorForStatus(status: UIUtils.ProxyStatus?, stats: RouterStats?): Int {
        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        val isFailing = now - since > WG_UPTIME_THRESHOLD && lastOk == 0L
        return when (status) {
            UIUtils.ProxyStatus.TOK -> if (isFailing) R.attr.chipTextNeutral else R.attr.accentGood
            UIUtils.ProxyStatus.TUP, UIUtils.ProxyStatus.TZZ, UIUtils.ProxyStatus.TNT -> R.attr.chipTextNeutral
            else -> R.attr.chipTextNegative // TKO, TEND
        }
    }

    private fun showInvalidConfigDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.lbl_wireguard))
        builder.setMessage(getString(R.string.config_invalid_desc))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { _, _ ->
            finish()
        }
        builder.setNeutralButton(getString(R.string.lbl_delete)) { _, _ ->
            WireguardManager.deleteConfig(configId)
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun shouldObserveAppsCount(): Boolean {
        return !wgType.isOneWg() && !b.catchAllCheck.isChecked
    }

    private fun prefillConfig(config: Config) {
        wgInterface = config.getInterface()
        peers.clear()
        peers.addAll(config.getPeers() ?: emptyList())
        if (wgInterface == null) {
            return
        }
        b.configNameText.visibility = View.VISIBLE
        b.configNameText.text = config.getName()
        b.configIdText.text =
            getString(R.string.single_argument_parenthesis, config.getId().toString())

        setPeersAdapter()
        // show dns servers if in one-wg mode
        if (wgType.isOneWg()) {
            b.dnsServersLabel.visibility = View.VISIBLE
            b.dnsServersText.visibility = View.VISIBLE
            var dns = wgInterface?.dnsServers?.joinToString { it.hostAddress?.toString() ?: "" }
            val searchDomains = wgInterface?.dnsSearchDomains?.joinToString { it }
            dns =
                if (!searchDomains.isNullOrEmpty()) {
                    "$dns,$searchDomains"
                } else {
                    dns
                }
            b.dnsServersText.text = dns
        } else {
            b.publicKeyLabel.visibility = View.VISIBLE
            b.publicKeyText.visibility = View.VISIBLE
            b.publicKeyText.text = wgInterface?.getKeyPair()?.getPublicKey()?.base64().tos()
            b.dnsServersLabel.visibility = View.GONE
            b.dnsServersText.visibility = View.GONE
        }

        // uncomment this if we want to show the public key, addresses, listen port and mtu
        /*b.publicKeyText.text = wgInterface?.getKeyPair()?.getPublicKey()?.base64()

        if (wgInterface?.getAddresses()?.isEmpty() == true) {
            b.addressesLabel.visibility = View.GONE
            b.addressesText.visibility = View.GONE
        } else {
            b.addressesText.text = wgInterface?.getAddresses()?.joinToString { it.toString() }
        }*/

        /*if (wgInterface?.dnsServers?.isEmpty() == true) {
                    b.dnsServersText.visibility = View.GONE
              b.dnsServersLabel.visibility = View.GONE
                } else {
                    b.dnsServersText.text =
                        wgInterface?.dnsServers?.joinToString { it.hostAddress?.toString() ?: "" }
                }
        if (wgInterface?.listenPort?.isPresent == true) {
            b.listenPortText.text = wgInterface?.listenPort?.get().toString()
        } else {
            b.listenPortLabel.visibility = View.GONE
            b.listenPortText.visibility = View.GONE
        }
        if (wgInterface?.mtu?.isPresent == true) {
            b.mtuText.text = wgInterface?.mtu?.get().toString()
        } else {
            b.mtuLabel.visibility = View.GONE
            b.mtuText.visibility = View.GONE
        }*/
    }

    private fun handleAppsCount() {
        val id = ID_WG_BASE + configId
        b.applicationsBtn.isEnabled = true
        mappingViewModel.getAppCountById(id).observe(this) {
            if (it == 0) {
                b.applicationsBtn.setTextColor(fetchColor(this, R.attr.accentBad))
            } else {
                b.applicationsBtn.setTextColor(fetchColor(this, R.attr.accentGood))
            }
            b.applicationsBtn.text = getString(R.string.add_remove_apps, it.toString())
            b.appsLabel.text = "Apps ($it)"
        }
    }

    private fun setupClickListeners() {
        b.editBtn.setOnClickListener {
            val intent = Intent(this, WgConfigEditorActivity::class.java)
            intent.putExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, configId)
            intent.putExtra(INTENT_EXTRA_WG_TYPE, wgType.value)
            this.startActivity(intent)
        }

        b.addPeerFab.setOnClickListener { openAddPeerDialog() }

        b.applicationsBtn.setOnClickListener {
            val proxyName = WireguardManager.getConfigName(configId)
            openAppsDialog(proxyName)
        }

        b.deleteBtn.setOnClickListener { showDeleteInterfaceDialog() }

        /*b.newConfLayout.setOnClickListener {
            b.newConfProgressBar.visibility = View.VISIBLE
            io { createConfigOrShowErrorLayout() }
        }*/

        b.publicKeyLabel.setOnClickListener {
            UIUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.publicKeyText.setOnClickListener {
            UIUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.catchAllRl.setOnClickListener {
            b.catchAllCheck.isChecked = !b.catchAllCheck.isChecked
            updateCatchAll(b.catchAllCheck.isChecked)
        }

        b.catchAllCheck.setOnClickListener { updateCatchAll(b.catchAllCheck.isChecked) }

        b.useMobileRl.setOnClickListener {
            b.useMobileCheck.callOnClick()
        }

        b.useMobileCheck.setOnClickListener {
            val sid = ID_WG_BASE + configId
            if (WgHopManager.isAlreadyHop(sid)) {
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.wg_mobile_only_hop_err),
                    Toast.LENGTH_LONG
                )
                b.useMobileCheck.isChecked = !b.useMobileCheck.isChecked
                return@setOnClickListener
            }
            updateUseOnMobileNetwork(b.useMobileCheck.isChecked)
            if (b.useMobileCheck.isChecked) {
                // Enable experimental-dependent settings when experimental features are enabled
                persistentState.enableStabilityDependentSettings(this)
            }
        }

        b.logsBtn.setOnClickListener {
            startActivity(ID_WG_BASE + configId)
        }

        b.hopBtn.setOnClickListener {
            val mapping = WireguardManager.getConfigFilesById(configId)
            if (mapping == null) {
                showInvalidConfigDialog()
                return@setOnClickListener
            }

            if (mapping.isActive || mapping.isCatchAll) {
                io {
                    val sid = ID_WG_BASE + configId
                    val isVia = WgHopManager.isAlreadyHop(sid)
                    if (isVia) {
                        uiCtx {
                            Utilities.showToastUiCentered(
                                this,
                                getString(
                                    R.string.hop_error_toast_msg_1,
                                    getString(R.string.lbl_hop)
                                ),
                                Toast.LENGTH_LONG
                            )
                        }
                        return@io
                    }
                    val hopId = WgHopManager.getHop(configId)
                    Logger.d(LOG_TAG_PROXY, "hop result: $hopId")
                    val iid = convertStringIdToId(hopId)
                    val hopables = WgHopManager.getHopableWgs(configId)
                    uiCtx {
                        if (hopables.isEmpty()) {
                            Utilities.showToastUiCentered(
                                this,
                                getString(R.string.hop_error_toast_msg_2),
                                Toast.LENGTH_LONG
                            )
                        } else {
                            openHopDialog(hopables, iid)
                        }
                    }
                }
            } else {
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.wireguard_no_config_msg),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (_: Exception) {
            Logger.i(LOG_TAG_PROXY, "err converting string id to int: $id")
            INVALID_CONF_ID
        }
    }

    private fun startActivity(searchParam: String?) {
        val intent = Intent(this, NetworkLogsActivity::class.java)
        val query = RULES_SEARCH_ID_WIREGUARD + searchParam
        intent.putExtra(Constants.SEARCH_QUERY, query)
        startActivity(intent)
    }

    private fun updateUseOnMobileNetwork(enabled: Boolean) {
        io { WireguardManager.updateUseOnMobileNetworkConfig(configId, enabled) }
        logEvent(
            "WireGuard Use on Mobile Networks",
            "User ${if (enabled) "enabled" else "disabled"} use on mobile networks for WireGuard config with id $configId"
        )
    }

    private fun updateCatchAll(enabled: Boolean) {
        io {
            if (!VpnController.hasTunnel()) {
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_VPN_NOT_ACTIVE + getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                    b.catchAllCheck.isChecked = !enabled
                }
                return@io
            }

            if (!WireguardManager.canEnableProxy()) {
                Logger.i(
                    LOG_TAG_PROXY,
                    "not in DNS+Firewall mode, cannot enable WireGuard"
                )
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_VPN_NOT_FULL + getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }

            if (WireguardManager.oneWireGuardEnabled()) {
                // this should not happen, ui is disabled if one wireGuard is enabled
                Logger.w(LOG_TAG_PROXY, "one wireGuard is already enabled")
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_OTHER_WG_ACTIVE + getString(
                            R.string.wireguard_enabled_failure
                        ),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }


            val config = WireguardManager.getConfigFilesById(configId)
            if (config == null) {
                Logger.e(LOG_TAG_PROXY, "updateCatchAll: config not found for $configId")
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_WG_INVALID + getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }

            WireguardManager.updateCatchAllConfig(configId, enabled)
            logEvent(
                "WireGuard Catch All apps",
                "User ${if (enabled) "enabled" else "disabled"} catch all apps for WireGuard config with id $configId"
            )
            uiCtx {
                b.applicationsBtn.isEnabled = !enabled
                if (enabled) {
                    b.applicationsBtn.text = getString(R.string.routing_remaining_apps)
                } else {
                    handleAppsCount()
                }
            }
        }
    }

    private fun openAppsDialog(proxyName: String) {
        val proxyId = ID_WG_BASE + configId
        val appsAdapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val includeAppsDialog =
            WgIncludeAppsDialog(this, appsAdapter, mappingViewModel, themeId, proxyId, proxyName)
        includeAppsDialog.setCanceledOnTouchOutside(false)
        includeAppsDialog.show()
    }

    private fun openHopDialog(hopables: List<Config>, selectedId: Int) {
        val curr = WireguardManager.getConfigById(configId)
        if (curr == null) {
            Utilities.showToastUiCentered(
                this,
                getString(R.string.config_invalid_desc),
                Toast.LENGTH_SHORT
            )
            return
        }
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val hopDialog = WgHopDialog(this, themeId, configId, hopables, selectedId)
        hopDialog.setCanceledOnTouchOutside(false)
        hopDialog.show()
    }

    private fun showDeleteInterfaceDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        val delText =
            getString(
                R.string.two_argument_space,
                getString(R.string.config_delete_dialog_title),
                getString(R.string.lbl_wireguard)
            )
        builder.setTitle(delText)
        builder.setMessage(getString(R.string.config_delete_dialog_desc))
        builder.setCancelable(true)
        builder.setPositiveButton(delText) { _, _ ->
            io {
                WireguardManager.deleteConfig(configId)
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        getString(R.string.config_add_success_toast),
                        Toast.LENGTH_SHORT
                    )
                    finish()
                }
                logEvent("Delete WireGuard config", "User deleted WireGuard config with id $configId")
            }
        }

        builder.setNegativeButton(this.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun openAddPeerDialog() {
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val addPeerDialog = WgAddPeerDialog(this, themeId, configId, null)
        addPeerDialog.setCanceledOnTouchOutside(false)
        addPeerDialog.show()
        addPeerDialog.setOnDismissListener {
            if (wgPeersAdapter != null) {
                wgPeersAdapter?.dataChanged()
            } else {
                setPeersAdapter()
            }
        }
    }

    private fun setPeersAdapter() {
        layoutManager = LinearLayoutManager(this)
        b.peersList.layoutManager = layoutManager
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        wgPeersAdapter = WgPeersAdapter(this, themeId, configId, peers)
        b.peersList.adapter = wgPeersAdapter
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        if (isFinishing || isDestroyed) return

        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    // SSID section setup
    private fun setupSsidSection(cfg: WgConfigFilesImmutable?) {
        val sw = b.ssidCheck
        val editGroup = b.ssidEditGroup
        val displayGroup = b.ssidDisplayGroup
        val editBtn: AppCompatImageView = b.ssidEditBtn
        val valueTv = b.ssidValueTv
        val layout = b.ssidFilterRl
        val permissionErrorLayout = b.ssidPermissionErrorLayout
        val locationErrorLayout = b.ssidLocationErrorLayout

        if (cfg == null) {
            hideSsidExtras(editGroup, displayGroup)
            hideErrorLayouts(permissionErrorLayout, locationErrorLayout)
            sw.isEnabled = false
            Logger.w(LOG_TAG_UI, "setupSsidSection: cfg is null for $configId")
            return
        }

        // Check if device supports required features
        if (!SsidPermissionManager.isDeviceSupported(this)) {
            hideSsidExtras(editGroup, displayGroup)
            hideErrorLayouts(permissionErrorLayout, locationErrorLayout)
            sw.isEnabled = false
            layout.visibility = View.GONE
            Logger.w(LOG_TAG_UI, "setupSsidSection: device not supported for SSID feature")
            return
        }

        // Always keep the switch enabled
        sw.isEnabled = true
        layout.visibility = View.VISIBLE

        // Check permissions and location services
        val hasPermissions = SsidPermissionManager.hasRequiredPermissions(this)
        val isLocationEnabled = SsidPermissionManager.isLocationEnabled(this)

        val enabled = cfg.ssidEnabled
        val ssidItems = SsidItem.parseStorageList(cfg.ssids)
        sw.isChecked = enabled

        if (enabled && hasPermissions && isLocationEnabled) {
            // SSID is enabled and we have all necessary permissions/location
            if (ssidItems.isEmpty()) {
                val txt = getString(R.string.two_argument_space, getString(R.string.lbl_all), getString(R.string.lbl_ssids))
                val allTxt = getString(R.string.single_argument_parenthesis, txt)
                valueTv.text = allTxt
                showSsidDisplay(editGroup, displayGroup)
            } else {
                val displayText =
                    ssidItems.joinToString(", ") { "${it.name} (${it.type.getDisplayName(this)})" }
                valueTv.text = displayText
                showSsidDisplay(editGroup, displayGroup)
            }
        } else {
            // Hide SSID display/edit groups when disabled or missing permissions
            hideSsidExtras(editGroup, displayGroup)
        }

        Logger.d(LOG_TAG_UI, "SSID permissions: $hasPermissions, Location enabled: $isLocationEnabled, checked: ${b.ssidCheck.isChecked}")
        // Show/hide error layouts based on permission and location status
        updateErrorLayouts(hasPermissions, isLocationEnabled, permissionErrorLayout, locationErrorLayout)

        sw.setOnCheckedChangeListener { _, isChecked ->
            // Check current permissions and location status dynamically
            val currentHasPermissions = SsidPermissionManager.hasRequiredPermissions(this)
            val currentLocationEnabled = SsidPermissionManager.isLocationEnabled(this)

            // Check permissions before enabling SSID feature
            if (isChecked && !currentHasPermissions) {
                // Don't reset the switch, just request permissions
                SsidPermissionManager.checkAndRequestPermissions(this, ssidPermissionCallback)
                Logger.d(LOG_TAG_UI, "SSID permissions not granted, requesting...")
                return@setOnCheckedChangeListener
            }

            // Check if location services are enabled
            if (isChecked && !currentLocationEnabled) {
                // Don't reset the switch, just prompt user to enable location
                showLocationEnableDialog()
                Logger.d(LOG_TAG_UI, "Location services not enabled, prompting user...")
                return@setOnCheckedChangeListener
            }

            // If we reach here, either we're disabling or we have all required permissions
            io { WireguardManager.updateSsidEnabled(configId, isChecked) }
            logEvent(
                "SSID feature toggled",
                "ConfigId: $configId, Enabled: $isChecked"
            )

            if (isChecked && currentHasPermissions && currentLocationEnabled) {
                // Enable experimental-dependent settings when experimental features are enabled
                persistentState.enableStabilityDependentSettings(this)

                // Enabling with proper permissions, load and display SSIDs
                io {
                    val cur = WireguardManager.getConfigFilesById(configId)?.ssids ?: ""
                    val list = SsidItem.parseStorageList(cur)
                    uiCtx {
                        if (list.isEmpty()) {
                            val allTxt = getString(R.string.two_argument_space, getString(R.string.lbl_all), getString(R.string.lbl_ssids))
                            valueTv.text = allTxt
                            showSsidDisplay(editGroup, displayGroup)
                        } else {
                            val displayText =
                                list.joinToString(", ") { "${it.name} (${it.type.getDisplayName(this@WgConfigDetailActivity)})" }
                            valueTv.text = displayText
                            showSsidDisplay(editGroup, displayGroup)
                        }
                    }
                }
                Logger.i(LOG_TAG_UI, "SSID feature enabled for configId: $configId")
            } else {
                // Disabling SSID feature
                hideSsidExtras(editGroup, displayGroup)
                Logger.i(LOG_TAG_UI, "SSID feature disabled for configId: $configId")
            }

            // Update error layouts after state change with current permission status
            updateErrorLayouts(currentHasPermissions, currentLocationEnabled, permissionErrorLayout, locationErrorLayout)
        }

        layout.setOnClickListener { sw.performClick() }

        editBtn.setOnClickListener {
            openSsidDialog()
        }

        // Setup click listeners for error action buttons
        setupErrorActionListeners()
    }

    private fun setupErrorActionListeners() {
        // Permission error action - opens app settings
        b.ssidPermissionErrorAction.setOnClickListener {
            openAndroidAppInfo(this, this.packageName)
        }

        // Location error action - opens location settings
        b.ssidLocationErrorAction.setOnClickListener {
            SsidPermissionManager.requestLocationEnable(this)
        }
    }

    private fun hideSsidExtras(editGroup: LinearLayout, displayGroup: LinearLayout) {
        editGroup.visibility = View.GONE
        displayGroup.visibility = View.GONE
    }

    private fun showSsidDisplay(editGroup: LinearLayout, displayGroup: LinearLayout) {
        editGroup.visibility = View.GONE
        displayGroup.visibility = View.VISIBLE
    }

    private fun openSsidDialog() {
        val currentSsids = WireguardManager.getConfigFilesById(configId)?.ssids ?: ""
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val ssidDialog = WgSsidDialog(this, themeId, currentSsids) { newSsids ->
            // Save callback - update the SSID configuration
            io {
                WireguardManager.updateSsids(configId, newSsids)
                // Refresh SSID section after saving
                uiCtx {
                    val cfg = WireguardManager.getConfigFilesById(configId)
                    setupSsidSection(cfg)
                }
            }
        }
        ssidDialog.setCanceledOnTouchOutside(false)
        ssidDialog.show()
        ssidDialog.setOnDismissListener {
            // Refresh SSID section after dialog dismisses
            val cfg = WireguardManager.getConfigFilesById(configId)
            setupSsidSection(cfg)
        }
    }

    private fun showLocationEnableDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.ssid_location_error))
        builder.setMessage(getString(R.string.location_enable_explanation, getString(R.string.lbl_ssids)))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.ssid_location_error_action)) { dialog, _ ->
            SsidPermissionManager.requestLocationEnable(this)
            dialog.dismiss()
            Logger.vv(LOG_TAG_UI, "Prompted user to enable location services, opening settings...")
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // Reset the SSID switch since location is required
            b.ssidCheck.isChecked = false
            io { WireguardManager.updateSsidEnabled(configId, false) }
            logEvent("SSID location denied", "User denied enabling location services for configId: $configId")
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun showSsidPermissionExplanationDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.ssid_permission_error_action))
        builder.setMessage(getString(R.string.ssid_permission_explanation, getString(R.string.lbl_ssids)))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.ssid_permission_error_action)) { dialog, _ ->
            SsidPermissionManager.requestSsidPermissions(this)
            dialog.dismiss()
            Logger.vv(LOG_TAG_UI, "Showing SSID permission rationale dialog, requesting permissions...")
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // Reset the SSID switch since permissions are required
            b.ssidCheck.isChecked = false
            io { WireguardManager.updateSsidEnabled(configId, false) }
            logEvent("SSID permission denied", "User denied SSID permissions for configId: $configId")
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun updateErrorLayouts(
        hasPermissions: Boolean,
        isLocationEnabled: Boolean,
        permissionErrorLayout: LinearLayout,
        locationErrorLayout: LinearLayout
    ) {
        val sw = b.ssidCheck

        // Show permission error if SSID is enabled but permissions are missing
        if (sw.isChecked && !hasPermissions) {
            Logger.vv(LOG_TAG_UI, "Showing permission error layout")
            permissionErrorLayout.visibility = View.VISIBLE
        } else {
            permissionErrorLayout.visibility = View.GONE
        }

        // Show location error if SSID is enabled and has permissions but location is disabled
        if (sw.isChecked && hasPermissions && !isLocationEnabled) {
            Logger.vv(LOG_TAG_UI, "Showing location error layout")
            locationErrorLayout.visibility = View.VISIBLE
        } else {
            locationErrorLayout.visibility = View.GONE
        }
    }

    private fun hideErrorLayouts(
        permissionErrorLayout: LinearLayout,
        locationErrorLayout: LinearLayout
    ) {
        Logger.vv(LOG_TAG_UI, "Hiding all SSID error layouts")
        permissionErrorLayout.visibility = View.GONE
        locationErrorLayout.visibility = View.GONE
    }

    private fun updateSsidPermissionState(hasPermissions: Boolean) {
        // This method can be used to update UI state when permissions change
        // Currently handled by setupSsidSection being called from callback
        val cfg = WireguardManager.getConfigFilesById(configId)
        setupSsidSection(cfg)
    }

    private fun showPermissionDeniedDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.ssid_permission_error_action))
        builder.setMessage(SsidPermissionManager.getPermissionExplanation(this))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.ssid_permission_error_action)) { _, _ ->
            SsidPermissionManager.openAppSettings(this)
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // Reset the SSID switch since permissions are required
            b.ssidCheck.isChecked = false
            io { WireguardManager.updateSsidEnabled(configId, false) }
            logEvent("SSID permission denied", "User denied SSID permissions for configId: $configId")
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }
}
