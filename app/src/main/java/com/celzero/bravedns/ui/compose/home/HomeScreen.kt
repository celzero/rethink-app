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
package com.celzero.bravedns.ui.compose.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShieldMoon
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkTheme
import com.celzero.bravedns.ui.compose.theme.cardPositionFor

data class HomeScreenUiState(
    val isVpnActive: Boolean = false,
    val dnsLatency: String = "-- ms",
    val dnsConnectedName: String = "",
    val firewallUniversalRules: Int = 0,
    val firewallIpRules: Int = 0,
    val firewallDomainRules: Int = 0,
    val proxyStatus: String = "",
    val networkLogsCount: Long = 0,
    val dnsLogsCount: Long = 0,
    val appsAllowed: Int = 0,
    val appsBlocked: Int = 0,
    val appsTotal: Int = 0,
    val appsBypassed: Int = 0,
    val appsIsolated: Int = 0,
    val appsExcluded: Int = 0,
    val protectionStatus: String = "",
    val isProtectionFailing: Boolean = false
)

private data class StatusItem(
    val headline: String,
    val supporting: String,
    val iconPainter: Painter,
    val iconAccentColor: Color,
    val onClick: () -> Unit,
)

private data class HomeStatusIconTints(
    val apps: Color,
    val dns: Color,
    val firewall: Color,
    val proxy: Color,
    val logs: Color
)

private val HomePrimaryCardShape =
    RoundedCornerShape(Dimensions.heroCornerRadius)
private val HomeSecondaryCardShape =
    RoundedCornerShape(Dimensions.heroCornerRadius)

@Composable
private fun rememberHomeStatusIconTints(): HomeStatusIconTints {
    return HomeStatusIconTints(
        apps = Color(0xFF74C5FF),
        dns = Color(0xFFC5ACFF),
        firewall = Color(0xFFFF907F),
        proxy = Color(0xFF46EBC8),
        logs = Color(0xFF7EED92)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeScreenUiState,
    onStartStopClick: () -> Unit,
    onDnsClick: () -> Unit,
    onFirewallClick: () -> Unit,
    onProxyClick: () -> Unit,
    onLogsClick: () -> Unit,
    onAppsClick: () -> Unit,
    onSponsorClick: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val iconTints = rememberHomeStatusIconTints()

    val dnsSummary = if (uiState.dnsConnectedName.isNotBlank()) {
        "${uiState.dnsConnectedName} · ${uiState.dnsLatency}"
    } else {
        stringResource(R.string.lbl_inactive)
    }

    val firewallSummary =
        "${uiState.firewallUniversalRules} ${stringResource(R.string.lbl_universal_rules)}" +
                " · ${uiState.firewallIpRules} IP · ${uiState.firewallDomainRules} domain"

    val proxySummary = uiState.proxyStatus.ifEmpty { stringResource(R.string.lbl_inactive) }

    val logsSummary =
        "${uiState.networkLogsCount} ${stringResource(R.string.lbl_network)}" +
                " · ${uiState.dnsLogsCount} DNS"

    val appsSummary =
        "${uiState.appsAllowed}/${uiState.appsTotal} ${stringResource(R.string.lbl_allowed)}" +
                " · ${uiState.appsBlocked} ${stringResource(R.string.lbl_blocked)}"

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.txt_home),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior
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
                top = Dimensions.spacingSm,
                bottom = Dimensions.spacing3xl
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            item {
                ProtectionCard(uiState = uiState, onStartStopClick = onStartStopClick)
            }

            item {
                StatusSection(
                    title = stringResource(R.string.lbl_status),
                    accentColor = MaterialTheme.colorScheme.primary,
                    items = listOf(
                        StatusItem(
                            headline = stringResource(R.string.lbl_dns),
                            supporting = dnsSummary,
                            iconPainter = painterResource(id = R.drawable.dns_home_screen),
                            iconAccentColor = iconTints.dns,
                            onClick = onDnsClick,
                        ),
                        StatusItem(
                            headline = stringResource(R.string.lbl_firewall),
                            supporting = firewallSummary,
                            iconPainter = painterResource(id = R.drawable.firewall_home_screen),
                            iconAccentColor = iconTints.firewall,
                            onClick = onFirewallClick,
                        ),
                        StatusItem(
                            headline = stringResource(R.string.lbl_proxy),
                            supporting = proxySummary,
                            iconPainter = painterResource(id = R.drawable.ic_vpn),
                            iconAccentColor = iconTints.proxy,
                            onClick = onProxyClick,
                        ),
                        StatusItem(
                            headline = stringResource(R.string.lbl_logs),
                            supporting = logsSummary,
                            iconPainter = painterResource(id = R.drawable.ic_logs_accent),
                            iconAccentColor = iconTints.logs,
                            onClick = onLogsClick,
                        )
                    ),
                )
            }

            item {
                AppsHealthCard(
                    uiState = uiState,
                    onClick = onAppsClick
                )
            }
        }
    }
}

// ─── Status Section ───────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun StatusSection(
    title: String,
    accentColor: Color,
    items: List<StatusItem>,
) {
    val iconTint = MaterialTheme.colorScheme.onPrimaryFixed.copy(alpha = 0.8f)
    val statusIconShape = MaterialShapes.Cookie9Sided.toShape()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(
                0.8f,
                androidx.compose.ui.unit.TextUnitType.Sp
            ),
            color = accentColor,
            modifier = Modifier.padding(
                start = Dimensions.spacingLg,
                bottom = Dimensions.spacingSm,
            ),
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                RethinkListItem(
                    headline = item.headline,
                    supporting = item.supporting,
                    leadingIconPainter = item.iconPainter,
                    leadingIconTint = iconTint,
                    leadingIconContainerColor = item.iconAccentColor,
                    leadingIconContainerShape = statusIconShape,
                    position = cardPositionFor(index = index, lastIndex = items.lastIndex),
                    onClick = item.onClick
                )
            }
        }
    }
}

// ─── Protection Status Card ───────────────────────────────────────────────

@Composable
private fun ProtectionCard(
    uiState: HomeScreenUiState,
    onStartStopClick: () -> Unit
) {
    val statusColors = rememberHomeStatusIconTints()
    val iconGlyphTint = MaterialTheme.colorScheme.onPrimaryFixed.copy(alpha = 0.8f)
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val error = MaterialTheme.colorScheme.error

    val targetIcon = when {
        uiState.isProtectionFailing -> Icons.Rounded.WarningAmber
        uiState.isVpnActive -> Icons.Rounded.Shield
        else -> Icons.Rounded.ShieldMoon
    }

    val targetAccentColor = when {
        uiState.isProtectionFailing -> statusColors.firewall
        uiState.isVpnActive -> statusColors.proxy
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val targetContainerColor = when {
        uiState.isProtectionFailing -> statusColors.firewall
        uiState.isVpnActive -> statusColors.proxy
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val targetBorderColor = when {
        uiState.isProtectionFailing -> statusColors.firewall.copy(alpha = 0.34f)
        uiState.isVpnActive -> statusColors.proxy.copy(alpha = 0.34f)
        else -> outlineVariant.copy(alpha = 0.36f)
    }

    val accentColor by animateColorAsState(
        targetValue = targetAccentColor,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "protectionCardAccent"
    )

    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "protectionCardIconContainer"
    )

    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "protectionCardBorder"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "pressScale"
    )

    val statusLabel = uiState.protectionStatus.ifEmpty {
        if (uiState.isVpnActive) "Protected" else "Not active"
    }

    Surface(
        onClick = onStartStopClick,
        shape = HomePrimaryCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Column(modifier = Modifier.padding(Dimensions.cardPadding)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
            ) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.iconContainerRadius),
                    color = containerColor,
                    modifier = Modifier.size(Dimensions.iconContainerLg)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = targetIcon,
                            contentDescription = null,
                            tint = if (uiState.isVpnActive || uiState.isProtectionFailing) {
                                iconGlyphTint
                            } else {
                                accentColor
                            },
                            modifier = Modifier.size(Dimensions.iconSizeMd)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Protection",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                StartStopButton(
                    isPlaying = uiState.isVpnActive,
                    onClick = onStartStopClick
                )
            }

            Spacer(modifier = Modifier.height(Dimensions.spacingMd))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
            ) {
                MetricChip(
                    label = "Latency",
                    value = uiState.dnsLatency,
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    label = stringResource(R.string.lbl_network),
                    value = uiState.networkLogsCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    label = stringResource(R.string.lbl_blocked),
                    value = uiState.appsBlocked.toString(),
                    valueColor = if (uiState.appsBlocked > 0) statusColors.firewall
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

// ─── Apps Health Card ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsHealthCard(
    uiState: HomeScreenUiState,
    onClick: () -> Unit
) {
    val statusColors = rememberHomeStatusIconTints()
    val iconGlyphTint = MaterialTheme.colorScheme.onPrimaryFixed.copy(alpha = 0.8f)
    val appsProgress = remember(uiState.appsAllowed, uiState.appsTotal) {
        if (uiState.appsTotal > 0) uiState.appsAllowed.toFloat() / uiState.appsTotal.toFloat()
        else 0f
    }

    Surface(
        onClick = onClick,
        shape = HomeSecondaryCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))
    ) {
        Column(modifier = Modifier.padding(Dimensions.cardPadding)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.iconContainerRadius),
                    color = statusColors.apps,
                    modifier = Modifier.size(Dimensions.iconContainerMd)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_app_info_accent),
                            contentDescription = null,
                            tint = iconGlyphTint,
                            modifier = Modifier.size(Dimensions.iconSizeSm)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.lbl_apps),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                    color = statusColors.apps
                ) {
                    Text(
                        text = uiState.appsTotal.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = iconGlyphTint,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimensions.spacingMd))

            LinearProgressIndicator(
                progress = { appsProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(Dimensions.cornerRadiusPill)),
                color = statusColors.apps,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(Dimensions.spacingMd))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AppStat(
                    label = stringResource(R.string.lbl_allowed),
                    value = uiState.appsAllowed.toString(),
                    color = MaterialTheme.colorScheme.secondary
                )
                AppStat(
                    label = stringResource(R.string.lbl_blocked),
                    value = uiState.appsBlocked.toString(),
                    color = if (uiState.appsBlocked > 0) statusColors.firewall
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppStat(
                    label = stringResource(R.string.lbl_bypassed),
                    value = uiState.appsBypassed.toString(),
                    color = statusColors.dns
                )
            }
        }
    }
}

@Composable
private fun AppStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    RethinkTheme {
        HomeScreen(
            uiState = HomeScreenUiState(
                isVpnActive = true,
                dnsLatency = "24ms",
                dnsConnectedName = "Cloudflare",
                firewallUniversalRules = 12,
                firewallIpRules = 3,
                firewallDomainRules = 8,
                appsTotal = 120,
                appsAllowed = 115,
                appsBlocked = 5,
                appsBypassed = 2,
                networkLogsCount = 4320,
                dnsLogsCount = 1230,
                protectionStatus = "Protected"
            ),
            onStartStopClick = {},
            onDnsClick = {},
            onFirewallClick = {},
            onProxyClick = {},
            onLogsClick = {},
            onAppsClick = {},
            onSponsorClick = {}
        )
    }
}
