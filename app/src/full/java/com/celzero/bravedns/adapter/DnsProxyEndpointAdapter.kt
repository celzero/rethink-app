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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.service.FirewallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DnsProxyEndpointAdapter"

@Composable
fun DnsProxyEndpointRow(endpoint: DnsProxyEndpoint, appConfig: AppConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var explanation by remember(endpoint.id) { mutableStateOf("") }
    var infoDialog by remember(endpoint.id) { mutableStateOf<DnsInfoDialogModel?>(null) }
    var deleteDialog by remember(endpoint.id) { mutableStateOf<DnsDeleteDialogModel?>(null) }

    LaunchedEffect(endpoint.id, endpoint.proxyName, endpoint.proxyAppName) {
        val appName =
            withContext(Dispatchers.IO) {
                FirewallManager.getAppInfoByPackage(endpoint.proxyAppName)?.appName
            }
        val defaultName = context.getString(R.string.cd_custom_dns_proxy_default_app)
        val resolvedAppName =
            if (endpoint.proxyName != defaultName) {
                appName ?: defaultName
            } else {
                endpoint.proxyAppName ?: defaultName
            }
        explanation = endpoint.getExplanationText(context, resolvedAppName)
    }

    DnsEndpointRow(
        title = endpoint.proxyName,
        supporting = explanation.ifEmpty { null },
        selected = endpoint.isSelected,
        action = if (endpoint.isDeletable()) DnsRowAction.Delete else DnsRowAction.Info,
        selection = DnsRowSelection.Radio,
        onActionClick = {
            if (endpoint.isDeletable()) {
                deleteDialog =
                    DnsDeleteDialogModel(
                        id = endpoint.id,
                        titleRes = R.string.dns_proxy_remove_dialog_title,
                        messageRes = R.string.dns_proxy_remove_dialog_message,
                        successRes = R.string.dns_proxy_remove_success
                    )
            } else {
                scope.launch(Dispatchers.IO) {
                    val app =
                        FirewallManager.getAppInfoByPackage(endpoint.getPackageName())?.appName
                    val message =
                        if (!app.isNullOrEmpty()) {
                            context.getString(
                                R.string.dns_proxy_dialog_message,
                                app,
                                endpoint.proxyIP,
                                endpoint.proxyPort.toString()
                            )
                        } else {
                            context.getString(
                                R.string.dns_proxy_dialog_message_no_app,
                                endpoint.proxyIP,
                                endpoint.proxyPort.toString()
                            )
                        }
                    withContext(Dispatchers.Main) {
                        infoDialog =
                            DnsInfoDialogModel(
                                title = endpoint.proxyName,
                                message = message,
                                copyValue = endpoint.proxyIP,
                                copyToastRes = R.string.info_dialog_copy_toast_msg
                            )
                    }
                }
            }
        },
        onSelectionChange = {
            launchDnsEndpointSelectionUpdate(scope, context, TAG) {
                endpoint.isSelected = true
                appConfig.handleDnsProxyChanges(endpoint)
            }
        }
    )

    deleteDialog?.let { model ->
        DnsDeleteDialog(
            model = model,
            onDismiss = { deleteDialog = null },
            onConfirm = { id ->
                launchDnsEndpointDelete(scope, context, model.successRes) {
                    appConfig.deleteDnsProxyEndpoint(id)
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
