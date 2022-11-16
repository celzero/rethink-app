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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentDnsConfigureBinding
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.scheduler.WorkScheduler.Companion.BLOCKLIST_UPDATE_CHECK_JOB_TAG
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import com.celzero.bravedns.util.Utilities.Companion.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.Companion.openPauseActivityAndFinish
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.concurrent.TimeUnit

class DnsConfigureFragment : Fragment(R.layout.fragment_dns_configure),
                             LocalBlocklistsBottomSheet.OnBottomSheetDialogFragmentDismiss {
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
        // use custom download manager
        b.dcDownloaderSwitch.isChecked = persistentState.useCustomDownloadManager

        b.connectedStatusTitle.text = getConnectedDnsType()
    }

    private fun updateLocalBlocklistUi() {
        if (isPlayStoreFlavour()) {
            b.dcLocalBlocklistRl.visibility = View.GONE
            return
        }

        if (persistentState.blocklistEnabled) {
            b.dcLocalBlocklistCount.text = getString(R.string.dc_local_block_enabled)
            b.dcLocalBlocklistDesc.text = getString(R.string.settings_local_blocklist_in_use,
                                                    persistentState.numberOfLocalBlocklists.toString())
            b.dcLocalBlocklistCount.setTextColor(
                fetchColor(requireContext(), R.attr.secondaryTextColor))
            return
        }

        b.dcLocalBlocklistCount.setTextColor(fetchColor(requireContext(), R.attr.accentBad))
        b.dcLocalBlocklistCount.text = getString(R.string.dc_local_block_disabled)
    }

    private fun initObservers() {
        observeBraveMode()
        observeDnscryptStatus()
        observeAppState()
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            if (it == BraveVPNService.State.PAUSED) {
                openPauseActivityAndFinish(requireActivity())
            }
        }

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            updateConnectedStatus(it)
        }
    }

    private fun updateConnectedStatus(connectedDns: String) {
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_crypt_status)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
        }
    }

    // FIXME: Create common observer for dns instead of separate observers
    private fun observeDnscryptStatus() {
        appConfig.getDnscryptCountObserver().observe(viewLifecycleOwner) {
            if (appConfig.getDnsType() != AppConfig.DnsType.DNSCRYPT) return@observe

            val connectedCrypt = getString(R.string.configure_dns_crypt, it.toString())
            b.connectedStatusTitle.text = connectedCrypt
        }
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
        b.dcLocalBlocklistIcon.isEnabled = false
        b.dcLocalBlocklistRl.isEnabled = false

        b.dcLocalBlocklistRl.isClickable = false
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
            openLocalBlocklist()
        }

        b.dcLocalBlocklistImg.setOnClickListener {
            openLocalBlocklist()
        }

        b.dcCheckUpdateRl.setOnClickListener {
            b.dcCheckUpdateSwitch.isChecked = !b.dcCheckUpdateSwitch.isChecked
        }

        b.dcCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            persistentState.periodicallyCheckBlocklistUpdate = enabled
            if (enabled) {
                get<WorkScheduler>().scheduleBlocklistUpdateCheckJob()
            } else {
                Log.i(LoggerConstants.LOG_TAG_SCHEDULER,
                      "Cancel all the work related to blocklist update check")
                WorkManager.getInstance(requireContext().applicationContext).cancelAllWorkByTag(
                    BLOCKLIST_UPDATE_CHECK_JOB_TAG)
            }
        }

        b.dcFaviconRl.setOnClickListener {
            b.dcFaviconSwitch.isChecked = !b.dcFaviconSwitch.isChecked
        }

        b.dcFaviconSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcFaviconSwitch)
            persistentState.fetchFavIcon = enabled
        }

        b.dcPreventDnsLeaksRl.setOnClickListener {
            b.dcPreventDnsLeaksSwitch.isChecked = !b.dcPreventDnsLeaksSwitch.isChecked
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

        b.dcDownloaderRl.setOnClickListener {
            b.dcDownloaderSwitch.isChecked = !b.dcDownloaderSwitch.isChecked
        }

        b.dcDownloaderSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.useCustomDownloadManager = b
        }
    }

    // open local blocklist bottom sheet
    private fun openLocalBlocklist() {
        val bottomSheetFragment = LocalBlocklistsBottomSheet()
        bottomSheetFragment.setDismissListener(this)
        bottomSheetFragment.show(parentFragmentManager, bottomSheetFragment.tag)
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

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    override fun onBtmSheetDismiss() {
        if (!isAdded) return

        updateLocalBlocklistUi()
    }
}
