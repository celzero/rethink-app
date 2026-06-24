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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.firestack.backend.Backend
import io.github.aakira.napier.Napier
import kotlin.math.roundToInt

private data class DnsRowPalette(
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
private fun dnsRowPalette(log: DnsLog): DnsRowPalette {
    val scheme = MaterialTheme.colorScheme
    val allowedGreen = Color(0xFF2FB36B)
    val statusColor = when {
        log.isBlocked -> scheme.error
        determineMaybeBlocked(log) -> scheme.error.copy(alpha = 0.9f)
        else -> allowedGreen
    }

    val statusContainer = when {
        log.isBlocked -> scheme.errorContainer.copy(alpha = 0.55f)
        determineMaybeBlocked(log) -> scheme.errorContainer.copy(alpha = 0.48f)
        else -> allowedGreen.copy(alpha = 0.18f)
    }

    return DnsRowPalette(
        status = statusColor,
        statusContainer = statusContainer,
        statusLabel =
            if (log.isBlocked) {
                stringResource(R.string.lbl_blocked)
            } else {
                stringResource(R.string.lbl_allowed)
            },
        surfaceCollapsed = scheme.surfaceContainerLow,
        surfaceExpanded = scheme.surfaceContainer,
        surfaceSubtle = scheme.surfaceContainerHighest.copy(alpha = 0.32f),
        line = scheme.outlineVariant.copy(alpha = 0.45f),
        primaryText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        tagBg = scheme.surfaceContainerHighest.copy(alpha = 0.6f),
        tagText = scheme.onSurfaceVariant,
    )
}

@Composable
fun DnsLogRow(
    log: DnsLog,
    loadFavIcon: Boolean,
    isRethinkDns: Boolean,
    onShowBlocklist: (DnsLog) -> Unit,
    index: Int = 0,
    itemCount: Int = 1,
) {
    val context = LocalContext.current
    val palette = dnsRowPalette(log)
    val dnsType = dnsTypeName(context, log, isRethinkDns)
    val hint = unicodeHint(context, log, isRethinkDns)
    val appLabel = log.appName.ifEmpty {
        stringResource(R.string.network_log_app_name_unknown)
    }

    var appIcon by remember(log.packageName) { mutableStateOf<Drawable?>(null) }
    var favIcon by remember(log.queryStr) { mutableStateOf<Drawable?>(null) }
    var showFav by remember(log.queryStr, loadFavIcon) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val rowScale by animateFloatAsState(
        targetValue = if (isPressed) 0.988f else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "dnsRowScale"
    )

    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "dnsChevron"
    )

    val baseCardColor = if (expanded) palette.surfaceExpanded else palette.surfaceCollapsed
    val pressedCardColor = lerp(baseCardColor, MaterialTheme.colorScheme.primaryContainer, 0.2f)
    val cardColor by animateColorAsState(
        targetValue = if (isPressed) pressedCardColor else baseCardColor,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "dnsCardColor"
    )

    val shadowElevation by animateDpAsState(
        targetValue =
            when {
                isPressed -> 3.dp
                expanded -> 7.dp
                else -> 1.dp
            },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "dnsCardShadow"
    )

    val stripeAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0.9f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "dnsStripeAlpha"
    )

    val detailsProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "dnsDetailsProgress",
        finishedListener = { value ->
            if (value == 0f) showDetails = false
        }
    )

    LaunchedEffect(log.packageName) {
        appIcon =
            if (log.packageName.isEmpty() || log.packageName == Constants.EMPTY_PACKAGE_NAME) {
                null
            } else {
                getIcon(context, log.packageName)
            }
    }

    LaunchedEffect(log.queryStr, loadFavIcon, log.groundedQuery()) {
        showFav = false
        favIcon = null
    }

    LaunchedEffect(log.queryStr, loadFavIcon, log.groundedQuery()) {
        if (!loadFavIcon || log.groundedQuery()) return@LaunchedEffect
        displayFavIcon(
            context = context,
            log = log,
            loadFavIcon = true,
            onShowFlag = { showFav = false; favIcon = null },
            onShowFav = { d -> showFav = true; favIcon = d },
        )
    }

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
        border =
            if (expanded) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
            } else {
                null
            },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 26.dp, end = 12.dp, top = 12.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppIconSlot(
                        showFav = showFav,
                        favIcon = favIcon,
                        appIcon = appIcon,
                        statusColor = palette.statusContainer,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = log.queryStr,
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
                                text = appLabel,
                                fontSize = 11.sp,
                                color = palette.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            DnsTypeTag(type = dnsType, bg = palette.tagBg, textColor = palette.tagText)
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
                                text = log.wallTime(),
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
                        DetailPanel(
                            log = log,
                            dnsType = dnsType,
                            hint = hint,
                            statusColor = palette.status,
                            context = context,
                            panelColor = palette.surfaceSubtle,
                            dividerColor = palette.line,
                            textColor = palette.secondaryText,
                            onShowBlocklist = onShowBlocklist,
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
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    color,
                                    color.copy(alpha = 0.38f),
                                ),
                        ),
                ),
    )
}

@Composable
private fun AppIconSlot(
    showFav: Boolean,
    favIcon: Drawable?,
    appIcon: Drawable?,
    statusColor: Color,
) {
    val iconDrawable = if (showFav && favIcon != null) favIcon else appIcon

    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (iconDrawable != null) {
            Crossfade(targetState = iconDrawable, animationSpec = tween(durationMillis = 180), label = "dnsIcon") { drawable ->
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
private fun DnsTypeTag(type: String, bg: Color, textColor: Color) {
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
private fun DetailPanel(
    log: DnsLog,
    dnsType: String,
    hint: String,
    statusColor: Color,
    context: Context,
    panelColor: Color,
    dividerColor: Color,
    textColor: Color,
    onShowBlocklist: (DnsLog) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(panelColor),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        HorizontalDivider(
            color = dividerColor,
            thickness = 0.5.dp,
        )

        Column(
            modifier = Modifier.padding(start = 26.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            DetailLatencyRow(latency = log.latency)

            Spacer(Modifier.height(8.dp))

            DetailTextRow(
                label = "Transport",
                value = dnsType,
                tint = textColor,
            )

            val unknownLabel = context.getString(R.string.network_log_app_name_unknown)
            val countryName = UIUtils.getCountryNameFromFlag(log.flag).trim()
            val normalizedCountryName = countryName.takeUnless { it.isBlank() || it == "--" }
            val normalizedFlag = log.flag.trim().takeUnless { it.isBlank() || it == "--" }
            val countryDisplay =
                when {
                    normalizedCountryName != null && normalizedFlag != null -> "$normalizedCountryName $normalizedFlag"
                    normalizedCountryName != null -> normalizedCountryName
                    normalizedFlag != null -> normalizedFlag
                    else -> unknownLabel
                }
            DetailTextRow(
                label = "Country",
                value = countryDisplay,
                tint = textColor,
            )

            if (log.responseIps.isNotBlank()) {
                val ips = log.responseIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                DetailTextRow(
                    label = context.getString(R.string.response_ip_label).ifEmpty { "IP" },
                    value = ips.joinToString(" · "),
                    mono = true,
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }

            if (log.serverIP.isNotBlank()) {
                DetailTextRow(
                    label = context.getString(R.string.resolver_label).ifEmpty { "Resolver" },
                    value = log.serverIP,
                    mono = true,
                    tint = textColor,
                )
            }

            if (log.dnssecOk || log.dnssecValid) {
                val dnssecOkay = log.dnssecOk && log.dnssecValid
                DetailTextRow(
                    label = "DNSSEC",
                    value = if (dnssecOkay) "✓  Valid" else "⚠  Unverified",
                    tint = if (dnssecOkay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
            }

            if (hint.isNotEmpty()) {
                DetailTextRow(label = "Flags", value = hint, tint = textColor)
            }

            if (log.blockLists.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                BlocklistRow(log = log, statusColor = statusColor, onShowBlocklist = onShowBlocklist)
            }
        }
    }
}

@Composable
private fun DetailLatencyRow(latency: Long) {
    val scheme = MaterialTheme.colorScheme
    val successGreen = Color(0xFF2FB36B)
    val (barColor, label) =
        when {
            latency in 1..10 -> successGreen to "${latency}ms · fast"
            latency in 11..50 -> scheme.tertiary to "${latency}ms · ok"
            latency > 50 -> scheme.error to "${latency}ms · slow"
            else -> scheme.onSurfaceVariant to "${latency}ms"
        }
    val fraction = (latency.toFloat() / 100f).coerceIn(0.04f, 1f)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "Latency",
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant,
                letterSpacing = 0.4.sp,
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = barColor,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(barColor.copy(alpha = 0.7f), barColor),
                            ),
                        ),
            )
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
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = tint,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BlocklistRow(log: DnsLog, statusColor: Color, onShowBlocklist: (DnsLog) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val count = log.blockLists.split(",").filter { it.isNotEmpty() }.size
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(scheme.errorContainer.copy(alpha = 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = scheme.error.copy(alpha = 0.16f)),
                    onClick = { onShowBlocklist(log) },
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(statusColor),
            )
            Text(
                text = "$count blocklist${if (count != 1) "s" else ""} matched",
                fontSize = 11.sp,
                color = scheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_right_arrow_small),
            contentDescription = null,
            tint = scheme.error.copy(alpha = 0.65f),
            modifier = Modifier.size(10.dp),
        )
    }
}

private fun determineMaybeBlocked(log: DnsLog): Boolean =
    log.upstreamBlock || log.blockLists.isNotEmpty()

private fun unicodeHint(context: Context, log: DnsLog, isRethinkDns: Boolean): String {
    var hint = ""
    if (isRoundTripShorter(log.latency, log.isBlocked)) {
        hint = context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_rocket))
    }
    if (containsRelayProxy(log.relayIP)) {
        hint = context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_bunny))
    } else if (isConnectionProxied(log.proxyId)) {
        hint = context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_key))
    }
    if (isRethinkUsed(log, isRethinkDns)) {
        hint = context.getString(R.string.ci_desc, hint, getRethinkUnicode(context, log))
    } else if (isGoosOrSystemUsed(log)) {
        hint = context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_duck))
    } else if (isDefaultResolverUsed(log)) {
        hint = context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_diamond))
    } else if (containsMultipleIPs(log)) {
        hint = context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_heavy))
    }
    if (dnssecIndicatorRequired(log)) {
        hint = if (dnssecOk(log)) {
            context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_lock))
        } else {
            context.getString(R.string.ci_desc, hint, context.getString(R.string.symbol_unlock))
        }
    }
    return hint
}

private fun dnsTypeName(context: Context, log: DnsLog, isRethinkDns: Boolean): String =
    when (Transaction.TransportType.fromOrdinal(log.dnsType)) {
        Transaction.TransportType.DOH ->
            if (isRethinkDns && isRethinkUsed(log, isRethinkDns)) {
                context.getString(R.string.lbl_rdns)
            } else {
                context.getString(R.string.other_dns_list_tab1)
            }
        Transaction.TransportType.DNS_CRYPT -> context.getString(R.string.lbl_dc_abbr)
        Transaction.TransportType.DNS_PROXY -> context.getString(R.string.lbl_dp)
        Transaction.TransportType.DOT -> context.getString(R.string.lbl_dot)
        Transaction.TransportType.ODOH -> context.getString(R.string.lbl_odoh)
    }

private fun dnssecIndicatorRequired(log: DnsLog) =
    log.status == Transaction.Status.COMPLETE.name && (log.dnssecOk || log.dnssecValid)

private fun dnssecOk(log: DnsLog) = log.dnssecOk && log.dnssecValid

private fun isRoundTripShorter(rtt: Long, blocked: Boolean) = rtt in 1..10 && !blocked

private fun containsRelayProxy(rpid: String) = rpid.isNotEmpty()

private fun isConnectionProxied(proxy: String?): Boolean {
    if (proxy.isNullOrEmpty()) return false
    return ProxyManager.isNotLocalAndRpnProxy(proxy)
}

private fun containsMultipleIPs(log: DnsLog) = log.responseIps.split(",").size > 1

private fun isRethinkUsed(log: DnsLog, isRethinkDns: Boolean): Boolean {
    if (log.status != Transaction.Status.COMPLETE.name) return false
    return isRethinkDns &&
        (log.resolverId.contains(Backend.Preferred) || log.resolverId.contains(Backend.BlockFree))
}

private fun isGoosOrSystemUsed(log: DnsLog): Boolean {
    if (log.status != Transaction.Status.COMPLETE.name) return false
    return log.resolverId.contains(Backend.Goos) || log.resolverId.contains(Backend.System)
}

private fun isDefaultResolverUsed(log: DnsLog): Boolean {
    if (log.status != Transaction.Status.COMPLETE.name) return false
    return log.resolverId.contains(Backend.Default) || log.resolverId.contains(Backend.Bootstrap)
}

private fun getRethinkUnicode(context: Context, log: DnsLog): String {
    if (log.relayIP.endsWith(Backend.RPN) || log.relayIP == Backend.Auto) {
        return context.getString(R.string.symbol_sparkle)
    }
    return if (log.serverIP.contains(MAX_ENDPOINT)) {
        context.getString(R.string.symbol_max)
    } else {
        context.getString(R.string.symbol_sky)
    }
}

private fun displayFavIcon(
    context: Context,
    log: DnsLog,
    loadFavIcon: Boolean,
    onShowFlag: () -> Unit,
    onShowFav: (Drawable) -> Unit,
) {
    if (!loadFavIcon || log.groundedQuery()) {
        onShowFlag()
        return
    }
    if (FavIconDownloader.isUrlAvailableInFailedCache(log.queryStr.dropLast(1)) != null) {
        onShowFlag()
        return
    }
    displayNextDnsFavIcon(context, log, onShowFlag, onShowFav)
}

private fun displayNextDnsFavIcon(
    context: Context,
    log: DnsLog,
    onShowFlag: () -> Unit,
    onShowFav: (Drawable) -> Unit,
) {
    val trim = log.queryStr.dropLastWhile { it == '.' }
    val nextDnsUrl = FavIconDownloader.constructFavIcoUrlNextDns(trim)
    val duckduckGoUrl = FavIconDownloader.constructFavUrlDuckDuckGo(trim)
    val duckDomainUrl = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(trim)

    try {
        val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
        Glide.with(context.applicationContext)
            .load(nextDnsUrl)
            .onlyRetrieveFromCache(true)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .transition(withCrossFade(factory))
            .into(
                object : CustomTarget<Drawable>() {
                    override fun onLoadFailed(e: Drawable?) =
                        displayDuckduckgoFavIcon(
                            context,
                            duckduckGoUrl,
                            duckDomainUrl,
                            onShowFlag,
                            onShowFav,
                        )

                    override fun onResourceReady(r: Drawable, t: Transition<in Drawable>?) = onShowFav(r)

                    override fun onLoadCleared(p: Drawable?) = onShowFlag()
                },
            )
    } catch (_: Exception) {
        Napier.d("err loading icon, load flag instead")
        displayDuckduckgoFavIcon(context, duckduckGoUrl, duckDomainUrl, onShowFlag, onShowFav)
    }
}

private fun displayDuckduckgoFavIcon(
    context: Context,
    url: String,
    subDomainURL: String,
    onShowFlag: () -> Unit,
    onShowFav: (Drawable) -> Unit,
) {
    try {
        val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
        Glide.with(context.applicationContext)
            .load(url)
            .onlyRetrieveFromCache(true)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .error(
                Glide.with(context.applicationContext).load(subDomainURL).onlyRetrieveFromCache(true),
            )
            .transition(withCrossFade(factory))
            .into(
                object : CustomTarget<Drawable>() {
                    override fun onLoadFailed(e: Drawable?) = onShowFlag()

                    override fun onResourceReady(r: Drawable, t: Transition<in Drawable>?) = onShowFav(r)

                    override fun onLoadCleared(p: Drawable?) = onShowFlag()
                },
            )
    } catch (_: Exception) {
        Napier.d("err loading icon, load flag instead")
        onShowFlag()
    }
}
