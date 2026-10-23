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

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import com.celzero.bravedns.R
import androidx.compose.ui.res.stringResource
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/** Selectable pill used for the support category row. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
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
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}

/**
 * RPN support — simplified TV counterpart of the phone's
 * `CustomerSupportActivity`: category + description, optional
 * subscription/proxy-stat summaries folded into the body, and an
 * optional RPN-log zip attached through the app FileProvider.
 *
 * Skipped vs. the phone: proc-info attachment and the moneyback /
 * refund chips (refund lives on the Manage purchase screen).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnSupportScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rpnLogRepo: com.celzero.bravedns.database.RpnLogRepository = koinInject()
    val categories = listOf("Payment", "Activation", "Connectivity", "Refund", "Other")

    var category by remember { mutableStateOf(categories.first()) }
    var description by remember { mutableStateOf("") }
    var attachStatus by remember { mutableStateOf(true) }
    var attachStats by remember { mutableStateOf(false) }
    var attachLogs by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val supportEmail = stringResource(R.string.about_mail_to)

    val summary by produceState("" to "") {
        value = withContext(Dispatchers.IO) {
            val ident = runCatching { VpnController.getWinIdentifier() }.getOrNull().orEmpty()
            val expiry = runCatching { VpnController.getWinExpiryTs() }.getOrNull()
            ident to (expiry?.let { formatRelative(it) }.orEmpty())
        }
    }

    fun send() {
        if (working) return
        working = true
        scope.launch(Dispatchers.IO) {
            val body = buildString {
                appendLine("Category: $category")
                appendLine()
                appendLine(description.ifBlank { "(no description provided)" })
                appendLine()
                if (attachStatus) {
                    appendLine("--- subscription ---")
                    appendLine("state: ${runCatching { RpnProxyManager.getSubscriptionState().name }.getOrDefault("unknown")}")
                    appendLine("identifier: ${summary.first.take(12)}")
                    appendLine("expiry: ${summary.second}")
                    appendLine()
                }
                if (attachStats) {
                    appendLine("--- proxy stats ---")
                    val stats = runCatching {
                        VpnController.getProxyStats(Backend.RpnWin)
                    }.getOrNull()
                    appendLine("rx: ${stats?.rx ?: 0} tx: ${stats?.tx ?: 0}")
                    appendLine()
                }
                appendLine("--- device ---")
                appendLine("android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }

            var zipUri: android.net.Uri? = null
            if (attachLogs) {
                runCatching {
                    val dir = File(context.cacheDir, "support").apply { mkdirs() }
                    val zip = File(dir, BugReportZipper.getZipFileName(context.filesDir))
                    BugReportZipper.dumpRpnLogs(rpnLogRepo, zip)
                    if (zip.exists() && zip.length() > 0) {
                        zipUri = FileProvider.getUriForFile(
                            context,
                            BugReportZipper.FILE_PROVIDER_NAME,
                            zip,
                        )
                    }
                }
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(
                    Intent.EXTRA_EMAIL,
                    arrayOf(supportEmail),
                )
                putExtra(Intent.EXTRA_SUBJECT, "RPN Support Request: [$category]")
                putExtra(Intent.EXTRA_TEXT, body)
                zipUri?.let { uri ->
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            withContext(Dispatchers.Main) {
                working = false
                runCatching {
                    context.startActivity(Intent.createChooser(intent, "Send email"))
                }
            }
        }
    }

    TvScreenScaffold(
        title = "Report an issue",
        subtitle = "Contact support about your Rethink Plus subscription.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingSectionHeader("Category")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { cat ->
                    Chip(
                        label = cat,
                        selected = category == cat,
                        onClick = { category = cat },
                    )
                }
            }

            SettingSectionHeader("Description")
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Describe the issue") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            )

            SettingSectionHeader("Attach")
            FocusableActionCard(
                title = "Subscription status",
                subtitle = if (attachStatus) {
                    "State ${summary.first.take(12)} · expiry ${summary.second.ifBlank { "—" }}"
                } else {
                    "Include your subscription state and identifiers in the email."
                },
                valueText = if (attachStatus) "On" else "Off",
                onClick = { attachStatus = !attachStatus },
            )
            FocusableActionCard(
                title = "Proxy stats",
                subtitle = "Include current RPN tunnel counters.",
                valueText = if (attachStats) "On" else "Off",
                onClick = { attachStats = !attachStats },
            )
            FocusableActionCard(
                title = "RPN logs",
                subtitle = "Attach a zip of on-device RPN logs for diagnosis.",
                valueText = if (attachLogs) "On" else "Off",
                onClick = { attachLogs = !attachLogs },
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { send() }, enabled = !working) {
                    Text(if (working) "Preparing…" else "Send email")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
