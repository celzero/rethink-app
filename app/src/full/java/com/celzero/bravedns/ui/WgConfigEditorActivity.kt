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
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.databinding.ActivityWgConfigEditorBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_WIREGUARD
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UiUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.WgIncludeAppsViewModel
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.bravedns.wireguard.util.ErrorMessages
import ipn.Ipn
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class WgConfigEditorActivity : AppCompatActivity(R.layout.activity_wg_config_editor) {
    private val b by viewBinding(ActivityWgConfigEditorBinding::bind)
    private val persistentState by inject<PersistentState>()

    private val mappingViewModel: WgIncludeAppsViewModel by viewModel()
    private var wgConfig: Config? = null
    private var wgInterface: WgInterface? = null
    private var configId: Int = -1

    companion object {
        const val INTENT_EXTRA_WG_ID = "WIREGUARD_TUNNEL_ID"
        private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
        private const val DEFAULT_MTU = "1280"
        private const val DEFAULT_LISTEN_PORT = "51820"
        private const val DEFAULT_DNS_SERVER = "1.1.1.1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        configId = intent.getIntExtra(INTENT_EXTRA_WG_ID, -1)
        Log.d(LOG_TAG_WIREGUARD, "WgTunnelEditorActivity - tunnelId : $configId")
        init()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        handleAppsCount()
        wgConfig = WireguardManager.getConfigById(configId)
        wgInterface = wgConfig?.getInterface()
        Log.d(LOG_TAG_WIREGUARD, "WgTunnelEditorActivity - wgConfig : $wgConfig")

        b.interfaceNameText.setText(wgConfig?.getName())
        b.privateKeyText.setText(wgInterface?.getKeyPair()?.getPrivateKey()?.base64())
        b.publicKeyText.setText(wgInterface?.getKeyPair()?.getPublicKey()?.base64())
        if (wgInterface?.dnsServers?.isEmpty() != true) {
            b.dnsServersText.setText(
                wgInterface?.dnsServers?.joinToString { it.hostAddress?.toString() ?: "" }
            )
        }
        if (wgInterface?.getAddresses()?.isEmpty() != true) {
            b.addressesLabelText.setText(
                wgInterface?.getAddresses()?.joinToString { it.toString() }
            )
        }
        if (wgInterface?.listenPort?.isPresent == true && wgInterface?.listenPort?.get() != 1) {
            b.listenPortText.setText(wgInterface?.listenPort?.get().toString())
        }
        if (wgInterface?.mtu?.isPresent == true) {
            b.mtuText.setText(wgInterface?.mtu?.get().toString())
        }
    }

    private fun handleAppsCount() {
        if (configId == -1) return

        mappingViewModel.getAppCountById(configId).observe(this) {
            b.mapApplications.text = getString(R.string.firewall_card_status_active, it.toString())
        }
    }

    private fun setupClickListeners() {
        b.privateKeyTextLayout.setEndIconOnClickListener {
            val key = Ipn.newPrivateKey()
            val privateKey = key.base64()
            val publicKey = key.mult().base64()
            b.privateKeyText.setText(privateKey.toString())
            b.publicKeyText.setText(publicKey.toString())
        }

        b.mapApplications.setOnClickListener { openAppsDialog() }

        b.saveTunnel.setOnClickListener {
            if (addWgInterface() != null) {
                Toast.makeText(
                        this,
                        getString(R.string.config_add_success_toast),
                        Toast.LENGTH_LONG
                    )
                    .show()
                finish()
            } else {
                // no-op, addWgInterface() will show the error message
            }
        }

        b.publicKeyLabelLayout.setOnClickListener {
            clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.publicKeyText.setOnClickListener {
            clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun addWgInterface(): Config? {
        val name = b.interfaceNameText.text.toString()
        val addresses = b.addressesLabelText.text.toString()
        val mtu = b.mtuText.text.toString().ifEmpty { DEFAULT_MTU }
        val listenPort = b.listenPortText.text.toString().ifEmpty { DEFAULT_LISTEN_PORT }
        val dnsServers = b.dnsServersText.text.toString().ifEmpty { DEFAULT_DNS_SERVER }
        val privateKey = b.privateKeyText.text.toString()

        try {
            // parse the wg interface to check for errors
            val wgInterface =
                WgInterface.Builder()
                    .parsePrivateKey(privateKey)
                    .parseAddresses(addresses)
                    .parseListenPort(listenPort)
                    .parseDnsServers(dnsServers)
                    .parseMtu(mtu)
                    .build()
            wgConfig = WireguardManager.addOrUpdateInterface(configId, name, wgInterface)
            return wgConfig
        } catch (e: Throwable) {
            val error = ErrorMessages[this, e]
            Log.e(LOG_TAG_WIREGUARD, "Exception while parsing wg interface: $error", e)
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return null
        }
    }

    private fun openAppsDialog() {
        if (isConfigSaved()) {
            showIncludeAppsDialog()
        } else {
            addWgInterface()
        }
    }

    private fun isConfigSaved(): Boolean {
        return wgConfig?.getName()?.isNotEmpty() == true
    }

    private fun showIncludeAppsDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val appsAdapter = WgIncludeAppsAdapter(this, configId)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
        val includeAppsDialog =
            WgIncludeAppsDialog(this, appsAdapter, mappingViewModel, themeId, configId)
        includeAppsDialog.setCanceledOnTouchOutside(false)
        includeAppsDialog.show()
    }
}
