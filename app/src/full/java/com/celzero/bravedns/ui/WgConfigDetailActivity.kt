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

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.adapter.WgPeersAdapter
import com.celzero.bravedns.databinding.ActivityWgDetailBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.service.WireguardManager.WARP_ID
import com.celzero.bravedns.service.WireguardManager.isWarpWorking
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_PROXY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UiUtils
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
    private var animation: Animation =
        RotateAnimation(
            ANIMATION_START_DEGREE,
            ANIMATION_END_DEGREE,
            Animation.RELATIVE_TO_SELF,
            ANIMATION_PIVOT_VALUE,
            Animation.RELATIVE_TO_SELF,
            ANIMATION_PIVOT_VALUE
        )

    private var configId: Int = INVALID_CONF_ID
    private var wgInterface: WgInterface? = null
    private val peers: MutableList<Peer> = mutableListOf()

    companion object {
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
        private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        configId = intent.getIntExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, INVALID_CONF_ID)
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        handleWarpConfigView()
        handleAppsCount()
        val config = WireguardManager.getConfigById(configId)
        if (config == null && configId == WARP_ID) {
            showNewWarpConfigLayout()
            return
        }
        if (config == null) {
            finish()
            return
        }
        prefillWarpConfig(config)
    }

    private fun prefillWarpConfig(config: Config) {
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

    private suspend fun createConfigOrShowErrorLayout() {
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
    }

    private suspend fun fetchWarpConfigFromServer() {
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
    }

    private fun handleWarpConfigView() {
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

    private fun isWarpConfAvailable(): Boolean {
        return WireguardManager.getWarpConfig() != null
    }

    private fun showNewWarpConfigLayout() {
        b.tunnelDetailCard.visibility = View.GONE
        b.peersList.visibility = View.GONE
        b.addPeerFab.visibility = View.GONE
        b.newConfLayout.visibility = View.VISIBLE
    }

    private fun showWarpConfig() {
        b.tunnelDetailCard.visibility = View.VISIBLE
        b.peersList.visibility = View.VISIBLE
        b.addPeerFab.visibility = View.GONE
        b.interfaceEdit.visibility = View.GONE
        b.interfaceDelete.visibility = View.GONE
        b.interfaceRefresh.visibility = View.VISIBLE
        hideNewWarpConfLayout()
    }

    private fun hideNewWarpConfLayout() {
        b.newConfLayout.visibility = View.GONE
        b.tunnelDetailCard.visibility = View.VISIBLE
        b.peersList.visibility = View.VISIBLE
    }

    private fun handleAppsCount() {
        val id = ProxyManager.ID_WG_BASE + configId
        mappingViewModel.getAppCountById(id).observe(this) {
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

        b.applicationsBtn.setOnClickListener { openAppsDialog() }

        b.interfaceDelete.setOnClickListener { showDeleteInterfaceDialog() }

        b.newConfLayout.setOnClickListener {
            b.newConfProgressBar.visibility = View.VISIBLE
            io { createConfigOrShowErrorLayout() }
        }

        b.interfaceRefresh.setOnClickListener {
            b.interfaceRefresh.isEnabled = false
            b.interfaceRefresh.animation = animation
            b.interfaceRefresh.startAnimation(animation)
            io {
                createConfigOrShowErrorLayout()
                uiCtx {
                    b.interfaceRefresh.isEnabled = true
                    b.interfaceRefresh.clearAnimation()
                }
            }
        }

        b.publicKeyLabel.setOnClickListener {
            UiUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.publicKeyText.setOnClickListener {
            UiUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }
    }

    init {
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    private fun openAppsDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val proxyId = ProxyManager.ID_WG_BASE + configId
        val proxyName = WireguardManager.getConfigName(configId)
        val appsAdapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
        val includeAppsDialog =
            WgIncludeAppsDialog(this, appsAdapter, mappingViewModel, themeId, proxyId, proxyName)
        includeAppsDialog.setCanceledOnTouchOutside(false)
        includeAppsDialog.show()
    }

    private fun showDeleteInterfaceDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.lbl_delete))
        builder.setMessage(getString(R.string.config_delete_dialog_desc))
        builder.setCancelable(true)
        builder.setPositiveButton(this.getString(R.string.lbl_delete)) { _, _ ->
            WireguardManager.deleteConfig(configId)
            Toast.makeText(
                    this,
                    getString(R.string.config_delete_success_toast),
                    Toast.LENGTH_SHORT
                )
                .show()
            finish()
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
        addPeerDialog.setOnDismissListener { handlePeers() }
    }

    private fun handlePeers() {
        // instead of clearing the list and adding all the peers again, we just update the list
        // with the new peer, TODO: check if it can be done in a better way
        peers.clear()
        peers.addAll(WireguardManager.getPeers(configId))
        // update the peers adapter if there is any change in the list
        wgPeersAdapter?.notifyDataSetChanged()
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
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
