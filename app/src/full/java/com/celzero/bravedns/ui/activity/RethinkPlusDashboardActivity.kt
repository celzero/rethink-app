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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import backend.Backend
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityRethinkPlusDashboardBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RethinkPlusDashboardActivity : AppCompatActivity(R.layout.activity_rethink_plus_dashboard) {
    private val b by viewBinding(ActivityRethinkPlusDashboardBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private lateinit var animation: Animation

    private val options = listOf("Option 1", "Option 2", "Option 3", "Option 4", "Option 5")

    companion object {
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val TAG = "RPNDashboardActivity"
    }

    private var warpProps: RpnProxyManager.RpnProps? = null
    private var seProps: RpnProxyManager.RpnProps? = null
    private var exit64Props: RpnProxyManager.RpnProps? = null
    private var protonProps: RpnProxyManager.RpnProps? = null
    private var amzProps: RpnProxyManager.RpnProps? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        theme.applyStyle(R.style.OptOutEdgeToEdgeEnforcement, false)
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
        addAnimation()
        handleRpnMode()
        addProxiesToUi()
    }

    private fun handleRpnMode() {
        val mode = RpnProxyManager.rpnMode()
        when (mode) {
            RpnProxyManager.RpnMode.ANTI_CENSORSHIP -> {
                b.rsAntiCensorshipRadio.isChecked = true
                b.rsHideIpRadio.isChecked = false
            }

            RpnProxyManager.RpnMode.HIDE_IP -> {
                b.rsAntiCensorshipRadio.isChecked = false
                b.rsHideIpRadio.isChecked = true
            }
        }
    }

    private fun addProxiesToUi() {
        ui {
            for (option in options) {
                val rowView =
                    layoutInflater.inflate(
                        R.layout.item_rpn_proxy_dashboard_stats,
                        b.proxyContainer,
                        false
                    )
                val iv = rowView.findViewById<AppCompatImageView>(R.id.icon)
                val title = rowView.findViewById<AppCompatTextView>(R.id.title)
                val refreshIv = rowView.findViewById<AppCompatImageView>(R.id.refresh)
                val infoIv = rowView.findViewById<AppCompatImageView>(R.id.info)
                val statusTv = rowView.findViewById<AppCompatTextView>(R.id.status)

                b.proxyContainer.addView(rowView)
                val id = options.indexOf(option)
                updateProxiesUi(
                    id,
                    iv,
                    title,
                    infoIv,
                    statusTv
                )

                val type = getType(id)
                if (type == null) {
                    Logger.w(LOG_TAG_UI, "$TAG type is null")
                    return@ui
                }

                refreshIv.setOnClickListener {
                    handleRefreshUi(type, refreshIv)
                    ui {
                        updateProxiesUi(id, iv, title, infoIv, statusTv)
                    }
                }

            }
            keepUpdatingProxiesUi()
        }
    }

    private suspend fun keepUpdatingProxiesUi() {
        // keep updating the UI every 5 seconds
        while (true) {
            for (i in 0 until options.size) {
                val iv = b.proxyContainer.getChildAt(i).findViewById<AppCompatImageView>(R.id.icon)
                val title = b.proxyContainer.getChildAt(i).findViewById<AppCompatTextView>(R.id.title)
                val infoIv = b.proxyContainer.getChildAt(i).findViewById<AppCompatImageView>(R.id.info)
                val statusTv = b.proxyContainer.getChildAt(i).findViewById<AppCompatTextView>(R.id.status)

                updateProxiesUi(i, iv, title, infoIv, statusTv)
                Logger.vv(LOG_TAG_UI, "$TAG updating proxies UI for ${options[i]}")
            }
            Logger.v(LOG_TAG_UI, "$TAG updating proxies UI every 5 seconds")
            delay(5000)
        }
    }

    private suspend fun updateProxiesUi(
        id: Int,
        iv: AppCompatImageView,
        title: AppCompatTextView,
        infoIv: AppCompatImageView,
        statusTv: AppCompatTextView
    ) {
        val type = getType(id) ?: return // should never happen

        title.text = options[id]
        iv.setImageDrawable(ContextCompat.getDrawable(this, getDrawable(type)))

        var res: Pair<RpnProxyManager.RpnProps?, String?>? = null
        ioCtx {
            res = VpnController.getRpnProps(type)
        }

        val props = res?.first
        // in case of both props and error message are null, show vpn not connected
        val errMsg = res?.second ?: getString(R.string.notif_channel_vpn_failure)

        Logger.vv(LOG_TAG_UI, "$TAG updateProxiesUi $type props: $props")
        if (props == null) {
            infoIv.visibility = View.GONE
            statusTv.text = errMsg
            return
        }

        setProps(type, props)
        setStatus(props.status, statusTv)

        infoIv.setOnClickListener {
            showInfoDialog(type, props)
        }
    }

    private fun setProps(type: RpnProxyManager.RpnType, props: RpnProxyManager.RpnProps) {
        when (type) {
            RpnProxyManager.RpnType.WARP -> warpProps = props
            RpnProxyManager.RpnType.AMZ -> amzProps = props
            RpnProxyManager.RpnType.PROTON -> protonProps = props
            RpnProxyManager.RpnType.SE -> seProps = props
            RpnProxyManager.RpnType.EXIT_64 -> exit64Props = props
            RpnProxyManager.RpnType.EXIT -> {} // not used in the UI
        }
    }

    private fun getType(id: Int): RpnProxyManager.RpnType? {
        return when (id) {
            0 -> RpnProxyManager.RpnType.WARP
            1 -> RpnProxyManager.RpnType.AMZ
            2 -> RpnProxyManager.RpnType.PROTON
            3 -> RpnProxyManager.RpnType.SE
            4 -> RpnProxyManager.RpnType.EXIT_64
            5 -> RpnProxyManager.RpnType.EXIT // not used in the UI
            else -> null
        }
    }

    private fun getDrawable(type: RpnProxyManager.RpnType?): Int {
        return when (type) {
            RpnProxyManager.RpnType.WARP -> R.drawable.ic_wireguard_icon
            RpnProxyManager.RpnType.AMZ -> R.drawable.ic_wireguard_icon
            RpnProxyManager.RpnType.PROTON -> R.drawable.ic_wireguard_icon
            RpnProxyManager.RpnType.SE -> R.drawable.ic_wireguard_icon
            RpnProxyManager.RpnType.EXIT_64 -> R.drawable.ic_wireguard_icon
            RpnProxyManager.RpnType.EXIT -> R.drawable.ic_wireguard_icon // not used in the UI
            null -> R.drawable.ic_wireguard_icon
        }
    }

    private fun handleRefreshUi(type: RpnProxyManager.RpnType, refreshIv: AppCompatImageView) {
        Logger.v(LOG_TAG_UI, "$TAG ${type.name} refresh clicked")
        io {
            uiCtx {
                refreshIv.isEnabled = false
                refreshIv.animation = animation
                refreshIv.startAnimation(animation)
            }
            handleRefresh(type)
            uiCtx {
                refreshIv.clearAnimation()
                refreshIv.isEnabled = true
            }
        }
    }

    private suspend fun handleRefresh(type: RpnProxyManager.RpnType) {
        when (type) {
            RpnProxyManager.RpnType.WARP ->  recreateAndAddWarp()
            RpnProxyManager.RpnType.AMZ -> recreateAndAddAmz()
            RpnProxyManager.RpnType.PROTON -> recreateAndAddProton()
            RpnProxyManager.RpnType.SE -> reRegisterSE()
            else -> {} // no refresh needed for exit64 and exit
        }
    }

    private fun setupClickListeners() {

        b.rsAntiCensorshipRl.setOnClickListener {
            val checked = b.rsAntiCensorshipRadio.isChecked
            if (!checked) {
                b.rsAntiCensorshipRadio.isChecked = true
            }
            handleAntiCensorshipMode(checked)
        }

        b.rsAntiCensorshipRadio.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            handleAntiCensorshipMode(checked)
        }

        b.rsHideIpRl.setOnClickListener {
            val checked = b.rsHideIpRadio.isChecked
            if (!checked) {
                b.rsHideIpRadio.isChecked = true
            }
            handleHideIpMode(checked)
        }

        b.rsHideIpRadio.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            handleHideIpMode(checked)
        }

        b.reportIssueRl.setOnClickListener {
            collectDataForTroubleshoot()
        }

        b.pingTestRl.setOnClickListener {
            val intent = Intent(this, PingTestActivity::class.java)
            startActivity(intent)
        }
    }

    private fun handleAntiCensorshipMode(checked: Boolean) {
        Logger.v(LOG_TAG_UI, "$TAG Anti-censorship mode selected? $checked")
        if (!checked) return

        b.rsHideIpRadio.isChecked = false
        persistentState.rpnMode = RpnProxyManager.RpnMode.ANTI_CENSORSHIP.id
        Logger.i(LOG_TAG_UI, "$TAG Anti-censorship selected, state: ${persistentState.rpnMode}")
    }

    private fun handleHideIpMode(checked: Boolean) {
        Logger.v(LOG_TAG_UI, "$TAG Hide IP mode selected? $checked")
        if (!checked) return

        b.rsAntiCensorshipRadio.isChecked = false
        persistentState.rpnMode = RpnProxyManager.RpnMode.HIDE_IP.id
        Logger.i(LOG_TAG_UI, "$TAG Hide IP selected, state: ${persistentState.rpnMode}")
    }

    private fun showInfoDialog(type: RpnProxyManager.RpnType,prop: RpnProxyManager.RpnProps?) {
        io {
            if (prop == null) {
                Logger.e(LOG_TAG_UI, "$TAG rpnProps is null")
                uiCtx {
                    Utilities.showToastUiCentered(this, "Error fetching RpnProps", Toast.LENGTH_SHORT)
                }
                return@io
            }
            val genStats = VpnController.vpnStats()
            uiCtx {
                val title = type.name.uppercase()
                val msg = prop.toString() + "\n\n" + genStats.toString()
                showTroubleshootDialog(title, msg)
            }
        }
    }

    private fun setStatus(status: Long?, txtView: AppCompatTextView) {
        if (status == null) {
            txtView.text = getString(R.string.lbl_disabled)
            txtView.setTextColor(this, false)
        } else {
            val statusTxt = UIUtils.getProxyStatusStringRes(status)
            txtView.text = getString(statusTxt).replaceFirstChar(Char::uppercase)
            val isPositive = status == Backend.TUP || status == Backend.TOK
            txtView.setTextColor(this, isPositive)
        }
    }

    private suspend fun recreateAndAddWarp() {
        createWarpConfig()
    }

    private suspend fun recreateAndAddAmz() {
        createAmzConfig()
    }

    private suspend fun recreateAndAddProton() {
        // create a new proton config
        val config = RpnProxyManager.getNewProtonConfig()
        if (config == null) {
            Logger.e(LOG_TAG_UI, "$TAG err creating proton config")
            showConfigCreationError("Error creating Proton config")
            return
        }
        Logger.i(LOG_TAG_UI, "$TAG proton config created")
    }

    private suspend fun reRegisterSE() {
        // add the SE to the tunnel
        val isRegistered = VpnController.registerSEToTunnel()
        if (!isRegistered) {
            Logger.e(LOG_TAG_UI, "$TAG err registering SE to tunnel")
            showConfigCreationError("Error registering SE to tunnel")
        }
        Logger.i(LOG_TAG_UI, "$TAG SE registered to tunnel")
    }

    private suspend fun createWarpConfig(): Boolean {
        // create a new warp config
        val config = RpnProxyManager.getNewWarpConfig()
        if (config == null) {
            Logger.e(LOG_TAG_UI, "$TAG err creating warp config")
            showConfigCreationError(getString(R.string.new_warp_error_toast))
            return false
        }
        return true
    }

    private suspend fun createAmzConfig(): Boolean {
        // create a new amz config
        val config = RpnProxyManager.getNewAmzConfig()
        if (config == null) {
            Logger.e(LOG_TAG_UI, "$TAG err creating amz config")
            showConfigCreationError("Error creating Amnezia config")
            return false
        }
        return true
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

    private fun showTroubleshootDialog(title: String, msg: String) {
        io {
            uiCtx {
                val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
                val builder =
                    MaterialAlertDialogBuilder(this).setView(dialogBinding.root)
                val lp = WindowManager.LayoutParams()
                val dialog = builder.create()
                dialog.show()
                lp.copyFrom(dialog.window?.attributes)
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT

                dialog.setCancelable(true)
                dialog.window?.attributes = lp

                val heading = dialogBinding.infoRulesDialogRulesTitle
                val okBtn = dialogBinding.infoRulesDialogCancelImg
                val descText = dialogBinding.infoRulesDialogRulesDesc
                dialogBinding.infoRulesDialogRulesIcon.visibility = View.GONE

                heading.text = title
                heading.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, R.drawable.ic_rethink_plus),
                    null,
                    null,
                    null
                )

                descText.movementMethod = LinkMovementMethod.getInstance()
                descText.text = msg

                okBtn.setOnClickListener { dialog.dismiss() }

                dialog.show()
            }
        }
    }

    private fun collectDataForTroubleshoot() {
        val title = "Proxy Stats"
        val msg =
            "WARP \n" + warpProps?.toString() + "\n\n" + "Amz \n" + amzProps?.toString() + "\n\n" + "SE \n" + seProps?.toString() + "\n\n" + "Proton \n" + protonProps?.toString() + "\n\n" + "Exit64 \n" + exit64Props?.toString()
        showTroubleshootDialog(title, msg)
        // get system info
        // get dns info
        // get protocol info
        // get builder info
        // get rpn info
        // val appMode = appConfig.getBraveMode()
        // dns : appConfig.getDnsType().name
        // selected ip version : appConfig.getInternetProtocol().name
        // appConfig.getProxyProvider(), appConfig.getProxyType()
        // dump shared pref?
        // get info from vpn adapter
        // get rpn info
    }

    private fun AppCompatTextView.setTextColor(context: Context, success: Boolean) {
        this.setTextColor(
            if (success) UIUtils.fetchColor(context, R.attr.chipTextPositive)
            else UIUtils.fetchColor(context, R.attr.chipTextNegative)
        )
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

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
