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
package com.celzero.bravedns.ui.dialog

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.celzero.bravedns.R
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.bottomsheet.RuleSheetTextFieldRow
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetActionRow
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetCard
import com.celzero.bravedns.ui.compose.theme.RethinkSecondaryActionStyle
import com.celzero.bravedns.util.UIUtils.getDurationInHumanReadableFormat
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.util.ErrorMessages
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WgAddPeerDialog(
    configId: Int,
    wgPeer: Peer?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEditing = wgPeer != null

    WgDialog(onDismissRequest = onDismiss) {
        WgDialogColumn(
            scrollable = true,
            verticalSpacing = Dimensions.spacingMd
        ) {
            var publicKey by remember { mutableStateOf(wgPeer?.getPublicKey()?.base64()?.tos().orEmpty()) }
            var presharedKey by remember {
                mutableStateOf(
                    if (wgPeer?.getPreSharedKey()?.isPresent == true) {
                        wgPeer.getPreSharedKey().get().base64().tos().orEmpty()
                    } else {
                        ""
                    }
                )
            }
            var allowedIps by remember {
                mutableStateOf(wgPeer?.getAllowedIps()?.joinToString { it.toString() }.orEmpty())
            }
            var endpoint by remember {
                mutableStateOf(
                    if (wgPeer?.getEndpoint()?.isPresent == true) {
                        wgPeer.getEndpoint().get().toString()
                    } else {
                        ""
                    }
                )
            }
            var keepAlive by remember {
                mutableStateOf(
                    if (wgPeer?.persistentKeepalive?.isPresent == true) {
                        wgPeer.persistentKeepalive.get().toString()
                    } else {
                        ""
                    }
                )
            }
            var keepAliveHint by remember {
                mutableStateOf(
                    if (wgPeer?.persistentKeepalive?.isPresent == true) {
                        getDurationInHumanReadableFormat(context, wgPeer.persistentKeepalive.get())
                    } else {
                        ""
                    }
                )
            }

            RethinkBottomSheetCard {
                Text(text = stringResource(R.string.add_peer), style = MaterialTheme.typography.titleLarge)
                RuleSheetTextFieldRow(
                    value = publicKey,
                    onValueChange = { publicKey = it },
                    label = { Text(text = stringResource(R.string.lbl_public_key)) },
                    keyboardType = KeyboardType.Password
                )
                RuleSheetTextFieldRow(
                    value = presharedKey,
                    onValueChange = { presharedKey = it },
                    label = { Text(text = stringResource(R.string.lbl_preshared_key)) },
                    keyboardType = KeyboardType.Password
                )
                RuleSheetTextFieldRow(
                    value = keepAlive,
                    onValueChange = { value ->
                        keepAlive = value
                        keepAliveHint =
                            value.toIntOrNull()?.let { getDurationInHumanReadableFormat(context, it) }
                                .orEmpty()
                    },
                    label = { Text(text = stringResource(R.string.lbl_persistent_keepalive)) },
                    keyboardType = KeyboardType.Number
                )
                if (keepAliveHint.isNotBlank()) {
                    Text(
                        text = keepAliveHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RuleSheetTextFieldRow(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(text = stringResource(R.string.parse_error_inet_endpoint)) },
                    keyboardType = KeyboardType.Password
                )
                RuleSheetTextFieldRow(
                    value = allowedIps,
                    onValueChange = { allowedIps = it },
                    label = { Text(text = stringResource(R.string.lbl_allowed_ips)) },
                    keyboardType = KeyboardType.Text
                )
            }
            RethinkBottomSheetActionRow(
                primaryText = stringResource(R.string.lbl_save),
                onPrimaryClick = {
                    scope.launch {
                        savePeer(
                            context = context,
                            configId = configId,
                            wgPeer = wgPeer,
                            isEditing = isEditing,
                            publicKey = publicKey,
                            presharedKey = presharedKey,
                            allowedIps = allowedIps,
                            endpoint = endpoint,
                            keepAlive = keepAlive,
                            onSuccess = onDismiss,
                            onError = { message ->
                                Utilities.showToastUiCentered(context, message, Toast.LENGTH_SHORT)
                            }
                        )
                    }
                },
                secondaryText = stringResource(R.string.lbl_dismiss),
                onSecondaryClick = onDismiss,
                secondaryStyle = RethinkSecondaryActionStyle.TEXT
            )
        }
    }
}

private suspend fun savePeer(
    context: android.content.Context,
    configId: Int,
    wgPeer: Peer?,
    isEditing: Boolean,
    publicKey: String,
    presharedKey: String,
    allowedIps: String,
    endpoint: String,
    keepAlive: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    try {
        val builder = Peer.Builder()
        if (allowedIps.isNotEmpty()) builder.parseAllowedIPs(allowedIps)
        if (endpoint.isNotEmpty()) builder.parseEndpoint(endpoint)
        if (keepAlive.isNotEmpty()) builder.parsePersistentKeepalive(keepAlive)
        if (presharedKey.isNotEmpty()) builder.parsePreSharedKey(presharedKey)
        if (publicKey.isNotEmpty()) builder.parsePublicKey(publicKey)
        val newPeer = builder.build()

        withContext(Dispatchers.IO) {
            if (wgPeer != null && isEditing) {
                WireguardManager.deletePeer(configId, wgPeer)
            }
            WireguardManager.addPeer(configId, newPeer)
        }
        onSuccess()
    } catch (e: Throwable) {
        Napier.e("Error while adding peer", e)
        onError(ErrorMessages[context, e])
    }
}
