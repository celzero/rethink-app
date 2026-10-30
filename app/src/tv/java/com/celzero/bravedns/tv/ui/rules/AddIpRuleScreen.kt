/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text as M3Text

/**
 * Add a custom IP rule (with optional :port) for the current scope.
 * Mirrors the phone's add-IP dialog: universal scope offers
 * Block / Bypass universal, per-app scope offers Block / Trust.
 * getIpNetPort can throw NetworkOnMainThread, so validation and
 * insert run on Dispatchers.IO.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddIpRuleScreen(navController: NavController, uid: Int = Constants.UID_EVERYBODY) {
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    val universal = uid == Constants.UID_EVERYBODY

    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    fun submit(status: IpRulesManager.IpRuleStatus) {
        if (saving) return
        saving = true
        error = null
        val ipString = Utilities.removeLeadingAndTrailingDots(input)
        scope.launch(Dispatchers.IO) {
            val ipPair = IpRulesManager.getIpNetPort(ipString)
            val ip: IPAddress? = ipPair.first
            val port: Int = ipPair.second

            suspend fun fail(msg: String) {
                withContext(Dispatchers.Main) {
                    error = msg
                    saving = false
                }
            }

            if (ip == null || ipString.isEmpty()) {
                fail("Invalid IP address, subnet or port.")
                return@launch
            }
            // reject non-CIDR-able input such as "1.1.1.1-55"; the ip trie
            // only accepts CIDR notation and would reject the rule
            if (!IpRulesManager.isCidrEnforceable(ip)) {
                fail(
                    "Not a valid CIDR. IP ranges (e.g. 1.1.1.1-55) and non-aligned wildcards " +
                        "are not supported. Use CIDR notation like 1.1.1.0/24."
                )
                return@launch
            }
            IpRulesManager.addIpRule(uid, ip, port, status, proxyId = "", proxyCC = "")
            withContext(Dispatchers.Main) {
                saving = false
                navController.popBackStack()
            }
        }
    }

    TvScreenScaffold(
        title = "Add IP rule",
        subtitle = if (universal) {
            "Block or bypass an IP for the universal scope."
        } else {
            "Block or trust an IP for the selected app."
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            RuleFormField(
                label = "IP address",
                value = input,
                onValueChange = {
                    input = it
                    if (error != null) error = null
                },
                hint = "Enter an IP, ex: 1.1.1.1 or 10.0.0.0/8:53",
                focusRequester = fieldFocus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            error?.let { msg ->
                M3Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(TvSpacing.RowGap))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
                RulePrimaryButton(
                    label = if (saving) "Adding…" else "Block",
                    enabled = !saving && input.isNotBlank(),
                    onClick = { submit(IpRulesManager.IpRuleStatus.BLOCK) },
                )
                RulePrimaryButton(
                    label = if (universal) "Bypass universal" else "Trust",
                    enabled = !saving && input.isNotBlank(),
                    onClick = {
                        submit(
                            if (universal) {
                                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
                            } else {
                                IpRulesManager.IpRuleStatus.TRUST
                            }
                        )
                    },
                )
                RuleSecondaryButton(
                    label = "Cancel",
                    onClick = { navController.popBackStack() },
                )
            }
        }
    }
}
