/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.celzero.bravedns.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.getDurationInHumanReadableFormat
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_BYTES = 500000 // 500 KB
private const val MAX_TIME_TCP = 135 // seconds
private const val MAX_TIME_UDP = 135 // seconds
private const val NO_USER_ID = 0

private data class ConnectionRowPalette(
    val status: Color,
    val statusContainer: Color,
    val statusLabel: String,
    val surfaceCollapsed: Color,
    val surfaceExpanded: Color,
    val surfaceSubtle: Color,
    val line: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tagBg: Color,
    val tagText: Color,
)

@Composable
private fun connectionRowPalette(ct: ConnectionTracker): ConnectionRowPalette {
    val scheme = MaterialTheme.colorScheme
    val allowedGreen = Color(0xFF2FB36B)
    val statusColor = if (ct.isBlocked) scheme.error else allowedGreen
    val statusContainer = if (ct.isBlocked) scheme.errorContainer.copy(alpha = 0.55f) else allowedGreen.copy(alpha = 0.2f)

    return ConnectionRowPalette(
        status = statusColor,
        statusContainer = statusContainer,
        statusLabel = if (ct.isBlocked) stringResource(R.string.lbl_blocked) else stringResource(R.string.lbl_allowed),
        surfaceCollapsed = scheme.surfaceContainerLow,
        surfaceExpanded = scheme.surfaceContainer,
        surfaceSubtle = scheme.surfaceContainerHighest.copy(alpha = 0.3f),
        line = scheme.outlineVariant.copy(alpha = 0.42f),
        primaryText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        tagBg = scheme.surfaceContainerHighest.copy(alpha = 0.58f),
        tagText = scheme.onSurfaceVariant,
    )
}

@Composable
fun ConnectionRow(
    ct: ConnectionTracker,
    index: Int = 0,
    itemCount: Int = 1,
) {
    val context = LocalContext.current
    val palette = connectionRowPalette(ct)
    val summary = summaryInfo(context, ct)
    val hintColor = hintColor(ct) ?: palette.secondaryText
    val protocol = protocolLabel(context, ct.port, ct.protocol)
    val time = Utilities.convertLongToTime(ct.timeStamp, TIME_FORMAT_1)
    val destination = ct.dnsQuery?.takeIf { it.isNotBlank() } ?: ct.ipAddress
    val appDisplay = if (ct.appName.isBlank()) stringResource(R.string.network_log_app_name_unknown) else ct.appName

    var expanded by remember(ct.id) { mutableStateOf(false) }
    var showDetails by remember(ct.id) { mutableStateOf(false) }
    var appIcon by remember(ct.uid) { mutableStateOf<Drawable?>(null) }
    var appCount by remember(ct.uid) { mutableStateOf(1) }

    LaunchedEffect(ct.uid, ct.appName, ct.usrId) {
        val apps = withContext(Dispatchers.IO) { FirewallManager.getPackageNamesByUid(ct.uid) }
        appCount = apps.size
        appIcon = if (apps.isEmpty()) null else getIcon(context, apps[0])
    }

    val appName =
        when {
            ct.usrId != NO_USER_ID ->
                stringResource(R.string.about_version_install_source, appDisplay, ct.usrId.toString())
            appCount > 1 ->
                stringResource(R.string.ctbs_app_other_apps, appDisplay, "${appCount - 1}")
            else -> appDisplay
        }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val rowScale by animateFloatAsState(
        targetValue = if (isPressed) 0.988f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "connRowScale",
    )

    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "connChevron",
    )

    val baseCardColor = if (expanded) palette.surfaceExpanded else palette.surfaceCollapsed
    val pressedCardColor = lerp(baseCardColor, MaterialTheme.colorScheme.primaryContainer, 0.16f)
    val cardColor by animateColorAsState(
        targetValue = if (isPressed) pressedCardColor else baseCardColor,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "connCardColor",
    )

    val shadowElevation by animateDpAsState(
        targetValue =
            when {
                isPressed -> 3.dp
                expanded -> 6.dp
                else -> 1.dp
            },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "connShadow",
    )

    val stripeAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0.9f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "connStripeAlpha",
    )

    val detailsProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 230, easing = FastOutSlowInEasing),
        label = "connDetailsProgress",
        finishedListener = { value -> if (value == 0f) showDetails = false },
    )

    LaunchedEffect(expanded) {
        if (expanded) showDetails = true
    }

    val cardShape = ListItemDefaults.segmentedShapes(index = index, count = itemCount)

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(rowScale)
                .clip(cardShape.shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { expanded = !expanded },
                ),
        shape = cardShape.shape,
        color = cardColor,
        tonalElevation = if (expanded) 2.dp else 0.dp,
        shadowElevation = shadowElevation,
        border = if (expanded) BorderStroke(1.dp, palette.line.copy(alpha = 0.7f)) else null,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 26.dp, end = 12.dp, top = 12.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppIconSlot(
                        appIcon = appIcon,
                        statusColor = palette.statusContainer,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = destination,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = palette.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = (-0.2).sp,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = appName,
                                fontSize = 11.sp,
                                color = palette.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            ProtocolTag(type = protocol, bg = palette.tagBg, textColor = palette.tagText)
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        StatusLabel(text = palette.statusLabel, color = palette.status)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = time,
                                fontSize = 10.sp,
                                color = palette.secondaryText.copy(alpha = 0.92f),
                            )
                            ChevronIcon(angle = chevronAngle, tint = palette.secondaryText)
                        }
                    }
                }

                if (showDetails) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .accordionReveal(detailsProgress),
                    ) {
                        ConnectionDetailsPanel(
                            ct = ct,
                            protocol = protocol,
                            summary = summary,
                            panelColor = palette.surfaceSubtle,
                            dividerColor = palette.line,
                            textColor = palette.secondaryText,
                            hintColor = hintColor,
                        )
                    }
                }
            }

            StatusStripe(
                color = palette.status.copy(alpha = stripeAlpha),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxHeight()
                        .zIndex(1f),
            )
        }
    }
}

private fun Modifier.accordionReveal(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    return this
        .graphicsLayer { alpha = p }
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val h = (placeable.height * p).roundToInt()
            layout(placeable.width, h) {
                if (h > 0) placeable.place(0, 0)
            }
        }
}

@Composable
private fun StatusStripe(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp)
                .width(5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(
                    brush = Brush.verticalGradient(colors = listOf(color, color.copy(alpha = 0.38f))),
                ),
    )
}

@Composable
private fun AppIconSlot(
    appIcon: Drawable?,
    statusColor: Color,
) {
    val iconDrawable = appIcon

    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (iconDrawable != null) {
            Crossfade(targetState = iconDrawable, animationSpec = tween(durationMillis = 180), label = "connIcon") { drawable ->
                rememberDrawablePainter(drawable)?.let { painter ->
                    androidx.compose.foundation.Image(
                        painter = painter,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(7.dp)),
                    )
                }
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
private fun ProtocolTag(type: String, bg: Color, textColor: Color) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(bg)
                .padding(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(
            text = type,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun StatusLabel(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
private fun ChevronIcon(angle: Float, tint: Color) {
    Icon(
        painter = painterResource(R.drawable.ic_right_arrow_small),
        contentDescription = null,
        tint = tint,
        modifier =
            Modifier
                .size(10.dp)
                .rotate(angle),
    )
}

@Composable
private fun ConnectionDetailsPanel(
    ct: ConnectionTracker,
    protocol: String,
    summary: Summary,
    panelColor: Color,
    dividerColor: Color,
    textColor: Color,
    hintColor: Color,
) {
    val context = LocalContext.current
    val endpoint = buildString {
        append(ct.ipAddress)
        if (ct.port > 0) append(":${ct.port}")
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(panelColor),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

        Column(
            modifier = Modifier.padding(start = 26.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            DetailTextRow(label = "Transport", value = protocol, tint = textColor)
            DetailTextRow(label = "Country", value = countryDisplay(context, ct.flag), tint = textColor)

            if (endpoint.isNotBlank()) {
                DetailTextRow(label = "Endpoint", value = endpoint, mono = true, tint = MaterialTheme.colorScheme.secondary)
            }

            if (!ct.dnsQuery.isNullOrBlank()) {
                DetailTextRow(label = "DNS", value = ct.dnsQuery.orEmpty(), mono = true, tint = textColor)
            }

            if (ct.blockedByRule.isNotBlank()) {
                DetailTextRow(
                    label = "Rule",
                    value = ct.blockedByRule,
                    tint = if (ct.isBlocked) MaterialTheme.colorScheme.error else textColor,
                )
            }

            if (ct.proxyDetails.isNotBlank()) {
                DetailTextRow(label = "Proxy", value = ct.proxyDetails, mono = true, tint = textColor)
            }

            if (summary.duration.isNotBlank()) {
                DetailTextRow(label = "Duration", value = summary.duration, tint = textColor)
            }

            if (summary.dataUsage.isNotBlank()) {
                DetailTextRow(label = "Usage", value = summary.dataUsage, tint = textColor)
            }

            if (ct.synack > 0) {
                DetailTextRow(label = "Latency", value = "${ct.synack}ms", tint = textColor)
            }

            if (summary.delay.isNotBlank()) {
                DetailTextRow(label = "Flags", value = summary.delay, tint = hintColor)
            }

            if (ct.message.isNotBlank()) {
                DetailTextRow(
                    label = "Message",
                    value = ct.message,
                    tint = if (ct.isBlocked) MaterialTheme.colorScheme.error else textColor,
                )
            }
        }
    }
}

@Composable
private fun DetailTextRow(
    label: String,
    value: String,
    mono: Boolean = false,
    tint: Color,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.4.sp,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = tint,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun countryDisplay(context: Context, flag: String): String {
    val unknown = context.getString(R.string.network_log_app_name_unknown)
    val countryName = UIUtils.getCountryNameFromFlag(flag).trim()
    val normalizedName = countryName.takeUnless { it.isBlank() || it == "--" }
    val normalizedFlag = flag.trim().takeUnless { it.isBlank() || it == "--" }

    return when {
        normalizedName != null && normalizedFlag != null -> "$normalizedName $normalizedFlag"
        normalizedName != null -> normalizedName
        normalizedFlag != null -> normalizedFlag
        else -> unknown
    }
}

private fun protocolLabel(context: Context, port: Int, proto: Int): String {
    if (Protocol.UDP.protocolType != proto && Protocol.TCP.protocolType != proto) {
        return Protocol.getProtocolName(proto).name
    }

    val resolvedPort = KnownPorts.resolvePort(port)
    return if (port == KnownPorts.HTTPS_PORT && proto == Protocol.UDP.protocolType) {
        context.getString(R.string.connection_http3)
    } else if (resolvedPort != KnownPorts.PORT_VAL_UNKNOWN) {
        resolvedPort.uppercase(Locale.ROOT)
    } else {
        Protocol.getProtocolName(proto).name
    }
}

@Composable
private fun hintColor(ct: ConnectionTracker): Color? {
    val blocked =
        if (ct.blockedByRule == FirewallRuleset.RULE12.id) {
            ct.proxyDetails.isEmpty()
        } else {
            ct.isBlocked
        }
    val rule =
        if (ct.blockedByRule == FirewallRuleset.RULE12.id && ct.proxyDetails.isEmpty()) {
            FirewallRuleset.RULE18.id
        } else {
            ct.blockedByRule
        }
    return when {
        blocked -> {
            val isError = FirewallRuleset.isProxyError(rule)
            if (isError) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        }
        FirewallRuleset.shouldShowHint(rule) -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> null
    }
}

private data class Summary(
    val dataUsage: String,
    val duration: String,
    val delay: String,
    val showSummary: Boolean
)

private fun summaryInfo(context: Context, ct: ConnectionTracker): Summary {
    val connType = ConnectionTracker.ConnType.get(ct.connType)
    var dataUsage = ""
    var delay = ""
    var duration = ""

    if (ct.duration == 0 && ct.downloadBytes == 0L && ct.uploadBytes == 0L && ct.message.isEmpty()) {
        var hasMinSummary = false
        if (VpnController.hasCid(ct.connId, ct.uid)) {
            dataUsage = context.getString(R.string.lbl_active)
            duration = context.getString(R.string.symbol_green_circle)
            hasMinSummary = true
        }

        if (connType.isMetered()) {
            delay = context.getString(R.string.symbol_currency)
            hasMinSummary = true
        }

        if (isRpnProxy(ct.rpid)) {
            delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_sparkle))
        } else if (isConnectionProxied(ct.blockedByRule, ct.proxyDetails)) {
            delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_key))
            hasMinSummary = true
        }

        return Summary(dataUsage, duration, delay, hasMinSummary)
    }

    duration = context.getString(
        R.string.single_argument,
        getDurationInHumanReadableFormat(context, ct.duration)
    )

    val download = context.getString(
        R.string.symbol_download,
        Utilities.humanReadableByteCount(ct.downloadBytes, true)
    )
    val upload = context.getString(
        R.string.symbol_upload,
        Utilities.humanReadableByteCount(ct.uploadBytes, true)
    )
    dataUsage = context.getString(R.string.two_argument, upload, download)

    if (connType.isMetered()) {
        delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_currency))
    }
    if (isConnectionHeavier(ct)) {
        delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_heavy))
    }
    if (isConnectionSlower(ct)) {
        delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_turtle))
    }
    if (isRpnProxy(ct.rpid)) {
        delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_sparkle))
    } else if (containsRelayProxy(ct.rpid)) {
        delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_bunny))
    } else if (isConnectionProxied(ct.blockedByRule, ct.proxyDetails)) {
        delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_key))
    }
    if (isRoundTripShorter(ct.synack, ct.isBlocked)) {
        delay = context.getString(R.string.ci_desc, delay, context.getString(R.string.symbol_rocket))
    }

    val showSummary = delay.isNotEmpty() || dataUsage.isNotEmpty()
    return Summary(dataUsage, duration, delay, showSummary)
}

private fun isRoundTripShorter(rtt: Long, blocked: Boolean): Boolean {
    return rtt in 1..20 && !blocked
}

private fun containsRelayProxy(rpid: String): Boolean {
    return rpid.isNotEmpty()
}

private fun isConnectionProxied(ruleName: String?, proxyDetails: String): Boolean {
    if (ruleName == null) return false
    val rule = FirewallRuleset.getFirewallRule(ruleName) ?: return false
    val proxy = ProxyManager.isNotLocalAndRpnProxy(proxyDetails)
    val isProxyError = FirewallRuleset.isProxyError(ruleName)
    return (FirewallRuleset.isProxied(rule) && proxyDetails.isNotEmpty() && proxy) || isProxyError
}

private fun isRpnProxy(pid: String): Boolean {
    return pid.isNotEmpty() && ProxyManager.isRpnProxy(pid)
}

private fun isConnectionHeavier(ct: ConnectionTracker): Boolean {
    return ct.downloadBytes + ct.uploadBytes > MAX_BYTES
}

private fun isConnectionSlower(ct: ConnectionTracker): Boolean {
    return (ct.protocol == Protocol.UDP.protocolType && ct.duration > MAX_TIME_UDP) ||
        (ct.protocol == Protocol.TCP.protocolType && ct.duration > MAX_TIME_TCP)
}
