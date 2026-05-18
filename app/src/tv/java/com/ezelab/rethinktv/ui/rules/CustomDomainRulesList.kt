/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.rules

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainDAO
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class DomainRuleRow(
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
fun CustomDomainRulesList() {
    val dao = koinInject<CustomDomainDAO>()
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableStateOf(0) }

    val rows by produceState(initialValue = emptyList<DomainRuleRow>(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            dao.getDomainsByUID(Constants.UID_EVERYBODY)
                .map { it.toRow() }
        }
    }

    if (rows.isEmpty()) {
        EmptyRulesState("No universal custom domain rules yet.")
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            DomainRuleCard(
                row = row,
                onSelect = {
                    scope.launch(Dispatchers.IO) {
                        applyDomainStatus(row, nextDomainStatus(row.status))
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DomainRuleCard(row: DomainRuleRow, onSelect: () -> Unit) {
    val next = nextDomainStatus(row.status)

    Surface(
        onClick = onSelect,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.domain,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DomainStatusPill(row.status)
                    RuleTag(label = domainTypeLabel(row))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Next: ${statusLabel(next)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    val (label, color) = when (status) {
        DomainRulesManager.Status.NONE -> "None" to Color(0xFF607D8B)
        DomainRulesManager.Status.BLOCK -> "Block" to Color(0xFFC62828)
        DomainRulesManager.Status.TRUST -> "Trust" to Color(0xFF2E7D32)
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
internal fun RuleTag(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50))
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

private fun DomainRuleRow.toEntity(): CustomDomain = CustomDomain(
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
