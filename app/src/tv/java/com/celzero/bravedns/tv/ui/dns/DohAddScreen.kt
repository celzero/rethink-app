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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.SettingToggleRow
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointDAO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.net.MalformedURLException
import java.net.URL
import androidx.compose.material3.Text as M3Text

/**
 * Custom DoH endpoint add screen (`dns/doh/add`).
 *
 * Mirrors the phone's `DohListFragment` dialog: name + URL + optional
 * IP + a "skip TLS validation" toggle. The URL must be a plausible
 * DoH server (https, host, path, no query/fragment). Saving inserts
 * with `isCustom = true`, re-reads the row (auto-generated id is not
 * written back onto the inserted entity) and activates it via
 * [AppConfig.handleDoHChanges].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DohAddScreen(navController: NavController? = null) {
    val appConfig = koinInject<AppConfig>()
    val dohDao = koinInject<DoHEndpointDAO>()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("DoH") }
    var url by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var skipTlsValidation by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    TvScreenScaffold(
        title = "Add custom DoH",
        subtitle = "Bring your own DNS-over-HTTPS resolver.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            FormField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                hint = "Short label shown in the DoH picker",
            )
            FormField(
                label = "URL",
                value = url,
                onValueChange = { url = it },
                hint = "https://dns.example.com/dns-query",
            )
            FormField(
                label = "IP (optional)",
                value = ip,
                onValueChange = { ip = it },
                hint = "IP or comma-separated IPs for the URL",
            )
            SettingToggleRow(
                title = "Do not validate server TLS certificates",
                description = null,
                checked = skipTlsValidation,
                onCheckedChange = { skipTlsValidation = it },
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
                    enabled = !saving && url.isNotBlank(),
                    onClick = {
                        val trimmedUrl = url.trim()
                        if (!isValidDohUrl(trimmedUrl)) {
                            saveError = "Invalid DoH URL — expected https with a host and path."
                        } else {
                            saving = true
                            saveError = null
                            val epName = name.trim()
                            val epIp = ip.trim()
                            val secure = !skipTlsValidation
                            scope.launch {
                                var saved = false
                                try {
                                    saved = withContext(Dispatchers.IO) {
                                        val finalName = epName.ifBlank {
                                            "DoH ${appConfig.getDohCount() + 1}"
                                        }
                                        val endpoint = DoHEndpoint(
                                            id = 0,
                                            dohName = finalName,
                                            dohURL = trimmedUrl,
                                            dohIp = epIp.takeIf { it.isNotBlank() },
                                            dohExplanation = "",
                                            isSelected = true,
                                            isCustom = true,
                                            isSecure = secure,
                                            modifiedDataTime = 0,
                                            latency = 0,
                                        )
                                        val inserted = appConfig.insertDohEndpoint(endpoint).let { id ->
                                            // IGNORE conflict (-1) or unknown id: fall back to URL+name lookup
                                            if (id > 0) dohDao.getById(id.toInt()) else null
                                        } ?: dohDao.getAll().firstOrNull {
                                            it.dohURL == trimmedUrl && it.dohName == finalName
                                        }
                                        if (inserted != null) {
                                            inserted.isSelected = true
                                            appConfig.handleDoHChanges(inserted)
                                        }
                                        true
                                    }
                                } catch (e: Exception) {
                                    saveError = e.message ?: "Save failed"
                                }
                                if (saved) {
                                    navController?.popBackStack()
                                }
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

// Same rule as the phone's DohListFragment.checkUrl: https with a
// domain, a path (at least "/"), and no query or fragment.
private fun isValidDohUrl(url: String): Boolean {
    return try {
        val parsed = URL(url)
        parsed.protocol == "https" &&
            parsed.host.isNotEmpty() &&
            parsed.path.isNotEmpty() &&
            parsed.query == null &&
            parsed.ref == null
    } catch (e: MalformedURLException) {
        false
    }
}
