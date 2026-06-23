/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.dns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ODoHEndpointRepository
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.material3.Text as M3Text

/**
 * Custom ODoH endpoint add screen.
 *
 * Reached from the DNS destination's ODoH tab via the "+ Add custom"
 * button. ODoH (Oblivious DoH) needs three URLs:
 *
 *  * **Proxy** — the ODoH proxy that forwards the query without
 *    seeing its plaintext content.
 *  * **Resolver** — the target resolver that decrypts and answers
 *    the query without seeing the client IP.
 *  * **Name** — short label for the picker list.
 *
 * Saving inserts a new [ODoHEndpoint] with `isCustom = true` and
 * selects it as the active resolver via
 * [AppConfig.handleODoHChanges] (matches the phone activity's flow:
 * insert → select).
 *
 * No DoH/DoT add UX is shipped from this screen — those endpoints
 * are covered by upstream's default list; custom-add for them is a
 * separate follow-up.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OdohAddScreen(navController: NavController? = null) {
    val appConfig = koinInject<AppConfig>()
    val repository = koinInject<ODoHEndpointRepository>()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var proxy by remember { mutableStateOf("") }
    var resolver by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    TvScreenScaffold(
        title = "Add custom ODoH",
        subtitle = "Bring your own Oblivious DoH proxy + resolver.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                hint = "Short label shown in the ODoH picker",
            )
            FormField(
                label = "Proxy URL",
                value = proxy,
                onValueChange = { proxy = it },
                hint = "e.g. https://odoh.cloudflare-dns.com/proxy",
            )
            FormField(
                label = "Resolver URL",
                value = resolver,
                onValueChange = { resolver = it },
                hint = "e.g. https://odoh.crypto.sx/dns-query",
            )

            saveError?.let { msg ->
                M3Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    label = if (saving) "Saving…" else "Save and activate",
                    enabled = !saving && name.isNotBlank() && proxy.isNotBlank() && resolver.isNotBlank(),
                    onClick = {
                        saving = true
                        saveError = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                val endpoint = ODoHEndpoint(
                                    id = 0,
                                    name = name.trim(),
                                    proxy = proxy.trim(),
                                    resolver = resolver.trim(),
                                    proxyIps = "",
                                    desc = null,
                                    isSelected = true,
                                    isCustom = true,
                                    modifiedDataTime = INIT_TIME_MS,
                                    latency = 0,
                                )
                                repository.insertAsync(endpoint)
                                appConfig.handleODoHChanges(endpoint)
                                navController?.popBackStack()
                            } catch (e: Exception) {
                                saveError = e.message ?: "Save failed"
                            } finally {
                                saving = false
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

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        M3Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { M3Text(hint, style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
        Box(modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
