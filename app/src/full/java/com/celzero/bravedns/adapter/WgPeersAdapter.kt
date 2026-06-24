/*
 * Copyright 2023 RethinkDNS and its authors
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.wireguard.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WgPeerRow(
    context: Context,
    configId: Int,
    wgPeer: Peer,
    onPeerChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val showDeleteDialog = remember(wgPeer.getPublicKey()) { mutableStateOf(false) }
    var showEditDialog by remember(wgPeer.getPublicKey()) { mutableStateOf(false) }
    val endpoint =
        if (wgPeer.getEndpoint().isPresent) {
            wgPeer.getEndpoint().get().toString()
        } else {
            null
        }
    val allowedIps =
        if (wgPeer.getAllowedIps().isNotEmpty()) {
            wgPeer.getAllowedIps().joinToString { it.toString() }
        } else {
            null
        }
    val keepAlive =
        if (wgPeer.persistentKeepalive.isPresent) {
            UIUtils.getDurationInHumanReadableFormat(
                context,
                wgPeer.persistentKeepalive.get()
            )
        } else {
            null
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.lbl_peer),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    showEditDialog = true
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_edit_icon_grey),
                        contentDescription = null
                    )
                }
                IconButton(onClick = {
                    showDeleteDialog.value = true
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = null
                    )
                }
            }

            LabelValue(
                label = stringResource(id = R.string.lbl_public_key),
                value = wgPeer.getPublicKey().base64().tos().orEmpty()
            )
            if (!allowedIps.isNullOrEmpty()) {
                LabelValue(
                    label = stringResource(id = R.string.lbl_allowed_ips),
                    value = allowedIps
                )
            }
            if (!endpoint.isNullOrEmpty()) {
                LabelValue(
                    label = stringResource(id = R.string.parse_error_inet_endpoint),
                    value = endpoint
                )
            }
            if (!keepAlive.isNullOrEmpty()) {
                LabelValue(
                    label = stringResource(id = R.string.lbl_persistent_keepalive),
                    value = keepAlive
                )
            }
        }
    }

    if (showDeleteDialog.value) {
        val deleteTitle =
            context.getString(
                R.string.two_argument_space,
                context.getString(R.string.config_delete_dialog_title),
                context.getString(R.string.lbl_peer)
            )
        RethinkConfirmDialog(
            onDismissRequest = { showDeleteDialog.value = false },
            title = deleteTitle,
            message = context.getString(R.string.config_delete_dialog_desc),
            confirmText = deleteTitle,
            dismissText = context.getString(R.string.lbl_cancel),
            isConfirmDestructive = true,
            onConfirm = {
                showDeleteDialog.value = false
                deletePeer(context, scope, configId, wgPeer, onPeerChanged)
            },
            onDismiss = { showDeleteDialog.value = false }
        )
    }

    if (showEditDialog) {
        WgAddPeerDialog(
            configId = configId,
            wgPeer = wgPeer,
            onDismiss = {
                showEditDialog = false
                onPeerChanged()
            }
        )
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun deletePeer(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    configId: Int,
    wgPeer: Peer,
    onPeerChanged: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        WireguardManager.deletePeer(configId, wgPeer)
        withContext(Dispatchers.Main) { onPeerChanged() }
    }
}
