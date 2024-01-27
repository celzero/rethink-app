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

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.OneWgConfigAdapter
import com.celzero.bravedns.adapter.WgConfigAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityWireguardMainBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.QrCodeFromFileScanner
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.TunnelImporter
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.WgConfigViewModel
import com.google.android.material.button.MaterialButton
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class WgMainActivity : AppCompatActivity(R.layout.activity_wireguard_main) {
    private val b by viewBinding(ActivityWireguardMainBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private var wgConfigAdapter: WgConfigAdapter? = null
    private var oneWgConfigAdapter: OneWgConfigAdapter? = null
    private val wgConfigViewModel: WgConfigViewModel by viewModel()

    private lateinit var animation: Animation

    companion object {
        private const val IMPORT_LAUNCH_INPUT = "*/*"

        private const val REFRESH_TIMEOUT: Long = 4000

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
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
                        Log.i(LoggerConstants.LOG_TAG_PROXY, "result: $result, data: $data")
                        if (result != null) {
                            withContext(Dispatchers.Main) {
                                Log.i(LoggerConstants.LOG_TAG_PROXY, "result: ${result.text}")
                                TunnelImporter.importTunnel(result.text) {
                                    Utilities.showToastUiCentered(
                                        this@WgMainActivity,
                                        it.toString(),
                                        Toast.LENGTH_LONG
                                    )
                                    Log.e(LoggerConstants.LOG_TAG_PROXY, it.toString())
                                }
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
                            Log.e(LoggerConstants.LOG_TAG_PROXY, message)
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
                        Log.e(LoggerConstants.LOG_TAG_PROXY, e.message, e)
                    }
                } else {
                    TunnelImporter.importTunnel(contentResolver, data) {
                        Log.e(LoggerConstants.LOG_TAG_PROXY, it.toString())
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
                        Log.e(LoggerConstants.LOG_TAG_PROXY, it.toString())
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
    }

    private fun init() {
        b.settingsNetwork.text = getString(R.string.lbl_wireguard).lowercase()
        setAdapter()
        initAnimation()
        collapseFab()
        observeConfig()
        observeDnsName()
        setupClickListeners()

        onBackPressedDispatcher.addCallback(this /* lifecycle owner */) {
            if (b.createFab.visibility == View.VISIBLE) {
                collapseFab()
            } else {
                finish()
            }
        }
    }

    private fun setAdapter() {
        val type = WireguardManager.oneWireGuardEnabled()

        if (type) {
            selectToggleBtnUi(b.oneWgToggleBtn)
            unselectToggleBtnUi(b.wgGeneralToggleBtn)
            b.wgGeneralInterfaceList.visibility = View.GONE
            b.wgGeneralInterfaceList.adapter = null
            b.oneWgInterfaceList.visibility = View.VISIBLE
            setOneWgAdapter()
        } else {
            selectToggleBtnUi(b.wgGeneralToggleBtn)
            unselectToggleBtnUi(b.oneWgToggleBtn)
            b.wgGeneralInterfaceList.visibility = View.VISIBLE
            b.wgGeneralInterfaceList.adapter = wgConfigAdapter
            b.oneWgInterfaceList.visibility = View.GONE
            b.oneWgInterfaceList.adapter = null
            setGeneralAdapter()
        }
    }

    private fun setGeneralAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.wgGeneralInterfaceList.layoutManager = layoutManager

        wgConfigAdapter = WgConfigAdapter(this)
        wgConfigViewModel.interfaces.observe(this) { wgConfigAdapter?.submitData(lifecycle, it) }
        b.wgGeneralInterfaceList.adapter = wgConfigAdapter
    }

    private fun setOneWgAdapter() {
        val layoutManager = LinearLayoutManager(this)
        b.oneWgInterfaceList.layoutManager = layoutManager

        oneWgConfigAdapter = OneWgConfigAdapter(this)
        wgConfigViewModel.interfaces.observe(this) { oneWgConfigAdapter?.submitData(lifecycle, it) }
        b.oneWgInterfaceList.adapter = oneWgConfigAdapter
    }

    private fun selectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(this, R.color.accentGood))
    }

    private fun unselectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(this, R.color.defaultToggleBtnBg))
    }

    private fun initAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    override fun onResume() {
        super.onResume()
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
        b.wgRefresh.visibility = View.VISIBLE
        b.wgGeneralInterfaceList.visibility = View.VISIBLE
        b.oneWgInterfaceList.visibility = View.VISIBLE
        b.wgWireguardDisclaimer.visibility = View.VISIBLE
        b.wgGeneralInterfaceList.visibility = View.VISIBLE
    }

    private fun hideWgViews() {
        b.wgGeneralToggleBtn.visibility = View.GONE
        b.oneWgToggleBtn.visibility = View.GONE
        b.wgRefresh.visibility = View.GONE
        b.wgGeneralInterfaceList.visibility = View.GONE
        b.oneWgInterfaceList.visibility = View.GONE
        b.wgWireguardDisclaimer.visibility = View.GONE
    }

    private fun observeDnsName() {
        appConfig.getConnectedDnsObservable().observe(this) {
            b.wgWireguardDisclaimer.text = getString(R.string.wireguard_disclaimer, it)
        }
    }

    private fun setupClickListeners() {
        b.wgAddFab.setOnClickListener {
            if (b.createFab.visibility == View.VISIBLE) {
                collapseFab()
            } else {
                expendFab()
            }
        }
        b.importFab.setOnClickListener {
            tunnelFileImportResultLauncher.launch(IMPORT_LAUNCH_INPUT)
        }
        b.qrCodeFab.setOnClickListener {
            qrImportResultLauncher.launch(
                ScanOptions()
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)
                    .setPrompt(resources.getString(R.string.lbl_qr_code))
            )
        }
        b.createFab.setOnClickListener { openTunnelEditorActivity() }
        b.wgRefresh.setOnClickListener { refresh() }
        b.wgGeneralToggleBtn.setOnClickListener {
            if (WireguardManager.oneWireGuardEnabled()) {
                Toast.makeText(
                    this,
                    getString(R.string.one_wg_error_toast),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            selectToggleBtnUi(b.wgGeneralToggleBtn)
            unselectToggleBtnUi(b.oneWgToggleBtn)
            b.oneWgInterfaceList.visibility = View.GONE
            b.oneWgInterfaceList.adapter = null
            b.wgGeneralInterfaceList.visibility = View.VISIBLE
            if (wgConfigAdapter == null) setGeneralAdapter()
            else b.wgGeneralInterfaceList.adapter = wgConfigAdapter
        }
        b.oneWgToggleBtn.setOnClickListener {
            selectToggleBtnUi(b.oneWgToggleBtn)
            unselectToggleBtnUi(b.wgGeneralToggleBtn)
            b.wgGeneralInterfaceList.visibility = View.GONE
            b.wgGeneralInterfaceList.adapter = null
            b.oneWgInterfaceList.visibility = View.VISIBLE
            if (oneWgConfigAdapter == null) setOneWgAdapter()
            else b.oneWgInterfaceList.adapter = oneWgConfigAdapter
        }
    }

    private fun openTunnelEditorActivity() {
        val intent = Intent(this, WgConfigEditorActivity::class.java)
        startActivity(intent)
    }

    private fun refresh() {
        b.wgRefresh.isEnabled = false
        b.wgRefresh.animation = animation
        b.wgRefresh.startAnimation(animation)
        VpnController.refreshWireGuardConfig()
        Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
            b.wgRefresh.isEnabled = true
            b.wgRefresh.clearAnimation()
            Utilities.showToastUiCentered(
                this,
                getString(R.string.wireguard_refresh_toast),
                Toast.LENGTH_SHORT
            )
        }
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
}
