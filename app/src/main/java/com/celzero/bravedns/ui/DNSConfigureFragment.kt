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
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentDnsConfigureBinding
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.scheduler.WorkScheduler.Companion.BLOCKLIST_UPDATE_CHECK_JOB_TAG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import com.celzero.bravedns.util.Utilities.Companion.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.Companion.isPlayStoreFlavour
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.concurrent.TimeUnit

class DnsConfigureFragment : Fragment(R.layout.fragment_dns_configure) {
    private val b by viewBinding(FragmentDnsConfigureBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private val customDomainViewModel: CustomDomainViewModel by viewModel()

    companion object {
        fun newInstance() = DnsConfigureFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        // update selected dns values
        updateSelectedDns()
        // update local blocklist ui
        updateLocalBlocklistUi()
    }

    private fun initView() {
        // display fav icon in dns logs
        b.dcFaviconSwitch.isChecked = persistentState.fetchFavIcon
        // prevent dns leaks
        b.dcPreventDnsLeaksSwitch.isChecked = persistentState.preventDnsLeaks
        // periodically check for blocklist update
        b.dcCheckUpdateSwitch.isChecked = persistentState.periodicallyCheckBlocklistUpdate
    }

    private fun updateLocalBlocklistUi() {
        if (isPlayStoreFlavour()) {
            b.dcLocalBlocklistRl.visibility = View.GONE
            return
        }

        if (persistentState.blocklistEnabled) {
            b.dcLocalBlocklistEnableBtn.text = getString(R.string.dc_local_block_enabled)
            b.dcLocalBlocklistCount.text = getString(R.string.dc_local_block_enabled)
            b.dcLocalBlocklistDesc.text = getString(R.string.settings_local_blocklist_in_use,
                                                    persistentState.numberOfLocalBlocklists.toString())
            b.dcLocalBlocklistCount.setTextColor(
                fetchColor(requireContext(), R.attr.secondaryTextColor))
            return
        }

        b.dcLocalBlocklistEnableBtn.text = getString(R.string.dc_local_block_enable)

        b.dcLocalBlocklistCount.setTextColor(fetchColor(requireContext(), R.attr.accentBad))
        b.dcLocalBlocklistCount.text = getString(R.string.dc_local_block_disabled)
    }

    private fun initObservers() {
        observeBraveMode()
        observeWorkManager()
    }

    private fun isSystemDns(): Boolean {
        return appConfig.isSystemDns()
    }

    private fun isRethinkDns(): Boolean {
        return appConfig.isRethinkDnsConnected()
    }

    private fun updateSelectedDns() {

        if (isSystemDns()) {
            b.networkDnsRb.isChecked = true
            return
        }

        if (isRethinkDns()) {
            b.rethinkPlusDnsRb.isChecked = true
            return
        }

        // connected to custom dns, update the dns details
        b.customDnsRb.isChecked = true
    }

    private fun getConnectedDnsType(): String {
        return when (appConfig.getDnsType()) {
            AppConfig.DnsType.RETHINK_REMOTE -> {
                getString(R.string.dc_rethink_dns)
            }
            AppConfig.DnsType.DOH -> {
                resources.getString(R.string.dc_doh)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                resources.getString(R.string.dc_dns_crypt)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                resources.getString(R.string.dc_dns_proxy)
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                resources.getString(R.string.dc_dns_proxy)
            }
        }
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
            if (it == null) return@observe

            if (AppConfig.BraveMode.getMode(it).isDnsMode()) {
                updateDnsOnlyModeUi()
            }
        }

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            updateSelectedDns()
        }
    }

    private fun updateDnsOnlyModeUi() {
        // disable local-blocklist for dns-only mode
        b.dcLocalBlocklistEnableBtn.isEnabled = false
        b.dcLocalBlocklistIcon.isEnabled = false
        b.dcLocalBlocklistRl.isEnabled = false

        b.dcLocalBlocklistRl.isClickable = false
        b.dcLocalBlocklistEnableBtn.isClickable = false
        b.dcLocalBlocklistIcon.isClickable = false

        // disable prevent dns leaks in dns-only mode
        b.dcPreventDnsLeaksSwitch.isClickable = false
        b.dcPreventDnsLeaksSwitch.isEnabled = false

    }

    private fun initClickListeners() {

        b.dcCustomDomainRl.setOnClickListener {
            Utilities.showToastUiCentered(requireContext(), "Coming soon", Toast.LENGTH_SHORT)
            // fixme: enable the below code when the custom allow/blocklist is enabled
            /*enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcCustomDomainRl)
            openCustomDomainDialog()*/
        }

        b.dcLocalBlocklistRl.setOnClickListener {
            toggleLocalBlocklistActionUi()
        }

        b.dcLocalBlocklistImg.setOnClickListener {
            toggleLocalBlocklistActionUi()
        }

        b.dcLocalBlocklistConfigureBtn.setOnClickListener {
            invokeRethinkActivity(ConfigureRethinkBasicActivity.FragmentLoader.LOCAL)
        }

        b.dcLocalBlocklistEnableBtn.setOnClickListener {
            if (persistentState.blocklistEnabled) {
                removeBraveDnsLocal()
                updateLocalBlocklistUi()
                return@setOnClickListener
            }

            go {
                uiCtx {
                    val blocklistsExist = withContext(Dispatchers.Default) {
                        hasLocalBlocklists(requireContext(),
                                           persistentState.localBlocklistTimestamp)
                    }
                    if (blocklistsExist && isLocalBlocklistStampAvailable()) {
                        setBraveDnsLocal()
                        updateLocalBlocklistUi()
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
                persistentState.periodicallyCheckBlocklistUpdate = true
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

        b.rethinkPlusDnsRb.setOnClickListener {
            // rethink dns plus
            invokeRethinkActivity(ConfigureRethinkBasicActivity.FragmentLoader.DB_LIST)
        }

        b.customDnsRb.setOnClickListener {
            // custom dns
            showCustomDns()
        }

        b.networkDnsRb.setOnClickListener {
            // network dns proxy
            setNetworkDns()
        }
    }

    // toggle the local blocklist action button ui
    private fun toggleLocalBlocklistActionUi() {
        if (b.dcLocalBlocklistActionContainer.isVisible) {
            b.dcLocalBlocklistActionContainer.visibility = View.GONE
            return
        }

        b.dcLocalBlocklistActionContainer.visibility = View.VISIBLE
    }

    private fun isLocalBlocklistStampAvailable(): Boolean {
        if (persistentState.localBlocklistStamp.isEmpty()) {
            return false
        }

        return true
    }

    private fun invokeRethinkActivity(type: ConfigureRethinkBasicActivity.FragmentLoader) {
        val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(ConfigureRethinkBasicActivity.INTENT, type.ordinal)
        requireContext().startActivity(intent)
    }

    private fun showCustomDns() {
        val intent = Intent(requireContext(), DnsListActivity::class.java)
        intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, customDnsScreenToLoad())
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
    }

    private fun customDnsScreenToLoad(): Int {
        return when (appConfig.getDnsType()) {
            AppConfig.DnsType.RETHINK_REMOTE -> {
                DnsListActivity.Tabs.DOH.screen
            }
            AppConfig.DnsType.DOH -> {
                DnsListActivity.Tabs.DOH.screen
            }
            AppConfig.DnsType.DNSCRYPT -> {
                DnsListActivity.Tabs.DNSCRYPT.screen
            }
            AppConfig.DnsType.DNS_PROXY -> {
                DnsListActivity.Tabs.DNSPROXY.screen
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                DnsListActivity.Tabs.DOH.screen
            }
        }
    }

    private fun setNetworkDns() {
        // set network dns
        io {
            val sysDns = appConfig.getSystemDns()
            appConfig.setSystemDns(sysDns.ipAddress, sysDns.port)
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
