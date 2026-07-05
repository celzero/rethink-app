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


import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.bottomsheet.RuleSheetDualTextFieldRow
import com.celzero.bravedns.ui.bottomsheet.RuleSheetModeToggle
import com.celzero.bravedns.ui.bottomsheet.RuleSheetModal
import com.celzero.bravedns.ui.bottomsheet.RuleSheetSectionTitle
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetActionRow
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetCard
import com.celzero.bravedns.ui.compose.theme.RethinkSecondaryActionStyle
import inet.ipaddr.IPAddressString
import io.github.aakira.napier.Napier

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomLanIpSheet(
    persistentState: PersistentState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var initialMode by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(false) }

    var gatewayIpv4 by remember { mutableStateOf("") }
    var gatewayIpv4Prefix by remember { mutableStateOf("") }
    var gatewayIpv6 by remember { mutableStateOf("") }
    var gatewayIpv6Prefix by remember { mutableStateOf("") }

    var routerIpv4 by remember { mutableStateOf("") }
    var routerIpv4Prefix by remember { mutableStateOf("") }
    var routerIpv6 by remember { mutableStateOf("") }
    var routerIpv6Prefix by remember { mutableStateOf("") }

    var dnsIpv4 by remember { mutableStateOf("") }
    var dnsIpv4Prefix by remember { mutableStateOf("") }
    var dnsIpv6 by remember { mutableStateOf("") }
    var dnsIpv6Prefix by remember { mutableStateOf("") }

    var errorMessage by remember { mutableStateOf("") }

    fun loadDefaultAutoValues() {
        gatewayIpv4 = GATEWAY_4_IP
        gatewayIpv4Prefix = GATEWAY_4_PREFIX.toString()
        gatewayIpv6 = GATEWAY_6_IP
        gatewayIpv6Prefix = GATEWAY_6_PREFIX.toString()

        routerIpv4 = ROUTER_4_IP
        routerIpv4Prefix = ROUTER_4_PREFIX.toString()
        routerIpv6 = ROUTER_6_IP
        routerIpv6Prefix = ROUTER_6_PREFIX.toString()

        dnsIpv4 = DNS_4_IP
        dnsIpv4Prefix = DNS_4_PREFIX.toString()
        dnsIpv6 = DNS_6_IP
        dnsIpv6Prefix = DNS_6_PREFIX.toString()
    }

    fun loadManualValues() {
        if (persistentState.customLanGatewayIpv4.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanGatewayIpv4) { ip, prefix ->
                gatewayIpv4 = ip
                gatewayIpv4Prefix = prefix
            }
        } else {
            gatewayIpv4 = GATEWAY_4_IP
            gatewayIpv4Prefix = GATEWAY_4_PREFIX.toString()
        }

        if (persistentState.customLanGatewayIpv6.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanGatewayIpv6) { ip, prefix ->
                gatewayIpv6 = ip
                gatewayIpv6Prefix = prefix
            }
        } else {
            gatewayIpv6 = GATEWAY_6_IP
            gatewayIpv6Prefix = GATEWAY_6_PREFIX.toString()
        }

        if (persistentState.customLanRouterIpv4.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanRouterIpv4) { ip, prefix ->
                routerIpv4 = ip
                routerIpv4Prefix = prefix
            }
        } else {
            routerIpv4 = ROUTER_4_IP
            routerIpv4Prefix = ROUTER_4_PREFIX.toString()
        }

        if (persistentState.customLanRouterIpv6.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanRouterIpv6) { ip, prefix ->
                routerIpv6 = ip
                routerIpv6Prefix = prefix
            }
        } else {
            routerIpv6 = ROUTER_6_IP
            routerIpv6Prefix = ROUTER_6_PREFIX.toString()
        }

        if (persistentState.customLanDnsIpv4.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanDnsIpv4) { ip, prefix ->
                dnsIpv4 = ip
                dnsIpv4Prefix = prefix
            }
        } else {
            dnsIpv4 = DNS_4_IP
            dnsIpv4Prefix = DNS_4_PREFIX.toString()
        }

        if (persistentState.customLanDnsIpv6.isNotBlank()) {
            loadIpAndPrefixIntoFields(persistentState.customLanDnsIpv6) { ip, prefix ->
                dnsIpv6 = ip
                dnsIpv6Prefix = prefix
            }
        } else {
            dnsIpv6 = DNS_6_IP
            dnsIpv6Prefix = DNS_6_PREFIX.toString()
        }
    }

    fun hideError() {
        errorMessage = ""
    }

    fun resetManualFields() {
        loadDefaultAutoValues()
        hideError()
        Toast.makeText(context, R.string.custom_lan_ip_saved_manual, Toast.LENGTH_SHORT).show()
    }

    fun saveAutoMode() {
        try {
            val modeChanged = initialMode != currentMode
            persistentState.customLanIpMode = false
            if (modeChanged) {
                persistentState.customModeOrIpChanged = true
                Napier.i("Custom LAN IPs cleared (switched to AUTO)")
            }
            hideError()
            Toast.makeText(context, R.string.custom_lan_ip_saved_auto, Toast.LENGTH_SHORT).show()
            onDismiss()
        } catch (e: Exception) {
            Napier.e("err saving custom lan ip (auto): ${e.message}")
            errorMessage = context.getString(R.string.custom_lan_ip_save_error)
        }
    }

    fun saveManualMode() {
        try {
            val gatewayV4 = gatewayIpv4.trim()
            val gatewayV4Prefix = gatewayIpv4Prefix.trim()
            val gatewayV6 = gatewayIpv6.trim()
            val gatewayV6Prefix = gatewayIpv6Prefix.trim()

            val routerV4 = routerIpv4.trim()
            val routerV4Prefix = routerIpv4Prefix.trim()
            val routerV6 = routerIpv6.trim()
            val routerV6Prefix = routerIpv6Prefix.trim()

            val dnsV4 = dnsIpv4.trim()
            val dnsV4Prefix = dnsIpv4Prefix.trim()
            val dnsV6 = dnsIpv6.trim()
            val dnsV6Prefix = dnsIpv6Prefix.trim()

            if (!validateIpv4WithPrefix(gatewayV4, gatewayV4Prefix) ||
                !validateIpv6WithPrefix(gatewayV6, gatewayV6Prefix) ||
                !validateIpv4WithPrefix(routerV4, routerV4Prefix) ||
                !validateIpv6WithPrefix(routerV6, routerV6Prefix) ||
                !validateIpv4WithPrefix(dnsV4, dnsV4Prefix) ||
                !validateIpv6WithPrefix(dnsV6, dnsV6Prefix)
            ) {
                errorMessage = context.getString(R.string.custom_lan_ip_validation_error)
                return
            }

            val newGatewayV4 = combineIpAndPrefix(gatewayV4, gatewayV4Prefix)
            val newGatewayV6 = combineIpAndPrefix(gatewayV6, gatewayV6Prefix)
            val newRouterV4 = combineIpAndPrefix(routerV4, routerV4Prefix)
            val newRouterV6 = combineIpAndPrefix(routerV6, routerV6Prefix)
            val newDnsV4 = combineIpAndPrefix(dnsV4, dnsV4Prefix)
            val newDnsV6 = combineIpAndPrefix(dnsV6, dnsV6Prefix)

            val ipValuesChanged =
                newGatewayV4 != persistentState.customLanGatewayIpv4 ||
                    newGatewayV6 != persistentState.customLanGatewayIpv6 ||
                    newRouterV4 != persistentState.customLanRouterIpv4 ||
                    newRouterV6 != persistentState.customLanRouterIpv6 ||
                    newDnsV4 != persistentState.customLanDnsIpv4 ||
                    newDnsV6 != persistentState.customLanDnsIpv6

            val modeChanged = initialMode != currentMode

            persistentState.customLanIpMode = true
            persistentState.customLanGatewayIpv4 = newGatewayV4
            persistentState.customLanGatewayIpv6 = newGatewayV6
            persistentState.customLanRouterIpv4 = newRouterV4
            persistentState.customLanRouterIpv6 = newRouterV6
            persistentState.customLanDnsIpv4 = newDnsV4
            persistentState.customLanDnsIpv6 = newDnsV6

            if (modeChanged || ipValuesChanged) {
                persistentState.customModeOrIpChanged = true
                Napier.i(
                    "Custom LAN IPs changed - mode changed: $modeChanged, IP values changed: $ipValuesChanged"
                )
            }

            hideError()
            Toast.makeText(context, R.string.custom_lan_ip_saved_manual, Toast.LENGTH_SHORT).show()
            onDismiss()
        } catch (e: Exception) {
            Napier.e("err saving custom lan ip (manual): ${e.message}")
            errorMessage = context.getString(R.string.custom_lan_ip_save_error)
        }
    }

    LaunchedEffect(Unit) {
        persistentState.customModeOrIpChanged = false
        initialMode = persistentState.customLanIpMode
        currentMode = initialMode
        if (currentMode) {
            loadManualValues()
        } else {
            loadDefaultAutoValues()
        }
    }

    RuleSheetModal(onDismissRequest = onDismiss) {
        val manualEnabled = currentMode

        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal = Dimensions.screenPaddingHorizontal,
                        vertical = Dimensions.spacingLg
                    )
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
        ) {
            RethinkBottomSheetCard {
                RuleSheetModeToggle(
                    autoLabel = context.getString(R.string.settings_ip_text_ipv46),
                    manualLabel = context.getString(R.string.lbl_manual),
                    isAutoSelected = !currentMode,
                    onAutoClick = {
                        currentMode = false
                        loadDefaultAutoValues()
                        hideError()
                    },
                    onManualClick = {
                        currentMode = true
                        loadManualValues()
                        hideError()
                    }
                )
                Text(
                    text =
                        if (currentMode) {
                            context.getString(R.string.custom_lan_ip_manual_desc)
                        } else {
                            context.getString(R.string.custom_lan_ip_auto_desc)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RethinkBottomSheetCard {
                RuleSheetSectionTitle(
                    text = context.getString(R.string.custom_lan_ip_gateway),
                    horizontalPadding = 0.dp
                )
                RuleSheetDualTextFieldRow(
                    primaryValue = gatewayIpv4,
                    onPrimaryValueChange = { gatewayIpv4 = it },
                    secondaryValue = gatewayIpv4Prefix,
                    onSecondaryValueChange = { gatewayIpv4Prefix = it },
                    primaryLabel = { Text(text = context.getString(R.string.settings_ip_text_ipv4)) },
                    secondaryLabel = { Text(text = context.getString(R.string.lbl_prefix)) },
                    enabled = manualEnabled
                )
                RuleSheetDualTextFieldRow(
                    primaryValue = gatewayIpv6,
                    onPrimaryValueChange = { gatewayIpv6 = it },
                    secondaryValue = gatewayIpv6Prefix,
                    onSecondaryValueChange = { gatewayIpv6Prefix = it },
                    primaryLabel = { Text(text = context.getString(R.string.settings_ip_text_ipv6)) },
                    secondaryLabel = { Text(text = context.getString(R.string.lbl_prefix)) },
                    enabled = manualEnabled
                )

                RuleSheetSectionTitle(
                    text = context.getString(R.string.custom_lan_ip_router),
                    horizontalPadding = 0.dp
                )
                RuleSheetDualTextFieldRow(
                    primaryValue = routerIpv4,
                    onPrimaryValueChange = { routerIpv4 = it },
                    secondaryValue = routerIpv4Prefix,
                    onSecondaryValueChange = { routerIpv4Prefix = it },
                    primaryLabel = { Text(text = context.getString(R.string.settings_ip_text_ipv4)) },
                    secondaryLabel = { Text(text = context.getString(R.string.lbl_prefix)) },
                    enabled = manualEnabled
                )
                RuleSheetDualTextFieldRow(
                    primaryValue = routerIpv6,
                    onPrimaryValueChange = { routerIpv6 = it },
                    secondaryValue = routerIpv6Prefix,
                    onSecondaryValueChange = { routerIpv6Prefix = it },
                    primaryLabel = { Text(text = context.getString(R.string.settings_ip_text_ipv6)) },
                    secondaryLabel = { Text(text = context.getString(R.string.lbl_prefix)) },
                    enabled = manualEnabled
                )

                RuleSheetSectionTitle(
                    text = context.getString(R.string.dns_mode_info_title),
                    horizontalPadding = 0.dp
                )
                RuleSheetDualTextFieldRow(
                    primaryValue = dnsIpv4,
                    onPrimaryValueChange = { dnsIpv4 = it },
                    secondaryValue = dnsIpv4Prefix,
                    onSecondaryValueChange = { dnsIpv4Prefix = it },
                    primaryLabel = { Text(text = context.getString(R.string.settings_ip_text_ipv4)) },
                    secondaryLabel = { Text(text = context.getString(R.string.lbl_prefix)) },
                    enabled = manualEnabled
                )
                RuleSheetDualTextFieldRow(
                    primaryValue = dnsIpv6,
                    onPrimaryValueChange = { dnsIpv6 = it },
                    secondaryValue = dnsIpv6Prefix,
                    onSecondaryValueChange = { dnsIpv6Prefix = it },
                    primaryLabel = { Text(text = context.getString(R.string.settings_ip_text_ipv6)) },
                    secondaryLabel = { Text(text = context.getString(R.string.lbl_prefix)) },
                    enabled = manualEnabled
                )
            }

            RethinkBottomSheetActionRow(
                primaryText = context.getString(R.string.lbl_save),
                onPrimaryClick = {
                    if (!currentMode) {
                        saveAutoMode()
                    } else {
                        saveManualMode()
                    }
                },
                secondaryText = context.getString(R.string.lbl_reset),
                onSecondaryClick = {
                    if (!currentMode) {
                        Toast.makeText(context, R.string.custom_lan_ip_saved_auto, Toast.LENGTH_SHORT).show()
                    } else {
                        resetManualFields()
                    }
                },
                secondaryEnabled = currentMode,
                secondaryStyle = RethinkSecondaryActionStyle.TEXT
            )

            if (errorMessage.isNotBlank()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Dimensions.spacingXs)
                )
            }
        }
    }
}

private fun loadIpAndPrefixIntoFields(
    value: String,
    onLoaded: (String, String) -> Unit
) {
    if (value.isBlank()) {
        onLoaded("", "")
        return
    }
    val parts = value.split("/")
    val ip = parts.getOrNull(0).orEmpty()
    val prefix = parts.getOrNull(1).orEmpty()
    onLoaded(ip, prefix)
}

private fun combineIpAndPrefix(ip: String, prefix: String): String {
    if (ip.isBlank() && prefix.isBlank()) return ""
    return "$ip/$prefix"
}

private fun validateIpv4WithPrefix(ip: String, prefixText: String): Boolean {
    if (ip.isEmpty() && prefixText.isEmpty()) return true
    if (ip.isEmpty() || prefixText.isEmpty()) {
        Napier.w("IPv4 validation failed: both IP and prefix must be provided together")
        return false
    }

    return try {
        val addr = IPAddressString(ip).address
        if (addr == null) {
            Napier.w("IPv4 validation failed: invalid IP address format: $ip")
            return false
        }
        if (!addr.isIPv4) {
            Napier.w("IPv4 validation failed: not an IPv4 address: $ip")
            return false
        }

        val host = addr.toNormalizedString()
        if (!isRfc1918Ipv4(host)) {
            Napier.w(
                "IPv4 validation failed: not a private/unique local address (must be 10.x.x.x, 172.16-31.x.x, or 192.168.x.x): $host"
            )
            return false
        }

        val prefix = prefixText.toIntOrNull()
        if (prefix == null) {
            Napier.w("IPv4 validation failed: invalid prefix length: $prefixText")
            return false
        }
        if (prefix !in 0..32) {
            Napier.w("IPv4 validation failed: prefix out of range: $prefixText")
            return false
        }
        true
    } catch (e: Exception) {
        Napier.w("IPv4 validation failed: ${e.message}")
        false
    }
}

private fun validateIpv6WithPrefix(ip: String, prefixText: String): Boolean {
    if (ip.isEmpty() && prefixText.isEmpty()) return true
    if (ip.isEmpty() || prefixText.isEmpty()) {
        Napier.w("IPv6 validation failed: both IP and prefix must be provided together")
        return false
    }

    return try {
        val addr = IPAddressString(ip).address
        if (addr == null) {
            Napier.w("IPv6 validation failed: invalid IP address format: $ip")
            return false
        }
        if (!addr.isIPv6) {
            Napier.w("IPv6 validation failed: not an IPv6 address: $ip")
            return false
        }

        val host = addr.toNormalizedString()
        if (!isUlaIpv6(host)) {
            Napier.w(
                "IPv6 validation failed: not a unique local address (must start with fc or fd): $host"
            )
            return false
        }

        val prefix = prefixText.toIntOrNull()
        if (prefix == null) {
            Napier.w("IPv6 validation failed: invalid prefix length: $prefixText")
            return false
        }
        if (prefix !in 0..128) {
            Napier.w("IPv6 validation failed: prefix out of range: $prefixText")
            return false
        }
        true
    } catch (e: Exception) {
        Napier.w("IPv6 validation failed: ${e.message}")
        false
    }
}

private fun isRfc1918Ipv4(host: String): Boolean {
    if (host.startsWith("10.")) return true

    if (host.startsWith("172.")) {
        val parts = host.split(".")
        if (parts.size == 4) {
            val second = parts[1].toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
    }

    if (host.startsWith("192.168.")) return true

    return false
}

private fun isUlaIpv6(host: String): Boolean {
    val lower = host.lowercase()
    return lower.startsWith("fc") || lower.startsWith("fd")
}
