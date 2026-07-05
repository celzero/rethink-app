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

package com.celzero.bravedns.adapter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsCryptRelayEndpoint

private const val TAG = "DnsCryptRelayEndpointAdapter"

@Composable
fun RelayRow(endpoint: DnsCryptRelayEndpoint, appConfig: AppConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSelected by remember(endpoint.id) { mutableStateOf(endpoint.isSelected) }
    val explanation =
        rememberDnsStatusExplanation(
            key = endpoint.id,
            isSelected = isSelected,
            smartDnsEnabled = appConfig.isSmartDnsEnabled(),
            tag = TAG,
            pollIntervalMs = 1500L,
            requireTunnel = false,
            selectedFallbackText = null
        )
    var infoDialog by remember(endpoint.id) { mutableStateOf<DnsInfoDialogModel?>(null) }
    var deleteDialog by remember(endpoint.id) { mutableStateOf<DnsDeleteDialogModel?>(null) }

    val updateSelection: (Boolean) -> Unit = { checked ->
        isSelected = checked
        launchDnsEndpointSelectionUpdate(scope, context, TAG) {
            endpoint.isSelected = checked
            appConfig.handleDnsrelayChanges(endpoint)
        }
    }

    DnsEndpointRow(
        title = endpoint.dnsCryptRelayName,
        supporting = explanation.ifEmpty { null },
        selected = isSelected,
        action = if (endpoint.isDeletable()) DnsRowAction.Delete else DnsRowAction.Info,
        selection = DnsRowSelection.Checkbox,
        onActionClick = {
            if (endpoint.isDeletable()) {
                deleteDialog =
                    DnsDeleteDialogModel(
                        id = endpoint.id,
                        titleRes = R.string.dns_crypt_relay_remove_dialog_title,
                        messageRes = R.string.dns_crypt_relay_remove_dialog_message,
                        successRes = R.string.dns_crypt_relay_remove_success
                    )
            } else {
                val description =
                    if (endpoint.dnsCryptRelayExplanation.isNullOrEmpty()) {
                        endpoint.dnsCryptRelayURL
                    } else {
                        endpoint.dnsCryptRelayURL + "\n\n" +
                            resolveDnsDescriptionText(context, endpoint.dnsCryptRelayExplanation)
                    }
                infoDialog =
                    DnsInfoDialogModel(
                        title = endpoint.dnsCryptRelayName,
                        message = description,
                        copyValue = endpoint.dnsCryptRelayURL
                    )
            }
        },
        onSelectionChange = updateSelection
    )

    deleteDialog?.let { model ->
        DnsDeleteDialog(
            model = model,
            onDismiss = { deleteDialog = null },
            onConfirm = { id ->
                launchDnsEndpointDelete(scope, context, model.successRes) {
                    appConfig.deleteDnscryptRelayEndpoint(id)
                }
                deleteDialog = null
            }
        )
    }

    infoDialog?.let { model ->
        DnsInfoDialog(
            model = model,
            onDismiss = { infoDialog = null }
        )
    }
}
