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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.tv.ui.apps.AppRow
import com.celzero.bravedns.tv.ui.apps.toRow
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.TvButton
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.common.TvSelectableChip
import com.celzero.bravedns.tv.ui.common.TvTabRow
import com.celzero.bravedns.tv.ui.common.rememberAsImmutableState
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.tv.material3.Text

/** savedStateHandle key the app picker uses to return the picked uid. */
internal const val PICKED_UID_KEY = "rulesPickedUid"

private enum class RulesTab(val label: String) {
    DOMAINS("Domains"),
    IPS("IPs"),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RulesScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    var activeTab by rememberSaveable { mutableStateOf(RulesTab.DOMAINS) }
    var selectedUid by rememberSaveable { mutableStateOf(Constants.UID_EVERYBODY) }
    var domainQuery by rememberSaveable { mutableStateOf("") }
    var ipQuery by rememberSaveable { mutableStateOf("") }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingRuleDelete?>(null) }

    // App picker hands back the picked uid on the caller's savedStateHandle.
    LaunchedEffect(navController.currentBackStackEntry) {
        val handle = navController.currentBackStackEntry?.savedStateHandle ?: return@LaunchedEffect
        handle.getStateFlow(PICKED_UID_KEY, Constants.UID_EVERYBODY).collect { uid ->
            if (uid != Constants.UID_EVERYBODY) {
                selectedUid = uid
                handle[PICKED_UID_KEY] = Constants.UID_EVERYBODY
            }
        }
    }

    val appRows by rememberAsImmutableState(
        liveData = FirewallManager.getApplistObserver(),
        initial = emptyList<AppRow>(),
    ) { apps -> apps.orEmpty().map { it.toRow() } }
    val appName = if (selectedUid == Constants.UID_EVERYBODY) {
        null
    } else {
        appRows.firstOrNull { it.uid == selectedUid }?.appName ?: "Unknown app"
    }

    val addRoute = when (activeTab) {
        RulesTab.DOMAINS -> "rules/add/domain?uid=$selectedUid"
        RulesTab.IPS -> "rules/add/ip?uid=$selectedUid"
    }

    TvScreenScaffold(
        title = "Rules",
        subtitle = "Add, search, and manage universal or per-app custom domain and IP rules.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // trap D-pad focus away from the background while a
                // delete confirmation is up (dialog buttons stay focusable)
                .then(
                    if (confirmDeleteAll || pendingDelete != null) {
                        Modifier.focusProperties { canFocus = false }
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        ) {
            RulesTabs(active = activeTab, onSelect = { activeTab = it })
            ScopeRow(
                uid = selectedUid,
                appName = appName,
                onPickApp = { navController.navigate("rules/app-picker") },
                onChangeApp = { navController.navigate("rules/app-picker") },
                onClearApp = { selectedUid = Constants.UID_EVERYBODY },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
            ) {
                val query = if (activeTab == RulesTab.DOMAINS) domainQuery else ipQuery
                RulesSearchField(
                    query = query,
                    onQueryChange = { value ->
                        if (activeTab == RulesTab.DOMAINS) domainQuery = value else ipQuery = value
                    },
                    modifier = Modifier.weight(1f),
                )
                TvButton(
                    text = "Add rule",
                    onClick = { navController.navigate(addRoute) },
                )
                TvButton(
                    text = "Delete all",
                    destructive = true,
                    onClick = { confirmDeleteAll = true },
                )
            }

            SettingSectionHeader(
                when (activeTab) {
                    RulesTab.DOMAINS -> "Domain rules"
                    RulesTab.IPS -> "IP rules"
                },
            )
            Spacer(Modifier.height(4.dp))
            // key per scope: rows (and their captured callbacks) reset
            // when the uid changes, so stale-uid select/delete can't fire
            key(selectedUid) {
                when (activeTab) {
                    RulesTab.DOMAINS ->
                        CustomDomainRulesList(
                            uid = selectedUid,
                            query = domainQuery,
                            appName = appName,
                            onDeleteRequest = { pendingDelete = PendingRuleDelete.DomainRule(it) },
                        )
                    RulesTab.IPS ->
                        CustomIpRulesList(
                            uid = selectedUid,
                            query = ipQuery,
                            appName = appName,
                            onDeleteRequest = { pendingDelete = PendingRuleDelete.IpRule(it) },
                        )
                }
            }
        }

        // delete confirmations render in the scaffold's content Box so
        // they overlay the full content area instead of a list's bounds
        if (confirmDeleteAll) {
            val tabLabel = if (activeTab == RulesTab.DOMAINS) "domain" else "IP"
            val scopeLabel = appName ?: "Universal"
            RulesConfirmDialog(
                title = "Delete all $tabLabel rules?",
                message =
                    "This removes every custom $tabLabel rule for $scopeLabel. This cannot be undone.",
                confirmLabel = "Delete all",
                onConfirm = {
                    confirmDeleteAll = false
                    val uid = selectedUid
                    val tab = activeTab
                    scope.launch(Dispatchers.IO) {
                        when (tab) {
                            RulesTab.DOMAINS -> RulesScopeOps.deleteAllDomainRules(uid)
                            RulesTab.IPS -> RulesScopeOps.deleteAllIpRules(uid)
                        }
                    }
                },
                onDismiss = { confirmDeleteAll = false },
            )
        }

        pendingDelete?.let { pending ->
            val scopeLabel = appName ?: "Universal"
            RulesConfirmDialog(
                title = "Delete rule?",
                message = when (pending) {
                    is PendingRuleDelete.DomainRule ->
                        "Remove \"${pending.row.domain}\" from $scopeLabel custom domain rules?"
                    is PendingRuleDelete.IpRule ->
                        "Remove \"${pending.row.ipAddress}\" from $scopeLabel custom IP rules?"
                },
                confirmLabel = "Delete",
                onConfirm = {
                    pendingDelete = null
                    scope.launch(Dispatchers.IO) {
                        when (pending) {
                            is PendingRuleDelete.DomainRule ->
                                DomainRulesManager.deleteRules(listOf(pending.row.toEntity()))
                            is PendingRuleDelete.IpRule ->
                                IpRulesManager.deleteRules(listOf(pending.row.toEntity()))
                        }
                    }
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RulesTabs(active: RulesTab, onSelect: (RulesTab) -> Unit) {
    TvTabRow(
        tabs = RulesTab.entries.toList(),
        selected = active,
        label = { it.label },
        onSelect = onSelect,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ScopeRow(
    uid: Int,
    appName: String?,
    onPickApp: () -> Unit,
    onChangeApp: () -> Unit,
    onClearApp: () -> Unit,
) {
    Surface(
        shape = TvShapes.Small,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Scope",
                style = MaterialTheme.typography.labelLarge,
            )
            if (uid == Constants.UID_EVERYBODY) {
                ScopeTag(text = "Universal")
                TvSelectableChip(label = "Pick app…", selected = false, onClick = onPickApp)
            } else {
                ScopeTag(text = appName ?: "Unknown app")
                TvSelectableChip(label = "Change app", selected = false, onClick = onChangeApp)
                TvSelectableChip(label = "Clear", selected = false, onClick = onClearApp)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ScopeTag(text: String) {
    Surface(
        shape = TvShapes.Pill,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RulesSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                text = "Search rules…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier,
    )
}
