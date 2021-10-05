/*
 * Copyright 2021 RethinkDNS and its authors
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
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentDnsConfigureBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_2
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.Companion.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import org.koin.android.ext.android.inject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DNSConfigureFragment : Fragment(R.layout.fragment_dns_configure) {
    private val b by viewBinding(FragmentDnsConfigureBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()
    private val appConfig by inject<AppConfig>()

    companion object {
        fun newInstance() = DNSConfigureFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
    }

    private fun initView() {
        // display fav icon in dns logs
        b.dcFavIconSwitch.isChecked = persistentState.fetchFavIcon
        // prevent dns leaks
        b.dcPreventDnsLeaksSwitch.isChecked = persistentState.preventDnsLeaks
    }

    private fun initObservers() {
        observeBraveMode()
        observeWorkManager()
    }

    private fun observeWorkManager() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        workManager.getWorkInfosByTagLiveData(DownloadConstants.DOWNLOAD_TAG).observe(
            viewLifecycleOwner, { workInfoList ->
                val workInfo = workInfoList?.getOrNull(0) ?: return@observe
                Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                      "WorkManager state: ${workInfo.state} for ${DownloadConstants.DOWNLOAD_TAG}")
                if (WorkInfo.State.ENQUEUED == workInfo.state || WorkInfo.State.RUNNING == workInfo.state) {
                    onDownloadStart()
                } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                    onDownloadAbort()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(DownloadConstants.DOWNLOAD_TAG)
                    workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
                }
            })

        workManager.getWorkInfosByTagLiveData(DownloadConstants.FILE_TAG).observe(
            viewLifecycleOwner, { workInfoList ->
                val workInfo = workInfoList?.getOrNull(0) ?: return@observe
                Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                      "WorkManager state: ${workInfo.state} for ${DownloadConstants.FILE_TAG}")
                if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                    onDownloadSuccess()
                    workManager.pruneWork()
                } else if (WorkInfo.State.CANCELLED == workInfo.state || WorkInfo.State.FAILED == workInfo.state) {
                    onDownloadAbort()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
                } else { // state == blocked, queued, or running
                    // no-op
                }
            })
    }

    private fun observeBraveMode() {
        appConfig.braveModeObserver.observe(viewLifecycleOwner, {
            when (it) {
                // TODO: disable local-blocklist for dns-only mode
                // TODO: disable prevent dns leaks in dns-only mode
            }
        })
    }

    private fun initClickListeners() {

        b.dcCustomBlocklistCard.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcCustomBlocklist)

            val intent = Intent(requireContext(), CustomDomainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            startActivity(intent)
        }

        b.dcMoreDnsCard.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcMoreDns)

            val intent = Intent(requireContext(), DNSListActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            startActivity(intent)
        }

        b.dcOnDeviceBlockDownloadBtn.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcOnDeviceBlockUpdateBtn)

            go {
                uiCtx {

                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist) {
                        setBraveDNSLocal() // TODO: Move this to vpnService observer
                        b.dcOnDeviceBlockDesc.text = getString(
                            R.string.settings_local_blocklist_in_use,
                            persistentState.numberOfLocalBlocklists.toString())
                    } else {
                        if (VpnController.isVpnLockdown()) {
                            showVpnLockdownDownloadDialog()
                        } else {
                            showDownloadDialog()
                        }
                    }
                }
            }
        }

        b.dcOnDeviceBlockEnableBtn.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcOnDeviceBlockEnableBtn)

            if (persistentState.blocklistEnabled) {
                removeBraveDNSLocal()
                return@setOnClickListener
            }
            go {
                uiCtx {
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist) {
                        setBraveDNSLocal() // TODO: Move this to vpnService observer
                        b.dcOnDeviceBlockDesc.text = getString(
                            R.string.settings_local_blocklist_in_use,
                            persistentState.numberOfLocalBlocklists.toString())
                    }
                }
            }
        }

        b.dcOnDeviceBlockConfigureBtn.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcOnDeviceBlockConfigureBtn)

            val intent = Intent(requireContext(), DNSConfigureWebViewActivity::class.java)
            val stamp = persistentState.localBlocklistStamp
            if (DEBUG) Log.d(LOG_TAG_VPN, "Stamp value in settings screen: $stamp")
            intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            intent.putExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA,
                            DNSConfigureWebViewActivity.LOCAL)
            intent.putExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA, stamp)
            requireContext().startActivity(intent)
        }

        b.dcOnDeviceBlockUpdateBtn.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcOnDeviceBlockUpdateBtn)

            updateBlocklistIfNeeded(isRefresh = true)
        }

        b.dcFavIconSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcFavIconSwitch)
            persistentState.fetchFavIcon = enabled
        }

        b.dcPreventDnsLeaksSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcPreventDnsLeaksSwitch)
            persistentState.preventDnsLeaks = enabled
        }
    }

    // FIXME: Verification of BraveDns object should be added in future.
    private fun setBraveDNSLocal() {
        persistentState.blocklistEnabled = true
        refreshOnDeviceBlocklistUi()
    }

    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            // modify the desc text for local blocklist on lockdown if not downloaded
        } else {

        }
        // TODO: This is valid only when the local blocklist is not downloaded
        b.dcOnDeviceBlockDownloadBtn.isEnabled = !isLockdown
    }

    private fun showVpnLockdownDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.download_lockdown_dialog_heading)
        builder.setMessage(R.string.download_lockdown_dialog_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.download_lockdown_dialog_positive)) { _, _ ->
            openVpnProfile(requireContext())
        }
        builder.setNegativeButton(
            getString(R.string.download_lockdown_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun showDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_download)
        builder.setMessage(R.string.local_blocklist_download_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.settings_local_blocklist_dialog_positive)) { _, _ ->
            updateBlocklistIfNeeded(isRefresh = false)
        }
        builder.setNegativeButton(
            getString(R.string.settings_local_blocklist_dialog_negative)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun showRedownloadDialogLockdown(timestamp: Long) {
        uithread(activity) {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(R.string.local_blocklist_lockdown_redownload)
            builder.setMessage(getString(R.string.local_blocklist_lockdown_redownload_desc,
                                         Utilities.convertLongToTime(timestamp, TIME_FORMAT_2)))
            builder.setCancelable(false)
            builder.setPositiveButton(
                getString(R.string.local_blocklist_lockdown_positive)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            builder.create().show()
        }
    }

    private fun showRedownloadDialog(timestamp: Long) {
        uithread(activity) {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(R.string.local_blocklist_redownload)
            builder.setMessage(getString(R.string.local_blocklist_redownload_desc,
                                         Utilities.convertLongToTime(timestamp, TIME_FORMAT_2)))
            builder.setCancelable(false)
            builder.setPositiveButton(
                getString(R.string.local_blocklist_positive)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            builder.setNeutralButton(getString(R.string.local_blocklist_neutral)) { _, _ ->
                onDownloadStart()
                appDownloadManager.downloadLocalBlocklist(timestamp)
            }
            builder.create().show()
        }
    }

    private fun showFileCorruptionDialog(timestamp: Long) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.local_blocklist_corrupt)
        builder.setMessage(R.string.local_blocklist_corrupt_desc)
        builder.setCancelable(false)
        builder.setNegativeButton(
            getString(R.string.local_blocklist_corrupt_negative)) { dialogInterface, _ ->
            handleFailedDownload(isRefresh = false)
            dialogInterface.dismiss()
        }
        builder.setPositiveButton(getString(R.string.local_blocklist_corrupt_positive)) { _, _ ->
            appDownloadManager.downloadLocalBlocklist(timestamp)
        }
        builder.create().show()
    }

    private fun refreshOnDeviceBlocklistUi() {
        if (isPlayStoreFlavour()) { // hide the parent view
            b.dcOnDeviceBlockCard.visibility = View.GONE
            return
        }

        b.dcOnDeviceBlockCard.visibility = View.VISIBLE
        // this switch is hidden to show the download-progress bar,
        // enable it whenever the download is complete
        if (persistentState.blocklistEnabled) {
            b.dcOnDeviceBlockEnableBtn.text = getString(R.string.dc_on_device_block_btn_active)
            b.dcOnDeviceBlockEnableBtn.visibility = View.VISIBLE
            b.dcOnDeviceBlockDownloadBtn.visibility = View.GONE
            b.dcOnDeviceBlockProgress.visibility = View.GONE
            b.dcOnDeviceConfigureRl.visibility = View.VISIBLE
        } else {
            isBlocklistFilesAvailable()
        }

        refreshOnDeviceBlocklistStatus()
    }

    private fun isBlocklistFilesAvailable() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.Default) {
                    hasLocalBlocklists(requireContext(), persistentState.localBlocklistTimestamp)
                }
                b.dcOnDeviceConfigureRl.visibility = View.GONE
                if (blocklistsExist) {
                    b.dcOnDeviceBlockEnableBtn.text = getString(
                        R.string.dc_on_device_block_btn_inactive)
                    b.dcOnDeviceBlockEnableBtn.visibility = View.VISIBLE
                    b.dcOnDeviceBlockDownloadBtn.visibility = View.GONE
                } else {
                    b.dcOnDeviceBlockEnableBtn.visibility = View.GONE
                    b.dcOnDeviceBlockVersion.visibility = View.GONE
                    b.dcOnDeviceBlockDownloadBtn.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun refreshOnDeviceBlocklistStatus() {
        if (!persistentState.blocklistEnabled) {
            b.dcOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc_1)
            return
        }

        b.dcOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_in_use,
                                               persistentState.numberOfLocalBlocklists.toString())

        b.dcOnDeviceBlockVersion.visibility = View.VISIBLE
        b.dcOnDeviceBlockVersion.text = getString(R.string.settings_local_blocklist_version,
                                                  Utilities.convertLongToTime(
                                                      persistentState.localBlocklistTimestamp,
                                                      TIME_FORMAT_2))
    }

    private fun updateBlocklistIfNeeded(isRefresh: Boolean) {
        val timestamp = persistentState.localBlocklistTimestamp
        val appVersionCode = persistentState.appVersion
        val url = "${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_URL}$timestamp&${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_PARAMETER_VCODE}$appVersionCode"
        if (DEBUG) Log.d(LoggerConstants.LOG_TAG_DOWNLOAD, "Check for local download, url: $url")
        downloadBlocklistIfNeeded(url, isRefresh)
    }

    private fun downloadBlocklistIfNeeded(url: String, isRefresh: Boolean) {

        val bootstrapClient = OkHttpClient()
        // FIXME: Use user set doh provider
        // using quad9 doh provider
        val dns = DnsOverHttps.Builder().client(bootstrapClient).url(
            "https://dns.quad9.net/dns-query".toHttpUrl()).bootstrapDnsHosts(
            InetAddress.getByName("9.9.9.9"), InetAddress.getByName("149.112.112.112"),
            InetAddress.getByName("2620:fe::9"), InetAddress.getByName("2620:fe::fe")).build()

        val client = bootstrapClient.newBuilder().dns(
            dns).build() // FIXME: Move it to the http-request-helper class
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                      "onFailure, cancelled? ${call.isCanceled()}, exec? ${call.isExecuted()}")
                handleFailedDownload(isRefresh)
            }

            override fun onResponse(call: Call, response: Response) {
                val stringResponse = response.body?.string() ?: return
                response.body?.close()

                val json = JSONObject(stringResponse)
                val version = json.optInt(Constants.JSON_VERSION, 0)
                if (DEBUG) Log.d(LoggerConstants.LOG_TAG_DOWNLOAD,
                                 "client onResponse for refresh blocklist files:  $version")
                if (version != Constants.UPDATE_CHECK_RESPONSE_VERSION) {
                    return
                }

                val shouldUpdate = json.optBoolean(Constants.JSON_UPDATE, false)
                val timestamp = json.optLong(Constants.JSON_LATEST, INIT_TIME_MS)
                if (DEBUG) Log.d(LoggerConstants.LOG_TAG_DOWNLOAD,
                                 "onResponse:  update? $shouldUpdate")
                if (shouldUpdate) {
                    appDownloadManager.downloadLocalBlocklist(timestamp)
                    return
                }

                handleRedownload(isRefresh, timestamp)
            }
        })
    }

    private fun handleFailedDownload(isRefresh: Boolean) {
        uithread(activity) {
            if (isRefresh) {
                Utilities.showToastUiCentered(requireContext(), getString(
                    R.string.local_blocklist_update_check_failure), Toast.LENGTH_SHORT)
            } else {
                onDownloadAbort()
            }
        }
    }

    private fun handleRedownload(isRefresh: Boolean, timestamp: Long) {
        if (!isRefresh) {
            showFileCorruptionDialog(timestamp)
            return
        }

        if (VpnController.isVpnLockdown()) {
            showRedownloadDialogLockdown(timestamp)
        } else {
            showRedownloadDialog(timestamp)
        }
    }

    private fun onDownloadAbort() {
        uithread(activity) {
            refreshOnDeviceBlocklistUi()
            b.dcOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
            Utilities.showToastUiCentered(activity as Context,
                                          getString(R.string.settings_local_blocklist_desc4),
                                          Toast.LENGTH_SHORT)
        }
    }

    private fun onDownloadSuccess() {
        refreshOnDeviceBlocklistUi()
        b.dcOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc3)
        b.dcOnDeviceBlockVersion.text = getString(R.string.settings_local_blocklist_version,
                                                  Utilities.convertLongToTime(
                                                      persistentState.localBlocklistTimestamp,
                                                      TIME_FORMAT_2))
    }

    private fun onDownloadStart() {
        uithread(activity) {
            b.dcOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc2)

            b.dcOnDeviceConfigureRl.visibility = View.GONE
            b.dcOnDeviceBlockEnableBtn.visibility = View.GONE
            b.dcOnDeviceBlockDownloadBtn.visibility = View.GONE
            b.dcOnDeviceBlockProgress.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOnDeviceBlocklistUi()
    }

    private fun removeBraveDNSLocal() {
        persistentState.blocklistEnabled = false
        refreshOnDeviceBlocklistUi()
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun uithread(a: FragmentActivity?, f: () -> Unit) {
        a?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            f()
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

}
