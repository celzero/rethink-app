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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.DialogCustomLanIpBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.google.android.material.button.MaterialButton
import inet.ipaddr.IPAddressString

class CustomLanIpDialog(
    activity: Activity,
    private val persistentState: PersistentState,
    themeId: Int
) : AppCompatDialog(activity, themeId) {

    companion object {
        private const val GATEWAY_4_PREFIX = 24
        private const val GATEWAY_6_PREFIX = 120
        private const val ROUTER_4_PREFIX = 32
        private const val ROUTER_6_PREFIX = 128
        private const val DNS_4_PREFIX = 32
        private const val DNS_6_PREFIX = 128

        private const val GATEWAY_4_IP = "10.111.222.1"
        private const val GATEWAY_6_IP = "fd66:f83a:c650::1"
        private const val ROUTER_4_IP = "10.111.222.2"
        private const val ROUTER_6_IP = "fd66:f83a:c650::2"
        private const val DNS_4_IP = "10.111.222.3"
        private const val DNS_6_IP = "fd66:f83a:c650::3"
    }
    private lateinit var binding: DialogCustomLanIpBinding

    // Track initial state to detect changes
    private var initialMode: Boolean = false
    private var currentMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogCustomLanIpBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        setCancelable(true)

        // reset customIpChanged flag when dialog is created
        persistentState.customModeOrIpChanged = false

        setupInitialState()
        setupClickListeners()
    }

    private fun setupInitialState() {
        initialMode = persistentState.customLanIpMode
        currentMode = initialMode

        if (currentMode) {
            selectManualModeUi()
        } else {
            selectAutoModeUi()
        }
    }

    private fun loadDefaultAutoValues() {
        // Load default gateway values
        binding.gatewayIpv4.setText(GATEWAY_4_IP)
        binding.gatewayIpv4Prefix.setText(GATEWAY_4_PREFIX.toString())
        binding.gatewayIpv6.setText(GATEWAY_6_IP)
        binding.gatewayIpv6Prefix.setText(GATEWAY_6_PREFIX.toString())

        // Load default router values
        binding.routerIpv4.setText(ROUTER_4_IP)
        binding.routerIpv4Prefix.setText(ROUTER_4_PREFIX.toString())
        binding.routerIpv6.setText(ROUTER_6_IP)
        binding.routerIpv6Prefix.setText(ROUTER_6_PREFIX.toString())

        // Load default DNS values
        binding.dnsIpv4.setText(DNS_4_IP)
        binding.dnsIpv4Prefix.setText(DNS_4_PREFIX.toString())
        binding.dnsIpv6.setText(DNS_6_IP)
        binding.dnsIpv6Prefix.setText(DNS_6_PREFIX.toString())
    }

    private fun setupClickListeners() {
        binding.autoToggleBtn.setOnClickListener {
            currentMode = false
            selectAutoModeUi()
        }
        binding.manualToggleBtn.setOnClickListener {
            currentMode = true
            selectManualModeUi()
        }

        binding.resetButton.setOnClickListener {
            if (!currentMode) {
                Toast.makeText(context, R.string.custom_lan_ip_saved_auto, Toast.LENGTH_SHORT).show()
            } else {
                resetManualFields()
            }
        }

        binding.saveButton.setOnClickListener {
            if (!currentMode) {
                saveAutoMode()
            } else {
                saveManualMode()
            }
        }
    }

    private fun selectAutoModeUi() {
        selectToggleBtnUi(binding.autoToggleBtn)
        unselectToggleBtnUi(binding.manualToggleBtn)

        binding.modeDesc.text = context.getString(R.string.custom_lan_ip_auto_desc)

        setManualFieldsEnabled(false)
        binding.resetButton.isEnabled = false

        // Load default AUTO values
        loadDefaultAutoValues()
    }

    private fun selectManualModeUi() {
        selectToggleBtnUi(binding.manualToggleBtn)
        unselectToggleBtnUi(binding.autoToggleBtn)

        binding.modeDesc.text = context.getString(R.string.custom_lan_ip_manual_desc)

        setManualFieldsEnabled(true)
        binding.resetButton.isEnabled = true

        // Load saved manual values if they exist, otherwise load defaults
        loadManualValues()
    }

    private fun loadManualValues() {
        // If saved values exist in persistent state, load them
        // Otherwise, load default values
        if (persistentState.customLanGatewayIpv4.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanGatewayIpv4, binding.gatewayIpv4, binding.gatewayIpv4Prefix)
        } else {
            binding.gatewayIpv4.setText(GATEWAY_4_IP)
            binding.gatewayIpv4Prefix.setText(GATEWAY_4_PREFIX.toString())
        }

        if (persistentState.customLanGatewayIpv6.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanGatewayIpv6, binding.gatewayIpv6, binding.gatewayIpv6Prefix)
        } else {
            binding.gatewayIpv6.setText(GATEWAY_6_IP)
            binding.gatewayIpv6Prefix.setText(GATEWAY_6_PREFIX.toString())
        }

        if (persistentState.customLanRouterIpv4.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanRouterIpv4, binding.routerIpv4, binding.routerIpv4Prefix)
        } else {
            binding.routerIpv4.setText(ROUTER_4_IP)
            binding.routerIpv4Prefix.setText(ROUTER_4_PREFIX.toString())
        }

        if (persistentState.customLanRouterIpv6.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanRouterIpv6, binding.routerIpv6, binding.routerIpv6Prefix)
        } else {
            binding.routerIpv6.setText(ROUTER_6_IP)
            binding.routerIpv6Prefix.setText(ROUTER_6_PREFIX.toString())
        }

        if (persistentState.customLanDnsIpv4.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanDnsIpv4, binding.dnsIpv4, binding.dnsIpv4Prefix)
        } else {
            binding.dnsIpv4.setText(DNS_4_IP)
            binding.dnsIpv4Prefix.setText(DNS_4_PREFIX.toString())
        }

        if (persistentState.customLanDnsIpv6.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanDnsIpv6, binding.dnsIpv6, binding.dnsIpv6Prefix)
        } else {
            binding.dnsIpv6.setText(DNS_6_IP)
            binding.dnsIpv6Prefix.setText(DNS_6_PREFIX.toString())
        }
    }

    private fun selectToggleBtnUi(mb: MaterialButton) {
        mb.backgroundTintList = ColorStateList.valueOf(fetchToggleBtnColors(context, R.color.accentGood))
        mb.setTextColor(fetchColor(context, R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(mb: MaterialButton) {
        mb.setTextColor(fetchColor(context, R.attr.primaryTextColor))
        mb.backgroundTintList = ColorStateList.valueOf(fetchToggleBtnColors(context, R.color.defaultToggleBtnBg))
    }

    private fun setManualFieldsEnabled(enabled: Boolean) {
        // Gateway
        binding.gatewayIpv4.isEnabled = enabled
        binding.gatewayIpv4Prefix.isEnabled = enabled
        binding.gatewayIpv6.isEnabled = enabled
        binding.gatewayIpv6Prefix.isEnabled = enabled
        // Router
        binding.routerIpv4.isEnabled = enabled
        binding.routerIpv4Prefix.isEnabled = enabled
        binding.routerIpv6.isEnabled = enabled
        binding.routerIpv6Prefix.isEnabled = enabled
        // DNS
        binding.dnsIpv4.isEnabled = enabled
        binding.dnsIpv4Prefix.isEnabled = enabled
        binding.dnsIpv6.isEnabled = enabled
        binding.dnsIpv6Prefix.isEnabled = enabled
    }

    private fun resetManualFields() {
        // Reset to default values instead of clearing
        loadDefaultAutoValues()

        // Clear any error message
        hideError()

        Toast.makeText(context, R.string.custom_lan_ip_saved_manual, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorText.visibility = View.GONE
        binding.errorText.text = ""
    }

    private fun saveAutoMode() {
        try {
            // Check if mode changed
            val modeChanged = initialMode != currentMode

            // AUTO mode: mark mode as auto and clear any saved custom values
            persistentState.customLanIpMode = false

            // Set the customIpChanged flag if mode changed OR we had custom values and now cleared them
            if (modeChanged) {
                persistentState.customModeOrIpChanged = true
                Logger.i(LOG_TAG_UI, "Custom LAN IPs cleared (switched to AUTO)")
            }

            hideError()
            Toast.makeText(context, R.string.custom_lan_ip_saved_auto, Toast.LENGTH_SHORT).show()
            dismiss()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err saving custom lan ip (auto): ${e.message}", e)
            showError(context.getString(R.string.custom_lan_ip_save_error))
        }
    }

    private fun saveManualMode() {
        try {
            val gatewayV4 = binding.gatewayIpv4.text?.toString()?.trim().orEmpty()
            val gatewayV4Prefix = binding.gatewayIpv4Prefix.text?.toString()?.trim().orEmpty()
            val gatewayV6 = binding.gatewayIpv6.text?.toString()?.trim().orEmpty()
            val gatewayV6Prefix = binding.gatewayIpv6Prefix.text?.toString()?.trim().orEmpty()

            val routerV4 = binding.routerIpv4.text?.toString()?.trim().orEmpty()
            val routerV4Prefix = binding.routerIpv4Prefix.text?.toString()?.trim().orEmpty()
            val routerV6 = binding.routerIpv6.text?.toString()?.trim().orEmpty()
            val routerV6Prefix = binding.routerIpv6Prefix.text?.toString()?.trim().orEmpty()

            val dnsV4 = binding.dnsIpv4.text?.toString()?.trim().orEmpty()
            val dnsV4Prefix = binding.dnsIpv4Prefix.text?.toString()?.trim().orEmpty()
            val dnsV6 = binding.dnsIpv6.text?.toString()?.trim().orEmpty()
            val dnsV6Prefix = binding.dnsIpv6Prefix.text?.toString()?.trim().orEmpty()

            // Validate IP + prefix pairs; only allow private/ULA ranges
            if (!validateIpv4WithPrefix(gatewayV4, gatewayV4Prefix) ||
                !validateIpv6WithPrefix(gatewayV6, gatewayV6Prefix) ||
                !validateIpv4WithPrefix(routerV4, routerV4Prefix) ||
                !validateIpv6WithPrefix(routerV6, routerV6Prefix) ||
                !validateIpv4WithPrefix(dnsV4, dnsV4Prefix) ||
                !validateIpv6WithPrefix(dnsV6, dnsV6Prefix)
            ) {
                showError(context.getString(R.string.custom_lan_ip_validation_error))
                return
            }

            // Combine new values
            val newGatewayV4 = combineIpAndPrefix(gatewayV4, gatewayV4Prefix)
            val newGatewayV6 = combineIpAndPrefix(gatewayV6, gatewayV6Prefix)
            val newRouterV4 = combineIpAndPrefix(routerV4, routerV4Prefix)
            val newRouterV6 = combineIpAndPrefix(routerV6, routerV6Prefix)
            val newDnsV4 = combineIpAndPrefix(dnsV4, dnsV4Prefix)
            val newDnsV6 = combineIpAndPrefix(dnsV6, dnsV6Prefix)

            // Check if any IP values have changed
            val ipValuesChanged = newGatewayV4 != persistentState.customLanGatewayIpv4 ||
                    newGatewayV6 != persistentState.customLanGatewayIpv6 ||
                    newRouterV4 != persistentState.customLanRouterIpv4 ||
                    newRouterV6 != persistentState.customLanRouterIpv6 ||
                    newDnsV4 != persistentState.customLanDnsIpv4 ||
                    newDnsV6 != persistentState.customLanDnsIpv6

            // Check if mode changed
            val modeChanged = initialMode != currentMode

            persistentState.customLanIpMode = true

            // Store combined ip/prefix strings; empty pair becomes ""
            persistentState.customLanGatewayIpv4 = newGatewayV4
            persistentState.customLanGatewayIpv6 = newGatewayV6

            persistentState.customLanRouterIpv4 = newRouterV4
            persistentState.customLanRouterIpv6 = newRouterV6

            persistentState.customLanDnsIpv4 = newDnsV4
            persistentState.customLanDnsIpv6 = newDnsV6

            // Set the customIpChanged flag if mode changed OR IP values changed
            if (modeChanged || ipValuesChanged) {
                persistentState.customModeOrIpChanged = true
                Logger.i(LOG_TAG_UI, "Custom LAN IPs changed - mode changed: $modeChanged, IP values changed: $ipValuesChanged")
            }

            hideError()
            Toast.makeText(context, R.string.custom_lan_ip_saved_manual, Toast.LENGTH_SHORT).show()
            dismiss()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err saving custom lan ip (manual): ${e.message}", e)
            showError(context.getString(R.string.custom_lan_ip_save_error))
        }
    }

    private fun loadIpAndPrefixIntoFields(value: String, ipField: EditText, prefixField: EditText) {
        if (value.isBlank()) {
            ipField.setText("")
            prefixField.setText("")
            return
        }
        val parts = value.split("/")
        val ip = parts.getOrNull(0).orEmpty()
        val prefix = parts.getOrNull(1).orEmpty()
        ipField.setText(ip)
        prefixField.setText(prefix)
    }

    private fun combineIpAndPrefix(ip: String, prefix: String): String {
        if (ip.isBlank() && prefix.isBlank()) return ""
        // By this point, validation has already ensured both are non-empty and well-formed
        return "$ip/$prefix"
    }

    private fun validateIpv4WithPrefix(ip: String, prefixText: String): Boolean {
        // Both must be empty or both must be filled
        if (ip.isEmpty() && prefixText.isEmpty()) return true
        if (ip.isEmpty() || prefixText.isEmpty()) {
            Logger.w(LOG_TAG_UI, "IPv4 validation failed: both IP and prefix must be provided together")
            return false
        }

        return try {
            // Validate IP address
            val addr = IPAddressString(ip).address
            if (addr == null) {
                Logger.w(LOG_TAG_UI, "IPv4 validation failed: invalid IP address format: $ip")
                return false
            }
            if (!addr.isIPv4) {
                Logger.w(LOG_TAG_UI, "IPv4 validation failed: not an IPv4 address: $ip")
                return false
            }

            // Only allow RFC1918 private IPv4 ranges (unique local for IPv4)
            val host = addr.toNormalizedString() // e.g. "10.0.0.1"
            if (!isRfc1918Ipv4(host)) {
                Logger.w(LOG_TAG_UI, "IPv4 validation failed: not a private/unique local address (must be 10.x.x.x, 172.16-31.x.x, or 192.168.x.x): $host")
                return false
            }

            // Validate prefix length
            val prefix = prefixText.toIntOrNull()
            if (prefix == null) {
                Logger.w(LOG_TAG_UI, "IPv4 validation failed: invalid prefix length: $prefixText")
                return false
            }
            if (prefix !in 0..32) {
                Logger.w(LOG_TAG_UI, "IPv4 validation failed: prefix length must be 0-32, got: $prefix")
                return false
            }

            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "IPv4 validation error for $ip/$prefixText: ${e.message}", e)
            false
        }
    }

    private fun validateIpv6WithPrefix(ip: String, prefixText: String): Boolean {
        // Both must be empty or both must be filled
        if (ip.isEmpty() && prefixText.isEmpty()) return true
        if (ip.isEmpty() || prefixText.isEmpty()) {
            Logger.w(LOG_TAG_UI, "IPv6 validation failed: both IP and prefix must be provided together")
            return false
        }

        return try {
            // Validate IP address
            val addr = IPAddressString(ip).address
            if (addr == null) {
                Logger.w(LOG_TAG_UI, "IPv6 validation failed: invalid IP address format: $ip")
                return false
            }
            if (!addr.isIPv6) {
                Logger.w(LOG_TAG_UI, "IPv6 validation failed: not an IPv6 address: $ip")
                return false
            }

            // Only allow Unique Local IPv6 (fc00::/7)
            val host = addr.toNormalizedString() // e.g. "fd00:abcd::1"
            if (!isUlaIpv6(host)) {
                Logger.w(LOG_TAG_UI, "IPv6 validation failed: not a unique local address (must start with fc or fd): $host")
                return false
            }

            // Validate prefix length
            val prefix = prefixText.toIntOrNull()
            if (prefix == null) {
                Logger.w(LOG_TAG_UI, "IPv6 validation failed: invalid prefix length: $prefixText")
                return false
            }
            if (prefix !in 0..128) {
                Logger.w(LOG_TAG_UI, "IPv6 validation failed: prefix length must be 0-128, got: $prefix")
                return false
            }

            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "IPv6 validation error for $ip/$prefixText: ${e.message}", e)
            false
        }
    }

    // RFC1918 private IPv4 ranges using simple string prefix checks.
    // This assumes normalized dotted decimal addresses like "10.0.0.1".
    private fun isRfc1918Ipv4(host: String): Boolean {
        // 10.0.0.0/8
        if (host.startsWith("10.")) return true

        // 172.16.0.0/12: 172.16.0.0 – 172.31.255.255
        if (host.startsWith("172.")) {
            val parts = host.split(".")
            if (parts.size == 4) {
                val second = parts[1].toIntOrNull() ?: return false
                if (second in 16..31) return true
            }
        }

        // 192.168.0.0/16
        if (host.startsWith("192.168.")) return true

        return false
    }

    // Unique Local IPv6 (fc00::/7) string check.
    // Normalized IPv6 will start with "fc" or "fd" for ULA.
    private fun isUlaIpv6(host: String): Boolean {
        val lower = host.lowercase()
        // fc00::/7 = addresses starting with fc or fd
        return lower.startsWith("fc") || lower.startsWith("fd")
    }
}
