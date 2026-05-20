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

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.wireguard.Config
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import androidx.compose.material3.Text as M3Text

/**
 * WireGuard tunnel import screen.
 *
 * Phone build supports three sources: QR code, file picker, paste.
 * On TV we expose paste + SAF file picker (no camera). Both paths
 * funnel into [Config.parse] and then
 * [WireguardManager.addConfig].
 *
 * Paste flow:
 *  * Edit-field where the user pastes a `wg0.conf`-style multiline
 *    blob (Interface + Peer sections). Compose's
 *    `OutlinedTextField` handles this — Bluetooth keyboards on TV
 *    can paste with the standard shortcut, and remote-clipboard
 *    apps (e.g. Google TV's app launcher) inject text the same way.
 *  * Optional name field — defaults to upstream's `${WG}{id}`.
 *
 * SAF flow:
 *  * Tap "Open .conf file" — fires an `ACTION_OPEN_DOCUMENT` intent.
 *    The TV-side Files app fulfills it and returns a content URI we
 *    open via `ContentResolver`.
 *
 * Save calls `addConfig(parsed, name)`. On success we pop back to
 * Proxy; on parse / save error we render the message inline.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WgImportScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tunnelName by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            error = null
            try {
                val text = readUri(context, uri)
                withContext(Dispatchers.Main) { configText = text }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Could not read file" }
            }
        }
    }

    TvScreenScaffold(
        title = "Add WireGuard tunnel",
        subtitle = "Paste a tunnel .conf or pick a file from storage.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Source row — paste-from-clipboard + open-file shortcuts.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    label = "Paste from clipboard",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val pasted = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (pasted.isNotBlank()) {
                            configText = pasted
                            error = null
                        } else {
                            error = "Clipboard is empty or not text."
                        }
                    },
                )
                SecondaryButton(
                    label = "Open .conf file",
                    onClick = {
                        // Accept */* because the WireGuard MIME isn't
                        // standardised — letting the user pick any file
                        // and parsing the contents is more reliable on
                        // TV than filtering by extension.
                        runCatching {
                            openDocLauncher.launch(arrayOf("*/*"))
                        }.onFailure {
                            error = "No file picker available on this device."
                        }
                    },
                )
            }

            // Name + body fields.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                M3Text(
                    text = "Tunnel name (optional)",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                OutlinedTextField(
                    value = tunnelName,
                    onValueChange = { tunnelName = it },
                    placeholder = { M3Text("e.g. mullvad-us-nyc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                M3Text(
                    text = "Tunnel configuration",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    placeholder = {
                        M3Text(
                            "[Interface]\nPrivateKey = …\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = …\nEndpoint = host:port\nAllowedIPs = 0.0.0.0/0",
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 360.dp),
                )
            }

            error?.let { msg ->
                M3Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    label = if (saving) "Saving…" else "Import tunnel",
                    enabled = !saving && configText.isNotBlank(),
                    onClick = {
                        saving = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (!WireguardManager.isLoaded()) {
                                    WireguardManager.load(false)
                                }
                                val parsed = Config.parse(BufferedReader(StringReader(configText)))
                                WireguardManager.addConfig(parsed, name = tunnelName.trim())
                                withContext(Dispatchers.Main) {
                                    navController?.popBackStack()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    error = e.message ?: "Could not import tunnel"
                                }
                            } finally {
                                withContext(Dispatchers.Main) { saving = false }
                            }
                        }
                    },
                )
                SecondaryButton(
                    label = "Cancel",
                    onClick = { navController?.popBackStack() },
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun readUri(context: Context, uri: Uri): String {
    context.contentResolver.openInputStream(uri).use { stream ->
        return BufferedReader(InputStreamReader(stream)).readText()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
