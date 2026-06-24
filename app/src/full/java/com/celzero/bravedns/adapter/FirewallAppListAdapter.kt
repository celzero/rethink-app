/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.compose.apps.DiagonalWipeIcon
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

enum class FirewallRowPosition {
    First,
    Middle,
    Last,
    Single
}

@Composable
fun FirewallAppRow(
    appInfo: AppInfo,
    eventLogger: EventLogger,
    searchQuery: String = "",
    rowPosition: FirewallRowPosition = FirewallRowPosition.Single,
    onAppClick: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialogState by remember(appInfo.uid) { mutableStateOf<FirewallAppDialogState?>(null) }
    val packageManager = context.packageManager
    var appStatus by remember(appInfo.uid, appInfo.firewallStatus) {
        mutableStateOf(FirewallManager.FirewallStatus.getStatus(appInfo.firewallStatus))
    }
    var connStatus by remember(appInfo.uid, appInfo.connectionStatus) {
        mutableStateOf(FirewallManager.ConnectionStatus.getStatus(appInfo.connectionStatus))
    }
    var appIcon by
    remember(appInfo.packageName) {
        mutableStateOf<Drawable?>(FirewallAppIconCache.get(appInfo.packageName))
    }
    var proxyEnabled by remember(appInfo.uid) { mutableStateOf(false) }
    val isSelfApp = appInfo.packageName == context.packageName
    val tombstoned = appInfo.tombstoneTs > 0
    val nameAlpha = if (appInfo.hasInternetPermission(packageManager)) 1f else 0.4f

    LaunchedEffect(appInfo.uid, appInfo.firewallStatus, appInfo.connectionStatus) {
        appStatus = FirewallManager.FirewallStatus.getStatus(appInfo.firewallStatus)
        connStatus = FirewallManager.ConnectionStatus.getStatus(appInfo.connectionStatus)
    }

    LaunchedEffect(appInfo.uid, appInfo.packageName, appInfo.appName, appInfo.isProxyExcluded) {
        if (appIcon == null) {
            val icon =
                withContext(Dispatchers.IO) {
                    getIcon(context, appInfo.packageName, appInfo.appName)
                }
            appIcon = icon
            FirewallAppIconCache.put(appInfo.packageName, icon)
        }
        val proxyId = ProxyManager.getProxyIdForApp(appInfo.uid)
        proxyEnabled = !appInfo.isProxyExcluded && proxyId.isNotEmpty() && proxyId != ID_NONE
    }

    val hasDataUsage = appInfo.uploadBytes > 0L || appInfo.downloadBytes > 0L
    val dataUsageText = if (hasDataUsage) buildDataUsageText(context, appInfo) else ""
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "rowScale"
    )
    val highlightedAppName =
        buildHighlightedText(
            text = appInfo.appName,
            query = searchQuery,
            highlightColor = MaterialTheme.colorScheme.primary
        )
    val statusText =
        if (isSelfApp) {
            ""
        } else {
            getFirewallText(context, appStatus, connStatus)
        }
    val statusColor = getStatusColor(appStatus, connStatus)
    val accentColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "accentColor"
    )
    val wifiIcon = wifiIconRes(appStatus, connStatus, isSelfApp)
    val mobileIcon = mobileIconRes(appStatus, connStatus, isSelfApp)
    val wifiBlocked = wifiIcon == R.drawable.ic_firewall_wifi_off
    val mobileBlocked = mobileIcon == R.drawable.ic_firewall_data_off
    val wifiTint =
        if (wifiBlocked) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val mobileTint =
        if (mobileBlocked) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val wifiCircleMotion = rememberBlockCircleMotion(blocked = wifiBlocked, labelPrefix = "wifi")
    val mobileCircleMotion = rememberBlockCircleMotion(blocked = mobileBlocked, labelPrefix = "mobile")

    val shape = rowShapeFor(rowPosition)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    onAppClick?.invoke(appInfo.uid) ?: openAppDetailActivity(context, appInfo.uid)
                }
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimensions.spacingMd,
                    vertical = 9.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val iconPainter =
                rememberDrawablePainter(appIcon)
                    ?: rememberDrawablePainter(Utilities.getDefaultIcon(context))
            iconPainter?.let { painter ->
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = highlightedAppName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = nameAlpha),
                        textDecoration = if (tombstoned) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (statusText.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(Dimensions.buttonCornerRadius),
                            color = accentColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor,
                                modifier = Modifier.padding(
                                    horizontal = 6.dp,
                                    vertical = 2.dp
                                )
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (proxyEnabled) {
                        Surface(
                            shape = RoundedCornerShape(Dimensions.buttonCornerRadius),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
                        ) {
                            Text(
                                text = "Proxy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(
                                    horizontal = 6.dp,
                                    vertical = 1.dp
                                )
                            )
                        }
                    }
                }
                if (hasDataUsage) {
                    Text(
                        text = dataUsageText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = Dimensions.Opacity.MEDIUM
                        )
                    )
                }
            }

            if (wifiIcon != null) {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = wifiCircleMotion.alpha
                                    scaleX = wifiCircleMotion.scale
                                    scaleY = wifiCircleMotion.scale
                                }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                    )
                    IconButton(
                        onClick = {
                            handleWifiToggle(
                                scope = scope,
                                eventLogger = eventLogger,
                                appInfo = appInfo,
                                onShowDialog = { packageList ->
                                    dialogState =
                                        FirewallAppDialogState(
                                            packageList,
                                            appInfo,
                                            isWifi = true
                                        )
                                },
                                onStatusUpdated = { newAppStatus, newConnStatus ->
                                    appStatus = newAppStatus
                                    connStatus = newConnStatus
                                }
                            )
                        },
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    ) {
                        DiagonalWipeIcon(
                            blocked = wifiBlocked,
                            allowedIcon = Icons.Rounded.Wifi,
                            blockedIcon = Icons.Rounded.WifiOff,
                            allowedTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            blockedTint = wifiTint,
                            contentDescription = stringResource(R.string.firewall_rule_block_unmetered),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (mobileIcon != null) {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = mobileCircleMotion.alpha
                                    scaleX = mobileCircleMotion.scale
                                    scaleY = mobileCircleMotion.scale
                                }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                    )
                    IconButton(
                        onClick = {
                            handleMobileToggle(
                                scope = scope,
                                eventLogger = eventLogger,
                                appInfo = appInfo,
                                onShowDialog = { packageList ->
                                    dialogState =
                                        FirewallAppDialogState(
                                            packageList,
                                            appInfo,
                                            isWifi = false
                                        )
                                },
                                onStatusUpdated = { newAppStatus, newConnStatus ->
                                    appStatus = newAppStatus
                                    connStatus = newConnStatus
                                }
                            )
                        },
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    ) {
                        DiagonalWipeIcon(
                            blocked = mobileBlocked,
                            allowedIcon = Icons.Rounded.PhoneAndroid,
                            blockedIcon = Icons.Rounded.MobileOff,
                            allowedTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            blockedTint = mobileTint,
                            contentDescription = stringResource(R.string.lbl_mobile_data),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    dialogState?.let { state ->
        val count = state.packageList.count()
        RethinkConfirmDialog(
            onDismissRequest = { dialogState = null },
            title =
                stringResource(
                    R.string.ctbs_block_other_apps,
                    state.appInfo.appName,
                    count.toString()
                ),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)) {
                    state.packageList.forEach { name ->
                        Text(text = name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmText = stringResource(R.string.lbl_proceed),
            dismissText = stringResource(R.string.ctbs_dialog_negative_btn),
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    val updatedConnStatus =
                        if (state.isWifi) {
                            toggleWifi(eventLogger, state.appInfo)
                        } else {
                            toggleMobileData(eventLogger, state.appInfo)
                        }
                    withContext(Dispatchers.Main) {
                        appStatus = FirewallManager.FirewallStatus.NONE
                        connStatus = updatedConnStatus
                    }
                }
                dialogState = null
            },
            onDismiss = { dialogState = null }
        )
    }
}

@Composable
private fun getStatusColor(
    appStatus: FirewallManager.FirewallStatus,
    connStatus: FirewallManager.ConnectionStatus
): Color {
    return when (appStatus) {
        FirewallManager.FirewallStatus.NONE ->
            when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> MaterialTheme.colorScheme.primary
                FirewallManager.ConnectionStatus.METERED -> MaterialTheme.colorScheme.error
                FirewallManager.ConnectionStatus.UNMETERED -> MaterialTheme.colorScheme.error
                FirewallManager.ConnectionStatus.BOTH -> MaterialTheme.colorScheme.error
            }

        FirewallManager.FirewallStatus.EXCLUDE -> MaterialTheme.colorScheme.tertiary
        FirewallManager.FirewallStatus.ISOLATE -> MaterialTheme.colorScheme.error
        FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> MaterialTheme.colorScheme.tertiary
        FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> MaterialTheme.colorScheme.tertiary
        FirewallManager.FirewallStatus.UNTRACKED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private data class FirewallAppDialogState(
    val packageList: List<String>,
    val appInfo: AppInfo,
    val isWifi: Boolean
)

private data class BlockCircleMotion(
    val alpha: Float,
    val scale: Float
)

@Composable
private fun rememberBlockCircleMotion(blocked: Boolean, labelPrefix: String): BlockCircleMotion {
    val transition = updateTransition(targetState = blocked, label = "${labelPrefix}BlockCircle")

    val alpha by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                tween(durationMillis = 240, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 170, easing = FastOutLinearInEasing)
            }
        },
        label = "${labelPrefix}BlockCircleAlpha"
    ) { isBlocked ->
        if (isBlocked) 0.44f else 0f
    }

    val scale by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                spring(
                    dampingRatio = 0.80f,
                    stiffness = 620f
                )
            } else {
                tween(durationMillis = 190, easing = FastOutLinearInEasing)
            }
        },
        label = "${labelPrefix}BlockCircleScale"
    ) { isBlocked ->
        if (isBlocked) 1f else 0.72f
    }

    return BlockCircleMotion(alpha = alpha, scale = scale)
}

private fun buildDataUsageText(context: Context, appInfo: AppInfo): String {
    val u = Utilities.humanReadableByteCount(appInfo.uploadBytes, true)
    val uploadBytes = context.getString(R.string.symbol_upload, u)
    val d = Utilities.humanReadableByteCount(appInfo.downloadBytes, true)
    val downloadBytes = context.getString(R.string.symbol_download, d)
    return context.getString(R.string.two_argument, uploadBytes, downloadBytes)
}

private fun getFirewallText(
    context: Context,
    aStat: FirewallManager.FirewallStatus,
    cStat: FirewallManager.ConnectionStatus
): String {
    return when (aStat) {
        FirewallManager.FirewallStatus.NONE ->
            when (cStat) {
                FirewallManager.ConnectionStatus.ALLOW -> ""
                FirewallManager.ConnectionStatus.METERED ->
                    context.getString(R.string.lbl_blocked)

                FirewallManager.ConnectionStatus.UNMETERED ->
                    context.getString(R.string.lbl_blocked)

                FirewallManager.ConnectionStatus.BOTH ->
                    context.getString(R.string.lbl_blocked)
            }

        FirewallManager.FirewallStatus.EXCLUDE ->
            context.getString(R.string.fapps_firewall_filter_excluded)

        FirewallManager.FirewallStatus.ISOLATE ->
            context.getString(R.string.fapps_firewall_filter_isolate)

        FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ->
            context.getString(R.string.fapps_firewall_filter_bypass_universal)

        FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
            context.getString(R.string.fapps_firewall_filter_bypass_universal)

        FirewallManager.FirewallStatus.UNTRACKED ->
            context.getString(R.string.network_log_app_name_unknown)
    }
}

private fun wifiIconRes(
    firewallStatus: FirewallManager.FirewallStatus,
    connStatus: FirewallManager.ConnectionStatus,
    isSelfApp: Boolean
): Int? {
    if (isSelfApp) return null
    return when (firewallStatus) {
        FirewallManager.FirewallStatus.NONE ->
            when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> R.drawable.ic_firewall_wifi_on
                FirewallManager.ConnectionStatus.UNMETERED -> R.drawable.ic_firewall_wifi_off
                FirewallManager.ConnectionStatus.METERED -> R.drawable.ic_firewall_wifi_on
                FirewallManager.ConnectionStatus.BOTH -> R.drawable.ic_firewall_wifi_off
            }

        FirewallManager.FirewallStatus.EXCLUDE,
        FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
        FirewallManager.FirewallStatus.ISOLATE,
        FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
            R.drawable.ic_firewall_wifi_on_grey

        else -> R.drawable.ic_firewall_wifi_on
    }
}

private fun mobileIconRes(
    firewallStatus: FirewallManager.FirewallStatus,
    connStatus: FirewallManager.ConnectionStatus,
    isSelfApp: Boolean
): Int? {
    if (isSelfApp) return null
    return when (firewallStatus) {
        FirewallManager.FirewallStatus.NONE ->
            when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> R.drawable.ic_firewall_data_on
                FirewallManager.ConnectionStatus.UNMETERED -> R.drawable.ic_firewall_data_on
                FirewallManager.ConnectionStatus.METERED -> R.drawable.ic_firewall_data_off
                FirewallManager.ConnectionStatus.BOTH -> R.drawable.ic_firewall_data_off
            }

        FirewallManager.FirewallStatus.EXCLUDE,
        FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
        FirewallManager.FirewallStatus.ISOLATE,
        FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
            R.drawable.ic_firewall_data_on_grey

        else -> R.drawable.ic_firewall_data_on
    }
}

private fun handleWifiToggle(
    scope: CoroutineScope,
    eventLogger: EventLogger,
    appInfo: AppInfo,
    onShowDialog: (List<String>) -> Unit,
    onStatusUpdated: (FirewallManager.FirewallStatus, FirewallManager.ConnectionStatus) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
        if (appNames.count() > 1) {
            withContext(Dispatchers.Main) {
                onShowDialog(appNames)
            }
            return@launch
        }
        val updatedConnStatus = toggleWifi(eventLogger, appInfo)
        withContext(Dispatchers.Main) {
            onStatusUpdated(FirewallManager.FirewallStatus.NONE, updatedConnStatus)
        }
    }
}

private fun handleMobileToggle(
    scope: CoroutineScope,
    eventLogger: EventLogger,
    appInfo: AppInfo,
    onShowDialog: (List<String>) -> Unit,
    onStatusUpdated: (FirewallManager.FirewallStatus, FirewallManager.ConnectionStatus) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
        if (appNames.count() > 1) {
            withContext(Dispatchers.Main) {
                onShowDialog(appNames)
            }
            return@launch
        }
        val updatedConnStatus = toggleMobileData(eventLogger, appInfo)
        withContext(Dispatchers.Main) {
            onStatusUpdated(FirewallManager.FirewallStatus.NONE, updatedConnStatus)
        }
    }
}

private suspend fun toggleMobileData(
    eventLogger: EventLogger,
    appInfo: AppInfo
): FirewallManager.ConnectionStatus {
    return FirewallToggleLock.withLock {
        val connStatus = FirewallManager.connectionStatus(appInfo.uid)
        val updatedConnStatus = nextConnStatusForMobileToggle(connStatus)
        when (connStatus) {
            FirewallManager.ConnectionStatus.METERED -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }

            FirewallManager.ConnectionStatus.UNMETERED -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.BOTH
                )
            }

            FirewallManager.ConnectionStatus.BOTH -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.UNMETERED
                )
            }

            FirewallManager.ConnectionStatus.ALLOW -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.METERED
                )
            }
        }
        logEvent(
            eventLogger,
            "UID: ${appInfo.uid}, App: ${appInfo.appName}, New FW status: ${
                FirewallManager.connectionStatus(
                    appInfo.uid
                )
            }"
        )
        updatedConnStatus
    }
}

private suspend fun toggleWifi(
    eventLogger: EventLogger,
    appInfo: AppInfo
): FirewallManager.ConnectionStatus {
    return FirewallToggleLock.withLock {
        val connStatus = FirewallManager.connectionStatus(appInfo.uid)
        val updatedConnStatus = nextConnStatusForWifiToggle(connStatus)
        when (connStatus) {
            FirewallManager.ConnectionStatus.METERED -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.BOTH
                )
            }

            FirewallManager.ConnectionStatus.UNMETERED -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }

            FirewallManager.ConnectionStatus.BOTH -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.METERED
                )
            }

            FirewallManager.ConnectionStatus.ALLOW -> {
                updateFirewallStatus(
                    appInfo.uid,
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.UNMETERED
                )
            }
        }
        logEvent(
            eventLogger,
            "UID: ${appInfo.uid}, App: ${appInfo.appName}, New FW status: ${
                FirewallManager.connectionStatus(
                    appInfo.uid
                )
            }"
        )
        updatedConnStatus
    }
}

private fun nextConnStatusForMobileToggle(
    connStatus: FirewallManager.ConnectionStatus
): FirewallManager.ConnectionStatus {
    return when (connStatus) {
        FirewallManager.ConnectionStatus.METERED -> FirewallManager.ConnectionStatus.ALLOW
        FirewallManager.ConnectionStatus.UNMETERED -> FirewallManager.ConnectionStatus.BOTH
        FirewallManager.ConnectionStatus.BOTH -> FirewallManager.ConnectionStatus.UNMETERED
        FirewallManager.ConnectionStatus.ALLOW -> FirewallManager.ConnectionStatus.METERED
    }
}

private fun nextConnStatusForWifiToggle(
    connStatus: FirewallManager.ConnectionStatus
): FirewallManager.ConnectionStatus {
    return when (connStatus) {
        FirewallManager.ConnectionStatus.METERED -> FirewallManager.ConnectionStatus.BOTH
        FirewallManager.ConnectionStatus.UNMETERED -> FirewallManager.ConnectionStatus.ALLOW
        FirewallManager.ConnectionStatus.BOTH -> FirewallManager.ConnectionStatus.METERED
        FirewallManager.ConnectionStatus.ALLOW -> FirewallManager.ConnectionStatus.UNMETERED
    }
}

private fun openAppDetailActivity(context: Context, uid: Int) {
    val intent = Intent(context, HomeScreenActivity::class.java)
    intent.putExtra(HomeScreenActivity.EXTRA_NAV_TARGET, HomeScreenActivity.NAV_TARGET_APP_INFO)
    intent.putExtra(HomeScreenActivity.EXTRA_APP_INFO_UID, uid)
    context.startActivity(intent)
}

private fun logEvent(eventLogger: EventLogger, details: String) {
    eventLogger.log(
        EventType.FW_RULE_MODIFIED,
        Severity.LOW,
        "App list, rule change",
        EventSource.UI,
        false,
        details
    )
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank() || text.isBlank()) return AnnotatedString(text)

    val terms =
        normalizedQuery
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    if (terms.isEmpty()) return AnnotatedString(text)

    val normalizedText = text.lowercase()
    val ranges = mutableListOf<IntRange>()

    terms.forEach { term ->
        val normalizedTerm = term.lowercase()
        var from = 0
        while (from < normalizedText.length) {
            val start = normalizedText.indexOf(normalizedTerm, from)
            if (start == -1) break
            ranges.add(start until (start + normalizedTerm.length))
            from = start + normalizedTerm.length
        }
    }

    if (ranges.isEmpty()) return AnnotatedString(text)

    val mergedRanges = ranges.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, range ->
        val last = acc.lastOrNull()
        if (last == null || range.first > last.last + 1) {
            acc.add(range)
        } else {
            acc[acc.lastIndex] = last.first..maxOf(last.last, range.last)
        }
        acc
    }

    return buildAnnotatedString {
        append(text)
        mergedRanges.forEach { range ->
            addStyle(
                style = SpanStyle(color = highlightColor),
                start = range.first,
                end = range.last + 1
            )
        }
    }
}

private fun rowShapeFor(position: FirewallRowPosition): RoundedCornerShape {
    return when (position) {
        FirewallRowPosition.First ->
            RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 10.dp,
                bottomEnd = 10.dp
            )

        FirewallRowPosition.Middle -> RoundedCornerShape(10.dp)
        FirewallRowPosition.Last ->
            RoundedCornerShape(
                topStart = 10.dp,
                topEnd = 10.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            )

        FirewallRowPosition.Single -> RoundedCornerShape(18.dp)
    }
}

private object FirewallAppIconCache {
    private const val CACHE_SIZE = 256
    private val cache = LruCache<String, Drawable>(CACHE_SIZE)

    fun get(packageName: String): Drawable? = cache.get(packageName)

    fun put(packageName: String, icon: Drawable?) {
        if (packageName.isBlank() || icon == null) return
        cache.put(packageName, icon)
    }
}

private object FirewallToggleLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }
}
