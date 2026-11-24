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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.service.IpRulesManager
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

internal data class IpRuleRow(
    val uid: Int,
    val ipAddress: String,
    val port: Int,
    val protocol: String,
    val isActive: Boolean,
    val status: IpRulesManager.IpRuleStatus,
    val ruleType: Int,
    val wildcard: Boolean,
    val proxyId: String,
    val proxyCC: String,
    val modifiedDateTime: Long,
) {
    val key: String = "$uid/$ipAddress/$port/$protocol"
}

@Composable
internal fun CustomIpRulesList(
    uid: Int,
    query: String,
    appName: String?,
    onDeleteRequest: (IpRuleRow) -> Unit,
) {
    val dao = koinInject<CustomIpDao>()
    val scope = rememberCoroutineScope()

    val rows by produceState(initialValue = emptyList<IpRuleRow>(), uid, dao) {
        dao.observeRulesByUid(uid)
            .map { list -> list.map { it.toRow() } }
            .flowOn(Dispatchers.IO)
            .collect { value = it }
    }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { displayAddress(it).contains(query.trim(), ignoreCase = true) }
    }

    val universal = uid == Constants.UID_EVERYBODY
    val scopeLabel = if (universal) "Universal" else (appName ?: "Unknown app")

    if (filtered.isEmpty()) {
        EmptyRulesState(
            when {
                rows.isEmpty() ->
                    if (universal) "No universal custom IP rules yet."
                    else "No custom IP rules for $scopeLabel yet."
                else -> "No IP rules match \"$query\"."
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
            IpRuleCard(
                row = row,
                next = nextIpStatus(row.status, universal),
                onSelect = {
                    scope.launch(Dispatchers.IO) {
                        applyIpStatus(row, nextIpStatus(row.status, universal))
                    }
                },
                onDelete = { onDeleteRequest(row) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IpRuleCard(
    row: IpRuleRow,
    next: IpRulesManager.IpRuleStatus,
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
                    text = displayAddress(row),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IpStatusPill(row.status)
                    RuleTag(label = ipFamilyLabel(row))
                    if (row.wildcard) {
                        RuleTag(label = "Wildcard")
                    }
                    if (row.protocol.isNotBlank()) {
                        RuleTag(label = row.protocol.uppercase())
                    }
                }
            }
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Text(
                text = "Next: ${ipStatusLabel(next)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(TvSpacing.GroupGap))
            DeleteRuleButton(onDelete = onDelete)
        }
    }
}

@Composable
private fun IpStatusPill(status: IpRulesManager.IpRuleStatus) {
    // Status accent expressed through theme roles: success = good,
    // error = bad, secondary = bypass, on-surface-variant = neutral.
    val (label, container, content) = when (status) {
        IpRulesManager.IpRuleStatus.NONE -> Triple(
            "None",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surface,
        )
        IpRulesManager.IpRuleStatus.BLOCK -> Triple(
            "Block",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
        )
        IpRulesManager.IpRuleStatus.TRUST -> Triple(
            "Trust",
            TvColors.Success,
            TvColors.OnSuccess,
        )
        IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> Triple(
            "Bypass",
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.onSecondary,
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

private fun CustomIp.toRow() = IpRuleRow(
    uid = uid,
    ipAddress = ipAddress,
    port = port,
    protocol = protocol,
    isActive = isActive,
    status = IpRulesManager.IpRuleStatus.getStatus(status),
    ruleType = ruleType,
    wildcard = wildcard,
    proxyId = proxyId,
    proxyCC = proxyCC,
    modifiedDateTime = modifiedDateTime,
)

internal fun IpRuleRow.toEntity(): CustomIp = CustomIp().apply {
    uid = this@toEntity.uid
    ipAddress = this@toEntity.ipAddress
    port = this@toEntity.port
    protocol = this@toEntity.protocol
    isActive = this@toEntity.isActive
    status = this@toEntity.status.id
    ruleType = this@toEntity.ruleType
    wildcard = this@toEntity.wildcard
    proxyId = this@toEntity.proxyId
    proxyCC = this@toEntity.proxyCC
    modifiedDateTime = this@toEntity.modifiedDateTime
}

private suspend fun applyIpStatus(row: IpRuleRow, next: IpRulesManager.IpRuleStatus) {
    val customIp = row.toEntity()
    when (next) {
        IpRulesManager.IpRuleStatus.NONE -> IpRulesManager.updateNoRule(customIp)
        IpRulesManager.IpRuleStatus.BLOCK -> IpRulesManager.updateBlock(customIp)
        IpRulesManager.IpRuleStatus.TRUST -> IpRulesManager.updateTrust(customIp)
        IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> IpRulesManager.updateBypass(customIp)
    }
}

// Bypass universal is a universal-scope-only status; per-app rules
// cycle Block -> Trust, universal rules cycle Block -> Bypass.
private fun nextIpStatus(
    status: IpRulesManager.IpRuleStatus,
    universal: Boolean,
): IpRulesManager.IpRuleStatus = when {
    universal -> when (status) {
        IpRulesManager.IpRuleStatus.NONE -> IpRulesManager.IpRuleStatus.BLOCK
        IpRulesManager.IpRuleStatus.BLOCK -> IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
        IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL,
        IpRulesManager.IpRuleStatus.TRUST -> IpRulesManager.IpRuleStatus.NONE
    }
    else -> when (status) {
        IpRulesManager.IpRuleStatus.NONE -> IpRulesManager.IpRuleStatus.BLOCK
        IpRulesManager.IpRuleStatus.BLOCK -> IpRulesManager.IpRuleStatus.TRUST
        IpRulesManager.IpRuleStatus.TRUST,
        IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> IpRulesManager.IpRuleStatus.NONE
    }
}

private fun ipStatusLabel(status: IpRulesManager.IpRuleStatus): String = when (status) {
    IpRulesManager.IpRuleStatus.NONE -> "None"
    IpRulesManager.IpRuleStatus.BLOCK -> "Block"
    IpRulesManager.IpRuleStatus.TRUST -> "Trust"
    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> "Bypass"
}

private fun displayAddress(row: IpRuleRow): String = if (row.port > 0) {
    "${row.ipAddress}:${row.port}"
} else {
    row.ipAddress
}

private fun ipFamilyLabel(row: IpRuleRow): String = when {
    row.ruleType == IpRulesManager.IPRuleType.IPV6.id || row.ipAddress.contains(':') -> "IPv6"
    else -> "IPv4"
}
