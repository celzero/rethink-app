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

import Logger.LOG_IAB
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import backend.Backend
import backend.Rpn
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityTroubleshootBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.RPN_AMZ_ID
import com.celzero.bravedns.rpnproxy.RpnProxyManager.WARP_ID
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class TroubleshootActivity : AppCompatActivity(R.layout.activity_troubleshoot) {
    private val b by viewBinding(ActivityTroubleshootBinding::bind)
    private val persistentState by inject<PersistentState>()

    private lateinit var animation: Animation

    companion object {
        private const val IP_PORTS = "57.144.145.33:443,157.240.251.6:443"

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        addAnimation()
        initUsingText()
        io {
            uiCtx {
                b.overallProgressBar.visibility = View.VISIBLE
            }
            initWarpStatus()
            initSEStatus()
            initExit64Status()
            initExitStatus()
            initAmzStatus()
            initProtonStatus()
            uiCtx {
                b.overallProgressBar.visibility = View.INVISIBLE
            }
        }
    }

    private fun initUsingText() {
        when (VpnController.getAutoUsageId()) {
            Backend.RpnSE -> b.useSeTv.text = "Using SE"
            Backend.RpnWg -> b.useWarpTv.text = "Using Warp"
            Backend.RpnAmz -> b.useAmzTv.text = "Using Amnezia"
            Backend.Exit -> b.useExitTv.text = "Using Exit"
            Backend.Rpn64 -> b.useX464Tv.text = "Using Rpn64"
            Backend.RpnPro -> b.useProtonTv.text = "Using Proton"
        }
    }

    private fun setupClickListeners() {
        b.warpRefreshIv.setOnClickListener {
            io {
                uiCtx {
                    b.warpRefreshIv.isEnabled = false
                    b.warpRefreshIv.animation = animation
                    b.warpRefreshIv.startAnimation(animation)
                }
                recreateAndAddWarp()
                uiCtx {
                    b.warpRefreshIv.clearAnimation()
                    b.warpRefreshIv.isEnabled = true
                }
            }
        }

        b.amzRefreshIv.setOnClickListener {
            io {
                uiCtx {
                    b.amzRefreshIv.isEnabled = false
                    b.amzRefreshIv.animation = animation
                    b.amzRefreshIv.startAnimation(animation)
                }
                recreateAndAddAmz()
                uiCtx {
                    b.amzRefreshIv.clearAnimation()
                    b.amzRefreshIv.isEnabled = true
                }
            }
        }

        b.protonRefreshIv.setOnClickListener {
            io {
                uiCtx {
                    b.protonRefreshIv.isEnabled = false
                    b.protonRefreshIv.animation = animation
                    b.protonRefreshIv.startAnimation(animation)
                }
                recreateAndAddProton()
                uiCtx {
                    b.protonRefreshIv.clearAnimation()
                    b.protonRefreshIv.isEnabled = true
                }
            }
        }

        b.seRefreshIv.setOnClickListener {
            io {
                uiCtx {
                    b.seRefreshIv.isEnabled = false
                    b.seRefreshIv.animation = animation
                    b.seRefreshIv.startAnimation(animation)
                }
                reRegisterSE()
                uiCtx {
                    b.seRefreshIv.clearAnimation()
                    b.seRefreshIv.isEnabled = true
                }
            }
        }

        b.x464RefreshIv.setOnClickListener {
            // no-op for now
        }

        b.exitRefreshIv.setOnClickListener {
            // no-op for now
        }

        b.useSeTv.setOnClickListener {
            if (VpnController.getAutoUsageId() == Backend.RpnSE) {
                VpnController.setAutoUsageId(Backend.Auto)
                Utilities.showToastUiCentered(this, "Auto selected", Toast.LENGTH_SHORT)
                Logger.v(LOG_IAB, "Auto selected")
                b.useSeTv.text = "Use only SE"
                return@setOnClickListener
            }
            VpnController.setAutoUsageId(Backend.RpnSE)
            Utilities.showToastUiCentered(this, "SE selected", Toast.LENGTH_SHORT)
            Logger.v(LOG_IAB, "SE selected")
            b.useSeTv.text = "Using SE"
        }

        b.useWarpTv.setOnClickListener {
            if (VpnController.getAutoUsageId() == Backend.RpnWg) {
                VpnController.setAutoUsageId(Backend.Auto)
                Utilities.showToastUiCentered(this, "Auto selected", Toast.LENGTH_SHORT)
                Logger.v(LOG_IAB, "Auto selected")
                b.useWarpTv.text = "Use only Warp"
                return@setOnClickListener
            }
            VpnController.setAutoUsageId(Backend.RpnWg)
            Utilities.showToastUiCentered(this, "Warp selected", Toast.LENGTH_SHORT)
            Logger.v(LOG_IAB, "Warp selected")
            b.useWarpTv.text = "Using Warp"
        }

        b.useAmzTv.setOnClickListener {
            if (VpnController.getAutoUsageId() == Backend.RpnAmz) {
                VpnController.setAutoUsageId(Backend.Auto)
                Utilities.showToastUiCentered(this, "Auto selected", Toast.LENGTH_SHORT)
                Logger.v(LOG_IAB, "Auto selected")
                b.useAmzTv.text = "Use only Amnezia"
                return@setOnClickListener
            }
            VpnController.setAutoUsageId(Backend.RpnAmz)
            Utilities.showToastUiCentered(this, "Amnezia selected", Toast.LENGTH_SHORT)
            Logger.v(LOG_IAB, "Amnezia(amz) selected")
            b.useAmzTv.text = "Using Amnezia"
        }

        b.useExitTv.setOnClickListener {
            if (VpnController.getAutoUsageId() == Backend.Exit) {
                VpnController.setAutoUsageId(Backend.Auto)
                Utilities.showToastUiCentered(this, "Auto selected", Toast.LENGTH_SHORT)
                Logger.v(LOG_IAB, "Auto selected")
                b.useExitTv.text = "Use only Exit"
                return@setOnClickListener
            }
            VpnController.setAutoUsageId(Backend.Exit)
            Utilities.showToastUiCentered(this, "Exit selected", Toast.LENGTH_SHORT)
            Logger.v(LOG_IAB, "Exit selected")
            b.useExitTv.text = "Using Exit"
        }

        b.useX464Tv.setOnClickListener {
            if (VpnController.getAutoUsageId() == Backend.Rpn64) {
                VpnController.setAutoUsageId(Backend.Auto)
                Utilities.showToastUiCentered(this, "Auto selected", Toast.LENGTH_SHORT)
                Logger.v(LOG_IAB, "Auto selected")
                b.useX464Tv.text = "Use only Rpn64"
                return@setOnClickListener
            }
            VpnController.setAutoUsageId(Backend.Rpn64)
            Utilities.showToastUiCentered(this, "Rpn64 selected", Toast.LENGTH_SHORT)
            Logger.v(LOG_IAB, "Rpn64 selected")
            b.useX464Tv.text = "Using Rpn64"
        }

        b.useProtonTv.setOnClickListener {
            if (VpnController.getAutoUsageId() == Backend.RpnPro) {
                VpnController.setAutoUsageId(Backend.Auto)
                Utilities.showToastUiCentered(this, "Auto selected", Toast.LENGTH_SHORT)
                Logger.v(LOG_IAB, "Auto selected")
                b.useProtonTv.text = "Use only Proton"
                return@setOnClickListener
            }
            VpnController.setAutoUsageId(Backend.RpnPro)
            Utilities.showToastUiCentered(this, "Proton selected", Toast.LENGTH_SHORT)
            Logger.v(LOG_IAB, "Proton selected")
            b.useProtonTv.text = "Using Proton"
        }
    }

    private suspend fun initWarpStatus() {
        uiCtx { b.warpProgressBar.visibility = View.VISIBLE }
        VpnController.testWarp().let {
            uiCtx {
                b.warpProgressBar.visibility = View.GONE
                b.warpStatusTv.text = "${if (it) "Active" else "Inactive"}"
                b.warpStatusTv.setTextColor(this, it)
            }
        }
    }

    private suspend fun initAmzStatus() {
        uiCtx { b.amzProgressBar.visibility = View.VISIBLE }
        VpnController.testAmz().let {
            uiCtx {
                b.amzProgressBar.visibility = View.GONE
                b.amzStatusTv.text = "${if (it) "Active" else "Inactive"}"
                b.amzStatusTv.setTextColor(this, it)
            }
        }
    }

    private suspend fun initProtonStatus() {
        uiCtx { b.protonProgressBar.visibility = View.VISIBLE }
        VpnController.testProton().let {
            uiCtx {
                b.protonProgressBar.visibility = View.GONE
                b.protonStatusTv.text = "${if (it) "Active" else "Inactive"}"
                b.protonStatusTv.setTextColor(this, it)
            }
        }
    }

    private fun addAnimation() {
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

    private fun AppCompatTextView.setTextColor(context: Context, success: Boolean) {
        this.setTextColor(
            if (success) UIUtils.fetchColor(context, R.attr.chipTextPositive)
            else UIUtils.fetchColor(context, R.attr.chipTextNegative)
        )
    }

    private suspend fun initSEStatus() {
        uiCtx { b.seProgressBar.visibility = View.VISIBLE }
        VpnController.testSE().let {
            uiCtx {
                b.seProgressBar.visibility = View.GONE
                b.seStatusTv.text = "${if (it) "Active" else "Inactive"}"
                b.seStatusTv.setTextColor(this, it)
            }
        }
    }

    private suspend fun initExit64Status() {
        uiCtx { b.x464ProgressBar.visibility = View.VISIBLE }
        VpnController.isProxyReachable(Backend.Rpn64, IP_PORTS).let {
            uiCtx {
                b.x464ProgressBar.visibility = View.GONE
                b.x464StatusTv.text = "${if (it) "Active" else "Inactive"}"
                b.x464StatusTv.setTextColor(this, it)
            }
        }
    }

    private suspend fun initExitStatus() {
        uiCtx { b.exitProgressBar.visibility = View.VISIBLE }
        VpnController.isProxyReachable(Backend.Exit, IP_PORTS).let {
            uiCtx {
                b.exitProgressBar.visibility = View.GONE
                b.exitStatusTv.text = "${if (it) "Active" else "Inactive"}"
                b.exitStatusTv.setTextColor(this, it)
            }
        }
    }

    private suspend fun recreateAndAddWarp() {
        createWarpConfig()
        addWarpToTunnel()
        initWarpStatus()
    }

    private suspend fun recreateAndAddAmz() {
        createAmzConfig()
        addAmzToTunnel()
        initAmzStatus()
    }

    private suspend fun recreateAndAddProton() {
        // create a new proton config
        val config = RpnProxyManager.getNewProtonConfig()
        if (config == null) {
            Logger.e(LOG_IAB, "err creating proton config")
            showConfigCreationError("Error creating Proton config")
            return
        }
        Logger.i(LOG_IAB, "proton config created")
        // convert the json to a format
        // val config = WireguardManager.getNewProtonConfig(json)
        Logger.i(LOG_IAB, "proton config created: ${config.regionalWgConfs.size}")
    }

    private suspend fun reRegisterSE() {
        // add the SE to the tunnel
        val isRegistered = VpnController.registerSEToTunnel()
        if (!isRegistered) {
            Logger.e(LOG_IAB, "err registering SE to tunnel")
            showConfigCreationError("Error registering SE to tunnel")
        }
        Logger.i(LOG_IAB, "SE registered to tunnel")
        initSEStatus()
    }

    private suspend fun handleWarp(): Boolean {
        // see if the warp conf is available, if not create a new one
        val cf = RpnProxyManager.getWarpConfig()
        if (cf == null) {
            return createWarpConfig()
        } else {
            Logger.i(LOG_IAB, "warp config already exists")
        }
        return true
    }

    private suspend fun handleAmz(): Boolean {
        // see if the amz conf is available, if not create a new one
        val cf = RpnProxyManager.getAmneziaConfig()
        if (cf == null) {
            return createAmzConfig()
        } else {
            Logger.i(LOG_IAB, "amz config already exists")
        }
        return true
    }

    private suspend fun createWarpConfig(): Boolean {
        // create a new warp config
        val config = RpnProxyManager.getNewWarpConfig(true, WARP_ID, 0)
        if (config == null) {
            Logger.e(LOG_IAB, "err creating warp config")
            showConfigCreationError(getString(R.string.new_warp_error_toast))
            return false
        }
        return true
    }

    private suspend fun createAmzConfig(): Boolean {
        // create a new amz config
        val config = RpnProxyManager.getNewAmneziaConfig(RPN_AMZ_ID)
        if (config == null) {
            Logger.e(LOG_IAB, "err creating amz config")
            showConfigCreationError("Error creating Amnezia config")
            return false
        }
        return true
    }

    private suspend fun addWarpToTunnel() {
        if (!handleWarp()) {
            Logger.e(LOG_IAB, "err handling warp")
            return
        }
        val cf = WireguardManager.getConfigFilesById(WARP_ID)
        if (cf == null) {
            Logger.e(LOG_IAB, "err adding warp to tunnel")
            showConfigCreationError(getString(R.string.new_warp_error_toast))
            return
        }
        WireguardManager.enableConfig(cf)
        Logger.i(LOG_IAB, "warp added to tunnel")
    }

    private suspend fun addAmzToTunnel() {
        if (!handleAmz()) {
            Logger.e(LOG_IAB, "err handling amz")
            return
        }
        val cf = WireguardManager.getConfigFilesById(RPN_AMZ_ID)
        if (cf == null) {
            Logger.e(LOG_IAB, "err adding amz to tunnel")
            showConfigCreationError("Error adding Amnezia to tunnel")
            return
        }
        WireguardManager.enableConfig(cf)
        Logger.i(LOG_IAB, "amz added to tunnel")
    }

    private suspend fun showConfigCreationError(msg: String) {
        uiCtx { Utilities.showToastUiCentered(this, msg, Toast.LENGTH_LONG) }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
