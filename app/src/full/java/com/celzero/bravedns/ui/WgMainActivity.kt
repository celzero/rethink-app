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
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgConfigAdapter
import com.celzero.bravedns.databinding.ActivityWireguardMainBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.QrCodeFromFileScanner
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.TunnelImporter
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.WgConfigViewModel
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

    private var wgConfigAdapter: WgConfigAdapter? = null
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
                        Log.i(LoggerConstants.LOG_TAG_WIREGUARD, "result: $result, data: $data")
                        if (result != null) {
                            withContext(Dispatchers.Main) {
                                Log.i(LoggerConstants.LOG_TAG_WIREGUARD, "result: ${result.text}")
                                TunnelImporter.importTunnel(result.text) {
                                    Utilities.showToastUiCentered(
                                        this@WgMainActivity,
                                        it.toString(),
                                        Toast.LENGTH_LONG
                                    )
                                    Log.e(LoggerConstants.LOG_TAG_WIREGUARD, it.toString())
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
                            Log.e(LoggerConstants.LOG_TAG_WIREGUARD, message)
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
                        Log.e(LoggerConstants.LOG_TAG_WIREGUARD, e.message, e)
                    }
                } else {
                    TunnelImporter.importTunnel(contentResolver, data) {
                        Log.e(LoggerConstants.LOG_TAG_WIREGUARD, it.toString())
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
                        Log.e(LoggerConstants.LOG_TAG_WIREGUARD, it.toString())
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
        collapseFab()
        setupInterfaceList()
        setupClickListeners()

        onBackPressedDispatcher.addCallback(this /* lifecycle owner */) {
            if (b.createFab.visibility == View.VISIBLE) {
                collapseFab()
            } else {
                finish()
            }
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupInterfaceList() {
        val layoutManager = LinearLayoutManager(this)
        b.wgInterfaceList.layoutManager = layoutManager

        wgConfigAdapter = WgConfigAdapter(this)
        wgConfigViewModel.interfaces.observe(this) { wgConfigAdapter?.submitData(lifecycle, it) }
        b.wgInterfaceList.adapter = wgConfigAdapter
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
    }

    private fun openTunnelEditorActivity() {
        val intent = Intent(this, WgConfigEditorActivity::class.java)
        startActivity(intent)
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
