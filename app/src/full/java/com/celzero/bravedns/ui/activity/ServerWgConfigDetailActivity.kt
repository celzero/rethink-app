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

import Logger.LOG_TAG_UI
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
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.databinding.ActivityServerWgDetailBinding
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.service.CountryConfigManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_RPN_WIN
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.firestack.backend.Proxy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.getValue
import kotlin.math.abs

/**
 * Activity for viewing and editing server-provided WireGuard configurations
 * Allows editing MTU, listen port (from dropdown), and peer persistent keepalive
 * No delete or full edit capabilities
 */
class ServerWgConfigDetailActivity : AppCompatActivity(R.layout.activity_server_wg_detail) {
    private val b by viewBinding(ActivityServerWgDetailBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private var serverWgPeersAdapter: ServerWgPeersAdapter? = null
    private var layoutManager: LinearLayoutManager? = null

    private var configId: Int = WireguardManager.INVALID_CONF_ID
    private var countryCode: String = ""
    private var proxy: Proxy? = null
    private var countryConfig: CountryConfig? = null

    // Available listen ports
    private val availableListenPorts = listOf(80, 443, 8080, 9110)

    // SSID permission callback for country configs
    private val ssidPermissionCallback = object : com.celzero.bravedns.util.SsidPermissionManager.PermissionCallback {
        override fun onPermissionsGranted() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions granted for country: $countryCode")
            lifecycleScope.launch {
                refreshSsidSection()
            }
        }

        override fun onPermissionsDenied() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions denied for country: $countryCode")
            lifecycleScope.launch {
                // Reset the switch since permissions are required
                b.ssidCheck.isChecked = false
                refreshSsidSection()
            }
        }

        override fun onPermissionsRationale() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions rationale for country: $countryCode")
            showSsidPermissionExplanationDialog()
        }
    }

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
        countryCode = intent.getStringExtra(INTENT_EXTRA_COUNTRY_CODE) ?: ""

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

        // Setup smooth collapsing animation for hero content
        setupCollapsingAnimation()
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
        io {
            proxy = VpnController.getWinByKey(countryCode)
            val appCount = ProxyManager.getAppsCountForProxy(proxy?.id().tos() ?: "")
            uiCtx {
                if (countryCode.isEmpty() || proxy == null) {
                    showInvalidConfigDialog()
                    return@uiCtx
                }
                b.appsLabel.text = "Apps($appCount)"
                prefillConfig(proxy)
                setupListenPortSpinner()
            }
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
        val dialog = builder.create()
        dialog.show()
    }

    private fun prefillConfig(proxy: Proxy?) {
        if (proxy == null) return

        b.configNameText.visibility = View.VISIBLE
        b.configNameText.text = proxy.addr.tos()
        b.configIdText.text =
            getString(R.string.single_argument_parenthesis, proxy.id().toString())

        b.statusText.text = getString(R.string.lbl_server_config_readonly)

        val router = proxy.router()

        // Pre-fill MTU (read-only)
        b.mtuText.text = router.mtu().toString()
        b.mtuText.visibility = View.VISIBLE

        // Load switch states
        loadConfigSettings()

        // setPeersAdapter()
    }

    private fun loadConfigSettings() {
        val cc = countryCode
        if (cc.isEmpty()) {
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
                    b.catchAllCheck.isChecked = config.catchAll
                    b.useMobileCheck.isChecked = config.mobileOnly
                    b.ssidCheck.isChecked = config.ssidBased
                    b.otherSettingsCard.visibility = View.VISIBLE
                    b.mobileSsidSettingsCard.visibility = View.VISIBLE
                } else {
                    // Create default config if it doesn't exist
                    // should not happen normally
                    CountryConfigManager.upsertConfig(
                        CountryConfig(
                            id = cc,                 // or "WIN-$cc" if you prefer
                            cc = cc,
                            name = cc,               // you can replace with a nicer label later
                            address = "",
                            city = "",
                            key = cc,                // can be adjusted once you have a proper key
                            load = 0,
                            link = 0,
                            count = 0,
                            isActive = true,
                            catchAll = false,
                            lockdown = false,
                            mobileOnly = false,
                            ssidBased = false,
                            priority = 0,
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
        /*if (wgInterface?.listenPort?.isPresent == true) {
            val currentPort = wgInterface?.listenPort?.get()
            val position = availableListenPorts.indexOf(currentPort)
            if (position >= 0) {
                b.listenPortSpinner.setSelection(position)
            }
        }*/

        // Make read-only for server configs
        b.listenPortSpinner.isEnabled = false
    }

    private fun setupClickListeners() {
        // Applications button
        b.applicationsBtn.setOnClickListener {
            openAppsDialog()
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

        // Setup SSID section with premium dialog and permission handling
        setupSsidSection(cc)

        // Layout click listeners for better UX
        b.catchAllRl.setOnClickListener { b.catchAllCheck.performClick() }
        b.useMobileRl.setOnClickListener { b.useMobileCheck.performClick() }
        b.ssidFilterRl.setOnClickListener { b.ssidCheck.performClick() }
    }

    /*private fun setPeersAdapter() {
        layoutManager = LinearLayoutManager(this)
        b.peersList.layoutManager = layoutManager
        serverWgPeersAdapter = ServerWgPeersAdapter(this, peers) { position, isExpanded ->
            // Handle peer expansion if needed
        }
        b.peersList.adapter = serverWgPeersAdapter
    }*/

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

    private fun openAppsDialog() {
        if (countryCode.isEmpty() || proxy == null) {
            Logger.e(LOG_TAG_UI, "win-openAppsDialog: countryCode is null")
            return
        }

        val proxyId = proxy?.id().tos() ?: (ID_RPN_WIN + countryCode)
        val proxyName = countryCode
        val appsAdapter = WgIncludeAppsAdapter(this, this, proxyId, proxyName)
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

    private fun setupCollapsingAnimation() {
        b.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            val percentage = abs(verticalOffset).toFloat() / totalScrollRange.toFloat()

            // Fade out hero content as toolbar collapses
            b.configNameText.alpha = 1f - percentage
            b.statusText.alpha = 1f - percentage
            b.configIdText.alpha = 1f - percentage

            // Scale down hero content slightly for premium effect
            val scale = 1f - (percentage * 0.1f) // Scale from 1.0 to 0.9
            b.configNameText.scaleX = scale
            b.configNameText.scaleY = scale
        }
    }

    // ===== SSID Section Implementation =====

    private fun setupSsidSection(cc: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            countryConfig = com.celzero.bravedns.rpnproxy.RpnProxyManager.getCountryConfig(cc)
            withContext(Dispatchers.Main) {
                setupSsidSectionUI(countryConfig)
            }
        }
    }

    private fun setupSsidSectionUI(config: CountryConfig?) {
        val sw = b.ssidCheck

        if (config == null) {
            sw.isEnabled = false
            Logger.w(LOG_TAG_UI, "setupSsidSection: config is null for $countryCode")
            return
        }

        // Check if device supports required features
        if (!com.celzero.bravedns.util.SsidPermissionManager.isDeviceSupported(this)) {
            sw.isEnabled = false
            b.ssidFilterRl.visibility = View.GONE
            Logger.w(LOG_TAG_UI, "setupSsidSection: device not supported for SSID feature")
            return
        }

        // Always keep the switch enabled
        sw.isEnabled = true
        b.ssidFilterRl.visibility = View.VISIBLE

        // Check permissions and location services
        val hasPermissions = com.celzero.bravedns.util.SsidPermissionManager.hasRequiredPermissions(this)
        val isLocationEnabled = com.celzero.bravedns.util.SsidPermissionManager.isLocationEnabled(this)

        val enabled = config.ssidBased
        val ssidItems = com.celzero.bravedns.data.SsidItem.parseStorageList(config.ssids)
        sw.isChecked = enabled

        Logger.d(LOG_TAG_UI, "SSID for $countryCode - permissions: $hasPermissions, location: $isLocationEnabled, ssidBased: $enabled, items: ${ssidItems.size}")

        sw.setOnCheckedChangeListener { _, isChecked ->
            // Check current permissions and location status dynamically
            val currentHasPermissions = com.celzero.bravedns.util.SsidPermissionManager.hasRequiredPermissions(this)
            val currentLocationEnabled = com.celzero.bravedns.util.SsidPermissionManager.isLocationEnabled(this)

            // Check permissions before enabling SSID feature
            if (isChecked && !currentHasPermissions) {
                com.celzero.bravedns.util.SsidPermissionManager.checkAndRequestPermissions(this, ssidPermissionCallback)
                Logger.d(LOG_TAG_UI, "SSID permissions not granted, requesting...")
                return@setOnCheckedChangeListener
            }

            // Check if location services are enabled
            if (isChecked && !currentLocationEnabled) {
                showLocationEnableDialog()
                Logger.d(LOG_TAG_UI, "Location services not enabled, prompting user...")
                return@setOnCheckedChangeListener
            }

            // If we reach here, either we're disabling or we have all required permissions
            lifecycleScope.launch(Dispatchers.IO) {
                com.celzero.bravedns.rpnproxy.RpnProxyManager.updateSsidBased(countryCode, isChecked)
                withContext(Dispatchers.Main) {
                    if (isChecked) {
                        openSsidDialog()
                    }
                }
            }

            Logger.i(LOG_TAG_UI, "SSID feature ${if (isChecked) "enabled" else "disabled"} for country: $countryCode")
        }
    }

    private fun refreshSsidSection() {
        lifecycleScope.launch(Dispatchers.IO) {
            countryConfig = com.celzero.bravedns.rpnproxy.RpnProxyManager.getCountryConfig(countryCode)
            withContext(Dispatchers.Main) {
                setupSsidSectionUI(countryConfig)
            }
        }
    }

    private fun openSsidDialog() {
        if (countryCode.isEmpty() || countryConfig == null) {
            Logger.e(LOG_TAG_UI, "openSsidDialog: countryCode or config is null")
            return
        }

        val currentSsids = countryConfig?.ssids ?: ""
        val countryName = countryConfig?.countryName ?: countryCode

        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }

        val ssidDialog = com.celzero.bravedns.ui.dialog.CountrySsidDialog(
            this,
            themeId,
            countryCode,
            countryName,
            currentSsids
        ) { newSsids ->
            // Save callback - update the SSID configuration
            lifecycleScope.launch(Dispatchers.IO) {
                com.celzero.bravedns.rpnproxy.RpnProxyManager.updateSsids(countryCode, newSsids)
                withContext(Dispatchers.Main) {
                    refreshSsidSection()
                    Utilities.showToastUiCentered(
                        this@ServerWgConfigDetailActivity,
                        "SSID settings saved for $countryName",
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        ssidDialog.setCanceledOnTouchOutside(false)
        ssidDialog.show()
        ssidDialog.setOnDismissListener {
            // Refresh SSID section after dialog dismisses
            refreshSsidSection()
        }
    }

    private fun showLocationEnableDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.ssid_location_error))
        builder.setMessage(getString(R.string.location_enable_explanation, getString(R.string.lbl_ssids)))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.ssid_location_error_action)) { dialog, _ ->
            com.celzero.bravedns.util.SsidPermissionManager.requestLocationEnable(this)
            dialog.dismiss()
            Logger.vv(LOG_TAG_UI, "Prompted user to enable location services for country: $countryCode")
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // Reset the SSID switch since location is required
            b.ssidCheck.isChecked = false
            lifecycleScope.launch(Dispatchers.IO) {
                com.celzero.bravedns.rpnproxy.RpnProxyManager.updateSsidBased(countryCode, false)
            }
        }
        builder.create().show()
    }

    private fun showSsidPermissionExplanationDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.ssid_permission_error_action))
        builder.setMessage(getString(R.string.ssid_permission_explanation, getString(R.string.lbl_ssids)))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.ssid_permission_error_action)) { dialog, _ ->
            com.celzero.bravedns.util.SsidPermissionManager.requestSsidPermissions(this)
            dialog.dismiss()
            Logger.vv(LOG_TAG_UI, "Showing SSID permission rationale dialog for country: $countryCode")
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // Reset the SSID switch since permissions are required
            b.ssidCheck.isChecked = false
            lifecycleScope.launch(Dispatchers.IO) {
                com.celzero.bravedns.rpnproxy.RpnProxyManager.updateSsidBased(countryCode, false)
            }
        }
        builder.create().show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        com.celzero.bravedns.util.SsidPermissionManager.handlePermissionResult(
            requestCode,
            permissions,
            grantResults,
            ssidPermissionCallback
        )
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}

