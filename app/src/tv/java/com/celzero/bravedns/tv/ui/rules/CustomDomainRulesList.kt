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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainDAO
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal data class DomainRuleRow(
    val domain: String,
    val uid: Int,
    val ips: String,
    val status: DomainRulesManager.Status,
    val type: DomainRulesManager.DomainType,
    val proxyId: String,
    val proxyCC: String,
    val modifiedTs: Long,
    val deletedTs: Long,
    val version: Long,
) {
    val key: String = "$uid/$domain"
}

@Composable
internal fun CustomDomainRulesList(
    uid: Int,
    query: String,
    appName: String?,
    onDeleteRequest: (DomainRuleRow) -> Unit,
) {
    val dao = koinInject<CustomDomainDAO>()
    val scope = rememberCoroutineScope()

    val rows by produceState(initialValue = emptyList<DomainRuleRow>(), uid, dao) {
        dao.observeDomainsByUID(uid)
            .map { list -> list.map { it.toRow() } }
            .flowOn(Dispatchers.IO)
            .collect { value = it }
    }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { it.domain.contains(query.trim(), ignoreCase = true) }
    }

    val scopeLabel = if (uid == Constants.UID_EVERYBODY) "Universal" else (appName ?: "Unknown app")

    if (filtered.isEmpty()) {
        EmptyRulesState(
            when {
                rows.isEmpty() ->
                    if (uid == Constants.UID_EVERYBODY) "No universal custom domain rules yet."
                    else "No custom domain rules for $scopeLabel yet."
                else -> "No domain rules match \"$query\"."
            }
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp),
    ) {
        items(filtered, key = { it.key }) { row ->
            DomainRuleCard(
                row = row,
                next = nextDomainStatus(row.status),
                onSelect = {
                    scope.launch(Dispatchers.IO) {
                        applyDomainStatus(row, nextDomainStatus(row.status))
                    }
                },
                onDelete = { onDeleteRequest(row) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DomainRuleCard(
    row: DomainRuleRow,
    next: DomainRulesManager.Status,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.domain,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DomainStatusPill(row.status)
                    RuleTag(label = domainTypeLabel(row))
                }
            }
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Text(
                text = "Next: ${statusLabel(next)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(TvSpacing.GroupGap))
            DeleteRuleButton(onDelete = onDelete)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DeleteRuleButton(onDelete: () -> Unit) {
    Surface(
        onClick = onDelete,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            focusedContainerColor = MaterialTheme.colorScheme.error,
            focusedContentColor = MaterialTheme.colorScheme.onError,
            pressedContainerColor = MaterialTheme.colorScheme.error,
            pressedContentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(
            text = "Delete",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun EmptyRulesState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DomainStatusPill(status: DomainRulesManager.Status) {
    // Status accent expressed through theme roles: success = good,
    // error = bad, on-surface-variant = neutral.
    val (label, container, content) = when (status) {
        DomainRulesManager.Status.NONE -> Triple(
            "None",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surface,
        )
        DomainRulesManager.Status.BLOCK -> Triple(
            "Block",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
        )
        DomainRulesManager.Status.TRUST -> Triple(
            "Trust",
            TvColors.Success,
            TvColors.OnSuccess,
        )
    }
    Box(
        modifier = Modifier
            .background(container, TvShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
internal fun RuleTag(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiary, TvShapes.Pill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun CustomDomain.toRow() = DomainRuleRow(
    domain = domain,
    uid = uid,
    ips = ips,
    status = DomainRulesManager.Status.getStatus(status),
    type = DomainRulesManager.DomainType.getType(type),
    proxyId = proxyId,
    proxyCC = proxyCC,
    modifiedTs = modifiedTs,
    deletedTs = deletedTs,
    version = version,
)

internal fun DomainRuleRow.toEntity(): CustomDomain = CustomDomain(
    domain,
    uid,
    ips,
    type.id,
    status.id,
    proxyId,
    proxyCC,
    modifiedTs,
    deletedTs,
    version,
)

private suspend fun applyDomainStatus(row: DomainRuleRow, next: DomainRulesManager.Status) {
    val customDomain = row.toEntity()
    when (next) {
        DomainRulesManager.Status.NONE -> DomainRulesManager.noRule(customDomain)
        DomainRulesManager.Status.BLOCK -> DomainRulesManager.block(customDomain)
        DomainRulesManager.Status.TRUST -> DomainRulesManager.trust(customDomain)
    }
}

private fun nextDomainStatus(status: DomainRulesManager.Status): DomainRulesManager.Status = when (status) {
    DomainRulesManager.Status.NONE -> DomainRulesManager.Status.BLOCK
    DomainRulesManager.Status.BLOCK -> DomainRulesManager.Status.TRUST
    DomainRulesManager.Status.TRUST -> DomainRulesManager.Status.NONE
}

private fun statusLabel(status: DomainRulesManager.Status): String = when (status) {
    DomainRulesManager.Status.NONE -> "None"
    DomainRulesManager.Status.BLOCK -> "Block"
    DomainRulesManager.Status.TRUST -> "Trust"
}

private fun domainTypeLabel(row: DomainRuleRow): String = when (row.type) {
    DomainRulesManager.DomainType.DOMAIN -> "Domain"
    DomainRulesManager.DomainType.WILDCARD -> {
        val value = row.domain.removePrefix("*.").removePrefix(".")
        if (value.isNotBlank() && !value.contains('.')) "TLD" else "Wildcard"
    }
}
