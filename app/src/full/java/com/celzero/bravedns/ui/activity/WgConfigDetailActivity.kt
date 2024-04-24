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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.adapter.WgPeersAdapter
import com.celzero.bravedns.databinding.ActivityWgDetailBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
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
            fun fromInt(value: Int) = entries.first { it.value == value }
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
        } else if (wgType.isOneWg()) {
            b.wgHeaderTv.text =
                getString(R.string.rt_list_simple_btn_txt).replaceFirstChar(Char::titlecase)
            b.lockdownRl.visibility = View.GONE
            b.catchAllRl.visibility = View.GONE
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
            finish()
            return
        }

        // handleWarpConfigView()
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

        /*if (config == null && configId == WARP_ID) {
            showNewWarpConfigLayout()
            return@uiCtx
        }*/

        prefillConfig(config)
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
        b.configNameText.text = config.getName()
        b.publicKeyText.text = wgInterface?.getKeyPair()?.getPublicKey()?.base64()

        if (wgInterface?.getAddresses()?.isEmpty() == true) {
            b.addressesLabel.visibility = View.GONE
            b.addressesText.visibility = View.GONE
        } else {
            b.addressesText.text = wgInterface?.getAddresses()?.joinToString { it.toString() }
        }
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
            b.dnsServersLabel.visibility = View.GONE
            b.dnsServersText.visibility = View.GONE
        }

        // uncomment this if we want to show the dns servers, listen port and mtu
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

    /*private suspend fun createConfigOrShowErrorLayout() {
        val works = isWarpWorking()
        if (works) {
            fetchWarpConfigFromServer()
        } else {
            showConfigCreationError()
        }
    }

    private suspend fun showConfigCreationError() {
        uiCtx {
            Toast.makeText(this, getString(R.string.new_warp_error_toast), Toast.LENGTH_LONG).show()
        }
    }*/

    /*private suspend fun fetchWarpConfigFromServer() {
        val config = WireguardManager.getNewWarpConfig(WARP_ID)
        Log.i(LOG_TAG_PROXY, "new config from server: ${config?.getName()}")
        if (config == null) {
            showConfigCreationError()
            return
        }
        uiCtx {
            showWarpConfig()
            prefillWarpConfig(config)
        }
    }*/

    /* private suspend fun handleWarpConfigView() {
        if (configId == WARP_ID) {
            if (isWarpConfAvailable()) {
                if (DEBUG) Log.d(LOG_TAG_PROXY, "warp config already available")
                showWarpConfig()
            } else {
                if (DEBUG) Log.d(LOG_TAG_PROXY, "warp config not found, show new config layout")
                showNewWarpConfigLayout()
            }
        } else {
            b.interfaceEdit.visibility = View.VISIBLE
            b.interfaceDelete.visibility = View.VISIBLE
            b.interfaceRefresh.visibility = View.GONE
            b.addPeerFab.visibility = View.VISIBLE
        }
    }

    private suspend fun isWarpConfAvailable(): Boolean {
        return WireguardManager.getWarpConfig() != null
    }*/

    /*private fun showNewWarpConfigLayout() {
            b.interfaceDetailCard.visibility = View.GONE
            b.peersList.visibility = View.GONE
            b.addPeerFab.visibility = View.GONE
            b.newConfLayout.visibility = View.VISIBLE
        }

        private fun showWarpConfig() {
            b.interfaceDetailCard.visibility = View.VISIBLE
            b.peersList.visibility = View.VISIBLE
            b.addPeerFab.visibility = View.GONE
            b.interfaceEdit.visibility = View.GONE
            b.interfaceDelete.visibility = View.GONE
            b.interfaceRefresh.visibility = View.VISIBLE
            hideNewWarpConfLayout()
        }

        private fun hideNewWarpConfLayout() {
            b.newConfLayout.visibility = View.GONE
            b.interfaceDetailCard.visibility = View.VISIBLE
            b.peersList.visibility = View.VISIBLE
        }
    */
    private fun handleAppsCount() {
        val id = ProxyManager.ID_WG_BASE + configId
        b.applicationsBtn.isEnabled = true
        mappingViewModel.getAppCountById(id).observe(this) {
            if (it == 0) {
                b.applicationsBtn.setTextColor(UIUtils.fetchColor(this, R.attr.accentBad))
            } else {
                b.applicationsBtn.setTextColor(UIUtils.fetchColor(this, R.attr.accentGood))
            }
            b.applicationsBtn.text = getString(R.string.add_remove_apps, it.toString())
        }
    }

    private fun setupClickListeners() {
        b.interfaceEdit.setOnClickListener {
            val intent = Intent(this, WgConfigEditorActivity::class.java)
            intent.putExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, configId)
            this.startActivity(intent)
        }

        b.addPeerFab.setOnClickListener { openAddPeerDialog() }

        b.applicationsBtn.setOnClickListener {
            val proxyName = WireguardManager.getConfigName(configId)
            openAppsDialog(proxyName)
        }

        b.interfaceDelete.setOnClickListener { showDeleteInterfaceDialog() }

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
    }

    private fun updateLockdown(enabled: Boolean) {
        io { WireguardManager.updateLockdownConfig(configId, enabled) }
    }

    /* private fun updateOneWireGuard(enabled: Boolean) {
        io {
            WireguardManager.updateOneWireGuardConfig(configId, enabled)
            uiCtx {
                // disable add apps button
                b.applicationsBtn.isEnabled = !enabled
                b.applicationsBtn.text = getString(R.string.one_wg_apps_added)
                Toast.makeText(this, getString(R.string.one_wg_success_toast), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }*/

    private fun updateCatchAll(enabled: Boolean) {
        io {
            val config = WireguardManager.getConfigFilesById(configId)
            if (config == null) {
                Logger.e(LOG_TAG_PROXY, "updateCatchAll: config not found for $configId")
                return@io
            }
            if (WireguardManager.canEnableConfig(config)) {
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
            } else {
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                    b.catchAllCheck.isChecked = false
                }
                return@io
            }
        }
    }

    private fun openAppsDialog(proxyName: String) {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val proxyId = ProxyManager.ID_WG_BASE + configId
        val appsAdapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
        val includeAppsDialog =
            WgIncludeAppsDialog(this, appsAdapter, mappingViewModel, themeId, proxyId, proxyName)
        includeAppsDialog.setCanceledOnTouchOutside(false)
        includeAppsDialog.show()
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
