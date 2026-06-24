/*
Copyright 2023 RethinkDNS and its authors

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

package com.celzero.bravedns.adapter


import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.getDurationInHumanReadableFormat
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale


private const val MAX_BYTES = 500000 // 500 KB
private const val MAX_TIME_TCP = 135 // seconds
private const val MAX_TIME_UDP = 135 // seconds

const val DNS_IP_TEMPLATE_V4 = "10.111.222.3"
const val DNS_IP_TEMPLATE_V6 = "fd66:f83a:c650::3"

@Composable
fun RethinkLogRow(
    log: RethinkLog,
    onShowConnTracker: (ConnectionTracker) -> Unit
) {
    val context = LocalContext.current
    val time = Utilities.convertLongToTime(log.timeStamp, TIME_FORMAT_1)
    val protocolLabel = protocolLabel(context, log.port, log.protocol)
    val indicatorColor = hintColor(context, log)
    val summary = summaryInfo(context, log)
    val flag = log.flag
    val ipAddress =
        if (log.ipAddress == DNS_IP_TEMPLATE_V4 || log.ipAddress == DNS_IP_TEMPLATE_V6) {
            stringResource(R.string.dns_mode_info_title)
        } else {
            log.ipAddress
        }

    var appName by remember(log.uid, log.appName) { mutableStateOf(log.appName) }
    var appIcon by remember(log.uid) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(log.uid, log.appName) {
        val apps =
            withContext(Dispatchers.IO) { FirewallManager.getPackageNamesByUid(log.uid) }
        if (apps.isEmpty()) {
            appIcon = Utilities.getDefaultIcon(context)
            appName = log.appName
            return@LaunchedEffect
        }

        val count = apps.count()
        appName =
            if (count > 1) {
                context.getString(
                    R.string.ctbs_app_other_apps,
                    log.appName,
                    (count - 1).toString()
                )
            } else {
                log.appName
            }
        appIcon = Utilities.getIcon(context, apps[0], "")
    }

    val dnsQuery = log.dnsQuery

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onShowConnTracker(toConnectionTracker(log)) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(indicatorColor ?: Color.Transparent)
            )
            val iconDrawable = appIcon ?: Utilities.getDefaultIcon(context)
            val iconPainter = rememberDrawablePainter(iconDrawable)
            iconPainter?.let { painter ->
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = protocolLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Text(
                        text = flag,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = ipAddress,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!dnsQuery.isNullOrEmpty()) {
                    Text(
                        text = dnsQuery,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = time, style = MaterialTheme.typography.bodySmall)
            Text(
                text = summary.duration,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = summary.delay,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (summary.showSummary) {
            Text(
                text = summary.dataUsage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth())
    }
}

private fun protocolLabel(context: Context, port: Int, proto: Int): String {
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
private fun hintColor(context: Context, log: RethinkLog): Color? {
    val blocked =
        if (log.blockedByRule == FirewallRuleset.RULE12.id) {
            log.proxyDetails.isEmpty()
        } else {
            log.isBlocked
        }
    val rule =
        if (log.blockedByRule == FirewallRuleset.RULE12.id && log.proxyDetails.isEmpty()) {
            FirewallRuleset.RULE18.id
        } else {
            log.blockedByRule
        }
    return when {
        blocked -> {
            val isError = FirewallRuleset.isProxyError(rule)
            if (isError) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        }
        FirewallRuleset.shouldShowHint(rule) -> {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> null
    }
}

data class LogSummary(val dataUsage: String, val duration: String, val delay: String, val showSummary: Boolean)

private fun summaryInfo(context: Context, log: RethinkLog): LogSummary {
    val connType = ConnectionTracker.ConnType.get(log.connType)
    var dataUsage = ""
    var delay = ""
    var duration = ""
    var showSummary = false

    if (log.duration == 0 && log.downloadBytes == 0L && log.uploadBytes == 0L && log.message.isEmpty()) {
        var hasMinSummary = false
        if (VpnController.hasCid(log.connId, log.uid)) {
            dataUsage = context.getString(R.string.lbl_active)
            duration = context.getString(R.string.symbol_green_circle)
            hasMinSummary = true
        }

        if (connType.isMetered()) {
            delay = context.getString(R.string.symbol_currency)
            hasMinSummary = true
        }

        if (isRpnProxy(log.rpid)) {
            delay =
                context.getString(
                    R.string.ci_desc,
                    delay,
                    context.getString(R.string.symbol_sparkle)
                )
        } else if (isConnectionProxied(log.blockedByRule, log.proxyDetails)) {
            delay =
                context.getString(
                    R.string.ci_desc,
                    delay,
                    context.getString(R.string.symbol_key)
                )
            hasMinSummary = true
        }
        showSummary = hasMinSummary
        return LogSummary(dataUsage, duration, delay, showSummary)
    }

    showSummary = true
    duration = context.getString(R.string.single_argument, getDurationInHumanReadableFormat(context, log.duration))
    val download =
        context.getString(
            R.string.symbol_download,
            Utilities.humanReadableByteCount(log.downloadBytes, true)
        )
    val upload =
        context.getString(
            R.string.symbol_upload,
            Utilities.humanReadableByteCount(log.uploadBytes, true)
        )
    dataUsage = context.getString(R.string.two_argument, upload, download)

    if (connType.isMetered()) {
        delay =
            context.getString(
                R.string.ci_desc,
                delay,
                context.getString(R.string.symbol_currency)
            )
    }
    if (isConnectionHeavier(log)) {
        delay =
            context.getString(
                R.string.ci_desc,
                delay,
                context.getString(R.string.symbol_heavy)
            )
    }
    if (isConnectionSlower(log)) {
        delay =
            context.getString(
                R.string.ci_desc,
                delay,
                context.getString(R.string.symbol_turtle)
            )
    }
    if (isRpnProxy(log.rpid)) {
        delay =
            context.getString(
                R.string.ci_desc,
                delay,
                context.getString(R.string.symbol_sparkle)
            )
    } else if (containsRelayProxy(log.rpid)) {
        delay =
            context.getString(
                R.string.ci_desc,
                delay,
                context.getString(R.string.symbol_bunny)
            )
    } else if (isConnectionProxied(log.blockedByRule, log.proxyDetails)) {
        delay =
            context.getString(
                R.string.ci_desc,
                delay,
                context.getString(R.string.symbol_key)
            )
    }
    if (isRoundTripShorter(log.synack, log.isBlocked)) {
        delay =
            context.getString(
                R.string.ci_desc,
                delay,
                context.getString(R.string.symbol_rocket)
            )
    }
    showSummary = delay.isNotEmpty() || dataUsage.isNotEmpty()
    return LogSummary(dataUsage, duration, delay, showSummary)
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

private fun isConnectionHeavier(log: RethinkLog): Boolean {
    return log.downloadBytes + log.uploadBytes > MAX_BYTES
}

private fun isConnectionSlower(log: RethinkLog): Boolean {
    return (log.protocol == Protocol.UDP.protocolType && log.duration > MAX_TIME_UDP) ||
        (log.protocol == Protocol.TCP.protocolType && log.duration > MAX_TIME_TCP)
}

private fun toConnectionTracker(log: RethinkLog): ConnectionTracker {
    val tracker = ConnectionTracker()
    tracker.appName = log.appName
    tracker.uid = log.uid
    tracker.usrId = log.usrId
    tracker.ipAddress = log.ipAddress
    tracker.port = log.port
    tracker.protocol = log.protocol
    tracker.isBlocked = log.isBlocked
    tracker.blockedByRule = log.blockedByRule
    tracker.blocklists = log.blocklists
    tracker.proxyDetails = log.proxyDetails
    tracker.flag = log.flag
    tracker.dnsQuery = log.dnsQuery
    tracker.timeStamp = log.timeStamp
    tracker.connId = log.connId
    tracker.downloadBytes = log.downloadBytes
    tracker.uploadBytes = log.uploadBytes
    tracker.duration = log.duration
    tracker.synack = log.synack
    tracker.rpid = log.rpid
    tracker.message = log.message
    tracker.connType = log.connType
    return tracker
}
