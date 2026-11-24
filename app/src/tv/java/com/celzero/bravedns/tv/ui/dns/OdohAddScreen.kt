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
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ODoHEndpointDAO
import com.celzero.bravedns.database.ODoHEndpointRepository
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
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
 * Saving inserts a new [ODoHEndpoint] with `isCustom = true`, then
 * re-reads it from the database (the auto-generated id is not written
 * back onto the inserted entity) and activates it via
 * [AppConfig.handleODoHChanges] — matches the phone activity's flow.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OdohAddScreen(navController: NavController? = null) {
    val appConfig = koinInject<AppConfig>()
    val repository = koinInject<ODoHEndpointRepository>()
    val odohDao = koinInject<ODoHEndpointDAO>()
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
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
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

            Spacer(Modifier.height(TvSpacing.RowGap))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
                PrimaryButton(
                    label = if (saving) "Saving…" else "Save and activate",
                    enabled = !saving && name.isNotBlank() && proxy.isNotBlank() && resolver.isNotBlank(),
                    onClick = {
                        saving = true
                        saveError = null
                        val epName = name.trim()
                        val epProxy = proxy.trim()
                        val epResolver = resolver.trim()
                        scope.launch(Dispatchers.IO) {
                            try {
                                val endpoint = ODoHEndpoint(
                                    id = 0,
                                    name = epName,
                                    proxy = epProxy,
                                    resolver = epResolver,
                                    proxyIps = "",
                                    desc = null,
                                    isSelected = true,
                                    isCustom = true,
                                    modifiedDataTime = INIT_TIME_MS,
                                    latency = 0,
                                )
                                repository.insertAsync(endpoint)
                                val inserted = odohDao.getAllAsList().firstOrNull {
                                    it.name == epName && it.proxy == epProxy &&
                                        it.resolver == epResolver
                                }
                                if (inserted != null) {
                                    inserted.isSelected = true
                                    appConfig.handleODoHChanges(inserted)
                                }
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
