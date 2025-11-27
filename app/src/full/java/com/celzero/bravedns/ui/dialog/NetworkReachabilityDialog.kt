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
import com.celzero.bravedns.service.ConnectionMonitor.Companion.SCHEME_HTTP
import com.celzero.bravedns.service.ConnectionMonitor.Companion.SCHEME_HTTPS
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
import java.net.MalformedURLException
import java.net.URL

class NetworkReachabilityDialog(activity: Activity,
    private val persistentState: PersistentState,
    themeId: Int
) : androidx.appcompat.app.AppCompatDialog(activity, themeId) {

    private lateinit var binding: DialogInputIpsBinding

    private var useAuto: Boolean = false

    companion object {
        private const val URL4 = "IPv4"
        private const val URL6 = "IPv6"
        private const val URL_SEGMENT4 = "#ipv4"
        private const val URL_SEGMENT6 = "#ipv6"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogInputIpsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setCancelable(true)
        initViews()
        setupListeners()
    }

    private fun initViews() {
        binding.saveButton.text = context.getString(R.string.lbl_save).uppercase()
        binding.testButton.text = context.getString(R.string.lbl_test).uppercase()

        useAuto = persistentState.performAutoNetworkConnectivityChecks
        if (useAuto) {
            updateAutoModeUi()
        } else {
            updateManualModeUi()
        }
        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
        setProtocolsUi()
    }

    private fun setProtocolsUi() {
        val protocols = VpnController.protocols()
        if (protocols.contains(URL4)) {
            binding.protocolV4.setImageResource(R.drawable.ic_tick)
        } else {
            binding.protocolV4.setImageResource(R.drawable.ic_cross_accent)
        }

        if (protocols.contains(URL6)) {
            binding.protocolV6.setImageResource(R.drawable.ic_tick)
        } else {
            binding.protocolV6.setImageResource(R.drawable.ic_cross_accent)
        }
    }

    private fun setupListeners() {
        binding.autoToggleBtn.setOnClickListener {
            useAuto = true
            updateAutoModeUi()
            selectToggleBtnUi(binding.autoToggleBtn)
            unselectToggleBtnUi(binding.manualToggleBtn)
        }
        binding.manualToggleBtn.setOnClickListener {
            useAuto = false
            updateManualModeUi()
            selectToggleBtnUi(binding.manualToggleBtn)
            unselectToggleBtnUi(binding.autoToggleBtn)
        }
        binding.resetChip.setOnClickListener { resetToDefaults() }
        binding.testButton.setOnClickListener { testConnections() }
        binding.saveButton.setOnClickListener { saveIps() }
    }

    private fun updateAutoModeUi() {
        binding.resetChip.visibility = View.GONE
        binding.saveButton.visibility = View.GONE

        selectToggleBtnUi(binding.autoToggleBtn)
        unselectToggleBtnUi(binding.manualToggleBtn)

        val autoTxt = context.getString(R.string.lbl_auto)
        val v4 = listOf(
            ConnectionMonitor.SCHEME_IP + ConnectionMonitor.PROTOCOL_V4 + " " + autoTxt,
            ConnectionMonitor.SCHEME_HTTPS + ConnectionMonitor.PROTOCOL_V4 + " " + autoTxt
        )
        val v6 = listOf(
            ConnectionMonitor.SCHEME_IP + ConnectionMonitor.PROTOCOL_V6 + " " + autoTxt,
            ConnectionMonitor.SCHEME_HTTPS + ConnectionMonitor.PROTOCOL_V6 + " " + autoTxt
        )
        binding.ipv4Address1.apply { isEnabled = false; setText(v4[0]) }
        binding.ipv4Address2.apply { isEnabled = false; setText(v4[1]) }
        binding.ipv6Address1.apply { isEnabled = false; setText(v6[0]) }
        binding.ipv6Address2.apply { isEnabled = false; setText(v6[1]) }

        binding.urlV4Layout1.visibility = View.GONE
        binding.urlV6Layout1.visibility = View.GONE
        binding.urlV4Layout2.visibility = View.GONE
        binding.urlV6Layout2.visibility = View.GONE

        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
        binding.errorMessage.visibility = View.GONE
    }

    private fun updateManualModeUi() {
        binding.resetChip.visibility = View.VISIBLE
        binding.saveButton.visibility = View.VISIBLE
        selectToggleBtnUi(binding.manualToggleBtn)
        unselectToggleBtnUi(binding.autoToggleBtn)

        val itemsIp4 = persistentState.pingv4Ips.split(",").toTypedArray()
        val itemsIp6 = persistentState.pingv6Ips.split(",").toTypedArray()
        val itemsUrl4 = persistentState.pingv4Url.split(",").toTypedArray()
        val itemsUrl6 = persistentState.pingv6Url.split(",").toTypedArray()


        binding.urlV4Layout1.visibility = View.VISIBLE
        binding.urlV6Layout1.visibility = View.VISIBLE
        binding.urlV4Layout2.visibility = View.VISIBLE
        binding.urlV6Layout2.visibility = View.VISIBLE

        binding.ipv4Address1.apply { isEnabled = true; setText(itemsIp4.getOrNull(0) ?: "") }
        binding.ipv4Address2.apply { isEnabled = true; setText(itemsIp4.getOrNull(1) ?: "") }
        binding.urlV4Address1.apply { isEnabled = true; setText(itemsUrl4.getOrNull(0)?.split(URL_SEGMENT4)?.firstOrNull() ?: Constants.urlV4probes[0]) }
        binding.urlV4Address2.apply { isEnabled = true; setText(itemsUrl4.getOrNull(1)?.split(URL_SEGMENT4)?.firstOrNull() ?: Constants.urlV4probes[0]) }
        binding.ipv6Address1.apply { isEnabled = true; setText(itemsIp6.getOrNull(0) ?: "") }
        binding.ipv6Address2.apply { isEnabled = true; setText(itemsIp6.getOrNull(1) ?: "") }
        binding.urlV6Address1.apply { isEnabled = true; setText(itemsUrl6.getOrNull(0)?.split(URL_SEGMENT6)?.firstOrNull() ?: Constants.urlV6probes[0]) }
        binding.urlV6Address2.apply { isEnabled = true; setText(itemsUrl6.getOrNull(1)?.split(URL_SEGMENT6)?.firstOrNull() ?: Constants.urlV6probes[1]) }

        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
        binding.errorMessage.visibility = View.GONE
    }

    private fun resetToDefaults() {
        binding.ipv4Address1.setText(Constants.ip4probes[0])
        binding.ipv4Address2.setText(Constants.ip4probes[1])
        binding.urlV4Address1.setText(Constants.urlV4probes[0].split(URL_SEGMENT4).firstOrNull() ?: Constants.urlV4probes[0])
        binding.urlV4Address2.setText(Constants.urlV4probes[1].split(URL_SEGMENT4).firstOrNull() ?: Constants.urlV4probes[1])

        binding.ipv6Address1.setText(Constants.ip6probes[0])
        binding.ipv6Address2.setText(Constants.ip6probes[1])
        binding.urlV6Address1.setText(Constants.urlV6probes[0].split(URL_SEGMENT6).firstOrNull() ?: Constants.urlV6probes[0])
        binding.urlV6Address2.setText(Constants.urlV6probes[1].split(URL_SEGMENT6).firstOrNull() ?: Constants.urlV6probes[1])
        binding.errorMessage.visibility = View.GONE
        setAllStatusIconsVisibility(View.GONE)
        setAllProgressBarsVisibility(View.GONE)
    }

    private fun testConnections() {
        setButtonsEnabled(false)
        setAllProgressBarsVisibility(View.VISIBLE)
        setAllStatusIconsVisibility(View.GONE)
        binding.errorMessage.visibility = View.GONE

        io {
            try {
                val results = mutableMapOf<String, ConnectionMonitor.ProbeResult?>()
                val v41 =
                    if (useAuto) ConnectionMonitor.SCHEME_IP + ":" + ConnectionMonitor.PROTOCOL_V4 else binding.ipv4Address1.text.toString()
                val v42 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTPS + ":" + ConnectionMonitor.PROTOCOL_V4 else binding.ipv4Address2.text.toString()
                val v61 =
                    if (useAuto) ConnectionMonitor.SCHEME_IP + ":" + ConnectionMonitor.PROTOCOL_V6 else binding.ipv6Address1.text.toString()
                val v62 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTPS + ":" + ConnectionMonitor.PROTOCOL_V6 else binding.ipv6Address2.text.toString()

                results["ipv4_1"] = probeIpOrUrl(v41)
                results["ipv4_2"] = probeIpOrUrl(v42)
                if (!useAuto) {
                    results["url4_1"] = probeIpOrUrl(binding.urlV4Address1.text.toString() + URL_SEGMENT4)
                    results["url4_2"] = probeIpOrUrl(binding.urlV4Address2.text.toString() + URL_SEGMENT4)
                }
                results["ipv6_1"] = probeIpOrUrl(v61)
                results["ipv6_2"] = probeIpOrUrl(v62)
                if (!useAuto) {
                    results["url6_1"] = probeIpOrUrl(binding.urlV6Address1.text.toString() + URL_SEGMENT6)
                    results["url6_2"] = probeIpOrUrl(binding.urlV6Address2.text.toString() + URL_SEGMENT6)
                }

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
        binding.statusUrlV41.setImageDrawable(getDrawableForProbeResult(results["url4_1"]))
        binding.statusUrlV42.setImageDrawable(getDrawableForProbeResult(results["url4_2"]))
        binding.statusIpv61.setImageDrawable(getDrawableForProbeResult(results["ipv6_1"]))
        binding.statusIpv62.setImageDrawable(getDrawableForProbeResult(results["ipv6_2"]))
        binding.statusUrlV61.setImageDrawable(getDrawableForProbeResult(results["url6_1"]))
        binding.statusUrlV62.setImageDrawable(getDrawableForProbeResult(results["url6_2"]))
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
        binding.statusUrlV41.visibility = visibility
        binding.statusUrlV42.visibility = visibility
        binding.statusIpv61.visibility = visibility
        binding.statusIpv62.visibility = visibility
        binding.statusUrlV61.visibility = visibility
        binding.statusUrlV62.visibility = visibility
    }

    private fun setAllProgressBarsVisibility(visibility: Int) {
        binding.progressIpv41.visibility = visibility
        binding.progressIpv42.visibility = visibility
        binding.progressUrlV41.visibility = visibility
        binding.progressUrlV42.visibility = visibility
        binding.progressIpv61.visibility = visibility
        binding.progressIpv62.visibility = visibility
        binding.progressUrlV61.visibility = visibility
        binding.progressUrlV62.visibility = visibility
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.testButton.isEnabled = enabled
        binding.saveButton.isEnabled = enabled
    }

    private fun saveIps() {
        val defaultDrawable = ContextCompat.getDrawable(context, R.drawable.edittext_default)
        val errorDrawable = ContextCompat.getDrawable(context, R.drawable.edittext_error)
        if (!useAuto) {
            val valid41 = isValidIp(binding.ipv4Address1.text.toString(), IPVersion.IPV4)
            val valid42 = isValidIp(binding.ipv4Address2.text.toString(), IPVersion.IPV4)
            val validUrl41 = isValidUrl(binding.urlV4Address1.text.toString())
            val validUrl42 = isValidUrl(binding.urlV4Address2.text.toString())
            val valid61 = isValidIp(binding.ipv6Address1.text.toString(), IPVersion.IPV6)
            val valid62 = isValidIp(binding.ipv6Address2.text.toString(), IPVersion.IPV6)
            val validUrl61 = isValidUrl(binding.urlV6Address1.text.toString())
            val validUrl62 = isValidUrl(binding.urlV6Address2.text.toString())

            binding.ipv4Address1.background = if (valid41) defaultDrawable else errorDrawable
            binding.ipv4Address2.background = if (valid42) defaultDrawable else errorDrawable
            binding.urlV4Address1.background = if (validUrl41) defaultDrawable else errorDrawable
            binding.urlV4Address2.background = if (validUrl42) defaultDrawable else errorDrawable
            binding.ipv6Address1.background = if (valid61) defaultDrawable else errorDrawable
            binding.ipv6Address2.background = if (valid62) defaultDrawable else errorDrawable
            binding.urlV6Address1.background = if (validUrl61) defaultDrawable else errorDrawable
            binding.urlV6Address2.background = if (validUrl62) defaultDrawable else errorDrawable

            if (!valid41 || !valid42 || !validUrl41 || !validUrl42 || !valid61 || !valid62 || !validUrl61  || !validUrl62) {
                binding.errorMessage.text = context.getString(R.string.cd_dns_proxy_error_text_1)
                binding.errorMessage.visibility = View.VISIBLE
                return
            }
        }
        val ip4 = listOf(
            binding.ipv4Address1.text.toString(),
            binding.ipv4Address2.text.toString()
        )
        val ip6 = listOf(
            binding.ipv6Address1.text.toString(),
            binding.ipv6Address2.text.toString()
        )
        val url4Txt1 = if (binding.urlV4Address1.text.toString().contains(URL_SEGMENT4)) {
            binding.urlV4Address1.text.toString()
        } else {
            binding.urlV4Address1.text.toString() + URL_SEGMENT4
        }
        val url4Txt2 = if (binding.urlV4Address2.text.toString().contains(URL_SEGMENT4)) {
            binding.urlV4Address2.text.toString()
        } else {
            binding.urlV4Address2.text.toString() + URL_SEGMENT4
        }
        val url6Txt1 = if (binding.urlV6Address1.text.toString().contains(URL_SEGMENT6)) {
            binding.urlV6Address1.text.toString()
        } else {
            binding.urlV6Address1.text.toString() + URL_SEGMENT6
        }
        val url6Txt2 = if (binding.urlV6Address2.text.toString().contains(URL_SEGMENT6)) {
            binding.urlV6Address2.text.toString()
        } else {
            binding.urlV6Address2.text.toString() + URL_SEGMENT6
        }
        val url4Txt = listOf(url4Txt1, url4Txt2)
        val url6Txt = listOf(url6Txt1, url6Txt2)
        val isSame = persistentState.pingv4Ips == ip4.joinToString(",") &&
                persistentState.pingv6Ips == ip6.joinToString(",") &&
                persistentState.pingv4Url == url4Txt.joinToString(",") &&
                persistentState.pingv6Url == url6Txt.joinToString(",")
        if (isSame) {
            dismiss()
            return
        }
        persistentState.pingv4Ips = ip4.joinToString(",")
        persistentState.pingv6Ips = ip6.joinToString(",")
        persistentState.pingv4Url = url4Txt.joinToString(",")
        persistentState.pingv6Url = url6Txt.joinToString(",")
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

    private fun isValidUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            (parsed.protocol == SCHEME_HTTPS || parsed.protocol == SCHEME_HTTP) &&
                    parsed.host.isNotEmpty() &&
                    parsed.query == null &&
                    parsed.ref == null
        } catch (e: MalformedURLException) {
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
