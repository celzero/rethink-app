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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.tv.ui.apps.AppIcon
import com.celzero.bravedns.tv.ui.apps.AppRow
import com.celzero.bravedns.tv.ui.apps.toRow
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.common.rememberAsImmutableState
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import com.celzero.bravedns.tv.ui.common.Surface

/**
 * App picker backing the Rules screen's per-app scope. Lists installed
 * apps only (LiveData-backed, so uninstalls drop out); rules for
 * already-uninstalled uids stay reachable on the phone flavor only.
 * Selecting an app pushes its uid onto the caller's savedStateHandle
 * under [PICKED_UID_KEY] and pops back.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppPickerScreen(navController: NavController) {
    val rows by rememberAsImmutableState(
        liveData = FirewallManager.getApplistObserver(),
        initial = emptyList<AppRow>(),
    ) { apps ->
        apps.orEmpty()
            .map { it.toRow() }
            .sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
    }

    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter {
            it.appName.contains(query.trim(), ignoreCase = true) ||
                it.packageName.contains(query.trim(), ignoreCase = true)
        }
    }

    fun pick(uid: Int) {
        navController.previousBackStackEntry?.savedStateHandle?.set(PICKED_UID_KEY, uid)
        navController.popBackStack()
    }

    TvScreenScaffold(
        title = "Pick app",
        subtitle = "Scope the Rules screen to a single app's custom domain and IP rules.",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            RuleFormField(
                label = "Search",
                value = query,
                onValueChange = { query = it },
                hint = "Search app name or package…",
            )
            if (filtered.isEmpty()) {
                EmptyRulesState(
                    if (rows.isEmpty()) "No apps yet — the first refresh after install can take a few seconds."
                    else "No apps match \"$query\"."
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 24.dp),
                ) {
                    items(filtered, key = { "${it.uid}/${it.packageName}" }) { row ->
                        AppPickerCard(row = row, onClick = { pick(row.uid) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppPickerCard(row: AppRow, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = row.packageName, size = 44)
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.appName.ifBlank { row.packageName },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
