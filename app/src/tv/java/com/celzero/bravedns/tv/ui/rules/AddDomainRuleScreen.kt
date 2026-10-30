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
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text as M3Text

/**
 * Add a custom domain rule (block or trust) for the current scope —
 * universal (UID_EVERYBODY) or a picked app. Mirrors the phone's
 * add-domain dialog: one text field, Domain/Wildcard choice, inline
 * validation via [DomainRulesManager]. Validation and insert run on
 * Dispatchers.IO (extractHost/isValidDomain can be expensive and the
 * insert touches the Room-backed managers).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddDomainRuleScreen(navController: NavController, uid: Int = Constants.UID_EVERYBODY) {
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }

    var input by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(DomainRulesManager.DomainType.DOMAIN) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    // Mirror the phone dialog: input starting with * or . is a wildcard.
    fun onInputChanged(value: String) {
        input = value
        type = if (value.startsWith("*") || value.startsWith(".")) {
            DomainRulesManager.DomainType.WILDCARD
        } else {
            DomainRulesManager.DomainType.DOMAIN
        }
        if (error != null) error = null
    }

    fun submit(status: DomainRulesManager.Status) {
        if (saving) return
        saving = true
        error = null
        val raw = input
        val selectedType = type
        scope.launch(Dispatchers.IO) {
            val host = DomainRulesManager.extractHost(raw)

            suspend fun fail(msg: String) {
                withContext(Dispatchers.Main) {
                    error = msg
                    saving = false
                }
            }

            if (host.isNullOrEmpty()) {
                fail("Invalid URL or domain name")
                return@launch
            }
            if (selectedType == DomainRulesManager.DomainType.WILDCARD &&
                !DomainRulesManager.isWildCardEntry(host)
            ) {
                fail("Invalid wildcard URL or domain")
                return@launch
            }
            if (selectedType == DomainRulesManager.DomainType.DOMAIN &&
                !DomainRulesManager.isValidDomain(host)
            ) {
                fail("Invalid URL or domain name")
                return@launch
            }
            DomainRulesManager.addDomainRule(
                Utilities.removeLeadingAndTrailingDots(host),
                status,
                selectedType,
                uid = uid,
            )
            withContext(Dispatchers.Main) {
                saving = false
                navController.popBackStack()
            }
        }
    }

    TvScreenScaffold(
        title = "Add domain rule",
        subtitle = if (uid == Constants.UID_EVERYBODY) {
            "Block or trust a domain for the universal scope."
        } else {
            "Block or trust a domain for the selected app."
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            RuleFormField(
                label = "Domain",
                value = input,
                onValueChange = ::onInputChanged,
                hint = if (type == DomainRulesManager.DomainType.WILDCARD) {
                    "Enter Wildcard, ex: *.example.com"
                } else {
                    "Enter Domain, ex: example.com"
                },
                focusRequester = fieldFocus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            RuleSegmentedChoice(
                label = "Rule type",
                options = listOf("Domain", "Wildcard"),
                selectedIndex = if (type == DomainRulesManager.DomainType.DOMAIN) 0 else 1,
                onSelect = { index ->
                    type = if (index == 0) {
                        DomainRulesManager.DomainType.DOMAIN
                    } else {
                        DomainRulesManager.DomainType.WILDCARD
                    }
                },
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
                    onClick = { submit(DomainRulesManager.Status.BLOCK) },
                )
                RulePrimaryButton(
                    label = "Trust",
                    enabled = !saving && input.isNotBlank(),
                    onClick = { submit(DomainRulesManager.Status.TRUST) },
                )
                RuleSecondaryButton(
                    label = "Cancel",
                    onClick = { navController.popBackStack() },
                )
            }
        }
    }
}
