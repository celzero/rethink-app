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
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import android.view.*
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ExcludedAppListAdapter
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.filesDownloaded
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.localDownloadComplete
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_OTHERS
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Constants.Companion.REFRESH_BLOCKLIST_URL
import com.celzero.bravedns.util.HttpRequestHelper.Companion.checkStatus
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
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
import java.io.File
import java.io.IOException


class SettingsFragment : Fragment() {

    private lateinit var faqTxt: AppCompatTextView
    private lateinit var refreshDataRL: RelativeLayout

    private lateinit var enableLogsRL: RelativeLayout
    private lateinit var autoStartRL: RelativeLayout
    private lateinit var killAppRL: RelativeLayout
    private lateinit var allowByPassRL: RelativeLayout

    //private lateinit var alwaysOnRL: RelativeLayout
    private lateinit var socks5RL: RelativeLayout
    private lateinit var excludeAppsRL: RelativeLayout

    //private lateinit var blockUnknownConnRL: RelativeLayout
    private lateinit var onDeviceBlockListRL: RelativeLayout
    private lateinit var onDeviceBlockListDesc: TextView
    private lateinit var onDeviceLastUpdatedTime : TextView
    private lateinit var dnsSettingsHeading : TextView

    private lateinit var refreshDataImg: AppCompatImageView
    private lateinit var refreshDataDescTxt: TextView

    private lateinit var vpnSettingsHeadingTV  :TextView
    private lateinit var vpnSettingsHeadingDesc : TextView

    private lateinit var enableLogsSwitch: SwitchCompat
    private lateinit var autoStartSwitch: SwitchCompat
    private lateinit var killAppSwitch: SwitchCompat
    private lateinit var allowByPassSwitch: SwitchCompat
    private lateinit var allowByPassDescText : TextView
    private lateinit var allowByPassProgressBar: ProgressBar

    //private lateinit var alwaysOnSwitch: SwitchCompat
    private lateinit var socks5Switch: SwitchCompat
    private lateinit var excludeAppImg: AppCompatImageView
    private lateinit var socks5DescText: TextView
    private lateinit var socks5Progress: ProgressBar

    //private lateinit var blockUnknownConnSwitch: SwitchCompat
    private lateinit var onDeviceBlockListSwitch: SwitchCompat
    private lateinit var onDeviceBlockListProgress: ProgressBar

    private lateinit var configureBlockListBtn: Button
    private lateinit var refreshOnDeviceBlockListBtn: Button

    private lateinit var httpProxySwitch: SwitchMaterial
    private lateinit var httpProxyContainer: RelativeLayout
    private lateinit var httpProxyDescText: TextView
    private lateinit var httpProxyProgressBar: ProgressBar

    private var timeStamp : Long = 0L

    private var sock5Proxy: ProxyEndpoint? = null

    //For exclude apps dialog
    private var excludeAppAdapter: ExcludedAppListAdapter? = null
    private val excludeAppViewModel: ExcludedAppViewModel by viewModel()
    private lateinit var excludeListCountText: TextView

    private val refreshDatabase by inject<RefreshDatabase>()
    private lateinit var animation: Animation

    private lateinit var downloadManager: DownloadManager

    private val appInfoRepository by inject<AppInfoRepository>()
    private val proxyEndpointRepository by inject<ProxyEndpointRepository>()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val persistentState by inject<PersistentState>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view: View = inflater.inflate(R.layout.activity_settings_screen, container, false)
        initView(view)
        initClickListeners()
        return view
    }

    companion object {
        var enqueue: Long = 0
        var downloadInProgress = -1
        private const val FILE_LOG_TAG = "Settings"
    }

    private fun initView(view: View) {

        animation = RotateAnimation(
            0.0f, 360.0f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF,
            0.5f
        )
        animation.repeatCount = -1
        animation.duration = 1000

        faqTxt = view.findViewById(R.id.settings_app_faq_icon)

        refreshDataRL = view.findViewById(R.id.settings_activity_refresh_data_rl)
        refreshDataDescTxt = view.findViewById(R.id.settings_activity_refresh_desc)
        enableLogsRL = view.findViewById(R.id.settings_activity_enable_logs_rl)
        autoStartRL = view.findViewById(R.id.settings_activity_auto_start_rl)
        killAppRL = view.findViewById(R.id.settings_activity_kill_app_rl)
        allowByPassRL = view.findViewById(R.id.settings_activity_allow_bypass_rl)
        socks5RL = view.findViewById(R.id.settings_activity_socks5_rl)
        excludeAppsRL = view.findViewById(R.id.settings_activity_exclude_apps_rl)
        excludeListCountText = view.findViewById(R.id.settings_activity_exclude_apps_count_text)
        //blockUnknownConnRL = view.findViewById(R.id.settings_activity_block_unknown_rl)
        onDeviceBlockListRL = view.findViewById(R.id.settings_activity_on_device_block_rl)
        vpnSettingsHeadingTV = view.findViewById(R.id.settings_activity_vpn_heading_text)
        dnsSettingsHeading = view.findViewById(R.id.settings_heading_dns)

        vpnSettingsHeadingDesc = view.findViewById(R.id.settings_activity_vpn_lockdown_desc)

        refreshDataImg = view.findViewById(R.id.settings_activity_refresh_data_img)

        enableLogsSwitch = view.findViewById(R.id.settings_activity_enable_logs_switch)
        autoStartSwitch = view.findViewById(R.id.settings_activity_auto_start_switch)
        killAppSwitch = view.findViewById(R.id.settings_activity_kill_app_switch)
        allowByPassSwitch = view.findViewById(R.id.settings_activity_allow_bypass_switch)
        allowByPassDescText = view.findViewById(R.id.settings_activity_allow_bypass_desc)
        allowByPassProgressBar = view.findViewById(R.id.settings_activity_allow_bypass_progress)
        allowByPassProgressBar.visibility = View.GONE

        socks5Switch = view.findViewById(R.id.settings_activity_socks5_switch)
        socks5Progress = view.findViewById(R.id.settings_activity_socks5_progress)
        httpProxySwitch = view.findViewById(R.id.settings_activity_http_proxy_switch)
        httpProxyContainer = view.findViewById(R.id.settings_activity_http_proxy_container)
        excludeAppImg = view.findViewById(R.id.settings_activity_exclude_apps_img)
        //blockUnknownConnSwitch = view.findViewById(R.id.settings_activity_block_unknown_switch)
        onDeviceBlockListSwitch = view.findViewById(R.id.settings_activity_on_device_block_switch)
        onDeviceBlockListProgress = view.findViewById(R.id.settings_activity_on_device_block_progress)
        onDeviceBlockListDesc = view.findViewById(R.id.settings_activity_on_device_block_desc)
        onDeviceLastUpdatedTime = view.findViewById(R.id.settings_activity_on_device_last_updated_time_txt)

        socks5DescText = view.findViewById(R.id.settings_activity_socks5_desc)
        httpProxyDescText = view.findViewById(R.id.settings_activity_http_proxy_desc)
        httpProxyProgressBar = view.findViewById(R.id.settings_activity_http_proxy_progress)
        httpProxyProgressBar.visibility = View.GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            httpProxyContainer.visibility = View.VISIBLE
            httpProxySwitch.isChecked = persistentState.httpProxyEnabled
            if (httpProxySwitch.isChecked) {
                httpProxyDescText.text = "Forwarding to ${persistentState.httpProxyHostAddress}:${persistentState.httpProxyPort}"
            }
        } else {
            httpProxyContainer.visibility = View.GONE
        }
        allowByPassSwitch.isChecked = persistentState.allowByPass
        timeStamp = persistentState.localBlockListDownloadTime

        if(persistentState.downloadSource == DOWNLOAD_SOURCE_OTHERS){
            onDeviceBlockListRL.visibility = View.VISIBLE
            dnsSettingsHeading.visibility = View.VISIBLE
        }else{
            onDeviceBlockListRL.visibility = View.GONE
            dnsSettingsHeading.visibility = View.GONE
        }

        localDownloadComplete.observe(viewLifecycleOwner, {
            if (it == 1) {
                if(DEBUG) Log.d(LOG_TAG, "Observer log")
                downloadInProgress = 1
                configureBlockListBtn.visibility = View.VISIBLE
                onDeviceLastUpdatedTime.visibility = View.VISIBLE
                refreshOnDeviceBlockListBtn.visibility = View.VISIBLE
                onDeviceBlockListProgress.visibility = View.GONE
                onDeviceBlockListSwitch.visibility = View.VISIBLE
                onDeviceBlockListSwitch.isChecked = true
                onDeviceBlockListDesc.text = "Download completed, Configure blocklist"
                onDeviceLastUpdatedTime.text = "Version: v${Utilities.convertLongToDate(timeStamp)}"
                localDownloadComplete.postValue(0)
            }
        })

        HomeScreenActivity.GlobalVariable.braveModeToggler.observe(viewLifecycleOwner, {
            if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.DNS_MODE) {
                //disableDNSRelatedUI()
            } else if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.FIREWALL_MODE) {
                disableDNSRelatedUI()
            } else if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.DNS_FIREWALL_MODE) {
                enableDNSFirewallUI()
            }
        })

        configureBlockListBtn = view.findViewById(R.id.settings_activity_on_device_block_configure_btn)
        refreshOnDeviceBlockListBtn = view.findViewById(R.id.settings_activity_on_device_block_refresh_btn)

        sock5Proxy = proxyEndpointRepository.getConnectedProxy()

        enableLogsSwitch.isChecked = persistentState.logsEnabled
        autoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        killAppSwitch.isChecked = persistentState.killAppOnFirewall

        socks5Switch.isChecked = persistentState.socks5Enabled
        if (socks5Switch.isChecked) {
            val sock5Proxy = proxyEndpointRepository.getConnectedProxy()
            if (sock5Proxy?.proxyAppName != "Nobody") {
                val appName = appList[sock5Proxy?.proxyAppName]?.appName
                socks5DescText.text = "Forwarding to ${sock5Proxy!!.proxyIP}:${sock5Proxy.proxyPort}, $appName"
            } else {
                socks5DescText.text = "Forwarding to ${sock5Proxy.proxyIP}:${sock5Proxy.proxyPort}, Nobody"
            }
        }
        socks5Progress.visibility = View.GONE

        //blockUnknownConnSwitch.isChecked = persistentState.getBlockUnknownConnections(requireContext())
        if (persistentState.localBlocklistEnabled) {
            configureBlockListBtn.visibility = View.VISIBLE
            refreshOnDeviceBlockListBtn.visibility = View.VISIBLE
            onDeviceLastUpdatedTime.visibility = View.VISIBLE
            onDeviceLastUpdatedTime.text = "Version: v${Utilities.convertLongToDate(timeStamp)}"
            onDeviceBlockListSwitch.isChecked = true
        } else {
            configureBlockListBtn.visibility = View.GONE
            onDeviceLastUpdatedTime.visibility = View.GONE
            refreshOnDeviceBlockListBtn.visibility = View.GONE
            onDeviceBlockListSwitch.isChecked = false
            onDeviceBlockListDesc.text = "Choose from 170+ blocklists."
        }

        //For exclude apps
        excludeAppAdapter = ExcludedAppListAdapter(requireContext(), appInfoRepository, categoryInfoRepository)
        excludeAppViewModel.excludedAppList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(excludeAppAdapter!!::submitList))


        val appCount = appList.size
        val act: HomeScreenActivity = requireContext() as HomeScreenActivity
        appInfoRepository.getExcludedAppListCountLiveData().observe(act, {
            excludeListCountText.text = "$it/$appCount apps excluded."
        })


    }

    /**
     * Enabled all the layouts and change the labels
     * for the heading.
     */
    private fun enableDNSFirewallUI() {
        dnsSettingsHeading.text  = getString(R.string.app_mode_dns)
        onDeviceBlockListRL.isEnabled = true
        onDeviceBlockListSwitch.isEnabled = true
        refreshOnDeviceBlockListBtn.isEnabled = true
        configureBlockListBtn.isEnabled = true
    }

    /**
     * Disable all the layouts related with DNS
     */
    private fun disableDNSRelatedUI() {
        dnsSettingsHeading.text  = getString(R.string.app_mode_dns) + getString(R.string.features_disabled)
        onDeviceBlockListRL.isEnabled = false
        onDeviceBlockListSwitch.isEnabled = false
        refreshOnDeviceBlockListBtn.isEnabled = false
        configureBlockListBtn.isEnabled = false
    }

    /**
     * Disable all the layouts related to lockdown mode. like exclude apps and allow bypass
     */
    private fun disableForLockdownModeUI() {
        vpnSettingsHeadingTV.text = getString(R.string.settings_vpn_heading)+" " + getString(R.string.features_disabled)
        onDeviceBlockListRL.isEnabled = false
        excludeAppsRL.isEnabled = false
        allowByPassSwitch.isEnabled = false
        excludeAppImg.isEnabled = false
        vpnSettingsHeadingDesc.visibility = View.VISIBLE
    }

    private fun enableForLockdownModeUI(){
        vpnSettingsHeadingTV.text = getString(R.string.settings_vpn_heading)
        onDeviceBlockListRL.isEnabled = true
        excludeAppsRL.isEnabled = true
        allowByPassSwitch.isEnabled = true
        excludeAppImg.isEnabled = true
        vpnSettingsHeadingDesc.visibility = View.GONE
    }

    private fun detectLockDownMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val alwaysOn = android.provider.Settings.Secure.getString(requireContext().contentResolver, "always_on_vpn_app")
            val lockDown = android.provider.Settings.Secure.getInt(requireContext().contentResolver, "always_on_vpn_lockdown", 0)
            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isLockDownEnabled - $lockDown , $alwaysOn")
            if (lockDown != 0 && context?.packageName == alwaysOn) {
                disableForLockdownModeUI()
            } else {
                enableForLockdownModeUI()
            }
        }else{
            enableForLockdownModeUI()
        }

    }

    private fun initClickListeners() {
        refreshDataRL.setOnClickListener {
            refreshDatabase()
        }

        refreshDataImg.setOnClickListener {
            refreshDatabase()
        }

        enableLogsSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.logsEnabled = b
        }

        autoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.prefAutoStartBootUp = b
        }

        killAppSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.killAppOnFirewall = b
        }

        allowByPassSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.allowByPass = b
            object : CountDownTimer(100, 500) {
                override fun onTick(millisUntilFinished: Long) {
                    allowByPassSwitch.isEnabled = false
                    allowByPassSwitch.visibility = View.INVISIBLE
                    allowByPassProgressBar.visibility = View.VISIBLE
                }

                override fun onFinish() {
                    allowByPassSwitch.isEnabled = true
                    allowByPassProgressBar.visibility = View.GONE
                    allowByPassSwitch.visibility = View.VISIBLE
                }
            }.start()

        }

        onDeviceBlockListSwitch.setOnCheckedChangeListener(null)
        onDeviceBlockListSwitch.setOnClickListener{
            val isSelected = onDeviceBlockListSwitch.isChecked
            if(isSelected){
                onDeviceBlockListProgress.visibility = View.GONE
                if (!persistentState.blockListFilesDownloaded) {
                    showDownloadDialog()
                } else {
                    if (isSelected) {
                        setBraveDNSLocal()
                        val count = persistentState.numberOfLocalBlocklists
                        onDeviceBlockListDesc.text = "$count blocklists are in-use."
                    }
                }
            }else{
                removeBraveDNSLocal()
                refreshOnDeviceBlockListBtn.visibility = View.GONE
                configureBlockListBtn.visibility = View.GONE
                onDeviceLastUpdatedTime.visibility = View.GONE
                onDeviceBlockListProgress.visibility = View.GONE
                onDeviceBlockListDesc.text = "Choose from 170+ blocklists."
            }
        }

        socks5Switch.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
            persistentState.socks5Enabled = b
            if (b) {
                showDialogForSocks5Proxy()
            } else {
                socks5Switch.visibility = View.GONE
                socks5Progress.visibility = View.VISIBLE
                appMode?.setProxyMode(Settings.ProxyModeNone)
                //persistentState.setUDPBlockedSettings(requireContext(), false)
                socks5DescText.text = "Forward connections to SOCKS5 endpoint."
                socks5Switch.visibility = View.VISIBLE
                socks5Progress.visibility = View.GONE
            }
        }


        httpProxySwitch.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
            showDialogForHTTPProxy(b)
        }

        excludeAppImg.setOnClickListener {
            excludeAppImg.isEnabled = false
            showExcludeAppDialog(requireContext(), excludeAppAdapter!!, excludeAppViewModel)
            Handler().postDelayed({ excludeAppImg.isEnabled = true }, 100)
        }

        excludeAppsRL.setOnClickListener {
            excludeAppsRL.isEnabled = false
            showExcludeAppDialog(requireContext(), excludeAppAdapter!!, excludeAppViewModel)
            Handler().postDelayed({ excludeAppsRL.isEnabled = true }, 100)
        }

        faqTxt.setOnClickListener {
            startWebViewIntent()
        }

        configureBlockListBtn.setOnClickListener {
            val intent = Intent(requireContext(), DNSConfigureWebViewActivity::class.java)
            val stamp = persistentState.getLocalBlockListStamp()
            if (DEBUG) Log.d(LOG_TAG, "Stamp value in settings screen - $stamp")
            intent.putExtra("location", DNSConfigureWebViewActivity.LOCAL)
            intent.putExtra("stamp", stamp)
            (requireContext() as Activity).startActivityForResult(intent, Activity.RESULT_OK)
        }

        refreshOnDeviceBlockListBtn.setOnClickListener {
            checkForDownload(true)
        }

    }

    override fun onResume() {
        super.onResume()
        detectLockDownMode()
        if (persistentState.localBlocklistEnabled && persistentState.blockListFilesDownloaded && persistentState.getLocalBlockListStamp().isNullOrEmpty()) {
            onDeviceBlockListDesc.text = "Configure blocklists"
            onDeviceBlockListProgress.visibility = View.GONE
        } else if (downloadInProgress == 0) {
            onDeviceBlockListDesc.text = "Download in progress..."
            onDeviceBlockListSwitch.visibility = View.GONE
            onDeviceBlockListProgress.visibility = View.VISIBLE
        } else {
            onDeviceBlockListProgress.visibility = View.GONE
            val count = persistentState.numberOfLocalBlocklists
            if (count != 0) {
                onDeviceBlockListDesc.text = "$count blocklists in-use."
            }
        }
        val count = persistentState.numberOfLocalBlocklists
        if (count != 0 && persistentState.localBlocklistEnabled) {
            onDeviceBlockListDesc.text = "$count blocklists in-use."
        } else if (persistentState.localBlocklistEnabled) {
            onDeviceBlockListDesc.text = "No list configured."
        }
        if(!onDeviceBlockListSwitch.isChecked && downloadInProgress != 0){
            onDeviceBlockListDesc.text = "Choose from 170+ blocklists."
        }
    }

    private fun checkForDownload(isUserInitiated : Boolean): Boolean {
        if (timeStamp == 0L) {
            timeStamp = persistentState.localBlockListDownloadTime
        }
        val appVersionCode = persistentState.appVersion
        val url = "$REFRESH_BLOCKLIST_URL$timeStamp&${Constants.APPEND_VCODE}$appVersionCode"
        if(DEBUG) Log.d(LOG_TAG,"Check for local download, url - $url")
        run(url, isUserInitiated)
        return false
    }

    private fun run(url: String, isUserInitiated: Boolean) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(LOG_TAG, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                activity?.runOnUiThread {
                    configureBlockListBtn.visibility = View.GONE
                    onDeviceLastUpdatedTime.visibility = View.GONE
                    refreshOnDeviceBlockListBtn.visibility = View.GONE
                    onDeviceBlockListProgress.visibility = View.GONE
                    onDeviceBlockListSwitch.visibility = View.VISIBLE
                    onDeviceBlockListSwitch.isChecked = false
                    onDeviceBlockListDesc.text = "Error downloading file. Try again."
                    downloadInProgress = -1
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val stringResponse = response.body!!.string()
                    //creating json object
                    val jsonObject = JSONObject(stringResponse)
                    val version = jsonObject.getInt("version")
                    if (DEBUG) Log.d(LOG_TAG, "client onResponse for refresh blocklist files-  $version")
                    if (version == 1) {
                        val updateValue = jsonObject.getBoolean("update")
                        timeStamp = jsonObject.getLong("latest")
                        if (DEBUG) Log.d(LOG_TAG, "onResponse -  $updateValue")
                        if (updateValue) {
                            persistentState.localBlockListDownloadTime = timeStamp
                            activity?.runOnUiThread {
                                registerReceiverForDownloadManager()
                                handleDownloadFiles()
                            }
                        } else {
                            activity?.runOnUiThread {
                                if (isUserInitiated) {
                                    Utilities.showToastInMidLayout(activity as Context, "Blocklists are up-to-date.", Toast.LENGTH_SHORT)
                                } else {
                                    configureBlockListBtn.visibility = View.GONE
                                    onDeviceLastUpdatedTime.visibility = View.GONE
                                    refreshOnDeviceBlockListBtn.visibility = View.GONE
                                    onDeviceBlockListProgress.visibility = View.GONE
                                    onDeviceBlockListSwitch.visibility = View.VISIBLE
                                    onDeviceBlockListSwitch.isChecked = false
                                    onDeviceBlockListDesc.text = "Error downloading file. Try again."
                                    downloadInProgress = -1
                                    timeStamp = 0
                                    persistentState.localBlockListDownloadTime = 0
                                    Utilities.showToastInMidLayout(activity as Context, "Error downloading file. Try again later.", Toast.LENGTH_SHORT)
                                }
                            }
                        }
                    }
                    response.body!!.close()
                    client.connectionPool.evictAll()
                } catch (e: java.lang.Exception) {
                    Log.w(LOG_TAG,"Exception while downloading: ${e.message}",e)
                    configureBlockListBtn.visibility = View.GONE
                    onDeviceLastUpdatedTime.visibility = View.GONE
                    refreshOnDeviceBlockListBtn.visibility = View.GONE
                    onDeviceBlockListProgress.visibility = View.GONE
                    onDeviceBlockListSwitch.visibility = View.VISIBLE
                    onDeviceBlockListSwitch.isChecked = false
                    downloadInProgress = -1
                    timeStamp = 0
                    persistentState.localBlockListDownloadTime = 0
                    onDeviceBlockListDesc.text = "Error downloading file. Try again."
                }
            }
        })
    }


    private fun showDialogForHTTPProxy(isEnabled: Boolean) {
        if (isEnabled) {
            var isValid = true
            var host: String = ""
            var port: Int = 0
            val dialog = Dialog(requireContext())
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setTitle("Custom Server URL")
            dialog.setContentView(R.layout.dialog_set_http_proxy)

            val lp = WindowManager.LayoutParams()
            lp.copyFrom(dialog.window!!.attributes)
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            dialog.show()
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.window!!.attributes = lp

            val applyURLBtn = dialog.findViewById(R.id.dialog_http_proxy_ok_btn) as AppCompatButton
            val cancelURLBtn = dialog.findViewById(R.id.dialog_http_proxy_cancel_btn) as AppCompatButton
            val hostAddressEditText = dialog.findViewById(R.id.dialog_http_proxy_edit_text) as TextInputEditText
            val portEditText: EditText = dialog.findViewById(R.id.dialog_http_proxy_edit_text_port) as TextInputEditText
            val errorTxt: TextView = dialog.findViewById(R.id.dialog_http_proxy_failure_text) as TextView


            val hostName = persistentState.httpProxyHostAddress
            val portAddr = persistentState.httpProxyPort
            if (!hostName.isNullOrEmpty()) {
                hostAddressEditText.setText(hostName, TextView.BufferType.EDITABLE)
            }
            if (portAddr != 0) {
                portEditText.setText(portAddr.toString(), TextView.BufferType.EDITABLE)
            } else {
                portEditText.setText("8118", TextView.BufferType.EDITABLE)
            }
            applyURLBtn.setOnClickListener {
                host = hostAddressEditText.text.toString()
                /*val validHostnameRegex = "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$".toRegex()
                if (ip.matches(validHostnameRegex)) {
                    isValid = true
                } else {
                    errorTxt.visibility = View.VISIBLE
                    errorTxt.setText("Invalid host")
                    isValid = false
                }*/
                var isHostValid = true
                try {
                    port = portEditText.text.toString().toInt()
                    if (port in 65535 downTo 1024) {
                        isValid = true
                    } else {
                        errorTxt.text = "Port range should be from 1024-65535"
                        errorTxt.visibility = View.VISIBLE
                        isValid = false
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error: ${e.message}", e)
                    errorTxt.text = "Invalid port"
                    errorTxt.visibility = View.VISIBLE
                    isValid = false
                }

                if (host.isEmpty() || host.isBlank()) {
                    isHostValid = false
                    errorTxt.text = "Hostname is empty"
                    errorTxt.visibility = View.VISIBLE
                }

                if (isValid && isHostValid) {
                    httpProxyProgressBar.visibility = View.VISIBLE
                    httpProxySwitch.visibility = View.GONE
                    errorTxt.visibility = View.INVISIBLE
                    persistentState.httpProxyHostAddress = host
                    persistentState.httpProxyPort = port
                    persistentState.httpProxyEnabled = true
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "HTTP proxy is set", Toast.LENGTH_SHORT).show()
                    if (httpProxySwitch.isChecked) {
                        httpProxyDescText.text = "Forwarding to $host:$port"
                    }
                    httpProxyProgressBar.visibility = View.GONE
                    httpProxySwitch.visibility = View.VISIBLE
                }
            }

            cancelURLBtn.setOnClickListener {
                dialog.dismiss()
                if (DEBUG) Log.d(LOG_TAG, "HTTP IsSelected is false")
                persistentState.httpProxyEnabled = false
                httpProxyDescText.text = "This proxy is only a recomendation and it is possible that some apps will ignore it."
                httpProxySwitch.isChecked = false
            }
        } else {
            if (DEBUG) Log.d(LOG_TAG, "HTTP IsSelected is false")
            persistentState.httpProxyEnabled = false
            httpProxySwitch.isChecked = false
            httpProxyDescText.text = "This proxy is only a recommendation and it is possible that some apps will ignore it."
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Activity.RESULT_OK) {
            val stamp = data?.getStringExtra("stamp")
            Log.d(LOG_TAG, "onActivityResult - stamp from webview - $stamp")
            setBraveDNSLocal()
        }
    }

    private fun setBraveDNSLocal() {
        configureBlockListBtn.visibility = View.VISIBLE
        refreshOnDeviceBlockListBtn.visibility = View.VISIBLE
        onDeviceLastUpdatedTime.visibility = View.VISIBLE
        onDeviceLastUpdatedTime.text = "Version: v${Utilities.convertLongToDate(timeStamp)}"
        val path: String = requireContext().filesDir.canonicalPath
        if (appMode?.getBraveDNS() == null) {
            GlobalScope.launch(Dispatchers.IO) {
                if (DEBUG) Log.d(LOG_TAG, "Local brave dns set call from settings fragment")
                val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE, path + Constants.FILE_RD_FILE, path + Constants.FILE_BASIC_CONFIG, path + Constants.FILE_TAG_NAME)
                appMode?.setBraveDNSMode(braveDNS)
                persistentState.localBlocklistEnabled = true
            }
        }
    }

    private fun removeBraveDNSLocal() {
        appMode?.setBraveDNSMode(null)
        persistentState.localBlocklistEnabled = false
    }


    private fun showDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.local_blocklist_download)
        //set message for alert dialog
        builder.setMessage(R.string.local_blocklist_download_desc)
        builder.setCancelable(false)
        //performing positive action
        builder.setPositiveButton("Download") { dialogInterface, which ->
            downloadInProgress = 0
            onDeviceBlockListDesc.text = "Download in progress..."
            onDeviceBlockListSwitch.visibility = View.GONE
            onDeviceBlockListProgress.visibility = View.VISIBLE

            checkForDownload(false)
            //downloadLocalBlocklistFiles()
        }

        //performing negative action
        builder.setNegativeButton("Cancel") { dialogInterface, which ->
            onDeviceBlockListSwitch.isChecked = false
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        //alertDialog.setCancelable(true)
        alertDialog.show()

    }

    private fun refreshDatabase() {
        refreshDataImg.animation = animation
        refreshDataImg.startAnimation(animation)
        object : CountDownTimer(5000, 500) {
            override fun onTick(millisUntilFinished: Long) {
                refreshDataDescTxt.text = "Resync in progress..."
            }

            override fun onFinish() {
                refreshDataImg.clearAnimation()
                refreshDataDescTxt.text = "Resync completed"
            }
        }.start()

        refreshDatabase.refreshAppInfoDatabase()
        //refreshDatabase.updateCategoryInDB()
    }

    private fun registerReceiverForDownloadManager() {
        requireContext().registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun handleDownloadFiles() {
        downloadManager = requireContext().getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager
        if(timeStamp == 0L) {
            timeStamp = persistentState.localBlockListDownloadTime
        }
        val url = Constants.JSON_DOWNLOAD_BLOCKLIST_LINK + "/" + timeStamp
        downloadBlockListFiles(url, Constants.FILE_TAG_NAME, requireContext())
    }

    private fun downloadBlockListFiles(url: String, fileName: String, context: Context) {
        try {
            if(DEBUG) Log.d(LOG_TAG,"downloadBlockListFiles - url: $url")
            val uri: Uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
            request.setTitle("RethinkDNS Blocklists")
            request.setDescription("$fileName download in progress..")
            request.setDestinationInExternalFilesDir(context, Constants.DOWNLOAD_PATH, fileName)
            Log.d(LOG_TAG, "Path - ${context.filesDir.canonicalPath}${Constants.DOWNLOAD_PATH}${fileName}")
            enqueue = downloadManager.enqueue(request)
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG, "Download unsuccessful - ${e.message}", e)
            downloadInProgress = -1
        }
    }


    private var onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {
            if (DEBUG) Log.d(LOG_TAG, "Intent on receive ")
            try {
                val action = intent.action
                if (DEBUG) Log.d(LOG_TAG, "Download status: $action")
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                    if (DEBUG) Log.d(LOG_TAG, "Download status: $action")
                    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)
                    val query = DownloadManager.Query()
                    query.setFilterById(enqueue)
                    val c: Cursor = downloadManager.query(query)
                    if (c.moveToFirst()) {
                        val status = checkStatus(c)
                        if (DEBUG) Log.d(LOG_TAG, "Download status: $status,$filesDownloaded, $downloadId")
                        if (status == "STATUS_SUCCESSFUL") {
                            filesDownloaded += 1
                            if (filesDownloaded == 1) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_TAG_NAME)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_TAG_NAME)
                                from.copyTo(to, true)
                                if(timeStamp == 0L) {
                                    timeStamp = persistentState.localBlockListDownloadTime
                                }
                                val url = Constants.JSON_DOWNLOAD_BASIC_CONFIG_LINK + "/" + timeStamp
                                if(DEBUG) Log.d(LOG_TAG,"Check for local download, url - $url")
                                downloadBlockListFiles(url, Constants.FILE_BASIC_CONFIG, ctxt)
                            } else if (filesDownloaded == 2) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_BASIC_CONFIG)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_BASIC_CONFIG)
                                from.copyTo(to, true)
                                if (timeStamp == 0L) {
                                    timeStamp = persistentState.localBlockListDownloadTime
                                }
                                val url = Constants.JSON_DOWNLOAD_BASIC_RANK_LINK + "/" + timeStamp
                                if(DEBUG) Log.d(LOG_TAG,"Check for local download, url - $url")
                                downloadBlockListFiles(url, Constants.FILE_RD_FILE, ctxt)
                            } else if (filesDownloaded == 3) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_RD_FILE)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_RD_FILE)
                                from.copyTo(to, true)
                                if (timeStamp == 0L) {
                                    timeStamp = persistentState.localBlockListDownloadTime
                                }
                                val url = Constants.JSON_DOWNLOAD_BASIC_TRIE_LINK + "/" + timeStamp
                                if(DEBUG) Log.d(LOG_TAG,"Check for local download, url - $url")
                                downloadBlockListFiles(url, Constants.FILE_TD_FILE, ctxt)
                            } else if (filesDownloaded == 4) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_TD_FILE)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_TD_FILE)
                                val downloadedFile = from.copyTo(to, true)
                                if(downloadedFile.exists()) {
                                    Utilities.deleteOldFiles(ctxt)
                                }
                                persistentState.blockListFilesDownloaded = true
                                persistentState.localBlocklistEnabled = true
                                //persistentState.setLocalBlockListDownloadTime(ctxt, System.currentTimeMillis())
                                localDownloadComplete.postValue(1)
                                downloadInProgress = 1
                                configureBlockListBtn.visibility = View.VISIBLE
                                refreshOnDeviceBlockListBtn.visibility = View.VISIBLE
                                onDeviceBlockListProgress.visibility = View.GONE
                                onDeviceBlockListSwitch.visibility = View.VISIBLE
                                onDeviceLastUpdatedTime.visibility = View.VISIBLE
                                onDeviceLastUpdatedTime.text = "Version: v${Utilities.convertLongToDate(timeStamp)}"
                                onDeviceBlockListDesc.text = "Download completed, Configure blocklist"
                                if (DEBUG) Log.d(LOG_TAG, "Download status : Download completed: $status")
                                Toast.makeText(ctxt, "Blocklists downloaded successfully.", Toast.LENGTH_LONG).show()
                            } else {
                                //Toast.makeText(ctxt, "Download complete", Toast.LENGTH_LONG).show()
                                configureBlockListBtn.visibility = View.VISIBLE
                                onDeviceLastUpdatedTime.visibility = View.VISIBLE
                                refreshOnDeviceBlockListBtn.visibility = View.VISIBLE
                                onDeviceBlockListProgress.visibility = View.GONE
                                onDeviceBlockListSwitch.visibility = View.VISIBLE
                                onDeviceLastUpdatedTime.text = "Version: v${Utilities.convertLongToDate(timeStamp)}"
                            }
                        } else {
                            if (DEBUG) Log.d(LOG_TAG, "Download failed: $enqueue, $action, $downloadId")
                            configureBlockListBtn.visibility = View.GONE
                            onDeviceLastUpdatedTime.visibility = View.GONE
                            refreshOnDeviceBlockListBtn.visibility = View.GONE
                            onDeviceBlockListProgress.visibility = View.GONE
                            onDeviceBlockListSwitch.visibility = View.VISIBLE
                            onDeviceBlockListSwitch.isChecked = false
                            onDeviceBlockListDesc.text = "Error downloading file. Try again."
                            downloadInProgress = -1
                            timeStamp = 0
                            downloadManager.remove(downloadId)
                            persistentState.localBlockListDownloadTime = 0L
                            persistentState.blockListFilesDownloaded = false
                            persistentState.localBlocklistEnabled = false
                        }
                    } else {
                        if (DEBUG) Log.d(LOG_TAG, "Download failed: $enqueue, $action")
                        configureBlockListBtn.visibility = View.GONE
                        onDeviceLastUpdatedTime.visibility = View.GONE
                        refreshOnDeviceBlockListBtn.visibility = View.GONE
                        onDeviceBlockListProgress.visibility = View.GONE
                        onDeviceBlockListSwitch.visibility = View.VISIBLE
                        onDeviceBlockListSwitch.isChecked = false
                        onDeviceBlockListDesc.text = "Error downloading file. Try again."
                        downloadInProgress= -1
                        filesDownloaded = 0
                        timeStamp = 0
                        persistentState.localBlockListDownloadTime = 0L
                        persistentState.blockListFilesDownloaded = false
                        persistentState.localBlocklistEnabled = false
                    }
                    c.close()
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG,"Exception while downloading: ${e.message}",e)
                onDeviceBlockListDesc.text = "Error downloading file. Try again."
                configureBlockListBtn.visibility = View.GONE
                onDeviceLastUpdatedTime.visibility = View.GONE
                refreshOnDeviceBlockListBtn.visibility = View.GONE
                onDeviceBlockListProgress.visibility = View.GONE
                onDeviceBlockListSwitch.visibility = View.VISIBLE
                onDeviceBlockListSwitch.isChecked = false
                downloadInProgress = -1
                timeStamp = 0
                filesDownloaded = 0
                persistentState.localBlockListDownloadTime = 0L
                persistentState.blockListFilesDownloaded = false
                persistentState.localBlocklistEnabled = false
            }
        }
    }

    private fun startWebViewIntent() {
        val intent = Intent(requireContext(), FaqWebViewActivity::class.java)
        startActivity(intent)
    }


    private fun showExcludeAppDialog(context: Context, recyclerAdapter: ExcludedAppListAdapter, excludeAppViewModel: ExcludedAppViewModel) {
        val excludeAppDialog = ExcludeAppDialog(context, get(), get(), persistentState, recyclerAdapter, excludeAppViewModel)
        //if we know that the particular variable not null any time ,we can assign !! (not null operator ),
        // then  it won't check for null, if it becomes null, it will throw exception
        excludeAppDialog.show()
        excludeAppDialog.setCanceledOnTouchOutside(false)
    }

    private fun showDialogForSocks5Proxy() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_set_proxy)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp

        val applyURLBtn = dialog.findViewById(R.id.dialog_proxy_apply_btn) as AppCompatButton
        val cancelURLBtn = dialog.findViewById(R.id.dialog_proxy_cancel_btn) as AppCompatButton
        //val proxyNameEditText = dialog.findViewById(R.id.dialog_proxy_edit_name) as EditText
        val ipAddressEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_ip)
        val portEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_port)
        val appNameSpinner: Spinner = dialog.findViewById(R.id.dialog_proxy_spinner_appname)
        val errorTxt: TextView = dialog.findViewById(R.id.dialog_proxy_error_text) as TextView
        val llSpinnerHeader: LinearLayout = dialog.findViewById(R.id.dialog_proxy_spinner_header)
        val llIPHeader: LinearLayout = dialog.findViewById(R.id.dialog_proxy_ip_header)
        val userNameEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_username)
        val passwordEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_password)
        val udpBlockCheckBox: CheckBox = dialog.findViewById(R.id.dialog_proxy_udp_check)

        val sock5Proxy = proxyEndpointRepository.getConnectedProxy()

        udpBlockCheckBox.isChecked = persistentState.udpBlockedSettings

        val appNames: MutableList<String> = ArrayList()
        appNames.add("Nobody")
        appNames.addAll(getAppName())
        val proxySpinnerAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, appNames
        )
        appNameSpinner.adapter = proxySpinnerAdapter
        if (sock5Proxy != null && !sock5Proxy.proxyIP.isNullOrEmpty()) {
            ipAddressEditText.setText(sock5Proxy.proxyIP, TextView.BufferType.EDITABLE)
            portEditText.setText(sock5Proxy.proxyPort.toString(), TextView.BufferType.EDITABLE)
            userNameEditText.setText(sock5Proxy.userName.toString(), TextView.BufferType.EDITABLE)
            if (sock5Proxy.proxyAppName?.isNotEmpty()!! && sock5Proxy.proxyAppName != "Nobody") {
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
            ipAddressEditText.setText("127.0.0.1", TextView.BufferType.EDITABLE)
            portEditText.setText("9050", TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var ip: String = ""
            var appName: String = ""
            var port: Int = 0
            var mode: String = ""
            var userName: String = ""
            var password: String = ""
            var isValid = true
            var isIPValid = true
            var isUDPBlock = false
            var appPackageName = ""
            mode = "External"
            appName = appNames[appNameSpinner.selectedItemPosition]
            if (appName.isEmpty() || appName == "Nobody") {
                appPackageName = appNames[0]
            } else {
                appPackageName = appInfoRepository.getPackageNameForAppName(appName)
            }
            ip = ipAddressEditText.text.toString()

            if (ip.isEmpty() || ip.isBlank()) {
                isIPValid = false
                errorTxt.text = "Hostname is empty"
                errorTxt.visibility = View.VISIBLE
            }


            try {
                port = portEditText.text.toString().toInt()
                if (Utilities.isIPLocal(ip)) {
                    if (port in 65535 downTo 1024) {
                        isValid = true
                    } else {
                        errorTxt.text = "Port range should be from 1024-65535"
                        isValid = false
                    }
                } else {
                    isValid = true
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error: ${e.message}", e)
                errorTxt.setText("Invalid port")
                isValid = false
            }
            Log.d(LOG_TAG, "Pattern not matching - port- $port , ${portEditText.text}")
            if (udpBlockCheckBox.isChecked) {
                isUDPBlock = true
            }

            userName = userNameEditText.text.toString()
            password = passwordEditText.text.toString()
            if (isValid && isIPValid) {
                //Do the Socks5 Proxy setting there
                persistentState.udpBlockedSettings = udpBlockCheckBox.isChecked
                insertProxyEndpointDB(mode, "Socks5", appPackageName, ip, port, userName, password, isUDPBlock)
                socks5DescText.text = "Forwarding to ${ip}:${port}, $appName"
                dialog.dismiss()
            }
        }

        cancelURLBtn.setOnClickListener {
            socks5Switch.isChecked = false
            appMode?.setProxyMode(Settings.ProxyModeNone)
            socks5DescText.text = "Forward connections to SOCKS5 endpoint."
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
            if (mode == "Internal") {
                proxyName = appName
            } else
                proxyName = ip
        }
        Log.d(LOG_TAG, "Pattern matching 1- $appName")
        //id: Int, proxyName: String,  proxyType: String, proxyAppName: String, proxyIP: String,proxyPort : Int, isSelected: Boolean, isCustom: Boolean, modifiedDataTime: Long, latency: Int
        val proxyEndpoint = ProxyEndpoint(-1, proxyName, 1, mode, appName, ip, port, userName, password, true, true, isUDPBlock, 0L, 0)
        proxyEndpointRepository.clearAllData()
        proxyEndpointRepository.insertAsync(proxyEndpoint)
        object : CountDownTimer(1000, 500) {
            override fun onTick(millisUntilFinished: Long) {
                socks5Switch.isEnabled = false
                socks5Switch.visibility = View.GONE
                socks5Progress.visibility = View.VISIBLE
            }

            override fun onFinish() {
                appMode?.setProxyMode(Settings.ProxyModeSOCKS5)
                socks5Switch.isEnabled = true
                socks5Progress.visibility = View.GONE
                socks5Switch.visibility = View.VISIBLE
            }
        }.start()

        //removeConnections()
    }

}
