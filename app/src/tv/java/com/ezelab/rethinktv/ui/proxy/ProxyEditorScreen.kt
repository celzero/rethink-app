/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.proxy

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * TV editor for the SOCKS5 or HTTP custom-proxy endpoint.
 *
 * Replaces the upstream phone's bottom-sheet form (a single dialog
 * with host/port/user/password and an enable switch) with a
 * full-screen form sized for a 10-foot D-pad UX. Two routes share
 * this composable — see [ProxyEditorKind] — because the persistence
 * is symmetric apart from the [AppConfig.updateCustomSocks5Proxy] vs
 * [AppConfig.updateCustomHttpProxy] write call.
 *
 * Wiring:
 *  * Reads — [AppConfig.getSocks5ProxyDetails] /
 *    [AppConfig.getHttpProxyDetails] (suspending, dispatched on IO
 *    via [LaunchedEffect]).
 *  * Save — writes through `updateCustom*Proxy`, which itself
 *    persists the [ProxyEndpoint] row and calls
 *    [AppConfig.addProxy] so the engine starts using the new
 *    endpoint immediately.
 *  * Disable — calls [AppConfig.removeProxy] with the matching
 *    [AppConfig.ProxyType] / [AppConfig.ProxyProvider.CUSTOM]
 *    combination so the engine stops routing through it.
 *
 * Notes:
 *  * Orbot has a separate editor (see [OrbotInfoScreen] below) —
 *    Orbot writes only over the Orbot-app handshake, not via this
 *    editor.
 *  * `OutlinedTextField` from the Material 3 (phone) library is
 *    used because `tv.material3` doesn't ship a TextField yet. The
 *    field focuses correctly on TV and triggers the system IME on
 *    centre-key press; D-pad up/down moves between fields.
 */
enum class ProxyEditorKind { SOCKS5, HTTP }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyEditorScreen(kind: ProxyEditorKind) {
    val context = LocalContext.current
    val appConfig = koinInject<AppConfig>()
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<ProxyEndpoint?>(null) }
    var enabled by remember { mutableStateOf(false) }

    LaunchedEffect(kind) {
        val ep = withContext(Dispatchers.IO) {
            when (kind) {
                ProxyEditorKind.SOCKS5 -> appConfig.getSocks5ProxyDetails()
                ProxyEditorKind.HTTP -> appConfig.getHttpProxyDetails()
            }
        }
        existing = ep
        host = ep?.proxyIP.orEmpty()
        port = ep?.proxyPort?.takeIf { it > 0 }?.toString() ?: ""
        user = ep?.userName.orEmpty()
        pass = ep?.password.orEmpty()
        enabled = when (kind) {
            ProxyEditorKind.SOCKS5 -> appConfig.isCustomSocks5Enabled()
            ProxyEditorKind.HTTP -> appConfig.isCustomHttpProxyEnabled()
        }
        loaded = true
    }

    val title = when (kind) {
        ProxyEditorKind.SOCKS5 -> "SOCKS5 proxy"
        ProxyEditorKind.HTTP -> "HTTP proxy"
    }
    val subtitle = if (enabled) "Currently routing through this proxy." else "Configured but not active."

    com.ezelab.rethinktv.ui.common.TvScreenScaffold(
        title = title,
        subtitle = if (loaded) subtitle else "Loading…",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Host or IP") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val p = port.toIntOrNull() ?: 0
                    if (host.isBlank() || p <= 0 || p > 65535) {
                        Toast.makeText(context, "Enter a valid host and port (1–65535).", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch(Dispatchers.IO) {
                        val ep = existing?.also {
                            it.proxyIP = host
                            it.proxyPort = p
                            it.userName = user.ifBlank { null }
                            it.password = pass.ifBlank { null }
                            it.isSelected = true
                            it.modifiedDataTime = System.currentTimeMillis()
                        } ?: ProxyEndpoint(
                            id = 0,
                            proxyName = "custom-${kind.name.lowercase()}",
                            proxyMode = when (kind) {
                                ProxyEditorKind.SOCKS5 -> 0
                                ProxyEditorKind.HTTP -> 1
                            },
                            proxyType = ProxyEndpoint.DEFAULT_PROXY_TYPE,
                            proxyAppName = null,
                            proxyIP = host,
                            proxyPort = p,
                            userName = user.ifBlank { null },
                            password = pass.ifBlank { null },
                            isSelected = true,
                            isCustom = true,
                            isUDP = false,
                            modifiedDataTime = System.currentTimeMillis(),
                            latency = 0,
                        )
                        when (kind) {
                            ProxyEditorKind.SOCKS5 -> appConfig.updateCustomSocks5Proxy(ep)
                            ProxyEditorKind.HTTP -> appConfig.updateCustomHttpProxy(ep)
                        }
                        withContext(Dispatchers.Main) {
                            existing = ep
                            enabled = true
                            Toast.makeText(context, "Saved — proxy is active.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(if (enabled) "Save" else "Save and enable")
                }
                if (enabled) {
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val type = when (kind) {
                                ProxyEditorKind.SOCKS5 -> AppConfig.ProxyType.SOCKS5
                                ProxyEditorKind.HTTP -> AppConfig.ProxyType.HTTP
                            }
                            appConfig.removeProxy(type, AppConfig.ProxyProvider.CUSTOM)
                            withContext(Dispatchers.Main) {
                                enabled = false
                                Toast.makeText(context, "Disabled.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("Disable")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
