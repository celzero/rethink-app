/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.dns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DnsProxyEndpointDAO
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.material3.Text as M3Text

/**
 * Custom DNS Proxy add screen (`dns/proxy/add`).
 *
 * Name + IP(s) + Port only — no app picker; the endpoint is stored
 * as an external proxy with no app binding. Validation matches the
 * phone's `DnsProxyListFragment` dialog: every entry must parse as
 * an IP, the port must parse as an Int, and LAN IPs require a valid
 * local port. Saving inserts, re-reads the row (auto-generated id is
 * not written back onto the inserted entity) and activates it via
 * [AppConfig.handleDnsProxyChanges], which restarts the VPN through
 * the usual connected-dns pipeline.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyAddScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val appConfig = koinInject<AppConfig>()
    val dnsProxyDao = koinInject<DnsProxyEndpointDAO>()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("Proxy") }
    var ip by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    TvScreenScaffold(
        title = "Add custom DNS proxy",
        subtitle = "Forward DNS to a local or remote UDP proxy.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            FormField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                hint = "Short label shown in the DNS proxy picker",
            )
            FormField(
                label = "IP",
                value = ip,
                onValueChange = { ip = it },
                hint = "127.0.0.1 (comma-separated IPs allowed)",
            )
            FormField(
                label = "Port",
                value = port,
                onValueChange = { port = it },
                hint = "e.g. 5353",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            saveError?.let { msg ->
                M3Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(TvSpacing.RowGap))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
                PrimaryButton(
                    label = if (saving) "Saving…" else "Save and activate",
                    enabled = !saving,
                    onClick = {
                        when (val form = parseProxyForm(ip, port)) {
                            is ProxyForm.Error -> saveError = form.message
                            is ProxyForm.Input -> {
                                saving = true
                                saveError = null
                                val epName = name
                                val proxyType = context.getString(
                                    R.string.cd_dns_proxy_mode_external,
                                )
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        persistDnsProxy(appConfig, dnsProxyDao, epName, proxyType, form)
                                        navController?.popBackStack()
                                    } catch (e: Exception) {
                                        saveError = e.message ?: "Save failed"
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        }
                    },
                )
                SecondaryButton(
                    label = "Cancel",
                    onClick = { navController?.popBackStack() },
                )
            }
        }
    }
}

private sealed interface ProxyForm {
    data class Error(val message: String) : ProxyForm
    data class Input(val ips: List<String>, val port: Int) : ProxyForm
}

// Same rules as the phone's DnsProxyListFragment dialog: every entry
// must parse as an IP, the port as an Int, LAN IPs need a local port.
private fun parseProxyForm(ip: String, port: String): ProxyForm {
    val ips = ip.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val invalidIps = ips.filter { !IPAddressString(it).isIPAddress }
    val portInt = port.trim().toIntOrNull()
    val lanPortInvalid = portInt != null &&
        ips.any { Utilities.isLanIpv4(it) && !Utilities.isValidLocalPort(portInt) }
    return when {
        ips.isEmpty() -> ProxyForm.Error("Enter at least one IP address.")
        invalidIps.isNotEmpty() ->
            ProxyForm.Error("Invalid IP: ${invalidIps.joinToString(", ")}")
        portInt == null -> ProxyForm.Error("Port must be a number.")
        portInt !in 1..65535 -> ProxyForm.Error("Port must be between 1 and 65535.")
        lanPortInvalid ->
            ProxyForm.Error("Port $portInt is not valid for a LAN address.")
        else -> ProxyForm.Input(ips, portInt)
    }
}

private suspend fun persistDnsProxy(
    appConfig: AppConfig,
    dnsProxyDao: DnsProxyEndpointDAO,
    rawName: String,
    proxyType: String,
    form: ProxyForm.Input,
) {
    val endpoint = DnsProxyEndpoint(
        id = 0,
        proxyName = rawName.trim().ifBlank { "Proxy" },
        proxyType = proxyType,
        proxyAppName = "",
        proxyIP = form.ips.joinToString(","),
        proxyPort = form.port,
        isSelected = true,
        isCustom = true,
        modifiedDataTime = 0L,
        latency = 0,
    )
    val inserted = appConfig.insertDnsproxyEndpoint(endpoint).let { id ->
        // IGNORE conflict (-1) or unknown id: fall back to IP+port+name lookup
        if (id > 0) dnsProxyDao.getById(id.toInt()) else null
    } ?: dnsProxyDao.getAll().firstOrNull {
        it.proxyIP == endpoint.proxyIP &&
            it.proxyPort == endpoint.proxyPort &&
            it.proxyName == endpoint.proxyName
    }
    if (inserted != null) {
        inserted.isSelected = true
        appConfig.handleDnsProxyChanges(inserted)
    }
}
