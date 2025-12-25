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
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.OneWgConfigAdapter
import com.celzero.bravedns.adapter.WgConfigAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivityWireguardMainBinding
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.QrCodeFromFileScanner
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.TunnelImporter
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.WgConfigViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class WgMainActivity :
    AppCompatActivity(R.layout.activity_wireguard_main), OneWgConfigAdapter.DnsStatusListener {
    private val b by viewBinding(ActivityWireguardMainBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()

    private var wgConfigAdapter: WgConfigAdapter? = null
    private var oneWgConfigAdapter: OneWgConfigAdapter? = null
    private val wgConfigViewModel: WgConfigViewModel by viewModel()

    companion object {
        private const val IMPORT_LAUNCH_INPUT = "*/*"
    }

    private val tunnelFileImportResultLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
            if (data == null) return@registerForActivityResult
            val contentResolver = contentResolver ?: return@registerForActivityResult
            lifecycleScope.launch {
                if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                    try {
                        val qrCodeFromFileScanner =
                            QrCodeFromFileScanner(contentResolver, QRCodeReader())
                        val result = qrCodeFromFileScanner.scan(data)
                        Logger.i(LOG_TAG_PROXY, "result: $result, data: $data")
                        if (result != null) {
                            withContext(Dispatchers.Main) {
                                Logger.i(LOG_TAG_PROXY, "result: ${result.text}")
                                TunnelImporter.importTunnel(result.text) {
                                    Utilities.showToastUiCentered(
                                        this@WgMainActivity,
                                        it.toString(),
                                        Toast.LENGTH_LONG
                                    )
                                    Logger.e(LOG_TAG_PROXY, it.toString())
                                }
                                logEvent("Wireguard import", "imported from file")
                            }
                        } else {
                            val message =
                                resources.getString(
                                    R.string.generic_error,
                                    getString(R.string.invalid_file_error)
                                )
                            Utilities.showToastUiCentered(
                                this@WgMainActivity,
                                message,
                                Toast.LENGTH_LONG
                            )
                            Logger.e(LOG_TAG_PROXY, message)
                        }
                    } catch (e: Exception) {
                        val message =
                            resources.getString(
                                R.string.generic_error,
                                getString(R.string.invalid_file_error)
                            )
                        Utilities.showToastUiCentered(
                            this@WgMainActivity,
                            message,
                            Toast.LENGTH_LONG
                        )
                        Logger.e(LOG_TAG_PROXY, e.message ?: "err tun import", e)
                    }
                } else {
                    TunnelImporter.importTunnel(contentResolver, data) {
                        Logger.e(LOG_TAG_PROXY, it.toString())
                        Utilities.showToastUiCentered(
                            this@WgMainActivity,
                            it.toString(),
                            Toast.LENGTH_LONG
                        )
                    }
                }
            }
        }

    private val qrImportResultLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val qrCode = result.contents
            if (qrCode != null) {
                lifecycleScope.launch {
                    TunnelImporter.importTunnel(qrCode) {
                        Utilities.showToastUiCentered(
                            this@WgMainActivity,
                            it.toString(),
                            Toast.LENGTH_LONG
                        )
                        Logger.e(LOG_TAG_PROXY, it.toString())
                        logEvent("Wireguard import", "imported via QR scanner")
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        init()

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        onBackPressedDispatcher.addCallback(
            this /* lifecycle owner */,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (b.createFab.isVisible) {
                        collapseFab()
                    } else {
                        finish()
                    }
                    return
                }
            }
        )
    }

    private fun init() {
        setAdapter()
        collapseFab()
        observeConfig()
        observeDnsName()
        setupClickListeners()
    }

    private fun setAdapter() {
        setOneWgAdapter()
        setGeneralAdapter()

        if (WireguardManager.isAnyWgActive() && !WireguardManager.oneWireGuardEnabled()) {
            showGeneralToggle()
        } else {
            showOneWgToggle()
        }
    }

    private fun showOneWgToggle() {
        if (this.isDestroyed) return

        selectToggleBtnUi(b.oneWgToggleBtn)
        unselectToggleBtnUi(b.wgGeneralToggleBtn)
        b.wgGeneralInterfaceList.visibility = View.GONE
        b.oneWgInterfaceList.visibility = View.VISIBLE
        if (oneWgConfigAdapter == null) setOneWgAdapter()
        else b.oneWgInterfaceList.adapter = oneWgConfigAdapter
    }

    private fun showGeneralToggle() {
        if (this.isDestroyed) return

        selectToggleBtnUi(b.wgGeneralToggleBtn)
        unselectToggleBtnUi(b.oneWgToggleBtn)
        b.oneWgInterfaceList.visibility = View.GONE
        b.wgGeneralInterfaceList.visibility = View.VISIBLE
        if (wgConfigAdapter == null) setGeneralAdapter()
        else b.wgGeneralInterfaceList.adapter = wgConfigAdapter
    }

    private fun setGeneralAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.wgGeneralInterfaceList.layoutManager = layoutManager

        wgConfigAdapter = WgConfigAdapter(this, this, persistentState.splitDns, eventLogger)
        wgConfigViewModel.interfaces.observe(this) { wgConfigAdapter?.submitData(lifecycle, it) }
        b.wgGeneralInterfaceList.adapter = wgConfigAdapter
    }

    private fun setOneWgAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.oneWgInterfaceList.layoutManager = layoutManager

        oneWgConfigAdapter = OneWgConfigAdapter(this, this, eventLogger)
        wgConfigViewModel.interfaces.observe(this) { oneWgConfigAdapter?.submitData(lifecycle, it) }
        b.oneWgInterfaceList.adapter = oneWgConfigAdapter
    }

    private fun selectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(this, R.color.accentGood))
        b.setTextColor(UIUtils.fetchColor(this, R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(this, R.color.defaultToggleBtnBg))
        b.setTextColor(UIUtils.fetchColor(this, R.attr.primaryTextColor))
    }

    override fun onResume() {
        super.onResume()
        oneWgConfigAdapter?.notifyDataSetChanged()
        wgConfigAdapter?.notifyDataSetChanged()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun observeConfig() {
        wgConfigViewModel.configCount().observe(this) {
            if (it == 0) {
                showEmptyView()
                hideWgViews()
            } else {
                hideEmptyView()
                showWgViews()
            }
        }
    }

    private fun showEmptyView() {
        b.wgEmptyView.visibility = View.VISIBLE
    }

    private fun hideEmptyView() {
        b.wgEmptyView.visibility = View.GONE
    }

    private fun showWgViews() {
        b.wgGeneralToggleBtn.visibility = View.VISIBLE
        b.oneWgToggleBtn.visibility = View.VISIBLE
        b.wgWireguardDisclaimer.visibility = View.VISIBLE
    }

    private fun hideWgViews() {
        b.wgGeneralToggleBtn.visibility = View.GONE
        b.oneWgToggleBtn.visibility = View.GONE
        b.wgWireguardDisclaimer.visibility = View.GONE
    }

    private fun observeDnsName() {
        val activeConfigs = WireguardManager.getActiveConfigs()
        if (WireguardManager.oneWireGuardEnabled()) {
            val dnsName = activeConfigs.firstOrNull()?.getName() ?: return
            b.wgWireguardDisclaimer.text = getString(R.string.wireguard_disclaimer, dnsName)
            // remove the observer if any config is active
            appConfig.getConnectedDnsObservable().removeObservers(this)
        } else {
            appConfig.getConnectedDnsObservable().observe(this) { dns ->
                var dnsNames: String = dns.ifEmpty { "" }
                if (persistentState.splitDns) {
                    if (activeConfigs.isNotEmpty()) {
                        dnsNames += ", "
                    }
                    dnsNames += activeConfigs.joinToString(",") { it.getName() }
                }
                // add fallback to the list as it can be used to bypass trusted ip/domains/apps
                if (persistentState.useFallbackDnsToBypass) {
                    dnsNames += ", " + getString(R.string.lbl_fallback)
                }
                b.wgWireguardDisclaimer.text = getString(R.string.wireguard_disclaimer, dnsNames)
            }
        }
    }

    private fun setupClickListeners() {
        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.wgAddFab.bringToFront()
        b.wgAddFab.setOnClickListener {
            if (b.createFab.isVisible) {
                collapseFab()
            } else {
                expendFab()
            }
        }
        b.importFab.setOnClickListener {
            try {
                tunnelFileImportResultLauncher.launch(IMPORT_LAUNCH_INPUT)
            } catch (e: ActivityNotFoundException) {
                Logger.e(LOG_TAG_PROXY, "err; anf; while launching file import: ${e.message}", e)
                Utilities.showToastUiCentered(this, getString(R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "err while launching file import: ${e.message}", e)
                Utilities.showToastUiCentered(this, getString(R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
            }
        }
        b.qrCodeFab.setOnClickListener {
            try {
                qrImportResultLauncher.launch(
                    ScanOptions()
                        .setOrientationLocked(false)
                        .setBeepEnabled(false)
                        .setPrompt(resources.getString(R.string.lbl_qr_code))
                )
            } catch (e: ActivityNotFoundException) {
                Logger.e(LOG_TAG_PROXY, "err; anf while launching QR scanner: ${e.message}", e)
                Utilities.showToastUiCentered(this, getString(R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "err while launching QR scanner: ${e.message}", e)
                Utilities.showToastUiCentered(this, getString(R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
            }
        }
        b.createFab.setOnClickListener { openTunnelEditorActivity() }

        b.wgGeneralToggleBtn.setOnClickListener {
            if (WireguardManager.oneWireGuardEnabled()) {
                showDisableDialog(isOneWgToggle = false)
                return@setOnClickListener
            }
            showGeneralToggle()
        }
        b.oneWgToggleBtn.setOnClickListener {
            val activeConfigs = WireguardManager.getActiveConfigs()
            val isAnyConfigActive = activeConfigs.isNotEmpty()
            val isOneWgEnabled = WireguardManager.oneWireGuardEnabled()
            if (isAnyConfigActive && !isOneWgEnabled) {
                showDisableDialog(isOneWgToggle = true)
                return@setOnClickListener
            }
            showOneWgToggle()
        }
    }

    private fun openTunnelEditorActivity() {
        val intent = Intent(this, WgConfigEditorActivity::class.java)
        startActivity(intent)
    }

    private fun showDisableDialog(isOneWgToggle: Boolean) {
        // show alert dialog with don't show again toggle in it
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.wireguard_disable_title))
            .setMessage(getString(R.string.wireguard_disable_message))
            .setPositiveButton(getString(R.string.always_on_dialog_positive)) { _, _ ->
                // disable all configs
                io {
                    if (WireguardManager.canDisableAllActiveConfigs()) {
                        WireguardManager.disableAllActiveConfigs()
                        logEvent(
                            "Wireguard disable",
                            "all configs from toggle switch; isOneWgToggle: $isOneWgToggle"
                        )
                        uiCtx {
                            this.observeDnsName()
                            if (isOneWgToggle) {
                                showOneWgToggle()
                            } else {
                                showGeneralToggle()
                            }
                        }
                    } else {
                        val configs = WireguardManager.getActiveCatchAllConfig()
                        if (configs.isNotEmpty()) {
                            uiCtx {
                                Utilities.showToastUiCentered(
                                    this,
                                    getString(R.string.wireguard_disable_failure),
                                    Toast.LENGTH_LONG
                                )
                            }
                        } else {
                            uiCtx {
                                Utilities.showToastUiCentered(
                                    this,
                                    getString(R.string.wireguard_disable_failure_relay),
                                    Toast.LENGTH_LONG
                                )
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
                // do nothing
            }
            .create()
            .show()
    }

    private fun expendFab() {
        b.createFab.visibility = View.VISIBLE
        b.importFab.visibility = View.VISIBLE
        b.qrCodeFab.visibility = View.VISIBLE
        b.createFab.animate().translationY(-resources.getDimension(R.dimen.standard_55))
        b.importFab.animate().translationY(-resources.getDimension(R.dimen.standard_105))
        b.qrCodeFab.animate().translationY(-resources.getDimension(R.dimen.standard_155))
    }

    private fun collapseFab() {
        b.createFab.animate().translationY(resources.getDimension(R.dimen.standard_0))
        b.importFab.animate().translationY(resources.getDimension(R.dimen.standard_0))
        b.qrCodeFab.animate().translationY(resources.getDimension(R.dimen.standard_0))
        b.createFab.visibility = View.GONE
        b.importFab.visibility = View.GONE
        b.qrCodeFab.visibility = View.GONE
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    override fun onDnsStatusChanged() {
        observeDnsName()
    }
}
