/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.compose.rpn

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkTopBar
import com.celzero.bravedns.util.Utilities
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpnWinProxyDetailsScreen(
    countryCode: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val title = stringResource(R.string.rpn_proxy_details_title)
    val noProxyTitle = stringResource(R.string.rpn_no_proxy_found_title)
    val noProxyDesc = stringResource(R.string.rpn_no_proxy_found_desc)
    val selectAppsLabel = stringResource(R.string.rpn_select_apps_for_proxy)
    val appsInfoToast = stringResource(R.string.rpn_proxy_apps_info_toast)
    var appsCount by remember { mutableStateOf("-") }
    var domainsCount by remember { mutableStateOf("-") }
    var ipsCount by remember { mutableStateOf("-") }
    var proxyError by remember { mutableStateOf("") }
    var proxyName by remember { mutableStateOf("") }
    var proxyWho by remember { mutableStateOf("") }
    var proxyLatencyMs by remember { mutableStateOf<Int?>(null) }
    var proxyLastConnectedMs by remember { mutableStateOf<Long?>(null) }
    var isProxyActive by remember { mutableStateOf(false) }
    var showNoProxyFoundDialog by remember { mutableStateOf(false) }

    LaunchedEffect(countryCode) {
        if (countryCode.isEmpty()) {
            Napier.w(tag = TAG, message = "empty country code, showing dialog")
            showNoProxyFoundDialog = true
            return@LaunchedEffect
        }

        val loaded =
            withContext(Dispatchers.IO) {
            val appsByCountry = ProxyManager.getAppsCountForProxy(countryCode)
            val appsByWin = ProxyManager.getAppsCountForProxy(ProxyManager.ID_RPN_WIN)
            val apps = if (appsByCountry > 0) appsByCountry else appsByWin
            val ipCount = IpRulesManager.getRulesCountByCC(countryCode)
            val domainCount = DomainRulesManager.getRulesCountByCC(countryCode)
            val details = RpnProxyManager.getWinProxyDetails(countryCode)
            Napier.i(tag = TAG, message = "apps: $apps, ips: $ipCount, domains: $domainCount for country code: $countryCode, has details: ${details != null}")
            Triple(apps to domainCount to ipCount, details, details == null)
            }

        appsCount = loaded.first.first.first.toString()
        domainsCount = loaded.first.first.second.toString()
        ipsCount = loaded.first.second.toString()
        proxyName = loaded.second?.name.orEmpty()
        proxyWho = loaded.second?.who.orEmpty()
        proxyLatencyMs = loaded.second?.latencyMs
        proxyLastConnectedMs = loaded.second?.lastConnectedMs
        isProxyActive = loaded.second?.isActive == true
        showNoProxyFoundDialog = loaded.third
    }

    Scaffold(
        topBar = {
            RethinkTopBar(
                title = title,
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(paddingValues)
        ) {
            if (showNoProxyFoundDialog) {
                RethinkConfirmDialog(
                    onDismissRequest = {},
                    title = noProxyTitle,
                    message = noProxyDesc,
                    confirmText = stringResource(R.string.ada_noapp_dialog_positive),
                    onConfirm = onBackClick
                )
            }
            StatsRow(appsCount, domainsCount, ipsCount)
            Spacer(modifier = Modifier.height(12.dp))
            DetailsSection(
                countryCode = countryCode,
                proxyError = proxyError,
                proxyName = proxyName,
                proxyWho = proxyWho,
                proxyLatencyMs = proxyLatencyMs,
                proxyLastConnectedMs = proxyLastConnectedMs,
                isProxyActive = isProxyActive
            )
            Spacer(modifier = Modifier.height(16.dp))
            ActionButton(onClick = {
                Utilities.showToastUiCentered(
                    context,
                    appsInfoToast,
                    Toast.LENGTH_LONG
                )
            }, label = selectAppsLabel)
        }
    }
}

@Composable
private fun StatsRow(appsCount: String, domainsCount: String, ipsCount: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatCard(label = stringResource(R.string.rpn_proxy_apps), value = appsCount, modifier = Modifier.weight(1f))
        StatCard(label = stringResource(R.string.rpn_proxy_domains), value = domainsCount, modifier = Modifier.weight(1f))
        StatCard(label = stringResource(R.string.rpn_proxy_ips), value = ipsCount, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailsSection(
    countryCode: String,
    proxyError: String,
    proxyName: String,
    proxyWho: String,
    proxyLatencyMs: Int?,
    proxyLastConnectedMs: Long?,
    isProxyActive: Boolean
) {
    val fallback = stringResource(R.string.symbol_hyphen)
    val latencyText = proxyLatencyMs?.let { stringResource(R.string.dns_query_latency, it.toString()) } ?: fallback
    val lastConnectedText =
        if (proxyLastConnectedMs == null || proxyLastConnectedMs <= 0L) {
            fallback
        } else {
            val elapsedMs = (System.currentTimeMillis() - proxyLastConnectedMs).coerceAtLeast(0L)
            val minutes = elapsedMs / 60000L
            when {
                minutes < 1L -> stringResource(R.string.bubble_time_just_now)
                minutes < 60L -> stringResource(R.string.bubble_time_minutes_ago, minutes.toInt())
                else -> stringResource(R.string.bubble_time_hours_ago, (minutes / 60L).toInt())
            }
        }
    val statusText = if (isProxyActive) stringResource(R.string.rpn_proxy_connected) else stringResource(R.string.lbl_disabled)
    val statusColor = if (isProxyActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = proxyName.ifBlank { stringResource(R.string.rpn_proxy_name) },
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(12.dp))
        DetailRow(label = stringResource(R.string.rpn_proxy_who), value = proxyWho.ifBlank { fallback })
        if (proxyError.isNotEmpty()) {
            DetailRow(label = stringResource(R.string.rpn_proxy_error), value = proxyError, valueColor = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(12.dp))
        DetailRow(label = stringResource(R.string.rpn_proxy_country), value = countryCode.uppercase())
        DetailRow(label = stringResource(R.string.rpn_proxy_latency), value = latencyText)
        DetailRow(label = stringResource(R.string.rpn_proxy_last_connected), value = lastConnectedText)
        Spacer(modifier = Modifier.height(12.dp))
        DetailRow(label = stringResource(R.string.rpn_proxy_status), value = statusText, valueColor = statusColor)
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun ActionButton(onClick: () -> Unit, label: String) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_loop_back_app),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = label)
    }
}

private const val TAG = "RpnWinProxyDetails"
