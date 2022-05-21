/*
 * Copyright 2022 RethinkDNS and its authors
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

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentDnsConfigureNewBinding
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.scheduler.WorkScheduler.Companion.BLOCKLIST_UPDATE_CHECK_JOB_TAG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.concurrent.TimeUnit

class DnsConfigureFragmentNew : Fragment(R.layout.fragment_dns_configure_new) {
    private val b by viewBinding(FragmentDnsConfigureNewBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private val customDomainViewModel: CustomDomainViewModel by viewModel()

    companion object {
        fun newInstance() = DnsConfigureFragmentNew()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
    }

    private fun initView() {
        // display fav icon in dns logs
        b.dcFaviconSwitch.isChecked = persistentState.fetchFavIcon
        // prevent dns leaks
        b.dcPreventDnsLeaksSwitch.isChecked = persistentState.preventDnsLeaks
        // update selected dns values
        updateSelectedDns(appConfig.getConnectedDns())
        // show update available badge for local and remote blocklist
        if (isLocalBlocklistUpdateAvailable()) b.dcLocalBlocklistUpdate.visibility = View.VISIBLE
    }

    private fun initObservers() {
        observeBraveMode()
        observeWorkManager()
    }

    private fun isDefaultDnsConnected(name: String): Boolean {
        // FIXME: Change the logic here, added for testing
        return name.contains("RethinkDNS Default")
    }

    private fun isNetworkDns(): Boolean {
        return appConfig.isNetworkDns()
    }

    private fun isRethinkDnsPlus(): Boolean {
        return appConfig.isRethinkDnsConnected()
    }

    private fun updateSelectedDns(name: String) {
        if (isDefaultDnsConnected(name)) {
            b.defaultDnsRb.isChecked = true
            return
        }

        if (isNetworkDns()) {
            b.networkDnsRb.isChecked = true
            return
        }

        if (isRethinkDnsPlus()) {
            b.rethinkPlusDnsRb.isChecked = true
            return
        }

        // connected to custom dns, update the dns details
        b.customDnsRb.isChecked = true
        b.connectedDnsDetailsTv.text = "Connected DNS:" + name + "\n" + getConnectedDnsType()
    }

    private fun getConnectedDnsType(): String {
        return when (appConfig.getDnsType()) {
            AppConfig.DnsType.RETHINK_REMOTE -> {
                "rethink dns"
            }
            AppConfig.DnsType.DOH -> {
                resources.getString(R.string.configure_dns_connected_doh_status)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                resources.getString(R.string.configure_dns_connected_dns_crypt_status)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                resources.getString(R.string.configure_dns_connected_dns_proxy_status)
            }
        }
    }

    private fun isLocalBlocklistUpdateAvailable(): Boolean {
        return persistentState.isLocalBlocklistUpdateAvailable
    }

    private fun observeWorkManager() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // handle the check for blocklist switch based on the work manager's status
        workManager.getWorkInfosByTagLiveData(BLOCKLIST_UPDATE_CHECK_JOB_TAG).observe(
            viewLifecycleOwner) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Log.i(LoggerConstants.LOG_TAG_SCHEDULER,
                  "WorkManager state: ${workInfo.state} for $BLOCKLIST_UPDATE_CHECK_JOB_TAG")
            // disable the switch only when the work is in a CANCELLED state.
            // enable the switch for all the other states (ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED)
            b.dcCheckUpdateSwitch.isChecked = WorkInfo.State.CANCELLED != workInfo.state
        }
    }

    private fun observeBraveMode() {
        appConfig.getBraveModeObservable().observe(viewLifecycleOwner) {
            when (it) {
                // TODO: disable local-blocklist for dns-only mode
                // TODO: disable prevent dns leaks in dns-only mode
            }
        }

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            updateSelectedDns(it)
        }
    }

    private fun initClickListeners() {

        b.dcCustomDomainRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcCustomDomainRl)
            openCustomDomainDialog()
        }

        b.dcLocalBlocklistRl.setOnClickListener {
            invokeRethinkActivity(ConfigureRethinkBasicActivity.FragmentLoader.LOCAL)
        }

        b.dcLocalBlocklistSwitch.setOnCheckedChangeListener { _: CompoundButton, isEnabled: Boolean ->
            if (!isEnabled) {
                removeBraveDnsLocal()
                return@setOnCheckedChangeListener
            }

            go {
                uiCtx {
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist) {
                        setBraveDnsLocal() // TODO: Move this to vpnService observer
                        b.dcLocalBlocklistDesc.text = getString(
                            R.string.settings_local_blocklist_in_use,
                            persistentState.numberOfLocalBlocklists.toString())
                    } else {
                        invokeRethinkActivity(ConfigureRethinkBasicActivity.FragmentLoader.LOCAL)
                    }
                }
            }
        }

        b.dcCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            if (enabled) {
                get<WorkScheduler>().scheduleBlocklistUpdateCheckJob()
            } else {
                Log.d(LoggerConstants.LOG_TAG_SCHEDULER,
                      "Cancel all the work related to blocklist update check")
                WorkManager.getInstance(requireContext().applicationContext).cancelAllWorkByTag(
                    BLOCKLIST_UPDATE_CHECK_JOB_TAG)
            }
        }

        b.dcFaviconSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcFaviconSwitch)
            persistentState.fetchFavIcon = enabled
        }

        b.dcPreventDnsLeaksSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcPreventDnsLeaksSwitch)
            persistentState.preventDnsLeaks = enabled
        }

        b.configureDnsRg.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                b.defaultDnsRb.id -> {
                    // default dns (rethinkdns basic)
                    setDefaultDns()
                }
                b.rethinkPlusDnsRb.id -> {
                    // rethink dns plus
                    invokeRethinkActivity(ConfigureRethinkBasicActivity.FragmentLoader.DB_LIST)
                }
                b.customDnsRb.id -> {
                    // custom dns
                    setCustomDns()
                }
                b.networkDnsRb.id -> {
                    // network dns proxy
                    setNetworkDns()
                }
            }
        }
    }

    private fun setDefaultDns() {
        io {
            appConfig.setDefaultConnection()
        }
    }

    private fun invokeRethinkActivity(type: ConfigureRethinkBasicActivity.FragmentLoader) {
        val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(ConfigureRethinkBasicActivity.INTENT, type.ordinal)
        requireContext ().startActivity(intent)
    }

    private fun setCustomDns() {
        val intent = Intent(requireContext(), DnsListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
    }

    private fun setNetworkDns() {
        // set network dns
        io {
            appConfig.setNetworkDns()
        }
    }

    // FIXME: Verification of BraveDns object should be added in future.
    private fun setBraveDnsLocal() {
        persistentState.blocklistEnabled = true
    }

    private fun removeBraveDnsLocal() {
        persistentState.blocklistEnabled = false
    }


    private fun openCustomDomainDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)

        val customDialog = CustomDomainDialog(requireActivity(), customDomainViewModel, themeId)
        customDialog.setCanceledOnTouchOutside(false)
        customDialog.show()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
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
}
