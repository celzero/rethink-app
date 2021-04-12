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
package com.celzero.bravedns.ui

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.provider.Settings.ACTION_VPN_SETTINGS
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ExcludedAppListAdapter
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.ActivitySettingsScreenBinding
import com.celzero.bravedns.databinding.DialogSetHttpProxyBinding
import com.celzero.bravedns.databinding.DialogSetProxyBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_FDROID
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_PLAY_STORE
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_WEBSITE
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_FDROID
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_PLAY
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Constants.Companion.REFRESH_BLOCKLIST_URL
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import dnsx.Dnsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import settings.Settings
import java.io.IOException

class SettingsFragment : Fragment(R.layout.activity_settings_screen) {
    private val b by viewBinding(ActivitySettingsScreenBinding::bind)

    private val FILETAG : String = "Settings Fragment-"

    //For exclude apps dialog
    private var excludeAppAdapter: ExcludedAppListAdapter? = null
    private val excludeAppViewModel: ExcludedAppViewModel by viewModel()

    private lateinit var animation: Animation

    private val appInfoRepository by inject<AppInfoRepository>()
    private val appInfoViewRepository by inject<AppInfoViewRepository>()
    private val proxyEndpointRepository by inject<ProxyEndpointRepository>()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {

        animation = RotateAnimation(0.0f, 360.0f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        animation.repeatCount = -1
        animation.duration = 1000

        b.settingsActivityAllowBypassProgress.visibility = View.GONE

        b.settingsActivityHttpProxyProgress.visibility = View.GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
            b.settingsActivityHttpProxySwitch.isChecked = persistentState.httpProxyEnabled
            if (b.settingsActivityHttpProxySwitch.isChecked) {
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc, persistentState.httpProxyHostAddress, persistentState.httpProxyPort.toString())
            }
        } else {
            b.settingsActivityHttpProxyContainer.visibility = View.GONE
        }
        b.settingsActivityAllowBypassSwitch.isChecked = persistentState.allowByPass

        if (BuildConfig.FLAVOR != FLAVOR_PLAY) {
            b.settingsActivityOnDeviceBlockRl.visibility = View.VISIBLE
            b.settingsHeadingDns.visibility = View.VISIBLE
        } else {
            b.settingsActivityOnDeviceBlockRl.visibility = View.GONE
            b.settingsHeadingDns.visibility = View.GONE
        }

        if(BuildConfig.FLAVOR == FLAVOR_FDROID){
            b.settingsActivityCheckUpdateRl.visibility = View.GONE
        }

        initialUI()

        HomeScreenActivity.GlobalVariable.braveModeToggler.observe(viewLifecycleOwner, {
            if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.FIREWALL_MODE) {
                disableDNSRelatedUI()
            } else if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.DNS_FIREWALL_MODE) {
                enableDNSFirewallUI()
            }
        })

        b.settingsActivityEnableLogsSwitch.isChecked = persistentState.logsEnabled
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        b.settingsActivityKillAppSwitch.isChecked = persistentState.killAppOnFirewall
        b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate
        b.settingsActivityThemeRl.isEnabled = true

        when(persistentState.theme){
            0 -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(R.string.settings_theme_dialog_themes_1))
            }
            1 -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(R.string.settings_theme_dialog_themes_2))
            }
            2 -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(R.string.settings_theme_dialog_themes_3))
            }
            else -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(R.string.settings_theme_dialog_themes_4))
            }
        }

        b.settingsActivitySocks5Switch.isChecked = persistentState.socks5Enabled
        if (b.settingsActivitySocks5Switch.isChecked) {
            val sock5Proxy = proxyEndpointRepository.getConnectedProxy()
            if (sock5Proxy?.proxyAppName != getString(R.string.settings_app_list_default_app)) {
                val appName = appList[sock5Proxy?.proxyAppName]?.appName
                b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_desc, sock5Proxy!!.proxyIP, sock5Proxy.proxyPort.toString(), appName)
            } else {
                b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_desc, sock5Proxy.proxyIP, sock5Proxy.proxyPort.toString(), getString(R.string.settings_app_list_default_app))
            }
        }
        b.settingsActivitySocks5Progress.visibility = View.GONE

        if (persistentState.localBlocklistEnabled && BuildConfig.FLAVOR != FLAVOR_PLAY) {
            b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
            val timeStamp = persistentState.localBlockListDownloadTime
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
            b.settingsActivityOnDeviceBlockSwitch.isChecked = true
        } else {
            b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
        }

        //For exclude apps
        excludeAppAdapter = ExcludedAppListAdapter(requireContext(), appInfoRepository, categoryInfoRepository)
        excludeAppViewModel.excludedAppList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(excludeAppAdapter!!::submitList))


        val appCount = appList.size
        appInfoViewRepository.getExcludedAppListCountLiveData().observe(viewLifecycleOwner, {
            b.settingsActivityExcludeAppsCountText.text = getString(R.string.ex_dialog_count, it.toString(), appCount.toString())
        })

    }

    /**
     * Enabled all the layouts and change the labels
     * for the heading.
     */
    private fun enableDNSFirewallUI() {
        b.settingsHeadingDns.text = getString(R.string.app_mode_dns)
        b.settingsActivityOnDeviceBlockRl.isEnabled = true
        b.settingsActivityOnDeviceBlockSwitch.isEnabled = true
        b.settingsActivityOnDeviceBlockRefreshBtn.isEnabled = true
        b.settingsActivityOnDeviceBlockConfigureBtn.isEnabled = true
    }

    /**
     * Disable all the layouts related with DNS
     */
    private fun disableDNSRelatedUI() {
        b.settingsHeadingDns.text  = getString(R.string.dns_mode_disabled)
        b.settingsActivityOnDeviceBlockRl.isEnabled = false
        b.settingsActivityOnDeviceBlockSwitch.isEnabled = false
        b.settingsActivityOnDeviceBlockRefreshBtn.isEnabled = false
        b.settingsActivityOnDeviceBlockConfigureBtn.isEnabled = false
    }

    /**
     * Disable all the layouts related to lockdown mode. like exclude apps and allow bypass
     */
    private fun disableForLockdownModeUI() {
        b.settingsActivityVpnHeadingText.text = getString(R.string.settings_vpn_heading_disabled)
        b.settingsActivityOnDeviceBlockRl.isEnabled = false
        b.settingsActivityExcludeAppsRl.isEnabled = false
        b.settingsActivityAllowBypassSwitch.isEnabled = false
        b.settingsActivityExcludeAppsImg.isEnabled = false
        b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
        //Orbot
        b.settingsActivityOrbotImg.isEnabled = false
        b.settingsActivityOrbotContainer.isEnabled = false
        //SOCKS5
        b.settingsActivitySocks5Switch.isEnabled = false
        //HTTP Proxy
        b.settingsActivityHttpProxySwitch.isEnabled = false
    }

    private fun enableForLockdownModeUI() {
        b.settingsActivityVpnHeadingText.text = getString(R.string.settings_vpn_heading)
        b.settingsActivityOnDeviceBlockRl.isEnabled = true
        b.settingsActivityExcludeAppsRl.isEnabled = true
        b.settingsActivityAllowBypassSwitch.isEnabled = true
        b.settingsActivityExcludeAppsImg.isEnabled = true
        b.settingsActivityVpnLockdownDesc.visibility = View.GONE
        //Orbot
        b.settingsActivityOrbotImg.isEnabled = true
        b.settingsActivityOrbotContainer.isEnabled = true
        //SOCKS5
        b.settingsActivitySocks5Switch.isEnabled = true
        //HTTP Proxy
        b.settingsActivityHttpProxySwitch.isEnabled = true
    }

    private fun detectLockDownMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val vpnService = VpnController.getInstance().getBraveVpnService()
            if (vpnService != null) {
                val lockDown = vpnService.isLockdownEnabled
                if (DEBUG) Log.d(LOG_TAG, "$FILETAG isLockDownEnabled - $lockDown ")
                if (lockDown) {
                    disableForLockdownModeUI()
                } else {
                    enableForLockdownModeUI()
                }
            } else {
                enableForLockdownModeUI()
            }
        }
    }

    private fun initClickListeners() {

        b.settingsActivityEnableLogsSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.logsEnabled = b
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.prefAutoStartBootUp = b
        }

        b.settingsActivityKillAppSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.killAppOnFirewall = b
        }


        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener{ _: CompoundButton, b: Boolean ->
            persistentState.checkForAppUpdate = b
        }

        b.settingsActivityAllowBypassSwitch.setOnCheckedChangeListener { _: CompoundButton, bool: Boolean ->
            persistentState.allowByPass = bool
            b.settingsActivityAllowBypassSwitch.isEnabled = false
            b.settingsActivityAllowBypassSwitch.visibility = View.INVISIBLE
            b.settingsActivityAllowBypassProgress.visibility = View.VISIBLE
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }
                override fun onFinish() {
                    if(isAdded) {
                        b.settingsActivityAllowBypassSwitch.isEnabled = true
                        b.settingsActivityAllowBypassProgress.visibility = View.GONE
                        b.settingsActivityAllowBypassSwitch.visibility = View.VISIBLE
                    }
                }
            }.start()

        }

        b.settingsActivityOnDeviceBlockSwitch.setOnCheckedChangeListener(null)
        b.settingsActivityOnDeviceBlockSwitch.setOnClickListener {
            val isSelected = b.settingsActivityOnDeviceBlockSwitch.isChecked
            b.settingsActivityOnDeviceBlockSwitch.isEnabled = false
            if (isSelected) {
                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                if (!persistentState.blockListFilesDownloaded) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val vpnService = VpnController.getInstance().getBraveVpnService()
                        if(vpnService != null && vpnService.isLockdownEnabled){
                            showDownloadDialogWithLockdown()
                        }else{
                            showDownloadDialog()
                        }
                    }else{
                        showDownloadDialog()
                    }
                } else {
                    if (isSelected) {
                        setBraveDNSLocal()
                        val count = persistentState.numberOfLocalBlocklists
                        b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_in_use, count.toString())
                        persistentState.localBlocklistEnabled = true
                    }
                }
            } else {
                removeBraveDNSLocal()
                b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
                persistentState.localBlocklistEnabled = false
            }
            object : CountDownTimer(1000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    if(isAdded) {
                        b.settingsActivityOnDeviceBlockSwitch.isEnabled = true
                    }
                }
            }.start()
        }

        b.settingsActivityVpnLockdownDesc.setOnClickListener{
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Intent(ACTION_VPN_SETTINGS)
                } else {
                    Intent("android.net.vpn.SETTINGS")
                }
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(LOG_TAG, "Exception while opening app info: ${e.message}", e)
            }
        }

        b.settingsActivitySocks5Switch.setOnCheckedChangeListener { _: CompoundButton, bool: Boolean ->
            if (persistentState.getOrbotModePersistence() != Constants.ORBAT_MODE_NONE) {
                Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_socks5_disabled_error), Toast.LENGTH_SHORT)
                b.settingsActivitySocks5Switch.isChecked = false
            }else {
                persistentState.socks5Enabled = bool
                if (bool) {
                    showDialogForSocks5Proxy()
                } else {
                    b.settingsActivitySocks5Switch.visibility = View.GONE
                    b.settingsActivitySocks5Progress.visibility = View.VISIBLE
                    appMode?.setProxyMode(Settings.ProxyModeNone)
                    b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_default_desc)
                    b.settingsActivitySocks5Switch.visibility = View.VISIBLE
                    b.settingsActivitySocks5Progress.visibility = View.GONE
                }
            }
        }

        b.settingsActivityOrbotImg.setOnClickListener{
            if(get<OrbotHelper>().isOrbotInstalled()) {
                if (b.settingsActivityHttpProxySwitch.isChecked) {
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_https_orbot_disabled_error), Toast.LENGTH_SHORT)
                } else if (b.settingsActivitySocks5Switch.isChecked) {
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_socks5_orbot_disabled_error), Toast.LENGTH_SHORT)
                } else {
                    openBottomSheetForOrbot()
                }
            }else{
                showOrbotInstallDialog()
            }
        }

        b.settingsActivityOrbotContainer.setOnClickListener{
            if (get<OrbotHelper>().isOrbotInstalled()) {
                if (b.settingsActivityHttpProxySwitch.isChecked) {
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_https_orbot_disabled_error), Toast.LENGTH_SHORT)
                } else if (b.settingsActivitySocks5Switch.isChecked) {
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_socks5_orbot_disabled_error), Toast.LENGTH_SHORT)
                } else {
                    openBottomSheetForOrbot()
                }
            } else {
                showOrbotInstallDialog()
            }
        }

        b.settingsActivityHttpProxySwitch.setOnCheckedChangeListener { _: CompoundButton, isEnabled: Boolean ->
            if (persistentState.getOrbotModePersistence() != Constants.ORBAT_MODE_NONE) {
                Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_https_disabled_error), Toast.LENGTH_SHORT)
                b.settingsActivityHttpProxySwitch.isChecked = false
            } else {
                showDialogForHTTPProxy(isEnabled)
            }
        }


        b.settingsActivityExcludeAppsImg.setOnClickListener {
            b.settingsActivityExcludeAppsImg.isEnabled = false
            showExcludeAppDialog(requireContext(), excludeAppAdapter!!, excludeAppViewModel)
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    if(isAdded) {
                        b.settingsActivityExcludeAppsImg.isEnabled = true
                    }
                }
            }.start()
        }

        b.settingsActivityExcludeAppsRl.setOnClickListener {
            b.settingsActivityExcludeAppsRl.isEnabled = false
            showExcludeAppDialog(requireContext(), excludeAppAdapter!!, excludeAppViewModel)
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    if(isAdded) {
                        b.settingsActivityExcludeAppsRl.isEnabled = true
                    }
                }
            }.start()
        }


        b.settingsActivityOnDeviceBlockConfigureBtn.setOnClickListener {
            val intent = Intent(requireContext(), DNSConfigureWebViewActivity::class.java)
            val stamp = persistentState.getLocalBlockListStamp()
            if (DEBUG) Log.d(LOG_TAG, "$FILETAG Stamp value in settings screen - $stamp")
            intent.putExtra(Constants.LOCATION_INTENT_EXTRA, DNSConfigureWebViewActivity.LOCAL)
            intent.putExtra(Constants.STAMP_INTENT_EXTRA, stamp)
            (requireContext() as Activity).startActivityForResult(intent, Activity.RESULT_OK)
        }

        b.settingsActivityOnDeviceBlockRefreshBtn.setOnClickListener {
            checkForDownload(isUserInitiated = true, isRetry = true)
        }

        b.settingsActivityThemeRl.setOnClickListener{
            b.settingsActivityThemeRl.isEnabled = false
            showDialogForTheme()
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    if(isAdded) {
                        b.settingsActivityThemeRl.isEnabled = true
                    }
                }
            }.start()
        }

        WorkManager.getInstance().getWorkInfosByTagLiveData(DownloadConstants.DOWNLOAD_TAG).observe(viewLifecycleOwner, { workInfoList ->
            if (workInfoList != null && workInfoList.isNotEmpty()) {
                val workInfo = workInfoList[0]
                if (workInfo != null && (workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING)) {
                    updateDownloadInitiated()
                } else if (workInfo != null && (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED)) {
                    updateDownloadFailure()
                    WorkManager.getInstance().pruneWork()
                    WorkManager.getInstance().cancelAllWorkByTag(DownloadConstants.DOWNLOAD_TAG)
                    WorkManager.getInstance().cancelAllWorkByTag(DownloadConstants.FILE_TAG)
                    Log.i(LOG_TAG, "AppDownloadManager Work Manager failed - ${DownloadConstants.FILE_TAG}")
                } else {
                    Log.i(LOG_TAG, "AppDownloadManager Work Manager - ${DownloadConstants.DOWNLOAD_TAG}, ${workInfo.state}")
                }
            }
        })

        WorkManager.getInstance().getWorkInfosByTagLiveData(DownloadConstants.FILE_TAG).observe(viewLifecycleOwner, { workInfoList ->
            if (workInfoList != null && workInfoList.isNotEmpty()) {
                val workInfo = workInfoList[0]
                if (workInfo != null && workInfo.state == WorkInfo.State.SUCCEEDED) {
                    Log.i(LOG_TAG, "AppDownloadManager Work Manager completed - ${DownloadConstants.FILE_TAG}")
                    updateDownloadSuccess()
                    WorkManager.getInstance().pruneWork()
                } else if (workInfo != null && (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED)) {
                    updateDownloadFailure()
                    WorkManager.getInstance().pruneWork()
                    WorkManager.getInstance().cancelAllWorkByTag(DownloadConstants.FILE_TAG)
                    Log.i(LOG_TAG, "AppDownloadManager Work Manager failed - ${DownloadConstants.FILE_TAG}")
                } else {
                    Log.i(LOG_TAG, "AppDownloadManager Work Manager - ${DownloadConstants.FILE_TAG}, ${workInfo.state}")
                }
            }
        })

    }


    private fun updateUI() {
        when (persistentState.getOrbotModePersistence()) {
            Constants.ORBAT_MODE_SOCKS5 -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_1)
            }
            Constants.ORBAT_MODE_HTTP -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_2)
            }
            Constants.ORBAT_MODE_BOTH -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_3)
            }
            Constants.ORBAT_MODE_NONE -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
            }
            else -> {
                b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
            }
        }
    }

    private fun openBottomSheetForOrbot(){
        val vpnService = VpnController.getInstance().getBraveVpnService()
        if (vpnService != null) {
            val bottomSheetFragment = OrbotBottomSheetFragment()
            val frag = context as FragmentActivity
            bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
        } else {
            Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_socks5_vpn_disabled_error), Toast.LENGTH_SHORT)
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }


    private fun showDialogForTheme() {
        val alertBuilder = AlertDialog.Builder(requireContext())
        alertBuilder.setTitle(getString(R.string.settings_theme_dialog_title))
        val items  = arrayOf(getString(R.string.settings_theme_dialog_themes_1), getString(R.string.settings_theme_dialog_themes_2), getString(R.string.settings_theme_dialog_themes_3), getString(R.string.settings_theme_dialog_themes_4))
        val checkedItem = persistentState.theme
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if(persistentState.theme != which) {
                when (which) {
                    0 -> {
                        persistentState.theme = 0
                        if (requireActivity().isDarkThemeOn()) {
                            requireActivity().setTheme(R.style.AppTheme)
                            requireActivity().recreate()
                        } else {
                            requireActivity().setTheme(R.style.AppThemeWhite)
                            requireActivity().recreate()
                        }
                    }
                    1 -> {
                        persistentState.theme = 1
                        requireActivity().setTheme(R.style.AppThemeWhite)
                        requireActivity().recreate()
                    }
                    2 -> {
                        persistentState.theme = 2
                        requireActivity().setTheme(R.style.AppTheme)
                        requireActivity().recreate()
                    }
                    3 -> {
                        persistentState.theme = 3
                        requireActivity().setTheme(R.style.AppThemeTrueBlack)
                        requireActivity().recreate()
                    }
                }
            }
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = alertBuilder.create()
        // Set other dialog properties
        alertDialog.show()
    }

    override fun onResume() {
        super.onResume()
        detectLockDownMode()
        if (!persistentState.localBlocklistEnabled) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
        }else {
            val count = persistentState.numberOfLocalBlocklists
            if (persistentState.localBlocklistEnabled && persistentState.blockListFilesDownloaded && persistentState.getLocalBlockListStamp().isNotEmpty()) {
                if(count !=0 ){
                    b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_in_use, count.toString())
                }else {
                    b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc6)
                }
            } else if(count != 0){
                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_in_use, count.toString())
            }else {
                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
            }
        }

        // Checks whether the Orbot is installed.
        // If not, then prompt the user for installation.
        // Else, enable the Orbot bottom sheet fragment.
        if (!get<OrbotHelper>().isOrbotInstalled()) {
            b.settingsActivityHttpOrbotDesc.text = getString(R.string.settings_orbot_install_desc)
        } else {
            updateUI()
        }
    }

    private fun checkForDownload(isUserInitiated: Boolean, isRetry: Boolean): Boolean {
        val timeStamp = persistentState.localBlockListDownloadTime
        val appVersionCode = persistentState.appVersion
        val url = "$REFRESH_BLOCKLIST_URL$timeStamp&${Constants.APPEND_VCODE}$appVersionCode"
        if (DEBUG) Log.d(LOG_TAG, "$FILETAG Check for local download, url - $url")
        run(url, isUserInitiated, isRetry)
        return false
    }

    private fun run(url: String, isUserInitiated: Boolean, isRetry: Boolean) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG, "$FILETAG onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                activity?.runOnUiThread {
                    if(isRetry) {
                        Utilities.showToastInMidLayout(requireContext(),getString(R.string.local_blocklist_update_check_failure), Toast.LENGTH_SHORT)
                    }else{
                        updateDownloadFailure()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val stringResponse = response.body!!.string()
                    //creating json object
                    val jsonObject = JSONObject(stringResponse)
                    val version = jsonObject.getInt(Constants.JSON_VERSION)
                    if (DEBUG) Log.d(LOG_TAG, "$FILETAG client onResponse for refresh blocklist files-  $version")
                    if (version == 1) {
                        val updateValue = jsonObject.getBoolean(Constants.JSON_UPDATE)
                        val timeStamp = jsonObject.getLong(Constants.JSON_LATEST)
                        if (DEBUG) Log.d(LOG_TAG, "$FILETAG onResponse -  $updateValue")
                        if (updateValue) {
                            persistentState.tempBlocklistDownloadTime = timeStamp
                            persistentState.workManagerStartTime = SystemClock.elapsedRealtime()
                            get<AppDownloadManager>().downloadLocalBlocklist(timeStamp)
                            activity?.runOnUiThread {
                                updateDownloadInitiated()
                            }
                        } else {
                            activity?.runOnUiThread {
                                if (isUserInitiated && !isRetry) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val vpnService = VpnController.getInstance().getBraveVpnService()
                                        if (vpnService != null && vpnService.isLockdownEnabled) {
                                            showRedownloadDialogLockdown(timeStamp)
                                        } else {
                                            showRedownloadDialog(timeStamp)
                                        }
                                    } else {
                                        showRedownloadDialog(timeStamp)
                                    }
                                } else {
                                    updateDownloadFailure()
                                    Utilities.showToastInMidLayout(activity as Context, getString(R.string.settings_local_blocklist_desc4), Toast.LENGTH_SHORT)
                                }
                            }
                        }
                    }
                    response.body!!.close()
                    client.connectionPool.evictAll()
                } catch (e: java.lang.Exception) {
                    Log.w(LOG_TAG, "$FILETAG Exception while downloading: ${e.message}", e)
                    activity?.runOnUiThread {
                        updateDownloadFailure()
                    }
                }
            }
        })
    }

    private fun updateDownloadFailure(){
        b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
        b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
        b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
        b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
        b.settingsActivityOnDeviceBlockSwitch.isChecked = false
        b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
        persistentState.workManagerStartTime = 0
    }

    private fun updateDownloadSuccess(){
        b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
        b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
        b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
        b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
        b.settingsActivityOnDeviceBlockSwitch.isChecked = true
        b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc3)
        val timeStamp = persistentState.localBlockListDownloadTime
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
        persistentState.workManagerStartTime = 0
    }

    private fun initialUI(){
        b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
        b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
        b.settingsActivityOnDeviceBlockSwitch.isChecked = false
        b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
        b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
    }

    private fun updateDownloadInitiated(){
        b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc2)
        b.settingsActivityOnDeviceBlockSwitch.visibility = View.GONE
        b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE
        b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.INVISIBLE
    }


    private fun showDialogForHTTPProxy(isEnabled: Boolean) {
        if (isEnabled) {
            var isValid: Boolean
            var host: String
            var port = 0
            val dialog = Dialog(requireContext())
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            dialog.setTitle(getString(R.string.settings_http_proxy_dialog_title))
            val dialogBinding = DialogSetHttpProxyBinding.inflate(layoutInflater)
            dialog.setContentView(dialogBinding.root)

            val lp = WindowManager.LayoutParams()
            lp.copyFrom(dialog.window!!.attributes)
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            dialog.show()
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.window!!.attributes = lp

            val applyURLBtn = dialogBinding.dialogHttpProxyOkBtn
            val cancelURLBtn = dialogBinding.dialogHttpProxyCancelBtn
            val hostAddressEditText = dialogBinding.dialogHttpProxyEditText
            val portEditText = dialogBinding.dialogHttpProxyEditTextPort
            val errorTxt = dialogBinding.dialogHttpProxyFailureText

            val hostName = persistentState.httpProxyHostAddress
            val portAddr = persistentState.httpProxyPort
            if (hostName.isNotEmpty()) {
                hostAddressEditText.setText(hostName, TextView.BufferType.EDITABLE)
            }
            if (portAddr != 0) {
                portEditText.setText(portAddr.toString(), TextView.BufferType.EDITABLE)
            } else {
                portEditText.setText(Constants.HTTP_PROXY_PORT, TextView.BufferType.EDITABLE)
            }
            applyURLBtn.setOnClickListener {
                host = hostAddressEditText.text.toString()
                var isHostValid = true
                try {
                    port = portEditText.text.toString().toInt()
                    if (port in 65535 downTo 1024) {
                        isValid = true
                    } else {
                        errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                        errorTxt.visibility = View.VISIBLE
                        isValid = false
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "$FILETAG Error: ${e.message}", e)
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                    errorTxt.visibility = View.VISIBLE
                    isValid = false
                }

                if (host.isEmpty() || host.isBlank()) {
                    isHostValid = false
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                    errorTxt.visibility = View.VISIBLE
                }

                if (isValid && isHostValid) {
                    b.settingsActivityHttpProxyProgress.visibility = View.VISIBLE
                    b.settingsActivityHttpProxySwitch.visibility = View.GONE
                    errorTxt.visibility = View.INVISIBLE
                    persistentState.httpProxyHostAddress = host
                    persistentState.httpProxyPort = port
                    persistentState.httpProxyEnabled = true
                    dialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.settings_http_proxy_toast_success), Toast.LENGTH_SHORT).show()
                    if (b.settingsActivityHttpProxySwitch.isChecked) {
                        b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc, host, port.toString())
                    }
                    b.settingsActivityHttpProxyProgress.visibility = View.GONE
                    b.settingsActivityHttpProxySwitch.visibility = View.VISIBLE
                }
            }

            cancelURLBtn.setOnClickListener {
                dialog.dismiss()
                persistentState.httpProxyEnabled = false
                persistentState.httpProxyHostAddress = ""
                persistentState.httpProxyPort  = 0
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc_default)
                b.settingsActivityHttpProxySwitch.isChecked = false
            }
        } else {
            persistentState.httpProxyEnabled = false
            persistentState.httpProxyHostAddress = ""
            persistentState.httpProxyPort = 0
            b.settingsActivityHttpProxySwitch.isChecked = false
            b.settingsActivityHttpProxyDesc.text =  getString(R.string.settings_http_proxy_desc_default)
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Activity.RESULT_OK) {
            val stamp = data?.getStringExtra(Constants.STAMP_INTENT_EXTRA)
            Log.i(LOG_TAG, "$FILETAG onActivityResult - stamp from webview - $stamp")
            setBraveDNSLocal()
        }
    }

    private fun setBraveDNSLocal() {
        b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
        b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
        val timeStamp = persistentState.localBlockListDownloadTime
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
        val path: String = requireContext().filesDir.canonicalPath + "/" +timeStamp
        if (appMode?.getBraveDNS() == null) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    if (DEBUG) Log.d(LOG_TAG, "$FILETAG Local brave dns set call from settings fragment newBraveDNSLocal : $path")
                    val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE, path + Constants.FILE_RD_FILE, path + Constants.FILE_BASIC_CONFIG, path + Constants.FILE_TAG_NAME)
                    appMode?.setBraveDNSMode(braveDNS)
                    persistentState.localBlocklistEnabled = true
                }catch (e: Exception){
                    Log.w(LOG_TAG, "Exception while setting blocklist: ${e.message}", e)
                    persistentState.localBlocklistEnabled = false
                    if (isAdded) {
                        (context as HomeScreenActivity).runOnUiThread {
                            updateDownloadFailure()
                            showDialogForFileCorrupt(timeStamp)
                        }
                    }
                }
            }
        }
    }



    private fun removeBraveDNSLocal() {
        appMode?.setBraveDNSMode(null)
        persistentState.localBlocklistEnabled = false
    }

    /**
     * Prompt user to download the Orbot app based on the current BUILDCONFIG flavor.
     */
    private fun showOrbotInstallDialog() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.orbot_install_dialog_title)
        //set message for alert dialog
        builder.setMessage(R.string.orbot_install_dialog_message)

        //Play store install intent
        if(BuildConfig.FLAVOR == Constants.FLAVOR_PLAY){
            builder.setPositiveButton(getString(R.string.orbot_install_dialog_positive)){ _, _ ->
                val intent = get<OrbotHelper>().getIntentForDownload(requireContext(), DOWNLOAD_SOURCE_PLAY_STORE)
                if(intent != null){
                    try {
                        startActivity(intent)
                    }catch (e: ActivityNotFoundException){
                        Utilities.showToastInMidLayout(requireContext(), getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
                    }
                }else{
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
                }
            }

        }else if(BuildConfig.FLAVOR == FLAVOR_FDROID){//fDroid install intent
            builder.setPositiveButton(getString(R.string.orbot_install_dialog_positive)) { _, _ ->
                val intent = get<OrbotHelper>().getIntentForDownload(requireContext(), DOWNLOAD_SOURCE_FDROID)
                if (intent != null) {
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Utilities.showToastInMidLayout(requireContext(), getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
                    }
                } else {
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
                }
            }
        }else{//Orbot website download link
            builder.setPositiveButton(getString(R.string.orbot_install_dialog_positive)) { _, _ ->
                val intent = get<OrbotHelper>().getIntentForDownload(requireContext(), DOWNLOAD_SOURCE_WEBSITE)
                if (intent != null) {
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Utilities.showToastInMidLayout(requireContext(), getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
                    }
                } else {
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
                }
            }
        }

        //performing negative action
        builder.setNegativeButton(getString(R.string.orbot_install_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }

        // Take to the Tor website.
        builder.setNeutralButton(getString(R.string.orbot_install_dialog_neutral)) { _, _ ->
            val intent = Intent(Intent.ACTION_VIEW, getString(R.string.orbot_website_link).toUri())
            startActivity(intent)
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.show()
    }

    private fun showDownloadDialogWithLockdown(){
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.download_lockdown_dialog_heading)
        //set message for alert dialog
        builder.setMessage(R.string.download_lockdown_dialog_desc)
        builder.setCancelable(false)
        //performing positive action
        builder.setPositiveButton(getString(R.string.download_lockdown_dialog_positive)) { _, _ ->
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Intent(ACTION_VPN_SETTINGS)
            } else {
                Intent("android.net.vpn.SETTINGS")
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
        }

        //performing negative action
        builder.setNegativeButton(getString(R.string.download_lockdown_dialog_negative)) { dialog, _ ->
            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
            dialog.dismiss()
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.show()
    }


    private fun showDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.local_blocklist_download)
        //set message for alert dialog
        builder.setMessage(R.string.local_blocklist_download_desc)
        builder.setCancelable(false)
        //performing positive action
        builder.setPositiveButton(getString(R.string.settings_local_blocklist_dialog_positive)) { _, _ ->
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc2)
            b.settingsActivityOnDeviceBlockSwitch.visibility = View.GONE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE
            checkForDownload(isUserInitiated = false, isRetry = false)
        }
        //performing negative action
        builder.setNegativeButton(getString(R.string.settings_local_blocklist_dialog_negative)) { _, _ ->
            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.show()
    }

    private fun showRedownloadDialogLockdown(timeStamp: Long) {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.local_blocklist_lockdown_redownload)
        //set message for alert dialog
        builder.setMessage(getString(R.string.local_blocklist_lockdown_redownload_desc, Utilities.convertLongToDate(timeStamp)))
        builder.setCancelable(false)
        //performing positive action
        builder.setPositiveButton(getString(R.string.local_blocklist_lockdown_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.show()
    }

    private fun showRedownloadDialog(timeStamp: Long) {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.local_blocklist_redownload)
        //set message for alert dialog
        builder.setMessage(getString(R.string.local_blocklist_redownload_desc, Utilities.convertLongToDate(timeStamp)))
        builder.setCancelable(false)
        //performing positive action
        builder.setPositiveButton(getString(R.string.local_blocklist_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        //performing negative action
        builder.setNeutralButton(getString(R.string.local_blocklist_neutral)) { _, _ ->
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc2)
            b.settingsActivityOnDeviceBlockSwitch.visibility = View.GONE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.INVISIBLE
            persistentState.tempBlocklistDownloadTime = timeStamp
            persistentState.workManagerStartTime = SystemClock.elapsedRealtime()
            get<AppDownloadManager>().downloadLocalBlocklist(timeStamp)
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.show()
    }

    private fun showDialogForFileCorrupt(timeStamp: Long) {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.local_blocklist_corrupt)
        //set message for alert dialog
        builder.setMessage(R.string.local_blocklist_corrupt_desc)
        builder.setCancelable(false)
        //performing positive action
        builder.setNegativeButton(getString(R.string.local_blocklist_corrupt_negative)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        //performing negative action
        builder.setPositiveButton(getString(R.string.local_blocklist_corrupt_positive)) { _, _ ->
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc2)
            b.settingsActivityOnDeviceBlockSwitch.visibility = View.GONE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.INVISIBLE
            persistentState.tempBlocklistDownloadTime = timeStamp
            persistentState.workManagerStartTime = SystemClock.elapsedRealtime()
            get<AppDownloadManager>().downloadLocalBlocklist(timeStamp)
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.show()
    }

    private fun showExcludeAppDialog(context: Context, recyclerAdapter: ExcludedAppListAdapter, excludeAppViewModel: ExcludedAppViewModel) {
        val themeID = getCurrentTheme()
        val excludeAppDialog = ExcludeAppDialog(context, get(), get(), get(), persistentState, recyclerAdapter, excludeAppViewModel, themeID)
        //if we know that the particular variable not null any time ,we can assign !! (not null operator ),
        // then  it won't check for null, if it becomes null, it will throw exception
        excludeAppDialog.show()
        excludeAppDialog.setCanceledOnTouchOutside(false)
    }

    private fun getCurrentTheme(): Int {
        if (persistentState.theme == 0) {
            if (isDarkThemeOn()) {
                return R.style.AppThemeTrueBlack
            } else {
                return R.style.AppThemeWhite
            }
        } else if (persistentState.theme == 1) {
            return R.style.AppThemeWhite
        } else if (persistentState.theme == 2) {
            return R.style.AppTheme
        } else {
            return R.style.AppThemeTrueBlack
        }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showDialogForSocks5Proxy() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogBinding = DialogSetProxyBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp


        val applyURLBtn = dialogBinding.dialogProxyApplyBtn
        val cancelURLBtn = dialogBinding.dialogProxyCancelBtn
        val ipAddressEditText: EditText = dialogBinding.dialogProxyEditIp
        val portEditText: EditText = dialogBinding.dialogProxyEditPort
        val appNameSpinner: Spinner = dialogBinding.dialogProxySpinnerAppname
        val errorTxt: TextView = dialogBinding.dialogProxyErrorText
        val userNameEditText: EditText = dialogBinding.dialogProxyEditUsername
        val passwordEditText: EditText = dialogBinding.dialogProxyEditPassword
        val udpBlockCheckBox: CheckBox = dialogBinding.dialogProxyUdpCheck


        val sock5Proxy = proxyEndpointRepository.getConnectedProxy()

        udpBlockCheckBox.isChecked = persistentState.udpBlockedSettings

        val appNames: MutableList<String> = ArrayList()
        appNames.add(getString(R.string.settings_app_list_default_app))
        appNames.addAll(getAppName())
        val proxySpinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, appNames)
        appNameSpinner.adapter = proxySpinnerAdapter
        if (sock5Proxy != null && !sock5Proxy.proxyIP.isNullOrEmpty()) {
            ipAddressEditText.setText(sock5Proxy.proxyIP, TextView.BufferType.EDITABLE)
            portEditText.setText(sock5Proxy.proxyPort.toString(), TextView.BufferType.EDITABLE)
            userNameEditText.setText(sock5Proxy.userName.toString(), TextView.BufferType.EDITABLE)
            if (sock5Proxy.proxyAppName?.isNotEmpty()!! && sock5Proxy.proxyAppName != getString(R.string.settings_app_list_default_app)) {
                val packageName = sock5Proxy.proxyAppName
                val app = appList[packageName]
                var position = 0
                for ((i, item) in appNames.withIndex()) {
                    if (item == app?.appName) {
                        position = i
                    }
                }
                appNameSpinner.setSelection(position)
            }
        } else {
            ipAddressEditText.setText(Constants.SOCKS_DEFAULT_IP, TextView.BufferType.EDITABLE)
            portEditText.setText(Constants.SOCKS_DEFAULT_PORT, TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var port = 0
            var isValid: Boolean
            var isIPValid = true
            var isUDPBlock = false
            val mode: String = getString(R.string.cd_dns_proxy_mode_external)
            val appName : String = appNames[appNameSpinner.selectedItemPosition]
            val appPackageName = if (appName.isEmpty() || appName == getString(R.string.settings_app_list_default_app)) {
                appNames[0]
            } else {
                appInfoRepository.getPackageNameForAppName(appName)
            }
            val ip: String = ipAddressEditText.text.toString()

            if (ip.isEmpty() || ip.isBlank()) {
                isIPValid = false
                errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                errorTxt.visibility = View.VISIBLE
            }
            try {
                port = portEditText.text.toString().toInt()
                if (Utilities.isIPLocal(ip)) {
                    if (port in 65535 downTo 1024) {
                        isValid = true
                    } else {
                        errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                        isValid = false
                    }
                } else {
                    isValid = true
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "$FILETAG Error: ${e.message}", e)
                errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                isValid = false
            }
            if (udpBlockCheckBox.isChecked) {
                isUDPBlock = true
            }

            val userName: String = userNameEditText.text.toString()
            val password: String = passwordEditText.text.toString()
            if (isValid && isIPValid) {
                //Do the Socks5 Proxy setting there
                persistentState.udpBlockedSettings = udpBlockCheckBox.isChecked
                insertProxyEndpointDB(mode, Constants.SOCKS, appPackageName, ip, port, userName, password, isUDPBlock)
                b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_desc, ip, port.toString(), appName)
                dialog.dismiss()
            }
        }

        cancelURLBtn.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = false
            appMode?.setProxyMode(Settings.ProxyModeNone)
            b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_default_desc)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun getAppName(): MutableList<String> {
        return appInfoRepository.getAppNameList()
    }

    private fun insertProxyEndpointDB(mode: String, name: String, appName: String, ip: String, port: Int, userName: String, password: String, isUDPBlock: Boolean) {
        var proxyName = name
        if (proxyName.isEmpty() || proxyName.isBlank()) {
            proxyName = if (mode == getString(R.string.cd_dns_proxy_mode_internal)) {
                appName
            } else ip
        }
        val proxyEndpoint = ProxyEndpoint(-1, proxyName, 1, mode, appName, ip, port, userName, password, true, true, isUDPBlock, 0L, 0)
        proxyEndpointRepository.clearAllData()
        proxyEndpointRepository.insertAsync(proxyEndpoint)
        object : CountDownTimer(1000, 500) {
            override fun onTick(millisUntilFinished: Long) {
                b.settingsActivitySocks5Switch.isEnabled = false
                b.settingsActivitySocks5Switch.visibility = View.GONE
                b.settingsActivitySocks5Progress.visibility = View.VISIBLE
            }
            override fun onFinish() {
                appMode?.setProxyMode(Settings.ProxyModeSOCKS5)
                if(isAdded) {
                    b.settingsActivitySocks5Switch.isEnabled = true
                    b.settingsActivitySocks5Progress.visibility = View.GONE
                    b.settingsActivitySocks5Switch.visibility = View.VISIBLE
                }
            }
        }.start()
    }


}
