/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.bottomsheet

import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.ui.compose.networksheets.showOrbotInfoDialog
import com.celzero.bravedns.ui.compose.networksheets.OrbotSheetState
import com.celzero.bravedns.ui.compose.networksheets.OrbotSheetContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.DnsDetailActivity
import com.celzero.bravedns.ui.activity.WgIncludeAppsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * One touch Orbot Integration. Bottom sheet dialog fragment shows UI that enables One touch
 * Integration from the settings page.
 */
class OrbotBottomSheet : BaseBottomSheetDialogFragment() {
    private var _binding: OrbotSheetState? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val eventLogger by inject<EventLogger>()
    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = OrbotSheetState(inflater.context)
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { OrbotSheetContent(b) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        _binding?.saveChecks(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        b.restoreChecks(savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        observeApps()
        setupClickListeners()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        io {
            val isOrbotDns = appConfig.isOrbotDns()
            uiCtx { updateUi(isOrbotDns) }
        }
        handleHttpUI()
    }

    private fun observeApps() {
        mappingViewModel.getAppCountById(ProxyManager.ID_ORBOT_BASE).observe(viewLifecycleOwner) {
            b.includeApplications.text = getString(R.string.add_remove_apps, it.toString())
        }
    }

    private fun handleHttpUI() {
        // http proxy is only supported on Android Q and above
        if (!isAtleastQ()) {
            b.bsOrbotHttpRl.visibility = View.GONE
            b.bsOrbotBothRl.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {

        b.bsOrbotApp.setOnClickListener { orbotHelper.openOrbotApp() }

        b.bsOrbotRadioNone.setOnCheckedChangeListener(null)
        b.bsOrbotRadioNone.setOnClickListener {
            if (b.bsOrbotRadioNone.isChecked) {
                handleOrbotStop()
            }
        }

        b.bsOrbotNoneRl.setOnClickListener {
            if (!b.bsOrbotRadioNone.isChecked) {
                handleOrbotStop()
            }
        }

        b.bsOrbotRadioSocks5.setOnCheckedChangeListener(null)
        b.bsOrbotRadioSocks5.setOnClickListener {
            io {
                val isSelected = isAnyAppSelected()
                uiCtx {
                    if (!isSelected) {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            getString(R.string.orbot_no_app_toast),
                            Toast.LENGTH_SHORT
                        )
                        b.bsOrbotRadioNone.isChecked = true
                        b.bsOrbotRadioSocks5.isChecked = false
                        return@uiCtx
                    }
                    if (!b.bsOrbotRadioSocks5.isSelected) {
                        persistentState.orbotConnectionStatus.postValue(true)
                        enableSocks5Orbot()
                    }
                }
            }
        }

        b.bsSocks5OrbotRl.setOnClickListener {
            io {
                val isSelected = isAnyAppSelected()
                uiCtx {
                    if (!isSelected) {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            getString(R.string.orbot_no_app_toast),
                            Toast.LENGTH_SHORT
                        )
                        b.bsOrbotRadioNone.isChecked = true
                        b.bsOrbotRadioSocks5.isChecked = false
                        return@uiCtx
                    }
                    if (!b.bsOrbotRadioSocks5.isChecked) {
                        b.bsOrbotRadioSocks5.isChecked = true
                        persistentState.orbotConnectionStatus.postValue(true)
                        enableSocks5Orbot()
                    }
                }
            }
        }

        b.bsOrbotRadioHttp.setOnCheckedChangeListener(null)
        b.bsOrbotRadioHttp.setOnClickListener {
            io {
                val isSelected = isAnyAppSelected()
                uiCtx {
                    if (!isSelected) {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            getString(R.string.orbot_no_app_toast),
                            Toast.LENGTH_SHORT
                        )
                        b.bsOrbotRadioNone.isChecked = true
                        b.bsOrbotRadioHttp.isChecked = false
                        return@uiCtx
                    }
                    if (!b.bsOrbotRadioHttp.isSelected) {
                        persistentState.orbotConnectionStatus.postValue(true)
                        enableHttpOrbot()
                    }
                }
            }
        }

        b.bsOrbotHttpRl.setOnClickListener {
            io {
                val isSelected = isAnyAppSelected()
                uiCtx {
                    if (!isSelected) {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            getString(R.string.orbot_no_app_toast),
                            Toast.LENGTH_SHORT
                        )
                        b.bsOrbotRadioNone.isChecked = true
                        b.bsOrbotRadioHttp.isChecked = false
                        return@uiCtx
                    }
                    if (!b.bsOrbotRadioHttp.isChecked) {
                        persistentState.orbotConnectionStatus.postValue(true)
                        b.bsOrbotRadioHttp.isChecked = true
                        enableHttpOrbot()
                    }
                }
            }
        }

        b.bsOrbotRadioBoth.setOnCheckedChangeListener(null)
        b.bsOrbotRadioBoth.setOnClickListener {
            io {
                val isSelected = isAnyAppSelected()
                uiCtx {
                    if (!isSelected) {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            getString(R.string.orbot_no_app_toast),
                            Toast.LENGTH_SHORT
                        )
                        b.bsOrbotRadioNone.isChecked = true
                        b.bsOrbotRadioBoth.isChecked = false
                        return@uiCtx
                    }
                    if (!b.bsOrbotRadioBoth.isSelected) {
                        persistentState.orbotConnectionStatus.postValue(true)
                        enableSocks5HttpOrbot()
                    }
                }
            }
        }

        b.bsOrbotBothRl.setOnClickListener {
            io {
                val isSelected = isAnyAppSelected()
                uiCtx {
                    if (!isSelected) {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            getString(R.string.orbot_no_app_toast),
                            Toast.LENGTH_SHORT
                        )
                        b.bsOrbotRadioNone.isChecked = true
                        b.bsOrbotRadioBoth.isChecked = false
                        return@uiCtx
                    }
                    if (!b.bsOrbotRadioBoth.isChecked) {
                        persistentState.orbotConnectionStatus.postValue(true)
                        b.bsOrbotRadioBoth.isChecked = true
                        enableSocks5HttpOrbot()
                    }
                }
            }
        }

        b.orbotInfoIcon.setOnClickListener { showDialogForInfo() }

        // Livedata value which will have the data whether the Orbot connection is initiated
        // If initiated, show loading animation.
        // else - allow user to select Orbot options
        persistentState.orbotConnectionStatus.observe(viewLifecycleOwner) {
            if (it) {
                b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
                enableLoading()
            } else {
                disableLoading()
                io {
                    val isOrbotDns = appConfig.isOrbotDns()
                    uiCtx { updateUi(isOrbotDns) }
                }
            }
        }

        b.includeApplications.setOnClickListener { openAppsDialog() }
    }

    private suspend fun isAnyAppSelected(): Boolean {
        return ProxyManager.isAnyAppSelected(ProxyManager.ID_ORBOT_BASE)
    }

    private fun handleOrbotStop() {
        stopOrbot()
        io {
            val isOrbotDns = appConfig.isOrbotDns()
            uiCtx { showStopOrbotDialog(isOrbotDns) }
            logEvent(
                "Orbot Stopped",
                "User stopped Orbot from Orbot Bottom Sheet"
            )
        }
    }

    private fun updateUi(isOrbotDns: Boolean) {
        when (OrbotHelper.selectedProxyType) {
            AppConfig.ProxyType.SOCKS5.name -> {
                b.bsOrbotRadioSocks5.isChecked = true
                b.bsOrbotRadioNone.isChecked = false
                b.bsOrbotRadioHttp.isChecked = false
                b.bsOrbotRadioBoth.isChecked = false
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                if (isOrbotDns) {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_1,
                            getString(R.string.orbot_status_arg_3)
                        )
                } else {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_1,
                            getString(R.string.orbot_status_arg_2)
                        )
                }
            }
            AppConfig.ProxyType.HTTP.name -> {
                b.bsOrbotRadioHttp.isChecked = true
                b.bsOrbotRadioSocks5.isChecked = false
                b.bsOrbotRadioNone.isChecked = false
                b.bsOrbotRadioBoth.isChecked = false
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_2)
            }
            AppConfig.ProxyType.HTTP_SOCKS5.name -> {
                b.bsOrbotRadioBoth.isChecked = true
                b.bsOrbotRadioSocks5.isChecked = false
                b.bsOrbotRadioHttp.isChecked = false
                b.bsOrbotRadioNone.isChecked = false
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_3)
                if (isOrbotDns) {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_3,
                            getString(R.string.orbot_status_arg_3)
                        )
                } else {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_3,
                            getString(R.string.orbot_status_arg_2)
                        )
                }
            }
            AppConfig.ProxyType.NONE.name -> {
                updateOrbotNone()
            }
            else -> {
                updateOrbotNone()
            }
        }
    }

    private fun openAppsDialog() {
        // treat proxyId and proxyName of Orbot as base
        startActivity(
            WgIncludeAppsActivity.newIntent(
                requireContext(),
                ProxyManager.ID_ORBOT_BASE,
                ProxyManager.ORBOT_PROXY_NAME
            )
        )
    }

    private fun updateOrbotNone() {
        b.bsOrbotRadioNone.isChecked = true
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
        b.orbotStatus.text = getString(R.string.orbot_bs_status_4)
    }

    /**
     * Disables the UI from selecting any other mode until the Orbot connection status is
     * obtained/time out for the Orbot.
     */
    private fun enableLoading() {
        b.bsSocks5OrbotRl.isClickable = false
        b.bsOrbotBothRl.isClickable = false
        b.bsOrbotHttpRl.isClickable = false
        b.bsOrbotNoneRl.isClickable = false
        b.bsOrbotRadioGroup.isClickable = false
        b.bsOrbotRadioSocks5.isClickable = false
        b.bsOrbotRadioHttp.isClickable = false
        b.bsOrbotRadioBoth.isClickable = false
        b.bsOrbotRadioNone.isClickable = false
        b.orbotStatus.text = getString(R.string.orbot_bs_status_trying_connect)
        animateOrbotIcon()
    }

    /** The Compose icon mirrors the original accelerating four-turn Y-axis animation. */
    private fun animateOrbotIcon() {
        b.animating = true
    }

    private fun disableLoading() {
        b.bsSocks5OrbotRl.isClickable = true
        b.bsOrbotBothRl.isClickable = true
        b.bsOrbotHttpRl.isClickable = true
        b.bsOrbotNoneRl.isClickable = true
        b.bsOrbotRadioGroup.isClickable = true
        b.bsOrbotRadioSocks5.isClickable = true
        b.bsOrbotRadioHttp.isClickable = true
        b.bsOrbotRadioBoth.isClickable = true
        b.bsOrbotRadioNone.isClickable = true
        b.animating = false
    }

    /** Stop the Orbot - Calls the Orbot helper to initiate the stop orbot call. */
    private fun stopOrbot() {
        appConfig.removeAllProxies()
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        b.bsOrbotRadioNone.isChecked = true
        b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
        orbotHelper.stopOrbot(isInteractive = true)
    }

    /** Enable - Orbot with SOCKS5 mode */
    private fun enableSocks5Orbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        startOrbot(AppConfig.ProxyType.SOCKS5.name)
    }

    /** Enable - Orbot with HTTP mode */
    private fun enableHttpOrbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        startOrbot(AppConfig.ProxyType.HTTP.name)
    }

    /** Enable - Orbot with SOCKS5 + HTTP mode */
    private fun enableSocks5HttpOrbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        startOrbot(AppConfig.ProxyType.HTTP_SOCKS5.name)
    }

    /** Start the Orbot(OrbotHelper) - Intent action. */
    private fun startOrbot(type: String) {
        io {
            val isOrbotInstalled = FirewallManager.isOrbotInstalled()
            uiCtx {
                if (!isOrbotInstalled) {
                    return@uiCtx
                }

                if (VpnController.hasTunnel()) {
                    orbotHelper.startOrbot(type)
                    logEvent(
                        "Orbot Started with type: $type",
                        "User started Orbot from Orbot Bottom Sheet with type: $type"
                    )
                } else {
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                }
            }
        }
    }

    private fun showStopOrbotDialog(isOrbotDns: Boolean) {
        val builder = WgAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.orbot_stop_dialog_title))

        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_dismiss)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNegativeButton(getString(R.string.orbot_stop_dialog_negative)) {
            dialogInterface: DialogInterface,
            _: Int ->
            dialogInterface.dismiss()
            orbotHelper.openOrbotApp()
        }
        if (isOrbotDns) {
            builder.setMessage(
                getString(
                    R.string.orbot_stop_dialog_message_combo,
                    getString(R.string.orbot_stop_dialog_message),
                    getString(R.string.orbot_stop_dialog_dns_message)
                )
            )
            builder.setNeutralButton(getString(R.string.orbot_stop_dialog_neutral)) {
                dialogInterface,
                _ ->
                dialogInterface.dismiss()
                gotoDnsConfigureScreen()
            }
        } else {
            builder.setMessage(getString(R.string.orbot_stop_dialog_message))
        }
        builder.create().show()
    }

    private fun gotoDnsConfigureScreen() {
        this.dismiss()
        val intent = Intent(requireContext(), DnsDetailActivity::class.java)
        intent.putExtra(
            Constants.VIEW_PAGER_SCREEN_TO_LOAD,
            DnsDetailActivity.Tabs.CONFIGURE.screen
        )
        startActivity(intent)
    }

    private fun showDialogForInfo() {
        var text = getString(R.string.orbot_explanation)
        text = text.replace("\n", "<br /><br />")
        val styledText = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        showOrbotInfoDialog(requireActivity(), requireContext(), getString(R.string.orbot_title), styledText)
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
