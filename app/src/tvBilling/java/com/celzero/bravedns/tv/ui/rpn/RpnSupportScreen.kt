/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rpn

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStateHistoryDao
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusDao
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.SettingToggleRow
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import com.celzero.bravedns.util.ProcessInfoCollector
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SupportDiagnostics
import com.celzero.bravedns.util.TraceLogExporter
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val TAG = "RpnTvSupport"

/**
 * TV-native support screen (the ten-foot counterpart of the phone
 * `CustomerSupportActivity`). Collects an issue category, a free-text
 * description and attachment preferences, then delegates the entire
 * diagnostic/zip/email pipeline to the shared [SupportDiagnostics]
 * helper — the same object the phone activity uses, so both surfaces
 * produce identical support reports.
 *
 * Ten-foot adaptations: category chips instead of Material chips,
 * `SettingToggleRow`s instead of inline switches, and the description
 * field uses the leanback IME.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnSupportScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subscriptionStatusDao = koinInject<SubscriptionStatusDao>()
    val subscriptionStateHistoryDao = koinInject<SubscriptionStateHistoryDao>()

    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var includeStatus by remember { mutableStateOf(true) }
    var includeStats by remember { mutableStateOf(true) }
    var includeProcInfo by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    // Refund/moneyback chips are time-windowed against the purchase date.
    val hero by produceState<SupportHeroUi?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val sub = runCatching { subscriptionStatusDao.getCurrentSubscription() }.getOrNull()
            val deviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
            val expiry = runCatching<Long?> { VpnController.getWinExpiryTs() }.getOrNull() ?: 0L
            val purchaseTs = sub?.purchaseTime ?: 0L
            val elapsedMs = if (purchaseTs > 0L) System.currentTimeMillis() - purchaseTs else Long.MAX_VALUE
            val dayMs = 24 * 60 * 60 * 1000L
            val windowDays = sub?.windowDays?.takeIf { it > 0 }
                ?: InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
            SupportHeroUi(
                identityLine = heroIdentityLine(
                    sub?.purchaseToken.orEmpty(),
                    sub?.accountId.orEmpty(),
                    deviceId,
                    expiry.toString(16),
                ),
                refundVisible = elapsedMs < windowDays * dayMs,
                moneybackVisible = elapsedMs < InAppBillingHandler.MONEYBACK_WINDOW_DAYS * dayMs,
            )
        }
    }

    fun send() {
        if (sending) return
        if (description.isBlank() && category == null) {
            tvToast(context, context.getString(R.string.support_error_empty_description))
            return
        }
        sending = true
        scope.launch(Dispatchers.IO) {
            try {
                val statuses: List<SubscriptionStatus> = if (includeStatus) {
                    runCatching { subscriptionStatusDao.getAllSubscriptions() }.getOrElse { emptyList() }
                } else {
                    emptyList()
                }
                val recentHistory = if (includeStatus) {
                    runCatching {
                        subscriptionStateHistoryDao.getRecentHistory(SupportDiagnostics.MAX_HISTORY_ENTRIES)
                    }.getOrElse { emptyList() }
                } else {
                    emptyList()
                }
                val entitlementJson = if (includeStats) {
                    runCatching {
                        val details = RpnProxyManager.getEntitlementDetails()
                        if (details == null) {
                            "unavailable"
                        } else {
                            buildString {
                                append("cid: ").append(details.cid()?.take(12)).append('\n')
                                append("did: ").append(details.did().take(4)).append('\n')
                                append("expiry: ").append(
                                    Utilities.convertLongToTime(details.expiry(), com.celzero.bravedns.util.Constants.TIME_FORMAT_4)
                                ).append('\n')
                                append("providerId: ").append(details.providerID()).append('\n')
                                append("test: ").append(details.test()).append('\n')
                                append("allowRestore: ").append(details.allowRestore()).append("\n\n")
                            }
                        }
                    }.getOrNull()
                } else {
                    null
                }

                val diagContent = SupportDiagnostics.buildDiagnosticText(
                    ctx = context,
                    description = description.trim(),
                    category = category,
                    statuses = statuses,
                    history = recentHistory,
                    entitlementJson = entitlementJson,
                )
                val diagFile = SupportDiagnostics.writeDiagFile(context, diagContent)
                val procInfoBytes = if (includeProcInfo) {
                    runCatching {
                        ProcessInfoCollector.collect(context).toByteArray(Charsets.UTF_8)
                    }.getOrNull()
                } else {
                    null
                }
                val traceLogs = TraceLogExporter.dumpTraceLogsFile(context)
                val supportZip = SupportDiagnostics.buildSupportZip(
                    context,
                    diagFile,
                    SupportDiagnostics.prepareWirelogAttachment(
                        context,
                        (diagFile?.length() ?: 0L) + (procInfoBytes?.size?.toLong() ?: 0L),
                    ),
                    procInfoBytes,
                    null,
                    traceLogs = traceLogs,
                )
                traceLogs?.let {
                    if (it.exists() && !it.delete()) {
                        Logger.w(LOG_TAG_UI, "$TAG failed to delete temporary trace log file")
                    }
                }
                // the email pipeline cannot carry an oversized attachment
                if (supportZip != null && supportZip.length() > BugReportZipper.MAX_ZIP_SIZE_BYTES) {
                    Logger.w(LOG_TAG_UI, "$TAG support zip exceeds email size limit, rejecting send")
                    supportZip.delete()
                    withContext(Dispatchers.Main) {
                        sending = false
                        tvToast(context, context.getString(R.string.support_error_collect))
                    }
                    return@launch
                }
                val emailBody = SupportDiagnostics.buildEmailBody(context, description.trim(), category)

                withContext(Dispatchers.Main) {
                    sending = false
                    SupportDiagnostics.launchEmailIntent(context, emailBody, supportZip, category)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sending = false
                    tvToast(context, context.getString(R.string.support_error_collect))
                }
            }
        }
    }

    TvScreenScaffold(
        title = stringResource(R.string.contact_support_title),
        subtitle = "Describe the issue and attach diagnostics — everything is bundled into an email draft.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            hero?.let { h ->
                if (h.identityLine.isNotBlank()) {
                    Text(
                        text = h.identityLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingSectionHeader("Issue category")
            val catPayment = stringResource(R.string.support_category_payment)
            val catActivation = stringResource(R.string.support_category_activation)
            val catConnectivity = stringResource(R.string.support_category_connectivity)
            val catRefund = stringResource(R.string.support_category_refund)
            val catMoneyback = stringResource(R.string.support_category_moneyback)
            val catOther = stringResource(R.string.category_name_others)
            val categories = buildList {
                add(catPayment)
                add(catActivation)
                add(catConnectivity)
                if (hero?.refundVisible == true) add(catRefund)
                if (hero?.moneybackVisible == true) add(catMoneyback)
                add(catOther)
            }
            categories.chunked(3).forEach { rowCats ->
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.RowGap)) {
                    rowCats.forEach { cat ->
                        CategoryChip(
                            label = cat,
                            selected = category == cat,
                            onClick = { category = if (category == cat) null else cat },
                        )
                    }
                }
            }

            SettingSectionHeader("Description")
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                placeholder = { Text("What went wrong?") },
                colors = OutlinedTextFieldDefaults.colors(),
                shape = TvShapes.Small,
            )

            SettingSectionHeader("Attach diagnostics")
            SettingToggleRow(
                title = "Subscription status & state history",
                description = "Last ${SupportDiagnostics.MAX_HISTORY_ENTRIES} entitlement transitions (ids redacted).",
                checked = includeStatus,
                onCheckedChange = { includeStatus = it },
            )
            SettingToggleRow(
                title = "Proxy statistics",
                description = "Entitlement summary: cid, device, expiry, provider id.",
                checked = includeStats,
                onCheckedChange = { includeStats = it },
            )
            SettingToggleRow(
                title = "Process info",
                description = "App and Go-engine stack traces (large).",
                checked = includeProcInfo,
                onCheckedChange = { includeProcInfo = it },
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { send() },
                    enabled = !sending,
                    shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
                    ),
                    modifier = Modifier
                        .width(360.dp)
                        .height(64.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.tv.material3.Icon(
                            imageVector = Icons.Filled.SupportAgent,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Text(
                            text = if (sending) stringResource(R.string.support_btn_sending)
                            else stringResource(R.string.about_bug_report_dialog_positive_btn),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class SupportHeroUi(
    val identityLine: String,
    val refundVisible: Boolean,
    val moneybackVisible: Boolean,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(
                horizontal = TvSpacing.ContentHorizontal,
                vertical = 10.dp,
            ),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}

private fun tvToast(ctx: Context, msg: String) {
    Utilities.showToastUiCentered(ctx, msg, Toast.LENGTH_LONG)
}
