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
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import backend.RouterStats
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.adapter.WgPeersAdapter
import com.celzero.bravedns.databinding.ActivityWgDetailBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_OTHER_WG_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_FULL
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_WG_INVALID
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.service.WireguardManager.WG_HANDSHAKE_TIMEOUT
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_WIREGUARD
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.ui.dialog.WgHopDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class WgConfigDetailActivity : AppCompatActivity(R.layout.activity_wg_detail) {
    private val b by viewBinding(ActivityWgDetailBinding::bind)
    private val persistentState by inject<PersistentState>()

    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private var wgPeersAdapter: WgPeersAdapter? = null
    private var layoutManager: LinearLayoutManager? = null

    private var configId: Int = INVALID_CONF_ID
    private var wgInterface: WgInterface? = null
    private val peers: MutableList<Peer> = mutableListOf()
    private var wgType: WgType = WgType.DEFAULT

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
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: DEFAULT
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        configId = intent.getIntExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, INVALID_CONF_ID)
        wgType = WgType.fromInt(intent.getIntExtra(INTENT_EXTRA_WG_TYPE, WgType.DEFAULT.value))
    }

    override fun onResume() {
        super.onResume()
        init()
        setupClickListeners()
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

        b.editBtn.text = getString(R.string.rt_edit_dialog_positive).lowercase()
        b.globalLockdownTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.firewall_rule_global_lockdown),
                getString(R.string.symbol_lockdown)
            )
        b.catchAllTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.catch_all_wg_dialog_title),
                getString(R.string.symbol_lightening)
            )
        if (wgType.isDefault()) {
            b.wgHeaderTv.text = getString(R.string.lbl_advanced).replaceFirstChar(Char::titlecase)
            b.lockdownRl.visibility = View.VISIBLE
            b.catchAllRl.visibility = View.VISIBLE
            b.oneWgInfoTv.visibility = View.GONE
            b.hopBtn.visibility = View.VISIBLE
        } else if (wgType.isOneWg()) {
            b.wgHeaderTv.text =
                getString(R.string.rt_list_simple_btn_txt).replaceFirstChar(Char::titlecase)
            b.lockdownRl.visibility = View.GONE
            b.catchAllRl.visibility = View.GONE
            b.hopBtn.visibility = View.GONE
            b.oneWgInfoTv.visibility = View.VISIBLE
            b.applicationsBtn.isEnabled = false
            b.applicationsBtn.text = getString(R.string.one_wg_apps_added)
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
                b.lockdownCheck.isEnabled = false
                b.applicationsBtn.isEnabled = false
                b.applicationsBtn.text = getString(R.string.routing_remaining_apps)
            }
            b.lockdownCheck.isChecked = mapping.isLockdown
        }

        if (shouldObserveAppsCount()) {
            handleAppsCount()
        } else {
            b.applicationsBtn.isEnabled = false
            // texts are updated based on the catch all and one-wg
        }

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
                    b.interfaceDetailCard.strokeColor = fetchColor(this, R.attr.chipTextNegative)
                    b.statusText.text = getString(R.string.status_failing)
                        .replaceFirstChar(Char::titlecase)
                } else if (statusPair.first != null) {
                    val handshakeTime = getHandshakeTime(stats).toString()
                    val statusText = getIdleStatusText(ps, stats)
                        .ifEmpty { getStatusText(ps, handshakeTime, stats, statusPair.second) }
                    b.statusText.text = statusText
                } else {
                    if (statusPair.second.isEmpty()) {
                        b.statusText.text =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                    } else {
                        val txt =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase) + "(${statusPair.second})"
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
                getString(R.string.status_waiting) + "($errMsg)"
            } else {
                getString(R.string.status_waiting)
            }
            return txt.replaceFirstChar(Char::titlecase)
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

    private fun getIdleStatusText(status: UIUtils.ProxyStatus?, stats: RouterStats?): String {
        if (status != UIUtils.ProxyStatus.TZZ && status != UIUtils.ProxyStatus.TNT) return ""
        if (stats == null || stats.lastOK == 0L) return ""
        if (System.currentTimeMillis() - stats.since >= WG_HANDSHAKE_TIMEOUT) return ""

        return getString(R.string.dns_connected).replaceFirstChar(Char::titlecase)
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
        return when (status) {
            UIUtils.ProxyStatus.TOK -> if (stats?.lastOK == 0L) return R.attr.chipTextNeutral else R.attr.accentGood
            UIUtils.ProxyStatus.TUP, UIUtils.ProxyStatus.TZZ -> R.attr.chipTextNeutral
            else -> R.attr.chipTextNegative // TNT, TKO, TEND
        }
    }

    private fun showInvalidConfigDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.lbl_wireguard))
        builder.setMessage(getString(R.string.config_invalid_desc))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { _, _ ->
            finish()
        }
        builder.setNeutralButton(getString(R.string.lbl_delete)) { _, _ ->
            WireguardManager.deleteConfig(configId)
        }
        builder.create().show()
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
        b.configIdText.text = getString(R.string.single_argument_parenthesis, config.getId().toString())

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
            b.publicKeyText.text = wgInterface?.getKeyPair()?.getPublicKey()?.base64()
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

        b.lockdownCheck.setOnClickListener { updateLockdown(b.lockdownCheck.isChecked) }

        b.catchAllCheck.setOnClickListener { updateCatchAll(b.catchAllCheck.isChecked) }

        b.logsBtn.setOnClickListener {
            startActivity(ProxyManager.ID_WG_BASE + configId)
        }

        b.hopBtn.setOnClickListener {
            io {
                val id = ID_WG_BASE + configId
                val hopId = VpnController.via(id)
                Logger.d(LOG_TAG_PROXY, "hop result: $hopId")
                val sid = convertStringIdToId(hopId)
                val hopables = WgHopManager.getHopableWgs(id)
                //uiCtx { showHopDialog(hopables, sid) }
                uiCtx { openHopDialog(hopables, sid) }
            }
        }
    }

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (e: Exception) {
            Logger.i(LOG_TAG_PROXY, "err converting string id to int: $id")
            INVALID_CONF_ID
        }
    }

    private fun showHopDialog(filteredConfigs: List<Config>, sid: Int) {
        val curr = WireguardManager.getConfigById(configId)
        if (curr == null) {
            Utilities.showToastUiCentered(
                this,
                "Config not found",
                Toast.LENGTH_SHORT
            )
            return
        }

        val configNames = filteredConfigs.map { it.getName() }.toTypedArray()
        val selectedIndex = if (sid == -1) {
            -1
        } else {
            filteredConfigs.indexOfFirst { it.getId() == sid }
        }
        Logger.v(LOG_TAG_PROXY, "hop: configs: ${filteredConfigs.map { it.getId() }}")
        Logger.v(LOG_TAG_PROXY, "hop dialog: selected Index: $selectedIndex")
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Select a config to hop")
        builder.setSingleChoiceItems(configNames, selectedIndex) { dialog, which ->
            val config = filteredConfigs[which]
            io {
                val res = if (which == selectedIndex) {
                    // set empty via so that it is removed from the map
                    WgHopManager.deleteBySrc(ID_WG_BASE + curr.getId())
                } else {
                    WgHopManager.hop(curr.getId(), config.getId())
                }
                Logger.d(LOG_TAG_PROXY, "hop result: res:${res.first}, err: ${res.second}")
                uiCtx {
                    if (res.first) {
                        Utilities.showToastUiCentered(
                            this,
                            "Hop successful",
                            Toast.LENGTH_SHORT
                        )
                    } else {
                        Utilities.showToastUiCentered(
                            this,
                            res.second,
                            Toast.LENGTH_SHORT
                        )
                    }
                }
                WgHopManager.printMaps()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun startActivity(searchParam: String?) {
        val intent = Intent(this, NetworkLogsActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val query = RULES_SEARCH_ID_WIREGUARD + searchParam
        intent.putExtra(Constants.SEARCH_QUERY, query)
        startActivity(intent)
    }

    private fun updateLockdown(enabled: Boolean) {
        io { WireguardManager.updateLockdownConfig(configId, enabled) }
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
            uiCtx {
                b.lockdownCheck.isEnabled = !enabled
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
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val proxyId = ID_WG_BASE + configId
        val appsAdapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
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
                "Config not found",
                Toast.LENGTH_SHORT
            )
            return
        }
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val hopDialog = WgHopDialog(this, themeId, hopables, selectedId)
        hopDialog.setCanceledOnTouchOutside(false)
        hopDialog.show()
    }

    private fun showDeleteInterfaceDialog() {
        val builder = MaterialAlertDialogBuilder(this)
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
            }
        }

        builder.setNegativeButton(this.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun openAddPeerDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
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
}
