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
import com.celzero.bravedns.database.DnsCryptEndpoint

private const val TAG = "DnsCryptEndpointAdapter"

@Composable
fun DnsCryptRow(endpoint: DnsCryptEndpoint, appConfig: AppConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val explanation =
        rememberDnsStatusExplanation(
            key = endpoint.id,
            isSelected = endpoint.isSelected,
            smartDnsEnabled = appConfig.isSmartDnsEnabled(),
            tag = TAG
        )
    var infoDialog by remember(endpoint.id) { mutableStateOf<DnsInfoDialogModel?>(null) }
    var deleteDialog by remember(endpoint.id) { mutableStateOf<DnsDeleteDialogModel?>(null) }

    DnsEndpointRow(
        title = endpoint.dnsCryptName,
        supporting = explanation.ifEmpty { null },
        selected = endpoint.isSelected,
        action = if (endpoint.isDeletable()) DnsRowAction.Delete else DnsRowAction.Info,
        selection = DnsRowSelection.Radio,
        onActionClick = {
            if (endpoint.isDeletable()) {
                deleteDialog =
                    DnsDeleteDialogModel(
                        id = endpoint.id,
                        titleRes = R.string.dns_crypt_custom_url_remove_dialog_title,
                        messageRes = R.string.dns_crypt_url_remove_dialog_message,
                        successRes = R.string.dns_crypt_url_remove_success
                    )
            } else {
                val description =
                    if (endpoint.dnsCryptExplanation.isNullOrEmpty()) {
                        endpoint.dnsCryptURL
                    } else {
                        endpoint.dnsCryptURL + "\n\n" +
                            resolveDnsDescriptionText(context, endpoint.dnsCryptExplanation)
                    }
                infoDialog =
                    DnsInfoDialogModel(
                        title = endpoint.dnsCryptName,
                        message = description,
                        copyValue = endpoint.dnsCryptURL
                    )
            }
        },
        onSelectionChange = {
            launchDnsEndpointSelectionUpdate(scope, context, TAG) {
                endpoint.isSelected = true
                appConfig.handleDnscryptChanges(endpoint)
            }
        }
    )

    deleteDialog?.let { model ->
        DnsDeleteDialog(
            model = model,
            onDismiss = { deleteDialog = null },
            onConfirm = { id ->
                launchDnsEndpointDelete(scope, context, model.successRes) {
                    appConfig.deleteDnscryptEndpoint(id)
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
