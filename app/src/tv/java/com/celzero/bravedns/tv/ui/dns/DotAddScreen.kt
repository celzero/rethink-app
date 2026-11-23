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
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.DoTEndpointDAO
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.tv.ui.common.SettingToggleRow
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import androidx.compose.material3.Text as M3Text

/**
 * Custom DoT endpoint add screen (`dns/dot/add`).
 *
 * Mirrors the phone's `DoTListFragment` dialog: name + URL + a
 * "skip TLS validation" toggle. Unlike the phone (insert only, no
 * validation), activation is gated on a hostname-shape check.
 * Saving checks for an existing endpoint by identity (name, URL,
 * TLS) before inserting with `isCustom = true`, re-reads the row by
 * its insertion id and activates it via [AppConfig.handleDoTChanges].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DotAddScreen(navController: NavController? = null) {
    val appConfig = koinInject<AppConfig>()
    val dotDao = koinInject<DoTEndpointDAO>()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("DoT") }
    var url by remember { mutableStateOf("") }
    var skipTlsValidation by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    TvScreenScaffold(
        title = "Add custom DoT",
        subtitle = "Bring your own DNS-over-TLS resolver.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            FormField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                hint = "Short label shown in the DoT picker",
            )
            FormField(
                label = "URL",
                value = url,
                onValueChange = { url = it },
                hint = "tls://dns.example.com",
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
                        saving = true
                        saveError = null
                        val epName = name.trim()
                        val trimmedUrl = url.trim()
                        val secure = !skipTlsValidation
                        scope.launch(Dispatchers.IO) {
                            try {
                                // duplicates are not stored; activation is gated on a valid hostname
                                val hostOk = DomainRulesManager.extractHost(trimmedUrl)
                                    ?.let { DomainRulesManager.isValidDomain(it) } == true
                                val finalName = epName.ifBlank {
                                    "DoT ${appConfig.getDoTCount() + 1}"
                                }
                                val endpoint = DoTEndpoint(
                                    id = 0,
                                    name = finalName,
                                    url = trimmedUrl,
                                    desc = "",
                                    isSelected = hostOk,
                                    isCustom = true,
                                    isSecure = secure,
                                    modifiedDataTime = 0,
                                    latency = 0,
                                )
                                // identity check; IGNORE-on-insert never
                                // conflicts (no unique index beyond the pk)
                                val exists = dotDao.getAll().any {
                                    it.name == finalName && it.url == trimmedUrl &&
                                        it.isSecure == secure
                                }
                                val inserted = if (exists) {
                                    null
                                } else {
                                    appConfig.insertDoTEndpoint(endpoint).let { id ->
                                        if (id > 0) dotDao.getById(id.toInt()) else null
                                    }
                                }
                                when {
                                    !hostOk -> withContext(Dispatchers.Main) {
                                        saveError = "Invalid DoT host, expected a hostname."
                                    }
                                    inserted == null -> withContext(Dispatchers.Main) {
                                        saveError = "Endpoint already exists"
                                    }
                                    else -> {
                                        inserted.isSelected = true
                                        appConfig.handleDoTChanges(inserted)
                                        withContext(Dispatchers.Main) {
                                            navController?.popBackStack()
                                        }
                                    }
                                }
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
