/*
 * Copyright 2024 RethinkDNS and its authors
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
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import backend.Backend
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityPingTestBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class PingTestActivity: AppCompatActivity(R.layout.activity_ping_test) {
    private val b by viewBinding(ActivityPingTestBinding::bind)

    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "PingUi"
        private const val PING_IP1 = "1.1.1.1:53"
        private const val PING_IP2 = "8.8.8.8:53"
        private const val PING_IP3 = "216.239.32.27:443"
        private const val PING_HOST1 = "cloudflare.com:443"
        private const val PING_HOST2 = "google.com:443"
        private const val PING_HOST3 = "brave.com:443"
    }

    private val proxiesStatus = mutableListOf<Boolean>()


    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        initView()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        if (!VpnController.hasTunnel()) {
            showStartVpnDialog()
            return
        }

        b.pingButton.text = b.pingButton.text.toString().uppercase()
        b.cancelButton.text = b.cancelButton.text.toString().uppercase()

        b.ipAddress1.text = PING_IP1
        b.ipAddress2.text = PING_IP2
        b.ipAddress3.setText(PING_IP3)

        b.hostAddress1.text = PING_HOST1
        b.hostAddress2.text = PING_HOST2
        b.hostAddress3.setText(PING_HOST3)
    }

    private fun showStartVpnDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.vpn_not_active_dialog_title))
        builder.setMessage(getString(R.string.vpn_not_active_dialog_desc))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.dns_info_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            finish()
        }
        builder.create().show()
    }

    private fun setupClickListeners() {
        b.pingButton.setOnClickListener {
            performPing()
        }

        b.cancelButton.setOnClickListener {
            finish()
        }
    }

    private fun performPing() {
        try {
            Logger.v(Logger.LOG_IAB, "$TAG initiating ping test")
            b.progressIp1.visibility = View.VISIBLE
            b.progressIp2.visibility = View.VISIBLE
            b.progressIp3.visibility = View.VISIBLE
            b.progressHost1.visibility = View.VISIBLE
            b.progressHost2.visibility = View.VISIBLE
            b.progressHost3.visibility = View.VISIBLE

            val ip1 = b.ipAddress1.text.toString()
            val ip2 = b.ipAddress2.text.toString()
            val ip3 = b.ipAddress3.text.toString()
            val host1 = b.hostAddress1.text.toString()
            val host2 = b.hostAddress2.text.toString()
            val host3 = b.hostAddress3.text.toString()

            io {
                val validI1 = isReachable(ip1)
                val validI2 = isReachable(ip2)
                val validI3 = isReachable(ip3)

                val validH1 = isReachable(host1)
                val validH2 = isReachable(host2)
                val validH3 = isReachable(host3)
                Logger.d(Logger.LOG_IAB, "$TAG ip1 reachable: $validI1, ip2 reachable: $validI2, ip3 reachable: $validI3")
                Logger.d(Logger.LOG_IAB, "$TAG host1 reachable: $validH1, host2 reachable: $validH2, host3 reachable: $validH3")
                uiCtx {
                    b.progressIp1.visibility = View.GONE
                    b.progressIp2.visibility = View.GONE
                    b.progressIp3.visibility = View.GONE
                    b.progressHost1.visibility = View.GONE
                    b.progressHost2.visibility = View.GONE
                    b.progressHost3.visibility = View.GONE

                    b.statusIp1.visibility = View.VISIBLE
                    b.statusIp2.visibility = View.VISIBLE
                    b.statusIp3.visibility = View.VISIBLE
                    b.statusHost1.visibility = View.VISIBLE
                    b.statusHost2.visibility = View.VISIBLE
                    b.statusHost3.visibility = View.VISIBLE

                    b.statusIp1.setImageDrawable(getImgRes(validI1))
                    b.statusIp2.setImageDrawable(getImgRes(validI2))
                    b.statusIp3.setImageDrawable(getImgRes(validI3))
                    b.statusHost1.setImageDrawable(getImgRes(validH1))
                    b.statusHost2.setImageDrawable(getImgRes(validH2))
                    b.statusHost3.setImageDrawable(getImgRes(validH3))
                }

                val strength = calculateStrength(ip3)
                Logger.d(Logger.LOG_IAB, "$TAG strength: $strength for $ip3")
                uiCtx {
                    setStrengthLevel(strength)
                }
            }
        } catch (e: Exception) {
            Logger.e(Logger.LOG_IAB, "$TAG err isReachable: ${e.message}", e)
        }

    }

    private fun getImgRes(probeResult: Boolean): Drawable? {
        val failureDrawable = ContextCompat.getDrawable(this, R.drawable.ic_cross)
        val successDrawable = ContextCompat.getDrawable(this, R.drawable.ic_tick)

        return if (probeResult) {
            successDrawable
        } else {
            failureDrawable
        }
    }

    private fun setStrengthLevel(strength: Int) {
        // Ensure the strength is between 1 and 5
        val validStrength = when {
            strength < 1 -> 1
            strength > 5 -> 5
            else -> strength
        }

        b.strengthLayout.visibility = View.VISIBLE
        // Update the progress of the ProgressBar
        b.strengthIndicator.max = 5
        b.strengthIndicator.progress = validStrength
    }

    private suspend fun isReachable(csv: String): Boolean {
        val (wg, pr, se, w64, exit) = if (proxiesStatus.isEmpty()) {
            getProxiesStatus(csv)
        } else {
            proxiesStatus
        }
        Logger.d(Logger.LOG_IAB, "$TAG ip $csv reachable: $wg, $pr, $se, $w64, $exit")
        Logger.i(Logger.LOG_IAB, "$TAG ip $csv reachable: ${wg || pr || se || w64 || exit}")
        return wg || se || w64 || exit
    }

    private suspend fun calculateStrength(csv: String): Int {
        val (wg, proton, se, w64, exit) = if (proxiesStatus.isEmpty()) {
            getProxiesStatus(csv)
        } else {
            proxiesStatus
        }

        // calculate strength based on the above boolean values
        // 1 - 5
        var strength = 0
        if (wg) strength++
        if (proton) strength++
        if (se) strength++
        if (w64) strength++
        if (exit) strength++

        Logger.i(Logger.LOG_IAB, "$TAG strength: $strength ($wg, $se, $w64, $exit)")
        return strength
    }

    private suspend fun getProxiesStatus(csv: String): List<Boolean> {
        if (proxiesStatus.isNotEmpty()) return proxiesStatus

        val wg = VpnController.isProxyReachable(Backend.RpnWg, csv)
        val proton = VpnController.isProxyReachable(Backend.RpnPro, csv)
        val se = VpnController.isProxyReachable(Backend.RpnSE, csv)
        val w64 = VpnController.isProxyReachable(Backend.Rpn64, csv)
        val exit = VpnController.isProxyReachable(Backend.Exit, csv)
        Logger.d(Logger.LOG_IAB, "$TAG proxies reachable: $wg, $proton, $se, $w64, $exit")
        return proxiesStatus.apply {
            clear()
            add(wg)
            add(proton)
            add(se)
            add(w64)
            add(exit)
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
