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
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class IpRuleRow(
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
fun CustomIpRulesList() {
    val dao = koinInject<CustomIpDao>()
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableStateOf(0) }

    val rows by produceState(initialValue = emptyList<IpRuleRow>(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            dao.getRulesByUid(Constants.UID_EVERYBODY)
                .asSequence()
                .filter { it.isActive }
                .sortedByDescending { it.modifiedDateTime }
                .map { it.toRow() }
                .toList()
        }
    }

    if (rows.isEmpty()) {
        EmptyRulesState("No universal custom IP rules yet.")
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            IpRuleCard(
                row = row,
                onSelect = {
                    scope.launch(Dispatchers.IO) {
                        applyIpStatus(row, nextIpStatus(row.status))
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IpRuleCard(row: IpRuleRow, onSelect: () -> Unit) {
    val next = nextIpStatus(row.status)

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
                    text = displayAddress(row),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Next: ${ipStatusLabel(next)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IpStatusPill(status: IpRulesManager.IpRuleStatus) {
    val (label, color) = when (status) {
        IpRulesManager.IpRuleStatus.NONE -> "None" to Color(0xFF607D8B)
        IpRulesManager.IpRuleStatus.BLOCK -> "Block" to Color(0xFFC62828)
        IpRulesManager.IpRuleStatus.TRUST -> "Trust" to Color(0xFF2E7D32)
        IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> "Bypass" to Color(0xFF6E51BD)
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

private fun IpRuleRow.toEntity(): CustomIp = CustomIp().apply {
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

private fun nextIpStatus(status: IpRulesManager.IpRuleStatus): IpRulesManager.IpRuleStatus = when (status) {
    IpRulesManager.IpRuleStatus.NONE -> IpRulesManager.IpRuleStatus.BLOCK
    IpRulesManager.IpRuleStatus.BLOCK -> IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL,
    IpRulesManager.IpRuleStatus.TRUST -> IpRulesManager.IpRuleStatus.NONE
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
