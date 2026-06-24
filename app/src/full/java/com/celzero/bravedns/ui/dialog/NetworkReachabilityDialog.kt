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
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.celzero.bravedns.R
import com.celzero.bravedns.service.ConnectionMonitor
import com.celzero.bravedns.service.ConnectionMonitor.Companion.SCHEME_HTTP
import com.celzero.bravedns.service.ConnectionMonitor.Companion.SCHEME_HTTPS
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.bottomsheet.RuleSheetLabeledControlRow
import com.celzero.bravedns.ui.bottomsheet.RuleSheetModeToggle
import com.celzero.bravedns.ui.bottomsheet.RuleSheetModal
import com.celzero.bravedns.ui.bottomsheet.RuleSheetTextFieldRow
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetActionRow
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetCard
import com.celzero.bravedns.ui.compose.theme.RethinkSecondaryActionStyle
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils
import inet.ipaddr.IPAddress.IPVersion
import inet.ipaddr.IPAddressString
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val URL4 = "IPv4"
private const val URL6 = "IPv6"
private const val URL_SEGMENT4 = "#ipv4"
private const val URL_SEGMENT6 = "#ipv6"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkReachabilitySheet(
    persistentState: PersistentState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var useAuto by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var buttonsEnabled by remember { mutableStateOf(true) }

    var ipv4Address1 by remember { mutableStateOf("") }
    var ipv4Address2 by remember { mutableStateOf("") }
    var ipv6Address1 by remember { mutableStateOf("") }
    var ipv6Address2 by remember { mutableStateOf("") }
    var urlV4Address1 by remember { mutableStateOf("") }
    var urlV4Address2 by remember { mutableStateOf("") }
    var urlV6Address1 by remember { mutableStateOf("") }
    var urlV6Address2 by remember { mutableStateOf("") }

    var statusIpv41 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }
    var statusIpv42 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }
    var statusUrlV41 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }
    var statusUrlV42 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }
    var statusIpv61 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }
    var statusIpv62 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }
    var statusUrlV61 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }
    var statusUrlV62 by remember { mutableStateOf<ConnectionMonitor.ProbeResult?>(null) }

    var progressIpv41 by remember { mutableStateOf(false) }
    var progressIpv42 by remember { mutableStateOf(false) }
    var progressUrlV41 by remember { mutableStateOf(false) }
    var progressUrlV42 by remember { mutableStateOf(false) }
    var progressIpv61 by remember { mutableStateOf(false) }
    var progressIpv62 by remember { mutableStateOf(false) }
    var progressUrlV61 by remember { mutableStateOf(false) }
    var progressUrlV62 by remember { mutableStateOf(false) }

    fun setAllStatusIconsVisibility(visible: Boolean) {
        if (!visible) {
            statusIpv41 = null
            statusIpv42 = null
            statusUrlV41 = null
            statusUrlV42 = null
            statusIpv61 = null
            statusIpv62 = null
            statusUrlV61 = null
            statusUrlV62 = null
        }
    }

    fun setAllProgressBarsVisibility(visible: Boolean) {
        progressIpv41 = visible
        progressIpv42 = visible
        progressUrlV41 = visible
        progressUrlV42 = visible
        progressIpv61 = visible
        progressIpv62 = visible
        progressUrlV61 = visible
        progressUrlV62 = visible
    }

    fun updateAutoModeUi() {
        val autoTxt = context.getString(R.string.lbl_auto)
        ipv4Address1 = ConnectionMonitor.SCHEME_IP + ConnectionMonitor.PROTOCOL_V4 + " " + autoTxt
        ipv4Address2 = ConnectionMonitor.SCHEME_HTTPS + ConnectionMonitor.PROTOCOL_V4 + " " + autoTxt
        ipv6Address1 = ConnectionMonitor.SCHEME_IP + ConnectionMonitor.PROTOCOL_V6 + " " + autoTxt
        ipv6Address2 = ConnectionMonitor.SCHEME_HTTPS + ConnectionMonitor.PROTOCOL_V6 + " " + autoTxt
        errorMessage = ""
        setAllStatusIconsVisibility(false)
        setAllProgressBarsVisibility(false)
    }

    fun updateManualModeUi() {
        val itemsIp4 = persistentState.pingv4Ips.split(",").toTypedArray()
        val itemsIp6 = persistentState.pingv6Ips.split(",").toTypedArray()
        val itemsUrl4 = persistentState.pingv4Url.split(",").toTypedArray()
        val itemsUrl6 = persistentState.pingv6Url.split(",").toTypedArray()

        ipv4Address1 = itemsIp4.getOrNull(0) ?: ""
        ipv4Address2 = itemsIp4.getOrNull(1) ?: ""
        urlV4Address1 = itemsUrl4.getOrNull(0)?.split(URL_SEGMENT4)?.firstOrNull() ?: Constants.urlV4probes[0]
        urlV4Address2 = itemsUrl4.getOrNull(1)?.split(URL_SEGMENT4)?.firstOrNull() ?: Constants.urlV4probes[0]
        ipv6Address1 = itemsIp6.getOrNull(0) ?: ""
        ipv6Address2 = itemsIp6.getOrNull(1) ?: ""
        urlV6Address1 = itemsUrl6.getOrNull(0)?.split(URL_SEGMENT6)?.firstOrNull() ?: Constants.urlV6probes[0]
        urlV6Address2 = itemsUrl6.getOrNull(1)?.split(URL_SEGMENT6)?.firstOrNull() ?: Constants.urlV6probes[1]
        errorMessage = ""
        setAllStatusIconsVisibility(false)
        setAllProgressBarsVisibility(false)
    }

    fun resetToDefaults() {
        ipv4Address1 = Constants.ip4probes[0]
        ipv4Address2 = Constants.ip4probes[1]
        urlV4Address1 = Constants.urlV4probes[0].split(URL_SEGMENT4).firstOrNull() ?: Constants.urlV4probes[0]
        urlV4Address2 = Constants.urlV4probes[1].split(URL_SEGMENT4).firstOrNull() ?: Constants.urlV4probes[1]
        ipv6Address1 = Constants.ip6probes[0]
        ipv6Address2 = Constants.ip6probes[1]
        urlV6Address1 = Constants.urlV6probes[0].split(URL_SEGMENT6).firstOrNull() ?: Constants.urlV6probes[0]
        urlV6Address2 = Constants.urlV6probes[1].split(URL_SEGMENT6).firstOrNull() ?: Constants.urlV6probes[1]
        errorMessage = ""
        setAllStatusIconsVisibility(false)
        setAllProgressBarsVisibility(false)
    }

    fun updateButtonsEnabled(enabled: Boolean) {
        buttonsEnabled = enabled
    }

    fun updateStatusIcons(results: Map<String, ConnectionMonitor.ProbeResult?>) {
        statusIpv41 = results["ipv4_1"]
        statusIpv42 = results["ipv4_2"]
        statusUrlV41 = results["url4_1"]
        statusUrlV42 = results["url4_2"]
        statusIpv61 = results["ipv6_1"]
        statusIpv62 = results["ipv6_2"]
        statusUrlV61 = results["url6_1"]
        statusUrlV62 = results["url6_2"]
        setAllStatusIconsVisibility(true)
    }

    fun testConnections() {
        updateButtonsEnabled(false)
        setAllProgressBarsVisibility(true)
        setAllStatusIconsVisibility(false)
        errorMessage = ""

        scope.launch(Dispatchers.IO) {
            try {
                val results = mutableMapOf<String, ConnectionMonitor.ProbeResult?>()
                val v41 =
                    if (useAuto) ConnectionMonitor.SCHEME_IP + ":" + ConnectionMonitor.PROTOCOL_V4 else ipv4Address1
                val v42 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTPS + ":" + ConnectionMonitor.PROTOCOL_V4 else ipv4Address2
                val v61 =
                    if (useAuto) ConnectionMonitor.SCHEME_IP + ":" + ConnectionMonitor.PROTOCOL_V6 else ipv6Address1
                val v62 =
                    if (useAuto) ConnectionMonitor.SCHEME_HTTPS + ":" + ConnectionMonitor.PROTOCOL_V6 else ipv6Address2

                results["ipv4_1"] = probeIpOrUrl(v41, useAuto)
                results["ipv4_2"] = probeIpOrUrl(v42, useAuto)
                if (!useAuto) {
                    results["url4_1"] = probeIpOrUrl(urlV4Address1 + URL_SEGMENT4, useAuto)
                    results["url4_2"] = probeIpOrUrl(urlV4Address2 + URL_SEGMENT4, useAuto)
                }
                results["ipv6_1"] = probeIpOrUrl(v61, useAuto)
                results["ipv6_2"] = probeIpOrUrl(v62, useAuto)
                if (!useAuto) {
                    results["url6_1"] = probeIpOrUrl(urlV6Address1 + URL_SEGMENT6, useAuto)
                    results["url6_2"] = probeIpOrUrl(urlV6Address2 + URL_SEGMENT6, useAuto)
                }

                withContext(Dispatchers.Main) {
                    setAllProgressBarsVisibility(false)
                    updateStatusIcons(results)
                    updateButtonsEnabled(true)
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "NwReachability; testConnections error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.blocklist_update_check_failure)
                    setAllProgressBarsVisibility(false)
                    updateButtonsEnabled(true)
                }
            }
        }
    }

    fun saveIps() {
        if (!useAuto) {
            val valid41 = isValidIp(ipv4Address1, IPVersion.IPV4)
            val valid42 = isValidIp(ipv4Address2, IPVersion.IPV4)
            val validUrl41 = isValidUrl(urlV4Address1)
            val validUrl42 = isValidUrl(urlV4Address2)
            val valid61 = isValidIp(ipv6Address1, IPVersion.IPV6)
            val valid62 = isValidIp(ipv6Address2, IPVersion.IPV6)
            val validUrl61 = isValidUrl(urlV6Address1)
            val validUrl62 = isValidUrl(urlV6Address2)

            if (!valid41 || !valid42 || !validUrl41 || !validUrl42 || !valid61 || !valid62 || !validUrl61 || !validUrl62) {
                errorMessage = context.getString(R.string.cd_dns_proxy_error_text_1)
                return
            }
        }
        val ip4 = listOf(ipv4Address1, ipv4Address2)
        val ip6 = listOf(ipv6Address1, ipv6Address2)
        val url4Txt1 = if (urlV4Address1.contains(URL_SEGMENT4)) urlV4Address1 else urlV4Address1 + URL_SEGMENT4
        val url4Txt2 = if (urlV4Address2.contains(URL_SEGMENT4)) urlV4Address2 else urlV4Address2 + URL_SEGMENT4
        val url6Txt1 = if (urlV6Address1.contains(URL_SEGMENT6)) urlV6Address1 else urlV6Address1 + URL_SEGMENT6
        val url6Txt2 = if (urlV6Address2.contains(URL_SEGMENT6)) urlV6Address2 else urlV6Address2 + URL_SEGMENT6
        val url4Txt = listOf(url4Txt1, url4Txt2)
        val url6Txt = listOf(url6Txt1, url6Txt2)
        val isSame = persistentState.pingv4Ips == ip4.joinToString(",") &&
            persistentState.pingv6Ips == ip6.joinToString(",") &&
            persistentState.pingv4Url == url4Txt.joinToString(",") &&
            persistentState.pingv6Url == url6Txt.joinToString(",")
        if (isSame) {
            onDismiss()
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
        scope.launch(Dispatchers.IO) {
            VpnController.notifyConnectionMonitor()
        }
        onDismiss()
    }

    LaunchedEffect(Unit) {
        useAuto = persistentState.performAutoNetworkConnectivityChecks
        if (useAuto) {
            updateAutoModeUi()
        } else {
            updateManualModeUi()
        }
        setAllStatusIconsVisibility(false)
        setAllProgressBarsVisibility(false)
        errorMessage = ""
    }

    RuleSheetModal(onDismissRequest = onDismiss) {
        val protocols = VpnController.protocols()
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = Dimensions.screenPaddingHorizontal,
                        vertical = Dimensions.spacingLg
                    ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
        ) {
            RethinkBottomSheetCard {
                RuleSheetModeToggle(
                    autoLabel = context.getString(R.string.settings_ip_text_ipv46),
                    manualLabel = context.getString(R.string.lbl_manual),
                    isAutoSelected = useAuto,
                    onAutoClick = {
                        useAuto = true
                        updateAutoModeUi()
                    },
                    onManualClick = {
                        useAuto = false
                        updateManualModeUi()
                    }
                )
                Text(
                    text = context.getString(R.string.bypasses_network_restrictions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RethinkBottomSheetCard {
                ProtocolHeaderRow(
                    title = context.getString(R.string.settings_ip_text_ipv4),
                    isSupported = protocols.contains(URL4)
                ) {
                    if (!useAuto) {
                        TextButton(onClick = { resetToDefaults() }) {
                            Text(text = context.getString(R.string.brbs_restore_title))
                        }
                    }
                }
                AddressRow(
                    value = ipv4Address1,
                    onValueChange = { ipv4Address1 = it },
                    enabled = !useAuto,
                    progress = progressIpv41,
                    result = statusIpv41
                )
                AddressRow(
                    value = ipv4Address2,
                    onValueChange = { ipv4Address2 = it },
                    enabled = !useAuto,
                    progress = progressIpv42,
                    result = statusIpv42
                )
                if (!useAuto) {
                    AddressRow(
                        value = urlV4Address1,
                        onValueChange = { urlV4Address1 = it },
                        enabled = true,
                        progress = progressUrlV41,
                        result = statusUrlV41
                    )
                    AddressRow(
                        value = urlV4Address2,
                        onValueChange = { urlV4Address2 = it },
                        enabled = true,
                        progress = progressUrlV42,
                        result = statusUrlV42
                    )
                }
            }

            RethinkBottomSheetCard {
                ProtocolHeaderRow(
                    title = context.getString(R.string.settings_ip_text_ipv6),
                    isSupported = protocols.contains(URL6)
                )
                AddressRow(
                    value = ipv6Address1,
                    onValueChange = { ipv6Address1 = it },
                    enabled = !useAuto,
                    progress = progressIpv61,
                    result = statusIpv61
                )
                AddressRow(
                    value = ipv6Address2,
                    onValueChange = { ipv6Address2 = it },
                    enabled = !useAuto,
                    progress = progressIpv62,
                    result = statusIpv62
                )
                if (!useAuto) {
                    AddressRow(
                        value = urlV6Address1,
                        onValueChange = { urlV6Address1 = it },
                        enabled = true,
                        progress = progressUrlV61,
                        result = statusUrlV61
                    )
                    AddressRow(
                        value = urlV6Address2,
                        onValueChange = { urlV6Address2 = it },
                        enabled = true,
                        progress = progressUrlV62,
                        result = statusUrlV62
                    )
                }
            }

            RethinkBottomSheetActionRow(
                primaryText = context.getString(R.string.lbl_save),
                onPrimaryClick = { saveIps() },
                primaryEnabled = buttonsEnabled,
                secondaryText = context.getString(R.string.lbl_test),
                onSecondaryClick = { testConnections() },
                secondaryEnabled = buttonsEnabled,
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

@Composable
private fun ProtocolHeaderRow(
    title: String,
    isSupported: Boolean,
    trailing: @Composable (() -> Unit)? = null
) {
    RuleSheetLabeledControlRow(
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)) {
                StatusIcon(isOk = isSupported)
                Text(text = title)
            }
        },
        control = trailing,
        horizontalPadding = Dimensions.spacingNone,
        controlWeight = 0.7f
    )
}

@Composable
private fun StatusIcon(isOk: Boolean) {
    val icon =
        if (isOk) {
            R.drawable.ic_tick
        } else {
            R.drawable.ic_cross_accent
        }
    androidx.compose.material3.Icon(
        painter = painterResource(id = icon),
        contentDescription = null,
        tint = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun AddressRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    progress: Boolean,
    result: ConnectionMonitor.ProbeResult?
) {
    val trailingContent: (@Composable (() -> Unit))? =
        when {
            progress -> {
                { CircularProgressIndicator() }
            }
            result != null -> {
                {
                    val resId = getDrawableForProbeResult(result)
                    androidx.compose.material3.Icon(
                        painter = painterResource(id = resId),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            else -> null
        }

    RuleSheetTextFieldRow(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        trailing = trailingContent
    )
}

private fun getDrawableForProbeResult(probeResult: ConnectionMonitor.ProbeResult?): Int {
    if (probeResult == null || !probeResult.ok) return R.drawable.ic_cross_accent

    val cap = probeResult.capabilities
    return when {
        cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> R.drawable.ic_firewall_wifi_on
        cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> R.drawable.ic_firewall_data_on
        else -> R.drawable.ic_tick
    }
}

private suspend fun probeIpOrUrl(ipOrUrl: String, useAuto: Boolean): ConnectionMonitor.ProbeResult? {
    return try {
        VpnController.probeIpOrUrl(ipOrUrl, useAuto)
    } catch (e: Exception) {
        Logger.d(LOG_TAG_UI, "NwReachability; probeIpOrUrl err: ${e.message}")
        null
    }
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
