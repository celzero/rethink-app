/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.ActivePowerProfile
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.PowerProfileCurrentSetupManager
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.data.SavedPowerProfile
import com.celzero.bravedns.databinding.FragmentPowerBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.bottomsheet.HomeScreenSettingBottomSheet
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getPrivateDnsMode
import com.celzero.bravedns.util.Utilities.isPrivateDnsActive
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class PowerFragment : Fragment(R.layout.fragment_power) {

    private val b by viewBinding(FragmentPowerBinding::bind)
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private var activeProfilesDescView: TextView? = null
    private lateinit var startForResult: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>
    private var isVpnActivated = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        registerForActivityResult()
        activeProfilesDescView = b.fpActiveProfilesCard.findViewById(R.id.fp_active_profiles_desc)
        setupClickListeners()
        observeVpnState()
        bindState()
    }

    override fun onResume() {
        super.onResume()
        bindState()
    }

    private fun setupClickListeners() {
        b.fpDnsOnOffBtn.setOnClickListener { handleMainScreenBtnClickEvent() }
        b.fpPauseIcon.setOnClickListener { handlePause() }
        b.fpBottomSheetIcon.setOnClickListener { openBottomSheet() }

        b.fpActiveProfilesCard.setOnClickListener {
            findNavController().navigate(R.id.activeProfilesFragment)
        }
        b.fpDiscoverProfilesCard.setOnClickListener {
            findNavController().navigate(R.id.discoverProfilesFragment)
        }
        b.fpSaveSetupCard.setOnClickListener { saveCurrentSetup() }

        b.fpLogsCard.setOnClickListener {
            startActivity(Intent(requireContext(), NetworkLogsActivity::class.java))
        }

        b.fpDashboardCard.setOnClickListener {
            findNavController().navigate(R.id.homeScreenFragment)
        }
    }

    private fun bindState() {
        isVpnActivated = VpnController.state().activationRequested
        updateMainButtonUi()
        updateProtectionStatus()
        updateSavedProfilesSummary()
    }

    private fun updateProtectionStatus() {
        val vpnState = VpnController.state()
        val privateDnsMode = getPrivateDnsMode(requireContext())
        var color = fetchTextColor(R.color.accentBad)
        var statusId = R.string.status_exposed

        if (appConfig.getBraveMode().isFirewallMode()) {
            vpnState.connectionState = BraveVPNService.State.WORKING
        }

        if (vpnState.on) {
            color = fetchTextColor(R.color.accentGood)
            statusId =
                when (vpnState.connectionState) {
                    null -> R.string.status_no_internet
                    BraveVPNService.State.NEW,
                    BraveVPNService.State.WORKING -> R.string.status_protected
                    BraveVPNService.State.APP_ERROR -> {
                        color = fetchTextColor(R.color.accentBad)
                        R.string.status_app_error
                    }
                    BraveVPNService.State.DNS_ERROR -> {
                        color = fetchTextColor(R.color.accentBad)
                        R.string.status_dns_error
                    }
                    BraveVPNService.State.DNS_SERVER_DOWN -> {
                        color = fetchTextColor(R.color.accentBad)
                        R.string.status_dns_server_down
                    }
                    BraveVPNService.State.NO_INTERNET -> {
                        color = fetchTextColor(R.color.accentBad)
                        R.string.status_no_internet
                    }
                    else -> {
                        color = fetchTextColor(R.color.accentBad)
                        R.string.status_failing
                    }
                }
        } else if (isVpnActivated) {
            color = fetchTextColor(R.color.accentBad)
            statusId = R.string.status_waiting
        } else {
            statusId =
                when (privateDnsMode) {
                    Utilities.PrivateDnsMode.STRICT -> R.string.status_strict
                    else -> R.string.status_exposed
                }
        }

        if (statusId == R.string.status_protected) {
            statusId =
                when {
                    appConfig.getBraveMode().isDnsMode() && isPrivateDnsActive(requireContext()) ->
                        R.string.status_protected_with_private_dns
                    appConfig.getBraveMode().isDnsMode() -> R.string.status_protected
                    appConfig.isOrbotProxyEnabled() && isPrivateDnsActive(requireContext()) ->
                        R.string.status_protected_with_tor_private_dns
                    appConfig.isOrbotProxyEnabled() -> R.string.status_protected_with_tor
                    (appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) &&
                        isPrivateDnsActive(requireContext()) ->
                        R.string.status_protected_with_proxy_private_dns
                    appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled() ->
                        R.string.status_protected_with_proxy
                    appConfig.isCustomSocks5Enabled() && isPrivateDnsActive(requireContext()) ->
                        R.string.status_protected_with_socks5_private_dns
                    appConfig.isCustomHttpProxyEnabled() && isPrivateDnsActive(requireContext()) ->
                        R.string.status_protected_with_http_private_dns
                    appConfig.isCustomHttpProxyEnabled() -> R.string.status_protected_with_http
                    appConfig.isCustomSocks5Enabled() -> R.string.status_protected_with_socks5
                    appConfig.isWireGuardEnabled() && isPrivateDnsActive(requireContext()) ->
                        R.string.status_protected_with_wg_private_dns
                    appConfig.isWireGuardEnabled() -> R.string.status_protected_with_wg
                    isPrivateDnsActive(requireContext()) ->
                        R.string.status_protected_with_private_dns
                    else -> R.string.status_protected
                }
        }

        if (VpnController.isUnderlyingVpnNetworkEmpty()) {
            color = fetchTextColor(R.color.accentBad)
            statusId = R.string.status_no_network
        }

        b.fpStatusDesc.setTextColor(color)
        b.fpStatusDesc.text = getString(statusId)
    }

    private fun observeVpnState() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner) {
            isVpnActivated = it
            updateMainButtonUi()
            updateProtectionStatus()
        }

        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            if (VpnController.isAppPaused()) return@observe
            updateProtectionStatus()
        }
    }

    private fun updateMainButtonUi() {
        if (isVpnActivated) {
            b.fpDnsOnOffBtn.setBackgroundResource(R.drawable.home_screen_button_stop_bg)
            b.fpDnsOnOffBtn.text = getString(R.string.hsf_stop_btn_state)
        } else {
            b.fpDnsOnOffBtn.setBackgroundResource(R.drawable.home_screen_button_start_bg)
            b.fpDnsOnOffBtn.text = getString(R.string.hsf_start_btn_state)
        }
    }

    private fun handleMainScreenBtnClickEvent() {
        b.fpDnsOnOffBtn.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            if (isAdded) b.fpDnsOnOffBtn.isEnabled = true
        }
        handleVpnActivation()
    }

    private fun handleVpnActivation() {
        if (isVpnActivated) {
            stopVpnService()
        } else {
            prepareAndStartVpn()
        }
    }

    private fun handlePause() {
        if (!VpnController.hasTunnel()) {
            showToastUiCentered(
                requireContext(),
                requireContext().getString(R.string.hsf_pause_vpn_failure),
                Toast.LENGTH_SHORT
            )
            return
        }

        VpnController.pauseApp()
        val intent = Intent(requireContext(), PauseActivity::class.java)
        startActivity(intent)
    }

    private fun openBottomSheet() {
        val bottomSheetFragment = HomeScreenSettingBottomSheet()
        bottomSheetFragment.show(parentFragmentManager, bottomSheetFragment.tag)
    }

    private fun prepareAndStartVpn() {
        if (prepareVpnService()) {
            startVpnService()
        }
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent =
            try {
                VpnService.prepare(requireContext())
            } catch (_: NullPointerException) {
                return false
            }

        if (prepareVpnIntent != null) {
            startForResult.launch(prepareVpnIntent)
            return false
        }

        return true
    }

    private fun stopVpnService() {
        VpnController.stop("power", requireContext())
    }

    private fun startVpnService() {
        getNotificationPermissionIfNeeded()
        VpnController.start(requireContext(), true)
    }

    private fun getNotificationPermissionIfNeeded() {
        if (!Utilities.isAtleastT()) return

        if (
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (!persistentState.shouldRequestNotificationPermission) {
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun registerForActivityResult() {
        startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                when (result.resultCode) {
                    Activity.RESULT_OK -> startVpnService()
                    Activity.RESULT_CANCELED ->
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.hsf_vpn_prepare_failure),
                            Toast.LENGTH_LONG
                        )
                    else -> stopVpnService()
                }
            }

        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                persistentState.shouldRequestNotificationPermission = it
                if (!it && isAdded) {
                    Snackbar.make(
                        requireActivity().findViewById<View>(android.R.id.content).rootView,
                        getString(R.string.hsf_notification_permission_failure),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun fetchTextColor(colorRes: Int): Int {
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    private fun updateSavedProfilesSummary() {
        val activeProfiles = PowerProfileStore.listActiveProfiles(requireContext())
        if (activeProfiles.isNotEmpty()) {
            activeProfilesDescView?.text =
                getString(
                    R.string.power_active_profiles_active_summary,
                    activeProfiles.size,
                    activeProfiles.first().name,
                    formatActiveTimestamp(activeProfiles.first())
                )
            return
        }

        val profiles = PowerProfileStore.listSavedProfiles(requireContext())
        if (profiles.isEmpty()) {
            activeProfilesDescView?.text = getString(R.string.power_active_profiles_desc)
            return
        }

        activeProfilesDescView?.text =
            getString(
                R.string.power_active_profiles_saved_summary,
                profiles.size,
                formatProfileTimestamp(profiles.first())
            )
    }

    private fun saveCurrentSetup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val savedProfile = PowerProfileStore.saveCurrentSetup(requireContext(), appConfig)
            val reusableProfile =
                withContext(Dispatchers.IO) {
                    PowerProfileCurrentSetupManager.saveCurrentSetupAsImportedProfile(
                        requireContext(),
                        PowerProfileStore.listSavedProfiles(requireContext()).size
                    )
                }
            updateSavedProfilesSummary()
            val message =
                if (reusableProfile != null) {
                    getString(
                        R.string.power_saved_profile_saved_as_profile_message,
                        reusableProfile.resolveTitle(requireContext())
                    )
                } else {
                    getString(R.string.power_saved_profile_saved_message, savedProfile.name)
                }
            showToastUiCentered(requireContext(), message, Toast.LENGTH_SHORT)
        }
    }

    private fun formatProfileTimestamp(profile: SavedPowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.createdAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun formatActiveTimestamp(profile: ActivePowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.activatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }
}
