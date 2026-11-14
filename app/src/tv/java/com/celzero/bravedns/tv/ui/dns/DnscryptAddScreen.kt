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
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptEndpointDAO
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import androidx.compose.material3.Text as M3Text

/**
 * Custom DNSCrypt add screen (`dns/dnscrypt/add`).
 *
 * Mirrors the phone's `DnsCryptListFragment` dialog: name + stamp +
 * description with a Server | Relay segmented choice. A server is
 * inserted into `DNSCryptEndpoint` and activated via
 * [AppConfig.handleDnscryptChanges]; a relay only lands in
 * `DNSCryptRelayEndpoint` (relay selection stays out of scope) and
 * is never activated. Activation re-reads the inserted row first —
 * the auto-generated id is not written back onto the entity.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DnscryptAddScreen(navController: NavController? = null) {
    val appConfig = koinInject<AppConfig>()
    val dnscryptDao = koinInject<DnsCryptEndpointDAO>()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("DNSCrypt") }
    var stamp by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var relayMode by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    TvScreenScaffold(
        title = "Add custom DNSCrypt",
        subtitle = "Paste a DNSCrypt server or relay stamp.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            FormField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                hint = "Short label shown in the DNSCrypt picker",
            )
            FormField(
                label = "Stamp",
                value = stamp,
                onValueChange = { stamp = it },
                hint = "sdns://…",
            )
            FormField(
                label = "Description (optional)",
                value = desc,
                onValueChange = { desc = it },
                hint = "What this server or relay is for",
            )
            SegmentedChoice(
                label = "Type",
                options = listOf("Server", "Relay"),
                selectedIndex = if (relayMode) 1 else 0,
                onSelect = { index ->
                    val nextRelay = index == 1
                    if (nextRelay != relayMode) {
                        relayMode = nextRelay
                        name = defaultDnsCryptName(name, nextRelay)
                    }
                },
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
                    label = if (saving) "Saving…" else if (relayMode) "Save" else "Save and activate",
                    enabled = !saving && stamp.isNotBlank(),
                    onClick = {
                        saving = true
                        saveError = null
                        val asRelay = relayMode
                        val epName = name
                        val epStamp = stamp
                        val epDesc = desc
                        scope.launch(Dispatchers.IO) {
                            var saved = false
                            try {
                                persistDnsCrypt(appConfig, dnscryptDao, asRelay, epName, epStamp, epDesc)
                                saved = true
                            } catch (e: Exception) {
                                saveError = e.message ?: "Save failed"
                            }
                            if (saved) {
                                withContext(Dispatchers.Main) { navController?.popBackStack() }
                            }
                            saving = false
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

// Keep the mode's default label unless the user typed their own name.
private fun defaultDnsCryptName(current: String, relay: Boolean): String {
    if (current.isNotBlank() && current != "DNSCrypt" && current != "DNSRelay") return current
    return if (relay) "DNSRelay" else "DNSCrypt"
}

private suspend fun persistDnsCrypt(
    appConfig: AppConfig,
    dnscryptDao: DnsCryptEndpointDAO,
    asRelay: Boolean,
    rawName: String,
    rawStamp: String,
    rawDesc: String,
) {
    val finalName = rawName.trim().ifBlank {
        if (asRelay) "DNSRelay" else "DNSCrypt"
    }
    val stamp = rawStamp.trim()
    val desc = rawDesc.trim()
    if (asRelay) {
        val relay = DnsCryptRelayEndpoint(
            id = 0,
            dnsCryptRelayName = finalName,
            dnsCryptRelayURL = stamp,
            dnsCryptRelayExplanation = desc,
            isSelected = false,
            isCustom = true,
            modifiedDataTime = 0,
            latency = 0,
        )
        appConfig.insertDnscryptRelayEndpoint(relay)
        return
    }
    val server = DnsCryptEndpoint(
        id = 0,
        dnsCryptName = finalName,
        dnsCryptURL = stamp,
        dnsCryptExplanation = desc,
        isSelected = true,
        isCustom = true,
        modifiedDataTime = 0,
        latency = 0,
    )
    val inserted = dnscryptDao.getEndpointById(appConfig.insertDnscryptEndpoint(server).toInt())
    if (inserted != null) {
        inserted.isSelected = true
        appConfig.handleDnscryptChanges(inserted)
    }
}
