/*
Copyright 2026 RethinkDNS and its authors

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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkMultiActionDialog
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.UIUtils.getDnsStatusStringRes
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DnsInfoDialogModel(
    val title: String,
    val message: String,
    val copyValue: String? = null,
    val copyToastRes: Int = R.string.info_dialog_url_copy_toast_msg,
    val confirmTextRes: Int = R.string.dns_info_positive,
    val copyTextRes: Int = R.string.dns_info_neutral
)

internal data class DnsDeleteDialogModel(
    val id: Int,
    val titleRes: Int,
    val messageRes: Int,
    val successRes: Int
)

@Composable
internal fun DnsInfoDialog(
    model: DnsInfoDialogModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    RethinkMultiActionDialog(
        onDismissRequest = onDismiss,
        title = model.title,
        message = model.message,
        primaryText = context.getString(model.confirmTextRes),
        onPrimary = onDismiss,
        secondaryText =
            model.copyValue?.takeIf { it.isNotEmpty() }?.let {
                context.getString(model.copyTextRes)
            },
        onSecondary = {
            val copyValue = model.copyValue
            if (copyValue.isNullOrEmpty()) return@RethinkMultiActionDialog
            clipboardCopy(
                context,
                copyValue,
                context.getString(R.string.copy_clipboard_label)
            )
            Utilities.showToastUiCentered(
                context,
                context.getString(model.copyToastRes),
                Toast.LENGTH_SHORT
            )
        }
    )
}

@Composable
internal fun DnsDeleteDialog(
    model: DnsDeleteDialogModel,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val context = LocalContext.current
    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = context.getString(model.titleRes),
        message = context.getString(model.messageRes),
        confirmText = context.getString(R.string.lbl_delete),
        dismissText = context.getString(R.string.lbl_cancel),
        isConfirmDestructive = true,
        onConfirm = { onConfirm(model.id) },
        onDismiss = onDismiss
    )
}

@Composable
internal fun rememberDnsStatusExplanation(
    key: Any,
    isSelected: Boolean,
    smartDnsEnabled: Boolean,
    tag: String,
    pollIntervalMs: Long = 1000L,
    requireTunnel: Boolean = true,
    selectedFallbackText: ((Context) -> String)? = {
        it.getString(R.string.rt_filter_parent_selected)
    },
    statusTextMapper: (Context, Int) -> String = { context, statusRes ->
        context.getString(statusRes).replaceFirstChar(Char::titlecase)
    }
): String {
    val context = LocalContext.current
    var explanation by remember(key) { mutableStateOf("") }

    LaunchedEffect(key, isSelected, smartDnsEnabled) {
        if (isSelected && !smartDnsEnabled && (!requireTunnel || VpnController.hasTunnel())) {
            while (isActive) {
                val status =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val state = VpnController.getDnsStatus(Backend.Preferred)
                            getDnsStatusStringRes(state)
                        }
                    }.getOrElse {
                        Napier.e("$tag failed to read dns status", it)
                        R.string.rt_filter_parent_selected
                    }
                explanation = statusTextMapper(context, status)
                delay(pollIntervalMs)
            }
        } else if (isSelected && selectedFallbackText != null) {
            explanation = selectedFallbackText(context)
        } else {
            explanation = ""
        }
    }

    return explanation
}

internal fun resolveDnsDescriptionText(context: Context, message: String?): String {
    if (message.isNullOrEmpty()) return ""

    return try {
        if (message.contains("R.string.")) {
            val key = message.substringAfter("R.string.")
            val resId = context.resources.getIdentifier(key, "string", context.packageName)
            if (resId == 0) message else context.getString(resId)
        } else {
            message
        }
    } catch (_: Exception) {
        ""
    }
}

internal fun launchDnsEndpointSelectionUpdate(
    scope: CoroutineScope,
    context: Context,
    tag: String,
    apply: suspend () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        runCatching { apply() }.onFailure {
            Napier.e("$tag failed to update endpoint", it)
            withContext(Dispatchers.Main) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.status_failing),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }
}

internal fun launchDnsEndpointDelete(
    scope: CoroutineScope,
    context: Context,
    successRes: Int,
    apply: suspend () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        runCatching { apply() }.onSuccess {
            withContext(Dispatchers.Main) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(successRes),
                    Toast.LENGTH_SHORT
                )
            }
        }.onFailure {
            Napier.e("dns endpoint delete failed", it)
            withContext(Dispatchers.Main) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.status_failing),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }
}
