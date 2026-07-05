/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.dns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class DnsCapabilityDot {
    Fast,
    Private,
    Secure,
    Anonymous
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsListScreen(
    appConfig: AppConfig,
    onConfigureOtherDns: (Int) -> Unit,
    onConfigureRethinkBasic: (Int) -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    var selectedType by remember { mutableStateOf<AppConfig.DnsType?>(null) }
    var selectedWorking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (dnsType, isWorking) = withContext(Dispatchers.IO) {
            val id = if (appConfig.isSmartDnsEnabled()) Backend.Plus else Backend.Preferred
            val state = VpnController.getDnsStatus(id)
            val working = if (state == null) false else when (Transaction.Status.fromId(state)) {
                Transaction.Status.COMPLETE, Transaction.Status.START -> true
                else -> false
            }
            appConfig.getDnsType() to working
        }
        selectedType = dnsType
        selectedWorking = isWorking
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.lbl_dns_servers),
                subtitle = stringResource(R.string.dns_desc),
                onBackClick = onBackClick,
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingMd,
                bottom = Dimensions.spacingLg
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
        ) {
            // DNS protocol cards grid
            item {
                SectionHeader(title = stringResource(R.string.lbl_configure))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DnsCard(
                            label = stringResource(R.string.dc_doh),
                            title = stringResource(R.string.cd_custom_doh_url_name_default),
                            dots = listOf(DnsCapabilityDot.Private, DnsCapabilityDot.Secure),
                            type = AppConfig.DnsType.DOH,
                            selectedType = selectedType,
                            selectedWorking = selectedWorking,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigureOtherDns(DnsScreenType.DOH.index) }
                        )
                        DnsCard(
                            label = stringResource(R.string.lbl_dot_abbr),
                            title = stringResource(R.string.lbl_dot),
                            dots = listOf(DnsCapabilityDot.Private, DnsCapabilityDot.Secure),
                            type = AppConfig.DnsType.DOT,
                            selectedType = selectedType,
                            selectedWorking = selectedWorking,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigureOtherDns(DnsScreenType.DOT.index) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DnsCard(
                            label = stringResource(R.string.dc_dns_crypt),
                            title = stringResource(R.string.cd_dns_crypt_name_default),
                            dots = listOf(DnsCapabilityDot.Private, DnsCapabilityDot.Secure, DnsCapabilityDot.Anonymous),
                            type = AppConfig.DnsType.DNSCRYPT,
                            selectedType = selectedType,
                            selectedWorking = selectedWorking,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigureOtherDns(DnsScreenType.DNS_CRYPT.index) }
                        )
                        DnsCard(
                            label = stringResource(R.string.lbl_dp_abbr),
                            title = stringResource(R.string.lbl_dp),
                            dots = listOf(DnsCapabilityDot.Fast),
                            type = AppConfig.DnsType.DNS_PROXY,
                            selectedType = selectedType,
                            selectedWorking = selectedWorking,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigureOtherDns(DnsScreenType.DNS_PROXY.index) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DnsCard(
                            label = stringResource(R.string.lbl_odoh_abbr),
                            title = stringResource(R.string.lbl_odoh),
                            dots = listOf(DnsCapabilityDot.Private, DnsCapabilityDot.Secure, DnsCapabilityDot.Anonymous),
                            type = AppConfig.DnsType.ODOH,
                            selectedType = selectedType,
                            selectedWorking = selectedWorking,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigureOtherDns(DnsScreenType.ODOH.index) }
                        )
                        DnsCard(
                            label = stringResource(R.string.dc_rethink_dns_radio),
                            title = stringResource(R.string.lbl_rdns),
                            dots = listOf(DnsCapabilityDot.Fast, DnsCapabilityDot.Private, DnsCapabilityDot.Secure),
                            type = AppConfig.DnsType.RETHINK_REMOTE,
                            selectedType = selectedType,
                            selectedWorking = selectedWorking,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigureRethinkBasic(0) }
                        )
                    }
                }
            }

            // Legend
            item { LegendRow() }
        }
    }
}

// ─── DNS Card ─────────────────────────────────────────────────────────────────

@Composable
private fun DnsCard(
    label: String,
    title: String,
    dots: List<DnsCapabilityDot>,
    type: AppConfig.DnsType,
    selectedType: AppConfig.DnsType?,
    selectedWorking: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isSelected = selectedType == type
    val containerColor = when {
        isSelected && selectedWorking -> MaterialTheme.colorScheme.primaryContainer
        isSelected && !selectedWorking -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val labelColor = when {
        isSelected && selectedWorking -> MaterialTheme.colorScheme.onPrimaryContainer
        isSelected && !selectedWorking -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val titleColor = when {
        isSelected && selectedWorking -> MaterialTheme.colorScheme.primary
        isSelected && !selectedWorking -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.aspectRatio(1.24f),
        shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
        color = containerColor,
        tonalElevation = 0.dp,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Protocol name — small label
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = labelColor,
                textAlign = TextAlign.Center
            )

            // Abbreviation — large and prominent
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = titleColor,
                textAlign = TextAlign.Center
            )

            // Capability dots
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                dots.forEach { dot ->
                    DotIndicator(dot = dot)
                }
            }
        }
    }
}

// ─── Legend Row ───────────────────────────────────────────────────────────────

@Composable
private fun LegendRow() {
    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadiusLg),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.spacingLg, vertical = Dimensions.spacingMd)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(DnsCapabilityDot.Fast, stringResource(R.string.lbl_fast))
            LegendItem(DnsCapabilityDot.Private, stringResource(R.string.lbl_private))
            LegendItem(DnsCapabilityDot.Secure, stringResource(R.string.lbl_secure))
            LegendItem(DnsCapabilityDot.Anonymous, stringResource(R.string.lbl_anonymous))
        }
    }
}

@Composable
private fun LegendItem(dot: DnsCapabilityDot, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DotIndicator(dot = dot)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DotIndicator(dot: DnsCapabilityDot, modifier: Modifier = Modifier) {
    val color =
        when (dot) {
            DnsCapabilityDot.Fast -> MaterialTheme.colorScheme.error
            DnsCapabilityDot.Private -> MaterialTheme.colorScheme.tertiary
            DnsCapabilityDot.Secure -> MaterialTheme.colorScheme.primary
            DnsCapabilityDot.Anonymous -> MaterialTheme.colorScheme.secondary
        }

    Box(
        modifier =
            modifier
                .size(10.dp)
                .background(color = color, shape = CircleShape)
    )
}
