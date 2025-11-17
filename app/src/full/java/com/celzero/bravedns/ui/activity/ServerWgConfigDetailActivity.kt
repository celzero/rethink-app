/*
 * Copyright 2025 RethinkDNS and its authors
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
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ServerWgPeersAdapter
import com.celzero.bravedns.databinding.ActivityServerWgDetailBinding
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.service.CountryConfigManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Activity for viewing and editing server-provided WireGuard configurations
 * Allows editing MTU, listen port (from dropdown), and peer persistent keepalive
 * No delete or full edit capabilities
 */
class ServerWgConfigDetailActivity : AppCompatActivity(R.layout.activity_server_wg_detail) {
    private val b by viewBinding(ActivityServerWgDetailBinding::bind)
    private val persistentState by inject<PersistentState>()

    private var serverWgPeersAdapter: ServerWgPeersAdapter? = null
    private var layoutManager: LinearLayoutManager? = null

    private var configId: Int = WireguardManager.INVALID_CONF_ID
    private var wgInterface: WgInterface? = null
    private val peers: MutableList<Peer> = mutableListOf()
    private var countryCode: String? = null

    // Available listen ports
    private val availableListenPorts = listOf(80, 443, 8080, 9110)

    companion object {
        const val INTENT_EXTRA_SERVER_ID = "SERVER_WG_CONFIG_ID"
        const val INTENT_EXTRA_FROM_SERVER_SELECTION = "FROM_SERVER_SELECTION"
        const val INTENT_EXTRA_COUNTRY_CODE = "COUNTRY_CODE"
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
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        configId = intent.getIntExtra(INTENT_EXTRA_SERVER_ID, WireguardManager.INVALID_CONF_ID)
        countryCode = intent.getStringExtra(INTENT_EXTRA_COUNTRY_CODE)

        // Setup toolbar
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Set toolbar title
        b.collapsingToolbar.title = getString(R.string.lbl_server_config)

        // Handle back button
        b.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        init()
        setupClickListeners()
    }

    private fun init() {
        // For now, show a message that server configs are loaded from dummy data
        // In production, this would load actual server-provided WireGuard configs
        Utilities.showToastUiCentered(
            this,
            "Server configuration view (using dummy data for demo)",
            Toast.LENGTH_LONG
        )

        // Load config if ID is valid
        val config = WireguardManager.getConfigById(configId)

        if (config == null) {
            showInvalidConfigDialog()
            return
        }

        prefillConfig(config)
        setupListenPortSpinner()
    }

    private fun showInvalidConfigDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.lbl_wireguard))
        builder.setMessage(getString(R.string.config_invalid_desc))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { _, _ ->
            finish()
        }
        val dialog = builder.create()
        dialog.show()
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

        b.statusText.text = getString(R.string.lbl_server_config_readonly)

        // Pre-fill MTU (read-only)
        if (wgInterface?.mtu?.isPresent == true) {
            b.mtuText.text = wgInterface?.mtu?.get().toString()
            b.mtuText.visibility = View.VISIBLE
        }

        // Load switch states
        loadConfigSettings()

        setPeersAdapter()
    }

    private fun loadConfigSettings() {
        val cc = countryCode
        if (cc == null) {
            // Hide settings cards if no country code
            b.otherSettingsCard.visibility = View.GONE
            b.mobileSsidSettingsCard.visibility = View.GONE
            return
        }

        // Load from CountryConfigManager
        lifecycleScope.launch(Dispatchers.IO) {
            val config = CountryConfigManager.getConfig(cc)
            withContext(Dispatchers.Main) {
                if (config != null) {
                    b.lockdownCheck.isChecked = config.lockdown
                    b.catchAllCheck.isChecked = config.catchAll
                    b.useMobileCheck.isChecked = config.mobileOnly
                    b.ssidCheck.isChecked = config.ssidBased
                    b.otherSettingsCard.visibility = View.VISIBLE
                    b.mobileSsidSettingsCard.visibility = View.VISIBLE
                } else {
                    // Create default config if it doesn't exist
                    CountryConfigManager.upsertConfig(
                        CountryConfig(
                            cc = cc,
                            enabled = true,
                            lastModified = System.currentTimeMillis()
                        )
                    )
                    b.otherSettingsCard.visibility = View.VISIBLE
                    b.mobileSsidSettingsCard.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupListenPortSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableListenPorts.map { it.toString() }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.listenPortSpinner.adapter = adapter

        // Set current listen port if present
        if (wgInterface?.listenPort?.isPresent == true) {
            val currentPort = wgInterface?.listenPort?.get()
            val position = availableListenPorts.indexOf(currentPort)
            if (position >= 0) {
                b.listenPortSpinner.setSelection(position)
            }
        }

        // Make read-only for server configs
        b.listenPortSpinner.isEnabled = false
    }

    private fun setupClickListeners() {
        // Applications button
        b.applicationsBtn.setOnClickListener {
            openApplicationsDialog()
        }

        // Hop button
        b.hopBtn.setOnClickListener {
            openHopDialog()
        }

        // Logs button
        b.logsBtn.setOnClickListener {
            openLogsDialog()
        }

        val cc = countryCode
        if (cc == null) {
            // If no country code, hide the settings cards
            b.otherSettingsCard.visibility = View.GONE
            b.mobileSsidSettingsCard.visibility = View.GONE
            return
        }

        // Lockdown mode toggle - uses CountryConfigManager
        b.lockdownCheck.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch(Dispatchers.IO) {
                CountryConfigManager.updateLockdown(cc, isChecked)
                withContext(Dispatchers.Main) {
                    Utilities.showToastUiCentered(
                        this@ServerWgConfigDetailActivity,
                        if (isChecked) "Lockdown mode enabled" else "Lockdown mode disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        // Catch all mode toggle - uses CountryConfigManager
        b.catchAllCheck.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch(Dispatchers.IO) {
                CountryConfigManager.updateCatchAll(cc, isChecked)
                withContext(Dispatchers.Main) {
                    Utilities.showToastUiCentered(
                        this@ServerWgConfigDetailActivity,
                        if (isChecked) "Catch all mode enabled" else "Catch all mode disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        // Mobile data only toggle - uses CountryConfigManager
        b.useMobileCheck.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch(Dispatchers.IO) {
                CountryConfigManager.updateMobileOnly(cc, isChecked)
                withContext(Dispatchers.Main) {
                    Utilities.showToastUiCentered(
                        this@ServerWgConfigDetailActivity,
                        if (isChecked) "Mobile data only enabled" else "Mobile data only disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        // SSID filter toggle - uses CountryConfigManager
        b.ssidCheck.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch(Dispatchers.IO) {
                CountryConfigManager.updateSsidBased(cc, isChecked)
                withContext(Dispatchers.Main) {
                    Utilities.showToastUiCentered(
                        this@ServerWgConfigDetailActivity,
                        if (isChecked) "SSID filter enabled" else "SSID filter disabled",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        // Layout click listeners for better UX
        b.lockdownRl.setOnClickListener { b.lockdownCheck.performClick() }
        b.catchAllRl.setOnClickListener { b.catchAllCheck.performClick() }
        b.useMobileRl.setOnClickListener { b.useMobileCheck.performClick() }
        b.ssidFilterRl.setOnClickListener { b.ssidCheck.performClick() }
    }

    private fun setPeersAdapter() {
        layoutManager = LinearLayoutManager(this)
        b.peersList.layoutManager = layoutManager
        serverWgPeersAdapter = ServerWgPeersAdapter(this, peers) { position, isExpanded ->
            // Handle peer expansion if needed
        }
        b.peersList.adapter = serverWgPeersAdapter
    }

    private fun openApplicationsDialog() {
        Utilities.showToastUiCentered(
            this,
            "Add/Remove Apps - Coming Soon",
            Toast.LENGTH_SHORT
        )
        // TODO: Implement ProxyAppMappingActivity navigation
        // Similar to WgConfigDetailActivity implementation
    }

    private fun openHopDialog() {
        Utilities.showToastUiCentered(
            this,
            "Configure Hops - Coming Soon",
            Toast.LENGTH_SHORT
        )
        // TODO: Implement WgHopActivity navigation
        // Similar to WgConfigDetailActivity implementation
    }

    private fun openLogsDialog() {
        Utilities.showToastUiCentered(
            this,
            "View Logs - Coming Soon",
            Toast.LENGTH_SHORT
        )
        // TODO: Implement WgLogActivity navigation
        // Similar to WgConfigDetailActivity implementation
    }
}

