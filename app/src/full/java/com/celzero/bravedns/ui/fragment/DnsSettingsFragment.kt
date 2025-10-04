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
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_DNS
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.Companion.DOH_INDEX
import com.celzero.bravedns.data.AppConfig.Companion.DOT_INDEX
import com.celzero.bravedns.databinding.FragmentDnsConfigureBinding
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.scheduler.WorkScheduler.Companion.BLOCKLIST_UPDATE_CHECK_JOB_TAG
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.activity.DnsListActivity
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.bottomsheet.LocalBlocklistsBottomSheet
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.firestack.backend.Backend
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class DnsSettingsFragment : Fragment(R.layout.fragment_dns_configure),
    LocalBlocklistsBottomSheet.OnBottomSheetDialogFragmentDismiss {
    private val b by viewBinding(FragmentDnsConfigureBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private lateinit var animation: Animation

    companion object {
        fun newInstance() = DnsSettingsFragment()

        private const val REFRESH_TIMEOUT: Long = 4000

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
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
        // init animation
        initAnimation()
        // display fav icon in dns logs
        b.dcFaviconSwitch.isChecked = persistentState.fetchFavIcon
        // prevent dns leaks
        b.dcPreventDnsLeaksSwitch.isChecked = persistentState.preventDnsLeaks
        // enable per-app domain rules (dns alg)
        b.dcAlgSwitch.isChecked = persistentState.enableDnsAlg
        // periodically check for blocklist update
        b.dcCheckUpdateSwitch.isChecked = persistentState.periodicallyCheckBlocklistUpdate
        // use custom download manager
        b.dcDownloaderSwitch.isChecked = persistentState.useCustomDownloadManager
        // enable dns caching in tunnel
        b.dcEnableCacheSwitch.isChecked = persistentState.enableDnsCache
        // proxy dns
        b.dcProxyDnsSwitch.isChecked = !persistentState.proxyDns
        // use system dns for undelegated domains
        b.dcUndelegatedDomainsSwitch.isChecked = persistentState.useSystemDnsForUndelegatedDomains
        b.connectedStatusTitle.text = getConnectedDnsType()
        b.dcUseFallbackToBypassSwitch.isChecked = persistentState.useFallbackDnsToBypass
        showSplitDnsUi()
    }

    private fun updateLocalBlocklistUi() {
        if (isPlayStoreFlavour()) {
            b.dcLocalBlocklistRl.visibility = View.GONE
            return
        }

        if (persistentState.blocklistEnabled) {
            b.dcLocalBlocklistCount.text = getString(R.string.dc_local_block_enabled)
            b.dcLocalBlocklistDesc.text =
                getString(
                    R.string.settings_local_blocklist_in_use,
                    persistentState.numberOfLocalBlocklists.toString()
                )
            b.dcLocalBlocklistCount.setTextColor(
                fetchColor(requireContext(), R.attr.secondaryTextColor)
            )
            return
        }

        b.dcLocalBlocklistCount.setTextColor(fetchColor(requireContext(), R.attr.accentBad))
        b.dcLocalBlocklistCount.text = getString(R.string.lbl_disabled)
    }

    private fun initObservers() {
        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            if (it == BraveVPNService.State.PAUSED) {
                val intent = Intent(requireContext(), PauseActivity::class.java)
                startActivity(intent)
            }
        }

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            updateConnectedStatus(it)
            updateSelectedDns()
        }
    }

    private fun updateLatency(dnsType: String = b.connectedStatusTitleUrl.text.toString()) {
        io {
            val prefId = if (appConfig.isSmartDnsEnabled()) {
                Backend.Plus
            } else if (appConfig.isSystemDns()) {
                Backend.System
            } else {
                Backend.Preferred
            }
            val dnsId = if (WireguardManager.oneWireGuardEnabled()) {
                val id = WireguardManager.getOneWireGuardProxyId()
                if (id == null) {
                    prefId
                } else {
                    "${ProxyManager.ID_WG_BASE}${id}"
                }
            } else {
                prefId
            }
            val p50 = VpnController.p50(dnsId)
            if (p50 <= 0L) return@io

            uiCtx {
                val latency = getString(R.string.dns_query_latency, p50.toString())
                val text = getString(R.string.single_argument_parenthesis, latency)
                b.connectedStatusTitleUrl.text = getString(R.string.two_argument_space, text, dnsType)
            }
        }
    }

    private fun showSplitDnsUi() {
        if (persistentState.enableDnsAlg) {
            b.dvBypassDnsBlockRl.visibility = View.VISIBLE
            b.dvBypassDnsBlockSwitch.isChecked = persistentState.bypassBlockInDns
        } else {
            b.dvBypassDnsBlockRl.visibility = View.GONE
        }

        if (isAtleastR()) {
            // show split dns by default only if the device is running on Android 12 or above
            b.dcSplitDnsRl.visibility = View.VISIBLE
            b.dcSplitDnsSwitch.isChecked = persistentState.splitDns
        } else {
            if (persistentState.enableDnsAlg) {
                b.dcSplitDnsRl.visibility = View.VISIBLE
                b.dcSplitDnsSwitch.isChecked = persistentState.splitDns
            } else {
                b.dcSplitDnsRl.visibility = View.GONE
            }
        }
    }

    private fun updateSpiltDns() {
        if (isAtleastR()) {
            // no-op, no need to depend of alg when device is running on Android 12 or above
            // as split dns option is shown to user regardless of dns alg
            b.dcSplitDnsRl.visibility = View.VISIBLE
            b.dcSplitDnsSwitch.isChecked = persistentState.splitDns
            updateConnectedStatus(persistentState.connectedDnsName)
            return
        }

        if (persistentState.enableDnsAlg) {
            persistentState.splitDns = persistentState.splitDns // no-op, added for readability
        } else {
            persistentState.splitDns = false
        }
        showSplitDnsUi()
        updateConnectedStatus(persistentState.connectedDnsName)
    }

    private fun updateConnectedStatus(connectedDns: String) {
        var dnsType = resources.getString(R.string.configure_dns_connected_dns_proxy_status)
        if (WireguardManager.oneWireGuardEnabled()) {
            b.connectedStatusTitleUrl.text =
                resources.getString(R.string.configure_dns_connected_dns_proxy_status)
            b.connectedStatusTitle.text = resources.getString(R.string.lbl_wireguard)
            updateLatency()
            return
        }

        var dns = connectedDns
        if (persistentState.splitDns && WireguardManager.isAdvancedWgActive()) {
            dns += ", " + resources.getString(R.string.lbl_wireguard)
        }

        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                dnsType = resources.getString(R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text = dns
            }
            AppConfig.DnsType.DOT -> {
                dnsType = resources.getString(R.string.lbl_dot)
                b.connectedStatusTitleUrl.text = resources.getString(R.string.lbl_dot)
                b.connectedStatusTitle.text = dns
            }
            AppConfig.DnsType.DNSCRYPT -> {
                dnsType = resources.getString(R.string.configure_dns_connected_dns_crypt_status)
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_dns_crypt_status)
                b.connectedStatusTitle.text = dns
            }
            AppConfig.DnsType.DNS_PROXY -> {
                dnsType = resources.getString(R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text = dns
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                dnsType = resources.getString(R.string.dc_rethink_dns)
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text = dns
            }
            AppConfig.DnsType.SYSTEM_DNS -> {
                dnsType = resources.getString(R.string.dc_dns_proxy)
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text = dns
            }
            AppConfig.DnsType.ODOH -> {
                dnsType = resources.getString(R.string.lbl_odoh)
                b.connectedStatusTitleUrl.text = resources.getString(R.string.lbl_odoh)
                b.connectedStatusTitle.text = dns
            }
            AppConfig.DnsType.SMART_DNS -> {
                dnsType = resources.getString(R.string.smart_dns)
                b.connectedStatusTitleUrl.text = resources.getString(R.string.smart_dns)
                b.connectedStatusTitle.text = dns
            }
        }
        updateLatency(dnsType)
    }

    private fun isSystemDns(): Boolean {
        return appConfig.isSystemDns()
    }

    private fun isSmartDns(): Boolean {
        return appConfig.isSmartDnsEnabled()
    }

    private fun isRethinkDns(): Boolean {
        return appConfig.isRethinkDnsConnected()
    }

    private fun updateSelectedDns() {
        if (WireguardManager.oneWireGuardEnabled()) {
            b.wireguardRb.visibility = View.VISIBLE
            b.wireguardRb.isChecked = true
            b.wireguardRb.isChecked = true
            b.wireguardRb.isEnabled = true
            disableAllDns()
            return
        }

        b.wireguardRb.visibility = View.GONE
        if (isSmartDns()) {
            b.smartDnsRb.isChecked = true
            b.rethinkPlusDnsRb.isChecked = false
            b.customDnsRb.isChecked = false
            b.networkDnsRb.isChecked = false
            b.smartDnsRb.isChecked = true
        } else if (isSystemDns()) {
            b.networkDnsRb.isChecked = true
            b.rethinkPlusDnsRb.isChecked = false
            b.customDnsRb.isChecked = false
            b.smartDnsRb.isChecked = false
            b.networkDnsRb.isChecked = true
        } else if (isRethinkDns()) {
            b.rethinkPlusDnsRb.isChecked = true
            b.customDnsRb.isChecked = false
            b.networkDnsRb.isChecked = false
            b.smartDnsRb.isChecked = false
            b.rethinkPlusDnsRb.isChecked = true
        } else {
            // connected to custom dns, update the dns details
            b.customDnsRb.isChecked = true
            b.rethinkPlusDnsRb.isChecked = false
            b.networkDnsRb.isChecked = false
            b.smartDnsRb.isChecked = false
            b.customDnsRb.isChecked = true
        }
    }

    private fun disableAllDns() {
        b.rethinkPlusDnsRb.isChecked = false
        b.customDnsRb.isChecked = false
        b.networkDnsRb.isChecked = false
        b.smartDnsRb.isChecked = false

        b.rethinkPlusDnsRb.isEnabled = false
        b.customDnsRb.isEnabled = false
        b.networkDnsRb.isEnabled = false
        b.smartDnsRb.isEnabled = false

        b.rethinkPlusDnsRb.isClickable = false
        b.customDnsRb.isClickable = false
        b.networkDnsRb.isClickable = false
        b.smartDnsRb.isClickable = false
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
            AppConfig.DnsType.SYSTEM_DNS -> {
                resources.getString(R.string.dc_dns_proxy)
            }
            AppConfig.DnsType.SMART_DNS -> {
                // for now, the plus has multiple doh endpoints, so use the doh label
                resources.getString(R.string.dc_doh)
            }
            AppConfig.DnsType.DOT -> {
                resources.getString(R.string.lbl_dot)
            }
            AppConfig.DnsType.ODOH -> {
                resources.getString(R.string.lbl_odoh)
            }
        }
    }

    private fun initClickListeners() {

        b.dcLocalBlocklistRl.setOnClickListener { openLocalBlocklist() }

        b.dcLocalBlocklistImg.setOnClickListener { openLocalBlocklist() }

        b.dcCheckUpdateRl.setOnClickListener {
            b.dcCheckUpdateSwitch.isChecked = !b.dcCheckUpdateSwitch.isChecked
        }

        b.dcCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            persistentState.periodicallyCheckBlocklistUpdate = enabled
            if (enabled) {
                get<WorkScheduler>().scheduleBlocklistUpdateCheckJob()
            } else {
                Logger.i(
                    Logger.LOG_TAG_SCHEDULER,
                    "Cancel all the work related to blocklist update check"
                )
                WorkManager.getInstance(requireContext().applicationContext)
                    .cancelAllWorkByTag(BLOCKLIST_UPDATE_CHECK_JOB_TAG)
            }
        }

        b.dcAlgSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcAlgSwitch)
            persistentState.enableDnsAlg = enabled
            updateSpiltDns()
        }

        b.dcAlgRl.setOnClickListener { b.dcAlgSwitch.isChecked = !b.dcAlgSwitch.isChecked }

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

        b.dcPreventDnsLeaksSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean
            ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcPreventDnsLeaksSwitch)
            persistentState.preventDnsLeaks = enabled
        }

        b.rethinkPlusDnsRb.setOnCheckedChangeListener(null)
        b.rethinkPlusDnsRb.setOnClickListener {
            // rethink dns plus
            invokeRethinkActivity(ConfigureRethinkBasicActivity.FragmentLoader.DB_LIST)
        }

        b.customDnsRb.setOnCheckedChangeListener(null)
        b.customDnsRb.setOnClickListener {
            // custom dns
            showCustomDns()
        }

        b.networkDnsRb.setOnCheckedChangeListener(null)
        b.networkDnsRb.setOnClickListener {
            if (isSystemDns()) {
                io {
                    val sysDns = VpnController.getSystemDns()
                    uiCtx { showSystemDnsDialog(sysDns) }
                }
                return@setOnClickListener
            }
            // network dns proxy
            setNetworkDns()
        }

        b.smartDnsRb.setOnCheckedChangeListener(null)
        b.smartDnsRb.setOnClickListener {
            setSmartDns()
        }

        b.dcDownloaderRl.setOnClickListener {
            b.dcDownloaderSwitch.isChecked = !b.dcDownloaderSwitch.isChecked
        }

        b.dcDownloaderSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.useCustomDownloadManager = b
        }

        b.dcEnableCacheRl.setOnClickListener {
            b.dcEnableCacheSwitch.isChecked = !b.dcEnableCacheSwitch.isChecked
        }

        b.dcEnableCacheSwitch.setOnCheckedChangeListener { _, b ->
            persistentState.enableDnsCache = b
        }

        b.dcProxyDnsSwitch.setOnCheckedChangeListener { _, b -> persistentState.proxyDns = !b }

        b.dcProxyDnsRl.setOnClickListener {
            b.dcProxyDnsSwitch.isChecked = !b.dcProxyDnsSwitch.isChecked
        }

        b.dcRefresh.setOnClickListener {
            b.dcRefresh.isEnabled = false
            b.dcRefresh.animation = animation
            b.dcRefresh.startAnimation(animation)
            io { VpnController.refresh() }
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (isAdded) {
                    b.dcRefresh.isEnabled = true
                    b.dcRefresh.clearAnimation()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.dc_refresh_toast),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        b.dvBypassDnsBlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!persistentState.enableDnsAlg) return@setOnCheckedChangeListener

            persistentState.bypassBlockInDns = isChecked
        }

        b.dvBypassDnsBlockRl.setOnClickListener {
            b.dvBypassDnsBlockSwitch.isChecked = !b.dvBypassDnsBlockSwitch.isChecked
        }

        b.dcSplitDnsSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.splitDns = isChecked
            updateConnectedStatus(persistentState.connectedDnsName)
        }

        b.dcSplitDnsRl.setOnClickListener {
            b.dcSplitDnsSwitch.isChecked = !b.dcSplitDnsSwitch.isChecked
        }

        b.networkDnsInfo.setOnClickListener {
            io {
                val sysDns = VpnController.getSystemDns()
                uiCtx { showSystemDnsDialog(sysDns) }
            }
        }

        b.smartDnsInfo.setOnClickListener {
            showSmartDnsInfoDialog()
        }

        b.dcUndelegatedDomainsRl.setOnClickListener {
            b.dcUndelegatedDomainsSwitch.isChecked = !b.dcUndelegatedDomainsSwitch.isChecked
        }

        b.dcUndelegatedDomainsSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.useSystemDnsForUndelegatedDomains = isChecked
        }

        b.dcUseFallbackToBypassSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.useFallbackDnsToBypass = isChecked
        }

        b.dcUseFallbackToBypassHeading.setOnClickListener {
            b.dcUseFallbackToBypassSwitch.isChecked = !b.dcUseFallbackToBypassSwitch.isChecked
        }
    }

    private fun showSmartDnsInfoDialog() {
        io {
            val ids = VpnController.getPlusResolvers()
            val dnsList: MutableList<String> = mutableListOf()
            ids.forEach {
                val index = it.substringAfter(Backend.Plus).getOrNull(0)
                if (index == null) {
                    Logger.w(LOG_TAG_DNS, "smart(plus) dns resolver id is empty: $it")
                    return@forEach
                }
                // for now, only doh and dot are supported
                if (index != DOH_INDEX && index != DOT_INDEX) {
                    Logger.w(LOG_TAG_DNS, "smart(plus) dns resolver id is not doh or dot: $it")
                    return@forEach
                }
                val transport = VpnController.getPlusTransportById(it)
                val address = transport?.addr?.tos() ?: ""
                if (address.isNotEmpty()) dnsList.add(address)
            }

            Logger.i(LOG_TAG_DNS, "smart(plus) dns list size: ${dnsList.size}")
            uiCtx {
                val stringBuilder = StringBuilder()
                val desc = getString(R.string.smart_dns_desc)
                stringBuilder.append(desc).append("\n\n")
                dnsList.forEach {
                    val txt = getString(R.string.symbol_star) + " " + it
                    stringBuilder.append(txt).append("\n")
                }
                val list = stringBuilder.toString()
                val builder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.smart_dns)
                    .setMessage(list)
                    .setCancelable(true)
                    .setPositiveButton(R.string.ada_noapp_dialog_positive) { di, _ ->
                        di.dismiss()
                    }.setNeutralButton(
                        requireContext().getString(R.string.dns_info_neutral)
                    ) { _: DialogInterface, _: Int ->
                        UIUtils.clipboardCopy(
                            requireContext(),
                            list,
                            requireContext().getString(R.string.copy_clipboard_label)
                        )
                        Utilities.showToastUiCentered(
                            requireContext(),
                            requireContext().getString(R.string.info_dialog_url_copy_toast_msg),
                            Toast.LENGTH_SHORT
                        )
                    }
                val dialog = builder.create()
                dialog.show()
            }
        }
    }

    private fun showSystemDnsDialog(dns: String) {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.network_dns)
            .setMessage(dns)
            .setCancelable(true)
            .setPositiveButton(R.string.ada_noapp_dialog_positive) { di, _ ->
                di.dismiss()
            }
            .setNeutralButton(requireContext().getString(R.string.dns_info_neutral)) { _: DialogInterface, _: Int ->
                UIUtils.clipboardCopy(
                    requireContext(),
                    dns,
                    requireContext().getString(R.string.copy_clipboard_label)
                )
                Utilities.showToastUiCentered(
                    requireContext(),
                    requireContext().getString(R.string.info_dialog_url_copy_toast_msg),
                    Toast.LENGTH_SHORT
                )
            }
        val dialog = builder.create()
        dialog.show()
    }

    private fun initAnimation() {
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
        startActivity(intent)
    }

    private fun setNetworkDns() {
        // set network dns
        io { appConfig.enableSystemDns() }
    }

    private fun setSmartDns() {
        // set smart dns
        io { appConfig.enableSmartDns() }
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    override fun onBtmSheetDismiss() {
        if (!isAdded) return

        updateLocalBlocklistUi()
    }
}
