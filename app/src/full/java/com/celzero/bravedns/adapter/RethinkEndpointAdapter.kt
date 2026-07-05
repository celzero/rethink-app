/*
 * Copyright 2022 RethinkDNS and its authors
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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.theme.RethinkMultiActionDialog
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.ui.compose.dns.ConfigureRethinkScreenType

private const val TAG = "RethinkEndpointAdapter"

private sealed class RethinkDialogState {
    data class Info(val endpoint: RethinkDnsEndpoint) : RethinkDialogState()
}

@Composable
fun RethinkEndpointRow(
    endpoint: RethinkDnsEndpoint,
    appConfig: AppConfig,
    onEditConfiguration: (ConfigureRethinkScreenType, String, String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val explanation =
        rememberDnsStatusExplanation(
            key = "${endpoint.url}:${endpoint.blocklistCount}",
            isSelected = endpoint.isActive,
            smartDnsEnabled = appConfig.isSmartDnsEnabled(),
            tag = TAG,
            statusTextMapper = { ctx, status ->
                if (status != R.string.dns_connected) {
                    ctx.getString(status).replaceFirstChar(Char::titlecase)
                } else if (endpoint.blocklistCount > 0) {
                    ctx.getString(
                        R.string.dns_connected_rethink_plus,
                        endpoint.blocklistCount.toString()
                    )
                } else {
                    ctx.getString(status)
                }
            }
        )
    var dialogState by remember(endpoint.url) { mutableStateOf<RethinkDialogState?>(null) }

    DnsEndpointRow(
        title = endpoint.name,
        supporting = explanation.ifEmpty { null },
        selected = endpoint.isActive,
        action = if (endpoint.isEditable(context)) DnsRowAction.Edit else DnsRowAction.Info,
        selection = DnsRowSelection.Radio,
        onActionClick = { dialogState = RethinkDialogState.Info(endpoint) },
        onSelectionChange = {
            launchDnsEndpointSelectionUpdate(scope, context, TAG) {
                endpoint.isActive = true
                appConfig.handleRethinkChanges(endpoint)
            }
        }
    )

    dialogState?.let { state ->
        val info = state as RethinkDialogState.Info
        val editEnabled = info.endpoint.isEditable(context)
        val positiveText =
            if (editEnabled) {
                context.getString(R.string.rt_edit_dialog_positive)
            } else {
                context.getString(R.string.dns_info_positive)
            }
        RethinkMultiActionDialog(
            onDismissRequest = { dialogState = null },
            title = info.endpoint.name,
            message = info.endpoint.url + "\n\n" + info.endpoint.desc,
            primaryText = positiveText,
            onPrimary = {
                dialogState = null
                if (editEnabled) {
                    openEditConfiguration(context, endpoint, onEditConfiguration)
                }
            },
            secondaryText = context.getString(R.string.dns_info_neutral),
            onSecondary = {
                clipboardCopy(
                    context,
                    info.endpoint.url,
                    context.getString(R.string.copy_clipboard_label)
                )
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.info_dialog_url_copy_toast_msg),
                    Toast.LENGTH_SHORT
                )
            }
        )
    }
}

private fun openEditConfiguration(
    context: Context,
    endpoint: RethinkDnsEndpoint,
    onEditConfiguration: (ConfigureRethinkScreenType, String, String) -> Unit
) {
    if (!VpnController.hasTunnel()) {
        Utilities.showToastUiCentered(
            context,
            context.getString(R.string.ssv_toast_start_rethink),
            Toast.LENGTH_SHORT
        )
        return
    }

    onEditConfiguration(ConfigureRethinkScreenType.REMOTE, endpoint.name, endpoint.url)
}
