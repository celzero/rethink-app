/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.dialog

import Logger
import Logger.LOG_TAG_UI
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.DialogInputIpsBinding
import com.celzero.bravedns.service.ConnectionMonitor
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils
import com.google.android.material.button.MaterialButton
import inet.ipaddr.IPAddress.IPVersion
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkReachabilityDialog(activity: Activity,
    private val persistentState: PersistentState,
    themeId: Int
) : androidx.appcompat.app.AppCompatDialog(activity, themeId) {

    private lateinit var binding: DialogInputIpsBinding
    private val urlSegment4 = "#ipv4"
    private val urlSegment6 = "#ipv6"
    private var useAuto = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogInputIpsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setCancelable(false)
        initViews()
        setupListeners()
    }

    private fun initViews() {
        binding.saveChip.text =
            context.getString(R.string.lbl_save).replaceFirstChar(Char::titlecase)
        binding.cancelButton.text = context.getString(R.string.lbl_cancel).uppercase()
        binding.testButton.text = context.getString(R.string.lbl_test).uppercase()

        if (persistentState.performAutoNetworkConnectivityChecks) {
            updateAutoModeUi()
        } else {
            updateManualModeUi()
        }
        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
    }

    private fun setupListeners() {
        binding.autoToggleBtn.setOnClickListener {
            useAuto = true
            updateAutoModeUi()
            selectToggleBtnUi(binding.autoToggleBtn)
            unselectToggleBtnUi(binding.manualToggleBtn)
            persistentState.performAutoNetworkConnectivityChecks = true
            binding.chosenMode.text = "Reachability checks are performed with auto mode"
        }
        binding.manualToggleBtn.setOnClickListener {
            useAuto = false
            updateManualModeUi()
            selectToggleBtnUi(binding.manualToggleBtn)
            unselectToggleBtnUi(binding.autoToggleBtn)
            persistentState.performAutoNetworkConnectivityChecks = false
            binding.chosenMode.text = "Reachability checks are performed with manual mode"
        }
        binding.resetChip.setOnClickListener { resetToDefaults() }
        binding.testButton.setOnClickListener { testConnections() }
        binding.cancelButton.setOnClickListener { dismiss() }
        binding.saveChip.setOnClickListener { saveIps() }
    }

    private fun updateAutoModeUi() {
        binding.resetChip.visibility = View.GONE
        binding.saveChip.visibility = View.GONE

        selectToggleBtnUi(binding.autoToggleBtn)
        unselectToggleBtnUi(binding.manualToggleBtn)
        binding.chosenMode.text = "Reachability checks are performed with auto mode"

        val autoTxt = context.getString(R.string.lbl_auto)
        val v4 = listOf(
            ConnectionMonitor.SCHEME_IP + ConnectionMonitor.PROTOCOL_V4 + autoTxt,
            ConnectionMonitor.SCHEME_HTTP + ConnectionMonitor.PROTOCOL_V4 + autoTxt,
            ConnectionMonitor.SCHEME_HTTPS + ConnectionMonitor.PROTOCOL_V4 + autoTxt
        )
        val v6 = listOf(
            ConnectionMonitor.SCHEME_IP + ConnectionMonitor.PROTOCOL_V6 + autoTxt,
            ConnectionMonitor.SCHEME_HTTP + ConnectionMonitor.PROTOCOL_V6 + autoTxt,
            ConnectionMonitor.SCHEME_HTTPS + ConnectionMonitor.PROTOCOL_V6 + autoTxt
        )
        binding.ipv4Address1.apply { isEnabled = false; setText(v4[0]) }
        binding.ipv4Address2.apply { isEnabled = false; setText(v4[1]) }
        binding.ipv4Address3.apply { isEnabled = false; setText(v4[2]) }
        binding.ipv6Address1.apply { isEnabled = false; setText(v6[0]) }
        binding.ipv6Address2.apply { isEnabled = false; setText(v6[1]) }
        binding.ipv6Address3.apply { isEnabled = false; setText(v6[2]) }
        binding.urlV4Layout.visibility = View.GONE
        binding.urlV6Layout.visibility = View.GONE

        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
        binding.errorMessage.visibility = View.GONE
    }

    private fun updateManualModeUi() {
        binding.resetChip.visibility = View.VISIBLE
        binding.saveChip.visibility = View.VISIBLE
        selectToggleBtnUi(binding.manualToggleBtn)
        unselectToggleBtnUi(binding.autoToggleBtn)
        binding.chosenMode.text = "Reachability checks are performed with manual mode"

        val itemsIp4 = persistentState.pingv4Ips.split(",").toTypedArray()
        val itemsIp6 = persistentState.pingv6Ips.split(",").toTypedArray()
        val itemsUrl4 =
            persistentState.pingv4Url.split(urlSegment4).firstOrNull() ?: Constants.urlV4probe
        val itemsUrl6 =
            persistentState.pingv6Url.split(urlSegment6).firstOrNull() ?: Constants.urlV6probe

        binding.urlV4Layout.visibility = View.VISIBLE
        binding.urlV6Layout.visibility = View.VISIBLE
        binding.ipv4Address1.apply { isEnabled = true; setText(itemsIp4.getOrNull(0) ?: "") }
        binding.ipv4Address2.apply { isEnabled = true; setText(itemsIp4.getOrNull(1) ?: "") }
        binding.ipv4Address3.apply { isEnabled = true; setText(itemsIp4.getOrNull(2) ?: "") }
        binding.urlV4Address.apply { isEnabled = true; setText(itemsUrl4) }
        binding.ipv6Address1.apply { isEnabled = true; setText(itemsIp6.getOrNull(0) ?: "") }
        binding.ipv6Address2.apply { isEnabled = true; setText(itemsIp6.getOrNull(1) ?: "") }
        binding.ipv6Address3.apply { isEnabled = true; setText(itemsIp6.getOrNull(2) ?: "") }
        binding.urlV6Address.apply { isEnabled = true; setText(itemsUrl6) }

        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
        binding.errorMessage.visibility = View.GONE
    }

    private fun resetToDefaults() {
        binding.ipv4Address1.setText(Constants.ip4probes[0])
        binding.ipv4Address2.setText(Constants.ip4probes[1])
        binding.ipv4Address3.setText(Constants.ip4probes[2])
        binding.urlV4Address.setText(
            Constants.urlV4probe.split(urlSegment4).firstOrNull() ?: Constants.urlV4probe
        )
        binding.urlV6Address.setText(
            Constants.urlV6probe.split(urlSegment6).firstOrNull() ?: Constants.urlV6probe
        )
        binding.ipv6Address1.setText(Constants.ip6probes[0])
        binding.ipv6Address2.setText(Constants.ip6probes[1])
        binding.ipv6Address3.setText(Constants.ip6probes[2])
        binding.errorMessage.visibility = View.GONE
        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
    }

    private fun testConnections() {
        setButtonsEnabled(false)
        setAllProgressBarsVisibility(View.VISIBLE)
        setAllStatusIconsVisibility(View.GONE)
        binding.errorMessage.visibility = View.GONE

        // You must set your fragment/activity's own lifecycleScope or pass it via constructor
        io {
            try {
                val results = mutableMapOf<String, ConnectionMonitor.ProbeResult?>()
                val v41 =
                    if (useAuto) ConnectionMonitor.SCHEME_IP + ":" + ConnectionMonitor.PROTOCOL_V4 else binding.ipv4Address1.text.toString()
                val v42 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTP + ":" + ConnectionMonitor.PROTOCOL_V4 else binding.ipv4Address2.text.toString()
                val v43 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTPS + ":" + ConnectionMonitor.PROTOCOL_V4 else binding.ipv4Address3.text.toString()
                val v61 =
                    if (useAuto) ConnectionMonitor.SCHEME_IP + ":" + ConnectionMonitor.PROTOCOL_V6 else binding.ipv6Address1.text.toString()
                val v62 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTP + ":" + ConnectionMonitor.PROTOCOL_V6 else binding.ipv6Address2.text.toString()
                val v63 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTPS + ":" + ConnectionMonitor.PROTOCOL_V6 else binding.ipv6Address3.text.toString()

                results["ipv4_1"] = probeIpOrUrl(v41)
                results["ipv4_2"] = probeIpOrUrl(v42)
                results["ipv4_3"] = probeIpOrUrl(v43)
                if (!useAuto) results["url4"] =
                    probeIpOrUrl(binding.urlV4Address.text.toString() + urlSegment4)
                results["ipv6_1"] = probeIpOrUrl(v61)
                results["ipv6_2"] = probeIpOrUrl(v62)
                results["ipv6_3"] = probeIpOrUrl(v63)
                if (!useAuto) results["url6"] =
                    probeIpOrUrl(binding.urlV6Address.text.toString() + urlSegment6)

                uiCtx {
                    setAllProgressBarsVisibility(View.GONE)
                    updateStatusIcons(results)
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI , "NwReachability; testConnections error: ${e.message}", e)
                uiCtx {
                    binding.errorMessage.text =
                        context.getString(R.string.blocklist_update_check_failure)
                    binding.errorMessage.visibility = View.VISIBLE
                    setAllProgressBarsVisibility(View.GONE)
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private suspend fun probeIpOrUrl(ipOrUrl: String): ConnectionMonitor.ProbeResult? {
        return try {
            VpnController.probeIpOrUrl(ipOrUrl, useAuto)
        } catch (e: Exception) {
            Logger.d(LOG_TAG_UI, "NwReachability; probeIpOrUrl err: ${e.message}")
            null
        }
    }

    private fun updateStatusIcons(results: Map<String, ConnectionMonitor.ProbeResult?>) {
        binding.statusIpv41.setImageDrawable(getDrawableForProbeResult(results["ipv4_1"]))
        binding.statusIpv42.setImageDrawable(getDrawableForProbeResult(results["ipv4_2"]))
        binding.statusIpv43.setImageDrawable(getDrawableForProbeResult(results["ipv4_3"]))
        binding.statusUrlV4.setImageDrawable(getDrawableForProbeResult(results["url4"]))
        binding.statusIpv61.setImageDrawable(getDrawableForProbeResult(results["ipv6_1"]))
        binding.statusIpv62.setImageDrawable(getDrawableForProbeResult(results["ipv6_2"]))
        binding.statusIpv63.setImageDrawable(getDrawableForProbeResult(results["ipv6_3"]))
        binding.statusUrlV6.setImageDrawable(getDrawableForProbeResult(results["url6"]))
        setAllStatusIconsVisibility(View.VISIBLE)
    }

    private fun getDrawableForProbeResult(probeResult: ConnectionMonitor.ProbeResult?): Drawable? {
        val failureDrawable = ContextCompat.getDrawable(context, R.drawable.ic_cross_accent)
        if (probeResult == null || !probeResult.ok) return failureDrawable

        val cap = probeResult.capabilities
        val resId = when {
            cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> R.drawable.ic_firewall_wifi_on
            cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> R.drawable.ic_firewall_data_on
            else -> R.drawable.ic_tick
        }
        val drawable = ContextCompat.getDrawable(context, resId) ?: failureDrawable
        drawable?.setTint(UIUtils.fetchColor(context, R.attr.accentGood))
        return drawable
    }

    private fun setAllStatusIconsVisibility(visibility: Int) {
        binding.statusIpv41.visibility = visibility
        binding.statusIpv42.visibility = visibility
        binding.statusIpv43.visibility = visibility
        binding.statusUrlV4.visibility = visibility
        binding.statusIpv61.visibility = visibility
        binding.statusIpv62.visibility = visibility
        binding.statusIpv63.visibility = visibility
        binding.statusUrlV6.visibility = visibility
    }

    private fun setAllProgressBarsVisibility(visibility: Int) {
        binding.progressIpv41.visibility = visibility
        binding.progressIpv42.visibility = visibility
        binding.progressIpv43.visibility = visibility
        binding.progressUrlV4.visibility = visibility
        binding.progressIpv61.visibility = visibility
        binding.progressIpv62.visibility = visibility
        binding.progressIpv63.visibility = visibility
        binding.progressUrlV6.visibility = visibility
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.testButton.isEnabled = enabled
        binding.saveChip.isEnabled = enabled
        binding.cancelButton.isEnabled = enabled
    }

    private fun saveIps() {
        val defaultDrawable = ContextCompat.getDrawable(context, R.drawable.edittext_default)
        val errorDrawable = ContextCompat.getDrawable(context, R.drawable.edittext_error)
        if (!useAuto) {
            val valid41 = isValidIp(binding.ipv4Address1.text.toString(), IPVersion.IPV4)
            val valid42 = isValidIp(binding.ipv4Address2.text.toString(), IPVersion.IPV4)
            val valid43 = isValidIp(binding.ipv4Address3.text.toString(), IPVersion.IPV4)
            val url4Valid = binding.urlV4Address.text?.isNotEmpty() == true
            val valid61 = isValidIp(binding.ipv6Address1.text.toString(), IPVersion.IPV6)
            val valid62 = isValidIp(binding.ipv6Address2.text.toString(), IPVersion.IPV6)
            val valid63 = isValidIp(binding.ipv6Address3.text.toString(), IPVersion.IPV6)
            val url6Valid = binding.urlV6Address.text?.isNotEmpty() == true

            binding.ipv4Address1.background = if (valid41) defaultDrawable else errorDrawable
            binding.ipv4Address2.background = if (valid42) defaultDrawable else errorDrawable
            binding.ipv4Address3.background = if (valid43) defaultDrawable else errorDrawable
            binding.urlV4Address.background = if (url4Valid) defaultDrawable else errorDrawable
            binding.ipv6Address1.background = if (valid61) defaultDrawable else errorDrawable
            binding.ipv6Address2.background = if (valid62) defaultDrawable else errorDrawable
            binding.ipv6Address3.background = if (valid63) defaultDrawable else errorDrawable
            binding.urlV6Address.background = if (url6Valid) defaultDrawable else errorDrawable

            if (!valid41 || !valid42 || !valid43 || !valid61 || !valid62 || !valid63 || !url4Valid || !url6Valid) {
                binding.errorMessage.text = context.getString(R.string.cd_dns_proxy_error_text_1)
                binding.errorMessage.visibility = View.VISIBLE
                return
            }
        }
        val ip4 = listOf(
            binding.ipv4Address1.text.toString(),
            binding.ipv4Address2.text.toString(),
            binding.ipv4Address3.text.toString()
        )
        val ip6 = listOf(
            binding.ipv6Address1.text.toString(),
            binding.ipv6Address2.text.toString(),
            binding.ipv6Address3.text.toString()
        )
        val url4Txt = if (binding.urlV4Address.text.toString().contains(urlSegment4)) {
            binding.urlV4Address.text.toString()
        } else {
            binding.urlV4Address.text.toString() + urlSegment4
        }
        val url6Txt = if (binding.urlV6Address.text.toString().contains(urlSegment6)) {
            binding.urlV6Address.text.toString()
        } else {
            binding.urlV6Address.text.toString() + urlSegment6
        }
        val isSame = persistentState.pingv4Ips == ip4.joinToString(",") &&
                persistentState.pingv6Ips == ip6.joinToString(",") &&
                persistentState.pingv4Url == url4Txt &&
                persistentState.pingv6Url == url6Txt
        if (isSame) {
            dismiss()
            return
        }
        persistentState.pingv4Ips = ip4.joinToString(",")
        persistentState.pingv6Ips = ip6.joinToString(",")
        persistentState.pingv4Url = url4Txt
        persistentState.pingv6Url = url6Txt
        Toast.makeText(
            context,
            context.getString(R.string.config_add_success_toast),
            Toast.LENGTH_LONG
        ).show()
        io {
            VpnController.notifyConnectionMonitor()
        }
        dismiss()
    }

    private fun io(fn: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { fn() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun isValidIp(ipString: String, type: IPVersion): Boolean {
        return try {
            val addr = IPAddressString(ipString).toAddress()
            when {
                type.isIPv4 -> addr.isIPv4
                type.isIPv6 -> addr.isIPv6
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun selectToggleBtnUi(mb: MaterialButton) {
        mb.backgroundTintList = ColorStateList.valueOf(UIUtils.fetchToggleBtnColors(context, R.color.accentGood))
        mb.setTextColor(UIUtils.fetchColor(context, R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(mb: MaterialButton) {
        mb.setTextColor(UIUtils.fetchColor(context, R.attr.primaryTextColor))
        mb.backgroundTintList = ColorStateList.valueOf(UIUtils.fetchToggleBtnColors(context, R.color.defaultToggleBtnBg))
    }
}
