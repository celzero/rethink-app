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
import com.celzero.bravedns.customdownloader.LocalBlocklistDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentDnsConfigureBinding
import com.celzero.bravedns.download.DownloadConstants
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

    private fun initView() {
        // display fav icon in dns logs
        b.dcFaviconSwitch.isChecked = persistentState.fetchFavIcon
        // prevent dns leaks
        b.dcPreventDnsLeaksSwitch.isChecked = persistentState.preventDnsLeaks

        // show update available badge for local and remote blocklist
        if (isLocalBlocklistUpdateAvailable()) b.dcLocalBlocklistUpdate.visibility = View.VISIBLE
        if (isRemoteBlocklistUpdateAvailable()) b.dcRemoteBlocklistUpdate.visibility = View.VISIBLE
    }

    private fun initObservers() {
        observeBraveMode()
        observeWorkManager()
    }

    private fun isLocalBlocklistUpdateAvailable(): Boolean {
        return persistentState.isLocalBlocklistUpdateAvailable
    }

    private fun isRemoteBlocklistUpdateAvailable(): Boolean {
        // fixme: for testing the value is sent as true, remove after testing
        return true
        //return persistentState.isRemoteBlocklistUpdateAvailable
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
    }

    private fun initClickListeners() {

        b.dcCustomDomainRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcCustomDomainRl)
            openCustomDomainDialog()
        }

        b.dcLocalBlocklistRl.setOnClickListener {
            openLocalBlocklistBottomSheet()
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
                        openLocalBlocklistBottomSheet()
                    }
                }
            }
        }

        b.dcRemoteBlocklistSwitch.setOnCheckedChangeListener { _: CompoundButton, isEnabled: Boolean ->
            if (!isEnabled) {
                // disabled: will switch to default rethink's dns (show dialog before switch?)
                return@setOnCheckedChangeListener
            }

            // enabled: change the dns to rethink plus configure

        }

        b.dcCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            if (enabled) {
                get<WorkScheduler>().scheduleBlocklistUpdateCheckJob()
            } else {
                Log.d(LoggerConstants.LOG_TAG_SCHEDULER, "Cancel all the work related to blocklist update check")
                WorkManager.getInstance(requireContext().applicationContext).cancelAllWorkByTag(BLOCKLIST_UPDATE_CHECK_JOB_TAG)
            }
        }

        b.dcRemoteBlocklistRl.setOnClickListener {
            openRemoteBlocklistBottomSheet()
        }

        b.dcDnsOptionsRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcDnsOptionsRl)

            val intent = Intent(requireContext(), DnsListActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            startActivity(intent)
        }

        b.dcFaviconSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcFaviconSwitch)
            persistentState.fetchFavIcon = enabled
        }

        b.dcPreventDnsLeaksSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcPreventDnsLeaksSwitch)
            persistentState.preventDnsLeaks = enabled
        }

    }

    private fun openRemoteBlocklistBottomSheet() {
        val bottomSheetFragment = RemoteBlocklistBottomSheetFragment()
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun openLocalBlocklistBottomSheet() {
        val bottomSheetFragment = LocalBlocklistBottomSheetFragment()
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
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
}
